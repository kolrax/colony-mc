package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Le joueur fantôme (refonte, partie 7) : un faux joueur sans écran que les agents pilotent par la commande {@code /fantome}
 * pour jouer une vraie partie et voir ce qu'un joueur verrait. Chaque geste passe par le chemin d'un vrai joueur : la pose
 * d'un bloc par {@code useItemOn}, le clic droit par {@code interactOn}, les boutons de l'écran du poste par
 * {@link Bureau.Entite#action}, ceux de l'entretien par {@link Travailleur#commande}, la caisse et le dépôt par le
 * {@link Bureau.Menu} (le clic maj de l'écran). Ce que le serveur lui envoie (messages, écrans, paroles) est gardé pour le
 * rapport, qui écrit un JSON dans {@code <dossier du jeu>/fantome/}. Réservé aux op, et actif seulement en développement ou
 * si {@code reglages.json} contient {@code "fantome": true}.
 */
public final class Fantome {
    private Fantome() {}

    static final UUID ID = UUID.nameUUIDFromBytes("colonie_pnj:fantome".getBytes(StandardCharsets.UTF_8));
    static final String NOM = "Fantome";
    static final int MAX_CASSES = 5000, MAX_MESSAGES = 500, MAX_ECRANS = 100;
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static Joueur joueur;
    /** Le poste posé par le fantôme (null : celui de ses ouvriers). */
    static BlockPos poste;
    static final List<JsonObject> casses = new ArrayList<>();
    static final Deque<JsonObject> messages = new ArrayDeque<>(), ecrans = new ArrayDeque<>();
    /** Plan D : ce que sa carte (Xaero) montrerait, la dernière liste de ses ouvriers reçue (null : rien reçu). */
    static volatile List<Reperes.Repere> carte;

    static boolean actif() { return !FMLEnvironment.production || Reglages.actuel.fantome; }

    static void enregistrer() {
        NeoForge.EVENT_BUS.addListener(Fantome::commandes);
        NeoForge.EVENT_BUS.addListener(Fantome::evenement);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> retirer(e.getServer()));
    }

    // --- Le faux joueur ---------------------------------------------------------------------------------

    /** Un faux joueur qui garde ce qu'il reçoit : ses messages (dans le chat ou au-dessus de la barre) et les écrans du mod. */
    static final class Joueur extends FakePlayer {
        Joueur(ServerLevel l) {
            super(l, new GameProfile(ID, NOM));
            this.connection = new Ecoute(l.getServer(), this);
        }

        /** FakePlayer les jette : ici, ils partent comme pour un vrai joueur (et l'écoute les garde). */
        @Override
        public void displayClientMessage(Component c, boolean barre) { sendSystemMessage(c, barre); }

        @Override
        public boolean isFakePlayer() { return false; } // les ouvriers le traitent comme un joueur
    }

    /** La « connexion » du fantôme : rien ne part sur le réseau, tout ce qui lui est envoyé et qui compte est gardé. */
    static final class Ecoute extends ServerGamePacketListenerImpl {
        Ecoute(MinecraftServer s, ServerPlayer p) {
            super(s, new Connection(PacketFlow.SERVERBOUND) {
                @Override
                public void setListenerForServerboundHandshake(PacketListener l) {}
            }, p, CommonListenerCookie.createInitial(p.getGameProfile(), false));
        }

        @Override
        public void tick() {}

        @Override
        public void disconnect(Component raison) {}

        @Override
        public void send(Packet<?> paquet) { recevoir(paquet); }

        @Override
        public void send(Packet<?> paquet, @Nullable PacketSendListener suite) { recevoir(paquet); }

        /** Il a « le mod » : les écrans et les paroles lui sont envoyés comme à un joueur équipé du modpack. */
        @Override
        public boolean hasChannel(ResourceLocation id) { return id.getNamespace().equals(ColoniePnj.MODID); }

        void recevoir(Packet<?> paquet) {
            long tick = player.serverLevel().getGameTime();
            if (paquet instanceof ClientboundSystemChatPacket m) {
                JsonObject o = new JsonObject();
                o.addProperty("tick", tick);
                o.addProperty("texte", m.content().getString());
                o.addProperty("barre", m.overlay());
                garder(messages, o, MAX_MESSAGES);
            } else if (paquet instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof CarteOuvriers.Paquet p) {
                carte = p.liste(); // toutes les 2 s : gardée à part, pour ne pas chasser les écrans
            } else if (paquet instanceof ClientboundCustomPayloadPacket c && c.payload().type().id().getNamespace().equals(ColoniePnj.MODID)) {
                JsonObject o = new JsonObject();
                o.addProperty("tick", tick);
                o.addProperty("ecran", c.payload().type().id().getPath());
                if (c.payload() instanceof Record r) {
                    for (var rc : r.getClass().getRecordComponents()) {
                        try {
                            Object v = rc.getAccessor().invoke(r);
                            if (rc.getName().equals("id") && v instanceof Integer id && player.level().getEntity(id) instanceof Travailleur t)
                                o.addProperty("pnj", t.prenom);
                            o.add(rc.getName(), json(v));
                        } catch (ReflectiveOperationException ignore) {}
                    }
                }
                garder(ecrans, o, MAX_ECRANS);
            }
        }
    }

    static void garder(Deque<JsonObject> d, JsonObject o, int max) {
        synchronized (d) {
            d.addLast(o);
            while (d.size() > max) d.removeFirst();
        }
    }

    static JsonElement json(Object v) {
        if (v instanceof Tag t) return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, t);
        if (v instanceof Number n) return new com.google.gson.JsonPrimitive(n);
        if (v instanceof Boolean b) return new com.google.gson.JsonPrimitive(b);
        if (v instanceof BlockPos p) return new com.google.gson.JsonPrimitive(p.toShortString());
        return new com.google.gson.JsonPrimitive(String.valueOf(v));
    }

    static Joueur apparaitre(ServerLevel l, Vec3 ou) {
        retirer(l.getServer());
        Joueur j = new Joueur(l);
        j.moveTo(ou.x, ou.y, ou.z, 0, 0);
        l.addNewPlayer(j); // dans le monde : il charge les chunks autour de lui, les ouvriers le voient, les monstres aussi
        listeDesJoueurs(l.getServer(), j, true); // et dans la liste des joueurs : son ouvrier le trouve pour lui écrire
        joueur = j;
        casses.clear();
        survie.evenements.clear();
        survie.coups.clear();
        chutes.clear();
        synchronized (messages) { messages.clear(); }
        synchronized (ecrans) { ecrans.clear(); }
        carte = null;
        return j;
    }

    static void retirer(MinecraftServer s) {
        Joueur j = joueur;
        if (j == null) return;
        joueur = null;
        poste = null;
        listeDesJoueurs(s, j, false);
        j.serverLevel().removePlayerImmediately(j, Entity.RemovalReason.DISCARDED);
    }

    /** La liste des joueurs du serveur n'a pas d'entrée publique pour un faux joueur : on l'y met directement. */
    @SuppressWarnings("unchecked")
    static void listeDesJoueurs(MinecraftServer s, ServerPlayer j, boolean ajouter) {
        try {
            PlayerList pl = s.getPlayerList();
            var f = PlayerList.class.getDeclaredField("players");
            f.setAccessible(true);
            var g = PlayerList.class.getDeclaredField("playersByUUID");
            g.setAccessible(true);
            List<ServerPlayer> liste = (List<ServerPlayer>) f.get(pl);
            Map<UUID, ServerPlayer> parId = (Map<UUID, ServerPlayer>) g.get(pl);
            liste.remove(j);
            parId.remove(j.getUUID());
            if (ajouter) { liste.add(j); parId.put(j.getUUID(), j); }
        } catch (ReflectiveOperationException | RuntimeException e) {
            ColoniePnj.LOG.warn("Fantôme : pas dans la liste des joueurs ({}) ; ses ouvriers ne pourront pas lui écrire", e.toString());
        }
    }

    static void deplacer(Joueur j, Vec3 ou) {
        j.moveTo(ou.x, ou.y, ou.z, j.getYRot(), j.getXRot());
        j.serverLevel().getChunkSource().move(j);
    }

    /** Il va se tenir à côté (un vrai joueur marcherait jusque-là) : les écrans exigent d'être à moins de 8 blocs. */
    static void approcher(Joueur j, Vec3 cible) {
        if (j.position().distanceToSqr(cible) > 9) deplacer(j, cible.add(1.5, 0, 0));
    }

    // --- Les blocs cassés par les ouvriers ---------------------------------------------------------------

    /** Toute casse passe par {@code Level.destroyBlock(pos, ..., ouvrier)}, qui annonce BLOCK_DESTROY avec l'ouvrier pour cause. */
    static void evenement(VanillaGameEvent e) {
        if (joueur == null || e.getVanillaEvent() != GameEvent.BLOCK_DESTROY) return;
        if (!(e.getContext().sourceEntity() instanceof Travailleur t) || !(e.getLevel() instanceof ServerLevel l)) return;
        BlockState st = e.getContext().affectedState();
        if (st == null || casses.size() >= MAX_CASSES) return;
        BlockPos p = BlockPos.containing(e.getEventPosition());
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        boolean persistante = st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT);
        JsonObject o = new JsonObject();
        o.addProperty("tick", l.getGameTime());
        o.addProperty("pos", p.toShortString());
        o.addProperty("bloc", id);
        o.addProperty("nature", Nature.classer(id, persistante));
        o.addProperty("village", l.structureManager().getStructureWithPieceAt(p, StructureTags.VILLAGE).isValid());
        o.addProperty("ouvrier", t.prenom);
        o.addProperty("metier", t.metier);
        casses.add(o);
    }

    /**
     * Le classement d'un bloc cassé (logique pure, testée en JUnit) : « naturel » (terrain, roche, minerai, plante, arbre
     * aux feuilles non persistantes), « culture » (le fruit d'un champ : le travail du fermier) ou « construit » (tout le
     * reste : ce qu'un ouvrier ne doit jamais casser hors guerre).
     */
    static final class Nature {
        private Nature() {}

        static final Set<String> NATURELS = Set.copyOf(List.of(
                "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "dripstone_block", "pointed_dripstone",
                "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay", "sand", "red_sand", "gravel",
                "sandstone", "red_sandstone", "terracotta", "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice",
                "obsidian", "magma_block", "netherrack", "soul_sand", "soul_soil", "basalt", "smooth_basalt", "blackstone", "end_stone",
                "amethyst_block", "budding_amethyst", "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
                "moss_block", "moss_carpet", "glow_lichen", "vine", "cave_vines", "cave_vines_plant", "hanging_roots", "spore_blossom",
                "azalea", "flowering_azalea", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "cobweb",
                "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "kelp", "kelp_plant",
                "lily_pad", "sugar_cane", "cactus", "bamboo", "sweet_berry_bush", "pumpkin", "melon", "cocoa",
                "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
                "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "torchflower",
                "mangrove_roots", "muddy_mangrove_roots", "crimson_nylium", "warped_nylium", "nether_wart_block", "warped_wart_block",
                "shroomlight", "sculk", "sculk_vein"));
        static final Set<String> CULTURES = Set.copyOf(List.of(
                "wheat", "carrots", "potatoes", "beetroots", "nether_wart", "melon_stem", "pumpkin_stem", "attached_melon_stem",
                "attached_pumpkin_stem", "torchflower_crop", "pitcher_crop"));

        /** {@code id} : « minecraft:stone » ; {@code feuillesPersistantes} : des feuilles posées à la main. */
        static String classer(String id, boolean feuillesPersistantes) {
            String n = id.startsWith("minecraft:") ? id.substring(10) : id;
            if (CULTURES.contains(n)) return "culture";
            if (n.endsWith("_leaves")) return feuillesPersistantes ? "construit" : "naturel";
            if (n.startsWith("stripped_") || n.contains("glazed")) return "construit";
            if (NATURELS.contains(n) || n.endsWith("_ore") || n.startsWith("infested_") || n.endsWith("_log") || n.endsWith("_stem") && !n.equals("mushroom_stem") && (n.startsWith("crimson") || n.startsWith("warped"))
                    || n.endsWith("_terracotta") || n.endsWith("_sapling") || n.endsWith("_mushroom")) return "naturel";
            return "construit";
        }
    }

    // --- Les blessures et les morts des ouvriers (plan E6) ------------------------------------------------

    /**
     * Plan E6, pur Java : chaque mise à terre (« blessure »), chaque relèvement seul et chaque mort d'un ouvrier du fantôme,
     * avec sa cause lisible, l'heure de jeu et le lieu ; puis les totaux du rapport (par cause, par jour, par ouvrier). Les
     * coups reçus debout sont seulement additionnés par cause (nombre, points de vie perdus).
     */
    static final class Survie {
        static final int MAX = 2000;
        static final Gson JSON = new Gson();
        final List<JsonObject> evenements = new ArrayList<>();
        final Map<String, double[]> coups = new TreeMap<>();

        /**
         * La cause d'après le type de dégâts ({@code fall}, {@code lava}, {@code mob_attack}…) et l'auteur (l'id de son
         * type d'entité, « minecraft:zombie », ou « joueur » ; null : aucun).
         */
        static String cause(String type, @Nullable String auteur) {
            String a = auteur == null ? null : auteur.startsWith("minecraft:") ? auteur.substring(10) : auteur;
            String base = switch (type) {
                case "fall", "stalagmite" -> "chute";
                case "lava" -> "lave";
                case "in_fire", "on_fire", "hot_floor", "campfire" -> "feu";
                case "drown" -> "noyade";
                case "in_wall", "cramming" -> "étouffement";
                case "starve" -> "faim";
                case "out_of_world" -> "vide";
                case "generic_kill" -> "à terre sans secours";
                case "explosion", "player_explosion", "bad_respawn_point" -> "explosion";
                case "freeze" -> "gel";
                case "cactus", "sweet_berry_bush" -> "épines";
                case "falling_block", "falling_anvil", "falling_stalactite" -> "bloc tombé";
                case "mob_attack", "mob_attack_no_aggro", "arrow", "mob_projectile", "thrown", "sting", "trident", "fireball",
                     "unattributed_fireball", "wither_skull", "sonic_boom", "player_attack" -> a == null ? "coup" : "joueur".equals(a) ? "joueur" : "monstre " + a;
                default -> type;
            };
            return a == null || base.equals("joueur") || base.startsWith("monstre ") ? base : base + " (" + a + ")";
        }

        void noter(String genre, String ouvrier, String metier, String cause, long tick, long jour, int heure, String pos) {
            if (evenements.size() >= MAX) return;
            JsonObject o = new JsonObject();
            o.addProperty("genre", genre);
            o.addProperty("ouvrier", ouvrier);
            o.addProperty("metier", metier);
            o.addProperty("cause", cause);
            o.addProperty("tick", tick);
            o.addProperty("jour", jour);
            o.addProperty("heure", heure);
            o.addProperty("pos", pos);
            evenements.add(o);
        }

        void coup(String cause, float perte) {
            double[] c = coups.computeIfAbsent(cause, k -> new double[2]);
            c[0]++;
            c[1] += Math.max(0, perte);
        }

        JsonArray liste(String genre) {
            JsonArray a = new JsonArray();
            for (JsonObject o : evenements) if (o.get("genre").getAsString().equals(genre)) a.add(o);
            return a;
        }

        JsonObject resume() {
            Map<String, Integer> blessures = new TreeMap<>(), morts = new TreeMap<>();
            Map<String, Map<String, Integer>> jours = new TreeMap<>(), ouvriers = new TreeMap<>();
            int nb = 0, nm = 0, nr = 0;
            for (JsonObject o : evenements) {
                String genre = o.get("genre").getAsString(), cause = o.get("cause").getAsString();
                String g = genre.equals("blessure") ? "blessures" : genre.equals("mort") ? "morts" : "releves_seuls";
                if (genre.equals("blessure")) { nb++; blessures.merge(cause, 1, Integer::sum); }
                else if (genre.equals("mort")) { nm++; morts.merge(cause, 1, Integer::sum); }
                else nr++;
                jours.computeIfAbsent(o.get("jour").getAsString(), k -> new TreeMap<>()).merge(g, 1, Integer::sum);
                ouvriers.computeIfAbsent(o.get("ouvrier").getAsString() + " (" + o.get("metier").getAsString() + ")", k -> new TreeMap<>()).merge(g, 1, Integer::sum);
            }
            JsonObject r = new JsonObject();
            r.addProperty("blessures", nb);
            r.addProperty("morts", nm);
            r.addProperty("releves_seuls", nr);
            r.add("blessures_par_cause", JSON.toJsonTree(blessures));
            r.add("morts_par_cause", JSON.toJsonTree(morts));
            r.add("par_jour", JSON.toJsonTree(jours));
            r.add("par_ouvrier", JSON.toJsonTree(ouvriers));
            JsonObject c = new JsonObject();
            for (var e : coups.entrySet()) {
                JsonObject x = new JsonObject();
                x.addProperty("nombre", (int) e.getValue()[0]);
                x.addProperty("vie_perdue", Math.round(e.getValue()[1]));
                c.add(e.getKey(), x);
            }
            r.add("coups_par_cause", c);
            return r;
        }
    }

    static final Survie survie = new Survie();
    /** La cause de sa dernière mise à terre, par ouvrier : la mort au bout du temps à terre la rappelle. */
    static final Map<UUID, String> chutes = new java.util.HashMap<>();

    /** Un ouvrier du fantôme (rien sinon : une comparaison par coup, pour 50 ouvriers et plus). */
    static boolean suivi(Travailleur t) {
        Joueur j = joueur;
        return j != null && !t.level().isClientSide && j.getUUID().equals(t.chef);
    }

    static String cause(Travailleur t, net.minecraft.world.damagesource.DamageSource src) {
        String type = src.typeHolder().unwrapKey().map(k -> k.location().getPath()).orElse("autre");
        Entity e = src.getEntity();
        String auteur = e == null ? null : e instanceof net.minecraft.world.entity.player.Player ? "joueur" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return Survie.cause(type, auteur);
    }

    static void noter(Travailleur t, String genre, String cause) {
        long jt = t.level().getDayTime();
        survie.noter(genre, t.prenom, t.metier, cause, t.level().getGameTime(), jt / 24000, (int) ((jt + 6000) % 24000 / 1000), t.blockPosition().toShortString());
    }

    /** Il tombe à terre ({@link Travailleur#die} sans mort définitive). */
    static void aTerre(Travailleur t, net.minecraft.world.damagesource.DamageSource src) {
        if (!suivi(t)) return;
        String c = cause(t, src);
        chutes.put(t.getUUID(), c);
        noter(t, "blessure", c);
    }

    /** Il se relève seul au bout de son temps à terre ({@link Blessures#seRelever}). */
    static void releveSeul(Travailleur t) {
        if (suivi(t)) noter(t, "releve_seul", chutes.getOrDefault(t.getUUID(), "?"));
    }

    /** Sa mort définitive : au bout du temps à terre, la cause rappelle ce qui l'y avait mis. */
    static void mort(Travailleur t, net.minecraft.world.damagesource.DamageSource src) {
        if (!suivi(t)) return;
        String c = cause(t, src), chute = chutes.remove(t.getUUID());
        if (c.equals("à terre sans secours") && chute != null) c += ", après " + chute;
        noter(t, "mort", c);
    }

    /** Un coup reçu (debout ou à terre) : seulement additionné par cause. */
    static void coup(Travailleur t, net.minecraft.world.damagesource.DamageSource src, float perte) {
        if (suivi(t) && perte > 0) survie.coup(cause(t, src), perte);
    }

    // --- La commande -------------------------------------------------------------------------------------

    static void commandes(RegisterCommandsEvent e) {
        LiteralArgumentBuilder<CommandSourceStack> f = Commands.literal("fantome").requires(s -> s.hasPermission(2) && actif());
        f.then(Commands.literal("apparait")
                .executes(c -> apparait(c, "spawn"))
                .then(Commands.literal("village").executes(c -> apparait(c, "village")))
                .then(Commands.literal("foret").executes(c -> apparait(c, "foret")))
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> apparait(c, "pos"))));
        f.then(Commands.literal("va").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
            Joueur j = moi(c);
            BlockPos p = pos(c, j, "pos");
            deplacer(j, Vec3.atBottomCenterOf(p));
            return ok(c, "Le fantôme est en " + p.toShortString() + ".");
        })));
        f.then(Commands.literal("pose").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("objet", ResourceLocationArgument.id()).suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), b))
                        .executes(c -> {
                            Joueur j = moi(c);
                            Item item = objet(c);
                            BlockPos p = pos(c, j, "pos");
                            return ok(c, "Pose de " + BuiltInRegistries.ITEM.getKey(item) + " en " + p.toShortString() + " : " + poser(j, p, item));
                        }))));
        f.then(Commands.literal("donne").then(Commands.argument("objet", ResourceLocationArgument.id()).suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), b))
                .executes(c -> donner(c, 1))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 64 * 36)).executes(c -> donner(c, IntegerArgumentType.getInteger(c, "n"))))));
        f.then(Commands.literal("clic").then(Commands.argument("cible", StringArgumentType.word()).executes(c -> {
            Joueur j = moi(c);
            Travailleur t = ouvrier(c, j);
            approcher(j, t.position());
            var r = j.interactOn(t, InteractionHand.MAIN_HAND);
            return ok(c, "Clic droit sur " + t.prenom + " : " + r + ".");
        })));
        // Les boutons de l'écran du poste, exactement comme le paquet ActionBureau (Reseau) les transmet.
        f.then(Commands.literal("bouton").then(Commands.argument("action", StringArgumentType.word())
                .executes(c -> bouton(c, StringArgumentType.getString(c, "action"), new CompoundTag()))
                .then(Commands.argument("donnees", CompoundTagArgument.compoundTag())
                        .executes(c -> bouton(c, StringArgumentType.getString(c, "action"), CompoundTagArgument.getCompoundTag(c, "donnees"))))));
        // Une mission confirmée à l'écran : Missions.donner, la fonction serveur de l'écran de confirmation.
        // id : une ligne du catalogue du métier (Metier.commandes) ; répétition : fois, jour ou stock ; horaires : jour, nuit, toujours, 8-12.
        f.then(Commands.literal("mission").then(Commands.argument("cible", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> mission(c, "fois", 1, "toujours"))
                .then(Commands.argument("repetition", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("fois", "jour", "stock"), b))
                        .executes(c -> mission(c, StringArgumentType.getString(c, "repetition"), 1, "toujours"))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0))
                                .executes(c -> mission(c, StringArgumentType.getString(c, "repetition"), IntegerArgumentType.getInteger(c, "n"), "toujours"))
                                .then(Commands.argument("horaires", StringArgumentType.word())
                                        .executes(c -> mission(c, StringArgumentType.getString(c, "repetition"), IntegerArgumentType.getInteger(c, "n"),
                                                StringArgumentType.getString(c, "horaires")))))))));
        // Les boutons de la fiche de l'ouvrier (ici, suivre, attendre, rentrer, annuler, licencier) : Missions.action.
        f.then(Commands.literal("bouton_ouvrier").then(Commands.argument("cible", StringArgumentType.word()).then(Commands.argument("action", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("ici", "suivre", "attendre", "rentrer", "annuler", "licencier"), b))
                .executes(c -> boutonOuvrier(c, new CompoundTag()))
                .then(Commands.argument("donnees", CompoundTagArgument.compoundTag()).executes(c -> boutonOuvrier(c, CompoundTagArgument.getCompoundTag(c, "donnees")))))));
        // La barre de commandes : BarreCommandes.executer, comme le paquet de la barre (complément facultatif : « Jean|minecraft:bread|8 »).
        f.then(Commands.literal("commande").then(Commands.argument("cible", StringArgumentType.word()).then(Commands.argument("ordre", StringArgumentType.word())
                .executes(c -> commandeBarre(c, ""))
                .then(Commands.argument("complement", StringArgumentType.greedyString()).executes(c -> commandeBarre(c, StringArgumentType.getString(c, "complement")))))));
        f.then(Commands.literal("paie").executes(c -> ranger(c, true, null)));
        f.then(Commands.literal("depose").executes(c -> ranger(c, false, null))
                .then(Commands.argument("objet", ResourceLocationArgument.id()).executes(c -> ranger(c, false, objet(c)))));
        f.then(Commands.literal("poste").executes(c -> poste(c, false)).then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> poste(c, true))));
        f.then(Commands.literal("embauche").then(Commands.argument("metier", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Metier.TOUS.keySet(), b))
                .executes(c -> embauche(c, 1))
                .then(Commands.argument("niveau", IntegerArgumentType.integer(1, 5)).executes(c -> embauche(c, IntegerArgumentType.getInteger(c, "niveau"))))));
        f.then(Commands.literal("vitesse").then(Commands.argument("ticks", IntegerArgumentType.integer(1)).executes(c -> {
            MinecraftServer s = c.getSource().getServer();
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "tick sprint " + IntegerArgumentType.getInteger(c, "ticks"));
            return ok(c, "Le temps file : " + IntegerArgumentType.getInteger(c, "ticks") + " ticks.");
        })));
        f.then(Commands.literal("rapport").executes(c -> rapport(c, "rapport"))
                .then(Commands.argument("nom", StringArgumentType.word()).executes(c -> rapport(c, StringArgumentType.getString(c, "nom")))));
        f.then(Commands.literal("disparait").executes(c -> { retirer(c.getSource().getServer()); return ok(c, "Le fantôme est parti."); }));
        // Préparer un terrain (développement) : un vieux champ de blé sec, déjà retenu par un fermier ; un potager du fantôme
        // (houe puis graines, case par case, comme un joueur). Le rapport décrit ensuite ces terrains (« terrains »).
        f.then(Commands.literal("champ_sec").then(Commands.argument("cible", StringArgumentType.word()).then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("cote", IntegerArgumentType.integer(3, 15)).executes(c -> champSec(c, IntegerArgumentType.getInteger(c, "cote")))))));
        f.then(Commands.literal("potager").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("longueur", IntegerArgumentType.integer(1, 16)).then(Commands.argument("largeur", IntegerArgumentType.integer(1, 16))
                        .then(Commands.argument("objet", ResourceLocationArgument.id()).executes(c -> potager(c, IntegerArgumentType.getInteger(c, "longueur"),
                                IntegerArgumentType.getInteger(c, "largeur"))))))));
        e.getDispatcher().register(f);
    }

    static int ok(CommandContext<CommandSourceStack> c, String texte) {
        c.getSource().sendSuccess(() -> Component.literal(texte), false);
        return 1;
    }

    static Joueur moi(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (joueur == null || joueur.isRemoved()) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Pas de fantôme : /fantome apparait")).create();
        return joueur;
    }

    /** Les coordonnées relatives (~) partent du fantôme, pas de la console. */
    static BlockPos pos(CommandContext<CommandSourceStack> c, Joueur j, String nom) {
        return c.getArgument(nom, Coordinates.class).getBlockPos(c.getSource().withPosition(j.position()));
    }

    static Item objet(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(c, "objet");
        return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() ->
                new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Objet inconnu : " + id)).create());
    }

    /** Un ouvrier du fantôme : par prénom, par métier, ou « plus_proche ». */
    static Travailleur ouvrier(CommandContext<CommandSourceStack> c, Joueur j) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String n = StringArgumentType.getString(c, "cible");
        List<Travailleur> tous = ColoniePnj.tous(c.getSource().getServer());
        List<Travailleur> miens = tous.stream().filter(t -> j.getUUID().equals(t.chef)).toList();
        Comparator<Travailleur> pres = Comparator.comparingDouble(t -> t.distanceToSqr(j));
        // « mineur2 » : le 2e mineur embauché (plusieurs ouvriers du même métier, chacun sa mission).
        var rang = java.util.regex.Pattern.compile("([a-z_]+)(\\d+)").matcher(n);
        if (rang.matches()) {
            List<Travailleur> m = miens.stream().filter(x -> x.metier.equals(rang.group(1))).sorted(Comparator.comparingInt(Entity::getId)).toList();
            int k = Integer.parseInt(rang.group(2));
            if (k >= 1 && k <= m.size()) return m.get(k - 1);
        }
        Travailleur t = miens.stream().filter(x -> x.prenom.equalsIgnoreCase(n)).findFirst()
                .or(() -> miens.stream().filter(x -> x.metier.equalsIgnoreCase(n)).min(pres))
                .or(() -> n.equals("plus_proche") ? tous.stream().filter(x -> x.level() == j.level()).min(pres) : java.util.Optional.empty())
                .orElse(null);
        if (t == null) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Aucun ouvrier « " + n + " »")).create();
        return t;
    }

    static int apparait(CommandContext<CommandSourceStack> c, String ou) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel l = c.getSource().getServer().overworld();
        BlockPos p;
        switch (ou) {
            case "pos" -> p = BlockPosArgument.getBlockPos(c, "pos");
            case "village" -> {
                BlockPos v = l.findNearestMapStructure(StructureTags.VILLAGE, l.getSharedSpawnPos(), 100, false);
                if (v == null) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Aucun village à moins de 1600 blocs")).create();
                p = terrainLibre(l, v, true);
            }
            case "foret" -> {
                var b = l.findClosestBiome3d(h -> h.is(BiomeTags.IS_FOREST), l.getSharedSpawnPos(), 6400, 32, 64);
                if (b == null) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Aucune forêt à moins de 6400 blocs")).create();
                p = terrainLibre(l, b.getFirst(), false);
            }
            default -> p = surface(l, l.getSharedSpawnPos());
        }
        apparaitre(l, Vec3.atBottomCenterOf(p));
        return ok(c, "Le fantôme apparaît en " + p.toShortString() + (ou.equals("village") || ou.equals("foret") ? " (près d'un" + (ou.equals("village") ? " village" : "e forêt") + ")" : "") + ".");
    }

    static BlockPos surface(ServerLevel l, BlockPos p) {
        l.getChunk(p.getX() >> 4, p.getZ() >> 4); // sans le chunk chargé, la hauteur vaut le fond du monde
        return new BlockPos(p.getX(), l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    /** Un sol sec (terre, herbe, sable) près de {@code centre} : hors du village, à son bord, s'il s'agit d'un village. */
    static BlockPos terrainLibre(ServerLevel l, BlockPos centre, boolean horsVillage) {
        for (int r : horsVillage ? new int[]{48, 64, 80, 96} : new int[]{0, 8, 16, 24, 32})
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                BlockPos s = surface(l, centre.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)));
                BlockState sol = l.getBlockState(s.below());
                if (!(sol.is(BlockTags.DIRT) || sol.is(BlockTags.SAND)) || !l.getFluidState(s).isEmpty()) continue;
                if (horsVillage && l.structureManager().getStructureWithPieceAt(s, StructureTags.VILLAGE).isValid()) continue;
                return s;
            }
        return surface(l, centre);
    }

    /** Pose comme un joueur : l'objet en main, clic droit (accroupi, pour ne pas ouvrir ce qui est dessous) sur la case visée. */
    static String poser(Joueur j, BlockPos p, Item item) {
        approcher(j, Vec3.atBottomCenterOf(p));
        int i = j.getInventory().findSlotMatchingItem(new ItemStack(item));
        ItemStack s = i >= 0 ? j.getInventory().getItem(i) : new ItemStack(item);
        if (i >= 0) j.getInventory().setItem(i, ItemStack.EMPTY);
        ItemStack avant = j.getMainHandItem();
        j.setItemInHand(InteractionHand.MAIN_HAND, s);
        j.setShiftKeyDown(true);
        var r = j.gameMode.useItemOn(j, j.serverLevel(), s, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5), Direction.UP, p, false));
        j.setShiftKeyDown(false);
        ItemStack reste = j.getMainHandItem();
        j.setItemInHand(InteractionHand.MAIN_HAND, avant);
        if (!reste.isEmpty() && i >= 0) j.getInventory().add(reste);
        return r.toString();
    }

    static int donner(CommandContext<CommandSourceStack> c, int n) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Item item = objet(c);
        for (int reste = n; reste > 0; ) {
            int k = Math.min(reste, item.getDefaultMaxStackSize());
            if (!j.getInventory().add(new ItemStack(item, k))) break;
            reste -= k;
        }
        return ok(c, "Le fantôme reçoit " + n + " " + BuiltInRegistries.ITEM.getKey(item) + ".");
    }

    @Nullable
    static Bureau.Entite bureau(Joueur j) {
        BlockPos p = poste;
        if (p == null)
            p = ColoniePnj.pnjDe(j.server, j.getUUID()).stream().map(t -> t.bureau).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return p != null && j.level().getBlockEntity(p) instanceof Bureau.Entite b ? b : null;
    }

    static Bureau.Entite bureauOuErreur(Joueur j) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Bureau.Entite b = bureau(j);
        if (b == null) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Le fantôme n'a pas de poste : /fantome poste")).create();
        approcher(j, b.getBlockPos().getCenter());
        return b;
    }

    static int bouton(CommandContext<CommandSourceStack> c, String action, CompoundTag d) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Bureau.Entite b = bureauOuErreur(j);
        b.action(j, action, d);
        return ok(c, "Bouton « " + action + " » du poste " + d + ".");
    }

    /** Une mission confirmée à l'écran : {@link Missions#donner}, sans lieu imposé (il choisit son lieu). */
    static int mission(CommandContext<CommandSourceStack> c, String repetition, int n, String horaires) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Travailleur t = ouvrier(c, j);
        approcher(j, t.position());
        var r = Missions.donner(j, t, StringArgumentType.getString(c, "id"), repetition, n, horaires, null);
        return ok(c, (r.ok() ? "Mission acceptée par " : "Mission refusée par ") + t.prenom + " : " + r.texte());
    }

    /** Un bouton de la fiche de l'ouvrier ({@link Missions#action}) ; « ici » : là où se tient le fantôme. */
    static int commandeBarre(CommandContext<CommandSourceStack> c, String complement) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Travailleur t = ouvrier(c, j);
        approcher(j, t.position());
        String ordre = StringArgumentType.getString(c, "ordre");
        String r = BarreCommandes.executer(j, t, ordre, complement);
        return ok(c, "Commande « " + ordre + (complement.isEmpty() ? "" : " / " + complement) + " » à " + t.prenom + " : « " + r + " ».");
    }

    static int boutonOuvrier(CommandContext<CommandSourceStack> c, CompoundTag d) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Travailleur t = ouvrier(c, j);
        String action = StringArgumentType.getString(c, "action");
        if (!action.equals("ici")) approcher(j, t.position());
        Missions.action(j, t, action, d);
        return ok(c, "Bouton « " + action + " » de la fiche de " + t.prenom + ".");
    }

    /** La caisse (pièces) ou le dépôt : comme le clic maj d'un joueur sur ses cases, dans l'écran du dépôt. */
    static int ranger(CommandContext<CommandSourceStack> c, boolean pieces, @Nullable Item seul) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Bureau.Entite b = bureauOuErreur(j);
        var m = (Bureau.Menu) b.createMenu(0, j.getInventory(), j);
        int n = 0;
        for (int i = 36; i < m.slots.size(); i++) {
            ItemStack s = m.slots.get(i).getItem();
            if (s.isEmpty() || (Reglages.piece(s) >= 0) != pieces || seul != null && !s.is(seul)) continue;
            int avant = s.getCount();
            m.quickMoveStack(j, i);
            n += avant - m.slots.get(i).getItem().getCount();
        }
        m.removed(j);
        return ok(c, n + (pieces ? " pièces versées à la caisse." : " objets déposés au dépôt."));
    }

    static int poste(CommandContext<CommandSourceStack> c, boolean ici) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        BlockPos p = ici ? pos(c, j, "pos") : surface(j.serverLevel(), j.blockPosition().east(2));
        String r = poser(j, p, ColoniePnj.BUREAU_OBJET.get());
        if (!(j.level().getBlockEntity(p) instanceof Bureau.Entite b))
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Bureau non posé en " + p.toShortString() + " (" + r + ")")).create();
        poste = p;
        b.action(j, "devenir_poste", new CompoundTag());
        return ok(c, "Poste posé en " + p.toShortString() + ".");
    }

    /** Un chercheur d'emploi arrive à côté ; le fantôme lui parle (clic droit) puis l'embauche (bouton de l'entretien). */
    static int embauche(CommandContext<CommandSourceStack> c, int niveau) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        String metier = StringArgumentType.getString(c, "metier");
        if (!Metier.existe(metier)) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Métier inconnu : " + metier)).create();
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(j.serverLevel());
        if (t == null) return 0;
        BlockPos p = surface(j.serverLevel(), j.blockPosition().south(2));
        t.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
        t.tirerAuSort(metier, niveau);
        j.serverLevel().addFreshEntity(t);
        j.interactOn(t, InteractionHand.MAIN_HAND);
        t.commande(j, "embaucher");
        boolean pris = j.getUUID().equals(t.chef);
        return ok(c, t.prenom + " (" + metier + ") " + (pris ? "est embauché." : "n'est pas embauché (voir les messages du fantôme)."));
    }

    // --- Les terrains préparés (développement) -----------------------------------------------------------

    /** Les terrains préparés par champ_sec et potager : {nom, type, min, max} (coins du sol), décrits dans le rapport. */
    static final List<JsonObject> terrains = new ArrayList<>();

    static void terrain(String nom, String type, BlockPos min, BlockPos max) {
        JsonObject o = new JsonObject();
        o.addProperty("nom", nom);
        o.addProperty("type", type);
        o.addProperty("min", min.toShortString());
        o.addProperty("max", max.toShortString());
        terrains.add(o);
    }

    /**
     * Un vieux champ de blé, comme laissé par une version d'avant ou une mare bouchée : un carré de {@code cote} cases de
     * terre labourée sèche (moisture 0), semée de blé d'âges mêlés, au sol de {@code pos}, sur un terrain aplani (5 cases
     * d'herbe autour, de la terre dessous, l'air dessus : aucune eau à moins de 5 blocs), et retenu par le fermier comme
     * s'il l'avait semé lui-même (la même fiche que {@code Fermier.Champ.retenir}).
     */
    static int champSec(CommandContext<CommandSourceStack> c, int cote) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Travailleur t = ouvrier(c, j);
        if (!t.metier.equals("fermier")) throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal(t.prenom + " n'est pas fermier")).create();
        ServerLevel l = j.serverLevel();
        BlockPos centre = pos(c, j, "pos");
        int h = cote / 2, m = h + 5;
        for (int x = -m; x <= m; x++)
            for (int z = -m; z <= m; z++) {
                l.getChunk((centre.getX() + x) >> 4, (centre.getZ() + z) >> 4);
                for (int y = -3; y <= -1; y++) {
                    BlockPos q = centre.offset(x, y, z);
                    if (!l.getFluidState(q).isEmpty() || !l.getBlockState(q).isCollisionShapeFullBlock(l, q)) l.setBlock(q, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
                }
                for (int y = 1; y <= 8; y++) l.setBlock(centre.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                boolean champ = Math.abs(x) <= h && Math.abs(z) <= h;
                l.setBlock(centre.offset(x, 0, z), champ ? net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        // Les plants après le terrain, sans relire leurs voisins : la lumière n'est pas encore recalculée, le jeu les
        // croirait dans le noir et les retirerait (35 plants sur 81 au premier essai).
        for (int x = -h; x <= h; x++)
            for (int z = -h; z <= h; z++)
                l.setBlock(centre.offset(x, 1, z), net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.CropBlock.AGE, Math.floorMod(x * 3 + z, 5)), 2 | 16);
        net.minecraft.nbt.ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
        CompoundTag fiche = new CompoundTag();
        fiche.putLong("c", centre.asLong());
        fiche.putString("p", new Fouille.Plan(cote, cote, 1, "north", false).texte());
        fiche.putString("g", "ble");
        champs.add(fiche);
        t.memoire.put("champs", champs);
        terrain("champ_sec", "champ", centre.offset(-h, 0, -h), centre.offset(h, 0, h));
        String s = Monde.structure(l, centre);
        return ok(c, "Vieux champ sec de " + cote + " sur " + cote + " en " + centre.toShortString() + ", retenu par " + t.prenom
                + (s != null ? " (dans une structure : " + s + ")" : "") + ".");
    }

    /** Un potager du fantôme : chaque case labourée à la houe (celle de son sac), puis semée de l'objet, comme un joueur. */
    static int potager(CommandContext<CommandSourceStack> c, int longueur, int largeur) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        Item graine = objet(c);
        BlockPos p0 = pos(c, j, "pos");
        Item houe = net.minecraft.world.item.Items.IRON_HOE;
        for (int i = 0; i < j.getInventory().getContainerSize(); i++)
            if (j.getInventory().getItem(i).getItem() instanceof net.minecraft.world.item.HoeItem) { houe = j.getInventory().getItem(i).getItem(); break; }
        int labourees = 0, semees = 0;
        for (int x = 0; x < longueur; x++)
            for (int z = 0; z < largeur; z++) {
                BlockPos q = p0.offset(x, 0, z);
                poser(j, q, houe);
                if (j.level().getBlockState(q).is(net.minecraft.world.level.block.Blocks.FARMLAND)) labourees++;
                poser(j, q, graine);
                if (j.level().getBlockState(q.above()).getBlock() instanceof net.minecraft.world.level.block.CropBlock) semees++;
            }
        terrain("potager", "potager", p0, p0.offset(longueur - 1, 0, largeur - 1));
        return ok(c, "Potager du fantôme en " + p0.toShortString() + " : " + labourees + " cases labourées, " + semees + " semées.");
    }

    /** Ce qu'un joueur verrait de chaque terrain préparé : la terre (labourée, humide, sèche), les plants, l'eau, la casse. */
    static JsonArray terrains(Joueur j) {
        ServerLevel l = j.serverLevel();
        List<Travailleur> fermiers = ColoniePnj.pnjDe(j.server, j.getUUID()).stream().filter(t -> t.metier.equals("fermier")).toList();
        JsonArray a = new JsonArray();
        for (JsonObject o : terrains) {
            BlockPos min = parse(o.get("min").getAsString()), max = parse(o.get("max").getAsString());
            int cases = 0, labourees = 0, humides = 0, seches = 0, plants = 0, murs = 0, aEux = 0;
            List<String> autres = new ArrayList<>(), eau = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(min, max)) {
                cases++;
                BlockState s = l.getBlockState(q);
                if (s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                    labourees++;
                    if (s.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) == net.minecraft.world.level.block.FarmBlock.MAX_MOISTURE) humides++;
                    else seches++;
                } else autres.add(q.toShortString() + " " + BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + (l.getFluidState(q).isEmpty() ? "" : " (eau)"));
                if (l.getBlockState(q.above()).getBlock() instanceof net.minecraft.world.level.block.CropBlock k) {
                    plants++;
                    if (k.isMaxAge(l.getBlockState(q.above()))) murs++;
                    BlockPos plant = q.above();
                    if (fermiers.stream().anyMatch(t -> Fermier.aLui(t, plant))) aEux++;
                }
            }
            // L'eau à 4 blocs du terrain (ce qui arrose), au niveau du sol ou juste dessus et dessous.
            for (BlockPos q : BlockPos.betweenClosed(min.offset(-4, -1, -4), max.offset(4, 1, 4)))
                if (!l.getFluidState(q).isEmpty()) eau.add(q.toShortString() + (l.getFluidState(q).isSource() ? " source" : " coule"));
            int casses = 0;
            List<String> detail = new ArrayList<>();
            for (JsonObject x : Fantome.casses) {
                BlockPos q = parse(x.get("pos").getAsString());
                if (q.getX() < min.getX() || q.getX() > max.getX() || q.getZ() < min.getZ() || q.getZ() > max.getZ() || Math.abs(q.getY() - min.getY()) > 2) continue;
                casses++;
                if (detail.size() < 40) detail.add(x.get("pos").getAsString() + " " + x.get("bloc").getAsString() + " par " + x.get("ouvrier").getAsString());
            }
            JsonObject r = o.deepCopy();
            r.addProperty("cases", cases);
            r.addProperty("terre_labouree", labourees);
            r.addProperty("humides", humides);
            r.addProperty("seches", seches);
            r.addProperty("plants", plants);
            r.addProperty("plants_murs", murs);
            // Les plants que ses fermiers tiennent pour les leurs (Fermier.aLui : hors structure, son poste le plus près).
            r.addProperty("plants_a_ses_fermiers", aEux);
            String st = Monde.structure(l, BlockPos.containing((min.getX() + max.getX()) / 2.0, min.getY(), (min.getZ() + max.getZ()) / 2.0));
            r.addProperty("structure", st == null ? "" : st);
            r.add("autres_sols", GSON.toJsonTree(autres.size() > 40 ? autres.subList(0, 40) : autres));
            r.add("eau_a_4_blocs", GSON.toJsonTree(eau.size() > 40 ? eau.subList(0, 40) : eau));
            r.addProperty("blocs_casses_par_les_ouvriers", casses);
            r.add("casses", GSON.toJsonTree(detail));
            a.add(r);
        }
        return a;
    }

    // --- Le rapport --------------------------------------------------------------------------------------

    static int rapport(CommandContext<CommandSourceStack> c, String nom) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Joueur j = moi(c);
        JsonObject r = rapport(j);
        Path f = FMLPaths.GAMEDIR.get().resolve("fantome").resolve(nom.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(r), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Rapport non écrit : " + e)).create();
        }
        return ok(c, "Rapport du fantôme écrit : " + f.toAbsolutePath());
    }

    static JsonObject rapport(Joueur j) {
        ServerLevel l = j.serverLevel();
        JsonObject r = new JsonObject();
        r.addProperty("tick", l.getGameTime());
        r.addProperty("jour", l.getDayTime() / 24000);
        r.addProperty("heure", (int) ((l.getDayTime() + 6000) % 24000 / 1000));
        JsonObject moi = new JsonObject();
        moi.addProperty("pos", j.blockPosition().toShortString());
        moi.add("inventaire", contenu(j.getInventory()));
        r.add("fantome", moi);

        Bureau.Entite b = bureau(j);
        JsonObject p = new JsonObject();
        if (b != null) {
            p.addProperty("pos", b.getBlockPos().toShortString());
            p.add("ecran", json(b.fiche(j))); // ce que l'écran du poste affiche
            p.add("caisse", contenu(b.caisse));
            p.add("depot", contenu(b.depot));
            // La réserve : ce qui ne tient plus dans les 27 cases du dépôt (compté dans les missions, pas dans « depot »).
            Map<String, Integer> reserve = new TreeMap<>();
            for (ItemStack s : b.reserveVue()) reserve.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
            p.add("reserve", GSON.toJsonTree(reserve));
        }
        r.add("poste", p);

        JsonArray ouvriers = new JsonArray();
        Map<String, Integer> production = new TreeMap<>();
        for (Travailleur t : ColoniePnj.pnjDe(j.server, j.getUUID())) {
            JsonObject o = new JsonObject();
            o.addProperty("prenom", t.prenom);
            o.addProperty("metier", t.metier);
            o.addProperty("au_dessus", t.getDisplayName().getString());
            o.addProperty("role", t.getEntityData().get(Travailleur.ROLE));
            o.addProperty("etat", t.getEntityData().get(Travailleur.ETAT));
            o.addProperty("statut", t.getEntityData().get(Travailleur.STATUT)); // le statut unique (au-dessus de la tête et au poste)
            o.addProperty("pos", t.blockPosition().toShortString());
            o.addProperty("mode", String.valueOf(t.mode));
            o.addProperty("action", t.action == null ? "" : t.action.nom);
            // Pourquoi il n'avance pas : le résultat de sa dernière action (« ok » ou la raison de l'échec), son blocage,
            // et sa mémoire de travail (puits, marche, lieu…).
            o.addProperty("dernier_resultat", t.resultat);
            o.addProperty("blocage", t.blocage == null ? "" : t.blocage);
            String mem = t.memoire.toString();
            o.addProperty("memoire", mem.length() > 1500 ? mem.substring(0, 1500) : mem);
            if (b != null) o.addProperty("distance_poste", (int) Math.sqrt(t.blockPosition().distSqr(b.getBlockPos())));
            o.add("fiche", json(t.fiche(false)));
            o.add("sac", contenu(t.inventaire));
            ouvriers.add(o);
        }
        r.add("ouvriers", ouvriers);

        synchronized (messages) { r.add("messages", tableau(messages)); }
        synchronized (ecrans) { r.add("ecrans", tableau(ecrans)); }
        // Plan D : les repères de ses ouvriers sur sa carte (null : aucune liste reçue).
        List<Reperes.Repere> vus = carte;
        if (vus != null) {
            JsonArray cj = new JsonArray();
            for (Reperes.Repere x : vus) {
                JsonObject o = new JsonObject();
                o.addProperty("prenom", x.prenom());
                o.addProperty("dim", x.dim());
                o.addProperty("pos", x.x() + ", " + x.y() + ", " + x.z());
                cj.add(o);
            }
            r.add("carte", cj);
        }

        // Les blocs cassés par les ouvriers à moins de 64 blocs du poste (tous s'il n'y a pas de poste).
        JsonArray cs = new JsonArray();
        Map<String, Integer> parNature = new TreeMap<>(), construits = new TreeMap<>();
        int village = 0;
        for (JsonObject o : casses) {
            BlockPos q = parse(o.get("pos").getAsString());
            int d = b == null ? 0 : (int) Math.sqrt(q.distSqr(b.getBlockPos()));
            if (d > 64) continue;
            JsonObject x = o.deepCopy();
            if (b != null) x.addProperty("distance_poste", d);
            cs.add(x);
            String nature = o.get("nature").getAsString();
            parNature.merge(nature, 1, Integer::sum);
            if (nature.equals("construit")) construits.merge(o.get("bloc").getAsString() + " par " + o.get("ouvrier").getAsString(), 1, Integer::sum);
            if (o.get("village").getAsBoolean()) village++;
            production.merge(o.get("metier").getAsString(), 1, Integer::sum);
        }
        r.add("casses", cs);
        if (!terrains.isEmpty()) r.add("terrains", terrains(j));
        // Plan E6 : chaque mise à terre et chaque mort, avec sa cause et l'heure de jeu.
        r.add("blessures", survie.liste("blessure"));
        r.add("releves_seuls", survie.liste("releve_seul"));
        r.add("morts", survie.liste("mort"));

        JsonObject resume = new JsonObject();
        resume.addProperty("ouvriers", ouvriers.size());
        resume.add("casses_par_nature", GSON.toJsonTree(parNature));
        resume.add("construits_casses", GSON.toJsonTree(construits));
        resume.addProperty("casses_dans_un_village", village);
        resume.add("blocs_casses_par_metier", GSON.toJsonTree(production));
        resume.addProperty("objets_au_depot", b == null ? 0 : total(b.depot));
        resume.addProperty("messages", r.getAsJsonArray("messages").size());
        resume.add("survie", survie.resume());
        r.add("resume", resume);
        return r;
    }

    static BlockPos parse(String s) {
        String[] x = s.split(",\\s*");
        return new BlockPos(Integer.parseInt(x[0]), Integer.parseInt(x[1]), Integer.parseInt(x[2]));
    }

    static JsonArray tableau(Iterable<JsonObject> l) {
        JsonArray a = new JsonArray();
        for (JsonObject o : l) a.add(o);
        return a;
    }

    static JsonObject contenu(Container c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        return GSON.toJsonTree(m).getAsJsonObject();
    }

    static int total(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }
}

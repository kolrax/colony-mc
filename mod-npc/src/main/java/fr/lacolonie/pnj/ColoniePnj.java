package fr.lacolonie.pnj;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod(ColoniePnj.MODID)
public class ColoniePnj {
    public static final String MODID = "colonie_pnj";
    public static final Logger LOG = LogUtils.getLogger();

    static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(MODID);
    static final DeferredRegister.Items OBJETS = DeferredRegister.createItems(MODID);
    static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    static final DeferredRegister<BlockEntityType<?>> BES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<Travailleur>> TRAVAILLEUR = ENTITES.register("travailleur",
            () -> EntityType.Builder.of(Travailleur::new, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).build("travailleur"));
    public static final DeferredBlock<Bureau.Bloc> BUREAU = BLOCS.register("bureau",
            () -> new Bureau.Bloc(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).ignitedByLava()));
    public static final DeferredItem<BlockItem> BUREAU_OBJET = OBJETS.registerSimpleBlockItem(BUREAU);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Bureau.Entite>> BUREAU_BE = BES.register("bureau",
            () -> BlockEntityType.Builder.of(Bureau.Entite::new, BUREAU.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<Bureau.Menu>> BUREAU_MENU = MENUS.register("bureau",
            () -> IMenuTypeExtension.create((id, inv, buf) -> new Bureau.Menu(id, inv)));

    /**
     * Garde chargés les chunks autour de chaque PNJ employé : il travaille même quand son chef n'est pas là.
     * Au démarrage, chaque PNJ retrouve ses chunks d'avant ; il lâche lui-même ceux qu'il n'occupe plus.
     */
    static final java.util.Map<UUID, it.unimi.dsi.fastutil.longs.LongSet> CHUNKS_D_AVANT = new java.util.concurrent.ConcurrentHashMap<>();
    public static final TicketController TICKETS = new TicketController(ResourceLocation.fromNamespaceAndPath(MODID, "travail"),
            (level, aide) -> aide.getEntityTickets().forEach((id, t) -> {
                var s = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(t.ticking());
                s.addAll(t.nonTicking());
                CHUNKS_D_AVANT.put(id, s);
            }));

    public ColoniePnj(IEventBus bus) {
        BLOCS.register(bus);
        OBJETS.register(bus);
        ENTITES.register(bus);
        BES.register(bus);
        MENUS.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(TRAVAILLEUR.get(), Travailleur.attributs().build()));
        bus.addListener((RegisterTicketControllersEvent e) -> e.register(TICKETS));
        bus.addListener(Reseau::enregistrer);
        bus.addListener(CarteOuvriers::enregistrer); // plan D : les ouvriers sur la carte (paquet facultatif)
        bus.addListener((RegisterCapabilitiesEvent e) ->
                e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BUREAU_BE.get(), (be, cote) -> new InvWrapper(be.depot)));
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(BUREAU_OBJET);
        });

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> {
            Reglages.charger();
            Macon.preparerDossier();
            PanneauWeb.demarrer(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            PanneauWeb.arreter();
            laisserFinirLaGeneration(e.getServer());
        });
        // Après un plantage, seul « Stopped » est envoyé : on ferme aussi le panneau là.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            PanneauWeb.arreter();
        });
        NeoForge.EVENT_BUS.addListener(ColoniePnj::commandes);
        Fantome.enregistrer(); // le joueur fantôme des essais (/fantome, refonte partie 7)
        NeoForge.EVENT_BUS.addListener(Guerrier::impact); // les flèches des guerriers ne blessent pas les passants
        NeoForge.EVENT_BUS.addListener(Defense::chefFrappe); // on frappe le chef : ses gens réagissent selon ses ordres (GardeChef)
        NeoForge.EVENT_BUS.addListener(Defense::viser); // le coup visé d'un joueur (pas le coup balayé)
        NeoForge.EVENT_BUS.addListener(Defense::depotOuvert); // un autre joueur ouvre le dépôt du poste : les guerriers le défendent (GardeDepot)
        NeoForge.EVENT_BUS.addListener(ColoniePnj::arriveeChercheurs);
        NeoForge.EVENT_BUS.addListener(Fermier::pietiner); // un ouvrier ne piétine jamais une terre labourée
        NeoForge.EVENT_BUS.addListener(Eleveur::butin); // la bête qu'abat un ouvrier : son butin va dans son sac
        // Filet de sécurité : un PNJ arrivé dans un chunk inactif (poussé, ou figé avant la 0.3.0) ne peut plus
        // déplacer ses chunks lui-même ; le serveur les recale toutes les 5 secondes.
        // Chaque partie est mesurée pour le banc de charge, et le tick se clôt ici (après celui des PNJ).
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            Charge mesure = Charge.ICI;
            long h = System.nanoTime();
            if (e.getServer().getTickCount() % 100 == 0) {
                for (Travailleur t : tous(e.getServer())) t.forcerChunk();
                h = mesure.mesurer(Charge.Partie.FILET, h);
            }
            mesure.clore();
            Itineraire.nouveauTick(); // le budget des recherches d'étapes repart pour le tick suivant
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Load e) -> Essais.choisir(e.getLevel()));
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            // Les monstres s'en prennent aussi aux PNJ (sauf à ceux qui sont à terre).
            if (!e.getLevel().isClientSide && e.getEntity() instanceof Monster m && !(m instanceof NeutralMob))
                m.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(m, Travailleur.class, 10, true, false, x -> !((Travailleur) x).aTerre));
        });
        // Relever un PNJ à terre sans devoir viser sa petite silhouette allongée.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem e) -> {
            if (e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND && Travailleur.releverAutour(e.getEntity())) {
                e.setCanceled(true);
                e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND && Travailleur.releverAutour(e.getEntity())) {
                e.setCanceled(true);
                e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            } else if (!e.getLevel().isClientSide) Travailleur.cederLit(e.getLevel(), e.getPos());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            var donnees = p.getPersistentData();
            // Une fois par joueur : l'accueil de la 1.0 (ouvriers à missions ; l'ancien message de la 0.8.0 parlait du chat libre, retiré).
            if (!donnees.getBoolean("colonie_pnj_accueil_v1")) {
                donnees.putBoolean("colonie_pnj_accueil_v1", true);
                p.sendSystemMessage(Component.literal("✦ La Colonie : pose ton poste (le bloc Bureau), puis embauche les chercheurs d'emploi d'un clic droit. "
                        + "Clic droit sur un ouvrier : sa fiche et ses missions. Touche B : la barre de commandes. Ton premier poste te donne le livret du chef.")
                        .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            }
        });
    }

    /**
     * À l'arrêt, Minecraft décharge tous les chunks dans une boucle qui peut tourner sans fin sur un chunk dont la
     * génération est en cours (vu une fois aux tests, un PNJ explorant du terrain neuf). On laisse donc d'abord finir
     * le travail des chunks en cours, 3 secondes au plus.
     */
    static void laisserFinirLaGeneration(MinecraftServer s) {
        long fin = System.nanoTime() + 3_000_000_000L, dernier = System.nanoTime();
        while (System.nanoTime() < fin && System.nanoTime() - dernier < 200_000_000L) {
            boolean fait = false;
            for (ServerLevel l : s.getAllLevels()) while (System.nanoTime() < fin && l.getChunkSource().pollTask()) fait = true;
            if (fait) dernier = System.nanoTime();
            else java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
        }
    }

    /** Tous les PNJ d'un joueur (ils sont toujours chargés, puisqu'ils gardent leur chunk). */
    public static List<Travailleur> pnjDe(MinecraftServer s, UUID chef) {
        List<Travailleur> l = new ArrayList<>();
        for (ServerLevel lvl : s.getAllLevels()) l.addAll(lvl.getEntities(TRAVAILLEUR.get(), t -> chef.equals(t.chef) && t.isAlive()));
        return l;
    }

    public static List<Travailleur> tous(MinecraftServer s) {
        List<Travailleur> l = new ArrayList<>();
        for (ServerLevel lvl : s.getAllLevels()) l.addAll(lvl.getEntities(TRAVAILLEUR.get(), Travailleur::isAlive));
        return l;
    }

    // --- Les chercheurs d'emploi arrivent à pied près des joueurs ------------------------------------

    static long secondes;

    static void arriveeChercheurs(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 != 0) return;
        secondes++;
        if (secondes % (60L * Reglages.actuel.chercheurs.minutes_entre_arrivees) != 0) return;
        ServerLevel monde = e.getServer().overworld();
        if (!monde.isDay()) return; // la nuit, les voyageurs ne s'aventurent pas sur les routes
        for (ServerPlayer p : monde.players()) {
            if (p.isSpectator()) continue;
            boolean affiche = affiche(monde, p);
            BlockPos village = village(monde, p);
            double chance = Recrutement.chance(Reglages.actuel.chercheurs.chance, Courrier.de(e.getServer()).reputation(p.getUUID()), affiche, village != null);
            if (monde.random.nextDouble() < chance) faireVenir(monde, p, village, affiche);
        }
    }

    /** Un village (sa cloche) à moins de tant de blocs d'un joueur : des chercheurs en viennent (lot 13). */
    static final int RAYON_VILLAGE = 96;

    /**
     * La cloche du village le plus proche, ou null. Seulement les points d'intérêt que le jeu connaît déjà (aucune
     * recherche de structure), et seulement au moment d'une arrivée possible : une fois toutes les 15 minutes par joueur.
     * Une cloche posée chez soi ne compte pas : à plus de 48 blocs du poste du joueur, et un villageois chargé à moins de
     * 32 blocs d'elle.
     */
    static BlockPos village(ServerLevel l, ServerPlayer p) {
        Courrier.Poste poste = Courrier.de(l.getServer()).poste(p.getUUID());
        BlockPos chezLui = poste != null && poste.monde().equals(l.dimension()) ? poste.pos() : null;
        return l.getPoiManager().findClosest(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING),
                b -> (chezLui == null || b.distSqr(chezLui) > 48 * 48)
                        && !l.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, new net.minecraft.world.phys.AABB(b).inflate(32)).isEmpty(),
                p.blockPosition(), RAYON_VILLAGE, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).orElse(null);
    }

    /**
     * Le joueur est chez lui (à moins de 96 blocs de son poste) et une affiche d'embauche (un panneau « On embauche »,
     * {@link Recrutement#affiche}) est à moins de 8 blocs du poste. On ne regarde que les blocs à données des chunks
     * chargés autour du poste, au moment d'une arrivée possible.
     */
    static boolean affiche(ServerLevel l, ServerPlayer p) {
        Courrier.Poste poste = Courrier.de(l.getServer()).poste(p.getUUID());
        if (poste == null || !poste.monde().equals(l.dimension()) || p.blockPosition().distSqr(poste.pos()) > 96 * 96) return false;
        BlockPos b = poste.pos();
        for (int cx = (b.getX() - 8) >> 4; cx <= (b.getX() + 8) >> 4; cx++)
            for (int cz = (b.getZ() - 8) >> 4; cz <= (b.getZ() + 8) >> 4; cz++) {
                if (!l.hasChunk(cx, cz)) continue;
                for (var be : l.getChunk(cx, cz).getBlockEntities().values())
                    if (be instanceof net.minecraft.world.level.block.entity.SignBlockEntity s && be.getBlockPos().distSqr(b) <= 64
                            && (Recrutement.affiche(texte(s.getFrontText())) || Recrutement.affiche(texte(s.getBackText())))) return true;
            }
        return false;
    }

    static String texte(net.minecraft.world.level.block.entity.SignText s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(s.getMessage(i, false).getString()).append(' ');
        return b.toString();
    }

    static void faireVenir(ServerLevel l, ServerPlayer p) { faireVenir(l, p, null, false); }

    /** Un chercheur arrive à 24 à 40 blocs du joueur ; d'un village voisin, il arrive du côté du village. */
    static void faireVenir(ServerLevel l, ServerPlayer p, BlockPos village, boolean affiche) {
        List<String> metiers = Metier.actifs();
        if (metiers.isEmpty()) return;
        long autour = l.getEntities(TRAVAILLEUR.get(), t -> t.mode == Travailleur.Mode.CHOMEUR && t.distanceTo(p) < 96).size();
        if (autour >= Reglages.actuel.chercheurs.max_autour) return;
        for (int essai = 0; essai < 10; essai++) {
            double angle = village != null ? Math.atan2(village.getZ() + 0.5 - p.getZ(), village.getX() + 0.5 - p.getX()) + (l.random.nextDouble() - 0.5) * 1.2
                    : l.random.nextDouble() * Math.PI * 2, d = 24 + l.random.nextInt(17);
            int x = (int) (p.getX() + Math.cos(angle) * d), z = (int) (p.getZ() + Math.sin(angle) * d);
            BlockPos pos = new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!l.isLoaded(pos) || !l.getFluidState(pos.below()).isEmpty() || !l.getBlockState(pos.below()).isSolidRender(l, pos.below())) continue;
            Travailleur t = TRAVAILLEUR.get().create(l);
            if (t == null) return;
            t.moveTo(x + 0.5, pos.getY(), z + 0.5, l.random.nextFloat() * 360, 0);
            if (!l.noCollision(t)) continue;
            t.tirerAuSort(metiers.get(l.random.nextInt(metiers.size())), 0);
            l.addFreshEntity(t);
            p.sendSystemMessage(Component.literal(annonce(Metier.nom(t.metier), village != null, affiche)).withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            return;
        }
    }

    /** « On dit qu'un mineur du village voisin a vu ton affiche et cherche du travail dans les environs… » */
    static String annonce(String metier, boolean village, boolean affiche) {
        return "On dit qu'un " + metier.toLowerCase() + (village ? " du village voisin" : "") + (affiche ? " a vu ton affiche et" : "") + " cherche du travail dans les environs…";
    }

    // --- Commandes d'admin : /pnj ---------------------------------------------------------------------

    static void commandes(RegisterCommandsEvent e) {
        // /rapport : pour tous les joueurs (essais), pas d'op requis.
        e.getDispatcher().register(Commands.literal("rapport").then(Commands.argument("texte", StringArgumentType.greedyString()).executes(c -> {
            String m = Rapports.envoyer(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "texte"));
            c.getSource().sendSuccess(() -> Component.literal(m), false);
            return 1;
        })));
        e.getDispatcher().register(Commands.literal("pnj").requires(s -> s.hasPermission(2))
                .then(Commands.literal("chercheur").then(Commands.argument("metier", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Metier.TOUS.keySet(), b))
                        .executes(c -> chercheur(c, 0, ""))
                        .then(Commands.argument("niveau", IntegerArgumentType.integer(1, 5)).executes(c -> chercheur(c, IntegerArgumentType.getInteger(c, "niveau"), ""))
                                .then(Commands.argument("skin", StringArgumentType.word())
                                        .executes(c -> chercheur(c, IntegerArgumentType.getInteger(c, "niveau"), StringArgumentType.getString(c, "skin")))))))
                .then(Commands.literal("recharger").executes(c -> {
                    Reglages.charger();
                    c.getSource().sendSuccess(() -> Component.literal("Réglages rechargés."), true);
                    return 1;
                }))
                .then(Commands.literal("liste").executes(c -> {
                    var l = tous(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal(l.size() + " PNJ :"), false);
                    for (Travailleur t : l)
                        c.getSource().sendSuccess(() -> Component.literal(" - " + t.prenom + " (" + t.metier + " " + t.niveau + "★, " + t.mode.lua
                                + (t.aTerre ? ", à terre" : "") + ") chef=" + (t.chefNom.isEmpty() ? "aucun" : t.chefNom)
                                + " pos=" + t.blockPosition().toShortString() + " action=" + (t.action == null ? "-" : t.action.nom)
                                + " faim=" + t.faim + " impayes=" + t.impayes + (t.besoin.isEmpty() ? "" : " besoin=« " + t.besoin + " »")), false);
                    return l.size();
                }))
                .then(Commands.literal("diag").then(Commands.argument("prenom", StringArgumentType.greedyString()).executes(c -> {
                    String n = StringArgumentType.getString(c, "prenom");
                    var l = tous(c.getSource().getServer()).stream().filter(t -> t.prenom.equalsIgnoreCase(n)).toList();
                    if (l.isEmpty()) { c.getSource().sendFailure(Component.literal("Aucun PNJ nommé " + n)); return 0; }
                    for (Travailleur t : l) for (String ligne : Itineraire.diagnostic(t)) c.getSource().sendSuccess(() -> Component.literal(ligne), false);
                    return 1;
                })))

                // Les arbres autour d'un point, et pourquoi tel bûcheron ne les prendrait pas (Bucheron.diagnosticArbres).
                .then(Commands.literal("arbres").then(Commands.argument("prenom", StringArgumentType.word()).then(Commands.argument("bois", StringArgumentType.word())
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c -> {
                            String n = StringArgumentType.getString(c, "prenom");
                            var l = tous(c.getSource().getServer()).stream().filter(t -> t.prenom.equalsIgnoreCase(n) && t.level() instanceof ServerLevel).toList();
                            if (l.isEmpty()) { c.getSource().sendFailure(Component.literal("Aucun PNJ nommé " + n)); return 0; }
                            if (!(Metier.catalogue("bucheron", StringArgumentType.getString(c, "bois")) instanceof Bucheron.Bois b)) { c.getSource().sendFailure(Component.literal("Bois inconnu")); return 0; }
                            BlockPos p = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos");
                            for (Travailleur t : l) for (String ligne : Bucheron.diagnosticArbres(t, (ServerLevel) t.level(), p, b, 48)) c.getSource().sendSuccess(() -> Component.literal(ligne), false);
                            return 1;
                        })))))

                .then(Commands.literal("banc")
                        .then(Commands.literal("fin").executes(c -> {
                            int n = finBanc(c.getSource().getServer());
                            c.getSource().sendSuccess(() -> Component.literal(n + " ouvriers du banc de charge retirés."), true);
                            return n;
                        }))
                        .then(Commands.argument("nombre", IntegerArgumentType.integer(1, 300))
                                .executes(c -> banc(c, "", null))
                                .then(Commands.argument("metier", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(java.util.stream.Stream.of("tous"), Metier.TOUS.keySet().stream()), b))
                                        .executes(c -> banc(c, StringArgumentType.getString(c, "metier"), null))
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                .executes(c -> banc(c, StringArgumentType.getString(c, "metier"),
                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos")))))))

                .then(Commands.literal("charge").executes(c -> {
                    for (String l : charge(c.getSource().getServer())) c.getSource().sendSuccess(() -> Component.literal(l), false);
                    return 1;
                }))
                .then(Commands.literal("panneau").executes(c -> {
                    String url = PanneauWeb.adresse();
                    c.getSource().sendSuccess(() -> Component.literal("Panneau : " + url + "  (mot de passe : clique pour le copier)")
                            .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, Reglages.actuel.jeton_panneau))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copier le mot de passe")))), false);
                    return 1;
                })));
    }

    // --- Banc de charge : /pnj banc, /pnj charge ----------------------------------------------------------

    /** Le chef fictif des ouvriers du banc (aucun joueur) : leurs lettres et leur poste sont effacés à la fin. */
    static final UUID CHEF_BANC = UUID.nameUUIDFromBytes("colonie_pnj:banc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final String MARQUE_BANC = "colonie_pnj_banc";

    static int banc(CommandContext<CommandSourceStack> c, String metier, BlockPos pos) {
        if (metier.equals("tous")) metier = "";
        if (!metier.isEmpty() && !Metier.existe(metier)) {
            c.getSource().sendFailure(Component.literal("Métier inconnu : " + metier + ". Métiers : tous, " + String.join(", ", Metier.TOUS.keySet())));
            return 0;
        }
        if (pos == null && c.getSource().getEntity() == null) {
            c.getSource().sendFailure(Component.literal("Depuis la console, donne la position : /pnj banc <nombre> tous <x> <y> <z>"));
            return 0;
        }
        int n = IntegerArgumentType.getInteger(c, "nombre");
        try {
            var l = banc(c.getSource().getLevel(), pos != null ? pos : BlockPos.containing(c.getSource().getPosition()), n, metier);
            c.getSource().sendSuccess(() -> Component.literal(l.size() + " ouvriers du banc de charge au travail autour du poste. "
                    + "« /pnj charge » pour la mesure (attendre 5 s), « /pnj banc fin » pour les retirer."), true);
            return l.size();
        } catch (IllegalStateException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** Un banc tourne déjà : son poste existe, ou un de ses ouvriers. */
    static boolean bancEnCours(MinecraftServer s) {
        return Courrier.de(s).poste(CHEF_BANC) != null || tous(s).stream().anyMatch(t -> t.getTags().contains(MARQUE_BANC));
    }

    /**
     * Fait apparaître {@code n} ouvriers au travail (mineurs, bûcherons et guerriers à tour de rôle, sauf métier donné),
     * en grille tous les 3 blocs au sud de {@code centre}, rattachés à un poste posé 3 blocs au nord, sur le sol réel.
     * Ils sont marqués, et ne ramassent rien (pas les affaires d'un joueur mort). Refus (IllegalStateException) si un
     * banc tourne déjà, ou si la place du poste n'est pas une case d'air sur un sol, loin de tout coffre.
     */
    static List<Travailleur> banc(ServerLevel l, BlockPos centre, int n, String metier) {
        if (bancEnCours(l.getServer())) throw new IllegalStateException("Un banc de charge tourne déjà : « /pnj banc fin » d'abord.");
        BlockPos bureau = sol(l, centre.getX(), centre.getY(), centre.getZ() - 3);
        if (bureau == null || !l.getBlockState(bureau).isAir())
            throw new IllegalStateException("Pas de place pour le poste 3 blocs au nord : il faut une case d'air posée sur un sol.");
        for (var d : net.minecraft.core.Direction.values())
            if (l.getCapability(Capabilities.ItemHandler.BLOCK, bureau.relative(d), d.getOpposite()) != null // le côté que voit le poste (Bureau.voisins)
                    || l.getCapability(Capabilities.ItemHandler.BLOCK, bureau.relative(d), null) != null)
                throw new IllegalStateException("Un coffre ou une machine touche la place du poste (" + bureau.toShortString() + ") : choisis un autre endroit.");
        l.setBlockAndUpdate(bureau, BUREAU.get().defaultBlockState());
        Courrier.de(l.getServer()).definirPoste(CHEF_BANC, l, bureau);
        int cote = (int) Math.ceil(Math.sqrt(n));
        List<String> metiers = metier.isEmpty() ? List.of("mineur", "bucheron", "guerrier") : List.of(metier);
        List<Travailleur> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String m = metiers.get(i % metiers.size());
            BlockPos p = sol(l, centre.getX() + (i % cote - cote / 2) * 3, centre.getY(), centre.getZ() + 2 + (i / cote) * 3);
            if (p == null) continue; // dans un mur ou au-dessus du vide : pas d'ouvrier à cette place
            var outil = new net.minecraft.world.item.ItemStack(switch (m) {
                case "mineur" -> net.minecraft.world.item.Items.IRON_PICKAXE;
                case "bucheron" -> net.minecraft.world.item.Items.IRON_AXE;
                case "guerrier" -> net.minecraft.world.item.Items.IRON_SWORD;
                case "fermier" -> net.minecraft.world.item.Items.IRON_HOE;
                case "eleveur" -> net.minecraft.world.item.Items.SHEARS;
                case "pecheur" -> net.minecraft.world.item.Items.FISHING_ROD;
                default -> net.minecraft.world.item.Items.AIR;
            });
            Travailleur t = Travailleur.auTravail(l, m, bureau, net.minecraft.world.phys.Vec3.atBottomCenterOf(p), CHEF_BANC, outil);
            t.addTag(MARQUE_BANC);
            t.memoire.putLong("sans_ramasser", Long.MAX_VALUE);
            // Du vrai travail : le fer pour le mineur (un escalier à creuser), la ronde pour le guerrier, n'importe quel bois sinon.
            if (m.equals("mineur")) t.missions.add(new Travailleur.Mission("fer", "quantite", 100_000));
            if (m.equals("guerrier")) t.missions.add(new Travailleur.Mission("ronde", "quantite", 100_000));
            t.forcerChunk();
            r.add(t);
        }
        return r;
    }

    /**
     * Le sol réel sous (x, y, z) : la première case où tient un ouvrier (deux cases libres sur un bloc solide), en
     * descendant de y + 2 à y - 8. Pas le dessus de la colonne (un toit, le plafond du Nether). Chunk chargé d'abord.
     */
    static BlockPos sol(ServerLevel l, int x, int y, int z) {
        l.getChunk(x >> 4, z >> 4);
        for (int dy = 2; dy >= -8; dy--) {
            BlockPos p = new BlockPos(x, y + dy, z);
            if (Monde.libre(l, p) && Monde.libre(l, p.above()) && !l.getBlockState(p.below()).getCollisionShape(l, p.below()).isEmpty()) return p;
        }
        return null;
    }

    /**
     * Retire les ouvriers du banc (eux seuls), puis leurs postes vidés, leurs lettres et leur effectif. Les ouvriers
     * partent d'abord : sinon chacun écrirait que son poste est détruit (trente lignes au journal).
     */
    static int finBanc(MinecraftServer s) {
        Courrier c = Courrier.de(s);
        List<Courrier.Poste> postes = new ArrayList<>();
        if (c.poste(CHEF_BANC) != null) postes.add(c.poste(CHEF_BANC));
        int n = 0;
        for (Travailleur t : tous(s)) {
            if (!t.getTags().contains(MARQUE_BANC)) continue;
            if (t.bureau != null) postes.add(new Courrier.Poste(t.level().dimension(), t.bureau));
            t.discard();
            n++;
        }
        for (Courrier.Poste p : postes) {
            ServerLevel l = s.getLevel(p.monde());
            if (l == null || !(l.getBlockEntity(p.pos()) instanceof Bureau.Entite b) || !CHEF_BANC.equals(b.proprietaire)) continue;
            b.caisse.clearContent(); // ce qu'ils y ont déposé disparaît avec eux
            b.depot.clearContent();
            b.reserve.vider();
            l.removeBlock(p.pos(), false);
        }
        c.postes.remove(CHEF_BANC);
        c.boites.remove(CHEF_BANC);
        c.reserves.remove(CHEF_BANC);
        c.effectifs.remove(CHEF_BANC);
        c.setDirty();
        return n;
    }

    /** /pnj charge : le tick du serveur et la part du mod, sur les 100 derniers ticks. */
    static List<String> charge(MinecraftServer s) {
        long pire = 0;
        for (long t : s.getTickTimesNanos()) pire = Math.max(pire, t);
        return Charge.ICI.lignes(s.getAverageTickTimeNanos() / 1e6, pire / 1e6);
    }

    static int chercheur(CommandContext<CommandSourceStack> c, int niveau, String skin) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String metier = StringArgumentType.getString(c, "metier");
        if (!Metier.existe(metier)) {
            c.getSource().sendFailure(Component.literal("Métier inconnu : " + metier + ". Métiers : " + String.join(", ", Metier.TOUS.keySet())));
            return 0;
        }
        ServerLevel l = c.getSource().getLevel();
        Travailleur t = TRAVAILLEUR.get().create(l);
        if (t == null) return 0;
        var pos = c.getSource().getPosition();
        t.moveTo(pos.x, pos.y, pos.z, c.getSource().getRotation().y, 0);
        t.tirerAuSort(metier, niveau);
        t.setSkin(skin);
        l.addFreshEntity(t);
        c.getSource().sendSuccess(() -> Component.literal(t.prenom + " (" + Metier.nom(metier) + ", " + "★".repeat(t.niveau) + ") cherche du travail ici."), true);
        return 1;
    }
}

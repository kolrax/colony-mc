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
    public static final DeferredHolder<EntityType<?>, EntityType<Corbeau>> CORBEAU = ENTITES.register("corbeau",
            () -> EntityType.Builder.<Corbeau>of(Corbeau::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(2).noSave().build("corbeau"));
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
        bus.addListener((RegisterCapabilitiesEvent e) ->
                e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BUREAU_BE.get(), (be, cote) -> new InvWrapper(be.depot)));
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(BUREAU_OBJET);
        });

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> {
            Reglages.charger();
            ReglagesIA.charger();
            Macon.preparerDossier();
            IA.DEPENSES = DepensesIA.charger(Reglages.dossier().resolve("depenses-ia.json"));
            chargerIncompris();
            PanneauWeb.demarrer(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            PanneauWeb.arreter();
            Incompris.JOURNAL.sauver(fichierIncompris(), true, x -> LOG.warn("Phrases non comprises : écriture impossible", x));
            laisserFinirLaGeneration(e.getServer());
        });
        // Après un plantage, seul « Stopped » est envoyé : on ferme aussi le panneau là.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            PanneauWeb.arreter();
            Remarques.oublier(); // elles tiennent les mondes du serveur arrêté
            Deuil.oublier(); // les enterrements en cours aussi
            Incompris.JOURNAL.sauver(fichierIncompris(), true, x -> LOG.warn("Phrases non comprises : écriture impossible", x));
        });
        NeoForge.EVENT_BUS.addListener(ColoniePnj::commandes);
        NeoForge.EVENT_BUS.addListener(Guerrier::impact); // les flèches des guerriers ne blessent pas les passants
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
            if (e.getServer().getTickCount() % 20 == 0) { // les corbeaux arrivés livrent leur lettre
                Courrier.tick(e.getServer());
                h = mesure.mesurer(Charge.Partie.COURRIER, h);
            }
            if (e.getServer().getTickCount() % 600 == 0) // les phrases non comprises, si elles ont changé (écrites en arrière-plan)
                Incompris.JOURNAL.sauver(fichierIncompris(), false, x -> LOG.warn("Phrases non comprises : écriture impossible", x));
            Voix.tick(e.getServer()); // qui parle à quel ouvrier au micro, et les phrases finies
            mesure.mesurer(Charge.Partie.VOIX, h);
            mesure.clore();
            Itineraire.nouveauTick(); // le budget des recherches d'étapes repart pour le tick suivant
        });
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
            long n = Courrier.de(p.server).nonLues(p.getUUID());
            if (n > 0) Travailleur.signaler(p, "✉ " + n + (n > 1 ? " lettres t'attendent" : " lettre t'attend") + " à ton poste de travail.");
            // Une fois par joueur : on peut parler à ses ouvriers au micro, et ce que ça implique (sa voix part vers un service en ligne).
            var donnees = p.getPersistentData();
            if (Voix.pret && ReglagesIA.actuel.ecoutePossible() && !donnees.getBoolean("colonie_pnj_micro2")) {
                donnees.putBoolean("colonie_pnj_micro2", true);
                p.sendSystemMessage(Component.literal("🎙 Tu peux parler à tes ouvriers au micro (Simple Voice Chat) : regarde celui à qui tu parles, "
                        + "ou appelle-le par son prénom ; « les gars » s'adresse à tous ceux qui t'entendent. Ils répondent à voix haute. "
                        + "Quand un autre joueur est près de toi, il faut regarder un de tes ouvriers pour qu'ils t'écoutent. "
                        + "Seul ce que tu dis ou écris à tes propres ouvriers part vers un service en ligne (Groq), pour qu'ils te comprennent ; "
                        + "leurs réponses sont mises en voix par Google. Rien d'autre n'est envoyé.")
                        .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            }
            // Une fois par joueur : la nouveauté de la 0.8.0.
            if (!donnees.getBoolean("colonie_pnj_repertoire")) {
                donnees.putBoolean("colonie_pnj_repertoire", true);
                p.sendSystemMessage(Component.literal("✦ Tes ouvriers savent maintenant faire " + Repertoire.TOUTES.size() + " actions en plus de leur travail : "
                        + "« garde à vous », « repos », « assieds-toi », « danse », « fais le cochon », « mets-toi derrière moi », « donne-moi ton pain », "
                        + "« raconte une blague », « où est le poste ? »… Le guerrier se bat sur ordre, le mineur creuse où tu regardes, le bûcheron abat l'arbre "
                        + "que tu regardes. Demande-leur « que sais-tu faire ? ».")
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
        for (ServerPlayer p : monde.players())
            if (!p.isSpectator() && monde.random.nextDouble() < Reglages.actuel.chercheurs.chance) faireVenir(monde, p);
    }

    static void faireVenir(ServerLevel l, ServerPlayer p) {
        List<String> metiers = Metier.actifs();
        if (metiers.isEmpty()) return;
        long autour = l.getEntities(TRAVAILLEUR.get(), t -> t.mode == Travailleur.Mode.CHOMEUR && t.distanceTo(p) < 96).size();
        if (autour >= Reglages.actuel.chercheurs.max_autour) return;
        for (int essai = 0; essai < 10; essai++) {
            double angle = l.random.nextDouble() * Math.PI * 2, d = 24 + l.random.nextInt(17);
            int x = (int) (p.getX() + Math.cos(angle) * d), z = (int) (p.getZ() + Math.sin(angle) * d);
            BlockPos pos = new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!l.isLoaded(pos) || !l.getFluidState(pos.below()).isEmpty() || !l.getBlockState(pos.below()).isSolidRender(l, pos.below())) continue;
            Travailleur t = TRAVAILLEUR.get().create(l);
            if (t == null) return;
            t.moveTo(x + 0.5, pos.getY(), z + 0.5, l.random.nextFloat() * 360, 0);
            if (!l.noCollision(t)) continue;
            t.tirerAuSort(metiers.get(l.random.nextInt(metiers.size())), 0);
            l.addFreshEntity(t);
            p.sendSystemMessage(Component.literal("On dit qu'un " + Metier.nom(t.metier).toLowerCase() + " cherche du travail dans les environs…").withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            return;
        }
    }

    // --- Commandes d'admin : /pnj ---------------------------------------------------------------------

    static void commandes(RegisterCommandsEvent e) {
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
                .then(Commands.literal("ia")
                        .executes(c -> { for (String l : etatIA()) c.getSource().sendSuccess(() -> Component.literal(l), false); return 1; })
                        .then(Commands.literal("recharger").executes(c -> {
                            ReglagesIA.charger();
                            c.getSource().sendSuccess(() -> Component.literal("Réglages de l'IA rechargés (config/colonie_pnj/ia.json)."), true);
                            return 1;
                        }))
                        .then(Commands.literal("test").executes(c -> {
                            var source = c.getSource();
                            source.sendSuccess(() -> Component.literal("Essai des clés en cours…"), false);
                            IA.tester().whenComplete((l, err) -> source.getServer().execute(() -> {
                                if (l != null) for (String x : l) source.sendSuccess(() -> Component.literal(x), false);
                                else source.sendFailure(Component.literal("Essai impossible : " + err));
                            }));
                            return 1;
                        })))
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
                .then(Commands.literal("incompris")
                        .executes(c -> { for (String l : incompris()) c.getSource().sendSuccess(() -> Component.literal(l), false); return 1; })
                        .then(Commands.literal("vider").executes(c -> {
                            Incompris.JOURNAL.vider();
                            c.getSource().sendSuccess(() -> Component.literal("Journal des phrases non comprises remis à zéro."), true);
                            return 1;
                        })))
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

    // --- Phrases non comprises : /pnj incompris ------------------------------------------------------------

    static java.nio.file.Path fichierIncompris() { return Reglages.dossier().resolve("incompris.json"); }

    static void chargerIncompris() {
        try { Incompris.JOURNAL.charger(fichierIncompris()); }
        catch (Exception e) { LOG.error("Phrases non comprises illisibles (incompris.json), journal repris à zéro", e); }
    }

    static final Map<String, String> MOTIFS = Map.of(Incompris.RIEN, "personne", Incompris.IA_SEULE, "l'IA seule (action)", Incompris.IA_PAROLE, "l'IA seule (paroles)");

    /** Les 10 phrases non comprises les plus fréquentes, pour /pnj incompris. */
    static List<String> incompris() {
        List<String> l = new ArrayList<>();
        var top = Incompris.JOURNAL.plusFrequentes(10);
        l.add(Incompris.JOURNAL.taille() + " phrases non comprises" + (top.isEmpty() ? "." : ". Les plus fréquentes (compris par) :"));
        for (var e : top)
            l.add(" - " + e.get("nombre") + "× « " + e.get("phrase") + " » (" + MOTIFS.getOrDefault((String) e.get("motif"), (String) e.get("motif"))
                    + (((List<?>) e.get("metiers")).isEmpty() ? "" : " ; " + String.join(", ", ((List<?>) e.get("metiers")).stream().map(String::valueOf).toList()))
                    + " ; dernière fois " + String.valueOf(e.get("derniere")).substring(0, 16).replace('T', ' ') + " UTC)");
        return l;
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
     * partent d'abord : sinon chacun écrirait que son poste est détruit (une volée de corbeaux).
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
            l.removeBlock(p.pos(), false);
        }
        c.postes.remove(CHEF_BANC);
        c.boites.remove(CHEF_BANC);
        c.effectifs.remove(CHEF_BANC);
        c.vols.removeIf(v -> CHEF_BANC.equals(v.chef));
        c.setDirty();
        return n;
    }

    /** /pnj charge : le tick du serveur et la part du mod, sur les 100 derniers ticks. */
    static List<String> charge(MinecraftServer s) {
        long pire = 0;
        for (long t : s.getTickTimesNanos()) pire = Math.max(pire, t);
        return Charge.ICI.lignes(s.getAverageTickTimeNanos() / 1e6, pire / 1e6);
    }

    /** /pnj ia : ce qui marche, ce qui manque, et la consommation du jour (jamais les clés). */
    static List<String> etatIA() {
        ReglagesIA r = ReglagesIA.actuel;
        List<String> l = new ArrayList<>();
        l.add("IA " + (r.active ? "active" : "désactivée") + " · clé Groq " + (r.groq_cle.isEmpty() ? "absente" : "présente") + " · clé Gemini "
                + (r.gemini_cle.isEmpty() ? "absente" : "présente") + " · Simple Voice Chat " + (Voix.pret ? "prêt" : "absent"));
        for (var s : List.of(IA.CONVERSATION, IA.ECOUTE, IA.TRANSCRIPTION, IA.VOIX, IA.OPENROUTER)) {
            int max = s == IA.CONVERSATION ? r.max_conversations_jour : s == IA.ECOUTE ? r.max_ecoutes_jour : s == IA.TRANSCRIPTION ? r.max_transcriptions_jour
                    : s == IA.OPENROUTER ? r.max_openrouter_jour : r.max_voix_jour;
            var e = s.etat(max);
            l.add(" - " + s.nom + " : " + e.get("demandes") + "/" + max + " aujourd'hui" + ((long) e.get("pause") > 0 ? ", en pause " + e.get("pause") + " s" : "")
                    + (((String) e.get("erreur")).isEmpty() ? "" : " · dernière erreur : " + e.get("erreur")));
        }
        l.add(" - " + IA.openrouter());
        return l;
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

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
            PanneauWeb.demarrer(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            PanneauWeb.arreter();
            laisserFinirLaGeneration(e.getServer());
        });
        // Après un plantage, seul « Stopped » est envoyé : on ferme aussi le panneau là.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> PanneauWeb.arreter());
        NeoForge.EVENT_BUS.addListener(ColoniePnj::commandes);
        NeoForge.EVENT_BUS.addListener(ColoniePnj::arriveeChercheurs);
        // Filet de sécurité : un PNJ arrivé dans un chunk inactif (poussé, ou figé avant la 0.3.0) ne peut plus
        // déplacer ses chunks lui-même ; le serveur les recale toutes les 5 secondes.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 100 == 0) for (Travailleur t : tous(e.getServer())) t.forcerChunk();
            if (e.getServer().getTickCount() % 20 == 0) Courrier.tick(e.getServer()); // les corbeaux arrivés livrent leur lettre
            Voix.tick(e.getServer()); // qui parle à quel ouvrier au micro, et les phrases finies
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
                .then(Commands.literal("panneau").executes(c -> {
                    String url = PanneauWeb.adresse();
                    c.getSource().sendSuccess(() -> Component.literal("Panneau : " + url + "  (mot de passe : clique pour le copier)")
                            .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, Reglages.actuel.jeton_panneau))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copier le mot de passe")))), false);
                    return 1;
                })));
    }

    /** /pnj ia : ce qui marche, ce qui manque, et la consommation du jour (jamais les clés). */
    static List<String> etatIA() {
        ReglagesIA r = ReglagesIA.actuel;
        List<String> l = new ArrayList<>();
        l.add("IA " + (r.active ? "active" : "désactivée") + " · clé Groq " + (r.groq_cle.isEmpty() ? "absente" : "présente") + " · clé Gemini "
                + (r.gemini_cle.isEmpty() ? "absente" : "présente") + " · Simple Voice Chat " + (Voix.pret ? "prêt" : "absent"));
        for (var s : List.of(IA.CONVERSATION, IA.TRANSCRIPTION, IA.VOIX)) {
            int max = s == IA.CONVERSATION ? r.max_conversations_jour : s == IA.TRANSCRIPTION ? r.max_transcriptions_jour : r.max_voix_jour;
            var e = s.etat(max);
            l.add(" - " + s.nom + " : " + e.get("demandes") + "/" + max + " aujourd'hui" + ((long) e.get("pause") > 0 ? ", en pause " + e.get("pause") + " s" : "")
                    + (((String) e.get("erreur")).isEmpty() ? "" : " · dernière erreur : " + e.get("erreur")));
        }
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

package fr.lacolonie.pnj;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Banc photo des écrans, pour le développement seulement ({@code ./gradlew runEcrans}, propriété
 * {@code colonie.photos=ecrans}, jamais chez un joueur). Dans un monde solo, il pose un poste, embauche trois ouvriers
 * avec leurs missions, remplit le dépôt et le journal, puis ouvre chaque écran (fiche, nouvelle mission, confirmation,
 * les cinq onglets du poste, la barre de commandes) à plusieurs échelles d'interface et en prend une capture
 * (dossier screenshots du jeu). Puis il quitte.
 */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class BancEcrans {
    private BancEcrans() {}

    static final boolean ACTIF = "ecrans".equals(System.getProperty("colonie.photos"));

    /** Une capture : à cette taille de fenêtre et cette échelle, ouvrir l'écran ({@code ouvrir} au tick 0, {@code ensuite} au tick 12). */
    record Etape(String nom, int largeur, int hauteur, int echelle, Runnable ouvrir, Runnable ensuite, double[] souris) {}

    static final List<Etape> ETAPES = new ArrayList<>();
    static final String MONDE = "ecrans";
    static int tick = -200, etape = -1;
    static boolean cree;
    static BlockPos poste;
    static final List<Integer> IDS = new ArrayList<>();
    static final List<UUID> UUIDS = new ArrayList<>();

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ACTIF) return;
        Minecraft mc = Minecraft.getInstance();
        // Le premier lancement : le monde n'existe pas encore (le lancement rapide renvoie alors au menu) ; on le crée.
        if (!cree && mc.level == null && (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)
                && !mc.getLevelSource().levelExists(MONDE)) {
            cree = true;
            mc.createWorldOpenFlows().createFreshLevel(MONDE, new net.minecraft.world.level.LevelSettings(MONDE, net.minecraft.world.level.GameType.SURVIVAL, false,
                            net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                    new net.minecraft.world.level.levelgen.WorldOptions(20261019L, false, false),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        tick++;
        if (tick == -150) serveur(BancEcrans::scene);
        if (tick < 0) return;
        if (etape < 0) { preparer(); etape = 0; tick = 0; }
        if (etape >= ETAPES.size()) { if (tick > 20) mc.stop(); return; }
        Etape x = ETAPES.get(etape);
        if (tick == 1) {
            if (mc.getWindow().getScreenWidth() != x.largeur() || mc.getWindow().getScreenHeight() != x.hauteur())
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), x.largeur(), x.hauteur());
            mc.options.guiScale().set(x.echelle());
            mc.resizeDisplay();
            mc.setScreen(null);
        }
        if (tick == 4) x.ouvrir().run();
        if (tick == 14 && x.ensuite() != null) x.ensuite().run();
        if (tick == 22 && x.souris() != null) {
            double k = mc.getWindow().getGuiScale();
            GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x.souris()[0] * k, x.souris()[1] * k);
        }
        if (tick == 34) {
            String nom = String.format("ecran_%02d_%s_%dx%d_x%d.png", etape, x.nom(), x.largeur(), x.hauteur(), x.echelle());
            ColoniePnj.LOG.info("banc écrans : {} (écran {} ; {}x{} effectif)", nom, mc.screen == null ? "aucun" : mc.screen.getClass().getSimpleName(),
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            Screenshot.grab(mc.gameDirectory, nom, mc.getMainRenderTarget(), m -> ColoniePnj.LOG.info("photo : {}", m.getString()));
            etape++;
            tick = 0;
        }
    }

    /** Sur le fil du serveur, avec le joueur. */
    static void serveur(Consumer<ServerPlayer> f) {
        Minecraft mc = Minecraft.getInstance();
        var s = mc.getSingleplayerServer();
        UUID u = mc.player.getUUID();
        s.execute(() -> { ServerPlayer p = s.getPlayerList().getPlayer(u); if (p != null) f.accept(p); });
    }

    /** Le poste, ses ouvriers, leurs missions, le dépôt et le journal. */
    static void scene(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        p.server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        l.setDayTime(6000);
        l.setWeatherParameters(60000, 0, false, false);
        BlockPos b = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockPosition().east(3));
        l.setBlockAndUpdate(b, ColoniePnj.BUREAU.get().defaultBlockState());
        Bureau.Entite e = (Bureau.Entite) l.getBlockEntity(b);
        e.proprietaire = p.getUUID();
        Courrier c = Courrier.de(p.server);
        c.definirPoste(p.getUUID(), l, b);
        poste = b;
        // Le dépôt : 27 sortes dans le bureau, d'autres dans un coffre voisin.
        ItemStack[] depot = {new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.IRON_HOE), new ItemStack(Items.RAW_IRON, 64),
                new ItemStack(Items.COAL, 64), new ItemStack(Items.RAW_COPPER, 37), new ItemStack(Items.BREAD, 12), new ItemStack(Items.COOKED_BEEF, 7),
                new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.BIRCH_LOG, 40), new ItemStack(Items.SPRUCE_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.STONE, 64), new ItemStack(Items.DIRT, 23), new ItemStack(Items.WHEAT, 31), new ItemStack(Items.CARROT, 18),
                new ItemStack(Items.POTATO, 9), new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.TORCH, 48), new ItemStack(Items.STICK, 64),
                new ItemStack(Items.LEATHER, 5), new ItemStack(Items.WHITE_WOOL, 14), new ItemStack(Items.FEATHER, 3), new ItemStack(Items.EGG, 16),
                new ItemStack(Items.ANDESITE, 64), new ItemStack(Items.FLINT, 2), new ItemStack(Items.CHARCOAL, 21)};
        for (int i = 0; i < depot.length; i++) e.depot.setItem(i, depot[i]);
        BlockPos coffre = b.north();
        l.setBlockAndUpdate(coffre, Blocks.CHEST.defaultBlockState());
        if (l.getBlockEntity(coffre) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity ch) {
            net.minecraft.world.item.Item[] autres = {Items.GRANITE, Items.DIORITE, Items.GRAVEL, Items.SAND, Items.CLAY_BALL, Items.BRICK, Items.IRON_INGOT,
                    Items.COPPER_INGOT, Items.GOLD_NUGGET, Items.LAPIS_LAZULI, Items.REDSTONE, Items.DIAMOND, Items.APPLE, Items.BEETROOT, Items.COD,
                    Items.SALMON, Items.MUTTON, Items.PORKCHOP, Items.CHICKEN, Items.BONE, Items.STRING, Items.ARROW, Items.BOW, Items.FISHING_ROD,
                    Items.SHEARS, Items.MILK_BUCKET, Items.DARK_OAK_LOG};
            for (int i = 0; i < autres.length; i++) ch.setItem(i, new ItemStack(autres[i], 1 + (i * 7) % 64));
        }
        // Trois ouvriers au travail, et un disparu.
        Vec3 o = Vec3.atBottomCenterOf(b.south(2));
        Travailleur raoul = Travailleur.auTravail(l, "mineur", b, o, p.getUUID(), new ItemStack(Items.IRON_PICKAXE));
        Travailleur berthe = Travailleur.auTravail(l, "bucheron", b, o.add(1.5, 0, 0), p.getUUID(), new ItemStack(Items.IRON_AXE));
        Travailleur gaspard = Travailleur.auTravail(l, "fermier", b, o.add(-1.5, 0, 0), p.getUUID(), ItemStack.EMPTY);
        Travailleur perdu = Travailleur.auTravail(l, "guerrier", b, o.add(0, 0, 1.5), p.getUUID(), ItemStack.EMPTY);
        long maintenant = System.currentTimeMillis();
        for (Travailleur t : List.of(raoul, berthe, gaspard)) t.prochainePaie = maintenant + 83 * 60_000L;
        gaspard.impayes = 1;
        raoul.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        raoul.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        raoul.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        raoul.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        Travailleur.Mission fer = new Travailleur.Mission("fer", "quantite", 64);
        fer.livre = 23;
        raoul.missions.add(fer);
        raoul.missions.add(new Travailleur.Mission("charbon", "stock", 128));
        Travailleur.Mission cuivre = new Travailleur.Mission("cuivre", "quantite", 32);
        cuivre.horaires = "jour";
        cuivre.lieu = b.offset(40, -20, -12);
        raoul.missions.add(cuivre);
        for (String id : List.of("tout", "chene", "bouleau", "sapin", "chene_noir", "jungle", "acacia", "cerisier")) {
            Travailleur.Mission m = new Travailleur.Mission(id, id.equals("tout") ? "stock" : "quantite", id.equals("tout") ? 256 : 64);
            m.quotidienne = id.equals("chene");
            m.uneFois = id.equals("acacia");
            berthe.missions.add(m);
        }
        gaspard.missions.add(new Travailleur.Mission("tout", "stock", 64));
        c.inscrire(perdu);
        perdu.discard();
        IDS.clear();
        UUIDS.clear();
        for (Travailleur t : List.of(raoul, berthe, gaspard)) { IDS.add(t.getId()); UUIDS.add(t.getUUID()); t.majNom(); }
        // Le journal : de quoi remplir l'onglet et le faire défiler.
        String[] types = {"faim", "pioche", "hache", "graines", "coffre_plein", "chantier_fini", "fleches", "sans_four"};
        for (int i = 0; i < 18; i++) Courrier.ecrire(List.of(raoul, berthe, gaspard).get(i % 3), types[i % types.length]);
        Courrier.ecrireTexte(berthe, "Un loup rôde près du bois", "Un loup rôde près du bois de bouleaux, à l'est du poste. Je suis rentrée en hâte : envoie un garde avant que je n'y retourne, s'il te plaît.", true);
        Courrier.ecrireTexte(raoul, "Mission terminée : 64 fers bruts déposés au dépôt du poste, comme demandé", "J'ai fini : 64 fers bruts sont au dépôt.", false);
        Courrier.ecrireTexte(gaspard, "Je n'ai pas eu ma paie", "La caisse était vide quand je suis passé. Sans paie demain, je cesse le travail.", true);
        p.teleportTo(l, b.getX() + 0.5, b.getY(), b.getZ() + 4.5, 180, 10);
    }

    /** La liste des captures, aux tailles qui comptent : 1280 x 720 en échelle 2 et 3 (la plus courante), 1920 x 1080 en 4, 1280 x 960 en 4 (le plus petit écran du jeu, 320 x 240). */
    static void preparer() {
        int[][] tailles = {{1280, 720, 2}, {1280, 720, 3}, {1920, 1080, 4}, {1280, 960, 4}};
        for (int[] t : tailles) {
            int w = t[0], h = t[1], k = t[2];
            ETAPES.add(new Etape("fiche_mineur", w, h, k, () -> fiche(0), null, null));
            ETAPES.add(new Etape("fiche_bucheron_8_missions", w, h, k, () -> fiche(1), null, null));
            ETAPES.add(new Etape("nouvelle_mission_mineur", w, h, k, () -> fiche(0), () -> nouvelle(1, false), null));
            ETAPES.add(new Etape("nouvelle_mission_fermier", w, h, k, () -> fiche(2), () -> nouvelle(3, false), null));
            ETAPES.add(new Etape("confirmer_mission", w, h, k, () -> fiche(0), () -> nouvelle(1, true), null));
            ETAPES.add(new Etape("confirmer_renvoi", w, h, k, () -> fiche(0), BancEcrans::renvoi, null));
            for (String o : Client.EcranPoste.ONGLETS) ETAPES.add(new Etape("poste_" + o, w, h, k, () -> poste(o), null, souris(o, w / k, h / k)));
            ETAPES.add(new Etape("barre_vide", w, h, k, () -> barre(""), null, null));
            ETAPES.add(new Etape("barre_gar", w, h, k, () -> barre("gar"), null, null));
            ETAPES.add(new Etape("barre_rien", w, h, k, () -> barre("zzz"), null, null));
        }
    }

    /** Où mettre la souris pour voir l'infobulle : sur la 2e case du dépôt, sur la 2e ligne du journal. */
    static double[] souris(String onglet, int w, int h) {
        int x0 = (w - 420) / 2, y0 = (h - 228 + 11) / 2;
        return switch (onglet) {
            case "depot" -> new double[]{x0 + 9 + 18 + 8, y0 + 72 + 8};
            case "journal" -> new double[]{x0 + 150, y0 + 32 + 11 + 4};
            default -> new double[]{1, 1};
        };
    }

    static void fiche(int k) {
        int id = IDS.get(k);
        serveur(p -> { if (p.serverLevel().getEntity(id) instanceof Travailleur t) Missions.montrer(p, t); });
    }

    static void nouvelle(int choix, boolean valider) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof Client.EcranOuvrier f)) { ColoniePnj.LOG.warn("banc écrans : pas de fiche ouverte"); return; }
        Client.EcranNouvelleMission n = new Client.EcranNouvelleMission(f);
        n.choix = choix;
        n.repetition = 1;
        n.horaires = 1;
        mc.setScreen(n);
        if (valider) n.valider();
    }

    static void renvoi() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof Client.EcranOuvrier f)) return;
        mc.setScreen(new Client.EcranConfirmer(f, "Renvoyer " + f.i.getString("nom") + " ? Il ne travaillera plus pour toi.", () -> {}));
    }

    static void poste(String onglet) {
        Client.EcranPoste.ongletMemo = onglet;
        Client.EcranPoste.choisiMemo = UUIDS.get(0).toString();
        Client.EcranPoste.categorieMemo = "";
        Client.EcranPoste.rechercheMemo = "";
        BlockPos b = poste;
        serveur(p -> { if (p.serverLevel().getBlockEntity(b) instanceof Bureau.Entite e) PacketDistributor.sendToPlayer(p, new Reseau.Poste(b, e.fiche(p))); });
    }

    static void barre(String saisie) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level.getEntity(IDS.get(0)) instanceof Travailleur t)) { ColoniePnj.LOG.warn("banc écrans : ouvrier introuvable chez le client"); return; }
        BarreCommandes.Ecran b = new BarreCommandes.Ecran(t);
        mc.setScreen(b);
        b.saisie.setValue(saisie);
    }
}

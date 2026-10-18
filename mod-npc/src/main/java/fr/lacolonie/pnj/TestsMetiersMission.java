package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Les métiers branchés sur les missions (lot 16, refonte parties 1 à 3) : un éleveur sans enclos est bloqué avec sa raison
 * au-dessus de la tête, une mission hors de ses horaires le renvoie attendre au poste, et une mission de fermier avec un
 * lieu se cultive à ce lieu ; sans lieu ni champ, le fermier fait le sien près de l'eau. Le jour (l'heure réglée pour le
 * lot) ; chaque test dans son coin du monde plat (coins 771 à 774), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMetiersMission {
    @BeforeBatch(batch = "metiers_mission")
    public static void avant(ServerLevel l) { l.setDayTime(1000); } // 7 h du matin

    /** Un éleveur sans aucun enclos autour du poste : « ⚠ Pas d'enclos » au-dessus de sa tête. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "metiers_mission")
    public static void eleveurSansEnclos(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "eleveur", 771, ItemStack.EMPTY);
        h.assertTrue(Missions.donner(chef, t, "cuir", "stock", 10, "toujours", null).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            h.assertTrue(s.equals(Statut.bloque(Statut.ENCLOS)), "statut : « " + s + " » (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** Une mission « la nuit », donnée de jour, à 10 blocs du poste : « Hors horaires », et il rentre attendre au poste. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "metiers_mission")
    public static void horsHorairesAuPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", 772, new ItemStack(Items.IRON_PICKAXE));
        Vec3 loin = Vec3.atBottomCenterOf(t.bureau.east(10));
        t.moveTo(loin.x, loin.y, loin.z);
        h.assertTrue(Missions.donner(chef, t, "fer", "fois", 20, "nuit", null).ok(), "mission acceptée");
        h.assertTrue(t.missionActive() == null, "de jour, la mission de nuit n'est pas à faire");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            double d = Math.sqrt(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)));
            h.assertTrue(s.equals(Statut.HORS_HORAIRES), "statut : « " + s + " »");
            h.assertTrue(d < 5, "revenu au poste : à " + (int) d + " blocs (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** « Cultive le blé », avec un lieu à 12 blocs du poste : il y laboure et sème son champ, et le retient. */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "metiers_mission")
    public static void fermierAuLieuDonne(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "fermier", 773, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(2, new ItemStack(Items.IRON_HOE));
        BlockPos lieu = t.bureau.offset(12, 0, 4);
        h.assertTrue(Missions.donner(chef, t, "ble", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            int semees = 0;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (l.getBlockState(lieu.offset(x, 0, z)).is(Blocks.WHEAT)) semees++;
            h.assertTrue(semees >= 20, "blé semé autour du lieu : " + semees + " (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Fermier.parcelles(t).stream().anyMatch(p -> Fermier.distance(p.centre, lieu) <= 8 * 8), "le champ du lieu est retenu");
            t.discard();
        });
    }

    /** Sans lieu ni champ, une mare à 16 blocs du poste : il fait son champ de 9 sur 9 sur la terre plate à côté, et le retient. */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "metiers_mission")
    public static void fermierChampPresDeLEau(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "fermier", 774, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        BlockPos mare = t.bureau.offset(16, -1, 0);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) l.setBlockAndUpdate(mare.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(2, new ItemStack(Items.IRON_HOE));
        h.assertTrue(Missions.donner(chef, t, "ble", "stock", 64, "toujours", null).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            int semees = 0;
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) if (l.getBlockState(mare.offset(x, 1, z)).is(Blocks.WHEAT)) semees++;
            h.assertTrue(semees >= 20, "blé semé près de la mare : " + semees + " (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(Fermier.parcelles(t).isEmpty(), "le champ est retenu");
            t.discard();
        });
    }

    /**
     * Les chunks autour de p restent chargés, comme quand le chef s'y tient pour donner la mission « ici ». Rendu : pour les
     * lâcher (en cas de succès) ; ils sont aussi lâchés juste avant la fin du temps du test ({@code timeout}), en cas d'échec.
     */
    static Runnable charger(GameTestHelper h, BlockPos p, int rayon, int timeout) {
        ServerLevel l = h.getLevel();
        int x0 = (p.getX() - rayon) >> 4, x1 = (p.getX() + rayon) >> 4, z0 = (p.getZ() - rayon) >> 4, z1 = (p.getZ() + rayon) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) l.setChunkForced(x, z, true);
        Runnable lacher = () -> { for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) l.setChunkForced(x, z, false); };
        h.runAfterDelay(timeout - 2, lacher);
        return lacher;
    }

    /**
     * « Mine du fer, ici », donné par l'écran Nouvelle mission (pas « Travaille ici ») à 40 blocs du poste : il ouvre son
     * puits à ce lieu. Avant : la mission retenait le lieu, mais le mineur ne lisait que « Travaille ici » et creusait au poste.
     */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "metiers_mission")
    public static void mineurAuLieuDeLaMission(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", 775, new ItemStack(Items.IRON_PICKAXE));
        BlockPos lieu = t.bureau.east(40);
        Runnable lacher = charger(h, lieu, Lieux.RAYON_ICI, 600);
        h.assertTrue(Missions.donner(chef, t, "fer", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            h.assertTrue(t.memoire.contains("puits"), "pas encore de puits (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            BlockPos puits = BlockPos.of(t.memoire.getLong("puits"));
            double d = Math.sqrt(LieuxRegles.dist2(puits.getX(), puits.getZ(), lieu.getX(), lieu.getZ()));
            h.assertTrue(d <= Lieux.RAYON_ICI, "puits à " + (int) d + " blocs du lieu de la mission (" + (int) Math.sqrt(puits.distSqr(t.bureau)) + " du poste)");
            h.assertTrue(Lieux.lieu(t).pos().equals(lieu), "lieu affiché : " + Lieux.lieu(t).pos().toShortString());
            lacher.run();
            t.discard();
        });
    }

    /**
     * « Coupe du chêne, ici », donné par l'écran à 40 blocs du poste ; un autre chêne à 5 blocs du poste : il va couper celui
     * du lieu, et laisse l'autre. Avant : il coupait le chêne du poste, comme sans lieu.
     */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "metiers_mission")
    public static void bucheronAuLieuDeLaMission(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "bucheron", 776, new ItemStack(Items.IRON_AXE));
        ServerLevel l = h.getLevel();
        BlockPos lieu = t.bureau.east(40);
        Runnable lacher = charger(h, lieu, 8, 1600);
        BlockPos pres = t.bureau.east(5), loin = lieu.north(2);
        TestsBucheron.arbre(l, pres, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        TestsBucheron.arbre(l, loin, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        h.assertTrue(Missions.donner(chef, t, "chene", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            h.assertTrue(l.getBlockState(pres).is(Blocks.OAK_LOG), "le chêne du poste, hors du lieu de la mission, est coupé");
            h.assertFalse(l.getBlockState(loin).is(Blocks.OAK_LOG), "le chêne du lieu doit être coupé (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            lacher.run();
            t.discard();
        });
    }

    /**
     * « Fer, garder 64, ici » : le stock plein (plus de mission en cours), puis de nouveau entamé : il garde le même puits, au
     * lieu d'en ouvrir un nouveau à chaque sac. Avant : chaque passage à « rien à faire » lui faisait oublier puits et mine.
     */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "metiers_mission")
    public static void mineurGardeSonPuitsStockPlein(GameTestHelper h) { puitsGardeStockPlein(h, 777, false); }

    /** Pareil avec un ancien « Travaille ici » à 70 blocs du lieu de la mission : au repos, il ne rouvre pas de puits. Avant : si. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "metiers_mission")
    public static void mineurGardeSonPuitsAvecUnAncienIci(GameTestHelper h) { puitsGardeStockPlein(h, 780, true); }

    static void puitsGardeStockPlein(GameTestHelper h, int coin, boolean ancienIci) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", coin, new ItemStack(Items.IRON_PICKAXE));
        BlockPos lieu = t.bureau.east(40);
        Runnable lacher = charger(h, lieu, Lieux.RAYON_ICI, 600);
        Bureau.Entite d = TestsBucheron.depot(t);
        if (ancienIci) t.travailleIci(t.bureau.west(30));
        h.assertTrue(Missions.donner(chef, t, "fer", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            h.assertTrue(t.memoire.contains("puits"), "pas encore de puits (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            long puits = t.memoire.getLong("puits"), mine = t.memoire.getLong("mine");
            d.depot.setItem(26, new ItemStack(Items.RAW_IRON, 64)); // le stock est plein
            t.majLieu();
            boolean plein = t.missionActive() == null;
            d.depot.setItem(26, new ItemStack(Items.RAW_IRON, 63)); // un fer pris : la mission repart
            t.majLieu();
            h.assertTrue(plein && t.missionActive() != null, "stock plein puis entamé : mission en cours " + plein + "/" + (t.missionActive() != null));
            h.assertTrue(t.memoire.contains("puits") && t.memoire.getLong("puits") == puits && t.memoire.getLong("mine") == mine,
                    "le même puits et la même mine (" + BlockPos.of(puits).toShortString() + " ; maintenant " + (t.memoire.contains("puits") ? BlockPos.of(t.memoire.getLong("puits")).toShortString() : "aucun") + ")");
            h.assertTrue(java.util.Objects.equals(t.lieuTravail(), lieu), "toujours au lieu de la mission");
            lacher.run();
            t.discard();
        });
    }

    /**
     * « Chêne, garder 64, ici » à 40 blocs, puis de vive voix « Garde toujours 32 bûches de bouleau en stock » (sans lieu),
     * un bouleau à 5 blocs du poste : son lieu tombe aussitôt, il coupe le bouleau et ne part jamais vers l'ancien lieu.
     * Avant : l'ancien lieu valait encore jusqu'à 5 s, et il pouvait partir vers lui.
     */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "metiers_mission")
    public static void ordreSansLieuOublieLAncien(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "bucheron", 778, new ItemStack(Items.IRON_AXE));
        ServerLevel l = h.getLevel();
        BlockPos lieu = t.bureau.east(40), bouleau = t.bureau.east(5);
        TestsBucheron.arbre(l, bouleau, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 5, 1);
        h.assertTrue(Missions.donner(chef, t, "chene", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.assertTrue(lieu.equals(t.lieuTravail()), "au lieu de la mission");
        t.appliquer(Ordres.lire("Garde toujours 32 bûches de bouleau en stock", t.metier), chef); // de vive voix (Dialogue.parler)
        String[] perime = {null};
        double[] loin = {0};
        h.onEachTick(() -> {
            if (perime[0] == null && t.missions.stream().noneMatch(m -> m.lieu != null) && t.lieuTravail() != null)
                perime[0] = "file " + t.missions.stream().map(m -> m.id).toList() + ", lieu encore " + t.lieuTravail().toShortString() + " au tick " + h.getTick();
            loin[0] = Math.max(loin[0], t.getX() - t.bureau.getX());
        });
        h.succeedWhen(() -> {
            h.assertTrue(perime[0] == null, "l'ancien lieu valait encore : " + perime[0]);
            h.assertTrue(loin[0] < 20, "parti vers l'ancien lieu : " + (int) loin[0] + " blocs à l'est du poste");
            h.assertTrue(t.missions.size() == 1 && t.missions.get(0).id.equals("bouleau"), "la file : " + t.missions.stream().map(m -> m.id).toList());
            h.assertFalse(l.getBlockState(bouleau).is(Blocks.BIRCH_LOG), "le bouleau du poste doit être coupé (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /**
     * « Travaille ici » redit : en X, puis en Y. Toutes ses missions passent en Y, même celle qui n'est pas en cours (elle
     * avait pris X) ; une autre mission au lieu à elle le garde. Avant : la mission suivante restait en X, et l'y ramenait.
     */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "metiers_mission")
    public static void travailleIciRedit(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", 779, new ItemStack(Items.IRON_PICKAXE));
        BlockPos x = t.bureau.east(10), y = t.bureau.south(10), autre = t.bureau.north(12);
        h.assertTrue(Missions.donner(chef, t, "fer", "stock", 64, "toujours", null).ok(), "fer acceptée");
        h.assertTrue(Missions.donner(chef, t, "charbon", "stock", 64, "toujours", null).ok(), "charbon acceptée");
        h.assertTrue(Missions.donner(chef, t, "or", "stock", 64, "toujours", autre).ok(), "or acceptée");
        t.travailleIci(x);
        t.travailleIci(y);
        String lieux = t.missions.stream().map(m -> m.id + "@" + (m.lieu == null ? "-" : m.lieu.equals(x) ? "X" : m.lieu.equals(y) ? "Y" : m.lieu.equals(autre) ? "A" : m.lieu.toShortString())).toList().toString();
        t.discard();
        h.assertTrue(lieux.equals("[fer@Y, charbon@Y, or@A]"), "lieux des missions : " + lieux);
        h.assertTrue(y.equals(t.lieuTravail()), "il travaille en Y");
        h.succeed();
    }
}

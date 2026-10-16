package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 9, défense de la colonie : un ouvrier fuit un creeper et prévient le guerrier, qui vient le tuer (cahier 14.11) ;
 * le guerrier chasse le monstre qui approche du poste, se lève la nuit pour lui, fait sa ronde de nuit ; loin du poste,
 * la nuit, un ouvrier se creuse un abri et en sort au matin en rebouchant son trou (cahier 14.12). Chaque test dans son
 * coin du monde plat (z = 114000), au ciel ouvert ; les monstres sont figés (sans IA).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsDefense {
    static BlockPos base(GameTestHelper h, int coin) { return TestsJeu.sol(h, coin * 400, 114000); }

    /** Un guerrier au travail à 2 blocs à l'ouest de son poste. */
    static Travailleur guerrier(GameTestHelper h, BlockPos base) {
        Travailleur g = TestsJeu.employe(h, "guerrier", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_SWORD));
        g.forcerChunk();
        return g;
    }

    /**
     * Un creeper à 7 blocs d'un mineur, à 22 blocs du guerrier : le mineur s'éloigne et appelle le guerrier par son nom ; le
     * guerrier répond, vient le tuer et le dit.
     */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void creeperAlerteLeGuerrier(GameTestHelper h) {
        BlockPos b = base(h, 0);
        Travailleur g = guerrier(h, b);
        g.inventaire.addItem(new ItemStack(Items.BOW));
        g.inventaire.addItem(new ItemStack(Items.ARROW, 32));
        Travailleur m = TestsJeu.employe(h, "mineur", b, Vec3.atBottomCenterOf(b.east(20)), new ItemStack(Items.IRON_PICKAXE));
        m.chef = g.chef;
        TestsBucheron.depot(g).proprietaire = g.chef;
        m.mode = Travailleur.Mode.ATTENDRE;
        m.temperament = "calme"; // il appelle le guerrier par son nom (le colérique recule sans crier à l'aide : Bravoure)
        m.forcerChunk();
        var creeper = TestsGuerrier.fige(h, EntityType.CREEPER, b.east(27));
        boolean[] loin = {false};
        h.succeedWhen(() -> {
            if (m.distanceTo(creeper) > 10) loin[0] = true;
            h.assertTrue(TestsCaractere.aDit(m, "creeper_alerte"), "le mineur prévient le guerrier (" + m.paroles + ", " + TestsJeu.etat(m, b) + ")");
            h.assertTrue(TestsCaractere.aDit(g, "guerrier_alerte_ok"), "le guerrier répond (" + g.paroles + ")");
            h.assertTrue(loin[0], "le mineur s'éloigne du creeper");
            h.assertFalse(creeper.isAlive(), "le creeper est tué (" + TestsJeu.etat(g, b) + ")");
            h.assertTrue(TestsCaractere.aDit(g, "guerrier_monstre_tue"), "le guerrier le dit (" + g.paroles + ")");
            TestsGuerrier.fin(g, m);
        });
    }

    /** Sans arc, le guerrier appelé pour un creeper ne va pas le frapper au corps à corps : il le dit et le laisse. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void sansArcPasDeCorpsACorpsAvecUnCreeper(GameTestHelper h) {
        BlockPos b = base(h, 6);
        Travailleur g = guerrier(h, b);
        Travailleur m = TestsJeu.employe(h, "mineur", b, Vec3.atBottomCenterOf(b.east(20)), new ItemStack(Items.IRON_PICKAXE));
        m.chef = g.chef;
        TestsBucheron.depot(g).proprietaire = g.chef;
        m.mode = Travailleur.Mode.ATTENDRE;
        m.temperament = "calme"; // il appelle le guerrier par son nom (le colérique recule sans crier à l'aide : Bravoure)
        m.forcerChunk();
        var creeper = TestsGuerrier.fige(h, EntityType.CREEPER, b.east(27));
        String[] faute = {null};
        h.onEachTick(() -> {
            if (g.action instanceof Actions.Attaquer a && a.cible == creeper && faute[0] == null) faute[0] = "il part frapper le creeper au corps à corps (" + TestsJeu.etat(g, b) + ")";
        });
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertTrue(faute[0] == null, faute[0]);
            h.assertTrue(creeper.isAlive(), "le creeper n'est pas frappé");
            h.assertTrue(TestsCaractere.aDit(m, "creeper_alerte"), "le mineur a bien appelé (" + m.paroles + ")");
            creeper.discard();
            TestsGuerrier.fin(g, m);
            h.succeed();
        });
    }

    /** Le jour, un monstre à 20 blocs du poste, hors de sa vue de garde : le guerrier va le tuer. */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void gardeChasseLeMonstrePresDuPoste(GameTestHelper h) {
        BlockPos b = base(h, 1);
        Travailleur g = guerrier(h, b);
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.north(20));
        h.succeedWhen(() -> {
            h.assertFalse(husk.isAlive(), "le monstre près du poste est tué (" + TestsJeu.etat(g, b) + ")");
            TestsGuerrier.fin(g);
        });
    }

    /** Un monstre à 6 blocs d'un ouvrier, près du poste : il le signale (et la garde l'apprend). */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void ouvrierSignaleUnMonstre(GameTestHelper h) {
        BlockPos b = base(h, 5);
        Travailleur m = TestsJeu.employe(h, "mineur", b, Vec3.atBottomCenterOf(b.east(3)), new ItemStack(Items.IRON_PICKAXE));
        m.mode = Travailleur.Mode.ATTENDRE;
        m.temperament = "calme"; // il signale (les autres caractères chargent, fuient ou s'éclipsent : Bravoure)
        m.forcerChunk();
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(9));
        h.succeedWhen(() -> {
            h.assertTrue(TestsCaractere.aDit(m, "monstre_vu"), "il signale le monstre (" + m.paroles + ", " + TestsJeu.etat(m, b) + ")");
            h.assertTrue(m.memoire.getInt("alerte") == husk.getId(), "la garde l'apprendra");
            husk.discard();
            TestsGuerrier.fin(m);
        });
    }

    static long heureAvant;

    @BeforeBatch(batch = "defense_nuit")
    public static void avantNuit(ServerLevel l) { heureAvant = l.getDayTime(); l.setDayTime(18000); }

    @AfterBatch(batch = "defense_nuit")
    public static void apresNuit(ServerLevel l) { l.setDayTime(heureAvant); }

    /** La nuit, hors de son service, il se repose au poste ; un monstre à 16 blocs du poste : il se lève et va le tuer. */
    @GameTest(template = "vide", batch = "defense_nuit", timeoutTicks = 900)
    public static void gardeAuReposSeLeve(GameTestHelper h) {
        BlockPos b = base(h, 2);
        Travailleur g = guerrier(h, b);
        g.debut = 6;
        g.fin = 18;
        long[] pose = {-1};
        h.succeedWhen(() -> {
            if (pose[0] < 0) {
                h.assertTrue(g.action instanceof Actions.Reposer, "il se repose d'abord (" + TestsJeu.etat(g, b) + ")");
                pose[0] = h.getTick();
                TestsGuerrier.fige(h, EntityType.HUSK, b.north(16));
            }
            var husks = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.Husk.class, new net.minecraft.world.phys.AABB(b).inflate(24));
            h.assertTrue(husks.isEmpty(), "le monstre est tué (" + TestsJeu.etat(g, b) + ")");
            TestsGuerrier.fin(g);
        });
    }

    /** La nuit, en service à la garde : il fait une ronde autour du poste (plus loin que les 6 blocs de la garde) et l'annonce. */
    @GameTest(template = "vide", batch = "defense_nuit", timeoutTicks = 900)
    public static void rondeDeNuit(GameTestHelper h) {
        BlockPos b = base(h, 3);
        Travailleur g = guerrier(h, b);
        int[] loin = {0};
        h.succeedWhen(() -> {
            loin[0] = Math.max(loin[0], TestsJeu.distance(g, b));
            h.assertTrue(TestsCaractere.aDit(g, "ronde_nuit"), "il annonce sa ronde (" + g.paroles + ")");
            h.assertTrue(loin[0] >= 9, "il tourne autour du poste, plus loin que sa garde (" + loin[0] + " blocs au plus, " + TestsJeu.etat(g, b) + ")");
            TestsGuerrier.fin(g);
        });
    }

    @BeforeBatch(batch = "abri_rappel")
    public static void avantRappel(ServerLevel l) { heureAvant = l.getDayTime(); l.setDayTime(14000); }

    @AfterBatch(batch = "abri_rappel")
    public static void apresRappel(ServerLevel l) { l.setDayTime(heureAvant); }

    /**
     * Rappelé par son chef au milieu du creusage de son abri : il rebouche le trou commencé avec ce qu'il en a tiré (rien
     * de plus, rien de moins dans son sac), puis il rentre.
     */
    @GameTest(template = "vide", batch = "abri_rappel", timeoutTicks = 1200)
    public static void trouInterrompuRebouche(GameTestHelper h) {
        BlockPos bureau = base(h, 7);
        BlockPos ici = TestsJeu.sol(h, 7 * 400 + 200, 114000);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(ici), new ItemStack(Items.IRON_PICKAXE));
        t.debut = 6;
        t.fin = 18;
        t.forcerChunk();
        BlockPos[] trou = {null};
        int[] prof = {0};
        h.succeedWhen(() -> {
            if (trou[0] == null) {
                var e = Abri.trou(t);
                h.assertTrue(e != null && e.getInt("prof") >= 1 && t.action instanceof Abri.Creuser, "il commence son trou (" + TestsJeu.etat(t, bureau) + ")");
                trou[0] = Abri.place(e);
                prof[0] = e.getInt("prof");
                t.finirAction("interrompu"); // « Rentre ! »
                t.memoire.putBoolean("rappel", true);
            }
            h.assertFalse(t.memoire.contains("abri"), "il rebouche son trou (" + TestsJeu.etat(t, bureau) + ")");
            for (int k = 1; k <= prof[0]; k++) h.assertTrue(Monde.solide(h.getLevel(), trou[0].below(k)), "le trou est rebouché (" + k + " sur " + prof[0] + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:dirt")) == 0, "il a reposé ce qu'il a tiré, ni plus ni moins (" + t.compter(Actions.Filtre.de("minecraft:dirt")) + " terre)");
            h.assertTrue(TestsJeu.distance(t, ici) > 4, "puis il rentre (" + TestsJeu.etat(t, bureau) + ")");
            t.discard();
        });
    }

    @BeforeBatch(batch = "abri")
    public static void avantAbri(ServerLevel l) { heureAvant = l.getDayTime(); l.setDayTime(14000); }

    @AfterBatch(batch = "abri")
    public static void apresAbri(ServerLevel l) { l.setDayTime(heureAvant); }

    /**
     * Sorti pour la journée, à 200 blocs du poste, la nuit tombée et son service fini : il ne rentre pas dans le noir. Il le
     * dit, écrit à son chef, se creuse un trou de 3 blocs et le ferme au-dessus de sa tête. Au matin, il en sort en le
     * rebouchant.
     */
    @GameTest(template = "vide", batch = "abri", timeoutTicks = 2400)
    public static void abriLaNuitDehors(GameTestHelper h) {
        BlockPos bureau = base(h, 4);
        BlockPos ici = TestsJeu.sol(h, 4 * 400 + 200, 114000);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(ici), new ItemStack(Items.IRON_PICKAXE));
        t.debut = 6;
        t.fin = 18;
        t.forcerChunk();
        boolean[] cache = {false};
        BlockPos[] toits = {null};
        h.succeedWhen(() -> {
            ServerLevel l = h.getLevel();
            if (!cache[0]) {
                var e = Abri.trou(t);
                h.assertTrue(e != null && e.getBoolean("ferme"), "il se creuse un abri (" + TestsJeu.etat(t, bureau) + ")");
                BlockPos toit = Abri.place(e).below();
                h.assertTrue(t.blockPosition().equals(toit.below(2)) && t.action instanceof Actions.Reposer, "il s'y repose (" + TestsJeu.etat(t, bureau) + ")");
                h.assertTrue(Monde.solide(l, toit), "le trou est fermé au-dessus de sa tête");
                h.assertTrue(TestsCaractere.aDit(t, "nuit_cache") && TestsCaractere.aDit(t, "nuit_camp"), "il le dit (" + t.paroles + ")");
                h.assertTrue(Math.abs(t.getBlockY() - ici.getY()) == 3 && TestsJeu.distance(t, ici) <= 2, "à sa place, trois blocs sous terre");
                cache[0] = true;
                toits[0] = toit;
                l.setDayTime(1000); // le matin
            }
            h.assertFalse(t.memoire.contains("abri"), "au matin, il sort (" + TestsJeu.etat(t, bureau) + ")");
            h.assertTrue(t.getBlockY() == ici.getY(), "il est remonté au niveau du sol (" + TestsJeu.etat(t, bureau) + ")");
            for (int k = 0; k < 3; k++) h.assertTrue(Monde.solide(l, toits[0].below(k)), "le trou est rebouché (" + k + ")");
            h.assertTrue(TestsCaractere.aDit(t, "matin_reprise"), "il le dit (" + t.paroles + ")");
            t.discard();
        });
    }
}

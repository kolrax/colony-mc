package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Les trajets « GPS » (plan A) : un PNJ retenu par un obstacle que le calcul croit franchissable (une cabosse de cacao à
 * hauteur de tête, comme la noix de coco du rapport de KOLRAX) ne reste pas planté : il change de chemin. Le lot
 * « trajets_temoin » coupe le changement de chemin, pour montrer que le couloir bloque bien sans lui.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsTrajets {
    static int immobileAvant;

    @BeforeBatch(batch = "trajets_temoin")
    public static void avant(ServerLevel l) { immobileAvant = Reglages.actuel.trajet_immobile_ticks; Reglages.actuel.trajet_immobile_ticks = 1_000_000; }

    @AfterBatch(batch = "trajets_temoin")
    public static void apres(ServerLevel l) { Reglages.actuel.trajet_immobile_ticks = immobileAvant; }

    /**
     * Un couloir de deux blocs de large, de x = 2 à 21, le long de z = 11 et 12 ; en (11, 5, 12), une cabosse de cacao mûre
     * accrochée à une bûche de jungle du mur, à hauteur de tête, sur la ligne droite du départ (3, 4, 12) au bureau (20, 4, 12).
     */
    static Travailleur couloir(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 8; z < 16; z++) {
            for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
            for (int y = 4; y <= 7; y++) h.setBlock(x, y, z, Blocks.AIR);
        }
        for (int x = 1; x <= 22; x++) for (int y = 4; y <= 6; y++) { h.setBlock(x, y, 10, Blocks.STONE); h.setBlock(x, y, 13, Blocks.STONE); }
        for (int z = 11; z <= 12; z++) for (int y = 4; y <= 6; y++) { h.setBlock(1, y, z, Blocks.STONE); h.setBlock(22, y, z, Blocks.STONE); }
        h.setBlock(11, 5, 13, Blocks.JUNGLE_LOG);
        h.setBlock(11, 5, 12, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, Direction.SOUTH).setValue(CocoaBlock.AGE, 2));
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 4, 12, new ItemStack(Items.BREAD), new BlockPos(20, 4, 12));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        return t;
    }

    /** Le plus long arrêt sur place (moins d'un demi-bloc parcouru), en ticks, relevé à chaque tick. */
    static int[] arretMax(GameTestHelper h, Travailleur t) {
        int[] r = {0};
        Vec3[] repere = {t.position()};
        int[] depuis = {0};
        h.onEachTick(() -> {
            Vec3 p = t.position();
            if (Itineraire.horizontale(p, repere[0]) >= 0.5) { repere[0] = p; depuis[0] = 0; }
            else r[0] = Math.max(r[0], ++depuis[0]);
        });
        return r;
    }

    static boolean arrive(Travailleur t) { return t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16; }

    static String etat(Travailleur t, int[] arret) {
        return TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", plus long arrêt " + arret[0] + " ticks, en " + t.blockPosition().toShortString();
    }

    /** La cabosse de cacao n'est plus vue comme franchissable : il prend l'autre file du couloir, sans s'arrêter. */
    @GameTest(template = "grand", timeoutTicks = 240, skyAccess = true)
    public static void passeACoteDuCacao(GameTestHelper h) {
        Travailleur t = couloir(h);
        int[] arret = arretMax(h, t);
        h.succeedWhen(() -> {
            h.assertTrue(arrive(t), "il doit arriver au bureau (" + etat(t, arret) + ")");
            h.assertTrue(arret[0] < 60, "il ne doit pas rester 3 s sur place (" + etat(t, arret) + ")");
            t.discard();
        });
    }

    /**
     * Le même couloir, mais pour lui la cabosse est franchissable (comme un bloc d'un autre mod que le calcul ne connaît
     * pas) : il s'y arrête, et au bout de 3 s il refait un trajet qui évite cet endroit. Il arrive, sans rester 10 s planté.
     */
    @GameTest(template = "grand", timeoutTicks = 300, skyAccess = true)
    public static void changeDeCheminSIlEstRetenu(GameTestHelper h) {
        Travailleur t = couloir(h);
        t.setPathfindingMalus(PathType.COCOA, 0f);
        int[] arret = arretMax(h, t);
        h.succeedWhen(() -> {
            h.assertTrue(arrive(t), "il doit arriver au bureau (" + etat(t, arret) + ")");
            h.assertTrue(arret[0] < 200, "il ne doit pas rester 10 s sur place (" + etat(t, arret) + ")");
            t.discard();
        });
    }

    /**
     * Plan A1 : un long trajet sur du plat (100 blocs, terrain chargé) est calculé en entier avant de partir, comme par un
     * GPS, puis suivi d'un bout à l'autre : plus aucune recherche de chemin une fois parti, et jamais un pas en arrière.
     * Avant, il cherchait une nouvelle étape à chaque fois qu'il en atteignait une, puis le chemin direct près du but.
     */
    @GameTest(template = "vide", timeoutTicks = 900, skyAccess = true)
    public static void trajetCalculeAvantDePartir(GameTestHelper h) {
        Travailleur t = longTrajet(h, 151000, false, 900);
        Vec3 depart = t.position(), but = Vec3.atBottomCenterOf(TestsJeu.sol(h, 100, 151000));
        int[] auDepart = {-1};
        double[] meilleure = {Double.MAX_VALUE}, recul = {0};
        h.onEachTick(() -> {
            if (auDepart[0] < 0 && Itineraire.horizontale(t.position(), depart) > 3) auDepart[0] = t.recherches;
            double d = Itineraire.horizontale(t.position(), but);
            meilleure[0] = Math.min(meilleure[0], d);
            recul[0] = Math.max(recul[0], d - meilleure[0]);
        });
        h.succeedWhen(() -> {
            String e = "à " + (int) Itineraire.horizontale(t.position(), but) + " blocs du but, " + t.recherches + " recherches dont "
                    + auDepart[0] + " avant de partir, plus grand recul " + String.format("%.1f", recul[0]) + " blocs, " + t.diag;
            h.assertTrue(t.position().distanceToSqr(but) < 9, "il doit arriver (" + e + ")");
            t.discard();
            liberer(h);
            h.assertTrue(auDepart[0] > 0 && t.recherches - auDepart[0] <= 1, "le trajet doit être calculé avant de partir, pas en route (" + e + ")");
            h.assertTrue(recul[0] < 2, "il ne doit jamais revenir sur ses pas (" + e + ")");
        });
    }

    /**
     * Le même long trajet, coupé à mi-chemin par un mur percé d'une porte en bois sur sa route. Il l'ouvre : le jeu refait
     * alors de lui-même son chemin (un bloc a changé tout près). Il garde ce chemin, qui mène au bout de la même étape : pas
     * de nouveau calcul de son trajet, pas d'arrêt d'une seconde.
     */
    @GameTest(template = "vide", timeoutTicks = 900, skyAccess = true)
    public static void passeUnePorteSansRecalculer(GameTestHelper h) {
        Travailleur t = longTrajet(h, 152000, true, 900);
        BlockPos porte = PORTES.get(h);
        boolean[] ouverte = {false};
        Vec3 depart = t.position(), but = Vec3.atBottomCenterOf(TestsJeu.sol(h, 100, 152000));
        int[] auDepart = {-1}, arret = {0}, depuis = {0};
        Vec3[] repere = {depart};
        h.onEachTick(() -> {
            Vec3 p = t.position();
            if (auDepart[0] < 0 && Itineraire.horizontale(p, depart) > 3) auDepart[0] = t.recherchesEtape;
            if (h.getLevel().getBlockState(porte).getOptionalValue(net.minecraft.world.level.block.DoorBlock.OPEN).orElse(false)) ouverte[0] = true;
            if (auDepart[0] < 0 || Itineraire.horizontale(p, repere[0]) >= 0.5) { repere[0] = p; depuis[0] = 0; }
            else arret[0] = Math.max(arret[0], ++depuis[0]);
        });
        h.succeedWhen(() -> {
            String e = "à " + (int) Itineraire.horizontale(t.position(), but) + " blocs du but, " + t.recherchesEtape + " recherches d'étape dont "
                    + auDepart[0] + " avant de partir, " + t.recherches + " en tout, plus long arrêt " + arret[0] + " ticks, " + t.diag;
            h.assertTrue(t.position().distanceToSqr(but) < 9, "il doit arriver (" + e + ")");
            t.discard();
            liberer(h);
            h.assertTrue(ouverte[0], "il doit passer par la porte (" + e + ")");
            h.assertTrue(auDepart[0] > 0 && t.recherchesEtape == auDepart[0], "la porte ne doit pas faire recalculer son trajet (" + e + ")");
            h.assertTrue(arret[0] < 20, "il ne doit pas s'arrêter une seconde à la porte (" + e + ")");
        });
    }

    static final java.util.Map<GameTestHelper, BlockPos> PORTES = new java.util.HashMap<>();
    static final java.util.Map<GameTestHelper, java.util.List<net.minecraft.world.level.ChunkPos>> FORCES = new java.util.HashMap<>();

    /**
     * Un ouvrier qui part pour 100 blocs vers l'est, sur le monde plat à {@code dz} de ce test, terrain chargé tout du long
     * (chunks forcés, libérés à la fin, réussite ou échec) ; {@code porte} : un mur de 3 blocs de haut à mi-chemin, de 49 blocs
     * de large, percé d'une porte en bois sur la ligne droite.
     */
    static Travailleur longTrajet(GameTestHelper h, int dz, boolean porte, int duree) {
        ServerLevel l = h.getLevel();
        BlockPos depart = TestsJeu.sol(h, 0, dz), arrivee = TestsJeu.sol(h, 100, dz);
        java.util.List<net.minecraft.world.level.ChunkPos> forces = new java.util.ArrayList<>();
        for (int x = depart.getX() - 32; x <= arrivee.getX() + 32; x += 16)
            for (int z = -32; z <= 32; z += 16) {
                var c = new net.minecraft.world.level.ChunkPos(new BlockPos(x, 0, depart.getZ() + z));
                forces.add(c);
                l.setChunkForced(c.x, c.z, true);
            }
        FORCES.put(h, forces);
        h.runAtTickTime(h.getTick() + duree - 2, () -> liberer(h));
        if (porte) {
            BlockPos m = TestsJeu.sol(h, 50, dz);
            for (int z = -24; z <= 24; z++) for (int y = 0; y < 3; y++) l.setBlockAndUpdate(m.offset(0, y, z), Blocks.STONE_BRICKS.defaultBlockState());
            var d = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST);
            l.setBlock(m, d.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
            PORTES.put(h, m);
            l.setBlock(m.above(), d.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        }
        Travailleur t = TestsJeu.employe(h, "mineur", depart, Vec3.atBottomCenterOf(depart), ItemStack.EMPTY);
        t.mode = Travailleur.Mode.ATTENDRE; // le cerveau ne lui donne rien d'autre à faire
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(arrivee), 2));
        return t;
    }

    static void liberer(GameTestHelper h) {
        PORTES.remove(h);
        var forces = FORCES.remove(h);
        if (forces != null) for (var c : forces) h.getLevel().setChunkForced(c.x, c.z, false);
    }

    /** Témoin : sans le changement de chemin (lot « trajets_temoin »), la même cabosse le retient bien sur place. */
    @GameTest(template = "grand", timeoutTicks = 300, skyAccess = true, batch = "trajets_temoin")
    public static void temoinResteRetenuSansDetour(GameTestHelper h) {
        Travailleur t = couloir(h);
        t.setPathfindingMalus(PathType.COCOA, 0f);
        int[] arret = arretMax(h, t);
        h.runAtTickTime(h.getTick() + 260, () -> {
            String e = etat(t, arret);
            t.discard();
            h.assertFalse(arrive(t), "le témoin ne doit pas arriver : le couloir ne le retiendrait pas (" + e + ")");
            h.assertTrue(arret[0] >= 100, "le témoin doit rester retenu sur place (" + e + ")");
            h.succeed();
        });
    }
}

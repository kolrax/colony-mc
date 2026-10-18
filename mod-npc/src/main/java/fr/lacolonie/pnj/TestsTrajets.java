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

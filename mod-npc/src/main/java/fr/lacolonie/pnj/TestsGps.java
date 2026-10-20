package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;

/**
 * Le GPS ({@link Gps}, KOLRAX le 20/10) : la route du trajet entier, calculée sur le relief. Une colline qui se monte à pied
 * se franchit tout droit (elle n'est pas évitée) ; une paroi trop raide, la route en fait le tour.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsGps {
    /** Le sol de pierre (y 1 à 3) sur toute la structure. */
    static void sol(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
    }

    /** Une colline en marches (un bloc par bloc, 6 de haut) d'un bord à l'autre : la route passe par-dessus, tout droit. */
    @GameTest(template = "grand", timeoutTicks = 40, skyAccess = true)
    public static void collineFranchieToutDroit(GameTestHelper h) {
        sol(h);
        for (int x = 6; x <= 17; x++) {
            int haut = Math.min(x - 5, 18 - x); // 1, 2… 6, 6… 1
            for (int z = 0; z < 24; z++) for (int y = 4; y < 4 + Math.min(haut, 6); y++) h.setBlock(x, y, z, Blocks.STONE);
        }
        BlockPos de = h.absolutePos(new BlockPos(1, 4, 12)), a = h.absolutePos(new BlockPos(22, 4, 12));
        List<BlockPos> r = Gps.route(h.getLevel(), de, a, new HashSet<>());
        h.assertTrue(!r.isEmpty() && Gps.cle(r.get(r.size() - 1)) == Gps.cle(a), "la route doit atteindre le but : " + r);
        for (BlockPos p : r) h.assertTrue(Math.abs(p.getZ() - a.getZ()) <= Gps.CASE * 2, "tout droit par-dessus la colline, pas de détour : " + r);
        h.assertTrue(r.stream().anyMatch(p -> p.getY() >= h.absolutePos(BlockPos.ZERO).getY() + 8), "par le haut de la colline : " + r);
        h.succeed();
    }

    /** Une paroi verticale de 8 blocs (x 10 à 13, z 0 à 19), ouverte au sud : la route en fait le tour, jamais par-dessus. */
    @GameTest(template = "grand", timeoutTicks = 40, skyAccess = true)
    public static void paroiContournee(GameTestHelper h) {
        sol(h);
        for (int x = 10; x <= 13; x++) for (int z = 0; z <= 19; z++) for (int y = 4; y <= 11; y++) h.setBlock(x, y, z, Blocks.STONE);
        BlockPos de = h.absolutePos(new BlockPos(2, 4, 4)), a = h.absolutePos(new BlockPos(21, 4, 4));
        List<BlockPos> r = Gps.route(h.getLevel(), de, a, new HashSet<>());
        h.assertTrue(!r.isEmpty() && Gps.cle(r.get(r.size() - 1)) == Gps.cle(a), "la route doit atteindre le but : " + r);
        int sol = h.absolutePos(BlockPos.ZERO).getY() + 4;
        for (BlockPos p : r) h.assertTrue(p.getY() <= sol + 1, "jamais sur la paroi : " + r);
        h.succeed();
    }
}

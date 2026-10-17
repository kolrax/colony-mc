package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Les lieux de travail intelligents (refonte, partie 2 ; {@link Lieux}) : personne ne casse une construction, ni pour
 * travailler ni pour se débloquer ; le bûcheron ne coupe que des arbres naturels, hors de la colonie. Le lot « lieux »
 * tourne seul : le tampon et la colonie (ici de 16 blocs) n'y valent que là ({@link Lieux#enEssai}).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsLieux {
    static int rayonAvant;

    @BeforeBatch(batch = "lieux")
    public static void avant(ServerLevel l) {
        Lieux.enEssai = true;
        rayonAvant = Reglages.actuel.rayon_colonie;
        Reglages.actuel.rayon_colonie = 16;
    }

    @AfterBatch(batch = "lieux")
    public static void apres(ServerLevel l) {
        Lieux.enEssai = false;
        Reglages.actuel.rayon_colonie = rayonAvant;
        // La fausse structure du test des villages : défaite à rebours, comme dans TestsMineur.
        for (int i = TestsMineur.A_DEFAIRE.size() - 1; i >= 0; i--) TestsMineur.A_DEFAIRE.get(i).run();
        TestsMineur.A_DEFAIRE.clear();
    }

    /** Un bûcheron seul dans son coin du monde plat, poste en {@code base}, posé à {@code dx} blocs à l'est. */
    static Travailleur bucheron(GameTestHelper h, BlockPos base, int dx) {
        Travailleur t = TestsJeu.employe(h, "bucheron", base, Vec3.atBottomCenterOf(base.east(dx)), new ItemStack(Items.IRON_AXE));
        t.forcerChunk();
        return t;
    }

    /**
     * Un arbre de décoration hors de la colonie : sa couronne est faite de feuilles posées à la main (persistantes), avec
     * quelques feuilles naturelles collées au tronc. Il n'y touche pas. Avant : 4 feuilles naturelles suffisaient, il l'abattait.
     */
    @GameTest(template = "vide", timeoutTicks = 500, batch = "lieux")
    public static void arbreDeDecorationPasCoupe(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos base = TestsJeu.sol(h, 0, 130000);
        BlockPos pied = base.east(30);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 3; y <= 5; y++)
            l.setBlockAndUpdate(pied.offset(dx, y, dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        for (BlockPos q : new BlockPos[]{pied.offset(1, 1, 0), pied.offset(-1, 1, 0), pied.offset(0, 1, 1), pied.offset(0, 1, -1), pied.offset(1, 2, 0), pied.offset(-1, 2, 0)})
            l.setBlockAndUpdate(q, TestsBucheron.feuille(Blocks.OAK_LEAVES));
        for (int y = 0; y < 5; y++) l.setBlockAndUpdate(pied.above(y), Blocks.OAK_LOG.defaultBlockState());
        Travailleur t = bucheron(h, base, 26);
        h.runAtTickTime(h.getTick() + 400, () -> {
            String e = TestsJeu.etat(t, t.bureau);
            t.discard();
            for (int y = 0; y < 5; y++) h.assertTrue(l.getBlockState(pied.above(y)).is(Blocks.OAK_LOG), "l'arbre de décoration ne doit pas être coupé (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Un arbre naturel hors de la colonie, mais dans l'emprise d'une structure générée (un village) : il n'y touche pas.
     * Avant : seul le bloc du pied était regardé, et seulement pour le mineur ; le joueur fantôme a vu 40 bûches coupées
     * dans un village.
     */
    @GameTest(template = "vide", timeoutTicks = 500, batch = "lieux")
    public static void arbreDeVillagePasCoupe(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos base = TestsJeu.sol(h, 0, 136000);
        BlockPos pied = base.east(30);
        TestsBucheron.arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        TestsMineur.puitsDeMine(l, pied.offset(-6, -1, -6), pied.offset(6, 8, 6));
        Travailleur t = bucheron(h, base, 26);
        h.runAtTickTime(h.getTick() + 400, () -> {
            String e = TestsJeu.etat(t, t.bureau);
            t.discard();
            for (int y = 0; y < 5; y++) h.assertTrue(l.getBlockState(pied.above(y)).is(Blocks.OAK_LOG), "l'arbre du village ne doit pas être coupé (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Deux arbres naturels : l'un dans la colonie (à 13 blocs du poste), l'autre dehors (à 30). Le bûcheron, entre les deux
     * et plus près du premier, coupe celui du dehors et laisse celui de la colonie. Avant : il coupait le plus proche.
     */
    @GameTest(template = "vide", timeoutTicks = 900, batch = "lieux")
    public static void arbreNaturelHorsColonieSeulement(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos base = TestsJeu.sol(h, 0, 133000);
        BlockPos dedans = base.east(13), dehors = base.east(30);
        TestsBucheron.arbre(l, dedans, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        TestsBucheron.arbre(l, dehors, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        Travailleur t = bucheron(h, base, 21);
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau);
            h.assertTrue(l.getBlockState(dedans).is(Blocks.OAK_LOG), "l'arbre de la colonie ne doit pas être coupé (" + e + ")");
            h.assertTrue(!l.getBlockState(dehors).is(Blocks.OAK_LOG), "l'arbre du dehors doit être coupé (" + e + ")");
            t.discard();
        });
    }

    /**
     * Un filon de charbon dans le sol, juste au pied d'une maison en planches : le mineur n'ouvre pas de trou là (tampon de
     * 8 blocs autour d'une construction). Avant : il extrayait tout filon qu'il voyait.
     */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "lieux")
    public static void mineurPasDeTrouPresDUneMaison(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 6; x <= 8; x++) for (int z = 2; z <= 6; z++) for (int y = 2; y <= 4; y++)
            if (x == 6 || x == 8 || z == 2 || z == 6 || y == 4) h.setBlock(x, y, z, Blocks.OAK_PLANKS);
        h.setBlock(5, 1, 4, Blocks.COAL_ORE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 2, 4, new ItemStack(Items.IRON_PICKAXE));
        t.mission("charbon", "stock", 64);
        h.runAtTickTime(h.getTick() + 300, () -> {
            String e = TestsJeu.etat(t, t.bureau);
            t.discard();
            h.assertBlockPresent(Blocks.COAL_ORE, 5, 1, 4);
            for (int x = 6; x <= 8; x++) for (int z = 2; z <= 6; z++) for (int y = 2; y <= 4; y++)
                if (x == 6 || x == 8 || z == 2 || z == 6 || y == 4) h.assertTrue(h.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_PLANKS), "la maison doit rester entière (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Enfermé dans une cabane aux murs de pavés (posés par un joueur) de 4 blocs de haut, une pioche en main et aucun bloc à
     * poser, son bureau dehors : il ne se creuse pas de passage à travers les murs. Avant : le pavé n'était pas compté comme
     * une construction, et son escalier de déblocage le cassait.
     */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true, batch = "lieux")
    public static void coinceDansUneCabaneNeCassePasLesMurs(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) for (int y = 4; y <= 7; y++) h.setBlock(12 + dx, y, 12 + dz, Blocks.COBBLESTONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 4, 2));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(h.getTick() + 400, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            t.discard();
            int murs = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 7; y++)
                if (h.getBlockState(new BlockPos(12 + dx, y, 12 + dz)).is(Blocks.COBBLESTONE)) murs++;
            h.assertTrue(murs == 32, "il ne doit pas casser les murs de la cabane : " + murs + "/32 (" + e + ")");
            h.succeed();
        });
    }
}

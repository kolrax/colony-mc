package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Le mineur sous terre (corrections A4, E3 et E4) : devant un obstacle, il reste à son niveau et le contourne au lieu de
 * remonter à la surface ; il éclaire vraiment sa galerie ; il prend des torches au dépôt quand il n'en a plus.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMineurSousTerre {

    /**
     * Un bloc de pierre de 24 sur 24 (y de 1 à 10), et un mineur déjà au fond de son puits, en (fx, 3, fz) : sa mine est
     * ouverte, il commence le couloir de ses galeries. Le poste est en surface, au nord-ouest.
     */
    static Travailleur auFond(GameTestHelper h, int fx, int fz, ItemStack... sac) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(fx, 3, fz, Blocks.AIR);
        h.setBlock(fx, 4, fz, Blocks.AIR);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", fx, 3, fz, new ItemStack(Items.DIAMOND_PICKAXE), new BlockPos(1, 11, 1));
        t.niveau = 5;
        t.mission("charbon", "stock", 64);
        for (ItemStack s : sac) t.inventaire.addItem(s);
        t.profondeurForcee = h.absolutePos(new BlockPos(0, 3, 0)).getY();
        var m = t.memoire;
        m.putString("chantier", "charbon@" + Mineur.cle(t.bureau) + "/" + t.profondeurForcee);
        BlockPos puits = h.absolutePos(new BlockPos(fx, 11, fz));
        m.putLong("puits", puits.asLong());
        m.putLong("mine", puits.asLong());
        m.putInt("entree", puits.getY());
        m.putLong("fond", h.absolutePos(new BlockPos(fx, 3, fz)).asLong());
        m.putInt("cote", 1);
        m.putInt("branche", 1);
        m.putInt("couloir", 0);
        m.putInt("pas", 0);
        return t;
    }

    /** Il ne monte jamais au-dessus de son niveau de galeries (y = 3 du test, plus un bloc de marge). */
    static void resteAuNiveau(GameTestHelper h, Travailleur t) {
        int max = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        h.onEachTick(() -> h.assertTrue(t.getBlockY() <= max, "il est remonté (y=" + t.getBlockY() + ", " + TestsJeu.etat(t, t.bureau) + ")"));
    }

    /**
     * A4 : son couloir est barré des deux côtés par un bloc qu'il ne peut pas casser. Avant, il déclarait son puits fini et
     * remontait creuser un nouvel escalier ; maintenant, il ouvre une galerie de côté, à son niveau, vers l'ouest.
     */
    @GameTest(template = "grand", timeoutTicks = 1600)
    public static void mineurContourneSousTerre(GameTestHelper h) {
        Travailleur t = auFond(h, 20, 12, new ItemStack(Items.COBBLESTONE, 16));
        for (int y = 2; y <= 5; y++) { h.setBlock(20, y, 11, Blocks.BEDROCK); h.setBlock(20, y, 13, Blocks.BEDROCK); }
        resteAuNiveau(h, t);
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.STONE, 15, 3, 12);
            t.discard();
        });
    }

    /**
     * A4 et E4 : de la lave dans les parois de son couloir, des deux côtés (sous le sol d'à côté et au-dessus de la paroi).
     * Avant, il abandonnait les deux côtés et remontait ; maintenant, il la bouche avec la pierre de son sac et continue, sans
     * jamais prendre feu.
     */
    @GameTest(template = "grand", timeoutTicks = 1600)
    public static void mineurBoucheLaLaveDuCouloir(GameTestHelper h) {
        Travailleur t = auFond(h, 12, 12, new ItemStack(Items.COBBLESTONE, 16));
        h.setBlock(13, 2, 14, Blocks.LAVA);
        h.setBlock(11, 5, 14, Blocks.LAVA);
        h.setBlock(13, 2, 10, Blocks.LAVA);
        h.setBlock(11, 5, 10, Blocks.LAVA);
        resteAuNiveau(h, t);
        h.onEachTick(() -> h.assertFalse(t.isOnFire() || t.isInLava(), "il ne doit jamais brûler (" + TestsJeu.etat(t, t.bureau) + ")"));
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.STONE, 12, 3, 15);
            h.assertBlockNotPresent(Blocks.LAVA, 13, 2, 14);
            h.assertBlockNotPresent(Blocks.LAVA, 11, 5, 14);
            t.discard();
        });
    }

    /**
     * E3 : il éclaire sa galerie (une torche tous les 8 blocs), et les torches restent. Avant, la torche se collait au bloc qu'il allait casser juste après
     * (il regarde devant lui), et tombait.
     */
    @GameTest(template = "grand", timeoutTicks = 2400)
    public static void mineurEclaireSaGalerie(GameTestHelper h) {
        Travailleur t = auFond(h, 1, 2, new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.TORCH, 16));
        resteAuNiveau(h, t);
        h.succeedWhen(() -> {
            h.assertTrue(t.memoire.getInt("pas") >= 18 || t.memoire.getInt("branche") > 1, "il doit avoir creusé sa galerie (" + TestsJeu.etat(t, t.bureau) + ")");
            int torches = 0;
            for (int x = 0; x <= 20; x++) for (int z = 1; z <= 6; z++) for (int y = 3; y <= 5; y++) {
                var b = h.getBlockState(new BlockPos(x, y, z));
                if (b.is(Blocks.WALL_TORCH) || b.is(Blocks.TORCH)) torches++;
            }
            h.assertTrue(torches >= 2, "deux torches doivent rester dans sa galerie (" + torches + ", " + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** E3 : sans torche, il en prend au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void mineurPrendDesTorches(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 2, 3, new ItemStack(Items.IRON_PICKAXE));
        Bureau.Entite b = (Bureau.Entite) h.getLevel().getBlockEntity(t.bureau);
        b.depot.addItem(new ItemStack(Items.TORCH, 32));
        t.mission("charbon", "stock", 64);
        h.succeedWhen(() -> {
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:torch")) > 0, "il doit avoir pris des torches (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }
}

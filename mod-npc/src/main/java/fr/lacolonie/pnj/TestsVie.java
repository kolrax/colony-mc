package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 5, les besoins (cahier 5.1) : lit à soi, pause (loisirs), répliques de vie.txt dites une fois, soin en mangeant.
 * Chaque test dans son coin du monde plat (coins 110 à 113), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsVie {
    static long dites(Travailleur t, String cle) { return t.paroles.stream().filter(p -> Vie.vient(cle, p)).count(); }

    /**
     * Deux ouvriers épuisés, un seul lit près du poste : le premier qui le prend le garde, l'autre ne le prend jamais et
     * dit qu'il n'a pas de lit. Puis le lit est cassé : son propriétaire le dit (lit_vole) et son moral baisse de 3.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void litASoiPasPrisParUnAutre(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 110 * 400, 60000);
        ServerLevel l = h.getLevel();
        BlockPos pied = base.east(3).south(2), tete = pied.north();
        TestsJeu.lit(l, pied);
        Travailleur a = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        Travailleur b = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(3)), new ItemStack(Items.IRON_PICKAXE));
        a.forcerChunk();
        for (Travailleur t : new Travailleur[]{a, b}) { t.fatigue = 100; t.moral = 70; }
        Travailleur[] proprio = {null};
        boolean[] partage = {false}, casse = {false};
        int[] moralAvant = {0};
        h.onEachTick(() -> {
            if (a.lit != null && a.lit.equals(b.lit)) partage[0] = true;
            if (proprio[0] == null) for (Travailleur t : new Travailleur[]{a, b})
                if (t.isSleeping() && tete.equals(t.getSleepingPos().orElse(null))) proprio[0] = t;
            Travailleur o = proprio[0];
            if (o != null && !casse[0] && dites(o == a ? b : a, "sans_lit") > 0) {
                casse[0] = true;
                moralAvant[0] = o.moral;
                l.setBlockAndUpdate(tete, Blocks.AIR.defaultBlockState());
                l.setBlockAndUpdate(pied, Blocks.AIR.defaultBlockState());
            }
        });
        h.succeedWhen(() -> {
            h.assertFalse(partage[0], "jamais le même lit pour deux");
            Travailleur o = proprio[0];
            h.assertTrue(o != null, "l'un d'eux dort dans le lit (" + TestsJeu.etat(a, base) + " / " + TestsJeu.etat(b, base) + ")");
            Travailleur autre = o == a ? b : a;
            h.assertTrue(autre.lit == null, "l'autre n'a pas de lit (" + autre.lit + ")");
            h.assertTrue(dites(autre, "sans_lit") == 1, "l'autre dit une fois qu'il n'a pas de lit (" + autre.paroles + ")");
            h.assertTrue(casse[0], "lit cassé");
            h.assertTrue(dites(o, "lit_vole") == 1, "son lit cassé, il le dit une fois (" + o.paroles + ")");
            h.assertTrue(o.lit == null && o.moral <= moralAvant[0] - 3, "lit perdu, moral " + moralAvant[0] + " → " + o.moral);
            a.discard();
            b.discard();
        });
    }

    /** Moral moyen et longtemps au travail : il s'assied un moment près du poste (moral + 2), puis il reprend et mine le filon. */
    @GameTest(template = "vide", timeoutTicks = 1500)
    public static void pausePrisePuisTravailRepris(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 111 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.mission("charbon", "stock", 64);
        t.moral = 50;
        t.travailSansPause = Vie.PAUSE_APRES;
        BlockPos filon = base.west(2).south(2);
        ServerLevel l = h.getLevel();
        l.setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        boolean[] pause = {false}, assis = {false}, finie = {false}, mine = {false}, mineApres = {false};
        int[] moral = {0, 0};
        h.onEachTick(() -> {
            if (t.action instanceof Besoins.Flaner f) {
                if (!pause[0]) { pause[0] = true; moral[0] = t.moral; }
                if (f.assis >= 0 && t.geste().equals("assis") && t.distanceToSqr(Vec3.atBottomCenterOf(base)) <= 25) assis[0] = true;
            } else if (pause[0] && !finie[0]) { finie[0] = true; moral[1] = t.moral; }
            if (!mine[0] && !l.getBlockState(filon).is(Blocks.COAL_ORE)) { mine[0] = true; mineApres[0] = finie[0]; }
        });
        h.succeedWhen(() -> {
            h.assertTrue(pause[0], "il prend une pause (" + TestsJeu.etat(t, base) + ")");
            h.assertTrue(assis[0], "assis près du poste");
            h.assertTrue(dites(t, "envie_pause") == 1, "il dit qu'il fait une pause (" + t.paroles + ")");
            h.assertTrue(finie[0], "la pause finit (" + TestsJeu.etat(t, base) + ")");
            h.assertTrue(moral[1] >= moral[0] + Obeissance.PAUSE, "moral " + moral[0] + " → " + moral[1]);
            h.assertTrue(mine[0], "puis il reprend le travail : le filon miné (" + TestsJeu.etat(t, base) + ")");
            h.assertTrue(mineApres[0], "miné après la pause, pas avant");
            h.assertTrue(t.travailSansPause < Vie.PAUSE_APRES, "le compte de travail repart de zéro");
            t.discard();
        });
    }

    /** Un petit creux, rien à manger : il le dit, mais une seule fois en 20 s (au plus une fois par 10 minutes). */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void faimDiteUneSeuleFois(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 112 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.faim = 11; // faim légère (12 au plus), pas encore assez pour aller chercher à manger (8)
        h.runAtTickTime(h.getTick() + 450, () -> {
            h.assertTrue(dites(t, "faim_legere") == 1, "faim légère dite une fois (" + t.paroles + ", faim " + t.faim + ")");
            h.assertTrue(dites(t, "faim_forte") == 0, "pas de faim forte");
            t.discard();
            h.succeed();
        });
    }

    /** Blessé (40 % de sa vie), pas affamé (16) : il mange son pain pour reprendre des forces. Avant, il attendait d'avoir faim (14). */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void blesseMangePourGuerir(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 113 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        t.faim = 16;
        t.setHealth(t.getMaxHealth() * 0.4f);
        h.succeedWhen(() -> {
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:bread")) < 3, "il mange un pain (" + TestsJeu.etat(t, base) + ", faim " + t.faim + ")");
            h.assertTrue(dites(t, "blessure_legere") == 1, "il dit sa blessure (" + t.paroles + ")");
            t.discard();
        });
    }
}

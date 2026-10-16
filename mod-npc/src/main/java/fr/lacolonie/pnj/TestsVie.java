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
     * dit qu'il n'a pas de lit. Puis le lit est cassé, sans autre lit libre : son propriétaire dit qu'il n'a plus de lit
     * (sans_lit, pas lit_vole : personne ne le lui a pris) et son moral baisse de 3.
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
        for (Travailleur t : new Travailleur[]{a, b}) {
            t.fatigue = 100;
            t.moral = 70;
            // Le cas le plus dur, toujours : son premier passage au cerveau tombe sur un passage des besoins (Besoins.veiller,
            // toutes les 100 ticks selon son identifiant), avant qu'il ne soit noté « épuisé ». Sans ça, le cas ne venait que
            // selon les identifiants, donc selon les tests passés avant (« fatigue » dite, puis « pas de lit » muet 60 s).
            t.tickCount = Math.floorMod(-t.getId() - 1, 100);
        }
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
            h.assertTrue(dites(o, "sans_lit") == 1 && dites(o, "lit_vole") == 0, "son lit cassé, il dit une fois qu'il n'a plus de lit (" + o.paroles + ")");
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

    /**
     * Blessé (40 % de sa vie), pas affamé (16) : il mange pour reprendre des forces (avant, il attendait d'avoir faim : 14). Il
     * choisit la carotte (3, juste ce qui manque) : ni la chair putréfiée (néfaste), ni la pomme dorée (rare), ni le steak (8, trop).
     */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void blesseMangePourGuerir(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 113 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.inventaire.addItem(new ItemStack(Items.ROTTEN_FLESH, 3));
        t.inventaire.addItem(new ItemStack(Items.GOLDEN_APPLE, 3));
        t.inventaire.addItem(new ItemStack(Items.COOKED_BEEF, 3));
        t.inventaire.addItem(new ItemStack(Items.CARROT, 3));
        t.faim = 16;
        t.setHealth(t.getMaxHealth() * 0.4f);
        h.succeedWhen(() -> {
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:carrot")) == 2, "il mange une carotte (" + TestsJeu.etat(t, base) + ", faim " + t.faim + ")");
            for (var i : java.util.List.of("rotten_flesh", "golden_apple", "cooked_beef"))
                h.assertTrue(t.compter(Actions.Filtre.de("minecraft:" + i)) == 3, "pas touché : " + i);
            h.assertTrue(dites(t, "blessure_legere") == 1, "il dit sa blessure (" + t.paroles + ")");
            t.discard();
        });
    }

    /** En pleine pause, le chef dit « au travail » : il se lève, et ne se rassoit pas aussitôt (rien pendant 200 ticks). */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void ordreCoupeLaPause(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 114 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.mission("charbon", "stock", 64);
        t.moral = 50;
        t.travailSansPause = Vie.PAUSE_APRES;
        long[] ordre = {-1};
        boolean[] rassis = {false};
        h.onEachTick(() -> {
            boolean pause = t.action instanceof Besoins.Flaner;
            if (ordre[0] < 0 && pause) { t.appliquer(Ordres.lire("au travail", "mineur"), null); ordre[0] = h.getTick(); }
            else if (ordre[0] >= 0 && pause) rassis[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(ordre[0] >= 0, "la pause commence (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(rassis[0], "il ne se rassoit pas après l'ordre (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(h.getTick() >= ordre[0] + 200, "200 ticks sans pause");
            t.discard();
        });
    }
}

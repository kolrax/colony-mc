package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Le banc de charge : 10, 50 et 100 ouvriers au travail sur le monde plat, chacun dans son lot (seul sur le serveur),
 * 200 ticks, puis la mesure au journal. Pas encore de seuil : le test vérifie seulement que la mesure existe.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCharge {

    /**
     * Le banc est toujours retiré à la fin (finBanc), même si une vérification échoue ou si le banc plante : sinon le
     * lot suivant tomberait sur « un banc tourne déjà ».
     */
    static void mesurer(GameTestHelper h, int n, int coin) {
        List<Travailleur> l;
        try {
            l = ColoniePnj.banc(h.getLevel(), TestsJeu.sol(h, coin * 1000, 80000), n, "");
            boolean refuse = false;
            try { ColoniePnj.banc(h.getLevel(), TestsJeu.sol(h, coin * 1000 + 100, 80000), 1, ""); }
            catch (IllegalStateException e) { refuse = true; }
            h.assertTrue(refuse, "un second banc doit être refusé tant que le premier tourne");
        } catch (RuntimeException e) {
            ColoniePnj.finBanc(h.getLevel().getServer());
            throw e;
        }
        h.runAtTickTime(h.getTick() + 200, () -> {
            Charge c = Charge.ICI;
            List<String> lignes;
            try {
                lignes = new java.util.ArrayList<>(ColoniePnj.charge(h.getLevel().getServer()));
                var actions = new java.util.TreeMap<String, Integer>(); // ce qu'ils font à la fin, pour lire la mesure
                for (Travailleur t : l) actions.merge(t.metier + ":" + (t.action == null ? "-" : t.action.nom), 1, Integer::sum);
                lignes.add(" - à la fin : " + actions);
            } finally {
                ColoniePnj.finBanc(h.getLevel().getServer());
            }
            for (String x : lignes) ColoniePnj.LOG.info("[banc {} PNJ] {}", n, x);
            h.assertTrue(l.size() == n, "il faut " + n + " ouvriers (" + l.size() + ")");
            h.assertTrue(c.ticks() == Charge.FENETRE, "la fenêtre doit être pleine (" + c.ticks() + " ticks)");
            h.assertTrue(c.pnjMoyen() >= n, "les " + n + " ouvriers doivent être comptés (" + c.pnjMoyen() + ")");
            h.assertTrue(c.moyenneMs(Charge.Partie.TOTAL) > 0 && c.pireMs(Charge.Partie.TOTAL) > 0, "la part du mod doit être mesurée");
            h.assertTrue(c.moyenneMs(Charge.Partie.CERVEAU) > 0 && c.moyenneMs(Charge.Partie.ACTION) > 0, "le cerveau et les actions doivent être mesurés");
            h.succeed();
        });
    }

    @GameTest(template = "vide", timeoutTicks = 400, skyAccess = true, batch = "charge10")
    public static void banc10(GameTestHelper h) { mesurer(h, 10, 0); }

    @GameTest(template = "vide", timeoutTicks = 400, skyAccess = true, batch = "charge50")
    public static void banc50(GameTestHelper h) { mesurer(h, 50, 1); }

    @GameTest(template = "vide", timeoutTicks = 400, skyAccess = true, batch = "charge100")
    public static void banc100(GameTestHelper h) { mesurer(h, 100, 2); }

    /**
     * Des chemins sobres (0.8.4) : un bûcheron dont le seul arbre est en haut d'un pilier de pierre (inaccessible), et un
     * ouvrier envoyé vers un point au milieu d'un enclos fermé. Sur 200 ticks, chacun cherche peu de chemins, jamais en
     * rafale sur un même tick, et l'action échoue quand même comme avant (« inaccessible », « bloque »). Un troisième,
     * enfermé dans un enclos et envoyé dehors, ne trouve aucune étape : il ne fait jamais plus de 8 recherches sur un
     * tick (avant la 0.8.4 : toutes ses étapes d'un coup).
     */
    @GameTest(template = "vide", timeoutTicks = 400, skyAccess = true, batch = "chemins")
    public static void cheminsSobres(GameTestHelper h) {
        var l = h.getLevel();
        // Le pilier : 5 blocs de pierre, un de terre, puis l'arbre (3 bûches et ses feuilles).
        BlockPos pilier = TestsJeu.sol(h, 0, 90000);
        for (int y = 0; y < 5; y++) l.setBlockAndUpdate(pilier.above(y), Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(pilier.above(5), Blocks.DIRT.defaultBlockState());
        for (int y = 6; y <= 8; y++) l.setBlockAndUpdate(pilier.above(y), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 8; y <= 9; y++)
            if (!(dx == 0 && dz == 0 && y == 8)) l.setBlockAndUpdate(pilier.offset(dx, y, dz), Blocks.OAK_LEAVES.defaultBlockState());
        Travailleur b = TestsJeu.employe(h, "bucheron", TestsJeu.sol(h, -8, 90000), Vec3.atBottomCenterOf(TestsJeu.sol(h, -5, 90000)), new ItemStack(Items.IRON_AXE));
        // L'enclos : des murs de briques de pierre de 3 blocs, fermés, 5 x 5 dedans ; l'ouvrier part de 10 blocs.
        BlockPos centre = TestsJeu.sol(h, 0, 90100);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            if (Math.abs(dx) == 3 || Math.abs(dz) == 3) for (int y = 0; y < 3; y++) l.setBlockAndUpdate(centre.offset(dx, y, dz), Blocks.STONE_BRICKS.defaultBlockState());
        Travailleur o = TestsJeu.employe(h, "mineur", TestsJeu.sol(h, -12, 90100), Vec3.atBottomCenterOf(TestsJeu.sol(h, -10, 90100)), ItemStack.EMPTY);
        o.mode = Travailleur.Mode.ATTENDRE; // le cerveau ne lui donne rien d'autre à faire
        // Le prisonnier : au milieu d'un enclos pareil, envoyé à 20 blocs dehors.
        BlockPos prison = TestsJeu.sol(h, 0, 90200);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            if (Math.abs(dx) == 3 || Math.abs(dz) == 3) for (int y = 0; y < 3; y++) l.setBlockAndUpdate(prison.offset(dx, y, dz), Blocks.STONE_BRICKS.defaultBlockState());
        Travailleur e = TestsJeu.employe(h, "mineur", TestsJeu.sol(h, -12, 90200), Vec3.atBottomCenterOf(prison), ItemStack.EMPTY);
        e.mode = Travailleur.Mode.ATTENDRE;
        b.forcerChunk();
        o.forcerChunk();
        e.forcerChunk();
        Cerveau.lancer(o, new Actions.Aller(Vec3.atBottomCenterOf(centre), 1));
        Cerveau.lancer(e, new Actions.Aller(Vec3.atBottomCenterOf(TestsJeu.sol(h, 20, 90200)), 1));
        Travailleur[] pnj = {b, o, e};
        List<List<String>> raisons = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Actions.Action[] derniere = new Actions.Action[3];
        h.onEachTick(() -> { // comment finit chaque action
            for (int i = 0; i < 3; i++) {
                Actions.Action a = pnj[i].action;
                if (a == derniere[i]) continue;
                if (derniere[i] != null) raisons.get(i).add(derniere[i].nom + ":" + derniere[i].raison);
                derniere[i] = a;
            }
        });
        int[] debut = {b.recherches, o.recherches, e.recherches};
        h.runAtTickTime(h.getTick() + 200, () -> {
            String bilan = "bûcheron " + (b.recherches - debut[0]) + " recherches (au plus " + b.recherchesPireTick + " sur un tick), actions " + raisons.get(0) + ", " + b.diag
                    + " ; ouvrier " + (o.recherches - debut[1]) + " recherches (au plus " + o.recherchesPireTick + " sur un tick), actions " + raisons.get(1) + ", " + o.diag
                    + " ; prisonnier " + (e.recherches - debut[2]) + " recherches (au plus " + e.recherchesPireTick + " sur un tick), actions " + raisons.get(2) + ", " + e.diag;
            ColoniePnj.LOG.info("[chemins sobres] {}", bilan);
            for (Travailleur t : pnj) t.discard();
            h.assertTrue(raisons.get(0).contains("abattre:inaccessible"), "le bûcheron doit renoncer à l'arbre perché (" + bilan + ")");
            h.assertTrue(raisons.get(1).contains("aller:bloque"), "l'ouvrier doit se dire bloqué devant l'enclos (" + bilan + ")");
            for (int i = 0; i < 2; i++) h.assertTrue(pnj[i].recherches - debut[i] < 40, "trop de recherches de chemin (" + bilan + ")");
            for (Travailleur t : pnj) h.assertTrue(t.recherchesPireTick <= 8, "trop de recherches sur un même tick (" + bilan + ")");
            h.succeed();
        });
    }
}

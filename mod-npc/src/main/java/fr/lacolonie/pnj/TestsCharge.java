package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
}

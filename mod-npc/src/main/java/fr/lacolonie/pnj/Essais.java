package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Les outils des tests en jeu d'équipe (repris de l'ancien TestsIA) : trois ouvriers autour du chef, des étapes
 * retentées jusqu'au délai, l'attente, le regard. Et le test des destinataires d'un ordre écrit.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class Essais {

    /** Trois ouvriers autour du chef : Bertrand le mineur (celui qu'il regarde), Raoul le guerrier, Odon le bûcheron. */
    static Travailleur[] equipe(GameTestHelper h, net.minecraft.server.level.ServerPlayer chef, int coin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur[] e = {TestsJeu.employe(h, "mineur", base, c.add(-2.5, 0, -0.5), ItemStack.EMPTY),
                TestsJeu.employe(h, "guerrier", base, c.add(1.5, 0, -0.5), ItemStack.EMPTY),
                TestsJeu.employe(h, "bucheron", base, c.add(-0.5, 0, 2.5), ItemStack.EMPTY)};
        String[] noms = {"Bertrand", "Raoul", "Odon"};
        for (int i = 0; i < 3; i++) { e[i].chef = chef.getUUID(); e[i].prenom = noms[i]; e[i].forcerChunk(); }
        return e;
    }

    /**
     * Un test d'équipe, étape par étape : d'abord attendre que les ouvriers soient visibles du chef (dans un tronçon tout
     * juste chargé, ils ne le sont qu'au tick suivant), puis chaque étape dans l'ordre. Une étape qui échoue est retentée
     * au tick suivant (une vérification) ; au bout du délai, le test échoue avec son message.
     */
    static void etapes(GameTestHelper h, net.minecraft.server.level.ServerPlayer chef, Travailleur[] e, Runnable... etapes) {
        int[] n = {0};
        h.succeedWhen(() -> {
            if (n[0] == 0 && Dialogue.autour(chef, null).size() < e.length)
                throw new net.minecraft.gametest.framework.GameTestAssertException("ouvriers pas encore visibles");
            while (n[0] < etapes.length) {
                etapes[n[0]].run();
                n[0]++;
            }
        });
    }

    /** Une étape qui attend {@code ticks} ticks. */
    static Runnable attendre(GameTestHelper h, int ticks) {
        long[] debut = {-1};
        return () -> {
            if (debut[0] < 0) debut[0] = h.getTick();
            if (h.getTick() - debut[0] < ticks) throw new net.minecraft.gametest.framework.GameTestAssertException("attente");
        };
    }

    /** Il a dit une réplique de cette situation (vie.txt, etrangers.txt…). */
    static boolean aDit(Travailleur t, String cle) { return t.paroles.stream().anyMatch(p -> Vie.vient(cle, p)); }

    /** Le chef regarde cet ouvrier droit dans les yeux. */
    static void regarder(net.minecraft.server.level.ServerPlayer chef, Travailleur t) {
        Vec3 d = t.getEyePosition().subtract(chef.getEyePosition()); // sans paquet envoyé : le faux joueur n'a pas de vraie connexion
        chef.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        chef.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
        chef.setYHeadRot(chef.getYRot());
    }

    /** Le mod trouve seul à qui on parle (prénom, « les gars », « sauf », le métier qui comprend la demande). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void destinatairesSansIA(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 4);
        Travailleur bertrand = e[0], raoul = e[1], odon = e[2];
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Raoul, suis-moi"); }, // écrit dans la fenêtre de Bertrand
                () -> h.assertTrue(raoul.mode == Travailleur.Mode.SUIVRE && bertrand.mode == Travailleur.Mode.TRAVAIL, "seul Raoul suit"),
                () -> bertrand.parler(chef, "Les gars, attendez ici"),
                () -> { for (Travailleur t : e) h.assertTrue(t.mode == Travailleur.Mode.ATTENDRE, t.prenom + " doit attendre"); },
                () -> bertrand.parler(chef, "Tout le monde sauf Raoul, au travail"),
                () -> h.assertTrue(bertrand.mode == Travailleur.Mode.TRAVAIL && odon.mode == Travailleur.Mode.TRAVAIL && raoul.mode == Travailleur.Mode.ATTENDRE, "sauf Raoul"),
                () -> bertrand.parler(chef, "Va protéger la base"),
                () -> h.assertTrue(raoul.missions.stream().anyMatch(m -> m.id.equals("garder")) && bertrand.missions.isEmpty(), "c'est le travail du guerrier"),
                () -> bertrand.parler(chef, "Ramène-moi dix fers"),
                () -> h.assertTrue(bertrand.missions.stream().anyMatch(m -> m.id.equals("fer") && m.nombre == 10), "le fer, c'est pour Bertrand"),
                () -> Dialogue.parler(chef, "Je fredonne une vieille chanson", null, true),
                () -> h.assertTrue(odon.mode == Travailleur.Mode.TRAVAIL, "sans fenêtre, ce qui ne leur est pas adressé est ignoré"),
                () -> { for (Travailleur t : e) t.discard(); });
    }
}

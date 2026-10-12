package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Les règles de la boîte aux lettres du poste (lot 2 « messages »), sans le jeu. */
class BoiteTest {
    static final UUID ALIENOR = UUID.randomUUID(), BERTRAND = UUID.randomUUID();
    static long ids = 1;

    static Courrier.Lettre l(UUID de, String type, boolean importante) {
        Courrier.Lettre x = new Courrier.Lettre();
        x.id = ids++;
        x.de = de == null ? "Le poste" : de == ALIENOR ? "Aliénor" : "Bertrand";
        x.deUuid = de;
        x.type = type;
        x.importante = importante;
        x.objet = type;
        x.texte = "texte " + x.id;
        return x;
    }

    @Test
    void laRoutineDuMemeOuvrierSeRegroupe() {
        List<Courrier.Lettre> b = new ArrayList<>();
        assertTrue(Boite.ranger(b, l(ALIENOR, "faim", false), 200));
        for (int i = 0; i < 4; i++) assertFalse(Boite.ranger(b, l(ALIENOR, "faim", false), 200), "fondue, pas nouvelle");
        assertTrue(Boite.ranger(b, l(BERTRAND, "faim", false), 200), "un autre ouvrier : une autre lettre");
        assertTrue(Boite.ranger(b, l(ALIENOR, "torches", false), 200), "une autre situation : une autre lettre");
        assertEquals(3, b.size());
        Courrier.Lettre faim = b.stream().filter(x -> x.deUuid == ALIENOR && x.type.equals("faim")).findFirst().orElseThrow();
        assertEquals(5, faim.nombre);
        assertEquals("faim ×5", Boite.objet(faim));
        assertTrue(faim.texte.startsWith("texte "), "le texte le plus récent");
    }

    @Test
    void lImportantEtLesReponsesNeSeRegroupentJamais() {
        List<Courrier.Lettre> b = new ArrayList<>();
        for (int i = 0; i < 3; i++) assertTrue(Boite.ranger(b, l(ALIENOR, "a_terre", true), 200));
        for (int i = 0; i < 2; i++) assertTrue(Boite.ranger(b, l(ALIENOR, "", false), 200));
        assertEquals(5, b.size());
        assertTrue(b.stream().allMatch(x -> x.nombre == 1));
    }

    @Test
    void lesAvisRegroupesGardentChaqueTexte() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre a = l(null, "ramene", false), c = l(null, "ramene", false);
        String attendu = c.texte + "\n\n" + a.texte;
        Boite.ranger(b, a, 200);
        Boite.ranger(b, c, 200);
        assertEquals(1, b.size());
        assertEquals(attendu, b.get(0).texte);
        assertEquals(2, b.get(0).nombre);
    }

    @Test
    void laPurgeEnleveDAbordLesVieillesLues() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre nonLue = l(ALIENOR, "", false), archivee = l(ALIENOR, "", false);
        Boite.ranger(b, nonLue, 3);
        Boite.ranger(b, archivee, 3);
        archivee.archivee = archivee.lue = true;
        Courrier.Lettre lue = l(ALIENOR, "", false);
        Boite.ranger(b, lue, 3);
        lue.lue = true;
        Boite.ranger(b, l(ALIENOR, "", false), 3);
        assertEquals(3, b.size());
        assertFalse(b.contains(lue), "la lue part d'abord");
        Boite.ranger(b, l(ALIENOR, "", false), 3);
        assertTrue(b.contains(nonLue) && !b.contains(archivee), "puis l'archivée, jamais la non lue tant qu'autre chose peut partir");
    }

    @Test
    void ordreDeLEcran() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre lue = l(ALIENOR, "", false), imp = l(ALIENOR, "", true), nonLue = l(BERTRAND, "", false), archivee = l(BERTRAND, "", false);
        for (Courrier.Lettre x : List.of(lue, imp, nonLue, archivee)) Boite.ranger(b, x, 200);
        lue.lue = true;
        archivee.archivee = true;
        assertEquals(List.of(imp, nonLue, lue), Boite.affichees(b, false));
        assertEquals(List.of(archivee), Boite.affichees(b, true));
        assertTrue(Boite.effacerLues(b));
        assertEquals(List.of(archivee, nonLue, imp), b);
    }
}

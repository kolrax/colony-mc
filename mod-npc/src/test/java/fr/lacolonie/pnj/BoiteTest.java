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
        Courrier.Lettre derniere = null;
        for (int i = 0; i < 4; i++) assertFalse(Boite.ranger(b, derniere = l(ALIENOR, "faim", false), 200), "fondue, pas nouvelle");
        String texteDerniere = derniere.texte;
        assertTrue(Boite.ranger(b, l(BERTRAND, "faim", false), 200), "un autre ouvrier : une autre lettre");
        assertTrue(Boite.ranger(b, l(ALIENOR, "torches", false), 200), "une autre situation : une autre lettre");
        assertEquals(3, b.size());
        Courrier.Lettre faim = b.stream().filter(x -> x.deUuid == ALIENOR && x.type.equals("faim")).findFirst().orElseThrow();
        assertEquals(5, faim.nombre);
        assertEquals("faim ×5", Boite.objet(faim));
        assertEquals(texteDerniere, faim.texte, "le texte de la 5e lettre");
    }

    @Test
    void laPurgeGardeLesImportantesNonLuesEnDernier() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre importante = l(ALIENOR, "", true);
        Boite.ranger(b, importante, 200);
        for (int i = 0; i < 205; i++) Boite.ranger(b, l(BERTRAND, "", false), 200);
        assertEquals(200, b.size());
        assertTrue(b.contains(importante), "la vieille importante non lue survit : la routine non lue part avant elle");
    }

    @Test
    void plusieursAuteursSansRepondreEtAuteurCourt() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Boite.ranger(b, l(ALIENOR, "depot_plein", false), 200);
        Boite.ranger(b, l(ALIENOR, "depot_plein", false), 200);
        assertEquals(ALIENOR, b.get(0).deUuid, "un seul auteur : on peut lui répondre");
        assertEquals("Aliénor", b.get(0).de);
        Boite.ranger(b, l(BERTRAND, "depot_plein", false), 200);
        assertNull(b.get(0).deUuid, "deux auteurs : plus de « Répondre »");
        assertEquals("Aliénor et Bertrand", b.get(0).de);
        assertTrue(b.get(0).texte.startsWith("Ont écrit : Aliénor, Bertrand."), b.get(0).texte);
    }

    @Test
    void laListeDesAuteursEstBornee() {
        List<Courrier.Lettre> b = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Courrier.Lettre x = l(UUID.randomUUID(), "depot_plein", false);
            x.de = "Ouvrier" + i;
            Boite.ranger(b, x, 200);
        }
        assertEquals(1, b.size());
        Courrier.Lettre x = b.get(0);
        assertEquals(60, x.nombre);
        assertTrue(x.auteurs.length() <= Boite.AUTEURS_MAX + 3 && x.auteurs.endsWith("…"), "bornée : " + x.auteurs.length());
        assertEquals("Ouvrier0 et bien d'autres", x.de);
        assertEquals("Ouvrier0", Boite.court("Ouvrier0"));
        assertEquals("A et 9 autres", Boite.court("A, B, C, D, E, F, G, H, I, J"));
    }

    @Test
    void toujoursEnGreveResteParOuvrier() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Boite.ranger(b, l(ALIENOR, "toujours_greve", false), 200);
        Boite.ranger(b, l(BERTRAND, "toujours_greve", false), 200);
        Boite.ranger(b, l(ALIENOR, "toujours_greve", false), 200);
        assertEquals(2, b.size(), "chacun a sa somme due : une lettre par ouvrier");
    }

    @Test
    void toutLuLaisseLesImportantes() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre imp = l(ALIENOR, "", true), routine = l(BERTRAND, "", false);
        Boite.ranger(b, imp, 200);
        Boite.ranger(b, routine, 200);
        assertTrue(Boite.toutLu(b));
        assertTrue(routine.lue);
        assertFalse(imp.lue, "une importante se lit en l'ouvrant");
    }

    @Test
    void leDepotPleinSeRegroupeTousOuvriersConfondus() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Boite.ranger(b, l(ALIENOR, "depot_plein", false), 200);
        Boite.ranger(b, l(BERTRAND, "depot_plein", false), 200);
        Boite.ranger(b, l(ALIENOR, "greve", true), 200);
        Boite.ranger(b, l(BERTRAND, "greve", true), 200);
        Boite.ranger(b, l(ALIENOR, "poste_detruit", true), 200);
        Boite.ranger(b, l(BERTRAND, "poste_detruit", true), 200);
        assertEquals(1, b.stream().filter(x -> x.type.equals("depot_plein")).count());
        Courrier.Lettre depot = b.stream().filter(x -> x.type.equals("depot_plein")).findFirst().orElseThrow();
        assertTrue(depot.nombre == 2 && depot.de.contains("Aliénor") && depot.de.contains("Bertrand"), "cite les deux : " + depot.de);
        assertEquals(2, b.stream().filter(x -> x.type.equals("greve")).count(), "la grève reste par ouvrier");
        List<Courrier.Lettre> poste = b.stream().filter(x -> x.type.equals("poste_detruit")).toList();
        assertTrue(poste.size() == 1 && poste.get(0).importante && poste.get(0).nombre == 2, "poste détruit : une seule lettre, importante");
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

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Les règles du journal du poste (1.0.0, à la place des lettres), sans le jeu. */
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
        assertTrue(Boite.ranger(b, l(ALIENOR, "faim", false), 100));
        Courrier.Lettre derniere = null;
        for (int i = 0; i < 4; i++) assertFalse(Boite.ranger(b, derniere = l(ALIENOR, "faim", false), 100), "fondue, pas nouvelle");
        String texteDerniere = derniere.texte;
        assertTrue(Boite.ranger(b, l(BERTRAND, "faim", false), 100), "un autre ouvrier : une autre ligne");
        assertTrue(Boite.ranger(b, l(ALIENOR, "torches", false), 100), "une autre situation : une autre ligne");
        assertEquals(3, b.size());
        Courrier.Lettre faim = b.stream().filter(x -> x.deUuid == ALIENOR && x.type.equals("faim")).findFirst().orElseThrow();
        assertEquals(5, faim.nombre);
        assertEquals("Aliénor : faim ×5", Boite.ligne(faim));
        assertEquals(texteDerniere, faim.texte, "le texte de la 5e");
    }

    @Test
    void uneVieilleLigneNeSeRegroupePlus() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Boite.ranger(b, l(ALIENOR, "faim", false), 100);
        for (int i = 0; i < Boite.RECENTES; i++) Boite.ranger(b, l(BERTRAND, "", false), 100);
        assertTrue(Boite.ranger(b, l(ALIENOR, "faim", false), 100), "loin dans le journal : une nouvelle ligne, en tête");
        assertEquals(ALIENOR, b.get(0).deUuid);
    }

    @Test
    void centLignesAuPlusEtLesImportantesPartentEnDernier() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Courrier.Lettre importante = l(ALIENOR, "", true);
        Boite.ranger(b, importante, 100);
        for (int i = 0; i < 150; i++) Boite.ranger(b, l(BERTRAND, "", false), 100);
        assertEquals(100, b.size());
        assertTrue(b.contains(importante), "la vieille importante reste : la routine part avant elle");
        for (int i = 0; i < 150; i++) Boite.ranger(b, l(BERTRAND, "", true), 100);
        assertEquals(100, b.size());
        assertFalse(b.contains(importante), "que des importantes : la plus vieille part");
    }

    @Test
    void plusieursAuteursEtAuteurCourt() {
        List<Courrier.Lettre> b = new ArrayList<>();
        Boite.ranger(b, l(ALIENOR, "depot_plein", false), 100);
        Boite.ranger(b, l(ALIENOR, "depot_plein", false), 100);
        assertEquals("Aliénor", b.get(0).de);
        Boite.ranger(b, l(BERTRAND, "depot_plein", false), 100);
        assertNull(b.get(0).deUuid);
        assertEquals("Aliénor et Bertrand", b.get(0).de);
        assertTrue(b.get(0).texte.startsWith("Ont dit : Aliénor, Bertrand."), b.get(0).texte);
        assertEquals(1, b.size());
    }

    @Test
    void laListeDesAuteursEstBornee() {
        List<Courrier.Lettre> b = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Courrier.Lettre x = l(UUID.randomUUID(), "depot_plein", false);
            x.de = "Ouvrier" + i;
            Boite.ranger(b, x, 100);
        }
        assertEquals(1, b.size());
        Courrier.Lettre x = b.get(0);
        assertEquals(60, x.nombre);
        assertTrue(x.auteurs.length() <= Boite.AUTEURS_MAX + 3 && x.auteurs.endsWith("…"), "bornée : " + x.auteurs.length());
        assertEquals("Ouvrier0 et bien d'autres", x.de);
        assertEquals("A et 9 autres", Boite.court("A, B, C, D, E, F, G, H, I, J"));
    }

    @Test
    void lImportantNeSeRegroupeJamaisEtLAvisDuPosteEstSansAuteur() {
        List<Courrier.Lettre> b = new ArrayList<>();
        assertTrue(Boite.ranger(b, l(ALIENOR, "a_terre", true), 100));
        assertTrue(Boite.ranger(b, l(ALIENOR, "a_terre", true), 100));
        assertEquals(2, b.size());
        Courrier.Lettre avis = l(null, "deces", true);
        avis.objet = "Avis de décès : Raoul";
        assertEquals("Avis de décès : Raoul", Boite.ligne(avis));
    }

    @Test
    void deplacerUneMission() {
        List<String> l = new ArrayList<>(List.of("fer", "charbon", "bois"));
        assertTrue(Boite.deplacer(l, 1, -1));
        assertEquals(List.of("charbon", "fer", "bois"), l);
        assertTrue(Boite.deplacer(l, 1, 1));
        assertEquals(List.of("charbon", "bois", "fer"), l);
        assertFalse(Boite.deplacer(l, 0, -1), "déjà en tête");
        assertFalse(Boite.deplacer(l, 2, 1), "déjà en bas");
        assertFalse(Boite.deplacer(l, 5, -1), "n'existe pas");
        assertEquals(List.of("charbon", "bois", "fer"), l);
    }
}

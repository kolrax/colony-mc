package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Amitiés, inimitiés et couples (lot 7, cahier 5.6), la part pure : notes, bornes, seuils, couple, moral, deuil, réponse. */
class LiensTest {
    @Test
    void notesBorneesEtSeuils() {
        Liens l = new Liens();
        l.changer("a", "Jean", 40, 1);
        assertFalse(l.ami("a"), "40 : pas encore ami");
        l.changer("a", "Jean", 15, 1);
        assertTrue(l.ami("a"), "55 : ami");
        l.changer("a", "Jean", 500, 1);
        assertEquals(Liens.MAX, l.note("a"), "bornée à 100");
        l.changer("b", "Paul", Liens.VOL, 1);
        l.changer("b", "Paul", Liens.LIT, 1);
        assertTrue(l.ennemi("b"), "un vol vu et un lit pris : ennemi (" + l.note("b") + ")");
        l.changer("b", "Paul", -500, 1);
        assertEquals(Liens.MIN, l.note("b"), "bornée à −100");
        assertEquals(0, l.note("inconnu"));
        assertEquals(List.of("Jean"), l.amis());
        assertEquals(List.of("Paul"), l.ennemis());
    }

    @Test
    void huitColleguesAuPlusLeCoupleReste() {
        Liens l = new Liens();
        l.changer("c", "Aude", 85, 0);
        l.unir("c", "Aude", 0);
        for (int i = 0; i < 12; i++) l.changer("x" + i, "N" + i, 10 + i, 0);
        assertEquals(Liens.RETENUS, l.liens.size(), "au plus 8 retenus");
        assertEquals("c", l.conjoint(), "le couple n'est jamais oublié");
        assertNull(l.de("x0"), "le lien le plus faible est parti");
        assertNotNull(l.de("x11"), "le plus fort reste");
    }

    @Test
    void coupleApresDeuxJoursAQuatreVingts() {
        Liens a = new Liens(), b = new Liens();
        a.changer("B", "Bertille", 85, 10);
        b.changer("A", "Anselme", 85, 10);
        assertFalse(Liens.couplePossible(a, "B", b, "A", 11), "un seul jour : trop tôt");
        assertTrue(Liens.couplePossible(a, "B", b, "A", 12), "deux jours à 80 et plus");
        Liens c = new Liens();
        c.changer("A", "Anselme", 70, 10);
        assertFalse(Liens.couplePossible(a, "C", c, "A", 20), "il faut 80 des deux côtés");
        // Retombé sous 80 : le compte des jours repart.
        a.changer("B", "Bertille", -10, 11);
        a.changer("B", "Bertille", 10, 13);
        assertFalse(Liens.couplePossible(a, "B", b, "A", 14), "retombé sous 80 le jour 11, remonté le 13 : trop tôt le 14");
        assertTrue(Liens.couplePossible(a, "B", b, "A", 15));
        a.unir("B", "Bertille", 15);
        b.unir("A", "Anselme", 15);
        assertFalse(Liens.couplePossible(a, "B", b, "A", 30), "une seule fois");
        Liens d = new Liens();
        d.changer("A", "Anselme", 90, 0);
        Liens a2 = Liens.lire(a.enTexte());
        a2.changer("D", "Doucette", 90, 0);
        assertFalse(Liens.couplePossible(a2, "D", d, "A", 30), "déjà en couple avec un autre");
    }

    @Test
    void heurtsSelonLesCaracteres() {
        assertTrue(Liens.heurt("colerique", "colerique", 0.25) < 0, "deux colériques se chamaillent");
        assertEquals(0, Liens.heurt("colerique", "colerique", 0.5));
        assertTrue(Liens.heurt("calme", "fourbe", 0.1) < 0, "un fourbe agace");
        assertEquals(0, Liens.heurt("calme", "fourbe", 0.5));
        assertEquals(0, Liens.heurt("calme", "peureux", 0.0), "deux paisibles : jamais");
        assertEquals(Liens.VOISIN, Liens.voisinage("calme", "calme", 0.0, 1.0), "un bilan côte à côte rapproche");
        assertEquals(2, Liens.gain(Liens.VOISIN, 2.0), "réglage de vitesse");
        assertEquals(Liens.VOL, Liens.gain(Liens.VOL, 2.0), "les pertes ne changent pas");
        assertEquals(1, Liens.gain(Liens.REPAS, 0.1), "au moins 1");
    }

    @Test
    void moralDeuilEtMemoire() {
        Liens l = new Liens();
        assertEquals(0, l.moral());
        l.changer("a", "Jean", 60, 0);
        assertEquals(1, l.moral(), "un ami");
        l.changer("b", "Paul", -60, 0);
        assertEquals(0, l.moral(), "un ami et un ennemi");
        l.changer("c", "Aude", 90, 0);
        l.unir("c", "Aude", 0);
        assertEquals(1, l.moral(), "en couple, un ennemi");
        assertEquals(3, l.poidsDeuil("c"), "l'être aimé : deuil triple");
        assertEquals(2, l.poidsDeuil("a"), "un ami : double");
        assertEquals(1, l.poidsDeuil("b"));
        assertEquals(1, l.poidsDeuil("inconnu"));
        Liens r = Liens.lire(l.enTexte());
        assertEquals(l.liens, r.liens, "relu de la mémoire");
        assertTrue(Liens.lire("abîmé|x;;a|b|c|d|e").liens.isEmpty(), "une mémoire abîmée ne plante pas");
    }

    @Test
    void reponseAuChef() {
        Liens l = new Liens();
        assertNull(l.reponse(false), "aucun lien : la réplique liens_reponse_aucun");
        l.changer("a", "Jean", 60, 0);
        l.changer("m", "Marie", 70, 0);
        l.changer("b", "Paul", -60, 0);
        l.changer("n", "Neutre", 20, 0);
        assertEquals("Je m'entends bien avec Marie et Jean. Avec Paul, ça ne passe pas.", l.reponse(false));
        assertEquals("Je ne partage ma vie avec personne. Je m'entends bien avec Marie et Jean. Avec Paul, ça ne passe pas.", l.reponse(true));
        l.changer("c", "Aude", 90, 0);
        l.unir("c", "Aude", 0);
        assertTrue(l.reponse(true).startsWith("Je partage ma vie avec Aude."), l.reponse(true));
        assertFalse(l.reponse(false).contains("bien avec Aude"), "l'être aimé n'est pas compté parmi les amis");
        assertEquals("Jean, Marie et Paul", Liens.et(List.of("Jean", "Marie", "Paul")));
    }
}

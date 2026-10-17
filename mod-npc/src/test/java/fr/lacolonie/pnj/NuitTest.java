package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 13, travailleurs de nuit (cahier 5.10) : les ordres, la fatigue, la santé et le moral des nuits. */
class NuitTest {
    static Ordres.Lecture lire(String texte) { return Ordres.lire(texte, "mineur"); }

    @Test
    void ordresDeNuitEtDeJour() {
        for (String s : List.of("Travaille de nuit", "Tu feras les nuits", "Mathis, tu passes en équipe de nuit.", "Désormais tu travailles de nuit",
                "Je veux que tu travailles la nuit", "Tu es de service de nuit"))
            assertEquals(1, lire(s).equipe, s);
        for (String s : List.of("Travaille de jour", "Reprends le jour", "Plus de nuits pour toi", "Arrête de travailler de nuit", "Tu ne travailles plus de nuit"))
            assertEquals(0, lire(s).equipe, s);
        Ordres.Lecture l = lire("Travaille de nuit");
        assertFalse(l.vide(), "un ordre compris");
        assertTrue(l.debut < 0 && l.fin < 0, "les heures viennent des réglages");
        assertFalse(Obeissance.sansOrdre(l), "c'est un ordre");
        // Des heures dites restent les siennes.
        l = lire("Travaille de nuit, de 22h à 4h");
        assertEquals(1, l.equipe);
        assertEquals(22, l.debut);
        assertEquals(4, l.fin);
        l = lire("Travaille de 22h à 4h");
        assertEquals(22, l.debut);
        assertEquals(4, l.fin);
    }

    @Test
    void pasDeFauxPositifs() {
        for (String s : List.of("Bonne nuit", "Bonne nuit Mathis", "La nuit tombe", "Il fait nuit", "Cette nuit, il a plu", "Travaille jour et nuit",
                "La nuit, rentre au poste", "Quand la nuit tombe, rentre", "Tu as bien dormi cette nuit ?", "La nuit dernière était froide",
                "Le travail de nuit est dur", "Au travail", "Bonjour", "Tu travailles de nuit ?", "Qui travaille de nuit ?"))
            assertEquals(-1, lire(s).equipe, s);
        Ordres.Lecture l = lire("Travaille jour et nuit");
        assertEquals(0, l.debut, "toute la journée, pas de nuit");
        assertEquals(0, l.fin);
        l = lire("La nuit, rentre au poste");
        assertTrue(l.debut < 0 && l.fin < 0, "une règle n'est pas un horaire");
    }

    /** Relecture 1 : toute négation des nuits, c'est le jour, jamais la nuit. */
    @Test
    void negationsDonnentLeJour() {
        for (String s : List.of("Arrêtez de travailler de nuit", "Arrêtes de travailler de nuit", "Ne travaillez plus la nuit", "Ne travaille plus la nuit",
                "Pas de nuit pour toi", "Tu ne travailleras plus de nuit", "Je ne veux plus que tu travailles de nuit", "Stop les nuits", "Cesse de travailler la nuit",
                "Fini le travail de nuit"))
            assertEquals(0, lire(s).equipe, s);
        assertEquals(1, lire("Ne t'en fais pas, travaille de nuit").equipe, "la négation d'une autre proposition ne compte pas");
        assertEquals(1, lire("Travaille de nuit, ne t'arrête pas").equipe);
    }

    /** Relecture 2 : « poste / équipe de nuit » seulement après un verbe d'affectation. */
    @Test
    void posteDeNuitSeulementAffecte() {
        for (String s : List.of("Rentre au poste de nuit", "Reste au poste de nuit", "L'équipe de nuit est fatiguée", "Le service de nuit commence"))
            assertEquals(-1, lire(s).equipe, s);
        for (String s : List.of("Mets-toi au poste de nuit", "Tu seras de l'équipe de nuit", "Tu passes en équipe de nuit", "Rejoins l'équipe de nuit"))
            assertEquals(1, lire(s).equipe, s);
        assertEquals(0, lire("Tu seras de l'équipe de jour").equipe);
    }

    /** Relecture 3 et 6 : heures à moitié dites, « travaille de jour » sur un ouvrier qui n'est pas de nuit. */
    @Test
    void heuresCompletees() {
        Ordres.Lecture l = lire("Travaille de nuit et commence à 22h");
        assertEquals(1, l.equipe);
        assertArrayEquals(new int[]{22, 6}, Nuit.horaires(l.equipe, l.debut, l.fin, 6, 18, 20, 6), "le début dit, la fin des réglages");
        l = lire("Travaille de nuit et finis à 4h");
        assertArrayEquals(new int[]{20, 4}, Nuit.horaires(l.equipe, l.debut, l.fin, 6, 18, 20, 6));
        assertArrayEquals(new int[]{20, 6}, Nuit.horaires(1, -1, -1, 6, 18, 20, 6));
        assertArrayEquals(new int[]{6, 18}, Nuit.horaires(0, -1, -1, 20, 6, 20, 6), "de nuit, remis au jour");
        assertArrayEquals(new int[]{7, 19}, Nuit.horaires(0, -1, -1, 7, 19, 20, 6), "déjà de jour : ses heures réglées restent");
        assertArrayEquals(new int[]{8, 16}, Nuit.horaires(-1, 8, 16, 6, 18, 20, 6), "des heures dites seules");
    }

    /** Relecture 4 : une question polie est un ordre ; une vraie question non. */
    @Test
    void questionsPolies() {
        assertEquals(1, lire("Peux-tu travailler de nuit ?").equipe);
        assertEquals(1, lire("Travaille de nuit, ça te va ?").equipe);
        assertEquals(1, lire("Tu veux bien faire les nuits ?").equipe);
        assertEquals(-1, lire("Tu travailles de nuit ?").equipe);
    }

    /** Relecture 5 : seules les règles de nuit ou de jour tombent quand une équipe est dite. */
    @Test
    void autresReglesGardees() {
        Ordres.Lecture l = lire("Travaille de nuit. Le soir, rentre au poste.");
        assertEquals(1, l.equipe);
        assertTrue(l.regles.stream().anyMatch(r -> r.quand.equals("SOIR")), "la règle du soir reste : " + l.regles);
    }

    /** Relecture 6 : jour et nuit, c'est toute la journée. */
    @Test
    void jourCommeNuit() {
        for (String s : List.of("Travaille de jour comme de nuit", "Travaille le jour et la nuit", "Travaille de jour et de nuit")) {
            Ordres.Lecture l = lire(s);
            assertEquals(-1, l.equipe, s);
            assertTrue(l.debut == 0 && l.fin == 0, s + " : toute la journée (" + l.debut + "-" + l.fin + ")");
        }
    }

    @Test
    void heuresDeNuit() {
        assertTrue(Nuit.deNuit(20, 6));
        assertTrue(Nuit.deNuit(22, 4));
        assertTrue(Nuit.deNuit(18, 6), "de 18 h à 6 h : surtout la nuit");
        assertFalse(Nuit.deNuit(6, 18));
        assertFalse(Nuit.deNuit(0, 0), "toute la journée");
        assertFalse(Nuit.deNuit(8, 20));
        assertTrue(Nuit.heureDeNuit(23) && Nuit.heureDeNuit(2) && !Nuit.heureDeNuit(12) && !Nuit.heureDeNuit(6));
    }

    @Test
    void laNuitFatigueEtLeJourReposeMoins() {
        assertEquals(5, Nuit.fatigue(5, false, 1.3));
        assertEquals(7, Nuit.fatigue(5, true, 1.3), "5 × 1,3, arrondi");
        assertEquals(9, Nuit.fatigue(7, true, 1.3), "en expédition");
        assertEquals(5, Nuit.fatigue(5, true, 0.5), "jamais moins que de jour");
        assertEquals(12, Nuit.repos(12, false, 0.67));
        assertEquals(8, Nuit.repos(12, true, 0.67), "le sommeil de jour : les deux tiers");
        assertEquals(1, Nuit.repos(1, true, 0.1), "au moins un point");
    }

    @Test
    void nuitsDAffilee() {
        Nuit.Compte c = Nuit.Compte.ZERO;
        for (int k = 0; k < 3; k++) c = Nuit.compter(c, 8, 2);
        assertEquals(3, c.nuits());
        assertEquals(new Nuit.Compte(3, 1), Nuit.compter(c, 3, 2), "une courte nuit ne compte pas, mais n'efface pas encore");
        c = Nuit.compter(c, 0, 2);
        assertEquals(new Nuit.Compte(3, 1), c, "un jour libre : le compte reste");
        assertEquals(new Nuit.Compte(4, 0), Nuit.compter(c, 8, 2), "une nuit de plus : le repos repart de zéro");
        assertEquals(Nuit.Compte.ZERO, Nuit.compter(c, 0, 2), "deux jours libres : tout est effacé");
    }

    @Test
    void santeApresPlusieursNuits() {
        assertEquals(0, Nuit.vieEnMoins(2, 3));
        assertEquals(2, Nuit.vieEnMoins(3, 3));
        assertEquals(2, Nuit.vieEnMoins(5, 3));
        assertEquals(4, Nuit.vieEnMoins(6, 3));
        assertEquals(4, Nuit.vieEnMoins(20, 3), "jamais plus de 4");
        assertEquals(0, Nuit.vieEnMoins(20, 0), "réglé à 0 : jamais");
        assertEquals(200, Nuit.soin(0, 3));
        assertTrue(Nuit.soin(3, 3) > 200, "il guérit plus lentement");
    }

}

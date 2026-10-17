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

    @Test
    void lesNuitsPesentSurLeMoral() {
        assertEquals(0, Nuit.moral(0, 3, false));
        assertEquals(-1, Nuit.moral(1, 3, false));
        assertEquals(-2, Nuit.moral(3, 3, false));
        assertEquals(-3, Nuit.moral(6, 3, false));
        assertEquals(-2, Nuit.moral(6, 3, true), "une prime compense en partie");
        assertEquals(0, Nuit.moral(1, 3, true));
        Moral.Etat e = MoralTest.bien();
        Moral.Etat nuits = new Moral.Etat(e.impayes(), e.payeRecemment(), e.repas(), e.lit(), e.toit(), true, e.blesseVu(), e.joursDeuil(), e.pluie(),
                e.orage(), e.bilansPluie(), e.beauTemps(), e.coupChef(), e.ouvriers(), e.depot(), 0, Nuit.moral(6, 3, false));
        Moral.Bilan b = Moral.bilan(nuits);
        assertEquals(-3, b.de(Moral.Cause.NUIT));
        assertEquals(Moral.Cause.NUIT, b.pire(), "les nuits pèsent le plus (" + b + ")");
        assertEquals("les nuits me tuent", Moral.Cause.NUIT.dit);
        assertTrue(Vie.TOUTES.containsKey(Moral.Cause.NUIT.plainte), "sa plainte existe");
        assertTrue(Vie.TOUTES.containsKey("nuit_sans_ouvrage"));
    }
}

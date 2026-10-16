package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lot 11 : le salaire croît avec la taille de la colonie ({@link Cout}). */
class CoutTest {

    @Test
    void facteurSelonLeRang() {
        assertEquals(1, Cout.facteur(1, 3, 0.15), 1e-9);
        assertEquals(1, Cout.facteur(3, 3, 0.15), 1e-9);
        assertEquals(1.15, Cout.facteur(4, 3, 0.15), 1e-9);
        assertEquals(1.30, Cout.facteur(5, 3, 0.15), 1e-9);
        assertEquals(5.05, Cout.facteur(30, 3, 0.15), 1e-9);
    }

    @Test
    void reglagesBizarresBornes() {
        assertEquals(1, Cout.facteur(30, 3, 0), 1e-9);
        assertEquals(1, Cout.facteur(30, 3, -1), 1e-9);
        assertEquals(1, Cout.facteur(30, 3, Double.NaN), 1e-9);
        assertEquals(1.15, Cout.facteur(1, -5, 0.15), 1e-9); // aucun ouvrier sans surcoût : dès le premier
        assertEquals(1, Cout.facteur(0, 3, 0.15), 1e-9);
        assertEquals(1, Cout.facteur(-4, 3, 0.15), 1e-9);
        assertEquals(Cout.FACTEUR_MAX, Cout.facteur(1000, 0, 1e9), 1e-9);
        assertEquals(Cout.FACTEUR_MAX, Cout.facteur(Integer.MAX_VALUE, 0, Double.POSITIVE_INFINITY), 1e-9);
    }

    @Test
    void salaireArrondiJamaisMoinsQueLeNiveau() {
        assertEquals(100, Cout.salaire(100, 1));
        assertEquals(115, Cout.salaire(100, 1.15));
        assertEquals(505, Cout.salaire(100, 5.05));
        assertEquals(9, Cout.salaire(8, 1.15)); // 9,2
        assertEquals(2, Cout.salaire(2, 1.15)); // 2,3 : arrondi
        assertEquals(8, Cout.salaire(8, 0.5));
        assertEquals(8, Cout.salaire(8, Double.NaN));
        assertEquals(800, Cout.salaire(8, 1e9)); // borné à FACTEUR_MAX
    }
}

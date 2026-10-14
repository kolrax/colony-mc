package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CadenceTest {
    @Test
    void pasDeuxFoisLeMemeSonAvantSonDelai() {
        Cadence c = new Cadence();
        assertTrue(c.peut(Cadence.Genre.PAIE, 1000));
        assertFalse(c.peut(Cadence.Genre.PAIE, 1000 + Cadence.Genre.PAIE.delai - 1), "trop tôt");
        assertTrue(c.peut(Cadence.Genre.PAIE, 1000 + Cadence.Genre.PAIE.delai), "le délai passé");
        assertEquals(2, c.joues(Cadence.Genre.PAIE));
    }

    @Test
    void chaqueGenreASonDelai() {
        Cadence c = new Cadence();
        assertTrue(c.peut(Cadence.Genre.COUP, 0));
        assertTrue(c.peut(Cadence.Genre.FRAPPE, 0), "un autre genre n'attend pas");
        int n = 0;
        for (long t = 1; t <= 100; t++) if (c.peut(Cadence.Genre.COUP, t)) n++;
        assertEquals(100 / Cadence.Genre.COUP.delai, n, "un coup tous les " + Cadence.Genre.COUP.delai + " ticks au plus");
        assertEquals(0, c.joues(Cadence.Genre.ROT));
    }

    @Test
    void hauteurUnPeuVariee() {
        assertEquals(1f, Cadence.hauteur(1f, 0.5f, 0.5f), 1e-6);
        for (float a = 0; a < 1; a += 0.1f)
            for (float b = 0; b < 1; b += 0.1f) {
                float h = Cadence.hauteur(0.8f, a, b);
                assertTrue(h >= 0.72f && h <= 0.88f, "à 10 % près : " + h);
            }
    }
}

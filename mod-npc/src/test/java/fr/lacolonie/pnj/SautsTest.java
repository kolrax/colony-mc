package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SautsTest {
    @Test
    void renonceApresQuelquesSautsSurPlace() {
        Sauts s = new Sauts();
        int n = 0;
        for (long t = 0; t < 200; t += 12) if (s.permis(42L, t)) n++;
        assertEquals(Sauts.MAX, n, "au plus " + Sauts.MAX + " sauts depuis la même case, puis il renonce");
        assertTrue(s.permis(42L, 12L * 17 + Sauts.PAUSE), "la pause finie, il peut réessayer");
    }

    @Test
    void unSautQuiLeFaitMonterNeComptePas() {
        Sauts s = new Sauts();
        for (long t = 0; t < 40; t++) assertTrue(s.permis(100L + t, t * 12), "chaque saut part d'une autre case (un pilier, un escalier)");
    }

    @Test
    void desSautsEspacesNeSontPasUneBoucle() {
        Sauts s = new Sauts();
        for (int i = 0; i < 20; i++) assertTrue(s.permis(7L, i * (Sauts.OUBLI + 1L)), "un saut toutes les 5 s, ce n'est pas sauter en boucle");
    }
}

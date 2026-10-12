package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoinceTest {
    /** Relevés toutes les secondes (20 ticks) à la même place, en marchant : vrai au bout de combien de ticks ? */
    static int jusquAuRetour(Coince c, double x, long debut) {
        for (int t = 20; t <= 20 * 60 * 30; t += 20) {
            c.marche = true;
            if (c.relever(x, 64, 0, 20, debut + t, false)) return t;
        }
        return -1;
    }

    @Test
    void rameneAuBoutDeCinqMinutesSurPlace() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, false); // le premier relevé fixe le départ
        assertEquals(Coince.DELAI, jusquAuRetour(c, 0.5, 0));
    }

    @Test
    void travaillerSurPlaceNeComptePas() {
        Coince c = new Coince();
        for (int t = 0; t <= 20 * 60 * 30; t += 20) assertFalse(c.relever(0, 64, 0, 20, t, true), "sans marche ni échec, jamais coincé");
        assertEquals(0, c.ticks);
    }

    @Test
    void unEchecNeCompteQuEnAttente() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, true);
        c.echec = true;
        for (int t = 20; t <= 20 * 60 * 30; t += 20) assertFalse(c.relever(0, 64, 0, 20, t, false), "occupé à autre chose : l'échec ne compte pas");
        int t = 20 * 60 * 30 + 20, debut = t;
        while (!c.relever(0, 64, 0, 20, t, true)) t += 20;
        assertEquals(Coince.DELAI, t - debut + 20);
    }

    @Test
    void avancerDeHuitBlocsRemetAZero() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, false);
        for (int t = 20; t < Coince.DELAI - 20; t += 20) { c.marche = true; c.relever(0, 64, 0, 20, t, false); }
        c.echec = true;
        c.marche = true;
        assertFalse(c.relever(9, 64, 0, 20, Coince.DELAI, true)); // 9 blocs plus loin : un vrai progrès
        assertEquals(0, c.ticks);
        assertFalse(c.echec);
    }

    @Test
    void quiMarcheEnAvancantNEstJamaisRamene() {
        Coince c = new Coince();
        for (int t = 0; t <= 20 * 60 * 30; t += 20) { c.marche = true; assertFalse(c.relever(t / 20 * 0.5, 64, 0, 20, t, false)); }
    }

    @Test
    void pasDeuxRetoursEnDixMinutes() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, false);
        int t = jusquAuRetour(c, 0, 0);
        c.ramene(t);
        c.relever(100, 64, 0, 20, t + 20, false); // au poste : nouveau départ
        int deuxieme = jusquAuRetour(c, 100, t + 20);
        assertEquals(Coince.ENTRE_DEUX - 20, deuxieme, "le second retour attend 10 minutes après le premier");
    }

    @Test
    void plusDeRetourApresDeuxSansTravail() {
        Coince c = new Coince();
        long t = 0;
        for (int i = 0; i < Coince.RETOURS_MAX; i++) {
            c.relever(i * 100, 64, 0, 20, t, false);
            t += jusquAuRetour(c, i * 100, t);
            c.ramene(t);
            t += Coince.ENTRE_DEUX;
        }
        c.relever(500, 64, 0, 20, t, false);
        assertEquals(-1, jusquAuRetour(c, 500, t), "après deux retours sans travail, plus de téléportation");
        c.travail();
        assertTrue(jusquAuRetour(c, 500, t + 20 * 60 * 30) > 0, "après un vrai travail, de nouveau possible");
    }

    @Test
    void sansPlaceIlReessaieUneMinutePlusTard() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, false);
        int t = jusquAuRetour(c, 0, 0);
        c.reporter(t);
        c.marche = true;
        assertFalse(c.relever(0, 64, 0, 20, t + 20, false));
        assertEquals(Coince.REESSAI, jusquAuRetour(c, 0, t));
    }
}

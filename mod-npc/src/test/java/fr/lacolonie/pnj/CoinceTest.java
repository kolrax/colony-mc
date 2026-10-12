package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoinceTest {
    /** Relevés toutes les secondes (20 ticks) à la même place, en marchant : vrai au bout de combien de ticks ? */
    static int jusquAuRetour(Coince c, double x, long debut) {
        for (int t = 20; t <= 20 * 60 * 30; t += 20) {
            c.marche = true;
            if (c.relever(x, 64, 0, 20, debut + t)) return t;
        }
        return -1;
    }

    @Test
    void rameneAuBoutDeCinqMinutesSurPlace() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0); // le premier relevé fixe le départ
        assertEquals(Coince.DELAI, jusquAuRetour(c, 0.5, 0));
    }

    @Test
    void travaillerSurPlaceNeComptePas() {
        Coince c = new Coince();
        for (int t = 0; t <= 20 * 60 * 30; t += 20) assertFalse(c.relever(0, 64, 0, 20, t), "sans marche ni échec, jamais coincé");
        assertEquals(0, c.ticks);
    }

    @Test
    void unEchecFaitCompterMemeSansMarcher() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0);
        c.echec = true;
        int t = 20;
        while (!c.relever(0, 64, 0, 20, t)) t += 20;
        assertEquals(Coince.DELAI, t);
    }

    @Test
    void avancerDeHuitBlocsRemetAZero() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0);
        for (int t = 20; t < Coince.DELAI - 20; t += 20) { c.marche = true; c.relever(0, 64, 0, 20, t); }
        c.echec = true;
        c.marche = true;
        assertFalse(c.relever(9, 64, 0, 20, Coince.DELAI)); // 9 blocs plus loin : un vrai progrès
        assertEquals(0, c.ticks);
        assertFalse(c.echec);
    }

    @Test
    void quiMarcheEnAvancantNEstJamaisRamene() {
        Coince c = new Coince();
        for (int t = 0; t <= 20 * 60 * 30; t += 20) { c.marche = true; assertFalse(c.relever(t / 20 * 0.5, 64, 0, 20, t)); }
    }

    @Test
    void pasDeuxRetoursEnDixMinutes() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0);
        int t = jusquAuRetour(c, 0, 0);
        c.ramene(t);
        c.relever(100, 64, 0, 20, t + 20); // au poste : nouveau départ
        int deuxieme = jusquAuRetour(c, 100, t + 20);
        assertEquals(Coince.ENTRE_DEUX - 20, deuxieme, "le second retour attend 10 minutes après le premier");
    }
}

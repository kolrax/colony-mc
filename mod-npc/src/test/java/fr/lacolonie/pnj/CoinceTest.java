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

    /** Près du poste, seul un échec récent à atteindre le poste lui-même compte (plan A3) ; ni un vieil échec, ni vers autre chose. */
    @Test
    void posteHorsDAtteinteSeulementSiRecentEtVersLePoste() {
        Coince c = new Coince();
        c.echec(1000, true);
        assertTrue(c.posteHorsDAtteinte(1000 + Coince.FENETRE_ECHEC));
        assertFalse(c.posteHorsDAtteinte(1001 + Coince.FENETRE_ECHEC), "un vieil échec ne compte plus");
        c.echec(2000, false);
        assertFalse(c.posteHorsDAtteinte(2000), "un échec vers autre chose (un bloc, un arbre) ne compte pas près du poste");
        c.echec(3000, true);
        c.progres(0, 64, 0);
        assertFalse(c.posteHorsDAtteinte(3000), "arrivé quelque part : oublié");
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
        c.echec(0);
        for (int t = 20; t <= Coince.FENETRE_ECHEC; t += 20) assertFalse(c.relever(0, 64, 0, 20, t, false), "occupé à autre chose : l'échec ne compte pas");
        assertEquals(0, c.ticks);
    }

    @Test
    void unEchecNeCompteQuUneMinute() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, true);
        c.echec(0);
        for (int t = 20; t <= 20 * 60 * 30; t += 20) assertFalse(c.relever(0, 64, 0, 20, t, true), "une attente ordinaire après l'échec ne compte pas au-delà d'une minute");
        assertEquals(Coince.FENETRE_ECHEC, c.ticks);
    }

    /** Après un échec à rejoindre son poste, toute l'attente compte (plan A3, graine 42 : deux jours au fond d'un creux). */
    @Test
    void apresUnEchecVersLePosteToutLAttenteCompte() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, true);
        c.echec(0, true);
        int t = 20;
        while (t <= 20 * 60 * 30 && !c.relever(0, 64, 0, 20, t, true)) t += 20;
        assertEquals(Coince.DELAI, t, "ramené au bout du délai, pas seulement une minute comptée");
    }

    @Test
    void remisAuTravailApresLAttenteUnRetourDePlus() {
        Coince c = new Coince();
        c.retours = Coince.RETOURS_MAX;
        c.attenteAuto = true;
        c.mode(true);
        assertEquals(Coince.RETOURS_MAX, c.retours, "tant qu'il attend, rien ne change");
        c.mode(false); // son chef le remet au travail
        assertFalse(c.attenteAuto);
        assertEquals(Coince.RETOURS_MAX - 1, c.retours, "un seul retour de plus est permis");
    }

    @Test
    void lesRetoursSOublientAuBoutDUneHeure() {
        Coince c = new Coince();
        c.ramene(0);
        c.ramene(Coince.ENTRE_DEUX);
        c.relever(0, 64, 0, 20, Coince.ENTRE_DEUX + Coince.OUBLI_RETOURS + 20, false);
        c.relever(0, 64, 0, 20, Coince.ENTRE_DEUX + Coince.OUBLI_RETOURS + 40, false);
        assertEquals(0, c.retours);
    }

    @Test
    void avancerDeHuitBlocsRemetAZero() {
        Coince c = new Coince();
        c.relever(0, 64, 0, 20, 0, false);
        for (int t = 20; t < Coince.DELAI - 20; t += 20) { c.marche = true; c.relever(0, 64, 0, 20, t, false); }
        c.echec(Coince.DELAI);
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

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PecheTest {
    /** Un relevé 7 x 7 : '~' de l'eau, autre chose : sec. Lignes = dx de -3 à 3, colonnes = dz de -3 à 3. */
    static boolean[][] releve(String... lignes) {
        boolean[][] r = new boolean[7][7];
        for (int i = 0; i < 7; i++) for (int j = 0; j < 7; j++) r[i][j] = lignes[i].charAt(j) == '~';
        return r;
    }

    @Test
    void laGrilleVaDuPlusProcheAuPlusLoinEtToucheToutCarre() {
        List<int[]> g = Peche.grille(32, 3);
        assertArrayEquals(new int[]{0, 0}, g.get(0));
        for (int i = 1; i < g.size(); i++) {
            int a = g.get(i - 1)[0] * g.get(i - 1)[0] + g.get(i - 1)[1] * g.get(i - 1)[1], b = g.get(i)[0] * g.get(i)[0] + g.get(i)[1] * g.get(i)[1];
            assertTrue(a <= b, "trié par distance");
            assertTrue(b <= 32 * 32, "dans le rayon");
        }
        assertTrue(g.size() < 400, "pas de grand balayage : " + g.size() + " colonnes");
        // Tout carré de 3 x 3 dans le rayon (moins un bord) contient une colonne de la grille.
        for (int x = -28; x <= 26; x++)
            for (int z = -28; z <= 26; z++) {
                if ((x + 1) * (x + 1) + (z + 1) * (z + 1) > 28 * 28) continue;
                int fx = x, fz = z;
                assertTrue(g.stream().anyMatch(k -> k[0] >= fx && k[0] < fx + 3 && k[1] >= fz && k[1] < fz + 3), "carré en " + x + " " + z);
            }
    }

    @Test
    void attenteEntreQuinzeEtQuaranteSecondesPlusCourteSousLaPluie() {
        assertEquals(300, Peche.attente(0, false, 0));
        assertEquals(800, Peche.attente(1, false, 0));
        for (double r = 0; r <= 1; r += 0.05) {
            int a = Peche.attente(r, false, 0), p = Peche.attente(r, true, 0);
            assertTrue(a >= 300 && a <= 800);
            assertTrue(p < a, "la pluie fait mordre plus vite");
        }
        assertEquals(100, Peche.attente(0, true, 1000), "jamais moins de 5 s");
        assertEquals(200, Peche.attente(0, false, 100), "l'Appât raccourcit l'attente");
    }

    @Test
    void unCarreDEauAvecUneRive() {
        // Une mare de 3 x 3 juste au sud-est de la colonne trouvée (dx 0..2, dz 0..2) : un seul centre, (1, 1), bordé de rive.
        boolean[][] mare = releve(
                "#######",
                "#######",
                "#######",
                "###~~~#",
                "###~~~#",
                "###~~~#",
                "#######");
        List<int[]> c = Peche.centres(mare);
        assertEquals(1, c.size());
        assertArrayEquals(new int[]{1, 1}, c.get(0));
        assertEquals(16, Peche.rives(mare, 1, 1).size(), "tout l'anneau est sec");
    }

    @Test
    void troPetiteOuSansRiveRien() {
        // Une flaque de 2 x 3 : pas de carré de 3 x 3.
        assertTrue(Peche.centres(releve(
                "#######",
                "#######",
                "##~~~##",
                "##~~~##",
                "#######",
                "#######",
                "#######")).isEmpty());
        // La pleine mer : de l'eau partout, pas de rive à 2 cases d'un centre.
        assertTrue(Peche.centres(releve("~~~~~~~", "~~~~~~~", "~~~~~~~", "~~~~~~~", "~~~~~~~", "~~~~~~~", "~~~~~~~")).isEmpty());
    }

    @Test
    void auBordDUnLacLeCentreLePlusPresDeLaRive() {
        // Le bord d'un lac à l'ouest (dx = -3 sec) : le centre retenu d'abord est celui qui touche la rive (dx = -1).
        boolean[][] lac = releve(
                "#######",
                "~~~~~~~",
                "~~~~~~~",
                "~~~~~~~",
                "~~~~~~~",
                "~~~~~~~",
                "~~~~~~~");
        List<int[]> c = Peche.centres(lac);
        assertFalse(c.isEmpty());
        assertEquals(-1, c.get(0)[0]);
        for (int[] r : Peche.rives(lac, c.get(0)[0], c.get(0)[1])) assertEquals(-3, r[0], "la rive est la terre du bord");
    }

    @Test
    void poissonDechetOuTresor() {
        assertEquals("peche_prise", Peche.genre("minecraft:cod", false));
        assertEquals("peche_prise", Peche.genre("minecraft:pufferfish", false));
        assertEquals("peche_dechet", Peche.genre("minecraft:leather_boots", false));
        assertEquals("peche_tresor", Peche.genre("minecraft:bow", true), "un arc enchanté est un trésor");
        assertEquals("peche_dechet", Peche.genre("minecraft:bow", false), "un vieil arc abîmé, non");
        assertEquals("peche_tresor", Peche.genre("minecraft:name_tag", false));
        assertEquals("une vieille botte", Peche.NOMS.get("minecraft:leather_boots"));
        for (String id : Peche.POISSONS) assertTrue(Peche.NOMS.containsKey(id));
        for (String id : Peche.TRESORS) assertTrue(Peche.NOMS.containsKey(id));
    }
}

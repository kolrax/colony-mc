package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MonnaieTest {
    // bronze 1, andésite 8, rouage 16, laiton 64, or 512, netherite 4096 (désordre volontaire)
    static final int[] V = {64, 1, 8, 16, 512, 4096};

    @Test
    void payeAvecLesPetitesPiecesEtRendLaMonnaie() {
        int[] q = {1, 20, 0, 0, 0, 0}; // 1 laiton + 20 bronzes = 84
        int[] apres = Monnaie.payer(V, q, 30);
        assertNotNull(apres);
        assertEquals(54, Monnaie.total(V, apres));
        assertArrayEquals(new int[]{0, 6, 0, 3, 0, 0}, apres); // 3 rouages + 6 bronzes
    }

    @Test
    void refuseSiPasAssez() {
        assertNull(Monnaie.payer(V, new int[]{0, 7, 0, 0, 0, 0}, 8));
    }

    @Test
    void neToucheQueCeQuIlFaut() {
        int[] apres = Monnaie.payer(V, new int[]{0, 10, 1, 0, 0, 0}, 8);
        assertArrayEquals(new int[]{0, 2, 1, 0, 0, 0}, apres);
    }

    @Test
    void conserveToujoursLaValeur() {
        java.util.Random r = new java.util.Random(42);
        for (int n = 0; n < 2000; n++) {
            int[] q = new int[V.length];
            for (int i = 0; i < q.length; i++) q[i] = r.nextInt(12);
            long total = Monnaie.total(V, q), montant = r.nextInt(6000);
            int[] apres = Monnaie.payer(V, q, montant);
            if (total < montant) assertNull(apres);
            else assertEquals(total - montant, Monnaie.total(V, apres));
        }
    }

    @Test
    void texteLisible() {
        assertEquals("1 laiton, 3 bronze", Monnaie.texte(67, new int[]{1, 64}, new String[]{"bronze", "laiton"}));
    }

    @Test
    void paieEnRetardSansDebordement() {
        long jour = 1440 * 60_000L, maintenant = 1_791_834_138_252L;
        assertFalse(Monnaie.paieEnRetard(maintenant, Monnaie.SANS_PAIE, jour), "sans paie : jamais en retard");
        assertFalse(Monnaie.paieEnRetard(maintenant, Long.MAX_VALUE - 1, jour), "très loin dans l'avenir : pas de débordement");
        assertFalse(Monnaie.paieEnRetard(maintenant, maintenant - jour / 4 + 1, jour));
        assertTrue(Monnaie.paieEnRetard(maintenant, maintenant - jour / 4, jour), "un quart de jour de retard");
    }
}

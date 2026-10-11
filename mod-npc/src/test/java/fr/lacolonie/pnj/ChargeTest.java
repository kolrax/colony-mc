package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** La fenêtre glissante du banc de charge : moyenne, pire, parties calculées, PNJ comptés. */
class ChargeTest {
    static final double MS = 1e6;

    /** Un tick avec {@code pnj} PNJ de 1 ms chacun (dont 0,2 ms de cerveau) et 0,5 ms de voix. */
    static void tick(Charge c, int pnj) {
        for (int i = 0; i < pnj; i++) {
            c.pnj((long) MS);
            c.ajouter(Charge.Partie.CERVEAU, (long) (0.2 * MS));
        }
        c.ajouter(Charge.Partie.VOIX, (long) (0.5 * MS));
        c.clore();
    }

    @Test
    void vide() {
        Charge c = new Charge(4);
        assertEquals(0, c.ticks());
        assertEquals(0, c.moyenneMs(Charge.Partie.TOTAL));
        assertEquals(0, c.pnjDernier());
        assertFalse(c.lignes(0, 0).isEmpty());
    }

    @Test
    void moyenneEtPire() {
        Charge c = new Charge(4);
        tick(c, 2);
        tick(c, 4);
        assertEquals(2, c.ticks());
        assertEquals(4, c.pnjDernier());
        assertEquals(3, c.pnjMoyen(), 1e-9);
        assertEquals(3, c.moyenneMs(Charge.Partie.ENTITE), 1e-9);
        assertEquals(3.5, c.moyenneMs(Charge.Partie.TOTAL), 1e-9, "les PNJ plus la voix");
        assertEquals(4.5, c.pireMs(Charge.Partie.TOTAL), 1e-9);
        assertEquals(0.8 * 3, c.moyenneMs(Charge.Partie.MOTEUR), 1e-9, "le tick entier moins le cerveau");
    }

    @Test
    void laFenetreOublieLesVieuxTicks() {
        Charge c = new Charge(3);
        tick(c, 100);
        for (int i = 0; i < 3; i++) tick(c, 1);
        assertEquals(3, c.ticks());
        assertEquals(1.5, c.pireMs(Charge.Partie.TOTAL), 1e-9, "le gros tick est sorti de la fenêtre");
        assertEquals(1, c.pnjMoyen(), 1e-9);
    }

    @Test
    void mesurerEnchaine() {
        Charge c = new Charge(2);
        long debut = System.nanoTime();
        long apres = c.mesurer(Charge.Partie.ACTION, debut);
        assertTrue(apres >= debut);
        c.clore();
        assertEquals((apres - debut) / MS, c.moyenneMs(Charge.Partie.ACTION), 1e-9);
    }
}

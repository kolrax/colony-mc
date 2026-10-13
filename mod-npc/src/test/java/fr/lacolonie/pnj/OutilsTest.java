package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Les outils des ouvriers (cahier 3.12 et 3.13) : usure plus lente qu'un joueur, et quel outil est le meilleur. */
class OutilsTest {
    @Test
    void usurePlusLente() {
        // Réglage 1 : comme un joueur.
        assertEquals(1, Outils.usure(1, 1, 0.99));
        assertEquals(7, Outils.usure(7, 1, 0.5));
        // Réglage 3 : un point une fois sur trois pour un coup ; un arbre de 10 bûches : 3 ou 4 points.
        assertEquals(1, Outils.usure(1, 3, 0.2));
        assertEquals(0, Outils.usure(1, 3, 0.5));
        assertEquals(4, Outils.usure(10, 3, 0.0));
        assertEquals(3, Outils.usure(10, 3, 0.9));
        assertEquals(3, Outils.usure(9, 3, 0.99));
        assertEquals(0, Outils.usure(0, 3, 0.0));
        assertEquals(1, Outils.usure(1, 0, 0.9), "un réglage à 0 vaut 1");
        // En moyenne : trois fois moins qu'un joueur.
        Random r = new Random(42);
        int total = 0;
        for (int i = 0; i < 30000; i++) total += Outils.usure(1, 3, r.nextDouble());
        assertEquals(10000, total, 300);
        total = 0;
        for (int i = 0; i < 3000; i++) total += Outils.usure(5, 3, r.nextDouble());
        assertEquals(5000, total, 150);
    }

    @Test
    void leMeilleurOutil() {
        assertTrue(Outils.rang("IRON", 6) > Outils.rang("STONE", 4));
        assertTrue(Outils.rang("STONE", 4) > Outils.rang("GOLD", 12), "l'or ne vaut pas mieux que le bois");
        assertEquals(Outils.rang("DIAMOND", 8), Outils.rang("", 8), "un matériau inconnu est jugé à sa vitesse");
        // Le matériau d'abord : une pioche de fer très usée vaut mieux qu'une pioche de pierre neuve.
        assertTrue(Outils.note(3, 249, 250) > Outils.note(2, 0, 131));
        // Puis le moins usé.
        assertTrue(Outils.note(3, 10, 250) > Outils.note(3, 100, 250));
        // « Nettement meilleure » : un matériau au-dessus, pas seulement moins usée.
        double pierreUsee = Outils.note(2, 120, 131);
        assertFalse(Outils.note(2, 0, 131) > Outils.seuilMieux(pierreUsee));
        assertTrue(Outils.note(3, 249, 250) > Outils.seuilMieux(pierreUsee));
        assertTrue(Outils.note(1, 0, 59) > 0);
    }
}

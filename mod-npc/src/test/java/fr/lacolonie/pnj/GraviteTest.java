package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static fr.lacolonie.pnj.Gravite.*;
import static org.junit.jupiter.api.Assertions.*;

class GraviteTest {
    @Test
    void selonLesDegatsEtLaSource() {
        // Un coup : moins de 2 points rien, puis légère, grave (5), très grave (10) pour 20 points de vie.
        assertEquals(AUCUNE, du(1, 20, Source.COUP));
        assertEquals(LEGERE, du(3, 20, Source.COUP)); // un zombie
        assertEquals(GRAVE, du(6, 20, Source.CHUTE)); // une chute de 9 blocs
        assertEquals(TRES_GRAVE, du(12, 20, Source.CHUTE));
        // Explosion : un cran de plus, même de loin ; lave : un cran de plus, mais une flamme d'un point ne fait pas de plaie.
        assertEquals(LEGERE, du(1, 20, Source.EXPLOSION));
        assertEquals(GRAVE, du(4, 20, Source.EXPLOSION));
        assertEquals(TRES_GRAVE, du(9, 20, Source.EXPLOSION));
        assertEquals(GRAVE, du(4, 20, Source.FEU));
        assertEquals(AUCUNE, du(1, 20, Source.FEU));
        // La faim, la noyade : pas de plaie.
        assertEquals(AUCUNE, du(15, 20, Source.SANS));
        // Rapporté à sa vie : 6 points sur 40, c'est léger.
        assertEquals(LEGERE, du(6, 40, Source.COUP));
    }

    @Test
    void cumulEtATerre() {
        assertEquals(LEGERE, cumuler(LEGERE, LEGERE));
        assertEquals(GRAVE, cumuler(LEGERE, GRAVE));
        assertEquals(GRAVE, cumuler(GRAVE, LEGERE));
        assertEquals(TRES_GRAVE, cumuler(GRAVE, GRAVE));
        assertEquals(LEGERE, cumuler(LEGERE, AUCUNE));
        // À terre : au moins légère, et d'un cran plus grave que la blessure d'avant.
        assertEquals(LEGERE, aTerre(AUCUNE, AUCUNE));
        assertEquals(LEGERE, aTerre(AUCUNE, LEGERE));
        assertEquals(GRAVE, aTerre(LEGERE, LEGERE));
        assertEquals(TRES_GRAVE, aTerre(GRAVE, AUCUNE));
        assertEquals(TRES_GRAVE, aTerre(AUCUNE, TRES_GRAVE));
    }

    @Test
    void gueritUnCranParJour() {
        assertEquals(TRES_GRAVE, actuelle(TRES_GRAVE, 1000, 1000 + JOUR - 1));
        assertEquals(GRAVE, actuelle(TRES_GRAVE, 1000, 1000 + JOUR));
        assertEquals(LEGERE, actuelle(TRES_GRAVE, 1000, 1000 + 2 * JOUR));
        assertEquals(AUCUNE, actuelle(TRES_GRAVE, 1000, 1000 + 3 * JOUR));
        assertEquals(AUCUNE, actuelle(LEGERE, 0, JOUR));
        assertEquals(AUCUNE, actuelle(GRAVE, 0, 100 * JOUR));
        // L'heure remise en arrière (/time set) : rien ne guérit, rien ne s'aggrave.
        assertEquals(GRAVE, actuelle(GRAVE, 5000, 100));
    }

    @Test
    void effets() {
        // Plus c'est grave, plus il reste à terre avant de mourir ; toujours plus longtemps qu'avant (2 min pour tous).
        assertEquals(3 * 60 * 20, LEGERE.ticksATerre());
        assertEquals(5 * 60 * 20, GRAVE.ticksATerre());
        assertEquals(8 * 60 * 20, TRES_GRAVE.ticksATerre());
        assertEquals(LEGERE.ticksATerre(), AUCUNE.ticksATerre());
        assertEquals(0, LEGERE.lenteur());
        assertEquals(0.15, GRAVE.lenteur());
        assertEquals(0.30, TRES_GRAVE.lenteur());
        assertTrue(TRES_GRAVE.auRepos() && !GRAVE.auRepos());
        assertNull(AUCUNE.cle());
        assertEquals("blessure_legere", LEGERE.cle());
        assertEquals("blessure_grave", TRES_GRAVE.cle());
        assertEquals(GRAVE, de(2));
        assertEquals(AUCUNE, de(-1));
        assertEquals(TRES_GRAVE, de(9));
    }
}

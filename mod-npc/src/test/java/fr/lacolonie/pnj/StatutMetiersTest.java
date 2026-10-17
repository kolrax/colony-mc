package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Les blocages des métiers (lot 16) : la raison et la solution, et le cuisinier qui n'a rien à faire. */
class StatutMetiersTest {
    @Test
    void cuisine() {
        assertEquals(Statut.RIEN_A_CUIRE, Statut.cuisine("rien_a_cuire"));
        assertEquals(Statut.RIEN_A_CUIRE, Statut.cuisine("sans_ble"));
        assertEquals(Statut.SANS_FOUR, Statut.cuisine("sans_four"));
        assertEquals(Statut.SANS_COMBUSTIBLE, Statut.cuisine("sans_combustible"));
        assertNull(Statut.cuisine(null));
        assertNull(Statut.cuisine("autre"));
    }

    @Test
    void blocageAvantHorsHoraires() {
        Statut.Etat e = new Statut.Etat("eleveur", false, false, false, false, Statut.ENCLOS, true, null, 0, 0, true);
        assertEquals("⚠ Pas d'enclos", Statut.texte(e));
        assertEquals("Il manque de la pierre au dépôt", Statut.manque("de la pierre"));
    }
}

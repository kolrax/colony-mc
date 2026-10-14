package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static fr.lacolonie.pnj.Bravoure.Reaction.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BravoureTest {
    @Test
    void chaqueCaractere() {
        // (temperament, faible, frappé, collègue frappé, collègue proche, refuge)
        assertEquals(CHARGER, Bravoure.face("colerique", false, false, false, false, true));
        assertEquals(FUIR, Bravoure.face("colerique", true, true, false, false, true));
        assertEquals(SIGNALER, Bravoure.face("calme", false, false, false, true, true));
        assertEquals(RIPOSTER, Bravoure.face("calme", false, true, false, false, true));
        assertEquals(RIPOSTER, Bravoure.face("calme", false, false, true, true, true));
        assertEquals(SIGNALER, Bravoure.face("calme", true, true, false, false, true));
        assertEquals(FUIR, Bravoure.face("peureux", false, false, false, true, true));
        assertEquals(ECLIPSER, Bravoure.face("fourbe", false, false, false, false, true));
        assertEquals(POUSSER, Bravoure.face("fourbe", false, false, false, true, true));
        assertEquals(ECLIPSER, Bravoure.face("fourbe", false, true, false, false, true));
        assertEquals(RIPOSTER, Bravoure.face("fourbe", true, true, false, true, false));
        assertEquals(SIGNALER, Bravoure.face("inconnu", false, true, true, true, true));
        assertEquals(12, Bravoure.vue("peureux"));
        assertEquals(Defense.VUE, Bravoure.vue("colerique"));
    }
}

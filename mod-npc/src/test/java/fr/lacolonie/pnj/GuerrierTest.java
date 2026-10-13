package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Le guerrier (cahier 3.9) : ce que le chef lui dit pour sa ronde, une porte ou un pont à tenir. */
class GuerrierTest {
    @Test
    void leParcoursDeLaRonde() {
        RepertoireTest.demande("Retiens ce point pour ta ronde", "guerrier", "ronde_point");
        RepertoireTest.demande("Ajoute cet endroit à ta ronde, Raoul", "guerrier", "ronde_point");
        RepertoireTest.demande("Ta ronde passe par là", "guerrier", "ronde_point");
        RepertoireTest.demande("Efface ta ronde", "guerrier", "ronde_effacer");
        RepertoireTest.demande("Oublie ta ronde", "guerrier", "ronde_effacer");
        // Ce n'est pas une mission « ronde » : il retient le point sans partir.
        assertTrue(Ordres.lire("Retiens ce point pour ta ronde", "guerrier").missions.isEmpty());
        // « Fais la ronde » reste la mission de toujours.
        RepertoireTest.demande("Fais la ronde", "guerrier", "-");
        assertFalse(Ordres.lire("Fais la ronde", "guerrier").missions.isEmpty());
        RepertoireTest.demande("Retiens ce point pour ta ronde", "mineur", "-");
    }

    @Test
    void tenirUnePorteOuUnPont() {
        for (String s : List.of("Tiens cette porte", "Tiens ce pont !", "Défends ce pont", "Garde cette porte, Raoul", "Tiens cette entrée"))
            RepertoireTest.demande(s, "guerrier", "garder_ici");
        // Les autres phrases restent à leur place.
        RepertoireTest.demande("Ouvre la porte", "guerrier", "ouvrir_porte");
        RepertoireTest.demande("Va à la porte", "guerrier", "aller_porte");
        RepertoireTest.demande("Tiens une torche", "guerrier", "tenir_torche");
        assertFalse(Ordres.lire("Garde le poste", "guerrier").missions.isEmpty());
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InsignesTest {
    static Insignes.Corps bien() { return new Insignes.Corps(false, 20, false, false, 20, false, 0, 70, false); }

    @Test
    void role() {
        assertEquals("Mineur · KOLRAX_MC", Insignes.role("Mineur", "KOLRAX_MC", true));
        assertEquals("Cherche du travail", Insignes.role("Mineur", "", false));
        assertEquals("Cherche du travail", Insignes.role("Mineur", "", true));
    }

    @Test
    void etatSelonLaPriorite() {
        assertEquals("", Insignes.etat(bien()));
        assertEquals("faim", Insignes.etat(new Insignes.Corps(false, 20, false, false, 5, false, 0, 70, false)));
        assertEquals("attente", Insignes.etat(new Insignes.Corps(false, 20, false, false, 20, true, 0, 70, false)));
        assertEquals("sommeil", Insignes.etat(new Insignes.Corps(false, 20, false, false, 20, false, 0, 70, true)));
        assertEquals("colere", Insignes.etat(new Insignes.Corps(false, 20, false, false, 20, false, 0, 10, true)));
        assertEquals("paie", Insignes.etat(new Insignes.Corps(false, 20, false, false, 20, false, 1, 10, true)));
        // la faim passe avant l'attente d'un objet, la grève avant la faim, la blessure avant tout
        assertEquals("faim", Insignes.etat(new Insignes.Corps(false, 20, false, false, 5, true, 0, 70, false)));
        assertEquals("greve", Insignes.etat(new Insignes.Corps(false, 20, false, true, 5, true, 2, 10, false)));
        assertEquals("blesse", Insignes.etat(new Insignes.Corps(true, 20, true, true, 5, true, 2, 10, true)));
        assertEquals("blesse", Insignes.etat(new Insignes.Corps(false, 4, false, false, 20, false, 0, 70, false)));
        assertEquals("peur", Insignes.etat(new Insignes.Corps(false, 20, true, true, 20, false, 0, 70, false)));
    }

    @Test
    void chaqueIconeExiste() {
        Path gui = Path.of("src/main/resources/assets/colonie_pnj");
        for (String e : Insignes.ETATS) assertTrue(Files.exists(gui.resolve(Insignes.icone("etat", e))), e);
        for (String m : Tenues.METIERS) assertTrue(Files.exists(gui.resolve(Insignes.icone("metier", m))), m);
        assertEquals("textures/gui/metier/chomeur.png", Insignes.icone("metier", "inconnu"));
    }
}

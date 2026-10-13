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
        assertEquals("Cherche du travail", Insignes.role("Mineur", "KOLRAX_MC", false));
        // employé dont on ne connaît pas le chef (banc, vieille sauvegarde) : son métier seul
        assertEquals("Mineur", Insignes.role("Mineur", "", true));
        assertEquals("Mineur", Insignes.role("Mineur", null, true));
    }

    @Test
    void vueRevueAuDebutPuisToutesLesDixTicks() {
        assertTrue(Insignes.arevoir(0, Long.MIN_VALUE), "jamais vu : on regarde tout de suite");
        assertTrue(Insignes.arevoir(123456, Long.MIN_VALUE));
        assertFalse(Insignes.arevoir(105, 100));
        assertFalse(Insignes.arevoir(109, 100));
        assertTrue(Insignes.arevoir(110, 100));
        assertTrue(Insignes.arevoir(50, 100), "le temps a reculé (autre monde) : on regarde de nouveau");
    }

    @Test
    void prenom() {
        assertEquals("Philippa", Insignes.prenom("Philippa (Mineur)"));
        assertEquals("Philippa", Insignes.prenom("Philippa · en grève"));
        assertEquals("Philippa", Insignes.prenom("Philippa · cherche du travail"));
        assertEquals("Jean-Baptiste", Insignes.prenom("Jean-Baptiste"));
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
        assertTrue(Files.isRegularFile(gui.resolve("textures/gui/metier/chomeur.png")), "l'icône du chômeur, prise par défaut");
    }
}

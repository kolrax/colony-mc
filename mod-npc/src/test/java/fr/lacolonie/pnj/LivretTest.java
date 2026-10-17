package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Le livret du chef découpé en livre, sans le jeu. */
class LivretTest {
    @Test
    void titreAuteurEtPages() {
        Livret.Contenu c = Livret.lire("titre: Le métier de chef\nauteur: L'Intendant\nBienvenue.\n---\nLE POSTE\n\nPose-le.\n---\n\n---\nFin");
        assertEquals("Le métier de chef", c.titre());
        assertEquals("L'Intendant", c.auteur());
        assertEquals(3, c.pages().size(), "une page vide ne compte pas");
        assertEquals("LE POSTE\n\nPose-le.", c.pages().get(1));
    }

    @Test
    void bornesDuLivreDuJeu() {
        Livret.Contenu c = Livret.lire("titre: " + "x".repeat(50) + "\nauteur: a\n" + "y".repeat(2000));
        assertEquals(Livret.TITRE_MAX, c.titre().length());
        assertEquals(Livret.PAGE_MAX, c.pages().get(0).length());
    }

    @Test
    void leVraiLivretSeLit() throws Exception {
        try (InputStream in = Livret.class.getResourceAsStream("/livret.txt")) {
            assertNotNull(in, "livret.txt dans les ressources");
            Livret.Contenu c = Livret.lire(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(c.titre().isBlank());
            assertFalse(c.auteur().isBlank());
            assertTrue(c.pages().size() >= 5, "des pages : " + c.pages().size());
            assertTrue(c.pages().stream().allMatch(p -> p.length() <= Livret.PAGE_MAX));
        }
    }
}

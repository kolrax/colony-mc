package fr.lacolonie.pnj;

import fr.lacolonie.pnj.GardeChef.Posture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ce que le chef dit de ceux qui le frappent (cahier 14.6), lu par {@link Ordres#lire} (voir {@link GardeChef}). */
class GardeChefTest {
    static Ordres.Lecture lu(String s) { return Ordres.lire(s, "guerrier"); }

    @Test
    void attaqueQuiconqueMeFrappe() {
        for (String s : List.of("Attaque quiconque me frappe.", "Si quelqu'un me frappe, attaque-le !", "Défends-moi contre quiconque m'attaque.",
                "Tu frappes tous ceux qui lèvent la main sur moi.", "Attaque n'importe qui qui me touche", "Si on s'en prend à moi, tombe-lui dessus."))
            assertEquals(Posture.ATTAQUE, lu(s).posture, s);
        // L'ordre ne laisse rien derrière lui (ni mission, ni geste).
        Ordres.Lecture l = lu("Attaque quiconque me frappe.");
        assertTrue(l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty(), l.texte);
    }

    @Test
    void resteTranquille() {
        for (String s : List.of("Reste tranquille, ne frappe personne.", "Sois plus tranquille.", "Si on me frappe, interpose-toi sans le frapper.",
                "Quand quelqu'un me frappe, ne le frappe pas.", "Ne te bats pas.", "Ne frappe plus personne."))
            assertEquals(Posture.TRANQUILLE, lu(s).posture, s);
    }

    @Test
    void neFaisJamaisRienA() {
        assertEquals(List.of("Nielscutt"), lu("Ne fais jamais rien à Nielscutt.").epargner);
        assertEquals(List.of("Niels_cutt42"), lu("ne touche jamais à Niels_cutt42").epargner);
        assertEquals(List.of("Raoul"), lu("Laisse Raoul tranquille, et ramène 10 fers").epargner);
        assertTrue(GardeChef.epargne("Raoul,nielscutt", "NielsCutt"));
        assertFalse(GardeChef.epargne("Raoul", "Nielscutt"));
        assertEquals("Raoul,Nielscutt", GardeChef.ajouter("Raoul", List.of("Nielscutt", "raoul")));
    }

    @Test
    void lesAutresOrdresNeChangentPas() {
        for (String s : List.of("Attaque ce zombie.", "Ramène 64 bûches", "Ne touche pas au coffre", "Ne touche à rien", "Garde le poste", "Frappe le mannequin"))
            assertTrue(lu(s).posture == null && lu(s).epargner.isEmpty(), s);
        assertFalse(Ordres.lire("Laisse Raoul tranquille, et ramène 10 bûches", "bucheron").missions.isEmpty());
    }

    @Test
    void cesser() {
        assertTrue(GardeChef.cesser("Arrête !"));
        assertTrue(GardeChef.cesser("Ça suffit, laisse-le."));
        assertFalse(GardeChef.cesser("Attaque quiconque me frappe."));
    }
}

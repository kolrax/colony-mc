package fr.lacolonie.pnj;

import fr.lacolonie.pnj.GardeChef.Posture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ce que le chef dit du dépôt de son poste (cahier 6.4), lu par {@link Ordres#lire} (voir {@link GardeDepot}). */
class GardeDepotTest {
    static Ordres.Lecture lu(String s) { return Ordres.lire(s, "guerrier"); }

    @Test
    void attaqueLesVoleurs() {
        for (String s : List.of("Attaque ceux qui volent le dépôt.", "Défends le dépôt même par la force.", "Si quelqu'un vole au dépôt, frappe-le !",
                "Frappe les voleurs.", "Tue quiconque pille la réserve, sans pitié.", "Défends nos coffres, l'épée à la main."))
            assertEquals(Posture.ATTAQUE, lu(s).depot, s);
        // L'ordre ne laisse rien derrière lui (ni mission, ni geste, ni garde du chef).
        Ordres.Lecture l = lu("Si quelqu'un vole au dépôt, frappe-le !");
        assertTrue(l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty() && l.posture == null, l.texte);
    }

    @Test
    void dansLeDouteIlNeFrappePas() {
        for (String s : List.of("Défends le dépôt sans frapper personne.", "Ne frappe pas ceux qui volent le dépôt.", "Défends le dépôt.",
                "Si on se sert au dépôt, interpose-toi.", "N'attaque jamais les voleurs."))
            assertEquals(Posture.TRANQUILLE, lu(s).depot, s);
        // Rien sur le dépôt : rien de lu.
        for (String s : List.of("Attaque quiconque me frappe.", "Prends du fer au dépôt.", "Va déposer tes pierres au dépôt.", "Attaque ce zombie qui pille le dépôt."))
            assertNull(lu(s).depot, s);
        assertEquals(Posture.ATTAQUE, lu("Attaque quiconque me frappe.").posture);
    }

    @Test
    void autoriserEtRetirer() {
        assertEquals(List.of("Nielscutt"), lu("Nielscutt peut se servir dans le dépôt.").permis);
        assertEquals(List.of("Niels_cutt42"), lu("Autorise Niels_cutt42 à prendre au dépôt.").permis);
        assertEquals(List.of("Raoul"), lu("Laisse Raoul se servir.").permis);
        assertEquals(List.of("Raoul"), lu("Raoul a le droit de se servir, et ramène 10 fers.").permis);
        assertEquals(List.of("Raoul"), lu("Raoul n'a plus le droit.").retires);
        assertEquals(List.of("Raoul"), lu("Raoul ne peut plus se servir au dépôt.").retires);
        assertEquals(List.of("Raoul"), lu("Interdis le dépôt à Raoul.").retires);
        // Ni une garde, ni un geste, ni la garde du chef avec.
        for (String s : List.of("Nielscutt peut se servir dans le dépôt.", "Raoul n'a plus le droit.", "Laisse Raoul se servir.")) {
            Ordres.Lecture l = lu(s);
            assertTrue(l.depot == null && l.posture == null && l.epargner.isEmpty() && l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty(), s + " : " + l.texte);
        }
        // Pas un nom, pas le dépôt : rien.
        assertTrue(lu("Il peut se servir au dépôt.").permis.isEmpty());
        assertTrue(lu("Jean peut prendre sa pause.").permis.isEmpty());
        assertTrue(lu("Tu peux te servir au dépôt.").permis.isEmpty());
        // Épargné n'est pas autorisé.
        assertTrue(lu("Ne fais jamais rien à Nielscutt.").permis.isEmpty());
        // Le reste de la phrase reste un ordre.
        Ordres.Lecture avec = Ordres.lire("Raoul a le droit de se servir, et ramène 10 fers.", "mineur"), seul = Ordres.lire("Ramène 10 fers.", "mineur");
        assertFalse(seul.vide());
        assertEquals(seul.intentions, avec.intentions);
        assertEquals(seul.missions.size(), avec.missions.size());
    }

    @Test
    void laListe() {
        assertEquals("Raoul,Nielscutt", GardeDepot.changer("Raoul", List.of("Nielscutt", "raoul"), List.of()));
        assertEquals("Nielscutt", GardeDepot.changer("Raoul,Nielscutt", List.of(), List.of("RAOUL")));
        assertEquals("", GardeDepot.changer("", List.of(), List.of("Raoul")));
    }
}

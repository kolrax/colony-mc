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
                "Frappe les voleurs du dépôt.", "Attaque ceux qui touchent au dépôt.", "Tue quiconque pille la réserve, sans pitié.", "Défends nos coffres l'épée à la main."))
            assertEquals(Posture.ATTAQUE, lu(s).depot, s);
        // L'ordre ne laisse rien derrière lui (ni mission, ni geste, ni garde du chef).
        Ordres.Lecture l = lu("Si quelqu'un vole au dépôt, frappe-le !");
        assertTrue(l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty() && l.posture == null, l.texte);
    }

    @Test
    void dansLeDouteIlNeFrappePas() {
        for (String s : List.of("Défends le dépôt sans frapper personne.", "Ne frappe pas ceux qui volent le dépôt.", "Défends le dépôt.",
                "Si on se sert au dépôt, interpose-toi.", "N'attaque jamais ceux qui pillent le dépôt."))
            assertEquals(Posture.TRANQUILLE, lu(s).depot, s);
        // Rien sur le dépôt : rien de lu.
        for (String s : List.of("Attaque quiconque me frappe.", "Prends du fer au dépôt.", "Va déposer tes pierres au dépôt.", "Attaque ce zombie qui pille le dépôt.",
                "Va prendre une épée au dépôt et attaque-le."))
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
        assertEquals(List.of("Jean-Pierre_2"), lu("Jean-Pierre_2 n'a plus le droit.").retires);
        assertEquals(List.of("test-mock-player"), lu("test-mock-player peut se servir dans le dépôt.").permis);
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

    /** Relecture B1 : « arrête de frapper… », « plus la peine… » calment ; un participe (« tu as frappé ») n'est pas un ordre. */
    @Test
    void cesserNEstPasAttaquer() {
        for (String s : List.of("Arrête de frapper les voleurs du dépôt.", "Cesse de frapper ceux qui volent le dépôt.", "Assez frappé les voleurs du dépôt.",
                "Plus la peine de frapper les voleurs du dépôt."))
            assertEquals(Posture.TRANQUILLE, lu(s).depot, s);
        for (String s : List.of("Arrête de frapper les voleurs.", "Cesse de frapper ceux qui volent le dépôt", "Assez frappé les voleurs.", "Plus la peine de frapper les voleurs."))
            assertNotEquals(Posture.ATTAQUE, lu(s).depot, s);
        assertNull(lu("Tu as frappé les voleurs du dépôt hier.").depot);
    }

    /** Relecture B3 : le verbe de force et le dépôt dans la même partie de phrase ; « ce voleur », « voleurs de chevaux » ne comptent pas. */
    @Test
    void pasDOrdreDeDepotSansLeDepot() {
        for (String s : List.of("Je déteste les voleurs, tape fort sur ce minerai.", "Fouille le dépôt et frappe le fer.", "Attaque ce voleur.",
                "Raoul est un voleur, frappe-le si tu le vois.", "Va au dépôt, prends une épée, puis attaque les voleurs de la colonie de Raoul.",
                "Vole au dépôt de Raoul et frappe-le.", "Tue les voleurs de chevaux."))
            assertTrue(GardeDepot.lire(s).rien(), s + " : " + lu(s).depot);
    }

    /** Relecture I1 : « sauf Raoul » : Raoul peut se servir ; « sauf la nuit » : dans le doute, pas de coups. */
    @Test
    void saufUnNom() {
        for (String s : List.of("Frappe les voleurs du dépôt sauf Raoul.", "Attaque ceux qui pillent le dépôt, excepté Raoul.", "Hormis Raoul, frappe ceux qui volent le dépôt.")) {
            Ordres.Lecture l = lu(s);
            assertEquals(Posture.ATTAQUE, l.depot, s);
            assertEquals(List.of("Raoul"), l.permis, s);
        }
        assertEquals(Posture.TRANQUILLE, lu("Frappe les voleurs du dépôt, sauf la nuit.").depot);
    }

    /** Relecture I4 : sans le dépôt dans la phrase, seul « se servir » fait un permis. */
    @Test
    void permisSeulementPourLeDepot() {
        for (String s : List.of("Donne à Raoul le droit de passer.", "Autorise Raoul à prendre une pause.", "Raoul a la permission de prendre la charrette."))
            assertTrue(lu(s).permis.isEmpty(), s);
    }

    /** Relecture B2 : « ne frappe personne », « reste tranquille » calment aussi la garde du dépôt. */
    @Test
    void resteTranquilleCalmeLeDepot() {
        assertEquals(Posture.TRANQUILLE, GardeDepot.garde(Posture.ATTAQUE, lu("Ne frappe personne.")));
        assertEquals(Posture.TRANQUILLE, GardeDepot.garde(Posture.ATTAQUE, lu("Reste tranquille.")));
        assertEquals(Posture.ATTAQUE, GardeDepot.garde(Posture.ATTAQUE, lu("Ramène 10 fers.")));
        assertEquals(Posture.ATTAQUE, GardeDepot.garde(Posture.TRANQUILLE, lu("Attaque ceux qui volent le dépôt.")));
    }
}

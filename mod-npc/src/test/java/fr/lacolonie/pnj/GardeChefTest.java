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

    // --- Relecture du 14/10 : jamais d'attaque sur une interdiction ni sur une phrase anodine -------------------------

    @Test
    void lesInterdictionsNeDonnentJamaisLAttaque() {
        assertAll(List.of("N'attaque pas quiconque me frappe.", "N'attaque jamais ceux qui me frappent.", "Ne frappe jamais ceux qui me frappent.",
                "Si quelqu'un me frappe, ne le frappe surtout pas.", "Je ne veux pas que tu frappes ceux qui me frappent.",
                "Tu ne dois pas frapper ceux qui me touchent.", "Évite de frapper ceux qui me frappent.",
                "Si on me frappe, chasse-le de mes terres sans le blesser.").stream().map(s -> (org.junit.jupiter.api.function.Executable) () -> assertEquals(Posture.TRANQUILLE, lu(s).posture, s)));
    }

    @Test
    void epargneSansAttaquePourLesAutres() {
        Ordres.Lecture l = lu("Ne frappe jamais Nielscutt, même s'il me frappe.");
        assertEquals(List.of("Nielscutt"), l.epargner);
        assertNotEquals(Posture.ATTAQUE, l.posture);
    }

    @Test
    void lesPhrasesAnodinesNeChangentRien() {
        assertAll(List.of("Arrête de taper sur la pierre, ce Raoul me frappe encore", "Préviens-moi si quelqu'un me frappe ou me tape.",
                "Si un zombie m'attaque, tue-le.", "Si un creeper me menace, attaque-le à l'arc.").stream().map(s -> (org.junit.jupiter.api.function.Executable) () -> {
            GardeChef.Lu g = GardeChef.lire(s);
            assertTrue(g.rien(), s + " : " + g.posture + " " + g.epargner);
            assertEquals(s, g.reste, "la phrase reste entière");
        }));
    }

    @Test
    void lOrdreDeGardeLaisseLeResteDeLaPhrase() {
        assertAll(() -> {
            Ordres.Lecture l = Ordres.lire("Va miner, et si on m'attaque, défends-moi.", "mineur");
            assertEquals(Posture.ATTAQUE, l.posture);
            assertFalse(l.missions.isEmpty() && l.intentions.isEmpty(), "il va miner : " + l.texte);
        }, () -> {
            Ordres.Lecture l = Ordres.lire("Ramène 64 bûches et si quelqu'un me frappe, tape-le.", "bucheron");
            assertEquals(Posture.ATTAQUE, l.posture);
            assertFalse(l.missions.isEmpty(), "les bûches : " + l.texte);
        }, () -> {
            Ordres.Lecture l = Ordres.lire("Suis-moi et si on me frappe, attaque.", "guerrier");
            assertEquals(Posture.ATTAQUE, l.posture);
            assertTrue(l.intentions.contains(Ordres.Intention.SUIVRE), "il suit : " + l.texte);
        });
    }

    @Test
    void laLectureSansIAEstRecopiee() {
        Ordres.Lecture ia = new Ordres.Lecture(), seul = lu("Attaque quiconque me frappe. Ne fais jamais rien à Nielscutt.");
        GardeChef.reprendre(ia, seul);
        assertEquals(Posture.ATTAQUE, ia.posture);
        assertEquals(List.of("Nielscutt"), ia.epargner);
    }

    @Test
    void cesserLaRiposte() {
        assertAll(List.of("Arrête !", "Reste tranquille !", "Calme-toi !", "Ne frappe plus personne.", "Range ton épée.", "Oublie tes ordres").stream()
                .map(s -> (org.junit.jupiter.api.function.Executable) () -> assertTrue(GardeChef.cesse(lu(s)), s)));
        for (String s : List.of("Attaque quiconque me frappe.", "Ramène 64 bûches", "Suis-moi"))
            assertFalse(GardeChef.cesse(lu(s)), s);
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Le journal des phrases non comprises (lot 3), sans le jeu. */
class IncomprisTest {

    @Test
    void normaliseMinusculesEspacesEtPonctuationFinale() {
        assertEquals("fais tourner la lune à l'envers", Incompris.normaliser("  Fais   tourner la LUNE à l’envers ?! "));
        assertEquals("hé, toi", Incompris.normaliser("Hé, toi…"));
        assertEquals("", Incompris.normaliser(" ?! "));
    }

    @Test
    void lesDoublonsSeRegroupentAvecUnCompteur() {
        Incompris j = new Incompris();
        j.noter("Fais tourner la lune à l'envers !", Incompris.RIEN, List.of("mineur"), 1000);
        j.noter("fais  tourner la lune à l'envers", Incompris.RIEN, List.of("guerrier"), 5000);
        j.noter("Fais tourner la lune à l'envers", Incompris.IA_SEULE, List.of("mineur"), 6000);
        assertEquals(2, j.taille(), "même phrase, même motif : une seule entrée ; autre motif : une autre");
        Map<String, Object> e = j.plusFrequentes(1).get(0);
        assertEquals(2, e.get("nombre"));
        assertEquals("rien", e.get("motif"));
        assertEquals("fais  tourner la lune à l'envers", e.get("phrase"), "la phrase d'origine la plus récente");
        assertEquals(List.of("guerrier", "mineur"), e.get("metiers"));
        assertEquals("1970-01-01T00:00:01Z", e.get("premiere"));
        assertEquals("1970-01-01T00:00:05Z", e.get("derniere"));
        assertFalse(e.toString().contains("joueur"), "aucun nom de joueur");
    }

    @Test
    void rangeesParFrequencePuisParDateRecente() {
        Incompris j = new Incompris();
        j.noter("une", Incompris.RIEN, List.of(), 1);
        for (int i = 0; i < 3; i++) j.noter("trois", Incompris.RIEN, List.of(), 2 + i);
        j.noter("deux", Incompris.RIEN, List.of(), 10);
        j.noter("deux", Incompris.RIEN, List.of(), 11);
        j.noter("autre", Incompris.RIEN, List.of(), 20);
        List<Map<String, Object>> l = j.plusFrequentes(10);
        assertEquals(List.of("trois", "deux", "autre", "une"), l.stream().map(x -> x.get("phrase")).toList());
        assertEquals(2, j.plusFrequentes(2).size());
    }

    @Test
    void couperA200Caracteres() {
        Incompris j = new Incompris();
        String longue = "a".repeat(150) + " " + "b".repeat(150);
        j.noter(longue, Incompris.RIEN, List.of(), 1);
        j.noter(longue + " encore", Incompris.RIEN, List.of(), 2);
        assertEquals(1, j.taille(), "coupées pareil, elles se regroupent");
        String p = (String) j.plusFrequentes(1).get(0).get("phrase");
        assertEquals(Incompris.LONGUEUR, p.length());
        // Un caractère hors du plan de base n'est jamais coupé en deux.
        String emoji = "a".repeat(199) + "😀";
        assertFalse(Character.isHighSurrogate(Incompris.couper(emoji).charAt(Incompris.couper(emoji).length() - 1)));
    }

    @Test
    void borneA500EnRetirantDAbordLesAnciennesVuesUneFois() {
        Incompris j = new Incompris();
        j.noter("ancienne mais fréquente", Incompris.RIEN, List.of(), 0);
        j.noter("ancienne mais fréquente", Incompris.RIEN, List.of(), 1);
        for (int i = 0; i < Incompris.MAX - 1; i++) j.noter("phrase " + i, Incompris.RIEN, List.of(), 100 + i);
        assertEquals(Incompris.MAX, j.taille());
        j.noter("la nouvelle", Incompris.RIEN, List.of(), 10_000);
        assertEquals(Incompris.MAX, j.taille());
        List<Object> phrases = j.plusFrequentes(Incompris.MAX).stream().map(x -> x.get("phrase")).toList();
        assertTrue(phrases.contains("ancienne mais fréquente"), "vue deux fois : gardée");
        assertFalse(phrases.contains("phrase 0"), "la plus ancienne vue une seule fois part");
        assertTrue(phrases.contains("phrase 1") && phrases.contains("la nouvelle"));
    }

    @Test
    void allerRetourJsonEtFichier(@TempDir Path dossier) throws Exception {
        Incompris j = new Incompris();
        j.noter("Fais tourner la lune à l'envers", Incompris.RIEN, List.of("mineur", "guerrier"), 1_760_000_000_000L);
        j.noter("Fais tourner la lune à l'envers", Incompris.RIEN, List.of("bucheron"), 1_760_000_100_000L);
        j.noter("Mets-toi droit comme un piquet", Incompris.IA_SEULE, List.of("guerrier"), 1_760_000_200_000L);
        Path f = dossier.resolve("colonie_pnj/incompris.json");
        assertNull(new Incompris().aSauver(), "rien à écrire sans changement");
        j.sauver(f, true, e -> fail(e));
        assertNull(j.aSauver(), "écrit : plus rien à sauver");
        assertTrue(Files.readString(f).contains("\"motif\": \"ia_seule\""));

        Incompris relu = new Incompris();
        relu.charger(f);
        assertEquals(j.plusFrequentes(10), relu.plusFrequentes(10));
        relu.noter("fais tourner la lune à l'envers.", Incompris.RIEN, List.of(), 1_760_000_300_000L);
        assertEquals(3, relu.plusFrequentes(1).get(0).get("nombre"), "le compteur reprend après relecture");

        Incompris abime = new Incompris();
        abime.lire("{\"entrees\":[{\"phrase\":\"bonne\",\"motif\":\"rien\",\"nombre\":2,\"premiere\":\"2026-10-12T10:00:00Z\",\"derniere\":\"2026-10-12T11:00:00Z\"},{\"phrase\":42}]}");
        assertEquals(1, abime.taille(), "une entrée abîmée ne perd pas les autres");
        relu.vider();
        assertEquals(0, relu.taille());
        assertNotNull(relu.aSauver(), "vider se sauve aussi");
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cahier 3.2 : les chantiers du mineur dits de vive voix (salle, carrière, tunnel selon un plan). */
class FouilleTest {
    static String plan(String genre, String phrase) { return Fouille.lire(genre, phrase, 8).texte(); }

    @Test
    void lesMesuresDites() {
        assertEquals("5 5 3 - face", plan("salle", "Creuse une salle"));
        assertEquals("7 4 3 - face", plan("salle", "Creuse une salle de 7 sur 4"));
        assertEquals("10 10 3 - face", plan("salle", "Vide cette zone, de 10 blocs"));
        assertEquals("6 5 4 - face", plan("salle", "Creuse une cave de six sur cinq et 4 de haut"));
        assertEquals("8 8 4 - bas", plan("carriere", "Fais une carrière ici"));
        assertEquals("10 12 3 - bas", plan("carriere", "Fais une carrière de 10 x 12 sur 3 couches"));
        assertEquals("16 16 8 - bas", plan("carriere", "Une carrière de 100 sur 100 et 20 de profondeur")); // bornée
    }

    @Test
    void leTunnelSelonUnPlan() {
        assertEquals("8 1 2 - face", plan("tunnel", "Creuse un tunnel"));
        assertEquals("30 1 2 north face", plan("tunnel", "Creuse un tunnel de 30 blocs vers le nord"));
        assertEquals("trente 1 2 east face".replace("trente", "30"), plan("tunnel", "Perce un tunnel de trente blocs vers l'est"));
        assertEquals("16 3 4 - face", plan("tunnel", "Creuse un tunnel de train"));
        assertEquals("40 3 4 west face", plan("tunnel", "Un tunnel de train de 40 blocs à l'ouest"));
        assertEquals("12 2 3 south face", plan("tunnel", "Creuse un tunnel de 12 blocs, 2 de large et 3 de haut, plein sud"));
        assertEquals("64 1 2 - face", plan("tunnel", "Creuse un tunnel de 500 blocs"));
        // « est » le verbe n'est pas l'est.
        assertEquals("", Fouille.direction(Ordres.normaliser("Le tunnel est trop court")));
    }

    @Test
    void relireLePlanDeLaConsigne() {
        Fouille.Plan p = Fouille.lire("tunnel", "tunnel de 20 blocs au sud", 8);
        assertEquals(p, Fouille.Plan.de(p.texte()));
        assertNull(Fouille.Plan.de("north")); // une consigne d'avant : la direction seule
    }

    @Test
    void lesBlocsDansLOrdre() {
        // De face : tranche par tranche, du haut vers le bas ; une torche tous les 8 blocs.
        List<int[]> s = Fouille.blocs(new Fouille.Plan(5, 5, 3, "", false));
        assertEquals(75, s.size());
        assertArrayEquals(new int[]{0, -2, 2, 0}, s.get(0));
        assertArrayEquals(new int[]{0, 2, 0, 0}, s.get(14));
        List<int[]> t = Fouille.blocs(new Fouille.Plan(16, 1, 2, "", false));
        assertEquals(2, t.stream().filter(x -> x[3] == 1).count());
        // Vers le bas : couche par couche, un escalier gardé au premier rang (0 + 1 + 2 + 3 marches).
        List<int[]> c = Fouille.blocs(new Fouille.Plan(8, 8, 4, "", true));
        assertEquals(8 * 8 * 4 - 6, c.size());
        for (int i = 1; i < c.size(); i++) assertTrue(c.get(i)[2] <= c.get(i - 1)[2], "du haut vers le bas");
        assertTrue(c.stream().noneMatch(x -> x[0] == 0 && x[1] == -3 && x[2] == -1), "la première marche reste");
        assertTrue(c.stream().anyMatch(x -> x[0] == 1 && x[1] == -3 && x[2] == -1), "on descend la marche suivante");
    }

    @Test
    void leRepertoireLesComprend() {
        RepertoireTest.demande("Creuse une salle de 5 sur 5", "mineur", "salle");
        RepertoireTest.demande("Vide cette zone", "mineur", "salle");
        RepertoireTest.demande("Creuse une cave", "mineur", "salle");
        RepertoireTest.demande("Fais une carrière ici", "mineur", "carriere");
        RepertoireTest.demande("Creuse un tunnel de 30 blocs vers le nord", "mineur", "tunnel");
        RepertoireTest.demande("Creuse un tunnel de train", "mineur", "tunnel");
        assertTrue(Ordres.lire("Creuse une salle de 5 sur 5", "mineur").missions.isEmpty(), "pas une commande de 5 blocs");
    }
}

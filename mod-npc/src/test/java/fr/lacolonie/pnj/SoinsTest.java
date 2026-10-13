package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Le guérisseur (lot 5), la part pure : qui secourir d'abord, ce que fait le soin, ses ordres et ses répliques. */
class SoinsTest {
    static Soins.Cas terre(int reste, double d) { return new Soins.Cas(true, reste, Gravite.TRES_GRAVE, false, d, false); }

    static Soins.Cas debout(Gravite g, boolean soigne, double d) { return new Soins.Cas(false, 0, g, soigne, d, false); }

    @Test
    void aTerreDAbordPuisLePlusGrave() {
        // Un très grave tout près, un ouvrier à terre loin : il court d'abord à celui qui est à terre.
        assertEquals(1, Soins.choisir(List.of(debout(Gravite.TRES_GRAVE, false, 4), terre(5000, 900)), false));
        // Deux à terre : celui à qui il reste le moins de temps.
        assertEquals(1, Soins.choisir(List.of(terre(5000, 4), terre(1200, 900)), false));
        // Debout : le plus grave, puis le plus proche.
        assertEquals(1, Soins.choisir(List.of(debout(Gravite.GRAVE, false, 4), debout(Gravite.TRES_GRAVE, false, 900)), false));
        assertEquals(1, Soins.choisir(List.of(debout(Gravite.GRAVE, false, 400), debout(Gravite.GRAVE, false, 9)), false));
    }

    @Test
    void uneFoisParBlessureEtPasLesEgratignures() {
        // Déjà pansé de cette blessure, ou une blessure légère : personne (sauf sur ordre, pour la légère).
        assertEquals(-1, Soins.choisir(List.of(debout(Gravite.TRES_GRAVE, true, 4), debout(Gravite.LEGERE, false, 4), debout(Gravite.AUCUNE, false, 1)), false));
        assertEquals(1, Soins.choisir(List.of(debout(Gravite.TRES_GRAVE, true, 4), debout(Gravite.LEGERE, false, 4)), true));
        assertTrue(Soins.aPanser(Gravite.GRAVE, false, false));
        assertFalse(Soins.aPanser(Gravite.GRAVE, true, true));
        assertFalse(Soins.aPanser(Gravite.AUCUNE, false, true));
    }

    @Test
    void pasDeBlessesSousLaMenaceDUnMonstre() {
        Soins.Cas menace = new Soins.Cas(true, 100, Gravite.TRES_GRAVE, false, 4, true);
        // L'ouvrier à terre a un monstre tout près : il panse d'abord le blessé tranquille, et il le dit.
        assertEquals(1, Soins.choisir(List.of(menace, debout(Gravite.GRAVE, false, 900)), false));
        assertEquals(-1, Soins.choisir(List.of(menace), false));
        assertTrue(Soins.attendre(List.of(menace), false));
        assertFalse(Soins.attendre(List.of(debout(Gravite.GRAVE, false, 4)), false));
    }

    @Test
    void leSoinBaisseDUnCranEtRendDeLaVie() {
        assertEquals(Gravite.GRAVE, Soins.apres(Gravite.TRES_GRAVE));
        assertEquals(Gravite.LEGERE, Soins.apres(Gravite.GRAVE));
        assertEquals(Gravite.AUCUNE, Soins.apres(Gravite.LEGERE));
        assertEquals(Gravite.AUCUNE, Soins.apres(Gravite.AUCUNE));
        assertEquals(18f, Soins.vie(10, 24));
        assertEquals(24f, Soins.vie(20, 24), "jamais plus que sa vie");
    }

    @Test
    void sesOrdres() {
        assertEquals("soigner", RepertoireTest.action("Soigne Jean", "guerisseur"));
        assertEquals("soigner", RepertoireTest.action("Va soigner Aliénor", "guerisseur"));
        assertEquals("soigner_blesses", RepertoireTest.action("Va soigner les blessés", "guerisseur"));
        assertEquals("soigner_blesses", RepertoireTest.action("soigne les blessés", "guerisseur"));
        // « Soigne-toi » reste pour lui-même.
        assertEquals("se_soigner", RepertoireTest.action("Soigne-toi", "guerisseur"));
        // Un mineur ne soigne pas les autres.
        assertNotEquals("soigner", RepertoireTest.action("Soigne Jean", "mineur"));
    }

    @Test
    void sesRepliques() {
        Map<String, List<Vie.Replique>> banque = Vie.lire(Repertoire.lignes("/vie.txt"));
        List<Vie.Replique> r = banque.get("guerisseur_soigne");
        assertNotNull(r);
        assertTrue(r.size() >= 6, "6 répliques : " + r.size());
        for (double x = 0; x < 1; x += 0.1) {
            String dit = Vie.tirer(banque, "guerisseur_soigne", "calme", Map.of("nom", "Jean", "chef", "KOLRAX"), () -> 0.5);
            assertTrue(dit != null && dit.contains("Jean"), dit);
        }
    }
}

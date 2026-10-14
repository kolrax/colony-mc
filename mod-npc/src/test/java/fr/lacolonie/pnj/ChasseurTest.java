package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Chasse.Bete;
import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 6, le chasseur : le gibier qu'il a le droit de tirer, et ce que le chef lui demande, lu sans IA. */
class ChasseurTest {
    /** Un adulte sauvage, à personne, à {@code d} (au carré) de lui. */
    static Bete sauvage(int id, String espece, double d) { return new Bete(id, espece, true, true, false, d); }

    @Test
    void seulementDuGibierSauvageAdulteEtAPersonne() {
        List<Bete> b = List.of(
                sauvage(1, "cochon", 50), sauvage(2, "cochon", 10), sauvage(3, "cochon", 90), sauvage(4, "cochon", 70),
                new Bete(5, "cochon", false, true, false, 1),   // un petit
                new Bete(6, "cochon", true, true, true, 2),     // nommé, en laisse ou chez un voisin
                new Bete(7, "cochon", true, false, false, 3));  // dans un enclos
        // 5 adultes sauvages (le nommé compris) : il en laisse 2, donc 3 au plus, les plus proches, jamais le petit, le nommé ni celui de l'enclos.
        assertEquals(List.of(2, 1, 4), Chasse.proies(b, null, 10));
        assertEquals(List.of(2), Chasse.proies(b, null, 1));
        // Le cochon de l'enclos ne compte pas parmi le gibier : avec 2 sauvages seulement, rien.
        assertEquals(List.of(), Chasse.proies(List.of(sauvage(1, "cochon", 5), sauvage(2, "cochon", 5), new Bete(3, "cochon", true, false, false, 1),
                new Bete(4, "cochon", true, false, false, 1)), null, 5));
    }

    @Test
    void jamaisLeDernierDeSonEspece() {
        // Deux lapins et trois poules : pas un lapin (il en garde 2), une poule.
        List<Bete> b = List.of(sauvage(1, "lapin", 1), sauvage(2, "lapin", 2), sauvage(3, "poule", 9), sauvage(4, "poule", 8), sauvage(5, "poule", 7));
        assertEquals(List.of(5), Chasse.proies(b, null, 5));
        assertEquals(List.of(), Chasse.proies(b, Set.of("lapin"), 5));
        assertEquals(List.of(), Chasse.proies(List.of(sauvage(1, "vache", 1)), null, 5));
        // Il laisse du gibier pour qu'il revienne : seulement quand c'est vraiment la raison.
        assertTrue(Chasse.laisse(b, Set.of("lapin")));
        assertFalse(Chasse.laisse(b, null), "il reste une poule à prendre");
        assertFalse(Chasse.laisse(List.of(new Bete(1, "lapin", false, true, false, 1)), null), "rien que des petits : rien à laisser");
        // Les espèces de la commande : le cuir vient des vaches et des lapins, les plumes des poules.
        assertEquals(Set.of("vache", "lapin"), Chasse.pour("cuir"));
        assertEquals(Set.of("poule"), Chasse.pour("plumes"));
        assertNull(Chasse.pour("viande"));
    }

    @Test
    void cequOnLuiDemande() {
        assertEquals("lapin", Chasse.dite("Chasse des lapins").id());
        assertEquals("cochon", Chasse.dite("chasse un sanglier").id());
        assertEquals("poule", Chasse.dite("va me chasser deux poulets").id());
        assertNull(Chasse.dite("va chasser"));
        assertTrue(Chasse.monstres("Chasse les zombies"));
        assertTrue(Chasse.monstres("chasse ce monstre"));
        assertFalse(Chasse.monstres("Chasse des lapins"));
        assertEquals("minecraft:rabbit", Chasse.parId("lapin").type());
        assertEquals("lapin", Chasse.parType("minecraft:rabbit").id());
        assertNull(Chasse.parType("minecraft:wolf"), "jamais un loup");
        assertNull(Chasse.parType("minecraft:horse"), "jamais un cheval");
        RepertoireTest.demande("Va chasser", "chasseur", "chasser");
        RepertoireTest.demande("Chasse des lapins", "chasseur", "chasser");
        RepertoireTest.demande("Chasse trois cochons", "chasseur", "chasser");
        RepertoireTest.demande("Fais une battue", "chasseur", "chasser");
        assertEquals(Set.of("chasseur"), Repertoire.metiersConcernes("Va chasser"));
        OrdresTest.missions("Ramène-moi 10 viandes", "chasseur", new Mission("viande", "quantite", 10));
        OrdresTest.missions("Ramène 5 cuirs et des plumes", "chasseur", new Mission("cuir", "quantite", 5), new Mission("plumes", "quantite", 32));
    }
}

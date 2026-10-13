package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Elevage.Bete;
import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 6, l'éleveur : ce qu'il a le droit d'abattre ou de nourrir, ses enclos, et ce que le chef lui demande, lu sans IA. */
class EleveurTest {
    static Bete vache(int id, boolean adulte) { return new Bete(id, "vache", adulte, false, adulte); }

    @Test
    void jamaisLeDernierCoupleNiUnPetitNiUneBeteNommee() {
        // 5 vaches adultes (la 3 a un nom), un veau ; plafond 4 : il en abat une, jamais le veau ni la bête nommée.
        List<Bete> b = new ArrayList<>(List.of(vache(1, true), vache(2, true), new Bete(3, "vache", true, true, true), vache(4, true), vache(5, true), vache(6, false)));
        assertEquals(List.of(1), Elevage.aAbattre(b, "vache", 4, Integer.MAX_VALUE));
        // Sur ordre (« abats-en dix ») : jusqu'au dernier couple (la bête nommée en fait partie), jamais le veau.
        assertEquals(List.of(1, 2, 4), Elevage.aAbattre(b, "vache", Elevage.COUPLE, 10));
        assertEquals(List.of(1, 2), Elevage.aAbattre(b, "vache", Elevage.COUPLE, 2));
        // Un couple et un veau : rien, même sur ordre ; un plafond trop bas ne descend pas sous le couple.
        assertEquals(List.of(), Elevage.aAbattre(List.of(vache(1, true), vache(2, true), vache(3, false)), "vache", Elevage.COUPLE, 5));
        assertEquals(List.of(), Elevage.aAbattre(List.of(vache(1, true), vache(2, true)), "vache", 0, 5));
        assertEquals(2, Elevage.plafond(1));
        // Que des bêtes nommées : rien. Une autre espèce : pas comptée.
        assertEquals(List.of(), Elevage.aAbattre(List.of(new Bete(1, "vache", true, true, true), new Bete(2, "vache", true, true, true),
                new Bete(3, "vache", true, true, true)), "vache", Elevage.COUPLE, 1));
        assertEquals(List.of(), Elevage.aAbattre(List.of(vache(1, true), vache(2, true), new Bete(3, "mouton", true, false, true)), "vache", Elevage.COUPLE, 1));
    }

    @Test
    void nourritParCouplesSousLePlafond() {
        assertEquals(List.of(1, 2), Elevage.aNourrir(List.of(vache(1, true), vache(2, true)), "vache", 12));
        // Une seule bête prête : pas de petit possible. Une seule adulte : pas de couple.
        assertEquals(List.of(), Elevage.aNourrir(List.of(vache(1, true), new Bete(2, "vache", true, false, false)), "vache", 12));
        assertEquals(List.of(), Elevage.aNourrir(List.of(vache(1, true), vache(2, false)), "vache", 12));
        // 5 prêtes : deux couples ; plafond 6 avec 5 bêtes : un couple seulement (un petit de plus).
        List<Bete> cinq = List.of(vache(1, true), vache(2, true), vache(3, true), vache(4, true), vache(5, true));
        assertEquals(4, Elevage.aNourrir(cinq, "vache", 12).size());
        assertEquals(2, Elevage.aNourrir(cinq, "vache", 6).size());
        assertEquals(List.of(), Elevage.aNourrir(cinq, "vache", 5));
    }

    /** Un plan de cases : '#' barrière, 'P' portillon, '.' sol, ' ' (ou hors du plan) sol aussi. */
    static Elevage.Terrain plan(String... lignes) {
        return (x, y, z) -> {
            char c = z >= 0 && z < lignes.length && x >= 0 && x < lignes[z].length() ? lignes[z].charAt(x) : '.';
            return c == '#' ? Elevage.CLOTURE : c == 'P' ? Elevage.PORTILLON : y;
        };
    }

    @Test
    void reconnaitUnEnclosEtSonPortillon() {
        Elevage.Terrain t = plan(
                ".......",
                ".#####.",
                ".#...#.",
                ".P...#.",
                ".#...#.",
                ".#####.",
                ".......");
        Elevage.Enclos e = Elevage.clore(3, 64, 3, t, Elevage.CASES_MAX, new HashSet<>());
        assertNotNull(e);
        assertEquals(9, e.cases().size());
        assertTrue(e.contient(2, 2) && e.contient(4, 4) && !e.contient(1, 3) && !e.contient(5, 3));
        assertEquals(1, e.portillons().size());
        assertArrayEquals(new int[]{1, 64, 3}, e.portillons().get(0));
        assertEquals(2, e.minX());
        assertEquals(4, e.maxZ());
        // Une barrière percée : ce n'est plus un enclos, et les cases vues sont retenues comme ouvertes.
        Set<Long> ouvertes = new HashSet<>();
        assertNull(Elevage.clore(3, 64, 3, plan(
                ".......",
                ".#####.",
                ".#...#.",
                ".P....",
                ".#...#.",
                ".#####."), 200, ouvertes));
        assertTrue(ouvertes.contains(Elevage.cle(3, 3)));
        // La bête voisine, dans le même pré : on ne refait pas le tour.
        assertNull(Elevage.clore(3, 64, 4, (x, y, z) -> { if (x > 6) fail("refait le tour"); return y; }, 200, ouvertes));
    }

    @Test
    void cequOnLuiDemande() {
        assertEquals("vache", Elevage.dite("Abats deux vaches").id());
        assertEquals("cochon", Elevage.dite("tue le porc").id());
        assertEquals("poule", Elevage.dite("abats trois poulets").id());
        assertEquals("mouton", Elevage.dite("Abats une brebis").id());
        assertNull(Elevage.dite("abats les bêtes en trop"));
        assertEquals(2, Fouille.quantite("Abats deux vaches"));
        RepertoireTest.demande("Nourris les bêtes", "eleveur", "nourrir");
        RepertoireTest.demande("Donne à manger aux animaux", "eleveur", "nourrir");
        RepertoireTest.demande("Tonds les moutons", "eleveur", "tondre");
        RepertoireTest.demande("Abats deux vaches", "eleveur", "abattre_betes");
        RepertoireTest.demande("Abats trois cochons", "eleveur", "abattre_betes");
        RepertoireTest.demande("Tue une poule", "eleveur", "abattre_betes");
        assertEquals(Set.of("eleveur"), Repertoire.metiersConcernes("Tonds les moutons"));
        assertEquals(Set.of("eleveur"), Repertoire.metiersConcernes("Abats deux vaches"));
        assertTrue(Ordres.lire("Abats deux vaches", "eleveur").missions.isEmpty(), "ce n'est pas une commande de viande");
        OrdresTest.missions("Ramène des œufs", "eleveur", new Mission("oeufs", "quantite", 32));
        OrdresTest.missions("Ramène-moi 10 cuirs et de la laine", "eleveur", new Mission("cuir", "quantite", 10), new Mission("laine", "quantite", 32));
        OrdresTest.missions("Garde un stock de 3 seaux de lait", "eleveur", new Mission("lait", "stock", 3));
    }
}

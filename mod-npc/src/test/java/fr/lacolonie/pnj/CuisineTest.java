package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 6, le cuisinier : la réserve de pain, le choix de ce qu'il cuit, le combustible (jamais plus que nécessaire). */
class CuisineTest {
    @Test
    void laReserveDePain() {
        assertEquals(6, Cuisine.pains(10, 16, 0, 30), "remonter la réserve à 16");
        assertEquals(2, Cuisine.pains(0, 16, 0, 7), "3 blés par pain : 7 blés, 2 pains");
        assertEquals(0, Cuisine.pains(20, 16, 0, 30), "réserve pleine : rien");
        assertEquals(5, Cuisine.pains(20, 16, 5, 30), "une commande de 5 pains à livrer passe la réserve");
        assertEquals(0, Cuisine.pains(0, 16, 0, 2), "pas assez de blé");
    }

    @Test
    void leCombustibleJusteNecessaire() {
        assertEquals(1, Cuisine.combustible(8, 1600), "un charbon cuit 8 objets");
        assertEquals(2, Cuisine.combustible(9, 1600));
        assertEquals(2, Cuisine.combustible(3, 300), "une bûche cuit un objet et demi");
        assertEquals(8, Cuisine.cuisent(1, 1600));
        // Charbon de bois d'abord, même s'il n'en a pas assez pour tout : le bois reste le dernier recours.
        Cuisine.Chauffe c = Cuisine.chauffe(12, List.of(new Cuisine.Feu(1, 1600), new Cuisine.Feu(10, 300)));
        assertEquals(new Cuisine.Chauffe(0, 1, 8), c);
        // Sans charbon : des bûches, juste ce qu'il faut (12 objets, 8 bûches).
        assertEquals(new Cuisine.Chauffe(0, 8, 12), Cuisine.chauffe(12, List.of(new Cuisine.Feu(10, 300))));
        // Trois objets avec du charbon : un seul charbon, pas la pile.
        assertEquals(new Cuisine.Chauffe(0, 1, 3), Cuisine.chauffe(3, List.of(new Cuisine.Feu(64, 1600))));
        assertNull(Cuisine.chauffe(5, List.of()), "rien à brûler");
        assertNull(Cuisine.chauffe(0, List.of(new Cuisine.Feu(5, 1600))), "rien à cuire");
    }

    @Test
    void ceQuIlCuit() {
        Map<String, Integer> crus = new LinkedHashMap<>();
        crus.put("minecraft:cod", 3);
        crus.put("minecraft:beef", 70);
        // Le pain d'abord : pas besoin de four.
        assertEquals(Cuisine.Quoi.PAIN, Cuisine.choisir(4, false, crus, false, false).quoi());
        // Le cru le plus abondant, une pile au plus par fournée.
        Cuisine.Choix c = Cuisine.choisir(0, false, crus, true, true);
        assertEquals(Cuisine.Quoi.CUIRE, c.quoi());
        assertEquals("minecraft:beef", c.cru());
        assertEquals(64, c.nombre());
        assertNull(c.lettre());
        assertEquals("sans_ble", Cuisine.choisir(0, true, crus, true, true).lettre(), "il cuit, et écrit qu'il manque de blé");
        assertEquals("sans_four", Cuisine.choisir(0, false, crus, false, true).lettre());
        assertEquals("sans_combustible", Cuisine.choisir(0, false, crus, true, false).lettre());
        assertEquals("rien_a_cuire", Cuisine.choisir(0, false, Map.of(), true, true).lettre());
        assertEquals("sans_ble", Cuisine.choisir(0, true, Map.of(), true, true).lettre());
        // Les semences : il en laisse 16 au fermier.
        assertEquals(4, Cuisine.aCuire(20, true));
        assertEquals(0, Cuisine.aCuire(10, true));
        assertEquals(10, Cuisine.aCuire(10, false));
        assertEquals("poisson", Cuisine.manque("minecraft:salmon"));
        assertEquals("gibier", Cuisine.manque(""));
    }

    @Test
    void sesOrdresEtSonNom() {
        RepertoireTest.demande("Cuis la viande", "cuisinier", "cuire");
        RepertoireTest.demande("Fais griller le poisson", "cuisinier", "cuire");
        RepertoireTest.demande("Va cuisiner", "cuisinier", "cuisiner");
        RepertoireTest.demande("Fais à manger", "cuisinier", "cuisiner");
        RepertoireTest.demande("Fais du pain", "cuisinier", "pain");
        RepertoireTest.demande("Cuis la viande", "mineur", "-");
        assertEquals(List.of(new Ordres.Mission("pain", "quantite", 10)), Ordres.lire("Ramène 10 pains", "cuisinier").missions);
        assertEquals(List.of(new Ordres.Mission("viande", "quantite", 5)), Ordres.lire("Rapporte 5 steaks", "cuisinier").missions);
        for (String n : List.of("Le cuisinier, viens", "La cuisinière, viens", "Le boulanger, viens", "La boulangère, viens"))
            assertEquals(java.util.Set.of("cuisinier"), Ordres.adresse(n, List.of("Jean"), List.of("cuisinier")).metiersUn(), n);
    }
}

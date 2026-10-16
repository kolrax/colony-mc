package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 11 : prix, marchandage, « 1 pièce », paiement exact entre deux bourses, et les répliques d'economie.txt. */
class EconomieTest {
    static final int[] VALEURS = {1, 8, 16, 64};

    @Test
    void prixDeLaTableSinonSelonLaNourriture() {
        Map<String, Integer> table = Map.of("minecraft:bread", 1, "minecraft:cooked_beef", 3);
        assertEquals(1, Economie.prix(table, "minecraft:bread", 5));
        assertEquals(3, Economie.prix(table, "minecraft:cooked_beef", 8));
        assertEquals(2, Economie.prix(table, "minecraft:cooked_rabbit", 5)); // (5 + 2) / 3
        assertEquals(1, Economie.prix(table, "minecraft:sweet_berries", 2));
        assertEquals(1, Economie.prix(null, "x", 0)); // jamais gratuit
    }

    @Test
    void marchandageSelonLeCaractere() {
        assertEquals(6, Economie.marchander("fourbe", 8, () -> 0.1)); // un quart de rabais
        assertEquals(1, Economie.marchander("fourbe", 2, () -> 0.1)); // une pièce au moins de rabais, jamais sous une pièce
        assertEquals(1, Economie.marchander("fourbe", 1, () -> 0.0)); // une pièce ne se marchande pas
        assertEquals(8, Economie.marchander("peureux", 8, () -> 0.1)); // le peureux n'ose presque jamais
        assertEquals(8, Economie.marchander("fourbe", 8, () -> 0.9)); // pas cette fois
        assertTrue(Economie.envieDeMarchander("fourbe") > Economie.envieDeMarchander("calme"));
    }

    @Test
    void unePieceAuSingulier() {
        assertEquals("Ça fera 1 pièce. Tiens.", Economie.accorder("Ça fera 1 pièces. Tiens."));
        assertEquals("Ça fera 11 pièces.", Economie.accorder("Ça fera 11 pièces."));
        assertEquals("21 pièces, et 1 pièce.", Economie.accorder("21 pièces, et 1 pièces."));
        assertNull(Economie.accorder(null));
    }

    @Test
    void paiementExactSansPerteNiDoublon() {
        int[] acheteur = {0, 2, 0, 0}, vendeur = {3, 0, 0, 0}; // 16 contre 3
        Economie.Paiement p = Economie.payer(VALEURS, acheteur, vendeur, 3);
        assertNotNull(p);
        assertEquals(13, Monnaie.total(VALEURS, p.acheteur()));
        assertEquals(6, Monnaie.total(VALEURS, p.vendeur()));
        assertEquals(Monnaie.total(VALEURS, acheteur) + Monnaie.total(VALEURS, vendeur), Monnaie.total(VALEURS, p.acheteur()) + Monnaie.total(VALEURS, p.vendeur()));
        assertArrayEquals(new int[]{0, 2, 0, 0}, acheteur, "rien ne bouge avant le paiement");
        for (int m = 1; m <= 40; m++) {
            Economie.Paiement q = Economie.payer(VALEURS, new int[]{3, 1, 1, 0}, new int[4], m); // 27
            if (m > 27) assertNull(q, "pas assez pour " + m);
            else assertEquals(27, Monnaie.total(VALEURS, q.acheteur()) + Monnaie.total(VALEURS, q.vendeur()), "rien ne se perd à " + m);
        }
        assertNull(Economie.payer(VALEURS, new int[4], new int[4], 1), "bourse vide");
        assertNull(Economie.payer(new int[]{8}, new int[]{1}, new int[1], 3), "sans petite pièce, 3 ne passe pas exactement");
    }

    @Test
    void lesRepliquesSeLisent() {
        var banque = Vie.lire(Repertoire.lignes("/economie.txt"));
        for (String cle : List.of("achat_demande", "achat_accepte", "achat_marchande", "achat_pas_assez", "vente_rupture", "chef_achat_info", "pauvre_plainte"))
            assertTrue(banque.containsKey(cle) && Vie.TOUTES.containsKey(cle), cle);
        String x = Economie.accorder(Vie.tirer("achat_accepte", "calme", Map.of("chef", "Kolrax", "nom", "Mahaut", "objet", "un peu de pain", "prix", "1"), () -> 0.0));
        assertFalse(x.contains("1 pièces"), x);
    }
}

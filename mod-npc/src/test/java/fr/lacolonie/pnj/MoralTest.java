package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Le moral au fil des jours (lot 5, cahier 5.2 et 5.3), la part pure : causes, bornes, lenteur, lettres et démission. */
class MoralTest {
    /** Un ouvrier bien traité : payé, repas variés, lit sous un toit, rien de grave, beau temps. */
    static Moral.Etat bien() {
        return new Moral.Etat(0, true, List.of("bread", "carrot", "apple"), true, true, false, false, -1, false, false, 0, true, false, 1, 0);
    }

    static Moral.Etat avec(java.util.function.UnaryOperator<Object[]> f) {
        Moral.Etat b = bien();
        Object[] v = {b.impayes(), b.payeRecemment(), b.repas(), b.lit(), b.toit(), b.blesse(), b.blesseVu(), b.joursDeuil(), b.pluie(),
                b.orage(), b.bilansPluie(), b.beauTemps(), b.coupChef(), b.ouvriers(), b.depot()};
        v = f.apply(v);
        return new Moral.Etat((int) v[0], (boolean) v[1], (List<String>) v[2], (boolean) v[3], (boolean) v[4], (boolean) v[5], (boolean) v[6],
                (int) v[7], (boolean) v[8], (boolean) v[9], (int) v[10], (boolean) v[11], (boolean) v[12], (int) v[13], (int) v[14]);
    }

    static Moral.Etat un(int i, Object x) { return avec(v -> { v[i] = x; return v; }); }

    @Test
    void bienTraiteMonteUnPeu() {
        Moral.Bilan b = Moral.bilan(bien());
        assertTrue(b.total() > 0 && b.total() <= Moral.HAUSSE_MAX, "bien traité : un peu mieux (" + b + ")");
        assertNull(b.pire());
    }

    @Test
    void chaqueCauseCompte() {
        assertTrue(Moral.bilan(un(0, 2)).de(Moral.Cause.PAIE) < 0, "impayés");
        assertTrue(Moral.bilan(un(0, 3)).de(Moral.Cause.PAIE) < Moral.bilan(un(0, 1)).de(Moral.Cause.PAIE), "plus d'impayés, pire");
        assertTrue(Moral.bilan(bien()).de(Moral.Cause.PAIE) > 0, "payé à l'heure");
        assertTrue(Moral.bilan(un(2, List.of("bread", "bread", "bread"))).de(Moral.Cause.REPAS) < 0, "toujours du pain");
        assertTrue(Moral.bilan(bien()).de(Moral.Cause.REPAS) > 0, "repas variés");
        assertEquals(0, Moral.bilan(un(2, List.of("bread", "bread"))).de(Moral.Cause.REPAS), "deux repas : trop tôt pour juger");
        assertTrue(Moral.bilan(un(3, false)).de(Moral.Cause.LIT) < 0, "pas de lit");
        assertTrue(Moral.bilan(un(4, false)).de(Moral.Cause.TOIT) < 0, "lit à la belle étoile");
        assertTrue(Moral.bilan(un(5, true)).de(Moral.Cause.DANGER) < 0, "blessé");
        assertTrue(Moral.bilan(un(6, true)).de(Moral.Cause.DANGER) < 0, "un compagnon à terre");
        assertTrue(Moral.bilan(avec(v -> { v[8] = true; v[10] = 1; v[11] = false; return v; })).de(Moral.Cause.METEO) == 0, "une averse : rien");
        assertTrue(Moral.bilan(avec(v -> { v[8] = true; v[10] = 2; v[11] = false; return v; })).de(Moral.Cause.METEO) < 0, "longue pluie");
        assertTrue(Moral.bilan(avec(v -> { v[8] = true; v[9] = true; v[11] = false; return v; })).de(Moral.Cause.METEO) < -1, "orage");
        assertTrue(Moral.bilan(bien()).de(Moral.Cause.METEO) > 0, "beau temps");
        assertTrue(Moral.bilan(un(12, true)).de(Moral.Cause.CHEF) < 0, "frappé par son chef");
        assertEquals(2, Moral.bilan(avec(v -> { v[13] = 5; v[14] = 300; return v; })).de(Moral.Cause.COLONIE), "colonie qui prospère");
    }

    @Test
    void deuilQuiSAttenue() {
        int j0 = Moral.bilan(un(7, 0)).de(Moral.Cause.DEUIL), j1 = Moral.bilan(un(7, 1)).de(Moral.Cause.DEUIL),
                j2 = Moral.bilan(un(7, 2)).de(Moral.Cause.DEUIL), j5 = Moral.bilan(un(7, 5)).de(Moral.Cause.DEUIL);
        assertTrue(j0 < j1 && j1 < j2 && j2 < 0 && j5 == 0, j0 + " " + j1 + " " + j2 + " " + j5);
        assertEquals(Moral.Cause.DEUIL, Moral.bilan(un(7, 0)).pire());
        assertTrue(Moral.DEUIL_COUP <= -10, "la mort d'un compagnon : un gros coup sur le moment");
        // Trois morts le même jour : −10, −5, puis plus rien (au plus −15 ce jour-là).
        assertEquals(-10, Moral.deuilCoup(0));
        assertEquals(-5, Moral.deuilCoup(-10));
        assertEquals(0, Moral.deuilCoup(-15));
    }

    @Test
    void borneEtPireCause() {
        Moral.Etat tout = new Moral.Etat(3, false, List.of("bread", "bread", "bread"), false, false, true, true, 0, true, true, 5, false, true, 0, 0);
        Moral.Bilan b = Moral.bilan(tout);
        assertEquals(-Moral.BAISSE_MAX, b.total(), "borné");
        assertEquals(Moral.Cause.PAIE, b.pire(), "la paie pèse le plus");
        assertEquals(Moral.Cause.REPAS, Moral.bilan(un(2, List.of("bread", "bread", "bread", "bread"))).pire());
    }

    @Test
    void derniersRepasRetenus() {
        String l = "";
        for (String a : List.of("a", "b", "c", "d", "e", "f", "g")) l = Moral.retenir(l, a);
        assertEquals(List.of("c", "d", "e", "f", "g"), Moral.lire(l));
        assertEquals(List.of(), Moral.lire(""));
    }

    @Test
    void reposSeulementApresUnBilanCorrect() {
        assertEquals(41, Moral.repos(40, 0));
        assertEquals(41, Moral.repos(40, -2), "un petit tracas s'efface avec le temps");
        assertEquals(40, Moral.repos(40, -3), "un mauvais bilan : il ne remonte pas tout seul");
        assertEquals(79, Moral.repos(80, -3), "au-dessus de 60, il redescend toujours");
    }

    @Test
    void lenteurSelonLeMoral() {
        assertEquals(Moral.Effet.AUCUN, Moral.effet(50, 30, 15));
        assertEquals(Moral.Effet.LENT, Moral.effet(20, 30, 15));
        assertEquals(Moral.Effet.TRES_LENT, Moral.effet(10, 30, 15));
        assertEquals(0.0, Moral.lenteur(Moral.Effet.AUCUN, 0.2));
        assertEquals(0.1, Moral.lenteur(Moral.Effet.LENT, 0.2), 1e-9);
        assertEquals(0.2, Moral.lenteur(Moral.Effet.TRES_LENT, 0.2), 1e-9);
    }

    @Test
    void demissionSeulementApresDeuxAvertissements() {
        // Réglages par défaut : lettre sous 30, une par jour (4 bilans), départ après 12 bilans très bas.
        assertEquals(Moral.Suite.RIEN, Moral.suite(40, 0, 0, 0, 30, 4, 12), "moral correct : rien");
        assertEquals(Moral.Suite.AVERTIT, Moral.suite(25, 0, 0, 0, 30, 4, 12), "première lettre");
        assertEquals(Moral.Suite.RIEN, Moral.suite(25, 1, 2, 2, 30, 4, 12), "pas deux lettres le même jour");
        assertEquals(Moral.Suite.AVERTIT, Moral.suite(25, 1, 4, 4, 30, 4, 12), "deuxième lettre le lendemain");
        assertNotEquals(Moral.Suite.DEMISSIONNE, Moral.suite(5, 1, 1, 50, 30, 4, 12), "une seule lettre : il ne part pas, même au plus bas");
        assertNotEquals(Moral.Suite.DEMISSIONNE, Moral.suite(5, 2, 1, 11, 30, 4, 12), "deux lettres, pas encore assez de jours");
        assertEquals(Moral.Suite.DEMISSIONNE, Moral.suite(5, 2, 1, 12, 30, 4, 12), "deux lettres et trois jours au plus bas");
        assertNotEquals(Moral.Suite.DEMISSIONNE, Moral.suite(5, 2, 1, 1000, 30, 4, Integer.MAX_VALUE), "réglé à jamais");
    }

    /** Bien traité, des semaines durant (bilan + retour au repos) : il ne descend jamais sous le seuil de lettre. */
    @Test
    void bienTraiteNePartJamais() {
        int moral = 40;
        for (int i = 0; i < 400; i++) {
            Moral.Bilan b = Moral.bilan(un(13, 1));
            moral = Obeissance.moral(moral, b.total());
            for (int h = 0; h < 6; h++) moral = Moral.repos(moral, b.total());
            assertTrue(moral >= 30);
        }
        assertTrue(moral >= 60, "bien traité, il finit content (" + moral + ")");
    }
}

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
    void deuilPondereSansDependreDeLOrdre() {
        // Un inconnu (poids 1) et un ami (poids 2) le même jour, dans les deux ordres : la même perte en tout.
        int a = Moral.deuilDuJour(Moral.DEUIL_COUP, 1), ab = Moral.deuilDuJour(Moral.DEUIL_COUP * 3, 2);
        int b = Moral.deuilDuJour(Moral.DEUIL_COUP * 2, 2), ba = Moral.deuilDuJour(Moral.DEUIL_COUP * 3, 2);
        assertEquals(ab, ba, "l'ordre des morts ne change rien");
        assertEquals(-30, ab, "plafond du jour × le plus grand poids");
        assertTrue(b < a, "un ami pèse plus qu'un inconnu");
        assertEquals(-30, Moral.deuilDuJour(Moral.DEUIL_COUP * 3, 3), "l'être aimé : le triple");
        assertEquals(-45, Moral.deuilDuJour(Moral.DEUIL_COUP * 8, 3), "plafonné à 15 × 3");
        assertEquals(Moral.deuilCoup(0) + Moral.deuilCoup(-10), Moral.deuilDuJour(Moral.DEUIL_COUP * 2, 1), "sans liens : comme avant");
    }

    @Test
    void lesAmitiesComptent() {
        Moral.Etat b = bien();
        Moral.Etat ami = new Moral.Etat(b.impayes(), b.payeRecemment(), b.repas(), b.lit(), b.toit(), b.blesse(), b.blesseVu(), b.joursDeuil(), b.pluie(),
                b.orage(), b.bilansPluie(), b.beauTemps(), b.coupChef(), b.ouvriers(), b.depot(), 2);
        Moral.Etat ennemi = new Moral.Etat(0, false, List.of(), true, true, false, false, -1, false, false, 0, false, false, 1, 0, -1);
        assertEquals(0, Moral.bilan(b).de(Moral.Cause.AMITIES), "sans lien : rien");
        assertEquals(2, Moral.bilan(ami).de(Moral.Cause.AMITIES), "en couple : +2");
        assertEquals(Moral.Cause.AMITIES, Moral.bilan(ennemi).pire(), "un ennemi : la seule peine");
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

    // --- Méfaits (2e partie) ----------------------------------------------------------------------------------

    /** Au plus bas depuis 2 jours, deux lettres, un compagnon au plus bas, chef connecté et présent ; tirages favorables. */
    static Moral.Rancoeur rancoeur(java.util.function.UnaryOperator<Object[]> f) {
        Object[] v = {5, 15, 2, 8, 4, "colerique", 1, 0, 0, true, true, true, 0, 4, 25};
        v = f.apply(v);
        return new Moral.Rancoeur((int) v[0], (int) v[1], (int) v[2], (int) v[3], (int) v[4], (String) v[5], (int) v[6], (int) v[7], (int) v[8],
                (boolean) v[9], (boolean) v[10], (boolean) v[11], (int) v[12], (int) v[13], (int) v[14]);
    }

    static Moral.Rancoeur r(int i, Object x) { return rancoeur(v -> { v[i] = x; return v; }); }

    @Test
    void jamaisDeMefaitAuDessusDuSeuil() {
        for (int moral = 15; moral <= 100; moral += 5)
            for (String c : List.of("calme", "peureux", "fourbe", "colerique"))
                for (double tirage : new double[]{0, 0.3, 0.99})
                    for (int menaces : new int[]{0, 3}) {
                        int m = moral, me = menaces;
                        Moral.Rancoeur x = rancoeur(v -> { v[0] = m; v[5] = c; v[7] = me; v[8] = 5; return v; });
                        assertEquals(Moral.Mefait.RIEN, Moral.mefait(x, tirage, tirage), moral + " " + c);
                    }
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(r(2, 0), 0, 0), "sans lettre de plainte d'abord : rien");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(r(3, 1), 0, 0), "un seul bilan au plus bas : rien encore");
        // Bien traité des semaines durant (bienTraiteNePartJamais), son moral reste ≥ 30 : au-dessus du seuil (15 par défaut), jamais de méfait.
    }

    @Test
    void revolteSeulementApresTroisAvertissements() {
        assertEquals(Moral.Mefait.MENACE, Moral.mefait(r(7, 0), 0.99, 0.99), "d'abord un avertissement");
        assertEquals(Moral.Mefait.MENACE, Moral.mefait(rancoeur(v -> { v[7] = 1; v[8] = 2; return v; }), 0.99, 0.99), "le deuxième, deux bilans plus tard");
        assertNotEquals(Moral.Mefait.MENACE, Moral.mefait(rancoeur(v -> { v[7] = 1; v[8] = 1; return v; }), 0.99, 0.99), "pas deux de suite");
        assertNotEquals(Moral.Mefait.REVOLTE, Moral.mefait(rancoeur(v -> { v[7] = 2; v[8] = 5; return v; }), 0.99, 0.99), "deux avertissements : pas encore");
        assertEquals(Moral.Mefait.REVOLTE, Moral.mefait(rancoeur(v -> { v[7] = 3; v[8] = 1; return v; }), 0.99, 0.99), "après le troisième, le chef présent");
        assertNotEquals(Moral.Mefait.REVOLTE, Moral.mefait(rancoeur(v -> { v[7] = 3; v[10] = false; return v; }), 0.99, 0.99), "le chef absent : pas de révolte");
        assertNotEquals(Moral.Mefait.MENACE, Moral.mefait(rancoeur(v -> { v[9] = false; v[10] = false; return v; }), 0.99, 0.99), "chef déconnecté : pas d'avertissement");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[7] = 3; v[11] = false; return v; }), 0.99, 0.99), "réglage coupé : jamais");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[2] = 1; return v; }), 0.99, 0.99), "une seule lettre de plainte : pas encore");
        // Seul au plus bas : seulement après deux jours ; un peureux, jamais seul.
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 7; return v; }), 0.99, 0.99), "seul, pas encore deux jours");
        assertEquals(Moral.Mefait.MENACE, Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 8; return v; }), 0.99, 0.99), "seul, deux jours au plus bas");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[6] = 0; v[5] = "peureux"; v[3] = 50; return v; }), 0.99, 0.99), "un peureux seul");
        assertEquals(Moral.Mefait.MENACE, Moral.mefait(rancoeur(v -> { v[5] = "peureux"; return v; }), 0.99, 0.99), "un peureux avec d'autres");
    }

    @Test
    void volEtSabotageSelonLeCaractere() {
        Moral.Rancoeur seul = rancoeur(v -> { v[6] = 0; v[3] = 2; return v; }); // pas de révolte possible
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(seul, 0.99, 0), "le plus souvent, rien");
        assertEquals(Moral.Mefait.VOL, Moral.mefait(seul, 0, 0.1), "un vol");
        assertEquals(Moral.Mefait.SABOTAGE, Moral.mefait(seul, 0, 0.9), "un sabotage");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 2; v[12] = 4; return v; }), 0, 0.1), "quota du jour atteint");
        assertEquals(Moral.Mefait.RIEN, Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 2; v[14] = 0; return v; }), 0, 0.1), "réglé à 0 : jamais");
        assertTrue(Moral.penchant("fourbe") > Moral.penchant("calme") && Moral.partVol("fourbe") > Moral.partVol("colerique"));
        // Sur 1000 bilans au plus bas, un fourbe fait bien plus de méfaits qu'un calme, mais pas à chaque fois.
        java.util.Random rnd = new java.util.Random(1);
        int fourbe = 0, calme = 0;
        for (int i = 0; i < 1000; i++) {
            if (Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 2; v[5] = "fourbe"; return v; }), rnd.nextDouble(), rnd.nextDouble()) != Moral.Mefait.RIEN) fourbe++;
            if (Moral.mefait(rancoeur(v -> { v[6] = 0; v[3] = 2; v[5] = "calme"; return v; }), rnd.nextDouble(), rnd.nextDouble()) != Moral.Mefait.RIEN) calme++;
        }
        assertTrue(fourbe > 2 * calme && fourbe < 600 && calme > 50, fourbe + " contre " + calme);
    }

    @Test
    void fuiteEtButin() {
        assertTrue(Moral.fuit("fourbe", 0.5) && !Moral.fuit("calme", 0.5) && !Moral.fuit("calme", 0), "le calme ne fuit jamais : il démissionne");
        assertTrue(Moral.precieux("minecraft:diamond", true) && Moral.precieux("minecraft:gold_ingot", true) && Moral.precieux("numismatics:spur", true));
        assertTrue(Moral.precieux("minecraft:iron_pickaxe", false), "un outil ne s'empile pas : il ne le prend pas");
        assertFalse(Moral.precieux("minecraft:bread", true));
        assertEquals(3, Moral.prise(64, 4));
        assertEquals(1, Moral.prise(1, 4));
        assertEquals(2, Moral.prise(64, 2));
        assertEquals(0, Moral.prise(64, 0));
    }

    @Test
    void finDeLaRevolte() {
        assertFalse(Moral.finRevolte(5, 30, 2, 2, true), "toujours en colère");
        assertTrue(Moral.finRevolte(5, 30, 2, 0, true), "payé ce qu'il devait");
        assertFalse(Moral.finRevolte(5, 30, 0, 0, true), "rien ne lui était dû : il faut que le moral remonte");
        assertTrue(Moral.finRevolte(30, 30, 0, 0, true), "le moral remonté");
        assertTrue(Moral.finRevolte(5, 30, 2, 2, false), "le chef parti loin");
    }
    /** Le diamant gardé en cachette (cahier 14.3) selon le caractère et le moral ; ses aveux ; les excuses rendent la moitié. */
    @Test
    void diamantSelonLeCaractere() {
        assertEquals(0, Moral.gardeDiamant("calme", 5, 100), "le calme, jamais");
        assertEquals(0, Moral.gardeDiamant("peureux", 50, 100), "le peureux, pas au moral ordinaire");
        assertEquals(0, Moral.gardeDiamant("colerique", 70, 100), "le colérique content, non");
        assertTrue(Moral.gardeDiamant("colerique", 20, 50) > 0, "le colérique au moral bas, parfois");
        assertEquals(0.5, Moral.gardeDiamant("fourbe", 70, 50), 1e-9, "le fourbe, souvent");
        assertTrue(Moral.gardeDiamant("fourbe", 20, 50) > Moral.gardeDiamant("fourbe", 70, 50), "plus encore au moral bas");
        assertEquals(1, Moral.gardeDiamant("fourbe", 20, 100), 1e-9);
        assertEquals(0, Moral.gardeDiamant("fourbe", 20, 0), "réglé à 0 : jamais");
        assertEquals(1, Moral.avoueDiamant("peureux", 10), 1e-9, "le peureux avoue toujours");
        assertTrue(Moral.avoueDiamant("fourbe", 30) < Moral.avoueDiamant("calme", 30));
        assertEquals(8, Moral.pardon(15));
        assertEquals(3, Moral.pardon(5));
        assertEquals(0, Moral.pardon(0));
    }
}

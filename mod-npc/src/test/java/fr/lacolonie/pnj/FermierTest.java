package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 6, le fermier : ce que le chef lui demande, lu sans IA (mesures du champ, culture, graine), et ses zones de récolte. */
class FermierTest {
    @Test
    void lesMesuresDuChamp() {
        assertEquals("9 9 1 - face", Cultures.mesures("Plante un champ de blé ici").texte());
        assertEquals("9 9 1 - face", Cultures.mesures("Plante un champ de blé ici, 9 sur 9").texte());
        assertEquals("5 5 1 - face", Cultures.mesures("Plante un champ de blé ici, 5 sur 5").texte());
        assertEquals("12 6 1 - face", Cultures.mesures("Fais un champ de carottes de 12 sur 6").texte());
        assertEquals("32 32 1 - face", Cultures.mesures("Laboure ici 100 sur 100").texte()); // borné
        // 5 sur 5 : 25 cases, en serpentin autour du centre.
        List<int[]> c = Cultures.cases(new Fouille.Plan(5, 5, 1, "", false));
        assertEquals(25, c.size());
        assertArrayEquals(new int[]{-2, -2}, c.get(0));
        assertArrayEquals(new int[]{-2, 2}, c.get(4));
        assertArrayEquals(new int[]{-1, 2}, c.get(5));
        assertTrue(c.stream().anyMatch(k -> k[0] == 0 && k[1] == 0), "le centre en fait partie");
        assertTrue(Cultures.dans(new Fouille.Plan(5, 5, 1, "", false), 2, -2));
        assertFalse(Cultures.dans(new Fouille.Plan(5, 5, 1, "", false), 3, 0));
        assertEquals("7 7 1 - face", Cultures.agrandi(new Fouille.Plan(5, 5, 1, "", false)).texte());
        assertEquals(32, Cultures.agrandi(new Fouille.Plan(32, 31, 1, "", false)).longueur());
    }

    @Test
    void laCultureEtSaGraine() {
        assertEquals("ble", Cultures.dite("Plante un champ ici").id());
        assertEquals("ble", Cultures.dite("Plante un champ de blé ici, 9 sur 9").id());
        assertEquals("carotte", Cultures.dite("Fais un champ de carottes").id());
        assertEquals("pomme_de_terre", Cultures.dite("Plante des pommes de terre ici").id());
        assertEquals("pomme_de_terre", Cultures.dite("plante des patates").id());
        assertEquals("betterave", Cultures.dite("Sème des betteraves").id());
        assertEquals("minecraft:wheat_seeds", Cultures.parBloc("minecraft:wheat").graine());
        assertEquals("minecraft:carrot", Cultures.parBloc("minecraft:carrots").graine());
        assertEquals("minecraft:potato", Cultures.parBloc("minecraft:potatoes").graine());
        assertEquals("minecraft:beetroot_seeds", Cultures.parBloc("minecraft:beetroots").graine());
        assertNull(Cultures.parBloc("minecraft:oak_sapling"));
    }

    @Test
    void lesZonesDuTour() {
        List<int[]> z = Cultures.zones(Travailleur.ESPACE_DE_VIE);
        assertEquals(49, z.size(), "7 zones de 16 sur 7 couvrent les 101 blocs de l'espace de vie");
        assertEquals(25, Cultures.zones(32).size(), "seul, à 32 blocs du poste : 5 zones sur 5");
        assertTrue(z.get(0)[0] <= 0 && z.get(0)[0] + Cultures.ZONE > 0 && z.get(0)[1] <= 0 && z.get(0)[1] + Cultures.ZONE > 0, "la première zone est celle du poste");
        assertTrue(z.stream().anyMatch(k -> k[0] <= -50 && k[1] <= -50), "le coin de l'espace de vie est couvert");
        assertTrue(z.stream().anyMatch(k -> k[0] + Cultures.ZONE > 50 && k[1] + Cultures.ZONE > 50), "l'autre coin aussi");
        for (int i = 1; i < z.size(); i++) {
            double a = Math.hypot(z.get(i - 1)[0] + 7.5, z.get(i - 1)[1] + 7.5), b = Math.hypot(z.get(i)[0] + 7.5, z.get(i)[1] + 7.5);
            assertTrue(a <= b + 1e-9, "de la plus proche à la plus lointaine");
        }
    }

    @Test
    void cequOnLuiDemande() {
        OrdresTest.missions("Ramène-moi 32 carottes", "fermier", new Mission("carotte", "quantite", 32));
        OrdresTest.missions("Va chercher du blé", "fermier", new Mission("ble", "quantite", 32));
        RepertoireTest.demande("Plante un champ de blé ici, 9 sur 9", "fermier", "champ");
        RepertoireTest.demande("Laboure ici", "fermier", "champ");
        RepertoireTest.demande("Récolte !", "fermier", "recolter");
        RepertoireTest.demande("Fais la moisson", "fermier", "recolter");
        RepertoireTest.demande("Fais du pain", "fermier", "pain");
        RepertoireTest.demande("Oublie ce champ", "fermier", "oublier_champ");
        RepertoireTest.demande("Arrête de cultiver ici", "fermier", "oublier_champ");
        RepertoireTest.demande("Laisse ce champ en friche", "fermier", "oublier_champ");
        assertEquals(java.util.Set.of("fermier", "cuisinier"), Repertoire.metiersConcernes("Fais du pain")); // le cuisinier aussi (lot 6)
    }

    /** Un champ de 9 sur 9 autour de (0, 0), le niveau du sol de chaque rang donné par {@code niveau}. */
    static List<Cultures.Point> champ(java.util.function.IntUnaryOperator niveau) {
        List<Cultures.Point> r = new java.util.ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) r.add(new Cultures.Point(x, niveau.applyAsInt(z), z));
        return r;
    }

    /** L'eau des mares arrose toutes ces cases (sauf les mares elles-mêmes). */
    static void toutArrose(List<Cultures.Point> cases, List<Cultures.Point> mares) {
        for (Cultures.Point c : cases)
            assertTrue(mares.contains(c) || mares.stream().anyMatch(m -> Cultures.arrose(m, c)), "case sèche : " + c + " (mares " + mares + ")");
    }

    /** Les mares, toutes les places hors tampon ({@code sures}), aucune au tampon du poste. */
    static List<Cultures.Point> mares(List<Cultures.Point> seches, List<Cultures.Point> sures, int champ) {
        return Cultures.mares(seches, sures, List.of(), p -> true, champ, 0, 0);
    }

    @Test
    void lesMaresDuChamp() {
        // Plat : une mare, au milieu.
        List<Cultures.Point> plat = champ(z -> 0);
        assertEquals(List.of(new Cultures.Point(0, 0, 0)), mares(plat, plat, 81));
        // L'eau arrose son niveau et celui d'en dessous, pas celui d'au-dessus.
        assertTrue(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(4, 4, -4)));
        assertFalse(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(0, 6, 1)));
        assertFalse(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(5, 5, 0)));
        // En escalier sur trois niveaux : une mare de plus, au niveau du haut ; tout est arrosé, une mare par niveau au plus.
        List<Cultures.Point> escalier = champ(z -> z <= -2 ? -1 : z >= 2 ? 1 : 0);
        List<Cultures.Point> m = mares(escalier, escalier, 81);
        assertEquals(2, m.size(), m.toString());
        assertEquals(m.size(), m.stream().map(Cultures.Point::y).distinct().count());
        toutArrose(escalier, m);
        // Déjà arrosé : rien à creuser. Un coin sec (au bord d'une rivière : 7 cases) : il ne sacrifie pas une case pour lui.
        assertTrue(mares(List.of(), plat, 81).isEmpty());
        List<Cultures.Point> coin = plat.stream().filter(p -> p.x() >= 2 && (p.z() >= 3 || p.x() == 4 && p.z() == 2)).toList();
        assertEquals(7, coin.size());
        assertTrue(mares(coin, plat, 81).isEmpty());
        // Au plus une mare pour 20 cases : 9 niveaux (un par rang), 4 mares au plus pour 81 cases.
        assertEquals(4, mares(champ(z -> z), champ(z -> z), 81).size());
        // Seul le tampon du poste peut recevoir l'eau : il y creuse, faute d'autre place (vérifiée une fois).
        int[] verifs = {0};
        assertEquals(1, Cultures.mares(plat, List.of(), plat, p -> ++verifs[0] > 0, 81, 0, 0).size());
        assertEquals(1, verifs[0]);
        // Le tampon du poste interdit partout (une autre construction) : 8 vérifications au plus, pas de mare.
        verifs[0] = 0;
        assertTrue(Cultures.mares(plat, List.of(), plat, p -> ++verifs[0] < 0, 81, 0, 0).isEmpty());
        assertTrue(verifs[0] <= Cultures.VERIFS_POSTE, verifs[0] + " vérifications");
        // Une place hors tampon d'abord, même si celle du tampon arroserait plus ; le tampon du poste sert le reste.
        List<Cultures.Point> bord = plat.stream().filter(p -> p.x() == -4).toList(), reste = plat.stream().filter(p -> p.x() != -4).toList();
        List<Cultures.Point> b = Cultures.mares(plat, bord, reste, p -> true, 81, 0, 0);
        assertEquals(-4, b.get(0).x());
        toutArrose(plat, b);
        // Un champ de 18 de long : deux carrés de 9, une mare chacun, au même niveau, jamais côte à côte.
        List<Cultures.Point> long_ = new java.util.ArrayList<>();
        for (int x = -4; x <= 13; x++) for (int z = -4; z <= 4; z++) long_.add(new Cultures.Point(x, 0, z));
        List<Cultures.Point> deux = mares(long_, long_, long_.size());
        assertEquals(2, deux.size(), deux.toString());
        toutArrose(long_, deux);
    }

    /** Un champ de 32 sur 32 (plat, puis en marches de 4 rangs) : quelques millisecondes, pas un tick entier. */
    @Test
    void lesMaresDUnGrandChampVite() {
        for (java.util.function.IntUnaryOperator niveau : List.<java.util.function.IntUnaryOperator>of(z -> 0, z -> Math.floorDiv(z, 4))) {
            List<Cultures.Point> grand = new java.util.ArrayList<>();
            for (int x = -16; x < 16; x++) for (int z = -16; z < 16; z++) grand.add(new Cultures.Point(x, niveau.applyAsInt(z), z));
            long froid = System.nanoTime();
            List<Cultures.Point> m = Cultures.mares(grand, grand, List.of(), p -> true, grand.size(), 0, 0);
            froid = System.nanoTime() - froid;
            for (int i = 0; i < 20; i++) Cultures.mares(grand, grand, List.of(), p -> true, grand.size(), 0, 0);
            long chaud = System.nanoTime();
            for (int i = 0; i < 20; i++) Cultures.mares(grand, grand, List.of(), p -> true, grand.size(), 0, 0);
            chaud = (System.nanoTime() - chaud) / 20;
            System.out.printf("Mares d'un champ de 32 sur 32 : %d mares, %.1f ms à froid, %.2f ms à chaud%n", m.size(), froid / 1e6, chaud / 1e6);
            assertTrue(chaud < 20_000_000L, "trop lent : " + chaud / 1e6 + " ms");
            assertFalse(m.isEmpty());
        }
    }
}

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

    @Test
    void lesMaresDuChamp() {
        // Plat : une mare, au milieu.
        List<Cultures.Point> plat = champ(z -> 0);
        assertEquals(List.of(new Cultures.Point(0, 0, 0)), Cultures.mares(plat, List.of(plat, List.of()), 0, 0));
        // L'eau arrose son niveau et celui d'en dessous, pas celui d'au-dessus.
        assertTrue(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(4, 4, -4)));
        assertFalse(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(0, 6, 1)));
        assertFalse(Cultures.arrose(new Cultures.Point(0, 5, 0), new Cultures.Point(5, 5, 0)));
        // En escalier sur trois niveaux : une mare de plus, au niveau du haut ; tout est arrosé, une mare par niveau au plus.
        List<Cultures.Point> escalier = champ(z -> z <= -2 ? -1 : z >= 2 ? 1 : 0);
        List<Cultures.Point> m = Cultures.mares(escalier, List.of(escalier, List.of()), 0, 0);
        assertEquals(2, m.size(), m.toString());
        assertEquals(m.size(), m.stream().map(Cultures.Point::y).distinct().count());
        toutArrose(escalier, m);
        // Déjà arrosé : rien à creuser. Trop peu de cases sèches pour une mare : rien non plus.
        assertTrue(Cultures.mares(List.of(), List.of(plat), 0, 0).isEmpty());
        assertTrue(Cultures.mares(List.of(new Cultures.Point(4, 0, 4), new Cultures.Point(4, 0, 3)), List.of(plat), 0, 0).isEmpty());
        // Seul le tampon du poste peut recevoir l'eau : il y creuse, faute d'autre place.
        assertEquals(1, Cultures.mares(plat, List.of(List.of(), plat), 0, 0).size());
        // Une place hors tampon d'abord, même si celle du tampon arroserait plus.
        List<Cultures.Point> bord = plat.stream().filter(p -> p.x() == -4).toList();
        assertEquals(-4, Cultures.mares(plat, List.of(bord, plat), 0, 0).get(0).x());
        // Un champ de 18 de long : deux carrés de 9, une mare chacun, au même niveau, jamais côte à côte.
        List<Cultures.Point> long_ = new java.util.ArrayList<>();
        for (int x = -4; x <= 13; x++) for (int z = -4; z <= 4; z++) long_.add(new Cultures.Point(x, 0, z));
        List<Cultures.Point> deux = Cultures.mares(long_, List.of(long_), 0, 0);
        assertEquals(2, deux.size(), deux.toString());
        toutArrose(long_, deux);
    }
}

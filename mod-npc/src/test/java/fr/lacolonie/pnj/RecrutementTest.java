package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 13, cahier 5.8 : talents des chercheurs ({@link Talents}), réputation, affiche et village ({@link Recrutement}), répliques de recrutement.txt. */
class RecrutementTest {

    static java.util.function.DoubleSupplier suite(double... v) {
        ArrayDeque<Double> d = new ArrayDeque<>();
        for (double x : v) d.add(x);
        return d::poll;
    }

    @Test
    void unOuDeuxTalentsDistincts() {
        assertEquals(List.of(Talents.HABILE), Talents.tirer(suite(0.0, 0.9)));
        assertEquals(List.of(Talents.SOBRE), Talents.tirer(suite(0.999, 0.5)));
        List<String> deux = Talents.tirer(suite(0.0, 0.1, 0.0));
        assertEquals(List.of(Talents.HABILE, Talents.ROBUSTE), deux, "le second n'est jamais le premier");
        Random r = new Random(7);
        Set<String> vus = new HashSet<>();
        int doubles = 0;
        for (int i = 0; i < 2000; i++) {
            List<String> t = Talents.tirer(r::nextDouble);
            assertTrue(t.size() == 1 || t.size() == 2);
            assertEquals(t.size(), new HashSet<>(t).size());
            vus.addAll(t);
            if (t.size() == 2) doubles++;
        }
        assertEquals(Set.copyOf(Talents.TOUS), vus);
        assertTrue(doubles > 400 && doubles < 600, "environ un sur quatre a deux talents (" + doubles + ")");
    }

    @Test
    void effetsDesTalents() {
        assertEquals(1.2, Talents.vitesse(List.of(Talents.HABILE)), 1e-9);
        assertEquals(1, Talents.vitesse(List.of(Talents.ROBUSTE)), 1e-9);
        assertEquals(6, Talents.vie(List.of(Talents.SOBRE, Talents.ROBUSTE)));
        assertEquals(0, Talents.vie(List.of()));
        assertEquals(3, Talents.fatigue(5, List.of(Talents.ENDURANT)));
        assertEquals(4, Talents.fatigue(7, List.of(Talents.ENDURANT)));
        assertEquals(1, Talents.fatigue(1, List.of(Talents.ENDURANT)), "jamais nulle");
        assertEquals(-8, Talents.fatigue(-8, List.of(Talents.ENDURANT)), "le repos n'est pas touché");
        assertEquals(5, Talents.fatigue(5, List.of(Talents.HABILE)));
        assertEquals(21600, Talents.seuilFaim(14400, List.of(Talents.SOBRE)));
        assertEquals(14400, Talents.seuilFaim(14400, List.of()));
    }

    @Test
    void talentsSauvesEtRelus() {
        assertEquals("habile,sobre", Talents.texte(List.of(Talents.HABILE, Talents.SOBRE)));
        assertEquals(List.of(Talents.HABILE, Talents.SOBRE), Talents.lire("habile,sobre"));
        assertEquals(List.of(), Talents.lire(""), "ouvrier d'avant le lot 13");
        assertEquals(List.of(), Talents.lire(null));
        assertEquals(List.of(Talents.ROBUSTE, Talents.HABILE), Talents.lire("robuste, inconnu,robuste,habile,sobre"), "connus, sans doublon, deux au plus");
        assertTrue(Talents.portrait(List.of(Talents.ROBUSTE)).contains("chêne"));
        assertEquals("", Talents.portrait(List.of()));
    }

    @Test
    void reputationBornee() {
        assertEquals(Recrutement.MAX, Recrutement.reputation(49, 5));
        assertEquals(Recrutement.MIN, Recrutement.reputation(-48, Recrutement.MORT));
        assertEquals(Recrutement.MIN, Recrutement.reputation(Integer.MIN_VALUE + 1, -10), "sans débordement");
        assertEquals(1, Recrutement.reputation(0, Recrutement.PAIE));
    }

    @Test
    void chanceSelonReputationAfficheEtVillage() {
        assertEquals(0.35, Recrutement.chance(0.35, 0, false, false), 1e-9);
        assertEquals(0.525, Recrutement.chance(0.35, 50, false, false), 1e-9, "bonne réputation : +50 %");
        assertEquals(0.175, Recrutement.chance(0.35, -50, false, false), 1e-9, "mauvaise : moitié moins");
        assertEquals(0.455, Recrutement.chance(0.35, 0, true, false), 1e-9, "affiche : +30 %");
        assertEquals(0.525, Recrutement.chance(0.35, 0, false, true), 1e-9, "village : une fois et demie");
        // Relecture : tous les bonus ensemble ne rendent jamais plus d'une fois et demie la chance de base (la rareté tient).
        assertEquals(0.525, Recrutement.chance(0.35, 50, true, true), 1e-9, "bonus plafonnés à ×1,5");
        assertEquals(0.525, Recrutement.chance(0.35, 20, true, false), 1e-9, "affiche et bonne réputation : plafonnées aussi");
        assertEquals(0.2275, Recrutement.chance(0.35, -50, true, false), 1e-9, "une affiche ne rachète qu'un peu une mauvaise réputation");
        assertEquals(Recrutement.CHANCE_MAX, Recrutement.chance(0.9, 50, true, true), 1e-9, "borné");
        assertEquals(0, Recrutement.chance(0, 50, true, true), 1e-9, "arrivées coupées : rien ne les rallume");
        assertEquals(0, Recrutement.chance(Double.NaN, 0, false, false), 1e-9);
    }

    @Test
    void exigenceEtPalier() {
        assertEquals(1, Recrutement.exigence(50), 1e-9);
        assertEquals(1, Recrutement.exigence(0), 1e-9);
        assertEquals(1.15, Recrutement.exigence(-25), 1e-9);
        assertEquals(1.3, Recrutement.exigence(-50), 1e-9);
        assertEquals(1.3, Recrutement.exigence(-500), 1e-9);
        assertEquals("reputation_bonne", Recrutement.palier(20));
        assertEquals("reputation_neutre", Recrutement.palier(0));
        assertEquals("reputation_mauvaise", Recrutement.palier(-12));
        assertEquals("reputation_terrible", Recrutement.palier(-40));
    }

    @Test
    void afficheDEmbauche() {
        assertTrue(Recrutement.affiche("On embauche "));
        assertTrue(Recrutement.affiche("ON EMBAUCHE ICI"));
        assertTrue(Recrutement.affiche("Recrutons mineurs"));
        assertTrue(Recrutement.affiche("Cherchons main d'œuvre".replace("œ", "oe")));
        assertFalse(Recrutement.affiche("Mine de fer"));
        assertFalse(Recrutement.affiche(""));
        assertFalse(Recrutement.affiche(null));
    }

    @Test
    void lesRepliquesSeLisent() {
        var banque = Vie.lire(Repertoire.lignes("/recrutement.txt"));
        for (String t : Talents.TOUS) assertTrue(banque.containsKey("talent_" + t) && Vie.TOUTES.containsKey("talent_" + t), t);
        for (int r : new int[]{40, 0, -15, -45}) assertTrue(banque.containsKey(Recrutement.palier(r)), Recrutement.palier(r));
        assertTrue(banque.containsKey("embauche_reputation"));
        for (String cle : banque.keySet()) assertNotNull(Vie.tirer(cle, "colerique", Map.of("chef", "chef"), () -> 0.5), cle);
        for (var e : banque.entrySet())
            for (Vie.Replique r : e.getValue())
                for (String mot : List.of("joueur", "serveur", "Minecraft", " IA ")) assertFalse(r.texte().contains(mot), mot + " dans [" + e.getKey() + "]");
    }

    /** Correction F3 : la liste des métiers ouverts (vide ou absente : tous), sans casse ni accents ; l'interrupteur du panneau en ferme en plus. */
    @Test
    void metiersOuverts() {
        assertTrue(Recrutement.metierActif("mineur", null, null), "réglage absent : tous");
        assertTrue(Recrutement.metierActif("mineur", List.of(), List.of()), "liste vide : tous");
        assertTrue(Recrutement.metierActif("mineur", List.of(" ", ""), List.of()), "liste sans nom : tous");
        assertTrue(Recrutement.metierActif("guerisseur", List.of("Guérisseur", "bucheron"), List.of()), "accents et majuscules");
        assertTrue(Recrutement.metierActif("bucheron", List.of("BÛCHERON "), List.of()));
        assertFalse(Recrutement.metierActif("mineur", List.of("guérisseur", "bucheron"), List.of()), "hors de la liste");
        assertFalse(Recrutement.metierActif("mineur", List.of(), List.of("mineur")), "fermé au panneau");
        assertFalse(Recrutement.metierActif("bucheron", List.of("bucheron"), List.of("Bûcheron")), "fermé au panneau, même dans la liste");
        assertFalse(Recrutement.metierActif(null, List.of(), List.of()));
    }
}

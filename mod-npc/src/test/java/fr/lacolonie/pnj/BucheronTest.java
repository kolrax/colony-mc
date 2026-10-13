package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cahier 3.6 : ce que le chef demande au bûcheron, lu sans IA (essence, forêt en rangs, défrichage, planches, charbon de bois). */
class BucheronTest {
    @Test
    void lEssenceDemandee() {
        OrdresTest.missions("Coupe du bouleau", "bucheron", new Mission("bouleau", "quantite", 32));
        OrdresTest.missions("Va chercher du sapin", "bucheron", new Mission("sapin", "quantite", 32));
        OrdresTest.missions("Ramène-moi 64 bûches de chêne", "bucheron", new Mission("chene", "quantite", 64));
        OrdresTest.missions("Abats du chêne noir", "bucheron", new Mission("chene_noir", "quantite", 32));
    }

    @Test
    void laForetEnRangs() {
        assertEquals("10 10 1 - face", Fouille.lire("foret", "Plante une forêt ici", 0).texte());
        assertEquals("12 6 1 - face", Fouille.lire("foret", "Plante des bouleaux en rangs, 12 sur 6", 0).texte());
        assertEquals("32 32 1 - face", Fouille.lire("foret", "Plante une forêt de 100 sur 100", 0).texte()); // bornée
        // 10 sur 10, une pousse tous les 3 blocs : 4 rangs de 4.
        List<int[]> r = Fouille.rangs(new Fouille.Plan(10, 10, 1, "", false), false);
        assertEquals(16, r.size());
        assertArrayEquals(new int[]{-4, -4, 0}, r.get(0));
        assertArrayEquals(new int[]{-4, -1, 1}, r.get(1));
        // Des géants : des carrés de 4 pousses, tous les 5 blocs (2 carrés sur 2 dans 7 sur 7).
        List<int[]> g = Fouille.rangs(new Fouille.Plan(7, 7, 1, "", false), true);
        assertEquals(16, g.size());
        assertEquals(4, g.stream().filter(x -> x[2] == 0).count());
        assertTrue(g.stream().allMatch(x -> x[0] >= -3 && x[0] <= 3 && x[1] >= -3 && x[1] <= 3), "dans le terrain");
        assertEquals("bouleau", Fouille.essence("Plante des bouleaux en rangs"));
        assertEquals("chene_noir", Fouille.essence("une forêt de chênes noirs"));
        assertEquals("", Fouille.essence("Plante une forêt"));
        assertTrue(Fouille.geants("Plante des sapins géants"));
        assertFalse(Fouille.geants("Plante des sapins"));
    }

    @Test
    void leDefrichageEtLaQuantite() {
        assertEquals("16 16 1 - face", Fouille.lire("defriche", "Défriche cette zone", 0).texte());
        assertEquals("16 16 1 - face", Fouille.lire("defriche", "Défriche ici 16 sur 16", 0).texte());
        assertEquals("20 8 1 - face", Fouille.lire("defriche", "Défriche 20 sur 8", 0).texte());
        assertEquals(32, Fouille.lire("defriche", "Défriche 50 sur 50", 0).longueur());
        assertEquals(32, Fouille.quantite("Fais 32 planches"));
        assertEquals(128, Fouille.quantite("Fais deux piles de planches"));
        assertEquals(12, Fouille.quantite("Fais une douzaine de charbons de bois"));
        assertEquals(0, Fouille.quantite("Fais du charbon de bois"));
    }

    @Test
    void leRepertoireLesComprend() {
        RepertoireTest.demande("Plante une forêt ici", "bucheron", "foret");
        RepertoireTest.demande("Plante des bouleaux en rangs", "bucheron", "foret");
        RepertoireTest.demande("Défriche ici 16 sur 16", "bucheron", "defricher_zone");
        RepertoireTest.demande("Défriche cette zone", "bucheron", "defricher_zone");
        RepertoireTest.demande("Fais des planches", "bucheron", "planches");
        RepertoireTest.demande("Fais 32 planches de bouleau", "bucheron", "planches");
        RepertoireTest.demande("Fais du charbon de bois", "bucheron", "charbon_bois");
        RepertoireTest.demande("Plante des arbres", "bucheron", "planter");
        RepertoireTest.demande("Abats les arbres autour", "bucheron", "abattre_autour");
        // Relecture : un nom seul ne suffit pas, il faut le verbe (donner, vendre, ranger des planches, ce n'est pas en faire).
        for (String x : List.of("Donne-moi des planches", "Donne-moi 10 planches", "Vends-moi 32 planches", "Va chercher du charbon de bois au coffre",
                "Range le charbon de bois")) {
            String a = RepertoireTest.action(x, "bucheron");
            assertFalse(a.equals("planches") || a.equals("charbon_bois"), x + " → " + a);
        }
        // Ce ne sont pas des commandes de bois.
        for (String s : List.of("Plante des bouleaux en rangs", "Fais des planches de chêne", "Fais du charbon de bois", "Défriche cette zone"))
            assertTrue(Ordres.lire(s, "bucheron").missions.isEmpty(), s + " → " + Ordres.lire(s, "bucheron").missions);
    }
}

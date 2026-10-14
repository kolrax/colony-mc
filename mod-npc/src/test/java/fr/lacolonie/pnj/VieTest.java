package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VieTest {
    @Test
    void leFichierSeLit() {
        // Strict : une seule erreur dans le fichier livré fait échouer le test (le jeu, lui, ignorerait la section).
        Map<String, List<Vie.Replique>> banque = Vie.lire(Repertoire.lignes("/vie.txt"));
        assertEquals(Vie.TOUTES.size(), banque.size() + Vie.lire(Repertoire.lignes("/remarques.txt")).size());
        for (String cle : List.of("faim_legere", "faim_forte", "fatigue", "sans_lit", "lit_vole", "blessure_legere", "blessure_grave", "a_terre", "releve", "soigne", "blesse_vu",
                "envie_pause", "plainte_douce", "plainte_forte", "content", "fier_colonie"))
            assertTrue(banque.containsKey(cle), cle);
        for (var e : banque.entrySet())
            for (Vie.Replique r : e.getValue())
                for (String mot : List.of("joueur", "serveur", "Minecraft", " IA ")) assertFalse(r.texte().contains(mot), mot + " dans [" + e.getKey() + "]");
    }

    @Test
    void lectureEtTemperament() {
        Map<String, List<Vie.Replique>> b = Vie.lire(List.of("# note", "", "[faim]", "Commune.", "colerique: Colère !", "peureux: Peur…"));
        assertEquals(List.of(new Vie.Replique("", "Commune."), new Vie.Replique("colerique", "Colère !"), new Vie.Replique("peureux", "Peur…")), b.get("faim"));
        // Une fois sur deux, la sienne ; sinon une commune. Un calme n'a que la commune.
        assertEquals("Colère !", Vie.tirer(b, "faim", "colerique", Map.of(), () -> 0.1));
        assertEquals("Commune.", Vie.tirer(b, "faim", "colerique", Map.of(), () -> 0.7));
        assertEquals("Commune.", Vie.tirer(b, "faim", "calme", Map.of(), () -> 0.1));
        assertNull(Vie.tirer(b, "inconnue", "calme", Map.of(), () -> 0.1));
        // Sur beaucoup de tirages, à peu près moitié-moitié.
        Random r = new Random(4);
        int siennes = 0;
        for (int i = 0; i < 1000; i++) if (Vie.tirer(b, "faim", "peureux", Map.of(), r::nextDouble).equals("Peur…")) siennes++;
        assertTrue(siennes > 400 && siennes < 600, "siennes " + siennes);
    }

    @Test
    void variables() {
        Map<String, List<Vie.Replique>> b = Vie.lire(List.of("[lit_vole]", "{nom} a pris mon lit, {chef}.", "C'était mon lit."));
        assertEquals("Raoul a pris mon lit, KOLRAX.", Vie.tirer(b, "lit_vole", "calme", Map.of("nom", "Raoul", "chef", "KOLRAX"), () -> 0.1));
        // Sans {nom} connu, seule la réplique sans {nom} peut sortir.
        for (double x = 0; x < 1; x += 0.1) { double y = x; assertEquals("C'était mon lit.", Vie.tirer(b, "lit_vole", "calme", Map.of("chef", "KOLRAX"), () -> y)); }
        // « {chef}, … » avec « chef » pour nom : majuscule en tête.
        Map<String, List<Vie.Replique>> c = Vie.lire(List.of("[faim_forte]", "{chef}, à manger."));
        assertEquals("Chef, à manger.", Vie.tirer(c, "faim_forte", "calme", Map.of("chef", "chef"), () -> 0.1));
    }

    @Test
    void blocFautifIgnore() {
        List<String> lignes = List.of(
                "Hors section.",                 // 1 : hors d'une section
                "[bonne]", "Une.",               // gardée
                "[mauvaise_var]", "Bonjour {qui}.", "Commune.",
                "[mauvais_temperament]", "joyeux: Youpi.", "Commune.",
                "[Majuscule]", "Commune.",
                "[sans_commune]", "calme: Rien que moi.",
                "[bonne]", "Doublon.",
                "[fin_ok]", "Dernière.");
        List<String> erreurs = new ArrayList<>();
        Map<String, List<Vie.Replique>> b = Vie.lire(lignes, e -> erreurs.add(e.getMessage()));
        assertEquals(List.of("bonne", "fin_ok"), List.copyOf(b.keySet()));
        assertEquals(List.of(new Vie.Replique("", "Une.")), b.get("bonne"), "le doublon ne remplace pas la première");
        assertEquals(6, erreurs.size(), erreurs.toString());
        assertTrue(erreurs.get(0).contains("ligne 1"), erreurs.get(0));
        assertTrue(erreurs.get(1).contains("{qui}") && erreurs.get(1).contains("ligne 5"), erreurs.get(1));
        assertTrue(erreurs.get(2).contains("joyeux"), erreurs.get(2));
        // Strict : la première erreur arrête tout.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Vie.lire(lignes));
        assertTrue(e.getMessage().contains("ligne 1"), e.getMessage());
    }

    @Test
    void seuils() {
        assertNull(Vie.faim(13));
        assertEquals("faim_legere", Vie.faim(12));
        assertEquals("faim_forte", Vie.faim(6));
        assertNull(Vie.blessure(15, 20));
        assertEquals("blessure_legere", Vie.blessure(14, 20));
        assertEquals("blessure_grave", Vie.blessure(6, 20));
        assertEquals("plainte_douce", Vie.humeur(35, 38));
        assertEquals("plainte_forte", Vie.humeur(20, 22));
        assertNull(Vie.humeur(35, 30), "il remonte : pas de plainte");
        assertNull(Vie.humeur(35, 35), "rien n'a bougé");
        assertEquals("content", Vie.humeur(82, 80));
        assertEquals("fier_colonie", Vie.humeur(95, 90));
        assertNull(Vie.humeur(82, 85), "il baisse : pas de contentement");
        assertTrue(Vie.veutPause(50, Vie.PAUSE_APRES));
        assertFalse(Vie.veutPause(80, Vie.PAUSE_APRES), "content : pas besoin de pause");
        assertFalse(Vie.veutPause(Obeissance.REPOS, Vie.PAUSE_APRES), "moral au repos (60) : pas de pause, il faut moins");
        assertTrue(Vie.veutPause(Obeissance.REPOS - 1, Vie.PAUSE_APRES));
        assertFalse(Vie.veutPause(50, Vie.PAUSE_APRES - 1), "pas assez travaillé");
    }

    @Test
    void vient() {
        assertTrue(Vie.vient("lit_vole", "Raoul a pris mon lit. Ce n'est pas juste."));
        assertTrue(Vie.vient("faim_legere", "Mon ventre commence à gargouiller."));
        assertFalse(Vie.vient("faim_legere", "J'ai trop faim. Mes bras ne suivent plus."));
    }
}

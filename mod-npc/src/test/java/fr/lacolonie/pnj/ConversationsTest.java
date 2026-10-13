package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConversationsTest {
    static Conversations.Personne p(String metier, String temperament) { return new Conversations.Personne(metier, temperament, 20, 0, 60, false, 0, false); }

    /** De jour (10 h), sans pluie. */
    static Set<String> jour(Conversations.Personne a, Conversations.Personne b) { return Conversations.contexte(10, false, false, false, a, b); }

    @Test
    void leFichierSeLit() {
        assertTrue(Conversations.TOUTES.size() >= 200, "au moins 200 conversations : " + Conversations.TOUTES.size());
        for (Conversations.Conversation c : Conversations.TOUTES) {
            assertTrue(c.repliques().size() >= 2 && c.repliques().size() <= 6, "ligne " + c.ligne());
            assertTrue(c.repliques().get(0).deA(), "A parle d'abord, ligne " + c.ligne());
            for (Conversations.Replique r : c.repliques())
                for (String mot : List.of("joueur", "serveur", "Minecraft", " IA ")) assertFalse(r.texte().contains(mot), mot + " ligne " + c.ligne());
        }
    }

    @Test
    void erreursRefusees() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail | pluvieux", "A | Bonjour.", "B | Bonjour.")));
        assertTrue(e.getMessage().contains("ligne 1") && e.getMessage().contains("pluvieux"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail | metier_a=forgeron", "A | x", "B | y")));
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail | temperament_b=joyeux", "A | x", "B | y")));
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail |", "A | x", "A | y")), "A et B alternent");
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail |", "A | x")), "deux répliques au moins");
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail |", "A | Salut {c}.", "B | y")), "variable inconnue");
        assertThrows(IllegalStateException.class, () -> Conversations.lire(List.of("= travail", "A | x", "B | y")), "barre obligatoire");
        assertEquals(1, Conversations.lire(List.of("# rien", "", "= travail | nuit, meme_metier, metier_b=macon", "A | x", "B | y")).size());
    }

    @Test
    void choixSelonLesConditions() {
        Random r = new Random(7);
        Conversations.Personne mineurPeureux = p("mineur", "peureux"), bucheronCalme = p("bucheron", "calme");
        Set<String> nuit = Conversations.contexte(23, false, false, false, mineurPeureux, bucheronCalme);
        assertTrue(nuit.contains("nuit") && !nuit.contains("beau_temps") && !nuit.contains("meme_metier"), nuit.toString());
        assertTrue(jour(mineurPeureux, bucheronCalme).contains("beau_temps"));
        assertTrue(jour(mineurPeureux, p("mineur", "calme")).contains("meme_metier"));
        assertTrue(Conversations.contexte(10, true, false, false, mineurPeureux, bucheronCalme).contains("pluie"));
        int nuits = 0, precises = 0;
        for (int i = 0; i < 400; i++) {
            Conversations.Choix c = Conversations.choisir(Conversations.TOUTES, List.of(nuit), List.of(), r::nextDouble);
            Conversations.Conversation conv = Conversations.TOUTES.get(c.id());
            for (String cond : conv.conditions()) {
                assertTrue(nuit.contains(cond), "condition fausse : " + cond + " (ligne " + conv.ligne() + ")");
                assertNotEquals("temperament_a=calme", cond);
                assertNotEquals("metier_a=bucheron", cond);
            }
            if (conv.conditions().contains("nuit")) nuits++;
            if (!conv.conditions().isEmpty()) precises++;
        }
        assertTrue(nuits > 20, "les conversations de nuit doivent sortir souvent : " + nuits);
        assertTrue(precises > 200, "les plus précises d'abord : " + precises + " sur 400");
        // Les deux sens : le bûcheron calme peut parler en premier.
        boolean calmeEnA = false;
        for (int i = 0; i < 200 && !calmeEnA; i++) {
            Conversations.Choix c = Conversations.choisir(Conversations.TOUTES, List.of(jour(mineurPeureux, bucheronCalme), jour(bucheronCalme, mineurPeureux)), List.of(), r::nextDouble);
            if (c.sens() == 1 && Conversations.TOUTES.get(c.id()).conditions().contains("temperament_a=calme")) calmeEnA = true;
        }
        assertTrue(calmeEnA);
    }

    @Test
    void pasDeRepetition() {
        Random r = new Random(3);
        Deque<Integer> recentes = new ArrayDeque<>();
        Set<String> ctx = jour(p("guerrier", "colerique"), p("guerrier", "fourbe"));
        for (int i = 0; i < 300; i++) {
            Conversations.Choix c = Conversations.choisir(Conversations.TOUTES, List.of(ctx), recentes, r::nextDouble);
            assertFalse(recentes.contains(c.id()), "redite de la ligne " + Conversations.TOUTES.get(c.id()).ligne() + " au tour " + i);
            Conversations.noter(recentes, c.id());
        }
        assertEquals(Conversations.RECENTES, recentes.size());
        // Il ne reste que des redites : la plus ancienne revient.
        List<Conversations.Conversation> deux = Conversations.lire(List.of("= a |", "A | x", "B | y", "= b |", "A | z", "B | w"));
        Deque<Integer> toutes = new ArrayDeque<>(List.of(1, 0));
        assertEquals(1, Conversations.choisir(deux, List.of(Set.of()), toutes, r::nextDouble).id());
        assertNull(Conversations.choisir(Conversations.lire(List.of("= a | nuit", "A | x", "B | y")), List.of(Set.of()), List.of(), r::nextDouble));
    }

    @Test
    void variablesRemplacees() {
        String t = Conversations.remplir("{a}, dis à {b} que {chef} veut un {metier_b}, pas un {metier_a}.",
                Map.of("a", "Odon", "b", "Blanche", "chef", "KOLRAX", "metier_a", "mineur", "metier_b", "bûcheron"));
        assertEquals("Odon, dis à Blanche que KOLRAX veut un bûcheron, pas un mineur.", t);
        for (Conversations.Conversation c : Conversations.TOUTES)
            for (Conversations.Replique r : c.repliques())
                assertFalse(Conversations.remplir(r.texte(), Map.of("a", "x", "b", "x", "chef", "x", "metier_a", "x", "metier_b", "x")).contains("{"), "ligne " + c.ligne());
        assertTrue(Conversations.delai("Oui.") >= 40 && Conversations.delai("x".repeat(200)) <= 80);
    }
}

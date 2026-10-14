package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Remarques spontanées, humour devant l'impossible, réactions selon le caractère (lot 7, remarques.txt). */
class RemarquesTest {
    static final List<String> METIERS = List.of("mineur", "bucheron", "guerrier", "fermier", "eleveur", "pecheur", "chasseur", "cuisinier", "transporteur", "guerisseur");

    @Test
    void leFichierSeLit() {
        // Strict : une erreur dans le fichier livré fait échouer le test ; toutes ses clés sont chargées avec vie.txt.
        Map<String, List<Vie.Replique>> b = Vie.lire(Repertoire.lignes("/remarques.txt"));
        List<String> cles = new java.util.ArrayList<>(List.of("remarque_matin", "remarque_soir", "remarque_nuit", "remarque_pluie", "remarque_orage", "remarque_neige",
                "remarque_beau_temps", "remarque_travail_fini", "remarque_depot_plein", "remarque_ennui", "remarque_machines", "remarque_nouveau_collegue", "remarque_jour_paie",
                "impossible_voler", "impossible_lune", "impossible_roche", "impossible_creature", "impossible_duree", "impossible_vite", "impossible_quantite",
                "impossible_nature", "impossible_magie", "impossible_general", "caractere_insulte", "caractere_coup", "caractere_impaye", "caractere_diamant_cache",
                "caractere_diamant_rendu", "caractere_cadeau", "caractere_excuses"));
        for (String m : METIERS) cles.add("remarque_" + m);
        for (String c : cles) {
            assertTrue(b.containsKey(c), c);
            assertEquals(b.get(c), Vie.TOUTES.get(c), c + " chargée");
        }
        assertEquals(cles.size(), b.size(), "aucune clé de trop");
        Map<String, List<Vie.Replique>> vie = Vie.lire(Repertoire.lignes("/vie.txt"));
        for (String c : b.keySet()) assertFalse(vie.containsKey(c), c + " existe déjà dans vie.txt");
        for (var e : b.entrySet())
            for (Vie.Replique r : e.getValue())
                for (String mot : List.of("joueur", "serveur", "Minecraft", " IA ")) assertFalse(r.texte().contains(mot), mot + " dans [" + e.getKey() + "]");
        for (String g : Impossible.GENRES.stream().map(x -> (String) x[0]).toList()) assertTrue(b.containsKey("impossible_" + g), g);
    }

    @Test
    void caractereCompleteVie() {
        Map<String, List<Vie.Replique>> b = Vie.lire(List.of("[insulte]", "De vie.", "[caractere_insulte]", "Du caractère."));
        Set<String> vues = new HashSet<>();
        for (double x = 0; x < 1; x += 0.05) { double y = x; vues.add(Vie.tirer(b, "insulte", "calme", Map.of(), () -> y)); }
        assertEquals(Set.of("De vie.", "Du caractère."), vues);
    }

    @Test
    void impossibleSelonLeGenre() {
        Map<String, String> phrases = Map.ofEntries(
                Map.entry("Envole-toi !", "voler"), Map.entry("Saute du toit pour voler", "voler"), Map.entry("Tu sais voler ?", "voler"),
                Map.entry("Va sur la lune", "lune"), Map.entry("Décroche-moi la lune, Raoul", "lune"),
                Map.entry("Casse la bedrock", "roche"), Map.entry("Creuse sous le fond du monde", "roche"),
                Map.entry("Ramène-moi un dragon", "creature"), Map.entry("Apprivoise une licorne", "creature"),
                Map.entry("Travaille 48 h d'affilée", "duree"), Map.entry("Travaille sans dormir", "duree"),
                Map.entry("Construis-moi un château en une minute", "vite"),
                Map.entry("Ramène mille diamants", "quantite"), Map.entry("Coupe toute la forêt", "quantite"), Map.entry("Ramène 5000 diamants", "quantite"),
                Map.entry("Arrête la pluie", "nature"), Map.entry("Fais lever le soleil", "nature"),
                Map.entry("Change la pierre en or", "magie"), Map.entry("Deviens invisible", "magie"),
                Map.entry("Fais un miracle", "general"), Map.entry("Vide la mer", "general"));
        phrases.forEach((p, g) -> assertEquals(g, Impossible.genre(p), p));
    }

    @Test
    void ordresOrdinairesPasImpossibles() {
        for (String p : List.of("va miner du fer", "coupe du bois", "va chercher des diamants", "ramène 64 diamants", "ramène 10 fers", "travaille de 8h à 18h",
                "s'il pleut, rentre au poste", "va voler du pain au voisin", "arrête-toi", "rentre avant la nuit", "mine près de la bedrock",
                "garde le poste", "va au soleil couchant pêcher", "coupe des bouleaux", "construis un pont", "viens ici", "va au lit", "ramène 2000 pierres"))
            assertNull(Impossible.genre(p), p);
    }

    @Test
    void remarqueSelonLeMoment() {
        Map<String, List<Vie.Replique>> b = Vie.TOUTES;
        // Un événement passe d'abord.
        assertEquals("remarque_nouveau_collegue", Vie.remarque(b, moment(12, false, false, "Raoul", false), k -> false, () -> 0.1));
        assertEquals("remarque_travail_fini", Vie.remarque(b, moment(12, true, false, null, false), k -> false, () -> 0.9));
        // Sinon : le matin et le beau temps, ou son métier au travail.
        Set<String> vues = new HashSet<>();
        for (double x = 0; x < 1; x += 0.05) { double y = x; vues.add(Vie.remarque(b, moment(7, false, false, null, true), k -> false, () -> y)); }
        assertEquals(Set.of("remarque_matin", "remarque_beau_temps", "remarque_mineur"), vues);
        // La nuit, sous l'orage : pas de « beau temps ».
        Vie.Moment orage = new Vie.Moment(23, true, true, false, false, "mineur", false, false, false, null, false);
        vues.clear();
        for (double x = 0; x < 1; x += 0.05) { double y = x; vues.add(Vie.remarque(b, orage, k -> false, () -> y)); }
        assertEquals(Set.of("remarque_nuit", "remarque_orage"), vues);
        // Déjà dites aujourd'hui : rien.
        assertNull(Vie.remarque(b, orage, k -> true, () -> 0.5));
        // Ennui sans ordre plutôt que son métier.
        assertEquals("remarque_ennui", Vie.remarque(b, new Vie.Moment(12, true, false, false, true, "mineur", false, false, true, null, false),
                k -> !k.equals("remarque_ennui"), () -> 0.5));
    }

    static Vie.Moment moment(int heure, boolean fini, boolean paie, String nouveau, boolean auTravail) {
        return new Vie.Moment(heure, false, false, false, auTravail, "mineur", fini, false, false, nouveau, paie);
    }
}

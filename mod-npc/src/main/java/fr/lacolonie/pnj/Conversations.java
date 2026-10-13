package fr.lacolonie.pnj;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les conversations entre deux ouvriers d'une même colonie, sans IA (lot 7), lues dans {@code conversations.txt}.
 * Pur Java : lecture et validation du fichier, conditions vraies pour un couple, choix (les plus précises d'abord, avec
 * du hasard, sans redire les dernières de la colonie), remplacement des variables. Le jeu est dans {@link Causeries}.
 */
final class Conversations {
    private Conversations() {}

    static final Set<String> METIERS = Set.of("mineur", "bucheron", "guerrier", "fermier", "eleveur", "pecheur", "chasseur",
            "cuisinier", "boulanger", "transporteur", "guerisseur", "macon");
    static final Set<String> TEMPERAMENTS = Set.of("calme", "peureux", "fourbe", "colerique");
    static final Set<String> SIMPLES = Set.of("matin", "soir", "nuit", "pluie", "orage", "neige", "beau_temps", "paie_recue",
            "paie_en_retard", "faim", "fatigue", "moral_bas", "moral_haut", "apres_danger", "meme_metier");
    static final Set<String> VARIABLES = Set.of("a", "b", "chef", "metier_a", "metier_b");
    static final Pattern VARIABLE = Pattern.compile("\\{([^}]*)}");
    /** Combien de conversations récentes la colonie ne redit pas. */
    static final int RECENTES = 30;
    /** Seuils : faim (0 à 20), fatigue (0 à 100), moral (0 à 100). */
    static final int FAIM = 10, FATIGUE = 70, MORAL_BAS = 40, MORAL_HAUT = 80;

    record Replique(boolean deA, String texte) {}

    record Conversation(int ligne, String sujet, List<String> conditions, List<Replique> repliques) {
        boolean possible(Set<String> contexte) { return contexte.containsAll(conditions); }
    }

    /** Un des deux ouvriers, vu par les conditions. */
    record Personne(String metier, String temperament, int faim, int fatigue, int moral, boolean payeAujourdhui, int impayes, boolean apresDanger) {}

    /** La conversation choisie, et qui parle en premier : 0 = le premier contexte donné, 1 = le second. */
    record Choix(int id, int sens) {}

    static final List<Conversation> TOUTES = new ArrayList<>();

    static void charger() {
        TOUTES.clear();
        TOUTES.addAll(lire(Repertoire.lignes("/conversations.txt")));
    }

    static boolean connue(String c) {
        if (SIMPLES.contains(c)) return true;
        int i = c.indexOf('=');
        if (i < 0) return false;
        String cle = c.substring(0, i), val = c.substring(i + 1);
        return switch (cle) {
            case "metier_a", "metier_b" -> METIERS.contains(val);
            case "temperament_a", "temperament_b" -> TEMPERAMENTS.contains(val);
            default -> false;
        };
    }

    /** Lit le fichier ; une erreur (condition inconnue, répliques qui n'alternent pas…) arrête tout, avec le numéro de ligne. */
    static List<Conversation> lire(List<String> lignes) {
        List<Conversation> r = new ArrayList<>();
        int debut = 0;
        String sujet = null;
        List<String> conds = null;
        List<Replique> reps = null;
        for (int n = 1; n <= lignes.size() + 1; n++) {
            String l = n <= lignes.size() ? lignes.get(n - 1).strip() : "=";
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("=")) {
                if (sujet != null) {
                    if (reps.size() < 2 || reps.size() > 6) throw erreur(debut, "il faut 2 à 6 répliques (" + reps.size() + ")");
                    r.add(new Conversation(debut, sujet, List.copyOf(conds), List.copyOf(reps)));
                }
                if (n > lignes.size()) break;
                int barre = l.indexOf('|');
                if (barre < 0) throw erreur(n, "la barre « | » manque après le sujet");
                debut = n;
                sujet = l.substring(1, barre).strip();
                if (sujet.isEmpty() || sujet.contains(" ")) throw erreur(n, "sujet vide ou avec un espace");
                conds = new ArrayList<>();
                for (String c : l.substring(barre + 1).split(",")) {
                    c = c.strip();
                    if (c.isEmpty()) continue;
                    if (!connue(c)) throw erreur(n, "condition inconnue « " + c + " »");
                    conds.add(c);
                }
                reps = new ArrayList<>();
                continue;
            }
            if (sujet == null) throw erreur(n, "réplique hors d'un bloc");
            int barre = l.indexOf('|');
            String qui = barre < 0 ? "" : l.substring(0, barre).strip(), texte = barre < 0 ? "" : l.substring(barre + 1).strip();
            boolean deA = reps.size() % 2 == 0;
            if (!qui.equals(deA ? "A" : "B")) throw erreur(n, "il faut « " + (deA ? "A" : "B") + " | texte » (A et B alternent, A d'abord)");
            if (texte.isEmpty()) throw erreur(n, "réplique vide");
            Matcher m = VARIABLE.matcher(texte);
            while (m.find()) if (!VARIABLES.contains(m.group(1))) throw erreur(n, "variable inconnue {" + m.group(1) + "}");
            reps.add(new Replique(deA, texte));
        }
        return r;
    }

    static IllegalStateException erreur(int ligne, String quoi) { return new IllegalStateException("conversations.txt ligne " + ligne + " : " + quoi); }

    /**
     * Ce qui est vrai pour A et B (l'heure de 0 à 23, le temps qu'il fait là où ils sont). Faim, fatigue, moral, paie et
     * danger passé valent pour le couple : il suffit que l'un des deux soit concerné.
     */
    static Set<String> contexte(int heure, boolean pluie, boolean orage, boolean neige, Personne a, Personne b) {
        Set<String> c = new HashSet<>();
        boolean nuit = OrdresLongs.vrai("NUIT", heure, false, false);
        if (OrdresLongs.vrai("MATIN", heure, false, false)) c.add("matin");
        if (OrdresLongs.vrai("SOIR", heure, false, false)) c.add("soir");
        if (nuit) c.add("nuit");
        if (neige) c.add("neige");
        else if (pluie) c.add("pluie");
        if (orage) c.add("orage");
        if (!nuit && !pluie && !neige && !orage) c.add("beau_temps");
        for (Personne p : List.of(a, b)) {
            if (p.payeAujourdhui()) c.add("paie_recue");
            if (p.impayes() > 0) c.add("paie_en_retard");
            if (p.faim() <= FAIM) c.add("faim");
            if (p.fatigue() >= FATIGUE) c.add("fatigue");
            if (p.moral() < MORAL_BAS) c.add("moral_bas");
            if (p.moral() >= MORAL_HAUT) c.add("moral_haut");
            if (p.apresDanger()) c.add("apres_danger");
        }
        c.add("metier_a=" + a.metier());
        c.add("metier_b=" + b.metier());
        if (a.metier().equals(b.metier())) c.add("meme_metier");
        c.add("temperament_a=" + a.temperament());
        c.add("temperament_b=" + b.temperament());
        return c;
    }

    /**
     * Une conversation possible dans l'un des contextes (chacun : un sens, qui est A), au hasard, une conversation à n
     * conditions pesant 3^n : les plus précises passent le plus souvent, les autres restent possibles. Jamais une des
     * {@code recentes} de la colonie, sauf s'il ne reste qu'elles (alors la plus ancienne). Null si aucune ne va.
     */
    static Choix choisir(List<Conversation> banque, List<Set<String>> contextes, Collection<Integer> recentes, DoubleSupplier hasard) {
        List<Choix> possibles = new ArrayList<>();
        for (int i = 0; i < banque.size(); i++)
            for (int s = 0; s < contextes.size(); s++)
                if (banque.get(i).possible(contextes.get(s))) possibles.add(new Choix(i, s));
        if (possibles.isEmpty()) return null;
        List<Choix> neuves = possibles.stream().filter(c -> !recentes.contains(c.id())).toList();
        if (neuves.isEmpty()) {
            for (Integer id : recentes) for (Choix c : possibles) if (c.id() == id) return c; // la plus ancienne en tête
            return possibles.get(0);
        }
        double total = 0;
        for (Choix c : neuves) total += poids(banque.get(c.id()));
        double x = hasard.getAsDouble() * total;
        for (Choix c : neuves) if ((x -= poids(banque.get(c.id()))) < 0) return c;
        return neuves.get(neuves.size() - 1);
    }

    static double poids(Conversation c) { return Math.pow(3, c.conditions().size()); }

    /** Note une conversation jouée par la colonie (les plus anciennes en tête, {@link #RECENTES} au plus). */
    static void noter(Deque<Integer> recentes, int id) {
        recentes.remove(id);
        recentes.addLast(id);
        while (recentes.size() > RECENTES) recentes.removeFirst();
    }

    static Deque<Integer> nouvelles() { return new ArrayDeque<>(); }

    /** Le texte avec ses variables remplacées ({a}, {b}, {chef}, {metier_a}, {metier_b}). */
    static String remplir(String texte, Map<String, String> valeurs) {
        Matcher m = VARIABLE.matcher(texte);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(valeurs.getOrDefault(m.group(1), m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Le temps de lire une réplique avant la suivante, en ticks : 2 à 4 s selon la longueur. */
    static int delai(String texte) { return Math.clamp(24 + texte.length(), 40, 80); }

    static {
        charger();
    }
}

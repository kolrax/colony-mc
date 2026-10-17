package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce qu'un ouvrier sait des autres colonies (lot 14, cahier 7.6) : ce qu'il a vu de ses yeux en passant près d'elles ou
 * en les espionnant, et ce qu'on lui a raconté (bouche à oreille). Il ne dit jamais que ça : sans fait, « je n'ai rien
 * appris ». 20 faits au plus ; un fait plus récent sur le même chef et le même sujet remplace l'ancien. Pur Java : la
 * lecture des questions et de l'ordre d'espionner, le récit daté, la ligne donnée à l'IA, la sauvegarde. Le jeu est dans
 * {@link Espions}.
 */
final class Rumeurs {
    static final int MAX = 20;

    enum Genre { NOMBRE, METIERS, POSTE, GUERRE, MORT, NAISSANCE, DEBAUCHE, BOUTIQUE, ESPION }

    enum Source { VU, ENTENDU }

    /**
     * Un fait sur la colonie du chef {@code sujet}. {@code valeur} : le nombre d'ouvriers vus, les métiers en texte, « direction|distance »
     * du poste, le chef ennemi, le prénom du mort, du nouveau-né, de l'ouvrier débauché, du boutiquier ou de l'espion. {@code jour} :
     * le jour de jeu où il a été vu (de première main) ; {@code de} : qui le lui a dit (vide s'il l'a vu).
     */
    record Fait(String sujet, Genre genre, String valeur, long jour, Source source, String de) {
        boolean meme(Fait o) { return genre == o.genre && sujet.equalsIgnoreCase(o.sujet); }
    }

    /** Ce qu'on lui demande : sur un chef nommé ({@code sujet}), ou toutes les nouvelles ({@code sujet} nul). */
    record Question(String sujet) {}

    /** Les répliques (rumeurs.txt) : la clé et ses variables, null si elle manque (on prend alors le texte de secours). */
    interface Textes { String tirer(String cle, Map<String, String> valeurs); }

    final List<Fait> liste = new ArrayList<>();

    // --- Garder ------------------------------------------------------------------------------------------------

    /** Note un fait. Un fait plus ancien que celui qu'il a déjà (même chef, même sujet) est ignoré ; vrai s'il l'a gardé. */
    boolean noter(Fait f) {
        if (f.sujet == null || f.sujet.isBlank()) return false;
        for (Fait x : liste) {
            if (!x.meme(f)) continue;
            // Le même jour, ce qu'il a vu l'emporte sur ce qu'on lui dit ; vu à nouveau, il met à jour.
            if (x.jour > f.jour || x.jour == f.jour && x.source == Source.VU && f.source == Source.ENTENDU) return false;
        }
        liste.removeIf(x -> x.meme(f));
        liste.add(f);
        while (liste.size() > MAX) liste.remove(liste.stream().min(Comparator.comparingLong(Fait::jour)).orElseThrow()); // le plus ancien
        return true;
    }

    /** Un fait vu de ses yeux, ce jour. */
    boolean voir(String sujet, Genre g, String valeur, long jour) { return noter(new Fait(sujet, g, valeur, jour, Source.VU, "")); }

    /** {@code de} lui raconte ce fait : il le garde de seconde main, à la date où il a été vu. */
    boolean entendre(Fait f, String de) { return noter(new Fait(f.sujet, f.genre, f.valeur, f.jour, Source.ENTENDU, de)); }

    /** Sait-il déjà ce fait-là, aussi récent ? */
    boolean sait(Fait f) { return liste.stream().anyMatch(x -> x.meme(f) && x.jour >= f.jour); }

    /** Ce qu'il sait sur ce chef (le plus récent d'abord). */
    List<Fait> sur(String sujet) {
        return liste.stream().filter(f -> f.sujet.equalsIgnoreCase(sujet)).sorted(Comparator.comparingLong(Fait::jour).reversed()).toList();
    }

    /** Le fait de ce genre sur ce chef, ou null. */
    Fait fait(String sujet, Genre g) {
        for (Fait f : liste) if (f.genre == g && f.sujet.equalsIgnoreCase(sujet)) return f;
        return null;
    }

    /**
     * Un fait à raconter à {@code autre} (qu'il ignore, ou ne sait que d'avant), parmi ceux que {@code permis} laisse dire :
     * un des trois plus récents, au hasard. Null s'il n'a rien de neuf pour lui.
     */
    Fait aTransmettre(Rumeurs autre, Predicate<Fait> permis, DoubleSupplier hasard) {
        List<Fait> l = new ArrayList<>(liste.stream().filter(f -> permis.test(f) && !autre.sait(f)).toList());
        if (l.isEmpty()) return null;
        l.sort(Comparator.comparingLong(Fait::jour).reversed());
        return l.get(Math.min(Math.min(3, l.size()) - 1, (int) (hasard.getAsDouble() * Math.min(3, l.size()))));
    }

    // --- Ce qu'on lui demande -------------------------------------------------------------------------------------

    static final String[] NOUVELLES = {"quelles nouvelles", "quelles sont les nouvelles", "quelles sont tes nouvelles", "des rumeurs", "une rumeur",
            "les rumeurs", "quelles rumeurs", "des ragots", "un ragot", "des potins", "tu as entendu quelque chose", "as tu entendu quelque chose",
            "t as entendu quelque chose", "tu as entendu des choses", "qu as tu entendu", "qu est ce que tu as entendu", "tu as entendu quoi",
            "qu as tu appris", "qu est ce que tu as appris", "tu as appris quelque chose", "as tu appris quelque chose", "que dit on", "on dit quoi",
            "qu est ce qu on dit", "des nouvelles des autres", "des nouvelles des colonies", "des nouvelles des voisins", "quoi de neuf chez les voisins",
            "que se passe t il ailleurs", "les nouvelles des voisins"};
    /** « Que sais-tu de Raoul ? », « qu'as-tu vu chez Raoul ? », « des nouvelles de la colonie de Raoul ? ». */
    static final Pattern SUR = Pattern.compile(" (?:que sais tu|qu est ce que tu sais|tu sais quoi|tu sais quelque chose|sais tu quelque chose|tu sais des choses"
            + "|qu as tu vu|qu est ce que tu as vu|tu as vu quoi|qu as tu appris|qu as tu entendu|tu as entendu quelque chose|as tu entendu quelque chose"
            + "|des nouvelles|quelles nouvelles|tu as des nouvelles|as tu des nouvelles|parle moi|tu as entendu parler|as tu entendu parler|que dit on|on dit quoi"
            + "|quoi de neuf|tu connais quelque chose)"
            + " (?:de |d |du |sur |chez |a propos de |a propos d |au sujet de |au sujet d )(?:la colonie |le poste |le village |le camp |les gens |les terres |chez )?(?:de |d |du )?([a-z0-9_]+) ");
    /** Des mots qui ne sont pas un chef (« des nouvelles de ton travail », « parle-moi de toi »). */
    static final Set<String> PAS_UN_NOM = Set.of("lui", "elle", "eux", "elles", "ca", "cela", "ceci", "toi", "moi", "nous", "vous", "la", "le", "les", "l",
            "un", "une", "des", "ton", "ta", "tes", "notre", "nos", "votre", "vos", "son", "sa", "ses", "leur", "leurs", "mon", "ma", "mes", "ce", "cet", "cette",
            "ces", "tout", "rien", "quoi", "qui", "travail", "chantier", "mission", "missions", "metier", "mine", "foret", "journee", "hier", "aujourd", "demain",
            "nouveau", "neuf", "autres", "voisins", "colonies", "personne", "quelqu", "quelque");

    /** Une question sur les autres colonies, ou null. */
    static Question question(String phrase) {
        String n = Ordres.normaliser(phrase);
        Matcher m = SUR.matcher(n);
        while (m.find()) {
            if (!PAS_UN_NOM.contains(m.group(1))) return new Question(nomTel(phrase, m.group(1)));
        }
        return Ordres.dit(n, NOUVELLES) ? new Question(null) : null;
    }

    /** Le nom tel qu'il est écrit dans la phrase (« raoul » → « Raoul »). */
    static String nomTel(String phrase, String normalise) {
        for (String mot : phrase.split("[^\\p{L}0-9_]+")) if (!mot.isEmpty() && Ordres.normaliser(mot).strip().equals(normalise)) return mot;
        return Character.toUpperCase(normalise.charAt(0)) + normalise.substring(1);
    }

    static final Pattern ESPIONNER = Pattern.compile(" (?:espionn\\w*|epie|epies|epiez|epier) (?:la colonie |le poste |le village |le camp |les gens |les terres |chez )?(?:de |d |du )?([a-z0-9_]+) ");
    static final Pattern NEGATION = Pattern.compile(" (?:ne|n|pas|jamais|plus) ");

    /** « Va espionner la colonie de Raoul », « espionne Raoul » : le chef à espionner ; null sinon (question, négation, sans nom). */
    static String espionner(String phrase) {
        if (phrase.contains("?")) return null;
        String n = Ordres.normaliser(phrase);
        Matcher m = ESPIONNER.matcher(n);
        if (!m.find() || PAS_UN_NOM.contains(m.group(1)) || NEGATION.matcher(n.substring(0, m.start() + 1)).find()) return null;
        return nomTel(phrase, m.group(1));
    }

    /** Le reste de la phrase sans l'ordre d'espionner (pour les autres ordres). */
    static String sansEspionner(String phrase) {
        return phrase.replaceAll("(?iu)\\b(?:va |allez |pars |file )?(?:espionn\\p{L}*|épie[rz]?|epie[rz]?|épies|epies)\\b[^.;!,]*", " ").replaceAll("\\s{2,}", " ").strip();
    }

    // --- Raconter --------------------------------------------------------------------------------------------------

    /** « aujourd'hui », « hier », « il y a 3 jours ». */
    static String quand(long jourFait, long jour) {
        long d = jour - jourFait;
        return d <= 0 ? "aujourd'hui" : d == 1 ? "hier" : "il y a " + d + " jours";
    }

    static final String[] CHIFFRES = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix"};

    /** « deux mineurs et un bûcheron » : les métiers vus, les plus nombreux d'abord (trois au plus), noms en minuscules. */
    static String metiers(Map<String, Integer> comptes) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(comptes.entrySet());
        l.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> r = new ArrayList<>();
        for (var e : l.subList(0, Math.min(3, l.size()))) {
            int n = e.getValue();
            String nom = e.getKey().toLowerCase(java.util.Locale.ROOT);
            r.add(Souvenirs.nombre(n, nom).replaceFirst("^\\d+", n <= 10 ? CHIFFRES[n] : String.valueOf(n)));
        }
        if (r.size() <= 1) return r.isEmpty() ? "" : r.get(0);
        return String.join(", ", r.subList(0, r.size() - 1)) + " et " + r.get(r.size() - 1);
    }

    /** « 300 pas » : la distance arrondie (à 10 pas près sous 100, à 50 au-delà). */
    static String distance(double blocs) {
        long d = blocs < 100 ? Math.max(10, Math.round(blocs / 10) * 10) : Math.round(blocs / 50) * 50;
        return d + " pas";
    }

    static String cle(Genre g) {
        return switch (g) {
            case NOMBRE -> "rumeur_vu_nombre";
            case METIERS -> "rumeur_vu_metiers";
            case POSTE -> "rumeur_vu_poste";
            default -> "rumeur_" + g.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Le texte de secours de chaque clé, si rumeurs.txt ne l'a pas. */
    static final Map<String, String> SECOURS = Map.ofEntries(
            Map.entry("rumeur_vu_nombre", "{jours}, j'ai vu {nombre} ouvriers chez {nom}."),
            Map.entry("rumeur_vu_metiers", "{jours}, chez {nom}, j'ai vu {metiers}."),
            Map.entry("rumeur_vu_poste", "Le poste de {nom} est {direction}, à {distance} d'ici."),
            Map.entry("rumeur_guerre", "{nom} et {autre} sont en guerre."),
            Map.entry("rumeur_mort", "Un ouvrier de {nom} est mort."),
            Map.entry("rumeur_naissance", "Un enfant est né chez {nom}."),
            Map.entry("rumeur_debauche", "{nom} essaie de débaucher les ouvriers des autres."),
            Map.entry("rumeur_boutique", "{nom} tient boutique."),
            Map.entry("rumeur_espion", "{jours}, un ouvrier de {nom} rôdait près de chez nous."),
            Map.entry("rumeur_entendu_intro", "{de} m'a raconté ceci :"),
            Map.entry("rumeur_rien", "{nom} ? Je ne sais rien de lui."),
            Map.entry("rumeur_rien_du_tout", "Je n'ai aucune nouvelle."),
            Map.entry("rumeur_tais_chef", "Les affaires de {chef} restent chez {chef}."),
            Map.entry("rumeur_trahit_chef", "Bon… entre nous, alors :"),
            Map.entry("espion_part", "J'y vais, chef. Je reviens avec des nouvelles de {nom}."),
            Map.entry("espion_inconnu", "Je ne sais pas où est la colonie de {nom}."),
            Map.entry("espion_retour", "Je reviens de chez {nom}. Écoute :"),
            Map.entry("espion_repere", "Un ouvrier de {nom} m'a repéré. J'ai dû filer."),
            Map.entry("espion_refus_enfant", "Je suis trop jeune pour ça."),
            Map.entry("rumeur_passe", "{b}, j'ai des nouvelles de {nom}."));

    /** La réplique {@code cle} ({@code tx}, sinon le secours), variables remplies, majuscule en tête, « 1 ouvriers » au singulier. */
    static String dire(Textes tx, String cle, Map<String, String> v) {
        String s = tx == null ? null : tx.tirer(cle, v);
        if (s == null) s = Conversations.remplir(SECOURS.getOrDefault(cle, ""), v);
        s = s.replaceAll("\\b1 ouvriers\\b", "1 ouvrier").replaceAll("\\bun ouvriers\\b", "un ouvrier");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Les variables d'un fait. */
    static Map<String, String> valeurs(Fait f, long jour) {
        Map<String, String> v = new HashMap<>();
        v.put("nom", f.sujet);
        v.put("jours", quand(f.jour, jour));
        switch (f.genre) {
            case NOMBRE -> v.put("nombre", f.valeur);
            case METIERS -> v.put("metiers", f.valeur);
            case POSTE -> {
                String[] p = f.valeur.split("\\|", 2);
                v.put("direction", p[0]);
                v.put("distance", p.length > 1 ? p[1] : "quelques pas");
            }
            case GUERRE -> v.put("autre", f.valeur);
            default -> {}
        }
        return v;
    }

    /** Une phrase sur un fait ; de seconde main, précédée de qui le lui a dit. */
    static String phrase(Fait f, long jour, Textes tx) {
        String s = dire(tx, cle(f.genre), valeurs(f, jour));
        if (f.source == Source.ENTENDU && !f.de.isBlank()) s = dire(tx, "rumeur_entendu_intro", Map.of("de", f.de)) + " " + s;
        return s;
    }

    /**
     * Sa réponse : une à trois phrases sur les faits que {@code permis} laisse dire, les plus récents d'abord (sur le chef
     * demandé, ou sur tous). Rien d'inventé : sans fait, « je n'ai rien appris sur lui » (rumeur_rien, rumeur_rien_du_tout).
     */
    String raconter(Question q, long jour, Textes tx, Predicate<Fait> permis) {
        List<Fait> l = new ArrayList<>(liste.stream().filter(f -> permis.test(f) && (q.sujet() == null || f.sujet.equalsIgnoreCase(q.sujet()))).toList());
        if (l.isEmpty()) return q.sujet() == null ? dire(tx, "rumeur_rien_du_tout", Map.of()) : dire(tx, "rumeur_rien", Map.of("nom", q.sujet()));
        l.sort(Comparator.comparingLong(Fait::jour).reversed().thenComparing(f -> f.source)); // à date égale, ce qu'il a vu d'abord
        List<String> r = new ArrayList<>();
        for (Fait f : l.subList(0, Math.min(3, l.size()))) r.add(phrase(f, jour, tx));
        return String.join(" ", r);
    }

    /** Pour l'IA : 5 faits au plus, courts (vide s'il ne sait rien). */
    String pourIA(long jour) {
        if (liste.isEmpty()) return "";
        List<Fait> l = new ArrayList<>(liste);
        l.sort(Comparator.comparingLong(Fait::jour).reversed());
        List<String> r = new ArrayList<>();
        for (Fait f : l.subList(0, Math.min(5, l.size()))) {
            String s = phrase(new Fait(f.sujet, f.genre, f.valeur, f.jour, Source.VU, ""), jour, null);
            r.add((s.length() > 70 ? s.substring(0, 69) + "…" : s) + (f.source == Source.ENTENDU ? " (dit par " + f.de + ")" : " (vu)"));
        }
        return String.join(" ; ", r);
    }

    // --- Sauvegarde ------------------------------------------------------------------------------------------------

    /** Un fait par ligne : « GENRE;SOURCE;jour;sujet;de;valeur ». */
    List<String> enTextes() {
        List<String> r = new ArrayList<>();
        for (Fait f : liste) r.add(f.genre + ";" + f.source + ";" + f.jour + ";" + f.sujet + ";" + f.de + ";" + f.valeur);
        return r;
    }

    void lireTextes(List<String> lignes) {
        liste.clear();
        for (String x : lignes) {
            String[] p = x.split(";", 6);
            if (p.length < 6) continue;
            try { liste.add(new Fait(p[3], Genre.valueOf(p[0]), p[5], Long.parseLong(p[2]), Source.valueOf(p[1]), p[4])); }
            catch (IllegalArgumentException e) { /* un fait illisible est oublié */ }
        }
        while (liste.size() > MAX) liste.remove(0);
    }

    /** Pour les tests et le comptage : les métiers par nom. */
    static Map<String, Integer> compter(List<String> noms) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String n : noms) m.merge(n, 1, Integer::sum);
        return m;
    }
}

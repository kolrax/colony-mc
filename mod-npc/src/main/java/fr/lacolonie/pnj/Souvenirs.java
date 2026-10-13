package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * La mémoire longue d'un ouvrier (cahier 7.5, 14.7, 14.14) : ce que son chef lui a promis, ses insultes, ses
 * compliments, ses cadeaux, ses coups, ses ordres longs et ses marchés, datés en jours de jeu. 40 souvenirs au plus :
 * les plus anciens et les moins marquants partent d'abord. Pur Java : la lecture des phrases du chef, le récit
 * (« Tu m'as traité de bon à rien il y a 2 jours… ») et la ligne donnée à l'IA. Sauvegardé dans le NBT de l'ouvrier
 * ({@link #enTextes()}, {@link #lireTextes}). Rien ne tourne à chaque tick : tout se fait quand le chef parle.
 */
final class Souvenirs {
    static final int MAX = 40;
    /** Longueur d'un souvenir (la phrase du chef, résumée). */
    static final int LONGUEUR = 80;

    enum Genre {
        COUP(5), INSULTE(4), PROMESSE(4), MARCHE(3), CADEAU(3), ORDRE(2), COMPLIMENT(1);
        /** Combien il marque : un coup s'oublie moins vite qu'un compliment. */
        final int poids;
        Genre(int poids) { this.poids = poids; }
    }

    /** Un souvenir : quoi, quel jour de jeu, à quelle heure (0-23), et la phrase du chef résumée. */
    record Souvenir(Genre genre, long jour, int heure, String texte) {
        /** Plus c'est grand, plus il le garde : ce qui marque, et ce qui est récent. */
        long valeur() { return genre.poids * 3L + jour; }
    }

    final List<Souvenir> liste = new ArrayList<>();
    /** Le dernier jour où un compliment ou un cadeau lui a remonté le moral (une fois par jour, pas plus). */
    long jourBonus = -1;

    // --- Les mots du chef -------------------------------------------------------------------------------------

    /** Les insultes, telles qu'il les répète (la lecture se fait sans accents ni majuscules). */
    static final String[] INSULTES = {"imbécile", "idiot", "idiote", "bon à rien", "bonne à rien", "propre à rien", "crétin", "abruti", "minable",
            "incapable", "feignant", "feignasse", "fainéant", "vaurien", "maraud", "gueux", "sot", "sotte", "benêt", "nigaud", "larve", "vermine",
            "triple buse", "bouffon", "andouille", "cornichon", "tête de mule", "pignouf", "malotru", "gredin", "lambin", "tire-au-flanc", "misérable"};
    static final String[] PROMESSES = {"je te promets", "je vous promets", "je te le promets", "promis", "c est promis", "je te jure", "parole de chef",
            "je te donnerai", "je te paierai", "je te payerai", "je te ferai", "je t offrirai", "je t apporterai", "je te rendrai", "tu auras",
            "tu recevras", "tu toucheras", "je t augmenterai", "je t augmente"};
    static final String[] PAS_PROMESSE = {"m as promis", "m avais promis", "t ai promis", "je t ai promis", "t avais promis"};
    static final String[] COMPLIMENTS = {"bravo", "bien joue", "merci", "beau travail", "bon travail", "excellent", "parfait", "bien fait",
            "du bon travail", "tu es le meilleur", "t es le meilleur", "felicitations", "je suis fier de toi", "c est du beau travail"};
    static final String[] CADEAUX = {"c est pour toi", "ce sont pour toi", "c est un cadeau", "un cadeau", "je te l offre", "je te les offre", "cadeau",
            "garde le pour toi", "garde les pour toi", "tu peux le garder", "tu peux les garder", "je te le donne", "je te les donne"};
    static final String[] QUESTIONS = {"tu te souviens", "te souviens tu", "tu t en souviens", "t en souviens tu", "tu te rappelles", "te rappelles tu",
            "tu te rappelle", "tu t en rappelles", "de quoi te souviens tu", "tu te souviens de quoi", "qu est ce que je t ai promis", "que t ai je promis",
            "je t ai promis quoi", "qu est ce que je t ai dit", "que t ai je dit", "je t ai dit quoi", "qu est ce que je t avais dit", "que t avais je dit",
            "tu as oublie", "tu n as pas oublie", "as tu oublie", "ta memoire", "tes souvenirs"};

    static boolean un(String n, String... formules) { return Ordres.dit(n, formules); }

    /** Le premier mot d'insulte de la phrase (normalisée ou non), ou null. Une négation devant (« tu n'es pas un idiot ») l'annule. */
    static String insulte(String phrase) {
        String n = Ordres.normaliser(phrase);
        for (String m : INSULTES) {
            String x = Ordres.normaliser(m).strip();
            int i = n.indexOf(" " + x + " ");
            if (i < 0) i = n.indexOf(" " + x + "s ");
            if (i < 0) continue;
            String avant = n.substring(Math.max(0, i - 20), i);
            if (avant.contains(" pas ") || avant.contains(" plus ") || avant.contains(" jamais ")) continue;
            return m;
        }
        return null;
    }

    /** Le chef demande ce dont il se souvient (« tu te souviens… ? », « qu'est-ce que je t'ai promis ? »). */
    static boolean question(String phrase) { return un(Ordres.normaliser(phrase), QUESTIONS); }

    static boolean promesse(String n) { return un(n, PROMESSES) && !un(n, PAS_PROMESSE); }

    /**
     * Ce que la phrase du chef laisse comme souvenirs (hors ordres, coups et marchés, notés là où ils arrivent). Une
     * question sur ses souvenirs n'en laisse aucun (« qu'est-ce que je t'ai promis ? » n'est pas une promesse).
     */
    static List<Genre> lire(String phrase) {
        List<Genre> r = new ArrayList<>();
        String n = Ordres.normaliser(phrase);
        if (question(phrase)) return r;
        if (insulte(phrase) != null) r.add(Genre.INSULTE);
        if (promesse(n)) r.add(Genre.PROMESSE);
        if (un(n, CADEAUX)) r.add(Genre.CADEAU);
        else if (un(n, COMPLIMENTS) && !r.contains(Genre.INSULTE)) r.add(Genre.COMPLIMENT);
        return r;
    }

    /** La phrase, sans blancs en trop, coupée à 80 caractères. */
    static String resumer(String phrase) {
        String s = phrase.strip().replaceAll("\\s+", " ");
        return s.length() > LONGUEUR ? s.substring(0, LONGUEUR - 1).strip() + "…" : s;
    }

    // --- Garder ------------------------------------------------------------------------------------------------

    /** Note un souvenir (pas deux fois le même genre et le même texte le même jour). Au-delà de 40, le moins précieux part. */
    void noter(Genre g, long jour, int heure, String texte) {
        String t = resumer(texte);
        if (liste.stream().anyMatch(s -> s.genre == g && s.jour == jour && s.texte.equals(t))) return;
        liste.add(new Souvenir(g, jour, heure, t));
        while (liste.size() > MAX)
            liste.remove(liste.stream().min(Comparator.comparingLong(Souvenir::valeur)).orElseThrow()); // à égalité, le plus ancien
    }

    /** Un compliment ou un cadeau remonte le moral une fois par jour au plus : vrai si c'est la première fois aujourd'hui. */
    boolean bonus(long jour) {
        if (jourBonus == jour) return false;
        jourBonus = jour;
        return true;
    }

    // --- Raconter ----------------------------------------------------------------------------------------------

    /** « tout à l'heure », « ce matin », « hier », « il y a 3 jours ». */
    static String quand(Souvenir s, long jour, int heure) {
        long d = jour - s.jour;
        if (d <= 0) return s.heure < 12 && heure >= 13 ? "ce matin" : "tout à l'heure";
        if (d == 1) return "hier";
        return "il y a " + d + " jours";
    }

    static String majuscule(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** Une phrase sur un souvenir, dans sa bouche. */
    static String phrase(Souvenir s, long jour, int heure) {
        String q = quand(s, jour, heure);
        return switch (s.genre) {
            case INSULTE -> {
                String m = insulte(s.texte);
                yield "Tu m'as traité " + (m == null ? "comme un moins que rien" : de(m) + m) + " " + q + ", je n'ai pas oublié.";
            }
            case PROMESSE -> jour - s.jour > 2 ? "Tu m'avais promis « " + s.texte + " », " + q + ". J'attends toujours."
                    : majuscule(q) + ", tu m'as promis « " + s.texte + " ». Je m'en souviens.";
            case COUP -> "Tu as levé la main sur moi " + q + ". Ça, je ne l'oublie pas.";
            case CADEAU -> majuscule(q) + ", tu m'as fait présent de " + s.texte + ".";
            case COMPLIMENT -> majuscule(q) + ", tu m'as félicité : ça m'a fait chaud au cœur.";
            case ORDRE -> majuscule(q) + ", tu m'as dit : « " + s.texte + " ».";
            case MARCHE -> majuscule(q) + ", nous avons conclu un marché : " + s.texte + ".";
        };
    }

    /** « de » ou « d' » devant un mot. */
    static String de(String mot) { return "aeiouyhéèêâîôû".indexOf(mot.charAt(0)) >= 0 ? "d'" : "de "; }

    /**
     * Sa réponse à une question sur ses souvenirs : 1 à 3 souvenirs, ceux que vise la question (« promis » : promesses
     * et marchés ; « hier » : ceux d'hier ; « frappé », « insulté », « donné »…), sinon les plus marquants et récents.
     */
    String raconter(String question, long jour, int heure) {
        String n = Ordres.normaliser(question);
        List<Souvenir> l = new ArrayList<>(liste);
        List<Genre> vises = new ArrayList<>();
        if (un(n, "promis", "promesse", "promesses", "promis quoi")) { vises.add(Genre.PROMESSE); vises.add(Genre.MARCHE); }
        if (un(n, "insulte", "insultes", "traite", "injure", "injures")) vises.add(Genre.INSULTE);
        if (un(n, "frappe", "coup", "coups", "battu", "cogne")) vises.add(Genre.COUP);
        if (un(n, "donne", "cadeau", "cadeaux", "offert", "present")) vises.add(Genre.CADEAU);
        if (un(n, "ordre", "ordres", "demande", "mission", "missions")) vises.add(Genre.ORDRE);
        if (!vises.isEmpty()) l.removeIf(s -> !vises.contains(s.genre));
        if (un(n, "hier")) l.removeIf(s -> s.jour != jour - 1);
        else if (un(n, "aujourd hui", "ce matin", "tout a l heure", "tantot")) l.removeIf(s -> s.jour != jour);
        if (l.isEmpty()) return vises.contains(Genre.PROMESSE) ? "Tu ne m'as rien promis, chef, que je sache."
                : "Je ne me souviens de rien de particulier, chef.";
        l.sort(Comparator.comparingLong(Souvenir::valeur).reversed());
        List<String> r = new ArrayList<>();
        for (Souvenir s : l.subList(0, Math.min(3, l.size()))) r.add(phrase(s, jour, heure));
        return String.join(" ", r);
    }

    /** Pour l'IA : ses 5 souvenirs les plus marquants et récents, 50 caractères chacun au plus (vide s'il n'en a pas). */
    String pourIA(long jour, int heure) {
        if (liste.isEmpty()) return "";
        List<Souvenir> l = new ArrayList<>(liste);
        l.sort(Comparator.comparingLong(Souvenir::valeur).reversed());
        List<String> r = new ArrayList<>();
        for (Souvenir s : l.subList(0, Math.min(5, l.size())))
            r.add(s.genre.name().toLowerCase() + " (" + quand(s, jour, heure) + ") : " + (s.texte.length() > 50 ? s.texte.substring(0, 49) + "…" : s.texte));
        return String.join(" ; ", r);
    }

    // --- Sauvegarde --------------------------------------------------------------------------------------------

    /** Un souvenir par ligne : « GENRE;jour;heure;texte ». */
    List<String> enTextes() {
        List<String> r = new ArrayList<>();
        for (Souvenir s : liste) r.add(s.genre.name() + ";" + s.jour + ";" + s.heure + ";" + s.texte);
        return r;
    }

    void lireTextes(List<String> lignes) {
        liste.clear();
        for (String x : lignes) {
            String[] p = x.split(";", 4);
            if (p.length < 4) continue;
            try { liste.add(new Souvenir(Genre.valueOf(p[0]), Long.parseLong(p[1]), Integer.parseInt(p[2]), p[3])); }
            catch (IllegalArgumentException e) { /* un souvenir illisible est oublié */ }
        }
        while (liste.size() > MAX) liste.remove(0);
    }
}

package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Lot 13, cahier 5.6, 5.7 et 4.2 : l'âge, l'enfance, l'apprentissage, la vieillesse et la naissance, sans le jeu (testés
 * seuls). Un adulte prend un an par jour réel (72 jours du jeu), un enfant un an tous les 4 jours du jeu : une quinzaine
 * d'heures réelles de la naissance à l'apprentissage. Le côté serveur : {@link Famille}.
 */
final class Ages {
    private Ages() {}

    /** Moins de 12 ans : un enfant ; de 12 à 16 : un apprenti ; puis un ouvrier ; vieux à 60, très vieux à 70. */
    static final int APPRENTI = 12, ADULTE = 16, VIEUX = 60, TRES_VIEUX = 70;
    /** Un jour du jeu, en ticks ; une minute réelle (le pas du calcul) : 1200 ticks. */
    static final long JOUR = 24000, MINUTE = 1200;

    /** Les réglages (« ages » dans reglages.json). */
    public static class Reglage {
        /** Jours du jeu par an pour un adulte (72 = un jour réel) et pour un enfant de moins de 16 ans. */
        public double jours_par_an_adulte = 72, jours_par_an_enfant = 4;
        /** Chance, par jour du jeu et par couple, d'avoir un enfant (0,11 : un tous les neuf jours environ). */
        public double naissances_par_jour = 0.11;
        /** Au plus tant d'enfants par couple. */
        public int enfants_max = 3;
        /** L'âge où il s'éteint, tiré à son arrivée entre ces deux bornes. */
        public int fin_min = 75, fin_max = 92;

        /** Des réglages absents ou extravagants reprennent des valeurs sûres. */
        static Reglage valide(Reglage r) {
            Reglage d = new Reglage();
            if (r == null) return d;
            r.jours_par_an_adulte = sur(r.jours_par_an_adulte, d.jours_par_an_adulte, 0.01, 100_000);
            r.jours_par_an_enfant = sur(r.jours_par_an_enfant, d.jours_par_an_enfant, 0.01, 100_000);
            r.naissances_par_jour = sur(r.naissances_par_jour, d.naissances_par_jour, 0, 1);
            r.enfants_max = Math.clamp(r.enfants_max, 0, 20);
            r.fin_min = Math.clamp(r.fin_min, ADULTE + 1, 500);
            r.fin_max = Math.clamp(r.fin_max, r.fin_min, 500);
            return r;
        }

        private static double sur(double v, double defaut, double min, double max) { return Double.isNaN(v) ? defaut : Math.clamp(v, min, max); }
    }

    /** Ses années après {@code ticks} du jeu : vite jusqu'à 16 ans, puis au pas des adultes. */
    static double avancer(double age, long ticks, Reglage r) {
        if (ticks <= 0) return age;
        double jours = ticks / (double) JOUR;
        if (age < ADULTE) {
            double enfance = (ADULTE - age) * r.jours_par_an_enfant; // jours du jeu jusqu'à 16 ans
            if (jours < enfance) return age + jours / r.jours_par_an_enfant;
            jours -= enfance;
            age = ADULTE;
        }
        return age + jours / r.jours_par_an_adulte;
    }

    /** L'âge qu'il dit, en années pleines. */
    static int ans(double age) { return (int) Math.floor(Math.max(0, age)); }

    /** Un âge tiré entre deux bornes. */
    static double tirer(double min, double max, DoubleSupplier tirage) { return min + (max - min) * tirage.getAsDouble(); }

    enum Etape { ENFANT, APPRENTI, ADULTE, VIEUX }

    static Etape etape(double age) {
        return age < APPRENTI ? Etape.ENFANT : age < ADULTE ? Etape.APPRENTI : age < VIEUX ? Etape.ADULTE : Etape.VIEUX;
    }

    /** Le seuil franchi entre deux âges (12, 16, 60 ou 70), le plus haut ; -1 aucun. */
    static int passage(double avant, double apres) {
        int r = -1;
        for (int s : new int[]{APPRENTI, ADULTE, VIEUX, TRES_VIEUX}) if (avant < s && apres >= s) r = s;
        return r;
    }

    /** Tous les seuils franchis entre deux âges, dans l'ordre (de 11,9 à 16,2 : 12 puis 16). */
    static List<Integer> passages(double avant, double apres) {
        List<Integer> l = new ArrayList<>();
        for (int s : new int[]{APPRENTI, ADULTE, VIEUX, TRES_VIEUX}) if (avant < s && apres >= s) l.add(s);
        return l;
    }

    /** Sa taille : la moitié d'un adulte à la naissance, puis il grandit jusqu'à 16 ans. */
    static double echelle(double age) { return 0.5 + 0.5 * Math.clamp(age, 0, ADULTE) / ADULTE; }

    /** Son ouvrage : la moitié avant 16 ans (apprenti), un peu moins à 60 ans, moins encore à 70. */
    static double vitesse(double age) {
        if (age < 0) return 1; // âge inconnu : comme avant
        return age < ADULTE ? 0.5 : age >= TRES_VIEUX ? 0.7 : age >= VIEUX ? 0.85 : 1; // l'enfant ne travaille pas (jamais 0 : on divise par elle)
    }

    /** Les points de vie qu'il perd avec l'âge (2 à 60 ans, 4 à 70). */
    static int vieEnMoins(double age) { return age >= TRES_VIEUX ? 4 : age >= VIEUX ? 2 : 0; }

    /** Sa part du salaire de base : rien enfant, la moitié apprenti. */
    static double paie(double age) { return age < 0 || age >= ADULTE ? 1 : age < APPRENTI ? 0 : 0.5; }

    /** Points d'expérience pour une minute d'ouvrage : un apprenti apprend une fois et demie plus vite. */
    static int experience(double age, long minute) { return age >= APPRENTI && age < ADULTE && minute % 2 == 0 ? 2 : 1; }

    /** L'heure de s'éteindre : l'âge de sa fin passé, au repos de préférence ; au-delà d'un dixième d'année, quoi qu'il fasse. */
    static boolean fin(double age, double fin, boolean repos) { return fin > 0 && age >= fin && (repos || age >= fin + 0.1); }

    /** La chance par minute réelle (un vingtième de jour du jeu) qui donne {@code parJour} par jour du jeu. */
    static double chanceParMinute(double parJour) {
        if (parJour >= 1) return 1;
        if (parJour <= 0) return 0;
        return 1 - Math.pow(1 - parJour, MINUTE / (double) JOUR);
    }

    // --- Femmes, hommes : d'après le prénom (la liste les alterne : un homme, une femme) -------------------------------

    /** Vrai si le prénom est celui d'une femme ({@code prenoms} : un homme, une femme, un homme…) ; inconnu : faux. */
    static boolean femme(String prenom, String[] prenoms) {
        for (int i = 0; i < prenoms.length; i++) if (prenoms[i].equals(prenom)) return i % 2 == 1;
        return false;
    }

    /**
     * Celui des deux qui porte l'enfant (et tente la naissance, pour que le couple ne tire pas deux fois) : la femme ;
     * deux femmes, la première ({@code premier}) ; deux hommes, personne.
     */
    static boolean porte(boolean femme, boolean autreFemme, boolean premier) {
        if (femme && autreFemme) return premier;
        return femme;
    }

    /** Une naissance possible : au travail tous deux, moral correct, un toit, l'âge, la place chez le chef. */
    record Couple(boolean auTravail, int moral, int moralAutre, double ageMere, boolean toit, int enfants, int enfantsMax, int colonie, int plafond) {}

    static final int MORAL_MIN = 40, AGE_MERE_MAX = 45;

    static boolean naissancePossible(Couple c) {
        return c.auTravail() && c.moral() >= MORAL_MIN && c.moralAutre() >= MORAL_MIN && c.ageMere() >= 18 && c.ageMere() <= AGE_MERE_MAX
                && c.toit() && c.enfants() < c.enfantsMax() && c.colonie() < c.plafond();
    }

    /** Le métier qu'il apprendra par défaut : celui de l'un de ses parents. */
    static String metierHerite(String a, String b, double tirage) { return b == null || tirage < 0.5 ? a : b; }

    /** À 16 ans, une chance sur deux de recevoir un talent de l'un de ses parents (null : aucun). */
    static String talentHerite(List<String> deParents, double tirage, double choix) {
        if (deParents.isEmpty() || tirage >= 0.5) return null;
        return deParents.get(Math.min(deParents.size() - 1, (int) (choix * deParents.size())));
    }

    /** Ce qu'il tient de ses parents, à la première personne : « De mes parents, je tiens la main vive. » */
    static String don(String talent) {
        String d = switch (talent == null ? "" : talent) {
            case Talents.HABILE -> "la main vive";
            case Talents.ROBUSTE -> "une carrure solide";
            case Talents.ENDURANT -> "la force de tenir la peine";
            case Talents.SOBRE -> "un petit appétit";
            default -> "";
        };
        return d.isEmpty() ? "" : "De mes parents, je tiens " + d + ".";
    }

    // --- Les proches : « id:Prénom;id:Prénom » dans sa mémoire ------------------------------------------------------

    record Proche(String id, String nom) {}

    static List<Proche> lire(String texte) {
        List<Proche> l = new ArrayList<>();
        if (texte == null || texte.isBlank()) return l;
        for (String x : texte.split(";")) {
            int i = x.indexOf(':');
            if (i > 0 && i < x.length() - 1) l.add(new Proche(x.substring(0, i), x.substring(i + 1)));
        }
        return l;
    }

    static String texte(List<Proche> l) {
        StringBuilder b = new StringBuilder();
        for (Proche p : l) { if (!b.isEmpty()) b.append(';'); b.append(p.id()).append(':').append(p.nom().replace(";", "").replace(":", "")); }
        return b.toString();
    }

    static boolean parmi(String texte, String id) { return lire(texte).stream().anyMatch(p -> p.id().equals(id)); }

    /** Parent et enfant, ou frères et sœurs (un parent en commun) : jamais en couple. Les textes : « parents », « enfants ». */
    static boolean proches(String idA, String parentsA, String enfantsA, String idB, String parentsB, String enfantsB) {
        if (parmi(parentsA, idB) || parmi(enfantsA, idB) || parmi(parentsB, idA) || parmi(enfantsB, idA)) return true;
        for (Proche p : lire(parentsA)) if (parmi(parentsB, p.id())) return true;
        return false;
    }

    /** « Raoul », « Raoul et Agnès », « Raoul, Agnès et Thibaut ». */
    static String noms(List<Proche> l) { return Liens.et(l.stream().map(Proche::nom).toList()); }

    // --- Les questions du chef et l'apprentissage -----------------------------------------------------------------

    enum Question { AGE, PARENTS, ENFANTS }

    /** « Quel âge as-tu ? », « qui sont tes parents ? », « as-tu des enfants ? » (texte normalisé par {@link Ordres#normaliser}). */
    static Question question(String n) {
        if (Ordres.dit(n, "quel age", "ton age", "votre age", "quel est ton age", "combien d ans", "tu as quel age", "t as quel age")) return Question.AGE;
        if (Ordres.dit(n, "tes parents", "ton pere", "ta mere", "tes pere et mere", "ta famille", "tu es le fils", "tu es la fille", "tu es fils", "tu es fille",
                "fils de qui", "fille de qui", "qui t a eleve")) return Question.PARENTS;
        if (Ordres.dit(n, "des enfants", "tes enfants", "ton enfant", "ton fils", "ta fille", "tes fils", "tes filles", "des petits")
                && Ordres.dit(n, "?", "as tu", "tu as", "avez vous", "combien", "qui", "est ce")) return Question.ENFANTS;
        return null;
    }

    /** « Apprends le métier de bûcheron », « deviens fermière », « apprends à pêcher » : le métier dit, sinon null. */
    static String metierAppris(String n, Map<String, List<String[]>> noms) {
        if (!Ordres.dit(n, "apprends", "apprend", "apprendras", "deviens", "deviendras", "tu seras", "apprentissage", "apprenti", "apprentie")) return null;
        if (Ordres.dit(n, "arrete de te former", "va te former", "te former")) return null; // la formation payée ({@link Apprentissage})
        String meilleur = null;
        int pos = Integer.MAX_VALUE;
        for (var e : noms.entrySet())
            for (String[] formes : e.getValue()) {
                int i = n.indexOf(" " + formes[0].replace("*", "") + " ");
                if (i >= 0 && i < pos) { pos = i; meilleur = e.getKey(); }
            }
        return meilleur;
    }

    /** « 34 ans », « 1 an », « quelques mois ». */
    static String ansTexte(double age) {
        int a = ans(age);
        return a == 0 ? "quelques mois" : a == 1 ? "un an" : a + " ans";
    }
}

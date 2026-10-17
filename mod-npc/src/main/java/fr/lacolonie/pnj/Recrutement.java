package fr.lacolonie.pnj;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Lot 13, cahier 5.8 : d'où viennent les chercheurs d'emploi et combien. La réputation d'un chef (un nombre de
 * {@link #MIN} à {@link #MAX}, gardé par {@link Courrier}) monte avec chaque paie versée à l'heure et baisse avec les
 * paies manquées, les grèves, les renvois, les départs et les morts. Elle rend les arrivées plus ou moins nombreuses et,
 * mauvaise, les candidats plus exigeants. Une affiche « On embauche » près du poste et un village voisin en attirent
 * davantage. Pur Java ; le jeu est dans {@code ColoniePnj.arriveeChercheurs}.
 */
final class Recrutement {
    static final int MIN = -50, MAX = 50;
    /** Ce qui fait bouger la réputation. */
    static final int PAIE = 1, IMPAYE = -2, GREVE = -3, RENVOI = -2, DEPART = -4, MORT = -5;
    /**
     * Une affiche donne 30 % de chances en plus, un village voisin 50 % ; tous les bonus ensemble (réputation comprise)
     * ne dépassent jamais une fois et demie la chance de base, pour que les chercheurs restent rares.
     */
    static final double AFFICHE = 1.3, VILLAGE = 1.5, BONUS_MAX = 1.5, CHANCE_MAX = 0.95;
    /** Au plus bas, un candidat demande 30 % de plus. */
    static final double EXIGENCE_MAX = 0.3;

    private Recrutement() {}

    static int reputation(int avant, int delta) { return Math.clamp((long) avant + delta, MIN, MAX); }

    /** La chance qu'un chercheur vienne vers un joueur à chaque passage. */
    static double chance(double base, int reputation, boolean affiche, boolean village) {
        if (!(base > 0)) return 0;
        double bonus = (1 + Math.clamp(reputation, MIN, MAX) / 100.0) * (affiche ? AFFICHE : 1) * (village ? VILLAGE : 1);
        return Math.clamp(base * Math.min(bonus, BONUS_MAX), 0, CHANCE_MAX);
    }

    /** Le facteur de salaire en plus demandé à un chef mal réputé (1 tant que la réputation n'est pas mauvaise). */
    static double exigence(int reputation) {
        int r = Math.clamp(reputation, MIN, MAX);
        return r >= 0 ? 1 : 1 + EXIGENCE_MAX * -r / -MIN;
    }

    /** La réplique (recrutement.txt) que dit un candidat à l'entretien sur ce qu'on raconte du chef. */
    static String palier(int reputation) {
        if (reputation >= 15) return "reputation_bonne";
        if (reputation <= -30) return "reputation_terrible";
        if (reputation <= -10) return "reputation_mauvaise";
        return "reputation_neutre";
    }

    /** Le texte d'un panneau est une affiche d'embauche : « On embauche », « Recrute mineurs »… */
    static boolean affiche(String texte) {
        if (texte == null || texte.isBlank()) return false;
        String t = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return t.contains("embauch") || t.contains("recrut") || t.contains("main d'oeuvre") || t.contains("main-d'oeuvre");
    }
}

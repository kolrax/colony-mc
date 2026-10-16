package fr.lacolonie.pnj;

/**
 * Lot 11, cahier 9.2 : un débutant paie 2 ou 3 ouvriers, un très riche une trentaine. Le salaire demandé croît avec la
 * taille de la colonie : le facteur de la n-ième embauche chez un chef vaut 1 jusqu'à {@code sansSurcout}, puis
 * {@code parOuvrier} de plus par ouvrier au-delà (réglages {@code ouvriers_sans_surcout} et {@code surcout_par_ouvrier}).
 * Il est fixé à l'embauche ({@link Travailleur#surcout}). Pur Java.
 */
final class Cout {
    private Cout() {}

    /** Le facteur au plus : des réglages extravagants ne font pas des salaires sans fin. */
    static final double FACTEUR_MAX = 100;

    /** Le facteur du salaire pour la {@code rang}-ième embauche chez un chef (1 : son premier ouvrier). */
    static double facteur(int rang, int sansSurcout, double parOuvrier) {
        if (!(parOuvrier > 0)) return 1; // nul, négatif ou NaN : pas de surcoût
        int auDela = Math.max(0, rang - Math.max(0, sansSurcout));
        return Math.min(FACTEUR_MAX, 1 + auDela * Math.min(parOuvrier, 10));
    }

    /** Le salaire du niveau avec le facteur, arrondi ; jamais moins que le salaire du niveau. */
    static long salaire(long base, double facteur) {
        if (!(facteur > 1)) return base;
        return Math.max(base, Math.round(base * Math.min(facteur, FACTEUR_MAX)));
    }
}

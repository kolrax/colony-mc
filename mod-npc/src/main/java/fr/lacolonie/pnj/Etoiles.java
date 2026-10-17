package fr.lacolonie.pnj;

/**
 * Lot 13, cahier 5.9 : les étoiles (1 à 5) montent avec l'expérience ou une formation payée. L'expérience se compte en
 * minutes de vrai travail (une action de son métier réussie dans la minute) : tous les métiers progressent au même pas,
 * quelle que soit la cadence de leurs gestes. Chaque étoile rend l'ouvrier meilleur ({@link Travailleur#facteurTravail},
 * déjà borné) et lui fait demander une hausse mesurée, pas le prix d'un ouvrier né à ce rang. Les règles ici, sans le jeu
 * (testées seules) ; le côté serveur : {@link Apprentissage}.
 */
final class Etoiles {
    private Etoiles() {}

    static final int MAX = 5;
    /** Une minute (1200 ticks) : au plus un point d'expérience par minute de travail. */
    static final long MINUTE = 1200;

    /** Les réglages (« etoiles » dans reglages.json). */
    public static class Reglage {
        /** Les minutes de travail pour passer de ★ à ★★, de ★★ à ★★★, etc. */
        public int[] minutes_de_travail = {120, 240, 480, 960};
        /** Le prix de la formation, en valeur de pièces, depuis ★, ★★, ★★★ et ★★★★. */
        public int[] prix_formation = {16, 32, 64, 128};
        /** La durée de la formation, en minutes. */
        public int minutes_formation = 10;
        /** La hausse de salaire demandée par étoile gagnée chez son chef (0,25 : +25 %). */
        public double hausse_par_etoile = 0.25;

        /** Des réglages absents ou extravagants reprennent des valeurs sûres. */
        static Reglage valide(Reglage r) {
            Reglage d = new Reglage();
            if (r == null) return d;
            if (r.minutes_de_travail == null || r.minutes_de_travail.length != MAX - 1) r.minutes_de_travail = d.minutes_de_travail;
            for (int i = 0; i < r.minutes_de_travail.length; i++) r.minutes_de_travail[i] = Math.clamp(r.minutes_de_travail[i], 1, 1_000_000);
            if (r.prix_formation == null || r.prix_formation.length != MAX - 1) r.prix_formation = d.prix_formation;
            for (int i = 0; i < r.prix_formation.length; i++) r.prix_formation[i] = Math.clamp(r.prix_formation[i], 0, 1_000_000);
            r.minutes_formation = Math.clamp(r.minutes_formation, 1, 24 * 60);
            r.hausse_par_etoile = Double.isNaN(r.hausse_par_etoile) ? d.hausse_par_etoile : Math.clamp(r.hausse_par_etoile, 0.0, 1.0);
            return r;
        }
    }

    /** Les minutes de travail qu'il lui faut pour l'étoile suivante ; -1 à ★★★★★. */
    static int seuil(int niveau, int[] paliers) {
        if (niveau >= MAX || niveau < 1) return -1;
        return paliers[niveau - 1];
    }

    /** Assez d'expérience pour l'étoile suivante. */
    static boolean monte(int niveau, int experience, int[] paliers) {
        int s = seuil(niveau, paliers);
        return s > 0 && experience >= s;
    }

    /** Le numéro de la minute (temps du jeu) : un point d'expérience au plus par minute. */
    static long minute(long tempsDuJeu) { return Math.floorDiv(tempsDuJeu, MINUTE); }

    /** Le prix de la formation depuis ce niveau ; -1 à ★★★★★ (il n'y a plus rien à apprendre). */
    static long prix(int niveau, int[] prix) {
        if (niveau >= MAX || niveau < 1) return -1;
        return prix[niveau - 1];
    }

    /**
     * Ce qu'il demande, avant le surcoût de la colonie : le salaire de son rang d'arrivée ({@code base}), plus
     * {@code hausse} par étoile gagnée chez ce chef ; jamais plus que le salaire d'un ouvrier né à son rang ({@code plein}).
     */
    static long salaire(long base, long plein, int gagnees, double hausse) {
        if (gagnees <= 0 || !(hausse > 0)) return base;
        return Math.max(base, Math.min(plein, Math.round(base * (1 + hausse * gagnees))));
    }

    /** Où il en est : « 40 minutes de travail sur 120 », pour la fiche et les réponses. */
    static String progres(int niveau, int experience, int[] paliers) {
        int s = seuil(niveau, paliers);
        if (s < 0) return "maître en son métier";
        return Math.min(experience, s) + " minutes d'ouvrage sur " + s + " pour l'étoile suivante";
    }
}

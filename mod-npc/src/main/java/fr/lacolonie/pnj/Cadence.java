package fr.lacolonie.pnj;

/**
 * Les sons d'un PNJ (lot 8, cahier 10.7) : un délai minimal entre deux sons d'un même genre, pour ne jamais faire de
 * vacarme, et le compte de ceux joués (lu par les tests). Pur Java : le jeu est dans {@link Sons}.
 */
final class Cadence {
    /** Les genres de sons, avec leur délai minimal en ticks. */
    enum Genre {
        COUP(4),       // coup d'outil sur le bloc qu'il casse
        FRAPPE(6),     // coup d'arme
        DEPOT(40),     // il vide son sac au dépôt
        LETTRE(40),    // il pose ses lettres au poste
        COFFRE(20),    // il ouvre un coffre ou un tonneau
        FOUR(40),      // il charge un four
        SOIN(40),      // il panse un blessé
        PAIE(100),     // il touche sa paie
        GROGNE(100),   // il râle ou refuse
        ROT(600),      // après le repas
        RONFLE(400),   // il dort (plus un écart au hasard)
        PLAINTE(400);  // à terre (plus un écart au hasard)

        final int delai;

        Genre(int delai) { this.delai = delai; }
    }

    private final long[] prochain = new long[Genre.values().length];
    private final int[] joues = new int[Genre.values().length];

    /** Vrai (et noté) si le délai depuis le dernier son de ce genre est passé. */
    boolean peut(Genre g, long maintenant) {
        if (maintenant < prochain[g.ordinal()]) return false;
        prochain[g.ordinal()] = maintenant + g.delai;
        joues[g.ordinal()]++;
        return true;
    }

    int joues(Genre g) { return joues[g.ordinal()]; }

    /** Le délai de ce genre est passé (sans rien noter). */
    boolean pret(Genre g, long maintenant) { return maintenant >= prochain[g.ordinal()]; }

    /** Pas de son de ce genre avant {@code ticks} ticks au moins. */
    void retarder(Genre g, long maintenant, int ticks) { prochain[g.ordinal()] = Math.max(prochain[g.ordinal()], maintenant + ticks); }

    /** Endormi au dernier tick (le premier ronflement attend {@link #ENDORMI} ticks). */
    boolean dort;
    static final int ENDORMI = 200;

    /**
     * Les derniers ronflements, tous PNJ confondus : un seul ronfleur à la fois dans une même pièce (à {@code RAYON} blocs,
     * pendant {@code FENETRE} ticks). Quelques entrées au plus : pas de recherche d'entités.
     */
    static final class Ronfleurs {
        static final int RAYON = 8, FENETRE = 100;
        private static final java.util.ArrayDeque<double[]> DERNIERS = new java.util.ArrayDeque<>();

        /** Personne n'a ronflé tout près ces derniers temps ; si oui, le note comme le ronfleur du moment. */
        static synchronized boolean prendre(double x, double y, double z, long maintenant) {
            while (!DERNIERS.isEmpty() && (DERNIERS.peekFirst()[3] < maintenant - FENETRE || DERNIERS.peekFirst()[3] > maintenant)) DERNIERS.pollFirst();
            for (double[] d : DERNIERS) {
                double dx = d[0] - x, dy = d[1] - y, dz = d[2] - z;
                if (dx * dx + dy * dy + dz * dz <= RAYON * RAYON) return false;
            }
            DERNIERS.addLast(new double[]{x, y, z, maintenant});
            return true;
        }

        static synchronized void oublier() { DERNIERS.clear(); }
    }

    /** Une hauteur un peu variée autour de {@code base} (± 10 %), comme les sons du jeu ; {@code a} et {@code b} dans [0, 1[. */
    static float hauteur(float base, float a, float b) { return base * (1 + (a - b) * 0.1f); }
}

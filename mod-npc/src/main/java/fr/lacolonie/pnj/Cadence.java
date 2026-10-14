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
        RONFLE(160),   // il dort
        PLAINTE(200);  // à terre

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

    /** Une hauteur un peu variée autour de {@code base} (± 10 %), comme les sons du jeu ; {@code a} et {@code b} dans [0, 1[. */
    static float hauteur(float base, float a, float b) { return base * (1 + (a - b) * 0.1f); }
}

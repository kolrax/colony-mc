package fr.lacolonie.pnj;

/**
 * La gravité d'une blessure (lot 5, cahier 1.11 et 5.12), pur Java : déduite des dégâts et de leur source, cumulée, aggravée
 * quand il tombe à terre, résorbée avec le temps (un cran par jour de jeu : légère 1 jour, grave 2, très grave 3). Elle
 * fixe le temps à terre (avant la mort, s'il n'est pas relevé), la lenteur, le repos forcé et la réplique. Le jeu est dans {@link Blessures}.
 */
enum Gravite {
    AUCUNE, LEGERE, GRAVE, TRES_GRAVE;

    /** D'où vient le coup. {@code SANS} : ce qui ne fait pas de plaie (faim, noyade, étouffement, poison, magie). */
    enum Source { COUP, CHUTE, EXPLOSION, FEU, SANS }

    /** Un jour de jeu, en ticks : la blessure perd un cran par jour. */
    static final long JOUR = 24000;
    /** Les réglages (plan E6, recopiés par {@link Reglages#valider}) : minutes à terre (légère, grave, très grave), jours par cran de guérison. */
    static volatile int[] minutesATerre = {3, 5, 8};
    static volatile double joursParCran = 1.0;

    /**
     * La gravité d'un coup de {@code degats} points pour une vie de {@code vieMax} : moins d'un dixième, rien ; moins d'un
     * quart, légère ; moins de la moitié, grave ; au-delà, très grave. Une explosion, ou une brûlure franche (la lave), est
     * d'un cran plus grave.
     */
    static Gravite du(float degats, float vieMax, Source s) {
        if (s == Source.SANS || degats <= 0 || vieMax <= 0) return AUCUNE;
        float f = degats / vieMax;
        Gravite g = f < 0.1f ? AUCUNE : f < 0.25f ? LEGERE : f < 0.5f ? GRAVE : TRES_GRAVE;
        if (s == Source.EXPLOSION) return plus(g, 1);
        if (s == Source.FEU && g != AUCUNE) return plus(g, 1);
        return g;
    }

    /** Un nouveau coup sur une blessure : la plus grave des deux ; deux blessures graves font une très grave. */
    static Gravite cumuler(Gravite avant, Gravite coup) {
        if (coup.ordinal() >= GRAVE.ordinal() && avant.ordinal() >= GRAVE.ordinal()) return TRES_GRAVE;
        return avant.ordinal() >= coup.ordinal() ? avant : coup;
    }

    /** Il tombe à terre : au moins légère, et d'un cran plus grave que la blessure qu'il avait déjà. */
    static Gravite aTerre(Gravite avant, Gravite coup) {
        Gravite g = cumuler(avant, coup);
        if (avant != AUCUNE) g = max(g, plus(avant, 1));
        return max(g, LEGERE);
    }

    /** Ce qu'il en reste, {@code depuis} le moment de la blessure jusqu'à {@code maintenant} (un cran par jour de jeu). */
    static Gravite actuelle(Gravite g, long depuis, long maintenant) {
        long jours = Math.max(0, maintenant - depuis) / Math.max(1, (long) (JOUR * joursParCran));
        return values()[(int) Math.max(0, g.ordinal() - Math.min(jours, 3))];
    }

    /**
     * Le temps à terre avant la mort, s'il n'est pas relevé (cahier 5.12 : toujours plus long qu'avant, 2 min) : 3 min
     * (légère), 5 min (grave), 8 min (très grave).
     */
    int ticksATerre() {
        int[] m = minutesATerre;
        return switch (this) {
            case AUCUNE, LEGERE -> m[0] * 60 * 20;
            case GRAVE -> m[1] * 60 * 20;
            case TRES_GRAVE -> m[2] * 60 * 20;
        };
    }

    /** Ce que la blessure ôte à sa vitesse de marche : grave 15 %, très grave 30 %. */
    double lenteur() { return this == GRAVE ? 0.15 : this == TRES_GRAVE ? 0.30 : 0; }

    /** Très grave : ni mine ni combat, il se repose au poste. */
    boolean auRepos() { return this == TRES_GRAVE; }

    /** La réplique de vie.txt qui la dit (null : aucune). */
    String cle() { return this == AUCUNE ? null : this == LEGERE ? "blessure_legere" : "blessure_grave"; }

    static Gravite plus(Gravite g, int crans) { return values()[Math.min(TRES_GRAVE.ordinal(), g.ordinal() + crans)]; }

    static Gravite max(Gravite a, Gravite b) { return a.ordinal() >= b.ordinal() ? a : b; }

    static Gravite de(int n) { return values()[Math.clamp(n, 0, TRES_GRAVE.ordinal())]; }
}

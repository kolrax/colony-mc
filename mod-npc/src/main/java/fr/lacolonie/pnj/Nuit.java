package fr.lacolonie.pnj;

/**
 * Les travailleurs de nuit (lot 13, cahier 5.10), pur Java : le chef peut mettre un ouvrier « de nuit » ; il dort le jour,
 * mais la nuit le fatigue plus, le sommeil de jour répare moins, et les nuits d'affilée pèsent sur sa santé et son moral.
 * Le côté jeu est dans {@link Travailleur#tickCorps} (fatigue, compte des nuits, vie), {@link Humeur#etat} (moral) et
 * {@link Cerveau} (repos du jour).
 */
final class Nuit {
    private Nuit() {}

    /** Les heures de nuit (heures du jeu) : de 19 h à 6 h. */
    static boolean heureDeNuit(int h) { return h >= 19 || h < 6; }

    /** Vrai si la plupart de ses heures de service tombent la nuit (« toute la journée » n'est pas un travail de nuit). */
    static boolean deNuit(int debut, int fin) {
        if (debut == fin) return false;
        int total = Math.floorMod(fin - debut, 24), nuit = 0;
        for (int k = 0; k < total; k++) if (heureDeNuit((debut + k) % 24)) nuit++;
        return nuit * 2 > total;
    }

    /** La fatigue d'une heure de travail : la nuit, tant de fois plus (arrondi, au moins le gain de jour). */
    static int fatigue(int gain, boolean laNuit, double facteur) {
        return laNuit ? Math.max(gain, (int) Math.round(gain * facteur)) : gain;
    }

    /** Ce qu'une heure de repos lui rend : de jour, pour un travailleur de nuit, seulement une part (au moins 1). */
    static int repos(int gain, boolean deJour, double part) {
        return deJour ? Math.max(1, (int) Math.round(gain * part)) : gain;
    }

    /** Le compte des nuits travaillées d'affilée, et des jours libres (ou de travail de jour) depuis la dernière. */
    record Compte(int nuits, int libres) {
        static final Compte ZERO = new Compte(0, 0);
    }

    /** Une nuit de plus vaut au moins tant d'heures de travail entre 19 h et 6 h. */
    static final int HEURES_POUR_UNE_NUIT = 4;

    /** Un jour de passé : une nuit travaillée de plus, ou un jour libre ; tant de jours libres de suite effacent le compte. */
    static Compte compter(Compte c, int heuresDeNuit, int joursDeRepos) {
        if (heuresDeNuit >= HEURES_POUR_UNE_NUIT) return new Compte(c.nuits() + 1, 0);
        int libres = c.libres() + 1;
        return libres >= Math.max(1, joursDeRepos) ? Compte.ZERO : new Compte(c.nuits(), libres);
    }

    /** La vie max perdue : 2 points à partir de {@code seuil} nuits d'affilée, 4 au double (0 : jamais). */
    static int vieEnMoins(int nuits, int seuil) {
        if (seuil <= 0 || nuits < seuil) return 0;
        return nuits >= 2 * seuil ? 4 : 2;
    }

    /** Les ticks entre deux points de vie rendus (200 d'ordinaire) : moitié plus lent quand les nuits lui pèsent. */
    static int soin(int nuits, int seuil) { return vieEnMoins(nuits, seuil) > 0 ? 300 : 200; }

    /**
     * Ce que les nuits pèsent sur son moral à chaque bilan ({@link Moral.Cause#NUIT}) : −1 pour une ou deux nuits d'affilée,
     * −2 à partir du seuil, −3 au double ; une prime ou une paie au-dessus de ce qu'il demande en reprend un point.
     */
    static int moral(int nuits, int seuil, boolean compense) {
        if (nuits <= 0) return 0;
        int s = Math.max(1, seuil);
        int p = nuits >= 2 * s ? -3 : nuits >= s ? -2 : -1;
        return compense ? Math.min(0, p + 1) : p;
    }
}

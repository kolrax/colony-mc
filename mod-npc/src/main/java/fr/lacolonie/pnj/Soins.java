package fr.lacolonie.pnj;

import java.util.List;

/**
 * Les soins du guérisseur (lot 5, cahier 1.11 et 4.2), pur Java : qui secourir d'abord, ce que le soin fait à la blessure
 * et à la vie. Le jeu est dans {@link Guerisseur}.
 */
final class Soins {
    private Soins() {}

    /** Ses ordres (« soigne Jean », « va soigner les blessés ») : le vocabulaire « travail » du répertoire. */
    static final java.util.Set<String> TRAVAUX = java.util.Set.of("soigner", "soigner_blesses");
    /** Il cherche les blessés au plus toutes les tant de secondes (en dehors de l'alerte d'un ouvrier qui tombe). */
    static final int REVUE = 5;
    /** Le geste auprès du blessé, en ticks : relever (2 s), panser (4 s). */
    static final int RELEVER = 40, PANSER = 80;

    /**
     * Un ouvrier de la colonie vu par le guérisseur : à terre (et le temps qu'il lui reste), sa blessure, s'il a déjà été soigné
     * de cette blessure, sa distance (au carré), et s'il a un monstre tout près (le guérisseur ne s'y jette pas).
     */
    record Cas(boolean aTerre, int ticksRestants, Gravite gravite, boolean soigne, double distance2, boolean danger) {}

    /** Une blessure à panser : grave ou très grave (légère aussi sur ordre : {@code legeres}), pas encore soignée. */
    static boolean aPanser(Gravite g, boolean soigne, boolean legeres) {
        return !soigne && g.ordinal() >= (legeres ? Gravite.LEGERE : Gravite.GRAVE).ordinal();
    }

    /** Il a besoin de lui : à terre, ou une blessure à panser. */
    static boolean aBesoin(Cas c, boolean legeres) { return c.aTerre() || aPanser(c.gravite(), c.soigne(), legeres); }

    /**
     * Le premier à secourir, ou -1 : un ouvrier à terre d'abord (celui à qui il reste le moins de temps, puis le plus proche) ;
     * sinon le blessé le plus grave, puis le plus proche. Celui qu'un monstre menace est laissé : il attendra.
     */
    static int choisir(List<Cas> cas, boolean legeres) {
        int k = -1;
        for (int i = 0; i < cas.size(); i++) {
            Cas c = cas.get(i);
            if (c.danger() || !aBesoin(c, legeres)) continue;
            if (k < 0 || avant(c, cas.get(k))) k = i;
        }
        return k;
    }

    static boolean avant(Cas a, Cas b) {
        if (a.aTerre() != b.aTerre()) return a.aTerre();
        if (a.aTerre() && a.ticksRestants() != b.ticksRestants()) return a.ticksRestants() < b.ticksRestants();
        if (!a.aTerre() && a.gravite() != b.gravite()) return a.gravite().ordinal() > b.gravite().ordinal();
        return a.distance2() < b.distance2();
    }

    /** Un blessé qu'il faudrait secourir, mais qu'un monstre menace (il le dit, et attend). */
    static boolean attendre(List<Cas> cas, boolean legeres) {
        return cas.stream().anyMatch(c -> c.danger() && aBesoin(c, legeres));
    }

    /** La blessure après le soin : un cran de moins. */
    static Gravite apres(Gravite g) { return Gravite.de(g.ordinal() - 1); }

    /** La vie que rend le soin : un tiers de sa vie, sans dépasser le maximum. */
    static float vie(float vie, float vieMax) { return Math.min(vieMax, vie + vieMax / 3); }
}

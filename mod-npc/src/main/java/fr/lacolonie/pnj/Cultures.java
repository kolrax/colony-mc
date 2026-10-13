package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ce que sait le fermier, sans le monde (pur Java, testé seul) : les cultures (le bloc qui pousse, la graine qu'on plante,
 * ce qu'on récolte), la culture et les mesures dites par le chef, les cases d'un champ, et le découpage de l'espace de vie
 * en zones que {@link Fermier} parcourt une à une (pas de grand balayage d'un coup).
 */
final class Cultures {
    private Cultures() {}

    /** Une culture : son identifiant du catalogue du fermier, son nom, le bloc qui pousse, la graine qu'on plante, ce qu'on récolte. */
    record Culture(String id, String nom, String bloc, String graine, String recolte) {}

    static final List<Culture> TOUTES = List.of(
            new Culture("ble", "Blé", "minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat"),
            new Culture("carotte", "Carottes", "minecraft:carrots", "minecraft:carrot", "minecraft:carrot"),
            new Culture("pomme_de_terre", "Pommes de terre", "minecraft:potatoes", "minecraft:potato", "minecraft:potato"),
            new Culture("betterave", "Betteraves", "minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot"));

    static Culture parId(String id) {
        for (Culture c : TOUTES) if (c.id.equals(id)) return c;
        return null;
    }

    /** La culture de ce bloc (« minecraft:wheat » : le blé), ou null. */
    static Culture parBloc(String bloc) {
        for (Culture c : TOUTES) if (c.bloc.equals(bloc)) return c;
        return null;
    }

    /** La culture dite (« un champ de carottes », « des patates ») ; rien de dit : le blé. */
    static Culture dite(String phrase) {
        String[] mots = Ordres.normaliser(phrase == null ? "" : phrase).strip().split(" ");
        for (int i = 0; i < mots.length; i++) {
            String[] o = Ordres.objet(mots, i, "fermier");
            if (o != null && o[0].equals("fermier") && parId(o[1]) != null) return parId(o[1]);
        }
        return TOUTES.get(0);
    }

    /** Les mesures du champ dites (« 9 sur 9 », « de 5 blocs ») : 9 sur 9 sans rien dire, 32 sur 32 au plus. */
    static Fouille.Plan mesures(String phrase) { return Fouille.lire("champ", phrase, 0); }

    /** L'eau, au centre du champ, arrose la terre à 4 blocs autour (à l'horizontale), comme dans le jeu. */
    static final int ARROSAGE = 4;

    /** Les cases du champ autour de son centre, {avant, côté}, rang par rang en serpentin (il ne traverse pas le champ à chaque rang). */
    static List<int[]> cases(Fouille.Plan p) {
        List<int[]> r = new ArrayList<>();
        int f0 = -(p.longueur() - 1) / 2, c0 = -(p.largeur() - 1) / 2;
        for (int i = 0; i < p.longueur(); i++)
            for (int j = 0; j < p.largeur(); j++) r.add(new int[]{f0 + i, c0 + (i % 2 == 0 ? j : p.largeur() - 1 - j)});
        return r;
    }

    /** La case {avant, côté} (par rapport au centre) est dans le champ. */
    static boolean dans(Fouille.Plan p, int f, int c) {
        int f0 = -(p.longueur() - 1) / 2, c0 = -(p.largeur() - 1) / 2;
        return f >= f0 && f < f0 + p.longueur() && c >= c0 && c < c0 + p.largeur();
    }

    /** Le champ agrandi d'un rang tout autour (32 sur 32 au plus). */
    static Fouille.Plan agrandi(Fouille.Plan p) {
        return new Fouille.Plan(Math.min(Fouille.TERRAIN_MAX, p.longueur() + 2), Math.min(Fouille.TERRAIN_MAX, p.largeur() + 2), p.hauteur(), p.direction(), p.descend());
    }

    /** Côté d'une zone du balayage des cultures mûres (une zone par seconde). */
    static final int ZONE = 16;

    /**
     * Les zones qui couvrent l'espace de vie (un carré de {@code rayon} blocs autour du poste) : le coin {dx, dz} de chacune,
     * par rapport au poste, de la plus proche du poste à la plus lointaine (les champs sont souvent près de la maison).
     */
    static List<int[]> zones(int rayon) {
        int n = (2 * rayon + 1 + ZONE - 1) / ZONE, debut = -n * ZONE / 2;
        List<int[]> r = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) r.add(new int[]{debut + i * ZONE, debut + j * ZONE});
        r.sort(Comparator.comparingDouble(z -> Math.pow(z[0] + ZONE / 2.0 - 0.5, 2) + Math.pow(z[1] + ZONE / 2.0 - 0.5, 2)));
        return r;
    }
}

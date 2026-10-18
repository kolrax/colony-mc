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

    // --- Les mares du champ ----------------------------------------------------------------------------------------

    /** Une case du champ, sans le monde : x, y (le niveau du sol), z. */
    record Point(int x, int y, int z) {}

    /** Au plus une mare par niveau et par carré de tant de cases du champ (un champ de 9 sur 9 : une par niveau). */
    static final int CARRE_MARE = 9;
    /** Une mare prend une case : il ne la creuse que si elle arrose au moins tant de cases sèches (pas pour un coin sec). */
    static final int MARE_UTILE = 8;
    /** Au plus une mare pour tant de cases du champ (une au moins). */
    static final int CASES_PAR_MARE = 20;
    /** Les places du tampon du poste se vérifient dans le monde (coûteux) : au plus tant de vérifications par champ. */
    static final int VERIFS_POSTE = 8;

    /** L'eau en e arrose la terre en c, comme dans le jeu : à 4 blocs à l'horizontale, au même niveau ou un bloc plus haut. */
    static boolean arrose(Point e, Point c) {
        return Math.abs(e.x() - c.x()) <= ARROSAGE && Math.abs(e.z() - c.z()) <= ARROSAGE && (e.y() == c.y() || e.y() == c.y() + 1);
    }

    /**
     * Où creuser les mares d'un champ de {@code champ} cases, de milieu (cx, cz), pour arroser ses cases {@code seches}, même
     * sur un terrain en marches : tant qu'il en reste, la place qui en arrose le plus (au moins {@link #MARE_UTILE}), la plus
     * près du milieu à égalité. Les places {@code sures} d'abord ; puis, pour les cases qu'aucune d'elles n'arrose, celles du
     * tampon du poste, chacune vérifiée par {@code permise} une fois choisie ({@link #VERIFS_POSTE} fois au plus). Au plus
     * une mare par niveau et par carré de {@link #CARRE_MARE} (pour chacune des deux sortes de places), une pour
     * {@link #CASES_PAR_MARE} cases du champ, jamais deux côte à côte. Une case par colonne (le sol d'un champ) : chaque
     * place compte d'avance les cases sèches qu'elle arrose, et le choix d'une mare ne fait que décompter autour d'elle (un
     * champ de 32 sur 32 en quelques millisecondes).
     */
    static List<Point> mares(List<Point> seches, List<Point> sures, List<Point> duPoste, java.util.function.Predicate<Point> permise, int champ, int cx, int cz) {
        List<Point> r = new ArrayList<>(), places = new ArrayList<>(sures);
        places.addAll(duPoste);
        int n = places.size(), max = Math.max(1, champ / CASES_PAR_MARE), verifs = 0;
        if (n == 0 || seches.isEmpty()) return r;
        Grille gs = Grille.de(seches), gp = Grille.de(places);
        int[] note = new int[n];
        for (int p = 0; p < n; p++) {
            Point m = places.get(p);
            for (int dx = -ARROSAGE; dx <= ARROSAGE; dx++)
                for (int dz = -ARROSAGE; dz <= ARROSAGE; dz++) {
                    int i = dx == 0 && dz == 0 ? -1 : gs.a(m.x() + dx, m.z() + dz);
                    if (i >= 0 && arrose(m, seches.get(i))) note[p]++;
                }
        }
        boolean[] servie = new boolean[seches.size()], exclue = new boolean[n];
        List<java.util.Set<List<Integer>>> carres = List.of(new java.util.HashSet<>(), new java.util.HashSet<>());
        while (r.size() < max) {
            int mieux = -1;
            for (int rang = 0; rang < 2 && mieux < 0; rang++) // les places sûres d'abord, le tampon du poste ensuite
                for (int p = rang == 0 ? 0 : sures.size(); p < (rang == 0 ? sures.size() : n); p++) {
                    if (exclue[p] || note[p] < MARE_UTILE || mieux >= 0 && note[p] < note[mieux]) continue;
                    Point m = places.get(p);
                    if (mieux >= 0 && note[p] == note[mieux] && loin(m, cx, cz) >= loin(places.get(mieux), cx, cz)) continue;
                    if (carres.get(rang).contains(carre(m, cx, cz)) || r.stream().anyMatch(o -> Math.abs(o.x() - m.x()) <= 1 && Math.abs(o.z() - m.z()) <= 1)) continue;
                    mieux = p;
                }
            if (mieux < 0) break;
            Point m = places.get(mieux);
            int rang = mieux < sures.size() ? 0 : 1;
            if (rang == 1 && (verifs++ >= VERIFS_POSTE || !permise.test(m))) { // pas permise : écartée ; trop de vérifications : plus de tampon du poste
                if (verifs > VERIFS_POSTE) for (int p = sures.size(); p < n; p++) exclue[p] = true;
                exclue[mieux] = true;
                continue;
            }
            r.add(m);
            carres.get(rang).add(carre(m, cx, cz));
            for (int dx = -ARROSAGE; dx <= ARROSAGE; dx++) // les cases qu'elle arrose, et la sienne, qui n'a plus besoin d'eau
                for (int dz = -ARROSAGE; dz <= ARROSAGE; dz++) {
                    int i = gs.a(m.x() + dx, m.z() + dz);
                    if (i < 0 || servie[i] || !(dx == 0 && dz == 0 ? seches.get(i).equals(m) : arrose(m, seches.get(i)))) continue;
                    servie[i] = true;
                    Point c = seches.get(i);
                    for (int ex = -ARROSAGE; ex <= ARROSAGE; ex++) // elle ne compte plus pour les places qui l'arrosaient
                        for (int ez = -ARROSAGE; ez <= ARROSAGE; ez++) {
                            int q = ex == 0 && ez == 0 ? -1 : gp.a(c.x() + ex, c.z() + ez);
                            if (q >= 0 && arrose(places.get(q), c)) note[q]--;
                        }
                }
        }
        return r;
    }

    /** Les cases d'un champ, une par colonne : l'indice de celle de la colonne (x, z) dans la liste, -1 s'il n'y en a pas. */
    private record Grille(int x0, int z0, int w, int h, int[] t) {
        static Grille de(List<Point> pts) {
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (Point p : pts) { x0 = Math.min(x0, p.x()); z0 = Math.min(z0, p.z()); x1 = Math.max(x1, p.x()); z1 = Math.max(z1, p.z()); }
            int w = x1 - x0 + 1, h = z1 - z0 + 1;
            int[] t = new int[w * h];
            java.util.Arrays.fill(t, -1);
            for (int i = 0; i < pts.size(); i++) t[(pts.get(i).x() - x0) * h + pts.get(i).z() - z0] = i;
            return new Grille(x0, z0, w, h, t);
        }

        int a(int x, int z) {
            x -= x0;
            z -= z0;
            return x < 0 || z < 0 || x >= w || z >= h ? -1 : t[x * h + z];
        }
    }

    static long loin(Point m, int cx, int cz) { return (long) (m.x() - cx) * (m.x() - cx) + (long) (m.z() - cz) * (m.z() - cz); }

    /** Le niveau et le carré de {@link #CARRE_MARE} cases (comptés depuis le milieu du champ) de la place m. */
    static List<Integer> carre(Point m, int cx, int cz) {
        int a = CARRE_MARE / 2;
        return List.of(m.y(), Math.floorDiv(m.x() - cx + a, CARRE_MARE), Math.floorDiv(m.z() - cz + a, CARRE_MARE));
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

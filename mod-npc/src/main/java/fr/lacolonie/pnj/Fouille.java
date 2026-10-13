package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un chantier de creusage dit de vive voix (cahier 3.2) : une salle ou une cave, une carrière, un tunnel. Lit les mesures
 * dites (« de 7 sur 4 », « de 10 blocs », « 3 de haut », « vers le nord », « de train ») et donne les blocs à creuser
 * dans l'ordre. Pur Java (testé seul) ; {@link Consignes} les place dans le monde et les fait creuser.
 */
final class Fouille {
    private Fouille() {}

    /**
     * Le plan : {@code longueur} droit devant, {@code largeur} de part et d'autre, {@code hauteur} (en couches pour un
     * chantier qui {@code descend}) ; {@code direction} : « north », « south », « east », « west », ou vide (là où regarde le chef).
     */
    record Plan(int longueur, int largeur, int hauteur, String direction, boolean descend) {
        /** Gardé dans la consigne (et sa sauvegarde). */
        String texte() { return longueur + " " + largeur + " " + hauteur + " " + (direction.isEmpty() ? "-" : direction) + " " + (descend ? "bas" : "face"); }

        Plan vers(String d, boolean bas) { return new Plan(longueur, largeur, hauteur, d, bas); }

        /** Relit {@link #texte()} ; null si ce n'en est pas un (une consigne d'avant : la direction seule). */
        static Plan de(String t) {
            String[] m = t == null ? new String[0] : t.trim().split(" ");
            if (m.length != 5) return null;
            try {
                return new Plan(Integer.parseInt(m[0]), Integer.parseInt(m[1]), Integer.parseInt(m[2]), m[3].equals("-") ? "" : m[3], m[4].equals("bas"));
            } catch (NumberFormatException e) { return null; }
        }
    }

    static final int TORCHES = Mineur.TORCHES;

    static final Pattern HAUT = Pattern.compile(" (\\d+) (?:blocs? )?de (?:haut|hauteur) | (?:haute?|hauteur) de (\\d+) ");
    static final Pattern PROFOND = Pattern.compile(" (\\d+) (?:blocs? )?de (?:profondeur|profond|creux) | (?:profonde?|profondeur) de (\\d+) | (?:sur )?(\\d+) (?:couches|niveaux|etages) ");
    static final Pattern LARGE = Pattern.compile(" (\\d+) (?:blocs? )?de (?:large|largeur) | (?:large|largeur) de (\\d+) ");
    static final Pattern LONG = Pattern.compile(" (\\d+) (?:blocs? )?de (?:long|longueur) | (?:longue?|longueur) de (\\d+) ");
    static final Pattern MESURES = Pattern.compile(" (\\d+) (?:blocs? )?(?:sur|x|par) (\\d+)(?: (?:blocs? )?(?:sur|x|par) (\\d+))? ");
    static final Pattern SEULE = Pattern.compile(" de (\\d+) ");

    /**
     * Le plan d'un chantier {@code genre} (« salle », « carriere » ou « tunnel ») d'après la phrase du chef. Ce qui n'est pas
     * dit prend la valeur par défaut : salle 5 x 5 x 3, carrière 8 x 8 sur 4 couches, tunnel de {@code longueur} x 1 x 2
     * (« de train » : 16 x 3 x 4). Les mesures sont bornées (une salle de 16 sur 16 au plus, un tunnel de 64 blocs).
     */
    static Plan lire(String genre, String phrase, int longueur) {
        String n = chiffres(Ordres.normaliser(phrase == null ? "" : phrase).replaceAll("(\\d)x(\\d)", "$1 x $2"));
        boolean tunnel = genre.equals("tunnel"), carriere = genre.equals("carriere");
        boolean train = tunnel && Ordres.dit(n, "train", "trains", "rail", "rails", "chemin de fer", "wagonnet", "wagonnets");
        int lo = tunnel ? (train ? 16 : Math.max(1, longueur)) : carriere ? 8 : 5, la = tunnel ? (train ? 3 : 1) : lo, ha = tunnel ? (train ? 4 : 2) : carriere ? 4 : 3;
        int[] v;
        if ((v = prendre(HAUT, n)) != null) { ha = v[0]; n = enlever(HAUT, n); }
        if ((v = prendre(PROFOND, n)) != null) { ha = v[0]; n = enlever(PROFOND, n); }
        if ((v = prendre(LARGE, n)) != null) { la = v[0]; n = enlever(LARGE, n); }
        if ((v = prendre(LONG, n)) != null) { lo = v[0]; n = enlever(LONG, n); }
        Matcher m = MESURES.matcher(n);
        if (m.find()) {
            lo = Integer.parseInt(m.group(1));
            la = Integer.parseInt(m.group(2));
            if (m.group(3) != null) ha = Integer.parseInt(m.group(3));
            n = n.substring(0, m.start()) + " " + n.substring(m.end());
        }
        if ((v = prendre(SEULE, n)) != null) { lo = v[0]; if (!tunnel) la = v[0]; }
        if (tunnel) return new Plan(borne(lo, 1, 64), borne(la, 1, 5), borne(ha, 2, 5), direction(n), false);
        return new Plan(borne(lo, 1, 16), borne(la, 1, 16), carriere ? borne(ha, 1, 8) : borne(ha, 2, 6), direction(n), carriere);
    }

    /** « vers le nord », « au sud », « plein est », « à l'ouest » : la direction en anglais du jeu, ou vide. */
    static String direction(String n) {
        String[][] d = {{"nord", "north", "le "}, {"sud", "south", "le "}, {"est", "east", "l "}, {"ouest", "west", "l "}};
        for (String[] x : d)
            if (Ordres.dit(n, "vers " + x[2] + x[0], (x[2].equals("le ") ? "au " : "a l ") + x[0], "plein " + x[0], "direction " + x[0],
                    "direction " + x[2] + x[0], "cap " + x[0], "cap " + (x[2].equals("le ") ? "au " : "a l ") + x[0]))
                return x[1];
        return "";
    }

    /**
     * Les blocs à creuser, dans l'ordre : {avant, côté, hauteur, torche (1 : une torche à poser là une fois creusé)}.
     * De face : tranche par tranche, chacune du haut vers le bas, une torche tous les {@link #TORCHES} blocs.
     * Vers le bas : couche par couche, du haut vers le bas ; le premier rang garde un escalier pour remonter.
     */
    static List<int[]> blocs(Plan p) {
        List<int[]> r = new ArrayList<>();
        int cMin = -(p.largeur() - 1) / 2, cMax = p.largeur() / 2;
        if (!p.descend()) {
            for (int f = 0; f < p.longueur(); f++) {
                for (int y = p.hauteur() - 1; y >= 0; y--) for (int c = cMin; c <= cMax; c++) r.add(new int[]{f, c, y, 0});
                if ((f + 1) % TORCHES == 0) r.add(new int[]{f, cMin, Math.min(1, p.hauteur() - 1), 1});
            }
            return r;
        }
        for (int k = 0; k < p.hauteur(); k++)
            for (int f = 0; f < p.longueur(); f++)
                for (int i = 0; i < p.largeur(); i++) {
                    int c = f % 2 == 0 ? cMin + i : cMax - i; // en serpentin : il ne traverse pas la couche à chaque rang
                    if (c == cMin && f < k) continue; // une marche de l'escalier
                    r.add(new int[]{f, c, -k, 0});
                }
        return r;
    }

    // --- Petits outils ----------------------------------------------------------------------------------------

    static int borne(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    static int[] prendre(Pattern p, String n) {
        Matcher m = p.matcher(n);
        if (!m.find()) return null;
        for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) return new int[]{Integer.parseInt(m.group(g))};
        return null;
    }

    static String enlever(Pattern p, String n) { return p.matcher(n).replaceFirst(" "); }

    /** Les nombres en lettres en chiffres (« trente » → « 30 »), sauf « un », « une » (des articles). */
    static String chiffres(String n) {
        String[] mots = n.strip().split(" ");
        StringBuilder b = new StringBuilder(" ");
        for (int i = 0; i < mots.length; i++) {
            int[] nb = !mots[i].equals("un") && !mots[i].equals("une") && Ordres.NOMBRES.containsKey(mots[i]) ? Ordres.nombre(mots, i) : null;
            if (nb != null && nb[1] > 0) { b.append(nb[0]).append(' '); i += nb[1] - 1; }
            else b.append(mots[i]).append(' ');
        }
        return b.toString();
    }
}

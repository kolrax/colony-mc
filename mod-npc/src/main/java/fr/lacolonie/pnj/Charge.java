package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Le banc de charge : ce que coûte le mod à chaque tick du serveur. La mesure est toujours active et presque gratuite
 * (deux lectures d'horloge par partie, des tableaux fixes, aucune allocation par tick). Elle garde les 100 derniers
 * ticks : moyenne et pire, par partie, et le nombre de PNJ qui ont tourné. Sans Minecraft : testable en JUnit.
 * Tout se passe sur le fil du serveur (les PNJ côté client ne sont pas mesurés).
 */
final class Charge {
    /** Les parties mesurées. Les « dont » sont comprises dans le tick entier ; les chemins aussi, à cheval sur trois « dont ». */
    enum Partie {
        TOTAL("part du mod", false),
        ENTITE("PNJ, tick entier", false),
        MOTEUR("  dont mouvement, physique, buts de Minecraft", false),
        CHUNKS("  dont chunks forcés", false),
        CORPS("  dont corps (faim, ramassage, armure, courrier)", false),
        ACTION("  dont action en cours", false),
        CERVEAU("  dont cerveau (décisions)", false),
        CHEMINS("calcul des chemins (déjà compté dans le mouvement, l'action ou le cerveau)", false),
        FILET("filet des chunks (tous les PNJ, toutes les 5 s)", true),
        COURRIER("corbeaux arrivés", true),
        VOIX("voix et micro", true);

        final String nom;
        /** Mesurée une fois par tick pour tout le serveur, hors des PNJ. */
        final boolean globale;

        Partie(String nom, boolean globale) {
            this.nom = nom;
            this.globale = globale;
        }
    }

    /** D'où vient une recherche de chemin (pour savoir qui les multiplie). */
    enum Origine {
        DIRECT("chemin direct"),
        ETAPE("étapes"),
        SORTIE("sortie de sous terre"),
        MONTEE("montée sur une marche"),
        AUTRE("autres (buts de Minecraft…)");

        final String nom;

        Origine(String nom) { this.nom = nom; }
    }

    static final Partie[] PARTIES = Partie.values();
    static final Origine[] ORIGINES = Origine.values();
    static final int FENETRE = 100;
    /** Celui du serveur. */
    static final Charge ICI = new Charge(FENETRE);

    private final int taille;
    private final long[] courant = new long[PARTIES.length];
    private final long[][] fenetre;
    private final int[] pnjFenetre;
    private int pnjCourant, curseur, remplis;
    /** Recherches de chemin du tick : [origine] puis [ORIGINES.length + origine] pour les échecs. */
    private final int[] recherchesCourant = new int[ORIGINES.length * 2];
    private final int[][] recherchesFenetre;
    /** Le plus de recherches faites par un même PNJ sur un tick. */
    private int pirePnjCourant;
    private final int[] pirePnjFenetre;

    Charge(int taille) {
        this.taille = taille;
        fenetre = new long[taille][PARTIES.length];
        pnjFenetre = new int[taille];
        recherchesFenetre = new int[taille][ORIGINES.length * 2];
        pirePnjFenetre = new int[taille];
    }

    /**
     * Une recherche de chemin vient d'être faite. {@code echec} : pas de chemin, ou un chemin qui n'atteint pas la cible.
     * {@code duPnjCeTick} : combien ce PNJ en a déjà fait sur ce tick (celle-ci comprise).
     */
    void recherche(Origine o, boolean echec, int duPnjCeTick) {
        recherchesCourant[o.ordinal()]++;
        if (echec) recherchesCourant[ORIGINES.length + o.ordinal()]++;
        pirePnjCourant = Math.max(pirePnjCourant, duPnjCeTick);
    }

    /** Ajoute le temps écoulé depuis {@code debut} à la partie, et rend l'heure (pour enchaîner les mesures). */
    long mesurer(Partie p, long debut) {
        long n = System.nanoTime();
        courant[p.ordinal()] += n - debut;
        return n;
    }

    void ajouter(Partie p, long nanos) { courant[p.ordinal()] += nanos; }

    /** Le tick entier d'un PNJ : il compte aussi parmi les PNJ actifs. */
    void pnj(long nanos) {
        courant[Partie.ENTITE.ordinal()] += nanos;
        pnjCourant++;
    }

    /** Fin d'un tick du serveur : il entre dans la fenêtre, avec ses parties calculées. */
    void clore() {
        long[] c = courant;
        c[Partie.MOTEUR.ordinal()] = Math.max(0, c[Partie.ENTITE.ordinal()] - c[Partie.CHUNKS.ordinal()] - c[Partie.CORPS.ordinal()]
                - c[Partie.ACTION.ordinal()] - c[Partie.CERVEAU.ordinal()]);
        long total = c[Partie.ENTITE.ordinal()];
        for (Partie p : PARTIES) if (p.globale) total += c[p.ordinal()];
        c[Partie.TOTAL.ordinal()] = total;
        System.arraycopy(c, 0, fenetre[curseur], 0, c.length);
        java.util.Arrays.fill(c, 0);
        pnjFenetre[curseur] = pnjCourant;
        pnjCourant = 0;
        System.arraycopy(recherchesCourant, 0, recherchesFenetre[curseur], 0, recherchesCourant.length);
        java.util.Arrays.fill(recherchesCourant, 0);
        pirePnjFenetre[curseur] = pirePnjCourant;
        pirePnjCourant = 0;
        curseur = (curseur + 1) % taille;
        if (remplis < taille) remplis++;
    }

    int ticks() { return remplis; }

    double moyenneMs(Partie p) {
        if (remplis == 0) return 0;
        long s = 0;
        for (int i = 0; i < remplis; i++) s += fenetre[i][p.ordinal()];
        return s / (double) remplis / 1e6;
    }

    double pireMs(Partie p) {
        long m = 0;
        for (int i = 0; i < remplis; i++) m = Math.max(m, fenetre[i][p.ordinal()]);
        return m / 1e6;
    }

    /** PNJ qui ont tourné au dernier tick clos. */
    int pnjDernier() { return remplis == 0 ? 0 : pnjFenetre[(curseur - 1 + taille) % taille]; }

    double pnjMoyen() {
        if (remplis == 0) return 0;
        long s = 0;
        for (int i = 0; i < remplis; i++) s += pnjFenetre[i];
        return s / (double) remplis;
    }

    /** Recherches de chemin sur la fenêtre (null = toutes origines). */
    int recherches(Origine o) { return compter(o, 0); }

    /** Celles qui ont échoué (pas de chemin, ou chemin qui n'atteint pas la cible). */
    int echecs(Origine o) { return compter(o, ORIGINES.length); }

    private int compter(Origine o, int decalage) {
        int s = 0;
        for (int i = 0; i < remplis; i++)
            for (Origine x : ORIGINES) if (o == null || o == x) s += recherchesFenetre[i][decalage + x.ordinal()];
        return s;
    }

    /** Le plus de recherches (tous les PNJ) sur un même tick de la fenêtre. */
    int recherchesPireTick() {
        int m = 0;
        for (int i = 0; i < remplis; i++) {
            int s = 0;
            for (Origine x : ORIGINES) s += recherchesFenetre[i][x.ordinal()];
            m = Math.max(m, s);
        }
        return m;
    }

    /** Le plus de recherches d'un même PNJ sur un même tick de la fenêtre. */
    int recherchesPirePnj() {
        int m = 0;
        for (int i = 0; i < remplis; i++) m = Math.max(m, pirePnjFenetre[i]);
        return m;
    }

    /** Pour /pnj charge et le journal des tests : le serveur, la part du mod, puis chaque partie. */
    List<String> lignes(double msptMoyen, double msptPire) {
        List<String> l = new ArrayList<>();
        l.add(String.format(Locale.ROOT, "Charge sur %d ticks : %d PNJ actifs (%.1f en moyenne) · tick du serveur %.2f ms en moyenne, %.2f ms au pire",
                remplis, pnjDernier(), pnjMoyen(), msptMoyen, msptPire));
        double pnj = pnjMoyen();
        for (Partie p : PARTIES) {
            String parPnj = !p.globale && pnj > 0 ? String.format(Locale.ROOT, " · %.3f ms par PNJ", moyenneMs(p) / pnj) : "";
            l.add(String.format(Locale.ROOT, " - %s : %.3f ms en moyenne, %.3f ms au pire%s", p.nom, moyenneMs(p), pireMs(p), parPnj));
        }
        int n = recherches(null);
        l.add(String.format(Locale.ROOT, " - recherches de chemin : %d (%.1f par tick, %d au pire sur un tick), dont %d échouées ; au plus %d pour un même PNJ sur un tick",
                n, remplis == 0 ? 0 : n / (double) remplis, recherchesPireTick(), echecs(null), recherchesPirePnj()));
        StringBuilder b = new StringBuilder(" - par origine :");
        for (Origine o : ORIGINES) b.append(String.format(Locale.ROOT, " %s %d (%d échecs) ·", o.nom, recherches(o), echecs(o)));
        l.add(b.substring(0, b.length() - 2));
        return l;
    }
}

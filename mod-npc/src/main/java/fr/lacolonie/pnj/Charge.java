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
    /** Les parties mesurées. Les « dont » sont comprises dans la partie au-dessus d'elles. */
    enum Partie {
        TOTAL("part du mod", false),
        ENTITE("PNJ, tick entier", false),
        MOTEUR("  dont mouvement, physique, buts de Minecraft", false),
        CHUNKS("  dont chunks forcés", false),
        CORPS("  dont corps (faim, ramassage, armure, courrier)", false),
        ACTION("  dont action en cours", false),
        CERVEAU("  dont cerveau (décisions)", false),
        CHEMINS("  dont calcul des chemins (pris sur les trois au-dessus)", false),
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

    static final Partie[] PARTIES = Partie.values();
    static final int FENETRE = 100;
    /** Celui du serveur. */
    static final Charge ICI = new Charge(FENETRE);

    private final int taille;
    private final long[] courant = new long[PARTIES.length];
    private final long[][] fenetre;
    private final int[] pnjFenetre;
    private int pnjCourant, curseur, remplis;

    Charge(int taille) {
        this.taille = taille;
        fenetre = new long[taille][PARTIES.length];
        pnjFenetre = new int[taille];
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
        return l;
    }
}

package fr.lacolonie.pnj;

/** Calcul pur des paiements en pièces (aucune dépendance au jeu, testé par MonnaieTest). */
public final class Monnaie {
    private Monnaie() {}

    /** « Jamais de paie » (tests, banc de charge) : la prochaine paie n'arrive jamais. */
    public static final long SANS_PAIE = Long.MAX_VALUE;

    /**
     * La paie attendue à {@code prochaine} a un quart de période de retard. Écrit en soustraction : avec
     * {@link #SANS_PAIE}, l'addition débordait et la paie était en retard dès le premier instant.
     */
    public static boolean paieEnRetard(long maintenant, long prochaine, long periode) {
        return prochaine != SANS_PAIE && maintenant - prochaine >= periode / 4;
    }

    public static long total(int[] valeurs, int[] quantites) {
        long t = 0;
        for (int i = 0; i < valeurs.length; i++) t += (long) valeurs[i] * quantites[i];
        return t;
    }

    /**
     * Paie {@code montant} avec les pièces {@code quantites} (une case par valeur de {@code valeurs}).
     * On prend d'abord les petites pièces, puis on rend la monnaie en commençant par les grosses.
     * Renvoie les nouvelles quantités, ou null si le total ne suffit pas.
     * Si aucune pièce ne vaut 1, un reste impossible à rendre est perdu par le payeur (arrondi).
     */
    public static int[] payer(int[] valeurs, int[] quantites, long montant) {
        if (montant <= 0) return quantites.clone();
        if (total(valeurs, quantites) < montant) return null;
        Integer[] ordre = new Integer[valeurs.length];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        java.util.Arrays.sort(ordre, (a, b) -> Integer.compare(valeurs[a], valeurs[b]));

        int[] q = quantites.clone();
        long reste = montant;
        for (int i : ordre) {
            if (reste <= 0) break;
            if (valeurs[i] <= 0) continue;
            long pris = Math.min(q[i], (reste + valeurs[i] - 1) / valeurs[i]);
            q[i] -= (int) pris;
            reste -= pris * valeurs[i];
        }
        long rendu = -reste;
        for (int k = ordre.length - 1; k >= 0 && rendu > 0; k--) {
            int i = ordre[k];
            if (valeurs[i] <= 0) continue;
            long n = rendu / valeurs[i];
            q[i] += (int) n;
            rendu -= n * valeurs[i];
        }
        return q;
    }

    /** Décompose un montant en pièces, des plus grosses aux plus petites. */
    public static int[] rendre(int[] valeurs, long montant) {
        int[] q = new int[valeurs.length];
        Integer[] ordre = new Integer[valeurs.length];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        java.util.Arrays.sort(ordre, (a, b) -> Integer.compare(valeurs[b], valeurs[a]));
        for (int i : ordre) {
            if (valeurs[i] <= 0) continue;
            q[i] = (int) (montant / valeurs[i]);
            montant -= (long) q[i] * valeurs[i];
        }
        return q;
    }

    /** « 1 rouage en laiton et 3 pièces de bronze » à partir d'un montant, pour les messages. */
    public static String texte(long montant, int[] valeurs, String[] noms) {
        int[] q = rendre(valeurs, montant);
        StringBuilder sb = new StringBuilder();
        Integer[] ordre = new Integer[valeurs.length];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        java.util.Arrays.sort(ordre, (a, b) -> Integer.compare(valeurs[b], valeurs[a]));
        for (int i : ordre) {
            if (q[i] == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(q[i]).append(' ').append(noms[i]);
        }
        return sb.length() == 0 ? "rien" : sb.toString();
    }
}

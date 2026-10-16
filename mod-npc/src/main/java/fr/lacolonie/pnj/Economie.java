package fr.lacolonie.pnj;

import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * L'économie entre PNJ (lot 11, cahier 9.4), en pur Java : le prix d'une sorte de nourriture, le marchandage selon le
 * caractère, l'accord « 1 pièce », et le paiement exact d'une bourse à l'autre (rien ne se perd, rien ne se crée). Le jeu
 * (qui achète à qui, quand) est dans {@link Achats}. La monnaie : les pièces de {@link Reglages#monnaie}, en valeur.
 */
final class Economie {
    private Economie() {}

    /** Le prix d'une unité : celui de la table ({@code prix}, par identifiant), sinon un par tranche de 3 points de nourriture (1 au moins). */
    static int prix(Map<String, Integer> table, String objet, int nutrition) {
        Integer p = table == null ? null : table.get(objet);
        if (p != null && p > 0) return p;
        return Math.max(1, (nutrition + 2) / 3);
    }

    /** Chance qu'un acheteur de ce caractère marchande. */
    static double envieDeMarchander(String temperament) {
        return switch (temperament == null ? "" : temperament) {
            case "fourbe" -> 0.7;
            case "colerique" -> 0.5;
            case "calme" -> 0.25;
            case "peureux" -> 0.05;
            default -> 0.3;
        };
    }

    /**
     * Le prix après marchandage : s'il marchande (selon son caractère), un quart de rabais (une pièce au moins), jamais sous
     * une pièce. Un prix d'une pièce ne se marchande pas.
     */
    static int marchander(String temperament, int prix, DoubleSupplier hasard) {
        if (prix < 2 || hasard.getAsDouble() >= envieDeMarchander(temperament)) return prix;
        return Math.max(1, prix - Math.max(1, prix / 4));
    }

    /** « 1 pièces » devient « 1 pièce » (les répliques disent toujours « {prix} pièces »). */
    static String accorder(String texte) {
        return texte == null ? null : texte.replaceAll("(?<![0-9])1 pièces", "1 pièce");
    }

    /** Le résultat d'un paiement : les nouvelles quantités de pièces de l'acheteur et du vendeur. */
    record Paiement(int[] acheteur, int[] vendeur) {}

    /**
     * L'acheteur paie {@code montant} au vendeur avec ses pièces (une case par valeur de {@code valeurs}). Il rend la monnaie
     * lui-même (petites pièces d'abord, comme {@link Monnaie#payer}) ; le vendeur reçoit exactement {@code montant} en pièces.
     * Null (rien ne bouge) si l'acheteur n'a pas assez, ou si la somme ne peut pas passer exactement : la valeur totale des
     * deux bourses ne change jamais.
     */
    static Paiement payer(int[] valeurs, int[] acheteur, int[] vendeur, long montant) {
        if (montant <= 0) return null;
        int[] apres = Monnaie.payer(valeurs, acheteur, montant);
        if (apres == null || Monnaie.total(valeurs, acheteur) - Monnaie.total(valeurs, apres) != montant) return null;
        int[] recu = Monnaie.rendre(valeurs, montant);
        if (Monnaie.total(valeurs, recu) != montant) return null;
        int[] v = vendeur.clone();
        for (int i = 0; i < v.length; i++) v[i] += recu[i];
        return new Paiement(apres, v);
    }
}

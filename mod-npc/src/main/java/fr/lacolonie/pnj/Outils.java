package fr.lacolonie.pnj;

/**
 * Les outils des ouvriers (cahier 3.12 et 3.13), pur Java : l'usure, plus lente que pour un joueur, et la note qui dit
 * quel outil est le meilleur (le matériau d'abord, puis le moins usé). {@link Metier} s'en sert pour prendre l'outil au
 * dépôt, {@link Actions#userOutil} pour l'user.
 */
final class Outils {
    private Outils() {}

    /**
     * Les points d'usure pour {@code n} coups, quand l'outil s'use {@code lent} fois moins vite que pour un joueur : n / lent,
     * arrondi au hasard ({@code alea} entre 0 et 1) pour que la moyenne soit juste (1 coup sur 3 : 1 point une fois sur 3).
     */
    static int usure(int n, int lent, double alea) {
        if (n <= 0) return 0;
        lent = Math.max(1, lent);
        return n / lent + (alea * lent < n % lent ? 1 : 0);
    }

    /** Le rang du matériau : 1 bois ou or, 2 pierre, 3 fer, 4 diamant, 5 netherite ; un matériau inconnu (d'un mod) d'après sa vitesse. */
    static int rang(String materiau, float vitesse) {
        return switch (materiau) {
            case "WOOD", "GOLD" -> 1;
            case "STONE" -> 2;
            case "IRON" -> 3;
            case "DIAMOND" -> 4;
            case "NETHERITE" -> 5;
            default -> vitesse < 3 ? 1 : vitesse < 5 ? 2 : vitesse < 7 ? 3 : vitesse < 8.5 ? 4 : 5;
        };
    }

    /** La note d'un outil : le rang de son matériau d'abord (en milliers), puis ce qu'il lui reste de solidité. Toujours plus de 1000. */
    static double note(int rang, int degats, int max) {
        return Math.max(1, rang) * 1000.0 + (max > 0 ? 999.0 * Math.max(0, max - degats) / max : 999);
    }

    /** Au-delà de cette note, un outil est d'un matériau meilleur que celui noté {@code note} (pas seulement moins usé). */
    static double seuilMieux(double note) { return (Math.floor(note / 1000) + 1) * 1000 - 0.5; }
}

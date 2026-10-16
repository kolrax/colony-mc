package fr.lacolonie.pnj;

/**
 * Ce que fait un ouvrier qui ne se bat pas devant un monstre (pas un creeper : tous s'en éloignent), selon son
 * tempérament (consigne du chef de projet, réglage {@code caractere_monstres}). Logique pure, sans Minecraft :
 * {@link Defense#ouvrier} rassemble ce qu'il voit et applique la réaction.
 * <ul>
 * <li>colérique : il fonce frapper le monstre avec ce qu'il a en main ; blessé, il fuit comme un peureux ;</li>
 * <li>calme : il signale le monstre ; frappé, ou un collègue frappé tout près, il se défend ;</li>
 * <li>peureux : il fuit tout monstre en vue, en criant, et prévient la garde ;</li>
 * <li>fourbe : il s'éclipse sans prévenir, ou envoie un collègue tout proche à sa place ; frappé et acculé, il se défend.</li>
 * </ul>
 */
final class Bravoure {
    private Bravoure() {}

    enum Reaction { SIGNALER, CHARGER, RIPOSTER, FUIR, ECLIPSER, POUSSER }

    /** Jusqu'où il remarque un monstre : le peureux le voit venir de plus loin. */
    static double vue(String temperament) { return "peureux".equals(temperament) ? 12 : Defense.VUE; }

    /** Sous cette part de sa vie, même le colérique ne se bat plus. */
    static final double FAIBLE = 0.4;

    /**
     * @param faible         moins de 40 % de sa vie, ou hors de combat
     * @param frappe         un monstre en vue vient de le frapper
     * @param collegueFrappe un collègue de son chef, à 6 blocs, vient d'être frappé par un monstre en vue
     * @param collegue       un collègue de son chef, debout, à 8 blocs
     * @param refuge         il a où aller pour s'éloigner du monstre
     */
    static Reaction face(String temperament, boolean faible, boolean frappe, boolean collegueFrappe, boolean collegue, boolean refuge) {
        return switch (temperament) {
            case "colerique" -> faible ? Reaction.FUIR : Reaction.CHARGER;
            case "calme" -> !faible && (frappe || collegueFrappe) ? Reaction.RIPOSTER : Reaction.SIGNALER;
            case "peureux" -> Reaction.FUIR;
            case "fourbe" -> frappe && !refuge ? Reaction.RIPOSTER : collegue ? Reaction.POUSSER : Reaction.ECLIPSER;
            default -> Reaction.SIGNALER;
        };
    }
}

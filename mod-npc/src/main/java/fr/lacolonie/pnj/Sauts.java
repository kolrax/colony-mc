package fr.lacolonie.pnj;

/**
 * Les sauts d'un ouvrier, pour qu'il ne saute pas sur place sans fin (plan F1). Calcul pur, sans le jeu : testé par SautsTest.
 * <p>
 * Un point visé juste au-dessus de lui mais hors d'atteinte (rien pour s'y poser, plafond, bloc qui ne se pose pas) le
 * faisait sauter à chaque atterrissage, indéfiniment. Ici, plus de {@link #MAX} sauts depuis la même case, sans en être
 * sorti, et il renonce à sauter pendant {@link #PAUSE} ticks : l'action en cours voit alors qu'il n'avance plus et change
 * de plan (détour, abandon, coincé). Un saut qui le fait monter ou avancer part d'une autre case : il ne compte pas.
 */
final class Sauts {
    /** Sauts permis depuis la même case avant de renoncer. */
    static final int MAX = 4;
    /** Renoncement : 10 s sans sauter. */
    static final int PAUSE = 200;
    /** Deux sauts depuis la même case à plus de 5 s d'écart : ce n'est plus la même boucle. */
    static final int OUBLI = 100;

    private long cas = Long.MIN_VALUE, dernier = Long.MIN_VALUE / 2, jusqua = Long.MIN_VALUE;
    private int n;

    /** Il veut sauter depuis cette case ({@code BlockPos.asLong}) maintenant : vrai s'il le peut. */
    boolean permis(long caseDepart, long maintenant) {
        if (maintenant < jusqua) return false;
        if (caseDepart != cas || maintenant - dernier > OUBLI) { cas = caseDepart; n = 0; }
        dernier = maintenant;
        if (++n > MAX) { jusqua = maintenant + PAUSE; n = 0; return false; }
        return true;
    }
}

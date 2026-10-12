package fr.lacolonie.pnj;

/**
 * Le temps qu'un PNJ passe coincé, pour le ramener au poste en dernier recours (cahier 11.3 et 14.10 ; exception accordée
 * le 11/10, la seule téléportation permise). Calcul pur, sans le jeu : testé par CoinceTest.
 * <p>
 * Le temps compte quand il veut marcher vers un endroit fixe sans avancer (creuser ou poser des blocs qui avancent, ce
 * n'est pas être coincé ; suivre quelqu'un qui bouge non plus), ou, sans rien faire d'autre qu'attendre, quand il a échoué à
 * se rendre quelque part (« bloque », « inaccessible »). Il repart de zéro dès qu'il progresse vraiment (arrivé à son but,
 * ou à plus de {@link #PROGRES} blocs de l'endroit où le compte a commencé), et chaque fois qu'il ne pourrait pas être
 * ramené (il suit, attend, dort, se bat, reçoit un ordre…) : ce temps-là ne compte pas.
 */
final class Coince {
    /** Au bout de ce temps coincé (en ticks), il est ramené au poste : 5 minutes (les tests l'abaissent). */
    static int DELAI = 5 * 60 * 20;
    /** Pas deux retours au poste à moins de 10 minutes d'écart : sinon, une boucle. */
    static int ENTRE_DEUX = 10 * 60 * 20;
    /** Pas de place sûre au poste : il réessaie une minute plus tard. */
    static final int REESSAI = 60 * 20;
    /** Après tant de retours sans vrai travail entre eux, plus de téléportation : il attend les ordres au poste. */
    static final int RETOURS_MAX = 2;
    /** Un échec ne compte que pendant la minute qui le suit (les tests l'abaissent) : ensuite, il attend pour autre chose. */
    static int FENETRE_ECHEC = 60 * 20;
    /** Le dernier retour date de plus d'une heure : les retours ne s'enchaînent plus. */
    static final int OUBLI_RETOURS = 60 * 60 * 20;
    /** Se trouver à plus de 8 blocs de l'endroit où le compte a commencé, c'est avoir vraiment avancé. */
    static final double PROGRES = 8;

    private double x, y, z;
    private boolean depart;
    /** Le temps coincé cumulé, en ticks. */
    int ticks;
    /** Il a voulu marcher (vers un endroit fixe, sans creuser ni poser) depuis le dernier relevé. */
    boolean marche;
    /** Il a échoué à se rendre quelque part (« bloque », « inaccessible ») depuis son dernier vrai progrès. */
    boolean echec;
    /** Quand (temps du monde) : l'échec ne compte que pendant {@link #FENETRE_ECHEC} ticks. */
    long echecDate;
    /** Il a été mis à attendre au poste après {@link #RETOURS_MAX} retours (pas par son chef). */
    boolean attenteAuto;
    /** Retours au poste sans vrai travail entre eux. */
    int retours;
    /** La lettre « pas de place au poste » est partie (une seule fois, jusqu'au prochain retour ou travail). */
    boolean sansPlaceEcrit;
    private long dernierRetour = Long.MIN_VALUE / 2, pasAvant = Long.MIN_VALUE;

    /** Il a vraiment avancé, ou ce temps ne compte pas : le compte repart de zéro, d'ici. */
    void progres(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        depart = true;
        ticks = 0;
        echec = false;
        marche = false;
    }

    /** Il a échoué à se rendre quelque part, maintenant. */
    void echec(long maintenant) { echec = true; echecDate = maintenant; }

    /**
     * Son mode, à chaque relevé. Mis à attendre au poste après deux retours, puis remis au travail par son chef : un seul
     * retour de plus est permis (et le remet à attendre), pour qu'il ne reste jamais coincé pour de bon.
     */
    void mode(boolean attend) {
        if (attenteAuto && !attend) { attenteAuto = false; retours = RETOURS_MAX - 1; }
    }

    /** Il a fait un vrai travail (casser, abattre, poser) : les retours au poste ne s'enchaînent plus. */
    void travail() { retours = 0; sansPlaceEcrit = false; }

    /**
     * Un relevé, tous les {@code pas} ticks, à sa position du moment ({@code attente} : sans action, ou à attendre que le
     * cerveau décide). Vrai s'il est coincé depuis {@link #DELAI} ticks, que son dernier retour au poste date d'au moins
     * {@link #ENTRE_DEUX} ticks, et qu'il n'a pas déjà été ramené {@link #RETOURS_MAX} fois sans travailler entre-temps.
     */
    boolean relever(double x, double y, double z, int pas, long maintenant, boolean attente) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        boolean voulu = marche;
        marche = false;
        if (!depart || dx * dx + dy * dy + dz * dz > PROGRES * PROGRES) { progres(x, y, z); return false; }
        if (maintenant - dernierRetour > OUBLI_RETOURS) retours = 0;
        if (voulu || echec && attente && maintenant - echecDate <= FENETRE_ECHEC) ticks += pas;
        return ticks >= DELAI && maintenant - dernierRetour >= ENTRE_DEUX && maintenant >= pasAvant && retours < RETOURS_MAX;
    }

    /** Pas de place sûre au poste : il réessaiera dans {@link #REESSAI} ticks. */
    void reporter(long maintenant) { pasAvant = maintenant + REESSAI; }

    /** Il vient d'être ramené au poste : tout repart de zéro, et pas de nouveau retour avant {@link #ENTRE_DEUX}. */
    void ramene(long maintenant) {
        dernierRetour = maintenant;
        depart = false;
        ticks = 0;
        echec = false;
        marche = false;
        retours++;
        sansPlaceEcrit = false;
    }
}

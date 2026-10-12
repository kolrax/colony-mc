package fr.lacolonie.pnj;

/**
 * Le temps qu'un PNJ passe coincé, pour le ramener au poste en dernier recours (cahier 11.3 et 14.10 ; exception accordée
 * le 11/10, la seule téléportation permise). Calcul pur, sans le jeu : testé par CoinceTest.
 * <p>
 * Le temps compte quand il veut marcher (une action le fait avancer) ou qu'il a échoué à se rendre quelque part
 * (« bloque », « inaccessible ») ; il repart de zéro dès qu'il progresse vraiment : arrivé à son but, ou à plus de
 * {@link #PROGRES} blocs de l'endroit où le compte a commencé. Travailler sur place ne compte pas.
 */
final class Coince {
    /** Au bout de ce temps coincé (en ticks), il est ramené au poste : 5 minutes (les tests l'abaissent). */
    static int DELAI = 5 * 60 * 20;
    /** Pas deux retours au poste à moins de 10 minutes d'écart : sinon, une boucle. */
    static int ENTRE_DEUX = 10 * 60 * 20;
    /** Se trouver à plus de 8 blocs de l'endroit où le compte a commencé, c'est avoir vraiment avancé. */
    static final double PROGRES = 8;

    private double x, y, z;
    private boolean depart;
    /** Le temps coincé cumulé, en ticks. */
    int ticks;
    /** Il a voulu marcher depuis le dernier relevé. */
    boolean marche;
    /** Il a échoué à se rendre quelque part (« bloque », « inaccessible ») depuis son dernier vrai progrès. */
    boolean echec;
    private long dernierRetour = Long.MIN_VALUE / 2;

    /** Il a vraiment avancé (arrivé où il allait) : le compte repart de zéro, d'ici. */
    void progres(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        depart = true;
        ticks = 0;
        echec = false;
    }

    /**
     * Un relevé, tous les {@code pas} ticks, à sa position du moment : vrai s'il est coincé depuis {@link #DELAI} ticks
     * et que son dernier retour au poste date d'au moins {@link #ENTRE_DEUX} ticks.
     */
    boolean relever(double x, double y, double z, int pas, long maintenant) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        boolean voulu = marche;
        marche = false;
        if (!depart || dx * dx + dy * dy + dz * dz > PROGRES * PROGRES) { progres(x, y, z); return false; }
        if (voulu || echec) ticks += pas;
        return ticks >= DELAI && maintenant - dernierRetour >= ENTRE_DEUX;
    }

    /** Il vient d'être ramené au poste : tout repart de zéro, et pas de nouveau retour avant {@link #ENTRE_DEUX}. */
    void ramene(long maintenant) {
        dernierRetour = maintenant;
        depart = false;
        ticks = 0;
        echec = false;
        marche = false;
    }
}

package fr.lacolonie.pnj;

import java.util.List;

/**
 * Ce qu'on lit au-dessus de la tête d'un ouvrier (lot 8) : sous son nom, son rôle (« Mineur · KOLRAX_MC », ou « Cherche
 * du travail »), et au-dessus, une seule icône d'état, la plus pressante. Pur Java : le serveur le calcule
 * ({@code Travailleur.majTete}), le client le dessine ({@code Client.Rendu.renderNameTag}).
 */
public final class Insignes {
    private Insignes() {}

    /** Les états, du plus pressant au moins pressant ; chacun a son icône dans {@code textures/gui/etat/<etat>.png}. */
    static final List<String> ETATS = List.of("blesse", "peur", "greve", "faim", "attente", "paie", "colere", "sommeil");
    /** Faim forte (il ne travaille plus, voir {@code Cerveau}), moral en colère, santé de blessé. */
    static final int FAIM = 8, COLERE = 30, BLESSE = 6;
    /** Chez le joueur : le prénom se lit jusqu'à {@link #PORTEE_NOM} blocs, l'icône du métier, le rôle et l'état jusqu'à {@link #PORTEE_DETAILS}. */
    static final int PORTEE_NOM = 32, PORTEE_DETAILS = 12;
    /** Chez le joueur : la vue directe sur un ouvrier est revue toutes les {@link #REVUE} ticks. */
    static final int REVUE = 10;

    /** Ce qu'il est et pour qui : « Mineur · KOLRAX_MC » ; son métier seul si on ne sait pas qui est son chef ; « Cherche du travail » sans emploi. */
    static String role(String nomMetier, String chef, boolean embauche) {
        if (!embauche) return "Cherche du travail";
        if (chef == null || chef.isEmpty()) return nomMetier;
        return nomMetier + " · " + chef;
    }

    /** Revoir la vue directe, vue pour la dernière fois au tick {@code vueLe} ? Oui aussi la première fois ({@code Long.MIN_VALUE}) et si le temps a reculé. */
    static boolean arevoir(long maintenant, long vueLe) {
        return maintenant < vueLe || maintenant >= vueLe + REVUE; // pas « maintenant - vueLe » : déborde avec Long.MIN_VALUE
    }

    /** « Philippa (Mineur) », « Philippa · en grève » → « Philippa » (le nom du jeu, posé par {@code Travailleur.majNom}). */
    static String prenom(String nom) {
        int i = nom.indexOf(" (");
        int j = nom.indexOf(" ·");
        if (j >= 0 && (i < 0 || j < i)) i = j;
        return (i < 0 ? nom : nom.substring(0, i)).trim();
    }

    /** Ce qu'on sait de lui, lu chaque seconde par le serveur. */
    record Corps(boolean aTerre, float sante, boolean peur, boolean greve, int faim, boolean attend, int impayes, int moral, boolean repos) {}

    /** L'icône d'état à montrer (une de {@link #ETATS}), ou "" s'il va bien. */
    static String etat(Corps c) {
        if (c.aTerre() || c.sante() <= BLESSE) return "blesse";
        if (c.peur()) return "peur";
        if (c.greve()) return "greve";
        if (c.faim() <= FAIM) return "faim";
        if (c.attend()) return "attente";
        if (c.impayes() > 0) return "paie";
        if (c.moral() < COLERE) return "colere";
        if (c.repos()) return "sommeil";
        return "";
    }

    /** La texture d'une icône (sans l'espace de noms) : {@code dossier} = « etat » ou « metier ». Métier inconnu : celle du chômeur. */
    static String icone(String dossier, String nom) {
        if (dossier.equals("metier") && !Tenues.METIERS.contains(nom)) nom = Tenues.CHOMEUR;
        return "textures/gui/" + dossier + "/" + nom + ".png";
    }
}

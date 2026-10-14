package fr.lacolonie.pnj;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La cuisine du cuisinier (lot 6, cahier 4.2), pur Java : combien de pains faire pour garder la réserve, quoi mettre au four,
 * quel combustible et combien (jamais plus que nécessaire). Le jeu est dans {@link Cuisinier}.
 */
final class Cuisine {
    private Cuisine() {}

    /** Ses ordres (« cuis la viande », « va cuisiner ») : le vocabulaire « travail » du répertoire. */
    static final Set<String> TRAVAUX = Set.of("cuire", "cuisiner");
    /** Les pains qu'il garde au dépôt (sans commande du chef). */
    static final int RESERVE = 16;
    /** Au plus tant d'objets par fournée (une pile). */
    static final int FOURNEE = 64;
    /** Une semence (pomme de terre, algue) : il en laisse tant au dépôt pour le fermier. */
    static final int SEMENCES = 16;
    /** Ticks de feu pour cuire un objet au fourneau (le fumoir cuit deux fois plus vite, mais brûle deux fois plus vite). */
    static final int CUISSON = 200;
    /** Le four : à tant de blocs au plus de son poste. */
    static final int RAYON = 16;

    /**
     * Combien de pains faire : de quoi remonter la réserve du dépôt ({@code reserve}), ou ce qu'il reste à livrer d'une
     * commande ({@code aLivrer}), le plus grand des deux ; 3 blés par pain, autant que le blé le permet.
     */
    static int pains(int auDepot, int reserve, int aLivrer, int ble) {
        int voulu = Math.max(Math.max(0, reserve - auDepot), aLivrer);
        return Math.max(0, Math.min(voulu, ble / 3));
    }

    /** Ce qu'il met au four d'un cru qu'il y a {@code n} fois au dépôt : tout, sauf les semences qu'il laisse au fermier. */
    static int aCuire(int n, boolean semence) { return semence ? Math.max(0, n - SEMENCES) : n; }

    /** Combien de combustible, qui brûle {@code brule} ticks au fourneau, pour cuire {@code n} objets (arrondi au-dessus). */
    static int combustible(int n, int brule) { return brule <= 0 ? Integer.MAX_VALUE : (n * CUISSON + brule - 1) / brule; }

    /** Combien d'objets cuisent avec {@code k} combustibles qui brûlent {@code brule} ticks chacun. */
    static int cuisent(int k, int brule) { return brule <= 0 ? 0 : k * brule / CUISSON; }

    /** Un combustible du dépôt : combien il y en a, combien de ticks il brûle. */
    record Feu(int dispo, int brule) {}

    /** Le combustible choisi : son rang dans la liste, combien en mettre, combien d'objets cuire. */
    record Chauffe(int feu, int nombre, int cuire) {}

    /**
     * Le combustible pour cuire {@code crus} objets : le premier de la liste qui en cuit au moins un (charbon de bois et
     * charbon d'abord, le bois en dernier recours), et juste ce qu'il faut ; s'il n'y en a pas assez, il cuit ce qu'il peut.
     * Null : rien à brûler.
     */
    static Chauffe chauffe(int crus, List<Feu> feux) {
        if (crus <= 0) return null;
        for (int i = 0; i < feux.size(); i++) {
            Feu f = feux.get(i);
            int cuire = Math.min(crus, cuisent(f.dispo(), f.brule()));
            if (cuire <= 0) continue;
            return new Chauffe(i, Math.min(f.dispo(), combustible(cuire, f.brule())), cuire);
        }
        return null;
    }

    enum Quoi { PAIN, CUIRE, SANS_FOUR, SANS_COMBUSTIBLE, RIEN }

    /** Ce qu'il fait maintenant ; {@code lettre} : ce qu'il écrit à son chef (ou null). {@code cru} : ce qu'il met au four. */
    record Choix(Quoi quoi, int nombre, String cru, String lettre) {}

    /**
     * Sa prochaine tâche. Le pain d'abord ({@code pains} à faire : il n'y faut pas de four) ; puis le cru le plus abondant du
     * dépôt ({@code crus} : combien de chacun il peut cuire, dans l'ordre où il les a vus), s'il a un four et de quoi le
     * chauffer ({@code feu}) ; sinon la lettre qui dit ce qui manque. {@code manqueBle} : la réserve de pain est basse et il n'y
     * a plus de blé (la lettre part aussi pendant qu'il cuit autre chose).
     */
    static Choix choisir(int pains, boolean manqueBle, Map<String, Integer> crus, boolean four, boolean feu) {
        if (pains > 0) return new Choix(Quoi.PAIN, pains, null, null);
        String cru = null;
        int n = 0;
        for (var e : crus.entrySet()) if (e.getValue() > n) { n = e.getValue(); cru = e.getKey(); }
        if (cru == null) return new Choix(Quoi.RIEN, 0, null, manqueBle ? "sans_ble" : "rien_a_cuire");
        if (!four) return new Choix(Quoi.SANS_FOUR, 0, cru, "sans_four");
        if (!feu) return new Choix(Quoi.SANS_COMBUSTIBLE, 0, cru, "sans_combustible");
        return new Choix(Quoi.CUIRE, Math.min(n, FOURNEE), cru, manqueBle ? "sans_ble" : null);
    }

    /** Ce qui lui manque, en un mot, pour la lettre « rien à cuire » : « poisson » s'il cuisait du poisson, sinon « gibier ». */
    static String manque(String dernierCru) {
        return dernierCru != null && (dernierCru.contains("cod") || dernierCru.contains("salmon") || dernierCru.contains("fish")) ? "poisson" : "gibier";
    }
}

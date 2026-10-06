package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un métier, codé dans le mod. Le cerveau commun ({@link Cerveau}) s'occupe des besoins du PNJ, puis appelle
 * {@link #travailler}, qui décide de la prochaine action et la lance. Chaque PNJ garde son état de travail
 * dans {@code t.memoire} (sauvegardé avec lui).
 */
public abstract class Metier {
    public final String id, nom, description;
    /** Riposte quand on l'attaque (sinon il fuit). */
    final boolean combattant;
    /** Ce qu'il garde sur lui en vidant son sac au dépôt (une pile de chaque). */
    final List<Actions.Filtre> garder = new ArrayList<>();
    /** Le catalogue des commandes, affiché dans l'écran du bureau. */
    final List<Commande> commandes = new ArrayList<>();

    Metier(String id, String nom, String description, boolean combattant, String... garder) {
        this.id = id;
        this.nom = nom;
        this.description = description;
        this.combattant = combattant;
        for (String g : garder) this.garder.add(Actions.Filtre.de(g));
    }

    /** Une ligne du catalogue. {@code objet} : ce qui se compte au dépôt ou à la livraison (null : ni stock ni quantité). */
    public static class Commande {
        public final String id, nom;
        public final Actions.Filtre objet;

        Commande(String id, String nom, String objet) {
            this.id = id;
            this.nom = nom;
            this.objet = objet == null ? null : Actions.Filtre.de(objet);
        }
    }

    /** Décide de la prochaine action et la lance (ou rien : le cerveau rappellera dans une demi-seconde). */
    abstract void travailler(Travailleur t);

    /** Appelé même quand il est occupé : réagir à une urgence. */
    void vigilance(Travailleur t) {}

    /** En mode « Suivre » : vrai s'il a trouvé mieux à faire que suivre (le guerrier combat). */
    boolean escorter(Travailleur t) { return false; }

    // --- Le registre --------------------------------------------------------------------------------

    static final Map<String, Metier> TOUS = new LinkedHashMap<>();

    static {
        for (Metier m : List.of(new Mineur(), new Bucheron(), new Guerrier())) TOUS.put(m.id, m);
    }

    static Metier de(String id) { return TOUS.get(id); }

    static boolean existe(String id) { return TOUS.containsKey(id); }

    static List<String> actifs() {
        return TOUS.keySet().stream().filter(m -> !Reglages.actuel.metiers_inactifs.contains(m)).toList();
    }

    static String nom(String id) { Metier m = de(id); return m == null ? id : m.nom; }

    static String description(String id) { Metier m = de(id); return m == null ? "" : m.description; }

    // --- Les commandes ------------------------------------------------------------------------------

    /** La commande en cours du PNJ, ou null. */
    static Commande commande(Travailleur t) {
        Metier m = de(t.metier);
        if (m == null || t.commande == null || t.commande.isEmpty()) return null;
        for (Commande c : m.commandes) if (c.id.equals(t.commande)) return c;
        return null;
    }

    static String nomCommande(String metier, String id) {
        Metier m = de(metier);
        if (m != null) for (Commande c : m.commandes) if (c.id.equals(id)) return c.nom;
        return id;
    }

    /** L'objet produit par la commande en cours (filtre qui ne prend rien s'il n'y en a pas). */
    static Actions.Filtre objetCommande(Travailleur t) {
        Commande c = commande(t);
        return c != null && c.objet != null ? c.objet : Actions.Filtre.de("");
    }

    /** Où en est la commande : le stock au dépôt (mode stock) ou ce qu'il a livré (mode quantité) ; -1 si on ne sait pas. */
    static int avancement(Travailleur t) {
        if (commande(t) == null) return -1;
        if (t.commandeMode.equals("quantite")) return t.commandeLivre;
        Bureau.Entite b = Bureau.entite(t);
        return b == null ? -1 : b.compter(objetCommande(t));
    }

    static boolean commandeRemplie(Travailleur t) {
        Commande c = commande(t);
        if (c == null || c.objet == null) return false;
        int a = avancement(t);
        return a >= 0 && a >= t.commandeNombre;
    }

    static String avancementTexte(Travailleur t) {
        Commande c = commande(t);
        if (c == null) return "Aucune commande";
        if (c.objet == null) return c.nom;
        int a = avancement(t);
        String n = (a < 0 ? "?" : String.valueOf(a)) + "/" + t.commandeNombre;
        return c.nom + " : " + (t.commandeMode.equals("quantite") ? n + " livrés" : n + " au dépôt") + (commandeRemplie(t) ? " (fait)" : "");
    }
}

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
        this.garder.add(Actions.Filtre.de("#minecraft:boats")); // son bateau, s'il en a un, pour traverser l'eau
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

    // --- Les missions ------------------------------------------------------------------------------

    /** L'entrée du catalogue de ce métier, ou null. */
    static Commande catalogue(String metier, String id) {
        Metier m = de(metier);
        if (m == null || id == null) return null;
        for (Commande c : m.commandes) if (c.id.equals(id)) return c;
        return null;
    }

    /** L'entrée du catalogue de sa mission en cours, ou null. */
    static Commande commande(Travailleur t) {
        Travailleur.Mission m = t.missionActive();
        return m == null ? null : catalogue(t.metier, m.id);
    }

    static String nomCommande(String metier, String id) {
        Commande c = catalogue(metier, id);
        return c == null ? id : c.nom;
    }

    /** L'objet produit par la mission en cours (filtre qui ne prend rien s'il n'y en a pas). */
    static Actions.Filtre objetCommande(Travailleur t) {
        Commande c = commande(t);
        return c != null && c.objet != null ? c.objet : Actions.Filtre.de("");
    }

    /** Où en est une mission : le stock au dépôt (stock) ou ce qu'il a livré (quantité) ; -1 si on ne sait pas. */
    static int avancement(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null || c.objet == null) return -1;
        if (m.quantite()) return m.livre;
        Bureau.Entite b = Bureau.entite(t);
        return b == null ? -1 : b.compter(c.objet);
    }

    /** Une mission est remplie : quantité livrée, ou stock plein. Garder, faire la ronde ou escorter ne le sont jamais. */
    static boolean remplie(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null) return true;
        if (c.objet == null) return false;
        int a = avancement(t, m);
        return a >= 0 && a >= m.nombre;
    }

    /** « 10 fers », « un stock de 32 charbons », « Faire la ronde ». */
    static String nomMission(String metier, Travailleur.Mission m) {
        Commande c = catalogue(metier, m.id);
        if (c == null) return m.id;
        if (c.objet == null) return c.nom;
        if (m.quotidienne) return m.nombre + " " + c.nom.toLowerCase() + " chaque jour";
        return m.quantite() ? m.nombre + " " + c.nom.toLowerCase() : "un stock de " + m.nombre + " " + c.nom.toLowerCase();
    }

    /** « Fer : 7/10 livrés », « Charbon : 40/64 au dépôt (plein) », « Faire la ronde ». */
    static String avancementTexte(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null) return m.id;
        if (c.objet == null) return c.nom;
        int a = avancement(t, m);
        String n = (a < 0 ? "?" : String.valueOf(a)) + "/" + m.nombre;
        return c.nom + " : " + (m.quantite() ? n + " livrés" : n + " au dépôt") + (remplie(t, m) ? (m.quotidienne ? " (fait pour aujourd'hui)" : m.quantite() ? " (fait)" : " (plein)") : m.quotidienne ? " (aujourd'hui)" : "");
    }

    /** Toutes ses missions, en une ligne (pour le panneau web). */
    static String avancementTexte(Travailleur t) {
        if (t.missions.isEmpty()) return "Aucune mission";
        return String.join(" ; ", t.missions.stream().map(m -> avancementTexte(t, m)).toList());
    }
}

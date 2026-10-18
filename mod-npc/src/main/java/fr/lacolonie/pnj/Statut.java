package fr.lacolonie.pnj;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Le statut unique d'un ouvrier (refonte 1.0.0, partie 1) : ce qu'on lit au-dessus de sa tête, dans sa fiche et au poste.
 * Quatre sortes seulement : en cours (« ⛏ Mine · 32/64 fer »), bloqué avec la raison et la solution (« ⚠ Pas de pioche au
 * dépôt »), « En attente », « Hors horaires ». Et la phrase de confirmation d'une nouvelle mission (« Compris : Raoul mine
 * du fer jusqu'à 64. »). Pur Java : le serveur assemble ({@code Travailleur.statutUnique}), le client affiche.
 */
public final class Statut {
    private Statut() {}

    public static final String ATTENTE = "En attente", HORS_HORAIRES = "Hors horaires";

    // Les blocages, avec leur solution : posés par les métiers ({@code Travailleur.bloquer}).
    public static final String SAC_PLEIN = "Sac plein, dépôt plein", LIEU_MINE = "Aucun lieu sûr pour miner : dis-lui « Travaille ici »",
            ENCLOS = "Pas d'enclos", SANS_POSTE = "Pas de poste de travail", SANS_MISSION = "Aucune mission : clic droit pour en donner une",
            A_TERRE = "À terre : à manger ou une potion de soin", GREVE = "En grève : la paie manque à la caisse";

    /** « Pas de pioche au dépôt ». */
    public static String outil(String nom) { return "Pas de " + nom + " au dépôt"; }

    /** Une icône par métier (des signes que la police du jeu sait dessiner). */
    static final Map<String, String> ICONES = Map.ofEntries(
            Map.entry("mineur", "⛏"), Map.entry("bucheron", "♣"), Map.entry("guerrier", "⚔"), Map.entry("fermier", "⚘"),
            Map.entry("eleveur", "♞"), Map.entry("pecheur", "⚓"), Map.entry("chasseur", "➶"), Map.entry("cuisinier", "♨"),
            Map.entry("guerisseur", "✚"), Map.entry("transporteur", "⇄"), Map.entry("macon", "⚒"), Map.entry("boutiquier", "⚖"));
    /** Ce qu'il fait, en un mot, pour la ligne au-dessus de sa tête. */
    static final Map<String, String> VERBES = Map.ofEntries(
            Map.entry("mineur", "Mine"), Map.entry("bucheron", "Coupe"), Map.entry("guerrier", "Garde"), Map.entry("fermier", "Cultive"),
            Map.entry("eleveur", "Élève"), Map.entry("pecheur", "Pêche"), Map.entry("chasseur", "Chasse"), Map.entry("cuisinier", "Cuisine"),
            Map.entry("guerisseur", "Soigne"), Map.entry("transporteur", "Transporte"), Map.entry("macon", "Bâtit"), Map.entry("boutiquier", "Vend"));
    /** Le même, dans la phrase de confirmation (« Raoul mine du fer »). */
    static final Map<String, String> FAIT = Map.ofEntries(
            Map.entry("mineur", "mine"), Map.entry("bucheron", "coupe"), Map.entry("fermier", "récolte"), Map.entry("eleveur", "produit"),
            Map.entry("pecheur", "pêche"), Map.entry("chasseur", "rapporte"), Map.entry("cuisinier", "cuisine"));
    /** Ces métiers travaillent même sans mission (le poste leur dit quoi faire). */
    static final Set<String> SANS_MISSION_OK = Set.of("bucheron", "guerrier", "guerisseur", "cuisinier", "transporteur", "macon", "boutiquier");

    static String icone(String metier) { return ICONES.getOrDefault(metier, "⚒"); }

    static String verbe(String metier) { return VERBES.getOrDefault(metier, "Travaille"); }

    /** En cours : « ⛏ Mine · 32/64 fer » ; fait inconnu (-1) : « ?/64 » ; sans objet compté (nombre ≤ 0) : « ⚔ Garde · faire la ronde ». */
    public static String enCours(String metier, String nomCommande, int fait, int nombre) {
        String debut = icone(metier) + " " + verbe(metier);
        if (nomCommande == null || nomCommande.isEmpty()) return debut;
        if (nombre <= 0 && GARDES.containsKey(nomCommande)) return icone(metier) + " " + GARDES.get(nomCommande); // « ⚔ Défend la colonie »
        String nom = minuscule(nomCommande);
        if (nombre <= 0) return debut + " · " + nom;
        return debut + " · " + (fait < 0 ? "?" : String.valueOf(fait)) + "/" + nombre + " " + nom;
    }

    /** Bloqué : « ⚠ Pas de pioche au dépôt ». */
    public static String bloque(String raison) { return "⚠ " + raison; }

    /** Ce qu'on sait de lui pour choisir son statut. {@code commande} : le nom de la mission active, null s'il n'en a pas. */
    public record Etat(String metier, boolean aTerre, boolean greve, boolean attend, boolean suit, String blocage,
                       boolean aDesMissions, String commande, int fait, int nombre, boolean horsHoraires) {}

    /** Le statut unique, du plus pressant au moins pressant. */
    public static String texte(Etat e) {
        if (e.aTerre()) return bloque(A_TERRE);
        if (e.greve()) return bloque(GREVE);
        if (e.attend()) return ATTENTE;
        if (e.suit()) return icone(e.metier()) + " Suit son chef";
        if (e.blocage() != null && !e.blocage().isEmpty()) return bloque(e.blocage());
        if (e.commande() != null) return enCours(e.metier(), e.commande(), e.fait(), e.nombre());
        if (e.horsHoraires()) return HORS_HORAIRES;
        if (e.aDesMissions()) return ATTENTE; // toutes remplies : il attend que le stock baisse ou une autre mission
        if (SANS_MISSION_OK.contains(e.metier())) return enCours(e.metier(), null, 0, 0);
        return bloque(SANS_MISSION);
    }

    // --- Les horaires d'une mission ---------------------------------------------------------------------

    /** « jour » (6 h à 18 h), « nuit » (18 h à 6 h), « toujours », ou une plage « 8-12 » (qui peut passer minuit : « 22-4 »). */
    public static boolean horairesValides(String h) {
        if (h == null) return false;
        if (h.equals("jour") || h.equals("nuit") || h.equals("toujours")) return true;
        return plage(h) != null;
    }

    static int[] plage(String h) {
        String[] p = h.split("-");
        if (p.length != 2) return null;
        try {
            int a = Integer.parseInt(p[0].trim()), b = Integer.parseInt(p[1].trim());
            return a < 0 || a > 23 || b < 0 || b > 24 || a == b % 24 ? null : new int[]{a, b % 24};
        } catch (NumberFormatException x) {
            return null;
        }
    }

    /** À cette heure du jeu (0 à 23), la mission est-elle dans ses horaires ? Des horaires illisibles valent « toujours ». */
    public static boolean dansHoraires(String h, int heure) {
        if (h == null || h.equals("toujours")) return true;
        if (h.equals("jour")) return heure >= 6 && heure < 18;
        if (h.equals("nuit")) return heure >= 18 || heure < 6;
        int[] p = plage(h);
        if (p == null) return true;
        return p[0] < p[1] ? heure >= p[0] && heure < p[1] : heure >= p[0] || heure < p[1];
    }

    /** « , le jour », « , la nuit », « , de 8 h à 12 h », ou rien. */
    static String horairesTexte(String h) {
        if (h == null || h.equals("toujours")) return "";
        if (h.equals("jour")) return ", le jour";
        if (h.equals("nuit")) return ", la nuit";
        int[] p = plage(h);
        return p == null ? "" : ", de " + p[0] + " h à " + p[1] + " h";
    }

    // --- La confirmation (partie 4.4) ----------------------------------------------------------------------

    /**
     * « Compris : Raoul mine du fer jusqu'à 64. » {@code repetition} : « fois » (une fois), « jour » (tous les jours),
     * « stock » (garder N au dépôt). {@code compte} : la mission a un objet qui se compte (sinon : « Raoul va faire la ronde »).
     */
    public static String confirmation(String prenom, String metier, String nomCommande, boolean compte, String repetition, int nombre, String horaires, boolean ici) {
        StringBuilder s = new StringBuilder("Compris : ").append(prenom).append(' ');
        if (!compte) s.append("va ").append(minuscule(nomCommande));
        else {
            s.append(FAIT.getOrDefault(metier, "fournit")).append(' ').append(partitif(nomCommande));
            switch (repetition) {
                case "jour" -> {
                    // « 10 chaque jour, le jour » se répète : « 10 chaque jour, en journée », et « 10 chaque nuit » de nuit.
                    if ("nuit".equals(horaires)) { s.append(", ").append(nombre).append(" chaque nuit"); horaires = "toujours"; }
                    else s.append(", ").append(nombre).append(" chaque jour");
                    if ("jour".equals(horaires)) { s.append(", en journée"); horaires = "toujours"; }
                }
                case "stock" -> s.append(" et en garde ").append(nombre).append(" au dépôt");
                default -> s.append(" jusqu'à ").append(nombre);
            }
        }
        s.append(horairesTexte(horaires));
        if (ici) s.append(", ici");
        return s.append('.').toString();
    }

    /** Les masculins singuliers qui finissent par « e » ou « s » (le reste en « e » est féminin : « de la pierre »). */
    static final Set<String> MASCULINS_EN_E = Set.of("chêne", "cuivre", "granite", "charbon", "lapis-lazuli", "bois", "zinc");

    /** « du fer », « de l'or », « de la pierre », « des plumes » ; « toutes les récoltes » et « n'importe quel bois » sans article. */
    static String partitif(String nom) {
        String n = minuscule(nom.replaceAll("\\s*\\(.*\\)", "")).trim();
        if (n.isEmpty()) return n;
        String premier = n.split("[ ]")[0];
        if (premier.startsWith("tout") || premier.startsWith("n'importe")) return n;
        if (MASCULINS_EN_E.contains(premier)) return "du " + n;
        if (premier.endsWith("s") || premier.endsWith("x")) return "des " + n;
        if ("aeiouyhéèêœ".indexOf(n.charAt(0)) >= 0) return "de l'" + n;
        if (premier.endsWith("e")) return "de la " + n;
        return "du " + n;
    }

    static String minuscule(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    // --- Les missions des gardes (refonte, partie 6) ---------------------------------------------------

    /** Le nom des trois missions des gardes au catalogue (Guerrier), et ce qu'on lit au-dessus de leur tête. */
    public static final String DEFENDRE = "Défendre la colonie", ESCORTER = "Escorter son chef", ATTAQUER = "Attaquer l'ennemi";
    static final Map<String, String> GARDES = Map.of(DEFENDRE, "Défend la colonie", ESCORTER, "Escorte son chef", ATTAQUER, "À l'attaque");
    /** « Attaquer » sans lieu ni guerre déclarée : il garde la colonie en attendant. */
    public static final String SANS_GUERRE = "Pas de guerre déclarée : déclare-la au poste, ou dis-lui « Travaille ici »";

    // --- Les blocages des métiers (lot 16 : les métiers branchés sur les missions) -------------------------

    public static final String SANS_CHAMP = "Pas de terrain plat près du poste : dis-lui « Travaille ici »",
            RIEN_A_SEMER = "Rien à semer ici : il faut de la terre libre, des graines et une houe",
            SANS_GRAINES = "Plus de graines au dépôt : son champ reste à moitié vide",
            SANS_EAU = "Pas d'eau près du poste : montre-lui un étang",
            SANS_EAU_ICI = "Pas d'eau à pêcher ici : choisis un autre lieu",
            SANS_ARME = "Pas d'arc ni de lame au dépôt",
            SANS_GIBIER = "Pas de gibier par ici : envoie-le ailleurs",
            SANS_TRAJET = "Rien à porter : montre-lui un coffre et sa destination",
            SANS_CHANTIER = "Pas de chantier : donne-lui un schéma et un lieu",
            BOUTIQUE_VIDE = "Étal vide : mets des marchandises au dépôt",
            RIEN_A_CUIRE = "Rien à cuire au dépôt : de la viande, du poisson ou du blé",
            SANS_FOUR = "Pas de four près du poste",
            SANS_COMBUSTIBLE = "Ni charbon ni bois au dépôt pour le four",
            SANS_REMEDE = "Rien pour relever les blessés : mets du pain ou des potions de soin au dépôt";

    /** « Il manque de la pierre au dépôt » (le maçon). */
    public static String manque(String nom) { return "Il manque " + nom + " au dépôt"; }

    /** Le blocage d'un cuisinier qui n'a rien à faire, d'après la raison de {@code Cuisine.choisir} ; null : rien ne le bloque. */
    public static String cuisine(String lettre) {
        if (lettre == null) return null;
        return switch (lettre) {
            case "rien_a_cuire", "sans_ble" -> RIEN_A_CUIRE;
            case "sans_four" -> SANS_FOUR;
            case "sans_combustible" -> SANS_COMBUSTIBLE;
            default -> null;
        };
    }
}

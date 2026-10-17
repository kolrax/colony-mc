package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les répliques de la vie des PNJ (lot 5 : corps, santé, loisirs, moral…), lues dans {@code vie.txt}, et les seuils des
 * besoins qui les font dire. Pur Java : lecture et validation du fichier (format dans son en-tête), tirage selon le
 * tempérament, remplacement des variables. Le jeu est dans {@link Besoins}.
 */
final class Vie {
    private Vie() {}

    static final Set<String> VARIABLES = Set.of("chef", "nom", "objet", "mort", "plan", "prix", "ennemi", "machine"); // {plan} : l'ouvrage du maçon (macon.txt) ; {prix} : economie.txt ; {ennemi} : le chef ennemi (guerre.txt)
    static final Pattern CLE = Pattern.compile("\\[([a-z0-9_]+)]");
    /** Un préfixe en tête de réplique : « mot: texte ». Le mot doit être un tempérament connu. */
    static final Pattern PREFIXE = Pattern.compile("([a-z]+): (.*)");

    /** Une réplique ; {@code temperament} vide : pour tout le monde. */
    record Replique(String temperament, String texte) {}

    static final Map<String, List<Replique>> TOUTES = new HashMap<>();

    static void charger() {
        TOUTES.clear();
        // Comme Conversations : une section fautive est laissée de côté et notée au journal ; le test, lui, est strict.
        TOUTES.putAll(lire(Repertoire.lignes("/vie.txt"), e -> System.err.println("[colonie_pnj] Section de vie.txt ignorée : " + e.getMessage())));
        // Les remarques spontanées, l'humour devant l'impossible et les réactions selon le caractère (lot 7) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/remarques.txt"), e -> System.err.println("[colonie_pnj] Section de remarques.txt ignorée : " + e.getMessage())));
        // Deuil, enterrement, amitiés, inimitiés et couples (lots 5 et 7) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/relations.txt"), e -> System.err.println("[colonie_pnj] Section de relations.txt ignorée : " + e.getMessage())));
        // Défense du chantier, rondes du guerrier, nuits dehors (lot 9) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/defense.txt"), e -> System.err.println("[colonie_pnj] Section de defense.txt ignorée : " + e.getMessage())));
        // Quand on frappe le chef, et ses ordres à ce sujet (lot 9, GardeChef) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/chef.txt"), e -> System.err.println("[colonie_pnj] Section de chef.txt ignorée : " + e.getMessage())));
        // Le dépôt du poste : qui s'y sert, et les ordres du chef à ce sujet (lot 9, GardeDepot) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/depot.txt"), e -> System.err.println("[colonie_pnj] Section de depot.txt ignorée : " + e.getMessage())));
        // La guerre entre colonies (lot 9, Guerre, Armee) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/guerre.txt"), e -> System.err.println("[colonie_pnj] Section de guerre.txt ignorée : " + e.getMessage())));
        // Le maçon et ses chantiers (lot 10) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/macon.txt"), e -> System.err.println("[colonie_pnj] Section de macon.txt ignorée : " + e.getMessage())));
        // Achats et ventes entre PNJ (lot 11, Achats) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/economie.txt"), e -> System.err.println("[colonie_pnj] Section de economie.txt ignorée : " + e.getMessage())));
        // Talents et réputation à l'entretien d'embauche (lot 13, Recrutement) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/recrutement.txt"), e -> System.err.println("[colonie_pnj] Section de recrutement.txt ignorée : " + e.getMessage())));
        // Les machines que le transporteur remplit et vide (lot 12, Transporteur, Machines) : même format, {machine} en plus.
        TOUTES.putAll(lire(Repertoire.lignes("/machines.txt"), e -> System.err.println("[colonie_pnj] Section de machines.txt ignorée : " + e.getMessage())));
        // Un autre que le chef parle à l'ouvrier, lui donne des ordres ou veut le débaucher (lot 14, Etranger) : même format.
        TOUTES.putAll(lire(Repertoire.lignes("/etrangers.txt"), e -> System.err.println("[colonie_pnj] Section de etrangers.txt ignorée : " + e.getMessage())));
    }

    /** Lit le fichier, strictement : la première erreur arrête tout, avec le numéro de ligne. */
    static Map<String, List<Replique>> lire(List<String> lignes) { return lire(lignes, e -> { throw e; }); }

    /** Lit le fichier ; une section fautive (ou une ligne hors section) est laissée de côté et signalée à {@code erreurs}. */
    static Map<String, List<Replique>> lire(List<String> lignes, Consumer<IllegalStateException> erreurs) {
        Map<String, List<Replique>> r = new LinkedHashMap<>();
        String cle = null;
        int debut = 0;
        List<Replique> section = null; // null : section fautive (ou pas encore de section), ses lignes sont sautées
        for (int n = 1; n <= lignes.size() + 1; n++) {
            String l = n <= lignes.size() ? lignes.get(n - 1).strip() : "[fin]";
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("[")) {
                if (section != null) {
                    if (section.stream().noneMatch(x -> x.temperament().isEmpty())) erreurs.accept(erreur(debut, "aucune réplique pour tout le monde dans [" + cle + "]"));
                    else r.put(cle, List.copyOf(section));
                }
                section = null;
                if (n > lignes.size()) break;
                Matcher m = CLE.matcher(l);
                debut = n;
                if (!m.matches()) { erreurs.accept(erreur(n, "clé mal écrite « " + l + " » (minuscules, chiffres et _)")); continue; }
                cle = m.group(1);
                if (r.containsKey(cle)) { erreurs.accept(erreur(n, "clé [" + cle + "] en double")); continue; }
                section = new ArrayList<>();
                continue;
            }
            if (cle == null) { erreurs.accept(erreur(n, "réplique hors d'une section")); continue; }
            if (section == null) continue; // la section est déjà écartée
            try { section.add(replique(l, n)); }
            catch (IllegalStateException e) { erreurs.accept(e); section = null; }
        }
        return r;
    }

    static Replique replique(String l, int n) {
        String temperament = "", texte = l;
        Matcher p = PREFIXE.matcher(l);
        if (p.matches()) {
            if (!Conversations.TEMPERAMENTS.contains(p.group(1))) throw erreur(n, "tempérament inconnu « " + p.group(1) + " »");
            temperament = p.group(1);
            texte = p.group(2).strip();
        }
        if (texte.isEmpty()) throw erreur(n, "réplique vide");
        Matcher v = Conversations.VARIABLE.matcher(texte);
        while (v.find()) if (!VARIABLES.contains(v.group(1))) throw erreur(n, "variable inconnue {" + v.group(1) + "}");
        if (texte.replaceAll("\\{[^}]*}", "").matches(".*[{}].*")) throw erreur(n, "accolade seule");
        return new Replique(temperament, texte);
    }

    static IllegalStateException erreur(int ligne, String quoi) { return new IllegalStateException("vie.txt ligne " + ligne + " : " + quoi); }

    /** Vrai si toutes les variables de la réplique ont une valeur. */
    static boolean remplissable(Replique r, Map<String, String> valeurs) {
        Matcher v = Conversations.VARIABLE.matcher(r.texte());
        while (v.find()) if (valeurs.get(v.group(1)) == null || valeurs.get(v.group(1)).isBlank()) return false;
        return true;
    }

    /**
     * Une réplique de la section {@code cle}, variables remplies : une fois sur deux une réplique de son tempérament s'il
     * y en a, sinon une commune. Seules comptent les répliques dont toutes les variables sont connues (sans {nom}, pas de
     * « {nom} a pris mon lit »). Null si la section manque ou si rien ne va.
     */
    static String tirer(Map<String, List<Replique>> banque, String cle, String temperament, Map<String, String> valeurs, DoubleSupplier hasard) {
        List<Replique> toutes = banque.get(cle);
        if (toutes == null) return null;
        // Les réactions selon le caractère (remarques.txt) complètent la section du même nom (insulte, coup, impaye, cadeau…).
        if (banque.containsKey("caractere_" + cle)) { toutes = new ArrayList<>(toutes); toutes.addAll(banque.get("caractere_" + cle)); }
        List<Replique> propres = new ArrayList<>(), communes = new ArrayList<>();
        for (Replique r : toutes) {
            if (!remplissable(r, valeurs)) continue;
            if (r.temperament().isEmpty()) communes.add(r);
            else if (r.temperament().equals(temperament)) propres.add(r);
        }
        List<Replique> choix = !propres.isEmpty() && (communes.isEmpty() || hasard.getAsDouble() < 0.5) ? propres : communes;
        if (choix.isEmpty()) return null;
        String s = Conversations.remplir(choix.get(Math.min(choix.size() - 1, (int) (hasard.getAsDouble() * choix.size()))).texte(), valeurs);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1); // « {chef}, … » avec « chef » pour nom
    }

    static String tirer(String cle, String temperament, Map<String, String> valeurs, DoubleSupplier hasard) {
        return tirer(TOUTES, cle, temperament, valeurs, hasard);
    }

    /** Vrai si ce texte peut venir de la section {@code cle} (les variables valent n'importe quoi) : pour les tests. */
    static boolean vient(String cle, String texte) {
        for (Replique r : TOUTES.getOrDefault(cle, List.of())) {
            String motif = "(?i)" + Pattern.quote(r.texte()).replaceAll("\\{[a-z]+}", "\\\\E.+\\\\Q");
            if (texte.matches(motif)) return true;
        }
        return false;
    }

    // --- Seuils des besoins (faim 0 à 20, fatigue et moral 0 à 100) ----------------------------------------------

    static final int FAIM_LEGERE = 12, FAIM_FORTE = 6, FATIGUE = 80;
    static final int PLAINTE_DOUCE = Conversations.MORAL_BAS, PLAINTE_FORTE = 25, CONTENT = Conversations.MORAL_HAUT, FIER = 90;
    /** Une pause : moral moyen ou bas (sous 60, strictement), après tant de ticks de travail (6 heures du jeu), pour 20 à 40 s. */
    static final int PAUSE_MORAL = Obeissance.REPOS, PAUSE_APRES = 6000, PAUSE_MIN = 400, PAUSE_MAX = 800;

    /** « faim_forte », « faim_legere » ou null. */
    static String faim(int faim) { return faim <= FAIM_FORTE ? "faim_forte" : faim <= FAIM_LEGERE ? "faim_legere" : null; }

    /** « blessure_grave » (30 % de sa vie au plus), « blessure_legere » (70 % au plus) ou null. */
    static String blessure(float vie, float max) { return vie <= max * 0.3f ? "blessure_grave" : vie <= max * 0.7f ? "blessure_legere" : null; }

    /** Ce qu'il dit quand son moral a bougé depuis la dernière fois ({@code avant}) : plainte s'il baisse bas, contentement s'il monte haut. */
    static String humeur(int moral, int avant) {
        if (moral < avant) return moral < PLAINTE_FORTE ? "plainte_forte" : moral < PLAINTE_DOUCE ? "plainte_douce" : null;
        if (moral > avant) return moral >= FIER ? "fier_colonie" : moral >= CONTENT ? "content" : null;
        return null;
    }

    /** Il a envie d'une pause : moral moyen ou bas, et longtemps au travail. */
    static boolean veutPause(int moral, int travail) { return moral < PAUSE_MORAL && travail >= PAUSE_APRES; }

    // --- Remarques spontanées (lot 7, cahier 7.3 : « parfois : remarques, blagues ») -----------------------------

    /** Ce qu'il sait de son moment, sans rien chercher : l'heure (0 à 23), le ciel, son travail, sa colonie ({@code nouveau} : prénom du dernier embauché, ou null). */
    record Moment(int heure, boolean pluie, boolean orage, boolean neige, boolean auTravail, String metier,
                  boolean fini, boolean depotPlein, boolean ennui, String nouveau, boolean paie) {}

    /**
     * La remarque à faire (une clé de remarques.txt) : un événement d'abord (nouveau collègue, jour de paie, travail fini,
     * dépôt plein), sinon une au hasard parmi le moment de la journée, le ciel, l'ennui ou son métier. Jamais une clé
     * {@code recente}. Null : rien à dire.
     */
    static String remarque(Map<String, List<Replique>> banque, Moment m, Predicate<String> recente, DoubleSupplier hasard) {
        List<String> evenements = new ArrayList<>(), autres = new ArrayList<>();
        if (m.nouveau() != null) evenements.add("remarque_nouveau_collegue");
        if (m.paie()) evenements.add("remarque_jour_paie");
        if (m.fini()) evenements.add("remarque_travail_fini");
        if (m.depotPlein()) evenements.add("remarque_depot_plein");
        int h = m.heure();
        boolean nuit = h >= 21 || h < 5;
        if (h >= 5 && h < 9) autres.add("remarque_matin");
        else if (h >= 18 && h < 21) autres.add("remarque_soir");
        else if (nuit) autres.add("remarque_nuit");
        if (m.orage()) autres.add("remarque_orage");
        else if (m.pluie()) autres.add("remarque_pluie");
        else if (m.neige()) autres.add("remarque_neige");
        else if (!nuit) autres.add("remarque_beau_temps");
        if (m.ennui()) autres.add("remarque_ennui");
        else if (m.auTravail()) autres.add("remarque_" + m.metier());
        for (List<String> l : List.of(evenements, autres)) {
            List<String> c = l.stream().filter(k -> banque.containsKey(k) && !recente.test(k)).toList();
            if (!c.isEmpty()) return c.get(Math.min(c.size() - 1, (int) (hasard.getAsDouble() * c.size())));
        }
        return null;
    }

    static {
        charger();
    }
}

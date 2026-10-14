package fr.lacolonie.pnj;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * À qui le chef parle-t-il ? Une probabilité par ouvrier, de deux sources croisées :
 * <ol>
 * <li>le code : une formule logistique sur des indices (prénom, « les gars », regard, distance, tutoiement, ordre,
 *   question, troisième personne, métier concerné, vrais joueurs proches, conversation récente…), dont les poids
 *   sont appris sur des exemples annotés (voir {@code entrainement/}) et rangés dans {@code adressage.json} ;</li>
 * <li>l'IA : une consigne courte qui lit la phrase et son contexte, et rend sa probabilité et le lien qu'elle voit.</li>
 * </ol>
 * Les deux sont fusionnées (formule apprise elle aussi). Quand le code est sûr, l'IA n'est pas consultée.
 * Aucune dépendance au jeu : entraîné et testé seul.
 */
final class Adressage {
    private Adressage() {}

    /**
     * Un ouvrier qui entend le chef. {@code regard} : cosinus entre le regard du chef et la direction de l'ouvrier
     * (1 : il le regarde droit dans les yeux) ; {@code depuis} : secondes depuis leur dernier échange (-1 : aucun) ;
     * {@code question} : sa dernière réplique au chef était une question ; {@code fenetre} : le chef écrit dans sa
     * fenêtre de conversation ; {@code derniers} : leurs dernières répliques (pour l'IA).
     */
    record Pnj(String prenom, String metier, double distance, double regard, double depuis, boolean question, boolean fenetre, List<String> derniers) {}

    /** Un autre joueur, une vraie personne, à portée de voix. */
    record Joueur(String nom, double distance, double regard) {}

    /** Ce que le chef vient de dire ({@code micro} : à voix haute ; sinon écrit dans une fenêtre), et qui est autour. */
    record Situation(String texte, boolean micro, List<Pnj> pnj, List<Joueur> joueurs) {}

    // --- Les indices du code -------------------------------------------------------------------------------

    /**
     * Les indices. Ceux du premier groupe désignent explicitement des destinataires (prénom, « les gars », métier) ;
     * quand la phrase en désigne, les indices de contexte (regard, métier concerné, conversation récente…) sont
     * coupés : c'est l'appel qui compte. Ceux du texte (tutoiement, ordre…) disent s'il parle à ses ouvriers du tout.
     */
    static final List<String> INDICES = List.of(
            "appele", "appele_autre", "groupe", "exclu", "metier_tous", "metier_designe", "metier_autre",
            "deuxieme", "demande", "question", "politesse", "troisieme", "je", "ordre",
            "cite", "mission_demande", "mission_seule", "mission_autre", "hors_metier",
            "regarde_seul", "regarde_avec_joueur", "regarde_sans_adresse", "regarde_autre", "regard",
            "proche_sans_regard", "distance", "nb_pnj",
            "joueur_proche", "regarde_joueur", "joueur_appele",
            "recent", "recent_x_adresse", "recent_autre", "question_pnj", "court_x_question_pnj",
            "fenetre", "fenetre_autre", "micro", "seul_x_adresse", "fenetre_x_pas_son_metier", "son_metier_x_fenetre_autre",
            "pluriel_x_adresse");

    static final Set<Ordres.Intention> ORDRES = EnumSet.of(Ordres.Intention.SUIVRE, Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE,
            Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER, Ordres.Intention.SAC, Ordres.Intention.RENVOYER);
    /** « il faut », « il y a », « il fait nuit » : un « il » qui ne désigne personne. */
    static final Set<String> NON_PERSONNEL = Set.of("faut", "faudrait", "y", "fait", "pleut", "reste", "me", "nous", "manque", "suffit", "semble", "parait", "suffira");
    static final List<String> DESIGNATIONS = List.of("ce mec", "ce type", "ce gars", "celui la", "celle la", "ce bonhomme", "cette fille", "ce gamin",
            "ces gars", "ces types", "ces mecs", "le mec", "le type");

    /** Il parle de quelqu'un à la troisième personne (« ce mec », « il est lent », « elle a faim »). */
    static boolean troisieme(List<String> j, String n) {
        if (DESIGNATIONS.stream().anyMatch(x -> Ordres.dit(n, x))) return true;
        for (int k = 0; k < j.size(); k++) {
            String w = j.get(k);
            if (w.equals("elle") || w.equals("ils") || w.equals("elles") || w.equals("lui")) return true;
            if (!w.equals("il") || k + 1 < j.size() && NON_PERSONNEL.contains(j.get(k + 1))) continue;
            if (k + 2 < j.size() && j.get(k + 1).equals("est") && Set.of("temps", "tard", "l", "quelle").contains(j.get(k + 2))) continue;
            return true;
        }
        return false;
    }

    /** Un de ces mots est (presque) le pseudo de ce joueur (« Niels » pour Nielscutt). */
    static boolean nomDe(List<String> mots, String pseudo) {
        for (String partie : Ordres.normaliser(pseudo).trim().split(" ")) {
            if (partie.length() < 3) continue;
            for (String w : mots) if (w.length() >= 4 && (partie.startsWith(w) || Ordres.proche(w, partie))) return true;
        }
        return false;
    }

    static double b(boolean x) { return x ? 1 : 0; }

    static double borne(double x, double min, double max) { return Math.max(min, Math.min(max, x)); }

    /** Les indices de chaque ouvrier (une ligne par ouvrier, dans l'ordre de {@link #INDICES}). */
    static double[][] indices(Situation s) {
        List<Pnj> l = s.pnj();
        int n = l.size();
        List<String> prenoms = l.stream().map(Pnj::prenom).toList(), metiers = l.stream().map(Pnj::metier).toList();
        Ordres.Adresse a = Ordres.adresse(s.texte(), prenoms, metiers);
        List<String> j = Ordres.jetons(s.texte());
        List<String> mots = j.stream().filter(w -> !Ordres.ponctuation(w)).toList();
        String norm = Ordres.normaliser(s.texte());
        Ordres.Lecture general = Ordres.lire(s.texte(), "");
        boolean politesse = general.intentions.contains(Ordres.Intention.SALUT) || general.intentions.contains(Ordres.Intention.MERCI);
        boolean troisieme = !a.deuxiemePersonne() && troisieme(j, norm);
        // « Pourquoi il ne travaille pas, lui ? » : à la troisième personne, un verbe d'ordre décrit quelqu'un, il ne commande rien.
        // Une action du répertoire est un ordre aussi (« garde à vous ! », « fais le cochon »).
        boolean ordre = !troisieme && (general.intentions.stream().anyMatch(ORDRES::contains) || Repertoire.commande(s.texte()));
        // Une action du répertoire réservée à certains métiers (« abats cet arbre » : le bûcheron) les concerne comme une mission.
        Set<String> concernes = troisieme ? Set.of() : Repertoire.metiersConcernes(s.texte());
        boolean adresse = a.deuxiemePersonne() || a.demande() || a.question() || politesse || ordre;
        boolean je = mots.contains("je") || mots.contains("j");
        boolean court = mots.size() <= 4;
        boolean[] mission = new boolean[n], hors = new boolean[n];
        boolean missionQuelquUn = false;
        for (int i = 0; i < n; i++) {
            Ordres.Lecture x = Ordres.lire(s.texte(), metiers.get(i));
            mission[i] = !x.missions.isEmpty() || concernes.contains(metiers.get(i));
            hors[i] = !mission[i] && (!x.horsMetier.isEmpty() || !concernes.isEmpty());
            missionQuelquUn |= mission[i];
        }
        int vu = -1, proche = -1, fenetre = -1;
        for (int i = 0; i < n; i++) {
            Pnj p = l.get(i);
            if (p.regard() >= 0.9 && (vu < 0 || p.regard() > l.get(vu).regard())) vu = i;
            if (proche < 0 || p.distance() < l.get(proche).distance()) proche = i;
            if (p.fenetre()) fenetre = i;
        }
        boolean joueurProche = s.joueurs().stream().anyMatch(x -> x.distance() <= 12);
        boolean regardeJoueur = s.joueurs().stream().anyMatch(x -> x.regard() >= 0.9 && x.distance() <= 16);
        boolean seul = s.joueurs().stream().noneMatch(x -> x.distance() <= 32);
        boolean joueurAppele = s.joueurs().stream().anyMatch(x -> nomDe(mots, x.nom()));
        double[] recence = new double[n];
        for (int i = 0; i < n; i++) recence[i] = l.get(i).depuis() < 0 ? 0 : Math.exp(-l.get(i).depuis() / 30);
        boolean explicite = !a.appeles().isEmpty() || a.tous() || !a.metiersTous().isEmpty() || !a.metiersUn().isEmpty();
        double nx = explicite ? 0 : 1;
        // « Le guerrier, viens » : celui de ce métier qu'il regarde, sinon le plus proche.
        Set<Integer> designes = new HashSet<>();
        for (String m : a.metiersUn()) {
            int d = -1;
            if (vu >= 0 && l.get(vu).metier().equals(m)) d = vu;
            else for (int i = 0; i < n; i++) if (l.get(i).metier().equals(m) && (d < 0 || l.get(i).distance() < l.get(d).distance())) d = i;
            if (d >= 0) designes.add(d);
        }
        Set<String> metiersAppeles = new HashSet<>(a.metiersTous());
        metiersAppeles.addAll(a.metiersUn());
        boolean demandeQuelqueChose = a.demande() || a.deuxiemePersonne() || ordre;
        double[][] f = new double[n][];
        for (int i = 0; i < n; i++) {
            Pnj p = l.get(i);
            double recentAutre = 0;
            for (int k = 0; k < n; k++) if (k != i) recentAutre = Math.max(recentAutre, recence[k]);
            double regard = borne((p.regard() - 0.5) / 0.5, 0, 1);
            boolean question = p.question() && p.depuis() >= 0 && p.depuis() < 60;
            boolean regarde = vu == i;
            f[i] = new double[]{
                    b(a.appeles().contains(i)), b(!a.appeles().isEmpty() && !a.appeles().contains(i)), b(a.tous() && !a.exclus().contains(i)),
                    b(a.exclus().contains(i)), b(a.metiersTous().contains(p.metier())), b(designes.contains(i)),
                    b(!metiersAppeles.isEmpty() && !metiersAppeles.contains(p.metier())),
                    b(a.deuxiemePersonne()), b(a.demande()), b(a.question()), b(politesse), b(troisieme), b(je), b(ordre),
                    nx * b(a.cites().contains(i)), nx * b(mission[i] && demandeQuelqueChose), nx * b(mission[i] && !demandeQuelqueChose),
                    nx * b(!mission[i] && missionQuelquUn), nx * b(hors[i]),
                    nx * b(regarde && adresse && !joueurProche), nx * b(regarde && adresse && joueurProche), nx * b(regarde && !adresse),
                    nx * b(vu >= 0 && !regarde), nx * regard,
                    nx * b(adresse && proche == i && vu < 0), nx * Math.min(p.distance(), 16) / 16, Math.log(n) / Math.log(2) / 3,
                    nx * b(joueurProche), nx * b(regardeJoueur), b(joueurAppele && a.appeles().isEmpty()),
                    nx * recence[i], nx * b(adresse) * recence[i], nx * recentAutre, nx * b(question), nx * b(court && question),
                    b(fenetre == i), b(fenetre >= 0 && fenetre != i), b(s.micro()), nx * b(seul && adresse),
                    // Écrit dans la fenêtre du mineur : « va protéger la base » relève du guerrier présent.
                    nx * b(fenetre == i && !mission[i] && missionQuelquUn), nx * b(mission[i] && demandeQuelqueChose && fenetre >= 0 && fenetre != i),
                    // « Dansez ! », « Asseyez-vous » : un ordre au pluriel, sans « les gars », vise tous ceux qui l'entendent.
                    nx * b(a.pluriel() && adresse && !troisieme)};
        }
        return f;
    }

    // --- Les paramètres appris ------------------------------------------------------------------------------

    /**
     * Les poids de la formule du code, la fusion avec l'IA ({@code p = σ(a·logit(code) + b·logit(ia) + c)}), le seuil de
     * décision avec et sans IA, et la zone où le code est sûr de lui ({@code bas} / {@code haut}) : l'IA n'est consultée
     * que si au moins une probabilité du code tombe entre les deux.
     */
    record Parametres(double[] poids, double biais, double fusionCode, double fusionIa, double fusionBiais, double seuil, double seuilCode,
                      double bas, double haut) {}

    static volatile Parametres P = charger();

    static Parametres charger() {
        try (InputStream in = Adressage.class.getResourceAsStream("/adressage.json")) {
            if (in != null) return lire(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            System.err.println("adressage.json illisible : " + e);
        }
        return new Parametres(new double[INDICES.size()], -2, 0.5, 0.5, 0, 0.5, 0.5, 0.02, 0.98);
    }

    static Parametres lire(JsonObject o) {
        JsonObject w = o.getAsJsonObject("poids");
        double[] poids = new double[INDICES.size()];
        for (int k = 0; k < poids.length; k++) poids[k] = w.has(INDICES.get(k)) ? w.get(INDICES.get(k)).getAsDouble() : 0;
        JsonObject fu = o.getAsJsonObject("fusion"), se = o.getAsJsonObject("seuils");
        return new Parametres(poids, o.get("biais").getAsDouble(), fu.get("code").getAsDouble(), fu.get("ia").getAsDouble(), fu.get("biais").getAsDouble(),
                se.get("decision").getAsDouble(), se.get("code_seul").getAsDouble(), se.get("bas").getAsDouble(), se.get("haut").getAsDouble());
    }

    static JsonObject ecrire(Parametres p) {
        JsonObject o = new JsonObject(), w = new JsonObject(), fu = new JsonObject(), se = new JsonObject();
        for (int k = 0; k < INDICES.size(); k++) w.addProperty(INDICES.get(k), Math.round(p.poids()[k] * 1e4) / 1e4);
        o.add("poids", w);
        o.addProperty("biais", Math.round(p.biais() * 1e4) / 1e4);
        fu.addProperty("code", p.fusionCode());
        fu.addProperty("ia", p.fusionIa());
        fu.addProperty("biais", p.fusionBiais());
        o.add("fusion", fu);
        se.addProperty("decision", p.seuil());
        se.addProperty("code_seul", p.seuilCode());
        se.addProperty("bas", p.bas());
        se.addProperty("haut", p.haut());
        o.add("seuils", se);
        return o;
    }

    static double sigmoide(double z) { return 1 / (1 + Math.exp(-z)); }

    static double logit(double p) {
        p = borne(p, 0.01, 0.99);
        return Math.log(p / (1 - p));
    }

    static double[] code(Situation s) { return code(s, P); }

    /**
     * La probabilité du code pour chaque ouvrier. Quand la phrase désigne elle-même ses destinataires (prénom qui
     * interpelle, « les gars », métier nommé), la grammaire tranche net : 99 % pour eux, 1 % pour les autres ; « sauf
     * Raoul » : 1 % pour Raoul. Sinon, la formule apprise (regard, contexte, métier concerné, joueurs proches…).
     */
    static double[] code(Situation s, Parametres pa) {
        double[] p = code(indices(s), pa);
        Ordres.Adresse a = Ordres.adresse(s.texte(), s.pnj().stream().map(Pnj::prenom).toList(), s.pnj().stream().map(Pnj::metier).toList());
        if (explicite(a)) {
            Set<Integer> vises = vises(s, a);
            for (int i = 0; i < p.length; i++) p[i] = vises.contains(i) ? 0.99 : 0.01;
        }
        for (int i : a.exclus()) p[i] = 0.01;
        return p;
    }

    static boolean explicite(Ordres.Adresse a) {
        return !a.appeles().isEmpty() || a.tous() || !a.metiersTous().isEmpty() || !a.metiersUn().isEmpty();
    }

    /** Ceux que la phrase désigne : les prénoms appelés, tout le groupe, les ouvriers d'un métier nommé (« le guerrier » : celui qu'il regarde, sinon le plus proche). */
    static Set<Integer> vises(Situation s, Ordres.Adresse a) {
        List<Pnj> l = s.pnj();
        Set<Integer> r = new HashSet<>(a.appeles());
        for (int i = 0; i < l.size(); i++) if (a.tous() || a.metiersTous().contains(l.get(i).metier())) r.add(i);
        int vu = -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).regard() >= 0.9 && (vu < 0 || l.get(i).regard() > l.get(vu).regard())) vu = i;
        for (String m : a.metiersUn()) {
            int d = -1;
            if (vu >= 0 && l.get(vu).metier().equals(m)) d = vu;
            else for (int i = 0; i < l.size(); i++) if (l.get(i).metier().equals(m) && (d < 0 || l.get(i).distance() < l.get(d).distance())) d = i;
            if (d >= 0) r.add(d);
        }
        r.removeAll(a.exclus());
        return r;
    }

    /** La formule apprise seule (pour l'entraînement). */
    static double[] code(double[][] f, Parametres pa) {
        double[] p = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            double z = pa.biais();
            for (int k = 0; k < f[i].length; k++) z += pa.poids()[k] * f[i][k];
            p[i] = sigmoide(z);
        }
        return p;
    }

    /**
     * La probabilité finale : celle du code croisée avec celle de l'IA (NaN : l'IA n'a rien dit de lui, on garde le code).
     * {@code remarque} : la phrase parle de quelqu'un plutôt qu'à quelqu'un ({@link #remarque}) ; l'IA y prend trop
     * souvent un prénom cité pour un appel (mesuré à l'entraînement) : son avis ne compte pas, la grammaire du code tranche.
     */
    static double fusion(double pc, double pi, Parametres pa, boolean remarque) {
        if (Double.isNaN(pi)) return pc;
        return sigmoide(pa.fusionCode() * logit(pc) + (remarque ? 0 : pa.fusionIa() * logit(pi)) + pa.fusionBiais());
    }

    /**
     * La phrase parle de quelqu'un plutôt qu'à quelqu'un : troisième personne sans tutoiement (« il bosse bien »), un
     * prénom cité sans être interpellé (« regarde ce que Bertrand a trouvé »), ou un autre joueur nommé.
     */
    static boolean remarque(Situation s) {
        Ordres.Adresse a = Ordres.adresse(s.texte(), s.pnj().stream().map(Pnj::prenom).toList(), s.pnj().stream().map(Pnj::metier).toList());
        List<String> j = Ordres.jetons(s.texte());
        List<String> mots = j.stream().filter(w -> !Ordres.ponctuation(w)).toList();
        boolean joueurNomme = s.joueurs().stream().anyMatch(x -> nomDe(mots, x.nom()));
        return !a.deuxiemePersonne() && troisieme(j, Ordres.normaliser(s.texte())) || !a.cites().isEmpty() || joueurNomme && a.appeles().isEmpty();
    }

    /**
     * Le code peut trancher seul, sans consulter l'IA : personne n'est visé (toutes ses probabilités sont basses) ; ou
     * la phrase désigne elle-même ses destinataires (prénom, « les gars », métier) et il est sûr pour chacun ; ou elle
     * est écrite dans la fenêtre d'un ouvrier sans viser personne d'autre. Une certitude tirée du seul contexte
     * (regard, conversation récente) ne suffit pas : l'IA vérifie.
     */
    static boolean sur(Situation s, double[] pc, Parametres pa) {
        if (personne(pc, pa)) return true;
        boolean extremes = true, autresBas = true;
        for (int i = 0; i < pc.length; i++) {
            if (pc[i] > pa.bas() && pc[i] < pa.haut()) extremes = false;
            if (!s.pnj().get(i).fenetre() && pc[i] > pa.bas()) autresBas = false;
        }
        if (!s.micro() && autresBas) return true;
        Ordres.Adresse a = Ordres.adresse(s.texte(), s.pnj().stream().map(Pnj::prenom).toList(), s.pnj().stream().map(Pnj::metier).toList());
        return explicite(a) && extremes;
    }

    /** Personne n'est visé, d'après le code, avec certitude. */
    static boolean personne(double[] pc, Parametres pa) {
        for (double x : pc) if (x > pa.bas()) return false;
        return true;
    }

    /**
     * Les destinataires : ceux dont la probabilité atteint le seuil. Une phrase au singulier (ni « les gars », ni
     * pluriel, ni plusieurs prénoms) ne vise qu'un ouvrier : le plus probable. Écrite dans la fenêtre d'un ouvrier,
     * elle est au moins pour lui.
     */
    static List<Integer> choisir(Situation s, double[] p, double seuil) {
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i < p.length; i++) if (p[i] >= seuil) r.add(i);
        Ordres.Adresse a = Ordres.adresse(s.texte(), s.pnj().stream().map(Pnj::prenom).toList(), s.pnj().stream().map(Pnj::metier).toList());
        boolean plusieurs = a.tous() || !a.metiersTous().isEmpty() || a.appeles().size() > 1 || a.pluriel();
        if (r.size() > 1 && !plusieurs) {
            int m = r.get(0);
            for (int i : r) if (p[i] > p[m]) m = i;
            r = new ArrayList<>(List.of(m));
        }
        if (r.isEmpty()) for (int i = 0; i < s.pnj().size(); i++) if (s.pnj().get(i).fenetre()) r.add(i);
        return r;
    }

    // --- L'avis de l'IA ---------------------------------------------------------------------------------------

    /** Ce que sait faire chaque métier, en quelques mots (pour que l'IA relie la demande au bon ouvrier). */
    static final Map<String, String> CAPACITES = Map.of(
            "mineur", "mine et rapporte charbon, fer, cuivre, or, diamant, redstone, lapis, pierre ; creuse, perce des tunnels, pose des torches",
            "cuisinier", "cuisinier et boulanger : cuit au four la viande, le poisson et les pommes de terre du dépôt, fait le pain avec le blé ; cuisine, cuit, fait du pain",
            "guerisseur", "relève les ouvriers à terre avec du pain ou une potion de soin, panse les blessés ; soigne quelqu'un, fait le tour des blessés",
            "bucheron", "coupe et rapporte du bois : chêne, bouleau, sapin, acacia, cerisier, jungle ; abat les arbres, replante",
            "guerrier", "garde le poste ou la base, fait la ronde, escorte son chef, tue les monstres ; se met en garde, lève son bouclier, charge",
            "fermier", "cultive et rapporte blé, carottes, pommes de terre, betteraves ; récolte, laboure et sème un champ, fait du pain",
            "eleveur", "élève vaches, moutons, cochons, poules ; rapporte cuir, laine, viande, œufs, lait ; nourrit les bêtes, tond les moutons, abat les bêtes en trop");

    /** Une étiquette par ouvrier : son prénom (avec son métier si deux ouvriers ont le même). */
    static List<String> etiquettes(Situation s) {
        List<String> r = new ArrayList<>();
        for (Pnj p : s.pnj()) {
            String e = s.pnj().stream().filter(x -> x.prenom().equals(p.prenom())).count() > 1 ? p.prenom() + " le " + p.metier() : p.prenom();
            while (r.contains(e)) e += " bis";
            r.add(e);
        }
        return r;
    }

    static final String CONSIGNE = "Pour chaque ouvrier de la liste, estime la probabilité (de 0 à 100) que le chef lui parle dans cette phrase, "
            + "et dis en quelques mots le lien entre la phrase et lui. Règles, par ordre d'importance :\n"
            + "1. Un prénom qui interpelle (« Raoul, … », « Merci Raoul », « Raoul ! ») : très probable pour lui, même s'il est loin ou hors de vue, "
            + "et improbable pour les autres (sauf s'ils sont aussi interpellés). Un prénom cité sans interpeller (« Raoul est lent », « va aider Raoul ») : "
            + "le chef parle de lui, pas à lui.\n"
            + "2. « Les gars », « tout le monde », « vous tous », « vous deux », un verbe au pluriel adressé au groupe : très probable pour chacun, "
            + "même loin ou hors de vue (sauf ceux exclus : « sauf Raoul »). « Les mineurs », « le guerrier » : ceux de ce métier.\n"
            + "3. Sinon, ce qu'il demande : une ressource ou une tâche propre à un métier (fer, charbon, diamant : le mineur ; bois, bûches : le bûcheron ; "
            + "garder ou protéger la base, faire la ronde : le guerrier) désigne l'ouvrier de ce métier. Les ordres communs à tous (suis-moi, attends, "
            + "rentre au poste, au travail, montre ton sac), une question, un salut, un merci ne désignent aucun métier : c'est alors celui qu'il regarde, "
            + "ou celui avec qui il vient d'échanger (une réponse à sa question, la suite de leur conversation).\n"
            + "4. Pour personne (tout bas) : il parle d'un ouvrier à la troisième personne (« ce mec », « il est lent »), il parle à un autre joueur "
            + "(surtout s'il le regarde ou le nomme), il réfléchit tout haut, commente, chante.\n"
            + "Le regard et la distance ne font que départager quand rien d'autre ne désigne quelqu'un. Les probabilités sont indépendantes : "
            + "plusieurs ouvriers peuvent être visés ensemble.\n"
            + "Une ligne par ouvrier de la liste, et pour eux seulement (pas pour les autres joueurs).";

    /** La consigne de l'IA : la phrase, chaque ouvrier (métier, distance, regard, échange récent), les autres joueurs. */
    static String consigne(Situation s, List<String> etiquettes) {
        StringBuilder b = new StringBuilder("Dans Minecraft, un joueur (le chef) dirige des ouvriers (des personnages non joueurs). ");
        int fen = -1;
        for (int i = 0; i < s.pnj().size(); i++) if (s.pnj().get(i).fenetre()) fen = i;
        if (!s.micro() && fen >= 0) b.append("Il vient d'écrire dans la fenêtre de conversation de ").append(etiquettes.get(fen)).append(" : « ");
        else b.append("Il vient de dire à voix haute (sa voix, transcrite) : « ");
        b.append(s.texte()).append(" »\nSes ouvriers qui l'entendent :\n");
        for (int i = 0; i < s.pnj().size(); i++) {
            Pnj p = s.pnj().get(i);
            b.append("- ").append(etiquettes.get(i)).append(" : ").append(p.metier()).append(" (").append(CAPACITES.getOrDefault(p.metier(), ""))
                    .append("), à ").append(Math.round(p.distance())).append(" blocs, ")
                    .append(p.regard() >= 0.9 ? "le chef le regarde" : p.regard() >= 0.6 ? "dans le champ de vision du chef" : "hors de la vue du chef");
            if (p.question() && p.depuis() >= 0 && p.depuis() < 60) b.append(" ; il vient de poser une question au chef");
            if (p.depuis() >= 0 && p.depuis() < 120 && !p.derniers().isEmpty())
                b.append(" ; leur dernier échange, il y a ").append(Math.round(p.depuis())).append(" s : ").append(String.join(" / ", p.derniers()));
            b.append(".\n");
        }
        List<String> autres = new ArrayList<>();
        for (Joueur x : s.joueurs())
            autres.add(x.nom() + " à " + Math.round(x.distance()) + " blocs" + (x.regard() >= 0.9 ? " (le chef le regarde)" : ""));
        b.append(autres.isEmpty() ? "Aucun autre joueur à portée de voix.\n" : "D'autres joueurs (de vraies personnes) à portée de voix : " + String.join(", ", autres) + ".\n");
        return b.append(CONSIGNE).toString();
    }

    static JsonObject schema(List<String> etiquettes) {
        JsonObject ouvrier = ReponsesIA.objet(Map.of("qui", ReponsesIA.type("string"), "lien", ReponsesIA.type("string"),
                "probabilite", ReponsesIA.type("integer")));
        return ReponsesIA.objet(Map.of("ouvriers", ReponsesIA.tableau(ouvrier)));
    }

    /** L'avis de l'IA : une probabilité par ouvrier (NaN s'il n'en a rien dit), et le lien qu'elle voit. */
    record Avis(double[] p, String[] liens) {}

    static Avis lireAvis(String contenu, List<String> etiquettes) {
        double[] p = new double[etiquettes.size()];
        String[] liens = new String[etiquettes.size()];
        Arrays.fill(p, Double.NaN);
        Arrays.fill(liens, "");
        JsonObject o = ReponsesIA.objetJson(contenu);
        if (o.has("ouvriers") && o.get("ouvriers").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("ouvriers")) {
                if (!e.isJsonObject()) continue;
                JsonObject x = e.getAsJsonObject();
                int i = trouver(etiquettes, ReponsesIA.texte(x, "qui"));
                if (i < 0 || !Double.isNaN(p[i])) continue;
                p[i] = borne(ReponsesIA.entier(x, "probabilite", 50), 0, 100) / 100.0;
                String l = ReponsesIA.texte(x, "lien");
                liens[i] = l.length() > 160 ? l.substring(0, 160) + "…" : l;
            }
        return new Avis(p, liens);
    }

    /** L'ouvrier que l'IA désigne : son étiquette exacte, sinon à peu près (« raoul », « Raoul (guerrier) »). -1 : aucun. */
    static int trouver(List<String> etiquettes, String qui) {
        int i = etiquettes.indexOf(qui);
        if (i >= 0) return i;
        String q = Ordres.normaliser(qui).trim();
        if (q.isEmpty()) return -1;
        for (int k = 0; k < etiquettes.size(); k++) if (Ordres.normaliser(etiquettes.get(k)).trim().equals(q)) return k;
        int trouve = -1;
        for (int k = 0; k < etiquettes.size(); k++) {
            String e = Ordres.normaliser(etiquettes.get(k)).trim();
            if (q.startsWith(e + " ") || e.startsWith(q + " ")) {
                if (trouve >= 0) return -1; // ambigu
                trouve = k;
            }
        }
        return trouve;
    }

    /** Pour le journal : les probabilités en pourcentages. */
    static Map<String, Object> resume(Situation s, double[] pc, double[] pi, double[] p, String[] liens, List<Integer> choisis) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> l = new ArrayList<>();
        for (int i = 0; i < s.pnj().size(); i++) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("prenom", s.pnj().get(i).prenom());
            x.put("code", Math.round(pc[i] * 100));
            x.put("ia", pi == null || Double.isNaN(pi[i]) ? null : Math.round(pi[i] * 100));
            x.put("final", Math.round(p[i] * 100));
            x.put("lien", liens == null ? "" : liens[i]);
            x.put("choisi", choisis.contains(i));
            l.add(x);
        }
        m.put("ouvriers", l);
        return m;
    }
}

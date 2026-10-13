package fr.lacolonie.pnj;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Le répertoire des actions qu'un chef peut demander à ses ouvriers, en plus des ordres de base (suivre, attendre,
 * travailler, rentrer…) : gestes, postures, déplacements, tâches de métier, combats, paroles, imitations… Chacune est
 * décrite en une ligne de {@code repertoire.txt} (qui, quoi, comment, et ce qu'on dit pour la demander) et jouée par
 * l'un des comportements de {@link Consignes}.
 * <p>
 * Pour une phrase, le répertoire trouve les actions qu'elle demande : sans IA, celles dont une formule est dite
 * (« garde à vous », « fais le cochon ») ; avec l'IA, il lui propose les plus proches et elle choisit. Aucune
 * dépendance au jeu (testé seul).
 */
final class Repertoire {
    private Repertoire() {}

    /** Les comportements qui jouent les actions ({@link Consignes}), et s'ils attendent un argument. */
    static final Map<String, Boolean> COMPORTEMENTS = new LinkedHashMap<>();

    static {
        for (String c : List.of("geste", "aller", "suivre", "regarder", "son", "musique", "donner", "jeter", "prendre", "tenir", "utiliser",
                "combat", "travail", "info", "parler", "vitesse", "formation", "message")) COMPORTEMENTS.put(c, true);
        for (String c : List.of("arret", "deposer", "manger", "dormir", "ranger", "silence", "parole", "annuler")) COMPORTEMENTS.put(c, false);
    }

    /** Les gestes que sait dessiner le client ({@link Poses}). */
    static final Set<String> GESTES = Set.of(
            "garde_a_vous", "salut_militaire", "repos", "bras_croises", "mains_hanches", "assis", "a_genoux", "accroupi", "allonge", "en_garde",
            "bouclier", "priere", "mains_en_l_air", "guetter", "reflechir", "tete_basse", "fier", "mediter", "epuise", "cache_yeux", "pointer",
            "montrer", "zombie", "salut_epee", "statue", "saluer_main", "applaudir", "danser", "danse_bras", "gigue", "rire", "pleurer", "trembler",
            "hocher_tete", "secouer_tete", "hausser_epaules", "gratter_tete", "s_etirer", "bailler", "pompes", "echauffement", "marcher_au_pas",
            "tambour", "jouer_flute", "jouer_luth", "boire", "manger", "muscles", "faire_signe", "chut", "frapper", "moulinet", "victoire", "compter",
            "s_incliner", "reverence", "chanter", "parler", "calin", "serrer_main", "caresser", "crier", "oiseau", "singe", "robot", "nager", "boxe",
            "tirer_arc", "lancer", "piocher", "scier", "balayer", "coucou", "impatient", "timide", "dormir");

    /** Les lieux où l'on peut envoyer un ouvrier (comportement {@code aller}). */
    static final Set<String> LIEUX = Set.of("chef", "derriere", "devant", "gauche", "droite", "la", "poste", "lit", "cible", "loin", "recule",
            "avance", "haut", "abri", "eau", "feu", "porte", "coffre", "four", "etabli", "cloche", "arbre", "lumiere", "ombre", "dehors",
            "chien", "chat", "cheval", "vache", "cochon", "mouton", "poule", "villageois", "golem", "lapin", "renard", "perroquet", "chevre", "abeille");

    /** Le vocabulaire des programmes (vérifié au chargement par les tests, joué par {@link Consignes}). */
    static final Set<String> SONS = Set.of("cochon", "vache", "mouton", "poule", "chien", "loup", "chat", "cheval", "ane", "chevre", "perroquet",
            "renard", "grenouille", "abeille", "villageois", "zombie", "squelette", "creeper", "enderman", "araignee", "ghast", "ours", "chauve_souris",
            "dauphin", "panda", "tortue", "slime", "rot", "cri", "cor", "talons", "sifflet", "applaudir", "hourra", "rire", "grogner", "soupir", "pas",
            "frappe", "epee", "bouclier", "scie", "compter");
    static final Set<String> INSTRUMENTS = Set.of("flute", "harpe", "guitare", "tambour", "cloche", "xylophone", "banjo", "didgeridoo", "basse",
            "cloche_vache", "carillon", "caisse", "baguettes", "bit", "pling", "vibraphone", "hasard");
    static final Set<String> PARTICULES = Set.of("coeur", "note", "colere", "content", "sueur", "larmes", "magie", "eclaboussure", "fumee");
    static final Set<String> OBJETS = Set.of("objet", "nourriture", "tout", "arme", "outil", "torche", "bouclier");
    static final Set<String> INFOS = Set.of("heure", "meteo", "jour", "lune", "poste", "lit", "cible", "position", "nord", "altitude", "biome",
            "monstres", "animaux", "joueurs", "lumiere", "eau", "sac", "sante", "outil", "armure", "salaire", "anciennete", "caractere", "niveau",
            "distance", "ouvriers", "minerai", "arbre");
    static final Set<String> TRAVAUX = Set.of("miner_la", "tunnel", "trou", "salle", "carriere", "torches", "torche_la", "ramasser", "abattre_la", "abattre_autour",
            "planter", "planter_la", "defricher", "foret", "defricher_zone", "planches", "charbon");
    static final Set<String> COMBATS = Set.of("cible", "nettoyer", "garder_ici", "proteger", "patrouiller", "charge", "repli", "proteger_chef", "ronde_point", "ronde_effacer");
    static final Set<String> BLOCS = Set.of("porte", "cloche", "levier", "bouton", "trappe", "portillon", "note");
    static final Set<String> REGARDS = Set.of("chef", "devant", "ciel", "sol", "la", "cible", "autour", "dos", "aucun");
    static final Set<String> FORMES = Set.of("rang", "file", "cercle", "derriere");
    static final Set<String> VITESSES = Set.of("rapide", "lent", "normal");
    /** Les genres de paroles calculés (pas tirés de paroles.txt). */
    static final Set<String> GENRES_CALCULES = Set.of("presentation", "rapport", "compter", "alphabet");

    record Entree(String id, Set<String> metiers, String nom, String categorie, String comportement, String arg, Map<String, String> options,
                  List<String> dits, List<String> reponses, List<String> exemples) {
        /** Ce métier peut la faire (pas de métier précisé : tout le monde). */
        boolean pour(String metier) { return metiers.isEmpty() || metiers.contains(metier); }

        String opt(String cle, String defaut) { return options.getOrDefault(cle, defaut); }

        boolean drapeau(String cle) { return options.containsKey(cle); }

        /** Jusqu'à nouvel ordre (posture, garde, silence…) plutôt qu'un geste qui finit seul. */
        boolean tenue() {
            return "tenu".equals(options.get("duree")) || drapeau("tenu") || Set.of("arret", "suivre", "dormir", "formation").contains(comportement)
                    || comportement.equals("combat") && Set.of("garder_ici", "proteger", "patrouiller", "proteger_chef").contains(arg);
        }

        /** Elle vise quelqu'un (« protège @ ») : un autre ouvrier ou un joueur, trouvé dans la phrase ou donné par l'IA. */
        boolean visePersonne() { return arg.equals("cible") || options.containsKey("a") || comportement.equals("combat") && arg.equals("proteger"); }

        /**
         * Les ordres de base (suivre, attendre, rentrer, sac…) et les missions que la phrase ne veut pas dire quand elle demande
         * cette action : « donne-moi ton fer » n'est pas une mission de fer, « suis Raoul » ne veut pas dire « suis-moi ».
         */
        Set<String> remplace() {
            Set<String> r = new LinkedHashSet<>();
            if (options.containsKey("remplace")) r.addAll(List.of(options.get("remplace").split(",")));
            switch (comportement) {
                case "donner" -> { r.add("missions_sans_nombre"); r.add("sac"); }
                case "jeter", "tenir", "prendre", "ranger", "deposer" -> { r.add("missions"); r.add("sac"); }
                case "info", "combat", "travail", "message" -> r.add("missions");
                case "suivre" -> r.add("suivre");
                default -> {}
            }
            return r;
        }

        /** Sa durée en secondes ; -1 : jusqu'à nouvel ordre ; 0 : le temps que le comportement finisse. */
        int duree() {
            if (tenue()) return -1;
            String d = options.get("duree");
            if (d == null) return comportement.equals("geste") || comportement.equals("regarder") ? 4 : 0;
            return d.endsWith("m") ? Integer.parseInt(d.substring(0, d.length() - 1)) * 60 : Integer.parseInt(d.replace("s", ""));
        }

        /** Un petit geste que l'IA peut faire d'elle-même pour accompagner sa réponse (hocher la tête, hausser les épaules…). */
        boolean spontane() { return drapeau("spontane"); }
    }

    static final List<Entree> TOUTES = new ArrayList<>();
    static final Map<String, Entree> PAR_ID = new LinkedHashMap<>();
    /** Les textes sans IA, par genre (une blague, un dicton, une chanson…), dans {@code paroles.txt}. */
    static final Map<String, List<String>> PAROLES = new LinkedHashMap<>();

    static void charger() {
        TOUTES.clear();
        PAR_ID.clear();
        PAROLES.clear();
        String categorie = "";
        int n = 0;
        for (String ligne : lignes("/repertoire.txt")) {
            n++;
            String l = ligne.strip();
            if (l.isEmpty() || l.startsWith("# ") || l.equals("#")) continue;
            if (l.startsWith("## ")) { categorie = l.substring(3).strip(); continue; }
            String[] c = l.split("\\|", -1);
            if (c.length < 5) throw new IllegalStateException("repertoire.txt ligne " + n + " : il faut 5 champs");
            Entree e = entree(c, categorie, n);
            if (PAR_ID.put(e.id, e) != null) throw new IllegalStateException("repertoire.txt ligne " + n + " : « " + e.id + " » existe déjà");
            TOUTES.add(e);
        }
        for (String ligne : lignes("/paroles.txt")) {
            String l = ligne.strip();
            int i = l.indexOf('|');
            if (l.isEmpty() || l.startsWith("#") || i < 0) continue;
            PAROLES.computeIfAbsent(l.substring(0, i).strip(), k -> new ArrayList<>()).add(l.substring(i + 1).strip());
        }
        indexer();
    }

    static List<String> lignes(String ressource) {
        try (InputStream in = Repertoire.class.getResourceAsStream(ressource)) {
            if (in == null) throw new IllegalStateException(ressource + " introuvable");
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(ressource + " illisible", e);
        }
    }

    static Entree entree(String[] c, String categorie, int n) {
        String id = c[0].strip();
        Set<String> metiers = new LinkedHashSet<>();
        for (String m : c[1].split(",")) if (!m.isBlank() && !m.strip().equals("tous")) metiers.add(m.strip());
        String[] prog = c[3].strip().split("\\s+");
        String comp = prog[0];
        if (!COMPORTEMENTS.containsKey(comp)) throw new IllegalStateException("repertoire.txt ligne " + n + " : comportement inconnu « " + comp + " »");
        String arg = "";
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < prog.length; i++) {
            int eg = prog[i].indexOf('=');
            if (eg > 0) opts.put(prog[i].substring(0, eg), prog[i].substring(eg + 1));
            else if (i == 1 && COMPORTEMENTS.get(comp)) arg = prog[i];
            else opts.put(prog[i], "");
        }
        if (COMPORTEMENTS.get(comp) && arg.isEmpty()) throw new IllegalStateException("repertoire.txt ligne " + n + " : « " + comp + " » attend un argument");
        List<String> dits = new ArrayList<>(), exemples = new ArrayList<>();
        for (String d : c[4].split(";")) {
            if (!d.isBlank() && exemples.size() < 4) exemples.add(d.strip());
            String x = Ordres.normaliser(d.replace("@", " zzpersonnezz ")).strip().replace("zzpersonnezz", "@");
            if (!x.isEmpty() && !dits.contains(x)) dits.add(x);
        }
        List<String> reponses = new ArrayList<>();
        if (c.length > 5) for (String r : c[5].split(";")) if (!r.isBlank()) reponses.add(r.strip());
        return new Entree(id, metiers, c[2].strip(), categorie, comp, arg, opts, dits, reponses, exemples);
    }

    // --- Trouver ce que demande une phrase ------------------------------------------------------------------

    /** Des mots qui ne disent rien de l'action (« fais », « un peu », « s'il te plaît »…) : on ne les compte pas. */
    static final Set<String> VIDES = Set.copyOf(List.of("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "a", "au", "aux", "et", "en", "y",
            "me", "m", "moi", "te", "t", "toi", "nous", "vous", "se", "s", "ce", "c", "cet", "cette", "ces", "mon", "ton", "son", "ma", "ta", "sa",
            "mes", "tes", "ses", "nos", "vos", "notre", "votre", "pour", "par", "sur", "dans", "avec", "chef", "plait", "stp", "svp", "il", "peux",
            "pouvez", "tu", "pourrais", "pourriez", "veux", "voudrais", "bien", "tres", "peu", "petit", "petite", "allez", "hop", "maintenant",
            "fais", "fait", "faire", "faites", "un", "donc", "alors", "bon", "eh", "he", "oh", "ho", "dis", "je", "j", "que", "qu", "est", "ca",
            "?", "sais", "savez", "encore", "aussi", "tout", "toute", "tous", "gars", "les", "mais", "on", "va", "vas", "aller", "allez", "ici"));

    /** Avant l'action, ces mots l'arrêtent (« arrête de danser », « ça suffit les chansons »). */
    static final Set<String> FINS = Set.of("arrete", "arretez", "arreter", "cesse", "cessez", "stoppe", "stoppez", "finis", "finissez", "assez",
            "suffit", "fini", "termine");
    /** Entre un sujet et l'action, ces mots la gardent à son sujet (« je vais danser », « il ne chante », « on se couche »). */
    static final Set<String> AUXILIAIRES = Set.of("ne", "n", "me", "m", "te", "t", "se", "s", "vais", "va", "vas", "allons", "vont", "veux", "veut",
            "voulons", "compte", "comptes", "aime", "aimes", "adore", "sais");

    /** Ces mots font d'un « tu » ou d'un « il » une demande (« tu peux danser ? », « il faut que tu chantes »). */
    static final Set<String> MODAUX = Set.of("peux", "pourrais", "pouvez", "pourriez", "veux", "voudrais", "sais", "savez", "dois", "faut", "faudrait",
            "peut", "pourrait", "merci");

    /** Les mots qui arrêtent ou nient une action (« arrête de », « ne… plus ») : ils ne l'éloignent pas d'une formule. */
    static final Set<String> NEUTRES = new HashSet<>();

    static {
        for (String w : FINS) NEUTRES.add(racine(w));
        for (String w : List.of("ne", "n", "pas", "plus", "jamais")) NEUTRES.add(racine(w));
    }

    /** Un sujet juste avant le verbe : on décrit quelqu'un (« il danse », « je chante »), on ne commande rien. */
    static final Set<String> SUJETS = Set.of("je", "j", "il", "elle", "on", "ils", "elles", "qui", "tu"); // « joue-nous », « asseyez-vous » : des compléments

    /** Une racine grossière : « dansez », « danser », « danses » → « dans ». Assez pour comparer des mots, pas pour les écrire. */
    static String racine(String w) {
        if (w.length() <= 3) return w;
        // D'abord le pluriel (« charbons » → « charbon »), puis les terminaisons des verbes (« dansez », « danser », « danse » → « dans »).
        if ((w.endsWith("s") || w.endsWith("x")) && w.length() > 4) w = w.substring(0, w.length() - 1);
        for (String f : new String[]{"issez", "erez", "iez", "ez", "ent", "er", "e"})
            if (w.endsWith(f) && w.length() - f.length() >= 3) return w.substring(0, w.length() - f.length());
        return w;
    }

    /**
     * Où commence, dans la phrase, la formule « va voir @ » avec quelqu'un à la place de « @ » : un mot inconnu du
     * répertoire (un prénom, un pseudo) ou un pronom en fin de phrase (« suis-le »). « Protège la base » n'est pas
     * « protège @ ». Renvoie la position en caractères dans {@code n}, ou -1.
     */
    static int personne(String n, List<String> m, String d) {
        List<String> f = mots(d);
        int at = f.indexOf("@");
        for (int i = 0; i + f.size() <= m.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < f.size() && ok; j++) ok = j == at ? estPersonne(m, i + j) : f.get(j).equals(m.get(i + j));
            if (ok) {
                int c = 0;
                for (int j = 0; j < i; j++) c += m.get(j).length() + 1;
                return c;
            }
        }
        return -1;
    }

    static final Set<String> PRONOMS = Set.of("le", "la", "les", "lui", "l", "leur", "eux");

    static boolean estPersonne(List<String> m, int p) {
        if (p >= m.size()) return false;
        String w = m.get(p);
        if (PRONOMS.contains(w)) return p == m.size() - 1 || m.get(p + 1).equals("?") || VIDES.contains(m.get(p + 1)) && !PRONOMS.contains(m.get(p + 1));
        return w.length() >= 3 && !VIDES.contains(w) && !RARETE.containsKey(racine(w)) && !w.matches("\\d+") && !Ordres.NOMBRES.containsKey(w);
    }

    static List<String> mots(String normalise) {
        List<String> r = new ArrayList<>();
        for (String w : normalise.strip().split(" ")) if (!w.isEmpty()) r.add(w);
        return r;
    }

    /** Pour chaque formule (normalisée), ses racines utiles ; et la rareté de chaque racine dans le répertoire. */
    static final Map<String, List<String>> RACINES = new HashMap<>();
    static final Map<String, Double> RARETE = new HashMap<>();

    static void indexer() {
        RACINES.clear();
        RARETE.clear();
        Map<String, Integer> df = new HashMap<>();
        for (Entree e : TOUTES) {
            Set<String> vues = new HashSet<>();
            for (String d : e.dits) {
                List<String> r = new ArrayList<>();
                for (String w : mots(d)) if (!VIDES.contains(w) && !w.equals("@")) r.add(racine(w));
                RACINES.put(d, r);
                vues.addAll(r);
            }
            for (String r : vues) df.merge(r, 1, Integer::sum);
        }
        df.forEach((r, k) -> RARETE.put(r, Math.log(1 + TOUTES.size() / (double) k)));
    }

    /**
     * Une action trouvée dans la phrase. {@code score} : 100 et plus, une formule dite telle quelle ; 70 et plus, tous ses
     * mots présents (dans le désordre, conjugués autrement) ; moins, quelques mots en commun (une piste pour l'IA).
     * {@code fin} : on lui demande d'arrêter (« arrête de danser ») ; {@code nie} : « ne danse pas ».
     */
    record Trouve(Entree e, double score, int debut, boolean fin, boolean nie) {
        boolean sur() { return score >= 70 && !nie; }
    }

    /** Les actions que la phrase pourrait demander, de la plus sûre à la moins sûre (une par action). */
    static List<Trouve> chercher(String texte) {
        String n = Ordres.normaliser(texte);
        String sansNombres = n.replaceAll(" \\d{1,6}(?= )", " des");
        List<String> m = mots(n);
        List<String> rac = new ArrayList<>();
        for (String w : m) rac.add(VIDES.contains(w) ? "" : racine(w));
        Set<String> presentes = new HashSet<>(rac);
        presentes.remove("");
        List<Trouve> r = new ArrayList<>();
        for (Entree e : TOUTES) {
            Trouve meilleur = null;
            String formule = "";
            for (String d : e.dits) {
                double score;
                int debut;
                int k = d.contains("@") ? personne(n, m, d) : n.indexOf(" " + d + " ");
                // « fais 32 planches » se dit comme « fais des planches » (le nombre est lu à part).
                // (k est alors une place dans sansNombres : chaque nombre y est remplacé par un seul mot, le rang des mots est le même.)
                String ou = n;
                if (k < 0 && !d.contains("@") && !sansNombres.equals(n) && (k = sansNombres.indexOf(" " + d + " ")) >= 0) ou = sansNombres;
                List<String> rd = RACINES.get(d);
                if (d.contains("@") && k < 0) {
                    // Sans quelqu'un à sa place, la formule ne compte que comme piste pour l'IA.
                    if (rd.isEmpty() || !presentes.containsAll(rd)) continue;
                    score = 40;
                    debut = rac.indexOf(rd.get(0));
                } else if (k >= 0) {
                    score = 100 + 3 * mots(d).size();
                    debut = mots(ou.substring(0, k)).size();
                } else if (!rd.isEmpty() && presentes.containsAll(rd) && (mots(d).size() == 1 || rd.size() >= 2)) {
                    // Un seul mot qui compte dans une formule plus longue (« il fait noir ici ») : il faut la formule entière.
                    // Chaque autre mot qui compte ailleurs dans le répertoire éloigne la phrase de cette action (« va chercher
                    // la poule » n'est pas « fais la poule ») ; un prénom ou un mot inconnu ne compte pas.
                    long autres = presentes.stream().filter(x -> !rd.contains(x) && RARETE.containsKey(x) && !NEUTRES.contains(x)).count();
                    score = 70 + 3 * rd.size() - 10 * autres;
                    debut = rac.indexOf(rd.get(0));
                } else {
                    double commun = 0, total = 0;
                    for (String x : rd) {
                        double p = RARETE.getOrDefault(x, 1.0);
                        total += p;
                        if (presentes.contains(x)) commun += p;
                    }
                    if (commun == 0) continue;
                    score = 50 * commun / total;
                    debut = -1;
                    for (String x : rd) if (presentes.contains(x)) { debut = rac.indexOf(x); break; }
                }
                if (meilleur == null || score > meilleur.score) { meilleur = new Trouve(e, score, debut, false, false); formule = d; }
            }
            if (meilleur == null) continue;
            int k = meilleur.debut;
            boolean fin = false, nie = false;
            if (k >= 0) {
                for (int i = Math.max(0, k - 4); i < k; i++) if (FINS.contains(m.get(i))) fin = true;
                // « ne danse plus » : une fin ; « ne danse pas » : un refus.
                boolean ne = k > 0 && (m.get(k - 1).equals("ne") || m.get(k - 1).equals("n")) || k > 1 && (m.get(k - 2).equals("ne") || m.get(k - 2).equals("n"));
                if (ne) {
                    for (int i = k; i < Math.min(m.size(), k + 5); i++) {
                        if (m.get(i).equals("plus")) fin = true;
                        if (m.get(i).equals("pas") || m.get(i).equals("jamais")) nie = true;
                    }
                }
                // « il danse », « je vais poser une torche » : on parle de quelqu'un, ou de soi ; « qui danse ? » : une question
                // sur quelqu'un. Mais « tu peux danser ? », « il faut que tu chantes » : une demande.
                if (!fin && !formule.startsWith(k > 0 ? m.get(k - 1) + " " : "\0")) {
                    // Le sujet tient au verbe : juste devant (« il danse »), ou devant un auxiliaire (« je vais danser »).
                    boolean sujet = k > 0 && SUJETS.contains(m.get(k - 1)) || k > 1 && SUJETS.contains(m.get(k - 2)) && AUXILIAIRES.contains(m.get(k - 1)),
                            demande = false;
                    for (int i = Math.max(0, k - 3); i < k; i++) if (MODAUX.contains(m.get(i))) demande = true;
                    if (sujet && !demande) nie = true;
                }
            }
            r.add(new Trouve(meilleur.e, meilleur.score, k, fin, nie));
        }
        r.sort(Comparator.comparingDouble(Trouve::score).reversed());
        return r;
    }

    /**
     * Ce que la phrase demande pour de bon (sans IA) : une ou deux actions dont la formule est dite, que ce métier peut
     * faire ({@code metier} vide : n'importe lequel), et celles qu'on lui demande d'arrêter.
     */
    static List<Trouve> lire(String texte, String metier) {
        List<Trouve> r = new ArrayList<>();
        for (Trouve t : chercher(texte)) {
            if (!t.sur() || !(metier.isEmpty() || t.e.pour(metier))) continue;
            // Une seconde action seulement si elle est dite ailleurs dans la phrase (« salue et danse »), pas une variante de la première.
            if (!r.isEmpty() && (r.size() >= 2 || t.score < 100 || r.get(0).score < 100 || Math.abs(t.debut - r.get(0).debut) < 2)) continue;
            r.add(t);
        }
        return r;
    }

    /** La phrase demande une action du répertoire (un ordre, même sans verbe : « garde à vous ! », « en rang ! »). */
    static boolean commande(String texte) {
        for (Trouve t : chercher(texte)) if (t.sur() && !t.fin) return true;
        return false;
    }

    /** Les métiers que concerne la phrase : une action du répertoire propre à certains métiers (« abats cet arbre » : le bûcheron). */
    static Set<String> metiersConcernes(String texte) {
        Set<String> r = new LinkedHashSet<>();
        for (Trouve t : chercher(texte)) if (t.sur() && !t.fin && !t.e.metiers.isEmpty()) { r.addAll(t.e.metiers); break; }
        return r;
    }

    /** Les petits gestes que l'IA peut toujours faire d'elle-même. */
    static List<Entree> spontanes() { return TOUTES.stream().filter(Entree::spontane).toList(); }

    /**
     * Les actions à proposer à l'IA pour cette phrase : les {@code k} plus proches que ces métiers peuvent faire, puis
     * les petits gestes spontanés. Les ordres de base (suivre, attendre…) ne sont pas ici : l'IA les a toujours.
     */
    static List<Entree> candidats(String texte, Collection<String> metiers, int k) {
        List<Entree> r = new ArrayList<>();
        for (Trouve t : chercher(texte)) {
            if (r.size() >= k || t.score < 12) break;
            if (metiers.stream().anyMatch(t.e::pour)) r.add(t.e);
        }
        for (Entree e : spontanes()) if (!r.contains(e)) r.add(e);
        return r;
    }

    /** Une action que la phrase demande mais qu'aucun de ces métiers ne fait (pour que l'IA le dise franchement). */
    static List<Entree> horsMetiers(String texte, Collection<String> metiers) {
        List<Entree> r = new ArrayList<>();
        for (Trouve t : chercher(texte)) {
            if (t.score < 70 || r.size() >= 3) break;
            if (metiers.stream().noneMatch(t.e::pour)) r.add(t.e);
        }
        return r;
    }

    /** Un texte du genre demandé (blague, dicton, chanson…), pour parler sans IA. */
    static String parole(String genre, Random r) {
        List<String> l = PAROLES.getOrDefault(genre, PAROLES.getOrDefault("defaut", List.of("Euh… je ne sais pas trop quoi dire, chef.")));
        return l.get(r.nextInt(l.size()));
    }

    /** « danser » → « danser, gigue… » : l'aide (panneau, /pnj actions). */
    static List<Map<String, Object>> tableau() {
        List<Map<String, Object>> r = new ArrayList<>();
        for (Entree e : TOUTES) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", e.id);
            x.put("nom", e.nom);
            x.put("categorie", e.categorie);
            x.put("metiers", e.metiers.isEmpty() ? List.of() : List.copyOf(e.metiers));
            x.put("exemples", e.exemples());
            x.put("tenue", e.tenue());
            r.add(x);
        }
        return r;
    }

    // En dernier : tout ce dont la lecture a besoin (les mots vides, l'index) est prêt.
    static {
        charger();
    }
}

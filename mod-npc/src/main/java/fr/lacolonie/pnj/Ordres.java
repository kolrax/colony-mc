package fr.lacolonie.pnj;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprend ce qu'on dit ou écrit à un ouvrier, en français : ordres (« suis-moi », « rentre au poste »), questions
 * (« où en es-tu ? ») et missions (« ramène 10 fers et 1 diamant », « garde 32 bûches de bouleau en stock »).
 * Pas d'IA générative : des règles, des synonymes et des nombres en lettres. Aucune dépendance au jeu (testé seul).
 */
final class Ordres {
    private Ordres() {}

    enum Intention { SUIVRE, ARRETER_SUIVRE, ATTENDRE, TRAVAIL, RENTRER, SAC, RENVOYER, OUI, NON, ETAT, AVANCEMENT, POURQUOI, AIDE, SALUT, MERCI }

    /** Une mission comprise : un identifiant du catalogue du métier, « stock » ou « quantite », un nombre. */
    record Mission(String id, String mode, int nombre) {}

    /**
     * Une action du répertoire ({@link Repertoire}) demandée : son identifiant, ce qu'elle vise (un prénom, un objet ;
     * vide : on le cherchera dans la phrase), un nombre et une durée (0 : ceux du répertoire). {@code fin} : on lui
     * demande de l'arrêter (« arrête de danser »).
     */
    record Faire(String id, String cible, int nombre, int duree, boolean fin) {}

    /** Ce qu'on a compris d'un message. */
    static final class Lecture {
        final Set<Intention> intentions = EnumSet.noneOf(Intention.class);
        final List<Mission> missions = new ArrayList<>();
        /** Les missions s'ajoutent aux siennes (« aussi », « en plus ») au lieu de les remplacer. */
        boolean ajouter;
        /** Horaires demandés (-1 : rien dit). Même heure de début et de fin : toute la journée. */
        int debut = -1, fin = -1;
        /** Durée maximale des expéditions demandée, en jours (-1 : rien dit). */
        int jours = -1;
        /** Ce qu'on lui demande et qui n'est pas de son métier (« du bois » à un mineur), par métier. */
        final Map<String, String> horsMetier = new LinkedHashMap<>();
        /** Les actions du répertoire demandées (gestes, déplacements, tâches, paroles…). */
        final List<Faire> faire = new ArrayList<>();
        /** La phrase elle-même (on y cherche qui ou quoi est visé), et la place de l'ouvrier dans le groupe à qui elle est dite. */
        String texte = "";
        int rang, total = 1;
        /** Les ordres longs ({@link OrdresLongs}) : des règles à garder, « oublie tes ordres », des missions à refaire chaque jour. */
        final List<OrdresLongs.Regle> regles = new ArrayList<>();
        boolean annulerRegles, quotidien;
        /** Un ordre qui le tuerait (« saute dans la lave ») : le danger ({@link Obeissance#mortel}), sinon null. */
        String mortel;

        boolean vide() { return intentions.isEmpty() && missions.isEmpty() && debut < 0 && fin < 0 && jours < 0 && horsMetier.isEmpty() && faire.isEmpty()
                && regles.isEmpty() && !annulerRegles && mortel == null; }
    }

    // --- Le vocabulaire -------------------------------------------------------------------------------

    /** Pour chaque métier, chaque mission de son catalogue et ses noms (sans accents, au singulier et au pluriel). */
    static final Map<String, Map<String, List<String>>> NOMS = new LinkedHashMap<>();

    static {
        Map<String, List<String>> mineur = new LinkedHashMap<>();
        mineur.put("charbon", List.of("charbon", "charbons", "houille", "minerai de charbon"));
        mineur.put("fer", List.of("fer", "fers", "minerai de fer", "minerais de fer", "lingot de fer", "lingots de fer", "fer brut"));
        mineur.put("cuivre", List.of("cuivre", "cuivres", "minerai de cuivre", "lingot de cuivre", "lingots de cuivre"));
        mineur.put("zinc", List.of("zinc", "zincs", "minerai de zinc"));
        mineur.put("or", List.of("or", "minerai d or", "lingot d or", "lingots d or", "pepite d or", "pepites d or"));
        mineur.put("lapis", List.of("lapis", "lapis lazuli", "lazuli"));
        mineur.put("redstone", List.of("redstone", "poudre de redstone", "pierre rouge"));
        mineur.put("diamant", List.of("diamant", "diamants"));
        mineur.put("pierre", List.of("pierre", "pierres", "roche", "roches", "caillou", "cailloux", "cobble", "cobblestone", "pierre taillee"));
        mineur.put("andesite", List.of("andesite", "andesites"));
        mineur.put("granite", List.of("granite", "granites"));
        mineur.put("diorite", List.of("diorite", "diorites"));
        mineur.put("abimes", List.of("ardoise des abimes", "ardoise", "ardoises", "deepslate", "abimes"));
        NOMS.put("mineur", mineur);

        Map<String, List<String>> bucheron = new LinkedHashMap<>();
        bucheron.put("chene_noir", List.of("chene noir", "chenes noirs", "chene fonce"));
        bucheron.put("chene", List.of("chene", "chenes"));
        bucheron.put("bouleau", List.of("bouleau", "bouleaux"));
        bucheron.put("sapin", List.of("sapin", "sapins", "epicea", "epiceas"));
        bucheron.put("jungle", List.of("bois de la jungle", "bois de jungle", "jungle", "acajou", "bois exotique"));
        bucheron.put("acacia", List.of("acacia", "acacias"));
        bucheron.put("cerisier", List.of("cerisier", "cerisiers"));
        bucheron.put("paletuvier", List.of("paletuvier", "paletuviers", "mangrove"));
        bucheron.put("tout", List.of("n importe quel bois", "bois", "buche", "buches", "rondin", "rondins", "tronc", "troncs"));
        NOMS.put("bucheron", bucheron);

        Map<String, List<String>> fermier = new LinkedHashMap<>();
        fermier.put("ble", List.of("ble", "bles", "froment", "epi de ble", "epis de ble", "gerbe de ble", "gerbes de ble"));
        fermier.put("carotte", List.of("carotte", "carottes"));
        fermier.put("pomme_de_terre", List.of("pomme de terre", "pommes de terre", "patate", "patates"));
        fermier.put("betterave", List.of("betterave", "betteraves"));
        NOMS.put("fermier", fermier);

        Map<String, List<String>> eleveur = new LinkedHashMap<>();
        eleveur.put("cuir", List.of("cuir", "cuirs", "peau", "peaux", "peau de vache", "peaux de vache"));
        eleveur.put("laine", List.of("laine", "laines", "toison", "toisons", "pelote de laine", "pelotes de laine"));
        eleveur.put("viande", List.of("viande", "viandes", "viande crue", "viandes crues", "boeuf", "porc", "poulet cru", "cotelette", "cotelettes",
                "cotelette de porc", "cotelettes de porc", "gigot", "gigots", "lard"));
        eleveur.put("oeufs", List.of("oeuf", "oeufs"));
        eleveur.put("lait", List.of("lait", "seau de lait", "seaux de lait"));
        NOMS.put("eleveur", eleveur);

        Map<String, List<String>> pecheur = new LinkedHashMap<>();
        pecheur.put("morue", List.of("morue", "morues", "cabillaud", "cabillauds"));
        pecheur.put("saumon", List.of("saumon", "saumons"));
        pecheur.put("poisson", List.of("poisson", "poissons", "poisson cru", "poissons crus"));
        NOMS.put("pecheur", pecheur);
        Map<String, List<String>> chasseur = new LinkedHashMap<>();
        chasseur.put("viande", List.of("viande", "viandes", "viande crue", "viandes crues", "gibier", "venaison", "lapin cru", "lapins crus", "boeuf", "porc",
                "poulet cru", "cotelette", "cotelettes", "gigot", "gigots", "lard"));
        chasseur.put("cuir", List.of("cuir", "cuirs", "peau", "peaux", "peau de lapin", "peaux de lapin", "peau de vache", "peaux de vache"));
        chasseur.put("plumes", List.of("plume", "plumes"));
        NOMS.put("chasseur", chasseur);

        Map<String, List<String>> guerrier = new LinkedHashMap<>();
        guerrier.put("escorter", List.of("escorte moi", "escorte", "escorter", "protege moi", "garde du corps"));
        guerrier.put("ronde", List.of("fais la ronde", "la ronde", "ronde", "patrouille", "patrouiller", "patrouilles"));
        guerrier.put("garder", List.of("garde le poste", "garde le bureau", "garde la base", "monte la garde", "surveille le poste", "surveille", "garder le poste",
                "protege la base", "proteger la base", "protege le poste", "proteger le poste", "protege le bureau", "defends la base", "defendre la base",
                "defends le poste", "defendre le poste", "protege le camp", "defends le camp", "garde le camp", "garde l entree", "protege l entree",
                "garder la base", "garder le bureau", "garder l entree", "garder la porte", "garde la porte", "surveiller la base", "surveiller le poste",
                "surveiller l entree", "proteger l entree", "defendre l entree", "defends l entree", "garder le camp", "monter la garde"));
        NOMS.put("guerrier", guerrier);
    }

    /** Des mots qui désignent le bois, même entourés d'autres (« des bûches de bouleau ») : ils ne comptent pas comme « n'importe quel bois ». */
    static final Set<String> MOTS_DU_BOIS = Set.of("bois", "buche", "buches", "rondin", "rondins", "tronc", "troncs");

    static final Map<String, Integer> NOMBRES = new LinkedHashMap<>();

    static {
        String[] unites = {"zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize",
                "quatorze", "quinze", "seize"};
        for (int i = 0; i < unites.length; i++) NOMBRES.put(unites[i], i);
        NOMBRES.put("une", 1);
        NOMBRES.put("vingt", 20);
        NOMBRES.put("vingts", 20);
        NOMBRES.put("trente", 30);
        NOMBRES.put("quarante", 40);
        NOMBRES.put("cinquante", 50);
        NOMBRES.put("soixante", 60);
        NOMBRES.put("cent", 100);
        NOMBRES.put("cents", 100);
        NOMBRES.put("mille", 1000);
    }

    /** Des quantités toutes faites : « une pile » = 64, « une douzaine » = 12… */
    static final Map<String, Integer> PAQUETS = Map.of("pile", 64, "piles", 64, "stack", 64, "stacks", 64, "douzaine", 12, "douzaines", 12,
            "vingtaine", 20, "trentaine", 30, "cinquantaine", 50, "centaine", 100);

    // --- Lecture ------------------------------------------------------------------------------------

    /** Minuscules, sans accents ni ponctuation, les mots séparés par une espace (« Rentre au poste ! » → « rentre au poste »). */
    static String normaliser(String s) {
        String n = Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replace("œ", "oe").replace("æ", "ae"); // « œufs », « vœu » : écrits « oeufs », « voeu » dans le vocabulaire
        n = n.replaceAll("(\\d)\\s*h\\s*(\\d)", "$1h$2");
        n = n.replaceAll("[^a-z0-9?]+", " ").replace("?", " ? ");
        return " " + n.trim().replaceAll("\\s+", " ") + " ";
    }

    static boolean dit(String n, String... formules) {
        for (String f : formules) if (n.contains(" " + f + " ")) return true;
        return false;
    }

    static final Pattern HORAIRES = Pattern.compile(" (?:de |entre |des |a partir de )?(\\d{1,2}) ?h(?:eures?)?(?: ?\\d{1,2})? (?:a|jusqu a|et|au|-) (\\d{1,2}) ?h");
    static final Pattern COMMENCE = Pattern.compile(" (?:commence|commences|commencez|commencer|debute|debutes|debuter|embauche|leve toi|reveille toi) (?:a |vers |des )?(\\d{1,2}) ?h");
    static final Pattern FINIS = Pattern.compile(" (?:finis|finit|finissez|finir|termine|termines|terminer|arrete|arretes|arreter|debauche|stoppe|quitte) (?:le travail |de travailler |ta journee )?(?:a |vers )?(\\d{1,2}) ?h");
    static final Pattern JOURS = Pattern.compile(" (\\d{1,2}|" + String.join("|", List.of("un", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "quinze", "vingt", "trente")) + ") jours?");

    static final Pattern A_LA_MINE = Pattern.compile("(?:^|[,.;:!?]|\\b(?:tous|toutes|allez|va|vas|file|filez|direction|retourne|retournez|descends|descendez|vite|hop|et|monde))\\s*(?:a la mine|au bois|a la foret|a la tache|au chantier)\\b"
            .replace(" la ", " +la +").replace("au ", "au +"));

    /** Lit un message adressé à un ouvrier de ce métier. */
    static Lecture lire(String texte, String metier) {
        Lecture l = new Lecture();
        // Les ordres longs d'abord : la conséquence d'une règle (« s'il pleut, rentre ») ne se fait pas tout de suite.
        OrdresLongs.Lu lu = OrdresLongs.lire(texte);
        for (OrdresLongs.Regle r : lu.regles) {
            if (r.voit() && r.previens()) { l.regles.add(r); continue; }
            Lecture x = lire(r.suite, metier);
            String verbe = OrdresLongs.metierDuVerbe(r.suite);
            boolean rien = x.intentions.isEmpty() && x.missions.isEmpty() && x.faire.isEmpty();
            // L'emploi du temps : « l'après-midi tu coupes », dit à un mineur, n'est pas de son métier (il le dira).
            if (r.periode() && verbe != null && !metier.isEmpty() && !verbe.equals(metier) && rien) {
                l.horsMetier.putIfAbsent(verbe, switch (verbe) { case "mineur" -> "pierre"; case "bucheron" -> "tout"; default -> "garder"; });
                continue;
            }
            if (rien && !x.horsMetier.isEmpty()) { x.horsMetier.forEach(l.horsMetier::putIfAbsent); continue; }
            if (!rien || verbe != null) l.regles.add(r); // une suite qu'il ne comprend pas : ce n'était pas une règle
        }
        l.annulerRegles = lu.annuler;
        l.quotidien = lu.quotidien;
        if (!l.regles.isEmpty() || !l.horsMetier.isEmpty() || lu.annuler) texte = lu.reste;
        l.texte = texte;
        String n = normaliser(texte);

        // Horaires et expéditions (avant tout, leurs nombres ne sont pas des quantités).
        Matcher m = HORAIRES.matcher(n);
        if (m.find()) { l.debut = Math.floorMod(Integer.parseInt(m.group(1)), 24); l.fin = Math.floorMod(Integer.parseInt(m.group(2)), 24); }
        if ((m = COMMENCE.matcher(n)).find()) l.debut = Math.floorMod(Integer.parseInt(m.group(1)), 24);
        if ((m = FINIS.matcher(n)).find()) l.fin = Math.floorMod(Integer.parseInt(m.group(1)), 24);
        if (dit(n, "toute la journee", "jour et nuit", "nuit et jour", "sans arret", "24h", "24 h sur 24")) { l.debut = 0; l.fin = 0; }
        if ((dit(n, "expedition", "expeditions", "absent", "partir", "pars", "parti", "voyage", "dehors", "loin") || n.contains(" jours max"))
                && (m = JOURS.matcher(n)).find()) {
            String j = m.group(1);
            l.jours = j.matches("\\d+") ? Integer.parseInt(j) : NOMBRES.getOrDefault(j, -1);
        }
        String sansChiffresDeTemps = HORAIRES.matcher(n).replaceAll(" ");
        sansChiffresDeTemps = COMMENCE.matcher(sansChiffresDeTemps).replaceAll(" ");
        sansChiffresDeTemps = FINIS.matcher(sansChiffresDeTemps).replaceAll(" ");
        sansChiffresDeTemps = JOURS.matcher(sansChiffresDeTemps).replaceAll(" ");

        // Les ordres simples. L'ordre des tests compte : « arrête de me suivre » contient « suivre ».
        if (dit(n, "arrete de me suivre", "arrete de suivre", "arretez de me suivre", "ne me suis plus", "ne me suivez plus", "lache moi",
                "laisse moi tranquille", "cesse de me suivre", "plus besoin de me suivre", "arrete de me coller"))
            l.intentions.add(Intention.ARRETER_SUIVRE);
        else if (dit(n, "suis moi", "suivez moi", "suis nous", "suit moi", "viens avec moi", "venez avec moi", "accompagne moi", "avec moi",
                "suivre", "viens", "viens ici", "par ici", "en route") || n.startsWith(" suis ")) // « je suis… » n'est pas un ordre
            l.intentions.add(Intention.SUIVRE);
        if (dit(n, "rentre", "rentrez", "rentrer", "reviens", "revenez", "revenir", "retourne au poste", "retourne au bureau", "retourne a la base",
                "retourne a la maison", "va au poste", "va au bureau", "rentre au poste", "a la maison", "au bercail", "fais demi tour", "demi tour"))
            l.intentions.add(Intention.RENTRER);
        if (dit(n, "au travail", "travaille", "travailler", "retourne travailler", "reprends le travail", "reprend le travail", "reprends ton travail",
                "au boulot", "bosse", "va bosser", "retourne bosser", "remets toi au travail", "a l ouvrage", "va travailler", "reprends"))
            l.intentions.add(Intention.TRAVAIL);
        // « Les mineurs, tous à la mine ! », « au bois ! » : en tête, après une pause ou un mot d'élan ; mais « je suis à la
        // mine » ou « ils sont partis à la mine » ne sont pas des ordres.
        if (A_LA_MINE.matcher(OrdresLongs.plat(texte)).find()) l.intentions.add(Intention.TRAVAIL);
        if (!l.intentions.contains(Intention.ARRETER_SUIVRE) && dit(n, "attends", "attend", "attendez", "reste ici", "reste la", "restez ici",
                "ne bouge pas", "ne bouge plus", "bouge pas", "stop", "halte", "arrete toi", "arretez vous", "patiente", "pause", "reste"))
            l.intentions.add(Intention.ATTENDRE);
        if (dit(n, "ton sac", "tes affaires", "ton inventaire", "inventaire", "sac", "ce que tu as", "qu as tu", "qu est ce que tu as",
                "ton equipement", "montre moi ton sac", "montre moi tes affaires"))
            l.intentions.add(Intention.SAC);
        if (dit(n, "renvoye", "renvoyee", "renvoie", "je te renvoie", "vire", "viree", "je te vire", "licencie", "licenciee", "je te licencie",
                "degage", "va t en", "tu es libre", "je te libere", "plus besoin de toi", "je n ai plus besoin de toi"))
            l.intentions.add(Intention.RENVOYER);
        if (dit(n, "oui", "ouais", "ouai", "confirme", "je confirme", "exactement", "c est ca", "absolument", "oui chef"))
            l.intentions.add(Intention.OUI);
        if (dit(n, "non", "annule", "laisse tomber", "pas du tout", "surtout pas", "nan"))
            l.intentions.add(Intention.NON);
        if (dit(n, "ca va", "comment vas tu", "comment tu vas", "tu vas bien", "vas tu bien", "comment te sens tu", "la forme", "en forme",
                "ton etat", "tu as faim", "es tu fatigue", "tu es fatigue", "tu es fatiguee", "comment ca va", "comment allez vous"))
            l.intentions.add(Intention.ETAT);
        if (dit(n, "ou en es tu", "ou en es", "tu en es ou", "ou t en es", "que fais tu", "tu fais quoi", "qu est ce que tu fais", "quoi de neuf",
                "des nouvelles", "tes nouvelles", "ta mission", "tes missions", "ton travail", "rapport", "ou es tu", "t es ou", "ou tu es",
                "ca avance", "avancement", "combien"))
            l.intentions.add(Intention.AVANCEMENT);
        if (dit(n, "pourquoi", "tu ne travailles pas", "tu travailles pas", "qu est ce qui ne va pas", "qu est ce qui se passe", "un probleme",
                "quel probleme", "tu fais rien", "tu ne fais rien"))
            l.intentions.add(Intention.POURQUOI);
        if (dit(n, "aide", "que sais tu faire", "quels ordres", "qu est ce que tu comprends", "que comprends tu", "comment te parler"))
            l.intentions.add(Intention.AIDE);
        if (dit(n, "bonjour", "salut", "coucou", "bonsoir", "hey", "yo", "hello", "bien le bonjour", "bonne nuit", "a demain", "au revoir", "a plus",
                "bon courage", "bonne journee", "bonne soiree", "a tout a l heure", "a toute"))
            l.intentions.add(Intention.SALUT);
        if (dit(n, "merci", "bravo", "bien joue", "beau travail", "bon travail", "excellent", "parfait"))
            l.intentions.add(Intention.MERCI);
        l.ajouter = dit(n, "aussi", "en plus", "ajoute", "egalement", "de plus", "apres ca", "apres cela", "ensuite aussi");

        // Les missions : phrase par phrase, chaque objet cité avec sa quantité et son mode (« au bois » est un lieu, pas une mission).
        lireMissions(sansChiffresDeTemps.replace(" au bois ", " "), metier, l);
        // Une mission comprise le remet au travail : « escorte-moi » ou « viens avec 10 fers » ne veulent pas dire « suis-moi ».
        if (!l.missions.isEmpty()) l.intentions.remove(Intention.SUIVRE);
        repertoire(texte, metier, l);
        l.mortel = Obeissance.mortel(texte);
        return l;
    }

    /**
     * Les actions du répertoire que la phrase demande (« garde à vous », « fais le cochon », « protège Raoul »). Elles
     * l'emportent sur ce que leurs mots auraient voulu dire d'autre : « donne-moi ton fer » n'est pas une mission de fer,
     * « suis Raoul » n'est pas « suis-moi ».
     */
    static void repertoire(String texte, String metier, Lecture l) {
        String n = normaliser(texte);
        for (Repertoire.Trouve t : Repertoire.lire(texte, metier)) {
            l.faire.add(new Faire(t.e().id(), "", 0, 0, t.fin()));
            if (t.fin()) continue;
            // « Danse avec moi » : « avec moi » n'y veut pas dire « suis-moi » (mais « viens avec moi et chante », si).
            if (dit(n, "avec moi") && !dit(n, "suis moi", "suivez moi", "suis nous", "viens avec moi", "venez avec moi", "accompagne moi", "viens", "en route"))
                l.intentions.remove(Intention.SUIVRE);
            for (String x : t.e().remplace()) {
                switch (x) {
                    case "missions" -> { l.missions.clear(); l.horsMetier.clear(); }
                    case "missions_sans_nombre" -> {
                        String[] mots = normaliser(texte).strip().split(" ");
                        boolean nombre = false;
                        for (int i = 0; i < mots.length && !nombre; i++) nombre = !mots[i].equals("un") && !mots[i].equals("une") && nombre(mots, i) != null;
                        if (!nombre) { l.missions.clear(); l.horsMetier.clear(); }
                    }
                    default -> {
                        try { l.intentions.remove(Intention.valueOf(x.toUpperCase(java.util.Locale.ROOT))); }
                        catch (IllegalArgumentException ignore) {}
                    }
                }
            }
        }
    }

    static final Set<String> STOCK = Set.of("stock", "stocks", "garde", "gardes", "garder", "maintiens", "maintenir", "maintient", "toujours",
            "permanence", "reserve", "conserve", "conserver", "minimum", "constamment");
    static final Set<String> QUANTITE = Set.of("ramene", "ramener", "rapporte", "rapporter", "apporte", "apporter", "mine", "miner", "extrais",
            "coupe", "couper", "abats", "abattre", "recolte", "recolter", "va chercher", "cherche", "chercher", "trouve", "trouver", "faut",
            "besoin", "veux", "voudrais", "donne", "fournis", "livre", "livrer", "fais", "prends", "prendre");

    static void lireMissions(String n, String metier, Lecture l) {
        // Les phrases : « et », « puis », « ensuite », « plus » (mais pas le « et » de « soixante et onze »).
        n = n.replaceAll(" (vingt|trente|quarante|cinquante|soixante) et (un|une|onze) ", " $1 $2 ");
        String[] phrases = n.replace(" puis ", " | ").replace(" ensuite ", " | ").replace(" et ", " | ").replace(" plus ", " | ").split("\\|");
        String modeCourant = null;
        for (String p : phrases) {
            String ph = " " + p.trim() + " ";
            String[] mots = ph.trim().split(" ");
            String mode = null;
            for (String w : mots) {
                if (STOCK.contains(w)) mode = "stock";
                else if (QUANTITE.contains(w) && mode == null) mode = "quantite";
            }
            if (ph.contains(" en stock ") || ph.contains(" de stock ") || ph.contains(" au depot ") && ph.contains(" toujours ")) mode = "stock";
            if (mode != null) modeCourant = mode;
            lirePhrase(mots, metier, modeCourant, l);
        }
    }

    /** Les objets d'une phrase, avec le nombre qui les précède (ou qui les suit : « fer x10 »). */
    static void lirePhrase(String[] mots, String metier, String mode, Lecture l) {
        Integer enAttente = null;
        int i = 0, pris = -1, prisLong = 0; // un nombre déjà donné à l'objet qui le précède
        while (i < mots.length) {
            if (i == pris) { i += prisLong; continue; }
            // Un nombre (« 10 », « dix », « vingt-deux », « quatre-vingts », « une pile de », « deux piles »)…
            int[] nb = nombre(mots, i);
            if (nb != null) {
                enAttente = nb[0];
                i += nb[1];
                continue;
            }
            // … puis l'objet : on cherche d'abord dans son métier, puis dans les autres (pour lui dire que ce n'est pas son travail).
            String[] trouve = objet(mots, i, metier);
            if (trouve != null) {
                String metierObjet = trouve[0], id = trouve[1];
                int longueur = Integer.parseInt(trouve[2]);
                // Sans nombre devant : celui qui le suit dans la phrase, avant tout autre objet (« fer x20 », « acacia, s'il te plaît : 20 »).
                Integer apres = null;
                for (int k = i + longueur; enAttente == null && k < mots.length && objet(mots, k, metier) == null; k++) {
                    int[] nb2 = mots[k].equals("un") || mots[k].equals("une") ? null : nombre(mots, k);
                    if (nb2 != null) { apres = nb2[0]; pris = k; prisLong = nb2[1]; break; }
                }
                Integer combien = enAttente != null ? enAttente : apres;
                if (metierObjet.equals(metier)) {
                    String md = mode != null ? mode : combien != null ? "quantite" : "stock";
                    if (metier.equals("guerrier")) md = "stock"; // garder, ronde, escorter : sans quantité
                    int nombre = combien != null && combien > 0 ? Math.min(combien, 100_000) : md.equals("stock") ? 64 : 32;
                    if (l.missions.stream().noneMatch(x -> x.id().equals(id))) l.missions.add(new Mission(id, md, nombre));
                } else l.horsMetier.putIfAbsent(metierObjet, id);
                enAttente = null;
                i += longueur;
                continue;
            }
            i++;
        }
    }

    /** Le nombre qui commence au mot i : {valeur, nombre de mots}, ou null. */
    static int[] nombre(String[] mots, int i) {
        String w = mots[i];
        if (w.matches("x?\\d{1,6}")) {
            int v = Integer.parseInt(w.replace("x", ""));
            if (i + 1 < mots.length && PAQUETS.containsKey(mots[i + 1])) return new int[]{v * PAQUETS.get(mots[i + 1]), 2};
            if (i + 2 < mots.length && mots[i + 1].equals("demi") && PAQUETS.containsKey(mots[i + 2])) return new int[]{v * PAQUETS.get(mots[i + 2]) / 2, 3};
            return new int[]{v, 1};
        }
        if (PAQUETS.containsKey(w)) return new int[]{PAQUETS.get(w), 1};
        if ((w.equals("une") || w.equals("un")) && i + 1 < mots.length) {
            if (PAQUETS.containsKey(mots[i + 1])) return new int[]{PAQUETS.get(mots[i + 1]), 2};
            if (mots[i + 1].equals("demi") && i + 2 < mots.length && PAQUETS.containsKey(mots[i + 2])) return new int[]{PAQUETS.get(mots[i + 2]) / 2, 3};
        }
        if (w.equals("quelques") || w.equals("quelque")) return new int[]{8, 1};
        if (w.equals("beaucoup") || w.equals("plein")) return new int[]{64, 1};
        if (!NOMBRES.containsKey(w) || w.equals("zero")) return null;
        // Nombres en lettres composés : « vingt deux », « soixante dix sept », « quatre vingt dix », « deux cent cinquante ».
        int total = 0, courant = 0, j = i;
        while (j < mots.length && NOMBRES.containsKey(mots[j]) && !mots[j].equals("zero")) {
            int v = NOMBRES.get(mots[j]);
            if (v == 100 || v == 1000) { courant = Math.max(1, courant) * v; if (v == 1000) { total += courant; courant = 0; } }
            else if (v == 20 && courant > 0 && courant < 10) courant *= 20; // quatre-vingt
            else courant += v;
            j++;
            if (j < mots.length && mots[j].equals("et") && j + 1 < mots.length && NOMBRES.containsKey(mots[j + 1])) j++; // vingt et un
        }
        total += courant;
        // « un », « une » seuls : un article qui compte pour 1 (« ramène un diamant »).
        if (j < mots.length && PAQUETS.containsKey(mots[j])) return new int[]{total * PAQUETS.get(mots[j]), j - i + 1};
        return new int[]{total, j - i};
    }

    /** L'objet nommé à partir du mot i : {métier, identifiant, nombre de mots}, ou null. Le métier demandé passe en premier. */
    static String[] objet(String[] mots, int i, String metier) {
        List<String> ordre = new ArrayList<>();
        if (NOMS.containsKey(metier)) ordre.add(metier);
        for (String m : NOMS.keySet()) if (!ordre.contains(m)) ordre.add(m);
        String[] meilleur = null;
        int meilleureLongueur = 0;
        for (String m : ordre)
            for (var e : NOMS.get(m).entrySet())
                for (String nom : e.getValue()) {
                    String[] parts = nom.split(" ");
                    if (parts.length <= meilleureLongueur || i + parts.length > mots.length) continue;
                    boolean ok = true;
                    for (int k = 0; k < parts.length && ok; k++) ok = parts[k].equals(mots[i + k]);
                    if (!ok) continue;
                    // « or » n'est un objet qu'après un nombre ou « de l' », « d' » (sinon c'est la conjonction).
                    if (nom.equals("or") && !(i > 0 && (mots[i - 1].matches("\\d+|d|l|de|un|une") || NOMBRES.containsKey(mots[i - 1])))) continue;
                    meilleur = new String[]{m, e.getKey(), String.valueOf(parts.length)};
                    meilleureLongueur = parts.length;
                }
        if (meilleur == null) return null;
        // Les mots du bois (« bûches », « bois ») suivis d'une essence : c'est l'essence qui compte (« des bûches de bouleau »).
        if (meilleur[1].equals("tout") && MOTS_DU_BOIS.contains(mots[i])) {
            int k = i + Integer.parseInt(meilleur[2]);
            while (k < mots.length && (mots[k].equals("de") || mots[k].equals("d") || mots[k].equals("du") || mots[k].equals("la") || mots[k].equals("l"))) k++;
            if (k < mots.length) {
                String[] essence = objet(mots, k, "bucheron");
                if (essence != null && essence[0].equals("bucheron") && !essence[1].equals("tout"))
                    return new String[]{"bucheron", essence[1], String.valueOf(k - i + Integer.parseInt(essence[2]))};
            }
        }
        return meilleur;
    }

    // --- À qui le chef parle ---------------------------------------------------------------------------

    /**
     * Ce qu'une phrase dite devant plusieurs ouvriers dit de ses destinataires (indices dans la liste des prénoms) :
     * <ul>
     * <li>{@code appeles} : interpellés par leur prénom (« Raoul, viens », « Viens ici, Raoul », « Merci Raoul ») ;</li>
     * <li>{@code cites} : nommés sans être interpellés (« va aider Raoul », « Raoul est où ? ») ;</li>
     * <li>{@code exclus} : « tout le monde sauf Raoul » ;</li>
     * <li>{@code tous} : « les gars », « tout le monde », « vous tous »… ;</li>
     * <li>{@code metiersTous} / {@code metiersUn} : « les mineurs, rentrez » / « le guerrier, viens » ;</li>
     * <li>{@code pluriel} : un verbe au pluriel ou « vous » (un indice, pas une certitude) ;</li>
     * <li>{@code demande} : on leur demande quelque chose (« va… », « il faut… », « quelqu'un peut… ») ;</li>
     * <li>{@code deuxiemePersonne} : on s'adresse à quelqu'un (« tu », « toi », « vous », « ton »…) ;</li>
     * <li>{@code question} : une question (« ? », « où », « comment »…).</li>
     * </ul>
     * Une phrase sans rien de tout ça (« c'est vraiment un chômeur ce mec ») parle d'eux, ou à quelqu'un d'autre.
     */
    record Adresse(Set<Integer> appeles, Set<Integer> cites, Set<Integer> exclus, boolean tous, Set<String> metiersTous, Set<String> metiersUn,
                   boolean pluriel, boolean demande, boolean deuxiemePersonne, boolean question) {
        /** La phrase désigne elle-même ses destinataires. */
        boolean explicite() { return tous || !appeles.isEmpty() || !metiersTous.isEmpty() || !metiersUn.isEmpty(); }
    }

    static final List<String> GROUPES = List.of("les gars", "tout le monde", "vous tous", "vous toutes", "vous deux", "vous trois", "vous quatre",
            "tous les deux", "tous les trois", "toutes les deux", "les amis", "mes amis", "les copains", "mes copains", "les hommes", "mes hommes",
            "les filles", "mesdames", "messieurs", "l equipe", "mon equipe", "les ouvriers", "mes ouvriers", "les compagnons", "mes compagnons",
            "compagnons", "camarades", "les camarades", "les enfants", "la compagnie", "tout le groupe", "tous ensemble", "venez tous", "allez tous",
            "a tous", "vous autres", "la troupe", "les braves", "mes braves", "mes gaillards", "les gaillards", "tous sauf", "toutes sauf",
            "tous a part", "tous excepte", "tous hormis", "restez tous", "venez tous", "rentrez tous");
    /** Les métiers par leur nom : singulier, pluriel ; les mots marqués d'une étoile ne comptent qu'avec un article (« garde » est aussi un verbe). */
    static final Map<String, List<String[]>> NOMS_METIERS = Map.of(
            "mineur", List.of(new String[]{"mineur", "mineurs"}, new String[]{"mineuse", "mineuses"}),
            "guerisseur", List.of(new String[]{"guerisseur", "guerisseurs"}, new String[]{"guerisseuse", "guerisseuses"}, new String[]{"medecin", "medecins"},
                    new String[]{"soigneur", "soigneurs"}, new String[]{"soigneuse", "soigneuses"}),
            "bucheron", List.of(new String[]{"bucheron", "bucherons"}, new String[]{"bucheronne", "bucheronnes"}),
            "fermier", List.of(new String[]{"fermier", "fermiers"}, new String[]{"fermiere", "fermieres"}, new String[]{"paysan", "paysans"}, new String[]{"paysanne", "paysannes"}),
            "eleveur", List.of(new String[]{"eleveur", "eleveurs"}, new String[]{"eleveuse", "eleveuses"}, new String[]{"berger", "bergers"}, new String[]{"bergere", "bergeres"},
                    new String[]{"vacher", "vachers"}, new String[]{"porcher", "porchers"}),
            "pecheur", List.of(new String[]{"pecheur", "pecheurs"}, new String[]{"pecheuse", "pecheuses"}),
            "chasseur", List.of(new String[]{"chasseur", "chasseurs"}, new String[]{"chasseuse", "chasseuses"}, new String[]{"chasseresse", "chasseresses"},
                    new String[]{"veneur", "veneurs"}),
            "guerrier", List.of(new String[]{"guerrier", "guerriers"}, new String[]{"guerriere", "guerrieres"}, new String[]{"soldat", "soldats"},
                    new String[]{"*garde", "*gardes"}, new String[]{"*garde du corps", "*gardes du corps"}));
    static final Set<String> ARTICLES = Set.of("le", "la", "les", "l", "mon", "ma", "mes", "notre", "nos", "un", "une");
    // Pas « bien » : « il bosse bien, Bertrand » parle de lui. « joué », « travail », « nuit » : « bien joué Raoul », « bon travail Raoul », « bonne nuit Raoul ».
    static final Set<String> INTERJECTIONS = Set.of("he", "eh", "oh", "ho", "ohe", "hey", "dis", "dites", "allez", "alors", "bon", "ecoute",
            "ecoutez", "merci", "bravo", "salut", "bonjour", "bonsoir", "coucou", "yo", "oui", "non", "ok", "d", "accord", "tiens", "voyons", "toi",
            "joue", "travail", "nuit", "courage", "revoir", "bonne", "demain");
    /** Après un prénom en tête de phrase, ces mots en font le sujet (« Raoul est où ? ») et non celui qu'on appelle. */
    static final Set<String> TROISIEME_PERSONNE = Set.of("est", "etait", "sera", "a", "avait", "aura", "n", "s", "il", "elle", "y", "lui");
    static final Set<String> SAUF = Set.of("sauf", "excepte", "hormis", "hors");
    static final Set<String> DEMANDES = Set.of("va", "vas", "allez", "viens", "venez", "fais", "faites", "prends", "prenez", "apporte", "apportez",
            "ramene", "ramenez", "rapporte", "rapportez", "rentre", "rentrez", "suis", "suivez", "attends", "attendez", "reste", "restez", "protege",
            "protegez", "defends", "defendez", "garde", "gardez", "coupe", "coupez", "mine", "minez", "cherche", "cherchez", "recolte", "recoltez",
            "travaille", "travaillez", "arrete", "arretez", "aide", "aidez", "montre", "montrez", "donne", "donnez", "occupe", "occupez", "surveille",
            "surveillez", "patrouille", "patrouillez", "escorte", "escortez", "descends", "descendez", "monte", "montez", "creuse", "creusez", "abats",
            "abattez", "retourne", "retournez", "reviens", "revenez", "approche", "approchez", "bouge", "bougez", "pars", "partez", "file", "filez");
    /** Des mots qui interpellent (« salut Raoul », « merci les gars », « hé ! »). */
    static final Set<String> APPELS = Set.of("salut", "bonjour", "bonsoir", "coucou", "merci", "bravo", "he", "eh", "ohe", "hey", "yo", "ho", "oh",
            "allez", "dis", "dites", "ecoute", "ecoutez");
    static final Set<String> DETERMINANTS = Set.of("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "ma", "ta", "sa", "mon", "ton", "son",
            "mes", "tes", "ses", "ce", "cet", "cette", "ces", "au", "aux", "notre", "votre", "leur", "leurs");
    static final Set<String> SUJETS = Set.of("je", "j", "il", "elle", "on", "ils", "elles", "ca", "cela", "nous", "qu", "que", "qui", "se", "s", "me", "m");
    static final Set<String> DEUXIEME_PERSONNE = Set.of("tu", "toi", "te", "t", "ton", "ta", "tes", "tien", "tienne", "tiens", "vous", "votre", "vos");
    static final Set<String> INTERROGATIFS = Set.of("ou", "quand", "comment", "pourquoi", "combien", "quel", "quelle", "quels", "quelles", "qui", "quoi");
    static final List<String> DEMANDES_FORMULES = List.of("peux tu", "pouvez vous", "tu peux", "vous pouvez", "tu pourrais", "vous pourriez",
            "il faut", "il faudrait", "il me faut", "j ai besoin", "quelqu un", "qui peut", "qui veut", "je veux que", "je voudrais que");

    /** Les mots et la ponctuation (pour les virgules qui isolent un prénom), en minuscules, sans accents. */
    static List<String> jetons(String s) {
        String n = Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("œ", "oe").replace("æ", "ae");
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("[a-z0-9]+|[,.;:!?]").matcher(n);
        while (m.find()) r.add(m.group());
        return r;
    }

    static boolean ponctuation(String w) { return w.length() == 1 && ",.;:!?".contains(w); }

    /** Deux mots presque pareils (Whisper écrit parfois « Enguerran » ou « Bérangère ») : un écart par 5 lettres, deux à partir de 9. */
    static boolean proche(String mot, String nom) {
        if (mot.equals(nom)) return true;
        if (nom.length() < 5 || Math.abs(mot.length() - nom.length()) > 1 || mot.charAt(0) != nom.charAt(0)) return false;
        int[] avant = new int[nom.length() + 1], ici = new int[nom.length() + 1];
        for (int j = 0; j <= nom.length(); j++) avant[j] = j;
        for (int i = 1; i <= mot.length(); i++) {
            ici[0] = i;
            for (int j = 1; j <= nom.length(); j++)
                ici[j] = Math.min(Math.min(ici[j - 1] + 1, avant[j] + 1), avant[j - 1] + (mot.charAt(i - 1) == nom.charAt(j - 1) ? 0 : 1));
            int[] t = avant; avant = ici; ici = t;
        }
        return avant[nom.length()] <= (nom.length() >= 9 ? 2 : 1);
    }

    /** Des mots qui font d'un groupe un complément (« viens voir mes ouvriers », « avec les gars ») et non un appel. */
    static final Set<String> OBJETS = Set.of("voir", "aider", "appeler", "montrer", "presenter", "trouver", "chercher", "avec", "pour", "de", "des", "du",
            "sur", "chez", "entre", "par", "contre", "que", "qu", "aux", "vers", "sans");

    /** Un mot de groupe (« les gars », « tout le monde »…) qui n'est pas un complément (« viens voir mes ouvriers »). */
    static boolean groupeAppele(List<String> j) {
        for (String g : GROUPES) {
            List<String> mots = List.of(g.split(" "));
            for (int k = 0; k + mots.size() <= j.size(); k++)
                if (j.subList(k, k + mots.size()).equals(mots) && (k == 0 || !OBJETS.contains(j.get(k - 1)))) return true;
        }
        return false;
    }

    /** Un mot de groupe (« les gars », « tout le monde »…) entre deux pauses : en début ou en fin de phrase, ou entre virgules. */
    static boolean groupeEnAppel(List<String> j) {
        for (String g : GROUPES) {
            List<String> mots = List.of(g.split(" "));
            for (int k = 0; k + mots.size() <= j.size(); k++) {
                if (!j.subList(k, k + mots.size()).equals(mots)) continue;
                int g0 = k - 1;
                while (g0 >= 0 && INTERJECTIONS.contains(j.get(g0))) g0--;
                boolean gauche = g0 < 0 || ponctuation(j.get(g0)), droite = k + mots.size() >= j.size() || ponctuation(j.get(k + mots.size()));
                if (gauche && droite) return true;
            }
        }
        return false;
    }

    /** Les mots sans « garde à vous » (son « vous » ne désigne personne). */
    static List<String> sansGardeAVous(List<String> j) {
        List<String> r = new ArrayList<>(j);
        for (int k = 0; k + 2 < r.size(); k++)
            if (r.get(k).equals("garde") && r.get(k + 1).equals("a") && r.get(k + 2).equals("vous")) { r.remove(k + 2); r.remove(k + 1); }
        return r;
    }

    /** Un nom (prénom ou métier) trouvé dans la phrase, des jetons {@code debut} à {@code fin} (exclu). */
    record Mention(int qui, String metier, boolean pluriel, int debut, int fin) {}

    /** À qui s'adresse cette phrase, dite devant ces ouvriers (leurs prénoms et leurs métiers, dans le même ordre). */
    static Adresse adresse(String texte, List<String> prenoms, List<String> metiers) {
        List<String> j = jetons(texte);
        List<Mention> mentions = new ArrayList<>();
        for (int q = 0; q < prenoms.size(); q++) {
            List<String> nom = jetons(prenoms.get(q)).stream().filter(w -> !ponctuation(w)).toList();
            if (nom.isEmpty()) continue;
            for (int k = 0; k + nom.size() <= j.size(); k++) {
                boolean ok = true;
                for (int x = 0; x < nom.size() && ok; x++) ok = nom.size() == 1 ? proche(j.get(k), nom.get(0)) : j.get(k + x).equals(nom.get(x));
                if (ok) mentions.add(new Mention(q, null, false, k, k + nom.size()));
            }
        }
        for (var e : NOMS_METIERS.entrySet()) {
            if (!metiers.contains(e.getKey())) continue;
            for (String[] formes : e.getValue())
                for (int f = 0; f < 2; f++) {
                    boolean article = formes[f].startsWith("*");
                    List<String> mots = List.of(formes[f].replace("*", "").split(" "));
                    for (int k = 0; k + mots.size() <= j.size(); k++) {
                        if (!j.subList(k, k + mots.size()).equals(mots)) continue;
                        int debut = k > 0 && ARTICLES.contains(j.get(k - 1)) ? k - 1 : k;
                        if (article && debut == k) continue;
                        if (f == 0 && k + 1 < j.size() && j.get(k + 1).equals("du") && formes[0].equals("*garde")) continue; // « garde du corps » : ci-dessus
                        mentions.add(new Mention(-1, e.getKey(), f == 1 || debut < k && j.get(debut).matches("les|mes|nos"), debut, k + mots.size()));
                    }
                }
        }
        mentions.sort(java.util.Comparator.comparingInt(Mention::debut));

        Set<Integer> appeles = new TreeSet<>(), cites = new TreeSet<>(), exclus = new TreeSet<>();
        Set<String> metiersTous = new TreeSet<>(), metiersUn = new TreeSet<>();
        boolean[] vocatif = new boolean[mentions.size()];
        for (int i = 0; i < mentions.size(); i++) {
            Mention m = mentions.get(i);
            // À gauche : un début de phrase, une ponctuation ou une interjection (« hé », « merci »), ou une liste de noms appelés (« Bertrand et Raoul »).
            int g = m.debut() - 1;
            while (g >= 0 && INTERJECTIONS.contains(j.get(g))) g--;
            boolean gauche = g < 0 || ponctuation(j.get(g)) || g < m.debut() - 1 && INTERJECTIONS.contains(j.get(g + 1));
            if (!gauche && i > 0 && vocatif[i - 1] && (j.get(g).equals("et") || j.get(g).equals(",")) && mentions.get(i - 1).fin() == g) gauche = true;
            // À droite : pas un verbe dont il serait le sujet (« Raoul est où ? »).
            boolean droite = m.fin() >= j.size() || !TROISIEME_PERSONNE.contains(j.get(m.fin()));
            vocatif[i] = gauche && droite;
            // « sauf Raoul », « à part Raoul » : juste avant (ou dans une liste qui suit « sauf »).
            boolean sauf = false;
            for (int k = Math.max(0, m.debut() - 4); k < m.debut(); k++)
                if (SAUF.contains(j.get(k)) || j.get(k).equals("part") && k > 0 && j.get(k - 1).equals("a")) sauf = true;
            if (m.qui() >= 0) {
                if (sauf) exclus.add(m.qui());
                else if (vocatif[i]) appeles.add(m.qui());
                else cites.add(m.qui());
            } else if (vocatif[i] && !sauf) (m.pluriel() ? metiersTous : metiersUn).add(m.metier());
        }
        cites.removeAll(appeles);
        String n = normaliser(texte);
        boolean tous = groupeAppele(j);
        List<String> jv = sansGardeAVous(j);
        boolean pluriel = jv.stream().anyMatch(w -> w.equals("vous") || w.equals("tous") || w.equals("toutes")
                || w.length() > 3 && w.endsWith("ez") && !Set.of("chez", "assez", "allez").contains(w));
        // Un verbe à l'impératif, pas conjugué avec un sujet (« je mine », « il garde la base » ne sont pas des ordres).
        boolean demande = DEMANDES_FORMULES.stream().anyMatch(x -> dit(n, x));
        for (int k = 0; k < j.size() && !demande; k++) {
            int s = k - 1;
            if (s >= 0 && (j.get(s).equals("ne") || j.get(s).equals("n"))) s--; // « ne bouge pas » est un ordre, « je ne mine pas » non
            // Après un sujet (« je mine »), ou un article (« à la mine », « la garde »), ce n'est pas un ordre.
            demande = DEMANDES.contains(j.get(k)) && (s < 0 || !SUJETS.contains(j.get(s)) && !DETERMINANTS.contains(j.get(s)));
        }
        boolean deuxieme = jv.stream().anyMatch(DEUXIEME_PERSONNE::contains);
        boolean question = j.contains("?") || !j.isEmpty() && INTERROGATIFS.contains(j.get(0)) || dit(n, "est ce que", "est ce qu");
        // Un prénom ou « les gars » dans une phrase qui ne s'adresse à personne (« il est lent, Raoul ») : on parle d'eux, pas à eux.
        long noms = mentions.stream().mapToInt(m -> m.fin() - m.debut()).sum() + j.stream().filter(w -> ponctuation(w) || w.equals("et")).count();
        boolean adressee = deuxieme || demande || question || j.stream().anyMatch(APPELS::contains) || !lire(texte, "").intentions.isEmpty() || noms >= j.size()
                || Repertoire.commande(texte);
        // « Les gars », « tout le monde » placés comme un appel (« On bouge, tout le monde ! ») restent un appel ; sujets d'un verbe
        // (« les gars sont partis »), ils parlent d'eux.
        if (!adressee) { cites.addAll(appeles); appeles.clear(); metiersTous.clear(); metiersUn.clear(); tous = tous && groupeEnAppel(j); }
        return new Adresse(appeles, cites, exclus, tous, metiersTous, metiersUn, pluriel, demande, deuxieme, question);
    }
}

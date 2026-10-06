package fr.lacolonie.pnj;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        boolean vide() { return intentions.isEmpty() && missions.isEmpty() && debut < 0 && fin < 0 && jours < 0 && horsMetier.isEmpty(); }
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

        Map<String, List<String>> guerrier = new LinkedHashMap<>();
        guerrier.put("escorter", List.of("escorte moi", "escorte", "escorter", "protege moi", "garde du corps"));
        guerrier.put("ronde", List.of("fais la ronde", "la ronde", "ronde", "patrouille", "patrouiller", "patrouilles"));
        guerrier.put("garder", List.of("garde le poste", "garde le bureau", "garde la base", "monte la garde", "surveille le poste", "surveille", "garder le poste"));
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
        n = n.replace('œ', 'o').replace('æ', 'a');
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

    /** Lit un message adressé à un ouvrier de ce métier. */
    static Lecture lire(String texte, String metier) {
        Lecture l = new Lecture();
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
        if (!l.intentions.contains(Intention.ARRETER_SUIVRE) && dit(n, "attends", "attend", "attendez", "reste ici", "reste la", "restez ici",
                "ne bouge pas", "ne bouge plus", "bouge pas", "stop", "halte", "arrete toi", "arretez vous", "patiente", "pause", "reste"))
            l.intentions.add(Intention.ATTENDRE);
        if (dit(n, "ton sac", "tes affaires", "ton inventaire", "inventaire", "sac", "ce que tu as", "qu as tu", "qu est ce que tu as",
                "ton equipement", "montre moi"))
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
        if (dit(n, "bonjour", "salut", "coucou", "bonsoir", "hey", "yo", "hello", "bien le bonjour"))
            l.intentions.add(Intention.SALUT);
        if (dit(n, "merci", "bravo", "bien joue", "beau travail", "bon travail", "excellent", "parfait"))
            l.intentions.add(Intention.MERCI);
        l.ajouter = dit(n, "aussi", "en plus", "ajoute", "egalement", "de plus", "apres ca", "apres cela", "ensuite aussi");

        // Les missions : phrase par phrase, chaque objet cité avec sa quantité et son mode.
        lireMissions(sansChiffresDeTemps, metier, l);
        // Une mission comprise le remet au travail : « escorte-moi » ou « viens avec 10 fers » ne veulent pas dire « suis-moi ».
        if (!l.missions.isEmpty()) l.intentions.remove(Intention.SUIVRE);
        return l;
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
}

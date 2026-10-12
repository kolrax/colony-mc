package fr.lacolonie.pnj;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les ordres longs (cahier 8.3) : des règles qu'un ouvrier garde en tête, un déclencheur et ce qu'il doit faire alors
 * (« s'il pleut, rentre », « quand tu as fini, va aider Raoul », « si tu vois un zombie, préviens-moi », « le matin tu
 * mines »). La conséquence reste une phrase, relue par {@link Ordres#lire} au moment où la règle se déclenche. Aussi :
 * « tous les jours » (une mission quotidienne) et « oublie tes ordres ». Aucune dépendance au jeu (testé seul).
 */
final class OrdresLongs {
    private OrdresLongs() {}

    /** Il en garde 8 au plus (la plus ancienne part). */
    static final int MAX = 8;

    /** Une règle : {@code quand} (FINI, PLUIE, ORAGE, NUIT, JOUR, MATIN, APRES_MIDI, SOIR, VOIT:zombie…) et la phrase à suivre. */
    static final class Regle {
        final String quand, suite;
        /** La condition était vraie à la dernière vérification (on ne déclenche qu'au moment où elle le devient). */
        boolean actif;
        /** Il s'est mis à l'abri ou en attente à cause d'elle : quand elle cesse, il reprend le travail. */
        boolean mene;
        /** Moment (temps du jeu) où elle s'est déclenchée, et du dernier avis donné (non sauvegardés). */
        long depuis, dernierAvis = Long.MIN_VALUE / 2;

        Regle(String quand, String suite) { this.quand = quand; this.suite = suite; }

        boolean voit() { return quand.startsWith("VOIT:"); }

        String vu() { return voit() ? quand.substring(5) : ""; }

        /** Une condition qui a une fin (la pluie cesse, le matin passe) : pas « quand tu as fini », pas « si tu vois ». */
        boolean finit() { return !quand.equals("FINI") && !voit(); }

        /** Une période de l'emploi du temps. */
        boolean periode() { return quand.equals("MATIN") || quand.equals("APRES_MIDI") || quand.equals("SOIR"); }

        /** « préviens-moi », « avertis-moi », « dis-le-moi »… */
        boolean previens() {
            return PREVENIR.matcher(plat(suite)).find();
        }

        @Override
        public String toString() { return quand + " → " + suite; }
    }

    /** Ce qu'une phrase contient d'ordres longs, et ce qui reste à lire comme d'habitude. */
    static final class Lu {
        final List<Regle> regles = new ArrayList<>();
        String reste;
        boolean annuler, quotidien;
    }

    static final Pattern PREVENIR = Pattern.compile("\\b(?:previens|prevenir|previens moi|avertis|avertir|alerte|alerter|dis le moi|dis moi|signale|signaler|fais moi savoir|fais le moi savoir|appelle moi|crie)\\b");

    /** Les déclencheurs, à chercher dans le texte « à plat » (minuscules, sans accents, mots séparés). */
    static final List<Object[]> DECLENCHEURS = new ArrayList<>();

    static void d(String quand, String regex) { DECLENCHEURS.add(new Object[]{quand, Pattern.compile("\\b" + regex.replace(" ", " +") + "\\b")}); }

    static {
        String fini = "(?:fini|finie|finis|termine|terminee|termines|acheve)";
        String travail = "(?: (?:ta|tes|ton|cette|ce|la|les|ces) (?:mission|missions|travail|tache|taches|boulot|commande|commandes|ouvrage))?";
        d("FINI", "(?:quand|lorsque|lorsqu|des que|des qu|une fois que|sitot que|apres que) (?:tu |t )?(?:as|auras|aura|a) (?:tout |bien )?" + fini + travail);
        d("FINI", "une fois" + travail + " " + fini);
        d("FINI", "une fois " + fini + travail);
        d("FINI", "(?:apres|des la fin de|a la fin de) (?:ta mission|tes missions|ton travail|ta tache|ta commande|ton ouvrage)");
        d("FINI", "(?:quand|lorsque|des que) (?:c est|ce sera|ca sera) " + fini);
        d("ORAGE", "(?:s il y a|quand il y a|lorsqu il y a|des qu il y a|en cas d|par temps d|pendant l|pendant les|pendant un|s il fait) (?:un |de l |l )?orages?");
        d("ORAGE", "(?:s il|quand il|lorsqu il|des qu il) (?:tonne|fait de l orage)");
        d("PLUIE", "(?:s il|quand il|lorsqu il|des qu il) (?:pleut|se met a pleuvoir|commence a pleuvoir)");
        d("PLUIE", "(?:en cas de|sous la|par temps de|pendant la|s il y a de la|quand il y a de la|a la) pluie");
        d("NUIT", "(?:quand|lorsqu|lorsque|s|des qu|des que) (?:il fait nuit|la nuit tombe|la nuit vient|la nuit arrive)");
        d("NUIT", "(?:a la tombee de la nuit|a la nuit tombee|la nuit venue|des la nuit|pendant la nuit|de nuit)");
        d("NUIT", "(?<!toute )(?<!bonne )(?<!cette )la nuit");
        d("JOUR", "(?:quand|lorsqu|lorsque|s|des qu|des que) (?:il fait jour|le jour se leve|le soleil se leve)");
        d("JOUR", "(?:au lever du jour|au lever du soleil|a l aube|des l aube|au point du jour|pendant le jour|de jour)");
        d("JOUR", "(?<!tout )le jour(?! et)");
        d("MATIN", "(?:le|au|chaque|les|ce|tous les) matins?");
        d("MATIN", "(?:dans la|en|pendant la|toute la) matinee");
        d("APRES_MIDI", "(?:l |cet |chaque |les |dans l |en |pendant l |tous les |tout l )?apres midis?");
        d("SOIR", "(?:le|au|chaque|les|ce|tous les) soirs?");
        d("SOIR", "(?:dans la|en|pendant la) soiree");
        d("VOIT", "(?:si|quand|lorsque|lorsqu|des que|des qu|chaque fois que|a chaque fois que|au cas ou) (?:tu |t )?(?:vois|verras|voyais|apercois|apercevras|croises|croiseras|rencontres|reperes|tombes sur|as vu|trouves) (?:un |une |des |le |la |les |du |l |d |encore )?(quelqu un|\\w+)");
    }

    /** Ce qu'il peut voir : le mot du chef → l'espèce (pour le jeu). Le nom tel qu'il le dira est dans {@link #NOMS_VUS}. */
    static final Map<String, String> VUS = Map.ofEntries(
            Map.entry("zombie", "zombie"), Map.entry("zombies", "zombie"), Map.entry("zombi", "zombie"), Map.entry("zombis", "zombie"),
            Map.entry("mort", "zombie"), Map.entry("morts", "zombie"),
            Map.entry("squelette", "squelette"), Map.entry("squelettes", "squelette"),
            Map.entry("creeper", "creeper"), Map.entry("creepers", "creeper"),
            Map.entry("araignee", "araignee"), Map.entry("araignees", "araignee"),
            Map.entry("sorciere", "sorciere"), Map.entry("sorcieres", "sorciere"),
            Map.entry("enderman", "enderman"), Map.entry("endermen", "enderman"), Map.entry("endermans", "enderman"),
            Map.entry("pillard", "pillard"), Map.entry("pillards", "pillard"), Map.entry("brigand", "pillard"), Map.entry("brigands", "pillard"),
            Map.entry("monstre", "monstre"), Map.entry("monstres", "monstre"), Map.entry("creature", "monstre"), Map.entry("creatures", "monstre"),
            Map.entry("ennemi", "monstre"), Map.entry("ennemis", "monstre"),
            Map.entry("joueur", "etranger"), Map.entry("joueurs", "etranger"), Map.entry("etranger", "etranger"), Map.entry("etrangers", "etranger"),
            Map.entry("inconnu", "etranger"), Map.entry("inconnus", "etranger"), Map.entry("intrus", "etranger"), Map.entry("voleur", "etranger"),
            Map.entry("voleurs", "etranger"), Map.entry("visiteur", "etranger"), Map.entry("visiteurs", "etranger"), Map.entry("quelqu un", "etranger"));
    static final Map<String, String> NOMS_VUS = Map.of("zombie", "un zombie", "squelette", "un squelette", "creeper", "un creeper",
            "araignee", "une araignée", "sorciere", "une sorcière", "enderman", "un enderman", "pillard", "un pillard",
            "monstre", "un monstre", "etranger", "un étranger");

    static final Pattern QUOTIDIEN = Pattern.compile("\\b(?:tous les jours|chaque jour|chaque journee|quotidiennement|journellement|par jour)\\b");
    static final Pattern ANNULER = Pattern.compile(("\\b(?:(?:oublie|oubliez|annule|annulez|efface|effacez|retire|laisse tomber) (?:toutes |tous )?(?:tes|les|mes|vos|ces) (?:ordres|consignes|regles|instructions)"
            + "|plus de (?:regles|consignes)|oublie tout ce que je t ai dit)\\b").replace(" ", " +"));

    /**
     * Le texte à plat : minuscules, sans accents, chaque signe autre qu'une lettre, un chiffre ou une ponctuation de
     * phrase remplacé par une espace. Il garde la longueur du texte (une position y vaut une position dans l'original).
     */
    static String plat(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String n = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
            char x = n.isEmpty() ? ' ' : n.charAt(0);
            if (x == 'œ') x = 'o';
            if (x == 'æ') x = 'a';
            b.append(x >= 'a' && x <= 'z' || x >= '0' && x <= '9' || ".,;:!?".indexOf(x) >= 0 ? x : ' ');
        }
        return b.toString();
    }

    private record Trouve(String quand, int debut, int fin) {}

    /** Lit les règles d'un message, la mission quotidienne et « oublie tes ordres » ; {@code reste} : le texte sans les règles. */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        String p = plat(texte);
        Matcher a = ANNULER.matcher(p);
        StringBuilder sans = new StringBuilder(texte);
        StringBuilder platSans = new StringBuilder(p);
        while (a.find()) {
            lu.annuler = true;
            for (int i = a.start(); i < a.end(); i++) { sans.setCharAt(i, ' '); platSans.setCharAt(i, ' '); }
        }
        texte = sans.toString();
        p = platSans.toString();
        lu.quotidien = QUOTIDIEN.matcher(p).find();

        // Phrase par phrase : les déclencheurs, et la conséquence de chacun (après lui, ou avant s'il finit la phrase).
        List<String> reste = new ArrayList<>();
        int s0 = 0;
        for (int i = 0; i <= p.length(); i++) {
            if (i < p.length() && ".;!?\n".indexOf(p.charAt(i)) < 0) continue;
            phrase(texte, p, s0, i, lu, reste);
            s0 = i + 1;
        }
        lu.reste = String.join(". ", reste);
        return lu;
    }

    private static void phrase(String texte, String p, int s0, int s1, Lu lu, List<String> reste) {
        if (s1 <= s0) return;
        String ph = p.substring(s0, s1);
        List<Trouve> t = new ArrayList<>();
        for (Object[] d : DECLENCHEURS) {
            Matcher m = ((Pattern) d[1]).matcher(ph);
            while (m.find()) {
                String quand = (String) d[0];
                if (quand.equals("VOIT")) {
                    String vu = VUS.get(m.group(1));
                    if (vu == null) continue; // « si tu vois Raoul » : pas une créature à guetter
                    quand = "VOIT:" + vu;
                }
                t.add(new Trouve(quand, s0 + m.start(), s0 + m.end()));
            }
        }
        // Les plus longs d'abord à début égal ; un déclencheur pris dans un autre ne compte pas.
        t.sort(Comparator.comparingInt(Trouve::debut).thenComparing(x -> -(x.fin() - x.debut())));
        List<Trouve> gardes = new ArrayList<>();
        for (Trouve x : t) if (gardes.isEmpty() || x.debut() >= gardes.get(gardes.size() - 1).fin()) gardes.add(x);
        if (gardes.isEmpty()) {
            if (!texte.substring(s0, s1).isBlank()) reste.add(texte.substring(s0, s1).strip());
            return;
        }
        boolean avantPris = false;
        for (int k = 0; k < gardes.size(); k++) {
            Trouve x = gardes.get(k);
            int fin = k + 1 < gardes.size() ? gardes.get(k + 1).debut() : s1;
            String suite = nettoyer(texte.substring(x.fin(), fin));
            if (suite.isEmpty() && k == 0) { suite = nettoyer(texte.substring(s0, x.debut())); avantPris = true; }
            if (!suite.isEmpty()) lu.regles.add(new Regle(x.quand(), suite));
        }
        String avant = nettoyer(texte.substring(s0, gardes.get(0).debut()));
        if (!avantPris && !avant.isEmpty()) reste.add(avant);
    }

    static final Pattern BORDS = Pattern.compile("^[\\s,:;\\-]*(?:(?:alors|et|puis|donc)\\b[\\s,]*)?|[\\s,:;\\-]*(?:\\b(?:et|puis|mais|ou)[\\s,]*)?$", Pattern.CASE_INSENSITIVE);

    /** Sans la ponctuation et les mots de liaison des bords (« , et », « alors »). */
    static String nettoyer(String s) {
        String r = s;
        for (int i = 0; i < 3; i++) r = BORDS.matcher(r).replaceAll("");
        return r.strip();
    }

    /** Ajoute une règle : même déclencheur, elle remplace l'ancienne ; au-delà de {@link #MAX}, la plus ancienne part. */
    static void ajouter(List<Regle> regles, Regle r) {
        regles.removeIf(x -> x.quand.equals(r.quand));
        regles.add(r);
        while (regles.size() > MAX) regles.remove(0);
    }

    /** Le métier qu'un verbe de l'emploi du temps désigne (« tu mines » : mineur, « tu coupes » : bûcheron), ou null. */
    static String metierDuVerbe(String suite) {
        String p = " " + plat(suite).replaceAll("[^a-z0-9]+", " ") + " ";
        if (p.matches(".* (mine|mines|miner|minez|creuse|creuses|creuser|a la mine) .*")) return "mineur";
        if (p.matches(".* (coupe|coupes|couper|abats|abattre|bucheronne|bucheronnes|au bois|a la foret) .*")) return "bucheron";
        if (p.matches(".* (gardes|garde le poste|surveilles|surveille|patrouilles|patrouille|monte la garde|montes la garde|fais la ronde|fais ta ronde) .*")) return "guerrier";
        return null;
    }

    /** Le déclencheur dit par l'ouvrier : « s'il pleut », « quand j'aurai fini », « si je vois un zombie ». */
    static String quand(Regle r) {
        if (r.voit()) return "si je vois " + NOMS_VUS.getOrDefault(r.vu(), "un monstre");
        return switch (r.quand) {
            case "FINI" -> "quand j'aurai fini";
            case "PLUIE" -> "s'il pleut";
            case "ORAGE" -> "s'il y a de l'orage";
            case "NUIT" -> "la nuit";
            case "JOUR" -> "le jour";
            case "MATIN" -> "le matin";
            case "APRES_MIDI" -> "l'après-midi";
            case "SOIR" -> "le soir";
            default -> r.quand.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Ce qu'il dit quand la condition cesse (et qu'il reprend le travail). */
    static String fin(Regle r) {
        return switch (r.quand) {
            case "PLUIE" -> "La pluie a cessé";
            case "ORAGE" -> "L'orage est passé";
            case "NUIT" -> "Le jour se lève";
            case "JOUR" -> "La nuit tombe";
            case "MATIN" -> "La matinée est passée";
            case "APRES_MIDI" -> "L'après-midi s'achève";
            case "SOIR" -> "La soirée est finie";
            default -> "C'est passé";
        };
    }

    /** La condition, vue du jeu : l'heure (0 à 23), la pluie, l'orage. FINI et VOIT sont vérifiés par l'ouvrier lui-même. */
    static boolean vrai(String quand, int heure, boolean pluie, boolean orage) {
        return switch (quand) {
            case "PLUIE" -> pluie;
            case "ORAGE" -> orage;
            case "NUIT" -> heure >= 19 || heure < 5;
            case "JOUR" -> heure >= 5 && heure < 19;
            case "MATIN" -> heure >= 6 && heure < 12;
            case "APRES_MIDI" -> heure >= 12 && heure < 18;
            case "SOIR" -> heure >= 18 && heure < 22;
            default -> false;
        };
    }
}

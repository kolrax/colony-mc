package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce que le chef a dit, une fois pour toutes, de ceux qui le frappent (lot 9, cahier 14.6) : « attaque quiconque me
 * frappe » (le guerrier riposte), « reste tranquille, ne frappe personne » (il s'interpose seulement, comme sans ordre),
 * « ne fais jamais rien à Nielscutt » (il ne réagit pas à lui). Lu sans IA, comme {@link OrdresLongs}. Aucune dépendance
 * au jeu (testé seul). La réaction elle-même : {@link Defense#chefFrappe}.
 */
final class GardeChef {
    private GardeChef() {}

    enum Posture { DEFAUT, ATTAQUE, TRANQUILLE }

    /** Ce qu'une phrase en dit, et le texte sans ces ordres-là. */
    static final class Lu {
        Posture posture;
        final List<String> epargner = new ArrayList<>();
        String reste;

        boolean rien() { return posture == null && epargner.isEmpty(); }
    }

    private static Pattern p(String regex) { return Pattern.compile("\\b(?:" + regex.replace(" ", " +") + ")\\b"); }

    /** Quelqu'un s'en prend au chef : « me frappe », « m'attaque », « lève la main sur moi »… */
    static final Pattern AGRESSION = p("(?:me|m) (?:frappe|frappent|frappera|frapperait|frappait|touche|touchent|touchera|tape|tapent|tapera|attaque|attaquent|attaquera|cogne|cognent|blesse|blessent|agresse|agressent|menace|menacent)"
            + "|(?:leve|levent|levera|porte|portent|portera) la main sur moi|s en (?:prend|prennent|prendra) a moi");
    /** Ce qu'il doit lui faire alors : frapper. */
    static final Pattern RIPOSTE = p("frappes|cognes|tapes|tues|defends|attaque|attaques|attaquer|attaque le|attaque la|frappe|frapper|frappe le|frappe la|cogne|cogner|tape|taper|tue|tuer|defends moi|defend moi|defends|defendre|protege moi|riposte|riposter|venge moi|tombe lui dessus|tombe dessus|fais lui payer|corrige le|charge le|embroche le|abats le|chasse le");
    /** Seulement s'interposer, sans frapper : dit avec « s'il me frappe », ou seul (« reste tranquille »). */
    static final Pattern DOUX = p("interpose toi|t interposer|t interposes|mets toi entre|mets toi devant|ne (?:le |la |les )?frappe pas|ne (?:le |la |les )?touche pas|ne riposte pas|ne (?:le |la |les )?frappe plus|ne (?:le |la |les |lui )?(?:attaque|tape|cogne|tue|blesse) pas|ne fais rien|sans (?:le |la )?frapper|ne fais rien de plus|calme toi|reste calme|sois calme");
    static final Pattern TRANQUILLE = p("(?:reste|restes|sois|soyez|restez) (?:plus |bien |donc )?(?:tranquille|tranquilles|calme|calmes|pacifique|paisible)"
            + "|ne frappe (?:plus |jamais )?personne|ne (?:touche|touches) (?:plus |jamais )?a personne|ne leve (?:plus |jamais )?la main sur personne"
            + "|ne te bats (?:pas|plus|avec personne|jamais)|ne riposte (?:pas|plus|jamais)|pas de bagarre|plus de bagarre|(?:contente toi de|seulement|juste) t interposer|interpose toi (?:seulement|juste)");
    /** « Ne fais jamais rien à Nielscutt », « ne touche jamais à Nielscutt », « laisse Nielscutt tranquille »… */
    static final Pattern EPARGNE = p("(?:ne (?:lui )?fais|ne faites|ne fait|ne fais) (?:jamais |plus |surtout |pas |rien )*(?:rien|aucun mal|pas de mal|de mal|mal) a (\\w+)"
            + "|ne (?:frappe|touche|attaque|leve la main sur|touche a|touche jamais a|frappe jamais|attaque jamais|touche plus a|frappe plus|frappe pas|attaque pas) (?:jamais |plus |pas )?(?:a )?(\\w+)"
            + "|laisse (\\w+) (?:tranquille|en paix)|epargne (\\w+)|epargne toujours (\\w+)");
    /** Pas des noms. */
    static final java.util.Set<String> PAS_UN_NOM = java.util.Set.copyOf(List.of("le", "la", "les", "l", "un", "une", "des", "du", "de", "personne", "rien", "a", "au",
            "aux", "moi", "toi", "lui", "eux", "ca", "cela", "ce", "cet", "cette", "ces", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "nos", "vos", "leur",
            "jamais", "plus", "pas", "tout", "tous", "quiconque", "aucun", "aucune", "on", "y", "en", "si", "quand"));
    /** « Arrête », « ça suffit », « laisse-le » : le guerrier qui frappe un homme sur son ordre cesse. */
    static final Pattern CESSER = p("arrete|arretez|arrete toi|stop|halte|assez|ca suffit|laisse le|laisse la|laisse tomber|lache le|lache la|cesse|cessez|recule|baisse ton arme|range ton arme|pose ton arme");

    /** Lit une phrase du chef. */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        String p = OrdresLongs.plat(texte);
        StringBuilder sans = new StringBuilder(texte);
        int s0 = 0;
        for (int i = 0; i <= p.length(); i++) {
            if (i < p.length() && ".;!?\n".indexOf(p.charAt(i)) < 0) continue;
            if (i > s0 && !(i < p.length() && p.charAt(i) == '?')) phrase(texte, p, s0, i, lu, sans);
            s0 = i + 1;
        }
        lu.reste = sans.toString().replaceAll("\\s{2,}", " ").strip();
        return lu;
    }

    private static void effacer(StringBuilder sans, int a, int b) { for (int i = a; i < b; i++) sans.setCharAt(i, ' '); }

    private static void phrase(String texte, String p, int s0, int s1, Lu lu, StringBuilder sans) {
        String ph = p.substring(s0, s1);
        // Les noms épargnés (on garde le pseudo tel qu'écrit, avec ses chiffres et ses « _ »).
        Matcher e = EPARGNE.matcher(ph);
        while (e.find()) {
            int g = 1;
            while (g < e.groupCount() && e.group(g) == null) g++;
            if (e.group(g) == null || PAS_UN_NOM.contains(e.group(g))) continue;
            int a = s0 + e.start(g), b = a;
            while (b < texte.length() && (Character.isLetterOrDigit(texte.charAt(b)) || texte.charAt(b) == '_' || texte.charAt(b) == '-')) b++;
            String nom = texte.substring(a, b);
            if (lu.epargner.stream().noneMatch(nom::equalsIgnoreCase)) lu.epargner.add(nom);
            effacer(sans, s0 + e.start(), Math.max(b, s0 + e.end()));
        }
        Matcher ag = AGRESSION.matcher(ph);
        if (ag.find()) {
            // « Si on me frappe, attaque-le » / « interpose-toi sans frapper ».
            String autour = ph.substring(0, ag.start()) + " " + ph.substring(ag.end());
            Matcher doux = DOUX.matcher(autour), tranq = TRANQUILLE.matcher(autour);
            if (doux.find() || tranq.find()) lu.posture = Posture.TRANQUILLE;
            else if (RIPOSTE.matcher(autour).find()) lu.posture = Posture.ATTAQUE;
            else return;
            effacer(sans, s0, s1);
            return;
        }
        Matcher t = TRANQUILLE.matcher(ph);
        if (t.find()) {
            lu.posture = Posture.TRANQUILLE;
            effacer(sans, s0 + t.start(), s0 + t.end());
        }
    }

    /** Il épargne ce pseudo (sans tenir compte des majuscules). */
    static boolean epargne(String noms, String pseudo) {
        if (noms == null || noms.isEmpty() || pseudo == null) return false;
        for (String n : noms.split(",")) if (n.strip().equalsIgnoreCase(pseudo)) return true;
        return false;
    }

    /** La liste gardée en mémoire (« Nielscutt,Raoul »), avec les nouveaux noms. */
    static String ajouter(String noms, List<String> nouveaux) {
        List<String> l = new ArrayList<>();
        if (noms != null && !noms.isEmpty()) for (String n : noms.split(",")) if (!n.isBlank()) l.add(n.strip());
        for (String n : nouveaux) if (l.stream().noneMatch(n::equalsIgnoreCase)) l.add(n);
        while (l.size() > 16) l.remove(0);
        return String.join(",", l);
    }

    static void reprendre(Ordres.Lecture vers, Ordres.Lecture seul) {} // ANCIEN (stub)

    static boolean cesse(Ordres.Lecture l) { return cesser(l.texte); } // ANCIEN (stub)

    /** « Arrête », « ça suffit »… */
    static boolean cesser(String texte) { return CESSER.matcher(OrdresLongs.plat(texte)).find(); }

    /** La posture gardée en mémoire (DEFAUT si rien). */
    static Posture posture(String nom) {
        try { return nom == null || nom.isEmpty() ? Posture.DEFAUT : Posture.valueOf(nom); } catch (IllegalArgumentException x) { return Posture.DEFAUT; }
    }
}

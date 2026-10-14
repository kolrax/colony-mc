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
            "leurs", "notre", "votre", "nous", "vous", "il", "ils", "elle", "elles", "ceux", "celui", "celle", "celles", "qui", "que", "autre", "autres", "gens", "monde",
            "trop", "ici", "bas", "chef", "jamais", "plus", "pas", "tout", "tous", "quiconque", "aucun", "aucune", "on", "y", "en", "si", "quand"));
    /** « Arrête », « ça suffit », « calme-toi », « range ton épée » : le guerrier qui frappe un homme sur son ordre cesse. */
    static final Pattern CESSER = p("arrete|arretez|arrete toi|stop|halte|assez|ca suffit|laisse le|laisse la|laisse tomber|lache le|lache la|cesse|cessez|recule"
            + "|calme toi|calmez vous|du calme|(?:baisse|range|pose|rengaine) (?:ton|ta|tes) (?:arme|armes|epee|lame|fer|hache|glaive)|rengaine");

    /** « Si quelqu'un me frappe » : la condition, dite d'un homme (au plus 4 mots entre « si » et le coup). */
    static final Pattern SI = Pattern.compile("\\b(?:si|s|quand|lorsque|des que|chaque fois que|au cas ou|pour peu que) +(?:[a-z0-9]+ +){0,4}?(?:" + AGRESSION.pattern() + ")");
    /** « Quiconque me frappe », « ceux qui me frappent » : celui qui frappe, nommé dans l'ordre même. */
    static final Pattern QUI = Pattern.compile("\\b(?:quiconque|qui) +(?:" + AGRESSION.pattern() + ")");
    /**
     * Une négation ou un évitement, n'importe où dans la phrase : jamais d'attaque alors (dans le doute, il ne frappe pas).
     * « N'importe qui » n'en est pas une.
     */
    static final Pattern NEGATION = p("ne|n(?! importe)|jamais|pas|sans|evite|evites|eviter|evitez|interdit|interdis|refuse|surtout pas|point");
    /** Entre deux ordres d'une même phrase : « va miner, et si on m'attaque… ». */
    static final Pattern COUPURE = Pattern.compile(",|\\b(?:et|puis|mais|ensuite)\\b");

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
        lu.reste = lu.rien() ? texte : sans.toString().replaceAll("\\s{2,}", " ").strip();
        return lu;
    }

    private static void effacer(StringBuilder sans, int a, int b) { for (int i = a; i < b; i++) sans.setCharAt(i, ' '); }

    /** Le début de l'ordre qui finit en {@code i} : juste après la dernière virgule (ou « et », « puis »…) avant lui. */
    private static int debutOrdre(String ph, int i) {
        int d = 0;
        Matcher c = COUPURE.matcher(ph.substring(0, i));
        while (c.find()) d = c.end();
        return d;
    }

    /** La fin de l'ordre qui commence en {@code i} : la prochaine virgule (ou « et »…), sinon la fin de la phrase. */
    private static int finOrdre(String ph, int i) {
        Matcher c = COUPURE.matcher(ph);
        return c.find(i) ? c.start() : ph.length();
    }

    /** La phrase parle d'une bête ou d'un monstre (« si un zombie m'attaque ») : ce n'est pas l'ordre contre les hommes. */
    static boolean bete(String ph) { return Chasse.monstres(ph) || Chasse.dite(ph) != null; }

    private static void phrase(String texte, String p, int s0, int s1, Lu lu, StringBuilder sans) {
        StringBuilder vu = new StringBuilder(p.substring(s0, s1));
        String ph = vu.toString();
        // Les noms épargnés (on garde le pseudo tel qu'écrit, avec ses chiffres et ses « _ »).
        Matcher e = EPARGNE.matcher(ph);
        boolean epargne = false;
        while (e.find()) {
            int g = 1;
            while (g < e.groupCount() && e.group(g) == null) g++;
            if (e.group(g) == null || PAS_UN_NOM.contains(e.group(g))) continue;
            int a = s0 + e.start(g), b = a;
            while (b < texte.length() && (Character.isLetterOrDigit(texte.charAt(b)) || texte.charAt(b) == '_' || texte.charAt(b) == '-')) b++;
            String nom = texte.substring(a, b);
            if (lu.epargner.stream().noneMatch(nom::equalsIgnoreCase)) lu.epargner.add(nom);
            effacer(sans, s0 + e.start(), Math.max(b, s0 + e.end()));
            effacer(vu, e.start(), Math.min(ph.length(), Math.max(b - s0, e.end())));
            epargne = true;
        }
        ph = vu.toString();
        // « Si on me frappe, … » ou « … quiconque me frappe » : l'ordre de garde, et ce qu'il doit faire alors.
        Matcher si = SI.matcher(ph), qui = QUI.matcher(ph);
        int debut, fin;
        String consigne;
        if (si.find()) {
            debut = si.start();
            fin = ph.length();
            consigne = AGRESSION.matcher(ph.substring(si.end())).replaceAll(" ");
            if (!consigne.matches("[^a-z]*")) consigne = consigne + " " + ph.substring(debutOrdre(ph, debut), debut); // « défends-moi si on m'attaque »
            else { consigne = ph.substring(debutOrdre(ph, debut), debut); debut = debutOrdre(ph, debut); }
        } else if (qui.find()) {
            debut = debutOrdre(ph, qui.start());
            fin = finOrdre(ph, qui.end());
            consigne = ph.substring(debut, qui.start());
        } else {
            // « Reste tranquille », « ne frappe personne » : seul, sans parler de coups reçus.
            Matcher t = TRANQUILLE.matcher(ph);
            if (t.find()) {
                lu.posture = Posture.TRANQUILLE;
                effacer(sans, s0 + t.start(), s0 + t.end());
            }
            return;
        }
        if (bete(ph)) return;
        // « Ne frappe jamais Nielscutt, même s'il me frappe » : il l'épargne, rien de plus ; la condition part avec.
        if (epargne) { effacer(sans, s0 + debut, s0 + fin); return; }
        boolean riposte = RIPOSTE.matcher(consigne).find(), doux = DOUX.matcher(consigne).find() || TRANQUILLE.matcher(consigne).find();
        // Une négation, un évitement, n'importe où : jamais l'attaque (« n'attaque pas quiconque me frappe », « sans le blesser »).
        if (NEGATION.matcher(ph).find()) {
            if (!riposte && !doux) return; // « si on me frappe, ne me préviens pas » : ce n'est pas un ordre de garde
            lu.posture = Posture.TRANQUILLE;
        } else if (doux) lu.posture = Posture.TRANQUILLE;
        else if (riposte) lu.posture = Posture.ATTAQUE;
        else return; // « préviens-moi si quelqu'un me frappe » : autre chose
        effacer(sans, s0 + debut, s0 + fin);
    }

    /** Avec l'IA : ce que l'interpréteur a lu de la garde, repris tel quel (l'IA ne connaît pas ces ordres). */
    static void reprendre(Ordres.Lecture vers, Ordres.Lecture seul) {
        if (seul.posture != null) vers.posture = seul.posture;
        for (String n : seul.epargner) if (vers.epargner.stream().noneMatch(n::equalsIgnoreCase)) vers.epargner.add(n);
    }

    /** Il cesse de frapper un homme : « arrête », « calme-toi », « reste tranquille », « oublie tes ordres »… */
    static boolean cesse(Ordres.Lecture l) { return l.posture == Posture.TRANQUILLE || l.annulerRegles || cesser(l.texte); }

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

    /** « Arrête », « ça suffit »… */
    static boolean cesser(String texte) { return CESSER.matcher(OrdresLongs.plat(texte)).find(); }

    /** La posture gardée en mémoire (DEFAUT si rien). */
    static Posture posture(String nom) {
        try { return nom == null || nom.isEmpty() ? Posture.DEFAUT : Posture.valueOf(nom); } catch (IllegalArgumentException x) { return Posture.DEFAUT; }
    }
}

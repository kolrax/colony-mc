package fr.lacolonie.pnj;

import fr.lacolonie.pnj.GardeChef.Posture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le dépôt du poste (lot 9, cahier 6.4) : un autre joueur peut s'y servir, mais les guerriers du chef le défendent, sauf
 * s'il l'a autorisé et l'a dit à sa colonie. Ce que le chef en dit, lu sans IA comme {@link GardeChef} : « attaque ceux
 * qui volent le dépôt » (le guerrier frappe le voleur), « défends le dépôt sans frapper » (il s'interpose seulement),
 * « Nielscutt peut se servir au dépôt », « Nielscutt n'a plus le droit ». Aucune dépendance au jeu (testé seul). La
 * réaction elle-même : {@link Defense#depotOuvert}.
 */
final class GardeDepot {
    private GardeDepot() {}

    /** Ce qu'une phrase en dit, et le texte sans ces ordres-là. */
    static final class Lu {
        /** La garde du dépôt : ATTAQUE (le guerrier frappe le voleur), TRANQUILLE (il s'interpose seulement) ; null si rien n'est dit. */
        Posture posture;
        final List<String> permis = new ArrayList<>(), retires = new ArrayList<>();
        String reste;

        boolean rien() { return posture == null && permis.isEmpty() && retires.isEmpty(); }
    }

    private static Pattern p(String regex) { return Pattern.compile("\\b(?:" + regex.replace(" ", " +") + ")\\b"); }

    /** Le dépôt du poste et ses coffres. */
    static final Pattern DEPOT = p("depot|depots|reserve|reserves|coffres? du (?:poste|depot)|coffres? de la colonie|nos coffres|mes coffres|notre stock");
    /** Ce qu'on y fait sans droit (avec le dépôt nommé dans la phrase). */
    static final Pattern VOL = p("vole|volent|volera|voleront|volerait|voler|vol|vols|pille|pillent|pillera|piller|chaparde|chapardent|chaparder|derobe|derobent|derober"
            + "|se sert|se servent|se servira|se serviront|se servir|pioche|piochent|piocher|fouille|fouillent|fouiller|puise|puisent|prend|prennent|prendra|prendre|touche|touchent|touchera|toucher");
    /** Les voleurs, nommés sans le dépôt : « attaque les voleurs », « quiconque pille ». */
    static final Pattern VOLEURS = p("voleur|voleurs|voleuse|voleuses|pillard|pillards|chapardeur|chapardeurs|(?:qui|quiconque) (?:vole|volent|pille|pillent|chaparde|chapardent|derobe|derobent)");
    /** Défendre le dépôt. */
    static final Pattern DEFENDRE = p("defends|defend|defendre|defendez|protege|proteger|protegez");
    /** Le frapper : l'ordre de force. */
    static final Pattern FORCE = p("attaque|attaques|attaquer|attaquez|frappe|frappes|frapper|frappez|cogne|cogner|tape|taper|tue|tuer|tuez|riposte|riposter"
            + "|abats|abattre|embroche|corrige|chasse les a coups|par la force|de force|a coups d epee|a coups|l epee a la main|fais leur payer|fais lui payer|tombe leur dessus|tombe lui dessus");
    /** Seulement s'interposer, avertir. */
    static final Pattern DOUX = p("interpose toi|t interposer|t interposes|mets toi entre|mets toi devant|avertis|averti|avertir|previens les|previens le|mets les en garde|mets le en garde");
    /** Une négation ou un évitement : jamais l'attaque (dans le doute, il ne frappe pas). « Sans pitié » n'en est pas une. */
    static final Pattern NEGATION = GardeChef.NEGATION;
    static final Pattern PAS_NEGATION = p("sans pitie|sans merci|sans hesiter|sans hesitation|sans attendre|sans sommation|sans prevenir|sans faiblir");
    /** « Si quelqu'un vole le dépôt, frappe-le » : la condition, jusqu'à la fin de la phrase. */
    static final Pattern SI = p("si|s|quand|lorsque|des que|chaque fois que|au cas ou");

    /** « Nielscutt peut se servir au dépôt », « autorise Nielscutt à prendre au dépôt », « laisse Nielscutt se servir »… */
    static final Pattern PERMIS = p("(\\w+) (?:peut|pourra|a le droit de|aura le droit de|a la permission de|a ma permission pour|est autorise a|est autorisee a)"
            + " (?:desormais |maintenant |dorenavant |aussi |bien |toujours |librement )?(?:se servir|prendre|piocher|puiser|ouvrir|toucher|utiliser)"
            + "|autorise (\\w+) a (?:se servir|prendre|piocher|puiser|ouvrir|toucher|utiliser)|autorise (\\w+) (?:au|a utiliser le|dans le) depot"
            + "|(?:laisse|laissez) (\\w+) (?:se servir|prendre|piocher|puiser)"
            + "|(?:donne|accorde) (?:a )?(\\w+) (?:le droit|la permission|l acces)|(?:donne|accorde) (?:le droit|la permission|l acces) (?:au depot )?a (\\w+)");
    /** « Nielscutt n'a plus le droit », « Nielscutt ne peut plus se servir », « interdis le dépôt à Nielscutt »… */
    static final Pattern RETIRE = p("(\\w+) n a plus (?:le droit|la permission|ma permission|acces|l acces)"
            + "|(\\w+) ne (?:peut|pourra|doit) plus (?:se servir|prendre|piocher|puiser|ouvrir|toucher|utiliser|venir)"
            + "|(\\w+) n est plus autorisee?|n autorise plus (\\w+)|(?:ne laisse|ne laissez) plus (\\w+) (?:se servir|prendre|piocher|puiser)"
            + "|(?:interdis|interdit|defends|retire) (?:le depot |l acces au depot |l acces |le droit |la permission )?a (\\w+)");
    /** Il faut parler du dépôt, ou d'un droit, pour que ce soit ce sujet-là (« Jean peut prendre sa pause » n'en est pas). */
    static final Pattern SUJET = p("se servir|droit|permission|acces|autorise|autorisee");
    static final java.util.Set<String> PAS_UN_NOM = java.util.Set.copyOf(List.of("chacun", "chacune", "quelqu", "nul", "n", "ne", "il", "elle", "on", "tu", "je"));

    /** Lit une phrase du chef. */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        // Les pseudos gardent leurs « _ » (« Niels_cutt42 » est un seul mot).
        StringBuilder q = new StringBuilder(OrdresLongs.plat(texte));
        for (int i = 0; i < q.length(); i++) if (texte.charAt(i) == '_') q.setCharAt(i, '_');
        String p = q.toString();
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

    /** Les pseudos donnés par {@code motif} (pris tels qu'écrits), effacés de la phrase ; vrai s'il y en a un. */
    private static boolean noms(Pattern motif, String texte, int s0, StringBuilder vu, StringBuilder sans, List<String> dans, boolean depot) {
        String ph = vu.toString();
        Matcher e = motif.matcher(ph);
        boolean trouve = false;
        while (e.find()) {
            int g = 1;
            while (g < e.groupCount() && e.group(g) == null) g++;
            String n = e.group(g);
            if (n == null || GardeChef.PAS_UN_NOM.contains(n) || PAS_UN_NOM.contains(n) || !depot && !SUJET.matcher(e.group()).find()) continue;
            int a = s0 + e.start(g), b = a;
            while (b < texte.length() && (Character.isLetterOrDigit(texte.charAt(b)) || texte.charAt(b) == '_' || texte.charAt(b) == '-')) b++;
            String nom = texte.substring(a, b);
            if (dans.stream().noneMatch(nom::equalsIgnoreCase)) dans.add(nom);
            // Tout l'ordre part (« … dans le dépôt » compris), jusqu'à la virgule ou au « et » suivant.
            int fin = finOrdre(ph, Math.max(b - s0, e.end()));
            effacer(sans, s0 + e.start(), s0 + fin);
            effacer(vu, e.start(), fin);
            trouve = true;
        }
        return trouve;
    }

    private static int debutOrdre(String ph, int i) {
        int d = 0;
        Matcher c = GardeChef.COUPURE.matcher(ph.substring(0, i));
        while (c.find()) d = c.end();
        return d;
    }

    private static int finOrdre(String ph, int i) {
        Matcher c = GardeChef.COUPURE.matcher(ph);
        return c.find(Math.min(i, ph.length())) ? c.start() : ph.length();
    }

    private static void phrase(String texte, String p, int s0, int s1, Lu lu, StringBuilder sans) {
        StringBuilder vu = new StringBuilder(p.substring(s0, s1));
        boolean depot = DEPOT.matcher(vu).find();
        // Qui peut s'y servir, et qui ne le peut plus (le retrait d'abord : « ne laisse plus X se servir »).
        noms(RETIRE, texte, s0, vu, sans, lu.retires, depot);
        noms(PERMIS, texte, s0, vu, sans, lu.permis, depot);
        String ph = vu.toString();
        Matcher d = DEPOT.matcher(ph), v = VOLEURS.matcher(ph);
        boolean dit = d.find(), voleurs = v.find();
        boolean contexte = dit && (VOL.matcher(ph).find() || DEFENDRE.matcher(ph).find()) || voleurs;
        if (!contexte || GardeChef.bete(ph)) return;
        Matcher f = FORCE.matcher(ph), doux = DOUX.matcher(ph), def = DEFENDRE.matcher(ph);
        boolean force = f.find(), calme = doux.find(), defend = def.find();
        if (force) lu.posture = NEGATION.matcher(PAS_NEGATION.matcher(ph).replaceAll(" ")).find() ? Posture.TRANQUILLE : Posture.ATTAQUE;
        else if (calme || dit && defend) lu.posture = Posture.TRANQUILLE; // « défends le dépôt » seul : dans le doute, il ne frappe pas
        else return;
        // L'ordre part : de son début (après la dernière virgule avant lui) jusqu'à la fin de la phrase s'il y a une
        // condition (« si on vole au dépôt, frappe-le »), sinon jusqu'à la virgule suivante.
        int debut = ph.length(), fin = 0;
        for (Matcher m : new Matcher[]{d.reset(), v.reset(), f.reset(), doux.reset(), def.reset(), VOL.matcher(ph)})
            while (m.find()) { debut = Math.min(debut, m.start()); fin = Math.max(fin, m.end()); }
        Matcher si = SI.matcher(ph.substring(0, debut + 1));
        int cond = -1;
        while (si.find()) if (si.start() >= debutOrdre(ph, debut)) { cond = si.start(); break; }
        debut = debutOrdre(ph, cond >= 0 ? cond : debut);
        fin = cond >= 0 ? ph.length() : finOrdre(ph, fin);
        effacer(sans, s0 + debut, s0 + fin);
    }

    /** Avec l'IA : ce que l'interpréteur a lu du dépôt, repris tel quel (l'IA ne connaît pas ces ordres). */
    static void reprendre(Ordres.Lecture vers, Ordres.Lecture seul) {
        if (seul.depot != null) vers.depot = seul.depot;
        for (String n : seul.permis) if (vers.permis.stream().noneMatch(n::equalsIgnoreCase)) vers.permis.add(n);
        for (String n : seul.retires) if (vers.retires.stream().noneMatch(n::equalsIgnoreCase)) vers.retires.add(n);
    }

    /** La liste gardée (« Nielscutt,Raoul ») avec ces noms ajoutés, puis ceux-là retirés. */
    static String changer(String noms, List<String> plus, List<String> moins) {
        String l = GardeChef.ajouter(noms, plus);
        List<String> r = new ArrayList<>();
        for (String n : l.split(",")) if (!n.isBlank() && moins.stream().noneMatch(n::equalsIgnoreCase)) r.add(n);
        return String.join(",", r);
    }
}

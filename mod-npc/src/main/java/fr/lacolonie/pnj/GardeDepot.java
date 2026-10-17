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
            + "|se sert|se servent|se servira|se serviront|se servir|pioche|piochent|piocher|fouille|fouillent|fouiller|puise|puisent"
            // « Prendre », « toucher », seulement dits d'un autre (« va prendre l'épée au dépôt et attaque-le » n'en est pas) :
            + "|(?:qui|quiconque|on|un|ne) (?:\\w+ ){0,2}?(?:prend|prennent|prendra|prendrait|touche|touchent|touchera|ouvre|ouvrent|ouvrira)");
    /** Les voleurs (avec le dépôt dans la même partie de phrase) : « attaque les voleurs du dépôt », « quiconque pille la réserve ». */
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
    /** « Si quelqu'un vole le dépôt, frappe-le » : la condition, avec la partie qui la suit. */
    static final Pattern SI = Pattern.compile(" *(?:si|s|quand|lorsque|des que|chaque fois que|au cas ou|pour peu que)\\b");
    /** « Arrête de frapper… » se lit avec {@link GardeChef#CESSER} ; aussi « plus la peine », « plus besoin ». */
    static final Pattern INUTILE = p("plus la peine|plus besoin|inutile|pas la peine|pas besoin");
    /** Avant un participe (« tu as frappé », « assez frappé ») : ce n'est pas un ordre de frapper. */
    static final Pattern AUXILIAIRE = Pattern.compile("as|ai|a|avez|avons|ont|avais|avait|aviez|avaient|aurais|aurait|auras|aura|eu|ete|deja|assez|bien");
    /** « Sauf Raoul », « excepté Raoul », « hormis Raoul ». */
    static final Pattern SAUF = p("(?:sauf|excepte|exceptee|hormis|a part|a l exception de) (\\w+)");
    /** Sans le dépôt dans la phrase, un permis doit dire « se servir » (« Raoul a la permission de prendre la charrette » n'en est pas un). */
    static final Pattern SERVIR = p("se servir");

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

    /** Le pseudo dont le mot aplati commence en {@code a} (dans le texte), tel qu'écrit : ses bornes dans le texte. */
    static int[] pseudo(String texte, int s0, int a) {
        int b = a;
        // « Jean-Pierre », « test-mock-player » : le trait d'union coupe le mot dans le texte aplati, pas dans le pseudo.
        while (a - 2 >= s0 && texte.charAt(a - 1) == '-' && (Character.isLetterOrDigit(texte.charAt(a - 2)) || texte.charAt(a - 2) == '_')) {
            a -= 2;
            while (a - 1 >= s0 && (Character.isLetterOrDigit(texte.charAt(a - 1)) || texte.charAt(a - 1) == '_')) a--;
        }
        while (b < texte.length() && (Character.isLetterOrDigit(texte.charAt(b)) || texte.charAt(b) == '_' || texte.charAt(b) == '-')) b++;
        return new int[]{a, b};
    }

    static boolean unNom(String n) { return n != null && !GardeChef.PAS_UN_NOM.contains(n) && !PAS_UN_NOM.contains(n); }

    /**
     * Les pseudos donnés par {@code motif} (pris tels qu'écrits), effacés de la phrase. Sans le dépôt dans la phrase, il
     * faut que l'ordre lui-même dise de quoi il s'agit ({@code sujet} : « se servir », « le droit »…).
     */
    private static void noms(Pattern motif, String texte, int s0, StringBuilder vu, StringBuilder sans, List<String> dans, boolean depot, Pattern sujet) {
        String ph = vu.toString();
        Matcher e = motif.matcher(ph);
        while (e.find()) {
            int g = 1;
            while (g < e.groupCount() && e.group(g) == null) g++;
            if (!unNom(e.group(g)) || !depot && !sujet.matcher(e.group()).find()) continue;
            int[] n = pseudo(texte, s0, s0 + e.start(g));
            String nom = texte.substring(n[0], n[1]);
            if (dans.stream().noneMatch(nom::equalsIgnoreCase)) dans.add(nom);
            // Tout l'ordre part (« … dans le dépôt » compris), jusqu'à la virgule ou au « et » suivant.
            int fin = finOrdre(ph, Math.max(n[1] - s0, e.end()));
            effacer(sans, s0 + e.start(), s0 + fin);
            effacer(vu, e.start(), fin);
        }
    }

    private static int finOrdre(String ph, int i) {
        Matcher c = GardeChef.COUPURE.matcher(ph);
        return c.find(Math.min(i, ph.length())) ? c.start() : ph.length();
    }

    /** Un verbe de force dit comme un ordre : pas un participe (« tu as frappé les voleurs »). */
    static boolean force(String u) {
        Matcher f = FORCE.matcher(u);
        while (f.find()) {
            Matcher m = Pattern.compile("(\\w+)\\s*$").matcher(u.substring(0, f.start()));
            if (!m.find() || !AUXILIAIRE.matcher(m.group(1)).matches()) return true;
        }
        return false;
    }

    /**
     * La garde du dépôt, lue partie par partie (entre deux virgules, « et », « puis »…) : il faut le dépôt et ce qu'on y
     * fait (le voler, le défendre) dans la même partie que le verbe de force ; une condition (« si on vole au dépôt, »)
     * va avec la partie qui la suit. Une négation, « arrête », « plus la peine », un « sauf » sans nom : dans le doute, il
     * ne frappe pas. « Sauf Raoul » : Raoul peut se servir.
     */
    private static void phrase(String texte, String p, int s0, int s1, Lu lu, StringBuilder sans) {
        StringBuilder vu = new StringBuilder(p.substring(s0, s1));
        boolean depot = DEPOT.matcher(vu).find();
        // Qui peut s'y servir, et qui ne le peut plus (le retrait d'abord : « ne laisse plus X se servir »).
        noms(RETIRE, texte, s0, vu, sans, lu.retires, depot, SUJET);
        noms(PERMIS, texte, s0, vu, sans, lu.permis, depot, SERVIR);
        String ph = vu.toString();
        List<int[]> parts = new ArrayList<>();
        Matcher c = GardeChef.COUPURE.matcher(ph);
        int a = 0;
        while (c.find()) { parts.add(new int[]{a, c.start()}); a = c.end(); }
        parts.add(new int[]{a, ph.length()});
        for (int k = 0; k + 1 < parts.size(); k++)
            if (SI.matcher(ph.substring(parts.get(k)[0], parts.get(k)[1])).lookingAt()) { parts.get(k)[1] = parts.get(k + 1)[1]; parts.remove(k + 1); }
        Posture vue = null;
        for (int[] x : parts) {
            String u = ph.substring(x[0], x[1]);
            if (!DEPOT.matcher(u).find() || !(VOL.matcher(u).find() || VOLEURS.matcher(u).find() || DEFENDRE.matcher(u).find()) || GardeChef.bete(u)) continue;
            boolean doute = NEGATION.matcher(PAS_NEGATION.matcher(u).replaceAll(" ")).find() || GardeChef.CESSER.matcher(u).find() || INUTILE.matcher(u).find();
            Posture pz;
            if (force(u) && !doute) pz = Posture.ATTAQUE;
            else if (doute && FORCE.matcher(u).find() || DOUX.matcher(u).find() || DEFENDRE.matcher(u).find()) pz = Posture.TRANQUILLE; // « défends le dépôt » seul aussi
            else continue;
            vue = vue == Posture.TRANQUILLE ? vue : pz;
            effacer(sans, s0 + x[0], s0 + x[1]);
        }
        if (vue == null) return;
        // « Frappe les voleurs du dépôt, sauf Raoul » : Raoul peut s'y servir ; « sauf la nuit » : dans le doute, pas de coups.
        Matcher sauf = SAUF.matcher(ph);
        while (sauf.find()) {
            if (unNom(sauf.group(1))) {
                int[] n = pseudo(texte, s0, s0 + sauf.start(1));
                String nom = texte.substring(n[0], n[1]);
                if (lu.permis.stream().noneMatch(nom::equalsIgnoreCase)) lu.permis.add(nom);
            } else vue = Posture.TRANQUILLE;
            effacer(sans, s0 + sauf.start(), s0 + finOrdre(ph, sauf.end()));
        }
        lu.posture = lu.posture == Posture.TRANQUILLE ? lu.posture : vue;
    }

    /**
     * La garde du dépôt après cet ordre : celle qu'il dit, sinon « reste tranquille », « ne frappe personne » (dits pour
     * le chef) la calment aussi ; sinon celle d'avant.
     */
    static Posture garde(Posture avant, Ordres.Lecture l) {
        if (l.depot != null) return l.depot;
        return l.posture == Posture.TRANQUILLE && avant == Posture.ATTAQUE ? Posture.TRANQUILLE : avant;
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

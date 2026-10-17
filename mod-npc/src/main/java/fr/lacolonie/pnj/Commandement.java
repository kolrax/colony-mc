package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lot 14, cahier 6.1 et 6.2 : qui d'autre que le chef peut commander ses ouvriers. Le chef le dit à un seul (« tu peux
 * obéir à Nielscutt », « Nielscutt peut te donner des ordres », « n'obéis plus à Nielscutt ») et toute la colonie le sait
 * ({@code Courrier.commandants}). Ce que dit un autre joueur : y a-t-il un ordre ({@link #ordre}), et les ordres que seul
 * le chef peut donner, retirés ({@link #reserver}). Pur Java (testé par {@code CommandementTest}) ; le jeu est dans
 * {@link Etranger}.
 */
final class Commandement {
    private Commandement() {}

    /** Ce qu'une phrase du chef en dit, et le texte sans ces ordres-là. */
    static final class Lu {
        final List<String> permis = new ArrayList<>(), retires = new ArrayList<>();
        String reste;

        boolean rien() { return permis.isEmpty() && retires.isEmpty(); }
    }

    private static Pattern p(String regex) { return Pattern.compile("\\b(?:" + regex.replace(" ", " +") + ")\\b"); }

    /** Commander : « te commander », « vous donner des ordres », « te diriger ». */
    static final String COMMANDER = "(?:te |vous |t )?(?:commander|diriger|donner (?:des |ses |les |d |de )?ordres)";
    static final String QUAND = "(?:aussi |desormais |maintenant |dorenavant |egalement |toujours )?";

    /** « Tu peux obéir à Nielscutt », « Nielscutt peut te donner des ordres », « autorise Nielscutt à vous commander », « obéis aussi à Nielscutt ». */
    static final Pattern PERMIS = p("(?:tu peux|vous pouvez|tu pourras|vous pourrez) " + QUAND + "(?:obeir|ecouter les ordres) (?:a|de) (\\w+)"
            + "|(?:obeis|obeissez) " + QUAND + "a (\\w+)"
            + "|(\\w+) (?:peut|pourra|a le droit de|aura le droit de|a la permission de|est autorise a|est autorisee a) " + QUAND + COMMANDER
            + "|autorise (\\w+) a " + COMMANDER
            + "|(?:donne|accorde) (?:a )?(\\w+) le droit de " + COMMANDER
            + "|fais ce que (\\w+) (?:te dit|te dira|te demande|te demandera|vous dit|vous dira|dit|dira)");
    /** « N'obéis plus à Nielscutt », « Nielscutt n'a plus le droit de te commander », « Nielscutt ne peut plus vous donner d'ordres ». */
    static final Pattern RETIRE = p("(?:n obeis|n obeissez|n obeiras|n obeirez|n ecoute|n ecoutez) (?:plus|pas|jamais) (?:a |les ordres de )?(\\w+)"
            + "|(\\w+) n a plus (?:le droit|la permission) de " + COMMANDER
            + "|(\\w+) ne (?:peut|pourra|doit) plus " + COMMANDER
            + "|(\\w+) n est plus autorisee? a " + COMMANDER
            + "|n autorise plus (\\w+) a " + COMMANDER
            + "|(?:retire|enleve) a (\\w+) le droit de " + COMMANDER);

    /** Lit une phrase du chef : les retraits d'abord (« X ne peut plus te commander » contient « X peut… »). */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        StringBuilder q = new StringBuilder(OrdresLongs.plat(texte));
        for (int i = 0; i < q.length(); i++) if (texte.charAt(i) == '_') q.setCharAt(i, '_'); // « Niels_cutt42 » : un seul mot
        StringBuilder sans = new StringBuilder(texte);
        noms(RETIRE, texte, q, sans, lu.retires);
        noms(PERMIS, texte, q, sans, lu.permis);
        lu.reste = lu.rien() ? texte : sans.toString().replaceAll("\\s{2,}", " ").strip();
        return lu;
    }

    private static void noms(Pattern motif, String texte, StringBuilder vu, StringBuilder sans, List<String> dans) {
        Matcher e = motif.matcher(vu.toString());
        List<int[]> effaces = new ArrayList<>();
        while (e.find()) {
            int g = 1;
            while (g < e.groupCount() && e.group(g) == null) g++;
            if (!GardeDepot.unNom(e.group(g))) continue;
            int[] n = GardeDepot.pseudo(texte, 0, e.start(g));
            String nom = texte.substring(n[0], n[1]);
            if (dans.stream().noneMatch(nom::equalsIgnoreCase)) dans.add(nom);
            effaces.add(new int[]{Math.min(e.start(), n[0]), Math.max(e.end(), n[1])});
        }
        for (int[] x : effaces)
            for (int i = x[0]; i < x[1]; i++) { sans.setCharAt(i, ' '); vu.setCharAt(i, ' '); }
    }

    /** Avec l'IA : ce que l'interpréteur a lu, repris tel quel (l'IA ne connaît pas ces ordres). */
    static void reprendre(Ordres.Lecture vers, Ordres.Lecture seul) {
        for (String n : seul.commandants) if (vers.commandants.stream().noneMatch(n::equalsIgnoreCase)) vers.commandants.add(n);
        for (String n : seul.destitues) if (vers.destitues.stream().noneMatch(n::equalsIgnoreCase)) vers.destitues.add(n);
    }

    /** Les intentions qui ne demandent rien de faire : une question, une politesse. */
    static final Set<Ordres.Intention> PAROLES = EnumSet.of(Ordres.Intention.ETAT, Ordres.Intention.AVANCEMENT, Ordres.Intention.POURQUOI,
            Ordres.Intention.AIDE, Ordres.Intention.SALUT, Ordres.Intention.MERCI, Ordres.Intention.LIENS, Ordres.Intention.DIAMANT,
            Ordres.Intention.EXCUSES, Ordres.Intention.OUI, Ordres.Intention.NON);

    /** La phrase lui demande de faire quelque chose (un travail, un geste, une garde, une autorisation…), pas seulement de parler. */
    static boolean ordre(Ordres.Lecture l) {
        return !l.missions.isEmpty() || !l.faire.isEmpty() || l.debut >= 0 || l.fin >= 0 || l.equipe >= 0 || l.jours >= 0 || !l.horsMetier.isEmpty()
                || !l.regles.isEmpty() || l.annulerRegles || l.posture != null || !l.epargner.isEmpty() || l.depot != null || !l.permis.isEmpty()
                || !l.retires.isEmpty() || l.guerre != null || l.boutique != null || !l.commandants.isEmpty() || !l.destitues.isEmpty()
                || l.intentions.stream().anyMatch(i -> !PAROLES.contains(i));
    }

    /** Les intentions que seul le chef peut demander : le renvoi, le suivre (il suit son chef), montrer son sac, l'enterrement. */
    static final Set<Ordres.Intention> AU_CHEF = EnumSet.of(Ordres.Intention.RENVOYER, Ordres.Intention.SUIVRE, Ordres.Intention.SAC, Ordres.Intention.ENTERRER,
            Ordres.Intention.DIAMANT);
    /** Les actions du répertoire que seul le chef peut demander : donner ou jeter ses affaires, se battre, aller se former (payé par le chef). */
    static final Set<String> GESTES_AU_CHEF = Set.of("donner", "jeter", "combat", "former");

    /**
     * Un autre que le chef lui parle : les ordres que seul le chef peut donner (renvoi, horaires et expéditions, règles,
     * gardes, dépôt, guerre, boutique et prix, autorisations, gestes de {@link #GESTES_AU_CHEF}) sont retirés de la
     * lecture. {@code comportement} : le programme d'une action du répertoire (null si inconnue). Vrai si quelque chose a
     * été retiré.
     */
    static boolean reserver(Ordres.Lecture l, java.util.function.Function<String, String> comportement) {
        boolean r = l.intentions.removeIf(AU_CHEF::contains);
        r |= l.debut >= 0 || l.fin >= 0 || l.equipe >= 0 || l.jours >= 0 || !l.regles.isEmpty() || l.annulerRegles || l.posture != null || !l.epargner.isEmpty()
                || l.depot != null || !l.permis.isEmpty() || !l.retires.isEmpty() || l.guerre != null || l.boutique != null || !l.commandants.isEmpty() || !l.destitues.isEmpty()
                || l.quotidien;
        l.debut = l.fin = l.equipe = l.jours = -1;
        l.regles.clear();
        l.annulerRegles = false;
        l.quotidien = false;
        l.posture = null;
        l.epargner.clear();
        l.depot = null;
        l.permis.clear();
        l.retires.clear();
        l.guerre = null;
        l.boutique = null;
        l.commandants.clear();
        l.destitues.clear();
        r |= l.faire.removeIf(f -> { String c = comportement.apply(f.id()); return c == null || GESTES_AU_CHEF.contains(c); });
        return r;
    }
}

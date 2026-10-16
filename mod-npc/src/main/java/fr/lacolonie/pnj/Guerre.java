package fr.lacolonie.pnj;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La guerre entre colonies (lot 9, cahier 6.5 : « simple : déclaration, guerriers qui défendent et attaquent ») : ce que
 * le chef en dit à un de ses ouvriers, lu sans IA comme {@link GardeChef}. « On déclare la guerre à Nielscutt », « on
 * fait la paix avec Nielscutt » (ou « la guerre est finie »), « lève l'armée » / « à l'attaque » / « attaquez la colonie
 * de Nielscutt » (avec « et pillez leur dépôt », « cassez leurs portes » en plus), « retraite ». Une phrase qui parle de
 * guerre est toute prise ici (« pillez leur dépôt » n'est pas un ordre sur notre dépôt). Une question, une négation
 * (« ne déclare pas la guerre à X ») : rien. Aucune dépendance au jeu (testé seul). Le reste : {@link Armee}.
 */
final class Guerre {
    private Guerre() {}

    /** Ce qu'une phrase en dit, et le texte sans ces ordres-là. */
    static final class Lu {
        /** À qui l'on déclare la guerre ; avec qui l'on fait la paix (pseudos tels qu'écrits), null sinon. */
        String declare, paix;
        /** « La guerre est finie » : la paix avec tous. */
        boolean paixToutes;
        /** Lever l'armée et attaquer ({@code cible} : le chef ennemi nommé, null : l'ennemi en cours) ; casser, piller en plus. */
        boolean lever, casser, piller;
        String cible;
        /** « Retraite » : l'armée rentre. */
        boolean retraite;
        String reste;

        boolean rien() { return declare == null && paix == null && !paixToutes && !lever && !retraite; }
    }

    private static Pattern p(String regex) { return Pattern.compile("\\b(?:" + regex.replace(" ", " +") + ")\\b"); }

    /** Le chef ennemi peut être nommé par sa colonie : « la colonie de X », « les gens de X ». */
    private static final String DE = "(?:(?:la colonie|le poste|le village|le camp|les gens|les terres|la ville|chez) (?:de |d |du )?)?";
    static final Pattern DECLARE = p("declar\\w* (?:la )?guerre (?:a|au|aux|contre) " + DE + "(\\w+)"
            + "|(?:sommes|soyons|entrons|entre|est|partons|part|sera|serons) en guerre (?:contre|avec) " + DE + "(\\w+)"
            + "|guerre contre " + DE + "(\\w+)");
    static final Pattern PAIX = p("paix (?:avec|a) " + DE + "(\\w+)|(?:arret\\w*|fin\\w*|termin\\w*) la guerre (?:avec|contre) " + DE + "(\\w+)");
    static final Pattern PAIX_TOUTES = p("guerre est (?:finie|fini|terminee|termine|close)|fin de la guerre|(?:fai\\w*|sign\\w*|conclu\\w*) la paix|c est la paix|plus de guerre|(?:arret\\w*|fini\\w*|termin\\w*) la guerre");
    static final Pattern LEVER = p("lev\\w* (?:l |une |notre |nos |les |la )?(?:armee|troupes?|soldats|hommes|gardes|guerriers)(?: (?:contre|pour attaquer) " + DE + "(\\w+))?|aux armes|a l (?:attaque|assaut)"
            + "|(?:attaqu\\w*|assaill\\w*|envahi\\w*|marche\\w*|march\\w* sur|pill\\w*|partez contre|allez contre) (?:la colonie|le poste|le village|le camp|les gens|les terres|la ville|chez) (?:de |d |du )?(\\w+)"
            + "|marche\\w* sur (\\w+)|partez en guerre|allez a la guerre");
    static final Pattern RETRAITE = p("retraite|repli\\w*|on se replie|repliez vous|replie toi|abandonn\\w* l (?:attaque|assaut)");
    static final Pattern CASSER = p("cass\\w*|detrui\\w*|enfonc\\w*|defonc\\w*|demoli\\w*|rasez|brul\\w*|incendi\\w*|mett\\w* le feu|abattez leurs?");
    static final Pattern PILLER = p("pill\\w*|vol\\w*|butin|emport\\w*|vid\\w* (?:leur|le|leurs|les) (?:depot|coffres?|reserves?)|prenez (?:tout|leur|leurs)|derob\\w*");
    /** Une négation dans le même morceau de phrase : rien (dans le doute, pas de guerre). */
    static final Pattern NEGATION = p("ne|n|pas|jamais|point|surtout pas|evit\\w*|refus\\w*");

    /** Lit une phrase du chef. */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        StringBuilder q = new StringBuilder(OrdresLongs.plat(texte));
        for (int i = 0; i < q.length(); i++) if (texte.charAt(i) == '_') q.setCharAt(i, '_');
        String p = q.toString();
        StringBuilder sans = new StringBuilder(texte);
        int s0 = 0;
        for (int i = 0; i <= p.length(); i++) {
            if (i < p.length() && ".;!?\n".indexOf(p.charAt(i)) < 0) continue;
            if (i > s0 && !(i < p.length() && p.charAt(i) == '?') && phrase(texte, p.substring(s0, i), s0, lu))
                for (int k = s0; k < Math.min(i + 1, sans.length()); k++) sans.setCharAt(k, ' ');
            s0 = i + 1;
        }
        lu.reste = lu.rien() ? texte : sans.toString().replaceAll("\\s{2,}", " ").strip();
        return lu;
    }

    /** Vrai si cette phrase parlait de guerre (elle part toute). */
    private static boolean phrase(String texte, String ph, int s0, Lu lu) {
        boolean pris = false;
        Matcher m = PAIX.matcher(ph);
        if (m.find() && !nie(ph, m.start())) { lu.paix = nom(texte, s0, m); pris = true; }
        else if ((m = PAIX_TOUTES.matcher(ph)).find() && !nie(ph, m.start())) { lu.paixToutes = true; pris = true; }
        else if ((m = DECLARE.matcher(ph)).find()) {
            if (!nie(ph, m.start())) lu.declare = nom(texte, s0, m);
            pris = true;
        }
        if ((m = LEVER.matcher(ph)).find() && !nie(ph, m.start())) {
            lu.lever = true;
            String n = nom(texte, s0, m);
            if (n != null) lu.cible = n;
            lu.casser |= CASSER.matcher(ph).find();
            lu.piller |= PILLER.matcher(ph).find();
            pris = true;
        }
        if ((m = RETRAITE.matcher(ph)).find() && !nie(ph, m.start())) { lu.retraite = true; lu.lever = false; pris = true; }
        return pris;
    }

    /** Une négation dans le morceau de phrase qui mène à l'ordre (depuis la dernière virgule ou le dernier « et »). */
    private static boolean nie(String ph, int i) {
        int d = 0;
        Matcher c = GardeChef.COUPURE.matcher(ph.substring(0, i));
        while (c.find()) d = c.end();
        Matcher n = NEGATION.matcher(ph.substring(d, Math.min(ph.length(), i + 1)));
        // « Ne » dans « on n'a plus le choix » est loin de l'ordre : la négation doit précéder le verbe de peu.
        return n.find();
    }

    /** « Guerre contre les monstres », « contre eux » : pas un pseudo. */
    static final java.util.Set<String> PAS_UN_NOM = java.util.Set.copyOf(List.of("les", "le", "la", "des", "ces", "ce", "cette", "eux", "lui", "leur", "leurs", "nous", "vous", "tous", "un", "une"));

    /** Le pseudo du premier groupe trouvé, tel qu'écrit (« Niels-cutt_42 » compris), ou null. */
    private static String nom(String texte, int s0, Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) == null) continue;
            if (GardeChef.PAS_UN_NOM.contains(m.group(g)) || GardeDepot.PAS_UN_NOM.contains(m.group(g)) || PAS_UN_NOM.contains(m.group(g))) return null;
            int a = s0 + m.start(g), b = a;
            while (b < texte.length() && (Character.isLetterOrDigit(texte.charAt(b)) || texte.charAt(b) == '_' || texte.charAt(b) == '-')) b++;
            while (b > a && texte.charAt(b - 1) == '-') b--;
            return texte.substring(a, b);
        }
        return null;
    }

    /** Avec l'IA : ce que l'interpréteur a lu de la guerre, repris tel quel (l'IA ne connaît pas ces ordres). */
    static void reprendre(Ordres.Lecture vers, Ordres.Lecture seul) { if (seul.guerre != null) vers.guerre = seul.guerre; }
}

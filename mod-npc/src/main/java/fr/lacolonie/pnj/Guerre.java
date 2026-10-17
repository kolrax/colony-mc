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
    /** En tête de morceau (impératif ou première personne) : « on déclare la guerre à X », « déclarez la guerre à X », « nous entrons en guerre contre X ». */
    static final Pattern DECLARE = Pattern.compile(("(?:^|[,:;]|\\b(?:et|mais|alors|donc)\\b)\\s*(?:(?:on|je|j|nous|alors|donc|eh bien|bien) )*"
            + "(?:(?:declare|declares|declarez|declarons) (?:la )?guerre (?:a|au|aux|contre)|(?:entrons|entre|entrez) en guerre (?:contre|avec)) " + DE + "(\\w+)\\b").replace(" ", " +"));
    /** Un récit, une hypothèse, un autre qui déclare : pas une déclaration (« si on entre en guerre contre X », « on a perdu la guerre contre X »). */
    static final Pattern PAS_DECLARE = p("si|s|en cas de|en cas d|quand|lorsque|souviens|souvenir|rappelles|perdu|gagne|etait|avait|a declare|ont declare|avez declare|as declare");
    static final Pattern PAIX = p("paix (?:avec|a) " + DE + "(\\w+)|(?:arret\\w*|fin\\w*|termin\\w*) la guerre (?:avec|contre) " + DE + "(\\w+)");
    static final Pattern PAIX_TOUTES = p("guerre est (?:finie|fini|terminee|termine|close)|fin de la guerre|(?:fai\\w*|sign\\w*|conclu\\w*) la paix|c est la paix|plus de guerre|(?:arret\\w*|fini\\w*|termin\\w*) la guerre");
    /** « Lève l'armée » (contre X), « partez en guerre » : sans autre nom. */
    static final Pattern LEVER = p("lev\\w* (?:l |une |notre |nos |les |la )?(?:armee|troupes?|soldats|hommes|gardes|guerriers)(?: (?:contre|pour attaquer) " + DE + "(\\w+))?|partez en guerre|allez a la guerre");
    /** « Attaquez la colonie de X », « marchez sur X » : seulement avec un nom (« marche sur le pont » n'en est pas). */
    static final Pattern LEVER_CIBLE = p("(?:attaqu\\w*|assaill\\w*|envahi\\w*|march\\w* sur|pill\\w*|partez contre|allez contre) (?:la colonie|le poste|le village|le camp|les gens|les terres|la ville|chez) (?:de |d |du )?(\\w+)"
            + "|march\\w* sur (\\w+)");
    /** « À l'attaque ! », « aux armes ! » : seuls dans la phrase, ou suivis d'un nom (« va à l'attaque de ce filon » n'en est pas). */
    static final Pattern LEVER_SEUL = Pattern.compile(("^\\s*(?:(?:allez|tous|vite|en avant) )*(?:a l (?:attaque|assaut)|aux armes)(?: (?:contre )?" + DE + "(\\w+))?\\s*$").replace(" ", " +"));
    /** « Retraite ! », « sonnez la retraite », « abandonnez l'assaut » (« prends ta retraite » n'en est pas). */
    static final Pattern RETRAITE = Pattern.compile(("^\\s*(?:(?:on|nous) )?(?:(?:sonnez|sonne|battez|battons|bat) (?:la |en )?)?retraite(?: generale)?\\s*$|\\babandonn\\w* l (?:attaque|assaut)\\b").replace(" ", " +"));
    /** « Repliez-vous », « rentrez » : la retraite seulement en guerre ({@link #repli}), et la phrase reste aux autres ordres (la consigne « repli »). */
    static final Pattern REPLI = p("repliez vous|replie toi|on se replie|repliez|rentrez|rentrez tous|on rentre");
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
        else if ((m = DECLARE.matcher(ph)).find() && !nie(ph, m.end() - 1) && !PAS_DECLARE.matcher(ph).find()) {
            lu.declare = nom(texte, s0, m);
            pris = lu.declare != null;
        }
        String cible = null;
        boolean lever = false;
        if ((m = LEVER_CIBLE.matcher(ph)).find() && !nie(ph, m.start()) && (cible = nom(texte, s0, m)) != null) lever = true;
        else if ((m = LEVER.matcher(ph)).find() && !nie(ph, m.start())) { lever = true; cible = nom(texte, s0, m); }
        else if ((m = LEVER_SEUL.matcher(ph)).find()) { lever = true; cible = nom(texte, s0, m); }
        if (lever) {
            lu.lever = true;
            if (cible != null) lu.cible = cible;
            // « … et ne cassez rien », « mais ne pillez pas » : non.
            Matcher c = CASSER.matcher(ph), v = PILLER.matcher(ph);
            while (c.find()) if (!nie(ph, c.start())) { lu.casser = true; break; }
            while (v.find()) if (!nie(ph, v.start())) { lu.piller = true; break; }
            pris = true;
        }
        if ((m = RETRAITE.matcher(ph)).find() && !nie(ph, m.start())) { lu.retraite = true; lu.lever = false; pris = true; }
        return pris;
    }

    /** « Repliez-vous », « rentrez » : une retraite si une guerre est en cours (la phrase n'est pas prise ici). */
    static boolean repli(String texte) { return REPLI.matcher(OrdresLongs.plat(texte)).find() && !NEGATION.matcher(OrdresLongs.plat(texte)).find(); }

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

    // --- Refonte 1.0.0, partie 6 : les règles des gardes et la formation (pur) ---------------------------------

    /** Les trois missions des gardes : défendre la colonie, escorter le chef, attaquer (un poste ennemi ou le lieu donné). */
    static final String DEFENDRE = "defendre", ESCORTER = "escorter", ATTAQUER = "attaquer";
    /** Sous 30 % de sa vie, il se replie vers le poste. */
    static final double SEUIL_REPLI = 0.3;
    /** L'écart entre deux gardes dans les rangs, en blocs ; tant de gardes de front quand ils marchent. */
    static final double ECART = 2;
    static final int FRONT = 3;

    /** Il frappe un homme (un joueur ou un ouvrier ennemi) seulement pendant une guerre déclarée, et sur mission « défendre » ou « attaquer ». */
    static boolean frappeHomme(boolean enGuerre, String mission) { return enGuerre && (ATTAQUER.equals(mission) || DEFENDRE.equals(mission)); }

    /** Il casse des blocs seulement avec la mission « attaquer », pendant la guerre. */
    static boolean casse(boolean enGuerre, String mission) { return enGuerre && ATTAQUER.equals(mission); }

    /** Trop blessé pour se battre : il se replie vers le poste. */
    static boolean repli(double vie, double max) { return max > 0 && vie < max * SEUIL_REPLI; }

    /**
     * Sa place dans les rangs, par rapport au chef de file ({@code rang} 0), qui marche dans la direction ({@code dx}, {@code dz}) :
     * {@code front} gardes par rang, derrière lui ({@code front} 1 : une colonne ; grand : une ligne). Renvoie {décalage x, décalage z}.
     */
    static double[] place(int rang, double dx, double dz, int front) {
        if (rang <= 0) return new double[]{0, 0};
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1e-6) { dx = 1; dz = 0; n = 1; }
        dx /= n;
        dz /= n;
        int f = Math.max(1, front), k = rang - 1;
        double derriere = (k / f + 1) * ECART, cote = (k % f - (f - 1) / 2.0) * ECART;
        // Derrière : l'opposé de la direction ; à côté : la perpendiculaire (-dz, dx).
        return new double[]{-dx * derriere - dz * cote, -dz * derriere + dx * cote};
    }

    /** Ce que lisent les deux chefs à la déclaration et à la paix. */
    static String declaration(String de, String a) { return "⚔ " + de + " déclare la guerre à " + a + "."; }

    static String paixFaite(String de, String a) { return "☮ " + de + " fait la paix avec " + a + ". La guerre est finie."; }
}

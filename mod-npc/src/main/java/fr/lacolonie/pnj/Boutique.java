package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La boutique d'un ouvrier (lot 11, cahier 9.5 et 9.6), en pur Java : ce que le chef en dit (« tiens la boutique »,
 * « ferme la boutique », « vends le pain à 2 pièces », « ne vends pas le fer »), ce que demande un client (« qu'est-ce
 * que tu vends ? », « je t'achète 3 pains »), la liste de l'étal, et le règlement en vraies pièces : le client donne
 * des pièces de sa bourse, la caisse rend la monnaie avec les siennes, rien ne se crée ni ne se perd. Le jeu (qui vend
 * quoi, où sont les pièces) est dans {@link Boutiquier}. Les objets restent des mots (« pain ») : le jeu les reconnaît.
 */
final class Boutique {
    private Boutique() {}

    /** Ce qu'une phrase du chef dit de la boutique, et le texte sans ces ordres-là. */
    static final class Lu {
        /** Vrai : ouvrir (la tenir) ; faux : fermer ; null : rien dit. */
        Boolean ouvrir;
        /** Les prix fixés : le mot de l'objet, et son prix en pièces. */
        final List<Prix> prix = new ArrayList<>();
        /** Ce qu'il ne doit plus vendre, et ce qu'il peut vendre de nouveau (« tout » : tout ce qui était exclu). */
        final List<String> exclus = new ArrayList<>(), permis = new ArrayList<>();
        /** Un prix dit pour un lot (« 20 torches pour 2 pièces ») : il demande le prix d'une seule. */
        boolean lot;
        /** Pendant la lecture : la phrase en cours a déjà fixé un prix (« vends le pain à 2 pièces et le steak à 5 »). */
        boolean enPrix;
        String reste;

        boolean rien() { return ouvrir == null && prix.isEmpty() && exclus.isEmpty() && permis.isEmpty() && !lot; }
    }

    record Prix(String objet, int prix) {}

    private static Pattern p(String regex) { return Pattern.compile(regex); }

    static final String LIEU = "(?:boutique|boutiques|echoppe|etal|comptoir|magasin|commerce)";
    static final String ARTICLE = "(?:le |la |les |l |du |de la |de l |des |de |d |un |une |ton |ta |tes |ce |cet |cette |ces |nos |notre |mon |ma |mes )";
    static final String NOMBRE = "(\\d+|" + String.join("|", Ordres.NOMBRES.keySet()) + ")";
    static final String MONNAIE = "(?:pieces?|sous|ecus?|deniers?)";

    /**
     * La tête d'un ordre : l'impératif vient en tête du morceau, après un prénom ou un mot d'élan au plus (« Raoul, tiens la
     * boutique », « allez, ferme l'étal »). « Je tiens la boutique », « Raoul a fermé la boutique », « on garde l'étal » : ce
     * ne sont pas des ordres.
     */
    static final String TETE = "^ (?:(?!(?:je|j|il|elle|on|nous|vous|ils|elles|tu|t|qui|que|qu|ce|c|ca|ta|ton|sa|son)\\b)[a-z0-9]+ )?"
            + "(?:(?:et|alors|toi|maintenant|bon|allez|aussi|puis|desormais|dorenavant|demain|aujourd hui|des maintenant) )*";
    static final String ROLE = "(?:le |la |notre |mon |ma |un |une )?(?:boutiquier|boutiquiere|marchand|marchande|commercant|commercante|vendeur|vendeuse) ";

    static final Pattern FERMER = p(TETE + "(?:(?:ferme|fermez|fermeras|tu fermeras|va fermer|referme|refermez|quitte|quittez|lache|laisse|abandonne) " + ARTICLE + "?" + LIEU + " "
            // « Arrête de vendre » ferme ; « arrête de vendre le pain » n'exclut que le pain (EXCLURE).
            + "|(?:arrete|cesse|arretez|cessez) (?:de vendre (?:pour aujourd hui |aujourd hui |maintenant |pour le moment )?$|(?:la vente|les ventes|" + ARTICLE + "?" + LIEU + ") )"
            + "|(?:fini|finie|assez|plus) (?:de |d )?(?:la |les )?(?:" + LIEU + "|vente|ventes) )");
    /** Fermer, dit avec une négation : « ne vends plus rien », « tu n'es plus le marchand ». */
    static final Pattern FERMER_NEGATIF = p(TETE + "(?:ne vends plus rien|ne vendez plus rien|tu n es plus " + ROLE + ")");
    static final Pattern OUVRIR = p(TETE + "(?:(?:tiens|tenez|tiendras|tu tiendras|tu vas tenir|tu dois tenir|va tenir|vas tenir|ouvre|ouvrez|ouvriras|va ouvrir|rouvre|rouvrez"
            + "|garde|prends|reprends|occupe toi d|occupe toi de|charge toi d|charge toi de) " + ARTICLE + "?" + LIEU + " "
            + "|(?:tu seras|tu es|deviens|sois|fais le|fais la|tu feras le|tu feras la) " + ROLE
            + "|(?:va |vas |tu vas |tu iras )?vend(?:s|re|ras)? (?:au poste|au bureau|au comptoir|a l etal|a la boutique|a l echoppe) )");
    /** « Ne ferme pas la boutique », « ne tiens plus l'étal » : dans le doute, il ne change rien (sauf « ne vends plus rien »). */
    static final Pattern NEGATION = p(" (?:ne|n) (?:\\w+ ){0,2}?(?:pas|plus|jamais|point) ");
    /**
     * Le prix d'une unité, et seulement dit comme un ordre de vendre : « vends le pain à 2 pièces (l'unité) », « fixe le prix
     * du gigot à 4 sous ». « Achète du pain pour 3 pièces », « je t'augmente à 10 pièces » n'en sont pas.
     */
    static final Pattern PRIX = p(TETE + "(?:(?:vends|vendez|vendras|tu vendras|vend) |fixe (?:le )?prix (?:du |de la |de l |des |d ))"
            + ARTICLE + "?(.+?) (?:a|pour|au prix de) " + NOMBRE + " " + MONNAIE
            + "(?: (?:chacun|chacune|chaque|piece|l unite|la piece|par piece|seulement|pas plus|pas moins))* $");
    /** Dans la même phrase, après un prix : « …, et le steak à 5 pièces » (l'article d'abord, sans verbe). */
    static final Pattern SUITE_PRIX = p("^ (?:et |aussi |puis )?" + ARTICLE + "(.+?) (?:a|pour|au prix de) " + NOMBRE + " " + MONNAIE
            + "(?: (?:chacun|chacune|chaque|piece|l unite|la piece|par piece|seulement|pas plus|pas moins))* $");
    /** « 20 torches pour 2 pièces » : le prix d'un lot ; il demande celui d'une seule. */
    static final Pattern LOT = p("^(?:\\d+|" + String.join("|", Ordres.NOMBRES.keySet().stream().filter(x -> !x.equals("un") && !x.equals("une")).toList()) + ") ");
    /** « Ne vends pas le fer », « ne vends jamais d'or ni de diamants », « arrête de vendre le pain », « retire le fer de la vente ». */
    static final Pattern EXCLURE = p(TETE + "(?:tu )?ne (?:vends|vend|vendez|vendras) (?:pas|plus|jamais|point|surtout pas|sous aucun pretexte|en aucun cas) "
            + ARTICLE + "?(.+?) $"
            + "|" + TETE + "(?:retire|retirez|enleve|enlevez|ote|otez) " + ARTICLE + "?(.+?) de (?:la vente|l etal|la boutique|ta boutique|ton etal) $"
            + "|" + TETE + "(?:arrete|arretez|cesse|cessez) de vendre " + ARTICLE + "?(.+?) $");
    /** « Vends aussi le fer », « tu peux vendre l'or », « remets le fer en vente », « vends tout ». */
    static final Pattern PERMETTRE = p(TETE + "(?:tu peux |tu pourras |je veux que tu )?(?:vends|vend|vendez|vendre|revends|revendre|remets en vente|mets en vente) "
            + "(?:aussi |de nouveau |a nouveau |encore |maintenant )?" + ARTICLE + "?(.+?) $"
            + "|" + TETE + "(?:remets|remettez|mets|mettez) " + ARTICLE + "?(.+?) en vente $");
    /** Après « ne vends pas … » : « … ni l'or », « … ni de diamants ». */
    static final Pattern NI = p(" ni " + ARTICLE + "?");
    /** Ce qui n'est pas un objet (« ne le vends pas », « vends-le », « vends à Raoul »). */
    static final Pattern PAS_UN_OBJET = p("^(?:le|la|les|l|en|y|lui|leur|rien|ca|cela|a .*|au .*|aux .*|moi|toi|nous|vous)$");

    /** Les morceaux d'une phrase : coupés aux points, virgules, « et », « puis », « ensuite ». */
    static final Pattern COUPURE = Pattern.compile("[.;!?\\n,]+|\\s(?:et|puis|ensuite)\\s", Pattern.CASE_INSENSITIVE);

    /** Lit une phrase du chef : les ordres de boutique sont retirés de {@link Lu#reste}, le reste se lit comme d'habitude. */
    static Lu lire(String texte) {
        Lu lu = new Lu();
        StringBuilder sans = new StringBuilder(texte);
        // Les questions (« qu'est-ce que tu vends ? ») ne sont pas des ordres : on les laisse de côté.
        int debutPhrase = 0;
        for (int i = 0; i <= texte.length(); i++) {
            if (i < texte.length() && ".;!?\n".indexOf(texte.charAt(i)) < 0) continue;
            boolean question = i < texte.length() && texte.charAt(i) == '?';
            lu.enPrix = false;
            if (!question && i > debutPhrase) morceaux(texte, debutPhrase, i, lu, sans);
            debutPhrase = i + 1;
        }
        lu.reste = lu.rien() ? texte : sans.toString().replaceAll("(?:\\s*,)+\\s*$", "").replaceAll("^\\s*[,;.!]+", "").replaceAll("\\s{2,}", " ").strip();
        return lu;
    }

    private static void morceaux(String texte, int a, int b, Lu lu, StringBuilder sans) {
        Matcher c = COUPURE.matcher(texte).region(a, b);
        int debut = a;
        while (true) {
            boolean trouve = c.find();
            int fin = trouve ? c.start() : b;
            if (fin > debut && morceau(Ordres.normaliser(texte.substring(debut, fin)), lu))
                for (int i = debut; i < (trouve ? c.end() : fin); i++) sans.setCharAt(i, ' ');
            if (!trouve) break;
            debut = c.end();
        }
    }

    /** Un morceau de phrase (normalisé, « tiens la boutique ») : vrai s'il parlait de la boutique. */
    static boolean morceau(String n, Lu lu) {
        // « Laisse la boutique ouverte » n'est pas la fermer.
        if (FERMER_NEGATIF.matcher(n).find() || FERMER.matcher(n).find() && !NEGATION.matcher(n).find() && !n.contains(" ouverte ") && !n.contains(" ouvert ")) { lu.ouvrir = false; return true; }
        if (OUVRIR.matcher(n).find()) {
            if (NEGATION.matcher(n).find()) return false; // « ne tiens pas la boutique » : dans le doute, rien
            lu.ouvrir = true;
            return true;
        }
        Matcher m = PRIX.matcher(n);
        boolean vu = m.find();
        if (!vu && lu.enPrix) vu = (m = SUITE_PRIX.matcher(n)).find();
        if (vu && objet(m.group(1)) != null) {
            if (LOT.matcher(objet(m.group(1))).find()) { lu.lot = true; return true; } // « vends 20 torches pour 2 pièces » : le prix d'une seule, s'il te plaît
            int prix = nombre(m.group(2));
            if (prix > 0) { lu.prix.add(new Prix(objet(m.group(1)), prix)); lu.enPrix = true; return true; }
        }
        m = EXCLURE.matcher(n);
        if (m.find()) {
            String o = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            boolean un = false;
            for (String x : NI.split(o)) if (objet(x) != null) { lu.exclus.add(objet(x)); un = true; }
            return un;
        }
        if (NEGATION.matcher(n).find()) return false;
        m = PERMETTRE.matcher(n);
        if (m.find()) {
            String o = objet(m.group(1) != null ? m.group(1) : m.group(2));
            if (o != null) { lu.permis.add(o); return true; }
        }
        return false;
    }

    /** Le mot de l'objet, sans article ni ponctuation ; null si ce n'en est pas un. */
    static String objet(String s) {
        if (s == null) return null;
        String o = (s.strip() + " ").replaceFirst("^" + ARTICLE, "").strip();
        if (o.isEmpty() || PAS_UN_OBJET.matcher(o).matches() || o.split(" ").length > 5) return null;
        return o;
    }

    static int nombre(String s) {
        if (s.matches("\\d+")) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }
        return Ordres.NOMBRES.getOrDefault(s, -1);
    }

    // --- Le client --------------------------------------------------------------------------------------------

    /** Ce que demande un client : la liste de l'étal ({@code objet} nul), ou tant de cet objet (le mot, tel qu'il le dit). */
    record Demande(String objet, int nombre) {
        boolean catalogue() { return objet == null; }
    }

    /** Au plus autant d'objets d'un coup. */
    static final int MAX_ACHAT = 64;

    /** Les formules d'achat explicites : seules elles (ou « 3 pains » tout court) font payer. */
    static final Pattern ACHAT = p(" (?:je t achete|je vous achete|j achete|je prends|je prendrai|je voudrais|je veux|j aimerais"
            + "|donne moi|donnez moi|vends moi|vendez moi|sers moi|servez moi) (.+?) $");
    /**
     * Ce qui n'est jamais un achat, même avec un objet nommé : une négation (« pas de pain pour moi », « non merci »), « déjà »,
     * un remerciement (« merci pour le pain »), le prix (« c'est combien le pain », « je voudrais savoir le prix »).
     */
    static final Pattern PAS_ACHAT = p(" (?:pas|non|nan|plus|jamais|rien|ne|n|deja|merci pour|merci de|combien|prix|savoir|coute|coutent|cher|chers|chere|cheres|vaut|valent) ");
    static final Pattern POLITESSE = p(" (?:s il te plait|s il vous plait|stp|svp|merci|je te prie|je vous prie|mon ami|l ami|bonhomme|messire|marchand|vendeur|boutiquier|alors|donc|encore|bonjour|salut) ");
    static final Pattern QUANTITE = p("^(?:" + NOMBRE + " )?(?:(?:un peu|quelques|un morceau|une part|une miche|une portion) (?:de |d )?)?" + ARTICLE + "?(.+?)$");
    /** Une phrase nue d'achat : au plus autant de mots (« 3 pains », « du pain », « un steak »), le nombre en plus. */
    static final int MOTS_NUS = 2;

    /**
     * Lit ce que dit un client. Un achat seulement avec une formule explicite (« je t'achète 3 pains », « je veux du pain »,
     * « donne-moi deux steaks ») ou une phrase nue d'un ou deux mots (« 3 pains », « du pain ») ; jamais une question, une
     * négation, un remerciement ou une phrase sur le prix : on ne fait jamais payer quelqu'un qui se renseigne ou qui refuse.
     * Tout le reste : la liste de l'étal, avec les prix.
     */
    static Demande demande(String texte) {
        String n = Ordres.normaliser(texte);
        if (texte.contains("?") || PAS_ACHAT.matcher(n).find()) return new Demande(null, 0);
        n = POLITESSE.matcher(n).replaceAll(" ").replaceAll("\\s+", " ");
        Matcher a = ACHAT.matcher(n);
        if (a.find()) return quantite(a.group(1), 4);
        return quantite(n.strip(), MOTS_NUS);
    }

    /** « 3 pains » : 3 « pains » ; au plus {@code mots} mots après le nombre (article compris), sinon la liste. */
    private static Demande quantite(String s, int mots) {
        Matcher q = QUANTITE.matcher(s.strip());
        if (s.isBlank() || !q.matches()) return new Demande(null, 0);
        String reste = s.strip().replaceFirst("^" + NOMBRE + " ", "");
        if (reste.split(" ").length > mots) return new Demande(null, 0);
        int nb = q.group(1) == null ? 1 : nombre(q.group(1));
        String o = objet(q.group(2));
        if (o == null || nb <= 0) return new Demande(null, 0);
        return new Demande(o, Math.min(nb, MAX_ACHAT));
    }

    // --- L'étal --------------------------------------------------------------------------------------------------

    /** Une marchandise de l'étal : son nom, son prix d'une unité, combien il en reste. */
    record Article(String nom, int prix, int nombre) {}

    /** Au plus autant de marchandises nommées dans la liste. */
    static final int LISTE = 6;

    /** « pain à 1 pièce, steak à 3 pièces et gigot à 2 pièces, et 3 autres marchandises » ; « » si rien. */
    static String liste(List<Article> etal) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < etal.size() && i < LISTE; i++) l.add(etal.get(i).nom() + " à " + pieces(etal.get(i).prix()));
        if (l.isEmpty()) return "";
        String r = l.size() == 1 ? l.get(0) : String.join(", ", l.subList(0, l.size() - 1)) + " et " + l.get(l.size() - 1);
        int autres = etal.size() - l.size();
        return autres <= 0 ? r : r + ", et " + autres + " autre" + (autres > 1 ? "s marchandises" : " marchandise");
    }

    static String pieces(long n) { return n + (n > 1 ? " pièces" : " pièce"); }

    // --- Le règlement ---------------------------------------------------------------------------------------------

    /** Au-delà, la boutique ne fait pas l'affaire (le calcul de la monnaie resterait petit). */
    static final long MONTANT_MAX = 20_000;

    /** Le résultat d'un règlement : les pièces du client et de la caisse après l'affaire (une case par valeur). */
    record Reglement(int[] client, int[] caisse) {}

    /**
     * Le client paie {@code montant} : il donne des pièces de sa bourse (le compte juste si possible, sinon le moins possible
     * au-delà), et la caisse lui rend la différence avec ses propres pièces. Null si ses pièces ne suffisent pas, ou si
     * la caisse ne peut pas rendre la monnaie exacte : rien ne bouge. Pour chaque sorte de pièce, le total du client et
     * de la caisse ne change jamais : aucune pièce n'est créée, fondue ni perdue.
     */
    static Reglement regler(int[] valeurs, int[] client, int[] caisse, long montant) {
        if (montant <= 0 || montant > MONTANT_MAX || Monnaie.total(valeurs, client) < montant) return null;
        int grosse = 0;
        for (int v : valeurs) grosse = Math.max(grosse, v);
        int borne = (int) Math.min(montant + grosse, Monnaie.total(valeurs, client));
        int[][] donne = sommes(valeurs, client, borne), rend = sommes(valeurs, caisse, grosse);
        for (int s = (int) montant; s <= borne; s++) {
            int r = s - (int) montant;
            if (!atteint(donne, s) || r > grosse || !atteint(rend, r)) continue;
            int[] g = composer(valeurs, donne, s), m = composer(valeurs, rend, r);
            int[] c = client.clone(), k = caisse.clone();
            for (int i = 0; i < valeurs.length; i++) { c[i] += m[i] - g[i]; k[i] += g[i] - m[i]; }
            return new Reglement(c, k);
        }
        return null;
    }

    /**
     * Les sommes qu'on peut faire avec ces pièces, jusqu'à {@code borne} : {@code u[i][s]}, le nombre de pièces de la sorte i
     * prises pour faire s avec les sortes 0 à i (-1 : impossible). Un sac à dos borné, une passe par sorte.
     */
    static int[][] sommes(int[] valeurs, int[] q, int borne) {
        int[][] u = new int[valeurs.length][borne + 1];
        for (int i = 0; i < valeurs.length; i++) {
            int v = valeurs[i];
            for (int s = 0; s <= borne; s++) {
                boolean avant = i == 0 ? s == 0 : u[i - 1][s] >= 0;
                if (avant) u[i][s] = 0;
                else if (v > 0 && s >= v && u[i][s - v] >= 0 && u[i][s - v] < q[i]) u[i][s] = u[i][s - v] + 1;
                else u[i][s] = -1;
            }
        }
        return u;
    }

    static boolean atteint(int[][] u, int s) { return u.length == 0 ? s == 0 : s < u[0].length && u[u.length - 1][s] >= 0; }

    /** Les pièces (une case par sorte) qui font exactement s, d'après {@link #sommes}. */
    static int[] composer(int[] valeurs, int[][] u, int s) {
        int[] r = new int[valeurs.length];
        for (int i = valeurs.length - 1; i >= 0; i--) {
            r[i] = u[i][s];
            s -= r[i] * valeurs[i];
        }
        return r;
    }
}

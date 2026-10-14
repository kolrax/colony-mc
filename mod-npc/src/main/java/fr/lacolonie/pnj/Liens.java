package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amitiés, inimitiés et couples entre ouvriers d'un même chef (lot 7, cahier 5.6), pur Java. Chaque ouvrier garde une note
 * d'affinité (−100 à 100) pour au plus {@link #RETENUS} collègues, rangée dans sa mémoire en texte ({@link #enTexte}).
 * Elle monte quand ils causent, travaillent ou mangent ensemble, quand l'un aide l'autre ; elle baisse au hasard selon les
 * caractères ({@link #heurt}), beaucoup après un vol vu ou un lit pris. Ami à {@link #AMI}, ennemi à {@link #ENNEMI} ; deux
 * amis à {@link #COUPLE} depuis {@link #COUPLE_JOURS} jours peuvent se mettre en couple ({@link #couplePossible}).
 * Le côté jeu est dans {@link Relations}.
 */
final class Liens {
    static final int MIN = -100, MAX = 100, AMI = 50, ENNEMI = -50, COUPLE = 80, COUPLE_JOURS = 2, RETENUS = 8;
    /** Ce que chaque chose fait à la note (avant le réglage de vitesse pour les gains). */
    static final int CAUSERIE = 3, VOISIN = 1, REPAS = 2, AIDE = 8, VOL = -30, LIT = -20;
    static final long JAMAIS = Long.MIN_VALUE;

    /** Un collègue : son prénom, la note, le jour (du jeu) où elle a atteint {@link #COUPLE} ({@link #JAMAIS} : pas encore), le couple. */
    record Lien(String nom, int note, long depuis, boolean couple) {}

    /** Par identifiant du collègue (son UUID en texte). */
    final Map<String, Lien> liens = new LinkedHashMap<>();

    Lien de(String id) { return liens.get(id); }

    int note(String id) { Lien l = liens.get(id); return l == null ? 0 : l.note(); }

    boolean ami(String id) { return note(id) >= AMI; }

    boolean ennemi(String id) { return note(id) <= ENNEMI; }

    /** L'identifiant de celui ou celle avec qui il est en couple, ou null. */
    String conjoint() {
        for (var e : liens.entrySet()) if (e.getValue().couple()) return e.getKey();
        return null;
    }

    /** La note change (bornée) ; un nouveau collègue chasse le lien le plus faible au-delà de {@link #RETENUS} (jamais le couple). */
    void changer(String id, String nom, int delta, long jour) {
        Lien l = liens.get(id);
        int note = Math.clamp((long) (l == null ? 0 : l.note()) + delta, MIN, MAX);
        long depuis = note < COUPLE ? JAMAIS : l != null && l.depuis() != JAMAIS ? l.depuis() : jour;
        liens.put(id, new Lien(nom == null || nom.isBlank() ? l == null ? "" : l.nom() : nom, note, depuis, l != null && l.couple()));
        while (liens.size() > RETENUS) {
            String faible = liens.entrySet().stream().filter(e -> !e.getValue().couple())
                    .min(Comparator.comparingInt(e -> Math.abs(e.getValue().note()))).map(Map.Entry::getKey).orElse(null);
            if (faible == null) break;
            liens.remove(faible);
        }
    }

    /** Ils se mettent en couple (il faut aussi le faire de l'autre côté). */
    void unir(String id, String nom, long jour) {
        Lien l = liens.get(id);
        liens.put(id, new Lien(nom, l == null ? COUPLE : l.note(), l == null ? jour : l.depuis(), true));
    }

    /** L'autre est mort ou parti : il l'oublie. */
    void oublier(String id) { liens.remove(id); }

    /**
     * Deux ouvriers peuvent se mettre en couple : chacun à {@link #COUPLE} au moins pour l'autre depuis {@link #COUPLE_JOURS}
     * jours, et ni l'un ni l'autre déjà en couple.
     */
    static boolean couplePossible(Liens a, String idB, Liens b, String idA, long jour) {
        if (a.conjoint() != null || b.conjoint() != null) return false;
        Lien ab = a.de(idB), ba = b.de(idA);
        return ab != null && ba != null && ab.note() >= COUPLE && ba.note() >= COUPLE && ab.depuis() != JAMAIS && ba.depuis() != JAMAIS
                && jour - Math.max(ab.depuis(), ba.depuis()) >= COUPLE_JOURS;
    }

    /**
     * Les heurts du quotidien, au hasard selon les caractères ({@code tirage} entre 0 et 1) : deux colériques se
     * chamaillent souvent, un fourbe agace, un colérique de temps en temps. 0 ou moins.
     */
    static int heurt(String a, String b, double tirage) {
        boolean deuxColeriques = "colerique".equals(a) && "colerique".equals(b);
        boolean fourbe = "fourbe".equals(a) || "fourbe".equals(b);
        boolean colerique = "colerique".equals(a) || "colerique".equals(b);
        if (deuxColeriques) return tirage < 0.3 ? -4 : 0;
        if (fourbe) return tirage < 0.2 ? -3 : 0;
        if (colerique) return tirage < 0.1 ? -2 : 0;
        return 0;
    }

    /** Un bilan passé à travailler l'un près de l'autre : un peu plus proches, sauf un heurt. */
    static int voisinage(String a, String b, double tirage, double vitesse) {
        int h = heurt(a, b, tirage);
        return h < 0 ? h : gain(VOISIN, vitesse);
    }

    /** Un gain, au réglage de vitesse ({@code liens_vitesse}) ; au moins 1 s'il est positif. Les pertes ne changent pas. */
    static int gain(int points, double vitesse) { return points <= 0 ? points : Math.max(1, (int) Math.round(points * vitesse)); }

    List<String> amis() { return noms(l -> l.note() >= AMI && !l.couple()); }

    List<String> ennemis() { return noms(l -> l.note() <= ENNEMI); }

    String nomConjoint() { String c = conjoint(); return c == null ? null : liens.get(c).nom(); }

    private List<String> noms(java.util.function.Predicate<Lien> p) {
        List<Lien> l = new ArrayList<>(liens.values().stream().filter(p).toList());
        l.sort(Comparator.comparingInt(x -> -Math.abs(x.note())));
        return l.stream().map(Lien::nom).filter(n -> !n.isBlank()).toList();
    }

    /** Ce que ses liens font à son moral, à chaque bilan : en couple +2, un ami +1, un ennemi −1 (de −1 à +2). */
    int moral() {
        int m = conjoint() != null ? 2 : amis().isEmpty() ? 0 : 1;
        if (!ennemis().isEmpty()) m -= 1;
        return Math.clamp(m, -1, 2);
    }

    /** La mort de cet ouvrier pèse tant de fois plus lourd : 3 en couple, 2 pour un ami, 1 sinon. */
    int poidsDeuil(String id) {
        Lien l = liens.get(id);
        return l == null ? 1 : l.couple() ? 3 : l.note() >= AMI ? 2 : 1;
    }

    /**
     * Sa réponse au chef qui demande ses amis, ses inimitiés ou son couple. Null s'il n'a rien à dire (aucun lien marqué) :
     * le jeu tire alors {@code liens_reponse_aucun}.
     */
    String reponse(boolean demandeCouple) {
        List<String> r = new ArrayList<>();
        String c = nomConjoint();
        if (c != null) r.add("Je partage ma vie avec " + c + ".");
        else if (demandeCouple) r.add("Je ne partage ma vie avec personne.");
        if (!amis().isEmpty()) r.add("Je m'entends bien avec " + et(amis()) + ".");
        if (!ennemis().isEmpty()) r.add("Avec " + et(ennemis()) + ", ça ne passe pas.");
        if (c == null && amis().isEmpty() && ennemis().isEmpty()) return null;
        return String.join(" ", r);
    }

    /** « Jean », « Jean et Marie », « Jean, Marie et Paul ». */
    static String et(List<String> noms) {
        if (noms.size() <= 1) return noms.isEmpty() ? "" : noms.get(0);
        return String.join(", ", noms.subList(0, noms.size() - 1)) + " et " + noms.get(noms.size() - 1);
    }

    // --- En mémoire : « id|nom|note|depuis|couple » séparés par « ; » -------------------------------------------

    String enTexte() {
        List<String> l = new ArrayList<>();
        for (var e : liens.entrySet()) {
            Lien x = e.getValue();
            l.add(e.getKey() + "|" + x.nom().replaceAll("[|;]", " ") + "|" + x.note() + "|" + x.depuis() + "|" + (x.couple() ? 1 : 0));
        }
        return String.join(";", l);
    }

    static Liens lire(String texte) {
        Liens r = new Liens();
        if (texte == null || texte.isBlank()) return r;
        for (String s : texte.split(";")) {
            String[] p = s.split("\\|", -1);
            if (p.length != 5) continue;
            try { r.liens.put(p[0], new Lien(p[1], Math.clamp(Integer.parseInt(p[2]), MIN, MAX), Long.parseLong(p[3]), p[4].equals("1"))); }
            catch (NumberFormatException ignore) {}
        }
        return r;
    }
}

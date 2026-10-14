package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Le moral au fil des jours (lot 5, cahier 5.2 et 5.3), pur Java. Un bilan régulier et doux ({@link #bilan}) : chaque cause
 * pèse quelques points, le total est borné ; les effets ({@link #effet}) : lenteur, plaintes, lettres au chef, démission.
 * Le côté jeu est dans {@link Humeur}. Les coups, insultes, compliments, marchés et la paie touchent déjà le moral sur le
 * moment ({@link Obeissance}) : le bilan ne les recompte pas, sauf le coup du chef encore frais (cause {@link Cause#CHEF}).
 */
final class Moral {
    private Moral() {}

    /** Les causes, et ce qu'il en dit dans sa lettre (« ce qui ne va pas : … ») ; {@code cle} : sa réplique de vie.txt. */
    enum Cause {
        PAIE("la paie qui ne vient pas", "paie_retard", "paie_retard"),
        REPAS("toujours la même pitance", "repas_pareils", "repas_varies"),
        LIT("je n'ai pas de lit à moi", "sans_lit", null),
        TOIT("mon lit est à la belle étoile", null, null),
        DANGER("le travail est trop dangereux", "danger", null),
        DEUIL("un compagnon est mort", "deuil_tristesse", null),
        METEO("ce temps de chien", "meteo_mauvaise", "meteo_belle"),
        CHEF("la façon dont on me traite", "chef_crie", null),
        COLONIE("", null, "fier_colonie");

        final String dit, plainte, joie;

        Cause(String dit, String plainte, String joie) { this.dit = dit; this.plainte = plainte; this.joie = joie; }
    }

    /** Ce que le bilan regarde (tout est lu côté jeu, sans recherche coûteuse). */
    record Etat(int impayes, boolean payeRecemment, List<String> repas, boolean lit, boolean toit, boolean blesse, boolean blesseVu,
                int joursDeuil, boolean pluie, boolean orage, int bilansPluie, boolean beauTemps, boolean coupChef, int ouvriers, int depot) {}

    /** Le bilan : les points de chaque cause, et le total (borné). */
    record Bilan(Map<Cause, Integer> causes, int total) {
        /** La cause qui pèse le plus (null : rien ne va mal). */
        Cause pire() {
            Cause p = null;
            for (var e : causes.entrySet()) if (e.getValue() < 0 && (p == null || e.getValue() < causes.get(p))) p = e.getKey();
            return p;
        }

        /** Ce qui lui fait le plus plaisir (null : rien). */
        Cause meilleure() {
            Cause p = null;
            for (var e : causes.entrySet()) if (e.getValue() > 0 && (p == null || e.getValue() > causes.get(p))) p = e.getKey();
            return p;
        }

        int de(Cause c) { return causes.getOrDefault(c, 0); }
    }

    /** Au plus tant de points perdus ou gagnés par bilan. */
    static final int BAISSE_MAX = 8, HAUSSE_MAX = 4;
    /** Les derniers aliments retenus. */
    static final int REPAS = 5;
    /** La mort d'un compagnon : tout de suite, puis au bilan selon les jours passés (le jour même, le lendemain, le surlendemain). */
    static final int DEUIL_COUP = -10;
    static final int[] DEUIL = {-4, -2, -1};
    /** Plusieurs morts le même jour : au plus tant de points perdus ce jour-là. */
    static final int DEUIL_JOUR = 15;

    /** Le coup d'une mort, sachant {@code deja} (≤ 0) déjà perdu pour des morts du même jour. */
    static int deuilCoup(int deja) { return Math.min(0, Math.max(DEUIL_COUP, -DEUIL_JOUR - deja)); }
    /** Une colonie qui prospère : au moins tant d'ouvriers, et un dépôt d'au moins tant d'objets. */
    static final int OUVRIERS = 4, DEPOT = 256;
    /** Un bilan si mauvais que le moral ne remonte plus tout seul. */
    static final int MAUVAIS = -3;

    static Bilan bilan(Etat e) {
        Map<Cause, Integer> c = new EnumMap<>(Cause.class);
        if (e.impayes > 0) c.put(Cause.PAIE, -Math.min(6, 2 * e.impayes));
        else if (e.payeRecemment) c.put(Cause.PAIE, 1);
        c.put(Cause.REPAS, repas(e.repas));
        if (!e.lit) c.put(Cause.LIT, -2);
        else c.put(Cause.TOIT, e.toit ? 1 : -1);
        c.put(Cause.DANGER, (e.blesse ? -2 : 0) + (e.blesseVu ? -1 : 0));
        if (e.joursDeuil >= 0 && e.joursDeuil < DEUIL.length) c.put(Cause.DEUIL, DEUIL[e.joursDeuil]);
        c.put(Cause.METEO, e.orage ? -2 : e.pluie && e.bilansPluie >= 2 ? -1 : e.beauTemps ? 1 : 0);
        if (e.coupChef) c.put(Cause.CHEF, -2);
        c.put(Cause.COLONIE, (e.ouvriers >= OUVRIERS ? 1 : 0) + (e.depot >= DEPOT ? 1 : 0));
        c.values().removeIf(v -> v == 0);
        int total = c.values().stream().mapToInt(Integer::intValue).sum();
        return new Bilan(c, Math.clamp(total, -BAISSE_MAX, HAUSSE_MAX));
    }

    /** Ses derniers repas : au moins 3, tous pareils → −2 ; au moins 3 aliments différents → +1 ; sinon rien. */
    static int repas(List<String> r) {
        if (r.size() < 3) return 0;
        long differents = r.stream().distinct().count();
        return differents == 1 ? -2 : differents >= 3 ? 1 : 0;
    }

    /** La liste des derniers repas (« a,b,c »), avec celui-ci au bout ; les plus anciens partent au-delà de {@link #REPAS}. */
    static String retenir(String liste, String aliment) {
        List<String> l = new ArrayList<>(lire(liste));
        l.add(aliment);
        while (l.size() > REPAS) l.remove(0);
        return String.join(",", l);
    }

    static List<String> lire(String liste) { return liste == null || liste.isEmpty() ? List.of() : Arrays.asList(liste.split(",")); }

    /**
     * Avec le temps, le moral revient vers 60 ({@link Obeissance#versLeRepos}) ; mais il ne remonte pas tant que le dernier
     * bilan était franchement mauvais ({@link #MAUVAIS} ou pire). Un petit tracas (−1, −2) s'efface ainsi tout seul.
     */
    static int repos(int moral, int dernierBilan) {
        return moral < Obeissance.REPOS && dernierBilan <= MAUVAIS ? moral : Obeissance.versLeRepos(moral);
    }

    // --- Effets -------------------------------------------------------------------------------------

    /** Le niveau d'effet de son moral : un point d'entrée pour la suite (vol, sabotage, fuite, révolte). */
    enum Effet { AUCUN, LENT, TRES_LENT }

    /** Les seuils (réglables) : sous {@code lent}, il travaille plus lentement ; sous {@code tresLent}, plus encore. */
    static Effet effet(int moral, int lent, int tresLent) {
        return moral < tresLent ? Effet.TRES_LENT : moral < lent ? Effet.LENT : Effet.AUCUN;
    }

    /** La lenteur (fraction de vitesse ôtée) : la moitié de {@code max} un peu bas, {@code max} au plus bas. */
    static double lenteur(Effet e, double max) { return switch (e) { case AUCUN -> 0; case LENT -> max / 2; case TRES_LENT -> max; }; }

    /** Ce que le bilan décide côté lettres et départ. */
    enum Suite { RIEN, AVERTIT, DEMISSIONNE }

    /**
     * Où il en est de sa patience : {@code avertissements} lettres déjà écrites, la dernière il y a {@code depuis} bilans ;
     * {@code bas} bilans de suite sous le seuil de démission. Sous {@code seuilLettre}, il écrit (au plus une lettre tous les
     * {@code ecart} bilans) ; il ne démissionne qu'après au moins deux lettres et {@code bilansAvantDepart} bilans très bas.
     */
    static Suite suite(int moral, int avertissements, int depuis, int bas, int seuilLettre, int ecart, int bilansAvantDepart) {
        if (avertissements >= 2 && bas >= bilansAvantDepart) return Suite.DEMISSIONNE;
        if (moral < seuilLettre && (avertissements == 0 || depuis >= ecart)) return Suite.AVERTIT;
        return Suite.RIEN;
    }

    /** Le moral remonté assez haut (50) : il oublie ses lettres de plainte, sa patience repart à zéro. */
    static final int APAISE = 50;
}

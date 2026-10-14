package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Le moral au fil des jours (lot 5, cahier 5.2 et 5.3), pur Java. Un bilan régulier et doux ({@link #bilan}) : chaque cause
 * pèse quelques points, le total est borné ; les effets ({@link #effet}) : lenteur, plaintes, lettres au chef, démission ;
 * au plus bas et sur la durée ({@link #mefait}) : vol, sabotage, fuite avec un butin, avertissements puis révolte.
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
    /** Les jours après une mort où ses compagnons en reparlent d'eux-mêmes ({@link Remarques}). */
    static final int DEUIL_RECENT = 2;
    /** Un enterrement : le moral perdu par cette mort ({@code coup} ≤ 0) remonte de moitié (arrondi au point supérieur). */
    static int apaisement(int coup) { return (-coup + 1) / 2; }
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

    /** Le niveau d'effet de son moral (la lenteur ; les méfaits : {@link #mefait}). */
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

    // --- Méfaits (2e partie) : vol, sabotage, fuite, menaces et révolte -----------------------------

    /** Ce que le bilan décide en plus des lettres : un méfait, un avertissement avant la révolte, ou la révolte. */
    enum Mefait { RIEN, VOL, SABOTAGE, MENACE, REVOLTE }

    /** Au moins tant de bilans de suite au plus bas, après une première lettre, avant le moindre méfait. */
    static final int BAS_AVANT_MEFAIT = 2;
    /** Les avertissements avant la révolte (avertissement_1 à 3 de vie.txt), au moins tant de bilans entre deux. */
    static final int MENACES = 3, ECART_MENACE = 2;
    /** Seul au plus bas chez son chef : pas de révolte avant tant de jours au plus bas. */
    static final int JOURS_SEUL = 2;
    /** Un vol : au plus tant d'objets d'un coup ; son butin caché : au plus tant de piles. */
    static final int VOL_COUP = 3, BUTIN_MAX = 27;
    /** En fuite : il s'en va à tant de blocs de son poste (hors de son espace de vie) avant de se dire libre. */
    static final int FUITE_LOIN = 64;

    /**
     * Ce que le bilan regarde pour un méfait. {@code bas} : bilans de suite sous {@code seuilBas} ; {@code avertis} : lettres
     * de plainte ; {@code compagnons} : autres ouvriers du même chef au plus bas ; {@code menaces} : avertissements de
     * révolte déjà donnés, le dernier il y a {@code depuisMenace} bilans ; {@code chefConnecte} : il peut les lire ;
     * {@code chefPresent} : il est là, à portée ; {@code chance} : en pour cent par bilan (selon le caractère, plus ou moins).
     */
    record Rancoeur(int moral, int seuilBas, int avertis, int bas, int bilansParJour, String temperament, int compagnons, int menaces,
                    int depuisMenace, boolean chefConnecte, boolean chefPresent, boolean revolteReglee, int volsDuJour, int volMax, int chance) {}

    /**
     * Le méfait de ce bilan. Rien tant que le moral n'est pas au plus bas depuis un moment, après au moins une lettre : un
     * ouvrier bien traité ne fait jamais rien de tout cela. La révolte (un peureux, jamais seul) vient en dernier, quand d'autres
     * sont au plus bas avec lui (ou seul, après {@link #JOURS_SEUL} jours), après deux lettres et {@link #MENACES}
     * avertissements au chef connecté, et seulement le chef présent. Sinon, de temps en temps ({@code tirage}), un vol ou
     * un sabotage selon son caractère ({@code choix}).
     */
    static Mefait mefait(Rancoeur r, double tirage, double choix) {
        if (r.moral >= r.seuilBas || r.avertis < 1 || r.bas < BAS_AVANT_MEFAIT) return Mefait.RIEN;
        boolean seul = !"peureux".equals(r.temperament) && r.bas >= JOURS_SEUL * r.bilansParJour; // un peureux ne se lève qu'avec d'autres
        if (r.revolteReglee && r.avertis >= 2 && (r.compagnons >= 1 || seul)) {
            if (r.menaces >= MENACES) { if (r.chefPresent) return Mefait.REVOLTE; }
            else if (r.chefConnecte && (r.menaces == 0 || r.depuisMenace >= ECART_MENACE)) return Mefait.MENACE;
        }
        if (tirage * 100 >= r.chance * penchant(r.temperament)) return Mefait.RIEN;
        if (choix >= partVol(r.temperament)) return Mefait.SABOTAGE;
        return r.volsDuJour < r.volMax ? Mefait.VOL : Mefait.RIEN;
    }

    /** Selon son caractère, sa pente aux méfaits (la chance réglée, multipliée). */
    static double penchant(String temperament) {
        return switch (temperament) { case "fourbe" -> 1.5; case "colerique" -> 1.2; case "peureux" -> 0.6; default -> 0.5; };
    }

    /** La part du vol parmi ses méfaits (le reste : sabotage). Le fourbe vole, le colérique casse. */
    static double partVol(String temperament) {
        return switch (temperament) { case "fourbe" -> 0.8; case "peureux" -> 0.7; case "colerique" -> 0.3; default -> 0.5; };
    }

    /** Décidé à partir : en douce, avec un petit butin (vrai), ou en rendant tout au dépôt. */
    static boolean fuit(String temperament, double tirage) {
        return tirage < switch (temperament) { case "fourbe" -> 0.7; case "peureux" -> 0.5; case "colerique" -> 0.3; default -> 0; }; // le calme part en règle
    }

    /** Ce qu'il n'ose pas prendre : ce qui ne s'empile pas (outils, armes, armures) et ce qui a trop de valeur. */
    static boolean precieux(String id, boolean empilable) {
        if (!empilable) return true;
        for (String p : PRECIEUX) if (id.contains(p)) return true;
        return false;
    }

    static final List<String> PRECIEUX = List.of("diamond", "emerald", "netherite", "gold", "numismatics", "enchanted", "totem", "elytra",
            "beacon", "shulker", "nether_star", "ancient_debris", "heart_of_the_sea", "dragon", "spawn_egg", "echo_shard", "trim");

    /** Combien il en prend d'un tas de {@code dispo} : au plus {@link #VOL_COUP}, et ce qui reste de son quota du jour. */
    static int prise(int dispo, int resteDuJour) { return Math.max(0, Math.min(dispo, Math.min(VOL_COUP, resteDuJour))); }

    /**
     * La révolte s'arrête : le moral est remonté (au seuil des lettres), le chef a payé ce qu'il devait au moment où elle a
     * commencé, ou le chef n'est plus là (parti loin, déconnecté).
     */
    static boolean finRevolte(int moral, int seuilLettre, int impayesAuDebut, int impayes, boolean chefLa) {
        return moral >= seuilLettre || impayesAuDebut > 0 && impayes == 0 || !chefLa;
    }
}

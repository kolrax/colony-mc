package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

/**
 * Amitiés, inimitiés et couples, côté jeu (lot 7, cahier 5.6). Les notes ({@link Liens}) sont dans la mémoire de chacun
 * (« liens »). Rien à chaque tick : un voisinage au bilan du moral ({@link #bilan}, une recherche d'ouvriers à 8 blocs),
 * le reste sur événement (causerie finie, repas, aide, vol vu, lit pris, mort). Jamais de coups entre ennemis : des
 * piques, et ils évitent de causer ensemble.
 */
final class Relations {
    private Relations() {}

    static final String CLE = "liens";
    /** Voisins au bilan (blocs), repas pris ensemble (blocs, ticks), conjoint qui accourt auprès d'un blessé (blocs). */
    static final double PRES = 8, TABLE = 6, INQUIET = 48;
    static final int REPAS_ENSEMBLE = 1200;

    static Liens de(Travailleur t) { return Liens.lire(t.memoire.getString(CLE)); }

    static void garder(Travailleur t, Liens l) {
        if (l.liens.isEmpty()) t.memoire.remove(CLE);
        else t.memoire.putString(CLE, l.enTexte());
    }

    static boolean actifs() { return Reglages.actuel.liens_actifs; }

    static boolean collegues(Travailleur a, Travailleur b) {
        return a != b && a.chef != null && a.chef.equals(b.chef) && a.mode != Travailleur.Mode.CHOMEUR && b.mode != Travailleur.Mode.CHOMEUR;
    }

    /** La note de {@code a} pour {@code b} change ({@code lesDeux} : celle de b pour a aussi). Les gains suivent {@code liens_vitesse}. */
    static void changer(Travailleur a, Travailleur b, int delta, boolean lesDeux) {
        if (!actifs() || !collegues(a, b)) return;
        int d = Liens.gain(delta, Reglages.actuel.liens_vitesse);
        noter(a, b, d);
        if (lesDeux) noter(b, a, d);
    }

    private static void noter(Travailleur a, Travailleur b, int d) {
        Liens l = de(a);
        l.changer(b.getStringUUID(), b.prenom, d, a.jour());
        garder(a, l);
    }

    /** Ils ont causé jusqu'au bout : plus proches ; entre amis, un mot de la fin. */
    static void causerie(Travailleur a, Travailleur b) {
        changer(a, b, Liens.CAUSERIE, true);
        if (actifs() && de(b).ami(a.getStringUUID())) Besoins.parler(b, "ami_causerie", a.prenom);
    }

    /** L'un aide l'autre (soigné, accouru auprès de lui) : bien plus proches. */
    static void aide(Travailleur qui, Travailleur aide) { changer(qui, aide, Liens.AIDE, true); }

    /** Le témoin l'a vu voler : il ne le voit plus du même œil. */
    static void vol(Travailleur voleur, Travailleur temoin) { changer(temoin, voleur, Liens.VOL, false); }

    /** Il a trouvé l'autre dans son lit. */
    static void lit(Travailleur victime, Travailleur dormeur) { changer(victime, dormeur, Liens.LIT, false); }

    /** Il commence un repas : ceux du même chef qui ont mangé tout près dans la dernière minute ont mangé avec lui (une fois par repas). */
    static void repas(Travailleur t) {
        if (!actifs() || t.chef == null) return;
        long maintenant = t.level().getGameTime();
        // Une fois par repas : la deuxième bouchée du même repas ne compte plus.
        boolean dejaATable = t.memoire.contains("mange_t") && maintenant - t.memoire.getLong("mange_t") < REPAS_ENSEMBLE;
        t.memoire.putLong("mange_t", maintenant);
        if (dejaATable) return;
        for (Travailleur o : t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(TABLE),
                o -> collegues(t, o) && o.memoire.contains("mange_t") && maintenant - o.memoire.getLong("mange_t") < REPAS_ENSEMBLE))
            changer(t, o, Liens.REPAS, true);
    }

    /**
     * Au bilan du moral, avant son calcul : les liens vers qui n'est plus de la colonie (renvoyé, parti, mort) s'effacent ;
     * puis ceux qui travaillent près de lui (8 blocs, éveillés) : chaque paire n'est comptée qu'une fois (par celui des deux
     * qui a le plus petit identifiant), les caractères font des heurts ; tous les {@link Liens#DECLIN} bilans, les autres
     * notes reviennent d'un point vers 0. Puis le couple, s'il est temps. Rend ceux qui sont près de lui (pour {@link #parler}).
     */
    static List<Travailleur> bilan(Travailleur t) {
        if (!actifs() || t.chef == null || t.aTerre || t.isSleeping() || t.mode == Travailleur.Mode.CHOMEUR) return List.of();
        Liens l = de(t);
        purger(t, l);
        List<Travailleur> pres = t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(PRES),
                o -> collegues(t, o) && o.isAlive() && !o.aTerre && !o.isSleeping() && t.distanceToSqr(o) <= PRES * PRES);
        int n = t.memoire.getInt("liens_bilans") + 1;
        t.memoire.putInt("liens_bilans", n % Liens.DECLIN);
        if (n >= Liens.DECLIN) {
            l.declin(pres.stream().map(Travailleur::getStringUUID).collect(java.util.stream.Collectors.toSet()));
        }
        garder(t, l);
        double vitesse = Reglages.actuel.liens_vitesse;
        for (Travailleur o : pres) {
            if (t.getUUID().compareTo(o.getUUID()) > 0) continue;
            int d = Liens.voisinage(t.temperament, o.temperament, t.getRandom().nextDouble(), vitesse);
            noter(t, o, d);
            noter(o, t, d);
        }
        l = de(t);
        if (Reglages.actuel.liens_couples && l.conjoint() == null)
            for (Travailleur o : pres)
                if (!Famille.pasDeCouple(t, o) && Liens.couplePossible(l, o.getStringUUID(), de(o), t.getStringUUID(), t.jour())) { unir(t, o); return List.of(); } // ni mineur ni proche (lot 13)
        return pres;
    }

    /** Les liens vers un ouvrier absent de l'effectif de son chef, ou mort, s'effacent (lu dans le {@link Courrier}, sans recherche). */
    static void purger(Travailleur t, Liens l) {
        if (l.liens.isEmpty() || t.level().getServer() == null) return;
        var eff = Courrier.de(t.level().getServer()).effectifs.get(t.chef);
        if (eff == null || !eff.containsKey(t.getUUID())) return; // pas encore inscrit lui-même : on ne juge pas les autres
        l.liens.keySet().removeIf(id -> {
            try {
                var m = eff.get(UUID.fromString(id));
                return m == null || !m.mort.isEmpty();
            } catch (IllegalArgumentException e) { return true; }
        });
    }

    /**
     * Juste après le bilan, s'il n'a rien dit de son moral : une réplique au plus pour qui est là (éveillé, à 8 blocs) :
     * tendresse (ou inquiétude si l'être aimé est blessé), salut à un ami, pique à un ennemi.
     */
    static void parler(Travailleur t, List<Travailleur> pres) {
        if (pres.isEmpty() || t.isSleeping()) return;
        Liens l = de(t);
        String conjoint = l.conjoint();
        for (Travailleur o : pres)
            if (o.getStringUUID().equals(conjoint)) { Besoins.parler(t, Blessures.gravite(o) != Gravite.AUCUNE ? "couple_inquiet" : "couple_tendresse", o.prenom); return; }
        for (Travailleur o : pres) if (l.ami(o.getStringUUID())) { Besoins.parler(t, "ami_salut", o.prenom); return; }
        for (Travailleur o : pres) if (l.ennemi(o.getStringUUID())) { Besoins.parler(t, "inimitie_pique", o.prenom); return; }
    }

    /** Ils se mettent en couple : chacun le note, l'un l'annonce, et le chef reçoit une lettre. */
    static void unir(Travailleur a, Travailleur b) {
        long jour = a.jour();
        Liens la = de(a), lb = de(b);
        la.unir(b.getStringUUID(), b.prenom, jour);
        lb.unir(a.getStringUUID(), a.prenom, jour);
        garder(a, la);
        garder(b, lb);
        Besoins.dire(a, "couple_annonce", b.prenom);
        Courrier.ecrire(a, "couple", b.prenom);
    }

    /** Celui ou celle avec qui il est en couple, s'il est vivant dans le même monde (un seul accès par identifiant), sinon null. */
    static Travailleur conjoint(Travailleur t) {
        String id = actifs() ? de(t).conjoint() : null;
        if (id == null || !(t.level() instanceof ServerLevel sl)) return null;
        try {
            return sl.getEntity(UUID.fromString(id)) instanceof Travailleur o && o.isAlive() && collegues(t, o) ? o : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    /** L'un des deux ne veut pas causer avec l'autre. */
    static boolean brouilles(Travailleur a, Liens la, Travailleur b) {
        return la.ennemi(b.getStringUUID()) || de(b).ennemi(a.getStringUUID());
    }

    /** La mort de {@code mort} pèse tant de fois plus lourd pour {@code t} (couple 3, ami 2) ; il l'oublie ensuite. */
    static int deuil(Travailleur t, Travailleur mort) {
        if (!actifs()) return 1;
        Liens l = de(t);
        int p = l.poidsDeuil(mort.getStringUUID());
        l.oublier(mort.getStringUUID());
        garder(t, l);
        return p;
    }

    /** Sa réponse au chef : « qui sont tes amis ? », « tu t'entends avec qui ? », « tu es en couple ? ». */
    static String reponse(Travailleur t, boolean demandeCouple) {
        String r = de(t).reponse(demandeCouple);
        if (r != null) return r;
        r = Besoins.texte(t, "liens_reponse_aucun", null, null);
        return r != null ? r : "Je n'ai encore de lien avec personne ici.";
    }
}

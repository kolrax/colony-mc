package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Lot 13, cahier 5.9 : les étoiles qui montent, côté serveur (les règles : {@link Etoiles}). L'expérience vient du vrai
 * travail ({@link Travailleur#finirAction}) ; la formation, payée par le chef de sa bourse (« va te former », action
 * {@code se_former} du répertoire), le retire un moment à son poste pour étudier, puis il revient une étoile de plus.
 * Tout est dans sa mémoire (sauvegardée) : « experience », « xp_minute », « etoiles_gagnees », « formation_fin ».
 * Rien ne tourne à chaque tick : un calcul par action finie, un test par pensée.
 */
final class Apprentissage {
    private Apprentissage() {}

    static Etoiles.Reglage reglage() { return Reglages.actuel.etoiles; }

    /** Une action de son métier réussie : une minute d'ouvrage de plus (une par minute au plus). */
    static void travail(Travailleur t) {
        if (t.chef == null || t.niveau >= Etoiles.MAX || t.memoire.getLong("formation_fin") > 0) return;
        long m = Etoiles.minute(t.level().getGameTime()) + 1; // + 1 : 0 veut dire « jamais »
        if (t.memoire.getLong("xp_minute") == m) return;
        t.memoire.putLong("xp_minute", m);
        int xp = t.memoire.getInt("experience") + 1;
        t.memoire.putInt("experience", xp);
        if (Etoiles.monte(t.niveau, xp, reglage().minutes_de_travail)) monter(t, false);
    }

    /** Ce qu'il demande pour son rang : la hausse mesurée des étoiles gagnées chez ce chef (appelé par {@link Travailleur#salaireDemande}). */
    static long salaireDeBase(Travailleur t) {
        int g = Math.clamp(t.memoire.getInt("etoiles_gagnees"), 0, t.niveau - 1);
        return Etoiles.salaire(Reglages.salaire(t.niveau - g), Reglages.salaire(t.niveau), g, reglage().hausse_par_etoile);
    }

    /** Une étoile de plus : il le dit, et l'écrit au chef avec ce qu'il demande désormais. */
    static void monter(Travailleur t, boolean formation) {
        if (t.niveau >= Etoiles.MAX) return;
        long avant = t.salaireDemande();
        float vie = t.getHealth();
        t.niveau++;
        t.memoire.putInt("etoiles_gagnees", t.memoire.getInt("etoiles_gagnees") + 1);
        t.memoire.remove("experience");
        t.appliquerNiveau();
        t.setHealth(Math.min(vie, t.getMaxHealth())); // plus robuste, pas soigné pour autant
        String etoiles = "★".repeat(t.niveau);
        t.dire(formation ? "Me revoilà, chef ! Le maître m'a bien instruit : me voici " + etoiles + "."
                : "À force d'ouvrage, je connais mon métier sur le bout des doigts : me voici " + etoiles + " !");
        long demande = t.salaireDemande();
        String paie = demande <= avant ? ""
                : t.salaire < 0 ? " Ma paie passe de " + Reglages.texte(avant) + " à " + Reglages.texte(demande) + ", comme il sied à mon rang."
                : t.salaire < demande ? " Je vaux désormais " + Reglages.texte(demande) + " de paie : j'espère que tu y songeras, chef." : "";
        Courrier.ecrireTexte(t, "Une étoile de plus", (formation ? "Ma formation est finie. " : "Mon ouvrage m'a rendu plus habile. ")
                + "Je travaille plus vite et plus sûrement, désormais " + etoiles + "." + paie, false);
        t.majNom();
    }

    /** « Va te former » : le chef paie de sa bourse, il part étudier. Renvoie sa réponse. */
    static String commencer(Travailleur t, ServerPlayer chef) {
        if (t.niveau >= Etoiles.MAX) return "Je suis maître en mon métier, chef : nul ne peut plus rien m'apprendre.";
        if (t.memoire.getLong("formation_fin") > 0) return "Je suis déjà en apprentissage, chef.";
        long prix = Etoiles.prix(t.niveau, reglage().prix_formation);
        if (prix > 0 && !Reglages.payer(chef.getInventory(), prix, s -> { chef.getInventory().add(s); return s; }, s -> chef.drop(s, false)))
            return "Le maître demande " + Reglages.texte(prix) + " pour m'instruire, chef. Il te faut cette somme sur toi.";
        t.memoire.putLong("formation_fin", t.level().getGameTime() + reglage().minutes_formation * Etoiles.MINUTE);
        t.memoire.remove("formation_partie");
        if (!(t.action instanceof Actions.Attaquer)) t.finirAction("ordre");
        return "Grand merci, chef ! Je m'en vais étudier mon métier auprès d'un maître. Je reviens à l'ouvrage dans un moment, plus habile.";
    }

    /**
     * En apprentissage ({@link Cerveau#decider}) : il gagne son poste une fois, puis étudie sans travailler jusqu'au terme ;
     * au terme, une étoile de plus. Vrai tant qu'il étudie (il ne fait rien d'autre).
     */
    static boolean etudier(Travailleur t) {
        long fin = t.memoire.getLong("formation_fin");
        if (fin <= 0) return false;
        if (t.level().getGameTime() >= fin) {
            t.memoire.remove("formation_fin");
            t.memoire.remove("formation_partie");
            monter(t, true);
            return false;
        }
        if (t.bureau != null && !t.memoire.getBoolean("formation_partie")) {
            t.memoire.putBoolean("formation_partie", true); // une seule marche : s'il n'y arrive pas, il étudie où il est
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 16) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3)); return true; }
        }
        Cerveau.attendre(t, 10);
        return true;
    }
}

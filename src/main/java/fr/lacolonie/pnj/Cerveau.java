package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Le cerveau commun à tous les PNJ employés, appelé toutes les demi-secondes quand le PNJ n'a pas d'action en
 * cours. Il s'occupe de ce que tout PNJ fait, quel que soit son métier (grève, fuite, faim, paie, ordres, retour à
 * temps, repos, sac plein, commande terminée), puis laisse la main au métier.
 * <p>
 * Où travailler, c'est le PNJ qui le décide. Son espace de vie, c'est 50 blocs autour du bureau. Pour une sortie
 * à la journée, il rentre avant la fin de ses horaires. Si ce qu'il cherche est plus loin, il part de lui-même en
 * expédition : il campe la nuit, se fatigue plus vite, et rentre au plus tard au bout de la durée réglée au bureau.
 */
final class Cerveau {
    private Cerveau() {}

    static final Deque<String> JOURNAL = new ArrayDeque<>();

    static synchronized void journal(String ligne) {
        JOURNAL.addFirst(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + ligne);
        while (JOURNAL.size() > 300) JOURNAL.removeLast();
        ColoniePnj.LOG.info("[pnj] {}", ligne);
    }

    /** Appelé toutes les demi-secondes pour chaque PNJ employé. */
    static void penser(Travailleur t) {
        Metier m = Metier.de(t.metier);
        if (m == null) return;
        try {
            decider(t, m);
        } catch (RuntimeException e) {
            journal("[" + t.prenom + " / " + t.metier + "] erreur : " + e);
            ColoniePnj.LOG.error("Cerveau de {} en erreur", t.prenom, e);
            t.pauseCerveau = 200;
        }
    }

    static void decider(Travailleur t, Metier m) {
        // Une longue marche vers plus loin dure des heures : la faim, l'épuisement, la nuit ou l'heure du retour l'arrêtent
        // (sans ça, Aliénor marchait affamée et épuisée vers le bout du monde).
        if (t.action instanceof Actions.Aller a && t.bureau != null && !t.chezLui(BlockPos.containing(a.cible))
                && (t.faim <= 8 || t.fatigue >= 100 || !t.enService() || t.doitRentrer())) t.finirAction("interrompu");
        // Une action est en cours : le métier peut seulement surveiller (le guerrier lâche tout s'il voit un monstre).
        if (t.action != null) { m.vigilance(t); return; }
        CompoundTag mem = t.memoire;

        // En grève : il attend au bureau que la caisse couvre ce qu'on lui doit.
        if (t.mode == Travailleur.Mode.GREVE) {
            long du = t.du();
            Bureau.Entite b = Bureau.entite(t);
            if (du > 0 && b != null && Reglages.valeur(b.caisse) >= du) { lancer(t, new Actions.ToucherPaie()); return; }
            if (t.bureau != null && t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 16) { lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3)); return; }
            parfois(t, "greve", "Toujours en grève. Il me faut " + Reglages.texte(du) + " dans la caisse du bureau.", true, 1800);
            attendre(t, 20);
            return;
        }

        // Attaqué : un combattant riposte, les autres s'enfuient.
        LivingEntity agresseur = t.getLastHurtByMob();
        if (agresseur != null && agresseur.isAlive() && !t.estDe(agresseur) && Monde.recent(t, t.getLastHurtByMobTimestamp())) {
            if (m.combattant) lancer(t, new Actions.Attaquer(agresseur));
            else { parfois(t, "fuite", "À l'aide ! On m'attaque !", false, 30); lancer(t, new Actions.Fuir(agresseur)); }
            return;
        }

        // La faim : il mange ce qu'il a ; s'il n'a rien, il se sert au dépôt ou le demande à son chef.
        if (t.faim <= 14 && t.manger()) return;
        if (t.faim <= 8) {
            Bureau.Entite b = Bureau.entite(t);
            if (b != null && b.compter(Actions.Filtre.de("nourriture")) > 0) lancer(t, new Actions.Prendre(Actions.Filtre.de("nourriture"), 16));
            else lancer(t, new Actions.Demander("J'ai faim, chef. Il me faudrait de quoi manger.", Actions.Filtre.de("nourriture"), 1));
            return;
        }

        boolean dehors = t.bureau != null && !t.chezLui(t.blockPosition());

        // La paie : il va la chercher à la caisse du bureau (parti loin, à son retour).
        if (t.paieDue() && !dehors) { lancer(t, new Actions.ToucherPaie()); return; }

        // Les ordres du chef.
        if (t.mode == Travailleur.Mode.SUIVRE) {
            if (!m.escorter(t)) lancer(t, new Actions.SuivreChef());
            return;
        }
        if (t.mode == Travailleur.Mode.ATTENDRE) { attendre(t, 2); return; }

        // Au travail. Sans bureau, pas de travail.
        if (t.bureau == null) {
            parfois(t, "bureau", "Il me faut un bureau pour travailler : pose-en un, puis rattache-moi dans son écran.", true, 1800);
            attendre(t, 20);
            return;
        }

        // Rentrer à temps : le soir après une sortie, au bout de la durée maximale d'une expédition.
        if (t.doitRentrer()) {
            if ("bloque".equals(t.resultat)) {
                BlockPos p = t.blockPosition();
                parfois(t, "perdu", "Je n'arrive pas à rentrer au bureau : je suis coincé en " + p.getX() + " " + p.getY() + " " + p.getZ() + ".", true, 1800);
            } else if (t.expedition) parfois(t, "rentre", "Mon expédition touche à sa fin : je rentre au bureau (" + (int) t.distanceAuBureau() + " blocs).", true, 1800);
            else parfois(t, "rentre", "La journée avance : je rentre au bureau.", false, 600);
            rentrer(t);
            return;
        }

        // Hors de ses heures, ou épuisé : il se repose.
        if (t.fatigue >= 100) mem.putBoolean("epuise", true);
        else if (t.fatigue <= 40) mem.remove("epuise");
        if (!t.enService() || mem.getBoolean("epuise")) {
            if (mem.getBoolean("epuise")) parfois(t, "epuise", "Je n'en peux plus, chef : je vais me reposer.", true, 1800);
            if (dehors) {
                // En expédition, il campe sur place ; à moins d'une heure de marche, il rentre plutôt dormir chez lui.
                if (Travailleur.heuresDeMarche(t.blockPosition(), t.bureau) < 1) { rentrer(t); return; }
                if (!mem.getBoolean("camp")) {
                    mem.putBoolean("camp", true);
                    parfois(t, "camp", "Je passe la nuit dehors, à " + (int) t.distanceAuBureau() + " blocs du bureau. Je serai de retour dans "
                            + duree(t.heuresRestantes()) + " au plus tard.", true, 600);
                }
                lancer(t, new Actions.Reposer());
                return;
            }
            if (Monde.libres(t) < 24 && !mem.getBoolean("sacVide")) {
                mem.putBoolean("sacVide", true);
                lancer(t, new Actions.Deposer(m.garder));
                return;
            }
            lancer(t, new Actions.Reposer());
            return;
        }
        mem.remove("sacVide");
        mem.remove("camp");

        // Sac plein : direction le dépôt du bureau, même de très loin.
        if (Monde.libres(t) == 0) {
            if ("depot_plein".equals(t.resultat)) {
                parfois(t, "depot", "Le dépôt du bureau est plein, chef ! Vide-le (ou colle un coffre au bureau), je ne peux plus rien ranger.", true, 900);
                attendre(t, 30);
                return;
            }
            lancer(t, new Actions.Deposer(m.garder));
            return;
        }

        // Loin de chez lui, s'il a dans son sac de quoi remplir la commande, il rentre la livrer.
        Metier.Commande c = Metier.commande(t);
        if (c != null && c.objet != null && dehors) {
            int fait = Metier.avancement(t);
            if (fait >= 0 && fait + t.compter(c.objet) >= t.commandeNombre) { lancer(t, new Actions.Deposer(m.garder)); return; }
        }

        // Commande remplie : en mode quantité, c'est fini ; en mode stock, il attend que le stock baisse.
        if (c != null && Metier.commandeRemplie(t)) {
            if (Monde.libres(t) < 24 && !mem.getBoolean("livre")) {
                mem.putBoolean("livre", true);
                lancer(t, new Actions.Deposer(m.garder));
                return;
            }
            int fait = Metier.avancement(t);
            if (t.commandeMode.equals("quantite"))
                parfois(t, "commande_finie", "Commande terminée : " + t.commandeNombre + " " + c.nom + ". Donne-moi la suivante dans le bureau.", true, 3600);
            else parfois(t, "stock_plein", "Le stock de " + c.nom + " est plein (" + fait + "/" + t.commandeNombre + "). Je reprends dès qu'il baisse.", true, 3600);
            if (dehors) rentrer(t); else attendre(t, 20);
            return;
        }
        mem.remove("livre");

        m.travailler(t);
    }

    // --- Outils pour les métiers --------------------------------------------------------------------

    static void lancer(Travailleur t, Actions.Action a) {
        if (t.action != null) t.action.fin(t);
        if (t.isSleeping() && !(a instanceof Actions.Reposer)) t.stopSleeping();
        if (!(a instanceof Actions.Demander) && !(a instanceof Actions.Attendre)) t.besoin = "";
        t.action = a;
        t.resultat = "";
    }

    static void attendre(Travailleur t, double secondes) { lancer(t, new Actions.Attendre((int) (secondes * 20))); }

    static void rentrer(Travailleur t) { lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3)); }

    /** Une horloge en secondes qui ne fait qu'avancer. */
    static long temps(Travailleur t) { return t.level().getGameTime() / 20; }

    /** Dit quelque chose au plus une fois toutes les {@code delai} secondes pour une même clé (évite de radoter). */
    static void parfois(Travailleur t, String cle, String texte, boolean auChef, int delai) {
        CompoundTag dits = t.memoire.getCompound("dits");
        long maintenant = temps(t);
        if (dits.contains(cle) && maintenant - dits.getLong(cle) < delai) return;
        dits.putLong(cle, maintenant);
        t.memoire.put("dits", dits);
        if (auChef) t.direAuChef(texte); else t.dire(texte);
    }

    /** Une durée en heures du jeu, en toutes lettres (« 5 h », « 2 jours »). */
    static String duree(double heures) {
        if (heures < 24) return Math.max(1, (int) heures) + " h";
        long j = Math.round(heures / 24);
        return j + (j > 1 ? " jours" : " jour");
    }

    enum Portee { JOURNEE, DEMAIN, EXPEDITION, TROP_LOIN }

    /**
     * Jusqu'où ce trajet l'emmène : faisable dans la journée, faisable demain dans une journée entière (il attend),
     * en expédition (il campera), ou trop loin même pour l'expédition la plus longue permise.
     */
    static Portee portee(Travailleur t, BlockPos dest) {
        if (t.peutAller(dest)) return Portee.JOURNEE;
        double h = Travailleur.heuresDeMarche(t.blockPosition(), dest) + Travailleur.heuresDeMarche(dest, t.bureau) + 1;
        if (!t.expedition && !t.chezLui(dest) && h <= t.heuresJourneeComplete() && t.chezLui(t.blockPosition())) return Portee.DEMAIN;
        double expedition = t.joursExpedition * 24.0 - (t.expedition && t.sortieDepuis >= 0 ? (t.level().getGameTime() - t.sortieDepuis) / 1000.0 : 0);
        return h <= expedition ? Portee.EXPEDITION : Portee.TROP_LOIN;
    }

    /**
     * Part vers une destination, en sortie à la journée ou en expédition s'il le faut (il prévient alors son chef).
     * Renvoie la portée : s'il ne part pas (DEMAIN, TROP_LOIN), c'est au métier de décider quoi faire.
     */
    static Portee partir(Travailleur t, BlockPos dest, String pourQuoi) {
        Portee p = portee(t, dest);
        if (p == Portee.EXPEDITION && !t.expedition) {
            t.expedition = true;
            t.direAuChef("Je pars en expédition " + pourQuoi + " : vers " + dest.getX() + " " + dest.getZ() + ", à " + (int) Math.sqrt(t.blockPosition().distSqr(dest))
                    + " blocs. Je serai de retour dans " + t.joursExpedition + (t.joursExpedition > 1 ? " jours" : " jour") + " au plus tard.");
        }
        if (p == Portee.JOURNEE || p == Portee.EXPEDITION) lancer(t, new Actions.Aller(new Vec3(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5), 4, true));
        return p;
    }

    /**
     * Le prochain endroit à explorer autour de {@code centre} pour trouver « cle » : une spirale de plus en plus
     * large (un pas = 40 blocs), reprise là où il l'avait laissée. Null quand il a fait le tour jusqu'à {@code rayonMax}
     * (la spirale repart alors de zéro).
     */
    static BlockPos pointAExplorer(Travailleur t, String cle, BlockPos centre, double rayonMax) {
        CompoundTag e = t.memoire.getCompound("explo_" + cle);
        if (!e.contains("r") || e.getLong("c") != centre.asLong()) {
            e = new CompoundTag();
            e.putLong("c", centre.asLong());
            e.putDouble("a", t.getRandom().nextDouble() * Math.PI * 2);
            e.putDouble("r", 16);
        }
        double r = e.getDouble("r"), a = e.getDouble("a") + 40 / r;
        r += 1600 / r / (2 * Math.PI); // 40 blocs plus loin à chaque tour
        if (r > rayonMax) { t.memoire.remove("explo_" + cle); return null; }
        e.putDouble("a", a);
        e.putDouble("r", r);
        t.memoire.put("explo_" + cle, e);
        int x = (int) Math.floor(centre.getX() + Math.cos(a) * r), z = (int) Math.floor(centre.getZ() + Math.sin(a) * r);
        int y = Monde.surface(t.level(), x, z);
        return new BlockPos(x, y == Integer.MIN_VALUE ? centre.getY() : y, z);
    }

    static void oublierExploration(Travailleur t, String cle) { t.memoire.remove("explo_" + cle); }
}

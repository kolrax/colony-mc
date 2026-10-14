package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Le cerveau commun à tous les PNJ employés, appelé toutes les demi-secondes quand le PNJ n'a pas d'action en
 * cours. Il s'occupe de ce que tout PNJ fait, quel que soit son métier (grève, fuite, faim, paie, ordres, retour à
 * temps, repos, sac plein, courrier, missions remplies), puis laisse la main au métier.
 * <p>
 * Où travailler, c'est le PNJ qui le décide. Son espace de vie, c'est 50 blocs autour du poste de travail. Pour une sortie
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
        // Ce que le chef lui a demandé (une posture, un geste, une tâche) : une attaque ou une grosse faim passent avant.
        if (t.action instanceof Consignes.Faire f && Consignes.interrompre(t, f)) t.finirAction("interrompu");
        // Frappé en pleine action (il attend, il marche, il travaille) : il la lâche tout de suite, pour fuir ou riposter
        // plus bas. Une seule fois par coup, sinon il casserait chaque action pendant 10 s. Pas s'il se bat ou fuit déjà,
        // ni si c'est son chef qui l'a frappé. Avant, il ne réagissait qu'entre deux actions et finissait à terre.
        LivingEntity coupDe = t.getLastHurtByMob();
        if (t.action != null && coupDe != null && coupDe.isAlive() && !t.estDe(coupDe) && Monde.recent(t, t.getLastHurtByMobTimestamp())
                && t.getLastHurtByMobTimestamp() != t.coupVu && !(t.action instanceof Actions.Fuir) && !(t.action instanceof Actions.Attaquer)
                && !(t.action instanceof Actions.Echapper e && !e.eau) // il sort de la lave, du feu ou du gravier d'abord ; juste en feu, il se défend
                && !(t.action instanceof Consignes.Faire f && f.e.comportement().equals("combat"))) {
            t.coupVu = t.getLastHurtByMobTimestamp();
            t.finirAction("interrompu");
        }
        // Un creeper tout près d'un ouvrier qui ne se bat pas : il s'en éloigne et appelle la garde (cahier 14.11), que le
        // guerrier entend (Guerrier.veille). Seulement s'il a où aller : sinon il ne lâcherait son travail que pour rien.
        if (!m.combattant && !(t.action instanceof Actions.Fuir) && !(t.action instanceof Actions.Echapper) && !t.isSleeping()) {
            var creeper = t.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, t.getBoundingBox().inflate(5, 2, 5),
                    c -> c.isAlive() && t.getSensing().hasLineOfSight(c)).stream().findFirst().orElse(null);
            Vec3 loin = creeper == null ? null : net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(t, 16, 7, creeper.position());
            if (loin != null) {
                t.memoire.putInt("alerte", creeper.getId());
                t.memoire.putLong("alerte_t", t.level().getGameTime());
                dire(t, "creeper", 20, "Un creeper ! À la garde !");
                Actions.Fuir f = new Actions.Fuir(creeper);
                f.refuge = loin;
                lancer(t, f);
                return;
            }
        }
        // Toutes les 5 s environ : ce que son corps et son humeur lui font dire (faim, fatigue, lit, blessure, moral).
        if ((t.tickCount + t.getId()) % 100 == 0) { Besoins.veiller(t); Humeur.veiller(t); } // et, de loin en loin, le bilan de son moral
        // Oisif près d'un autre ouvrier oisif de son chef : de temps en temps, ils bavardent un peu (Causeries).
        if (Causeries.essayer(t)) return;
        // Une action est en cours : le métier peut seulement surveiller (le guerrier lâche tout s'il voit un monstre).
        // Très grave en plein combat (sans consigne du chef) : il rompt et recule ; sinon, il ne cherche pas de nouveau combat.
        if (t.action != null) {
            if (!Blessures.horsDeCombat(t)) m.vigilance(t);
            else if (t.action instanceof Actions.Attaquer a) {
                dire(t, "fuite", 30, "Je suis trop mal en point pour me battre !");
                lancer(t, new Actions.Fuir(a.cible));
            }
            return;
        }
        // Resté quelque part d'où le chemin ordinaire ne sort pas (un enclos et son portillon) : il en sort d'abord.
        if (m.sortir(t)) return;
        CompoundTag mem = t.memoire;

        // Attaqué (même en grève) : un combattant riposte contre un monstre ou un autre PNJ, sinon il recule ; les autres
        // s'enfuient. Sans ordre de son chef, il ne frappe ni un joueur (une consigne, en guerre), ni une bête, ni un golem.
        LivingEntity agresseur = t.getLastHurtByMob();
        if (agresseur != null && agresseur.isAlive() && !t.estDe(agresseur) && Monde.recent(t, t.getLastHurtByMobTimestamp())) {
            if (m.combattant && Monde.combattable(agresseur) && !Blessures.horsDeCombat(t)) lancer(t, new Actions.Attaquer(agresseur));
            else {
                if (!m.combattant || Blessures.horsDeCombat(t)) dire(t, "fuite", 30, "À l'aide ! On m'attaque !");
                else if (agresseur instanceof Player) dire(t, "fuite", 30, "Arrêtez ! Je ne frapperai pas sans l'ordre de mon chef.");
                else dire(t, "fuite", 30, "Holà, tout doux ! Je ne lève pas mon arme sur une bête.");
                lancer(t, new Actions.Fuir(agresseur));
            }
            return;
        }

        // Blessé (moins de la moitié de sa vie), hors combat : il mange s'il a de quoi, pour reprendre des forces (le guerrier
        // a son propre soin, avec les potions et le dépôt : Guerrier.soigner).
        if (!m.combattant && t.getHealth() < t.getMaxHealth() * 0.5 && t.faim < 17 && t.manger()) {
            dire(t, "soin", 60, "Je panse mes plaies et je mange un morceau.");
            attendre(t, 2);
            return;
        }

        // À bout depuis des jours, après deux lettres au chef : il rend ce qu'il a pris au dépôt et s'en va (Humeur).
        if (Humeur.demissionner(t)) return;

        // En grève : il attend au poste que la caisse couvre ce qu'on lui doit.
        if (t.mode == Travailleur.Mode.GREVE) {
            long du = t.du();
            Bureau.Entite b = Bureau.entite(t);
            if (du > 0 && b != null && Reglages.valeur(b.caisse) >= du) { lancer(t, new Actions.ToucherPaie()); return; }
            if (t.bureau != null && t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 16) { lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3)); return; }
            ecrire(t, "greve", 1800, "toujours_greve", Reglages.texte(du));
            attendre(t, 20);
            return;
        }

        // La faim : il mange ce qu'il a ; s'il n'a rien, il se sert au dépôt ou le demande à son chef.
        if (t.faim <= 14 && t.manger()) return;
        if (t.faim <= 8) {
            Bureau.Entite b = Bureau.entite(t);
            if (b != null && b.compter(Actions.Filtre.de("nourriture")) > 0) lancer(t, new Actions.Prendre(Actions.Filtre.de("nourriture"), 16, 4)); // 4 de chaque sorte : des repas variés
            else lancer(t, new Actions.Demander("faim", Actions.Filtre.de("nourriture"), 1));
            return;
        }

        // Ce que le chef lui a demandé passe avant le reste (le courrier, la paie, le travail) : il le fait, ou le tient jusqu'à nouvel ordre.
        if (Consignes.executer(t)) return;

        boolean dehors = t.bureau != null && !t.chezLui(t.blockPosition());

        // Le courrier : lire au poste les lettres de son chef, y poser les siennes.
        boolean libre = t.mode == Travailleur.Mode.TRAVAIL && t.bureau != null && !dehors; // pas s'il te suit ou t'attend
        if (libre && !t.lettresALire.isEmpty()) { lancer(t, new Actions.LireLettres()); return; }
        if (libre && !t.lettresADeposer.isEmpty()) { lancer(t, new Actions.PorterLettres()); return; }

        // La paie : il va la chercher à la caisse du poste (parti loin, à son retour).
        // « Attends ici » : il ne bouge pas ; mais mis à attendre au poste après s'être perdu (Coince), il y touche sa paie.
        if (t.paieDue() && !dehors && (t.mode != Travailleur.Mode.ATTENDRE || t.coince.attenteAuto)) { lancer(t, new Actions.ToucherPaie()); return; }

        // Les ordres du chef.
        if (t.mode == Travailleur.Mode.SUIVRE) {
            if (Blessures.horsDeCombat(t) || !m.escorter(t)) lancer(t, new Actions.SuivreChef());
            return;
        }
        if (t.mode == Travailleur.Mode.ATTENDRE) { attendre(t, 2); return; }

        // Au travail. Sans poste, pas de travail.
        if (t.bureau == null) {
            ecrire(t, "bureau", 1800, "sans_poste");
            attendre(t, 20);
            return;
        }

        // Rappelé au poste (« rentre ! », de vive voix ou par lettre).
        if (mem.getBoolean("rappel")) {
            if (dehors || t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 25) { rentrer(t); return; }
            mem.remove("rappel");
            if (mem.getBoolean("attendre_apres")) { mem.remove("attendre_apres"); t.mode = Travailleur.Mode.ATTENDRE; t.majNom(); }
            t.dire("Me voilà au poste, chef.");
            attendre(t, 2);
            return;
        }

        // Rentrer à temps : le soir après une sortie, au bout de la durée maximale d'une expédition.
        if (t.doitRentrer()) {
            if ("bloque".equals(t.resultat)) ecrire(t, "perdu", 1800, "coince", t.blockPosition().toShortString());
            else if (t.expedition) ecrire(t, "rentre", 1800, "fin_expedition", (int) t.distanceAuBureau());
            else dire(t, "rentre", 600, "La journée avance : je rentre au poste.");
            rentrer(t);
            return;
        }

        // Hors de ses heures, ou épuisé : il se repose.
        if (t.fatigue >= 100) mem.putBoolean("epuise", true);
        else if (t.fatigue <= 40) mem.remove("epuise");
        if (!t.enService() || mem.getBoolean("epuise")) {
            if (mem.getBoolean("epuise")) ecrire(t, "epuise", 1800, "epuise");
            if (dehors) {
                // En expédition, il campe sur place ; à moins d'une heure de marche, il rentre plutôt dormir chez lui.
                if (Travailleur.heuresDeMarche(t.blockPosition(), t.bureau) < 1) { rentrer(t); return; }
                if (!mem.getBoolean("camp")) {
                    mem.putBoolean("camp", true);
                    ecrire(t, "camp", 600, "camp", (int) t.distanceAuBureau(), duree(t.heuresRestantes()));
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

        // Sac plein : direction le dépôt du poste, même de très loin.
        if (Monde.libres(t) == 0) {
            if ("depot_plein".equals(t.resultat)) {
                ecrire(t, "depot", UN_JOUR, "depot_plein");
                attendre(t, 30);
                return;
            }
            lancer(t, new Actions.Deposer(m.garder));
            return;
        }

        // Ses missions : la première qui n'est pas remplie.
        t.nettoyerMissions();
        Travailleur.Mission mission = t.missionActive();
        Metier.Commande c = mission == null ? null : Metier.catalogue(t.metier, mission.id);

        // Loin de chez lui, s'il a dans son sac de quoi finir une quantité, il rentre la livrer.
        if (c != null && c.objet != null && dehors && mission.quantite() && mission.livre + t.compter(c.objet) >= mission.nombre) {
            lancer(t, new Actions.Deposer(m.garder));
            return;
        }

        // Toutes ses missions sont remplies (des stocks pleins) : il attend que le stock baisse.
        if (mission == null && !t.missions.isEmpty() && !m.enCours(t)) {
            if (Monde.libres(t) < 24 && !mem.getBoolean("livre")) {
                mem.putBoolean("livre", true);
                lancer(t, new Actions.Deposer(m.garder));
                return;
            }
            Travailleur.Mission premiere = t.missions.get(0);
            Metier.Commande cp = Metier.catalogue(t.metier, premiere.id);
            if (cp != null) ecrire(t, "stock_plein", 3600, "stock_plein", cp.nom.toLowerCase(), Math.max(0, Metier.avancement(t, premiere)), premiere.nombre);
            if (dehors) rentrer(t); else attendre(t, 20);
            return;
        }
        mem.remove("livre");

        // Longtemps au travail, le moral moyen ou bas : une courte pause au poste, puis il reprend (loisirs, lot 5).
        if (Besoins.pause(t)) return;

        // Blessé très gravement : ni mine ni combat, il se repose au poste (Blessures).
        if (Blessures.auRepos(t, m)) return;

        m.travailler(t);
    }

    // --- Outils pour les métiers --------------------------------------------------------------------

    static void lancer(Travailleur t, Actions.Action a) {
        if (t.action != null) t.action.fin(t);
        if (!(a instanceof Consignes.Faire)) t.setGeste("");
        if (t.isSleeping() && !(a instanceof Actions.Reposer)) t.stopSleeping();
        if (!(a instanceof Actions.Demander) && !(a instanceof Actions.Attendre)) t.besoin = "";
        t.action = a;
        t.resultat = "";
    }

    static void attendre(Travailleur t, double secondes) { lancer(t, new Actions.Attendre((int) (secondes * 20))); }

    static void rentrer(Travailleur t) { lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3)); }

    /** Un jour du jeu, en secondes de {@link #temps} : le plus court intervalle entre deux lettres bavardes. */
    static final int UN_JOUR = 1200;

    /** Une horloge en secondes qui ne fait qu'avancer. */
    static long temps(Travailleur t) { return t.level().getGameTime() / 20; }

    /** Vrai au plus une fois toutes les {@code delai} secondes pour une même clé (pour ne pas radoter). */
    static boolean parfois(Travailleur t, String cle, int delai) {
        CompoundTag dits = t.memoire.getCompound("dits");
        long maintenant = temps(t);
        if (dits.contains(cle) && maintenant - dits.getLong(cle) < delai) return false;
        dits.putLong(cle, maintenant);
        t.memoire.put("dits", dits);
        return true;
    }

    /** Il le dit (bulle au-dessus de sa tête), au plus une fois par délai. */
    static void dire(Travailleur t, String cle, int delai, String texte) { if (parfois(t, cle, delai)) t.dire(texte); }

    /** Il l'écrit à son chef (ou le lui dit, s'il est là), au plus une fois par délai. */
    static void ecrire(Travailleur t, String cle, int delai, String type, Object... vals) { if (parfois(t, cle, delai)) Courrier.ecrire(t, type, vals); }

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
            Courrier.ecrire(t, "depart_expedition", pourQuoi, Messages.direction(t.blockPosition(), dest) + " (vers " + dest.getX() + " " + dest.getZ() + ")",
                    (int) Math.sqrt(t.blockPosition().distSqr(dest)), t.joursExpedition + (t.joursExpedition > 1 ? " jours" : " jour"));
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

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le deuil et l'enterrement (lot 5, cahier 14.13). Le coup au moral est donné par {@link Humeur#deuil} ; ici, la suite :
 * <ul>
 * <li>la nouvelle : chaque compagnon la dit ({@code deuil_apprend}) dès que le chef est là, et s'il est absent à la mort,
 * un compagnon lui écrit au poste ;</li>
 * <li>les souvenirs : les jours suivants, une remarque sur le mort passe avant les autres ({@link Remarques}) ;</li>
 * <li>l'enterrement, sur ordre du chef (« on va enterrer Bertrand ») : les ouvriers du chef à {@code Reglages.deuil_rayon}
 * blocs de la tombe y marchent, se tiennent autour, l'un dit quelques mots, les autres se recueillent ; à la fin, le moral
 * perdu par cette mort remonte de moitié (une fois par mort) et le deuil pèse moins aux bilans suivants ;</li>
 * <li>la proposition : un jour du jeu sans enterrement, un ouvrier libre le propose au chef, une seule fois par mort.</li>
 * </ul>
 * La tombe est le coffre de ses affaires (ou, sans affaires, le lieu de sa mort), gardée dans l'effectif du {@link Courrier} :
 * si le coffre a été vidé ou cassé, l'enterrement se fait au même endroit. Rien n'est cherché à chaque tick : le cerveau
 * appelle {@link #veiller} toutes les 5 s, et l'ordre fait une seule recherche des ouvriers du chef.
 */
final class Deuil {
    private Deuil() {}

    static final String SOUVENIR = "deuil_souvenir";
    /** Au bout d'un jour du jeu sans enterrement, il peut le proposer (pendant deux jours encore). */
    static final long JOUR = 24000;
    /** Le temps de venir jusqu'à la tombe : passé ce délai, la cérémonie commence avec ceux qui sont là (en ticks). */
    static final int MARCHE = 90 * 20;
    /** Pas plus de tant d'ouvriers autour d'une tombe. */
    static final int PLACES = 16;

    /** Les enterrements en cours, par mort (non sauvegardés : un redémarrage les interrompt). */
    static final Map<UUID, Ceremonie> EN_COURS = new HashMap<>();

    static void oublier() { EN_COURS.clear(); }

    // --- La mort et la nouvelle ----------------------------------------------------------------------

    /** Depuis {@link Humeur#deuil}, pour chaque compagnon : ce qu'il a perdu pour ce mort (rendu à moitié par l'enterrement), et la nouvelle à dire. */
    static void noter(Travailleur t, Travailleur mort, int coup) {
        CompoundTag d = t.memoire.getCompound("deuils");
        while (d.size() >= 5) { // les plus anciens s'oublient
            String vieux = d.getAllKeys().stream().min(Comparator.comparingLong(k -> d.getCompound(k).getLong("le"))).orElseThrow();
            d.remove(vieux);
        }
        CompoundTag x = new CompoundTag();
        x.putInt("coup", coup);
        x.putLong("le", t.level().getGameTime());
        d.put(mort.getUUID().toString(), x);
        t.memoire.put("deuils", d);
        t.memoire.putString("deuil_apprend", mort.prenom);
    }

    /** Il vient de mourir (après {@link Humeur#deuil}) : sa tombe est notée ; si son chef est absent, un compagnon lui écrit. */
    static void mort(Travailleur mort, BlockPos tombe) {
        if (mort.chef == null || !(mort.level() instanceof ServerLevel sl)) return;
        Courrier c = Courrier.de(sl.getServer());
        var eff = c.effectifs.get(mort.chef);
        Courrier.Membre m = eff == null ? null : eff.get(mort.getUUID());
        if (m != null) {
            m.tombe = tombe.immutable();
            m.tombeMonde = sl.dimension().location().toString();
            m.mortLe = sl.getGameTime();
            m.enterre = m.propose = false;
            c.setDirty();
        }
        if (mort.chefJoueur() != null) return;
        for (Travailleur t : ColoniePnj.pnjDe(sl.getServer(), mort.chef))
            if (t != mort && !t.aTerre) { Courrier.ecrire(t, "deuil", mort.prenom); return; }
    }


    /** Ceux qui entendent la nouvelle : à tant de blocs de celui qui la dit, ils ne la redisent pas. */
    static final int VOISINS = 16;
    /** Le chef, pour l'entendre : dans le même monde, à tant de blocs au plus. */
    static final int PRES_DU_CHEF = 24;
    /** Le « oui » du chef à une proposition d'enterrement : dans les 2 minutes (en ticks). */
    static final int REPONSE = 2400;

    /** Toutes les 5 s, depuis le cerveau : la nouvelle (le chef près de lui), sinon, le moment venu, proposer l'enterrement. */
    static void veiller(Travailleur t) {
        if (t.chef == null || t.mode == Travailleur.Mode.CHOMEUR || t.aTerre || t.isSleeping() || t.muet() || t.menace()) return;
        String nom = t.memoire.getString("deuil_apprend");
        if (!nom.isEmpty()) {
            ServerPlayer chef = t.chefJoueur();
            if (chef == null || chef.level() != t.level() || chef.distanceTo(t) > PRES_DU_CHEF) return; // il le dira au chef quand il le verra
            t.memoire.remove("deuil_apprend");
            dire(t, "deuil_apprend", nom);
            // Ses voisins l'ont entendu : un seul la dit (sans quoi chacun répéterait la même nouvelle).
            for (Travailleur o : t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(VOISINS),
                    o -> o != t && t.chef.equals(o.chef) && nom.equals(o.memoire.getString("deuil_apprend"))))
                o.memoire.remove("deuil_apprend");
            return;
        }
        proposer(t);
    }

    /** Ses compagnons en reparlent : un mort de moins de {@link Moral#DEUIL_RECENT} jours, dont la nouvelle est déjà dite. */
    static boolean souvenir(Travailleur t) {
        return t.memoire.contains("deuil_le") && t.jour() - t.memoire.getLong("deuil_le") <= Moral.DEUIL_RECENT && !t.memoire.contains("deuil_apprend");
    }

    /** Un jour sans enterrement : un ouvrier libre, le chef près de lui, le propose (une seule fois par mort, tous ouvriers confondus). */
    static void proposer(Travailleur t) {
        if (t.memoire.getBoolean("revolte") || !(t.level() instanceof ServerLevel sl)) return;
        ServerPlayer chef = t.chefJoueur();
        if (chef == null || chef.level() != t.level() || chef.distanceTo(t) > PRES_DU_CHEF) return;
        var eff = Courrier.de(sl.getServer()).effectifs.get(t.chef);
        if (eff == null) return;
        long maintenant = sl.getGameTime();
        for (var e : eff.entrySet()) {
            Courrier.Membre m = e.getValue();
            long depuis = maintenant - m.mortLe;
            if (m.tombe == null || m.enterre || m.propose || depuis < JOUR || depuis > 3 * JOUR || EN_COURS.containsKey(e.getKey())) continue;
            if (!Remarques.libre(t)) return; // occupé, en danger : un autre (ou lui, plus tard) le proposera
            m.propose = true;
            Courrier.de(sl.getServer()).setDirty();
            t.memoire.putLong("enterrement_propose", maintenant); // le chef peut répondre « oui » (repondre)
            t.memoire.putString("enterrement_propose_id", e.getKey().toString());
            dire(t, "enterrement_propose", m.nom);
            return;
        }
    }

    /**
     * Depuis {@link Travailleur#obeir} : « oui » à sa proposition (dans les 2 minutes, sans autre ordre) lance l'enterrement ;
     * « non », « arrête » en pleine cérémonie l'arrête pour tous. Sinon null : la phrase suit son cours.
     */
    static String repondre(Travailleur t, Ordres.Lecture l) {
        String n = Ordres.normaliser(l.texte);
        if (t.action instanceof Enterrement e && (l.intentions.contains(Ordres.Intention.NON) || l.intentions.contains(Ordres.Intention.ARRETER_SUIVRE)
                || Ordres.dit(n, "arrete", "arretez", "stop", "halte", "assez"))) {
            e.c.annuler();
            return Messages.corps(t, "dit_enterrement_annule");
        }
        if (!t.memoire.contains("enterrement_propose")) return null;
        long depuis = t.level().getGameTime() - t.memoire.getLong("enterrement_propose");
        String id = t.memoire.getString("enterrement_propose_id");
        if (depuis > REPONSE) { t.memoire.remove("enterrement_propose"); t.memoire.remove("enterrement_propose_id"); return null; }
        if (!l.intentions.contains(Ordres.Intention.OUI) || !Obeissance.sansOrdre(l) || t.enAttente != null || !(t.level() instanceof ServerLevel sl)) return null;
        t.memoire.remove("enterrement_propose");
        t.memoire.remove("enterrement_propose_id");
        var eff = Courrier.de(sl.getServer()).effectifs.get(t.chef);
        UUID mort;
        try { mort = UUID.fromString(id); } catch (IllegalArgumentException x) { return null; }
        Courrier.Membre m = eff == null ? null : eff.get(mort);
        return m == null || m.tombe == null ? null : ordonner(t, mort, m);
    }

    /** Sa réplique {@code cle} (relations.txt), avec {mort} et {chef}. */
    static boolean dire(Travailleur t, String cle, String mort) {
        Map<String, String> v = new HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        v.put("mort", mort);
        String s = Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble);
        if (s != null) t.dire(s);
        return s != null;
    }

    // --- L'enterrement ---------------------------------------------------------------------------------

    /**
     * Le chef ordonne l'enterrement (le mort nommé, sinon le dernier qui n'est pas enterré) : la réponse de celui à qui il
     * parle. Un compagnon vivant nommé (« enterre Odon ») : il le dit bien en vie.
     */
    static String ordonner(Travailleur t, String texte) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return Messages.corps(t, "dit_enterrement_rien");
        var eff = Courrier.de(sl.getServer()).effectifs.get(t.chef);
        String n = Ordres.normaliser(texte);
        UUID id = null, cite = null;
        Courrier.Membre choisi = null, nomme = null;
        String vivant = null;
        boolean fait = false;
        if (eff != null) for (var e : eff.entrySet()) {
            Courrier.Membre m = e.getValue();
            boolean dit = !m.nom.isEmpty() && Ordres.dit(n, Ordres.normaliser(m.nom).strip());
            if (m.tombe == null) {
                if (dit && m.mort.isEmpty() && !m.nom.equals(t.prenom)) vivant = m.nom;
                continue;
            }
            if (dit && (nomme == null || m.mortLe > nomme.mortLe)) { nomme = m; cite = e.getKey(); }
            if (m.enterre) fait = true;
            else if (choisi == null || m.mortLe > choisi.mortLe) { choisi = m; id = e.getKey(); }
        }
        if (nomme != null) { choisi = nomme; id = cite; }
        else if (vivant != null) return Messages.corps(t, "dit_enterrement_vivant", vivant);
        if (choisi == null) return Messages.corps(t, fait ? "dit_enterrement_fait" : "dit_enterrement_rien");
        return ordonner(t, id, choisi);
    }

    /** L'enterrement de ce mort-là : déjà en cours, déjà fait, ou on y va. */
    static String ordonner(Travailleur t, UUID id, Courrier.Membre choisi) {
        MinecraftServer s = t.level().getServer();
        Ceremonie c = EN_COURS.get(id);
        if (c != null && !c.purger(c.monde.getGameTime())) c = null; // plus personne, ou trop longue : on la recommence
        if (c != null) return Messages.corps(t, c.gens.stream().anyMatch(x -> x.t == t) ? "dit_enterrement" : "dit_enterrement_autres");
        if (choisi.enterre) return Messages.corps(t, "dit_enterrement_fait");
        ResourceLocation rl = ResourceLocation.tryParse(choisi.tombeMonde);
        ServerLevel monde = rl == null || s == null ? null : s.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
        if (monde == null) return Messages.corps(t, "dit_enterrement_rien");
        Ceremonie nouvelle = new Ceremonie(id, choisi, monde, Vec3.atBottomCenterOf(choisi.tombe), monde.getGameTime());
        int rayon = Reglages.actuel.deuil_rayon;
        Vec3 lieu = nouvelle.lieu;
        List<Travailleur> gens = ColoniePnj.pnjDe(s, t.chef).stream()
                .filter(o -> o.level() == monde && o.position().distanceToSqr(lieu) <= (double) rayon * rayon && peutVenir(o, t))
                .sorted(Comparator.comparingDouble(o -> o.position().distanceToSqr(lieu))).limit(PLACES).toList();
        if (gens.isEmpty()) return Messages.corps(t, "dit_enterrement_loin");
        EN_COURS.put(id, nouvelle);
        for (Travailleur o : gens) nouvelle.gens.add(new Enterrement(nouvelle, o));
        for (Enterrement e : List.copyOf(nouvelle.gens)) Cerveau.lancer(e.t, e);
        if (!gens.contains(t)) return Messages.corps(t, "dit_enterrement_autres");
        return Messages.corps(t, gens.size() == 1 ? "dit_enterrement_seul" : "dit_enterrement");
    }

    /** Il peut venir : employé, debout, ni en danger, ni en révolte, ni sur le départ ; endormi, seulement si c'est à lui qu'on parle. */
    static boolean peutVenir(Travailleur o, Travailleur appele) {
        return o.isAlive() && !o.aTerre && o.mode != Travailleur.Mode.CHOMEUR && !o.enDanger() && !(o.action instanceof Enterrement)
                && (!o.isSleeping() || o == appele)
                && !o.memoire.getBoolean("revolte") && !o.memoire.getBoolean("fuite") && !o.memoire.getBoolean("demission");
    }

    /** Ce qu'il retrouve à la fin : la moitié du moral perdu pour ce mort (une fois), et un deuil qui pèse moins. */
    static void apaiser(Travailleur t, Ceremonie c) {
        CompoundTag d = t.memoire.getCompound("deuils");
        String k = c.mort.toString();
        if (d.contains(k)) {
            t.changerMoral(Moral.apaisement(d.getCompound(k).getInt("coup")));
            d.remove(k);
            t.memoire.put("deuils", d);
        }
        if (c.nom.equals(t.memoire.getString("deuil_nom"))) t.memoire.putBoolean("deuil_enterre", true);
    }

    /** Un enterrement : le mort, sa tombe, ceux qui y viennent ; il commence quand tous sont là (ou au bout de {@link #MARCHE}). */
    static final class Ceremonie {
        final UUID mort;
        final Courrier.Membre membre;
        final String nom;
        final ServerLevel monde;
        final Vec3 lieu;
        final long ordre;
        final List<Enterrement> gens = new ArrayList<>();
        long debut = -1;
        Enterrement orateur;
        /** Le chef l'a arrêtée (« non », « arrête ») : chacun repart, sans rien en recevoir. */
        boolean annulee;

        Ceremonie(UUID mort, Courrier.Membre membre, ServerLevel monde, Vec3 lieu, long ordre) {
            this.mort = mort;
            this.membre = membre;
            this.nom = membre.nom;
            this.monde = monde;
            this.lieu = lieu;
            this.ordre = ordre;
        }

        int duree() { return Reglages.actuel.deuil_secondes_enterrement * 20; }

        /**
         * Ceux qui n'y sont plus (morts, partis du monde, passés à autre chose) sortent de la liste. Faux, et retirée des
         * enterrements en cours, si personne ne reste ou si elle dure plus que prévu (marche, cérémonie et une marge).
         */
        boolean purger(long maintenant) {
            gens.removeIf(e -> !e.fini && (!e.t.isAlive() || e.t.isRemoved() || e.t.action != e));
            boolean vivante = !annulee && !gens.isEmpty() && maintenant - ordre <= MARCHE + duree() + 400L;
            if (!vivante && EN_COURS.get(mort) == this) EN_COURS.remove(mort);
            return vivante;
        }

        /** Tous ceux qui restent sont arrivés, ou le temps de marche est passé et quelqu'un est là : elle commence. */
        void commencer(long maintenant) {
            if (debut >= 0) return;
            purger(maintenant);
            if (gens.stream().noneMatch(x -> x.arrive)) return;
            if (gens.stream().allMatch(x -> x.arrive) || maintenant - ordre >= MARCHE) {
                debut = maintenant;
                orateur = gens.stream().filter(x -> x.arrive).findFirst().orElseThrow();
            }
        }

        /** Le chef l'arrête : chacun repart (celui qui attendait retourne à sa place). */
        void annuler() {
            annulee = true;
            if (EN_COURS.get(mort) == this) EN_COURS.remove(mort);
        }

        /** Il a quitté la cérémonie (un coup, un danger, un ordre) : s'il n'en reste personne, elle s'arrête. */
        void quitter(Enterrement e) {
            gens.remove(e);
            if (gens.isEmpty() && EN_COURS.get(mort) == this) EN_COURS.remove(mort);
        }

        /** C'est fini : le mort est enterré. */
        void finir() {
            if (membre.enterre) return;
            membre.enterre = true;
            Courrier.de(monde.getServer()).setDirty();
            if (EN_COURS.get(mort) == this) EN_COURS.remove(mort);
        }
    }

    /**
     * Il marche jusqu'à la tombe, prend sa place autour (comme autour d'un blessé, {@link Blessures.Accourir}), tête basse ;
     * l'orateur dit quelques mots, les autres se recueillent tour à tour ; à la fin, il est apaisé et reprend son travail.
     * Celui qu'on avait mis à attendre retourne à sa place. Un coup, un danger ou un ordre l'interrompent comme toute action :
     * il n'en reçoit alors rien.
     */
    static final class Enterrement extends Actions.Action {
        final Ceremonie c;
        final Travailleur t;
        /** Mis à attendre quelque part (« attends ici ») : il y retourne ensuite. */
        final Vec3 retour;
        boolean arrive, parle, fini, rentre;

        Enterrement(Ceremonie c, Travailleur t) {
            super("enterrement");
            this.c = c;
            this.t = t;
            this.retour = t.mode == Travailleur.Mode.ATTENDRE ? t.position() : null;
        }

        int rang() { return Math.max(0, c.gens.indexOf(this)); }

        /** Sa place autour de la tombe : sur un cercle de 2,5 blocs (8 places), puis un second à 4 blocs. */
        Vec3 place() {
            int rang = rang(), n = Math.min(8, Math.max(3, c.gens.size()));
            double r = rang < 8 ? 2.5 : 4, a = (rang % 8) * 2 * Math.PI / n + (rang >= 8 ? Math.PI / 8 : 0);
            return c.lieu.add(Math.cos(a) * r, 0, Math.sin(a) * r);
        }

        Actions.Etat tick(Travailleur t) {
            if (rentre) return rentrer(t);
            if (t.level() != c.monde) return echec("loin");
            if (c.annulee) return finir(t);
            long maintenant = c.monde.getGameTime();
            if (!arrive) {
                Vec3 p = place();
                double d = t.position().distanceToSqr(c.lieu);
                if (t.position().distanceToSqr(p) > 1.2 * 1.2 && d > 2 * 2 && !coince() && age < MARCHE) { marcher(t, p, 1.0); return Actions.Etat.EN_COURS; }
                if (d > 6 * 6) { // pas de passage jusqu'à la tombe : il n'y vient pas (jamais téléporté)
                    t.dire(Messages.corps(t, "dit_enterrement_passage"));
                    return echec("tombe_inaccessible");
                }
                arrive = true;
                t.getNavigation().stop();
                t.setGeste("tete_basse");
            }
            t.getLookControl().setLookAt(c.lieu.add(0, 0.5, 0));
            c.commencer(maintenant);
            if (c.debut < 0) return Actions.Etat.EN_COURS;
            long depuis = maintenant - c.debut;
            int duree = c.duree();
            if (!parle && depuis >= quand(duree)) {
                parle = true;
                dire(t, c.orateur == this ? "enterrement_mots" : "enterrement_recueil", c.nom);
            }
            if (depuis < duree + 10L * rang()) return Actions.Etat.EN_COURS;
            fini = true;
            apaiser(t, c);
            c.finir();
            dire(t, "enterrement_apres", c.nom);
            return finir(t);
        }

        /** La cérémonie finie (ou arrêtée) : il reprend ; celui qui attendait retourne d'abord à sa place. */
        Actions.Etat finir(Travailleur t) {
            t.setGeste("");
            if (retour == null || t.mode != Travailleur.Mode.ATTENDRE || t.level() != c.monde) return Actions.Etat.OK;
            rentre = true;
            nouveauBut();
            return rentrer(t);
        }

        Actions.Etat rentrer(Travailleur t) {
            if (t.position().distanceToSqr(retour) <= 1.5 * 1.5 || coince() || age > MARCHE * 3) {
                t.getNavigation().stop();
                return Actions.Etat.OK;
            }
            marcher(t, retour, 1.0);
            return Actions.Etat.EN_COURS;
        }

        /** Quand il parle : l'orateur d'abord, puis les autres tour à tour, tous avant la fin. */
        int quand(int duree) {
            if (c.orateur == this) return 10;
            int autres = Math.max(1, c.gens.size() - 1), k = rang() - (c.gens.indexOf(c.orateur) < rang() ? 1 : 0);
            return 20 + (Math.max(0, k) + 1) * Math.max(1, duree - 30) / (autres + 1);
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.setGeste("");
            if (!fini) c.quitter(this);
        }
    }
}

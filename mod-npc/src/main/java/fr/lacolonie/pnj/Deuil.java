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

    /** Toutes les 5 s, depuis le cerveau : la nouvelle (le chef là), sinon, le moment venu, proposer l'enterrement. */
    static void veiller(Travailleur t) {
        if (t.chef == null || t.mode == Travailleur.Mode.CHOMEUR || t.aTerre || t.isSleeping() || t.muet() || t.menace()) return;
        String nom = t.memoire.getString("deuil_apprend");
        if (!nom.isEmpty()) {
            if (t.chefJoueur() == null) return; // il le dira quand le chef sera revenu
            t.memoire.remove("deuil_apprend");
            dire(t, "deuil_apprend", nom);
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
        if (!Remarques.libre(t) || t.memoire.getBoolean("revolte") || !(t.level() instanceof ServerLevel sl)) return;
        ServerPlayer chef = t.chefJoueur();
        if (chef == null || chef.level() != t.level() || chef.distanceTo(t) > 24) return;
        var eff = Courrier.de(sl.getServer()).effectifs.get(t.chef);
        if (eff == null) return;
        long maintenant = sl.getGameTime();
        for (var e : eff.entrySet()) {
            Courrier.Membre m = e.getValue();
            long depuis = maintenant - m.mortLe;
            if (m.tombe == null || m.enterre || m.propose || depuis < JOUR || depuis > 3 * JOUR || EN_COURS.containsKey(e.getKey())) continue;
            m.propose = true;
            Courrier.de(sl.getServer()).setDirty();
            dire(t, "enterrement_propose", m.nom);
            return;
        }
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

    /** Le chef ordonne l'enterrement (le mort nommé, sinon le dernier qui n'est pas enterré) : la réponse de celui à qui il parle. */
    static String ordonner(Travailleur t, String texte) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return Messages.corps(t, "dit_enterrement_rien");
        MinecraftServer s = sl.getServer();
        var eff = Courrier.de(s).effectifs.get(t.chef);
        String n = Ordres.normaliser(texte);
        UUID id = null, cite = null;
        Courrier.Membre choisi = null, nomme = null;
        boolean fait = false;
        if (eff != null) for (var e : eff.entrySet()) {
            Courrier.Membre m = e.getValue();
            if (m.tombe == null) continue;
            if (!m.nom.isEmpty() && Ordres.dit(n, Ordres.normaliser(m.nom).strip()) && (nomme == null || m.mortLe > nomme.mortLe)) { nomme = m; cite = e.getKey(); }
            if (m.enterre) fait = true;
            else if (choisi == null || m.mortLe > choisi.mortLe) { choisi = m; id = e.getKey(); }
        }
        if (nomme != null) { choisi = nomme; id = cite; }
        if (choisi == null) return Messages.corps(t, fait ? "dit_enterrement_fait" : "dit_enterrement_rien");
        if (choisi.enterre && !EN_COURS.containsKey(id)) return Messages.corps(t, "dit_enterrement_fait");
        Ceremonie c = EN_COURS.get(id);
        if (c != null) return Messages.corps(t, "dit_enterrement");
        ResourceLocation rl = ResourceLocation.tryParse(choisi.tombeMonde);
        ServerLevel monde = rl == null ? null : s.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
        if (monde == null) return Messages.corps(t, "dit_enterrement_rien");
        c = new Ceremonie(id, choisi, monde, Vec3.atBottomCenterOf(choisi.tombe), monde.getGameTime());
        int rayon = Reglages.actuel.deuil_rayon;
        Vec3 lieu = c.lieu;
        List<Travailleur> gens = new ArrayList<>(ColoniePnj.pnjDe(s, t.chef).stream()
                .filter(o -> o.level() == monde && o.position().distanceToSqr(lieu) <= (double) rayon * rayon && peutVenir(o))
                .sorted(Comparator.comparingDouble(o -> o.position().distanceToSqr(lieu))).limit(PLACES).toList());
        if (gens.isEmpty()) return Messages.corps(t, "dit_enterrement_loin");
        EN_COURS.put(id, c);
        for (int i = 0; i < gens.size(); i++) {
            Enterrement a = new Enterrement(c, i);
            c.gens.add(a);
            Cerveau.lancer(gens.get(i), a);
        }
        return Messages.corps(t, gens.contains(t) && gens.size() == 1 ? "dit_enterrement_seul" : "dit_enterrement");
    }

    /** Il peut venir : employé, debout, ni en danger, ni en révolte, ni sur le départ. */
    static boolean peutVenir(Travailleur o) {
        return o.isAlive() && !o.aTerre && o.mode != Travailleur.Mode.CHOMEUR && !o.enDanger() && !(o.action instanceof Enterrement)
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

        Ceremonie(UUID mort, Courrier.Membre membre, ServerLevel monde, Vec3 lieu, long ordre) {
            this.mort = mort;
            this.membre = membre;
            this.nom = membre.nom;
            this.monde = monde;
            this.lieu = lieu;
            this.ordre = ordre;
        }

        int duree() { return Reglages.actuel.deuil_secondes_enterrement * 20; }

        /** Tous ceux qui restent sont arrivés, ou le temps de marche est passé et quelqu'un est là : elle commence. */
        void commencer(long maintenant) {
            if (debut >= 0 || gens.stream().noneMatch(x -> x.arrive)) return;
            if (gens.stream().allMatch(x -> x.arrive) || maintenant - ordre >= MARCHE) {
                debut = maintenant;
                orateur = gens.stream().filter(x -> x.arrive).findFirst().orElseThrow();
            }
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
     * Un coup, un danger ou un ordre l'interrompent comme toute action : il n'en reçoit alors rien.
     */
    static final class Enterrement extends Actions.Action {
        final Ceremonie c;
        final int rang;
        boolean arrive, parle, fini;

        Enterrement(Ceremonie c, int rang) {
            super("enterrement");
            this.c = c;
            this.rang = rang;
        }

        /** Sa place autour de la tombe : sur un cercle de 2,5 blocs (8 places), puis un second à 4 blocs. */
        Vec3 place() {
            int n = Math.min(8, Math.max(3, c.gens.size()));
            double r = rang < 8 ? 2.5 : 4, a = (rang % 8) * 2 * Math.PI / n + (rang >= 8 ? Math.PI / 8 : 0);
            return c.lieu.add(Math.cos(a) * r, 0, Math.sin(a) * r);
        }

        Actions.Etat tick(Travailleur t) {
            if (t.level() != c.monde) return echec("loin");
            long maintenant = c.monde.getGameTime();
            if (!arrive) {
                Vec3 p = place();
                double d = t.position().distanceToSqr(c.lieu);
                if (t.position().distanceToSqr(p) > 1.2 * 1.2 && d > 2 * 2 && !coince() && age < MARCHE) { marcher(t, p, 1.0); return Actions.Etat.EN_COURS; }
                if (d > 6 * 6) return echec("bloque"); // pas de passage jusqu'à la tombe : il n'y vient pas (jamais téléporté)
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
            if (depuis < duree + 10L * rang) return Actions.Etat.EN_COURS;
            fini = true;
            apaiser(t, c);
            c.finir();
            dire(t, "enterrement_apres", c.nom);
            return Actions.Etat.OK;
        }

        /** Quand il parle : l'orateur d'abord, puis les autres tour à tour, tous avant la fin. */
        int quand(int duree) {
            if (c.orateur == this) return 10;
            int autres = Math.max(1, c.gens.size() - 1), k = c.gens.indexOf(this) - (c.gens.indexOf(c.orateur) < c.gens.indexOf(this) ? 1 : 0);
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

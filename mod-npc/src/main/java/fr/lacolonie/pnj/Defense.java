package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.util.Comparator;
import java.util.List;

/**
 * La défense de la colonie vue par les ouvriers qui ne se battent pas (lot 9, cahier 14.11). Toutes les demi-secondes,
 * depuis le cerveau, une seule petite recherche autour de lui (8 blocs) : un creeper en vue, il lâche son travail, s'en
 * éloigne et prévient le guerrier de son chef le plus proche ; à portée de voix (48 blocs), le guerrier répond et vient le
 * tuer ; plus loin (96 blocs au plus), l'ouvrier court vers lui, à l'opposé du creeper, et le guerrier l'apprend en le
 * voyant arriver ({@link Guerrier#veille}). Un autre monstre près du poste : il le signale (le guerrier vient de même). Le
 * travail reprend tout seul ensuite : la fuite est une action comme une autre.
 */
final class Defense {
    private Defense() {}

    /** Ce qu'un ouvrier remarque autour de lui. */
    static final double VUE = 8;
    /** Jusque-là, le guerrier l'entend crier. */
    static final double VOIX = 48;
    /** Jusque-là, il sait où trouver le guerrier et court le prévenir. */
    static final double LOIN = 96;

    /** Un ouvrier qui ne se bat pas voit un monstre : vrai s'il lâche ce qu'il fait (il fuit un creeper). */
    static boolean ouvrier(Travailleur t) {
        if (t.action instanceof Actions.Fuir || t.action instanceof Actions.Echapper || t.isSleeping() || t.aTerre) return false;
        List<LivingEntity> autour = t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(VUE, 3, VUE), Monde::hostile);
        if (autour.isEmpty()) return false;
        autour.sort(Comparator.comparingDouble(t::distanceToSqr));
        Creeper creeper = null;
        LivingEntity autre = null;
        for (LivingEntity e : autour) {
            if (!t.getSensing().hasLineOfSight(e)) continue;
            if (e instanceof Creeper c) { if (creeper == null) creeper = c; }
            else if (autre == null) autre = e;
        }
        if (creeper != null) {
            // Il fuit seulement s'il a où aller (sinon il ne lâcherait son travail que pour rien) ; il appelle la garde de toute façon.
            Vec3 loin = DefaultRandomPos.getPosAway(t, 16, 7, creeper.position());
            if (fuir(t, creeper, loin)) return true;
        }
        LivingEntity vu = autre;
        if (vu != null && t.bureau != null && t.chezLui(t.blockPosition()) && Cerveau.parfois(t, "monstre_vu", 60)) {
            signaler(t, vu);
            Besoins.dire(t, "monstre_vu", null);
        }
        return false;
    }

    /**
     * Il prévient la garde du creeper : de vive voix, ou en courant vers le guerrier s'il est plus loin ; et il s'en éloigne
     * ({@code loin}, null s'il n'a pas où aller). Vrai s'il fuit.
     */
    static boolean fuir(Travailleur t, Creeper c, Vec3 loin) {
        signaler(t, c);
        Actions.Fuir f = new Actions.Fuir(c);
        f.refuge = loin;
        Travailleur g = garde(t, LOIN);
        boolean parle = Cerveau.parfois(t, "creeper", 20);
        if (g != null && g.distanceTo(t) <= VOIX) {
            if (parle && !Besoins.dire(t, "creeper_alerte", g.prenom)) t.dire("Un creeper ! À la garde !");
            appeler(g, c);
        } else {
            if (parle && !Besoins.dire(t, "creeper_fuite", null)) t.dire("Un creeper ! À la garde !");
            if (g != null) {
                // Le guerrier est loin, mais pas du côté du creeper : il court le prévenir.
                Vec3 versG = g.position().subtract(t.position()), versC = c.position().subtract(t.position());
                if (versG.x * versC.x + versG.z * versC.z < 0) { f.refuge = g.position(); f.duree = 20 * 40; }
            }
        }
        if (f.refuge == null) return false;
        Cerveau.lancer(t, f);
        return true;
    }

    /** Il retient le monstre vu, pour la garde ({@link Guerrier#veille} le lit pendant 30 s). */
    static void signaler(Travailleur t, LivingEntity m) {
        t.memoire.putInt("alerte", m.getId());
        t.memoire.putLong("alerte_t", t.level().getGameTime());
    }

    /** Le guerrier de son chef le plus proche, à {@code r} blocs au plus ; null s'il n'y en a pas. */
    static Travailleur garde(Travailleur t, double r) {
        if (t.chef == null) return null;
        return t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(r, 24, r),
                        g -> g != t && g.isAlive() && "guerrier".equals(g.metier) && t.chef.equals(g.chef))
                .stream().min(Comparator.comparingDouble(t::distanceToSqr)).orElse(null);
    }

    /**
     * Le guerrier entend l'alerte : s'il est libre (au travail ou au repos, pas déjà en plein combat, pas à terre ni gardé
     * sur place par son chef), il répond et part tuer le monstre. Sinon, il le verra à sa prochaine veille.
     */
    static void appeler(Travailleur g, LivingEntity m) {
        if (g.mode != Travailleur.Mode.TRAVAIL || g.aTerre || Blessures.horsDeCombat(g)) return;
        // Sans arc, il ne va pas frapper un creeper au corps à corps : il le laisse s'éloigner.
        if (!Guerrier.aSaPortee(g, m)) { Cerveau.dire(g, "creeper_sans_arc", 60, "Sans arc, je ne m'approche pas de ça. Écartez-vous, qu'il s'en aille !"); return; }
        // Il y va déjà (sa veille l'a vu) : il répond seulement.
        if (g.action instanceof Actions.Attaquer a && a.cible == m) { if (Cerveau.parfois(g, "alerte_ok", 10)) Besoins.dire(g, "guerrier_alerte_ok", null); return; }
        if (g.action instanceof Actions.Attaquer a && a.cible.isAlive() && Monde.hostile(a.cible) || g.action instanceof Actions.Echapper
                || g.action instanceof Consignes.Faire f && f.e.comportement().equals("combat")) return;
        Besoins.dire(g, "guerrier_alerte_ok", null);
        Guerrier.armer(g);
        Cerveau.lancer(g, new Actions.Attaquer(m).portee(2.5 * Travailleur.ESPACE_DE_VIE));
    }
    // --- On frappe le chef (cahier 14.6) ------------------------------------------------------------------

    /** Jusque-là, ses gens voient qu'on frappe leur chef (et l'agresseur qui s'en éloigne autant est laissé). */
    static final double SCENE = 16;

    /** Le dernier coup de mêlée voulu de chaque joueur : qui il visait, et quand (un coup balayé touche les voisins sans les viser). */
    private static final java.util.Map<java.util.UUID, long[]> VISE = new java.util.HashMap<>();

    /** Un joueur frappe quelqu'un en mêlée (sa cible à lui, pas les voisins du coup balayé). */
    static void viser(AttackEntityEvent e) {
        if (!e.getEntity().level().isClientSide()) VISE.put(e.getEntity().getUUID(), new long[]{e.getTarget().getId(), e.getEntity().level().getGameTime()});
    }

    /**
     * Un joueur frappe un autre joueur : les ouvriers du frappé, à {@link #SCENE} blocs et qui voient la scène, réagissent
     * selon ce que le chef a dit plus tôt ({@link GardeChef}). Seulement un coup direct : sa main (le coup visé, pas le coup
     * balayé) ou un projectile qu'il a tiré ; ni épines, ni explosion, ni faux joueur (machine). Une seule recherche, au coup.
     */
    static void chefFrappe(LivingDamageEvent.Post e) {
        DamageSource s = e.getSource();
        if (!(e.getEntity() instanceof ServerPlayer chef) || chef instanceof FakePlayer || !(s.getEntity() instanceof Player a) || a instanceof FakePlayer
                || a == chef || !a.isAlive() || s.is(DamageTypes.THORNS) || s.is(DamageTypeTags.IS_EXPLOSION)) return;
        boolean tir = s.getDirectEntity() instanceof Projectile pr && pr.getOwner() == a;
        long[] v = VISE.get(a.getUUID());
        boolean main = s.getDirectEntity() == a && v != null && v[0] == chef.getId() && v[1] == chef.level().getGameTime();
        if (!tir && !main) return;
        for (Travailleur t : chef.level().getEntitiesOfClass(Travailleur.class, chef.getBoundingBox().inflate(SCENE, 8, SCENE),
                x -> x.isAlive() && chef.getUUID().equals(x.chef) && x.distanceTo(chef) <= SCENE))
            reagir(t, chef, a, main);
    }

    /**
     * Ce que fait un ouvrier qui voit {@code a} frapper son chef. Sur l'ordre « attaque quiconque me frappe », le guerrier
     * frappe un coup de main visé ; une flèche (qui visait peut-être autre chose) : seulement à la deuxième en 10 s.
     */
    static void reagir(Travailleur t, ServerPlayer chef, Player a, boolean main) {
        if (t.aTerre || t.isSleeping() || t.mode == Travailleur.Mode.GREVE || t.memoire.getBoolean("revolte")) return; // un révolté ne défend pas le chef
        // « Ne fais jamais rien à Nielscutt » : il laisse faire, sans un mot.
        if (GardeChef.epargne(t.memoire.getString("epargnes"), a.getGameProfile().getName())) return;
        if (!t.getSensing().hasLineOfSight(chef) && !t.getSensing().hasLineOfSight(a)) return;
        long maintenant = t.level().getGameTime();
        boolean encore = a.getStringUUID().equals(t.memoire.getString("chef_coup_par")) && maintenant - t.memoire.getLong("chef_coup_t") <= 200;
        t.memoire.putString("chef_coup_par", a.getStringUUID());
        t.memoire.putLong("chef_coup_t", maintenant);
        Metier m = Metier.de(t.metier);
        boolean combat = t.action instanceof Actions.Attaquer x && x.cible.isAlive() && (x.cible == a || Monde.hostile(x.cible))
                || t.action instanceof Consignes.Faire f && f.e.comportement().equals("combat");
        if (m == null || !m.combattant || Blessures.horsDeCombat(t) || combat) {
            // Les autres ouvriers : un mot, indigné ou effrayé selon leur caractère ; pas plus.
            if (!combat && Cerveau.parfois(t, "chef_frappe_vu", 30) && !Besoins.dire(t, "chef_frappe_vu", null)) t.dire("Hé ! On ne frappe pas le chef !");
            return;
        }
        // Il l'attendait quelque part : il y retourne ensuite (une consigne du chef, elle, reprend d'elle-même).
        Actions.Action ensuite = t.action instanceof Interposer i && i.agresseur == a ? i.ensuite
                : t.mode == Travailleur.Mode.ATTENDRE ? new Actions.Aller(t.position(), 1) : null;
        if (GardeChef.posture(t.memoire.getString("garde_chef")) == GardeChef.Posture.ATTAQUE && (main || encore)) {
            // L'ordre du chef, donné plus tôt : il frappe, jusqu'à ce que l'autre s'éloigne du chef, que le chef s'en aille ou dise « arrête ».
            if (!Besoins.dire(t, "chef_riposte", null)) t.dire("Tu as levé la main sur mon chef. À toi de goûter le fer !");
            Guerrier.armer(t);
            Actions.Attaquer x = new Actions.Attaquer(a).portee(SCENE).garder(chef);
            x.ensuite = ensuite;
            Cerveau.lancer(t, x);
            return;
        }
        // Sans ordre, ou « reste tranquille » : il se met entre eux et le met en garde, sans frapper.
        if (!(t.action instanceof Interposer i && i.agresseur == a)) {
            Interposer x = new Interposer(chef, a);
            x.ensuite = ensuite;
            Cerveau.lancer(t, x);
        }
        if (Cerveau.parfois(t, "chef_interpose", 5) && !Besoins.dire(t, "chef_interpose", null)) t.dire("Recule ! Ne touche plus à mon chef.");
    }

    /** Il se tient entre son chef et celui qui l'a frappé, face à lui, quelques secondes, sans frapper. */
    static final class Interposer extends Actions.Action {
        final ServerPlayer chef;
        final Player agresseur;
        Vec3 place;

        Interposer(ServerPlayer chef, Player agresseur) { super("s_interposer"); this.chef = chef; this.agresseur = agresseur; mobile = true; }

        @Override
        Actions.Etat tick(Travailleur t) {
            if (age > 20 * 10 || !agresseur.isAlive() || !chef.isAlive() || chef.isRemoved() || agresseur.level() != t.level() || chef.level() != t.level()
                    || agresseur.distanceTo(chef) > SCENE) return Actions.Etat.OK;
            t.getLookControl().setLookAt(agresseur);
            if (place == null || age % 10 == 0) {
                // À un pas et demi du chef, du côté de l'agresseur (au milieu s'ils sont tout près).
                Vec3 c = chef.position(), d = agresseur.position().subtract(c).multiply(1, 0, 1);
                double l = d.length();
                place = l < 0.1 ? c : c.add(d.scale(Math.min(1.5, l / 2) / l));
            }
            if (t.position().distanceToSqr(place) > 1) { t.setGeste(""); marcher(t, place, 1.3); }
            else { t.getNavigation().stop(); t.setGeste("en_garde"); }
            return Actions.Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.setGeste("");
        }
    }
}

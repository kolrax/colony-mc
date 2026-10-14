package fr.lacolonie.pnj;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

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
}

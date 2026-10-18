package fr.lacolonie.pnj;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Fuir avant d'être à terre (plan E2). Sous la moitié de sa vie ({@link Reglages#fuite_vie}), un ouvrier qui n'est pas
 * guerrier lâche le travail ({@link Repli}) : il s'éloigne du monstre qui l'a frappé (vers son poste quand le poste n'est
 * pas du côté du monstre), mange ce qu'il a dans son sac, et attend d'aller mieux. Le guerrier garde son comportement
 * (Guerrier, Armee). Et, pour tous, la guérison d'un joueur : hors combat et nourri, un point de vie toutes les
 * {@link Reglages#regen_secondes} secondes ({@link #regenerer}), en plus de la guérison lente d'avant.
 */
final class Fuite {
    private Fuite() {}

    /** À chaque tick, debout : la guérison, et une fois toutes les 10 ticks, le repli s'il est trop blessé. Presque rien à lire. */
    static void tick(Travailleur t) {
        if (t.mode == Travailleur.Mode.CHOMEUR) return;
        regenerer(t);
        if ((t.tickCount + t.getId()) % 10 == 5 && doitFuir(t)) {
            if (t.action != null) t.finirAction("danger");
            Cerveau.dire(t, "fuite", 30, "Je suis trop mal en point : je me mets à l'abri.");
            Cerveau.lancer(t, new Repli());
        }
    }

    /** Frappé par une créature il y a moins de 10 secondes (0 : jamais). */
    static boolean frappeRecemment(Travailleur t) {
        int quand = t.getLastHurtByMobTimestamp();
        return quand > 0 && Monde.recent(t, quand);
    }

    /**
     * Comme un joueur : pas frappé depuis 10 s (ni blessé depuis 2 s), pas en feu, nourri (faim d'au moins
     * {@link Reglages#regen_faim}), il regagne un point de vie toutes les {@link Reglages#regen_secondes} secondes. Ses nuits
     * d'affilée sans dormir ({@link Nuit}) ralentissent toujours sa guérison : pas de ce soin-là tant qu'elles pèsent.
     */
    static void regenerer(Travailleur t) {
        Reglages r = Reglages.actuel;
        if (r.regen_secondes <= 0 || (t.tickCount + t.getId()) % (r.regen_secondes * 20) != 0) return;
        if (t.getHealth() >= t.getMaxHealth() || t.getHealth() <= 0 || t.faim < r.regen_faim) return;
        if (frappeRecemment(t) || t.getLastDamageSource() != null || t.isOnFire()) return;
        if (Nuit.vieEnMoins(t.memoire.getInt("nuits"), r.nuit_nuits_avant_malus) > 0) return;
        t.heal(1);
    }

    /**
     * Trop blessé (sous {@link Reglages#fuite_vie} de sa vie), pas guerrier, ni déjà en fuite, ni en train d'échapper à la
     * lave, ni à une consigne de son chef (le suivre, une tâche), ni au repos : il se replie. Après un repli sans guérison (rien
     * à manger), pas de nouveau repli avant {@link Reglages#fuite_pause_secondes}, sauf s'il est frappé de nouveau.
     */
    static boolean doitFuir(Travailleur t) {
        Reglages r = Reglages.actuel;
        if (r.fuite_vie <= 0 || t.getHealth() >= t.getMaxHealth() * r.fuite_vie) return false;
        if ("guerrier".equals(t.metier) || t.isPassenger() || t.isSleeping()) return false;
        Actions.Action a = t.action;
        if (a instanceof Repli || a instanceof Actions.Fuir || a instanceof Actions.Echapper || a instanceof Actions.Reposer
                || a instanceof Actions.SuivreChef || a instanceof Consignes.Faire) return false;
        return frappeRecemment(t) || t.memoire.getLong("repli_pause") <= Cerveau.temps(t);
    }

    /**
     * Où se mettre à l'abri de {@code danger} : son poste s'il n'est pas du côté du danger (et pas déjà au poste), sinon un
     * endroit à l'opposé (jusqu'à 16 blocs). Sans danger : son poste, s'il est chez lui et à plus de 5 blocs. Null : il reste là.
     */
    static Vec3 refuge(Travailleur t, LivingEntity danger) {
        Vec3 ici = t.position();
        if (t.bureau != null && t.chezLui(t.blockPosition())) {
            Vec3 poste = Vec3.atBottomCenterOf(t.bureau);
            double d2 = ici.distanceToSqr(poste);
            if (danger == null) return d2 > 5 * 5 ? poste : null;
            if (d2 > 4 * 4 && d2 < 64 * 64 && poste.subtract(ici).dot(danger.position().subtract(ici)) <= 0) return poste;
        }
        return danger == null ? null : DefaultRandomPos.getPosAway(t, 16, 7, danger.position());
    }

    /** Le monstre le plus proche de {@code centre}, à moins de {@code rayon} blocs ; null s'il n'y en a pas. */
    static LivingEntity menace(Travailleur t, Vec3 centre, double rayon) {
        LivingEntity m = null;
        for (LivingEntity e : t.level().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(centre, centre).inflate(rayon, 4, rayon), Monde::hostile))
            if (m == null || e.distanceToSqr(centre) < m.distanceToSqr(centre)) m = e;
        return m;
    }

    /**
     * Le repli : il s'éloigne du danger (à {@link Reglages#fuite_distance} blocs) ou rentre au poste, mange tant qu'il a faim
     * et qu'il lui manque de la vie, et attend. Fini quand il a retrouvé {@link Reglages#fuite_reprise} de sa vie, ou au bout de
     * {@link Reglages#fuite_secondes_max}. Il gère lui-même les coups : un nouveau coup change seulement le danger à fuir.
     */
    static final class Repli extends Actions.Action {
        LivingEntity danger;
        Vec3 but;
        boolean auPoste;
        int prochainChoix;

        Repli() { super("fuir"); mobile = true; }

        @Override
        Actions.Etat tick(Travailleur t) {
            Reglages r = Reglages.actuel;
            t.coupVu = t.getLastHurtByMobTimestamp(); // le cerveau ne le coupe pas à chaque coup : c'est ici qu'on y répond
            LivingEntity coup = t.getLastHurtByMob();
            if (coup != null && coup != danger && coup.isAlive() && !t.estDe(coup)) { danger = coup; but = null; prochainChoix = 0; nouveauBut(); }
            if (danger != null && (!danger.isAlive() || danger.level() != t.level() || t.distanceToSqr(danger) > 32 * 32)) danger = null;
            if (t.getHealth() >= t.getMaxHealth() * r.fuite_reprise) {
                Cerveau.dire(t, "repli_fini", 60, "Ça va mieux. Je retourne à l'ouvrage.");
                return Actions.Etat.OK;
            }
            if (age > r.fuite_secondes_max * 20) return Actions.Etat.OK;
            double loin = r.fuite_distance;
            boolean proche = danger != null && t.distanceToSqr(danger) < loin * loin;
            // Le coup oublié (5 s), un monstre peut rester tout près, ou près du poste : il le cherche, toutes les 2 s au plus.
            if (danger == null && age % 40 == 0) {
                danger = menace(t, t.position(), loin);
                if (danger == null && !auPoste && t.bureau != null) danger = menace(t, Vec3.atBottomCenterOf(t.bureau), loin);
                if (danger != null) { but = null; prochainChoix = 0; proche = t.distanceToSqr(danger) < loin * loin; }
            }
            if (but == null && age >= prochainChoix && (proche || danger == null && !auPoste)) {
                but = refuge(t, danger);
                if (danger == null) auPoste = true; // sans danger, il ne rentre qu'une fois
                prochainChoix = age + 40;
                nouveauBut();
            }
            if (but != null) {
                boolean versPoste = t.bureau != null && but.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 1;
                if (t.position().distanceToSqr(but) < 2.5 * 2.5 || coince() || !versPoste && danger != null && !proche) { but = null; t.getNavigation().stop(); }
                else marcher(t, but, proche ? 1.35 : 1.0);
            } else t.getNavigation().stop();
            // Il mange ce qu'il a, s'il a faim et qu'il lui manque de la vie (toutes les 2 s : le temps d'avaler).
            if (age % 40 == 20 && t.faim <= r.regen_faim + 2 && t.getHealth() < t.getMaxHealth() && t.manger())
                Cerveau.dire(t, "soin", 60, "Je panse mes plaies et je mange un morceau.");
            return Actions.Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.memoire.putLong("repli_pause", Cerveau.temps(t) + Reglages.actuel.fuite_pause_secondes);
        }
    }
}

package fr.lacolonie.pnj;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * La défense de la colonie vue par les ouvriers qui ne se battent pas (lot 9, cahier 14.11). Toutes les demi-secondes,
 * depuis le cerveau, une seule petite recherche autour de lui (8 blocs) : un creeper en vue, il lâche son travail, s'en
 * éloigne et prévient le guerrier de son chef le plus proche ; à portée de voix (48 blocs), le guerrier répond et vient le
 * tuer ; plus loin (96 blocs au plus), l'ouvrier court vers lui, à l'opposé du creeper, et le guerrier l'apprend en le
 * voyant arriver ({@link Guerrier#veille}). Un autre monstre : son caractère décide ({@link Bravoure}) ; le colérique fonce le
 * frapper, le calme le signale près du poste (et se défend, lui ou un collègue frappé), le peureux fuit en criant, le fourbe
 * s'éclipse. Le travail reprend tout seul ensuite : la fuite et le combat sont des actions comme les autres.
 */
final class Defense {
    private Defense() {}

    /** Ce qu'un ouvrier remarque autour de lui. */
    static final double VUE = 8;
    /** Jusque-là, le guerrier l'entend crier. */
    static final double VOIX = 48;
    /** Jusque-là, il sait où trouver le guerrier et court le prévenir. */
    static final double LOIN = 96;

    /** Jusque-là, un ouvrier qui se bat (colérique, riposte) poursuit le monstre ; il ne s'écarte pas de plus de 4 blocs de moins de là où il était. */
    static final double PORTEE = 16;

    /**
     * Un ouvrier qui ne se bat pas voit un monstre : vrai s'il lâche ce qu'il fait (il fuit, ou il se bat). Un creeper, tous
     * s'en éloignent ; un autre monstre, son caractère décide ({@link Bravoure}, réglage {@code caractere_monstres}) ; sans
     * ça, il le signale s'il est près du poste.
     */
    static boolean ouvrier(Travailleur t) {
        if (t.action instanceof Actions.Fuir || t.action instanceof Actions.Echapper || t.isSleeping() || t.aTerre) return false;
        // Pas en pleine consigne du chef, ni à cheval ou en barque.
        boolean caractere = Reglages.actuel.caractere_monstres && !(t.action instanceof Consignes.Faire) && !t.isPassenger();
        // En plein combat contre un monstre, trop blessé : même le colérique rompt et s'enfuit.
        if (caractere && t.action instanceof Actions.Attaquer a && Monde.hostile(a.cible) && faible(t))
            return fuir(t, a.cible, DefaultRandomPos.getPosAway(t, 16, 7, a.cible.position()));
        double vue = caractere ? Bravoure.vue(t.temperament) : VUE;
        // Une seule recherche : les monstres, et ses collègues tout proches (pour les secourir ou les envoyer à sa place).
        List<LivingEntity> autour = t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(vue, 3, vue),
                e -> Monde.hostile(e) || caractere && e instanceof Travailleur w && w != t && t.chef != null && t.chef.equals(w.chef));
        if (autour.isEmpty()) return false;
        autour.sort(Comparator.comparingDouble(t::distanceToSqr));
        Creeper creeper = null;
        LivingEntity autre = null;
        List<LivingEntity> vus = new ArrayList<>();
        List<Travailleur> collegues = new ArrayList<>();
        for (LivingEntity e : autour) {
            if (e instanceof Travailleur w) { collegues.add(w); continue; }
            if (!t.getSensing().hasLineOfSight(e)) continue;
            vus.add(e);
            if (e instanceof Creeper c) { if (creeper == null) creeper = c; }
            else if (autre == null) autre = e;
        }
        if (creeper != null) {
            // Il fuit seulement s'il a où aller (sinon il ne lâcherait son travail que pour rien) ; il appelle la garde de toute façon.
            Vec3 loin = DefaultRandomPos.getPosAway(t, 16, 7, creeper.position());
            if (fuir(t, creeper, loin)) return true;
        }
        if (t.action instanceof Actions.Attaquer) return false; // il se bat déjà
        LivingEntity vu = autre;
        if (vu != null && caractere && reagir(t, vu, vus, collegues)) return true;
        // Le fourbe ne prévient personne, même quand il n'a pas où filer.
        if (vu != null && !(caractere && t.temperament.equals("fourbe")) && t.bureau != null && t.chezLui(t.blockPosition()) && Cerveau.parfois(t, "monstre_vu", 60)) {
            signaler(t, vu);
            Besoins.dire(t, "monstre_vu", null);
        }
        return false;
    }

    /** Moins de 40 % de sa vie, ou hors de combat : il ne se bat plus. */
    static boolean faible(Travailleur t) { return t.getHealth() < t.getMaxHealth() * Bravoure.FAIBLE || Blessures.horsDeCombat(t); }

    /**
     * Son caractère devant le monstre {@code m}, le plus proche en vue (pas un creeper) ; {@code vus} : les monstres en vue,
     * {@code collegues} : ceux de son chef autour de lui, du plus proche au plus loin. Vrai s'il lâche ce qu'il fait.
     */
    static boolean reagir(Travailleur t, LivingEntity m, List<LivingEntity> vus, List<Travailleur> collegues) {
        LivingEntity coup = t.getLastHurtByMob();
        boolean frappe = coup != null && vus.contains(coup) && Monde.recent(t, t.getLastHurtByMobTimestamp());
        LivingEntity contre = frappe ? coup : null;
        Travailleur proche = null;
        for (Travailleur w : collegues) {
            double d2 = w.distanceToSqr(t);
            if (proche == null && !w.aTerre && d2 <= VUE * VUE) proche = w;
            LivingEntity a = w.getLastHurtByMob();
            if (contre == null && d2 <= 6 * 6 && a != null && vus.contains(a) && Monde.recent(w, w.getLastHurtByMobTimestamp())) contre = a;
        }
        boolean faible = faible(t);
        // Où s'éloigner : seulement pour ceux qui peuvent fuir (quelques essais de position, pas plus d'une fois par décision).
        boolean fuyard = t.temperament.equals("peureux") || t.temperament.equals("fourbe") || t.temperament.equals("colerique") && faible;
        Vec3 loin = fuyard ? DefaultRandomPos.getPosAway(t, 16, 7, m.position()) : null;
        Bravoure.Reaction r = Bravoure.face(t.temperament, faible, frappe, contre != null && !frappe, proche != null, loin != null);
        return switch (r) {
            case CHARGER -> attaquer(t, frappe ? coup : m, "monstre_charge");
            case RIPOSTER -> contre != null && attaquer(t, contre, "monstre_riposte");
            case FUIR -> loin != null && fuir(t, m, loin);
            case ECLIPSER, POUSSER -> {
                if (loin == null) yield false;
                // Sans prévenir la garde : il file, ou il y envoie un collègue en paroles.
                if (Cerveau.parfois(t, "monstre_eclipse", 30)) crier(t, r == Bravoure.Reaction.POUSSER ? "monstre_pousse" : "monstre_eclipse", proche == null ? null : proche.prenom);
                Actions.Fuir f = new Actions.Fuir(m);
                f.refuge = loin;
                Cerveau.lancer(t, f);
                yield true;
            }
            case SIGNALER -> false;
        };
    }

    /** Il frappe le monstre avec ce qu'il a en main, sans quitter son espace de vie ni le poursuivre loin ; pas un monstre qu'il n'a pas pu atteindre tout à l'heure. */
    static boolean attaquer(Travailleur t, LivingEntity m, String cle) {
        if (t.bureau != null && !t.chezLui(m.blockPosition()) || t.inaccessibles.getOrDefault(m.getId(), 0L) > t.level().getGameTime()) return false;
        if (Cerveau.parfois(t, cle, 20)) crier(t, cle, null);
        Cerveau.lancer(t, new Actions.Attaquer(m).portee(PORTEE).tenir(t.position(), PORTEE - 4));
        return true;
    }

    /** Les phrases dites quand defense.txt n'a pas (encore) la section. */
    static final Map<String, String> SECOURS = Map.of(
            "creeper_alerte", "Un creeper ! À la garde !",
            "creeper_fuite", "Un creeper ! À la garde !",
            "monstre_creeper_recule", "Celui-là, je ne le frappe pas : il éclate ! Je recule.",
            "monstre_charge", "Viens donc, sale bête ! Je vais t'apprendre !",
            "monstre_riposte", "Puisqu'il le faut, je me défends !",
            "monstre_panique", "Au secours ! Une bête ! Sauve qui peut !",
            "monstre_eclipse", "Mieux vaut ne pas traîner par ici…",
            "monstre_pousse", "{nom}, une bête rôde par là, va donc voir ! Moi, j'ai à faire.",
            "monstre_abattu", "Et d'un ! Celui-là ne fera plus de mal.");

    static String secours(String cle, String nom) { return SECOURS.get(cle).replace("{nom}", nom == null ? "" : nom); }

    /** La réplique {@code cle} de defense.txt, ou sa phrase de secours. */
    static void crier(Travailleur t, String cle, String nom) { if (!Besoins.dire(t, cle, nom)) t.dire(secours(cle, nom)); }

    /** Ce tempérament, et le réglage qui laisse le caractère jouer devant les monstres. */
    static boolean caractere(Travailleur t, String temperament) { return Reglages.actuel.caractere_monstres && temperament.equals(t.temperament); }

    /**
     * Il prévient la garde du monstre (un creeper ; ou n'importe lequel pour un peureux) : de vive voix, ou en courant vers
     * le guerrier s'il est plus loin ; et il s'en éloigne ({@code loin}, null s'il n'a pas où aller). Vrai s'il fuit.
     */
    static boolean fuir(Travailleur t, LivingEntity c, Vec3 loin) {
        signaler(t, c);
        Actions.Fuir f = new Actions.Fuir(c);
        f.refuge = loin;
        Travailleur g = garde(t, LOIN);
        boolean creeper = c instanceof Creeper;
        boolean parle = Cerveau.parfois(t, creeper ? "creeper" : "panique", 20);
        // Le colérique ne crie pas à l'aide devant un creeper : il recule (la garde l'entend quand même) ; un autre monstre ne le fait fuir que blessé.
        String seul = !creeper ? "monstre_panique" : caractere(t, "colerique") ? "monstre_creeper_recule" : "creeper_fuite";
        if (g != null && g.distanceTo(t) <= VOIX) {
            if (parle) crier(t, seul.equals("creeper_fuite") ? "creeper_alerte" : seul, g.prenom);
            appeler(g, c);
        } else {
            if (parle) crier(t, seul, null);
            if (g != null) {
                // Le guerrier est loin, mais pas du côté du monstre : il court le prévenir.
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

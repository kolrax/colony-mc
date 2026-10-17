package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * La défense de la colonie vue par les ouvriers qui ne se battent pas (lot 9, cahier 14.11). Toutes les demi-secondes,
 * depuis le cerveau, une seule petite recherche autour de lui (8 blocs) : un creeper en vue, il lâche son travail, s'en
 * éloigne et prévient le guerrier de son chef le plus proche ; à portée de voix (48 blocs), le guerrier répond et vient le
 * tuer ; plus loin (96 blocs au plus), l'ouvrier court vers lui, à l'opposé du creeper, et le guerrier l'apprend en le
 * voyant arriver ({@link Guerrier#veille}). Un autre monstre : son caractère décide ; le colérique fonce le
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

    /**
     * Un monstre persistant est d'ordinaire gardé exprès par quelqu'un : l'ouvrier le laisse à son caractère ({@link #apprivoise}).
     * Avec cette marque (tests, commande), il compte comme sauvage.
     */
    static final String SAUVAGE = "colonie_sauvage";

    /** Jusque-là, un ouvrier qui se bat (colérique, riposte) poursuit le monstre ; il ne s'écarte pas de plus de 4 blocs de moins de là où il était. */
    static final double PORTEE = 16;

    /**
     * Un ouvrier qui ne se bat pas voit un monstre : vrai s'il lâche ce qu'il fait (il fuit, ou il se bat). Un creeper, tous
     * s'en éloignent ; un autre monstre, son caractère décide ; sans
     * ça, il le signale s'il est près du poste.
     */
    static boolean ouvrier(Travailleur t) {
        if (t.action instanceof Actions.Fuir || t.action instanceof Actions.Echapper || t.isSleeping() || t.aTerre) return false;
        // Pas en pleine consigne du chef, ni à cheval ou en barque.
        boolean caractere = Reglages.actuel.caractere_monstres && !(t.action instanceof Consignes.Faire) && !t.isPassenger();
        // En plein combat contre un monstre, trop blessé : même le colérique rompt et s'enfuit.
        // Sans refuge, il ne recherche pas où fuir à chaque demi-seconde : toutes les 3 s au plus.
        if (caractere && t.action instanceof Actions.Attaquer a && Monde.hostile(a.cible) && faible(t) && Cerveau.parfois(t, "rompre", 3))
            return fuir(t, a.cible, DefaultRandomPos.getPosAway(t, 16, 7, a.cible.position()));
        // En guerre, un ennemi chez nous : il le signale à la garde et s'écarte (Armee).
        if (Armee.fuir(t)) return true;
        double vue = VUE;
        // Une seule recherche : les monstres, et ses collègues tout proches (pour les secourir ou les envoyer à sa place).
        List<LivingEntity> autour = t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(vue, 3, vue),
                e -> Monde.hostile(e) || caractere && e instanceof Travailleur w && w != t && t.chef != null && t.chef.equals(w.chef));
        if (autour.stream().allMatch(e -> e instanceof Travailleur)) return false; // pas un monstre : ses collègues ne comptent pas
        autour.sort(Comparator.comparingDouble(t::distanceToSqr));
        Creeper creeper = null;
        LivingEntity autre = null, cible = null;
        List<LivingEntity> vus = new ArrayList<>();
        List<Travailleur> collegues = new ArrayList<>();
        for (LivingEntity e : autour) {
            if (e instanceof Travailleur w) { collegues.add(w); continue; }
            if (!t.getSensing().hasLineOfSight(e)) continue;
            if (e instanceof Creeper c) { if (creeper == null) creeper = c; vus.add(e); continue; }
            if (autre == null) autre = e;
            // Un monstre qui est à quelqu'un, ou pas en guerre : son caractère ne joue pas (il le signale seulement).
            if (caractere && apprivoise(e)) continue;
            vus.add(e);
            if (cible == null) cible = e;
        }
        if (creeper != null) {
            // Il fuit seulement s'il a où aller (sinon il ne lâcherait son travail que pour rien) ; il appelle la garde de toute façon.
            Vec3 loin = DefaultRandomPos.getPosAway(t, 16, 7, creeper.position());
            if (fuir(t, creeper, loin)) return true;
        }
        if (t.action instanceof Actions.Attaquer) return false; // il se bat déjà
        if (cible != null && caractere && reagir(t, cible, vus, collegues)) return true;
        LivingEntity vu = autre;
        // Le fourbe ne prévient personne, même quand il n'a pas où filer.
        if (vu != null && t.bureau != null && t.chezLui(t.blockPosition()) && Cerveau.parfois(t, "monstre_vu", 60)) {
            signaler(t, vu);
            Besoins.dire(t, "monstre_vu", null);
        }
        return false;
    }

    /** Moins de 40 % de sa vie, ou hors de combat : il ne se bat plus. */
    static boolean faible(Travailleur t) { return t.getHealth() < t.getMaxHealth() * 0.4 || Blessures.horsDeCombat(t); }

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
        // Plus de caractère (refonte 1.0.0), une règle fixe : frappé, celui qui ne se bat pas (ou trop blessé) fuit s'il a où aller ;
        // debout, un combattant riposte à qui frappe (lui ou un collègue tout près) ; sinon il signale.
        boolean faible = faible(t);
        if (frappe && (faible || !Armee.arme(t))) {
            Vec3 loin = refuge(t, m);
            if (loin != null) return fuir(t, m, loin) && noterFuite(t, m);
        }
        return !faible && (frappe || Armee.arme(t)) && contre != null && attaquer(t, contre, "monstre_riposte");
    }

    /** Il frappe le monstre avec ce qu'il a en main, sans quitter son espace de vie ni le poursuivre loin ; pas un monstre qu'il n'a pas pu atteindre tout à l'heure. */
    static boolean attaquer(Travailleur t, LivingEntity m, String cle) {
        if (t.bureau != null && !t.chezLui(m.blockPosition()) || t.inaccessibles.getOrDefault(m.getId(), 0L) > t.level().getGameTime()) return false;
        if (Cerveau.parfois(t, cle, 20)) crier(t, cle, null);
        retenirPlace(t);
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
        if (!creeper) retenirPlace(t); // après un creeper, il ne revient pas se planter à côté
        Cerveau.lancer(t, f);
        return true;
    }

    /**
     * Un monstre qui est à quelqu'un, ou qui n'est pas en guerre : nommé, en laisse, persistant (gardé exprès, sauf marqué
     * {@link #SAUVAGE}), un zombie villageois qu'on soigne, un piglin qui n'attaque personne.
     */
    static boolean apprivoise(LivingEntity e) {
        if (e.hasCustomName()) return true;
        if (e instanceof Mob m && (m.isLeashed() || m.isPersistenceRequired() && !m.getTags().contains(SAUVAGE))) return true;
        if (e instanceof ZombieVillager z && z.isConverting()) return true;
        return e instanceof AbstractPiglin p && p.getTarget() == null;
    }

    /**
     * Où fuir ce monstre : de préférence hors de sa vue (il ne le refuira pas sans qu'il s'approche : {@link #dejaFui}), en trois
     * tirages au plus ; sinon le plus loin trouvé ; null s'il n'a nulle part où aller.
     */
    static Vec3 refuge(Travailleur t, LivingEntity m) {
        Vec3 mieux = null;
        double vue = VUE + 1;
        for (int k = 0; k < 3; k++) {
            Vec3 p = DefaultRandomPos.getPosAway(t, 16, 7, m.position());
            if (p == null) continue;
            if (p.distanceToSqr(m.position()) > vue * vue) return p;
            if (mieux == null || p.distanceToSqr(m.position()) > mieux.distanceToSqr(m.position())) mieux = p;
        }
        return mieux;
    }

    /** Combien de temps il se souvient d'avoir fui un monstre (3 minutes). */
    static final int SOUVENIR_FUITE = 20 * 180;

    /** Il a fui ce monstre il y a peu, et il n'est pas plus près qu'alors (à un bloc et demi près). */
    static boolean dejaFui(Travailleur t, LivingEntity m) {
        long maintenant = t.level().getGameTime();
        t.fuis.values().removeIf(v -> v[1] < maintenant);
        double[] f = t.fuis.get(m.getId());
        return f != null && t.distanceTo(m) >= f[0] - 1.5;
    }

    /** Il retient d'où il a fui ce monstre (la distance la plus courte) ; toujours vrai. */
    static boolean noterFuite(Travailleur t, LivingEntity m) {
        double[] avant = t.fuis.get(m.getId());
        double d = t.distanceTo(m);
        t.fuis.put(m.getId(), new double[]{avant == null ? d : Math.min(d, avant[0]), t.level().getGameTime() + SOUVENIR_FUITE});
        return true;
    }

    /** « Attends ici » : avant de fuir ou de se battre, il retient sa place pour y revenir ensuite ({@link #revenir}). */
    static void retenirPlace(Travailleur t) {
        if (t.mode != Travailleur.Mode.ATTENDRE || t.memoire.contains("place_attente")) return;
        CompoundTag p = new CompoundTag();
        p.putDouble("x", t.getX());
        p.putDouble("y", t.getY());
        p.putDouble("z", t.getZ());
        p.putLong("t", t.level().getGameTime());
        t.memoire.put("place_attente", p);
    }

    /** Revenu au calme, celui qu'on a mis à attendre retourne à sa place : trois essais au plus, dans les 5 minutes. Vrai s'il y va. */
    static boolean revenir(Travailleur t) {
        if (!t.memoire.contains("place_attente")) return false;
        CompoundTag p = t.memoire.getCompound("place_attente");
        Vec3 place = new Vec3(p.getDouble("x"), p.getDouble("y"), p.getDouble("z"));
        if (t.mode != Travailleur.Mode.ATTENDRE || p.getInt("essais") >= 3 || t.level().getGameTime() - p.getLong("t") > 20 * 300
                || t.position().distanceToSqr(place) <= 1.5 * 1.5) {
            t.memoire.remove("place_attente");
            return false;
        }
        p.putInt("essais", p.getInt("essais") + 1);
        Cerveau.lancer(t, new Actions.Aller(place, 1));
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

    // --- On se sert au dépôt du poste (cahier 6.4) -----------------------------------------------------

    /** Jusque-là, les gens du chef voient qu'on se sert au dépôt ; le guerrier qui frappe le voleur le laisse à {@link #LAISSE} blocs du dépôt. */
    static final double DEPOT_VUE = 20, LAISSE = 12;
    /** Le chef prévenu, par voleur : au plus une fois toutes les 5 minutes (temps du jeu). */
    static final long PREVENIR = 20 * 300;
    private static final Map<String, Long> PREVENUS = new java.util.HashMap<>();
    /** Le voleur averti (par chef et voleur, temps du jeu) : s'il rouvre le dépôt dans les 2 minutes, sur l'ordre du chef, on le frappe. */
    static final long AVERTI = 20 * 120;
    private static final Map<String, Long> AVERTIS = new java.util.HashMap<>();

    /**
     * Un joueur ouvre le dépôt d'un poste (le bureau ou un coffre collé à lui) : ni le chef, ni un faux joueur (machine), ni
     * un joueur qu'il a autorisé ({@link Courrier#permis}). On ne l'empêche pas d'ouvrir : ses gens réagissent seulement.
     */
    static void depotOuvert(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer v) || v instanceof FakePlayer || v.isSpectator()
                || v.isSecondaryUseActive() && !(v.getMainHandItem().isEmpty() && v.getOffhandItem().isEmpty())) return; // accroupi, objet en main : il n'ouvre rien
        Bureau.Entite b = Bureau.depotDe(v.level(), e.getPos());
        if (b == null || v.getUUID().equals(b.proprietaire) || Courrier.de(v.server).permis(b.proprietaire, v.getGameProfile().getName())) return;
        depotVole(b, v);
    }

    /**
     * Les gens du chef à {@link #DEPOT_VUE} blocs qui voient {@code v} (une seule recherche) : les guerriers s'interposent
     * et l'avertissent, ou le frappent sur l'ordre du chef (« attaque ceux qui volent le dépôt ») ; sans guerrier, un
     * ouvrier le signale. Le chef est prévenu, de vive voix s'il est là, sinon par une lettre.
     */
    static void depotVole(Bureau.Entite b, ServerPlayer v) {
        java.util.UUID chef = b.proprietaire;
        Vec3 lieu = Vec3.atBottomCenterOf(b.getBlockPos());
        String nom = v.getGameProfile().getName();
        long maintenant = v.level().getGameTime();
        String cle = chef + "/" + v.getUUID();
        // Averti il y a plus d'une seconde et moins de 2 minutes : il récidive (un double clic ne compte pas).
        Long averti = AVERTIS.get(cle);
        boolean recidive = averti != null && maintenant - averti >= 20 && maintenant - averti <= AVERTI;
        Travailleur garde = null, temoin = null;
        for (Travailleur t : v.level().getEntitiesOfClass(Travailleur.class, v.getBoundingBox().inflate(DEPOT_VUE, 8, DEPOT_VUE),
                x -> x.isAlive() && chef.equals(x.chef) && x.distanceTo(v) <= DEPOT_VUE)) {
            if (t.aTerre || t.isSleeping() || t.mode == Travailleur.Mode.GREVE || t.memoire.getBoolean("revolte") || !t.getSensing().hasLineOfSight(v)) continue;
            Metier m = Metier.de(t.metier);
            boolean combat = t.action instanceof Actions.Attaquer x && x.cible.isAlive() && x.cible != v && Monde.hostile(x.cible)
                    || t.action instanceof Consignes.Faire f && f.e.comportement().equals("combat");
            if (m != null && m.combattant && !Blessures.horsDeCombat(t) && !combat) { garderDepot(t, lieu, v, recidive); if (garde == null) garde = t; }
            else if (temoin == null) temoin = t;
        }
        // Sans guerrier : un ouvrier le signale, d'un mot, pas plus.
        if (garde == null && temoin != null && Cerveau.parfois(temoin, "depot_vu", 30) && !Besoins.dire(temoin, "depot_vu", nom)) temoin.dire("Hé ! Ce dépôt n'est pas à toi !");
        if (garde != null && (averti == null || maintenant - averti > AVERTI || maintenant < averti)) {
            if (AVERTIS.size() > 64) AVERTIS.values().removeIf(x -> maintenant - x > AVERTI || maintenant < x);
            AVERTIS.put(cle, maintenant);
        }
        Travailleur qui = garde != null ? garde : temoin;
        if (qui == null) return;
        Long avant = PREVENUS.get(cle);
        if (avant != null && maintenant - avant < PREVENIR && maintenant >= avant) return;
        if (PREVENUS.size() > 64) PREVENUS.values().removeIf(x -> maintenant - x >= PREVENIR || maintenant < x);
        PREVENUS.put(cle, maintenant);
        String dit = Besoins.texte(qui, "depot_prevenir", nom, null);
        Courrier.ecrireTexte(qui, "On se sert au dépôt", dit != null ? dit : "Chef, " + nom + " s'est servi dans notre dépôt. Je l'ai vu de mes yeux.", false);
    }

    /**
     * Le guerrier qui voit {@code v} au dépôt : il s'interpose et l'avertit ; sur l'ordre du chef, il le frappe s'il
     * récidive après l'avertissement (jamais un joueur épargné).
     */
    static void garderDepot(Travailleur t, Vec3 lieu, ServerPlayer v, boolean recidive) {
        Actions.Action ensuite = t.action instanceof Interposer i && i.agresseur == v ? i.ensuite
                : t.mode == Travailleur.Mode.ATTENDRE ? new Actions.Aller(t.position(), 1) : null;
        boolean epargne = GardeChef.epargne(t.memoire.getString("epargnes"), v.getGameProfile().getName());
        boolean guerre = !epargne && Armee.ennemis(t).containsKey(v.getUUID()) && Armee.aOrdre(t, true); // le chef ennemi, en guerre, et sa mission défendre ou attaquer (refonte 6)
        boolean ordre = !epargne && GardeChef.posture(t.memoire.getString("garde_depot")) == GardeChef.Posture.ATTAQUE;
        if (guerre || (ordre && recidive)) {
            if (t.action instanceof Actions.Attaquer a && a.cible == v) return;
            if (!Besoins.dire(t, "depot_riposte", v.getGameProfile().getName())) t.dire("Pas un pas de plus avec notre bien. En garde !");
            Guerrier.armer(t);
            Actions.Attaquer x = new Actions.Attaquer(v).portee(DEPOT_VUE).tenir(lieu, LAISSE);
            x.guerre = guerre;
            x.ensuite = ensuite;
            Cerveau.lancer(t, x);
            return;
        }
        if (!(t.action instanceof Interposer i && i.agresseur == v)) {
            Interposer x = new Interposer(lieu, v);
            x.ensuite = ensuite;
            Cerveau.lancer(t, x);
        }
        // Sur l'ordre du chef : une sommation, la seule.
        if (ordre) { if (!Besoins.dire(t, "depot_sommation", v.getGameProfile().getName())) t.dire("Repose ça. Si tu y reviens, je frappe."); }
        else if (Cerveau.parfois(t, "depot_interpose", 5) && !Besoins.dire(t, "depot_interpose", v.getGameProfile().getName())) t.dire("Holà ! Ce dépôt n'est pas le tien.");
    }

    /**
     * Il se tient entre son chef (ou le dépôt du poste, {@code lieu}) et celui qui l'a frappé (ou s'y sert), face à lui,
     * quelques secondes, sans frapper.
     */
    static final class Interposer extends Actions.Action {
        final ServerPlayer chef;
        final Vec3 lieu;
        final Player agresseur;
        Vec3 place;

        Interposer(ServerPlayer chef, Player agresseur) { super("s_interposer"); this.chef = chef; this.lieu = null; this.agresseur = agresseur; mobile = true; }

        Interposer(Vec3 lieu, Player agresseur) { super("s_interposer"); this.chef = null; this.lieu = lieu; this.agresseur = agresseur; mobile = true; }

        Vec3 centre() { return chef != null ? chef.position() : lieu; }

        @Override
        Actions.Etat tick(Travailleur t) {
            if (age > 20 * 10 || !agresseur.isAlive() || agresseur.level() != t.level()
                    || chef != null && (!chef.isAlive() || chef.isRemoved() || chef.level() != t.level()) || agresseur.position().distanceTo(centre()) > SCENE) return Actions.Etat.OK;
            t.getLookControl().setLookAt(agresseur);
            if (place == null || age % 10 == 0) {
                // À un pas et demi du chef, du côté de l'agresseur ; au dépôt, devant celui qui s'y sert, du côté du dépôt
                // (au milieu s'ils sont tout près).
                Vec3 c = chef != null ? chef.position() : agresseur.position(), d = (chef != null ? agresseur.position() : lieu).subtract(c).multiply(1, 0, 1);
                double l = d.length();
                place = l < 0.1 ? c : c.add(d.scale(Math.min(chef != null ? 1.5 : 1.2, l / 2) / l));
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

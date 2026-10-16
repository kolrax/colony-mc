package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Le guerrier (cahier 3.9). Commandes : garder le bureau, faire la ronde (sur le parcours que le chef lui a montré, sinon
 * en cercle autour du poste), ou escorter son chef. Il tue les créatures hostiles, et celles qui s'en prennent à lui, à
 * son chef ou à un ouvrier de la colonie ; la nuit, en service, il chasse les rôdeurs de l'espace de vie. Il tire à l'arc
 * ou à l'arbalète s'il en a, s'équipe seul au dépôt, se soigne seul, et s'entraîne sur un mannequin. Il ne frappe un
 * joueur que sur ordre de son chef (en guerre, une consigne de combat) ; frappé par un joueur, il recule sans riposter. Il
 * ne frappe jamais un villageois, un golem ou un animal (règle du serveur : pas de violence hors RP).
 */
final class Guerrier extends Metier {
    static final Actions.Filtre MELEE = Actions.Filtre.de("#minecraft:swords|#minecraft:axes");
    static final Actions.Filtre TIR = Actions.Filtre.de("minecraft:bow|minecraft:crossbow");
    static final Actions.Filtre FLECHES = Actions.Filtre.de("#minecraft:arrows");
    /** Un monstre à tant de blocs du poste (à l'horizontale) : il va le tuer, de jour comme de nuit, même au repos. */
    static final int POSTE = 24;
    /** Au plus tant de points sur le parcours de sa ronde. */
    static final int POINTS_MAX = 16;

    Guerrier() {
        super("guerrier", "Guerrier", "Garde le bureau, fait la ronde autour ou escorte son chef, et tue les monstres. Il ne lève son arme sur un homme que sur l'ordre de son chef ; si quelqu'un le frappe, il recule sans rendre le coup. Il ne touche jamais aux villageois ni aux bêtes.",
                true, "nourriture", "#minecraft:swords", "#minecraft:axes", "minecraft:bow|minecraft:crossbow", "#minecraft:arrows", "soin", "soin");
        commandes.add(new Commande("garder", "Garder le bureau", null));
        commandes.add(new Commande("ronde", "Faire la ronde (50 blocs)", null));
        commandes.add(new Commande("escorter", "Escorter son chef", null));
    }

    // --- Les armes --------------------------------------------------------------------------------------

    /** Les dégâts d'une arme de mêlée (épée, hache) en main ; 0 pour le reste. */
    static double degats(ItemStack s) {
        if (!MELEE.objet(s)) return 0;
        double[] d = {1};
        s.forEachModifier(EquipmentSlot.MAINHAND, (a, m) -> { if (a.is(Attributes.ATTACK_DAMAGE) && m.operation() == AttributeModifier.Operation.ADD_VALUE) d[0] += m.amount(); });
        return d[0];
    }

    /** La valeur d'une pièce d'armure pour cette case (0 si elle ne s'y porte pas). */
    static double protection(ItemStack s, EquipmentSlot slot) {
        return s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot ? a.getDefense() + a.getToughness() / 10 + 0.01 : 0;
    }

    /** Une potion qui soigne (soin ou régénération), à boire. */
    static boolean soignante(ItemStack s) {
        if (!s.is(Items.POTION)) return false;
        for (MobEffectInstance e : s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects())
            if (e.getEffect().is(MobEffects.HEAL) || e.getEffect().is(MobEffects.REGENERATION)) return true;
        return false;
    }

    /** Prend en main sa meilleure arme de mêlée (l'objet tenu retourne dans le sac). */
    static void armer(Travailleur t) {
        int k = -1;
        double d = degats(t.getMainHandItem());
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            double di = degats(t.inventaire.getItem(i));
            if (di > d) { d = di; k = i; }
        }
        if (k < 0) return;
        ItemStack tenu = t.getMainHandItem();
        t.setItemSlot(EquipmentSlot.MAINHAND, t.inventaire.getItem(k));
        t.inventaire.setItem(k, tenu);
    }

    /** Un arc ou une arbalète, et des flèches. */
    static boolean peutTirer(Travailleur t) { return t.compter(TIR) > 0 && t.compter(FLECHES) > 0; }

    /** Prend en main son arbalète, sinon son arc. */
    static void prendreArc(Travailleur t) {
        if (!TIR.objet(t.getMainHandItem()) && !t.equiper(Actions.Filtre.de("minecraft:crossbow"))) t.equiper(TIR);
    }

    /**
     * Tire une de ses flèches sur la cible, comme un squelette : une vraie flèche de son sac, que l'on peut ramasser ensuite.
     * Renvoie le temps (en ticks) avant le prochain tir : l'arbalète est plus lente à recharger.
     */
    static int tirer(Travailleur t, LivingEntity cible) {
        ItemStack arme = t.getMainHandItem();
        if (!TIR.objet(arme)) return 10;
        ItemStack fleche = Actions.sortir(t, FLECHES);
        if (fleche.isEmpty()) return 10;
        boolean arbalete = arme.is(Items.CROSSBOW);
        AbstractArrow f = ProjectileUtil.getMobArrow(t, fleche, 1.6f, arme);
        if (arme.getItem() instanceof ProjectileWeaponItem p) f = p.customArrow(f, fleche, arme);
        f.pickup = AbstractArrow.Pickup.ALLOWED; // sa flèche, prise dans son sac : rien ne se perd
        f.clearFire();
        // Marquée : elle ne blesse que des monstres ou la cible voulue (voir impact), jamais un passant.
        f.getPersistentData().putUUID(TIREUR, t.getUUID());
        f.getPersistentData().putUUID(VISEE, cible.getUUID());
        if (arbalete) f.setBaseDamage(f.getBaseDamage() + 1);
        double dx = cible.getX() - t.getX(), dy = cible.getY(1 / 3.0) - f.getY(), dz = cible.getZ() - t.getZ();
        f.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.6f, 2);
        t.level().addFreshEntity(f);
        t.playSound(arbalete ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.ARROW_SHOOT, 1, 1 / (t.getRandom().nextFloat() * 0.4f + 0.8f));
        Actions.userOutil(t, 1); // l'arc en main s'use, plus lentement que celui d'un joueur
        return (int) ((arbalete ? 40 : 25) / Math.min(1.2, t.facteurTravail()));
    }

    static final String TIREUR = "colonie_tireur", VISEE = "colonie_visee";

    /**
     * Une flèche d'un guerrier touche quelqu'un : seulement un monstre ou la cible qu'il visait (sur ordre de son chef, ce
     * peut être un homme). Tout autre (joueur, ouvrier, villageois, bête, golem), elle le traverse sans le blesser : une
     * flèche ratée, qui retombe ou qui file derrière la cible ne frappe jamais un innocent (règle du serveur).
     */
    static void impact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent e) {
        if (!(e.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult r)) return;
        var d = e.getProjectile().getPersistentData();
        if (!d.hasUUID(TIREUR)) return;
        if (r.getEntity() instanceof LivingEntity l && Monde.hostile(l) || d.hasUUID(VISEE) && r.getEntity().getUUID().equals(d.getUUID(VISEE))) return;
        e.setCanceled(true);
    }

    static void attaquer(Travailleur t, LivingEntity cible) {
        armer(t);
        Cerveau.lancer(t, new Actions.Attaquer(cible));
    }

    // --- Se soigner ---------------------------------------------------------------------------------------

    /** Boit une potion de soin ou de régénération de son sac ; la fiole vide lui reste. */
    static boolean boire(Travailleur t) {
        // Pas coup sur coup : déjà sous régénération, ou une potion bue il y a moins de 5 s.
        if (t.hasEffect(MobEffects.REGENERATION) || t.level().getGameTime() - t.memoire.getLong("bu") < 100 && t.memoire.contains("bu")) return false;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!soignante(s)) continue;
            s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).forEachEffect(e -> {
                if (e.getEffect().value().isInstantenous()) e.getEffect().value().applyInstantenousEffect(null, null, t, e.getAmplifier(), 1.0);
                else t.addEffect(new MobEffectInstance(e));
            });
            s.shrink(1);
            t.inventaire.setChanged();
            t.ranger(new ItemStack(Items.GLASS_BOTTLE));
            t.playSound(SoundEvents.GENERIC_DRINK, 1, 1);
            t.memoire.putLong("bu", t.level().getGameTime());
            return true;
        }
        return false;
    }

    /**
     * Blessé (moins de la moitié de sa vie), sans ennemi en vue : il boit une potion, mange, ou va chercher à manger au dépôt,
     * puis se ménage au poste le temps de reprendre des forces. Vrai s'il s'en occupe.
     */
    static boolean soigner(Travailleur t) {
        if (t.getHealth() >= t.getMaxHealth() * 0.5) return false;
        if (boire(t)) { Cerveau.dire(t, "soin", 60, "Une gorgée, et ça repart."); Cerveau.attendre(t, 2); return true; }
        if (t.faim < 17 && t.manger()) { Cerveau.dire(t, "soin", 60, "Je panse mes plaies et je mange un morceau."); Cerveau.attendre(t, 2); return true; }
        Bureau.Entite b = Bureau.entite(t);
        if (t.faim < 15 && b != null && b.compter(Actions.Filtre.de("nourriture")) > 0) {
            Cerveau.lancer(t, new Actions.Prendre(Actions.Filtre.de("nourriture"), 8));
            return true;
        }
        // Il reprend des forces au poste (rassasié, ou sous l'effet d'une régénération) ; sinon, il continue sa garde.
        if (t.faim < 15 && !t.hasEffect(MobEffects.REGENERATION)) return false;
        if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 16) { Cerveau.rentrer(t); return true; }
        Cerveau.attendre(t, 5);
        return true;
    }

    // --- Veiller sur la colonie ----------------------------------------------------------------------------

    /**
     * Toutes les deux secondes au plus : un monstre qui s'en prend à un ouvrier de la colonie (ou rôde tout près de lui, ou
     * qu'un ouvrier a signalé), à 48 blocs de lui au plus et dans l'espace de vie ; la nuit, le monstre le plus proche dans
     * l'espace de vie. Null sinon.
     */
    static LivingEntity veille(Travailleur t, boolean nuit) {
        long maintenant = t.level().getGameTime();
        if (t.memoire.getLong("veille") > maintenant || t.bureau == null || t.chef == null) return null;
        t.memoire.putLong("veille", maintenant + 40);
        AABB zone = new AABB(t.bureau).inflate(Travailleur.ESPACE_DE_VIE, 24, Travailleur.ESPACE_DE_VIE);
        for (Travailleur o : t.level().getEntitiesOfClass(Travailleur.class, zone, o -> o != t && o.isAlive() && t.chef.equals(o.chef) && o.distanceToSqr(t) < 48 * 48)) {
            LivingEntity m = null;
            LivingEntity a = o.getLastHurtByMob();
            if (a != null && Monde.hostile(a) && Monde.recent(o, o.getLastHurtByMobTimestamp())) m = a;
            if (m == null && o.memoire.getLong("alerte_t") > maintenant - 600 && t.level().getEntity(o.memoire.getInt("alerte")) instanceof LivingEntity c
                    && (Monde.hostile(c) || Armee.ennemi(t, Armee.ennemis(t), c))) m = c; // un monstre, ou un ennemi en guerre (Armee.fuir)
            if (m == null) m = t.level().getEntitiesOfClass(LivingEntity.class, o.getBoundingBox().inflate(6, 3, 6), Monde::hostile).stream().findFirst().orElse(null);
            if (m != null && !t.inaccessibles.containsKey(m.getId()) && aSaPortee(t, m)) {
                Cerveau.dire(t, "secours", 20, "Tiens bon, " + o.prenom + ", j'arrive !");
                return m;
            }
        }
        // Le jour, les monstres qui approchent du poste (lot 9) ; la nuit, tous ceux de l'espace de vie.
        BlockPos b = t.bureau;
        double r = nuit ? Travailleur.ESPACE_DE_VIE : POSTE;
        LivingEntity m = t.level().getEntitiesOfClass(LivingEntity.class, nuit ? zone : new AABB(b).inflate(POSTE, 12, POSTE),
                e -> Monde.hostile(e) && (e.getX() - b.getX()) * (e.getX() - b.getX()) + (e.getZ() - b.getZ()) * (e.getZ() - b.getZ()) <= r * r
                        && aSaHauteur(t, e) && aSaPortee(t, e) && !t.inaccessibles.containsKey(e.getId())).stream().min(Comparator.comparingDouble(t::distanceToSqr)).orElse(null);
        if (m != null) Cerveau.dire(t, "chasse", 120, nuit ? "Un rôdeur dans la nuit. Je m'en charge." : "Un monstre près du poste. Je m'en charge.");
        // En guerre : un ouvrier ennemi, ou le chef ennemi, dans l'espace de vie (Armee) ; la déclaration est l'ordre.
        if (m == null && (m = Armee.ennemi(t, zone)) != null && Cerveau.parfois(t, "guerre_defense", 30)) Armee.dire(t, "guerre_defense", Armee.ennemis(t).get(Armee.camp(m)), null);
        return m;
    }

    /** L'attaque de ce que sa veille a trouvé : un monstre, ou un ennemi en guerre (cette attaque-là cesse à la paix : {@link Armee#paixFaite}). */
    static Actions.Attaquer contre(Travailleur t, LivingEntity m) {
        double portee = 2.5 * Travailleur.ESPACE_DE_VIE;
        return !Monde.hostile(m) && Armee.camp(m) != null ? Armee.attaque(t, m, portee) : new Actions.Attaquer(m).portee(portee);
    }

    /** Un monstre qu'il peut combattre : pas un creeper sans arc (au corps à corps, il lui sauterait au visage). */
    static boolean aSaPortee(Travailleur t, LivingEntity e) { return !(e instanceof net.minecraft.world.entity.monster.Creeper) || peutTirer(t); }

    /** Comme {@link Monde#cibles} : à sa hauteur (4 blocs) ou en vue, pas un monstre au fond d'une grotte. */
    static boolean aSaHauteur(Travailleur t, LivingEntity e) {
        double dy = Math.abs(e.getY() - t.getY());
        return dy <= 4 || dy < 12 && t.getSensing().hasLineOfSight(e);
    }

    /** Les cibles de {@link Monde#cibles}, sans les creepers s'il n'a pas de quoi tirer. */
    static List<LivingEntity> cibles(Travailleur t, double rayon) {
        List<LivingEntity> c = Monde.cibles(t, rayon);
        if (!peutTirer(t)) c.removeIf(e -> e instanceof net.minecraft.world.entity.monster.Creeper);
        return c;
    }

    // --- S'équiper au dépôt ---------------------------------------------------------------------------------

    /** Le meilleur objet du dépôt (et des coffres collés au poste) selon la note, s'il bat {@code seuil} ; retiré du dépôt si {@code prendre}. */
    static ItemStack auDepot(Bureau.Entite b, ToDoubleFunction<ItemStack> note, double seuil, boolean prendre) {
        double max = seuil;
        int k = -1;
        IItemHandler h = null;
        for (int i = 0; i < b.depot.getContainerSize(); i++) {
            double n = note.applyAsDouble(b.depot.getItem(i));
            if (n > max) { max = n; k = i; }
        }
        for (IItemHandler v : b.voisins())
            for (int i = 0; i < v.getSlots(); i++) {
                double n = note.applyAsDouble(v.getStackInSlot(i));
                if (n > max) { max = n; k = i; h = v; }
            }
        if (k < 0) return ItemStack.EMPTY;
        if (!prendre) return (h == null ? b.depot.getItem(k) : h.getStackInSlot(k)).copyWithCount(1);
        if (h != null) return h.extractItem(k, 1, false);
        ItemStack s = b.depot.removeItem(k, 1);
        b.depot.setChanged();
        return s;
    }

    /** Met dans son sac ce qu'il prend au dépôt ; ce qui n'y entre pas retourne au dépôt (rien ne tombe par terre). Vrai si tout est entré. */
    static boolean garder(Travailleur t, Bureau.Entite b, ItemStack s) {
        ItemStack reste = t.inventaire.addItem(s);
        if (reste.isEmpty()) return true;
        rendre(t, b, reste);
        return false;
    }

    /** Range au dépôt ce qu'il remplace ; ce qui n'y tient pas reste dans son sac. */
    static void rendre(Travailleur t, Bureau.Entite b, ItemStack s) {
        if (s.isEmpty()) return;
        ItemStack reste = b.ranger(s);
        if (!reste.isEmpty()) t.ranger(reste);
    }

    /**
     * Ce que le dépôt a de mieux pour lui : une meilleure arme de mêlée, une meilleure pièce d'armure par case, un arc (ou
     * une arbalète) s'il n'en a pas, des flèches, des potions de soin. Sans {@code faire}, il regarde seulement (vrai s'il y a
     * quelque chose à prendre) ; avec, il échange : ce qu'il remplace va au dépôt. Rien ne se perd, rien ne se crée.
     */
    static boolean revue(Travailleur t, Bureau.Entite b, boolean faire) {
        boolean mieux = false;
        // L'arme de mêlée.
        int k = -1;
        double d = degats(t.getMainHandItem());
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (degats(t.inventaire.getItem(i)) > d) { d = degats(t.inventaire.getItem(i)); k = i; }
        // Il a déjà une arme : seules les épées du dépôt comptent (les haches y sont pour le bûcheron).
        boolean dejaArme = d > 0;
        ItemStack arme = auDepot(b, s -> !dejaArme || s.is(net.minecraft.tags.ItemTags.SWORDS) ? degats(s) : 0, d, faire);
        if (!arme.isEmpty()) {
            mieux = true;
            if (faire) {
                ItemStack vieille = ItemStack.EMPTY;
                if (k >= 0) { vieille = t.inventaire.getItem(k); t.inventaire.setItem(k, arme); }
                else if (MELEE.objet(t.getMainHandItem())) { vieille = t.getMainHandItem(); t.setItemSlot(EquipmentSlot.MAINHAND, arme); }
                else garder(t, b, arme);
                rendre(t, b, vieille);
                armer(t);
            }
        }
        // L'armure, case par case.
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack porte = t.getItemBySlot(slot);
            ItemStack piece = auDepot(b, s -> protection(s, slot), protection(porte, slot), faire);
            if (piece.isEmpty()) continue;
            mieux = true;
            if (faire) { t.setItemSlot(slot, piece); rendre(t, b, porte); }
        }
        // De quoi tirer de loin.
        boolean arc = t.compter(TIR) > 0;
        if (!arc && Monde.libres(t) > 0) {
            ItemStack a = auDepot(b, s -> s.is(Items.CROSSBOW) ? 2 : s.is(Items.BOW) ? 1 : 0, 0, faire);
            if (!a.isEmpty()) { mieux = arc = true; if (faire) garder(t, b, a); }
        }
        int fleches = t.compter(FLECHES);
        if (arc && fleches < 16 && b.compter(FLECHES) > 0 && t.inventaire.canAddItem(new ItemStack(Items.ARROW))) {
            mieux = true;
            if (faire) b.prendre(FLECHES, 32 - fleches).forEach(s -> garder(t, b, s));
        }
        // Deux potions de soin d'avance.
        int potions = 0;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (soignante(t.inventaire.getItem(i))) potions += t.inventaire.getItem(i).getCount();
        for (; potions < 2 && Monde.libres(t) > 0; potions++) {
            ItemStack p = auDepot(b, s -> soignante(s) ? 1 : 0, 0, faire);
            if (p.isEmpty()) break;
            mieux = true;
            if (!faire) break;
            garder(t, b, p);
        }
        if (faire) t.swing(InteractionHand.MAIN_HAND);
        return mieux;
    }

    /** Au poste, il passe au dépôt prendre ce qu'il y a de mieux (et y laisse ce qu'il remplace). */
    static final class Armurerie extends Actions.AuBureau {
        Armurerie() { super("s_equiper"); }

        Actions.Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            if (revue(t, b, true)) Cerveau.dire(t, "equipe", 300, "Me voilà mieux équipé.");
            return Actions.Etat.OK;
        }
    }

    // --- Le mannequin -------------------------------------------------------------------------------------

    /** Un porte-armure près du poste : il s'y entraîne quelques coups, sans l'abîmer ni lui prendre quoi que ce soit. */
    static final class Mannequin extends Actions.Action {
        final ArmorStand cible;
        int coups;

        Mannequin(ArmorStand cible) { super("mannequin"); this.cible = cible; }

        Actions.Etat tick(Travailleur t) {
            if (!cible.isAlive() || age > 20 * 30) return Actions.Etat.OK;
            t.getLookControl().setLookAt(cible);
            if (t.distanceToSqr(cible) > 2.2 * 2.2) {
                if (coince()) return echec("bloque");
                marcher(t, cible.position(), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            if (age % 14 == 0) {
                t.swing(InteractionHand.MAIN_HAND);
                t.level().broadcastEntityEvent(cible, (byte) 32); // le mannequin vacille et sonne, comme sous un coup de joueur
                t.memoire.putInt("coups_mannequin", t.memoire.getInt("coups_mannequin") + 1);
                coups++;
            }
            return coups >= 8 ? Actions.Etat.OK : Actions.Etat.EN_COURS;
        }
    }

    // --- Le métier ----------------------------------------------------------------------------------------

    /** Un ennemi surgit pendant qu'il fait autre chose (il marche, il est au bureau…) : il lâche tout. */
    @Override
    void vigilance(Travailleur t) {
        // Au plus mal en plein combat : il boit une potion et recule un instant.
        if (t.action instanceof Actions.Attaquer a) {
            if (Armee.paixFaite(t, a)) { t.finirAction("paix"); return; } // la paix est faite : il baisse son arme
            if (t.getHealth() < t.getMaxHealth() * 0.4 && boire(t)) {
                Cerveau.dire(t, "boire", 20, "Je recule, le temps de boire un coup !");
                Cerveau.lancer(t, new Actions.Fuir(a.cible));
            }
            return;
        }
        if (t.action instanceof Actions.Echapper || t.action instanceof Actions.Fuir) return;
        // Au repos (la nuit, hors service), il garde l'oreille ouverte : un ouvrier en danger, un monstre près du poste, il
        // se lève et y va (lot 9). Il reprend son repos ensuite.
        if (t.action instanceof Actions.Reposer) {
            LivingEntity m = t.bureau != null && t.chezLui(t.blockPosition()) ? veille(t, false) : null;
            if (m != null) {
                Cerveau.dire(t, "debout", 60, "Debout ! On attaque la colonie !");
                armer(t);
                Cerveau.lancer(t, contre(t, m));
                return;
            }
            if (t.isSleeping()) return;
        }
        // Une consigne de combat (tenir une porte, protéger quelqu'un, se replier) mène elle-même sa lutte.
        if (t.action instanceof Consignes.Faire f && f.e.comportement().equals("combat")) return;
        List<LivingEntity> c = cibles(t, 10);
        if (!c.isEmpty()) { attaquer(t, c.get(0)); return; }
        // En guerre, un ennemi tout près (une fois par seconde au plus) : il le frappe, sans autre ordre (la déclaration en est un).
        if ((t.tickCount + t.getId()) % 20 < 10 && !(t.action instanceof Consignes.Faire)) {
            LivingEntity e = Armee.ennemi(t, t.getBoundingBox().inflate(10, 4, 10));
            if (e != null && (t.memoire.contains("guerre_assaut") || t.bureau != null && t.chezLui(e.blockPosition()))) Cerveau.lancer(t, Armee.attaque(t, e, 2.5 * Travailleur.ESPACE_DE_VIE));
        }
    }

    @Override
    boolean escorter(Travailleur t) {
        List<LivingEntity> c = cibles(t, 12);
        if (c.isEmpty()) return false;
        attaquer(t, c.get(0));
        return true;
    }

    @Override
    void travailler(Travailleur t) {
        armer(t);
        // La guerre : l'assaut d'une colonie ennemie, ou le butin à déposer au retour (Armee).
        if (Armee.assaut(t)) return;
        Commande c = Metier.commande(t);
        String id = c == null ? "garder" : c.id;

        if (id.equals("escorter")) {
            ServerPlayer chef = t.chefJoueur();
            if (chef != null && chef.level() == t.level()) {
                if (!escorter(t)) Cerveau.lancer(t, new Actions.SuivreChef());
                return;
            }
            // Le chef n'est pas là (déconnecté, ou dans une autre dimension) : il garde le bureau en l'attendant.
            Cerveau.dire(t, "chef_absent", 1800, "Tu n'es pas là, chef : je garde le poste en t'attendant.");
            id = "garder";
        }

        BlockPos b = t.bureau;
        // La nuit, la garde devient une ronde autour du poste (lot 9) : huit postes à 8 et 12 blocs.
        boolean nuitGarde = id.equals("garder") && Abri.nuit(t.level());
        int r = nuitGarde ? 20 : id.equals("garder") ? 12 : Travailleur.ESPACE_DE_VIE;
        List<LivingEntity> cibles = cibles(t, Math.min(r, 32));
        if (!cibles.isEmpty()) { Cerveau.lancer(t, new Actions.Attaquer(cibles.get(0))); return; }
        if (soigner(t)) return;
        // Un ouvrier de la colonie en danger ; la nuit, les rôdeurs autour de la colonie. Il revient ensuite à son poste.
        LivingEntity m = veille(t, Abri.nuit(t.level()));
        if (m != null) { armer(t); Cerveau.lancer(t, contre(t, m)); return; }
        if (t.distanceToSqr(Vec3.atBottomCenterOf(b)) > r * r) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(b), 3)); return; }

        // Près du poste, de temps en temps : ce que le dépôt a de mieux pour lui.
        long maintenant = Cerveau.temps(t);
        Bureau.Entite depot = Bureau.entite(t);
        if (depot != null && t.memoire.getLong("revue") <= maintenant && t.distanceToSqr(Vec3.atBottomCenterOf(b)) < 16 * 16) {
            t.memoire.putLong("revue", maintenant + 60);
            if (revue(t, depot, false)) { Cerveau.lancer(t, new Armurerie()); return; }
        }

        // Une ronde, ce n'est pas marcher au hasard : le parcours que le chef lui a montré, sinon un circuit de huit postes
        // autour du bureau, toujours le même. Pour garder le bureau : quatre postes tout près, avec une pause à chacun.
        boolean ronde = id.equals("ronde") || nuitGarde;
        long[] parcours = id.equals("ronde") ? t.memoire.getLongArray("ronde_points") : new long[0];
        if (nuitGarde && Cerveau.parfois(t, "ronde_nuit", 900)) Besoins.dire(t, "ronde_nuit", null);
        int postes = ronde ? parcours.length > 0 ? parcours.length : 8 : 4;
        if (t.memoire.getLong("pause") > maintenant) { Cerveau.attendre(t, 2); return; }
        int poste = (t.memoire.getInt("poste") + 1) % postes;
        t.memoire.putInt("poste", poste);
        t.memoire.putLong("pause", maintenant + (ronde ? 4 : 15));
        // À la garde, sans menace, il s'entraîne de temps en temps sur un mannequin près du poste (une fois par tour).
        if (!ronde && poste == 1) {
            ArmorStand mannequin = t.level().getEntitiesOfClass(ArmorStand.class, new AABB(b).inflate(10, 3, 10), e -> e.isAlive() && !e.isMarker())
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(Vec3.atCenterOf(b)))).orElse(null);
            if (mannequin != null) { Cerveau.dire(t, "mannequin", 600, "Un peu d'exercice, ça garde le bras ferme."); Cerveau.lancer(t, new Mannequin(mannequin)); return; }
        }
        Vec3 but;
        if (ronde && parcours.length > 0) but = Vec3.atBottomCenterOf(BlockPos.of(parcours[poste]));
        else {
            double rayon = nuitGarde ? (poste % 2 == 0 ? 8 : 12) : ronde ? (poste % 2 == 0 ? 24 : 42) : 6;
            double a = poste * 2 * Math.PI / postes;
            int x = b.getX() + (int) Math.round(Math.cos(a) * rayon), z = b.getZ() + (int) Math.round(Math.sin(a) * rayon);
            int y = Monde.surface(t.level(), x, z);
            but = new Vec3(x + 0.5, y == Integer.MIN_VALUE ? b.getY() : y, z + 0.5);
        }
        Actions.Aller aller = new Actions.Aller(but, 2, !(ronde && parcours.length > 0));
        aller.travail = true; // arrivé à son poste de ronde : il fait son travail
        Cerveau.lancer(t, aller);
    }

    // --- Le parcours de la ronde ----------------------------------------------------------------------------

    /** « Retiens ce point pour ta ronde » : ajoute l'endroit au parcours ; renvoie ce qu'il répond. */
    static String retenirPoint(Travailleur t, BlockPos p) {
        if (p == null) return "Où ça, chef ? Regarde l'endroit en me le disant.";
        if (t.bureau != null && !t.chezLui(p)) return "C'est trop loin du poste, chef : ma ronde reste autour de chez nous.";
        long[] l = t.memoire.getLongArray("ronde_points");
        if (l.length >= POINTS_MAX) return "Ma ronde compte déjà " + POINTS_MAX + " points, chef. Efface-la pour en tracer une autre.";
        long[] n = java.util.Arrays.copyOf(l, l.length + 1);
        n[l.length] = p.asLong();
        t.memoire.putLongArray("ronde_points", n);
        t.memoire.putInt("poste", n.length - 1); // il repart du premier point à sa prochaine ronde
        return n.length == 1 ? "C'est noté, chef : premier point de ma ronde." : "C'est noté, chef : point " + n.length + " de ma ronde.";
    }

    /** « Efface ta ronde » : il revient au cercle autour du poste. */
    static String effacerRonde(Travailleur t) {
        if (!t.memoire.contains("ronde_points")) return "Je n'ai pas de parcours, chef : je tourne autour du poste.";
        t.memoire.remove("ronde_points");
        return "C'est effacé, chef : je referai ma ronde autour du poste.";
    }
}

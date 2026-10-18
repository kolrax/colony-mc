package fr.lacolonie.pnj;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Les blessures (lot 5), côté jeu : la gravité ({@link Gravite}) de chaque coup reçu, gardée dans sa mémoire (NBT
 * « Etat » : {@code blessure}, {@code blessure_le}, en temps du jour du monde), la lenteur qu'elle donne, le repos forcé au
 * poste quand elle est très grave, et le rassemblement des autres ouvriers autour d'un blessé à terre.
 * <p>
 * Rien à chaque tick : la gravité se calcule à la demande, la lenteur se vérifie une fois par seconde, et les voisins ne
 * sont cherchés qu'une fois, au moment où il tombe.
 */
final class Blessures {
    private Blessures() {}

    static final ResourceLocation LENTEUR = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "blessure");
    /** Qui accourt : les ouvriers du même chef à tant de blocs (8 en hauteur), au plus {@link #ACCOURENT}. */
    static final int RAYON = 24, ACCOURENT = 3;

    static Gravite.Source source(DamageSource src) {
        if (src.is(DamageTypeTags.IS_EXPLOSION)) return Gravite.Source.EXPLOSION;
        if (src.is(DamageTypeTags.IS_FIRE)) return Gravite.Source.FEU;
        if (src.is(DamageTypeTags.IS_FALL)) return Gravite.Source.CHUTE;
        if (src.is(DamageTypes.STARVE) || src.is(DamageTypeTags.IS_DROWNING) || src.is(DamageTypes.IN_WALL) || src.is(DamageTypes.MAGIC)
                || src.is(DamageTypes.INDIRECT_MAGIC) || src.is(DamageTypes.WITHER) || src.is(DamageTypeTags.IS_FREEZING)) return Gravite.Source.SANS;
        return Gravite.Source.COUP;
    }

    /** Sa blessure, ce qu'il en reste aujourd'hui. */
    static Gravite gravite(Travailleur t) {
        if (!t.memoire.contains("blessure")) return Gravite.AUCUNE;
        return Gravite.actuelle(Gravite.de(t.memoire.getInt("blessure")), t.memoire.getLong("blessure_le"), t.level().getDayTime());
    }

    static void noter(Travailleur t, Gravite g) {
        if (g == Gravite.AUCUNE) { t.memoire.remove("blessure"); t.memoire.remove("blessure_le"); }
        else { t.memoire.putInt("blessure", g.ordinal()); t.memoire.putLong("blessure_le", t.level().getDayTime()); }
        lenteur(t);
    }

    /** Un coup qui ne le met pas à terre : la blessure s'ajoute à la sienne, et il le dit (au plus une fois par 10 min). */
    static void blesser(Travailleur t, Gravite coup) {
        if (coup == Gravite.AUCUNE) return;
        Gravite avant = gravite(t), g = Gravite.cumuler(avant, coup);
        if (g == avant) return; // la même gravité : la date ne bouge pas (sinon, frappé chaque nuit, il ne guérirait jamais)
        noter(t, g);
        if (g.cle() != null) Besoins.parler(t, g.cle(), null);
    }

    /** La réplique de blessure que dit {@link Besoins#veiller} : sa blessure, sinon sa vie basse (la faim, par exemple). */
    static String cle(Travailleur t) {
        Gravite g = gravite(t);
        return g != Gravite.AUCUNE ? g.cle() : Vie.blessure(t.getHealth(), t.getMaxHealth());
    }

    /** Une fois par seconde : la lenteur de sa blessure (grave −15 %, très grave −30 %), ôtée quand elle guérit. */
    static void lenteur(Travailleur t) {
        var a = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        // L'heure remise en arrière (« /time set day ») : la blessure repart de maintenant, sinon elle ne guérirait plus.
        if (t.memoire.contains("blessure") && t.level().getDayTime() < t.memoire.getLong("blessure_le")) {
            // Déjà pansée par le guérisseur : elle le reste (soigne_le suit la date de la blessure).
            if (Guerisseur.soigne(t)) t.memoire.putLong("soigne_le", t.level().getDayTime());
            t.memoire.putLong("blessure_le", t.level().getDayTime());
        }
        Gravite g = gravite(t);
        if (g == Gravite.AUCUNE && t.memoire.contains("blessure")) { t.memoire.remove("blessure"); t.memoire.remove("blessure_le"); }
        AttributeModifier m = a.getModifier(LENTEUR);
        double voulu = -g.lenteur();
        if (m != null && m.amount() == voulu) return;
        if (m != null) a.removeModifier(LENTEUR);
        if (voulu != 0) a.addTransientModifier(new AttributeModifier(LENTEUR, voulu, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /**
     * Juste avant de travailler : blessé très gravement, un mineur ou un guerrier ne descend pas à la mine et ne se bat
     * pas. Il rentre au poste et s'y repose (le guerrier boit ou mange d'abord : {@link Guerrier#soigner}). Une consigne
     * du chef (un ordre de guerre) passe avant : le cerveau la joue plus tôt. Vrai s'il s'en occupe.
     */
    static boolean auRepos(Travailleur t, Metier m) {
        if (!gravite(t).auRepos() || !(m.combattant || "mineur".equals(t.metier) || releveSeul(t)) || t.bureau == null) return false;
        if (m instanceof Guerrier && Guerrier.soigner(t)) return true;
        if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 5 * 5) { Cerveau.rentrer(t); return true; }
        Besoins.parler(t, "blessure_grave", null);
        Cerveau.attendre(t, 10);
        return true;
    }

    /** La mémoire (NBT « Etat ») du moment où il s'est relevé seul, en temps de jeu (insensible à « /time set »). */
    static final String RELEVE_SEUL = "releve_seul_t";

    /** Il s'est relevé seul il y a moins de {@link Reglages#releve_seul_minutes} minutes de jeu : encore « blessé après un relèvement ». */
    static boolean releveSeul(Travailleur t) {
        if (!t.memoire.contains(RELEVE_SEUL)) return false;
        long d = t.level().getGameTime() - t.memoire.getLong(RELEVE_SEUL);
        return d >= 0 && d < Reglages.actuel.releve_seul_minutes * 60L * 20;
    }

    /**
     * Plan E : la fin de son temps à terre, sans aide. La première fois, il se relève seul avec peu de vie et une blessure
     * très grave (ralenti, il laisse son ouvrage, rentre au poste et s'y repose jusqu'à guérir : {@link #auRepos}), et le
     * journal le dit. Faux (il meurt) s'il s'était déjà relevé seul il y a peu, ou s'il gît dans la lave.
     */
    static boolean seRelever(Travailleur t) {
        if (releveSeul(t) || t.isInLava()) return false;
        t.relever(null);
        t.setHealth(Math.min(t.getMaxHealth(), Reglages.actuel.releve_seul_vie));
        t.memoire.putLong(RELEVE_SEUL, t.level().getGameTime());
        noter(t, Gravite.TRES_GRAVE);
        Consignes.annuler(t); // il laisse l'ouvrage en cours, même un ordre du chef
        if (t.bureau != null) Cerveau.rentrer(t);
        ColoniePnj.LOG.info("{} ({}, chef {}) se relève seul en {}", t.prenom, t.metier, t.chef, t.blockPosition().toShortString());
        Courrier.ecrireTexte(t, "Relevé seul", "Personne n'est venu : je me suis relevé seul en " + position(t)
                + ", bien mal en point. Je rentre au poste me reposer. Si je retombe avant d'être guéri, je n'y survivrai pas.", true);
        return true;
    }

    static String position(Travailleur t) { return t.getBlockX() + " " + t.getBlockY() + " " + t.getBlockZ(); }

    /** Il vient de tomber à terre : son chef, s'il est là, le lit aussi dans la discussion, en rouge, avec le lieu exact. */
    static void prevenirChef(Travailleur t) {
        ServerPlayer c = t.chefJoueur();
        if (c == null) return;
        c.sendSystemMessage(Component.literal("⚠ " + t.prenom + " est à terre en " + position(t)
                + " ! Porte-lui vite à manger ou une potion de soin.").withStyle(ChatFormatting.RED));
    }

    /** La case de son sac qui peut relever un blessé (à manger, une potion de soin), ou -1. */
    static int remede(Travailleur t) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (Travailleur.soigne(t.inventaire.getItem(i))) return i;
        return -1;
    }

    /** Très gravement blessé : un guerrier ne cherche plus la bagarre (il recule s'il est frappé). */
    static boolean horsDeCombat(Travailleur t) { return gravite(t).auRepos(); }

    /**
     * Il vient de tomber à terre : les ouvriers de son chef à {@link #RAYON} blocs, au travail (ni « suis-moi », ni
     * « attends ici », ni consigne), hors de tout danger, éveillés, accourent autour de lui : les {@link #ACCOURENT} plus
     * proches. Un guerrier qui garde (le poste, son chef) reste à sa garde ; celui qui fait sa ronde vient.
     */
    static void accourir(Travailleur blesse) {
        if (blesse.chef == null) return;
        Guerisseur.alerter(blesse); // le guérisseur vient le relever
        List<Travailleur> proches = blesse.level().getEntitiesOfClass(Travailleur.class, blesse.getBoundingBox().inflate(RAYON, 8, RAYON),
                        o -> o != blesse && blesse.chef.equals(o.chef) && o.isAlive() && !o.aTerre
                                && o.distanceToSqr(blesse) <= RAYON * RAYON && peutAccourir(o))
                .stream().sorted(Comparator.comparing((Travailleur o) -> remede(o) < 0).thenComparingDouble(o -> o.distanceToSqr(blesse))).limit(ACCOURENT).collect(java.util.stream.Collectors.toList());
        Travailleur conjoint = null;
        for (int i = 0; i < proches.size(); i++) {
            Accourir a = new Accourir(blesse, i);
            a.inquiet = proches.get(i) == conjoint;
            Cerveau.lancer(proches.get(i), a);
        }
    }

    static boolean peutAccourir(Travailleur o) {
        if ((o.mode != Travailleur.Mode.TRAVAIL && o.mode != Travailleur.Mode.GREVE) || o.consigne != null || o.isSleeping() || o.memoire.getBoolean("rappel")
                || o.action instanceof Accourir || o.lettreEnMain != null || o.enDanger() || "guerisseur".equals(o.metier)) return false;
        if (Metier.de(o.metier) instanceof Guerrier) {
            Metier.Commande c = Metier.commande(o);
            return c != null && c.id.equals("ronde");
        }
        return true;
    }

    /**
     * Il se tient à 2 ou 3 blocs du blessé, le regarde, lui dit un mot ; il repart quand le blessé se relève ou meurt (au
     * plus 9 minutes). Un coup, un ordre ou un danger l'interrompent comme toute action. S'il a à manger ou une potion de
     * soin dans son sac (plan E), il la lui donne et le relève, comme le ferait le chef.
     */
    static final class Accourir extends Actions.Action {
        final Travailleur blesse;
        final int rang;
        boolean arrive;
        int aupres;
        /** L'être aimé du blessé : il se tient tout près, et le dit (« couple_inquiet »). */
        boolean inquiet;

        Accourir(Travailleur blesse, int rang) {
            super("accourir");
            this.blesse = blesse;
            this.rang = rang;
        }

        /** Sa place autour du blessé : un tiers de tour chacun, à 2,5 blocs. */
        Vec3 place() {
            double a = rang * 2 * Math.PI / ACCOURENT + (blesse.getId() % 7);
            double r = inquiet ? 1.5 : 2.5;
            return blesse.position().add(Math.cos(a) * r, 0, Math.sin(a) * r);
        }

        Actions.Etat tick(Travailleur t) {
            if (!blesse.isAlive() || blesse.isRemoved() || !blesse.aTerre || age > 9 * 60 * 20) return Actions.Etat.OK;
            double d = t.distanceToSqr(blesse);
            if (!arrive) {
                if (d > 3.2 * 3.2 && !coince()) { marcher(t, place(), 1.0); return Actions.Etat.EN_COURS; }
                if (d > 6 * 6) return Actions.Etat.ECHEC; // pas de passage jusqu'à lui
                arrive = true;
                t.getNavigation().stop();
                if (inquiet) Besoins.dire(t, "couple_inquiet", blesse.prenom);
                else if (Cerveau.parfois(t, "blesse_vu", 60)) Besoins.dire(t, "blesse_vu", blesse.prenom);
            }
            t.getLookControl().setLookAt(blesse);
            int i = remede(t);
            if (i >= 0 && ++aupres >= Soins.RELEVER) {
                ItemStack r = t.inventaire.getItem(i).split(1);
                t.inventaire.setChanged();
                if (r.is(Items.POTION)) t.ranger(new ItemStack(Items.GLASS_BOTTLE));
                t.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                Sons.soin(t);
                blesse.relever(null);
                t.dire("Tiens, " + blesse.prenom + ", prends ça et relève-toi.");
                return Actions.Etat.OK;
            }
            return Actions.Etat.EN_COURS;
        }
    }
}

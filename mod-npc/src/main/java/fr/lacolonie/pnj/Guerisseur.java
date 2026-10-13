package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Le guérisseur (lot 5, cahier 1.11 et 4.2). Il veille sur les ouvriers de son chef dans son espace de vie : celui qui tombe
 * à terre, il accourt le relever, avec un pain ou une potion de soin (le sien, sinon pris au dépôt), comme le ferait le chef ;
 * celui qui est gravement ou très gravement blessé, il va le panser (quelques secondes à genoux près de lui) : sa blessure
 * baisse d'un cran et il reprend un tiers de sa vie, une fois par blessure. Sinon, il attend au poste.
 * <p>
 * Il apprend la chute d'un ouvrier au moment même ({@link #alerter}, appelé par {@link Blessures#accourir}) ; il ne passe en
 * revue les blessés qu'au plus toutes les {@link Soins#REVUE} secondes. Jamais : lâcher un ordre de son chef (« attends ici »,
 * « suis-moi », une consigne), sortir de son espace de vie, ni aller vers un blessé qu'un monstre menace (il attend).
 */
final class Guerisseur extends Metier {
    /** Ce qui relève un ouvrier à terre : un pain ou une potion de soin (ou de régénération). */
    static final Actions.Filtre REMEDE = Actions.Filtre.de("minecraft:bread|soin");

    Guerisseur() {
        super("guerisseur", "Guérisseur", "Veille sur les ouvriers de son chef autour du poste : il relève ceux qui tombent, avec un pain ou une potion "
                + "de soin pris au dépôt, et panse les blessures graves. Il ne s'éloigne pas de chez lui et ne lâche jamais un ordre de son chef.",
                false, "minecraft:bread", "soin");
        commandes.add(new Commande("veiller", "Veiller sur les blessés", null));
    }

    @Override
    void travailler(Travailleur t) {
        if (secourir(t)) return;
        if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 4 * 4) { Cerveau.rentrer(t); return; }
        Cerveau.attendre(t, Soins.REVUE);
    }

    // --- Qui secourir ---------------------------------------------------------------------------------------

    /** Au plus toutes les {@link Soins#REVUE} secondes : le premier blessé à secourir ; vrai s'il y va (ou attend qu'un monstre s'en aille). */
    static boolean secourir(Travailleur t) {
        if (t.chef == null || t.bureau == null) return false;
        long maintenant = Cerveau.temps(t);
        if (t.memoire.getLong("revue_blesses") > maintenant) return false;
        t.memoire.putLong("revue_blesses", maintenant + Soins.REVUE);
        // Celui qu'il n'a pas pu atteindre (un enclos fermé, un trou) : laissé une minute, sans chercher de chemin à chaque revue.
        Travailleur o = prochain(t, false, t.memoire.getLong("soin_laisse_t") > maintenant ? Set.of(t.memoire.getInt("soin_laisse")) : Set.of());
        if (o == null) return false;
        Cerveau.lancer(t, new Soin(o));
        return true;
    }

    /** Les ouvriers de son chef dans son espace de vie (lui excepté). */
    static List<Travailleur> colonie(Travailleur t) {
        return t.level().getEntitiesOfClass(Travailleur.class, new AABB(t.bureau).inflate(Travailleur.ESPACE_DE_VIE, 24, Travailleur.ESPACE_DE_VIE),
                o -> o != t && o.isAlive() && t.chef.equals(o.chef) && t.chezLui(o.blockPosition()));
    }

    /** Le prochain à secourir ({@link Soins#choisir}), hors {@code exclus} et ceux qu'un autre guérisseur soigne déjà ; null : personne. */
    static Travailleur prochain(Travailleur t, boolean legeres, Set<Integer> exclus) {
        if (t.chef == null || t.bureau == null) return null;
        List<Travailleur> gens = colonie(t);
        List<Travailleur> blesses = new ArrayList<>();
        List<Soins.Cas> cas = new ArrayList<>();
        for (Travailleur o : gens) {
            if (exclus.contains(o.getId()) || gens.stream().anyMatch(x -> x.action instanceof Soin s && s.blesse == o)) continue;
            Gravite g = Blessures.gravite(o);
            boolean soigne = soigne(o);
            if (!o.aTerre && !Soins.aPanser(g, soigne, legeres)) continue;
            blesses.add(o);
            cas.add(new Soins.Cas(o.aTerre, o.aTerreTicks, g, soigne, o.distanceToSqr(t), menace(o)));
        }
        int k = Soins.choisir(cas, legeres);
        if (k < 0 && Soins.attendre(cas, legeres)) Cerveau.dire(t, "soin_danger", 60, "Un monstre rôde près d'un blessé : j'attends que la voie soit libre.");
        return k < 0 ? null : blesses.get(k);
    }

    /** Déjà soigné de cette blessure (la date de la blessure est celle qu'il a soignée). */
    static boolean soigne(Travailleur o) {
        return o.memoire.contains("soigne_le") && o.memoire.contains("blessure_le") && o.memoire.getLong("soigne_le") == o.memoire.getLong("blessure_le");
    }

    /** Un monstre à 8 blocs de lui. */
    static boolean menace(Travailleur o) {
        return !o.level().getEntitiesOfClass(LivingEntity.class, o.getBoundingBox().inflate(8, 4, 8), Monde::hostile).isEmpty();
    }

    /**
     * Un ouvrier vient de tomber à terre ({@link Blessures#accourir}) : le guérisseur de son chef le plus proche, libre (au
     * travail, sans ordre ni consigne, pas déjà en train d'en relever un autre), qui a le blessé dans son espace de vie et
     * aucun monstre sur le chemin, accourt tout de suite, même endormi.
     */
    static void alerter(Travailleur blesse) {
        if (blesse.chef == null) return;
        int r = 2 * Travailleur.ESPACE_DE_VIE;
        blesse.level().getEntitiesOfClass(Travailleur.class, blesse.getBoundingBox().inflate(r, 32, r),
                        g -> g != blesse && "guerisseur".equals(g.metier) && blesse.chef.equals(g.chef) && g.isAlive() && !g.aTerre && libre(g)
                                && g.chezLui(blesse.blockPosition()) && !g.enDanger())
                .stream().min(java.util.Comparator.comparingDouble(g -> g.distanceToSqr(blesse)))
                .ifPresent(g -> {
                    if (menace(blesse)) { Cerveau.dire(g, "soin_danger", 60, "Un monstre rôde près de " + blesse.prenom + " : j'attends que la voie soit libre."); return; }
                    Cerveau.lancer(g, new Soin(blesse));
                    Cerveau.dire(g, "accourt", 20, "J'arrive, " + blesse.prenom + " ! Ne bouge pas.");
                });
    }

    /** Libre d'accourir : au travail, sans ordre du chef en cours, pas déjà auprès d'un ouvrier à terre. */
    static boolean libre(Travailleur g) {
        return g.mode == Travailleur.Mode.TRAVAIL && g.bureau != null && g.consigne == null && !(g.action instanceof Consignes.Faire)
                && !g.memoire.getBoolean("rappel") && g.lettreEnMain == null && !(g.action instanceof Soin s && s.blesse.aTerre);
    }

    // --- Les ordres -----------------------------------------------------------------------------------------

    /** « Soigne Jean » (la personne visée par la consigne), « va soigner les blessés ». */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) {
        if (mode.equals("soigner_blesses")) return new Tournee();
        Entity e = c.entite == null || !(t.level() instanceof ServerLevel sl) ? null : sl.getEntity(c.entite);
        if (e instanceof Player) return new Refus("Toi, chef ? Mange un morceau et repose-toi. Moi, je soigne nos gens.");
        if (!(e instanceof Travailleur o) || !o.isAlive()) return new Refus("Je ne le vois nulle part, chef.");
        if (!t.chezLui(o.blockPosition())) return new Refus("C'est trop loin de mon poste, chef : je soigne près de chez nous.");
        if (!o.aTerre && soigne(o)) return new Refus("J'ai déjà pansé " + o.prenom + ", chef. Il lui faut du repos, maintenant.");
        if (!o.aTerre && Blessures.gravite(o) == Gravite.AUCUNE && o.getHealth() >= o.getMaxHealth()) return new Refus(o.prenom + " n'a rien, chef.");
        return new Soin(o);
    }

    /** Un ordre qu'il ne peut pas faire : il dit pourquoi. */
    static final class Refus extends Actions.Action {
        final String texte;

        Refus(String texte) { super("refus"); this.texte = texte; }

        Actions.Etat tick(Travailleur t) {
            Voix.dire(t, texte, "calme");
            return echec("refus");
        }
    }

    /** « Va soigner les blessés » : un à un, ceux de son espace de vie (les blessures légères aussi), puis il le dit. */
    static final class Tournee extends Actions.Action {
        final Set<Integer> vus = new HashSet<>();
        Soin sous;
        int soignes;

        Tournee() { super("tournee_soins"); }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t);
            sous = null;
        }

        Actions.Etat tick(Travailleur t) {
            if (sous != null) {
                Actions.Etat r = sous.tick(t);
                sous.age++;
                if (r == Actions.Etat.EN_COURS) return r;
                if (r == Actions.Etat.OK) soignes++;
                vus.add(sous.blesse.getId());
                sous.fin(t);
                sous = null;
            }
            Travailleur o = prochain(t, true, vus);
            if (o == null) {
                Voix.dire(t, soignes == 0 ? "Je n'ai trouvé personne à soigner près du poste, chef." : "Tous nos blessés sont soignés, chef.", "calme");
                return Actions.Etat.OK;
            }
            sous = new Soin(o);
            return Actions.Etat.EN_COURS;
        }
    }

    // --- Le soin --------------------------------------------------------------------------------------------

    /**
     * Il va auprès du blessé et s'agenouille. À terre : il le relève avec un remède ({@link #REMEDE}) qu'il a sur lui, sinon
     * qu'il passe prendre au dépôt (s'il n'y en a pas, il le dit) ; la potion bue laisse sa fiole. Debout : il le panse, la
     * blessure baisse d'un cran et il reprend un tiers de sa vie ({@link Soins}), une fois par blessure. Le blessé debout
     * s'arrête un instant pour se laisser soigner (sauf ordre de son chef ou danger). Un monstre près de l'un ou de l'autre,
     * ou le blessé hors de l'espace de vie : il renonce (la revue suivante le reprendra).
     */
    static final class Soin extends Actions.AuBureau {
        final Travailleur blesse;
        boolean pret;
        int aupres;

        Soin(Travailleur blesse) {
            super("soigner");
            this.blesse = blesse;
            this.mobile = true;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.setGeste("");
        }

        Actions.Etat tick(Travailleur t) {
            if (!blesse.isAlive() || blesse.isRemoved()) return echec("mort");
            if (!t.chezLui(blesse.blockPosition())) return echec("trop_loin");
            if (age % 10 == 0 && (t.enDanger() || menace(blesse))) {
                Cerveau.dire(t, "soin_danger", 60, "Un monstre rôde près de " + blesse.prenom + " : j'attends que la voie soit libre.");
                return echec("danger");
            }
            boolean aTerre = blesse.aTerre;
            if (!aTerre && soigne(blesse)) return Actions.Etat.OK; // un autre l'a pansé entre-temps
            // Le remède, pour un ouvrier à terre : le sien, sinon celui du dépôt.
            if (aTerre && !pret) {
                if (t.compter(REMEDE) > 0) pret = true;
                else {
                    Bureau.Entite b = arriver(t);
                    if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
                    List<ItemStack> pris = b.prendre(REMEDE, 1);
                    if (pris.isEmpty()) {
                        Cerveau.dire(t, "sans_remede", 60, "Plus de pain ni de potion de soin au dépôt ! Il m'en faut pour relever " + blesse.prenom + ".");
                        return echec("rien_au_depot");
                    }
                    pris.forEach(t::ranger);
                    pret = true;
                }
            }
            if (t.distanceToSqr(blesse) > 2.2 * 2.2) {
                if (coince()) {
                    t.memoire.putInt("soin_laisse", blesse.getId());
                    t.memoire.putLong("soin_laisse_t", Cerveau.temps(t) + 60);
                    Cerveau.dire(t, "soin_inaccessible", 60, "Je n'arrive pas jusqu'à " + blesse.prenom + " !");
                    return echec("inaccessible");
                }
                marcher(t, blesse.position(), aTerre ? 1.2 : 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(blesse);
            t.setGeste("a_genoux");
            // Debout, il s'arrête le temps du soin (pas s'il obéit à son chef, s'il dort ou s'il est menacé).
            if (!aTerre && aupres == 0 && blesse.mode == Travailleur.Mode.TRAVAIL && blesse.consigne == null && !blesse.isSleeping() && !blesse.menace())
                Cerveau.lancer(blesse, new Actions.Attendre(Soins.PANSER + 20));
            if (!aTerre) blesse.getLookControl().setLookAt(t);
            if (++aupres < (aTerre ? Soins.RELEVER : Soins.PANSER)) return Actions.Etat.EN_COURS;
            t.swing(InteractionHand.MAIN_HAND);
            if (aTerre) {
                ItemStack r = Actions.sortir(t, REMEDE);
                if (r.isEmpty() && REMEDE.objet(t.getMainHandItem())) r = t.getMainHandItem().split(1);
                if (r.isEmpty()) { pret = false; aupres = 0; return Actions.Etat.EN_COURS; } // il l'a mangé en route : il en reprend
                if (r.is(Items.POTION)) t.ranger(new ItemStack(Items.GLASS_BOTTLE));
                blesse.relever(null);
                Besoins.dire(t, "guerisseur_soigne", blesse.prenom);
                return Actions.Etat.OK;
            }
            Gravite g = Soins.apres(Blessures.gravite(blesse));
            Blessures.noter(blesse, g);
            if (g == Gravite.AUCUNE) blesse.memoire.remove("soigne_le");
            else blesse.memoire.putLong("soigne_le", blesse.memoire.getLong("blessure_le"));
            blesse.setHealth(Soins.vie(blesse.getHealth(), blesse.getMaxHealth()));
            Besoins.dire(blesse, "soigne", t.prenom);
            Besoins.dire(t, "guerisseur_soigne", blesse.prenom);
            return Actions.Etat.OK;
        }
    }
}

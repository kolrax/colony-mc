package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * La nuit dehors (lot 9, cahier 14.12) : loin du poste, la nuit tombée, l'ouvrier se cache comme un joueur prudent. Il
 * creuse sous ses pieds un trou d'un bloc de large et de {@link #PROFONDEUR} de profondeur, dans de la terre ou de la
 * pierre naturelle (jamais du sable ni du gravier, jamais près d'un liquide ou d'un vide, jamais une construction), s'y
 * laisse descendre et le ferme d'un bloc au-dessus de sa tête. Il y dort jusqu'au jour. Au matin, il reprend ce bloc et
 * remonte en posant sous lui les blocs tirés du trou (comme le pilier d'{@link Itineraire}) : le trou est rebouché.
 * <p>
 * Coût : la place est choisie une fois (25 colonnes au plus, une douzaine de blocs lus chacune), sans chemin ; pas de
 * nouvel essai avant 30 s si rien ne convient. Réglage {@code Reglages.abri_nuit} pour couper.
 */
final class Abri {
    private Abri() {}

    static final int PROFONDEUR = 3;

    /**
     * Hors service, la nuit, à plus d'une heure de marche du poste : il ne rentre pas dans le noir, il campe (le cerveau ne
     * le renvoie plus au poste, et l'arrête s'il était en chemin). Rappelé par son chef, il rentre quand même.
     */
    static boolean nuitDehors(Travailleur t) {
        return t.bureau != null && t.mode == Travailleur.Mode.TRAVAIL && !t.enService() && nuit(t.level()) && !t.chezLui(t.blockPosition())
                && Travailleur.heuresDeMarche(t.blockPosition(), t.bureau) >= 1;
    }

    /** La nuit, là où le jour se lève (pas dans le Nether ni l'End, où l'heure ne bouge pas : il n'en sortirait jamais). */
    static boolean nuit(Level l) { return !l.dimensionType().hasFixedTime() && !l.isDay(); }

    /** Le soir, dehors : vrai s'il part se creuser un abri (ou s'il y est déjà, il s'y repose : faux). */
    static boolean abriter(Travailleur t) {
        if (t.memoire.contains("abri")) {
            if (dedans(t, BlockPos.of(t.memoire.getLong("abri")))) return false;
            t.memoire.remove("abri");
        }
        long maintenant = t.level().getGameTime();
        if (!Reglages.actuel.abri_nuit || !nuit(t.level()) || t.isInWater() || t.memoire.getLong("abri_essai") > maintenant) return false;
        t.memoire.putLong("abri_essai", maintenant + 600);
        BlockPos p = choisir(t);
        if (p == null) return false;
        Cerveau.lancer(t, new Creuser(p));
        return true;
    }

    /** Le jour levé, dans son trou : il en sort. Vrai s'il s'y met. */
    static boolean sortir(Travailleur t) {
        if (!t.memoire.contains("abri") || nuit(t.level())) return false;
        BlockPos toit = BlockPos.of(t.memoire.getLong("abri"));
        if (!dedans(t, toit)) { t.memoire.remove("abri"); return false; }
        Cerveau.lancer(t, new Sortir(toit));
        return true;
    }

    /** Il est au fond de son trou (le toit au-dessus de sa tête). */
    static boolean dedans(Travailleur t, BlockPos toit) { return t.blockPosition().equals(toit.below(PROFONDEUR - 1)); }

    /** La place la plus proche (à 2 blocs au plus, à sa hauteur) où il peut creuser son trou ; null s'il n'y en a pas. */
    static BlockPos choisir(Travailleur t) {
        BlockPos ici = t.blockPosition();
        BlockPos mieux = null;
        double d = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(ici.offset(-2, 0, -2), ici.offset(2, 0, 2))) {
            double dq = q.distSqr(ici);
            if (dq >= d || !convient(t, q)) continue;
            mieux = q.immutable();
            d = dq;
        }
        if (mieux == null || Mineur.interdit(t, mieux.below(), false)) return null;
        return mieux;
    }

    /** Les pieds en p, il peut creuser là : trois blocs naturels qu'il sait récolter, des murs pleins, un fond, et de quoi remonter. */
    static boolean convient(Travailleur t, BlockPos p) {
        Level l = t.level();
        if (!l.isLoaded(p.below(PROFONDEUR + 1)) || !Monde.libre(l, p) || !Monde.libre(l, p.above())) return false;
        int blocs = 0;
        for (int k = 1; k <= PROFONDEUR; k++) {
            BlockPos q = p.below(k);
            if (!creusable(t, q)) return false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos c = q.relative(dir);
                BlockState s = l.getBlockState(c);
                if (!s.isCollisionShapeFullBlock(l, c) || !s.getFluidState().isEmpty()) return false;
            }
            for (ItemStack s : Block.getDrops(l.getBlockState(q), (ServerLevel) l, q, null, t, t.getMainHandItem())) if (posable(s)) blocs += s.getCount();
        }
        BlockPos fond = p.below(PROFONDEUR + 1);
        if (!Monde.solide(l, fond) || !l.getBlockState(fond).getFluidState().isEmpty()) return false;
        // De quoi remonter au matin : ce qu'il tirera du trou, ou ce que son sac contient déjà.
        for (int i = 0; i < t.inventaire.getContainerSize() && blocs < PROFONDEUR; i++) if (posable(t.inventaire.getItem(i))) blocs += t.inventaire.getItem(i).getCount();
        return blocs >= PROFONDEUR;
    }

    /** Un bloc naturel, plein, qui ne tombe pas, qu'il casse vite et dont il garde ce qu'il donne (sinon il ne pourrait pas remonter). */
    static boolean creusable(Travailleur t, BlockPos q) {
        Level l = t.level();
        BlockState s = l.getBlockState(q);
        float durete = s.getDestroySpeed(l, q);
        return !s.isAir() && s.getFluidState().isEmpty() && durete >= 0 && durete <= 3 && l.getBlockEntity(q) == null && !Monde.construit(l, q)
                && !Monde.tombe(l, q) && s.isCollisionShapeFullBlock(l, q) && (!s.requiresCorrectToolForDrops() || t.getMainHandItem().isCorrectToolForDrops(s));
    }

    /** Un bloc plein qui tient en place (ni sable, ni gravier, ni feuilles, ni coffre). */
    static boolean posable(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi) || s.is(ItemTags.LEAVES)) return false;
        Block b = bi.getBlock();
        return !(b instanceof FallingBlock) && !(b instanceof EntityBlock) && b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** La case du sac d'où prendre un bloc à poser : un bloc de chantier d'abord (terre, pierre), sinon tout bloc posable ; -1 sinon. */
    static int caseBloc(Travailleur t) {
        int autre = -1;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!posable(s)) continue;
            if (Itineraire.blocDeChantier(s)) return i;
            if (autre < 0) autre = i;
        }
        return autre;
    }

    /** Casse peu à peu le bloc q (comme Actions.Casser) ; vrai quand il est cassé et ce qu'il donne est dans le sac. */
    static boolean casser(Travailleur t, BlockPos q, float[] progres, int age) {
        Level l = t.level();
        BlockState st = l.getBlockState(q);
        if (st.isAir()) return true;
        t.getLookControl().setLookAt(Vec3.atCenterOf(q));
        if (age % 5 == 0) t.swing(InteractionHand.MAIN_HAND);
        progres[0]++;
        float total = Actions.duree(t, st, q);
        l.destroyBlockProgress(t.getId(), q, Math.min(9, (int) (progres[0] / total * 10)));
        if (progres[0] < total) return false;
        l.destroyBlockProgress(t.getId(), q, -1);
        Actions.recolter(t, q);
        if (st.getDestroySpeed(l, q) > 0) Actions.userOutil(t, 1);
        progres[0] = 0;
        return true;
    }

    /** Il va à sa place, creuse son trou sous ses pieds, y descend et pose le toit. */
    static final class Creuser extends Actions.Action {
        final BlockPos p;
        int k = 1;
        final float[] progres = {0};

        Creuser(BlockPos p) { super("abri"); this.p = p; }

        Actions.Etat tick(Travailleur t) {
            if (age > 20 * 90) return echec("trop_long");
            Vec3 c = Vec3.atBottomCenterOf(p);
            double dx = t.getX() - c.x, dz = t.getZ() - c.z;
            if (k == 1 && dx * dx + dz * dz > 1.5 * 1.5) {
                if (coince()) return echec("bloque");
                marcher(t, c, 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getMoveControl().setWantedPosition(c.x, t.getY(), c.z, 0.6);
            t.diag = "creuse son abri";
            if (k <= PROFONDEUR) {
                BlockPos q = p.below(k);
                // Il attend d'être retombé sur le bloc suivant, au milieu du trou.
                if (!t.onGround() || t.getY() > q.getY() + 1.2 || dx * dx + dz * dz > 0.09) return Actions.Etat.EN_COURS;
                if (!t.level().getBlockState(q).isAir() && !creusable(t, q)) return echec("danger");
                if (casser(t, q, progres, age)) k++;
                return Actions.Etat.EN_COURS;
            }
            // Au fond : le toit au-dessus de sa tête (tenu par les murs du trou).
            BlockPos fond = p.below(PROFONDEUR);
            if (!t.onGround() || t.getY() > fond.getY() + 0.2) return Actions.Etat.EN_COURS;
            BlockPos toit = p.below();
            int i = caseBloc(t);
            t.memoire.putLong("abri", toit.asLong()); // même sans toit, il remontera au matin
            if (i >= 0 && Actions.placer(t, toit, t.inventaire.getItem(i)) == null) Besoins.dire(t, "nuit_cache", null);
            return Actions.Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (k <= PROFONDEUR) t.level().destroyBlockProgress(t.getId(), p.below(k), -1);
        }
    }

    /** Au matin : il reprend son toit, puis remonte en posant un bloc sous lui à chaque saut ; le trou est rebouché. */
    static final class Sortir extends Actions.Action {
        final BlockPos toit;
        int poses;
        boolean dit;
        final float[] progres = {0};

        Sortir(BlockPos toit) { super("sortir_abri"); this.toit = toit; }

        Actions.Etat tick(Travailleur t) {
            if (age > 20 * 60) { t.memoire.remove("abri"); return echec("bloque"); }
            t.getNavigation().stop();
            t.diag = "sort de son abri";
            if (!dit) { dit = true; Besoins.dire(t, "matin_reprise", null); }
            if (poses == 0 && !casser(t, toit, progres, age)) return Actions.Etat.EN_COURS;
            if (poses >= PROFONDEUR) { t.memoire.remove("abri"); return Actions.Etat.OK; }
            BlockPos fond = toit.below(PROFONDEUR - 1), q = fond.above(poses);
            double cx = toit.getX() + 0.5, cz = toit.getZ() + 0.5;
            // En l'air, les pieds au-dessus de la case : il pose le bloc sous lui.
            if (t.getY() >= q.getY() + 1) {
                int i = caseBloc(t);
                // Plus de blocs : à un bloc du bord, il en sort à pied (le haut du trou reste ouvert).
                if (i < 0) { t.memoire.remove("abri"); return poses >= PROFONDEUR - 1 ? Actions.Etat.OK : echec("sans_bloc"); }
                if (!t.level().getBlockState(q).canBeReplaced() || Actions.placer(t, q, t.inventaire.getItem(i)) == null) poses++;
                return Actions.Etat.EN_COURS;
            }
            t.getMoveControl().setWantedPosition(cx, t.getY(), cz, 0.6);
            if (t.onGround()) t.getJumpControl().jump();
            return Actions.Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.level().destroyBlockProgress(t.getId(), toit, -1);
        }
    }
}

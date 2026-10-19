package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * La nuit dehors (lot 9, cahier 14.12) : loin du poste, la nuit tombée, l'ouvrier se cache comme un joueur prudent. Il
 * creuse sous ses pieds un trou d'un bloc de large et de {@link #PROFONDEUR} de profondeur, dans de la terre ou de la
 * pierre naturelle (jamais un minerai, du sable ni du gravier, jamais près d'un liquide, d'un vide ou d'une construction),
 * s'y laisse descendre et le ferme d'un bloc au-dessus de sa tête. Il y dort jusqu'au jour. Au matin, il reprend ce bloc
 * et remonte en posant sous lui les blocs mêmes qu'il a tirés du trou (comme le pilier d'{@link Itineraire}) : le trou est
 * rebouché.
 * <p>
 * Le trou est retenu dès le premier bloc cassé ({@code memoire.abri} : place, profondeur, blocs tirés, toit posé). Tout
 * trou qui n'est pas son abri fermé de la nuit (creusage interrompu par un creeper, un coup, un ordre, le jour levé) est
 * rebouché dès qu'il est libre ({@link #sortir}), de dedans ou du bord.
 * <p>
 * Coût : la place est choisie une fois (25 colonnes au plus, une douzaine de blocs lus chacune), sans chemin ; pas de
 * nouvel essai avant 30 s. Réglage {@code Reglages.abri_nuit} pour couper.
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

    /**
     * La nuit, à l'heure (de 19 h à 5 h ; un orage n'est pas la nuit), là où le jour se lève : pas dans le Nether ni l'End,
     * où l'heure ne bouge pas (il ne sortirait jamais de son trou).
     */
    static boolean nuit(Level l) {
        if (l.dimensionType().hasFixedTime()) return false;
        long h = l.getDayTime() % 24000;
        return h >= 13000 && h < 23000;
    }

    /** Ce qu'il retient de son trou, ou null. */
    static CompoundTag trou(Travailleur t) { return t.memoire.contains("abri") ? t.memoire.getCompound("abri") : null; }

    static BlockPos place(CompoundTag e) { return BlockPos.of(e.getLong("p")); }

    /** Le soir, dehors : vrai s'il part se creuser un abri (il en a déjà un : faux, il s'y repose). */
    static boolean abriter(Travailleur t) {
        if (trou(t) != null) return false;
        long maintenant = t.level().getGameTime();
        if (!Reglages.actuel.abri_nuit || !nuit(t.level()) || t.isInWater() || t.isPassenger() || t.memoire.getLong("abri_essai") > maintenant) return false;
        t.memoire.putLong("abri_essai", maintenant + 600);
        BlockPos p = choisir(t);
        if (p == null) return false;
        Cerveau.lancer(t, new Creuser(p));
        return true;
    }

    /**
     * Un trou à reboucher : le jour levé, rappelé, de nouveau au travail, ou un creusage interrompu. Seul l'abri fermé où il
     * est, la nuit, hors service, reste tel quel. Vrai s'il s'y met.
     */
    static boolean sortir(Travailleur t) {
        CompoundTag e = trou(t);
        if (e == null) return false;
        if (dedans(t, e) && nuit(t.level()) && !t.enService() && !t.memoire.getBoolean("rappel") && t.mode == Travailleur.Mode.TRAVAIL) return false;
        Cerveau.lancer(t, new Sortir());
        return true;
    }

    /** Il est au fond de son abri fermé. */
    static boolean dedans(Travailleur t, CompoundTag e) {
        return e.getBoolean("ferme") && e.getInt("prof") == PROFONDEUR && t.blockPosition().equals(place(e).below(PROFONDEUR));
    }

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
        if (mieux == null || Mineur.interdit(t, mieux.below(), false) || Monde.construitAutour(t.level(), mieux.below())) return null;
        return mieux;
    }

    /**
     * Les pieds en p, il peut creuser là : pas au fond d'un boyau ou d'un ancien trou, trois blocs naturels qu'il sait
     * récolter et qui donnent chacun un bloc à reposer (avec la place dans son sac), des murs pleins, un fond.
     */
    static boolean convient(Travailleur t, BlockPos p) {
        Level l = t.level();
        if (!l.isLoaded(p.below(PROFONDEUR + 1)) || !Monde.libre(l, p) || !Monde.libre(l, p.above())) return false;
        int murs = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) if (Monde.solide(l, p.relative(dir))) murs++;
        if (murs >= 3) return false;
        for (int k = 1; k <= PROFONDEUR; k++) {
            BlockPos q = p.below(k);
            if (!creusable(t, q)) return false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos c = q.relative(dir);
                BlockState s = l.getBlockState(c);
                if (!s.isCollisionShapeFullBlock(l, c) || !s.getFluidState().isEmpty()) return false;
            }
            Item tire = tire(t, q);
            if (tire == null || !t.inventaire.canAddItem(new ItemStack(tire))) return false;
        }
        BlockPos fond = p.below(PROFONDEUR + 1);
        return Monde.solide(l, fond) && l.getBlockState(fond).getFluidState().isEmpty();
    }

    /** Terre ou pierre naturelle (pas un minerai), pleine, qui ne tombe pas, qu'il casse vite et dont il garde ce qu'il donne. */
    static boolean creusable(Travailleur t, BlockPos q) {
        Level l = t.level();
        BlockState s = l.getBlockState(q);
        float durete = s.getDestroySpeed(l, q);
        return (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)) && s.getFluidState().isEmpty() && durete >= 0 && durete <= 3
                && l.getBlockEntity(q) == null && !Monde.construit(l, q) && !Monde.tombe(l, q) && s.isCollisionShapeFullBlock(l, q)
                && (!s.requiresCorrectToolForDrops() || t.getMainHandItem().isCorrectToolForDrops(s));
    }

    /** Le bloc qu'il tirera de q (l'herbe donne de la terre, la pierre des pavés) ; null s'il n'en tire rien à reposer. */
    static Item tire(Travailleur t, BlockPos q) {
        for (ItemStack s : Block.getDrops(t.level().getBlockState(q), (ServerLevel) t.level(), q, null, t, t.getMainHandItem()))
            if (s.getItem() instanceof BlockItem) return s.getItem();
        return null;
    }

    /** La case du sac de cet objet (ce qu'il a tiré du trou), sinon un bloc de chantier hors mission ; -1 sinon. */
    static int caseBloc(Travailleur t, String id) {
        Item voulu = id.isEmpty() ? null : BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        for (int i = 0; voulu != null && i < t.inventaire.getContainerSize(); i++)
            if (t.inventaire.getItem(i).is(voulu)) return i;
        return Itineraire.caseDeChantier(t, true);
    }

    /** Ce qu'il a tiré du k-ième bloc du trou (k de 1 à 3). */
    static String bloc(CompoundTag e, int k) {
        ListTag l = e.getList("blocs", Tag.TAG_STRING);
        return k - 1 < l.size() ? l.getString(k - 1) : "";
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
                if (!creusable(t, q)) return echec("danger");
                Item tire = tire(t, q);
                if (!casser(t, q, progres, age)) return Actions.Etat.EN_COURS;
                // Retenu tout de suite : interrompu, il rebouchera ce qu'il a ouvert, avec ce qu'il en a tiré.
                CompoundTag e = trou(t) == null ? new CompoundTag() : trou(t);
                e.putLong("p", p.asLong());
                e.putInt("prof", k);
                ListTag blocs = e.getList("blocs", Tag.TAG_STRING);
                blocs.add(StringTag.valueOf(tire == null ? "" : BuiltInRegistries.ITEM.getKey(tire).toString()));
                e.put("blocs", blocs);
                t.memoire.put("abri", e);
                k++;
                return Actions.Etat.EN_COURS;
            }
            // Au fond : le toit au-dessus de sa tête (tenu par les murs du trou), avec ce qu'il a tiré du haut du trou.
            BlockPos fond = p.below(PROFONDEUR);
            if (!t.onGround() || t.getY() > fond.getY() + 0.2) return Actions.Etat.EN_COURS;
            CompoundTag e = trou(t);
            int i = caseBloc(t, bloc(e, 1));
            if (i >= 0 && Actions.placer(t, p.below(), t.inventaire.getItem(i)) == null) {
                e.putBoolean("ferme", true);
                t.memoire.put("abri", e);
                Besoins.dire(t, "nuit_cache", null);
                // Sorti pour la journée, la nuit l'a pris loin du poste : il écrit à son chef qu'il est à l'abri.
                String lettre = t.expedition ? null : Besoins.texte(t, "lettre_nuit_dehors", null, null);
                if (lettre != null && Cerveau.parfois(t, "lettre_nuit", 600)) Courrier.ecrireTexte(t, Messages.objet("camp"), lettre, false);
            }
            return Actions.Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (k <= PROFONDEUR) t.level().destroyBlockProgress(t.getId(), p.below(k), -1);
        }
    }

    /**
     * Il rebouche son trou : il reprend son toit, puis remplit le trou de bas en haut avec ce qu'il en a tiré. Dedans, il
     * saute et pose un bloc sous lui à chaque saut ; au bord, il les pose à la main.
     */
    static final class Sortir extends Actions.Action {
        boolean dit;
        final float[] progres = {0};

        Sortir() { super("sortir_abri"); }

        Actions.Etat tick(Travailleur t) {
            CompoundTag e = trou(t);
            if (e == null) return Actions.Etat.OK;
            if (age > 20 * 60) { t.memoire.remove("abri"); return echec("bloque"); }
            BlockPos p = place(e), toit = p.below();
            int prof = e.getInt("prof");
            boolean colonne = t.getBlockX() == p.getX() && t.getBlockZ() == p.getZ() && t.getY() < p.getY() - 0.5;
            if (!dit && colonne && e.getBoolean("ferme")) { dit = true; Besoins.dire(t, "matin_reprise", null); }
            t.diag = "rebouche son abri";
            // Son toit d'abord (de dedans, il est à portée de main).
            if (e.getBoolean("ferme")) {
                if (!t.level().getBlockState(toit).isAir() && Actions.aPortee(t, toit)) {
                    t.getNavigation().stop();
                    if (!casser(t, toit, progres, age)) return Actions.Etat.EN_COURS;
                }
                if (t.level().getBlockState(toit).isAir()) { e.putBoolean("ferme", false); t.memoire.put("abri", e); }
                else if (!colonne) {
                    if (coince()) { t.memoire.remove("abri"); return echec("bloque"); }
                    marcher(t, Vec3.atBottomCenterOf(p), 1.0);
                    return Actions.Etat.EN_COURS;
                }
            }
            // La case vide la plus basse du trou.
            int j = prof;
            while (j >= 1 && !t.level().getBlockState(p.below(j)).canBeReplaced()) j--;
            if (j < 1) { t.memoire.remove("abri"); return Actions.Etat.OK; }
            BlockPos q = p.below(j);
            int i = caseBloc(t, bloc(e, j));
            if (i < 0) { t.memoire.remove("abri"); return echec("sans_bloc"); }
            if (colonne) {
                t.getNavigation().stop();
                // En l'air, les pieds au-dessus de la case : il pose le bloc sous lui.
                if (t.getY() >= q.getY() + 1) { Actions.placer(t, q, t.inventaire.getItem(i)); return Actions.Etat.EN_COURS; }
                t.getMoveControl().setWantedPosition(p.getX() + 0.5, t.getY(), p.getZ() + 0.5, 0.6);
                if (t.onGround() && !t.sauter()) { t.memoire.remove("abri"); return echec("bloque"); } // il saute sans que le bloc se pose
                return Actions.Etat.EN_COURS;
            }
            // Au bord : à portée de main, il pose le bloc dans le trou.
            if (Actions.aPortee(t, q) && !t.getBoundingBox().intersects(new AABB(q))) {
                t.getNavigation().stop();
                t.getLookControl().setLookAt(Vec3.atCenterOf(q));
                if (age % 5 == 0) Actions.placer(t, q, t.inventaire.getItem(i));
                return Actions.Etat.EN_COURS;
            }
            if (coince()) { t.memoire.remove("abri"); return echec("bloque"); }
            marcher(t, Vec3.atBottomCenterOf(p), 1.0);
            return Actions.Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            CompoundTag e = trou(t);
            if (e != null) t.level().destroyBlockProgress(t.getId(), place(e).below(), -1);
        }
    }
}

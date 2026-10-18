package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ce que le PNJ voit du monde : seulement les coins chargés, comme un joueur qui regarde autour de lui. */
final class Monde {
    private Monde() {}

    /** La hauteur du premier bloc d'air au-dessus du sol (feuilles ignorées), ou Integer.MIN_VALUE si le coin n'est pas chargé. */
    static int surface(Level l, int x, int z) {
        return l.hasChunkAt(x, z) ? l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : Integer.MIN_VALUE;
    }

    /** On peut y passer : ni bloc solide, ni liquide. */
    static boolean libre(Level l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && s.getFluidState().isEmpty();
    }

    /** On peut y passer en marchant ou en nageant (pas dans la lave). */
    static boolean traversable(Level l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && !s.getFluidState().is(FluidTags.LAVA) && !s.is(Blocks.FIRE) && !s.is(Blocks.SWEET_BERRY_BUSH) && !s.is(Blocks.POWDER_SNOW);
    }

    static boolean solide(Level l, BlockPos p) { return !l.getBlockState(p).getCollisionShape(l, p).isEmpty(); }

    /** On peut s'y tenir (les pieds en p) : de la place pour le corps, et un appui (sol, eau, échelle). */
    static boolean debout(Level l, BlockPos p) {
        if (!l.isLoaded(p) || !traversable(l, p) || !traversable(l, p.above())) return false;
        BlockState pieds = l.getBlockState(p);
        if (!pieds.getFluidState().isEmpty() || pieds.is(BlockTags.CLIMBABLE)) return true;
        BlockState dessous = l.getBlockState(p.below());
        return !dessous.getCollisionShape(l, p.below()).isEmpty() && !dessous.is(Blocks.MAGMA_BLOCK) && !dessous.is(Blocks.CAMPFIRE)
                && !dessous.getFluidState().is(FluidTags.LAVA) || !dessous.getFluidState().isEmpty() && dessous.getFluidState().is(FluidTags.WATER);
    }

    /**
     * Une place sûre à côté de {@code centre} (le poste), de plus en plus loin jusqu'à 4 blocs, d'abord à la hauteur du
     * centre sur tout l'anneau, puis un bloc plus haut ou plus bas, puis deux : un sol solide (pas le centre lui-même, ni
     * feu de camp ni magma), deux blocs libres, rien de mortel, et pas de cactus ni de baies tout contre. Null s'il n'y en a pas.
     */
    static BlockPos placeSure(Level l, BlockPos centre) {
        for (int r = 1; r <= 4; r++)
            for (int dy : new int[]{0, 1, -1, 2, -2})
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos q = centre.offset(dx, dy, dz);
                        if (libre(l, q) && libre(l, q.above()) && solide(l, q.below()) && debout(l, q) && !mortel(l, q) && !piquant(l, q)) return q;
                    }
        return null;
    }

    /**
     * De la lave qui peut couler jusque dans sa case (pieds en p) : à côté de lui, à sa hauteur, assez haute pour s'étendre
     * encore d'une case (une source, ou une coulée de plus de 2). Une coulée arrivée au bout ne bouge plus : pas d'alerte.
     * Quatre lectures de blocs. Null s'il n'y en a pas, ou si sa case ne peut pas la recevoir (debout sur une dalle du bas,
     * un escalier, un chemin de terre, une terre labourée : la case est le sol même, la lave n'y entre jamais).
     */
    static BlockPos laveQuiVient(Level l, BlockPos p) {
        if (!l.getBlockState(p).canBeReplaced(net.minecraft.world.level.material.Fluids.LAVA)) return null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(d);
            net.minecraft.world.level.material.FluidState f = l.getFluidState(q);
            if (f.is(FluidTags.LAVA) && f.getAmount() > 2) return q;
        }
        return null;
    }

    /**
     * Une place sûre où fuir un danger (lave, feu, bloc où il étouffe) : comme {@link #placeSure}, mais d'abord à sa hauteur
     * ou à un bloc près sur tout le rayon (une rive à deux pas plutôt qu'un rebord à escalader), et jamais une des places
     * {@code exclues} (déjà essayées sans chemin sûr). Null s'il n'y en a pas.
     */
    static BlockPos refuge(Level l, BlockPos centre, java.util.Set<BlockPos> exclues) {
        for (int[] dys : new int[][]{{0, 1, -1}, {2, -2}})
            for (int r = 1; r <= 4; r++)
                for (int dy : dys)
                    for (int dx = -r; dx <= r; dx++)
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            BlockPos q = centre.offset(dx, dy, dz);
                            if (!exclues.contains(q) && libre(l, q) && libre(l, q.above()) && solide(l, q.below()) && debout(l, q) && !mortel(l, q) && !piquant(l, q))
                                return q;
                        }
        return null;
    }

    /**
     * Aller tout droit de {@code depuis} à {@code vers} ne traverse rien de mortel : à chaque demi-bloc, de la place pour le
     * corps, un appui à 3 blocs au plus sous les pieds (ou de l'eau), et ni lave ni feu, hors de sa propre case qu'il fuit
     * et de {@code lave} cases de plus (déjà dans la lave, il doit bien la traverser pour en sortir).
     */
    static boolean ligneSure(Level l, Vec3 depuis, BlockPos vers, int lave) {
        java.util.Set<BlockPos> brulantes = new java.util.HashSet<>();
        Vec3 c = Vec3.atBottomCenterOf(vers);
        int n = (int) Math.ceil(depuis.distanceTo(c) * 2);
        BlockPos dep = BlockPos.containing(depuis);
        int avant = dep.getY();
        for (int i = 1; i <= n; i++) {
            Vec3 v = depuis.lerp(c, i / (double) n);
            BlockPos q = BlockPos.containing(v);
            if (q.getX() == dep.getX() && q.getZ() == dep.getZ()) continue;
            if (!traversable(l, q) && !traversable(l, q.above())) return false;
            BlockPos pied = traversable(l, q) ? q : q.above();
            // Une marche d'un bloc au plus d'un pas à l'autre, et la place pour la tête.
            if (pied.getY() - avant > 1 || !traversable(l, pied.above())) return false;
            avant = pied.getY();
            boolean appui = false;
            for (int k = 1; k <= 4 && !appui; k++) {
                BlockPos b = pied.below(k);
                BlockState s = l.getBlockState(b);
                if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.MAGMA_BLOCK)) {
                    if (brulantes.add(b.immutable()) && brulantes.size() > lave) return false;
                    appui = true; // il y nage (déjà compté comme lave traversée)
                } else {
                    var forme = s.getCollisionShape(l, b);
                    if (!forme.isEmpty() && forme.max(Direction.Axis.Y) > 1) return false; // une clôture, un muret : un mur
                    appui = !forme.isEmpty() || s.getFluidState().is(FluidTags.WATER);
                }
            }
            if (!appui) return false;
            for (int dy = -1; dy <= 1; dy++) {
                BlockState s = l.getBlockState(q.above(dy));
                if ((s.getFluidState().is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || s.is(Blocks.MAGMA_BLOCK)) && brulantes.add(q.above(dy).immutable())
                        && brulantes.size() > lave) return false;
            }
        }
        return true;
    }

    /** Combien de blocs il tomberait, les pieds en p (0 : il y tient debout ; 99 : plus de 3, ou de la lave au fond). */
    static int chute(Level l, BlockPos p) {
        for (int k = 0; k <= 3; k++) {
            BlockState s = l.getBlockState(p.below(k + 1));
            if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.MAGMA_BLOCK)) return 99;
            if (!s.getCollisionShape(l, p.below(k + 1)).isEmpty() || s.getFluidState().is(FluidTags.WATER)) return k;
        }
        return 99;
    }

    /**
     * Une case de {@code haut} blocs (les pieds en p) où du gravier ou du sable va tomber : un tel bloc juste au-dessus,
     * ou en train de tomber dans la colonne. Il n'y entre pas tant que ça dure.
     */
    static boolean eboulement(Level l, BlockPos p, int haut) {
        return tombe(l, p.above(haut)) && FallingBlock.isFree(l.getBlockState(p.above(haut - 1))) || !l.getEntitiesOfClass(FallingBlockEntity.class,
                new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + haut + 8, p.getZ() + 1)).isEmpty();
    }

    /** Un cactus ou des baies tout contre (pieds ou tête), ou un feu de camp sous les pieds : on s'y blesse. */
    static boolean piquant(Level l, BlockPos q) {
        if (l.getBlockState(q.below()).is(BlockTags.CAMPFIRES)) return true;
        for (BlockPos x : List.of(q, q.above()))
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState s = l.getBlockState(x.relative(d));
                if (s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH)) return true;
            }
        return false;
    }

    static String liquide(Level l, BlockPos p) {
        var f = l.getBlockState(p).getFluidState();
        return f.isEmpty() ? null : f.is(FluidTags.LAVA) ? "lave" : f.is(FluidTags.WATER) ? "eau" : "autre";
    }

    /** Un bloc qui tombe dès qu'on lui retire son appui : gravier, sable, poudre de béton… */
    static boolean tombe(Level l, BlockPos p) { return l.isLoaded(p) && l.getBlockState(p).getBlock() instanceof FallingBlock; }

    /**
     * Le liquide à boucher avant de casser q : de la lave contre lui (elle coulerait dans la galerie), ou, si {@code eau},
     * de l'eau juste au-dessus (elle lui tomberait dessus). Null s'il n'y en a pas.
     */
    static BlockPos coulee(Level l, BlockPos q, boolean eau) {
        for (Direction d : Direction.values()) {
            BlockPos n = q.relative(d);
            if (!l.isLoaded(n)) continue;
            var f = l.getFluidState(n);
            if (f.is(FluidTags.LAVA) || eau && d == Direction.UP && f.is(FluidTags.WATER)) return n;
        }
        return null;
    }

    /** De l'eau où se jeter (les pieds dedans, la tête à l'air), à r blocs au plus : pour éteindre le feu. Null s'il n'y en a pas. */
    static BlockPos eauProche(Level l, BlockPos c, int r) {
        BlockPos meilleur = null;
        double dMin = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 1, r))) {
            double d = q.distSqr(c);
            if (d >= dMin || !l.isLoaded(q) || !l.getFluidState(q).is(FluidTags.WATER) || !traversable(l, q.above())) continue;
            meilleur = q.immutable();
            dMin = d;
        }
        return meilleur;
    }

    /** Le bloc touche de l'air (on le voit). */
    static boolean expose(Level l, BlockPos q) {
        for (Direction d : Direction.values()) {
            BlockPos n = q.relative(d);
            if (l.isLoaded(n) && l.getBlockState(n).getCollisionShape(l, n).isEmpty()) return true;
        }
        return false;
    }

    /** Le bloc correspondant le plus proche du PNJ, dans un cube de rayon r autour du centre. */
    static BlockPos chercherBloc(Travailleur t, Actions.Filtre f, int r, BlockPos centre, boolean expose, boolean recoltable) {
        Level l = t.level();
        BlockPos meilleur = null;
        double dMin = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
            double d = q.distSqr(t.blockPosition());
            if (d >= dMin || !l.isLoaded(q)) continue;
            BlockState s = l.getBlockState(q);
            if (!f.bloc(s) || expose && !expose(l, q)) continue;
            if (recoltable && s.requiresCorrectToolForDrops() && !t.getMainHandItem().isCorrectToolForDrops(s)) continue;
            meilleur = q.immutable();
            dMin = d;
        }
        return meilleur;
    }

    /**
     * Un endroit où creuser sans abîmer de construction, le plus près possible du centre : sol naturel (terre,
     * sable, pierre…) à ciel ouvert, aucun bloc « de joueur » à 5 blocs autour, et rien que {@code refus} écarte.
     */
    static BlockPos solNaturel(Travailleur t, BlockPos c, int rayon, java.util.function.Predicate<BlockPos> refus) {
        Level l = t.level();
        var r = t.getRandom();
        for (int essai = 0; essai < 200; essai++) {
            double angle = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * Math.min(rayon - 6, 4 + essai);
            int x = c.getX() + (int) (Math.cos(angle) * d), z = c.getZ() + (int) (Math.sin(angle) * d);
            int y = surface(l, x, z);
            if (y == Integer.MIN_VALUE) continue;
            BlockPos sol = new BlockPos(x, y - 1, z);
            BlockState st = l.getBlockState(sol);
            // Herbes, fleurs, neige, barrières : ce n'est pas encore le sol, on descend jusqu'à lui.
            for (int i = 0; i < 20 && (st.canBeReplaced() && st.getFluidState().isEmpty() || st.is(Blocks.BARRIER)); i++) {
                sol = sol.below();
                st = l.getBlockState(sol);
            }
            boolean naturel = st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL);
            if (!naturel || !st.getFluidState().isEmpty() || refus.test(sol) || construitAutour(l, sol)) continue;
            return sol.above();
        }
        return null;
    }

    /** Un bloc posé par un joueur : on n'y touche pas (pas de destruction hors RP). */
    static boolean construit(Level l, BlockPos q) {
        if (l.getBlockEntity(q) != null) return true;
        BlockState st = l.getBlockState(q);
        return st.is(BlockTags.PLANKS) || st.is(BlockTags.WOOL) || st.is(BlockTags.DOORS) || st.is(BlockTags.SLABS) || st.is(BlockTags.STAIRS)
                || st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(BlockTags.WALLS) || st.is(BlockTags.BEDS) || st.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                || st.is(BlockTags.TRAPDOORS) || st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(BlockTags.STONE_BRICKS) || st.is(Blocks.BRICKS);
    }

    /**
     * La structure générée (puits de mine, village, ruines, fort, cité antique…) dont une pièce contient p : son nom en
     * français simple, ou null. Seulement dans un coin chargé. Bon marché sans structure : le chunk n'a alors aucune référence.
     */
    static String structure(Level l, BlockPos p) {
        if (!(l instanceof net.minecraft.server.level.ServerLevel sl) || !l.isLoaded(p)) return null;
        if (sl.getChunk(p).getAllReferences().isEmpty()) return null;
        var s = sl.structureManager().getStructureWithPieceAt(p, h -> true);
        if (!s.isValid()) return null;
        var id = sl.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getKey(s.getStructure());
        return nomStructure(id == null ? "" : id.getPath());
    }

    /** « mineshaft_mesa » → « un vieux puits de mine » ; inconnue : « une construction ancienne ». */
    static String nomStructure(String id) {
        String[][] noms = {{"mineshaft", "un vieux puits de mine"}, {"village", "un village"}, {"ancient_city", "une cité antique"},
                {"stronghold", "un fort"}, {"fortress", "une forteresse"}, {"bastion", "une forteresse"}, {"ruin", "des ruines"},
                {"pyramid", "un temple"}, {"temple", "un temple"}, {"monument", "un temple englouti"}, {"outpost", "un avant-poste de pillards"},
                {"mansion", "un manoir"}, {"swamp_hut", "une cabane de sorcière"}, {"igloo", "une hutte de neige"}, {"shipwreck", "une épave"},
                {"trial_chambers", "des salles souterraines"}, {"end_city", "une cité ancienne"}};
        for (String[] n : noms) if (id.contains(n[0])) return n[1];
        return "une construction ancienne";
    }

    static boolean construitAutour(Level l, BlockPos sol) {
        for (BlockPos q : BlockPos.betweenClosed(sol.offset(-5, -2, -5), sol.offset(5, 4, 5))) {
            if (!l.isLoaded(q)) return true;
            if (!l.getBlockState(q).isAir() && construit(l, q)) return true;
        }
        return false;
    }

    static int libres(Travailleur t) {
        int n = 0;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (t.inventaire.getItem(i).isEmpty()) n++;
        return n;
    }

    /** Une créature hostile, et qui ne se tient pas tranquille (un piglin zombie calme n'est pas une cible). */
    static boolean hostile(LivingEntity e) {
        if (!(e instanceof Enemy) || e instanceof Travailleur || !e.isAlive()) return false;
        return !(e instanceof NeutralMob n) || n.isAngry();
    }

    static boolean recent(LivingEntity qui, int quand) { return qui.tickCount - quand < 200; }

    /**
     * Ce qu'un PNJ peut frapper sans ordre de son chef quand on s'en prend à lui ou à son chef : un monstre, ou un autre
     * PNJ. Jamais une bête (loup apprivoisé, chèvre, abeille…), un golem ni un joueur : il recule (règle du serveur).
     */
    static boolean combattable(LivingEntity e) { return hostile(e) || e instanceof Travailleur w && w.isAlive(); }

    /**
     * Un endroit où l'envoyer le tuerait (cahier 8.5 : il refuse) : de la lave ou du feu à ses pieds, sous lui ou à
     * hauteur de tête, ou rien pour le retenir en dessous (le vide, ou une chute de plus de 20 blocs, ou de la lave au fond).
     */
    static boolean mortel(Level l, BlockPos p) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockState s = l.getBlockState(p.above(dy));
            if (s.getFluidState().is(FluidTags.LAVA) || s.is(BlockTags.FIRE)) return true;
        }
        for (BlockPos q = p.below(); q.getY() >= l.getMinBuildHeight(); q = q.below()) {
            BlockState s = l.getBlockState(q);
            if (s.getFluidState().is(FluidTags.LAVA)) return true;
            if (!s.getCollisionShape(l, q).isEmpty() || !s.getFluidState().isEmpty()) return false;
            if (p.getY() - q.getY() > 20) return true;
        }
        return true;
    }

    /**
     * Rien ni personne à épargner entre l'archer et sa cible : ni un joueur, ni un autre ouvrier, ni une bête sur la
     * trajectoire (une flèche ne choisit pas qui elle touche).
     */
    static boolean ligneDeTir(Travailleur t, LivingEntity cible) {
        Vec3 de = t.getEyePosition(), a = cible.getBoundingBox().getCenter();
        for (Entity e : t.level().getEntities(t, new AABB(de, a).inflate(1), x -> x != cible && x instanceof LivingEntity l && l.isAlive() && !hostile(l) && !x.isSpectator()))
            if (e.getBoundingBox().inflate(0.4).clip(de, a).isPresent()) return false;
        return true;
    }

    /**
     * Ce qu'un PNJ combattant peut frapper : les créatures hostiles proches, celui qui l'a attaqué ou qui a attaqué son
     * chef (un monstre ou un autre PNJ, {@link #combattable}), et le monstre que son chef combat.
     * Jamais un villageois, un golem, un animal ni un joueur, même agresseur : un joueur, seulement sur ordre du chef
     * (une consigne, voir Consignes.combat).
     */
    static List<LivingEntity> cibles(Travailleur t, double rayon) {
        // Comme un joueur : les monstres qu'il voit, à sa hauteur (pas un zombie au fond d'une grotte sous ses pieds,
        // ni un phantom tout là-haut), et pas ceux qu'il vient d'échouer à atteindre.
        long maintenant = t.level().getGameTime();
        t.inaccessibles.values().removeIf(v -> v < maintenant);
        List<LivingEntity> l = new ArrayList<>(t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(rayon),
                e -> hostile(e) && Math.abs(e.getY() - t.getY()) < 6 && t.getSensing().hasLineOfSight(e)));
        LivingEntity mien = t.getLastHurtByMob();
        if (mien != null && combattable(mien) && recent(t, t.getLastHurtByMobTimestamp()) && !t.estDe(mien) && !l.contains(mien)) l.add(mien);
        ServerPlayer c = t.chefJoueur();
        if (c != null && c.level() == t.level() && c.distanceTo(t) < rayon * 2) {
            LivingEntity agresseur = c.getLastHurtByMob();
            if (agresseur != null && agresseur != t && combattable(agresseur) && recent(c, c.getLastHurtByMobTimestamp()) && !l.contains(agresseur)) l.add(agresseur);
            LivingEntity vise = c.getLastHurtMob();
            if (vise != null && vise != t && hostile(vise) && recent(c, c.getLastHurtMobTimestamp()) && !l.contains(vise)) l.add(vise);
        }
        l.removeIf(e -> t.inaccessibles.containsKey(e.getId()));
        l.sort(Comparator.comparingDouble(t::distanceToSqr));
        return l;
    }
}

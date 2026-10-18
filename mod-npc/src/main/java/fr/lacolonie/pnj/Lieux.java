package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Les lieux de travail intelligents (refonte 1.0.0, partie 2). Personne ne casse jamais une construction, ni pour travailler,
 * ni pour se débloquer : est « construit » tout bloc absent de la liste des blocs naturels ({@link LieuxRegles#naturel}), et des
 * feuilles persistantes (posées à la main). La colonie est la zone autour du poste ({@code Reglages.rayon_colonie}, 48 blocs
 * par défaut) ; autour de toute construction, un tampon de {@link #TAMPON} blocs où l'on ne creuse pas et ne choisit pas
 * de lieu ({@link #zoneConstruite}). Seule exception : la destruction sur ordre pendant une guerre déclarée ({@link #enGuerre}).
 * <p>
 * La partie pure (listes, distances, phrases) se teste sans le jeu : {@code LieuxTest}.
 */
final class Lieux {
    private Lieux() {}

    /** Tampon autour d'une construction (blocs, dans chaque direction). */
    static final int TAMPON = 8;
    /** Le mineur ne creuse jamais à moins de tant de blocs (à l'horizontale) sous une construction. */
    static final int FONDATION = 4;
    /** Le mineur travaille au moins à tant de blocs sous la surface (dans la colonie). */
    static final int SOUS_SURFACE = 10;
    /** « Travaille ici » : la zone désignée par le chef, autour du point (blocs). */
    static final int RAYON_ICI = 24;

    // --- Dans le jeu -------------------------------------------------------------------------------

    /**
     * Le crochet de la guerre (partie 6, à venir) : vrai si cet ouvrier a la mission « Attaquer » pendant une guerre
     * déclarée. Il peut alors casser une construction. Par défaut : jamais.
     */
    static Predicate<Travailleur> enGuerre = t -> false;

    /**
     * Serveur de tests : le tampon, la colonie et les fondations ne valent que dans le lot « lieux » (ailleurs, les tests
     * posent leur poste et leurs blocs à deux pas du travail). Le refus de casser un bloc construit, lui, vaut partout.
     */
    static boolean enEssai;

    static boolean regles(Level l) {
        return enEssai || !(l instanceof ServerLevel sl && sl.getServer() instanceof net.minecraft.gametest.framework.GameTestServer);
    }

    private static final Map<Block, Boolean> PAR_BLOC = new ConcurrentHashMap<>();

    /** Une construction (voir {@link LieuxRegles#naturel}). L'air n'en est pas une. */
    static boolean construit(BlockState st) {
        if (st.isAir()) return false;
        if (st.getBlock() instanceof LeavesBlock) return st.getValue(LeavesBlock.PERSISTENT);
        return PAR_BLOC.computeIfAbsent(st.getBlock(), b -> !LieuxRegles.naturel(BuiltInRegistries.BLOCK.getKey(b).toString(), false));
    }

    /** Une construction en p : le bloc, ou un contenant (coffre, four…) quel qu'il soit. */
    static boolean construit(Level l, BlockPos p) {
        BlockState st = l.getBlockState(p);
        return construit(st) || l.getBlockEntity(p) != null && !st.is(net.minecraft.world.level.block.Blocks.BEE_NEST);
    }

    /** Ce qui compte pour le tampon : une construction qu'un joueur peut faire (pas les barrières ni les blocs de structure). */
    static boolean compte(BlockState st) { return construit(st) && st.getBlock().defaultDestroyTime() >= 0; }

    /** Le résultat du tampon, par case de 4 x 4 x 4 blocs : (fin de validité << 1) | résultat. */
    private static final Map<Cle, Long> ZONES = new ConcurrentHashMap<>();
    /** Le résultat d'une case est gardé 30 secondes. */
    static final int DUREE_ZONE = 600;

    private record Cle(Object monde, long cellule) {}

    /**
     * Une construction à moins de {@link #TAMPON} blocs de p (dans chaque direction, de la case de 4 x 4 x 4 de p) ? Bon
     * marché : une section de 16 x 16 x 16 dont la palette n'a aucun bloc construit est sautée sans la lire, et le résultat
     * de chaque case est gardé 30 secondes. Un coin pas chargé compte comme construit (prudence).
     */
    static boolean zoneConstruite(Level l, BlockPos p) {
        Cle k = new Cle(l.dimension(), BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2));
        long maintenant = l.getGameTime();
        Long v = ZONES.get(k);
        if (v != null && (v >> 1) > maintenant) return (v & 1) == 1;
        int x0 = (p.getX() & ~3) - TAMPON, y0 = (p.getY() & ~3) - TAMPON, z0 = (p.getZ() & ~3) - TAMPON;
        boolean r = chercher(l, x0, y0, z0, x0 + 3 + 2 * TAMPON, y0 + 3 + 2 * TAMPON, z0 + 3 + 2 * TAMPON);
        if (ZONES.size() > 50_000) ZONES.clear();
        ZONES.put(k, (maintenant + DUREE_ZONE) << 1 | (r ? 1 : 0));
        return r;
    }

    /** Un bloc qui {@link #compte} dans la boîte (bornes comprises). */
    static boolean chercher(Level l, int x0, int y0, int z0, int x1, int y1, int z1) { return chercher(l, x0, y0, z0, x1, y1, z1, null); }

    /** Pareil, sans compter les torches et le pavé que ce mineur a posés aux positions {@code siens} ({@link #sien}). */
    static boolean chercher(Level l, int x0, int y0, int z0, int x1, int y1, int z1, LongSet siens) {
        y0 = Math.max(y0, l.getMinBuildHeight());
        y1 = Math.min(y1, l.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!l.hasChunk(cx, cz)) return true;
                LevelChunk c = l.getChunk(cx, cz);
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    LevelChunkSection s = c.getSection(c.getSectionIndexFromSectionY(sy));
                    if (s.hasOnlyAir() || !s.maybeHas(Lieux::compte)) continue;
                    int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15), az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                    int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                    for (int x = ax; x <= bx; x++)
                        for (int y = ay; y <= by; y++)
                            for (int z = az; z <= bz; z++)
                                if (compte(s.getBlockState(x & 15, y & 15, z & 15)) && (siens == null || !sien(s.getBlockState(x & 15, y & 15, z & 15), siens, x, y, z))) return true;
                }
            }
        return false;
    }

    /**
     * Sous une construction : le haut d'une colonne à moins de {@link #FONDATION} blocs (à l'horizontale) est construit et
     * plus haut que p. 81 colonnes, lues sur la carte des hauteurs : pas de balayage.
     */
    static boolean sousFondation(Level l, BlockPos p) {
        for (int dx = -FONDATION; dx <= FONDATION; dx++)
            for (int dz = -FONDATION; dz <= FONDATION; dz++) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if (!l.hasChunk(x >> 4, z >> 4)) return true;
                int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y >= p.getY() && compte(l.getBlockState(new BlockPos(x, y, z)))) return true;
            }
        return false;
    }

    /**
     * p est dans l'emprise entière d'une structure générée (tout le village, pas seulement une de ses maisons : un arbre entre
     * deux maisons en fait partie). Bon marché sans structure : le chunk n'a alors aucune référence.
     */
    static boolean dansStructure(Level l, BlockPos p) {
        if (!(l instanceof ServerLevel sl) || !l.isLoaded(p)) return false;
        var refs = sl.getChunk(p).getAllReferences();
        if (refs.isEmpty()) return false;
        for (var s : refs.keySet()) if (sl.structureManager().getStructureAt(p, s).isValid()) return true;
        return Monde.structure(l, p) != null;
    }

    /** p est dans sa colonie (le rayon autour de son poste) ; sans poste, nulle part. */
    static boolean dansColonie(Travailleur t, BlockPos p) {
        return t.bureau != null && LieuxRegles.dansColonie(t.bureau.getX(), t.bureau.getZ(), Reglages.actuel.rayon_colonie, p.getX(), p.getZ());
    }

    /** p est dans la zone que son chef lui a désignée par « Travaille ici ». */
    static boolean ici(Travailleur t, BlockPos p) {
        return t.lieuImpose != null && LieuxRegles.dist2(t.lieuImpose.getX(), t.lieuImpose.getZ(), p.getX(), p.getZ()) <= (long) RAYON_ICI * RAYON_ICI;
    }

    /**
     * Il peut casser le bloc p pour travailler ou se débloquer : jamais une construction (ni un contenant), jamais dans le
     * tampon d'une construction (sauf dans la zone « Travaille ici »), jamais près du poste d'un autre joueur ni dans une
     * structure générée ; le mineur, jamais sous une fondation. Seule exception : la guerre ({@link #enGuerre}).
     */
    static boolean peutCasser(Travailleur t, BlockPos p) {
        Level l = t.level();
        if (enGuerre.test(t)) return true;
        if (construit(l, p)) return false;
        if (!regles(l)) return true;
        if (Monde.structure(l, p) != null || Mineur.baseVoisine(t, p) != null) return false;
        if ("mineur".equals(t.metier) && p.getY() < Monde.surface(l, p.getX(), p.getZ()) - 1 && sousFondation(l, p)) return false;
        return ici(t, p) || !zoneConstruite(l, p) || saMine(t, p) && !mineConstruite(t, p);
    }

    /**
     * Comme {@link #peutCasser}, pour la mare d'un champ du fermier ({@link Fermier.Champ#mares}) : la terre labourée et les
     * cultures de son champ ({@code siens} : leurs positions, {@link #sien}) ne comptent pas comme construction, ni en p ni
     * dans le tampon ; et le poste de son chef ne compte pas dans le tampon quand il y est la seule construction (un champ
     * collé au poste, quand il n'y a pas d'autre place). Le tampon est relu sans le bloc du poste (six boîtes autour de lui).
     */
    static boolean peutCasserPresDuPoste(Travailleur t, BlockPos p, LongSet siens) {
        if (peutCasser(t, p)) return true;
        Level l = t.level();
        BlockPos b = t.bureau;
        if (construit(l, p) && !sien(l.getBlockState(p), siens, p.getX(), p.getY(), p.getZ()) || Monde.structure(l, p) != null || Mineur.baseVoisine(t, p) != null) return false;
        if (!regles(l) || ici(t, p)) return true; // sa terre labourée, hors des règles (essais) ou dans la zone « Travaille ici »
        int x0 = (p.getX() & ~3) - TAMPON, y0 = (p.getY() & ~3) - TAMPON, z0 = (p.getZ() & ~3) - TAMPON;
        int x1 = x0 + 3 + 2 * TAMPON, y1 = y0 + 3 + 2 * TAMPON, z1 = z0 + 3 + 2 * TAMPON;
        if (siens != null && !chercher(l, x0, y0, z0, x1, y1, z1, siens)) return true; // rien d'autre que son champ dans le tampon
        if (b == null) return false;
        int bx = b.getX(), by = b.getY(), bz = b.getZ();
        if (bx < x0 || bx > x1 || by < y0 || by > y1 || bz < z0 || bz > z1) return false; // la construction n'est pas le poste
        return (bx == x0 || !chercher(l, x0, y0, z0, bx - 1, y1, z1, siens)) && (bx == x1 || !chercher(l, bx + 1, y0, z0, x1, y1, z1, siens))
                && (by == y0 || !chercher(l, bx, y0, z0, bx, by - 1, z1, siens)) && (by == y1 || !chercher(l, bx, by + 1, z0, bx, y1, z1, siens))
                && (bz == z0 || !chercher(l, bx, by, z0, bx, by, bz - 1, siens)) && (bz == z1 || !chercher(l, bx, by, bz + 1, bx, by, z1, siens));
    }

    /** Au plus tant de blocs posés retenus par mineur (les plus récents) : une mine entière, escaliers et galeries. */
    static final int POSES = 512;

    /**
     * Le mineur pose une torche ou du pavé en p : il le retient, pour que ce bloc-là ne compte pas dans le tampon de sa
     * mine ({@link #mineConstruite}). Une torche ou un coffre qu'un joueur pose à côté compte toujours.
     */
    static void pose(Travailleur t, BlockPos p) {
        if (!"mineur".equals(t.metier)) return;
        long[] a = t.memoire.getLongArray("poses_mine");
        long[] n = java.util.Arrays.copyOfRange(a, Math.max(0, a.length + 1 - POSES), a.length + 1);
        n[n.length - 1] = p.asLong();
        t.memoire.putLongArray("poses_mine", n);
    }

    /**
     * Ce bloc en (x, y, z) est une torche ou du pavé que ce mineur a posé lui-même, ou la terre labourée ou une culture du
     * champ de ce fermier ({@link #peutCasserPresDuPoste}).
     */
    static boolean sien(BlockState st, LongSet siens, int x, int y, int z) {
        return siens != null && (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(net.neoforged.neoforge.common.Tags.Blocks.COBBLESTONES)
                || st.is(Blocks.FARMLAND) || st.getBlock() instanceof net.minecraft.world.level.block.CropBlock) && siens.contains(BlockPos.asLong(x, y, z));
    }

    /** Le résultat de {@link #mineConstruite}, par mineur et par case de 4 x 4 x 4 : comme {@link #ZONES}. */
    private static final Map<Cle, Long> MINES = new ConcurrentHashMap<>();

    /**
     * Le tampon dans sa mine : une construction à moins de {@link #TAMPON} blocs de p, sans compter ses propres torches et
     * son pavé ({@link #pose}). Lu seulement quand {@link #zoneConstruite} a vu quelque chose ; gardé 30 secondes.
     */
    static boolean mineConstruite(Travailleur t, BlockPos p) {
        Level l = t.level();
        Cle k = new Cle(java.util.List.of(t.getUUID(), l.dimension()), BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2));
        long maintenant = l.getGameTime();
        Long v = MINES.get(k);
        if (v != null && (v >> 1) > maintenant) return (v & 1) == 1;
        int x0 = (p.getX() & ~3) - TAMPON, y0 = (p.getY() & ~3) - TAMPON, z0 = (p.getZ() & ~3) - TAMPON;
        boolean r = chercher(l, x0, y0, z0, x0 + 3 + 2 * TAMPON, y0 + 3 + 2 * TAMPON, z0 + 3 + 2 * TAMPON,
                new LongOpenHashSet(t.memoire.getLongArray("poses_mine")));
        if (MINES.size() > 50_000) MINES.clear();
        MINES.put(k, (maintenant + DUREE_ZONE) << 1 | (r ? 1 : 0));
        return r;
    }

    /** Rayon (à l'horizontale) de la mine d'un mineur autour de son entrée : son escalier et ses galeries. */
    static final int RAYON_MINE = 32;

    /**
     * p est dans la mine que ce mineur a ouverte ({@code mine} : l'entrée, choisie hors du tampon par {@link #puitsPermis}) :
     * sous l'entrée, à moins de {@link #RAYON_MINE} blocs. Là, ses propres torches et le pavé dont il comble les trous
     * ({@link #pose}) ne comptent pas dans le tampon : sinon il ne pouvait pas continuer (fantôme : 0 charbon en 2 jours).
     * Toute autre construction (coffre, rails, torche d'un joueur) garde son tampon ({@link #mineConstruite}) ; les
     * fondations ({@link #sousFondation}) et les blocs construits eux-mêmes restent interdits.
     */
    static boolean saMine(Travailleur t, BlockPos p) {
        if (!"mineur".equals(t.metier) || !t.memoire.contains("mine")) return false;
        BlockPos e = BlockPos.of(t.memoire.getLong("mine"));
        return p.getY() <= e.getY() + 2 && LieuxRegles.dist2(e.getX(), e.getZ(), p.getX(), p.getZ()) <= (long) RAYON_MINE * RAYON_MINE;
    }

    /** Le bloc lui-même est une construction qu'il ne doit pas casser (sans les règles de lieu) : pour {@link Actions.Casser}. */
    static boolean protege(Travailleur t, BlockPos p) { return !enGuerre.test(t) && construit(t.level(), p); }

    /**
     * Un arbre (pied p) qu'il a le droit d'abattre : hors de la colonie, ou dans sa zone « Travaille ici » ; jamais dans le
     * tampon d'une construction (un arbre de village, de jardin), ni dans une structure, ni près d'un autre poste.
     */
    static boolean arbrePermis(Travailleur t, BlockPos p) {
        Level l = t.level();
        if (!regles(l)) return true;
        if (dansStructure(l, p) || Mineur.baseVoisine(t, p) != null) return false;
        if (ici(t, p)) return true;
        return !dansColonie(t, p) && !zoneConstruite(l, p);
    }

    /**
     * Un endroit où ouvrir son puits : sol naturel, hors de toute structure et des terres d'un voisin, loin des constructions
     * (hors du tampon) ; dans la colonie, au moins à 2 tampons de toute construction.
     */
    static boolean puitsPermis(Travailleur t, BlockPos sol) {
        Level l = t.level();
        if (Mineur.interdit(t, sol, false)) return false;
        if (!regles(l) || ici(t, sol)) return true;
        if (zoneConstruite(l, sol)) return false;
        if (!dansColonie(t, sol)) return true;
        // Dans la colonie : loin des constructions, à 2 tampons au moins (4 cases voisines à 8 blocs).
        for (int[] d : new int[][]{{TAMPON, 0}, {-TAMPON, 0}, {0, TAMPON}, {0, -TAMPON}})
            if (zoneConstruite(l, sol.offset(d[0], 0, d[1]))) return false;
        return true;
    }

    /** Le lieu de travail choisi, et ce qu'il en dit. */
    record Lieu(BlockPos pos, String texte) {}

    /**
     * Le lieu de travail de cet ouvrier : celui que son chef a désigné (« Travaille ici »), sinon celui qu'il a choisi (le
     * puits du mineur, le dernier arbre du bûcheron), sinon son poste. Null s'il n'a ni lieu ni poste.
     */
    static Lieu lieu(Travailleur t) {
        BlockPos p = t.lieuImpose;
        if (p == null && t.memoire.contains("puits")) p = BlockPos.of(t.memoire.getLong("puits"));
        if (p == null && t.memoire.contains("lieu")) p = BlockPos.of(t.memoire.getLong("lieu"));
        if (p == null) p = t.bureau;
        return p == null ? null : new Lieu(p, LieuxRegles.phrase(t.metier, p.getX(), p.getY(), p.getZ()));
    }

    /** Il a choisi ce lieu : il le retient, et le dit s'il change de coin (à plus de 32 blocs du lieu d'avant). */
    static void choisi(Travailleur t, BlockPos p) {
        boolean nouveau = !t.memoire.contains("lieu") || BlockPos.of(t.memoire.getLong("lieu")).distSqr(p) > 32 * 32;
        if (nouveau) t.memoire.putLong("lieu", p.asLong());
        if (nouveau) t.dire(LieuxRegles.phrase(t.metier, p.getX(), p.getY(), p.getZ()));
    }
}

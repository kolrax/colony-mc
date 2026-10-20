package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Le GPS (KOLRAX, 20/10) : le trajet complet jusqu'au but, calculé d'avance sur la carte du relief, avant de partir. Une
 * recherche A* sur des cases de {@link #CASE} blocs, chacune à la hauteur de son sol : le terrain chargé, sinon celui que
 * le monde générerait là. On passe d'une case à l'autre si la pente se marche (un bloc de montée par bloc d'avancée, au
 * plus) ; jamais par la lave ni la neige poudreuse ; l'eau se nage, plus lentement. Une montagne n'est ni évitée ni
 * recherchée : la route est la plus courte qui se marche, par-dessus si elle est la plus courte. Le détail (les blocs, les
 * portes, les arbres) reste à la marche du jeu, d'un point de la route au suivant ({@link Itineraire}).
 */
final class Gps {
    static final int CASE = 4;
    /**
     * Cases explorées au plus : 100 par case de distance à vol d'oiseau, entre 1500 et 6000. Un but sans route (sur un arbre,
     * dans un trou fermé) ne fait pas explorer tout le pays : au banc de charge, 6000 cases pour un but à 18 blocs coûtaient 1 s.
     */
    static final int NOEUDS_PAR_CASE = 100, NOEUDS_MIN = 1500, NOEUDS_MAX = 6000;
    /** Hauteurs nouvelles demandées au générateur au plus par recherche (quelques ms chacune) ; elles restent connues ensuite. */
    static final int GENERATEUR_MAX = 64;
    /** Recherches au plus par tick du serveur, pour tous les PNJ (les autres attendent le tick suivant). */
    static final int PAR_TICK = 1;
    static int restant = PAR_TICK;

    /** Un nouveau tick du serveur ({@link Itineraire#nouveauTick}). */
    static void nouveauTick() { restant = PAR_TICK; }

    /** Une recherche est encore permise sur ce tick. */
    static boolean libre() { return restant > 0; }

    /** Les hauteurs déjà demandées au générateur (elles ne changent pas), pour tous les PNJ ; vidé au-delà de 200 000. */
    private static final Map<Long, Integer> GENERE = new HashMap<>();
    private static ServerLevel genereDe;
    static final int BLOQUE = Integer.MIN_VALUE, INCONNU = Integer.MIN_VALUE + 1;

    private record Noeud(long cle, double f) {}

    static long cle(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** La case de ce bloc. */
    static long cle(BlockPos p) { return cle(Math.floorDiv(p.getX(), CASE), Math.floorDiv(p.getZ(), CASE)); }

    /**
     * La route de {@code de} jusqu'à {@code a} : le centre des cases traversées, du premier pas jusqu'au but (à la hauteur
     * estimée du sol). Sans route complète, celle qui mène le plus près du but. Les cases {@code coupees} (un point de
     * route que la marche n'a pas atteint) ne se traversent plus. Liste vide s'il n'y a rien de mieux que sa place.
     */
    static List<BlockPos> route(ServerLevel l, BlockPos de, BlockPos a, Set<Long> coupees) { return chercher(l, de, a, coupees).points; }

    /** Une route, et si elle atteint le but (sinon elle s'arrête au plus près : au pied d'une paroi, au bord d'un trou). */
    record Route(List<BlockPos> points, boolean complete) {}

    static Route chercher(ServerLevel l, BlockPos de, BlockPos a, Set<Long> coupees) {
        long t0 = System.nanoTime();
        restant--;
        Hauteurs h = new Hauteurs(l);
        int sx = Math.floorDiv(de.getX(), CASE), sz = Math.floorDiv(de.getZ(), CASE);
        int gx = Math.floorDiv(a.getX(), CASE), gz = Math.floorDiv(a.getZ(), CASE);
        long depart = cle(sx, sz), but = cle(gx, gz);
        Map<Long, Double> g = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> sol = new HashMap<>();
        PriorityQueue<Noeud> file = new PriorityQueue<>((x, y) -> Double.compare(x.f, y.f));
        g.put(depart, 0.0);
        sol.put(depart, de.getY() << 1);
        file.add(new Noeud(depart, reste(sx, sz, gx, gz)));
        long meilleur = depart;
        double meilleurReste = reste(sx, sz, gx, gz);
        int explores = 0, max = (int) Math.clamp(NOEUDS_PAR_CASE * reste(sx, sz, gx, gz) / CASE, NOEUDS_MIN, NOEUDS_MAX);
        while (!file.isEmpty() && explores < max) {
            Noeud n = file.poll();
            int cx = (int) (n.cle >> 32), cz = (int) n.cle;
            double gn = g.get(n.cle);
            if (n.f > gn + reste(cx, cz, gx, gz) + 1e-6) continue; // une entrée périmée
            explores++;
            double r = reste(cx, cz, gx, gz);
            if (r < meilleurReste) { meilleurReste = r; meilleur = n.cle; }
            if (n.cle == but) break;
            int hc = sol.get(n.cle) >> 1;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    long k = cle(nx, nz);
                    if (k != but && coupees.contains(k)) continue;
                    int s = h.sol(nx, nz);
                    if (s == BLOQUE) continue;
                    if (s == INCONNU) s = hc << 1; // pas encore mesuré : supposé praticable, la route se corrigera en chemin
                    int dy = (s >> 1) - hc;
                    double pas = CASE * (dx != 0 && dz != 0 ? Math.sqrt(2) : 1);
                    if (dy > pas || dy < -2 * pas) continue; // trop raide pour la marche (montée), ou une falaise (descente)
                    double c = gn + Math.sqrt(pas * pas + dy * dy) * ((s & 1) != 0 ? 2 : 1);
                    Double ancien = g.get(k);
                    if (ancien != null && ancien <= c) continue;
                    g.put(k, c);
                    parent.put(k, n.cle);
                    sol.put(k, s);
                    file.add(new Noeud(k, c + reste(nx, nz, gx, gz)));
                }
        }
        List<BlockPos> route = new ArrayList<>();
        for (long k = meilleur; k != depart; k = parent.get(k)) {
            int cx = (int) (k >> 32), cz = (int) k;
            route.add(new BlockPos(cx * CASE + CASE / 2, sol.get(k) >> 1, cz * CASE + CASE / 2));
        }
        Collections.reverse(route);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 50) ColoniePnj.LOG.warn("GPS lent : {} ms, {} cases explorées, {} hauteurs du générateur, {} -> {} ({} blocs), route {} {}",
                ms, explores, h.generateur, de.toShortString(), a.toShortString(), (int) Math.sqrt(de.distSqr(a)), route.size(), meilleur == but ? "complète" : "partielle");
        return new Route(route, meilleur == but);
    }

    private static double reste(int cx, int cz, int gx, int gz) { return CASE * Math.sqrt((double) (cx - gx) * (cx - gx) + (double) (cz - gz) * (cz - gz)); }

    /** La hauteur du sol des cases, pour une recherche : le double de la hauteur, plus 1 dans l'eau ; {@link #BLOQUE} sinon. */
    private static final class Hauteurs {
        final ServerLevel l;
        final Map<Long, Integer> vues = new HashMap<>();
        int generateur;

        Hauteurs(ServerLevel l) { this.l = l; }

        int sol(int cx, int cz) {
            return vues.computeIfAbsent(cle(cx, cz), k -> calculer(cx * CASE, cz * CASE));
        }

        private int calculer(int x, int z) {
            if (l.hasChunkAt(x, z) && l.hasChunkAt(x + 2, z + 2)) {
                // Deux colonnes de la case, la plus basse : un tronc ou un poteau n'en fait pas une falaise.
                int a = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), b = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 2, z + 2);
                int bx = a <= b ? x : x + 2, bz = a <= b ? z : z + 2, y = Math.min(a, b);
                BlockState dessous = l.getBlockState(new BlockPos(bx, y - 1, bz));
                if (dessous.getFluidState().is(FluidTags.LAVA) || dessous.is(Blocks.POWDER_SNOW) || dessous.is(Blocks.MAGMA_BLOCK)) return BLOQUE;
                return y << 1 | (dessous.getFluidState().is(FluidTags.WATER) ? 1 : 0);
            }
            // Pas encore chargé : la hauteur que le monde générerait (sans ce que les joueurs y ont bâti), en nombre limité.
            if (genereDe != l || GENERE.size() > 200_000) { GENERE.clear(); genereDe = l; }
            long k = cle(Math.floorDiv(x, CASE), Math.floorDiv(z, CASE));
            Integer connu = GENERE.get(k);
            if (connu != null) return connu;
            if (generateur++ >= GENERATEUR_MAX) return INCONNU;
            var src = l.getChunkSource();
            int y = src.getGenerator().getBaseHeight(x + CASE / 2, z + CASE / 2, Heightmap.Types.WORLD_SURFACE_WG, l, src.randomState());
            int v = y << 1 | (y <= l.getSeaLevel() ? 1 : 0);
            GENERE.put(k, v);
            return v;
        }
    }
}

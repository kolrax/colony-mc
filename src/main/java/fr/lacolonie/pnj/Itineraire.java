package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comment le PNJ se rend quelque part, à n'importe quelle distance, sans foncer dans un cul-de-sac.
 * <ul>
 * <li>De près, il suit le chemin calculé par Minecraft (64 blocs au plus).</li>
 * <li>De loin, il avance d'étape en étape. Chaque étape (une trentaine de blocs vers le but, ou de côté s'il le
 *   faut) n'est choisie que si son chemin complet est trouvé AVANT de partir, et il évite les coins déjà
 *   parcourus : pas de cul-de-sac, pas de tours en rond.</li>
 * <li>Pour rentrer au bureau, il refait en sens inverse le chemin de l'aller (sa trace), qu'il sait praticable.</li>
 * <li>Sous terre, il cherche la sortie la plus proche en explorant les galeries ; s'il est muré, il creuse un
 *   escalier jusqu'à la surface (jamais à travers une construction, jamais vers un liquide).</li>
 * <li>Il court sur les longues distances et nage pour traverser l'eau ; avec un bateau dans son sac, il traverse
 *   les lacs et les océans en bateau.</li>
 * </ul>
 */
final class Itineraire {
    final Vec3 but;
    final boolean versLeBureau;
    private Path chemin;
    private Vec3 etape;
    private final Map<Long, Integer> visites = new HashMap<>();
    private List<BlockPos> sortie;
    private int indexSortie;
    private BlockPos aCreuser;
    private float progres;
    private int directRate, echecs, sortieAvance, ageCourant;
    private boolean sortieRatee;
    boolean bloque;

    Itineraire(Travailleur t, Vec3 but) {
        this.but = but;
        this.versLeBureau = t.bureau != null && Vec3.atBottomCenterOf(t.bureau).distanceToSqr(but) < 64;
    }

    /** Il creuse ou suit la route vers la sortie : il ne marche pas droit vers le but, mais il avance. */
    boolean occupe() { return aCreuser != null || sortie != null; }

    static double horizontale(Vec3 a, Vec3 b) { double dx = a.x - b.x, dz = a.z - b.z; return Math.sqrt(dx * dx + dz * dz); }

    static long cellule(BlockPos p) { return ((long) (p.getX() >> 3) << 32) ^ ((p.getZ() >> 3) & 0xffffffffL); }

    /** Un pas de marche vers le but (appelé à chaque tick par l'action en cours). */
    void avancer(Travailleur t, double vitesse, int age) {
        if (bloque) return; // c'est à l'action de décider (elle échoue ou attend)
        if (t.getVehicle() instanceof Boat b) { naviguer(t, b, age); return; }
        var nav = t.getNavigation();
        Vec3 p = t.position();
        double dh = horizontale(p, but), dv = Math.abs(but.y - p.y);
        // Il court sur les longues distances et en nageant (comme un joueur : 30 % plus vite, et il a plus faim).
        boolean courir = dh > 20 || t.isInWater();
        double v = vitesse;
        t.setSprinting(courir);

        if (aCreuser != null) { t.diag = "creuse " + aCreuser.toShortString(); creuser(t); return; }
        if (montee != null) {
            if (t.blockPosition().equals(montee) || age > monteeFin) { montee = null; etape = null; }
            else {
                t.diag = "monte en " + montee.toShortString();
                if (t.onGround() && age % 10 == 0) {
                    Path c = t.getNavigation().createPath(montee, 0);
                    if (c != null) t.getNavigation().moveTo(c, 1.0);
                    else t.getJumpControl().jump();
                }
                t.getMoveControl().setWantedPosition(montee.getX() + 0.5, montee.getY(), montee.getZ() + 0.5, 1.0);
                ageCourant = age;
                return;
            }
        }
        ageCourant = age;
        if (sortie != null) { t.diag = "sort de sous terre " + indexSortie + "/" + sortie.size(); suivreSortie(t, v, age); return; }
        // Une grande étendue d'eau devant lui, et un bateau dans son sac : il le met à l'eau.
        if (age % 20 == 0 && age >= bateauPas && embarquer(t)) return;

        // De près : le chemin direct, s'il mène bien au but.
        if (dh <= 40 && dv <= 20 && directRate < 2) {
            t.diag = "chemin direct";
            if (p.distanceToSqr(but) < 2.25 && nav.isDone()) { t.getMoveControl().setWantedPosition(but.x, but.y, but.z, v); return; }
            if (age % 20 == 0 || nav.isDone()) {
                if (!t.onGround() && !t.isInWater()) return;
                Path c = nav.createPath(BlockPos.containing(but), 0);
                // Le chemin s'arrête à côté du but (bloc à casser, coffre…) : c'est bon aussi.
                if (c != null && (c.canReach() || c.getEndNode() != null && c.getEndNode().asBlockPos().distSqr(BlockPos.containing(but)) <= 4)) {
                    nav.moveTo(c, v);
                    directRate = 0;
                } else {
                    // Un chemin qui s'arrête en route, recalculé à chaque instant, le ferait hésiter et revenir sur ses pas
                    // (Enguerrand le 6 octobre). De loin, il passe par des étapes sûres ; tout près (derrière un mur, au fond
                    // d'un trou, en hauteur), c'est inaccessible : il ne tourne pas autour, l'action choisira autre chose.
                    nav.stop();
                    boolean pres = dh <= 8 && !sousTerre(t);
                    directRate = pres ? directRate + 1 : 2;
                    if (pres && directRate >= 2) { bloque = true; t.diag = "inaccessible"; }
                }
            }
            return;
        }

        // De loin, ou quand le chemin direct n'arrive pas au but : étape par étape.
        boolean arrive = etape != null && p.distanceToSqr(etape) < 6.25;
        if (etape == null || arrive || nav.isDone() && age % 10 == 0) {
            if (!t.onGround() && !t.isInWater()) return; // Minecraft ne calcule un chemin qu'au sol ou dans l'eau
            choisirEtape(t, v);
        } else if (age % 60 == 0 && chemin != null) nav.moveTo(chemin, v);
    }

    void choisirEtape(Travailleur t, double v) {
        Level l = t.level();
        BlockPos ici = t.blockPosition();
        visites.merge(cellule(ici), 1, Integer::sum);
        Vec3 p = t.position();
        double dh = horizontale(p, but);

        // a) Le but lui-même, s'il est à portée d'un chemin complet.
        if (dh <= 48 && Math.abs(but.y - p.y) <= 24 && aller(t, BlockPos.containing(but), but, v)) return;

        // b) Pour rentrer : le chemin de l'aller, à l'envers (le point le plus avancé qu'il peut atteindre).
        if (versLeBureau && !t.trace.isEmpty()) {
            int fin = t.trace.size() - 1;
            for (int k = Math.max(0, fin - 3); k <= fin; k++) {
                BlockPos q = t.trace.get(k);
                if (aller(t, q, Vec3.atBottomCenterOf(q), v)) {
                    while (t.trace.size() > k) t.trace.remove(t.trace.size() - 1);
                    return;
                }
            }
        }

        // c) Sous terre : d'abord sortir à l'air libre.
        if (sousTerre(t) && (dh > 24 || but.y > p.y + 6)) {
            sortie = sortieRatee ? null : versLeCiel(l, ici, 30000);
            indexSortie = 0;
            sortieAvance = 0;
            if (sortie != null) return;
            sortieRatee = true; // pas de sortie à pied : inutile de la rechercher à chaque marche qu'il creuse
            commencerCreusage(t);
            if (aCreuser != null) return;
        }

        // d) Une étape sûre : des points à 28 et 14 blocs, vers le but d'abord, puis de plus en plus de côté.
        double cap = Math.atan2(but.z - p.z, but.x - p.x);
        record Candidat(BlockPos pos, double score) {}
        List<Candidat> candidats = new ArrayList<>();
        Set<BlockPos> vus = new HashSet<>();
        for (int ecart = 0; ecart <= 180; ecart += 20) {
            for (int signe : ecart == 0 || ecart == 180 ? new int[]{1} : new int[]{1, -1}) {
                for (double r : new double[]{Math.min(28, Math.max(8, dh)), 14, 7}) {
                    double a = cap + signe * Math.toRadians(ecart);
                    int x = (int) Math.floor(p.x + Math.cos(a) * r), z = (int) Math.floor(p.z + Math.sin(a) * r);
                    int y = Monde.surface(l, x, z);
                    if (y == Integer.MIN_VALUE) continue;
                    if (Math.abs(y - p.y) > 12 && !sousTerre(t)) y = Math.max(y, ici.getY()); // falaise : on vise sa hauteur
                    BlockPos q = new BlockPos(x, y, z);
                    if (!vus.add(q) || !Monde.debout(l, q)) continue;
                    double score = horizontale(Vec3.atBottomCenterOf(q), but) + 24 * visites.getOrDefault(cellule(q), 0)
                            + ("eau".equals(Monde.liquide(l, q)) || "eau".equals(Monde.liquide(l, q.below())) ? 10 : 0);
                    candidats.add(new Candidat(q, score));
                }
            }
        }
        candidats.sort(Comparator.comparingDouble(Candidat::score));
        int essais = 0;
        List<BlockPos> rates = new ArrayList<>();
        for (Candidat c : candidats) {
            if (essais >= 18) break;
            if (rates.stream().anyMatch(r -> r.distSqr(c.pos) < 64)) continue; // même obstacle qu'un point déjà essayé : on essaie ailleurs
            essais++;
            if (aller(t, c.pos, Vec3.atBottomCenterOf(c.pos), v)) { echecs = 0; return; }
            rates.add(c.pos);
        }
        // Rien d'atteignable (au fond d'un trou, au pied d'une paroi) : il creuse un escalier pour en sortir.
        // S'il ne peut pas creuser non plus (construction, liquide), après plusieurs essais, il se dit coincé.
        commencerCreusage(t);
        if (aCreuser != null) return;
        if (++echecs >= 3) { bloque = true; t.diag = "coincé"; }
        etape = null;
    }

    /** Calcule le chemin complet jusqu'à q ; s'il y arrive, part dessus. */
    private boolean aller(Travailleur t, BlockPos q, Vec3 cible, double v) {
        Path c = t.getNavigation().createPath(q, 1);
        if (c == null || !c.canReach()) return false;
        chemin = c;
        etape = cible;
        t.diag = "étape " + q.getX() + " " + q.getZ() + " (but à " + (int) horizontale(t.position(), but) + " blocs)";
        t.getNavigation().moveTo(c, v);
        return true;
    }

    /** Vraiment sous terre : sans ciel, et à plus de 8 blocs sous la surface (pas sous un toit, un arbre ou un surplomb). */
    static boolean sousTerre(Travailleur t) {
        BlockPos p = t.blockPosition();
        int s = Monde.surface(t.level(), p.getX(), p.getZ());
        return s != Integer.MIN_VALUE && p.getY() < s - 8 && !t.level().canSeeSky(p.above());
    }

    /** Pour /pnj diag : pourquoi il avance ou n'avance pas (sous terre, sortie, ce qu'il peut creuser). */
    static List<String> diagnostic(Travailleur t) {
        Level l = t.level();
        BlockPos p = t.blockPosition();
        List<String> r = new ArrayList<>();
        int s = Monde.surface(l, p.getX(), p.getZ());
        r.add(t.prenom + " en " + p.toShortString() + " : " + (t.action == null ? "aucune action" : t.action.nom + " (" + t.diag + ")") + ", résultat « " + t.resultat + " »");
        r.add("sol en surface à y=" + s + ", ciel visible : " + l.canSeeSky(p.above()) + ", sous terre : " + sousTerre(t) + ", au sol : " + t.onGround() + ", dans l'eau : " + t.isInWater());
        List<BlockPos> sortie = versLeCiel(l, p, 30000);
        r.add("sortie à pied : " + (sortie == null ? "aucune" : sortie.size() + " pas, jusqu'en " + sortie.get(sortie.size() - 1).toShortString()));
        if (t.bureau != null) {
            Vec3 but = Vec3.atBottomCenterOf(t.bureau);
            double cap = Math.atan2(but.z - t.getZ(), but.x - t.getX());
            StringBuilder b = new StringBuilder("étapes vers le bureau :");
            for (int ecart = -160; ecart <= 180; ecart += 40) {
                int x = (int) Math.floor(t.getX() + Math.cos(cap + Math.toRadians(ecart)) * 20), z = (int) Math.floor(t.getZ() + Math.sin(cap + Math.toRadians(ecart)) * 20);
                int y = Monde.surface(l, x, z);
                if (y == Integer.MIN_VALUE) { b.append(" ").append(ecart).append("°:non chargé"); continue; }
                var c = t.getNavigation().createPath(new BlockPos(x, y, z), 1);
                b.append(" ").append(ecart).append("°(y").append(y).append("):").append(c == null ? "aucun" : c.canReach() ? "OK" : "partiel");
            }
            r.add(b.toString());
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            StringBuilder b = new StringBuilder(d.getName() + " :");
            for (BlockPos q : List.of(p.relative(d), p.relative(d).above(), p.relative(d).above(2), p.above(2))) {
                var st = l.getBlockState(q);
                b.append(" ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath())
                        .append(creusable(l, q) ? "" : Monde.construit(l, q) ? "(construction)" : "(non)");
            }
            r.add(b.toString());
        }
        return r;
    }

    // --- Sortir de sous terre ------------------------------------------------------------------------

    /**
     * Le chemin à pied jusqu'à l'air libre le plus proche, en explorant les galeries et les grottes (on monte d'un
     * bloc, on descend de trois au plus, on nage, on grimpe aux échelles). Null s'il n'y en a pas.
     */
    static List<BlockPos> versLeCiel(Level l, BlockPos depart, int max) {
        BlockPos d = Monde.debout(l, depart) ? depart : Monde.debout(l, depart.above()) ? depart.above() : depart;
        ArrayDeque<BlockPos> file = new ArrayDeque<>(List.of(d));
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        parent.put(d, d);
        int n = 0;
        while (!file.isEmpty() && n++ < max) {
            BlockPos p = file.poll();
            int s = Monde.surface(l, p.getX(), p.getZ());
            if (p != d && (l.canSeeSky(p.above()) || s != Integer.MIN_VALUE && p.getY() >= s - 1)) {
                List<BlockPos> route = new ArrayList<>();
                for (BlockPos x = p; !x.equals(d); x = parent.get(x)) route.add(0, x);
                return route;
            }
            List<BlockPos> voisins = new ArrayList<>();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos devant = p.relative(dir);
                if (Monde.traversable(l, devant) && Monde.traversable(l, devant.above())) {
                    for (int dy = 0; dy >= -3; dy--) {
                        BlockPos q = devant.above(dy);
                        if (Monde.debout(l, q)) { voisins.add(q); break; }
                        if (!Monde.traversable(l, q.below())) break;
                    }
                } else if (Monde.traversable(l, p.above(2)) && Monde.debout(l, devant.above())) voisins.add(devant.above()); // une marche
            }
            BlockState pieds = l.getBlockState(p);
            if (!pieds.getFluidState().isEmpty() || pieds.is(BlockTags.CLIMBABLE)) { // nager ou grimper
                if (Monde.debout(l, p.above())) voisins.add(p.above());
                if (Monde.debout(l, p.below())) voisins.add(p.below());
            }
            for (BlockPos q : voisins)
                if (Math.abs(q.getX() - d.getX()) <= 96 && Math.abs(q.getZ() - d.getZ()) <= 96 && parent.putIfAbsent(q, p) == null) file.add(q);
        }
        return null;
    }

    private void suivreSortie(Travailleur t, double v, int age) {
        if (indexSortie >= sortie.size()) { sortie = null; etape = null; return; }
        // La route trouvée ne se laisse pas suivre (saut impossible, passage trop étroit…) : il creusera plutôt.
        if (++sortieAvance > 300) { sortie = null; sortieRatee = true; etape = null; return; }
        int cible = Math.min(sortie.size() - 1, indexSortie + 5);
        BlockPos q = sortie.get(cible);
        Vec3 c = Vec3.atBottomCenterOf(q);
        boolean dernier = cible == sortie.size() - 1;
        // Le dernier pas se fait jusqu'au bout : à un bloc près, il resterait sous le plafond et chercherait encore la sortie.
        if (t.position().distanceToSqr(c) < (dernier ? 0.3 : 2.25)) { indexSortie = cible + 1; sortieAvance = 0; return; }
        if (dernier && t.position().distanceToSqr(c) < 4) { t.getMoveControl().setWantedPosition(c.x, c.y, c.z, v); return; }
        if (age % 20 == 0 || t.getNavigation().isDone()) {
            Path ch = t.getNavigation().createPath(q, 0);
            if (ch != null) t.getNavigation().moveTo(ch, v);
            else t.getMoveControl().setWantedPosition(c.x, c.y, c.z, v);
        }
    }

    // --- Creuser un escalier vers la surface ---------------------------------------------------------

    private Direction sens;
    private boolean monte;
    /** La marche qu'il vient de dégager : il y monte avant de réfléchir à la suite. */
    private BlockPos montee;
    private int monteeFin;
    private final List<BlockPos> aCasser = new ArrayList<>();

    private void commencerCreusage(Travailleur t) {
        Level l = t.level();
        BlockPos p = t.blockPosition();
        List<Direction> dirs = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        Vec3 pos = t.position();
        dirs.sort(Comparator.comparingDouble(d -> -(d.getStepX() * (but.x - pos.x) + d.getStepZ() * (but.z - pos.z))));
        // Un escalier qui monte vers le but ; sinon (plafond qu'il ne peut pas toucher), d'abord un pas de côté en galerie.
        List<List<BlockPos>> essais = new ArrayList<>();
        List<Direction> sensEssais = new ArrayList<>();
        for (Direction d : dirs) { essais.add(List.of(p.above(2), p.relative(d).above(), p.relative(d).above(2))); sensEssais.add(d); }
        for (Direction d : dirs) { essais.add(List.of(p.relative(d), p.relative(d).above())); sensEssais.add(d); }
        for (int i = 0; i < essais.size(); i++) {
            Direction d = sensEssais.get(i);
            List<BlockPos> blocs = essais.get(i);
            // Rien à creuser : ce n'est une solution que si la marche est déjà prête (il retente de monter dessus).
            BlockPos marche = i < dirs.size() ? p.relative(d).above() : p.relative(d);
            boolean aCreuserIci = blocs.stream().anyMatch(b -> !Monde.traversable(l, b));
            if (blocs.stream().allMatch(b -> creusable(l, b)) && (aCreuserIci || Monde.debout(l, marche))) {
                monte = i < dirs.size();
                sens = d;
                aCasser.clear();
                for (BlockPos b : blocs) if (!Monde.traversable(l, b)) aCasser.add(b);
                aCreuser = aCasser.isEmpty() ? null : aCasser.get(0);
                if (aCreuser == null) monter(t);
                else { progres = 0; echecs = 0; Cerveau.parfois(t, "creuse", "Je suis bloqué : je creuse un passage.", false, 600); }
                return;
            }
        }
    }

    /** On peut le casser sans risque : pas de construction, pas d'incassable, et pas de lave à côté (l'eau, il la traverse à la nage). */
    static boolean creusable(Level l, BlockPos b) {
        if (!l.isLoaded(b)) return false;
        BlockState st = l.getBlockState(b);
        if (Monde.traversable(l, b)) return true;
        if (st.getDestroySpeed(l, b) < 0 || Monde.construit(l, b) || !st.getFluidState().isEmpty()) return false;
        for (Direction d : Direction.values()) if (l.getBlockState(b.relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
        return true;
    }

    private void creuser(Travailleur t) {
        Level l = t.level();
        if (!creusable(l, aCreuser)) { aCreuser = null; aCasser.clear(); return; }
        BlockState st = l.getBlockState(aCreuser);
        if (Monde.traversable(l, aCreuser) || st.isAir()) { suivantACreuser(t); return; }
        meilleurOutil(t, st, aCreuser);
        t.getNavigation().stop();
        t.getLookControl().setLookAt(Vec3.atCenterOf(aCreuser));
        if ((int) progres % 5 == 0) t.swing(InteractionHand.MAIN_HAND);
        progres += 1;
        l.destroyBlockProgress(t.getId(), aCreuser, Math.min(9, (int) (progres / Actions.duree(t, st, aCreuser) * 10)));
        if (progres < Actions.duree(t, st, aCreuser)) return;
        l.destroyBlockProgress(t.getId(), aCreuser, -1);
        Actions.recolter(t, aCreuser);
        Actions.userOutil(t, 1);
        suivantACreuser(t);
    }

    private void suivantACreuser(Travailleur t) {
        aCasser.remove(aCreuser);
        progres = 0;
        aCreuser = aCasser.isEmpty() ? null : aCasser.get(0);
        if (aCreuser == null) monter(t);
    }

    /** Le passage est dégagé : il monte sur la marche suivante (ou avance d'un pas en galerie). */
    private void monter(Travailleur t) {
        montee = monte ? t.blockPosition().relative(sens).above() : t.blockPosition().relative(sens);
        monteeFin = ageCourant + 60;
        echecs = 0; // il avance : ce n'est pas un échec
        if (monte) t.getJumpControl().jump();
        t.getMoveControl().setWantedPosition(montee.getX() + 0.5, montee.getY(), montee.getZ() + 0.5, 1.0);
        etape = null;
    }

    // --- En bateau -----------------------------------------------------------------------------------

    private int bateauPas, bateauBloque, barreFin;
    /** Décalage du cap (en radians) pour longer une côte qu'il ne peut pas aborder. */
    private double barre;

    /** De l'eau en surface en (x, z) (sinon de la terre, ou un coin pas encore chargé). */
    static boolean eau(Level l, int x, int z) {
        int s = Monde.surface(l, x, z);
        return s != Integer.MIN_VALUE && l.getFluidState(new BlockPos(x, s - 1, z)).is(FluidTags.WATER);
    }

    static int bateau(Travailleur t) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (t.inventaire.getItem(i).is(ItemTags.BOATS)) return i;
        return -1;
    }

    /** Au bord de l'eau (ou dedans), face à au moins 12 blocs d'eau vers le but : il met son bateau à l'eau et monte dedans. */
    private boolean embarquer(Travailleur t) {
        Level l = t.level();
        Vec3 p = t.position();
        if (horizontale(p, but) < 16 || bateau(t) < 0) return false;
        double cap = Math.atan2(but.z - p.z, but.x - p.x), cx = Math.cos(cap), cz = Math.sin(cap);
        int premier = -1;
        for (int d = 0; d <= 2 && premier < 0; d++) if (eau(l, Mth.floor(p.x + cx * d), Mth.floor(p.z + cz * d))) premier = d;
        if (premier < 0) return false;
        int n = 0;
        for (int d = premier + 1; d <= premier + 12; d++) if (eau(l, Mth.floor(p.x + cx * d), Mth.floor(p.z + cz * d))) n++;
        if (n < 10) return false; // une flaque, une petite rivière : il nage
        // Un bloc plus loin que le bord : le bateau ne doit pas toucher la rive.
        int x = Mth.floor(p.x + cx * (premier + 1)), z = Mth.floor(p.z + cz * (premier + 1)), surface = Monde.surface(l, x, z) - 1;
        if (!eau(l, x, z) || surface < t.getBlockY() - 3 || surface > t.getBlockY() + 1) return false; // l'eau est au pied d'une falaise
        int i = bateau(t);
        ItemStack objet = t.inventaire.getItem(i);
        Boat b = EntityType.BOAT.create(l);
        if (b == null) return false;
        String bois = BuiltInRegistries.ITEM.getKey(objet.getItem()).getPath().replace("_chest_boat", "").replace("_boat", "").replace("_raft", "");
        b.setVariant(Boat.Type.byName(bois));
        b.moveTo(x + 0.5, surface + 1, z + 0.5, (float) Math.toDegrees(cap) - 90, 0);
        if (!l.noCollision(b, b.getBoundingBox())) return false;
        l.addFreshEntity(b);
        if (!t.startRiding(b)) { b.discard(); return false; }
        t.memoire.putString("bateau", BuiltInRegistries.ITEM.getKey(objet.getItem()).toString());
        objet.shrink(1);
        t.inventaire.setChanged();
        t.getNavigation().stop();
        t.setSprinting(false);
        bateauBloque = 0;
        barreFin = 0;
        Cerveau.parfois(t, "bateau", "Je prends le bateau pour traverser.", false, 600);
        return true;
    }

    /**
     * En bateau : cap sur le but. Une rive praticable devant lui, il débarque ; une côte qu'il ne peut pas aborder
     * (falaise, rochers), il la longe ; coincé, il finit par débarquer et nager.
     */
    private void naviguer(Travailleur t, Boat b, int age) {
        Level l = t.level();
        Vec3 p = b.position();
        double dh = horizontale(p, but);
        t.diag = "en bateau (but à " + (int) dh + " blocs)";
        double cap = Math.atan2(but.z - p.z, but.x - p.x) + (age < barreFin ? barre : 0), cx = Math.cos(cap), cz = Math.sin(cap);
        BlockPos rive = null;
        boolean libre = true;
        for (int d = 1; d <= 3 && libre; d++) {
            int x = Mth.floor(p.x + cx * d), z = Mth.floor(p.z + cz * d);
            if (eau(l, x, z)) continue;
            libre = false;
            int s = Monde.surface(l, x, z);
            if (s != Integer.MIN_VALUE && Math.abs(s - p.y) <= 1.5 && Monde.debout(l, new BlockPos(x, s, z))) rive = new BlockPos(x, s, z);
        }
        if (rive != null && Vec3.atBottomCenterOf(rive).distanceTo(p) < 2.5) { debarquer(t, b, rive); return; }
        if (!libre && rive == null && age >= barreFin) {
            // Une côte qu'on ne peut pas aborder : il la longe, du côté où l'eau continue.
            double base = Math.atan2(but.z - p.z, but.x - p.x);
            for (int deg : new int[]{40, -40, 80, -80, 120, -120, 160, -160}) {
                double a = base + Math.toRadians(deg);
                boolean ok = true;
                for (int d = 1; d <= 5 && ok; d++) ok = eau(l, Mth.floor(p.x + Math.cos(a) * d), Mth.floor(p.z + Math.sin(a) * d));
                if (ok) { barre = Math.toRadians(deg); barreFin = age + 60; break; }
            }
        }
        // Comme à pied, il n'entre pas dans un coin que le serveur n'a pas fini de préparer.
        if (l instanceof net.minecraft.server.level.ServerLevel sl && !sl.isPositionEntityTicking(BlockPos.containing(p.add(cx * 8, 0, cz * 8)))) {
            t.diag = "attend que le terrain soit prêt";
            return;
        }
        float voulu = (float) Math.toDegrees(cap) - 90, ecart = Mth.wrapDegrees(voulu - b.getYRot());
        b.setYRot(b.getYRot() + Mth.clamp(ecart, -6, 6));
        if (Math.abs(ecart) < 60) {
            float r = b.getYRot() * Mth.DEG_TO_RAD;
            b.setDeltaMovement(b.getDeltaMovement().add(-Mth.sin(r) * 0.04, 0, Mth.cos(r) * 0.04));
            b.setPaddleState(true, true);
        }
        // Bloqué contre des rochers ou des nénuphars : un autre cap ; toujours rien, il débarque et nage.
        bateauBloque = b.getDeltaMovement().horizontalDistanceSqr() < 0.0004 ? bateauBloque + 1 : 0;
        if (bateauBloque == 60) { barre = Math.toRadians(t.getRandom().nextBoolean() ? 90 : -90); barreFin = age + 60; }
        if (bateauBloque > 200) { debarquer(t, b, null); bateauPas = age + 600; }
    }

    /** Il descend (sur la rive s'il y en a une, vers laquelle il se tourne) et remet le bateau dans son sac. */
    private void debarquer(Travailleur t, Boat b, BlockPos rive) {
        if (rive != null) t.setYRot((float) Math.toDegrees(Math.atan2(rive.getZ() + 0.5 - t.getZ(), rive.getX() + 0.5 - t.getX())) - 90);
        t.stopRiding();
        b.discard();
        ResourceLocation id = ResourceLocation.tryParse(t.memoire.getString("bateau"));
        var objet = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        t.memoire.remove("bateau");
        t.ranger(new ItemStack(objet == Items.AIR ? Items.OAK_BOAT : objet));
        etape = null;
        chemin = null;
        directRate = 0;
    }

    /** Prend en main l'outil de son sac qui casse ce bloc le plus vite (pioche, pelle…). */
    static void meilleurOutil(Travailleur t, BlockState st, BlockPos p) {
        int meilleur = -1;
        float vitesse = Math.max(1f, t.getMainHandItem().getDestroySpeed(st));
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            float s = t.inventaire.getItem(i).getDestroySpeed(st);
            if (s > vitesse) { vitesse = s; meilleur = i; }
        }
        if (meilleur < 0) return;
        ItemStack tenu = t.getMainHandItem();
        t.setItemSlot(EquipmentSlot.MAINHAND, t.inventaire.getItem(meilleur));
        t.inventaire.setItem(meilleur, tenu);
    }
}

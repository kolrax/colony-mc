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
import net.minecraft.world.item.Item;
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
    /** Le chemin direct accepté en dernier, et quand il a été calculé (âge de l'action). */
    private Path direct;
    private int directDate = -1000, sortieDate = -1000;
    private Path cheminSortie;

    // Le choix d'une étape, étalé sur plusieurs ticks (au plus PAR_TICK recherches par tick, BUDGET pour tout le serveur).
    // Une recherche ratée explore tout ce qu'elle peut (jusqu'à 1024 nœuds, ~9 ms) : c'est elle qu'il faut rendre rare et courte.
    static final int PAR_TICK = 2, BUDGET = 4, DELAI_APRES_ECHEC = 20, DELAI_MAX = 200, RECALCUL = 10;
    /**
     * La portée d'une recherche borne la longueur MARCHÉE du chemin (pas le rayon) : on donne la distance du point plus
     * {@link #DETOUR} blocs (44 pour une étape à 28 blocs), 36 au moins vers le but et la trace. Vers un point d'étape,
     * la moitié des 1024 nœuds ({@link #NOEUDS_ETAPE}).
     */
    static final float DETOUR = 16, PORTEE_MIN = 36, NOEUDS_ETAPE = 0.5f;
    /** Un point d'étape raté depuis le même endroit n'est pas recherché de nouveau avant 30 s. */
    static final int OUBLI = 600;
    /** Le choix en cours a déjà été repris sans la mémoire des points ratés (tout y était : elle est peut-être fausse). */
    private boolean sansMemoire;
    /** Recherches « étape » qui restent à tous les PNJ pour ce tick du serveur (remis à BUDGET à chaque tick). */
    static int budget = BUDGET;
    /** -1 : pas de choix en cours ; 0 : le but ; 1 : la trace ; 2 : la sortie de sous terre ; 3 : les étapes autour. */
    private int phase = -1, curseur, essais, recherchesCeTick;
    private List<BlockPos> candidats;
    private final List<BlockPos> rates = new ArrayList<>();
    /** Après un choix où rien n'était atteignable, il ne recommence pas avant ce moment (temps du monde). */
    private long prochainChoix;
    /** Choix entièrement ratés de suite : le délai avant le suivant double à chaque fois (remis à zéro par une étape réussie). */
    private int choixRates;

    /** Un PNJ qui attend un jeton depuis tant de ticks en reçoit un, même si le budget du tick est épuisé. */
    static final int ATTENTE_MAX = 10;
    /** Depuis quand (temps du monde) il attend un jeton refusé faute de budget ; -1 s'il n'attend pas. */
    private long attendDepuis = -1;
    /** Le tick où il est passé hors budget : ce tick-là, il a droit à toutes ses recherches (PAR_TICK). */
    private long passeDroit = -1;
    /** Un seul passe-droit par tick du serveur, au PNJ qui attend depuis le plus longtemps ({@link #plusAncienne}). */
    static boolean passeDroitDonne;
    /** Le début de la plus vieille attente vue au tick précédent, et celle qu'on relève pendant ce tick. */
    static long plusAncienne = Long.MAX_VALUE, plusAncienneVue = Long.MAX_VALUE;

    /** Un nouveau tick du serveur : le budget des étapes repart, et le passe-droit ira au plus ancien des affamés. */
    static void nouveauTick() {
        budget = BUDGET;
        passeDroitDonne = false;
        plusAncienne = plusAncienneVue;
        plusAncienneVue = Long.MAX_VALUE;
    }

    /**
     * Une recherche « étape » de plus est permise maintenant (au plus PAR_TICK pour lui, BUDGET pour tous) ; elle est
     * décomptée. Les PNJ passent toujours dans le même ordre : sans recours, ceux de la fin de la liste n'auraient jamais
     * de jeton et finiraient faussement « coincés ». Après {@link #ATTENTE_MAX} ticks d'attente, il passe hors budget
     * (pour ce tick, jusqu'à PAR_TICK recherches) ; mais un seul par tick du serveur, celui qui attend depuis le plus
     * longtemps : les autres affamés attendent les ticks suivants, chacun son tour.
     * Les refus et les passe-droits sont comptés dans {@link Charge}.
     */
    boolean jeton(long maintenant) {
        if (recherchesCeTick >= PAR_TICK) return false;
        if (budget <= 0) {
            if (attendDepuis < 0) attendDepuis = maintenant;
            boolean force = passeDroit == maintenant
                    || !passeDroitDonne && maintenant - attendDepuis >= ATTENTE_MAX && attendDepuis <= plusAncienne;
            Charge.ICI.jeton(force);
            if (!force) { plusAncienneVue = Math.min(plusAncienneVue, attendDepuis); return false; }
            if (passeDroit != maintenant) { passeDroitDonne = true; passeDroit = maintenant; }
        }
        attendDepuis = -1;
        recherchesCeTick++;
        budget--;
        return true;
    }

    private void finChoix() { phase = -1; candidats = null; rates.clear(); attendDepuis = -1; }

    /**
     * Il attend son tour pour chercher une étape (jeton refusé) ou le délai après un choix raté : il est immobile, mais
     * ce n'est pas d'être coincé (l'action ne doit pas compter ce temps-là comme « immobile »).
     */
    boolean attend(long maintenant) { return attendDepuis >= 0 || maintenant < prochainChoix || !plan.isEmpty() && !parti; }

    Itineraire(Travailleur t, Vec3 but) {
        this.but = but;
        this.versLeBureau = t.bureau != null && Vec3.atBottomCenterOf(t.bureau).distanceToSqr(but) < 64;
    }

    /** Il creuse ou suit la route vers la sortie : il ne marche pas droit vers le but, mais il avance. */
    boolean occupe() { return aCreuser != null || sortie != null || pilier != null || !pont.isEmpty(); }

    /** Il suit un chemin direct qui a réussi, sans détour en cours (étape, montée, creusage, pilier, pont) : le refaire ne coûte qu'une recherche courte. */
    boolean surCheminDirect() { return !bloque && directRate == 0 && direct != null && phase < 0 && montee == null && !occupe(); }

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
        if (pilier != null || !pont.isEmpty()) { poser(t, age); return; }
        if (montee != null) {
            if (t.blockPosition().equals(montee) || age > monteeFin) {
                boolean sorti = t.blockPosition().equals(montee);
                montee = null;
                etape = null;
                finChoix();
                if (sorti) reprendre(t);
            }
            else {
                t.diag = "monte en " + montee.toShortString();
                if (t.onGround() && age % 10 == 0) {
                    Path c = chercher(t, montee, 0, Charge.Origine.MONTEE);
                    if (c != null) t.getNavigation().moveTo(c, 1.0);
                    else t.getJumpControl().jump();
                }
                t.getMoveControl().setWantedPosition(montee.getX() + 0.5, montee.getY(), montee.getZ() + 0.5, 1.0);
                ageCourant = age;
                return;
            }
        }
        ageCourant = age;
        if (immobile(t, age)) return;
        if (sortie != null) { t.diag = "sort de sous terre " + indexSortie + "/" + sortie.size(); suivreSortie(t, v, age); return; }
        // Une grande étendue d'eau devant lui, et un bateau dans son sac : il le met à l'eau.
        if (age % 20 == 0 && age >= bateauPas && embarquer(t)) return;

        // De près : le chemin direct, s'il mène bien au but.
        if (dh <= 40 && dv <= 20 && directRate < 2 && plan.isEmpty()) {
            t.diag = "chemin direct";
            if (phase >= 0) finChoix();
            attendDepuis = -1; // un choix d'étape laissé en route : ses positions ne valent plus rien
            if (p.distanceToSqr(but) < 2.25 && nav.isDone()) { t.getMoveControl().setWantedPosition(but.x, but.y, but.z, v); return; }
            // Au bout du chemin accepté, sans le recalculer à chaque tick (seulement toutes les 20 ticks) : s'il mène au but,
            // il fait les derniers pas tout droit ; s'il s'arrête à côté (bloc à casser, coffre, ou but de l'autre côté
            // d'une lave, d'un trou), il reste au bord : marcher tout droit le jetterait dedans.
            if (nav.isDone() && auBout(t, direct) && age % 20 != 0) {
                if (direct.canReach()) t.getMoveControl().setWantedPosition(but.x, but.y, but.z, v);
                return;
            }
            // Un chemin complet qu'il suit encore n'est pas recalculé (plan A1 : pas d'hésitation) : Minecraft le refait de
            // lui-même si un bloc change dessus, et un arrêt sur place est vu par immobile().
            boolean suit = !nav.isDone() && direct != null && direct.canReach() && nav.getPath() == direct;
            if (age % 20 == 0 && !suit || nav.isDone() && age - directDate >= RECALCUL) {
                if (!t.onGround() && !t.isInWater()) return;
                directDate = age;
                Path c = chercher(t, BlockPos.containing(but), 0, Charge.Origine.DIRECT);
                // Le chemin s'arrête à côté du but (bloc à casser, coffre…) : c'est bon aussi.
                if (c != null && (c.canReach() || c.getEndNode() != null && c.getEndNode().asBlockPos().distSqr(BlockPos.containing(but)) <= 4)) {
                    nav.moveTo(c, v);
                    direct = c;
                    directRate = 0;
                    choixRates = 0;
                } else {
                    direct = null;
                    // Un chemin qui s'arrête en route, recalculé à chaque instant, le ferait hésiter et revenir sur ses pas
                    // (Enguerrand le 6 octobre) : il passe aux étapes, qui ne suivent que des chemins complets.
                    nav.stop();
                    directRate = dh <= 8 ? directRate + 1 : 2; // tout près, il réessaie une fois
                }
            }
            return;
        }

        // De loin, ou quand le chemin direct n'arrive pas au but : le trajet calculé d'avance (plan A1), étape par étape.
        if (!plan.isEmpty()) { suivrePlan(t, v); return; }
        boolean arrive = etape != null && p.distanceToSqr(etape) < 6.25;
        if (phase >= 0 || etape == null || arrive || nav.isDone() && age % 10 == 0) {
            if (!t.onGround() && !t.isInWater()) return; // Minecraft ne calcule un chemin qu'au sol ou dans l'eau
            choisirEtape(t, v);
        } else if (age % 60 == 0 && chemin != null) nav.moveTo(chemin, v);
    }

    /**
     * Choisit la prochaine étape : le but, la trace, la sortie de sous terre, puis des points autour, dans cet ordre.
     * Le choix s'étale sur plusieurs ticks (au plus {@link #PAR_TICK} recherches par tick, {@link #BUDGET} pour tout le
     * serveur) : en attendant, il ne part sur aucun chemin partiel. Si rien n'est atteignable, il attend
     * {@link #DELAI_APRES_ECHEC} ticks avant de recommencer, puis le double à chaque nouvel échec ({@link #DELAI_MAX} au
     * plus), sauf s'il creuse (un échec coûte cher : la recherche explore tout).
     */
    void choisirEtape(Travailleur t, double v) {
        Level l = t.level();
        long maintenant = l.getGameTime();
        if (maintenant < prochainChoix) return;
        recherchesCeTick = 0;
        BlockPos ici = t.blockPosition();
        Vec3 p = t.position();
        double dh = horizontale(p, but);
        if (phase < 0) {
            visites.merge(cellule(ici), 1, Integer::sum);
            phase = 0;
        }

        // a) Le but lui-même, s'il est à portée d'un chemin complet. Si le chemin direct vient d'échouer, la moitié des nœuds.
        if (phase == 0) {
            if (dh <= 48 && Math.abs(but.y - p.y) <= 24) {
                if (!jeton(maintenant)) return;
                if (aller(t, BlockPos.containing(but), but, v, false, directRate >= 2 ? NOEUDS_ETAPE : 1)) { finChoix(); return; }
            }
            phase = 1;
            curseur = Math.max(0, t.trace.size() - 4);
        }

        // b) Pour rentrer : le chemin de l'aller, à l'envers (le point le plus avancé qu'il peut atteindre).
        if (phase == 1) {
            if (versLeBureau) {
                for (; curseur < t.trace.size(); curseur++) {
                    if (!jeton(maintenant)) return;
                    BlockPos q = t.trace.get(curseur);
                    if (aller(t, q, Vec3.atBottomCenterOf(q), v, false, 1)) {
                        while (t.trace.size() > curseur) t.trace.remove(t.trace.size() - 1);
                        finChoix();
                        return;
                    }
                }
            }
            phase = 2;
        }

        // c) Sous terre : d'abord sortir à l'air libre.
        if (phase == 2) {
            phase = 3;
            if (sousTerre(t) && (dh > 24 || but.y > p.y + 6)) {
                sortie = sortieRatee ? null : versLeCiel(l, ici, 30000);
                indexSortie = 0;
                sortieAvance = 0;
                if (sortie != null) { finChoix(); choixRates = 0; return; }
                sortieRatee = true; // pas de sortie à pied : inutile de la rechercher à chaque marche qu'il creuse
                if (evasionPermise(t)) commencerCreusage(t);
                if (aCreuser != null) { finChoix(); choixRates = 0; return; }
            }
            candidats = candidats(t, l, ici, p, dh);
            curseur = 0;
            essais = 0;
            rates.clear();
            sansMemoire = false;
        }

        // d) Une étape sûre : des points à 28 et 14 blocs, vers le but d'abord, puis de plus en plus de côté.
        // Un point raté depuis ce même endroit (cellule de 8 blocs, hauteur à 4 blocs près) il y a moins de 30 s n'est pas
        // recherché de nouveau : le terrain n'a pas changé.
        long depart = cellule(ici) * 31 + (ici.getY() >> 2);
        for (; curseur < candidats.size() && essais < 18; curseur++) {
            BlockPos c = candidats.get(curseur);
            if (rates.stream().anyMatch(r -> r.distSqr(c) < 64)) continue; // même obstacle qu'un point déjà essayé : on essaie ailleurs
            long cle = depart * 31 + cellule(c);
            Long oubli = t.etapesRatees.get(cle);
            if (!sansMemoire && oubli != null && oubli > maintenant) { rates.add(c); continue; }
            if (!jeton(maintenant)) return;
            essais++;
            if (aller(t, c, Vec3.atBottomCenterOf(c), v, true, NOEUDS_ETAPE)) {
                finChoix();
                // Il peut bouger, mais le but, tout près, n'a pas de chemin (derrière un mur, en hauteur, dans un trou) :
                // inaccessible. Il ne tourne pas autour d'étape en étape (Raoul le 6 octobre) : l'action choisira autre chose.
                if (dh <= 8 && !sousTerre(t)) { oublierPlan(); t.getNavigation().stop(); bloque = true; t.diag = "inaccessible"; return; }
                echecs = 0;
                return;
            }
            rates.add(c);
            if (t.etapesRatees.size() > 256) t.etapesRatees.values().removeIf(f -> f <= maintenant);
            t.etapesRatees.put(cle, maintenant + OUBLI);
        }
        // Tous les points étaient en mémoire, aucun essai : ce n'est pas un échec. Il reprend le choix sans la mémoire.
        if (essais == 0 && !sansMemoire && !candidats.isEmpty()) {
            sansMemoire = true;
            curseur = 0;
            rates.clear();
            return; // au tick suivant (le choix est toujours en cours : phase 3)
        }
        finChoix();
        // Rien d'atteignable (au fond d'un trou, au pied d'une paroi) : il creuse un escalier pour en sortir.
        // S'il ne peut pas creuser non plus (construction, liquide), après plusieurs essais, il se dit coincé.
        // Il creuse : il avance, le prochain choix vient vite. Sinon, il attend de plus en plus (20, 40, 80… 200 ticks).
        // Plan A3 : la paroi devant lui ne se récolte pas avec ce qu'il a (de la pierre sans pioche) : la creuser serait long
        // et ne rendrait rien. Il monte d'abord sur des blocs de son sac (qu'il reprend ensuite), puis creuse s'il le faut.
        if (evasionPermise(t) && sansOutilPourLaParoi(t) && caseDeChantier(t, false, true) >= 0 && commencerAPoser(t)) { montee = null; choixRates = 0; etape = null; return; }
        if (evasionPermise(t)) commencerCreusage(t);
        if (aCreuser != null) { prochainChoix = maintenant + DELAI_APRES_ECHEC; choixRates = 0; return; }
        // Rien à creuser (des murs construits, trop haut), ou seulement un pas de côté déjà libre qui ne mène nulle part :
        // il monte sur des blocs de son sac, ou jette un petit pont.
        if ((montee == null || !monte) && evasionPermise(t) && commencerAPoser(t)) { montee = null; choixRates = 0; etape = null; return; }
        prochainChoix = maintenant + Math.min(DELAI_MAX, DELAI_APRES_ECHEC << Math.min(4, choixRates++));
        if (++echecs >= 3) { bloque = true; t.diag = "coincé"; }
        etape = null;
    }

    /** Les points d'étape possibles autour de lui, du meilleur au moins bon (vers le but, pas déjà parcouru, pas dans l'eau). */
    private List<BlockPos> candidats(Travailleur t, Level l, BlockPos ici, Vec3 p, double dh) {
        double cap = Math.atan2(but.z - p.z, but.x - p.x);
        record Candidat(BlockPos pos, double score) {}
        List<Candidat> candidats = new ArrayList<>();
        Set<BlockPos> vus = new HashSet<>();
        boolean sousUnToit = !sousTerre(t) && !l.canSeeSky(ici.above()); // une maison, des arbres (sous terre, c'est la sortie qui compte)
        for (int ecart = 0; ecart <= 180; ecart += 20) {
            for (int signe : ecart == 0 || ecart == 180 ? new int[]{1} : new int[]{1, -1}) {
                for (double r : new double[]{Math.min(28, Math.max(8, dh)), 14, 7}) {
                    double a = cap + signe * Math.toRadians(ecart);
                    int x = (int) Math.floor(p.x + Math.cos(a) * r), z = (int) Math.floor(p.z + Math.sin(a) * r);
                    int y = Monde.surface(l, x, z);
                    if (y == Integer.MIN_VALUE) continue;
                    // Sous un toit (maison, grotte) : le sol de surface est au-dessus de lui, il vise un appui à sa hauteur.
                    int h = sousUnToit && y > p.y + 3 ? appuiPres(l, x, z, ici.getY()) : Integer.MIN_VALUE;
                    if (h != Integer.MIN_VALUE) y = h;
                    else if (Math.abs(y - p.y) > 12 && !sousTerre(t)) y = Math.max(y, ici.getY()); // falaise : on vise sa hauteur
                    BlockPos q = new BlockPos(x, y, z);
                    if (!vus.add(q) || !Monde.debout(l, q)) continue;
                    double score = horizontale(Vec3.atBottomCenterOf(q), but) + 24 * visites.getOrDefault(cellule(q), 0)
                            + ("eau".equals(Monde.liquide(l, q)) || "eau".equals(Monde.liquide(l, q.below())) ? 10 : 0);
                    candidats.add(new Candidat(q, score));
                }
            }
        }
        candidats.sort(Comparator.comparingDouble(Candidat::score));
        return candidats.stream().map(Candidat::pos).toList();
    }

    /** Il se tient au bout de ce chemin (à un bloc près). */
    static boolean auBout(Travailleur t, Path c) {
        return c != null && c.getEndNode() != null && c.getEndNode().asBlockPos().distToCenterSqr(t.position()) < 2.5;
    }

    /** Un endroit où se tenir en (x, z), à quelques blocs de la hauteur y ; Integer.MIN_VALUE s'il n'y en a pas. */
    static int appuiPres(Level l, int x, int z, int y) {
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, -4}) if (Monde.debout(l, new BlockPos(x, y + dy, z))) return y + dy;
        return Integer.MIN_VALUE;
    }

    /** Une recherche de chemin, comptée sous son origine pour le banc de charge ({@link Charge}). */
    static Path chercher(Travailleur t, BlockPos q, int precision, Charge.Origine o) { return chercher(t, q, precision, o, 0, 1); }

    /**
     * Une recherche de chemin bornée : {@code portee} (0 : la portée normale, 64) limite la distance parcourue et la région
     * chargée, {@code noeuds} la part des 1024 nœuds qu'elle peut explorer. Les deux sont remis comme avant après l'appel.
     */
    static Path chercher(Travailleur t, BlockPos q, int precision, Charge.Origine o, float portee, float noeuds) {
        var nav = t.getNavigation();
        t.origineChemin = o;
        t.porteeChemin = portee;
        nav.setMaxVisitedNodesMultiplier(noeuds);
        try { return nav.createPath(q, precision); }
        finally {
            t.origineChemin = Charge.Origine.AUTRE;
            t.porteeChemin = 0;
            nav.resetMaxVisitedNodesMultiplier();
        }
    }

    /** La portée d'une recherche vers q : sa distance plus {@link #DETOUR} blocs (la portée borne la longueur marchée). */
    static float portee(BlockPos depuis, BlockPos q) { return (float) Math.sqrt(depuis.distSqr(q)) + DETOUR; }

    /**
     * Calcule le chemin complet jusqu'à q ; s'il y arrive, part dessus. Portée : {@link #portee}, et au moins
     * {@link #PORTEE_MIN} vers le but ou la trace (pas un point d'étape, {@code court}) ; {@code noeuds} : la part des
     * 1024 nœuds.
     */
    private boolean aller(Travailleur t, BlockPos q, Vec3 cible, double v, boolean court, float noeuds) {
        float portee = Math.max(court ? 0 : PORTEE_MIN, portee(t.blockPosition(), q));
        Path c = chercher(t, q, 1, Charge.Origine.ETAPE, portee, noeuds);
        if (c == null || !c.canReach()) return false;
        choixRates = 0;
        chemin = c;
        t.diag = "étape " + q.getX() + " " + q.getZ() + " (but à " + (int) horizontale(t.position(), but) + " blocs)";
        // Plan A1 : il ne part pas encore. Le trajet se prolonge d'abord depuis le bout de cette étape jusqu'au but (ou aussi
        // loin que le terrain chargé le permet), puis il le suit d'un bout à l'autre (suivrePlan).
        oublierPlan();
        etape = cible;
        plan.add(c);
        planFini = q.equals(BlockPos.containing(but));
        prolonge = !planFini && !cibleMobile;
        return true;
    }

    // --- Le trajet calculé d'avance, comme un GPS (plan A1) ---------------------------------------------

    /** Au plus tant d'étapes calculées d'avance (près de 300 blocs). */
    static final int PLAN_MAX = 12;
    /** Points essayés au plus pour prolonger le trajet d'une étape (le premier choix, depuis sa place, en essaie 18). */
    static final int ESSAIS_SUITE = 6;
    /**
     * Le trajet : des chemins complets mis bout à bout, chacun cherché depuis la fin du précédent ({@link #depuis}). Le
     * dernier mène au but ({@code planFini}), ou s'arrête là où le terrain n'est pas encore chargé. Il ne part qu'une fois le
     * calcul fini ({@code parti}), et ne le refait que s'il est coupé.
     */
    private final List<Path> plan = new ArrayList<>();
    private int morceau;
    private boolean planFini, prolonge, parti;
    /** Les points qui restent à essayer pour prolonger le trajet (null : pas de prolongement en cours). */
    private List<BlockPos> suite;
    private int curseurSuite, essaisSuite;

    /** Combien d'étapes de son trajet il connaît d'avance (0 : pas de trajet calculé en cours). */
    int etapesConnues() { return plan.size(); }

    /** La dernière étape connue de son trajet mène au but. */
    boolean trajetComplet() { return planFini; }

    private void oublierPlan() {
        plan.clear();
        morceau = 0;
        planFini = prolonge = parti = false;
        suite = null;
        etape = null;
        attendDepuis = -1; // sinon attend() resterait vrai, et l'évitement des 3 s (immobile) ne viendrait plus
    }

    /** La cible bouge (le chef, un autre ouvrier) : il ne calcule pas son trajet d'avance, une étape à la fois suffit. */
    boolean cibleMobile;

    /**
     * Suit le trajet calculé. Au bout d'une étape, il enchaîne la suivante sans rien recalculer. Il ne le refait que s'il
     * est coupé : il s'est arrêté avant le bout (un bloc posé, quelqu'un en travers, le jeu a dû refaire le chemin), ou il
     * est resté immobile ({@link #immobile}). Au bout d'un trajet qui s'arrête avant le but, il choisit la suite de là.
     */
    private void suivrePlan(Travailleur t, double v) {
        var nav = t.getNavigation();
        if (prolonge) prolonger(t);
        if (!parti) {
            if (prolonge) { t.diag = "prépare son trajet (" + plan.size() + " étapes)"; return; }
            parti = true;
            nav.moveTo(plan.get(morceau), v);
        }
        Path seg = plan.get(morceau);
        etape = Vec3.atBottomCenterOf(seg.getEndNode().asBlockPos());
        Path suivi = nav.getPath();
        if (!nav.isDone() && (suivi == seg || seg.sameAs(suivi))) {
            if (t.tickCount % 20 == 0) t.diag = "trajet : étape " + (morceau + 1) + "/" + plan.size() + (planFini ? "" : " (suite à calculer)") + ", but à " + (int) horizontale(t.position(), but) + " blocs";
            return;
        }
        // Le jeu a refait le chemin de lui-même (une porte ouverte, un bloc posé près du trajet) jusqu'au bout de cette
        // étape : il le garde, sans s'arrêter ni recalculer le reste.
        if (!nav.isDone() && suivi != null && suivi.canReach() && suivi.getEndNode() != null
                && suivi.getEndNode().asBlockPos().distSqr(seg.getEndNode().asBlockPos()) <= 2) { plan.set(morceau, suivi); return; }
        if (auBout(t, seg)) {
            visites.merge(cellule(t.blockPosition()), 1, Integer::sum); // seulement les endroits vraiment atteints
            if (morceau + 1 < plan.size()) {
                morceau++;
                // Une étape calculée il y a un moment : un coup d'œil sur ses cases (un bloc posé, de la lave) ; sinon il la refait seule.
                if (!praticable(t.level(), plan.get(morceau)) && !refaireEtape(t, v)) return;
                nav.moveTo(plan.get(morceau), v);
                // Sa dernière étape connue : il essaie de prolonger le trajet en marchant (le terrain devant lui s'est chargé).
                if (morceau == plan.size() - 1 && !planFini && plan.size() < PLAN_MAX && !cibleMobile) prolonge = true;
                return;
            }
            // Au bout du trajet : les derniers pas tout droit seulement si ce chemin mène bien au but.
            if (planFini) { if (seg.canReach()) t.getMoveControl().setWantedPosition(but.x, but.y, but.z, v); return; }
            if (!prolonge) oublierPlan(); // d'ici, il choisit la suite (choisirEtape)
            return;
        }
        // Coupé avant le bout : il refait seulement cette étape, d'où il est, en gardant les suivantes (jamais un chemin partiel).
        if (t.onGround() || t.isInWater()) refaireEtape(t, v);
    }

    /**
     * Refait l'étape en cours depuis sa place, jusqu'au même bout : les étapes suivantes restent bonnes. Faux s'il attend un
     * jeton (il réessaie au tick suivant) ; sans chemin complet, tout le trajet est refait d'ici.
     */
    private boolean refaireEtape(Travailleur t, double v) {
        var nav = t.getNavigation();
        BlockPos bout = plan.get(morceau).getEndNode().asBlockPos();
        if (!jeton(t.level().getGameTime())) { nav.stop(); return false; }
        Path c = chercher(t, bout, 0, Charge.Origine.ETAPE, Math.max(PORTEE_MIN, portee(t.blockPosition(), bout)), 1);
        if (c == null || !c.canReach()) { t.diag = "trajet coupé"; nav.stop(); oublierPlan(); return false; }
        plan.set(morceau, c);
        nav.moveTo(c, v);
        return true;
    }

    /** Les cases d'un chemin calculé sont toujours libres (pas de bloc posé à hauteur des pieds ou de la tête, pas de lave). */
    static boolean praticable(Level l, Path c) {
        for (int i = 0; i < c.getNodeCount(); i++) {
            BlockPos n = c.getNodePos(i);
            for (BlockPos q : List.of(n, n.above())) {
                BlockState st = l.getBlockState(q);
                if (st.is(BlockTags.DOORS) || st.is(BlockTags.FENCE_GATES)) continue; // il les ouvre
                if (!Monde.traversable(l, q)) return false;
            }
        }
        return true;
    }

    /**
     * Prolonge le trajet d'une étape depuis la fin de la dernière : le but s'il est à portée, sinon un point qui le rapproche
     * du but d'au moins 4 blocs (jamais un demi-tour). Étalé sur plusieurs ticks avec les mêmes jetons que le choix d'une
     * étape ({@link #jeton}). Rien de trouvé : le trajet s'arrête là, il partira sur ce qu'il connaît.
     */
    private void prolonger(Travailleur t) {
        Level l = t.level();
        long maintenant = l.getGameTime();
        recherchesCeTick = 0;
        BlockPos d = plan.get(plan.size() - 1).getEndNode().asBlockPos();
        Vec3 pd = Vec3.atBottomCenterOf(d);
        double dh = horizontale(pd, but);
        if (suite == null) {
            if (plan.size() >= PLAN_MAX) { prolonge = false; return; }
            if (dh <= 48 && Math.abs(but.y - d.getY()) <= 24) {
                if (!jeton(maintenant)) return;
                BlockPos q = BlockPos.containing(but);
                Path c = depuis(t, d, q, Math.max(PORTEE_MIN, portee(d, q)), 1);
                // Comme le chemin direct : arrêté juste à côté du but (bloc à casser, coffre), c'est bon aussi.
                if (c != null && (c.canReach() || c.getEndNode() != null && c.getEndNode().asBlockPos().distSqr(q) <= 4)) {
                    plan.add(c);
                    planFini = true;
                    prolonge = false;
                    return;
                }
            }
            suite = candidats(t, l, d, pd, dh).stream().filter(c -> horizontale(Vec3.atBottomCenterOf(c), but) < dh - 4).toList();
            curseurSuite = 0;
            essaisSuite = 0;
            rates.clear();
        }
        for (; curseurSuite < suite.size() && essaisSuite < ESSAIS_SUITE; curseurSuite++) {
            BlockPos c = suite.get(curseurSuite);
            if (rates.stream().anyMatch(r -> r.distSqr(c) < 64)) continue;
            if (!jeton(maintenant)) return;
            essaisSuite++;
            Path ch = depuis(t, d, c, portee(d, c), NOEUDS_ETAPE);
            if (ch != null && ch.canReach()) { plan.add(ch); suite = null; rates.clear(); return; }
            rates.add(c);
        }
        suite = null;
        rates.clear();
        prolonge = false;
    }

    /**
     * Une recherche de chemin depuis {@code depart} (la fin d'une étape où il n'est pas encore) jusqu'à q : la même marche
     * que d'habitude ({@link Evaluateur}, endroits évités compris), dans une région autour du départ. Comptée comme une
     * recherche d'étape ({@link Charge}).
     */
    static Path depuis(Travailleur t, BlockPos depart, BlockPos q, float portee, float noeuds) {
        if (t.chercheur == null || !(t.getNavigation().getNodeEvaluator() instanceof Evaluateur e)) return null;
        int r = (int) portee + 8;
        var region = new net.minecraft.world.level.PathNavigationRegion(t.level(), depart.offset(-r, -r, -r), depart.offset(r, r, r));
        long debut = System.nanoTime();
        t.origineChemin = Charge.Origine.ETAPE;
        e.depart = depart;
        try { return t.chercheur.findPath(region, t, Set.of(q), portee, 1, noeuds); }
        finally {
            e.depart = null;
            t.origineChemin = Charge.Origine.AUTRE;
            Charge.ICI.mesurer(Charge.Partie.CHEMINS, debut);
        }
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

    // --- Immobile sans être arrivé (plan A2) -------------------------------------------------------

    /** Où il était au début de l'arrêt en cours, et depuis quand (âge de l'action). */
    private Vec3 repere;
    private int repereAge;

    /**
     * Il n'a presque pas bougé (moins d'un demi-bloc) depuis {@link Reglages#trajet_immobile_ticks} ticks, sans être arrivé
     * ni attendre son tour pour chercher une étape (ce n'est pas être coincé), ni se tenir au bout d'un chemin accepté
     * (le but est de l'autre côté d'un trou) : quelque chose que le calcul croit franchissable le retient (une noix de coco
     * d'un autre mod, une souche). Il évite cet endroit un moment et refait son trajet. Vrai s'il vient de le faire.
     * Le déblocage par pilier et pont, puis le retour au poste ({@link Coince}), restent derrière.
     */
    private boolean immobile(Travailleur t, int age) {
        Vec3 p = t.position();
        var nav = t.getNavigation();
        if (repere == null || horizontale(p, repere) >= 0.5 || t.onClimbable() || Math.abs(p.y - repere.y) >= 1.5 || attend(t.level().getGameTime()) || p.distanceToSqr(but) < 2.25
                || nav.isDone() && auBout(t, direct)) { repere = p; repereAge = age; return false; }
        // Sans chemin en cours, il ne cherche pas à marcher (il choisit, il attend, il pose) : seulement quand il en suit un.
        if (age - repereAge < Math.max(20, Reglages.actuel.trajet_immobile_ticks) || nav.isDone()) return false;
        repere = p;
        repereAge = age;
        if (!(nav.getNodeEvaluator() instanceof Evaluateur e)) return false;
        // L'endroit qui le retient : le prochain point de son chemin, et le bloc devant lui (vers ce point, ou vers le but),
        // à hauteur des pieds et de la tête. Jamais sa propre place, ni le but.
        BlockPos ici = t.blockPosition(), viseBut = BlockPos.containing(but);
        List<BlockPos> obstacles = new ArrayList<>();
        Path c = nav.getPath();
        Vec3 vers = etape != null ? etape : but;
        if (c != null && !c.isDone()) {
            BlockPos n = c.getNextNodePos();
            if (n.equals(ici) && c.getNextNodeIndex() + 1 < c.getNodeCount()) n = c.getNodePos(c.getNextNodeIndex() + 1);
            obstacles.add(n);
            vers = Vec3.atBottomCenterOf(n);
        }
        Vec3 cap = vers.subtract(p).multiply(1, 0, 1);
        if (cap.lengthSqr() > 1e-4) obstacles.add(BlockPos.containing(p.add(cap.normalize().scale(0.9))));
        long maintenant = t.level().getGameTime();
        BlockPos premier = null;
        for (BlockPos o : obstacles)
            for (BlockPos q : List.of(o, o.above())) {
                // Quelqu'un se tient là (un autre ouvrier, une bête) : il s'en ira, ce n'est pas un endroit à éviter.
                if (q == o && occupe(t, o.below())) break;
                if (q.getX() == ici.getX() && q.getZ() == ici.getZ() || q.equals(viseBut) || q.equals(viseBut.above())) continue;
                e.eviter(q, maintenant, maintenant + Reglages.actuel.trajet_evitement_ticks);
                if (premier == null) premier = q;
            }
        if (premier == null) return false;
        nav.stop();
        direct = null;
        directDate = -1000;
        chemin = null;
        oublierPlan();
        if (phase >= 0) finChoix();
        t.diag = "évite " + premier.toShortString();
        Cerveau.dire(t, "detour", 600, "Quelque chose me barre la route : je passe ailleurs.");
        return true;
    }

    /** La marche de Minecraft, qui tient pour murs les endroits qu'il a décidé d'éviter (plan A2), jusqu'à la fin de l'évitement. */
    static final class Evaluateur extends net.minecraft.world.level.pathfinder.WalkNodeEvaluator {
        private final Map<Long, Long> evites = new HashMap<>();

        Evaluateur() { setCanPassDoors(true); } // comme celui de Minecraft pour la marche

        /** Plan A1 : chercher depuis cet endroit plutôt que depuis là où il se tient (null : sa place). */
        BlockPos depart;

        @Override
        public net.minecraft.world.level.pathfinder.Node getStart() { return depart != null ? getStartNode(depart) : super.getStart(); }

        void eviter(BlockPos q, long maintenant, long fin) {
            if (evites.size() > 64) evites.values().removeIf(f -> f <= maintenant);
            evites.put(q.asLong(), fin);
        }

        @Override
        public net.minecraft.world.level.pathfinder.PathType getPathType(net.minecraft.world.level.pathfinder.PathfindingContext c, int x, int y, int z) {
            if (!evites.isEmpty() && mob != null) {
                Long f = evites.get(BlockPos.asLong(x, y, z));
                if (f != null && f > mob.level().getGameTime()) return net.minecraft.world.level.pathfinder.PathType.BLOCKED;
            }
            return super.getPathType(c, x, y, z);
        }
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
        // Arrivé à un point de la route plus proche (par le chemin de secours ci-dessous) : la route avance aussi.
        while (indexSortie < cible && t.position().distanceToSqr(Vec3.atBottomCenterOf(sortie.get(indexSortie))) < 2.25) { indexSortie++; sortieAvance = 0; }
        // Des pas tout droit (le dernier, ou au bout du chemin, sans le recalculer à chaque tick) seulement si le dernier
        // chemin calculé mène bien à ce point : sinon, entre lui et le point, il peut y avoir un trou ou de la lave.
        boolean complet = cheminSortie != null && cheminSortie.canReach() && cheminSortie.getTarget().equals(q);
        if (dernier && complet && t.position().distanceToSqr(c) < 4) { t.getMoveControl().setWantedPosition(c.x, c.y, c.z, v); return; }
        boolean fini = t.getNavigation().isDone();
        if (fini && complet && auBout(t, cheminSortie) && age % 20 != 0) { t.getMoveControl().setWantedPosition(c.x, c.y, c.z, v); return; }
        // Minecraft ne cherche pas de chemin en l'air (en plein saut d'une marche) : il garde celui qu'il suit.
        if ((age % 20 == 0 || fini && age - sortieDate >= RECALCUL) && (t.onGround() || t.isInWater())) {
            sortieDate = age;
            Path ch = chercher(t, q, 0, Charge.Origine.SORTIE);
            // Pas de chemin complet jusqu'au point visé : le point suivant de la route, tout près (jamais un chemin partiel).
            if ((ch == null || !ch.canReach()) && cible > indexSortie) ch = chercher(t, sortie.get(indexSortie), 0, Charge.Origine.SORTIE);
            if (ch == null) return; // rien de trouvé : il ne lâche pas le chemin en cours
            cheminSortie = ch;
            if (ch.canReach()) t.getNavigation().moveTo(ch, v);
            else t.getNavigation().stop(); // un chemin partiel : il ne le suit pas
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
            // Sous la marche, un trou de plus de 3 blocs (un puits), ou de la lave ou du magma au fond : il ne la creuse pas.
            boolean appui = false;
            for (int k = 1; k <= 4 && !appui; k++) {
                BlockPos q = marche.below(k);
                BlockState sol = l.getBlockState(q);
                if (sol.getFluidState().is(FluidTags.LAVA) || sol.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) break;
                appui = Monde.solide(l, q) || sol.getFluidState().is(FluidTags.WATER);
            }
            // Du gravier ou du sable sur un bloc de sa propre colonne lui tomberait dessus ; dans la marche, il attend qu'il
            // soit tombé (il le recassera de côté) avant d'y monter.
            boolean surLui = blocs.stream().anyMatch(b -> b.getX() == p.getX() && b.getZ() == p.getZ() && !Monde.traversable(l, b) && Monde.tombe(l, b.above()));
            if (appui && !surLui && !Monde.eboulement(l, marche, 2) && blocs.stream().allMatch(b -> creusable(l, b) && (Monde.traversable(l, b) || Lieux.peutCasser(t, b))) && (aCreuserIci || Monde.debout(l, marche))) {
                monte = i < dirs.size();
                sens = d;
                aCasser.clear();
                for (BlockPos b : blocs) if (!Monde.traversable(l, b)) aCasser.add(b);
                aCreuser = aCasser.isEmpty() ? null : aCasser.get(0);
                if (aCreuser == null) monter(t);
                else { progres = 0; echecs = 0; Cerveau.dire(t, "creuse", 600, "Je suis bloqué : je creuse un passage."); }
                return;
            }
        }
    }

    /**
     * On peut le casser sans risque : pas de construction, pas d'incassable, pas une pièce de structure (puits de mine, ruines :
     * cahier 3.3), et pas de lave à côté (l'eau, il la traverse à la nage).
     */
    static boolean creusable(Level l, BlockPos b) {
        if (!l.isLoaded(b)) return false;
        BlockState st = l.getBlockState(b);
        if (Monde.traversable(l, b)) return true;
        if (st.getDestroySpeed(l, b) < 0 || Monde.construit(l, b) || !st.getFluidState().isEmpty() || Monde.structure(l, b) != null) return false;
        for (Direction d : Direction.values()) if (l.getBlockState(b.relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
        return true;
    }

    private void creuser(Travailleur t) {
        Level l = t.level();
        if (!creusable(l, aCreuser)) { aCreuser = null; aCasser.clear(); reprise = false; return; }
        if (reprise && occupe(t, aCreuser)) { suivantACreuser(t); return; } // quelqu'un d'autre se tient dessus : il le laisse
        BlockState st = l.getBlockState(aCreuser);
        if (Monde.traversable(l, aCreuser) || st.isAir()) { suivantACreuser(t); return; }
        meilleurOutil(t, st, aCreuser);
        t.getNavigation().stop();
        t.getLookControl().setLookAt(Vec3.atCenterOf(aCreuser));
        if ((int) progres % 5 == 0) { t.swing(InteractionHand.MAIN_HAND); Sons.coup(t, aCreuser, st); }
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
        if (aCreuser == null && !reprise) monter(t);
        if (aCreuser == null) reprise = false;
    }

    /** La paroi devant lui, vers le but, n'est pas vide et il n'a rien pour la récolter (de la pierre, sans pioche). */
    private boolean sansOutilPourLaParoi(Travailleur t) {
        BlockPos q = t.blockPosition().relative(Direction.getNearest(but.x - t.getX(), 0, but.z - t.getZ())).above();
        return !Monde.traversable(t.level(), q) && !recolte(t, t.level().getBlockState(q));
    }

    /** Le passage est dégagé : il monte sur la marche suivante (ou avance d'un pas en galerie). */
    private void monter(Travailleur t) {
        BlockPos vers = monte ? t.blockPosition().relative(sens).above() : t.blockPosition().relative(sens);
        if (Monde.eboulement(t.level(), vers, 2)) { etape = null; return; } // du gravier y tombe : il le recreusera d'abord
        montee = vers;
        monteeFin = ageCourant + 60;
        echecs = 0; // il avance : ce n'est pas un échec
        if (monte) t.getJumpControl().jump();
        t.getMoveControl().setWantedPosition(montee.getX() + 0.5, montee.getY(), montee.getZ() + 0.5, 1.0);
        etape = null;
    }

    // --- Poser des blocs pour sortir : un pilier sous ses pieds, un petit pont ------------------------

    /** Les blocs banals qu'il veut bien poser pour se tirer d'affaire (ni gravier ni sable : ils tombent). */
    static final Set<Item> BLOCS_CHANTIER = Set.copyOf(List.of(Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.NETHERRACK));
    /** Il ne monte pas plus haut que ça sur ses blocs ; affaibli (vie de 10 ou moins), 3 blocs, comme pour sauter d'un à-pic. */
    static final int PILIER_MAX = 8, PILIER_AFFAIBLI = 3;
    /** Au plus tant de blocs posés pour un même trajet. */
    static final int POSES_MAX = 24;
    /** Où ira le prochain bloc du pilier (sous lui, une fois en l'air) ; null s'il ne monte pas. */
    private BlockPos pilier;
    private int pilierHaut, poseFin, posesFaites;
    /** Où il sort, une fois en haut du pilier ou au bout du pont ; d'où il a commencé à poser. */
    private BlockPos issue, departPose;
    /** Les blocs du pont qui restent à poser. */
    private final List<BlockPos> pont = new ArrayList<>();
    /** Les blocs posés pendant ce trajet : une fois sorti, il reprend ceux qu'il a à portée de main. */
    private final List<BlockPos> poses = new ArrayList<>();
    /** Les endroits d'où une pose n'a rien donné : il n'y recommence pas. */
    private final Set<BlockPos> posesRatees = new HashSet<>();
    /** Il casse ses propres blocs pour les reprendre (le creusage, sans monter ensuite). */
    private boolean reprise;
    /** La case du sac d'où vient le bloc de ce saut (-1 : pas encore cherchée). */
    private int caseSaut = -1;

    /** Un ouvrier qui doit attendre là (gardé, prisonnier) ne creuse ni ne pose de blocs pour s'en aller. */
    static boolean evasionPermise(Travailleur t) { return t.mode != Travailleur.Mode.ATTENDRE; }

    static boolean blocDeChantier(ItemStack s) { return !s.isEmpty() && (BLOCS_CHANTIER.contains(s.getItem()) || s.is(ItemTags.PLANKS)); }

    /**
     * La case de son sac d'où prendre un bloc à poser, -1 s'il n'en a pas. Ce que sa mission demande n'y passe pas : de cet
     * objet, il garde au moins le nombre demandé. D'abord un bloc qu'il saura reprendre ; un autre seulement s'il n'en a pas
     * (en dernier recours : la montée du plan A3, qui passe avant le creusage, n'en part pas sans bloc reprenable).
     */
    static int caseDeChantier(Travailleur t) { int i = caseDeChantier(t, false, true); return i >= 0 ? i : caseDeChantier(t, false, false); }

    /** Pareil ; {@code sansBois} : pas de planches (pour boucher la lave, elles brûleraient). */
    static int caseDeChantier(Travailleur t, boolean sansBois) { return caseDeChantier(t, sansBois, false); }

    /** {@code reprenable} : seulement des blocs qu'il saura reprendre une fois posés (pas de pierre sans pioche : elle serait perdue). */
    static int caseDeChantier(Travailleur t, boolean sansBois, boolean reprenable) {
        Metier.Commande c = Metier.commande(t);
        Actions.Filtre f = c == null ? null : c.objet;
        int garder = f == null ? 0 : t.missionActive().nombre, compte = 0, caseMission = -1;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!blocDeChantier(s) || sansBois && s.is(ItemTags.PLANKS) || reprenable && !reprenable(t, s)) continue;
            if (f == null || !f.objet(s)) return i;
            compte += s.getCount();
            caseMission = i;
        }
        return compte > garder ? caseMission : -1;
    }

    /** Ce bloc, une fois posé, il saura le reprendre (le récolter avec ce qu'il a). */
    static boolean reprenable(Travailleur t, ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.BlockItem b && recolte(t, b.getBlock().defaultBlockState());
    }

    /** Combien de blocs il peut poser pour monter ou franchir un trou (sans toucher à ce que sa mission demande). */
    static int blocsDeChantier(Travailleur t) {
        Metier.Commande c = Metier.commande(t);
        Actions.Filtre f = c == null ? null : c.objet;
        int libres = 0, mission = 0;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!blocDeChantier(s)) continue;
            if (f != null && f.objet(s)) mission += s.getCount(); else libres += s.getCount();
        }
        return libres + (f == null ? 0 : Math.max(0, mission - t.missionActive().nombre));
    }

    /**
     * Pose en q un bloc de chantier de son sac (la case {@code i}, de {@link #caseDeChantier}), comme un joueur ; seulement
     * à une place vide (jamais dans la lave ni dans l'eau).
     */
    static boolean poserBloc(Travailleur t, BlockPos q, int i) {
        BlockState st = t.level().getBlockState(q);
        if (i < 0 || !blocDeChantier(t.inventaire.getItem(i)) || !st.canBeReplaced() || !st.getFluidState().isEmpty()) return false;
        return Actions.placer(t, q, t.inventaire.getItem(i)) == null;
    }

    /**
     * Au fond d'un trou ou dans un enclos qu'il ne peut pas creuser, avec des blocs de chantier dans son sac : il monte en
     * pilier (comme un joueur : il saute et pose un bloc sous lui) jusqu'à pouvoir marcher sur un bord sûr ; sinon, devant
     * un vide d'un ou deux blocs, il pose un petit pont jusqu'à l'autre bord. Rien vers la lave, le feu ou le vide ; pas
     * deux fois d'un endroit où ça n'a rien donné, et au plus {@link #POSES_MAX} blocs par trajet.
     */
    private boolean commencerAPoser(Travailleur t) {
        Level l = t.level();
        BlockPos p = t.blockPosition();
        int blocs = Math.min(blocsDeChantier(t), POSES_MAX - posesFaites);
        if (blocs <= 0 || posesRatees.contains(p) || !t.onGround() || t.isInWater() || !Monde.solide(l, p.below())) return false;
        Vec3 pos = t.position();
        List<Direction> dirs = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        dirs.sort(Comparator.comparingDouble(d -> -(d.getStepX() * (but.x - pos.x) + d.getStepZ() * (but.z - pos.z))));
        // Le pilier : la plus petite hauteur d'où il peut marcher sur un bord, s'il a la place de sauter jusque-là. Il faut
        // que la place de ses pieds soit vide : debout sur une dalle, un chemin, une terre labourée, une torche, le bloc n'irait pas.
        BlockState pieds = l.getBlockState(p);
        int haut = pieds.canBeReplaced() && pieds.getFluidState().isEmpty() ? Math.min(blocs, t.getHealth() > 10 ? PILIER_MAX : PILIER_AFFAIBLI) : 0;
        for (int k = 1; k <= haut; k++) {
            if (!Monde.libre(l, p.above(k)) || !Monde.libre(l, p.above(k + 1))) break; // un plafond, un liquide
            for (Direction d : dirs) {
                BlockPos bord = p.above(k).relative(d);
                if (Monde.debout(l, bord) && Monde.solide(l, bord.below()) && !Monde.mortel(l, bord)) {
                    pilier = p;
                    pilierHaut = p.getY() + k;
                    return commencer(t, p, bord, "pilier", "Je suis coincé : je monte sur des blocs pour sortir.");
                }
            }
        }
        // Le pont : à deux ou trois pas, de l'autre côté d'un vide d'un ou deux blocs, un sol sûr à sa hauteur.
        for (Direction d : dirs)
            for (int n = 2; n <= 3; n++) {
                BlockPos arrivee = p.relative(d, n);
                if (!Monde.debout(l, arrivee) || !Monde.solide(l, arrivee.below()) || Monde.mortel(l, arrivee)) continue;
                List<BlockPos> blocsPont = new ArrayList<>();
                boolean passe = true;
                for (int i = 1; i < n && passe; i++) {
                    BlockPos q = p.relative(d, i);
                    passe = Monde.libre(l, q) && Monde.libre(l, q.above()) && (l.getBlockState(q.below()).isAir() || Monde.solide(l, q.below()));
                    if (l.getBlockState(q.below()).isAir()) blocsPont.add(q.below());
                }
                if (!passe || blocsPont.isEmpty() || blocsPont.size() > blocs) continue;
                pont.clear();
                pont.addAll(blocsPont);
                return commencer(t, p, arrivee, "pont", "Je pose quelques blocs pour franchir ce trou.");
            }
        return false;
    }

    private boolean commencer(Travailleur t, BlockPos depart, BlockPos sortie, String cle, String texte) {
        departPose = depart;
        issue = sortie;
        poseFin = ageCourant + 100;
        Cerveau.dire(t, cle, 600, texte);
        return true;
    }

    /** Un pas du pilier ou du pont (à chaque tick, sans recherche de chemin). */
    private void poser(Travailleur t, int age) {
        t.getNavigation().stop();
        if (age > poseFin) { abandonner(t); return; } // ça n'avance plus (poussé de côté, bloc qui ne tient pas)
        if (!pont.isEmpty()) {
            t.diag = "pose un pont";
            BlockPos q = pont.get(0);
            t.getLookControl().setLookAt(Vec3.atCenterOf(q));
            if (age % 5 != 0) return;
            if (!poserBloc(t, q, caseDeChantier(t))) { abandonner(t); return; }
            poses.add(q);
            posesFaites++;
            pont.remove(0);
            poseFin = age + 100;
            if (pont.isEmpty()) sortirSurLeBord(age);
            return;
        }
        t.diag = "monte sur des blocs (" + (pilierHaut - pilier.getY()) + " à poser)";
        if (pilier.getY() >= pilierHaut) { if (t.onGround()) sortirSurLeBord(age); return; }
        // En l'air, les pieds au-dessus de la place : il pose le bloc sous lui (la case du sac, cherchée une fois par saut).
        if (t.getY() >= pilier.getY() + 1) {
            if (caseSaut < 0) caseSaut = caseDeChantier(t);
            if (caseSaut < 0) { abandonner(t); return; }
            if (poserBloc(t, pilier, caseSaut)) {
                poses.add(pilier);
                posesFaites++;
                pilier = pilier.above();
                poseFin = age + 100;
                caseSaut = -1;
                return;
            }
        } else caseSaut = -1;
        double cx = pilier.getX() + 0.5, cz = pilier.getZ() + 0.5, dx = t.getX() - cx, dz = t.getZ() - cz;
        t.getMoveControl().setWantedPosition(cx, t.getY(), cz, 0.6);
        if (t.onGround() && dx * dx + dz * dz < 0.09) t.getJumpControl().jump();
    }

    /** La pose n'a rien donné : il n'y recommence pas d'ici, attend un peu plus à chaque fois, et finit par se dire coincé. */
    private void abandonner(Travailleur t) {
        pilier = null;
        pont.clear();
        if (departPose != null) posesRatees.add(departPose);
        prochainChoix = t.level().getGameTime() + Math.min(DELAI_MAX, DELAI_APRES_ECHEC << Math.min(4, choixRates++));
        if (++echecs >= 3) { bloque = true; t.diag = "coincé"; }
        etape = null;
    }

    /** En haut du pilier ou au bout du pont : il marche jusqu'au bord, puis reprend son chemin. */
    private void sortirSurLeBord(int age) {
        pilier = null;
        montee = issue;
        monteeFin = age + 60;
        echecs = 0;
        directRate = 0;
        etape = null;
    }

    /**
     * Sorti sur le bord : il reprend les blocs qu'il a posés, du dernier au premier, s'il les a à portée de main et sait
     * les récolter (une pioche pour la pierre). Sinon, tant pis : ils restent.
     */
    private void reprendre(Travailleur t) {
        Level l = t.level();
        List<BlockPos> liste = new ArrayList<>();
        for (int i = poses.size() - 1; i >= 0; i--) {
            BlockPos q = poses.get(i);
            BlockState st = l.getBlockState(q);
            if (blocDeChantier(new ItemStack(st.getBlock().asItem())) && Actions.aPortee(t, q) && recolte(t, st) && creusable(l, q)
                    && !q.equals(t.blockPosition().below())) liste.add(q);
        }
        poses.clear();
        if (liste.isEmpty()) return;
        aCasser.clear();
        aCasser.addAll(liste);
        aCreuser = aCasser.get(0);
        progres = 0;
        reprise = true;
    }

    /** Une autre créature que lui (un joueur, un ouvrier, une bête) se tient sur ce bloc. */
    static boolean occupe(Travailleur t, BlockPos q) {
        return !t.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(q.above()).inflate(-0.01, 0, -0.01), e -> e != t).isEmpty();
    }

    /** Il a de quoi récolter ce bloc (un bloc qui ne demande pas d'outil, ou le bon outil dans la main ou dans son sac). */
    static boolean recolte(Travailleur t, BlockState st) {
        if (!st.requiresCorrectToolForDrops() || t.getMainHandItem().isCorrectToolForDrops(st)) return true;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (t.inventaire.getItem(i).isCorrectToolForDrops(st)) return true;
        return false;
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
        finChoix();
        oublierPlan();
        bateauBloque = 0;
        barreFin = 0;
        Cerveau.dire(t, "bateau", 600, "Je prends le bateau pour traverser.");
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
        oublierPlan();
        chemin = null;
        directRate = 0;
        finChoix();
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

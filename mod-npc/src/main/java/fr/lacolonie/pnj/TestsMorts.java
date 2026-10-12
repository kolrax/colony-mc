package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lot 1 « plus de morts bêtes » (cahier : « aucune mort de PNJ due à un bug », le mineur doit « boucher l'eau et la
 * lave ») : des ouvriers mis devant une chute, une longue nage, une galerie inondée, de la lave, du gravier, la faim.
 * <p>
 * Critère : il reste vivant, jamais « à terre », garde au moins la moitié de sa vie quand c'est raisonnable, et ne subit
 * aucun des dégâts qu'un ouvrier prudent ne prend jamais (lave, feu, noyade, étouffement selon le test). Chaque test
 * relève à chaque tick la vie la plus basse, l'air le plus bas, les dégâts subis (« lava », « inWall », « drown »,
 * « fall »…) et les actions jouées, pour dire de quoi il serait mort. Chaque fin de test l'écrit dans le journal
 * (« [morts] »).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMorts {
    static final String[] LAVE = {"lava", "inFire", "onFire"};
    static final String[] NOYADE = {"drown"};
    static final String[] ETOUFFE = {"inWall"};

    /** Ce qui lui arrive pendant le test, relevé à chaque tick. */
    static final class Suivi {
        final Travailleur t;
        final GameTestHelper h;
        float vieMin;
        int airMin;
        boolean aTerre, mort, mouille;
        final Set<String> degats = new LinkedHashSet<>();
        String premier = "";
        final List<String> actions = new ArrayList<>();
        Actions.Action derniere;

        Suivi(GameTestHelper h, Travailleur t) { this.h = h; this.t = t; vieMin = t.getHealth(); airMin = t.getAirSupply(); }

        String texte() {
            return "vie min " + vieMin + "/" + t.getMaxHealth() + ", air min " + airMin + ", dégâts " + degats
                    + (premier.isEmpty() ? "" : " (premier : " + premier + ")") + (aTerre ? ", À TERRE" : "") + (mort ? ", MORT" : "")
                    + ", vie " + t.getHealth() + ", en " + rel(h, t.blockPosition()).toShortString() + ", action "
                    + (t.action == null ? "-" : t.action.nom) + ", résultat " + t.resultat + ", " + t.diag + " ; actions " + actions;
        }

        /** Vivant, jamais à terre, au moins {@code part} de sa vie (0 : pas de seuil), et aucun des dégâts {@code interdits}. */
        void verifier(double part, String... interdits) {
            h.assertFalse(mort || aTerre, "il ne doit ni mourir ni tomber à terre (" + texte() + ")");
            for (String d : interdits) h.assertFalse(degats.contains(d), "il ne doit pas subir « " + d + " » (" + texte() + ")");
            h.assertTrue(vieMin >= t.getMaxHealth() * part, "il a perdu trop de vie (" + texte() + ")");
        }
    }

    /** Position dans le test (sans rotation). */
    static BlockPos rel(GameTestHelper h, BlockPos p) { return p.subtract(h.absolutePos(BlockPos.ZERO)); }

    static Suivi suivre(GameTestHelper h, Travailleur t) {
        Suivi s = new Suivi(h, t);
        h.onEachTick(() -> {
            if (t.getRemovalReason() == Entity.RemovalReason.DISCARDED) return; // fin du test
            s.vieMin = Math.min(s.vieMin, t.getHealth());
            s.airMin = Math.min(s.airMin, t.getAirSupply());
            if (t.isInWater()) s.mouille = true;
            DamageSource d = t.getLastDamageSource();
            if (d != null && s.degats.add(d.getMsgId()) && s.premier.isEmpty())
                s.premier = d.getMsgId() + " en " + rel(h, t.blockPosition()).toShortString() + " au tick " + h.getTick()
                        + ", action " + (t.action == null ? "-" : t.action.nom);
            if (t.aTerre) s.aTerre = true;
            if (!t.isAlive()) s.mort = true;
            if (t.action != s.derniere) {
                if (s.derniere != null && s.actions.size() < 30)
                    s.actions.add(s.derniere.nom + (s.derniere.raison.isEmpty() ? "" : ":" + s.derniere.raison)
                            + (s.derniere instanceof Actions.Casser c ? "@" + rel(h, c.pos).toShortString() : ""));
                s.derniere = t.action;
            }
        });
        return s;
    }

    /** Écrit le relevé dans le journal, retire le PNJ, vérifie. */
    static void finir(GameTestHelper h, String nom, Travailleur t, Suivi s, double part, String... interdits) {
        ColoniePnj.LOG.info("[morts] {} : {}", nom, s.texte());
        t.discard();
        s.verifier(part, interdits);
        h.succeed();
    }

    /** Un ouvrier ★, le plus courant : 20 points de vie, travail à 0,8 (les aides des tests le font ★★★★★). */
    static void debutant(Travailleur t) {
        t.niveau = 1;
        t.appliquerNiveau();
    }

    /** Remplit le pavé x0..23, z0..23, y1..haut de pierre. */
    static void roche(GameTestHelper h, int haut) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= haut; y++) h.setBlock(x, y, z, Blocks.STONE);
    }

    /** Une galerie de mine (2 de haut) le long de x, de x1 à x2, en z = 12, sol en y = 1. */
    static void galerie(GameTestHelper h, int x1, int x2) {
        for (int x = x1; x <= x2; x++) for (int y = 2; y <= 3; y++) h.setBlock(x, y, 12, Blocks.AIR);
    }

    /**
     * Un filon de charbon dans les parois de la galerie, de 2 à 3 blocs de part et d'autre de x : plus loin de lui que
     * le bloc au-dessus de sa tête (cassé en premier), il le garde occupé quelques instants sur place ensuite.
     */
    static void filon(GameTestHelper h, int x) {
        for (int dx : new int[]{-3, -2, 2, 3}) { h.setBlock(x + dx, 2, 11, Blocks.COAL_ORE); h.setBlock(x + dx, 3, 13, Blocks.COAL_ORE); }
    }

    /** Un mineur au travail (pioche en fer, commande de charbon), bureau au bout de la galerie. */
    static Travailleur mineur(GameTestHelper h, int x, int y, int z) {
        Travailleur t = TestsJeu.ouvrier(h, "mineur", x, y, z, new ItemStack(Items.IRON_PICKAXE), new BlockPos(1, 2, 12));
        debutant(t);
        t.mission("charbon", "stock", 64);
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 32));
        return t;
    }

    // --- 1. Chutes -------------------------------------------------------------------------------------

    /**
     * En haut d'une falaise à pic de 11 blocs, sans descente ; son bureau en bas, à 14 blocs. Il ne doit pas se jeter
     * dans le vide (au pire il reste en haut, coincé). Garde-fou : le saut d'à-pic est borné à 6 blocs.
     */
    @GameTest(template = "grand", timeoutTicks = 1100, skyAccess = true, batch = "morts")
    public static void falaiseSansDescente(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 0; x <= 9; x++) for (int z = 0; z < 24; z++) for (int y = 2; y <= 12; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 4, 13, 12, ItemStack.EMPTY, new BlockPos(18, 2, 12));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1000, () -> finir(h, "falaiseSansDescente", t, s, 0.5));
    }

    /**
     * Une falaise de 11 blocs en deux à-pics (6 puis 5) : il peut descendre en sautant (3 + 2 points de vie), comme un
     * joueur. Il arrive en bas sans perdre plus de la moitié de sa vie.
     */
    @GameTest(template = "grand", timeoutTicks = 1100, skyAccess = true, batch = "morts")
    public static void falaiseEnDeuxAPics(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 0; x <= 9; x++) for (int z = 0; z < 24; z++) for (int y = 2; y <= (x <= 7 ? 12 : 6); y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 4, 13, 12, ItemStack.EMPTY, new BlockPos(18, 2, 12));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1000, () -> {
            boolean arrive = t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16;
            h.assertTrue(arrive, "il doit être descendu jusqu'au bureau (" + s.texte() + ")");
            finir(h, "falaiseEnDeuxAPics", t, s, 0.5);
        });
    }

    /**
     * Enfermé dans un enclos de planches de 7 blocs de haut, de la pierre en poche, son bureau dehors : il monte sur un
     * pilier, sort par le haut du mur… et ne doit pas se jeter de 8 blocs de haut pour redescendre.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true, batch = "morts")
    public static void pilierPuisDescente(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) for (int y = 4; y <= 10; y++) h.setBlock(12 + dx, y, 12 + dz, Blocks.OAK_PLANKS);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        debutant(t);
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1100, () -> finir(h, "pilierPuisDescente", t, s, 0.5));
    }

    // --- 2. Noyade -------------------------------------------------------------------------------------

    /** Un lac de 61 blocs de large (3 de fond) entre lui et son bureau, sans bateau : il nage près d'une minute sans se noyer. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "morts")
    public static void longueTraverseeALaNage(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = TestsJeu.sol(h, 0, 101000);
        for (int x = 6; x <= 66; x++)
            for (int z = -40; z <= 40; z++) {
                BlockPos s = TestsJeu.sol(h, x, 101000 + z);
                for (int k = 1; k <= 3; k++) l.setBlockAndUpdate(s.below(k), Blocks.WATER.defaultBlockState());
            }
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(TestsJeu.sol(h, 70, 101000)), ItemStack.EMPTY);
        debutant(t);
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1150, () -> {
            h.assertTrue(s.mouille, "il devait nager (" + s.texte() + ")");
            finir(h, "longueTraverseeALaNage (à " + TestsJeu.distance(t, bureau) + " blocs du bureau)", t, s, 0.5, NOYADE);
        });
    }

    /**
     * Une galerie entièrement inondée (en U, une vingtaine de blocs sous l'eau sans air) entre deux salles sèches : il ne
     * doit pas s'y noyer (soit il passe à temps, soit il n'y entre pas).
     */
    @GameTest(template = "grand", timeoutTicks = 1100, batch = "morts")
    public static void galerieInondee(GameTestHelper h) {
        roche(h, 9);
        for (int y = 6; y <= 7; y++) {
            for (int x = 1; x <= 4; x++) h.setBlock(x, y, 12, Blocks.AIR);
            for (int x = 19; x <= 22; x++) h.setBlock(x, y, 12, Blocks.AIR);
        }
        for (int y = 3; y <= 5; y++) { h.setBlock(4, y, 12, Blocks.WATER); h.setBlock(19, y, 12, Blocks.WATER); }
        for (int x = 5; x <= 18; x++) for (int y = 3; y <= 4; y++) h.setBlock(x, y, 12, Blocks.WATER);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 6, 12, ItemStack.EMPTY, new BlockPos(22, 6, 12));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1000, () -> finir(h, "galerieInondee", t, s, 0.5, NOYADE));
    }

    /**
     * Un filon de charbon dans sa galerie, dont un bloc au plafond sous une poche d'eau : en le cassant, l'eau lui tombe
     * dessus. Il ne doit pas s'y noyer (le cahier : il bouche l'eau).
     */
    @GameTest(template = "grand", timeoutTicks = 800, batch = "morts")
    public static void minePercePocheDEau(GameTestHelper h) {
        roche(h, 10);
        galerie(h, 1, 14);
        filon(h, 7);
        h.setBlock(7, 4, 12, Blocks.COAL_ORE);
        for (int x = 6; x <= 8; x++) for (int z = 11; z <= 13; z++) for (int y = 5; y <= 7; y++) h.setBlock(x, y, z, Blocks.WATER);
        Travailleur t = mineur(h, 7, 2, 12);
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finir(h, "minePercePocheDEau", t, s, 0.5, NOYADE));
    }

    /**
     * Des filons de charbon au fond d'une mare de 3 blocs de profondeur (2 au-dessus du minerai), au bord de laquelle il travaille : il ne doit
     * pas se noyer en allant les chercher.
     */
    @GameTest(template = "grand", timeoutTicks = 1100, skyAccess = true, batch = "morts")
    public static void filonAuFondDUneMare(GameTestHelper h) {
        roche(h, 6);
        for (int x = 9; x <= 15; x++) for (int z = 9; z <= 15; z++) for (int y = 4; y <= 6; y++) h.setBlock(x, y, z, Blocks.WATER);
        for (int x = 10; x <= 12; x++) for (int z = 11; z <= 13; z++) h.setBlock(x, 4, z, Blocks.COAL_ORE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 8, 7, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(4, 7, 12));
        debutant(t);
        t.mission("charbon", "stock", 64);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1000, () -> finir(h, "filonAuFondDUneMare", t, s, 0.5, NOYADE));
    }

    // --- 3. Lave ---------------------------------------------------------------------------------------

    /**
     * Un filon de charbon dans sa galerie, dont un bloc au plafond, juste au-dessus de lui, sous de la lave : en le
     * cassant, la lave coule dans la galerie. Il ne doit pas se brûler (le cahier : il bouche la lave).
     */
    @GameTest(template = "grand", timeoutTicks = 800, batch = "morts")
    public static void filonSousLaLave(GameTestHelper h) {
        roche(h, 9);
        galerie(h, 1, 14);
        filon(h, 7);
        h.setBlock(7, 4, 12, Blocks.COAL_ORE);
        h.setBlock(7, 5, 12, Blocks.LAVA);
        Travailleur t = mineur(h, 7, 2, 12);
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finir(h, "filonSousLaLave", t, s, 0.5, LAVE));
    }

    /** Un filon de charbon au fond de la galerie, un lac de lave juste derrière : il ne doit pas se brûler en le cassant. */
    @GameTest(template = "grand", timeoutTicks = 800, batch = "morts")
    public static void filonDevantUnLacDeLave(GameTestHelper h) {
        roche(h, 9);
        galerie(h, 1, 10);
        filon(h, 9);
        h.setBlock(11, 2, 12, Blocks.COAL_ORE);
        h.setBlock(11, 3, 12, Blocks.COAL_ORE);
        for (int x = 12; x <= 14; x++) for (int z = 11; z <= 13; z++) for (int y = 2; y <= 3; y++) h.setBlock(x, y, z, Blocks.LAVA);
        Travailleur t = mineur(h, 9, 2, 12);
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finir(h, "filonDevantUnLacDeLave", t, s, 0.5, LAVE));
    }

    /** Le bûcheron abat un arbre dont le pied touche deux mares de lave : il ne doit ni s'y brûler ni y tomber. */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true, batch = "morts")
    public static void bucheronPresDeLaLave(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
        for (int y = 2; y <= 6; y++) h.setBlock(12, y, 12, Blocks.OAK_LOG);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 5; y <= 7; y++)
            if (!(dx == 0 && dz == 0 && y <= 6) && Math.abs(dx) + Math.abs(dz) <= 3) h.setBlock(12 + dx, y, 12 + dz, Blocks.OAK_LEAVES);
        h.setBlock(11, 1, 12, Blocks.LAVA);
        h.setBlock(12, 1, 13, Blocks.LAVA);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 6, 2, 12, new ItemStack(Items.IRON_AXE), new BlockPos(4, 2, 4));
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1100, () -> finir(h, "bucheronPresDeLaLave", t, s, 0.5, LAVE));
    }

    // --- 4. Faim ---------------------------------------------------------------------------------------

    /**
     * Affamé (faim 0, 4 points de vie), rien à manger au poste, son chef absent : la faim ne le tue pas (elle s'arrête à
     * un point de vie) et il ne tombe pas à terre. Garde-fou.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, batch = "morts")
    public static void affameSansRienAManger(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 2, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 2, 4));
        debutant(t);
        t.faim = 0;
        t.setHealth(4);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1100, () -> finir(h, "affameSansRienAManger", t, s, 0));
    }

    // --- 5. Asphyxie -----------------------------------------------------------------------------------

    /**
     * Un filon de charbon dans sa galerie, dont un bloc au plafond, juste au-dessus de lui, sous une colonne de gravier :
     * en le cassant, le gravier l'ensevelit. Il ne doit pas étouffer.
     */
    @GameTest(template = "grand", timeoutTicks = 800, batch = "morts")
    public static void filonSousDuGravier(GameTestHelper h) {
        roche(h, 14);
        galerie(h, 1, 14);
        filon(h, 7);
        h.setBlock(7, 4, 12, Blocks.COAL_ORE);
        for (int y = 5; y <= 14; y++) h.setBlock(7, y, 12, Blocks.GRAVEL);
        Travailleur t = mineur(h, 7, 2, 12);
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finir(h, "filonSousDuGravier", t, s, 0.5, ETOUFFE));
    }

    /** Pareil avec du sable. */
    @GameTest(template = "grand", timeoutTicks = 800, batch = "morts")
    public static void filonSousDuSable(GameTestHelper h) {
        roche(h, 14);
        galerie(h, 1, 14);
        filon(h, 7);
        h.setBlock(7, 4, 12, Blocks.COAL_ORE);
        for (int y = 5; y <= 14; y++) h.setBlock(7, y, 12, Blocks.SAND);
        Travailleur t = mineur(h, 7, 2, 12);
        debutant(t);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finir(h, "filonSousDuSable", t, s, 0.5, ETOUFFE));
    }

    /**
     * Muré sous terre, sous une couche de gravier de 4 blocs : il creuse son escalier vers la surface sans se faire
     * ensevelir.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, batch = "morts")
    public static void creuseSousDuGravier(GameTestHelper h) {
        roche(h, 12);
        for (int x = 4; x <= 20; x++) for (int z = 4; z <= 20; z++) for (int y = 7; y <= 10; y++) h.setBlock(x, y, z, Blocks.GRAVEL);
        for (int y = 4; y <= 5; y++) h.setBlock(12, y, 12, Blocks.AIR);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 13, 3));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1100, () -> finir(h, "creuseSousDuGravier", t, s, 0.5, ETOUFFE));
    }

    // --- 6. Réflexe de survie (tout métier) ---------------------------------------------------------------

    /**
     * Il attend, debout dans un feu (sur de la netherrack, qui ne s'éteint pas) : il ne reste pas à brûler, il en sort
     * aussitôt. Sans le réflexe, l'attente le gardait dans les flammes jusqu'à tomber à terre.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true, batch = "morts")
    public static void sortDuFeu(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(12, 1, 12, Blocks.NETHERRACK);
        h.setBlock(12, 2, 12, Blocks.FIRE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 2, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Attendre(400));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertFalse(rel(h, t.blockPosition()).equals(new BlockPos(12, 2, 12)), "il doit être sorti du feu (" + s.texte() + ")");
            finir(h, "sortDuFeu", t, s, 0.25);
        });
    }

    /**
     * Il attend, enseveli de la tête aux pieds dans du gravier tombé sur lui : il s'en dégage (par un côté libre) au lieu
     * d'étouffer. Sans le réflexe, l'attente le laissait étouffer jusqu'à tomber à terre.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true, batch = "morts")
    public static void sortDuGravier(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 2, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(4, 2, 4));
        debutant(t);
        h.setBlock(12, 2, 12, Blocks.GRAVEL);
        h.setBlock(12, 3, 12, Blocks.GRAVEL);
        Cerveau.lancer(t, new Actions.Attendre(400));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertFalse(t.isInWall(), "il doit s'être dégagé (" + s.texte() + ")");
            finir(h, "sortDuGravier", t, s, 0.5);
        });
    }

    /**
     * Il attend dans un trou de lave d'un bloc, une mare d'eau à 3 pas : il en sort, puis va éteindre ses flammes dans
     * l'eau (sinon le feu de la lave, 15 secondes, l'achevait).
     */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true, batch = "morts")
    public static void sortDeLaLave(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(12, 1, 12, Blocks.LAVA);
        for (int z = 11; z <= 13; z++) h.setBlock(15, 1, z, Blocks.WATER);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 1, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Attendre(500));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 400, () -> {
            h.assertFalse(t.isInLava() || t.isOnFire(), "il doit être sorti de la lave et éteint (" + s.texte() + ")");
            finir(h, "sortDeLaLave", t, s, 0);
        });
    }
}

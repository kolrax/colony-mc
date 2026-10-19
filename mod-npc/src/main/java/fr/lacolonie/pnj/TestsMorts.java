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
        /** Où il est, tous les 5 ticks (L : dans la lave), les 6 premières secondes. */
        final List<String> trace = new ArrayList<>();
        Actions.Action derniere;

        Suivi(GameTestHelper h, Travailleur t) { this.h = h; this.t = t; vieMin = t.getHealth(); airMin = t.getAirSupply(); }

        String texte() {
            return "vie min " + vieMin + "/" + t.getMaxHealth() + ", air min " + airMin + ", dégâts " + degats
                    + (premier.isEmpty() ? "" : " (premier : " + premier + ")") + (aTerre ? ", À TERRE" : "") + (mort ? ", MORT" : "")
                    + ", vie " + t.getHealth() + ", en " + rel(h, t.blockPosition()).toShortString() + ", action "
                    + (t.action == null ? "-" : t.action.nom) + ", résultat " + t.resultat + ", " + t.diag + " ; actions " + actions + " ; trace " + trace;
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
            if (h.getTick() % 5 == 0 && s.trace.size() < 24) s.trace.add(String.format(java.util.Locale.ROOT, "%d:%.2f,%.2f,%.2f%s", h.getTick(), t.getX() - h.absolutePos(BlockPos.ZERO).getX(), t.getY() - h.absolutePos(BlockPos.ZERO).getY(), t.getZ() - h.absolutePos(BlockPos.ZERO).getZ(), t.isInLava() ? "L" : ""));
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
     * dans le vide (au pire il reste en haut, coincé). Garde-fou : le saut d'à-pic est borné à 3 blocs (plan E4).
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
     * Une arête de pierre de 9 blocs en deux à-pics vers son bureau (4 puis 5, sur un replat d'un bloc ; de l'autre côté,
     * 9 d'un coup), sans pioche, de la terre en poche. Avant (1.0.21), il sautait chaque à-pic (1 + 2 points de vie). Plan
     * E4 (jamais de chute de plus de 3 blocs) : il pose une marche de terre contre chaque paroi, à portée de main, descend
     * par elle sans se blesser, et reprend sa terre. (Arête de 2 blocs, replat d'un : où qu'il soit, la marche est à portée ;
     * sur un large plateau, sans pioche, il creuserait d'abord sous lui à mains nues.)
     */
    @GameTest(template = "grand", timeoutTicks = 1600, skyAccess = true, batch = "morts")
    public static void falaiseEnDeuxAPics(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 6; x <= 8; x++) for (int z = 0; z < 24; z++) for (int y = 2; y <= (x <= 7 ? 10 : 6); y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 6, 11, 12, new ItemStack(Items.DIRT, 16), new BlockPos(18, 2, 12));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1500, () -> {
            boolean arrive = t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16;
            int terre = t.compter(Actions.Filtre.de("minecraft:dirt"));
            h.assertTrue(arrive, "il doit être descendu jusqu'au bureau (" + s.texte() + ")");
            h.assertTrue(terre == 16, "il doit avoir repris ses marches de terre (" + terre + " terre, " + s.texte() + ")");
            finir(h, "falaiseEnDeuxAPics", t, s, 0.9, "fall");
        });
    }

    /**
     * Plan E4 (« pas de chute de plus de 3 blocs », KOLRAX) : en haut d'une marche de pierre de 5 blocs, son bureau en bas,
     * un escalier taillé dans la paroi à 10 blocs de là. Avant, en pleine vie, il sautait les 5 blocs (2 points de vie ; en
     * montagne, 7 à 22 coups de chute par partie au joueur fantôme). Maintenant, il fait le détour par l'escalier.
     */
    @GameTest(template = "grand", timeoutTicks = 700, skyAccess = true, batch = "morts")
    public static void nesautePasDeCinqBlocs(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 0; x <= 9; x++) for (int z = 0; z < 24; z++) for (int y = 2; y <= 6; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int x = 10; x <= 13; x++) for (int y = 2; y <= 15 - x; y++) h.setBlock(x, y, 2, Blocks.STONE); // l'escalier
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 5, 7, 12, ItemStack.EMPTY, new BlockPos(16, 2, 12));
        debutant(t);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 2));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 600, () -> {
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être descendu jusqu'au bureau (" + s.texte() + ")");
            finir(h, "nesautePasDeCinqBlocs", t, s, 0.9, "fall");
        });
    }

    /**
     * Plan A3 (joueur fantôme, montagne : 5 à 11 chutes de 3,3 à 5,7 blocs par partie, en allant vers le sommet, à la
     * course) : une pente en terrasses vers son bureau, à 59 blocs (il court) ; une bosse d'un bloc au bord d'un à-pic de
     * 3 blocs, puis un à-pic de 3 blocs sur une corniche d'un bloc, une marche plus haut que la terrasse suivante. Sur son
     * élan, il passait par-dessus la bosse ou la corniche et tombait plus bas que prévu. Jamais de dégât de chute.
     */
    @GameTest(template = "vide", timeoutTicks = 1800, batch = "morts")
    public static void terrassesEnCourant(GameTestHelper h) {
        int[] haut = new int[60]; // la hauteur de la pierre au-dessus du sol, colonne par colonne le long de x
        for (int x = 0; x < haut.length; x++)
            haut[x] = x <= 9 ? 14 : x == 10 ? 15 : x <= 15 ? 12 : x == 16 ? 9 : x <= 21 ? 8 : x == 22 ? 9 : x <= 27 ? 6 : x == 28 ? 3 : x <= 33 ? 2 : x == 34 ? 3 : 0;
        descente(h, "terrassesEnCourant", haut, 161000);
    }

    /** Pareil, plus serré : quatre bosses au bord d'à-pics de 3 blocs, à 5 blocs l'une de l'autre, à la course. */
    @GameTest(template = "vide", timeoutTicks = 1800, batch = "morts")
    public static void bossesEnCourant(GameTestHelper h) {
        int[] haut = new int[60];
        for (int x = 0; x < haut.length; x++) {
            int m = x - 7; // une marche de 2 blocs vers le bas tous les 5 blocs, la bosse (un bloc de plus) au bord
            haut[x] = x <= 6 ? 12 : x > 30 ? 0 : x > 26 ? 2 : 12 - 2 * (m / 5 + 1) + (m % 5 == 4 ? 1 : 0);
        }
        descente(h, "bossesEnCourant", haut, 162000);
    }

    /**
     * Une bande de pierre (13 de large) de hauteur {@code haut[x]} sur le monde plat, loin des autres tests ; un ouvrier ★
     * sans outil en haut, son bureau au pied, 59 blocs plus loin. Il doit y arriver sans aucun dégât de chute.
     */
    static void descente(GameTestHelper h, String nom, int[] haut, int dz) {
        ServerLevel l = h.getLevel();
        BlockPos o = TestsJeu.sol(h, 0, dz);
        for (int x = 0; x < haut.length; x++)
            for (int z = -6; z <= 6; z++) for (int k = 0; k < haut[x]; k++) l.setBlockAndUpdate(o.offset(x, k, z), Blocks.STONE.defaultBlockState());
        BlockPos bureau = o.offset(haut.length - 1, 0, 0);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(o.offset(1, haut[1], 0)), ItemStack.EMPTY);
        debutant(t);
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(bureau), 2));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 1700, () -> {
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 16, "il doit être descendu jusqu'au bureau (" + s.texte() + ")");
            finir(h, nom, t, s, 0.9, "fall");
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

    /**
     * E4 (bucheronPresDeLaLave tombait parfois dans la lave en ramassant) : des pousses posées au bord d'une mare de lave,
     * une autre plus loin, à l'écart. Il ramasse celle à l'écart, laisse les autres, et ne pose jamais le pied au bord de la
     * lave en ramassant.
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true, batch = "morts")
    public static void ramasseAuBordDeLaLave(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
        h.setBlock(12, 1, 12, Blocks.LAVA);
        h.setBlock(13, 1, 12, Blocks.LAVA);
        h.setBlock(12, 1, 13, Blocks.LAVA);
        ServerLevel l = h.getLevel();
        for (Vec3 v : new Vec3[]{new Vec3(11.7, 2, 12.5), new Vec3(14.3, 2, 12.5), new Vec3(12.5, 2, 14.3)}) {
            Vec3 a = h.absoluteVec(v);
            l.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(l, a.x, a.y, a.z, new ItemStack(Items.OAK_SAPLING), 0, 0, 0));
        }
        Vec3 a = h.absoluteVec(new Vec3(15.5, 2, 17.5));
        net.minecraft.world.entity.item.ItemEntity loin = new net.minecraft.world.entity.item.ItemEntity(l, a.x, a.y, a.z, new ItemStack(Items.APPLE), 0, 0, 0);
        l.addFreshEntity(loin);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 6, 2, 12, new ItemStack(Items.IRON_AXE), new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Ramasser(10, Actions.Filtre.de("tout")));
        Suivi s = suivre(h, t);
        List<String> auBord = auBord(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finRamassage(h, "ramasseAuBordDeLaLave", t, s, auBord, loin));
    }

    /**
     * E4, le chemin : une pomme au bout d'un couloir taillé dans la roche, loin de toute lave. Le passage court (8 pas) longe
     * un bloc de lave sur 3 cases ; un détour de 48 pas fait le tour. Il prend le détour : jamais le pied au bord de la lave
     * en ramassant. (Avec le coût ordinaire de Minecraft, 8 par case, il passait au bord.)
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true, batch = "morts")
    public static void ramasseSansLongerLaLave(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
        // La roche (x 7..17), creusée : le couloir court en z = 22 (x 8..16), le détour par x = 8, z = 2 et x = 16.
        for (int x = 7; x <= 17; x++) for (int z = 0; z < 24; z++) {
            boolean couloir = z == 22 && x >= 8 && x <= 16 || z == 2 && x >= 8 && x <= 16 || (x == 8 || x == 16) && z >= 2 && z <= 22;
            for (int y = 2; y <= 3; y++) h.setBlock(x, y, z, couloir ? Blocks.AIR : Blocks.STONE);
        }
        h.setBlock(12, 1, 23, Blocks.LAVA); // sous la roche, contre le couloir court : ses cases x 11..13 sont « au bord »
        ServerLevel l = h.getLevel();
        Vec3 a = h.absoluteVec(new Vec3(16.5, 2, 22.5));
        net.minecraft.world.entity.item.ItemEntity pomme = new net.minecraft.world.entity.item.ItemEntity(l, a.x, a.y, a.z, new ItemStack(Items.APPLE), 0, 0, 0);
        l.addFreshEntity(pomme);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 8, 2, 22, new ItemStack(Items.IRON_AXE), new BlockPos(2, 2, 2));
        debutant(t);
        Cerveau.lancer(t, new Actions.Ramasser(24, Actions.Filtre.de("tout")));
        Suivi s = suivre(h, t);
        List<String> auBord = auBord(h, t);
        h.runAtTickTime(h.getTick() + 700, () -> finRamassage(h, "ramasseSansLongerLaLave", t, s, auBord, pomme));
    }

    /** Où il a posé le pied au bord de la lave en ramassant (5 au plus). */
    static List<String> auBord(GameTestHelper h, Travailleur t) {
        List<String> auBord = new ArrayList<>();
        h.onEachTick(() -> {
            if (t.isAlive() && t.action instanceof Actions.Ramasser && Monde.bordDeLave(h.getLevel(), t.blockPosition()) && auBord.size() < 5)
                auBord.add(rel(h, t.blockPosition()).toShortString() + " au tick " + h.getTick());
        });
        return auBord;
    }

    /** Fin d'un test de ramassage près de la lave : jamais au bord en ramassant, la pomme ramassée, ni brûlé ni à terre. */
    static void finRamassage(GameTestHelper h, String nom, Travailleur t, Suivi s, List<String> auBord, net.minecraft.world.entity.item.ItemEntity loin) {
        boolean pomme = !loin.isAlive();
        ColoniePnj.LOG.info("[morts] {} : au bord {}, pomme {} ; {}", nom, auBord, pomme, s.texte());
        t.discard();
        h.assertTrue(auBord.isEmpty(), "en ramassant, il ne doit jamais poser le pied au bord de la lave : " + auBord + " (" + s.texte() + ")");
        h.assertTrue(pomme, "il doit ramasser la pomme à l'écart de la lave (" + s.texte() + ")");
        s.verifier(0.5, LAVE);
        h.succeed();
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

    /**
     * Correction E4 (« pas de lave à côté de lui ») : une source de lave s'ouvre à 3 blocs de lui, sur son sol plat, et
     * coule vers lui. Il s'écarte avant qu'elle n'entre dans sa case : ni brûlure, ni lave. L'ancien code attendait d'y
     * être pour en sortir.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true, batch = "morts")
    public static void secarteDeLaLaveQuiCoule(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 2, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Attendre(500));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 10, () -> h.setBlock(9, 2, 12, Blocks.LAVA));
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertFalse(t.isInLava() || t.isOnFire(), "il ne doit être ni dans la lave ni en feu (" + s.texte() + ")");
            finir(h, "secarteDeLaLaveQuiCoule", t, s, 0.9, LAVE);
        });
    }

    /**
     * Garde-fou de E4 : debout sur une dalle du bas, une source de lave enclose juste à côté, au même niveau. La lave
     * n'entre jamais dans la case de la dalle : il ne fuit pas (sinon il fuyait, revenait et refuyait sans fin).
     */
    @GameTest(template = "grand", timeoutTicks = 300, skyAccess = true, batch = "morts")
    public static void dalleAColeDUneLaveImmobile(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(12, 2, 12, Blocks.STONE_SLAB);
        for (BlockPos p : List.of(new BlockPos(10, 2, 12), new BlockPos(11, 2, 11), new BlockPos(11, 2, 13), new BlockPos(11, 3, 12)))
            h.setBlock(p, Blocks.STONE);
        h.setBlock(11, 2, 12, Blocks.LAVA);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 2, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Attendre(500));
        Suivi s = suivre(h, t);
        boolean[] fuite = {false};
        h.onEachTick(() -> { if (t.action instanceof Actions.Echapper) fuite[0] = true; });
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(fuite[0], "il ne doit pas fuir une lave qui ne peut pas l'atteindre (" + s.texte() + ")");
            finir(h, "dalleAColeDUneLaveImmobile", t, s, 0.9, LAVE);
        });
    }

    /**
     * Enseveli dans du gravier sur un plateau : au nord, un puits de 10 blocs ; au sud, le plateau. Il sort par le sud,
     * jamais en sautant dans le puits. Garde-fou : l'ancien code (premier côté libre, le nord) passait aussi, il ne sortait
     * pas assez loin pour tomber.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true, batch = "morts")
    public static void sortDuGravierDuBonCote(GameTestHelper h) {
        roche(h, 11);
        for (int y = 2; y <= 13; y++) h.setBlock(12, y, 11, Blocks.AIR);
        for (int x : new int[]{11, 13}) for (int y = 12; y <= 13; y++) h.setBlock(x, y, 12, Blocks.STONE);
        Travailleur t = mineur(h, 12, 12, 12);
        Cerveau.lancer(t, new Actions.Attendre(400));
        h.setBlock(12, 12, 12, Blocks.GRAVEL);
        h.setBlock(12, 13, 12, Blocks.GRAVEL);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertFalse(t.isInWall(), "il doit s'être dégagé (" + s.texte() + ")");
            finir(h, "sortDuGravierDuBonCote", t, s, 0.5, "fall");
        });
    }

    /**
     * Debout au milieu d'un feu de 3 x 3 (sur de la netherrack) ; à l'ouest, un pilier de 2 blocs (un rebord à 2 blocs
     * au-dessus de lui, qu'il ne peut pas escalader) ; à deux pas, le sol nu. Il traverse les flammes vers le sol nu.
     * Avant, il visait le haut du pilier, poussait contre lui et restait dans le feu.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true, batch = "morts")
    public static void feuSansEscaladerUnRebord(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 11; x <= 13; x++) for (int z = 11; z <= 13; z++) { h.setBlock(x, 1, z, Blocks.NETHERRACK); h.setBlock(x, 2, z, Blocks.FIRE); }
        h.setBlock(11, 2, 12, Blocks.STONE);
        h.setBlock(11, 3, 12, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 2, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        Cerveau.lancer(t, new Actions.Attendre(400));
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 300, () -> {
            BlockPos r = rel(h, t.blockPosition());
            h.assertFalse(r.getX() >= 11 && r.getX() <= 13 && r.getZ() >= 11 && r.getZ() <= 13, "il doit être sorti du feu (" + s.texte() + ")");
            finir(h, "feuSansEscaladerUnRebord", t, s, 0.25);
        });
    }

    /**
     * Sur une plage : 7 blocs de sable sur la pierre. Le mineur creuse son escalier dans le sable jusqu'à la profondeur
     * demandée, sans s'y ensevelir (le sable qui tombe dans la marche, il le recasse de la marche d'au-dessus).
     */
    @GameTest(template = "grand", timeoutTicks = 3000, batch = "morts")
    public static void escalierDansLeSable(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, y <= 3 ? Blocks.STONE : Blocks.SAND);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(6, 11, 6));
        debutant(t);
        t.mission("charbon", "stock", 64);
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 32));
        t.profondeurForcee = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        t.memoire.putString("chantier", "charbon@" + Mineur.cle(t.bureau) + "/" + t.profondeurForcee);
        t.memoire.putLong("puits", h.absolutePos(new BlockPos(12, 11, 12)).asLong());
        t.memoire.putInt("marche", 0);
        int but = h.absolutePos(new BlockPos(0, 5, 0)).getY();
        Suivi s = suivre(h, t);
        h.succeedWhen(() -> {
            s.verifier(0.5, ETOUFFE);
            h.assertTrue(t.getBlockY() <= but, "il doit être descendu dans le sable (y=" + t.getBlockY() + ", attendu <= " + but + ", "
                    + s.texte() + ", mémoire " + t.memoire + ")");
            ColoniePnj.LOG.info("[morts] escalierDansLeSable : {}", s.texte());
            t.discard();
        });
    }

    /**
     * En feu, de l'eau à 4 pas mais derrière une clôture, un monstre qui le frappe : il ne reste pas à pousser contre la
     * clôture (avant : 10 s sans réagir aux coups), il fuit le monstre.
     */
    @GameTest(template = "grand", timeoutTicks = 300, batch = "morts")
    public static void fuitSiFrappeEnFeu(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int z = 11; z <= 13; z++) h.setBlock(16, 1, z, Blocks.WATER);
        for (int x = 15; x <= 17; x++) for (int z = 10; z <= 14; z++)
            if (x != 16 || z == 10 || z == 14) h.setBlock(x, 2, z, Blocks.OAK_FENCE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 2, 12, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        debutant(t);
        t.temperament = "peureux"; // il fuit les monstres (un calme ou un colérique se défendrait : Bravoure)
        net.minecraft.world.entity.monster.Zombie z = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 9, 2, 12);
        z.setNoAi(true);
        t.igniteForSeconds(10);
        Cerveau.lancer(t, new Actions.Attendre(300));
        Suivi s = suivre(h, t);
        long debut = h.getTick();
        int[] fuite = {-1};
        h.onEachTick(() -> {
            int age = (int) (h.getTick() - debut);
            if (age >= 20 && age % 20 == 0 && t.isAlive()) t.hurt(t.damageSources().mobAttack(z), 1);
            if (fuite[0] < 0 && t.action instanceof Actions.Fuir) fuite[0] = age;
        });
        h.runAtTickTime(debut + 200, () -> {
            h.assertTrue(fuite[0] >= 0 && fuite[0] <= 80, "frappé à partir du tick 20, il doit fuir vite (fuite au tick " + fuite[0] + ", " + s.texte() + ")");
            z.discard();
            finir(h, "fuitSiFrappeEnFeu", t, s, 0);
        });
    }

    /**
     * Un canal d'irrigation au ras du sol, contre la première marche de l'escalier : il ne le bouche pas (ce serait
     * détruire le travail d'un autre) ; son escalier passe ailleurs.
     */
    @GameTest(template = "grand", timeoutTicks = 700, skyAccess = true, batch = "morts")
    public static void canalAuSoleilPasBouche(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int x = 11; x <= 15; x++) h.setBlock(x, 10, 13, Blocks.WATER);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(6, 11, 6));
        debutant(t);
        t.mission("charbon", "stock", 64);
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 32));
        t.profondeurForcee = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        t.memoire.putString("chantier", "charbon@" + Mineur.cle(t.bureau) + "/" + t.profondeurForcee);
        t.memoire.putLong("puits", h.absolutePos(new BlockPos(12, 11, 12)).asLong());
        t.memoire.putInt("marche", 0);
        Suivi s = suivre(h, t);
        h.runAtTickTime(h.getTick() + 600, () -> {
            for (int x = 11; x <= 15; x++) h.assertBlockPresent(Blocks.WATER, x, 10, 13);
            finir(h, "canalAuSoleilPasBouche", t, s, 0.5);
        });
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Le pêcheur (lot 6) : il pêche dans une mare près du poste et porte ses prises au dépôt, prend sa canne au dépôt, écrit
 * s'il n'en a pas, ne pêche pas dans l'eau d'un voisin, fait le tour de la mare plutôt que d'y marcher, et retient le coin
 * que lui montre son chef (« pêche ici »). Chaque test dans son coin du monde plat (coins 160 à 165), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsPecheur {
    /** Une mare d'un bloc de fond, de (x0, z0) à (x1, z1) près de base (le sol du poste : la surface de l'eau est juste dessous). */
    static void mare(ServerLevel l, BlockPos base, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) l.setBlockAndUpdate(base.offset(x, -1, z), Blocks.WATER.defaultBlockState());
    }

    /** Un pêcheur seul, au travail, dans son coin du monde plat, avec cet outil en main ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base, Vec3 decalage, ItemStack outil) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "pecheur", base[0], Vec3.atBottomCenterOf(base[0]).add(decalage), outil);
        t.forcerChunk();
        return t;
    }

    /** Vrai s'il a mis les pieds dans l'eau depuis le début du test (relevé à chaque tick). */
    static boolean[] mouille(GameTestHelper h, Travailleur t) {
        boolean[] m = {false};
        h.onEachTick(() -> { if (t.isAlive() && t.isInWater()) m[0] = true; });
        return m;
    }

    static int auDepot(Travailleur t) {
        Bureau.Entite d = TestsBucheron.depot(t);
        int n = 0;
        for (int k = 0; k < d.depot.getContainerSize(); k++) n += d.depot.getItem(k).getCount();
        return n;
    }

    /** Une mare de 5 x 5 près du poste, la canne en main : il pêche depuis la rive (jamais dans l'eau), et la prise va au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void pecheEtDeposeAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 160, b, new Vec3(-2, 0, 0), new ItemStack(Items.FISHING_ROD));
        ServerLevel l = h.getLevel();
        mare(l, b[0], 4, -2, 8, 2);
        t.memoire.putInt("prises", Pecheur.PRISES - 1); // la prochaine prise remplit son panier : il la porte au dépôt
        boolean[] m = mouille(h, t);
        h.succeedWhen(() -> {
            h.assertFalse(m[0], "il ne doit jamais entrer dans l'eau (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(auDepot(t) > 0 && t.memoire.getInt("prises") == 0, "une prise au dépôt : " + auDepot(t) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Pecheur.CANNE.objet(t.getMainHandItem()) || t.compter(Pecheur.CANNE) == 1, "il garde sa canne");
            t.discard();
        });
    }

    /** Sans canne, une au dépôt : il la prend, la met en main, et trouve son coin de pêche. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void prendLaCanneAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 161, b, new Vec3(-2, 0, 0), ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        mare(l, b[0], 4, -2, 8, 2);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.FISHING_ROD));
        h.succeedWhen(() -> {
            h.assertTrue(Pecheur.CANNE.objet(t.getMainHandItem()) && TestsBucheron.compte(d, Items.FISHING_ROD) == 0,
                    "la canne du dépôt en main (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.action instanceof Pecheur.Pecher, "il part pêcher (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(TestsMineur.lettre(t, "canne_peche"), "pas de lettre : il en a trouvé une");
            t.discard();
        });
    }

    /** Ni canne sur lui, ni au dépôt : il l'écrit à son chef, et ne pêche pas. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void sansCanneUneLettre(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 162, b, new Vec3(-2, 0, 0), ItemStack.EMPTY);
        mare(h.getLevel(), b[0], 4, -2, 8, 2);
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "canne_peche"), "la lettre « canne_peche » (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(t.action instanceof Pecheur.Pecher, "il ne pêche pas sans canne");
            t.discard();
        });
    }

    /**
     * Lot « voisins » : la seule mare autour est plus près du poste d'un autre chef que du sien. Il n'y pêche pas, et écrit
     * qu'il ne trouve pas d'eau.
     */
    @GameTest(template = "vide", timeoutTicks = 800, batch = "voisins")
    public static void laisseLEauDuVoisin(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 163, b, new Vec3(-2, 0, 0), new ItemStack(Items.FISHING_ROD));
        ServerLevel l = h.getLevel();
        UUID voisin = UUID.randomUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(voisin, l, b[0].offset(24, 0, 0));
        TestsMineur.A_DEFAIRE.add(() -> c.postes.remove(voisin));
        mare(l, b[0], 16, -2, 20, 2);
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "sans_eau_peche"), "la lettre « sans_eau_peche » (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(t.memoire.contains("peche") || t.action instanceof Pecheur.Pecher, "il ne pêche pas chez le voisin : " + t.memoire.get("peche"));
            t.discard();
        });
    }

    /**
     * Il arrive de l'autre côté de la mare ; la rive retenue est celle du côté du poste : il en fait le tour sans jamais
     * marcher dans l'eau, et prend un poisson.
     */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void contourneLaMare(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 164, b, new Vec3(10, 0, 0), new ItemStack(Items.FISHING_ROD));
        ServerLevel l = h.getLevel();
        mare(l, b[0], 3, -2, 7, 2);
        boolean[] m = mouille(h, t);
        h.succeedWhen(() -> {
            h.assertFalse(m[0], "il ne doit jamais entrer dans l'eau (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.memoire.getInt("prises") >= 1, "une prise (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.getX() < b[0].getX() + 3, "pêche depuis la rive côté poste : x " + (t.getX() - b[0].getX()));
            t.discard();
        });
    }

    /** Deux mares ; le chef montre la plus lointaine : « Pêche ici ». Il la retient, et va y pêcher (pas dans la plus proche). */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void pecheIci(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 165 * 400, 60000);
        ServerLevel l = h.getLevel();
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur g = TestsJeu.employe(h, "pecheur", base, c.add(-0.5, 0, 2.5), new ItemStack(Items.FISHING_ROD));
        g.chef = chef.getUUID();
        g.prenom = "Gaspard";
        g.forcerChunk();
        Travailleur[] e = {g};
        mare(l, base, 4, -2, 8, 2); // la proche
        mare(l, base, 4, 12, 8, 16); // la lointaine, montrée par le chef
        BlockPos fond = base.offset(6, -2, 14);
        boolean[] m = mouille(h, g);
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(fond.getX() + 0.5, fond.getY() + 0.98, fond.getZ() + 0.5));
                    g.parler(chef, "Pêche ici");
                },
                () -> {
                    Pecheur.Coin k = Pecheur.Coin.de(g.memoire.getCompound("peche"));
                    h.assertTrue(k != null && k.ordre() && k.eau().getZ() - base.getZ() >= 13 && k.eau().getZ() - base.getZ() <= 15,
                            "le coin montré est retenu : " + g.memoire.get("peche") + " (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")");
                },
                () -> h.assertTrue(g.action instanceof Pecheur.Pecher p && p.lance >= 0 && g.position().distanceTo(Vec3.atCenterOf(fond)) < 6.5,
                        "il pêche à la mare montrée (" + TestsJeu.etat(g, g.bureau) + ")"),
                () -> h.assertFalse(m[0], "jamais dans l'eau"),
                () -> TestsRepertoire.fin(e));
    }
}

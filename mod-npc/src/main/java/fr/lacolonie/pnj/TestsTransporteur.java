package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Trajets.Trajet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Le transporteur (lot 6) : un trajet coffre → dépôt vide le coffre, un objet dit seulement, une destination pleine (lettre,
 * rien de perdu), le coffre d'un voisin refusé, un coffre cassé (lettre, trajet suspendu), et l'ordre du chef qui regarde un
 * coffre. Chaque test dans son coin du monde plat (coins 190 à 195).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsTransporteur {
    /** Un transporteur seul, au travail, dans son coin du monde plat ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "transporteur", base[0], Vec3.atBottomCenterOf(base[0].west(2)), ItemStack.EMPTY);
        t.forcerChunk();
        return t;
    }

    /** Un coffre en p, garni de ces piles. */
    static Container coffre(ServerLevel l, BlockPos p, ItemStack... piles) {
        l.setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
        Container c = (Container) l.getBlockEntity(p);
        for (int i = 0; i < piles.length; i++) c.setItem(i, piles[i]);
        return c;
    }

    static int compte(Container c, Item i) {
        int n = 0;
        for (int k = 0; k < c.getContainerSize(); k++) if (c.getItem(k).is(i)) n += c.getItem(k).getCount();
        return n;
    }

    static int sac(Travailleur t, Item i) { return t.compter(Actions.Filtre.de(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString())); }

    static int parTerre(ServerLevel l, BlockPos centre) { return l.getEntitiesOfClass(ItemEntity.class, new AABB(centre).inflate(16)).size(); }

    static void trajet(Travailleur t, long source, long dest, String objet) { Transporteur.ecrire(t, List.of(new Trajet(source, dest, objet))); }

    /** Un coffre de fer et de pierre à 6 blocs : le trajet vers le dépôt le vide, tout arrive au dépôt, rien ne traîne. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void coffreVersDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 190, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        Container c = coffre(l, a, new ItemStack(Items.IRON_INGOT, 20), new ItemStack(Items.COBBLESTONE, 10));
        trajet(t, a.asLong(), Trajets.DEPOT, "");
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 20 && TestsBucheron.compte(d, Items.COBBLESTONE) == 10,
                    "tout au dépôt : fer " + TestsBucheron.compte(d, Items.IRON_INGOT) + ", pierre " + TestsBucheron.compte(d, Items.COBBLESTONE) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(compte(c, Items.IRON_INGOT) == 0 && compte(c, Items.COBBLESTONE) == 0, "le coffre est vide");
            h.assertTrue(sac(t, Items.IRON_INGOT) == 0 && parTerre(l, b[0]) == 0, "rien sur lui ni par terre");
            t.discard();
        });
    }

    /** « Le fer » seulement : le fer va au dépôt, la pierre reste dans le coffre, même après plusieurs tours. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void seulementLObjetDit(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 191, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        Container c = coffre(l, a, new ItemStack(Items.IRON_INGOT, 20), new ItemStack(Items.COBBLESTONE, 10));
        trajet(t, a.asLong(), Trajets.DEPOT, "minecraft:iron_ingot");
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 20, "le fer au dépôt : " + TestsBucheron.compte(d, Items.IRON_INGOT) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(compte(c, Items.COBBLESTONE) == 10 && TestsBucheron.compte(d, Items.COBBLESTONE) == 0 && sac(t, Items.COBBLESTONE) == 0,
                    "la pierre reste au coffre : " + compte(c, Items.COBBLESTONE));
            h.assertTrue(t.tickCount > 900, "plusieurs tours");
            t.discard();
        });
    }

    /** D'un coffre à l'autre, l'arrivée pleine de terre : il l'écrit, et le fer retourne au départ ; rien de perdu. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void destinationPleineRienPerdu(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 192, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0), z = b[0].offset(-6, 0, 4);
        Container c = coffre(l, a, new ItemStack(Items.IRON_INGOT, 16));
        ItemStack[] terre = new ItemStack[27];
        for (int i = 0; i < 27; i++) terre[i] = new ItemStack(Items.DIRT, 64);
        Container plein = coffre(l, z, terre);
        trajet(t, a.asLong(), z.asLong(), "");
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            int fer = compte(c, Items.IRON_INGOT) + compte(plein, Items.IRON_INGOT) + sac(t, Items.IRON_INGOT) + TestsBucheron.compte(d, Items.IRON_INGOT);
            h.assertTrue(fer == 16, "16 fers en tout : " + fer);
            h.assertTrue(TestsMineur.lettre(t, "coffre_plein"), "il écrit que le coffre est plein (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(compte(c, Items.IRON_INGOT) == 16, "le fer est revenu au coffre de départ : " + compte(c, Items.IRON_INGOT));
            h.assertTrue(compte(plein, Items.DIRT) == 27 * 64 && parTerre(l, b[0]) == 0, "la terre intacte, rien par terre");
            t.discard();
        });
    }

    /** Le coffre est plus près du poste d'un voisin : « porte ce coffre au dépôt » est refusé, le coffre intact. Lot « voisins ». */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "voisins")
    public static void coffreDuVoisinRefuse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 193 * 400, 60000);
        ServerLevel l = h.getLevel();
        Vec3 o = Vec3.atBottomCenterOf(base);
        chef.moveTo(o.x + 8, o.y, o.z);
        Travailleur g = TestsJeu.employe(h, "transporteur", base, o.add(8, 0, 2.5), ItemStack.EMPTY);
        g.chef = chef.getUUID();
        g.prenom = "Gaspard";
        g.forcerChunk();
        UUID voisin = UUID.randomUUID();
        Courrier co = Courrier.de(l.getServer());
        co.definirPoste(voisin, l, base.offset(20, 0, 0));
        TestsMineur.A_DEFAIRE.add(() -> co.postes.remove(voisin));
        BlockPos a = base.offset(14, 0, 0);
        Container c = coffre(l, a, new ItemStack(Items.IRON_INGOT, 8));
        Travailleur[] e = {g};
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(a));
                    h.assertTrue(a.equals(Consignes.regardBloc(chef, 48)), "le chef regarde le coffre : " + Consignes.regardBloc(chef, 48));
                    g.parler(chef, "Porte le contenu de ce coffre au dépôt");
                },
                () -> h.assertTrue(IA.echange(g).contains("pas à nous"), "il refuse et dit pourquoi : " + IA.echange(g)),
                TestsIA.attendre(h, 200),
                () -> {
                    h.assertTrue(Transporteur.lire(g).isEmpty(), "aucun trajet retenu : " + Transporteur.lire(g));
                    h.assertTrue(compte(c, Items.IRON_INGOT) == 8, "le coffre du voisin est intact : " + compte(c, Items.IRON_INGOT));
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Le coffre de départ est cassé : il l'écrit, et le trajet est suspendu. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void coffreCasseLettre(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 194, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        coffre(l, a);
        trajet(t, a.asLong(), Trajets.DEPOT, "");
        l.removeBlock(a, false);
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "coffre_introuvable"), "il écrit que le coffre n'y est plus (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Transporteur.lire(t).size() == 1 && Transporteur.lire(t).get(0).suspendu(), "le trajet est suspendu : " + Transporteur.lire(t));
            t.discard();
        });
    }

    /** Le chef regarde un coffre et dit « porte le fer de ce coffre au dépôt » : le trajet est retenu, le fer arrive au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void ordreDuChef(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 195 * 400, 60000);
        ServerLevel l = h.getLevel();
        Vec3 o = Vec3.atBottomCenterOf(base);
        chef.moveTo(o.x, o.y, o.z - 3);
        Travailleur g = TestsJeu.employe(h, "transporteur", base, o.add(-0.5, 0, 2.5), ItemStack.EMPTY);
        g.chef = chef.getUUID();
        g.prenom = "Gaspard";
        g.forcerChunk();
        BlockPos a = base.offset(6, 0, -3);
        Container c = coffre(l, a, new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.COBBLESTONE, 5));
        Bureau.Entite d = TestsBucheron.depot(g);
        Travailleur[] e = {g};
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(a));
                    g.parler(chef, "Porte le fer de ce coffre au dépôt");
                },
                () -> h.assertTrue(Transporteur.lire(g).size() == 1, "le trajet est retenu : " + Transporteur.lire(g) + " / " + IA.echange(g)),
                () -> {
                    h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 12, "le fer au dépôt : " + TestsBucheron.compte(d, Items.IRON_INGOT) + " (" + TestsJeu.etat(g, g.bureau) + ")");
                    h.assertTrue(compte(c, Items.COBBLESTONE) == 5, "la pierre reste au coffre");
                },
                () -> TestsRepertoire.fin(e));
    }
}

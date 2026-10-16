package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Les machines (lot 12, cahier 4.3) : le transporteur remplit et vide les machines de Create et les fours, sur ordre. Le
 * four (vanilla), et les vraies machines de Create (présent dans les essais seulement, en {@code localRuntime}) : le
 * présentoir sous la presse, le bassin qui garde ses entrées, le moulin plein (rien de perdu, la lettre le nomme). Coins 390
 * à 394.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMachines {
    static Block bloc(String id) {
        ResourceLocation r = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(r)) throw new net.minecraft.gametest.framework.GameTestAssertException("Create absent des essais : " + id);
        return BuiltInRegistries.BLOCK.get(r);
    }

    static IItemHandler poignee(ServerLevel l, BlockPos p) { return l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP); }

    static int compte(IItemHandler h, net.minecraft.world.item.Item i) {
        int n = 0;
        for (int k = 0; k < h.getSlots(); k++) if (h.getStackInSlot(k).is(i)) n += h.getStackInSlot(k).getCount();
        return n;
    }

    /** Un transporteur et son chef, qui le regarde. */
    static Travailleur avecChef(GameTestHelper h, ServerPlayer chef, BlockPos base) {
        Vec3 o = Vec3.atBottomCenterOf(base);
        chef.moveTo(o.x, o.y, o.z - 3);
        Travailleur g = TestsJeu.employe(h, "transporteur", base, o.add(-0.5, 0, 2.5), ItemStack.EMPTY);
        g.chef = chef.getUUID();
        g.prenom = "Gaspard";
        g.forcerChunk();
        return g;
    }

    /** « Remplis le four avec le fer » en regardant le four : un trajet du dépôt au four, le fer brut dans son entrée, le four nommé. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void remplisLeFour(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 390 * 400, 60000);
        ServerLevel l = h.getLevel();
        Travailleur g = avecChef(h, chef, base);
        BlockPos f = base.offset(6, 0, -3);
        l.setBlockAndUpdate(f, Blocks.FURNACE.defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.RAW_IRON, 8));
        d.depot.setItem(1, new ItemStack(Items.COBBLESTONE, 8));
        Travailleur[] e = {g};
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(f));
                    g.parler(chef, "Remplis le four avec le fer");
                },
                () -> h.assertTrue(Transporteur.lire(g).size() == 1 && Transporteur.lire(g).get(0).source() == Trajets.DEPOT && Transporteur.lire(g).get(0).dest() == f.asLong()
                        && Transporteur.machineDe(g, f.asLong()).equals("minecraft:furnace"), "le trajet dépôt → four est retenu : " + Transporteur.lire(g) + " / " + IA.echange(g)),
                () -> {
                    FurnaceBlockEntity four = (FurnaceBlockEntity) l.getBlockEntity(f);
                    h.assertTrue(four.getItem(0).is(Items.RAW_IRON) && four.getItem(0).getCount() == 8, "le fer brut dans l'entrée du four : " + four.getItem(0) + " (" + TestsJeu.etat(g, g.bureau) + ")");
                    h.assertTrue(TestsBucheron.compte(d, Items.COBBLESTONE) == 8, "la pierre reste au dépôt");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** « Mets le fer sous la presse » en regardant la presse (sans inventaire) : il pose sur le présentoir de Create dessous. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void presentoirSousLaPresse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 391 * 400, 60000);
        ServerLevel l = h.getLevel();
        Travailleur g = avecChef(h, chef, base);
        BlockPos p = base.offset(6, 0, -3), presse = p.above(2);
        l.setBlockAndUpdate(p, bloc("create:depot").defaultBlockState());
        l.setBlockAndUpdate(presse, bloc("create:mechanical_press").defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        Travailleur[] e = {g};
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(presse));
                    g.parler(chef, "Mets le fer sous la presse");
                },
                () -> h.assertTrue(Transporteur.lire(g).size() == 1 && Transporteur.lire(g).get(0).dest() == p.asLong(),
                        "le trajet va au présentoir sous la presse : " + Transporteur.lire(g) + " / " + IA.echange(g)),
                () -> h.assertTrue(compte(poignee(l, p), Items.IRON_INGOT) > 0, "du fer sur le présentoir (" + TestsJeu.etat(g, g.bureau) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /** Un bassin de Create : il ne prend que ses sorties (les pépites), jamais ses entrées (le blé). */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void bassinGardeSesEntrees(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 392, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(a, bloc("create:basin").defaultBlockState());
        IItemHandlerModifiable bassin = (IItemHandlerModifiable) poignee(l, a);
        bassin.setStackInSlot(0, new ItemStack(Items.WHEAT, 10));
        bassin.setStackInSlot(9, new ItemStack(Items.IRON_NUGGET, 5));
        TestsTransporteur.trajet(t, a.asLong(), Trajets.DEPOT, "");
        Transporteur.retenirMachine(t, a.asLong());
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_NUGGET) == 5, "les pépites au dépôt : " + TestsBucheron.compte(d, Items.IRON_NUGGET) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 600, "plusieurs tours");
            h.assertTrue(compte(poignee(l, a), Items.WHEAT) == 10 && TestsBucheron.compte(d, Items.WHEAT) == 0, "le blé reste dans le bassin");
            t.discard();
        });
    }

    /** Un moulin de Create plein (à l'arrêt) : lettre « machine_pleine » qui le nomme, le blé revient au dépôt, rien de perdu. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void moulinPleinRienPerdu(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 393, b);
        ServerLevel l = h.getLevel();
        BlockPos m = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(m, bloc("create:millstone").defaultBlockState());
        IItemHandler moulin = poignee(l, m);
        moulin.insertItem(0, new ItemStack(Items.COBBLESTONE, 64), false);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT, 16));
        TestsTransporteur.trajet(t, Trajets.DEPOT, m.asLong(), "minecraft:wheat");
        Transporteur.retenirMachine(t, m.asLong());
        h.succeedWhen(() -> {
            int ble = TestsBucheron.compte(d, Items.WHEAT) + TestsTransporteur.sac(t, Items.WHEAT) + compte(moulin, Items.WHEAT);
            h.assertTrue(ble == 16, "16 blés en tout : " + ble);
            h.assertTrue(TestsMineur.lettre(t, "machine_pleine"), "il écrit que le moulin est plein (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.WHEAT) == 16 && compte(moulin, Items.COBBLESTONE) == 64, "le blé revenu au dépôt, le moulin intact");
            h.assertTrue(TestsTransporteur.parTerre(l, b[0]) == 0, "rien par terre");
            t.discard();
        });
    }
}

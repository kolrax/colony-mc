package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Les machines (lot 12, cahier 4.3) : le transporteur remplit et vide les machines sur ordre. Create n'est pas chargé dans
 * les essais (essayé le 16/10 : Create envoie ses paquets à tous les joueurs à chaque tick, et le faux joueur des essais fait
 * planter le serveur de test) : le four est un vrai four ; la presse et le bassin sont imités par des blocs vanilla inscrits
 * dans {@link Machines#PAR_ID} pour les essais (la pierre d'aimant « presse », le distributeur « bassin » à 5 cases d'entrée).
 * La reconnaissance des vrais identifiants de Create est vérifiée par {@code TransporteurTest}. Coins 390 à 393.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMachines {
    static {
        // Des blocs vanilla qui imitent les machines de Create (jamais employés par les autres essais).
        Machines.m("la presse", Machines.Relais.DESSOUS, false, 0, "minecraft:lodestone");
        Machines.m("le bassin", Machines.Relais.AUCUN, false, 5, "minecraft:dispenser");
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

    /** « Mets le fer sous la presse » en regardant la presse (sans inventaire) : il pose dans ce qui est dessous (un tonneau, deux blocs plus bas). */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void sousLaPresse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 391 * 400, 60000);
        ServerLevel l = h.getLevel();
        Travailleur g = avecChef(h, chef, base);
        BlockPos p = base.offset(6, 0, -3), presse = p.above(2);
        l.setBlockAndUpdate(p, Blocks.BARREL.defaultBlockState());
        l.setBlockAndUpdate(presse, Blocks.LODESTONE.defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        Travailleur[] e = {g};
        TestsIA.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(presse));
                    g.parler(chef, "Mets le fer sous la presse");
                },
                () -> h.assertTrue(Transporteur.lire(g).size() == 1 && Transporteur.lire(g).get(0).dest() == p.asLong(),
                        "le trajet va à ce qui est sous la presse : " + Transporteur.lire(g) + " / " + IA.echange(g)),
                () -> h.assertTrue(compte(poignee(l, p), Items.IRON_INGOT) == 10, "le fer sous la presse (" + TestsJeu.etat(g, g.bureau) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /** Un « bassin » (5 cases d'entrée, puis les sorties) : il ne prend que les sorties (les pépites), jamais les entrées (le blé). */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void sortiesSeulement(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 392, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(a, Blocks.DISPENSER.defaultBlockState());
        net.minecraft.world.Container bassin = (net.minecraft.world.Container) l.getBlockEntity(a);
        bassin.setItem(0, new ItemStack(Items.WHEAT, 10));
        bassin.setItem(6, new ItemStack(Items.IRON_NUGGET, 5));
        TestsTransporteur.trajet(t, a.asLong(), Trajets.DEPOT, "");
        Transporteur.retenirMachine(t, a.asLong());
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_NUGGET) == 5, "les pépites au dépôt : " + TestsBucheron.compte(d, Items.IRON_NUGGET) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 600, "plusieurs tours");
            h.assertTrue(TestsTransporteur.compte(bassin, Items.WHEAT) == 10 && TestsBucheron.compte(d, Items.WHEAT) == 0, "le blé reste dans le bassin");
            t.discard();
        });
    }

    /** Un four dont l'entrée est pleine : lettre « machine_pleine » qui le nomme, le fer revient au dépôt, rien de perdu. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void fourPleinRienPerdu(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 393, b);
        ServerLevel l = h.getLevel();
        BlockPos m = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(m, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity four = (FurnaceBlockEntity) l.getBlockEntity(m);
        four.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.RAW_IRON, 16));
        TestsTransporteur.trajet(t, Trajets.DEPOT, m.asLong(), "minecraft:raw_iron");
        Transporteur.retenirMachine(t, m.asLong());
        h.succeedWhen(() -> {
            int fer = TestsBucheron.compte(d, Items.RAW_IRON) + TestsTransporteur.sac(t, Items.RAW_IRON) + four.getItem(0).getCount() - 64;
            h.assertTrue(fer == 16, "16 fers bruts en tout : " + fer);
            h.assertTrue(TestsMineur.lettre(t, "machine_pleine"), "il écrit que le four est plein (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.RAW_IRON) == 16 && four.getItem(0).is(Items.COBBLESTONE) && four.getItem(0).getCount() == 64, "le fer revenu au dépôt, le four intact");
            h.assertTrue(TestsTransporteur.parTerre(l, b[0]) == 0, "rien par terre");
            t.discard();
        });
    }
}

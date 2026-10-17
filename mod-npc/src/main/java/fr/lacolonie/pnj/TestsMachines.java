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
 * La reconnaissance des vrais identifiants de Create est vérifiée par {@code TransporteurTest}. Coins 390 à 398.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMachines {
    /** Des blocs vanilla qui imitent les machines de Create (jamais employés par les autres essais), inscrits le temps d'un essai. */
    static void imiter(String id, String nom, Machines.Relais r, int sortie) { Machines.ESSAIS.put(id, new Machines.Machine(nom, r, false, sortie)); }

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
        imiter("minecraft:lodestone", "la presse", Machines.Relais.DESSOUS, 0);
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
                () -> { Machines.ESSAIS.remove("minecraft:lodestone"); TestsRepertoire.fin(e); });
    }

    /** Un « bassin » (5 cases d'entrée, puis les sorties) : il ne prend que les sorties (les pépites), jamais les entrées (le blé). */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void sortiesSeulement(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 392, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(a, Blocks.DISPENSER.defaultBlockState());
        imiter("minecraft:dispenser", "le bassin", Machines.Relais.AUCUN, 5);
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
            Machines.ESSAIS.remove("minecraft:dispenser");
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

    // --- Relecture du 16/10 ---------------------------------------------------------------------------------------

    /**
     * Une machine qui ne sépare pas l'entrée de la sortie (un « tapis » : un dropper), qu'il remplit de fer et qu'il vide :
     * il ne reprend jamais le fer qu'il y a posé ; les pépites (le produit) vont au dépôt.
     */
    @GameTest(template = "vide", timeoutTicks = 1800)
    public static void neReprendPasCeQuIlAPose(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 394, b);
        ServerLevel l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(a, Blocks.DROPPER.defaultBlockState());
        imiter("minecraft:dropper", "le tapis", Machines.Relais.AUCUN, 0);
        net.minecraft.world.Container tapis = (net.minecraft.world.Container) l.getBlockEntity(a);
        tapis.setItem(4, new ItemStack(Items.IRON_NUGGET, 3));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        Transporteur.ecrire(t, java.util.List.of(new Trajets.Trajet(Trajets.DEPOT, a.asLong(), "minecraft:iron_ingot"), new Trajets.Trajet(a.asLong(), Trajets.DEPOT, "")));
        Transporteur.retenirMachine(t, a.asLong());
        boolean[] parti = {false}, revenu = {false};
        h.onEachTick(() -> {
            int n = TestsBucheron.compte(d, Items.IRON_INGOT);
            if (n == 0) parti[0] = true;
            else if (parti[0]) revenu[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertFalse(revenu[0], "le fer posé est revenu au dépôt (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 1400, "plusieurs tours");
            h.assertTrue(TestsTransporteur.compte(tapis, Items.IRON_INGOT) == 10 && TestsBucheron.compte(d, Items.IRON_NUGGET) == 3,
                    "le fer sur le tapis, les pépites au dépôt : " + TestsTransporteur.compte(tapis, Items.IRON_INGOT) + " / " + TestsBucheron.compte(d, Items.IRON_NUGGET));
            Machines.ESSAIS.remove("minecraft:dropper");
            t.discard();
        });
    }

    /** Ce qui sort d'un four, l'arrivée pleine : il ne le rend pas au four (ce serait son entrée), il le porte au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void retourDUneMachineAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 395, b);
        ServerLevel l = h.getLevel();
        BlockPos f = b[0].offset(6, 0, 0), z = b[0].offset(-6, 0, 4);
        l.setBlockAndUpdate(f, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity four = (FurnaceBlockEntity) l.getBlockEntity(f);
        four.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
        ItemStack[] terre = new ItemStack[27];
        for (int i = 0; i < 27; i++) terre[i] = new ItemStack(Items.DIRT, 64);
        TestsTransporteur.coffre(l, z, terre);
        TestsTransporteur.trajet(t, f.asLong(), z.asLong(), "");
        Transporteur.retenirMachine(t, f.asLong());
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(four.getItem(0).isEmpty(), "rien dans l'entrée du four : " + four.getItem(0));
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 5, "le fer au dépôt : " + TestsBucheron.compte(d, Items.IRON_INGOT) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** Un four collé au bureau ne fait pas partie du dépôt : le dépôt plein, le fer revient au coffre, rien dans le four. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void machineColleeHorsDuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 396, b);
        ServerLevel l = h.getLevel();
        BlockPos f = t.bureau.below(), a = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(f, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity four = (FurnaceBlockEntity) l.getBlockEntity(f);
        Bureau.Entite d = TestsBucheron.depot(t);
        for (int i = 0; i < d.depot.getContainerSize(); i++) d.depot.setItem(i, new ItemStack(Items.DIRT, 64));
        net.minecraft.world.Container c = TestsTransporteur.coffre(l, a, new ItemStack(Items.IRON_INGOT, 8));
        TestsTransporteur.trajet(t, a.asLong(), Trajets.DEPOT, "");
        h.succeedWhen(() -> {
            h.assertTrue(t.tickCount > 600, "il a essayé");
            h.assertTrue(four.getItem(0).isEmpty() && four.getItem(1).isEmpty(), "rien dans le four collé au bureau : " + four.getItem(0) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsTransporteur.compte(c, Items.IRON_INGOT) + TestsTransporteur.sac(t, Items.IRON_INGOT) == 8, "le fer revenu au coffre (ou sur lui)");
            t.discard();
        });
    }

    /** Le charbon porté au four va dans la case du combustible (par le côté), pas dans l'entrée. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void charbonParLeCote(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 397, b);
        ServerLevel l = h.getLevel();
        BlockPos f = b[0].offset(6, 0, 0);
        l.setBlockAndUpdate(f, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity four = (FurnaceBlockEntity) l.getBlockEntity(f);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.COAL, 8));
        TestsTransporteur.trajet(t, Trajets.DEPOT, f.asLong(), "minecraft:coal");
        Transporteur.retenirMachine(t, f.asLong());
        h.succeedWhen(() -> {
            h.assertTrue(four.getItem(1).is(Items.COAL) && four.getItem(1).getCount() == 8 && four.getItem(0).isEmpty(),
                    "le charbon dans la case du combustible : " + four.getItem(1) + " / entrée " + four.getItem(0) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** « Le minerai de fer » reste du fer (le mot le plus long l'emporte) ; « le minerai » seul, tous les minerais. */
    @GameTest(template = "vide")
    public static void mineraiPrecis(GameTestHelper h) {
        h.assertTrue("#c:ores/iron|minecraft:raw_iron".equals(Consignes.filtre("", "Mets le minerai de fer dans le broyeur", null)), "minerai de fer : " + Consignes.filtre("", "Mets le minerai de fer dans le broyeur", null));
        h.assertTrue("#c:ores/copper|minecraft:raw_copper".equals(Consignes.filtre("", "verse les minerais de cuivre dans ce broyeur", null)), "minerai de cuivre");
        h.assertTrue("#c:ores|#c:raw_materials".equals(Consignes.filtre("", "Mets le minerai dans le broyeur", null)), "minerai seul : tous");
        h.assertTrue("le minerai de fer".equals(Transporteur.nomDit("Mets le minerai de fer dans le broyeur")), "nom : " + Transporteur.nomDit("Mets le minerai de fer dans le broyeur"));
        h.succeed();
    }
}

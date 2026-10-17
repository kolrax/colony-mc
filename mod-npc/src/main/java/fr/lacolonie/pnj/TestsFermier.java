package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Le fermier (lot 6) : dans ses champs marqués, il récolte et ressème le blé mûr sans piétiner la terre, laisse le blé vert, laboure et sème un
 * champ de 5 sur 5 avec les graines du dépôt (l'eau au milieu, de son seau), fait du pain au poste, et ne touche pas aux
 * cultures d'un voisin ni d'un champ lointain, ne verse pas l'eau au bord d'un trou, oublie un champ. Chaque test dans
 * son coin du monde plat (coins 120 à 127), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsFermier {
    /** Une culture de cet âge en p, sur une terre labourée (sans eau : une culture garde sa terre). */
    static void culture(ServerLevel l, BlockPos p, Block b, int age) {
        l.setBlockAndUpdate(p.below(), Blocks.FARMLAND.defaultBlockState());
        l.setBlockAndUpdate(p, ((CropBlock) b).getStateForAge(age));
    }

    static int age(ServerLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getBlock() instanceof CropBlock c ? c.getAge(s) : -1;
    }

    /** Un fermier seul, au travail, dans son coin du monde plat ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "fermier", base[0], Vec3.atBottomCenterOf(base[0].west(2)), ItemStack.EMPTY);
        t.forcerChunk();
        return t;
    }

    /** Un fermier, Gautier, près de son chef, dans son coin du monde plat. */
    static Travailleur[] aupres(GameTestHelper h, ServerPlayer chef, int coin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur f = TestsJeu.employe(h, "fermier", base, c.add(-0.5, 0, 2.5), ItemStack.EMPTY);
        f.chef = chef.getUUID();
        f.prenom = "Gautier";
        f.forcerChunk();
        return new Travailleur[]{f};
    }

    /** Un champ marqué (comme après « plante un champ ici ») : centre = le sol ; pas d'entretien pendant le test. */
    static void marquer(Travailleur t, BlockPos centre, int longueur, int largeur, String culture) {
        net.minecraft.nbt.ListTag champs = t.memoire.getList("champs", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.CompoundTag c = new net.minecraft.nbt.CompoundTag();
        c.putLong("c", centre.asLong());
        c.putString("p", new Fouille.Plan(longueur, largeur, 1, "north", false).texte());
        c.putString("g", culture);
        champs.add(c);
        t.memoire.put("champs", champs);
        Fermier.TOURS.computeIfAbsent(t, k -> new Fermier.Tour()).champsVus = Long.MAX_VALUE;
    }

    static int ble(Travailleur t) { return t.compter(Fermier.BLE) + TestsBucheron.compte(TestsBucheron.depot(t), Items.WHEAT); }

    static int parTerre(ServerLevel l, BlockPos centre) { return l.getEntitiesOfClass(ItemEntity.class, new AABB(centre).inflate(12)).size(); }

    /** Trois blés mûrs près du poste : récoltés, ressemés aussitôt, la terre intacte (pas piétinée), rien par terre. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void recolteEtRessemeLeBleMur(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 120, b);
        ServerLevel l = h.getLevel();
        List<BlockPos> murs = List.of(b[0].offset(4, 0, -1), b[0].offset(4, 0, 0), b[0].offset(4, 0, 1));
        for (BlockPos p : murs) culture(l, p, Blocks.WHEAT, 7);
        marquer(t, b[0].offset(4, -1, 0), 5, 5, "ble");
        h.succeedWhen(() -> {
            for (BlockPos p : murs) h.assertTrue(age(l, p) >= 0 && age(l, p) < 7, "blé récolté et ressemé en " + p.subtract(b[0]).toShortString()
                    + " : âge " + age(l, p) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            for (BlockPos p : murs) h.assertTrue(l.getBlockState(p.below()).is(Blocks.FARMLAND), "la terre labourée ne doit pas être piétinée");
            h.assertTrue(ble(t) == 3, "3 blés récoltés, sur lui ou au dépôt : " + ble(t));
            h.assertTrue(parTerre(l, b[0]) == 0, "rien par terre : " + parTerre(l, b[0]));
            t.discard();
        });
    }

    /** Du blé vert à côté d'un blé mûr : le mûr est récolté, le vert reste à son âge. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void laisseLeBleVert(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 121, b);
        ServerLevel l = h.getLevel();
        BlockPos mur = b[0].offset(4, 0, -1);
        List<BlockPos> verts = List.of(b[0].offset(4, 0, 0), b[0].offset(4, 0, 1), b[0].offset(5, 0, 0));
        culture(l, mur, Blocks.WHEAT, 7);
        for (BlockPos p : verts) culture(l, p, Blocks.WHEAT, 2);
        marquer(t, b[0].offset(4, -1, 0), 5, 5, "ble");
        h.succeedWhen(() -> {
            h.assertTrue(age(l, mur) >= 0 && age(l, mur) < 7 && t.tickCount > 300, "le blé mûr doit être récolté et ressemé (" + TestsJeu.etat(t, t.bureau) + ")");
            for (BlockPos p : verts) h.assertTrue(age(l, p) >= 2, "le blé vert reste : âge " + age(l, p) + " en " + p.subtract(b[0]).toShortString());
            t.discard();
        });
    }

    /**
     * « Plante un champ de blé ici, 5 sur 5 » : la houe et 24 graines prises au dépôt (6 y restent), l'eau du seau au milieu
     * (le seau vide lui reste), 24 cases labourées et semées.
     */
    @GameTest(template = "vide", timeoutTicks = 2000)
    public static void champCinqSurCinq(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 122);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        g.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos centre = o.offset(7, -1, 0); // le sol regardé
        List<BlockPos> cases = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (x != 0 || z != 0) cases.add(centre.offset(x, 0, z));
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 5 sur 5");
                },
                () -> {
                    List<String> manquent = new ArrayList<>();
                    for (BlockPos q : cases) if (!l.getBlockState(q).is(Blocks.FARMLAND) || !l.getBlockState(q.above()).is(Blocks.WHEAT)) manquent.add(q.subtract(centre).toShortString());
                    h.assertTrue(manquent.isEmpty() && g.consigne == null, "cases pas semées : " + manquent + " (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")");
                },
                () -> {
                    h.assertTrue(l.getFluidState(centre).is(net.minecraft.tags.FluidTags.WATER), "l'eau au milieu du champ : " + l.getBlockState(centre));
                    h.assertTrue(TestsBucheron.compte(d, Items.WHEAT_SEEDS) == 6, "6 graines restent au dépôt : " + TestsBucheron.compte(d, Items.WHEAT_SEEDS));
                    h.assertTrue(g.compter(Actions.Filtre.de("minecraft:bucket")) == 1 && g.compter(Fermier.SEAU) == 0, "le seau vide lui reste");
                    h.assertTrue(l.getBlockState(centre.offset(3, 0, 0)).is(Blocks.GRASS_BLOCK) && l.getBlockState(centre.offset(0, 0, -3)).is(Blocks.GRASS_BLOCK), "rien hors du champ");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** « Fais du pain » : 2 blés du sac et 7 du dépôt donnent 3 pains au dépôt ; plus un seul blé. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void painAuPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 123);
        Travailleur g = e[0];
        g.inventaire.addItem(new ItemStack(Items.WHEAT, 2));
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT, 7));
        Essais.etapes(h, chef, e,
                () -> g.parler(chef, "Fais du pain"),
                () -> h.assertTrue(TestsBucheron.compte(d, Items.BREAD) == 3 && g.consigne == null,
                        "3 pains au dépôt : " + TestsBucheron.compte(d, Items.BREAD) + " (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> h.assertTrue(ble(g) == 0, "plus de blé : " + ble(g)),
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Relecture : son champ marqué et celui d'un voisin se touchent ; le poste du voisin est plus près des carottes que le sien :
     * il n'y touche pas ; son blé, plus près de chez lui, est récolté. Lot « voisins » ({@link Mineur#voisinsEnEssai}).
     */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "voisins")
    public static void culturesDuVoisinIntactes(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 124, b);
        ServerLevel l = h.getLevel();
        UUID voisin = UUID.randomUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(voisin, l, b[0].offset(10, 0, 0));
        TestsMineur.A_DEFAIRE.add(() -> c.postes.remove(voisin));
        BlockPos mien = b[0].offset(-6, 0, 0);
        List<BlockPos> siens = List.of(b[0].offset(7, 0, 0), b[0].offset(7, 0, 1));
        culture(l, mien, Blocks.WHEAT, 7);
        for (BlockPos p : siens) culture(l, p, Blocks.CARROTS, 7);
        marquer(t, b[0].below(), 15, 15, "ble");
        h.succeedWhen(() -> {
            h.assertTrue(age(l, mien) >= 0 && age(l, mien) < 7 && t.tickCount > 300, "son blé doit être récolté (" + TestsJeu.etat(t, t.bureau) + ")");
            for (BlockPos p : siens) h.assertTrue(age(l, p) == 7, "les carottes du voisin restent mûres : âge " + age(l, p));
            t.discard();
        });
    }

    /**
     * Relecture : du blé mûr à 40 blocs de son poste, hors de ses champs (celui d'un autre) : il n'y touche pas, même après
     * plusieurs tours ; son champ marqué, lui, est récolté.
     */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void champLointainIntact(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 125, b);
        ServerLevel l = h.getLevel();
        BlockPos mien = b[0].offset(4, 0, 0);
        culture(l, mien, Blocks.WHEAT, 7);
        marquer(t, mien.below(), 5, 5, "ble");
        List<BlockPos> loin = List.of(b[0].offset(40, 0, -1), b[0].offset(40, 0, 0), b[0].offset(40, 0, 1));
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(loin.get(1));
        l.setChunkForced(cp.x, cp.z, true); // chargé : il pourrait le voir
        for (BlockPos p : loin) culture(l, p, Blocks.WHEAT, 7);
        h.succeedWhen(() -> {
            for (BlockPos p : loin) h.assertTrue(age(l, p) == 7, "le blé à 40 blocs, hors de ses champs, doit rester : âge " + age(l, p) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(age(l, mien) >= 0 && age(l, mien) < 7, "son blé doit être récolté (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 1500, "plusieurs tours");
            l.setChunkForced(cp.x, cp.z, false);
            t.discard();
        });
    }

    /**
     * Relecture : « plante un champ ici, 3 sur 3 » au bord d'un trou : l'eau du centre coulerait. Il ne la met pas (le seau
     * reste plein), le dit, et sème le centre comme les autres cases.
     */
    @GameTest(template = "vide", timeoutTicks = 1500)
    public static void pasDEauSurUnePente(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 126);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 20));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        g.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos centre = o.offset(7, -1, 0);
        l.setBlockAndUpdate(centre.east(), Blocks.AIR.defaultBlockState()); // un trou à côté du centre
        l.setBlockAndUpdate(centre.east().below(), Blocks.AIR.defaultBlockState());
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 3 sur 3");
                },
                () -> h.assertTrue(g.consigne == null && l.getBlockState(centre.above()).is(Blocks.WHEAT),
                        "le centre est semé, comme les autres (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    int eau = 0;
                    for (BlockPos q : BlockPos.betweenClosed(centre.offset(-3, -3, -3), centre.offset(3, 1, 3))) if (!l.getFluidState(q).isEmpty()) eau++;
                    h.assertTrue(eau == 0, "pas d'eau versée : " + eau + " blocs d'eau");
                    h.assertTrue(g.compter(Fermier.SEAU) == 1, "le seau reste plein");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : « Oublie ce champ », en le regardant : il le retire de ses champs, sans rien arracher. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void oublieLeChamp(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 127);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos centre = chef.blockPosition().offset(7, -1, 0);
        culture(l, centre.offset(1, 1, 1), Blocks.WHEAT, 3);
        marquer(g, centre, 5, 5, "ble");
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Oublie ce champ");
                },
                () -> h.assertTrue(g.memoire.getList("champs", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty() && g.consigne == null,
                        "le champ est oublié (" + TestsRepertoire.c(g) + ", " + g.memoire.get("champs") + ")"),
                () -> h.assertTrue(age(l, centre.offset(1, 1, 1)) >= 3, "rien n'est arraché"),
                () -> TestsRepertoire.fin(e));
    }
}

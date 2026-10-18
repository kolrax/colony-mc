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
        // Un fossé de chaque côté du rang du milieu : aucune case n'est de niveau (avant, un trou à côté du centre suffisait ;
        // il met maintenant l'eau sur une autre case de niveau).
        for (int z = -1; z <= 1; z++) for (int x : new int[]{-1, 1}) for (int y = 0; y >= -2; y--) l.setBlockAndUpdate(centre.offset(x, y, z), Blocks.AIR.defaultBlockState());
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

    /**
     * Son champ de 3 sur 3 à semer, sans une graine sur lui ni au dépôt : « ⚠ Plus de graines au dépôt ». Puis {@code ensuite}
     * (des graines au dépôt, ou le champ semé par quelqu'un d'autre) et un nouvel entretien : le statut s'efface.
     */
    static void plusDeGraines(GameTestHelper h, int coin, boolean auDepot) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, coin, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(4, -1, 0);
        marquer(t, centre, 3, 3, "ble");
        Cerveau.lancer(t, Fermier.Champ.de(t.memoire.getList("champs", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)));
        h.runAtTickTime(h.getTick() + 60, () -> {
            String avant = t.blocageActuel() + " / " + t.memoire.get("sans_graines");
            if (!Statut.SANS_GRAINES.equals(t.blocageActuel())) { t.discard(); h.fail("sans graines, il le dit au-dessus de sa tête : " + avant); }
            if (auDepot) TestsBucheron.depot(t).depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 9));
            else {
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) culture(l, centre.offset(x, 1, z), Blocks.WHEAT, 2);
                Cerveau.lancer(t, Fermier.Champ.de(t.memoire.getList("champs", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)));
            }
            // Il peut être en pause (rien de mûr : 20 à 30 secondes) : il le voit à sa décision suivante.
            h.runAtTickTime(h.getTick() + 660, () -> {
                String apres = t.blocageActuel() + " / " + t.memoire.get("sans_graines") + " (" + TestsJeu.etat(t, t.bureau) + ")";
                t.discard();
                h.assertTrue(!Statut.SANS_GRAINES.equals(t.blocageActuel()) && !t.memoire.contains("sans_graines"),
                        (auDepot ? "des graines au dépôt" : "plus rien à semer") + " : « Plus de graines » s'efface : " + apres);
                h.succeed();
            });
        });
    }

    /** Relecture : des graines arrivent au dépôt, « Plus de graines au dépôt » s'efface. Avant : il le disait jusqu'au prochain semis. */
    @GameTest(template = "vide", timeoutTicks = 1000)
    public static void plusDeGrainesSEffaceAvecLeDepot(GameTestHelper h) { plusDeGraines(h, 128, true); }

    /** Relecture : son champ est tout semé, « Plus de graines au dépôt » s'efface. Avant : il le disait pour toujours. */
    @GameTest(template = "vide", timeoutTicks = 1000)
    public static void plusDeGrainesSEffaceChampSeme(GameTestHelper h) { plusDeGraines(h, 129, false); }

    /**
     * Fantôme : 16 graines, un champ de 9 sur 9 loin de l'eau et pas de seau, 0 blé en 2 jours (sur une terre sèche et serré,
     * le blé met une semaine à mûrir). Maintenant : il creuse une mare au milieu, sème une case sur deux en tout sens (aucun
     * plant n'en touche un autre, même en coin), laboure les autres cases : toute la terre du champ est arrosée.
     */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void champSansSeauNiAssezDeGraines(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 119);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        BlockPos centre = o.offset(9, -1, 0);
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 9 sur 9");
                },
                () -> h.assertTrue(g.consigne == null, "le champ est fait (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    h.assertTrue(l.getFluidState(centre).is(net.minecraft.tags.FluidTags.WATER), "une mare au milieu du champ : " + l.getBlockState(centre));
                    List<BlockPos> ble = new ArrayList<>();
                    List<String> pasLaboure = new ArrayList<>();
                    for (int x = -4; x <= 4; x++)
                        for (int z = -4; z <= 4; z++) {
                            BlockPos q = centre.offset(x, 0, z);
                            if (l.getBlockState(q.above()).is(Blocks.WHEAT)) ble.add(q);
                            if ((x != 0 || z != 0) && !l.getBlockState(q).is(Blocks.FARMLAND)) pasLaboure.add(x + " " + z);
                        }
                    h.assertTrue(ble.size() == 16, "16 cases semées : " + ble.size());
                    for (BlockPos q : ble) for (BlockPos r : ble)
                        h.assertTrue(q == r || Math.abs(q.getX() - r.getX()) > 1 || Math.abs(q.getZ() - r.getZ()) > 1,
                                "deux plants se touchent : " + q.subtract(centre).toShortString() + " et " + r.subtract(centre).toShortString());
                    h.assertTrue(pasLaboure.isEmpty(), "toute la terre du champ labourée : " + pasLaboure);
                    h.assertTrue(l.getBlockState(centre.offset(5, 0, 0)).is(Blocks.GRASS_BLOCK) && l.getBlockState(centre.offset(0, 0, -5)).is(Blocks.GRASS_BLOCK), "rien hors du champ");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Fantôme : le champ fait sur un terrain en marches, le milieu au bord d'une marche ; avant, pas d'eau (« le milieu n'est
     * pas de niveau »), même avec un seau. Maintenant : l'eau du seau sur la case de niveau qui arrose le plus de cases, sans
     * couler, et le seau vide lui reste ; le rang du bas, trop loin, a sa propre mare (restes de la refonte).
     */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void mareSurUnTerrainEnMarches(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 118);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        g.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos centre = o.offset(9, -1, 0);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= -1; z++) l.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.AIR.defaultBlockState()); // la marche, au nord du milieu
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 9 sur 9");
                },
                () -> h.assertTrue(g.consigne == null, "le champ est fait (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    List<BlockPos> eau = new ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -2, -6), centre.offset(6, 1, 6))) if (!l.getFluidState(q).isEmpty()) eau.add(q.immutable());
                    List<String> ou = eau.stream().map(q -> q.subtract(centre).toShortString()).toList();
                    // Une mare par niveau au plus (la seconde arrose le rang du bas, trop loin de la première), qui ne coule pas.
                    h.assertTrue(eau.size() <= 2 && eau.stream().allMatch(q -> l.getFluidState(q).isSource()), "une mare par niveau, qui ne coule pas : " + ou);
                    int arrosees = 0;
                    for (int x = -4; x <= 4; x++)
                        for (int z = -4; z <= 4; z++) {
                            BlockPos q = centre.offset(x, z < 0 ? -1 : 0, z);
                            if (l.getBlockState(q).is(Blocks.FARMLAND) && Fermier.arrose(l, q)) arrosees++;
                        }
                    h.assertTrue(arrosees == 81 - eau.size(), "toute la terre du champ arrosée : " + arrosees + " cases sur " + (81 - eau.size()) + " (eau en " + ou + ")");
                    h.assertTrue(g.compter(Actions.Filtre.de("minecraft:bucket")) == 1 && g.compter(Fermier.SEAU) == 0, "le seau vide lui reste");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Relecture : le champ collé à des constructions d'un joueur (des planches de chaque côté) : tout le champ est dans leur
     * tampon, il n'y creuse pas de mare (le seau reste plein) et sème quand même. Avant : l'eau au milieu. Lot « lieux ».
     */
    @GameTest(template = "vide", timeoutTicks = 3000, batch = "lieux")
    public static void pasDeMarePresDUneConstruction(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 117);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        g.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos centre = o.offset(9, -1, 0);
        l.setBlockAndUpdate(centre.offset(0, 1, 5), Blocks.OAK_PLANKS.defaultBlockState());
        l.setBlockAndUpdate(centre.offset(0, 1, -5), Blocks.OAK_PLANKS.defaultBlockState());
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 9 sur 9");
                },
                () -> h.assertTrue(g.consigne == null && l.getBlockState(centre.above()).is(Blocks.WHEAT),
                        "le champ est semé, le milieu aussi (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    int eau = 0;
                    for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -2, -6), centre.offset(6, 1, 6))) if (!l.getFluidState(q).isEmpty()) eau++;
                    h.assertTrue(eau == 0, "pas de mare dans le tampon des constructions : " + eau + " blocs d'eau");
                    h.assertTrue(g.compter(Fermier.SEAU) == 1, "le seau reste plein");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Le niveau du sol de la case z du champ en escalier : le bas au nord, le haut au sud. */
    static int marche(int z) { return z <= -2 ? -1 : z >= 2 ? 1 : 0; }

    /**
     * Restes de la refonte : un champ de 9 sur 9 sur trois niveaux (escalier), sans seau. Avant : une seule mare, au milieu,
     * et le rang du haut restait sec (l'eau n'arrose que son niveau et celui d'en dessous). Maintenant : une mare de plus
     * au niveau des cases sèches ; toute la terre du champ est arrosée, et aucune eau ne coule.
     */
    @GameTest(template = "vide", timeoutTicks = 3600)
    public static void champEnEscalier(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 116);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(2, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        BlockPos centre = o.offset(9, -1, 0);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= -2; z++) l.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.AIR.defaultBlockState()); // la marche du bas, au nord
            for (int z = 2; z <= 5; z++) l.setBlockAndUpdate(centre.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState()); // la marche du haut, au sud
        }
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 9 sur 9");
                },
                () -> h.assertTrue(g.consigne == null, "le champ est fait (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    List<String> eau = new ArrayList<>(), coule = new ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -3, -6), centre.offset(6, 3, 6))) {
                        if (l.getFluidState(q).isEmpty()) continue;
                        eau.add(q.subtract(centre).toShortString());
                        if (!l.getFluidState(q).isSource()) coule.add(q.subtract(centre).toShortString());
                    }
                    h.assertTrue(coule.isEmpty() && eau.size() <= 3, "des mares qui ne coulent pas, une par niveau au plus : " + eau + ", eau qui coule : " + coule);
                    List<String> seches = new ArrayList<>();
                    for (int x = -4; x <= 4; x++)
                        for (int z = -4; z <= 4; z++) {
                            BlockPos q = centre.offset(x, marche(z), z);
                            if (!l.getFluidState(q).isEmpty()) continue;
                            if (!l.getBlockState(q).is(Blocks.FARMLAND) || !Fermier.arrose(l, q)) seches.add(x + " " + marche(z) + " " + z);
                        }
                    h.assertTrue(seches.isEmpty(), "toute la terre du champ labourée et arrosée ; sèches : " + seches + " (mares en " + eau + ")");
                    h.assertTrue(l.getBlockState(centre.offset(6, 0, 0)).is(Blocks.GRASS_BLOCK), "rien hors du champ");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Restes de la refonte : un champ collé au poste, sans autre construction autour. Tout le champ est dans le tampon du
     * poste : c'est le seul endroit, il y creuse quand même la mare. Avant : pas d'eau du tout. Lot « lieux ».
     */
    @GameTest(template = "vide", timeoutTicks = 2400, batch = "lieux")
    public static void mareDansLeTamponDuPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 115);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        BlockPos centre = o.offset(6, -1, 0);
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 5 sur 5");
                },
                () -> h.assertTrue(g.consigne == null, "le champ est fait (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> h.assertTrue(l.getFluidState(centre).isSource(), "la mare au milieu du champ, dans le tampon du poste : " + l.getBlockState(centre)),
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Restes de la refonte : un vieux champ de 9 sur 9, déjà labouré et retenu, sans eau, et le fermier en mission dessus.
     * Avant : la mare ne se creusait qu'à la création d'un champ, la terre restait sèche. Maintenant, à l'entretien, il
     * creuse une mare dans le champ (sans seau), qui ne coule pas, et toute la terre labourée du champ devient humide.
     */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void mareDansUnVieuxChampSec(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "fermier", 114, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        BlockPos centre = t.bureau.offset(10, -1, 0);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) l.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.FARMLAND.defaultBlockState());
        marquer(t, centre, 9, 9, "ble");
        Fermier.TOURS.get(t).champsVus = 0; // l'entretien du champ dès son premier tour
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(2, new ItemStack(Items.IRON_HOE));
        h.assertTrue(Missions.donner(chef, t, "ble", "stock", 64, "toujours", centre.above()).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            List<String> eau = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -2, -6), centre.offset(6, 1, 6))) {
                if (l.getFluidState(q).isEmpty()) continue;
                h.assertTrue(l.getFluidState(q).isSource() && q.getY() == centre.getY() && Math.abs(q.getX() - centre.getX()) <= 4 && Math.abs(q.getZ() - centre.getZ()) <= 4,
                        "l'eau ne coule pas et reste dans le champ : " + q.subtract(centre).toShortString());
                eau.add(q.subtract(centre).toShortString());
            }
            h.assertTrue(!eau.isEmpty(), "une mare dans le vieux champ sec (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            int humides = 0;
            List<String> seches = new ArrayList<>();
            for (int x = -4; x <= 4; x++)
                for (int z = -4; z <= 4; z++) {
                    BlockPos q = centre.offset(x, 0, z);
                    if (!l.getBlockState(q).is(Blocks.FARMLAND)) continue;
                    // Le jeu humidifie la terre au hasard de ses ticks : on lui en donne un tout de suite.
                    if (l.getBlockState(q).getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) < 7) l.getBlockState(q).randomTick(l, q, l.random);
                    if (l.getBlockState(q).getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) == 7) humides++;
                    else seches.add(x + " " + z);
                }
            h.assertTrue(seches.isEmpty() && humides >= 40, "la terre du champ humide (moisture 7) : " + humides + " cases, sèches : " + seches + " (mare en " + eau + ")");
            t.discard();
        });
    }

    /**
     * Relecture des mares : « plante un champ ici, 9 sur 9 » sur un potager labouré et semé (5 sur 5, au milieu) qui n'est
     * pas à lui. Il creuse sa mare dans l'herbe autour, jamais dans le potager : aucun plant arraché, la terre intacte.
     */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void pasDeMareDansLePotagerDUnAutre(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = aupres(h, chef, 109);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        Bureau.Entite d = TestsBucheron.depot(g);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.IRON_HOE));
        BlockPos centre = o.offset(9, -1, 0);
        List<BlockPos> potager = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) potager.add(centre.offset(x, 1, z));
        for (BlockPos p : potager) culture(l, p, Blocks.CARROTS, 2);
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    g.parler(chef, "Plante un champ de blé ici, 9 sur 9");
                },
                () -> h.assertTrue(g.consigne == null, "le champ est fait (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> {
                    List<String> abimes = new ArrayList<>();
                    for (BlockPos p : potager)
                        if (!l.getBlockState(p).is(Blocks.CARROTS) || !l.getBlockState(p.below()).is(Blocks.FARMLAND)) abimes.add(p.subtract(centre).toShortString() + " " + l.getBlockState(p.below()));
                    h.assertTrue(abimes.isEmpty(), "le potager d'un autre reste intact : " + abimes);
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Un champ retenu de 9 sur 9, tout semé et sec (aucune terre vide) ; {@code vide} : une case labourée sans plant, à côté
     * du milieu. Tout semé : une mare, un seul plant arraché, sa graine au sac. Une case vide : la mare y va, aucun plant
     * arraché.
     */
    static void champToutSeme(GameTestHelper h, int coin, boolean vide) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, coin, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(7, -1, 0), libre = centre.offset(1, 0, 0);
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                BlockPos q = centre.offset(x, 0, z);
                if (vide && q.equals(libre)) l.setBlockAndUpdate(q, Blocks.FARMLAND.defaultBlockState());
                else culture(l, q.above(), Blocks.WHEAT, 2);
            }
        marquer(t, centre, 9, 9, "ble");
        Fermier.TOURS.get(t).champsVus = 0; // l'entretien du champ dès son premier tour
        h.succeedWhen(() -> {
            List<BlockPos> eau = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -2, -6), centre.offset(6, 1, 6))) {
                if (l.getFluidState(q).isEmpty()) continue;
                h.assertTrue(l.getFluidState(q).isSource(), "l'eau ne coule pas : " + q.subtract(centre).toShortString());
                eau.add(q.immutable());
            }
            h.assertTrue(eau.size() == 1, "une mare dans le champ : " + eau.size() + " (" + TestsJeu.etat(t, t.bureau) + ")");
            int ble = 0;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (l.getBlockState(centre.offset(x, 1, z)).is(Blocks.WHEAT)) ble++;
            int graines = t.compter(Actions.Filtre.de("minecraft:wheat_seeds")) + TestsBucheron.compte(TestsBucheron.depot(t), Items.WHEAT_SEEDS);
            if (vide) {
                h.assertTrue(eau.get(0).equals(libre), "la mare sur la terre vide, pas sous un plant : en " + eau.get(0).subtract(centre).toShortString());
                h.assertTrue(ble == 80 && graines == 0, "aucun plant arraché : " + ble + " blés, " + graines + " graines");
            } else h.assertTrue(ble == 80 && graines == 1, "un seul plant arraché, sa graine au sac : " + ble + " blés, " + graines + " graines");
            h.assertTrue(parTerre(l, centre) == 0, "rien par terre : " + parTerre(l, centre));
            t.discard();
        });
    }

    /** Relecture des mares : un vieux champ tout semé et sec reçoit sa mare ; un seul plant arraché, sa graine au sac. */
    @GameTest(template = "vide", timeoutTicks = 2000)
    public static void mareDansUnChampToutSeme(GameTestHelper h) { champToutSeme(h, 108, false); }

    /** Relecture des mares : une terre labourée vide convient, la mare y va ; aucun plant n'est arraché. Avant : le plant du milieu. */
    @GameTest(template = "vide", timeoutTicks = 2000)
    public static void mareSurLaTerreVidePlutotQueSousUnPlant(GameTestHelper h) { champToutSeme(h, 107, true); }

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

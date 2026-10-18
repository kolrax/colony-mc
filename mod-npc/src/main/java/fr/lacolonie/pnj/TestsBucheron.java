package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Le bûcheron du cahier 3.6 : les feuilles tombent après l'abattage, l'arbre géant 2 x 2 tombe en entier et repousse de
 * 4 pousses, la forêt en rangs, les planches et le charbon de bois au poste, le défrichage qui n'abîme rien de construit.
 * Chaque test dans son coin du monde plat (coins 70 à 76), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsBucheron {
    /** Des feuilles naturelles (non posées par un joueur), qui tiennent à leur arbre. */
    static BlockState feuille(Block b) { return b.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1); }

    /** Un arbre : tronc de {@code haut} blocs depuis le pied p (sur la terre), couronne de feuilles 5 x 5 sur 2 blocs au sommet. */
    static List<BlockPos> arbre(ServerLevel l, BlockPos p, Block bois, Block feuilles, int haut, int large) {
        List<BlockPos> tronc = new ArrayList<>();
        for (int y = 0; y < haut; y++) for (int dx = 0; dx < large; dx++) for (int dz = 0; dz < large; dz++) tronc.add(p.offset(dx, y, dz));
        for (int dx = -2; dx < large + 2; dx++) for (int dz = -2; dz < large + 2; dz++) for (int y = haut - 2; y <= haut; y++) {
            BlockPos q = p.offset(dx, y, dz);
            if (!tronc.contains(q)) l.setBlockAndUpdate(q, feuille(feuilles));
        }
        for (BlockPos q : tronc) l.setBlockAndUpdate(q, bois.defaultBlockState());
        return tronc;
    }

    static Bureau.Entite depot(Travailleur t) { return (Bureau.Entite) t.level().getBlockEntity(t.bureau); }

    static int compte(Bureau.Entite b, net.minecraft.world.item.Item i) {
        int n = 0;
        for (int k = 0; k < b.depot.getContainerSize(); k++) if (b.depot.getItem(k).is(i)) n += b.depot.getItem(k).getCount();
        return n;
    }

    /** Un bûcheron seul, au travail, dans son coin du monde plat ; renvoie {sol du poste} via le tableau. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base, ItemStack outil) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "bucheron", base[0], Vec3.atBottomCenterOf(base[0].west(2)), outil);
        t.forcerChunk();
        return t;
    }

    /** Après l'abattage, les feuilles de l'arbre tombent tout de suite (pas une à une en une minute) ; une feuille posée reste. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void feuillesTombentApresAbattage(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 70, b, new ItemStack(Items.IRON_AXE));
        ServerLevel l = h.getLevel();
        BlockPos pied = b[0].east(5);
        arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        BlockPos posee = pied.offset(3, 4, 0); // une feuille posée par un joueur, collée à la couronne
        l.setBlockAndUpdate(posee, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        long[] tombe = {-1};
        h.succeedWhen(() -> {
            h.assertTrue(!l.getBlockState(pied).is(Blocks.OAK_LOG), "l'arbre doit être abattu (" + TestsJeu.etat(t, t.bureau) + ")");
            if (tombe[0] < 0) tombe[0] = h.getTick();
            List<String> restes = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(pied.offset(-3, 0, -3), pied.offset(3, 7, 3)))
                if (l.getBlockState(q).is(Blocks.OAK_LEAVES) && !q.equals(posee)) restes.add(q.subtract(pied).toShortString());
            h.assertTrue(restes.isEmpty(), "les feuilles doivent tomber : " + restes.size() + " restent, " + (h.getTick() - tombe[0]) + " ticks après");
            h.assertTrue(l.getBlockState(posee).is(Blocks.OAK_LEAVES), "la feuille posée doit rester");
            t.discard();
        });
    }

    /**
     * Un grand sapin 2 x 2 de 42 blocs (168 bûches : au-delà des 160 d'avant, et plus haut que les 24 blocs que la recherche
     * regardait) : abattu en entier, et 4 pousses de sapin replantées en carré.
     */
    @GameTest(template = "vide", timeoutTicks = 2600)
    public static void geantAbattuEtReplanteEnCarre(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 71, b, new ItemStack(Items.DIAMOND_AXE));
        t.inventaire.addItem(new ItemStack(Items.SPRUCE_SAPLING, 4));
        ServerLevel l = h.getLevel();
        BlockPos pied = b[0].east(4);
        List<BlockPos> tronc = arbre(l, pied, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, 42, 2);
        List<BlockPos> carre = List.of(pied, pied.east(), pied.south(), pied.south().east());
        java.util.LinkedHashSet<String> vu = new java.util.LinkedHashSet<>();
        h.onEachTick(() -> { if (vu.size() < 30) vu.add((t.action == null ? "-" : t.action.nom) + "/" + t.resultat + "@" + (h.getTick() / 100)); });
        h.succeedWhen(() -> {
            long restent = tronc.stream().filter(q -> l.getBlockState(q).is(Blocks.SPRUCE_LOG)).count();
            h.assertTrue(restent == 0, "tout le tronc doit tomber : " + restent + " bûches restent (vu : " + Actions.chercherArbre(l, t.blockPosition(), 24)
                    + ", arbre " + (Actions.arbre(l, pied) == null ? "non" : "oui") + ", inaccessible " + t.blocInaccessible(pied.asLong()) + ", suite " + vu + ", " + TestsJeu.etat(t, t.bureau) + ")");
            long pousses = carre.stream().filter(q -> l.getBlockState(q).is(Blocks.SPRUCE_SAPLING)).count();
            h.assertTrue(pousses == 4, "4 pousses de sapin en carré : " + pousses + " (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** « Plante des bouleaux en rangs, 7 sur 7 » : 9 pousses de bouleau tous les 3 blocs, prises au dépôt ; rien sur une planche. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void foretEnRangs(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 72);
        Travailleur odon = e[2];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        depot(odon).depot.setItem(0, new ItemStack(Items.BIRCH_SAPLING, 20));
        BlockPos centre = o.offset(7, -1, 0); // le sol regardé
        BlockPos planche = centre.offset(3, 0, 3);
        l.setBlockAndUpdate(planche, Blocks.OAK_PLANKS.defaultBlockState());
        List<BlockPos> places = new ArrayList<>();
        for (int f = -3; f <= 3; f += 3) for (int c = -3; c <= 3; c += 3) places.add(centre.offset(f, 1, c));
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    odon.parler(chef, "Plante des bouleaux en rangs, 7 sur 7");
                },
                () -> {
                    List<String> manquent = new ArrayList<>();
                    for (BlockPos q : places) if (!q.equals(planche.above()) && !l.getBlockState(q).is(Blocks.BIRCH_SAPLING)) manquent.add(q.subtract(centre).toShortString());
                    h.assertTrue(manquent.isEmpty(), "pousses de bouleau manquantes : " + manquent + " (" + TestsRepertoire.c(odon) + ", " + TestsJeu.etat(odon, odon.bureau) + ")");
                },
                () -> {
                    h.assertTrue(odon.consigne == null, "fini, il reprend son travail (" + TestsRepertoire.c(odon) + ")");
                    h.assertTrue(l.getBlockState(planche.above()).isAir() && l.getBlockState(planche).is(Blocks.OAK_PLANKS), "rien sur la planche");
                    int n = 0;
                    for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, 0, -6), centre.offset(6, 2, 6))) if (l.getBlockState(q).is(Blocks.BIRCH_SAPLING)) n++;
                    h.assertTrue(n == 8, "8 pousses, en rangs (" + n + ")");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** « Fais des planches » : 2 bûches de chêne du sac et 4 de bouleau du dépôt donnent 8 + 16 planches, rangées au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void planchesAuPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 73);
        Travailleur odon = e[2];
        odon.inventaire.addItem(new ItemStack(Items.OAK_LOG, 2));
        Bureau.Entite d = depot(odon);
        d.depot.setItem(0, new ItemStack(Items.BIRCH_LOG, 4));
        Essais.etapes(h, chef, e,
                () -> odon.parler(chef, "Fais des planches"),
                () -> h.assertTrue(compte(d, Items.OAK_PLANKS) == 8 && compte(d, Items.BIRCH_PLANKS) == 16,
                        "planches au dépôt : chêne " + compte(d, Items.OAK_PLANKS) + ", bouleau " + compte(d, Items.BIRCH_PLANKS) + " (" + TestsRepertoire.c(odon) + ", " + TestsJeu.etat(odon, odon.bureau) + ")"),
                () -> {
                    h.assertTrue(odon.consigne == null, "fini (" + TestsRepertoire.c(odon) + ")");
                    h.assertTrue(compte(d, Items.BIRCH_LOG) == 0 && odon.compter(Actions.Filtre.de("#minecraft:logs")) == 0, "toutes les bûches sont débitées");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** « Fais du charbon de bois » : 4 bûches et 1 charbon du dépôt au fourneau près du poste, 4 charbons de bois au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 1800)
    public static void charbonDeBoisAuFourneau(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 74);
        Travailleur odon = e[2];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        l.setBlockAndUpdate(o.offset(3, 0, -3), Blocks.FURNACE.defaultBlockState());
        Bureau.Entite d = depot(odon);
        d.depot.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        d.depot.setItem(1, new ItemStack(Items.COAL, 1));
        Essais.etapes(h, chef, e,
                () -> odon.parler(chef, "Fais du charbon de bois"),
                () -> h.assertTrue(compte(d, Items.CHARCOAL) == 4, "4 charbons de bois au dépôt : " + compte(d, Items.CHARCOAL) + " (" + TestsRepertoire.c(odon) + ", " + TestsJeu.etat(odon, odon.bureau) + ")"),
                () -> h.assertTrue(odon.consigne == null && compte(d, Items.OAK_LOG) == 0, "fini, bûches cuites (" + TestsRepertoire.c(odon) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /** Sans fourneau près du poste, il le dit et ne touche à rien. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void charbonSansFourneau(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 75);
        Travailleur odon = e[2];
        Bureau.Entite d = depot(odon);
        d.depot.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        String[] dit = {null};
        Essais.etapes(h, chef, e,
                () -> dit[0] = Consignes.ordonner(odon, new Ordres.Faire("charbon_bois", "", 0, 0, false), chef, "Fais du charbon de bois", 0, 1),
                () -> {
                    h.assertTrue(dit[0] != null && dit[0].contains("fourneau"), "il doit dire qu'il lui faut un fourneau : " + dit[0]);
                    h.assertTrue(odon.consigne == null && compte(d, Items.OAK_LOG) == 4, "rien n'est pris");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * « Défriche ici 9 sur 9 » : l'arbre naturel, ses feuilles, l'herbe et les fleurs partent ; la planche, le coffre, le
     * poteau de rondins sans feuilles et la feuille posée restent ; le sol n'est pas creusé, rien n'est replanté.
     */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void defricheSansAbimer(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 76);
        Travailleur odon = e[2];
        odon.inventaire.addItem(new ItemStack(Items.IRON_AXE));
        odon.inventaire.addItem(new ItemStack(Items.OAK_SAPLING, 4));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        BlockPos centre = o.offset(8, -1, 0); // le sol regardé
        BlockPos pied = centre.offset(2, 1, 2);
        List<BlockPos> tronc = arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        List<BlockPos> herbes = List.of(centre.offset(-1, 1, 1), centre.offset(3, 1, -2), centre.offset(-3, 1, 3), centre.offset(0, 1, -4));
        for (BlockPos q : herbes) l.setBlockAndUpdate(q, Blocks.SHORT_GRASS.defaultBlockState());
        BlockPos fleur = centre.offset(-4, 1, -1), fougere = centre.offset(1, 1, 4);
        l.setBlockAndUpdate(fleur, Blocks.POPPY.defaultBlockState());
        l.setBlockAndUpdate(fougere, Blocks.FERN.defaultBlockState());
        BlockPos planche = centre.offset(-2, 1, -2), coffre = centre.offset(-3, 1, 2), posee = centre.offset(4, 1, -4);
        l.setBlockAndUpdate(planche, Blocks.OAK_PLANKS.defaultBlockState());
        l.setBlockAndUpdate(coffre, Blocks.CHEST.defaultBlockState());
        l.setBlockAndUpdate(posee, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        List<BlockPos> poteau = List.of(centre.offset(0, 1, -3), centre.offset(0, 2, -3)); // des rondins sans feuilles : une construction
        for (BlockPos q : poteau) l.setBlockAndUpdate(q, Blocks.OAK_LOG.defaultBlockState());
        // Hors du terrain (à 14 blocs du centre) : il ne va pas le ramasser.
        net.minecraft.world.entity.item.ItemEntity pain = new net.minecraft.world.entity.item.ItemEntity(l, centre.getX() + 0.5, centre.getY() + 1, centre.getZ() - 13.5, new ItemStack(Items.BREAD));
        pain.setUnlimitedLifetime();
        l.addFreshEntity(pain);
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    odon.parler(chef, "Défriche ici 9 sur 9");
                },
                () -> {
                    List<String> restes = new ArrayList<>();
                    for (BlockPos q : tronc) if (!l.getBlockState(q).isAir()) restes.add("bois " + q.subtract(centre).toShortString());
                    for (BlockPos q : herbes) if (!l.getBlockState(q).isAir()) restes.add("herbe " + q.subtract(centre).toShortString());
                    if (!l.getBlockState(fleur).isAir()) restes.add("fleur");
                    if (!l.getBlockState(fougere).isAir()) restes.add("fougère");
                    for (BlockPos q : BlockPos.betweenClosed(pied.offset(-2, 0, -2), pied.offset(2, 6, 2)))
                        if (l.getBlockState(q).is(Blocks.OAK_LEAVES) && !q.equals(posee)) restes.add("feuille " + q.subtract(centre).toShortString());
                    h.assertTrue(restes.isEmpty() && odon.consigne == null, "à défricher encore : " + restes + " (" + TestsRepertoire.c(odon) + ", " + TestsJeu.etat(odon, odon.bureau) + ")");
                },
                () -> {
                    h.assertTrue(l.getBlockState(planche).is(Blocks.OAK_PLANKS) && l.getBlockState(coffre).is(Blocks.CHEST), "la planche et le coffre restent");
                    h.assertTrue(poteau.stream().allMatch(q -> l.getBlockState(q).is(Blocks.OAK_LOG)), "le poteau de rondins reste");
                    h.assertTrue(l.getBlockState(posee).is(Blocks.OAK_LEAVES), "la feuille posée reste");
                    h.assertTrue(l.getBlockState(pied.below()).is(Blocks.GRASS_BLOCK) || l.getBlockState(pied.below()).is(Blocks.DIRT), "le sol n'est pas creusé");
                    h.assertTrue(l.getBlockState(pied).isAir(), "rien n'est replanté");
                    h.assertTrue(pain.isAlive(), "le pain hors du terrain reste par terre");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : une cabane de rondins (murs de chêne, planches) collée à un chêne : il n'y touche pas, ni à l'arbre. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void cabaneCollee(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 77, b, new ItemStack(Items.IRON_AXE));
        ServerLevel l = h.getLevel();
        BlockPos pied = b[0].east(5);
        arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        List<BlockPos> mur = new ArrayList<>(), planches = new ArrayList<>();
        for (int y = 0; y < 3; y++) { mur.add(pied.offset(1, y, 0)); planches.add(pied.offset(2, y, 0)); }
        for (BlockPos q : mur) l.setBlockAndUpdate(q, Blocks.OAK_LOG.defaultBlockState());
        for (BlockPos q : planches) l.setBlockAndUpdate(q, Blocks.OAK_PLANKS.defaultBlockState());
        h.runAtTickTime(h.getTick() + 400, () -> {
            h.assertTrue(mur.stream().allMatch(q -> l.getBlockState(q).is(Blocks.OAK_LOG)), "le mur de rondins de la cabane doit rester (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(planches.stream().allMatch(q -> l.getBlockState(q).is(Blocks.OAK_PLANKS)), "les planches restent");
            t.discard();
            h.succeed();
        });
    }

    /** Relecture : des bûches d'un joueur dans le fourneau : il ne s'en sert pas et n'y prend rien ; il le dit. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void fourneauDUnAutre(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 78);
        Travailleur odon = e[2];
        ServerLevel l = h.getLevel();
        BlockPos four = chef.blockPosition().offset(3, 0, -3);
        l.setBlockAndUpdate(four, Blocks.FURNACE.defaultBlockState());
        var f = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) l.getBlockEntity(four);
        f.setItem(0, new ItemStack(Items.BIRCH_LOG, 3));
        Bureau.Entite d = depot(odon);
        d.depot.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        d.depot.setItem(1, new ItemStack(Items.COAL, 1));
        String[] dit = {null};
        Essais.etapes(h, chef, e,
                () -> dit[0] = Consignes.ordonner(odon, new Ordres.Faire("charbon_bois", "", 0, 0, false), chef, "Fais du charbon de bois", 0, 1),
                Essais.attendre(h, 300),
                () -> {
                    h.assertTrue(f.getItem(0).is(Items.BIRCH_LOG) && f.getItem(0).getCount() == 3 && f.getItem(1).isEmpty(), "le fourneau du joueur est intact : " + f.getItem(0) + " / " + f.getItem(1));
                    h.assertTrue(compte(d, Items.OAK_LOG) == 4 && compte(d, Items.COAL) == 1 && odon.compter(Actions.Filtre.de("#minecraft:logs")) == 0, "rien n'est pris");
                    h.assertTrue(dit[0] != null && dit[0].contains("occupé"), "il doit dire que le fourneau est occupé : " + dit[0]);
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Enguerrand (serveur, 18/10) : à 2 000 blocs de son poste, il s'éloignait encore, sans ordre de son chef. Chaque coin
     * fouillé en vain repoussait sa recherche de 320 blocs plus loin, sans borne, et il abattait arbre après arbre autour de
     * lui. De lui-même, il ne va plus à plus de {@link Missions#LOIN} blocs du poste : ni vers un vieux coin ni vers une
     * recherche partie trop loin, ni vers l'arbre d'à côté s'il en est déjà trop loin. Avant : il abattait cet arbre, puis
     * partait à 2 000 blocs.
     */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void resteAPorteeDuPoste(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos base = TestsJeu.sol(h, 0, 66000);
        BlockPos ou = TestsJeu.sol(h, 600, 66000);
        List<BlockPos> tronc = arbre(l, TestsJeu.sol(h, 606, 66000), Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        Travailleur t = TestsJeu.employe(h, "bucheron", base, Vec3.atBottomCenterOf(ou), new ItemStack(Items.IRON_AXE));
        t.mission("chene", "stock", 64);
        t.memoire.putLong("coin_chene", base.offset(2000, 0, 0).asLong());   // un coin retenu au loin
        t.memoire.putLong("depuis_chene", base.offset(2000, 0, 0).asLong()); // une recherche repoussée au loin
        t.forcerChunk();
        int[] vu = {0, 0}; // le plus loin qu'il ait visé (blocs du poste), et le nombre de ticks en marche
        h.onEachTick(() -> {
            if (!(t.action instanceof Actions.Aller a)) return;
            double dx = a.cible.x - base.getX(), dz = a.cible.z - base.getZ();
            vu[0] = Math.max(vu[0], (int) Math.sqrt(dx * dx + dz * dz));
            vu[1]++;
        });
        h.runAtTickTime(h.getTick() + 700, () -> {
            String e = TestsJeu.etat(t, base);
            t.discard();
            h.assertTrue(tronc.stream().allMatch(q -> l.getBlockState(q).is(Blocks.OAK_LOG)), "l'arbre à 606 blocs du poste reste debout (" + e + ")");
            h.assertTrue(vu[1] > 0, "il doit partir chercher du bois (" + e + ")");
            h.assertTrue(vu[0] <= Missions.LOIN, "il ne doit pas viser plus loin que " + Missions.LOIN + " blocs du poste : " + vu[0] + " (" + e + ")");
            h.succeed();
        });
    }

    /** Relecture : sans poste et le sac plein, il ne défriche pas en laissant le bois par terre : il s'arrête et le dit. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void defricheSacPleinSansPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 79);
        Travailleur odon = e[2];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        odon.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        for (int i = 0; i < odon.inventaire.getContainerSize(); i++) odon.inventaire.setItem(i, new ItemStack(Items.DIRT, 64));
        odon.bureau = null;
        BlockPos centre = o.offset(7, -1, 0);
        List<BlockPos> tronc = arbre(l, centre.offset(1, 1, 1), Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5, 1);
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(centre.getX() + 0.5, centre.getY() + 0.98, centre.getZ() + 0.5));
                    odon.parler(chef, "Défriche ici 5 sur 5");
                },
                Essais.attendre(h, 400),
                () -> {
                    var par = l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(centre).inflate(16),
                            x -> x.getItem().is(net.minecraft.tags.ItemTags.LOGS));
                    h.assertTrue(par.isEmpty(), "pas de bûches par terre : " + par.size() + " (" + TestsRepertoire.c(odon) + ")");
                    h.assertTrue(tronc.stream().allMatch(q -> l.getBlockState(q).is(Blocks.OAK_LOG)), "l'arbre reste debout");
                    h.assertTrue(odon.consigne == null, "il s'arrête (" + TestsRepertoire.c(odon) + ")");
                },
                () -> TestsRepertoire.fin(e));
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le maçon (lot 10, lot de tests « macon ») : il bâtit un petit plan de 3 x 3 x 3 avec les matériaux du dépôt (et dégage la
 * terre qui gêne) ; il s'arrête et l'écrit quand un matériau manque, puis reprend quand le dépôt en a ; il ne casse jamais
 * le bloc d'un joueur ; « arrête le chantier » puis « reprends le chantier », dits à voix haute. Coins 300 à 303 (z 130000).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMacon {
    static final String CUBE = "essai_cube.nbt";

    /**
     * Le plan d'essai, 3 x 3 x 3 : un sol de pierre ; des bûches aux coins et des planches, une ouverture au sud (air) et de
     * l'air au milieu ; une torche au sol, au milieu ; un toit de planches. 9 pierres, 4 bûches, 12 planches, 1 torche.
     */
    static Map<BlockPos, BlockState> cube() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++) {
                m.put(new BlockPos(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
                m.put(new BlockPos(x, 2, z), Blocks.OAK_PLANKS.defaultBlockState());
                boolean coin = x != 1 && z != 1;
                m.put(new BlockPos(x, 1, z), coin ? Blocks.OAK_LOG.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
            }
        m.put(new BlockPos(1, 1, 2), Blocks.AIR.defaultBlockState());
        m.put(new BlockPos(1, 1, 1), Blocks.TORCH.defaultBlockState());
        return m;
    }

    /** Écrit le plan d'essai dans le dossier des schémas du serveur (format des blocs de structure). */
    static void ecrire(String fichier, Map<BlockPos, BlockState> blocs) {
        try {
            List<BlockState> palette = new ArrayList<>();
            ListTag pal = new ListTag(), lb = new ListTag();
            for (var e : blocs.entrySet()) {
                int i = palette.indexOf(e.getValue());
                if (i < 0) { i = palette.size(); palette.add(e.getValue()); pal.add(NbtUtils.writeBlockState(e.getValue())); }
                CompoundTag b = new CompoundTag();
                ListTag pos = new ListTag();
                pos.add(IntTag.valueOf(e.getKey().getX()));
                pos.add(IntTag.valueOf(e.getKey().getY()));
                pos.add(IntTag.valueOf(e.getKey().getZ()));
                b.put("pos", pos);
                b.putInt("state", i);
                lb.add(b);
            }
            CompoundTag t = new CompoundTag();
            ListTag taille = new ListTag();
            for (int k = 0; k < 3; k++) taille.add(IntTag.valueOf(3));
            t.put("size", taille);
            t.put("palette", pal);
            t.put("blocks", lb);
            t.put("entities", new ListTag());
            t.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            Path d = Macon.dossier();
            Files.createDirectories(d);
            NbtIo.writeCompressed(t, d.resolve(fichier));
            Macon.MODELES.remove("config:" + fichier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Un maçon seul au travail, dans son coin ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base) {
        base[0] = TestsJeu.sol(h, coin * 400, 130000);
        Travailleur t = TestsJeu.employe(h, "macon", base[0], Vec3.atBottomCenterOf(base[0].west(2)), ItemStack.EMPTY);
        t.forcerChunk();
        return t;
    }

    static void garnir(Bureau.Entite d, boolean buches) {
        d.depot.addItem(new ItemStack(Items.COBBLESTONE, 9));
        d.depot.addItem(new ItemStack(Items.OAK_PLANKS, 12));
        d.depot.addItem(new ItemStack(Items.TORCH, 1));
        if (buches) d.depot.addItem(new ItemStack(Items.OAK_LOG, 4));
    }

    /** Les places du plan (origine o, sans rotation) qui ne sont pas encore comme sur le plan, sauf {@code sauf}. */
    static List<BlockPos> restes(ServerLevel l, BlockPos o, BlockPos sauf) {
        List<BlockPos> r = new ArrayList<>();
        for (var e : cube().entrySet()) {
            BlockPos p = o.offset(e.getKey());
            if (p.equals(sauf)) continue;
            if (l.getBlockState(p).getBlock() != e.getValue().getBlock()) r.add(e.getKey());
        }
        return r;
    }

    /** Des étapes l'une après l'autre (chacune retentée au tick suivant tant qu'elle échoue). */
    static void etapes(GameTestHelper h, Runnable... etapes) {
        int[] n = {0};
        h.succeedWhen(() -> {
            while (n[0] < etapes.length) {
                etapes[n[0]].run();
                n[0]++;
            }
        });
    }

    /** Le plan bâti avec le dépôt : chaque bloc à sa place, la terre qui bouchait l'ouverture dégagée, le dépôt vidé de ce qui a servi. */
    @GameTest(template = "vide", timeoutTicks = 2400, batch = "macon")
    public static void construitAvecLeDepot(GameTestHelper h) {
        ecrire(CUBE, cube());
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 300, b);
        ServerLevel l = h.getLevel();
        BlockPos o = b[0].offset(5, 0, -1);
        l.setBlockAndUpdate(o.offset(1, 1, 2), Blocks.DIRT.defaultBlockState()); // de la terre là où le plan veut de l'air
        Bureau.Entite d = TestsBucheron.depot(t);
        garnir(d, true);
        Macon.commencer(t, "config:" + CUBE, "essai cube", o, 0, 0, null);
        h.succeedWhen(() -> {
            List<BlockPos> r = restes(l, o, null);
            h.assertTrue(r.isEmpty(), "reste à bâtir : " + r + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(l.getBlockState(o.offset(1, 1, 2)).isAir(), "la terre dégagée");
            h.assertTrue(!t.memoire.contains("chantier"), "le chantier est fini");
            h.assertTrue(TestsBucheron.compte(d, Items.COBBLESTONE) == 0 && TestsBucheron.compte(d, Items.OAK_LOG) == 0, "tout a servi");
            t.discard();
        });
    }

    /** Pas de bûches au dépôt : il bâtit le sol, s'arrête et l'écrit ; des bûches au dépôt, il reprend et finit. */
    @GameTest(template = "vide", timeoutTicks = 3000, batch = "macon")
    public static void manqueUnMateriau(GameTestHelper h) {
        ecrire(CUBE, cube());
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 301, b);
        ServerLevel l = h.getLevel();
        BlockPos o = b[0].offset(5, 0, -1);
        Bureau.Entite d = TestsBucheron.depot(t);
        garnir(d, false);
        Macon.commencer(t, "config:" + CUBE, "essai cube", o, 0, 0, null);
        etapes(h,
                () -> {
                    h.assertTrue(Macon.chantier(t).getString("manque").equals("minecraft:oak_log"), "il attend des bûches (" + TestsJeu.etat(t, t.bureau) + ")");
                    h.assertTrue(TestsMineur.lettre(t, "materiau_manque"), "il l'a écrit");
                    for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
                        h.assertTrue(l.getBlockState(o.offset(x, 0, z)).is(Blocks.COBBLESTONE), "le sol est fait");
                    h.assertTrue(l.getBlockState(o.offset(0, 2, 0)).isAir(), "il n'a pas bâti au-dessus du manque");
                    // Relecture : le manque ne bloque pas tout ; il a bâti les murs de planches, et le toit sur eux.
                    h.assertTrue(l.getBlockState(o.offset(1, 1, 0)).is(Blocks.OAK_PLANKS) && l.getBlockState(o.offset(1, 2, 0)).is(Blocks.OAK_PLANKS),
                            "les planches sont posées malgré le manque de bûches");
                    d.depot.addItem(new ItemStack(Items.OAK_LOG, 4));
                },
                () -> {
                    List<BlockPos> r = restes(l, o, null);
                    h.assertTrue(r.isEmpty(), "reprise : reste " + r + " (" + TestsJeu.etat(t, t.bureau) + ")");
                    h.assertTrue(!t.memoire.contains("chantier"), "fini");
                },
                () -> t.discard());
    }

    /** Un bloc de pierre taillée posé par un joueur à une place du plan : il n'y touche pas, bâtit le reste, et l'écrit. */
    @GameTest(template = "vide", timeoutTicks = 2400, batch = "macon")
    public static void neCassePasLeBlocDUnJoueur(GameTestHelper h) {
        ecrire(CUBE, cube());
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 302, b);
        ServerLevel l = h.getLevel();
        BlockPos o = b[0].offset(5, 0, -1), joueur = o.offset(1, 1, 0);
        l.setBlockAndUpdate(joueur, Blocks.STONE_BRICKS.defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(t);
        garnir(d, true);
        Macon.commencer(t, "config:" + CUBE, "essai cube", o, 0, 0, null);
        h.succeedWhen(() -> {
            h.assertTrue(l.getBlockState(joueur).is(Blocks.STONE_BRICKS), "le bloc du joueur est intact");
            List<BlockPos> r = restes(l, o, joueur);
            h.assertTrue(r.isEmpty() && !t.memoire.contains("chantier"), "le reste est bâti : " + r + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsMineur.lettre(t, "chantier_gene"), "il a écrit qu'un ouvrage gênait");
            t.discard();
        });
    }

    /**
     * À voix haute : « construis l'essai cube ici » (le chef regarde le sol), puis « arrête le chantier » : il ne pose plus
     * rien ; le plan oublié de la mémoire (comme après un redémarrage), « reprends le chantier » : il finit.
     */
    @GameTest(template = "vide", timeoutTicks = 3600, batch = "macon")
    public static void arretePuisReprend(GameTestHelper h) {
        ecrire(CUBE, cube());
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 303 * 400, 130000);
        ServerLevel l = h.getLevel();
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z + 3);
        Travailleur g = TestsJeu.employe(h, "macon", base, c.add(-1.5, 0, 2.5), ItemStack.EMPTY);
        g.chef = chef.getUUID();
        g.prenom = "Gautier";
        g.forcerChunk();
        Bureau.Entite d = TestsBucheron.depot(g);
        garnir(d, true);
        BlockPos sol = base.offset(0, -1, 8); // le chef regarde le sol, au sud : le plan tourne d'un demi-tour
        int[] arret = {-1};
        long[] quand = {0};
        Travailleur[] e = {g};
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, Vec3.atCenterOf(sol).add(0, 0.5, 0));
                    g.parler(chef, "Construis l'essai cube ici");
                },
                () -> h.assertTrue(Macon.chantier(g).getString("nom").equals("essai cube") && Macon.chantier(g).getInt("r") == 2,
                        "le chantier est retenu, tourné vers le chef : " + Macon.chantier(g) + " / " + Paroles.echange(g)),
                () -> h.assertTrue(Macon.chantier(g).getInt("i") >= 6, "il avance : " + Macon.chantier(g) + " (" + TestsJeu.etat(g, base) + ")"),
                () -> g.parler(chef, "Arrête le chantier"),
                () -> {
                    h.assertTrue(Macon.chantier(g).getBoolean("pause"), "en pause : " + Paroles.echange(g));
                    arret[0] = Macon.chantier(g).getInt("i");
                    quand[0] = h.getTick();
                    Macon.MODELES.clear(); // comme après un redémarrage : le plan sera relu
                },
                () -> {
                    if (h.getTick() - quand[0] < 200) throw new GameTestAssertException("attente");
                    h.assertTrue(Macon.chantier(g).getInt("i") <= arret[0] + 1, "rien de plus en pause : " + Macon.chantier(g).getInt("i") + " / " + arret[0]);
                    h.assertTrue(Macon.etatTexte(g).contains("pour cent"), "il dit où il en est : " + Macon.etatTexte(g));
                },
                () -> g.parler(chef, "Reprends le chantier"),
                () -> {
                    CompoundTag ch = Macon.chantier(g);
                    h.assertTrue(ch.isEmpty(), "fini : " + ch + " (" + TestsJeu.etat(g, base) + ")");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * La cabane d'exemple embarquée (outils/cabane_nbt.py) se lit, et se bâtit tournée d'un quart de tour : chaque bloc à sa
     * place, la porte entière, la torche au mur dans le bon sens. Coin 304.
     */
    @GameTest(template = "vide", timeoutTicks = 4800, batch = "macon")
    public static void batitLaCabaneDExemple(GameTestHelper h) {
        try (var in = Macon.class.getResourceAsStream("/schemas/cabane.nbt")) {
            Files.createDirectories(Macon.dossier());
            Files.copy(in, Macon.dossier().resolve("cabane.nbt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception x) { throw new RuntimeException(x); }
        ServerLevel l = h.getLevel();
        Macon.Modele m = Macon.modele(l, "config:cabane.nbt", true);
        h.assertTrue(m != null && m.ordre().size() == 99 && m.pleins() == 83 && m.sx() == 5 && m.sy() == 4, "la cabane se lit : " + (m == null ? "-" : m.ordre().size() + " / " + m.pleins()));
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 304, b);
        BlockPos o = b[0].offset(9, 0, -2);
        Bureau.Entite d = TestsBucheron.depot(t);
        CompoundTag c = new CompoundTag();
        c.putInt("r", 1);
        for (var x : m.ordre()) {
            BlockState s = Macon.etat(c, m, x);
            if (Macon.objet(s) != Items.AIR) d.ranger(new ItemStack(Macon.objet(s), Macon.nombre(s)));
        }
        Macon.commencer(t, "config:cabane.nbt", "cabane", o, 1, 0, null);
        h.succeedWhen(() -> {
            CompoundTag ch = Macon.chantier(t);
            h.assertTrue(ch.isEmpty(), "fini : " + ch + " (" + TestsJeu.etat(t, t.bureau) + ")");
            List<String> r = new ArrayList<>();
            for (var x : m.ordre()) {
                BlockState s = Macon.etat(c, m, x);
                BlockPos p = Macon.place(c.copy().merge(posee(o)), x);
                if (l.getBlockState(p).getBlock() != s.getBlock()) r.add(x + " " + s.getBlock());
            }
            h.assertTrue(r.isEmpty(), "pas à sa place : " + r);
            int portes = 0, torches = 0;
            for (BlockPos p : BlockPos.betweenClosed(o.offset(-5, 0, -5), o.offset(5, 4, 5))) {
                if (l.getBlockState(p).is(Blocks.OAK_DOOR)) portes++;
                if (l.getBlockState(p).is(Blocks.WALL_TORCH)) {
                    torches++;
                    h.assertTrue(l.getBlockState(p).getValue(net.minecraft.world.level.block.WallTorchBlock.FACING) == net.minecraft.core.Direction.WEST, "la torche tournée avec la cabane");
                }
            }
            h.assertTrue(portes == 2 && torches == 1, "la porte entière (" + portes + " moitiés), une torche (" + torches + ")");
            t.discard();
        });
    }

    static CompoundTag posee(BlockPos o) {
        CompoundTag c = new CompoundTag();
        c.putInt("ox", o.getX());
        c.putInt("oy", o.getY());
        c.putInt("oz", o.getZ());
        return c;
    }

    /** Relecture : « abandonne le chantier » : le chantier est oublié, et les matériaux qu'il portait retournent au dépôt. Coin 305. */
    @GameTest(template = "vide", timeoutTicks = 2400, batch = "macon")
    public static void abandonRendLesMateriaux(GameTestHelper h) {
        ecrire(CUBE, cube());
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 305 * 400, 130000);
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z + 3);
        Travailleur g = TestsJeu.employe(h, "macon", base, c.add(-1.5, 0, 2.5), ItemStack.EMPTY);
        g.chef = chef.getUUID();
        g.prenom = "Gautier";
        g.forcerChunk();
        Bureau.Entite d = TestsBucheron.depot(g);
        garnir(d, true);
        Macon.commencer(g, "config:" + CUBE, "essai cube", base.offset(0, 0, 8), 0, 0, null);
        Travailleur[] e = {g};
        Essais.etapes(h, chef, e,
                () -> h.assertTrue(Macon.chantier(g).getInt("i") >= 3 && g.compter(Actions.Filtre.de("minecraft:oak_planks")) > 0,
                        "il porte des planches : " + Macon.chantier(g) + " (" + TestsJeu.etat(g, base) + ")"),
                () -> g.parler(chef, "Abandonne le chantier"),
                () -> {
                    h.assertTrue(Macon.chantier(g).isEmpty(), "chantier oublié : " + Paroles.echange(g));
                    h.assertTrue(g.compter(Actions.Filtre.de("minecraft:oak_planks")) == 0 && g.compter(Actions.Filtre.de("minecraft:cobblestone")) == 0,
                            "rien gardé sur lui : " + TestsJeu.etat(g, base));
                    h.assertTrue(TestsBucheron.compte(d, Items.OAK_PLANKS) > 0, "les planches sont au dépôt");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : de l'herbe et un chemin dans le plan (introuvables tels quels) : il les fait en terre, et finit. Coin 306. */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "macon")
    public static void herbeEtCheminEnTerre(GameTestHelper h) {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState());
        plan.put(new BlockPos(1, 0, 0), Blocks.DIRT_PATH.defaultBlockState());
        plan.put(new BlockPos(2, 0, 0), Blocks.COBBLESTONE.defaultBlockState());
        ecrire("essai_herbe.nbt", plan);
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 306, b);
        ServerLevel l = h.getLevel();
        BlockPos o = b[0].offset(4, 0, 0);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.DIRT, 2));
        d.depot.addItem(new ItemStack(Items.COBBLESTONE, 1));
        Macon.commencer(t, "config:essai_herbe.nbt", "essai herbe", o, 0, 0, null);
        h.succeedWhen(() -> {
            h.assertTrue(Macon.chantier(t).isEmpty(), "fini : " + Macon.chantier(t) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(l.getBlockState(o).is(Blocks.DIRT) && l.getBlockState(o.east()).is(Blocks.DIRT) && l.getBlockState(o.east(2)).is(Blocks.COBBLESTONE),
                    "de la terre pour l'herbe et le chemin");
            h.assertTrue(TestsBucheron.compte(d, Items.DIRT) == 0, "la terre du dépôt a servi");
            t.discard();
        });
    }
}

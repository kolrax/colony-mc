package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Les outils (cahier 3.12 et 3.13) : fournis au dépôt, le mineur et le bûcheron vont y prendre celui qu'il leur faut (ou un
 * meilleur, en y laissant le leur), et ils les usent trois fois moins vite qu'un joueur. Chaque test dans son coin du monde
 * plat (coins 80 à 86), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsOutils {
    /** Un ouvrier au travail dans son coin, à 2 blocs à l'ouest de son poste ; le poste en base[0]. */
    static Travailleur ouvrier(GameTestHelper h, String metier, int coin, BlockPos[] base, ItemStack outil) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, metier, base[0], Vec3.atBottomCenterOf(base[0].west(2)), outil);
        t.forcerChunk();
        return t;
    }

    /** Un mineur sans pioche : il prend au dépôt la meilleure (fer, pas celle de bois ni l'épée), puis mine le filon. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void mineurPrendSaPiocheAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 80, b, ItemStack.EMPTY);
        t.mission("charbon", "stock", 64);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.WOODEN_PICKAXE));
        d.depot.addItem(new ItemStack(Items.IRON_PICKAXE));
        d.depot.addItem(new ItemStack(Items.IRON_SWORD));
        BlockPos filon = b[0].west(2).south(2);
        h.getLevel().setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        h.succeedWhen(() -> {
            h.assertTrue(t.getMainHandItem().is(Items.IRON_PICKAXE), "la pioche de fer en main (" + t.getMainHandItem() + ", besoin " + t.besoin + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(!h.getLevel().getBlockState(filon).is(Blocks.COAL_ORE) && t.compter(Actions.Filtre.de("minecraft:coal")) >= 1, "il mine le filon");
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_PICKAXE) == 0 && TestsBucheron.compte(d, Items.WOODEN_PICKAXE) == 1
                    && TestsBucheron.compte(d, Items.IRON_SWORD) == 1, "seule la pioche de fer quitte le dépôt");
            t.discard();
        });
    }

    /** Un bûcheron sans hache : il prend au dépôt la hache de fer (pas celle de pierre, ni la pioche) et abat l'arbre. */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void bucheronPrendSaHacheAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "bucheron", 81, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.STONE_AXE));
        d.depot.addItem(new ItemStack(Items.IRON_AXE));
        d.depot.addItem(new ItemStack(Items.IRON_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos pied = b[0].east(5);
        TestsBucheron.arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 4, 1);
        boolean[] pris = {false};
        h.succeedWhen(() -> {
            if (!pris[0]) {
                h.assertTrue(t.getMainHandItem().is(Items.IRON_AXE), "la hache de fer en main (" + t.getMainHandItem() + ", " + TestsJeu.etat(t, t.bureau) + ")");
                h.assertTrue(TestsBucheron.compte(d, Items.IRON_AXE) == 0 && TestsBucheron.compte(d, Items.STONE_AXE) == 1
                        && TestsBucheron.compte(d, Items.IRON_PICKAXE) == 1, "seule la hache de fer quitte le dépôt");
                h.assertFalse(TestsMineur.lettre(t, "hache"), "pas de lettre : il a trouvé sa hache");
                pris[0] = true;
            }
            h.assertFalse(l.getBlockState(pied).is(Blocks.OAK_LOG), "puis il abat l'arbre (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** Une pioche de pierre, une pioche de fer au dépôt : il l'échange, et la sienne retourne au dépôt (rien de perdu ni de créé). */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void echangeContreUneMeilleurePioche(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 82, b, new ItemStack(Items.STONE_PICKAXE));
        t.mission("charbon", "stock", 64);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.IRON_PICKAXE));
        h.succeedWhen(() -> {
            h.assertTrue(t.getMainHandItem().is(Items.IRON_PICKAXE), "la pioche de fer en main (" + t.getMainHandItem() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.STONE_PICKAXE) == 1 && TestsBucheron.compte(d, Items.IRON_PICKAXE) == 0, "la pioche de pierre au dépôt");
            h.assertTrue(t.compter(Actions.Filtre.de(Mineur.PIOCHE)) == 1, "une seule pioche sur lui");
            t.discard();
        });
    }

    /** Sa hache casse en abattant l'arbre : il retourne au dépôt en prendre une autre, sans écrire de lettre. */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void hacheCasseeRemplaceeAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        ItemStack usee = new ItemStack(Items.IRON_AXE);
        usee.setDamageValue(usee.getMaxDamage() - 1);
        Travailleur t = ouvrier(h, "bucheron", 83, b, usee);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.IRON_AXE)); // même matériau : pas d'échange avant qu'elle casse
        ServerLevel l = h.getLevel();
        BlockPos pied = b[0].east(5);
        TestsBucheron.arbre(l, pied, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 4, 1);
        h.succeedWhen(() -> {
            h.assertFalse(l.getBlockState(pied).is(Blocks.OAK_LOG), "l'arbre abattu (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.getMainHandItem().is(Items.IRON_AXE) && t.getMainHandItem().getDamageValue() < 10, "une hache neuve en main (" + t.getMainHandItem() + ", dégâts " + t.getMainHandItem().getDamageValue() + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_AXE) == 0, "prise au dépôt");
            h.assertFalse(TestsMineur.lettre(t, "hache"), "pas de lettre : il en a trouvé une");
            t.discard();
        });
    }

    /** 27 blocs de pierre cassés : la pioche perd environ 9 points (trois fois moins qu'un joueur), pas 27. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void usureTroisFoisPlusLente(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 84 * 400, 60000);
        BlockPos p = base.west(6);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(p), ItemStack.EMPTY);
        t.forcerChunk();
        t.mode = Travailleur.Mode.ATTENDRE;
        t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        ServerLevel l = h.getLevel();
        List<BlockPos> blocs = new ArrayList<>();
        for (int dx = 1; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos q = p.offset(dx, dy, dz);
            l.setBlockAndUpdate(q, Blocks.STONE.defaultBlockState());
            blocs.add(q);
        }
        int[] i = {0};
        h.onEachTick(() -> { if (t.action == null && i[0] < blocs.size()) Cerveau.lancer(t, new Actions.Casser(blocs.get(i[0]++))); });
        h.succeedWhen(() -> {
            long restent = blocs.stream().filter(q -> l.getBlockState(q).is(Blocks.STONE)).count();
            h.assertTrue(restent == 0, "les 27 blocs cassés (" + restent + " restent, " + TestsJeu.etat(t, base) + ")");
            int perdu = t.getMainHandItem().getDamageValue();
            h.assertTrue(perdu >= 2 && perdu <= 18, "la pioche perd environ 9 points pour 27 blocs : " + perdu);
            t.discard();
        });
    }

    /** Relecture : un guerrier armé d'une épée laisse au dépôt la hache de fer (elle est pour le bûcheron), même plus forte. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void guerrierLaisseLaHacheDuBucheron(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "guerrier", 85, b, new ItemStack(Items.IRON_SWORD));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.IRON_AXE));
        h.runAtTickTime(h.getTick() + 200, () -> {
            h.assertTrue(t.memoire.contains("revue"), "il a passé le dépôt en revue (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_AXE) == 1, "la hache reste au dépôt");
            h.assertTrue(t.getMainHandItem().is(Items.IRON_SWORD), "il garde son épée (" + t.getMainHandItem() + ")");
            t.discard();
            h.succeed();
        });
    }

    /** Relecture : une pioche de pierre enchantée (donnée par le chef) n'est pas échangée contre une pioche de fer ordinaire. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void gardeSaPiocheEnchantee(GameTestHelper h) {
        ItemStack enchantee = new ItemStack(Items.STONE_PICKAXE);
        enchantee.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY), 3);
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 86, b, enchantee);
        t.mission("charbon", "stock", 64);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.IRON_PICKAXE));
        h.runAtTickTime(h.getTick() + 200, () -> {
            h.assertTrue(t.memoire.contains("revue_outil"), "il a regardé le dépôt (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_PICKAXE) == 1 && TestsBucheron.compte(d, Items.STONE_PICKAXE) == 0, "la pioche de fer reste au dépôt");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:stone_pickaxe")) == 1, "il garde sa pioche enchantée");
            t.discard();
            h.succeed();
        });
    }
}

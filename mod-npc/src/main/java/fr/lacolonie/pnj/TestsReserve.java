package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * La réserve du poste (cahier 14.4) : le dépôt plein, les ouvriers y rangent sans limite ; elle reverse au dépôt dès
 * qu'une place s'y libère ; ils y trouvent leurs outils ; elle se sauve avec le bureau et tombe au sol s'il est cassé.
 * Coins 340 à 344 du monde plat.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsReserve {
    /** Le dépôt du bureau rempli de terre, 27 piles pleines. */
    static void remplir(Bureau.Entite d) {
        for (int i = 0; i < d.depot.getContainerSize(); i++) d.depot.setItem(i, new ItemStack(Items.DIRT, 64));
    }

    static long enReserve(Bureau.Entite d, Item i) { return d.reserve.compter(s -> s.is(i)); }

    /** Dépôt plein : ce que l'ouvrier dépose va en réserve, rien ne reste au sac ni ne se perd ; lettre « réserve » ; il sait dire ce qu'elle contient. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void depotPleinVaEnReserve(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 340, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        remplir(d);
        t.inventaire.addItem(new ItemStack(Items.IRON_INGOT, 10));
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 64));
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 6));
        Actions.Etat e = new Actions.Deposer(List.of()).tick(t);
        h.assertTrue(e == Actions.Etat.OK, "dépôt réussi (" + e + ", " + t.resultat + ")");
        h.assertTrue(t.compter(Actions.Filtre.de("minecraft:iron_ingot")) == 0 && t.compter(Actions.Filtre.de("minecraft:cobblestone")) == 0, "rien ne reste au sac");
        h.assertTrue(enReserve(d, Items.IRON_INGOT) == 10 && enReserve(d, Items.COBBLESTONE) == 70, "tout en réserve : " + d.reserveTexte());
        h.assertTrue(d.reserve.tas.size() == 2, "fusionné par sorte");
        h.assertTrue(TestsBucheron.compte(d, Items.DIRT) == 27 * 64, "le dépôt visible n'a pas bougé");
        h.assertTrue(d.compter(Actions.Filtre.de("minecraft:iron_ingot")) == 10, "les ouvriers voient la réserve comme le dépôt");
        h.assertTrue(TestsMineur.lettre(t, "depot_reserve") && !TestsMineur.lettre(t, "depot_plein"), "la lettre dit la réserve, pas le dépôt plein");
        String r = Consignes.fait(t, "reserve", null, "qu'y a-t-il dans la réserve ?").texte();
        h.assertTrue(r.contains("70 ") && r.contains("10 "), "il dit ce qu'il y a : " + r);
        t.discard();
        h.succeed();
    }

    /** Le joueur libère une case : la réserve reverse au dépôt (une pile), sans rien perdre ni doubler. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void placeLibereeLaReserveReverse(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 341, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        t.discard();
        remplir(d);
        h.assertTrue(d.ranger(new ItemStack(Items.IRON_INGOT, 64)).isEmpty() && d.ranger(new ItemStack(Items.IRON_INGOT, 36)).isEmpty(), "le dépôt plein prend tout");
        h.assertTrue(enReserve(d, Items.IRON_INGOT) == 100, "100 fers en réserve");
        d.depot.removeItem(0, 64); // le joueur retire une pile de terre
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 64, "une pile de fer revenue au dépôt (" + TestsBucheron.compte(d, Items.IRON_INGOT) + ")");
            h.assertTrue(enReserve(d, Items.IRON_INGOT) == 36, "le reste en réserve (" + enReserve(d, Items.IRON_INGOT) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.DIRT) == 26 * 64, "la terre intacte");
        });
    }

    /** Un mineur sans pioche : la seule pioche est en réserve (dépôt plein de terre). Il la prend et mine. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void outilPrisDansLaReserve(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 342, b, ItemStack.EMPTY);
        t.mission("charbon", "stock", 64);
        Bureau.Entite d = TestsBucheron.depot(t);
        remplir(d);
        d.ranger(new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(enReserve(d, Items.IRON_PICKAXE) == 1, "la pioche est en réserve");
        BlockPos filon = b[0].west(2).south(2);
        h.getLevel().setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        h.succeedWhen(() -> {
            h.assertTrue(t.getMainHandItem().is(Items.IRON_PICKAXE), "la pioche de fer en main (" + t.getMainHandItem() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(enReserve(d, Items.IRON_PICKAXE) == 0, "elle a quitté la réserve");
            h.assertTrue(!h.getLevel().getBlockState(filon).is(Blocks.COAL_ORE), "il mine le filon");
            t.discard();
        });
    }

    /** La réserve se sauve avec le bureau et se relit à l'identique (objet abîmé compris). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void reserveSauveeEtRelue(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 343, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        t.discard();
        remplir(d);
        ItemStack pioche = new ItemStack(Items.IRON_PICKAXE);
        pioche.setDamageValue(37);
        d.ranger(pioche.copy());
        for (int i = 0; i < 5; i++) d.ranger(new ItemStack(Items.COBBLESTONE, 64));
        var reg = h.getLevel().registryAccess();
        CompoundTag tag = d.saveWithFullMetadata(reg);
        BlockEntity relu = BlockEntity.loadStatic(d.getBlockPos(), d.getBlockState(), tag, reg);
        h.assertTrue(relu instanceof Bureau.Entite, "bureau relu");
        Bureau.Entite r = (Bureau.Entite) relu;
        h.assertTrue(enReserve(r, Items.COBBLESTONE) == 320, "320 pierres relues (" + enReserve(r, Items.COBBLESTONE) + ")");
        h.assertTrue(r.reserve.compter(s -> ItemStack.isSameItemSameComponents(s, pioche)) == 1, "la pioche abîmée relue telle quelle");
        h.assertTrue(r.reserve.tas.size() == 2, "deux sortes");
        h.succeed();
    }

    /** Le bureau cassé : la réserve tombe au sol en piles, rien de perdu. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void bureauCasseLaReserveTombe(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 344, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        t.discard();
        remplir(d);
        d.ranger(new ItemStack(Items.IRON_INGOT, 64));
        d.ranger(new ItemStack(Items.IRON_INGOT, 36));
        BlockPos p = d.getBlockPos();
        h.getLevel().removeBlock(p, false);
        int fer = 0;
        for (ItemEntity ie : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(3)))
            if (ie.getItem().is(Items.IRON_INGOT)) fer += ie.getItem().getCount();
        h.assertTrue(fer == 100, "100 fers au sol (" + fer + ")");
        h.succeed();
    }
}

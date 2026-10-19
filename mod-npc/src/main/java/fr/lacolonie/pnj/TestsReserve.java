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
 * Coins 340 à 346 du monde plat.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsReserve {
    /** Le dépôt du bureau rempli de terre, 27 piles pleines. */
    static void remplir(Bureau.Entite d) {
        for (int i = 0; i < d.depot.getContainerSize(); i++) d.depot.setItem(i, new ItemStack(Items.DIRT, 64));
    }

    static long enReserve(Bureau.Entite d, Item i) { return d.reserve.compter(s -> s.is(i)); }

    /**
     * A3 : la terre et le seau d'eau donnés à un ouvrier servent à monter (pilier, colonne d'eau). Au dépôt, il garde 16 terre
     * et le seau d'eau ; le reste (terre en trop, seau vide en plus, fer brut) est rangé. Le transporteur, lui, dépose tout.
     */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void garderTerreEtSeauPourMonter(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 471, b, ItemStack.EMPTY);
        t.mission("pierre", "quantite", 10);
        Bureau.Entite d = TestsBucheron.depot(t);
        t.inventaire.addItem(new ItemStack(Items.BUCKET));
        t.inventaire.addItem(new ItemStack(Items.DIRT, 40));
        t.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        t.inventaire.addItem(new ItemStack(Items.RAW_IRON, 10));
        h.assertTrue(Actions.Deposer.aDeposer(t, Metier.de("mineur").garder, null) == 35, "il compte déposer 24 terre, le fer brut et le seau vide : "
                + Actions.Deposer.aDeposer(t, Metier.de("mineur").garder, null));
        Actions.Etat e = new Actions.Deposer(Metier.de("mineur").garder).tick(t);
        h.assertTrue(e == Actions.Etat.OK, "dépôt réussi (" + e + ", " + t.resultat + ")");
        h.assertTrue(t.compter(Actions.Filtre.de("minecraft:dirt")) == 16, "16 terre restent au sac : " + t.compter(Actions.Filtre.de("minecraft:dirt")));
        h.assertTrue(t.compter(Actions.Filtre.de("minecraft:water_bucket")) == 1, "le seau d'eau reste au sac");
        h.assertTrue(t.compter(Actions.Filtre.de("minecraft:bucket")) == 0 && t.compter(Actions.Filtre.de("minecraft:raw_iron")) == 0, "le seau vide et le fer brut sont rangés");
        h.assertTrue(d.compter(Actions.Filtre.de("minecraft:dirt")) == 24 && d.compter(Actions.Filtre.de("minecraft:raw_iron")) == 10, "le reste au dépôt");
        h.assertTrue(!Actions.Deposer.utile(t, Metier.de("mineur").garder), "plus rien à déposer");
        // Le transporteur : tout son sac est sa charge.
        Travailleur p = TestsOutils.ouvrier(h, "transporteur", 472, b, ItemStack.EMPTY);
        Bureau.Entite dp = TestsBucheron.depot(p);
        p.inventaire.addItem(new ItemStack(Items.DIRT, 8));
        p.inventaire.addItem(new ItemStack(Items.WATER_BUCKET));
        new Actions.Deposer(List.of()).tick(p);
        h.assertTrue(p.compter(Actions.Filtre.de("minecraft:dirt")) == 0 && dp.compter(Actions.Filtre.de("minecraft:water_bucket")) == 1, "le transporteur dépose tout");
        t.discard();
        p.discard();
        h.succeed();
    }

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
        t.mode = Travailleur.Mode.ATTENDRE; // il garde le coin chargé (le poste ne reverse que chargé), sans rien faire
        remplir(d);
        d.versReserve(new ItemStack(Items.IRON_INGOT, 64));
        d.versReserve(new ItemStack(Items.IRON_INGOT, 36));
        h.assertTrue(enReserve(d, Items.IRON_INGOT) == 100, "100 fers en réserve");
        d.depot.removeItem(0, 64); // le joueur retire une pile de terre
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_INGOT) == 64, "une pile de fer revenue au dépôt (" + TestsBucheron.compte(d, Items.IRON_INGOT) + ")");
            h.assertTrue(enReserve(d, Items.IRON_INGOT) == 36, "le reste en réserve (" + enReserve(d, Items.IRON_INGOT) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.DIRT) == 26 * 64, "la terre intacte");
            t.discard();
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
        d.versReserve(new ItemStack(Items.IRON_PICKAXE));
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

    /** Le bureau cassé : rien ne tombe au sol, la réserve est gardée pour son chef et rendue à son prochain poste. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void bureauCasseLaReserveGardee(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 344, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        java.util.UUID chef = t.chef;
        d.proprietaire = chef;
        t.discard();
        remplir(d);
        d.ranger(new ItemStack(Items.IRON_INGOT, 64));
        d.ranger(new ItemStack(Items.IRON_INGOT, 36));
        BlockPos p = d.getBlockPos();
        var l = h.getLevel();
        l.removeBlock(p, false);
        int fer = 0;
        for (ItemEntity ie : l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(3)))
            if (ie.getItem().is(Items.IRON_INGOT)) fer += ie.getItem().getCount();
        h.assertTrue(fer == 0, "rien au sol (" + fer + " fers)");
        Courrier c = Courrier.de(l.getServer());
        h.assertTrue(c.reserves.containsKey(chef), "réserve gardée pour le chef");
        BlockPos q = p.east(3);
        l.setBlockAndUpdate(q, ColoniePnj.BUREAU.get().defaultBlockState());
        Bureau.Entite n = (Bureau.Entite) l.getBlockEntity(q);
        n.proprietaire = chef;
        Bureau.rendreReserve(c, n);
        h.assertTrue(enReserve(n, Items.IRON_INGOT) == 100, "100 fers rendus au nouveau poste (" + enReserve(n, Items.IRON_INGOT) + ")");
        h.assertTrue(!c.reserves.containsKey(chef), "plus rien de gardé à part");
        l.removeBlock(q, false);
        c.reserves.remove(chef);
        h.succeed();
    }

    /** Le transporteur qui porte au dépôt plein ne remplit pas la réserve (pas de stock sans limite pour le chef) : le fer revient au coffre. */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void transporteurNeRemplitPasLaReserve(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsTransporteur.seul(h, 345, b);
        var l = h.getLevel();
        BlockPos a = b[0].offset(6, 0, 0);
        net.minecraft.world.Container c = TestsTransporteur.coffre(l, a, new ItemStack(Items.IRON_INGOT, 16));
        Bureau.Entite d = TestsBucheron.depot(t);
        remplir(d);
        TestsTransporteur.trajet(t, a.asLong(), Trajets.DEPOT, "");
        boolean[] porte = {false};
        h.succeedWhen(() -> {
            if (TestsTransporteur.sac(t, Items.IRON_INGOT) > 0) porte[0] = true;
            h.assertTrue(enReserve(d, Items.IRON_INGOT) == 0, "rien en réserve (" + enReserve(d, Items.IRON_INGOT) + ")");
            h.assertTrue(porte[0] || TestsMineur.lettre(t, "coffre_plein"), "il a essayé (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsTransporteur.compte(c, Items.IRON_INGOT) == 16, "le fer revenu au coffre : " + TestsTransporteur.compte(c, Items.IRON_INGOT));
            t.discard();
        });
    }

    /** Une boîte pleine et un livre écrit ne vont jamais en réserve : ils restent au sac, comme avant. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void boitesEtLivresPasEnReserve(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 346, b, ItemStack.EMPTY);
        Bureau.Entite d = TestsBucheron.depot(t);
        remplir(d);
        ItemStack boite = new ItemStack(Items.SHULKER_BOX);
        boite.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        livre.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                new net.minecraft.world.item.component.WrittenBookContent(net.minecraft.server.network.Filterable.passThrough("Registre"), "Chef", 0, List.of(), true));
        t.inventaire.addItem(boite);
        t.inventaire.addItem(livre);
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 5));
        new Actions.Deposer(List.of()).tick(t);
        h.assertTrue(enReserve(d, Items.COBBLESTONE) == 5, "la pierre en réserve");
        h.assertTrue(enReserve(d, Items.SHULKER_BOX) == 0 && enReserve(d, Items.WRITTEN_BOOK) == 0, "ni boîte ni livre en réserve");
        h.assertTrue(t.compter(Actions.Filtre.de("minecraft:shulker_box")) == 1 && t.compter(Actions.Filtre.de("minecraft:written_book")) == 1, "les deux restent au sac");
        t.discard();
        h.succeed();
    }
}

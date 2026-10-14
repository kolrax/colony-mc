package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Lot 6, le cuisinier (cahier 4.2) : il cuit au fourneau le bœuf cru du dépôt et dépose les steaks, fait le pain avec le blé,
 * écrit quand il n'a rien pour chauffer le four, ne touche ni au four d'un voisin ni à un four où un autre a mis quelque
 * chose, et ne perd rien en route. Coins 180 à 184 du monde plat. Tous échouent sur l'ancien code : le métier n'existait pas.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCuisinier {
    /** Un cuisinier repu, au travail, dans son coin ; son poste est connu du courrier (ses lettres y arrivent). */
    static Travailleur cuisinier(GameTestHelper h, int coin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "cuisinier", base, Vec3.atBottomCenterOf(base.west(2)), ItemStack.EMPTY);
        t.prenom = "Mahaut";
        t.forcerChunk();
        Courrier.de(h.getLevel().getServer()).definirPoste(t.chef, h.getLevel(), base);
        return t;
    }

    static void fin(Travailleur t) {
        Courrier.de(t.level().getServer()).postes.remove(t.chef);
        t.discard();
    }

    static BlockPos four(GameTestHelper h, BlockPos p, net.minecraft.world.level.block.Block b) {
        h.getLevel().setBlockAndUpdate(p, b.defaultBlockState());
        return p;
    }

    static AbstractFurnaceBlockEntity fo(GameTestHelper h, BlockPos p) { return (AbstractFurnaceBlockEntity) h.getLevel().getBlockEntity(p); }

    /** Combien de cet objet : au dépôt, dans son sac et sa main, et dans ces fours. */
    static int partout(Travailleur t, Item i, AbstractFurnaceBlockEntity... fours) {
        int n = TestsBucheron.compte(TestsBucheron.depot(t), i) + t.compter(Actions.Filtre.de(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString()));
        for (AbstractFurnaceBlockEntity f : fours) for (int k = 0; k < 3; k++) if (f.getItem(k).is(i)) n += f.getItem(k).getCount();
        return n;
    }

    /** Il a écrit cette lettre à son chef (au poste, ou encore dans sa poche). */
    static boolean lettre(Travailleur t, String type) {
        return Courrier.de(t.level().getServer()).boite(t.chef).stream().anyMatch(l -> type.equals(l.type)) || t.lettresADeposer.stream().anyMatch(l -> type.equals(l.type));
    }

    /** Trois bœufs crus et un charbon au dépôt, un fourneau à 4 blocs : il les cuit, et les trois steaks arrivent au dépôt ; le four est vide après. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void cuitLeBoeufAuFourneau(GameTestHelper h) {
        Travailleur t = cuisinier(h, 180);
        BlockPos f = four(h, t.bureau.east(4), Blocks.FURNACE);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.BEEF, 3));
        d.depot.setItem(1, new ItemStack(Items.COAL, 5));
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.COOKED_BEEF) == 3, "3 steaks au dépôt : " + TestsBucheron.compte(d, Items.COOKED_BEEF) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.COAL) == 4, "un seul charbon brûlé : " + TestsBucheron.compte(d, Items.COAL));
            h.assertTrue(Cuisinier.libre(fo(h, f)), "le four est vide");
            h.assertTrue(t.memoire.getCompound("fournee").isEmpty(), "fournée sortie");
            fin(t);
        });
    }

    /** Dix blés au dépôt, pas de four : il fait trois pains (trois blés chacun) et les porte au dépôt ; il reste un blé. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void faitDuPainAvecLeBle(GameTestHelper h) {
        Travailleur t = cuisinier(h, 181);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT, 10));
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.BREAD) == 3, "3 pains au dépôt : " + TestsBucheron.compte(d, Items.BREAD) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.WHEAT) == 1 && t.compter(Fermier.BLE) == 0, "un blé reste au dépôt");
            fin(t);
        });
    }

    /** Du bœuf cru et un fourneau, mais ni charbon ni bois : il l'écrit à son chef ; le bœuf ne quitte pas le dépôt, le four reste vide. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void sansCombustibleIlEcrit(GameTestHelper h) {
        Travailleur t = cuisinier(h, 182);
        BlockPos f = four(h, t.bureau.east(4), Blocks.FURNACE);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.BEEF, 3));
        h.succeedWhen(() -> {
            h.assertTrue(lettre(t, "sans_combustible"), "lettre « plus de combustible » (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.BEEF) == 3, "le bœuf reste au dépôt : " + TestsBucheron.compte(d, Items.BEEF));
            h.assertTrue(Cuisinier.libre(fo(h, f)), "le four reste vide");
            fin(t);
        });
    }

    /**
     * Un fourneau plus près du poste d'un voisin que du sien, et un autre près de chez lui où quelqu'un a laissé du fer à fondre :
     * il ne touche à aucun des deux (lettre « il me faut un four ») ; son bœuf et son charbon restent au dépôt. Lot « voisins ».
     */
    @GameTest(template = "vide", timeoutTicks = 800, batch = "voisins")
    public static void neTouchePasAuFourDUnAutre(GameTestHelper h) {
        Travailleur t = cuisinier(h, 183);
        ServerLevel l = h.getLevel();
        UUID voisin = UUID.randomUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(voisin, l, t.bureau.east(12));
        TestsMineur.A_DEFAIRE.add(() -> c.postes.remove(voisin));
        BlockPos sien = four(h, t.bureau.east(8), Blocks.FURNACE), pris = four(h, t.bureau.west(4), Blocks.SMOKER);
        fo(h, pris).setItem(0, new ItemStack(Items.CHICKEN, 2)); // le poulet d'un autre, sans feu
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.BEEF, 3));
        d.depot.setItem(1, new ItemStack(Items.COAL, 2));
        h.succeedWhen(() -> {
            h.assertTrue(lettre(t, "sans_four"), "lettre « il me faut un four » (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Cuisinier.libre(fo(h, sien)), "le four du voisin reste vide");
            h.assertTrue(fo(h, pris).getItem(0).is(Items.CHICKEN) && fo(h, pris).getItem(0).getCount() == 2 && fo(h, pris).getItem(1).isEmpty(),
                    "le fumoir de l'autre est intact");
            h.assertTrue(TestsBucheron.compte(d, Items.BEEF) == 3 && TestsBucheron.compte(d, Items.COAL) == 2, "bœuf et charbon au dépôt");
            fin(t);
        });
    }

    /**
     * Rien ne se perd : son sac plein de pierres (il les dépose d'abord), 12 bœufs, 2 charbons et 10 blés au dépôt, un fumoir.
     * À chaque tick, bœuf cru + cuit = 12 et blé + 3 × pain = 10, où qu'ils soient (dépôt, sac, four) ; rien ne tombe par
     * terre. À la fin : 12 steaks et 3 pains au dépôt.
     */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void rienNeSePerd(GameTestHelper h) {
        Travailleur t = cuisinier(h, 184);
        BlockPos f = four(h, t.bureau.east(4), Blocks.SMOKER);
        for (int i = 0; i < 20; i++) t.inventaire.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.BEEF, 12));
        d.depot.setItem(1, new ItemStack(Items.COAL, 2));
        d.depot.setItem(2, new ItemStack(Items.WHEAT, 10));
        String[] perte = {null};
        h.onEachTick(() -> {
            if (perte[0] != null) return;
            AbstractFurnaceBlockEntity s = fo(h, f);
            int boeuf = partout(t, Items.BEEF, s) + partout(t, Items.COOKED_BEEF, s), ble = partout(t, Items.WHEAT, s) + 3 * partout(t, Items.BREAD, s);
            if (boeuf != 12 || ble != 10) perte[0] = "tick " + h.getTick() + " : bœuf " + boeuf + ", blé " + ble;
            if (!h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(t.bureau).inflate(24)).isEmpty()) perte[0] = "un objet par terre au tick " + h.getTick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(perte[0] == null, "rien ne se perd : " + perte[0]);
            h.assertTrue(TestsBucheron.compte(d, Items.COOKED_BEEF) == 12, "12 steaks au dépôt : " + TestsBucheron.compte(d, Items.COOKED_BEEF) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.BREAD) == 3, "3 pains au dépôt : " + TestsBucheron.compte(d, Items.BREAD));
            h.assertTrue(TestsBucheron.compte(d, Items.COBBLESTONE) == 20 * 64, "ses pierres au dépôt");
            fin(t);
        });
    }
}

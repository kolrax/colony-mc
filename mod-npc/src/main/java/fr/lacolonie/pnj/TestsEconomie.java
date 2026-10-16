package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Lot 11, l'économie entre PNJ (cahier 9.4) : la paie arrive dans son sac ; affamé, sans rien au dépôt, il achète du pain à
 * un cuisinier voisin (même chef, ou autre chef : les pièces vont alors à la caisse du poste du vendeur) ; sans pièces, il
 * ne prend rien et le dit. Coins 370 à 373 du monde plat. Lot à part (« economie ») : la monnaie y est changée en cuivre
 * (1) et or (8), qui existent sans Numismatics, et remise à la fin du lot. Tous échouent sur l'ancien code (voir le journal).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsEconomie {
    static List<Reglages.Piece> normale;
    static int restants = 4;

    static void monnaie() {
        if (normale == null) normale = Reglages.actuel.monnaie;
        Reglages.actuel.monnaie = new java.util.ArrayList<>(List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
    }

    static void fin(Travailleur... ts) {
        for (Travailleur t : ts) { Courrier.de(t.level().getServer()).postes.remove(t.chef); t.discard(); }
        if (--restants <= 0 && normale != null) Reglages.actuel.monnaie = normale;
    }

    /** Un affamé (mineur) à son poste vide, et un cuisinier à 8 blocs ; {@code memeChef} : le même chef pour les deux. */
    static Travailleur[] scene(GameTestHelper h, int coin, boolean memeChef) {
        monnaie();
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur a = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), ItemStack.EMPTY);
        a.prenom = "Gauthier";
        a.faim = 6;
        a.forcerChunk();
        Travailleur v = TestsJeu.employe(h, "cuisinier", base.east(8), Vec3.atBottomCenterOf(base.east(6)), ItemStack.EMPTY);
        v.prenom = "Mahaut";
        if (memeChef) v.chef = a.chef;
        v.forcerChunk();
        return new Travailleur[]{a, v};
    }

    static int compte(Container c, Item i) {
        int n = 0;
        for (int k = 0; k < c.getContainerSize(); k++) if (c.getItem(k).is(i)) n += c.getItem(k).getCount();
        return n;
    }

    /** Le pain du vendeur (sac et dépôt), et ce que mange l'acheteur se retrouve dans sa faim. */
    static int pains(Travailleur v) { return compte(v.inventaire, Items.BREAD) + compte(TestsBucheron.depot(v).depot, Items.BREAD); }

    /** Même chef : deux pièces d'or en poche, un cuisinier avec 3 pains : il en achète un (1 pièce), le mange ; rien ne se perd. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "economie")
    public static void affameAchetePainAuCuisinier(GameTestHelper h) {
        Travailleur[] e = scene(h, 370, true);
        Travailleur a = e[0], v = e[1];
        a.inventaire.addItem(new ItemStack(Items.GOLD_INGOT, 2));
        v.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        h.succeedWhen(() -> {
            h.assertTrue(a.memoire.contains("achat"), "il doit avoir acheté (" + TestsJeu.etat(a, a.bureau) + ", faim " + a.faim + ")");
            h.assertTrue(pains(v) == 2, "un pain vendu : " + pains(v));
            h.assertTrue(a.faim > 6, "le pain mangé : faim " + a.faim);
            long ap = Reglages.valeur(a.inventaire), vp = Reglages.valeur(v.inventaire) + Reglages.valeur(TestsBucheron.depot(v).caisse);
            h.assertTrue(ap == 15 && vp == 1, "1 pièce passée exactement : acheteur " + ap + ", vendeur " + vp);
            fin(a, v);
        });
    }

    /** Sans pièces : il ne prend rien au cuisinier et le dit (« pas assez »). */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "economie")
    public static void sansPiecesPasDAchat(GameTestHelper h) {
        Travailleur[] e = scene(h, 371, true);
        Travailleur a = e[0], v = e[1];
        v.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        h.succeedWhen(() -> {
            h.assertTrue(a.paroles.stream().anyMatch(p -> Vie.vient("achat_pas_assez", p)), "il doit dire qu'il n'a pas de quoi : " + a.paroles);
            h.assertTrue(pains(v) == 3 && !a.memoire.contains("achat"), "rien vendu : " + pains(v));
            fin(a, v);
        });
    }

    /** Deux chefs : le pain est au dépôt du cuisinier ; l'affamé de l'autre chef l'achète, et la pièce va à la caisse de ce poste. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "economie")
    public static void achatEntreDeuxChefs(GameTestHelper h) {
        Travailleur[] e = scene(h, 372, false);
        Travailleur a = e[0], v = e[1];
        a.inventaire.addItem(new ItemStack(Items.GOLD_INGOT, 1));
        Bureau.Entite d = TestsBucheron.depot(v);
        d.depot.setItem(0, new ItemStack(Items.BREAD, 3));
        h.succeedWhen(() -> {
            h.assertTrue(!a.chef.equals(v.chef), "deux chefs");
            h.assertTrue(a.memoire.contains("achat"), "il doit avoir acheté (" + TestsJeu.etat(a, a.bureau) + ", faim " + a.faim + ")");
            h.assertTrue(pains(v) == 2 && a.faim > 6, "un pain vendu et mangé : " + pains(v) + ", faim " + a.faim);
            h.assertTrue(Reglages.valeur(a.inventaire) == 7 && Reglages.valeur(d.caisse) == 1,
                    "1 pièce à la caisse du poste : acheteur " + Reglages.valeur(a.inventaire) + ", caisse " + Reglages.valeur(d.caisse));
            fin(a, v);
        });
    }

    /** La paie touchée arrive dans son sac (sa bourse), et pas ailleurs : la caisse perd exactement ce qu'il gagne. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "economie")
    public static void laPaieVaDansLeSac(GameTestHelper h) {
        monnaie();
        BlockPos base = TestsJeu.sol(h, 373 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), ItemStack.EMPTY);
        t.forcerChunk();
        Bureau.Entite b = TestsBucheron.depot(t);
        b.caisse.addItem(new ItemStack(Items.GOLD_INGOT, 9));
        t.prochainePaie = System.currentTimeMillis() - 1000;
        long du = t.du();
        h.succeedWhen(() -> {
            h.assertTrue(du > 0 && t.impayes == 0 && !t.paieDue(), "payé (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Reglages.valeur(t.inventaire) == du, "sa paie dans son sac : " + Reglages.valeur(t.inventaire) + " sur " + du);
            h.assertTrue(Reglages.valeur(b.caisse) == 72 - du, "la caisse : " + Reglages.valeur(b.caisse));
            fin(t);
        });
    }
}

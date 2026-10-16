package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lot 11, la boutique (cahier 9.5 et 9.6) : le chef dit « tiens la boutique » à un ouvrier, qui vend au poste ce qu'il y a
 * au dépôt ; un autre joueur lui achète du pain avec ses pièces (elles passent à la caisse, il reçoit le pain), sans assez
 * de pièces rien ne bouge, le prix fixé par le chef est respecté (la caisse rend la monnaie), un objet exclu ne se vend
 * pas, et un affamé d'une autre colonie lui achète à manger ; relecture : rien ne disparaît d'un inventaire plein, pas de
 * boutique en grève ni la nuit. Coins 380 à 387 du monde plat. Lot à part (« boutique ») :
 * la monnaie y est changée en cuivre (1) et or (8), qui existent sans Numismatics, et remise à la fin du lot ; le lot
 * commence au matin (la boutique ferme la nuit).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsBoutique {
    static List<Reglages.Piece> normale;

    @BeforeBatch(batch = "boutique")
    public static void avant(ServerLevel l) {
        l.setDayTime(1000);
        normale = Reglages.actuel.monnaie;
        Reglages.actuel.monnaie = new java.util.ArrayList<>(List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
    }

    @AfterBatch(batch = "boutique")
    public static void apres(ServerLevel l) { if (normale != null) Reglages.actuel.monnaie = normale; }

    /** La boutique, son chef et un client : le boutiquier (un mineur) à 2 blocs de son poste, le chef et le client tout près. */
    record Scene(Travailleur t, ServerPlayer chef, ServerPlayer client, Bureau.Entite poste) {}

    static Scene scene(GameTestHelper h, int coin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), client = h.makeMockServerPlayerInLevel();
        chef.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5);
        client.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 2.5);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.east(2)), ItemStack.EMPTY);
        t.chef = chef.getUUID();
        t.chefNom = chef.getName().getString();
        t.prenom = "Mahaut";
        t.forcerChunk();
        return new Scene(t, chef, client, TestsBucheron.depot(t));
    }

    static void fin(Travailleur... ts) {
        for (Travailleur t : ts) { if (t.chef != null) Courrier.de(t.level().getServer()).postes.remove(t.chef); t.discard(); }
    }

    static int compte(Container c, Item i) {
        int n = 0;
        for (int k = 0; k < c.getContainerSize(); k++) if (c.getItem(k).is(i)) n += c.getItem(k).getCount();
        return n;
    }

    /** Il a dit une réplique de cette section (n'importe où dans ce qu'il a dit ; « 1 pièce » remis au pluriel du fichier). */
    static boolean dit(Travailleur t, String cle) {
        for (Vie.Replique r : Vie.TOUTES.getOrDefault(cle, List.of())) {
            Pattern m = Pattern.compile("(?i)" + Pattern.quote(r.texte()).replaceAll("\\{[a-z]+}", "\\\\E.+\\\\Q"));
            for (String p : t.paroles) if (m.matcher(p.replaceAll("(?<![0-9])1 pièce\\b", "1 pièces")).find()) return true;
        }
        return false;
    }

    static String vu(Scene s) {
        return "paroles " + s.t().paroles + ", boutique " + s.t().memoire.getCompound("boutique") + ", ouverte " + Boutiquier.ouverte(s.t())
                + " (" + TestsJeu.etat(s.t(), s.t().bureau) + ")";
    }

    /** Un autre joueur achète 3 pains (1 pièce chacun) : 3 pièces de cuivre passent de sa bourse à la caisse, le pain dans son sac. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void venteAUnAutreJoueur(GameTestHelper h) {
        Scene s = scene(h, 380);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 5));
        s.client().getInventory().add(new ItemStack(Items.COPPER_INGOT, 5));
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Tiens la boutique"),
                () -> h.assertTrue(Boutiquier.ouverte(s.t()), "la boutique doit être ouverte : " + vu(s)),
                () -> s.t().parler(s.client(), "Je t'achète 3 pains"),
                () -> {
                    h.assertTrue(compte(s.client().getInventory(), Items.BREAD) == 3, "3 pains reçus : " + compte(s.client().getInventory(), Items.BREAD) + ", " + vu(s));
                    h.assertTrue(compte(s.client().getInventory(), Items.COPPER_INGOT) == 2, "2 pièces gardées : " + compte(s.client().getInventory(), Items.COPPER_INGOT));
                    h.assertTrue(compte(s.poste().caisse, Items.COPPER_INGOT) == 3, "3 pièces à la caisse : " + compte(s.poste().caisse, Items.COPPER_INGOT));
                    h.assertTrue(compte(s.poste().depot, Items.BREAD) == 2, "2 pains au dépôt : " + compte(s.poste().depot, Items.BREAD));
                    h.assertTrue(dit(s.t(), "vente_conclue"), "il le dit : " + vu(s));
                    h.assertTrue(Boutiquier.ventes(s.t()).contains("3 pains"), "ses ventes du jour : " + Boutiquier.ventes(s.t()));
                },
                () -> fin(s.t()));
    }

    /** Une seule pièce pour 3 pains : rien ne bouge (ni pain, ni pièce), et il le dit. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void pasAssezDePieces(GameTestHelper h) {
        Scene s = scene(h, 381);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 5));
        s.client().getInventory().add(new ItemStack(Items.COPPER_INGOT, 1));
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Ouvre une boutique."),
                () -> h.assertTrue(Boutiquier.ouverte(s.t()), "la boutique doit être ouverte : " + vu(s)),
                () -> s.t().parler(s.client(), "Je t'achète 3 pains"),
                () -> {
                    h.assertTrue(dit(s.t(), "vente_pas_assez"), "il dit qu'il manque des pièces : " + vu(s));
                    h.assertTrue(compte(s.client().getInventory(), Items.BREAD) == 0 && compte(s.client().getInventory(), Items.COPPER_INGOT) == 1,
                            "le client garde sa pièce, sans pain");
                    h.assertTrue(compte(s.poste().depot, Items.BREAD) == 5 && Reglages.valeur(s.poste().caisse) == 0, "rien ne sort du dépôt ni n'entre en caisse");
                },
                () -> fin(s.t()));
    }

    /** « Vends le pain à 2 pièces » : 2 pains pour 4 ; le client paie d'une pièce d'or (8), la caisse lui rend 4 cuivres des siens. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void prixDuChefEtMonnaieRendue(GameTestHelper h) {
        Scene s = scene(h, 382);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 5));
        s.poste().caisse.setItem(0, new ItemStack(Items.COPPER_INGOT, 4));
        s.client().getInventory().add(new ItemStack(Items.GOLD_INGOT, 1));
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Tiens la boutique, et vends le pain à 2 pièces."),
                () -> h.assertTrue(Boutiquier.ouverte(s.t()) && dit(s.t(), "boutique_ordre_prix"), "ouverte, et le prix compris : " + vu(s)),
                () -> s.t().parler(s.client(), "Je veux deux pains"),
                () -> {
                    h.assertTrue(compte(s.client().getInventory(), Items.BREAD) == 2, "2 pains reçus : " + vu(s));
                    h.assertTrue(compte(s.client().getInventory(), Items.GOLD_INGOT) == 0 && compte(s.client().getInventory(), Items.COPPER_INGOT) == 4,
                            "il a payé d'une pièce d'or et reçu 4 cuivres : or " + compte(s.client().getInventory(), Items.GOLD_INGOT) + ", cuivre " + compte(s.client().getInventory(), Items.COPPER_INGOT));
                    h.assertTrue(compte(s.poste().caisse, Items.GOLD_INGOT) == 1 && compte(s.poste().caisse, Items.COPPER_INGOT) == 0,
                            "la caisse : l'or reçu, ses 4 cuivres rendus : " + Reglages.valeur(s.poste().caisse));
                },
                () -> fin(s.t()));
    }

    /** « Ne vends pas le pain » : le client n'en a pas, rien ne bouge ; le steak, lui, se vend (3 pièces, prix de la table). */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void objetExcluPasVendu(GameTestHelper h) {
        Scene s = scene(h, 383);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 5));
        s.poste().depot.setItem(1, new ItemStack(Items.COOKED_BEEF, 2));
        s.client().getInventory().add(new ItemStack(Items.COPPER_INGOT, 10));
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Tiens la boutique. Ne vends pas le pain."),
                () -> h.assertTrue(Boutiquier.ouverte(s.t()) && dit(s.t(), "boutique_ordre_exclu"), "ouverte, et l'exclusion comprise : " + vu(s)),
                () -> s.t().parler(s.client(), "Je veux du pain"),
                () -> {
                    h.assertTrue(compte(s.client().getInventory(), Items.BREAD) == 0 && compte(s.poste().depot, Items.BREAD) == 5, "pas de pain vendu : " + vu(s));
                    h.assertTrue(compte(s.client().getInventory(), Items.COPPER_INGOT) == 10, "pas une pièce prise");
                    h.assertTrue(dit(s.t(), "vente_absent"), "il dit qu'il n'en vend pas : " + vu(s));
                },
                TestsIA.attendre(h, 25), // une demande par seconde au plus
                () -> s.t().parler(s.client(), "Je t'achète un steak"),
                () -> h.assertTrue(compte(s.client().getInventory(), Items.COOKED_BEEF) == 1 && compte(s.client().getInventory(), Items.COPPER_INGOT) == 7
                        && compte(s.poste().caisse, Items.COPPER_INGOT) == 3, "le steak vendu 3 pièces : " + vu(s)),
                () -> fin(s.t()));
    }

    /**
     * Un affamé d'une autre colonie, son dépôt vide, une pièce d'or en poche : il achète du pain au boutiquier (un mineur, pas
     * un producteur), au prix du chef (2) ; la caisse garde la pièce d'or et lui rend 6 cuivres. Il le mange.
     */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "boutique")
    public static void affameAcheteAuBoutiquier(GameTestHelper h) {
        Scene s = scene(h, 384);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 3));
        s.poste().caisse.setItem(0, new ItemStack(Items.COPPER_INGOT, 6));
        BlockPos loin = s.t().bureau.west(10);
        Travailleur a = TestsJeu.employe(h, "mineur", loin, Vec3.atBottomCenterOf(loin.east(2)), ItemStack.EMPTY);
        a.prenom = "Gauthier";
        a.inventaire.addItem(new ItemStack(Items.GOLD_INGOT, 1));
        a.forcerChunk();
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> { s.t().parler(s.chef(), "Tiens la boutique, et vends le pain à 2 pièces."); a.faim = 6; }, // affamé après l'ouverture : sa recherche de vendeur n'a lieu qu'une fois toutes les 3 minutes
                () -> {
                    h.assertTrue(!a.chef.equals(s.t().chef), "deux chefs");
                    h.assertTrue(a.memoire.contains("achat"), "il doit avoir acheté (" + TestsJeu.etat(a, a.bureau) + ", faim " + a.faim + ") ; " + vu(s));
                    h.assertTrue(compte(s.poste().depot, Items.BREAD) == 2 && a.faim > 6, "un pain vendu et mangé : " + compte(s.poste().depot, Items.BREAD) + ", faim " + a.faim);
                    h.assertTrue(Reglages.valeur(a.inventaire) == 6 && compte(a.inventaire, Items.GOLD_INGOT) == 0,
                            "il a payé 2 d'une pièce d'or, 6 rendus : " + Reglages.valeur(a.inventaire));
                    h.assertTrue(compte(s.poste().caisse, Items.GOLD_INGOT) == 1 && compte(s.poste().caisse, Items.COPPER_INGOT) == 0,
                            "la caisse : la pièce d'or, sans ses 6 cuivres : " + Reglages.valeur(s.poste().caisse));
                },
                () -> fin(s.t(), a));
    }

    /** Ce qui est tombé au sol près du client. */
    static int auSol(ServerPlayer p, Item i) {
        int n = 0;
        for (ItemEntity e : p.serverLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4), e -> e.getItem().is(i))) n += e.getItem().getCount();
        return n;
    }

    /**
     * Relecture : un client en créatif, l'inventaire plein, achète 2 pains d'une pièce d'or : la monnaie prend la place de
     * l'or, et le pain tombe à ses pieds au lieu d'être effacé (Inventory.add efface le surplus d'un joueur en créatif).
     */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void inventairePleinRienNeDisparait(GameTestHelper h) {
        Scene s = scene(h, 385);
        s.poste().depot.setItem(0, new ItemStack(Items.BREAD, 5));
        s.poste().caisse.setItem(0, new ItemStack(Items.COPPER_INGOT, 6));
        s.client().setGameMode(GameType.CREATIVE);
        s.client().getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 1));
        for (int i = 1; i < 36; i++) s.client().getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Tiens la boutique"),
                () -> h.assertTrue(Boutiquier.ouverte(s.t()), "la boutique doit être ouverte : " + vu(s)),
                () -> s.t().parler(s.client(), "Je t'achète 2 pains"),
                () -> {
                    h.assertTrue(compte(s.poste().depot, Items.BREAD) == 3, "2 pains vendus : " + vu(s));
                    int pain = compte(s.client().getInventory(), Items.BREAD) + auSol(s.client(), Items.BREAD);
                    int cuivre = compte(s.client().getInventory(), Items.COPPER_INGOT) + auSol(s.client(), Items.COPPER_INGOT);
                    h.assertTrue(pain == 2, "les 2 pains, dans le sac ou à ses pieds : " + pain);
                    h.assertTrue(cuivre == 6, "les 6 pièces de monnaie : " + cuivre);
                    h.assertTrue(compte(s.poste().caisse, Items.GOLD_INGOT) == 1, "l'or à la caisse");
                },
                () -> fin(s.t()));
    }

    /** Relecture : en grève, il ne tient pas la boutique pour un chef qui ne le paie pas. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "boutique")
    public static void enGreveIlRefuse(GameTestHelper h) {
        Scene s = scene(h, 387);
        s.t().mode = Travailleur.Mode.GREVE;
        TestsIA.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), "Tiens la boutique"),
                TestsIA.attendre(h, 10),
                () -> h.assertTrue(!Boutiquier.tient(s.t()) && !Boutiquier.ouverte(s.t()), "pas de boutique en grève : " + vu(s)),
                () -> fin(s.t()));
    }

    static long heureAvant = -1;

    @BeforeBatch(batch = "boutique_nuit")
    public static void laNuit(ServerLevel l) { heureAvant = l.getDayTime(); l.setDayTime(15000); }

    @AfterBatch(batch = "boutique_nuit")
    public static void auJour(ServerLevel l) { if (heureAvant >= 0) l.setDayTime(heureAvant); }

    /** Relecture : la nuit, la boutique est fermée et il ne reste pas planté au poste (le cerveau ne l'y tient pas). */
    @GameTest(template = "vide", timeoutTicks = 200, batch = "boutique_nuit")
    public static void laNuitIlNeTientPasLePoste(GameTestHelper h) {
        Scene s = scene(h, 386);
        net.minecraft.nbt.CompoundTag e = s.t().memoire.getCompound(Boutiquier.CLE);
        e.putBoolean("ouverte", true); // tenue (comme après « tiens la boutique »), sans attendre la parole du chef
        s.t().memoire.put(Boutiquier.CLE, e);
        h.succeedWhen(() -> {
            h.assertTrue(Boutiquier.tient(s.t()) && !Boutiquier.ouverte(s.t()), "tenue, mais fermée la nuit : " + vu(s));
            h.assertTrue(!Boutiquier.tenir(s.t()), "la nuit, le cerveau ne le garde pas au poste");
            fin(s.t());
        });
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests de jeu automatiques : ./gradlew runGameTestServer. Les scripts Lua de base tournent pour de vrai. */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsJeu {

    static Travailleur ouvrier(GameTestHelper h, String metier, int x, int y, int z, ItemStack outil) {
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(x + 0.5, y, z + 0.5));
        t.moveTo(p.x, p.y, p.z, -90, 0);
        t.tirerAuSort(metier, 5);
        t.mode = Travailleur.Mode.TRAVAIL;
        t.lieu = h.absolutePos(new BlockPos(x, y, z));
        t.direction = Direction.EAST;
        if (!outil.isEmpty()) t.inventaire.addItem(outil);
        h.getLevel().addFreshEntity(t);
        return t;
    }

    /** Le mineur scripté creuse sa galerie vers l'est dans la pierre. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void mineurCreuse(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 2; z <= 6; z++) h.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 2; x <= 8; x++) for (int y = 2; y <= 4; y++) for (int z = 3; z <= 5; z++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 1, 2, 4, new ItemStack(Items.DIAMOND_PICKAXE));
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.AIR, 2, 2, 4);
            h.assertBlockPresent(Blocks.AIR, 2, 3, 4);
            h.assertBlockPresent(Blocks.AIR, 4, 2, 4);
            h.assertTrue(t.compter(Actions.Filtre.de("#c:cobblestones")) >= 4, "le mineur doit avoir ramassé la pierre");
        });
    }

    /** Le bûcheron scripté abat l'arbre et garde le bois. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void bucheronAbat(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
        for (int y = 2; y <= 5; y++) h.setBlock(5, y, 4, Blocks.OAK_LOG);
        for (int x = 4; x <= 6; x++) for (int z = 3; z <= 5; z++) for (int y = 5; y <= 6; y++)
            if (!(x == 5 && z == 4 && y == 5)) h.setBlock(x, y, z, Blocks.OAK_LEAVES);
        Travailleur t = ouvrier(h, "bucheron", 1, 2, 4, new ItemStack(Items.IRON_AXE));
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.OAK_LOG, 5, 2, 4);
            h.assertBlockNotPresent(Blocks.OAK_LOG, 5, 5, 4);
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:oak_log")) >= 4, "le bûcheron doit avoir les 4 bûches");
        });
    }

    /** Le guerrier qui garde sa zone tue le monstre qui s'approche. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void guerrierDefend(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        ouvrier(h, "guerrier", 1, 2, 4, new ItemStack(Items.IRON_SWORD));
        h.spawn(net.minecraft.world.entity.EntityType.HUSK, 7, 2, 4);
        h.succeedWhen(() -> h.assertEntityNotPresent(net.minecraft.world.entity.EntityType.HUSK));
    }

    /** Blessé à mort, il tombe à terre ; si personne ne le soigne, il meurt et ses affaires vont dans un coffre. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void aTerrePuisCoffre(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 3, 2, 4, new ItemStack(Items.IRON_PICKAXE));
        t.hurt(t.damageSources().generic(), 100);
        h.assertTrue(t.aTerre && t.isAlive(), "il doit être à terre, pas mort");
        t.aTerreTicks = 5;
        h.succeedWhen(() -> {
            h.assertFalse(t.isAlive(), "il doit être mort");
            h.assertBlockPresent(Blocks.CHEST, 3, 2, 4);
        });
    }

    /** Le panneau web répond : la page, l'API avec le mot de passe, et un refus sans. */
    @GameTest(template = "vide", timeoutTicks = 6000) // le serveur de test enchaîne les ticks bien plus vite que le temps réel
    public static void panneauRepond(GameTestHelper h) {
        var client = java.net.http.HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + Reglages.actuel.port_panneau;
        var page = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var api = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/etat")).header("X-Jeton", Reglages.actuel.jeton_panneau).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var refus = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/etat")).header("X-Jeton", "faux").build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        h.succeedWhen(() -> {
            h.assertTrue(page.isDone() && api.isDone() && refus.isDone(), "réponses en attente");
            h.assertTrue(page.join().statusCode() == 200 && page.join().body().contains("Panneau des PNJ"), "la page doit s'afficher");
            h.assertTrue(api.join().statusCode() == 200 && api.join().body().contains("\"metiers\""), "l'API doit répondre : " + api.join().body());
            h.assertTrue(refus.join().statusCode() == 401, "sans mot de passe : refus attendu");
        });
    }

    /** Un script qui boucle à l'infini est coupé net au lieu de geler le serveur. */
    @GameTest(template = "vide")
    public static void bouclesInfiniesCoupees(GameTestHelper h) {
        String e = Lua.verifier("boucle", "while true do end");
        h.assertTrue(e != null && e.contains("trop long"), "attendu : script trop long, obtenu : " + e);
        String ok = Lua.verifier("essai", "local M = {nom='Essai'} function M.travailler(pnj) return pnj.attendre(1) end return M");
        h.assertTrue(ok == null, "un script correct doit passer : " + ok);
        h.succeed();
    }

    /** La prime d'embauche est payée en pièces et la monnaie est rendue. */
    @GameTest(template = "vide")
    public static void paiementAvecMonnaie(GameTestHelper h) {
        var avant = Reglages.actuel.monnaie;
        Reglages.actuel.monnaie = new java.util.ArrayList<>(java.util.List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
        try {
            var c = new net.minecraft.world.SimpleContainer(9);
            c.addItem(new ItemStack(Items.GOLD_INGOT, 2));
            h.assertTrue(Reglages.payer(c, 5, c::addItem, s -> {}), "16 doit couvrir 5");
            h.assertTrue(Reglages.valeur(c) == 11, "il doit rester 11, il reste " + Reglages.valeur(c));
            h.assertFalse(Reglages.payer(c, 12, c::addItem, s -> {}), "11 ne couvre pas 12");
            h.succeed();
        } finally {
            Reglages.actuel.monnaie = avant;
        }
    }
}

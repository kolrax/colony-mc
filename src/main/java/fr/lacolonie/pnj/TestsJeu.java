package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests de jeu automatiques : ./gradlew runGameTestServer. Le cerveau et les métiers tournent pour de vrai. */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsJeu {

    static Travailleur ouvrier(GameTestHelper h, String metier, int x, int y, int z, ItemStack outil) {
        return ouvrier(h, metier, x, y, z, outil, new BlockPos(0, 2, 0));
    }

    /** Un PNJ employé, rattaché à un bureau posé en {@code bureau} (coordonnées du test), au travail toute la journée. */
    static Travailleur ouvrier(GameTestHelper h, String metier, int x, int y, int z, ItemStack outil, BlockPos bureau) {
        return employe(h, metier, h.absolutePos(bureau), h.absoluteVec(new Vec3(x + 0.5, y, z + 0.5)), outil);
    }

    /** Pareil en coordonnées absolues. */
    static Travailleur employe(GameTestHelper h, String metier, BlockPos bureau, Vec3 p, ItemStack outil) {
        ServerLevel l = h.getLevel();
        l.setBlockAndUpdate(bureau, ColoniePnj.BUREAU.get().defaultBlockState());
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(l);
        t.moveTo(p.x, p.y, p.z, -90, 0);
        t.tirerAuSort(metier, 5);
        t.mode = Travailleur.Mode.TRAVAIL;
        t.chef = java.util.UUID.randomUUID();
        t.prochainePaie = Long.MAX_VALUE; // pas de paie pendant les tests (sauf ceux qui la testent)
        t.debut = 0;
        t.fin = 0; // de 0 h à 0 h : en service toute la journée
        Bureau.Entite b = (Bureau.Entite) l.getBlockEntity(bureau);
        b.proprietaire = t.chef;
        b.employe = t.getUUID();
        t.bureau = bureau;
        if (!outil.isEmpty()) t.inventaire.addItem(outil);
        l.addFreshEntity(t);
        return t;
    }

    // --- Loin des tests, sur le monde plat : sorties et expéditions -----------------------------------

    /** Le sol du monde plat à (dx, dz) de ce test : loin de tout, personne ne garde ces chunks actifs à part le PNJ. */
    static BlockPos sol(GameTestHelper h, int dx, int dz) {
        BlockPos o = h.absolutePos(BlockPos.ZERO).offset(dx, 0, dz);
        h.getLevel().getChunk(o.getX() >> 4, o.getZ() >> 4); // sans chunk chargé, la hauteur du sol vaut le fond du monde
        return new BlockPos(o.getX(), h.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, o.getX(), o.getZ()), o.getZ());
    }

    /** Un lit, pied en p, tête au nord. */
    static void lit(ServerLevel l, BlockPos p) {
        var lit = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        l.setBlockAndUpdate(p, lit.setValue(BedBlock.PART, BedPart.FOOT));
        l.setBlockAndUpdate(p.north(), lit.setValue(BedBlock.PART, BedPart.HEAD));
    }

    static int distance(Travailleur t, BlockPos b) { return (int) Math.sqrt(t.blockPosition().distSqr(new BlockPos(b.getX(), t.getBlockY(), b.getZ()))); }

    static int heure(GameTestHelper h) { return (int) ((h.getLevel().getDayTime() + 6000) % 24000 / 1000); }

    /** Ce qu'il fait et pense, pour comprendre un échec. */
    static String etat(Travailleur t, BlockPos bureau) {
        return "à " + distance(t, bureau) + " blocs (y " + t.getBlockY() + "), vivant " + t.isAlive() + (t.isRemoved() ? " retiré " + t.getRemovalReason() : "")
                + ", vie " + t.getHealth() + ", action " + (t.action == null ? "-" : t.action.nom) + ", résultat " + t.resultat
                + ", tick " + t.tickCount + ", heure " + (t.level().getDayTime() + 6000) % 24000 + ", service " + t.debut + "-" + t.fin
                + ", reste " + String.format("%.2f", t.heuresRestantes()) + " h, doitRentrer " + t.doitRentrer() + ", lit " + t.lit
                + ", chunk actif " + ((ServerLevel) t.level()).isPositionEntityTicking(t.blockPosition())
                + ", mémoire " + t.memoire;
    }

    /**
     * Sans lit, à 200 blocs du bureau, il part à temps pour être rentré avant la fin de sa journée.
     * Il marche seul, loin de tout joueur : il ne doit pas se figer en changeant de chunk.
     */
    @GameTest(template = "vide", timeoutTicks = 4000)
    public static void rentreATempsSansLit(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 3000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(sol(h, 200, 3000)), ItemStack.EMPTY); // sans commande, il ne bouge pas de lui-même
        t.debut = heure(h);
        t.fin = (t.debut + 3) % 24; // sa journée finit dans 2 à 3 heures du jeu : il lui en faut à peu près 2 pour rentrer
        t.memoire.putLong("test_debut", h.getLevel().getDayTime() % 24000);
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Aller && !t.memoire.contains("test_depart")) t.memoire.putLong("test_depart", h.getLevel().getDayTime() % 24000);
            if (t.chezLui(t.blockPosition()) && !t.memoire.contains("test_arrivee")) t.memoire.putLong("test_arrivee", h.getLevel().getDayTime() % 24000);
        });
        t.forcerChunk();
        h.succeedWhen(() -> {
            h.assertTrue(t.chezLui(t.blockPosition()), "il doit être rentré (" + etat(t, bureau) + ")");
            h.assertTrue(t.enService(), "il devait rentrer avant la fin de sa journée (" + etat(t, bureau) + ", parti à " + t.memoire.getLong("test_depart") + ", début du test " + t.memoire.getLong("test_debut") + ")");
            t.discard();
        });
    }

    /** Figé dans un chunk inactif à côté de son ancien chunk (le cas d'Aliénor le 6 octobre, avant la 0.3.0) : le serveur le réveille. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void reveilleSiFige(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 12000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(bureau.east(2)), ItemStack.EMPTY);
        net.minecraft.world.level.ChunkPos c = t.chunkPosition();
        ColoniePnj.TICKETS.forceChunk(h.getLevel(), t, c.x + 1, c.z, true, true); // seul le chunk voisin reste actif
        h.succeedWhen(() -> {
            h.assertTrue(t.tickCount > 60 && ((ServerLevel) t.level()).isPositionEntityTicking(t.blockPosition()), "il doit être réveillé (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /** En expédition (sans lit), loin du bureau quand sa journée est finie : il campe sur place au lieu de rentrer. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void campeEnExpedition(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 6000);
        BlockPos loin = sol(h, 220, 6000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(loin), ItemStack.EMPTY);
        t.debut = (heure(h) + 2) % 24; // sa journée commence dans 2 heures : il est au repos
        t.fin = (heure(h) + 3) % 24;
        t.expedition = true;
        t.forcerChunk();
        h.succeedWhen(() -> {
            h.assertTrue(t.action instanceof Actions.Reposer, "il doit se reposer (" + etat(t, bureau) + ")");
            h.assertTrue(t.blockPosition().distSqr(loin) < 16, "il doit camper là où il est (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /** Pas de bouleau près du bureau, et aucun biome connu où il pousse (monde plat) : il fouille de plus en plus loin, jusqu'aux bouleaux à 100 blocs. */
    @GameTest(template = "vide", timeoutTicks = 12000)
    public static void expeditionTrouveLeBois(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = sol(h, 0, 9000);
        for (int i = 0; i < 12; i++) {
            BlockPos pied = sol(h, (int) (100 * Math.cos(i * Math.PI / 6)), 9000 + (int) (100 * Math.sin(i * Math.PI / 6)));
            for (int y = 0; y < 3; y++) l.setBlockAndUpdate(pied.above(y), Blocks.BIRCH_LOG.defaultBlockState());
            for (BlockPos f : BlockPos.betweenClosed(pied.offset(-1, 2, -1), pied.offset(1, 3, 1)))
                if (l.getBlockState(f).isAir()) l.setBlockAndUpdate(f, Blocks.BIRCH_LEAVES.defaultBlockState());
        }
        Travailleur t = employe(h, "bucheron", bureau, Vec3.atBottomCenterOf(bureau.east()), new ItemStack(Items.IRON_AXE));
        t.commande = "bouleau";
        t.forcerChunk();
        h.succeedWhen(() -> {
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:birch_log")) >= 3, "il doit avoir abattu un bouleau (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /** Le mineur extrait d'abord le minerai demandé qu'il voit autour de lui. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void mineurFilonVisible(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(3, 2, 4, Blocks.COAL_ORE);
        h.setBlock(3, 3, 4, Blocks.COAL_ORE);
        Travailleur t = ouvrier(h, "mineur", 1, 2, 4, new ItemStack(Items.IRON_PICKAXE));
        t.commande = "charbon";
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.COAL_ORE, 3, 2, 4);
            h.assertBlockNotPresent(Blocks.COAL_ORE, 3, 3, 4);
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:coal")) >= 2, "il doit avoir le charbon");
        });
    }

    /** Sans filon en vue, le mineur choisit un sol naturel et descend en escalier jusqu'à la profondeur demandée. */
    @GameTest(template = "grand", timeoutTicks = 3000)
    public static void mineurDescend(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        // Bureau au milieu de la pierre : il creuse le plus près possible du bureau, donc sur la pierre du test.
        Travailleur t = ouvrier(h, "mineur", 18, 11, 18, new ItemStack(Items.DIAMOND_PICKAXE), new BlockPos(12, 11, 12));
        t.niveau = 5;
        t.commande = "charbon";
        t.profondeurForcee = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 32));
        int but = h.absolutePos(new BlockPos(0, 5, 0)).getY();
        h.succeedWhen(() -> h.assertTrue(t.getBlockY() <= but, "il doit être descendu (y=" + t.getBlockY() + ", attendu <= " + but + ", action "
                + (t.action == null ? "-" : t.action.nom) + ", résultat " + t.resultat + ", besoin " + t.besoin
                + ", mémoire " + t.memoire + ")"));
    }

    /** Le bûcheron n'abat que le bois commandé. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void bucheronBoisCommande(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
        arbre(h, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 3, 6);
        arbre(h, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 6, 2);
        Travailleur t = ouvrier(h, "bucheron", 1, 2, 4, new ItemStack(Items.IRON_AXE));
        t.commande = "bouleau";
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.BIRCH_LOG, 6, 2, 2);
            h.assertBlockPresent(Blocks.OAK_LOG, 3, 2, 6);
        });
    }

    static void arbre(GameTestHelper h, net.minecraft.world.level.block.Block bois, net.minecraft.world.level.block.Block feuilles, int x, int z) {
        for (int y = 2; y <= 4; y++) h.setBlock(x, y, z, bois);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 5; y++)
            if (!(dx == 0 && dz == 0 && y == 4)) h.setBlock(x + dx, y, z + dz, feuilles);
    }

    /** Dépôt plein : il range dans le coffre collé au bureau, et la commande compte ce qu'il a livré. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void depotEtCoffreVoisin(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 3, 2, 3, new ItemStack(Items.IRON_PICKAXE));
        Bureau.Entite b = (Bureau.Entite) h.getLevel().getBlockEntity(t.bureau);
        for (int i = 0; i < 27; i++) b.depot.setItem(i, new ItemStack(Items.DIRT, 64));
        h.setBlock(1, 2, 0, Blocks.CHEST);
        t.commande = "pierre";
        t.commandeMode = "quantite";
        t.commandeNombre = 100_000;
        for (int i = 0; i < 27; i++) t.inventaire.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        h.succeedWhen(() -> {
            var coffre = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 0)));
            int n = 0;
            for (int i = 0; i < coffre.getContainerSize(); i++) if (coffre.getItem(i).is(Items.COBBLESTONE)) n += coffre.getItem(i).getCount();
            h.assertTrue(n >= 64 * 20, "le coffre voisin doit avoir reçu la pierre (" + n + ")");
            h.assertTrue(t.commandeLivre >= 64 * 20, "la commande doit compter la pierre livrée (" + t.commandeLivre + ")");
        });
    }

    /** Hors de ses heures, il va se coucher dans le lit près de son bureau. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void reposAuLit(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        var lit = Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH);
        h.setBlock(4, 2, 3, lit.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        h.setBlock(4, 2, 2, lit.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        Travailleur t = ouvrier(h, "mineur", 6, 2, 6, new ItemStack(Items.IRON_PICKAXE));
        t.commande = "charbon";
        int heure = (int) ((h.getLevel().getDayTime() + 6000) % 24000 / 1000);
        t.debut = (heure + 2) % 24; // son service commence dans 2 heures : il est au repos
        t.fin = (heure + 3) % 24;
        h.succeedWhen(() -> h.assertTrue(t.isSleeping(), "il doit dormir dans le lit"));
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
            h.assertTrue(((Bureau.Entite) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(0, 2, 0)))).mort, "son bureau doit le dire mort");
            h.assertBlockPresent(Blocks.CHEST, 3, 2, 4);
        });
    }

    /** Scénario 9 de KOLRAX : du pain en main à 2 blocs d'un PNJ à terre le relève, sans devoir viser sa silhouette. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void releverAvecDuPain(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 4, 2, 4, ItemStack.EMPTY);
        t.hurt(t.damageSources().generic(), 100);
        h.assertTrue(t.aTerre, "il doit être à terre");
        var joueur = h.makeMockServerPlayerInLevel();
        joueur.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); // en créatif, rien ne se consomme
        Vec3 p = h.absoluteVec(new Vec3(2.5, 2, 4.5));
        joueur.moveTo(p.x, p.y, p.z);
        joueur.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 3));
        h.assertTrue(Travailleur.releverAutour(joueur), "le pain doit le relever");
        h.assertFalse(t.aTerre, "il doit être debout");
        h.assertTrue(joueur.getMainHandItem().getCount() == 2, "un pain doit être consommé");
        h.succeed();
    }

    /** Caisse vide → grève (le plantage du 5 octobre) ; la caisse remplie → il se paie et reprend le travail. */
    @GameTest(template = "vide", timeoutTicks = 2000)
    public static void grevePuisReprise(GameTestHelper h) {
        var avant = Reglages.actuel.monnaie;
        Reglages.actuel.monnaie = new java.util.ArrayList<>(java.util.List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 1, 2, 4, ItemStack.EMPTY, new BlockPos(4, 2, 4));
        Bureau.Entite b = (Bureau.Entite) h.getLevel().getBlockEntity(t.bureau);
        t.impayes = 1;
        t.prochainePaie = System.currentTimeMillis() - 1000; // due, mais pas « en retard » : il va lui-même à la caisse vide
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(t.mode == Travailleur.Mode.GREVE, "il doit se mettre en grève"))
                .thenExecute(() -> b.caisse.addItem(new ItemStack(Items.GOLD_INGOT, 9)))
                .thenWaitUntil(() -> h.assertTrue(t.mode == Travailleur.Mode.TRAVAIL && t.impayes == 0, "payé, il doit reprendre"))
                .thenExecute(() -> {
                    h.assertTrue(t.isAlive(), "il doit être vivant");
                    h.assertTrue(Reglages.valeur(b.caisse) == 72 - 64, "il doit avoir pris 64 (2 paies de 32)");
                    Reglages.actuel.monnaie = avant;
                })
                .thenSucceed();
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

    /** Ce que fait pousser chaque biome est lu dans la génération du monde : le bois de la jungle pousse dans la jungle, pas au désert. */
    @GameTest(template = "vide")
    public static void boisEtBiomes(GameTestHelper h) {
        var jungle = Biomes.ouPousse(h.getLevel(), Actions.Filtre.de("minecraft:jungle_log"));
        var bouleau = Biomes.ouPousse(h.getLevel(), Actions.Filtre.de("minecraft:birch_log"));
        h.assertTrue(jungle.contains(net.minecraft.world.level.biome.Biomes.JUNGLE) && jungle.contains(net.minecraft.world.level.biome.Biomes.BAMBOO_JUNGLE), "jungle : " + jungle);
        h.assertTrue(bouleau.contains(net.minecraft.world.level.biome.Biomes.BIRCH_FOREST), "bouleau : " + bouleau);
        h.assertFalse(jungle.contains(net.minecraft.world.level.biome.Biomes.DESERT), "pas de jungle au désert");
        h.succeed();
    }

    /** Le bois demandé pousse à 600 blocs et sa journée est courte : il décide seul de partir en expédition (sans lit). */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void partEnExpeditionSansLit(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 15000);
        Travailleur t = employe(h, "bucheron", bureau, Vec3.atBottomCenterOf(bureau.east()), new ItemStack(Items.IRON_AXE));
        t.commande = "bouleau";
        t.debut = heure(h);
        t.fin = (t.debut + 4) % 24; // 4 heures de travail : trop court pour un aller-retour de 1 200 blocs
        t.memoire.putLong("coin_bouleau", bureau.offset(600, 0, 0).asLong()); // un coin de bouleaux qu'il connaît
        t.forcerChunk();
        h.succeedWhen(() -> {
            h.assertTrue(t.expedition, "il doit partir en expédition (" + etat(t, bureau) + ")");
            h.assertTrue(distance(t, bureau) > 60, "il doit être en route (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /** Coincé au bout d'une galerie dont la sortie part à l'opposé du bureau : il trouve la sortie au lieu de buter contre la roche. */
    @GameTest(template = "grand", timeoutTicks = 2400)
    public static void sortParLesGaleries(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 12; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int x = 4; x <= 20; x++) for (int y = 4; y <= 5; y++) h.setBlock(x, y, 12, Blocks.AIR);             // la galerie, vers l'est
        for (int k = 1; k <= 8; k++) for (int y = 4 + k; y <= 6 + k; y++) h.setBlock(20, y, 12 + k, Blocks.AIR); // l'escalier, vers le sud
        Travailleur t = ouvrier(h, "mineur", 4, 4, 12, ItemStack.EMPTY, new BlockPos(4, 13, 4)); // bureau au nord-ouest, au-dessus
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + etat(t, t.bureau) + ")"));
    }

    /** Muré sous terre, sans aucune galerie : il creuse un escalier jusqu'à la surface, puis rejoint son bureau. */
    @GameTest(template = "grand", timeoutTicks = 3000)
    public static void creuseVersLaSurface(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 12; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int y = 4; y <= 5; y++) h.setBlock(12, y, 12, Blocks.AIR);
        Travailleur t = ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 13, 3));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit avoir creusé jusqu'au bureau (" + etat(t, t.bureau) + ")"));
    }

    /** Son chef frappe un villageois : le guerrier ne s'en mêle pas (il ne tue que les monstres et les agresseurs). */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void guerrierEpargneVillageois(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "guerrier", 2, 2, 4, new ItemStack(Items.IRON_SWORD));
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(3.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        t.chef = chef.getUUID();
        t.commande = "escorter";
        var villageois = h.spawn(net.minecraft.world.entity.EntityType.VILLAGER, 5, 2, 4);
        villageois.setNoAi(true);
        h.runAtTickTime(h.getTick() + 5, () -> chef.setLastHurtMob(villageois));
        h.runAtTickTime(h.getTick() + 100, () -> {
            h.assertTrue(villageois.isAlive() && villageois.getHealth() == villageois.getMaxHealth(), "le guerrier ne doit pas toucher au villageois");
            h.succeed();
        });
    }

    /** Ce que le bureau montre de lui : au travail, se repose, distance. */
    @GameTest(template = "vide")
    public static void statutAuBureau(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 4, 2, 4, ItemStack.EMPTY);
        h.assertTrue(t.statut().equals("Au travail"), "statut : " + t.statut());
        t.debut = (heure(h) + 2) % 24;
        t.fin = (heure(h) + 3) % 24;
        h.assertTrue(t.statut().equals("Se repose"), "hors de ses heures : " + t.statut());
        t.expedition = true;
        h.assertTrue(t.statut().equals("En expédition"), "en expédition : " + t.statut());
        h.assertTrue((int) t.distanceAuBureau() == 6, "distance : " + t.distanceAuBureau());
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

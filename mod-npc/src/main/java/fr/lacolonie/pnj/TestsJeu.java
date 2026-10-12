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

import java.util.List;

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
        // Pas de paie pendant les tests (sauf ceux qui la testent).
        return Travailleur.auTravail(h.getLevel(), metier, bureau, p, java.util.UUID.randomUUID(), outil);
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

    /** En expédition, épuisé en pleine marche vers plus loin (Aliénor le 6 octobre) : il s'arrête et campe. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void sArreteEpuiseEnExpedition(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 18000);
        BlockPos loin = sol(h, 220, 18000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(loin), ItemStack.EMPTY);
        t.expedition = true;
        t.fatigue = 100;
        t.action = new Actions.Aller(Vec3.atBottomCenterOf(sol(h, 800, 18000)), 4, true);
        t.forcerChunk();
        h.succeedWhen(() -> {
            h.assertTrue(t.action instanceof Actions.Reposer, "il doit camper (" + etat(t, bureau) + ")");
            h.assertTrue(t.blockPosition().distSqr(loin) < 100, "sans aller plus loin (" + etat(t, bureau) + ")");
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
        t.mission("bouleau", "stock", 64);
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
        t.mission("charbon", "stock", 64);
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
        t.mission("charbon", "stock", 64);
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
        t.mission("bouleau", "stock", 64);
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
        t.mission("pierre", "quantite", 100_000);
        for (int i = 0; i < 27; i++) t.inventaire.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        h.succeedWhen(() -> {
            var coffre = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 0)));
            int n = 0;
            for (int i = 0; i < coffre.getContainerSize(); i++) if (coffre.getItem(i).is(Items.COBBLESTONE)) n += coffre.getItem(i).getCount();
            h.assertTrue(n >= 64 * 20, "le coffre voisin doit avoir reçu la pierre (" + n + ")");
            h.assertTrue(t.missions.get(0).livre >= 64 * 20, "la commande doit compter la pierre livrée (" + t.missions.get(0).livre + ")");
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
        t.mission("charbon", "stock", 64);
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
            var membre = Courrier.de(h.getLevel().getServer()).effectifs.get(t.chef).get(t.getUUID());
            h.assertTrue(membre != null && !membre.mort.isEmpty(), "le poste doit le dire mort");
            h.assertTrue(Courrier.de(h.getLevel().getServer()).boite(t.chef).stream().anyMatch(l -> l.objet.startsWith("Avis de décès")), "un avis de décès doit arriver au poste");
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

    /** Sans paie (tests, banc de charge) : il ne compte jamais de paie en retard (avant, le calcul débordait dès le premier tick). */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void sansPaieJamaisEnRetard(GameTestHelper h) {
        Travailleur t = ouvrier(h, "mineur", 2, 2, 2, ItemStack.EMPTY);
        h.runAtTickTime(h.getTick() + 60, () -> {
            h.assertTrue(t.impayes == 0 && t.prochainePaie == Monnaie.SANS_PAIE, "pas de paie en retard (impayés " + t.impayes + ", prochaine paie " + t.prochainePaie + ")");
            t.discard();
            h.succeed();
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
        t.mission("bouleau", "stock", 64);
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
        h.succeedWhen(() -> h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + etat(t, t.bureau) + ", " + t.diag + ") " + String.join(" / ", Itineraire.diagnostic(t))));
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

    /** Au fond d'un puits à ciel ouvert de 3 blocs (trop haut pour sauter) : il creuse des marches pour en sortir. */
    @GameTest(template = "grand", timeoutTicks = 2000)
    public static void sortDuPuits(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int y = 4; y <= 6; y++) h.setBlock(12, y, 12, Blocks.AIR);
        Travailleur t = ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 7, 3));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti du puits (" + etat(t, t.bureau) + ", " + t.diag + ") " + String.join(" / ", Itineraire.diagnostic(t))));
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
        t.mission("escorter", "stock", 64);
        var villageois = h.spawn(net.minecraft.world.entity.EntityType.VILLAGER, 5, 2, 4);
        villageois.setNoAi(true);
        h.runAtTickTime(h.getTick() + 5, () -> chef.setLastHurtMob(villageois));
        h.runAtTickTime(h.getTick() + 100, () -> {
            h.assertTrue(villageois.isAlive() && villageois.getHealth() == villageois.getMaxHealth(), "le guerrier ne doit pas toucher au villageois");
            h.succeed();
        });
    }

    /**
     * Un joueur qui n'est pas son chef frappe un guerrier : il ne riposte pas (il ne frappe un joueur que sur ordre de
     * son chef), il recule. Avant la 0.8.3, il l'attaquait comme un monstre.
     */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void guerrierNeRipostePasAUnJoueur(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "guerrier", 2, 2, 4, new ItemStack(Items.IRON_SWORD));
        Guerrier.armer(t);
        var joueur = h.makeMockServerPlayerInLevel();
        joueur.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); // en créatif, il ne prendrait aucun coup
        Vec3 p = h.absoluteVec(new Vec3(3.5, 2, 4.5));
        joueur.moveTo(p.x, p.y, p.z);
        boolean[] vu = {false, false}; // il a lancé une attaque contre lui ; il a reculé
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Attaquer a && a.cible == joueur) vu[0] = true;
            if (t.action instanceof Actions.Fuir) vu[1] = true;
        });
        // Frappé avant sa première décision : sinon, selon son numéro d'entité, il pouvait être déjà parti vers un poste de
        // garde (il ne réagit qu'entre deux actions) et le test échouait au hasard (vu le 11/10 en ajoutant un lot de tests).
        t.hurt(t.damageSources().playerAttack(joueur), 1);
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(vu[0], "il ne doit pas attaquer le joueur");
            h.assertTrue(joueur.getHealth() == joueur.getMaxHealth(), "le joueur ne doit prendre aucun coup (vie " + joueur.getHealth() + ")");
            h.assertTrue(vu[1], "il doit reculer (" + (t.action == null ? "-" : t.action.nom) + ")");
            t.discard();
            h.succeed();
        });
    }

    /**
     * Un messager le hèle, puis un monstre le frappe : il cesse d'écouter tout de suite et s'enfuit ; hélé de nouveau,
     * il ne s'arrête pas. Avant la 0.8.3, il restait figé sans défense jusqu'au bout de l'écoute.
     */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void ouvrierFrappeNeResteFigePas(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY);
        Travailleur messager = ouvrier(h, "bucheron", 6, 2, 4, ItemStack.EMPTY);
        // Sa première décision vient après le coup : sinon, selon son numéro d'entité, il pouvait être déjà parti attendre
        // 20 s (mineur sans commande, il ne réagit qu'entre deux actions) et le test échouait au hasard (vu le 11/10).
        t.pauseCerveau = 8;
        boolean[] fui = {false};
        h.onEachTick(() -> { if (t.action instanceof Actions.Fuir) fui[0] = true; });
        net.minecraft.world.entity.monster.Zombie[] z = new net.minecraft.world.entity.monster.Zombie[1];
        h.runAtTickTime(h.getTick() + 5, () -> {
            t.ecouter(messager, 100);
            h.assertTrue(t.ecouteTicks == 100, "sans danger, il doit écouter le messager (" + t.ecouteTicks + ")");
            z[0] = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 2, 2, 7);
            z[0].setNoAi(true);
            t.setLastHurtByMob(z[0]);
        });
        h.runAtTickTime(h.getTick() + 7, () -> {
            h.assertTrue(t.ecouteTicks == 0, "frappé, il doit cesser d'écouter (encore " + t.ecouteTicks + " ticks)");
            t.ecouter(messager, 20);
            h.assertTrue(t.ecouteTicks == 0, "frappé, il ne doit pas s'arrêter pour écouter (" + t.ecouteTicks + " ticks)");
        });
        h.runAtTickTime(h.getTick() + 60, () -> {
            h.assertTrue(fui[0], "il doit s'enfuir (" + (t.action == null ? "-" : t.action.nom) + ")");
            t.discard();
            messager.discard();
            z[0].discard();
            h.succeed();
        });
    }

    /**
     * Frappé par un monstre en pleine action longue (il attend 20 s), il la lâche et s'enfuit tout de suite ; frappé par
     * son chef, il ne lâche rien. Avant la 0.8.4, il ne réagissait qu'entre deux actions (un mineur qui attendait ou
     * marchait finissait à terre).
     */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void frappeEnPleineAction(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 2, 2, 2, ItemStack.EMPTY);
        Travailleur u = ouvrier(h, "mineur", 2, 2, 7, ItemStack.EMPTY);
        var chef = h.makeMockServerPlayerInLevel();
        u.chef = chef.getUUID();
        Cerveau.lancer(t, new Actions.Attendre(400));
        Actions.Action tache = new Actions.Attendre(400);
        Cerveau.lancer(u, tache);
        var z = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 6, 2, 2);
        z.setNoAi(true);
        int[] fuite = {-1};
        h.onEachTick(() -> { if (fuite[0] < 0 && t.action instanceof Actions.Fuir) fuite[0] = (int) h.getTick(); });
        int coup = (int) h.getTick() + 5;
        h.runAtTickTime(coup, () -> {
            t.hurt(t.damageSources().mobAttack(z), 1);
            u.hurt(u.damageSources().playerAttack(chef), 1);
        });
        h.runAtTickTime(coup + 40, () -> {
            h.assertTrue(fuite[0] >= 0 && fuite[0] - coup <= 40, "frappé par un monstre, il doit s'enfuir dans les 2 s (" + (t.action == null ? "-" : t.action.nom) + ")");
            h.assertTrue(u.action == tache, "frappé par son chef, il ne doit pas lâcher son action (" + (u.action == null ? "-" : u.action.nom) + ")");
            t.discard();
            u.discard();
            z.discard();
            h.succeed();
        });
    }

    /**
     * Un golem de fer et un loup apprivoisé frappent chacun un guerrier : il ne lève pas son arme sur eux (sans ordre,
     * un PNJ ne frappe que des monstres), il recule. Avant, il les attaquait comme des monstres.
     */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void guerrierNAttaquePasLesBetes(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur g1 = ouvrier(h, "guerrier", 2, 2, 2, new ItemStack(Items.IRON_SWORD));
        Travailleur g2 = ouvrier(h, "guerrier", 2, 2, 6, new ItemStack(Items.IRON_SWORD));
        Guerrier.armer(g1);
        Guerrier.armer(g2);
        var golem = h.spawn(net.minecraft.world.entity.EntityType.IRON_GOLEM, 6, 2, 2);
        golem.setNoAi(true);
        var loup = h.spawn(net.minecraft.world.entity.EntityType.WOLF, 6, 2, 6);
        loup.setNoAi(true);
        loup.tame(h.makeMockServerPlayerInLevel());
        boolean[] vu = {false, false, false, false}; // g1 attaque le golem, g2 attaque le loup ; g1 recule, g2 recule
        h.onEachTick(() -> {
            if (g1.action instanceof Actions.Attaquer a && a.cible == golem) vu[0] = true;
            if (g2.action instanceof Actions.Attaquer a && a.cible == loup) vu[1] = true;
            if (g1.action instanceof Actions.Fuir) vu[2] = true;
            if (g2.action instanceof Actions.Fuir) vu[3] = true;
        });
        // Frappés avant leur première décision (comme guerrierNeRipostePasAUnJoueur).
        g1.hurt(g1.damageSources().mobAttack(golem), 1);
        g2.hurt(g2.damageSources().mobAttack(loup), 1);
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(vu[0], "il ne doit pas attaquer le golem");
            h.assertFalse(vu[1], "il ne doit pas attaquer le loup apprivoisé");
            h.assertTrue(golem.getHealth() == golem.getMaxHealth() && loup.getHealth() == loup.getMaxHealth(), "aucune bête ne doit être blessée (golem "
                    + golem.getHealth() + ", loup " + loup.getHealth() + ")");
            h.assertTrue(vu[2] && vu[3], "ils doivent reculer (" + (g1.action == null ? "-" : g1.action.nom) + ", " + (g2.action == null ? "-" : g2.action.nom) + ")");
            g1.discard();
            g2.discard();
            golem.discard();
            loup.discard();
            h.succeed();
        });
    }

    /** Un gréviste frappé par un monstre s'enfuit comme les autres. Avant, la grève passait avant : il restait sur place. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void grevisteFrappeSEnfuit(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY);
        t.mode = Travailleur.Mode.GREVE;
        var z = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 6, 2, 4);
        z.setNoAi(true);
        boolean[] fui = {false};
        h.onEachTick(() -> { if (t.action instanceof Actions.Fuir) fui[0] = true; });
        t.hurt(t.damageSources().mobAttack(z), 1);
        h.runAtTickTime(h.getTick() + 60, () -> {
            h.assertTrue(fui[0], "en grève, frappé, il doit s'enfuir (" + (t.action == null ? "-" : t.action.nom) + ", " + t.mode + ")");
            t.discard();
            z.discard();
            h.succeed();
        });
    }

    /**
     * Une lettre arrive par corbeau pendant qu'il fuit un monstre : il continue de fuir (avant, il s'arrêtait 3 s pour
     * la lire, sans défense), et la lit une fois au calme, sans la perdre.
     */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void lettreLueAuCalme(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = sol(h, 0, 96000);
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur t = employe(h, "bucheron", bureau, Vec3.atBottomCenterOf(sol(h, 8, 96000)), ItemStack.EMPTY);
        t.chef = chef.getUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(t.chef, l, bureau);
        t.mode = Travailleur.Mode.ATTENDRE; // il ne bouge que pour fuir
        t.forcerChunk();
        var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(l);
        Vec3 zp = Vec3.atBottomCenterOf(sol(h, 12, 96000));
        z.moveTo(zp.x, zp.y, zp.z);
        z.setNoAi(true);
        l.addFreshEntity(z);
        Actions.Action fuite = new Actions.Fuir(z);
        Cerveau.lancer(t, fuite);
        Courrier.Lettre lettre = c.nouvelle(chef.getName().getString(), chef.getUUID(), t.getUUID(), "Lettre du chef", "Ramène-moi 10 bûches de bouleau.", l);
        lettre.duChef = true;
        t.recevoirLettre(lettre);
        h.assertTrue(t.action == fuite && t.lettreEnMain == null, "en fuite, la lettre ne doit pas l'arrêter (action "
                + (t.action == null ? "-" : t.action.nom) + ", résultat " + t.resultat + ")");
        boolean[] fige = {false};
        h.onEachTick(() -> { if (t.lettreEnMain != null && t.action instanceof Actions.Fuir) fige[0] = true; });
        h.runAtTickTime(h.getTick() + 3, z::discard);
        h.succeedWhen(() -> {
            h.assertFalse(fige[0], "il ne doit pas lire en fuyant");
            h.assertTrue(t.missions.stream().anyMatch(m -> m.id.equals("bouleau") && m.nombre == 10), "au calme, il doit lire la lettre (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /**
     * Le corbeau arrive en biais : entre le tronçon du poste et le tronçon actif d'où il part, son trajet coupe sur moins
     * d'un bloc le coin d'un tronçon figé. Avant, le trajet n'était vérifié que tous les 4 blocs : il partait de trop
     * loin, se figeait dans ce coin, et la lettre n'arrivait qu'au bout de 30 s, par le secours du courrier.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void corbeauNeCoupePasUnCoinFige(GameTestHelper h) {
        var l = h.getLevel();
        BlockPos o = sol(h, 0, 99000);
        int x = (o.getX() & ~15) + 12, z = (o.getZ() & ~15) + 12;
        BlockPos poste = new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        var tronc = new net.minecraft.world.level.ChunkPos(poste);
        l.setChunkForced(tronc.x, tronc.z, true);
        l.setChunkForced(tronc.x + 1, tronc.z + 1, true); // en diagonale : les deux autres voisins restent figés
        l.setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        var chef = h.makeMockServerPlayerInLevel();
        chef.moveTo(poste.getX() + 0.5, poste.getY(), poste.getZ() - 1.5); // le témoin
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(chef.getUUID(), l, poste);
        Courrier.Lettre lettre = c.nouvelle("Aliénor", null, chef.getUUID(), "Essai en biais", "Bonjour chef.", l);
        c.envoyerCorbeau(l, poste.offset(31, 0, 26), chef.getUUID(), lettre, true); // cap à 40° : le coin du tronçon voisin, sur 0,9 bloc
        h.succeedWhen(() -> {
            h.assertTrue(c.boite(chef.getUUID()).stream().anyMatch(x2 -> x2.objet.equals("Essai en biais")), "la lettre doit être livrée par le corbeau posé");
            l.setChunkForced(tronc.x, tronc.z, false);
            l.setChunkForced(tronc.x + 1, tronc.z + 1, false);
        });
    }

    /**
     * Muré sous terre, et sous chaque marche qu'il pourrait creuser, un puits : il ne creuse pas une marche sans sol
     * (il y tomberait). Avant, il creusait la marche, sautait dessus et tombait au fond du puits.
     */
    @GameTest(template = "grand", timeoutTicks = 400)
    public static void neCreusePasUneMarcheSurLeVide(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 12; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int y = 4; y <= 5; y++) h.setBlock(12, y, 12, Blocks.AIR);
        for (Direction d : Direction.Plane.HORIZONTAL) for (int y = 1; y <= 4; y++) h.setBlock(12 + d.getStepX(), y, 12 + d.getStepZ(), Blocks.AIR);
        Travailleur t = ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 13, 3));
        t.mode = Travailleur.Mode.ATTENDRE; // il ne fait rien d'autre que chercher à sortir
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        double fond = h.absoluteVec(new Vec3(0, 4, 0)).y - 0.5;
        boolean[] tombe = {false};
        h.onEachTick(() -> { if (t.getY() < fond) tombe[0] = true; });
        h.runAtTickTime(h.getTick() + 300, () -> {
            String e = etat(t, t.bureau) + ", " + t.diag;
            t.discard();
            h.assertFalse(tombe[0], "il ne doit pas tomber dans un puits (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Envoyé sur un îlot entouré d'une douve de lave d'un bloc : le chemin s'arrête au bord, à deux blocs du but. Il
     * reste au bord et finit par renoncer, sans jamais entrer dans la lave. Dans la première 0.8.4, au bout de ce chemin
     * inachevé, il marchait tout droit vers le but… et dans la lave.
     */
    @GameTest(template = "vide", timeoutTicks = 400, skyAccess = true)
    public static void neMarchePasDansLaLave(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos ilot = sol(h, 0, 93000);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) l.setBlockAndUpdate(ilot.offset(dx, -1, dz), Blocks.LAVA.defaultBlockState());
        Travailleur t = employe(h, "mineur", sol(h, -10, 93000), Vec3.atBottomCenterOf(sol(h, -8, 93000)), ItemStack.EMPTY);
        t.mode = Travailleur.Mode.ATTENDRE; // le cerveau ne lui donne rien d'autre à faire
        t.forcerChunk();
        Actions.Action aller = new Actions.Aller(Vec3.atBottomCenterOf(ilot), 1);
        Cerveau.lancer(t, aller);
        boolean[] lave = {false};
        h.onEachTick(() -> { if (t.isInLava() || t.getY() < ilot.getY() - 0.4) lave[0] = true; });
        h.runAtTickTime(h.getTick() + 220, () -> {
            String e = etat(t, t.bureau) + ", " + t.diag + ", feu " + t.isOnFire();
            double bord = Itineraire.horizontale(t.position(), Vec3.atBottomCenterOf(ilot));
            t.discard();
            h.assertFalse(lave[0], "il ne doit jamais entrer dans la lave (" + e + ")");
            // Le bord est à 2 blocs du centre de l'îlot : il doit y être arrivé (pas resté au départ, à 8 blocs).
            h.assertTrue(bord < 3, "il doit être arrivé au bord de la lave (à " + String.format("%.1f", bord) + " blocs de l'îlot, " + e + ")");
            h.succeed();
        });
    }

    /** Un zombie enfermé sous ses pieds, hors de vue (Raoul le 6 octobre) : le guerrier ne tourne pas en rond au-dessus. */
    @GameTest(template = "grand", timeoutTicks = 300)
    public static void guerrierNeTournePasEnRond(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(12, 2, 12, Blocks.AIR);
        h.setBlock(12, 3, 12, Blocks.AIR);
        var zombie = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 12, 2, 12);
        zombie.setNoAi(true);
        Travailleur t = ouvrier(h, "guerrier", 12, 7, 12, new ItemStack(Items.IRON_SWORD), new BlockPos(12, 7, 9));
        t.mode = Travailleur.Mode.ATTENDRE; // il ne bouge que pour se battre
        float[] tour = {0, t.getYRot()};
        h.onEachTick(() -> { tour[0] += Math.abs(net.minecraft.util.Mth.wrapDegrees(t.getYRot() - tour[1])); tour[1] = t.getYRot(); });
        h.runAtTickTime(h.getTick() + 240, () -> {
            h.assertTrue(tour[0] < 360, "il tourne en rond (" + (int) tour[0] + "° en 12 s, " + etat(t, t.bureau) + ", " + t.diag + ")");
            t.discard();
            zombie.discard();
            h.succeed();
        });
    }

    /** Un zombie qu'il voit, perché sur un pilier qu'il ne peut pas escalader : il le laisse au lieu de tourner autour. */
    @GameTest(template = "grand", timeoutTicks = 300, skyAccess = true)
    public static void guerrierLaisseCibleInaccessible(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int y = 7; y <= 9; y++) h.setBlock(15, y, 12, Blocks.STONE);
        var zombie = h.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 15, 10, 12);
        zombie.setNoAi(true);
        Travailleur t = ouvrier(h, "guerrier", 12, 7, 12, new ItemStack(Items.IRON_SWORD), new BlockPos(12, 7, 9));
        t.mode = Travailleur.Mode.ATTENDRE;
        float[] tour = {0, t.getYRot()};
        h.onEachTick(() -> { tour[0] += Math.abs(net.minecraft.util.Mth.wrapDegrees(t.getYRot() - tour[1])); tour[1] = t.getYRot(); });
        h.runAtTickTime(h.getTick() + 240, () -> {
            h.assertTrue(tour[0] < 360, "il tourne autour (" + (int) tour[0] + "° en 12 s, " + etat(t, t.bureau) + ", " + t.diag + ")");
            h.assertFalse(t.action instanceof Actions.Attaquer, "il doit avoir laissé ce zombie (" + etat(t, t.bureau) + ", " + t.diag + ")");
            t.discard();
            zombie.discard();
            h.succeed();
        });
    }

    /** Un canal entre lui et son bureau (scénario 24) : il le traverse à la nage au lieu de faire le tour, en courant. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void traverseALaNage(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = sol(h, 0, 21000);
        for (int x = 8; x <= 16; x++)
            for (int z = -40; z <= 40; z++) {
                BlockPos s = sol(h, x, 21000 + z);
                l.setBlockAndUpdate(s.below(), Blocks.WATER.defaultBlockState());
                l.setBlockAndUpdate(s.below(2), Blocks.WATER.defaultBlockState());
            }
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(sol(h, 26, 21000)), ItemStack.EMPTY);
        boolean[] vu = new boolean[3];
        h.onEachTick(() -> { if (t.isInWater()) vu[0] = true; if (t.isSprinting()) vu[1] = true; if (t.getPose() == net.minecraft.world.entity.Pose.SWIMMING) vu[2] = true; });
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(bureau), 3));
        h.succeedWhen(() -> {
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 16, "il doit être arrivé au bureau (" + etat(t, bureau) + ", " + t.diag + ")");
            h.assertTrue(vu[0], "il devait traverser à la nage");
            h.assertTrue(vu[1], "il devait courir");
            h.assertTrue(vu[2], "il devait nager allongé, comme un joueur");
            t.discard();
        });
    }

    /** Un bras de mer entre lui et son bureau, et un bateau dans son sac : il traverse en bateau, puis le remet dans son sac. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void traverseEnBateau(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = sol(h, 0, 24000);
        for (int x = 6; x <= 46; x++)
            for (int z = -50; z <= 50; z++) {
                BlockPos s = sol(h, x, 24000 + z);
                l.setBlockAndUpdate(s.below(), Blocks.WATER.defaultBlockState());
                l.setBlockAndUpdate(s.below(2), Blocks.WATER.defaultBlockState());
            }
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(sol(h, 50, 24000)), new ItemStack(Items.SPRUCE_BOAT));
        boolean[] enBateau = {false};
        h.onEachTick(() -> { if (t.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) enBateau[0] = true; });
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(bureau), 3));
        h.succeedWhen(() -> {
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 16, "il doit être arrivé au bureau (" + etat(t, bureau) + ", " + t.diag + ")");
            h.assertTrue(enBateau[0], "il devait traverser en bateau");
            h.assertTrue(!t.isPassenger() && t.compter(Actions.Filtre.de("#minecraft:boats")) == 1, "son bateau doit être de nouveau dans son sac");
            h.assertTrue(l.getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class, t.getBoundingBox().inflate(80)).isEmpty(), "il ne doit pas laisser de bateau derrière lui");
            t.discard();
        });
    }

    /** De l'eau devant la 5e marche de son escalier : l'escalier tourne, il ne remonte pas creuser ailleurs (Bérengère le 6 octobre). */
    @GameTest(template = "grand", timeoutTicks = 3000)
    public static void escalierContourneLEau(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(18, 6, 12, Blocks.WATER); // une poche d'eau à l'est de la 5e marche
        Travailleur t = ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.DIAMOND_PICKAXE), new BlockPos(6, 11, 6));
        t.niveau = 5;
        t.mission("charbon", "stock", 64);
        t.profondeurForcee = h.absolutePos(new BlockPos(0, 4, 0)).getY();
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 32));
        t.memoire.putString("chantier", "charbon@" + Mineur.cle(t.bureau) + "/" + t.profondeurForcee);
        t.memoire.putLong("puits", h.absolutePos(new BlockPos(12, 11, 12)).asLong());
        t.memoire.putInt("marche", 0);
        int but = h.absolutePos(new BlockPos(0, 5, 0)).getY();
        int[] plusBas = {Integer.MAX_VALUE};
        h.onEachTick(() -> {
            plusBas[0] = Math.min(plusBas[0], t.getBlockY());
            h.assertTrue(t.getBlockY() <= plusBas[0] + 2, "il est remonté (y=" + t.getBlockY() + " après " + plusBas[0] + ", mémoire " + t.memoire + ")");
        });
        h.succeedWhen(() -> h.assertTrue(t.getBlockY() <= but, "il doit être descendu (y=" + t.getBlockY() + ", attendu <= " + but + ", " + etat(t, t.bureau) + ")"));
    }

    // --- 0.5.0 : poste commun, missions, courrier, corbeau, conversation ---------------------------------------

    /** Un chef avec un poste : ses ouvriers s'y rattachent seuls, et il ne peut pas en poser un deuxième. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void unSeulPosteParJoueur(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        var chef = h.makeMockServerPlayerInLevel();
        BlockPos poste = h.absolutePos(new BlockPos(1, 2, 1));
        h.getLevel().setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        ((Bureau.Entite) h.getLevel().getBlockEntity(poste)).proprietaire = chef.getUUID();
        Courrier.de(h.getLevel().getServer()).definirPoste(chef.getUUID(), h.getLevel(), poste);
        Travailleur a = ouvrier(h, "mineur", 5, 2, 5, ItemStack.EMPTY), b = ouvrier(h, "bucheron", 6, 2, 6, ItemStack.EMPTY);
        for (Travailleur t : List.of(a, b)) { t.chef = chef.getUUID(); t.bureau = null; }
        var clic = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(poste.east(4)), Direction.UP, poste.east(4).below(), false);
        var ctx = new net.minecraft.world.item.context.BlockPlaceContext(chef, net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ColoniePnj.BUREAU_OBJET.get()), clic);
        h.assertTrue(ColoniePnj.BUREAU.get().getStateForPlacement(ctx) == null, "un deuxième poste doit être refusé");
        h.succeedWhen(() -> {
            h.assertTrue(poste.equals(a.bureau) && poste.equals(b.bureau), "ses ouvriers doivent se rattacher au poste (" + a.bureau + ", " + b.bureau + ")");
            a.discard();
            b.discard();
        });
    }

    /** Ses missions, dans l'ordre : chaque quantité livrée est retirée, et il prévient son chef (une lettre, le chef n'étant pas là). */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void missionsALaSuite(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 2, 2, 2, new ItemStack(Items.IRON_PICKAXE));
        t.missions.add(new Travailleur.Mission("pierre", "quantite", 5));
        t.missions.add(new Travailleur.Mission("charbon", "quantite", 3));
        t.missions.add(new Travailleur.Mission("fer", "stock", 16));
        h.assertTrue(t.missionActive().id.equals("pierre"), "il commence par la première");
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 5));
        t.inventaire.addItem(new ItemStack(Items.COAL, 3));
        Cerveau.lancer(t, new Actions.Deposer(List.of()));
        h.succeedWhen(() -> {
            h.assertTrue(t.missions.size() == 1 && t.missions.get(0).id.equals("fer"), "il ne doit rester que le stock de fer (" + t.missions.size() + ")");
            h.assertTrue(Courrier.de(h.getLevel().getServer()).boite(t.chef).stream().anyMatch(l -> l.objet.equals("Mission terminée"))
                    || !t.lettresADeposer.isEmpty(), "il doit écrire qu'il a fini");
            t.discard();
        });
    }

    /** Près du poste, il apporte sa lettre à pied, une lettre à la main, et la pose au poste. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void lettreApporteeAPied(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 27000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(sol(h, 20, 27000)), ItemStack.EMPTY);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), bureau);
        t.forcerChunk();
        Courrier.ecrire(t, "torches");
        h.assertTrue(t.lettresADeposer.size() == 1, "près du poste, il doit garder la lettre pour l'apporter");
        h.succeedWhen(() -> {
            h.assertTrue(t.lettresADeposer.isEmpty() && c.boite(t.chef).stream().anyMatch(l -> l.objet.equals("Plus de torches")),
                    "la lettre doit être au poste (" + etat(t, bureau) + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 25, "il doit l'avoir apportée lui-même");
            t.discard();
        });
    }

    /** Au loin, il envoie un corbeau : la lettre arrive au poste au bout du temps de vol (15 blocs par seconde). */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void lettreParCorbeau(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 30000);
        Travailleur t = employe(h, "mineur", bureau, Vec3.atBottomCenterOf(sol(h, 300, 30000)), ItemStack.EMPTY);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), bureau);
        t.forcerChunk();
        Courrier.ecrire(t, "faim");
        h.assertTrue(t.lettresADeposer.isEmpty() && c.vols.stream().anyMatch(v -> v.lettre.objet.equals("J'ai faim")), "un corbeau doit être parti");
        long depart = h.getLevel().getGameTime();
        h.succeedWhen(() -> {
            h.assertTrue(c.boite(t.chef).stream().anyMatch(l -> l.objet.equals("J'ai faim")), "la lettre doit arriver au poste");
            h.assertTrue(h.getLevel().getGameTime() - depart >= 300 / Courrier.VITESSE_CORBEAU * 20 - 25, "pas avant la fin du vol");
            t.discard();
        });
    }

    /** Le chef écrit « Rentre au poste » à un ouvrier au loin : un corbeau la lui porte, il la lit, rentre et répond. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void ordreParLettre(GameTestHelper h) {
        BlockPos bureau = sol(h, 0, 33000);
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur t = employe(h, "bucheron", bureau, Vec3.atBottomCenterOf(sol(h, 150, 33000)), ItemStack.EMPTY);
        t.chef = chef.getUUID();
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), bureau);
        t.mode = Travailleur.Mode.ATTENDRE; // il attend là-bas, jusqu'à la lettre
        t.forcerChunk();
        Courrier.ecrireAuPnj(chef, t, "Rentre au poste, s'il te plaît. Et ramène-moi 10 bûches de bouleau.");
        h.succeedWhen(() -> {
            h.assertTrue(t.missions.stream().anyMatch(m -> m.id.equals("bouleau") && m.nombre == 10), "il doit avoir compris sa mission (" + t.missions.size() + ")");
            h.assertTrue(c.boite(t.chef).stream().anyMatch(l -> l.objet.equals("Des nouvelles")) || c.vols.stream().anyMatch(v -> v.lettre.objet.equals("Des nouvelles")),
                    "il doit avoir répondu");
            h.assertTrue(t.chezLui(t.blockPosition()), "il doit être rentré (" + etat(t, bureau) + ")");
            t.discard();
        });
    }

    /** On lui parle : il obéit et répond (sans boutons). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void conversation(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        Travailleur t = ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY);
        t.chef = chef.getUUID();
        t.parler(chef, "Suis-moi !");
        h.assertTrue(t.mode == Travailleur.Mode.SUIVRE, "suis-moi");
        t.parler(chef, "Arrête de me suivre");
        h.assertTrue(t.mode == Travailleur.Mode.TRAVAIL, "arrête de me suivre");
        t.parler(chef, "Ramène 10 fers et 1 diamant");
        h.assertTrue(t.missions.size() == 2 && t.missions.get(0).id.equals("fer") && t.missions.get(0).nombre == 10 && t.missions.get(1).id.equals("diamant"), "missions");
        t.parler(chef, "Travaille de 8 h à 20 h");
        h.assertTrue(t.debut == 8 && t.fin == 20, "horaires");
        t.parler(chef, "Tu es viré");
        h.assertTrue(t.chef != null, "il doit d'abord demander confirmation");
        t.parler(chef, "oui");
        h.assertTrue(t.chef == null && t.mode == Travailleur.Mode.CHOMEUR, "renvoyé après confirmation");
        t.discard();
        h.succeed();
    }

    /** Un corbeau qu'on peut voir : il arrive au poste, s'y pose, laisse la lettre et repart. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void corbeauSePoseAuPoste(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        var chef = h.makeMockServerPlayerInLevel();
        BlockPos poste = h.absolutePos(new BlockPos(4, 2, 4));
        chef.moveTo(poste.getX() + 2.5, poste.getY(), poste.getZ() + 0.5); // le témoin, au poste (sinon il reste au point d'apparition du monde)
        h.getLevel().setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(chef.getUUID(), h.getLevel(), poste);
        Courrier.Lettre l = c.nouvelle("Aliénor", null, chef.getUUID(), "Essai", "Bonjour chef.", h.getLevel());
        c.envoyerCorbeau(h.getLevel(), poste.east(40), chef.getUUID(), l, true);
        boolean[] vu = {false};
        h.onEachTick(() -> { if (!h.getLevel().getEntitiesOfClass(Corbeau.class, new net.minecraft.world.phys.AABB(poste).inflate(64)).isEmpty()) vu[0] = true; });
        h.succeedWhen(() -> {
            h.assertTrue(vu[0], "on doit voir le corbeau");
            h.assertTrue(c.boite(chef.getUUID()).stream().anyMatch(x -> x.objet.equals("Essai")), "la lettre doit être livrée");
        });
    }

    /**
     * Le corbeau arrive d'un coin où les entités ne bougent pas (seul le tronc du poste est actif) : il part de plus
     * près, se pose et livre la lettre. Avant, il apparaissait là-bas, y restait suspendu, et la lettre n'arrivait
     * qu'au bout de 30 s, par le secours du courrier.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void corbeauVenuDUnCoinFige(GameTestHelper h) {
        var l = h.getLevel();
        BlockPos poste = sol(h, 0, 36000);
        var tronc = new net.minecraft.world.level.ChunkPos(poste);
        l.setChunkForced(tronc.x, tronc.z, true);
        l.setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        var chef = h.makeMockServerPlayerInLevel();
        chef.moveTo(poste.getX() + 2.5, poste.getY(), poste.getZ() + 0.5); // le témoin
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(chef.getUUID(), l, poste);
        Courrier.Lettre lettre = c.nouvelle("Aliénor", null, chef.getUUID(), "Essai au loin", "Bonjour chef.", l);
        c.envoyerCorbeau(l, poste.east(40), chef.getUUID(), lettre, true);
        boolean[] vu = {false};
        h.onEachTick(() -> { if (!l.getEntitiesOfClass(Corbeau.class, new net.minecraft.world.phys.AABB(poste).inflate(16)).isEmpty()) vu[0] = true; });
        h.succeedWhen(() -> {
            h.assertTrue(vu[0], "on doit le voir arriver au poste");
            h.assertTrue(c.boite(chef.getUUID()).stream().anyMatch(x -> x.objet.equals("Essai au loin")), "la lettre doit être livrée par le corbeau posé");
            l.setChunkForced(tronc.x, tronc.z, false);
        });
    }

    /** Ce que le bureau montre de lui : au travail, se repose, distance. */
    @GameTest(template = "vide")
    public static void statutAuBureau(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = ouvrier(h, "mineur", 4, 2, 4, ItemStack.EMPTY);
        h.assertTrue(t.statut().equals("Sans mission"), "sans mission : " + t.statut());
        t.mission("charbon", "stock", 64);
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

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Le mineur respecte ce qui ne lui appartient pas (cahier 3.3, 14.2) : ni structure générée, ni la base d'un autre joueur.
 * Le lot « voisins » tourne seul : la règle des bases n'y vaut que là ({@link Mineur#voisinsEnEssai}), et on y défait après
 * coup la fausse structure et le faux poste.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMineur {
    static final List<Runnable> A_DEFAIRE = new ArrayList<>();

    @BeforeBatch(batch = "voisins")
    public static void avant(ServerLevel l) { Mineur.voisinsEnEssai = true; }

    @AfterBatch(batch = "voisins")
    public static void apres(ServerLevel l) {
        Mineur.voisinsEnEssai = false;
        // À rebours : deux puits de mine dans le même chunk reviennent ainsi à l'état d'avant le premier.
        for (int i = A_DEFAIRE.size() - 1; i >= 0; i--) A_DEFAIRE.get(i).run();
        A_DEFAIRE.clear();
    }

    /** Une vraie structure « puits de mine » réduite à une pièce (la boîte de a à b), enregistrée dans le chunk comme le fait le monde. */
    static void puitsDeMine(ServerLevel l, BlockPos a, BlockPos b) {
        Structure s = l.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.MINESHAFT);
        StructurePiece piece = new StructurePiece(StructurePieceType.MINE_SHAFT_CORRIDOR, 0, BoundingBox.fromCorners(a, b)) {
            @Override protected void addAdditionalSaveData(StructurePieceSerializationContext c, CompoundTag t) {}
            @Override public void postProcess(WorldGenLevel w, StructureManager m, ChunkGenerator g, RandomSource r, BoundingBox bb, ChunkPos cp, BlockPos p) {}
        };
        ChunkPos cp = new ChunkPos(a);
        ChunkAccess chunk = l.getChunk(cp.x, cp.z);
        var startsAvant = new HashMap<>(chunk.getAllStarts());
        // Un chunk n'a qu'un départ par structure : deux tests voisins (même chunk) gardent chacun leur pièce.
        List<StructurePiece> pieces = new ArrayList<>(List.of(piece));
        StructureStart deja = chunk.getStartForStructure(s);
        if (deja != null && deja.isValid()) pieces.addAll(deja.getPieces());
        l.structureManager().setStartForStructure(SectionPos.bottomOf(chunk), s, new StructureStart(s, cp, 0, new PiecesContainer(pieces)), chunk);
        A_DEFAIRE.add(() -> chunk.setAllStarts(startsAvant));
        // La référence dans chaque chunk que touche la pièce (selon où tombe le test, elle peut en chevaucher deux), comme le fait le monde.
        for (int x = a.getX() >> 4; x <= b.getX() >> 4; x++)
            for (int z = a.getZ() >> 4; z <= b.getZ() >> 4; z++) {
                ChunkAccess c = l.getChunk(x, z);
                var refsAvant = new HashMap<>(c.getAllReferences());
                l.structureManager().addReferenceForStructure(SectionPos.bottomOf(c), s, cp.toLong(), c);
                A_DEFAIRE.add(() -> c.setAllReferences(refsAvant));
            }
    }

    /** Une lettre de ce type est partie vers son chef : gardée pour le poste, en vol, ou arrivée. */
    static boolean lettre(Travailleur t, String type) {
        Courrier c = Courrier.de(t.level().getServer());
        return t.lettresADeposer.stream().anyMatch(x -> x.type.equals(type)) || c.vols.stream().anyMatch(v -> v.lettre.type.equals(type))
                || c.boite(t.chef).stream().anyMatch(x -> x.type.equals(type));
    }

    /** Un filon de charbon dans un puits de mine : il n'y touche pas, et prévient son chef. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "voisins")
    public static void mineurNeCassePasUneStructure(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(3, 2, 4, Blocks.COAL_ORE);
        h.setBlock(3, 3, 4, Blocks.COAL_ORE);
        puitsDeMine(h.getLevel(), h.absolutePos(new BlockPos(2, 1, 3)), h.absolutePos(new BlockPos(4, 4, 5)));
        h.assertTrue(Monde.structure(h.getLevel(), h.absolutePos(new BlockPos(3, 2, 4))) != null, "la fausse structure doit être reconnue");
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 1, 2, 4, new ItemStack(Items.IRON_PICKAXE));
        t.mission("charbon", "stock", 64);
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertBlockPresent(Blocks.COAL_ORE, 3, 2, 4);
            h.assertBlockPresent(Blocks.COAL_ORE, 3, 3, 4);
            h.assertTrue(lettre(t, "structure"), "il doit prévenir son chef (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
            h.succeed();
        });
    }

    /**
     * Coincé contre le charbon d'un puits de mine (son puits à creuser est hors d'atteinte, à l'est, derrière l'enceinte du
     * test) : il ne se creuse pas une marche à travers le minerai. Avant, son escalier de déblocage ({@link Itineraire})
     * cassait le bloc du haut ; c'est ce qui faisait échouer de temps en temps le test d'avant.
     */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "voisins")
    public static void mineurCoinceNeCreusePasLaStructure(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(3, 2, 4, Blocks.COAL_ORE);
        h.setBlock(3, 3, 4, Blocks.COAL_ORE);
        puitsDeMine(h.getLevel(), h.absolutePos(new BlockPos(2, 1, 3)), h.absolutePos(new BlockPos(4, 4, 5)));
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, new ItemStack(Items.IRON_PICKAXE));
        t.mission("charbon", "stock", 64);
        // Son puits est déjà choisi, à 10 blocs à l'est : il ne peut pas y aller à pied.
        t.memoire.putString("chantier", "charbon@" + Mineur.cle(t.bureau) + "/48");
        t.memoire.putLong("puits", h.absolutePos(new BlockPos(12, 0, 4)).asLong());
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertBlockPresent(Blocks.COAL_ORE, 3, 2, 4);
            h.assertBlockPresent(Blocks.COAL_ORE, 3, 3, 4);
            h.assertTrue(lettre(t, "structure"), "il doit prévenir son chef (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
            h.succeed();
        });
    }

    /** Un filon à 18 blocs du poste d'un autre joueur : il n'y touche pas, et prévient son chef. */
    @GameTest(template = "vide", timeoutTicks = 1500, batch = "voisins")
    public static void mineurPasSousLaBaseVoisine(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos bureau = TestsJeu.sol(h, 0, 108000);
        BlockPos filon = bureau.offset(18, -1, 2);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) l.setBlockAndUpdate(filon.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        UUID voisin = UUID.randomUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(voisin, l, filon.offset(2, 1, 18)); // à 18 blocs du filon, à l'horizontale
        A_DEFAIRE.add(() -> c.postes.remove(voisin));
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(filon.offset(-2, 1, 0)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk(); // loin de tout : sans cela, son chunk se décharge avant qu'il ait pu le garder
        t.mission("charbon", "stock", 64);
        // Loin de tout, son coin peut tarder à s'éveiller : on attend qu'il ait travaillé 10 s (le filon intact tout du long).
        h.succeedWhen(() -> {
            h.assertTrue(l.getBlockState(filon).is(Blocks.COAL_ORE), "il ne doit pas toucher au filon (" + TestsJeu.etat(t, bureau) + ")");
            h.assertTrue(t.isAlive() && t.tickCount > 200, "il doit avoir travaillé (" + TestsJeu.etat(t, bureau) + ")");
            h.assertTrue(lettre(t, "base_voisine"), "il doit prévenir son chef (" + TestsJeu.etat(t, bureau) + ")");
            t.discard();
        });
    }

    // --- Lot 4, chantiers dits de vive voix (cahier 3.2) : salle, tunnel selon un plan, tri au dépôt ---------------

    /** Un pavé de pierre, coins a et b compris (coordonnées absolues). */
    static void pierre(ServerLevel l, BlockPos a, BlockPos b) {
        for (BlockPos q : BlockPos.betweenClosed(a, b)) l.setBlockAndUpdate(q, Blocks.STONE.defaultBlockState());
    }

    /** « Creuse une salle de 5 sur 5 » face à une paroi : 5 x 5 x 3 vidés, rien autour, et la planche posée dedans reste. */
    @GameTest(template = "vide", timeoutTicks = 4000)
    public static void salleDeCinqSurCinq(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 60);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(-4, 0, 3), o.offset(4, 4, 11));
        BlockPos planche = o.offset(1, 1, 5);
        l.setBlockAndUpdate(planche, Blocks.OAK_PLANKS.defaultBlockState());
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 0.5, o.getY() + 0.5, o.getZ() + 3.02));
                    bertrand.parler(chef, "Creuse une salle de 5 sur 5");
                },
                () -> {
                    List<String> restes = new ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(o.offset(-2, 0, 3), o.offset(2, 2, 7)))
                        if (!q.equals(planche) && !l.getBlockState(q).isAir()) restes.add(q.subtract(o).toShortString());
                    h.assertTrue(restes.isEmpty(), "la salle doit être vidée : " + restes + " (" + TestsRepertoire.c(bertrand) + ", " + TestsJeu.etat(bertrand, bertrand.bureau) + ")");
                },
                () -> {
                    h.assertTrue(l.getBlockState(planche).is(Blocks.OAK_PLANKS), "la planche (un bloc construit) doit rester");
                    for (BlockPos q : List.of(o.offset(0, 0, 8), o.offset(3, 0, 5), o.offset(-3, 1, 5), o.offset(0, 3, 5)))
                        h.assertTrue(l.getBlockState(q).is(Blocks.STONE), "rien hors de la salle : " + q.subtract(o));
                    h.assertTrue(bertrand.consigne == null, "fini, il reprend son travail (" + TestsRepertoire.c(bertrand) + ")");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** « Fais une carrière de 4 sur 4 sur 3 couches » (en regardant le sol) : vidée couche par couche, un escalier gardé pour remonter. */
    @GameTest(template = "vide", timeoutTicks = 3000)
    public static void carriereEnCouches(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 62);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(-3, -3, 2), o.offset(3, -1, 8));
        List<BlockPos> marches = List.of(o.offset(1, -2, 3), o.offset(1, -3, 3), o.offset(1, -3, 4));
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 0.5, o.getY() - 0.02, o.getZ() + 3.5));
                    bertrand.parler(chef, "Fais une carrière de 4 sur 4 sur 3 couches");
                },
                () -> {
                    List<String> restes = new ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(o.offset(-2, -3, 3), o.offset(1, -1, 6)))
                        if (!marches.contains(q) && !l.getBlockState(q).isAir()) restes.add(q.subtract(o).toShortString());
                    h.assertTrue(restes.isEmpty(), "la carrière doit être vidée : " + restes + " (" + TestsRepertoire.c(bertrand) + ", " + TestsJeu.etat(bertrand, bertrand.bureau) + ")");
                },
                () -> {
                    for (BlockPos q : marches) h.assertTrue(l.getBlockState(q).is(Blocks.STONE), "l'escalier reste : " + q.subtract(o));
                    for (BlockPos q : List.of(o.offset(0, -1, 7), o.offset(-3, -1, 4), o.offset(2, -2, 4)))
                        h.assertTrue(l.getBlockState(q).is(Blocks.STONE), "rien hors de la carrière : " + q.subtract(o));
                    h.assertTrue(bertrand.consigne == null, "fini, il reprend son travail (" + TestsRepertoire.c(bertrand) + ")");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : il se tient sur le bloc à creuser, un vide dessous (une grotte) : il se met à côté pour le casser, il ne tombe pas. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void carriereAuDessusDUnVide(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 63);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(-3, -1, 1), o.offset(3, -1, 6));
        // Une bande de 3 blocs (z 3 à 5) au-dessus d'un vide ; il se tient au milieu, sur le deuxième à creuser.
        BlockPos bloc = o.offset(0, -1, 4);
        for (int z = 3; z <= 5; z++) for (int y = 2; y <= 3; y++) l.setBlockAndUpdate(o.offset(0, -y, z), Blocks.AIR.defaultBlockState());
        int[] plusBas = {Integer.MAX_VALUE};
        h.onEachTick(() -> { if (bertrand.isAlive()) plusBas[0] = Math.min(plusBas[0], bertrand.getBlockY()); });
        Essais.etapes(h, chef, e,
                () -> bertrand.moveTo(bloc.getX() + 0.5, bloc.getY() + 1, bloc.getZ() + 0.5),
                Essais.attendre(h, 10), // déplacé, il n'est visible du chef qu'au tick suivant
                () -> {
                    if (bertrand.position().distanceToSqr(Vec3.atBottomCenterOf(bloc.above())) > 0.05) {
                        bertrand.moveTo(bloc.getX() + 0.5, bloc.getY() + 1, bloc.getZ() + 0.5);
                        throw new net.minecraft.gametest.framework.GameTestAssertException("il doit se tenir sur le bloc : " + bertrand.position().subtract(Vec3.atBottomCenterOf(o)));
                    }
                },
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 0.5, o.getY() - 0.02, o.getZ() + 3.5));
                    bertrand.parler(chef, "Fais une carrière de 3 sur 1, 1 de profondeur");
                },
                () -> h.assertTrue(l.getBlockState(bloc).isAir() && l.getBlockState(bloc.north()).isAir() && l.getBlockState(bloc.south()).isAir() && bertrand.consigne == null, "il doit creuser la bande (" + TestsRepertoire.c(bertrand) + ", " + TestsJeu.etat(bertrand, bertrand.bureau) + ")"),
                Essais.attendre(h, 40),
                () -> h.assertTrue(plusBas[0] >= o.getY(), "il ne doit pas tomber dans le vide (descendu à " + (plusBas[0] - o.getY()) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : sac plein, poste pas chargé (ou loin) : il va au poste au lieu de jeter ce qu'il creuse par terre. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void sacPleinRienParTerre(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 64);
        Travailleur bertrand = e[0];
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(3, 0, -2), o.offset(14, 3, 2));
        bertrand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        for (int i = 0; i < bertrand.inventaire.getContainerSize(); i++) bertrand.inventaire.setItem(i, new ItemStack(Items.DIRT, 64));
        bertrand.bureau = o.offset(-40, 0, 0); // pas de poste chargé là : avant, il cassait quand même et jetait la pierre
        long[] debut = {-1};
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 3.02, o.getY() + 0.5, o.getZ() + 0.5));
                    bertrand.parler(chef, "Creuse un tunnel");
                    debut[0] = h.getTick();
                },
                () -> {
                    var par = l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(o).inflate(24));
                    h.assertTrue(par.isEmpty(), "rien par terre : " + par.stream().map(x -> x.getItem().toString()).toList());
                    h.assertTrue(h.getTick() - debut[0] > 300, "attente");
                },
                () -> TestsRepertoire.fin(e));
    }

    /** Relecture : un minerai de diamant dans le tunnel et seulement une pioche de pierre : il le laisse (cassé, il serait perdu). */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void laisseLeDiamantSansBonnePioche(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 65);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.STONE_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(3, 0, -2), o.offset(14, 3, 2));
        BlockPos diamant = o.offset(10, 0, 0); // au bout : laissé en place, il ne barre pas le passage
        l.setBlockAndUpdate(diamant, Blocks.DIAMOND_ORE.defaultBlockState());
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 3.02, o.getY() + 0.5, o.getZ() + 0.5));
                    bertrand.parler(chef, "Creuse un tunnel");
                },
                () -> h.assertTrue(bertrand.consigne == null && l.getBlockState(o.offset(10, 1, 0)).isAir(), "il doit finir le tunnel (" + TestsRepertoire.c(bertrand) + ", " + TestsJeu.etat(bertrand, bertrand.bureau) + ")"),
                () -> h.assertTrue(l.getBlockState(diamant).is(Blocks.DIAMOND_ORE), "le diamant doit rester en place"),
                () -> TestsRepertoire.fin(e));
    }

    /** « Creuse un tunnel de 12 blocs vers l'est » : 12 blocs de long (pas les 8 d'avant), 2 de haut, et pas un de plus. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void tunnelDeDouzeBlocs(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 61);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        ServerLevel l = h.getLevel();
        BlockPos o = chef.blockPosition();
        pierre(l, o.offset(3, 0, -2), o.offset(20, 3, 2));
        Essais.etapes(h, chef, e,
                () -> {
                    TestsRepertoire.regarderPoint(chef, new Vec3(o.getX() + 3.02, o.getY() + 0.5, o.getZ() + 0.5));
                    bertrand.parler(chef, "Creuse un tunnel de 12 blocs vers l'est");
                },
                () -> {
                    for (int x = 3; x <= 14; x++) for (int y = 0; y <= 1; y++)
                        h.assertTrue(l.getBlockState(o.offset(x, y, 0)).isAir(), "le tunnel doit être percé : " + x + "," + y + " (" + TestsRepertoire.c(bertrand) + ", " + TestsJeu.etat(bertrand, bertrand.bureau) + ")");
                },
                () -> {
                    h.assertTrue(bertrand.consigne == null, "fini, il reprend son travail (" + TestsRepertoire.c(bertrand) + ")");
                    h.assertTrue(l.getBlockState(o.offset(15, 0, 0)).is(Blocks.STONE) && l.getBlockState(o.offset(8, 2, 0)).is(Blocks.STONE)
                            && l.getBlockState(o.offset(8, 0, 1)).is(Blocks.STONE), "pas un bloc de plus");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Rapport de KOLRAX (1.0.7) : « un mineur mine du fer, mais le compteur au-dessus de sa tête ne monte pas ». Il casse un
     * minerai de fer et un minerai de fer des profondeurs : le fer brut est dans son sac, pas encore livré, et on lit déjà
     * « 2/20 fer » au-dessus de sa tête (avant : « 0/20 » jusqu'au dépôt).
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void compteurMonteQuandIlMine(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", 851, new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(Missions.donner(chef, t, "fer", "fois", 20, "toujours", null).ok(), "mission acceptée");
        ServerLevel l = h.getLevel();
        BlockPos fer = t.bureau.offset(-3, -1, 1), profond = t.bureau.offset(-4, -1, -1);
        l.setBlockAndUpdate(fer, Blocks.IRON_ORE.defaultBlockState());
        l.setBlockAndUpdate(profond, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState());
        h.succeedWhen(() -> {
            String s = t.statutTete();
            int brut = t.compter(Actions.Filtre.de("minecraft:raw_iron"));
            h.assertTrue(!l.getBlockState(fer).is(Blocks.IRON_ORE) && !l.getBlockState(profond).is(Blocks.DEEPSLATE_IRON_ORE) && brut >= 2,
                    "il doit miner les deux minerais (fer brut au sac : " + brut + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.missions.get(0).livre == 0, "pas encore livré (" + t.missions.get(0).livre + ")");
            h.assertTrue(s.contains("2/20") && s.contains("fer"), "le compteur doit compter le fer qu'il porte : « " + s + " »");
            t.discard();
        });
    }

    /** Au dépôt, le minerai va dans le coffre voisin qui a déjà du minerai, la pierre dans celui qui a déjà de la pierre. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void triAuDepot(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 2, 3, new ItemStack(Items.IRON_PICKAXE));
        h.setBlock(1, 2, 0, Blocks.CHEST);
        h.setBlock(0, 2, 1, Blocks.CHEST);
        var pierres = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 0)));
        var minerais = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(0, 2, 1)));
        pierres.setItem(5, new ItemStack(Items.COBBLESTONE));
        minerais.setItem(5, new ItemStack(Items.RAW_IRON));
        t.inventaire.addItem(new ItemStack(Items.RAW_IRON, 10));
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 20));
        t.inventaire.addItem(new ItemStack(Items.COAL, 5));
        t.inventaire.addItem(new ItemStack(Items.DIRT, 23)); // il en garde 16 pour monter (A3)
        Cerveau.lancer(t, new Actions.Deposer(List.of()));
        h.succeedWhen(() -> {
            h.assertTrue(pierres.countItem(Items.COBBLESTONE) == 21 && pierres.countItem(Items.DIRT) == 7, "la pierre et la terre vont avec la pierre (" + pierres.countItem(Items.COBBLESTONE) + ")");
            h.assertTrue(minerais.countItem(Items.RAW_IRON) == 11 && minerais.countItem(Items.COAL) == 5, "le minerai va avec le minerai (" + minerais.countItem(Items.RAW_IRON) + ")");
            t.discard();
        });
    }

    /** Il a raté ses puits : « ⚠ Aucun lieu sûr pour miner », 5 minutes sans essayer (comme {@link Mineur#puitsRate}). */
    static void rate(Travailleur t, BlockPos mine) {
        t.memoire.putInt("rates", Mineur.RATES);
        t.memoire.putLong("rates_t", Cerveau.temps(t) + 300);
        t.memoire.putLong("mine", mine.asLong());
        t.bloquer(Statut.LIEU_MINE);
    }

    /**
     * Bloqué « Aucun lieu sûr pour miner » : « Travaille ici » le débloque tout de suite (ses puits ratés et sa mine sont
     * oubliés), un nouveau chantier aussi. Avant : il attendait encore 5 minutes, toujours bloqué.
     */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void travailleIciDebloqueLeMineur(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 2, 3, new ItemStack(Items.IRON_PICKAXE));
        t.mission("charbon", "stock", 64);
        BlockPos vieille = h.absolutePos(new BlockPos(7, 2, 7));
        rate(t, vieille);
        t.travailleIci(h.absolutePos(new BlockPos(5, 2, 5)));
        String ici = t.memoire.getInt("rates") + "/" + t.memoire.contains("rates_t") + "/" + t.memoire.contains("mine") + "/" + t.blocageActuel();
        t.travailleIci(null);
        rate(t, vieille);
        t.memoire.putString("chantier", "fer@ailleurs/16");
        Metier.de("mineur").travailler(t);
        String chantier = t.memoire.getInt("rates") + "/" + t.memoire.contains("rates_t") + "/" + (t.memoire.getLong("mine") == vieille.asLong());
        t.discard();
        h.assertTrue(ici.equals("0/false/false/null"), "« Travaille ici » : plus de puits ratés, plus de mine, plus bloqué (" + ici + ")");
        h.assertTrue(chantier.equals("0/false/false"), "nouveau chantier : plus de puits ratés ni d'ancienne mine (" + chantier + ")");
        h.succeed();
    }

    /**
     * Un trou sous ses pieds, de la terre et du pavé dans son sac : il bouche le trou avec la terre (elle ne compte pas comme
     * une construction) ; sa mission est la pierre, il ne pose jamais le pavé qu'il doit livrer. Avant : le pavé d'abord.
     */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void boucheLeTrouALaTerre(GameTestHelper h) {
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 3, 2, 3, new ItemStack(Items.IRON_PICKAXE));
        t.inventaire.addItem(new ItemStack(Items.COBBLESTONE, 10));
        t.inventaire.addItem(new ItemStack(Items.DIRT, 5));
        BlockPos p = h.absolutePos(new BlockPos(4, 3, 4)); // de l'air dessous : un trou
        List<String> rates = new ArrayList<>();
        for (String mission : new String[]{"charbon", "pierre"}) {
            t.mission(mission, "stock", 64);
            Mineur.Case r = Mineur.creuserCase(t, p, 2);
            boolean terre = r == Mineur.Case.ACTION && t.action instanceof Actions.Poser po && po.pos.equals(p.below())
                    && po.filtre.objet(new ItemStack(Items.DIRT)) && !po.filtre.objet(new ItemStack(Items.COBBLESTONE));
            if (!terre) rates.add(mission + " : " + r + ", " + (t.action instanceof Actions.Poser po ? po.filtre.texte : String.valueOf(t.action)));
        }
        t.discard();
        h.assertTrue(rates.isEmpty(), "il bouche le trou avec la terre, pas le pavé : " + rates);
        h.succeed();
    }
}

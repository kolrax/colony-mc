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
        A_DEFAIRE.forEach(Runnable::run);
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
        var refsAvant = new HashMap<>(chunk.getAllReferences());
        l.structureManager().setStartForStructure(SectionPos.bottomOf(chunk), s, new StructureStart(s, cp, 0, new PiecesContainer(List.of(piece))), chunk);
        l.structureManager().addReferenceForStructure(SectionPos.bottomOf(chunk), s, cp.toLong(), chunk);
        A_DEFAIRE.add(() -> { chunk.setAllStarts(startsAvant); chunk.setAllReferences(refsAvant); });
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
}

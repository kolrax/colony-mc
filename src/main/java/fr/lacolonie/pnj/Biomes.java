package fr.lacolonie.pnj;

import com.mojang.datafixers.util.Pair;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Où pousse un bois : on lit les arbres que chaque biome fait pousser à la génération du monde (biomes des mods
 * compris, comme Terralith), puis on cherche le plus proche de ces biomes, comme la commande /locate biome.
 */
final class Biomes {
    private Biomes() {}

    /** Pour chaque biome, les troncs de ses arbres. Calculé une fois. */
    static Map<ResourceKey<Biome>, Set<BlockState>> troncs;

    static synchronized Map<ResourceKey<Biome>, Set<BlockState>> troncs(ServerLevel l) {
        if (troncs != null) return troncs;
        Map<ResourceKey<Biome>, Set<BlockState>> m = new HashMap<>();
        l.registryAccess().registryOrThrow(Registries.BIOME).holders().forEach(h -> {
            Set<BlockState> s = new HashSet<>();
            for (HolderSet<PlacedFeature> etape : h.value().getGenerationSettings().features())
                for (Holder<PlacedFeature> pf : etape) troncs(pf.value().feature().value(), s, 0);
            if (!s.isEmpty()) m.put(h.key(), s);
        });
        return troncs = m;
    }

    private static void troncs(ConfiguredFeature<?, ?> f, Set<BlockState> s, int profondeur) {
        if (profondeur > 6) return;
        switch (f.config()) {
            case TreeConfiguration tc -> {
                RandomSource r = RandomSource.create(0);
                for (int i = 0; i < 4; i++) s.add(tc.trunkProvider.getState(r, BlockPos.ZERO));
            }
            case RandomFeatureConfiguration rc -> {
                for (WeightedPlacedFeature w : rc.features) troncs(w.feature.value().feature().value(), s, profondeur + 1);
                troncs(rc.defaultFeature.value().feature().value(), s, profondeur + 1);
            }
            case SimpleRandomFeatureConfiguration sc -> { for (Holder<PlacedFeature> p : sc.features) troncs(p.value().feature().value(), s, profondeur + 1); }
            case RandomBooleanFeatureConfiguration bc -> {
                troncs(bc.featureTrue.value().feature().value(), s, profondeur + 1);
                troncs(bc.featureFalse.value().feature().value(), s, profondeur + 1);
            }
            default -> {}
        }
    }

    /** Les biomes où poussent naturellement des arbres de ce bois. */
    static Set<ResourceKey<Biome>> ouPousse(ServerLevel l, Actions.Filtre bois) {
        Set<ResourceKey<Biome>> r = new HashSet<>();
        troncs(l).forEach((b, s) -> { if (s.stream().anyMatch(bois::bloc)) r.add(b); });
        return r;
    }

    /** Les recherches en cours ou faites, par PNJ et par bois (une recherche peut prendre quelques secondes). */
    static final Map<String, CompletableFuture<BlockPos>> RECHERCHES = new ConcurrentHashMap<>();

    /**
     * Le biome le plus proche où pousse ce bois, cherché en arrière-plan. Null tant que la recherche n'est pas finie ;
     * ensuite la position (ou {@code BlockPos.ZERO} s'il n'y en a pas dans le rayon).
     */
    static BlockPos chercher(ServerLevel l, String cle, Actions.Filtre bois, BlockPos depuis, int rayon) {
        CompletableFuture<BlockPos> f = RECHERCHES.computeIfAbsent(cle, k -> {
            Set<ResourceKey<Biome>> biomes = ouPousse(l, bois);
            if (biomes.isEmpty()) return CompletableFuture.completedFuture(BlockPos.ZERO);
            return CompletableFuture.supplyAsync(() -> {
                Pair<BlockPos, Holder<Biome>> p = l.findClosestBiome3d(h -> h.unwrapKey().map(biomes::contains).orElse(false), depuis, rayon, 32, 64);
                return p == null ? BlockPos.ZERO : p.getFirst();
            }, Util.backgroundExecutor()).exceptionally(e -> BlockPos.ZERO);
        });
        return f.isDone() ? f.join() : null;
    }

    static void oublier(String cle) { RECHERCHES.remove(cle); }
}

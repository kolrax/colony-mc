package fr.lacolonie.pnj;

import java.util.List;
import java.util.Set;

/** Les règles pures des lieux de travail ({@link Lieux}), sans le jeu : testées par LieuxTest. */
final class LieuxRegles {
    private LieuxRegles() {}


    /** Les blocs naturels du jeu de base (chemin de l'identifiant, sans « minecraft: »). */
    static final Set<String> NATURELS = Set.copyOf(List.of(
            "air", "cave_air", "void_air", "water", "lava", "bubble_column",
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "dripstone_block", "pointed_dripstone", "bedrock",
            "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay", "sand", "red_sand",
            "suspicious_sand", "suspicious_gravel", "gravel", "sandstone", "red_sandstone", "terracotta", "obsidian", "magma_block",
            "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice",
            "moss_block", "moss_carpet", "azalea", "flowering_azalea", "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "pink_petals",
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sea_pickle", "vine", "glow_lichen", "cactus", "lily_pad", "sweet_berry_bush",
            "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
            "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "spore_blossom", "hanging_roots", "cave_vines", "cave_vines_plant",
            "cobweb", "amethyst_block", "budding_amethyst", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster",
            "infested_stone", "infested_deepslate", "sculk", "sculk_vein", "smooth_basalt", "raw_iron_block", "raw_copper_block",
            "mangrove_roots", "muddy_mangrove_roots", "bee_nest",
            "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "gilded_blackstone", "crimson_nylium", "warped_nylium",
            "nether_wart_block", "warped_wart_block", "shroomlight", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots",
            "nether_sprouts", "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "glowstone", "ancient_debris",
            "end_stone", "chorus_plant", "chorus_flower"));

    /** Les roches que Create fait apparaître dans le monde. */
    static final Set<String> NATURELS_CREATE = Set.copyOf(List.of("limestone", "scoria", "asurine", "crimsite", "ochrum", "veridium"));

    /**
     * Un bloc de la nature (identifiant complet, « minecraft:stone »), que l'on peut casser pour travailler : roches, terre,
     * sable, minerais, liquides, neige, troncs d'arbre (pas écorcés), plantes sauvages… Des feuilles ne le sont que si elles
     * ne sont pas {@code persistantes} (posées à la main). Tout le reste (planches, coffre, torche, culture, clôture, verre,
     * pierre taillée, pavé…) est une construction.
     */
    static boolean naturel(String id, boolean persistantes) {
        int i = id.indexOf(':');
        String ns = i < 0 ? "minecraft" : id.substring(0, i), p = id.substring(i + 1);
        if (p.endsWith("_leaves")) return !persistantes;
        if (p.startsWith("stripped_") || p.contains("glazed")) return false;
        if (p.endsWith("_ore")) return true;                 // minerais, des mods compris
        if (p.endsWith("_log") || p.endsWith("_stem") && !p.contains("dripleaf") && !p.contains("melon") && !p.contains("pumpkin")) return true; // troncs
        if (ns.equals("minecraft")) return NATURELS.contains(p) || p.endsWith("_terracotta");
        if (ns.equals("create")) return NATURELS_CREATE.contains(p);
        return false;
    }

    /** Distance horizontale au carré. */
    static long dist2(int x1, int z1, int x2, int z2) { long dx = x1 - x2, dz = z1 - z2; return dx * dx + dz * dz; }

    /** Le point (x, z) est dans la colonie d'un poste en (px, pz), de ce rayon. */
    static boolean dansColonie(int px, int pz, int rayon, int x, int z) { return dist2(px, pz, x, z) <= (long) rayon * rayon; }

    /** Ce qu'il dit du lieu choisi : « Je mine en 120, 40, -35. » */
    static String phrase(String metier, int x, int y, int z) {
        String verbe = switch (metier == null ? "" : metier) {
            case "mineur" -> "Je mine";
            case "bucheron" -> "Je coupe du bois";
            case "fermier" -> "Je cultive";
            case "eleveur" -> "J'élève mes bêtes";
            case "pecheur" -> "Je pêche";
            case "chasseur" -> "Je chasse";
            default -> "Je travaille";
        };
        return verbe + " en " + x + ", " + y + ", " + z + ".";
    }

}

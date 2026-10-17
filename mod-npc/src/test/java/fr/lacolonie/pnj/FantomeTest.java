package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Le joueur fantôme (refonte, partie 7) : le classement des blocs cassés par les ouvriers, naturel ou construit. */
class FantomeTest {
    static String c(String id) { return Fantome.Nature.classer(id, false); }

    @Test
    void terrainEtMineraisSontNaturels() {
        for (String id : new String[]{"minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block", "minecraft:gravel",
                "minecraft:sand", "minecraft:iron_ore", "minecraft:deepslate_diamond_ore", "minecraft:nether_gold_ore", "minecraft:infested_stone",
                "minecraft:short_grass", "minecraft:poppy", "minecraft:oak_log", "minecraft:spruce_sapling", "minecraft:red_mushroom", "minecraft:crimson_stem"})
            assertEquals("naturel", c(id), id);
    }

    @Test
    void constructionsSontConstruites() {
        for (String id : new String[]{"minecraft:cobblestone", "minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:torch", "minecraft:chest",
                "minecraft:glass", "minecraft:oak_door", "minecraft:white_bed", "minecraft:farmland", "minecraft:dirt_path", "minecraft:stripped_oak_log",
                "minecraft:oak_wood", "minecraft:white_glazed_terracotta", "minecraft:cobblestone_wall", "colonie_pnj:bureau", "create:andesite_casing"})
            assertEquals("construit", c(id), id);
    }

    @Test
    void feuillesPoseesALaMainSontConstruites() {
        assertEquals("naturel", Fantome.Nature.classer("minecraft:oak_leaves", false));
        assertEquals("construit", Fantome.Nature.classer("minecraft:oak_leaves", true));
    }

    @Test
    void lesRecoltesSontDesCultures() {
        assertEquals("culture", c("minecraft:wheat"));
        assertEquals("culture", c("minecraft:carrots"));
        assertEquals("culture", c("minecraft:pumpkin_stem"));
        assertEquals("naturel", c("minecraft:pumpkin"));
    }
}

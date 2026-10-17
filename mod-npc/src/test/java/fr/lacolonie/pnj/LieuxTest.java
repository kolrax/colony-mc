package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LieuxTest {
    static boolean construit(String id) { return !LieuxRegles.naturel(id, false); }

    @Test
    void laNatureNEstPasUneConstruction() {
        for (String id : new String[]{"minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:dirt", "minecraft:grass_block",
                "minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:coal_ore", "minecraft:deepslate_diamond_ore", "minecraft:water",
                "minecraft:lava", "minecraft:snow", "minecraft:ice", "minecraft:oak_log", "minecraft:dark_oak_log", "minecraft:short_grass",
                "minecraft:poppy", "minecraft:red_mushroom", "minecraft:air", "minecraft:cave_air", "minecraft:red_terracotta",
                "create:zinc_ore", "create:limestone", "minecraft:crimson_stem", "minecraft:mushroom_stem"})
            assertFalse(construit(id), id);
    }

    @Test
    void ceQuePoseUnJoueurEstUneConstruction() {
        for (String id : new String[]{"minecraft:oak_planks", "minecraft:chest", "minecraft:red_bed", "minecraft:torch", "minecraft:wall_torch",
                "minecraft:wheat", "minecraft:carrots", "minecraft:farmland", "minecraft:oak_fence", "minecraft:glass", "minecraft:cobblestone",
                "minecraft:stone_bricks", "minecraft:stripped_oak_log", "minecraft:oak_sapling", "minecraft:white_glazed_terracotta",
                "minecraft:smooth_stone", "minecraft:furnace", "minecraft:oak_door", "minecraft:pumpkin_stem", "create:andesite_casing",
                "minecraft:barrier", "unmod:bloc_inconnu"})
            assertTrue(construit(id), id);
    }

    @Test
    void feuillesPoseesALaMain() {
        assertTrue(LieuxRegles.naturel("minecraft:oak_leaves", false), "feuilles d'un arbre naturel");
        assertFalse(LieuxRegles.naturel("minecraft:oak_leaves", true), "feuilles persistantes : posées à la main");
        assertFalse(LieuxRegles.naturel("biomesoplenty:fir_leaves", true));
        assertTrue(LieuxRegles.naturel("biomesoplenty:fir_leaves", false));
    }

    @Test
    void colonieAutourDuPoste() {
        assertTrue(LieuxRegles.dansColonie(0, 0, 48, 30, 30));
        assertTrue(LieuxRegles.dansColonie(100, -50, 48, 148, -50));
        assertFalse(LieuxRegles.dansColonie(0, 0, 48, 40, 40));
        assertFalse(LieuxRegles.dansColonie(0, 0, 0, 1, 0));
    }

    @Test
    void ilDitOuIlTravaille() {
        assertEquals("Je mine en 120, 40, -35.", LieuxRegles.phrase("mineur", 120, 40, -35));
        assertEquals("Je coupe du bois en 1, 2, 3.", LieuxRegles.phrase("bucheron", 1, 2, 3));
        assertEquals("Je travaille en 0, 64, 0.", LieuxRegles.phrase("macon", 0, 64, 0));
    }
}

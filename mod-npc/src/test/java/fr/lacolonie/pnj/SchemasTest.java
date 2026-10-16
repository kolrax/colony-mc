package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Schemas.Bloc;
import fr.lacolonie.pnj.Schemas.Genre;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Le maçon (lot 10), sans le jeu : sortes de blocs, ordre de pose, rotation, placement, nom du plan dit. */
class SchemasTest {
    static final List<String> PALETTE = List.of("minecraft:air", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:wall_torch[facing=south]",
            "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]",
            "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]", "minecraft:water[level=0]",
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]", "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]");

    @Test
    void sortesDeBlocs() {
        assertEquals(Genre.VIDE, Schemas.genre("minecraft:air"));
        assertEquals(Genre.SOLIDE, Schemas.genre("minecraft:cobblestone"));
        assertEquals(Genre.FRAGILE, Schemas.genre("minecraft:wall_torch[facing=south]"));
        assertEquals(Genre.FRAGILE, Schemas.genre(PALETTE.get(4)), "le bas d'une porte : fragile");
        assertEquals(Genre.COMPAGNON, Schemas.genre(PALETTE.get(5)), "le haut d'une porte vient avec le bas");
        assertEquals(Genre.COMPAGNON, Schemas.genre("minecraft:red_bed[facing=north,occupied=false,part=head]"));
        assertEquals(Genre.FRAGILE, Schemas.genre("minecraft:red_bed[facing=north,occupied=false,part=foot]"));
        assertEquals(Genre.SOLIDE, Schemas.genre(PALETTE.get(7)), "« half=top » d'un escalier n'est pas une moitié de porte");
        assertEquals(Genre.FRAGILE, Schemas.genre(PALETTE.get(8)), "une vitre");
        assertEquals(Genre.IGNORE, Schemas.genre(PALETTE.get(6)), "l'eau n'est pas posée");
        assertEquals("east", Schemas.propriete(PALETTE.get(7), "facing"));
        assertEquals("", Schemas.propriete("minecraft:stone", "facing"));
    }

    @Test
    void ordreDePose() {
        List<Bloc> b = new ArrayList<>();
        b.add(new Bloc(1, 1, 0, 3)); // torche, couche 1
        b.add(new Bloc(0, 2, 0, 2)); // planches, couche 2
        b.add(new Bloc(0, 1, 0, 2)); // planches, couche 1
        b.add(new Bloc(1, 1, 1, 0)); // air, couche 1
        b.add(new Bloc(0, 0, 0, 1)); // pierre, couche 0
        b.add(new Bloc(2, 1, 0, 4)); // bas de porte
        b.add(new Bloc(2, 2, 0, 5)); // haut de porte : pas dans l'ordre
        b.add(new Bloc(2, 0, 0, 6)); // eau : pas dans l'ordre
        List<Bloc> o = Schemas.ordre(b, PALETTE);
        assertEquals(List.of(new Bloc(0, 0, 0, 1), new Bloc(1, 1, 1, 0), new Bloc(0, 1, 0, 2), new Bloc(0, 2, 0, 2), new Bloc(1, 1, 0, 3), new Bloc(2, 1, 0, 4)), o,
                "de bas en haut, l'air dégagé avant les blocs de sa couche, tout le solide avant le fragile");
    }

    @Test
    void serpentDansUneCouche() {
        List<Bloc> b = new ArrayList<>();
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) b.add(new Bloc(x, 0, z, 1));
        List<Bloc> o = Schemas.ordre(b, PALETTE);
        assertEquals(List.of(new Bloc(0, 0, 0, 1), new Bloc(0, 0, 1, 1), new Bloc(1, 0, 1, 1), new Bloc(1, 0, 0, 1)), o, "en serpentant, sans revenir au bout du rang");
    }

    @Test
    void rotationCommeLeJeu() {
        // StructureTemplate.transform, pivot à zéro : quart horaire (x, z) → (-z, x) ; demi-tour (-x, -z) ; quart antihoraire (z, -x).
        assertArrayEquals(new int[]{3, 5}, Schemas.tourner(3, 5, 0, 0));
        assertArrayEquals(new int[]{-5, 3}, Schemas.tourner(3, 5, 0, 1));
        assertArrayEquals(new int[]{-3, -5}, Schemas.tourner(3, 5, 0, 2));
        assertArrayEquals(new int[]{5, -3}, Schemas.tourner(3, 5, 0, 3));
        // Miroirs : gauche-droite inverse z, avant-arrière inverse x, avant la rotation.
        assertArrayEquals(new int[]{3, -5}, Schemas.tourner(3, 5, 1, 0));
        assertArrayEquals(new int[]{-5, -3}, Schemas.tourner(3, 5, 2, 1));
        assertEquals(0, Schemas.rotation("north"));
        assertEquals(1, Schemas.rotation("east"));
        assertEquals(2, Schemas.rotation("south"));
        assertEquals(3, Schemas.rotation("west"));
    }

    @Test
    void centreSurLEndroitMontre() {
        for (int r = 0; r < 4; r++) {
            int[] o = Schemas.centre(100, 64, -50, 5, 3, 0, r);
            int[] a = Schemas.tourner(0, 0, 0, r), b = Schemas.tourner(4, 2, 0, r);
            int minX = o[0] + Math.min(a[0], b[0]), maxX = o[0] + Math.max(a[0], b[0]);
            int minZ = o[2] + Math.min(a[1], b[1]), maxZ = o[2] + Math.max(a[1], b[1]);
            assertTrue(minX <= 100 && maxX >= 100 && minZ <= -50 && maxZ >= -50, "l'endroit montré est dans l'emprise (rotation " + r + ")");
            assertTrue(Math.abs(minX + maxX - 200) <= 1 && Math.abs(minZ + maxZ + 100) <= 1, "et au milieu (rotation " + r + ")");
            assertEquals(64, o[1], "le bas du plan à la hauteur montrée");
        }
    }

    @Test
    void lePlanDit() {
        List<String> noms = List.of("cabane", "tour de guet", "tour", "moulin a vent");
        assertEquals("cabane", Schemas.choisir("Construis la cabane ici", noms));
        assertEquals("tour de guet", Schemas.choisir("bâtis-moi la tour de guet là", noms), "le nom le plus long");
        assertEquals("tour", Schemas.choisir("construis une tour", noms));
        assertEquals("moulin a vent", Schemas.choisir("construis le moulin à vent", noms));
        assertEquals("moulin a vent", Schemas.choisir("construis le moulin", noms), "un mot du nom suffit");
        assertEquals("cabane", Schemas.choisir("construis des cabanes", noms), "au pluriel");
        assertNull(Schemas.choisir("construis ici", noms));
        assertEquals("tour de guet", Schemas.nom("tour_de_guet.nbt"));
        assertEquals("ma maison", Schemas.nom("Robert/ma-maison.NBT"));
    }

    @Test
    void avancementEtListe() {
        assertEquals(0, Schemas.pourcent(0, 40));
        assertEquals(75, Schemas.pourcent(30, 40));
        assertEquals(99, Schemas.pourcent(399, 400), "100 seulement fini");
        assertEquals(100, Schemas.pourcent(0, 0));
        assertEquals("cabane", Schemas.liste(List.of("cabane")));
        assertEquals("cabane, tour et moulin", Schemas.liste(List.of("cabane", "tour", "moulin")));
    }

    @Test
    void lesOrdresDuMacon() {
        RepertoireTest.demande("Construis la cabane ici", "macon", "construire");
        RepertoireTest.demande("bâtis-moi la tour de guet là-bas", "macon", "construire");
        RepertoireTest.demande("tu peux construire le moulin ?", "macon", "construire");
        RepertoireTest.demande("Quels schémas tu connais ?", "macon", "schemas_connus");
        RepertoireTest.demande("quels plans connais-tu ?", "macon", "schemas_connus");
        RepertoireTest.demande("Arrête le chantier", "macon", "arreter_chantier");
        RepertoireTest.demande("reprends le chantier", "macon", "reprendre_chantier");
        RepertoireTest.demande("abandonne le chantier", "macon", "abandonner_chantier");
        RepertoireTest.demande("Où en est le chantier ?", "macon", "etat_chantier");
        RepertoireTest.demande("Construis la cabane ici", "mineur", "-");
    }

    /** Relecture : un plan ne fabrique rien gratuitement (les cornichons, bougies, œufs, fleurs, couches se paient chacun). */
    @Test
    void cequeCouteUnBloc() {
        assertEquals(4, Schemas.nombre("minecraft:sea_pickle[pickles=4,waterlogged=true]"));
        assertEquals(3, Schemas.nombre("minecraft:candle[candles=3,lit=false,waterlogged=false]"));
        assertEquals(4, Schemas.nombre("minecraft:turtle_egg[eggs=4,hatch=0]"));
        assertEquals(4, Schemas.nombre("minecraft:pink_petals[facing=north,flower_amount=4]"));
        assertEquals(8, Schemas.nombre("minecraft:snow[layers=8]"));
        assertEquals(2, Schemas.nombre("minecraft:oak_slab[type=double,waterlogged=false]"));
        assertEquals(1, Schemas.nombre("minecraft:oak_slab[type=bottom,waterlogged=false]"));
        assertEquals(1, Schemas.nombre("minecraft:stone"));
        for (String k : List.of("age", "level", "honey_level", "charges", "bites")) assertTrue(Schemas.A_ZERO.contains(k), k + " remis à zéro");
    }

    @Test
    void fragilesSansMeprise() {
        assertEquals(Genre.SOLIDE, Schemas.genre("minecraft:bedrock"));
        assertEquals(Genre.SOLIDE, Schemas.genre("minecraft:sea_lantern"));
        assertEquals(Genre.SOLIDE, Schemas.genre("minecraft:jack_o_lantern[facing=north]"));
        assertEquals(Genre.FRAGILE, Schemas.genre("minecraft:lantern[hanging=false,waterlogged=false]"));
        assertEquals(Genre.FRAGILE, Schemas.genre("minecraft:white_bed[facing=north,occupied=false,part=foot]"));
        assertFalse(Schemas.disable("tour|bis"), "« | » sépare les champs d'un ordre");
        assertNull(Schemas.choisir("construis-moi un coffre", List.of("cabane")), "pas le seul plan connu à la place d'un coffre");
    }
}

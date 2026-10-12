package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Le déblocage (cahier 14.10 et 11.3) : un ouvrier coincé pose des blocs pour sortir ; en dernier recours, il est ramené
 * au poste avec un avis à son chef. Le lot « deblocage » abaisse le délai du dernier recours ({@link Coince#DELAI}) à
 * 15 s : ses tests tournent seuls, sans gêner les autres.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsDeblocage {
    static int delaiAvant;

    @BeforeBatch(batch = "deblocage")
    public static void avant(ServerLevel l) { delaiAvant = Coince.DELAI; Coince.DELAI = 300; }

    @AfterBatch(batch = "deblocage")
    public static void apres(ServerLevel l) { Coince.DELAI = delaiAvant; }

    /** Un sol de pierre et, autour de (12, 4, 12), un enclos de planches (une construction : il n'y touche pas) de 4 blocs de haut. */
    static void enclos(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) for (int y = 4; y <= 7; y++) h.setBlock(12 + dx, y, 12 + dz, Blocks.OAK_PLANKS);
    }

    static int planches(GameTestHelper h) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 7; y++)
            if (h.getBlockState(new BlockPos(12 + dx, y, 12 + dz)).is(Blocks.OAK_PLANKS)) n++;
        return n;
    }

    /**
     * Enfermé dans un enclos de planches de 4 blocs de haut (qu'il ne peut pas creuser), de la pierre taillée en poche,
     * son bureau dehors à 10 blocs : il monte sur des blocs posés sous lui, sort par le haut du mur et rejoint son bureau.
     * Avant, il se disait coincé et y restait.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void sortEnPosantDesBlocs(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(planches(h) == 32, "il ne doit pas casser l'enclos (" + e + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + e + ")");
            h.assertTrue(h.getBlockState(new BlockPos(12, 4, 12)).is(Blocks.COBBLESTONE), "il doit être sorti sur un pilier de blocs posés (" + e + ")");
            t.discard();
        });
    }

    /**
     * Sur un îlot de pierre de 3 x 3, entouré d'une douve vide d'un bloc de large et de 10 de profondeur (trop haut pour
     * sauter), de la pierre en poche, son bureau de l'autre côté : il pose un bloc de pont et passe.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void passeEnPosantUnPont(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++)
            if (Math.max(Math.abs(x - 12), Math.abs(z - 12)) != 2) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 11, 3));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit avoir passé la douve et rejoint son bureau (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:cobblestone")) < 16, "il doit avoir posé un bloc (" + e + ")");
            t.discard();
        });
    }

    /**
     * Le même enclos, rien à poser ni à creuser : au bout du délai (abaissé à 15 s), il est ramené près de son bureau, et
     * le poste en avertit son chef par un avis.
     */
    @GameTest(template = "grand", timeoutTicks = 900, skyAccess = true, batch = "deblocage")
    public static void rameneAuPosteEnDernierRecours(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, ItemStack.EMPTY, new BlockPos(12, 4, 2));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        var boite = Courrier.de(h.getLevel().getServer()).boite(t.chef);
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 9, "il doit avoir été ramené près du bureau (" + e + ")");
            h.assertTrue(planches(h) == 32, "l'enclos ne doit pas être cassé (" + e + ")");
            h.assertTrue(boite.stream().anyMatch(l -> l.texte.contains(t.prenom) && l.texte.contains("coincé")), "le chef doit avoir un avis (" + boite.size() + " lettres)");
            t.discard();
        });
    }

    /**
     * Garde-fou : un ouvrier qui marche normalement (une centaine de blocs, plus long que le délai abaissé) n'est jamais
     * téléporté : aucun saut de plus de 2 blocs d'un tick à l'autre.
     */
    @GameTest(template = "vide", timeoutTicks = 700, batch = "deblocage")
    public static void quiMarcheNEstJamaisRamene(GameTestHelper h) {
        BlockPos bureau = TestsJeu.sol(h, 0, 97000);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(bureau.east(2)), ItemStack.EMPTY);
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(TestsJeu.sol(h, 120, 97000)), 2));
        Vec3[] avant = {t.position()};
        double[] saut = {0};
        h.onEachTick(() -> { saut[0] = Math.max(saut[0], t.position().distanceTo(avant[0])); avant[0] = t.position(); });
        h.runAtTickTime(h.getTick() + 600, () -> {
            String e = TestsJeu.etat(t, bureau) + ", " + t.diag;
            int loin = TestsJeu.distance(t, bureau);
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit jamais être téléporté (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.assertTrue(loin > 40, "il doit avoir marché (" + e + ")");
            h.succeed();
        });
    }
}

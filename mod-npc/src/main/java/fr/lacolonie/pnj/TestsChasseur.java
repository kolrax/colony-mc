package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Le chasseur (lot 6) : il tire un cochon sauvage et dépose la viande, laisse les bêtes d'un enclos, une bête nommée et un
 * petit, écrit sans arc ni lame, ne blesse ni un ouvrier ni un villageois derrière sa proie, et chasse sur ordre l'espèce
 * dite. Chaque test dans son coin du monde plat (coins 170 à 176).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsChasseur {
    /** Un chasseur seul, au travail, avec un arc et 16 flèches (ou rien si {@code arc} est faux) ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base, boolean arc) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "chasseur", base[0], Vec3.atBottomCenterOf(base[0].west(2)), arc ? new ItemStack(Items.BOW) : ItemStack.EMPTY);
        if (arc) t.inventaire.addItem(new ItemStack(Items.ARROW, 16));
        t.forcerChunk();
        return t;
    }

    /** {@code n} bêtes de ce type en ligne, à {@code dx} blocs à l'est du poste. */
    static <T extends Animal> List<T> betes(ServerLevel l, EntityType<T> type, BlockPos base, int dx, int n) {
        List<T> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(TestsEleveur.bete(l, type, base.offset(dx, 0, 2 * i - n), false, null));
        return r;
    }

    static long vivants(List<? extends Animal> l) { return l.stream().filter(Animal::isAlive).count(); }

    /** Quatre cochons sauvages : il en tire deux (il en laisse toujours deux), la viande va au dépôt, rien ne traîne. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void tueUnCochonSauvageEtDeposeLaViande(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 170, b, true);
        ServerLevel l = h.getLevel();
        List<Pig> cochons = betes(l, EntityType.PIG, b[0], 10, 4);
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.PORKCHOP) >= 1, "la viande au dépôt : " + TestsBucheron.compte(d, Items.PORKCHOP) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(vivants(cochons) == 2, "il en tire deux, et en laisse deux : " + vivants(cochons));
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:porkchop")) == 0, "rien gardé dans le sac");
            t.discard();
        });
    }

    /** Quatre cochons dans un enclos : ce ne sont pas du gibier. Il n'y touche pas, et écrit qu'il n'y a pas de gibier. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void laisseLesBetesDUnEnclos(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 171, b, true);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(10, 0, 0);
        TestsEleveur.enclos(l, centre);
        List<Pig> cochons = new ArrayList<>();
        for (int i = 0; i < 4; i++) cochons.add(TestsEleveur.bete(l, EntityType.PIG, centre.offset(1, 0, i - 2), false, null));
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "sans_gibier"), "il cherche, et écrit qu'il n'y a pas de gibier (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 400, "il a eu le temps de chasser");
            h.assertTrue(vivants(cochons) == 4, "les cochons de l'enclos sont tous vivants : " + vivants(cochons));
            t.discard();
        });
    }

    /**
     * Un grand pré clos de barrières (39 sur 39, plus de 1024 cases : trop grand pour en faire le tour) : on ne sait pas si
     * c'est un enclos, donc ses 4 cochons ne sont pas du gibier. Il n'y touche pas, et écrit qu'il n'y a pas de gibier.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void laisseLesBetesDUnGrandPreClos(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 176, b, true);
        ServerLevel l = h.getLevel();
        for (int pass = 0; pass < 2; pass++)
            for (int dx = 4; dx <= 44; dx++)
                for (int dz = -20; dz <= 20; dz++) {
                    if (dx != 4 && dx != 44 && Math.abs(dz) != 20) continue;
                    BlockPos q = b[0].offset(dx, 0, dz);
                    l.setBlockAndUpdate(q, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), l, q));
                }
        List<Pig> cochons = betes(l, EntityType.PIG, b[0], 12, 4);
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "sans_gibier"), "il cherche, et écrit qu'il n'y a pas de gibier (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 400, "il a eu le temps de chasser");
            h.assertTrue(vivants(cochons) == 4, "les cochons du pré clos sont tous vivants : " + vivants(cochons));
            t.discard();
        });
    }

    /** Rosette (un nom) et un porcelet, les plus proches, et trois cochons sauvages : il en tire un, jamais Rosette ni le petit. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void laisseLaBeteNommeeEtLePetit(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 172, b, true);
        ServerLevel l = h.getLevel();
        Pig rosette = TestsEleveur.bete(l, EntityType.PIG, b[0].offset(6, 0, 0), false, "Rosette");
        Pig petit = TestsEleveur.bete(l, EntityType.PIG, b[0].offset(6, 0, 2), true, null);
        List<Pig> autres = betes(l, EntityType.PIG, b[0], 12, 3);
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(TestsBucheron.compte(d, Items.PORKCHOP) >= 1, "la viande au dépôt (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(rosette.isAlive() && petit.isAlive(), "Rosette et le porcelet sont vivants");
            h.assertTrue(vivants(autres) == 1, "Rosette compte parmi les deux qu'il laisse : il en tire deux des trois autres : " + vivants(autres));
            t.discard();
        });
    }

    /** Ni arc ni lame, rien au dépôt : il écrit à son chef, et ne touche pas aux cochons. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void sansArmeIlEcrit(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 173, b, false);
        List<Pig> cochons = betes(h.getLevel(), EntityType.PIG, b[0], 6, 4);
        h.succeedWhen(() -> {
            h.assertTrue(TestsMineur.lettre(t, "arc"), "il demande un arc (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.tickCount > 200 && vivants(cochons) == 4, "les cochons sont vivants : " + vivants(cochons));
            t.discard();
        });
    }

    /**
     * Un ouvrier et un villageois juste derrière les cochons, dans sa ligne de tir : il tire ses cochons, et ses flèches
     * (marquées) ne blessent ni l'un ni l'autre.
     */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void neBlessePersonneDerriereLaProie(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 174, b, true);
        ServerLevel l = h.getLevel();
        List<Pig> cochons = betes(l, EntityType.PIG, b[0], 12, 4);
        Travailleur autre = TestsJeu.employe(h, "eleveur", b[0], Vec3.atBottomCenterOf(b[0].offset(14, 0, -1)), ItemStack.EMPTY);
        Villager v = EntityType.VILLAGER.spawn(l, b[0].offset(14, 0, 1), net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED);
        v.setNoAi(true);
        autre.setNoAi(true);
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(vivants(cochons) == 2 && TestsBucheron.compte(d, Items.PORKCHOP) >= 1, "il tire deux cochons : " + vivants(cochons) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(autre.getHealth() == autre.getMaxHealth() && v.getHealth() == v.getMaxHealth(), "personne n'est blessé : ouvrier " + autre.getHealth() + ", villageois " + v.getHealth());
            t.discard();
            autre.discard();
            v.discard();
        });
    }

    /** « Chasse deux poules » : quatre poules et trois cochons ; il tire deux poules, aucun cochon, et le dit. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void chasseSurOrdreLEspeceDite(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 175 * 400, 60000);
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur g = TestsJeu.employe(h, "chasseur", base, c.add(-0.5, 0, 2.5), new ItemStack(Items.BOW));
        g.inventaire.addItem(new ItemStack(Items.ARROW, 16));
        g.chef = chef.getUUID();
        g.prenom = "Gaston";
        g.forcerChunk();
        Travailleur[] e = {g};
        ServerLevel l = h.getLevel();
        List<Chicken> poules = betes(l, EntityType.CHICKEN, base, 9, 4);
        List<Pig> cochons = betes(l, EntityType.PIG, base, 5, 3);
        g.memoire.putLong("chasse_repos", Long.MAX_VALUE); // seul, il ne chasserait pas : seulement sur ordre
        Essais.etapes(h, chef, e,
                () -> g.parler(chef, "Chasse deux poules"),
                () -> {
                    h.assertTrue(g.consigne == null, "la consigne est finie (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")");
                    h.assertTrue(vivants(poules) == 2, "deux poules tirées : " + vivants(poules) + " vivantes");
                    h.assertTrue(vivants(cochons) == 3, "aucun cochon : " + vivants(cochons));
                    h.assertTrue(TestsBucheron.compte(TestsBucheron.depot(g), Items.CHICKEN) >= 1, "la volaille au dépôt");
                },
                () -> TestsRepertoire.fin(e));
    }
}

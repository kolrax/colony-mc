package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Les métiers branchés sur les missions (lot 16, refonte parties 1 à 3) : un éleveur sans enclos est bloqué avec sa raison
 * au-dessus de la tête, une mission hors de ses horaires le renvoie attendre au poste, et une mission de fermier avec un
 * lieu se cultive à ce lieu ; sans lieu ni champ, le fermier fait le sien près de l'eau. Le jour (l'heure réglée pour le
 * lot) ; chaque test dans son coin du monde plat (coins 771 à 774), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMetiersMission {
    @BeforeBatch(batch = "metiers_mission")
    public static void avant(ServerLevel l) { l.setDayTime(1000); } // 7 h du matin

    /** Un éleveur sans aucun enclos autour du poste : « ⚠ Pas d'enclos » au-dessus de sa tête. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "metiers_mission")
    public static void eleveurSansEnclos(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "eleveur", 771, ItemStack.EMPTY);
        h.assertTrue(Missions.donner(chef, t, "cuir", "stock", 10, "toujours", null).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            h.assertTrue(s.equals(Statut.bloque(Statut.ENCLOS)), "statut : « " + s + " » (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** Une mission « la nuit », donnée de jour, à 10 blocs du poste : « Hors horaires », et il rentre attendre au poste. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "metiers_mission")
    public static void horsHorairesAuPoste(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "mineur", 772, new ItemStack(Items.IRON_PICKAXE));
        Vec3 loin = Vec3.atBottomCenterOf(t.bureau.east(10));
        t.moveTo(loin.x, loin.y, loin.z);
        h.assertTrue(Missions.donner(chef, t, "fer", "fois", 20, "nuit", null).ok(), "mission acceptée");
        h.assertTrue(t.missionActive() == null, "de jour, la mission de nuit n'est pas à faire");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            double d = Math.sqrt(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)));
            h.assertTrue(s.equals(Statut.HORS_HORAIRES), "statut : « " + s + " »");
            h.assertTrue(d < 5, "revenu au poste : à " + (int) d + " blocs (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** « Cultive le blé », avec un lieu à 12 blocs du poste : il y laboure et sème son champ, et le retient. */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "metiers_mission")
    public static void fermierAuLieuDonne(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "fermier", 773, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(2, new ItemStack(Items.IRON_HOE));
        BlockPos lieu = t.bureau.offset(12, 0, 4);
        h.assertTrue(Missions.donner(chef, t, "ble", "stock", 64, "toujours", lieu).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            int semees = 0;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (l.getBlockState(lieu.offset(x, 0, z)).is(Blocks.WHEAT)) semees++;
            h.assertTrue(semees >= 20, "blé semé autour du lieu : " + semees + " (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(Fermier.parcelles(t).stream().anyMatch(p -> Fermier.distance(p.centre, lieu) <= 8 * 8), "le champ du lieu est retenu");
            t.discard();
        });
    }

    /** Sans lieu ni champ, une mare à 16 blocs du poste : il fait son champ de 9 sur 9 sur la terre plate à côté, et le retient. */
    @GameTest(template = "vide", timeoutTicks = 1600, batch = "metiers_mission")
    public static void fermierChampPresDeLEau(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = TestsMission.ouvrier(h, chef, "fermier", 774, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        BlockPos mare = t.bureau.offset(16, -1, 0);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) l.setBlockAndUpdate(mare.offset(x, 0, z), Blocks.WATER.defaultBlockState());
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
        d.depot.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 30));
        d.depot.setItem(2, new ItemStack(Items.IRON_HOE));
        h.assertTrue(Missions.donner(chef, t, "ble", "stock", 64, "toujours", null).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            int semees = 0;
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) if (l.getBlockState(mare.offset(x, 1, z)).is(Blocks.WHEAT)) semees++;
            h.assertTrue(semees >= 20, "blé semé près de la mare : " + semees + " (" + t.statutTete() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertFalse(Fermier.parcelles(t).isEmpty(), "le champ est retenu");
            t.discard();
        });
    }
}

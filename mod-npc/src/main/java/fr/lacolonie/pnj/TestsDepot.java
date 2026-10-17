package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 9, cahier 6.4 : un autre joueur ouvre le dépôt du poste (ici un coffre collé au bureau) devant le guerrier du chef.
 * Sans ordre, il s'interpose et l'avertit, sans frapper ; sur « attaque ceux qui volent le dépôt », il le somme, puis le
 * frappe s'il rouvre ; un joueur
 * autorisé (dit à un autre ouvrier : toute la colonie le sait) ou le chef lui-même, rien. Sans guerrier, un ouvrier le
 * signale et le chef reçoit une lettre (une seule). Voir {@link GardeDepot} et {@link Defense#depotOuvert}. Coins 238 à 242.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsDepot {
    /** La scène de la frappe du chef ({@link TestsChef#scene}), le bureau devenu poste du chef, un coffre collé à lui ; l'autre joueur va près du coffre. */
    static TestsChef.Scene scene(GameTestHelper h, int coin) {
        TestsChef.Scene s = TestsChef.scene(h, coin);
        Courrier.de(h.getLevel().getServer()).definirPoste(s.chef().getUUID(), h.getLevel(), s.b());
        h.getLevel().setBlockAndUpdate(coffre(s), Blocks.CHEST.defaultBlockState());
        s.autre().teleportTo(h.getLevel(), s.b().getX() + 3.5, s.b().getY(), s.b().getZ() + 1.5, 0, 0);
        return s;
    }

    static BlockPos coffre(TestsChef.Scene s) { return s.b().east(); }

    /** {@code p} ouvre le coffre de la main, comme un vrai joueur (clic droit), puis le referme. */
    static void ouvrir(GameTestHelper h, TestsChef.Scene s, ServerPlayer p) {
        BlockPos c = coffre(s);
        p.gameMode.useItemOn(p, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(c), Direction.UP, c, false));
        p.closeContainer();
    }

    static void finir(TestsChef.Scene s, Travailleur... autres) {
        Courrier c = Courrier.de(s.chef().getServer());
        c.oublierPoste(s.chef().getUUID(), s.chef().level(), s.b());
        c.permis.remove(s.chef().getUUID());
        TestsGuerrier.fin(s.g());
        TestsGuerrier.fin(autres);
        s.partir();
    }

    /** Ce qu'on surveille : il a attaqué l'autre ; il s'est tenu entre le dépôt et lui. */
    static boolean[] surveiller(GameTestHelper h, TestsChef.Scene s) {
        boolean[] vu = {false, false};
        h.onEachTick(() -> {
            if (s.g().action instanceof Actions.Attaquer a && a.cible == s.autre()) vu[0] = true;
            if (s.g().action instanceof Defense.Interposer i && i.lieu != null && i.agresseur == s.autre()) vu[1] = true;
        });
        return vu;
    }

    /** Sans ordre : il s'interpose et l'avertit, sans frapper ; le chef, tout près, l'apprend de vive voix. Épargné n'est pas autorisé. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void sansOrdreSInterpose(GameTestHelper h) {
        TestsChef.Scene s = scene(h, 238);
        String nom = s.autre().getGameProfile().getName();
        s.g().appliquer(Ordres.lire("Ne fais jamais rien à " + nom + ".", "guerrier"), s.chef());
        boolean[] vu = surveiller(h, s);
        h.runAtTickTime(h.getTick() + 10, () -> ouvrir(h, s, s.autre()));
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(vu[0], "il ne doit pas attaquer l'autre joueur");
            h.assertTrue(s.autre().getHealth() == s.autre().getMaxHealth(), "l'autre ne prend aucun coup");
            h.assertTrue(vu[1], "il se met entre le dépôt et l'autre (" + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            h.assertTrue(Essais.aDit(s.g(), "depot_interpose"), "il l'avertit (" + s.g().paroles + ")");
            h.assertTrue(Essais.aDit(s.g(), "depot_prevenir"), "il prévient le chef (" + s.g().paroles + ")");
            finir(s);
            h.succeed();
        });
    }

    /**
     * « Attaque ceux qui volent le dépôt » : à la première ouverture, il s'interpose et le somme, sans frapper ; l'autre
     * rouvre : il le frappe. Puis « reste tranquille » calme aussi la garde du dépôt.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void ordreAttaqueFrappe(GameTestHelper h) {
        TestsChef.Scene s = scene(h, 239);
        String r = s.g().appliquer(Ordres.lire("Attaque ceux qui volent le dépôt.", "guerrier"), s.chef());
        h.assertTrue(GardeChef.posture(s.g().memoire.getString("garde_depot")) == GardeChef.Posture.ATTAQUE, "l'ordre est retenu (" + r + ")");
        h.assertTrue(Vie.vient("depot_ordre_attaque", r), "il le redit : " + r);
        boolean[] vu = surveiller(h, s);
        h.runAtTickTime(h.getTick() + 10, () -> ouvrir(h, s, s.autre()));
        h.runAtTickTime(h.getTick() + 50, () -> {
            h.assertFalse(vu[0], "première fois : pas d'attaque");
            h.assertTrue(s.autre().getHealth() == s.autre().getMaxHealth(), "première fois : aucun coup");
            h.assertTrue(vu[1], "il s'interpose (" + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            h.assertTrue(Essais.aDit(s.g(), "depot_sommation"), "il le somme (" + s.g().paroles + ")");
            ouvrir(h, s, s.autre());
        });
        h.succeedWhen(() -> {
            h.assertTrue(s.autre().getHealth() < s.autre().getMaxHealth(), "il rouvre : il le frappe (attaque lancée " + vu[0] + ", " + s.g().paroles + ")");
            h.assertTrue(Essais.aDit(s.g(), "depot_riposte"), "il le dit (" + s.g().paroles + ")");
            s.g().appliquer(Ordres.lire("Reste tranquille.", "guerrier"), s.chef());
            h.assertTrue(GardeChef.posture(s.g().memoire.getString("garde_depot")) == GardeChef.Posture.TRANQUILLE, "« reste tranquille » calme aussi le dépôt");
            finir(s);
        });
    }

    /**
     * « X peut se servir dans le dépôt », dit au mineur : le guerrier, qui a l'ordre de frapper, laisse X ouvrir (la colonie
     * le sait). Puis « X n'a plus le droit », dit au mineur : X rouvre, le guerrier réagit.
     */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void autoriseRienPuisRetire(GameTestHelper h) {
        TestsChef.Scene s = scene(h, 240);
        String nom = s.autre().getGameProfile().getName();
        Travailleur m = s.ouvrier("mineur", 4.5, 7.5, new ItemStack(Items.IRON_PICKAXE));
        m.mode = Travailleur.Mode.ATTENDRE;
        s.g().appliquer(Ordres.lire("Attaque ceux qui volent le dépôt.", "guerrier"), s.chef());
        String r = m.appliquer(Ordres.lire(nom + " peut se servir dans le dépôt.", "mineur"), s.chef());
        h.assertTrue(Vie.vient("depot_ordre_permis", r), "le mineur le redit : " + r);
        h.assertTrue(Courrier.de(h.getLevel().getServer()).permis(s.chef().getUUID(), nom), "toute la colonie le sait");
        boolean[] vu = surveiller(h, s);
        h.runAtTickTime(h.getTick() + 10, () -> ouvrir(h, s, s.autre()));
        h.runAtTickTime(h.getTick() + 90, () -> {
            h.assertFalse(vu[0] || vu[1], "autorisé : rien (" + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            h.assertFalse(Essais.aDit(s.g(), "depot_interpose") || Essais.aDit(s.g(), "depot_riposte") || Essais.aDit(s.g(), "depot_prevenir"),
                    "pas un mot (" + s.g().paroles + ")");
            String r2 = m.appliquer(Ordres.lire(nom + " n'a plus le droit.", "mineur"), s.chef());
            h.assertTrue(Vie.vient("depot_ordre_retire", r2), "le mineur le redit : " + r2);
            ouvrir(h, s, s.autre());
        });
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertTrue(vu[0] || vu[1], "retiré : il réagit (" + s.g().paroles + ")");
            finir(s, m);
            h.succeed();
        });
    }

    /** Le chef ouvre son propre dépôt : rien, même avec l'ordre de frapper ; l'autre ouvre ensuite : il réagit. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void chefLuiMemeRien(GameTestHelper h) {
        TestsChef.Scene s = scene(h, 241);
        s.g().appliquer(Ordres.lire("Défends le dépôt même par la force.", "guerrier"), s.chef());
        boolean[] chef = {false};
        h.onEachTick(() -> { if (s.g().action instanceof Actions.Attaquer a && a.cible == s.chef() || s.g().action instanceof Defense.Interposer) chef[0] = true; });
        h.runAtTickTime(h.getTick() + 10, () -> ouvrir(h, s, s.chef()));
        boolean[] vu = surveiller(h, s);
        h.runAtTickTime(h.getTick() + 90, () -> {
            h.assertFalse(chef[0], "le chef ouvre : rien");
            h.assertTrue(s.chef().getHealth() == s.chef().getMaxHealth(), "le chef ne prend aucun coup");
            ouvrir(h, s, s.autre());
        });
        h.runAtTickTime(h.getTick() + 300, () -> {
            h.assertTrue(vu[0] || vu[1], "l'autre ouvre : il réagit (" + s.g().paroles + ")");
            finir(s);
            h.succeed();
        });
    }

    /** Sans guerrier, le chef loin : un mineur qui voit l'autre le signale, et une seule lettre part pour le chef, même s'il rouvre. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void ouvrierSignaleEtLettre(GameTestHelper h) {
        TestsChef.Scene s = scene(h, 242);
        Travailleur m = s.ouvrier("mineur", 4.5, 4.5, new ItemStack(Items.IRON_PICKAXE));
        m.mode = Travailleur.Mode.ATTENDRE;
        s.g().discard();
        s.chef().teleportTo(h.getLevel(), s.b().getX() + 60.5, s.b().getY() + 20, s.b().getZ() + 60.5, 0, 0);
        Courrier c = Courrier.de(h.getLevel().getServer());
        int avant = c.boites.getOrDefault(s.chef().getUUID(), java.util.List.of()).size();
        h.runAtTickTime(h.getTick() + 10, () -> ouvrir(h, s, s.autre()));
        h.runAtTickTime(h.getTick() + 40, () -> ouvrir(h, s, s.autre()));
        h.runAtTickTime(h.getTick() + 80, () -> {
            h.assertTrue(Essais.aDit(m, "depot_vu"), "le mineur le signale (" + m.paroles + ")");
            long dits = m.paroles.stream().filter(p -> Vie.vient("depot_vu", p)).count();
            h.assertTrue(dits == 1, "une seule fois (" + m.paroles + ")");
            int lettres = m.lettresADeposer.size() + c.boites.getOrDefault(s.chef().getUUID(), java.util.List.of()).size() - avant
                    + (int) c.vols.stream().filter(v -> s.chef().getUUID().equals(v.chef)).count();
            h.assertTrue(lettres == 1, "une seule lettre pour le chef (" + lettres + ")");
            finir(s, m);
            h.succeed();
        });
    }
}

package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 9, cahier 14.6 : un autre joueur frappe le chef devant son guerrier. Sans ordre, ou « reste tranquille », il
 * s'interpose sans frapper ; « attaque quiconque me frappe », il frappe ; « ne fais jamais rien à X », il laisse faire
 * (voir {@link GardeChef} et {@link Defense#chefFrappe}).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsChef {
    /** Un guerrier armé en (2, 4), son chef en (6, 4), un autre joueur en (9, 4), tous deux pouvant prendre des coups. */
    record Scene(Travailleur g, ServerPlayer chef, ServerPlayer autre) {}

    static Scene scene(GameTestHelper h) {
        for (int x = 0; x <= 12; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.getLevel().getServer().setPvpAllowed(true);
        Travailleur g = TestsJeu.ouvrier(h, "guerrier", 2, 2, 4, new ItemStack(Items.IRON_SWORD));
        Guerrier.armer(g);
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        for (ServerPlayer p : new ServerPlayer[]{chef, autre}) p.setGameMode(GameType.SURVIVAL);
        Vec3 c = h.absoluteVec(new Vec3(6.5, 2, 4.5)), a = h.absoluteVec(new Vec3(9.5, 2, 4.5));
        chef.moveTo(c.x, c.y, c.z);
        autre.moveTo(a.x, a.y, a.z);
        g.chef = chef.getUUID();
        return new Scene(g, chef, autre);
    }

    /** L'autre frappe le chef (après que le guerrier est bien là). */
    static void frapper(GameTestHelper h, Scene s) {
        h.runAtTickTime(h.getTick() + 10, () -> s.chef().hurt(s.chef().damageSources().playerAttack(s.autre()), 1));
    }

    /** Ce qu'on surveille à chaque tick : il a attaqué l'autre ; il s'est tenu entre eux deux. */
    static boolean[] surveiller(GameTestHelper h, Scene s) {
        boolean[] vu = {false, false};
        h.onEachTick(() -> {
            if (s.g().action instanceof Actions.Attaquer a && a.cible == s.autre()) vu[0] = true;
            if (s.g().action instanceof Defense.Interposer && s.g().getX() > s.chef().getX() + 0.3 && s.g().getX() < s.autre().getX() - 0.3
                    && Math.abs(s.g().getZ() - s.chef().getZ()) < 1.5) vu[1] = true;
        });
        return vu;
    }

    static void sInterpose(GameTestHelper h, Scene s, boolean[] vu, Travailleur... autres) {
        h.runAtTickTime(h.getTick() + 160, () -> {
            h.assertFalse(vu[0], "il ne doit pas attaquer l'autre joueur");
            h.assertTrue(s.autre().getHealth() == s.autre().getMaxHealth(), "l'autre ne prend aucun coup (vie " + s.autre().getHealth() + ")");
            h.assertTrue(vu[1], "il se met entre le chef et l'autre (" + (s.g().action == null ? "-" : s.g().action.nom) + ", x " + s.g().getX() + ")");
            h.assertTrue(TestsCaractere.aDit(s.g(), "chef_interpose"), "il le met en garde (" + s.g().paroles + ")");
            for (Travailleur o : autres) h.assertTrue(TestsCaractere.aDit(o, "chef_frappe_vu"), "l'ouvrier réagit (" + o.paroles + ")");
            TestsGuerrier.fin(s.g());
            TestsGuerrier.fin(autres);
            h.succeed();
        });
    }

    /** Sans ordre : il s'interpose et le met en garde, sans frapper ; un mineur à côté s'indigne. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void sansOrdreSInterpose(GameTestHelper h) {
        Scene s = scene(h);
        Travailleur m = TestsJeu.ouvrier(h, "mineur", 4, 2, 7, new ItemStack(Items.IRON_PICKAXE));
        m.chef = s.chef().getUUID();
        m.mode = Travailleur.Mode.ATTENDRE;
        boolean[] vu = surveiller(h, s);
        frapper(h, s);
        sInterpose(h, s, vu, m);
    }

    /** « Reste tranquille, ne frappe personne » : il s'interpose seulement. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void tranquilleSInterpose(GameTestHelper h) {
        Scene s = scene(h);
        s.g().appliquer(Ordres.lire("Reste tranquille, ne frappe personne.", "guerrier"), s.chef());
        h.assertTrue(GardeChef.posture(s.g().memoire.getString("garde_chef")) == GardeChef.Posture.TRANQUILLE, "l'ordre est retenu");
        boolean[] vu = surveiller(h, s);
        frapper(h, s);
        sInterpose(h, s, vu);
    }

    /** « Attaque quiconque me frappe » : il frappe l'autre joueur. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void ordreAttaqueFrappe(GameTestHelper h) {
        Scene s = scene(h);
        s.g().appliquer(Ordres.lire("Attaque quiconque me frappe.", "guerrier"), s.chef());
        frapper(h, s);
        h.succeedWhen(() -> {
            h.assertTrue(s.autre().getHealth() < s.autre().getMaxHealth(), "il frappe l'autre (" + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            h.assertTrue(TestsCaractere.aDit(s.g(), "chef_riposte"), "il le dit (" + s.g().paroles + ")");
            TestsGuerrier.fin(s.g());
        });
    }

    /** « Ne fais jamais rien à X » (avec « attaque quiconque me frappe ») : il ne fait rien à X. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void jamaisRienAX(GameTestHelper h) {
        Scene s = scene(h);
        String nom = s.autre().getGameProfile().getName();
        String r = s.g().appliquer(Ordres.lire("Attaque quiconque me frappe. Ne fais jamais rien à " + nom + ".", "guerrier"), s.chef());
        h.assertTrue(GardeChef.epargne(s.g().memoire.getString("epargnes"), nom), "il retient le nom (" + r + ")");
        boolean[] vu = surveiller(h, s);
        boolean[] bouge = {false};
        h.onEachTick(() -> { if (s.g().action instanceof Defense.Interposer) bouge[0] = true; });
        frapper(h, s);
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(vu[0] || bouge[0], "il ne doit rien faire à " + nom);
            h.assertTrue(s.autre().getHealth() == s.autre().getMaxHealth(), "l'autre ne prend aucun coup");
            h.assertFalse(TestsCaractere.aDit(s.g(), "chef_interpose") || TestsCaractere.aDit(s.g(), "chef_riposte"), "pas un mot (" + s.g().paroles + ")");
            TestsGuerrier.fin(s.g());
            h.succeed();
        });
    }
}

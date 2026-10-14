package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
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
    /**
     * Un guerrier armé en (1, 4), son chef en (4, 4), un autre joueur en (7, 4), tous deux pouvant prendre des coups. Tout
     * reste dans les 9 x 9 blocs du modèle « vide » : en x = 9, le mur de barrières du test cacherait la tête de l'autre.
     */
    record Scene(Travailleur g, ServerPlayer chef, ServerPlayer autre) {
        /** Les faux joueurs restent dans le monde après le test : on les retire ; le dernier test fini remet le PvP d'avant. */
        void partir() {
            chef.discard();
            autre.discard();
            if (--scenes == 0) chef.getServer().setPvpAllowed(pvpAvant);
        }
    }

    /** Les scènes en cours (les tests tournent ensemble) et le réglage du PvP avant la première. */
    static int scenes;
    static boolean pvpAvant;

    static Scene scene(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        if (scenes++ == 0) pvpAvant = h.getLevel().getServer().isPvpAllowed();
        h.getLevel().getServer().setPvpAllowed(true);
        Travailleur g = TestsJeu.ouvrier(h, "guerrier", 1, 2, 4, new ItemStack(Items.IRON_SWORD));
        Guerrier.armer(g);
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        for (ServerPlayer p : new ServerPlayer[]{chef, autre}) {
            p.setGameMode(GameType.SURVIVAL);
            // Un faux joueur n'est jamais « tické » : ses 3 s d'invulnérabilité à l'arrivée ne finiraient pas.
            ObfuscationReflectionHelper.setPrivateValue(ServerPlayer.class, p, 0, "spawnInvulnerableTime");
        }
        Vec3 c = h.absoluteVec(new Vec3(4.5, 2, 4.5)), a = h.absoluteVec(new Vec3(7.5, 2, 4.5));
        chef.moveTo(c.x, c.y, c.z);
        autre.moveTo(a.x, a.y, a.z);
        g.chef = chef.getUUID();
        return new Scene(g, chef, autre);
    }

    /** L'autre frappe le chef de sa main, comme un vrai joueur (après que le guerrier est bien là). */
    static void frapper(GameTestHelper h, Scene s) {
        h.runAtTickTime(h.getTick() + 10, () -> {
            s.autre().attack(s.chef());
            h.assertTrue(s.chef().getHealth() < s.chef().getMaxHealth(), "le chef prend le coup");
        });
    }

    /** Une flèche de l'autre touche le chef. */
    static void tirer(Scene s) {
        net.minecraft.world.entity.projectile.Arrow f = new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW, s.chef().level());
        f.setOwner(s.autre());
        s.chef().invulnerableTime = 0; // le faux joueur n'est pas « tické » : le délai entre deux coups ne s'écoulerait pas
        s.chef().hurt(s.chef().damageSources().arrow(f, s.autre()), 1);
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
            s.partir();
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
        h.assertTrue(GardeChef.posture(s.g().memoire.getString("garde_chef")) == GardeChef.Posture.ATTAQUE, "l'ordre est retenu");
        boolean[] vu = surveiller(h, s);
        frapper(h, s);
        h.succeedWhen(() -> {
            h.assertTrue(s.autre().getHealth() < s.autre().getMaxHealth(), "il frappe l'autre (" + (s.g().action == null ? "-" : s.g().action.nom) + ", attaque lancée " + vu[0]
                    + ", " + s.g().paroles + ", " + s.g().resultat + ")");
            h.assertTrue(TestsCaractere.aDit(s.g(), "chef_riposte"), "il le dit (" + s.g().paroles + ")");
            TestsGuerrier.fin(s.g());
            s.partir();
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
            s.partir();
            h.succeed();
        });
    }

    /** Le chef dit « reste tranquille ! » pendant que son guerrier frappe l'autre : il baisse son arme. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void tranquilleArreteLaRiposte(GameTestHelper h) {
        Scene s = scene(h);
        s.g().appliquer(Ordres.lire("Attaque quiconque me frappe.", "guerrier"), s.chef());
        boolean[] dit = {false};
        frapper(h, s);
        h.onEachTick(() -> {
            if (!dit[0] && s.g().action instanceof Actions.Attaquer a && a.cible == s.autre()) {
                dit[0] = true;
                s.g().appliquer(Ordres.lire("Reste tranquille !", "guerrier"), s.chef());
                h.assertFalse(s.g().action instanceof Actions.Attaquer, "il arrête de frapper");
                h.assertTrue(GardeChef.posture(s.g().memoire.getString("garde_chef")) == GardeChef.Posture.TRANQUILLE, "l'ordre change");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(dit[0], "l'attaque a commencé");
            TestsGuerrier.fin(s.g());
            s.partir();
        });
    }

    /**
     * Avec « attaque quiconque me frappe » : une flèche (qui visait peut-être autre chose) ne suffit pas, il s'interpose ;
     * une deuxième du même tireur en moins de 10 s, il attaque. Un coup sans geste visé (balayé, comme ici sans
     * {@code attack}) : il ne fait rien.
     */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void flecheUneFoisSInterpose(GameTestHelper h) {
        Scene s = scene(h);
        s.g().appliquer(Ordres.lire("Attaque quiconque me frappe.", "guerrier"), s.chef());
        boolean[] vu = surveiller(h, s);
        h.runAtTickTime(h.getTick() + 10, () -> {
            h.assertTrue(s.chef().hurt(s.chef().damageSources().playerAttack(s.autre()), 1), "touché"); // coup balayé : la cible visée était une autre
            h.assertTrue(s.g().action == null || !(s.g().action instanceof Defense.Interposer || s.g().action instanceof Actions.Attaquer), "un coup balayé ne compte pas");
        });
        h.runAtTickTime(h.getTick() + 30, () -> tirer(s));
        h.runAtTickTime(h.getTick() + 70, () -> {
            h.assertFalse(vu[0], "une seule flèche : pas d'attaque");
            h.assertTrue(s.g().action instanceof Defense.Interposer, "il s'interpose (" + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            tirer(s);
        });
        h.succeedWhen(() -> {
            h.assertTrue(vu[0], "deuxième flèche : il attaque");
            TestsGuerrier.fin(s.g());
            s.partir();
        });
    }

    /** Il attendait là où le chef l'a mis : il s'interpose, puis y retourne et attend encore ; un mineur ne promet pas de frapper. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void attendRevientASaPlace(GameTestHelper h) {
        Scene s = scene(h);
        Travailleur m = TestsJeu.ouvrier(h, "mineur", 4, 2, 7, new ItemStack(Items.IRON_PICKAXE));
        m.chef = s.chef().getUUID();
        String r = m.appliquer(Ordres.lire("Attaque quiconque me frappe.", "mineur"), s.chef());
        h.assertTrue(Vie.vient("chef_ordre_attaque_civil", r) || r.contains("je ne frapperai pas"), "le mineur ne promet pas de frapper : " + r);
        s.g().mode = Travailleur.Mode.ATTENDRE;
        Vec3 place = s.g().position();
        boolean[] parti = {false};
        h.onEachTick(() -> { if (s.g().action instanceof Defense.Interposer && s.g().position().distanceTo(place) > 2) parti[0] = true; });
        frapper(h, s);
        h.succeedWhen(() -> {
            h.assertTrue(parti[0], "il s'est interposé");
            h.assertFalse(s.g().action instanceof Defense.Interposer, "l'interposition est finie");
            h.assertTrue(s.g().position().distanceTo(place) < 1.5 && s.g().mode == Travailleur.Mode.ATTENDRE, "revenu à sa place, il attend ("
                    + s.g().position().distanceTo(place) + ", " + s.g().mode + ")");
            TestsGuerrier.fin(s.g(), m);
            s.partir();
        });
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Plan E, points 1 et 5 : un ouvrier seul à terre se relève seul une fois (peu de vie, très gravement blessé, il rentre
 * au poste se reposer) ; un deuxième K.O. pendant ce temps, ou la lave, le tuent ; le chef est prévenu (ligne rouge au
 * journal) et un compagnon qui a du pain le relève. Coins 961 à 965 du monde plat.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRelever {

    /** Ce que fait le compagnon, pour dire pourquoi il ne relève pas le blessé. */
    static String etat(Travailleur a, Travailleur blesse) {
        String act = a.action == null ? "aucune" : a.action.getClass().getSimpleName()
                + (a.action instanceof Blessures.Accourir x ? " arrivé " + x.arrive + " auprès " + x.aupres : "");
        return act + ", mode " + a.mode + ", à " + (int) Math.sqrt(a.distanceToSqr(blesse)) + " blocs, remède " + Blessures.remede(a)
                + ", faim " + a.faim + ", consigne " + a.consigne + ", peut accourir " + Blessures.peutAccourir(a) + ", vivant " + a.isAlive()
                + ", paroles " + a.paroles;
    }

    static Travailleur aTerre(GameTestHelper h, BlockPos base, BlockPos pos, UUID chef) {
        Travailleur t = TestsBlessures.ouvrier(h, "mineur", base, pos, chef, new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.hurt(t.damageSources().generic(), 100);
        h.assertTrue(t.aTerre && t.isAlive(), "à terre, pas mort");
        return t;
    }

    /**
     * Seul, au bout de son temps à terre, il se relève (avant : il mourait), avec 4 points de vie, très gravement blessé et
     * ralenti ; le journal le dit ; il rentre au poste (à 12 blocs) et s'y repose.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void seReleveSeulEtRentre(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 961 * 400, 60000);
        UUID chef = UUID.randomUUID();
        Travailleur t = aTerre(h, base, base.east(12), chef);
        t.mission("charbon", "stock", 64);
        t.aTerreTicks = 5;
        boolean[] debout = {false};
        float[] vie = {-1};
        h.onEachTick(() -> {
            if (!debout[0] && t.isAlive() && !t.aTerre) { debout[0] = true; vie[0] = t.getHealth(); }
        });
        h.succeedWhen(() -> {
            h.assertTrue(debout[0], "il se relève seul (vivant : " + t.isAlive() + ")");
            h.assertTrue(vie[0] > 0 && vie[0] <= Reglages.actuel.releve_seul_vie, "peu de vie : " + vie[0]);
            h.assertTrue(Blessures.gravite(t) == Gravite.TRES_GRAVE && TestsBlessures.lenteur(t) == 0.30, "très gravement blessé, ralenti");
            h.assertTrue(Blessures.releveSeul(t), "il s'en souvient");
            h.assertTrue(Courrier.de(h.getLevel().getServer()).boite(chef).stream().anyMatch(l -> l.objet.equals("Relevé seul")), "le journal le dit");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(base)) <= 5 * 5, "il est rentré au poste (" + TestsJeu.etat(t, base) + ")");
            h.assertTrue(t.action instanceof Actions.Attendre && t.isAlive(), "il s'y repose (" + t.action + ")");
            t.discard();
        });
    }

    /** Retombé à terre dans les 20 minutes après s'être relevé seul : sans aide, il meurt au bout de son temps à terre. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void deuxiemeChuteMortelle(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 962 * 400, 60000);
        Travailleur t = TestsBlessures.ouvrier(h, "mineur", base, base.east(2), UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.memoire.putLong(Blessures.RELEVE_SEUL, h.getLevel().getGameTime() - 10 * 60 * 20); // relevé seul il y a 10 minutes
        t.hurt(t.damageSources().generic(), 100);
        h.assertTrue(t.aTerre && t.isAlive(), "à terre");
        t.aTerreTicks = 5;
        h.succeedWhen(() -> h.assertFalse(t.isAlive(), "il meurt"));
    }

    /** Un bain de lave tue net, sans temps à terre (avant : à terre, puis relevé seul dans la lave). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void laveTueNet(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 963 * 400, 60000);
        Travailleur t = TestsBlessures.ouvrier(h, "mineur", base, base.east(2), UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.hurt(t.damageSources().lava(), 100);
        h.assertFalse(t.aTerre, "pas à terre");
        h.succeedWhen(() -> h.assertFalse(t.isAlive(), "mort dans la lave"));
    }

    /**
     * Il tombe : le journal du chef le dit en rouge (le chef, connecté, le lit aussi). Un compagnon qui a du pain dans son
     * sac (à 8 blocs) accourt et le relève avec. Avant : la ligne était orange et le compagnon ne faisait que regarder.
     */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void compagnonApporteAManger(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 964 * 400, 60000);
        var joueur = h.makeMockServerPlayerInLevel();
        UUID chef = joueur.getUUID();
        BlockPos lieu = base.south(6);
        Travailleur aide = TestsBlessures.ouvrier(h, "mineur", base, lieu.east(8), chef, new ItemStack(Items.IRON_PICKAXE));
        aide.inventaire.addItem(new ItemStack(Items.BREAD, 2));
        Travailleur blesse = aTerre(h, base, lieu, chef);
        var journal = Bureau.Entite.journal(Courrier.de(h.getLevel().getServer()).boite(chef));
        h.assertTrue(!journal.isEmpty() && journal.getCompound(0).getString("ligne").startsWith(net.minecraft.ChatFormatting.RED.toString()),
                "ligne rouge au journal : " + (journal.isEmpty() ? "rien" : journal.getCompound(0).getString("ligne")));
        h.succeedWhen(() -> {
            h.assertTrue(blesse.isAlive() && !blesse.aTerre, "relevé par son compagnon (aide : " + etat(aide, blesse) + ")");
            h.assertTrue(aide.compter(Actions.Filtre.de("minecraft:bread")) == 1, "un pain donné : " + aide.compter(Actions.Filtre.de("minecraft:bread")));
            h.assertFalse(Blessures.releveSeul(blesse), "pas relevé seul");
            blesse.discard();
            aide.discard();
        });
    }
}

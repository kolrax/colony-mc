package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 7 : remarques spontanées ({@link Remarques}) et humour devant l'impossible ({@link Impossible}). Le lot « remarques »
 * tourne seul, avec un délai raccourci : ailleurs, sur le serveur de tests, les ouvriers ne font pas de remarques. Coins 220 à 222.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRemarques {
    static int delai;

    @BeforeBatch(batch = "remarques")
    public static void avant(ServerLevel l) {
        Remarques.enEssai = true;
        delai = Reglages.actuel.secondes_entre_remarques;
        Reglages.actuel.secondes_entre_remarques = 1;
    }

    @AfterBatch(batch = "remarques")
    public static void apres(ServerLevel l) {
        Remarques.enEssai = false;
        Reglages.actuel.secondes_entre_remarques = delai;
    }

    /** La remarque qu'il a faite, si sa dernière bulle en vient (null sinon). */
    static String remarque(Travailleur t) {
        String cle = t.memoire.getString("remarque");
        return !cle.isEmpty() && !t.paroles.isEmpty() && Vie.vient(cle, t.paroles.getLast()) ? cle : null;
    }

    /** Délai écoulé : un ouvrier dit de lui-même une remarque de remarques.txt, en bulle. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "remarques")
    public static void remarqueApresLeDelai(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 220 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        h.succeedWhen(() -> {
            String cle = remarque(t);
            h.assertTrue(cle != null && cle.startsWith("remarque_"), "il fait une remarque (" + t.paroles + ", " + TestsJeu.etat(t, base) + ")");
            t.discard();
        });
    }

    /** Deux ouvriers côte à côte : quand l'un a fait sa remarque, l'autre se tait pendant la minute qui suit. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "remarques")
    public static void pasDeuxVoisinsALaFois(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 221 * 400, 60000);
        Travailleur a = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        Travailleur b = TestsJeu.employe(h, "bucheron", base, Vec3.atBottomCenterOf(base.east(2)), new ItemStack(Items.IRON_AXE));
        a.forcerChunk();
        a.mode = b.mode = Travailleur.Mode.ATTENDRE; // ils restent côte à côte
        Travailleur[] premier = {null};
        long[] quand = {0};
        boolean[] deux = {false};
        h.onEachTick(() -> {
            for (Travailleur t : new Travailleur[]{a, b}) {
                if (remarque(t) == null) continue;
                if (premier[0] == null) { premier[0] = t; quand[0] = h.getTick(); }
                else if (t != premier[0]) deux[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(premier[0] != null, "l'un d'eux fait une remarque (" + a.paroles + " / " + b.paroles + ")");
            h.assertFalse(deux[0], "l'autre ne parle pas dans la même minute (" + a.paroles + " / " + b.paroles + ")");
            h.assertTrue(h.getTick() - quand[0] >= 500, "l'autre se tait encore");
            a.discard();
            b.discard();
        });
    }

    /** « Va sur la lune » : il en rit, avec une réplique impossible_lune, et ne part nulle part. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void vaSurLaLune(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 222);
        Travailleur b = e[0];
        Essais.etapes(h, chef, e,
                () -> {
                    Essais.regarder(chef, b);
                    b.parler(chef, "Va sur la lune, Bertrand !");
                    String r = Paroles.echange(b, 1);
                    h.assertTrue(r.startsWith("Toi : ") && Vie.vient("impossible_lune", r.substring(6)), "il en rit (" + Paroles.echange(b, 2) + ")");
                    h.assertFalse(b.action instanceof Actions.Aller, "il ne part pas");
                },
                () -> TestsOrdres.fin(h, e));
    }
}

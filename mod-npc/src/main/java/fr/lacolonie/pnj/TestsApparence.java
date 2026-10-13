package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Lot 8 : la tenue d'un ouvrier (donnée synchronisée {@link Travailleur#TENUE}) suit son embauche, son renvoi et sa sauvegarde. */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsApparence {

    @GameTest(template = "vide", timeoutTicks = 100)
    public static void tenueSelonEmbauche(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort("guerrier", 2);
        t.setNoAi(true);
        h.getLevel().addFreshEntity(t);
        h.assertTrue(t.tenue().equals("chomeur"), "sans chef, tenue de chômeur (" + t.tenue() + ")");

        int[] primes = Reglages.actuel.prime_par_niveau.clone();
        try {
            java.util.Arrays.fill(Reglages.actuel.prime_par_niveau, 0); // embauche gratuite : le faux chef n'a pas de pièces
            t.embaucher(chef);
        } finally {
            Reglages.actuel.prime_par_niveau = primes;
        }
        h.assertTrue(t.mode == Travailleur.Mode.SUIVRE, "embauché (" + t.mode + ")");
        h.assertTrue(t.tenue().equals("guerrier"), "embauché, tenue de son métier (" + t.tenue() + ")");

        t.commencerGreve();
        h.assertTrue(t.tenue().equals("guerrier"), "en grève, il garde sa tenue (" + t.tenue() + ")");

        CompoundTag tag = new CompoundTag();
        t.saveWithoutId(tag);
        Travailleur copie = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        copie.load(tag);
        h.assertTrue(copie.tenue().equals("guerrier"), "relu, tenue de son métier (" + copie.tenue() + ")");

        t.licencier();
        h.assertTrue(t.tenue().equals("chomeur"), "renvoyé, tenue de chômeur (" + t.tenue() + ")");
        t.discard();
        h.succeed();
    }
}

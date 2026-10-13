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

    /** Au-dessus de la tête : son métier (la tenue), son rôle « Métier · chef » et son icône d'état, posés par le serveur. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void teteSelonSituation(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort("mineur", 2);
        t.setNoAi(true);
        h.getLevel().addFreshEntity(t);
        h.assertTrue(t.role().equals("Cherche du travail"), "sans chef, il cherche du travail (« " + t.role() + " »)");
        h.assertTrue(t.etat().isEmpty(), "chercheur bien portant : pas d'icône (" + t.etat() + ")");

        int[] primes = Reglages.actuel.prime_par_niveau.clone();
        try {
            java.util.Arrays.fill(Reglages.actuel.prime_par_niveau, 0); // embauche gratuite : le faux chef n'a pas de pièces
            t.embaucher(chef);
        } finally {
            Reglages.actuel.prime_par_niveau = primes;
        }
        String attendu = "Mineur · " + chef.getName().getString();
        h.assertTrue(t.tenue().equals("mineur"), "embauché mineur, icône de mineur (" + t.tenue() + ")");
        h.assertTrue(t.role().equals(attendu), "embauché : « " + attendu + " » (« " + t.role() + " »)");

        t.faim = 4;
        t.majTete();
        h.assertTrue(t.etat().equals("faim"), "affamé : icône de faim (" + t.etat() + ")");
        t.faim = 20;
        t.besoin = "Il me faut une pioche, chef.";
        t.majTete();
        h.assertTrue(t.etat().equals("attente"), "il attend un objet : icône d'attente (" + t.etat() + ")");
        t.besoin = "";
        t.majTete();
        h.assertTrue(t.etat().isEmpty(), "rien ne lui manque : plus d'icône (" + t.etat() + ")");

        t.commencerGreve();
        h.assertTrue(t.etat().equals("greve"), "en grève : icône de grève, tout de suite (" + t.etat() + ")");
        t.besoin = "Il me faut une pioche, chef.";
        t.licencier();
        h.assertTrue(t.role().equals("Cherche du travail"), "renvoyé : il cherche du travail (« " + t.role() + " »)");
        h.assertTrue(t.etat().isEmpty(), "renvoyé : ni grève, ni attente, ni paie (" + t.etat() + ")");
        t.discard();

        // Sans rien appeler à la main : un chercheur qui vit sa vie voit son icône posée par son propre tick (une fois par seconde).
        Travailleur affame = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        affame.moveTo(p.x, p.y, p.z, 0, 0);
        affame.tirerAuSort("mineur", 2);
        affame.faim = 4; // après tirerAuSort (qui pose l'état) : seul le tick peut le voir
        h.getLevel().addFreshEntity(affame);
        h.assertTrue(affame.etat().isEmpty(), "pas encore revu (" + affame.etat() + ")");
        h.succeedWhen(() -> {
            h.assertTrue(affame.etat().equals("faim"), "au bout d'une seconde, icône de faim (" + affame.etat() + ")");
            affame.discard();
        });
    }
}

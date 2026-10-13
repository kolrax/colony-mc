package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Les conversations entre ouvriers d'un même chef (lot 7, {@link Causeries}). Le lot « conversations » tourne seul : ailleurs,
 * sur le serveur de tests, les ouvriers ne bavardent pas ({@link Causeries#enEssai}).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsConversations {
    static int intervalle;

    @BeforeBatch(batch = "conversations")
    public static void avant(ServerLevel l) {
        Causeries.enEssai = true;
        intervalle = Reglages.actuel.secondes_entre_conversations;
        Reglages.actuel.secondes_entre_conversations = 1; // intervalle raccourci : seule la menace empêche d'en recommencer une
    }

    @AfterBatch(batch = "conversations")
    public static void apres(ServerLevel l) {
        Causeries.enEssai = false;
        Reglages.actuel.secondes_entre_conversations = intervalle;
    }

    /** Un ouvrier du chef, mis à attendre au poste (oisif), en (x, 2, z) du test. */
    static Travailleur auPoste(GameTestHelper h, String metier, int x, int z, UUID chef) {
        Travailleur t = Travailleur.auTravail(h.getLevel(), metier, h.absolutePos(new BlockPos(0, 2, 0)), h.absoluteVec(new Vec3(x + 0.5, 2, z + 0.5)), chef, ItemStack.EMPTY);
        t.mode = Travailleur.Mode.ATTENDRE;
        return t;
    }

    static Causeries.Causerie causerie(Travailleur t) { return t.action instanceof Causeries.Bavarder b ? b.c : null; }

    /** Deux ouvriers du même chef attendent au poste à 3 blocs l'un de l'autre : A puis B disent les répliques d'une même conversation, dans l'ordre. */
    @GameTest(template = "vide", timeoutTicks = 900, batch = "conversations")
    public static void deuxOuvriersBavardent(GameTestHelper h) {
        UUID chef = UUID.randomUUID();
        Travailleur t1 = auPoste(h, "mineur", 2, 3, chef), t2 = auPoste(h, "bucheron", 5, 3, chef);
        Causeries.Causerie[] vue = new Causeries.Causerie[1];
        h.onEachTick(() -> { if (vue[0] == null) vue[0] = causerie(t1); });
        h.succeedWhen(() -> {
            Causeries.Causerie c = vue[0];
            h.assertTrue(c != null, "ils doivent se mettre à bavarder (" + TestsJeu.etat(t1, t1.bureau) + ")");
            h.assertTrue(c.a == t1 && c.b == t2 || c.a == t2 && c.b == t1, "la conversation doit être entre eux deux");
            h.assertTrue(causerie(t2) == c || c.fini, "les deux doivent bavarder ensemble");
            h.assertFalse(c.rompue, "la conversation ne doit pas être rompue après " + c.dites.size() + " répliques");
            h.assertTrue(c.fini, "la conversation doit aller au bout (" + c.dites.size() + "/" + c.textes.size() + ")");
            h.assertTrue(c.dites.size() == c.textes.size(), "toutes les répliques doivent être dites");
            for (int i = 0; i < c.dites.size(); i++) {
                Map.Entry<Travailleur, String> d = c.dites.get(i);
                h.assertTrue(d.getKey() == (i % 2 == 0 ? c.a : c.b), "réplique " + i + " : A et B parlent à tour de rôle, A d'abord");
                h.assertTrue(d.getValue().equals(c.textes.get(i)) && !d.getValue().contains("{"), "réplique " + i + " : « " + d.getValue() + " »");
            }
            h.assertTrue(Conversations.TOUTES.contains(c.conv), "une conversation du fichier");
            t1.discard();
            t2.discard();
        });
    }

    /** En pleine conversation, B est frappé par un monstre : elle cesse tout de suite pour les deux, et ne reprend pas tant qu'il est menacé. */
    @GameTest(template = "vide", timeoutTicks = 900, batch = "conversations")
    public static void frappeArreteLaCauserie(GameTestHelper h) {
        UUID chef = UUID.randomUUID();
        Travailleur t1 = auPoste(h, "mineur", 2, 3, chef), t2 = auPoste(h, "bucheron", 5, 3, chef);
        Causeries.Causerie[] vue = new Causeries.Causerie[1];
        long[] coup = {-1};
        Zombie[] z = new Zombie[1];
        h.onEachTick(() -> {
            if (vue[0] == null && (vue[0] = causerie(t1)) == null) return;
            if (coup[0] < 0 && vue[0].dites.size() >= 1) {
                Travailleur b = vue[0].b;
                z[0] = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(7, 2, 7));
                b.hurt(h.getLevel().damageSources().mobAttack(z[0]), 1f);
                coup[0] = h.getTick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(coup[0] >= 0, "ils doivent se mettre à bavarder (" + TestsJeu.etat(t1, t1.bureau) + ")");
            Causeries.Causerie c = vue[0];
            h.assertTrue(c.rompue && !c.fini, "la conversation doit être rompue");
            h.assertTrue(causerie(t1) == null && causerie(t2) == null, "aucun des deux ne bavarde plus");
            h.assertTrue(c.dites.size() < c.textes.size(), "elle ne va pas au bout");
            h.assertTrue(h.getTick() - coup[0] >= 200, "on attend 10 s de plus");
            if (z[0] != null) z[0].discard();
            t1.discard();
            t2.discard();
        });
        h.onEachTick(() -> {
            if (coup[0] >= 0 && h.getTick() - coup[0] > 20)
                h.assertTrue(causerie(t1) == null && causerie(t2) == null, "une nouvelle conversation ne doit pas commencer si vite");
        });
    }

    /** Deux ouvriers de chefs différents, côte à côte, oisifs : ils ne bavardent pas (cette passe : même chef seulement). */
    @GameTest(template = "vide", timeoutTicks = 500, batch = "conversations")
    public static void chefsDifferentsNeBavardentPas(GameTestHelper h) {
        Travailleur t1 = auPoste(h, "mineur", 2, 3, UUID.randomUUID()), t2 = auPoste(h, "bucheron", 5, 3, UUID.randomUUID());
        h.onEachTick(() -> h.assertTrue(causerie(t1) == null && causerie(t2) == null, "ils ne doivent pas bavarder"));
        h.runAtTickTime(400, () -> {
            t1.discard();
            t2.discard();
            h.succeed();
        });
    }
}

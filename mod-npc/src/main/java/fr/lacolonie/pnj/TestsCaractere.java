package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 5 : réactions selon le caractère. Le diamant gardé en cachette (cahier 14.3 ; lot « caractere », chance réglée à
 * 100 % pour que le fourbe triche à coup sûr) et les excuses du chef. Coins 230 à 234.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCaractere {
    static int chance;

    @BeforeBatch(batch = "caractere")
    public static void avant(ServerLevel l) {
        chance = Reglages.actuel.caractere_diamant;
        Reglages.actuel.caractere_diamant = 100;
    }

    @AfterBatch(batch = "caractere")
    public static void apres(ServerLevel l) { Reglages.actuel.caractere_diamant = chance; }

    /** Les diamants de sa cachette. */
    static int diamantsCaches(Travailleur t) {
        int n = 0;
        for (Tag x : t.memoire.getList("butin", Tag.TAG_COMPOUND))
            if (((CompoundTag) x).getString("id").equals("minecraft:diamond")) n += ((CompoundTag) x).getInt("count");
        return n;
    }

    static boolean aDit(Travailleur t, String cle) { return t.paroles.stream().anyMatch(p -> Vie.vient(cle, p)); }

    /** Un mineur de ce caractère, chargé de diamants, trois minerais de diamant à portée. */
    static Travailleur mineur(GameTestHelper h, int coin, String caractere, BlockPos[] b) {
        Travailleur t = TestsOutils.ouvrier(h, "mineur", coin, b, new ItemStack(Items.IRON_PICKAXE));
        t.temperament = caractere;
        t.moral = 50;
        t.mission("diamant", "stock", 64);
        for (int k = 1; k <= 3; k++) h.getLevel().setBlockAndUpdate(b[0].west(k).south(2), Blocks.DIAMOND_ORE.defaultBlockState());
        return t;
    }

    /**
     * Les trois minerais extraits, il est envoyé au dépôt (près du poste, il n'y va de lui-même que sac plein) ; puis
     * {@code fin} vérifie le résultat.
     */
    static void apresLeDepot(GameTestHelper h, Travailleur t, BlockPos[] b, Runnable fin) {
        boolean[] envoye = {false};
        h.succeedWhen(() -> {
            if (!envoye[0]) {
                for (int k = 1; k <= 3; k++)
                    h.assertTrue(h.getLevel().getBlockState(b[0].west(k).south(2)).isAir(), "il extrait les trois diamants (" + TestsJeu.etat(t, b[0]) + ")");
                envoye[0] = true;
                Cerveau.lancer(t, new Actions.Deposer(java.util.List.of()));
            }
            h.assertTrue(!(t.action instanceof Actions.Deposer), "il dépose (" + TestsJeu.etat(t, b[0]) + ")");
            fin.run();
            t.discard();
        });
    }

    /** Un fourbe trouve trois diamants : il en garde un, un seul, dans sa cachette (en marmonnant) ; les deux autres vont au dépôt. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "caractere")
    public static void fourbeGardeUnDiamant(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = mineur(h, 230, "fourbe", b);
        Bureau.Entite d = TestsBucheron.depot(t);
        apresLeDepot(h, t, b, () -> {
            h.assertTrue(TestsBucheron.compte(d, Items.DIAMOND) == 2, "deux diamants au dépôt (" + TestsBucheron.compte(d, Items.DIAMOND) + ", " + TestsJeu.etat(t, b[0]) + ")");
            h.assertTrue(diamantsCaches(t) == 1, "un seul diamant caché (" + t.memoire.get("butin") + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:diamond")) == 0, "aucun dans son sac");
            h.assertTrue(aDit(t, "caractere_diamant_cache"), "il marmonne (" + t.paroles + ")");
        });
    }

    /** Un calme trouve les mêmes diamants : il ne garde rien, tout va au dépôt, et il le dit. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "caractere")
    public static void calmeRendTout(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = mineur(h, 231, "calme", b);
        Bureau.Entite d = TestsBucheron.depot(t);
        apresLeDepot(h, t, b, () -> {
            h.assertTrue(TestsBucheron.compte(d, Items.DIAMOND) == 3, "les trois diamants au dépôt (" + TestsBucheron.compte(d, Items.DIAMOND) + ", " + TestsJeu.etat(t, b[0]) + ")");
            h.assertTrue(diamantsCaches(t) == 0, "rien de caché (" + t.memoire.get("butin") + ")");
            h.assertTrue(aDit(t, "caractere_diamant_rendu"), "il s'en vante (" + t.paroles + ")");
        });
    }

    /** Insulté puis frappé par son chef, il accepte ses excuses : la moitié du moral perdu revient, une seule fois. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void excusesDuChef(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = TestsIA.equipe(h, chef, 232);
        Travailleur b = e[0];
        int[] avant = {0};
        TestsIA.etapes(h, chef, e,
                () -> {
                    b.moral = 70;
                    TestsIA.regarder(chef, b);
                    b.parler(chef, "Tu es un bon à rien, Bertrand !");
                    b.hurt(h.getLevel().damageSources().playerAttack(chef), 1);
                    avant[0] = b.moral;
                    b.parler(chef, "Pardon, Bertrand.");
                },
                // Les vérifications à part : une étape qui échoue est rejouée à chaque tick, sans refaire l'insulte ni le coup.
                () -> {
                    h.assertTrue(avant[0] == 55, "insulte et coup : 15 de moins (" + avant[0] + ")");
                    String r = IA.echange(b, 1);
                    h.assertTrue(r.startsWith("Toi : ") && Vie.vient("caractere_excuses", r.substring(6)), "il accepte (" + IA.echange(b, 2) + ")");
                    h.assertTrue(b.moral == avant[0] + 8, "la moitié revient (" + avant[0] + " → " + b.moral + ")");
                },
                () -> b.parler(chef, "Pardon, Bertrand."),
                () -> {
                    h.assertTrue(b.moral == avant[0] + 8, "une seule fois par offense (" + b.moral + ")");
                    h.assertFalse(Vie.vient("caractere_excuses", IA.echange(b, 1).substring(6)), "rien de plus à pardonner (" + IA.echange(b, 1) + ")");
                },
                () -> TestsOrdres.fin(h, e));
    }

    /** « Tu as gardé un diamant ? » : un peureux qui en cache un avoue, et le diamant va au dépôt ; le reste de son butin ne bouge pas. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void avoueEtRendLeDiamant(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = TestsIA.equipe(h, chef, 233);
        Travailleur b = e[0];
        TestsIA.etapes(h, chef, e,
                () -> {
                    b.temperament = "peureux";
                    Humeur.cacher(b, new ItemStack(Items.DIAMOND));
                    Humeur.cacher(b, new ItemStack(Items.BREAD, 2));
                    TestsIA.regarder(chef, b);
                    b.parler(chef, "Tu as gardé un diamant, Bertrand ?");
                },
                () -> {
                    String r = IA.echange(b, 1);
                    h.assertTrue(r.startsWith("Toi : ") && Vie.vient("diamant_avoue", r.substring(6)), "il avoue (" + IA.echange(b, 2) + ")");
                    h.assertTrue(diamantsCaches(b) == 0 && Humeur.butin(b) == 2, "le pain reste caché, plus de diamant (" + b.memoire.get("butin") + ")");
                    h.assertTrue(TestsBucheron.compte(TestsBucheron.depot(b), Items.DIAMOND) == 1, "le diamant est au dépôt");
                },
                () -> TestsOrdres.fin(h, e));
    }
    /** Il a déjà nié aujourd'hui : même un peureux nie encore, sans nouveau tirage, et garde son diamant jusqu'au lendemain. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void nieJusquAuLendemain(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = TestsIA.equipe(h, chef, 234);
        Travailleur b = e[0];
        TestsIA.etapes(h, chef, e,
                () -> {
                    b.temperament = "peureux";
                    Humeur.cacher(b, new ItemStack(Items.DIAMOND));
                    b.memoire.putLong("diamant_nie", b.jour());
                    TestsIA.regarder(chef, b);
                    b.parler(chef, "Tu as gardé un diamant, Bertrand ?");
                },
                () -> {
                    String r = IA.echange(b, 1);
                    h.assertTrue(r.startsWith("Toi : ") && Vie.vient("diamant_nie", r.substring(6)), "il nie encore (" + IA.echange(b, 2) + ")");
                    h.assertTrue(diamantsCaches(b) == 1, "le diamant reste caché (" + b.memoire.get("butin") + ")");
                },
                () -> TestsOrdres.fin(h, e));
    }
}

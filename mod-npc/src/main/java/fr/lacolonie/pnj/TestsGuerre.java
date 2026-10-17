package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Lot 9, cahier 6.5 : la guerre entre colonies. Sans guerre, le guerrier ne touche pas l'ouvrier d'un autre chef (et refuse
 * l'assaut) ; la guerre déclarée, il le frappe près du poste, et un ouvrier le signale ; levée, l'armée marche au poste
 * ennemi, pille son dépôt et casse sa porte ; la paix faite, il baisse son arme. Voir {@link Guerre} et {@link Armee}.
 * Le chef ennemi n'est pas un joueur ici (les faux joueurs portent tous le même nom) : ses ouvriers portent son nom.
 * Coins 320 à 323.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsGuerre {
    /** Le chef ennemi (pas un joueur), son poste, et un de ses ouvriers (null si {@code ici} est null). */
    record Rival(UUID chef, String nom, BlockPos poste, Travailleur o) {
        void finir(TestsChef.Scene s, Travailleur... autres) {
            Courrier c = Courrier.de(s.chef().getServer());
            c.paix(s.chef().getUUID(), chef);
            c.oublierPoste(chef, s.chef().level(), poste);
            c.boites.remove(chef);
            if (o != null) TestsGuerrier.fin(o);
            TestsGuerrier.fin(s.g());
            TestsGuerrier.fin(autres);
            s.partir();
        }
    }

    /** Un rival nommé « Rival&lt;coin&gt; », poste en {@code poste} ; son mineur, qui attend, en (x, z) de la scène. */
    static Rival rival(GameTestHelper h, TestsChef.Scene s, int coin, BlockPos poste, Double x, double z) {
        UUID u = UUID.randomUUID();
        String nom = "Rival" + coin;
        ServerLevel l = h.getLevel();
        Courrier.de(l.getServer()).definirPoste(u, l, poste);
        Travailleur o = null;
        if (x != null) {
            o = Travailleur.auTravail(l, "mineur", poste, new Vec3(s.b().getX() + x, s.b().getY(), s.b().getZ() + z), u, ItemStack.EMPTY);
            o.chefNom = nom;
            o.mode = Travailleur.Mode.ATTENDRE;
            o.forcerChunk();
        }
        return new Rival(u, nom, poste, o);
    }

    /** Loin de la scène : l'ouvrier ennemi n'est pas chez lui près de notre poste. */
    static BlockPos loin(GameTestHelper h, int coin) { return TestsJeu.sol(h, coin * 400 + 200, 60000); }

    static boolean[] surveiller(GameTestHelper h, TestsChef.Scene s, Rival r) {
        boolean[] vu = {false};
        h.onEachTick(() -> { if (s.g().action instanceof Actions.Attaquer a && a.cible == r.o()) vu[0] = true; });
        return vu;
    }

    /** Sans guerre : il laisse l'ouvrier de l'autre chef, tout près du poste ; « attaquez la colonie de X » : il refuse. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void sansGuerreRien(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 320);
        Rival r = rival(h, s, 320, loin(h, 320), 4.5, 7.5);
        boolean[] vu = surveiller(h, s, r);
        String dit = s.g().appliquer(Ordres.lire("Attaquez la colonie de " + r.nom() + " !", "guerrier"), s.chef());
        h.assertTrue(Vie.vient("guerre_refus_hors_guerre", dit), "il refuse : " + dit);
        h.assertFalse(s.g().memoire.contains("guerre_assaut"), "pas d'assaut");
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertFalse(vu[0], "il ne l'attaque pas");
            h.assertTrue(r.o().getHealth() == r.o().getMaxHealth(), "l'autre ouvrier ne prend aucun coup");
            r.finir(s);
            h.succeed();
        });
    }

    /** La guerre déclarée : la lettre part au poste ennemi ; le guerrier frappe l'ouvrier ennemi près du poste, un mineur le signale. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void guerreDeclareeDefense(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 321);
        Rival r = rival(h, s, 321, loin(h, 321), 4.5, 7.5);
        Travailleur m = s.ouvrier("mineur", 1.5, 8.5, new ItemStack(Items.IRON_PICKAXE));
        m.mode = Travailleur.Mode.ATTENDRE;
        String dit = s.g().appliquer(Ordres.lire("On déclare la guerre à " + r.nom() + ".", "guerrier"), s.chef());
        Courrier c = Courrier.de(h.getLevel().getServer());
        h.assertTrue(Vie.vient("guerre_declaree", dit), "il le redit : " + dit);
        h.assertTrue(c.enGuerre(s.chef().getUUID(), r.chef()) && c.enGuerre(r.chef(), s.chef().getUUID()), "la guerre, des deux côtés");
        h.assertTrue(!c.boites.getOrDefault(r.chef(), java.util.List.of()).isEmpty(), "une lettre au poste ennemi");
        h.succeedWhen(() -> {
            h.assertTrue(r.o().getHealth() < r.o().getMaxHealth(), "il frappe l'ouvrier ennemi (" + s.g().paroles + ")");
            h.assertTrue(Essais.aDit(m, "guerre_ennemi_vu"), "le mineur le signale (" + m.paroles + ")");
            r.finir(s, m);
        });
    }

    /** Levée : le guerrier marche au poste ennemi, à pied, prend au dépôt (pas les pièces) et casse la porte, pas le bureau. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void leveePilleEtCasse(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 322);
        ServerLevel l = h.getLevel();
        BlockPos poste = TestsJeu.sol(h, 322 * 400 + 24, 60000 + 4);
        l.setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        Bureau.Entite b = (Bureau.Entite) l.getBlockEntity(poste);
        b.depot.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        BlockPos porte = poste.offset(0, 0, 3);
        l.setBlockAndUpdate(porte, Blocks.OAK_DOOR.defaultBlockState());
        l.setBlockAndUpdate(porte.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Rival r = rival(h, s, 322, poste, null, 0);
        s.g().debut = s.g().fin = 0; // en service jour et nuit
        // Le rival n'a pas d'ouvrier ici (on ne trouve pas son nom) : la guerre est posée directement dans le Courrier.
        Courrier.de(l.getServer()).declarer(s.chef().getUUID(), "chef", r.chef(), r.nom());
        String dit = s.g().appliquer(Ordres.lire("Attaquez la colonie de " + r.nom() + ", pillez leur dépôt et cassez leurs portes.", "guerrier"), s.chef());
        h.assertTrue(Vie.vient("guerre_levee", dit), "il rejoint les rangs : " + dit);
        h.assertTrue(s.g().memoire.contains("guerre_assaut"), "l'assaut est lancé");
        h.succeedWhen(() -> {
            h.assertTrue(b.depot.getItem(0).isEmpty(), "il pille le dépôt (" + s.g().paroles + ")");
            h.assertFalse(l.getBlockState(porte).getBlock() instanceof DoorBlock, "il casse la porte (" + s.g().paroles + ")");
            h.assertTrue(l.getBlockState(poste).is(ColoniePnj.BUREAU.get()), "le bureau reste");
            h.assertTrue(s.g().compter(Actions.Filtre.de("minecraft:iron_ingot")) == 5 && s.g().memoire.getBoolean("guerre_butin"), "il emporte le butin");
            l.removeBlock(poste, false);
            r.finir(s);
        });
    }

    /** La paix faite pendant qu'il frappe l'ouvrier ennemi : il baisse son arme et ne le touche plus. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void paixPlusDAttaque(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 323);
        Rival r = rival(h, s, 323, loin(h, 323), 4.5, 7.5);
        s.g().appliquer(Ordres.lire("On déclare la guerre à " + r.nom() + ".", "guerrier"), s.chef());
        long[] paix = {-1};
        boolean[] apres = {false};
        String[] dit = {""};
        h.onEachTick(() -> {
            boolean attaque = s.g().action instanceof Actions.Attaquer a && a.cible == r.o();
            if (attaque && paix[0] < 0) {
                paix[0] = h.getTick();
                dit[0] = s.g().appliquer(Ordres.lire("On fait la paix avec " + r.nom() + ".", "guerrier"), s.chef());
            }
            if (paix[0] >= 0 && h.getTick() == paix[0] + 20) r.o().setHealth(r.o().getMaxHealth());
            if (paix[0] >= 0 && h.getTick() > paix[0] + 20 && attaque) apres[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(paix[0] >= 0, "la guerre : il attaque d'abord");
            h.assertTrue(h.getTick() > paix[0] + 140, "on attend");
            h.assertTrue(Vie.vient("guerre_paix", dit[0]), "il le redit : " + dit[0]);
            h.assertFalse(apres[0], "après la paix, plus d'attaque");
            h.assertTrue(r.o().getHealth() == r.o().getMaxHealth(), "plus un coup");
            r.finir(s);
        });
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Plan E2 et E3 : trop blessé, un ouvrier lâche le travail, fuit le monstre qui l'a frappé et mange ({@link Fuite}) ; hors
 * combat et nourri, il guérit comme un joueur ; passé au dépôt, il prend la meilleure armure ({@link Metier#armureAuDepot}).
 * Chaque test dans son coin du monde plat (coins 957 à 960), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsFuite {
    /** Au bout de {@code ticks}, {@code verifier} ; puis le test réussit (ses assertions échouent le test sinon). */
    static void apres(GameTestHelper h, int ticks, Runnable verifier) {
        long debut = h.getTick();
        boolean[] fait = {false};
        h.onEachTick(() -> {
            if (fait[0] || h.getTick() - debut < ticks) return;
            verifier.run();
            fait[0] = true;
        });
        h.succeedWhen(() -> h.assertTrue(fait[0], "attendre " + ticks + " ticks"));
    }

    /** Un mineur au travail, tombé à 8 points sans être frappé : il lâche la mine, mange son pain et attend d'aller mieux. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void tropBlesseIlLacheLeTravailEtMange(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 957, b, new ItemStack(Items.IRON_PICKAXE));
        t.mission("charbon", "stock", 64);
        BlockPos filon = b[0].west(2).south(2);
        h.getLevel().setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        t.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        t.faim = 10;
        t.setHealth(8);
        apres(h, 300, () -> {
            h.assertTrue(h.getLevel().getBlockState(filon).is(Blocks.COAL_ORE), "il ne mine plus (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.faim > 10 && t.compter(Actions.Filtre.de("minecraft:bread")) < 3, "il a mangé (faim " + t.faim + ")");
            h.assertTrue(t.action instanceof Fuite.Repli, "il se repose à l'abri (" + (t.action == null ? "rien" : t.action.nom) + ")");
            h.assertTrue(t.isAlive() && !t.aTerre, "debout");
            t.discard();
        });
    }

    /** Frappé par un monstre, à 8 points : il s'en éloigne d'au moins 9 blocs et y reste, au lieu de retourner travailler à côté. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void fuitLeMonstreQuiLaFrappe(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 958, b, new ItemStack(Items.IRON_PICKAXE));
        t.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        Husk m = EntityType.HUSK.create(h.getLevel());
        BlockPos ou = b[0].west(4);
        m.moveTo(Vec3.atBottomCenterOf(ou));
        m.setNoAi(true);
        m.setPersistenceRequired();
        h.getLevel().addFreshEntity(m);
        t.setHealth(10);
        t.hurt(t.damageSources().mobAttack(m), 2);
        apres(h, 400, () -> {
            h.assertTrue(t.distanceTo(m) >= 9, "loin du monstre : " + String.format("%.1f", t.distanceTo(m)) + " blocs (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.action instanceof Fuite.Repli, "toujours à l'abri, pas au travail (" + (t.action == null ? "rien" : t.action.nom) + ")");
            h.assertTrue(t.isAlive() && !t.aTerre, "debout");
            t.discard();
            m.discard();
        });
    }

    /** Hors combat et nourri, à 12 points : en 25 s il en regagne au moins 5 (avant : un toutes les 10 s). */
    @GameTest(template = "vide", timeoutTicks = 700)
    public static void gueritCommeUnJoueur(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 959, b, new ItemStack(Items.IRON_PICKAXE));
        t.faim = 20;
        t.setHealth(12);
        apres(h, 500, () -> {
            h.assertTrue(t.getHealth() >= 17, "vie " + t.getHealth() + " / " + t.getMaxHealth() + ", faim " + t.faim);
            t.discard();
        });
    }

    /** Au poste, un casque de cuir sur la tête : il prend le casque et le plastron de fer du dépôt, et y laisse son casque de cuir. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void prendLaMeilleureArmureAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = TestsOutils.ouvrier(h, "mineur", 960, b, new ItemStack(Items.IRON_PICKAXE));
        t.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.IRON_HELMET));
        d.depot.addItem(new ItemStack(Items.IRON_CHESTPLATE));
        d.depot.addItem(new ItemStack(Items.LEATHER_BOOTS));
        h.succeedWhen(() -> {
            h.assertTrue(t.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "casque de fer (" + t.getItemBySlot(EquipmentSlot.HEAD) + ")");
            h.assertTrue(t.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "plastron de fer");
            h.assertTrue(t.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS), "bottes de cuir");
            h.assertTrue(TestsBucheron.compte(d, Items.LEATHER_HELMET) == 1 && TestsBucheron.compte(d, Items.IRON_HELMET) == 0
                    && TestsBucheron.compte(d, Items.IRON_CHESTPLATE) == 0 && TestsBucheron.compte(d, Items.LEATHER_BOOTS) == 0,
                    "le casque de cuir rendu au dépôt, rien de perdu");
            t.discard();
        });
    }
}

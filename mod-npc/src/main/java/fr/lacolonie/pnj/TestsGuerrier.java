package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Le guerrier du cahier 3.9 : il s'équipe au dépôt, se soigne, tire à l'arc sans blesser personne sur la trajectoire,
 * défend un ouvrier de la colonie, chasse la nuit, fait la ronde sur son parcours, tient un pont, s'entraîne sur un
 * mannequin ; et un ouvrier s'éloigne d'un creeper (cahier 14.11). Chaque test dans son coin du monde plat (coins 90 à 99),
 * au ciel ouvert. Les monstres sont figés (sans IA) : ce qui est testé, c'est le guerrier.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsGuerrier {
    /** Un guerrier au travail dans son coin, à 2 blocs à l'ouest de son poste ; le poste en base[0]. */
    static Travailleur guerrier(GameTestHelper h, int coin, BlockPos[] base, ItemStack arme) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "guerrier", base[0], Vec3.atBottomCenterOf(base[0].west(2)), arme);
        t.forcerChunk();
        return t;
    }

    /** Un monstre figé en p. */
    static <T extends Mob> T fige(GameTestHelper h, EntityType<T> type, BlockPos p) {
        T m = type.create(h.getLevel());
        m.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
        m.setNoAi(true);
        m.setPersistenceRequired();
        h.getLevel().addFreshEntity(m);
        return m;
    }

    static void fin(Travailleur... l) { for (Travailleur t : l) t.discard(); }

    /** Au poste, il prend au dépôt une meilleure épée, un meilleur plastron, un arc et des flèches ; ce qu'il remplace y reste. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void sEquipeAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 90, b, new ItemStack(Items.STONE_SWORD));
        t.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.addItem(new ItemStack(Items.DIAMOND_SWORD));
        d.depot.addItem(new ItemStack(Items.IRON_CHESTPLATE));
        d.depot.addItem(new ItemStack(Items.BOW));
        d.depot.addItem(new ItemStack(Items.ARROW, 20));
        h.succeedWhen(() -> {
            h.assertTrue(t.getMainHandItem().is(Items.DIAMOND_SWORD), "l'épée de diamant en main (" + t.getMainHandItem() + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "le plastron de fer (" + t.getItemBySlot(EquipmentSlot.CHEST) + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:bow")) == 1 && t.compter(Actions.Filtre.de("minecraft:arrow")) == 20, "l'arc et les 20 flèches");
            h.assertTrue(TestsBucheron.compte(d, Items.STONE_SWORD) == 1 && TestsBucheron.compte(d, Items.LEATHER_CHESTPLATE) == 1, "l'épée de pierre et le plastron de cuir au dépôt");
            h.assertTrue(TestsBucheron.compte(d, Items.DIAMOND_SWORD) + TestsBucheron.compte(d, Items.IRON_CHESTPLATE) + TestsBucheron.compte(d, Items.BOW) == 0, "rien en double");
            fin(t);
        });
    }

    /** Blessé, sans ennemi en vue, il boit sa potion de soin ; la fiole vide lui reste. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void boitUnePotionBlesse(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 91, b, new ItemStack(Items.IRON_SWORD));
        t.inventaire.addItem(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
        t.setHealth(6);
        h.succeedWhen(() -> {
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:potion")) == 0, "la potion doit être bue (vie " + t.getHealth() + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:glass_bottle")) == 1, "la fiole vide lui reste");
            h.assertTrue(t.getHealth() >= 10, "elle le soigne (vie " + t.getHealth() + ")");
            fin(t);
        });
    }

    /**
     * Un monstre hors d'atteinte, sur un pilier : il le tire à l'arc, avec ses propres flèches. Un villageois est sur la
     * trajectoire : il ne tire pas à travers lui (il se place pour avoir le champ libre).
     */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void tireALArcSansBlesserPersonne(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 92, b, new ItemStack(Items.BOW));
        t.inventaire.addItem(new ItemStack(Items.ARROW, 32));
        ServerLevel l = h.getLevel();
        BlockPos pilier = b[0].east(8);
        l.setBlockAndUpdate(pilier, Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(pilier.above(), Blocks.STONE.defaultBlockState());
        var husk = fige(h, EntityType.HUSK, pilier.above(2));
        l.setBlockAndUpdate(b[0].east(3), Blocks.STONE.defaultBlockState());
        var villageois = fige(h, EntityType.VILLAGER, b[0].east(3).above());
        h.succeedWhen(() -> {
            h.assertTrue(villageois.getHealth() == villageois.getMaxHealth(), "le villageois ne doit pas être touché");
            h.assertFalse(husk.isAlive(), "le monstre doit tomber sous ses flèches (vie " + husk.getHealth() + ", flèches " + t.compter(Actions.Filtre.de("minecraft:arrow")) + ", " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:arrow")) < 32, "de vraies flèches tirées");
            villageois.discard();
            fin(t);
        });
    }

    /** Un monstre rôde près d'un mineur de la colonie, loin de la garde : le guerrier y va, le tue, puis revient au poste. */
    @GameTest(template = "vide", timeoutTicks = 1000)
    public static void defendUnOuvrier(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 93, b, new ItemStack(Items.IRON_SWORD));
        Travailleur m = TestsJeu.employe(h, "mineur", b[0], Vec3.atBottomCenterOf(b[0].east(24)), new ItemStack(Items.IRON_PICKAXE));
        m.chef = t.chef;
        TestsBucheron.depot(t).proprietaire = t.chef;
        m.mode = Travailleur.Mode.ATTENDRE;
        var husk = fige(h, EntityType.HUSK, b[0].east(26));
        boolean[] tue = {false};
        h.succeedWhen(() -> {
            if (!tue[0]) {
                h.assertFalse(husk.isAlive(), "le monstre près du mineur doit être tué (" + TestsJeu.etat(t, t.bureau) + ")");
                tue[0] = true;
            }
            h.assertTrue(TestsJeu.distance(t, t.bureau) <= 12, "il revient garder le poste (" + TestsJeu.etat(t, t.bureau) + ")");
            fin(t, m);
        });
    }

    static long heureAvant;

    @BeforeBatch(batch = "guerrier_nuit")
    public static void avantNuit(ServerLevel l) { heureAvant = l.getDayTime(); l.setDayTime(18000); }

    @AfterBatch(batch = "guerrier_nuit")
    public static void apresNuit(ServerLevel l) { l.setDayTime(heureAvant); }

    /** La nuit, il chasse le monstre qui rôde dans l'espace de vie, loin de sa garde. */
    @GameTest(template = "vide", batch = "guerrier_nuit", timeoutTicks = 1200)
    public static void chasseDeNuit(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 94, b, new ItemStack(Items.IRON_SWORD));
        var husk = fige(h, EntityType.HUSK, b[0].north(30));
        h.succeedWhen(() -> {
            h.assertFalse(husk.isAlive(), "le rôdeur doit être tué (" + TestsJeu.etat(t, t.bureau) + ", jour " + h.getLevel().isDay() + ")");
            fin(t);
        });
    }

    /** La ronde suit le parcours que le chef lui a montré, point par point (et non plus le cercle autour du poste). */
    @GameTest(template = "vide", timeoutTicks = 900)
    public static void rondeSurSonParcours(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 95, b, new ItemStack(Items.IRON_SWORD));
        t.mission("ronde", "stock", 0);
        BlockPos p1 = b[0].offset(6, 0, -8), p2 = b[0].offset(-8, 0, -6);
        t.memoire.putLongArray("ronde_points", new long[]{p1.asLong(), p2.asLong()});
        t.memoire.putInt("poste", 1);
        boolean[] vu = {false, false};
        h.succeedWhen(() -> {
            if (t.position().distanceToSqr(Vec3.atBottomCenterOf(p1)) < 2.5 * 2.5) vu[0] = true;
            if (vu[0] && t.position().distanceToSqr(Vec3.atBottomCenterOf(p2)) < 2.5 * 2.5) vu[1] = true;
            h.assertTrue(vu[0] && vu[1], "il passe par le point 1 puis le point 2 (" + vu[0] + ", " + vu[1] + ", " + TestsJeu.etat(t, t.bureau) + ")");
            fin(t);
        });
    }

    /**
     * « Tiens ce pont » : un monstre à 9 blocs ne le fait pas bouger (sans arc, il le guette) ; à 3 blocs, il le frappe,
     * puis revient à sa place.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void tientLePont(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 96, b, new ItemStack(Items.IRON_SWORD));
        BlockPos pont = b[0].south(6);
        t.consigne = new Consignes.Consigne("garder_ici", "", "", 0, 0, 0, -1, pont, null);
        t.finirAction("ordre");
        var husk = fige(h, EntityType.HUSK, pont.east(9));
        long[] debut = {-1};
        h.succeedWhen(() -> {
            if (debut[0] < 0) debut[0] = h.getTick();
            long age = h.getTick() - debut[0];
            if (age < 160) {
                // D'abord, il rejoint sa place et y reste malgré le monstre à 9 blocs.
                if (age > 80) h.assertTrue(t.position().distanceToSqr(Vec3.atBottomCenterOf(pont)) < 2.5 * 2.5, "il tient sa place (" + TestsJeu.etat(t, pont) + ")");
                h.assertTrue(husk.isAlive(), "le monstre à 9 blocs n'est pas poursuivi");
                throw new net.minecraft.gametest.framework.GameTestAssertException("on attend");
            }
            if (husk.isAlive() && husk.distanceToSqr(Vec3.atBottomCenterOf(pont)) > 16) husk.moveTo(pont.getX() + 3.5, pont.getY(), pont.getZ() + 0.5);
            h.assertFalse(husk.isAlive(), "le monstre tout proche est frappé (" + TestsJeu.etat(t, pont) + ")");
            h.assertTrue(t.position().distanceToSqr(Vec3.atBottomCenterOf(pont)) < 2.5 * 2.5, "il revient à sa place (" + TestsJeu.etat(t, pont) + ")");
            fin(t);
        });
    }

    /** À la garde, sans menace, il s'entraîne sur le mannequin près du poste, sans l'abîmer ni lui prendre son casque. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void mannequin(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = guerrier(h, 97, b, new ItemStack(Items.IRON_SWORD));
        ArmorStand m = EntityType.ARMOR_STAND.create(h.getLevel());
        BlockPos p = b[0].south(3);
        m.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
        m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        h.getLevel().addFreshEntity(m);
        h.succeedWhen(() -> {
            h.assertTrue(t.memoire.getInt("coups_mannequin") >= 4, "il frappe le mannequin (" + t.memoire.getInt("coups_mannequin") + " coups, " + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(m.isAlive() && m.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "le mannequin reste entier, casque compris");
            m.discard();
            fin(t);
        });
    }

    /** Cahier 14.11 : un creeper tout près d'un mineur ; il s'en éloigne et appelle la garde. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void ouvrierSEloigneDuCreeper(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 98 * 400, 60000);
        Travailleur m = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        m.forcerChunk();
        m.mode = Travailleur.Mode.ATTENDRE;
        var creeper = fige(h, EntityType.CREEPER, base.west(5));
        h.succeedWhen(() -> {
            h.assertTrue(m.distanceTo(creeper) > 6, "il s'éloigne du creeper (" + (int) m.distanceTo(creeper) + " blocs, " + TestsJeu.etat(m, base) + ")");
            h.assertTrue(m.memoire.contains("alerte"), "il appelle la garde");
            creeper.discard();
            fin(m);
        });
    }
}

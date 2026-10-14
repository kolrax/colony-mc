package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * L'éleveur (lot 6) : il nourrit deux vaches qui font un veau, tond un mouton (la laine au dépôt), abat l'excédent sans
 * toucher au dernier couple, au veau ni à la vache nommée, sur ordre aussi, ne touche ni aux bêtes au pré ni à celles d'un
 * voisin, et referme toujours le portillon derrière lui. Chaque test dans son coin du monde plat (coins 140 à 145).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsEleveur {
    static int plafond;

    /** Le lot « eleveur_plafond » : au plus 2 bêtes de chaque espèce par enclos, pour voir l'excédent abattu sans en mettre 13. */
    @BeforeBatch(batch = "eleveur_plafond")
    public static void avant(ServerLevel l) {
        plafond = Reglages.actuel.betes_par_enclos;
        Reglages.actuel.betes_par_enclos = 2;
    }

    @AfterBatch(batch = "eleveur_plafond")
    public static void apres(ServerLevel l) { Reglages.actuel.betes_par_enclos = plafond; }

    /** Un éleveur seul, au travail, dans son coin du monde plat ; le sol du poste dans base[0]. */
    static Travailleur seul(GameTestHelper h, int coin, BlockPos[] base) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "eleveur", base[0], Vec3.atBottomCenterOf(base[0].west(2)), ItemStack.EMPTY);
        t.forcerChunk();
        return t;
    }

    /** Un éleveur, Mathurin, près de son chef ; le sol du poste dans base[0]. */
    static Travailleur[] aupres(GameTestHelper h, ServerPlayer chef, int coin, BlockPos[] base) {
        base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Vec3 c = Vec3.atBottomCenterOf(base[0]);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur f = TestsJeu.employe(h, "eleveur", base[0], c.add(-0.5, 0, 2.5), ItemStack.EMPTY);
        f.chef = chef.getUUID();
        f.prenom = "Mathurin";
        f.forcerChunk();
        return new Travailleur[]{f};
    }

    /**
     * Un enclos de barrières autour de {@code centre} (5 sur 5 dedans), le portillon au milieu du côté ouest (vers le poste) ;
     * renvoie le portillon.
     */
    static BlockPos enclos(ServerLevel l, BlockPos centre) {
        BlockPos porte = centre.offset(-3, 0, 0);
        for (int pass = 0; pass < 2; pass++)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
                    BlockPos p = centre.offset(dx, 0, dz);
                    if (p.equals(porte)) continue;
                    l.setBlockAndUpdate(p, Block.updateFromNeighbourShapes(Blocks.OAK_FENCE.defaultBlockState(), l, p));
                }
        l.setBlockAndUpdate(porte, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
        return porte;
    }

    static <T extends Animal> T bete(ServerLevel l, EntityType<T> type, BlockPos p, boolean petit, String nom) {
        T a = type.spawn(l, p, MobSpawnType.MOB_SUMMONED);
        if (petit) a.setBaby(true);
        if (nom != null) a.setCustomName(Component.literal(nom));
        return a;
    }

    static List<Cow> vaches(ServerLevel l, BlockPos centre) { return l.getEntitiesOfClass(Cow.class, new AABB(centre).inflate(6, 3, 6), Cow::isAlive); }

    static boolean ferme(ServerLevel l, BlockPos porte) { return l.getBlockState(porte).is(Blocks.OAK_FENCE_GATE) && !l.getBlockState(porte).getValue(FenceGateBlock.OPEN); }

    /** Il est sorti de l'enclos (à l'ouest du portillon). */
    static boolean dehors(Travailleur t, BlockPos porte) { return t.getX() < porte.getX(); }

    /**
     * Toutes les bêtes sont restées dans l'enclos (rien ne s'est échappé par le portillon). Le milieu de la bête à moins de 3
     * blocs du milieu de l'enclos : un petit serré dans un coin a le milieu au-dessus du poteau (à 2,65), mais il est dedans ;
     * dehors, la barrière le tient à plus de 3,3.
     */
    static boolean dedans(List<? extends Animal> betes, BlockPos centre) { return betes.stream().allMatch(a -> dedans(a, centre)); }

    static boolean dedans(Animal a, BlockPos centre) { return Math.abs(a.getX() - centre.getX() - 0.5) < 3 && Math.abs(a.getZ() - centre.getZ() - 0.5) < 3; }

    /**
     * Pour comprendre un échec : ce qu'il a fait, tick par tick (chaque changement d'action, de sous-action ou d'étape de
     * corvée, avec le résultat), et où étaient les bêtes de l'enclos à ce moment-là.
     */
    static List<String> suivi(GameTestHelper h, Travailleur t, BlockPos centre) {
        List<String> l = new java.util.ArrayList<>();
        String[] avant = {""};
        h.onEachTick(() -> {
            String s = decrire(t.action);
            List<Cow> sorties = vaches(h.getLevel(), centre).stream().filter(c -> !dedans(c, centre)).toList();
            if (!(s + sorties.size()).equals(avant[0]) && l.size() < 80) {
                String ou = sorties.stream().map(c -> String.format(java.util.Locale.ROOT, "(%.2f %.2f %.2f%s)", c.getX() - centre.getX() - 0.5, c.getY() - centre.getY(), c.getZ() - centre.getZ() - 0.5, c.isBaby() ? " petit" : "")).reduce("", String::concat);
                l.add(h.getTick() + ":" + s + (t.resultat.isEmpty() ? "" : "=" + t.resultat) + (sorties.isEmpty() ? "" : " [dehors " + ou + "]")
                        + String.format(java.util.Locale.ROOT, " @%.1f,%.1f", t.getX() - centre.getX() - 0.5, t.getZ() - centre.getZ() - 0.5));
                avant[0] = s + sorties.size();
            }
        });
        return l;
    }

    static String decrire(Actions.Action a) {
        if (a == null) return "-";
        if (a instanceof Consignes.Faire f) return a.nom + "/" + decrire(f.sous) + (f.raisonSous.isEmpty() ? "" : "!" + f.raisonSous);
        if (a instanceof Eleveur.Tournee r) return a.nom + "/" + decrire(r.sous);
        if (a instanceof Eleveur.Corvee c) return a.nom + "#" + c.etape + "(" + c.taches.size() + ")" + (c.sous == null ? "" : "/" + c.sous.nom + (c.sous instanceof Eleveur.Passer p ? p.pas : ""));
        return a.nom + (a.raison.isEmpty() ? "" : "!" + a.raison);
    }

    static int parTerre(ServerLevel l, BlockPos centre) { return l.getEntitiesOfClass(ItemEntity.class, new AABB(centre).inflate(8)).size(); }

    /** Deux vaches et 4 blés au dépôt : il en prend 2, nourrit les deux vaches, un veau naît ; le portillon est refermé, rien ne s'échappe. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void nourritEtFaitNaitreUnVeau(GameTestHelper h) { nourrit(h, 140); }

    static void nourrit(GameTestHelper h, int coin) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, coin, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(8, 0, 0);
        BlockPos porte = enclos(l, centre);
        bete(l, EntityType.COW, centre.offset(1, 0, 1), false, null);
        bete(l, EntityType.COW, centre.offset(2, 0, -1), false, null);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.WHEAT, 4));
        List<String> suivi = suivi(h, t, centre);
        h.succeedWhen(() -> {
            List<Cow> v = vaches(l, centre);
            long veaux = v.stream().filter(Cow::isBaby).count();
            int ble = t.compter(Actions.Filtre.de("minecraft:wheat")) + TestsBucheron.compte(d, Items.WHEAT);
            h.assertTrue(veaux == 1 && v.size() == 3, "un veau doit naître : " + veaux + " veau(x), " + v.size() + " vaches (" + TestsJeu.etat(t, t.bureau) + ") " + suivi);
            h.assertTrue(ble == 2, "2 blés donnés, 2 restent : " + ble + " " + suivi);
            h.assertTrue(dedans(v, centre), "les vaches restent dans l'enclos " + suivi);
            h.assertTrue(ferme(l, porte) && dehors(t, porte), "il ressort et referme le portillon : " + l.getBlockState(porte) + ", x " + (t.getX() - porte.getX()));
            t.discard();
        });
    }

    /** Un mouton et des cisailles au dépôt : il prend les cisailles, tond le mouton, la laine va au dépôt ; portillon refermé. */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void tondUnMouton(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 141, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(8, 0, 0);
        BlockPos porte = enclos(l, centre);
        Sheep s = bete(l, EntityType.SHEEP, centre.offset(1, 0, 0), false, null);
        s.setColor(DyeColor.WHITE);
        Bureau.Entite d = TestsBucheron.depot(t);
        d.depot.setItem(0, new ItemStack(Items.SHEARS));
        h.succeedWhen(() -> {
            h.assertTrue(s.isSheared(), "le mouton doit être tondu (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(d, Items.WHITE_WOOL) >= 1, "la laine au dépôt : " + TestsBucheron.compte(d, Items.WHITE_WOOL));
            h.assertTrue(t.compter(Eleveur.CISAILLES) == 1 && TestsBucheron.compte(d, Items.SHEARS) == 0, "il garde les cisailles prises au dépôt");
            h.assertTrue(dedans(List.of(s), centre), "le mouton reste dans l'enclos");
            h.assertTrue(ferme(l, porte) && dehors(t, porte), "il ressort et referme le portillon : " + l.getBlockState(porte) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(parTerre(l, centre) == 0, "rien par terre : " + parTerre(l, centre));
            t.discard();
        });
    }

    /**
     * Au plus 2 vaches par enclos : 4 vaches adultes (Marguerite a un nom) et un veau. Il en abat 2, jamais Marguerite ni le
     * veau, et il reste un couple ; la viande va au dépôt (rien par terre), le portillon est refermé.
     */
    @GameTest(template = "vide", timeoutTicks = 2400, batch = "eleveur_plafond")
    public static void abatLExcedent(GameTestHelper h) { excedent(h, 142); }

    static void excedent(GameTestHelper h, int coin) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, coin, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(8, 0, 0);
        BlockPos porte = enclos(l, centre);
        Cow marguerite = bete(l, EntityType.COW, centre.offset(2, 0, 2), false, "Marguerite");
        Cow veau = bete(l, EntityType.COW, centre.offset(2, 0, -2), true, null);
        for (int i = 0; i < 3; i++) bete(l, EntityType.COW, centre.offset(1, 0, i - 1), false, null);
        Bureau.Entite d = TestsBucheron.depot(t);
        List<String> suivi = suivi(h, t, centre);
        h.succeedWhen(() -> {
            List<Cow> v = vaches(l, centre);
            long adultes = v.stream().filter(c -> !c.isBaby()).count();
            h.assertTrue(dedans(v, centre), "les vaches restent dans l'enclos " + suivi);
            h.assertTrue(adultes <= 2, "l'excédent doit être abattu : " + adultes + " adultes (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(adultes == 2 && marguerite.isAlive() && veau.isAlive() && veau.isBaby(), "le dernier couple, Marguerite et le veau restent");
            h.assertTrue(TestsBucheron.compte(d, Items.BEEF) >= 2, "la viande au dépôt : " + TestsBucheron.compte(d, Items.BEEF));
            h.assertTrue(parTerre(l, centre) == 0, "rien par terre : " + parTerre(l, centre));
            h.assertTrue(ferme(l, porte) && dehors(t, porte), "il ressort et referme le portillon : " + l.getBlockState(porte) + " (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** Au plus 2 vaches par enclos, mais 4 vaches au pré, sans clôture : ce ne sont pas des bêtes d'enclos, il n'y touche pas. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "eleveur_plafond")
    public static void laisseLesBetesDuPre(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = seul(h, 144, b);
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(8, 0, 0);
        List<Cow> pre = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) pre.add(bete(l, EntityType.COW, centre.offset(i - 2, 0, 1), false, null));
        Bureau.Entite d = TestsBucheron.depot(t);
        h.succeedWhen(() -> {
            h.assertTrue(t.tickCount > 600, "il a eu le temps de faire sa tournée");
            h.assertTrue(pre.stream().allMatch(Cow::isAlive), "les vaches au pré sont toutes vivantes (" + TestsJeu.etat(t, t.bureau) + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:beef")) + TestsBucheron.compte(d, Items.BEEF) == 0, "pas de viande");
            t.discard();
        });
    }

    /**
     * « Abats deux vaches » : 3 vaches adultes (Marguerite a un nom) et un veau. Il n'en abat qu'une : il garde le dernier
     * couple (Marguerite comprise) et le veau ; il referme le portillon.
     */
    @GameTest(template = "vide", timeoutTicks = 2400)
    public static void abatSurOrdreSansToucherAuDernierCouple(GameTestHelper h) { abatSurOrdre(h, 143); }

    static void abatSurOrdre(GameTestHelper h, int coin) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos[] b = new BlockPos[1];
        Travailleur[] e = aupres(h, chef, coin, b);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        BlockPos centre = b[0].offset(8, 0, 0);
        BlockPos porte = enclos(l, centre);
        Cow marguerite = bete(l, EntityType.COW, centre.offset(2, 0, 2), false, "Marguerite");
        Cow veau = bete(l, EntityType.COW, centre.offset(2, 0, -2), true, null);
        for (int i = 0; i < 2; i++) bete(l, EntityType.COW, centre.offset(1, 0, i), false, null);
        List<String> suivi = suivi(h, g, centre);
        TestsIA.etapes(h, chef, e,
                () -> g.parler(chef, "Abats deux vaches"),
                () -> {
                    List<Cow> v = vaches(l, centre);
                    long adultes = v.stream().filter(c -> !c.isBaby()).count();
                    h.assertTrue(adultes == 2 && g.consigne == null, "une vache abattue, pas plus : " + adultes + " adultes (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ") " + suivi);
                    h.assertTrue(marguerite.isAlive() && veau.isAlive(), "Marguerite et le veau restent");
                    h.assertTrue(ferme(l, porte) && dehors(g, porte), "il ressort et referme le portillon : " + l.getBlockState(porte) + " (" + TestsJeu.etat(g, g.bureau) + ")");
                },
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Lot « voisins » : un enclos à 12 blocs de son poste, mais à 8 de celui d'un autre chef : il n'est pas à lui. « Abats deux
     * vaches » : il n'y touche pas, et le dit.
     */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "voisins")
    public static void laisseLesBetesDuVoisin(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos[] b = new BlockPos[1];
        Travailleur[] e = aupres(h, chef, 145, b);
        Travailleur g = e[0];
        ServerLevel l = h.getLevel();
        UUID voisin = UUID.randomUUID();
        Courrier c = Courrier.de(l.getServer());
        c.definirPoste(voisin, l, b[0].offset(20, 0, 0));
        TestsMineur.A_DEFAIRE.add(() -> c.postes.remove(voisin));
        BlockPos centre = b[0].offset(12, 0, 0);
        enclos(l, centre);
        for (int i = 0; i < 4; i++) bete(l, EntityType.COW, centre.offset(1, 0, i - 2), false, null);
        TestsIA.etapes(h, chef, e,
                () -> g.parler(chef, "Abats deux vaches"),
                () -> h.assertTrue(g.consigne == null, "la consigne est finie (" + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> h.assertTrue(vaches(l, centre).size() == 4, "les vaches du voisin sont toutes là : " + vaches(l, centre).size()),
                () -> TestsRepertoire.fin(e));
    }

}

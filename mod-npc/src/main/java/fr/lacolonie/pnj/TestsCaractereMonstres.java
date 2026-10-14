package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Le caractère d'un ouvrier devant un monstre (Bravoure, consigne du chef de projet) : le colérique frappe, mais pas un
 * creeper ; le peureux fuit de loin ; le fourbe s'éclipse ou envoie un collègue ; le calme frappé se défend, et défend son
 * collègue. Chaque test dans son coin du monde plat (z = 118000) ; les monstres sont figés (sans IA) et ne brûlent pas
 * (husks).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCaractereMonstres {
    static BlockPos base(GameTestHelper h, int coin) { return TestsJeu.sol(h, coin * 400, 118000); }

    /** Un mineur de ce caractère, à 3 blocs à l'est de son poste, qui attend sur place. */
    static Travailleur mineur(GameTestHelper h, BlockPos b, String caractere) {
        Travailleur t = TestsJeu.employe(h, "mineur", b, Vec3.atBottomCenterOf(b.east(3)), new ItemStack(Items.IRON_PICKAXE));
        t.temperament = caractere;
        t.mode = Travailleur.Mode.ATTENDRE;
        t.forcerChunk();
        return t;
    }

    /** La réplique de defense.txt, ou sa phrase de secours. */
    static boolean dit(Travailleur t, String cle, String nom) { return TestsCaractere.aDit(t, cle) || t.paroles.contains(Defense.secours(cle, nom)); }

    static boolean dit(Travailleur t, String cle) { return dit(t, cle, null); }

    /** Un colérique voit un monstre à 5 blocs : il lâche tout, va le frapper avec sa pioche, le tue et le dit. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void colereFrappeLeMonstre(GameTestHelper h) {
        BlockPos b = base(h, 0);
        Travailleur t = mineur(h, b, "colerique");
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(8));
        boolean[] frappe = {false};
        h.onEachTick(() -> { if (t.action instanceof Actions.Attaquer a && a.cible == husk) frappe[0] = true; });
        h.succeedWhen(() -> {
            h.assertTrue(frappe[0], "il va frapper le monstre (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(dit(t, "monstre_charge"), "il le dit en chargeant (" + t.paroles + ")");
            h.assertFalse(husk.isAlive(), "il le tue (vie " + husk.getHealth() + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(dit(t, "monstre_abattu"), "et le dit (" + t.paroles + ")");
            TestsGuerrier.fin(t);
        });
    }

    /** Un colérique devant un creeper : il ne le charge pas, il recule et le dit à sa façon. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void colereNeChargePasLeCreeper(GameTestHelper h) {
        BlockPos b = base(h, 1);
        Travailleur t = mineur(h, b, "colerique");
        var creeper = TestsGuerrier.fige(h, EntityType.CREEPER, b.east(8));
        String[] faute = {null};
        double[] loin = {0};
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Attaquer && faute[0] == null) faute[0] = "il charge le creeper (" + TestsJeu.etat(t, b) + ")";
            loin[0] = Math.max(loin[0], t.distanceTo(creeper));
        });
        h.succeedWhen(() -> {
            h.assertTrue(faute[0] == null, faute[0]);
            h.assertTrue(dit(t, "monstre_creeper_recule"), "il recule en le disant (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(loin[0] > 9, "il s'éloigne du creeper (" + (int) loin[0] + " blocs au plus)");
            creeper.discard();
            TestsGuerrier.fin(t);
        });
    }

    /** Un peureux voit un monstre à 10 blocs (plus loin que la vue des autres) : il s'enfuit en criant, sans le frapper. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void peureuxFuitDeLoin(GameTestHelper h) {
        BlockPos b = base(h, 2);
        Travailleur t = mineur(h, b, "peureux");
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(13));
        String[] faute = {null};
        double[] loin = {0};
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Attaquer && faute[0] == null) faute[0] = "il le frappe (" + TestsJeu.etat(t, b) + ")";
            loin[0] = Math.max(loin[0], t.distanceTo(husk));
        });
        h.succeedWhen(() -> {
            h.assertTrue(faute[0] == null, faute[0]);
            h.assertTrue(dit(t, "monstre_panique"), "il crie (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(loin[0] > 16, "il s'enfuit (" + (int) loin[0] + " blocs au plus du monstre, " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(husk.getHealth() == husk.getMaxHealth(), "le monstre n'est pas touché");
            husk.discard();
            TestsGuerrier.fin(t);
        });
    }

    /** Un fourbe seul voit un monstre à 5 blocs : il s'éloigne sans le frapper et sans prévenir la garde. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void fourbeSEclipse(GameTestHelper h) {
        BlockPos b = base(h, 3);
        Travailleur t = mineur(h, b, "fourbe");
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(8));
        String[] faute = {null};
        double[] loin = {0};
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Attaquer && faute[0] == null) faute[0] = "il le frappe (" + TestsJeu.etat(t, b) + ")";
            loin[0] = Math.max(loin[0], t.distanceTo(husk));
        });
        h.succeedWhen(() -> {
            h.assertTrue(faute[0] == null, faute[0]);
            h.assertTrue(dit(t, "monstre_eclipse"), "il marmonne en s'éclipsant (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(loin[0] > 12, "il s'éloigne (" + (int) loin[0] + " blocs au plus du monstre, " + TestsJeu.etat(t, b) + ")");
            h.assertFalse(t.memoire.contains("alerte"), "sans prévenir la garde");
            husk.discard();
            TestsGuerrier.fin(t);
        });
    }

    /** Un fourbe avec un collègue de son chef à côté : il l'envoie au monstre, par son prénom, et s'en va. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void fourbeEnvoieUnCollegue(GameTestHelper h) {
        BlockPos b = base(h, 4);
        Travailleur t = mineur(h, b, "fourbe");
        Travailleur c = TestsJeu.employe(h, "bucheron", b, Vec3.atBottomCenterOf(b.east(3).south(3)), ItemStack.EMPTY);
        c.chef = t.chef;
        c.prenom = "Odon";
        c.temperament = "calme";
        c.mode = Travailleur.Mode.ATTENDRE;
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(8));
        double[] loin = {0};
        h.onEachTick(() -> loin[0] = Math.max(loin[0], t.distanceTo(husk)));
        h.succeedWhen(() -> {
            h.assertTrue(dit(t, "monstre_pousse", "Odon") && t.paroles.stream().anyMatch(p -> p.contains("Odon")), "il y envoie Odon (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(loin[0] > 12, "puis il s'en va (" + (int) loin[0] + " blocs au plus du monstre)");
            husk.discard();
            TestsGuerrier.fin(t, c);
        });
    }

    /** Un calme frappé par un monstre se défend au lieu de fuir ; son collègue, à 3 blocs, vient le défendre aussi. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void calmeFrappeSeDefend(GameTestHelper h) {
        BlockPos b = base(h, 5);
        Travailleur t = mineur(h, b, "calme");
        Travailleur c = TestsJeu.employe(h, "bucheron", b, Vec3.atBottomCenterOf(b.east(3).south(3)), new ItemStack(Items.IRON_AXE));
        c.chef = t.chef;
        c.temperament = "calme";
        c.mode = Travailleur.Mode.ATTENDRE;
        var husk = TestsGuerrier.fige(h, EntityType.HUSK, b.east(6));
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        husk.setHealth(200); // qu'il dure assez pour que les deux s'y mettent
        boolean[] lui = {false}, collegue = {false}, fuite = {false};
        h.onEachTick(() -> {
            if (t.action instanceof Actions.Attaquer a && a.cible == husk) lui[0] = true;
            if (c.action instanceof Actions.Attaquer a && a.cible == husk) collegue[0] = true;
            if (t.action instanceof Actions.Fuir) fuite[0] = true;
        });
        h.runAtTickTime(h.getTick() + 10, () -> t.hurt(t.damageSources().mobAttack(husk), 1));
        h.succeedWhen(() -> {
            h.assertFalse(fuite[0], "il ne fuit pas (" + TestsJeu.etat(t, b) + ")");
            h.assertTrue(lui[0], "frappé, il se défend (" + t.paroles + ", " + TestsJeu.etat(t, b) + ")");
            h.assertTrue(dit(t, "monstre_riposte"), "en le disant (" + t.paroles + ")");
            h.assertTrue(collegue[0], "son collègue vient le défendre (" + c.paroles + ", " + TestsJeu.etat(c, b) + ")");
            h.assertTrue(husk.getHealth() < 200, "le monstre est blessé");
            husk.discard();
            TestsGuerrier.fin(t, c);
        });
    }
}

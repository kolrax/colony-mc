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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * La guerre (refonte 1.0.0, partie 6) : elle se déclare et se fait la paix au poste ({@link Armee#declarer}, {@link Armee#paix}) ;
 * les gardes ont trois missions (défendre, escorter, attaquer). Sans guerre, un garde qui défend la colonie ne touche pas un
 * joueur étranger ; la guerre déclarée, il le frappe ; sous 30 % de sa vie, il rompt et se replie vers le poste ; la troupe qui
 * attaque marche en rangs jusqu'au poste ennemi et en enfonce la porte (pas le bureau) ; la paix faite, il baisse son arme.
 * Le banc « 30 contre 30 » mesure la part du mod pendant une grande bataille. Voir {@link Guerre} et {@link Armee}.
 * Coins 320 à 326.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsGuerre {
    /** Le chef ennemi (pas un joueur), son poste, et un de ses ouvriers (null si {@code x} est null). */
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

    /** Le chef lui donne une mission de garde par sa fiche (comme le joueur) : elle doit être acceptée. */
    static void mission(GameTestHelper h, TestsChef.Scene s, Travailleur g, String id) {
        Missions.Reponse r = Missions.donner(s.chef(), g, id, "fois", 1, "toujours", null);
        h.assertTrue(r.ok(), "la mission « " + id + " » est acceptée : " + r.texte());
    }

    /** L'autre joueur, étranger, a un poste loin d'ici (on peut lui déclarer la guerre) ; il se tient près de notre garde. */
    static BlockPos etranger(GameTestHelper h, TestsChef.Scene s, int coin, double x, double z) {
        BlockPos poste = loin(h, coin);
        Courrier.de(h.getLevel().getServer()).definirPoste(s.autre().getUUID(), h.getLevel(), poste);
        s.autre().teleportTo(h.getLevel(), s.b().getX() + x, s.b().getY(), s.b().getZ() + z, 0, 0);
        return poste;
    }

    static void finEtranger(GameTestHelper h, TestsChef.Scene s, BlockPos poste) {
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.paix(s.chef().getUUID(), s.autre().getUUID());
        c.oublierPoste(s.autre().getUUID(), h.getLevel(), poste);
        c.boites.remove(s.autre().getUUID());
        c.boites.remove(s.chef().getUUID());
        TestsGuerrier.fin(s.g());
        s.partir();
    }

    static boolean[] surveiller(GameTestHelper h, Travailleur g, net.minecraft.world.entity.Entity cible) {
        boolean[] vu = {false};
        h.onEachTick(() -> { if (g.action instanceof Actions.Attaquer a && a.cible == cible) vu[0] = true; });
        return vu;
    }

    /** Sans guerre déclarée : le garde qui défend la colonie laisse le joueur étranger, tout près de lui. */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void defendreSansGuerre(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 320);
        BlockPos poste = etranger(h, s, 320, 3.5, 4.5);
        mission(h, s, s.g(), Guerre.DEFENDRE);
        boolean[] vu = surveiller(h, s.g(), s.autre());
        h.runAtTickTime(h.getTick() + 160, () -> {
            h.assertFalse(vu[0], "il ne l'attaque pas (" + s.g().paroles + ")");
            h.assertTrue(s.autre().getHealth() == s.autre().getMaxHealth(), "l'étranger ne prend aucun coup");
            finEtranger(h, s, poste);
            h.succeed();
        });
    }

    /** La guerre déclarée au poste : les deux le lisent ; le garde qui défend la colonie frappe l'ennemi qui y est. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void defendreEnGuerre(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 321);
        BlockPos poste = etranger(h, s, 321, 3.5, 4.5);
        mission(h, s, s.g(), Guerre.DEFENDRE);
        String dit = Armee.declarer(s.chef(), s.autre().getUUID().toString());
        Courrier c = Courrier.de(h.getLevel().getServer());
        h.assertTrue(dit.contains("déclare la guerre"), "le chef lit la déclaration : " + dit);
        h.assertTrue(c.enGuerre(s.chef().getUUID(), s.autre().getUUID()) && c.enGuerre(s.autre().getUUID(), s.chef().getUUID()), "la guerre, des deux côtés");
        h.assertTrue(!c.boites.getOrDefault(s.autre().getUUID(), List.of()).isEmpty(), "l'autre l'apprend aussi à son poste");
        h.assertTrue(Armee.declarer(s.chef(), s.autre().getUUID().toString()).startsWith("Tu es déjà en guerre"), "pas deux fois");
        h.succeedWhen(() -> {
            h.assertTrue(s.autre().getHealth() < s.autre().getMaxHealth(), "il frappe l'ennemi (" + s.g().paroles + ")");
            finEtranger(h, s, poste);
        });
    }

    /** La troupe qui attaque marche jusqu'au poste ennemi, en enfonce la porte, pas le bureau. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void attaquePosteEnnemi(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 322);
        ServerLevel l = h.getLevel();
        BlockPos poste = TestsJeu.sol(h, 322 * 400 + 24, 60000 + 4);
        l.setBlockAndUpdate(poste, ColoniePnj.BUREAU.get().defaultBlockState());
        BlockPos porte = poste.offset(0, 0, 3);
        l.setBlockAndUpdate(porte, Blocks.OAK_DOOR.defaultBlockState());
        l.setBlockAndUpdate(porte.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Rival r = rival(h, s, 322, poste, null, 0);
        Travailleur g2 = s.ouvrier("guerrier", 1.5, 2.5, new ItemStack(Items.IRON_SWORD)), g3 = s.ouvrier("guerrier", 1.5, 6.5, new ItemStack(Items.IRON_SWORD));
        // Sans guerre (ni lieu) : la mission « attaquer » est acceptée mais attend, bloquée avec sa raison.
        for (Travailleur g : List.of(s.g(), g2, g3)) mission(h, s, g, Guerre.ATTAQUER);
        h.assertTrue(Armee.but(s.g(), s.g().missionActive()) == null, "pas de but sans guerre");
        Courrier.de(l.getServer()).declarer(s.chef().getUUID(), "chef", r.chef(), r.nom());
        h.assertTrue(poste.equals(Armee.but(s.g(), s.g().missionActive())), "le but : le poste ennemi");
        h.succeedWhen(() -> {
            h.assertFalse(l.getBlockState(porte).getBlock() instanceof DoorBlock, "la troupe enfonce la porte (" + s.g().paroles + ")");
            h.assertTrue(l.getBlockState(poste).is(ColoniePnj.BUREAU.get()), "le bureau reste");
            for (Travailleur g : List.of(s.g(), g2, g3))
                h.assertTrue(g.distanceToSqr(Vec3.atBottomCenterOf(poste)) < 12 * 12, g.prenom + " est arrivé avec la troupe");
            l.removeBlock(poste, false);
            r.finir(s, g2, g3);
        });
    }

    /** La paix faite au poste pendant qu'il frappe l'ouvrier ennemi : il baisse son arme et ne le touche plus. */
    @GameTest(template = "vide", timeoutTicks = 500)
    public static void paixPlusDAttaque(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 323);
        Rival r = rival(h, s, 323, loin(h, 323), 4.5, 7.5);
        mission(h, s, s.g(), Guerre.DEFENDRE);
        String guerre = Armee.declarer(s.chef(), r.chef().toString()); // un chef absent, désigné dans la liste des postes
        h.assertTrue(guerre.contains("déclare la guerre"), "la guerre contre le rival : " + guerre);
        long[] paix = {-1};
        boolean[] apres = {false};
        String[] dit = {""};
        h.onEachTick(() -> {
            boolean attaque = s.g().action instanceof Actions.Attaquer a && a.cible == r.o();
            if (attaque && paix[0] < 0) {
                paix[0] = h.getTick();
                dit[0] = Armee.paix(s.chef(), "");
            }
            if (paix[0] >= 0 && h.getTick() == paix[0] + 20) r.o().setHealth(r.o().getMaxHealth());
            if (paix[0] >= 0 && h.getTick() > paix[0] + 20 && attaque) apres[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(paix[0] >= 0, "la guerre : il attaque d'abord");
            h.assertTrue(h.getTick() > paix[0] + 140, "on attend");
            h.assertTrue(dit[0].contains("fait la paix"), "le chef lit la paix : " + dit[0]);
            h.assertFalse(Courrier.de(h.getLevel().getServer()).enGuerre(s.chef().getUUID(), r.chef()), "plus de guerre");
            h.assertFalse(apres[0], "après la paix, plus d'attaque");
            h.assertTrue(r.o().getHealth() == r.o().getMaxHealth(), "plus un coup");
            r.finir(s);
        });
    }

    /** En plein combat, sous 30 % de sa vie : il rompt et recule jusqu'au poste. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void repliSous30(GameTestHelper h) {
        TestsChef.Scene s = TestsChef.scene(h, 324);
        BlockPos poste = etranger(h, s, 324, 16.5, 4.5);
        s.g().teleportTo(s.b().getX() + 14.5, s.b().getY(), s.b().getZ() + 4.5);
        mission(h, s, s.g(), Guerre.DEFENDRE);
        Armee.declarer(s.chef(), s.autre().getUUID().toString());
        long[] blesse = {-1};
        h.onEachTick(() -> {
            if (blesse[0] < 0 && s.g().action instanceof Actions.Attaquer a && a.cible == s.autre()) {
                blesse[0] = h.getTick();
                s.g().setHealth(s.g().getMaxHealth() * 0.25f);
            }
            if (blesse[0] >= 0) s.autre().setHealth(s.autre().getMaxHealth()); // l'étranger tient bon : seul le repli compte
        });
        h.succeedWhen(() -> {
            h.assertTrue(blesse[0] >= 0, "il charge d'abord l'ennemi");
            h.assertTrue(s.g().distanceToSqr(Vec3.atBottomCenterOf(s.b())) < 4 * 4, "il recule jusqu'au poste (" + s.g().paroles + ", " + (s.g().action == null ? "-" : s.g().action.nom) + ")");
            h.assertFalse(s.g().action instanceof Actions.Attaquer a && a.cible == s.autre(), "il ne se bat plus");
            finEtranger(h, s, poste);
        });
    }

    /**
     * Le banc de la grande bataille : 30 gardes « rouges » attaquent le poste des 30 « bleus », qui le défendent. On vérifie
     * qu'ils se battent, et on écrit au journal la part du mod par tick (Charge) pour la comparer au budget d'un tick (50 ms).
     */
    @GameTest(template = "vide", timeoutTicks = 700, skyAccess = true, batch = "bataille")
    public static void bataille30contre30(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        Courrier c = Courrier.de(l.getServer());
        UUID rouge = UUID.randomUUID(), bleu = UUID.randomUUID();
        BlockPos pr = TestsJeu.sol(h, 325 * 400, 60000), pb = TestsJeu.sol(h, 325 * 400 + 40, 60000);
        c.definirPoste(rouge, l, pr);
        c.definirPoste(bleu, l, pb);
        c.declarer(rouge, "Rouge", bleu, "Bleu");
        List<Travailleur> rouges = new ArrayList<>(), bleus = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            for (boolean r : new boolean[]{true, false}) {
                BlockPos p = r ? pr : pb;
                Travailleur g = Travailleur.auTravail(l, "guerrier", p, new Vec3(p.getX() + 2.5 + i % 6 * 1.5 * (r ? -1 : 1), p.getY(), p.getZ() - 6.5 + i / 6 * 2.5), r ? rouge : bleu, new ItemStack(Items.IRON_SWORD));
                g.chefNom = r ? "Rouge" : "Bleu";
                Guerrier.armer(g);
                g.missions.add(new Travailleur.Mission(r ? Guerre.ATTAQUER : Guerre.DEFENDRE, "quantite", 1));
                if (i == 0) g.forcerChunk();
                (r ? rouges : bleus).add(g);
            }
        }
        h.runAtTickTime(h.getTick() + 500, () -> {
            Charge ch = Charge.ICI;
            long touches = bleus.stream().filter(g -> g.getHealth() < g.getMaxHealth() || g.aTerre || !g.isAlive()).count()
                    + rouges.stream().filter(g -> g.getHealth() < g.getMaxHealth() || g.aTerre || !g.isAlive()).count();
            double pres = rouges.stream().mapToDouble(g -> Math.sqrt(g.distanceToSqr(Vec3.atBottomCenterOf(pb)))).average().orElse(-1);
            ColoniePnj.LOG.info("[bataille 30 contre 30] part du mod : {} ms par tick en moyenne, {} ms au pire ; cerveau {} ms, actions {} ms, chemins {} ms ; {} gardes touchés, rouges à {} blocs du poste bleu en moyenne",
                    String.format(java.util.Locale.ROOT, "%.3f", ch.moyenneMs(Charge.Partie.TOTAL)), String.format(java.util.Locale.ROOT, "%.3f", ch.pireMs(Charge.Partie.TOTAL)),
                    String.format(java.util.Locale.ROOT, "%.3f", ch.moyenneMs(Charge.Partie.CERVEAU)), String.format(java.util.Locale.ROOT, "%.3f", ch.moyenneMs(Charge.Partie.ACTION)),
                    String.format(java.util.Locale.ROOT, "%.3f", ch.moyenneMs(Charge.Partie.CHEMINS)), touches, String.format(java.util.Locale.ROOT, "%.1f", pres));
            c.paix(rouge, bleu);
            c.oublierPoste(rouge, l, pr);
            c.oublierPoste(bleu, l, pb);
            c.boites.remove(rouge);
            c.boites.remove(bleu);
            rouges.forEach(Travailleur::discard);
            bleus.forEach(Travailleur::discard);
            h.assertTrue(touches >= 10, "ils se battent : " + touches + " gardes touchés");
            h.succeed();
        });
    }
}

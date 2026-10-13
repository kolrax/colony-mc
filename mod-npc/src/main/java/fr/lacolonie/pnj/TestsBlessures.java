package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lot 5, les blessures (cahier 1.11 et 5.12) : gravité selon le coup, temps à terre selon la gravité, les compagnons qui
 * accourent autour du blessé, guérison avec les jours, repos forcé quand c'est très grave, combat rompu.
 * Coins 130 à 134 du monde plat.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsBlessures {
    static Travailleur ouvrier(GameTestHelper h, String metier, BlockPos bureau, BlockPos pos, UUID chef, ItemStack outil) {
        return Travailleur.auTravail(h.getLevel(), metier, bureau, Vec3.atBottomCenterOf(pos), chef, outil);
    }

    static double lenteur(Travailleur t) {
        var m = t.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Blessures.LENTEUR);
        return m == null ? 0 : -m.amount();
    }

    /**
     * Deux ouvriers à terre : l'un d'un coup très grave (100 points, 8 min), l'autre d'un coup léger qui l'achève (3 points,
     * 3 min). Tous deux vivent encore après 2 min 05 (avant : morts au bout de 2 min). Puis le temps du léger écourté : il
     * meurt au bout, comme avant (pas de relevé seul), le très grave reste à terre.
     */
    @GameTest(template = "vide", timeoutTicks = 2700)
    public static void aTerrePlusLongtempsSiTresGrave(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 130 * 400, 60000);
        Travailleur grave = ouvrier(h, "mineur", base, base.west(3), UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE));
        Travailleur leger = ouvrier(h, "mineur", base, base.east(3), UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE));
        grave.forcerChunk();
        grave.hurt(grave.damageSources().generic(), 100);
        leger.setHealth(2);
        leger.hurt(leger.damageSources().generic(), 3);
        h.assertTrue(grave.aTerre && leger.aTerre, "tous deux à terre");
        h.assertTrue(Blessures.gravite(grave) == Gravite.TRES_GRAVE, "très grave : " + Blessures.gravite(grave));
        h.assertTrue(Blessures.gravite(leger) == Gravite.LEGERE, "légère : " + Blessures.gravite(leger));
        h.assertTrue(grave.aTerreTicks == 8 * 60 * 20 && leger.aTerreTicks == 3 * 60 * 20, "à terre " + grave.aTerreTicks + " / " + leger.aTerreTicks);
        long debut = h.getTick();
        boolean[] vivants = {false};
        h.onEachTick(() -> {
            if (!vivants[0] && h.getTick() - debut >= 2500) {
                h.assertTrue(grave.aTerre && grave.isAlive() && leger.aTerre && leger.isAlive(),
                        "après 2 min 05, tous deux à terre et vivants (" + grave.aTerreTicks + " / " + leger.aTerreTicks + ")");
                vivants[0] = true;
                leger.aTerreTicks = 5; // la fin de ses 3 minutes
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(vivants[0], "2 min 05 passées");
            h.assertFalse(leger.isAlive(), "le léger meurt au bout de son temps, sans se relever seul");
            h.assertTrue(grave.aTerre && grave.isAlive(), "le très grave est toujours à terre, vivant");
            h.assertTrue(TestsVie.dites(grave, "a_terre") == 1, "il appelle à l'aide (" + grave.paroles + ")");
            grave.discard();
        });
    }

    /**
     * Un ouvrier tombe à terre : les trois plus proches de son chef accourent (à 6, 8 et 10 blocs), se tiennent autour de lui
     * et lui disent un mot ; le quatrième (12 blocs), celui qui attend sur ordre, le guerrier de garde et l'ouvrier d'un
     * autre chef ne bougent pas. Relevé, les trois repartent. Avant : personne ne venait.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void ouvriersAccourentPuisRepartent(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 131 * 400, 60000);
        UUID chef = UUID.randomUUID();
        BlockPos lieu = base.south(6);
        Travailleur blesse = ouvrier(h, "mineur", base, lieu, chef, new ItemStack(Items.IRON_PICKAXE));
        blesse.forcerChunk();
        List<Travailleur> aides = List.of(
                ouvrier(h, "mineur", base, lieu.east(6), chef, new ItemStack(Items.IRON_PICKAXE)),
                ouvrier(h, "mineur", base, lieu.west(8), chef, new ItemStack(Items.IRON_PICKAXE)),
                ouvrier(h, "mineur", base, lieu.east(10), chef, new ItemStack(Items.IRON_PICKAXE)));
        Travailleur loin = ouvrier(h, "mineur", base, lieu.west(12), chef, new ItemStack(Items.IRON_PICKAXE));
        Travailleur attend = ouvrier(h, "mineur", base, lieu.east(3), chef, new ItemStack(Items.IRON_PICKAXE));
        attend.mode = Travailleur.Mode.ATTENDRE;
        Travailleur garde = ouvrier(h, "guerrier", base, lieu.north(2), chef, new ItemStack(Items.IRON_SWORD));
        Travailleur autre = ouvrier(h, "mineur", base, lieu.west(4), UUID.randomUUID(), new ItemStack(Items.IRON_PICKAXE));
        List<Travailleur> restent = List.of(loin, attend, garde, autre);
        Set<Travailleur> venus = new HashSet<>(), autour = new HashSet<>(), restes = new HashSet<>();
        long[] tombe = {-1}, releve = {-1};
        boolean[] partis = {false}, tres = {false};
        var joueur = h.makeMockServerPlayerInLevel(); // celui qui le relèvera, loin d'eux (à l'origine du test)
        h.onEachTick(() -> {
            // Une seconde pour que tout le monde soit là et au travail, puis le coup.
            if (tombe[0] < 0 && h.getTick() > 20) {
                blesse.hurt(blesse.damageSources().generic(), 100);
                tombe[0] = h.getTick();
                tres[0] = blesse.aTerre && Blessures.gravite(blesse) == Gravite.TRES_GRAVE;
            }
            if (tombe[0] < 0) return;
            for (Travailleur t : aides) {
                if (t.action instanceof Blessures.Accourir) venus.add(t);
                if (t.action instanceof Blessures.Accourir a && a.arrive && t.distanceToSqr(blesse) <= 3.5 * 3.5) autour.add(t);
            }
            for (Travailleur t : restent) if (t.action instanceof Blessures.Accourir) restes.add(t);
            if (releve[0] < 0 && autour.size() == 3) {
                blesse.relever(joueur);
                releve[0] = h.getTick();
            }
            if (releve[0] >= 0 && !partis[0] && aides.stream().noneMatch(t -> t.action instanceof Blessures.Accourir)) partis[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(tres[0], "à terre, très grave");
            h.assertTrue(venus.size() == 3, "les trois plus proches accourent (" + venus.size() + ")");
            h.assertTrue(restes.isEmpty(), "personne d'autre ne vient (" + restes.stream().map(t -> t.prenom + " " + t.metier).toList() + ")");
            h.assertTrue(autour.size() == 3, "les trois se tiennent autour de lui (" + autour.size() + ")");
            for (Travailleur t : aides) h.assertTrue(TestsVie.dites(t, "blesse_vu") == 1, t.prenom + " lui dit un mot (" + t.paroles + ")");
            h.assertTrue(releve[0] >= 0 && !blesse.aTerre, "relevé");
            h.assertTrue(TestsVie.dites(blesse, "releve") == 1, "il dit qu'il est debout (" + blesse.paroles + ")");
            h.assertTrue(partis[0], "relevé, ils repartent");
            blesse.discard();
            aides.forEach(Travailleur::discard);
            restent.forEach(Travailleur::discard);
        });
    }

    /**
     * Une chute qui lui ôte 35 % de sa vie (sa vie va de 20 à 28 points selon ses étoiles) : blessure grave, il le dit et marche 15 % moins vite. Un jour de jeu plus tard (la date de la
     * blessure reculée d'un jour), elle n'est plus que légère et la lenteur s'en va ; un jour encore, guérie et oubliée.
     */
    @GameTest(template = "vide", timeoutTicks = 300)
    public static void gueritAvecLeTemps(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 132 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        ServerLevel l = h.getLevel();
        t.hurt(t.damageSources().fall(), t.getMaxHealth() * 0.35f);
        h.assertTrue(Blessures.gravite(t) == Gravite.GRAVE, "une chute de 35 % : grave (" + Blessures.gravite(t) + ", vie " + t.getHealth() + "/" + t.getMaxHealth() + ")");
        h.assertTrue(lenteur(t) == 0.15, "15 % plus lent (" + lenteur(t) + ")");
        h.assertTrue(TestsVie.dites(t, "blessure_grave") == 1, "il le dit (" + t.paroles + ")");
        int[] etape = {0};
        h.onEachTick(() -> {
            if (etape[0] == 0 && h.getTick() > 40) { t.memoire.putLong("blessure_le", l.getDayTime() - Gravite.JOUR); etape[0] = 1; }
            else if (etape[0] == 1 && Blessures.gravite(t) == Gravite.LEGERE && lenteur(t) == 0) {
                t.memoire.putLong("blessure_le", l.getDayTime() - Gravite.JOUR); // la légère : un jour encore
                etape[0] = 2;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(etape[0] == 2, "un jour après, légère et sans lenteur (" + Blessures.gravite(t) + ", " + lenteur(t) + ")");
            h.assertTrue(Blessures.gravite(t) == Gravite.AUCUNE && lenteur(t) == 0, "deux jours après, guérie");
            h.assertFalse(t.memoire.contains("blessure"), "et oubliée (" + t.memoire + ")");
            t.discard();
        });
    }

    /**
     * Très gravement blessé, le mineur ne mine pas : le filon de charbon à deux pas reste intact 20 s, il reste au poste,
     * 30 % plus lent. Un jour plus tard (la blessure n'est plus que grave), il le mine. Avant : il le minait tout de suite.
     */
    @GameTest(template = "vide", timeoutTicks = 1500)
    public static void tresGraveNeMinePas(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 133 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        t.mission("charbon", "stock", 64);
        ServerLevel l = h.getLevel();
        BlockPos filon = base.west(2).south(2);
        l.setBlockAndUpdate(filon, Blocks.COAL_ORE.defaultBlockState());
        Blessures.noter(t, Gravite.TRES_GRAVE);
        long debut = h.getTick();
        boolean[] mineTot = {false}, loin = {false}, lent = {false}, gueri = {false};
        h.onEachTick(() -> {
            boolean mine = !l.getBlockState(filon).is(Blocks.COAL_ORE);
            if (!gueri[0]) {
                if (mine) mineTot[0] = true;
                if (t.distanceToSqr(Vec3.atBottomCenterOf(base)) > 6 * 6) loin[0] = true;
                if (lenteur(t) == 0.30) lent[0] = true;
                if (h.getTick() - debut >= 400) { t.memoire.putLong("blessure_le", l.getDayTime() - Gravite.JOUR); gueri[0] = true; }
            }
        });
        h.succeedWhen(() -> {
            h.assertFalse(mineTot[0], "très grave, il ne mine pas");
            h.assertFalse(loin[0], "il reste au poste");
            h.assertTrue(lent[0], "30 % plus lent");
            h.assertTrue(gueri[0] && Blessures.gravite(t) == Gravite.GRAVE, "un jour après, grave seulement");
            h.assertFalse(l.getBlockState(filon).is(Blocks.COAL_ORE), "puis il mine le filon (" + TestsJeu.etat(t, base) + ")");
            t.discard();
        });
    }

    /**
     * Un guerrier frappe un husk (figé, solide) ; en plein combat, sa blessure devient très grave : il rompt et recule, et ne
     * reprend pas le combat. Avant : il continuait de frapper, sans plus pouvoir boire ni reculer.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void tresGraveRomptLeCombat(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 134 * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "guerrier", base, Vec3.atBottomCenterOf(base.west(2)), new ItemStack(Items.IRON_SWORD));
        t.forcerChunk();
        var husk = TestsGuerrier.fige(h, net.minecraft.world.entity.EntityType.HUSK, base.west(2).south(5));
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
        husk.setHealth(500);
        long[] blesse = {-1}, fuite = {-1};
        boolean[] reprend = {false};
        h.onEachTick(() -> {
            if (blesse[0] < 0 && t.action instanceof Actions.Attaquer) { Blessures.noter(t, Gravite.TRES_GRAVE); blesse[0] = h.getTick(); }
            if (blesse[0] >= 0 && fuite[0] < 0 && t.action instanceof Actions.Fuir) fuite[0] = h.getTick();
            if (fuite[0] >= 0 && t.action instanceof Actions.Attaquer) reprend[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(blesse[0] >= 0, "il attaque le husk (" + TestsJeu.etat(t, base) + ")");
            h.assertTrue(fuite[0] >= 0 && fuite[0] - blesse[0] <= 40, "très grave, il recule aussitôt (" + TestsJeu.etat(t, base) + ")");
            h.assertFalse(reprend[0], "il ne reprend pas le combat");
            h.assertTrue(h.getTick() >= fuite[0] + 200, "10 s sans reprendre");
            husk.discard();
            t.discard();
        });
    }
}

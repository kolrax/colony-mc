package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Le déblocage (cahier 14.10 et 11.3) : un ouvrier coincé pose des blocs pour sortir ; en dernier recours, il est ramené
 * au poste avec un avis à son chef. Le lot « deblocage » abaisse le délai du dernier recours ({@link Coince#DELAI}) à
 * 15 s : ses tests tournent seuls, sans gêner les autres.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsDeblocage {
    static int delaiAvant, entreDeuxAvant, fenetreAvant;

    @BeforeBatch(batch = "deblocage")
    public static void avant(ServerLevel l) {
        delaiAvant = Coince.DELAI;
        entreDeuxAvant = Coince.ENTRE_DEUX;
        Coince.DELAI = 300;
        Coince.ENTRE_DEUX = 400;
    }

    @AfterBatch(batch = "deblocage")
    public static void apres(ServerLevel l) { Coince.DELAI = delaiAvant; Coince.ENTRE_DEUX = entreDeuxAvant; }

    /** Le lot « deblocage2 » : délai de 15 s, et un échec ne compte que 5 s (au lieu d'une minute). */
    @BeforeBatch(batch = "deblocage2")
    public static void avant2(ServerLevel l) {
        delaiAvant = Coince.DELAI;
        fenetreAvant = Coince.FENETRE_ECHEC;
        Coince.DELAI = 300;
        Coince.FENETRE_ECHEC = 100;
    }

    @AfterBatch(batch = "deblocage2")
    public static void apres2(ServerLevel l) { Coince.DELAI = delaiAvant; Coince.FENETRE_ECHEC = fenetreAvant; }

    /** Un sol de pierre et, autour de (12, 4, 12), un enclos de planches (une construction : il n'y touche pas) de 4 blocs de haut. */
    static void enclos(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) for (int y = 4; y <= 7; y++) h.setBlock(12 + dx, y, 12 + dz, Blocks.OAK_PLANKS);
    }

    static int planches(GameTestHelper h) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 7; y++)
            if (h.getBlockState(new BlockPos(12 + dx, y, 12 + dz)).is(Blocks.OAK_PLANKS)) n++;
        return n;
    }

    /**
     * Enfermé dans un enclos de planches de 4 blocs de haut (qu'il ne peut pas creuser), de la pierre taillée en poche,
     * son bureau dehors à 10 blocs : il monte sur des blocs posés sous lui, sort par le haut du mur et rejoint son bureau.
     * Avant, il se disait coincé et y restait. Il a une pioche : une fois sur le mur, il reprend les blocs qu'il a à portée
     * de main (les trois du haut ; celui du bas est trop loin, il reste).
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void sortEnPosantDesBlocs(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(planches(h) == 32, "il ne doit pas casser l'enclos (" + e + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + e + ")");
            h.assertTrue(h.getBlockState(new BlockPos(12, 4, 12)).is(Blocks.COBBLESTONE), "il doit être sorti sur un pilier de blocs posés (" + e + ")");
            h.assertTrue(h.getBlockState(new BlockPos(12, 7, 12)).isAir() && h.getBlockState(new BlockPos(12, 5, 12)).isAir(),
                    "il doit avoir repris les blocs à portée de main (" + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 (montagne) : au fond d'un puits de pierre naturelle de 3 blocs, sans pioche, de la terre en poche, son bureau
     * en haut. Avant, il creusait un escalier dans la pierre à mains nues (7 s par bloc, rien de récolté). Maintenant il
     * monte sur des blocs de terre posés sous lui, sans toucher à la pierre, et les reprend tous une fois en haut.
     */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true)
    public static void monteSurSesBlocsSansPioche(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++)
            if (x != 12 || z != 12 || y < 4) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.DIRT, 16), new BlockPos(12, 7, 4));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", terre " + t.compter(Actions.Filtre.de("minecraft:dirt"));
            int pierres = 0;
            for (int x = 9; x <= 15; x++) for (int z = 9; z <= 15; z++) for (int y = 4; y <= 6; y++)
                if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) pierres++;
            h.assertTrue(pierres == 7 * 7 * 3 - 3, "il ne doit pas creuser la pierre à mains nues (" + pierres + " pierres, " + e + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:dirt")) == 16, "il doit avoir repris toute sa terre (" + e + ")");
            t.discard();
        });
    }

    /**
     * Sur un îlot de pierre de 3 x 3, entouré d'une douve vide d'un bloc de large et de 10 de profondeur (trop haut pour
     * sauter), de la pierre en poche, son bureau de l'autre côté : il pose un bloc de pont et passe.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void passeEnPosantUnPont(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++)
            if (Math.max(Math.abs(x - 12), Math.abs(z - 12)) != 2) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 11, 3));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit avoir passé la douve et rejoint son bureau (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:cobblestone")) < 16, "il doit avoir posé un bloc (" + e + ")");
            t.discard();
        });
    }

    /** Comment finit chaque action de ce PNJ (« aller:bloque »…), relevé à chaque tick. */
    static java.util.List<String> fins(GameTestHelper h, Travailleur t) {
        java.util.List<String> fins = new java.util.ArrayList<>();
        Actions.Action[] derniere = {null};
        h.onEachTick(() -> {
            if (t.action == derniere[0]) return;
            if (derniere[0] != null) fins.add(derniere[0].nom + ":" + derniere[0].raison);
            derniere[0] = t.action;
        });
        return fins;
    }

    /**
     * Le même enclos, mais le sol est une dalle du bas : un bloc ne peut pas aller à la place de ses pieds. Il ne s'acharne
     * pas à monter (avant : des tentatives sans fin, une vingtaine de recherches de chemin chacune, jamais « coincé ») : il
     * se dit coincé, avec peu de recherches.
     */
    @GameTest(template = "grand", timeoutTicks = 700, skyAccess = true)
    public static void neSAcharnePasSurUneDalle(GameTestHelper h) {
        enclos(h);
        h.setBlock(12, 4, 12, Blocks.STONE_SLAB);
        Travailleur t = TestsJeu.employe(h, "mineur", h.absolutePos(new BlockPos(12, 4, 2)), h.absoluteVec(new Vec3(12.5, 4.5, 12.5)), new ItemStack(Items.COBBLESTONE, 16));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        var fins = fins(h, t);
        int debut = t.recherches;
        h.runAtTickTime(h.getTick() + 600, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", fins " + fins + ", " + (t.recherches - debut) + " recherches";
            t.discard();
            h.assertTrue(fins.contains("aller:bloque"), "il doit se dire coincé (" + e + ")");
            h.assertTrue(t.recherches - debut < 80, "trop de recherches de chemin (" + e + ")");
            h.succeed();
        });
    }

    /** Un ouvrier qui doit attendre là (gardé, prisonnier), envoyé dehors : il ne pose ni ne creuse pour s'en aller. */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true)
    public static void prisonnierNeSEvadePas(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        t.mode = Travailleur.Mode.ATTENDRE;
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(h.getTick() + 400, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            int pierres = t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            t.discard();
            h.assertTrue(pierres == 16 && h.getBlockState(new BlockPos(12, 4, 12)).isAir(), "il ne doit poser aucun bloc (" + pierres + " pierres, " + e + ")");
            h.assertTrue(planches(h) == 32, "ni rien casser (" + e + ")");
            h.succeed();
        });
    }

    /** Sa mission demande 16 pierres et il en a 16 : il n'en pose aucune pour sortir (il garde ce que la mission demande). */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true)
    public static void garderLesPierresDeLaMission(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        t.mission("pierre", "quantite", 16);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(h.getTick() + 400, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            int pierres = t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            t.discard();
            h.assertTrue(pierres == 16 && h.getBlockState(new BlockPos(12, 4, 12)).isAir(), "il ne doit pas poser les pierres de sa mission (" + pierres + ", " + e + ")");
            h.succeed();
        });
    }

    /** Une tour de planches de 5 blocs de haut (qu'il ne peut ni creuser ni escalader), son chef dessus ; son bureau à l'autre bout. */
    static net.minecraft.server.level.ServerPlayer chefSurUneTour(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) for (int y = 4; y <= 8; y++) h.setBlock(x, y, z, Blocks.OAK_PLANKS);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(5.5, 9, 5.5));
        chef.moveTo(p.x, p.y, p.z);
        return chef;
    }

    /** Aucun saut de plus de 2 blocs d'un tick à l'autre (une téléportation), relevé à chaque tick. */
    static double[] sauts(GameTestHelper h, Travailleur t) {
        Vec3[] avant = {t.position()};
        double[] saut = {0};
        h.onEachTick(() -> { saut[0] = Math.max(saut[0], t.position().distanceTo(avant[0])); avant[0] = t.position(); });
        return saut;
    }

    /**
     * Il suit son chef, qui est sur une tour qu'il ne peut pas atteindre, plus longtemps que le délai ; puis son chef le
     * remet au travail : il n'est pas ramené au poste (le temps passé à suivre ne compte pas). Avant : ramené tout de suite.
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true, batch = "deblocage")
    public static void suivreNeComptePas(GameTestHelper h) {
        var chef = chefSurUneTour(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 10, 4, 10, ItemStack.EMPTY, new BlockPos(20, 4, 20));
        t.chef = chef.getUUID();
        t.mode = Travailleur.Mode.SUIVRE;
        double[] saut = sauts(h, t);
        h.runAtTickTime(h.getTick() + 400, () -> { t.mode = Travailleur.Mode.TRAVAIL; t.finirAction("interrompu"); });
        h.runAtTickTime(h.getTick() + 700, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit pas être ramené (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.succeed();
        });
    }

    /** Un guerrier qui escorte son chef, perché hors d'atteinte : il n'est jamais ramené au poste. */
    @GameTest(template = "grand", timeoutTicks = 700, skyAccess = true, batch = "deblocage")
    public static void escorteNEstPasRamenee(GameTestHelper h) {
        var chef = chefSurUneTour(h);
        Travailleur t = TestsJeu.ouvrier(h, "guerrier", 10, 4, 10, new ItemStack(Items.IRON_SWORD), new BlockPos(20, 4, 20));
        t.chef = chef.getUUID();
        t.mission("escorter", "stock", 64);
        double[] saut = sauts(h, t);
        h.runAtTickTime(h.getTick() + 600, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit pas être ramené (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.assertTrue(TestsJeu.distance(t, t.bureau) > 8, "il doit être resté près de son chef (" + e + ")");
            h.succeed();
        });
    }

    /** Un échec « inaccessible », puis il dort sur place plus longtemps que le délai : il n'est pas ramené (repos et sommeil ne comptent pas). */
    @GameTest(template = "grand", timeoutTicks = 600, skyAccess = true, batch = "deblocage")
    public static void dormirNeComptePas(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 14, ItemStack.EMPTY, new BlockPos(12, 4, 2));
        BlockPos lit = h.absolutePos(new BlockPos(12, 4, 14));
        TestsJeu.lit(h.getLevel(), lit);
        t.fatigue = 100;
        double[] saut = sauts(h, t);
        h.runAtTickTime(h.getTick() + 40, () -> { // après le premier relevé du compte
            t.finirAction("inaccessible");
            t.startSleeping(lit);
            Cerveau.lancer(t, new Actions.Reposer());
        });
        h.runAtTickTime(h.getTick() + 500, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", dort " + t.isSleeping();
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit pas être ramené (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.succeed();
        });
    }

    /**
     * Affamé, sans rien à manger au poste, il va demander à manger à son chef, perché sur une tour qu'il ne peut pas
     * atteindre : marcher vers quelqu'un n'est pas être coincé, il n'est pas ramené au poste.
     */
    @GameTest(template = "grand", timeoutTicks = 600, skyAccess = true, batch = "deblocage")
    public static void demanderAuChefNeComptePas(GameTestHelper h) {
        var chef = chefSurUneTour(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 10, 4, 10, ItemStack.EMPTY, new BlockPos(20, 4, 20));
        t.chef = chef.getUUID();
        t.faim = 5;
        double[] saut = sauts(h, t);
        h.runAtTickTime(h.getTick() + 500, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", besoin " + t.besoin;
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit pas être ramené (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.succeed();
        });
    }

    /**
     * Ramené deux fois, il attend au poste ; son chef le remet au travail, il se coince encore : il est ramené une
     * troisième fois (et attend de nouveau), au lieu de rester coincé pour toujours.
     */
    @GameTest(template = "grand", timeoutTicks = 2400, skyAccess = true, batch = "deblocage")
    public static void remisAuTravailIlPeutEtreRameneEncore(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, ItemStack.EMPTY, new BlockPos(12, 4, 2));
        Vec3 dedans = h.absoluteVec(new Vec3(12.5, 4, 12.5));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        var boite = Courrier.de(h.getLevel().getServer()).boite(t.chef);
        int[] etape = {1};
        h.onEachTick(() -> {
            long retours = boite.stream().filter(l -> l.texte.contains("ramené au poste")).mapToLong(l -> l.nombre).sum(); // les avis se regroupent
            if (retours < etape[0]) return;
            if (etape[0] == 2) { // ramené deux fois : il attend ; son chef le remet au travail
                if (t.mode != Travailleur.Mode.ATTENDRE) return;
                t.mode = Travailleur.Mode.TRAVAIL;
            }
            if (etape[0] < 3) { // de nouveau coincé dans l'enclos
                t.teleportTo(dedans.x, dedans.y, dedans.z);
                Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
            }
            etape[0]++;
        });
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", étape " + etape[0] + ", mode " + t.mode;
            h.assertTrue(etape[0] > 3, "il doit être ramené une troisième fois (" + e + ")");
            h.assertTrue(t.mode == Travailleur.Mode.ATTENDRE, "puis attendre de nouveau au poste (" + e + ")");
            t.discard();
        });
    }

    /**
     * Un échec « inaccessible », puis il attend tranquillement (sans mission, rien à faire) loin du poste : passé la
     * fenêtre de l'échec (abaissée à 5 s), cette attente ne compte plus, il n'est pas ramené.
     */
    @GameTest(template = "grand", timeoutTicks = 600, skyAccess = true, batch = "deblocage2")
    public static void attenteOrdinaireApresEchecNeComptePas(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 16, ItemStack.EMPTY, new BlockPos(12, 4, 2));
        double[] saut = sauts(h, t);
        h.runAtTickTime(h.getTick() + 40, () -> t.finirAction("inaccessible")); // après le premier relevé du compte
        h.runAtTickTime(h.getTick() + 500, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit pas être ramené (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.succeed();
        });
    }

    /**
     * Le même enclos, rien à poser ni à creuser : au bout du délai (abaissé à 15 s), il est ramené près de son bureau, et
     * le poste en avertit son chef par un avis.
     */
    @GameTest(template = "grand", timeoutTicks = 900, skyAccess = true, batch = "deblocage")
    public static void rameneAuPosteEnDernierRecours(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, ItemStack.EMPTY, new BlockPos(12, 4, 2));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        var boite = Courrier.de(h.getLevel().getServer()).boite(t.chef);
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 9, "il doit avoir été ramené près du bureau (" + e + ")");
            h.assertTrue(planches(h) == 32, "l'enclos ne doit pas être cassé (" + e + ")");
            h.assertTrue(boite.stream().anyMatch(l -> l.texte.contains(t.prenom) && l.texte.contains("coincé")), "le chef doit avoir un avis (" + boite.size() + " lettres)");
            t.discard();
        });
    }

    /**
     * Garde-fou : un ouvrier qui marche normalement (une centaine de blocs, plus long que le délai abaissé) n'est jamais
     * téléporté : aucun saut de plus de 2 blocs d'un tick à l'autre.
     */
    @GameTest(template = "vide", timeoutTicks = 700, batch = "deblocage")
    public static void quiMarcheNEstJamaisRamene(GameTestHelper h) {
        BlockPos bureau = TestsJeu.sol(h, 0, 97000);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(bureau.east(2)), ItemStack.EMPTY);
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(TestsJeu.sol(h, 120, 97000)), 2));
        Vec3[] avant = {t.position()};
        double[] saut = {0};
        h.onEachTick(() -> { saut[0] = Math.max(saut[0], t.position().distanceTo(avant[0])); avant[0] = t.position(); });
        h.runAtTickTime(h.getTick() + 600, () -> {
            String e = TestsJeu.etat(t, bureau) + ", " + t.diag;
            int loin = TestsJeu.distance(t, bureau);
            t.discard();
            h.assertTrue(saut[0] < 2, "il ne doit jamais être téléporté (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            h.assertTrue(loin > 40, "il doit avoir marché (" + e + ")");
            h.succeed();
        });
    }
}

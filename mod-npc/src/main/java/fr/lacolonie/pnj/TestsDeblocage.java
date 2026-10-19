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
     * Avant, il se disait coincé et y restait. Il a une pioche : une fois sur le mur, il reprend tout son pilier, penché
     * au bord, jusqu'au bloc du bas (plan A3 ; avant, celui-là, trop loin, restait dans l'enclos).
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
            h.assertTrue(t.memoire.getCompound("dits").contains("pilier"), "il doit être sorti sur un pilier de blocs posés (" + e + ")");
            for (int y = 4; y <= 7; y++) h.assertTrue(h.getBlockState(new BlockPos(12, y, 12)).isAir(), "il doit avoir repris tout son pilier (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:cobblestone")) == 16, "sans perdre une pierre (" + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 : le monde s'arrête (ou son coin se décharge) en pleine montée sur son pilier, dans l'enclos. Il est
     * sauvegardé là, retiré sans rien reprendre, puis relu et renvoyé à son bureau : ses blocs sont toujours suivis, il
     * finit de sortir et reprend tout son pilier, l'ancien comme le nouveau, sans perdre une pierre.
     */
    @GameTest(template = "grand", timeoutTicks = 1600, skyAccess = true)
    public static void reprendSonPilierApresUnArret(GameTestHelper h) {
        enclos(h);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 4, 2));
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Travailleur[] copie = new Travailleur[1];
        h.succeedWhen(() -> {
            if (copie[0] == null) {
                h.assertTrue(t.pilierPose.size() >= 2, "en attente de la montée (" + t.diag + ")");
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                t.saveWithoutId(tag);
                t.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK); // sans passer par remove()
                Travailleur c = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
                c.load(tag);
                c.setUUID(java.util.UUID.randomUUID());
                h.assertTrue(h.getLevel().addFreshEntity(c), "relu dans le monde");
                Cerveau.lancer(c, new Actions.Aller(Vec3.atBottomCenterOf(c.bureau), 3));
                copie[0] = c;
                h.assertTrue(false, "relu, en attente");
            }
            Travailleur c = copie[0];
            String e = TestsJeu.etat(c, c.bureau) + ", " + c.diag + ", pierres " + c.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(planches(h) == 32, "il ne doit pas casser l'enclos (" + e + ")");
            h.assertTrue(c.distanceToSqr(Vec3.atBottomCenterOf(c.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + e + ")");
            for (int y = 4; y <= 8; y++) h.assertTrue(h.getBlockState(new BlockPos(12, y, 12)).isAir(), "il doit avoir repris tout son pilier (" + e + ")");
            h.assertTrue(c.compter(Actions.Filtre.de("minecraft:cobblestone")) == 16, "sans perdre une pierre (" + e + ")");
            c.discard();
        });
    }

    // --- Les blocs de son pilier repris d'office (plan A3) : seulement les siens, jamais sous quelqu'un ----------

    /** Un sol de pierre (y 1 à 3) et un ouvrier qui attend là (il ne part nulle part de lui-même), en (x, y, z) exactement. */
    static Travailleur immobile(GameTestHelper h, double x, double y, double z) {
        for (int i = 0; i < 24; i++) for (int k = 0; k < 24; k++) for (int j = 1; j <= 3; j++) h.setBlock(i, j, k, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 20, 4, 20, ItemStack.EMPTY, new BlockPos(20, 4, 20));
        t.mode = Travailleur.Mode.ATTENDRE;
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        t.moveTo(p.x, p.y, p.z);
        return t;
    }

    /** Une colonne de ce bloc (y 4 à 7) en (x, z), notée comme le pilier qu'il vient de poser. */
    static void pilierDe(GameTestHelper h, Travailleur t, int x, int z, net.minecraft.world.level.block.Block bloc) {
        for (int y = 4; y <= 7; y++) {
            h.setBlock(x, y, z, bloc);
            t.pilierPose.add(new Itineraire.PoseDePilier(h.absolutePos(new BlockPos(x, y, z)), bloc, h.getLevel().dimension(), h.getLevel().getGameTime()));
        }
    }

    static int dansColonne(GameTestHelper h, int x, int z, net.minecraft.world.level.block.Block bloc) {
        int n = 0;
        for (int y = 4; y <= 7; y++) if (h.getBlockState(new BlockPos(x, y, z)).is(bloc)) n++;
        return n;
    }

    /** Quelqu'un (ici un mannequin) se tient sur son pilier : il n'en retire aucun bloc, même d'office. Avant : tout sauf le haut. */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void pilierPorteurGardeToutDessous(GameTestHelper h) {
        Travailleur t = immobile(h, 12.5, 4, 12.5);
        pilierDe(h, t, 5, 5, Blocks.DIRT);
        h.spawn(net.minecraft.world.entity.EntityType.ARMOR_STAND, new Vec3(5.5, 8, 5.5));
        h.runAtTickTime(h.getTick() + 5, () -> {
            Itineraire.retirerPilier(t, false, false);
            Itineraire.retirerPilier(t, true, false);
            int n = dansColonne(h, 5, 5, Blocks.DIRT);
            t.discard();
            h.assertTrue(n == 4, "le pilier qui porte quelqu'un doit rester entier (" + n + " terres)");
            h.succeed();
        });
    }

    /** Il se tient sur le bord, sa boîte à cheval sur la colonne de son pilier (ses pieds comptés sur la case d'à côté) : il n'y touche pas. */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void pilierSousSaBoiteGarde(GameTestHelper h) {
        Travailleur t = immobile(h, 6.2, 8, 5.5);
        h.setBlock(6, 7, 5, Blocks.STONE); // le bord où il se tient
        pilierDe(h, t, 5, 5, Blocks.DIRT);
        h.runAtTickTime(h.getTick() + 5, () -> {
            Vec3 p = h.absoluteVec(new Vec3(6.2, 8, 5.5));
            t.moveTo(p.x, p.y, p.z);
            Itineraire.retirerPilier(t, false, false);
            int n = dansColonne(h, 5, 5, Blocks.DIRT);
            t.discard();
            h.assertTrue(n == 4, "le pilier sous lui doit rester (" + n + " terres)");
            h.succeed();
        });
    }

    /** Un bloc de son pilier remplacé par une pierre (pas la sienne) reste ; sa terre devenue herbe est bien reprise (en terre). */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void pilierNeReprendQueSesBlocs(GameTestHelper h) {
        Travailleur t = immobile(h, 12.5, 4, 12.5);
        pilierDe(h, t, 5, 5, Blocks.DIRT);
        h.setBlock(5, 7, 5, Blocks.COBBLESTONE);
        pilierDe(h, t, 8, 5, Blocks.DIRT);
        for (int y = 4; y <= 7; y++) h.setBlock(8, y, 5, Blocks.GRASS_BLOCK);
        h.runAtTickTime(h.getTick() + 5, () -> {
            Itineraire.retirerPilier(t, false, false);
            boolean pierre = h.getBlockState(new BlockPos(5, 7, 5)).is(Blocks.COBBLESTONE);
            int herbe = 4 - dansColonne(h, 8, 5, Blocks.AIR), terre = t.compter(Actions.Filtre.de("minecraft:dirt"));
            t.discard();
            h.assertTrue(pierre, "la pierre posée par un autre doit rester");
            h.assertTrue(herbe == 0 && terre == 7, "sa terre devenue herbe doit être reprise (" + herbe + " restent, " + terre + " terres dans son sac)");
            h.succeed();
        });
    }

    /** Un pilier à plus de 16 blocs de lui : il l'oublie, sans y toucher. */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void pilierLointainOublie(GameTestHelper h) {
        Travailleur t = immobile(h, 22.5, 4, 22.5);
        pilierDe(h, t, 1, 1, Blocks.DIRT);
        h.runAtTickTime(h.getTick() + 5, () -> {
            Itineraire.retirerPilier(t, true, false);
            int n = dansColonne(h, 1, 1, Blocks.DIRT), suivis = t.pilierPose.size();
            t.discard();
            h.assertTrue(n == 4 && suivis == 0, "un pilier lointain est oublié, pas cassé (" + n + " terres, " + suivis + " suivies)");
            h.succeed();
        });
    }

    /** Son sac est plein : le bloc reste en place, rien ne tombe par terre. */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void pilierSacPleinResteEnPlace(GameTestHelper h) {
        Travailleur t = immobile(h, 12.5, 4, 12.5);
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) t.inventaire.setItem(i, new ItemStack(Items.STONE, 64));
        pilierDe(h, t, 5, 5, Blocks.DIRT);
        h.runAtTickTime(h.getTick() + 5, () -> {
            Itineraire.retirerPilier(t, true, false);
            int n = dansColonne(h, 5, 5, Blocks.DIRT);
            int parTerre = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(30)).size();
            t.discard();
            h.assertTrue(n == 4 && parTerre == 0, "sac plein : le pilier reste, rien par terre (" + n + " terres, " + parTerre + " objets au sol)");
            h.succeed();
        });
    }

    /** Il meurt en haut de son pilier : le pilier est repris dans ses affaires, et le coffre est posé au sol, pas en l'air. */
    @GameTest(template = "grand", timeoutTicks = 100, skyAccess = true)
    public static void mortSurSonPilierTombeAuSol(GameTestHelper h) {
        Travailleur t = immobile(h, 12.5, 8, 12.5);
        pilierDe(h, t, 12, 12, Blocks.DIRT);
        h.runAtTickTime(h.getTick() + 5, () -> {
            Vec3 p = h.absoluteVec(new Vec3(12.5, 8, 12.5));
            t.moveTo(p.x, p.y, p.z);
            t.dropAllDeathLoot(h.getLevel(), t.damageSources().generic());
            t.discard();
            BlockPos sol = new BlockPos(12, 4, 12);
            h.assertTrue(h.getBlockState(sol).is(Blocks.CHEST), "le coffre doit être au sol (" + h.getBlockState(sol) + ", au-dessus : " + h.getBlockState(new BlockPos(12, 8, 12)) + ")");
            int terre = 0;
            if (h.getLevel().getBlockEntity(h.absolutePos(sol)) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c)
                for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.DIRT)) terre += c.getItem(i).getCount();
            h.assertTrue(terre == 4, "la terre du pilier doit être dans le coffre (" + terre + ")");
            for (int y = 5; y <= 8; y++) h.assertTrue(h.getBlockState(new BlockPos(12, y, 12)).isAir(), "plus rien du pilier au-dessus du coffre");
            h.succeed();
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
     * Pareil, mais de la pierre taillée en poche, toujours sans pioche : posée, elle ne se reprendrait pas. Il ne monte pas
     * dessus (il creuse comme avant) : pas de pilier laissé, pas une pierre perdue.
     */
    @GameTest(template = "grand", timeoutTicks = 400, skyAccess = true)
    public static void neMontePasSurDesBlocsPerdus(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++)
            if (x != 12 || z != 12 || y < 4) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 7, 4));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(390, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            for (int y = 4; y <= 6; y++)
                h.assertFalse(h.getBlockState(new BlockPos(12, y, 12)).is(Blocks.COBBLESTONE), "il ne doit pas poser de pierre qu'il ne reprendrait pas (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:cobblestone")) == 16, "il ne doit perdre aucune pierre (" + e + ")");
            t.discard();
            h.succeed();
        });
    }

    /**
     * Le puits du plan A3 : de la pierre naturelle pleine, sauf une colonne d'air de 6 blocs en (12, 4..9, 12), fermée de
     * tous côtés ; en haut, du blé de part et d'autre (sur de la terre labourée) et une torche à deux pas. Au fond, un
     * mineur sans pioche ni bloc à poser, un seau d'eau en poche, son bureau en haut, en route vers lui.
     */
    static Travailleur puitsAuSeau(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 9; y++)
            if (x != 12 || z != 12 || y < 4) h.setBlock(x, y, z, Blocks.STONE);
        for (int x : new int[] {11, 13}) { h.setBlock(x, 9, 12, Blocks.FARMLAND); h.setBlock(x, 10, 12, Blocks.WHEAT); }
        h.setBlock(12, 10, 14, Blocks.TORCH);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.WATER_BUCKET), new BlockPos(12, 10, 4));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        return t;
    }

    /** Les cases d'eau autour du puits, de son fond à deux blocs au-dessus du bord. */
    static int eauDuPuits(GameTestHelper h) {
        int eau = 0;
        for (int x = 9; x <= 15; x++) for (int z = 9; z <= 15; z++) for (int y = 4; y <= 11; y++)
            if (!h.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) eau++;
        return eau;
    }

    /**
     * Plan A3 (montagne) : au fond d'un puits de pierre naturelle de 6 blocs, sans pioche ni bloc à poser, un seau d'eau en
     * poche, son bureau en haut. Avant, il creusait la pierre à mains nues. Maintenant il monte à la nage par une colonne
     * d'eau, sort en haut et reprend l'eau : son seau est plein à la fin, et il ne reste pas une goutte dans le puits. L'eau
     * s'arrête un bloc sous le bord : elle ne coule pas dessus, le blé et la torche du bord sont toujours là.
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true)
    public static void monteParUneColonneDEau(GameTestHelper h) {
        Travailleur t = puitsAuSeau(h);
        h.succeedWhen(() -> {
            int pierres = 0, eau = eauDuPuits(h);
            for (int x = 9; x <= 15; x++) for (int z = 9; z <= 15; z++) for (int y = 4; y <= 11; y++)
                if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) pierres++;
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", seaux d'eau " + t.compter(Actions.Filtre.de("minecraft:water_bucket"))
                    + ", " + pierres + " pierres, " + eau + " cases d'eau";
            h.assertTrue(h.getBlockState(new BlockPos(11, 10, 12)).is(Blocks.WHEAT) && h.getBlockState(new BlockPos(13, 10, 12)).is(Blocks.WHEAT)
                    && h.getBlockState(new BlockPos(12, 10, 14)).is(Blocks.TORCH), "l'eau ne doit pas couler sur le bord : le blé et la torche restent (" + e + ")");
            h.assertTrue(pierres == 7 * 7 * 6 - 6 - 2, "il ne doit pas creuser la pierre à mains nues (" + e + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être sorti et arrivé au bureau (" + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:water_bucket")) == 1, "il doit avoir repris l'eau dans son seau (" + e + ")");
            h.assertTrue(eau == 0, "il ne doit pas laisser d'eau (" + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 : le monde s'arrête (ou son coin se décharge) en pleine montée à la nage. Il est sauvegardé là, retiré sans
     * rien reprendre (comme au déchargement), puis relu : il reprend l'eau dans son seau peu après, le puits est sec.
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true)
    public static void reprendLEauApresUnArret(GameTestHelper h) {
        Travailleur t = puitsAuSeau(h);
        Travailleur[] copie = new Travailleur[1];
        h.succeedWhen(() -> {
            if (copie[0] == null) {
                h.assertTrue(t.eau != null && t.eau.getY() >= h.absolutePos(new BlockPos(12, 6, 12)).getY(), "en attente de la montée (" + t.diag + ")");
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                t.saveWithoutId(tag);
                t.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK); // sans passer par remove()
                Travailleur c = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
                c.load(tag);
                c.setUUID(java.util.UUID.randomUUID());
                h.assertTrue(h.getLevel().addFreshEntity(c), "relu dans le monde");
                copie[0] = c;
                h.assertTrue(false, "relu, en attente");
            }
            Travailleur c = copie[0];
            int eau = eauDuPuits(h);
            String e = c.diag + ", seaux d'eau " + c.compter(Actions.Filtre.de("minecraft:water_bucket")) + ", " + eau + " cases d'eau";
            h.assertTrue(c.compter(Actions.Filtre.de("minecraft:water_bucket")) == 1, "il doit avoir repris l'eau dans son seau (" + e + ")");
            h.assertTrue(eau == 0, "il ne doit pas laisser d'eau (" + e + ")");
            c.discard();
        });
    }

    /**
     * Sur un îlot de pierre de 3 x 3, entouré d'une douve vide d'un bloc de large et de 10 de profondeur (trop haut pour
     * sauter), de la pierre en poche et une pioche, son bureau de l'autre côté : il pose un bloc de pont et passe.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void passeEnPosantUnPont(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++)
            if (Math.max(Math.abs(x - 12), Math.abs(z - 12)) != 2) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 11, 12, new ItemStack(Items.COBBLESTONE, 16), new BlockPos(12, 11, 3));
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE)); // sans pioche, il ne poserait pas une pierre qu'il ne reprendrait pas
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", pierres " + t.compter(Actions.Filtre.de("minecraft:cobblestone"));
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit avoir passé la douve et rejoint son bureau (" + e + ")");
            // Il a une pioche : passé de l'autre côté, il reprend le bloc du pont (plan A3 : rien de perdu).
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:cobblestone")) == 16, "il doit avoir repris le bloc du pont (" + e + ")");
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

    /**
     * Plan A3 (le joueur fantôme : 3 minutes d'allers-retours entre deux cases voisines, « monte en … ») : enfermé dans un
     * enclos de planches de deux cases, sans bloc ni seau, son bureau dehors. Le pas de côté vers l'autre case, déjà libre,
     * n'est pas une solution : il ne le rapproche pas du but. Avant, il faisait ce pas, revenait, et recommençait sans fin,
     * sans jamais se dire coincé. Maintenant il se dit coincé.
     */
    @GameTest(template = "grand", timeoutTicks = 700, skyAccess = true)
    public static void pasDAllersRetoursEntreDeuxCases(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        for (int x = 11; x <= 14; x++) for (int z = 11; z <= 13; z++)
            if (z != 12 || x == 11 || x == 14) for (int y = 4; y <= 7; y++) h.setBlock(x, y, z, Blocks.OAK_PLANKS);
        Travailleur t = TestsJeu.employe(h, "mineur", h.absolutePos(new BlockPos(12, 4, 2)), h.absoluteVec(new Vec3(12.5, 4, 12.5)), new ItemStack(Items.BREAD));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        var fins = fins(h, t);
        int[] pas = {0};
        BlockPos[] avant = {t.blockPosition()};
        h.onEachTick(() -> { if (!t.blockPosition().equals(avant[0])) { pas[0]++; avant[0] = t.blockPosition(); } });
        h.runAtTickTime(h.getTick() + 600, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", fins " + fins + ", " + pas[0] + " changements de case";
            t.discard();
            h.assertTrue(fins.contains("aller:bloque"), "il doit se dire coincé (" + e + ")");
            h.assertTrue(pas[0] <= 4, "il ne doit pas aller et venir entre les deux cases (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Plan A3 (montagne) : au fond d'une cuvette de pierre naturelle (16 x 10 blocs, 6 de haut), une pioche et de la terre en
     * poche, son bureau en haut à une quinzaine de blocs. Avant, la montée sur blocs n'était essayée que sans pioche : il
     * creusait un escalier dans la pierre. Maintenant, le but étant plus haut, il va au pied de la paroi, monte sur sa
     * terre, la reprend en haut, et rejoint son bureau sans toucher à la pierre ni laisser de bloc.
     */
    @GameTest(template = "grand", timeoutTicks = 1600, skyAccess = true)
    public static void grimpeLaFalaiseMemeAvecUnePioche(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 9; y++)
            if (x < 4 || x > 19 || z < 12 || z > 21 || y < 4) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 17, new ItemStack(Items.DIRT, 16), new BlockPos(12, 10, 3));
        t.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            int pierres = 0, terre = 0;
            for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 4; y <= 9; y++) {
                var st = h.getBlockState(new BlockPos(x, y, z));
                if (st.is(Blocks.STONE)) pierres++;
                if (st.is(Blocks.DIRT)) terre++;
            }
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", terre " + t.compter(Actions.Filtre.de("minecraft:dirt")) + ", " + pierres + " pierres";
            h.assertTrue(pierres == 24 * 24 * 6 - 16 * 10 * 6, "il ne doit pas creuser d'escalier dans la pierre (" + e + ")");
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être monté jusqu'à son bureau (" + e + ")");
            h.assertTrue(terre == 0 && t.compter(Actions.Filtre.de("minecraft:dirt")) == 16, "il doit avoir repris toute sa terre (" + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 (la descente) : en haut d'une falaise de pierre naturelle de 10 blocs, sans chemin pour descendre, son bureau
     * en bas. Avant, il se disait « inaccessible » sur la corniche jusqu'au retour forcé au poste. Maintenant, il creuse un
     * escalier qui descend dans la paroi et rejoint son bureau sans tomber de plus de 3 blocs ni se blesser.
     */
    @GameTest(template = "grand", timeoutTicks = 2000, skyAccess = true)
    public static void descendDeLaFalaise(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 13; y++)
            if (z <= 9 || y <= 3) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 14, 5, new ItemStack(Items.IRON_PICKAXE), new BlockPos(12, 4, 18));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        double[] chute = {0};
        double[] haut = {t.getY()};
        h.onEachTick(() -> {
            if (t.onGround() || t.isInWater()) { chute[0] = Math.max(chute[0], haut[0] - t.getY()); haut[0] = t.getY(); }
            else haut[0] = Math.max(haut[0], t.getY());
        });
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag + ", vie " + t.getHealth() + ", chute " + chute[0];
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être descendu jusqu'à son bureau (" + e + ")");
            h.assertTrue(chute[0] <= 3.1 && t.getHealth() >= t.getMaxHealth(), "sans chute de plus de 3 blocs ni blessure (" + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 (la descente, relecture) : sur une corniche de pierre de 22 blocs de haut (plus de 20 : le chemin direct n'est
     * jamais tenté), loin des tests, son bureau en bas à une quinzaine de blocs. Avant, le trajet d'étape en cours le
     * renvoyait sur la corniche après chaque marche creusée : il errait là-haut sans fin. Maintenant, il descend jusqu'en bas.
     */
    @GameTest(template = "vide", timeoutTicks = 2400, skyAccess = true)
    public static void descendDeLaHauteCorniche(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos base = TestsJeu.sol(h, 977 * 400, 60000);
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 0; y < 22; y++) l.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        Travailleur t = TestsJeu.employe(h, "mineur", base.offset(6, 0, 26), Vec3.atBottomCenterOf(base.offset(4, 22, 6)), new ItemStack(Items.IRON_PICKAXE));
        t.forcerChunk();
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            h.assertTrue(t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) < 16, "il doit être descendu de la corniche jusqu'à son bureau (" + e + ")");
            t.discard();
            for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 0; y < 22; y++) l.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
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

    /**
     * Plan A3 (le joueur fantôme, montagne, 1.0.17) : un bûcheron sans pioche, un seau d'eau en poche, au fond d'une fosse de
     * pierre naturelle de 3 blocs, large de 2 (l'eau s'y répandrait : pas de colonne d'eau), son bureau en haut. Avant, il
     * taillait à mains nues un escalier dans la pierre (230 à 306 blocs en 2 jours). Maintenant, il ne touche pas à la pierre :
     * il se dit coincé, réclame une pioche, et garde son seau.
     */
    @GameTest(template = "grand", timeoutTicks = 500, skyAccess = true)
    public static void neTaillePasLaPierreAMainsNues(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 6; y++)
            if (x < 12 || x > 13 || z != 12 || y < 4) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 4, 12, new ItemStack(Items.WATER_BUCKET), new BlockPos(12, 7, 4));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(450, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            int pierres = 0;
            for (int x = 9; x <= 16; x++) for (int z = 9; z <= 15; z++) for (int y = 4; y <= 6; y++)
                if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) pierres++;
            t.discard();
            h.assertTrue(pierres == 8 * 7 * 3 - 6, "il ne doit pas tailler la pierre à mains nues (" + pierres + " pierres, " + e + ")");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:water_bucket")) == 1, "il doit garder son seau d'eau (" + e + ")");
            h.succeed();
        });
    }

    /**
     * Plan A3 (le joueur fantôme, montagne, 1.0.17 : 11 h au fond du trou de sa descente sans être ramené) : le temps passé à
     * creuser sans s'éloigner compte aussi comme coincé. Enfoui sous 8 blocs de pierre, une pioche de bois, son bureau en
     * haut : il creuse, mais au bout du délai (abaissé à 15 s) sans s'être éloigné de 8 blocs, il est ramené au poste.
     * Avant, le creusage ne comptait jamais : il n'était pas ramené.
     */
    @GameTest(template = "grand", timeoutTicks = 800, skyAccess = true, batch = "deblocage")
    public static void creuserSansAvancerCompte(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 13; y++)
            if (x != 12 || z != 12 || y < 4 || y > 5) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 12, 4, 12, new ItemStack(Items.WOODEN_PICKAXE), new BlockPos(12, 14, 2));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        Vec3[] avant = {t.position()};
        double[] saut = {0};
        h.onEachTick(() -> { saut[0] = Math.max(saut[0], t.position().distanceTo(avant[0])); avant[0] = t.position(); });
        h.succeedWhen(() -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            h.assertTrue(saut[0] > 4, "il doit avoir été ramené au poste (saut de " + String.format("%.1f", saut[0]) + " blocs, " + e + ")");
            t.discard();
        });
    }

    /**
     * Plan A3 (relecture des mains nues, 1.0.18) : sur un plateau de pierre naturelle, son bureau enfoui 6 blocs plus bas,
     * presque sous lui, sans pioche, sans bloc qu'il reprendrait ni seau d'eau. Avant, il creusait à mains nues un puits
     * d'un bloc dans la pierre, s'arrêtait à 8 blocs au fond, à ciel ouvert, sans pouvoir ni grimper ni recreuser : coincé
     * dans son propre trou jusqu'au retour forcé au poste. Maintenant, il ne creuse pas : rien ne lui permettrait de remonter.
     */
    @GameTest(template = "grand", timeoutTicks = 1200, skyAccess = true)
    public static void neCreusePasDePuitsSansIssue(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 12, 11, 12, ItemStack.EMPTY, new BlockPos(13, 5, 12));
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(t.bureau), 3));
        h.runAtTickTime(1100, () -> {
            String e = TestsJeu.etat(t, t.bureau) + ", " + t.diag;
            boolean issue = Itineraire.caseDeChantier(t, false, true) >= 0 || Itineraire.seauDEau(t) >= 0;
            int pierres = 0;
            for (int x = 6; x <= 18; x++) for (int z = 6; z <= 18; z++) for (int y = 6; y <= 10; y++)
                if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) pierres++;
            t.discard();
            h.assertFalse(issue, "le test veut un ouvrier sans bloc à reprendre ni seau (" + e + ")");
            h.assertTrue(pierres == 13 * 13 * 5, "il ne doit pas creuser de puits à mains nues (" + (13 * 13 * 5 - pierres) + " pierres cassées, " + e + ")");
            h.succeed();
        });
    }
}

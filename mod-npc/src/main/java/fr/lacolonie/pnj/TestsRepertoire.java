package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static fr.lacolonie.pnj.TestsIA.attendre;
import static fr.lacolonie.pnj.TestsIA.equipe;
import static fr.lacolonie.pnj.TestsIA.etapes;
import static fr.lacolonie.pnj.TestsIA.regarder;

/**
 * Les actions du répertoire, jouées pour de vrai (sans IA : l'interpréteur les reconnaît ; un test avec l'IA est dans
 * le lot « ia »). Chaque test a son coin de monde plat, loin des autres (TestsIA.equipe).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRepertoire {

    static String c(Travailleur t) { return t.consigne == null ? "aucune" : t.consigne.id; }

    static void fin(Travailleur[] e) { for (Travailleur t : e) t.discard(); }

    /** L'exemple de KOLRAX : « garde à vous », il se fige au garde-à-vous jusqu'à « repos ». */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void gardeAVousPuisRepos(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 21);
        Travailleur raoul = e[1];
        Vec3[] avant = new Vec3[1];
        etapes(h, chef, e,
                () -> { regarder(chef, raoul); raoul.parler(chef, "Garde à vous !"); },
                () -> h.assertTrue(c(raoul).equals("garde_a_vous") && raoul.geste().equals("garde_a_vous"), "garde-à-vous : " + c(raoul) + " / " + raoul.geste()),
                () -> avant[0] = raoul.position(),
                attendre(h, 80),
                () -> h.assertTrue(raoul.position().distanceTo(avant[0]) < 0.3 && raoul.geste().equals("garde_a_vous"), "il ne bouge plus : " + raoul.position().distanceTo(avant[0])),
                () -> raoul.parler(chef, "Repos !"),
                () -> h.assertTrue(raoul.consigne == null && raoul.geste().isEmpty(), "repos : " + c(raoul) + " / " + raoul.geste()),
                () -> fin(e));
    }

    /** Un salut pendant le garde-à-vous : il salue, puis reprend la posture. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void unGesteDansUnePosture(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 22);
        Travailleur raoul = e[1];
        etapes(h, chef, e,
                () -> { regarder(chef, raoul); raoul.parler(chef, "Garde à vous !"); },
                () -> h.assertTrue(c(raoul).equals("garde_a_vous"), "garde-à-vous : " + c(raoul)),
                () -> raoul.parler(chef, "Salue-moi"),
                () -> h.assertTrue(c(raoul).equals("salut_militaire") && raoul.geste().equals("salut_militaire"), "salut : " + c(raoul) + " / " + raoul.geste()),
                attendre(h, 80),
                () -> h.assertTrue(c(raoul).equals("garde_a_vous") && raoul.geste().equals("garde_a_vous"), "de retour au garde-à-vous : " + c(raoul) + " / " + raoul.geste()),
                () -> fin(e));
    }

    /** « Fige-toi » : il arrête tout jusqu'à nouvel ordre ; « au travail » l'en libère. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void figeToiPuisAuTravail(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 23);
        Travailleur bertrand = e[0];
        Vec3[] avant = new Vec3[1];
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Fige-toi !"); },
                () -> h.assertTrue(c(bertrand).equals("arret") && bertrand.action instanceof Consignes.Faire, "figé : " + c(bertrand) + " " + bertrand.action),
                () -> avant[0] = bertrand.position(),
                attendre(h, 100),
                () -> h.assertTrue(bertrand.position().distanceTo(avant[0]) < 0.3, "il ne bouge plus"),
                () -> bertrand.parler(chef, "Au travail !"),
                () -> h.assertTrue(bertrand.consigne == null && bertrand.mode == Travailleur.Mode.TRAVAIL, "au travail : " + c(bertrand) + " " + bertrand.mode),
                () -> fin(e));
    }

    /** « Mets-toi derrière moi » : il y va et y reste. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void derriereMoi(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 24);
        Travailleur odon = e[2];
        Vec3[] place = new Vec3[1];
        etapes(h, chef, e,
                () -> {
                    chef.setYRot(0); // face au sud : derrière lui, c'est au nord
                    chef.setXRot(0);
                    place[0] = chef.position().add(0, 0, -2);
                    odon.parler(chef, "Mets-toi derrière moi");
                },
                () -> h.assertTrue(c(odon).equals("derriere"), "consigne : " + c(odon)),
                () -> h.assertTrue(odon.position().distanceTo(place[0]) < 2.2, "derrière le chef : à " + odon.position().distanceTo(place[0])),
                attendre(h, 60),
                () -> h.assertTrue(odon.position().distanceTo(place[0]) < 2.2 && c(odon).equals("derriere"), "il y reste"),
                () -> fin(e));
    }

    /** « Donne-moi ton pain » : il le met dans le sac du chef. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void donneMoiTonPain(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 25);
        Travailleur odon = e[2];
        odon.inventaire.addItem(new ItemStack(Items.BREAD, 6));
        etapes(h, chef, e,
                () -> { regarder(chef, odon); odon.parler(chef, "Donne-moi ton pain"); },
                () -> h.assertTrue(chef.getInventory().countItem(Items.BREAD) == 6, "le chef a " + chef.getInventory().countItem(Items.BREAD) + " pains (" + c(odon) + ")"),
                () -> h.assertTrue(odon.compter(Actions.Filtre.de("minecraft:bread")) == 0, "Odon n'en a plus"),
                () -> fin(e));
    }

    /** « Attaque ce zombie » (en le regardant) : le guerrier va le tuer. */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void attaqueCeZombie(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 26);
        Travailleur raoul = e[1];
        raoul.inventaire.addItem(new ItemStack(Items.IRON_SWORD));
        raoul.mode = Travailleur.Mode.ATTENDRE; // sans l'ordre, il resterait là (le zombie est hors de sa portée de garde)
        Zombie[] z = new Zombie[1];
        etapes(h, chef, e,
                () -> {
                    z[0] = EntityType.ZOMBIE.create(h.getLevel());
                    Vec3 p = chef.position().add(0, 0, 16);
                    z[0].moveTo(p.x, p.y, p.z, 180, 0);
                    z[0].setNoAi(true); // il attend sagement qu'on vienne le chercher
                    z[0].setPersistenceRequired();
                    h.getLevel().addFreshEntity(z[0]);
                },
                () -> h.assertTrue(Monde.hostile(z[0]), "zombie prêt"),
                () -> {
                    Vec3 d = z[0].getEyePosition().subtract(chef.getEyePosition());
                    chef.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
                    chef.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
                    raoul.parler(chef, "Attaque ce zombie !");
                },
                () -> h.assertTrue(c(raoul).equals("attaquer_la") || !z[0].isAlive(), "consigne : " + c(raoul)),
                () -> h.assertTrue(!z[0].isAlive(), "le zombie est mort (vie " + z[0].getHealth() + ", " + c(raoul) + ")"),
                () -> fin(e));
    }

    /** « Mine ce bloc » (en le regardant) : le mineur le casse et le ramasse. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void mineCeBloc(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 27);
        Travailleur bertrand = e[0];
        bertrand.inventaire.addItem(new ItemStack(Items.IRON_PICKAXE));
        BlockPos[] b = new BlockPos[1];
        etapes(h, chef, e,
                () -> {
                    b[0] = BlockPos.containing(chef.position().add(0, 0, 4));
                    h.getLevel().setBlockAndUpdate(b[0], Blocks.STONE.defaultBlockState());
                    Vec3 d = Vec3.atCenterOf(b[0]).subtract(chef.getEyePosition());
                    chef.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
                    chef.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
                    bertrand.parler(chef, "Mine ce bloc");
                },
                () -> h.assertTrue(h.getLevel().getBlockState(b[0]).isAir(), "le bloc est cassé (" + c(bertrand) + ")"),
                () -> h.assertTrue(bertrand.compter(Actions.Filtre.de("minecraft:cobblestone")) >= 1, "et ramassé"),
                () -> fin(e));
    }

    /** « Les gars, en rang ! » au micro : chacun prend une place différente face au chef. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void enRang(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 28);
        etapes(h, chef, e,
                () -> { chef.setYRot(0); chef.setXRot(0); Dialogue.parler(chef, "Les gars, en rang !", null, true); },
                () -> { for (Travailleur t : e) h.assertTrue(c(t).equals("en_rang"), t.prenom + " : " + c(t)); },
                () -> {
                    for (Travailleur t : e) h.assertTrue(t.consigne.lieu != null && t.position().distanceTo(Vec3.atBottomCenterOf(t.consigne.lieu)) < 1.5,
                            t.prenom + " à sa place : " + t.position().distanceTo(Vec3.atBottomCenterOf(t.consigne.lieu)));
                },
                () -> {
                    for (int i = 0; i < e.length; i++) for (int j = i + 1; j < e.length; j++)
                        h.assertTrue(!e[i].consigne.lieu.equals(e[j].consigne.lieu), "des places différentes");
                    h.assertTrue(e[1].geste().equals("garde_a_vous"), "au garde-à-vous dans le rang : " + e[1].geste());
                },
                () -> fin(e));
    }

    /** « Tais-toi » : plus un mot ; « tu peux parler » : il reparle. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void taisToi(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 29);
        Travailleur bertrand = e[0];
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Tais-toi"); },
                () -> h.assertTrue(bertrand.muet(), "il se tait"),
                () -> bertrand.parler(chef, "Tu peux parler"),
                () -> h.assertTrue(!bertrand.muet(), "il reparle"),
                () -> fin(e));
    }

    /** Ce qu'il sait : l'heure, et où est le poste (il le montre du doigt). */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void questions(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 30);
        Travailleur odon = e[2];
        etapes(h, chef, e,
                () -> { regarder(chef, odon); odon.parler(chef, "Quelle heure est-il ?"); },
                () -> h.assertTrue(IA.echange(odon).contains("Il est"), "l'heure : " + IA.echange(odon)),
                () -> odon.parler(chef, "Où est le poste ?"),
                () -> h.assertTrue(IA.echange(odon).contains("Le poste est"), "le poste : " + IA.echange(odon)),
                () -> h.assertTrue(odon.geste().equals("pointer"), "il le montre du doigt : " + odon.geste()),
                () -> fin(e));
    }

    /** « Ouvre la porte » : il va à la porte la plus proche et l'ouvre. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void ouvreLaPorte(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 31);
        Travailleur bertrand = e[0];
        BlockPos[] p = new BlockPos[1];
        etapes(h, chef, e,
                () -> {
                    p[0] = BlockPos.containing(bertrand.position().add(0, 0, -4));
                    h.getLevel().setBlockAndUpdate(p[0], Blocks.OAK_DOOR.defaultBlockState());
                    h.getLevel().setBlockAndUpdate(p[0].above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                    regarder(chef, bertrand);
                    bertrand.parler(chef, "Ouvre la porte");
                },
                () -> h.assertTrue(h.getLevel().getBlockState(p[0]).getValue(DoorBlock.OPEN), "la porte est ouverte (" + c(bertrand) + ")"),
                attendre(h, 40),
                // Ouverte pour de bon (un ouvrier qui passe par une porte la referme derrière lui).
                () -> h.assertTrue(h.getLevel().getBlockState(p[0]).getValue(DoorBlock.OPEN), "elle reste ouverte"),
                () -> fin(e));
    }

    /** « Dis à Odon que le repas est prêt » : Bertrand va le lui dire. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void porterUnMessage(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 32);
        Travailleur bertrand = e[0], odon = e[2];
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Dis à Odon que le repas est prêt"); },
                () -> h.assertTrue(c(bertrand).equals("message") || IA.echange(odon).contains("repas"), "consigne : " + c(bertrand)),
                () -> h.assertTrue(IA.echange(odon).contains("le repas est prêt"), "Odon l'a appris : " + IA.echange(odon)),
                () -> fin(e));
    }

    /**
     * « Dis à Odon que… » alors qu'Odon est parti à 15 blocs d'ici : Bertrand le hèle, Odon l'attend en route, l'écoute,
     * puis reprend sa marche. Avant, Bertrand le suivait sans le rattraper (ils vont aussi vite l'un que l'autre) : le
     * message n'arrivait qu'une fois Odon arrêté au bout de sa route, ou jamais s'il allait loin.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void messageAUnOuvrierQuiPart(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 35);
        Travailleur bertrand = e[0], odon = e[2];
        Vec3 depart = odon.position(), but = depart.add(0, 0, 15); // dans les tronçons qu'il garde actifs autour de lui
        double[] auMessage = {-1}; // à combien de blocs du bout il était quand il l'a appris
        boolean[] auBout = {false, false}; // il est arrivé au bout ; il y était déjà arrivé quand il l'a appris
        h.onEachTick(() -> {
            if (odon.position().distanceTo(but) < 2) auBout[0] = true;
            if (auMessage[0] < 0 && IA.echange(odon).contains("le repas est prêt")) { auMessage[0] = odon.position().distanceTo(but); auBout[1] = auBout[0]; }
        });
        etapes(h, chef, e,
                () -> Cerveau.lancer(odon, new Actions.Aller(but, 1)),
                () -> h.assertTrue(odon.position().distanceTo(depart) >= 3, "Odon doit être en route"),
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Dis à Odon que le repas est prêt"); },
                () -> h.assertTrue(c(bertrand).equals("message"), "consigne : " + c(bertrand)),
                () -> h.assertTrue(auMessage[0] >= 0, "Odon doit l'apprendre (Bertrand à " + (int) bertrand.distanceTo(odon) + " blocs de lui)"),
                () -> h.assertTrue(!auBout[1] && auMessage[0] > 5, "Odon doit l'apprendre en route, avant le bout (il l'a appris à " + (int) auMessage[0]
                        + " blocs du bout, " + (auBout[1] ? "après y être arrivé" : "sans y être encore arrivé") + ")"),
                () -> h.assertTrue(odon.position().distanceTo(but) < 3, "puis il reprend sa route jusqu'au bout : "
                        + (odon.action == null ? "-" : odon.action.nom) + ", à " + (int) odon.position().distanceTo(but) + " blocs du bout"),
                () -> fin(e));
    }

    /**
     * Le messager ne hèle pas l'ouvrier d'un autre chef (sinon, on pourrait figer à volonté les ouvriers d'un autre) :
     * il va jusqu'à lui. Ici, Odon change de chef pendant que Bertrand vient lui parler.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void messagerNeFigePasLOuvrierDUnAutre(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 36);
        Travailleur bertrand = e[0], odon = e[2];
        Vec3 depart = odon.position(), but = depart.add(0, 0, 15);
        int[] ecoute = {-1}; // le plus long arrêt d'Odon depuis son changement de chef
        h.onEachTick(() -> { if (ecoute[0] >= 0) ecoute[0] = Math.max(ecoute[0], odon.ecouteTicks); });
        etapes(h, chef, e,
                () -> Cerveau.lancer(odon, new Actions.Aller(but, 1)),
                () -> h.assertTrue(odon.position().distanceTo(depart) >= 3, "Odon doit être en route"),
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Dis à Odon que le repas est prêt"); },
                () -> h.assertTrue(c(bertrand).equals("message"), "consigne : " + c(bertrand)),
                () -> { odon.chef = java.util.UUID.randomUUID(); odon.ecouteTicks = 0; ecoute[0] = 0; },
                attendre(h, 40),
                () -> h.assertTrue(ecoute[0] == 0, "Odon ne doit pas être hélé par l'ouvrier d'un autre chef (arrêté " + ecoute[0] + " ticks)"),
                () -> fin(e));
    }

    /** Le chef regarde un bain de lave et dit « va là-bas » : il refuse (cahier 8.5), et dit pourquoi. Avant, il y allait. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void refuseDAllerDansLaLave(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 37);
        Travailleur raoul = e[1];
        BlockPos lave = TestsJeu.sol(h, 37 * 400, 60000).offset(0, -1, 9);
        for (Travailleur t : e) t.mode = Travailleur.Mode.ATTENDRE; // personne ne s'approche du bain
        // Un grand bain (7 x 7, à 6 blocs du chef) : le regard du chef traverse la lave (il ne la « voit » pas) et touche
        // le fond, encore sous le bain.
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) h.getLevel().setBlockAndUpdate(lave.offset(dx, 0, dz), Blocks.LAVA.defaultBlockState());
        etapes(h, chef, e,
                () -> {
                    regarderPoint(chef, new Vec3(lave.getX() + 0.5, lave.getY() + 1, lave.getZ() - 2.7)); // la surface, près du bord
                    BlockPos vu = Consignes.regardBloc(chef, 48);
                    h.assertTrue(vu != null && h.getLevel().getFluidState(vu.above()).is(net.minecraft.tags.FluidTags.LAVA), "le chef doit regarder le fond du bain : " + vu);
                    raoul.parler(chef, "Raoul, va là-bas !");
                },
                () -> h.assertTrue(raoul.consigne == null, "il doit refuser d'y aller : " + c(raoul) + " / " + IA.echange(raoul)),
                () -> h.assertTrue(IA.echange(raoul).contains("mort"), "il doit dire pourquoi : " + IA.echange(raoul)),
                () -> fin(e));
    }

    /** Le chef regarde un point (sans paquet envoyé : le faux joueur n'a pas de vraie connexion). */
    static void regarderPoint(ServerPlayer chef, Vec3 p) {
        Vec3 d = p.subtract(chef.getEyePosition());
        chef.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        chef.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
        chef.setYHeadRot(chef.getYRot());
    }

    /** Un message à porter ne dure pas sans fin : au bout de 2 minutes sans avoir rattrapé l'ouvrier, il renonce. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void messagerRenonceAuBoutDeDeuxMinutes(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 38);
        Travailleur bertrand = e[0];
        boolean[] renonce = {false};
        h.onEachTick(() -> { if (bertrand.resultat.equals("trop_long")) renonce[0] = true; });
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Dis à Odon que le repas est prêt"); },
                () -> h.assertTrue(bertrand.action instanceof Consignes.Faire f && f.c.id.equals("message"), "il doit porter le message : " + c(bertrand)),
                () -> bertrand.action.age = 20 * 120 + 1, // deux minutes ont passé
                () -> h.assertTrue(renonce[0] && bertrand.consigne == null, "il doit renoncer : " + c(bertrand) + ", résultat " + bertrand.resultat),
                () -> fin(e));
    }

    /**
     * Le messager ne peut pas rejoindre Odon (il est enfermé dans un enclos) : Odon, hélé, ne reste pas planté là plus de
     * quelques secondes. Avant, il restait figé tant que le messager le voyait.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void heleNeFigePasLongtemps(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 39);
        Travailleur bertrand = e[0], odon = e[2];
        odon.mode = Travailleur.Mode.ATTENDRE;
        BlockPos b = odon.blockPosition().offset(0, 0, -9);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) h.getLevel().setBlockAndUpdate(b.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
        Vec3 enclos = Vec3.atBottomCenterOf(b);
        bertrand.moveTo(enclos.x, enclos.y, enclos.z);
        int[] fige = {0, 0}; // l'arrêt en cours d'Odon, le plus long
        h.onEachTick(() -> {
            fige[0] = odon.ecouteTicks > 0 ? fige[0] + 1 : 0;
            fige[1] = Math.max(fige[1], fige[0]);
        });
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Dis à Odon que le repas est prêt"); },
                () -> h.assertTrue(c(bertrand).equals("message"), "consigne : " + c(bertrand)),
                () -> h.assertTrue(fige[1] > 0, "Odon doit d'abord être hélé"),
                attendre(h, 300),
                () -> h.assertTrue(fige[1] <= 170, "Odon ne doit pas rester figé plus de quelques secondes (" + fige[1] + " ticks)"),
                () -> fin(e));
    }

    /** « Danse ! » : il danse dix secondes, puis reprend son travail tout seul. */
    @GameTest(template = "vide", timeoutTicks = 800)
    public static void danseUnMoment(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 33);
        Travailleur odon = e[2];
        etapes(h, chef, e,
                () -> { regarder(chef, odon); odon.parler(chef, "Danse !"); },
                () -> h.assertTrue(odon.geste().equals("danser"), "il danse : " + odon.geste()),
                attendre(h, 230),
                () -> h.assertTrue(odon.consigne == null && !odon.geste().equals("danser"), "la danse est finie : " + c(odon)),
                () -> fin(e));
    }

    /**
     * Lot 3 : une phrase que personne ne comprend (sans IA) va au journal des phrases non comprises, motif « rien »,
     * avec le métier visé ; une phrase comprise n'y va pas.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void phraseIncompriseNotee(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 40);
        Travailleur raoul = e[1];
        // « Fais tourner la lune à l'envers » est compris par le répertoire (action « lune ») : une autre phrase absurde.
        String absurde = "Fais pousser des ailes au cochon !";
        h.assertFalse(IA.conversationDisponible(), "ce test se joue sans IA");
        h.assertTrue(Ordres.lire(absurde, "guerrier").vide(), "l'interpréteur ne doit rien y comprendre");
        int[] avant = {TestsIA.fois(absurde, Incompris.RIEN), TestsIA.fois("Suis-moi", Incompris.RIEN)};
        etapes(h, chef, e,
                () -> { regarder(chef, raoul); raoul.parler(chef, absurde); raoul.parler(chef, "Suis-moi"); },
                () -> {
                    h.assertTrue(TestsIA.fois(absurde, Incompris.RIEN) == avant[0] + 1, "notée une fois de plus, motif rien : " + Incompris.JOURNAL.plusFrequentes(3));
                    h.assertTrue(Incompris.JOURNAL.plusFrequentes(Incompris.MAX).stream().anyMatch(x -> "fais pousser des ailes au cochon".equals(x.get("phrase_normalisee"))
                            && ((java.util.List<?>) x.get("metiers")).contains("guerrier")), "avec le métier de Raoul");
                    h.assertTrue(TestsIA.fois("Suis-moi", Incompris.RIEN) == avant[1] && TestsIA.fois("Suis-moi", Incompris.IA_SEULE) == 0, "« suis-moi » est compris : pas noté");
                    h.assertTrue(raoul.mode == Travailleur.Mode.SUIVRE, "et il suit toujours : " + raoul.mode);
                },
                () -> fin(e));
    }
}

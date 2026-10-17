package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 5, deuil et enterrement (cahier 14.13, {@link Deuil}) : les compagnons apprennent la mort et en reparlent, le chef
 * absent reçoit une lettre, l'enterrement rassemble les ouvriers proches et leur rend la moitié du moral perdu, un ouvrier
 * à terre n'y vient pas. Coins 246 à 251 ; le lot « deuil » raccourcit la cérémonie à 2 s.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsDeuil {
    static int duree;

    @BeforeBatch(batch = "deuil")
    public static void avant(ServerLevel l) {
        duree = Reglages.actuel.deuil_secondes_enterrement;
        Reglages.actuel.deuil_secondes_enterrement = 2;
    }

    @AfterBatch(batch = "deuil")
    public static void apres(ServerLevel l) { Reglages.actuel.deuil_secondes_enterrement = duree; }

    /** Il a dit une réplique de la section {@code cle}. */
    static boolean dit(Travailleur t, String cle) { return t.paroles.stream().anyMatch(p -> Vie.vient(cle, p)); }

    static String paroles(Travailleur[] e) { return e[1].prenom + " " + e[1].paroles + " / " + e[2].prenom + " " + e[2].paroles; }

    /**
     * Bertrand meurt devant le chef : ses compagnons disent la nouvelle (deuil_apprend), puis l'un d'eux en reparle de
     * lui-même (deuil_souvenir, avant toute autre remarque). Dans le lot « remarques » : ailleurs, pas de remarques en test.
     */
    @GameTest(template = "vide", timeoutTicks = 2600, batch = "remarques")
    public static void compagnonsEnParlent(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 246);
        int[] moral = new int[3];
        Essais.etapes(h, chef, e,
                () -> {
                    moral[1] = e[1].moral;
                    e[0].kill();
                },
                () -> h.assertTrue(e[1].moral <= moral[1] - 10, "le coup du deuil : " + moral[1] + " → " + e[1].moral),
                () -> h.assertTrue(dit(e[1], "deuil_apprend") != dit(e[2], "deuil_apprend") && !e[1].memoire.contains("deuil_apprend")
                        && !e[2].memoire.contains("deuil_apprend"), "l'un d'eux dit la nouvelle, l'autre l'a entendue (" + paroles(e) + ")"),
                () -> h.assertTrue(dit(e[1], Deuil.SOUVENIR) || dit(e[2], Deuil.SOUVENIR), "l'un d'eux en reparle (" + paroles(e) + ")"),
                () -> TestsOrdres.fin(h, e));
    }

    /**
     * Bertrand meurt à 12 blocs d'eux ; le chef dit à Raoul « on va enterrer Bertrand » : Raoul et Odon vont à la tombe,
     * l'un dit quelques mots, puis le moral de chacun remonte de la moitié du coup du deuil, et Bertrand est enterré.
     */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "deuil")
    public static void enterrementApaise(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 247);
        Vec3[] tombe = {null};
        int[] apres = new int[3];
        boolean[] proches = new boolean[3];
        h.onEachTick(() -> {
            for (int i = 1; i < 3; i++) if (tombe[0] != null && e[i].position().distanceToSqr(tombe[0]) <= 3.5 * 3.5) proches[i] = true;
        });
        Essais.etapes(h, chef, e,
                () -> e[0].moveTo(e[0].getX() + 12, e[0].getY(), e[0].getZ()),
                () -> {
                    tombe[0] = e[0].position();
                    e[0].kill();
                },
                () -> {
                    for (int i = 1; i < 3; i++) apres[i] = e[i].moral;
                    Essais.regarder(chef, e[1]);
                    e[1].parler(chef, "Raoul, on va enterrer Bertrand.");
                    h.assertTrue(e[1].action instanceof Deuil.Enterrement && e[2].action instanceof Deuil.Enterrement,
                            "tous deux vont à la tombe (" + TestsRepertoire.c(e[1]) + " / " + TestsRepertoire.c(e[2]) + ")");
                },
                () -> h.assertTrue(proches[1] && proches[2], "ils se tiennent autour de la tombe (" + TestsJeu.etat(e[1], e[1].bureau) + " / " + TestsJeu.etat(e[2], e[2].bureau) + ")"),
                () -> {
                    var m = Courrier.de(h.getLevel().getServer()).effectifs.get(chef.getUUID()).get(e[0].getUUID());
                    h.assertTrue(m.enterre, "Bertrand est enterré");
                    h.assertFalse(e[1].action instanceof Deuil.Enterrement || e[2].action instanceof Deuil.Enterrement, "ils reprennent le travail");
                    h.assertTrue(dit(e[1], "enterrement_mots") || dit(e[2], "enterrement_mots"), "quelques mots devant la tombe (" + paroles(e) + ")");
                    h.assertTrue(dit(e[1], "enterrement_apres") && dit(e[2], "enterrement_apres"), "ils le disent en repartant (" + paroles(e) + ")");
                    for (int i = 1; i < 3; i++) h.assertTrue(e[i].moral >= apres[i] + 4, e[i].prenom + " : moral " + apres[i] + " → " + e[i].moral);
                    h.assertTrue(e[1].memoire.getBoolean("deuil_enterre"), "le deuil pèsera moins");
                },
                () -> TestsOrdres.fin(h, e));
    }

    /** Odon est à terre quand le chef ordonne l'enterrement : Raoul y va, Odon non, et son moral ne remonte pas. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "deuil")
    public static void aTerreNeVientPas(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 248);
        int[] avant = new int[3];
        Essais.etapes(h, chef, e,
                () -> e[0].kill(),
                () -> {
                    e[2].tomberATerre();
                    avant[2] = e[2].moral;
                    Essais.regarder(chef, e[1]);
                    e[1].parler(chef, "Allez à la tombe de Bertrand.");
                    h.assertTrue(e[1].action instanceof Deuil.Enterrement, "Raoul y va (" + TestsRepertoire.c(e[1]) + ")");
                    h.assertFalse(e[2].action instanceof Deuil.Enterrement, "Odon, à terre, n'y va pas");
                },
                () -> h.assertTrue(Courrier.de(h.getLevel().getServer()).effectifs.get(chef.getUUID()).get(e[0].getUUID()).enterre, "Bertrand est enterré"),
                () -> {
                    h.assertTrue(e[2].aTerre && e[2].moral <= avant[2], "Odon n'en reçoit rien : moral " + avant[2] + " → " + e[2].moral);
                    h.assertTrue(e[2].memoire.getCompound("deuils").contains(e[0].getUUID().toString()), "son deuil reste à adoucir");
                },
                () -> TestsOrdres.fin(h, e));
    }

    /** Le chef est absent à la mort : un compagnon lui écrit ; la nouvelle n'est dite qu'à son retour. */
    @GameTest(template = "vide", timeoutTicks = 600, batch = "deuil")
    public static void lettreAuChefAbsent(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 249);
        java.util.UUID absent = java.util.UUID.randomUUID();
        Essais.etapes(h, chef, e,
                () -> {
                    for (Travailleur t : e) t.chef = absent;
                    e[0].kill();
                },
                () -> h.assertTrue(TestsMineur.lettre(e[1], "deuil") || TestsMineur.lettre(e[2], "deuil"), "une lettre au chef absent"),
                Essais.attendre(h, 120),
                () -> {
                    h.assertFalse(dit(e[1], "deuil_apprend") || dit(e[2], "deuil_apprend"), "personne à qui le dire (" + paroles(e) + ")");
                    e[1].chef = e[2].chef = chef.getUUID(); // le chef revient
                },
                () -> h.assertTrue(dit(e[1], "deuil_apprend") || dit(e[2], "deuil_apprend"), "à son retour, on le lui dit (" + paroles(e) + ")"),
                () -> TestsOrdres.fin(h, e));
    }

    /** Odon attendait là où le chef l'a mis : il vient à l'enterrement, puis retourne à sa place et y attend. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "deuil")
    public static void attendRetourneASaPlace(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 250);
        Vec3[] place = {null};
        boolean[] venu = {false};
        h.onEachTick(() -> { if (e[2].action instanceof Deuil.Enterrement d && d.arrive) venu[0] = true; });
        Essais.etapes(h, chef, e,
                () -> {
                    e[2].mode = Travailleur.Mode.ATTENDRE;
                    place[0] = e[2].position();
                    e[0].moveTo(e[0].getX() + 12, e[0].getY(), e[0].getZ());
                },
                () -> e[0].kill(),
                () -> {
                    Essais.regarder(chef, e[1]);
                    e[1].parler(chef, "Raoul, on va enterrer Bertrand.");
                    h.assertTrue(e[2].action instanceof Deuil.Enterrement, "Odon vient aussi (" + TestsRepertoire.c(e[2]) + ")");
                },
                () -> h.assertTrue(venu[0] && Courrier.de(h.getLevel().getServer()).effectifs.get(chef.getUUID()).get(e[0].getUUID()).enterre, "Bertrand est enterré, Odon était là"),
                () -> h.assertTrue(!(e[2].action instanceof Deuil.Enterrement) && e[2].position().distanceToSqr(place[0]) <= 2 * 2 && e[2].mode == Travailleur.Mode.ATTENDRE,
                        "Odon est revenu à sa place (" + Math.sqrt(e[2].position().distanceToSqr(place[0])) + " blocs, " + e[2].mode + ")"),
                () -> TestsOrdres.fin(h, e));
    }

    /** Un jour après la mort, un ouvrier propose l'enterrement ; le chef dit « oui » : ils y vont ; « non, arrêtez » : ils s'arrêtent tous. */
    @GameTest(template = "vide", timeoutTicks = 1200, batch = "deuil")
    public static void ouiALaProposition(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = Essais.equipe(h, chef, 251);
        Travailleur[] qui = {null};
        Essais.etapes(h, chef, e,
                () -> e[0].kill(),
                () -> Courrier.de(h.getLevel().getServer()).effectifs.get(chef.getUUID()).get(e[0].getUUID()).mortLe -= Deuil.JOUR + 1, // un jour a passé
                () -> {
                    qui[0] = dit(e[1], "enterrement_propose") ? e[1] : dit(e[2], "enterrement_propose") ? e[2] : null;
                    h.assertTrue(qui[0] != null, "l'un d'eux propose l'enterrement (" + paroles(e) + ")");
                    h.assertFalse(dit(e[1], "enterrement_propose") && dit(e[2], "enterrement_propose"), "un seul le propose");
                },
                () -> {
                    Essais.regarder(chef, qui[0]);
                    qui[0].parler(chef, "Oui.");
                    h.assertTrue(e[1].action instanceof Deuil.Enterrement && e[2].action instanceof Deuil.Enterrement, "ils y vont (" + paroles(e) + ")");
                    qui[0].parler(chef, "Non, arrêtez.");
                },
                () -> {
                    h.assertFalse(e[1].action instanceof Deuil.Enterrement || e[2].action instanceof Deuil.Enterrement, "ils s'arrêtent tous");
                    h.assertFalse(Courrier.de(h.getLevel().getServer()).effectifs.get(chef.getUUID()).get(e[0].getUUID()).enterre, "pas enterré");
                },
                () -> TestsOrdres.fin(h, e));
    }
}

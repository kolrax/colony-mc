package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static fr.lacolonie.pnj.TestsIA.attendre;
import static fr.lacolonie.pnj.TestsIA.equipe;
import static fr.lacolonie.pnj.TestsIA.etapes;
import static fr.lacolonie.pnj.TestsIA.regarder;

/**
 * Les ordres longs (cahier 8.3), joués pour de vrai. Lot à part (« ordres ») : on y change la météo et l'heure,
 * sans gêner les autres tests.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsOrdres {

    static void fin(GameTestHelper h, Travailleur[] e) { for (Travailleur t : e) t.discard(); }

    static long heureAvant;

    @BeforeBatch(batch = "ordres")
    public static void avant(ServerLevel l) { heureAvant = l.getDayTime(); }

    /** Le beau temps et l'heure d'avant, même si un test du lot a échoué en pleine pluie (les lots suivants en dépendent). */
    @AfterBatch(batch = "ordres")
    public static void apres(ServerLevel l) {
        l.setWeatherParameters(6000, 0, false, false);
        l.setDayTime(heureAvant);
    }

    /** « S'il pleut, rentre » : il se met à l'abri au poste quand la pluie tombe, et reprend le travail quand elle cesse. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 800)
    public static void pluieRentrePuisReprend(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 42);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> { h.getLevel().setWeatherParameters(6000, 0, false, false); regarder(chef, b); b.parler(chef, "S'il pleut, rentre au poste."); },
                () -> h.assertTrue(b.mode == Travailleur.Mode.TRAVAIL, "pas de pluie : il travaille (" + b.mode + ")"),
                attendre(h, 40),
                () -> h.getLevel().setWeatherParameters(0, 6000, true, false),
                () -> h.assertTrue(b.mode == Travailleur.Mode.ATTENDRE, "il pleut : il attend au poste (" + b.mode + ", " + b.memoire + ", règles " + b.regles + ")"),
                () -> h.getLevel().setWeatherParameters(6000, 0, false, false),
                () -> h.assertTrue(b.mode == Travailleur.Mode.TRAVAIL, "la pluie a cessé : il reprend (" + b.mode + ")"),
                () -> fin(h, e)); // seul ce test touche à la météo (les autres du lot ne la remettent pas au beau)
    }

    /** « Quand tu as fini, va aider Raoul » : sa mission livrée, il part rejoindre Raoul. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 600)
    public static void finiPuisVaAiderRaoul(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 43);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> { regarder(chef, b); b.parler(chef, "Ramène 5 fers. Quand tu as fini, va aider Raoul."); },
                () -> h.assertTrue(b.missions.size() == 1 && b.consigne == null, "la mission d'abord (" + b.missions.size() + ", " + TestsRepertoire.c(b) + ")"),
                attendre(h, 40),
                () -> h.assertTrue(b.consigne == null, "pas encore fini : il ne part pas (" + TestsRepertoire.c(b) + ")"),
                () -> b.missions.get(0).livre = 5,
                () -> h.assertTrue("aller_voir_cible".equals(TestsRepertoire.c(b)), "fini : il va voir Raoul (" + TestsRepertoire.c(b) + ")"),
                () -> fin(h, e));
    }

    /** « Si tu vois un zombie, préviens-moi » : un zombie en vue, son chef reçoit un avis au poste (une seule fois). */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 600)
    public static void zombieVuAvisAuChef(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 44);
        Travailleur b = e[0];
        net.minecraft.world.entity.monster.Zombie[] z = {null};
        java.util.function.LongSupplier avis = () -> Courrier.de(h.getLevel().getServer()).boite(chef.getUUID()).stream().filter(l -> l.objet.contains("zombie")).count();
        etapes(h, chef, e,
                () -> { regarder(chef, b); b.parler(chef, "Si tu vois un zombie, préviens-moi."); },
                () -> h.assertTrue(b.regles.size() == 1, "une règle (" + b.regles + ")"),
                attendre(h, 30),
                () -> h.assertTrue(avis.getAsLong() == 0, "pas de zombie : pas d'avis"),
                () -> {
                    Vec3 p = b.position().add(5, 0, 0);
                    z[0] = EntityType.ZOMBIE.create(h.getLevel());
                    z[0].moveTo(p.x, p.y, p.z);
                    z[0].setNoAi(true);
                    z[0].setInvulnerable(true);
                    h.getLevel().addFreshEntity(z[0]);
                },
                () -> h.assertTrue(avis.getAsLong() == 1, "le zombie vu : un avis (" + avis.getAsLong() + ")"),
                attendre(h, 60),
                () -> h.assertTrue(avis.getAsLong() == 1, "un seul avis tant qu'il reste là (" + avis.getAsLong() + ")"),
                () -> { z[0].discard(); fin(h, e); });
    }

    /** « Tous les jours, ramène 5 fers » : livrée, la mission reste ; le lendemain, elle repart de zéro. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 600)
    public static void missionQuotidienneRepartLeLendemain(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 45);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> { h.getLevel().setDayTime(1000); regarder(chef, b); b.parler(chef, "Tous les jours, ramène 5 fers."); },
                () -> h.assertTrue(b.missions.size() == 1, "une mission (" + b.missions.size() + ")"),
                () -> { b.missions.get(0).livre = 5; b.nettoyerMissions(); },
                attendre(h, 40),
                () -> h.assertTrue(b.missions.size() == 1 && b.missionActive() == null, "livrée : elle reste, faite pour aujourd'hui (" + b.missions.size() + ")"),
                () -> h.getLevel().setDayTime(25000),
                () -> h.assertTrue(b.missions.get(0).livre == 0 && b.missionActive() != null, "le lendemain, elle repart (" + b.missions.get(0).livre + ")"),
                () -> fin(h, e));
    }

    /** « Les mineurs, tous à la mine ! » : le mineur reprend le travail, le bûcheron et le guerrier ne bougent pas. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 400)
    public static void lesMineursALaMine(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 46);
        etapes(h, chef, e,
                () -> { for (Travailleur t : e) t.mode = Travailleur.Mode.ATTENDRE; Dialogue.parler(chef, "Les mineurs, tous à la mine !", null, true); },
                () -> {
                    h.assertTrue(e[0].mode == Travailleur.Mode.TRAVAIL, "le mineur y va (" + e[0].mode + ")");
                    h.assertTrue(e[1].mode == Travailleur.Mode.ATTENDRE && e[2].mode == Travailleur.Mode.ATTENDRE, "les autres restent (" + e[1].mode + ", " + e[2].mode + ")");
                },
                () -> fin(h, e));
    }

    /** La condition vraie en ce moment (le jour ou la nuit). */
    static String maintenant(GameTestHelper h) { return OrdresLongs.vrai("JOUR", TestsJeu.heure(h), false, false) ? "JOUR" : "NUIT"; }

    /** Une règle qui donne une mission l'ajoute aux autres, et ne remet pas à zéro la même mission en cours. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 400)
    public static void regleAjouteSansEffacer(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 48);
        Travailleur b = e[0];
        OrdresLongs.Regle r = new OrdresLongs.Regle(maintenant(h), "ramène 5 fers");
        etapes(h, chef, e,
                () -> { b.mission("charbon", "quantite", 10); OrdresLongs.ajouter(b.regles, r); },
                () -> h.assertTrue(b.missions.size() == 2 && b.missions.get(0).id.equals("charbon") && b.missions.get(1).id.equals("fer"),
                        "charbon puis fer (" + b.missions.stream().map(m -> m.id).toList() + ")"),
                () -> { b.missions.get(1).livre = 2; b.declencher(r, null, h.getLevel().getGameTime()); },
                () -> h.assertTrue(b.missions.size() == 2 && b.missions.get(1).livre == 2, "le fer en cours n'est pas remis à zéro (" + b.missions.get(1).livre + ")"),
                () -> fin(h, e));
    }

    /** Une règle ne défait pas un ordre donné après elle : il suit son chef, la règle attend. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 400)
    public static void regleNEcrasePasSuivre(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 49);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> { regarder(chef, b); b.parler(chef, "Suis-moi"); OrdresLongs.ajouter(b.regles, new OrdresLongs.Regle(maintenant(h), "rentre au poste")); },
                attendre(h, 50),
                () -> h.assertTrue(b.mode == Travailleur.Mode.SUIVRE && !b.memoire.contains("rappel"), "il suit toujours (" + b.mode + ", " + b.memoire + ")"),
                () -> fin(h, e));
    }

    /** « Au travail » après « rentre au poste » : il ne rentre plus. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 300)
    public static void auTravailEffaceLeRappel(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 50);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> {
                    b.memoire.putBoolean("rappel", true);
                    b.memoire.putBoolean("attendre_apres", true);
                    regarder(chef, b);
                    b.parler(chef, "Au travail !");
                    h.assertTrue(!b.memoire.contains("rappel") && !b.memoire.contains("attendre_apres"), "plus de retour au poste (" + b.memoire + ")");
                },
                () -> fin(h, e));
    }

    /** « Quand tu as fini, va aider Raoul », chef absent : il y va quand même, et la règle ne sert qu'une fois. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 400)
    public static void finiChefAbsent(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 51);
        Travailleur b = e[0];
        java.util.UUID absent = java.util.UUID.randomUUID();
        etapes(h, chef, e,
                () -> {
                    for (Travailleur t : e) t.chef = absent; // un chef qui n'est pas dans le monde
                    b.mission("fer", "quantite", 5);
                    OrdresLongs.ajouter(b.regles, new OrdresLongs.Regle("FINI", "va aider Raoul"));
                },
                attendre(h, 30),
                () -> b.missions.get(0).livre = 5,
                () -> h.assertTrue("aller_voir_cible".equals(TestsRepertoire.c(b)) && b.regles.isEmpty(), "il va voir Raoul, règle usée (" + TestsRepertoire.c(b) + ", " + b.regles + ")"),
                () -> fin(h, e));
    }

    /** Ses règles sont sauvegardées avec lui, et « où en es-tu » les dit ; « oublie tes ordres » les efface. */
    @GameTest(template = "vide", batch = "ordres", timeoutTicks = 400)
    public static void reglesSauvegardees(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 47);
        Travailleur b = e[0];
        etapes(h, chef, e,
                () -> { regarder(chef, b); b.parler(chef, "S'il pleut, rentre. Si tu vois un creeper, préviens-moi."); },
                () -> {
                    h.assertTrue(b.regles.size() == 2, "deux règles (" + b.regles + ")");
                    CompoundTag tag = new CompoundTag();
                    b.saveWithoutId(tag);
                    Travailleur copie = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
                    copie.load(tag);
                    h.assertTrue(copie.regles.toString().equals(b.regles.toString()), "relues : " + copie.regles);
                    h.assertTrue(b.rapport().contains("s'il pleut"), "le rapport les dit : " + b.rapport());
                },
                () -> b.parler(chef, "Oublie tes ordres"),
                () -> h.assertTrue(b.regles.isEmpty(), "effacées (" + b.regles + ")"),
                () -> fin(h, e));
    }
}

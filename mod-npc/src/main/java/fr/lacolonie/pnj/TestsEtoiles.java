package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Lot 13, cahier 5.9 : les étoiles montent avec l'expérience ou une formation payée ({@link Apprentissage}). Lot à part
 * (« etoiles ») : monnaie en cuivre (1) et or (8), qui existent sans Numismatics, salaires et réglages des étoiles du
 * code, remis à la fin du lot. Des PNJ sans IA de déplacement dans la structure du test, près d'un poste.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsEtoiles {
    static List<Reglages.Piece> monnaie;
    static int[] salaires;
    static Etoiles.Reglage etoiles;

    @BeforeBatch(batch = "etoiles")
    public static void avant(ServerLevel l) {
        Reglages r = Reglages.actuel;
        monnaie = r.monnaie;
        salaires = r.salaire_par_niveau;
        etoiles = r.etoiles;
        r.monnaie = new java.util.ArrayList<>(List.of(new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
        r.salaire_par_niveau = new int[]{2, 4, 8, 16, 32};
        r.etoiles = new Etoiles.Reglage();
        r.etoiles.minutes_de_travail = new int[]{2, 4, 8, 16}; // deux minutes d'ouvrage pour ★★
    }

    @AfterBatch(batch = "etoiles")
    public static void apres(ServerLevel l) {
        if (monnaie == null) return;
        Reglages.actuel.monnaie = monnaie;
        Reglages.actuel.salaire_par_niveau = salaires;
        Reglages.actuel.etoiles = etoiles;
        monnaie = null;
    }

    /** Un mineur ★ de {@code chef}, au travail, à côté du poste. */
    static Travailleur mineur(GameTestHelper h, ServerPlayer chef, Bureau.Entite poste) {
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(3.5, 2, 4.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort("mineur", 1);
        t.talents = List.of(); // pas de talent au hasard (Talents) : la vie et la vitesse se comparent
        t.appliquerNiveau();
        t.setNoAi(true);
        t.chef = chef.getUUID();
        t.mode = Travailleur.Mode.TRAVAIL;
        t.bureau = poste.getBlockPos();
        t.debut = t.fin = 0; // en service toute la journée
        h.getLevel().addFreshEntity(t);
        return t;
    }

    static void casser(Travailleur t, String resultat) {
        t.action = new Actions.Casser(t.blockPosition().below());
        t.finirAction(resultat);
    }

    /** Deux minutes d'ouvrage (une seule par minute, les échecs ne comptent pas) : ★★, la vie, une hausse mesurée, il le dit et l'écrit. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "etoiles")
    public static void lExperienceDonneUneEtoile(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef, TestsMessages.poste(h, chef));
        h.assertTrue(t.salaireDemande() == 2, "★ : il demande 2 (" + t.salaireDemande() + ")");
        double habile = t.facteurTravail();
        casser(t, "ok");
        casser(t, "ok"); // la même minute : un seul point
        casser(t, "echec");
        h.assertTrue(t.niveau == 1 && t.memoire.getInt("experience") == 1, "une minute d'ouvrage, toujours ★ : " + t.niveau + ", " + t.memoire);
        t.memoire.putLong("xp_minute", 0); // la minute suivante
        casser(t, "ok");
        h.assertTrue(t.niveau == 2, "★★ après deux minutes d'ouvrage : " + t.niveau + ", " + t.memoire);
        h.assertTrue(t.getMaxHealth() == 22, "plus robuste : " + t.getMaxHealth());
        h.assertTrue(t.facteurTravail() > habile, "plus habile : " + t.facteurTravail());
        h.assertTrue(t.salaireDemande() == 3, "une hausse mesurée (3), pas le prix d'un ★★ (4) : " + t.salaireDemande());
        h.assertTrue(t.paroles.stream().anyMatch(s -> s.contains("★★")), "il le dit : " + t.paroles);
        // La lettre au chef, avec sa nouvelle paie (le chef à portée de voix : il la lui dit, Courrier.envoyer).
        h.assertTrue(t.lettresADeposer.stream().anyMatch(l -> l.objet.equals("Une étoile de plus") && l.texte.contains("passe de"))
                || t.paroles.stream().anyMatch(s -> s.contains("passe de")), "une lettre au chef, avec sa nouvelle paie : " + t.paroles);
        t.discard();
        h.succeed();
    }

    /**
     * « Va te former » : sans la somme sur lui, le chef essuie un refus ; avec, il paie, l'ouvrier étudie au poste sans
     * travailler, puis revient ★★. Un maître ★★★★★ n'a plus rien à apprendre.
     */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "etoiles")
    public static void laFormationPayeeDonneUneEtoile(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite poste = TestsMessages.poste(h, chef);
        Travailleur t = mineur(h, chef, poste);
        Ordres.Faire former = new Ordres.Faire("se_former", "", 0, 0, false);
        String refus = Consignes.ordonner(t, former, chef, "va te former", 0, 1);
        h.assertTrue(refus.contains("maître demande") && t.memoire.getLong("formation_fin") == 0, "sans argent, pas de formation : " + refus);
        chef.getInventory().add(new ItemStack(Items.COPPER_INGOT, 20));
        String oui = Consignes.ordonner(t, former, chef, "va te former", 0, 1);
        h.assertTrue(t.memoire.getLong("formation_fin") > h.getLevel().getGameTime(), "en formation : " + oui);
        h.assertTrue(chef.getInventory().countItem(Items.COPPER_INGOT) == 4, "le chef a payé 16 : " + chef.getInventory().countItem(Items.COPPER_INGOT));
        Cerveau.decider(t, Metier.de(t.metier));
        h.assertTrue(t.action != null && t.action.nom.equals("attendre"), "il étudie, il ne travaille pas : " + (t.action == null ? "-" : t.action.nom));
        h.assertTrue(t.niveau == 1, "pas encore ★★");
        t.finirAction("ok");
        t.memoire.putLong("formation_fin", h.getLevel().getGameTime()); // le terme
        Cerveau.decider(t, Metier.de(t.metier));
        h.assertTrue(t.niveau == 2 && t.memoire.getLong("formation_fin") == 0, "revenu ★★ : " + t.niveau + ", " + t.memoire);
        h.assertTrue(t.paroles.stream().anyMatch(s -> s.contains("Me revoilà")), "il le dit : " + t.paroles);
        t.niveau = 5;
        h.assertTrue(Consignes.ordonner(t, former, chef, "va te former", 0, 1).contains("maître"), "★★★★★ : plus rien à apprendre");
        h.assertTrue(chef.getInventory().countItem(Items.COPPER_INGOT) == 4, "le maître ne coûte rien de plus");
        t.discard();
        h.succeed();
    }
}

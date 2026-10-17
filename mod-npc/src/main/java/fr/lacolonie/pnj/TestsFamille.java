package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Lot 13, cahier 5.6, 5.7 et 4.2 : les familles ({@link Famille}). Lot à part (« famille ») : une naissance par jour
 * du jeu (le tirage est sûr), réglages remis à la fin. Des PNJ sans IA de déplacement dans la structure du test, près
 * d'un poste ; aucun coin du monde pris.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsFamille {
    static Ages.Reglage ages;
    static boolean familles;

    @BeforeBatch(batch = "famille")
    public static void avant(ServerLevel l) {
        ages = Reglages.actuel.ages;
        familles = Reglages.actuel.familles;
        Reglages.actuel.familles = true;
        Reglages.actuel.ages = new Ages.Reglage();
        Reglages.actuel.ages.naissances_par_jour = 1; // le tirage tombe à coup sûr
    }

    @AfterBatch(batch = "famille")
    public static void apres(ServerLevel l) {
        if (ages == null) return;
        Reglages.actuel.ages = ages;
        Reglages.actuel.familles = familles;
        ages = null;
    }

    /** Un ouvrier de {@code chef}, au travail toute la journée, à côté du poste, de tel âge. */
    static Travailleur ouvrier(GameTestHelper h, ServerPlayer chef, Bureau.Entite poste, String prenom, String metier, double age, double x) {
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(x, 2, 4.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort(metier, 1);
        t.prenom = prenom;
        t.talents = List.of();
        t.age = age;
        t.ageFin = 85;
        t.ageLe = h.getLevel().getGameTime();
        t.appliquerNiveau();
        t.setNoAi(true);
        t.chef = chef.getUUID();
        t.chefNom = chef.getName().getString();
        t.mode = Travailleur.Mode.TRAVAIL;
        t.bureau = poste.getBlockPos();
        t.debut = t.fin = 0; // en service toute la journée
        t.prochainePaie = System.currentTimeMillis() + 3_600_000L;
        t.majNom();
        h.getLevel().addFreshEntity(t);
        Courrier.de(h.getLevel().getServer()).inscrire(t);
        return t;
    }

    /** Un couple du même chef, content, sous le toit du poste : à la minute, un enfant naît, petit, qui connaît ses parents. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "famille")
    public static void unCoupleAUnEnfant(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite poste = TestsMessages.poste(h, chef);
        Travailleur raoul = ouvrier(h, chef, poste, "Raoul", "bucheron", 30, 3.5);
        Travailleur agnes = ouvrier(h, chef, poste, "Agnès", "mineur", 28, 5.5);
        Relations.unir(raoul, agnes);
        // Raoul ne porte pas l'enfant : rien ne se passe de son côté.
        raoul.ageLe -= 1200;
        Famille.veiller(raoul);
        h.assertTrue(Famille.naissance(raoul, true) == null, "un homme ne porte pas l'enfant");
        agnes.ageLe -= 1200; // une minute a passé
        Famille.veiller(agnes);
        List<Ages.Proche> enfants = Ages.lire(agnes.memoire.getString("enfants"));
        h.assertTrue(enfants.size() == 1, "un enfant est né : " + agnes.memoire);
        Travailleur e = h.getLevel().getEntitiesOfClass(Travailleur.class, agnes.getBoundingBox().inflate(4),
                x -> x.getStringUUID().equals(enfants.get(0).id())).stream().findFirst().orElse(null);
        h.assertTrue(e != null, "l'enfant est là, près de sa mère");
        h.assertTrue(e.chef.equals(chef.getUUID()) && e.mode == Travailleur.Mode.TRAVAIL && e.age < 1, "du même chef, nouveau-né : " + e.age);
        h.assertTrue(Math.abs(e.getAttributeValue(Attributes.SCALE) - 0.5) < 0.01 && e.getBbHeight() < 1.2, "petit : " + e.getAttributeValue(Attributes.SCALE) + ", " + e.getBbHeight());
        h.assertTrue(e.metier.equals("bucheron") || e.metier.equals("mineur"), "le métier d'un parent : " + e.metier);
        h.assertTrue(Ages.lire(raoul.memoire.getString("enfants")).size() == 1, "le père le sait aussi");
        h.assertTrue(e.memoire.getString("famille").equals("Raoul"), "le nom de sa maison vient d'un parent : " + e.memoire.getString("famille"));
        h.assertTrue(!e.paieDue() && e.salaireDemande() == 0, "pas de salaire");
        h.assertTrue(e.getCustomName().getString().contains("Enfant"), "« Enfant » sous son nom : " + e.getCustomName().getString());
        // La nouvelle au chef (à portée de voix : elle la lui dit, Courrier.envoyer).
        h.assertTrue(agnes.paroles.stream().anyMatch(s -> s.contains(e.prenom)), "elle annonce la naissance : " + agnes.paroles);
        // Il connaît ses parents ; elle, son enfant ; et chacun son âge.
        String parents = e.appliquer(Ordres.lire("Qui sont tes parents ?", e.metier), chef);
        h.assertTrue(parents.contains("Raoul") && parents.contains("Agnès"), "ses parents : " + parents);
        String enfant = agnes.appliquer(Ordres.lire("As-tu des enfants ?", agnes.metier), chef);
        h.assertTrue(enfant.contains(e.prenom), "son enfant : " + enfant);
        String age = raoul.appliquer(Ordres.lire("Quel âge as-tu ?", raoul.metier), chef);
        h.assertTrue(age.contains("30 ans"), "son âge : " + age);
        // Pas un deuxième le même jour par hasard : au plus 3 par couple, la colonie au plafond n'en a plus.
        int plafond = Reglages.actuel.pnj_max_par_joueur;
        Reglages.actuel.pnj_max_par_joueur = 3;
        try { h.assertTrue(Famille.naissance(agnes, true) == null, "la colonie est au plafond (3 avec l'enfant)"); }
        finally { Reglages.actuel.pnj_max_par_joueur = plafond; }
        e.discard();
        raoul.discard();
        agnes.discard();
        h.succeed();
    }

    /** Un enfant ne travaille pas : il joue près du poste, ne prend pas d'ordre de travail, mais peut choisir le métier qu'il apprendra. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "famille")
    public static void lEnfantNeTravaillePas(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite poste = TestsMessages.poste(h, chef);
        h.setBlock(3, 1, 6, net.minecraft.world.level.block.Blocks.IRON_ORE); // un filon à côté : un mineur adulte s'y mettrait
        Travailleur t = ouvrier(h, chef, poste, "Thibaut", "mineur", 6, 3.5);
        t.prochainePaie = 0; // la paie serait due depuis longtemps
        Cerveau.penser(t);
        h.assertTrue(t.action == null || t.action.nom.equals("aller") || t.action.nom.equals("attendre"), "il joue : " + (t.action == null ? "-" : t.action.nom));
        h.assertTrue(!t.paieDue(), "pas de paie pour un enfant");
        String r = t.appliquer(Ordres.lire("Thibaut, ramène-moi 10 fers.", t.metier), chef);
        h.assertTrue(t.missions.isEmpty() && Vie.vient("enfant_ordre", r), "pas d'ordre de travail : " + r + " ; " + t.missions.size());
        r = t.appliquer(Ordres.lire("Apprends le métier de bûcheron", t.metier), chef);
        h.assertTrue(t.metier.equals("bucheron"), "il apprendra le métier de bûcheron : " + r);
        r = t.appliquer(Ordres.lire("Quel âge as-tu ?", t.metier), chef);
        h.assertTrue(r.contains("6 ans"), "son âge : " + r);
        t.discard();
        h.succeed();
    }

    /** À 16 ans, l'apprenti (à demi-vitesse, à demi-paie) devient un ouvrier comme les autres ; il le dit au chef. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "famille")
    public static void lApprentiDevientOuvrier(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite poste = TestsMessages.poste(h, chef);
        Travailleur t = ouvrier(h, chef, poste, "Isabeau", "mineur", 15.9, 3.5);
        t.memoire.putString("talents_parents", "habile");
        Famille.appliquer(t);
        double apprenti = t.facteurTravail();
        long demiPaie = t.salaireDemande();
        h.assertTrue(t.getCustomName().getString().contains("Apprenti"), "« Apprenti mineur » : " + t.getCustomName().getString());
        Famille.vieillir(t, 0.2);
        Famille.veiller(t);
        h.assertTrue(t.age >= 16 && Ages.etape(t.age) == Ages.Etape.ADULTE, "seize ans : " + t.age);
        h.assertTrue(t.facteurTravail() >= apprenti * 1.99, "à pleine vitesse : " + apprenti + " → " + t.facteurTravail());
        h.assertTrue(demiPaie < t.salaireDemande() && demiPaie == Math.round(t.salaireDemande() * 0.5), "la paie entière : " + demiPaie + " → " + t.salaireDemande());
        h.assertTrue(Math.abs(t.getAttributeValue(Attributes.SCALE) - 1) < 0.01, "à sa taille d'adulte");
        h.assertTrue(t.niveau >= 1 && !t.getCustomName().getString().contains("Apprenti"), "un ouvrier ★ : " + t.getCustomName().getString());
        h.assertTrue(t.paroles.stream().anyMatch(s -> s.contains("Seize ans") || s.contains("seize ans") || Vie.vient("adulte", s)), "il le dit : " + t.paroles);
        t.discard();
        h.succeed();
    }

    /** Son heure venue, il s'éteint (pas « à terre ») : ses affaires dans un coffre, un avis « de vieillesse » au chef, sa fille en deuil plus lourd. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "famille")
    public static void mortDeVieillesse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite poste = TestsMessages.poste(h, chef);
        Travailleur vieux = ouvrier(h, chef, poste, "Odon", "mineur", 84.5, 3.5);
        Travailleur fille = ouvrier(h, chef, poste, "Oriane", "mineur", 40, 5.5);
        Travailleur autre = ouvrier(h, chef, poste, "Amaury", "mineur", 40, 6.5);
        fille.memoire.putString("parents", Ages.texte(List.of(new Ages.Proche(vieux.getStringUUID(), "Odon"))));
        vieux.inventaire.addItem(new ItemStack(Items.BREAD, 3));
        int renom = Courrier.de(h.getLevel().getServer()).reputation(chef.getUUID());
        int moralFille = fille.moral, moralAutre = autre.moral;
        Famille.vieillir(vieux, 1);
        Famille.veiller(vieux);
        h.assertTrue(!vieux.isAlive() && !vieux.aTerre, "il s'est éteint, sans être à terre");
        BlockPos coffre = vieux.tombe;
        h.assertTrue(coffre != null && h.getLevel().getBlockEntity(coffre) instanceof ChestBlockEntity c && c.countItem(Items.BREAD) == 3, "ses affaires dans un coffre : " + coffre);
        h.assertTrue(Courrier.de(h.getLevel().getServer()).boite(chef.getUUID()).stream().anyMatch(l -> l.texte.contains("vieillesse")), "l'avis au chef dit « de vieillesse »");
        h.assertTrue(Courrier.de(h.getLevel().getServer()).reputation(chef.getUUID()) == renom, "pas un mauvais point pour le chef");
        h.assertTrue(moralFille - fille.moral > moralAutre - autre.moral && autre.moral < moralAutre,
                "deuil plus lourd pour sa fille : " + (moralFille - fille.moral) + " contre " + (moralAutre - autre.moral));
        fille.discard();
        autre.discard();
        h.succeed();
    }
}

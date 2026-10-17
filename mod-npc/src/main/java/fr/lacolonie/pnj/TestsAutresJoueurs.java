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
import java.util.UUID;

/**
 * Lot 14, autres joueurs (cahier 5.8, 6.1, 6.2, 6.6) : un joueur autorisé par le chef se fait obéir ; un autre se fait
 * répondre poliment mais refuser ses ordres, sauf s'il paie l'ordre ; la débauche réussie (pièces exactes, nouveau chef,
 * lettre à l'ancien) et refusée (rien ne bouge, rancune) ; le chef écrit au-dessus de la tête. Coins 420 à 425 du monde
 * plat. Lot à part (« autres_joueurs ») : la monnaie y est le cuivre (1) et l'or (8), comme pour la boutique.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsAutresJoueurs {
    static List<Reglages.Piece> normale;

    @BeforeBatch(batch = "autres_joueurs")
    public static void avant(ServerLevel l) {
        normale = Reglages.actuel.monnaie;
        Reglages.actuel.monnaie = new java.util.ArrayList<>(List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
    }

    @AfterBatch(batch = "autres_joueurs")
    public static void apres(ServerLevel l) { if (normale != null) Reglages.actuel.monnaie = normale; }

    record Scene(Travailleur t, ServerPlayer chef, ServerPlayer autre, BlockPos base) {
        String nom() { return autre.getGameProfile().getName(); }
    }

    /** Un mineur de {@code chef}, ce dernier à {@code loin} blocs, l'autre joueur à 2 blocs. */
    static Scene scene(GameTestHelper h, int coin, double loin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        chef.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5 + loin);
        autre.moveTo(base.getX() + 4.5, base.getY(), base.getZ() + 0.5);
        Travailleur t = TestsJeu.employe(h, "mineur", base, Vec3.atBottomCenterOf(base.east(2)), ItemStack.EMPTY);
        t.chef = chef.getUUID();
        t.chefNom = chef.getName().getString();
        t.prenom = "Bertrand";
        t.temperament = "colerique";
        t.moral = 50;
        t.salaire = 10;
        t.majNom();
        t.forcerChunk();
        Courrier.de(h.getLevel().getServer()).inscrire(t);
        return new Scene(t, chef, autre, base);
    }

    static void fin(Scene s) {
        Courrier c = Courrier.de(s.t().level().getServer());
        for (UUID u : new UUID[]{s.chef().getUUID(), s.autre().getUUID()}) { c.postes.remove(u); c.commandants.remove(u); c.effectifs.remove(u); }
        c.vols.removeIf(v -> s.t().getUUID().equals(v.lettre.deUuid));
        s.t().discard();
    }

    static String vu(Scene s) {
        return "paroles " + s.t().paroles + ", mode " + s.t().mode + ", chef " + (s.chef().getUUID().equals(s.t().chef) ? "l'ancien" : s.autre().getUUID().equals(s.t().chef) ? "l'autre" : s.t().chef);
    }

    /** Une lettre de l'ouvrier pour ce chef : en vol, à déposer ou arrivée. */
    static boolean lettre(Scene s, UUID pour) {
        Courrier c = Courrier.de(s.t().level().getServer());
        return c.vols.stream().anyMatch(v -> s.t().getUUID().equals(v.lettre.deUuid) && pour.equals(v.lettre.pour))
                || s.t().lettresADeposer.stream().anyMatch(l -> pour.equals(l.pour))
                || c.boites.getOrDefault(pour, List.of()).stream().anyMatch(l -> s.t().getUUID().equals(l.deUuid));
    }

    /** Le chef autorise l'autre joueur : l'ouvrier lui obéit (« attends ici ») ; le renvoi reste au chef. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "autres_joueurs")
    public static void autoriseObeit(GameTestHelper h) {
        Scene s = scene(h, 420, 3);
        Essais.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.chef(), s.nom() + " peut te donner des ordres."),
                () -> h.assertTrue(Courrier.de(h.getLevel().getServer()).commande(s.chef().getUUID(), s.nom()), "l'autorisation est retenue : " + vu(s)),
                () -> h.assertTrue(Essais.aDit(s.t(), "commande_permis"), "il le dit : " + vu(s)),
                () -> s.t().parler(s.autre(), "Attends ici."),
                () -> h.assertTrue(s.t().mode == Travailleur.Mode.ATTENDRE, "il attend sur l'ordre de l'autre : " + vu(s)),
                Essais.attendre(h, 25),
                () -> s.t().parler(s.autre(), "Je te renvoie."),
                () -> {
                    h.assertTrue(s.t().memoire.getLong("renvoi") == 0 && s.t().chef.equals(s.chef().getUUID()), "pas de renvoi par l'autre : " + vu(s));
                    h.assertTrue(Essais.aDit(s.t(), "etranger_reserve"), "c'est au chef d'en décider : " + vu(s));
                },
                () -> fin(s));
    }

    /** Sans autorisation : il salue l'autre, mais refuse son ordre. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "autres_joueurs")
    public static void nonAutoriseRefuse(GameTestHelper h) {
        Scene s = scene(h, 421, 3);
        Essais.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().parler(s.autre(), "Attends ici."),
                () -> {
                    h.assertTrue(s.t().mode == Travailleur.Mode.TRAVAIL, "il n'attend pas : " + vu(s));
                    h.assertTrue(Essais.aDit(s.t(), "etranger_refus"), "il refuse poliment : " + vu(s));
                },
                Essais.attendre(h, 25),
                () -> s.t().parler(s.autre(), "Bonjour !"),
                () -> h.assertTrue(Essais.aDit(s.t(), "etranger_salut"), "il répond au salut : " + vu(s)),
                () -> fin(s));
    }

    /** Relecture : un clic droit d'un autre joueur (comme en jeu, par interact → mobInteract) : il le salue, sans erreur. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "autres_joueurs")
    public static void clicDroitDUnAutre(GameTestHelper h) {
        Scene s = scene(h, 426, 3);
        Essais.etapes(h, s.chef(), new Travailleur[]{s.t()},
                () -> s.t().interact(s.autre(), net.minecraft.world.InteractionHand.MAIN_HAND),
                () -> h.assertTrue(Essais.aDit(s.t(), "etranger_salut"), "il salue l'autre au clic droit : " + vu(s)),
                () -> fin(s));
    }

    /** Cahier 6.6 : le nom affiché dit le prénom, le métier et le chef ; un chômeur, non. */
    @GameTest(template = "vide", timeoutTicks = 200, batch = "autres_joueurs")
    public static void nomAvecLeChef(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 424 * 400, 60000);
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        chef.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5);
        Travailleur t = Travailleur.auTravail(h.getLevel(), "mineur", base, Vec3.atBottomCenterOf(base.east(2)), chef.getUUID(), ItemStack.EMPTY);
        String attendu = t.prenom + " (" + Metier.nom("mineur") + ") · " + chef.getName().getString();
        h.runAtTickTime(h.getTick() + 2, () -> {
            String nom = t.getCustomName() == null ? "" : t.getCustomName().getString();
            h.assertTrue(nom.equals(attendu), "nom affiché « " + nom + " », attendu « " + attendu + " »");
            h.assertTrue(Insignes.prenom(nom).equals(t.prenom), "le prénom se relit : " + Insignes.prenom(nom));
            t.licencier();
            String chomeur = t.getCustomName().getString();
            h.assertTrue(chomeur.equals(t.prenom + " · cherche du travail"), "sans chef : " + chomeur);
            t.discard();
            h.succeed();
        });
    }
}

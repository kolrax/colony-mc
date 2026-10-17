package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lot 14, rumeurs (cahier 7.6) : un ouvrier qui passe près d'une autre colonie le dit ensuite au chef ; deux ouvriers qui
 * bavardent se passent une nouvelle ; un étranger ne lui fait rien dire de son propre chef ; « espionne X » sans savoir où
 * est X ; l'espion va, guette, revient le dire, et un ouvrier de là-bas l'a vu. Voir {@link Rumeurs} et {@link Espions}.
 * Coins 430 à 434. Lot à part (« rumeurs ») : les ouvriers y bavardent ({@link Causeries#enEssai}) et regardent autour d'eux
 * chaque seconde ({@link Espions#PERIODE}).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRumeurs {
    static int periode = Espions.PERIODE;

    @BeforeBatch(batch = "rumeurs")
    public static void avant(ServerLevel l) {
        periode = Espions.PERIODE;
        Espions.PERIODE = 20;
        Causeries.enEssai = true;
    }

    @AfterBatch(batch = "rumeurs")
    public static void apres(ServerLevel l) {
        Espions.PERIODE = periode;
        Causeries.enEssai = false;
    }

    /** Une colonie rivale (pas un joueur) : son chef, son nom, son poste, et ses ouvriers qui attendent près du poste. */
    record Rival(UUID chef, String nom, BlockPos poste, List<Travailleur> ouvriers) {
        void finir(ServerLevel l) {
            Courrier.de(l.getServer()).oublierPoste(chef, l, poste);
            ouvriers.forEach(Travailleur::discard);
            l.removeBlock(poste, false);
        }
    }

    static Rival rival(GameTestHelper h, int coin, BlockPos poste, String... metiers) {
        ServerLevel l = h.getLevel();
        UUID u = UUID.randomUUID();
        String nom = "Rival" + coin;
        Courrier.de(l.getServer()).definirPoste(u, l, poste);
        List<Travailleur> ouvriers = new ArrayList<>();
        for (int i = 0; i < metiers.length; i++) {
            Travailleur o = Travailleur.auTravail(l, metiers[i], poste, new Vec3(poste.getX() + 1.5 + i, poste.getY(), poste.getZ() + 2.5), u, ItemStack.EMPTY);
            o.chefNom = nom;
            o.mode = Travailleur.Mode.ATTENDRE;
            o.forcerChunk();
            ouvriers.add(o);
        }
        return new Rival(u, nom, poste, ouvriers);
    }

    /** Il passe près d'une autre colonie (son poste, trois ouvriers) : il le note de lui-même, puis le dit au chef qui le lui demande. */
    @GameTest(template = "vide", timeoutTicks = 400, batch = "rumeurs")
    public static void voitUneColonieEtLeDitAuChef(GameTestHelper h) {
        TestsAutresJoueurs.Scene s = TestsAutresJoueurs.scene(h, 430, 3);
        s.t().mode = Travailleur.Mode.ATTENDRE;
        Rival r = rival(h, 430, s.base().east(12), "mineur", "mineur", "bucheron");
        String avant = s.t().appliquer(Ordres.lire("Que sais-tu de " + r.nom() + " ?", "mineur"), s.chef());
        h.assertTrue(Vie.vient("rumeur_rien", avant), "sans l'avoir vu, il n'en sait rien : " + avant);
        h.succeedWhen(() -> {
            h.assertTrue(s.t().rumeurs.fait(r.nom(), Rumeurs.Genre.POSTE) != null, "il note le poste de l'autre colonie (" + s.t().rumeurs.liste + ")");
            String dit = s.t().appliquer(Ordres.lire("Que sais-tu de " + r.nom() + " ?", "mineur"), s.chef());
            h.assertTrue(s.t().reponseImposee, "c'est lui qui répond, pas l'IA");
            h.assertTrue(dit.contains(r.nom()) && !Vie.vient("rumeur_rien", dit), "il le dit au chef : " + dit);
            h.assertTrue(dit.contains("3 ouvriers") || dit.contains("deux mineurs") || dit.contains("à l'est"), "ce qu'il a vu, et rien d'autre : " + dit);
            r.finir(h.getLevel());
            TestsAutresJoueurs.fin(s);
        });
    }

    /** Deux ouvriers du même chef bavardent : celui qui sait une nouvelle la passe à l'autre, qui la tient de lui. */
    @GameTest(template = "vide", timeoutTicks = 900, batch = "rumeurs")
    public static void boucheAOreilleEnBavardant(GameTestHelper h) {
        UUID chef = UUID.randomUUID();
        Travailleur t1 = TestsConversations.auPoste(h, "mineur", 2, 3, chef), t2 = TestsConversations.auPoste(h, "bucheron", 5, 3, chef);
        t1.rumeurs.voir("Raoul", Rumeurs.Genre.NOMBRE, "5", t1.jour());
        h.succeedWhen(() -> {
            Rumeurs.Fait f = t2.rumeurs.fait("Raoul", Rumeurs.Genre.NOMBRE);
            h.assertTrue(f != null, "l'autre apprend la nouvelle (" + t1.paroles + " / " + t2.paroles + ")");
            h.assertTrue(f.source() == Rumeurs.Source.ENTENDU && f.de().equals(t1.prenom) && f.valeur().equals("5"), "de seconde main, de " + t1.prenom + " : " + f);
            h.assertTrue(TestsCaractere.aDit(t1, "rumeur_passe"), "il la lui dit (" + t1.paroles + ")");
            String dit = t2.rumeurs.raconter(new Rumeurs.Question("Raoul"), t2.jour(), Espions.textes(t2), x -> true);
            h.assertTrue(dit.contains(t1.prenom) && dit.contains("5"), "il sait qui la lui a dite : " + dit);
            t1.discard();
            t2.discard();
        });
    }

    /** Un autre joueur lui demande des nouvelles de son chef : il se tait ; des nouvelles en général : rien sur son chef ; fourbe, il en lâche. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "rumeurs")
    public static void etrangerRienSurSonChef(GameTestHelper h) {
        TestsAutresJoueurs.Scene s = TestsAutresJoueurs.scene(h, 432, 3);
        Travailleur t = s.t();
        t.chefNom = "Kolrax"; // un vrai pseudo (celui des faux joueurs a des tirets)
        t.temperament = "calme";
        t.moral = 60;
        t.rumeurs.voir(t.chefNom, Rumeurs.Genre.MORT, "Aliénor", t.jour());
        t.rumeurs.voir("Raoul", Rumeurs.Genre.BOUTIQUE, "Gaspard", t.jour());
        String surChef = Etranger.repondre(t, s.autre(), "Nielscutt", "Que sais-tu de " + t.chefNom + " ?");
        h.assertTrue(Vie.vient("rumeur_tais_chef", surChef), "il se tait sur son chef : " + surChef);
        String nouvelles = Etranger.repondre(t, s.autre(), "Nielscutt", "Quelles nouvelles ?");
        h.assertTrue(Vie.vient("rumeur_boutique", nouvelles), "il dit ce qu'il sait des autres : " + nouvelles);
        h.assertFalse(nouvelles.contains(t.chefNom), "rien sur son chef : " + nouvelles);
        t.temperament = "fourbe";
        String fourbe = Etranger.repondre(t, s.autre(), "Nielscutt", "Que sais-tu de " + t.chefNom + " ?");
        h.assertTrue(fourbe.contains(t.chefNom) && !Vie.vient("rumeur_tais_chef", fourbe), "fourbe, il lâche un fait sur sa colonie : " + fourbe);
        TestsAutresJoueurs.fin(s);
        h.succeed();
    }

    /** « Espionne X » sans savoir où est sa colonie : il le dit, et ne part pas. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "rumeurs")
    public static void espionSansPosteConnu(GameTestHelper h) {
        TestsAutresJoueurs.Scene s = TestsAutresJoueurs.scene(h, 433, 3);
        Rival r = rival(h, 433, TestsJeu.sol(h, 433 * 400 + 200, 60000));
        String dit = s.t().appliquer(Ordres.lire("Va espionner la colonie de " + r.nom() + ".", "mineur"), s.chef());
        h.assertTrue(Vie.vient("espion_inconnu", dit), "il ne sait pas où c'est : " + dit);
        h.assertFalse(s.t().memoire.contains("espion"), "il ne part pas");
        r.finir(h.getLevel());
        TestsAutresJoueurs.fin(s);
        h.succeed();
    }

    /** Il sait où est le poste : il y va à pied, guette, revient au poste et le dit au chef ; l'ouvrier de là-bas l'a vu. */
    @GameTest(template = "vide", timeoutTicks = 2000, batch = "rumeurs")
    public static void espionVaEtRevient(GameTestHelper h) {
        TestsAutresJoueurs.Scene s = TestsAutresJoueurs.scene(h, 434, 3);
        Travailleur t = s.t();
        Rival r = rival(h, 434, TestsJeu.sol(h, 434 * 400 + 40, 60000), "guerrier");
        t.rumeurs.voir(r.nom(), Rumeurs.Genre.POSTE, "à l'est|40 pas", t.jour());
        // L'ouvrier rival tout juste apparu n'est pas encore dans la liste du monde : l'ordre vient un peu après.
        boolean[] parti = {false};
        h.runAtTickTime(h.getTick() + 5, () -> {
            String dit = t.appliquer(Ordres.lire("Espionne " + r.nom() + ".", "mineur"), s.chef());
            h.assertTrue(Vie.vient("espion_part", dit), "il part : " + dit);
            h.assertTrue(t.memoire.contains("espion"), "la mission est notée");
            parti[0] = true;
        });
        double[] loin = {0};
        h.onEachTick(() -> loin[0] = Math.max(loin[0], t.position().distanceTo(Vec3.atBottomCenterOf(s.base()))));
        h.succeedWhen(() -> {
            h.assertTrue(parti[0] && loin[0] > 25,"il va jusque là-bas (" + TestsJeu.etat(t, t.bureau) + ")");
            // « J'ai fait le tour de chez Rival434. Écoute bien : » suivi de ce qu'il a vu.
            h.assertTrue(t.paroles.stream().anyMatch(p -> p.contains(" ouvrier") && Vie.TOUTES.get("espion_retour").stream()
                    .anyMatch(x -> p.startsWith(x.texte().replace("{nom}", r.nom())))), "revenu, il le dit au chef (" + t.paroles + ")");
            h.assertFalse(t.memoire.contains("espion"), "la mission est finie");
            h.assertTrue(t.rumeurs.fait(r.nom(), Rumeurs.Genre.NOMBRE) != null, "il a compté leurs ouvriers");
            h.assertTrue(r.ouvriers().get(0).rumeurs.fait(t.chefNom, Rumeurs.Genre.ESPION) != null, "l'ouvrier de là-bas l'a vu");
            r.finir(h.getLevel());
            TestsAutresJoueurs.fin(s);
        });
    }
}

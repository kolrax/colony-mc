package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * La mission (refonte 1.0.0, parties 1 et 4) : donnée par la même fonction que la fiche ({@link Missions#donner}), elle
 * s'ajoute à la file, le statut au-dessus de la tête la suit, un blocage s'y lit avec sa solution, et la file survit à une
 * sauvegarde. Chaque test dans son coin du monde plat (coins 731 à 735), au ciel ouvert.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMission {
    /** Un ouvrier au travail pour ce chef, à 2 blocs à l'ouest de son poste. */
    static Travailleur ouvrier(GameTestHelper h, ServerPlayer chef, String metier, int coin, ItemStack outil) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = Travailleur.auTravail(h.getLevel(), metier, base, Vec3.atBottomCenterOf(base.west(2)), chef.getUUID(), outil);
        t.forcerChunk();
        return t;
    }

    /** Le chef donne « 20 fers, une fois » : la mission est dans la file, et au-dessus de sa tête on lit « ⛏ Mine · 0/20 fer ». */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void missionDonneeEtStatut(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = ouvrier(h, chef, "mineur", 731, new ItemStack(Items.IRON_PICKAXE));
        Missions.Reponse r = Missions.donner(chef, t, "fer", "fois", 20, "toujours", null);
        h.assertTrue(r.ok(), "mission acceptée (" + r.texte() + ")");
        h.assertTrue(r.texte().equals("Compris : " + t.prenom + " mine du fer jusqu'à 20."), "la confirmation (" + r.texte() + ")");
        h.assertTrue(t.missions.size() == 1 && t.missions.get(0).id.equals("fer") && t.missions.get(0).uneFois, "dans la file");
        Missions.Reponse faux = Missions.donner(chef, t, "chene", "fois", 20, "toujours", null);
        h.assertFalse(faux.ok(), "un mineur ne coupe pas de chêne");
        h.assertTrue(t.missions.size() == 1, "refusée : la file ne change pas");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            h.assertTrue(s.startsWith("⛏ Mine") && s.contains("0/20") && s.contains("fer"), "statut au-dessus de la tête : « " + s + " »");
            t.discard();
        });
    }

    /** Sans pioche, et aucune au dépôt : « ⚠ Pas de pioche au dépôt ». */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void sansPiocheAuDepot(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = ouvrier(h, chef, "mineur", 732, ItemStack.EMPTY);
        h.assertTrue(Missions.donner(chef, t, "fer", "stock", 64, "toujours", null).ok(), "mission acceptée");
        h.succeedWhen(() -> {
            String s = t.statutTete();
            h.assertTrue(s.equals("⚠ Pas de pioche au dépôt"), "statut : « " + s + " » (" + TestsJeu.etat(t, t.bureau) + ")");
            t.discard();
        });
    }

    /** La file, les horaires, la répétition et le lieu survivent à la sauvegarde ; une ancienne mission se relit avec les défauts. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void fileSauvegardee(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = ouvrier(h, chef, "mineur", 733, new ItemStack(Items.IRON_PICKAXE));
        BlockPos ici = t.bureau.east(5);
        h.assertTrue(Missions.donner(chef, t, "charbon", "jour", 10, "nuit", ici).ok(), "première mission");
        h.assertTrue(Missions.donner(chef, t, "fer", "stock", 32, "8-12", null).ok(), "deuxième mission");
        CompoundTag tag = new CompoundTag();
        t.saveWithoutId(tag);
        Travailleur copie = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        copie.load(tag);
        h.assertTrue(copie.missions.size() == 2, "deux missions relues (" + copie.missions.size() + ")");
        Travailleur.Mission a = copie.missions.get(0), b = copie.missions.get(1);
        h.assertTrue(a.id.equals("charbon") && a.horaires.equals("nuit") && a.quotidienne && ici.equals(a.lieu) && a.nombre == 10, "la première, entière");
        h.assertTrue(b.id.equals("fer") && b.horaires.equals("8-12") && !b.quantite() && b.lieu == null && b.nombre == 32, "la seconde, entière");
        CompoundTag ancienne = new CompoundTag();
        ancienne.putString("id", "fer");
        ancienne.putString("mode", "quantite");
        ancienne.putInt("nombre", 5);
        Travailleur.Mission v = Travailleur.Mission.lire(ancienne);
        h.assertTrue(v.horaires.equals("toujours") && v.lieu == null && !v.uneFois, "une mission d'avant la 1.0.0 : les défauts");

        // Hors de ses horaires : ni l'une ni l'autre n'est à faire maintenant.
        int heure = t.heure();
        t.missions.clear();
        h.assertTrue(Missions.donner(chef, t, "fer", "fois", 5, (heure + 2) % 24 + "-" + (heure + 3) % 24, null).ok(), "mission plus tard");
        h.assertTrue(t.missionActive() == null, "pas encore son heure");
        h.assertTrue(t.statutUnique().equals(Statut.HORS_HORAIRES), "statut : « " + t.statutUnique() + " »");
        t.discard();
        copie.discard();
        h.succeed();
    }

    /** Un joueur que son chef n'a pas autorisé ne lui donne pas de mission. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void autreJoueurRefuse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        ServerPlayer autre = h.makeMockServerPlayerInLevel();
        Travailleur t = ouvrier(h, chef, "mineur", 734, new ItemStack(Items.IRON_PICKAXE));
        Missions.Reponse r = Missions.donner(autre, t, "fer", "fois", 20, "toujours", null);
        h.assertFalse(r.ok(), "refusée (" + r.texte() + ")");
        h.assertTrue(t.missions.isEmpty(), "la file reste vide");
        h.assertTrue(Missions.donner(chef, t, "fer", "fois", 20, "toujours", null).ok(), "son chef, lui, peut");
        t.discard();
        h.succeed();
    }

    /**
     * Les boutons de la fiche : un joueur autorisé donne une mission mais ne se fait pas suivre, et plus rien s'il est en guerre
     * avec le chef ; « annuler » sur une file qui a changé ne fait rien ; le renvoi pèse sur la réputation du chef.
     */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void boutonsDeLaFiche(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        ServerPlayer ami = h.makeMockServerPlayerInLevel();
        Travailleur t = ouvrier(h, chef, "mineur", 735, new ItemStack(Items.IRON_PICKAXE));
        Courrier c = Courrier.de(h.getLevel().getServer());
        String nomAmi = ami.getGameProfile().getName();
        c.autoriser(chef.getUUID(), java.util.List.of(nomAmi), java.util.List.of());
        try {
            h.assertTrue(Missions.donner(ami, t, "fer", "fois", 5, "toujours", null).ok(), "autorisé : il donne une mission");
            Missions.action(ami, t, "suivre", new CompoundTag());
            h.assertTrue(t.mode == Travailleur.Mode.TRAVAIL, "autorisé, mais il ne se fait pas suivre (" + t.mode + ")");

            CompoundTag vieille = new CompoundTag();
            vieille.putInt("index", 0);
            vieille.putInt("n", 3); // l'écran voyait 3 missions : la file a changé depuis
            Missions.action(chef, t, "annuler", vieille);
            h.assertTrue(t.missions.size() == 1, "file changée : rien d'annulé (" + t.missions.size() + ")");
            CompoundTag juste = new CompoundTag();
            juste.putInt("index", 0);
            juste.putInt("n", 1);
            Missions.action(chef, t, "annuler", juste);
            h.assertTrue(t.missions.isEmpty(), "la bonne file : annulée");

            c.declarer(chef.getUUID(), "chef", ami.getUUID(), nomAmi);
            h.assertFalse(Missions.autorise(ami, t), "en guerre avec le chef : il ne commande plus");
            h.assertFalse(Missions.donner(ami, t, "fer", "fois", 5, "toujours", null).ok(), "en guerre : mission refusée");

            int avant = c.reputation(chef.getUUID());
            Missions.action(chef, t, "licencier", new CompoundTag());
            h.assertTrue(t.mode == Travailleur.Mode.CHOMEUR, "renvoyé");
            h.assertTrue(c.reputation(chef.getUUID()) < avant, "le renvoi pèse sur sa réputation (" + avant + " → " + c.reputation(chef.getUUID()) + ")");
        } finally {
            c.autoriser(chef.getUUID(), java.util.List.of(), java.util.List.of(nomAmi));
            c.guerres.remove(chef.getUUID());
            c.guerres.remove(ami.getUUID());
        }
        t.discard();
        h.succeed();
    }
}

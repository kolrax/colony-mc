package fr.lacolonie.pnj;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plan D (les ouvriers sur la carte Xaero), côté serveur : le chef équipé du mod reçoit la place de ses deux ouvriers,
 * et rien d'autre (ni l'ouvrier du voisin, ni le chercheur d'emploi) ; le voisin, sans le canal, ne reçoit rien ; et rien
 * ne repart tant que rien n'a bougé. Coin 912 du monde plat. Échoue sur l'ancien code : il n'envoyait aucune carte.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCarte {
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void chacunSesOuvriersSurLaCarte(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 912 * 400, 60000);
        Fantome.Joueur moi = new Fantome.Joueur(h.getLevel()); // sa connexion a le canal du mod et garde ce qu'elle reçoit
        @SuppressWarnings("removal") ServerPlayer voisin = h.makeMockServerPlayerInLevel(); // sans le canal (faux joueur de test)
        Travailleur jean = TestsBlessures.ouvrier(h, "mineur", base, base.east(2), moi.getUUID(), ItemStack.EMPTY);
        Travailleur isabeau = TestsBlessures.ouvrier(h, "bucheron", base, base.west(2), moi.getUUID(), ItemStack.EMPTY);
        Travailleur gauthier = TestsBlessures.ouvrier(h, "mineur", base, base.south(3), voisin.getUUID(), ItemStack.EMPTY);
        jean.prenom = "Jean";
        isabeau.prenom = "Isabeau";
        gauthier.prenom = "Gauthier";
        Travailleur errant = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        errant.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 4.5, 0, 0);
        errant.tirerAuSort("mineur", 1);
        errant.prenom = "Errant";
        h.getLevel().addFreshEntity(errant);
        for (Travailleur t : List.of(jean, isabeau, gauthier, errant)) t.forcerChunk();
        h.succeedWhen(() -> {
            var tous = ColoniePnj.tous(h.getLevel().getServer());
            for (Travailleur t : List.of(jean, isabeau, gauthier, errant))
                if (!tous.contains(t)) throw new net.minecraft.gametest.framework.GameTestAssertException(t.prenom + " pas encore là");
            CarteOuvriers.ENVOYE.remove(moi);
            Fantome.carte = null;
            CarteOuvriers.envoyer(h.getLevel().getServer(), List.of(moi, voisin));
            var recu = Fantome.carte;
            h.assertTrue(recu != null, "le chef a reçu la place de ses ouvriers");
            Set<String> noms = recu.stream().map(Reperes.Repere::prenom).collect(Collectors.toSet());
            h.assertTrue(noms.equals(Set.of("Jean", "Isabeau")), "seulement ses deux ouvriers : " + noms);
            var j = recu.stream().filter(r -> r.id().equals(jean.getUUID())).findFirst().orElseThrow();
            h.assertTrue(j.x() == jean.blockPosition().getX() && j.z() == jean.blockPosition().getZ()
                    && j.dim().equals("minecraft:overworld"), "à sa place : " + j + " / " + jean.blockPosition());
            h.assertTrue(CarteOuvriers.ENVOYE.get(voisin) == null, "rien au voisin sans le canal");
            Fantome.carte = null;
            CarteOuvriers.envoyer(h.getLevel().getServer(), List.of(moi, voisin));
            h.assertTrue(Fantome.carte == null, "rien ne repart tant que rien n'a bougé");
        });
    }

    /**
     * Plan E, point 5 : un ouvrier à terre est signalé sur la carte de son chef (repère rouge, « à terre »), même sans avoir
     * bougé ; relevé, son repère redevient normal. Coin 913. Échoue sur l'ancien code : le repère ne savait rien de sa chute.
     * Son propre lot, et Oriane retirée à la fin : le chef est le même joueur fantôme que dans le test d'à côté.
     */
    @GameTest(template = "vide", timeoutTicks = 200, batch = "carte_terre")
    public static void aTerreEnRougeSurLaCarte(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 913 * 400, 60000);
        Fantome.Joueur moi = new Fantome.Joueur(h.getLevel());
        Travailleur oriane = TestsBlessures.ouvrier(h, "mineur", base, base.east(2), moi.getUUID(), ItemStack.EMPTY);
        oriane.prenom = "Oriane";
        oriane.forcerChunk();
        h.succeedWhen(() -> {
            if (!ColoniePnj.tous(h.getLevel().getServer()).contains(oriane))
                throw new net.minecraft.gametest.framework.GameTestAssertException("Oriane pas encore là");
            CarteOuvriers.ENVOYE.remove(moi);
            if (oriane.aTerre) oriane.relever(null); // un essai précédent de ce même test a pu la laisser à terre
            CarteOuvriers.envoyer(h.getLevel().getServer(), List.of(moi));
            h.assertTrue(Fantome.carte != null && !sonRepere(oriane).aTerre(), "debout : " + Fantome.carte);
            Fantome.carte = null;
            oriane.tomberATerre();
            CarteOuvriers.envoyer(h.getLevel().getServer(), List.of(moi));
            h.assertTrue(Fantome.carte != null && sonRepere(oriane).aTerre(), "à terre, le chef le voit : " + Fantome.carte);
            h.assertTrue(Reperes.nom(sonRepere(oriane)).equals("Oriane (à terre)"), "son nom : " + Reperes.nom(sonRepere(oriane)));
            Fantome.carte = null;
            oriane.relever(null);
            CarteOuvriers.envoyer(h.getLevel().getServer(), List.of(moi));
            h.assertTrue(Fantome.carte != null && !sonRepere(oriane).aTerre(), "relevée, repère normal : " + Fantome.carte);
            oriane.discard();
        });
    }

    /**
     * Plan E, point 5 : le repère, écrit à la main (sept champs), revient du réseau tel qu'il est parti : chaque champ, le
     * drapeau « à terre », un prénom trop long coupé à 60 lettres par le serveur, la liste entière ; au-delà de 64 lettres,
     * le codec refuse. Rien dans le monde : échoue si l'on inverse deux champs de l'encodage.
     */
    @GameTest(template = "vide")
    public static void repereAllerRetour(GameTestHelper h) {
        UUID id = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
        String prenom = CarteOuvriers.court("É".repeat(10) + "x".repeat(200));
        h.assertTrue(prenom.length() == 60, "prénom borné à 60 : " + prenom.length());
        Reperes.Repere tombe = new Reperes.Repere(id, prenom, "minecraft:the_nether", -30_000_123, -64, 1_234_567, true);
        Reperes.Repere lu = allerRetour(h, tombe);
        h.assertTrue(lu.id().equals(id), "id : " + lu.id());
        h.assertTrue(lu.prenom().equals(prenom), "prénom : " + lu.prenom());
        h.assertTrue(lu.dim().equals("minecraft:the_nether"), "dimension : " + lu.dim());
        h.assertTrue(lu.x() == -30_000_123 && lu.y() == -64 && lu.z() == 1_234_567, "place : " + lu);
        h.assertTrue(lu.aTerre(), "le drapeau « à terre » arrive");
        Reperes.Repere debout = new Reperes.Repere(new UUID(1, 2), "Oriane", "minecraft:overworld", 5, 70, -9, false);
        Reperes.Repere deboutLu = allerRetour(h, debout);
        h.assertTrue(deboutLu.equals(debout), "debout reste debout : " + deboutLu);
        var liste = List.of(debout, new Reperes.Repere(new UUID(3, 4), "Bertrand", "minecraft:overworld", -5, 12, 9, true));
        RegistryFriendlyByteBuf b = tampon(h);
        CarteOuvriers.Paquet.CODEC.encode(b, new CarteOuvriers.Paquet(liste));
        var listeLue = CarteOuvriers.Paquet.CODEC.decode(b).liste();
        h.assertTrue(listeLue.equals(liste) && b.readableBytes() == 0, "la liste entière : " + listeLue);
        Reperes.Repere tropLong = new Reperes.Repere(new UUID(5, 6), "x".repeat(65), "minecraft:overworld", 0, 0, 0, false);
        boolean refuse = false;
        try {
            CarteOuvriers.Paquet.REPERE.encode(tampon(h), tropLong);
        } catch (RuntimeException e) {
            refuse = true;
        }
        h.assertTrue(refuse, "au-delà de 64 lettres, le codec refuse (d'où la coupe à 60 du serveur)");
        h.succeed();
    }

    static RegistryFriendlyByteBuf tampon(GameTestHelper h) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
    }

    static Reperes.Repere allerRetour(GameTestHelper h, Reperes.Repere r) {
        RegistryFriendlyByteBuf b = tampon(h);
        CarteOuvriers.Paquet.REPERE.encode(b, r);
        Reperes.Repere lu = CarteOuvriers.Paquet.REPERE.decode(b);
        h.assertTrue(b.readableBytes() == 0, "rien ne reste dans le tampon : " + b.readableBytes());
        return lu;
    }

    /** Le repère de cet ouvrier dans la dernière liste reçue (le joueur fantôme peut avoir d'autres ouvriers dans d'autres tests). */
    static Reperes.Repere sonRepere(Travailleur t) {
        return Fantome.carte.stream().filter(r -> r.id().equals(t.getUUID())).findFirst()
                .orElseThrow(() -> new net.minecraft.gametest.framework.GameTestAssertException(t.prenom + " absent de la carte"));
    }
}

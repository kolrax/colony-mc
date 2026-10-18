package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
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

    /** Le repère de cet ouvrier dans la dernière liste reçue (le joueur fantôme peut avoir d'autres ouvriers dans d'autres tests). */
    static Reperes.Repere sonRepere(Travailleur t) {
        return Fantome.carte.stream().filter(r -> r.id().equals(t.getUUID())).findFirst()
                .orElseThrow(() -> new net.minecraft.gametest.framework.GameTestAssertException(t.prenom + " absent de la carte"));
    }
}

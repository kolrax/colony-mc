package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Amitiés, inimitiés et couples entre ouvriers d'un même chef (lot 7, {@link Relations}, {@link Liens}). Coins 262 et 263 (le dernier test est dans le lot « conversations »). */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRelations {
    /** La note de {@code a} pour {@code b}, posée telle quelle (atteinte à 80 le jour {@code depuis}). */
    static void poser(Travailleur a, Travailleur b, int note, long depuis) {
        Liens l = Relations.de(a);
        l.liens.put(b.getStringUUID(), new Liens.Lien(b.prenom, note, depuis, false));
        Relations.garder(a, l);
    }

    /**
     * Deux ouvriers du même chef, amis à 85 l'un pour l'autre depuis deux jours, travaillent côte à côte : au bilan du
     * moral, ils se mettent en couple, l'un l'annonce, le chef reçoit une lettre ; à la question, chacun nomme l'autre.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void grandsAmisEnCouple(GameTestHelper h) {
        BlockPos base = TestsJeu.sol(h, 262 * 400, 60000);
        UUID chef = UUID.randomUUID();
        Travailleur t1 = Travailleur.auTravail(h.getLevel(), "mineur", base, Vec3.atBottomCenterOf(base.west(2)), chef, ItemStack.EMPTY);
        Travailleur t2 = Travailleur.auTravail(h.getLevel(), "bucheron", base, Vec3.atBottomCenterOf(base.west(4)), chef, ItemStack.EMPTY);
        for (Travailleur t : java.util.List.of(t1, t2)) { t.mode = Travailleur.Mode.ATTENDRE; t.forcerChunk(); } // ils restent côte à côte
        for (Travailleur t : java.util.List.of(t1, t2)) Courrier.de(h.getLevel().getServer()).inscrire(t); // à l'effectif : leurs liens ne sont pas purgés
        long jour = t1.jour();
        poser(t1, t2, 85, jour - 2);
        poser(t2, t1, 85, jour - 2);
        long passe = h.getLevel().getGameTime() - Reglages.actuel.moral_minutes_bilan * 1200L;
        t1.memoire.putLong("bilan_le", passe); // l'heure du bilan est passée pour les deux
        t2.memoire.putLong("bilan_le", passe);
        h.succeedWhen(() -> {
            Liens l1 = Relations.de(t1), l2 = Relations.de(t2);
            h.assertTrue(t2.getStringUUID().equals(l1.conjoint()) && t1.getStringUUID().equals(l2.conjoint()),
                    "en couple l'un avec l'autre (" + t1.memoire.getString(Relations.CLE) + " / " + t2.memoire.getString(Relations.CLE) + ")");
            h.assertTrue(TestsMineur.lettre(t1, "couple") || TestsMineur.lettre(t2, "couple"), "le chef reçoit une lettre");
            h.assertTrue(t1.paroles.stream().anyMatch(p -> Vie.vient("couple_annonce", p)) || t2.paroles.stream().anyMatch(p -> Vie.vient("couple_annonce", p)),
                    "l'un des deux l'annonce : " + t1.paroles + " / " + t2.paroles);
            h.assertTrue(Relations.reponse(t1, true).contains(t2.prenom), "à la question, il nomme l'autre : " + Relations.reponse(t1, true));
            t1.discard();
            t2.discard();
        });
    }

    /**
     * Avec l'IA : le chef demande « Qui sont tes amis ? » ; l'IA répond sans action et invente des noms. C'est le mod qui
     * répond, avec le vrai nom de son ami.
     */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void lIANInventePasSesAmis(GameTestHelper h) {
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        BlockPos base = TestsJeu.sol(h, 263 * 400, 60000);
        Travailleur t = Travailleur.auTravail(h.getLevel(), "mineur", base, Vec3.atBottomCenterOf(base.west(2)), chef.getUUID(), ItemStack.EMPTY);
        Travailleur ami = Travailleur.auTravail(h.getLevel(), "bucheron", base, Vec3.atBottomCenterOf(base.west(4)), chef.getUUID(), ItemStack.EMPTY);
        for (Travailleur x : java.util.List.of(t, ami)) { x.mode = Travailleur.Mode.ATTENDRE; x.forcerChunk(); }
        ami.prenom = "Gertrude";
        poser(t, ami, 70, Liens.JAMAIS);
        boolean[] fait = {false};
        h.succeedWhen(() -> {
            if (!fait[0]) {
                fait[0] = true;
                var d = new ReponsesIA.Decision("Mes amis ? Robert et Mahaut, bien sûr.", "calme", java.util.List.of());
                Dialogue.repondre(chef, "Qui sont tes amis ?", java.util.List.of(t), java.util.List.of(d));
            }
            var conv = t.memoire.getList("conv", net.minecraft.nbt.Tag.TAG_STRING);
            String dit = conv.isEmpty() ? "" : conv.getString(conv.size() - 1);
            h.assertTrue(dit.startsWith("Toi : ") && dit.contains("Gertrude") && !dit.contains("Robert"), "le mod répond, avec son vrai ami : « " + dit + " »");
            t.discard();
            ami.discard();
        });
    }

    /** Deux ouvriers du même chef qui se détestent attendent au poste côte à côte : jamais ils ne causent ensemble, l'un le dit. */
    @GameTest(template = "vide", timeoutTicks = 500, batch = "conversations")
    public static void ennemisNeCausentPas(GameTestHelper h) {
        UUID chef = UUID.randomUUID();
        Travailleur t1 = TestsConversations.auPoste(h, "mineur", 2, 3, chef), t2 = TestsConversations.auPoste(h, "bucheron", 5, 3, chef);
        poser(t1, t2, -60, Liens.JAMAIS);
        poser(t2, t1, -60, Liens.JAMAIS);
        h.onEachTick(() -> h.assertTrue(TestsConversations.causerie(t1) == null && TestsConversations.causerie(t2) == null, "deux ennemis ne doivent pas causer ensemble"));
        h.runAtTickTime(400, () -> {
            h.assertTrue(t1.paroles.stream().anyMatch(p -> Vie.vient("inimitie_evite", p)) || t2.paroles.stream().anyMatch(p -> Vie.vient("inimitie_evite", p)),
                    "l'un des deux se détourne en le disant : " + t1.paroles + " / " + t2.paroles);
            t1.discard();
            t2.discard();
            h.succeed();
        });
    }
}

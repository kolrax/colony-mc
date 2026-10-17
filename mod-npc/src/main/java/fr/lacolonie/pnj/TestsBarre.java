package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * La barre de commandes ({@link BarreCommandes#executer}) : une ligne choisie lance l'action du répertoire sur
 * l'ouvrier ; une action inconnue, ou un joueur que le chef n'a pas autorisé, ne font rien. Un ouvrier sans IA de
 * déplacement, ses joueurs à côté de lui.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsBarre {
    /** Un mineur de {@code chef}, au travail, et le chef à deux pas. */
    static Travailleur mineur(GameTestHelper h, ServerPlayer chef) {
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(3.5, 2, 4.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort("mineur", 1);
        t.setNoAi(true);
        t.chef = chef.getUUID();
        t.chefNom = chef.getName().getString();
        t.prenom = "Raoul";
        t.mode = Travailleur.Mode.TRAVAIL;
        t.debut = t.fin = 0;
        t.prochainePaie = System.currentTimeMillis() + 3_600_000L;
        h.getLevel().addFreshEntity(t);
        chef.moveTo(p.x + 2, p.y, p.z, 0, 0);
        return t;
    }

    static String vu(Travailleur t) { return "consigne " + (t.consigne == null ? "-" : t.consigne.id) + ", paroles " + t.paroles; }

    /** Son chef choisit « Garde à vous » : il se met au garde-à-vous, et le dit. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void laBarreLanceLAction(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        String dit = BarreCommandes.executer(chef, t, "garde_a_vous");
        h.assertTrue(t.consigne != null && t.consigne.id.equals("garde_a_vous"), "au garde-à-vous : " + vu(t));
        h.assertTrue(!dit.isBlank() && t.paroles.contains(dit), "il répond : " + dit + ", " + vu(t));
        t.discard();
        h.succeed();
    }

    /** Une action qui n'est pas au répertoire, ou un joueur que le chef n'a pas autorisé : rien. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void inconnuOuNonAutoriseNeFaitRien(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        autre.moveTo(t.getX() - 2, t.getY(), t.getZ(), 0, 0);
        h.assertTrue(BarreCommandes.executer(chef, t, "danse_du_ventre_libre").isEmpty() && t.consigne == null, "id inconnu : " + vu(t));
        h.assertTrue(BarreCommandes.executer(chef, t, "Garde à vous").isEmpty() && t.consigne == null, "une phrase n'est pas un id : " + vu(t));
        BarreCommandes.executer(autre, t, "garde_a_vous");
        h.assertTrue(t.consigne == null, "un inconnu n'est pas obéi : " + vu(t));
        t.discard();
        h.succeed();
    }

    /** Un joueur autorisé par le chef est obéi, sauf pour ce que seul le chef demande (donner ses affaires). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void unAutoriseEstObeiSaufReserve(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        autre.moveTo(t.getX() - 2, t.getY(), t.getZ(), 0, 0);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.autoriser(chef.getUUID(), List.of(autre.getName().getString()), List.of());
        try {
            BarreCommandes.executer(autre, t, "donner_chef");
            h.assertTrue(t.consigne == null, "donner ses affaires : au chef seul : " + vu(t));
            BarreCommandes.executer(autre, t, "garde_a_vous");
            h.assertTrue(t.consigne != null && t.consigne.id.equals("garde_a_vous"), "l'autorisé est obéi : " + vu(t));
        } finally {
            c.commandants.remove(chef.getUUID());
            t.discard();
        }
        h.succeed();
    }
}

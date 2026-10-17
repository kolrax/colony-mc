package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Lot 5, le guérisseur (cahier 1.11 et 4.2) : il relève l'ouvrier tombé avec un pain du dépôt, panse un blessé très grave
 * (une seule fois par blessure), ne lâche pas un ordre de son chef, et soigne qui on lui nomme. Coins 150 à 153 du monde plat.
 * Tous échouent sur l'ancien code : le métier n'existait pas.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsGuerisseur {
    /** Le guérisseur Isabeau et le mineur Jean, du même chef, autour du même poste ; trois pains au dépôt. */
    static Travailleur[] colonie(GameTestHelper h, int coin, UUID chef, BlockPos ouJean) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur g = TestsBlessures.ouvrier(h, "guerisseur", base, base.east(2), chef, ItemStack.EMPTY);
        Travailleur b = TestsBlessures.ouvrier(h, "mineur", base, base.offset(ouJean), chef, new ItemStack(Items.IRON_PICKAXE));
        g.prenom = "Isabeau";
        b.prenom = "Jean";
        g.forcerChunk();
        b.forcerChunk();
        TestsBucheron.depot(g).depot.setItem(0, new ItemStack(Items.BREAD, 3));
        return new Travailleur[]{g, b};
    }

    /**
     * Jean tombe à terre à 12 blocs (il n'a plus qu'une minute à vivre) : Isabeau, qui n'a rien sur elle, passe prendre un pain
     * au dépôt (3 → 2), accourt, s'agenouille et le relève avant sa mort ; le pain est mangé, chacun dit un mot.
     */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void releveUnOuvrierATerre(GameTestHelper h) {
        Travailleur[] e = colonie(h, 150, UUID.randomUUID(), new BlockPos(0, 0, 12));
        Travailleur g = e[0], b = e[1];
        long[] tombe = {-1};
        boolean[] mort = {false};
        h.onEachTick(() -> {
            if (tombe[0] < 0 && h.getTick() > 20) {
                b.hurt(b.damageSources().generic(), 100);
                b.aTerreTicks = 60 * 20;
                tombe[0] = h.getTick();
            }
            if (!b.isAlive()) mort[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(tombe[0] >= 0, "tombé");
            h.assertFalse(mort[0], "mort avant d'être relevé");
            h.assertFalse(b.aTerre, "relevé (" + TestsJeu.etat(g, g.bureau) + ")");
            h.assertTrue(TestsBucheron.compte(TestsBucheron.depot(g), Items.BREAD) == 2, "un pain pris au dépôt : " + TestsBucheron.compte(TestsBucheron.depot(g), Items.BREAD));
            h.assertTrue(g.compter(Guerisseur.REMEDE) == 0, "le pain est mangé : " + g.compter(Guerisseur.REMEDE));
            h.assertTrue(TestsVie.dites(b, "releve") == 1, "Jean dit qu'il est debout (" + b.paroles + ")");
            h.assertTrue(TestsVie.dites(g, "guerisseur_soigne") >= 1, "Isabeau lui dit un mot (" + g.paroles + ")");
            g.discard();
            b.discard();
        });
    }

    /**
     * Jean, très gravement blessé et à 8 points de vie, se repose au poste : Isabeau le panse ; sa blessure n'est plus que
     * grave, il reprend un tiers de sa vie, et tous deux le disent. Dix secondes après, elle ne l'a pas pansé une seconde
     * fois (toujours grave).
     */
    @GameTest(template = "vide", timeoutTicks = 1400)
    public static void panseUnBlesseTresGrave(GameTestHelper h) {
        Travailleur[] e = colonie(h, 151, UUID.randomUUID(), new BlockPos(-6, 0, 0));
        Travailleur g = e[0], b = e[1];
        Blessures.noter(b, Gravite.TRES_GRAVE);
        b.setHealth(8);
        long[] panse = {-1};
        float[] vie = {0};
        h.onEachTick(() -> {
            if (panse[0] < 0 && Blessures.gravite(b) == Gravite.GRAVE) { panse[0] = h.getTick(); vie[0] = b.getHealth(); }
        });
        h.succeedWhen(() -> {
            h.assertTrue(panse[0] >= 0, "pansé (" + Blessures.gravite(b) + ", " + TestsJeu.etat(g, g.bureau) + ")");
            h.assertTrue(vie[0] >= 8 + b.getMaxHealth() / 3 - 0.01f, "un tiers de sa vie rendu : " + vie[0] + "/" + b.getMaxHealth());
            h.assertTrue(TestsVie.dites(b, "soigne") == 1, "Jean la remercie (" + b.paroles + ")");
            h.assertTrue(TestsVie.dites(g, "guerisseur_soigne") == 1, "Isabeau lui dit un mot (" + g.paroles + ")");
            h.assertTrue(h.getTick() - panse[0] >= 200, "dix secondes après");
            h.assertTrue(Blessures.gravite(b) == Gravite.GRAVE, "pansé une seule fois : " + Blessures.gravite(b));
            g.discard();
            b.discard();
        });
    }

    /**
     * Isabeau est au garde-à-vous sur ordre du chef quand Jean tombe à 10 blocs : elle ne bouge pas (15 s, bien plus que sa revue
     * de 5 s). « Repos » : elle va le relever.
     */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void neQuittePasUnOrdreDuChef(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = colonie(h, 152, chef.getUUID(), new BlockPos(0, 0, 10));
        Travailleur g = e[0], b = e[1];
        Vec3 c = Vec3.atBottomCenterOf(g.bureau);
        chef.moveTo(c.x, c.y, c.z);
        Vec3[] place = {null};
        Essais.etapes(h, chef, e,
                () -> g.parler(chef, "Garde à vous"),
                () -> h.assertTrue(g.consigne != null && g.consigne.id.equals("garde_a_vous"), "au garde-à-vous (" + TestsRepertoire.c(g) + ")"),
                () -> { place[0] = g.position(); b.hurt(b.damageSources().generic(), 100); },
                Essais.attendre(h, 300),
                () -> {
                    h.assertTrue(b.aTerre && b.isAlive(), "Jean est toujours à terre");
                    h.assertTrue(g.consigne != null && g.consigne.id.equals("garde_a_vous"), "elle tient l'ordre (" + TestsRepertoire.c(g) + ")");
                    h.assertTrue(g.position().distanceTo(place[0]) < 1, "elle n'a pas bougé : " + g.position().distanceTo(place[0]));
                },
                () -> g.parler(chef, "Repos"),
                () -> h.assertFalse(b.aTerre, "après « repos », elle le relève (" + TestsJeu.etat(g, g.bureau) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Jean n'a qu'une blessure légère : seule, Isabeau ne le panse pas (10 s). « Soigne Jean » : elle va le panser, la
     * blessure est guérie, et l'ordre est fini.
     */
    @GameTest(template = "vide", timeoutTicks = 1400)
    public static void soigneQuiOnLuiNomme(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = colonie(h, 153, chef.getUUID(), new BlockPos(-8, 0, 0));
        Travailleur g = e[0], b = e[1];
        Vec3 c = Vec3.atBottomCenterOf(g.bureau);
        chef.moveTo(c.x, c.y, c.z);
        Blessures.noter(b, Gravite.LEGERE);
        b.setHealth(10);
        Essais.etapes(h, chef, e,
                Essais.attendre(h, 200),
                () -> h.assertTrue(Blessures.gravite(b) == Gravite.LEGERE, "une blessure légère : elle ne la panse pas seule (" + Blessures.gravite(b) + ")"),
                () -> g.parler(chef, "Soigne Jean"),
                () -> h.assertTrue(Blessures.gravite(b) == Gravite.AUCUNE && b.getHealth() > 10, "pansé sur ordre (" + Blessures.gravite(b) + ", " + b.getHealth()
                        + ", " + TestsRepertoire.c(g) + ", " + TestsJeu.etat(g, g.bureau) + ")"),
                () -> h.assertTrue(g.consigne == null, "l'ordre est fini (" + TestsRepertoire.c(g) + ")"),
                () -> TestsRepertoire.fin(e));
    }

    /**
     * Isabeau est hors service, au repos au poste. Jean tombe à 6 blocs du poste, un monstre (immobile) à 4 blocs de lui : elle
     * ne vient pas. Le monstre parti, sa revue de repos la fait lever, et elle le relève. Avant : jamais (la revue n'avait lieu
     * qu'au travail, et l'alerte de la chute était passée).
     */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void releveAuReposQuandLeMonstreEstParti(GameTestHelper h) {
        Travailleur[] e = colonie(h, 154, UUID.randomUUID(), new BlockPos(-6, 0, 0));
        Travailleur g = e[0], b = e[1];
        g.temperament = "calme"; // ni elle ne charge le monstre, ni elle ne le fuit (Bravoure)
        int heure = TestsJeu.heure(h);
        g.debut = (heure + 2) % 24; // hors service maintenant
        g.fin = (heure + 3) % 24;
        net.minecraft.world.entity.monster.Husk monstre = net.minecraft.world.entity.EntityType.HUSK.create(h.getLevel());
        Vec3 m = Vec3.atBottomCenterOf(b.blockPosition().west(4));
        monstre.moveTo(m.x, m.y, m.z);
        monstre.setNoAi(true);
        monstre.setPersistenceRequired();
        h.getLevel().addFreshEntity(monstre);
        long[] tombe = {-1}, parti = {-1};
        boolean[] venue = {false};
        h.onEachTick(() -> {
            if (tombe[0] < 0 && h.getTick() > 20) { b.hurt(b.damageSources().generic(), 100); tombe[0] = h.getTick(); }
            if (tombe[0] >= 0 && parti[0] < 0) {
                if (g.action instanceof Guerisseur.Soin) venue[0] = true;
                if (h.getTick() - tombe[0] >= 300) { monstre.discard(); parti[0] = h.getTick(); }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(parti[0] >= 0, "le monstre est parti");
            h.assertFalse(g.enService(), "hors service");
            h.assertFalse(venue[0], "elle n'est pas venue tant que le monstre était là");
            h.assertFalse(b.aTerre, "le monstre parti, elle le relève (" + TestsJeu.etat(g, g.bureau) + ")");
            g.discard();
            b.discard();
        });
    }
}

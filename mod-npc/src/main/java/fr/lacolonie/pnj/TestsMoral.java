package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 5, le moral au fil des jours (cahier 5.2 et 5.3) : bilan des causes ({@link Moral}, {@link Humeur}), lettre au chef,
 * repas toujours pareils, démission après deux lettres, lenteur. Chaque test dans son coin du monde plat (coins 200 à 203).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMoral {
    static Travailleur mineur(GameTestHelper h, int coin, BlockPos[] base, int dx, ItemStack outil) {
        if (base[0] == null) base[0] = TestsJeu.sol(h, coin * 400, 60000);
        Travailleur t = TestsJeu.employe(h, "mineur", base[0], Vec3.atBottomCenterOf(base[0].west(dx)), outil);
        t.forcerChunk();
        return t;
    }

    /**
     * Deux paies impayées, moral déjà bas : à l'heure du bilan (le cerveau l'appelle de lui-même), le moral baisse, la paie
     * est la cause principale, et il écrit au chef une lettre qui le dit.
     */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void impayesLettreEtMoralEnBaisse(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = mineur(h, 200, b, 2, new ItemStack(Items.IRON_PICKAXE));
        t.impayes = 2;
        t.moral = 32;
        t.memoire.putLong("bilan_le", h.getLevel().getGameTime() - Reglages.actuel.moral_minutes_bilan * 1200L); // l'heure du bilan est passée
        h.succeedWhen(() -> {
            Courrier c = Courrier.de(t.level().getServer());
            h.assertTrue(TestsMineur.lettre(t, "moral_bas"), "une lettre au chef (" + TestsJeu.etat(t, t.bureau) + ", moral " + t.moral + ")");
            h.assertTrue(t.moral < 30, "moral en baisse : " + t.moral);
            h.assertTrue("PAIE".equals(t.memoire.getString("bilan_cause")), "la paie d'abord : " + t.memoire.getString("bilan_cause"));
            boolean ditLaPaie = c.vols.stream().anyMatch(v -> v.lettre.type.equals("moral_bas") && v.lettre.texte.contains("paie"))
                    || c.boite(t.chef).stream().anyMatch(x -> x.type.equals("moral_bas") && x.texte.contains("paie"))
                    || t.lettresADeposer.stream().anyMatch(x -> x.type.equals("moral_bas") && x.texte.contains("paie"));
            h.assertTrue(ditLaPaie, "la lettre parle de la paie");
            h.assertTrue(Messages.etat(t).contains("paie"), "au chef qui demande, il dit ce qui lui pèse : " + Messages.etat(t));
            t.discard();
        });
    }

    /** Toujours du pain : ses repas sont retenus, et le bilan le lui fait payer ; un repas varié, au contraire, le réjouit. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void toujoursLeMemeRepas(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur pareil = mineur(h, 201, b, 2, new ItemStack(Items.BREAD, 3));
        Travailleur varie = mineur(h, 201, b, 4, new ItemStack(Items.BREAD));
        varie.inventaire.addItem(new ItemStack(Items.BAKED_POTATO));
        varie.inventaire.addItem(new ItemStack(Items.APPLE));
        h.runAtTickTime(h.getTick() + 5, () -> {
            for (Travailleur t : new Travailleur[]{pareil, varie}) {
                t.faim = 0;
                for (int i = 0; i < 3; i++) t.manger();
                t.moral = 50;
            }
            Moral.Bilan bp = Humeur.bilan(pareil), bv = Humeur.bilan(varie);
            h.assertTrue(Moral.lire(pareil.memoire.getString("repas")).equals(java.util.List.of("bread", "bread", "bread")), "trois pains retenus : " + pareil.memoire.getString("repas"));
            h.assertTrue(bp.de(Moral.Cause.REPAS) < 0, "toujours pareil : moins bien (" + bp + ")");
            h.assertTrue(bv.de(Moral.Cause.REPAS) > 0, "varié : mieux (" + bv + ")");
            h.assertTrue(pareil.moral < 50 && pareil.moral < varie.moral, "moral " + pareil.moral + " contre " + varie.moral);
            pareil.discard();
            varie.discard();
            h.succeed();
        });
    }

    /**
     * Mal payé, sans lit, au plus bas des jours durant : une première lettre, une deuxième le lendemain, puis seulement
     * après trois jours (12 bilans) il décide de partir ; il rend sa pioche et son épée au dépôt, dit au revoir, écrit au chef
     * et redevient chercheur d'emploi, le sac vide.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void demissionApresDeuxLettres(GameTestHelper h) {
        BlockPos[] b = {TestsJeu.sol(h, 202 * 400, 60000)};
        // Un vrai chef connecté (faux joueur, loin de lui) : il a pu lire les lettres, le départ est permis.
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = Travailleur.auTravail(h.getLevel(), "mineur", b[0], Vec3.atBottomCenterOf(b[0].west(3)), chef.getUUID(), ItemStack.EMPTY);
        t.forcerChunk();
        t.inventaire.addItem(new ItemStack(Items.IRON_SWORD));
        t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE)); // en main
        t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)); // sur le dos
        t.impayes = 3;
        t.moral = 5;
        int[] bilans = {0}, avertisAuDepart = {-1}, bilansAuDepart = {-1};
        boolean[] parti = {false}, sacVideAuDepart = {false};
        h.onEachTick(() -> {
            if (parti[0]) return;
            if (t.mode == Travailleur.Mode.CHOMEUR) {
                parti[0] = true;
                sacVideAuDepart[0] = true;
                for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (!t.inventaire.getItem(i).isEmpty()) sacVideAuDepart[0] = false;
                if (!t.getMainHandItem().isEmpty() || !t.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()) sacVideAuDepart[0] = false;
                return;
            }
            if (t.memoire.getBoolean("demission")) {
                if (avertisAuDepart[0] < 0) { avertisAuDepart[0] = t.memoire.getInt("avertis"); bilansAuDepart[0] = bilans[0]; }
                return;
            }
            if (h.getTick() % 10 == 0) { Humeur.bilan(t); bilans[0]++; } // un bilan tous les 10 ticks : des jours en accéléré
        });
        h.succeedWhen(() -> {
            h.assertTrue(avertisAuDepart[0] >= 2, "deux lettres avant de partir (" + avertisAuDepart[0] + ")");
            h.assertTrue(bilansAuDepart[0] >= 3 * Humeur.bilansParJour(), "plusieurs jours au plus bas avant de partir (" + bilansAuDepart[0] + " bilans)");
            h.assertTrue(parti[0], "il est parti (" + TestsJeu.etat(t, b[0]) + ")");
            h.assertTrue(sacVideAuDepart[0], "rien du dépôt dans son sac, ni en main, ni sur le dos");
            Bureau.Entite d = (Bureau.Entite) h.getLevel().getBlockEntity(b[0]);
            h.assertTrue(TestsBucheron.compte(d, Items.IRON_PICKAXE) == 1 && TestsBucheron.compte(d, Items.IRON_SWORD) == 1
                    && TestsBucheron.compte(d, Items.IRON_CHESTPLATE) == 1, "pioche (en main), épée et plastron (porté) rendus au dépôt");
            h.assertTrue(t.moral == Obeissance.DEPART && !t.memoire.contains("demission") && !t.memoire.contains("avertis"), "son moral de ce chef-là oublié");
            h.assertTrue(TestsVie.dites(t, "demission") == 1, "il dit au revoir (" + t.paroles + ")");
            h.assertTrue(t.chef == null && t.bureau == null, "chercheur d'emploi");
            t.discard();
            chef.discard();
        });
    }

    /** Moral au plus bas : il marche plus lentement qu'un ouvrier content ; remonté, il retrouve son pas. */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void moralBasPlusLent(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur triste = mineur(h, 203, b, 2, ItemStack.EMPTY), content = mineur(h, 203, b, 4, ItemStack.EMPTY);
        triste.moral = 10;
        content.moral = 70;
        h.runAtTickTime(h.getTick() + 60, () -> {
            double vt = triste.getAttributeValue(Attributes.MOVEMENT_SPEED), vc = content.getAttributeValue(Attributes.MOVEMENT_SPEED);
            h.assertTrue(vt < vc * 0.9, "plus lent : " + vt + " contre " + vc);
            h.assertTrue(triste.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Humeur.LENTEUR) != null, "la lenteur du moral");
            triste.moral = 70;
        });
        h.runAtTickTime(h.getTick() + 120, () -> {
            h.assertTrue(triste.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(Humeur.LENTEUR) == null, "moral remonté : plus de lenteur");
            triste.discard();
            content.discard();
            h.succeed();
        });
    }
}

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
 * repas toujours pareils, démission après deux lettres, lenteur ; au plus bas (2e partie) : vol, sabotage, fuite avec un butin,
 * avertissements puis révolte contre le chef seul. Chaque test dans son coin du monde plat (coins 200 à 207).
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
        t.temperament = "calme"; // un calme démissionne toujours en règle (les autres peuvent fuir avec un butin : fuiteAvecUnButin)
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

    // --- Méfaits au plus bas (2e partie) ---------------------------------------------------------------------

    /** Son butin caché, sans y toucher : {objet → nombre}. */
    static java.util.Map<net.minecraft.world.item.Item, Integer> butin(Travailleur t) {
        java.util.Map<net.minecraft.world.item.Item, Integer> m = new java.util.HashMap<>();
        for (var x : t.memoire.getList("butin", net.minecraft.nbt.Tag.TAG_COMPOUND))
            ItemStack.parse(t.registryAccess(), x).ifPresent(s -> m.merge(s.getItem(), s.getCount(), Integer::sum));
        return m;
    }

    /** Une lettre de ce type, partie vers ce chef (même s'il n'est plus son ouvrier). */
    static boolean lettrePour(GameTestHelper h, java.util.UUID chef, String type) {
        Courrier c = Courrier.de(h.getLevel().getServer());
        return c.vols.stream().anyMatch(v -> v.lettre.type.equals(type) && chef.equals(v.chef)) || c.boite(chef).stream().anyMatch(x -> x.type.equals(type));
    }

    /**
     * Un fourbe au plus bas, après une première lettre : au fil des bilans (appelés comme le cerveau le ferait), il finit par
     * voler au dépôt un peu de pain (au plus 3), jamais les diamants ni l'épée, et le cache (pas dans son sac, qu'il range au
     * dépôt) ; à qui lui demande comment il va, il finit par se trahir.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void volDiscretAuDepot(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = mineur(h, 204, b, 2, new ItemStack(Items.IRON_PICKAXE));
        t.temperament = "fourbe";
        t.memoire.putInt("avertis", 1); // il a déjà écrit au chef que ça n'allait pas
        Bureau.Entite d = (Bureau.Entite) h.getLevel().getBlockEntity(b[0]);
        d.depot.addItem(new ItemStack(Items.BREAD, 10));
        d.depot.addItem(new ItemStack(Items.DIAMOND, 5));
        d.depot.addItem(new ItemStack(Items.IRON_SWORD));
        int[] bilans = {0};
        h.onEachTick(() -> {
            if (h.getTick() % 10 != 0 || !butin(t).isEmpty() || t.memoire.contains("vol_ou") || t.action instanceof Humeur.Voler) return;
            t.moral = 5;
            Humeur.bilan(t);
            bilans[0]++;
        });
        h.succeedWhen(() -> {
            var pris = butin(t);
            h.assertTrue(!pris.isEmpty(), "il a volé (" + bilans[0] + " bilans, " + TestsJeu.etat(t, b[0]) + ")");
            int pain = pris.getOrDefault(Items.BREAD, 0);
            h.assertTrue(pris.size() == 1 && pain >= 1 && pain <= Moral.VOL_COUP, "un peu de pain, rien d'autre : " + pris);
            h.assertTrue(TestsBucheron.compte(d, Items.BREAD) == 10 - pain && TestsBucheron.compte(d, Items.DIAMOND) == 5
                    && TestsBucheron.compte(d, Items.IRON_SWORD) == 1, "le reste du dépôt intact");
            h.assertTrue(t.compter(Actions.Filtre.de("minecraft:bread")) == 0, "caché, pas dans son sac");
            h.assertTrue(t.memoire.getInt("vols") == pain, "compté dans ses vols du jour");
            boolean trahi = false;
            for (int i = 0; i < 40 && !trahi; i++) trahi = Vie.vient("butin_cache", Humeur.aveu(t));
            h.assertTrue(trahi, "il se trahit quand on lui demande comment il va");
            t.discard();
        });
    }

    /**
     * Le sabotage : une torche près du poste, arrachée (elle tombe à terre, il suffit de la reposer) ; rien d'autre de
     * cassé. Plus tard, il l'avoue au chef qui lui demande comment il va.
     */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void sabotageDUneTorche(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = mineur(h, 205, b, 2, new ItemStack(Items.IRON_PICKAXE));
        BlockPos torche = b[0].east(3);
        h.getLevel().setBlockAndUpdate(torche, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
        h.runAtTickTime(h.getTick() + 5, () -> {
            BlockPos cible = Humeur.cibleSabotage(t);
            h.assertTrue(torche.equals(cible), "il a repéré la torche : " + cible);
            t.memoire.putLong("sabotage_ou", cible.asLong()); // décidé au bilan (Moral.mefait : SABOTAGE)
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(torche).isAir(), "la torche arrachée (" + TestsJeu.etat(t, b[0]) + ")");
            h.assertTrue(h.getLevel().getBlockState(b[0]).is(ColoniePnj.BUREAU.get()), "le poste intact");
            h.assertTrue(!h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(torche).inflate(4),
                    e -> e.getItem().is(Items.TORCH)).isEmpty(), "la torche est à terre, à reposer");
            h.assertTrue("la torche".equals(t.memoire.getString("sabotage")), "il s'en souvient");
            String aveu = "";
            for (int i = 0; i < 40 && aveu.isEmpty(); i++) aveu = Humeur.aveu(t);
            h.assertTrue(Vie.vient("sabotage_avoue", aveu) && !t.memoire.contains("sabotage"), "il l'avoue, une fois : " + aveu);
            t.discard();
        });
    }

    /**
     * Décidé à partir, en douce : il rend d'abord au dépôt tout ce qu'il a sur lui (sa pioche en diamant, son plastron, son
     * charbon), prend un peu de pain (au plus 3), dit tout bas qu'il file, marche loin du poste, écrit une lettre d'adieu au
     * chef et redevient chercheur d'emploi avec ce seul butin.
     */
    @GameTest(template = "vide", timeoutTicks = 1600)
    public static void fuiteAvecUnButin(GameTestHelper h) {
        BlockPos[] b = {TestsJeu.sol(h, 206 * 400, 60000)};
        java.util.UUID chef = java.util.UUID.randomUUID();
        Travailleur t = Travailleur.auTravail(h.getLevel(), "mineur", b[0], Vec3.atBottomCenterOf(b[0].west(2)), chef, new ItemStack(Items.COAL, 5));
        t.forcerChunk();
        t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        t.temperament = "fourbe";
        t.moral = 5;
        Bureau.Entite d = (Bureau.Entite) h.getLevel().getBlockEntity(b[0]);
        d.depot.addItem(new ItemStack(Items.BREAD, 10));
        t.memoire.putBoolean("demission", true); // décidé au bilan (Humeur.suite, Moral.fuit)
        t.memoire.putBoolean("fuite", true);
        h.succeedWhen(() -> {
            h.assertTrue(t.mode == Travailleur.Mode.CHOMEUR, "parti (" + TestsJeu.etat(t, b[0]) + ")");
            var pris = butin(t); // un peu de pain, ou du charbon qu'il vient de rendre : rien d'autre
            int pain = pris.getOrDefault(Items.BREAD, 0), charbon = pris.getOrDefault(Items.COAL, 0);
            h.assertTrue(pain + charbon >= 1 && pain + charbon <= Moral.VOL_COUP && pris.size() == 1, "un petit butin : " + pris);
            h.assertTrue(TestsBucheron.compte(d, Items.BREAD) == 10 - pain, "le reste du pain au dépôt");
            h.assertTrue(Humeur.vide(t), "il ne part qu'avec son butin caché : rien en main, sur le dos ni dans le sac");
            h.assertTrue(TestsBucheron.compte(d, Items.DIAMOND_PICKAXE) == 1 && TestsBucheron.compte(d, Items.IRON_CHESTPLATE) == 1
                    && TestsBucheron.compte(d, Items.COAL) == 5 - charbon, "pioche, plastron et charbon rendus au dépôt");
            h.assertTrue(TestsJeu.distance(t, b[0]) >= 40, "loin du poste : " + TestsJeu.distance(t, b[0]));
            h.assertTrue(TestsVie.dites(t, "fuite_nuit") == 1, "il file en douce (" + t.paroles + ")");
            h.assertTrue(lettrePour(h, chef, "fuite"), "une lettre d'adieu au chef");
            h.assertTrue(t.chef == null && !t.memoire.contains("fuite"), "chercheur d'emploi");
            t.discard();
        });
    }

    /**
     * La révolte : deux colériques au plus bas, deux lettres de plainte, deux avertissements déjà donnés. Le troisième est
     * dit en face au chef (« on prend les armes ») ; seulement au bilan suivant, ils se lèvent et frappent le chef, et jamais
     * l'autre personne présente. Le chef remet dans la caisse du poste les pièces qu'il leur doit : ils lâchent le chef,
     * vont se payer et posent les armes.
     */
    @GameTest(template = "vide", timeoutTicks = 1200)
    public static void revolteContreLeChefSeul(GameTestHelper h) {
        var monnaie = Reglages.actuel.monnaie; // comme TestsJeu.grevePuisReprise : des pièces qui existent sans Numismatics
        Reglages.actuel.monnaie = new java.util.ArrayList<>(java.util.List.of(
                new Reglages.Piece("minecraft:copper_ingot", "cuivre", 1), new Reglages.Piece("minecraft:gold_ingot", "or", 8)));
        BlockPos b = TestsJeu.sol(h, 207 * 400, 60000);
        net.minecraft.server.level.ServerPlayer chef = h.makeMockServerPlayerInLevel(), passant = h.makeMockServerPlayerInLevel();
        for (var p : new net.minecraft.server.level.ServerPlayer[]{chef, passant}) p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        chef.teleportTo(h.getLevel(), b.getX() + 0.5, b.getY(), b.getZ() + 5.5, 0, 0); // le banc d'essai place le faux joueur
        passant.teleportTo(h.getLevel(), b.getX() - 4.5, b.getY(), b.getZ() + 5.5, 0, 0);
        Travailleur[] l = new Travailleur[2];
        for (int i = 0; i < 2; i++) {
            Travailleur t = l[i] = Travailleur.auTravail(h.getLevel(), "mineur", b, Vec3.atBottomCenterOf(b.west(2 + 2 * i)), chef.getUUID(), ItemStack.EMPTY);
            t.forcerChunk();
            t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            t.temperament = "colerique";
            t.moral = 5;
            t.impayes = 2;
            t.memoire.putInt("avertis", 2);
            t.memoire.putInt("bilans_bas", 6);
            t.memoire.putInt("menaces", 2);
            t.memoire.putInt("menace_depuis", 5);
        }
        float vie = chef.getHealth();
        h.runAtTickTime(h.getTick() + 5, () -> {
            Humeur.mefaits(l[0], Moral.Cause.PAIE);
            h.assertTrue(l[0].memoire.getInt("menaces") == 3 && !l[0].memoire.getBoolean("revolte"), "d'abord le dernier avertissement");
            h.assertTrue(l[0].paroles.stream().anyMatch(p -> p.contains("on prend les armes")), "dit en face : " + l[0].paroles);
            for (Travailleur t : l) { t.memoire.putInt("menaces", 3); Humeur.mefaits(t, Moral.Cause.PAIE); } // le bilan suivant
            for (Travailleur t : l) h.assertTrue(t.memoire.getBoolean("revolte"), "révolte");
        });
        boolean[] paye = {false};
        h.onEachTick(() -> {
            h.assertTrue(passant.getHealth() == passant.getMaxHealth(), "personne d'autre que le chef n'est frappé");
            if (!paye[0] && chef.getHealth() < vie - 2) { // frappé : le chef remet dans la caisse ce qu'il leur doit
                paye[0] = true;
                long du = l[0].du() + l[1].du();
                ((Bureau.Entite) h.getLevel().getBlockEntity(b)).caisse.addItem(new ItemStack(Items.GOLD_INGOT, (int) Math.min(64, (du + 7) / 8)));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(paye[0], "le chef frappé (" + chef.getHealth() + "/" + vie + ", " + TestsJeu.etat(l[0], b) + ")");
            h.assertTrue(chef.isAlive(), "le chef a survécu : ils l'ont lâché pour aller se payer");
            for (Travailleur t : l) {
                h.assertTrue(t.impayes == 0, "payé à la caisse (" + TestsJeu.etat(t, b) + ")");
                h.assertTrue(!t.memoire.getBoolean("revolte") && !(t.action instanceof Actions.Attaquer), "payé : il pose les armes (" + TestsJeu.etat(t, b) + ")");
                h.assertTrue(TestsVie.dites(t, "revolte_fin") == 1 && !t.memoire.contains("menaces"), "il le dit, et ses avertissements repartent à zéro");
            }
            Reglages.actuel.monnaie = monnaie;
            for (Travailleur t : l) t.discard();
            chef.discard();
            passant.discard();
        });
    }
}

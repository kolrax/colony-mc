package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Lot 13, cahier 5.8 : chercheurs plus rares, talents propres, réputation du chef, affiche d'embauche et village voisin.
 * Lot à part (« recrutement ») : salaire de 100 à tous les niveaux, embauche gratuite, comme {@link TestsCout}. Des PNJ
 * sans cerveau dans la structure du test (aucun coin du monde n'est pris).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsRecrutement {
    static Reglages avant;

    @BeforeBatch(batch = "recrutement")
    public static void avant(ServerLevel l) {
        Reglages r = Reglages.actuel;
        avant = new Reglages();
        avant.salaire_par_niveau = r.salaire_par_niveau.clone();
        avant.prime_par_niveau = r.prime_par_niveau.clone();
        avant.ouvriers_sans_surcout = r.ouvriers_sans_surcout;
        r.salaire_par_niveau = new int[]{100, 100, 100, 100, 100};
        r.prime_par_niveau = new int[]{0, 0, 0, 0, 0};
        r.ouvriers_sans_surcout = 3;
    }

    @AfterBatch(batch = "recrutement")
    public static void apres(ServerLevel l) {
        if (avant == null) return;
        Reglages r = Reglages.actuel;
        r.salaire_par_niveau = avant.salaire_par_niveau;
        r.prime_par_niveau = avant.prime_par_niveau;
        r.ouvriers_sans_surcout = avant.ouvriers_sans_surcout;
        avant = null;
    }

    /** Un habile et robuste travaille 20 % plus vite et a 6 points de vie de plus ; sa fiche le dit ; la sauvegarde le garde. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "recrutement")
    public static void talentsDuChercheur(GameTestHelper h) {
        List<Travailleur> tous = new ArrayList<>();
        Travailleur doue = TestsCout.pnj(h, null, tous), simple = TestsCout.pnj(h, null, tous);
        h.assertTrue(!doue.talents.isEmpty() && doue.talents.size() <= 2, "un chercheur tiré au sort a un ou deux talents (" + doue.talents + ")");
        doue.talents = List.of(Talents.HABILE, Talents.ROBUSTE);
        doue.appliquerNiveau();
        simple.talents = List.of();
        simple.appliquerNiveau();
        double r = doue.facteurTravail() / simple.facteurTravail();
        h.assertTrue(Math.abs(r - 1.2) < 1e-6, "l'habile travaille 20 % plus vite (" + r + ")");
        h.assertTrue(doue.getMaxHealth() == simple.getMaxHealth() + 6, "le robuste a 6 points de vie de plus (" + doue.getMaxHealth() + " contre " + simple.getMaxHealth() + ")");
        String attitude = doue.fiche(true).getString("attitude");
        h.assertTrue(attitude.contains("main vive") && attitude.contains("chêne"), "la fiche le dit (« " + attitude + " »)");

        CompoundTag tag = new CompoundTag();
        doue.saveWithoutId(tag);
        Travailleur copie = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        copie.load(tag);
        h.assertTrue(copie.talents.equals(List.of(Talents.HABILE, Talents.ROBUSTE)), "relu, il garde ses talents (" + copie.talents + ")");
        h.assertTrue(copie.getAttribute(Attributes.MAX_HEALTH).getBaseValue() == doue.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), "et sa vie");
        tag.remove("Talents");
        copie.load(tag);
        h.assertTrue(copie.talents.isEmpty(), "un ouvrier d'avant ce lot n'a pas de talent");
        TestsCout.fin(h, tous);
    }

    /** Ce qui arrive aux ouvriers fait la réputation du chef : paie versée, grève, mort. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "recrutement")
    public static void reputationSuitLaColonie(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Courrier c = Courrier.de(h.getLevel().getServer());
        List<Travailleur> tous = new ArrayList<>();
        Travailleur t = TestsCout.pnj(h, null, tous);
        t.embaucher(chef);
        h.assertTrue(c.reputation(chef.getUUID()) == 0, "rien encore (" + c.reputation(chef.getUUID()) + ")");
        t.paye(100, true);
        h.assertTrue(c.reputation(chef.getUUID()) == Recrutement.PAIE, "paie versée : +1 (" + c.reputation(chef.getUUID()) + ")");
        t.commencerGreve();
        h.assertTrue(c.reputation(chef.getUUID()) == Recrutement.PAIE + Recrutement.GREVE, "grève : −3 (" + c.reputation(chef.getUUID()) + ")");
        c.marquerMort(t, "aujourd'hui");
        int attendu = Recrutement.PAIE + Recrutement.GREVE + Recrutement.MORT;
        h.assertTrue(c.reputation(chef.getUUID()) == attendu, "un mort à son service : −5 (" + c.reputation(chef.getUUID()) + ")");
        CompoundTag sauve = c.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(Courrier.charger(sauve, h.getLevel().registryAccess()).reputation(chef.getUUID()) == attendu, "gardée à la sauvegarde");
        TestsCout.fin(h, tous);
    }

    /** Chez un chef mal réputé, le candidat le dit à l'entretien et demande 30 % de plus ; chez un chef inconnu, le prix du niveau. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "recrutement")
    public static void mauvaiseRenommeeCoutePlus(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), inconnu = h.makeMockServerPlayerInLevel();
        Courrier.de(h.getLevel().getServer()).reputer(chef.getUUID(), -100);
        List<Travailleur> tous = new ArrayList<>();
        Travailleur t = TestsCout.pnj(h, null, tous);
        t.talents = List.of(); // seule la réputation parle
        t.surcout = Travailleur.surcoutPour(inconnu);
        h.assertTrue(t.fiche(true).getString("salaire").equals(Reglages.texte(100)), "chez un inconnu : 100 (« " + t.fiche(true).getString("salaire") + " »)");
        t.surcout = Travailleur.surcoutPour(chef);
        h.assertTrue(t.fiche(true).getString("salaire").equals(Reglages.texte(130)), "chez un chef mal réputé : 130 (« " + t.fiche(true).getString("salaire") + " »)");
        t.sePresenter(chef);
        h.assertTrue(t.paroles.stream().anyMatch(s -> Vie.vient("reputation_terrible", s)), "il dit ce qu'on raconte (" + t.paroles + ")");
        t.embaucher(chef);
        h.assertTrue(t.mode == Travailleur.Mode.SUIVRE && t.salaireDemande() == 130, "embauché à 130 (" + t.mode + ", " + t.salaireDemande() + ")");
        h.assertTrue(t.paroles.stream().anyMatch(s -> Vie.vient("embauche_reputation", s)), "il dit pourquoi (" + t.paroles + ")");
        TestsCout.fin(h, tous);
    }

    /** Un panneau « On embauche » près du poste et la cloche d'un village voisin font venir plus de monde. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "recrutement")
    public static void afficheEtVillage(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        ServerLevel l = h.getLevel();
        BlockPos poste = h.absolutePos(new BlockPos(1, 2, 1)), panneau = new BlockPos(3, 2, 1), cloche = new BlockPos(1, 2, 3);
        chef.moveTo(poste.getX() + 0.5, poste.getY(), poste.getZ() + 2.5, 0, 0); // chez lui, à deux pas du poste
        h.assertTrue(!ColoniePnj.affiche(l, chef), "sans poste, pas d'affiche");
        Courrier.de(l.getServer()).definirPoste(chef.getUUID(), l, poste);
        h.assertTrue(!ColoniePnj.affiche(l, chef), "un poste sans affiche");
        h.setBlock(panneau, Blocks.OAK_SIGN);
        SignBlockEntity s = (SignBlockEntity) h.getBlockEntity(panneau);
        s.setText(s.getFrontText().setMessage(0, Component.literal("Mine de fer")), true);
        h.assertTrue(!ColoniePnj.affiche(l, chef), "un panneau quelconque n'est pas une affiche");
        s.setText(s.getFrontText().setMessage(1, Component.literal("On embauche !")), true);
        h.assertTrue(ColoniePnj.affiche(l, chef), "l'affiche est vue");

        BlockPos avantCloche = ColoniePnj.village(l, chef);
        h.setBlock(cloche, Blocks.BELL);
        BlockPos v = ColoniePnj.village(l, chef);
        h.assertTrue(v != null && v.equals(h.absolutePos(cloche)), "la cloche du village est trouvée (" + v + ", avant : " + avantCloche + ")");
        double c = Recrutement.chance(Reglages.actuel.chercheurs.chance, 0, true, true);
        h.assertTrue(c > Reglages.actuel.chercheurs.chance, "plus de chances d'une arrivée (" + c + ")");
        h.assertTrue(ColoniePnj.annonce("Mineur", true, true).equals("On dit qu'un mineur du village voisin a vu ton affiche et cherche du travail dans les environs…"), ColoniePnj.annonce("Mineur", true, true));
        Courrier.de(l.getServer()).postes.remove(chef.getUUID());
        h.succeed();
    }

    /** Un ancien fichier de réglages (0,5 toutes les 10 minutes) devient plus rare une seule fois ; ensuite le choix de l'admin est respecté. */
    @GameTest(template = "vide", timeoutTicks = 20, batch = "recrutement")
    public static void chercheursPlusRares(GameTestHelper h) {
        Reglages r = Reglages.GSON.fromJson("{\"chercheurs\":{\"minutes_entre_arrivees\":10,\"chance\":0.5,\"duree_minutes\":60,\"max_autour\":2}}", Reglages.class);
        r.valider();
        h.assertTrue(r.chercheurs.chance == 0.35 && r.chercheurs.minutes_entre_arrivees == 15 && r.chercheurs.plus_rares,
                "ancien fichier : 0,35 toutes les 15 minutes (" + r.chercheurs.chance + ", " + r.chercheurs.minutes_entre_arrivees + ")");
        r.chercheurs.chance = 0.8;
        r.valider();
        h.assertTrue(r.chercheurs.chance == 0.8, "ensuite, le réglage choisi reste (" + r.chercheurs.chance + ")");
        h.succeed();
    }
}

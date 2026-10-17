package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Lot 13, travailleurs de nuit (cahier 5.10) : mis de nuit par la parole, il travaille la nuit et dort le jour dans son lit ;
 * la nuit le fatigue plus et le sommeil du jour répare moins ; après plusieurs nuits d'affilée, sa vie max baisse et les
 * nuits pèsent sur son moral, jusqu'à deux jours de repos ; « travaille de jour » le remet aux heures du jour ; le chasseur
 * mis de nuit le dit et attend au poste. Chaque test dans son coin du monde plat (coins 410 à 414), l'heure réglée par lot.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsNuit {
    static long heureAvant = -1;

    static void heure(ServerLevel l, long t) { if (heureAvant < 0) heureAvant = l.getDayTime(); l.setDayTime(t); }

    static void rendre(ServerLevel l) { if (heureAvant >= 0) l.setDayTime(heureAvant); heureAvant = -1; }

    @BeforeBatch(batch = "nuit_minuit")
    public static void minuit(ServerLevel l) { heure(l, 18000); }

    @AfterBatch(batch = "nuit_minuit")
    public static void apresMinuit(ServerLevel l) { rendre(l); }

    /** Seul dans son lot : il fait passer le monde de minuit à midi. */
    @BeforeBatch(batch = "nuit_bascule")
    public static void bascule(ServerLevel l) { heure(l, 18000); }

    @AfterBatch(batch = "nuit_bascule")
    public static void apresBascule(ServerLevel l) { rendre(l); }

    @BeforeBatch(batch = "nuit_jour")
    public static void midi(ServerLevel l) { heure(l, 6000); }

    @AfterBatch(batch = "nuit_jour")
    public static void apresMidi(ServerLevel l) { rendre(l); }

    record Scene(Travailleur t, ServerPlayer chef, BlockPos base) {}

    /** Un ouvrier de ce métier près de son poste, son chef à côté ; un lit à 3 blocs à l'est du poste si {@code lit}. */
    static Scene scene(GameTestHelper h, int coin, String metier, ItemStack outil, boolean lit) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        chef.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 3.5);
        if (lit) TestsJeu.lit(h.getLevel(), base.east(3));
        Travailleur t = TestsJeu.employe(h, metier, base, Vec3.atBottomCenterOf(base.west(2)), outil);
        t.chef = chef.getUUID();
        t.chefNom = chef.getName().getString();
        t.forcerChunk();
        return new Scene(t, chef, base);
    }

    static void fin(Travailleur t) {
        if (t.chef != null) Courrier.de(t.level().getServer()).postes.remove(t.chef);
        t.discard();
    }

    /**
     * À minuit, « travaille de nuit » : il répond, prend les heures de nuit (20 h - 6 h), se met au travail au lieu de dormir et
     * ne se creuse pas d'abri ; sa fiche le dit. Puis à midi : il dort dans son lit, et y reste (le jour ne le réveille pas).
     */
    @GameTest(template = "vide", batch = "nuit_bascule", timeoutTicks = 2400)
    public static void travailleLaNuitDortLeJour(GameTestHelper h) {
        Scene s = scene(h, 410, "bucheron", new ItemStack(Items.IRON_AXE), true);
        Travailleur t = s.t();
        String[] reponse = {null};
        long[] endormi = {-1};
        Essais.etapes(h, s.chef(), new Travailleur[]{t},
                () -> {
                    reponse[0] = t.appliquer(Ordres.lire("Travaille de nuit", "bucheron"), s.chef());
                    h.assertTrue(t.debut == 20 && t.fin == 6, "heures de nuit (" + t.debut + "-" + t.fin + ", réponse " + reponse[0] + ")");
                    h.assertTrue(reponse[0] != null && reponse[0].contains("nuit"), "il répond qu'il fera les nuits : " + reponse[0]);
                    h.assertTrue(t.enService(), "à minuit, il est en service");
                },
                Essais.attendre(h, 100),
                () -> {
                    h.assertFalse(t.action instanceof Actions.Reposer || t.isSleeping(), "la nuit, il ne dort pas (" + TestsJeu.etat(t, s.base()) + ")");
                    h.assertFalse(t.memoire.contains("abri"), "pas d'abri : il travaille");
                    h.assertTrue(t.fiche(false).getString("attitude").contains("travaille de nuit"), "sa fiche le dit : " + t.fiche(false).getString("attitude"));
                    h.getLevel().setDayTime(6000); // midi
                },
                () -> {
                    h.assertTrue(t.isSleeping(), "à midi, il dort dans son lit (" + TestsJeu.etat(t, s.base()) + ")");
                    if (endormi[0] < 0) endormi[0] = h.getTick();
                },
                Essais.attendre(h, 800),
                () -> {
                    h.assertTrue(t.isSleeping() && t.action instanceof Actions.Reposer, "toujours endormi 40 s plus tard (" + TestsJeu.etat(t, s.base()) + ")");
                    h.assertTrue(t.statut().startsWith("Dort le jour"), "statut : " + t.statut());
                    fin(t);
                });
    }

    /** La nuit, au travail : une heure de travail le fatigue de 7 (5 de jour). */
    @GameTest(template = "vide", batch = "nuit_minuit", timeoutTicks = 1200)
    public static void laNuitFatiguePlus(GameTestHelper h) {
        Scene s = scene(h, 411, "bucheron", new ItemStack(Items.IRON_AXE), false);
        Travailleur t = s.t();
        t.debut = 20;
        t.fin = 6;
        boolean[] lance = {false};
        h.succeedWhen(() -> {
            if (!lance[0]) {
                h.assertTrue(t.mode == Travailleur.Mode.TRAVAIL && t.action != null && !(t.action instanceof Actions.Reposer) && !(t.action instanceof Actions.Attendre),
                        "il travaille (" + TestsJeu.etat(t, s.base()) + ")");
                t.fatigue = 0;
                t.fatigueCompteur = 999; // l'heure passe au prochain tick
                lance[0] = true;
            }
            h.assertTrue(t.fatigueCompteur < 999, "l'heure est passée");
            h.assertTrue(t.fatigue == 7, "une heure de nuit : +7 de fatigue (" + t.fatigue + ", " + TestsJeu.etat(t, s.base()) + ")");
            fin(t);
        });
    }

    /** Le jour, un travailleur de nuit qui dort : une heure de sommeil lui rend 8 de fatigue (12 la nuit). */
    @GameTest(template = "vide", batch = "nuit_jour", timeoutTicks = 1200)
    public static void leSommeilDuJourReposeMoins(GameTestHelper h) {
        Scene s = scene(h, 412, "bucheron", new ItemStack(Items.IRON_AXE), true);
        Travailleur t = s.t();
        t.debut = 20;
        t.fin = 6;
        boolean[] lance = {false};
        h.succeedWhen(() -> {
            if (!lance[0]) {
                h.assertTrue(t.isSleeping() && t.action instanceof Actions.Reposer, "il dort le jour (" + TestsJeu.etat(t, s.base()) + ")");
                t.fatigue = 50;
                t.fatigueCompteur = 999;
                lance[0] = true;
            }
            h.assertTrue(t.fatigueCompteur < 999, "l'heure est passée");
            h.assertTrue(t.fatigue == 42, "une heure de sommeil de jour : −8 (" + t.fatigue + ")");
            fin(t);
        });
    }

    /**
     * Après 3 nuits d'affilée, −2 points de vie max, −4 après 6, et les nuits pèsent sur son moral ; « travaille de jour » le
     * remet de 6 h à 18 h, et deux jours sans nuit effacent tout.
     */
    @GameTest(template = "vide", batch = "nuit_jour", timeoutTicks = 1200)
    public static void plusieursNuitsPuisLeJour(GameTestHelper h) {
        Scene s = scene(h, 413, "mineur", new ItemStack(Items.IRON_PICKAXE), false);
        Travailleur t = s.t();
        t.debut = 20;
        t.fin = 6;
        float vie = t.getMaxHealth();
        // Hier, une nuit de travail (4 heures de nuit au moins) après deux autres : la troisième d'affilée.
        long jour = Math.floorDiv(t.level().getDayTime() + 6000 - 12000, 24000);
        t.memoire.putInt("nuits", 2);
        t.memoire.putInt("nuit_heures", 6);
        t.memoire.putLong("nuit_jour", jour - 1);
        int[] etape = {0};
        h.succeedWhen(() -> {
            if (etape[0] == 0) { t.fatigueCompteur = 999; etape[0] = 1; }
            if (etape[0] == 1) {
                h.assertTrue(t.memoire.getInt("nuits") == 3, "trois nuits d'affilée (" + t.memoire + ")");
                h.assertTrue(t.getMaxHealth() == vie - 2, "−2 points de vie max (" + t.getMaxHealth() + " au lieu de " + vie + ")");
                h.assertTrue(Moral.bilan(Humeur.etat(t)).de(Moral.Cause.NUIT) == -2, "les nuits pèsent sur son moral");
                t.memoire.putInt("nuits", 6);
                t.fatigueCompteur = 999;
                etape[0] = 2;
            }
            if (etape[0] == 2) {
                h.assertTrue(t.getMaxHealth() == vie - 4, "−4 après six nuits (" + t.getMaxHealth() + ")");
                String r = t.appliquer(Ordres.lire("Reprends le jour", "mineur"), s.chef());
                h.assertTrue(t.debut == 6 && t.fin == 18, "de nouveau de jour (" + t.debut + "-" + t.fin + ", réponse " + r + ")");
                // Deux jours sans nuit : le compte s'efface.
                t.memoire.putInt("nuits_libres", 1);
                t.memoire.putLong("nuit_jour", Math.floorDiv(t.level().getDayTime() + 6000 - 12000, 24000) - 1);
                t.memoire.remove("nuit_heures");
                t.fatigueCompteur = 999;
                etape[0] = 3;
            }
            h.assertTrue(t.memoire.getInt("nuits") == 0, "deux jours de repos : plus de nuits comptées (" + t.memoire + ")");
            h.assertTrue(t.getMaxHealth() == vie, "sa vie max revient (" + t.getMaxHealth() + ")");
            h.assertTrue(Moral.bilan(Humeur.etat(t)).de(Moral.Cause.NUIT) == 0, "les nuits ne pèsent plus");
            fin(t);
        });
    }

    /** Un chasseur mis de nuit : rien à chasser dans le noir ; il le dit une fois et attend au poste. */
    @GameTest(template = "vide", batch = "nuit_minuit", timeoutTicks = 1200)
    public static void chasseurDeNuitAttendAuPoste(GameTestHelper h) {
        Scene s = scene(h, 414, "chasseur", new ItemStack(Items.BOW), false);
        Travailleur t = s.t();
        t.inventaire.addItem(new ItemStack(Items.ARROW, 16));
        t.debut = 20;
        t.fin = 6;
        h.succeedWhen(() -> {
            h.assertTrue(Essais.aDit(t, "nuit_sans_ouvrage"), "il dit qu'il n'a rien à faire de nuit (" + t.paroles + ")");
            h.assertTrue(TestsJeu.distance(t, s.base()) <= 8, "il reste au poste (" + TestsJeu.etat(t, s.base()) + ")");
            h.assertTrue(t.paroles.stream().filter(p -> Vie.vient("nuit_sans_ouvrage", p)).count() == 1, "une seule fois");
            fin(t);
        });
    }
}

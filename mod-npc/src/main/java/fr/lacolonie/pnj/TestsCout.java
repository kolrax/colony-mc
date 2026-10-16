package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Lot 11, cahier 9.2 : un débutant paie 2 ou 3 ouvriers, un très riche une trentaine. Le salaire croît avec la colonie
 * ({@link Cout}), fixé à l'embauche ; 30 ouvriers au plus par chef. Lot à part (« cout ») : salaire de 100 à tous les
 * niveaux (15 % se voient), embauche gratuite (le faux chef n'a pas de pièces), remis à la fin du lot. Des PNJ sans
 * cerveau dans la structure du test : rien ne bouge. Tous échouent sur l'ancien code (voir le journal).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsCout {
    static Reglages avant;

    @BeforeBatch(batch = "cout")
    public static void avant(ServerLevel l) {
        Reglages r = Reglages.actuel;
        avant = new Reglages();
        avant.salaire_par_niveau = r.salaire_par_niveau.clone();
        avant.prime_par_niveau = r.prime_par_niveau.clone();
        avant.pnj_max_par_joueur = r.pnj_max_par_joueur;
        avant.ouvriers_sans_surcout = r.ouvriers_sans_surcout;
        avant.surcout_par_ouvrier = r.surcout_par_ouvrier;
        Reglages defaut = new Reglages(); // les valeurs du code, quoi qu'en dise le reglages.json du serveur de test
        r.salaire_par_niveau = new int[]{100, 100, 100, 100, 100};
        r.prime_par_niveau = new int[]{0, 0, 0, 0, 0};
        r.pnj_max_par_joueur = defaut.pnj_max_par_joueur;
        r.ouvriers_sans_surcout = defaut.ouvriers_sans_surcout;
        r.surcout_par_ouvrier = defaut.surcout_par_ouvrier;
    }

    @AfterBatch(batch = "cout")
    public static void apres(ServerLevel l) {
        if (avant == null) return;
        Reglages r = Reglages.actuel;
        r.salaire_par_niveau = avant.salaire_par_niveau;
        r.prime_par_niveau = avant.prime_par_niveau;
        r.pnj_max_par_joueur = avant.pnj_max_par_joueur;
        r.ouvriers_sans_surcout = avant.ouvriers_sans_surcout;
        r.surcout_par_ouvrier = avant.surcout_par_ouvrier;
        avant = null;
    }

    /** Un PNJ sans cerveau dans la structure du test : chercheur d'emploi ({@code chef} null) ou déjà ouvrier de {@code chef}. */
    static Travailleur pnj(GameTestHelper h, ServerPlayer chef, List<Travailleur> tous) {
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        Vec3 p = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        t.moveTo(p.x, p.y, p.z, 0, 0);
        t.tirerAuSort("mineur", 3);
        t.setNoAi(true);
        if (chef != null) { t.chef = chef.getUUID(); t.mode = Travailleur.Mode.TRAVAIL; }
        h.getLevel().addFreshEntity(t);
        tous.add(t);
        return t;
    }

    static void fin(GameTestHelper h, List<Travailleur> tous) {
        for (Travailleur t : tous) t.discard();
        h.succeed();
    }

    /** Trois ouvriers déjà là : le 4e annonce et demande 15 % de plus, et le dit ; le 3e, non. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "cout")
    public static void quatriemeDemandeQuinzePourCent(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        List<Travailleur> tous = new ArrayList<>();
        pnj(h, chef, tous);
        pnj(h, chef, tous);
        Travailleur troisieme = pnj(h, null, tous);
        troisieme.embaucher(chef);
        h.assertTrue(troisieme.mode == Travailleur.Mode.SUIVRE, "3e embauché (" + troisieme.mode + ")");
        h.assertTrue(troisieme.salaireDemande() == 100, "le 3e au prix du niveau (" + troisieme.salaireDemande() + ")");
        h.assertFalse(troisieme.paroles.stream().anyMatch(s -> Vie.vient("embauche_surcout", s)), "le 3e ne parle pas de surcoût");

        Travailleur quatrieme = pnj(h, null, tous);
        quatrieme.surcout = Travailleur.surcoutPour(chef); // ce que fait le clic droit avant d'ouvrir l'entretien
        String annonce = quatrieme.fiche(true).getString("salaire");
        h.assertTrue(annonce.equals(Reglages.texte(115)), "l'entretien annonce 115 (« " + annonce + " »)");
        quatrieme.embaucher(chef);
        h.assertTrue(quatrieme.mode == Travailleur.Mode.SUIVRE, "4e embauché (" + quatrieme.mode + ")");
        h.assertTrue(quatrieme.salaireDemande() == 115 && quatrieme.salaireVerse() == 115, "le 4e demande 115 (" + quatrieme.salaireDemande() + ", versé " + quatrieme.salaireVerse() + ")");
        h.assertTrue(quatrieme.paroles.stream().anyMatch(s -> Vie.vient("embauche_surcout", s)), "le 4e dit que ça se paie (" + quatrieme.paroles + ")");
        fin(h, tous);
    }

    /** Un ouvrier garde sa paie quand le chef en embauche d'autres, et après sauvegarde ; un renvoyé réembauché reprend le rang de son nouveau chef. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "cout")
    public static void ancienGardeSonSalaire(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), autre = h.makeMockServerPlayerInLevel();
        List<Travailleur> tous = new ArrayList<>();
        Travailleur ancien = pnj(h, null, tous);
        ancien.embaucher(chef);
        for (int i = 0; i < 10; i++) pnj(h, chef, tous);
        Travailleur nouveau = pnj(h, null, tous);
        nouveau.embaucher(chef); // le 12e : 1 + 9 × 0,15
        h.assertTrue(ancien.salaireDemande() == 100 && ancien.salaireVerse() == 100, "le premier garde 100 (" + ancien.salaireDemande() + ")");
        h.assertTrue(nouveau.salaireDemande() == 235, "le 12e demande 235 (" + nouveau.salaireDemande() + ")");

        CompoundTag tag = new CompoundTag();
        nouveau.saveWithoutId(tag);
        Travailleur copie = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        copie.load(tag);
        h.assertTrue(copie.salaireDemande() == 235, "relu, il demande toujours 235 (" + copie.salaireDemande() + ")");
        tag.remove("Surcout"); // un ouvrier d'avant ce lot
        copie.load(tag);
        h.assertTrue(copie.salaireDemande() == 100, "sans facteur sauvegardé : au prix du niveau (" + copie.salaireDemande() + ")");

        nouveau.licencier();
        nouveau.embaucher(autre); // le premier ouvrier de l'autre chef
        h.assertTrue(nouveau.salaireDemande() == 100, "réembauché par un chef sans ouvrier : 100 (" + nouveau.salaireDemande() + ")");
        fin(h, tous);
    }

    /** 29 ouvriers : le 30e est embauché, le 31e refusé. */
    @GameTest(template = "vide", timeoutTicks = 100, batch = "cout")
    public static void plafondDeTrente(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        List<Travailleur> tous = new ArrayList<>();
        for (int i = 0; i < 29; i++) pnj(h, chef, tous);
        Travailleur trentieme = pnj(h, null, tous);
        trentieme.embaucher(chef);
        h.assertTrue(trentieme.mode == Travailleur.Mode.SUIVRE, "le 30e est embauché (" + trentieme.mode + ")");
        h.assertTrue(trentieme.salaireDemande() == 505, "le 30e demande 505 (" + trentieme.salaireDemande() + ")");
        Travailleur trenteEtUnieme = pnj(h, null, tous);
        trenteEtUnieme.embaucher(chef);
        h.assertTrue(trenteEtUnieme.mode == Travailleur.Mode.CHOMEUR && trenteEtUnieme.chef == null, "le 31e est refusé (" + trenteEtUnieme.mode + ")");
        fin(h, tous);
    }
}

package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lot 2 « messages du poste » (cahier 10.4 et 10.5 : « trop de messages », « gestion difficile ») : la routine se
 * regroupe, l'important reste à part et en tête, et le chef range son courrier (tout lu, archiver, tout effacer).
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsMessages {
    static final BlockPos BUREAU = new BlockPos(4, 2, 4);

    /** Un sol, un bureau en (4, 2, 4) dont {@code chef} est le propriétaire et le poste, et le chef à côté. */
    static Bureau.Entite poste(GameTestHelper h, ServerPlayer chef) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        BlockPos b = h.absolutePos(BUREAU);
        h.getLevel().setBlockAndUpdate(b, ColoniePnj.BUREAU.get().defaultBlockState());
        Bureau.Entite e = (Bureau.Entite) h.getLevel().getBlockEntity(b);
        e.proprietaire = chef.getUUID();
        Courrier.de(h.getLevel().getServer()).definirPoste(chef.getUUID(), h.getLevel(), b);
        chef.moveTo(b.getX() + 2.5, b.getY(), b.getZ() + 0.5);
        return e;
    }

    /** Une lettre d'Aliénor arrive au poste du chef. */
    static Courrier.Lettre lettre(GameTestHelper h, UUID chef, String objet, String type, boolean importante) {
        Courrier c = Courrier.de(h.getLevel().getServer());
        Courrier.Lettre l = c.nouvelle("Aliénor", UUID.nameUUIDFromBytes(objet.getBytes()), chef, objet, "Chef,\n\n" + objet + ".", h.getLevel());
        l.type = type;
        l.importante = importante;
        c.recevoir(h.getLevel().getServer(), chef, l);
        return l;
    }

    static CompoundTag id(long id) {
        CompoundTag d = new CompoundTag();
        d.putLong("id", id);
        return d;
    }

    static List<Long> ids(ListTag l) {
        List<Long> r = new ArrayList<>();
        for (Tag x : l) r.add(((CompoundTag) x).getLong("id"));
        return r;
    }

    static List<Long> ids(List<Courrier.Lettre> l) { return l.stream().map(x -> x.id).toList(); }

    /** Cinq fois la même lettre de routine du même ouvrier : une seule lettre au poste, qui compte 5. */
    @GameTest(template = "vide", batch = "messages")
    public static void routineRegroupee(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY, BUREAU);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), t.bureau);
        for (int i = 0; i < 5; i++) Courrier.ecrire(t, "torches");
        h.assertTrue(t.lettresADeposer.size() == 5, "près du poste, il garde ses lettres pour les apporter (" + t.lettresADeposer.size() + ")");
        Courrier.deposer(t);
        List<Courrier.Lettre> b = c.boite(t.chef);
        h.assertTrue(b.size() == 1, "une seule lettre au poste, pas " + b.size());
        h.assertTrue(b.get(0).nombre == 5 && b.get(0).objet.equals("Plus de torches") && Boite.objet(b.get(0)).equals("Plus de torches ×5"),
                "elle compte 5 (" + b.get(0).nombre + ", « " + Boite.objet(b.get(0)) + " »)");
        // Une fois lue, la suivante fait une nouvelle lettre.
        b.get(0).lue = true;
        Courrier.ecrire(t, "torches");
        Courrier.deposer(t);
        h.assertTrue(b.size() == 2 && b.get(0).nombre == 1 && !b.get(0).lue, "après lecture, une nouvelle lettre (" + b.size() + ")");
        t.discard();
        h.succeed();
    }

    /** Dix ouvriers face au dépôt plein : une seule lettre, qui compte 10 et cite chacun. */
    @GameTest(template = "vide", batch = "messages")
    public static void depotPleinTousOuvriersConfondus(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        UUID chef = UUID.randomUUID();
        Courrier c = Courrier.de(h.getLevel().getServer());
        List<Travailleur> ts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Travailleur t = TestsJeu.ouvrier(h, "mineur", 1 + i % 5, 2, 1 + i / 5 * 6, ItemStack.EMPTY, BUREAU);
            t.chef = chef;
            ts.add(t);
        }
        c.definirPoste(chef, h.getLevel(), ts.get(0).bureau);
        for (Travailleur t : ts) { Courrier.ecrire(t, "depot_plein"); Courrier.deposer(t); }
        List<Courrier.Lettre> b = c.boite(chef);
        h.assertTrue(b.size() == 1 && b.get(0).nombre == 10, "une seule lettre qui compte 10 (" + b.size() + " lettres)");
        for (Travailleur t : ts) h.assertTrue(b.get(0).de.contains(t.prenom), t.prenom + " doit être cité : « " + b.get(0).de + " »");
        ts.forEach(Travailleur::discard);
        h.succeed();
    }

    /** « Mission terminée, je passe à la suivante » n'est jamais regroupée : chaque lettre dit quelle mission est finie. */
    @GameTest(template = "vide", batch = "messages")
    public static void missionFinieSuiteJamaisRegroupee(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY, BUREAU);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), t.bureau);
        Courrier.ecrire(t, "mission_finie_suite", "10 fers", "5 ors");
        Courrier.ecrire(t, "mission_finie_suite", "5 ors", "3 diamants");
        Courrier.deposer(t);
        h.assertTrue(c.boite(t.chef).size() == 2, "deux lettres, pas " + c.boite(t.chef).size());
        t.discard();
        h.succeed();
    }

    /** « Tout lu » laisse les importantes non lues (elles se lisent en les ouvrant). */
    @GameTest(template = "vide", batch = "messages")
    public static void toutLuLaisseLesImportantes(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        Courrier.Lettre imp = lettre(h, chef.getUUID(), "À l'aide", "", true), routine = lettre(h, chef.getUUID(), "Routine", "", false);
        e.action(chef, "tout_lu", new CompoundTag());
        h.assertTrue(routine.lue && !imp.lue, "la routine est lue, l'importante attend d'être ouverte");
        h.succeed();
    }

    /** Plus de 60 non lues : l'écran sait quand même qu'il y a des lues à effacer et de la routine à marquer lue. */
    @GameTest(template = "vide", batch = "messages")
    public static void compteursAuDelaDeSoixante(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        lettre(h, chef.getUUID(), "Vieille lue", "", false).lue = true;
        for (int i = 0; i < 70; i++) lettre(h, chef.getUUID(), "Non lue " + i, "", true);
        CompoundTag f = e.fiche(chef);
        h.assertTrue(f.getList("lettres", Tag.TAG_COMPOUND).size() == 60, "60 lettres envoyées");
        h.assertTrue(f.getInt("lues") == 1, "une lue à effacer, même hors des 60 : " + f.getInt("lues"));
        h.assertTrue(f.getInt("aMarquer") == 0, "rien à marquer lu : ce sont des importantes (" + f.getInt("aMarquer") + ")");
        h.succeed();
    }

    /** L'important n'est jamais regroupé ; les avis du poste « ramené au poste » se regroupent, sans perdre qui. */
    @GameTest(template = "vide", batch = "messages")
    public static void importanteJamaisFusionnee(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY, BUREAU);
        var s = h.getLevel().getServer();
        Courrier c = Courrier.de(s);
        c.definirPoste(t.chef, h.getLevel(), t.bureau);
        for (int i = 0; i < 3; i++) Courrier.ecrire(t, "a_terre", "1 2 3"); // urgent : un corbeau chacune
        for (Courrier.Vol v : List.copyOf(c.vols)) if (t.getUUID().equals(v.lettre.deUuid)) c.livrer(s, v);
        Courrier.ecrire(t, "mission_finie", "10 fers");
        Courrier.ecrire(t, "mission_finie", "5 ors");
        Courrier.deposer(t);
        List<Courrier.Lettre> b = c.boite(t.chef);
        h.assertTrue(b.stream().filter(l -> l.objet.equals("À L'AIDE")).count() == 3, "3 appels à l'aide, pas regroupés (" + b.size() + " lettres)");
        h.assertTrue(b.stream().filter(l -> l.objet.equals("Mission terminée")).count() == 2, "2 missions finies, pas regroupées");
        h.assertTrue(b.stream().allMatch(l -> l.importante && l.nombre == 1), "toutes importantes, comptées une fois");
        Courrier.avis(s, t.chef, "deces", true, "Avis de décès : Aliénor", "Aliénor est morte.");
        Courrier.avis(s, t.chef, "deces", true, "Avis de décès : Bertrand", "Bertrand est mort.");
        for (String nom : List.of("Aliénor", "Bertrand", "Clotaire"))
            Courrier.avis(s, t.chef, "ramene", false, "Ouvrier ramené au poste", nom + " était coincé : je l'ai ramené au poste.");
        h.assertTrue(b.stream().filter(l -> l.objet.startsWith("Avis de décès")).count() == 2, "2 avis de décès");
        List<Courrier.Lettre> ramenes = b.stream().filter(l -> l.objet.equals("Ouvrier ramené au poste")).toList();
        h.assertTrue(ramenes.size() == 1 && ramenes.get(0).nombre == 3, "les avis « ramené » regroupés en un, qui compte 3 (" + ramenes.size() + ")");
        h.assertTrue(ramenes.get(0).texte.startsWith("Clotaire") && ramenes.get(0).texte.contains("Aliénor") && ramenes.get(0).texte.contains("Bertrand"),
                "l'avis regroupé garde chaque nom, le dernier en tête : " + ramenes.get(0).texte);
        t.discard();
        h.succeed();
    }

    /** À l'écran : importantes non lues, puis non lues, puis lues ; les plus récentes d'abord ; le compteur dans l'objet. */
    @GameTest(template = "vide", batch = "messages")
    public static void ordreDesLettres(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        UUID u = chef.getUUID();
        Courrier.Lettre lueAncienne = lettre(h, u, "Lue, ancienne", "", false);
        Courrier.Lettre importante = lettre(h, u, "À l'aide", "", true);
        Courrier.Lettre nonLue = lettre(h, u, "Plus de torches", "torches", false);
        lettre(h, u, "Plus de torches", "torches", false);
        Courrier.Lettre lueRecente = lettre(h, u, "Lue, récente", "", false);
        lueAncienne.lue = true;
        lueRecente.lue = true;
        CompoundTag f = e.fiche(chef);
        ListTag l = f.getList("lettres", Tag.TAG_COMPOUND);
        h.assertTrue(ids(l).equals(List.of(importante.id, nonLue.id, lueRecente.id, lueAncienne.id)), "ordre : " + ids(l));
        h.assertTrue(l.getCompound(0).getBoolean("importante"), "l'écran doit savoir qu'elle est importante");
        h.assertTrue(l.getCompound(1).getString("objet").equals("Plus de torches ×2"), "compteur dans l'objet : " + l.getCompound(1).getString("objet"));
        h.assertTrue(f.getLong("nonLues") == 2, "2 non lues : " + f.getLong("nonLues"));
        h.succeed();
    }

    /** « Archiver », « Ressortir », « Tout lu », et ouvrir une lettre la marque lue. */
    @GameTest(template = "vide", batch = "messages")
    public static void archiverEtToutLu(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        UUID u = chef.getUUID();
        Courrier.Lettre x = lettre(h, u, "X", "", false), y = lettre(h, u, "Y", "", false), z = lettre(h, u, "Z", "", false);
        e.action(chef, "lue", id(y.id));
        h.assertTrue(y.lue && !x.lue, "ouvrir une lettre la marque lue");
        e.action(chef, "archiver", id(x.id));
        h.assertTrue(x.archivee && x.lue, "archivée (et donc lue)");
        CompoundTag f = e.fiche(chef);
        h.assertTrue(!ids(f.getList("lettres", Tag.TAG_COMPOUND)).contains(x.id) && ids(f.getList("archives", Tag.TAG_COMPOUND)).equals(List.of(x.id)),
                "elle passe du courrier aux archives : " + ids(f.getList("lettres", Tag.TAG_COMPOUND)) + " / " + ids(f.getList("archives", Tag.TAG_COMPOUND)));
        e.action(chef, "tout_lu", new CompoundTag());
        Courrier c = Courrier.de(h.getLevel().getServer());
        h.assertTrue(z.lue && c.nonLues(u) == 0, "tout lu : plus aucune non lue (" + c.nonLues(u) + ")");
        e.action(chef, "desarchiver", id(x.id));
        h.assertTrue(!x.archivee && ids(e.fiche(chef).getList("lettres", Tag.TAG_COMPOUND)).contains(x.id), "ressortie des archives");
        h.succeed();
    }

    /** « Tout effacer » : partent les lettres lues ; restent les non lues et les archivées. */
    @GameTest(template = "vide", batch = "messages")
    public static void toutEffacerGardeNonLuesEtArchivees(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        UUID u = chef.getUUID();
        Courrier.Lettre lue = lettre(h, u, "Lue", "", false), nonLue = lettre(h, u, "Non lue", "", false), gardee = lettre(h, u, "Gardée", "", false);
        Courrier.Lettre importanteLue = lettre(h, u, "Importante lue", "", true);
        e.action(chef, "lue", id(lue.id));
        e.action(chef, "lue", id(importanteLue.id));
        e.action(chef, "archiver", id(gardee.id));
        e.action(chef, "tout_effacer", new CompoundTag());
        List<Courrier.Lettre> b = Courrier.de(h.getLevel().getServer()).boite(u);
        h.assertTrue(ids(b).size() == 2 && b.contains(nonLue) && b.contains(gardee), "restent la non lue et l'archivée : " + b.stream().map(l -> l.objet).toList());
        h.succeed();
    }

    /** Un autre joueur, même au poste, ne touche pas au courrier du chef ; le chef, lui, le peut. */
    @GameTest(template = "vide", batch = "messages")
    public static void autreJoueurSansPouvoir(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), intrus = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        intrus.moveTo(chef.getX() - 1, chef.getY(), chef.getZ());
        UUID u = chef.getUUID();
        Courrier.Lettre lue = lettre(h, u, "Lue", "", false), nonLue = lettre(h, u, "Non lue", "", false);
        lue.lue = true;
        e.action(intrus, "tout_effacer", new CompoundTag());
        e.action(intrus, "supprimer", id(lue.id));
        e.action(intrus, "archiver", id(nonLue.id));
        e.action(intrus, "lue", id(nonLue.id));
        e.action(intrus, "tout_lu", new CompoundTag());
        List<Courrier.Lettre> b = Courrier.de(h.getLevel().getServer()).boite(u);
        h.assertTrue(b.size() == 2 && !nonLue.lue && !nonLue.archivee, "l'intrus ne doit rien changer");
        e.action(chef, "tout_effacer", new CompoundTag());
        h.assertTrue(b.size() == 1 && b.get(0) == nonLue, "le chef, lui, efface ses lettres lues (" + b.size() + ")");
        h.succeed();
    }

    /** Trop de lettres : partent d'abord les plus vieilles lues, jamais une non lue si une lue peut partir. */
    @GameTest(template = "vide", batch = "messages")
    public static void purgeGardeLesNonLues(GameTestHelper h) {
        UUID u = UUID.randomUUID();
        Courrier.Lettre vieilleNonLue = lettre(h, u, "Vieille non lue", "", false);
        Courrier.Lettre vieilleArchivee = lettre(h, u, "Vieille archivée", "", false);
        vieilleArchivee.archivee = vieilleArchivee.lue = true;
        for (int i = 0; i < Courrier.MAX_LETTRES; i++) lettre(h, u, "Lue " + i, "", false).lue = true;
        List<Courrier.Lettre> b = Courrier.de(h.getLevel().getServer()).boite(u);
        h.assertTrue(b.size() == Courrier.MAX_LETTRES, "pas plus de " + Courrier.MAX_LETTRES + " lettres (" + b.size() + ")");
        h.assertTrue(b.contains(vieilleNonLue) && b.contains(vieilleArchivee), "la vieille non lue et l'archivée doivent rester");
        h.assertTrue(b.stream().noneMatch(l -> l.objet.equals("Lue 0") || l.objet.equals("Lue 1")), "les plus vieilles lues partent d'abord");
        h.succeed();
    }

    /** Les nouveaux champs se sauvegardent ; une lettre d'une ancienne sauvegarde (sans eux) se lit avec les valeurs par défaut. */
    @GameTest(template = "vide", batch = "messages")
    public static void sauvegardeDesLettres(GameTestHelper h) {
        Courrier.Lettre l = new Courrier.Lettre();
        l.id = 7;
        l.objet = "Plus de torches";
        l.type = "torches";
        l.importante = true;
        l.archivee = true;
        l.nombre = 4;
        Courrier.Lettre r = Courrier.Lettre.lire(l.sauver());
        h.assertTrue(r.id == 7 && r.type.equals("torches") && r.importante && r.archivee && r.nombre == 4,
                "relue : type " + r.type + ", importante " + r.importante + ", archivée " + r.archivee + ", nombre " + r.nombre);
        // Toute la boîte, avec le monde.
        UUID u = UUID.randomUUID();
        Courrier c = new Courrier();
        c.boite(u).add(l);
        Courrier rc = Courrier.charger(c.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        Courrier.Lettre rl = rc.boite(u).get(0);
        h.assertTrue(rl.nombre == 4 && rl.archivee && rl.importante && rl.type.equals("torches"), "relue avec le monde");
        // Une lettre écrite avant ce lot.
        CompoundTag ancienne = new CompoundTag();
        ancienne.putLong("id", 3);
        ancienne.putString("de", "Aliénor");
        ancienne.putString("objet", "J'ai faim");
        ancienne.putString("texte", "Chef,");
        ancienne.putString("date", "Jour 2, 8 h");
        ancienne.putBoolean("lue", true);
        ancienne.putBoolean("duChef", false);
        Courrier.Lettre a = Courrier.Lettre.lire(ancienne);
        h.assertTrue(a.id == 3 && a.lue && a.nombre == 1 && a.type.isEmpty() && !a.importante && !a.archivee,
                "ancienne lettre : nombre " + a.nombre + ", type « " + a.type + " », importante " + a.importante + ", archivée " + a.archivee);
        h.succeed();
    }
}

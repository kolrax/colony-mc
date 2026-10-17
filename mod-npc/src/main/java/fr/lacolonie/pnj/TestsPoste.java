package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Le poste à 5 onglets et son journal (1.0.0, refonte partie 4.2) : ce qu'un ouvrier a à dire devient une ligne du
 * journal du poste, sans lettre ni corbeau ; l'onglet Missions réordonne et annule les files par l'action serveur.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsPoste {
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

    /** Un ouvrier de ce chef, rattaché à ce poste, immobile (son cerveau ne touche pas à sa file pendant le test). */
    static Travailleur ouvrier(GameTestHelper h, ServerPlayer chef, String metier) {
        Travailleur t = Travailleur.auTravail(h.getLevel(), metier, h.absolutePos(BUREAU), h.absoluteVec(new Vec3(2.5, 2, 2.5)), chef.getUUID(), ItemStack.EMPTY);
        t.setNoAi(true);
        return t;
    }

    /** Courrier.ecrire écrit une ligne au journal du poste, et l'écran du poste (son CompoundTag) la montre. */
    @GameTest(template = "vide", batch = "poste")
    public static void ecrireDonneUneLigneAuJournal(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        chef.moveTo(chef.getX() + 40, chef.getY(), chef.getZ()); // loin : pas face à face
        Travailleur t = ouvrier(h, chef, "mineur");
        Courrier.ecrire(t, "torches");
        List<Courrier.Lettre> j = Courrier.de(h.getLevel().getServer()).boite(chef.getUUID());
        h.assertTrue(j.size() == 1 && j.get(0).objet.equals(Messages.objet("torches")), "une ligne au journal : " + j.size());
        h.assertTrue(t.lettresADeposer.isEmpty(), "plus de lettre à porter au poste");
        chef.moveTo(e.getBlockPos().getX() + 2.5, e.getBlockPos().getY(), e.getBlockPos().getZ() + 0.5);
        CompoundTag f = e.fiche(chef);
        var journal = f.getList("journal", Tag.TAG_COMPOUND);
        String attendu = t.prenom + " : " + Messages.objet("torches");
        h.assertTrue(journal.size() == 1 && journal.getCompound(0).getString("ligne").equals(attendu),
                "l'onglet Journal doit montrer « " + attendu + " » : " + journal);
        h.assertTrue(!journal.getCompound(0).getString("date").isEmpty(), "avec l'heure du jeu");
        h.assertTrue(f.contains("depot") && f.contains("caisse"), "les onglets Dépôt et Caisse viennent du serveur");
        t.discard();
        h.succeed();
    }

    /** L'onglet Dépôt : une ligne par sorte, avec le nombre et la catégorie. */
    @GameTest(template = "vide", batch = "poste")
    public static void depotParCategorie(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        e.depot.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        e.depot.setItem(1, new ItemStack(Items.RAW_IRON, 40));
        e.depot.setItem(2, new ItemStack(Items.RAW_IRON, 40));
        e.depot.setItem(3, new ItemStack(Items.BREAD, 5));
        e.depot.setItem(4, new ItemStack(Items.OAK_LOG, 3));
        var d = e.fiche(chef).getList("depot", Tag.TAG_COMPOUND);
        h.assertTrue(d.size() == 4, "quatre sortes : " + d);
        String cats = d.stream().map(x -> ((CompoundTag) x).getString("cat")).toList().toString();
        h.assertTrue(cats.equals("[outils, minerais, nourriture, bois]"), "les catégories : " + cats);
        h.assertTrue(((CompoundTag) d.get(1)).getLong("n") == 80, "le fer brut compté ensemble : " + d.get(1));
        h.succeed();
    }

    /** L'onglet Missions : monter, descendre, annuler par l'action serveur ; un intrus ou un écran périmé ne change rien. */
    @GameTest(template = "vide", batch = "poste")
    public static void reordonnerEtAnnulerUneMission(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel(), intrus = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        intrus.moveTo(chef.getX() - 1, chef.getY(), chef.getZ());
        Travailleur t = ouvrier(h, chef, "mineur");
        t.missions.clear();
        for (String id : List.of("fer", "charbon", "cuivre")) t.missions.add(new Travailleur.Mission(id, "quantite", 64));
        h.runAtTickTime(h.getTick() + 2, () -> { // un PNJ tout juste apparu n'est pas encore dans le monde
            e.action(chef, "mission_monter", mission(t, 2, 3));
            h.assertTrue(ids(t).equals(List.of("fer", "cuivre", "charbon")), "cuivre monte : " + ids(t));
            e.action(chef, "mission_descendre", mission(t, 0, 3));
            h.assertTrue(ids(t).equals(List.of("cuivre", "fer", "charbon")), "fer descend : " + ids(t));
            e.action(chef, "mission_annuler", mission(t, 0, 2)); // l'écran a vu 2 missions, il y en a 3 : rien
            e.action(intrus, "mission_annuler", mission(t, 0, 3)); // pas son ouvrier : rien
            h.assertTrue(ids(t).size() == 3, "ni l'écran périmé ni l'intrus ne touchent à la file : " + ids(t));
            e.action(chef, "mission_annuler", mission(t, 0, 3));
            h.assertTrue(ids(t).equals(List.of("fer", "charbon")), "la mission en cours annulée, il passe à la suivante : " + ids(t));
            t.discard();
            h.succeed();
        });
    }

    static CompoundTag mission(Travailleur t, int i, int n) {
        CompoundTag d = new CompoundTag();
        d.putString("uuid", t.getUUID().toString());
        d.putInt("i", i);
        d.putInt("n", n);
        return d;
    }

    static List<String> ids(Travailleur t) { return t.missions.stream().map(m -> m.id).toList(); }

    /** Plus de corbeau : le type n'existe plus, et un ouvrier au loin qui a quelque chose à dire ne fait rien apparaître. */
    @GameTest(template = "vide", batch = "poste")
    public static void plusAucunCorbeau(GameTestHelper h) {
        h.assertFalse(BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "corbeau")), "le corbeau ne doit plus exister");
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Bureau.Entite e = poste(h, chef);
        chef.moveTo(chef.getX() + 200, chef.getY(), chef.getZ());
        Travailleur t = ouvrier(h, chef, "bucheron");
        for (String type : List.of("faim", "torches", "a_terre")) Courrier.ecrire(t, type);
        Courrier c = Courrier.de(h.getLevel().getServer());
        h.assertTrue(c.vols.isEmpty(), "aucun vol");
        h.assertTrue(c.boite(chef.getUUID()).size() == 3, "trois lignes au journal : " + c.boite(chef.getUUID()).size());
        AABB zone = new AABB(e.getBlockPos()).inflate(64);
        h.runAtTickTime(h.getTick() + 20, () -> {
            List<Entity> autres = h.getLevel().getEntities((Entity) null, zone, x -> !(x instanceof Travailleur) && !(x instanceof Player));
            h.assertTrue(autres.isEmpty(), "rien d'autre ne doit apparaître : " + autres);
            t.discard();
            h.succeed();
        });
    }
}

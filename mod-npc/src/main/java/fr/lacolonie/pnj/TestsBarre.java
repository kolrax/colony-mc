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

    /** Un autre ouvrier du même chef, à {@code dx} blocs. */
    static Travailleur autre(GameTestHelper h, Travailleur t, String metier, String prenom, double dx) {
        Travailleur w = ColoniePnj.TRAVAILLEUR.get().create(h.getLevel());
        w.moveTo(t.getX() + dx, t.getY(), t.getZ() + 2, 0, 0);
        w.tirerAuSort(metier, 1);
        w.setNoAi(true);
        w.chef = t.chef;
        w.chefNom = t.chefNom;
        w.prenom = prenom;
        w.mode = Travailleur.Mode.TRAVAIL;
        w.debut = w.fin = 0;
        w.prochainePaie = System.currentTimeMillis() + 3_600_000L;
        h.getLevel().addFreshEntity(w);
        return w;
    }

    static final java.util.Set<String> QUESTIONS = java.util.Set.of("Qui ça, chef ?", "Quoi donc, chef ?", "À qui, chef ?");

    /** « Montre-moi… » avec Jean choisi dans la deuxième liste : il montre Jean, sans demander qui (deux autres ouvriers à côté). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void montrerDuDoigtLePrenomChoisi(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        Travailleur jean = autre(h, t, "fermier", "Jean", -2), paul = autre(h, t, "bucheron", "Paul", 2);
        try {
            String dit = BarreCommandes.executer(chef, t, "montrer_cible", "Jean||0");
            h.assertFalse(QUESTIONS.contains(dit), "il ne demande plus qui : " + dit);
            h.assertTrue(t.consigne != null && t.consigne.id.equals("montrer_cible") && jean.getUUID().equals(t.consigne.entite),
                    "il montre Jean : " + vu(t) + ", " + dit);
        } finally {
            t.discard();
            jean.discard();
            paul.discard();
        }
        h.succeed();
    }

    /** « Donne-moi… » avec le pain choisi, et combien : il donne ce pain-là, sans demander quoi. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void donnerLObjetChoisi(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        t.inventaire.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 12));
        try {
            net.minecraft.nbt.CompoundTag l = BarreCommandes.objets(t, Repertoire.PAR_ID.get("donner_chef"));
            h.assertTrue(l.getList("objets", 10).stream().anyMatch(x -> ((net.minecraft.nbt.CompoundTag) x).getString("id").equals("minecraft:bread")
                    && ((net.minecraft.nbt.CompoundTag) x).getInt("n") == 12), "son pain dans la liste : " + l);
            String dit = BarreCommandes.executer(chef, t, "donner_chef", "|minecraft:bread|8");
            h.assertFalse(QUESTIONS.contains(dit), "il ne demande plus quoi : " + dit);
            h.assertTrue(t.consigne != null && t.consigne.id.equals("donner_chef") && t.consigne.texte.equals("minecraft:bread") && t.consigne.nombre == 8,
                    "il donne 8 pains : " + vu(t) + ", " + (t.consigne == null ? "" : t.consigne.texte + " × " + t.consigne.nombre));
        } finally {
            t.discard();
        }
        h.succeed();
    }

    /**
     * Aucun ordre de la barre ne répond par une question qu'on ne peut pas trancher (« Qui ça, chef ? ») : ni sans complément
     * (il ne fait alors rien), ni avec celui de la deuxième liste. Chaque ordre est donné à un ouvrier de son métier.
     */
    @GameTest(template = "vide", timeoutTicks = 200)
    public static void aucuneQuestionSansReponse(GameTestHelper h) {
        ServerPlayer chef = h.makeMockServerPlayerInLevel();
        Travailleur t = mineur(h, chef);
        Travailleur jean = autre(h, t, "fermier", "Jean", -2), paul = autre(h, t, "bucheron", "Paul", 2);
        java.util.Map<String, Travailleur> parMetier = new java.util.HashMap<>();
        parMetier.put("mineur", t);
        List<String> fautes = new java.util.ArrayList<>();
        try {
            for (BarreCommandes.Recherche.Ligne l : BarreCommandes.Recherche.lignes()) {
                Repertoire.Entree e = Repertoire.PAR_ID.get(l.id());
                String metier = e.metiers().isEmpty() ? "mineur" : e.metiers().iterator().next();
                Travailleur w = parMetier.computeIfAbsent(metier, m -> autre(h, t, m, "Ouvrier", 0));
                w.inventaire.addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4));
                BarreCommandes.Recherche.Besoin b = BarreCommandes.Recherche.besoin(e);
                String sans = BarreCommandes.executer(chef, w, e.id());
                if (QUESTIONS.contains(sans)) fautes.add(e.id() + " sans : " + sans);
                String avec = BarreCommandes.executer(chef, w, e.id(), new BarreCommandes.Recherche.Complement(b.qui() ? "Jean" : "", b.quoi() ? "minecraft:bread" : "", 1).texte());
                if (QUESTIONS.contains(avec)) fautes.add(e.id() + " avec : " + avec);
                Consignes.annuler(w);
            }
            h.assertTrue(fautes.isEmpty(), "des questions sans réponse : " + fautes);
        } finally {
            parMetier.values().forEach(Travailleur::discard);
            jean.discard();
            paul.discard();
        }
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

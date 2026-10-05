package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Réglages du serveur, dans config/colonie_pnj/reglages.json, modifiables depuis le panneau web. */
public class Reglages {
    public static class Piece {
        public String objet;
        public String nom;
        public int valeur;

        Piece(String objet, String nom, int valeur) { this.objet = objet; this.nom = nom; this.valeur = valeur; }
    }

    public static class Chercheurs {
        public int minutes_entre_arrivees = 10;
        public double chance = 0.5;
        public int duree_minutes = 60;
        public int max_autour = 2;
    }

    public List<Piece> monnaie = new ArrayList<>(List.of(
            new Piece("numismatics:spur", "pièce de bronze", 1),
            new Piece("numismatics:bevel", "pièce d'andésite", 8),
            new Piece("numismatics:sprocket", "rouage", 16),
            new Piece("numismatics:cog", "rouage en laiton", 64),
            new Piece("numismatics:crown", "rouage en or", 512),
            new Piece("numismatics:sun", "rouage en netherite", 4096)));
    public int[] salaire_par_niveau = {2, 4, 8, 16, 32};
    public int[] prime_par_niveau = {8, 12, 24, 48, 96};
    public int minutes_entre_paies = 1440;
    public int paies_impayees_avant_greve = 2;
    public int pnj_max_par_joueur = 5;
    public Chercheurs chercheurs = new Chercheurs();
    public List<String> metiers_inactifs = new ArrayList<>();
    public int port_panneau = 25776;
    public String jeton_panneau = "";

    public static Reglages actuel = new Reglages();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Scripts fournis avec le mod, copiés dans config/colonie_pnj/metiers/ s'ils n'y sont pas. */
    static final List<String> SCRIPTS_DE_BASE = List.of("commun", "mineur", "bucheron", "guerrier");

    public static Path dossier() { return FMLPaths.CONFIGDIR.get().resolve("colonie_pnj"); }
    public static Path dossierMetiers() { return dossier().resolve("metiers"); }
    static Path fichier() { return dossier().resolve("reglages.json"); }

    public static synchronized void charger() {
        try {
            Files.createDirectories(dossierMetiers());
            Reglages r = Files.exists(fichier())
                    ? GSON.fromJson(Files.readString(fichier(), StandardCharsets.UTF_8), Reglages.class)
                    : new Reglages();
            if (r.jeton_panneau == null || r.jeton_panneau.isBlank()) {
                byte[] b = new byte[18];
                new SecureRandom().nextBytes(b);
                r.jeton_panneau = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            }
            r.valider();
            actuel = r;
            sauver();
            for (String s : SCRIPTS_DE_BASE) {
                Path p = dossierMetiers().resolve(s + ".lua");
                if (!Files.exists(p)) Files.writeString(p, scriptDeBase(s), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            ColoniePnj.LOG.error("Réglages illisibles, valeurs par défaut utilisées", e);
        }
    }

    public static synchronized void sauver() throws IOException {
        Files.writeString(fichier(), GSON.toJson(actuel), StandardCharsets.UTF_8);
    }

    /** Remplace les réglages par ceux envoyés par le panneau (le jeton et le port ne changent pas ici). */
    public static synchronized void remplacer(Reglages r) throws IOException {
        r.jeton_panneau = actuel.jeton_panneau;
        r.port_panneau = actuel.port_panneau;
        r.valider();
        actuel = r;
        sauver();
    }

    void valider() {
        if (monnaie == null) monnaie = new ArrayList<>();
        monnaie.removeIf(p -> p == null || p.objet == null || p.valeur <= 0 || ResourceLocation.tryParse(p.objet) == null);
        for (Piece p : monnaie) if (p.nom == null || p.nom.isBlank()) p.nom = p.objet;
        if (salaire_par_niveau == null || salaire_par_niveau.length != 5) salaire_par_niveau = new int[]{2, 4, 8, 16, 32};
        if (prime_par_niveau == null || prime_par_niveau.length != 5) prime_par_niveau = new int[]{8, 12, 24, 48, 96};
        if (chercheurs == null) chercheurs = new Chercheurs();
        if (metiers_inactifs == null) metiers_inactifs = new ArrayList<>();
        minutes_entre_paies = Math.max(1, minutes_entre_paies);
        paies_impayees_avant_greve = Math.max(1, paies_impayees_avant_greve);
        pnj_max_par_joueur = Math.max(0, pnj_max_par_joueur);
        chercheurs.minutes_entre_arrivees = Math.max(1, chercheurs.minutes_entre_arrivees);
        chercheurs.duree_minutes = Math.max(1, chercheurs.duree_minutes);
    }

    public static String scriptDeBase(String nom) throws IOException {
        try (InputStream in = Reglages.class.getResourceAsStream("/scripts/" + nom + ".lua")) {
            if (in == null) throw new IOException("script de base absent : " + nom);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- Argent -------------------------------------------------------------------------------

    public static long salaire(int niveau) { return actuel.salaire_par_niveau[Math.clamp(niveau, 1, 5) - 1]; }
    public static long prime(int niveau) { return actuel.prime_par_niveau[Math.clamp(niveau, 1, 5) - 1]; }

    static int[] valeurs() { return actuel.monnaie.stream().mapToInt(p -> p.valeur).toArray(); }

    public static String texte(long montant) {
        return Monnaie.texte(montant, valeurs(), actuel.monnaie.stream().map(p -> p.nom).toArray(String[]::new));
    }

    /** Index de la pièce dans la monnaie, ou -1 si l'objet n'est pas une pièce. */
    public static int piece(ItemStack s) {
        if (s.isEmpty()) return -1;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        for (int i = 0; i < actuel.monnaie.size(); i++)
            if (id.toString().equals(actuel.monnaie.get(i).objet)) return i;
        return -1;
    }

    public static long valeur(Container c) {
        long t = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            int p = piece(c.getItem(i));
            if (p >= 0) t += (long) actuel.monnaie.get(p).valeur * c.getItem(i).getCount();
        }
        return t;
    }

    /**
     * Retire {@code montant} des pièces d'un contenant et y remet la monnaie avec {@code ajouter}
     * (qui renvoie le reste qui n'a pas pu entrer, alors confié à {@code surplus}).
     * Les pièces payées disparaissent du jeu. Faux si les pièces ne suffisent pas (rien n'est touché).
     */
    public static boolean payer(Container c, long montant, UnaryOperator<ItemStack> ajouter, Consumer<ItemStack> surplus) {
        int[] v = valeurs();
        int[] q = new int[v.length];
        for (int i = 0; i < c.getContainerSize(); i++) {
            int p = piece(c.getItem(i));
            if (p >= 0) q[p] += c.getItem(i).getCount();
        }
        int[] apres = Monnaie.payer(v, q, montant);
        if (apres == null) return false;
        for (int i = 0; i < c.getContainerSize(); i++)
            if (piece(c.getItem(i)) >= 0) c.setItem(i, ItemStack.EMPTY);
        for (int p = 0; p < apres.length; p++) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(actuel.monnaie.get(p).objet));
            if (item == net.minecraft.world.item.Items.AIR) continue; // pièce inconnue (mod absent) : on ne la rend pas
            int n = apres[p];
            while (n > 0) {
                ItemStack s = new ItemStack(item, Math.min(n, item.getDefaultMaxStackSize()));
                n -= s.getCount();
                ItemStack reste = ajouter.apply(s);
                if (!reste.isEmpty()) surplus.accept(reste);
            }
        }
        c.setChanged();
        return true;
    }
}

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    /**
     * Les chercheurs d'emploi : toutes les N minutes de jour, chaque joueur a tant de chances qu'il en vienne un (modulées
     * par sa réputation, une affiche et un village voisin : {@link Recrutement#chance}). Plus rares depuis le lot 13
     * (cahier 5.8) : 0,35 toutes les 15 minutes au lieu de 0,5 toutes les 10.
     */
    public static class Chercheurs {
        public int minutes_entre_arrivees = 15;
        public double chance = 0.35;
        public int duree_minutes = 60;
        public int max_autour = 2;
        /** Vrai une fois la rareté du lot 13 appliquée à un ancien fichier (qui gardait 0,5 toutes les 10 minutes). */
        public boolean plus_rares = false;
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
    /**
     * Les prix entre PNJ (lot 11), en valeur de pièces (1 = la plus petite pièce), par objet. Une nourriture absente : une
     * pièce par tranche de 3 points de nourriture (Economie.prix).
     */
    public Map<String, Integer> prix = new java.util.LinkedHashMap<>(Map.of(
            "minecraft:bread", 1, "minecraft:baked_potato", 1, "minecraft:apple", 1, "minecraft:carrot", 1,
            "minecraft:cooked_beef", 3, "minecraft:cooked_porkchop", 3, "minecraft:cooked_mutton", 2, "minecraft:cooked_chicken", 2,
            "minecraft:cooked_cod", 2, "minecraft:cooked_salmon", 2));
    /** Un ouvrier qui a faim et rien au dépôt cherche à acheter à manger à un producteur à moins de N blocs (0 = jamais). */
    public int achat_distance = 32;
    /** Au plus tant d'ouvriers par chef (cahier 9.2 : un très riche en a une trentaine). */
    public int pnj_max_par_joueur = 30;
    /** Le salaire croît avec la colonie (Cout) : les N premiers ouvriers d'un chef au prix de leur niveau, puis tant de plus par ouvrier au-delà (0,15 : +15 %). */
    public int ouvriers_sans_surcout = 3;
    public double surcout_par_ouvrier = 0.15;
    public Chercheurs chercheurs = new Chercheurs();
    /** Les étoiles qui montent (lot 13) : expérience, formation payée, hausse de paie ({@link Etoiles}). */
    public Etoiles.Reglage etoiles = new Etoiles.Reglage();
    public List<String> metiers_inactifs = new ArrayList<>();
    /** Une conversation entre deux ouvriers d'un même chef au plus toutes les N secondes, par colonie (0 = jamais). */
    public int secondes_entre_conversations = 180;
    /** Une remarque spontanée par ouvrier au plus toutes les N à 2N secondes (lot 7 ; 0 = jamais). */
    public int secondes_entre_remarques = 600;
    /** Les outils et armes des PNJ s'usent N fois moins vite que ceux d'un joueur (cahier 3.13 ; 1 = comme un joueur). */
    public int usure_outils = 3;
    /** L'éleveur garde au plus N bêtes de chaque espèce par enclos : il n'en fait plus naître et abat l'excédent (2 au moins). */
    public int betes_par_enclos = 12;
    /**
     * Travail de nuit (lot 13, cahier 5.10) : les heures de « travaille de nuit » ; la fatigue d'une heure de travail la nuit
     * (×1,3) ; ce que rend une heure de sommeil le jour (les deux tiers) ; à tant de nuits d'affilée, −2 points de vie max
     * (−4 au double), une guérison plus lente et un moral plus bas (0 = jamais) ; tant de jours sans nuit effacent le compte.
     */
    public int nuit_debut = 20, nuit_fin = 6;
    public double nuit_fatigue = 1.3;
    public double nuit_sommeil_jour = 0.67;
    public int nuit_nuits_avant_malus = 3;
    public int nuit_jours_de_repos = 2;
    /** Moral (lot 5, cahier 5.2) : un bilan de ses causes (paie, repas, lit, danger, deuil, météo…) toutes les N minutes. */
    public int moral_minutes_bilan = 5;
    /** Sous ce moral, il travaille plus lentement et écrit au chef ce qui ne va pas (une lettre par jour du jeu au plus). */
    public int moral_seuil_lettre = 30;
    /** Sous ce moral, plus lent encore ; tant de jours du jeu de suite, après deux lettres, il démissionne (0 jour = jamais). */
    public int moral_seuil_demission = 15;
    public int moral_jours_avant_demission = 3;
    /** La lenteur au plus bas, en pour cent de sa vitesse (la moitié sous le seuil de lettre). */
    public int moral_lenteur = 20;
    /** Au plus bas depuis un moment, après une lettre : chance d'un vol ou d'un sabotage à chaque bilan, en pour cent (le caractère la module ; 0 = jamais). */
    public int moral_mefaits = 25;
    /** Au plus tant d'objets volés par jour du jeu (rien de précieux) ; aussi dans les coffres proches d'autres que son chef. */
    public int moral_vol_max = 4;
    public boolean moral_vol_autres = true;
    /** Révolte armée (cahier 5.4) : après plusieurs avertissements, les ouvriers au plus bas frappent leur chef, et lui seul. */
    public boolean moral_revolte = true;
    /** Deuil (lot 5, cahier 14.13) : un enterrement dure tant de secondes autour de la tombe ; y viennent les ouvriers du chef à moins de tant de blocs. */
    public int deuil_secondes_enterrement = 30;
    public int deuil_rayon = 48;
    /** Amitiés, inimitiés et couples entre ouvriers d'un même chef (lot 7) ; {@code liens_vitesse} : multiplie ce qui rapproche (0,1 à 10). */
    public boolean liens_actifs = true;
    public double liens_vitesse = 1.0;
    /** Deux grands amis depuis deux jours peuvent se mettre en couple. */
    public boolean liens_couples = true;
    /** Un mineur fourbe qui trouve des diamants en garde un en cachette, au plus un par jour du jeu : chance en pour cent (le colérique au moral bas : à peine plus de la moitié ; jamais le calme ; 0 = jamais). */
    public int caractere_diamant = 50;
    /** Les sons des PNJ (lot 8) : coups d'outil, d'arme, dépôt, paie, grognements, ronflements… et des pas, chutes et blessures de joueur. */
    public boolean sons = true;
    /** Nuit dehors (lot 9, cahier 14.12) : loin du poste, un ouvrier se creuse un trou dans la terre et s'y enferme jusqu'au matin (false : il dort à la belle étoile). */
    public boolean abri_nuit = true;
    /** Devant un monstre, l'ouvrier agit selon son caractère (Bravoure) : le colérique le frappe, le peureux fuit, le fourbe s'éclipse, le calme se défend s'il est frappé (false : tous signalent et fuient s'ils sont frappés). */
    public boolean caractere_monstres = true;
    public int port_panneau = 25776;
    public String jeton_panneau = "";
    /** Le joueur fantôme (/fantome, refonte partie 7) : toujours actif en développement ; sur un vrai serveur, seulement si vrai. */
    public boolean fantome = false;

    public static Reglages actuel = new Reglages();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Path dossier() { return FMLPaths.CONFIGDIR.get().resolve("colonie_pnj"); }
    static Path fichier() { return dossier().resolve("reglages.json"); }

    public static synchronized void charger() {
        try {
            Files.createDirectories(dossier());
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
        if (r.chercheurs != null) r.chercheurs.plus_rares = actuel.chercheurs.plus_rares; // le panneau ne l'envoie pas : ses chiffres sont respectés
        r.valider();
        actuel = r;
        sauver();
    }

    void valider() {
        if (monnaie == null) monnaie = new ArrayList<>();
        monnaie.removeIf(p -> p == null || p.objet == null || p.valeur <= 0 || ResourceLocation.tryParse(p.objet) == null);
        for (Piece p : monnaie) if (p.nom == null || p.nom.isBlank()) p.nom = p.objet;
        if (salaire_par_niveau == null || salaire_par_niveau.length != 5) salaire_par_niveau = new int[]{2, 4, 8, 16, 32};
        if (prix == null) prix = new java.util.LinkedHashMap<>();
        prix.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0);
        achat_distance = Math.clamp(achat_distance, 0, 64);
        if (prime_par_niveau == null || prime_par_niveau.length != 5) prime_par_niveau = new int[]{8, 12, 24, 48, 96};
        if (chercheurs == null) chercheurs = new Chercheurs();
        etoiles = Etoiles.Reglage.valide(etoiles);
        if (metiers_inactifs == null) metiers_inactifs = new ArrayList<>();
        minutes_entre_paies = Math.max(1, minutes_entre_paies);
        paies_impayees_avant_greve = Math.max(1, paies_impayees_avant_greve);
        pnj_max_par_joueur = Math.max(0, pnj_max_par_joueur);
        ouvriers_sans_surcout = Math.clamp(ouvriers_sans_surcout, 0, 1000);
        surcout_par_ouvrier = Double.isNaN(surcout_par_ouvrier) ? 0.15 : Math.clamp(surcout_par_ouvrier, 0.0, 1.0);
        secondes_entre_conversations = Math.max(0, secondes_entre_conversations);
        secondes_entre_remarques = Math.max(0, secondes_entre_remarques);
        usure_outils = Math.max(1, usure_outils);
        moral_minutes_bilan = Math.clamp(moral_minutes_bilan, 1, 20);
        nuit_debut = Math.floorMod(nuit_debut, 24);
        nuit_fin = Math.floorMod(nuit_fin, 24);
        if (nuit_debut == nuit_fin) { nuit_debut = 20; nuit_fin = 6; }
        nuit_fatigue = Double.isNaN(nuit_fatigue) || nuit_fatigue <= 0 ? 1.3 : Math.clamp(nuit_fatigue, 1.0, 3.0);
        nuit_sommeil_jour = Double.isNaN(nuit_sommeil_jour) || nuit_sommeil_jour <= 0 ? 0.67 : Math.clamp(nuit_sommeil_jour, 0.1, 1.0);
        nuit_nuits_avant_malus = Math.clamp(nuit_nuits_avant_malus, 0, 30);
        nuit_jours_de_repos = Math.clamp(nuit_jours_de_repos, 1, 30);
        moral_seuil_lettre = Math.clamp(moral_seuil_lettre, 0, 100);
        moral_seuil_demission = Math.clamp(moral_seuil_demission, 0, moral_seuil_lettre);
        moral_jours_avant_demission = Math.max(0, moral_jours_avant_demission);
        moral_lenteur = Math.clamp(moral_lenteur, 0, 50);
        moral_mefaits = Math.clamp(moral_mefaits, 0, 100);
        moral_vol_max = Math.max(0, moral_vol_max);
        deuil_secondes_enterrement = Math.clamp(deuil_secondes_enterrement, 1, 300);
        deuil_rayon = Math.clamp(deuil_rayon, 8, 128);
        liens_vitesse = Double.isNaN(liens_vitesse) ? 1.0 : Math.clamp(liens_vitesse, 0.1, 10.0);
        caractere_diamant = Math.clamp(caractere_diamant, 0, 100);
        if (betes_par_enclos <= 0) betes_par_enclos = 12;
        betes_par_enclos = Math.max(2, betes_par_enclos);
        if (!chercheurs.plus_rares) { // une seule fois : ensuite, le réglage choisi au panneau ou dans le fichier est respecté
            Chercheurs defaut = new Chercheurs();
            chercheurs.chance = Math.min(chercheurs.chance, defaut.chance);
            chercheurs.minutes_entre_arrivees = Math.max(chercheurs.minutes_entre_arrivees, defaut.minutes_entre_arrivees);
            chercheurs.plus_rares = true;
        }
        chercheurs.chance = Double.isNaN(chercheurs.chance) ? 0 : Math.clamp(chercheurs.chance, 0, 1);
        chercheurs.minutes_entre_arrivees = Math.max(1, chercheurs.minutes_entre_arrivees);
        chercheurs.duree_minutes = Math.max(1, chercheurs.duree_minutes);
    }

    // --- Argent -------------------------------------------------------------------------------

    public static long salaire(int niveau) { return actuel.salaire_par_niveau[Math.clamp(niveau, 1, 5) - 1]; }
    public static long prime(int niveau) { return actuel.prime_par_niveau[Math.clamp(niveau, 1, 5) - 1]; }
    /** Le facteur du salaire de la {@code rang}-ième embauche chez un chef ({@link Cout}). */
    public static double surcout(int rang) { return Cout.facteur(rang, actuel.ouvriers_sans_surcout, actuel.surcout_par_ouvrier); }

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

    /** Les quantités de chaque pièce dans un contenant (une case par pièce de la monnaie). */
    static int[] quantites(Container c) {
        int[] q = new int[actuel.monnaie.size()];
        for (int i = 0; i < c.getContainerSize(); i++) {
            int p = piece(c.getItem(i));
            if (p >= 0) q[p] += c.getItem(i).getCount();
        }
        return q;
    }

    /** {@code montant} en pièces, les plus grosses d'abord (une pièce inconnue, mod absent, n'est pas faite). */
    static List<ItemStack> pieces(long montant) {
        int[] q = Monnaie.rendre(valeurs(), montant);
        List<ItemStack> r = new ArrayList<>();
        for (int p = 0; p < q.length; p++) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(actuel.monnaie.get(p).objet));
            if (item == net.minecraft.world.item.Items.AIR) continue;
            for (int n = q[p]; n > 0; ) {
                ItemStack s = new ItemStack(item, Math.min(n, item.getDefaultMaxStackSize()));
                n -= s.getCount();
                r.add(s);
            }
        }
        return r;
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

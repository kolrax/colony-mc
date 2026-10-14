package fr.lacolonie.pnj;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un métier, codé dans le mod. Le cerveau commun ({@link Cerveau}) s'occupe des besoins du PNJ, puis appelle
 * {@link #travailler}, qui décide de la prochaine action et la lance. Chaque PNJ garde son état de travail
 * dans {@code t.memoire} (sauvegardé avec lui).
 */
public abstract class Metier {
    public final String id, nom, description;
    /** Riposte quand on l'attaque (sinon il fuit). */
    final boolean combattant;
    /** Ce qu'il garde sur lui en vidant son sac au dépôt (une pile de chaque). */
    final List<Actions.Filtre> garder = new ArrayList<>();
    /** Le catalogue des commandes, affiché dans l'écran du bureau. */
    final List<Commande> commandes = new ArrayList<>();

    Metier(String id, String nom, String description, boolean combattant, String... garder) {
        this.id = id;
        this.nom = nom;
        this.description = description;
        this.combattant = combattant;
        for (String g : garder) this.garder.add(Actions.Filtre.de(g));
        this.garder.add(Actions.Filtre.de("#minecraft:boats")); // son bateau, s'il en a un, pour traverser l'eau
    }

    /** Une ligne du catalogue. {@code objet} : ce qui se compte au dépôt ou à la livraison (null : ni stock ni quantité). */
    public static class Commande {
        public final String id, nom;
        public final Actions.Filtre objet;

        Commande(String id, String nom, String objet) {
            this.id = id;
            this.nom = nom;
            this.objet = objet == null ? null : Actions.Filtre.de(objet);
        }
    }

    /** Décide de la prochaine action et la lance (ou rien : le cerveau rappellera dans une demi-seconde). */
    abstract void travailler(Travailleur t);

    /** Appelé même quand il est occupé : réagir à une urgence. */
    void vigilance(Travailleur t) {}

    /**
     * Sans action, avant tout le reste (faim, paie, ordres) : vrai s'il lance de quoi se tirer d'un endroit d'où le chemin
     * ordinaire ne sait pas sortir (l'éleveur, resté dans un enclos fermé par un portillon).
     */
    boolean sortir(Travailleur t) { return false; }

    /** En mode « Suivre » : vrai s'il a trouvé mieux à faire que suivre (le guerrier combat). */
    boolean escorter(Travailleur t) { return false; }

    // --- Les outils, fournis au dépôt (cahier 3.12) --------------------------------------------------

    /** La note d'un outil : le matériau d'abord, puis le moins usé ({@link Outils#note}). */
    static double note(ItemStack s) {
        int rang = s.getItem() instanceof TieredItem ti ? Outils.rang(ti.getTier() instanceof Tiers x ? x.name() : "", ti.getTier().getSpeed()) : 1;
        return Outils.note(rang, s.getDamageValue(), s.getMaxDamage());
    }

    /**
     * L'outil convient : un de la liste ({@code bon}), ou un outil de la bonne sorte venu d'un autre mod (pas listé : on lui
     * fait confiance, il ne manque pas).
     */
    static boolean convient(ItemStack s, Actions.Filtre sorte, Actions.Filtre bon) {
        return bon.objet(s) || sorte.objet(s) && !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace());
    }

    /** La note de son meilleur outil qui répond à f (en main ou au sac), 0 s'il n'en a pas. */
    static double meilleure(Travailleur t, Predicate<ItemStack> f) {
        double n = f.test(t.getMainHandItem()) ? note(t.getMainHandItem()) : 0;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (f.test(t.inventaire.getItem(i))) n = Math.max(n, note(t.inventaire.getItem(i)));
        return n;
    }

    /** A-t-il (en main ou au sac) un outil enchanté qui répond à f ? */
    static boolean enchante(Travailleur t, Predicate<ItemStack> f) {
        if (f.test(t.getMainHandItem()) && t.getMainHandItem().isEnchanted()) return true;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (f.test(t.inventaire.getItem(i)) && t.inventaire.getItem(i).isEnchanted()) return true;
        return false;
    }

    /** Prend en main son meilleur outil qui répond à f (l'objet tenu retourne au sac). Faux s'il n'en a pas. */
    static boolean equiperMeilleur(Travailleur t, Predicate<ItemStack> f) {
        double n = f.test(t.getMainHandItem()) ? note(t.getMainHandItem()) : 0;
        int k = -1;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (f.test(s) && note(s) > n) { n = note(s); k = i; }
        }
        if (k >= 0) {
            ItemStack tenu = t.getMainHandItem();
            t.setItemSlot(EquipmentSlot.MAINHAND, t.inventaire.getItem(k));
            t.inventaire.setItem(k, tenu);
        }
        return n > 0;
    }

    /**
     * L'outil de son métier est fourni au dépôt (cahier 3.12). Il lui manque un outil {@code bon} (aucun, cassé, ou pas assez
     * bon pour ce minerai) : il regarde au dépôt de son poste (coffres collés compris), au plus toutes les 10 s s'il n'y en
     * a pas (30 s après être parti en prendre un). Il en a un : passé près du poste, au plus une fois par minute, il regarde s'il y en a
     * un d'un meilleur matériau (enchanté, si le sien l'est : celui que le chef lui a donné n'est jamais échangé contre un outil
     * ordinaire). Vrai s'il part le prendre ({@link PrendreOutil}) ; sinon, il fait comme avant (il le demande,
     * ou travaille sans). Sans poste, ou loin de chez lui, rien.
     */
    static boolean outilAuDepot(Travailleur t, Actions.Filtre sorte, Actions.Filtre bon, String nom) {
        if (!t.chezLui(t.blockPosition())) return false;
        // Les minuteries d'abord : rien à lire (ni sac ni dépôt) tant qu'aucune n'est échue.
        long maintenant = Cerveau.temps(t);
        boolean voir = t.memoire.getLong("outil_vu") <= maintenant;
        boolean revoir = t.memoire.getLong("revue_outil") <= maintenant && t.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(t.bureau)) <= 16 * 16;
        if (!voir && !revoir) return false;
        double mien = meilleure(t, s -> convient(s, sorte, bon));
        boolean manque = mien == 0;
        if (manque ? !voir : !revoir) return false;
        Bureau.Entite b = Bureau.entite(t);
        if (b == null) return false;
        double seuil = manque ? 0 : Outils.seuilMieux(mien);
        boolean enchante = !manque && enchante(t, sorte::objet);
        boolean vu = !Guerrier.auDepot(b, depot(bon, enchante), seuil, false).isEmpty();
        if (manque) t.memoire.putLong("outil_vu", maintenant + (vu ? 30 : 10)); // trouvé : s'il n'arrive pas au poste, pas d'essai sans fin
        else t.memoire.putLong("revue_outil", maintenant + 60);
        if (!vu) return false;
        Cerveau.lancer(t, new PrendreOutil(sorte, bon, seuil, nom, manque, enchante));
        return true;
    }

    /** La note d'un outil du dépôt : seulement un outil {@code bon}, et enchanté si le sien l'est. */
    static java.util.function.ToDoubleFunction<ItemStack> depot(Actions.Filtre bon, boolean enchante) {
        return s -> bon.objet(s) && (!enchante || s.isEnchanted()) ? note(s) : 0;
    }

    /**
     * Au poste, il prend au dépôt le meilleur outil {@code bon} (au-dessus de {@code seuil}) et le met en main ; le sien de la
     * même sorte (le meilleur qu'il avait) retourne au dépôt s'il vaut moins que le nouveau, sinon il le garde au sac. Rien ne
     * se perd, rien ne se crée, rien ne tombe par terre. Échec (poste inaccessible, outil déjà pris) : plus d'essai avant 5 min.
     */
    static final class PrendreOutil extends Actions.AuBureau {
        final Actions.Filtre sorte, bon;
        final double seuil;
        final String nom;
        final boolean manque, enchante;

        PrendreOutil(Actions.Filtre sorte, Actions.Filtre bon, double seuil, String nom, boolean manque, boolean enchante) {
            super("prendre_outil");
            this.sorte = sorte;
            this.bon = bon;
            this.seuil = seuil;
            this.nom = nom;
            this.manque = manque;
            this.enchante = enchante;
        }

        Actions.Etat tick(Travailleur t) {
            Actions.Etat e = prendre(t);
            if (e == Actions.Etat.ECHEC) {
                long plusTard = Cerveau.temps(t) + 300;
                t.memoire.putLong("outil_vu", plusTard);
                t.memoire.putLong("revue_outil", plusTard);
            }
            return e;
        }

        Actions.Etat prendre(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            ItemStack neuf = Guerrier.auDepot(b, depot(bon, enchante), seuil, true);
            if (neuf.isEmpty()) return echec("rien_au_depot"); // quelqu'un l'a pris entre-temps
            equiperMeilleur(t, sorte::objet);
            ItemStack vieux = t.getMainHandItem();
            t.setItemSlot(EquipmentSlot.MAINHAND, neuf);
            // La sienne retourne au dépôt si elle vaut moins que la neuve ; sinon (une meilleure qui ne suffit pas pour ce
            // minerai…), et pour ce qu'il tenait d'autre : au sac (s'il est plein, au dépôt).
            if (sorte.objet(vieux) && note(vieux) < note(neuf)) Guerrier.rendre(t, b, vieux);
            else if (!vieux.isEmpty()) Guerrier.garder(t, b, vieux);
            t.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            Cerveau.dire(t, "outil_depot", 60, manque ? "Je prends une " + nom + " au dépôt, et au travail."
                    : "Une meilleure " + nom + " au dépôt : je la prends, et je laisse la mienne.");
            return Actions.Etat.OK;
        }
    }

    // --- Le registre --------------------------------------------------------------------------------

    static final Map<String, Metier> TOUS = new LinkedHashMap<>();

    static {
        for (Metier m : List.of(new Mineur(), new Bucheron(), new Guerrier(), new Fermier(), new Eleveur())) TOUS.put(m.id, m);
    }

    static Metier de(String id) { return TOUS.get(id); }

    static boolean existe(String id) { return TOUS.containsKey(id); }

    static { TOUS.put("guerisseur", new Guerisseur()); } // lot 5 (à part : l'éleveur s'inscrit sur la ligne du haut)

    static { TOUS.put("pecheur", new Pecheur()); } // lot 6
    static { TOUS.put("chasseur", new Chasseur()); } // lot 6

    static List<String> actifs() {
        return TOUS.keySet().stream().filter(m -> !Reglages.actuel.metiers_inactifs.contains(m)).toList();
    }

    static String nom(String id) { Metier m = de(id); return m == null ? id : m.nom; }

    static String description(String id) { Metier m = de(id); return m == null ? "" : m.description; }

    // --- Les missions ------------------------------------------------------------------------------

    /** L'entrée du catalogue de ce métier, ou null. */
    static Commande catalogue(String metier, String id) {
        Metier m = de(metier);
        if (m == null || id == null) return null;
        for (Commande c : m.commandes) if (c.id.equals(id)) return c;
        return null;
    }

    /** L'entrée du catalogue de sa mission en cours, ou null. */
    static Commande commande(Travailleur t) {
        Travailleur.Mission m = t.missionActive();
        return m == null ? null : catalogue(t.metier, m.id);
    }

    static String nomCommande(String metier, String id) {
        Commande c = catalogue(metier, id);
        return c == null ? id : c.nom;
    }

    /** L'objet produit par la mission en cours (filtre qui ne prend rien s'il n'y en a pas). */
    static Actions.Filtre objetCommande(Travailleur t) {
        Commande c = commande(t);
        return c != null && c.objet != null ? c.objet : Actions.Filtre.de("");
    }

    /** Où en est une mission : le stock au dépôt (stock) ou ce qu'il a livré (quantité) ; -1 si on ne sait pas. */
    static int avancement(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null || c.objet == null) return -1;
        if (m.quantite()) return m.livre;
        Bureau.Entite b = Bureau.entite(t);
        return b == null ? -1 : b.compter(c.objet);
    }

    /** Une mission est remplie : quantité livrée, ou stock plein. Garder, faire la ronde ou escorter ne le sont jamais. */
    static boolean remplie(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null) return true;
        if (c.objet == null) return false;
        int a = avancement(t, m);
        return a >= 0 && a >= m.nombre;
    }

    /** « 10 fers », « un stock de 32 charbons », « Faire la ronde ». */
    static String nomMission(String metier, Travailleur.Mission m) {
        Commande c = catalogue(metier, m.id);
        if (c == null) return m.id;
        if (c.objet == null) return c.nom;
        if (m.quotidienne) return m.nombre + " " + c.nom.toLowerCase() + " chaque jour";
        return m.quantite() ? m.nombre + " " + c.nom.toLowerCase() : "un stock de " + m.nombre + " " + c.nom.toLowerCase();
    }

    /** « Fer : 7/10 livrés », « Charbon : 40/64 au dépôt (plein) », « Faire la ronde ». */
    static String avancementTexte(Travailleur t, Travailleur.Mission m) {
        Commande c = catalogue(t.metier, m.id);
        if (c == null) return m.id;
        if (c.objet == null) return c.nom;
        int a = avancement(t, m);
        String n = (a < 0 ? "?" : String.valueOf(a)) + "/" + m.nombre;
        return c.nom + " : " + (m.quantite() ? n + " livrés" : n + " au dépôt") + (remplie(t, m) ? (m.quotidienne ? " (fait pour aujourd'hui)" : m.quantite() ? " (fait)" : " (plein)") : m.quotidienne ? " (aujourd'hui)" : "");
    }

    /** Toutes ses missions, en une ligne (pour le panneau web). */
    static String avancementTexte(Travailleur t) {
        if (t.missions.isEmpty()) return "Aucune mission";
        return String.join(" ; ", t.missions.stream().map(m -> avancementTexte(t, m)).toList());
    }
}

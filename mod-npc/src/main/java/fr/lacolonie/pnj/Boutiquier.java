package fr.lacolonie.pnj;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * La boutique d'un ouvrier, côté jeu (lot 11, cahier 9.5 et 9.6). Sur l'ordre de son chef (« tiens la boutique »), il se
 * tient au poste de jour et vend ce qu'il y a au dépôt de son chef (bureau, coffres collés, réserve), aux prix du chef ou
 * de la table {@code prix} de reglages.json. Un autre joueur lui parle (clic droit : la fenêtre de conversation) ; il paie
 * en vraies pièces de sa bourse, qui vont à la caisse du poste, et la caisse rend la monnaie avec ses pièces
 * ({@link Boutique#regler}) : aucune pièce n'est créée ni perdue, pas de banque. Les affamés des autres colonies lui
 * achètent aussi à manger ({@link Achats}). Ce qu'il vend, ses prix, ses ventes du jour : dans sa mémoire (« boutique »),
 * sauvegardée avec lui. Aucune recherche à chaque tick : l'étal n'est compté qu'à une vente, à une question, ou une fois
 * par minute pour savoir s'il est vide (lettre au chef, une par jour au plus).
 */
final class Boutiquier {
    private Boutiquier() {}

    static final String CLE = "boutique";
    /** Un client lui parle à moins de tant de blocs. */
    static final double PORTEE = 8;
    /** La boutique est ouverte s'il est à moins de tant de blocs du poste. */
    static final double AU_POSTE = 6;

    static CompoundTag etat(Travailleur t) { return t.memoire.getCompound(CLE); }

    /** Son chef lui a dit de tenir la boutique (ouverte ou fermée selon l'heure). */
    static boolean tient(Travailleur t) { return t.chef != null && etat(t).getBoolean("ouverte"); }

    /** Il est derrière l'étal, maintenant : de jour, au travail, à son poste, ni épuisé ni à terre. */
    static boolean ouverte(Travailleur t) {
        return tient(t) && t.isAlive() && !t.aTerre && t.mode == Travailleur.Mode.TRAVAIL && t.bureau != null && t.enService()
                && !t.memoire.getBoolean("epuise") && !Abri.nuit(t.level()) && t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) <= AU_POSTE * AU_POSTE;
    }

    // --- Les ordres du chef -----------------------------------------------------------------------------------

    /** Ce que le chef a dit de la boutique : il l'applique et dit ce qu'il a compris (dans {@code r}). */
    static void ordre(Travailleur t, Boutique.Lu lu, List<String> r) {
        CompoundTag e = etat(t), prix = e.getCompound("prix"), exclus = e.getCompound("exclus");
        for (Boutique.Prix p : lu.prix) {
            String f = Consignes.filtre("", p.objet(), t);
            if (f == null) { r.add(texte(t, "boutique_inconnu", null, p.objet(), 0, "Je ne sais pas ce que c'est, « " + p.objet() + " »." )); continue; }
            prix.putInt(f, p.prix());
            exclus.remove(f);
            r.add(texte(t, "boutique_ordre_prix", null, nom(f, p.objet()), p.prix(), "Entendu : " + nom(f, p.objet()) + ", " + Boutique.pieces(p.prix()) + "."));
        }
        for (String o : lu.exclus) {
            String f = Consignes.filtre("", o, t);
            if (f == null) { r.add(texte(t, "boutique_inconnu", null, o, 0, "Je ne sais pas ce que c'est, « " + o + " »." )); continue; }
            exclus.putBoolean(f, true);
            prix.remove(f);
            r.add(texte(t, "boutique_ordre_exclu", null, nom(f, o), 0, "Compris : " + nom(f, o) + ", ce n'est plus à vendre."));
        }
        for (String o : lu.permis) {
            String f = o.equals("tout") ? "tout" : Consignes.filtre("", o, t);
            if (f == null) { r.add(texte(t, "boutique_inconnu", null, o, 0, "Je ne sais pas ce que c'est, « " + o + " »." )); continue; }
            if (f.equals("tout")) exclus = new CompoundTag();
            else exclus.remove(f);
            r.add(texte(t, "boutique_ordre_permis", null, f.equals("tout") ? "tout" : nom(f, o), 0, "Bien : " + nom(f, o) + ", de nouveau à vendre."));
        }
        e.put("prix", prix);
        e.put("exclus", exclus);
        if (lu.ouvrir != null) {
            if (lu.ouvrir && t.bureau == null) r.add(Messages.corps(t, "sans_poste"));
            else {
                e.putBoolean("ouverte", lu.ouvrir);
                if (lu.ouvrir) {
                    // Il laisse ce qu'on lui avait demandé et va au poste (le cerveau l'y mène : tenir).
                    Consignes.annuler(t);
                    if (t.mode == Travailleur.Mode.SUIVRE || t.mode == Travailleur.Mode.ATTENDRE) { t.mode = Travailleur.Mode.TRAVAIL; t.majNom(); }
                    t.finirAction("ordre");
                }
                r.add(texte(t, lu.ouvrir ? "boutique_ordre_ouvrir" : "boutique_ordre_fermer", null, null, 0,
                        lu.ouvrir ? "Entendu, chef : je tiens la boutique au poste, de jour." : "Bien, chef : je ferme la boutique."));
            }
        }
        t.memoire.put(CLE, e);
    }

    /** Le nom d'une marchandise désignée par le chef : celui de son premier objet (« lingot de fer »), sinon le mot dit. */
    static String nom(String filtre, String mot) {
        String premier = filtre.split("\\|")[0];
        return Consignes.NOMS.getOrDefault(premier, mot);
    }

    // --- Le cerveau ----------------------------------------------------------------------------------------------

    /**
     * Le cerveau, au travail : vrai s'il tient la boutique (ses missions attendent). Il va au poste, s'y tient, l'annonce
     * de temps en temps ; une fois par minute il regarde si l'étal est vide (lettre au chef, une par jour au plus).
     */
    static boolean tenir(Travailleur t) {
        if (!tient(t) || t.bureau == null) return false;
        if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 4 * 4) { Cerveau.rentrer(t); return true; }
        Bureau.Entite b = Bureau.entite(t);
        if (b != null && Cerveau.parfois(t, "boutique_etal", 60)) {
            if (etal(t, b).isEmpty()) Cerveau.ecrire(t, "boutique_vide", Cerveau.UN_JOUR, "boutique_vide");
            else if (Cerveau.parfois(t, "boutique_ouverte", 600)) Achats.dire(t, "boutique_ouverte", null, null, 0);
        }
        Cerveau.attendre(t, 5);
        return true;
    }

    // --- L'étal ----------------------------------------------------------------------------------------------------

    /** Une marchandise de l'étal : un objet ordinaire (une pile d'une unité), son nom, son prix d'une unité, combien il en reste. */
    record Marchandise(ItemStack modele, String nom, int prix, int nombre) {
        Predicate<ItemStack> meme() { return s -> ItemStack.isSameItemSameComponents(s, modele); }
    }

    /**
     * Jamais à vendre : les pièces, les outils, armes et armures, et tout objet nommé, enchanté ou rempli (livre écrit,
     * potion, boîte, sac). Le reste se vend s'il a un prix.
     */
    static boolean marchandise(ItemStack s) {
        return !s.isEmpty() && Reglages.piece(s) < 0 && !s.isDamageableItem() && s.getComponentsPatch().isEmpty()
                && !(s.getItem() instanceof BundleItem) && !(Block.byItem(s.getItem()) instanceof ShulkerBoxBlock) && !s.is(Items.WRITTEN_BOOK);
    }

    /**
     * Le prix d'une unité, ou -1 si ce n'est pas à vendre : exclu par le chef, sinon le prix du chef, sinon celui de la
     * table {@code prix}, sinon une nourriture ordinaire au prix d'{@link Economie#prix}. Un objet sans prix connu (du fer,
     * un diamant) ne se vend pas tant que le chef n'en a pas fixé un.
     */
    static int prix(Travailleur t, ItemStack s) {
        if (!marchandise(s)) return -1;
        CompoundTag e = etat(t), exclus = e.getCompound("exclus"), prix = e.getCompound("prix");
        for (String k : exclus.getAllKeys()) if (Actions.Filtre.de(k).objet(s)) return -1;
        for (String k : prix.getAllKeys()) if (Actions.Filtre.de(k).objet(s)) return Math.max(1, prix.getInt(k));
        Integer p = Reglages.actuel.prix.get(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        if (p != null && p > 0) return p;
        return Achats.vendable(s) ? Achats.prix(s) : -1;
    }

    /** Ce qui est à vendre au dépôt du poste (bureau, coffres collés, réserve), le plus abondant d'abord. */
    static List<Marchandise> etal(Travailleur t, Bureau.Entite b) {
        Map<Item, int[]> nombres = new LinkedHashMap<>();
        Map<Item, ItemStack> modeles = new HashMap<>();
        Consumer<ItemStack> voir = s -> {
            if (!marchandise(s)) return;
            modeles.putIfAbsent(s.getItem(), s.copyWithCount(1));
            nombres.computeIfAbsent(s.getItem(), k -> new int[1])[0] += s.getCount();
        };
        for (int i = 0; i < b.depot.getContainerSize(); i++) voir.accept(b.depot.getItem(i));
        for (IItemHandler h : b.voisins()) for (int i = 0; i < h.getSlots(); i++) voir.accept(h.getStackInSlot(i));
        for (ItemStack s : b.reserveVue()) voir.accept(s);
        List<Marchandise> l = new ArrayList<>();
        for (var x : nombres.entrySet()) {
            ItemStack m = modeles.get(x.getKey());
            int p = prix(t, m);
            if (p > 0) l.add(new Marchandise(m, Consignes.nom(m), p, x.getValue()[0]));
        }
        l.sort((a, c) -> Integer.compare(c.nombre(), a.nombre()));
        return l;
    }

    static String liste(List<Marchandise> etal) {
        return Boutique.liste(etal.stream().map(m -> new Boutique.Article(m.nom(), m.prix(), m.nombre())).toList());
    }

    // --- La vente ----------------------------------------------------------------------------------------------------

    static final String VENDU = "", RUPTURE = "rupture", PAS_ASSEZ = "pas_assez", MONNAIE = "monnaie";

    /**
     * Vend {@code n} unités de {@code m} (moins s'il en reste moins) payées avec les pièces de {@code bourse} : les pièces
     * passent exactement à la caisse du poste, la caisse rend la monnaie avec les siennes, et l'acheteur reçoit les objets.
     * {@code ajouter} met une pile dans la bourse et rend ce qui n'y entre pas (confié à {@code surplus}). Renvoie
     * {@link #VENDU}, ou pourquoi rien n'a bougé ({@link #RUPTURE}, {@link #PAS_ASSEZ}, {@link #MONNAIE}).
     */
    static String vendre(Travailleur t, Bureau.Entite b, Marchandise m, int n, Container bourse, UnaryOperator<ItemStack> ajouter,
                         Consumer<ItemStack> surplus, Consumer<ItemStack> recevoir) {
        n = Math.min(n, b.compter(m.meme()));
        if (n <= 0) return RUPTURE;
        long total = (long) m.prix() * n;
        int[] v = Reglages.valeurs(), client = Reglages.quantites(bourse), caisse = Reglages.quantites(b.caisse);
        if (Monnaie.total(v, client) < total) return PAS_ASSEZ;
        Boutique.Reglement rg = Boutique.regler(v, client, caisse, total);
        if (rg == null) return MONNAIE;
        List<ItemStack> pris = b.prendre(m.meme(), n);
        if (pris.stream().mapToInt(ItemStack::getCount).sum() != n) { pris.forEach(b::ranger); return RUPTURE; } // jamais : compté juste avant
        changer(bourse, client, rg.client(), ajouter, surplus);
        changer(b.caisse, caisse, rg.caisse(), b.caisse::addItem, t::ranger); // une caisse pleine : le reste dans son sac, jamais perdu
        b.setChanged();
        pris.forEach(recevoir);
        noter(t, m.nom(), n, total);
        Sons.paie(t);
        return VENDU;
    }

    /** Les pièces d'un contenant passent de {@code avant} à {@code apres} (une case par pièce) : on retire, puis on ajoute. */
    static void changer(Container c, int[] avant, int[] apres, UnaryOperator<ItemStack> ajouter, Consumer<ItemStack> surplus) {
        for (int p = 0; p < avant.length; p++) {
            int moins = avant[p] - apres[p];
            for (int i = 0; i < c.getContainerSize() && moins > 0; i++) {
                ItemStack s = c.getItem(i);
                if (Reglages.piece(s) != p) continue;
                int k = Math.min(moins, s.getCount());
                s.shrink(k);
                moins -= k;
            }
        }
        for (int p = 0; p < avant.length; p++) {
            Item piece = BuiltInRegistries.ITEM.get(ResourceLocation.parse(Reglages.actuel.monnaie.get(p).objet));
            for (int plus = apres[p] - avant[p]; plus > 0 && piece != Items.AIR; ) {
                ItemStack s = new ItemStack(piece, Math.min(plus, piece.getDefaultMaxStackSize()));
                plus -= s.getCount();
                ItemStack reste = ajouter.apply(s);
                if (!reste.isEmpty()) surplus.accept(reste);
            }
        }
        c.setChanged();
    }

    /** Ses ventes du jour (remises à zéro chaque jour de jeu). */
    static void noter(Travailleur t, String nom, int n, long total) {
        CompoundTag e = etat(t);
        if (e.getLong("jour") != t.jour()) { e.putLong("jour", t.jour()); e.putLong("recette", 0); e.put("vendu", new CompoundTag()); }
        e.putLong("recette", e.getLong("recette") + total);
        CompoundTag v = e.getCompound("vendu");
        v.putInt(nom, v.getInt(nom) + n);
        e.put("vendu", v);
        t.memoire.put(CLE, e);
    }

    // --- Les clients -----------------------------------------------------------------------------------------------

    /** Un client lui fait un clic droit : il le salue, et lui dit ce qu'il vend (ou que c'est fermé). */
    static void accueillir(Travailleur t, ServerPlayer p) {
        if (!Cerveau.parfois(t, "boutique_accueil", 20)) return;
        Bureau.Entite b = Bureau.entite(t);
        if (!ouverte(t) || b == null) { t.dire(texte(t, "boutique_fermee", null, null, 0, "C'est fermé. Revenez demain.")); return; }
        List<Marchandise> etal = etal(t, b);
        t.dire(etal.isEmpty() ? texte(t, "vente_rupture", null, null, 0, "Je n'ai plus rien à vendre.")
                : texte(t, "boutique_ouverte", null, null, 0, "La boutique est ouverte !") + " " + catalogue(t, etal));
    }

    static String catalogue(Travailleur t, List<Marchandise> etal) {
        String l = liste(etal);
        return texte(t, "vente_etal", null, l, 0, "J'ai " + l + ". Que te faut-il ?");
    }

    /** Un autre joueur que son chef lui parle : vrai s'il tient la boutique (c'est elle qui répond, pas la conversation). */
    static boolean client(Travailleur t, ServerPlayer p, String texte) {
        if (!tient(t) || t.aTerre) return false;
        if (p.level() != t.level() || p.distanceToSqr(t) > PORTEE * PORTEE || texte.isBlank() || texte.length() > 256) return true;
        t.getLookControl().setLookAt(p);
        t.dire(repondre(t, p, texte));
        return true;
    }

    /** Sa réponse à un client : la liste, ou la vente (ou pourquoi elle ne se fait pas). */
    static String repondre(Travailleur t, ServerPlayer p, String texte) {
        Bureau.Entite b = Bureau.entite(t);
        if (!ouverte(t) || b == null) return texte(t, "boutique_fermee", null, null, 0, "C'est fermé. Revenez demain.");
        List<Marchandise> etal = etal(t, b);
        if (etal.isEmpty()) return texte(t, "vente_rupture", null, null, 0, "Je n'ai plus rien à vendre.");
        Boutique.Demande d = Boutique.demande(texte);
        String f = d.catalogue() ? null : Consignes.filtre("", d.objet(), t);
        if (f == null) return catalogue(t, etal);
        Actions.Filtre fl = Actions.Filtre.de(f);
        Marchandise m = etal.stream().filter(x -> fl.objet(x.modele())).findFirst().orElse(null);
        if (m == null) return texte(t, "vente_absent", null, null, 0, "Ça, je n'en vends pas.") + " " + catalogue(t, etal);
        int n = Math.min(d.nombre(), m.nombre());
        String quoi = Souvenirs.nombre(n, m.nom());
        String r = vendre(t, b, m, d.nombre(), p.getInventory(), s -> { p.getInventory().add(s); return s; }, p::spawnAtLocation,
                s -> { p.getInventory().add(s); if (!s.isEmpty()) p.spawnAtLocation(s); });
        String nom = p.getName().getString();
        return switch (r) {
            case VENDU -> texte(t, "vente_conclue", nom, quoi, (int) Math.min(Integer.MAX_VALUE, (long) m.prix() * n), "Voilà " + quoi + ".")
                    + " " + texte(t, "vente_faite", nom, null, 0, "Merci.");
            case PAS_ASSEZ -> texte(t, "vente_pas_assez", nom, quoi, (int) Math.min(Integer.MAX_VALUE, (long) m.prix() * n),
                    "Il te manque des pièces : ça fait " + Boutique.pieces((long) m.prix() * n) + ".");
            case MONNAIE -> texte(t, "vente_sans_monnaie", nom, null, 0, "Je n'ai pas la monnaie. Apporte le compte juste.");
            default -> texte(t, "vente_rupture", null, null, 0, "Je n'ai plus rien à vendre.");
        };
    }

    /** Un affamé d'une autre colonie (Achats) : la nourriture qu'il trouverait à cette boutique, si elle est ouverte. */
    static Marchandise manger(Travailleur v) {
        if (!ouverte(v)) return null;
        Bureau.Entite b = Bureau.entite(v);
        if (b == null) return null;
        for (Marchandise m : etal(v, b)) if (Achats.vendable(m.modele())) return m;
        return null;
    }

    /** L'affamé {@code a}, arrivé devant la boutique de {@code v}, achète une part de {@code m} au prix du chef (sans marchander). */
    static boolean vendreAPnj(Travailleur a, Travailleur v, Marchandise m) {
        Bureau.Entite b = Bureau.entite(v);
        String objet = Achats.objet(m.modele());
        String r = b == null ? RUPTURE : vendre(v, b, m, 1, a.inventaire, a.inventaire::addItem, a::spawnAtLocation, a::ranger);
        if (r.equals(RUPTURE)) { Achats.dire(v, "vente_rupture", a.prenom, null, 0); return false; }
        if (!r.equals(VENDU)) { Achats.dire(a, "achat_pas_assez", v.prenom, objet, m.prix()); return false; }
        Achats.dire(a, "achat_demande", v.prenom, objet, 0);
        Achats.dire(v, "vente_conclue", a.prenom, objet, m.prix());
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        CompoundTag x = new CompoundTag();
        x.putString("objet", objet);
        x.putString("nom", v.prenom);
        x.putInt("prix", m.prix());
        a.memoire.put("achat", x);
        return true;
    }

    // --- Ce qu'il en dit à son chef --------------------------------------------------------------------------------

    /** « Qu'as-tu vendu ? » : ses ventes du jour. */
    static String ventes(Travailleur t) {
        CompoundTag e = etat(t);
        if (e.getLong("jour") != t.jour() || e.getLong("recette") <= 0)
            return tient(t) ? "Rien vendu aujourd'hui, chef." : "Je ne tiens pas de boutique, chef. Dis-moi « tiens la boutique », et je m'y mets.";
        List<String> l = new ArrayList<>();
        CompoundTag v = e.getCompound("vendu");
        for (String k : v.getAllKeys()) l.add(Souvenirs.nombre(v.getInt(k), k));
        String quoi = l.size() == 1 ? l.get(0) : String.join(", ", l.subList(0, l.size() - 1)) + " et " + l.get(l.size() - 1);
        return "Aujourd'hui, j'ai vendu " + quoi + ", pour " + Boutique.pieces(e.getLong("recette")) + " en tout. Les pièces sont à la caisse du poste.";
    }

    /** « Qu'est-ce que tu vends ? », dit par le chef. */
    static String etalPourLeChef(Travailleur t) {
        if (!tient(t)) return "Je ne tiens pas de boutique, chef. Dis-moi « tiens la boutique », et je m'y mets.";
        Bureau.Entite b = Bureau.entite(t);
        List<Marchandise> etal = b == null ? List.of() : etal(t, b);
        return etal.isEmpty() ? "Rien à vendre au dépôt pour l'instant, chef." : "À l'étal : " + liste(etal) + ".";
    }

    /** La réplique {@code cle} (economie.txt) ; « 1 pièces » devient « 1 pièce » ; {@code secours} si elle manque. */
    static String texte(Travailleur t, String cle, String nom, String objet, int prix, String secours) {
        Map<String, String> v = new HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        if (nom != null) v.put("nom", nom);
        if (objet != null) v.put("objet", objet);
        if (prix > 0) v.put("prix", Integer.toString(prix));
        String r = Economie.accorder(Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble));
        return r != null ? r : secours;
    }
}

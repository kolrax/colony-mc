package fr.lacolonie.pnj;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Les achats entre PNJ (lot 11, cahier 9.4) : un ouvrier affamé, sans rien au dépôt de son chef, achète à manger à un
 * producteur proche (cuisinier, fermier, pêcheur, éleveur), du même chef ou d'un autre, avec les pièces de sa paie (dans
 * son sac). Le vendeur prend dans son sac (les pièces vont dans son sac) ou au dépôt de son chef (les pièces vont dans la
 * caisse de ce poste). Une recherche au plus toutes les {@link #ENTRE} secondes. Prix et marchandage : {@link Economie}.
 */
final class Achats {
    private Achats() {}

    static final Set<String> VENDEURS = Set.of("cuisinier", "fermier", "pecheur", "eleveur");
    /** Une recherche de vendeur au plus toutes les tant de secondes (3 minutes). */
    static final int ENTRE = 180;

    /** Ce qui se vend : une nourriture ordinaire, sans effet néfaste (comme ce qu'il mange, Travailleur.manger). */
    static boolean vendable(ItemStack s) {
        FoodProperties fp = s.getFoodProperties(null);
        return fp != null && fp.nutrition() > 0 && s.getRarity() == Rarity.COMMON && Reglages.piece(s) < 0
                && fp.effects().stream().noneMatch(e -> e.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
    }

    static int prix(ItemStack s) {
        FoodProperties fp = s.getFoodProperties(null);
        return Economie.prix(Reglages.actuel.prix, BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), fp == null ? 0 : fp.nutrition());
    }

    /** La case d'un contenant où prendre ce qui se vend, ou -1. */
    static int etal(Container c) {
        if (c == null) return -1;
        for (int i = 0; i < c.getContainerSize(); i++) if (vendable(c.getItem(i))) return i;
        return -1;
    }

    /** Ce qu'un vendeur d'un autre chef laisse toujours au dépôt de son poste, pour les siens. */
    static final int RESERVE = 2;

    /** Les parts vendables d'un contenant. */
    static int parts(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (vendable(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /**
     * Le contenant où le vendeur prend pour cet acheteur : son sac d'abord, sinon le dépôt de son poste ; null s'il n'a rien.
     * Pour l'ouvrier d'un autre chef, le dépôt garde au moins {@link #RESERVE} parts : on ne vide pas le garde-manger des voisins.
     */
    static Container stock(Travailleur v, Travailleur acheteur) {
        if (etal(v.inventaire) >= 0) return v.inventaire;
        Bureau.Entite b = Bureau.entite(v);
        if (b == null || etal(b.depot) < 0) return null;
        boolean voisin = acheteur.chef == null || !acheteur.chef.equals(v.chef);
        return !voisin || parts(b.depot) > RESERVE ? b.depot : null;
    }

    /** Jamais d'achat chez un chef avec qui son chef est en guerre (Courrier.guerres). */
    static boolean ennemi(Travailleur t, Travailleur v) {
        return t.chef != null && v.chef != null && t.level().getServer() != null && Courrier.de(t.level().getServer()).enGuerre(t.chef, v.chef);
    }

    /**
     * Le cerveau, affamé et sans rien au dépôt : vrai s'il part acheter à manger. S'il n'a pas de quoi payer le vendeur le
     * plus proche, il le dit (au plus toutes les 10 minutes) et c'est faux : il le demande alors à son chef, comme avant.
     */
    static boolean acheter(Travailleur t) {
        int d = Reglages.actuel.achat_distance;
        if (d <= 0 || !Cerveau.parfois(t, "achat", ENTRE)) return false;
        Travailleur vendeur = null;
        double meilleur = Double.MAX_VALUE;
        for (Travailleur v : t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(d),
                v -> v != t && v.isAlive() && !v.aTerre && (VENDEURS.contains(v.metier) || Boutiquier.tient(v)) && v.distanceToSqr(t) <= d * d && !ennemi(t, v))) {
            // Un boutiquier ouvert vend aussi à manger, au prix de son chef (Boutiquier).
            if (v.distanceToSqr(t) < meilleur && (Boutiquier.manger(v) != null || VENDEURS.contains(v.metier) && stock(v, t) != null)) { meilleur = v.distanceToSqr(t); vendeur = v; }
        }
        if (vendeur == null) return false;
        Boutiquier.Marchandise m = Boutiquier.manger(vendeur);
        ItemStack s;
        int prix;
        if (m != null) { s = m.modele(); prix = m.prix(); }
        else { Container c = stock(vendeur, t); s = c.getItem(etal(c)); prix = prix(s); }
        if (Reglages.valeur(t.inventaire) < prix) {
            if (Cerveau.parfois(t, "achat_pas_assez", 600)) dire(t, "achat_pas_assez", vendeur.prenom, objet(s), prix);
            return false;
        }
        Cerveau.lancer(t, new Acheter(vendeur));
        return true;
    }

    /** « un peu de pain ». */
    static String objet(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        return Consignes.NOMS.containsKey(id) ? "un peu de " + Consignes.NOMS.get(id) : "de quoi manger"; // jamais le nom anglais du serveur
    }

    /** Il dit la réplique {@code cle} d'economie.txt ; « 1 pièces » devient « 1 pièce ». */
    static void dire(Travailleur t, String cle, String nom, String objet, int prix) {
        Map<String, String> v = new HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        if (nom != null) v.put("nom", nom);
        if (objet != null) v.put("objet", objet);
        if (prix > 0) v.put("prix", Integer.toString(prix));
        t.dire(Economie.accorder(Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble)));
    }

    /** Il va trouver le vendeur et lui achète une part de nourriture (une minute au plus). */
    static final class Acheter extends Actions.Action {
        final Travailleur vendeur;

        Acheter(Travailleur vendeur) { super("acheter"); this.vendeur = vendeur; this.mobile = true; }

        Actions.Etat tick(Travailleur t) {
            if (!vendeur.isAlive() || vendeur.aTerre || age > 20 * 60) return echec("vendeur_parti");
            if (t.distanceToSqr(vendeur) > 2.5 * 2.5) {
                if (coince()) return echec("coince");
                marcher(t, vendeur.position(), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(vendeur);
            vendeur.getLookControl().setLookAt(t);
            return conclure(t, vendeur) ? Actions.Etat.OK : echec("pas_d_achat");
        }
    }

    /**
     * Le marché, sur place : il demande (ou marchande), paie, et reçoit une part. Les pièces passent exactement de son sac au
     * sac du vendeur (ou à la caisse du poste, si la part vient du dépôt). Faux si rien ne s'est vendu.
     */
    static boolean conclure(Travailleur t, Travailleur v) {
        Boutiquier.Marchandise m = ennemi(t, v) ? null : Boutiquier.manger(v);
        if (m != null) return Boutiquier.vendreAPnj(t, v, m); // sa boutique : le dépôt de son chef, au prix du chef
        Container c = ennemi(t, v) ? null : stock(v, t);
        if (c == null) { dire(v, "vente_rupture", t.prenom, null, 0); return false; }
        int i = etal(c);
        ItemStack s = c.getItem(i);
        String objet = objet(s);
        int base = prix(s), prix = Economie.marchander(t.temperament, base, t.getRandom()::nextDouble);
        if (Economie.payer(Reglages.valeurs(), Reglages.quantites(t.inventaire), Reglages.quantites(v.inventaire), prix) == null) {
            dire(t, "achat_pas_assez", v.prenom, objet, prix);
            return false;
        }
        if (prix < base) dire(t, "achat_marchande", v.prenom, objet, base);
        else dire(t, "achat_demande", v.prenom, objet, 0);
        Reglages.payer(t.inventaire, prix, t.inventaire::addItem, r -> t.spawnAtLocation(r));
        Bureau.Entite poste = c == v.inventaire ? null : Bureau.entite(v);
        for (ItemStack p : Reglages.pieces(prix)) {
            ItemStack reste = poste != null ? poste.caisse.addItem(p) : p;
            if (!reste.isEmpty()) v.ranger(reste);
        }
        t.ranger(c.removeItem(i, 1));
        c.setChanged();
        dire(v, "achat_accepte", t.prenom, objet, prix);
        t.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        Sons.paie(v);
        CompoundTag a = new CompoundTag();
        a.putString("objet", objet);
        a.putString("nom", v.prenom);
        a.putInt("prix", prix);
        t.memoire.put("achat", a);
        return true;
    }

    /** Sa réponse au chef qui demande ce qu'il a acheté. */
    static String dernierAchat(Travailleur t) {
        if (!t.memoire.contains("achat")) return "Je n'ai rien acheté, chef.";
        CompoundTag a = t.memoire.getCompound("achat");
        Map<String, String> v = new HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        v.put("nom", a.getString("nom"));
        v.put("objet", a.getString("objet"));
        v.put("prix", Integer.toString(a.getInt("prix")));
        String r = Economie.accorder(Vie.tirer("chef_achat_info", t.temperament, v, t.getRandom()::nextDouble));
        return r != null ? r : "J'ai acheté " + v.get("objet") + " à " + v.get("nom") + ".";
    }

    /** Sa réponse au chef qui demande combien il a de pièces. */
    static String bourse(Travailleur t) {
        long n = Reglages.valeur(t.inventaire);
        if (n <= 0) {
            String p = Vie.tirer("pauvre_plainte", t.temperament, Map.of("chef", "chef"), t.getRandom()::nextDouble);
            return p != null ? p : "Ma bourse est vide.";
        }
        return "J'ai " + Reglages.texte(n) + " dans ma bourse.";
    }
}

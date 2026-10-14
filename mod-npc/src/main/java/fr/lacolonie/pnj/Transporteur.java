package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Trajets.Trajet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Le transporteur (lot 6, cahier 4.2 et 4.3) : il porte d'un coffre à l'autre, ou entre un coffre et le dépôt du poste, les
 * trajets que son chef lui a montrés ({@link Trajets}, 6 au plus, gardés dans {@code memoire}), l'un après l'autre et en
 * boucle. Un « coffre », c'est tout bloc qui expose la capacité d'objets de NeoForge (coffre, tonneau, et les dépôts ou
 * entonnoirs de Create) : il sert de main-d'œuvre aux machines.
 * <p>
 * Un tour : à la source, il prend ce qu'il faut (jamais plus que la place dans son sac), va à la destination et dépose ; ce
 * qui n'y rentre pas retourne à la source, sinon au dépôt : rien ne se perd. Destination pleine : lettre (une par jour et
 * par coffre), le trajet attend 2 minutes. Source vide : trajet suivant ; tous vides plusieurs fois de suite : lettre et
 * pause. Coffre disparu : lettre, trajet suspendu (repris s'il revient). Jamais le coffre d'un voisin ni d'un village, jamais
 * à plus de {@link Trajets#ZONE} blocs du poste.
 * <p>
 * Pas de lag : aucune recherche de coffre ; il ne lit que les coffres de ses trajets, en y arrivant.
 */
final class Transporteur extends Metier {
    /** La pause quand tout est vide, et l'attente d'un trajet dont la destination est pleine (en secondes). */
    static final int PAUSE = 60, PLEIN = 120, REVUE = 60;

    Transporteur() {
        super("transporteur", "Transporteur", "Porte les marchandises d'un coffre à l'autre, ou entre un coffre et le dépôt du poste, sur les trajets "
                + "que lui montre son chef, et les refait en boucle tant qu'il y a à porter. Il ne touche jamais aux coffres d'un voisin.", false);
    }

    // --- Ses trajets, gardés avec lui ------------------------------------------------------------------------------

    static List<Trajet> lire(Travailleur t) {
        List<Trajet> r = new ArrayList<>();
        ListTag l = t.memoire.getList("trajets", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            r.add(new Trajet(c.getLong("s"), c.getLong("d"), c.getString("o"), c.getBoolean("x"), c.getLong("a")));
        }
        return r;
    }

    static void ecrire(Travailleur t, List<Trajet> trajets) {
        ListTag l = new ListTag();
        for (Trajet x : trajets) {
            CompoundTag c = new CompoundTag();
            c.putLong("s", x.source());
            c.putLong("d", x.dest());
            c.putString("o", x.objet());
            if (x.suspendu()) c.putBoolean("x", true);
            if (x.attente() > 0) c.putLong("a", x.attente());
            l.add(c);
        }
        t.memoire.put("trajets", l);
    }

    /** Remplace le trajet {@code avant} (le même source, destination, objet) par {@code apres}. */
    static void changer(Travailleur t, Trajet avant, Trajet apres) {
        List<Trajet> l = lire(t);
        for (int i = 0; i < l.size(); i++) if (l.get(i).meme(avant)) l.set(i, apres);
        ecrire(t, l);
    }

    /** Ce point, comme on le dit dans une lettre : « notre poste », ou « 120 64 -38 ». */
    static String lieu(long p) {
        if (p == Trajets.DEPOT) return "notre poste";
        BlockPos b = BlockPos.of(p);
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    /** Les poignées d'un bout de trajet (le dépôt : le bureau et ses coffres collés) ; null si le coffre n'est plus là. */
    static List<IItemHandler> poignees(Travailleur t, long p) {
        Level l = t.level();
        if (p == Trajets.DEPOT) {
            if (t.bureau == null || !(l.getBlockEntity(t.bureau) instanceof Bureau.Entite b)) return null;
            List<IItemHandler> r = new ArrayList<>();
            r.add(new InvWrapper(b.depot));
            r.addAll(b.voisins());
            return r;
        }
        BlockPos b = BlockPos.of(p);
        IItemHandler h = l.getCapability(Capabilities.ItemHandler.BLOCK, b, null);
        return h == null ? null : List.of(h);
    }

    /** Un bloc à inventaire (coffre, tonneau, dépôt de Create…) ; le bureau compte pour le dépôt. */
    static boolean coffre(Level l, BlockPos p) {
        return l.getBlockEntity(p) instanceof Bureau.Entite || l.getCapability(Capabilities.ItemHandler.BLOCK, p, null) != null;
    }

    /** Le bloc regardé comme bout de trajet : le bureau de son poste est le dépôt. */
    static long point(Travailleur t, BlockPos p) {
        return p == null || p.equals(t.bureau) || t.level().getBlockEntity(p) instanceof Bureau.Entite ? Trajets.DEPOT : p.asLong();
    }

    /** Pourquoi il refuse ce coffre (null : il l'accepte). */
    static String refus(Travailleur t, String mode, BlockPos vu) {
        if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef.";
        if (mode.equals("oublier_trajets") || vu == null) return null;
        if (!coffre(t.level(), vu)) return "Ce n'est pas un coffre, ça, chef. Montre-moi un coffre, un tonneau, ou une caisse.";
        if (point(t, vu) == Trajets.DEPOT) return mode.equals("trajet") ? "C'est notre dépôt, ça, chef. Montre-moi l'autre coffre." : null;
        if (Fermier.distance(t.bureau, vu) > (double) Trajets.ZONE * Trajets.ZONE || Math.abs(vu.getY() - t.bureau.getY()) > Trajets.ZONE)
            return "C'est trop loin du poste, chef : je ne porte pas à plus de soixante-quatre pas de chez nous.";
        if (!Fermier.aLui(t, vu)) return "Ce coffre n'est pas à nous, chef : je n'y touche pas.";
        return null;
    }

    // --- Son travail ----------------------------------------------------------------------------------------------

    @Override
    void travailler(Travailleur t) {
        if (t.bureau == null) { Cerveau.attendre(t, 5); return; }
        long maintenant = Cerveau.temps(t);
        // Une charge restée sur lui (un tour interrompu, un retour refusé) : au dépôt. Rien ne se perd.
        if (t.memoire.getBoolean("en_charge")) {
            t.memoire.remove("en_charge");
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }
        if ("depot_plein".equals(t.resultat)) Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
        List<Trajet> l = lire(t);
        revoir(t, l, maintenant);
        int i = t.memoire.getLong("transport_repos") > maintenant ? -1 : Trajets.suivant(l, t.memoire.getInt("trajet_n"), maintenant);
        if (i < 0) {
            if (l.isEmpty()) Cerveau.dire(t, "sans_trajet", 600, "Je n'ai rien à porter. Montre-moi un coffre, et dis-moi où en porter le contenu.");
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 6 * 6) Cerveau.rentrer(t);
            else Cerveau.attendre(t, 10);
            return;
        }
        t.memoire.putInt("trajet_n", i);
        Cerveau.lancer(t, new Tour(l.get(i)));
    }

    /** Toutes les minutes, ses trajets suspendus dont le coffre est revenu reprennent. */
    static void revoir(Travailleur t, List<Trajet> l, long maintenant) {
        if (t.memoire.getLong("trajets_revus") > maintenant || l.stream().noneMatch(Trajet::suspendu)) return;
        t.memoire.putLong("trajets_revus", maintenant + REVUE);
        boolean change = false;
        for (int i = 0; i < l.size(); i++) {
            Trajet x = l.get(i);
            if (x.suspendu() && present(t, x.source()) && present(t, x.dest())) { l.set(i, x.suspendre(false)); change = true; }
        }
        if (change) ecrire(t, l);
    }

    static boolean present(Travailleur t, long p) {
        return p == Trajets.DEPOT || t.level().isLoaded(BlockPos.of(p)) && poignees(t, p) != null;
    }

    /**
     * Un tour d'un trajet : à la source, il charge ; à la destination, il décharge ; ce qui n'est pas entré retourne à la
     * source (et ce qu'elle refuse, au dépôt, par {@code en_charge}).
     */
    static final class Tour extends Actions.Action {
        final Trajet trajet;
        final Actions.Filtre filtre;
        /** Ce qu'il porte (les piles prises), pour ne déposer que cela, jamais ses propres affaires. */
        final List<ItemStack> charge = new ArrayList<>();
        int etape;

        Tour(Trajet trajet) {
            super("transport");
            this.trajet = trajet;
            this.filtre = Actions.Filtre.de(trajet.objet().isEmpty() ? "tout" : trajet.objet());
            travail = true;
        }

        /** Il marche jusqu'au bout {@code p} ; vrai une fois à côté. */
        boolean arriver(Travailleur t, long p) {
            BlockPos b = p == Trajets.DEPOT ? t.bureau : BlockPos.of(p);
            if (t.position().distanceToSqr(Vec3.atBottomCenterOf(b)) <= 3.2 * 3.2) {
                t.getNavigation().stop();
                t.getLookControl().setLookAt(Vec3.atCenterOf(b));
                return true;
            }
            marcher(t, Vec3.atBottomCenterOf(b), 1.0);
            return false;
        }

        Actions.Etat tick(Travailleur t) {
            if (t.bureau == null) return echec("pas_de_bureau");
            long p = etape == 0 || etape == 2 ? trajet.source() : trajet.dest();
            if (!arriver(t, p)) {
                if (coince() || age > 20 * 60 * 3) {
                    // Il n'y arrive pas : le trajet attend, et ce qu'il porte ira au dépôt.
                    changer(t, trajet, trajet.attendre(Cerveau.temps(t) + PLEIN));
                    return echec("bloque");
                }
                return Actions.Etat.EN_COURS;
            }
            List<IItemHandler> h = poignees(t, p);
            if (h == null) {
                // Le coffre n'est plus là : le trajet est suspendu, il l'écrit ; ce qu'il porte retourne à la source, sinon au dépôt.
                changer(t, trajet, trajet.suspendre(true));
                Cerveau.ecrire(t, "introuvable_" + p, Cerveau.UN_JOUR, "coffre_introuvable", lieu(p));
                Besoins.parler(t, "transport_coffre_introuvable", null);
                if (etape == 1 && !charge.isEmpty()) { etape = 2; nouveauBut(); return Actions.Etat.EN_COURS; }
                return echec("introuvable");
            }
            t.swing(InteractionHand.MAIN_HAND);
            switch (etape) {
                case 0 -> {
                    prendre(t, h);
                    if (charge.isEmpty()) return vide(t);
                    t.memoire.putInt("trajet_vides", 0);
                    t.memoire.putBoolean("en_charge", true);
                    Besoins.parler(t, "transport_charge", null);
                    etape = 1;
                    nouveauBut();
                }
                case 1 -> {
                    if (!poser(t, p, h)) {
                        // Plus de place : une lettre par jour et par coffre ; le trajet attend ; le reste retourne à la source.
                        Cerveau.ecrire(t, "coffre_plein_" + p, Cerveau.UN_JOUR, "coffre_plein", lieu(p));
                        Besoins.parler(t, "transport_coffre_plein", null);
                        changer(t, trajet, trajet.attendre(Cerveau.temps(t) + PLEIN));
                        etape = 2;
                        nouveauBut();
                        return Actions.Etat.EN_COURS;
                    }
                    t.memoire.remove("en_charge");
                    return Actions.Etat.OK;
                }
                default -> {
                    // Le retour à la source : ce qu'elle refuse reste sur lui et ira au dépôt (en_charge).
                    if (poser(t, p, h)) t.memoire.remove("en_charge");
                    return echec("retour");
                }
            }
            return Actions.Etat.EN_COURS;
        }

        /** Rien à prendre : trajet suivant ; tous ses trajets vides plusieurs fois de suite, il l'écrit et souffle. */
        Actions.Etat vide(Travailleur t) {
            int n = t.memoire.getInt("trajet_vides") + 1;
            long maintenant = Cerveau.temps(t);
            int prets = (int) lire(t).stream().filter(x -> x.pret(maintenant)).count();
            if (Trajets.tousVides(n, prets)) {
                n = 0;
                t.memoire.putLong("transport_repos", maintenant + PAUSE);
                Cerveau.ecrire(t, "source_vide", Cerveau.UN_JOUR, "coffre_source_vide", lieu(trajet.source()));
            }
            t.memoire.putInt("trajet_vides", n);
            return echec("source_vide");
        }

        /** Il charge ce qui répond au filtre, jamais plus que la place dans son sac. */
        void prendre(Travailleur t, List<IItemHandler> hs) {
            for (IItemHandler h : hs)
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack vu = h.getStackInSlot(i);
                    if (!filtre.objet(vu)) continue;
                    int n = Trajets.prise(vu.getCount(), place(t, vu));
                    if (n <= 0) continue;
                    ItemStack pris = h.extractItem(i, n, false);
                    if (pris.isEmpty()) continue;
                    ItemStack reste = t.inventaire.addItem(pris.copy());
                    if (!reste.isEmpty()) reste = ItemHandlerHelper.insertItemStacked(h, reste, false);
                    if (!reste.isEmpty()) t.ranger(reste); // jamais : la place a été comptée
                    ItemStack porte = pris.copyWithCount(pris.getCount() - reste.getCount());
                    if (!porte.isEmpty()) charge.add(porte);
                }
        }

        /** La place pour cet objet dans son sac. */
        static int place(Travailleur t, ItemStack s) {
            int vides = 0;
            List<Integer> memes = new ArrayList<>();
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack x = t.inventaire.getItem(i);
                if (x.isEmpty()) vides++;
                else if (ItemStack.isSameItemSameComponents(x, s)) memes.add(x.getCount());
            }
            return Trajets.place(vides, s.getMaxStackSize(), memes);
        }

        /** Il pose ce qu'il porte au bout {@code p} ; ce qui n'entre pas reste sur lui (et dans {@code charge}). Vrai si tout est posé. */
        boolean poser(Travailleur t, long p, List<IItemHandler> hs) {
            Bureau.Entite b = p == Trajets.DEPOT && t.level().getBlockEntity(t.bureau) instanceof Bureau.Entite e ? e : null;
            List<ItemStack> restes = new ArrayList<>();
            for (ItemStack c : charge) {
                ItemStack s = sortir(t, c);
                if (s.isEmpty()) continue;
                if (b != null) s = b.ranger(s);
                else for (IItemHandler h : hs) if (!s.isEmpty()) s = ItemHandlerHelper.insertItemStacked(h, s, false);
                if (!s.isEmpty()) {
                    restes.add(s.copy());
                    ItemStack x = t.inventaire.addItem(s);
                    if (!x.isEmpty()) t.ranger(x); // jamais : la place vient d'être libérée
                }
            }
            charge.clear();
            charge.addAll(restes);
            return restes.isEmpty();
        }

        /** Sort de son sac jusqu'à {@code c.getCount()} objets pareils à {@code c}. */
        static ItemStack sortir(Travailleur t, ItemStack c) {
            ItemStack r = ItemStack.EMPTY;
            int n = c.getCount();
            for (int i = 0; i < t.inventaire.getContainerSize() && n > 0; i++) {
                ItemStack x = t.inventaire.getItem(i);
                if (!ItemStack.isSameItemSameComponents(x, c)) continue;
                ItemStack part = x.split(Math.min(n, x.getCount()));
                n -= part.getCount();
                if (r.isEmpty()) r = part;
                else r.grow(part.getCount());
            }
            t.inventaire.setChanged();
            return r;
        }
    }

    // --- Sur ordre ------------------------------------------------------------------------------------------------

    /** « Porte ce coffre au dépôt », « prends dans ce coffre », « dépose dans celui-là », « oublie tes trajets ». */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) { return new Ordre(mode, c); }

    /** Il retient (ou oublie) un trajet, et dit ce qu'il a compris. */
    static final class Ordre extends Actions.Action {
        final String mode;
        final Consignes.Consigne c;

        Ordre(String mode, Consignes.Consigne c) { super("trajets"); this.mode = mode; this.c = c; }

        Actions.Etat tick(Travailleur t) {
            String[] x = c.texte.split("\\|", -1);
            String objet = x.length > 1 ? x[1] : "";
            long vu = point(t, c.lieu);
            switch (mode) {
                case "oublier_trajets" -> {
                    t.memoire.remove("trajets");
                    t.memoire.remove("trajet_depart");
                    Voix.dire(t, "Bien, chef : j'oublie tous mes trajets. Je reste au poste.", "calme");
                    return Actions.Etat.OK;
                }
                case "trajet_prendre" -> {
                    t.memoire.putLong("trajet_depart", vu);
                    t.memoire.putString("trajet_depart_o", objet);
                    Voix.dire(t, "Bien, chef : je prendrai là. Montre-moi maintenant où le porter, et dis-moi « dépose dans celui-là ».", "calme");
                    return Actions.Etat.OK;
                }
                case "trajet_deposer" -> {
                    if (!t.memoire.contains("trajet_depart")) {
                        Voix.dire(t, "D'où dois-je le prendre, chef ? Montre-moi d'abord le coffre, et dis-moi « prends dans ce coffre ».", "calme");
                        return echec("sans_depart");
                    }
                    long depart = t.memoire.getLong("trajet_depart");
                    if (objet.isEmpty()) objet = t.memoire.getString("trajet_depart_o");
                    t.memoire.remove("trajet_depart");
                    t.memoire.remove("trajet_depart_o");
                    return retenir(t, new Trajet(depart, vu, objet));
                }
                default -> {
                    boolean versDepot = x[0].isEmpty() || Trajets.Sens.valueOf(x[0]) == Trajets.Sens.VERS_DEPOT;
                    return retenir(t, versDepot ? new Trajet(vu, Trajets.DEPOT, objet) : new Trajet(Trajets.DEPOT, vu, objet));
                }
            }
        }

        Actions.Etat retenir(Travailleur t, Trajet tr) {
            if (tr.source() == tr.dest()) {
                Voix.dire(t, "C'est le même coffre, chef : je n'ai rien à porter de lui à lui.", "calme");
                return echec("meme_coffre");
            }
            List<Trajet> l = Trajets.ajouter(lire(t), tr);
            if (l == null) {
                Voix.dire(t, "J'ai déjà " + Trajets.MAX + " trajets, chef : je n'en retiens pas plus. Dis-moi d'abord d'oublier les miens.", "calme");
                return echec("trop_de_trajets");
            }
            ecrire(t, l);
            t.memoire.remove("transport_repos");
            t.memoire.putInt("trajet_vides", 0);
            String sens = tr.source() == Trajets.DEPOT ? "du dépôt à ce coffre" : tr.dest() == Trajets.DEPOT ? "de ce coffre au dépôt" : "d'un coffre à l'autre";
            Voix.dire(t, "Bien, chef : je porterai " + (tr.objet().isEmpty() ? "tout" : "ce que tu m'as dit") + " " + sens
                    + ", et je referai le chemin tant qu'il y aura à porter.", "calme");
            return Actions.Etat.OK;
        }
    }
}

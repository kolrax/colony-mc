package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Trajets.Trajet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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

    /** Ce point, comme on le dit dans une lettre : « du poste », « à 30 pas au nord du poste (120 64 -38) ». */
    static String lieu(Travailleur t, long p) {
        if (p == Trajets.DEPOT || t.bureau == null) return "du poste";
        BlockPos b = BlockPos.of(p);
        String xyz = " (" + b.getX() + " " + b.getY() + " " + b.getZ() + ")";
        int d = (int) Math.round(Math.sqrt(Fermier.distance(t.bureau, b)));
        return (d < 2 ? "juste à côté du poste" : "à " + d + " pas " + Messages.direction(t.bureau, b) + " du poste") + xyz;
    }

    /** Le bloc regardé est une machine (ou une pièce de machine qui mène à une), pas un coffre. */
    static boolean machineVue(Travailleur t, BlockPos vu) {
        long p = point(t, vu);
        return p != Trajets.DEPOT && Machines.machine(id(t.level(), BlockPos.of(p)));
    }

    /** L'identifiant retenu d'un bout de trajet qui est une machine (« machines », sans lire le monde : elle a pu être cassée), ou "". */
    static String machineDe(Travailleur t, long p) { return p == Trajets.DEPOT ? "" : t.memoire.getCompound("machines").getString(Long.toString(p)); }

    /** Il retient que ce bout est une machine (pour la nommer dans ses lettres et ses répliques, même cassée). */
    static void retenirMachine(Travailleur t, long p) {
        if (p == Trajets.DEPOT || !t.level().isLoaded(BlockPos.of(p))) return;
        String id = id(t.level(), BlockPos.of(p));
        if (!Machines.machine(id)) return;
        CompoundTag c = t.memoire.getCompound("machines");
        c.putString(Long.toString(p), id);
        t.memoire.put("machines", c);
    }

    /**
     * Une lettre sur un bout de trajet : celle du coffre ({@code coffre_plein}, « le coffre {0} »), ou, pour une machine,
     * {@code machine_pleine}… qui la nomme (« le moulin », {0}) et dit où ({1}).
     */
    static void lettre(Travailleur t, String cle, String type, long p) {
        String id = machineDe(t, p);
        if (id.isEmpty()) Cerveau.ecrire(t, cle, Cerveau.UN_JOUR, type, lieu(t, p));
        else Cerveau.ecrire(t, cle, Cerveau.UN_JOUR, LETTRES_MACHINE.get(type), Machines.nom(id), lieu(t, p));
    }

    static final java.util.Map<String, String> LETTRES_MACHINE = java.util.Map.of("coffre_plein", "machine_pleine", "coffre_introuvable", "machine_introuvable",
            "coffre_source_vide", "machine_source_vide", "coffre_inaccessible", "machine_inaccessible", "coffre_interdit", "machine_interdite");

    /** Une réplique sur une machine ({@code {machine}} : « le moulin »), si elle est permise ; sinon la réplique du coffre ({@code coffre}, peut être null). */
    static void parler(Travailleur t, long p, String cleMachine, String coffre) {
        String id = machineDe(t, p);
        if (id.isEmpty()) { if (coffre != null) Besoins.parler(t, coffre, null); return; }
        if (t.muet() || !Besoins.permis(t, cleMachine)) return;
        String s = replique(t, cleMachine, id, null);
        if (s != null) t.dire(s);
    }

    /** La réplique {@code cle} sur la machine {@code id}, {@code objet} dit s'il est connu ; null si la section manque. */
    static String replique(Travailleur t, String cle, String id, String objet) {
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        v.put("machine", Machines.nom(id));
        if (objet != null && !objet.isEmpty()) v.put("objet", objet);
        return Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble);
    }

    /**
     * Les poignées d'un bout de trajet (le dépôt : le bureau et ses coffres collés) ; null si le coffre n'est plus là. Un coffre
     * se vide par le dessous et se remplit par le dessus ({@code prendre}) : un four ne rend que ce qu'il a cuit, et reçoit son
     * minerai à la bonne place.
     */
    static List<IItemHandler> poignees(Travailleur t, long p, boolean prendre) {
        Level l = t.level();
        if (p == Trajets.DEPOT) {
            if (t.bureau == null || !(l.getBlockEntity(t.bureau) instanceof Bureau.Entite b)) return null;
            List<IItemHandler> r = new ArrayList<>();
            r.add(new InvWrapper(b.depot));
            r.addAll(b.voisins());
            return r;
        }
        IItemHandler h = l.getCapability(Capabilities.ItemHandler.BLOCK, BlockPos.of(p), prendre ? Direction.DOWN : Direction.UP);
        return h == null ? null : List.of(h);
    }

    /** Un bloc à inventaire (coffre, tonneau, four, dépôt de Create…) ; le bureau compte pour le dépôt. */
    static boolean coffre(Level l, BlockPos p) {
        return l.getBlockEntity(p) instanceof Bureau.Entite || l.getCapability(Capabilities.ItemHandler.BLOCK, p, null) != null
                || l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP) != null || l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN) != null;
    }

    /** L'identifiant du bloc (« create:millstone »). */
    static String id(Level l, BlockPos p) { return BuiltInRegistries.BLOCK.getKey(l.getBlockState(p).getBlock()).toString(); }

    /**
     * Le bloc où poser ou prendre quand le chef montre {@code vu} (lot 12) : lui-même s'il a un inventaire ; pour une pièce de
     * machine qui n'en a pas, son voisin : sous la presse ou le mélangeur, le présentoir, le bassin ou le tapis (1 ou 2 blocs
     * plus bas) ; l'entonnoir, le bloc où il est accroché ; une roue de broyage, l'entrée entre les deux roues. Null : rien.
     * Quelques blocs lus, une seule fois, à l'ordre : pas de recherche.
     */
    static BlockPos cible(Level l, BlockPos vu) {
        if (coffre(l, vu)) return vu;
        Machines.Machine m = Machines.de(id(l, vu));
        if (m == null) return null;
        List<BlockPos> essais = new ArrayList<>();
        switch (m.relais()) {
            case DESSOUS -> { essais.add(vu.below()); essais.add(vu.below(2)); }
            case ACCROCHE -> {
                BlockState s = l.getBlockState(vu);
                for (var pr : s.getProperties())
                    if (pr.getName().equals("facing") && s.getValue(pr) instanceof Direction d) essais.add(vu.relative(d.getOpposite()));
            }
            case CONTROLEUR -> { for (Direction d : Direction.Plane.HORIZONTAL) if (id(l, vu.relative(d)).equals(Machines.CONTROLEUR)) essais.add(vu.relative(d)); }
            default -> {}
        }
        for (BlockPos q : essais) if (coffre(l, q)) return q;
        return null;
    }

    /** Les blocs d'un coffre : ses deux moitiés pour un double coffre, la moitié de référence d'abord (la plus petite position). */
    static List<BlockPos> moities(Level l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        if (s.getBlock() instanceof ChestBlock && s.hasProperty(ChestBlock.TYPE) && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos o = p.relative(ChestBlock.getConnectedDirection(s));
            if (l.getBlockState(o).getBlock() instanceof ChestBlock) return o.asLong() < p.asLong() ? List.of(o, p) : List.of(p, o);
        }
        return List.of(p);
    }

    /** Le bloc regardé comme bout de trajet : le bureau de son poste, et les coffres collés à lui (une moitié suffit), sont le dépôt. */
    static long point(Travailleur t, BlockPos p) {
        if (p == null || t.level().getBlockEntity(p) instanceof Bureau.Entite) return Trajets.DEPOT;
        BlockPos c = cible(t.level(), p);
        if (c != null) p = c;
        if (Machines.machine(id(t.level(), p))) return p.asLong(); // une machine collée au bureau reste une machine
        List<BlockPos> m = moities(t.level(), p);
        for (BlockPos q : m) if (t.bureau != null && q.distManhattan(t.bureau) <= 1) return Trajets.DEPOT;
        return m.get(0).asLong();
    }

    /** Pourquoi il refuse ce coffre (null : il l'accepte). */
    static String refus(Travailleur t, String mode, BlockPos vu) {
        if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef.";
        if (mode.equals("oublier_trajets") || vu == null) return null;
        if (cible(t.level(), vu) == null) {
            if (!Machines.deCreate(id(t.level(), vu))) return "Ce n'est pas un coffre, ça, chef. Montre-moi un coffre, un tonneau, une caisse, ou une machine.";
            String r = Besoins.texte(t, "machine_pas_capable", null, null);
            return r != null ? r : "Ça, on ne peut rien mettre dedans, chef. Montre-moi plutôt le présentoir, le bassin ou le tapis dessous.";
        }
        long p = point(t, vu);
        if (p == Trajets.DEPOT) return mode.equals("trajet") ? "C'est notre dépôt, ça, chef. Montre-moi l'autre coffre." : null;
        if (!aNous(t, p)) return Fermier.distance(t.bureau, vu) > (double) Trajets.ZONE * Trajets.ZONE || Math.abs(vu.getY() - t.bureau.getY()) > Trajets.ZONE
                ? "C'est trop loin du poste, chef : je ne porte pas à plus de soixante-quatre pas de chez nous."
                : "Ce coffre n'est pas à nous, chef : je n'y touche pas.";
        return null;
    }

    /** Ce bout de trajet est à nous : le dépôt, ou un coffre à 64 blocs au plus du poste actuel, hors d'un village et pas chez un voisin. */
    static boolean aNous(Travailleur t, long p) {
        if (p == Trajets.DEPOT) return true;
        if (t.bureau == null) return false;
        BlockPos b = BlockPos.of(p);
        return Fermier.distance(t.bureau, b) <= (double) Trajets.ZONE * Trajets.ZONE && Math.abs(b.getY() - t.bureau.getY()) <= Trajets.ZONE && Fermier.aLui(t, b);
    }

    /**
     * Le sens, l'objet et le départ d'un ordre, avant de l'accepter : « remplis ce coffre » sans dire avec quoi ne vide pas
     * tout le dépôt ; un trajet d'un coffre vers lui-même n'en est pas un. Null : il l'accepte.
     */
    static String verifierOrdre(Travailleur t, String mode, Trajets.Sens sens, String objet, BlockPos vu) {
        long p = point(t, vu);
        String id = p == Trajets.DEPOT ? "" : id(t.level(), BlockPos.of(p));
        Machines.Machine m = Machines.de(id);
        if (m != null && m.entreeSeule() && (mode.equals("trajet_prendre") || mode.equals("trajet") && sens == Trajets.Sens.VERS_DEPOT))
            return "On ne vide pas " + m.nom() + ", chef : ce qu'il fait sort tout seul. Montre-moi l'endroit où ça tombe.";
        if (mode.equals("trajet") && sens == Trajets.Sens.DU_DEPOT && objet.isEmpty())
            return "Avec quoi, chef ? Dis-moi ce que je dois mettre " + (m != null ? "dans " + m.nom() : "du dépôt dans ce coffre") + ".";
        if (!mode.equals("trajet_deposer")) return null;
        if (!t.memoire.contains("trajet_depart")) return "D'où dois-je le prendre, chef ? Montre-moi d'abord le coffre, et dis-moi « prends dans ce coffre ».";
        long depart = t.memoire.getLong("trajet_depart");
        if (depart == p) return "C'est le même coffre, chef : je n'ai rien à porter de lui à lui.";
        if (depart == Trajets.DEPOT && objet.isEmpty() && t.memoire.getString("trajet_depart_o").isEmpty())
            return "Avec quoi, chef ? Dis-moi ce que je dois prendre au dépôt.";
        return null;
    }

    /**
     * Renvoyé : il oublie ses trajets et tout ce qui va avec. Ce qu'il portait va au dépôt de son ancien poste s'il est là
     * (sinon il le garde sur lui) : rien ne se perd.
     */
    static void oublier(Travailleur t) { oublier(t, true); }

    /** {@code renvoi} faux (« oublie tes trajets ») : ce qu'il porte reste sur lui et ira au dépôt à pied. */
    static void oublier(Travailleur t, boolean renvoi) {
        boolean charge = t.memoire.getBoolean("en_charge");
        if (renvoi && charge && t.bureau != null && t.level().isLoaded(t.bureau) && t.level().getBlockEntity(t.bureau) instanceof Bureau.Entite b)
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (!s.isEmpty()) t.inventaire.setItem(i, b.rangerVisible(s)); // pas la réserve : elle n'est pas un stock sans limite pour le chef
            }
        purger(t);
        for (String k : List.of("trajets", "trajet_depart", "trajet_depart_o", "trajet_depart_n", "en_charge", "transport_repos", "trajet_vides", "trajet_n", "trajets_revus", "machines", "poses"))
            t.memoire.remove(k);
        if (!renvoi && charge) t.memoire.putBoolean("en_charge", true);
    }

    /** Le nom de l'objet dit, tel que le chef l'a dit (« les bûches »), ou "" : le mot le plus long du vocabulaire des objets. */
    static String nomDit(String phrase) {
        String n = Ordres.normaliser(phrase), mot = "";
        for (String k : Consignes.MOTS_OBJETS.keySet()) if (n.contains(" " + k + " ") && k.length() > mot.length()) mot = k;
        if (mot.isEmpty()) return "";
        // Les mots de la phrase d'origine (accents compris) qui font ce mot.
        String[] w = phrase.split("[\\s'’,.!?;:]+");
        int len = mot.split(" ").length;
        for (int i = 0; i + len <= w.length; i++) {
            String bout = String.join(" ", java.util.Arrays.copyOfRange(w, i, i + len));
            if (Ordres.normaliser(bout).equals(" " + mot + " ")) return (i > 0 && Ordres.normaliser(w[i - 1]).matches(" (le|la|les|du|des|de) ") ? article(w[i - 1]) : "") + bout;
        }
        return mot;
    }

    static String article(String a) {
        String n = Ordres.normaliser(a).trim();
        return switch (n) { case "du", "de" -> "le "; case "des" -> "les "; default -> n + " "; };
    }

    /** Les délais de lettres propres à chaque coffre ({@code coffre_plein_<pos>}…) : effacés avec les trajets. */
    static void purger(Travailleur t) {
        CompoundTag dits = t.memoire.getCompound("dits");
        for (String k : List.copyOf(dits.getAllKeys()))
            if (k.startsWith("coffre_plein_") || k.startsWith("introuvable_") || k.startsWith("interdit_") || k.startsWith("inaccessible_")) dits.remove(k);
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
        if ("depot_plein".equals(t.resultat)) { Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein"); t.bloquer(Statut.SAC_PLEIN); }
        List<Trajet> l = lire(t);
        revoir(t, l, maintenant);
        int i = t.memoire.getLong("transport_repos") > maintenant ? -1 : Trajets.suivant(l, t.memoire.getInt("trajet_n"), maintenant);
        if (i < 0) {
            if (l.isEmpty()) {
                Cerveau.dire(t, "sans_trajet", 600, "Je n'ai rien à porter. Montre-moi un coffre, et dis-moi où en porter le contenu.");
                t.bloquer(Statut.SANS_TRAJET);
            }
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 6 * 6) Cerveau.rentrer(t);
            else Cerveau.attendre(t, 10);
            return;
        }
        t.memoire.putInt("trajet_n", i);
        t.debloquer(Statut.SANS_TRAJET);
        // Au départ de chaque tour : ses deux bouts sont-ils toujours à nous ?
        if (!interdit(t, l.get(i))) Cerveau.lancer(t, new Tour(l.get(i)));
    }

    /** Un bout du trajet n'est plus à nous (trop loin du poste, chez un voisin, dans un village) : trajet suspendu, lettre. */
    static boolean interdit(Travailleur t, Trajet x) {
        boolean source = !aNous(t, x.source());
        if (!source && aNous(t, x.dest())) return false;
        long p = source ? x.source() : x.dest();
        changer(t, x, x.suspendre(true));
        lettre(t, "interdit_" + p, "coffre_interdit", p);
        return true;
    }

    /** Toutes les minutes : un trajet dont un bout n'est plus à nous est suspendu ; un trajet suspendu dont les coffres sont là (et à nous) reprend. */
    static void revoir(Travailleur t, List<Trajet> l, long maintenant) {
        if (t.memoire.getLong("trajets_revus") > maintenant || l.isEmpty()) return;
        t.memoire.putLong("trajets_revus", maintenant + REVUE);
        // Les bouts chargés : une machine posée depuis (ou un ancien trajet) est retenue ; un bloc qui n'en est plus une est oublié.
        CompoundTag mach = t.memoire.getCompound("machines");
        for (Trajet x : l)
            for (long p : new long[]{x.source(), x.dest()}) {
                if (p == Trajets.DEPOT || !t.level().isLoaded(BlockPos.of(p))) continue;
                String id = id(t.level(), BlockPos.of(p));
                if (Machines.machine(id)) mach.putString(Long.toString(p), id);
                else if (coffre(t.level(), BlockPos.of(p))) mach.remove(Long.toString(p));
            }
        t.memoire.put("machines", mach);
        for (Trajet x : List.copyOf(l)) {
            if (!x.suspendu()) interdit(t, x);
            else if (present(t, x.source()) && present(t, x.dest()) && aNous(t, x.source()) && aNous(t, x.dest())) changer(t, x, x.suspendre(false));
        }
        l.clear();
        l.addAll(lire(t));
    }

    static boolean present(Travailleur t, long p) {
        return p == Trajets.DEPOT || t.level().isLoaded(BlockPos.of(p)) && poignees(t, p, true) != null;
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
                    // Il n'y arrive pas : il l'écrit (une fois par jour et par coffre), le trajet attend, et ce qu'il porte ira au dépôt.
                    lettre(t, "inaccessible_" + p, "coffre_inaccessible", p);
                    changer(t, trajet, trajet.attendre(Cerveau.temps(t) + PLEIN));
                    return echec("bloque");
                }
                return Actions.Etat.EN_COURS;
            }
            List<IItemHandler> h = poignees(t, p, etape == 0);
            if (h == null) {
                // Le coffre n'est plus là : le trajet est suspendu, il l'écrit ; ce qu'il porte retourne à la source, sinon au dépôt.
                changer(t, trajet, trajet.suspendre(true));
                lettre(t, "introuvable_" + p, "coffre_introuvable", p);
                parler(t, p, "machine_introuvable", "transport_coffre_introuvable");
                if (etape == 1 && !charge.isEmpty()) return retour(t);
                return echec("introuvable");
            }
            t.swing(InteractionHand.MAIN_HAND);
            // Le dépôt n'a pas de position propre (Trajets.DEPOT) : BlockPos.of(DEPOT) tombe aux confins du monde (crash du 18/10).
            if (p != Trajets.DEPOT || t.bureau != null) Sons.coffre(t, p == Trajets.DEPOT ? t.bureau : BlockPos.of(p));
            if (Machines.deCreate(machineDe(t, p)) && t.getRandom().nextInt(8) == 0) parler(t, p, "machine_peur", null); // devant une machine de Create, parfois, un mot sur elle
            switch (etape) {
                case 0 -> {
                    // Une machine : seulement ses cases de sortie (un bassin garde ses entrées) ; jamais une machine qui n'a que des entrées.
                    Machines.Machine m = Machines.de(machineDe(t, p));
                    if (m == null || !m.entreeSeule()) prendre(t, h, m == null ? 0 : m.sortie(), m != null && Trajets.alimente(lire(t), p), p);
                    if (charge.isEmpty()) {
                        parler(t, p, "machine_vide_rien", null);
                        return vide(t);
                    }
                    t.memoire.putInt("trajet_vides", 0);
                    t.memoire.putBoolean("en_charge", true);
                    Besoins.parler(t, "transport_charge", null);
                    etape = 1;
                    nouveauBut();
                }
                case 1 -> {
                    if (!poser(t, p, h)) {
                        // Plus de place : une lettre par jour et par coffre ; le trajet attend ; le reste retourne à la source.
                        lettre(t, "coffre_plein_" + p, "coffre_plein", p);
                        parler(t, p, "machine_pleine", "transport_coffre_plein");
                        changer(t, trajet, trajet.attendre(Cerveau.temps(t) + PLEIN));
                        return retour(t);
                    }
                    t.memoire.remove("en_charge");
                    parler(t, p, "machine_charge", null);
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

        /**
         * Ce qu'il n'a pas pu poser retourne à la source ; jamais à une machine (ce serait dans ses entrées) : il le garde
         * ({@code en_charge}) et le porte au dépôt.
         */
        Actions.Etat retour(Travailleur t) {
            if (!machineDe(t, trajet.source()).isEmpty()) return echec("retour_depot");
            etape = 2;
            nouveauBut();
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
                lettre(t, "source_vide", "coffre_source_vide", trajet.source());
            }
            t.memoire.putInt("trajet_vides", n);
            return echec("source_vide");
        }

        /**
         * Il charge ce qui répond au filtre, jamais plus que la place dans son sac, à partir de la case {@code debut} (la sortie
         * d'une machine). Il ne prend que ce que le bloc laisse sortir (la capacité d'une machine de Create ne rend que ses produits).
         */
        void prendre(Travailleur t, List<IItemHandler> hs, int debut, boolean alimentee, long p) {
            CompoundTag pose = t.memoire.getCompound("poses").getCompound(Long.toString(p));
            java.util.Set<String> poses = new java.util.HashSet<>();
            for (Tag x : pose.getList("o", Tag.TAG_STRING)) poses.add(x.getAsString());
            long maintenant = Cerveau.temps(t);
            for (IItemHandler h : hs)
                for (int i = debut; i < h.getSlots(); i++) {
                    ItemStack vu = h.getStackInSlot(i);
                    if (!filtre.objet(vu) || vu.isEmpty()) continue;
                    // Une presse, un tapis : ce qu'il y a posé lui-même (pas encore travaillé) n'est pas un produit.
                    if (!Trajets.prenable(BuiltInRegistries.ITEM.getKey(vu.getItem()).toString(), alimentee, poses, pose.getLong("t"), maintenant)) continue;
                    int n = Trajets.prise(vu.getCount(), place(t, vu));
                    if (n <= 0) continue;
                    ItemStack pris = h.extractItem(i, n, false);
                    if (pris.isEmpty()) continue;
                    ItemStack reste = t.inventaire.addItem(pris.copy());
                    ItemStack porte = pris.copyWithCount(pris.getCount() - reste.getCount());
                    if (!porte.isEmpty()) charge.add(porte);
                    // Jamais : la place a été comptée. Ce qui n'entre pas retourne au coffre, sinon il le porte quand même.
                    if (!reste.isEmpty()) reste = ItemHandlerHelper.insertItemStacked(h, reste, false);
                    if (!reste.isEmpty()) { charge.add(reste.copy()); t.ranger(reste); }
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
                int avant = s.getCount();
                String mid = machineDe(t, p);
                if (b != null) s = b.rangerVisible(s); // pas la réserve (cahier 14.4 : « mais pas le joueur »)
                else if (etape == 1 && Machines.four(mid) && s.getBurnTime(net.minecraft.world.item.crafting.RecipeType.SMELTING) > 0
                        && t.level().getCapability(Capabilities.ItemHandler.BLOCK, BlockPos.of(p), Direction.NORTH) instanceof IItemHandler cote)
                    s = ItemHandlerHelper.insertItemStacked(cote, s, false); // le combustible par le côté
                else for (IItemHandler h : hs) if (!s.isEmpty()) s = ItemHandlerHelper.insertItemStacked(h, s, false);
                if (etape == 1 && !mid.isEmpty() && s.getCount() < avant) posee(t, p, c);
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

        /** Il note ce qu'il a posé dans la machine {@code p}, et quand (16 sortes au plus) : il ne le reprendra pas comme un produit. */
        static void posee(Travailleur t, long p, ItemStack c) {
            CompoundTag tout = t.memoire.getCompound("poses"), une = tout.getCompound(Long.toString(p));
            ListTag o = une.getList("o", Tag.TAG_STRING);
            String id = BuiltInRegistries.ITEM.getKey(c.getItem()).toString();
            boolean deja = false;
            for (Tag x : o) deja |= x.getAsString().equals(id);
            if (!deja) {
                if (o.size() >= 16) o.remove(0);
                o.add(net.minecraft.nbt.StringTag.valueOf(id));
            }
            une.put("o", o);
            une.putLong("t", Cerveau.temps(t));
            tout.put(Long.toString(p), une);
            t.memoire.put("poses", tout);
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
            // texte : le sens | le nom de l'objet dit | son filtre (vides : tout).
            String[] x = c.texte.split("\\|", 3);
            String nom = x.length > 1 ? x[1] : "", objet = x.length > 2 ? x[2] : "";
            long vu = point(t, c.lieu);
            switch (mode) {
                case "oublier_trajets" -> {
                    oublier(t, false);
                    Paroles.dire(t, "J'oublie tous mes trajets. Je reste au poste.", "calme");
                    return Actions.Etat.OK;
                }
                case "trajet_prendre" -> {
                    t.memoire.putLong("trajet_depart", vu);
                    t.memoire.putString("trajet_depart_o", objet);
                    t.memoire.putString("trajet_depart_n", nom);
                    String ici = vu == Trajets.DEPOT ? "au dépôt" : "dans " + Machines.nom(id(t.level(), BlockPos.of(vu))).replaceFirst("^le ", "ce ").replaceFirst("^la ", "cette ").replaceFirst("^l'", "cet ");
                    Paroles.dire(t, "Je prendrai " + (nom.isEmpty() ? "" : nom + " ") + ici
                            + ". Montre-moi maintenant où le porter, et dis-moi « dépose dans celui-là ».", "calme");
                    return Actions.Etat.OK;
                }
                case "trajet_deposer" -> {
                    if (!t.memoire.contains("trajet_depart")) return echec("sans_depart"); // refusé avant (Consignes)
                    long depart = t.memoire.getLong("trajet_depart");
                    if (objet.isEmpty()) { objet = t.memoire.getString("trajet_depart_o"); nom = t.memoire.getString("trajet_depart_n"); }
                    for (String k : List.of("trajet_depart", "trajet_depart_o", "trajet_depart_n")) t.memoire.remove(k);
                    return retenir(t, new Trajet(depart, vu, objet), nom);
                }
                default -> {
                    boolean versDepot = x[0].isEmpty() || Trajets.Sens.valueOf(x[0]) == Trajets.Sens.VERS_DEPOT;
                    return retenir(t, versDepot ? new Trajet(vu, Trajets.DEPOT, objet) : new Trajet(Trajets.DEPOT, vu, objet), nom);
                }
            }
        }

        Actions.Etat retenir(Travailleur t, Trajet tr, String nom) {
            if (tr.source() == tr.dest()) {
                Paroles.dire(t, "C'est le même coffre, chef : je n'ai rien à porter de lui à lui.", "calme");
                return echec("meme_coffre");
            }
            List<Trajet> l = Trajets.ajouter(lire(t), tr);
            if (l == null) {
                Paroles.dire(t, "J'ai déjà " + Trajets.MAX + " trajets, chef : je n'en retiens pas plus. Dis-moi d'abord d'oublier les miens.", "calme");
                return echec("trop_de_trajets");
            }
            ecrire(t, l);
            t.memoire.remove("transport_repos");
            t.memoire.putInt("trajet_vides", 0);
            retenirMachine(t, tr.source());
            retenirMachine(t, tr.dest());
            // Une machine : il la nomme (« Je mets le blé dans le moulin »).
            String md = machineDe(t, tr.dest()), ms = machineDe(t, tr.source());
            String dit = !md.isEmpty() ? replique(t, "machine_remplir_ok", md, nom) : !ms.isEmpty() ? replique(t, "machine_vider_ok", ms, null) : null;
            if (dit != null) {
                Paroles.dire(t, dit, "calme");
                return Actions.Etat.OK;
            }
            String sens = tr.source() == Trajets.DEPOT ? "du dépôt à ce coffre" : tr.dest() == Trajets.DEPOT ? "de ce coffre au dépôt" : "d'un coffre à l'autre";
            Paroles.dire(t, "Je porterai " + (tr.objet().isEmpty() ? "tout" : nom.isEmpty() ? "ce que tu m'as dit" : nom) + " " + sens
                    + ", et je referai le chemin tant qu'il y aura à porter.", "calme");
            return Actions.Etat.OK;
        }
    }
}

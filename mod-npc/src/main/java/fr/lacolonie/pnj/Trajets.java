package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Les trajets du transporteur (lot 6, pur Java) : d'un coffre à l'autre, ou entre un coffre et le dépôt du poste. Un trajet
 * retient la source, la destination (positions de blocs codées en {@code long}, {@link #DEPOT} pour le dépôt du poste) et
 * l'objet à porter (un filtre, vide : tout). Il les fait l'un après l'autre, en boucle. Test {@code TransporteurTest}.
 */
final class Trajets {
    static final Set<String> TRAVAUX = Set.of("trajet", "trajet_prendre", "trajet_deposer", "oublier_trajets");
    /** Au plus tant de trajets retenus ; jamais un coffre à plus de tant de blocs du poste. */
    static final int MAX = 6, ZONE = 64;
    /** Le dépôt du poste (le bureau et ses coffres collés), comme source ou destination. */
    static final long DEPOT = Long.MIN_VALUE;
    /** Tous ses trajets trouvés vides tant de fois de suite chacun : il le dit par lettre et souffle. */
    static final int VIDES = 2;

    private Trajets() {}

    /**
     * Un trajet. {@code suspendu} : un de ses coffres a disparu (il attend qu'on le lui remontre) ; {@code attente} : pas
     * avant ce moment (en secondes de jeu), après une destination pleine.
     */
    record Trajet(long source, long dest, String objet, boolean suspendu, long attente) {
        Trajet(long source, long dest, String objet) { this(source, dest, objet == null ? "" : objet, false, 0); }

        boolean meme(Trajet o) { return source == o.source && dest == o.dest && objet.equals(o.objet); }

        Trajet suspendre(boolean oui) { return new Trajet(source, dest, objet, oui, attente); }

        Trajet attendre(long jusqua) { return new Trajet(source, dest, objet, suspendu, jusqua); }

        boolean pret(long maintenant) { return !suspendu && attente <= maintenant; }
    }

    /** Le sens d'un ordre « porte… » : du coffre regardé vers le dépôt, ou du dépôt vers le coffre regardé. */
    enum Sens { VERS_DEPOT, DU_DEPOT }

    /**
     * « Porte le fer de ce coffre au dépôt » : vers le dépôt ; « porte les bûches du dépôt à ce coffre », « remplis ce
     * coffre » : du dépôt. Sans dépôt nommé, du coffre vers le dépôt. Les machines (lot 12) : « mets le blé dans le moulin »,
     * « verse le minerai dans ce broyeur » : du dépôt ; « vide le bassin », « ramène ce qui sort de la machine » : vers le
     * dépôt. « Le dépôt de Create », « ce dépôt-là » sont la machine regardée, pas notre dépôt ({@link Machines#sansDepotDeCreate}).
     */
    static Sens sens(String phrase) { return sens(phrase, false); }

    /**
     * Pareil, le bout regardé étant une machine ({@code machine}) : sans verbe qui la vide (« vide », « ramène », « sors »,
     * « prends »…), on la remplit (« porte le blé dans le moulin », « le blé dans le moulin »). « Vide le moulin et mets la
     * farine dans le coffre » : « vide » l'emporte.
     */
    static Sens sens(String phrase, boolean machine) {
        String n = Machines.sansDepotDeCreate(Ordres.normaliser(phrase));
        if (Ordres.dit(n, "du depot", "depuis le depot", "de notre depot", "du poste", "depuis le poste", "hors du depot", "pris au depot",
                "prends au depot", "prise au depot")) return Sens.DU_DEPOT;
        if (Ordres.dit(n, "au depot", "vers le depot", "jusqu au depot", "au poste", "dans le depot")) return Sens.VERS_DEPOT;
        if (Ordres.dit(n, "vide", "vider", "videz", "ramene", "ramener", "ramenez", "rapporte", "rapporter", "sors", "sortir", "sortez", "prends", "prendre",
                "prenez", "recupere", "recuperer", "recuperez", "retire", "retirer", "ce qui sort", "ce qui en sort", "decharge", "decharger")) return Sens.VERS_DEPOT;
        if (Ordres.dit(n, "remplis", "remplir", "remplissez", "approvisionne", "approvisionner", "ravitaille", "garnis", "alimente",
                "mets", "met", "mettez", "mettre", "verse", "versez", "verser", "nourris", "nourrir", "enfourne", "enfourner")) return Sens.DU_DEPOT;
        return machine ? Sens.DU_DEPOT : Sens.VERS_DEPOT;
    }

    /**
     * Ajoute un trajet. Le même (source, destination, objet) : rien de neuf. Déjà {@link #MAX} trajets : null (il refuse).
     * Un trajet remis (un coffre remontré) n'est plus suspendu.
     */
    static List<Trajet> ajouter(List<Trajet> l, Trajet t) {
        List<Trajet> r = new ArrayList<>(l);
        for (int i = 0; i < r.size(); i++) if (r.get(i).meme(t)) { r.set(i, t); return r; }
        if (r.size() >= MAX) return null;
        r.add(t);
        return r;
    }

    /** Le prochain trajet à faire après le n° {@code apres} (en boucle), ni suspendu ni en attente ; -1 s'il n'y en a aucun. */
    static int suivant(List<Trajet> l, int apres, long maintenant) {
        int n = l.size();
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(apres + k, n);
            if (l.get(i).pret(maintenant)) return i;
        }
        return -1;
    }

    /** Tant de secondes après avoir posé dans une machine, il n'y reprend rien (elle n'a pas encore travaillé). */
    static final int ATTENTE_POSE = 20;

    /** Un autre de ses trajets pose dans ce bout {@code p} (une machine qu'il remplit et qu'il vide). */
    static boolean alimente(List<Trajet> l, long p) {
        for (Trajet x : l) if (x.dest() == p && p != DEPOT) return true;
        return false;
    }

    /**
     * Ce qu'il peut prendre dans une machine qu'il remplit aussi (une presse, un tapis, qui ne séparent pas l'entrée de la
     * sortie) : jamais ce qu'il y a posé lui-même ({@code poses} : les objets posés), et rien juste après une pose.
     */
    static boolean prenable(String objet, boolean alimentee, Set<String> poses, long posee, long maintenant) {
        return !alimentee || maintenant >= posee + ATTENTE_POSE && !poses.contains(objet);
    }

    /** Combien en prendre : ce qu'il y a, sans dépasser la place dans son sac. */
    static int prise(int dispo, int place) { return Math.max(0, Math.min(dispo, place)); }

    /** La place pour un objet dans un sac : les cases vides (une pile pleine chacune), plus le reste des piles du même objet. */
    static int place(int casesVides, int pileMax, List<Integer> memes) {
        int n = casesVides * pileMax;
        for (int c : memes) n += Math.max(0, pileMax - c);
        return n;
    }

    /** Trouvés vides {@code deSuite} fois de suite (tous trajets confondus) : chacun de ses {@code prets} trajets l'a été au moins {@link #VIDES} fois. */
    static boolean tousVides(int deSuite, int prets) { return prets > 0 && deSuite >= VIDES * prets; }
}

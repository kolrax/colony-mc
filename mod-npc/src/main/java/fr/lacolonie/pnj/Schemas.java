package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Les schémas du maçon (lot 10, cahier 4.4), pur Java : ce qui se calcule sans le jeu. Un schéma est un fichier {@code .nbt} au
 * format des blocs de structure (celui des schematics de Create) ; le jeu le lit ({@link Macon}) et nous donne sa palette
 * (« minecraft:oak_door[half=upper,facing=north] ») et ses blocs (position relative, numéro dans la palette).
 * <p>
 * Ici : la sorte de chaque bloc ({@link Genre}), l'ordre de pose (couche par couche, de bas en haut, le dégagement et le solide
 * d'abord, le fragile ensuite), la rotation et le miroir (les mêmes que le jeu, pivot à zéro, comme la table de Create),
 * le placement centré sur l'endroit montré, et le nom du schéma dit dans une phrase. Test {@code SchemasTest}.
 */
final class Schemas {
    /** Les ordres du maçon (répertoire, {@code travail <mode>}). */
    static final Set<String> TRAVAUX = Set.of("construire", "arreter_chantier", "reprendre_chantier", "abandonner_chantier");
    /** Au plus tant de blocs (hors air) dans un schéma : au-delà, il refuse (il bâtit seul, sans échafaudage). */
    static final int BLOCS_MAX = 20000, COTE_MAX = 96;

    private Schemas() {}

    /**
     * La sorte d'un bloc du schéma. {@code VIDE} : de l'air, à dégager s'il y a du naturel ; {@code SOLIDE} : posé d'abord ;
     * {@code FRAGILE} : posé une fois tout le solide en place (torches, portes, vitres, échelles…) ; {@code COMPAGNON} : la
     * moitié haute d'une porte ou la tête d'un lit, posée avec l'autre moitié ; {@code IGNORE} : rien à faire (eau, lave,
     * bloc de structure).
     */
    enum Genre { VIDE, SOLIDE, FRAGILE, COMPAGNON, IGNORE }

    /** Les mots d'un identifiant de bloc fragile : ce qui tient à un autre bloc, ou se casse si on le bouscule. */
    static final List<String> FRAGILES = List.of("torch", "door", "pane", "glass", "ladder", "sign", "button", "lever", "carpet", "rail",
            "flower", "sapling", "lantern", "banner", "bed", "pressure_plate", "redstone_wire", "repeater", "comparator", "tripwire",
            "vine", "candle", "tulip", "poppy", "dandelion", "orchid", "allium", "bluet", "daisy", "cornflower", "lily", "fern",
            "short_grass", "tall_grass", "dead_bush", "mushroom", "potted", "flower_pot", "head", "skull", "chain", "bell", "scaffolding",
            "cobweb", "snow", "item_frame", "campfire", "anvil", "lectern", "brewing_stand", "cake", "sea_pickle", "trapdoor");
    static final Set<String> IGNORES = Set.of("minecraft:water", "minecraft:lava", "minecraft:structure_void", "minecraft:structure_block",
            "minecraft:jigsaw", "minecraft:barrier", "minecraft:light", "minecraft:fire", "minecraft:soul_fire", "minecraft:nether_portal",
            "minecraft:end_portal", "minecraft:end_gateway", "minecraft:moving_piston", "minecraft:piston_head", "minecraft:bubble_column");

    /** Le nom du bloc d'un état écrit « id[k=v,…] ». */
    static String id(String etat) { int i = etat.indexOf('['); return i < 0 ? etat : etat.substring(0, i); }

    /** La valeur d'une propriété d'un état écrit « id[k=v,…] », ou "". */
    static String propriete(String etat, String cle) {
        int i = etat.indexOf('[');
        if (i < 0) return "";
        for (String kv : etat.substring(i + 1, etat.length() - 1).split(",")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).trim().equals(cle)) return kv.substring(e + 1).trim();
        }
        return "";
    }

    static Genre genre(String etat) {
        String id = id(etat);
        if (id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")) return Genre.VIDE;
        if (IGNORES.contains(id)) return Genre.IGNORE;
        if (propriete(etat, "half").equals("upper") || propriete(etat, "part").equals("head")) return Genre.COMPAGNON;
        String nom = id.substring(id.indexOf(':') + 1);
        for (String f : FRAGILES) if (nom.contains(f)) return Genre.FRAGILE;
        return Genre.SOLIDE;
    }

    /** Un bloc du schéma : sa place relative et son numéro dans la palette. */
    record Bloc(int x, int y, int z, int etat) {}

    /**
     * L'ordre de pose : d'abord tout le solide, couche par couche de bas en haut (dans chaque couche, le dégagement de l'air
     * puis les blocs, en serpentant), puis le fragile, couche par couche aussi. Les compagnons et ce qui est ignoré n'y sont pas.
     */
    static List<Bloc> ordre(List<Bloc> blocs, List<String> palette) {
        List<Bloc> r = new ArrayList<>();
        for (Bloc b : blocs) {
            Genre g = genre(palette.get(b.etat()));
            if (g != Genre.COMPAGNON && g != Genre.IGNORE) r.add(b);
        }
        r.sort(Comparator.<Bloc>comparingInt(b -> genre(palette.get(b.etat())) == Genre.FRAGILE ? 1 : 0)
                .thenComparingInt(Bloc::y)
                .thenComparingInt(b -> genre(palette.get(b.etat())) == Genre.VIDE ? 0 : 1)
                .thenComparingInt(Bloc::x)
                .thenComparingInt(b -> (b.x() & 1) == 0 ? b.z() : -b.z()));
        return r;
    }

    /**
     * La place d'un bloc après miroir puis rotation, pivot à zéro, comme le jeu ({@code StructureTemplate.transform}).
     * {@code miroir} : 0 aucun, 1 gauche-droite (z inversé), 2 avant-arrière (x inversé) ; {@code rotation} : 0 aucune, 1 un
     * quart de tour horaire, 2 un demi-tour, 3 un quart de tour antihoraire (l'ordre des énumérations du jeu).
     */
    static int[] tourner(int x, int z, int miroir, int rotation) {
        if (miroir == 1) z = -z;
        else if (miroir == 2) x = -x;
        return switch (rotation) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    /** La rotation pour un chef qui regarde dans cette direction (« north »…) : le schéma tel qu'enregistré quand il regarde au nord. */
    static int rotation(String direction) {
        return switch (direction) {
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;
        };
    }

    /**
     * Le décalage à ajouter aux places tournées pour que l'emprise du schéma ({@code sx} sur {@code sz}) soit centrée sur
     * (ax, az) ; en y, le bas du schéma est en {@code ay}. Rend {x, y, z}.
     */
    static int[] centre(int ax, int ay, int az, int sx, int sz, int miroir, int rotation) {
        int[] a = tourner(0, 0, miroir, rotation), b = tourner(sx - 1, sz - 1, miroir, rotation);
        int minX = Math.min(a[0], b[0]), maxX = Math.max(a[0], b[0]), minZ = Math.min(a[1], b[1]), maxZ = Math.max(a[1], b[1]);
        return new int[]{ax - Math.floorDiv(minX + maxX, 2), ay, az - Math.floorDiv(minZ + maxZ, 2)};
    }

    /** Le nom qu'on dit d'un fichier : « tour_de_guet.nbt » → « tour de guet ». */
    static String nom(String fichier) {
        String n = fichier.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".nbt")) n = n.substring(0, n.length() - 4);
        return n.replace('_', ' ').replace('-', ' ').trim();
    }

    /**
     * Le schéma dit dans la phrase (« construis la tour de guet ici ») : le nom le plus long dont tous les mots y sont, dans
     * l'ordre ; à défaut, un nom dont un mot (de plus de 3 lettres) y est. Null : aucun.
     */
    static String choisir(String phrase, Collection<String> noms) {
        String n = Ordres.normaliser(phrase), meilleur = null;
        for (String x : noms) {
            String k = Ordres.normaliser(x);
            if (k.isBlank()) continue;
            if ((n.contains(k) || n.contains(k.replaceAll("(\\w)s ", "$1 "))) && (meilleur == null || k.length() > Ordres.normaliser(meilleur).length())) meilleur = x;
        }
        if (meilleur != null) return meilleur;
        for (String x : noms)
            for (String w : Ordres.normaliser(x).trim().split(" "))
                if (w.length() > 3 && (n.contains(" " + w + " ") || n.contains(" " + w + "s "))) return x;
        return null;
    }

    /** « 3 sur 4 » : où il en est, en pour cent (arrondi en dessous ; 100 seulement fini). */
    static int pourcent(int fait, int total) { return total <= 0 ? 100 : Math.min(100, fait * 100 / total); }

    /** « cabane, moulin et tour de guet ». */
    static String liste(List<String> noms) {
        if (noms.isEmpty()) return "";
        if (noms.size() == 1) return noms.get(0);
        return String.join(", ", noms.subList(0, noms.size() - 1)) + " et " + noms.get(noms.size() - 1);
    }
}

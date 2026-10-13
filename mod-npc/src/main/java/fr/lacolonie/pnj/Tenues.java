package fr.lacolonie.pnj;

import java.util.List;
import java.util.UUID;

/**
 * La tenue d'un ouvrier sans pseudo de skin : le skin de son métier (« chomeur » tant qu'il n'est pas embauché),
 * dans l'une des {@link #VARIANTES} variantes, tirée de son UUID pour qu'il garde la même tête toute sa vie.
 * Pur Java : le client en fait une texture ({@code Client.skin}).
 */
public final class Tenues {
    /** Les métiers qui ont leurs textures, dans {@code textures/entity/ouvrier/<metier>_<n>.png}. */
    static final List<String> METIERS = List.of("chomeur", "mineur", "bucheron", "guerrier", "fermier", "eleveur",
            "pecheur", "chasseur", "cuisinier", "transporteur", "guerisseur", "macon");
    static final int VARIANTES = 3;
    static final String CHOMEUR = "chomeur";

    private Tenues() {}

    /** La tenue à porter : {@code "chomeur"} s'il n'est pas embauché, sinon son métier. */
    static String tenue(String metier, boolean embauche) { return embauche && metier != null ? metier : CHOMEUR; }

    /** Sa variante, de 1 à {@link #VARIANTES}, toujours la même pour un même UUID. */
    static int variante(UUID id) {
        long x = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        x ^= x >>> 33; // mélange : les UUID proches donnent des variantes différentes
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        return (int) Math.floorMod(x, (long) VARIANTES) + 1;
    }

    /** Chemin de la texture (sans l'espace de noms) ; un métier sans texture porte celle du chômeur. */
    static String chemin(String tenue, UUID id) {
        String m = tenue != null && METIERS.contains(tenue) ? tenue : CHOMEUR;
        return "textures/entity/ouvrier/" + m + "_" + variante(id) + ".png";
    }
}

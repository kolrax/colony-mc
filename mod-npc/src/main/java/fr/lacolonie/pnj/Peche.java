package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que sait le pêcheur, sans le monde (pur Java, testé seul) : où chercher l'eau (une grille de colonnes autour du poste,
 * parcourue par petits morceaux), ce qu'est un bon coin de pêche (un carré d'eau de 3 x 3 au moins, une rive sèche à côté)
 * et combien de temps il faut attendre que ça morde. {@link Pecheur} y apporte le monde.
 */
final class Peche {
    /** Seul, il ne pêche qu'à tant de blocs au plus de son poste (comme le fermier). */
    static final int PORTEE = 32;
    /** L'écart des colonnes regardées : tout carré d'eau de 3 x 3 en contient une. */
    static final int PAS = 3;
    /** Les colonnes regardées autour du poste, de la plus proche à la plus lointaine. */
    static final List<int[]> GRILLE = grille(PORTEE, PAS);
    /** Une prise : entre 15 et 40 secondes d'attente (en ticks). */
    static final int ATTENTE_MIN = 15 * 20, ATTENTE_MAX = 40 * 20;
    /** Au plus vite, même avec une canne enchantée (Appât) : 5 secondes. */
    static final int ATTENTE_PLANCHER = 5 * 20;
    /** Le côté du relevé autour d'une colonne d'eau trouvée : 7 x 7 (le centre du carré à ±1, sa rive à ±2 du centre). */
    static final int RELEVE = 7;

    /** Ce que rend l'eau, dit comme on le dit (pour les répliques de vie.txt, {@code {objet}}). */
    static final Map<String, String> NOMS = Map.ofEntries(
            Map.entry("minecraft:cod", "une morue"), Map.entry("minecraft:salmon", "un saumon"), Map.entry("minecraft:tropical_fish", "un poisson des mers chaudes"),
            Map.entry("minecraft:pufferfish", "un poisson-globe"), Map.entry("minecraft:leather_boots", "une vieille botte"), Map.entry("minecraft:lily_pad", "un nénuphar"),
            Map.entry("minecraft:bowl", "un bol"), Map.entry("minecraft:leather", "un bout de cuir"), Map.entry("minecraft:rotten_flesh", "de la chair pourrie"),
            Map.entry("minecraft:stick", "un bâton"), Map.entry("minecraft:string", "un bout de ficelle"), Map.entry("minecraft:potion", "une fiole"),
            Map.entry("minecraft:bone", "un os"), Map.entry("minecraft:ink_sac", "une poche d'encre"), Map.entry("minecraft:tripwire_hook", "un crochet"),
            Map.entry("minecraft:bamboo", "une tige de bambou"), Map.entry("minecraft:enchanted_book", "un vieux grimoire"), Map.entry("minecraft:bow", "un arc"),
            Map.entry("minecraft:fishing_rod", "une canne à pêche"), Map.entry("minecraft:name_tag", "une étiquette"),
            Map.entry("minecraft:nautilus_shell", "une coquille de nautile"), Map.entry("minecraft:saddle", "une selle"));
    /** Les poissons, et les trésors (rares, souvent enchantés) ; le reste : des déchets. */
    static final Set<String> POISSONS = Set.of("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish"),
            TRESORS = Set.of("minecraft:enchanted_book", "minecraft:name_tag", "minecraft:nautilus_shell", "minecraft:saddle");

    private Peche() {}

    /** La réplique d'une prise : « peche_prise » (un poisson), « peche_tresor » (un trésor, ou tout objet enchanté), sinon « peche_dechet ». */
    static String genre(String id, boolean enchante) {
        return POISSONS.contains(id) ? "peche_prise" : enchante || TRESORS.contains(id) ? "peche_tresor" : "peche_dechet";
    }

    /** Les décalages (dx, dz) multiples de {@code pas} dans le disque de ce rayon, du plus proche au plus lointain. */
    static List<int[]> grille(int rayon, int pas) {
        List<int[]> r = new ArrayList<>();
        int n = rayon / pas;
        for (int i = -n; i <= n; i++)
            for (int j = -n; j <= n; j++) {
                int dx = i * pas, dz = j * pas;
                if (dx * dx + dz * dz <= rayon * rayon) r.add(new int[]{dx, dz});
            }
        r.sort(Comparator.comparingInt(k -> k[0] * k[0] + k[1] * k[1]));
        return r;
    }

    /**
     * L'attente avant une prise, en ticks : 15 à 40 s selon le hasard {@code r} (0 à 1) ; sous la pluie, un quart de moins
     * (le poisson mord mieux) ; moins encore avec l'enchantement Appât ({@code appat}, en ticks), jamais moins de 5 s.
     */
    static int attente(double r, boolean pluie, int appat) {
        int a = ATTENTE_MIN + (int) Math.round(Math.clamp(r, 0, 1) * (ATTENTE_MAX - ATTENTE_MIN));
        if (pluie) a = a * 3 / 4;
        return Math.max(ATTENTE_PLANCHER, a - Math.max(0, appat));
    }

    /**
     * Les centres possibles d'un carré d'eau de 3 x 3, dans le relevé {@code eau} (7 x 7, indices [dx + 3][dz + 3] autour de
     * la colonne d'eau trouvée ; vrai : de l'eau de source à la surface, au même niveau). Le centre est à ±1 de la colonne,
     * ses 9 cases sont de l'eau, et au moins une case sèche le borde à 2 cases (une rive possible). Ceux qui ont le plus
     * de rive d'abord, puis le plus près de la colonne. Rendus en décalages (dx, dz) depuis la colonne.
     */
    static List<int[]> centres(boolean[][] eau) {
        List<int[]> r = new ArrayList<>();
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                boolean plein = true;
                for (int i = -1; i <= 1 && plein; i++) for (int j = -1; j <= 1 && plein; j++) plein = eau[cx + i + 3][cz + j + 3];
                if (plein && !rives(eau, cx, cz).isEmpty()) r.add(new int[]{cx, cz});
            }
        r.sort(Comparator.<int[]>comparingInt(c -> -rives(eau, c[0], c[1]).size()).thenComparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        return r;
    }

    /** Les cases sèches à 2 cases du centre (cx, cz) (l'anneau autour du carré d'eau), en décalages depuis la colonne. */
    static List<int[]> rives(boolean[][] eau, int cx, int cz) {
        List<int[]> r = new ArrayList<>();
        for (int i = -2; i <= 2; i++)
            for (int j = -2; j <= 2; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != 2) continue;
                int x = cx + i, z = cz + j;
                if (Math.abs(x) <= 3 && Math.abs(z) <= 3 && !eau[x + 3][z + 3]) r.add(new int[]{x, z});
            }
        return r;
    }
}

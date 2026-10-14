package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que sait le chasseur, sans le monde (pur Java, testé seul) : le gibier qu'il a le droit de tirer, l'espèce dite par le
 * chef. {@link Chasseur} y apporte le monde.
 * <p>
 * Le gibier : les bêtes sauvages (vache, mouton, cochon, poule, lapin) autour du poste. Jamais une bête d'enclos (elle est à
 * l'éleveur), ni une bête à quelqu'un (nom, laisse, selle, maître), ni chez un voisin, ni un petit ; et il en laisse toujours
 * {@link #GARDER} adultes de chaque espèce aux alentours, pour que le gibier revienne.
 */
final class Chasse {
    private Chasse() {}

    /** Une espèce de gibier : son id, son nom (singulier, pluriel), son type d'entité du jeu, et ses mots (sans accents). */
    record Espece(String id, String nom, String noms, String type, List<String> mots) {}

    static final List<Espece> TOUTES = List.of(
            new Espece("vache", "vache", "vaches", "minecraft:cow", Elevage.MOTS.get(0)),
            new Espece("mouton", "mouton", "moutons", "minecraft:sheep", Elevage.MOTS.get(1)),
            new Espece("cochon", "cochon", "cochons", "minecraft:pig", List.of("cochon", "cochons", "porc", "porcs", "sanglier", "sangliers", "truie", "truies",
                    "pourceau", "pourceaux", "goret", "gorets", "verrat", "verrats", "marcassin", "marcassins")),
            new Espece("poule", "poule", "poules", "minecraft:chicken", Elevage.MOTS.get(3)),
            new Espece("lapin", "lapin", "lapins", "minecraft:rabbit", List.of("lapin", "lapins", "lievre", "lievres", "garenne", "lapereau", "lapereaux")));

    /** Les travaux du chasseur demandés au répertoire (programme « travail »). */
    static final Set<String> TRAVAUX = Set.of("chasser");

    /** Il laisse toujours tant d'adultes de chaque espèce dans sa zone de chasse. */
    static final int GARDER = 2;
    /** Au plus tant de bêtes par tour, puis retour au dépôt et repos. */
    static final int PAR_TOUR = 3;
    /** Sa zone de chasse, autour du poste (plus large que l'espace de vie). */
    static final int ZONE = 64;

    static Espece parId(String id) {
        for (Espece e : TOUTES) if (e.id.equals(id)) return e;
        return null;
    }

    static Espece parType(String type) {
        for (Espece e : TOUTES) if (e.type.equals(type)) return e;
        return null;
    }

    /** L'espèce dite (« chasse des lapins » : le lapin), ou null si la phrase n'en nomme aucune. */
    static Espece dite(String phrase) {
        String n = Ordres.normaliser(phrase == null ? "" : phrase);
        for (Espece e : TOUTES) for (String m : e.mots) if (n.contains(" " + m + " ")) return e;
        return null;
    }

    /**
     * Une bête vue par le chasseur. {@code sauvage} : hors de tout enclos ; {@code intouchable} : à quelqu'un (nom, laisse,
     * selle, maître) ou chez un voisin ; {@code distance} : au carré, depuis lui.
     */
    record Bete(int id, String espece, boolean adulte, boolean sauvage, boolean intouchable, double distance) {}

    /**
     * Les bêtes qu'il peut tirer, la plus proche d'abord : {@code voulu} au plus, des espèces dites ({@code especes}, ou toutes
     * si null), des adultes sauvages à personne ; il laisse toujours {@link #GARDER} adultes sauvages de chaque espèce (les
     * bêtes à quelqu'un comptent parmi elles, pas celles des enclos).
     */
    static List<Integer> proies(List<Bete> betes, Set<String> especes, int voulu) {
        Map<String, Integer> reste = new HashMap<>();
        for (Bete b : betes) if (b.adulte && b.sauvage) reste.merge(b.espece, 1, Integer::sum);
        List<Bete> tri = new ArrayList<>(betes);
        tri.sort(Comparator.comparingDouble(Bete::distance));
        List<Integer> r = new ArrayList<>();
        for (Bete b : tri) {
            if (r.size() >= voulu) break;
            if (especes != null && !especes.contains(b.espece) || !b.adulte || !b.sauvage || b.intouchable) continue;
            int n = reste.getOrDefault(b.espece, 0);
            if (n <= GARDER) continue;
            reste.put(b.espece, n - 1);
            r.add(b.id);
        }
        return r;
    }

    /** Les espèces qui donnent ce que demande la mission (« cuir » : vaches et lapins), ou null pour toutes. */
    static Set<String> pour(String mission) {
        if (mission == null) return null;
        return switch (mission) {
            case "cuir" -> Set.of("vache", "lapin");
            case "plumes" -> Set.of("poule");
            default -> null;
        };
    }
}

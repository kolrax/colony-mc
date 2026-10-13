package fr.lacolonie.pnj;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ce que sait l'éleveur, sans le monde (pur Java, testé seul) : les quatre bêtes de ferme et leur fourrage, l'espèce dite par
 * le chef, ce qu'il a le droit d'abattre ou de mettre au mâle, et comment reconnaître un enclos (une zone close de barrières
 * ou de murs, avec ses portillons) à partir d'une bête. {@link Eleveur} y apporte le monde.
 */
final class Elevage {
    private Elevage() {}

    /** Une espèce : son id, son nom (singulier, pluriel), son fourrage (filtre d'objets, et son nom) et son type d'entité du jeu. */
    record Espece(String id, String nom, String noms, String fourrage, String fourrageNom, String type) {}

    static final List<Espece> TOUTES = List.of(
            new Espece("vache", "vache", "vaches", "#minecraft:cow_food", "blé", "minecraft:cow"),
            new Espece("mouton", "mouton", "moutons", "#minecraft:sheep_food", "blé", "minecraft:sheep"),
            new Espece("cochon", "cochon", "cochons", "#minecraft:pig_food", "carottes, pommes de terre ou betteraves", "minecraft:pig"),
            new Espece("poule", "poule", "poules", "#minecraft:chicken_food", "graines", "minecraft:chicken"));

    static Espece parId(String id) {
        for (Espece e : TOUTES) if (e.id.equals(id)) return e;
        return null;
    }

    static Espece parType(String type) {
        for (Espece e : TOUTES) if (e.type.equals(type)) return e;
        return null;
    }

    /** Les mots de chaque espèce (sans accents), dans l'ordre de {@link #TOUTES}. */
    static final List<List<String>> MOTS = List.of(
            List.of("vache", "vaches", "boeuf", "boeufs", "veau", "veaux", "bovin", "bovins", "genisse", "genisses", "taureau", "taureaux"),
            List.of("mouton", "moutons", "brebis", "agneau", "agneaux", "belier", "beliers", "ovin", "ovins"),
            List.of("cochon", "cochons", "porc", "porcs", "truie", "truies", "pourceau", "pourceaux", "goret", "gorets", "verrat", "verrats"),
            List.of("poule", "poules", "poulet", "poulets", "coq", "coqs", "volaille", "volailles", "poussin", "poussins", "chapon", "chapons"));

    /** L'espèce dite (« abats deux vaches » : la vache), ou null si la phrase n'en nomme aucune. */
    static Espece dite(String phrase) {
        String n = Ordres.normaliser(phrase == null ? "" : phrase);
        for (int i = 0; i < TOUTES.size(); i++) for (String m : MOTS.get(i)) if (n.contains(" " + m + " ")) return TOUTES.get(i);
        return null;
    }

    // --- Ce qu'il a le droit de faire ---------------------------------------------------------------------------

    /** Il garde toujours au moins un couple d'adultes de chaque espèce. */
    static final int COUPLE = 2;

    /**
     * Une bête d'un enclos, vue par l'éleveur. {@code intouchable} : nommée, sellée ou tenue en laisse (quelqu'un y tient) ;
     * {@code prete} : adulte qui peut être mise au mâle (ni déjà en chaleur, ni au repos après une naissance).
     */
    record Bete(int id, String espece, boolean adulte, boolean intouchable, boolean prete) {}

    /** Le nombre de bêtes par enclos et par espèce au-delà duquel il n'en fait plus naître et abat l'excédent (2 au moins). */
    static int plafond(int reglage) { return Math.max(COUPLE, reglage); }

    /**
     * Les bêtes de cette espèce qu'il peut abattre (dans l'ordre de la liste) : {@code voulu} au plus, des adultes seulement,
     * jamais une bête intouchable, et il en reste toujours {@code garder} adultes (et au moins un couple), intouchables compris.
     * Seul, il garde le plafond (il n'abat que l'excédent) ; sur ordre, il peut descendre jusqu'au dernier couple.
     */
    static List<Integer> aAbattre(List<Bete> betes, String espece, int garder, int voulu) {
        int adultes = 0;
        List<Integer> libres = new ArrayList<>();
        for (Bete b : betes) {
            if (!b.espece.equals(espece) || !b.adulte) continue;
            adultes++;
            if (!b.intouchable) libres.add(b.id);
        }
        int n = Math.min(Math.min(voulu, libres.size()), adultes - Math.max(garder, COUPLE));
        return n <= 0 ? List.of() : List.copyOf(libres.subList(0, n));
    }

    /**
     * Les bêtes de cette espèce à nourrir pour qu'elles fassent des petits : par couples d'adultes prêts, seulement s'il y a
     * au moins un couple, et sans dépasser {@code plafond} bêtes (petits compris) une fois les petits nés.
     */
    static List<Integer> aNourrir(List<Bete> betes, String espece, int plafond) {
        int adultes = 0, toutes = 0;
        List<Integer> pretes = new ArrayList<>();
        for (Bete b : betes) {
            if (!b.espece.equals(espece)) continue;
            toutes++;
            if (b.adulte) adultes++;
            if (b.adulte && b.prete) pretes.add(b.id);
        }
        if (adultes < COUPLE) return List.of();
        int couples = Math.min(pretes.size() / 2, plafond - toutes);
        return couples <= 0 ? List.of() : List.copyOf(pretes.subList(0, 2 * couples));
    }

    // --- Les enclos ---------------------------------------------------------------------------------------------

    /** Réponses de {@link Terrain#pas} : une clôture (barrière, mur, paroi trop haute), ou un portillon. */
    static final int CLOTURE = Integer.MIN_VALUE, PORTILLON = Integer.MIN_VALUE + 1;

    /** Ce que le monde dit d'une colonne. */
    interface Terrain {
        /**
         * Pour une bête qui arrive en (x, z) depuis la hauteur {@code y} d'une colonne voisine : la hauteur de ses pieds là
         * (une marche d'un bloc, ou une descente), ou {@link #CLOTURE}, ou {@link #PORTILLON} (un portillon à la hauteur y).
         */
        int pas(int x, int y, int z);
    }

    /** Un enclos : ses cases (x, z), ses portillons {x, y, z}, son rectangle et la hauteur du sol à sa première case. */
    record Enclos(Set<Long> cases, List<int[]> portillons, int minX, int minZ, int maxX, int maxZ, int y) {
        boolean contient(int x, int z) { return cases.contains(cle(x, z)); }
    }

    static long cle(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    /** Au plus tant de cases (32 sur 32) : au-delà, c'est un pré ouvert, pas un enclos. */
    static final int CASES_MAX = 1024;

    /**
     * L'enclos où se tient une bête en (x, y, z) : toutes les cases où elle peut aller à pied, de proche en proche (4
     * directions), jusqu'aux clôtures et aux portillons. Null si elle peut aller plus loin que {@code max} cases (un pré
     * ouvert, ou une clôture percée) ; {@code ouvertes} reçoit alors les cases vues, pour ne pas refaire le tour pour la
     * bête voisine.
     */
    static Enclos clore(int x, int y, int z, Terrain terrain, int max, Set<Long> ouvertes) {
        Set<Long> vues = new HashSet<>();
        Set<Long> portes = new HashSet<>();
        List<int[]> portillons = new ArrayList<>();
        ArrayDeque<int[]> file = new ArrayDeque<>();
        vues.add(cle(x, z));
        file.add(new int[]{x, y, z});
        int minX = x, minZ = z, maxX = x, maxZ = z;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!file.isEmpty()) {
            int[] c = file.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[2] + d[1];
                long k = cle(nx, nz);
                if (vues.contains(k) || portes.contains(k)) continue;
                int h = terrain.pas(nx, c[1], nz);
                if (h == CLOTURE) continue;
                if (h == PORTILLON) { portes.add(k); portillons.add(new int[]{nx, c[1], nz}); continue; }
                if (ouvertes != null && ouvertes.contains(k)) { vues.add(k); ouvertes.addAll(vues); return null; }
                vues.add(k);
                if (vues.size() > max) { if (ouvertes != null) ouvertes.addAll(vues); return null; }
                minX = Math.min(minX, nx); maxX = Math.max(maxX, nx);
                minZ = Math.min(minZ, nz); maxZ = Math.max(maxZ, nz);
                file.add(new int[]{nx, h, nz});
            }
        }
        return new Enclos(Set.copyOf(vues), List.copyOf(portillons), minX, minZ, maxX, maxZ, y);
    }
}

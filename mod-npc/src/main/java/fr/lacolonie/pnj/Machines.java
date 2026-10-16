package fr.lacolonie.pnj;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les machines que le transporteur remplit et vide (lot 12, cahier 4.3, pur Java) : leur nom en français, reconnu par
 * l'identifiant du bloc (sans dépendre de Create pour compiler), et ce qu'il faut savoir pour les servir : à quel bloc voisin
 * on pose quand la pièce montrée ne prend rien (la presse : le présentoir dessous ; l'entonnoir : le bloc où il est
 * accroché), les machines qu'on ne vide jamais (un fabricant mécanique ne garde que ses entrées), et la première case de
 * sortie (un bassin : ses neuf premières cases sont les entrées, il ne les vide jamais). Test {@code TransporteurTest}.
 * <p>
 * Identifiants vérifiés sur le jar de Create 6.0.10 pour 1.21.1 (blockstates et capacités enregistrées) : moulin, bassin,
 * présentoir, tapis, goulottes, déployeur, fabricant, contrôleur des roues exposent les objets ; la presse, le mélangeur, les
 * roues de broyage et les entonnoirs non.
 */
final class Machines {
    /** Où trouver la capacité d'objets d'une pièce qui n'en a pas. */
    enum Relais { AUCUN, DESSOUS, ACCROCHE, CONTROLEUR }

    /** Une machine : son nom avec l'article (« le moulin »), le relais, jamais vidée, la première case de sortie. */
    record Machine(String nom, Relais relais, boolean entreeSeule, int sortie) {}

    static final Map<String, Machine> PAR_ID = new LinkedHashMap<>();

    static void m(String nom, Relais r, boolean entreeSeule, int sortie, String... ids) { for (String id : ids) PAR_ID.put(id, new Machine(nom, r, entreeSeule, sortie)); }

    static {
        m("le moulin", Relais.AUCUN, false, 0, "create:millstone");
        m("le bassin", Relais.AUCUN, false, 9, "create:basin");
        m("le présentoir", Relais.AUCUN, false, 0, "create:depot");
        m("le tapis", Relais.AUCUN, false, 0, "create:belt");
        m("la goulotte", Relais.AUCUN, false, 0, "create:chute", "create:smart_chute");
        m("le déployeur", Relais.AUCUN, false, 0, "create:deployer");
        m("l'éjecteur", Relais.AUCUN, false, 0, "create:weighted_ejector");
        m("la scie", Relais.AUCUN, false, 0, "create:mechanical_saw");
        m("la chambre forte", Relais.AUCUN, false, 0, "create:item_vault");
        m("le fabricant mécanique", Relais.AUCUN, true, 0, "create:mechanical_crafter");
        m("le broyeur", Relais.AUCUN, true, 0, "create:crushing_wheel_controller");
        m("le broyeur", Relais.CONTROLEUR, true, 0, "create:crushing_wheel");
        m("la presse", Relais.DESSOUS, false, 0, "create:mechanical_press");
        m("le mélangeur", Relais.DESSOUS, false, 0, "create:mechanical_mixer");
        m("l'entonnoir", Relais.ACCROCHE, false, 0, "create:andesite_funnel", "create:brass_funnel");
        m("l'entonnoir", Relais.DESSOUS, false, 0, "create:andesite_belt_funnel", "create:brass_belt_funnel");
        m("le four", Relais.AUCUN, false, 0, "minecraft:furnace");
        m("le haut fourneau", Relais.AUCUN, false, 0, "minecraft:blast_furnace");
        m("le fumoir", Relais.AUCUN, false, 0, "minecraft:smoker");
        m("la trémie", Relais.AUCUN, false, 0, "minecraft:hopper");
    }

    /** Le contrôleur entre deux roues de broyage : là où l'on verse ce qu'il faut broyer. */
    static final String CONTROLEUR = "create:crushing_wheel_controller";

    private Machines() {}

    static Machine de(String id) { return PAR_ID.get(id); }

    /** Une machine connue (pas un coffre, un tonneau, une caisse). */
    static boolean machine(String id) { return PAR_ID.containsKey(id); }

    /** Une pièce de Create (même inconnue d'ici) : sans capacité d'objets, on dit quoi viser plutôt que « ce n'est pas un coffre ». */
    static boolean deCreate(String id) { return id.startsWith("create:"); }

    /** Son nom, article compris : « le moulin », « le coffre » pour tout le reste. */
    static String nom(String id) {
        Machine m = PAR_ID.get(id);
        return m == null ? "le coffre" : m.nom();
    }

    /** « Le moulin » en tête de phrase. */
    static String Nom(String id) {
        String n = nom(id);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /**
     * Notre « dépôt », c'est celui du poste ; « le dépôt de Create », « ce dépôt-là », « ce dépôt » (regardé), c'est le
     * présentoir de Create. Sur une phrase normalisée ({@link Ordres#normaliser}) : ces dépôts-là deviennent « présentoir ».
     */
    static String sansDepotDeCreate(String n) {
        return n.replace(" depot de create ", " presentoir ").replace(" ce depot la ", " ce presentoir ").replace(" ce depot ci ", " ce presentoir ")
                .replace(" ce depot ", " ce presentoir ").replace(" depot la ", " presentoir ");
    }
}

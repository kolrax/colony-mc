package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Trajets.Sens;
import fr.lacolonie.pnj.Trajets.Trajet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lot 6, le transporteur : le sens des trajets dits, combien il en retient, lequel il fait, combien il prend. */
class TransporteurTest {
    @Test
    void sensDuTrajet() {
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("Porte le contenu de ce coffre au dépôt"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("porte le fer de ce coffre au dépôt"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("vide ce coffre"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("Porte les bûches du dépôt à ce coffre"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("remplis ce coffre"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("approvisionne ce coffre avec du charbon"));
    }

    @Test
    void sixTrajetsAuPlusSansDoublon() {
        List<Trajet> l = new ArrayList<>();
        for (int i = 0; i < Trajets.MAX; i++) l = Trajets.ajouter(l, new Trajet(i, Trajets.DEPOT, ""));
        assertEquals(6, l.size());
        assertNull(Trajets.ajouter(l, new Trajet(99, Trajets.DEPOT, "")), "un septième : refusé");
        List<Trajet> m = Trajets.ajouter(l, new Trajet(2, Trajets.DEPOT, ""));
        assertEquals(6, m.size(), "le même trajet n'est pas ajouté deux fois");
        // Le même trajet remontré reprend (plus suspendu).
        l.set(1, l.get(1).suspendre(true));
        assertFalse(Trajets.ajouter(l, new Trajet(1, Trajets.DEPOT, "")).get(1).suspendu());
        // Un autre objet, c'est un autre trajet.
        assertEquals(3, Trajets.ajouter(List.of(new Trajet(1, 2, ""), new Trajet(1, 2, "minecraft:iron_ingot")), new Trajet(1, 2, "#minecraft:logs")).size());
    }

    @Test
    void trajetSuivantEnBoucle() {
        List<Trajet> l = List.of(new Trajet(1, Trajets.DEPOT, ""), new Trajet(2, Trajets.DEPOT, "").suspendre(true), new Trajet(3, Trajets.DEPOT, "").attendre(100));
        assertEquals(0, Trajets.suivant(l, 0, 50), "le suspendu et celui qui attend sont sautés : il revient au premier");
        assertEquals(2, Trajets.suivant(l, 0, 100), "l'attente finie, le troisième");
        assertEquals(0, Trajets.suivant(l, 2, 100), "en boucle");
        assertEquals(-1, Trajets.suivant(List.of(new Trajet(1, 2, "").suspendre(true)), 0, 0));
        assertEquals(-1, Trajets.suivant(List.of(), 0, 0));
    }

    @Test
    void jamaisPlusQueLaPlaceDuSac() {
        assertEquals(64 * 2 + 14, Trajets.place(2, 64, List.of(50, 64)));
        assertEquals(3, Trajets.place(0, 16, List.of(13)));
        assertEquals(10, Trajets.prise(10, 64));
        assertEquals(5, Trajets.prise(40, 5));
        assertEquals(0, Trajets.prise(40, 0));
        assertTrue(Trajets.tousVides(4, 2));
        assertFalse(Trajets.tousVides(3, 2), "chaque trajet vide deux fois de suite");
        assertFalse(Trajets.tousVides(5, 0));
    }

    @Test
    void ordresCompris() {
        RepertoireTest.demande("Porte le contenu de ce coffre au dépôt", "transporteur", "trajet");
        RepertoireTest.demande("Porte le fer de ce coffre au dépôt", "transporteur", "trajet");
        RepertoireTest.demande("Porte les bûches du dépôt à ce coffre", "transporteur", "trajet");
        RepertoireTest.demande("Remplis ce coffre", "transporteur", "trajet");
        RepertoireTest.demande("Prends dans ce coffre", "transporteur", "trajet_prendre");
        RepertoireTest.demande("Prends ce coffre", "transporteur", "trajet_prendre");
        RepertoireTest.demande("Dépose dans celui-là", "transporteur", "trajet_deposer");
        RepertoireTest.demande("Oublie tes trajets", "transporteur", "oublier_trajets");
    }

    // --- Lot 12 : les machines (Create) ---------------------------------------------------------------------------

    @Test
    void machinesDansLesOrdres() {
        RepertoireTest.demande("Remplis le moulin avec le blé", "transporteur", "trajet_machine");
        RepertoireTest.demande("Mets le minerai dans ce broyeur", "transporteur", "trajet_machine");
        RepertoireTest.demande("Mets le fer sur le tapis", "transporteur", "trajet_machine");
        RepertoireTest.demande("Mets le blé sur ce présentoir", "transporteur", "trajet_machine");
        RepertoireTest.demande("Mets le fer sous la presse", "transporteur", "trajet_machine");
        RepertoireTest.demande("Vide cette machine", "transporteur", "trajet_machine");
        RepertoireTest.demande("Vide ce bassin", "transporteur", "trajet_machine");
        RepertoireTest.demande("Vide ce moulin", "transporteur", "trajet_machine");
        RepertoireTest.demande("Ramène ce qui sort de la machine au dépôt", "transporteur", "trajet_machine");
        RepertoireTest.demande("Prends dans ce bassin", "transporteur", "trajet_prendre_machine");
        RepertoireTest.demande("Dépose dans ce moulin", "transporteur", "trajet_deposer_machine");
        RepertoireTest.demande("Dépose-le sur ce tapis", "transporteur", "trajet_deposer_machine");
    }

    @Test
    void sensDesMachines() {
        assertEquals(Sens.DU_DEPOT, Trajets.sens("Remplis le moulin avec le blé"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("mets le minerai dans ce broyeur"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("mets le fer sur le tapis"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("verse le blé dans la meule"));
        // « Le dépôt de Create », « ce dépôt-là » : la machine regardée, pas notre dépôt.
        assertEquals(Sens.DU_DEPOT, Trajets.sens("mets le blé dans le dépôt de Create"));
        assertEquals(Sens.DU_DEPOT, Trajets.sens("mets le fer dans ce dépôt-là"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("vide ce dépôt-là"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("vide cette machine"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("ramène ce qui sort de la machine au dépôt"));
        // Notre dépôt reste le nôtre.
        assertEquals(Sens.DU_DEPOT, Trajets.sens("mets le blé du dépôt dans ce moulin"));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("porte le fer de ce coffre au dépôt"));
    }

    @Test
    void nomsEtReglesDesMachines() {
        assertEquals("le moulin", Machines.nom("create:millstone"));
        assertEquals("le présentoir", Machines.nom("create:depot"));
        assertEquals("le tapis", Machines.nom("create:belt"));
        assertEquals("l'entonnoir", Machines.nom("create:brass_funnel"));
        assertEquals("le four", Machines.nom("minecraft:furnace"));
        assertEquals("le coffre", Machines.nom("minecraft:chest"), "un vrai coffre reste un coffre");
        assertEquals("le coffre", Machines.nom("minecraft:barrel"));
        assertEquals("Le bassin", Machines.Nom("create:basin"));
        assertFalse(Machines.machine("minecraft:chest"));
        assertTrue(Machines.deCreate("create:shaft"));
        // Le bassin garde ses entrées (cases 0 à 8) : il ne prend qu'à partir de la sortie.
        assertEquals(9, Machines.de("create:basin").sortie());
        assertEquals(0, Machines.de("create:millstone").sortie());
        assertTrue(Machines.de("create:mechanical_crafter").entreeSeule(), "un fabricant ne se vide pas");
        assertEquals(Machines.Relais.DESSOUS, Machines.de("create:mechanical_press").relais());
        assertEquals(Machines.Relais.ACCROCHE, Machines.de("create:andesite_funnel").relais());
        assertEquals(Machines.Relais.CONTROLEUR, Machines.de("create:crushing_wheel").relais());
        assertEquals(Machines.Relais.AUCUN, Machines.de("create:depot").relais());
    }

    @Test
    void repliquesDesMachines() {
        var banque = Vie.lire(Repertoire.lignes("/machines.txt"));
        for (String k : List.of("machine_remplir_ok", "machine_vider_ok", "machine_charge", "machine_vide_rien", "machine_pleine", "machine_pas_capable", "machine_introuvable"))
            assertTrue(banque.containsKey(k) && Vie.TOUTES.containsKey(k), k);
        for (var e : banque.entrySet())
            for (Vie.Replique r : e.getValue())
                for (String mot : List.of("joueur", "serveur", "Minecraft", " IA ", " au {machine}", " du {machine}", " à {machine}", " de {machine}"))
                    assertFalse(r.texte().contains(mot), mot + " dans [" + e.getKey() + "]");
        String s = Vie.tirer(banque, "machine_pleine", "", java.util.Map.of("machine", "le moulin", "chef", "chef"), () -> 0.0);
        assertFalse(s.contains("{"), s);
    }

    // --- Relecture du 16/10 ---------------------------------------------------------------------------------------

    /** Une machine regardée, sans verbe qui la vide : on la remplit. « Vide » l'emporte ; « charge » garde son sens pour un coffre. */
    @Test
    void uneMachineSeRemplitParDefaut() {
        for (String p : List.of("Porte le blé dans le moulin", "Apporte du charbon dans le four", "Amène le minerai dans le broyeur", "Porte le fer sous la presse", "Le blé dans le moulin"))
            assertEquals(Sens.DU_DEPOT, Trajets.sens(p, true), p);
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("Vide le moulin et mets la farine dans le coffre", true));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("Ramène ce qui sort du moulin", true));
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("Charge le fer de ce coffre"), "un coffre : vers le dépôt, comme avant");
        assertEquals(Sens.VERS_DEPOT, Trajets.sens("Porte le contenu de ce coffre"), "un coffre sans verbe : vers le dépôt, comme avant");
        // « Le dépôt là-bas » reste notre dépôt.
        assertEquals(Sens.DU_DEPOT, Trajets.sens("Porte le fer du dépôt là-bas à ce coffre"));
        for (String p : List.of("Porte le blé dans le moulin", "Apporte du charbon dans le four", "Le blé dans le moulin", "Porte le fer sous la presse"))
            RepertoireTest.demande(p, "transporteur", "trajet_machine");
    }

    /** Les formules des machines ne prennent pas les ordres des autres métiers : la phrase va au même métier qu'avant. */
    @Test
    void lesMachinesNeVolentPasLesOrdres() {
        java.util.Map<String, java.util.Set<String>> avant = new java.util.LinkedHashMap<>();
        avant.put("Mets la viande dans le four", java.util.Set.of("cuisinier"));
        avant.put("Cuis du poisson dans le four", java.util.Set.of());
        avant.put("Cuis les patates dans ce four", java.util.Set.of());
        avant.put("va cuire dans le four", java.util.Set.of());
        avant.put("Mets du bois dans le four", java.util.Set.of());
        avant.put("Fais du charbon dans le four", java.util.Set.of());
        avant.put("nourris les vaches", java.util.Set.of("eleveur"));
        avant.put("vends le pain à 2 pièces", java.util.Set.of());
        avant.put("Mets-toi dans la machine", java.util.Set.of());
        avant.put("Fais du pain dans le four", java.util.Set.of("fermier", "cuisinier"));
        avant.forEach((p, m) -> assertEquals(m, Repertoire.metiersConcernes(p), p));
        // Au transporteur lui-même : « mets-toi dans la machine », « fais du pain dans le four » ne sont pas des trajets.
        for (String p : List.of("Mets-toi dans la machine", "Fais du pain dans le four", "Cuis le poisson dans ce four"))
            assertFalse(RepertoireTest.action(p, "transporteur").startsWith("trajet"), p + " : " + RepertoireTest.action(p, "transporteur"));
    }

    /** Une machine qu'il remplit et qu'il vide : il n'y reprend pas ce qu'il a posé, ni rien juste après une pose. */
    @Test
    void neReprendPasCeQuIlAPose() {
        List<Trajet> l = List.of(new Trajet(Trajets.DEPOT, 5, "minecraft:iron_ingot"), new Trajet(5, Trajets.DEPOT, ""));
        assertTrue(Trajets.alimente(l, 5));
        assertFalse(Trajets.alimente(l, 6));
        java.util.Set<String> poses = java.util.Set.of("minecraft:iron_ingot");
        assertFalse(Trajets.prenable("minecraft:iron_ingot", true, poses, 0, 1000), "ce qu'il a posé");
        assertTrue(Trajets.prenable("minecraft:iron_nugget", true, poses, 0, 1000), "le produit");
        assertFalse(Trajets.prenable("minecraft:iron_nugget", true, poses, 990, 1000), "juste après une pose : rien");
        assertTrue(Trajets.prenable("minecraft:iron_ingot", false, poses, 990, 1000), "une machine qu'il ne remplit pas : tout");
        assertTrue(Machines.four("minecraft:blast_furnace") && !Machines.four("create:millstone"));
    }
}

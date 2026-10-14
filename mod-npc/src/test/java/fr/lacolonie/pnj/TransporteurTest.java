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
}

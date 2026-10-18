package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Plan D : chaque chef ne voit sur sa carte que ses propres ouvriers, et rien ne part pour rien. */
class ReperesTest {
    static Reperes.Repere repere(String prenom, int x) {
        return new Reperes.Repere(UUID.nameUUIDFromBytes(prenom.getBytes()), prenom, "minecraft:overworld", x, 64, 0);
    }

    @Test
    void chaqueChefNeVoitQueSesOuvriers() {
        UUID kolrax = UUID.randomUUID(), voisin = UUID.randomUUID();
        var jean = repere("Jean", 1);
        var isabeau = repere("Isabeau", 2);
        var gauthier = repere("Gauthier", 3);
        var errant = repere("Errant", 4);
        Map<UUID, List<Reperes.Repere>> r = Reperes.parChef(List.of(
                new Reperes.Ouvrier(kolrax, jean), new Reperes.Ouvrier(voisin, gauthier),
                new Reperes.Ouvrier(null, errant), new Reperes.Ouvrier(kolrax, isabeau)));
        assertEquals(2, r.size(), "le chercheur d'emploi (sans chef) ne va à personne : " + r);
        assertEquals(2, r.get(kolrax).size());
        assertTrue(r.get(kolrax).containsAll(List.of(jean, isabeau)), "les deux siens : " + r.get(kolrax));
        assertFalse(r.get(kolrax).contains(gauthier), "pas l'ouvrier du voisin");
        assertEquals(List.of(gauthier), r.get(voisin), "le voisin, seulement le sien");
        assertNull(r.get(UUID.randomUUID()), "un joueur sans ouvrier n'a rien");
    }

    @Test
    void ordreFixe() {
        UUID chef = UUID.randomUUID();
        var a = repere("Jean", 1);
        var b = repere("Isabeau", 2);
        assertEquals(Reperes.parChef(List.of(new Reperes.Ouvrier(chef, a), new Reperes.Ouvrier(chef, b))).get(chef),
                Reperes.parChef(List.of(new Reperes.Ouvrier(chef, b), new Reperes.Ouvrier(chef, a))).get(chef),
                "même liste quel que soit l'ordre des entités : pas de renvoi pour rien");
    }

    @Test
    void envoiSeulementQuandCaChange() {
        var l = List.of(repere("Jean", 1));
        assertFalse(Reperes.aEnvoyer(null, List.of()), "un joueur sans ouvrier ne reçoit rien");
        assertTrue(Reperes.aEnvoyer(null, l), "premier envoi");
        assertFalse(Reperes.aEnvoyer(l, List.of(repere("Jean", 1))), "rien n'a bougé");
        assertTrue(Reperes.aEnvoyer(l, List.of(repere("Jean", 2))), "il a bougé");
        assertTrue(Reperes.aEnvoyer(l, List.of()), "plus d'ouvrier : on efface ses repères");
    }

    @Test
    void sonPrenomSurLeRepere() {
        assertEquals("Je", Reperes.symbole(" Jean "));
        assertEquals("Él", Reperes.symbole("Éloi"));
        assertEquals("Y", Reperes.symbole("Y"));
        assertEquals("Ou", Reperes.symbole(""));
    }
}

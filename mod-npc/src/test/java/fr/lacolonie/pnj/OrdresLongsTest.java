package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Ordres.Intention;
import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Les ordres longs du cahier 8.3, lus par {@link Ordres#lire} (voir {@link OrdresLongs}). */
class OrdresLongsTest {
    static List<String> regles(Ordres.Lecture l) { return l.regles.stream().map(Object::toString).toList(); }

    @Test
    void tousLesJours() {
        Ordres.Lecture l = Ordres.lire("Tous les jours, ramène 64 bûches", "bucheron");
        assertTrue(l.quotidien);
        assertEquals(List.of(new Mission("tout", "quantite", 64)), l.missions);
        assertTrue(l.regles.isEmpty());
    }

    @Test
    void gardeToujoursUnStock() {
        Ordres.Lecture l = Ordres.lire("Garde toujours 64 charbons au dépôt", "mineur");
        assertEquals(List.of(new Mission("charbon", "stock", 64)), l.missions);
        assertFalse(l.quotidien);
    }

    @Test
    void quandTuAsFini() {
        Ordres.Lecture l = Ordres.lire("Quand tu as fini, va aider Raoul", "mineur");
        assertEquals(List.of("FINI → va aider Raoul"), regles(l));
        assertTrue(l.faire.isEmpty(), "il ne part pas tout de suite : " + l.faire);
        // Avec une mission dans la même phrase : la mission tout de suite, la suite plus tard.
        Ordres.Lecture m = Ordres.lire("Ramène 10 fers, et une fois fini, rentre au poste", "mineur");
        assertEquals(List.of(new Mission("fer", "quantite", 10)), m.missions);
        assertEquals(List.of("FINI → rentre au poste"), regles(m));
        assertFalse(m.intentions.contains(Intention.RENTRER));
    }

    @Test
    void siTuVoisUnZombie() {
        Ordres.Lecture l = Ordres.lire("Si tu vois un zombie, préviens-moi", "mineur");
        assertEquals(List.of("VOIT:zombie → préviens-moi"), regles(l));
        assertTrue(l.regles.get(0).previens());
        assertEquals(List.of("VOIT:creeper → préviens-moi"), regles(Ordres.lire("préviens-moi si tu croises un creeper.", "bucheron")));
        assertTrue(Ordres.lire("Si tu vois Raoul, salue-le", "mineur").regles.isEmpty(), "Raoul n'est pas une créature à guetter");
    }

    @Test
    void emploiDuTemps() {
        Ordres.Lecture l = Ordres.lire("Le matin tu mines, l'après-midi tu coupes", "");
        assertEquals(List.of("MATIN → tu mines", "APRES_MIDI → tu coupes"), regles(l));
        // À un mineur : il ne garde que ce qui est de son métier, et dit le reste.
        Ordres.Lecture m = Ordres.lire("Le matin tu mines, l'après-midi tu coupes", "mineur");
        assertEquals(List.of("MATIN → tu mines"), regles(m));
        assertTrue(m.horsMetier.containsKey("bucheron"), m.horsMetier.toString());
        assertEquals(List.of("SOIR → rentre au poste"), regles(Ordres.lire("Le soir, rentre au poste.", "mineur")));
    }

    @Test
    void conditions() {
        Ordres.Lecture l = Ordres.lire("S'il pleut, rentre.", "mineur");
        assertEquals(List.of("PLUIE → rentre"), regles(l));
        assertFalse(l.intentions.contains(Intention.RENTRER), "il ne rentre pas tout de suite");
        assertEquals(List.of("PLUIE → Rentre au poste"), regles(Ordres.lire("Rentre au poste s'il pleut", "mineur")));
        assertEquals(List.of("ORAGE → rentre"), regles(Ordres.lire("En cas d'orage, rentre", "bucheron")));
        assertEquals(List.of("NUIT → rentre au poste"), regles(Ordres.lire("Quand il fait nuit, rentre au poste", "bucheron")));
        // Pas des règles : des horaires, une salutation.
        assertTrue(Ordres.lire("Travaille jour et nuit", "mineur").regles.isEmpty());
        assertTrue(Ordres.lire("Bonne nuit Raoul", "mineur").regles.isEmpty());
        assertTrue(Ordres.lire("La nuit tombe vite ici", "mineur").regles.isEmpty(), "une suite qu'il ne comprend pas n'est pas une règle");
    }

    @Test
    void ordresDeGroupe() {
        assertTrue(Ordres.lire("Les mineurs, tous à la mine !", "mineur").intentions.contains(Intention.TRAVAIL));
        Ordres.Lecture b = Ordres.lire("Les bûcherons, au bois !", "bucheron");
        assertTrue(b.intentions.contains(Intention.TRAVAIL) && b.missions.isEmpty(), b.missions.toString());
        assertFalse(Ordres.lire("Je suis à la mine", "mineur").intentions.contains(Intention.TRAVAIL));
        Ordres.Adresse a = Ordres.adresse("Les mineurs, tous à la mine !", List.of("Bertrand", "Odon"), List.of("mineur", "bucheron"));
        assertEquals(java.util.Set.of("mineur"), a.metiersTous());
    }

    @Test
    void oublieTesOrdres() {
        for (String s : List.of("Oublie tes ordres", "Annule les consignes", "Plus de règles !"))
            assertTrue(Ordres.lire(s, "mineur").annulerRegles, s);
        assertFalse(Ordres.lire("Annule", "mineur").annulerRegles);
    }

    @Test
    void huitAuPlusMemeDeclencheurRemplace() {
        List<OrdresLongs.Regle> r = new ArrayList<>();
        OrdresLongs.ajouter(r, new OrdresLongs.Regle("PLUIE", "rentre"));
        OrdresLongs.ajouter(r, new OrdresLongs.Regle("PLUIE", "attends"));
        assertEquals(1, r.size());
        assertEquals("attends", r.get(0).suite);
        for (int i = 0; i < 10; i++) OrdresLongs.ajouter(r, new OrdresLongs.Regle("VOIT:x" + i, "préviens-moi"));
        assertEquals(OrdresLongs.MAX, r.size());
        assertEquals("VOIT:x9", r.get(r.size() - 1).quand);
    }

    /** Relecture du lot 3 : une période qui dit une échéance n'est pas une règle ; la conséquence est la seule proposition voisine. */
    @Test
    void pasDeFauxPositifs() {
        for (String s : List.of("Ramène 64 bûches avant la nuit", "Suis-moi jusqu'au soir", "Rentre au poste pour la nuit", "Travaille jusqu'au soir",
                "Ramène 20 charbons d'ici ce soir", "Viens avec moi, on part de nuit", "Ne travaille pas la nuit"))
            assertTrue(Ordres.lire(s, "bucheron").regles.isEmpty(), s + " → " + regles(Ordres.lire(s, "bucheron")));
        assertEquals(List.of(new Mission("tout", "quantite", 64)), Ordres.lire("Ramène 64 bûches avant la nuit", "bucheron").missions);
        assertTrue(Ordres.lire("Suis-moi jusqu'au soir", "mineur").intentions.contains(Intention.SUIVRE));
        assertTrue(Ordres.lire("Rentre au poste pour la nuit", "mineur").intentions.contains(Intention.RENTRER));
        assertEquals(List.of(new Mission("charbon", "quantite", 20)), Ordres.lire("Ramène 20 charbons d'ici ce soir", "mineur").missions);
        assertTrue(Ordres.lire("Viens avec moi, on part de nuit", "mineur").intentions.contains(Intention.SUIVRE));

        Ordres.Lecture a = Ordres.lire("Ramène 64 pierres et préviens-moi si tu vois un creeper", "mineur");
        assertEquals(List.of(new Mission("pierre", "quantite", 64)), a.missions);
        assertEquals(List.of("VOIT:creeper → préviens-moi"), regles(a));
        Ordres.Lecture b = Ordres.lire("Rentre au poste s'il pleut, et ramène 10 fers", "mineur");
        assertEquals(List.of("PLUIE → Rentre au poste"), regles(b));
        assertEquals(List.of(new Mission("fer", "quantite", 10)), b.missions);
        assertFalse(b.intentions.contains(Intention.RENTRER));
        assertEquals(List.of("VOIT:zombie → Préviens-moi", "NUIT → rentre"), regles(Ordres.lire("Préviens-moi si tu vois un zombie, et rentre la nuit", "mineur")));
    }
}

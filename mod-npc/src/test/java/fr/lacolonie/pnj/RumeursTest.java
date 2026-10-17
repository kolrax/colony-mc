package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static fr.lacolonie.pnj.Rumeurs.Genre.*;
import static org.junit.jupiter.api.Assertions.*;

class RumeursTest {

    /** Sans rumeurs.txt : les textes de secours (le récit ne dépend pas du hasard). */
    static final Rumeurs.Textes SECOURS = (cle, v) -> null;

    @Test
    void questions() {
        assertEquals(new Rumeurs.Question(null), Rumeurs.question("Quelles nouvelles ?"));
        assertEquals(new Rumeurs.Question(null), Rumeurs.question("Des rumeurs ?"));
        assertEquals(new Rumeurs.Question(null), Rumeurs.question("Tu as entendu quelque chose ?"));
        assertEquals(new Rumeurs.Question("Raoul"), Rumeurs.question("Que sais-tu de Raoul ?"));
        assertEquals(new Rumeurs.Question("Raoul"), Rumeurs.question("que sais tu sur la colonie de Raoul"));
        assertEquals(new Rumeurs.Question("Raoul"), Rumeurs.question("Qu'as-tu vu chez Raoul ?"));
        assertEquals(new Rumeurs.Question("Nielscutt"), Rumeurs.question("Des nouvelles de Nielscutt ?"));
        // Le travail, l'état, ses souvenirs : pas des rumeurs.
        for (String p : List.of("Que sais-tu faire ?", "Des nouvelles de ton travail ?", "Où en es-tu ?", "Tu te souviens de ce que je t'ai promis ?",
                "Ramène du fer", "Parle-moi de toi"))
            assertNull(Rumeurs.question(p), p);
    }

    @Test
    void ordreDEspionner() {
        assertEquals("Raoul", Rumeurs.espionner("Va espionner la colonie de Raoul."));
        assertEquals("Raoul", Rumeurs.espionner("espionne Raoul"));
        assertNull(Rumeurs.espionner("N'espionne pas Raoul."));
        assertNull(Rumeurs.espionner("Tu as espionné Raoul ?"));
        assertNull(Rumeurs.espionner("Va miner du fer."));
        assertEquals("puis ramène du fer.", Rumeurs.sansEspionner("Espionne Raoul, puis ramène du fer.").replaceFirst("^,\\s*", ""));
    }

    @Test
    void raconterDate() {
        Rumeurs r = new Rumeurs();
        r.voir("Raoul", NOMBRE, "5", 10);
        r.voir("Raoul", POSTE, "au nord-est|300 pas", 10);
        String dit = r.raconter(new Rumeurs.Question("Raoul"), 12, SECOURS, f -> true);
        assertTrue(dit.contains("Il y a 2 jours, j'ai vu 5 ouvriers chez Raoul."), dit);
        assertTrue(dit.contains("au nord-est, à 300 pas"), dit);
        // De seconde main : qui le lui a dit.
        Rumeurs b = new Rumeurs();
        assertTrue(b.entendre(r.fait("Raoul", NOMBRE), "Bertrand"));
        assertEquals("Bertrand m'a raconté ceci : Hier, j'ai vu 5 ouvriers chez Raoul.", b.raconter(new Rumeurs.Question(null), 11, SECOURS, f -> true));
        // Un seul ouvrier : au singulier.
        r.voir("Ysolde", NOMBRE, "1", 12);
        assertTrue(r.raconter(new Rumeurs.Question("Ysolde"), 12, SECOURS, f -> true).contains("1 ouvrier chez"));
    }

    @Test
    void rienInvente() {
        Rumeurs r = new Rumeurs();
        assertEquals("Raoul ? Je ne sais rien de lui.", r.raconter(new Rumeurs.Question("Raoul"), 3, SECOURS, f -> true));
        assertEquals("Je n'ai aucune nouvelle.", r.raconter(new Rumeurs.Question(null), 3, SECOURS, f -> true));
        r.voir("Raoul", GUERRE, "Nielscutt", 2);
        // Ce qu'il ne peut pas dire (sur son propre chef, à un étranger) : comme s'il n'en savait rien.
        assertEquals("Raoul ? Je ne sais rien de lui.", r.raconter(new Rumeurs.Question("Raoul"), 3, SECOURS, f -> !f.sujet().equals("Raoul")));
        assertEquals("", new Rumeurs().pourIA(3));
        assertTrue(r.pourIA(3).contains("Raoul et Nielscutt sont en guerre"), r.pourIA(3));
    }

    @Test
    void remplacementEtPlafond() {
        Rumeurs r = new Rumeurs();
        r.voir("Raoul", NOMBRE, "3", 1);
        r.voir("Raoul", NOMBRE, "6", 4);
        assertEquals(1, r.liste.size());
        assertEquals("6", r.fait("Raoul", NOMBRE).valeur());
        // Une nouvelle plus ancienne, ou entendue le jour où il a vu lui-même : ignorée.
        assertFalse(r.noter(new Rumeurs.Fait("Raoul", NOMBRE, "2", 3, Rumeurs.Source.VU, "")));
        assertFalse(r.entendre(new Rumeurs.Fait("Raoul", NOMBRE, "9", 4, Rumeurs.Source.VU, ""), "Bertrand"));
        assertEquals("6", r.fait("Raoul", NOMBRE).valeur());
        for (int i = 0; i < 30; i++) r.voir("Chef" + i, MORT, "X", 10 + i);
        assertEquals(Rumeurs.MAX, r.liste.size());
        assertNull(r.fait("Raoul", NOMBRE), "le plus ancien part");
        assertNotNull(r.fait("Chef29", MORT));
    }

    @Test
    void transmettre() {
        Rumeurs a = new Rumeurs(), b = new Rumeurs();
        a.voir("Raoul", NOMBRE, "4", 5);
        Rumeurs.Fait f = a.aTransmettre(b, x -> true, () -> 0.0);
        assertNotNull(f);
        b.entendre(f, "Aliénor");
        assertNull(a.aTransmettre(b, x -> true, () -> 0.0), "il le sait déjà");
        assertEquals(Rumeurs.Source.ENTENDU, b.fait("Raoul", NOMBRE).source());
        assertNull(a.aTransmettre(new Rumeurs(), x -> !x.sujet().equals("Raoul"), () -> 0.0), "jamais sur son propre chef");
    }

    @Test
    void sauvegarde() {
        Rumeurs r = new Rumeurs();
        r.voir("Raoul", POSTE, "au sud|50 pas", 7);
        r.entendre(new Rumeurs.Fait("Nielscutt", METIERS, "deux mineurs et un bûcheron", 6, Rumeurs.Source.VU, ""), "Bertrand");
        Rumeurs s = new Rumeurs();
        s.lireTextes(r.enTextes());
        assertEquals(r.liste, s.liste);
    }

    @Test
    void metiersEtDistance() {
        assertEquals("deux mineurs et un bûcheron", Rumeurs.metiers(Rumeurs.compter(List.of("Mineur", "Bûcheron", "Mineur"))));
        assertEquals("un guerrier", Rumeurs.metiers(Map.of("Guerrier", 1)));
        assertEquals("300 pas", Rumeurs.distance(310));
        assertEquals("20 pas", Rumeurs.distance(22));
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.lacolonie.pnj.Souvenirs.Genre.*;
import static org.junit.jupiter.api.Assertions.*;

class SouvenirsTest {

    @Test
    void promessesEtInsultes() {
        assertEquals(List.of(PROMESSE), Souvenirs.lire("Je te promets une hache neuve demain."));
        assertEquals(List.of(PROMESSE), Souvenirs.lire("Tu auras ta paie demain, promis."));
        assertEquals(List.of(INSULTE), Souvenirs.lire("Espèce de bon à rien !"));
        assertEquals("imbécile", Souvenirs.insulte("Quel IMBECILE, celui-là"));
        assertNull(Souvenirs.insulte("Tu n'es pas un idiot, Raoul."));
        // Une question sur ses souvenirs n'est pas une promesse.
        assertTrue(Souvenirs.question("Qu'est-ce que je t'ai promis ?"));
        assertEquals(List.of(), Souvenirs.lire("Qu'est-ce que je t'ai promis ?"));
        assertEquals(List.of(), Souvenirs.lire("J'ai une mission pour toi : ramène 10 fers."));
    }

    /** Seule compte l'insulte qui s'adresse à lui : pas un ordre de guerre, ni un mot sur une chose ou sur le chef lui-même. */
    @Test
    void insulteAdresseeSeulement() {
        for (String p : List.of("Tue cette vermine !", "Chassez ces gueux", "Attention à ce misérable zombie", "Je suis incapable de le trouver",
                "C'est idiot de miner ici"))
            assertNull(Souvenirs.insulte(p), p);
        assertEquals("bon à rien", Souvenirs.insulte("Tu es un bon à rien"));
        assertEquals("idiot", Souvenirs.insulte("Espèce d'idiot !"));
        assertEquals("imbécile", Souvenirs.insulte("Raoul, imbécile !"));
    }

    @Test
    void quaranteAuPlusEtLesPlusPrecieuxRestent() {
        Souvenirs s = new Souvenirs();
        s.noter(INSULTE, 0, 8, "Espèce d'abruti");
        for (int i = 0; i < 60; i++) s.noter(COMPLIMENT, i / 10, 9, "Bravo n°" + i);
        assertEquals(Souvenirs.MAX, s.liste.size());
        assertTrue(s.liste.stream().anyMatch(x -> x.genre() == INSULTE), "l'insulte, plus marquante, reste");
        assertFalse(s.liste.stream().anyMatch(x -> x.texte().equals("Bravo n°0")), "le plus vieux compliment part");
        // Sauvegardés puis relus.
        Souvenirs copie = new Souvenirs();
        copie.lireTextes(s.enTextes());
        assertEquals(s.liste, copie.liste);
    }

    @Test
    void ilRaconteAvecLeTempsEcoule() {
        Souvenirs s = new Souvenirs();
        s.noter(INSULTE, 10, 9, "Espèce de bon à rien !");
        s.noter(PROMESSE, 12, 15, "Je te promets une hache neuve.");
        String r = s.raconter("Tu te souviens de ce que je t'ai dit ?", 13, 10);
        assertTrue(r.contains("bon à rien il y a 3 jours"), r);
        assertTrue(r.contains("Hier, tu m'as promis"), r);
        assertTrue(s.raconter("Qu'est-ce que je t'ai promis ?", 16, 10).startsWith("Tu m'avais promis « Je te promets une hache neuve. », il y a 4 jours"),
                s.raconter("Qu'est-ce que je t'ai promis ?", 16, 10));
        assertEquals("Je ne me souviens de rien de particulier, chef.", new Souvenirs().raconter("De quoi te souviens-tu ?", 1, 1));
    }
}

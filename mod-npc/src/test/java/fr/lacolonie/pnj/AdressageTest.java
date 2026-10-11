package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** La formule apprise (src/main/resources/adressage.json) sur des cas nets, et la consigne de l'IA. */
class AdressageTest {
    static Adressage.Pnj pnj(String prenom, String metier, double d, double regard) {
        return new Adressage.Pnj(prenom, metier, d, regard, -1, false, false, List.of());
    }

    static List<String> choisis(Adressage.Situation s) {
        Adressage.Parametres p = Adressage.P;
        return Adressage.choisir(s, Adressage.code(s), p.seuilCode()).stream().map(i -> s.pnj().get(i).prenom()).collect(Collectors.toList());
    }

    static Adressage.Situation micro(String texte, Adressage.Pnj... l) { return new Adressage.Situation(texte, true, List.of(l), List.of()); }

    @Test
    void autantDIndicesQueDeNoms() {
        double[][] f = Adressage.indices(micro("Raoul, suis-moi", pnj("Raoul", "guerrier", 4, 0.2), pnj("Odon", "bucheron", 3, 0.97)));
        assertEquals(Adressage.INDICES.size(), f[0].length);
        assertEquals(Adressage.INDICES.size(), Adressage.P.poids().length);
    }

    @Test
    void casNets() {
        Adressage.Pnj raoul = pnj("Raoul", "guerrier", 9, 0.2), bertrand = pnj("Bertrand", "mineur", 3, 0.97), odon = pnj("Odon", "bucheron", 6, 0.1);
        assertEquals(List.of("Raoul"), choisis(micro("Raoul, suis-moi.", raoul, bertrand, odon)));
        assertEquals(List.of("Raoul", "Bertrand", "Odon"), choisis(micro("Les gars, suivez-moi !", raoul, bertrand, odon)));
        assertEquals(List.of("Bertrand", "Odon"), choisis(micro("Tout le monde sauf Raoul, au travail !", raoul, bertrand, odon)));
        assertEquals(List.of("Raoul"), choisis(micro("Va protéger la base.", raoul, bertrand, odon)), "le métier qui convient, même s'il regarde le mineur");
        assertEquals(List.of("Bertrand"), choisis(micro("Tu as faim ?", raoul, bertrand, odon)), "celui qu'il regarde");
        assertEquals(List.of(), choisis(micro("C'est vraiment un chômeur ce mec.", raoul, bertrand, odon)));
        assertEquals(List.of(), choisis(micro("Bon, je vais miner un peu.", raoul, bertrand, odon)));
        // À un autre joueur, qu'il regarde et qu'il nomme.
        Adressage.Situation ami = new Adressage.Situation("Niels, tu viens ?", true, List.of(bertrand), List.of(new Adressage.Joueur("Nielscutt", 4, 0.95)));
        assertEquals(List.of(), choisis(ami));
    }

    @Test
    void leCodeNeTrancheSeulQueSIlEstSur() {
        Adressage.Parametres p = Adressage.P;
        Adressage.Pnj raoul = pnj("Raoul", "guerrier", 9, 0.2), bertrand = pnj("Bertrand", "mineur", 3, 0.97);
        Adressage.Situation appel = micro("Raoul, suis-moi.", raoul, bertrand), groupe = micro("Les gars, suivez-moi !", raoul, bertrand);
        assertTrue(Adressage.sur(appel, Adressage.code(appel), p), "un prénom qui interpelle : sûr");
        assertTrue(Adressage.sur(groupe, Adressage.code(groupe), p), "« les gars » : sûr");
        Adressage.Situation bruit = micro("La la la.", raoul, pnj("Bertrand", "mineur", 9, 0.1));
        assertTrue(Adressage.sur(bruit, Adressage.code(bruit), p), "personne n'est visé, personne n'est regardé : sûr");
        Adressage.Situation regard = micro("Tu as faim ?", raoul, bertrand);
        assertFalse(Adressage.sur(regard, Adressage.code(regard), p), "deviné d'après le regard seul : l'IA vérifie");
    }

    @Test
    void consigneEtAvisDeLIA() {
        Adressage.Situation s = new Adressage.Situation("Il faudrait quelqu'un pour garder l'entrée.", true,
                List.of(new Adressage.Pnj("Raoul", "guerrier", 8.4, 0.1, 12, true, false, List.of("Raoul : Je reste ici, chef ?")), pnj("Bertrand", "mineur", 3, 0.95)),
                List.of(new Adressage.Joueur("Nielscutt", 6, 0.2)));
        List<String> et = Adressage.etiquettes(s);
        String c = Adressage.consigne(s, et);
        assertTrue(c.contains("« Il faudrait quelqu'un pour garder l'entrée. »"));
        assertTrue(c.contains("- Raoul : guerrier (") && c.contains("à 8 blocs") && c.contains("il vient de poser une question au chef"));
        assertTrue(c.contains("- Bertrand : mineur (") && c.contains("le chef le regarde"));
        assertTrue(c.contains("Nielscutt à 6 blocs"));
        assertTrue(Adressage.schema(et).toString().contains("\"probabilite\""));
        Adressage.Avis a = Adressage.lireAvis("{\"ouvriers\":[{\"qui\":\"Raoul\",\"lien\":\"garder l'entrée : un guerrier\",\"probabilite\":88},"
                + "{\"qui\":\"Inconnu\",\"lien\":\"\",\"probabilite\":99}]}", et);
        assertEquals(0.88, a.p()[0], 1e-9);
        assertTrue(Double.isNaN(a.p()[1]), "rien sur Bertrand : on garde l'avis du code");
        assertEquals(0.3, Adressage.fusion(0.3, Double.NaN, Adressage.P, false), 1e-9);
        assertTrue(Adressage.fusion(0.9, 0.9, Adressage.P, false) > Adressage.fusion(0.9, 0.1, Adressage.P, false));
        assertTrue(Adressage.fusion(0.9, 0.1, Adressage.P, false) > Adressage.fusion(0.1, 0.1, Adressage.P, false));
        assertEquals(Adressage.fusion(0.2, 0.99, Adressage.P, true), Adressage.fusion(0.2, 0.01, Adressage.P, true), 1e-9, "une remarque : l'avis de l'IA ne compte pas");
        assertTrue(Adressage.remarque(micro("Il bosse bien Bertrand, hein ?", pnj("Bertrand", "mineur", 4, 0.95))));
        assertTrue(Adressage.remarque(micro("Regarde ce que Bertrand a trouvé !", pnj("Bertrand", "mineur", 4, 0.95))));
        assertFalse(Adressage.remarque(micro("Bertrand, tu viens ?", pnj("Bertrand", "mineur", 4, 0.95))));
        assertFalse(Adressage.remarque(micro("Suis-moi.", pnj("Bertrand", "mineur", 4, 0.95))));
    }

    @Test
    void deuxOuvriersDuMemePrenom() {
        Adressage.Situation s = micro("Salut !", pnj("Raoul", "guerrier", 4, 0.2), pnj("Raoul", "mineur", 3, 0.97));
        assertEquals(List.of("Raoul le guerrier", "Raoul le mineur"), Adressage.etiquettes(s));
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObeissanceTest {
    @Test
    void ordresMortels() {
        assertEquals("lave", Obeissance.mortel("Saute dans la lave !"));
        assertEquals("lave", Obeissance.mortel("Va dans la lave"));
        assertEquals("vide", Obeissance.mortel("Saute dans le vide"));
        assertEquals("vide", Obeissance.mortel("Jette-toi du haut de la falaise"));
        assertEquals("vide", Obeissance.mortel("saute de la falaise"));
        assertEquals("feu", Obeissance.mortel("Va dans le feu"));
        assertEquals("noyade", Obeissance.mortel("Jette-toi à l'eau et noie-toi"));
        assertEquals("creuser", Obeissance.mortel("Creuse droit sous toi"));
        assertEquals("creuser", Obeissance.mortel("creuse sous tes pieds"));
        assertEquals("lave", Obeissance.mortel("Saute dans la lave, c'est un ordre !"));
        assertEquals("vide", Obeissance.mortel("Plonge dans le ravin"));
        assertEquals("noyade", Obeissance.mortel("Au travail, puis noie-toi"));
        // Les ordres proches, mais sans danger, et les mises en garde.
        for (String s : new String[]{"Va chercher de l'eau", "Creuse ici", "Creuse un trou", "Creuse vers le bas", "Va près du feu", "Va au feu de camp",
                "Saute", "Ne saute pas dans la lave", "Attention à la lave", "Vide ton sac", "Va à la rivière", "Creuse un tunnel tout droit",
                // Relecture : des ordres de tous les jours, même près d'un danger.
                "Va miner dans le ravin", "Va au ravin", "Va dans le ravin chercher du fer", "Va à la falaise chercher de la pierre",
                "Marche le long de la falaise", "Va au dépôt et vide ton sac", "Va boxer dans le vide", "Va construire un pont sur la lave",
                "Va jeter tes déchets dans la lave", "Va mettre une bûche dans le feu", "Va cuire la viande sur le feu",
                // Des mises en garde et des constats.
                "Ne va pas te noyer", "Tu vas te noyer", "Ne jamais sauter dans la lave", "Évite de sauter dans la lave", "Au travail, et ne va pas te noyer"})
            assertNull(Obeissance.mortel(s), s);
        // Compris par l'interpréteur (pas noté comme phrase incomprise).
        assertFalse(Ordres.lire("Jette-toi à l'eau et noie-toi", "mineur").vide());
    }

    @Test
    void accepterEtInsister() {
        for (String s : new String[]{"D'accord", "Oui", "Marché conclu !", "ça marche", "Tope là", "Topons là", "Oui, pas de souci", "D'accord, pas de problème", "D'accord, mais ne traîne pas"}) assertTrue(Obeissance.accepte(s), s);
        for (String s : new String[]{"Non", "Pas question", "Rentre au poste", "Pas d'accord", "Je ne suis pas d'accord", "Je n'accepte pas",
                "Ça va bien ?", "D'accord, laisse tomber", "C'est entendu, oublie ça"}) assertFalse(Obeissance.accepte(s), s);
        assertTrue(Obeissance.abandonne("Non, laisse tomber") && Obeissance.abandonne("Annule") && !Obeissance.abandonne("Non"));
        // Une phrase qui porte son propre ordre n'est ni une acceptation ni une insistance : c'est un nouvel ordre.
        for (String s : new String[]{"Fais le guet", "J'ai dit rentre au poste", "Oui, mais d'abord rentre au poste"})
            assertFalse(Obeissance.sansOrdre(Ordres.lire(s, "mineur")), s);
        for (String s : new String[]{"D'accord", "C'est un ordre !", "Non", "Pourquoi ?", "D'accord, marché conclu.", "Tope là !"})
            assertTrue(Obeissance.sansOrdre(Ordres.lire(s, "mineur")), s);
        // Seule une vraie question laisse l'ordre en suspens ; un ordre léger l'efface.
        assertTrue(Obeissance.question(Ordres.lire("Pourquoi ?", "mineur")));
        assertTrue(Obeissance.question(Ordres.lire("Ça va ?", "mineur")));
        for (String s : new String[]{"Suis-moi", "Attends ici", "Rentre au poste", "Garde à vous !"}) assertFalse(Obeissance.question(Ordres.lire(s, "mineur")), s);
    }

    @Test
    void satisfaction() {
        assertEquals(100, Obeissance.moral(95, 10));
        assertEquals(0, Obeissance.moral(5, -12));
        // La paie manque : la satisfaction vise 20, quelle que soit la faim ; sinon la nourriture décide.
        assertEquals(20, Obeissance.cible(1, 20));
        assertEquals(40, Obeissance.cible(0, 3));
        assertEquals(70, Obeissance.cible(0, 10));
        assertEquals(100, Obeissance.cible(0, 20));
        assertEquals(72, Obeissance.rapprocher(70, 100));
        assertEquals(68, Obeissance.rapprocher(70, 20));
        assertEquals(41, Obeissance.rapprocher(40, 41));
        assertEquals(50, Obeissance.rapprocher(50, 50));
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static fr.lacolonie.pnj.Obeissance.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class ObeissanceTest {
    static final String[] TEMPERAMENTS = {"calme", "peureux", "fourbe", "colerique"};
    static final Obeissance.Ordre LEGER = new Obeissance.Ordre(Obeissance.Peine.LEGER, false),
            NORMAL = new Obeissance.Ordre(Obeissance.Peine.NORMAL, false),
            PENIBLE = new Obeissance.Ordre(Obeissance.Peine.PENIBLE, false),
            DANGER = new Obeissance.Ordre(Obeissance.Peine.PENIBLE, true);

    @Test
    void tableDeDecision() {
        // Content : il obéit toujours (le peureux râle devant le danger).
        for (String t : TEMPERAMENTS) assertEquals(OBEIT, Obeissance.decider(70, t, PENIBLE, false), t);
        assertEquals(RALE, Obeissance.decider(70, "peureux", DANGER, false));
        // Les ordres légers : toujours obéis, même au plus bas.
        for (String t : TEMPERAMENTS) assertEquals(OBEIT, Obeissance.decider(0, t, LEGER, false), t);
        // Moral moyen : seuls les ordres pénibles se discutent.
        assertEquals(OBEIT, Obeissance.decider(40, "colerique", NORMAL, false));
        assertEquals(REFUSE, Obeissance.decider(30, "colerique", PENIBLE, false));
        assertEquals(RALE, Obeissance.decider(45, "colerique", PENIBLE, false));
        assertEquals(NEGOCIE, Obeissance.decider(40, "fourbe", PENIBLE, false));
        assertEquals(RALE, Obeissance.decider(40, "calme", PENIBLE, false));
        assertEquals(RALE, Obeissance.decider(40, "peureux", PENIBLE, false));
        assertEquals(NEGOCIE, Obeissance.decider(40, "peureux", DANGER, false));
        // Au plus bas : tout ordre non léger se marchande, ou se refuse (colérique).
        assertEquals(NEGOCIE, Obeissance.decider(20, "calme", NORMAL, false));
        assertEquals(REFUSE, Obeissance.decider(20, "colerique", NORMAL, false));
        // Le chef insiste : il obéit en râlant, sauf sous 15.
        assertEquals(RALE, Obeissance.decider(20, "colerique", PENIBLE, true));
        assertEquals(RALE, Obeissance.decider(20, "fourbe", PENIBLE, true));
        assertEquals(REFUSE, Obeissance.decider(10, "colerique", PENIBLE, true));
    }

    @Test
    void peineDesOrdres() {
        assertEquals(Obeissance.Peine.LEGER, peine("Suis-moi", "mineur", false));
        assertEquals(Obeissance.Peine.LEGER, peine("Garde à vous !", "mineur", false));
        assertEquals(Obeissance.Peine.NORMAL, peine("Au travail !", "mineur", false));
        assertEquals(Obeissance.Peine.PENIBLE, peine("Au travail !", "mineur", true));
        assertEquals(Obeissance.Peine.PENIBLE, peine("Ramène 64 fers", "mineur", false));
        assertEquals(Obeissance.Peine.PENIBLE, peine("Pars en expédition 7 jours", "mineur", false));
        assertEquals(Obeissance.Peine.PENIBLE, peine("Travaille toute la journée", "mineur", false));
        // Le combat : le métier du guerrier (ses ordres de guerre restent sûrs) ; pour un autre, ce n'est pas son métier (il le dit).
        assertEquals(Obeissance.Peine.LEGER, peine("Tue les monstres", "guerrier", false));
        assertEquals(Obeissance.Peine.LEGER, peine("Tue les monstres", "mineur", false));
        assertTrue(Obeissance.peine(Ordres.lire("Pars en expédition 7 jours", "mineur"), "mineur", false).dangereux());
    }

    static Obeissance.Peine peine(String texte, String metier, boolean horsService) {
        return Obeissance.peine(Ordres.lire(texte, metier), metier, horsService).peine();
    }

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
        // Les ordres proches, mais sans danger, et les mises en garde.
        for (String s : new String[]{"Va chercher de l'eau", "Creuse ici", "Creuse un trou", "Creuse vers le bas", "Va près du feu", "Va au feu de camp",
                "Saute", "Ne saute pas dans la lave", "Attention à la lave", "Vide ton sac", "Va à la rivière", "Creuse un tunnel tout droit"})
            assertNull(Obeissance.mortel(s), s);
        // Compris par l'interpréteur (pas noté comme phrase incomprise).
        assertFalse(Ordres.lire("Jette-toi à l'eau et noie-toi", "mineur").vide());
    }

    @Test
    void accepterEtInsister() {
        for (String s : new String[]{"D'accord", "Oui", "Marché conclu !", "ça marche", "Tope là"}) assertTrue(Obeissance.accepte(s), s);
        for (String s : new String[]{"Non", "Pas question", "Rentre au poste"}) assertFalse(Obeissance.accepte(s), s);
        for (String s : new String[]{"C'est un ordre !", "Obéis !", "J'insiste", "Fais-le !"}) assertTrue(Obeissance.insiste(s), s);
        assertFalse(Obeissance.insiste("Au travail"));
    }

    @Test
    void moral() {
        assertEquals(100, Obeissance.moral(95, Obeissance.PAIE));
        assertEquals(0, Obeissance.moral(5, Obeissance.RETARD));
        assertEquals(21, Obeissance.versLeRepos(20));
        assertEquals(69, Obeissance.versLeRepos(70));
        assertEquals(60, Obeissance.versLeRepos(60));
        assertEquals(1, Obeissance.prime(2));
        assertEquals(8, Obeissance.prime(32));
        assertEquals("paie", Obeissance.raison(1, 3, 90, true));
        assertEquals("faim", Obeissance.raison(0, 3, 90, true));
        assertEquals("coups", Obeissance.raison(0, 20, 0, true));
        assertEquals("moral", Obeissance.raison(0, 20, 0, false));
    }
}

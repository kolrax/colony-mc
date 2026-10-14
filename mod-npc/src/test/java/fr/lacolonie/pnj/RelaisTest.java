package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** « Va me chercher 64 fers », dit au bûcheron (cahier 14.1) : la partie sans le jeu ({@link Relais}). */
class RelaisTest {

    @Test
    void laCommandeDUnAutreMetierEstGardeeAvecSaQuantite() {
        Ordres.Lecture l = Ordres.lire("Va me chercher 64 fers.", "bucheron");
        assertTrue(l.missions.isEmpty());
        assertEquals(Map.of("mineur", new Ordres.Mission("fer", "quantite", 64)), l.pourAutres);
        assertEquals(new Ordres.Mission("morue", "quantite", 10), Ordres.lire("Ramène dix morues", "mineur").pourAutres.get("pecheur"));
        assertEquals(new Ordres.Mission("tout", "stock", 32), Ordres.lire("Garde 32 bûches en stock", "pecheur").pourAutres.get("bucheron"));
        // La sienne reste une mission ; l'autre part au relais.
        Ordres.Lecture deux = Ordres.lire("Ramène 10 fers et 20 bûches", "bucheron");
        assertEquals(List.of(new Ordres.Mission("tout", "quantite", 20)), deux.missions);
        assertEquals(new Ordres.Mission("fer", "quantite", 10), deux.pourAutres.get("mineur"));
        // Une règle n'est pas une commande à porter, ni son propre métier, ni le pain du fermier.
        assertTrue(Ordres.lire("S'il pleut, ramène du fer", "bucheron").pourAutres.isEmpty());
        assertTrue(Ordres.lire("Va me chercher 64 fers", "mineur").pourAutres.isEmpty());
        assertTrue(Ordres.lire("Fais du pain", "fermier").pourAutres.isEmpty());
    }

    @Test
    void quiFournitQuoi() {
        assertEquals(List.of("mineur"), Relais.fournisseurs("mineur", "fer", "bucheron"));
        assertEquals(List.of("eleveur", "chasseur"), Relais.fournisseurs("eleveur", "viande", "mineur"));
        assertEquals(List.of("chasseur"), Relais.fournisseurs("eleveur", "viande", "eleveur"));
        assertEquals(List.of("pecheur"), Relais.fournisseurs("pecheur", "poisson", "mineur")); // le cuisinier ne pêche pas
        assertEquals(List.of("cuisinier"), Relais.fournisseurs("cuisinier", "pain", "mineur"));
    }

    @Test
    void lePlusProcheDuPremierMetier() {
        List<String> f = List.of("eleveur", "chasseur");
        List<Relais.Candidat> c = List.of(new Relais.Candidat("chasseur", 4), new Relais.Candidat("eleveur", 900), new Relais.Candidat("eleveur", 100));
        assertEquals(2, Relais.choisir(f, c));
        assertEquals(0, Relais.choisir(f, List.of(new Relais.Candidat("chasseur", 4), new Relais.Candidat("mineur", 1))));
        assertEquals(-1, Relais.choisir(List.of("mineur"), List.of(new Relais.Candidat("guerrier", 1))));
    }

    @Test
    void ouiOuNon() {
        for (String oui : List.of("oui", "Oui chef", "vas-y", "Va le prévenir", "d'accord", "Bonne idée, file !", "ok"))
            assertTrue(Relais.accepte(oui) && !Relais.refuse(oui), oui);
        for (String non : List.of("non", "Non merci", "pas la peine", "laisse tomber", "je m'en occupe"))
            assertTrue(Relais.refuse(non) && !Relais.accepte(non), non);
        assertFalse(Relais.accepte("pourquoi ?"));
        // « vas-y » ne porte pas d'autre ordre : la réponse va à la proposition.
        assertTrue(Obeissance.sansOrdre(Ordres.lire("vas-y", "bucheron")));
        assertTrue(Obeissance.sansOrdre(Ordres.lire("Oui, va le prévenir", "bucheron")));
    }

    @Test
    void lesMotsDeLaPhrase() {
        assertEquals("Bertrand le mineur", Relais.qui("Bertrand", "Mineur"));
        assertEquals("Aude l'éleveur", Relais.qui("Aude", "Éleveur"));
        assertEquals("zinc", Relais.objet("Zinc (Create)"));
        assertEquals("Personne ici ne mine, chef. Il faudrait embaucher un mineur.",
                Messages.remplir(Messages.CORPS.get("relais_personne")[0], Relais.personne("mineur"), "mineur", "fer"));
        assertEquals("C'est le travail de Bertrand le mineur, chef. Je vais le prévenir ?",
                Messages.remplir(Messages.CORPS.get("relais_propose")[0], "fer", Relais.qui("Bertrand", "Mineur")));
        for (String m : Ordres.NOMS.keySet()) assertFalse(Relais.personne(m).contains("ce travail"), m);
    }
}

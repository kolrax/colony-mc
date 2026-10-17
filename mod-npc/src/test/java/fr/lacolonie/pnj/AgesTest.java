package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 13, cahier 5.6, 5.7 et 4.2 : l'âge, l'enfance, l'apprentissage, la vieillesse et les naissances ({@link Ages}). */
class AgesTest {
    static final Ages.Reglage R = new Ages.Reglage();

    @Test
    void lEnfantGranditVitePuisAuPasDesAdultes() {
        // Un enfant : un an tous les 4 jours du jeu ; seize ans en 64 jours du jeu (un peu plus de 21 h réelles).
        assertEquals(1, Ages.avancer(0, 4 * Ages.JOUR, R), 1e-9);
        assertEquals(12, Ages.avancer(0, 48 * Ages.JOUR, R), 1e-9, "douze ans en 48 jours du jeu : 16 h réelles");
        // Il passe 16 ans en route : le reste au pas des adultes (72 jours du jeu par an).
        assertEquals(16 + 36.0 / 72, Ages.avancer(15, (4 + 36) * Ages.JOUR, R), 1e-9);
        // Un adulte : un an par jour réel (72 jours du jeu, 1 728 000 ticks).
        assertEquals(31, Ages.avancer(30, 72 * Ages.JOUR, R), 1e-9);
        assertEquals(30, Ages.avancer(30, 0, R), 1e-9);
        assertEquals(30, Ages.avancer(30, -5, R), 1e-9, "le temps qui recule ne rajeunit pas");
        assertEquals(34, Ages.ans(34.9));
        assertEquals("quelques mois", Ages.ansTexte(0.4));
        assertEquals("un an", Ages.ansTexte(1.2));
        assertEquals("34 ans", Ages.ansTexte(34.9));
    }

    @Test
    void lesEtapesDeLaVie() {
        assertEquals(Ages.Etape.ENFANT, Ages.etape(11.99));
        assertEquals(Ages.Etape.APPRENTI, Ages.etape(12));
        assertEquals(Ages.Etape.ADULTE, Ages.etape(16));
        assertEquals(Ages.Etape.VIEUX, Ages.etape(60));
        assertEquals(12, Ages.passage(11.9, 12.1));
        assertEquals(16, Ages.passage(11.9, 16.5), "le plus haut seuil franchi");
        assertEquals(70, Ages.passage(69.99, 70));
        assertEquals(-1, Ages.passage(30, 31));
        // Relecture : tous les seuils franchis d'un coup, dans l'ordre (sinon l'apprenti de 11 à 16 ans restait sans paie).
        assertEquals(List.of(12, 16), Ages.passages(11.9, 16.2));
        assertEquals(List.of(60, 70), Ages.passages(59, 71));
        assertEquals(List.of(), Ages.passages(30, 31));
    }

    @Test
    void tailleOuvrageEtPaieSelonLAge() {
        assertEquals(0.5, Ages.echelle(0), 1e-9);
        assertEquals(0.75, Ages.echelle(8), 1e-9);
        assertEquals(1, Ages.echelle(16), 1e-9);
        assertEquals(1, Ages.echelle(80), 1e-9);
        assertEquals(0.5, Ages.vitesse(13), 1e-9, "l'apprenti, à demi");
        assertTrue(Ages.vitesse(5) > 0, "jamais 0 : on divise par elle");
        assertEquals(1, Ages.vitesse(30), 1e-9);
        assertEquals(0.85, Ages.vitesse(60), 1e-9);
        assertEquals(0.7, Ages.vitesse(75), 1e-9);
        assertEquals(1, Ages.vitesse(-1), 1e-9, "âge inconnu : comme avant");
        assertEquals(0, Ages.vieEnMoins(59));
        assertEquals(2, Ages.vieEnMoins(60));
        assertEquals(4, Ages.vieEnMoins(70));
        assertEquals(0, Ages.paie(8), 1e-9, "l'enfant n'est pas payé");
        assertEquals(0.5, Ages.paie(14), 1e-9);
        assertEquals(1, Ages.paie(16), 1e-9);
        // L'apprenti apprend une fois et demie plus vite : 2 points une minute sur deux.
        assertEquals(3, Ages.experience(13, 10) + Ages.experience(13, 11));
        assertEquals(2, Ages.experience(30, 10) + Ages.experience(30, 11));
    }

    @Test
    void ilSEteintAuReposDePreference() {
        assertFalse(Ages.fin(80, 85, true));
        assertTrue(Ages.fin(85, 85, true), "son heure, au repos");
        assertFalse(Ages.fin(85.05, 85, false), "pas en plein ouvrage, si l'on peut attendre le soir");
        assertTrue(Ages.fin(85.1, 85, false), "au-delà, quoi qu'il fasse");
        assertFalse(Ages.fin(85, 0, true), "pas d'âge de fin : jamais");
        Ages.Reglage r = Ages.Reglage.valide(new Ages.Reglage());
        assertTrue(r.fin_min >= 75 && r.fin_max <= 92 && r.fin_min <= r.fin_max);
        Ages.Reglage fou = new Ages.Reglage();
        fou.fin_min = 3;
        fou.fin_max = 1;
        fou.naissances_par_jour = 7;
        fou.jours_par_an_enfant = Double.NaN;
        Ages.Reglage.valide(fou);
        assertTrue(fou.fin_min > Ages.ADULTE && fou.fin_max >= fou.fin_min && fou.naissances_par_jour == 1 && fou.jours_par_an_enfant == 4);
    }

    @Test
    void lesNaissancesSontRaresEtPermises() {
        // Par minute, une chance qui donne la chance du jour en vingt minutes.
        double m = Ages.chanceParMinute(0.11);
        assertEquals(0.11, 1 - Math.pow(1 - m, 20), 1e-9);
        assertEquals(1, Ages.chanceParMinute(1));
        assertEquals(0, Ages.chanceParMinute(0));
        // Qui porte l'enfant : la femme ; deux femmes, la première ; deux hommes, personne.
        String[] p = {"Raoul", "Agnès", "Thibaut", "Isabeau"}; // comme Travailleur.PRENOMS : un homme, une femme
        assertTrue(Ages.femme("Agnès", p));
        assertFalse(Ages.femme("Raoul", p));
        assertFalse(Ages.femme("Inconnu", p));
        assertTrue(Ages.porte(true, false, false));
        assertFalse(Ages.porte(false, true, true));
        assertTrue(Ages.porte(true, true, true));
        assertFalse(Ages.porte(true, true, false));
        assertFalse(Ages.porte(false, false, true));
        Ages.Couple ok = new Ages.Couple(true, 70, 60, 30, true, 0, 3, 5, 30);
        assertTrue(Ages.naissancePossible(ok));
        assertFalse(Ages.naissancePossible(new Ages.Couple(false, 70, 60, 30, true, 0, 3, 5, 30)), "pas au travail");
        assertFalse(Ages.naissancePossible(new Ages.Couple(true, 30, 60, 30, true, 0, 3, 5, 30)), "moral trop bas");
        assertFalse(Ages.naissancePossible(new Ages.Couple(true, 70, 60, 50, true, 0, 3, 5, 30)), "trop âgée");
        assertFalse(Ages.naissancePossible(new Ages.Couple(true, 70, 60, 30, false, 0, 3, 5, 30)), "sans toit");
        assertFalse(Ages.naissancePossible(new Ages.Couple(true, 70, 60, 30, true, 3, 3, 5, 30)), "trois enfants déjà");
        assertFalse(Ages.naissancePossible(new Ages.Couple(true, 70, 60, 30, true, 0, 3, 30, 30)), "la colonie au plafond");
    }

    @Test
    void ceQuIlTientDeSesParents() {
        assertEquals("mineur", Ages.metierHerite("mineur", "bucheron", 0.2));
        assertEquals("bucheron", Ages.metierHerite("mineur", "bucheron", 0.7));
        assertNull(Ages.talentHerite(List.of("habile"), 0.6, 0), "une chance sur deux");
        assertNull(Ages.talentHerite(List.of(), 0.1, 0), "rien à hériter");
        assertEquals("robuste", Ages.talentHerite(List.of("habile", "robuste"), 0.1, 0.9));
        assertTrue(Ages.don("habile").contains("main vive"));
        assertEquals("", Ages.don(null));
        // La famille en mémoire, et en mots.
        List<Ages.Proche> l = List.of(new Ages.Proche("a-1", "Raoul"), new Ages.Proche("b-2", "Agnès"));
        assertEquals(l, Ages.lire(Ages.texte(l)));
        assertTrue(Ages.parmi(Ages.texte(l), "b-2"));
        assertFalse(Ages.parmi(Ages.texte(l), "c-3"));
        assertEquals("Raoul et Agnès", Ages.noms(l));
        assertTrue(Ages.lire("abîmé;;x:").isEmpty(), "mémoire abîmée ignorée");
        // Relecture : parent et enfant, frères et sœurs ne se mettent jamais en couple ; deux inconnus, si.
        String parents = Ages.texte(l);
        assertTrue(Ages.proches("e-1", parents, "", "a-1", "", "e-1:Thibaut"), "parent et enfant");
        assertTrue(Ages.proches("a-1", "", "e-1:Thibaut", "e-1", parents, ""), "dans l'autre sens");
        assertTrue(Ages.proches("e-1", parents, "", "e-2", "b-2:Agnès", ""), "un parent en commun");
        assertFalse(Ages.proches("e-1", parents, "", "x-9", "", ""), "deux inconnus");
        assertFalse(Ages.proches("e-1", "", "", "e-2", "", ""), "sans famille connue");
    }

    @Test
    void lesQuestionsEtLApprentissage() {
        assertEquals(Ages.Question.AGE, Ages.question(Ordres.normaliser("Quel âge as-tu ?")));
        assertEquals(Ages.Question.AGE, Ages.question(Ordres.normaliser("Tu as quel âge, Thibaut ?")));
        assertEquals(Ages.Question.PARENTS, Ages.question(Ordres.normaliser("Qui sont tes parents ?")));
        assertEquals(Ages.Question.ENFANTS, Ages.question(Ordres.normaliser("As-tu des enfants ?")));
        assertNull(Ages.question(Ordres.normaliser("Va miner du fer")));
        assertNull(Ages.question(Ordres.normaliser("Occupe-toi des enfants")), "pas une question");
        Map<String, List<String[]>> noms = Ordres.NOMS_METIERS;
        assertEquals("bucheron", Ages.metierAppris(Ordres.normaliser("Apprends le métier de bûcheron"), noms));
        assertEquals("fermier", Ages.metierAppris(Ordres.normaliser("Thibaut, tu seras fermière"), noms));
        assertNull(Ages.metierAppris(Ordres.normaliser("Va te former"), noms), "la formation payée, pas l'apprentissage");
        assertNull(Ages.metierAppris(Ordres.normaliser("Va couper du bois, bûcheron"), noms));
    }

    @Test
    void lesRepliquesDeFamilleSeLisent() {
        var banque = Vie.lire(Repertoire.lignes("/famille.txt"));
        for (String cle : List.of("naissance", "apprenti_debut", "adulte", "vieux", "tres_vieux", "adieu", "age", "age_enfant", "age_vieux", "parents",
                "parents_aucun", "enfants", "enfants_aucun", "enfant_ordre", "enfant_metier", "apprenti_metier", "enfant_peur", "enfant_jeu"))
            assertTrue(banque.containsKey(cle) && banque.get(cle).size() >= 3, cle);
    }
}

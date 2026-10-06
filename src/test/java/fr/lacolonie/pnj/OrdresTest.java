package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Ordres.Intention;
import fr.lacolonie.pnj.Ordres.Mission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrdresTest {
    static Ordres.Lecture lire(String texte, String metier) { return Ordres.lire(texte, metier); }

    static void missions(String texte, String metier, Mission... attendues) {
        assertEquals(List.of(attendues), lire(texte, metier).missions, texte);
    }

    static void intention(String texte, Intention i) {
        assertTrue(lire(texte, "mineur").intentions.contains(i), texte + " → " + lire(texte, "mineur").intentions);
    }

    static void pasIntention(String texte, Intention i) {
        assertFalse(lire(texte, "mineur").intentions.contains(i), texte + " → " + lire(texte, "mineur").intentions);
    }

    @Test
    void lExempleDeKolrax() {
        missions("Ramener 10 fers et 1 diamant", "mineur", new Mission("fer", "quantite", 10), new Mission("diamant", "quantite", 1));
    }

    @Test
    void quantitesEnLettresEtPaquets() {
        missions("ramène dix fers", "mineur", new Mission("fer", "quantite", 10));
        missions("Mine-moi vingt-deux charbons", "mineur", new Mission("charbon", "quantite", 22));
        missions("il me faut quatre-vingt-dix pierres", "mineur", new Mission("pierre", "quantite", 90));
        missions("Rapporte une pile de cuivre", "mineur", new Mission("cuivre", "quantite", 64));
        missions("rapporte deux piles de cuivre", "mineur", new Mission("cuivre", "quantite", 128));
        missions("une douzaine de diamants", "mineur", new Mission("diamant", "quantite", 12));
        missions("ramène un diamant", "mineur", new Mission("diamant", "quantite", 1));
        missions("Va chercher du fer x20", "mineur", new Mission("fer", "quantite", 20));
        missions("Ramène soixante et onze fers", "mineur", new Mission("fer", "quantite", 71));
        missions("fer 20 diamant 5", "mineur", new Mission("fer", "quantite", 20), new Mission("diamant", "quantite", 5));
    }

    @Test
    void stockOuQuantite() {
        missions("Garde toujours 32 bûches de bouleau en stock", "bucheron", new Mission("bouleau", "stock", 32));
        missions("Maintiens un stock de 64 charbons au dépôt", "mineur", new Mission("charbon", "stock", 64));
        missions("coupe 16 bûches de chêne", "bucheron", new Mission("chene", "quantite", 16));
        missions("Du chêne noir, 40, en permanence", "bucheron", new Mission("chene_noir", "stock", 40));
        missions("ramène du bois", "bucheron", new Mission("tout", "quantite", 32));
        missions("je veux du bois de la jungle", "bucheron", new Mission("jungle", "quantite", 32));
        missions("Des rondins d'acacia, s'il te plaît : 20", "bucheron", new Mission("acacia", "quantite", 20));
    }

    @Test
    void plusieursMissionsALaSuite() {
        missions("Mine 10 charbons, puis 5 fers, ensuite 2 diamants", "mineur",
                new Mission("charbon", "quantite", 10), new Mission("fer", "quantite", 5), new Mission("diamant", "quantite", 2));
        missions("Ramène 10 fers et garde 32 charbons en stock", "mineur",
                new Mission("fer", "quantite", 10), new Mission("charbon", "stock", 32));
    }

    @Test
    void lOrNEstPasLaConjonction() {
        missions("Ramène 3 or", "mineur", new Mission("or", "quantite", 3));
        missions("Il me faut de l'or", "mineur", new Mission("or", "quantite", 32));
        missions("Or, il me faut du fer", "mineur", new Mission("fer", "quantite", 32));
        missions("des lingots d'or, 12", "mineur", new Mission("or", "quantite", 12));
    }

    @Test
    void horsDeSonMetier() {
        Ordres.Lecture l = lire("Coupe-moi 20 bûches de bouleau", "mineur");
        assertTrue(l.missions.isEmpty());
        assertEquals("bouleau", l.horsMetier.get("bucheron"));
    }

    @Test
    void ajouterOuRemplacer() {
        assertTrue(lire("Ramène aussi 5 diamants", "mineur").ajouter);
        assertTrue(lire("En plus, 10 fers", "mineur").ajouter);
        assertFalse(lire("Ramène 5 diamants", "mineur").ajouter);
    }

    @Test
    void ordresDuGuerrier() {
        missions("Fais la ronde", "guerrier", new Mission("ronde", "stock", 64));
        missions("Garde le poste", "guerrier", new Mission("garder", "stock", 64));
        missions("Escorte-moi", "guerrier", new Mission("escorter", "stock", 64));
        assertFalse(lire("Escorte-moi", "guerrier").intentions.contains(Intention.SUIVRE));
    }

    @Test
    void deplacements() {
        intention("Suis-moi !", Intention.SUIVRE);
        intention("viens avec moi", Intention.SUIVRE);
        intention("Arrête de me suivre", Intention.ARRETER_SUIVRE);
        pasIntention("Arrête de me suivre", Intention.SUIVRE);
        pasIntention("Arrête de me suivre", Intention.ATTENDRE);
        intention("Attends ici", Intention.ATTENDRE);
        intention("ne bouge pas", Intention.ATTENDRE);
        intention("Au travail !", Intention.TRAVAIL);
        intention("retourne bosser", Intention.TRAVAIL);
        intention("Rentre au poste", Intention.RENTRER);
        intention("reviens", Intention.RENTRER);
        pasIntention("reviens", Intention.SUIVRE);
        pasIntention("Je suis ton chef", Intention.SUIVRE);
    }

    @Test
    void questionsEtPolitesse() {
        intention("Comment ça va ?", Intention.ETAT);
        intention("Où en es-tu ?", Intention.AVANCEMENT);
        intention("tu fais quoi", Intention.AVANCEMENT);
        intention("Pourquoi tu ne travailles pas ?", Intention.POURQUOI);
        pasIntention("Pourquoi tu ne travailles pas ?", Intention.TRAVAIL);
        intention("Montre-moi ton sac", Intention.SAC);
        intention("Tu es viré.", Intention.RENVOYER);
        intention("oui", Intention.OUI);
        intention("Bonjour Philippa", Intention.SALUT);
        intention("Merci, beau travail", Intention.MERCI);
        intention("aide", Intention.AIDE);
        assertTrue(lire("blablabla", "mineur").vide());
    }

    @Test
    void horairesEtExpeditions() {
        Ordres.Lecture l = lire("Travaille de 8 h à 20 h", "mineur");
        assertEquals(8, l.debut);
        assertEquals(20, l.fin);
        assertTrue(l.missions.isEmpty(), "les heures ne sont pas des quantités");
        l = lire("Tu commences à 7h et tu finis à 19h", "mineur");
        assertEquals(7, l.debut);
        assertEquals(19, l.fin);
        l = lire("Travaille toute la journée", "mineur");
        assertEquals(0, l.debut);
        assertEquals(0, l.fin);
        assertEquals(5, lire("Tu peux partir en expédition 5 jours", "bucheron").jours);
        assertEquals(10, lire("expéditions de dix jours max", "bucheron").jours);
    }
}

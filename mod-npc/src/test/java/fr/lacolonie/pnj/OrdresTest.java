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

    /** Cahier 3.2 : « trouve-moi du diamant » est une commande de minerai, pas seulement « où vois-tu du minerai ? ». */
    @Test
    void chercherUnMineraiPrecis() {
        missions("Trouve-moi du diamant", "mineur", new Mission("diamant", "quantite", 32));
        missions("cherche du diamant", "mineur", new Mission("diamant", "quantite", 32));
        missions("va chercher du fer", "mineur", new Mission("fer", "quantite", 32));
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

    // --- À qui le chef parle -----------------------------------------------------------------------

    static final List<String> PRENOMS = List.of("Bertrand", "Raoul", "Bérengère", "Blanche", "Enguerrand");
    static final List<String> METIERS = List.of("mineur", "guerrier", "mineur", "bucheron", "bucheron");

    static Ordres.Adresse adr(String texte) { return Ordres.adresse(texte, PRENOMS, METIERS); }

    @Test
    void appelesParLeurPrenom() {
        assertEquals(java.util.Set.of(1), adr("Raoul, va protéger la base").appeles());
        assertEquals(java.util.Set.of(1), adr("Raoul va protéger la base").appeles());
        assertEquals(java.util.Set.of(1), adr("Viens ici, Raoul.").appeles());
        assertEquals(java.util.Set.of(1), adr("Merci Raoul !").appeles());
        assertEquals(java.util.Set.of(0, 1), adr("Hé Bertrand et Raoul, venez par ici").appeles());
        assertEquals(java.util.Set.of(0, 1), adr("Bertrand, Raoul, avec moi").appeles());
        assertEquals(java.util.Set.of(2), adr("Bérangère, rentre au poste").appeles(), "Whisper écrit mal les prénoms");
        assertEquals(java.util.Set.of(4), adr("Enguerran, coupe du bouleau").appeles());
    }

    @Test
    void citesSansEtreAppeles() {
        Ordres.Adresse a = adr("Va aider Raoul à la mine");
        assertTrue(a.appeles().isEmpty());
        assertEquals(java.util.Set.of(1), a.cites());
        assertTrue(adr("Raoul est où ?").appeles().isEmpty());
        assertTrue(adr("Ramène-moi de la pierre blanche").appeles().isEmpty(), "« blanche » n'est pas Blanche");
        assertFalse(adr("Ramène-moi de la pierre blanche").explicite());
        assertTrue(adr("Ramène dix fers").appeles().isEmpty() && adr("Ramène dix fers").cites().isEmpty());
    }

    @Test
    void groupesEtExclus() {
        assertTrue(adr("Les gars, suivez-moi").tous());
        assertTrue(adr("On bouge, tout le monde !").tous(), "« tout le monde » placé comme un appel");
        assertFalse(adr("Les gars sont partis à la mine.").tous(), "sujet d'un verbe : on parle d'eux");
        assertFalse(adr("Niels, viens voir mes ouvriers.").tous(), "complément de « voir » : ce n'est pas un appel");
        assertTrue(adr("Allez hop, au travail tout le monde !").tous());
        assertTrue(adr("Tout le monde au travail !").tous());
        assertTrue(adr("Rentrez au poste, vous tous").tous());
        Ordres.Adresse a = adr("Tout le monde sauf Raoul, rentrez au poste");
        assertTrue(a.tous());
        assertEquals(java.util.Set.of(1), a.exclus());
        assertTrue(a.appeles().isEmpty());
        assertEquals(java.util.Set.of(0, 1), adr("Tous à part Bertrand et Raoul, au travail").exclus());
        assertFalse(adr("Suis-moi").tous());
        assertTrue(adr("Suivez-moi").pluriel());
        assertFalse(adr("Suis-moi").pluriel());
    }

    @Test
    void parLeurMetier() {
        assertEquals(java.util.Set.of("mineur"), adr("Les mineurs, rentrez").metiersTous());
        assertEquals(java.util.Set.of("guerrier"), adr("Le guerrier, viens avec moi").metiersUn());
        assertEquals(java.util.Set.of("bucheron"), adr("Bûcherons, coupez du chêne").metiersTous());
        assertEquals(java.util.Set.of("guerrier"), adr("Les gardes, surveillez le poste").metiersTous());
        assertTrue(adr("Garde 32 charbons au dépôt").metiersUn().isEmpty(), "« garde » est ici un verbe");
        assertTrue(adr("Va aider le mineur").metiersUn().isEmpty());
    }

    @Test
    void demandeSansDestinataire() {
        Ordres.Adresse a = adr("Va protéger la base");
        assertFalse(a.explicite());
        assertTrue(a.demande());
        assertEquals(List.of(new Mission("garder", "stock", 64)), lire("Va protéger la base", "guerrier").missions);
        assertTrue(lire("Va protéger la base", "mineur").missions.isEmpty());
        assertTrue(adr("Il faudrait que quelqu'un défende l'entrée").demande());
        assertFalse(adr("Bon, je crois que je vais manger").demande());
    }

    @Test
    void remarquesQuiNeLeurSontPasAdressees() {
        for (String p : List.of("C'est vraiment un chômeur ce mec.", "Il est lent, Raoul.", "Bon, je mine encore un peu.", "Il garde la base, lui ?",
                "On rentre bientôt.")) {
            Ordres.Adresse a = adr(p);
            assertFalse(a.explicite() || a.demande() || a.deuxiemePersonne(), p);
        }
        assertTrue(adr("Il garde la base, lui ?").question());
        assertTrue(adr("Tu peux courir 50 blocs ?").deuxiemePersonne());
        assertTrue(adr("Tu peux courir 50 blocs ?").question());
        assertTrue(adr("Où est Raoul ?").question());
        assertTrue(adr("Mine du fer").demande());
        assertTrue(adr("Ton sac est plein ?").deuxiemePersonne());
    }
    /** « Tu as gardé un diamant ? » est une question (cahier 14.3), « garde 10 diamants » un ordre ; « pardon » des excuses, « demande pardon » un ordre. */
    @Test
    void diamantCacheEtExcuses() {
        for (String p : List.of("Tu as gardé un diamant ?", "As-tu caché des diamants ?", "Bertrand, tu n'as pas gardé un diamant pour toi ?",
                "Tu caches quelque chose ?", "Qu'est-ce que tu me caches ?", "Est-ce que tu as pris un diamant ?", "Tu caches un diamant ?")) {
            assertTrue(Ordres.diamantCache(p), p);
            Ordres.Lecture l = lire(p, "mineur");
            assertTrue(l.intentions.contains(Intention.DIAMANT) && l.missions.isEmpty() && l.faire.isEmpty() && !l.intentions.contains(Intention.SAC), p);
        }
        for (String p : List.of("Garde 10 diamants en stock", "Ramène 3 diamants", "Cache-toi !", "Tu as trouvé des diamants ?", "Montre ton sac"))
            assertFalse(Ordres.diamantCache(p), p);
        assertFalse(lire("Garde 10 diamants en stock", "mineur").missions.isEmpty());
        for (String p : List.of("Pardon, Bertrand.", "Excuse-moi.", "Désolé, je me suis emporté.", "Je te demande pardon.", "Toutes mes excuses, Raoul."))
            assertTrue(Ordres.excuses(p) && lire(p, "mineur").intentions.contains(Intention.EXCUSES), p);
        for (String p : List.of("Demande pardon !", "Excuse-toi tout de suite.", "Bonjour Raoul", "Tu es désolant"))
            assertFalse(Ordres.excuses(p), p);
    }
}

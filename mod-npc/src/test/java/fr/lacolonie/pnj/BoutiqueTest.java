package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 11 : la boutique, lue sans IA (ordres du chef, demandes des clients), l'étal, et le règlement en vraies pièces. */
class BoutiqueTest {
    static final int[] VALEURS = {1, 8, 16, 64};

    @Test
    void ouvrirEtFermer() {
        for (String s : List.of("Tiens la boutique", "Raoul, ouvre une boutique.", "Tu seras notre marchand", "Va vendre au poste", "Occupe-toi de l'étal"))
            assertEquals(Boolean.TRUE, Boutique.lire(s).ouvrir, s);
        for (String s : List.of("Ferme la boutique", "Arrête de vendre", "Ne vends plus rien", "Tu n'es plus le marchand", "Laisse l'étal"))
            assertEquals(Boolean.FALSE, Boutique.lire(s).ouvrir, s);
        // Dans le doute (négation), ou une question : rien.
        assertTrue(Boutique.lire("Ne ferme pas la boutique").rien());
        assertTrue(Boutique.lire("Tu tiens la boutique ?").rien());
        assertTrue(Boutique.lire("Va miner du fer").rien());
        assertTrue(Boutique.lire("Garde le dépôt").rien());
        assertTrue(Boutique.lire("Laisse la boutique ouverte").ouvrir == null);
        assertTrue(Boutique.lire("Ne mets pas le feu").rien());
        assertTrue(Boutique.lire("Propose-moi quelque chose").rien());
    }

    @Test
    void prixFixesParLeChef() {
        Boutique.Lu l = Boutique.lire("Vends le pain à 2 pièces et le steak à cinq pièces chacun");
        assertEquals(List.of(new Boutique.Prix("pain", 2), new Boutique.Prix("steak", 5)), l.prix);
        assertEquals(List.of(new Boutique.Prix("gigot", 4)), Boutique.lire("Fixe le prix du gigot à 4 sous").prix);
        assertEquals(List.of(new Boutique.Prix("pain", 2)), Boutique.lire("Raoul, vends le pain pour 2 pièces l'unité").prix);
        assertTrue(Boutique.lire("Vends le pain à Raoul").prix.isEmpty()); // pas un prix
    }

    @Test
    void exclusEtPermis() {
        Boutique.Lu l = Boutique.lire("Ne vends pas le fer ni l'or");
        assertEquals(List.of("fer", "or"), l.exclus);
        assertEquals(List.of("diamants"), Boutique.lire("Ne vends jamais de diamants").exclus);
        assertEquals(List.of("fer"), Boutique.lire("Retire le fer de la vente").exclus);
        assertEquals(List.of("fer"), Boutique.lire("Tu peux vendre le fer").permis);
        assertEquals(List.of("tout"), Boutique.lire("Vends tout").permis);
        assertTrue(Boutique.lire("Ne le vends pas").exclus.isEmpty()); // un pronom : on ne sait pas quoi
    }

    @Test
    void leResteSeLitCommeDhabitude() {
        Boutique.Lu l = Boutique.lire("Tiens la boutique, puis suis-moi");
        assertEquals(Boolean.TRUE, l.ouvrir);
        assertEquals("puis suis-moi", l.reste);
        // Dans Ordres : les nombres d'un prix ne sont pas une mission, et « suis-moi » reste compris.
        Ordres.Lecture o = Ordres.lire("Vends le pain à 2 pièces, et suis-moi", "cuisinier");
        assertNotNull(o.boutique);
        assertTrue(o.missions.isEmpty(), "pas de mission : " + o.missions);
        assertTrue(o.intentions.contains(Ordres.Intention.SUIVRE));
        assertFalse(Ordres.lire("Tiens la boutique", "mineur").vide());
        assertNull(Ordres.lire("Va miner du fer", "mineur").boutique);
    }

    @Test
    void demandesDesClients() {
        assertEquals(new Boutique.Demande("pains", 3), Boutique.demande("Je t'achète 3 pains"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Je veux du pain, s'il te plaît"));
        assertEquals(new Boutique.Demande("steaks", 2), Boutique.demande("Donne-moi deux steaks"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("un pain"));
        assertTrue(Boutique.demande("Qu'est-ce que tu vends ?").catalogue());
        assertTrue(Boutique.demande("Tu as du pain ?").catalogue()); // se renseigner ne coûte rien
        assertEquals(64, Boutique.demande("Je t'achète 500 pains").nombre());
    }

    @Test
    void laListeDeLEtal() {
        assertEquals("pain à 1 pièce et steak à 3 pièces", Boutique.liste(List.of(new Boutique.Article("pain", 1, 5), new Boutique.Article("steak", 3, 2))));
        List<Boutique.Article> l = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) l.add(new Boutique.Article("objet" + i, 2, 1));
        assertTrue(Boutique.liste(l).endsWith(", et 2 autres marchandises"), Boutique.liste(l));
        assertEquals("", Boutique.liste(List.of()));
    }

    static void conserve(int[] client, int[] caisse, Boutique.Reglement r, long montant) {
        for (int i = 0; i < VALEURS.length; i++) assertEquals(client[i] + caisse[i], r.client()[i] + r.caisse()[i], "pièce " + i + " créée ou perdue");
        assertEquals(Monnaie.total(VALEURS, client) - montant, Monnaie.total(VALEURS, r.client()));
        assertEquals(Monnaie.total(VALEURS, caisse) + montant, Monnaie.total(VALEURS, r.caisse()));
        for (int i = 0; i < VALEURS.length; i++) assertTrue(r.client()[i] >= 0 && r.caisse()[i] >= 0);
    }

    @Test
    void reglementAuCompteJusteOuAvecLaMonnaieDeLaCaisse() {
        // Le compte juste : 3 petites pièces sur 5, la caisse vide.
        int[] client = {5, 0, 0, 0}, caisse = {0, 0, 0, 0};
        Boutique.Reglement r = Boutique.regler(VALEURS, client, caisse, 3);
        assertArrayEquals(new int[]{2, 0, 0, 0}, r.client());
        conserve(client, caisse, r, 3);
        // Une pièce de 8 pour 3 : la caisse rend 5 avec ses propres pièces.
        client = new int[]{0, 1, 0, 0};
        caisse = new int[]{6, 0, 0, 0};
        r = Boutique.regler(VALEURS, client, caisse, 3);
        assertArrayEquals(new int[]{5, 0, 0, 0}, r.client());
        assertArrayEquals(new int[]{1, 1, 0, 0}, r.caisse());
        conserve(client, caisse, r, 3);
        // Sans monnaie dans la caisse : rien.
        assertNull(Boutique.regler(VALEURS, new int[]{0, 1, 0, 0}, new int[]{0, 0, 0, 0}, 3));
        // Pas assez : rien.
        assertNull(Boutique.regler(VALEURS, new int[]{2, 0, 0, 0}, new int[]{50, 9, 9, 9}, 3));
        // Le compte juste de préférence, même avec de grosses pièces : 8 + 1 pour 9.
        client = new int[]{1, 1, 0, 1};
        r = Boutique.regler(VALEURS, client, new int[]{0, 0, 0, 0}, 9);
        assertArrayEquals(new int[]{0, 0, 0, 1}, r.client());
        // Beaucoup de pièces, un gros montant : toujours exact.
        client = new int[]{300, 40, 20, 10};
        caisse = new int[]{7, 3, 1, 0};
        r = Boutique.regler(VALEURS, client, caisse, 1234);
        conserve(client, caisse, r, 1234);
    }

    /** Relecture (bloquant) : un client ne paie que s'il le dit clairement ; jamais en refusant, en remerciant ou en demandant le prix. */
    @Test
    void unClientNePaieJamaisSansLeVouloir() {
        for (String s : List.of("Merci pour le pain", "Non merci, pas de pain", "je veux pas de pain", "Pas de pain pour moi", "J'ai déjà du pain",
                "Ton pain est trop cher", "C'est combien le pain", "Le pain a l'air bon", "Je voudrais savoir le prix du pain ?", "je viens de manger",
                "Je ne veux plus de pain", "Tu as du pain ?", "Bonjour", "Jamais de steak"))
            assertTrue(Boutique.demande(s).catalogue(), "« " + s + " » ne doit rien acheter : " + Boutique.demande(s));
        assertEquals(new Boutique.Demande("pains", 3), Boutique.demande("3 pains"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Du pain, s'il te plaît"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Vends-moi un pain"));
        assertEquals(new Boutique.Demande("steaks", 2), Boutique.demande("Je prends 2 steaks, merci"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Je voudrais du pain"));
    }

    /** Relecture : « arrête de vendre le pain » n'exclut que le pain, sans fermer la boutique. */
    @Test
    void arreterDeVendreUnObjet() {
        Boutique.Lu l = Boutique.lire("Arrête de vendre le pain");
        assertNull(l.ouvrir);
        assertEquals(List.of("pain"), l.exclus);
        assertEquals(Boolean.FALSE, Boutique.lire("Arrête de vendre").ouvrir);
    }

    /** Relecture : ce qu'on raconte (« je tiens la boutique », « Raoul a fermé la boutique ») n'est pas un ordre. */
    @Test
    void premierePersonneEtPasseNeSontPasDesOrdres() {
        for (String s : List.of("Je tiens la boutique aujourd'hui, toi va couper du bois", "Je garde la boutique", "Raoul a fermé la boutique",
                "On ferme la boutique ce soir", "Je vends le pain à 2 pièces"))
            assertTrue(Boutique.lire(s).rien(), s);
        assertEquals("Je tiens la boutique aujourd'hui, toi va couper du bois", Boutique.lire("Je tiens la boutique aujourd'hui, toi va couper du bois").reste);
        assertEquals(Boolean.TRUE, Boutique.lire("Allez, tiens la boutique").ouvrir);
    }

    /** Relecture : seul « vends le X à N pièces » fixe un prix, celui d'une unité. */
    @Test
    void prixSeulementPourVendreUneUnite() {
        for (String s : List.of("Achète du pain pour 3 pièces", "Je t'augmente à 10 pièces", "Va acheter du fer pour 5 pièces", "Mets 20 torches pour 2 pièces"))
            assertTrue(Boutique.lire(s).prix.isEmpty(), s + " : " + Boutique.lire(s).prix);
        Boutique.Lu lot = Boutique.lire("Vends 20 torches pour 2 pièces");
        assertTrue(lot.prix.isEmpty(), "pas 2 pièces par torche : " + lot.prix);
        assertFalse(lot.rien(), "il demande le prix d'une seule");
        assertEquals(List.of(new Boutique.Prix("torche", 2)), Boutique.lire("Vends une torche à 2 pièces").prix);
    }

    /** Relecture : tenir la boutique est un ordre de travail (son obéissance en décide), et la monnaie a une borne. */
    @Test
    void ouvrirEstUnTravailEtLaMonnaieABorne() {
        assertEquals(Obeissance.Peine.NORMAL, Obeissance.peine(Ordres.lire("Tiens la boutique", "mineur"), "mineur", false).peine());
        assertEquals(Obeissance.Peine.PENIBLE, Obeissance.peine(Ordres.lire("Tiens la boutique", "mineur"), "mineur", true).peine());
        assertNull(Boutique.regler(VALEURS, new int[]{0, 0, 0, 10_000}, new int[]{0, 0, 0, 0}, 100_000));
    }

    /** 2e relecture (bloquant) : après la formule d'achat, seulement un nombre ou un article et la marchandise ; une phrase nue, seulement « 3 pains » ou « du pain ». */
    @Test
    void lAchatEstExactementUneFormuleEtUneMarchandise() {
        for (String s : List.of("je veux juste regarder le pain", "je veux voir le pain", "je voudrais goûter ton pain", "je veux le tarif du pain",
                "je veux te vendre du pain", "je veux te donner du pain", "je voudrais te rendre ton pain", "donne moi mon pain", "je prends le pain de Raoul",
                "je prends le pain mais demain", "je voudrais un pain peut-être", "j'aimerais un pain si possible", "je veux du pain gratuit",
                "donne-moi ton pain gratuitement", "sans pain", "aucun pain", "pain rassis", "mauvais pain", "beau pain", "le pain", "ton pain", "ce pain", "pain"))
            assertTrue(Boutique.demande(s).catalogue(), "« " + s + " » ne doit rien acheter : " + Boutique.demande(s));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("je veux du pain"));
        assertEquals(new Boutique.Demande("steaks", 2), Boutique.demande("donne-moi deux steaks"));
        assertEquals(new Boutique.Demande("pains", 3), Boutique.demande("3 pains"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("je t'achète un pain"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Je t'achète un pain, s'il te plaît"));
        assertEquals(new Boutique.Demande("pommes de terre", 4), Boutique.demande("Je prends 4 pommes de terre"));
        assertEquals(new Boutique.Demande("pain", 1), Boutique.demande("Je voudrais un peu de pain"));
    }

    /** 2e relecture : « vends pas le pain » exclut (sans « ne »), « vends tout sauf le fer » n'exclut que le fer et ne remet rien en vente. */
    @Test
    void vendsPasEtSauf() {
        Boutique.Lu l = Boutique.lire("Vends pas le pain");
        assertEquals(List.of("pain"), l.exclus);
        assertTrue(l.permis.isEmpty());
        l = Boutique.lire("Vends tout sauf le fer");
        assertEquals(List.of("fer"), l.exclus);
        assertTrue(l.permis.isEmpty(), "rien remis en vente : " + l.permis);
        assertTrue(Boutique.lire("Vends seulement le pain").permis.isEmpty());
    }
}

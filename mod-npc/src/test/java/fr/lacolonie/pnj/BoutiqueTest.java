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
        assertEquals(List.of(new Boutique.Prix("lingot de fer", 6)), Boutique.lire("Le lingot de fer coûte 6 pièces.").prix);
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
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatutTest {
    static Statut.Etat etat(String metier, String blocage, String commande, int fait, int nombre, boolean horsHoraires) {
        return new Statut.Etat(metier, false, false, false, false, blocage, commande != null, commande, fait, nombre, horsHoraires);
    }

    @Test
    void enCours() {
        assertEquals("⛏ Mine · 32/64 fer", Statut.enCours("mineur", "Fer", 32, 64));
        assertEquals("⛏ Mine · ?/64 fer", Statut.enCours("mineur", "Fer", -1, 64));
        assertEquals("⚔ Garde · faire la ronde (50 blocs)", Statut.enCours("guerrier", "Faire la ronde (50 blocs)", 0, 0));
        assertEquals("⛏ Mine · 0/64 fer", Statut.texte(etat("mineur", null, "Fer", 0, 64, false)));
    }

    @Test
    void bloqueAvecLaSolution() {
        assertEquals("⚠ Pas de pioche au dépôt", Statut.texte(etat("mineur", Statut.outil("pioche"), "Fer", 0, 64, false)));
        assertEquals("⚠ Sac plein, dépôt plein", Statut.texte(etat("mineur", Statut.SAC_PLEIN, "Fer", 3, 64, false)));
        assertEquals("⚠ Aucun lieu sûr pour miner ici", Statut.bloque(Statut.LIEU_MINE));
        assertEquals("⚠ Pas d'enclos", Statut.bloque(Statut.ENCLOS));
        // sans mission : un mineur est bloqué (et on dit comment faire), un bûcheron coupe quand même
        assertTrue(Statut.texte(etat("mineur", null, null, 0, 0, false)).startsWith("⚠ Aucune mission"));
        assertEquals("♣ Coupe", Statut.texte(etat("bucheron", null, null, 0, 0, false)));
    }

    @Test
    void lePlusPressantDAbord() {
        Statut.Etat aTerre = new Statut.Etat("mineur", true, true, true, false, Statut.SAC_PLEIN, true, "Fer", 1, 2, false);
        assertTrue(Statut.texte(aTerre).startsWith("⚠ À terre"));
        Statut.Etat attend = new Statut.Etat("mineur", false, false, true, false, Statut.SAC_PLEIN, true, "Fer", 1, 2, false);
        assertEquals(Statut.ATTENTE, Statut.texte(attend));
        assertEquals(Statut.HORS_HORAIRES, Statut.texte(new Statut.Etat("mineur", false, false, false, false, null, true, null, 0, 0, true)));
        assertEquals(Statut.ATTENTE, Statut.texte(new Statut.Etat("mineur", false, false, false, false, null, true, null, 0, 0, false)), "missions remplies");
    }

    @Test
    void horaires() {
        assertTrue(Statut.dansHoraires("jour", 6));
        assertFalse(Statut.dansHoraires("jour", 18));
        assertTrue(Statut.dansHoraires("nuit", 23));
        assertTrue(Statut.dansHoraires("nuit", 3));
        assertFalse(Statut.dansHoraires("nuit", 12));
        assertTrue(Statut.dansHoraires("toujours", 12));
        assertTrue(Statut.dansHoraires("8-12", 8));
        assertFalse(Statut.dansHoraires("8-12", 12));
        assertTrue(Statut.dansHoraires("22-4", 1));
        assertFalse(Statut.dansHoraires("22-4", 12));
        assertTrue(Statut.horairesValides("8-12"));
        assertFalse(Statut.horairesValides("8-8"));
        assertFalse(Statut.horairesValides("matin"));
        assertFalse(Statut.horairesValides("25-3"));
    }

    @Test
    void confirmation() {
        assertEquals("Compris : Raoul mine du fer jusqu'à 64.", Statut.confirmation("Raoul", "mineur", "Fer", true, "fois", 64, "toujours", false));
        assertEquals("Compris : Raoul mine de l'or, 10 chaque jour, le jour.", Statut.confirmation("Raoul", "mineur", "Or", true, "jour", 10, "jour", false));
        assertEquals("Compris : Jeanne récolte des carottes et en garde 32 au dépôt, ici.", Statut.confirmation("Jeanne", "fermier", "Carottes", true, "stock", 32, "toujours", true));
        assertEquals("Compris : Odon va faire la ronde (50 blocs), la nuit.", Statut.confirmation("Odon", "guerrier", "Faire la ronde (50 blocs)", false, "fois", 1, "nuit", false));
        assertEquals("Compris : Raoul mine de la pierre jusqu'à 5, de 8 h à 12 h.", Statut.confirmation("Raoul", "mineur", "Pierre", true, "fois", 5, "8-12", false));
        assertEquals("du zinc", Statut.partitif("Zinc (Create)"));
        assertEquals("du bois de la jungle", Statut.partitif("Bois de la jungle"));
        assertEquals("des œufs", Statut.partitif("Œufs"));
        assertEquals("toutes les récoltes", Statut.partitif("Toutes les récoltes"));
        assertEquals("du granite", Statut.partitif("Granite"));
    }
}

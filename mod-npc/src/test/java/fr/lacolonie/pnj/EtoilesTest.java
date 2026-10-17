package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 13, cahier 5.9 : les étoiles montent avec l'expérience ou une formation payée ({@link Etoiles}). */
class EtoilesTest {
    static final int[] PALIERS = {120, 240, 480, 960};

    @Test
    void lesPaliersMontentLentementJusquaCinq() {
        assertFalse(Etoiles.monte(1, 119, PALIERS));
        assertTrue(Etoiles.monte(1, 120, PALIERS));
        assertFalse(Etoiles.monte(2, 120, PALIERS), "chaque étoile coûte plus que la précédente");
        assertTrue(Etoiles.monte(4, 960, PALIERS));
        assertFalse(Etoiles.monte(5, 1_000_000, PALIERS), "★★★★★ est le plus haut");
        assertEquals(-1, Etoiles.seuil(5, PALIERS));
    }

    @Test
    void unPointParMinuteAuPlus() {
        assertEquals(Etoiles.minute(0), Etoiles.minute(1199));
        assertNotEquals(Etoiles.minute(1199), Etoiles.minute(1200));
    }

    @Test
    void laFormationAUnPrixSaufAuMaitre() {
        int[] prix = {16, 32, 64, 128};
        assertEquals(16, Etoiles.prix(1, prix));
        assertEquals(128, Etoiles.prix(4, prix));
        assertEquals(-1, Etoiles.prix(5, prix));
    }

    @Test
    void laHausseEstMesureeEtBornee() {
        // Arrivé ★ à 2, deux étoiles gagnées (★★★) : 2 × 1,5 = 3, pas les 8 d'un ★★★ embauché tel quel.
        assertEquals(3, Etoiles.salaire(2, 8, 2, 0.25));
        assertEquals(2, Etoiles.salaire(2, 8, 0, 0.25), "sans étoile gagnée, rien ne change");
        assertEquals(8, Etoiles.salaire(2, 8, 4, 1.0), "jamais plus qu'un ouvrier né à ce rang");
        assertEquals(2, Etoiles.salaire(2, 8, 3, Double.NaN));
    }

    @Test
    void desReglagesExtravagantsSontRepris() {
        Etoiles.Reglage r = new Etoiles.Reglage();
        r.minutes_de_travail = new int[]{1, 2};
        r.prix_formation = new int[]{-5, 10, 20, 30};
        r.minutes_formation = 0;
        r.hausse_par_etoile = 50;
        Etoiles.Reglage v = Etoiles.Reglage.valide(r);
        assertArrayEquals(PALIERS, v.minutes_de_travail);
        assertEquals(0, v.prix_formation[0]);
        assertEquals(1, v.minutes_formation);
        assertEquals(1.0, v.hausse_par_etoile);
        assertNotNull(Etoiles.Reglage.valide(null));
    }

    @Test
    void leChefDemandeUneFormation() {
        for (String s : new String[]{"Va te former", "pars en apprentissage", "Forme-toi, Gauthier", "va apprendre ton métier"})
            RepertoireTest.demande(s, "mineur", "se_former");
        RepertoireTest.demande("formez les rangs", "mineur", "en_rang"); // l'ordre militaire ne change pas
        RepertoireTest.demande("apprends-moi ton métier", "mineur", "astuce_metier");
    }
}

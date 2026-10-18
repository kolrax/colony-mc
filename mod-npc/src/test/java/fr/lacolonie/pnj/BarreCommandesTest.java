package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** La recherche de la barre de commandes ({@link BarreCommandes.Recherche#chercher}) : sur le répertoire réel. */
class BarreCommandesTest {
    static List<String> ids(String saisie, String metier) { return BarreCommandes.Recherche.chercher(saisie, metier).stream().map(BarreCommandes.Recherche.Ligne::id).toList(); }

    @Test
    void garProposeLeGardeAVousEnPremier() {
        List<BarreCommandes.Recherche.Ligne> l = BarreCommandes.Recherche.chercher("gar", "guerrier");
        assertFalse(l.isEmpty());
        assertEquals("garde_a_vous", l.get(0).id(), "le libellé qui commence par la saisie d'abord : " + l);
        assertEquals("Garde à vous", l.get(0).libelle());
        assertTrue(l.stream().anyMatch(x -> x.id().equals("garder_ici")), "« garde cette position », propre au guerrier : " + l);
    }

    @Test
    void seulementLesActionsDuMetier() {
        assertFalse(ids("gar", "mineur").contains("garder_ici"), "un mineur ne garde pas une position");
        assertTrue(ids("gar", "mineur").contains("garde_a_vous"), "le garde-à-vous est pour tous");
        for (String metier : List.of("mineur", "fermier", "guerrier"))
            for (BarreCommandes.Recherche.Ligne x : BarreCommandes.Recherche.chercher("", metier))
                assertTrue(x.pour(metier), x.id() + " pour " + metier);
    }

    @Test
    void sansAccentsNiMajusculesEtAuDebutDesMots() {
        assertEquals("garde_a_vous", ids("GÂRDE À", "mineur").get(0));
        assertEquals("garde_a_vous", ids("  garde-a-v ", "mineur").get(0));
        assertTrue(ids("vous", "mineur").contains("garde_a_vous"), "le début d'un mot au milieu de la formule");
        assertFalse(ids("arde", "mineur").contains("garde_a_vous"), "pas le milieu d'un mot");
    }

    @Test
    void huitAuPlusEtRienPourLeCharabia() {
        assertTrue(BarreCommandes.Recherche.chercher("", "mineur").size() <= BarreCommandes.Recherche.MAX);
        assertEquals(BarreCommandes.Recherche.MAX, BarreCommandes.Recherche.chercher("a", "guerrier").size());
        assertTrue(BarreCommandes.Recherche.chercher("zzqxw", "mineur").isEmpty());
    }

    @Test
    void chaqueLigneAUnLibelleEtUnIdConnu() {
        for (BarreCommandes.Recherche.Ligne l : BarreCommandes.Recherche.lignes()) {
            assertFalse(l.libelle().isBlank(), l.id());
            assertTrue(Repertoire.PAR_ID.containsKey(l.id()), l.id());
            assertTrue(l.id().length() <= 64, "le paquet porte 64 caractères : " + l.id());
        }
    }

    /** Qui, quoi : ce que chaque ordre demande en plus ; « dis à Jean que… » ne se choisit pas et quitte la barre. */
    @Test
    void lesOrdresQuiDemandentUnComplement() {
        var R = Repertoire.PAR_ID;
        assertEquals(BarreCommandes.Recherche.Besoin.QUI, BarreCommandes.Recherche.besoin(R.get("montrer_cible")));
        assertEquals(BarreCommandes.Recherche.Besoin.QUOI, BarreCommandes.Recherche.besoin(R.get("prendre_objet")));
        assertEquals(BarreCommandes.Recherche.Besoin.QUI_QUOI, BarreCommandes.Recherche.besoin(R.get("donner_cible")));
        assertEquals(BarreCommandes.Recherche.Besoin.RIEN, BarreCommandes.Recherche.besoin(R.get("attaquer_la")), "attaquer : le monstre regardé");
        assertEquals(BarreCommandes.Recherche.Besoin.RIEN, BarreCommandes.Recherche.besoin(R.get("prendre_torches")));
        assertEquals(BarreCommandes.Recherche.Besoin.RETIRE, BarreCommandes.Recherche.besoin(R.get("message")));
        assertTrue(BarreCommandes.Recherche.lignes().stream().noneMatch(l -> l.id().equals("message")), "retiré de la barre");
        assertTrue(BarreCommandes.Recherche.combien(R.get("prendre_objet")) && !BarreCommandes.Recherche.combien(R.get("tenir_objet")));
    }

    @Test
    void laPhraseCommeSiLeNomAvaitEteDit() {
        var R = Repertoire.PAR_ID;
        assertEquals("montre-moi Jean", BarreCommandes.Recherche.phrase(R.get("montrer_cible"), "Jean", ""));
        assertEquals("donne à Jean minecraft:bread", BarreCommandes.Recherche.phrase(R.get("donner_cible"), "Jean", "minecraft:bread"));
        assertEquals("montre-moi", BarreCommandes.Recherche.phrase(R.get("montrer_cible"), "", ""));
        var c = BarreCommandes.Recherche.Complement.lire("Jean|minecraft:bread|16");
        assertEquals(List.of("Jean", "minecraft:bread", 16), List.of(c.qui(), c.quoi(), c.combien()));
        assertEquals(c, BarreCommandes.Recherche.Complement.lire(c.texte()));
        assertEquals(0, BarreCommandes.Recherche.Complement.lire("").combien());
        assertEquals(64, BarreCommandes.Recherche.Complement.lire("||9999").combien());
    }
}

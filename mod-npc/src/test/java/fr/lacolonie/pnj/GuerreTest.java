package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ce que le chef dit de la guerre entre colonies (cahier 6.5), lu par {@link Ordres#lire} (voir {@link Guerre}). */
class GuerreTest {
    static Guerre.Lu lu(String s) { return Ordres.lire(s, "guerrier").guerre; }

    @Test
    void declaration() {
        for (String s : List.of("On déclare la guerre à Nielscutt.", "Je déclare la guerre à Nielscutt !", "Nous sommes en guerre contre Nielscutt.",
                "Déclare la guerre à la colonie de Nielscutt.", "C'est décidé : guerre contre Nielscutt."))
            assertEquals("Nielscutt", lu(s) == null ? null : lu(s).declare, s);
        // Le pseudo tel qu'écrit, trait d'union et souligné compris.
        assertEquals("Niels_cutt-42", lu("On déclare la guerre à Niels_cutt-42.").declare);
        // L'ordre ne laisse rien derrière lui (ni mission, ni garde du chef ou du dépôt).
        Ordres.Lecture l = Ordres.lire("On déclare la guerre à Nielscutt.", "guerrier");
        assertTrue(l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty() && l.posture == null && l.depot == null, l.texte);
    }

    @Test
    void negationsEtQuestions() {
        for (String s : List.of("Ne déclare pas la guerre à Nielscutt.", "On ne déclare jamais la guerre à Nielscutt.", "On déclare la guerre à Nielscutt ?",
                "Ne lève pas l'armée.", "Il ne faut pas attaquer la colonie de Nielscutt."))
            assertTrue(lu(s) == null || lu(s).declare == null && !lu(s).lever, s);
        // Rien sur la guerre : rien de lu (les autres ordres restent aux leurs).
        for (String s : List.of("Attaque quiconque me frappe.", "Attaque ceux qui volent le dépôt.", "Va couper du bois.", "Rentre au poste.", "Attaque ce zombie."))
            assertNull(lu(s), s);
    }

    @Test
    void paix() {
        assertEquals("Nielscutt", lu("On fait la paix avec Nielscutt.").paix);
        assertEquals("Nielscutt", lu("Paix avec Nielscutt !").paix);
        for (String s : List.of("La guerre est finie.", "Faisons la paix.", "C'est la paix, rangez vos armes.", "Fin de la guerre !"))
            assertTrue(lu(s) != null && lu(s).paixToutes, s);
        assertNull(lu("Ne fais pas la paix avec Nielscutt.") == null ? null : lu("Ne fais pas la paix avec Nielscutt.").paix);
    }

    @Test
    void leveeEtAttaque() {
        for (String s : List.of("Lève l'armée !", "Levez les troupes.", "À l'attaque !", "Aux armes !"))
            assertTrue(lu(s) != null && lu(s).lever && lu(s).cible == null, s);
        Guerre.Lu g = lu("Attaquez la colonie de Nielscutt, pillez leur dépôt et cassez leurs portes.");
        assertTrue(g.lever && g.piller && g.casser, "levée, pillage, casse");
        assertEquals("Nielscutt", g.cible);
        assertEquals("Nielscutt", lu("Lève l'armée contre Nielscutt.").cible);
        assertEquals("Nielscutt", lu("Marchez sur Nielscutt !").cible);
        // « Pillez leur dépôt » n'est pas la garde de notre dépôt.
        Ordres.Lecture l = Ordres.lire("Attaquez la colonie de Nielscutt et pillez leur dépôt.", "guerrier");
        assertNull(l.depot);
        assertNull(l.posture);
        Guerre.Lu seul = lu("À l'attaque !");
        assertFalse(seul.piller || seul.casser, "sans le dire, ni pillage ni casse");
    }

    @Test
    void retraite() {
        for (String s : List.of("Retraite !", "Repliez-vous !", "On se replie.", "Abandonnez l'assaut."))
            assertTrue(lu(s) != null && lu(s).retraite, s);
    }
}

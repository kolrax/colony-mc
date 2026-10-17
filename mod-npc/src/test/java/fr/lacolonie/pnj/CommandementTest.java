package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 14 (cahier 6.1, 6.2, 5.8) : qui d'autre commande ({@link Commandement}), et la débauche ({@link Debauche}). */
class CommandementTest {
    static Ordres.Lecture lu(String s) { return Ordres.lire(s, "bucheron"); }

    @Test
    void leChefAutoriseUnAutre() {
        for (String s : List.of("Tu peux obéir à Nielscutt.", "Nielscutt peut te donner des ordres.", "Autorise Nielscutt à vous commander.",
                "Obéis aussi à Nielscutt.", "Nielscutt a le droit de te commander", "Fais ce que Nielscutt te dit.", "Vous pouvez obéir à Nielscutt, les gars."))
            assertEquals(List.of("Nielscutt"), lu(s).commandants, s);
        assertEquals(List.of("Niels_cutt42"), lu("Niels_cutt42 peut vous donner des ordres").commandants);
        Ordres.Lecture tiret = lu("test-mock-player peut te donner des ordres.");
        assertEquals(List.of("test-mock-player"), tiret.commandants);
        assertFalse(tiret.texte.contains("test"), tiret.texte);
        // Rien d'autre n'est lu : ni le dépôt, ni une mission, ni un geste.
        Ordres.Lecture l = lu("Nielscutt peut te donner des ordres.");
        assertTrue(l.permis.isEmpty() && l.retires.isEmpty() && l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.isEmpty(), l.texte);
        // Le reste de la phrase se lit comme d'habitude.
        Ordres.Lecture m = lu("Obéis aussi à Nielscutt, et coupe du bouleau.");
        assertEquals(List.of("Nielscutt"), m.commandants);
        assertFalse(m.missions.isEmpty(), "la mission reste : " + m.texte);
    }

    @Test
    void leChefRetire() {
        for (String s : List.of("N'obéis plus à Nielscutt.", "Nielscutt n'a plus le droit de te commander.", "Nielscutt ne peut plus vous donner d'ordres.",
                "N'obéissez plus à Nielscutt", "Nielscutt n'a plus le droit de vous donner des ordres")) {
            Ordres.Lecture l = lu(s);
            assertEquals(List.of("Nielscutt"), l.destitues, s);
            assertTrue(l.commandants.isEmpty() && l.retires.isEmpty() && l.permis.isEmpty(), s + " : ni permis, ni dépôt");
        }
    }

    @Test
    void pasDeConfusionAvecLeDepot() {
        for (String s : List.of("Nielscutt peut se servir au dépôt.", "Nielscutt n'a plus le droit.", "Laisse Raoul se servir.", "Nielscutt peut prendre sa pause.",
                "Il peut te commander une épée ?", "Tu peux aller voir Nielscutt.", "Je n'obéis à personne.", "Coupe du bois et obéis.")) {
            Ordres.Lecture l = lu(s);
            assertTrue(l.commandants.isEmpty() && l.destitues.isEmpty(), s + " : " + l.commandants + l.destitues);
        }
        assertEquals(List.of("Nielscutt"), lu("Nielscutt peut se servir au dépôt.").permis);
        assertEquals(List.of("Nielscutt"), lu("Nielscutt n'a plus le droit.").retires);
        // Les deux dans la même phrase : chacun le sien.
        Ordres.Lecture l = lu("Nielscutt peut se servir au dépôt, et Raoul peut te donner des ordres.");
        assertEquals(List.of("Nielscutt"), l.permis);
        assertEquals(List.of("Raoul"), l.commandants);
    }

    @Test
    void ordresReservesAuChef() {
        // Un geste, une question : ce n'est pas un ordre de travail.
        assertFalse(Commandement.ordre(lu("Bonjour, ça va ?")));
        assertFalse(Commandement.ordre(lu("Merci !")));
        assertTrue(Commandement.ordre(lu("Coupe du bouleau.")));
        assertTrue(Commandement.ordre(lu("Rentre au poste.")));
        assertTrue(Commandement.ordre(lu("Nielscutt peut te donner des ordres.")));
        // Ce que seul le chef décide est retiré ; le travail reste.
        Ordres.Lecture l = lu("Coupe du bouleau, et Raoul peut te donner des ordres, travaille de nuit.");
        assertTrue(Commandement.reserver(l, id -> null));
        assertTrue(l.commandants.isEmpty() && l.equipe < 0 && !l.missions.isEmpty());
        Ordres.Lecture r = lu("Je te renvoie.");
        assertTrue(Commandement.reserver(r, id -> null));
        assertTrue(r.vide());
        Ordres.Lecture t = lu("Coupe du bouleau.");
        assertFalse(Commandement.reserver(t, id -> null), "un ordre de travail passe tel quel");
        // Les ouvrages durables (trajets, champs, chantiers) restent au chef.
        Ordres.Lecture tr = lu("Coupe du bouleau.");
        tr.faire.add(new Ordres.Faire("trajet_deposer", "", 0, 0, false));
        tr.faire.add(new Ordres.Faire("champ", "", 0, 0, false));
        tr.faire.add(new Ordres.Faire("construire", "", 0, 0, false));
        tr.faire.add(new Ordres.Faire("oublier_trajets", "", 0, 0, false));
        assertTrue(Commandement.reserver(tr, id -> "travail"));
        assertTrue(tr.faire.isEmpty() && !tr.missions.isEmpty(), tr.faire.toString());
        Ordres.Lecture f = lu("Va te former.");
        assertTrue(Commandement.reserver(f, Map.of("se_former", "former")::get));
        assertTrue(f.faire.isEmpty());
    }

}

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
        Ordres.Lecture f = lu("Va te former.");
        assertTrue(Commandement.reserver(f, Map.of("se_former", "former")::get));
        assertTrue(f.faire.isEmpty());
    }

    @Test
    void propositionsDeDebauche() {
        assertEquals(20, Debauche.lire("Viens travailler pour moi, je te paie 20 pièces.").pieces());
        assertEquals(30, Debauche.lire("Je te donne 30 pièces pour que tu me rejoignes").pieces());
        assertEquals(-1, Debauche.lire("Quitte ton chef, viens chez moi !").pieces());
        assertNull(Debauche.lire("Coupe du bois pour moi."));
        assertNull(Debauche.lire("Je te paie 20 pièces"));
        Debauche.Pourboire p = Debauche.pourboire("Tiens, 10 pièces : coupe du bouleau.");
        assertEquals(10, p.pieces());
        assertFalse(p.reste().contains("10"), p.reste());
        assertFalse(lu(p.reste()).missions.isEmpty(), p.reste());
        assertNull(Debauche.pourboire("Coupe du bouleau."));
        assertNull(Debauche.pourboire("Viens travailler pour moi, je te paie 20 pièces."));
    }

    static Debauche.Cas cas(long offre, int moral, String temp, int repChef, int repAutre, boolean couple, int amis) {
        return new Debauche.Cas(offre, 10, moral, temp, repChef, repAutre, couple, amis, false, false, true);
    }

    @Test
    void laDecisionDeDebauche() {
        // Un ouvrier ordinaire (moral 50, colérique, rien qui le retienne) : 30 % de plus suffisent, 20 % non.
        assertEquals(Debauche.Raison.ACCEPTE, Debauche.decider(cas(13, 50, "colerique", 0, 0, false, 0)));
        assertEquals(Debauche.Raison.PAIE, Debauche.decider(cas(12, 50, "colerique", 0, 0, false, 0)));
        // Le fourbe se laisse tenter plus vite ; le calme heureux tient bon.
        assertEquals(Debauche.Raison.ACCEPTE, Debauche.decider(cas(12, 50, "fourbe", 0, 0, false, 0)));
        assertEquals(Debauche.Raison.FIDELE, Debauche.decider(cas(15, 80, "calme", 0, 0, false, 0)));
        assertEquals(Debauche.Raison.ACCEPTE, Debauche.decider(cas(20, 80, "calme", 0, 0, false, 0)));
        // Ses liens le retiennent ; la réputation des chefs aussi.
        assertEquals(Debauche.Raison.LIENS, Debauche.decider(cas(15, 50, "colerique", 0, 0, true, 2)));
        assertEquals(Debauche.Raison.REPUTATION, Debauche.decider(cas(15, 50, "colerique", 20, -20, false, 0)));
        // Jamais moins de 10 % de plus, même au plus bas.
        assertEquals(Debauche.Raison.PAIE, Debauche.decider(cas(10, 0, "fourbe", -50, 50, false, 0)));
        assertEquals(Debauche.Raison.ACCEPTE, Debauche.decider(cas(11, 0, "fourbe", -50, 50, false, 0)));
        // Guerre, rancune, sans somme, sans place.
        assertEquals(Debauche.Raison.GUERRE, Debauche.decider(new Debauche.Cas(100, 10, 0, "fourbe", 0, 0, false, 0, true, false, true)));
        assertEquals(Debauche.Raison.RANCUNE, Debauche.decider(new Debauche.Cas(100, 10, 0, "fourbe", 0, 0, false, 0, false, true, true)));
        assertEquals(Debauche.Raison.SOMME, Debauche.decider(new Debauche.Cas(-1, 10, 0, "fourbe", 0, 0, false, 0, false, false, true)));
        assertEquals(Debauche.Raison.PLACE, Debauche.decider(new Debauche.Cas(100, 10, 0, "fourbe", 0, 0, false, 0, false, false, false)));
        // La paie d'un ordre : la moitié de son salaire au moins.
        assertTrue(Debauche.assez(5, 10));
        assertFalse(Debauche.assez(4, 10));
        assertTrue(Debauche.assez(1, 0));
    }
}

package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepertoireTest {
    /** L'action que la phrase demande sans IA (la première), ou « - ». */
    static String action(String texte, String metier) {
        List<Repertoire.Trouve> l = Repertoire.lire(texte, metier);
        return l.isEmpty() ? "-" : l.get(0).e().id() + (l.get(0).fin() ? " (fin)" : "");
    }

    static void demande(String texte, String metier, String id) { assertEquals(id, action(texte, metier), texte); }

    @Test
    void leFichierEstValable() {
        assertTrue(Repertoire.TOUTES.size() >= 300, "au moins 300 actions : " + Repertoire.TOUTES.size());
        List<String> erreurs = new ArrayList<>();
        for (Repertoire.Entree e : Repertoire.TOUTES) {
            String c = e.comportement(), a = e.arg();
            for (String m : e.metiers()) if (!Set.of("mineur", "bucheron", "guerrier", "fermier", "eleveur").contains(m)) erreurs.add(e.id() + " : métier " + m);
            if (e.dits().isEmpty()) erreurs.add(e.id() + " : aucune formule");
            switch (c) {
                case "geste" -> { if (!Repertoire.GESTES.contains(a)) erreurs.add(e.id() + " : geste " + a); }
                case "aller" -> { if (!Repertoire.LIEUX.contains(a)) erreurs.add(e.id() + " : lieu " + a); }
                case "regarder" -> { if (!Repertoire.REGARDS.contains(a)) erreurs.add(e.id() + " : regard " + a); }
                case "son" -> { if (!Repertoire.SONS.contains(a)) erreurs.add(e.id() + " : son " + a); }
                case "musique" -> { if (!Repertoire.INSTRUMENTS.contains(a)) erreurs.add(e.id() + " : instrument " + a); }
                case "donner", "jeter", "prendre", "tenir" -> { if (!Repertoire.OBJETS.contains(a)) erreurs.add(e.id() + " : objet " + a); }
                case "utiliser" -> { if (!Repertoire.BLOCS.contains(a)) erreurs.add(e.id() + " : bloc " + a); }
                case "combat" -> { if (!Repertoire.COMBATS.contains(a)) erreurs.add(e.id() + " : combat " + a); }
                case "travail" -> { if (!Repertoire.TRAVAUX.contains(a)) erreurs.add(e.id() + " : travail " + a); }
                case "info" -> { if (!Repertoire.INFOS.contains(a)) erreurs.add(e.id() + " : info " + a); }
                case "parler" -> { if (!Repertoire.PAROLES.containsKey(a) && !Repertoire.GENRES_CALCULES.contains(a)) erreurs.add(e.id() + " : genre " + a); }
                case "vitesse" -> { if (!Repertoire.VITESSES.contains(a)) erreurs.add(e.id() + " : vitesse " + a); }
                case "formation" -> { if (!Repertoire.FORMES.contains(a)) erreurs.add(e.id() + " : forme " + a); }
                case "suivre", "message" -> { if (!a.equals("cible")) erreurs.add(e.id() + " : " + c + " " + a); }
                default -> {}
            }
            String g = e.opt("geste", "");
            if (!g.isEmpty() && !Repertoire.GESTES.contains(g)) erreurs.add(e.id() + " : geste= " + g);
            String r = e.opt("regard", "");
            if (!r.isEmpty() && !Repertoire.REGARDS.contains(r)) erreurs.add(e.id() + " : regard= " + r);
            String s = e.opt("son", "");
            if (!s.isEmpty() && !Repertoire.SONS.contains(s)) erreurs.add(e.id() + " : son= " + s);
            String p = e.opt("part", "");
            if (!p.isEmpty() && !Repertoire.PARTICULES.contains(p)) erreurs.add(e.id() + " : part= " + p);
            String o = e.opt("objet", "");
            if (!o.isEmpty() && !Repertoire.OBJETS.contains(o)) erreurs.add(e.id() + " : objet= " + o);
            String puis = e.opt("puis", "");
            if (!puis.isEmpty() && !Repertoire.PAR_ID.containsKey(puis)) erreurs.add(e.id() + " : puis= " + puis);
            try { e.duree(); } catch (RuntimeException x) { erreurs.add(e.id() + " : durée " + e.opt("duree", "")); }
        }
        assertEquals(List.of(), erreurs);
    }

    @Test
    void lesExemplesDeKolrax() {
        demande("Garde à vous !", "guerrier", "garde_a_vous");
        demande("Garde-à-vous, Raoul !", "guerrier", "garde_a_vous");
        demande("Repos !", "guerrier", "annuler");
        demande("Arrête-toi là où tu es, et plus un geste", "mineur", "arret");
    }

    @Test
    void desGestesEtDesPostures() {
        demande("Assieds-toi", "mineur", "assis");
        demande("Asseyez-vous les gars", "mineur", "assis");
        demande("Danse !", "mineur", "danser");
        demande("Dansez !", "mineur", "danser");
        demande("Mets-toi à genoux", "mineur", "a_genoux");
        demande("Fais le cochon", "bucheron", "imiter_cochon");
        demande("Imite la poule", "bucheron", "imiter_poule");
        demande("Applaudissez !", "mineur", "applaudir");
        demande("Raconte une blague", "mineur", "blague");
        demande("Chante-moi une chanson", "mineur", "chanter");
        demande("Joue de la flûte", "mineur", "flute");
        demande("Mets-toi derrière moi", "mineur", "derriere");
        demande("Va là-bas", "mineur", "aller_la");
        demande("En rang !", "guerrier", "en_rang");
        demande("Tais-toi", "mineur", "silence");
        demande("Ferme la porte", "mineur", "ouvrir_porte");
        demande("Ferme-la !", "mineur", "silence");
        demande("Quelle heure est-il ?", "mineur", "heure");
        demande("Où est le poste ?", "mineur", "ou_poste");
        demande("Donne-moi ton pain", "mineur", "donner_nourriture");
        demande("Fais des pompes", "guerrier", "pompes");
    }

    @Test
    void lesActionsDeMetier() {
        demande("Abats cet arbre", "bucheron", "abattre_la");
        demande("Abats cet arbre", "mineur", "-");
        demande("Attaque ce zombie", "guerrier", "attaquer_la");
        demande("Lève ton bouclier", "guerrier", "bouclier");
        demande("Pose des torches", "mineur", "torches");
        demande("Creuse un tunnel", "mineur", "tunnel");
    }

    @Test
    void quelquUnALaPlaceDuArobase() {
        demande("Protège Raoul", "guerrier", "proteger_cible");
        assertNotEquals("proteger_cible", action("Protège la base", "guerrier"));
        assertNotEquals("proteger_cible", action("Protège le poste", "guerrier"));
        demande("Suis Bertrand", "mineur", "suivre_cible");
        demande("Suis-le", "mineur", "suivre_cible");
        demande("Suis-moi", "mineur", "-");
        demande("Où est Raoul ?", "mineur", "ou_cible");
        demande("Va voir Odon", "mineur", "aller_voir_cible");
        demande("Dis à Raoul que le repas est prêt", "mineur", "message");
    }

    @Test
    void arreterOuRefuser() {
        demande("Arrête de danser", "mineur", "danser (fin)");
        demande("Ne danse plus", "mineur", "danser (fin)");
        demande("Ne danse pas", "mineur", "-");
        demande("Il danse bien, Raoul", "mineur", "-");
        demande("Je chante faux", "mineur", "-");
    }

    @Test
    void desMotsCommunsNeSuffisentPas() {
        assertNotEquals("imiter_poule", action("Va chercher la poule", "mineur"));
        assertEquals("-", action("Ramène-moi dix fers", "mineur"));
        assertEquals("-", action("Suis-moi", "mineur"));
        assertEquals("-", action("Au travail", "mineur"));
    }

    @Test
    void commandeEtMetiersConcernes() {
        assertTrue(Repertoire.commande("Garde à vous !"));
        assertTrue(Repertoire.commande("Les gars, en rang !"));
        assertFalse(Repertoire.commande("Il est lent, Raoul."));
        assertEquals(Set.of("bucheron"), Repertoire.metiersConcernes("Abats cet arbre"));
        assertEquals(Set.of("guerrier"), Repertoire.metiersConcernes("Lève ton bouclier"));
        assertEquals(Set.of(), Repertoire.metiersConcernes("Danse !"));
    }

    /**
     * Le jeu d'essai (phrases écrites à part, comme un joueur les dirait) : sans IA, la bonne action (ou rien) ; avec
     * l'IA, la bonne action parmi celles qu'on lui propose. Les mesures s'affichent ; en dessous des seuils, le test échoue.
     */
    @Test
    void leJeuDEssai() throws Exception {
        List<String> lignes = java.nio.file.Files.readAllLines(java.nio.file.Path.of(RepertoireTest.class.getResource("/repertoire_essais.txt").toURI()));
        int pos = 0, bonsPos = 0, neg = 0, bonsNeg = 0, rappel = 0;
        List<String> erreurs = new ArrayList<>();
        for (String l : lignes) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] c = l.split("\\|");
            String phrase = c[0].strip(), metier = c[1].strip(), attendu = c[2].strip(), vu = action(phrase, metier);
            if (attendu.equals("-")) {
                neg++;
                if (vu.equals("-")) bonsNeg++; else erreurs.add("déclenche à tort : " + phrase + " → " + vu);
            } else {
                pos++;
                if (vu.equals(attendu)) bonsPos++; else erreurs.add(phrase + " → " + vu + " (attendu " + attendu + ")");
                String id = attendu.replace(" (fin)", "");
                if (Repertoire.candidats(phrase, List.of(metier), 14).stream().anyMatch(e -> e.id().equals(id))) rappel++;
                else erreurs.add("pas proposé à l'IA : " + phrase + " (" + id + ")");
            }
        }
        System.out.printf("Répertoire : %d actions. Sans IA : %d/%d justes (%.1f %%), %d/%d phrases sans action laissées tranquilles. "
                        + "Proposé à l'IA : %d/%d (%.1f %%).%n", Repertoire.TOUTES.size(), bonsPos, pos, 100.0 * bonsPos / pos, bonsNeg, neg, rappel, pos, 100.0 * rappel / pos);
        erreurs.forEach(x -> System.out.println("  " + x));
        assertTrue(bonsPos >= pos * 0.95, "sans IA : " + bonsPos + "/" + pos);
        assertEquals(neg, bonsNeg, "aucune action déclenchée à tort");
        assertTrue(rappel >= pos * 0.98, "proposé à l'IA : " + rappel + "/" + pos);
    }

    /** Le jeu à l'aveugle (écrit après la mise au point, jamais corrigé dessus) : on l'affiche, sans seuil. */
    @Test
    void leJeuALAveugle() throws Exception {
        List<String> lignes = java.nio.file.Files.readAllLines(java.nio.file.Path.of(RepertoireTest.class.getResource("/repertoire_aveugle.txt").toURI()));
        int pos = 0, bonsPos = 0, neg = 0, bonsNeg = 0, rappel = 0, pourIA = 0;
        List<String> erreurs = new ArrayList<>();
        for (String l : lignes) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] c = l.split("\\|");
            String phrase = c[0].strip(), metier = c[1].strip(), attendu = c[2].strip(), vu = action(phrase, metier);
            boolean iaSeule = attendu.endsWith(" ?");
            attendu = attendu.replace(" ?", "");
            String id = attendu.replace(" (fin)", "");
            if (!attendu.equals("-")) {
                pourIA++;
                if (Repertoire.candidats(phrase, List.of(metier), 14).stream().anyMatch(e -> e.id().equals(id))) rappel++;
                else erreurs.add("pas proposé à l'IA : " + phrase + " (" + id + ")");
            }
            if (iaSeule) continue;
            if (attendu.equals("-")) { neg++; if (vu.equals("-")) bonsNeg++; else erreurs.add("déclenche à tort : " + phrase + " → " + vu); }
            else { pos++; if (vu.equals(attendu)) bonsPos++; else erreurs.add(phrase + " → " + vu + " (attendu " + attendu + ")"); }
        }
        System.out.printf("À L'AVEUGLE — sans IA : %d/%d justes (%.1f %%), %d/%d phrases sans action laissées tranquilles. Proposé à l'IA : %d/%d (%.1f %%).%n",
                bonsPos, pos, 100.0 * bonsPos / pos, bonsNeg, neg, rappel, pourIA, 100.0 * rappel / pourIA);
        erreurs.forEach(x -> System.out.println("  " + x));
    }

    @Test
    void lesCandidatsPourLIA() {
        List<String> c = Repertoire.candidats("Mets-toi droit comme un piquet", List.of("guerrier"), 12).stream().map(Repertoire.Entree::id).toList();
        assertTrue(c.contains("garde_a_vous"), c.toString());
        assertTrue(c.contains("hocher_tete"), "les gestes spontanés sont toujours là : " + c);
        List<String> b = Repertoire.candidats("tu pourrais couper ce grand chêne ?", List.of("bucheron"), 12).stream().map(Repertoire.Entree::id).toList();
        assertTrue(b.contains("abattre_la"), b.toString());
    }
}

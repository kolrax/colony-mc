package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;

/**
 * Le livret du chef (1.0.0) : le texte de {@code livret.txt} découpé en livre (pur Java). Première ligne
 * {@code titre: …}, deuxième {@code auteur: …}, puis les pages séparées par une ligne {@code ---}. Le livre écrit est
 * donné une seule fois à chaque joueur, quand il fait d'un bureau son poste ({@link Bureau}).
 */
final class Livret {
    private Livret() {}

    /** Les bornes d'un livre écrit du jeu : 32 caractères de titre, 100 pages, 1024 caractères par page. */
    static final int TITRE_MAX = 32, PAGES_MAX = 100, PAGE_MAX = 1024;

    record Contenu(String titre, String auteur, List<String> pages) {}

    static Contenu lire(String texte) {
        String titre = "Livret", auteur = "";
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        String[] lignes = texte.replace("\r", "").split("\n", -1);
        int debut = 0;
        for (; debut < Math.min(2, lignes.length); debut++) {
            String l = lignes[debut];
            if (l.startsWith("titre:")) titre = l.substring(6).strip();
            else if (l.startsWith("auteur:")) auteur = l.substring(7).strip();
            else break;
        }
        for (int i = debut; i <= lignes.length; i++) {
            if (i == lignes.length || lignes[i].strip().equals("---")) {
                String p = page.toString().strip();
                if (!p.isEmpty() && pages.size() < PAGES_MAX) pages.add(p.length() <= PAGE_MAX ? p : p.substring(0, PAGE_MAX));
                page.setLength(0);
            } else page.append(lignes[i]).append('\n');
        }
        if (titre.length() > TITRE_MAX) titre = titre.substring(0, TITRE_MAX);
        return new Contenu(titre, auteur, pages);
    }
}

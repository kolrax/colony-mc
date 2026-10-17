package fr.lacolonie.pnj;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Les règles du journal du poste (pur Java), qui remplace les lettres depuis la 1.0.0. Le journal est rangé de la ligne
 * la plus récente (en tête) à la plus ancienne.
 * <ul>
 * <li>Une ligne de routine (pas importante, avec un type) se fond dans la même ligne du même ouvrier si elle est parmi
 *   les {@link #RECENTES} dernières : elle remonte en tête, avec l'heure et un compteur (« J'ai faim ×3 »). Les situations
 *   communes ({@link #TOUS_OUVRIERS}) se regroupent tous ouvriers confondus, la ligne citant chacun.</li>
 * <li>Trop de lignes : partent d'abord les plus vieilles de routine, puis les plus vieilles importantes.</li>
 * </ul>
 * Et un petit outil de l'écran du poste : déplacer une mission dans une file ({@link #deplacer}).
 */
final class Boite {
    private Boite() {}

    /** Au-delà, le texte cumulé d'un avis regroupé est coupé. */
    static final int TEXTE_MAX = 1500;

    /** Au-delà, la liste des auteurs d'une ligne regroupée ({@code Lettre.auteurs}) s'arrête (« … »). */
    static final int AUTEURS_MAX = 200;

    /** Une ligne ne se fond que dans une semblable parmi les dernières : plus loin, c'est une nouvelle ligne. */
    static final int RECENTES = 10;

    /**
     * Les situations communes à tous : une seule ligne par situation, tous ouvriers confondus, qui cite chacun.
     * {@code poste_detruit} reste importante mais se regroupe quand même (une ligne, pas trente). Pas « toujours en
     * grève » : chacun a sa somme due.
     */
    static final Set<String> TOUS_OUVRIERS = Set.of("depot_plein", "depot_reserve", "paie_retard", "sans_poste", "poste_detruit");

    static boolean commune(Courrier.Lettre l) { return TOUS_OUVRIERS.contains(l.type); }

    static boolean regroupable(Courrier.Lettre l) { return !l.type.isEmpty() && (!l.importante || commune(l)); }

    /** La ligne récente du même type (et du même auteur, sauf situation commune) où {@code l} se fond ; ou null. */
    static Courrier.Lettre semblable(List<Courrier.Lettre> journal, Courrier.Lettre l) {
        if (!regroupable(l)) return null;
        for (int i = 0; i < Math.min(RECENTES, journal.size()); i++) {
            Courrier.Lettre x = journal.get(i);
            if (regroupable(x) && x.type.equals(l.type) && (commune(l) || Objects.equals(x.deUuid, l.deUuid) && x.de.equals(l.de))) return x;
        }
        return null;
    }

    /** Range une ligne arrivée. Vrai si c'est une nouvelle ligne ; faux si elle s'est fondue dans une autre. */
    static boolean ranger(List<Courrier.Lettre> journal, Courrier.Lettre l, int max) {
        Courrier.Lettre x = semblable(journal, l);
        if (x != null) {
            journal.remove(x);
            x.nombre += Math.max(1, l.nombre);
            if (commune(x)) { // le texte du dernier ; tous ceux qui l'ont dit, en tête du texte ; l'auteur, en court
                if (!Objects.equals(x.deUuid, l.deUuid)) x.deUuid = null;
                x.auteurs = auteurs(x.auteurs.isEmpty() ? x.de : x.auteurs, l.de);
                x.de = court(x.auteurs);
                x.texte = x.auteurs.contains(", ") ? "Ont dit : " + x.auteurs + ".\n\n" + l.texte : l.texte;
            } else x.texte = x.deUuid == null ? cumul(l.texte, x.texte) : l.texte;
            x.date = l.date;
            journal.add(0, x);
            return false;
        }
        journal.add(0, l);
        purger(journal, max);
        return true;
    }

    /** Le dernier avis en tête, les précédents dessous. */
    static String cumul(String recent, String ancien) {
        String s = recent + "\n\n" + ancien;
        return s.length() <= TEXTE_MAX ? s : s.substring(0, TEXTE_MAX - 1) + "…";
    }

    /** « Aliénor, Bertrand » : un nom de plus, s'il n'y est pas déjà ; « … » quand la liste est pleine. */
    static String auteurs(String liste, String nom) {
        if (List.of(liste.split(", ")).contains(nom) || liste.endsWith("…")) return liste;
        return liste.length() + nom.length() + 2 <= AUTEURS_MAX ? liste + ", " + nom : liste + ", …";
    }

    /** L'auteur tel que la ligne le montre : « Aliénor », « Aliénor et Bertrand », « Aliénor et 9 autres ». */
    static String court(String liste) {
        List<String> noms = List.of(liste.split(", "));
        if (noms.getLast().equals("…")) return noms.getFirst() + " et bien d'autres";
        return switch (noms.size()) {
            case 1 -> noms.getFirst();
            case 2 -> noms.get(0) + " et " + noms.get(1);
            default -> noms.getFirst() + " et " + (noms.size() - 1) + " autres";
        };
    }

    /** Ramène le journal à {@code max} lignes, les plus vieilles d'abord : la routine, puis (s'il le faut) les importantes. */
    static void purger(List<Courrier.Lettre> journal, int max) {
        for (int passe = 0; passe < 2 && journal.size() > max; passe++)
            for (int i = journal.size() - 1; i >= 0 && journal.size() > max; i--)
                if (passe == 1 || !journal.get(i).importante) journal.remove(i);
    }

    /** L'objet avec son compteur : « Plus de torches ×3 ». */
    static String objet(Courrier.Lettre l) { return l.nombre > 1 ? l.objet + " ×" + l.nombre : l.objet; }

    /** La ligne telle que le journal la montre : « Raoul : Mission terminée ×2 » ; un avis du poste, sans auteur. */
    static String ligne(Courrier.Lettre l) {
        return l.de.isEmpty() || l.de.equals("Le poste") ? objet(l) : l.de + " : " + objet(l);
    }

    /**
     * Déplace l'élément {@code i} de {@code sens} places (−1 : monte, +1 : descend) dans la liste. Faux (rien ne bouge)
     * si {@code i} n'existe pas ou s'il est déjà au bout.
     */
    static <T> boolean deplacer(List<T> l, int i, int sens) {
        int j = i + sens;
        if (i < 0 || i >= l.size() || j < 0 || j >= l.size() || sens == 0) return false;
        l.add(j, l.remove(i));
        return true;
    }
}

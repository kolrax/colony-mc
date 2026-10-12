package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Les règles de la boîte aux lettres du poste (pur Java). La boîte est rangée de la plus récente (en tête) à la plus
 * ancienne.
 * <ul>
 * <li>Une lettre de routine (pas importante, avec un type) se fond dans la lettre non lue du même auteur et du même
 *   type : texte le plus récent (un avis du poste garde aussi les précédents), date, et un compteur.</li>
 * <li>Trop de lettres : partent d'abord les plus vieilles lues, puis les plus vieilles archivées ; une non lue
 *   seulement s'il ne reste rien d'autre.</li>
 * <li>À l'écran : les importantes non lues, puis les non lues, puis les lues ; les plus récentes d'abord.</li>
 * </ul>
 */
final class Boite {
    private Boite() {}

    /** Au-delà, le texte cumulé d'un avis regroupé est coupé. */
    static final int TEXTE_MAX = 1500;

    static boolean regroupable(Courrier.Lettre l) { return !l.importante && !l.duChef && !l.type.isEmpty(); }

    /** La lettre non lue, non archivée, du même auteur et du même type, où {@code l} se fond ; ou null. */
    static Courrier.Lettre semblable(List<Courrier.Lettre> boite, Courrier.Lettre l) {
        if (!regroupable(l)) return null;
        for (Courrier.Lettre x : boite)
            if (!x.lue && !x.archivee && regroupable(x) && x.type.equals(l.type) && Objects.equals(x.deUuid, l.deUuid) && x.de.equals(l.de)) return x;
        return null;
    }

    /** Range une lettre arrivée. Vrai si c'est une nouvelle lettre ; faux si elle s'est fondue dans une autre. */
    static boolean ranger(List<Courrier.Lettre> boite, Courrier.Lettre l, int max) {
        Courrier.Lettre x = semblable(boite, l);
        if (x != null) {
            boite.remove(x);
            x.nombre += Math.max(1, l.nombre);
            x.texte = x.deUuid == null ? cumul(l.texte, x.texte) : l.texte;
            x.date = l.date;
            boite.add(0, x);
            return false;
        }
        boite.add(0, l);
        purger(boite, max);
        return true;
    }

    /** Le dernier avis en tête, les précédents dessous. */
    static String cumul(String recent, String ancien) {
        String s = recent + "\n\n" + ancien;
        return s.length() <= TEXTE_MAX ? s : s.substring(0, TEXTE_MAX - 1) + "…";
    }

    /** Ramène la boîte à {@code max} lettres : les plus vieilles lues, puis archivées, puis (seulement s'il le faut) non lues. */
    static void purger(List<Courrier.Lettre> boite, int max) {
        for (int passe = 0; passe < 3 && boite.size() > max; passe++)
            for (int i = boite.size() - 1; i >= 0 && boite.size() > max; i--) {
                Courrier.Lettre x = boite.get(i);
                if (passe == 2 || (passe == 0 ? x.lue && !x.archivee : x.archivee)) boite.remove(i);
            }
    }

    /** 0 : importante non lue ; 1 : non lue ; 2 : lue. */
    static int rang(Courrier.Lettre l) { return l.lue ? 2 : l.importante ? 0 : 1; }

    /** Les lettres à montrer (celles des archives, ou les autres), dans l'ordre de l'écran. */
    static List<Courrier.Lettre> affichees(List<Courrier.Lettre> boite, boolean archives) {
        List<Courrier.Lettre> l = new ArrayList<>();
        for (Courrier.Lettre x : boite) if (x.archivee == archives) l.add(x);
        l.sort(Comparator.comparingInt(Boite::rang)); // tri stable : les plus récentes d'abord dans chaque groupe
        return l;
    }

    /** L'objet tel que la liste le montre : « Plus de torches ×3 ». */
    static String objet(Courrier.Lettre l) { return l.nombre > 1 ? l.objet + " ×" + l.nombre : l.objet; }

    static Courrier.Lettre trouver(List<Courrier.Lettre> boite, long id) {
        for (Courrier.Lettre x : boite) if (x.id == id) return x;
        return null;
    }

    /** « Tout lu » : vrai si quelque chose a changé. */
    static boolean toutLu(List<Courrier.Lettre> boite) {
        boolean change = false;
        for (Courrier.Lettre x : boite) if (!x.lue && !x.archivee) { x.lue = true; change = true; }
        return change;
    }

    /** Range la lettre aux archives (elle compte alors comme lue), ou l'en ressort. */
    static boolean archiver(List<Courrier.Lettre> boite, long id, boolean archivee) {
        Courrier.Lettre x = trouver(boite, id);
        if (x == null || x.archivee == archivee) return false;
        x.archivee = archivee;
        x.lue = true;
        return true;
    }

    /** « Tout effacer » : les lettres lues, hors archives. Les non lues et les archivées restent. */
    static boolean effacerLues(List<Courrier.Lettre> boite) { return boite.removeIf(x -> x.lue && !x.archivee); }
}

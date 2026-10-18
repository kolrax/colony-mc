package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan D : les ouvriers sur la carte (Xaero). Qui voit quoi : chaque chef ne reçoit que ses propres ouvriers, et
 * seulement quand quelque chose a changé. Pur Java : l'envoi est dans {@link CarteOuvriers}, l'affichage dans
 * {@link XaeroOuvriers}.
 */
final class Reperes {
    private Reperes() {}

    /** Ce que le chef voit d'un ouvrier sur sa carte : son prénom et sa place (dimension, bloc). */
    record Repere(UUID id, String prenom, String dim, int x, int y, int z) {}

    /** Un ouvrier du monde, avec son chef ({@code null} pour un chercheur d'emploi). */
    record Ouvrier(UUID chef, Repere repere) {}

    /** Tous les combien de ticks le serveur refait la liste (2 secondes). */
    static final int PERIODE = 40;
    /** Au plus autant de repères par chef (bien plus qu'une colonie n'en aura). */
    static final int MAX = 256;

    /** Les repères de chaque chef, dans un ordre fixe (par identifiant) ; les ouvriers sans chef ne vont à personne. */
    static Map<UUID, List<Repere>> parChef(Collection<Ouvrier> ouvriers) {
        Map<UUID, List<Repere>> r = new HashMap<>();
        for (Ouvrier o : ouvriers) {
            if (o.chef() == null || o.repere() == null) continue;
            List<Repere> l = r.computeIfAbsent(o.chef(), c -> new ArrayList<>());
            if (l.size() < MAX) l.add(o.repere());
        }
        for (List<Repere> l : r.values()) l.sort(Comparator.comparing(Repere::id));
        return r;
    }

    /**
     * Faut-il envoyer cette liste au chef ? Oui si elle a changé depuis le dernier envoi ; un joueur sans ouvrier,
     * à qui rien n'a jamais été envoyé, ne reçoit rien (la liste vide ne part que pour effacer d'anciens repères).
     */
    static boolean aEnvoyer(List<Repere> avant, List<Repere> maintenant) {
        if (avant == null) return !maintenant.isEmpty();
        return !avant.equals(maintenant);
    }

    /**
     * Au plus autant de lettres dans le drapeau du repère : celui de la carte du monde de Xaero n'en tient que deux (vu au
     * banc : « Jean » y devient « Jear »). Le prénom entier est le nom du repère (survol de la carte, liste, étiquette).
     */
    static final int LETTRES = 2;

    /** Le texte du drapeau : le début du prénom de l'ouvrier, « Ou » s'il n'en a pas. */
    static String symbole(String prenom) {
        if (prenom == null || prenom.isBlank()) return "Ou";
        String p = prenom.strip();
        return p.length() <= LETTRES ? p : p.substring(0, LETTRES);
    }
}

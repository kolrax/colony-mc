package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Le chef donne un ordre écrit devant certains de ses ouvriers (refonte 1.0.0 : plus de chat libre ni d'IA). Seules
 * les phrases du répertoire et des métiers sont comprises ({@link Ordres}) ; la phrase désigne elle-même ses
 * destinataires ({@link Ordres#adresse}) : prénom, « les gars », métier, sinon l'ouvrier de la fenêtre (ou le plus proche).
 */
final class Dialogue {
    private Dialogue() {}

    /** On entend le chef jusque-là. */
    static final double PORTEE = 16;
    /** Au plus autant d'ouvriers répondent à la fois. */
    static final int MAX = 6;

    /** Ses ouvriers qui l'entendent, du plus proche au plus loin ; celui qu'il regarde (ou à qui il écrit) en premier. */
    static List<Travailleur> autour(ServerPlayer p, Travailleur vise) {
        List<Travailleur> l = new ArrayList<>(p.serverLevel().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(PORTEE),
                t -> p.getUUID().equals(t.chef) && !t.aTerre && t.isAlive() && t.distanceToSqr(p) <= PORTEE * PORTEE));
        l.sort(Comparator.comparingDouble(t -> t.distanceToSqr(p)));
        if (vise != null && l.remove(vise)) l.add(0, vise);
        return l.size() > MAX ? new ArrayList<>(l.subList(0, MAX)) : l;
    }

    /**
     * Le chef dit {@code texte}. {@code vise} : l'ouvrier de la fenêtre de conversation (null : sans viser personne).
     * {@code auMicro} : gardé pour les appelants, sans effet (plus de micro).
     */
    static void parler(ServerPlayer p, String texte, Travailleur vise, boolean auMicro) {
        String dit = texte.strip();
        if (dit.isEmpty() || dit.length() > 256) return;
        if (vise != null && !p.getUUID().equals(vise.chef)) { Etranger.parler(vise, p, dit); return; } // un autre que son chef (lot 14)
        if (vise != null && (vise.aTerre || p.distanceToSqr(vise) > PORTEE * PORTEE)) return;
        List<Travailleur> autour = autour(p, vise);
        if (autour.isEmpty()) return;

        // Une confirmation de renvoi (« oui » / « non ») reste une affaire de règles, avec celui à qui on l'a demandée.
        for (Travailleur t : autour)
            if (t.memoire.getLong("renvoi") > t.level().getGameTime()) {
                Ordres.Lecture l = Ordres.lire(dit, t.metier);
                if (l.intentions.contains(Ordres.Intention.OUI) || l.intentions.contains(Ordres.Intention.NON)) {
                    t.getLookControl().setLookAt(p);
                    Paroles.dire(t, t.appliquer(l, p), "calme");
                    return;
                }
            }

        // « Je vais prévenir Bertrand ? » : le « oui » ou le « non » qui suit est pour celui qui l'a proposé (Relais).
        for (Travailleur t : autour)
            if (t.relais != null && attend(t) && (vise == null || vise == t) && autour.stream().noneMatch(x -> x != t && (attend(x) || questionRecente(x)))
                    && (Relais.accepte(dit) || Relais.refuse(dit)) && Obeissance.sansOrdre(Ordres.lire(dit, t.metier))) {
                t.getLookControl().setLookAt(p);
                String r = t.appliquer(Ordres.lire(dit, t.metier), p);
                Paroles.noter(t, "Chef", dit);
                Paroles.noter(t, "Toi", r);
                Paroles.dire(t, r, "calme");
                return;
            }

        List<Travailleur> dest = destinataires(dit, autour, vise);
        if (!dest.isEmpty()) repondre(p, dit, dest);
    }

    /** À qui s'adresse la phrase : ceux qu'elle nomme, le groupe (« les gars », « les mineurs »), sinon le premier de la liste. */
    static List<Travailleur> destinataires(String dit, List<Travailleur> autour, Travailleur vise) {
        Ordres.Adresse a = Ordres.adresse(dit, autour.stream().map(t -> t.prenom).toList(), autour.stream().map(t -> t.metier).toList());
        List<Travailleur> r = new ArrayList<>();
        boolean groupe = a.tous() || !a.explicite() && a.pluriel();
        for (int i = 0; i < autour.size(); i++) {
            Travailleur t = autour.get(i);
            if (a.exclus().contains(i)) continue;
            if (groupe || a.appeles().contains(i) || a.metiersTous().contains(t.metier)) r.add(t);
        }
        for (String m : a.metiersUn())
            autour.stream().filter(t -> t.metier.equals(m) && !r.contains(t)).findFirst().ifPresent(r::add);
        if (!r.isEmpty() || a.explicite()) return r;
        // Personne de nommé : le premier dont le métier comprend la demande (la fenêtre d'abord) ; sinon celui de la fenêtre.
        for (Travailleur t : autour) {
            Ordres.Lecture l = Ordres.lire(dit, t.metier);
            if (!l.vide() && l.horsMetier.isEmpty() && l.pourAutres.isEmpty()) { r.add(t); return r; } // c'est son métier
        }
        for (Travailleur t : autour) if (!Ordres.lire(dit, t.metier).vide()) { r.add(t); return r; }
        if (vise != null) r.add(vise);
        return r;
    }

    /** Il attend la réponse du chef à un ordre refusé ou à une proposition (encore dans les 60 s). */
    static boolean attend(Travailleur t) { return t.enAttente != null && t.attenteFin >= t.level().getGameTime(); }

    /** Il vient de poser une question au chef (dans la minute). */
    static boolean questionRecente(Travailleur t) { return t.memoire.getLong("question_t") > System.currentTimeMillis() - 60_000; }

    /** Chaque destinataire applique ce qui le concerne et répond, chacun à son tour. */
    static void repondre(ServerPlayer p, String dit, List<Travailleur> dest) {
        ServerPlayer enFace = p.hasDisconnected() ? null : p;
        String chef = dest.size() > 1 ? "Chef (à " + String.join(", ", dest.stream().map(t -> t.prenom).toList()) + ")" : "Chef";
        long maintenant = System.currentTimeMillis();
        for (int i = 0; i < dest.size(); i++) {
            Travailleur t = dest.get(i);
            if (!t.isAlive() || t.aTerre) continue;
            if (enFace != null) t.getLookControl().setLookAt(enFace);
            Ordres.Lecture lecture = Ordres.lire(dit, t.metier);
            lecture.rang = i;
            lecture.total = dest.size();
            for (Travailleur x : dest) lecture.avec.add(x.getUUID());
            String r = t.appliquer(lecture, enFace);
            Paroles.noter(t, chef, dit);
            t.memoire.putLong("echange_t", maintenant);
            if (r.strip().endsWith("?")) t.memoire.putLong("question_t", maintenant);
            else t.memoire.remove("question_t");
            if (r.isEmpty()) continue;
            Paroles.noter(t, "Toi", r);
            Paroles.dire(t, r, "calme");
        }
    }
}

package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Le chef parle à voix haute (fenêtre de conversation ou micro) devant certains de ses ouvriers : à qui parle-t-il,
 * et qui répond ?
 * <ol>
 * <li>Le mod lit la phrase ({@link Ordres#adresse}) : un prénom qui interpelle (« Raoul, … »), « les gars »,
 *   « les mineurs », « sauf Raoul »… Ce qu'il y trouve est sûr : ce sont les destinataires.</li>
 * <li>Sinon, l'IA choisit parmi les ouvriers présents, d'après la demande et le métier de chacun
 *   (« va protéger la base » : le guerrier), et celui que le chef regarde.</li>
 * <li>Sans IA, le mod devine : le métier qui comprend la mission demandée, sinon celui qu'il regarde.</li>
 * </ol>
 * Chaque destinataire applique ce qui le concerne (revérifié comme un ordre écrit), et ils répondent chacun à leur tour.
 */
final class Dialogue {
    private Dialogue() {}

    /** On entend le chef jusque-là. */
    static final double PORTEE = 16;
    /** Au plus autant d'ouvriers décrits à l'IA (sa limite de jetons par minute est basse). */
    static final int MAX = 6;
    /** Les chefs dont la phrase précédente attend encore la réponse de l'IA (fil du serveur). */
    static final Set<UUID> EN_ATTENTE = new HashSet<>();

    /** Ses ouvriers qui l'entendent, du plus proche au plus loin ; celui qu'il regarde (ou à qui il écrit) en premier. */
    static List<Travailleur> autour(ServerPlayer p, Travailleur vise) {
        List<Travailleur> l = new ArrayList<>(p.serverLevel().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(PORTEE),
                t -> p.getUUID().equals(t.chef) && !t.aTerre && t.isAlive() && t.distanceToSqr(p) <= PORTEE * PORTEE));
        l.sort(Comparator.comparingDouble(t -> t.distanceToSqr(p)));
        if (vise != null && l.remove(vise)) l.add(0, vise);
        // ponytail: au-delà de MAX ouvriers, les plus loin n'entendent pas ; à revoir si une colonie en aligne davantage.
        return l.size() > MAX ? new ArrayList<>(l.subList(0, MAX)) : l;
    }

    /**
     * Le chef dit {@code texte}. {@code vise} : l'ouvrier de la fenêtre de conversation, ou celui qu'il regarde au micro
     * (null : au micro, sans regarder personne). {@code auMicro} : dit à voix haute plutôt qu'écrit dans la fenêtre.
     */
    static void parler(ServerPlayer p, String texte, Travailleur vise, boolean auMicro) {
        String dit = texte.strip();
        if (dit.isEmpty() || dit.length() > 256) return;
        if (vise != null && (vise.aTerre || !p.getUUID().equals(vise.chef) || p.distanceToSqr(vise) > PORTEE * PORTEE)) return;
        List<Travailleur> autour = autour(p, vise);
        if (autour.isEmpty()) return;

        // Une confirmation de renvoi (« oui » / « non ») reste une affaire de règles, avec celui à qui on l'a demandée.
        for (Travailleur t : autour)
            if (t.memoire.getLong("renvoi") > t.level().getGameTime()) {
                Ordres.Lecture l = Ordres.lire(dit, t.metier);
                if (l.intentions.contains(Ordres.Intention.OUI) || l.intentions.contains(Ordres.Intention.NON)) {
                    t.getLookControl().setLookAt(p);
                    Voix.dire(t, t.appliquer(l, p), "calme");
                    return;
                }
            }

        Ordres.Adresse a = Ordres.adresse(dit, autour.stream().map(t -> t.prenom).toList(), autour.stream().map(t -> t.metier).toList());
        List<Travailleur> imposes = imposes(a, autour);
        // Au micro, sans regarder personne : seulement ce qui s'adresse visiblement à eux (sinon il parle peut-être à quelqu'un d'autre).
        if (vise == null && imposes.isEmpty() && !a.demande() && autour.stream().allMatch(t -> Ordres.lire(dit, t.metier).vide())) return;

        if (IA.conversationDisponible()) avecIA(p, dit, autour, vise, auMicro, imposes);
        else repondre(p, dit, imposes.isEmpty() ? deviner(dit, autour, vise, a) : imposes, null);
    }

    /** Les destinataires que la phrase désigne elle-même (vide si elle n'en désigne pas). */
    static List<Travailleur> imposes(Ordres.Adresse a, List<Travailleur> autour) {
        Set<Travailleur> r = new LinkedHashSet<>();
        if (a.tous()) r.addAll(autour);
        for (int i : a.appeles()) r.add(autour.get(i));
        for (String m : a.metiersTous()) autour.stream().filter(t -> t.metier.equals(m)).forEach(r::add);
        for (String m : a.metiersUn()) autour.stream().filter(t -> t.metier.equals(m)).findFirst().ifPresent(r::add); // celui qu'il regarde, sinon le plus proche
        for (int i : a.exclus()) r.remove(autour.get(i));
        return new ArrayList<>(r);
    }

    /**
     * Sans IA, et sans destinataire dans la phrase : ceux dont le métier comprend la mission demandée (« va protéger
     * la base » : le guerrier ; un seul, sauf au pluriel), sinon celui qu'il regarde, sinon le plus proche qui comprend.
     */
    static List<Travailleur> deviner(String dit, List<Travailleur> autour, Travailleur vise, Ordres.Adresse a) {
        List<Travailleur> concernes = autour.stream().filter(t -> !Ordres.lire(dit, t.metier).missions.isEmpty()).toList();
        if (!concernes.isEmpty()) return a.pluriel() ? concernes : List.of(concernes.contains(vise) ? vise : concernes.get(0));
        if (vise != null) return List.of(vise);
        return autour.stream().filter(t -> !Ordres.lire(dit, t.metier).vide()).limit(a.pluriel() ? MAX : 1).toList();
    }

    /**
     * Face à un ouvrier, la phrase pourrait-elle viser quelqu'un d'autre ? Sinon, inutile de décrire tout le monde à
     * l'IA (son quota se compte en jetons) : « ramène dix fers » au mineur, « comment ça va ? », c'est pour lui.
     */
    static boolean pourUnAutre(String dit, List<Travailleur> autour, Travailleur vise, Ordres.Adresse a) {
        if (autour.size() < 2) return false;
        if (!a.cites().isEmpty() || a.pluriel()) return true;
        Ordres.Lecture lui = Ordres.lire(dit, vise.metier);
        boolean pourAutre = autour.stream().anyMatch(t -> !t.metier.equals(vise.metier) && !Ordres.lire(dit, t.metier).missions.isEmpty());
        return pourAutre || a.demande() && lui.missions.isEmpty() && lui.intentions.isEmpty();
    }

    static void avecIA(ServerPlayer p, String dit, List<Travailleur> autour, Travailleur vise, boolean auMicro, List<Travailleur> imposes) {
        if (!EN_ATTENTE.add(p.getUUID())) { Travailleur.signaler(p, "Tes ouvriers réfléchissent encore à ce que tu viens de dire…"); return; }
        List<Travailleur> envoyes = !imposes.isEmpty() ? imposes : vise != null && !pourUnAutre(dit, autour, vise, Ordres.adresse(dit,
                autour.stream().map(t -> t.prenom).toList(), autour.stream().map(t -> t.metier).toList())) ? List.of(vise) : autour;
        envoyes.forEach(t -> t.reflechit = true);
        Travailleur.signaler(p, envoyes.size() == 1 ? envoyes.get(0).prenom + " réfléchit…" : "Ils réfléchissent…");
        var serveur = p.server;
        List<String> etiquettes = IA.etiquettes(envoyes);
        java.util.concurrent.CompletableFuture<ReponsesIA.Groupe> f;
        try { f = IA.converserGroupe(envoyes, vise, !imposes.isEmpty(), dit); }
        catch (RuntimeException e) { f = java.util.concurrent.CompletableFuture.failedFuture(e); } // jamais bloqué « en attente »
        f.whenComplete((g, err) -> serveur.execute(() -> {
            EN_ATTENTE.remove(p.getUUID());
            envoyes.forEach(t -> t.reflechit = false);
            if (g == null) { // l'IA ne répond pas : le mod devine seul
                repondre(p, dit, imposes.isEmpty() ? deviner(dit, autour, vise, Ordres.adresse(dit, autour.stream().map(t -> t.prenom).toList(),
                        autour.stream().map(t -> t.metier).toList())) : imposes, null);
                return;
            }
            List<Travailleur> dest = new ArrayList<>(imposes);
            if (dest.isEmpty()) for (String e : g.destinataires()) dest.add(envoyes.get(etiquettes.indexOf(e)));
            // Dans la fenêtre de conversation, on parle au moins à celui à qui on écrit.
            if (dest.isEmpty() && vise != null && !auMicro) dest.add(vise);
            List<ReponsesIA.Decision> decisions = new ArrayList<>();
            for (Travailleur t : dest) decisions.add(g.reponses().get(etiquettes.get(envoyes.indexOf(t))));
            repondre(p, dit, dest, decisions);
        }));
    }

    /**
     * Chaque destinataire applique ce qui le concerne et répond. {@code decisions} : celles de l'IA (dans le même
     * ordre ; null, ou une case vide : l'interpréteur). Ceux à qui l'IA fait dire quelque chose parlent les premiers.
     */
    static void repondre(ServerPlayer p, String dit, List<Travailleur> dest, List<ReponsesIA.Decision> decisions) {
        ServerPlayer enFace = p.hasDisconnected() ? null : p;
        List<Voix.Replique> parlent = new ArrayList<>(), suivent = new ArrayList<>();
        String chef = dest.size() > 1 ? "Chef (à " + String.join(", ", dest.stream().map(t -> t.prenom).toList()) + ")" : "Chef";
        for (int i = 0; i < dest.size(); i++) {
            Travailleur t = dest.get(i);
            if (!t.isAlive() || t.aTerre) continue;
            if (enFace != null) t.getLookControl().setLookAt(enFace);
            ReponsesIA.Decision d = decisions == null ? null : decisions.get(i);
            String r, humeur = "calme";
            boolean ia = false;
            if (d == null) r = t.appliquer(Ordres.lire(dit, t.metier), enFace);
            else {
                long renvoiAvant = t.memoire.getLong("renvoi");
                String regles = t.appliquer(d.lecture(), enFace);
                // Un renvoi demande toujours confirmation : on pose la question clairement.
                ia = t.memoire.getLong("renvoi") == renvoiAvant && !d.reponse().isEmpty();
                r = ia ? d.reponse() : regles;
                humeur = d.humeur();
            }
            IA.noter(t, chef, dit);
            if (r.isEmpty()) continue;
            IA.noter(t, "Toi", r);
            (ia || decisions == null ? parlent : suivent).add(new Voix.Replique(t, r, humeur));
        }
        parlent.addAll(suivent);
        Voix.direEnSuite(parlent);
    }
}

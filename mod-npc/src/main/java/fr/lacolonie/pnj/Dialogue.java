package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Le chef parle (fenêtre de conversation ou micro) devant certains de ses ouvriers : à qui parle-t-il, et qui répond ?
 * <ol>
 * <li>Le code donne à chaque ouvrier une probabilité d'être visé ({@link Adressage}) : prénom, « les gars », regard,
 *   distance, tutoiement, ordre, métier concerné, vrais joueurs proches, conversation récente…</li>
 * <li>S'il est sûr (personne n'est visé, ou la phrase désigne elle-même ses destinataires), il tranche seul. Sinon,
 *   l'IA lit la phrase et le contexte et donne sa propre probabilité ; les deux sont croisées.</li>
 * <li>Les destinataires appliquent ce qui les concerne (revérifié comme un ordre écrit) et répondent chacun à leur tour.</li>
 * </ol>
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

    /** Ce que voit et entend le code : la phrase, chaque ouvrier (distance, regard du chef, échange récent), les vrais joueurs proches. */
    static Adressage.Situation situation(ServerPlayer p, String dit, List<Travailleur> autour, Travailleur vise, boolean auMicro) {
        Vec3 oeil = p.getEyePosition(), regard = p.getLookAngle();
        long maintenant = System.currentTimeMillis();
        List<Adressage.Pnj> pnj = new ArrayList<>();
        for (Travailleur t : autour) {
            Vec3 v = t.getEyePosition().subtract(oeil);
            double d = v.length();
            double depuis = t.memoire.contains("echange_t") ? Math.max(0, (maintenant - t.memoire.getLong("echange_t")) / 1000.0) : -1;
            List<String> derniers = new ArrayList<>(List.of(IA.echange(t, 2).split("\n")));
            derniers.removeIf(String::isBlank);
            pnj.add(new Adressage.Pnj(t.prenom, t.metier, d, d < 0.01 ? 1 : v.scale(1 / d).dot(regard), depuis,
                    t.memoire.getLong("question_t") > maintenant - 60_000, !auMicro && t == vise, derniers));
        }
        List<Adressage.Joueur> joueurs = new ArrayList<>();
        for (ServerPlayer x : p.serverLevel().players()) {
            if (x == p || x.isSpectator() || x instanceof net.neoforged.neoforge.common.util.FakePlayer) continue;
            Vec3 v = x.getEyePosition().subtract(oeil);
            double d = v.length();
            if (d > 32) continue;
            joueurs.add(new Adressage.Joueur(x.getName().getString(), d, d < 0.01 ? 1 : v.scale(1 / d).dot(regard)));
        }
        return new Adressage.Situation(dit, auMicro, pnj, joueurs);
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

        // « Je vais prévenir Bertrand ? » : le « oui » ou le « non » qui suit est pour celui qui l'a proposé (Relais), s'il est
        // celui de la fenêtre (ou qu'il n'y en a pas) et que personne d'autre autour n'attend une réponse (un marché, une
        // question) ; sinon, c'est l'adressage qui tranche.
        for (Travailleur t : autour)
            if (t.relais != null && attend(t) && (vise == null || vise == t) && autour.stream().noneMatch(x -> x != t && (attend(x) || questionRecente(x)))
                    && (Relais.accepte(dit) || Relais.refuse(dit)) && Obeissance.sansOrdre(Ordres.lire(dit, t.metier))) {
                t.getLookControl().setLookAt(p);
                String r = t.appliquer(Ordres.lire(dit, t.metier), p);
                IA.noter(t, "Chef", dit);
                IA.noter(t, "Toi", r);
                Voix.dire(t, r, "calme");
                return;
            }

        Adressage.Situation s = situation(p, dit, autour, vise, auMicro);
        Adressage.Parametres pa = Adressage.P;
        double[] pc = Adressage.code(s);
        // Le code est sûr de lui (ou l'IA n'est pas là) : il tranche seul.
        if (Adressage.sur(s, pc, pa) || !IA.ecouteIADisponible()) {
            List<Integer> choisis = Adressage.choisir(s, pc, pa.seuilCode());
            journal(p, s, pc, null, pc, null, choisis, Adressage.sur(s, pc, pa) ? "code sûr" : "code seul (IA indisponible)");
            if (!choisis.isEmpty()) repondreAvec(p, dit, indices(autour, choisis), vise, auMicro, false);
            return;
        }
        // Sinon, l'avis de l'IA, croisé avec celui du code.
        if (!EN_ATTENTE.add(p.getUUID())) { Travailleur.signaler(p, "Tes ouvriers réfléchissent encore à ce que tu viens de dire…"); return; }
        autour.forEach(t -> t.reflechit = true);
        if (!auMicro && vise != null) Travailleur.signaler(p, vise.prenom + " réfléchit…");
        var serveur = p.server;
        CompletableFuture<Adressage.Avis> f;
        try { f = IA.ecouter(s); }
        catch (RuntimeException e) { f = CompletableFuture.failedFuture(e); } // jamais bloqué « en attente »
        f.whenComplete((avis, err) -> serveur.execute(() -> {
            autour.forEach(t -> t.reflechit = false);
            double[] pi = avis == null ? null : avis.p();
            double[] pf = new double[pc.length];
            boolean remarque = Adressage.remarque(s);
            for (int i = 0; i < pc.length; i++) pf[i] = pi == null ? pc[i] : Adressage.fusion(pc[i], pi[i], pa, remarque);
            List<Integer> choisis = Adressage.choisir(s, pf, pi == null ? pa.seuilCode() : pa.seuil());
            journal(p, s, pc, pi, pf, avis == null ? null : avis.liens(), choisis, pi == null ? "code seul (l'IA n'a pas répondu)" : "code × IA");
            if (choisis.isEmpty()) { EN_ATTENTE.remove(p.getUUID()); return; }
            repondreAvec(p, dit, indices(autour, choisis), vise, auMicro, true);
        }));
    }

    /** Il attend la réponse du chef à un ordre refusé, marchandé, ou à une proposition (encore dans les 60 s). */
    static boolean attend(Travailleur t) { return t.enAttente != null && t.attenteFin >= t.level().getGameTime(); }

    /** Il vient de poser une question au chef (dans la minute). */
    static boolean questionRecente(Travailleur t) { return t.memoire.getLong("question_t") > System.currentTimeMillis() - 60_000; }

    static List<Travailleur> indices(List<Travailleur> autour, List<Integer> l) {
        List<Travailleur> r = new ArrayList<>();
        for (int i : l) r.add(autour.get(i));
        return r;
    }

    /**
     * Les destinataires répondent : avec l'IA (une seule demande pour eux tous), sinon avec l'interpréteur.
     * {@code tenu} : ce chef est déjà marqué « en attente » (par l'avis de l'IA).
     */
    static void repondreAvec(ServerPlayer p, String dit, List<Travailleur> dest, Travailleur vise, boolean auMicro, boolean tenu) {
        // Des ordres longs (« s'il pleut, rentre », « oublie tes ordres ») : l'interpréteur seul les comprend, sans dépenser l'IA.
        if (!IA.conversationDisponible() || dest.stream().anyMatch(t -> { Ordres.Lecture x = Ordres.lire(dit, t.metier); return !x.regles.isEmpty() || x.annulerRegles; })) {
            if (tenu) EN_ATTENTE.remove(p.getUUID());
            repondre(p, dit, dest, null);
            return;
        }
        if (!tenu && !EN_ATTENTE.add(p.getUUID())) { Travailleur.signaler(p, "Tes ouvriers réfléchissent encore à ce que tu viens de dire…"); return; }
        dest.forEach(t -> t.reflechit = true);
        Travailleur.signaler(p, dest.size() == 1 ? dest.get(0).prenom + " réfléchit…" : "Ils réfléchissent…");
        var serveur = p.server;
        List<String> etiquettes = IA.etiquettes(dest);
        CompletableFuture<ReponsesIA.Groupe> f;
        try { f = IA.converserGroupe(dest, dest.contains(vise) ? vise : null, !auMicro, dit); }
        catch (RuntimeException e) { f = CompletableFuture.failedFuture(e); }
        f.whenComplete((g, err) -> serveur.execute(() -> {
            EN_ATTENTE.remove(p.getUUID());
            dest.forEach(t -> t.reflechit = false);
            if (g == null) { repondre(p, dit, dest, null); return; } // l'IA ne répond pas : l'interpréteur
            List<ReponsesIA.Decision> decisions = new ArrayList<>();
            for (int i = 0; i < dest.size(); i++) decisions.add(g.reponses().get(etiquettes.get(i)));
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
        long maintenant = System.currentTimeMillis();
        noterIncompris(p, dit, dest, decisions, maintenant);
        for (int i = 0; i < dest.size(); i++) {
            Travailleur t = dest.get(i);
            if (!t.isAlive() || t.aTerre) continue;
            if (enFace != null) t.getLookControl().setLookAt(enFace);
            ReponsesIA.Decision d = decisions == null ? null : decisions.get(i);
            String r, humeur = "calme";
            boolean ia = false;
            Ordres.Lecture lecture = d == null ? Ordres.lire(dit, t.metier) : d.lecture();
            if (d != null) {
                lecture.texte = dit; // on y cherche qui ou quoi est visé (l'interpréteur l'a déjà fait, sans la partie des règles)
                Ordres.Lecture seul = Ordres.lire(dit, t.metier);
                lecture.quotidien |= seul.quotidien; // « tous les jours » : l'IA ne le dit pas
                if (seul.intentions.contains(Ordres.Intention.ENTERRER)) lecture.intentions.add(Ordres.Intention.ENTERRER); // l'IA ne connaît pas l'enterrement (Deuil)
                Relais.reprendre(lecture, seul); // pas son métier : il propose de prévenir (Relais)
            }
            lecture.rang = i;
            lecture.total = dest.size();
            for (Travailleur x : dest) lecture.avec.add(x.getUUID());
            if (d == null) r = t.appliquer(lecture, enFace);
            else {
                long renvoiAvant = t.memoire.getLong("renvoi");
                String regles = t.appliquer(lecture, enFace);
                // Un renvoi demande toujours confirmation : on pose la question clairement.
                // Un refus, un marché ou un ordre mortel : c'est son obéissance qui répond, pas l'IA (cahier 8.4, 8.5).
                ia = t.memoire.getLong("renvoi") == renvoiAvant && !d.reponse().isEmpty() && !t.reponseImposee;
                r = ia ? d.reponse() : regles;
                humeur = d.humeur();
            }
            IA.noter(t, chef, dit);
            // Le contexte pour la prochaine fois : quand ils se sont parlé, et s'il attend une réponse à sa question.
            t.memoire.putLong("echange_t", maintenant);
            if (r.strip().endsWith("?")) t.memoire.putLong("question_t", maintenant);
            else t.memoire.remove("question_t");
            if (r.isEmpty()) continue;
            IA.noter(t, "Toi", r);
            (ia || decisions == null ? parlent : suivent).add(new Voix.Replique(t, r, humeur));
        }
        parlent.addAll(suivent);
        Voix.direEnSuite(parlent);
    }

    /**
     * Une phrase que l'interpréteur ne comprend pour aucun destinataire va au journal des phrases non comprises
     * ({@link Incompris}) : « ia_seule » si l'IA y a vu une action, « ia_parole » si elle a seulement répondu,
     * « rien » sinon (IA absente, en échec ou muette). Ne change rien à ce que font les ouvriers.
     */
    static void noterIncompris(ServerPlayer p, String dit, List<Travailleur> dest, List<ReponsesIA.Decision> decisions, long maintenant) {
        if (Souvenirs.question(dit) || !Souvenirs.lire(dit).isEmpty() || !dit.contains("?") && Impossible.genre(dit) != null) return; // compris : une question sur ses souvenirs, une insulte, une promesse, l'impossible…
        Set<String> metiers = new java.util.TreeSet<>();
        for (Travailleur t : dest)
            if (metiers.add(t.metier) && !Ordres.lire(dit, t.metier).vide()) return; // compris sans l'IA
        String motif = Incompris.RIEN;
        if (decisions != null) for (ReponsesIA.Decision d : decisions) {
            if (d == null) continue;
            if (!d.lectureDemandee().vide()) { motif = Incompris.IA_SEULE; break; }
            if (!d.reponse().isBlank()) motif = Incompris.IA_PAROLE;
        }
        // Aucun nom dans le journal : ni les joueurs connectés (le chef compris), ni les prénoms de ses ouvriers.
        List<String> noms = new ArrayList<>(p.server.getPlayerList().getPlayers().stream().map(x -> x.getName().getString()).toList());
        noms.add(p.getName().getString());
        dest.forEach(t -> noms.add(t.prenom));
        var effectif = Courrier.de(p.server).effectifs.get(p.getUUID());
        if (effectif != null) effectif.values().forEach(m -> noms.add(m.nom));
        Incompris.JOURNAL.noter(Incompris.anonymiser(dit, noms), motif, metiers, maintenant);
    }

    // --- Le journal d'écoute (pour le panneau, en mémoire seulement) ---------------------------------------------

    /** Les dernières phrases entendues et ce qui a été décidé, avec les probabilités du code, de l'IA et la finale. */
    static final Deque<Map<String, Object>> JOURNAL = new ArrayDeque<>();

    static void journal(ServerPlayer p, Adressage.Situation s, double[] pc, double[] pi, double[] pf, String[] liens, List<Integer> choisis, String comment) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("heure", java.time.LocalTime.now().withNano(0).toString());
        e.put("joueur", p.getName().getString());
        e.put("mode", s.micro() ? "micro" : "écrit");
        e.put("texte", s.texte());
        e.put("decision", comment);
        e.put("autres_joueurs", s.joueurs().size());
        e.putAll(Adressage.resume(s, pc, pi, pf, liens, choisis));
        synchronized (JOURNAL) {
            JOURNAL.addFirst(e);
            while (JOURNAL.size() > 60) JOURNAL.removeLast();
        }
    }

    static List<Map<String, Object>> journal() {
        synchronized (JOURNAL) { return new ArrayList<>(JOURNAL); }
    }
}

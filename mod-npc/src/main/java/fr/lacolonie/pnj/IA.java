package fr.lacolonie.pnj;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Les appels aux services d'IA, toujours en arrière-plan (jamais sur le fil du serveur) :
 * <ul>
 * <li>{@link #converser} : Groq répond à la place du PNJ et choisit son action dans une liste fermée ;</li>
 * <li>{@link #transcrire} : Groq (Whisper) met en texte ce qu'un joueur dit au micro ;</li>
 * <li>{@link #voix} : Gemini prête sa voix au PNJ.</li>
 * </ul>
 * La conversation passe d'abord par Groq (gratuit) ; quand il n'est pas disponible ou qu'il échoue, une seule fois
 * OpenRouter (payant, dans le budget du mois : {@link #secours}).
 * Chaque service a son quota du jour (sous les quotas gratuits) et se met en pause quand le fournisseur refuse
 * (trop de demandes, clé refusée). Tout échec renvoie un futur en erreur : l'appelant retombe alors sur le
 * fonctionnement sans IA (l'interpréteur, les bulles).
 */
final class IA {
    private IA() {}

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** Un service : ses demandes du jour, ses pauses, sa dernière erreur (pour le panneau). */
    static final class Service {
        final String nom;
        String jour = "", derniereErreur = "";
        int demandes, reussites, jetons;
        long pauseJusqua, derniereReussite;
        final ArrayDeque<Long> minute = new ArrayDeque<>();
        final int parMinute;

        Service(String nom, int parMinute) { this.nom = nom; this.parMinute = parMinute; }

        void raz() {
            String j = LocalDate.now(ZoneOffset.UTC).toString();
            if (!j.equals(jour)) { jour = j; demandes = 0; reussites = 0; jetons = 0; }
            long t = System.currentTimeMillis();
            while (!minute.isEmpty() && t - minute.peekFirst() > 60_000) minute.pollFirst();
        }

        /** Vrai s'il reste du quota (jour et minute) et que le service n'est pas en pause ; compte la demande. */
        synchronized boolean prendre(int maxJour) {
            raz();
            if (System.currentTimeMillis() < pauseJusqua || demandes >= maxJour || minute.size() >= parMinute) return false;
            demandes++;
            minute.addLast(System.currentTimeMillis());
            return true;
        }

        synchronized boolean disponible(int maxJour) {
            raz();
            return System.currentTimeMillis() >= pauseJusqua && demandes < maxJour && minute.size() < parMinute;
        }

        /** Les clés ont peut-être changé (ia.json relu, essai réussi) : on lève la pause. */
        synchronized void reprendre() { pauseJusqua = 0; }

        synchronized void reussite(int n) { reussites++; jetons += n; derniereReussite = System.currentTimeMillis(); }

        synchronized void echec(String message, long pauseMs) {
            derniereErreur = java.time.LocalTime.now().withNano(0) + " : " + message;
            if (pauseMs > 0) pauseJusqua = Math.max(pauseJusqua, System.currentTimeMillis() + pauseMs);
            ColoniePnj.LOG.warn("IA ({}) : {}", nom, message);
        }

        synchronized Map<String, Object> etat(int maxJour) {
            raz();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("demandes", demandes);
            m.put("reussites", reussites);
            m.put("max", maxJour);
            m.put("jetons", jetons);
            m.put("pause", Math.max(0, (pauseJusqua - System.currentTimeMillis()) / 1000));
            m.put("erreur", derniereErreur);
            m.put("derniereReussite", derniereReussite);
            return m;
        }
    }

    // Groq gratuit : 30 demandes par minute pour la conversation, 20 pour Whisper ; Gemini : plus serré.
    static final Service CONVERSATION = new Service("conversation", 25), TRANSCRIPTION = new Service("transcription", 18), VOIX = new Service("voix", 8);
    /** L'avis d'écoute (à qui le chef parle) : un autre modèle, avec son propre quota chez Groq. */
    static final Service ECOUTE = new Service("écoute", 25);
    /** La conversation payante, quand Groq n'est pas disponible ; sa dépense est dans {@link #DEPENSES}. */
    static final Service OPENROUTER = new Service("openrouter", 20);
    static volatile DepensesIA DEPENSES = new DepensesIA();

    static LocalDate aujourdhui() { return LocalDate.now(ZoneOffset.UTC); }

    static ReglagesIA r() { return ReglagesIA.actuel; }

    /** La conversation est possible : par Groq (gratuit), sinon par OpenRouter (payant, s'il reste du budget). */
    static boolean conversationDisponible() { return groqDisponible() || openrouterDisponible(); }

    static boolean groqDisponible() { return r().conversationPossible() && CONVERSATION.disponible(r().max_conversations_jour); }

    static boolean openrouterDisponible() {
        return r().openrouterPossible() && DEPENSES.permis(r().budget_openrouter_mois, aujourdhui()) && OPENROUTER.disponible(r().max_openrouter_jour);
    }

    /** L'avis d'écoute est possible (clé Groq, quota du jour et de la minute). */
    static boolean ecouteIADisponible() { return r().conversationPossible() && ECOUTE.disponible(r().max_ecoutes_jour); }

    static boolean voixDisponible() { return r().voixPossible() && VOIX.disponible(r().max_voix_jour); }

    static boolean ecouteDisponible() { return r().ecoutePossible() && TRANSCRIPTION.disponible(r().max_transcriptions_jour); }

    // --- Appels HTTP -----------------------------------------------------------------------------------

    /** Envoie la requête ; en cas de refus, met le service en pause (le temps demandé, ou un délai raisonnable) et échoue. */
    static CompletableFuture<String> envoyer(Service s, HttpRequest req) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(rep -> {
            int code = rep.statusCode();
            if (code / 100 == 2) return rep.body();
            if (code == 400 && ReponsesIA.generationRatee(rep.body()) != null) {
                // Le modèle est sorti du schéma : on garde ce qu'il a écrit, la lecture en écarte ce qui n'est pas permis.
                ColoniePnj.LOG.info("IA ({}) : réponse hors schéma rattrapée ({})", s.nom, ReponsesIA.erreur(rep.body()));
                return rep.body();
            }
            String msg = "HTTP " + code + " : " + ReponsesIA.erreur(rep.body());
            long attente = rep.headers().firstValue("retry-after").map(v -> { try { return (long) (Double.parseDouble(v) * 1000); } catch (NumberFormatException e) { return 60_000L; } }).orElse(-1L);
            if (code == 429) s.echec("trop de demandes (quota du fournisseur) — " + msg, attente > 0 ? Math.min(attente, 3_600_000) : 120_000);
            else if (code == 401 || code == 403) s.echec("clé refusée — vérifie-la dans ia.json (" + msg + ")", 600_000);
            else if (code == 402) s.echec("plus de crédit chez le fournisseur — " + msg, 3_600_000);
            else if (code >= 500) s.echec("le service a un problème — " + msg, 30_000);
            else s.echec(msg, 0);
            throw new CompletionException(new IllegalStateException(msg));
        }).whenComplete((v, e) -> {
            if (e != null && !(e instanceof CompletionException ce && ce.getCause() instanceof IllegalStateException))
                s.echec("injoignable — " + e.getMessage(), 15_000);
        });
    }

    // --- La conversation ---------------------------------------------------------------------------------

    /**
     * Ce que répond le PNJ, et ce qu'il fait. À appeler sur le fil du serveur (on y lit l'état du PNJ) ; la réponse
     * arrive plus tard, sur un autre fil. {@code enFace} : de vive voix (sinon c'est une lettre).
     */
    static CompletableFuture<ReponsesIA.Decision> converser(Travailleur t, String parole, boolean enFace) {
        if (!conversationDisponible()) return CompletableFuture.failedFuture(new IllegalStateException("conversation indisponible"));
        Metier m = Metier.de(t.metier);
        List<String> ids = m == null ? List.of() : m.commandes.stream().map(c -> c.id).toList();
        Set<String> idsSet = Set.copyOf(ids);
        String corps = ReponsesIA.requeteConversation(r().modele_conversation, systeme(t, enFace), (enFace ? "Ton chef te dit : « " : "Lettre de ton chef : « ") + parole + " »",
                ReponsesIA.schema(ids, enFace));
        java.util.function.Function<String, ReponsesIA.Decision> lire = rep -> ReponsesIA.decision(ReponsesIA.contenu(rep), idsSet, enFace);
        CompletableFuture<ReponsesIA.Decision> groq = !r().conversationPossible() || !CONVERSATION.prendre(r().max_conversations_jour)
                ? CompletableFuture.failedFuture(new IllegalStateException("Groq indisponible"))
                : envoyer(CONVERSATION, requeteGroq(corps)).thenApply(rep -> {
                    ReponsesIA.Decision d = lire.apply(rep);
                    CONVERSATION.reussite(ReponsesIA.jetons(rep));
                    return d;
                });
        return groq.exceptionallyCompose(e -> secours(corps, lire, e));
    }

    static HttpRequest requeteGroq(String corps) {
        return HttpRequest.newBuilder(URI.create(r().url_conversation)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + r().groq_cle).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corps)).build();
    }

    /**
     * Groq n'a pas pu répondre (pas de clé, quota, pause, échec) : une seule demande à OpenRouter, s'il a une clé et
     * qu'il reste du budget (mois et jour) ; son coût réel est ajouté aux dépenses, même si la réponse est inutilisable.
     * Sinon l'échec de Groq passe tel quel : l'appelant retombe sur l'interpréteur.
     */
    static <T> CompletableFuture<T> secours(String corps, java.util.function.Function<String, T> lire, Throwable cause) {
        if (!r().openrouterPossible() || !DEPENSES.permis(r().budget_openrouter_mois, aujourdhui()) || !OPENROUTER.prendre(r().max_openrouter_jour))
            return CompletableFuture.failedFuture(cause);
        HttpRequest req = HttpRequest.newBuilder(URI.create(r().url_openrouter)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + r().openrouter_cle).header("Content-Type", "application/json")
                .header("X-Title", "La Colonie")
                .POST(HttpRequest.BodyPublishers.ofString(ReponsesIA.pourOpenRouter(corps, r().modele_openrouter))).build();
        return envoyer(OPENROUTER, req).whenComplete((rep, e) -> {
            // Coupé en route (délai, connexion) : l'appel est peut-être facturé quand même, on le compte au prix prudent.
            if (e != null && !(e instanceof CompletionException ce && ce.getCause() instanceof IllegalStateException))
                DEPENSES.ajouter(ReponsesIA.PRIX_APPEL, aujourdhui());
        }).thenApply(rep -> {
            DEPENSES.ajouter(ReponsesIA.cout(rep), aujourdhui());
            T x = lire.apply(rep);
            OPENROUTER.reussite(ReponsesIA.jetons(rep));
            return x;
        });
    }

    static final Map<String, String> TEMPERAMENTS = Map.of(
            "calme", "posé, patient, fiable, parle simplement",
            "peureux", "craintif, prudent, s'inquiète vite et le montre",
            "fourbe", "rusé et flatteur, un brin malhonnête mais jamais ouvertement déloyal",
            "colerique", "bourru, prompt à s'emporter, mais reste au service de son chef");

    static int heure(Travailleur t) { return (int) ((t.level().getDayTime() + 6000) % 24000 / 1000); }

    /** Ce qu'il sait de lui, en une ligne (le moins de jetons possible : le quota gratuit en compte 200 000 par jour). */
    static String etat(Travailleur t) {
        String missions = t.missions.isEmpty() ? "aucune" : t.missions.stream().map(x -> Metier.avancementTexte(t, x)).collect(Collectors.joining(" ; "));
        return t.statut() + " ; " + t.activite() + " Position : " + Messages.ou(t) + ". Santé " + (int) Math.ceil(t.getHealth()) + "/" + (int) t.getMaxHealth()
                + ", faim " + t.faim + "/20, fatigue " + t.fatigue + "/100. Missions : " + missions + ". Horaires : de " + t.debut + " h à " + t.fin
                + " h ; expéditions de " + t.joursExpedition + " jours au plus. Salaire : " + Reglages.texte(t.salaireVerse()) + " par paie"
                + (t.impayes > 0 ? ", " + t.impayes + " paie(s) en retard" : "") + ". Moral " + t.moral + "/100"
                + (t.prime > 0 ? ", prime promise : " + Reglages.texte(t.prime) : "") + (t.besoin.isEmpty() ? "" : ". Il lui manque : " + t.besoin) + ".";
    }

    static String catalogue(Travailleur t) {
        Metier m = Metier.de(t.metier);
        return m == null ? "" : m.commandes.stream().map(c -> c.id + " = " + c.nom).collect(Collectors.joining(", "));
    }

    static List<String> idsMissions(Travailleur t) {
        Metier m = Metier.de(t.metier);
        return m == null ? List.of() : m.commandes.stream().map(c -> c.id).toList();
    }

    static final String CORRECT = "Ne dis jamais que tu es une IA ou un programme, ne parle pas du monde réel, reste correct (rien de choquant), même en colère.";

    static String actions(boolean enFace) { return actions(enFace, List.of(), List.of()); }

    /**
     * Les actions possibles. {@code repertoire} : les actions du répertoire proposées pour cette phrase (les plus proches
     * de ce qu'il a dit, puis les petits gestes) ; {@code hors} : celles qu'il demande mais qu'aucun d'eux ne sait faire.
     */
    static String actions(boolean enFace, List<Repertoire.Entree> repertoire, List<Repertoire.Entree> hors) {
        StringBuilder b = new StringBuilder("Actions (champ actions, seulement si le chef le demande clairement ; sinon liste vide ; type pris seulement dans cette liste) : ");
        if (enFace) b.append("suivre (suivre le chef) ; arreter_suivre (cesser de le suivre et retourner au travail) ; attendre (rester sur place) ; montrer_sac ; ")
                .append("renvoyer (seulement si le chef le renvoie clairement) ; ");
        b.append("travailler (retourner au travail) ; rentrer (revenir au poste de travail) ; missions (missions : id parmi les possibles de son métier, mode quantite = en rapporter nombre puis arrêter, ")
                .append("ou stock = en garder nombre au dépôt en permanence ; ajouter = vrai si le chef dit « aussi » ou « en plus », sinon elles remplacent les anciennes) ; ")
                .append("horaires (debut et fin, de 0 à 23 ; même heure = toute la journée) ; expedition (jours : 1 à 30). ")
                .append("Dans une action, mets 0, faux, \"\" ou [] aux champs inutiles. Ce que son métier ne fait pas, il le dit, sans action ; ")
                .append("s'il ne comprend pas, il demande de préciser. humeur : le ton de la réponse.");
        if (enFace && !repertoire.isEmpty()) {
            b.append("\nfaire : une action de son répertoire (type = \"faire\" et champ faire = son id ; un id du répertoire ne va jamais dans type ; cible = le prénom, le joueur ou l'objet visé s'il y en a un, sinon \"\" ; ")
                    .append("nombre = combien si le chef le dit, sinon 0 ; duree = en secondes si le chef la dit, sinon 0). Ce qu'il fait, il le fait vraiment : ")
                    .append("ne la choisis que si elle correspond à ce que le chef demande (« garde à vous » : faire = garde_a_vous ; « arrête de danser » : faire = annuler). ")
                    .append("Une question sur ce qu'il sait ou voit : l'action faire du répertoire qui donne ce renseignement, et la réponse dit le fait donné plus haut (« Ce qu'il sait »). ")
                    .append("Un petit geste (marqué *) peut accompagner une réponse sans qu'on le demande. Répertoire (id : ce qu'elle fait) :\n");
            for (Repertoire.Entree e : repertoire)
                b.append("- ").append(e.id()).append(e.spontane() ? "*" : "").append(" : ").append(e.nom())
                        .append(e.metiers().isEmpty() ? "" : " [" + String.join(", ", e.metiers().stream().map(m -> Metier.nom(m).toLowerCase()).toList()) + " seulement]").append('\n');
            for (Repertoire.Entree e : hors)
                b.append("(Personne ici ne sait « ").append(e.nom()).append(" » : c'est le travail d'un ")
                        .append(String.join(" ou d'un ", e.metiers().stream().map(m -> Metier.nom(m).toLowerCase()).toList())).append(".)\n");
        }
        return b.toString().strip();
    }

    /** Le PNJ seul, qui répond à une lettre de son chef. */
    static String systeme(Travailleur t, boolean enFace) {
        StringBuilder b = new StringBuilder();
        b.append("Tu es ").append(t.prenom).append(", ").append(Metier.nom(t.metier).toLowerCase()).append(" (").append(t.niveau)
                .append(" étoiles sur 5) au service de ton chef ").append(t.chefNom)
                .append(", dans La Colonie : un monde médiéval qui s'industrialise (Minecraft). Ton tempérament : ")
                .append(t.temperament).append(" (").append(TEMPERAMENTS.getOrDefault(t.temperament, "")).append(").\n")
                .append("Parle français, tutoie ton chef et appelle-le « chef ». ")
                .append(enFace ? "Réponds en une ou deux phrases courtes et naturelles, comme à l'oral, dans ton personnage. "
                        : "Ton chef t'a écrit : réponds par une courte lettre (2 à 4 phrases) avec ce que tu vas faire et de tes nouvelles (où tu es, ce que tu fais, comment tu vas), sans formule d'appel ni signature (elles sont ajoutées). ")
                .append(CORRECT).append("\n")
                .append("Ce que tu sais de toi : ").append(etat(t)).append(" Il est ").append(heure(t)).append(" h.\n")
                .append("Ton métier : ").append(Metier.description(t.metier)).append(" Missions possibles (id = nom) : ").append(catalogue(t)).append(".\n")
                .append(actions(enFace));
        String souvenirs = t.souvenirs.pourIA(t.jour(), heure(t));
        if (!souvenirs.isEmpty()) b.append("\nTes souvenirs : ").append(souvenirs).append('.');
        String echange = echange(t, 8);
        if (!echange.isEmpty()) b.append("\nVos derniers échanges :\n").append(echange);
        return b.toString();
    }

    /**
     * Le chef vient de parler à ces ouvriers (ses destinataires, déjà trouvés : {@link Adressage}) : l'IA répond et
     * agit pour chacun.
     */
    static String systemeGroupe(List<Travailleur> ouvriers, List<String> etiquettes, Travailleur vise, boolean ecrit) {
        return systemeGroupe(ouvriers, etiquettes, vise, ecrit, "", List.of(), List.of());
    }

    static String systemeGroupe(List<Travailleur> ouvriers, List<String> etiquettes, Travailleur vise, boolean ecrit, String parole,
                                List<Repertoire.Entree> repertoire, List<Repertoire.Entree> hors) {
        Travailleur t0 = ouvriers.get(0);
        net.minecraft.world.entity.player.Player chef = t0.chefJoueur();
        StringBuilder b = new StringBuilder();
        b.append("Tu fais parler les ouvriers de ").append(t0.chefNom.isEmpty() ? "leur chef" : t0.chefNom)
                .append(" dans La Colonie : un monde médiéval qui s'industrialise (Minecraft). Il est ").append(heure(t0)).append(" h. ")
                .append(ecrit && vise != null ? "Le chef vient d'écrire à " + etiquettes.get(ouvriers.indexOf(vise)) + " dans sa fenêtre de conversation ; "
                        : "Le chef vient de parler à voix haute, au micro ; ")
                .append(ouvriers.size() == 1 ? "voici l'ouvrier qui l'entend :\n" : "voici ceux de ses ouvriers qui l'entendent, du plus proche au plus loin :\n");
        for (int i = 0; i < ouvriers.size(); i++) {
            Travailleur t = ouvriers.get(i);
            b.append("- ").append(etiquettes.get(i)).append(" : ").append(Metier.nom(t.metier).toLowerCase()).append(", ").append(t.niveau)
                    .append(" étoiles, tempérament ").append(t.temperament).append(" (").append(TEMPERAMENTS.getOrDefault(t.temperament, "")).append(")");
            if (chef != null) b.append(", à ").append((int) Math.round(t.distanceTo(chef))).append(" blocs");
            if (t == vise) b.append(", le chef le regarde");
            b.append(". ").append(etat(t)).append(" Missions possibles de son métier (id = nom) : ").append(catalogue(t)).append('.');
            String e = echange(t, 4);
            String sv = t.souvenirs.pourIA(t.jour(), heure(t));
            if (!sv.isEmpty()) b.append(" Ses souvenirs : ").append(sv).append('.');
            if (!e.isEmpty()) b.append(" Derniers échanges avec le chef : ").append(e.replace("\n", " / ")).append('.');
            if (t.consigne != null && t.consigne.entree() != null) b.append(" En ce moment, à la demande du chef : ").append(t.consigne.entree().nom()).append('.');
            String faits = repertoire.isEmpty() ? "" : Consignes.faits(t, chef instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null, parole, repertoire);
            if (!faits.isEmpty()) b.append(" Ce qu'il sait : ").append(faits);
            b.append('\n');
        }
        b.append("Il s'adresse à eux tous (destinataires : tous ceux de la liste).\n");
        b.append("Puis reponses : une par destinataire au plus (qui = son nom, écrit comme ci-dessus). Chacun parle dans son personnage : en français, ")
                .append("il tutoie le chef et l'appelle « chef », en une ou deux phrases courtes et naturelles, comme à l'oral. À plusieurs, un ou deux seulement ")
                .append("prennent la parole, très brièvement ; les autres laissent reponse vide mais font quand même leur action. ")
                .append("Ne promets jamais ce que tes actions ne permettent pas : si on te demande autre chose, dis franchement que tu ne sais pas le faire. ")
                .append(CORRECT).append("\n")
                .append(actions(true, repertoire, hors));
        return b.toString();
    }

    /**
     * L'avis de l'IA sur une phrase : pour chaque ouvrier, la probabilité que le chef s'adresse à lui, et le lien
     * qu'elle voit (consigne courte, modèle rapide : {@code modele_ecoute}). Croisé ensuite avec celui du code.
     */
    static CompletableFuture<Adressage.Avis> ecouter(Adressage.Situation s) {
        if (!r().conversationPossible() || !ECOUTE.prendre(r().max_ecoutes_jour))
            return CompletableFuture.failedFuture(new IllegalStateException("conversation indisponible"));
        List<String> et = Adressage.etiquettes(s);
        String corps = ReponsesIA.requete(r().modele_ecoute, Adressage.consigne(s, et), "Donne la probabilité pour chaque ouvrier.",
                Adressage.schema(et), 0, 700);
        HttpRequest req = HttpRequest.newBuilder(URI.create(r().url_conversation)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + r().groq_cle).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        return envoyer(ECOUTE, req).thenApply(rep -> {
            Adressage.Avis a = Adressage.lireAvis(ReponsesIA.contenu(rep), et);
            ECOUTE.reussite(ReponsesIA.jetons(rep));
            return a;
        });
    }

    /** Une étiquette par ouvrier pour l'IA : son prénom (avec son métier si deux ouvriers ont le même). */
    static List<String> etiquettes(List<Travailleur> l) {
        List<String> r = new java.util.ArrayList<>();
        for (Travailleur t : l) {
            String e = l.stream().filter(x -> x.prenom.equals(t.prenom)).count() > 1 ? t.prenom + " le " + Metier.nom(t.metier).toLowerCase() : t.prenom;
            while (r.contains(e)) e += " bis";
            r.add(e);
        }
        return r;
    }

    /** Ce que répondent ces ouvriers à ce que le chef vient de dire devant eux (et à qui il parlait). À appeler sur le fil du serveur. */
    static CompletableFuture<ReponsesIA.Groupe> converserGroupe(List<Travailleur> ouvriers, Travailleur vise, boolean ecrit, String parole) {
        if (!conversationDisponible()) return CompletableFuture.failedFuture(new IllegalStateException("conversation indisponible"));
        List<String> etiquettes = etiquettes(ouvriers), tous = new java.util.ArrayList<>();
        Map<String, Set<String>> ids = new LinkedHashMap<>();
        for (int i = 0; i < ouvriers.size(); i++) {
            List<String> x = idsMissions(ouvriers.get(i));
            ids.put(etiquettes.get(i), Set.copyOf(x));
            x.forEach(id -> { if (!tous.contains(id)) tous.add(id); });
        }
        // Les actions du répertoire les plus proches de ce qu'il dit (que l'un d'eux sait faire), et les petits gestes.
        List<String> metiers = ouvriers.stream().map(t -> t.metier).distinct().toList();
        List<Repertoire.Entree> repertoire = Repertoire.candidats(parole, metiers, 14), hors = Repertoire.horsMetiers(parole, metiers);
        String corps = ReponsesIA.requeteConversation(r().modele_conversation, systemeGroupe(ouvriers, etiquettes, vise, ecrit, parole, repertoire, hors),
                "Le chef dit : « " + parole + " »", ReponsesIA.schemaGroupe(etiquettes, tous, repertoire.stream().map(Repertoire.Entree::id).toList()));
        HttpRequest req = requeteGroq(corps);
        java.util.function.Function<String, ReponsesIA.Groupe> lire = rep -> {
            ReponsesIA.Groupe g = ReponsesIA.decisionGroupe(ReponsesIA.contenu(rep), ids);
            // Un refus rattrapé sans rien d'utilisable compte comme un JSON raté : seconde chance.
            if (ReponsesIA.generationRatee(rep) != null && g.reponses().values().stream().allMatch(d -> d.reponse().isEmpty() && d.actions().isEmpty()))
                throw new CompletionException(new IllegalStateException("rattrapage vide (Failed to generate JSON)"));
            return g;
        };
        java.util.function.Function<String, ReponsesIA.Groupe> lireGroq = rep -> {
            ReponsesIA.Groupe g = lire.apply(rep);
            CONVERSATION.reussite(ReponsesIA.jetons(rep));
            return g;
        };
        // Groq rate parfois son JSON (« Failed to generate JSON ») : une seconde chance, puis OpenRouter s'il le peut, puis l'interpréteur.
        CompletableFuture<ReponsesIA.Groupe> groq = !r().conversationPossible() || !CONVERSATION.prendre(r().max_conversations_jour)
                ? CompletableFuture.failedFuture(new IllegalStateException("Groq indisponible"))
                : envoyer(CONVERSATION, req).thenApply(lireGroq).exceptionallyCompose(e -> String.valueOf(racine(e)).contains("generate JSON")
                && CONVERSATION.prendre(r().max_conversations_jour) ? envoyer(CONVERSATION, req).thenApply(lireGroq) : CompletableFuture.failedFuture(e));
        return groq.exceptionallyCompose(e -> secours(corps, lire, e));
    }

    /** Les derniers échanges avec son chef (gardés dans sa mémoire), pour qu'il suive la conversation. */
    static String echange(Travailleur t) { return echange(t, 8); }

    static String echange(Travailleur t, int n) {
        ListTag l = t.memoire.getList("conv", Tag.TAG_STRING);
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) b.append(l.getString(i)).append('\n');
        return b.toString().strip();
    }

    static void noter(Travailleur t, String qui, String texte) {
        ListTag l = t.memoire.getList("conv", Tag.TAG_STRING);
        l.add(StringTag.valueOf(qui + " : " + (texte.length() > 200 ? texte.substring(0, 200) + "…" : texte)));
        while (l.size() > 8) l.remove(0);
        t.memoire.put("conv", l);
    }

    // --- La transcription ----------------------------------------------------------------------------

    /** Ce qu'a dit un joueur (son en WAV), en texte. {@code indice} : des mots du jeu, pour aider Whisper. */
    static CompletableFuture<String> transcrire(byte[] wav, String indice) {
        if (!r().ecoutePossible() || !TRANSCRIPTION.prendre(r().max_transcriptions_jour))
            return CompletableFuture.failedFuture(new IllegalStateException("transcription indisponible"));
        String frontiere = "colonie" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("model", r().modele_transcription);
        champs.put("language", "fr");
        champs.put("response_format", "verbose_json"); // avec, pour chaque segment, la probabilité qu'il n'y ait pas de parole
        champs.put("temperature", "0");
        champs.put("prompt", indice);
        byte[] corps = ReponsesIA.formulaire(frontiere, champs, "voix.wav", "audio/wav", wav);
        HttpRequest req = HttpRequest.newBuilder(URI.create(r().url_transcription)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + r().groq_cle).header("Content-Type", "multipart/form-data; boundary=" + frontiere)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corps)).build();
        return envoyer(TRANSCRIPTION, req).thenApply(rep -> {
            TRANSCRIPTION.reussite(0);
            return ReponsesIA.transcription(rep);
        });
    }

    // --- La voix -----------------------------------------------------------------------------------------

    /** Les dernières phrases dites, pour ne pas redemander la même voix (les quotas de Gemini sont serrés). */
    static final Map<String, short[]> CACHE_VOIX = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, short[]> e) { return size() > 24; }
    });

    /** La phrase, dite par cette voix et sur ce ton : du son à 48 kHz, prêt pour Simple Voice Chat. */
    static CompletableFuture<short[]> voix(String texte, String voix, String style, float hauteur) {
        String cle = voix + "|" + style + "|" + hauteur + "|" + texte;
        short[] deja = CACHE_VOIX.get(cle);
        if (deja != null) return CompletableFuture.completedFuture(deja);
        if (!r().voixPossible() || !VOIX.prendre(r().max_voix_jour))
            return CompletableFuture.failedFuture(new IllegalStateException("voix indisponible"));
        return essayerVoix(modelesVoix(), 0, false, texte, voix, style).thenApply(octets -> {
            short[] s = enVoix(octets, hauteur);
            CACHE_VOIX.put(cle, s);
            VOIX.reussite(0);
            return s;
        }).whenComplete((v, e) -> {
            if (e != null && racineErreur(e) instanceof java.io.IOException io) VOIX.echec("injoignable — " + io, 15_000);
        });
    }

    /**
     * Le quota gratuit de Gemini se compte par modèle (une dizaine de phrases par jour pour chacun) : quand l'un est
     * épuisé, on passe au suivant. Ici, chaque modèle épuisé, et jusqu'à quand.
     */
    static final Map<String, Long> EPUISES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Les modèles qui refusent l'API « interactions » mais acceptent l'ancienne forme (découvert à leur premier refus). */
    static final Set<String> ANCIENS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Les modèles de voix encore disponibles aujourd'hui : le principal, puis ceux de secours. */
    static List<String> modelesVoix() {
        long t = System.currentTimeMillis();
        List<String> l = new java.util.ArrayList<>();
        java.util.stream.Stream.concat(java.util.stream.Stream.of(r().modele_voix), r().modeles_voix_secours.stream())
                .filter(m -> m != null && !m.isBlank() && !l.contains(m) && EPUISES.getOrDefault(m, 0L) <= t).forEach(l::add);
        return l;
    }

    /**
     * Demande la voix au modèle {@code i} : quota atteint, on passe au suivant ; refus de l'API « interactions », on
     * réessaie le même avec l'ancienne forme ; modèle inconnu, on passe au suivant. Rend le son tel que Gemini l'envoie.
     */
    static CompletableFuture<byte[]> essayerVoix(List<String> modeles, int i, boolean ancienneForcee, String texte, String voix, String style) {
        long t = System.currentTimeMillis();
        if (i >= modeles.size()) {
            long fin = EPUISES.values().stream().mapToLong(Long::longValue).filter(x -> x > t).min().orElse(t + 3_600_000);
            VOIX.echec("quota du jour atteint pour tous les modèles de voix — la voix revient dans "
                    + java.time.Duration.ofMillis(fin - t).toMinutes() + " min", fin - t);
            return CompletableFuture.failedFuture(new IllegalStateException("plus de modèle de voix disponible"));
        }
        String modele = modeles.get(i);
        boolean ancienne = ancienneForcee || ANCIENS.contains(modele) || r().url_voix.endsWith(":generateContent");
        return HTTP.sendAsync(requeteVoix(modele, texte, voix, style, ancienne), HttpResponse.BodyHandlers.ofString()).thenCompose(rep -> {
            int code = rep.statusCode();
            if (code / 100 == 2) {
                if (ancienneForcee) ANCIENS.add(modele);
                return CompletableFuture.completedFuture(ReponsesIA.audio(rep.body()));
            }
            String msg = "HTTP " + code + " : " + ReponsesIA.erreur(rep.body());
            if (code == 429) {
                long attente = ReponsesIA.attente(rep.headers().firstValue("retry-after").orElse(""), rep.body());
                EPUISES.put(modele, t + attente);
                ColoniePnj.LOG.info("IA (voix) : {} a atteint son quota, on passe au modèle suivant ({})", modele, msg);
                return essayerVoix(modeles, i + 1, false, texte, voix, style);
            }
            if ((code == 400 || code == 404) && !ancienne) return essayerVoix(modeles, i, true, texte, voix, style);
            if (code == 400 || code == 404) {
                EPUISES.put(modele, t + 86_400_000L); // modèle inconnu ou retiré : on n'y revient que demain
                VOIX.echec("modèle de voix " + modele + " refusé — " + msg, 0);
                return essayerVoix(modeles, i + 1, false, texte, voix, style);
            }
            if (code == 401 || code == 403) VOIX.echec("clé refusée — vérifie-la dans ia.json (" + msg + ")", 600_000);
            else VOIX.echec("le service a un problème — " + msg, 30_000);
            return CompletableFuture.failedFuture(new IllegalStateException(msg));
        });
    }

    /**
     * La requête à Gemini : l'API « interactions », ou l'ancienne forme (generateContent, le modèle dans l'adresse)
     * pour les modèles qui l'exigent, ou si url_voix se termine déjà par « :generateContent ».
     */
    static HttpRequest requeteVoix(String modele, String texte, String voix, String style, boolean ancienne) {
        String url = r().url_voix;
        if (ancienne && !url.endsWith(":generateContent")) url = url.substring(0, url.lastIndexOf('/')) + "/models/" + modele + ":generateContent";
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", r().gemini_cle).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ancienne ? ReponsesIA.requeteVoixAncienne(texte, voix, style)
                        : ReponsesIA.requeteVoix(modele, texte, voix, style))).build();
    }

    static Throwable racineErreur(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e;
    }

    /**
     * Du son de Gemini (WAV ou brut, 24 kHz) au son de Simple Voice Chat (48 kHz), sans les silences autour.
     * {@code hauteur} : sa variation de voix (1,05 : 5 % plus aiguë et plus rapide, 0,95 : plus grave et plus lente).
     */
    static short[] enVoix(byte[] octets, float hauteur) {
        Audio.Son s = Audio.lire(octets, 24_000);
        short[] r = Audio.rogner(s.echantillons(), s.frequence());
        return Audio.reechantillonner(r, Math.round(s.frequence() * hauteur), Audio.FREQ_VOIX);
    }

    // --- Pour le panneau et /pnj ia ----------------------------------------------------------------------

    static Map<String, Object> etat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reglages", r().public_());
        m.put("conversation", CONVERSATION.etat(r().max_conversations_jour));
        m.put("ecoute", ECOUTE.etat(r().max_ecoutes_jour));
        m.put("transcription", TRANSCRIPTION.etat(r().max_transcriptions_jour));
        m.put("voix", VOIX.etat(r().max_voix_jour));
        Map<String, Object> o = OPENROUTER.etat(r().max_openrouter_jour);
        o.put("depense_mois", DEPENSES.mois(aujourdhui()));
        o.put("depense_jour", DEPENSES.jour(aujourdhui()));
        o.put("budget_mois", r().budget_openrouter_mois);
        m.put("openrouter", o);
        m.put("simpleVoiceChat", Voix.pret);
        return m;
    }

    /** Essaie les deux clés pour de vrai (une petite demande chacune) ; le résultat en clair. */
    static CompletableFuture<List<String>> tester() {
        CompletableFuture<String> groq = r().groq_cle.isEmpty() ? CompletableFuture.completedFuture("Groq : pas de clé dans ia.json")
                : envoyer(CONVERSATION, HttpRequest.newBuilder(URI.create(r().url_conversation)).timeout(Duration.ofSeconds(25))
                        .header("Authorization", "Bearer " + r().groq_cle).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + r().modele_conversation + "\",\"max_completion_tokens\":40,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"Réponds juste : prêt.\"}]}")).build())
                .thenApply(rep -> { CONVERSATION.reprendre(); TRANSCRIPTION.reprendre(); return rep; })
                .thenApply(rep -> "Groq : OK (" + r().modele_conversation + " répond « " + ReponsesIA.contenu(rep).strip() + " »)")
                .exceptionally(e -> "Groq : ÉCHEC — " + racine(e));
        CompletableFuture<String> gemini = r().gemini_cle.isEmpty() ? CompletableFuture.completedFuture("Gemini : pas de clé dans ia.json")
                // Par le même relais que les vraies répliques : un modèle épuisé ne coupe pas la voix.
                : essayerVoix(modelesVoix(), 0, false, "Bonjour chef.", "Kore", "calm")
                .thenApply(octets -> {
                    VOIX.reprendre();
                    long t = System.currentTimeMillis();
                    List<String> epuises = EPUISES.entrySet().stream().filter(e -> e.getValue() > t).map(Map.Entry::getKey).sorted().toList();
                    return "Gemini : OK (" + String.format("%.1f", enVoix(octets, 1f).length / 48000.0) + " s de voix"
                            + (epuises.isEmpty() ? "" : " ; quota du jour atteint pour " + String.join(", ", epuises)) + ")";
                })
                .exceptionally(e -> "Gemini : ÉCHEC — " + racine(e));
        return groq.thenCombine(gemini, (a, b) -> List.of(a, b, openrouter(), "Simple Voice Chat : " + (Voix.pret ? "prêt" : "absent ou pas encore démarré")));
    }

    /** OpenRouter sans l'appeler (il est payant) : la clé, et ce qui reste du budget du mois. */
    static String openrouter() {
        ReglagesIA r = r();
        if (r.openrouter_cle.isEmpty()) return "OpenRouter : pas de clé dans ia.json (pas de conversation payante)";
        double d = DEPENSES.mois(aujourdhui());
        return String.format(java.util.Locale.ROOT, "OpenRouter : clé présente, non essayée (payant) ; %.3f $ dépensés ce mois sur %.2f $ (reste %.3f $) ; %d appel(s) aujourd'hui sur %d%s",
                d, r.budget_openrouter_mois, Math.max(0, r.budget_openrouter_mois * 0.95 - d), (int) OPENROUTER.etat(r.max_openrouter_jour).get("demandes"),
                r.max_openrouter_jour, openrouterDisponible() ? "" : " — indisponible pour l'instant (budget, quota ou pause)");
    }

    static String racine(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}

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

    static ReglagesIA r() { return ReglagesIA.actuel; }

    static boolean conversationDisponible() { return r().conversationPossible() && CONVERSATION.disponible(r().max_conversations_jour); }

    static boolean voixDisponible() { return r().voixPossible() && VOIX.disponible(r().max_voix_jour); }

    static boolean ecouteDisponible() { return r().ecoutePossible() && TRANSCRIPTION.disponible(r().max_transcriptions_jour); }

    // --- Appels HTTP -----------------------------------------------------------------------------------

    /** Envoie la requête ; en cas de refus, met le service en pause (le temps demandé, ou un délai raisonnable) et échoue. */
    static CompletableFuture<String> envoyer(Service s, HttpRequest req) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(rep -> {
            int code = rep.statusCode();
            if (code / 100 == 2) return rep.body();
            String msg = "HTTP " + code + " : " + ReponsesIA.erreur(rep.body());
            long attente = rep.headers().firstValue("retry-after").map(v -> { try { return (long) (Double.parseDouble(v) * 1000); } catch (NumberFormatException e) { return 60_000L; } }).orElse(-1L);
            if (code == 429) s.echec("trop de demandes (quota du fournisseur) — " + msg, attente > 0 ? Math.min(attente, 3_600_000) : 120_000);
            else if (code == 401 || code == 403) s.echec("clé refusée — vérifie-la dans ia.json (" + msg + ")", 600_000);
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
        if (!r().conversationPossible() || !CONVERSATION.prendre(r().max_conversations_jour))
            return CompletableFuture.failedFuture(new IllegalStateException("conversation indisponible"));
        Metier m = Metier.de(t.metier);
        List<String> ids = m == null ? List.of() : m.commandes.stream().map(c -> c.id).toList();
        Set<String> idsSet = Set.copyOf(ids);
        String corps = ReponsesIA.requeteConversation(r().modele_conversation, systeme(t, enFace), (enFace ? "Ton chef te dit : « " : "Lettre de ton chef : « ") + parole + " »",
                ReponsesIA.schema(ids, enFace));
        HttpRequest req = HttpRequest.newBuilder(URI.create(r().url_conversation)).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + r().groq_cle).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        return envoyer(CONVERSATION, req).thenApply(rep -> {
            ReponsesIA.Decision d = ReponsesIA.decision(ReponsesIA.contenu(rep), idsSet, enFace);
            CONVERSATION.reussite(ReponsesIA.jetons(rep));
            return d;
        });
    }

    static final Map<String, String> TEMPERAMENTS = Map.of(
            "calme", "posé, patient, fiable ; tu parles simplement",
            "peureux", "craintif, prudent, tu t'inquiètes vite et tu le montres",
            "fourbe", "rusé et flatteur, un brin malhonnête mais jamais ouvertement déloyal",
            "colerique", "bourru, prompt à s'emporter, mais tu restes au service de ton chef");

    /** Qui il est et ce qu'il sait, en quelques lignes (le moins de jetons possible : le quota gratuit en compte 200 000 par jour). */
    static String systeme(Travailleur t, boolean enFace) {
        Metier m = Metier.de(t.metier);
        String catalogue = m == null ? "" : m.commandes.stream().map(c -> c.id + " = " + c.nom).collect(Collectors.joining(", "));
        String missions = t.missions.isEmpty() ? "aucune" : t.missions.stream().map(x -> Metier.avancementTexte(t, x)).collect(Collectors.joining(" ; "));
        int heure = (int) ((t.level().getDayTime() + 6000) % 24000 / 1000);
        StringBuilder b = new StringBuilder();
        b.append("Tu es ").append(t.prenom).append(", ").append(Metier.nom(t.metier).toLowerCase()).append(" (").append(t.niveau)
                .append(" étoiles sur 5) au service de ton chef ").append(t.chefNom.isEmpty() ? "" : t.chefNom)
                .append(", dans La Colonie : un monde médiéval qui s'industrialise (Minecraft). Ton tempérament : ")
                .append(t.temperament).append(" (").append(TEMPERAMENTS.getOrDefault(t.temperament, "")).append(").\n")
                .append("Parle français, tutoie ton chef et appelle-le « chef ». ")
                .append(enFace ? "Réponds en une ou deux phrases courtes et naturelles, comme à l'oral, dans ton personnage. "
                        : "Ton chef t'a écrit : réponds par une courte lettre (2 à 4 phrases) avec ce que tu vas faire et de tes nouvelles (où tu es, ce que tu fais, comment tu vas), sans formule d'appel ni signature (elles sont ajoutées). ")
                .append("Ne dis jamais que tu es une IA ou un programme, ne parle pas du monde réel, reste correct (rien de choquant), même en colère.\n")
                .append("Ce que tu sais de toi : ").append(t.statut()).append(" ; ").append(t.activite()).append(" Tu es ").append(Messages.ou(t)).append(". ")
                .append("Santé ").append((int) Math.ceil(t.getHealth())).append("/").append((int) t.getMaxHealth()).append(", faim ").append(t.faim)
                .append("/20, fatigue ").append(t.fatigue).append("/100. Missions : ").append(missions).append(". Horaires : de ").append(t.debut)
                .append(" h à ").append(t.fin).append(" h ; expéditions de ").append(t.joursExpedition).append(" jours au plus. Salaire : ")
                .append(Reglages.texte(t.salaireVerse())).append(" par paie").append(t.impayes > 0 ? ", " + t.impayes + " paie(s) en retard" : "")
                .append(t.besoin.isEmpty() ? "" : ". Il te manque : " + t.besoin).append(". Il est ").append(heure).append(" h.\n")
                .append("Ton métier : ").append(Metier.description(t.metier)).append(" Missions possibles (id = nom) : ").append(catalogue).append(".\n")
                .append("Actions (champ actions, seulement si le chef le demande clairement ; sinon liste vide) : ");
        if (enFace) b.append("suivre (le suivre) ; arreter_suivre (cesser de le suivre et retourner au travail) ; attendre (rester sur place) ; montrer_sac ; ")
                .append("renvoyer (seulement s'il te renvoie clairement) ; ");
        b.append("travailler (retourner au travail) ; rentrer (revenir au poste de travail) ; missions (missions : id parmi les possibles, mode quantite = en rapporter nombre puis arrêter, ")
                .append("ou stock = en garder nombre au dépôt en permanence ; ajouter = vrai s'il dit « aussi » ou « en plus », sinon elles remplacent les anciennes) ; ")
                .append("horaires (debut et fin, de 0 à 23 ; même heure = toute la journée) ; expedition (jours : 1 à 30). ")
                .append("Dans une action, mets 0, faux ou [] aux champs inutiles. Si on te demande ce que ton métier ne fait pas, dis-le sans action ; ")
                .append("si tu ne comprends pas, demande de préciser. humeur : le ton de ta réponse.");
        String echange = echange(t);
        if (!echange.isEmpty()) b.append("\nVos derniers échanges :\n").append(echange);
        return b.toString();
    }

    /** Les derniers échanges avec son chef (gardés dans sa mémoire), pour qu'il suive la conversation. */
    static String echange(Travailleur t) {
        ListTag l = t.memoire.getList("conv", Tag.TAG_STRING);
        StringBuilder b = new StringBuilder();
        for (Tag x : l) b.append(x.getAsString()).append('\n');
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
        champs.put("response_format", "json");
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
    static CompletableFuture<short[]> voix(String texte, String voix, String style) {
        String cle = voix + "|" + style + "|" + texte;
        short[] deja = CACHE_VOIX.get(cle);
        if (deja != null) return CompletableFuture.completedFuture(deja);
        if (!r().voixPossible() || !VOIX.prendre(r().max_voix_jour))
            return CompletableFuture.failedFuture(new IllegalStateException("voix indisponible"));
        return envoyer(VOIX, requeteVoix(texte, voix, style)).thenApply(rep -> {
            short[] s = enVoix(ReponsesIA.audio(rep));
            CACHE_VOIX.put(cle, s);
            VOIX.reussite(0);
            return s;
        });
    }

    /**
     * La requête à Gemini. Par défaut l'API « interactions » ; si url_voix se termine par « :generateContent »
     * (un ancien modèle de voix, le modèle est alors dans l'adresse), l'ancienne forme.
     */
    static HttpRequest requeteVoix(String texte, String voix, String style) {
        boolean ancienne = r().url_voix.endsWith(":generateContent");
        return HttpRequest.newBuilder(URI.create(r().url_voix)).timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", r().gemini_cle).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ancienne ? ReponsesIA.requeteVoixAncienne(texte, voix, style)
                        : ReponsesIA.requeteVoix(r().modele_voix, texte, voix, style))).build();
    }

    /** Du son de Gemini (WAV ou brut, 24 kHz) au son de Simple Voice Chat (48 kHz), sans les silences autour. */
    static short[] enVoix(byte[] octets) {
        Audio.Son s = Audio.lire(octets, 24_000);
        short[] r = Audio.rogner(s.echantillons(), s.frequence());
        return Audio.reechantillonner(r, s.frequence(), Audio.FREQ_VOIX);
    }

    // --- Pour le panneau et /pnj ia ----------------------------------------------------------------------

    static Map<String, Object> etat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reglages", r().public_());
        m.put("conversation", CONVERSATION.etat(r().max_conversations_jour));
        m.put("transcription", TRANSCRIPTION.etat(r().max_transcriptions_jour));
        m.put("voix", VOIX.etat(r().max_voix_jour));
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
                : envoyer(VOIX, requeteVoix("Bonjour chef.", "Kore", "calm"))
                .thenApply(rep -> { VOIX.reprendre(); return rep; })
                .thenApply(rep -> "Gemini : OK (" + r().modele_voix + ", " + String.format("%.1f", IA.enVoix(ReponsesIA.audio(rep)).length / 48000.0) + " s de voix)")
                .exceptionally(e -> "Gemini : ÉCHEC — " + racine(e));
        return groq.thenCombine(gemini, (a, b) -> List.of(a, b, "Simple Voice Chat : " + (Voix.pret ? "prêt" : "absent ou pas encore démarré")));
    }

    static String racine(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage();
    }
}

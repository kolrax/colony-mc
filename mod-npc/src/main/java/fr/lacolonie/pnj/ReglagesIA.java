package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les réglages de l'intelligence artificielle, dans config/colonie_pnj/ia.json (à part des autres réglages : ce
 * fichier contient les clés d'accès, que le panneau web ne renvoie jamais).
 * <ul>
 * <li>Groq : la conversation (un modèle de langage qui répond et choisit l'action) et la transcription de la voix (Whisper).</li>
 * <li>Gemini : la voix des PNJ (synthèse vocale).</li>
 * </ul>
 * Sans clé, ou quand un quota du jour est atteint, les PNJ retombent sur l'interpréteur sans IA et les bulles.
 */
public class ReglagesIA {
    /** Interrupteur général. */
    public boolean active = true;
    /** La conversation écrite et les lettres passent par l'IA. */
    public boolean conversation = true;
    /** Les PNJ répondent à voix haute (Gemini, via Simple Voice Chat). */
    public boolean voix = true;
    /** On peut leur parler au micro (Simple Voice Chat, transcription par Groq). */
    public boolean ecoute = true;

    public String groq_cle = "";
    public String gemini_cle = "";

    /** Adresse compatible OpenAI : on peut changer de fournisseur sans toucher au mod (Groq par défaut). */
    public String url_conversation = "https://api.groq.com/openai/v1/chat/completions";
    public String modele_conversation = "openai/gpt-oss-120b";
    /** Le modèle qui juge à qui le chef parle (une consigne courte : un modèle rapide suffit, et son quota est à part). */
    public String modele_ecoute = "openai/gpt-oss-20b";
    public String url_transcription = "https://api.groq.com/openai/v1/audio/transcriptions";
    public String modele_transcription = "whisper-large-v3-turbo";
    public String url_voix = "https://generativelanguage.googleapis.com/v1beta/interactions";
    public String modele_voix = "gemini-3.8-flash-tts";
    /** Quand le quota gratuit du modèle de voix est atteint (il se compte par modèle), ceux-ci prennent le relais, dans l'ordre. */
    public java.util.List<String> modeles_voix_secours = new java.util.ArrayList<>(DEFAUT_SECOURS);
    static final java.util.List<String> DEFAUT_SECOURS = java.util.List.of("gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts");

    /** Garde-fous, sous les quotas gratuits (Groq : 1 000 conversations et 2 000 transcriptions par jour). */
    public int max_conversations_jour = 900;
    /** Les avis d'écoute (à qui parle le chef) : un autre modèle, sous son propre quota gratuit (1 000 demandes par jour). */
    public int max_ecoutes_jour = 900;
    public int max_transcriptions_jour = 1800;
    public int max_voix_jour = 200;
    /** À combien de blocs de son ouvrier (et en le regardant) on peut lui parler au micro. */
    public double distance_ecoute = 6;

    public static ReglagesIA actuel = new ReglagesIA();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static Path fichier() { return Reglages.dossier().resolve("ia.json"); }

    /** Lit ia.json (le crée vide au premier démarrage, prêt à recevoir les clés). */
    public static synchronized void charger() {
        try {
            Files.createDirectories(Reglages.dossier());
            ReglagesIA r = Files.exists(fichier()) ? GSON.fromJson(Files.readString(fichier(), StandardCharsets.UTF_8), ReglagesIA.class) : null;
            if (r == null) r = new ReglagesIA();
            r.valider();
            actuel = r;
            sauver();
            // Les clés ont peut-être été corrigées : les services refusés jusqu'ici ont droit à un nouvel essai.
            IA.CONVERSATION.reprendre();
            IA.TRANSCRIPTION.reprendre();
            IA.VOIX.reprendre();
        } catch (Exception e) {
            ColoniePnj.LOG.error("Réglages de l'IA illisibles (config/colonie_pnj/ia.json) : IA désactivée jusqu'à correction", e);
            ReglagesIA r = new ReglagesIA();
            r.active = false;
            actuel = r;
        }
    }

    public static synchronized void sauver() throws IOException {
        Files.writeString(fichier(), GSON.toJson(actuel), StandardCharsets.UTF_8);
    }

    void valider() {
        groq_cle = groq_cle == null ? "" : groq_cle.strip();
        gemini_cle = gemini_cle == null ? "" : gemini_cle.strip();
        if (url_conversation == null || url_conversation.isBlank()) url_conversation = new ReglagesIA().url_conversation;
        if (modele_conversation == null || modele_conversation.isBlank()) modele_conversation = new ReglagesIA().modele_conversation;
        if (modele_ecoute == null || modele_ecoute.isBlank()) modele_ecoute = new ReglagesIA().modele_ecoute;
        if (url_transcription == null || url_transcription.isBlank()) url_transcription = new ReglagesIA().url_transcription;
        if (modele_transcription == null || modele_transcription.isBlank()) modele_transcription = new ReglagesIA().modele_transcription;
        if (url_voix == null || url_voix.isBlank()) url_voix = new ReglagesIA().url_voix;
        if (modele_voix == null || modele_voix.isBlank()) modele_voix = new ReglagesIA().modele_voix;
        if (modeles_voix_secours == null) modeles_voix_secours = new java.util.ArrayList<>(DEFAUT_SECOURS);
        max_conversations_jour = Math.max(0, max_conversations_jour);
        max_ecoutes_jour = Math.max(0, max_ecoutes_jour);
        max_transcriptions_jour = Math.max(0, max_transcriptions_jour);
        max_voix_jour = Math.max(0, max_voix_jour);
        distance_ecoute = Math.clamp(distance_ecoute, 2, 16);
    }

    boolean conversationPossible() { return active && conversation && !groq_cle.isEmpty(); }

    boolean voixPossible() { return active && voix && !gemini_cle.isEmpty(); }

    boolean ecoutePossible() { return active && ecoute && !groq_cle.isEmpty(); }

    /** Ce que le panneau affiche : tout sauf les clés (seulement si elles sont là, et leurs 4 derniers caractères). */
    Map<String, Object> public_() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("conversation", conversation);
        m.put("voix", voix);
        m.put("ecoute", ecoute);
        m.put("groq_cle", masque(groq_cle));
        m.put("gemini_cle", masque(gemini_cle));
        m.put("modele_conversation", modele_conversation);
        m.put("modele_ecoute", modele_ecoute);
        m.put("modele_transcription", modele_transcription);
        m.put("modele_voix", modele_voix);
        m.put("modeles_voix_secours", String.join(", ", modeles_voix_secours));
        m.put("max_conversations_jour", max_conversations_jour);
        m.put("max_ecoutes_jour", max_ecoutes_jour);
        m.put("max_transcriptions_jour", max_transcriptions_jour);
        m.put("max_voix_jour", max_voix_jour);
        m.put("distance_ecoute", distance_ecoute);
        return m;
    }

    static String masque(String cle) { return cle == null || cle.isEmpty() ? "" : "…" + cle.substring(Math.max(0, cle.length() - 4)); }

    /** Le panneau change les interrupteurs, modèles et quotas ; jamais les clés (elles ne passent pas par lui). */
    static synchronized void modifier(com.google.gson.JsonObject c) throws IOException {
        ReglagesIA r = actuel;
        if (c.has("active")) r.active = c.get("active").getAsBoolean();
        if (c.has("conversation")) r.conversation = c.get("conversation").getAsBoolean();
        if (c.has("voix")) r.voix = c.get("voix").getAsBoolean();
        if (c.has("ecoute")) r.ecoute = c.get("ecoute").getAsBoolean();
        if (c.has("modele_conversation")) r.modele_conversation = c.get("modele_conversation").getAsString();
        if (c.has("modele_ecoute")) r.modele_ecoute = c.get("modele_ecoute").getAsString();
        if (c.has("modele_transcription")) r.modele_transcription = c.get("modele_transcription").getAsString();
        if (c.has("modele_voix")) r.modele_voix = c.get("modele_voix").getAsString();
        if (c.has("modeles_voix_secours")) r.modeles_voix_secours = new java.util.ArrayList<>(java.util.Arrays.stream(c.get("modeles_voix_secours").getAsString()
                .split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList());
        if (c.has("max_conversations_jour")) r.max_conversations_jour = c.get("max_conversations_jour").getAsInt();
        if (c.has("max_ecoutes_jour")) r.max_ecoutes_jour = c.get("max_ecoutes_jour").getAsInt();
        if (c.has("max_transcriptions_jour")) r.max_transcriptions_jour = c.get("max_transcriptions_jour").getAsInt();
        if (c.has("max_voix_jour")) r.max_voix_jour = c.get("max_voix_jour").getAsInt();
        if (c.has("distance_ecoute")) r.distance_ecoute = c.get("distance_ecoute").getAsDouble();
        r.valider();
        sauver();
    }
}

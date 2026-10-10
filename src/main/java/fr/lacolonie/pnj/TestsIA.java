package fr.lacolonie.pnj;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Tests de jeu de l'IA, contre un faux Groq et un faux Gemini (un petit serveur local qui répond comme eux) :
 * la conversation, les lettres, le micro, la voix, le retour à l'interpréteur quand le service échoue, et
 * Simple Voice Chat (chargé dans les essais). Lot à part : les clés de test ne servent qu'à ces tests-là.
 */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsIA {
    static final String CLE_GROQ = "gsk_test_0000000000001234", CLE_GEMINI = "AIza_test_00000005678";
    static HttpServer faux;
    static ReglagesIA avant;

    @BeforeBatch(batch = "ia")
    public static void avant(ServerLevel l) {
        try {
            faux = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        faux.createContext("/chat", x -> repondre(x, chat(x)));
        faux.createContext("/whisper", x -> repondre(x, whisper(x)));
        faux.createContext("/voix", x -> repondre(x, voix(x)));
        faux.createContext("/models/", x -> repondre(x, voixAncienne(x)));
        faux.start();
        String base = "http://127.0.0.1:" + faux.getAddress().getPort();
        ReglagesIA r = new ReglagesIA();
        r.groq_cle = CLE_GROQ;
        r.gemini_cle = CLE_GEMINI;
        r.url_conversation = base + "/chat";
        r.url_transcription = base + "/whisper";
        r.url_voix = base + "/voix";
        avant = ReglagesIA.actuel;
        ReglagesIA.actuel = r; // en mémoire seulement : rien n'est écrit dans ia.json
    }

    @AfterBatch(batch = "ia")
    public static void apres(ServerLevel l) {
        ReglagesIA.actuel = avant;
        faux.stop(0);
    }

    record Reponse(int code, String corps) {}

    static void repondre(HttpExchange x, Reponse r) throws IOException {
        byte[] b = r.corps().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(r.code(), b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    static Reponse erreur(int code, String message) { return new Reponse(code, "{\"error\":{\"message\":\"" + message + "\"}}"); }

    /** Le faux Groq : vérifie la requête comme le vrai, puis décide selon ce que dit le chef. */
    static Reponse chat(HttpExchange x) throws IOException {
        if (!("Bearer " + CLE_GROQ).equals(x.getRequestHeaders().getFirst("Authorization"))) return erreur(401, "Invalid API Key");
        JsonObject req = JsonParser.parseString(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!req.getAsJsonObject("response_format").get("type").getAsString().equals("json_schema")) return erreur(400, "schéma attendu");
        String systeme = req.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        String parole = req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        JsonObject schema = req.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema");
        boolean ecoute = schema.getAsJsonObject("properties").has("ouvriers");
        if (!ecoute && !systeme.contains("Missions possibles")) return erreur(400, "pas de catalogue");
        if (parole.contains("PANNE") || systeme.contains("PANNE")) return erreur(400, "The model does not exist");
        String decision;
        if (ecoute) decision = ecoute(schema, systeme);
        else if (schema.getAsJsonObject("properties").has("destinataires")) decision = groupe(schema, systeme, parole);
        else if (parole.contains("pioches"))
            decision = decision("Compris chef, je te ramène dix lingots de fer pour tes pioches.", "fier",
                    "{\"type\":\"missions\",\"missions\":[{\"id\":\"fer\",\"mode\":\"quantite\",\"nombre\":10}],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}");
        else if (parole.contains("Suis-moi"))
            decision = decision("J'arrive, chef, je te suis !", "joyeux",
                    "{\"type\":\"suivre\",\"missions\":[],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}");
        else if (parole.startsWith("Lettre de ton chef"))
            decision = decision("Bien reçu, chef. Je rentre au poste et je garderai trente-deux charbons au dépôt.", "calme",
                    "{\"type\":\"missions\",\"missions\":[{\"id\":\"charbon\",\"mode\":\"stock\",\"nombre\":32}],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}");
        else decision = decision("Oui chef.", "calme", "");
        JsonObject message = new JsonObject(), choix = new JsonObject(), rep = new JsonObject(), usage = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", decision);
        choix.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choix);
        rep.add("choices", choices);
        usage.addProperty("total_tokens", 321);
        rep.add("usage", usage);
        return new Reponse(200, rep.toString());
    }

    /**
     * Le faux Groq, quand le chef parle devant plusieurs ouvriers : il lit les présents dans le schéma (leurs
     * étiquettes) et leur métier dans la consigne, puis décide comme le ferait le vrai.
     */
    static String groupe(JsonObject schema, String systeme, String parole) {
        java.util.List<String> qui = new java.util.ArrayList<>();
        schema.getAsJsonObject("properties").getAsJsonObject("reponses").getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("qui").getAsJsonArray("enum").forEach(e -> qui.add(e.getAsString()));
        java.util.Map<String, String> metier = new java.util.HashMap<>();
        for (String q : qui) {
            int i = systeme.indexOf("- " + q + " : ");
            if (i >= 0) metier.put(q, systeme.substring(i + q.length() + 5).split(",")[0]);
        }
        JsonArray dest = new JsonArray(), reps = new JsonArray();
        if (parole.contains("protège la base")) {
            for (String q : qui) if ("guerrier".equals(metier.get(q))) {
                dest.add(q);
                reps.add(rep(q, "J'y vais, chef : personne ne passera.", "fier",
                        "{\"type\":\"missions\",\"missions\":[{\"id\":\"garder\",\"mode\":\"stock\",\"nombre\":1}],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}"));
            }
        } else if (parole.contains("Les gars")) {
            for (int i = 0; i < qui.size(); i++) {
                dest.add(qui.get(i));
                reps.add(rep(qui.get(i), i == 0 ? "On te suit, chef !" : "", "joyeux", "{\"type\":\"suivre\",\"missions\":[],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}"));
            }
        } else if (parole.contains("pioches")) {
            dest.add(qui.get(0));
            reps.add(rep(qui.get(0), "Compris chef, je te ramène dix lingots de fer pour tes pioches.", "fier",
                    "{\"type\":\"missions\",\"missions\":[{\"id\":\"fer\",\"mode\":\"quantite\",\"nombre\":10}],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}"));
        } else if (parole.contains("Suis-moi")) {
            dest.add(qui.get(0));
            reps.add(rep(qui.get(0), "J'arrive, chef, je te suis !", "joyeux", "{\"type\":\"suivre\",\"missions\":[],\"ajouter\":false,\"debut\":0,\"fin\":0,\"jours\":0}"));
        } else if (!parole.contains("chanson")) {
            dest.add(qui.get(0));
            reps.add(rep(qui.get(0), "Oui chef.", "calme", ""));
        }
        JsonObject d = new JsonObject();
        d.add("destinataires", dest);
        d.add("reponses", reps);
        return d.toString();
    }

    /**
     * Le faux avis d'écoute : la phrase est dans la consigne, entre « » ; chaque ouvrier y a sa ligne (« - Raoul : guerrier (… »),
     * avec « le chef le regarde » s'il le regarde. Il répond comme le ferait le vrai, en plus simple.
     */
    static String ecoute(JsonObject schema, String systeme) {
        java.util.List<String> qui = new java.util.ArrayList<>();
        for (String ligne : systeme.split("\n")) if (ligne.startsWith("- ") && ligne.contains(" : ")) qui.add(ligne.substring(2, ligne.indexOf(" : ")));
        String texte = systeme.substring(systeme.indexOf("« ") + 2, systeme.indexOf(" »"));
        JsonArray l = new JsonArray();
        for (int i = 0; i < qui.size(); i++) {
            String q = qui.get(i);
            String ligne = systeme.substring(systeme.indexOf("- " + q + " : "));
            ligne = ligne.substring(0, ligne.indexOf('\n'));
            int p;
            if (texte.contains("chanson") || texte.contains("fredonne") || texte.contains("chômeur") || texte.contains("lent") || texte.contains("je mine")) p = 3;
            else if (texte.startsWith(q)) p = 95;
            else if (qui.stream().anyMatch(texte::startsWith)) p = 5;
            else if (texte.contains("protège la base")) p = ligne.contains(": guerrier") ? 92 : 4;
            else if (texte.contains("Les gars")) p = 90;
            else if (ligne.contains("le chef le regarde")) p = 85;
            else p = i == 0 ? 75 : 6;
            JsonObject o = new JsonObject();
            o.addProperty("qui", q);
            o.addProperty("lien", p > 50 ? "la phrase s'adresse à lui" : "aucun lien avec lui");
            o.addProperty("probabilite", p);
            l.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("ouvriers", l);
        return r.toString();
    }

    static JsonObject rep(String qui, String reponse, String humeur, String action) {
        JsonObject r = JsonParser.parseString(decision(reponse, humeur, action)).getAsJsonObject();
        r.addProperty("qui", qui);
        return r;
    }

    static String decision(String reponse, String humeur, String action) {
        JsonObject d = new JsonObject();
        d.addProperty("reponse", reponse);
        d.addProperty("humeur", humeur);
        d.add("actions", JsonParser.parseString("[" + action + "]"));
        return d.toString();
    }

    /** Le faux Whisper : un formulaire avec le modèle, la langue et un vrai WAV. */
    static Reponse whisper(HttpExchange x) throws IOException {
        if (!("Bearer " + CLE_GROQ).equals(x.getRequestHeaders().getFirst("Authorization"))) return erreur(401, "Invalid API Key");
        String corps = new String(x.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        if (!corps.contains("name=\"model\"") || !corps.contains("\r\n\r\nfr\r\n") || !corps.contains("RIFF") || !corps.contains("filename=\"voix.wav\"")
                || !corps.contains("\r\n\r\nverbose_json\r\n"))
            return erreur(400, "formulaire incomplet");
        return new Reponse(200, "{\"text\":\" Sous-titrage ST' 501 Suis-moi, s'il te plaît.\",\"segments\":["
                + "{\"id\":0,\"text\":\" Sous-titrage ST' 501\",\"avg_logprob\":-1.4,\"compression_ratio\":1.1,\"no_speech_prob\":0.93},"
                + "{\"id\":1,\"text\":\" Suis-moi, s'il te plaît.\",\"avg_logprob\":-0.21,\"compression_ratio\":0.9,\"no_speech_prob\":0.01}],"
                + "\"x_groq\":{\"id\":\"req_1\"}}");
    }

    /** Le faux Gemini : une demi-seconde de silence puis une seconde de « voix » (un son pur), en WAV 24 kHz. */
    static Reponse voix(HttpExchange x) throws IOException {
        if (!CLE_GEMINI.equals(x.getRequestHeaders().getFirst("x-goog-api-key"))) return erreur(403, "API key not valid");
        String corps = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!corps.contains("speech_config") || !corps.contains("\"store\":false")) return erreur(400, "requête de voix invalide");
        String modele = JsonParser.parseString(corps).getAsJsonObject().get("model").getAsString();
        // Comme le vrai : un modèle qui n'accepte que l'ancienne forme, et un quota gratuit épuisé (par modèle).
        if (modele.equals("gemini-3.8-flash-lite-tts")) return erreur(400, "Model does not support the Interactions API");
        if (corps.contains("QUOTA") && modele.equals(ReglagesIA.actuel.modele_voix))
            return new Reponse(429, "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded for model " + modele + " (limit: 10 requests per day on Free Tier). Please retry in 2s.\","
                    + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"2s\"}]}}");
        String b64 = Base64.getEncoder().encodeToString(Audio.ecrire(sonGemini(), 24_000));
        return new Reponse(200, "{\"id\":\"v1\",\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\""
                + b64 + "\"}]}]}");
    }

    /** Une demi-seconde de silence puis une seconde de « voix » (un son pur), à 24 kHz. */
    static short[] sonGemini() {
        short[] s = new short[36_000];
        for (int i = 12_000; i < s.length; i++) s[i] = (short) (7000 * Math.sin(2 * Math.PI * 220 * i / 24_000.0));
        return s;
    }

    /** L'ancienne forme (generateContent, le modèle dans l'adresse) : du PCM brut dans inlineData. */
    static Reponse voixAncienne(HttpExchange x) throws IOException {
        if (!CLE_GEMINI.equals(x.getRequestHeaders().getFirst("x-goog-api-key"))) return erreur(403, "API key not valid");
        String corps = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!x.getRequestURI().getPath().endsWith(":generateContent") || !corps.contains("voiceName") || !corps.contains("AUDIO"))
            return erreur(400, "requête de voix invalide");
        short[] s = sonGemini();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(s.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (short v : s) b.putShort(v);
        return new Reponse(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/L16;codec=pcm;rate=24000\",\"data\":\""
                + Base64.getEncoder().encodeToString(b.array()) + "\"}}],\"role\":\"model\"}}]}");
    }

    /** Le quota gratuit du modèle de voix est épuisé : le suivant prend le relais (ici par l'ancienne forme), sans couper la voix. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaVoixRelais(GameTestHelper h) {
        // Directement le relais (les autres tests du lot ont pu épuiser la limite de 8 voix par minute que le mod s'impose).
        CompletableFuture<byte[]> f = IA.essayerVoix(IA.modelesVoix(), 0, false, "Avec ce QUOTA, chef, j'attends mon tour.", "Puck", "calm");
        h.succeedWhen(() -> {
            h.assertTrue(f.isDone() && !f.isCompletedExceptionally(), "la voix doit venir d'un autre modèle");
            int n = IA.enVoix(f.join(), 1f).length;
            h.assertTrue(Math.abs(n - 50_400) < 2_500, "longueur " + n);
            h.assertTrue(IA.EPUISES.containsKey(ReglagesIA.actuel.modele_voix), "le modèle épuisé est noté");
            h.assertTrue(IA.ANCIENS.contains("gemini-3.8-flash-lite-tts"), "le modèle de secours passe par l'ancienne forme");
            h.assertTrue((long) IA.VOIX.etat(9999).get("pause") == 0, "la voix n'est pas coupée");
        });
    }

    static short[] son(int n, int amplitude) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (amplitude * Math.sin(2 * Math.PI * 330 * i / 48_000.0));
        return s;
    }

    static void sol(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
    }

    /** On lui parle : l'IA comprend ce que l'interpréteur ne comprendrait pas, répond à sa place et choisit sa mission. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaConversation(GameTestHelper h) {
        h.assertTrue(ReglagesIA.actuel.url_conversation.startsWith("http://127.0.0.1"), "les tests de l'IA doivent tourner contre le faux service");
        sol(h);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY);
        t.chef = chef.getUUID();
        String phrase = "Il me faudrait de quoi faire des pioches, tu vois ?";
        h.assertTrue(Ordres.lire(phrase, "mineur").missions.isEmpty(), "l'interpréteur seul ne doit rien y comprendre");
        t.parler(chef, phrase);
        h.assertTrue(t.reflechit, "il doit attendre la réponse de l'IA");
        t.parler(chef, "Suis-moi");
        h.assertTrue(t.mode == Travailleur.Mode.TRAVAIL, "pendant qu'il réfléchit, une deuxième demande attend");
        h.succeedWhen(() -> {
            h.assertFalse(t.reflechit, "réponse attendue");
            h.assertTrue(t.missions.size() == 1 && t.missions.get(0).id.equals("fer") && t.missions.get(0).nombre == 10, "mission choisie par l'IA : " + t.missions.size());
            h.assertTrue(IA.echange(t).contains("pour tes pioches") && IA.echange(t).contains("Chef : " + phrase), "la conversation doit être retenue : " + IA.echange(t));
            t.discard();
        });
    }

    /** Une lettre : l'IA la comprend et écrit la réponse, envoyée comme les autres lettres. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaLettre(GameTestHelper h) {
        BlockPos bureau = TestsJeu.sol(h, 0, 39000);
        Travailleur t = TestsJeu.employe(h, "mineur", bureau, Vec3.atBottomCenterOf(TestsJeu.sol(h, 40, 39000)), ItemStack.EMPTY);
        Courrier c = Courrier.de(h.getLevel().getServer());
        c.definirPoste(t.chef, h.getLevel(), bureau);
        t.forcerChunk();
        Courrier.Lettre l = c.nouvelle("Chef", t.chef, t.getUUID(), "Lettre du chef", "Garde-moi toujours une bonne réserve de charbon, tu veux bien ?", h.getLevel());
        l.duChef = true;
        t.lireLettre(l);
        h.succeedWhen(() -> {
            h.assertTrue(t.missions.stream().anyMatch(m -> m.id.equals("charbon") && m.mode.equals("stock") && m.nombre == 32), "mission de la lettre : " + t.missions.size());
            h.assertTrue(c.boite(t.chef).stream().anyMatch(x -> x.objet.equals("Réponse à ta lettre") && x.texte.contains("trente-deux charbons"))
                            || c.vols.stream().anyMatch(v -> v.lettre.objet.equals("Réponse à ta lettre"))
                            || t.lettresADeposer.stream().anyMatch(x -> x.objet.equals("Réponse à ta lettre")),
                    "la réponse écrite par l'IA doit partir");
            t.discard();
        });
    }

    /** Au micro : sa phrase est mise en texte (Whisper), puis traitée comme une phrase écrite. Un simple bruit est ignoré. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaMicro(GameTestHelper h) {
        sol(h);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        Travailleur t = TestsJeu.ouvrier(h, "bucheron", 2, 2, 4, ItemStack.EMPTY);
        t.chef = chef.getUUID();
        var s = h.getLevel().getServer();
        int avantDemandes = IA.TRANSCRIPTION.etat(9999).get("demandes") instanceof Integer i ? i : 0;
        Voix.entendre(s, chef, t, son(48_000 / 10, 6000)); // 0,1 s : trop court
        Voix.entendre(s, chef, t, son(48_000, 40)); // presque silencieux
        h.assertTrue((int) IA.TRANSCRIPTION.etat(9999).get("demandes") == avantDemandes, "un bruit ne doit pas partir chez Whisper");
        Voix.entendre(s, chef, t, son(48_000, 6000));
        h.succeedWhen(() -> {
            h.assertTrue(t.mode == Travailleur.Mode.SUIVRE, "il doit l'avoir entendu (" + t.mode + ")");
            h.assertTrue(IA.echange(t).contains("Chef : Suis-moi, s'il te plaît.") && IA.echange(t).contains("J'arrive, chef"), "échange : " + IA.echange(t));
            t.discard();
        });
    }

    /** Le service répond mal : l'interpréteur prend le relais, et une erreur de requête ne met pas le service en pause. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaRepliSurLInterpreteur(GameTestHelper h) {
        sol(h);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        Travailleur t = TestsJeu.ouvrier(h, "guerrier", 2, 2, 4, ItemStack.EMPTY);
        t.chef = chef.getUUID();
        t.parler(chef, "Suis-moi ! PANNE");
        h.assertTrue(t.reflechit, "il doit d'abord demander à l'IA (" + diag(chef, t) + ")");
        h.succeedWhen(() -> {
            h.assertTrue(!t.reflechit && t.mode == Travailleur.Mode.SUIVRE, "l'interpréteur doit avoir compris (" + t.mode + ")");
            h.assertFalse(IA.echange(t).contains("J'arrive, chef"), "ce n'est pas l'IA qui a répondu");
            h.assertTrue((long) IA.CONVERSATION.etat(9999).get("pause") == 0, "une requête refusée ne doit pas couper le service");
            t.discard();
        });
    }

    /** La voix : Gemini la dit, le mod la nettoie (silences) et la met à 48 kHz, puis le PNJ la joue par Simple Voice Chat. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaVoix(GameTestHelper h) {
        h.assertTrue(Voix.pret, "Simple Voice Chat doit être chargé et prêt dans les essais");
        sol(h);
        var chef = h.makeMockServerPlayerInLevel();
        Vec3 p = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        chef.moveTo(p.x, p.y, p.z);
        Travailleur t = TestsJeu.ouvrier(h, "mineur", 2, 2, 4, ItemStack.EMPTY);
        t.chef = chef.getUUID();
        Voix.Timbre tb = Voix.timbre(t);
        h.assertTrue(tb.equals(Voix.timbre(t)) && !tb.voix().isEmpty(), "son timbre doit rester le même");
        CompletableFuture<short[]> f = IA.voix("Bien, chef.", tb.voix(), Voix.style(t, tb, "joyeux"), tb.hauteur());
        boolean[] entendu = new boolean[1];
        Voix.dire(t, "Je descends à la mine, chef.", "fier");
        h.succeedWhen(() -> {
            h.assertTrue(f.isDone() && !f.isCompletedExceptionally(), "voix attendue");
            short[] s = f.join();
            // Une seconde de voix (sans le silence du début), à 48 kHz, plus courte ou plus longue selon sa hauteur.
            h.assertTrue(Math.abs(s.length * tb.hauteur() - 50_400) < 2_500, "longueur " + s.length + " pour la hauteur " + tb.hauteur());
            if (VoixSvc.parle(t)) entendu[0] = true;
            h.assertTrue(entendu[0], "il doit parler par Simple Voice Chat");
            t.discard();
        });
    }

    /** L'Opus de Simple Voice Chat : ce qu'un micro envoie (trames de 20 ms) se décode en son de même longueur et de même volume. */
    @GameTest(template = "vide", batch = "ia")
    public static void svcOpus(GameTestHelper h) {
        h.assertTrue(Voix.pret, "Simple Voice Chat doit être prêt");
        short[] s = son(48_000, 8000);
        short[] r = VoixSvc.allerRetour(s);
        h.assertTrue(r.length == 48_000, "longueur " + r.length);
        h.assertTrue(Math.abs(Audio.rms(r) - Audio.rms(s)) < Audio.rms(s) * 0.25, "volume " + Audio.rms(r) + " pour " + Audio.rms(s));
        h.succeed();
    }

    /** Le panneau donne l'état de l'IA sans jamais renvoyer les clés. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 6000)
    public static void iaPanneauSansCles(GameTestHelper h) {
        var client = java.net.http.HttpClient.newHttpClient();
        var rep = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + Reglages.actuel.port_panneau + "/api/ia"))
                .header("X-Jeton", Reglages.actuel.jeton_panneau).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        h.succeedWhen(() -> {
            h.assertTrue(rep.isDone(), "réponse en attente");
            String b = rep.join().body();
            h.assertTrue(rep.join().statusCode() == 200 && b.contains("\"conversation\"") && b.contains("…1234") && b.contains("…5678"), "état attendu : " + b);
            h.assertFalse(b.contains(CLE_GROQ) || b.contains(CLE_GEMINI), "les clés ne doivent jamais sortir");
        });
    }

    /** Trois ouvriers autour du chef : Bertrand le mineur (celui qu'il regarde), Raoul le guerrier, Odon le bûcheron. */
    static Travailleur[] equipe(GameTestHelper h, net.minecraft.server.level.ServerPlayer chef, int coin) {
        BlockPos base = TestsJeu.sol(h, coin * 400, 60000);
        Vec3 c = Vec3.atBottomCenterOf(base);
        chef.moveTo(c.x, c.y, c.z);
        Travailleur[] e = {TestsJeu.employe(h, "mineur", base, c.add(-2.5, 0, -0.5), ItemStack.EMPTY),
                TestsJeu.employe(h, "guerrier", base, c.add(1.5, 0, -0.5), ItemStack.EMPTY),
                TestsJeu.employe(h, "bucheron", base, c.add(-0.5, 0, 2.5), ItemStack.EMPTY)};
        String[] noms = {"Bertrand", "Raoul", "Odon"};
        for (int i = 0; i < 3; i++) { e[i].chef = chef.getUUID(); e[i].prenom = noms[i]; e[i].forcerChunk(); }
        return e;
    }

    /**
     * Un test d'équipe, étape par étape : d'abord attendre que les ouvriers soient visibles du chef (dans un tronçon tout
     * juste chargé, ils ne le sont qu'au tick suivant), puis chaque étape dans l'ordre. Une étape qui échoue est retentée
     * au tick suivant (une vérification) ; au bout du délai, le test échoue avec son message.
     */
    static void etapes(GameTestHelper h, net.minecraft.server.level.ServerPlayer chef, Travailleur[] e, Runnable... etapes) {
        int[] n = {0};
        h.succeedWhen(() -> {
            if (n[0] == 0 && Dialogue.autour(chef, null).size() < e.length)
                throw new net.minecraft.gametest.framework.GameTestAssertException("ouvriers pas encore visibles");
            while (n[0] < etapes.length) {
                etapes[n[0]].run();
                n[0]++;
            }
        });
    }

    /** Une étape qui attend {@code ticks} ticks. */
    static Runnable attendre(GameTestHelper h, int ticks) {
        long[] debut = {-1};
        return () -> {
            if (debut[0] < 0) debut[0] = h.getTick();
            if (h.getTick() - debut[0] < ticks) throw new net.minecraft.gametest.framework.GameTestAssertException("attente");
        };
    }

    /** Pour comprendre un échec : qui le chef entend, ce que le journal d'écoute a retenu, s'il attend l'IA. */
    static String diag(net.minecraft.server.level.ServerPlayer chef, Travailleur vise) {
        return "autour=" + Dialogue.autour(chef, vise).size() + " attente=" + Dialogue.EN_ATTENTE.contains(chef.getUUID())
                + " conversation=" + IA.conversationDisponible() + " écoute=" + IA.ecouteIADisponible()
                + " journal=" + Dialogue.journal().stream().filter(x -> chef.getName().getString().equals(x.get("joueur"))).findFirst().map(Object::toString).orElse("-");
    }

    /** Le chef regarde cet ouvrier droit dans les yeux. */
    static void regarder(net.minecraft.server.level.ServerPlayer chef, Travailleur t) {
        Vec3 d = t.getEyePosition().subtract(chef.getEyePosition()); // sans paquet envoyé : le faux joueur n'a pas de vraie connexion
        chef.setXRot((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        chef.setYRot((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
        chef.setYHeadRot(chef.getYRot());
    }

    /** Sans prénom : l'IA comprend que « protéger la base », c'est pour le guerrier, même quand on regarde le mineur. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaDevineLeDestinataire(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 1);
        etapes(h, chef, e,
                () -> { regarder(chef, e[0]); Dialogue.parler(chef, "Il faudrait que quelqu'un protège la base", e[0], true); },
                () -> h.assertTrue(e[0].reflechit && e[1].reflechit && e[2].reflechit, "les trois doivent être décrits à l'IA (" + diag(chef, e[0]) + ")"),
                () -> {
                    h.assertTrue(e[1].missions.stream().anyMatch(m -> m.id.equals("garder")), "Raoul doit garder la base");
                    h.assertTrue(IA.echange(e[1]).contains("personne ne passera"), "c'est Raoul qui répond : " + IA.echange(e[1]));
                    h.assertTrue(IA.echange(e[0]).isEmpty() && e[0].missions.isEmpty(), "Bertrand n'était pas concerné : " + IA.echange(e[0]));
                },
                () -> { for (Travailleur t : e) t.discard(); });
    }

    /** « Les gars » : le mod l'a compris seul ; l'IA répond pour chacun, un seul parle, tous obéissent. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 400)
    public static void iaLesGars(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 2);
        etapes(h, chef, e,
                () -> Dialogue.parler(chef, "Les gars, suivez-moi !", null, true),
                () -> {
                    for (Travailleur t : e) h.assertTrue(t.mode == Travailleur.Mode.SUIVRE, t.prenom + " doit suivre (" + t.mode + ")");
                    long parlent = java.util.Arrays.stream(e).filter(t -> IA.echange(t).contains("On te suit, chef")).count();
                    h.assertTrue(parlent == 1, "un seul répond (" + parlent + ")");
                    String vu = IA.echange(e[2]);
                    h.assertTrue(vu.contains("Chef (à ") && vu.contains("Bertrand") && vu.contains("Raoul") && vu.contains("Odon"), "la phrase était pour tous : " + vu);
                },
                () -> { for (Travailleur t : e) t.discard(); });
    }

    /** Au micro, sans regarder personne, une phrase qui ne leur est pas adressée : personne ne répond ni ne bouge. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 200)
    public static void iaPasPourEux(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 3);
        etapes(h, chef, e,
                () -> Dialogue.parler(chef, "Je fredonne une vieille chanson", null, true),
                () -> h.assertTrue(!e[0].reflechit, "le code est sûr que ce n'est pas pour eux : l'IA n'est même pas sollicitée"),
                () -> Dialogue.parler(chef, "Qui peut me chanter une chanson ?", null, true),
                attendre(h, 80),
                () -> { for (Travailleur t : e) h.assertTrue(IA.echange(t).isEmpty() && t.mode == Travailleur.Mode.TRAVAIL, t.prenom + " ne doit pas répondre : " + IA.echange(t)); },
                () -> { for (Travailleur t : e) t.discard(); });
    }

    /** Sans IA : le mod trouve seul à qui on parle (prénom, « les gars », « sauf », le métier qui comprend la demande). */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void destinatairesSansIA(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 4);
        Travailleur bertrand = e[0], raoul = e[1], odon = e[2];
        etapes(h, chef, e,
                () -> { regarder(chef, bertrand); bertrand.parler(chef, "Raoul, suis-moi"); }, // écrit dans la fenêtre de Bertrand
                () -> h.assertTrue(raoul.mode == Travailleur.Mode.SUIVRE && bertrand.mode == Travailleur.Mode.TRAVAIL, "seul Raoul suit"),
                () -> bertrand.parler(chef, "Les gars, attendez ici"),
                () -> { for (Travailleur t : e) h.assertTrue(t.mode == Travailleur.Mode.ATTENDRE, t.prenom + " doit attendre"); },
                () -> bertrand.parler(chef, "Tout le monde sauf Raoul, au travail"),
                () -> h.assertTrue(bertrand.mode == Travailleur.Mode.TRAVAIL && odon.mode == Travailleur.Mode.TRAVAIL && raoul.mode == Travailleur.Mode.ATTENDRE, "sauf Raoul"),
                () -> bertrand.parler(chef, "Va protéger la base"),
                () -> h.assertTrue(raoul.missions.stream().anyMatch(m -> m.id.equals("garder")) && bertrand.missions.isEmpty(), "c'est le travail du guerrier"),
                () -> bertrand.parler(chef, "Ramène-moi dix fers"),
                () -> h.assertTrue(bertrand.missions.stream().anyMatch(m -> m.id.equals("fer") && m.nombre == 10), "le fer, c'est pour Bertrand"),
                () -> Dialogue.parler(chef, "Je fredonne une vieille chanson", null, true),
                () -> h.assertTrue(odon.mode == Travailleur.Mode.TRAVAIL, "au micro, ce qui ne leur est pas adressé est ignoré"),
                () -> { for (Travailleur t : e) t.discard(); });
    }

    /** Chacun son timbre : une voix différente tant qu'il en reste, puis la même voix à une autre hauteur ; et il le garde. */
    @GameTest(template = "vide", timeoutTicks = 100)
    public static void chacunSaVoix(GameTestHelper h) {
        sol(h);
        // Des ouvriers restés d'un essai précédent (un test raté ne les retire pas) fausseraient le décompte des voix prises.
        java.util.List<Travailleur> restes = new java.util.ArrayList<>();
        for (var x : h.getLevel().getAllEntities()) if (x instanceof Travailleur t && t.memoire.contains("voix_hauteur")) restes.add(t);
        restes.forEach(Travailleur::discard);
        java.util.List<Travailleur> l = new java.util.ArrayList<>();
        java.util.Set<String> timbres = new java.util.HashSet<>(), voix = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            Travailleur t = TestsJeu.ouvrier(h, "mineur", i % 8, 2, i / 8, ItemStack.EMPTY);
            t.prenom = "Raoul";
            t.temperament = "calme";
            l.add(t);
        }
        h.runAfterDelay(5, () -> chacunSaVoixSuite(h, l, timbres, voix));
    }

    static void chacunSaVoixSuite(GameTestHelper h, java.util.List<Travailleur> l, java.util.Set<String> timbres, java.util.Set<String> voix) {
        for (int i = 0; i < l.size(); i++) {
            Travailleur t = l.get(i);
            Voix.Timbre tb = Voix.timbre(t);
            h.assertFalse(tb.femme(), "Raoul est un homme");
            h.assertTrue(timbres.add(tb.voix() + "@" + tb.hauteur()), "timbre en double : " + tb);
            if (i < Voix.VOIX_HOMMES.size()) {
                long vus = 0;
                for (var e : h.getLevel().getAllEntities()) if (e instanceof Travailleur x && x.memoire.contains("voix_hauteur")) vus++;
                h.assertTrue(voix.add(tb.voix()), "tant qu'il reste des voix libres, chacun la sienne : " + tb.voix() + " (n° " + i + ", " + vus + " timbres visibles, déjà " + voix + ")");
            }
        }
        for (Travailleur t : l) h.assertTrue(Voix.timbre(t).equals(Voix.timbre(t)), "il garde son timbre");
        Travailleur blanche = TestsJeu.ouvrier(h, "bucheron", 7, 2, 7, ItemStack.EMPTY);
        blanche.prenom = "Blanche";
        h.assertTrue(Voix.VOIX_FEMMES.contains(Voix.timbre(blanche).voix()) && Voix.style(blanche, Voix.timbre(blanche), "joyeux").contains("woman"), "Blanche est une femme");
        // La hauteur change vraiment le son : plus aiguë, il est plus court.
        short[] s = new short[24_000];
        for (int i = 0; i < s.length; i++) s[i] = (short) (6000 * Math.sin(i / 10.0));
        int normal = IA.enVoix(Audio.ecrire(s, 24_000), 1f).length, aigu = IA.enVoix(Audio.ecrire(s, 24_000), 1.1f).length;
        h.assertTrue(Math.abs(aigu * 1.1 - normal) < 100, normal + " / " + aigu);
        l.forEach(Travailleur::discard);
        blanche.discard();
        h.succeed();
    }

    /** Au micro, une remarque sur eux (pas à eux) : personne ne répond, et l'IA n'est même pas sollicitée. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 200)
    public static void iaRemarqueSurEux(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 5);
        // Une phrase à la fois (le chef attend la réponse de l'IA avant d'être de nouveau écouté).
        etapes(h, chef, e,
                () -> { regarder(chef, e[0]); Dialogue.parler(chef, "C'est vraiment un chômeur ce mec.", e[0], true); },
                attendre(h, 20),
                () -> Dialogue.parler(chef, "Il est lent, Raoul.", null, true),
                attendre(h, 20),
                () -> { regarder(chef, e[1]); Dialogue.parler(chef, "Bon, je mine encore un peu et je rentre.", e[1], true); },
                attendre(h, 60),
                () -> { for (Travailleur t : e) h.assertTrue(IA.echange(t).isEmpty(), t.prenom + " ne doit pas répondre : " + IA.echange(t)); },
                () -> { for (Travailleur t : e) t.discard(); });
    }

    /** Sans casque, la voix du PNJ revient par le micro du joueur : on ne l'écoute pas pendant que le PNJ parle, et on reconnaît l'écho. */
    @GameTest(template = "vide", batch = "ia", timeoutTicks = 100)
    public static void iaPasDEcho(GameTestHelper h) {
        var chef = h.makeMockServerPlayerInLevel();
        Travailleur[] e = equipe(h, chef, 6);
        java.util.UUID u = chef.getUUID();
        Voix.cibles = java.util.Map.of(u, e[1].getId());
        Voix.paquet(u, new byte[]{1, 2, 3});
        h.assertTrue(Voix.EN_COURS.containsKey(u), "sans PNJ qui parle, on l'écoute");
        String dit = "Chef, je comprends pas trop, tu peux préciser ce que tu veux que je fasse ?";
        Voix.assourdir(e[1], dit, System.currentTimeMillis() + 2000);
        h.assertFalse(Voix.EN_COURS.containsKey(u), "ce qu'il disait pendant que Raoul parlait est jeté");
        Voix.paquet(u, new byte[]{1, 2, 3});
        h.assertFalse(Voix.EN_COURS.containsKey(u), "pendant que Raoul parle, on ne l'écoute pas");
        h.assertTrue(Voix.echo(u, "Chef, je comprends pas trop. Tu peux préciser ce que tu veux que je fasse ? Surveille les alentours."), "l'écho est reconnu");
        h.assertFalse(Voix.echo(u, "Suis-moi, Raoul."), "une vraie phrase passe");
        h.assertFalse(Voix.echo(u, "Oui, chef."), "trop court pour juger");
        Voix.SOURDINE.remove(u);
        Voix.ENTENDU.remove(u);
        for (Travailleur t : e) t.discard();
        h.succeed();
    }
}

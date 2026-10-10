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
        if (!systeme.contains("Missions possibles")) return erreur(400, "pas de catalogue");
        if (parole.contains("PANNE")) return erreur(400, "The model does not exist");
        String decision;
        if (parole.contains("pioches"))
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
        if (!corps.contains("name=\"model\"") || !corps.contains("\r\n\r\nfr\r\n") || !corps.contains("RIFF") || !corps.contains("filename=\"voix.wav\""))
            return erreur(400, "formulaire incomplet");
        return new Reponse(200, "{\"text\":\" Suis-moi, s'il te plaît.\",\"x_groq\":{\"id\":\"req_1\"}}");
    }

    /** Le faux Gemini : une demi-seconde de silence puis une seconde de « voix » (un son pur), en WAV 24 kHz. */
    static Reponse voix(HttpExchange x) throws IOException {
        if (!CLE_GEMINI.equals(x.getRequestHeaders().getFirst("x-goog-api-key"))) return erreur(403, "API key not valid");
        String corps = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!corps.contains("speech_config") || !corps.contains("\"store\":false")) return erreur(400, "requête de voix invalide");
        short[] s = new short[36_000];
        for (int i = 12_000; i < s.length; i++) s[i] = (short) (7000 * Math.sin(2 * Math.PI * 220 * i / 24_000.0));
        String b64 = Base64.getEncoder().encodeToString(Audio.ecrire(s, 24_000));
        return new Reponse(200, "{\"id\":\"v1\",\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\""
                + b64 + "\"}]}]}");
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
        h.assertTrue(t.reflechit, "il doit d'abord demander à l'IA");
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
        String v = Voix.voixDe(t);
        h.assertTrue(v.equals(Voix.voixDe(t)) && !v.isEmpty(), "sa voix doit rester la même");
        CompletableFuture<short[]> f = IA.voix("Bien, chef.", v, Voix.style(t, "joyeux"));
        boolean[] entendu = new boolean[1];
        Voix.dire(t, "Je descends à la mine, chef.", "fier");
        h.succeedWhen(() -> {
            h.assertTrue(f.isDone() && !f.isCompletedExceptionally(), "voix attendue");
            short[] s = f.join();
            h.assertTrue(s.length > 48_000 && s.length < 48_000 * 1.2, "une seconde de voix à 48 kHz, sans le silence du début : " + s.length);
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
}

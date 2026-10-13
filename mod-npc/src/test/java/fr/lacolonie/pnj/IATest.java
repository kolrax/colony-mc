package fr.lacolonie.pnj;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Le son et la lecture des réponses des services d'IA (sans réseau). */
class IATest {
    static short[] sinus(int n, int frequence, double hz, int amplitude) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (amplitude * Math.sin(2 * Math.PI * hz * i / frequence));
        return s;
    }

    @Test
    void wavAllerRetour() {
        short[] s = sinus(2400, 24_000, 440, 8000);
        Audio.Son lu = Audio.lire(Audio.ecrire(s, 24_000), 0);
        assertEquals(24_000, lu.frequence());
        assertArrayEquals(s, lu.echantillons());
    }

    @Test
    void pcmBrutEtReechantillonnage() {
        byte[] brut = new byte[]{1, 0, -1, -1, 0, 1}; // 1, -1, 256
        Audio.Son lu = Audio.lire(brut, 24_000);
        assertArrayEquals(new short[]{1, -1, 256}, lu.echantillons());
        assertEquals(24_000, lu.frequence());
        assertEquals(48_000, Audio.reechantillonner(new short[24_000], 24_000, 48_000).length);
        assertEquals(16_000, Audio.reechantillonner(new short[48_000], 48_000, 16_000).length);
        short[] s = sinus(4800, 48_000, 200, 10_000);
        assertEquals(Audio.rms(s), Audio.rms(Audio.reechantillonner(s, 48_000, 16_000)), 300, "le volume doit survivre au changement de fréquence");
    }

    @Test
    void wavStereoAvecMorceauEnPlus() {
        // Un WAV stéréo avec un morceau « LIST » avant les données : on garde la voie de gauche.
        ByteBuffer b = ByteBuffer.allocate(12 + 24 + 12 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2).putInt(22_050).putInt(0).putShort((short) 4).putShort((short) 16);
        b.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII));
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8).putShort((short) 5).putShort((short) -5).putShort((short) 7).putShort((short) -7);
        Audio.Son lu = Audio.lire(b.array(), 0);
        assertEquals(22_050, lu.frequence());
        assertArrayEquals(new short[]{5, 7}, lu.echantillons());
    }

    @Test
    void rognerLesSilences() {
        short[] s = new short[48_000];
        System.arraycopy(sinus(4800, 48_000, 300, 5000), 0, s, 20_000, 4800);
        short[] r = Audio.rogner(s, 48_000);
        assertTrue(r.length >= 4700 && r.length <= 4800 + 2 * 2400 + 2, "taille " + r.length);
        assertEquals(0, Audio.rogner(new short[1000], 48_000).length, "le silence seul ne garde rien");
    }

    @Test
    void phrasesInventeesParWhisper() {
        assertTrue(Audio.inventee("Sous-titres réalisés par la communauté d'Amara.org"));
        assertTrue(Audio.inventee("Merci d'avoir regardé !"));
        assertTrue(Audio.inventee("..."));
        assertTrue(Audio.inventee("[Musique]"));
        assertFalse(Audio.inventee("Ramène-moi dix fers."));
        assertFalse(Audio.inventee("Merci"));
        assertFalse(Audio.inventee("Joue-moi de la musique en rentrant"));
    }

    static final Set<String> IDS = Set.of("fer", "diamant", "charbon");

    @Test
    void decisionComplete() {
        String c = """
                {"reponse":"Bien, chef ! Dix fers et un diamant, j'y vais.","humeur":"joyeux","actions":[
                 {"type":"missions","missions":[{"id":"fer","mode":"quantite","nombre":10},{"id":"diamant","mode":"quantite","nombre":1}],"ajouter":false,"debut":0,"fin":0,"jours":0},
                 {"type":"horaires","missions":[],"ajouter":false,"debut":8,"fin":20,"jours":0}]}""";
        ReponsesIA.Decision d = ReponsesIA.decision(c, IDS, true);
        assertEquals("joyeux", d.humeur());
        assertTrue(d.reponse().startsWith("Bien, chef"));
        Ordres.Lecture l = d.lecture();
        assertEquals(List.of(new Ordres.Mission("fer", "quantite", 10), new Ordres.Mission("diamant", "quantite", 1)), l.missions);
        assertEquals(8, l.debut);
        assertEquals(20, l.fin);
        assertFalse(l.ajouter);
    }

    @Test
    void decisionRevérifiée() {
        // Ce que le modèle n'a pas le droit de faire est ignoré : mission hors catalogue, action inconnue ou interdite par lettre,
        // horaires impossibles, expédition trop longue, nombre délirant.
        String c = """
                Voici : {"reponse":"%s","humeur":"furieux","actions":[
                 {"type":"missions","missions":[{"id":"emeraude","mode":"quantite","nombre":5},{"id":"charbon","mode":"stock","nombre":999999999}],"ajouter":true,"debut":0,"fin":0,"jours":0},
                 {"type":"teleporter","missions":[],"ajouter":false,"debut":0,"fin":0,"jours":0},
                 {"type":"suivre","missions":[],"ajouter":false,"debut":0,"fin":0,"jours":0},
                 {"type":"horaires","missions":[],"ajouter":false,"debut":25,"fin":3,"jours":0},
                 {"type":"expedition","missions":[],"ajouter":false,"debut":0,"fin":0,"jours":90}]} merci""".formatted("mot ".repeat(200));
        ReponsesIA.Decision d = ReponsesIA.decision(c, IDS, false);
        assertEquals("calme", d.humeur(), "humeur inconnue : calme");
        assertTrue(d.reponse().length() <= 401, "réponse coupée");
        assertEquals(1, d.actions().size(), "seule la mission charbon reste : " + d.actions());
        Ordres.Lecture l = d.lecture();
        assertEquals(List.of(new Ordres.Mission("charbon", "stock", 100_000)), l.missions);
        assertTrue(l.ajouter);
        assertTrue(l.intentions.isEmpty(), "« suivre » n'existe pas par lettre");
        assertEquals(-1, l.debut);
        assertEquals(-1, l.jours);
    }

    @Test
    void schemaStrict() {
        JsonObject s = ReponsesIA.schema(List.of("fer", "or"), true);
        assertFalse(s.get("additionalProperties").getAsBoolean());
        assertEquals(3, s.getAsJsonArray("required").size());
        JsonObject action = s.getAsJsonObject("properties").getAsJsonObject("actions").getAsJsonObject("items");
        assertEquals(6, action.getAsJsonArray("required").size(), "en mode strict, tout est obligatoire");
        assertTrue(action.toString().contains("\"renvoyer\""));
        assertFalse(ReponsesIA.schema(List.of(), false).toString().contains("\"renvoyer\""), "pas de renvoi par lettre");
        JsonObject r = JsonParser.parseString(ReponsesIA.requeteConversation("openai/gpt-oss-120b", "sys", "Salut", s)).getAsJsonObject();
        assertEquals("json_schema", r.getAsJsonObject("response_format").get("type").getAsString());
        assertTrue(r.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("strict").getAsBoolean());
        assertEquals("low", r.get("reasoning_effort").getAsString());
        assertFalse(JsonParser.parseString(ReponsesIA.requeteConversation("llama-3.3-70b-versatile", "s", "p", s)).getAsJsonObject().has("reasoning_effort"));
    }

    @Test
    void lectureDesReponsesGroq() {
        String rep = """
                {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"reponse\\":\\"Oui chef.\\",\\"humeur\\":\\"calme\\",\\"actions\\":[]}"}}],
                 "usage":{"prompt_tokens":900,"completion_tokens":40,"total_tokens":940}}""";
        assertEquals("Oui chef.", ReponsesIA.decision(ReponsesIA.contenu(rep), IDS, true).reponse());
        assertEquals(940, ReponsesIA.jetons(rep));
        assertEquals("Ramène dix fers.", ReponsesIA.transcription("{\"text\":\" Ramène dix fers. \",\"x_groq\":{\"id\":\"r\"}}"));
        assertEquals("Rate limit reached", ReponsesIA.erreur("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"tokens\"}}"));
    }

    @Test
    void formulaireMultipart() {
        byte[] f = ReponsesIA.formulaire("XYZ", Map.of("model", "whisper-large-v3-turbo"), "voix.wav", "audio/wav", new byte[]{1, 2, 3});
        String s = new String(f, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("--XYZ\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n"));
        assertTrue(s.contains("name=\"file\"; filename=\"voix.wav\"\r\nContent-Type: audio/wav\r\n\r\n\u0001\u0002\u0003\r\n"));
        assertTrue(s.endsWith("--XYZ--\r\n"));
    }

    @Test
    void sonDesDeuxFormesDeGemini() {
        byte[] wav = Audio.ecrire(sinus(240, 24_000, 440, 6000), 24_000);
        String b64 = Base64.getEncoder().encodeToString(wav);
        // API « interactions » : steps → model_output → content → audio
        String interactions = """
                {"id":"i1","status":"completed","steps":[{"type":"user_input","content":[{"type":"text","text":"Bonjour"}]},
                 {"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"%s"}]}],"usage":{"total_tokens":12}}""".formatted(b64);
        assertArrayEquals(wav, ReponsesIA.audio(interactions));
        // generateContent : candidates → content → parts → inlineData (PCM brut)
        byte[] brut = new byte[]{4, 0, 8, 0};
        String ancienne = """
                {"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"%s"}}],"role":"model"}}]}"""
                .formatted(Base64.getEncoder().encodeToString(brut));
        assertArrayEquals(brut, ReponsesIA.audio(ancienne));
        assertThrows(IllegalStateException.class, () -> ReponsesIA.audio("{\"candidates\":[]}"));
        // La requête : texte, voix, ton, et rien de gardé chez Google
        JsonObject r = JsonParser.parseString(ReponsesIA.requeteVoix("gemini-3.8-flash-tts", "Bonjour chef.", "Kore", "calm")).getAsJsonObject();
        assertFalse(r.get("store").getAsBoolean());
        assertTrue(r.toString().contains("\"voice\":\"Kore\"") && r.toString().contains("Bonjour chef."));
        assertTrue(ReponsesIA.requeteVoixAncienne("Bonjour", "Puck", "calm").contains("\"voiceName\":\"Puck\""));
    }

    @Test
    void attenteApresUnQuota() {
        assertEquals(30_000, ReponsesIA.attente("30", ""));
        String google = "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded for model gemini-3.8-flash-tts (limit: 10 requests per day on Free Tier). "
                + "Please retry in 6h51m50s or upgrade your tier.\",\"details\":[{\"retryDelay\":\"24710s\"}]}}";
        assertEquals(24_710_000, ReponsesIA.attente("", google));
        assertEquals((6 * 3600 + 51 * 60 + 50) * 1000L, ReponsesIA.attente("", "Please retry in 6h51m50s or upgrade"));
        assertEquals(12_500, ReponsesIA.attente("", "Please retry in 12.5s."));
        assertEquals(3_600_000, ReponsesIA.attente("", "{}"));
    }

    @Test
    void reponseDeGroupe() {
        Map<String, Set<String>> ids = new java.util.LinkedHashMap<>();
        ids.put("Bertrand", Set.of("fer", "charbon"));
        ids.put("Raoul", Set.of("garder", "ronde"));
        String c = """
                {"destinataires":["Raoul","Gontran","Raoul"],"reponses":[
                 {"qui":"Raoul","reponse":"J'y vais, chef.","humeur":"fier","actions":[
                   {"type":"missions","missions":[{"id":"garder","mode":"stock","nombre":1},{"id":"fer","mode":"quantite","nombre":10}],"ajouter":false,"debut":0,"fin":0,"jours":0}]},
                 {"qui":"Gontran","reponse":"Moi aussi !","humeur":"calme","actions":[]},
                 {"qui":"Raoul","reponse":"Encore moi","humeur":"calme","actions":[]}]}""";
        ReponsesIA.Groupe g = ReponsesIA.decisionGroupe(c, ids);
        assertEquals(List.of("Raoul"), g.destinataires(), "inconnus et doublons écartés");
        assertEquals(Set.of("Raoul"), g.reponses().keySet());
        assertEquals("J'y vais, chef.", g.reponses().get("Raoul").reponse());
        assertEquals(List.of(new Ordres.Mission("garder", "stock", 1)), g.reponses().get("Raoul").lecture().missions, "le fer n'est pas du métier de Raoul");
        JsonObject s = ReponsesIA.schemaGroupe(List.of("Bertrand", "Raoul"), List.of("fer", "garder"));
        assertTrue(s.toString().contains("\"destinataires\"") && s.toString().contains("\"qui\""));
        assertEquals(2, s.getAsJsonArray("required").size());
    }

    @Test
    void refusDeSchemaRattrape() {
        // Refus de Groq (HTTP 400) : le modèle est sorti d'une liste (ici l'humeur), Groq rejette tout mais rend le JSON
        // généré dans failed_generation. On garde la réplique et les actions valides.
        Map<String, Set<String>> ids = new java.util.LinkedHashMap<>();
        ids.put("Bertrand", Set.of("fer", "charbon"));
        String genere = """
                {"destinataires":["Bertrand"],"reponses":[{"qui":"Bertrand","reponse":"Je cherche ça, chef, et je te suis.","humeur":"pressé","actions":[
                 {"type":"suivre","missions":[],"ajouter":false,"debut":0,"fin":0,"jours":0}]}]}""";
        JsonObject err = new JsonObject(), e = new JsonObject();
        e.addProperty("message", "Generated JSON does not match the expected schema. Please adjust your prompt. See 'failed_generation' for more details. "
                + "Error: jsonschema: '/reponses/0/actions/0/type' does not validate with /properties/reponses/items/properties/actions/items/properties/type/enum: "
                + "value must be one of 'suivre', 'arreter_suivre', 'travailler', 'attendre', 'rentrer', 'montrer_sac', 'renvoyer', 'missions', 'horaires', 'expedition', 'faire'");
        e.addProperty("type", "invalid_request_error");
        e.addProperty("code", "json_validate_failed");
        e.addProperty("failed_generation", genere);
        err.add("error", e);
        String corps = err.toString();
        assertNotNull(ReponsesIA.generationRatee(corps));
        ReponsesIA.Groupe g = ReponsesIA.decisionGroupe(ReponsesIA.contenu(corps), ids);
        assertEquals(List.of("Bertrand"), g.destinataires());
        ReponsesIA.Decision d = g.reponses().get("Bertrand");
        assertEquals("Je cherche ça, chef, et je te suis.", d.reponse());
        assertEquals("calme", d.humeur(), "humeur hors liste : calme");
        assertEquals(List.of("suivre"), d.actions().stream().map(ReponsesIA.Action::type).toList());
        assertEquals(Set.of(Ordres.Intention.SUIVRE), d.lecture().intentions);
        // Illisible (coupé) ou absent : pas de rattrapage, l'appel échoue comme avant.
        e.addProperty("failed_generation", "{\"reponses\":[{\"qui\":\"Bertrand\"");
        assertNull(ReponsesIA.generationRatee(err.toString()));
        assertNull(ReponsesIA.generationRatee("{\"error\":{\"message\":\"Failed to generate JSON\"}}"));
        assertNull(ReponsesIA.generationRatee("pas du json"));
    }

    static String refus(String genere) {
        JsonObject err = new JsonObject(), e = new JsonObject();
        e.addProperty("message", "Generated JSON does not match the expected schema.");
        e.addProperty("code", "json_validate_failed");
        e.addProperty("failed_generation", genere);
        err.add("error", e);
        return err.toString();
    }

    @Test
    void champsMalTypes() {
        // Un champ du mauvais type ne fait pas tomber tout le groupe.
        String c = """
                {"destinataires":["Raoul"],"reponses":[{"qui":"Raoul","reponse":"Oui chef.","humeur":{"ton":"fier"},"actions":[
                 {"type":"suivre","missions":[],"ajouter":[],"debut":0,"fin":0,"jours":0}]}]}""";
        ReponsesIA.Decision d = ReponsesIA.decisionGroupe(c, Map.of("Raoul", Set.of("garder"))).reponses().get("Raoul");
        assertEquals("calme", d.humeur());
        assertEquals(Set.of(Ordres.Intention.SUIVRE), d.lecture().intentions);
        assertFalse(d.lecture().ajouter);
    }

    @Test
    void idDuRepertoireDansType() {
        // Le modèle met un id du répertoire dans type : c'est un « faire », par réponse normale comme rattrapée.
        Map<String, Set<String>> ids = Map.of("Raoul", Set.of("garder"));
        String genere = """
                {"destinataires":["Raoul"],"reponses":[{"qui":"Raoul","reponse":"À vos ordres, chef.","humeur":"fier","actions":[
                 {"type":"garde_a_vous","missions":[],"ajouter":false,"debut":0,"fin":0,"jours":0}]}]}""";
        String corps = refus(genere);
        assertNotNull(ReponsesIA.generationRatee(corps), "un id du répertoire n'empêche pas le rattrapage");
        ReponsesIA.Decision d = ReponsesIA.decisionGroupe(ReponsesIA.contenu(corps), ids).reponses().get("Raoul");
        assertEquals(List.of("garde_a_vous"), d.lecture().faire.stream().map(Ordres.Faire::id).toList());
        // Un type vraiment inconnu dans un rattrapage : on ne garde pas la réponse (retour à l'interpréteur).
        assertNull(ReponsesIA.generationRatee(refus(genere.replace("garde_a_vous", "chercher"))));
    }

    @Test
    void whisperSansSesInventions() {
        String v = "{\"text\":\" Merci d'avoir regardé. Ramène dix fers.\",\"segments\":["
                + "{\"text\":\" Merci d'avoir regardé.\",\"avg_logprob\":-0.9,\"compression_ratio\":1.2,\"no_speech_prob\":0.82},"
                + "{\"text\":\" Ramène dix fers.\",\"avg_logprob\":-0.3,\"compression_ratio\":1.0,\"no_speech_prob\":0.02},"
                + "{\"text\":\" fers fers fers fers fers fers fers\",\"avg_logprob\":-0.4,\"compression_ratio\":3.1,\"no_speech_prob\":0.1}]}";
        assertEquals("Ramène dix fers.", ReponsesIA.transcription(v));
        assertEquals("", ReponsesIA.transcription("{\"text\":\" ...\",\"segments\":[{\"text\":\" ...\",\"avg_logprob\":-1.6,\"no_speech_prob\":0.97}]}"));
        String indice = "Minecraft. Raoul, Enguerrand, les gars, chef, mineur, bûcheron, guerrier, poste, dépôt, base, fer.";
        assertTrue(Audio.recopieIndice("Raoul, les gars, chef, mineur, c'est un peu de temps.", indice));
        assertFalse(Audio.recopieIndice("Raoul, suis-moi.", indice));
        assertFalse(Audio.recopieIndice("Les gars, au travail !", indice));
    }

    /** OpenRouter : plus d'appel au-delà de 95 % du budget du mois ou du plafond du jour ; un nouveau jour, un nouveau mois remettent à zéro. */
    @Test
    void budgetOpenRouter() {
        DepensesIA d = new DepensesIA();
        java.time.LocalDate j = java.time.LocalDate.of(2026, 10, 13);
        assertTrue(d.permis(5, j));
        assertFalse(d.permis(0, j), "budget 0 : jamais");
        d.ajouter(0.34, j); // plafond du jour : 5 / 30 × 2 = 0,333 $
        assertFalse(d.permis(5, j), "plafond du jour atteint");
        assertTrue(d.permis(5, j.plusDays(1)), "le lendemain, ça repart");
        for (int i = 2; i <= 14; i++) d.ajouter(0.33, j.plusDays(i)); // 0,34 + 13 × 0,33 = 4,63 $
        assertTrue(d.permis(5, j.plusDays(15)));
        d.ajouter(0.2, j.plusDays(15)); // 4,83 $ : plus de 95 % de 5 $
        assertFalse(d.permis(5, j.plusDays(16)), "budget du mois atteint");
        assertEquals(4.83, d.mois(j.plusDays(16)), 1e-9);
        assertTrue(d.permis(5, java.time.LocalDate.of(2026, 11, 1)), "un nouveau mois remet à zéro");
        assertEquals(0, d.mois(java.time.LocalDate.of(2026, 11, 1)));
    }

    /** La requête OpenRouter demande le coût ; la réponse le donne, sinon on l'estime par les jetons (prudemment). */
    @Test
    void coutOpenRouter() {
        JsonObject r = JsonParser.parseString(ReponsesIA.pourOpenRouter("{\"model\":\"a\",\"messages\":[]}", "openai/gpt-oss-120b")).getAsJsonObject();
        assertEquals("openai/gpt-oss-120b", r.get("model").getAsString());
        assertTrue(r.getAsJsonObject("usage").get("include").getAsBoolean());
        assertEquals(0.00042, ReponsesIA.cout("{\"usage\":{\"total_tokens\":321,\"cost\":0.00042}}"), 1e-12);
        assertEquals(1000 * ReponsesIA.PRIX_JETON, ReponsesIA.cout("{\"usage\":{\"total_tokens\":1000}}"), 1e-12);
        assertEquals(ReponsesIA.PRIX_APPEL, ReponsesIA.cout("{}"), 1e-12);
    }
}

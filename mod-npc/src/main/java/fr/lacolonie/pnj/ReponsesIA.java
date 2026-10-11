package fr.lacolonie.pnj;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce qu'on envoie aux services d'IA et ce qu'on lit dans leurs réponses, sans rien du jeu (testé seul) :
 * <ul>
 * <li>la conversation (Groq, format compatible OpenAI), avec une réponse imposée en JSON : la phrase du PNJ, son
 *   humeur, et des actions prises dans une liste fermée — l'IA ne peut rien faire d'autre, et le mod revérifie tout ;</li>
 * <li>la transcription de la voix (Whisper) ;</li>
 * <li>la synthèse vocale (Gemini).</li>
 * </ul>
 */
final class ReponsesIA {
    private ReponsesIA() {}

    static final List<String> ACTIONS_FACE = List.of("suivre", "arreter_suivre", "travailler", "attendre", "rentrer", "montrer_sac", "renvoyer",
            "missions", "horaires", "expedition", "faire");
    static final List<String> ACTIONS_LETTRE = List.of("travailler", "rentrer", "missions", "horaires", "expedition");
    static final List<String> HUMEURS = List.of("calme", "joyeux", "inquiet", "agace", "triste", "fatigue", "fier");

    record Mission(String id, String mode, int nombre) {}

    /** Une action. {@code faire} : l'identifiant d'une action du répertoire (type « faire »), avec ce qu'elle vise, un nombre, une durée. */
    record Action(String type, List<Mission> missions, boolean ajouter, int debut, int fin, int jours, String faire, String cible, int nombre, int duree) {
        Action(String type, List<Mission> missions, boolean ajouter, int debut, int fin, int jours) { this(type, missions, ajouter, debut, fin, jours, "", "", 0, 0); }
    }

    /** Ce que l'IA a décidé : ce que le PNJ répond, sur quel ton, et ce qu'il fait. */
    record Decision(String reponse, String humeur, List<Action> actions) {
        /** Les actions, traduites dans la langue de l'interpréteur (le mod les applique et les revérifie comme un ordre écrit). */
        Ordres.Lecture lecture() {
            Ordres.Lecture l = new Ordres.Lecture();
            for (Action a : actions) {
                switch (a.type()) {
                    case "suivre" -> l.intentions.add(Ordres.Intention.SUIVRE);
                    case "arreter_suivre" -> l.intentions.add(Ordres.Intention.ARRETER_SUIVRE);
                    case "travailler" -> l.intentions.add(Ordres.Intention.TRAVAIL);
                    case "attendre" -> l.intentions.add(Ordres.Intention.ATTENDRE);
                    case "rentrer" -> l.intentions.add(Ordres.Intention.RENTRER);
                    case "montrer_sac" -> l.intentions.add(Ordres.Intention.SAC);
                    case "renvoyer" -> l.intentions.add(Ordres.Intention.RENVOYER);
                    case "missions" -> {
                        l.ajouter |= a.ajouter();
                        for (Mission m : a.missions()) if (l.missions.stream().noneMatch(x -> x.id().equals(m.id())))
                            l.missions.add(new Ordres.Mission(m.id(), m.mode(), m.nombre()));
                    }
                    case "horaires" -> { l.debut = a.debut(); l.fin = a.fin(); }
                    case "expedition" -> l.jours = a.jours();
                    case "faire" -> l.faire.add(new Ordres.Faire(a.faire(), a.cible(), a.nombre(), a.duree(), false));
                    default -> {}
                }
            }
            return l;
        }
    }

    // --- La conversation ------------------------------------------------------------------------------

    /** Le schéma imposé à la réponse (mode strict : tous les champs obligatoires, rien d'autre). */
    static JsonObject schema(List<String> idsMissions, boolean enFace) {
        return objet(Map.of("reponse", type("string"), "humeur", enumeration(HUMEURS), "actions", tableau(action(idsMissions, enFace, List.of()))));
    }

    /**
     * Le schéma d'une réponse de groupe : à qui le chef parle ({@code destinataires}, parmi les présents), puis la
     * réponse et les actions de chacun ({@code qui} : l'étiquette de l'ouvrier).
     */
    static JsonObject schemaGroupe(List<String> etiquettes, List<String> idsMissions) { return schemaGroupe(etiquettes, idsMissions, List.of()); }

    /** Pareil, avec les actions du répertoire proposées pour cette phrase ({@code repertoire} : leurs identifiants). */
    static JsonObject schemaGroupe(List<String> etiquettes, List<String> idsMissions, List<String> repertoire) {
        JsonObject reponse = objet(Map.of("qui", enumeration(etiquettes), "reponse", type("string"), "humeur", enumeration(HUMEURS),
                "actions", tableau(action(idsMissions, true, repertoire))));
        return objet(Map.of("destinataires", tableau(enumeration(etiquettes)), "reponses", tableau(reponse)));
    }

    static JsonObject action(List<String> idsMissions, boolean enFace, List<String> repertoire) {
        JsonObject mission = objet(Map.of(
                "id", enumeration(idsMissions.isEmpty() ? List.of("aucune") : idsMissions),
                "mode", enumeration(List.of("quantite", "stock")),
                "nombre", type("integer")));
        Map<String, JsonObject> champs = new java.util.LinkedHashMap<>(Map.of(
                "type", enumeration(enFace && !repertoire.isEmpty() ? ACTIONS_FACE : enFace ? ACTIONS_FACE.subList(0, ACTIONS_FACE.size() - 1) : ACTIONS_LETTRE),
                "missions", tableau(mission),
                "ajouter", type("boolean"),
                "debut", type("integer"),
                "fin", type("integer"),
                "jours", type("integer")));
        if (enFace && !repertoire.isEmpty()) {
            List<String> ids = new ArrayList<>(repertoire);
            ids.add("");
            champs.put("faire", enumeration(ids));
            champs.put("cible", type("string"));
            champs.put("nombre", type("integer"));
            champs.put("duree", type("integer"));
        }
        return objet(champs);
    }

    static JsonObject type(String t) { JsonObject o = new JsonObject(); o.addProperty("type", t); return o; }

    static JsonObject enumeration(List<String> valeurs) {
        JsonObject o = type("string");
        JsonArray a = new JsonArray();
        valeurs.forEach(a::add);
        o.add("enum", a);
        return o;
    }

    static JsonObject tableau(JsonObject elements) { JsonObject o = type("array"); o.add("items", elements); return o; }

    static JsonObject objet(Map<String, JsonObject> champs) {
        JsonObject o = type("object"), props = new JsonObject();
        JsonArray requis = new JsonArray();
        champs.keySet().stream().sorted().forEach(k -> { props.add(k, champs.get(k)); requis.add(k); });
        o.add("properties", props);
        o.add("required", requis);
        o.addProperty("additionalProperties", false);
        return o;
    }

    /** La requête de conversation (Groq, ou tout service compatible OpenAI). */
    static String requeteConversation(String modele, String systeme, String parole, JsonObject schema) {
        return requete(modele, systeme, parole, schema, 0.7, 700);
    }

    /** Une requête compatible OpenAI, à réponse JSON imposée par le schéma (mode strict). */
    static String requete(String modele, String systeme, String parole, JsonObject schema, double temperature, int maxJetons) {
        return requete(modele, systeme, parole, schema, temperature, maxJetons, "low");
    }

    /** Pareil, avec l'effort de réflexion des modèles qui raisonnent (« low », « medium », « high »). */
    static String requete(String modele, String systeme, String parole, JsonObject schema, double temperature, int maxJetons, String effort) {
        JsonObject r = new JsonObject();
        r.addProperty("model", modele);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systeme));
        messages.add(message("user", parole));
        r.add("messages", messages);
        r.addProperty("temperature", temperature);
        r.addProperty("max_completion_tokens", maxJetons);
        if (modele.contains("gpt-oss") || modele.contains("qwen3")) r.addProperty("reasoning_effort", effort); // « low » : moins de jetons, plus vite
        JsonObject format = new JsonObject(), js = new JsonObject();
        format.addProperty("type", "json_schema");
        js.addProperty("name", "decision");
        js.addProperty("strict", true);
        js.add("schema", schema);
        format.add("json_schema", js);
        r.add("response_format", format);
        return r.toString();
    }

    static JsonObject message(String role, String texte) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", texte);
        return m;
    }

    /** Le texte de la réponse (choices[0].message.content), ou celui d'un refus de schéma rattrapable ({@link #generationRatee}). */
    static String contenu(String corps) {
        JsonObject o = JsonParser.parseString(corps).getAsJsonObject();
        if (!o.has("choices")) { String r = generationRatee(corps); if (r != null) return r; }
        return o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
    }

    /**
     * Groq vérifie le JSON après coup : si le modèle sort du schéma (une action inventée…), il refuse (HTTP 400) mais
     * rend ce qu'il avait écrit dans error.failed_generation. On le garde s'il se lit comme un objet JSON et que ses
     * actions sont toutes connues (un id du répertoire compte comme un « faire ») : la lecture, tolérante, écarte le
     * reste. Sinon null (une action inventée : on retombe sur l'interpréteur, qui comprend peut-être la phrase).
     */
    static String generationRatee(String corps) {
        try {
            JsonElement f = JsonParser.parseString(corps).getAsJsonObject().getAsJsonObject("error").get("failed_generation");
            if (f == null || f.isJsonNull()) return null;
            String s = f.isJsonPrimitive() ? f.getAsString() : f.toString();
            return typesConnus(objetJson(s)) ? s : null;
        } catch (RuntimeException e) { return null; }
    }

    /** Vrai si chaque action (d'une réponse seule ou de groupe) a un type connu, ou un id du répertoire pour type. */
    static boolean typesConnus(JsonObject o) {
        if (o.has("actions") && o.get("actions").isJsonArray())
            for (JsonElement a : o.getAsJsonArray("actions")) {
                String t = a.isJsonObject() ? texte(a.getAsJsonObject(), "type") : "";
                if (!ACTIONS_FACE.contains(t) && !Repertoire.PAR_ID.containsKey(t)) return false;
            }
        if (o.has("reponses") && o.get("reponses").isJsonArray())
            for (JsonElement r : o.getAsJsonArray("reponses")) if (r.isJsonObject() && !typesConnus(r.getAsJsonObject())) return false;
        return true;
    }

    /** Les jetons consommés (usage.total_tokens), 0 si le service ne le dit pas. */
    static int jetons(String corps) {
        try {
            JsonObject u = JsonParser.parseString(corps).getAsJsonObject().getAsJsonObject("usage");
            return u == null || !u.has("total_tokens") ? 0 : u.get("total_tokens").getAsInt();
        } catch (RuntimeException e) { return 0; }
    }

    /**
     * Lit et revérifie la décision : missions de son catalogue seulement, nombres bornés, actions connues, phrase
     * pas trop longue. Ce qui ne va pas est simplement ignoré.
     */
    static Decision decision(String contenuJson, Set<String> idsMissions, boolean enFace) {
        return decision(objetJson(contenuJson), idsMissions, enFace);
    }

    /** Le JSON de la réponse, même si le modèle l'entoure de texte. */
    static JsonObject objetJson(String contenuJson) {
        String s = contenuJson.strip();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return JsonParser.parseString(a >= 0 && b > a ? s.substring(a, b + 1) : s).getAsJsonObject();
    }

    /** Ce que l'IA a décidé pour un groupe : à qui le chef parle, et la décision de chacun (par étiquette). */
    record Groupe(List<String> destinataires, Map<String, Decision> reponses) {}

    /** Lit et revérifie une réponse de groupe : étiquettes connues seulement, une décision par ouvrier, revérifiée comme une seule. */
    static Groupe decisionGroupe(String contenuJson, Map<String, Set<String>> idsParQui) {
        JsonObject o = objetJson(contenuJson);
        List<String> dest = new ArrayList<>();
        if (o.has("destinataires") && o.get("destinataires").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("destinataires"))
                if (e.isJsonPrimitive() && idsParQui.containsKey(e.getAsString()) && !dest.contains(e.getAsString())) dest.add(e.getAsString());
        Map<String, Decision> reponses = new java.util.LinkedHashMap<>();
        if (o.has("reponses") && o.get("reponses").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("reponses")) {
                if (!e.isJsonObject()) continue;
                String qui = texte(e.getAsJsonObject(), "qui");
                if (idsParQui.containsKey(qui) && !reponses.containsKey(qui)) reponses.put(qui, decision(e.getAsJsonObject(), idsParQui.get(qui), true));
            }
        return new Groupe(dest, reponses);
    }

    static Decision decision(JsonObject o, Set<String> idsMissions, boolean enFace) {
        String reponse = o.has("reponse") && o.get("reponse").isJsonPrimitive() ? o.get("reponse").getAsString().strip() : "";
        if (reponse.length() > 400) reponse = reponse.substring(0, 400).replaceAll("\\s+\\S*$", "") + "…";
        String humeur = texte(o, "humeur");
        if (!HUMEURS.contains(humeur)) humeur = "calme";
        List<String> permises = enFace ? ACTIONS_FACE : ACTIONS_LETTRE;
        List<Action> actions = new ArrayList<>();
        if (o.has("actions") && o.get("actions").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("actions")) {
                if (!e.isJsonObject()) continue;
                JsonObject x = e.getAsJsonObject();
                String type = texte(x, "type");
                String faire = texte(x, "faire");
                if (!permises.contains(type) && permises.contains("faire") && Repertoire.PAR_ID.containsKey(type)) { faire = type; type = "faire"; }
                if (!permises.contains(type)) continue;
                List<Mission> ms = new ArrayList<>();
                if (x.has("missions") && x.get("missions").isJsonArray())
                    for (JsonElement me : x.getAsJsonArray("missions")) {
                        if (!me.isJsonObject()) continue;
                        JsonObject m = me.getAsJsonObject();
                        String id = texte(m, "id"), mode = texte(m, "mode");
                        if (!idsMissions.contains(id)) continue;
                        ms.add(new Mission(id, mode.equals("quantite") ? "quantite" : "stock", Math.clamp(entier(m, "nombre", 64), 1, 100_000)));
                    }
                if (type.equals("missions") && ms.isEmpty()) continue;
                int debut = entier(x, "debut", -1), fin = entier(x, "fin", -1), jours = entier(x, "jours", -1);
                if (type.equals("horaires") && (debut < 0 || debut > 23 || fin < 0 || fin > 23)) continue;
                if (type.equals("expedition") && (jours < 1 || jours > 30)) continue;
                if (type.equals("faire") && !Repertoire.PAR_ID.containsKey(faire)) continue;
                String cible = texte(x, "cible").strip();
                actions.add(new Action(type, ms, vrai(x, "ajouter"), debut, fin, jours, type.equals("faire") ? faire : "",
                        cible.length() > 64 ? cible.substring(0, 64) : cible, Math.clamp(entier(x, "nombre", 0), 0, 1000), Math.clamp(entier(x, "duree", 0), 0, 3600)));
            }
        return new Decision(reponse, humeur, actions);
    }

    static String texte(JsonObject o, String cle) {
        JsonElement e = o.get(cle);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    static boolean vrai(JsonObject o, String cle) {
        try { return o.has(cle) && o.get(cle).getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    static int entier(JsonObject o, String cle, int defaut) {
        try { return o.has(cle) ? o.get(cle).getAsInt() : defaut; } catch (RuntimeException e) { return defaut; }
    }

    // --- La transcription (Whisper) ----------------------------------------------------------------------

    /** Un formulaire « multipart » avec des champs texte et un fichier. */
    static byte[] formulaire(String frontiere, Map<String, String> champs, String nomFichier, String typeFichier, byte[] fichier) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        champs.forEach((k, v) -> o.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + k + "\"\r\n\r\n" + v + "\r\n")
                .getBytes(StandardCharsets.UTF_8)));
        o.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + nomFichier + "\"\r\nContent-Type: "
                + typeFichier + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.writeBytes(fichier);
        o.writeBytes(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }

    /**
     * Le texte de la transcription. Avec les segments (verbose_json), on ne garde que ceux où Whisper a vraiment
     * entendu une voix : sur du bruit ou du silence, il invente (probabilité de non-parole haute, confiance basse).
     */
    static String transcription(String corps) {
        JsonObject o = JsonParser.parseString(corps).getAsJsonObject();
        if (o.has("segments") && o.get("segments").isJsonArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonElement e : o.getAsJsonArray("segments")) {
                if (!e.isJsonObject()) continue;
                JsonObject s = e.getAsJsonObject();
                double rien = s.has("no_speech_prob") ? s.get("no_speech_prob").getAsDouble() : 0;
                double confiance = s.has("avg_logprob") ? s.get("avg_logprob").getAsDouble() : 0;
                double repetition = s.has("compression_ratio") ? s.get("compression_ratio").getAsDouble() : 1;
                if (rien < 0.6 && confiance > -1.0 && repetition < 2.4) b.append(texte(s, "text")).append(' ');
            }
            return b.toString().strip().replaceAll("\\s+", " ");
        }
        return o.has("text") ? o.get("text").getAsString().strip() : "";
    }

    // --- La voix (Gemini) ------------------------------------------------------------------------------

    /** Requête de synthèse vocale (API « interactions » de Gemini). Rien n'est conservé chez Google au-delà de la requête (store = false). */
    static String requeteVoix(String modele, String texte, String voix, String style) {
        JsonObject r = new JsonObject();
        r.addProperty("model", modele);
        JsonObject annotation = new JsonObject();
        annotation.addProperty("type", "speech_metadata");
        annotation.addProperty("style", style);
        JsonArray annotations = new JsonArray();
        annotations.add(annotation);
        JsonObject contenu = new JsonObject();
        contenu.addProperty("type", "text");
        contenu.addProperty("text", texte);
        contenu.add("annotations", annotations);
        JsonArray contenus = new JsonArray();
        contenus.add(contenu);
        JsonObject entree = new JsonObject();
        entree.addProperty("type", "user_input");
        entree.add("content", contenus);
        JsonArray input = new JsonArray();
        input.add(entree);
        r.add("input", input);
        JsonObject format = new JsonObject();
        format.addProperty("type", "audio");
        format.addProperty("mime_type", "audio/wav");
        format.addProperty("sample_rate", 24000);
        r.add("response_format", format);
        JsonObject v = new JsonObject();
        v.addProperty("voice", voix);
        JsonArray sc = new JsonArray();
        sc.add(v);
        JsonObject gc = new JsonObject();
        gc.add("speech_config", sc);
        r.add("generation_config", gc);
        r.addProperty("store", false);
        return r.toString();
    }

    /** L'ancienne forme (generateContent), pour les modèles de voix qui ne passent pas par « interactions ». */
    static String requeteVoixAncienne(String texte, String voix, String style) {
        JsonObject r = new JsonObject();
        JsonObject part = new JsonObject();
        part.addProperty("text", "Lis ce texte en français, " + style + " : " + texte);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject c = new JsonObject();
        c.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(c);
        r.add("contents", contents);
        JsonObject pv = new JsonObject();
        pv.addProperty("voiceName", voix);
        JsonObject vc = new JsonObject();
        vc.add("prebuiltVoiceConfig", pv);
        JsonObject sc = new JsonObject();
        sc.add("voiceConfig", vc);
        JsonObject gc = new JsonObject();
        JsonArray mod = new JsonArray();
        mod.add("AUDIO");
        gc.add("responseModalities", mod);
        gc.add("speechConfig", sc);
        r.add("generationConfig", gc);
        return r.toString();
    }

    /** Le son de la réponse, où qu'il soit : un contenu « audio » avec ses données (interactions) ou un inlineData (generateContent). */
    static byte[] audio(String corps) {
        String b64 = chercherAudio(JsonParser.parseString(corps));
        if (b64 == null) throw new IllegalStateException("pas de son dans la réponse");
        return Base64.getDecoder().decode(b64);
    }

    static String chercherAudio(JsonElement e) {
        if (e.isJsonArray()) {
            String trouve = null;
            for (JsonElement x : e.getAsJsonArray()) { String s = chercherAudio(x); if (s != null) trouve = s; } // le dernier, comme la doc
            return trouve;
        }
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (o.has("data") && o.get("data").isJsonPrimitive()
                && ("audio".equals(texte(o, "type")) || texte(o, "mime_type").startsWith("audio") || texte(o, "mimeType").startsWith("audio")))
            return o.get("data").getAsString();
        for (String k : o.keySet()) { String s = chercherAudio(o.get(k)); if (s != null) return s; }
        return null;
    }

    /**
     * Combien de temps attendre après un refus pour quota (en ms) : l'en-tête retry-after, sinon le « retryDelay »
     * de Google, sinon son « Please retry in 6h51m50s », sinon une heure.
     */
    static long attente(String retryAfter, String corps) {
        try { if (!retryAfter.isBlank()) return (long) (Double.parseDouble(retryAfter.strip()) * 1000); } catch (NumberFormatException ignore) {}
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"([\\d.]+)s\"").matcher(corps);
        if (m.find()) return (long) (Double.parseDouble(m.group(1)) * 1000);
        m = java.util.regex.Pattern.compile("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").matcher(corps);
        if (m.find() && m.group(0).length() > "retry in ".length()) {
            double s = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 3600) + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)) * 60)
                    + (m.group(3) == null ? 0 : Double.parseDouble(m.group(3)));
            return (long) (s * 1000);
        }
        return 3_600_000;
    }

    /** Le message d'erreur d'un service (error.message), pour le panneau. */
    static String erreur(String corps) {
        try {
            JsonElement e = JsonParser.parseString(corps);
            if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                JsonElement er = e.getAsJsonObject().get("error");
                if (er.isJsonObject() && er.getAsJsonObject().has("message")) return er.getAsJsonObject().get("message").getAsString();
                return er.toString();
            }
        } catch (RuntimeException ignore) {}
        return corps.length() > 200 ? corps.substring(0, 200) + "…" : corps;
    }
}

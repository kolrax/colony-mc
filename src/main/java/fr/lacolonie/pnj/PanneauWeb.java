package fr.lacolonie.pnj;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Le panneau d'admin, servi par le serveur Minecraft lui-même (http://adresse-du-serveur:port).
 * Protégé par le mot de passe « jeton_panneau » de reglages.json (/pnj panneau pour le copier).
 */
public final class PanneauWeb {
    private PanneauWeb() {}

    static HttpServer http;
    static MinecraftServer serveur;

    public static void demarrer(MinecraftServer s) {
        serveur = s;
        arreter();
        // Démarré depuis un fil « démon » : le fil interne du serveur HTTP l'est aussi et n'empêche jamais
        // Java de s'arrêter (après un plantage, NeoForge ne prévient pas les mods de l'arrêt du serveur).
        Thread demarrage = new Thread(() -> {
            try {
                HttpServer h = HttpServer.create(new InetSocketAddress(Reglages.actuel.port_panneau), 0);
                h.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "colonie-panneau"); t.setDaemon(true); return t; }));
                h.createContext("/", PanneauWeb::traiter);
                h.start();
                http = h;
                ColoniePnj.LOG.info("Panneau des PNJ ouvert sur le port {}", Reglages.actuel.port_panneau);
            } catch (IOException e) {
                ColoniePnj.LOG.error("Impossible d'ouvrir le panneau sur le port {}", Reglages.actuel.port_panneau, e);
            }
        }, "colonie-panneau-demarrage");
        demarrage.setDaemon(true);
        demarrage.start();
        try { demarrage.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void arreter() {
        if (http != null) http.stop(0);
        http = null;
    }

    public static String adresse() {
        String ip = serveur != null && !serveur.getLocalIp().isBlank() ? serveur.getLocalIp() : "adresse-du-serveur";
        return "http://" + ip + ":" + Reglages.actuel.port_panneau + (http == null ? " (fermé : port indisponible)" : "");
    }

    // --- Aiguillage ---------------------------------------------------------------------------------

    static void traiter(HttpExchange ex) throws IOException {
        try {
            String chemin = ex.getRequestURI().getPath();
            if (chemin.equals("/") || chemin.equals("/index.html")) { ressource(ex, "/web/index.html", "text/html; charset=utf-8"); return; }
            if (chemin.equals("/guide.md")) { ressource(ex, "/web/guide.md", "text/markdown; charset=utf-8"); return; }
            if (!chemin.startsWith("/api/")) { envoyer(ex, 404, "text/plain", "introuvable"); return; }
            String jeton = ex.getRequestHeaders().getFirst("X-Jeton");
            if (jeton == null || !MessageDigest.isEqual(jeton.getBytes(StandardCharsets.UTF_8), Reglages.actuel.jeton_panneau.getBytes(StandardCharsets.UTF_8))) {
                Thread.sleep(400);
                json(ex, 401, Map.of("erreur", "mot de passe incorrect"));
                return;
            }
            JsonObject corps = ex.getRequestMethod().equals("POST")
                    ? JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            Object reponse = switch (chemin) {
                case "/api/etat" -> surServeur(PanneauWeb::etat);
                case "/api/script" -> script(requete(ex, "id"));
                case "/api/script/enregistrer" -> surServeur(() -> enregistrer(chaine(corps, "id"), chaine(corps, "source")));
                case "/api/script/verifier" -> surServeur(() -> resultat(Lua.verifier(chaine(corps, "id"), chaine(corps, "source"))));
                case "/api/script/supprimer" -> surServeur(() -> supprimer(chaine(corps, "id")));
                case "/api/script/defaut" -> surServeur(() -> defaut(chaine(corps, "id")));
                case "/api/metier/actif" -> surServeur(() -> actif(chaine(corps, "id"), corps.get("actif").getAsBoolean()));
                case "/api/reglages" -> surServeur(() -> reglages(corps));
                case "/api/pnj/apparaitre" -> surServeur(() -> apparaitre(corps));
                case "/api/pnj/action" -> surServeur(() -> actionPnj(corps));
                case "/api/recharger" -> surServeur(() -> { Reglages.charger(); Lua.charger(); return resultat(null); });
                default -> null;
            };
            if (reponse == null) envoyer(ex, 404, "text/plain", "introuvable");
            else json(ex, 200, reponse);
        } catch (Exception e) {
            ColoniePnj.LOG.warn("Panneau : requête en erreur", e);
            json(ex, 500, Map.of("erreur", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    static <T> T surServeur(Supplier<T> f) throws Exception {
        return serveur.submit(f).get(15, TimeUnit.SECONDS);
    }

    static Map<String, Object> resultat(String erreur) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", erreur == null);
        if (erreur != null) m.put("erreur", erreur);
        return m;
    }

    // --- Lecture ------------------------------------------------------------------------------------

    static Map<String, Object> etat() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> metiers = new ArrayList<>();
        try (var fichiers = Files.list(Reglages.dossierMetiers())) {
            for (Path p : fichiers.filter(f -> f.toString().endsWith(".lua")).sorted().toList()) {
                String id = p.getFileName().toString().replace(".lua", "");
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", id);
                x.put("nom", id.equals("commun") ? "Cerveau commun" : Lua.nomMetier(id));
                x.put("description", id.equals("commun") ? "Besoins, paie, ordres : appelé avant chaque métier." : Lua.descriptionMetier(id));
                x.put("actif", !Reglages.actuel.metiers_inactifs.contains(id));
                x.put("commun", id.equals("commun"));
                x.put("base", Reglages.SCRIPTS_DE_BASE.contains(id));
                x.put("erreur", Lua.ERREURS.get(id));
                metiers.add(x);
            }
        } catch (IOException e) {
            m.put("erreur", "dossier des métiers illisible");
        }
        m.put("metiers", metiers);
        List<Map<String, Object>> pnj = new ArrayList<>();
        for (Travailleur t : ColoniePnj.tous(serveur)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("uuid", t.getUUID().toString());
            x.put("nom", t.prenom);
            x.put("metier", t.metier);
            x.put("niveau", t.niveau);
            x.put("temperament", t.temperament);
            x.put("mode", t.mode.lua);
            x.put("aTerre", t.aTerre);
            x.put("chef", t.chefNom);
            x.put("skin", t.skin());
            x.put("position", t.blockPosition().toShortString());
            x.put("dimension", t.level().dimension().location().toString());
            x.put("vie", Math.ceil(t.getHealth()));
            x.put("faim", t.faim);
            x.put("impayes", t.impayes);
            x.put("besoin", t.besoin);
            x.put("action", t.action == null ? "" : t.action.nom);
            x.put("resultat", t.resultat);
            x.put("bureau", t.bureau == null ? "" : t.bureau.toShortString());
            x.put("commande", Lua.avancementTexte(t));
            x.put("fatigue", t.fatigue);
            x.put("sortie", t.sortieTexte() + (t.bureau == null ? "" : t.monLit() == null ? " · sans lit" : " · lit"));
            pnj.add(x);
        }
        m.put("pnj", pnj);
        JsonObject r = Reglages.GSON.toJsonTree(Reglages.actuel).getAsJsonObject();
        r.remove("jeton_panneau");
        m.put("reglages", r);
        m.put("joueurs", serveur.getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList());
        synchronized (Lua.class) { m.put("journal", new ArrayList<>(Lua.JOURNAL)); }
        return m;
    }

    static Map<String, Object> script(String id) throws IOException {
        verifierId(id);
        Path p = Reglages.dossierMetiers().resolve(id + ".lua");
        if (!Files.exists(p)) return Map.of("erreur", "script introuvable");
        return Map.of("id", id, "source", Files.readString(p, StandardCharsets.UTF_8));
    }

    // --- Écriture -----------------------------------------------------------------------------------

    static void verifierId(String id) {
        if (id == null || !id.matches("[a-z0-9_]{2,32}")) throw new IllegalArgumentException("identifiant invalide : lettres minuscules, chiffres et _ (2 à 32)");
    }

    /** Range l'ancienne version dans metiers/historique/ avant de la remplacer ou de la retirer. */
    static void archiver(String id) throws IOException {
        Path p = Reglages.dossierMetiers().resolve(id + ".lua");
        if (!Files.exists(p)) return;
        Path h = Reglages.dossier().resolve("historique");
        Files.createDirectories(h);
        Files.copy(p, h.resolve(id + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".lua"), StandardCopyOption.REPLACE_EXISTING);
    }

    static Map<String, Object> enregistrer(String id, String source) {
        verifierId(id);
        String erreur = Lua.verifier(id, source);
        if (erreur != null) return resultat(erreur);
        try {
            archiver(id);
            Files.writeString(Reglages.dossierMetiers().resolve(id + ".lua"), source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return resultat("écriture impossible : " + e.getMessage());
        }
        Lua.charger();
        Lua.journal("Script " + id + ".lua enregistré depuis le panneau");
        return resultat(null);
    }

    static Map<String, Object> supprimer(String id) {
        verifierId(id);
        if (id.equals("commun")) return resultat("le cerveau commun ne peut pas être retiré");
        try {
            archiver(id);
            Files.deleteIfExists(Reglages.dossierMetiers().resolve(id + ".lua"));
        } catch (IOException e) {
            return resultat(e.getMessage());
        }
        Lua.charger();
        Lua.journal("Métier " + id + " retiré (copie dans historique/)");
        return resultat(null);
    }

    static Map<String, Object> defaut(String id) {
        verifierId(id);
        if (!Reglages.SCRIPTS_DE_BASE.contains(id)) return resultat("pas de version d'origine pour " + id);
        try {
            archiver(id);
            Files.writeString(Reglages.dossierMetiers().resolve(id + ".lua"), Reglages.scriptDeBase(id), StandardCharsets.UTF_8);
            Reglages.installerScriptsDeBase();
        } catch (IOException e) {
            return resultat(e.getMessage());
        }
        Lua.charger();
        return resultat(null);
    }

    static Map<String, Object> actif(String id, boolean actif) {
        verifierId(id);
        Reglages.actuel.metiers_inactifs.remove(id);
        if (!actif) Reglages.actuel.metiers_inactifs.add(id);
        try { Reglages.sauver(); } catch (IOException e) { return resultat(e.getMessage()); }
        return resultat(null);
    }

    static Map<String, Object> reglages(JsonObject corps) {
        try {
            Reglages.remplacer(Reglages.GSON.fromJson(corps, Reglages.class));
        } catch (Exception e) {
            return resultat("réglages invalides : " + e.getMessage());
        }
        return resultat(null);
    }

    static Map<String, Object> apparaitre(JsonObject c) {
        String metier = chaine(c, "metier");
        if (!Lua.metierExiste(metier)) return resultat("métier inconnu : " + metier);
        ServerPlayer p = serveur.getPlayerList().getPlayerByName(chaine(c, "joueur"));
        if (p == null) return resultat("ce joueur n'est pas connecté");
        ServerLevel l = p.serverLevel();
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(l);
        if (t == null) return resultat("création impossible");
        var devant = p.position().add(p.getLookAngle().multiply(2, 0, 2));
        t.moveTo(devant.x, p.getY(), devant.z, p.getYRot() + 180, 0);
        t.tirerAuSort(metier, c.has("niveau") ? c.get("niveau").getAsInt() : 0);
        String nom = chaine(c, "nom");
        if (!nom.isBlank()) { t.prenom = nom.trim(); t.majNom(); }
        t.setSkin(chaine(c, "skin"));
        l.addFreshEntity(t);
        Lua.journal("Panneau : " + t.prenom + " (" + metier + ") apparaît près de " + p.getName().getString());
        return resultat(null);
    }

    static Map<String, Object> actionPnj(JsonObject c) {
        UUID id = UUID.fromString(chaine(c, "uuid"));
        Travailleur t = ColoniePnj.tous(serveur).stream().filter(x -> x.getUUID().equals(id)).findFirst().orElse(null);
        if (t == null) return resultat("PNJ introuvable");
        switch (chaine(c, "action")) {
            case "relever" -> { if (t.aTerre) { t.aTerre = false; t.setPose(net.minecraft.world.entity.Pose.STANDING); } t.setHealth(t.getMaxHealth()); t.faim = 20; t.majNom(); }
            case "licencier" -> {
                t.chef = null;
                t.chefNom = "";
                t.mode = Travailleur.Mode.CHOMEUR;
                Bureau.liberer(t);
                t.arrivee = System.currentTimeMillis();
                t.finirAction("licencie");
                t.libererChunk();
                t.majNom();
            }
            case "supprimer" -> t.discard();
            case "skin" -> t.setSkin(chaine(c, "valeur"));
            case "nom" -> { t.prenom = chaine(c, "valeur").trim(); t.majNom(); }
            default -> { return resultat("action inconnue"); }
        }
        Lua.journal("Panneau : " + chaine(c, "action") + " sur " + t.prenom);
        return resultat(null);
    }

    // --- HTTP -------------------------------------------------------------------------------------

    static String chaine(JsonObject o, String cle) {
        JsonElement e = o.get(cle);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static String requete(HttpExchange ex, String cle) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return "";
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(cle)) return java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    static void ressource(HttpExchange ex, String chemin, String type) throws IOException {
        try (InputStream in = PanneauWeb.class.getResourceAsStream(chemin)) {
            if (in == null) { envoyer(ex, 404, "text/plain", "introuvable"); return; }
            envoyer(ex, 200, type, in.readAllBytes());
        }
    }

    static void json(HttpExchange ex, int code, Object o) throws IOException {
        envoyer(ex, code, "application/json; charset=utf-8", Reglages.GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    static void envoyer(HttpExchange ex, int code, String type, String texte) throws IOException {
        envoyer(ex, code, type, texte.getBytes(StandardCharsets.UTF_8));
    }

    static void envoyer(HttpExchange ex, int code, String type, byte[] corps) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, corps.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(corps); }
    }
}

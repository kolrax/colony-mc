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
import java.security.MessageDigest;
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
                case "/api/metier/actif" -> surServeur(() -> actif(chaine(corps, "id"), corps.get("actif").getAsBoolean()));
                case "/api/reglages" -> surServeur(() -> reglages(corps));
                case "/api/pnj/apparaitre" -> surServeur(() -> apparaitre(corps));
                case "/api/pnj/action" -> surServeur(() -> actionPnj(corps));
                case "/api/recharger" -> surServeur(() -> { Reglages.charger(); ReglagesIA.charger(); return resultat(null); });
                case "/api/ia" -> IA.etat();
                case "/api/repertoire" -> Map.of("actions", Repertoire.tableau());
                case "/api/ia/ecoute" -> Map.of("journal", Dialogue.journal(), "parametres", Adressage.ecrire(Adressage.P).toString());
                case "/api/ia/reglages" -> { ReglagesIA.modifier(corps); yield resultat(null); }
                case "/api/ia/tester" -> Map.of("resultats", IA.tester().get(40, TimeUnit.SECONDS));
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
        for (Metier mt : Metier.TOUS.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", mt.id);
            x.put("nom", mt.nom);
            x.put("description", mt.description);
            x.put("actif", !Reglages.actuel.metiers_inactifs.contains(mt.id));
            x.put("commandes", mt.commandes.stream().map(c -> c.nom).toList());
            metiers.add(x);
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
            x.put("action", t.consigne != null && t.consigne.entree() != null ? "« " + t.consigne.entree().nom() + " »" : t.action == null ? "" : t.action.nom);
            x.put("resultat", t.resultat);
            x.put("bureau", t.bureau == null ? "" : t.bureau.toShortString());
            x.put("commande", Metier.avancementTexte(t));
            x.put("fatigue", t.fatigue);
            x.put("statut", t.statut());
            x.put("route", t.action == null ? "" : t.diag);
            x.put("sortie", t.bureau == null ? "" : ((int) t.distanceAuBureau() + " blocs du bureau" + (t.sortieTexte().isEmpty() ? "" : ", " + t.sortieTexte())));
            pnj.add(x);
        }
        m.put("pnj", pnj);
        JsonObject r = Reglages.GSON.toJsonTree(Reglages.actuel).getAsJsonObject();
        r.remove("jeton_panneau");
        m.put("reglages", r);
        m.put("joueurs", serveur.getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList());
        synchronized (Cerveau.class) { m.put("journal", new ArrayList<>(Cerveau.JOURNAL)); }
        return m;
    }

    // --- Écriture -----------------------------------------------------------------------------------

    static Map<String, Object> actif(String id, boolean actif) {
        if (!Metier.existe(id)) return resultat("métier inconnu : " + id);
        Reglages.actuel.metiers_inactifs.remove(id);
        if (!actif) Reglages.actuel.metiers_inactifs.add(id);
        try { Reglages.sauver(); } catch (IOException e) { return resultat(e.getMessage()); }
        return resultat(null);
    }

    static Map<String, Object> reglages(JsonObject corps) {
        try {
            Reglages.remplacer(Reglages.GSON.fromJson(corps, Reglages.class));
            // Un délai entre paies raccourci s'applique tout de suite (sinon la paie suivante resterait au lendemain).
            long max = System.currentTimeMillis() + Travailleur.periodePaie();
            for (Travailleur t : ColoniePnj.tous(serveur)) if (t.prochainePaie > max && t.prochainePaie != Monnaie.SANS_PAIE) t.prochainePaie = max; // pas les ouvriers sans paie (banc)
        } catch (Exception e) {
            return resultat("réglages invalides : " + e.getMessage());
        }
        return resultat(null);
    }

    static Map<String, Object> apparaitre(JsonObject c) {
        String metier = chaine(c, "metier");
        if (!Metier.existe(metier)) return resultat("métier inconnu : " + metier);
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
        Cerveau.journal("Panneau : " + t.prenom + " (" + metier + ") apparaît près de " + p.getName().getString());
        return resultat(null);
    }

    static Map<String, Object> actionPnj(JsonObject c) {
        UUID id = UUID.fromString(chaine(c, "uuid"));
        Travailleur t = ColoniePnj.tous(serveur).stream().filter(x -> x.getUUID().equals(id)).findFirst().orElse(null);
        if (t == null) return resultat("PNJ introuvable");
        switch (chaine(c, "action")) {
            case "relever" -> { if (t.aTerre) { t.aTerre = false; t.setPose(net.minecraft.world.entity.Pose.STANDING); } t.setHealth(t.getMaxHealth()); t.faim = 20; t.majNom(); }
            case "licencier" -> t.licencier();
            case "supprimer" -> t.discard();
            case "skin" -> t.setSkin(chaine(c, "valeur"));
            case "nom" -> { t.prenom = chaine(c, "valeur").trim(); t.majNom(); }
            default -> { return resultat("action inconnue"); }
        }
        Cerveau.journal("Panneau : " + chaine(c, "action") + " sur " + t.prenom);
        return resultat(null);
    }

    // --- HTTP -------------------------------------------------------------------------------------

    static String chaine(JsonObject o, String cle) {
        JsonElement e = o.get(cle);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
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

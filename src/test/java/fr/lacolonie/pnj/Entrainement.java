package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * L'entraînement de l'adressage (à qui le chef parle), sur les situations de {@code entrainement/} :
 * <ul>
 * <li>{@code code} : apprend les poids de la formule du code (régression logistique, régularisation choisie par
 *   validation croisée sur des familles de phrases), le seuil de décision et la zone où le code est sûr ;</li>
 * <li>{@code ia <modèle> <n>} : demande l'avis de l'IA (la consigne de production) sur les situations réelles et sur
 *   {@code n} situations de test ; les réponses sont gardées en cache ({@code cache_ia.json}) ;</li>
 * <li>{@code fusion <modèle>} : apprend le croisement code × IA et son seuil, mesuré en validation croisée.</li>
 * </ul>
 * Les paramètres vont dans {@code src/main/resources/adressage.json}. Lancer : {@code ./gradlew entrainer --args="code"}.
 */
public class Entrainement {
    static final Path DOSSIER = Path.of("entrainement");
    static final Path PARAMS = Path.of("src/main/resources/adressage.json");
    static final Path CACHE = DOSSIER.resolve("cache_ia.json");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    record Cas(String id, String categorie, String famille, String jeu, Adressage.Situation s, Set<String> attendu, String note) {}

    public static void main(String[] a) throws Exception {
        switch (a.length == 0 ? "code" : a[0]) {
            case "code" -> code();
            case "ia" -> ia(a[1], Integer.parseInt(a[2]), a.length > 3 ? a[3] : "");
            case "fusion" -> fusion(a[1], a.length > 2 && a[2].equals("reels"), !(a.length > 2 && a[2].equals("essai")));
            case "bilan" -> bilan(a.length > 1 ? a[1] : "");
            case "detail" -> detail(a[1]);
            default -> System.out.println("modes : code | ia <modèle> <n> | fusion <modèle> | bilan");
        }
    }

    // --- Les données ------------------------------------------------------------------------------------------

    static List<Cas> charger(String fichier) throws Exception {
        List<Cas> l = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(DOSSIER.resolve(fichier), StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            List<Adressage.Pnj> pnj = new ArrayList<>();
            for (JsonElement x : o.getAsJsonArray("pnj")) {
                JsonObject p = x.getAsJsonObject();
                List<String> derniers = new ArrayList<>();
                if (p.has("derniers")) p.getAsJsonArray("derniers").forEach(d -> derniers.add(d.getAsString()));
                pnj.add(new Adressage.Pnj(p.get("prenom").getAsString(), p.get("metier").getAsString(), p.get("distance").getAsDouble(),
                        p.get("regard").getAsDouble(), p.get("depuis").getAsDouble(), p.get("question").getAsBoolean(), p.get("fenetre").getAsBoolean(), derniers));
            }
            List<Adressage.Joueur> js = new ArrayList<>();
            for (JsonElement x : o.getAsJsonArray("joueurs")) {
                JsonObject j = x.getAsJsonObject();
                js.add(new Adressage.Joueur(j.get("nom").getAsString(), j.get("distance").getAsDouble(), j.get("regard").getAsDouble()));
            }
            Set<String> attendu = new TreeSet<>();
            o.getAsJsonArray("attendu").forEach(x -> attendu.add(x.getAsString()));
            l.add(new Cas(o.get("id").getAsString(), o.get("categorie").getAsString(), o.get("famille").getAsString(), o.get("jeu").getAsString(),
                    new Adressage.Situation(o.get("texte").getAsString(), o.get("micro").getAsBoolean(), pnj, js), attendu,
                    o.has("note") ? o.get("note").getAsString() : ""));
        }
        return l;
    }

    static JsonObject parametresBruts() throws Exception {
        return Files.exists(PARAMS) ? JsonParser.parseString(Files.readString(PARAMS, StandardCharsets.UTF_8)).getAsJsonObject() : null;
    }

    static Adressage.Parametres parametres() throws Exception {
        JsonObject o = parametresBruts();
        return o == null ? Adressage.charger() : Adressage.lire(o);
    }

    static void sauver(Adressage.Parametres p, JsonObject mesures) throws Exception {
        JsonObject o = Adressage.ecrire(p);
        JsonObject avant = parametresBruts();
        if (avant != null && avant.has("mesures")) for (var e : avant.getAsJsonObject("mesures").entrySet()) if (!mesures.has(e.getKey())) mesures.add(e.getKey(), e.getValue());
        o.add("mesures", mesures);
        o.addProperty("entraine_le", java.time.LocalDate.now().toString());
        Files.writeString(PARAMS, GSON.toJson(o), StandardCharsets.UTF_8);
    }

    // --- La régression logistique (Newton, régularisation L2 hors biais) -------------------------------------------

    static double[] ajuster(List<double[]> X, List<Integer> y, double lambda) {
        int d = X.get(0).length + 1;
        double[] w = new double[d];
        for (int it = 0; it < 60; it++) {
            double[] g = new double[d];
            double[][] h = new double[d][d];
            for (int n = 0; n < X.size(); n++) {
                double[] x = Arrays.copyOf(X.get(n), d);
                x[d - 1] = 1;
                double z = 0;
                for (int i = 0; i < d; i++) z += w[i] * x[i];
                double p = Adressage.sigmoide(z), r = p - y.get(n), v = Math.max(p * (1 - p), 1e-9);
                for (int i = 0; i < d; i++) {
                    g[i] += r * x[i];
                    for (int j = 0; j < d; j++) h[i][j] += v * x[i] * x[j];
                }
            }
            for (int i = 0; i < d - 1; i++) { g[i] += lambda * w[i]; h[i][i] += lambda; }
            h[d - 1][d - 1] += 1e-6;
            double[] pas = resoudre(h, g);
            double norme = 0;
            for (int i = 0; i < d; i++) { w[i] -= pas[i]; norme += pas[i] * pas[i]; }
            if (norme < 1e-14) break;
        }
        return w;
    }

    static double[] resoudre(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = Arrays.copyOf(a0[i], n);
        double[] b = Arrays.copyOf(b0, n);
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] t = a[c]; a[c] = a[piv]; a[piv] = t;
            double tb = b[c]; b[c] = b[piv]; b[piv] = tb;
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) a[r][k] -= f * a[c][k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= a[r][k] * x[k];
            x[r] = s / a[r][r];
        }
        return x;
    }

    static double logLoss(List<double[]> X, List<Integer> y, double[] w) {
        double s = 0;
        for (int n = 0; n < X.size(); n++) {
            double z = w[w.length - 1];
            for (int i = 0; i < X.get(n).length; i++) z += w[i] * X.get(n)[i];
            double p = Math.min(1 - 1e-9, Math.max(1e-9, Adressage.sigmoide(z)));
            s -= y.get(n) == 1 ? Math.log(p) : Math.log(1 - p);
        }
        return s / X.size();
    }

    // --- Mesurer -----------------------------------------------------------------------------------------------

    record Bilan(int n, int exacts, int pourRien, int oublis, List<String> erreurs) {
        String ligne() {
            return String.format("%d situations : %.1f %% justes, %.1f %% répondent pour rien, %.1f %% en oublient", n, 100.0 * exacts / n,
                    100.0 * pourRien / n, 100.0 * oublis / n);
        }
    }

    static Set<String> noms(Cas c, List<Integer> l) {
        Set<String> r = new TreeSet<>();
        for (int i : l) r.add(c.s().pnj().get(i).prenom());
        return r;
    }

    static Bilan evaluer(List<Cas> cas, Function<Cas, double[]> proba, double seuil) {
        int exacts = 0, pourRien = 0, oublis = 0;
        List<String> erreurs = new ArrayList<>();
        for (Cas c : cas) {
            double[] p = proba.apply(c);
            Set<String> choisis = noms(c, Adressage.choisir(c.s(), p, seuil));
            boolean fp = choisis.stream().anyMatch(x -> !c.attendu().contains(x)), fn = c.attendu().stream().anyMatch(x -> !choisis.contains(x));
            if (!fp && !fn) exacts++;
            if (fp) pourRien++;
            if (fn) oublis++;
            if (fp || fn) {
                StringBuilder b = new StringBuilder(String.format("[%s %s] « %s » attendu %s, choisi %s :", c.categorie(), c.id(), c.s().texte(), c.attendu(), choisis));
                for (int i = 0; i < p.length; i++) b.append(String.format(" %s=%.0f%%", c.s().pnj().get(i).prenom(), p[i] * 100));
                erreurs.add(b.toString());
            }
        }
        return new Bilan(cas.size(), exacts, pourRien, oublis, erreurs);
    }

    static void montrer(String titre, Bilan b, int maxErreurs) {
        System.out.println(titre + " — " + b.ligne());
        b.erreurs().stream().limit(maxErreurs).forEach(e -> System.out.println("    " + e));
    }

    static double meilleurSeuil(List<Cas> cas, Function<Cas, double[]> proba) {
        double meilleur = 0.5;
        double score = -1;
        for (double t = 0.2; t <= 0.85; t += 0.01) {
            Bilan b = evaluer(cas, proba, t);
            double s = b.exacts() - 0.5 * b.pourRien(); // répondre pour rien gêne plus qu'un oubli (on peut répéter)
            if (s > score + 1e-9) { score = s; meilleur = Math.round(t * 100) / 100.0; }
        }
        return meilleur;
    }

    static JsonObject mesure(Bilan b) {
        JsonObject o = new JsonObject();
        o.addProperty("situations", b.n());
        o.addProperty("justes", Math.round(1000.0 * b.exacts() / b.n()) / 10.0);
        o.addProperty("pour_rien", Math.round(1000.0 * b.pourRien() / b.n()) / 10.0);
        o.addProperty("oublis", Math.round(1000.0 * b.oublis() / b.n()) / 10.0);
        return o;
    }

    // --- Le code -------------------------------------------------------------------------------------------------

    /** Les exemples de la formule : seulement les situations où la phrase ne désigne pas elle-même ses destinataires (sinon, la grammaire tranche). */
    static void paires(List<Cas> cas, List<double[]> X, List<Integer> y) {
        for (Cas c : cas) {
            if (Adressage.explicite(Ordres.adresse(c.s().texte(), c.s().pnj().stream().map(Adressage.Pnj::prenom).toList(),
                    c.s().pnj().stream().map(Adressage.Pnj::metier).toList()))) continue;
            double[][] f = Adressage.indices(c.s());
            for (int i = 0; i < f.length; i++) {
                X.add(f[i]);
                y.add(c.attendu().contains(c.s().pnj().get(i).prenom()) ? 1 : 0);
            }
        }
    }

    static Adressage.Parametres avecPoids(Adressage.Parametres p, double[] w, double seuilCode, double bas, double haut) {
        return new Adressage.Parametres(Arrays.copyOf(w, w.length - 1), w[w.length - 1], p.fusionCode(), p.fusionIa(), p.fusionBiais(), p.seuil(),
                seuilCode, bas, haut);
    }

    static void code() throws Exception {
        List<Cas> tous = charger("scenarios.json"), reels = charger("reels.json");
        List<Cas> ent = tous.stream().filter(c -> c.jeu().equals("entrainement")).toList(), test = tous.stream().filter(c -> c.jeu().equals("test")).toList();
        System.out.printf("%d situations d'entraînement, %d de test (familles jamais vues), %d réelles ; %d indices%n", ent.size(), test.size(), reels.size(),
                Adressage.INDICES.size());
        // La force de la régularisation : validation croisée en 5 groupes de familles (une famille n'est jamais à la fois apprise et mesurée).
        List<String> familles = new ArrayList<>(new TreeSet<>(ent.stream().map(Cas::famille).toList()));
        Collections.shuffle(familles, new Random(7));
        double meilleurL = 1, meilleurPerte = Double.MAX_VALUE;
        for (double lambda : new double[]{0.05, 0.1, 0.2, 0.3, 0.5, 1, 2, 3, 10}) {
            double perte = 0;
            for (int k = 0; k < 5; k++) {
                Set<String> pli = new java.util.HashSet<>();
                for (int i = k; i < familles.size(); i += 5) pli.add(familles.get(i));
                List<double[]> xa = new ArrayList<>(), xv = new ArrayList<>();
                List<Integer> ya = new ArrayList<>(), yv = new ArrayList<>();
                paires(ent.stream().filter(c -> !pli.contains(c.famille())).toList(), xa, ya);
                paires(ent.stream().filter(c -> pli.contains(c.famille())).toList(), xv, yv);
                perte += logLoss(xv, yv, ajuster(xa, ya, lambda));
            }
            System.out.printf("  régularisation %.1f : perte en validation croisée %.4f%n", lambda, perte / 5);
            if (perte < meilleurPerte) { meilleurPerte = perte; meilleurL = lambda; }
        }
        List<double[]> X = new ArrayList<>();
        List<Integer> y = new ArrayList<>();
        paires(ent, X, y);
        double[] w = ajuster(X, y, meilleurL);
        Adressage.Parametres base = parametres();
        Adressage.Parametres p0 = avecPoids(base, w, 0.5, base.bas(), base.haut());
        Function<Cas, double[]> proba = c -> Adressage.code(c.s(), p0);
        double seuil = meilleurSeuil(ent, proba);
        // La zone sûre : sous « bas », presque aucun destinataire ; au-dessus de « haut », presque aucun intrus (sur l'entraînement).
        List<Double> positifs = new ArrayList<>(), negatifs = new ArrayList<>();
        for (Cas c : ent) {
            double[] p = proba.apply(c);
            for (int i = 0; i < p.length; i++) (c.attendu().contains(c.s().pnj().get(i).prenom()) ? positifs : negatifs).add(p[i]);
        }
        Collections.sort(positifs);
        negatifs.sort(Comparator.reverseOrder());
        double bas = Math.min(0.05, positifs.get((int) (positifs.size() * 0.005)));
        double haut = Math.max(0.95, negatifs.get((int) (negatifs.size() * 0.005)));
        Adressage.Parametres p = avecPoids(base, w, seuil, Math.round(bas * 1000) / 1000.0, Math.round(haut * 1000) / 1000.0);
        Function<Cas, double[]> pr = c -> Adressage.code(c.s(), p);
        System.out.printf("régularisation retenue %.1f ; seuil %.2f ; zone sûre : sous %.3f ou au-dessus de %.3f%n", meilleurL, seuil, p.bas(), p.haut());
        Bilan be = evaluer(ent, pr, seuil), bt = evaluer(test, pr, seuil), br = evaluer(reels, pr, seuil);
        montrer("CODE SEUL, entraînement", be, 8);
        montrer("CODE SEUL, test (phrases jamais vues)", bt, 40);
        montrer("CODE SEUL, situations réelles", br, 40);
        // Par catégorie, sur le test.
        Map<String, List<Cas>> parCat = new LinkedHashMap<>();
        for (Cas c : test) parCat.computeIfAbsent(c.categorie(), k -> new ArrayList<>()).add(c);
        parCat.forEach((k, v) -> System.out.printf("    test %-13s %s%n", k, evaluer(v, pr, seuil).ligne()));
        // Les situations que le code tranche seul (sans l'IA) : sont-elles justes ?
        List<Cas> surs = new ArrayList<>();
        for (Cas c : concat(test, reels)) if (Adressage.sur(c.s(), pr.apply(c), p)) surs.add(c);
        montrer("CODE SÛR DE LUI (l'IA n'est pas consultée), test + réelles", evaluer(surs, pr, seuil), 20);
        // Les poids, du plus fort au plus faible.
        Integer[] ordre = new Integer[Adressage.INDICES.size()];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        Arrays.sort(ordre, Comparator.comparingDouble(i -> -Math.abs(w[i])));
        StringBuilder b = new StringBuilder("poids :");
        for (int i : ordre) b.append(String.format(" %s=%.2f", Adressage.INDICES.get(i), w[i]));
        System.out.println(b.append(String.format(" biais=%.2f", w[w.length - 1])));
        JsonObject m = new JsonObject();
        m.add("code_entrainement", mesure(be));
        m.add("code_test", mesure(bt));
        m.add("code_reels", mesure(br));
        sauver(p, m);
        System.out.println("→ " + PARAMS);
    }

    static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    // --- L'IA ------------------------------------------------------------------------------------------------------

    static JsonObject cache() throws Exception {
        return Files.exists(CACHE) ? JsonParser.parseString(Files.readString(CACHE, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
    }

    static String cle(String modele, Cas c, String consigne) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest((modele + "\n" + consigne).getBytes(StandardCharsets.UTF_8));
        return modele + "|" + c.id() + "|" + HexFormat.of().formatHex(h, 0, 6);
    }

    /** Les situations soumises à l'IA : toutes les réelles, et n situations de test, réparties entre les catégories. */
    static List<Cas> echantillon(int n) throws Exception {
        List<Cas> test = charger("scenarios.json").stream().filter(c -> c.jeu().equals("test")).toList();
        Map<String, List<Cas>> parCat = new LinkedHashMap<>();
        for (Cas c : test) parCat.computeIfAbsent(c.categorie(), k -> new ArrayList<>()).add(c);
        List<Cas> r = new ArrayList<>(charger("reels.json"));
        int voulu = r.size() + n;
        Random rnd = new Random(11);
        parCat.values().forEach(v -> Collections.shuffle(v, rnd));
        for (int k = 0; r.size() < voulu; k++) {
            boolean ajoute = false;
            for (List<Cas> v : parCat.values()) if (k < v.size() && r.size() < voulu) { r.add(v.get(k)); ajoute = true; }
            if (!ajoute) break;
        }
        return r;
    }

    static void ia(String modele, int n, String jeu) throws Exception {
        String cle = System.getenv("GROQ_CLE");
        if (cle == null || cle.isBlank()) { System.out.println("GROQ_CLE manquante"); return; }
        JsonObject cache = cache();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        int faits = 0, jetons = 0;
        // « modèle@effort#version » : la version de la consigne est mesurée à part (le cache garde chaque essai).
        String nom = modele.split("[@#]")[0], effort = modele.contains("@") ? modele.substring(modele.indexOf('@') + 1).split("#")[0] : "low";
        Set<String> deja = new java.util.HashSet<>();
        for (var e : cache.entrySet()) if (e.getValue().getAsJsonObject().get("modele").getAsString().equals(modele)) deja.add(e.getValue().getAsJsonObject().get("id").getAsString());
        List<Cas> choix = jeu.equals("aveugle") ? charger("aveugle.json") : echantillon(n);
        if (jeu.startsWith("ids:")) {
            Set<String> ids = Set.of(jeu.substring(4).split(","));
            choix = choix.stream().filter(c -> ids.contains(c.id())).toList();
        }
        for (Cas c : choix) {
            if (deja.contains(c.id())) continue;
            List<String> et = Adressage.etiquettes(c.s());
            String consigne = Adressage.consigne(c.s(), et);
            String k = cle(modele, c, consigne);
            String corps = ReponsesIA.requete(nom, consigne, "Donne la probabilité pour chaque ouvrier.", Adressage.schema(et), 0, effort.equals("low") ? 700 : 2000, effort);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions")).timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer " + cle).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corps)).build();
            HttpResponse<String> rep = null;
            for (int essai = 0; essai < 6; essai++) {
                rep = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (rep.statusCode() != 429) break;
                long attente = ReponsesIA.attente(rep.headers().firstValue("retry-after").orElse(""), rep.body());
                System.out.printf("  quota par minute atteint, pause de %d s%n", attente / 1000 + 1);
                if (attente > 600_000) { System.out.println("  quota du jour atteint : arrêt."); return; }
                Thread.sleep(attente + 1000);
            }
            if (rep.statusCode() / 100 != 2) { System.out.println("  " + c.id() + " : HTTP " + rep.statusCode() + " " + ReponsesIA.erreur(rep.body())); continue; }
            Adressage.Avis avis;
            try { avis = Adressage.lireAvis(ReponsesIA.contenu(rep.body()), et); }
            catch (RuntimeException e) { System.out.println("  " + c.id() + " : réponse illisible"); continue; }
            int j = ReponsesIA.jetons(rep.body());
            jetons += j;
            faits++;
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("modele", modele);
            JsonArray p = new JsonArray(), l = new JsonArray();
            for (int i = 0; i < et.size(); i++) { p.add(Double.isNaN(avis.p()[i]) ? -1 : avis.p()[i]); l.add(avis.liens()[i]); }
            o.add("p", p);
            o.add("liens", l);
            o.addProperty("jetons", j);
            cache.add(k, o);
            Files.writeString(CACHE, GSON.toJson(cache), StandardCharsets.UTF_8);
            StringBuilder b = new StringBuilder(String.format("  %-10s « %s » attendu %s :", c.id(), c.s().texte(), c.attendu()));
            for (int i = 0; i < et.size(); i++) b.append(String.format(" %s=%.0f%%", et.get(i), avis.p()[i] * 100));
            System.out.println(b.append("  (").append(j).append(" jetons)"));
            Thread.sleep(Math.max(2500, j * 60_000L / 6000)); // sous 6 000 jetons par minute
        }
        System.out.printf("%d appels, %d jetons%n", faits, jetons);
    }

    // --- La fusion -----------------------------------------------------------------------------------------------

    record Avec(Cas c, double[] pc, double[] pi) {}

    static List<Avec> avecIa(String modele, Adressage.Parametres pa) throws Exception {
        JsonObject cache = cache();
        Map<String, JsonObject> parId = new HashMap<>();
        for (var e : cache.entrySet()) if (e.getValue().getAsJsonObject().get("modele").getAsString().equals(modele)) parId.put(e.getValue().getAsJsonObject().get("id").getAsString(), e.getValue().getAsJsonObject());
        List<Avec> l = new ArrayList<>();
        for (Cas c : concat(concat(charger("reels.json"), charger("scenarios.json")), charger("aveugle.json"))) {
            JsonObject o = parId.get(c.id());
            if (o == null) continue;
            double[] pi = new double[c.s().pnj().size()];
            JsonArray a = o.getAsJsonArray("p");
            for (int i = 0; i < pi.length; i++) pi[i] = a.get(i).getAsDouble() < 0 ? Double.NaN : a.get(i).getAsDouble();
            l.add(new Avec(c, Adressage.code(c.s(), pa), pi));
        }
        return l;
    }

    static double[] ajusterFusion(List<Avec> l) {
        List<double[]> X = new ArrayList<>();
        List<Integer> y = new ArrayList<>();
        for (Avec a : l) {
            boolean remarque = Adressage.remarque(a.c().s());
            for (int i = 0; i < a.pc().length; i++) {
                double pi = Double.isNaN(a.pi()[i]) ? a.pc()[i] : a.pi()[i];
                X.add(new double[]{Adressage.logit(a.pc()[i]), remarque ? 0 : Adressage.logit(pi)});
                y.add(a.c().attendu().contains(a.c().s().pnj().get(i).prenom()) ? 1 : 0);
            }
        }
        return ajuster(X, y, 1);
    }

    static Adressage.Parametres avecFusion(Adressage.Parametres p, double[] f, double seuil) {
        return new Adressage.Parametres(p.poids(), p.biais(), Math.round(f[0] * 1e4) / 1e4, Math.round(f[1] * 1e4) / 1e4, Math.round(f[2] * 1e4) / 1e4,
                seuil, p.seuilCode(), p.bas(), p.haut());
    }

    static double[] fusionnees(Avec a, Adressage.Parametres p) {
        double[] r = new double[a.pc().length];
        boolean remarque = Adressage.remarque(a.c().s());
        for (int i = 0; i < r.length; i++) r[i] = Adressage.fusion(a.pc()[i], a.pi()[i], p, remarque);
        return r;
    }

    /** Comme en jeu : le code tranche seul quand il est sûr, sinon le croisement. */
    static double[] decision(Avec a, Adressage.Parametres p) {
        return Adressage.sur(a.c().s(), a.pc(), p) ? a.pc() : fusionnees(a, p);
    }

    static void fusion(String modele, boolean reelsSeulement, boolean ecrire) throws Exception {
        Adressage.Parametres pa = parametres();
        List<Avec> l = new ArrayList<>(avecIa(modele, pa));
        l.removeIf(a -> a.c().jeu().equals("aveugle") || reelsSeulement && !a.c().jeu().equals("reel"));
        if (l.isEmpty()) { System.out.println("aucun avis de " + modele + " en cache"); return; }
        Map<Cas, Avec> parCas = new LinkedHashMap<>();
        for (Avec a : l) parCas.put(a.c(), a);
        List<Cas> cas = new ArrayList<>(parCas.keySet());
        System.out.printf("%d situations avec l'avis de %s%n", cas.size(), modele);
        montrer("CODE SEUL", evaluer(cas, c -> parCas.get(c).pc(), pa.seuilCode()), 30);
        montrer("IA SEULE", evaluer(cas, c -> {
            double[] pi = parCas.get(c).pi().clone();
            for (int i = 0; i < pi.length; i++) if (Double.isNaN(pi[i])) pi[i] = 0;
            return pi;
        }, 0.5), 30);
        // Validation croisée en 5 plis : le croisement et son seuil appris sur 4/5, mesurés sur le 1/5 restant.
        List<Cas> melange = new ArrayList<>(cas);
        Collections.shuffle(melange, new Random(3));
        int exacts = 0, pourRien = 0, oublis = 0;
        List<String> erreurs = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            List<Avec> appr = new ArrayList<>();
            List<Cas> val = new ArrayList<>();
            for (int i = 0; i < melange.size(); i++) if (i % 5 == k) val.add(melange.get(i)); else appr.add(parCas.get(melange.get(i)));
            double[] f = ajusterFusion(appr);
            Adressage.Parametres p0 = avecFusion(pa, f, 0.5);
            List<Cas> ca = appr.stream().map(Avec::c).toList();
            double s = meilleurSeuil(ca, c -> decision(parCas.get(c), p0));
            Adressage.Parametres p1 = avecFusion(pa, f, s);
            Bilan b = evaluer(val, c -> decision(parCas.get(c), p1), s);
            exacts += b.exacts();
            pourRien += b.pourRien();
            oublis += b.oublis();
            erreurs.addAll(b.erreurs());
        }
        Bilan cv = new Bilan(cas.size(), exacts, pourRien, oublis, erreurs);
        montrer("CODE × IA, validation croisée (jamais mesuré sur ce qui a servi à apprendre)", cv, 40);
        double[] f = ajusterFusion(l);
        Adressage.Parametres p0 = avecFusion(pa, f, 0.5);
        double s = meilleurSeuil(cas, c -> decision(parCas.get(c), p0));
        Adressage.Parametres p = avecFusion(pa, f, s);
        System.out.printf("croisement : p = σ(%.2f·logit(code) + %.2f·logit(IA) + %.2f), seuil %.2f%n", f[0], f[1], f[2], s);
        List<Cas> reels = cas.stream().filter(c -> c.jeu().equals("reel")).toList(), test = cas.stream().filter(c -> !c.jeu().equals("reel")).toList();
        montrer("CODE × IA, situations réelles (appris sur tout : indicatif)", evaluer(reels, c -> decision(parCas.get(c), p), s), 20);
        montrer("CODE × IA, situations de test (appris sur tout : indicatif)", evaluer(test, c -> decision(parCas.get(c), p), s), 20);
        long surs = cas.stream().filter(c -> Adressage.sur(c.s(), parCas.get(c).pc(), p)).count();
        System.out.printf("le code tranche seul %d situations sur %d (l'IA n'est pas consultée)%n", surs, cas.size());
        incertains(cas, parCas, p);
        JsonObject m = new JsonObject();
        m.add("croise_" + modele.replace('/', '_') + "_validation_croisee", mesure(cv));
        if (!ecrire || reelsSeulement) { System.out.println("(comparaison seulement : rien n'est écrit)"); return; }
        sauver(p, m);
        System.out.println("→ " + PARAMS);
    }

    /**
     * La mesure finale, sur le jeu écrit à l'aveugle (jamais regardé pendant la mise au point) : le code seul, l'IA
     * seule, et le système complet (le code tranche quand il est sûr, sinon le croisement).
     */
    static void bilan(String modele) throws Exception {
        Adressage.Parametres p = parametres();
        List<Cas> aveugle = charger("aveugle.json");
        montrer("À L'AVEUGLE, code seul", evaluer(aveugle, c -> Adressage.code(c.s(), p), p.seuilCode()), 40);
        if (modele.isEmpty()) return;
        Map<Cas, Avec> parCas = new LinkedHashMap<>();
        for (Avec a : avecIa(modele, p)) if (a.c().jeu().equals("aveugle")) parCas.put(a.c(), a);
        List<Cas> cas = new ArrayList<>(parCas.keySet());
        System.out.printf("%d situations à l'aveugle avec l'avis de %s%n", cas.size(), modele);
        montrer("À L'AVEUGLE, IA seule", evaluer(cas, c -> {
            double[] pi = parCas.get(c).pi().clone();
            for (int i = 0; i < pi.length; i++) if (Double.isNaN(pi[i])) pi[i] = 0;
            return pi;
        }, 0.5), 40);
        montrer("À L'AVEUGLE, système complet (code sûr, sinon code × IA)", evaluer(cas, c -> decision(parCas.get(c), p), p.seuil()), 40);
        long surs = cas.stream().filter(c -> Adressage.sur(c.s(), parCas.get(c).pc(), p)).count();
        System.out.printf("le code tranche seul %d situations sur %d%n", surs, cas.size());
        incertains(cas, parCas, p);
    }

    /** Là où l'IA est vraiment consultée (le code n'est pas sûr) : le code seul, l'IA seule, et le croisement. */
    static void incertains(List<Cas> cas, Map<Cas, Avec> parCas, Adressage.Parametres p) {
        List<Cas> l = cas.stream().filter(c -> !Adressage.sur(c.s(), parCas.get(c).pc(), p)).toList();
        if (l.isEmpty()) return;
        montrer("  quand le code n'est pas sûr, code seul", evaluer(l, c -> parCas.get(c).pc(), p.seuilCode()), 0);
        montrer("  quand le code n'est pas sûr, IA seule", evaluer(l, c -> {
            double[] pi = parCas.get(c).pi().clone();
            for (int i = 0; i < pi.length; i++) if (Double.isNaN(pi[i])) pi[i] = 0;
            return pi;
        }, 0.5), 0);
        montrer("  quand le code n'est pas sûr, code × IA", evaluer(l, c -> fusionnees(parCas.get(c), p), p.seuil()), 0);
    }

    /** Pour chaque situation avec l'avis de ce modèle : la probabilité du code, celle de l'IA et ses liens, et si le code était sûr. */
    static void detail(String modele) throws Exception {
        Adressage.Parametres p = parametres();
        JsonObject cache = cache();
        Map<String, JsonObject> parId = new HashMap<>();
        for (var e : cache.entrySet()) if (e.getValue().getAsJsonObject().get("modele").getAsString().equals(modele)) parId.put(e.getValue().getAsJsonObject().get("id").getAsString(), e.getValue().getAsJsonObject());
        for (Avec a : avecIa(modele, p)) {
            Cas c = a.c();
            boolean sur = Adressage.sur(c.s(), a.pc(), p);
            System.out.printf("%s%s « %s » attendu %s%n", sur ? "  (sûr) " : "  ", c.id(), c.s().texte(), c.attendu());
            JsonArray liens = parId.get(c.id()).getAsJsonArray("liens");
            for (int i = 0; i < a.pc().length; i++)
                System.out.printf("        %-11s code %3.0f %%  IA %3.0f %%  | %s%n", c.s().pnj().get(i).prenom(), a.pc()[i] * 100,
                        Double.isNaN(a.pi()[i]) ? -1 : a.pi()[i] * 100, liens.get(i).getAsString().length() > 110 ? liens.get(i).getAsString().substring(0, 110) : liens.get(i).getAsString());
        }
    }
}

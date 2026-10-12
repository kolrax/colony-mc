package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Le journal des phrases du chef que ses ouvriers n'ont pas comprises, pour enrichir ensuite le répertoire
 * (cahier des charges 8.1 et 8.2). Pur Java (Gson et fichiers seulement).
 * <ul>
 * <li>{@link #RIEN} : personne n'a compris (ni l'interpréteur, ni l'IA, absente ou en échec) ;</li>
 * <li>{@link #IA_SEULE} : l'interpréteur n'a rien compris, l'IA y a vu une action : c'est la phrase à apprendre ;</li>
 * <li>{@link #IA_PAROLE} : l'interpréteur n'a rien compris, l'IA a seulement répondu par des mots.</li>
 * </ul>
 * Les doublons (même phrase normalisée, même motif) se regroupent avec un compteur. Aucun nom de joueur : la phrase,
 * le motif, les métiers visés et les dates. Au plus {@link #MAX} entrées (on retire d'abord les plus anciennes vues
 * une seule fois), chaque phrase coupée à {@link #LONGUEUR} caractères.
 */
final class Incompris {
    static final String RIEN = "rien", IA_SEULE = "ia_seule", IA_PAROLE = "ia_parole";
    static final int MAX = 500, LONGUEUR = 200;

    /** Le journal du serveur (relu au démarrage, sauvé au plus toutes les 30 s et à l'arrêt). */
    static final Incompris JOURNAL = new Incompris();

    static final class Entree {
        /** La phrase normalisée (sert à regrouper), la dernière phrase d'origine, le motif. */
        final String cle, motif;
        String phrase;
        final TreeSet<String> metiers = new TreeSet<>();
        int nombre;
        long premiere, derniere;

        Entree(String cle, String motif) { this.cle = cle; this.motif = motif; }
    }

    private final Map<String, Entree> entrees = new LinkedHashMap<>();
    private boolean change;

    /** La clé de regroupement : comme l'interpréteur ({@link Ordres#normaliser} : minuscules, sans accents ni ponctuation), sans « ? » final. */
    static String normaliser(String phrase) {
        String n = Ordres.normaliser(couper(phrase)).strip();
        return n.endsWith("?") ? n.substring(0, n.length() - 1).strip() : n;
    }

    /** Les noms donnés (joueurs, prénoms des PNJ) remplacés par « @ », mots entiers, sans tenir compte de la casse. */
    static String anonymiser(String phrase, Collection<String> noms) {
        String r = phrase;
        for (String nom : noms.stream().filter(x -> x != null && !x.isBlank()).sorted(Comparator.comparingInt(String::length).reversed()).toList())
            r = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(nom.strip()) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(r).replaceAll("@");
        return r;
    }

    /** Coupée à {@link #LONGUEUR} caractères (sans couper un caractère en deux), sans espaces autour. */
    static String couper(String phrase) {
        String p = phrase == null ? "" : phrase.strip();
        if (p.length() <= LONGUEUR) return p;
        int n = Character.isHighSurrogate(p.charAt(LONGUEUR - 1)) ? LONGUEUR - 1 : LONGUEUR;
        return p.substring(0, n).strip();
    }

    /** Note une phrase non comprise, dite à des ouvriers de ces métiers, à l'instant {@code quand} (ms). */
    synchronized void noter(String phrase, String motif, Collection<String> metiers, long quand) {
        String cle = normaliser(phrase);
        if (cle.isEmpty()) return;
        Entree e = entrees.get(cle + "|" + motif);
        if (e == null) {
            if (entrees.size() >= MAX) retirerUne();
            e = new Entree(cle, motif);
            e.premiere = quand;
            entrees.put(cle + "|" + motif, e);
        }
        e.phrase = couper(phrase);
        e.metiers.addAll(metiers);
        e.nombre++;
        e.derniere = Math.max(e.derniere, quand);
        change = true;
    }

    /** La place manque : la plus ancienne des phrases vues une seule fois, sinon la plus ancienne de toutes. */
    private void retirerUne() {
        Comparator<Entree> ordre = Comparator.<Entree>comparingInt(x -> x.nombre == 1 ? 0 : 1).thenComparingLong(x -> x.derniere);
        entrees.values().stream().min(ordre).ifPresent(x -> entrees.remove(x.cle + "|" + x.motif));
    }

    synchronized int taille() { return entrees.size(); }

    synchronized void vider() {
        if (!entrees.isEmpty()) change = true;
        entrees.clear();
    }

    /** Les {@code n} plus fréquentes (à égalité, la plus récente d'abord), en copies. */
    synchronized List<Map<String, Object>> plusFrequentes(int n) {
        List<Map<String, Object>> l = new ArrayList<>();
        entrees.values().stream()
                .sorted(Comparator.<Entree>comparingInt(e -> -e.nombre).thenComparingLong(e -> -e.derniere))
                .limit(n).forEach(e -> l.add(enMap(e)));
        return l;
    }

    static Map<String, Object> enMap(Entree e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phrase_normalisee", e.cle);
        m.put("phrase", e.phrase);
        m.put("motif", e.motif);
        m.put("metiers", new ArrayList<>(e.metiers));
        m.put("nombre", e.nombre);
        m.put("premiere", Instant.ofEpochMilli(e.premiere).toString());
        m.put("derniere", Instant.ofEpochMilli(e.derniere).toString());
        return m;
    }

    // --- Fichier (config/colonie_pnj/incompris.json) ---------------------------------------------------------

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static String json(List<Map<String, Object>> entrees) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("entrees", entrees);
        return GSON.toJson(m);
    }

    /** Remplace le contenu par celui du texte (entrées illisibles ignorées, bornes réappliquées). */
    synchronized void lire(String json) {
        entrees.clear();
        JsonElement racine = JsonParser.parseString(json);
        JsonArray l = racine.isJsonObject() && racine.getAsJsonObject().has("entrees") ? racine.getAsJsonObject().getAsJsonArray("entrees") : new JsonArray();
        for (JsonElement x : l) {
            try {
                JsonObject o = x.getAsJsonObject();
                String phrase = couper(o.get("phrase").getAsString()), cle = normaliser(phrase), motif = o.get("motif").getAsString();
                if (cle.isEmpty() || entrees.containsKey(cle + "|" + motif)) continue;
                if (entrees.size() >= MAX) retirerUne();
                Entree e = new Entree(cle, motif);
                e.phrase = phrase;
                if (o.has("metiers")) o.getAsJsonArray("metiers").forEach(m -> e.metiers.add(m.getAsString()));
                e.nombre = Math.max(1, o.get("nombre").getAsInt());
                e.premiere = Instant.parse(o.get("premiere").getAsString()).toEpochMilli();
                e.derniere = Instant.parse(o.get("derniere").getAsString()).toEpochMilli();
                entrees.put(cle + "|" + motif, e);
            } catch (RuntimeException ignoree) {
                // une entrée abîmée ne perd pas les autres
            }
        }
        change = false;
    }

    /** Une copie des entrées à écrire si elles ont changé (null sinon) ; le changement est alors considéré comme sauvé. */
    synchronized List<Map<String, Object>> aSauver() {
        if (!change) return null;
        change = false;
        return plusFrequentes(MAX);
    }

    /** L'écriture a raté : à refaire la prochaine fois. */
    synchronized void aRefaire() { change = true; }

    /** Les écritures, dans l'ordre, hors du fil du serveur. */
    private static final ExecutorService ECRITURE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colonie-incompris");
        t.setDaemon(true);
        return t;
    });

    /** Relit le fichier s'il existe (au démarrage). Illisible : mis de côté en « .abime », et le journal repart de zéro. */
    void charger(Path fichier) throws IOException {
        if (!Files.exists(fichier)) { lire("{}"); return; }
        try { lire(Files.readString(fichier, StandardCharsets.UTF_8)); }
        catch (IOException | RuntimeException e) {
            lire("{}");
            Files.move(fichier, fichier.resolveSibling(fichier.getFileName() + ".abime"), StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("illisible, mis de côté en .abime", e);
        }
    }

    /**
     * Écrit le fichier s'il a changé : en arrière-plan, ou en attendant la fin ({@code attendre}, à l'arrêt).
     * {@code erreur} reçoit l'échec éventuel (pour le journal du serveur).
     */
    void sauver(Path fichier, boolean attendre, Consumer<Exception> erreur) {
        var copie = aSauver();
        if (copie == null && !attendre) return;
        var f = ECRITURE.submit(() -> {
            try { if (copie != null) ecrire(fichier, json(copie)); }
            catch (IOException | RuntimeException e) { aRefaire(); erreur.accept(e); }
        });
        if (attendre) {
            try { f.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { erreur.accept(e); }
        }
    }

    static void ecrire(Path fichier, String texte) throws IOException {
        Files.createDirectories(fichier.getParent());
        Path tmp = fichier.resolveSibling(fichier.getFileName() + ".tmp");
        Files.writeString(tmp, texte, StandardCharsets.UTF_8);
        try { Files.move(tmp, fichier, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, fichier, StandardCopyOption.REPLACE_EXISTING); }
    }
}

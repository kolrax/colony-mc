package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La voix des PNJ, et les écouter au micro, par Simple Voice Chat (si le mod est installé ; tout ce qui le touche
 * est dans {@link VoixSvc}, chargé seulement s'il est là).
 * <ul>
 * <li>Parler : la phrase est dite par Gemini avec le timbre du PNJ (sa voix, sa hauteur, son âge, son débit : à
 *   lui seul, et qui ne change pas), puis jouée depuis sa position. Plusieurs répliques : chacun son tour.</li>
 * <li>Écouter : un joueur parle près de ses ouvriers (en regardant l'un d'eux, ou seul avec eux) ; sa phrase est
 *   enregistrée jusqu'à ce qu'il se taise, mise en texte par Whisper (Groq), puis {@link Dialogue} trouve à qui
 *   il parlait.</li>
 * </ul>
 */
final class Voix {
    private Voix() {}

    /** Simple Voice Chat est là et son serveur vocal a démarré (c'est {@link VoixSvc} qui le dit). */
    static volatile boolean pret;

    // --- Parler -----------------------------------------------------------------------------------------

    /** Une réplique : qui la dit, quoi, sur quel ton. */
    record Replique(Travailleur t, String texte, String humeur) {}

    /** Au plus trois voix par phrase du chef (le quota de Gemini est serré) : les autres répondent en bulle. */
    static final int VOIX_PAR_TOUR = 3;

    static void dire(Travailleur t, String texte, String humeur) { direEnSuite(List.of(new Replique(t, texte, humeur))); }

    /**
     * Les répliques, dites chacune à son tour : en bulle, et à voix haute si c'est possible (quelqu'un pour
     * l'entendre, Gemini disponible). Les voix sont demandées ensemble, puis jouées l'une après l'autre ; la bulle
     * s'affiche quand la voix commence.
     */
    static void direEnSuite(List<Replique> l) {
        if (l.isEmpty() || l.get(0).t().level().getServer() == null) return;
        MinecraftServer s = l.get(0).t().level().getServer();
        List<CompletableFuture<short[]>> sons = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) sons.add(i < VOIX_PAR_TOUR ? son(l.get(i)) : CompletableFuture.completedFuture(null));
        CompletableFuture.allOf(sons.toArray(CompletableFuture[]::new)).thenRun(() -> s.execute(() -> {
            long quand = System.currentTimeMillis();
            for (int i = 0; i < l.size(); i++) {
                short[] pcm = sons.get(i).join();
                PREVUES.add(new Prevue(quand, l.get(i), pcm));
                quand += pcm != null ? pcm.length * 1000L / Audio.FREQ_VOIX + 250 : 700 + l.get(i).texte().length() * 25L;
            }
        }));
    }

    /** Sa voix pour cette réplique (null : en bulle seulement). Jamais en erreur. */
    static CompletableFuture<short[]> son(Replique r) {
        Travailleur t = r.t();
        if (!pret || !IA.voixDisponible() || t.level().getNearestPlayer(t, 24) == null) return CompletableFuture.completedFuture(null);
        Timbre tb = timbre(t);
        return IA.voix(r.texte(), tb.voix(), style(t, tb, r.humeur()), tb.hauteur()).exceptionally(e -> null);
    }

    /** Une réplique prête, à dire à ce moment-là. */
    record Prevue(long quand, Replique r, short[] pcm) {}

    /** Sur le fil du serveur seulement. */
    static final List<Prevue> PREVUES = new ArrayList<>();

    static void direPrevues() {
        long maintenant = System.currentTimeMillis();
        for (var it = PREVUES.iterator(); it.hasNext(); ) {
            Prevue p = it.next();
            if (p.quand() > maintenant) continue;
            it.remove();
            Travailleur t = p.r().t();
            if (!t.isAlive()) continue;
            t.dire(p.r().texte());
            if (p.pcm() != null && p.pcm().length > 0 && pret) VoixSvc.jouer(t, p.pcm());
        }
    }

    // --- Le timbre de chacun ------------------------------------------------------------------------------

    /**
     * Le timbre d'un PNJ : une des 30 voix de Gemini (une différente pour chacun tant qu'il en reste), une hauteur
     * (plus grave ou plus aiguë, différente de celle des autres qui ont la même voix), un âge et un débit.
     * Choisi la première fois qu'il parle, puis gardé dans sa mémoire.
     */
    record Timbre(String voix, float hauteur, String allure, boolean femme) {}

    static final List<String> VOIX_FEMMES = List.of("Kore", "Vindemiatrix", "Sulafat", "Achernar", "Leda", "Laomedeia", "Autonoe", "Despina",
            "Callirrhoe", "Pulcherrima", "Gacrux", "Erinome", "Zephyr", "Aoede");
    static final List<String> VOIX_HOMMES = List.of("Iapetus", "Schedar", "Charon", "Achird", "Enceladus", "Umbriel", "Puck", "Algieba",
            "Zubenelgenubi", "Sadachbia", "Algenib", "Fenrir", "Orus", "Alnilam", "Rasalgethi", "Sadaltager");
    /** Les voix qui vont le mieux à chaque tempérament (prises en premier quand elles sont libres). */
    static final Map<String, List<String>> PREFEREES = Map.of(
            "calme", List.of("Kore", "Vindemiatrix", "Sulafat", "Achernar", "Iapetus", "Schedar", "Charon", "Achird", "Rasalgethi"),
            "peureux", List.of("Leda", "Laomedeia", "Autonoe", "Aoede", "Enceladus", "Umbriel", "Puck"),
            "fourbe", List.of("Despina", "Callirrhoe", "Pulcherrima", "Algieba", "Zubenelgenubi", "Sadachbia", "Sadaltager"),
            "colerique", List.of("Gacrux", "Erinome", "Zephyr", "Algenib", "Fenrir", "Orus", "Alnilam"));
    /** Les hauteurs, de la plus neutre à la plus marquée (au-delà de 10 %, la voix ne sonne plus naturelle). */
    static final float[] HAUTEURS = {1f, 0.95f, 1.05f, 0.92f, 1.08f, 0.97f, 1.03f, 0.9f, 1.1f, 0.935f, 1.065f};
    static final String[] AGES_HOMMES = {"a young man in his twenties", "a man in his thirties", "a seasoned man in his forties",
            "a weathered man in his fifties, slightly hoarse", "an old man with a gravelly voice"};
    static final String[] AGES_FEMMES = {"a young woman in her twenties", "a woman in her thirties", "a seasoned woman in her forties",
            "a weathered woman in her fifties, slightly husky", "an old woman with a thin, cracked voice"};
    static final String[] DEBITS = {"speaking briskly", "speaking at an unhurried pace", "speaking slowly and deliberately"};
    static final Set<String> FEMININS = Set.of("Aliénor", "Isabeau", "Mahaut", "Ysolde", "Blanche", "Héloïse", "Clémence", "Agnès", "Jehanne",
            "Ermengarde", "Béatrix", "Pétronille", "Sibylle", "Mélisende", "Adélaïde", "Guenièvre", "Oriane", "Bérengère", "Philippa");

    static boolean femme(Travailleur t) {
        // Les prénoms tirés au sort sont connus ; un prénom donné au panneau : on devine (« -e », « -a » : une femme).
        return java.util.Arrays.asList(Travailleur.PRENOMS).contains(t.prenom) ? FEMININS.contains(t.prenom)
                : t.prenom.endsWith("e") || t.prenom.endsWith("a");
    }

    static Timbre timbre(Travailleur t) {
        CompoundTag m = t.memoire;
        if (m.contains("voix_hauteur")) {
            String v = m.getString("voix");
            return new Timbre(v, m.getFloat("voix_hauteur"), m.getString("voix_allure"), VOIX_FEMMES.contains(v));
        }
        // Les timbres des autres ouvriers (ceux qui sont chargés) : on évite les doublons.
        // ponytail: seuls les PNJ chargés sont vus ; un registre dans Courrier si les doublons gênent un jour.
        Map<String, List<Float>> pris = new HashMap<>();
        if (t.level().getServer() != null)
            for (ServerLevel l : t.level().getServer().getAllLevels())
                for (Entity e : l.getAllEntities())
                    if (e instanceof Travailleur x && x != t && x.memoire.contains("voix_hauteur"))
                        pris.computeIfAbsent(x.memoire.getString("voix"), k -> new ArrayList<>()).add(x.memoire.getFloat("voix_hauteur"));
        boolean femme = femme(t);
        int graine = t.getUUID().hashCode();
        String voix = choisirVoix(femme, t.temperament, m.getString("voix"), pris, graine);
        float hauteur = HAUTEURS[Math.floorMod(graine, HAUTEURS.length)];
        List<Float> memes = pris.getOrDefault(voix, List.of());
        for (float h : HAUTEURS) if (memes.stream().noneMatch(x -> Math.abs(x - h) < 0.005f)) { hauteur = h; break; }
        String[] ages = femme ? AGES_FEMMES : AGES_HOMMES;
        String allure = ages[Math.floorMod(graine >>> 3, ages.length)] + ", " + DEBITS[Math.floorMod(graine >>> 7, DEBITS.length)];
        m.putString("voix", voix);
        m.putFloat("voix_hauteur", hauteur);
        m.putString("voix_allure", allure);
        return new Timbre(voix, hauteur, allure, femme);
    }

    /**
     * La voix la moins prise parmi celles de son genre, celles de son tempérament d'abord. Celle qu'il avait déjà
     * (0.6.0) est gardée si personne d'autre ne l'a.
     */
    static String choisirVoix(boolean femme, String temperament, String avant, Map<String, List<Float>> pris, int graine) {
        List<String> toutes = femme ? VOIX_FEMMES : VOIX_HOMMES;
        if (toutes.contains(avant) && !pris.containsKey(avant)) return avant;
        List<String> pref = new ArrayList<>(PREFEREES.getOrDefault(temperament, List.of()).stream().filter(toutes::contains).toList());
        List<String> autres = new ArrayList<>(toutes.stream().filter(v -> !pref.contains(v)).toList());
        Collections.rotate(pref, Math.floorMod(graine, Math.max(1, pref.size())));
        Collections.rotate(autres, Math.floorMod(graine, Math.max(1, autres.size())));
        pref.addAll(autres);
        String meilleure = pref.get(0);
        int min = Integer.MAX_VALUE;
        for (String v : pref) {
            int n = pris.getOrDefault(v, List.of()).size();
            if (n < min) { min = n; meilleure = v; }
        }
        return meilleure;
    }

    static final Map<String, String> TONS = Map.of("calme", "calm, steady and warm", "peureux", "nervous, hesitant, a little shaky",
            "fourbe", "sly, smooth and flattering", "colerique", "gruff, curt and irritated");
    static final Map<String, String> HUMEURS = Map.of("joyeux", "cheerful", "inquiet", "worried", "agace", "annoyed", "triste", "sad",
            "fatigue", "tired, out of breath", "fier", "proud");

    /** Le ton (en anglais, comme le comprend le mieux Gemini) : son âge et son débit, son tempérament, et l'humeur de sa réponse. */
    static String style(Travailleur t, Timbre tb, String humeur) {
        String h = HUMEURS.get(humeur);
        return tb.allure() + ", a medieval " + (tb.femme() ? "craftswoman" : "craftsman") + " speaking French, "
                + TONS.getOrDefault(t.temperament, TONS.get("calme")) + (h == null ? "" : ", " + h);
    }

    // --- Écouter ----------------------------------------------------------------------------------------

    /** Ce qu'un joueur est en train de dire (des trames Opus de 20 ms) ; {@code cible} : l'ouvrier qu'il regarde, -1 si aucun. */
    static final class Enonce {
        final int cible;
        final long debut = System.currentTimeMillis();
        volatile long dernier = debut;
        final List<byte[]> trames = Collections.synchronizedList(new ArrayList<>());

        Enonce(int cible) { this.cible = cible; }
    }

    static final Map<UUID, Enonce> EN_COURS = new ConcurrentHashMap<>();
    /**
     * Les joueurs qu'on écoute, avec l'ouvrier qu'ils regardent (-1 : aucun, mais ils sont seuls avec leurs ouvriers).
     * Mis à jour sur le fil du serveur, lu par le fil de la voix.
     */
    static volatile Map<UUID, Integer> cibles = Map.of();
    /** Au-delà, un autre joueur n'entend plus (Simple Voice Chat porte à 48 blocs) : on peut se croire seul avec ses ouvriers. */
    static final double SEUL = 32;

    /** Une trame de micro (fil de Simple Voice Chat) : on ne garde que ce qui peut s'adresser à ses ouvriers. */
    static void paquet(UUID joueur, byte[] opus) {
        if (opus == null || opus.length == 0 || !ReglagesIA.actuel.ecoutePossible()) return;
        Enonce e = EN_COURS.get(joueur);
        if (e == null) {
            Integer cible = cibles.get(joueur);
            if (cible == null) return;
            e = new Enonce(cible);
            EN_COURS.put(joueur, e);
        }
        if (e.trames.size() < 50 * 15) e.trames.add(opus); // 15 secondes au plus
        e.dernier = System.currentTimeMillis();
    }

    /** À chaque tick : les répliques à dire, qui regarde qui, et les phrases finies (le joueur s'est tu depuis 0,7 s). */
    static void tick(MinecraftServer s) {
        direPrevues();
        if (!pret) return;
        if (s.getTickCount() % 5 == 0) viser(s);
        long maintenant = System.currentTimeMillis();
        for (var it = EN_COURS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Enonce e = en.getValue();
            if (maintenant - e.dernier < 700 && maintenant - e.debut < 15_000) continue;
            it.remove();
            try { traiter(s, en.getKey(), e); }
            catch (RuntimeException ex) { ColoniePnj.LOG.warn("Écoute : phrase illisible", ex); }
        }
    }

    /**
     * Pour chaque joueur : le plus proche de ses ouvriers qu'il regarde, à la distance d'écoute. S'il n'en regarde
     * aucun mais qu'il est seul avec eux (aucun autre joueur à portée de voix), on l'écoute quand même : il peut les
     * appeler par leur prénom ou dire « les gars ».
     */
    static void viser(MinecraftServer s) {
        double d = ReglagesIA.actuel.distance_ecoute;
        Map<UUID, Integer> n = new HashMap<>();
        if (ReglagesIA.actuel.ecoutePossible())
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                Vec3 oeil = p.getEyePosition(), regard = p.getLookAngle();
                Travailleur meilleur = null;
                double meilleurCos = 0;
                List<Travailleur> autour = p.serverLevel().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(d),
                        x -> p.getUUID().equals(x.chef) && !x.aTerre && x.isAlive());
                for (Travailleur t : autour) {
                    Vec3 v = t.getEyePosition().subtract(oeil);
                    double dist = v.length();
                    if (dist > d || dist < 0.01) continue;
                    double c = v.scale(1 / dist).dot(regard);
                    if (c > (dist < 2.5 ? 0.7 : 0.9) && c > meilleurCos) { meilleurCos = c; meilleur = t; }
                }
                if (meilleur != null) n.put(p.getUUID(), meilleur.getId());
                else if (!autour.isEmpty() && p.serverLevel().players().stream().noneMatch(x -> x != p && !x.isSpectator() && x.distanceTo(p) < SEUL))
                    n.put(p.getUUID(), -1);
            }
        cibles = n;
    }

    /** Une phrase finie : on la décode, puis on l'écoute. */
    static void traiter(MinecraftServer s, UUID joueur, Enonce e) {
        ServerPlayer p = s.getPlayerList().getPlayer(joueur);
        if (p == null) return;
        Travailleur vise = e.cible >= 0 && p.level().getEntity(e.cible) instanceof Travailleur t && joueur.equals(t.chef) ? t : null;
        if (e.cible >= 0 && vise == null) return;
        List<byte[]> trames;
        synchronized (e.trames) { trames = new ArrayList<>(e.trames); }
        entendre(s, p, vise, VoixSvc.decoder(trames));
    }

    /**
     * Du son (48 kHz) dit près de ses ouvriers ({@code vise} : celui qu'il regardait, ou null) : on écarte les
     * bruits, Whisper le met en texte, puis c'est comme s'il l'avait dit dans la fenêtre de conversation.
     */
    static void entendre(MinecraftServer s, ServerPlayer p, Travailleur vise, short[] pcm) {
        if (pcm.length < Audio.FREQ_VOIX * 0.35 || Audio.rms(pcm) < 250) return; // un bruit, pas une phrase
        List<Travailleur> autour = Dialogue.autour(p, vise);
        if (autour.isEmpty()) return;
        if (!IA.ecouteDisponible()) {
            if (vise != null) Travailleur.signaler(p, vise.prenom + " ne peut pas t'écouter pour l'instant (quota du jour ou service en pause) : écris-lui (clic droit).");
            return;
        }
        byte[] wav = Audio.ecrire(Audio.reechantillonner(pcm, Audio.FREQ_VOIX, 16_000), 16_000);
        Travailleur.signaler(p, vise != null ? "🎙 " + vise.prenom + " t'écoute…" : "🎙 …");
        IA.transcrire(wav, indice(autour)).whenComplete((texte, err) -> s.execute(() -> {
            if (err != null) {
                if (vise != null) Travailleur.signaler(p, vise.prenom + " n'a pas pu t'entendre (service indisponible) : écris-lui (clic droit).");
                return;
            }
            if (texte == null || texte.isBlank() || Audio.inventee(texte) || p.hasDisconnected()) return;
            Travailleur.signaler(p, "Tu dis : « " + texte + " »");
            Dialogue.parler(p, texte, vise != null && vise.isAlive() ? vise : null, true);
        }));
    }

    /** Des mots du jeu, et les prénoms de ceux qui l'entendent, pour aider Whisper à bien les écrire. */
    static String indice(List<Travailleur> autour) {
        return "Minecraft. " + String.join(", ", autour.stream().map(t -> t.prenom).distinct().toList())
                + ", les gars, chef, mineur, bûcheron, guerrier, poste, dépôt, base, fer, charbon, cuivre, or, diamant, lapis, redstone, "
                + "pierre, bouleau, chêne, sapin, jungle, acacia, cerisier, palétuvier, ronde, escorte, expédition.";
    }
}

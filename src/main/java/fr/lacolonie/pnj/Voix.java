package fr.lacolonie.pnj;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La voix des PNJ, et les écouter au micro, par Simple Voice Chat (si le mod est installé ; tout ce qui le touche
 * est dans {@link VoixSvc}, chargé seulement s'il est là).
 * <ul>
 * <li>Parler : la phrase est dite par Gemini, avec une voix propre à chaque PNJ (homme ou femme, selon son
 *   tempérament) et le ton de sa réponse, puis jouée depuis sa position : on l'entend comme un joueur proche.</li>
 * <li>Écouter : quand un joueur regarde un de ses ouvriers (à quelques blocs) et parle, sa phrase est enregistrée
 *   jusqu'à ce qu'il se taise, mise en texte par Whisper (Groq), puis traitée comme s'il l'avait écrite.</li>
 * </ul>
 */
final class Voix {
    private Voix() {}

    /** Simple Voice Chat est là et son serveur vocal a démarré (c'est {@link VoixSvc} qui le dit). */
    static volatile boolean pret;

    // --- Parler -----------------------------------------------------------------------------------------

    /**
     * Le PNJ dit sa phrase : en bulle, et à voix haute si c'est possible (quelqu'un pour l'entendre, Gemini
     * disponible). La bulle s'affiche quand la voix commence, pour qu'elles aillent ensemble.
     */
    static void dire(Travailleur t, String texte, String humeur) {
        if (!pret || !IA.voixDisponible() || t.level().getNearestPlayer(t, 24) == null) { t.dire(texte); return; }
        MinecraftServer s = t.level().getServer();
        IA.voix(texte, voixDe(t), style(t, humeur)).whenComplete((pcm, e) -> s.execute(() -> {
            if (!t.isAlive()) return;
            t.dire(texte);
            if (pcm != null && pcm.length > 0 && pret) VoixSvc.jouer(t, pcm);
        }));
    }

    static final Set<String> FEMININS = Set.of("Aliénor", "Isabeau", "Mahaut", "Ysolde", "Blanche", "Héloïse", "Clémence", "Agnès", "Jehanne",
            "Ermengarde", "Béatrix", "Pétronille", "Sibylle", "Mélisende", "Adélaïde", "Guenièvre", "Oriane", "Bérengère", "Philippa");
    /** Les voix de Gemini, par timbre : pour chaque tempérament, celles qui lui vont le mieux. */
    static final Map<String, String[]> VOIX_FEMMES = Map.of(
            "calme", new String[]{"Kore", "Vindemiatrix", "Sulafat", "Achernar"},
            "peureux", new String[]{"Leda", "Laomedeia", "Autonoe"},
            "fourbe", new String[]{"Despina", "Callirrhoe", "Pulcherrima"},
            "colerique", new String[]{"Gacrux", "Erinome", "Zephyr"});
    static final Map<String, String[]> VOIX_HOMMES = Map.of(
            "calme", new String[]{"Iapetus", "Schedar", "Charon", "Achird"},
            "peureux", new String[]{"Enceladus", "Umbriel", "Puck"},
            "fourbe", new String[]{"Algieba", "Zubenelgenubi", "Sadachbia"},
            "colerique", new String[]{"Algenib", "Fenrir", "Orus", "Alnilam"});

    /** Sa voix : choisie une fois (selon son prénom et son tempérament), puis gardée. */
    static String voixDe(Travailleur t) {
        String v = t.memoire.getString("voix");
        if (!v.isEmpty()) return v;
        // Les prénoms tirés au sort sont connus ; un prénom donné au panneau : on devine (« -e », « -a » : une femme).
        boolean connu = java.util.Arrays.asList(Travailleur.PRENOMS).contains(t.prenom);
        boolean femme = connu ? FEMININS.contains(t.prenom) : t.prenom.endsWith("e") || t.prenom.endsWith("a");
        String[] choix = (femme ? VOIX_FEMMES : VOIX_HOMMES).getOrDefault(t.temperament, femme ? VOIX_FEMMES.get("calme") : VOIX_HOMMES.get("calme"));
        v = choix[Math.floorMod(t.getUUID().hashCode(), choix.length)];
        t.memoire.putString("voix", v);
        return v;
    }

    static final Map<String, String> TONS = Map.of("calme", "calm, steady and warm", "peureux", "nervous, hesitant, a little shaky",
            "fourbe", "sly, smooth and flattering", "colerique", "gruff, curt and irritated");
    static final Map<String, String> HUMEURS = Map.of("joyeux", "cheerful", "inquiet", "worried", "agace", "annoyed", "triste", "sad",
            "fatigue", "tired, out of breath", "fier", "proud");

    /** Le ton (en anglais, comme le comprend le mieux Gemini) : son tempérament, et l'humeur de sa réponse. */
    static String style(Travailleur t, String humeur) {
        String h = HUMEURS.get(humeur);
        return "a medieval craftsman speaking French, " + TONS.getOrDefault(t.temperament, TONS.get("calme")) + (h == null ? "" : ", " + h);
    }

    // --- Écouter ----------------------------------------------------------------------------------------

    /** Ce qu'un joueur est en train de dire à un de ses ouvriers (des trames Opus de 20 ms). */
    static final class Enonce {
        final int cible;
        final long debut = System.currentTimeMillis();
        volatile long dernier = debut;
        final List<byte[]> trames = Collections.synchronizedList(new ArrayList<>());

        Enonce(int cible) { this.cible = cible; }
    }

    static final Map<UUID, Enonce> EN_COURS = new ConcurrentHashMap<>();
    /** Pour chaque joueur, l'ouvrier qu'il regarde de près (mis à jour sur le fil du serveur, lu par le fil de la voix). */
    static volatile Map<UUID, Integer> cibles = Map.of();

    /** Une trame de micro (fil de Simple Voice Chat) : on ne garde que ce qui s'adresse à un de ses ouvriers. */
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

    /** À chaque tick : qui regarde qui, et les phrases finies (le joueur s'est tu depuis 0,7 s). */
    static void tick(MinecraftServer s) {
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

    /** Pour chaque joueur, le plus proche de ses ouvriers qu'il regarde, à la distance d'écoute. */
    static void viser(MinecraftServer s) {
        double d = ReglagesIA.actuel.distance_ecoute;
        Map<UUID, Integer> n = new HashMap<>();
        if (ReglagesIA.actuel.ecoutePossible())
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                Vec3 oeil = p.getEyePosition(), regard = p.getLookAngle();
                Travailleur meilleur = null;
                double meilleurCos = 0;
                for (Travailleur t : p.serverLevel().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(d),
                        x -> p.getUUID().equals(x.chef) && !x.aTerre && x.isAlive())) {
                    Vec3 v = t.getEyePosition().subtract(oeil);
                    double dist = v.length();
                    if (dist > d || dist < 0.01) continue;
                    double c = v.scale(1 / dist).dot(regard);
                    if (c > (dist < 2.5 ? 0.7 : 0.9) && c > meilleurCos) { meilleurCos = c; meilleur = t; }
                }
                if (meilleur != null) n.put(p.getUUID(), meilleur.getId());
            }
        cibles = n;
    }

    /** Une phrase finie : on la décode, on écarte les bruits, Whisper la met en texte, et l'ouvrier répond. */
    static void traiter(MinecraftServer s, UUID joueur, Enonce e) {
        ServerPlayer p = s.getPlayerList().getPlayer(joueur);
        if (p == null || !(p.level().getEntity(e.cible) instanceof Travailleur t) || !joueur.equals(t.chef)) return;
        List<byte[]> trames;
        synchronized (e.trames) { trames = new ArrayList<>(e.trames); }
        short[] pcm = VoixSvc.decoder(trames);
        entendre(s, p, t, pcm);
    }

    /** Du son (48 kHz) adressé à cet ouvrier : en texte, puis comme s'il avait été écrit. */
    static void entendre(MinecraftServer s, ServerPlayer p, Travailleur t, short[] pcm) {
        if (pcm.length < Audio.FREQ_VOIX * 0.35 || Audio.rms(pcm) < 250) return; // un bruit, pas une phrase
        if (!IA.ecouteDisponible()) {
            Travailleur.signaler(p, t.prenom + " ne peut pas t'écouter pour l'instant (quota du jour ou service en pause) : écris-lui (clic droit).");
            return;
        }
        byte[] wav = Audio.ecrire(Audio.reechantillonner(pcm, Audio.FREQ_VOIX, 16_000), 16_000);
        Travailleur.signaler(p, "🎙 " + t.prenom + " t'écoute…");
        IA.transcrire(wav, indice(t)).whenComplete((texte, err) -> s.execute(() -> {
            if (err != null) { Travailleur.signaler(p, t.prenom + " n'a pas pu t'entendre (service indisponible) : écris-lui (clic droit)."); return; }
            if (texte == null || texte.isBlank() || Audio.inventee(texte) || !t.isAlive() || p.hasDisconnected()) return;
            Travailleur.signaler(p, "Tu dis à " + t.prenom + " : « " + texte + " »");
            t.parler(p, texte);
        }));
    }

    /** Des mots du jeu, pour aider Whisper à bien les écrire. */
    static String indice(Travailleur t) {
        return "Minecraft. " + t.prenom + ", chef, mineur, bûcheron, guerrier, poste, dépôt, fer, charbon, cuivre, or, diamant, lapis, redstone, "
                + "pierre, bouleau, chêne, sapin, jungle, acacia, cerisier, palétuvier, ronde, escorte, expédition.";
    }
}

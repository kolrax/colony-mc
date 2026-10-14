package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Les remarques spontanées (lot 7, cahier 7.3) : de loin en loin, un ouvrier dit seul, en bulle, une remarque de
 * remarques.txt qui va avec le moment, le ciel, son travail ou sa colonie ({@link Vie#remarque}). Au plus une toutes les
 * {@code Reglages.secondes_entre_remarques} à deux fois ce temps par ouvrier, jamais deux ouvriers à moins de 16 blocs dans la
 * même minute, jamais en danger, à terre, endormi ni en pleine conversation. Rien n'est cherché dans le monde : le cerveau
 * l'appelle toutes les 5 s ({@link Cerveau#decider}), avec ce que l'ouvrier sait déjà.
 */
final class Remarques {
    private Remarques() {}

    /** Sur le serveur de tests, seulement dans le lot « remarques » (ailleurs, une bulle de plus gênerait les autres tests). */
    static boolean enEssai;
    /** Deux ouvriers à moins de tant de blocs ne font pas de remarque dans la même minute (en ticks). */
    static final int PROCHES = 16, MINUTE = 1200;
    /** Les remarques de la dernière minute : le monde, le lieu, le moment (temps du jeu). */
    static final Deque<Object[]> DITES = new ArrayDeque<>();
    /** Par chef : son dernier embauché (prénom, moment), dont les autres parlent dans la journée. */
    static final Map<UUID, Object[]> NOUVEAUX = new HashMap<>();

    /** Le serveur s'arrête : on lâche ses mondes. */
    static void oublier() { DITES.clear(); NOUVEAUX.clear(); }

    /** Le chef vient d'embaucher {@code t} : ses ouvriers pourront en parler. */
    static void embauche(Travailleur t) { NOUVEAUX.put(t.chef, new Object[]{t.prenom, t.level().getGameTime()}); }

    /** Appelé par le cerveau toutes les 5 s environ : une remarque, si son heure est venue et que rien ne l'en empêche. */
    static void essayer(Travailleur t) {
        int secondes = Reglages.actuel.secondes_entre_remarques;
        if (secondes <= 0 || t.chef == null || !(t.level() instanceof ServerLevel sl)) return;
        if (sl.getServer() instanceof net.minecraft.gametest.framework.GameTestServer && !enEssai) return;
        long maintenant = sl.getGameTime();
        if (t.prochaineRemarque == 0) t.prochaineRemarque = maintenant + delai(t, secondes); // pas tous ensemble au démarrage
        if (maintenant < t.prochaineRemarque || !libre(t)) return;
        DITES.removeIf(d -> maintenant - (long) d[2] >= MINUTE || maintenant < (long) d[2]);
        for (Object[] d : DITES) if (d[0] == sl && ((BlockPos) d[1]).closerThan(t.blockPosition(), PROCHES)) return;
        Object[] nouveau = NOUVEAUX.get(t.chef);
        String nom = nouveau != null && maintenant - (long) nouveau[1] < 24000 && !t.prenom.equals(nouveau[0]) ? (String) nouveau[0] : null;
        // Un compagnon mort il y a peu (et la nouvelle déjà dite) : il en reparle d'abord, une fois par jour (Deuil).
        String cle = Deuil.souvenir(t) && !recente(t, Deuil.SOUVENIR) && Vie.TOUTES.containsKey(Deuil.SOUVENIR) ? Deuil.SOUVENIR
                : Vie.remarque(Vie.TOUTES, moment(t, sl, maintenant, nom), k -> recente(t, k), t.getRandom()::nextDouble);
        t.prochaineRemarque = maintenant + delai(t, secondes);
        if (cle == null || !Besoins.dire(t, cle, nom)) return;
        var dits = t.memoire.getCompound("dits");
        dits.putLong("rq_" + cle, Cerveau.temps(t));
        dits.putLong("vie", Cerveau.temps(t)); // pas de réplique de besoin juste après (Besoins.permis)
        t.memoire.put("dits", dits);
        t.memoire.putString("remarque", cle); // la dernière (lue par les tests)
        DITES.add(new Object[]{sl, t.blockPosition(), maintenant});
    }

    /** Entre deux remarques d'un même ouvrier : le réglage, plus jusqu'à autant au hasard (10 à 20 min par défaut). */
    static long delai(Travailleur t, int secondes) { return secondes * 20L + t.getRandom().nextInt(secondes * 20 + 1); }

    /** Chaque remarque, au plus une fois par jour du jeu. */
    static boolean recente(Travailleur t, String cle) {
        var dits = t.memoire.getCompound("dits");
        return dits.contains("rq_" + cle) && Cerveau.temps(t) - dits.getLong("rq_" + cle) < Cerveau.UN_JOUR;
    }

    /** Rien ne l'en empêche : debout, éveillé, ni menacé, ni muet, ni occupé par son chef, une lettre ou une conversation, et pas de réplique de besoin à l'instant. */
    static boolean libre(Travailleur t) {
        long vie = t.memoire.getCompound("dits").getLong("vie");
        return !t.aTerre && !t.isSleeping() && !t.muet() && !t.reflechit && t.lettreEnMain == null && t.ecouteTicks <= 0
                && t.mode != Travailleur.Mode.GREVE && t.mode != Travailleur.Mode.CHOMEUR && !(t.action instanceof Causeries.Bavarder) && !(t.action instanceof Deuil.Enterrement)
                && Cerveau.temps(t) - vie >= 30 && !t.enDanger();
    }

    /** Ce qu'il sait de son moment, sans rien chercher : l'heure, le ciel au-dessus de lui, son travail, sa colonie. */
    static Vie.Moment moment(Travailleur t, ServerLevel sl, long maintenant, String nouveau) {
        BlockPos p = t.blockPosition();
        Biome.Precipitation ciel = sl.isRaining() ? sl.getBiome(p).value().getPrecipitationAt(p) : Biome.Precipitation.NONE;
        boolean pluie = ciel == Biome.Precipitation.RAIN, neige = ciel == Biome.Precipitation.SNOW;
        var dits = t.memoire.getCompound("dits");
        boolean fini = t.memoire.contains("fini_le") && maintenant - t.memoire.getLong("fini_le") < 6000;
        boolean depot = dits.contains("depot") && Cerveau.temps(t) - dits.getLong("depot") < 300; // la lettre « dépôt plein » (Cerveau)
        boolean auTravail = t.mode == Travailleur.Mode.TRAVAIL && t.enService() && !(t.action instanceof Actions.Reposer);
        boolean ennui = t.mode == Travailleur.Mode.ATTENDRE || auTravail && t.missions.isEmpty() && t.action instanceof Actions.Attendre a && a.ticks >= 200;
        boolean paie = t.memoire.contains("paie_le") && t.memoire.getLong("paie_le") == t.jour();
        return new Vie.Moment(IA.heure(t), pluie, sl.isThundering() && (pluie || neige), neige, auTravail, t.metier, fini, depot, ennui, nouveau, paie);
    }
}

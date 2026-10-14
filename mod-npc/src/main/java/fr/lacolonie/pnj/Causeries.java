package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deux ouvriers d'un même chef, oisifs l'un près de l'autre, se tournent l'un vers l'autre et échangent les répliques d'une
 * conversation de {@link Conversations} en bulles (lot 7). Une par colonie au plus toutes les
 * {@code Reglages.secondes_entre_conversations} (3 min par défaut, 0 = jamais). Rien à chaque tick : chaque ouvrier
 * oisif tente sa chance au plus toutes les 5 s, depuis son cerveau ({@link Cerveau#decider}).
 */
final class Causeries {
    private Causeries() {}

    /** Sur le serveur de tests, seulement dans le lot « conversations » (ailleurs, deux ouvriers au repos pourraient bavarder). */
    static boolean enEssai;
    /** Distance pour lancer une conversation (6 blocs), et au-delà de laquelle elle cesse (8). */
    static final double PRES = 6, LOIN = 8;
    /** Entre deux essais d'un même ouvrier (ticks), et durée maximale d'une conversation (ticks). */
    static final int ESSAI = 100, DUREE_MAX = 500;

    /** Par chef : le moment (temps du jeu) de la dernière conversation, et les dernières jouées. */
    static final Map<UUID, Long> DERNIERE = new HashMap<>();
    /** Par chef : la dernière causerie entre amis lancée avant l'heure commune (Relations). */
    static final Map<UUID, Long> DERNIERE_AMIS = new HashMap<>();
    static final Map<UUID, Deque<Integer>> RECENTES = new HashMap<>();

    /** Une conversation en cours, partagée par les deux ouvriers. */
    static final class Causerie {
        final Travailleur a, b;
        final Conversations.Conversation conv;
        final List<String> textes = new ArrayList<>();
        /** Ce qui a été dit, dans l'ordre : l'ouvrier, puis la réplique (lu par les tests). */
        final List<Map.Entry<Travailleur, String>> dites = new ArrayList<>();
        int suivante, prochaine;
        boolean fini, rompue;

        Causerie(Travailleur a, Travailleur b, Conversations.Conversation conv) {
            this.a = a;
            this.b = b;
            this.conv = conv;
            String chef = a.chefNom == null || a.chefNom.isBlank() ? "le chef" : a.chefNom;
            Map<String, String> v = Map.of("a", a.prenom, "b", b.prenom, "chef", chef,
                    "metier_a", Metier.nom(a.metier).toLowerCase(), "metier_b", Metier.nom(b.metier).toLowerCase());
            for (Conversations.Replique r : conv.repliques()) textes.add(Conversations.remplir(r.texte(), v));
        }
    }

    /** L'action de bavarder : il s'arrête, regarde l'autre ; A mène la conversation (il fait parler chacun à son tour). */
    static final class Bavarder extends Actions.Action {
        final Causerie c;
        final Travailleur autre;

        Bavarder(Causerie c, Travailleur autre) { super("bavarder"); this.c = c; this.autre = autre; }

        @Override
        Actions.Etat tick(Travailleur t) {
            if (c.fini) return Actions.Etat.OK;
            Travailleur o = autre;
            // L'autre part, est frappé, reçoit un ordre ou une lettre, un monstre approche : la conversation cesse.
            if (c.rompue || !o.isAlive() || o.isRemoved() || o.level() != t.level() || !(o.action instanceof Bavarder b && b.c == c)
                    || t.distanceToSqr(o) > LOIN * LOIN || t.reflechit || o.reflechit || t.hurtTime > 0 || o.hurtTime > 0 || o.lettreEnMain != null || o.ecouteTicks > 0
                    || age % 20 == 10 && t.enDanger() || age > DUREE_MAX) {
                c.rompue = !c.fini;
                raison = "interrompu";
                return Actions.Etat.ECHEC;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(o);
            if (t != c.a || age < c.prochaine) return Actions.Etat.EN_COURS;
            if (c.suivante >= c.textes.size()) { c.fini = true; Relations.causerie(c.a, c.b); return Actions.Etat.OK; }
            Travailleur qui = c.conv.repliques().get(c.suivante).deA() ? c.a : c.b;
            String texte = c.textes.get(c.suivante++);
            qui.dire(texte);
            c.dites.add(Map.entry(qui, texte));
            c.prochaine = age + (c.suivante < c.textes.size() ? Conversations.delai(texte) : 30);
            return Actions.Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (!c.fini) c.rompue = true;
        }
    }

    /**
     * Oisif : il attend (au moins 2 s), se repose arrivé à son lit ou au poste sans dormir, ou n'a rien en cours en mode attente ; au travail ou au poste ; ni en grève,
     * ni à terre, ni menacé (frappé, combat, fuite, péril), ni muet, ni affamé, ni sa paie à toucher, ni une consigne, une
     * lettre, un messager ou l'IA en cours.
     */
    static boolean oisif(Travailleur t) {
        Actions.Action a = t.action;
        // Pas « sans action » en plein travail (entre deux tâches, il reprendrait 25 s plus tard), sauf mis à attendre ; au repos, arrivé.
        boolean libre = a instanceof Actions.Attendre at && at.ticks >= 40 || a == null && t.mode == Travailleur.Mode.ATTENDRE
                || a instanceof Actions.Reposer r && r.but != null && t.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(r.but)) <= 2.5 * 2.5;
        return libre && t.chef != null && (t.mode == Travailleur.Mode.TRAVAIL || t.mode == Travailleur.Mode.ATTENDRE)
                && !t.isSleeping() && !t.aTerre && !t.muet() && t.lettreEnMain == null && t.ecouteTicks <= 0 && !t.reflechit
                && t.consigne == null && t.faim > 8 && !t.paieDue() && !t.menace();
    }

    /** Appelé par le cerveau (toutes les 10 ticks) : lance une conversation avec un voisin oisif du même chef. Vrai s'il l'a fait. */
    static boolean essayer(Travailleur t) {
        int secondes = Reglages.actuel.secondes_entre_conversations;
        if (secondes <= 0 || t.chef == null || !(t.level() instanceof ServerLevel sl)) return false;
        long maintenant = sl.getGameTime();
        if (maintenant < t.prochaineCauserie) return false;
        t.prochaineCauserie = maintenant + ESSAI + t.getRandom().nextInt(ESSAI);
        if (sl.getServer() instanceof net.minecraft.gametest.framework.GameTestServer && !enEssai) return false;
        Long der = DERNIERE.get(t.chef);
        // Entre deux conversations de la colonie : deux amis n'attendent que la moitié du temps (Relations).
        boolean tot = der != null && maintenant >= der && maintenant - der < secondes * 20L;
        Long derAmis = DERNIERE_AMIS.get(t.chef); // au plus une causerie « tôt » entre amis par intervalle, sans toucher à l'horloge commune
        if (tot && (maintenant - der < secondes * 10L || !Relations.actifs()
                || derAmis != null && maintenant >= derAmis && maintenant - derAmis < secondes * 20L)) return false;
        if (!oisif(t)) return false;
        List<Travailleur> autour = sl.getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(PRES),
                o -> o != t && t.chef.equals(o.chef) && t.distanceToSqr(o) <= PRES * PRES && oisif(o) && t.hasLineOfSight(o));
        // Deux ennemis ne causent pas ensemble : il se détourne, et le dit parfois.
        Liens liens = Relations.de(t);
        List<Travailleur> voisins = new ArrayList<>(), amis = new ArrayList<>();
        Travailleur evite = null;
        for (Travailleur o : autour) {
            if (Relations.actifs() && Relations.brouilles(t, liens, o)) { evite = o; continue; }
            boolean ami = liens.ami(o.getStringUUID());
            if (tot && !ami) continue;
            voisins.add(o);
            if (ami) amis.add(o);
        }
        if (voisins.isEmpty() && evite != null && !t.enDanger()) Besoins.parler(t, "inimitie_evite", evite.prenom);
        if (voisins.isEmpty() || t.enDanger()) return false;
        if (!amis.isEmpty() && t.getRandom().nextInt(3) > 0) voisins = amis; // un ami de préférence
        Travailleur o = voisins.get(t.getRandom().nextInt(voisins.size()));
        if (o.enDanger()) return false;
        int heure = (int) ((sl.getDayTime() + 6000) % 24000 / 1000);
        BlockPos p = t.blockPosition();
        Biome.Precipitation ciel = sl.isRaining() ? sl.getBiome(p).value().getPrecipitationAt(p) : Biome.Precipitation.NONE;
        boolean pluie = ciel == Biome.Precipitation.RAIN, neige = ciel == Biome.Precipitation.SNOW;
        boolean orage = sl.isThundering() && (pluie || neige);
        Conversations.Personne pt = personne(t, maintenant), po = personne(o, maintenant);
        Set<String> ab = Conversations.contexte(heure, pluie, orage, neige, pt, po), ba = Conversations.contexte(heure, pluie, orage, neige, po, pt);
        Deque<Integer> recentes = RECENTES.computeIfAbsent(t.chef, k -> Conversations.nouvelles());
        Conversations.Choix ch = Conversations.choisir(Conversations.TOUTES, List.of(ab, ba), recentes, t.getRandom()::nextDouble);
        if (ch == null) return false;
        (tot ? DERNIERE_AMIS : DERNIERE).put(t.chef, maintenant);
        Conversations.noter(recentes, ch.id());
        Travailleur a = ch.sens() == 0 ? t : o, b = ch.sens() == 0 ? o : t;
        Causerie c = new Causerie(a, b, Conversations.TOUTES.get(ch.id()));
        Cerveau.lancer(a, new Bavarder(c, b));
        Cerveau.lancer(b, new Bavarder(c, a));
        return true;
    }

    static Conversations.Personne personne(Travailleur t, long maintenant) {
        boolean paye = t.memoire.contains("paie_le") && t.memoire.getLong("paie_le") == t.jour();
        boolean danger = t.finDanger > 0 && maintenant - t.finDanger < 2400;
        return new Conversations.Personne(t.metier, t.temperament, t.faim, t.fatigue, t.moral, paye, t.impayes, danger);
    }
}

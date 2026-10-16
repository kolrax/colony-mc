package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Les besoins qui parlent (lot 5) : faim, fatigue, lit, blessure, humeur, envie de pause, avec les répliques de
 * {@code vie.txt} ({@link Vie}). Chacun au plus une fois toutes les 10 minutes. Aussi : la pause (loisirs) et le lit
 * perdu. Le cerveau appelle {@link #veiller} toutes les 5 s environ, et {@link #pause} juste avant le travail.
 */
final class Besoins {
    private Besoins() {}

    /** Une même réplique de besoin, au plus une fois par tant de secondes (10 minutes). */
    static final int DELAI = 600;
    /** Entre deux répliques de besoin, quelles qu'elles soient : au moins tant de secondes. */
    static final int ENTRE = 60;

    /** Vrai (et noté) si la réplique {@code cle} peut être dite : pas dite depuis {@link #DELAI}, et aucune autre depuis {@link #ENTRE}. */
    static boolean permis(Travailleur t, String cle) {
        CompoundTag dits = t.memoire.getCompound("dits");
        long maintenant = Cerveau.temps(t);
        if (dits.contains("vie") && maintenant - dits.getLong("vie") < ENTRE) return false;
        if (dits.contains("vie_" + cle) && maintenant - dits.getLong("vie_" + cle) < DELAI) return false;
        dits.putLong("vie", maintenant);
        dits.putLong("vie_" + cle, maintenant);
        t.memoire.put("dits", dits);
        return true;
    }

    /** Il dit la réplique {@code cle} de vie.txt, si elle est permise ({@link #permis}). {@code nom} : l'autre en jeu (ou null : « quelqu'un »). */
    static boolean parler(Travailleur t, String cle, String nom) {
        return !t.muet() && permis(t, cle) && dire(t, cle, nom);
    }

    /** Pareil, avec {@code {objet}} : ce dont il parle (une prise du pêcheur), ou null (les répliques qui le citent ne sortent pas). */
    static boolean parler(Travailleur t, String cle, String nom, String objet) {
        return !t.muet() && permis(t, cle) && dire(t, cle, nom, objet);
    }

    /** Il dit la réplique {@code cle} tout de suite, sans délai : ce qui n'arrive qu'une fois (à terre, relevé, soigné). */
    static boolean dire(Travailleur t, String cle, String nom) { return dire(t, cle, nom, null); }

    static boolean dire(Travailleur t, String cle, String nom, String objet) {
        String texte = texte(t, cle, nom, objet);
        if (texte != null) t.dire(texte);
        return texte != null;
    }

    /** La réplique {@code cle} tirée pour lui (variables remplies), sans la dire : pour une réponse au chef. Null si la section manque. */
    static String texte(Travailleur t, String cle, String nom, String objet) {
        Map<String, String> v = new HashMap<>();
        if (objet != null) v.put("objet", objet);
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        if (nom != null) v.put("nom", nom);
        if (t.memoire.contains("deuil_nom")) v.put("mort", t.memoire.getString("deuil_nom")); // deuil_tristesse (Humeur.deuil)
        return Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble);
    }

    /** Toutes les 5 s environ : ce que son corps et son humeur lui font dire. Rien à terre, endormi, en combat ou en fuite. */
    static void veiller(Travailleur t) {
        if (t.aTerre || t.isSleeping() || t.menace()) return;
        int avant = t.memoire.contains("moral_vu") ? t.memoire.getInt("moral_vu") : Obeissance.DEPART;
        t.memoire.putInt("moral_vu", t.moral);
        // Au repos chez lui, son lit est-il toujours là ? (monLit le voit cassé, en cherche un autre, et s'il n'y en a pas, le dit.)
        // À 100 de fatigue, il est épuisé même si le cerveau ne l'a pas encore noté (veiller passe avant, au même tick) :
        // il va se coucher, pas dire « fatigue », qui fermerait la bouche à « pas de lit » pendant ENTRE secondes.
        boolean repos = !t.enService() || t.fatigue >= 100 || t.memoire.getBoolean("epuise");
        if (repos && t.lit != null && t.bureau != null && t.chezLui(t.blockPosition())) t.monLit();
        // Une seule réplique par passage : la première qui a quelque chose à dire.
        String faim = Vie.faim(t.faim), blessure = Blessures.cle(t), humeur = Vie.humeur(t.moral, avant);
        if (faim != null && parler(t, faim, null)) return;
        if (blessure != null && parler(t, blessure, null)) return;
        if (t.fatigue >= Vie.FATIGUE && !repos && parler(t, "fatigue", null)) return;
        // Pas de lit : seulement au travail (pas s'il suit ou attend), au repos, après une vraie recherche (Actions.Reposer).
        if (t.mode == Travailleur.Mode.TRAVAIL && t.action instanceof Actions.Reposer r && r.sansLit && parler(t, "sans_lit", null)) return;
        if (humeur != null) parler(t, humeur, null);
    }

    /** Son lit n'est plus là (cassé, ou le poste a changé) et il n'en trouve pas d'autre : moral −3 et « pas de lit » (une fois par 10 minutes). */
    static void litPerdu(Travailleur t) {
        if (!Cerveau.parfois(t, "lit_perdu", DELAI)) return;
        t.changerMoral(Obeissance.LIT_VOLE);
        parler(t, "sans_lit", null);
    }

    /**
     * Un autre dort dans son lit : il le dit (son prénom pour un ouvrier, son nom pour un joueur, sinon « quelqu'un ») et son
     * moral baisse (une fois par 10 minutes). Un autre ouvrier : il lui laisse ce lit et en cherchera un autre.
     */
    static void litVole(Travailleur t, LivingEntity qui) {
        String nom = qui instanceof Travailleur o ? o.prenom : qui instanceof Player p ? p.getName().getString() : null;
        if (qui instanceof Travailleur o && t.lit != null) { t.litRefuse = t.lit; t.lit = null; Relations.lit(t, o); }
        if (!Cerveau.parfois(t, "lit_perdu", DELAI)) return;
        t.changerMoral(Obeissance.LIT_VOLE);
        parler(t, "lit_vole", nom);
    }

    /** Qui dort dans ce lit, à part lui (null : personne). */
    static LivingEntity dormeur(Travailleur t, BlockPos lit) {
        for (LivingEntity e : t.level().getEntitiesOfClass(LivingEntity.class, new AABB(lit).inflate(2), e -> e != t && lit.equals(e.getSleepingPos().orElse(null))))
            return e;
        return null;
    }

    /**
     * Juste avant de travailler : moral moyen ou bas et longtemps au travail, il prend une courte pause au poste. Le cerveau
     * n'y arrive qu'après les combats, les consignes, les ordres (suivre, attendre, rentrer) et le repos : rien de tout ça
     * n'est quitté pour une pause. Vrai s'il la prend.
     */
    static boolean pause(Travailleur t) {
        if (t.mode != Travailleur.Mode.TRAVAIL || t.bureau == null || t.consigne != null || !t.chezLui(t.blockPosition())
                || !Vie.veutPause(t.moral, t.travailSansPause)) return false;
        // Le chef vient de lui donner un ordre (« au travail ! » en pleine pause) : pas de pause avant longtemps.
        if (t.ordreChef > 0 && t.level().getGameTime() - t.ordreChef < Vie.PAUSE_APRES) return false;
        t.travailSansPause = 0; // une pause coupée (ordre, coup, danger) ne recommence pas aussitôt
        Cerveau.lancer(t, new Flaner(Vie.PAUSE_MIN + t.getRandom().nextInt(Vie.PAUSE_MAX - Vie.PAUSE_MIN + 1)));
        parler(t, "envie_pause", null);
        return true;
    }

    /** La pause : il va près du poste, s'assied un moment, puis reprend (moral + 2 à la fin ; le compte de travail est remis à zéro dès le début). */
    static final class Flaner extends Actions.Action {
        final int duree;
        int assis = -1;

        Flaner(int duree) { super("pause"); this.duree = duree; }

        Actions.Etat tick(Travailleur t) {
            if (t.bureau == null) return Actions.Etat.ECHEC;
            Vec3 poste = Vec3.atBottomCenterOf(t.bureau);
            if (assis < 0) {
                if (t.position().distanceToSqr(poste) > 4 * 4 && !coince()) { marcher(t, poste, 0.8); return Actions.Etat.EN_COURS; }
                t.getNavigation().stop();
                t.getLookControl().setLookAt(poste);
                t.setGeste("assis");
                assis = age;
            }
            if (age - assis < duree) return Actions.Etat.EN_COURS;
            t.changerMoral(Obeissance.PAUSE);
            return Actions.Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.setGeste("");
        }
    }
}

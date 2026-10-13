package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
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

    /** Il dit la réplique {@code cle} de vie.txt, au plus une fois par {@link #DELAI}. {@code nom} : l'autre PNJ en jeu (ou null). */
    static boolean parler(Travailleur t, String cle, String nom) {
        if (t.muet() || !Cerveau.parfois(t, "vie_" + cle, DELAI)) return false;
        Map<String, String> v = new HashMap<>();
        v.put("chef", t.chefNom.isEmpty() ? "chef" : t.chefNom);
        if (nom != null) v.put("nom", nom);
        String texte = Vie.tirer(cle, t.temperament, v, t.getRandom()::nextDouble);
        if (texte != null) t.dire(texte);
        return texte != null;
    }

    /** Toutes les 5 s environ : ce que son corps et son humeur lui font dire. Rien à terre, endormi, en combat ou en fuite. */
    static void veiller(Travailleur t) {
        if (t.aTerre || t.isSleeping() || t.menace()) return;
        String faim = Vie.faim(t.faim);
        if (faim != null) parler(t, faim, null);
        String blessure = Vie.blessure(t.getHealth(), t.getMaxHealth());
        if (blessure != null) parler(t, blessure, null);
        boolean repos = !t.enService() || t.memoire.getBoolean("epuise");
        if (t.fatigue >= Vie.FATIGUE && !repos) parler(t, "fatigue", null);
        // À l'heure du repos, chez lui : son lit est-il toujours là (monLit le voit cassé : lit_vole) ? Pas de lit à lui :
        // il le dit. Pas de nouvelle recherche ici (un balayage de blocs) : le repos (Actions.Reposer) la fait.
        if (repos && t.bureau != null && t.chezLui(t.blockPosition())) {
            if (t.lit != null) t.monLit();
            if (t.lit == null) parler(t, "sans_lit", null);
        }
        int avant = t.memoire.contains("moral_vu") ? t.memoire.getInt("moral_vu") : Obeissance.DEPART;
        String humeur = Vie.humeur(t.moral, avant);
        if (humeur != null) parler(t, humeur, null);
        t.memoire.putInt("moral_vu", t.moral);
    }

    /** Son lit est cassé, ou un autre y dort ({@code nom}, ou null) : il le dit, et son moral baisse (une fois par 10 minutes). */
    static void litVole(Travailleur t, String nom) {
        if (!Cerveau.parfois(t, "lit_perdu", DELAI)) return;
        t.changerMoral(Obeissance.LIT_VOLE);
        parler(t, "lit_vole", nom);
    }

    /** Qui dort dans ce lit, à part lui (null : personne). */
    static String dormeur(Travailleur t, BlockPos lit) {
        for (LivingEntity e : t.level().getEntitiesOfClass(LivingEntity.class, new AABB(lit).inflate(2), e -> e != t && lit.equals(e.getSleepingPos().orElse(null))))
            return e instanceof Travailleur o ? o.prenom : e.getName().getString();
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
        Cerveau.lancer(t, new Flaner(Vie.PAUSE_MIN + t.getRandom().nextInt(Vie.PAUSE_MAX - Vie.PAUSE_MIN + 1)));
        parler(t, "envie_pause", null);
        return true;
    }

    /** La pause : il va près du poste, s'assied un moment, puis reprend (moral + 2). */
    static final class Flaner extends Actions.Action {
        final int duree;
        int assis = -1;

        Flaner(int duree) { super("pause"); this.duree = duree; }

        Actions.Etat tick(Travailleur t) {
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
            t.travailSansPause = 0;
            return Actions.Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.setGeste("");
        }
    }
}

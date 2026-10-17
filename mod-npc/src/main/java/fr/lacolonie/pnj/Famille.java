package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lot 13, cahier 5.6, 5.7 et 4.2 : les familles côté serveur (les règles : {@link Ages}). Une fois par minute réelle
 * ({@link #veiller}, décalé par l'identifiant) : il prend de l'âge, grandit, devient apprenti puis ouvrier, se fait vieux,
 * s'éteint ; un couple du même chef peut avoir un enfant. L'enfant ne travaille pas : il joue près du poste ou de ses
 * parents, mange au dépôt, dort, fuit les monstres ({@link #penser}). Ce qu'il sait de sa famille est dans sa mémoire
 * (sauvegardée) : « parents », « enfants » (« id:Prénom;… »), « famille » (le nom de sa maison), « talents_parents ».
 */
final class Famille {
    private Famille() {}

    static Ages.Reglage reglage() { return Reglages.actuel.ages; }

    static boolean actives() { return Reglages.actuel.familles; }

    /** Son âge à son arrivée : un chercheur d'emploi de 18 à 50 ans, un ancien ouvrier de 20 à 45 ; et l'âge où il s'éteindra. */
    static void naitreAdulte(Travailleur t, double min, double max) {
        var r = t.getRandom();
        t.age = Ages.tirer(min, max, r::nextDouble);
        t.ageFin = Ages.tirer(reglage().fin_min, reglage().fin_max + 0.999, r::nextDouble);
        t.ageLe = 0;
    }

    /** Sa taille et sa vie selon son âge (sans le soigner) : à l'arrivée, au chargement, et quand l'âge change. */
    static void appliquer(Travailleur t) {
        var taille = t.getAttribute(Attributes.SCALE);
        if (taille != null) {
            double e = actives() ? Ages.echelle(t.age < 0 ? Ages.ADULTE : t.age) : 1;
            if (Math.abs(taille.getBaseValue() - e) > 0.005) { taille.setBaseValue(e); t.refreshDimensions(); }
        }
        double vie = 20 + 2 * (t.niveau - 1) + Talents.vie(t.talents) - (actives() ? Ages.vieEnMoins(t.age) : 0);
        var max = t.getAttribute(Attributes.MAX_HEALTH);
        if (max != null && max.getBaseValue() != vie) {
            max.setBaseValue(vie);
            if (t.getHealth() > t.getMaxHealth()) t.setHealth(t.getMaxHealth());
        }
    }

    static boolean enfant(Travailleur t) { return actives() && t.age >= 0 && t.age < Ages.APPRENTI; }

    static boolean apprenti(Travailleur t) { return actives() && t.age >= Ages.APPRENTI && t.age < Ages.ADULTE; }

    /** Moins de 16 ans : ni guerre, ni garde. */
    static boolean mineur(Travailleur t) { return actives() && t.age >= 0 && t.age < Ages.ADULTE; }

    /** Ce qu'on lit au-dessus de sa tête : « Enfant », « Apprenti mineur », ou son métier. */
    static String nomMetier(Travailleur t) {
        if (enfant(t)) return "Enfant";
        if (apprenti(t)) return "Apprenti " + Metier.nom(t.metier).toLowerCase();
        return Metier.nom(t.metier);
    }

    // --- Le temps qui passe (une fois par minute) ---------------------------------------------------------------------

    static void veiller(Travailleur t) {
        if (t.chef == null || t.aTerre || !(t.level() instanceof ServerLevel)) return;
        paie(t); // même familles coupées : un enfant devenu ouvrier est payé
        if (!actives()) return;
        long maintenant = t.level().getGameTime();
        if (t.age < 0) naitreAdulte(t, 20, 45);
        if (t.ageLe == 0 || maintenant < t.ageLe) { t.ageLe = maintenant; return; }
        double avant = t.age;
        t.age = Ages.avancer(avant, maintenant - t.ageLe, reglage());
        t.ageLe = maintenant;
        List<Integer> seuils = Ages.passages(avant, t.age);
        if (!seuils.isEmpty() || avant < Ages.ADULTE) appliquer(t); // il grandit jusqu'à seize ans
        for (int seuil : seuils) // tous les seuils franchis (une longue absence : de 11 à 16 ans d'un coup)
            switch (seuil) {
                case Ages.APPRENTI -> apprenti(t, avant);
                case Ages.ADULTE -> adulte(t);
                case Ages.VIEUX -> vieux(t, true);
                case Ages.TRES_VIEUX -> vieux(t, false);
                default -> {}
            }
        boolean repos = !t.enService() || t.isSleeping() || t.action instanceof Actions.Reposer;
        if (Ages.fin(t.age, t.ageFin, repos) && !t.menace()) { eteindre(t); return; }
        naissance(t, false);
    }

    /** 12 ans : il commence à apprendre le métier qu'on lui destine. */
    static void apprenti(Travailleur t, double avant) {
        t.majNom();
        String metier = Metier.nom(t.metier).toLowerCase();
        if (!Besoins.dire(t, "apprenti_debut", null, metier)) t.dire("Me voilà en âge d'apprendre le métier de " + metier + " !");
        Courrier.ecrire(t, "apprenti", metier);
        paie(t); // sa première demi-paie, au prochain terme
    }

    /**
     * Né dans la colonie (il a des parents), il n'est plus un enfant et n'avait pas de paie ({@link Monnaie#SANS_PAIE}) :
     * elle reprend au prochain terme. Pas les ouvriers des tests et du banc, qui n'ont pas de parents.
     */
    static void paie(Travailleur t) {
        if (t.prochainePaie == Monnaie.SANS_PAIE && !enfant(t) && t.memoire.contains("parents"))
            t.prochainePaie = System.currentTimeMillis() + Travailleur.periodePaie();
    }

    /** 16 ans : un ouvrier comme les autres, ★ au moins ; une chance sur deux d'un talent de ses parents. */
    static void adulte(Travailleur t) {
        var r = t.getRandom();
        String talent = Ages.talentHerite(Talents.lire(t.memoire.getString("talents_parents")), r.nextDouble(), r.nextDouble());
        if (talent != null && !t.talents.contains(talent)) {
            List<String> l = new ArrayList<>(t.talents);
            l.add(talent);
            t.talents = List.copyOf(l);
            appliquer(t);
        }
        t.niveau = Math.max(1, t.niveau);
        paie(t);
        t.majNom();
        String metier = Metier.nom(t.metier).toLowerCase();
        if (!Besoins.dire(t, "adulte", null, metier)) t.dire("Seize ans ! Le métier de " + metier + " est le mien pour de bon.");
        Courrier.ecrire(t, "adulte", metier, Ages.don(talent));
    }

    /** 60 ans : il le dit, et l'écrit au chef ; 70 ans : il le dit seulement. */
    static void vieux(Travailleur t, boolean lettre) {
        appliquer(t);
        Besoins.dire(t, lettre ? "vieux" : "tres_vieux", null);
        if (lettre) Courrier.ecrire(t, "vieux", Ages.ans(t.age));
    }

    /** Son heure est venue : il s'éteint paisiblement (ni « à terre », ni mauvaise réputation pour le chef). */
    static void eteindre(Travailleur t) {
        if (t.isRemoved() || t.isDeadOrDying()) return;
        t.memoire.putBoolean("vieillesse", true);
        Besoins.dire(t, "adieu", null);
        t.mortDefinitive = true;
        t.setHealth(0);
        t.die(t.damageSources().generic());
    }

    // --- Les naissances -----------------------------------------------------------------------------------------------

    /** Un couple du même chef, au travail et content, sous un toit : de loin en loin, un enfant ({@code forcer} : les tests). */
    static Travailleur naissance(Travailleur t, boolean forcer) {
        if (!actives() || t.mode != Travailleur.Mode.TRAVAIL || t.age < Ages.ADULTE || t.bureau == null || !Reglages.actuel.liens_couples) return null;
        Travailleur c = Relations.conjoint(t);
        if (c == null || c.mode != Travailleur.Mode.TRAVAIL || c.age >= 0 && c.age < Ages.ADULTE) return null;
        boolean femme = Ages.femme(t.prenom, Travailleur.PRENOMS), autre = Ages.femme(c.prenom, Travailleur.PRENOMS);
        if (!Ages.porte(femme, autre, t.getStringUUID().compareTo(c.getStringUUID()) < 0)) return null;
        // Le tirage d'abord : le reste (compter la colonie) seulement les rares fois où il tombe.
        if (!forcer && t.getRandom().nextDouble() >= Ages.chanceParMinute(reglage().naissances_par_jour)) return null;
        int enfants = Math.max(Ages.lire(t.memoire.getString("enfants")).size(), Ages.lire(c.memoire.getString("enfants")).size());
        int colonie = ColoniePnj.pnjDe(t.level().getServer(), t.chef).size();
        Ages.Couple k = new Ages.Couple(true, t.moral, c.moral, t.age, toit(t) || toit(c), enfants, reglage().enfants_max, colonie, Reglages.actuel.pnj_max_par_joueur);
        if (!Ages.naissancePossible(k)) return null;
        return naitre(t, c);
    }

    /** Un lit à lui, ou un toit au-dessus du poste. */
    static boolean toit(Travailleur t) {
        if (t.lit != null) return true;
        BlockPos b = t.bureau;
        return b != null && t.level().isLoaded(b) && t.level().getHeight(Heightmap.Types.MOTION_BLOCKING, b.getX(), b.getZ()) > b.getY() + 1;
    }

    /** L'enfant naît près de sa mère : un ouvrier du même chef, petit, sans salaire, qui connaît ses parents. */
    static Travailleur naitre(Travailleur mere, Travailleur autre) {
        if (!(mere.level() instanceof ServerLevel sl)) return null;
        Travailleur e = ColoniePnj.TRAVAILLEUR.get().create(sl);
        if (e == null) return null;
        var r = mere.getRandom();
        Vec3 lieu = auPoste(sl, mere.bureau);
        if (lieu == null) lieu = mere.position();
        e.moveTo(lieu.x, lieu.y, lieu.z, mere.getYRot(), 0);
        e.tirerAuSort(Ages.metierHerite(mere.metier, autre.metier, r.nextDouble()), 1);
        e.talents = List.of(); // ses talents viendront avec l'âge (Ages.talentHerite)
        e.age = 0;
        e.ageLe = sl.getGameTime();
        e.mode = Travailleur.Mode.TRAVAIL;
        e.chef = mere.chef;
        e.chefNom = mere.chefNom;
        e.bureau = mere.bureau;
        e.prochainePaie = Monnaie.SANS_PAIE; // aucun salaire avant douze ans
        e.arrivee = System.currentTimeMillis();
        String maison = !mere.memoire.getString("famille").isEmpty() ? mere.memoire.getString("famille")
                : !autre.memoire.getString("famille").isEmpty() ? autre.memoire.getString("famille")
                : Ages.femme(autre.prenom, Travailleur.PRENOMS) ? mere.prenom : autre.prenom;
        e.memoire.putString("famille", maison);
        e.memoire.putString("parents", Ages.texte(List.of(new Ages.Proche(mere.getStringUUID(), mere.prenom), new Ages.Proche(autre.getStringUUID(), autre.prenom))));
        List<String> talents = new ArrayList<>(mere.talents);
        for (String x : autre.talents) if (!talents.contains(x)) talents.add(x);
        e.memoire.putString("talents_parents", Talents.texte(talents));
        if (!mere.memoire.contains("famille")) mere.memoire.putString("famille", maison);
        if (!autre.memoire.contains("famille")) autre.memoire.putString("famille", maison);
        for (Travailleur p : List.of(mere, autre)) {
            List<Ages.Proche> l = Ages.lire(p.memoire.getString("enfants"));
            l.add(new Ages.Proche(e.getStringUUID(), e.prenom));
            p.memoire.putString("enfants", Ages.texte(l));
        }
        e.appliquerNiveau();
        appliquer(e);
        e.setHealth(e.getMaxHealth());
        e.majNom();
        sl.addFreshEntity(e);
        Courrier.de(sl.getServer()).inscrire(e);
        Besoins.dire(mere, "naissance", e.prenom);
        Courrier.ecrire(mere, "naissance", e.prenom, autre.prenom);
        return e;
    }

    /** Une place libre à côté du poste (deux blocs d'air sur un sol), là où l'enfant voit le jour ; null s'il n'y en a pas. */
    static Vec3 auPoste(ServerLevel sl, BlockPos poste) {
        if (poste == null || !sl.isLoaded(poste)) return null;
        for (int dy : new int[]{0, -1, 1})
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos p = poste.relative(d).above(dy);
                if (sl.getBlockState(p).getCollisionShape(sl, p).isEmpty() && sl.getBlockState(p.above()).getCollisionShape(sl, p.above()).isEmpty()
                        && sl.getBlockState(p.below()).isFaceSturdy(sl, p.below(), net.minecraft.core.Direction.UP)) return Vec3.atBottomCenterOf(p);
            }
        return null;
    }

    /** Ces deux-là ne se mettent jamais en couple : un mineur (moins de 16 ans), ou des proches (parent, enfant, frère ou sœur). */
    static boolean pasDeCouple(Travailleur a, Travailleur b) {
        if (!actives()) return false;
        if (mineur(a) || mineur(b)) return true;
        return Ages.proches(a.getStringUUID(), a.memoire.getString("parents"), a.memoire.getString("enfants"),
                b.getStringUUID(), b.memoire.getString("parents"), b.memoire.getString("enfants"));
    }

    /** Il peut porter les armes contre un homme : un combattant, pas un mineur. */
    static boolean arme(Travailleur t) {
        Metier m = Metier.de(t.metier);
        return m != null && m.combattant && !mineur(t);
    }

    // --- La famille et le moral ------------------------------------------------------------------------------------------

    /** +1 au moral (avec les amitiés) quand un parent ou un enfant vit chez le même chef (d'après l'effectif, sans recherche). */
    static int moral(Travailleur t) {
        if (!actives() || t.chef == null || t.level().getServer() == null) return 0;
        var eff = Courrier.de(t.level().getServer()).effectifs.get(t.chef);
        if (eff == null) return 0;
        for (String cle : new String[]{"parents", "enfants"})
            for (Ages.Proche p : Ages.lire(t.memoire.getString(cle))) {
                try {
                    Courrier.Membre m = eff.get(UUID.fromString(p.id()));
                    if (m != null && m.mort.isEmpty()) return 1;
                } catch (IllegalArgumentException ignore) { /* mémoire abîmée */ }
            }
        return 0;
    }

    /** La mort d'un parent ou d'un enfant pèse comme celle de l'être aimé (3), sinon 1 ({@link Humeur#deuil}). */
    static int poidsDeuil(Travailleur t, Travailleur mort) {
        if (!actives()) return 1;
        String id = mort.getStringUUID();
        return Ages.parmi(t.memoire.getString("parents"), id) || Ages.parmi(t.memoire.getString("enfants"), id) ? 3 : 1;
    }

    // --- Ce qu'il dit de lui, ce qu'il fait des ordres ----------------------------------------------------------------

    /** « Quel âge as-tu ? », « qui sont tes parents ? », « as-tu des enfants ? » : sa réponse, ou null. */
    static String question(Travailleur t, String texte) {
        if (!actives() || t.age < 0) return null;
        Ages.Question q = Ages.question(Ordres.normaliser(texte));
        if (q == null) return null;
        return switch (q) {
            case AGE -> {
                String age = Ages.ansTexte(t.age);
                String s = Besoins.texte(t, enfant(t) ? "age_enfant" : t.age >= Ages.VIEUX ? "age_vieux" : "age", null, age);
                yield s != null ? s : "J'ai " + age + ".";
            }
            case PARENTS -> {
                List<Ages.Proche> p = Ages.lire(t.memoire.getString("parents"));
                String s = p.isEmpty() ? Besoins.texte(t, "parents_aucun", null, null) : Besoins.texte(t, "parents", Ages.noms(p), null);
                yield s != null ? s : p.isEmpty() ? "Mes parents sont loin d'ici, ou en terre depuis longtemps." : "Mes parents sont " + Ages.noms(p) + ".";
            }
            case ENFANTS -> {
                List<Ages.Proche> e = Ages.lire(t.memoire.getString("enfants"));
                String s = e.isEmpty() ? Besoins.texte(t, "enfants_aucun", null, null) : Besoins.texte(t, "enfants", Ages.noms(e), null);
                yield s != null ? s : e.isEmpty() ? "Je n'ai pas d'enfant." : "Oui : " + Ages.noms(e) + ".";
            }
        };
    }

    /**
     * Un ordre à un enfant ou à un apprenti (appelé par {@link Travailleur#obeir}) : « apprends le métier de bûcheron »
     * change le métier qu'il apprend ; l'enfant ne travaille pas (une réplique d'enfant). Null : l'ordre suit son cours.
     */
    static String ordre(Travailleur t, Ordres.Lecture l) {
        if (!mineur(t)) return null;
        String m = Ages.metierAppris(Ordres.normaliser(l.texte), Ordres.NOMS_METIERS);
        if (m != null && Metier.existe(m) && !Reglages.actuel.metiers_inactifs.contains(m)) {
            boolean change = !m.equals(t.metier);
            t.metier = m;
            t.missions.clear();
            if (t.action != null && !enfant(t)) t.finirAction("ordre");
            t.majNom();
            String nom = Metier.nom(m).toLowerCase();
            String s = Besoins.texte(t, enfant(t) ? "enfant_metier" : "apprenti_metier", null, nom);
            return s != null ? s : change ? "Oui, chef ! J'apprendrai le métier de " + nom + "." : "C'est déjà le métier que j'apprends, chef.";
        }
        // Un mineur ne se bat pas sur ordre (ni joueur, ni bête) : « trop jeune ».
        boolean combat = l.faire.stream().anyMatch(f -> { Repertoire.Entree x = Repertoire.PAR_ID.get(f.id()); return x != null && x.comportement().equals("combat"); })
                || l.posture == GardeChef.Posture.ATTAQUE || l.depot == GardeChef.Posture.ATTAQUE;
        if (combat) {
            String s = Besoins.texte(t, "mineur_combat", null, null);
            return s != null ? s : "Je suis trop jeune pour me battre, chef. Demande aux grands.";
        }
        if (!enfant(t) || Obeissance.sansOrdre(l) && l.faire.isEmpty()) return null;
        String s = Besoins.texte(t, "enfant_ordre", null, null);
        return s != null ? s : "Ce n'est pas un ouvrage pour un enfant, chef !";
    }

    /** Sur la fiche du bureau : « 34 ans, de la maison de Raoul. » */
    static String portrait(Travailleur t) {
        if (!actives() || t.age < 0) return "";
        String maison = t.memoire.getString("famille");
        List<Ages.Proche> p = Ages.lire(t.memoire.getString("parents"));
        return Ages.ansTexte(t.age).substring(0, 1).toUpperCase() + Ages.ansTexte(t.age).substring(1)
                + (p.isEmpty() ? "" : ", enfant de " + Ages.noms(p)) + (maison.isEmpty() || !p.isEmpty() ? "" : ", de la maison de " + maison) + ".";
    }

    // --- L'enfant au quotidien (à la place du cerveau) -------------------------------------------------------------------

    /** Vrai s'il a pensé en enfant (il ne travaille pas) ; appelé par {@link Cerveau#penser} toutes les demi-secondes. */
    static boolean penser(Travailleur t) {
        if (!enfant(t)) return false;
        // Frappé ou un monstre tout près : il se sauve (une recherche toutes les 2 s au plus).
        if (!(t.action instanceof Actions.Fuir) && !(t.action instanceof Actions.Echapper)) {
            LivingEntity danger = t.getLastHurtByMob();
            if (danger == null || !danger.isAlive() || t.estDe(danger) || !Monde.recent(t, t.getLastHurtByMobTimestamp())) danger = null;
            if (danger == null && (t.tickCount + t.getId()) % 40 < 10)
                danger = t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(10, 4, 10), x -> Monde.hostile(x) && t.hasLineOfSight(x))
                        .stream().findFirst().orElse(null);
            if (danger != null) {
                if (t.action != null) t.finirAction("danger");
                if (Cerveau.parfois(t, "enfant_peur", 20)) Besoins.dire(t, "enfant_peur", null);
                Cerveau.lancer(t, new Actions.Fuir(danger));
                return true;
            }
        }
        if (t.action != null) return true;
        if (t.faim <= 14 && t.manger()) return true;
        if (t.faim <= 8) {
            Bureau.Entite b = Bureau.entite(t);
            if (b != null && b.compter(Actions.Filtre.de("nourriture")) > 0) { Cerveau.lancer(t, new Actions.Prendre(Actions.Filtre.de("nourriture"), 4, 2)); return true; }
        }
        if (t.bureau == null) { Cerveau.attendre(t, 10); return true; }
        if (!t.enService()) { Cerveau.lancer(t, new Actions.Reposer()); return true; }
        Vec3 poste = Vec3.atBottomCenterOf(t.bureau);
        if (t.distanceToSqr(poste) > 14 * 14) { Cerveau.lancer(t, new Actions.Aller(poste, 3)); return true; }
        // Il joue : près d'un parent s'il y en a un à côté, sinon autour du poste ; une fois de temps en temps, un mot d'enfant.
        if (Cerveau.parfois(t, "enfant_jeu", 300)) Besoins.parler(t, "enfant_jeu", null);
        Travailleur parent = null;
        if (t.getRandom().nextBoolean() && t.level() instanceof ServerLevel sl)
            for (Ages.Proche p : Ages.lire(t.memoire.getString("parents"))) {
                try {
                    if (sl.getEntity(UUID.fromString(p.id())) instanceof Travailleur x && x.isAlive() && x.distanceToSqr(t) < 24 * 24 && x.distanceToSqr(poste) < 40 * 40) { parent = x; break; }
                } catch (IllegalArgumentException ignore) { /* mémoire abîmée */ }
            }
        Vec3 but = parent != null ? parent.position() : LandRandomPos.getPosTowards(t, 6, 3, poste);
        if (but != null && but.distanceToSqr(t.position()) > 4) Cerveau.lancer(t, new Actions.Aller(but, 2));
        else Cerveau.attendre(t, 5 + t.getRandom().nextInt(10));
        return true;
    }

    /** Pour les tests : il prend {@code ans} années d'un coup (au prochain {@link #veiller}). */
    static void vieillir(Travailleur t, double ans) {
        if (t.ageLe == 0) t.ageLe = t.level().getGameTime();
        double jours = 0, a = t.age;
        // Le temps du jeu qu'il faut pour gagner ces années, à son pas (enfant puis adulte).
        double fin = a + ans;
        if (a < Ages.ADULTE) { double e = Math.min(fin, Ages.ADULTE) - a; jours += e * reglage().jours_par_an_enfant; a += e; }
        if (fin > a) jours += (fin - a) * reglage().jours_par_an_adulte;
        t.ageLe -= (long) Math.ceil(jours * Ages.JOUR);
    }
}

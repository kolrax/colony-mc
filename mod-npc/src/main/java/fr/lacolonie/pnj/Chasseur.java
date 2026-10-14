package fr.lacolonie.pnj;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Le chasseur (lot 6, cahier 4.2). Le jour, il part chasser le gibier sauvage autour du poste ({@link Chasse#ZONE} blocs) :
 * vaches, moutons, cochons, poules et lapins qui ne sont à personne. Il tire à l'arc (ou à l'arbalète) avec les flèches
 * marquées du guerrier ({@link Guerrier#tirer} : elles ne blessent que la bête visée), sinon il frappe à l'épée ou à la
 * hache ; arc, flèches et lame sont pris au dépôt (cahier 3.12), et sans rien de tout ça il le dit par lettre et attend.
 * Le butin de la bête va droit dans son sac ({@link Eleveur#butin}) ; il ramasse ses flèches ; au bout de
 * {@link Chasse#PAR_TOUR} bêtes, ou le sac presque plein, il rentre tout mettre au dépôt et souffle un peu. La nuit, il rentre.
 * <p>
 * Jamais : une bête d'enclos ({@link Elevage#clore}), une bête qui a un nom, une laisse, une selle ou un maître, un petit,
 * une bête chez un voisin ({@link Fermier#aLui}), ni la dernière de son espèce aux alentours ({@link Chasse#GARDER}). Il
 * ne frappe ni joueur, ni ouvrier, ni villageois.
 * <p>
 * Pas de lag : le gibier n'est cherché qu'au moment de choisir une proie (une boîte autour du poste, 64 bêtes au plus) ; un
 * pré relevé sert une minute, et au plus {@link #RELEVES} relevés d'enclos par recherche.
 */
final class Chasseur extends Metier {
    static final Actions.Filtre PRODUITS = Actions.Filtre.de("minecraft:beef|minecraft:porkchop|minecraft:mutton|minecraft:chicken|minecraft:rabbit"
            + "|minecraft:leather|minecraft:rabbit_hide|minecraft:rabbit_foot|minecraft:feather|#minecraft:wool");
    static final int BETES_MAX = 64, RELEVES = 6, REVUE = 1200;
    /** Le repos après un tour de chasse, et après un tour bredouille (en secondes). */
    static final int PAUSE = 60, BREDOUILLE = 30;

    Chasseur() {
        super("chasseur", "Chasseur", "Chasse le gibier sauvage autour du poste (vaches, moutons, cochons, poules, lapins), à l'arc ou à l'épée, et "
                + "rapporte viande, cuir et plumes au dépôt. Il ne touche jamais aux bêtes des enclos, ni à celles qui ont un nom ou un maître, "
                + "ni aux petits, et en laisse toujours assez pour que le gibier revienne.", false,
                "minecraft:bow|minecraft:crossbow", "#minecraft:arrows", "#minecraft:swords|#minecraft:axes");
        commandes.add(new Commande("viande", "Viande crue", "minecraft:beef|minecraft:porkchop|minecraft:mutton|minecraft:chicken|minecraft:rabbit"));
        commandes.add(new Commande("cuir", "Cuir et peaux", "minecraft:leather|minecraft:rabbit_hide"));
        commandes.add(new Commande("plumes", "Plumes", "minecraft:feather"));
    }

    @Override
    void travailler(Travailleur t) {
        if (t.bureau == null) { Cerveau.attendre(t, 5); return; }
        long maintenant = Cerveau.temps(t);
        boolean nuit = !t.level().isDay(), repos = t.memoire.getLong("chasse_repos") > maintenant;
        // Le butin au dépôt : le sac presque plein, entre deux tours, ou le soir venu.
        if (t.compter(PRODUITS) > 0 && (Monde.libres(t) < 3 || nuit || repos)) {
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                Cerveau.attendre(t, 30);
                return;
            }
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }
        // La nuit, la chasse est au guerrier : lui rentre au poste.
        if (nuit || repos) {
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 8 * 8) Cerveau.rentrer(t);
            else Cerveau.attendre(t, 5);
            return;
        }
        if (equiper(t)) return;
        if (!arme(t)) {
            manque(t);
            Cerveau.attendre(t, 10);
            return;
        }
        Commande c = commande(t);
        Cerveau.lancer(t, new Battue(Chasse.pour(c == null ? null : c.id), Chasse.PAR_TOUR, false));
    }

    // --- Ses armes ----------------------------------------------------------------------------------------------

    /** De quoi chasser : un arc (ou une arbalète) et des flèches, ou une épée, ou une hache. */
    static boolean arme(Travailleur t) { return Guerrier.peutTirer(t) || meilleure(t, Eleveur.ARMES::objet) > 0; }

    /** Sans rien pour chasser, il l'écrit à son chef (une fois par jour) : des flèches s'il a un arc, sinon un arc. */
    static void manque(Travailleur t) {
        Cerveau.ecrire(t, "arc", Cerveau.UN_JOUR, t.compter(Guerrier.TIR) > 0 ? "fleches" : "arc");
    }

    /** La note d'un arc au dépôt : l'arbalète d'abord. */
    static double arc(ItemStack s) { return s.is(Items.CROSSBOW) ? 2 : s.is(Items.BOW) ? 1 : 0; }

    /** Ce que le dépôt a pour lui (sans rien prendre) : un arc s'il n'en a pas, des flèches s'il en manque, une lame s'il n'a rien. */
    static boolean aPrendre(Travailleur t, Bureau.Entite b) {
        boolean arc = t.compter(Guerrier.TIR) > 0 || !Guerrier.auDepot(b, Chasseur::arc, 0, false).isEmpty();
        if (t.compter(Guerrier.TIR) == 0 && arc) return true;
        if (arc && t.compter(Guerrier.FLECHES) < 16 && b.compter(Guerrier.FLECHES) > 0) return true;
        return !arc && meilleure(t, Eleveur.ARMES::objet) == 0 && b.compter(Eleveur.ARMES) > 0;
    }

    /**
     * Il lui manque de quoi chasser, ou il n'a pas d'arc, ou peu de flèches : il regarde au dépôt (au plus toutes les 10 s
     * sans arme, toutes les minutes sinon, et seulement près du poste s'il est armé) et part s'y servir ({@link Armes}).
     */
    static boolean equiper(Travailleur t) {
        long maintenant = Cerveau.temps(t);
        boolean arme = arme(t);
        if (t.memoire.getLong("arme_vue") > maintenant || arme && t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 16 * 16) return false;
        if (arme && Guerrier.peutTirer(t) && t.compter(Guerrier.FLECHES) >= 16) return false;
        t.memoire.putLong("arme_vue", maintenant + (arme ? 60 : 10));
        Bureau.Entite b = Bureau.entite(t);
        if (b == null || !aPrendre(t, b)) return false;
        Cerveau.lancer(t, new Armes());
        return true;
    }

    /** Au poste : un arc (l'arbalète d'abord) s'il n'en a pas, des flèches jusqu'à 32, et une lame s'il n'a toujours pas de quoi chasser. */
    static final class Armes extends Actions.AuBureau {
        Armes() { super("s_equiper"); }

        Actions.Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            boolean pris = false;
            if (t.compter(Guerrier.TIR) == 0) {
                ItemStack a = Guerrier.auDepot(b, Chasseur::arc, 0, true);
                if (!a.isEmpty()) { Guerrier.garder(t, b, a); pris = true; }
            }
            int fleches = t.compter(Guerrier.FLECHES);
            if (t.compter(Guerrier.TIR) > 0 && fleches < 32) for (ItemStack s : b.prendre(Guerrier.FLECHES, 32 - fleches)) { Guerrier.garder(t, b, s); pris = true; }
            if (!Guerrier.peutTirer(t) && meilleure(t, Eleveur.ARMES::objet) == 0) {
                ItemStack l = Guerrier.auDepot(b, s -> Eleveur.ARMES.objet(s) ? Guerrier.degats(s) : 0, 0, true);
                if (!l.isEmpty()) { Guerrier.garder(t, b, l); pris = true; }
            }
            if (!pris) return echec("rien_au_depot");
            t.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            Cerveau.dire(t, "armes", 300, Guerrier.peutTirer(t) ? "Mon arc et mes flèches : me voilà paré pour la chasse." : "Faute d'arc, je prends une lame. Il faudra s'approcher.");
            return Actions.Etat.OK;
        }
    }

    // --- Le gibier ----------------------------------------------------------------------------------------------

    /** Ce qu'il sait des prés et des enclos autour du poste (pas sauvegardé) : revu toutes les minutes. */
    static final class Pres {
        /** Les cases d'un pré ouvert, avec leur hauteur (à 4 blocs près). */
        final Map<Long, Integer> ouvertes = new HashMap<>();
        final List<Elevage.Enclos> enclos = new ArrayList<>();
        /** Les bêtes dont on ne sait pas si elles sont dans un enclos (un grand pré clos, un bord pas chargé) : ni tirées ni comptées. */
        final Set<Integer> douteuses = new HashSet<>();
        long jusqua;
        /** À la dernière recherche : il y avait du gibier, mais il le laisse pour qu'il revienne ({@link Chasse#laisse}). */
        boolean garde;
    }

    /** Ce point est dans un pré ouvert déjà relevé, à 4 blocs près en hauteur. */
    static boolean ouverte(Pres p, int x, int y, int z) {
        Integer h = p.ouvertes.get(Elevage.cle(x, z));
        return h != null && Math.abs(h - y) <= 4;
    }

    /**
     * La bête en (x, y, z) est-elle sauvage ? Vrai seulement si elle peut aller loin ({@link Elevage#clore} renvoie null) sans
     * que le parcours touche une barrière, un muret, un portillon ou un bord pas chargé : sinon, on ne sait pas (null), et un
     * enclos trouvé est retenu (faux). Seules les cases d'un vrai pré ouvert vont dans {@code p.ouvertes}.
     */
    static Boolean sauvage(ServerLevel l, Pres p, int x, int y, int z) {
        boolean[] doute = {false};
        Elevage.Terrain terrain = (i, j, k) -> {
            if (!l.hasChunkAt(i, k)) { doute[0] = true; return Elevage.CLOTURE; }
            net.minecraft.core.BlockPos q = new net.minecraft.core.BlockPos(i, j, k);
            if (Eleveur.cloture(l.getBlockState(q)) || Eleveur.cloture(l.getBlockState(q.below())) || Eleveur.cloture(l.getBlockState(q.above()))) doute[0] = true;
            return Eleveur.pas(l, i, j, k);
        };
        // Les cases déjà connues pour ouvertes (à cette hauteur) arrêtent le parcours ; les nouvelles restent à part tant qu'on doute.
        Set<Long> vues = new HashSet<>() {
            @Override
            public boolean contains(Object o) { return super.contains(o) || o instanceof Long c && p.ouvertes.containsKey(c) && Math.abs(p.ouvertes.get(c) - y) <= 4; }
        };
        Elevage.Enclos e = Elevage.clore(x, y, z, terrain, Elevage.CASES_MAX, vues);
        if (e != null) { p.enclos.add(e); return Boolean.FALSE; }
        if (doute[0]) return null;
        for (Long c : vues) p.ouvertes.putIfAbsent(c, y);
        return Boolean.TRUE;
    }

    static final Map<Travailleur, Pres> PRES = new WeakHashMap<>();

    static Chasse.Espece espece(Animal a) { return Chasse.parType(BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString()); }

    /**
     * Les proies qu'il peut tirer maintenant, la plus proche d'abord ({@link Chasse#proies}) : des espèces dites (null :
     * toutes), {@code voulu} au plus. Une bête dont on ne sait pas encore si elle est dans un enclos n'est ni tirée ni comptée.
     */
    static List<Animal> gibier(Travailleur t, Set<String> especes, int voulu) {
        ServerLevel l = (ServerLevel) t.level();
        Pres p = PRES.computeIfAbsent(t, k -> new Pres());
        long maintenant = l.getGameTime();
        if (p.jusqua <= maintenant) { p.ouvertes.clear(); p.enclos.clear(); p.douteuses.clear(); p.jusqua = maintenant + REVUE; }
        List<Animal> betes = new ArrayList<>();
        int r = Chasse.ZONE;
        double bx = t.bureau.getX() + 0.5, bz = t.bureau.getZ() + 0.5;
        l.getEntities(EntityTypeTest.forClass(Animal.class), new AABB(t.bureau).inflate(r, 16, r),
                a -> a.isAlive() && espece(a) != null && Math.pow(a.getX() - bx, 2) + Math.pow(a.getZ() - bz, 2) <= r * r, betes, BETES_MAX);
        betes.sort(Comparator.comparingDouble(a -> a.distanceToSqr(t)));
        long tard = maintenant;
        t.inaccessibles.values().removeIf(v -> v < tard);
        int releves = 0;
        List<Chasse.Bete> vues = new ArrayList<>();
        Map<Integer, Animal> parId = new HashMap<>();
        for (Animal a : betes) {
            int x = a.getBlockX(), y = a.getBlockY(), z = a.getBlockZ();
            // L'enclos d'abord (une étable sous un pré), puis le pré ouvert ; une bête douteuse n'est relevée qu'une fois par minute.
            Boolean sauvage = Eleveur.enclos(p.enclos, x, y, z) != null ? Boolean.FALSE : ouverte(p, x, y, z) ? Boolean.TRUE : null;
            if (sauvage == null && releves < RELEVES && !p.douteuses.contains(a.getId())) {
                releves++;
                sauvage = sauvage(l, p, x, y, z);
                if (sauvage == null) p.douteuses.add(a.getId());
            }
            if (sauvage == null) continue; // pas relevé, ou douteux : ni tiré, ni compté
            boolean intouchable = Eleveur.intouchable(a) || t.inaccessibles.containsKey(a.getId()) || !Fermier.aLui(t, a.blockPosition());
            vues.add(new Chasse.Bete(a.getId(), espece(a).id(), !a.isBaby(), sauvage, intouchable, a.distanceToSqr(t)));
            parId.put(a.getId(), a);
        }
        p.garde = Chasse.laisse(vues, especes);
        return Chasse.proies(vues, especes, voulu).stream().map(parId::get).toList();
    }

    // --- La chasse ----------------------------------------------------------------------------------------------

    /**
     * Un tour de chasse : {@code voulu} bêtes au plus, des espèces dites (null : toutes), une proie après l'autre ; puis le
     * butin au dépôt et une pause. Sur ordre ({@code ordre}), il s'équipe d'abord au dépôt, et dit ce qu'il a fait.
     */
    static final class Battue extends Actions.Action {
        final Set<String> especes;
        final int voulu;
        final boolean ordre;
        Actions.Action sous;
        int etape, tues, ratees;
        boolean sansArme, garde, lasse;
        /** Au plus tant de traques ratées, et tant de temps (en ticks) par tour. */
        static final int RATEES = 3, DUREE = 20 * 180;
        final Map<String, Integer> par = new LinkedHashMap<>();

        Battue(Set<String> especes, int voulu, boolean ordre) {
            super("chasse");
            this.especes = especes;
            this.voulu = voulu;
            this.ordre = ordre;
            mobile = true;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t);
            sous = null;
        }

        Actions.Etat tick(Travailleur t) {
            if (sous != null) {
                Actions.Etat r = sous.tick(t);
                sous.age++;
                if (r == Actions.Etat.EN_COURS) return r;
                sous.fin(t);
                if (sous instanceof Traque q) {
                    if (q.tue) { tues++; par.merge(q.espece.id(), 1, Integer::sum); }
                    else ratees++;
                }
                sous = null;
            }
            switch (etape) {
                case 0 -> {
                    etape = 1;
                    Bureau.Entite b = Bureau.entite(t);
                    if (ordre && b != null && aPrendre(t, b)) sous = new Armes();
                }
                case 1 -> {
                    if (!arme(t)) { sansArme = true; etape = 2; return Actions.Etat.EN_COURS; }
                    if (tues >= voulu || Monde.libres(t) < 3 || !t.level().isDay() && !ordre) { etape = 2; return Actions.Etat.EN_COURS; }
                    // Trop de bêtes manquées, ou trop longtemps : il rentre (il reprendra au tour suivant).
                    if (ratees >= RATEES || age > DUREE) { lasse = true; etape = 2; return Actions.Etat.EN_COURS; }
                    List<Animal> p = gibier(t, especes, 1);
                    garde = PRES.get(t).garde;
                    // Seul, pour une commande de cuir ou de plumes : faute de la bête qui en donne, une autre (la viande sert toujours).
                    if (p.isEmpty() && !ordre && especes != null) p = gibier(t, null, 1);
                    if (p.isEmpty()) { etape = 2; return Actions.Etat.EN_COURS; }
                    sous = new Traque(p.get(0));
                }
                case 2 -> {
                    etape = 3;
                    Metier m = Metier.de(t.metier);
                    if (t.compter(PRODUITS) > 0) sous = new Actions.Deposer(m == null ? List.of() : m.garder);
                }
                default -> {
                    t.memoire.putLong("chasse_repos", Cerveau.temps(t) + (tues > 0 ? PAUSE : BREDOUILLE));
                    if (ordre) Voix.dire(t, bilan(), "calme");
                    else if (sansArme) manque(t);
                    else if (tues == 0) Cerveau.ecrire(t, "gibier", Cerveau.UN_JOUR, "sans_gibier");
                    else Besoins.parler(t, "chasse_retour", null);
                    travail = tues > 0;
                    return tues > 0 ? Actions.Etat.OK : echec(sansArme ? "sans_arme" : "bredouille");
                }
            }
            return Actions.Etat.EN_COURS;
        }

        String bilan() {
            if (tues == 0) {
                if (sansArme) return "Il me faut un arc et des flèches, chef, ou au moins une lame : il n'y en a pas au dépôt.";
                String quoi = especes != null && especes.size() == 1 ? Chasse.parId(especes.iterator().next()).noms() : "gibier";
                if (lasse) return "Le gibier m'a filé entre les doigts, chef. Je retenterai ma chance.";
                if (garde) return "Il ne reste que quelques " + (quoi.equals("gibier") ? "bêtes" : quoi) + " par ici, chef : je les laisse, pour que le gibier revienne.";
                return "Je ne trouve pas de " + quoi + " à prendre par ici, chef. Je ne touche ni aux bêtes des enclos, ni à celles qui ont un maître, ni aux petits.";
            }
            List<String> l = new ArrayList<>();
            par.forEach((k, v) -> { Chasse.Espece e = Chasse.parId(k); l.add(v + " " + (v > 1 ? e.noms() : e.nom())); });
            String fait = "Belle chasse, chef : " + String.join(" et ", l) + ". Le butin est au dépôt.";
            if (tues >= voulu) return fait;
            if (garde) return fait + " Je n'en ai pas pris plus : il faut en laisser pour que le gibier revienne.";
            return lasse ? fait + " Le reste m'a filé entre les doigts." : fait;
        }
    }

    /**
     * Une proie : il la tire à l'arc de loin, ou la frappe de près ({@link Actions.Attaquer}) ; puis il ramasse ses flèches
     * plantées alentour (10 s au plus). La bête change de main entre-temps (un nom, une laisse) : il la laisse.
     */
    static final class Traque extends Actions.Action {
        final Animal cible;
        final Chasse.Espece espece;
        Actions.Attaquer attaque;
        AbstractArrow fleche;
        boolean tue, fini;
        String rate = "";
        int ramasse;

        Traque(Animal cible) { super("traque"); this.cible = cible; this.espece = espece(cible); mobile = true; }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (attaque != null) attaque.fin(t);
        }

        Actions.Etat tick(Travailleur t) {
            if (!fini) {
                if (attaque == null) {
                    if (!Guerrier.peutTirer(t)) Eleveur.armer(t);
                    attaque = new Actions.Attaquer(cible).portee(Chasse.ZONE + 16);
                    Besoins.parler(t, "chasse_piste", null);
                }
                // Chaque seconde : la bête a changé de main (un nom, une laisse), ou elle est passée chez un voisin ; il la laisse.
                if (age % 20 == 0 && cible.isAlive() && (Eleveur.intouchable(cible) || cible.isBaby() || !Fermier.aLui(t, cible.blockPosition()))) {
                    attaque.fin(t);
                    laisser(t, "intouchable");
                    return Actions.Etat.EN_COURS;
                }
                Actions.Etat r = attaque.tick(t);
                attaque.age++;
                if (r == Actions.Etat.EN_COURS) return r;
                attaque.fin(t);
                fini = true;
                // Prise seulement s'il l'a tuée lui-même (pas une bête tuée par un autre, ni partie avec un chunk déchargé).
                var parti = cible.getRemovalReason();
                tue = !cible.isAlive() && (parti == null || parti == net.minecraft.world.entity.Entity.RemovalReason.KILLED) && cible.getLastHurtByMob() == t;
                if (cible.isAlive()) { Besoins.parler(t, "chasse_rate", null); laisser(t, r == Actions.Etat.ECHEC ? attaque.raison : "echappe"); }
                else if (!tue) rate = "pas_lui";
            }
            // Ses flèches plantées alentour, la bête prise ou manquée : il les ramasse (une par une, la plus proche d'abord).
            if (++ramasse > 20 * 10) return issue();
            if (fleche == null || !fleche.isAlive() || ramasse % 20 == 1) {
                fleche = t.level().getEntitiesOfClass(AbstractArrow.class, t.getBoundingBox().inflate(16, 6, 16), a -> a.isAlive() && a.tickCount > 20
                                && a.pickup == AbstractArrow.Pickup.ALLOWED && a.getPersistentData().hasUUID(Guerrier.TIREUR)
                                && a.getPersistentData().getUUID(Guerrier.TIREUR).equals(t.getUUID()))
                        .stream().min(Comparator.comparingDouble(a -> a.distanceToSqr(t))).orElse(null);
                if (fleche == null) return issue();
            }
            if (t.distanceToSqr(fleche) < 2 * 2) {
                t.take(fleche, 1);
                t.ranger(fleche.getPickupItemStackOrigin().copy());
                fleche.discard();
                fleche = null;
                return Actions.Etat.EN_COURS;
            }
            if (coince()) return issue();
            marcher(t, fleche.position(), 1.0);
            return Actions.Etat.EN_COURS;
        }

        Actions.Etat issue() { return tue ? Actions.Etat.OK : echec(rate.isEmpty() ? "echappe" : rate); }

        /** Il la laisse une minute (elle a fui, ou n'est plus du gibier), puis ramasse ses flèches. */
        void laisser(Travailleur t, String r) {
            t.inaccessibles.put(cible.getId(), t.level().getGameTime() + 1200);
            fini = true;
            rate = r.isEmpty() ? "echappe" : r;
        }
    }

    // --- Sur ordre ----------------------------------------------------------------------------------------------

    /** « Va chasser », « chasse deux lapins » : un tour de chasse, l'espèce dite ({@code c.texte}) et combien ({@code c.nombre}). */
    static Actions.Action consigne(Travailleur t, Consignes.Consigne c) {
        Set<String> especes = c.texte.isEmpty() ? null : Set.of(c.texte);
        return new Battue(especes, Math.max(1, c.nombre), true);
    }
}

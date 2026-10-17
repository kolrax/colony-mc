package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * L'éleveur (lot 6). Il s'occupe des bêtes de ferme (vaches, moutons, cochons, poules) qui vivent dans les enclos de son
 * espace de vie : une zone close de barrières ou de murs, avec un portillon ({@link Elevage#clore}). Enclos par enclos,
 * il entre par le portillon (qu'il referme derrière lui, en entrant comme en sortant), fait ce qu'il y a à faire, ramasse ce
 * qui traîne (les œufs), ressort, et met tout au dépôt :
 * <ul>
 * <li>il nourrit les adultes par couples pour qu'ils aient des petits, tant que l'espèce compte moins de bêtes que le plafond
 * ({@code Reglages.betes_par_enclos}, 12) ; le fourrage vient du dépôt (blé, carottes, graines) ;</li>
 * <li>il tond les moutons (cisailles prises au dépôt, {@link Metier#outilAuDepot}) et trait une vache avec un seau vide du
 * dépôt (s'il y a moins de 3 seaux de lait au dépôt, ou si on lui en commande) ;</li>
 * <li>il abat l'excédent d'adultes au-delà du plafond ; sur ordre (« abats deux vaches »), jusqu'au dernier couple.</li>
 * </ul>
 * Jamais : un petit, le dernier couple, une bête qui a un nom, sellée ou tenue en laisse, une bête hors de son espace de
 * vie, hors d'un enclos ou chez un voisin (plus près du poste d'un autre chef, {@link #aLui}). Il ne frappe ni un joueur, ni un villageois, ni une bête
 * apprivoisée : seulement ces quatre espèces, dans ses enclos (l'élevage, permis par les règles du serveur). Le butin va
 * droit dans son sac ({@link #butin}) : rien ne traîne par terre.
 * <p>
 * Pas de lag : les bêtes de l'espace de vie sont cherchées au plus toutes les 2 s (256 au plus) ; chaque enclos est relevé
 * au plus une fois par minute (1024 cases au plus, 4 relevés par recherche).
 */
final class Eleveur extends Metier {
    static final Actions.Filtre CISAILLES = Actions.Filtre.de("minecraft:shears");
    static final Actions.Filtre SEAU_VIDE = Actions.Filtre.de("minecraft:bucket");
    static final Actions.Filtre LAIT = Actions.Filtre.de("minecraft:milk_bucket");
    static final Actions.Filtre ARMES = Actions.Filtre.de("#minecraft:swords|#minecraft:axes");
    /** Ce que donnent les bêtes : tout va au dépôt. */
    static final Actions.Filtre PRODUITS = Actions.Filtre.de("minecraft:leather|#minecraft:wool|minecraft:beef|minecraft:porkchop|minecraft:mutton"
            + "|minecraft:chicken|minecraft:egg|minecraft:feather|minecraft:milk_bucket");
    /** Il trait tant qu'il y a moins de tant de seaux de lait au dépôt (sauf commande de lait). */
    static final int LAIT_EN_RESERVE = 3;
    /** Les bêtes cherchées au plus toutes les 2 s (en ticks), 256 au plus ; un enclos revu au plus une fois par minute. */
    static final int RELEVE = 40, REVUE = 1200, BETES_MAX = 256, RELEVES_PAR_RECHERCHE = 4;

    Eleveur() {
        super("eleveur", "Éleveur", "Soigne les bêtes des enclos autour du poste : nourrit les vaches, les moutons, les cochons et les poules pour "
                + "qu'ils aient des petits, tond les moutons, trait les vaches, ramasse les œufs, abat les bêtes en trop (jamais un petit, ni le "
                + "dernier couple, ni une bête qui a un nom) et met tout au dépôt.", false,
                "minecraft:shears", "minecraft:bucket", "#minecraft:cow_food", "#minecraft:pig_food", "#minecraft:chicken_food");
        commandes.add(new Commande("cuir", "Cuir", "minecraft:leather"));
        commandes.add(new Commande("laine", "Laine", "#minecraft:wool"));
        commandes.add(new Commande("viande", "Viande crue", "minecraft:beef|minecraft:porkchop|minecraft:mutton|minecraft:chicken"));
        commandes.add(new Commande("oeufs", "Œufs", "minecraft:egg"));
        commandes.add(new Commande("lait", "Lait", "minecraft:milk_bucket"));
    }

    @Override
    void travailler(Travailleur t) {
        // Resté dans un enclos (son travail a été interrompu) : il en sort d'abord, par le portillon.
        if (sortir(t)) return;
        Ferme f = releve(t, false);

        // Ce qu'il a tiré des bêtes va au dépôt (rien ne s'entasse dans son sac).
        if (t.compter(PRODUITS) > 0 || Monde.libres(t) < 3) {
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                t.bloquer(Statut.SAC_PLEIN);
                Cerveau.attendre(t, 30);
                return;
            }
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }

        Map<Elevage.Enclos, List<Animal>> enclos = parEnclos(f);
        if (enclos.isEmpty()) {
            t.bloquer(Statut.ENCLOS); // « ⚠ Pas d'enclos » au-dessus de sa tête (refonte, partie 2)
            Cerveau.ecrire(t, "enclos", Cerveau.UN_JOUR, "sans_enclos");
            Cerveau.attendre(t, 5);
            return;
        }
        t.debloquer(Statut.ENCLOS);
        // Sa mission a un lieu (ou « Travaille ici ») : l'enclos le plus proche de ce lieu, et lui seul.
        BlockPos lieu = t.lieuTravail();
        if (lieu != null && enclos.size() > 1) {
            Elevage.Enclos pres = java.util.Collections.min(enclos.keySet(), java.util.Comparator.comparingDouble(e -> carre(e, lieu)));
            enclos = Map.of(pres, enclos.get(pres));
        }
        // Ses cisailles, prises au dépôt, s'il y a des moutons à tondre.
        boolean laine = enclos.values().stream().anyMatch(l -> l.stream().anyMatch(a -> a instanceof Sheep s && s.readyForShearing()));
        if (laine && outilAuDepot(t, CISAILLES, CISAILLES, "paire de cisailles")) return;

        // Un enclos après l'autre (chacun son tour) : le premier où il y a quelque chose à faire.
        List<Elevage.Enclos> liste = new ArrayList<>(enclos.keySet());
        // Un autre éleveur fait déjà sa corvée dans un enclos : il en prend un autre, s'il y en a (ils ne se gênent pas).
        if (liste.size() > 1) {
            List<Elevage.Enclos> pris = ((ServerLevel) t.level()).getEntitiesOfClass(Travailleur.class, new AABB(t.bureau).inflate(Travailleur.ESPACE_DE_VIE, 16, Travailleur.ESPACE_DE_VIE),
                    o -> o != t && o.action instanceof Corvee).stream().map(o -> ((Corvee) o.action).enclos).toList();
            List<Elevage.Enclos> libres = liste.stream().filter(e -> pris.stream().noneMatch(x -> meme(x, e))).toList();
            if (!libres.isEmpty()) liste = new ArrayList<>(libres);
        }
        for (int i = 0; i < liste.size(); i++) {
            Elevage.Enclos e = liste.get((f.suivant + i) % liste.size());
            Corvee c = plan(t, e, enclos.get(e), "tout", null, null);
            if (c != null) {
                f.suivant = (f.suivant + i + 1) % liste.size();
                Cerveau.lancer(t, c);
                return;
            }
        }
        Cerveau.attendre(t, 2);
    }

    /**
     * Il est dans un enclos (pas sa cour) sans y travailler : il en sort par le portillon. L'enclos d'une corvée interrompue
     * est retenu ({@code Ferme.aSortir}) même si le relevé l'a oublié entre-temps.
     */
    @Override
    boolean sortir(Travailleur t) {
        Ferme f = FERMES.get(t);
        if (f == null || t.bureau == null) return false;
        Elevage.Enclos ici = f.aSortir != null && dans(f.aSortir, t) ? f.aSortir : null;
        for (Elevage.Enclos e : f.enclos) if (ici == null && dans(e, t)) ici = e;
        if (ici == null) { f.aSortir = null; return false; }
        if (ici.contient(t.bureau.getX(), t.bureau.getZ())) return false;
        Passage p = passage(t, ici);
        if (p == null) return false;
        Cerveau.lancer(t, new Corvee(ici, p, new ArrayList<>(), t));
        return true;
    }

    // --- Les bêtes et leurs enclos ------------------------------------------------------------------------------

    /** Ce qu'il sait des enclos de son espace de vie (pas sauvegardé : il les relève de nouveau après un redémarrage). */
    static final class Ferme {
        final List<Elevage.Enclos> enclos = new ArrayList<>();
        final List<Long> jusqua = new ArrayList<>();
        final Set<Long> ouvertes = new HashSet<>();
        long ouvertesJusqua, releve = -RELEVE;
        List<Animal> betes = new ArrayList<>();
        int suivant;
        /** L'enclos d'une corvée interrompue, d'où il doit encore sortir. */
        Elevage.Enclos aSortir;
    }

    static final Map<Travailleur, Ferme> FERMES = new WeakHashMap<>();

    /** L'espèce d'une bête de ferme (vache, mouton, cochon, poule), ou null pour toute autre (une vache-champignon non plus). */
    static Elevage.Espece espece(Animal a) { return Elevage.parType(BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString()); }

    /**
     * Les bêtes de ferme de son espace de vie, et l'enclos de chacune : cherchées au plus toutes les 2 s ({@code force} :
     * tout de suite, pour un ordre), 256 au plus ; un enclos relevé sert une minute, 4 relevés au plus par recherche.
     */
    static Ferme releve(Travailleur t, boolean force) {
        Ferme f = FERMES.computeIfAbsent(t, k -> new Ferme());
        if (t.bureau == null) return f;
        ServerLevel l = (ServerLevel) t.level();
        long maintenant = l.getGameTime();
        if (!force && maintenant - f.releve < RELEVE) { f.betes.removeIf(a -> !a.isAlive()); return f; }
        f.releve = maintenant;
        for (int i = f.enclos.size() - 1; i >= 0; i--) if (f.jusqua.get(i) <= maintenant) { f.enclos.remove(i); f.jusqua.remove(i); }
        if (f.ouvertesJusqua <= maintenant) { f.ouvertes.clear(); f.ouvertesJusqua = maintenant + REVUE; }
        List<Animal> betes = new ArrayList<>();
        int r = Travailleur.ESPACE_DE_VIE;
        l.getEntities(EntityTypeTest.forClass(Animal.class), new AABB(t.bureau).inflate(r, 12, r),
                a -> a.isAlive() && espece(a) != null && t.chezLui(a.blockPosition()), betes, BETES_MAX);
        int releves = 0;
        for (Animal a : betes) {
            if (releves >= RELEVES_PAR_RECHERCHE || enclos(f, a) != null
                    || f.ouvertes.contains(Elevage.cle(a.getBlockX(), a.getBlockZ()))) continue;
            releves++;
            Elevage.Enclos e = Elevage.clore(a.getBlockX(), a.getBlockY(), a.getBlockZ(), (x, y, z) -> pas(l, x, y, z), Elevage.CASES_MAX, f.ouvertes);
            if (e != null) { f.enclos.add(e); f.jusqua.add(maintenant + REVUE); }
        }
        f.betes = betes;
        return f;
    }

    /** La distance (au carré) du milieu de l'enclos à ce point, à l'horizontale. */
    static double carre(Elevage.Enclos e, BlockPos p) {
        double dx = (e.minX() + e.maxX()) / 2.0 - p.getX(), dz = (e.minZ() + e.maxZ()) / 2.0 - p.getZ();
        return dx * dx + dz * dz;
    }

    /** Le même enclos, relevé par deux éleveurs. */
    static boolean meme(Elevage.Enclos a, Elevage.Enclos b) {
        return a.minX() == b.minX() && a.minZ() == b.minZ() && a.maxX() == b.maxX() && a.maxZ() == b.maxZ() && Math.abs(a.y() - b.y()) <= 4;
    }

    /** L'enclos connu qui contient ce point (à 4 blocs près en hauteur), ou null. */
    static Elevage.Enclos enclos(Ferme f, int x, int y, int z) { return enclos(f.enclos, x, y, z); }

    /**
     * L'enclos connu où est cette bête, ou null : la case sous elle, ou sinon une case sous son corps (un petit serré dans
     * le coin de deux barrières a le milieu au-dessus du poteau, mais il est bien dedans).
     */
    static Elevage.Enclos enclos(Ferme f, Entity a) {
        Elevage.Enclos e = enclos(f, a.getBlockX(), a.getBlockY(), a.getBlockZ());
        if (e != null) return e;
        for (Elevage.Enclos x : f.enclos) if (dans(x, a)) return x;
        return null;
    }

    static Elevage.Enclos enclos(List<Elevage.Enclos> liste, int x, int y, int z) {
        for (Elevage.Enclos e : liste) if (e.contient(x, z) && Math.abs(y - e.y()) <= 4) return e;
        return null;
    }

    /**
     * Il est dans l'enclos, ne serait-ce qu'un pied : un coin de son corps au-dessus d'une case de l'enclos (arrêté contre le
     * portillon, il est dans la colonne du portillon, mais encore du côté de l'enclos). Une bête aussi : serrée dans un coin,
     * son milieu peut être au-dessus d'un poteau ; dehors, la clôture l'écarte toujours des cases de l'enclos.
     */
    static boolean dans(Elevage.Enclos e, Entity t) {
        if (Math.abs(t.getBlockY() - e.y()) > 4) return false;
        AABB b = t.getBoundingBox();
        for (double x : new double[]{b.minX, b.maxX}) for (double z : new double[]{b.minZ, b.maxZ})
            if (e.contient(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z))) return true;
        return false;
    }

    /** Les bêtes de chaque enclos (les bêtes au pré, hors enclos, ne sont pas à lui). */
    static Map<Elevage.Enclos, List<Animal>> parEnclos(Ferme f) {
        Map<Elevage.Enclos, List<Animal>> r = new java.util.IdentityHashMap<>();
        for (Animal a : f.betes) {
            if (!a.isAlive()) continue;
            Elevage.Enclos e = enclos(f, a);
            if (e != null) r.computeIfAbsent(e, k -> new ArrayList<>()).add(a);
        }
        return r;
    }

    /** Une barrière, un muret ou un portillon : une bête ne passe pas par-dessus. */
    static boolean cloture(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS) || s.getBlock() instanceof FenceGateBlock;
    }

    static boolean libre(ServerLevel l, BlockPos p) { return l.getBlockState(p).getCollisionShape(l, p).isEmpty(); }

    /** Le monde vu par {@link Elevage#clore} : où une bête met les pieds en (x, z) en venant de la hauteur y. */
    static int pas(ServerLevel l, int x, int y, int z) {
        if (!l.hasChunkAt(x, z)) return y; // pas chargé : on ne sait pas, comme ouvert
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = l.getBlockState(p);
        if (s.getBlock() instanceof FenceGateBlock) return Elevage.PORTILLON;
        if (cloture(s) || cloture(l.getBlockState(p.below()))) return Elevage.CLOTURE;
        if (!libre(l, p)) {
            // Un bloc à hauteur des pieds : une marche, si la place est libre au-dessus ; sinon une paroi.
            BlockPos q = p.above();
            return !cloture(l.getBlockState(q)) && libre(l, q) && libre(l, q.above()) ? y + 1 : Elevage.CLOTURE;
        }
        for (int d = 1; d <= 4; d++) if (!libre(l, p.below(d))) return y - d + 1;
        return y - 4; // une chute : la bête peut partir par là
    }

    // --- Le portillon -------------------------------------------------------------------------------------------

    /** Un portillon d'enclos : la case devant, dehors, et la case derrière, dedans (à hauteur des pieds). */
    record Passage(BlockPos porte, BlockPos dehors, BlockPos dedans) {}

    /** Le portillon de l'enclos le plus proche de lui, avec ses deux cases ; null si l'enclos n'en a pas. */
    static Passage passage(Travailleur t, Elevage.Enclos e) {
        ServerLevel l = (ServerLevel) t.level();
        Passage meilleur = null;
        for (int[] k : e.portillons()) {
            BlockPos g = new BlockPos(k[0], k[1], k[2]);
            BlockState s = l.getBlockState(g);
            if (!(s.getBlock() instanceof FenceGateBlock)) continue;
            Direction d = s.getValue(FenceGateBlock.FACING); // on passe dans ce sens-là
            BlockPos a = g.relative(d), b = g.relative(d.getOpposite());
            BlockPos dedans = e.contient(a.getX(), a.getZ()) ? a : e.contient(b.getX(), b.getZ()) ? b : null;
            if (dedans == null) continue;
            BlockPos dehors = pieds(l, dedans == a ? b : a), pied = pieds(l, dedans);
            if (dehors == null || pied == null || e.contient(dehors.getX(), dehors.getZ())) continue;
            if (meilleur == null || dehors.distSqr(t.blockPosition()) < meilleur.dehors.distSqr(t.blockPosition())) meilleur = new Passage(g, dehors, pied);
        }
        return meilleur;
    }

    /** Où il se tient dans cette colonne, à un bloc près : de la place pour lui (deux blocs) sur un sol ; null sinon. */
    static BlockPos pieds(ServerLevel l, BlockPos p) {
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos q = p.above(dy);
            if (libre(l, q) && libre(l, q.above()) && !libre(l, q.below())) return q;
        }
        return null;
    }

    // --- Ce qu'il y a à faire -----------------------------------------------------------------------------------

    /** Une tâche sur une bête : « nourrir », « tondre », « traire » ou « abattre ». */
    record Tache(String quoi, Animal bete, int garder) {
        Tache(String quoi, Animal bete) { this(quoi, bete, 0); }
    }

    /** Quelqu'un tient à cette bête : un nom, une selle, une laisse, un maître. Il n'y touche pas (ni pour l'abattre). */
    static boolean intouchable(Animal a) {
        return a.hasCustomName() || a.isLeashed() || a instanceof Pig p && p.isSaddled() || a instanceof OwnableEntity o && o.getOwnerUUID() != null;
    }

    /** Ce qu'il peut abattre : une bête de ferme adulte de son enclos, à personne, vivante. */
    static boolean abattable(Travailleur t, Animal a, Elevage.Enclos e) {
        return a.isAlive() && !a.isBaby() && espece(a) != null && !intouchable(a) && dans(e, a) && t.chezLui(a.blockPosition())
                && Fermier.aLui(t, a.blockPosition());
    }

    /**
     * Ce qu'il y a à faire dans un enclos, ou null s'il n'y a rien à faire (ou pas de portillon, ou chez un voisin).
     * {@code mode} : « tout » (seul), « nourrir », « tondre », « abattre » (sur ordre ; {@code seule} : l'espèce dite, et
     * {@code voulu[0]} : combien, décompté ici).
     */
    static Corvee plan(Travailleur t, Elevage.Enclos e, List<Animal> betes, String mode, Elevage.Espece seule, int[] voulu) {
        ServerLevel l = (ServerLevel) t.level();
        if (!aLui(t, e)) return null; // pas chez lui, ou chez un voisin
        Passage p = passage(t, e);
        if (p == null && !e.contient(t.bureau.getX(), t.bureau.getZ())) return null; // pas de portillon (sauf sa cour, où il est déjà)
        boolean tout = mode.equals("tout");
        Bureau.Entite b = Bureau.entite(t);
        List<Elevage.Bete> vues = new ArrayList<>();
        Map<Integer, Animal> parId = new java.util.HashMap<>();
        for (Animal a : betes) {
            if (!a.isAlive()) continue;
            boolean adulte = !a.isBaby();
            vues.add(new Elevage.Bete(a.getId(), espece(a).id(), adulte, intouchable(a) || !t.chezLui(a.blockPosition()) || !Fermier.aLui(t, a.blockPosition()),
                    adulte && a.getAge() == 0 && a.canFallInLove()));
            parId.put(a.getId(), a);
        }
        int plafond = Elevage.plafond(Reglages.actuel.betes_par_enclos);
        List<Tache> taches = new ArrayList<>();
        for (Elevage.Espece esp : Elevage.TOUTES) {
            if (seule != null && seule != esp) continue;
            if (tout || mode.equals("abattre")) {
                boolean ordre = mode.equals("abattre") && seule != null;
                List<Integer> ids = Elevage.aAbattre(vues, esp.id(), ordre ? Elevage.COUPLE : plafond, ordre ? voulu[0] : Integer.MAX_VALUE);
                for (int id : ids) if (abattable(t, parId.get(id), e)) { taches.add(new Tache("abattre", parId.get(id), ordre ? Elevage.COUPLE : plafond)); if (ordre) voulu[0]--; }
            }
            if (tout || mode.equals("nourrir")) {
                List<Integer> ids = Elevage.aNourrir(vues, esp.id(), plafond);
                Actions.Filtre fourrage = Actions.Filtre.de(esp.fourrage());
                if (!ids.isEmpty() && tout && t.compter(fourrage) + (b == null ? 0 : b.compter(fourrage)) < 2) {
                    Cerveau.ecrire(t, "fourrage", Cerveau.UN_JOUR, "fourrage", esp.fourrageNom());
                    continue;
                }
                for (int id : ids) taches.add(new Tache("nourrir", parId.get(id)));
            }
        }
        if (tout || mode.equals("tondre")) {
            boolean cisailles = meilleure(t, CISAILLES::objet) > 0 || b != null && b.compter(CISAILLES) > 0;
            for (Animal a : betes) if (a instanceof Sheep s && s.readyForShearing() && (cisailles || !tout)) taches.add(new Tache("tondre", a));
            if (tout && !cisailles && betes.stream().anyMatch(a -> a instanceof Sheep s && s.readyForShearing())) Cerveau.ecrire(t, "cisailles", Cerveau.UN_JOUR, "cisailles");
        }
        if (tout) {
            // Du lait : s'il en manque au dépôt (ou qu'on lui en commande), et qu'il a un seau vide.
            Travailleur.Mission m = t.missionActive();
            boolean commande = m != null && m.id.equals("lait");
            boolean seau = t.compter(SEAU_VIDE) > 0 || b != null && b.compter(SEAU_VIDE) > 0;
            if (seau && (commande || b != null && b.compter(LAIT) < LAIT_EN_RESERVE))
                betes.stream().filter(a -> a instanceof Cow && a.getType() == net.minecraft.world.entity.EntityType.COW && !a.isBaby() && a.isAlive())
                        .findFirst().ifPresent(a -> taches.add(new Tache("traire", a)));
        }
        if (taches.isEmpty() && !(tout && !parTerre(l, e).isEmpty())) return null;
        return new Corvee(e, p, taches, t);
    }

    /**
     * L'enclos est à lui : dans son espace de vie, hors d'une structure (un village), et aucun poste d'un autre chef n'en est
     * plus près que le sien (la règle du fermier, {@link Fermier#aLui}).
     */
    static boolean aLui(Travailleur t, Elevage.Enclos e) {
        BlockPos milieu = new BlockPos((e.minX() + e.maxX()) / 2, e.y(), (e.minZ() + e.maxZ()) / 2);
        return t.chezLui(milieu) && Fermier.aLui(t, milieu);
    }

    /** Ce qui traîne par terre dans l'enclos et qu'il ramasse : des œufs, des plumes, de la laine. */
    static List<ItemEntity> parTerre(ServerLevel l, Elevage.Enclos e) {
        AABB zone = new AABB(e.minX(), e.y() - 3, e.minZ(), e.maxX() + 1, e.y() + 4, e.maxZ() + 1);
        return l.getEntitiesOfClass(ItemEntity.class, zone, ie -> ie.isAlive() && PRODUITS.objet(ie.getItem()) && e.contient(ie.getBlockX(), ie.getBlockZ()));
    }

    /**
     * Une visite d'un enclos : il prend au dépôt ce qu'il lui faut (fourrage, cisailles, seau), entre par le portillon (et le
     * referme), fait ses tâches, la bête la plus proche d'abord, ramasse ce qui traîne, et ressort (en refermant). Interrompu,
     * il referme le portillon qu'il avait ouvert ; il sortira de l'enclos à son prochain travail.
     */
    static final class Corvee extends Actions.Action {
        final Elevage.Enclos enclos;
        final Passage passage;
        final List<Tache> taches;
        Actions.Action sous;
        Tache cible;
        ItemEntity objet;
        final Set<String> pris = new HashSet<>();
        int etape, marche, prochain, ramasse, essais;
        int nourries, tondus, traites, abattues;
        boolean sansFourrage, sansCisailles;
        final Map<String, Integer> abattuesPar = new LinkedHashMap<>();
        /** La tâche d'abattage en cours (pour la reprendre si la bête lui a échappé) ; les reprises par bête. */
        Tache frappe;
        final Map<Integer, Integer> reprises = new java.util.HashMap<>();
        /** Une bête qui lui a échappé est reprise au plus tant de fois dans la même visite. */
        static final int REPRISES = 3;

        /** Son poste est dans cet enclos (sa cour) : il y est chez lui, sans passer le portillon. */
        final boolean cour;

        Corvee(Elevage.Enclos enclos, Passage passage, List<Tache> taches, Travailleur t) {
            super("corvee");
            this.enclos = enclos;
            this.passage = passage;
            this.taches = taches;
            cour = t.bureau != null && enclos.contient(t.bureau.getX(), t.bureau.getZ());
            mobile = true; // il suit des bêtes qui bougent : ne pas les rattraper tout de suite n'est pas être coincé (Coince)
            dePres = true; // dans l'enclos, son chemin suit la bête qui bouge (Actions.Action.marcher)
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t); // un passage en cours referme son portillon
            sous = null;
            // Interrompu dans l'enclos : il en sortira avant toute autre chose (Metier.sortir).
            Ferme f = FERMES.get(t);
            if (f != null && !cour && dedans(t)) f.aSortir = enclos;
        }

        boolean dedans(Travailleur t) { return dans(enclos, t); }

        Actions.Etat tick(Travailleur t) {
            if (sous != null) {
                Actions.Etat r = sous.tick(t);
                sous.age++;
                if (r == Actions.Etat.EN_COURS) return r;
                sous.fin(t);
                Actions.Action fait = sous;
                sous = null;
                if (fait instanceof Actions.Attaquer a && !a.cible.isAlive()) {
                    abattues++;
                    Elevage.Espece esp = a.cible instanceof Animal x ? espece(x) : null;
                    if (esp != null) abattuesPar.merge(esp.id(), 1, Integer::sum);
                } else if (fait instanceof Actions.Attaquer a && frappe != null) {
                    // La bête lui a échappé (coincé derrière les autres bêtes de l'enclos, « inaccessible » au bout de 15 s) :
                    // elle est dans son enclos, il la reprend plus tard dans la visite au lieu de renoncer (un ordre serait perdu).
                    t.inaccessibles.remove(a.cible.getId());
                    reprendre(frappe);
                }
                frappe = null;
                if (fait instanceof Passer p && r == Actions.Etat.ECHEC) {
                    // Pas réussi à entrer : il renonce (il est dehors). À sortir : il recommence, jamais d'abandon dedans
                    // (5 essais au plus ; ensuite Metier.sortir le relancera).
                    if (etape == 1 && !dedans(t)) return echec(p.raison);
                    if (etape >= 4 && dedans(t) && ++essais >= 5) return echec(p.raison);
                }
            }
            switch (etape) {
                case 0 -> { return provisions(t); }
                case 1 -> {
                    if (cour || dedans(t)) { etape = 2; return Actions.Etat.EN_COURS; }
                    sous = new Passer(passage, passage.dehors, passage.dedans);
                    return Actions.Etat.EN_COURS;
                }
                case 2 -> { return soigner(t); }
                case 3 -> { return ramasser(t); }
                default -> {
                    if (cour || !dedans(t)) { travail = nourries + tondus + traites + abattues + ramasse > 0; return Actions.Etat.OK; }
                    sous = new Passer(passage, passage.dedans, passage.dehors);
                    return Actions.Etat.EN_COURS;
                }
            }
        }

        /** Au poste, ce qui lui manque pour ses tâches ; puis il garde seulement les tâches qu'il peut faire. */
        Actions.Etat provisions(Travailleur t) {
            Bureau.Entite b = Bureau.entite(t);
            Map<String, Integer> faut = new LinkedHashMap<>();
            for (Tache k : taches) if (k.quoi.equals("nourrir")) faut.merge(espece(k.bete).fourrage(), 1, Integer::sum);
            for (var x : faut.entrySet()) {
                Actions.Filtre f = Actions.Filtre.de(x.getKey());
                int manque = x.getValue() - t.compter(f);
                if (manque > 0 && b != null && b.compter(f) > 0 && pris.add(x.getKey())) { sous = new Actions.Prendre(f, manque); return Actions.Etat.EN_COURS; }
            }
            boolean tondre = taches.stream().anyMatch(k -> k.quoi.equals("tondre")), traire = taches.stream().anyMatch(k -> k.quoi.equals("traire"));
            if (tondre && meilleure(t, CISAILLES::objet) == 0 && b != null && b.compter(CISAILLES) > 0 && pris.add("cisailles")) { sous = new Actions.Prendre(CISAILLES, 1); return Actions.Etat.EN_COURS; }
            if (traire && t.compter(SEAU_VIDE) == 0 && b != null && b.compter(SEAU_VIDE) > 0 && pris.add("seau")) { sous = new Actions.Prendre(SEAU_VIDE, 1); return Actions.Etat.EN_COURS; }
            // Le fourrage, par couples : une bête nourrie seule ne fait pas de petit.
            Map<String, Integer> reste = new LinkedHashMap<>();
            for (String f : faut.keySet()) reste.put(f, t.compter(Actions.Filtre.de(f)));
            for (Elevage.Espece esp : Elevage.TOUTES) {
                List<Tache> siennes = taches.stream().filter(k -> k.quoi.equals("nourrir") && espece(k.bete) == esp).toList();
                if (siennes.isEmpty()) continue;
                int peut = Math.min(siennes.size(), reste.get(esp.fourrage())) / 2 * 2;
                reste.merge(esp.fourrage(), -peut, Integer::sum);
                if (peut < siennes.size()) { sansFourrage = true; taches.removeAll(siennes.subList(peut, siennes.size())); }
            }
            if (tondre && meilleure(t, CISAILLES::objet) == 0) { sansCisailles = true; taches.removeIf(k -> k.quoi.equals("tondre")); }
            if (traire && t.compter(SEAU_VIDE) == 0) taches.removeIf(k -> k.quoi.equals("traire"));
            etape = 1;
            return Actions.Etat.EN_COURS;
        }

        /** Les tâches, la bête la plus proche d'abord ; le sac presque plein, il s'arrête. */
        Actions.Etat soigner(Travailleur t) {
            if (cible == null) {
                taches.removeIf(k -> !k.bete.isAlive() || !dans(enclos, k.bete));
                if (taches.isEmpty() || Monde.libres(t) < 2) { etape = 3; ramasse = 0; return Actions.Etat.EN_COURS; }
                cible = taches.stream().min(Comparator.comparingDouble(k -> k.bete.distanceToSqr(t))).orElseThrow();
                taches.remove(cible);
                marche = 0;
                if (cible.quoi.equals("abattre")) {
                    Animal a = cible.bete;
                    Tache cible0 = cible;
                    cible = null;
                    if (!abattable(t, a, enclos) || !encoreTrop(t, a, cible0)) return Actions.Etat.EN_COURS;
                    armer(t);
                    frappe = cible0;
                    sous = new Actions.Attaquer(a).portee(24);
                    sous.dePres = true;
                    return Actions.Etat.EN_COURS;
                }
            }
            Animal a = cible.bete;
            if (!a.isAlive()) { cible = null; return Actions.Etat.EN_COURS; }
            if (t.distanceToSqr(a) > 2.5 * 2.5) {
                if (++marche > 20 * 15) { reprendre(cible); cible = null; } // elle lui échappe : la suivante, puis de nouveau elle
                else marcher(t, a.position(), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(a);
            if (age < prochain) return Actions.Etat.EN_COURS;
            prochain = age + 6;
            switch (cible.quoi) {
                case "nourrir" -> nourrir(t, a);
                case "tondre" -> tondre(t, a);
                case "traire" -> traire(t, a);
                default -> {}
            }
            cible = null;
            return Actions.Etat.EN_COURS;
        }

        /**
         * Une bête qui lui a échappé (coincée derrière les autres, dans un coin) : sa tâche revient en fin de liste, au plus
         * {@link #REPRISES} fois. Sinon il nourrissait une bête sur deux (pas de petit, fourrage perdu) ou un ordre d'abattre
         * s'arrêtait sans rien faire.
         */
        void reprendre(Tache k) {
            if (k.bete.isAlive() && dans(enclos, k.bete) && reprises.merge(k.bete.getId(), 1, Integer::sum) <= REPRISES) taches.add(k);
        }

        /**
         * Juste avant d'abattre : les bêtes de l'espèce recomptées dans l'enclos (un joueur a pu en tuer une entre-temps) ; il
         * n'abat que s'il en reste plus que ce qu'il garde (le dernier couple au moins).
         */
        boolean encoreTrop(Travailleur t, Animal a, Tache k) {
            Elevage.Espece esp = espece(a);
            AABB zone = new AABB(enclos.minX(), enclos.y() - 3, enclos.minZ(), enclos.maxX() + 1, enclos.y() + 4, enclos.maxZ() + 1);
            List<Elevage.Bete> vues = new ArrayList<>();
            for (Animal x : t.level().getEntitiesOfClass(Animal.class, zone, x -> x.isAlive() && espece(x) == esp && dans(enclos, x)))
                vues.add(new Elevage.Bete(x.getId(), esp.id(), !x.isBaby(), intouchable(x), false));
            return !Elevage.aAbattre(vues, esp.id(), k.garder(), 1).isEmpty();
        }

        void nourrir(Travailleur t, Animal a) {
            if (a.isBaby() || a.getAge() != 0 || !a.canFallInLove()) return;
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (s.isEmpty() || !a.isFood(s)) continue;
                s.shrink(1);
                t.inventaire.setChanged();
                a.setInLove(null);
                t.swing(InteractionHand.MAIN_HAND);
                a.playSound(SoundEvents.GENERIC_EAT, 0.5f, 1);
                nourries++;
                return;
            }
        }

        void tondre(Travailleur t, Animal a) {
            if (!(a instanceof Sheep s) || !s.readyForShearing() || !t.equiper(CISAILLES)) return;
            ItemStack c = t.getMainHandItem();
            for (ItemStack laine : s.onSheared(null, c, t.level(), s.blockPosition())) t.ranger(laine.copy());
            s.gameEvent(GameEvent.SHEAR, t);
            Actions.userOutil(t, 1);
            t.swing(InteractionHand.MAIN_HAND);
            tondus++;
        }

        void traire(Travailleur t, Animal a) {
            if (a.isBaby() || Actions.sortir(t, SEAU_VIDE).isEmpty()) return;
            t.ranger(new ItemStack(Items.MILK_BUCKET));
            a.playSound(SoundEvents.COW_MILK, 1, 1);
            t.swing(InteractionHand.MAIN_HAND);
            traites++;
        }

        /** Ce qui traîne par terre dans l'enclos (des œufs) : il le ramasse en passant dessus, 20 s au plus. */
        Actions.Etat ramasser(Travailleur t) {
            if (++ramasse > 20 * 20 || Monde.libres(t) == 0) { etape = 4; return Actions.Etat.EN_COURS; }
            if (objet == null || !objet.isAlive() || ramasse % 20 == 1) {
                objet = parTerre((ServerLevel) t.level(), enclos).stream().min(Comparator.comparingDouble(ie -> ie.distanceToSqr(t))).orElse(null);
                if (objet == null) { etape = 4; return Actions.Etat.EN_COURS; }
            }
            marcher(t, objet.position(), 1.0);
            return Actions.Etat.EN_COURS;
        }

    }

    /**
     * Passer le portillon de {@code de} à {@code vers} : il va devant, ouvre, traverse (tout droit s'il est dans l'axe, sinon par
     * le chemin du jeu, qui passe le portillon ouvert), et referme derrière lui dès qu'il est passé (s'il n'y a pas une bête
     * dans l'embrasure : 3 s au plus). Il n'y arrive pas : il recule hors de l'embrasure, referme, et échoue. Une action à
     * part : ses compteurs et son chemin repartent de zéro à chaque passage. Interrompu, il referme.
     */
    static final class Passer extends Actions.Action {
        final Passage passage;
        final BlockPos de, vers;
        int pas, marche;
        boolean ouvert;

        Passer(Passage passage, BlockPos de, BlockPos vers) { super("portillon"); this.passage = passage; this.de = de; this.vers = vers; }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (ouvert) { fermer(t, passage.porte); ouvert = false; } // jamais un portillon laissé ouvert
        }

        Actions.Etat tick(Travailleur t) {
            Vec3 a = Vec3.atBottomCenterOf(de), b = Vec3.atBottomCenterOf(vers);
            switch (pas) {
                case 0 -> {
                    if (horizontale(t, a) <= 1.2) { pas = 1; marche = 0; return Actions.Etat.EN_COURS; }
                    if (coince() || ++marche > 20 * 60) return echec("portillon_inaccessible");
                    marcher(t, a, 1.0);
                }
                case 1 -> {
                    // Juste devant, dans l'axe du portillon (2 s au plus : une bête peut lui prendre la place).
                    t.getNavigation().stop();
                    t.getMoveControl().setWantedPosition(a.x, a.y, a.z, 0.8);
                    if (horizontale(t, a) <= 0.25 || ++marche > 40) { pas = 2; marche = 0; ouvrir(t, passage.porte); ouvert = true; }
                }
                case 2 -> {
                    t.getLookControl().setLookAt(b.x, b.y + 1.5, b.z);
                    boolean passe = horizontale(t, b) <= 0.45 && !t.getBoundingBox().intersects(new AABB(passage.porte));
                    if (passe) {
                        t.getNavigation().stop();
                        boolean libre = t.level().getEntities(t, new AABB(passage.porte)).isEmpty();
                        if (libre || ++marche > 20 * 3) { fermer(t, passage.porte); ouvert = false; return Actions.Etat.OK; }
                        return Actions.Etat.EN_COURS;
                    }
                    if (++marche > 20 * 6) { pas = 3; marche = 0; return Actions.Etat.EN_COURS; }
                    if (droit(t, b)) { t.getNavigation().stop(); t.getMoveControl().setWantedPosition(b.x, b.y, b.z, 0.8); }
                    else if (t.getNavigation().isDone() || marche % 20 == 1) t.getNavigation().moveTo(b.x, b.y, b.z, 0.8);
                }
                default -> {
                    // Il recule hors de l'embrasure, referme, et échoue (le portillon ne reste jamais ouvert).
                    t.getNavigation().stop();
                    t.getMoveControl().setWantedPosition(a.x, a.y, a.z, 0.8);
                    if (!t.getBoundingBox().intersects(new AABB(passage.porte)) || ++marche > 40) {
                        fermer(t, passage.porte);
                        ouvert = false;
                        return echec("portillon_bloque");
                    }
                }
            }
            return Actions.Etat.EN_COURS;
        }

        /**
         * Le chemin tout droit jusqu'à {@code b} ne bute pas contre la clôture : il est dans l'axe du portillon (à 0,15 bloc
         * près), ou déjà sorti de l'embrasure, du côté de {@code b}.
         */
        boolean droit(Travailleur t, Vec3 b) {
            BlockState s = t.level().getBlockState(passage.porte);
            if (!(s.getBlock() instanceof FenceGateBlock)) return true;
            boolean x = s.getValue(FenceGateBlock.FACING).getAxis() == Direction.Axis.X;
            double cx = passage.porte.getX() + 0.5, cz = passage.porte.getZ() + 0.5;
            double travers = x ? Math.abs(t.getZ() - cz) : Math.abs(t.getX() - cx);
            double ici = x ? t.getX() - cx : t.getZ() - cz, la = x ? b.x - cx : b.z - cz;
            return travers <= 0.15 || Math.signum(ici) == Math.signum(la) && Math.abs(ici) >= 0.5 + t.getBbWidth() / 2;
        }
    }

    static double horizontale(Travailleur t, Vec3 p) { return Math.sqrt(Math.pow(t.getX() - p.x, 2) + Math.pow(t.getZ() - p.z, 2)); }

    static void ouvrir(Travailleur t, BlockPos g) {
        BlockState s = t.level().getBlockState(g);
        if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.OPEN)) return;
        t.level().setBlock(g, s.setValue(FenceGateBlock.OPEN, true), 10);
        t.level().playSound(null, g, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1, 1);
        t.level().gameEvent(t, GameEvent.BLOCK_OPEN, g);
        t.swing(InteractionHand.OFF_HAND);
    }

    static void fermer(Travailleur t, BlockPos g) {
        BlockState s = t.level().getBlockState(g);
        if (!(s.getBlock() instanceof FenceGateBlock) || !s.getValue(FenceGateBlock.OPEN)) return;
        t.level().setBlock(g, s.setValue(FenceGateBlock.OPEN, false), 10);
        t.level().playSound(null, g, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1, 1);
        t.level().gameEvent(t, GameEvent.BLOCK_CLOSE, g);
    }

    /** Pour abattre : son épée ou sa hache en main, s'il en a une ; sinon les mains nues (ses cisailles ne s'usent pas à frapper). */
    static void armer(Travailleur t) {
        if (equiperMeilleur(t, ARMES::objet)) return;
        ItemStack tenu = t.getMainHandItem();
        if (!tenu.isEmpty() && t.inventaire.canAddItem(tenu)) {
            t.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            t.ranger(tenu);
        }
    }

    /** Le butin d'une bête qu'abat un ouvrier va droit dans son sac (rien ne tombe par terre ; s'il est plein, à ses pieds). */
    static void butin(LivingDropsEvent e) {
        if (!(e.getSource().getEntity() instanceof Travailleur t) || !(e.getEntity() instanceof Animal a)) return;
        if (!("eleveur".equals(t.metier) && espece(a) != null || "chasseur".equals(t.metier) && Chasseur.espece(a) != null)) return; // le chasseur aussi
        for (ItemEntity ie : e.getDrops()) t.ranger(ie.getItem().copy());
        e.setCanceled(true);
    }

    // --- Sur ordre ----------------------------------------------------------------------------------------------

    /** L'action qui joue une consigne de l'éleveur (« nourrir », « tondre », « abattre_betes ») ; elle dit ce qu'elle a fait. */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) {
        return switch (mode) {
            case "abattre_betes" -> new Tournee("abattre", Elevage.parId(c.texte), c.texte.isEmpty() ? Integer.MAX_VALUE : Math.max(1, c.nombre));
            case "tondre" -> new Tournee("tondre", null, 0);
            default -> new Tournee("nourrir", null, 0);
        };
    }

    /** Sur ordre : la tournée de tous les enclos pour une seule tâche, puis le dépôt, puis il dit ce qu'il a fait. */
    static final class Tournee extends Actions.Action {
        final String mode;
        final Elevage.Espece espece;
        final int[] voulu;
        final List<Corvee> corvees = new ArrayList<>();
        Actions.Action sous;
        int etape, i, nourries, tondus, abattues;
        boolean sansFourrage, sansCisailles, enclos, voisin, sienne;
        final Map<String, Integer> abattuesPar = new LinkedHashMap<>();

        Tournee(String mode, Elevage.Espece espece, int voulu) { super("tournee"); this.mode = mode; this.espece = espece; this.voulu = new int[]{voulu}; }

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
                if (sous instanceof Corvee c) {
                    nourries += c.nourries;
                    tondus += c.tondus;
                    abattues += c.abattues;
                    c.abattuesPar.forEach((k, v) -> abattuesPar.merge(k, v, Integer::sum));
                    sansFourrage |= c.sansFourrage;
                    sansCisailles |= c.sansCisailles;
                }
                sous.fin(t);
                sous = null;
            }
            if (etape == 0) {
                etape = 1;
                Ferme f = releve(t, true);
                Map<Elevage.Enclos, List<Animal>> parEnclos = parEnclos(f);
                enclos = !parEnclos.isEmpty();
                List<Elevage.Enclos> liste = new ArrayList<>(parEnclos.keySet());
                liste.sort(Comparator.comparingDouble(e -> t.distanceToSqr(e.minX(), e.y(), e.minZ())));
                for (Elevage.Enclos e : liste) {
                    if (mode.equals("abattre") && espece != null && voulu[0] <= 0) break;
                    if (!aLui(t, e)) { voisin = true; continue; }
                    sienne = true;
                    Corvee c = plan(t, e, parEnclos.get(e), mode, espece, voulu);
                    if (c != null) corvees.add(c);
                }
                if (corvees.isEmpty()) { Voix.dire(t, rien(), "calme"); return echec("rien_a_faire"); }
            }
            if (i < corvees.size()) { sous = corvees.get(i++); return Actions.Etat.EN_COURS; }
            if (etape == 1) {
                etape = 2;
                Metier m = Metier.de(t.metier);
                if (t.compter(PRODUITS) > 0) { sous = new Actions.Deposer(m == null ? List.of() : m.garder); return Actions.Etat.EN_COURS; }
            }
            Voix.dire(t, bilan(), "calme");
            return nourries + tondus + abattues > 0 ? Actions.Etat.OK : echec("rien_fait");
        }

        /** Rien à faire : pourquoi. */
        String rien() {
            if (!enclos) return "Je ne vois aucune bête dans un enclos près du poste, chef.";
            if (voisin && !sienne) return "Les seules bêtes que je vois ne sont pas à nous, chef : je n'y touche pas.";
            return switch (mode) {
                case "tondre" -> "Les moutons n'ont pas de laine à tondre, chef.";
                case "abattre" -> espece == null ? "Il n'y a pas de bêtes en trop dans les enclos, chef."
                        : "Je ne peux pas, chef : je garde toujours un couple de " + espece.noms() + ", et je ne touche ni aux petits ni aux bêtes qui ont un nom.";
                default -> "Les bêtes n'ont pas besoin d'être nourries, chef : pas de couple prêt, ou l'enclos est plein.";
            };
        }

        String bilan() {
            return switch (mode) {
                case "tondre" -> tondus > 0 ? "J'ai tondu " + tondus + " mouton" + (tondus > 1 ? "s" : "") + ", chef : la laine est au dépôt."
                        : sansCisailles ? "Il me faut des cisailles, chef, et il n'y en a pas au dépôt." : "Je n'ai pu tondre aucun mouton, chef.";
                case "abattre" -> {
                    if (abattues == 0) yield "Je n'ai pu en abattre aucune, chef.";
                    List<String> l = new ArrayList<>();
                    abattuesPar.forEach((k, v) -> { Elevage.Espece x = Elevage.parId(k); l.add(v + " " + (v > 1 ? x.noms() : x.nom())); });
                    String fait = "J'ai abattu " + String.join(" et ", l) + ", chef : la viande est au dépôt.";
                    yield espece != null && voulu[0] > 0 ? fait + " Je n'en abats pas plus : je garde toujours un couple, et je ne touche ni aux petits ni aux bêtes qui ont un nom." : fait;
                }
                default -> nourries > 0 ? "J'ai nourri " + nourries + " bêtes, chef : il y aura bientôt des petits."
                        : sansFourrage ? "Je n'ai pas de quoi les nourrir, chef : il faut du blé, des carottes ou des graines au dépôt." : "Je n'ai pu nourrir aucune bête, chef.";
            };
        }
    }
}

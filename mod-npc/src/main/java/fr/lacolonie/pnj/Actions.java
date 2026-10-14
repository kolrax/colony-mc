package fr.lacolonie.pnj;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Les gestes du PNJ. Chaque action dure plusieurs ticks ; le script en lance une à la fois. */
public final class Actions {
    private Actions() {}

    public enum Etat { EN_COURS, OK, ECHEC }

    // --- Filtres : "minecraft:stone", "#minecraft:logs", "nourriture", "tout", ou plusieurs avec "|" ---

    public static final class Filtre {
        final String texte;
        final List<TagKey<Block>> tagsBloc = new ArrayList<>();
        final List<TagKey<Item>> tagsObjet = new ArrayList<>();
        final Set<ResourceLocation> ids = new HashSet<>();
        boolean nourriture, tout, soin;
        static final Map<String, Filtre> CACHE = new ConcurrentHashMap<>();

        public static Filtre de(String s) { return CACHE.computeIfAbsent(s == null ? "" : s.trim(), Filtre::new); }

        private Filtre(String s) {
            texte = s;
            for (String p : s.split("\\|")) {
                p = p.trim();
                if (p.equals("nourriture")) nourriture = true;
                else if (p.equals("soin")) soin = true; // une potion de soin ou de régénération (pas une fiole d'eau)
                else if (p.equals("tout") || p.equals("*")) tout = true;
                else if (p.startsWith("#")) {
                    ResourceLocation id = ResourceLocation.tryParse(p.substring(1));
                    if (id != null) {
                        tagsBloc.add(TagKey.create(Registries.BLOCK, id));
                        tagsObjet.add(TagKey.create(Registries.ITEM, id));
                    }
                } else {
                    ResourceLocation id = ResourceLocation.tryParse(p);
                    if (id != null) ids.add(id);
                }
            }
        }

        public boolean bloc(BlockState st) {
            if (st.isAir()) return false;
            if (tout) return true;
            for (TagKey<Block> t : tagsBloc) if (st.is(t)) return true;
            return !ids.isEmpty() && ids.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock()));
        }

        public boolean objet(ItemStack s) {
            if (s.isEmpty()) return false;
            if (tout) return true;
            if (nourriture && s.getFoodProperties(null) != null) return true;
            if (soin && Guerrier.soignante(s)) return true;
            for (TagKey<Item> t : tagsObjet) if (s.is(t)) return true;
            return !ids.isEmpty() && ids.contains(BuiltInRegistries.ITEM.getKey(s.getItem()));
        }

        @Override
        public String toString() { return texte; }
    }

    // --- Base -------------------------------------------------------------------------------------

    public abstract static class Action {
        public final String nom;
        public int age;
        public String raison = "";
        private Itineraire route;
        private Vec3 dernier;
        private int immobile, sansProgres;
        private double meilleure = Double.MAX_VALUE;
        /** Sa cible bouge (le chef, une créature) : ne pas l'atteindre n'est pas être coincé (Coince). */
        boolean mobile;
        /** Y arriver, c'est du travail (un poste de ronde) : les retours au poste ne s'enchaînent plus (Coince). */
        boolean travail;
        /**
         * Il suit de près une bête qui bouge dans un enclos (l'éleveur) : le chemin est revu dès qu'elle s'écarte, tant qu'il
         * suit un chemin direct réussi (voir {@link #marcher}).
         */
        boolean dePres;

        Action(String nom) { this.nom = nom; }

        abstract Etat tick(Travailleur t);

        /** Un nouveau but dans la même action (après le dépôt, le blessé) : la marche d'avant ne compte plus (chemin, progrès, immobilité). */
        void nouveauBut() {
            route = null;
            dernier = null;
            meilleure = Double.MAX_VALUE;
            sansProgres = 0;
            immobile = 0;
        }

        void fin(Travailleur t) {
            t.getNavigation().stop();
            t.setSprinting(false);
        }

        /** Marche vers la cible, à n'importe quelle distance, sans foncer dans un cul-de-sac (voir {@link Itineraire}). */
        void marcher(Travailleur t, Vec3 cible, double vitesse) {
            Vec3 p = t.position();
            // Le but a bougé de plus de 4 blocs : nouveau chemin. Une bête suivie de près ({@code dePres}, dans un enclos) : dès
            // qu'elle s'est écartée d'un quart de la distance (un bloc au moins), revu au plus toutes les 10 ticks, mais seulement
            // s'il suit un chemin direct réussi ; sinon il restait planté là où elle était (ou là où était la bête d'avant). Un
            // détour en cours (étapes, creusage) n'est jamais remis à zéro : une cible inaccessible reste « bloquée ».
            double ecart = route == null ? 0 : route.but.distanceToSqr(cible);
            if (route == null || ecart > 16
                    || dePres && age % 10 == 0 && ecart > Math.max(1, p.distanceToSqr(cible) / 16) && route.surCheminDirect()) route = new Itineraire(t, cible);
            // Il n'entre pas dans un coin que le monde n'a pas fini de préparer (terrain neuf en cours de génération) :
            // il s'y figerait. Il attend au bord, le temps que le serveur le termine.
            Vec3 cap = cible.subtract(p).multiply(1, 0, 1);
            if (cap.lengthSqr() > 1 && t.level() instanceof net.minecraft.server.level.ServerLevel sl
                    && !sl.isPositionEntityTicking(BlockPos.containing(p.add(cap.normalize().scale(5))))) {
                t.getNavigation().stop();
                t.diag = "attend que le terrain soit prêt";
                // Pas compté « coincé » (Coince) : il attend le serveur, pas un passage ; sinon il pouvait être ramené au poste.
                sansProgres++; // au bout de 2 minutes d'attente, il se dira coincé
                immobile = 0;
                dernier = null;
                return;
            }
            route.avancer(t, vitesse, age);
            // Il veut aller à un endroit fixe et ne creuse ni ne pose : ce temps compte s'il n'avance pas vraiment (Coince).
            if (!mobile && !route.occupe()) t.coince.marche = true;
            double d = p.distanceTo(cible);
            if (d < meilleure - 2 || route.occupe()) { meilleure = Math.min(meilleure, d); sansProgres = 0; }
            else sansProgres++;
            // Il attend son tour pour chercher une étape, ou le délai après un choix raté : ce temps-là ne compte pas.
            if (age % 40 == 0) {
                if (!route.attend(t.level().getGameTime()))
                    immobile = dernier != null && dernier.distanceToSqr(p) < 0.25 && !route.occupe() ? immobile + 40 : 0;
                dernier = p;
            }
        }

        /** Il a marché pendant cette action (vers une cible). */
        boolean aMarche() { return route != null; }

        /** Coincé : plus aucun passage vers le but, immobile depuis 15 s, ou sans se rapprocher depuis 2 minutes. */
        boolean coince() { return route != null && route.bloque || immobile >= 300 || sansProgres >= 2400; }

        Etat echec(String r) { raison = r; return Etat.ECHEC; }
    }

    /** Portée des petites recherches de chemin (fuite, place à côté d'un bloc) : quelques blocs suffisent. */
    static final float PORTEE_FUITE = 8;

    static boolean aPortee(Travailleur t, BlockPos p) { return t.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= 4.5 * 4.5; }

    /** Durée (en ticks) pour casser un bloc avec l'outil en main, comme un joueur. */
    static float duree(Travailleur t, BlockState st, BlockPos p) {
        float durete = st.getDestroySpeed(t.level(), p);
        if (durete <= 0) return 1;
        ItemStack outil = t.getMainHandItem();
        boolean bon = !st.requiresCorrectToolForDrops() || outil.isCorrectToolForDrops(st);
        float vitesse = Math.max(1f, outil.getDestroySpeed(st));
        return (float) (durete * (bon ? 30 : 100) / vitesse / t.facteurTravail());
    }

    /** Casse le bloc et met ce qu'il donne dans le sac (rien si l'outil ne convient pas, comme pour un joueur). */
    static void recolter(Travailleur t, BlockPos p) {
        ServerLevel lvl = (ServerLevel) t.level();
        BlockState st = lvl.getBlockState(p);
        ItemStack outil = t.getMainHandItem();
        if (!st.requiresCorrectToolForDrops() || outil.isCorrectToolForDrops(st))
            for (ItemStack d : Block.getDrops(st, lvl, p, lvl.getBlockEntity(p), t, outil)) { d = Humeur.trouve(t, d); if (!d.isEmpty()) t.ranger(d); } // un diamant gardé en cachette ?
        lvl.destroyBlock(p, false, t);
    }

    /**
     * Use l'outil (ou l'arme) en main pour {@code n} coups : la seule règle d'usure des ouvriers (cahier 3.13). Il s'use
     * {@code Reglages.usure_outils} fois moins vite que celui d'un joueur (3 par défaut ; 1 : comme un joueur).
     */
    static void userOutil(Travailleur t, int n) {
        ItemStack outil = t.getMainHandItem();
        if (outil.isEmpty() || !outil.isDamageableItem()) return;
        int u = Outils.usure(n, Reglages.actuel.usure_outils, t.getRandom().nextDouble());
        if (u > 0) outil.hurtAndBreak(u, t, EquipmentSlot.MAINHAND);
    }

    // --- Déplacements -----------------------------------------------------------------------------

    static final class Aller extends Action {
        Vec3 cible;
        final double portee;
        /** La cible est au niveau du sol, qu'on ne connaît qu'une fois le coin chargé (exploration au loin). */
        final boolean auSol;

        Aller(Vec3 cible, double portee) { this(cible, portee, false); }

        Aller(Vec3 cible, double portee, boolean auSol) { super("aller"); this.cible = cible; this.portee = Math.max(0.8, portee); this.auSol = auSol; }

        /** Vers une créature qui bouge (le chef, un autre ouvrier) : pas compté comme coincé s'il ne l'atteint pas. */
        Aller mobile(boolean oui) { mobile = oui; return this; }

        Etat tick(Travailleur t) {
            if (auSol && age % 20 == 0) {
                int x = (int) Math.floor(cible.x), z = (int) Math.floor(cible.z);
                if (t.level().hasChunkAt(x, z)) cible = new Vec3(cible.x, t.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), cible.z);
            }
            if (t.position().distanceToSqr(cible) <= portee * portee) { if (aMarche()) t.coince.progres(t.getX(), t.getY(), t.getZ()); return Etat.OK; }
            if (coince()) return echec("bloque");
            if (age > 20 * 60 * 15) return echec("trop_loin");
            marcher(t, cible, 1.0);
            return Etat.EN_COURS;
        }
    }

    /**
     * Il va prévenir un autre ouvrier de son chef d'une commande qui n'est pas de son métier ({@link Relais}) : arrivé à
     * 3 blocs, il la lui dit. S'il n'y arrive pas (1 min 30, coincé, interrompu par un danger), il la lui fait passer
     * quand même ({@code fin}) : la commande n'est jamais perdue.
     */
    static final class Prevenir extends Action {
        final Relais.Commande c;
        boolean fait;

        Prevenir(Relais.Commande c) { super("prevenir"); this.c = c; mobile = true; }

        Etat tick(Travailleur t) {
            Travailleur o = ((ServerLevel) t.level()).getEntity(c.cible()) instanceof Travailleur x && x.isAlive() ? x : null;
            if (o == null) return echec("perdu");
            if (t.distanceToSqr(o) <= 9) {
                t.getNavigation().stop();
                t.getLookControl().setLookAt(o);
                fait = true;
                t.transmettre(c, true);
                return Etat.OK;
            }
            if (age > 20 * 90 || coince()) return echec("trop_loin");
            if (t.distanceToSqr(o) <= 64) o.ecouter(t, 40); // « Bertrand, attends ! » : il s'arrête pour l'écouter
            t.diag = "va prévenir " + c.prenom();
            marcher(t, o.position(), 1.0);
            return Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (!fait) { fait = true; t.transmettre(c, false); }
        }
    }

    static final class SuivreChef extends Action {
        SuivreChef() { super("suivre"); mobile = true; }

        Etat tick(Travailleur t) {
            ServerPlayer c = t.chefJoueur();
            if (c == null || c.level() != t.level()) return echec("chef_absent");
            double d = t.distanceTo(c);
            t.getLookControl().setLookAt(c);
            if (d <= 3.5) { t.coince.progres(t.getX(), t.getY(), t.getZ()); return Etat.OK; }
            if (age > 20 * 30) return Etat.OK;
            marcher(t, c.position(), d > 12 ? 1.3 : 1.1);
            return Etat.EN_COURS;
        }
    }

    static final class Fuir extends Action {
        final Entity danger;
        Vec3 refuge;
        /** Au plus tant de ticks (plus long quand il court prévenir le guerrier : Defense). */
        int duree = 20 * 8;

        Fuir(Entity danger) { super("fuir"); this.danger = danger; mobile = true; }

        Etat tick(Travailleur t) {
            if (refuge == null) refuge = DefaultRandomPos.getPosAway(t, 16, 7, danger.position());
            if (refuge == null || age > duree) return Etat.OK;
            marcher(t, refuge, 1.35);
            return t.position().distanceToSqr(refuge) < 4 ? Etat.OK : Etat.EN_COURS;
        }
    }

    static final class Attendre extends Action {
        final int ticks;

        Attendre(int ticks) { super("attendre"); this.ticks = ticks; }

        Etat tick(Travailleur t) { return age >= ticks ? Etat.OK : Etat.EN_COURS; }
    }

    // --- Travail sur les blocs --------------------------------------------------------------------

    static final class Casser extends Action {
        final BlockPos pos;
        float progres;
        /** Où il se met pour casser un bloc qui a du gravier ou du sable au-dessus, sans l'avoir sur la tête. */
        BlockPos cote;
        int bouchons, aCote;
        /** Un bloc du chantier du maçon : hors d'atteinte, ce n'est pas être coincé ({@code Travailleur.finirAction}). */
        boolean chantier;

        Casser(BlockPos pos) { super("casser"); this.pos = pos; }

        Etat tick(Travailleur t) {
            ServerLevel lvl = (ServerLevel) t.level();
            BlockState st = lvl.getBlockState(pos);
            if (st.isAir() || !st.getFluidState().isEmpty() && st.canBeReplaced()) return Etat.OK;
            if (st.getDestroySpeed(lvl, pos) < 0) return echec("incassable");
            if (lvl.getBlockEntity(pos) != null) return echec("contenant");
            if (!aPortee(t, pos)) {
                if (coince() || age > 20 * 60) { t.blocInaccessible(pos); return echec("inaccessible"); }
                marcher(t, Vec3.atCenterOf(pos), 1.0);
                return Etat.EN_COURS;
            }
            // Comme un mineur prudent : de la lave contre le bloc (ou de l'eau au-dessus, s'il est dessous et au sec), il la
            // bouche d'abord avec un bloc banal de son sac ; il n'en a pas : il renonce à ce bloc.
            if (progres == 0 || age % 10 == 0) {
                BlockPos source = Monde.coulee(lvl, pos, !t.isInWater() && dessous(t, pos));
                if (source != null) return boucher(t, source);
            }
            // Du gravier ou du sable au-dessus : il ne casse jamais le bloc en se tenant dessous ; il se met à côté.
            if (Monde.tombe(lvl, pos.above()) && dessous(t, pos)) return seMettreACote(t);
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            progres += 1;
            float total = duree(t, st, pos);
            if (age % 5 == 0) { t.swing(InteractionHand.MAIN_HAND); Sons.coup(t, pos, st); }
            lvl.destroyBlockProgress(t.getId(), pos, Math.min(9, (int) (progres / total * 10)));
            if (progres < total) return Etat.EN_COURS;
            recolter(t, pos);
            if (st.getDestroySpeed(lvl, pos) > 0) userOutil(t, 1);
            return Etat.OK;
        }

        /** Il pose un bloc de chantier dans le liquide (un par tick), ou renonce s'il ne peut pas. */
        Etat boucher(Travailleur t, BlockPos source) {
            int i = Itineraire.caseDeChantier(t, true); // pierre ou terre : jamais de planches contre la lave
            String quoi = Monde.liquide(t.level(), source);
            if (i < 0 || ++bouchons > 6 || !aPortee(t, source) || placer(t, source, t.inventaire.getItem(i)) != null) {
                t.blocInaccessible(pos);
                return echec("danger");
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(source));
            Cerveau.dire(t, "bouche", 600, "lave".equals(quoi) ? "De la lave derrière la roche : je la bouche avant de creuser." : "De l'eau au-dessus : je la bouche avant de creuser.");
            return Etat.EN_COURS;
        }

        /** Son corps est dans la colonne sous le bloc : ce qui est au-dessus lui tomberait dessus. */
        static boolean dessous(Travailleur t, BlockPos p) {
            return t.getBoundingBox().inflate(0.05, 0, 0.05).intersects(p.getX(), p.getY() - 24, p.getZ(), p.getX() + 1, p.getY(), p.getZ() + 1);
        }

        /** Il gagne une place d'où il touche le bloc sans être dessous ; s'il n'y en a pas, ou s'il n'y arrive pas, il renonce. */
        Etat seMettreACote(Travailleur t) {
            Level l = t.level();
            if (cote == null) {
                BlockPos p = t.blockPosition();
                double dMin = Double.MAX_VALUE;
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 1, 2))) {
                    double d = q.distSqr(p);
                    if (d >= dMin || q.getX() == pos.getX() && q.getZ() == pos.getZ() || !Monde.libre(l, q) || !Monde.libre(l, q.above())
                            || !Monde.debout(l, q) || Monde.mortel(l, q) || Vec3.atBottomCenterOf(q).add(0, 1.6, 0).distanceToSqr(Vec3.atCenterOf(pos)) > 4.2 * 4.2)
                        continue;
                    cote = q.immutable();
                    dMin = d;
                }
                if (cote == null) { t.blocInaccessible(pos); return echec("danger"); }
            }
            if (++aCote > 100) { t.blocInaccessible(pos); return echec("danger"); }
            Vec3 c = Vec3.atBottomCenterOf(cote);
            if (t.position().distanceToSqr(c) > 2.25) {
                // Une recherche de chemin par seconde au plus ; une place qu'aucun chemin complet n'atteint : il renonce.
                if (aCote % 20 == 1) {
                    net.minecraft.world.level.pathfinder.Path ch = Itineraire.chercher(t, cote, 0, Charge.Origine.AUTRE, PORTEE_FUITE, Itineraire.NOEUDS_ETAPE);
                    if (ch == null || !ch.canReach()) { t.blocInaccessible(pos); return echec("danger"); }
                    t.getNavigation().moveTo(ch, 0.8);
                }
            } else {
                t.getNavigation().stop();
                t.getMoveControl().setWantedPosition(c.x, c.y, c.z, 0.6);
            }
            return Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.level().destroyBlockProgress(t.getId(), pos, -1);
        }
    }

    /** Bouche de l'eau ou de la lave avec un bloc banal de son sac (pierre, terre ; jamais de planches), comme un mineur. */
    static final class Boucher extends Action {
        final BlockPos pos;

        Boucher(BlockPos pos) { super("boucher"); this.pos = pos; }

        Etat tick(Travailleur t) {
            if (Monde.liquide(t.level(), pos) == null) return Etat.OK;
            int i = Itineraire.caseDeChantier(t, true);
            if (i < 0) return echec("pas_d_objet");
            if (!aPortee(t, pos)) return echec("hors_de_portee");
            t.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            String r = placer(t, pos, t.inventaire.getItem(i));
            if (r != null) return echec(r);
            Cerveau.dire(t, "bouche", 600, "De l'eau ou de la lave devant : je la bouche avant de creuser.");
            return Etat.OK;
        }
    }

    /**
     * Le réflexe de survie, pour tout métier ({@link Travailleur#survivre}) : dans la lave ou le feu, enseveli, à bout d'air
     * ou en feu près de l'eau, il a tout lâché ; il gagne une place sûre (cherchée une fois par seconde), se dégage du bloc
     * où il étouffe, et reprend ensuite son travail.
     */
    static final class Echapper extends Action {
        BlockPos refuge, fouille;
        float progres;
        /** En feu, hors de la lave : il ne cherche que de l'eau, et s'arrête dès qu'il est éteint. */
        final boolean eau;
        /** Il va tout droit au refuge (la ligne est sûre), faute de chemin complet. */
        boolean toutDroit;
        /** Les refuges essayés sans chemin sûr : il ne les reprend pas. */
        final java.util.Set<BlockPos> exclus = new HashSet<>();

        Echapper(boolean eau) { super("echapper"); this.eau = eau; }

        /** Choisit où aller ; faux s'il n'y a rien à portée. */
        boolean choisir(Travailleur t) {
            Level l = t.level();
            refuge = eau ? Monde.eauProche(l, t.blockPosition(), 5) : Monde.refuge(l, t.blockPosition(), exclus);
            return refuge != null;
        }

        /**
         * Une fois par seconde au plus : tout droit si la ligne ne traverse rien de mortel (déjà dans la lave ou le feu, 3
         * cases brûlantes au plus : le plus court pour en sortir), sinon le chemin jusqu'au refuge s'il l'atteint, sinon un autre refuge (jamais un chemin partiel).
         */
        void partir(Travailleur t) {
            toutDroit = false;
            for (int essai = 0; essai < 3 && refuge != null; essai++) {
                if (Monde.ligneSure(t.level(), t.position(), refuge, t.isInLava() || t.level().getBlockState(t.blockPosition()).is(BlockTags.FIRE) ? 3 : 0)) { t.getNavigation().stop(); toutDroit = true; return; }
                net.minecraft.world.level.pathfinder.Path ch = Itineraire.chercher(t, refuge, 0, Charge.Origine.AUTRE, PORTEE_FUITE, Itineraire.NOEUDS_ETAPE);
                if (ch != null && ch.canReach()) { t.getNavigation().moveTo(ch, 1.3); return; }
                if (eau) { refuge = null; return; }
                exclus.add(refuge);
                choisir(t);
            }
            t.getNavigation().stop();
        }

        /** Enseveli : le côté libre le plus sûr (debout, sinon la plus petite chute, 3 blocs au plus), ou null. */
        static BlockPos sortie(Level l, BlockPos p) {
            BlockPos mieux = null;
            int chuteMin = 4;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!Monde.libre(l, q) || !Monde.libre(l, q.above()) || Monde.mortel(l, q)) continue;
                int c = Monde.chute(l, q);
                if (c < chuteMin) { chuteMin = c; mieux = q; }
            }
            return mieux;
        }

        Etat tick(Travailleur t) {
            Level l = t.level();
            if (t.isInWall()) {
                // Enseveli : il sort par le côté le plus sûr, sinon il dégage le bloc qu'il a sur la tête (s'il est naturel).
                BlockPos tete = BlockPos.containing(t.getEyePosition()), q = sortie(l, t.blockPosition());
                if (q != null) {
                    t.getNavigation().stop();
                    t.getMoveControl().setWantedPosition(q.getX() + 0.5, q.getY(), q.getZ() + 0.5, 1.0);
                    return age > 200 ? echec("perdu") : Etat.EN_COURS;
                }
                BlockState st = l.getBlockState(tete);
                if (!st.getCollisionShape(l, tete).isEmpty() && st.getDestroySpeed(l, tete) >= 0 && !Monde.construit(l, tete)) {
                    if (!tete.equals(fouille)) { fouille = tete; progres = 0; }
                    t.getLookControl().setLookAt(Vec3.atCenterOf(tete));
                    if (age % 5 == 0) { t.swing(InteractionHand.MAIN_HAND); Sons.coup(t, tete, st); }
                    if (++progres >= duree(t, st, tete)) { recolter(t, tete); fouille = null; }
                    return age > 200 ? echec("perdu") : Etat.EN_COURS;
                }
            }
            // En feu sans eau qu'il puisse atteindre : il renonce (pause de 5 s), pour rester libre de fuir ou de se défendre.
            if (eau && refuge == null) { t.sansRefugeJusqua = t.tickCount + 100; return echec("sans_eau"); }
            boolean peril = eau ? t.isOnFire() && !t.isInWaterRainOrBubble() || t.enPeril() : t.enPeril();
            if (!peril && (eau || age > 40 || refuge == null)) return Etat.OK; // hors de danger (éteint) : il ne s'attarde pas
            if (age % 20 == 0) { // une fois par seconde, pas plus
                if (age > 0 && peril) choisir(t);
                partir(t);
            }
            boolean nage = t.isInWater() || t.isInLava();
            t.diag = "échappe vers " + (refuge == null ? "rien" : refuge.toShortString() + (toutDroit ? " tout droit" : " par un chemin"));
            if (refuge == null) {
                if (nage) t.getJumpControl().jump(); // dans la lave sans refuge : au moins remonter
                return age > 200 ? echec("perdu") : Etat.EN_COURS;
            }
            Vec3 c = Vec3.atBottomCenterOf(refuge);
            if (t.position().distanceToSqr(c) < 0.5) { if (!peril) return Etat.OK; }
            else if (toutDroit) t.getMoveControl().setWantedPosition(c.x, c.y, c.z, 1.3);
            if (nage && refuge.getY() >= t.getBlockY()) t.getJumpControl().jump();
            return age > 200 ? echec("perdu") : Etat.EN_COURS;
        }
    }

    /** Abat un arbre entier depuis son pied : tout le bois tombe d'un coup quand le pied cède. */
    /**
     * Abat l'arbre entier (même un géant 2 x 2), puis fait tomber ses feuilles naturelles restées en l'air : celles qui ne
     * tiennent plus à aucun bois (le jeu les fait pourrir d'elles-mêmes, lentement). Pousses, pommes et bâtons tombent au
     * sol ; il les ramasse ensuite. Les feuilles posées par un joueur (persistantes) et celles d'un arbre voisin restent.
     */
    static final class Abattre extends Action {
        final BlockPos pied;
        List<BlockPos> bois;
        float progres, total;
        /** Les feuilles à faire tomber, une fois le bois coupé (null avant) ; le tick où les chercher. */
        List<BlockPos> feuilles;
        int abattu = -1;
        /** Où se tenir pour l'abattre : à côté du pied (à 2 blocs pour un tronc 2 x 2), au plus près de lui. */
        BlockPos place;

        static BlockPos place(Travailleur t, BlockPos pied) {
            BlockPos r = null;
            for (Direction d : Direction.Plane.HORIZONTAL)
                for (int k = 1; k <= 2; k++) {
                    BlockPos q = pied.relative(d, k);
                    if (!Monde.debout(t.level(), q)) continue;
                    if (r == null || q.distSqr(t.blockPosition()) < r.distSqr(t.blockPosition())) r = q;
                    break;
                }
            return r;
        }

        Abattre(BlockPos pied) { super("abattre"); this.pied = pied; }

        Etat tick(Travailleur t) {
            ServerLevel lvl = (ServerLevel) t.level();
            if (abattu >= 0) return feuilles(t, lvl);
            if (bois == null) {
                bois = arbre(lvl, pied);
                if (bois == null) return echec("pas_un_arbre");
            }
            if (!lvl.getBlockState(pied).is(BlockTags.LOGS)) return Etat.OK;
            if (!aPortee(t, pied)) {
                if (coince() || age > 20 * 60) { t.blocInaccessible(pied); return echec("inaccessible"); }
                // Vers une place à côté du tronc : viser le bois lui-même, c'est viser le haut du tronc (le chemin du jeu
                // remonte un bloc plein jusqu'à l'air), hors d'atteinte pour un grand arbre.
                if (place == null) place = place(t, pied);
                marcher(t, place != null ? Vec3.atBottomCenterOf(place) : Vec3.atCenterOf(pied), 1.0);
                return Etat.EN_COURS;
            }
            if (total == 0) for (BlockPos p : bois) total += duree(t, lvl.getBlockState(p), p);
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(pied));
            if (age % 5 == 0) { t.swing(InteractionHand.MAIN_HAND); Sons.coup(t, pied, lvl.getBlockState(pied)); }
            lvl.destroyBlockProgress(t.getId(), pied, Math.min(9, (int) (++progres / total * 10)));
            if (progres < total) return Etat.EN_COURS;
            for (int i = bois.size() - 1; i >= 0; i--)
                if (lvl.getBlockState(bois.get(i)).is(BlockTags.LOGS)) recolter(t, bois.get(i));
            userOutil(t, bois.size());
            lvl.destroyBlockProgress(t.getId(), pied, -1);
            abattu = age;
            return Etat.EN_COURS;
        }

        /**
         * Le bois tombé, les feuilles de l'arbre sentent en quelques ticks qu'elles ne tiennent plus (distance 7, comme dans
         * le jeu) : une seule recherche autour de l'arbre, puis 8 feuilles par tick, du haut vers le bas.
         */
        Etat feuilles(Travailleur t, ServerLevel lvl) {
            if (age - abattu < ATTENTE_FEUILLES) return Etat.EN_COURS;
            if (feuilles == null) {
                feuilles = new ArrayList<>();
                int x0 = pied.getX(), x1 = x0, z0 = pied.getZ(), z1 = z0, y1 = pied.getY();
                for (BlockPos p : bois) { x0 = Math.min(x0, p.getX()); x1 = Math.max(x1, p.getX()); z0 = Math.min(z0, p.getZ()); z1 = Math.max(z1, p.getZ()); y1 = Math.max(y1, p.getY()); }
                for (int x = x0 - 5; x <= x1 + 5; x++)
                    for (int z = z0 - 5; z <= z1 + 5; z++) {
                        if (!lvl.hasChunkAt(new BlockPos(x, pied.getY(), z))) continue;
                        for (int y = pied.getY(); y <= y1 + 5 && feuilles.size() < 800; y++) {
                            BlockPos q = new BlockPos(x, y, z);
                            if (detachee(lvl.getBlockState(q))) feuilles.add(q);
                        }
                    }
                feuilles.sort(java.util.Comparator.comparingInt(BlockPos::getY)); // la fin de la liste (le haut) d'abord
            }
            for (int k = 0; k < 8 && !feuilles.isEmpty(); k++) {
                BlockPos q = feuilles.remove(feuilles.size() - 1);
                if (detachee(lvl.getBlockState(q))) lvl.destroyBlock(q, true, t);
            }
            if (age % 5 == 0) t.swing(InteractionHand.MAIN_HAND);
            return feuilles.isEmpty() ? Etat.OK : Etat.EN_COURS;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.level().destroyBlockProgress(t.getId(), pied, -1);
        }
    }

    /** Ticks laissés aux feuilles pour sentir que leur arbre est tombé (le jeu les recalcule d'un rang par tick). */
    static final int ATTENTE_FEUILLES = 20;

    /** Une feuille naturelle qui ne tient plus à aucun bois (elle pourrirait d'elle-même). */
    static boolean detachee(BlockState st) {
        return st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE;
    }

    /** Un arbre a au plus tant de bûches (un grand sapin ou un grand arbre de la jungle 2 x 2 en a moins de 200), dans ce rayon autour de son pied. */
    static final int BUCHES_MAX = 256, RAYON_ARBRE = 10;

    /** Le bois d'un arbre naturel à partir de son pied (null si ce n'est pas un arbre : maison en rondins…). */
    static List<BlockPos> arbre(ServerLevel lvl, BlockPos pied) {
        if (!lvl.getBlockState(pied).is(BlockTags.LOGS)) return null;
        List<BlockPos> bois = new ArrayList<>();
        Set<BlockPos> vus = new HashSet<>();
        ArrayDeque<BlockPos> file = new ArrayDeque<>(List.of(pied));
        vus.add(pied);
        Block essence = lvl.getBlockState(pied).getBlock();
        int feuilles = 0;
        while (!file.isEmpty() && bois.size() < BUCHES_MAX) {
            BlockPos p = file.poll();
            bois.add(p);
            // Une bûche collée à une construction (planches, vitre, porte, escalier…) : ce n'est pas (que) un arbre, il n'y touche pas.
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (Monde.construit(lvl, q) && !lvl.getBlockState(q).is(net.minecraft.world.level.block.Blocks.BEE_NEST)) return null;
            }
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
                if (vus.contains(q) || q.getY() < pied.getY() || Math.abs(q.getX() - pied.getX()) > RAYON_ARBRE || Math.abs(q.getZ() - pied.getZ()) > RAYON_ARBRE) continue;
                BlockState st = lvl.getBlockState(q);
                // Seulement le bois de la même essence que le pied : un arbre ne mêle pas ses bois (une cabane d'un autre bois reste).
                if (st.is(essence)) { BlockPos im = q.immutable(); vus.add(im); file.add(im); }
                else if (st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) feuilles++;
            }
        }
        return feuilles >= 4 ? bois : null;
    }

    /** Le pied de l'arbre naturel le plus proche du centre, dans un rayon donné. */
    static BlockPos chercherArbre(ServerLevel lvl, BlockPos centre, int rayon) { return chercherArbre(lvl, centre, rayon, null, null); }

    /**
     * Pareil, pour un bois précis (filtre sur le pied de l'arbre, null = n'importe lequel), en sautant les arbres que
     * {@code pour} n'a pas pu atteindre récemment (null = aucun).
     */
    static BlockPos chercherArbre(ServerLevel lvl, BlockPos centre, int rayon, Filtre bois, Travailleur pour) {
        BlockPos meilleur = null;
        double dMin = Double.MAX_VALUE;
        for (int dx = -rayon; dx <= rayon; dx++)
            for (int dz = -rayon; dz <= rayon; dz++) {
                double d = dx * dx + dz * dz;
                if (d >= dMin || d > rayon * rayon) continue;
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                if (!lvl.isLoaded(new BlockPos(x, centre.getY(), z))) continue;
                int haut = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                for (int y = haut; y > haut - 24; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!lvl.getBlockState(p).is(BlockTags.LOGS)) continue;
                    // Le tronc jusqu'en bas, si haut soit-il (un grand sapin dépasse 24 blocs).
                    while (p.getY() > lvl.getMinBuildHeight() && lvl.getBlockState(p.below()).is(BlockTags.LOGS)) p = p.below();
                    if (!lvl.getBlockState(p.below()).is(BlockTags.DIRT)) break;
                    if (bois != null && !bois.bloc(lvl.getBlockState(p))) break;
                    if (pour != null && pour.blocInaccessible(p.asLong())) break;
                    if (arbre(lvl, p) != null) { meilleur = p; dMin = d; }
                    break;
                }
            }
        return meilleur;
    }

    static final GameProfile PROFIL = new GameProfile(UUID.nameUUIDFromBytes("colonie_pnj".getBytes()), "[PNJ]");

    /** Pose un bloc du sac, comme un joueur (les torches vont au mur, les pousses sur la terre…). */
    static final class Poser extends Action {
        final BlockPos pos;
        final Filtre filtre;

        Poser(BlockPos pos, Filtre filtre) { super("poser"); this.pos = pos; this.filtre = filtre; }

        Etat tick(Travailleur t) {
            ServerLevel lvl = (ServerLevel) t.level();
            if (!lvl.getBlockState(pos).canBeReplaced()) return echec("occupe");
            if (!aPortee(t, pos)) {
                if (coince() || age > 20 * 60) return echec("inaccessible");
                marcher(t, Vec3.atCenterOf(pos), 1.0);
                return Etat.EN_COURS;
            }
            ItemStack s = ItemStack.EMPTY;
            for (int i = 0; i < t.inventaire.getContainerSize() && s.isEmpty(); i++) {
                ItemStack c = t.inventaire.getItem(i);
                if (filtre.objet(c) && c.getItem() instanceof BlockItem) s = c;
            }
            if (s.isEmpty()) return echec("pas_d_objet");
            String r = placer(t, pos, s);
            return r == null ? Etat.OK : echec(r);
        }
    }

    /**
     * Pose en pos le bloc de cet objet de son sac, comme un joueur : il faut un appui, et personne à cette place (un joueur,
     * lui-même). Null si c'est fait, sinon la raison.
     */
    static String placer(Travailleur t, BlockPos pos, ItemStack s) {
        ServerLevel lvl = (ServerLevel) t.level();
        Direction face = null;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP})
            if (lvl.getBlockState(pos.relative(d)).isFaceSturdy(lvl, pos.relative(d), d.getOpposite())) { face = d; break; }
        if (face == null) return "rien_pour_tenir";
        FakePlayer fp = FakePlayerFactory.get(lvl, PROFIL);
        fp.moveTo(t.getX(), t.getY(), t.getZ(), t.getYRot(), t.getXRot());
        BlockPos appui = pos.relative(face);
        BlockHitResult clic = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face.getOpposite(), appui, false);
        var r = ((BlockItem) s.getItem()).place(new BlockPlaceContext(lvl, fp, InteractionHand.MAIN_HAND, s, clic));
        t.inventaire.setChanged();
        t.swing(InteractionHand.MAIN_HAND);
        return r.consumesAction() ? null : "impossible";
    }

    // --- Combat -----------------------------------------------------------------------------------

    static final class Attaquer extends Action {
        final LivingEntity cible;
        int recharge;
        /** Il tient un endroit (une porte, un pont) : il ne s'en écarte pas de plus de {@code laisse} blocs pour frapper. */
        Vec3 poste;
        double laisse;
        /** Il a un arc ou une arbalète et des flèches (revu toutes les secondes) ; le recul devant un creeper. */
        boolean arc;
        Vec3 recul;
        /** La ligne de tir, revue seulement au moment de tirer. */
        boolean libre;
        /** Au-delà, il laisse la cible (lancé par la veille ou la chasse de nuit : jusqu'au bout de l'espace de vie). */
        double portee = 32;

        Attaquer(LivingEntity cible) { super("attaquer"); this.cible = cible; mobile = true; }

        Attaquer portee(double p) { portee = p; return this; }

        Attaquer tenir(Vec3 poste, double laisse) { this.poste = poste; this.laisse = laisse; return this; }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (t.geste().equals("tirer_arc")) t.setGeste("");
        }

        Etat tick(Travailleur t) {
            if (!cible.isAlive() || cible.level() != t.level() || t.distanceToSqr(cible) > portee * portee) {
                // Un monstre qu'il vient d'abattre : il le dit (lot 9), pas plus d'une fois toutes les 20 s.
                if (cible.isDeadOrDying() && cible instanceof net.minecraft.world.entity.monster.Enemy && cible.getLastHurtByMob() == t
                        && Cerveau.parfois(t, "monstre_tue", 20)) {
                    if ("guerrier".equals(t.metier)) Besoins.dire(t, "guerrier_monstre_tue", null);
                    else Defense.crier(t, "monstre_abattu", null); // un ouvrier qui s'est battu (son caractère : Defense)
                }
                if (cible.isAlive()) oublier(t); // parti trop loin
                return Etat.OK;
            }
            if (age > 20 * 60) return laisser(t, "trop_long");
            t.getLookControl().setLookAt(cible);
            if (recharge > 0) recharge--;
            if (age % 20 == 0) arc = Guerrier.peutTirer(t);
            double d2 = t.distanceToSqr(cible);
            boolean creeper = cible instanceof net.minecraft.world.entity.monster.Creeper;
            boolean vue = t.getSensing().hasLineOfSight(cible);
            // De loin, à l'arc : il garde ses distances (de loin avec un creeper, qu'il ne va jamais frapper au corps à corps).
            if (arc && d2 < 24 * 24 && (creeper || !t.isWithinMeleeAttackRange(cible))) {
                double pres = creeper ? 7 : 3;
                if (d2 < pres * pres) {
                    if (recul == null || age % 20 == 0 || t.position().distanceToSqr(recul) < 2) recul = DefaultRandomPos.getPosAway(t, 10, 4, cible.position());
                    if (recul != null && (poste == null || recul.distanceToSqr(poste) <= (laisse + 4) * (laisse + 4))) { t.setGeste(""); marcher(t, recul, 1.3); return Etat.EN_COURS; }
                } else if (vue && (recharge > 0 ? libre : (libre = Monde.ligneDeTir(t, cible)))) {
                    t.getNavigation().stop();
                    Guerrier.prendreArc(t);
                    t.setGeste("tirer_arc");
                    if (recharge == 0) recharge = Guerrier.tirer(t, cible);
                    return Etat.EN_COURS;
                }
            }
            if (t.geste().equals("tirer_arc")) t.setGeste("");
            if (t.isWithinMeleeAttackRange(cible) && vue) {
                t.getNavigation().stop();
                if (Guerrier.TIR.objet(t.getMainHandItem())) Guerrier.armer(t);
                if (recharge == 0) {
                    t.swing(InteractionHand.MAIN_HAND);
                    boolean touche = t.doHurtTarget(cible);
                    Sons.frappe(t, touche);
                    if (touche) userOutil(t, 1); // l'arme s'use, comme celle d'un joueur (en plus lent)
                    recharge = (int) Math.max(6, 14 / t.facteurTravail());
                }
            } else {
                // Il tient sa place : le monstre trop loin de son poste, il le laisse (il reviendra s'il approche).
                if (poste != null && cible.position().distanceToSqr(poste) > laisse * laisse) { oublier(t); return Etat.OK; }
                if (coince()) return laisser(t, "inaccessible");
                marcher(t, cible.position(), 1.25);
            }
            return Etat.EN_COURS;
        }

        /** Il n'arrive pas à l'atteindre : il le laisse 30 secondes au lieu de s'acharner (et de tourner autour). */
        Etat laisser(Travailleur t, String r) {
            t.inaccessibles.put(cible.getId(), t.level().getGameTime() + 600);
            oublier(t);
            return echec(r);
        }

        /** Un ouvrier (pas un guerrier) qui laisse un monstre ne revient pas le charger avant 5 minutes (Defense.attaquer). */
        void oublier(Travailleur t) {
            if (!"guerrier".equals(t.metier) && Monde.hostile(cible)) t.inaccessibles.put(cible.getId(), t.level().getGameTime() + 20 * 300);
        }
    }

    // --- Bureau, paie, demandes -------------------------------------------------------------------

    /** Marche jusqu'au poste de travail ; renvoie le poste quand il est à côté, sinon null (et l'état dans raison). Il y pose ses lettres en passant. */
    abstract static class AuBureau extends Action {
        BlockPos bureau;

        AuBureau(String nom) { super(nom); }

        Bureau.Entite arriver(Travailleur t) {
            if (bureau == null) bureau = Bureau.trouver(t);
            if (bureau == null) { raison = "pas_de_bureau"; return null; }
            if (t.position().distanceToSqr(Vec3.atBottomCenterOf(bureau)) > 3.2 * 3.2) {
                if (coince()) raison = "bureau_inaccessible";
                marcher(t, Vec3.atBottomCenterOf(bureau), 1.0);
                return null;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(bureau));
            if (t.level().getBlockEntity(bureau) instanceof Bureau.Entite b) {
                if (!t.lettresADeposer.isEmpty()) { t.swing(InteractionHand.OFF_HAND); Courrier.deposer(t); }
                return b;
            }
            t.bureau = null; // le bureau a disparu
            raison = "pas_de_bureau";
            return null;
        }
    }

    static final class ToucherPaie extends AuBureau {
        ToucherPaie() { super("toucher_paie"); }

        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) {
                if (raison.isEmpty()) return Etat.EN_COURS;
                if (raison.equals("pas_de_bureau")) manque(t, "sans_poste");
                else manque(t, "coince", t.blockPosition().toShortString());
                return Etat.ECHEC;
            }
            long du = t.du();
            boolean jour = t.paieDue();
            if (du <= 0) return Etat.OK;
            if (Reglages.payer(b.caisse, du, b.caisse::addItem, s -> Block.popResource(t.level(), bureau.above(), s))) {
                t.swing(InteractionHand.MAIN_HAND);
                if (t.mode == Travailleur.Mode.GREVE) t.paye(du, jour);
                else { t.paye(du, jour); t.dire("Paie touchée : " + Reglages.texte(du) + ". Merci chef !"); }
                return Etat.OK;
            }
            manque(t, "paie_retard");
            return echec("caisse_vide");
        }

        static void manque(Travailleur t, String type, Object... vals) {
            if (t.mode == Travailleur.Mode.GREVE || !t.paieDue()) return;
            t.impayes++;
            t.changerMoral(Obeissance.RETARD);
            t.prochainePaie = System.currentTimeMillis() + Travailleur.periodePaie();
            if (t.impayes >= Reglages.actuel.paies_impayees_avant_greve) t.commencerGreve();
            else Courrier.ecrire(t, type, vals);
        }
    }

    static final class Deposer extends AuBureau {
        final List<Filtre> garder;

        Deposer(List<Filtre> garder) { super("deposer"); this.garder = garder; }

        /** Range tout au dépôt, sauf une pile de chaque objet à garder (une pioche, des torches, à manger…). */
        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            boolean plein = false, diamants = false, depose = false;
            boolean[] garde = new boolean[garder.size()];
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (s.isEmpty()) continue;
                int k = -1;
                for (int j = 0; j < garder.size() && k < 0; j++) if (!garde[j] && garder.get(j).objet(s)) k = j;
                if (k >= 0) { garde[k] = true; continue; }
                int avant = s.getCount();
                // Ce qu'il livre compte pour la première quantité à livrer de cet objet.
                Travailleur.Mission credit = null;
                for (Travailleur.Mission mi : t.missions) {
                    Metier.Commande ci = Metier.catalogue(t.metier, mi.id);
                    if (mi.quantite() && mi.livre < mi.nombre && ci != null && ci.objet != null && ci.objet.objet(s)) { credit = mi; break; }
                }
                boolean diamant = s.is(net.minecraft.world.item.Items.DIAMOND);
                ItemStack reste = b.ranger(s);
                t.inventaire.setItem(i, reste);
                if (diamant && reste.getCount() < avant) diamants = true;
                if (reste.getCount() < avant) depose = true;
                if (credit != null) credit.livre += avant - reste.getCount();
                if (!reste.isEmpty()) plein = true;
            }
            t.swing(InteractionHand.MAIN_HAND);
            if (depose) Sons.depot(t, b.getBlockPos()); // le sac vidé ne sonne que s'il a vraiment laissé quelque chose
            t.nettoyerMissions();
            if (diamants) Humeur.diamantsRendus(t); // celui qui ne triche pas s'en vante
            return plein ? echec("depot_plein") : Etat.OK;
        }
    }

    static final class Prendre extends AuBureau {
        final Filtre filtre;
        final int nombre;

        /** Au plus tant de chaque sorte d'abord (0 : sans compter), puis le reste de n'importe laquelle. */
        final int parSorte;

        Prendre(Filtre filtre, int nombre) { this(filtre, nombre, 0); }

        Prendre(Filtre filtre, int nombre, int parSorte) { super("prendre"); this.filtre = filtre; this.nombre = nombre; this.parSorte = parSorte; }

        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            List<ItemStack> pris = new ArrayList<>();
            int reste = nombre;
            if (parSorte > 0) {
                java.util.Set<String> sortes = new java.util.LinkedHashSet<>();
                for (int i = 0; i < b.depot.getContainerSize(); i++)
                    if (filtre.objet(b.depot.getItem(i))) sortes.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b.depot.getItem(i).getItem()).toString());
                for (String id : sortes) {
                    if (reste <= 0) break;
                    for (ItemStack x : b.prendre(Filtre.de(id), Math.min(parSorte, reste))) { reste -= x.getCount(); pris.add(x); }
                }
            }
            if (reste > 0) pris.addAll(b.prendre(filtre, reste));
            pris.forEach(t::ranger);
            return pris.isEmpty() ? echec("rien_au_depot") : Etat.OK;
        }
    }

    /** Sort de son sac un objet de ce filtre (un seul), ou rien. */
    static ItemStack sortir(Travailleur t, Filtre f) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++)
            if (f.objet(t.inventaire.getItem(i))) { t.inventaire.setChanged(); return t.inventaire.getItem(i).split(1); }
        return ItemStack.EMPTY;
    }

    /** Ce que donne la recette d'artisanat d'un seul objet (une bûche : 4 planches de son bois), ou rien. */
    static ItemStack recette(ServerLevel lvl, ItemStack s) {
        var entree = net.minecraft.world.item.crafting.CraftingInput.of(1, 1, List.of(s));
        return lvl.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, entree, lvl)
                .map(r -> r.value().assemble(entree, lvl.registryAccess())).orElse(ItemStack.EMPTY);
    }

    /**
     * Fait des planches au poste, comme à l'établi : une bûche (de son sac, sinon du dépôt) toutes les demi-secondes, et les
     * planches vont au dépôt. Rien ne se crée ni ne se perd : c'est la recette du jeu (1 bûche, 4 planches du même bois).
     */
    static final class Planches extends AuBureau {
        final Filtre bois;
        final int voulu;
        int faites;

        Planches(Filtre bois, int voulu) { super("planches"); this.bois = bois; this.voulu = voulu; }

        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            if (age % 10 != 0) return Etat.EN_COURS;
            if (faites >= voulu) return Etat.OK;
            ItemStack buche = sortir(t, bois);
            if (buche.isEmpty()) {
                List<ItemStack> pris = b.prendre(bois, 1);
                if (pris.isEmpty()) return Etat.OK; // plus de bûches : il a fait ce qu'il a pu
                buche = pris.get(0);
            }
            ItemStack planches = recette((ServerLevel) t.level(), buche);
            if (planches.isEmpty()) { t.ranger(b.ranger(buche)); return echec("pas_de_recette"); }
            faites += planches.getCount();
            ItemStack reste = b.ranger(planches);
            if (!reste.isEmpty()) t.ranger(reste);
            t.swing(InteractionHand.MAIN_HAND);
            t.level().playSound(null, t.blockPosition(), net.minecraft.sounds.SoundEvents.WOOD_BREAK, net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.2f);
            return reste.isEmpty() ? Etat.EN_COURS : echec("depot_plein");
        }
    }

    /**
     * Du charbon de bois au fourneau près du poste : il prend au dépôt les bûches et le combustible (charbon, sinon des bûches
     * en plus), charge le fourneau, attend près de lui en reprenant le charbon à mesure, puis range tout au dépôt.
     */
    static final class Charbon extends AuBureau {
        static final Filtre COMBUSTIBLE = Filtre.de("minecraft:coal|minecraft:charcoal");
        final Filtre bois;
        final int voulu;
        BlockPos four;
        int etape, recu, arret;
        /** Ce qu'il a mis à cuire et à brûler (il ne reprend que ça). */
        ItemStack mis = ItemStack.EMPTY, feuMis = ItemStack.EMPTY;

        Charbon(Filtre bois, int voulu) { super("charbon"); this.bois = bois; this.voulu = voulu; }

        /** Un vrai fourneau à 8 blocs du poste au plus (le plus proche), ou null. Une seule recherche, à la demande. */
        static BlockPos fourneau(Level l, BlockPos poste) {
            BlockPos r = null;
            boolean libre = false;
            for (BlockPos q : BlockPos.betweenClosed(poste.offset(-8, -3, -8), poste.offset(8, 3, 8)))
                if (l.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f) {
                    boolean v = vide(f); // un fourneau vide d'abord ; sinon le plus proche (il dira qu'il est occupé)
                    if (r == null || v && !libre || v == libre && q.distSqr(poste) < r.distSqr(poste)) { r = q.immutable(); libre = v; }
                }
            return r;
        }

        /** Rien dedans : ni à cuire, ni combustible, ni rien de cuit. Il ne se sert jamais d'un fourneau où un autre a mis quelque chose. */
        static boolean vide(net.minecraft.world.level.block.entity.FurnaceBlockEntity f) {
            return f.getItem(0).isEmpty() && f.getItem(1).isEmpty() && f.getItem(2).isEmpty();
        }

        Etat tick(Travailleur t) {
            Level l = t.level();
            switch (etape) {
                case 0 -> { // au poste : trouver le fourneau, prendre les bûches et le combustible
                    Bureau.Entite b = arriver(t);
                    if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
                    four = fourneau(l, bureau);
                    if (four == null) return echec("pas_de_fourneau");
                    int manque = voulu - t.compter(bois);
                    if (manque > 0) b.prendre(bois, manque).forEach(t::ranger);
                    int a = Math.min(voulu, t.compter(bois));
                    if (a == 0) return echec("pas_de_bois");
                    // Le charbon fait cuire 8 bûches ; sans charbon, il brûle des bûches (une et demie chacune).
                    int charbon = (a + 7) / 8 - t.compter(COMBUSTIBLE);
                    if (charbon > 0) b.prendre(COMBUSTIBLE, charbon).forEach(t::ranger);
                    t.swing(InteractionHand.MAIN_HAND);
                    etape = 1;
                }
                case 1 -> { // charger le fourneau
                    if (!(l.getBlockEntity(four) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f)) return echec("pas_de_fourneau");
                    if (!aPortee(t, four)) {
                        if (coince() || age > 20 * 60) return echec("inaccessible");
                        marcher(t, Vec3.atCenterOf(four), 1.0);
                        return Etat.EN_COURS;
                    }
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(Vec3.atCenterOf(four));
                    ItemStack dedans = f.getItem(0), feu = f.getItem(1);
                    if (!vide(f)) return echec("four_occupe");
                    int nb = Math.min(voulu, t.compter(bois));
                    int charbon = t.compter(COMBUSTIBLE);
                    int cuire = charbon > 0 ? nb : nb * 3 / 5; // sans charbon : 3 bûches cuites pour 2 brûlées
                    if (cuire == 0) return echec("pas_de_combustible");
                    ItemStack type = ItemStack.EMPTY;
                    for (int i = 0; i < cuire; i++) {
                        ItemStack s = dedans.isEmpty() ? sortir(t, bois) : sortirPareil(t, dedans);
                        if (s.isEmpty()) break;
                        if (dedans.isEmpty()) dedans = s; else dedans.grow(1);
                        if (dedans.getCount() >= dedans.getMaxStackSize()) break;
                    }
                    f.setItem(0, dedans);
                    Filtre brule = charbon > 0 ? COMBUSTIBLE : bois;
                    int aBruler = charbon > 0 ? (dedans.getCount() + 7) / 8 : (dedans.getCount() * 2 + 2) / 3;
                    for (int i = 0; i < aBruler && (feu.isEmpty() || feu.getCount() < feu.getMaxStackSize()); i++) {
                        ItemStack s = feu.isEmpty() ? sortir(t, brule) : sortirPareil(t, feu);
                        if (s.isEmpty()) break;
                        if (feu.isEmpty()) feu = s; else feu.grow(1);
                    }
                    f.setItem(1, feu);
                    mis = dedans.copy();
                    feuMis = feu.copy();
                    f.setChanged();
                    t.swing(InteractionHand.MAIN_HAND);
                    Sons.four(t, f.getBlockPos());
                    etape = 2;
                }
                case 2 -> { // il attend près du fourneau et reprend le charbon à mesure
                    if (!(l.getBlockEntity(four) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f)) return echec("pas_de_fourneau");
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(Vec3.atCenterOf(four));
                    if (age % 20 != 0) return Etat.EN_COURS;
                    // Il ne reprend que ce qui vient de lui : le charbon de bois, ses bûches pas cuites, son combustible pas brûlé.
                    ItemStack fait = f.getItem(2);
                    if (fait.is(net.minecraft.world.item.Items.CHARCOAL) && recu < mis.getCount()) {
                        ItemStack pris = fait.split(Math.min(fait.getCount(), mis.getCount() - recu));
                        recu += pris.getCount();
                        t.ranger(pris);
                        f.setChanged();
                        t.swing(InteractionHand.MAIN_HAND);
                    }
                    boolean allume = f.getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
                    arret = allume ? 0 : arret + 1;
                    if (f.getItem(0).isEmpty() || arret >= 4 || recu >= mis.getCount()) {
                        // Fini, ou le feu s'est éteint : il reprend ses bûches pas cuites et son combustible (rien ne se perd).
                        for (int k = 0; k < 2; k++) {
                            ItemStack a = f.getItem(k), sien = k == 0 ? mis : feuMis;
                            if (!a.isEmpty() && ItemStack.isSameItemSameComponents(a, sien)) { t.ranger(a.split(Math.min(a.getCount(), sien.getCount()))); f.setChanged(); }
                        }
                        etape = 3;
                    }
                }
                default -> { // tout au dépôt
                    Bureau.Entite b = arriver(t);
                    if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
                    for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                        ItemStack s = t.inventaire.getItem(i);
                        if (s.is(net.minecraft.world.item.Items.CHARCOAL)) t.inventaire.setItem(i, b.ranger(s));
                    }
                    return Etat.OK;
                }
            }
            return Etat.EN_COURS;
        }

        /** Sort de son sac un objet identique à celui-ci (même bois), ou rien. */
        static ItemStack sortirPareil(Travailleur t, ItemStack modele) {
            for (int i = 0; i < t.inventaire.getContainerSize(); i++)
                if (ItemStack.isSameItemSameComponents(t.inventaire.getItem(i), modele)) { t.inventaire.setChanged(); return t.inventaire.getItem(i).split(1); }
            return ItemStack.EMPTY;
        }
    }

    /**
     * Il lui manque quelque chose : si son chef est dans les parages, il va le voir et le lui demande de vive voix ;
     * sinon, il lui écrit (une fois) et l'attend au poste.
     */
    static final class Demander extends Action {
        final String type;
        final Object[] vals;
        final Filtre filtre;
        final int nombre;
        String texte;
        boolean ecrit, annonce;

        Demander(String type, Filtre filtre, int nombre, Object... vals) { super("demander"); this.type = type; this.filtre = filtre; this.nombre = nombre; this.vals = vals; }

        Etat tick(Travailleur t) {
            if (texte == null) texte = Messages.corps(t, type, vals);
            t.besoin = texte;
            if (t.compter(filtre) >= nombre) {
                t.besoin = "";
                t.dire(Messages.corps(t, "dit_merci_objet"));
                return Etat.OK;
            }
            if (age > 20 * 150) return echec("pas_recu");
            ServerPlayer c = t.chefJoueur();
            if (c != null && c.level() == t.level() && t.distanceTo(c) < 96) {
                if (t.distanceTo(c) > 3) { mobile = true; marcher(t, c.position(), 1.15); }
                else {
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(c);
                    if (!annonce) { t.dire(texte + " (accroupi + clic droit avec l'objet en main pour me le donner)"); annonce = true; }
                }
            } else {
                if (!ecrit && Cerveau.parfois(t, "demande_" + type, 1800)) Courrier.ecrire(t, type, vals);
                ecrit = true;
                BlockPos bureau = t.bureau;
                if (bureau != null && t.position().distanceToSqr(Vec3.atBottomCenterOf(bureau)) > 16) { mobile = false; marcher(t, Vec3.atBottomCenterOf(bureau), 1.0); }
                else if (bureau != null && !t.lettresADeposer.isEmpty()) { t.swing(InteractionHand.OFF_HAND); Courrier.deposer(t); }
            }
            return Etat.EN_COURS;
        }
    }

    /** Il apporte ses lettres au poste, une lettre à la main, et les y pose. */
    static final class PorterLettres extends AuBureau {
        PorterLettres() { super("porter_lettres"); }

        Etat tick(Travailleur t) {
            if (t.lettresADeposer.isEmpty()) return Etat.OK;
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            return Etat.OK; // arriver() les a posées
        }
    }

    /** Il passe au poste lire les lettres de son chef : il la tient un moment, la lit, obéit et répond. */
    static final class LireLettres extends AuBureau {
        int lecture;

        LireLettres() { super("lire_lettres"); }

        Etat tick(Travailleur t) {
            if (t.lettresALire.isEmpty()) return Etat.OK;
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            t.getLookControl().setLookAt(t.getX(), t.getEyeY() - 1, t.getZ()); // les yeux sur la lettre
            if (++lecture < 50) return Etat.EN_COURS;
            lecture = 0;
            t.lireLettre(t.lettresALire.remove(0));
            return t.lettresALire.isEmpty() ? Etat.OK : Etat.EN_COURS;
        }
    }

    /** Va se reposer chez lui, dans son lit s'il en a un (sinon à côté du bureau) ; loin de chez lui, en expédition, il campe sur place. */
    static final class Reposer extends Action {
        BlockPos lit, but;
        /** Chez lui, il a cherché un lit et n'en a trouvé aucun (lot 5 : il le dit, Besoins.veiller). */
        boolean sansLit;

        Reposer() { super("se_reposer"); }

        Etat tick(Travailleur t) {
            if (but == null) {
                if (t.isSleeping()) return age >= 600 ? Etat.OK : Etat.EN_COURS;
                boolean camp = t.bureau == null || !t.chezLui(t.blockPosition());
                lit = camp ? null : t.monLit();
                sansLit = !camp && lit == null && t.litIntrouvable;
                but = camp ? t.blockPosition() : lit != null ? lit : t.bureau;
            }
            if (!t.isSleeping() && t.position().distanceToSqr(Vec3.atBottomCenterOf(but)) > 2.5 * 2.5) {
                if (coince()) { but = t.blockPosition(); lit = null; }
                else { marcher(t, Vec3.atBottomCenterOf(but), 1.0); return Etat.EN_COURS; }
            }
            t.getNavigation().stop();
            if (lit != null && !t.isSleeping() && t.level().getBlockState(lit).getBlock() instanceof BedBlock) {
                if (!t.level().getBlockState(lit).getValue(BedBlock.OCCUPIED)) t.startSleeping(lit);
                else if (age % 100 == 0) { // un autre dort dans son lit
                    net.minecraft.world.entity.LivingEntity qui = Besoins.dormeur(t, lit);
                    if (qui != null) { Besoins.litVole(t, qui); if (qui instanceof Travailleur) lit = null; }
                }
            }
            return age >= 600 ? Etat.OK : Etat.EN_COURS; // il refait le point toutes les 30 s
        }
    }

    static final class Ramasser extends Action {
        final double rayon;
        final Filtre filtre;
        /** Où ramasser (null : autour de lui, dans le rayon). */
        final net.minecraft.world.phys.AABB zone;
        ItemEntity proche;

        Ramasser(double rayon, Filtre filtre) { this(rayon, filtre, null); }

        Ramasser(double rayon, Filtre filtre, net.minecraft.world.phys.AABB zone) { super("ramasser"); this.rayon = rayon; this.filtre = filtre; this.zone = zone; }

        Etat tick(Travailleur t) {
            if (age > 20 * 40) return Etat.OK;
            // L'objet le plus proche : cherché quand le précédent est ramassé, et une fois par seconde (pas à chaque tick).
            if (proche == null || !proche.isAlive() || age % 20 == 0) {
                proche = null;
                for (ItemEntity ie : t.level().getEntitiesOfClass(ItemEntity.class, zone != null ? zone : t.getBoundingBox().inflate(rayon)))
                    if (filtre.objet(ie.getItem()) && t.inventaire.canAddItem(ie.getItem()) && (proche == null || t.distanceToSqr(ie) < t.distanceToSqr(proche))) proche = ie;
            }
            if (proche == null) return Etat.OK;
            if (coince()) return echec("inaccessible");
            marcher(t, proche.position(), 1.0);
            return Etat.EN_COURS;
        }
    }
}

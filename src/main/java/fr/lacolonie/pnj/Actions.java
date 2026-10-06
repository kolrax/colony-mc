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
        boolean nourriture, tout;
        static final Map<String, Filtre> CACHE = new ConcurrentHashMap<>();

        public static Filtre de(String s) { return CACHE.computeIfAbsent(s == null ? "" : s.trim(), Filtre::new); }

        private Filtre(String s) {
            texte = s;
            for (String p : s.split("\\|")) {
                p = p.trim();
                if (p.equals("nourriture")) nourriture = true;
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

        Action(String nom) { this.nom = nom; }

        abstract Etat tick(Travailleur t);

        void fin(Travailleur t) {
            t.getNavigation().stop();
            t.setSprinting(false);
        }

        /** Marche vers la cible, à n'importe quelle distance, sans foncer dans un cul-de-sac (voir {@link Itineraire}). */
        void marcher(Travailleur t, Vec3 cible, double vitesse) {
            if (route == null || route.but.distanceToSqr(cible) > 16) route = new Itineraire(t, cible);
            Vec3 p = t.position();
            // Il n'entre pas dans un coin que le monde n'a pas fini de préparer (terrain neuf en cours de génération) :
            // il s'y figerait. Il attend au bord, le temps que le serveur le termine.
            Vec3 cap = cible.subtract(p).multiply(1, 0, 1);
            if (cap.lengthSqr() > 1 && t.level() instanceof net.minecraft.server.level.ServerLevel sl
                    && !sl.isPositionEntityTicking(BlockPos.containing(p.add(cap.normalize().scale(5))))) {
                t.getNavigation().stop();
                sansProgres++; // au bout de 2 minutes d'attente, il se dira coincé
                immobile = 0;
                dernier = null;
                return;
            }
            route.avancer(t, vitesse, age);
            double d = p.distanceTo(cible);
            if (d < meilleure - 2 || route.occupe()) { meilleure = Math.min(meilleure, d); sansProgres = 0; }
            else sansProgres++;
            if (age % 40 == 0) {
                immobile = dernier != null && dernier.distanceToSqr(p) < 0.25 && !route.occupe() ? immobile + 40 : 0;
                dernier = p;
            }
        }

        /** Coincé : plus aucun passage vers le but, immobile depuis 15 s, ou sans se rapprocher depuis 2 minutes. */
        boolean coince() { return route != null && route.bloque || immobile >= 300 || sansProgres >= 2400; }

        Etat echec(String r) { raison = r; return Etat.ECHEC; }
    }

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
            for (ItemStack d : Block.getDrops(st, lvl, p, lvl.getBlockEntity(p), t, outil)) t.ranger(d);
        lvl.destroyBlock(p, false, t);
    }

    static void userOutil(Travailleur t, int n) {
        ItemStack outil = t.getMainHandItem();
        if (!outil.isEmpty() && outil.isDamageableItem()) outil.hurtAndBreak(n, t, EquipmentSlot.MAINHAND);
    }

    // --- Déplacements -----------------------------------------------------------------------------

    static final class Aller extends Action {
        Vec3 cible;
        final double portee;
        /** La cible est au niveau du sol, qu'on ne connaît qu'une fois le coin chargé (exploration au loin). */
        final boolean auSol;

        Aller(Vec3 cible, double portee) { this(cible, portee, false); }

        Aller(Vec3 cible, double portee, boolean auSol) { super("aller"); this.cible = cible; this.portee = Math.max(0.8, portee); this.auSol = auSol; }

        Etat tick(Travailleur t) {
            if (auSol && age % 20 == 0) {
                int x = (int) Math.floor(cible.x), z = (int) Math.floor(cible.z);
                if (t.level().hasChunkAt(x, z)) cible = new Vec3(cible.x, t.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), cible.z);
            }
            if (t.position().distanceToSqr(cible) <= portee * portee) return Etat.OK;
            if (coince()) return echec("bloque");
            if (age > 20 * 60 * 15) return echec("trop_loin");
            marcher(t, cible, 1.0);
            return Etat.EN_COURS;
        }
    }

    static final class SuivreChef extends Action {
        SuivreChef() { super("suivre"); }

        Etat tick(Travailleur t) {
            ServerPlayer c = t.chefJoueur();
            if (c == null || c.level() != t.level()) return echec("chef_absent");
            double d = t.distanceTo(c);
            t.getLookControl().setLookAt(c);
            if (d <= 3.5) return Etat.OK;
            if (age > 20 * 30) return Etat.OK;
            marcher(t, c.position(), d > 12 ? 1.3 : 1.1);
            return Etat.EN_COURS;
        }
    }

    static final class Fuir extends Action {
        final Entity danger;
        Vec3 refuge;

        Fuir(Entity danger) { super("fuir"); this.danger = danger; }

        Etat tick(Travailleur t) {
            if (refuge == null) refuge = DefaultRandomPos.getPosAway(t, 16, 7, danger.position());
            if (refuge == null || age > 20 * 8) return Etat.OK;
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

        Casser(BlockPos pos) { super("casser"); this.pos = pos; }

        Etat tick(Travailleur t) {
            ServerLevel lvl = (ServerLevel) t.level();
            BlockState st = lvl.getBlockState(pos);
            if (st.isAir() || !st.getFluidState().isEmpty() && st.canBeReplaced()) return Etat.OK;
            if (st.getDestroySpeed(lvl, pos) < 0) return echec("incassable");
            if (lvl.getBlockEntity(pos) != null) return echec("contenant");
            if (!aPortee(t, pos)) {
                if (coince() || age > 20 * 60) return echec("inaccessible");
                marcher(t, Vec3.atCenterOf(pos), 1.0);
                return Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            progres += 1;
            float total = duree(t, st, pos);
            if (age % 5 == 0) t.swing(InteractionHand.MAIN_HAND);
            lvl.destroyBlockProgress(t.getId(), pos, Math.min(9, (int) (progres / total * 10)));
            if (progres < total) return Etat.EN_COURS;
            recolter(t, pos);
            if (st.getDestroySpeed(lvl, pos) > 0) userOutil(t, 1);
            return Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.level().destroyBlockProgress(t.getId(), pos, -1);
        }
    }

    /** Abat un arbre entier depuis son pied : tout le bois tombe d'un coup quand le pied cède. */
    static final class Abattre extends Action {
        final BlockPos pied;
        List<BlockPos> bois;
        float progres, total;

        Abattre(BlockPos pied) { super("abattre"); this.pied = pied; }

        Etat tick(Travailleur t) {
            ServerLevel lvl = (ServerLevel) t.level();
            if (bois == null) {
                bois = arbre(lvl, pied);
                if (bois == null) return echec("pas_un_arbre");
            }
            if (!lvl.getBlockState(pied).is(BlockTags.LOGS)) return Etat.OK;
            if (!aPortee(t, pied)) {
                if (coince() || age > 20 * 60) return echec("inaccessible");
                marcher(t, Vec3.atCenterOf(pied), 1.0);
                return Etat.EN_COURS;
            }
            if (total == 0) for (BlockPos p : bois) total += duree(t, lvl.getBlockState(p), p);
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(pied));
            if (age % 5 == 0) t.swing(InteractionHand.MAIN_HAND);
            lvl.destroyBlockProgress(t.getId(), pied, Math.min(9, (int) (++progres / total * 10)));
            if (progres < total) return Etat.EN_COURS;
            for (int i = bois.size() - 1; i >= 0; i--)
                if (lvl.getBlockState(bois.get(i)).is(BlockTags.LOGS)) recolter(t, bois.get(i));
            userOutil(t, bois.size());
            return Etat.OK;
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            t.level().destroyBlockProgress(t.getId(), pied, -1);
        }
    }

    /** Le bois d'un arbre naturel à partir de son pied (null si ce n'est pas un arbre : maison en rondins…). */
    static List<BlockPos> arbre(ServerLevel lvl, BlockPos pied) {
        if (!lvl.getBlockState(pied).is(BlockTags.LOGS)) return null;
        List<BlockPos> bois = new ArrayList<>();
        Set<BlockPos> vus = new HashSet<>();
        ArrayDeque<BlockPos> file = new ArrayDeque<>(List.of(pied));
        vus.add(pied);
        int feuilles = 0;
        while (!file.isEmpty() && bois.size() < 160) {
            BlockPos p = file.poll();
            bois.add(p);
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
                if (vus.contains(q) || q.getY() < pied.getY() || Math.abs(q.getX() - pied.getX()) > 8 || Math.abs(q.getZ() - pied.getZ()) > 8) continue;
                BlockState st = lvl.getBlockState(q);
                if (st.is(BlockTags.LOGS)) { BlockPos im = q.immutable(); vus.add(im); file.add(im); }
                else if (st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) feuilles++;
            }
        }
        return feuilles >= 4 ? bois : null;
    }

    /** Le pied de l'arbre naturel le plus proche du centre, dans un rayon donné. */
    static BlockPos chercherArbre(ServerLevel lvl, BlockPos centre, int rayon) { return chercherArbre(lvl, centre, rayon, null); }

    /** Pareil, pour un bois précis (filtre sur le pied de l'arbre, null = n'importe lequel). */
    static BlockPos chercherArbre(ServerLevel lvl, BlockPos centre, int rayon, Filtre bois) {
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
                    if (!lvl.getBlockState(p.below()).is(BlockTags.DIRT)) continue;
                    if (bois != null && !bois.bloc(lvl.getBlockState(p))) break;
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
            Direction face = null;
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP})
                if (lvl.getBlockState(pos.relative(d)).isFaceSturdy(lvl, pos.relative(d), d.getOpposite())) { face = d; break; }
            if (face == null) return echec("rien_pour_tenir");
            FakePlayer fp = FakePlayerFactory.get(lvl, PROFIL);
            fp.moveTo(t.getX(), t.getY(), t.getZ(), t.getYRot(), t.getXRot());
            BlockPos appui = pos.relative(face);
            BlockHitResult clic = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face.getOpposite(), appui, false);
            var r = ((BlockItem) s.getItem()).place(new BlockPlaceContext(lvl, fp, InteractionHand.MAIN_HAND, s, clic));
            t.inventaire.setChanged();
            t.swing(InteractionHand.MAIN_HAND);
            return r.consumesAction() ? Etat.OK : echec("impossible");
        }
    }

    // --- Combat -----------------------------------------------------------------------------------

    static final class Attaquer extends Action {
        final LivingEntity cible;
        int recharge;

        Attaquer(LivingEntity cible) { super("attaquer"); this.cible = cible; }

        Etat tick(Travailleur t) {
            if (!cible.isAlive() || cible.level() != t.level() || t.distanceToSqr(cible) > 32 * 32) return Etat.OK;
            if (age > 20 * 60) return echec("trop_long");
            t.getLookControl().setLookAt(cible);
            if (recharge > 0) recharge--;
            if (t.isWithinMeleeAttackRange(cible) && t.getSensing().hasLineOfSight(cible)) {
                t.getNavigation().stop();
                if (recharge == 0) {
                    t.swing(InteractionHand.MAIN_HAND);
                    t.doHurtTarget(cible);
                    recharge = (int) Math.max(6, 14 / t.facteurTravail());
                }
            } else {
                if (coince()) return echec("inaccessible");
                marcher(t, cible.position(), 1.25);
            }
            return Etat.EN_COURS;
        }
    }

    // --- Bureau, paie, demandes -------------------------------------------------------------------

    /** Marche jusqu'au bureau du chef ; renvoie le bureau quand il est à côté, sinon null (et l'état dans raison). */
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
            if (t.level().getBlockEntity(bureau) instanceof Bureau.Entite b) return b;
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
                manque(t, raison.equals("pas_de_bureau") ? "Je n'ai pas de bureau où toucher ma paie : rattache-moi à un bureau." : "Je n'arrive pas à rejoindre mon bureau pour toucher ma paie.");
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
            manque(t, "La caisse du bureau est vide : il me faut " + Reglages.texte(du) + ".");
            return echec("caisse_vide");
        }

        static void manque(Travailleur t, String message) {
            if (t.mode == Travailleur.Mode.GREVE || !t.paieDue()) return;
            t.impayes++;
            t.prochainePaie = System.currentTimeMillis() + Travailleur.periodePaie();
            if (t.impayes >= Reglages.actuel.paies_impayees_avant_greve) t.commencerGreve();
            else t.direAuChef(message);
        }
    }

    static final class Deposer extends AuBureau {
        final List<Filtre> garder;

        Deposer(List<Filtre> garder) { super("deposer"); this.garder = garder; }

        /** Range tout au dépôt, sauf une pile de chaque objet à garder (une pioche, des torches, à manger…). */
        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            boolean plein = false;
            boolean[] garde = new boolean[garder.size()];
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (s.isEmpty()) continue;
                int k = -1;
                for (int j = 0; j < garder.size() && k < 0; j++) if (!garde[j] && garder.get(j).objet(s)) k = j;
                if (k >= 0) { garde[k] = true; continue; }
                int avant = s.getCount();
                boolean commande = t.objetCommande().objet(s);
                ItemStack reste = b.ranger(s);
                t.inventaire.setItem(i, reste);
                if (commande) t.commandeLivre += avant - reste.getCount();
                if (!reste.isEmpty()) plein = true;
            }
            t.swing(InteractionHand.MAIN_HAND);
            return plein ? echec("depot_plein") : Etat.OK;
        }
    }

    static final class Prendre extends AuBureau {
        final Filtre filtre;
        final int nombre;

        Prendre(Filtre filtre, int nombre) { super("prendre"); this.filtre = filtre; this.nombre = nombre; }

        Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Etat.EN_COURS : Etat.ECHEC;
            var pris = b.prendre(filtre, nombre);
            pris.forEach(t::ranger);
            return pris.isEmpty() ? echec("rien_au_depot") : Etat.OK;
        }
    }

    /** Va voir son chef pour lui demander quelque chose ; s'il n'est pas là, l'attend au bureau. */
    static final class Demander extends Action {
        final String texte;
        final Filtre filtre;
        final int nombre;
        boolean prevenu, annonce;

        Demander(String texte, Filtre filtre, int nombre) { super("demander"); this.texte = texte; this.filtre = filtre; this.nombre = nombre; }

        Etat tick(Travailleur t) {
            t.besoin = texte;
            if (t.compter(filtre) >= nombre) {
                t.besoin = "";
                t.dire("Merci chef !");
                return Etat.OK;
            }
            if (age > 20 * 150) return echec("pas_recu");
            ServerPlayer c = t.chefJoueur();
            if (c != null && c.level() == t.level() && t.distanceTo(c) < 96) {
                if (!prevenu) { t.direAuChef(texte + " J'arrive te voir."); prevenu = true; }
                if (t.distanceTo(c) > 3) marcher(t, c.position(), 1.15);
                else {
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(c);
                    if (!annonce) { t.dire(texte + " (accroupi + clic droit avec l'objet en main pour me le donner)"); annonce = true; }
                }
            } else {
                BlockPos bureau = t.bureau;
                if (bureau != null && t.position().distanceToSqr(Vec3.atBottomCenterOf(bureau)) > 16) marcher(t, Vec3.atBottomCenterOf(bureau), 1.0);
            }
            return Etat.EN_COURS;
        }
    }

    /** Va se reposer chez lui, dans son lit s'il en a un (sinon à côté du bureau) ; loin de chez lui, en expédition, il campe sur place. */
    static final class Reposer extends Action {
        BlockPos lit, but;

        Reposer() { super("se_reposer"); }

        Etat tick(Travailleur t) {
            if (but == null) {
                if (t.isSleeping()) return age >= 600 ? Etat.OK : Etat.EN_COURS;
                boolean camp = t.bureau == null || !t.chezLui(t.blockPosition());
                lit = camp ? null : t.monLit();
                but = camp ? t.blockPosition() : lit != null ? lit : t.bureau;
            }
            if (!t.isSleeping() && t.position().distanceToSqr(Vec3.atBottomCenterOf(but)) > 2.5 * 2.5) {
                if (coince()) { but = t.blockPosition(); lit = null; }
                else { marcher(t, Vec3.atBottomCenterOf(but), 1.0); return Etat.EN_COURS; }
            }
            t.getNavigation().stop();
            if (lit != null && !t.isSleeping() && t.level().getBlockState(lit).getBlock() instanceof BedBlock
                    && !t.level().getBlockState(lit).getValue(BedBlock.OCCUPIED)) t.startSleeping(lit);
            return age >= 600 ? Etat.OK : Etat.EN_COURS; // il refait le point toutes les 30 s
        }
    }

    static final class Ramasser extends Action {
        final double rayon;
        final Filtre filtre;

        Ramasser(double rayon, Filtre filtre) { super("ramasser"); this.rayon = rayon; this.filtre = filtre; }

        Etat tick(Travailleur t) {
            if (age > 20 * 40) return Etat.OK;
            ItemEntity proche = null;
            for (ItemEntity ie : t.level().getEntitiesOfClass(ItemEntity.class, t.getBoundingBox().inflate(rayon)))
                if (filtre.objet(ie.getItem()) && t.inventaire.canAddItem(ie.getItem()) && (proche == null || t.distanceToSqr(ie) < t.distanceToSqr(proche))) proche = ie;
            if (proche == null) return Etat.OK;
            if (coince()) return echec("inaccessible");
            marcher(t, proche.position(), 1.0);
            return Etat.EN_COURS;
        }
    }
}

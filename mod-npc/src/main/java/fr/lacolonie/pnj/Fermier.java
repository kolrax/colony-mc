package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Le fermier (lot 6). Seul, il fait le tour des cultures de son espace de vie, une zone de 16 x 16 par seconde (jamais de
 * grand balayage) : ce qui est mûr (blé, carottes, pommes de terre, betteraves), il le récolte et replante aussitôt la même
 * graine ; la récolte et les graines en trop vont au dépôt à la fin de chaque tour. Les champs que le chef lui a marqués
 * (« plante un champ de blé ici, 9 sur 9 »), il les entretient : il relaboure et ressème les cases vides, et agrandit le
 * champ sur la terre que l'eau arrose. Sur ordre, il récolte tout de suite, ou fait du pain au poste.
 * <p>
 * Jamais : piétiner une terre labourée ({@link #pietiner}), casser une culture pas mûre, toucher aux cultures d'un voisin ou
 * d'une structure ({@link Mineur#interdit}), labourer un bloc construit ou hors de son espace de vie. Rien ne tombe par terre,
 * rien ne se crée : chaque graine semée sort de la récolte ou du sac, le pain suit la recette du jeu.
 */
final class Fermier extends Metier {
    static final Actions.Filtre HOUES = Actions.Filtre.de("#minecraft:hoes");
    static final Actions.Filtre BLE = Actions.Filtre.de("minecraft:wheat");
    static final Actions.Filtre SEAU = Actions.Filtre.de("minecraft:water_bucket");
    /** Au plus tant de champs marqués retenus (le plus ancien part). */
    static final int CHAMPS = 8;
    /** Les zones de son espace de vie, de la plus proche du poste à la plus lointaine. */
    static final List<int[]> ZONES = Cultures.zones(Travailleur.ESPACE_DE_VIE);

    Fermier() {
        super("fermier", "Fermier", "Cultive les champs autour du poste : récolte le blé, les carottes, les pommes de terre et les betteraves mûrs, "
                + "replante aussitôt, laboure et sème les champs que tu lui marques, et fait du pain.", false, "#minecraft:hoes", "minecraft:wheat_seeds", "nourriture");
        commandes.add(new Commande("tout", "Toutes les récoltes", String.join("|", Cultures.TOUTES.stream().map(Cultures.Culture::recolte).toList())));
        for (Cultures.Culture c : Cultures.TOUTES) commandes.add(new Commande(c.id(), c.nom(), c.recolte()));
    }

    /** Où en est son tour des champs (pas sauvegardé : après un redémarrage, il reprend au poste). */
    static final class Tour {
        int zone, recoltees, champ;
        long champsVus;
    }

    static final Map<Travailleur, Tour> TOURS = new WeakHashMap<>();

    @Override
    void travailler(Travailleur t) {
        Tour tour = TOURS.computeIfAbsent(t, k -> new Tour());
        ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);

        // Sa houe, prise au dépôt, s'il a des champs à labourer (pour récolter, les mains suffisent).
        if (!champs.isEmpty() && outilAuDepot(t, HOUES, HOUES, "houe")) return;

        // La récolte au dépôt : à la fin de chaque tour des champs, ou le sac presque plein (rien ne tombe par terre).
        if (Monde.libres(t) < 3 || tour.recoltees > 0 && tour.zone == 0) {
            tour.recoltees = 0;
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                Cerveau.attendre(t, 30);
                return;
            }
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }

        // Une fois par tour, et au plus toutes les 5 minutes : un des champs marqués, ressemé et agrandi s'il le faut.
        if (tour.zone == 0 && !champs.isEmpty() && Cerveau.temps(t) >= tour.champsVus) {
            tour.champsVus = Cerveau.temps(t) + 300;
            Champ c = Champ.de(champs.getCompound(tour.champ++ % champs.size()));
            if (c != null) { Cerveau.lancer(t, c); return; }
        }

        // Une zone de son espace de vie : ce qui est mûr, il le récolte ; sinon, la zone suivante dans une seconde.
        List<BlockPos> mures = mures(t, tour.zone);
        tour.zone = (tour.zone + 1) % ZONES.size();
        if (!mures.isEmpty()) { Cerveau.lancer(t, new Moisson(mures, tour)); return; }
        Cerveau.attendre(t, 1);
    }

    // --- Les cultures mûres -------------------------------------------------------------------------------------

    /** Une culture mûre qu'on sait replanter (la fleur-torche, qui se change en fleur, n'en est pas une). */
    static boolean mure(BlockState s) {
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s) && !s.is(Blocks.TORCHFLOWER_CROP) && s.getBlock().asItem() != Items.AIR;
    }

    /**
     * Les cultures mûres d'une zone de son espace de vie : à la surface de chaque colonne (la hauteur du sol est connue du jeu,
     * pas de fouille bloc à bloc), dans les tronçons chargés, ni dans une structure ni sur les terres d'un voisin.
     */
    static List<BlockPos> mures(Travailleur t, int zone) {
        List<BlockPos> r = new ArrayList<>();
        if (t.bureau == null) return r;
        ServerLevel l = (ServerLevel) t.level();
        int[] z = ZONES.get(zone);
        int rayon = Travailleur.ESPACE_DE_VIE;
        for (int dx = z[0]; dx < z[0] + Cultures.ZONE; dx++)
            for (int dz = z[1]; dz < z[1] + Cultures.ZONE; dz++) {
                int x = t.bureau.getX() + dx, y, zz = t.bureau.getZ() + dz;
                if (dx * dx + dz * dz > rayon * rayon || !l.hasChunkAt(x, zz)) continue;
                y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz); // le dessus de la terre labourée
                BlockPos p = new BlockPos(x, y, zz);
                if (mure(l.getBlockState(p)) && !t.blocInaccessible(p.asLong()) && !Mineur.interdit(t, p, false)) r.add(p);
            }
        return r;
    }

    /**
     * Récolte une culture mûre : ce qu'elle donne va au sac, et la même graine est replantée aussitôt, prise dans la récolte
     * (sinon dans le sac ; sans graine, la terre reste nue et l'entretien du champ la ressèmera).
     */
    static void recolter(Travailleur t, BlockPos p, BlockState s) {
        ServerLevel l = (ServerLevel) t.level();
        Item graine = s.getBlock().asItem();
        List<ItemStack> donne = Block.getDrops(s, l, p, null, t, t.getMainHandItem());
        l.destroyBlock(p, false, t);
        t.swing(InteractionHand.MAIN_HAND);
        boolean semee = false;
        for (ItemStack d : donne) if (!semee && d.is(graine)) { d.shrink(1); semee = true; }
        if (!semee) semee = !Actions.sortir(t, filtre(graine)).isEmpty();
        for (ItemStack d : donne) if (!d.isEmpty()) t.ranger(d);
        if (!semee) return;
        BlockState jeune = s.getBlock().defaultBlockState();
        if (jeune.canSurvive(l, p)) {
            l.setBlockAndUpdate(p, jeune);
            l.playSound(null, p, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1, 1);
        } else t.ranger(new ItemStack(graine)); // la terre labourée n'est plus là : la graine retourne au sac
    }

    static Actions.Filtre filtre(Item i) { return Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(i).toString()); }

    /** Il récolte une à une les cultures mûres de la liste, la plus proche d'abord ; le sac presque plein, il s'arrête. */
    static final class Moisson extends Actions.Action {
        final List<BlockPos> reste;
        final Tour tour;
        BlockPos cible;
        int marche, prochain, recoltees;

        Moisson(List<BlockPos> l, Tour tour) { super("moisson"); reste = new ArrayList<>(l); this.tour = tour; }

        Actions.Etat tick(Travailleur t) {
            if (cible == null) {
                if (reste.isEmpty()) { travail = recoltees > 0; return Actions.Etat.OK; } // du vrai travail (Coince)
                BlockPos ici = t.blockPosition();
                cible = reste.stream().min(Comparator.comparingDouble(q -> q.distSqr(ici))).orElseThrow();
                reste.remove(cible);
                marche = 0;
            }
            BlockState s = t.level().getBlockState(cible);
            if (!mure(s)) { cible = null; return Actions.Etat.EN_COURS; } // déjà récoltée (par un autre), ou plus là
            if (Monde.libres(t) < 2) return echec("sac_plein");
            if (!Actions.aPortee(t, cible)) {
                if (coince()) { t.blocInaccessible(cible); return echec("inaccessible"); }
                if (++marche > 20 * 30) { t.blocInaccessible(cible); cible = null; return Actions.Etat.EN_COURS; }
                marcher(t, Vec3.atBottomCenterOf(cible), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(cible));
            if (age < prochain) return Actions.Etat.EN_COURS;
            prochain = age + 4; // un geste toutes les 4 ticks
            recolter(t, cible, s);
            recoltees++;
            if (tour != null) tour.recoltees++;
            cible = null;
            return Actions.Etat.EN_COURS;
        }
    }

    // --- Les champs marqués -------------------------------------------------------------------------------------

    /** Se labourent à la houe : l'herbe et la terre (jamais un chemin, que quelqu'un a tracé). */
    static boolean labourable(BlockState s) { return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT); }

    /** De l'eau qui arrose ce sol, comme dans le jeu (à 4 blocs à l'horizontale, au même niveau ou juste au-dessus). */
    static boolean arrose(ServerLevel l, BlockPos q) {
        int a = Cultures.ARROSAGE;
        for (BlockPos x : BlockPos.betweenClosed(q.offset(-a, 0, -a), q.offset(a, 1, a))) if (l.getFluidState(x).is(FluidTags.WATER)) return true;
        return false;
    }

    /**
     * Un champ autour de {@code centre} (le sol visé), selon le plan (mesures et direction). Il prend au dépôt les graines, la
     * houe et le seau d'eau qui lui manquent ; s'il le faut et qu'il a un seau d'eau, il met l'eau au centre ; puis case par
     * case : il arrache l'herbe, laboure, sème. {@code ordre} : le chef vient de le demander (il parle, il retient le champ) ;
     * sinon c'est l'entretien d'un champ marqué : il ressème les cases vides et agrandit le champ d'un rang tout autour, là
     * où l'eau arrose la terre.
     */
    static final class Champ extends Actions.Action {
        final BlockPos centre;
        final Fouille.Plan plan;
        final Direction d;
        final Cultures.Culture culture;
        final Actions.Filtre graine;
        final boolean ordre;
        /** Les sols à semer, dans l'ordre. */
        final List<BlockPos> cases = new ArrayList<>();
        Actions.Action sous;
        BlockPos centreSol;
        int etape, voulues, semees, prochain, marche;
        boolean eau, agrandi;

        Champ(BlockPos centre, Fouille.Plan plan, Cultures.Culture culture, boolean ordre) {
            super("champ");
            this.centre = centre;
            this.plan = plan;
            Direction x = Direction.byName(plan.direction());
            this.d = x == null || x.getAxis().isVertical() ? Direction.NORTH : x;
            this.culture = culture;
            this.graine = Actions.Filtre.de(culture.graine());
            this.ordre = ordre;
        }

        /** Un champ retenu, à entretenir ; null s'il est illisible. */
        static Champ de(CompoundTag c) {
            Fouille.Plan p = Fouille.Plan.de(c.getString("p"));
            Cultures.Culture k = Cultures.parId(c.getString("g"));
            return p == null || k == null ? null : new Champ(BlockPos.of(c.getLong("c")), p, k, false);
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t);
            sous = null;
        }

        void dire(Travailleur t, String texte) { if (ordre) Voix.dire(t, texte, "calme"); }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            if (sous != null) {
                Actions.Etat r = sous.tick(t);
                sous.age++;
                if (r == Actions.Etat.EN_COURS) return r;
                sous.fin(t);
                sous = null;
            }
            Bureau.Entite b = Bureau.entite(t);
            switch (etape) {
                case 0 -> {
                    etape = 1;
                    relever(t, l);
                    if (cases.isEmpty()) {
                        dire(t, "Il n'y a rien à labourer là, chef : la terre est déjà semée, bâtie, ou trop loin du poste.");
                        return ordre ? echec("rien_a_semer") : Actions.Etat.OK;
                    }
                    // L'eau au milieu, si rien n'arrose déjà le champ : de son seau d'eau, sinon de celui du dépôt.
                    eau = ordre && centreSol != null && cases.contains(centreSol) && !arrose(l, centreSol);
                    if (eau && t.compter(SEAU) == 0 && b != null && b.compter(SEAU) > 0) { sous = new Actions.Prendre(SEAU, 1); return Actions.Etat.EN_COURS; }
                }
                case 1 -> {
                    etape = 2;
                    if (eau && t.compter(SEAU) == 0) {
                        eau = false;
                        dire(t, "Je n'ai pas de seau d'eau, chef : pas d'eau au milieu du champ, il poussera moins vite.");
                    }
                    if (eau) cases.remove(centreSol);
                    int manque = cases.size() - t.compter(graine);
                    if (manque > 0 && b != null && b.compter(graine) > 0) { sous = new Actions.Prendre(graine, manque); return Actions.Etat.EN_COURS; }
                }
                case 2 -> {
                    etape = 3;
                    if (aLabourer(l) && meilleure(t, HOUES::objet) == 0 && b != null && b.compter(HOUES) > 0) { sous = new Actions.Prendre(HOUES, 1); return Actions.Etat.EN_COURS; }
                }
                case 3 -> {
                    etape = 4;
                    voulues = cases.size();
                    int graines = t.compter(graine);
                    if (graines == 0) {
                        if (ordre) dire(t, "Je n'ai pas de " + nomGraines() + ", chef, ni sur moi ni au dépôt.");
                        else Cerveau.ecrire(t, "graines", Cerveau.UN_JOUR, "graines", nomGraines());
                        return echec("pas_de_graines");
                    }
                    if (aLabourer(l) && !equiperMeilleur(t, HOUES::objet)) {
                        if (ordre) dire(t, "Il me faut une houe pour labourer, chef, et il n'y en a pas au dépôt.");
                        else Cerveau.ecrire(t, "houe", 1800, "houe");
                        return echec("pas_de_houe");
                    }
                    while (cases.size() > graines) cases.remove(cases.size() - 1);
                }
                case 4 -> {
                    etape = 5;
                    if (eau) return mettreEau(t, l);
                }
                default -> {
                    if (cases.isEmpty()) return finir(t);
                    BlockPos q = cases.get(0);
                    if (!Actions.aPortee(t, q)) {
                        if (coince() || ++marche > 20 * 30) { cases.remove(0); marche = 0; t.blocInaccessible(q); return coince() ? finir(t) : Actions.Etat.EN_COURS; }
                        marcher(t, Vec3.atBottomCenterOf(q.above()), 1.0);
                        return Actions.Etat.EN_COURS;
                    }
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(Vec3.atCenterOf(q));
                    if (age < prochain) return Actions.Etat.EN_COURS;
                    prochain = age + 5;
                    marche = 0;
                    cases.remove(0);
                    if (semer(t, l, q) && !dans(q)) agrandi = true;
                }
            }
            return Actions.Etat.EN_COURS;
        }

        String nomGraines() {
            return switch (culture.id()) {
                case "ble" -> "graines de blé";
                case "betterave" -> "graines de betterave";
                case "carotte" -> "carottes à planter";
                default -> "pommes de terre à planter";
            };
        }

        /** Le sol est dans le champ marqué (pas dans le rang ajouté autour). */
        boolean dans(BlockPos q) {
            int f = (q.getX() - centre.getX()) * d.getStepX() + (q.getZ() - centre.getZ()) * d.getStepZ();
            Direction cote = d.getClockWise();
            int c = (q.getX() - centre.getX()) * cote.getStepX() + (q.getZ() - centre.getZ()) * cote.getStepZ();
            return Cultures.dans(plan, f, c);
        }

        /** Les sols à semer : les cases vides du champ, et (à l'entretien) un rang tout autour, seulement là où l'eau arrose. */
        void relever(Travailleur t, ServerLevel l) {
            Fouille.Plan p = ordre ? plan : Cultures.agrandi(plan);
            for (int[] k : Cultures.cases(p)) {
                BlockPos q = sol(t, l, centre.relative(d, k[0]).relative(d.getClockWise(), k[1]), centre.getY());
                if (k[0] == 0 && k[1] == 0) centreSol = q;
                if (q != null && (ordre || Cultures.dans(plan, k[0], k[1]) || arrose(l, q))) cases.add(q);
            }
        }

        boolean aLabourer(ServerLevel l) {
            for (BlockPos q : cases) if (!l.getBlockState(q).is(Blocks.FARMLAND)) return true;
            return false;
        }

        /** L'eau au centre : il creuse le sol du centre (la terre va au sac) et y vide son seau d'eau (le seau vide lui reste). */
        Actions.Etat mettreEau(Travailleur t, ServerLevel l) {
            BlockPos q = centreSol;
            if (!Actions.aPortee(t, q)) {
                if (coince() || ++marche > 20 * 30) { marche = 0; return Actions.Etat.EN_COURS; } // il sème sans eau
                etape = 4;
                marcher(t, Vec3.atBottomCenterOf(q.above()), 1.0);
                return Actions.Etat.EN_COURS;
            }
            marche = 0;
            if (Monde.libres(t) < 2) return Actions.Etat.EN_COURS; // pas la place pour la terre : il sème sans eau
            if (!l.getBlockState(q.above()).isAir()) Actions.recolter(t, q.above()); // l'herbe haute
            Actions.recolter(t, q);
            l.setBlockAndUpdate(q, Blocks.WATER.defaultBlockState());
            l.playSound(null, q, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1, 1);
            if (SEAU.objet(t.getMainHandItem())) t.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BUCKET));
            else for (int i = 0; i < t.inventaire.getContainerSize(); i++)
                if (SEAU.objet(t.inventaire.getItem(i))) { t.inventaire.setItem(i, new ItemStack(Items.BUCKET)); break; }
            t.swing(InteractionHand.MAIN_HAND);
            return Actions.Etat.EN_COURS;
        }

        /** Une case : l'herbe arrachée (au sac), la terre labourée, la graine semée. Faux si rien n'est semé. */
        boolean semer(Travailleur t, ServerLevel l, BlockPos q) {
            BlockState sol = l.getBlockState(q), dessus = l.getBlockState(q.above());
            if (!libreDessus(dessus) || !sol.is(Blocks.FARMLAND) && !labourable(sol)) return false;
            if (!dessus.isAir()) {
                if (Monde.libres(t) == 0) return false;
                Actions.recolter(t, q.above());
            }
            if (!sol.is(Blocks.FARMLAND)) {
                if (!HOUES.objet(t.getMainHandItem())) return false;
                l.setBlockAndUpdate(q, Blocks.FARMLAND.defaultBlockState());
                l.playSound(null, q, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1, 1);
                Actions.userOutil(t, 1);
            }
            Block bloc = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(culture.bloc()));
            if (!bloc.defaultBlockState().canSurvive(l, q.above())) return false;
            if (Actions.sortir(t, graine).isEmpty()) return false;
            l.setBlockAndUpdate(q.above(), bloc.defaultBlockState());
            l.playSound(null, q, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1, 1);
            t.swing(InteractionHand.MAIN_HAND);
            semees++;
            return true;
        }

        Actions.Etat finir(Travailleur t) {
            travail = semees > 0; // du vrai travail (Coince)
            if (ordre) {
                if (semees > 0) retenir(t);
                dire(t, semees == 0 ? "Je n'ai rien pu semer, chef." : semees < voulues
                        ? "Il me manquait des " + nomGraines() + " : j'ai semé " + semees + " cases sur " + voulues + "."
                        : "Le champ est labouré et semé, chef.");
            } else if (agrandi) retenir(t);
            return Actions.Etat.OK;
        }

        /** Il retient le champ (agrandi d'un rang s'il l'a agrandi) pour l'entretenir ; 8 champs au plus. */
        void retenir(Travailleur t) {
            ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
            for (int i = champs.size() - 1; i >= 0; i--) if (champs.getCompound(i).getLong("c") == centre.asLong()) champs.remove(i);
            CompoundTag c = new CompoundTag();
            c.putLong("c", centre.asLong());
            c.putString("p", (agrandi ? Cultures.agrandi(plan) : plan).vers(d.getName(), false).texte());
            c.putString("g", culture.id());
            champs.add(c);
            while (champs.size() > CHAMPS) champs.remove(0);
            t.memoire.put("champs", champs);
            TOURS.computeIfAbsent(t, k -> new Tour()).champsVus = Cerveau.temps(t) + 300; // il vient de le semer : l'entretien attendra
        }
    }

    /** Au-dessus du sol, la place est libre : de l'air, de l'herbe ou une fleur (rien de bâti, pas d'eau, pas une culture). */
    static boolean libreDessus(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof CropBlock);
    }

    /**
     * Le sol à semer dans cette colonne, près de la hauteur y0 : de l'herbe, de la terre ou une terre labourée, libre dessus,
     * au ciel ouvert, dans son espace de vie, ni bâti ni sur les terres d'un voisin ; null sinon (déjà semé compris).
     */
    static BlockPos sol(Travailleur t, ServerLevel l, BlockPos col, int y0) {
        if (!l.isLoaded(col)) return null;
        for (int y = y0 + 3; y >= y0 - 3; y--) {
            BlockPos q = col.atY(y);
            BlockState s = l.getBlockState(q);
            if (s.getBlock() instanceof CropBlock) return null; // déjà semé
            if (s.isAir() || libreDessus(s)) continue;
            if (!libreDessus(l.getBlockState(q.above()))) return null;
            boolean ok = (s.is(Blocks.FARMLAND) || labourable(s)) && !Monde.construit(l, q) && t.chezLui(q) && l.canSeeSky(q.above()) && !Mineur.interdit(t, q, false);
            return ok ? q.immutable() : null;
        }
        return null;
    }

    // --- Sur ordre : récolter tout de suite, faire du pain --------------------------------------------------------

    /** L'action qui joue une consigne du fermier (« champ », « recolter », « pain ») ; elle dit elle-même ce qu'elle a fait. */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) {
        return switch (mode) {
            case "champ" -> {
                String[] x = c.texte.split("\\|");
                Fouille.Plan p = Fouille.Plan.de(x[0]);
                Cultures.Culture k = x.length > 1 ? Cultures.parId(x[1]) : null;
                yield new Champ(c.lieu, p == null ? Cultures.mesures("") : p, k == null ? Cultures.TOUTES.get(0) : k, true);
            }
            case "pain" -> new Pain(Math.max(1, c.nombre));
            default -> new Recolte();
        };
    }

    /**
     * « Récolte ! » : il relève tout son espace de vie (deux zones par tick), récolte et ressème ce qui est mûr, puis porte la
     * récolte au dépôt. Le sac plein, il va le vider et reprend (trois fois au plus).
     */
    static final class Recolte extends Actions.Action {
        final List<BlockPos> mures = new ArrayList<>();
        Actions.Action sous;
        int zone, etape, tours, recoltees;
        boolean plein;

        Recolte() { super("recolte"); }

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
                if (sous instanceof Moisson m) { recoltees += m.recoltees; plein = "sac_plein".equals(m.raison); }
                sous.fin(t);
                sous = null;
            }
            switch (etape) {
                case 0 -> {
                    for (int i = 0; i < 2 && zone < ZONES.size(); i++) mures.addAll(mures(t, zone++));
                    if (zone < ZONES.size()) return Actions.Etat.EN_COURS;
                    etape = 1;
                    if (mures.isEmpty() && recoltees == 0) { Voix.dire(t, "Rien n'est mûr dans les champs pour l'instant, chef.", "calme"); return Actions.Etat.OK; }
                    sous = new Moisson(mures, null);
                    mures.clear();
                }
                case 1 -> {
                    etape = 2;
                    Metier m = Metier.de(t.metier);
                    sous = new Actions.Deposer(m == null ? List.of() : m.garder);
                }
                default -> {
                    if (plein && ++tours < 3) { plein = false; etape = 0; zone = 0; return Actions.Etat.EN_COURS; }
                    Voix.dire(t, recoltees == 0 ? "Je n'ai rien pu récolter, chef." : "La récolte est au dépôt, chef : " + recoltees + " plants récoltés et ressemés.", "calme");
                    return Actions.Etat.OK;
                }
            }
            return Actions.Etat.EN_COURS;
        }
    }

    /**
     * Du pain au poste : 3 blés (du sac, sinon du dépôt) donnent un pain, selon la recette du jeu, toutes les demi-secondes ;
     * le pain va au dépôt. Rien ne se crée ni ne se perd.
     */
    static final class Pain extends Actions.AuBureau {
        final int voulu;
        int faits;

        Pain(int voulu) { super("pain"); this.voulu = voulu; }

        Actions.Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            if (age % 10 != 0) return Actions.Etat.EN_COURS;
            if (faits >= voulu || t.compter(BLE) + b.compter(BLE) < 3) return dire(t);
            List<ItemStack> ble = new ArrayList<>();
            while (ble.size() < 3) {
                ItemStack s = Actions.sortir(t, BLE);
                if (s.isEmpty()) s = b.prendre(BLE, 1).stream().findFirst().orElse(ItemStack.EMPTY);
                if (s.isEmpty()) break;
                ble.add(s);
            }
            ItemStack pain = ble.size() < 3 ? ItemStack.EMPTY : recette((ServerLevel) t.level(), ble);
            if (pain.isEmpty()) {
                for (ItemStack s : ble) { ItemStack reste = b.ranger(s); if (!reste.isEmpty()) t.ranger(reste); }
                return echec("pas_de_recette");
            }
            faits += pain.getCount();
            ItemStack reste = b.ranger(pain);
            if (!reste.isEmpty()) t.ranger(reste);
            t.swing(InteractionHand.MAIN_HAND);
            if (!reste.isEmpty()) { Voix.dire(t, faits + " pain" + (faits > 1 ? "s" : "") + " de faits, chef. Le dépôt est plein : je garde le reste sur moi.", "calme"); return echec("depot_plein"); }
            return Actions.Etat.EN_COURS;
        }

        Actions.Etat dire(Travailleur t) {
            Voix.dire(t, faits == 0 ? "Je n'ai pas assez de blé, chef : il en faut trois pour un pain, et il n'y en a ni sur moi ni au dépôt."
                    : faits + " pain" + (faits > 1 ? "s" : "") + " au dépôt, chef.", "calme");
            return faits == 0 ? echec("pas_de_ble") : Actions.Etat.OK;
        }

        /** Ce que donnent ces objets en rang sur l'établi (3 blés : un pain), selon les recettes du jeu ; rien sinon. */
        static ItemStack recette(ServerLevel l, List<ItemStack> rang) {
            var entree = net.minecraft.world.item.crafting.CraftingInput.of(rang.size(), 1, rang);
            return l.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, entree, l)
                    .map(r -> r.value().assemble(entree, l.registryAccess())).orElse(ItemStack.EMPTY);
        }
    }

    // --- Les règles ---------------------------------------------------------------------------------------------

    /** Un ouvrier ne piétine jamais une terre labourée, en sautant ou en tombant dessus. */
    static void pietiner(BlockEvent.FarmlandTrampleEvent e) {
        if (e.getEntity() instanceof Travailleur) e.setCanceled(true);
    }
}

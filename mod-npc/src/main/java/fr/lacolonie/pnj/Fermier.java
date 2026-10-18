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
import net.minecraft.world.level.block.FarmBlock;
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
 * Le fermier (lot 6). Seul, il ne travaille que dans les champs que son chef lui a marqués (« plante un champ de blé ici,
 * 9 sur 9 »), à 32 blocs au plus de son poste : il en fait le tour, une zone de 16 x 16 à la fois (jamais de grand
 * balayage) ; ce qui est mûr (blé, carottes, pommes de terre, betteraves), il le récolte et replante aussitôt la même graine ;
 * la récolte et les graines en trop vont au dépôt à la fin de chaque tour. Il entretient ses champs : il relaboure et
 * ressème les cases vides, et sème le rang autour là où l'eau arrose la terre. Sur ordre, il récolte tout de suite (dans
 * tout son espace de vie), fait du pain au poste, ou oublie un champ.
 * <p>
 * Jamais : piétiner une terre labourée ({@link #pietiner}), casser une culture pas mûre, toucher aux cultures d'une structure
 * ou plus près du poste d'un autre chef que du sien ({@link #aLui}), labourer un bloc construit ou hors de son espace de vie,
 * laisser l'eau couler hors du champ. Rien ne tombe par terre, rien ne se crée : chaque graine semée sort de la récolte ou du
 * sac, le pain suit la recette du jeu. Seule l'eau, faute de seau, vient des mares d'un bloc qu'il creuse : à la création
 * d'un champ, et dans un champ où de la terre reste sèche (une fois par jour au plus) ; le plant à la place d'une mare est
 * arraché (sa graine va au sac), seule exception à « pas une culture pas mûre ».
 */
final class Fermier extends Metier {
    static final Actions.Filtre HOUES = Actions.Filtre.de("#minecraft:hoes");
    static final Actions.Filtre BLE = Actions.Filtre.de("minecraft:wheat");
    static final Actions.Filtre SEAU = Actions.Filtre.de("minecraft:water_bucket");
    /** Au plus tant de champs marqués retenus (le plus ancien part). */
    static final int CHAMPS = 8;
    /** Seul, il ne récolte qu'à tant de blocs au plus de son poste. */
    static final int PORTEE = 32;
    /** Le tour seul (32 blocs) et « Récolte ! » (tout l'espace de vie) : les zones, de la plus proche du poste à la plus lointaine. */
    static final List<int[]> ZONES = Cultures.zones(PORTEE), ZONES_VIE = Cultures.zones(Travailleur.ESPACE_DE_VIE);
    /** Un plant qu'il n'a pas pu atteindre (un champ clos) : laissé tant de secondes. */
    static final int OUBLI_PLANT = 600;

    Fermier() {
        super("fermier", "Fermier", "Cultive les champs que tu lui marques près du poste : récolte le blé, les carottes, les pommes de terre et les "
                + "betteraves mûrs, replante aussitôt, laboure et sème, et fait du pain.", false, "#minecraft:hoes", "minecraft:wheat_seeds", "nourriture");
        commandes.add(new Commande("tout", "Toutes les récoltes", String.join("|", Cultures.TOUTES.stream().map(Cultures.Culture::recolte).toList())));
        for (Cultures.Culture c : Cultures.TOUTES) commandes.add(new Commande(c.id(), c.nom(), c.recolte()));
    }

    /** Où en est son tour des champs (pas sauvegardé : après un redémarrage, il reprend au poste). */
    static final class Tour {
        int zone, recoltees, champ;
        long champsVus;
        /** Ce tour-ci : il a trouvé du mûr ; il a vu un plant (mûr ou non) de la culture commandée. */
        boolean trouve, vuCommande;
    }

    static final Map<Travailleur, Tour> TOURS = new WeakHashMap<>();

    /** Un champ marqué : son centre (le sol), son plan, sa culture ; {@link #contient} : le champ et son rang autour. */
    static final class Parcelle {
        final BlockPos centre;
        final Fouille.Plan plan, large;
        final Direction d;
        final Cultures.Culture culture;

        Parcelle(BlockPos centre, Fouille.Plan plan, Cultures.Culture culture) {
            this.centre = centre;
            this.plan = plan;
            this.large = Cultures.agrandi(plan);
            Direction x = Direction.byName(plan.direction());
            this.d = x == null || x.getAxis().isVertical() ? Direction.NORTH : x;
            this.culture = culture;
        }

        static Parcelle de(CompoundTag c) {
            Fouille.Plan p = Fouille.Plan.de(c.getString("p"));
            Cultures.Culture k = Cultures.parId(c.getString("g"));
            return p == null || k == null ? null : new Parcelle(BlockPos.of(c.getLong("c")), p, k);
        }

        /** {avant, côté} de la colonne (x, z) par rapport au centre. */
        int[] coordonnees(int x, int z) {
            int dx = x - centre.getX(), dz = z - centre.getZ();
            Direction c = d.getClockWise();
            return new int[]{dx * d.getStepX() + dz * d.getStepZ(), dx * c.getStepX() + dz * c.getStepZ()};
        }

        boolean contient(int x, int z) { int[] k = coordonnees(x, z); return Cultures.dans(large, k[0], k[1]); }
    }

    static List<Parcelle> parcelles(Travailleur t) {
        List<Parcelle> r = new ArrayList<>();
        ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
        for (int i = 0; i < champs.size(); i++) { Parcelle p = Parcelle.de(champs.getCompound(i)); if (p != null) r.add(p); }
        return r;
    }

    /** La culture de sa commande en cours (« 32 carottes »), ou null (pas de commande, ou toutes les récoltes). */
    static Cultures.Culture commandee(Travailleur t) {
        Metier.Commande c = Metier.commande(t);
        return c == null ? null : Cultures.parId(c.id);
    }

    /** Il ne manque plus de graines (semis réussi, rien à semer, ou des graines au dépôt) : « Plus de graines au dépôt » s'efface. */
    static void plusSansGraines(Travailleur t) {
        t.memoire.remove("sans_graines");
        t.debloquer(Statut.SANS_GRAINES);
    }

    @Override
    void travailler(Travailleur t) {
        Tour tour = TOURS.computeIfAbsent(t, k -> new Tour());
        ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);

        // Sa houe, prise au dépôt, s'il a des champs à labourer (pour récolter, les mains suffisent).
        if (!champs.isEmpty() && outilAuDepot(t, HOUES, HOUES, "houe")) return;
        // Plus de graines pour semer (vu au dernier semis) : il le dit au-dessus de sa tête jusqu'au prochain semis réussi,
        // au lieu de « Cultive » quand son champ reste vide (fantôme : 0 blé en 2 jours). Dès qu'il y a de ces graines au
        // dépôt (ou sur lui), il ne le dit plus : il les prendra au prochain semis.
        String sans = t.memoire.getString("sans_graines");
        if (!sans.isEmpty()) {
            Actions.Filtre g = Actions.Filtre.de(sans);
            Bureau.Entite b = Bureau.entite(t);
            if (t.compter(g) > 0 || b != null && b.compter(g) > 0) plusSansGraines(t);
            else t.bloquer(Statut.SANS_GRAINES);
        }

        // La récolte au dépôt : à la fin de chaque tour des champs, ou le sac presque plein (rien ne tombe par terre).
        if (Monde.libres(t) < 3 || tour.recoltees > 0 && tour.zone == 0) {
            tour.recoltees = 0;
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                t.bloquer(Statut.SAC_PLEIN);
                Cerveau.attendre(t, 30);
                return;
            }
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }

        // Sa mission a un lieu (ou « Travaille ici ») sans champ à lui autour : il y laboure et sème un champ de 9 sur 9
        // (un essai toutes les 5 minutes au plus ; raté, faute de terre libre ou de graines, il le dit au-dessus de sa tête).
        BlockPos lieu = t.lieuTravail();
        if (lieu != null && parcelles(t).stream().noneMatch(p -> distance(p.centre, lieu) <= PRES_DU_LIEU * PRES_DU_LIEU)) {
            if (t.memoire.getLong("champ_lieu") != lieu.asLong() || t.memoire.getLong("champ_lieu_t") <= Cerveau.temps(t)) {
                t.memoire.putLong("champ_lieu", lieu.asLong());
                t.memoire.putLong("champ_lieu_t", Cerveau.temps(t) + 300);
                Cultures.Culture k = commandee(t);
                Cerveau.lancer(t, new Champ(lieu, Cultures.mesures(""), k == null ? Cultures.TOUTES.get(0) : k, true));
                return;
            }
            t.bloquer(Statut.RIEN_A_SEMER);
            Cerveau.attendre(t, 20);
            return;
        }
        t.debloquer(Statut.RIEN_A_SEMER);

        // Sans champ marqué : il en fait un de 9 sur 9 sur un terrain plat et libre, près de l'eau (refonte, partie 2), cherché
        // toutes les 5 minutes au plus ; faute de terrain, il le dit à son chef, et au-dessus de sa tête.
        if (champs.isEmpty()) {
            if (t.memoire.getLong("champ_auto_t") <= Cerveau.temps(t)) {
                t.memoire.putLong("champ_auto_t", Cerveau.temps(t) + 300);
                BlockPos ici = terrain(t);
                if (ici != null) {
                    Cultures.Culture k = commandee(t);
                    Cerveau.lancer(t, new Champ(ici, Cultures.mesures(""), k == null ? Cultures.TOUTES.get(0) : k, true));
                    return;
                }
            }
            t.bloquer(Statut.SANS_CHAMP);
            Cerveau.ecrire(t, "sans_champ", Cerveau.UN_JOUR, "sans_champ");
            Cerveau.attendre(t, 20);
            return;
        }
        t.debloquer(Statut.SANS_CHAMP);

        // Une fois par tour, et au plus toutes les 5 minutes : un des champs marqués, ressemé s'il le faut.
        if (tour.zone == 0 && Cerveau.temps(t) >= tour.champsVus) {
            tour.champsVus = Cerveau.temps(t) + 300;
            Champ c = Champ.de(champs.getCompound(tour.champ++ % champs.size()));
            if (c != null) { Cerveau.lancer(t, c); return; }
        }

        // Le tour de ses champs : la prochaine zone qui en touche un ; ce qui est mûr, il le récolte.
        List<Parcelle> parcelles = parcelles(t);
        if (lieu != null) parcelles.removeIf(p -> distance(p.centre, lieu) > PRES_DU_LIEU * PRES_DU_LIEU); // seulement le champ de ce lieu
        Cultures.Culture voulue = commandee(t);
        List<BlockPos> mures = new ArrayList<>();
        boolean[] vu = {false, false}; // {la zone touche un champ, un plant de la culture commandée}
        do {
            vu[0] = false;
            mures.addAll(mures(t, ZONES, tour.zone, parcelles, voulue, vu));
            if (vu[1]) tour.vuCommande = true;
            tour.zone = (tour.zone + 1) % ZONES.size();
        } while (!vu[0] && tour.zone != 0);
        if (!mures.isEmpty()) tour.trouve = true;
        if (tour.zone == 0) {
            // Fin du tour. La commande d'une culture qu'il n'a dans aucun champ : il le dit (une fois).
            if (voulue != null && !tour.vuCommande) Cerveau.ecrire(t, "culture_absente:" + voulue.id(), Mineur.AVIS, "culture_absente", voulue.nom().toLowerCase());
            boolean vide = !tour.trouve;
            tour.trouve = tour.vuCommande = false;
            if (vide) { Cerveau.attendre(t, 20 + t.getRandom().nextInt(11)); return; } // rien de mûr nulle part : une pause
        }
        if (!mures.isEmpty()) mures.removeAll(autresMoissons(t)); // un autre fermier les récolte déjà : ils ne se gênent pas
        if (!mures.isEmpty()) { Cerveau.lancer(t, new Moisson(mures, tour, voulue)); return; }
        Cerveau.attendre(t, 1);
    }

    /**
     * Le centre d'un champ de 9 sur 9 à faire, à 32 blocs au plus du poste : sur trois cercles de huit points, celui où le plus
     * de cases (une sur deux, 25 relevées) se sèment ({@link #sol} : libre, à ciel ouvert, ni bâti ni à un voisin), à 8 blocs
     * au plus d'une eau à fleur de sol ; faute d'eau, la meilleure terre plate ; null s'il n'y en a pas (au moins 20 cases sur 25). Quelques milliers de blocs lus,
     * au plus toutes les 5 minutes, et seulement par un fermier sans champ.
     */
    static BlockPos terrain(Travailleur t) {
        if (t.bureau == null || !(t.level() instanceof ServerLevel l)) return null;
        BlockPos mieux = null, sec = null; // près de l'eau d'abord ; sinon la meilleure terre plate (le blé y pousse, moins vite)
        int note = 19, noteSec = 19;
        for (int r = 10; r <= PORTEE - 6; r += 6)
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                int x = t.bureau.getX() + (int) Math.round(Math.cos(a) * r), z = t.bureau.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!l.hasChunkAt(x, z)) continue;
                int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int n = 0;
                for (int i = -4; i <= 4; i += 2) for (int j = -4; j <= 4; j += 2) if (sol(t, l, new BlockPos(x + i, y, z + j), y) != null) n++;
                if (n <= Math.min(note, noteSec)) continue;
                if (eauPres(l, x, y, z)) { if (n > note) { note = n; mieux = new BlockPos(x, y, z); } }
                else if (n > noteSec) { noteSec = n; sec = new BlockPos(x, y, z); }
            }
        return mieux != null ? mieux : sec;
    }

    /** De l'eau à fleur de sol (à 2 blocs près de la hauteur y) à 8 blocs au plus de (x, z), relevée une colonne sur deux. */
    static boolean eauPres(ServerLevel l, int x, int y, int z) {
        for (int i = -8; i <= 8; i += 2)
            for (int j = -8; j <= 8; j += 2) {
                if (!l.hasChunkAt(x + i, z + j)) continue;
                int h = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x + i, z + j) - 1;
                if (Math.abs(h - y) <= 2 && l.getFluidState(new BlockPos(x + i, h, z + j)).is(FluidTags.WATER)) return true;
            }
        return false;
    }

    /** Un champ est « au lieu » de la mission si son centre en est à tant de blocs au plus. */
    static final int PRES_DU_LIEU = 8;

    /** Les plants que les autres fermiers alentour sont déjà en train de récolter. */
    static java.util.Set<BlockPos> autresMoissons(Travailleur t) {
        java.util.Set<BlockPos> r = new java.util.HashSet<>();
        for (Travailleur o : t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(Travailleur.ESPACE_DE_VIE, 16, Travailleur.ESPACE_DE_VIE),
                o -> o != t && o.action instanceof Moisson)) {
            Moisson m = (Moisson) o.action;
            r.addAll(m.reste);
            if (m.cible != null) r.add(m.cible);
        }
        return r;
    }

    // --- Les cultures mûres -------------------------------------------------------------------------------------

    /** Une culture mûre qu'on sait replanter (la fleur-torche, qui se change en fleur, n'en est pas une). */
    static boolean mure(BlockState s) {
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s) && !s.is(Blocks.TORCHFLOWER_CROP) && s.getBlock().asItem() != Items.AIR;
    }

    /**
     * Les cultures mûres d'une zone : à la surface de chaque colonne (la hauteur du sol est connue du jeu, pas de fouille bloc
     * à bloc), dans les tronçons chargés, à lui ({@link #aLui}), pas laissées faute d'accès. {@code parcelles} : seulement
     * dans ces champs (son tour) ; null : tout son espace de vie (« Récolte ! »). {@code vu} : la zone touche un champ, un
     * plant de la culture {@code voulue} y pousse.
     */
    static List<BlockPos> mures(Travailleur t, List<int[]> zones, int zone, List<Parcelle> parcelles, Cultures.Culture voulue, boolean[] vu) {
        List<BlockPos> r = new ArrayList<>();
        if (t.bureau == null) return r;
        ServerLevel l = (ServerLevel) t.level();
        int[] z = zones.get(zone);
        int rayon = parcelles == null ? Travailleur.ESPACE_DE_VIE : PORTEE;
        Block bloc = voulue == null ? null : BuiltInRegistries.BLOCK.get(ResourceLocation.parse(voulue.bloc()));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = z[0]; dx < z[0] + Cultures.ZONE; dx++)
            for (int dz = z[1]; dz < z[1] + Cultures.ZONE; dz++) {
                int x = t.bureau.getX() + dx, zz = t.bureau.getZ() + dz;
                if (dx * dx + dz * dz > rayon * rayon) continue;
                if (parcelles != null && parcelles.stream().noneMatch(c -> c.contient(x, zz))) continue;
                if (!l.hasChunkAt(x, zz)) continue;
                vu[0] = true;
                p.set(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz), zz); // le dessus de la terre labourée
                BlockState s = l.getBlockState(p);
                if (bloc != null && s.is(bloc)) vu[1] = true;
                if (mure(s) && !laisse(t, p) && aLui(t, p)) r.add(p.immutable());
            }
        return r;
    }

    /** Ce plant est à lui : hors d'une structure (un village), et aucun poste d'un autre chef n'en est plus près que le sien. */
    static boolean aLui(Travailleur t, BlockPos p) {
        if (Monde.structure(t.level(), p) != null) return false;
        if (!(t.level() instanceof ServerLevel sl) || t.bureau == null) return false;
        var serveur = sl.getServer();
        // Serveur de tests : comme pour le mineur, les postes voisins ne comptent que dans le lot « voisins ».
        if (serveur instanceof net.minecraft.gametest.framework.GameTestServer && !Mineur.voisinsEnEssai) return true;
        double mien = distance(t.bureau, p);
        for (var e : Courrier.de(serveur).postes.entrySet())
            if (!e.getKey().equals(t.chef) && e.getValue().monde() == sl.dimension() && distance(e.getValue().pos(), p) < mien) return false;
        return true;
    }

    static double distance(BlockPos a, BlockPos b) { double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return dx * dx + dz * dz; }

    /** Un plant qu'il a renoncé à atteindre (un champ clos), il le laisse 10 minutes (souvenir gardé avec lui). */
    static boolean laisse(Travailleur t, BlockPos p) {
        CompoundTag m = t.memoire.getCompound("plants_laisses");
        String k = Long.toString(p.asLong());
        return m.contains(k) && m.getLong(k) > Cerveau.temps(t);
    }

    /** Il renonce à ce plant pour 10 minutes, et le dit à son chef (une lettre, pas une par plant). */
    static void laisser(Travailleur t, BlockPos p) {
        CompoundTag m = t.memoire.getCompound("plants_laisses");
        long maintenant = Cerveau.temps(t);
        if (m.size() >= 64) for (String k : new ArrayList<>(m.getAllKeys())) if (m.getLong(k) <= maintenant) m.remove(k);
        if (m.size() < 64) m.putLong(Long.toString(p.asLong()), maintenant + OUBLI_PLANT);
        t.memoire.put("plants_laisses", m);
        Cerveau.ecrire(t, "plant_inaccessible", Mineur.AVIS, "plant_inaccessible", p.getX() + " " + p.getY() + " " + p.getZ());
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

    /**
     * Il récolte une à une les cultures mûres de la liste : celles de la culture commandée d'abord, puis la plus proche ; le
     * sac presque plein, il s'arrête. Un plant qu'il n'atteint pas (champ clos) : laissé 10 minutes, et il le dit.
     */
    static final class Moisson extends Actions.Action {
        final List<BlockPos> reste;
        final Tour tour;
        final Block voulu;
        BlockPos cible;
        int marche, prochain, recoltees;

        Moisson(List<BlockPos> l, Tour tour, Cultures.Culture voulue) {
            super("moisson");
            reste = new ArrayList<>(l);
            this.tour = tour;
            voulu = voulue == null ? null : BuiltInRegistries.BLOCK.get(ResourceLocation.parse(voulue.bloc()));
        }

        Actions.Etat tick(Travailleur t) {
            if (cible == null) {
                if (reste.isEmpty()) { travail = recoltees > 0; return Actions.Etat.OK; } // du vrai travail (Coince)
                BlockPos ici = t.blockPosition();
                cible = reste.stream().min(Comparator.comparingDouble(q -> q.distSqr(ici) + (voulu != null && !t.level().getBlockState(q).is(voulu) ? 1e9 : 0))).orElseThrow();
                reste.remove(cible);
                marche = 0;
            }
            BlockState s = t.level().getBlockState(cible);
            if (!mure(s)) { cible = null; return Actions.Etat.EN_COURS; } // déjà récoltée (par un autre), ou plus là
            if (Monde.libres(t) < 2) return echec("sac_plein");
            if (!Actions.aPortee(t, cible)) {
                if (coince()) { laisser(t, cible); return echec("inaccessible"); }
                if (++marche > 20 * 30) { laisser(t, cible); cible = null; return Actions.Etat.EN_COURS; }
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
     * L'eau tiendra en q sans couler hors du champ : le dessous et les 4 côtés pleins (terre, herbe, terre labourée), sans
     * liquide, et au-dessus rien de solide ni de liquide (un plant, qu'il arrache : sa graine va au sac). Sur une pente ou au
     * bord d'un trou, non.
     */
    static boolean bassin(ServerLevel l, BlockPos q) {
        BlockState dessus = l.getBlockState(q.above());
        if (!libreDessus(dessus) && !(dessus.getBlock() instanceof CropBlock)) return false;
        for (BlockPos n : List.of(q.below(), q.north(), q.south(), q.east(), q.west())) {
            BlockState s = l.getBlockState(n);
            if (!s.getFluidState().isEmpty() || !(s.isCollisionShapeFullBlock(l, n) || s.is(Blocks.FARMLAND))) return false;
        }
        return true;
    }

    /**
     * Un champ autour de {@code centre} (le sol visé), selon le plan (mesures et direction). Il prend au dépôt le seau d'eau,
     * les graines (autant que son sac en tient) et la houe qui lui manquent ; là où rien n'arrose la terre, il met l'eau
     * ({@link #mares}) ; puis case par case : il arrache l'herbe, laboure, sème. {@code ordre} :
     * le chef vient de le demander (il parle, il retient le champ) ; sinon c'est l'entretien d'un champ marqué : il ressème
     * les cases vides, et le rang autour seulement là où l'eau arrose ou la terre est déjà labourée (le plan ne change pas).
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
        /** Les sols à labourer sans graine (il en manquait) : la terre labourée et arrosée autour d'un plant le fait pousser plus vite. */
        final List<BlockPos> labours = new ArrayList<>();
        /** Les mares à creuser, dans l'ordre (champ neuf, ou champ sec à l'entretien). */
        final List<BlockPos> mares = new ArrayList<>();
        /** Sa terre labourée sous ses plants (pour l'eau) ; et toute sa terre labourée et ses plants, qui ne comptent pas comme construction. */
        final List<BlockPos> terres = new ArrayList<>();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet siens = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        Actions.Action sous;
        int etape, voulues, semees, prochain, marche, plants, faites, seches;

        Champ(BlockPos centre, Fouille.Plan plan, Cultures.Culture culture, boolean ordre) {
            super("champ");
            Parcelle p = new Parcelle(centre, plan, culture);
            this.centre = centre;
            this.plan = plan;
            this.d = p.d;
            this.culture = culture;
            this.graine = Actions.Filtre.de(culture.graine());
            this.ordre = ordre;
        }

        /** Un champ retenu, à entretenir ; null s'il est illisible. */
        static Champ de(CompoundTag c) {
            Parcelle p = Parcelle.de(c);
            return p == null ? null : new Champ(p.centre, p.plan, p.culture, false);
        }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t);
            sous = null;
        }

        void dire(Travailleur t, String texte) { if (ordre) Paroles.dire(t, texte, "calme"); }

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
                    // À l'entretien, un champ dont une part de la terre n'est arrosée par aucune eau (un vieux champ, une mare
                    // bouchée) : la mare comme pour un champ neuf, cherchée une fois par jour de jeu au plus (pas de boucle).
                    CompoundTag fiche = ordre ? null : fiche(t);
                    if (fiche != null && fiche.getLong("mare_t") <= Cerveau.temps(t)) {
                        mares.addAll(mares(t, l));
                        if (seches > 0) fiche.putLong("mare_t", Cerveau.temps(t) + Cerveau.UN_JOUR);
                        // Pas de place pour l'eau (des constructions tout autour) : il le dit au journal du poste, une fois par jour.
                        if (mares.isEmpty() && seches * 2 > cases.size() + terres.size()) Cerveau.ecrire(t, "sans_mare", Cerveau.UN_JOUR, "sans_mare");
                    }
                    if (cases.isEmpty() && mares.isEmpty()) {
                        plusSansGraines(t); // plus rien à semer : il ne manque plus de graines
                        dire(t, "Il n'y a rien à labourer là, chef : la terre est déjà semée, bâtie, ou trop loin du poste.");
                        return ordre ? echec("rien_a_semer") : Actions.Etat.OK;
                    }
                    // L'eau, là où rien n'arrose la terre : une mare d'un bloc au niveau des cases sèches, même sur un terrain en
                    // marches ({@link #mares}) ; de son seau, de celui du dépôt, sinon il la creuse. Jamais là où elle coulerait.
                    if (ordre) mares.addAll(mares(t, l));
                    if (ordre && mares.isEmpty() && seches * 2 > cases.size())
                        dire(t, "Il n'y a pas de place pour l'eau dans le champ, chef : elle s'en irait, ou c'est trop près d'une construction. Le champ poussera moins vite.");
                    if (!mares.isEmpty() && t.compter(SEAU) == 0 && b != null && b.compter(SEAU) > 0 && Monde.libres(t) > 1) { sous = new Actions.Prendre(SEAU, 1); return Actions.Etat.EN_COURS; }
                }
                case 1 -> {
                    etape = 2;
                    cases.removeAll(mares);
                    // Les graines qui manquent, autant que son sac en tient (une place gardée pour l'herbe arrachée) : rien par terre.
                    int manque = Math.min(cases.size() - t.compter(graine), (Monde.libres(t) - 1) * 64);
                    if (manque > 0 && b != null && b.compter(graine) > 0) { sous = new Actions.Prendre(graine, manque); return Actions.Etat.EN_COURS; }
                }
                case 2 -> {
                    etape = 3;
                    if (aLabourer(l) && meilleure(t, HOUES::objet) == 0 && b != null && b.compter(HOUES) > 0 && Monde.libres(t) > 0) { sous = new Actions.Prendre(HOUES, 1); return Actions.Etat.EN_COURS; }
                }
                case 3 -> {
                    etape = 4;
                    voulues = cases.size();
                    int graines = t.compter(graine);
                    if (graines == 0 && !ordre && plants > 0) { // ses plants poussent : il ressèmera de la récolte
                        plusSansGraines(t);
                        if (mares.isEmpty()) return Actions.Etat.OK;
                    } else if (graines == 0) {
                        t.memoire.putString("sans_graines", graine.texte); // une mare à creuser d'abord : il le montrera après (travailler)
                        if (ordre || mares.isEmpty()) t.bloquer(Statut.SANS_GRAINES);
                        if (ordre) dire(t, "Je n'ai pas de " + nomGraines() + ", chef, ni sur moi ni au dépôt.");
                        else Cerveau.ecrire(t, "graines", Cerveau.UN_JOUR, "graines", nomGraines());
                        if (ordre || mares.isEmpty()) return echec("pas_de_graines");
                    }
                    if (graines == 0) { cases.clear(); return Actions.Etat.EN_COURS; } // sans graines, la mare de l'entretien quand même
                    plusSansGraines(t);
                    if (aLabourer(l) && !equiperMeilleur(t, HOUES::objet)) {
                        if (ordre) dire(t, "Il me faut une houe pour labourer, chef, et il n'y en a pas au dépôt.");
                        else Cerveau.ecrire(t, "houe", 1800, "houe");
                        return echec("pas_de_houe");
                    }
                    if (cases.size() > graines) espacer(l, graines);
                }
                case 4 -> {
                    if (!mares.isEmpty()) return mettreEau(t, l);
                    etape = 5;
                }
                default -> {
                    List<BlockPos> file = cases.isEmpty() ? labours : cases; // les semis d'abord, puis la terre labourée sans graine
                    if (file.isEmpty()) return finir(t);
                    BlockPos q = file.get(0);
                    if (!Actions.aPortee(t, q)) {
                        if (coince() || ++marche > 20 * 30) { file.remove(0); marche = 0; t.blocInaccessible(q); if (coince()) return finir(t); nouveauBut(); return Actions.Etat.EN_COURS; }
                        marcher(t, Vec3.atBottomCenterOf(q.above()), 1.0);
                        return Actions.Etat.EN_COURS;
                    }
                    t.getNavigation().stop();
                    t.getLookControl().setLookAt(Vec3.atCenterOf(q));
                    if (age < prochain) return Actions.Etat.EN_COURS;
                    prochain = age + 5;
                    marche = 0;
                    nouveauBut(); // chaque case est un nouveau but : la marche vers les autres ne le fait pas croire coincé (fantôme)
                    file.remove(0);
                    semer(t, l, q, file == cases);
                }
            }
            return Actions.Etat.EN_COURS;
        }

        /**
         * Où creuser les mares : sur les cases de niveau (l'eau n'y coulera pas, {@link #bassin}) qui peuvent en recevoir une
         * ({@link #place}) et qu'il peut creuser ({@link Lieux#peutCasser} : ni le potager d'un joueur, ni le tampon d'une
         * construction ; sa terre labourée et ses plants d'un champ retenu ne comptent pas, {@link #siens}), pour arroser les
         * cases sèches du champ, semées ou non (à 4 blocs, au même niveau ou un plus bas), une mare par niveau au plus
         * ({@link Cultures#mares}). Le tampon du poste de son chef, et sa terre labourée, seulement pour les cases qu'aucune
         * autre place n'arrose (un champ collé au poste, un vieux champ tout labouré) ; sous un de ses plants, seulement si
         * aucune autre place ne convient (un champ tout semé). À la création du champ, et à l'entretien une fois par jour au
         * plus ; {@link #seches} : les cases sèches comptées (une terre labourée humide l'est sans relire l'eau autour).
         */
        List<BlockPos> mares(Travailleur t, ServerLevel l) {
            List<Cultures.Point> seches = new ArrayList<>();
            List<BlockPos> champ = new ArrayList<>(cases);
            champ.addAll(terres);
            for (BlockPos q : champ) {
                BlockState s = l.getBlockState(q);
                if (!(s.is(Blocks.FARMLAND) && s.getValue(FarmBlock.MOISTURE) == FarmBlock.MAX_MOISTURE) && !arrose(l, q)) seches.add(new Cultures.Point(q.getX(), q.getY(), q.getZ()));
            }
            this.seches = seches.size();
            if (seches.isEmpty()) return List.of();
            List<BlockPos> r = mares(t, l, seches, champ, false);
            return r.isEmpty() && !terres.isEmpty() ? mares(t, l, seches, champ, true) : r;
        }

        /** Les mares pour ces cases sèches, sur les places du champ ; sous un de ses plants seulement si {@code plant}. */
        List<BlockPos> mares(Travailleur t, ServerLevel l, List<Cultures.Point> seches, List<BlockPos> champ, boolean plant) {
            List<Cultures.Point> libres = new ArrayList<>(), poste = new ArrayList<>();
            for (BlockPos q : champ) if (place(l, q, plant)) (Lieux.peutCasser(t, q) ? libres : poste).add(new Cultures.Point(q.getX(), q.getY(), q.getZ()));
            // Le tampon (coûteux, sans compter son champ) ne se relit que pour les places retenues ; mettreEau le revérifie de même.
            return Cultures.mares(seches, libres, poste, p -> Lieux.peutCasserPresDuPoste(t, new BlockPos(p.x(), p.y(), p.z()), siens()), champ.size(), centre.getX(), centre.getZ())
                    .stream().map(p -> new BlockPos(p.x(), p.y(), p.z())).toList();
        }

        /**
         * Une mare peut aller en q : de l'herbe ou de la terre ; à l'entretien d'un champ retenu, aussi sa terre labourée du
         * champ ({@link #siens}, jamais pendant un ordre : le potager d'un autre), sous un de ses plants si {@code plant} ;
         * et l'eau n'y coulera pas ({@link #bassin}).
         */
        boolean place(ServerLevel l, BlockPos q, boolean plant) {
            BlockState s = l.getBlockState(q);
            boolean sienne = s.is(Blocks.FARMLAND) && siens.contains(q.asLong());
            if (!labourable(s) && !sienne) return false;
            if (l.getBlockState(q.above()).getBlock() instanceof CropBlock && !(plant && sienne)) return false;
            return bassin(l, q);
        }

        /** Sa terre labourée et ses plants, pour {@link Lieux#peutCasserPresDuPoste} ; null s'il n'en a pas (un ordre). */
        it.unimi.dsi.fastutil.longs.LongSet siens() { return siens.isEmpty() ? null : siens; }

        /** Ce champ dans ses champs retenus (la mémoire, où il note sa dernière recherche de mare), ou null. */
        CompoundTag fiche(Travailleur t) {
            ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
            for (int i = 0; i < champs.size(); i++) if (champs.getCompound(i).getLong("c") == centre.asLong()) return champs.getCompound(i);
            return null;
        }

        /** Sa terre labourée en q : elle ne compte pas comme construction pour ses mares, ni le plant dessus ; sous un plant ({@code semee}), elle compte pour l'eau. */
        void terre(ServerLevel l, BlockPos q, boolean semee) {
            if (!l.getBlockState(q).is(Blocks.FARMLAND)) return;
            siens.add(q.asLong());
            siens.add(q.above().asLong());
            if (semee) terres.add(q.immutable());
        }

        /**
         * Moins de graines que de cases : il sème une case sur deux en tout sens (deux plants qui se touchent, même en coin,
         * poussent deux fois moins vite), et laboure les autres sans graine. Les cases vides se rempliront de la récolte.
         */
        void espacer(ServerLevel l, int graines) {
            Parcelle p = new Parcelle(centre, plan, culture);
            int f0 = -(plan.longueur() - 1) / 2, c0 = -(plan.largeur() - 1) / 2;
            List<BlockPos> espacees = new ArrayList<>();
            for (BlockPos q : cases) {
                int[] k = p.coordonnees(q.getX(), q.getZ());
                if (espacees.size() < graines && Math.floorMod(k[0] - f0, 2) == 0 && Math.floorMod(k[1] - c0, 2) == 0) espacees.add(q);
            }
            for (BlockPos q : cases) if (espacees.size() < graines && !espacees.contains(q)) espacees.add(q); // plus de graines que de cases espacées
            for (BlockPos q : cases) if (!espacees.contains(q) && !l.getBlockState(q).is(Blocks.FARMLAND)) labours.add(q); // déjà labourée : rien à faire
            cases.clear();
            cases.addAll(espacees);
        }

        String nomGraines() {
            return switch (culture.id()) {
                case "ble" -> "graines de blé";
                case "betterave" -> "graines de betterave";
                case "carotte" -> "carottes à planter";
                default -> "pommes de terre à planter";
            };
        }

        /**
         * Les sols à semer : les cases vides du champ ; à l'entretien, le rang autour aussi, seulement là où l'eau arrose ou
         * la terre est déjà labourée.
         */
        void relever(Travailleur t, ServerLevel l) {
            Fouille.Plan p = ordre ? plan : Cultures.agrandi(plan);
            for (int[] k : Cultures.cases(p)) {
                BlockPos col = centre.relative(d, k[0]).relative(d.getClockWise(), k[1]);
                boolean sienne = !ordre && Cultures.dans(plan, k[0], k[1]); // sa terre labourée : celle d'un champ retenu, pas le rang autour
                for (int y = centre.getY() + 3; y >= centre.getY() - 3; y--) // ses plants, à toute hauteur du champ (terrain en marches)
                    if (l.getBlockState(col.atY(y)).getBlock() instanceof CropBlock) { if (aLui(t, col.atY(y))) { plants++; if (sienne) terre(l, col.atY(y - 1), true); } break; }
                BlockPos q = sol(t, l, col, centre.getY());
                if (q != null && (ordre || Cultures.dans(plan, k[0], k[1]) || l.getBlockState(q).is(Blocks.FARMLAND) || arrose(l, q))) { cases.add(q); if (sienne) terre(l, q, false); }
            }
        }

        boolean aLabourer(ServerLevel l) {
            for (BlockPos q : cases) if (!l.getBlockState(q).is(Blocks.FARMLAND)) return true;
            return false;
        }

        /**
         * La prochaine mare. Juste avant, il revérifie la case (de la terre ou de l'herbe, libre dessus, rien de bâti, et l'eau
         * n'y coulera pas) et vide d'abord son seau (le seau vide lui reste) ; puis il creuse le sol (la terre va au sac) et y
         * met l'eau. Sinon, pas d'eau là : il le dit, et la case sera semée comme les autres.
         */
        Actions.Etat mettreEau(Travailleur t, ServerLevel l) {
            BlockPos q = mares.get(0);
            if (!Actions.aPortee(t, q)) {
                if (coince() || ++marche > 20 * 30) return sansEau(t, "Je n'arrive pas à l'endroit de la mare, chef : pas d'eau là.");
                marcher(t, Vec3.atBottomCenterOf(q.above()), 1.0);
                return Actions.Etat.EN_COURS;
            }
            marche = 0;
            if (!place(l, q, true) || Monde.construit(l, q) || !Lieux.peutCasserPresDuPoste(t, q, siens()))
                return sansEau(t, "L'endroit de la mare a changé, chef : l'eau s'en irait, je n'en mets pas.");
            if (Monde.libres(t) < 2) return sansEau(t, "Mon sac est trop plein pour creuser la mare, chef : pas d'eau là.");
            // Son seau d'eau s'il en a un (le seau vide lui reste) ; sinon il creuse une mare : l'eau monte du sol.
            boolean vide = false;
            if (SEAU.objet(t.getMainHandItem())) { t.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BUCKET)); vide = true; }
            else for (int i = 0; i < t.inventaire.getContainerSize() && !vide; i++)
                if (SEAU.objet(t.inventaire.getItem(i))) { t.inventaire.setItem(i, new ItemStack(Items.BUCKET)); vide = true; }
            // Sans seau d'eau, il creuse une mare : sans eau, le blé met une semaine à mûrir (fantôme : 0 blé en 2 jours).
            if (!vide) dire(t, faites == 0 ? "Je n'ai pas de seau d'eau, chef : je creuse une mare dans le champ."
                    : "Je creuse une autre mare, chef : l'eau n'arrose pas tout le champ.");
            if (!l.getBlockState(q.above()).isAir()) Actions.recolter(t, q.above()); // l'herbe haute
            Actions.recolter(t, q);
            l.setBlockAndUpdate(q, Blocks.WATER.defaultBlockState());
            l.playSound(null, q, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1, 1);
            t.swing(InteractionHand.MAIN_HAND);
            mares.remove(0);
            faites++;
            nouveauBut();
            return Actions.Etat.EN_COURS;
        }

        /** Pas d'eau finalement à cet endroit : il le dit, et la case redevient une case à semer. */
        Actions.Etat sansEau(Travailleur t, String texte) {
            marche = 0;
            dire(t, texte);
            cases.add(mares.remove(0));
            nouveauBut();
            return Actions.Etat.EN_COURS;
        }

        /** Une case : l'herbe arrachée (au sac), la terre labourée, la graine semée ({@code graineAussi}). Faux si rien n'est semé. */
        boolean semer(Travailleur t, ServerLevel l, BlockPos q, boolean graineAussi) {
            BlockState sol = l.getBlockState(q), dessus = l.getBlockState(q.above());
            if (!libreDessus(dessus) || Monde.construit(l, q) || !sol.is(Blocks.FARMLAND) && !labourable(sol)) return false;
            if (!sol.is(Blocks.FARMLAND) && !HOUES.objet(t.getMainHandItem())) return false; // la houe d'abord, avant d'arracher l'herbe
            Block bloc = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(culture.bloc()));
            if (graineAussi && t.compter(graine) == 0) return false;
            if (!dessus.isAir()) {
                if (Monde.libres(t) == 0) return false;
                Actions.recolter(t, q.above());
            }
            if (!sol.is(Blocks.FARMLAND)) {
                l.setBlockAndUpdate(q, Blocks.FARMLAND.defaultBlockState());
                l.playSound(null, q, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1, 1);
                Actions.userOutil(t, 1);
            }
            if (!graineAussi) return false;
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
            }
            return Actions.Etat.OK;
        }

        /** Il retient le champ, avec son plan d'origine, pour l'entretenir ; 8 champs au plus. */
        void retenir(Travailleur t) {
            ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
            for (int i = champs.size() - 1; i >= 0; i--) if (champs.getCompound(i).getLong("c") == centre.asLong()) champs.remove(i);
            CompoundTag c = new CompoundTag();
            c.putLong("c", centre.asLong());
            c.putString("p", plan.vers(d.getName(), false).texte());
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
            boolean ok = (s.is(Blocks.FARMLAND) || labourable(s)) && !Monde.construit(l, q) && t.chezLui(q) && l.canSeeSky(q.above()) && aLui(t, q);
            return ok ? q.immutable() : null;
        }
        return null;
    }

    // --- Sur ordre : récolter tout de suite, faire du pain --------------------------------------------------------

    /** L'action qui joue une consigne du fermier (« champ », « recolter », « pain », « oublier_champ ») ; elle dit elle-même ce qu'elle a fait. */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) {
        return switch (mode) {
            case "champ" -> {
                String[] x = c.texte.split("\\|");
                Fouille.Plan p = Fouille.Plan.de(x[0]);
                Cultures.Culture k = x.length > 1 ? Cultures.parId(x[1]) : null;
                yield new Champ(c.lieu, p == null ? Cultures.mesures("") : p, k == null ? Cultures.TOUTES.get(0) : k, true);
            }
            case "pain" -> new Pain(Math.max(1, c.nombre));
            case "oublier_champ" -> new Oubli(c.lieu);
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
                    for (int i = 0; i < 2 && zone < ZONES_VIE.size(); i++) mures.addAll(mures(t, ZONES_VIE, zone++, null, null, new boolean[2]));
                    if (zone < ZONES_VIE.size()) return Actions.Etat.EN_COURS;
                    etape = 1;
                    if (mures.isEmpty() && recoltees == 0) { Paroles.dire(t, "Rien n'est mûr dans les champs pour l'instant, chef.", "calme"); return Actions.Etat.OK; }
                    sous = new Moisson(mures, null, commandee(t));
                    mures.clear();
                }
                case 1 -> {
                    etape = 2;
                    Metier m = Metier.de(t.metier);
                    sous = new Actions.Deposer(m == null ? List.of() : m.garder);
                }
                default -> {
                    if (plein && ++tours < 3) { plein = false; etape = 0; zone = 0; return Actions.Etat.EN_COURS; }
                    Paroles.dire(t, recoltees == 0 ? "Je n'ai rien pu récolter, chef." : "La récolte est au dépôt, chef : " + recoltees + " plants récoltés et ressemés.", "calme");
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
        /** Le cuisinier, de lui-même : le pain va dans son sac (il le livre au dépôt ensuite, ce qui compte pour ses commandes), sans un mot. */
        final boolean seul;
        int faits;

        Pain(int voulu) { this(voulu, false); }

        Pain(int voulu, boolean seul) { super("pain"); this.voulu = voulu; this.seul = seul; }

        Actions.Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            if (age % 10 != 0) return Actions.Etat.EN_COURS;
            if (seul && (faits >= voulu || t.compter(BLE) + b.compter(BLE) < 3 || Monde.libres(t) == 0)) return faits == 0 ? echec("pas_de_ble") : Actions.Etat.OK;
            if (faits >= voulu || t.compter(BLE) + b.compter(BLE) < 3) return dire(t);
            List<ItemStack> ble = new ArrayList<>();
            while (ble.size() < 3) {
                ItemStack s = Actions.sortir(t, BLE);
                if (s.isEmpty() && BLE.objet(t.getMainHandItem())) s = t.getMainHandItem().split(1); // le blé qu'il tient en main
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
            t.swing(InteractionHand.MAIN_HAND);
            if (seul) { Guerrier.garder(t, b, pain); return Actions.Etat.EN_COURS; } // ce qui n'entre pas au sac reste au dépôt
            ItemStack reste = b.ranger(pain);
            if (!reste.isEmpty()) t.ranger(reste);
            if (!reste.isEmpty()) { Paroles.dire(t, faits + " pain" + (faits > 1 ? "s" : "") + " de faits, chef. Le dépôt est plein : je garde le reste sur moi.", "calme"); return echec("depot_plein"); }
            return Actions.Etat.EN_COURS;
        }

        Actions.Etat dire(Travailleur t) {
            Paroles.dire(t, faits == 0 ? "Je n'ai pas assez de blé, chef : il en faut trois pour un pain, et il n'y en a ni sur moi ni au dépôt."
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

    /**
     * « Oublie ce champ » : il retire de ses champs marqués celui que regarde le chef (celui qui contient l'endroit, sinon le
     * plus proche à 16 blocs au plus). Il n'y sème plus et n'y récolte plus ; il ne touche à rien de ce qui pousse.
     */
    static final class Oubli extends Actions.Action {
        final BlockPos vu;

        Oubli(BlockPos vu) { super("oubli_champ"); this.vu = vu; }

        Actions.Etat tick(Travailleur t) {
            ListTag champs = t.memoire.getList("champs", Tag.TAG_COMPOUND);
            int k = -1;
            double mieux = 16 * 16 + 1;
            for (int i = 0; i < champs.size(); i++) {
                Parcelle p = Parcelle.de(champs.getCompound(i));
                if (p == null) continue;
                double dd = p.contient(vu.getX(), vu.getZ()) ? -1 : distance(p.centre, vu);
                if (dd < mieux) { mieux = dd; k = i; }
            }
            if (k < 0) { Paroles.dire(t, "Je n'ai pas de champ par là, chef.", "calme"); return echec("pas_de_champ"); }
            champs.remove(k);
            t.memoire.put("champs", champs);
            Paroles.dire(t, "Bien, chef : je laisse ce champ, je n'y sème plus et je n'y récolte plus.", "calme");
            return Actions.Etat.OK;
        }
    }

    // --- Les règles ---------------------------------------------------------------------------------------------

    /** Un ouvrier ne piétine jamais une terre labourée, en sautant ou en tombant dessus. */
    static void pietiner(BlockEvent.FarmlandTrampleEvent e) {
        if (e.getEntity() instanceof Travailleur) e.setCanceled(true);
    }
}

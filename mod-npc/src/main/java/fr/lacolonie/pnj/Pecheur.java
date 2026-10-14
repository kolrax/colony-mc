package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Le pêcheur (lot 6, cahier 4.2). Il prend sa canne au dépôt (cahier 3.12), cherche une eau de pêche près de son poste
 * (32 blocs au plus : une eau de source à ciel ouvert, un carré de 3 x 3 au moins, une rive sèche à côté, jamais chez un
 * voisin), la retient, et y pêche debout sur la rive : une prise toutes les 15 à 40 s (plus vite sous la pluie), tirée de
 * la table de pêche du jeu (poissons, déchets, trésors en eau libre). La canne s'use. Le sac presque plein, après une
 * dizaine de prises, ou en fin de journée, il porte tout au dépôt. Sur ordre : « va pêcher », « pêche ici » (l'endroit regardé devient son coin).
 * <p>
 * Pas de grand balayage : la recherche de l'eau lit une colonne sur trois (la hauteur du sol est connue du jeu), par petits
 * morceaux à chaque appel du cerveau, et le coin trouvé est gardé avec lui. Pas de vrai flotteur (il lui faudrait un
 * joueur) : un son et des remous suffisent.
 */
final class Pecheur extends Metier {
    /** Une canne de n'importe quel mod ({@code sorte}), et celle du jeu ({@code bon}). */
    static final Actions.Filtre CANNES = Actions.Filtre.de("#c:tools/fishing_rod|minecraft:fishing_rod"), CANNE = Actions.Filtre.de("minecraft:fishing_rod");
    /** Après tant de prises, il les porte au dépôt. */
    static final int PRISES = 10;
    /** Colonnes de la grille lues par appel du cerveau. */
    static final int PAR_APPEL = 24;
    /** Une eau qu'il n'a pas pu atteindre : laissée tant de secondes. */
    static final int OUBLI_EAU = 600;

    Pecheur() {
        super("pecheur", "Pêcheur", "Pêche au bord de l'eau près du poste, avec la canne prise au dépôt, et rapporte morues, saumons et autres prises.",
                false, CANNES.texte);
        commandes.add(new Commande("poisson", "Poissons", "minecraft:cod|minecraft:salmon|minecraft:tropical_fish|minecraft:pufferfish"));
        commandes.add(new Commande("morue", "Morues", "minecraft:cod"));
        commandes.add(new Commande("saumon", "Saumons", "minecraft:salmon"));
    }

    /** Son coin de pêche : le centre du carré d'eau (la surface), la case de la rive où il se tient, et s'il lui a été donné. */
    record Coin(BlockPos eau, BlockPos rive, boolean ordre) {
        CompoundTag tag() {
            CompoundTag c = new CompoundTag();
            c.putLong("e", eau.asLong());
            c.putLong("r", rive.asLong());
            c.putBoolean("o", ordre);
            return c;
        }

        static Coin de(CompoundTag c) { return c.contains("e") ? new Coin(BlockPos.of(c.getLong("e")), BlockPos.of(c.getLong("r")), c.getBoolean("o")) : null; }

        /** Le flotteur : sur l'eau, au milieu du carré. */
        Vec3 flotteur() { return new Vec3(eau.getX() + 0.5, eau.getY() + 0.9, eau.getZ() + 0.5); }
    }

    /** Où en est sa recherche de l'eau (rang dans la grille ; pas sauvegardé : il recommence au poste). */
    static final Map<Travailleur, int[]> RECHERCHES = new WeakHashMap<>();

    @Override
    void travailler(Travailleur t) {
        // Son sac vidé (au dépôt, par lui ou le soir), le compte de ses prises repart de zéro.
        if (t.memoire.getInt("prises") > 0 && rienAuSac(t)) t.memoire.putInt("prises", 0);
        // Sa canne, prise au dépôt.
        if (outilAuDepot(t, CANNES, CANNE, "canne à pêche")) return;

        // Les prises au dépôt : le sac presque plein, une dizaine de prises, ou de quoi finir ce qu'on lui a commandé.
        Metier.Commande c = Metier.commande(t);
        Travailleur.Mission m = t.missionActive();
        boolean finir = c != null && c.objet != null && m != null && m.quantite() && t.compter(c.objet) > 0 && m.livre + t.compter(c.objet) >= m.nombre;
        // En fin de journée (moins d'une demi-heure de service), ce qu'il a pris rentre aussi au dépôt.
        boolean soir = t.memoire.getInt("prises") > 0 && t.heuresAvantFinService() < 0.5;
        if (Monde.libres(t) < 3 || t.memoire.getInt("prises") >= PRISES || finir || soir) {
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                Cerveau.attendre(t, 30);
                return;
            }
            t.memoire.putInt("prises", 0);
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }

        // Sans canne, rien à faire : il le dit à son chef, et attend qu'on lui en laisse une au dépôt.
        if (meilleure(t, s -> convient(s, CANNES, CANNE)) == 0) {
            Cerveau.ecrire(t, "canne_peche", 1800, "canne_peche");
            Cerveau.attendre(t, 10);
            return;
        }

        // Le coin que le chef lui a donné est hors d'atteinte pour l'instant : il réessaiera.
        if (t.memoire.getLong("peche_pause") > Cerveau.temps(t)) { Cerveau.attendre(t, 10); return; }
        Coin coin = coin(t);
        if (coin == null) coin = chercher(t);
        if (coin != null) Cerveau.lancer(t, new Pecher(coin));
    }

    /**
     * Son coin retenu, s'il est toujours bon : les mêmes contrôles que {@link #trouver} (son espace de vie, 32 blocs du poste
     * sauf sur ordre, à ciel ouvert, à lui), de l'eau, une rive où se tenir, ni écartée ni prise par un autre ; sinon il l'oublie.
     */
    static Coin coin(Travailleur t) {
        Coin c = Coin.de(t.memoire.getCompound("peche"));
        if (c == null) return null;
        ServerLevel l = (ServerLevel) t.level();
        if (!l.isLoaded(c.eau) || !l.isLoaded(c.rive)) return c; // il verra sur place
        if (valable(t, l, c.eau, c.ordre) && eau(l, c.eau.getX(), c.eau.getY(), c.eau.getZ()) && debout(l, c.rive)
                && (c.ordre || !ecartee(t, c.rive)) && !prises(t, c.rive).contains(c.rive)) return c;
        t.memoire.remove("peche");
        return null;
    }

    /** Une eau où il peut pêcher : dans son espace de vie (à 32 blocs du poste, sauf sur ordre), à ciel ouvert, à lui. */
    static boolean valable(Travailleur t, ServerLevel l, BlockPos centre, boolean ordre) {
        if (!ordre && Fermier.distance(t.bureau, centre) > Peche.PORTEE * Peche.PORTEE) return false;
        return t.chezLui(centre) && l.canSeeSky(centre.above()) && Fermier.aLui(t, centre);
    }

    static void retenir(Travailleur t, Coin c) { t.memoire.put("peche", c.tag()); }

    /** Renvoyé (ou réembauché ailleurs), il oublie tout de sa pêche. */
    static void oublier(Travailleur t) {
        for (String k : List.of("peche", "prises", "eau_revue", "rives_ecartees", "peche_pause", "ordre_echecs")) t.memoire.remove(k);
        RECHERCHES.remove(t);
    }

    /** Rien au sac que ce qu'il garde (sa canne, son bateau) : ses prises sont au dépôt. */
    boolean rienAuSac(Travailleur t) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!s.isEmpty() && garder.stream().noneMatch(f -> f.objet(s))) return false;
        }
        return true;
    }

    /** Les rives que tiennent déjà les autres ouvriers de son chef, alentour (deux pêcheurs ne se marchent pas dessus). */
    static List<BlockPos> prises(Travailleur t, BlockPos pres) {
        List<BlockPos> r = new ArrayList<>();
        for (Travailleur x : t.level().getEntitiesOfClass(Travailleur.class, new net.minecraft.world.phys.AABB(pres).inflate(48),
                x -> x != t && x.isAlive() && java.util.Objects.equals(x.chef, t.chef))) {
            Coin c = Coin.de(x.memoire.getCompound("peche"));
            if (c != null) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) r.add(c.rive.offset(dx, 0, dz)); // et ses voisines
        }
        return r;
    }

    /** Une rive qu'il n'a pas pu atteindre, écartée 10 minutes (16 au plus, souvenir gardé avec lui). */
    static boolean ecartee(Travailleur t, BlockPos rive) {
        CompoundTag m = t.memoire.getCompound("rives_ecartees");
        String k = Long.toString(rive.asLong());
        return m.contains(k) && m.getLong(k) > Cerveau.temps(t);
    }

    /**
     * Il n'arrive pas à sa rive. Un coin à lui : la rive est écartée 10 minutes, il en cherchera une autre (toutes écartées :
     * la recherche échoue, et il l'écrit). Le coin que lui a donné le chef : il le garde, réessaie dans 2 minutes, et le dit
     * au chef après trois échecs.
     */
    static void ecarter(Travailleur t, Coin c) {
        long maintenant = Cerveau.temps(t);
        if (c.ordre) {
            int n = t.memoire.getInt("ordre_echecs") + 1;
            t.memoire.putInt("ordre_echecs", n);
            t.memoire.putLong("peche_pause", maintenant + 120);
            if (n >= 3) Cerveau.ecrire(t, "coin_peche", Cerveau.UN_JOUR, "coin_peche_inaccessible");
            return;
        }
        CompoundTag m = t.memoire.getCompound("rives_ecartees");
        if (m.size() >= 16) for (String k : new ArrayList<>(m.getAllKeys())) if (m.getLong(k) <= maintenant) m.remove(k);
        if (m.size() < 16) m.putLong(Long.toString(c.rive.asLong()), maintenant + OUBLI_EAU);
        t.memoire.put("rives_ecartees", m);
        t.memoire.remove("peche");
    }

    /**
     * Un morceau de la recherche : les {@link #PAR_APPEL} colonnes suivantes de la grille autour du poste. Le coin trouvé est
     * retenu ; la grille finie sans rien, il le dit à son chef (une lettre par jour) et ne cherche plus avant 5 minutes.
     */
    static Coin chercher(Travailleur t) {
        long maintenant = Cerveau.temps(t);
        if (t.memoire.getLong("eau_revue") > maintenant) { Cerveau.attendre(t, 20); return null; }
        ServerLevel l = (ServerLevel) t.level();
        int[] i = RECHERCHES.computeIfAbsent(t, k -> new int[1]);
        for (int n = 0; n < PAR_APPEL && i[0] < Peche.GRILLE.size(); n++, i[0]++) {
            int[] k = Peche.GRILLE.get(i[0]);
            Coin c = trouver(t, l, t.bureau.getX() + k[0], t.bureau.getZ() + k[1], false);
            if (c != null) {
                i[0] = 0;
                retenir(t, c);
                return c;
            }
        }
        if (i[0] < Peche.GRILLE.size()) return null; // la suite au prochain appel, dans une demi-seconde
        i[0] = 0;
        t.memoire.putLong("eau_revue", maintenant + 300);
        Cerveau.ecrire(t, "sans_eau_peche", Cerveau.UN_JOUR, "sans_eau_peche");
        Cerveau.attendre(t, 20);
        return null;
    }

    /**
     * Un coin de pêche autour de la colonne (x, z), si sa surface est de l'eau : le relevé de 7 x 7 autour, les centres
     * possibles ({@link Peche#centres}), chacun à lui, dans son espace de vie (à 32 blocs du poste, sauf sur ordre), pas
     * laissé ; puis la rive la plus proche du poste où il tient debout. Null sinon.
     */
    static Coin trouver(Travailleur t, ServerLevel l, int x, int z, boolean ordre) {
        if (!l.hasChunkAt(x, z)) return null;
        int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (!eau(l, x, y, z)) return null;
        boolean[][] r = new boolean[Peche.RELEVE][Peche.RELEVE];
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) r[i + 3][j + 3] = l.hasChunkAt(x + i, z + j) && eau(l, x + i, y, z + j);
        List<BlockPos> prises = null;
        for (int[] c : Peche.centres(r)) {
            BlockPos centre = new BlockPos(x + c[0], y, z + c[1]);
            if (!valable(t, l, centre, ordre)) continue;
            if (prises == null) prises = prises(t, centre);
            List<BlockPos> tenues = prises;
            BlockPos rive = Peche.rives(r, c[0], c[1]).stream().map(k -> new BlockPos(x + k[0], y, z + k[1]))
                    .sorted(Comparator.comparingDouble(q -> Fermier.distance(t.bureau, q)))
                    .map(q -> pied(l, q)).filter(q -> q != null && !ecartee(t, q) && !tenues.contains(q)).findFirst().orElse(null);
            if (rive != null) return new Coin(centre, rive, ordre);
        }
        return null;
    }

    /** Une eau de pêche : de l'eau de source, à la surface (rien au-dessus, d'après la hauteur connue du jeu). */
    static boolean eau(ServerLevel l, int x, int y, int z) {
        if (l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1 != y) return false;
        BlockState s = l.getBlockState(new BlockPos(x, y, z));
        return s.is(Blocks.WATER) && s.getFluidState().isSource();
    }

    /** Où se tenir dans cette colonne de la rive, du niveau de l'eau à 2 blocs au-dessus : null s'il n'y tient pas au sec. */
    static BlockPos pied(ServerLevel l, BlockPos q) {
        if (!l.isLoaded(q)) return null;
        for (int dy = 2; dy >= 0; dy--) if (debout(l, q.above(dy))) return q.above(dy);
        return null;
    }

    /** Il tient debout là, au sec : un sol solide dessous (pas de l'eau), rien de solide ni de liquide aux pieds et à la tête. */
    static boolean debout(ServerLevel l, BlockPos f) {
        BlockState sol = l.getBlockState(f.below());
        if (!sol.getFluidState().isEmpty() || !sol.isFaceSturdy(l, f.below(), Direction.UP)) return false;
        for (BlockPos q : List.of(f, f.above())) {
            BlockState s = l.getBlockState(q);
            if (!s.getFluidState().isEmpty() || !s.getCollisionShape(l, q).isEmpty()) return false;
        }
        return true;
    }

    /** Le type d'un bloc autour du flotteur, comme le jeu le voit ({@code FishingHook.getOpenWaterTypeForBlock}). */
    static int typeEau(ServerLevel l, BlockPos q) {
        BlockState s = l.getBlockState(q);
        if (s.isAir() || s.is(Blocks.LILY_PAD)) return Peche.DESSUS;
        return s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && s.getFluidState().isSource() && s.getCollisionShape(l, q).isEmpty() ? Peche.DEDANS : Peche.INVALIDE;
    }

    /** L'eau libre du jeu autour du flotteur (la surface en p) : 5 x 5, de y - 1 à y + 2 ({@link Peche#eauLibre}). */
    static boolean eauLibre(ServerLevel l, BlockPos p) {
        int[] couches = new int[4];
        for (int i = -1; i <= 2; i++) {
            int type = -1;
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, i, -2), p.offset(2, i, 2))) {
                int k = l.isLoaded(q) ? typeEau(l, q) : Peche.INVALIDE;
                if (type < 0) type = k;
                else if (type != k) { type = Peche.INVALIDE; break; }
            }
            couches[i + 1] = type;
        }
        return Peche.eauLibre(couches);
    }

    /**
     * Ce que donne une prise : la table de pêche du jeu, avec l'origine (le flotteur), la canne, sa Chance de la mer, et un
     * flotteur fictif (jamais ajouté au monde) qui dit si l'eau est libre comme le jeu la mesure ({@link #eauLibre}) : les
     * trésors ne viennent qu'en eau libre ; une petite mare ne donne que poissons et déchets.
     */
    static List<ItemStack> butin(Travailleur t, ServerLevel l, Vec3 flotteur, ItemStack canne) {
        boolean libre = eauLibre(l, BlockPos.containing(flotteur));
        FishingHook f = new FishingHook(EntityType.FISHING_BOBBER, l) {
            @Override
            public boolean isOpenWaterFishing() { return libre; }
        };
        f.setPos(flotteur);
        LootParams p = new LootParams.Builder(l)
                .withParameter(LootContextParams.ORIGIN, flotteur)
                .withParameter(LootContextParams.TOOL, canne)
                .withParameter(LootContextParams.THIS_ENTITY, f)
                .withParameter(LootContextParams.ATTACKING_ENTITY, t)
                .withLuck(EnchantmentHelper.getFishingLuckBonus(l, canne, t))
                .create(LootContextParamSets.FISHING);
        return l.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING).getRandomItems(p);
    }

    /**
     * Une prise : il va à la rive (jamais dans l'eau), regarde l'eau, canne en main, lance ; 15 à 40 s plus tard (moins sous
     * la pluie, moins avec l'Appât), ça mord : l'eau gicle, il ramène la prise au sac, la canne s'use. Puis le cerveau
     * reprend la main (faim, fin de journée, sac), et relance une prise.
     */
    static final class Pecher extends Actions.Action {
        final Coin coin;
        int marche, lance = -1, attente;

        Pecher(Coin coin) { super("pecher"); this.coin = coin; }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            Vec3 pied = Vec3.atBottomCenterOf(coin.rive), flotteur = coin.flotteur();
            if (Monde.libres(t) < 2) return echec("sac_plein");
            if (lance >= 0) {
                // Poussé à l'eau ou hors de sa rive pendant l'attente : il la regagne, et relancera.
                double ex = t.getX() - pied.x, ez = t.getZ() - pied.z;
                if (t.isInWater() || ex * ex + ez * ez > 1.5 * 1.5) { lance = -1; marche = 0; }
            }
            if (lance < 0) {
                double dx = t.getX() - pied.x, dz = t.getZ() - pied.z;
                if (dx * dx + dz * dz > 0.6 * 0.6 || Math.abs(t.getY() - pied.y) > 1.2) {
                    if (coince() || ++marche > 20 * 60) {
                        // Il n'y arrive pas : cette rive est écartée (ou, donnée par le chef, réessayée plus tard).
                        ecarter(t, coin);
                        return echec("rive_laissee");
                    }
                    marcher(t, pied, 1.0);
                    return Actions.Etat.EN_COURS;
                }
                t.getNavigation().stop();
                if (!equiperMeilleur(t, s -> convient(s, CANNES, CANNE))) return echec("pas_de_canne");
                lance = age;
                int appat = (int) (EnchantmentHelper.getFishingTimeReduction(l, t.getMainHandItem(), t) * 20);
                attente = Peche.attente(t.getRandom().nextDouble(), l.isRainingAt(coin.eau.above()), appat);
                t.swing(InteractionHand.MAIN_HAND);
                l.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5f, 0.4f / (t.getRandom().nextFloat() * 0.4f + 0.8f));
                l.sendParticles(ParticleTypes.SPLASH, flotteur.x, flotteur.y, flotteur.z, 4, 0.1, 0, 0.1, 0);
            }
            t.getLookControl().setLookAt(flotteur);
            int attendu = age - lance;
            // Les petits remous autour du flotteur, et le poisson qui approche juste avant de mordre.
            if (attendu % 30 == 0) l.sendParticles(ParticleTypes.FISHING, flotteur.x, flotteur.y, flotteur.z, 1, 0.05, 0, 0.05, 0);
            if (attente - attendu < 40 && attendu % 4 == 0) l.sendParticles(ParticleTypes.BUBBLE, flotteur.x, flotteur.y - 0.3, flotteur.z, 2, 0.2, 0.1, 0.2, 0);
            if (attendu == attente / 2 && t.getRandom().nextInt(4) == 0) Besoins.parler(t, "peche_attente", null);
            if (attendu < attente) return Actions.Etat.EN_COURS;
            // Ça mord !
            ItemStack canne = t.getMainHandItem();
            if (!convient(canne, CANNES, CANNE)) return echec("pas_de_canne");
            l.playSound(null, flotteur.x, flotteur.y, flotteur.z, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.25f, 1 + (t.getRandom().nextFloat() - t.getRandom().nextFloat()) * 0.4f);
            l.sendParticles(ParticleTypes.SPLASH, flotteur.x, flotteur.y, flotteur.z, 12, 0.2, 0, 0.2, 0);
            l.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1, 0.4f / (t.getRandom().nextFloat() * 0.4f + 0.8f));
            t.swing(InteractionHand.MAIN_HAND);
            List<ItemStack> prises = butin(t, l, flotteur, canne);
            // Il en dit un mot, de temps en temps (un trésor, toujours s'il le peut ; Besoins.permis espace les répliques).
            if (!prises.isEmpty()) {
                ItemStack p = prises.get(0);
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getItem()).toString(), genre = Peche.genre(id, p.isEnchanted());
                if (!genre.equals("peche_prise") || t.getRandom().nextInt(3) == 0) Besoins.parler(t, genre, null, Peche.NOMS.get(id));
            }
            for (ItemStack s : prises) t.ranger(s);
            Actions.userOutil(t, 1);
            t.memoire.putInt("prises", t.memoire.getInt("prises") + 1);
            t.memoire.remove("ordre_echecs");
            travail = true; // du vrai travail (Coince)
            return Actions.Etat.OK;
        }
    }

    // --- Sur ordre -----------------------------------------------------------------------------------------------

    /** L'action qui joue une consigne du pêcheur (« pecher », « pecher_la ») ; elle dit elle-même ce qu'elle a fait. */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) {
        return mode.equals("pecher_la") ? new Ici(c.lieu) : new AuTravail();
    }

    /** « Va pêcher » : au travail, au bord de l'eau. */
    static final class AuTravail extends Actions.Action {
        AuTravail() { super("va_pecher"); }

        Actions.Etat tick(Travailleur t) {
            if (t.mode != Travailleur.Mode.GREVE) { t.mode = Travailleur.Mode.TRAVAIL; t.majNom(); }
            t.memoire.remove("rappel");
            t.memoire.remove("eau_revue"); // il regarde de nouveau autour du poste
            Voix.dire(t, "J'y vais, chef : je prends ma canne et je file au bord de l'eau.", "calme");
            return Actions.Etat.OK;
        }
    }

    /** « Pêche ici » : l'eau que regarde le chef (à 5 blocs près) devient son coin de pêche, retenu. */
    static final class Ici extends Actions.Action {
        final BlockPos vu;

        Ici(BlockPos vu) { super("pecher_la"); this.vu = vu; }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            List<int[]> autour = new ArrayList<>();
            for (int i = -5; i <= 5; i++) for (int j = -5; j <= 5; j++) if (i * i + j * j <= 25) autour.add(new int[]{i, j});
            autour.sort(Comparator.comparingInt(k -> k[0] * k[0] + k[1] * k[1]));
            Coin c = null;
            BlockPos vue = null; // de l'eau vue, même s'il n'y pêchera pas
            for (int k = 0; k < autour.size() && c == null; k++) {
                int x = vu.getX() + autour.get(k)[0], z = vu.getZ() + autour.get(k)[1];
                if (vue == null && l.hasChunkAt(x, z)) {
                    int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                    if (eau(l, x, y, z)) vue = new BlockPos(x, y, z);
                }
                c = trouver(t, l, x, z, true);
            }
            if (c == null) {
                Voix.dire(t, vue == null ? "Je ne vois pas d'eau par là, chef."
                        : !Fermier.aLui(t, vue) ? "Cette eau est plus près d'un autre poste que du nôtre, chef : elle n'est pas à nous, je n'y pêche pas."
                        : "Je ne peux pas pêcher là pour l'instant, chef : il me faut une eau d'au moins trois pas sur trois, avec un bord sec où je n'aie pas déjà échoué, ni pris par un autre. Redemande-moi plus tard, ou montre-moi un autre endroit.", "calme");
                return echec("sans_eau");
            }
            retenir(t, c);
            for (String k : List.of("eau_revue", "peche_pause", "ordre_echecs")) t.memoire.remove(k);
            if (t.mode != Travailleur.Mode.GREVE) { t.mode = Travailleur.Mode.TRAVAIL; t.majNom(); }
            Voix.dire(t, "Bien, chef : c'est là que je pêcherai désormais.", "calme");
            return Actions.Etat.OK;
        }
    }
}

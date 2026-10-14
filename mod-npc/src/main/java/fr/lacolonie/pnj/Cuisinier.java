package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le cuisinier, ou boulanger (lot 6, cahier 4.2). Il travaille près du poste, avec un vrai four posé à 16 blocs au plus
 * (un fumoir d'abord, sinon un fourneau, sinon un feu de camp allumé) : il y met ce qu'il trouve de cru au dépôt (viande,
 * poisson, pommes de terre : tout ce que les recettes de cuisson du jeu changent en nourriture), avec juste le combustible
 * qu'il faut (charbon de bois, charbon, puis planches ou bûches en dernier recours), surveille la cuisson et porte ce qui est
 * cuit au dépôt. Il fait aussi le pain avec le blé du dépôt ({@link Fermier.Pain}, 3 blés pour un pain) pour en garder toujours
 * une réserve ({@link Cuisine#RESERVE}). Il laisse au fermier quelques pommes de terre pour ses semailles.
 * <p>
 * Jamais : un four d'un voisin ou d'une structure ({@link Fermier#aLui}), ni un four où un autre a mis quelque chose (il ne
 * prend dans un four que ce qu'il y a mis lui-même, noté dans sa mémoire : la fournée survit à la nuit et au redémarrage).
 * Rien ne se perd : ce qui n'entre pas dans son sac reste au four ou au dépôt.
 */
final class Cuisinier extends Metier {
    static final Actions.Filtre PAIN = Actions.Filtre.de("minecraft:bread");
    /** Les combustibles, dans l'ordre où il les prend : charbon de bois, charbon, puis le bois (planches avant bûches). */
    static final List<Actions.Filtre> FEUX = List.of(Actions.Filtre.de("minecraft:charcoal"), Actions.Filtre.de("minecraft:coal"),
            Actions.Filtre.de("#minecraft:planks"), Actions.Filtre.de("#minecraft:logs_that_burn"));
    /** Une fournée qui ne cuit plus (le feu éteint) au bout de tant de secondes : il va la sortir. */
    static final int ETEINT = 15;

    Cuisinier() {
        super("cuisinier", "Cuisinier", "Cuit au four, près du poste, ce qu'il trouve de cru au dépôt (viande, poisson, pommes de terre) avec le "
                + "charbon ou le bois du dépôt, et fait le pain avec le blé : il en garde toujours une réserve au dépôt.", false);
        commandes.add(new Commande("pain", "Pain", "minecraft:bread"));
        commandes.add(new Commande("viande", "Viande cuite",
                "minecraft:cooked_beef|minecraft:cooked_porkchop|minecraft:cooked_mutton|minecraft:cooked_chicken|minecraft:cooked_rabbit"));
        commandes.add(new Commande("poisson", "Poisson cuit", "minecraft:cooked_cod|minecraft:cooked_salmon"));
        commandes.add(new Commande("patate", "Pommes de terre cuites", "minecraft:baked_potato"));
    }

    @Override
    void travailler(Travailleur t) {
        // Ce qu'il porte (le cuit, le pain, un reste de bois ou de cru) va d'abord au dépôt.
        if (aDeposer(t)) {
            if ("depot_plein".equals(t.resultat)) {
                Cerveau.ecrire(t, "depot", Cerveau.UN_JOUR, "depot_plein");
                Cerveau.attendre(t, 30);
                return;
            }
            Cerveau.lancer(t, new Actions.Deposer(garder));
            return;
        }
        Bureau.Entite b = Bureau.entite(t);
        if (b == null || !(t.level() instanceof ServerLevel l)) { Cerveau.attendre(t, 10); return; }

        // Sa fournée : cuite (ou le feu éteint), il la sort ; sinon il fait le pain en attendant, ou il surveille le four.
        CompoundTag f = t.memoire.getCompound("fournee");
        if (!f.isEmpty()) {
            int e = etat(l, f);
            if (e < 0) t.memoire.remove("fournee"); // le four a disparu (cassé : ce qu'il contenait est tombé là)
            else if (e > 0) { Cerveau.lancer(t, new Defourner()); return; }
            else {
                int pains = pains(t, b);
                if (pains > 0) { Cerveau.lancer(t, new Fermier.Pain(pains, true)); return; }
                BlockPos four = BlockPos.of(f.getLong("four"));
                if (t.distanceToSqr(Vec3.atBottomCenterOf(four)) > 4 * 4) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(four), 2.5)); return; }
                t.getLookControl().setLookAt(Vec3.atCenterOf(four));
                if (t.getRandom().nextInt(4) == 0) Besoins.parler(t, "cuisine_odeur", null);
                Cerveau.attendre(t, 5);
                return;
            }
        }

        Plan p = plan(t, b, l, true);
        Cuisine.Choix c = p.choix;
        if (c.lettre() != null) {
            if (c.lettre().equals("rien_a_cuire")) Cerveau.ecrire(t, c.lettre(), Cerveau.UN_JOUR, c.lettre(), Cuisine.manque(t.memoire.getString("dernier_cru")));
            else Cerveau.ecrire(t, c.lettre(), Cerveau.UN_JOUR, c.lettre());
        }
        switch (c.quoi()) {
            case PAIN -> Cerveau.lancer(t, new Fermier.Pain(c.nombre(), true));
            case CUIRE -> Cerveau.lancer(t, new Enfourner(p.four, c.cru(), c.nombre()));
            default -> {
                // Rien à faire : il attend au poste.
                if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 4 * 4) Cerveau.rentrer(t);
                else Cerveau.attendre(t, 20 + t.getRandom().nextInt(11));
            }
        }
    }

    /** Il porte autre chose que ce qu'il garde (son bateau). */
    boolean aDeposer(Travailleur t) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!s.isEmpty() && garder.stream().noneMatch(g -> g.objet(s))) return true;
        }
        return false;
    }

    // --- Ce qu'il fait ------------------------------------------------------------------------------------------

    /** Sa décision, et le four qui va avec (null s'il n'en a pas). */
    record Plan(Cuisine.Choix choix, BlockPos four) {}

    /** Combien de pains faire : la réserve du dépôt, ou ce qu'il reste à livrer d'une commande de pain. */
    static int pains(Travailleur t, Bureau.Entite b) {
        int[] r = reserve(t);
        return Cuisine.pains(b.compter(PAIN), r[0], r[1], t.compter(Fermier.BLE) + b.compter(Fermier.BLE));
    }

    /** {réserve de pain au dépôt, pains encore à livrer} selon sa commande en cours. */
    static int[] reserve(Travailleur t) {
        Travailleur.Mission m = t.missionActive();
        if (m == null || !"pain".equals(m.id)) return new int[]{Cuisine.RESERVE, 0};
        return m.quantite() ? new int[]{Cuisine.RESERVE, Math.max(0, m.nombre - m.livre)} : new int[]{Math.max(Cuisine.RESERVE, m.nombre), 0};
    }

    /** Ce qu'il fait maintenant ({@link Cuisine#choisir}) ; {@code pain} : le pain compte (pas pour « cuis la viande »). */
    static Plan plan(Travailleur t, Bureau.Entite b, ServerLevel l, boolean pain) {
        int[] r = reserve(t);
        int ble = t.compter(Fermier.BLE) + b.compter(Fermier.BLE), auDepot = b.compter(PAIN);
        int pains = pain ? Cuisine.pains(auDepot, r[0], r[1], ble) : 0;
        boolean manqueBle = pain && auDepot < r[0] && ble < 3;
        if (pains > 0) return new Plan(Cuisine.choisir(pains, false, Map.of(), false, false), null);
        // Le cru du dépôt (selon les recettes du fourneau : le fumoir et le feu de camp cuisent les mêmes aliments).
        Map<String, Integer> crus = crus(t, b, l, RecipeType.SMELTING);
        BlockPos four = crus.isEmpty() ? null : four(t, l);
        boolean feu = false;
        if (four != null) {
            RecipeType<?> type = type(l.getBlockEntity(four));
            if (type != RecipeType.SMELTING) crus = crus(t, b, l, type);
            feu = type == RecipeType.CAMPFIRE_COOKING || Cuisine.chauffe(1, feux(t, b, new ArrayList<>())) != null;
        }
        return new Plan(Cuisine.choisir(0, manqueBle, crus, four != null, feu), four);
    }

    /** Le contenu du dépôt (le bureau et les coffres collés) et de son sac, sans rien prendre. */
    static List<ItemStack> contenu(Travailleur t, Bureau.Entite b) {
        List<ItemStack> r = new ArrayList<>();
        for (int i = 0; i < b.depot.getContainerSize(); i++) r.add(b.depot.getItem(i));
        for (IItemHandler h : b.voisins()) for (int i = 0; i < h.getSlots(); i++) r.add(h.getStackInSlot(i));
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) r.add(t.inventaire.getItem(i));
        return r;
    }

    /** Ce qu'il peut cuire, par objet (identifiant), dans l'ordre du dépôt : les semences moins celles qu'il laisse au fermier. */
    static Map<String, Integer> crus(Travailleur t, Bureau.Entite b, ServerLevel l, RecipeType<?> type) {
        Map<Item, Integer> n = new LinkedHashMap<>();
        for (ItemStack s : contenu(t, b)) if (!s.isEmpty() && cuit(l, type, s.getItem()) != Items.AIR) n.merge(s.getItem(), s.getCount(), Integer::sum);
        Map<String, Integer> r = new LinkedHashMap<>();
        n.forEach((i, k) -> {
            int c = Cuisine.aCuire(k, i instanceof BlockItem);
            if (c > 0) r.put(BuiltInRegistries.ITEM.getKey(i).toString(), c);
        });
        return r;
    }

    /** Les combustibles du dépôt et du sac, dans l'ordre de {@link #FEUX} : le plus abondant de chaque sorte (sa sorte dans {@code quoi}). */
    static List<Cuisine.Feu> feux(Travailleur t, Bureau.Entite b, List<Item> quoi) {
        List<ItemStack> tout = contenu(t, b);
        List<Cuisine.Feu> r = new ArrayList<>();
        for (Actions.Filtre f : FEUX) {
            Map<Item, Integer> n = new HashMap<>();
            for (ItemStack s : tout) if (f.objet(s) && s.getBurnTime(RecipeType.SMELTING) > 0) n.merge(s.getItem(), s.getCount(), Integer::sum);
            Item mieux = null;
            for (var e : n.entrySet()) if (mieux == null || e.getValue() > n.get(mieux)) mieux = e.getKey();
            if (mieux == null) continue;
            r.add(new Cuisine.Feu(n.get(mieux), new ItemStack(mieux).getBurnTime(RecipeType.SMELTING)));
            quoi.add(mieux);
        }
        return r;
    }

    // --- Les recettes du jeu (en cache : une recherche par objet et par sorte de four) -----------------------------

    static final Map<String, Item> CUITS = new HashMap<>();
    static RecipeManager recettesVues;

    /** Ce que donne la cuisson de cet objet dans ce four, si c'est de la nourriture ; sinon l'air. */
    static Item cuit(ServerLevel l, RecipeType<?> type, Item cru) {
        RecipeManager m = l.getRecipeManager();
        if (m != recettesVues) { CUITS.clear(); recettesVues = m; } // les recettes rechargées
        return CUITS.computeIfAbsent(BuiltInRegistries.RECIPE_TYPE.getKey(type) + "|" + BuiltInRegistries.ITEM.getKey(cru), k -> {
            @SuppressWarnings("unchecked")
            RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe> ty = (RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe>) type;
            ItemStack r = m.getRecipeFor(ty, new SingleRecipeInput(new ItemStack(cru)), l).map(h -> h.value().getResultItem(l.registryAccess())).orElse(ItemStack.EMPTY);
            return !r.isEmpty() && r.getFoodProperties(null) != null ? r.getItem() : Items.AIR;
        });
    }

    /** La sorte de cuisson d'un four : fumoir, fourneau, feu de camp ; null pour le reste (un haut fourneau ne cuit pas la viande). */
    static RecipeType<?> type(BlockEntity e) {
        if (e instanceof SmokerBlockEntity) return RecipeType.SMOKING;
        if (e instanceof FurnaceBlockEntity) return RecipeType.SMELTING;
        if (e instanceof CampfireBlockEntity) return RecipeType.CAMPFIRE_COOKING;
        return null;
    }

    // --- Le four ------------------------------------------------------------------------------------------------

    /**
     * Un four libre à lui, à {@link Cuisine#RAYON} blocs du poste : un fumoir d'abord, puis un fourneau, puis un feu de camp
     * allumé ; le plus proche à égalité. Il passe en revue les blocs spéciaux des tronçons voisins (pas de fouille bloc à bloc).
     */
    static BlockPos four(Travailleur t, ServerLevel l) {
        BlockPos poste = t.bureau, mieux = null;
        int rang = 9;
        double d = 0;
        boolean laisse = t.memoire.getLong("four_laisse_t") > Cerveau.temps(t);
        for (int cx = SectionPos.blockToSectionCoord(poste.getX() - Cuisine.RAYON); cx <= SectionPos.blockToSectionCoord(poste.getX() + Cuisine.RAYON); cx++)
            for (int cz = SectionPos.blockToSectionCoord(poste.getZ() - Cuisine.RAYON); cz <= SectionPos.blockToSectionCoord(poste.getZ() + Cuisine.RAYON); cz++) {
                if (!(l.getChunkSource().getChunkNow(cx, cz) instanceof LevelChunk c)) continue;
                for (BlockEntity e : c.getBlockEntities().values()) {
                    BlockPos q = e.getBlockPos();
                    RecipeType<?> type = type(e);
                    if (type == null || Math.abs(q.getY() - poste.getY()) > 6 || Fermier.distance(q, poste) > Cuisine.RAYON * Cuisine.RAYON) continue;
                    int r = type == RecipeType.SMOKING ? 0 : type == RecipeType.SMELTING ? 1 : 2;
                    double dd = q.distSqr(poste);
                    if (r > rang || r == rang && dd >= d || !libre(e) || laisse && q.asLong() == t.memoire.getLong("four_laisse") || !Fermier.aLui(t, q)) continue;
                    mieux = q;
                    rang = r;
                    d = dd;
                }
            }
        return mieux;
    }

    /** Rien dedans (ni à cuire, ni combustible, ni rien de cuit) ; un feu de camp : allumé et vide. Il ne se sert jamais d'un four où un autre a mis quelque chose. */
    static boolean libre(BlockEntity e) {
        if (e instanceof AbstractFurnaceBlockEntity f) return f.getItem(0).isEmpty() && f.getItem(1).isEmpty() && f.getItem(2).isEmpty();
        if (e instanceof CampfireBlockEntity c) return allume(c) && c.getItems().stream().allMatch(ItemStack::isEmpty);
        return false;
    }

    static boolean allume(BlockEntity e) {
        var s = e.getBlockState();
        if (s.hasProperty(AbstractFurnaceBlock.LIT)) return s.getValue(AbstractFurnaceBlock.LIT);
        return s.hasProperty(CampfireBlock.LIT) && s.getValue(CampfireBlock.LIT);
    }

    /** Sa fournée : -1 le four n'est plus là, 0 elle cuit, 1 elle est cuite (ou le feu s'est éteint) : à sortir. */
    static int etat(ServerLevel l, CompoundTag f) {
        BlockPos p = BlockPos.of(f.getLong("four"));
        if (!l.isLoaded(p)) return 0;
        BlockEntity e = l.getBlockEntity(p);
        Item cru = objet(f.getString("cru"));
        boolean eteint = !allume(e) && l.getGameTime() - f.getLong("t") > ETEINT * 20L;
        if (e instanceof AbstractFurnaceBlockEntity fo) return !fo.getItem(0).is(cru) || eteint ? 1 : 0;
        if (e instanceof CampfireBlockEntity c) return c.getItems().stream().noneMatch(s -> s.is(cru)) || eteint ? 1 : 0;
        return -1;
    }

    static Item objet(String id) {
        ResourceLocation r = ResourceLocation.tryParse(id);
        return r == null ? Items.AIR : BuiltInRegistries.ITEM.get(r);
    }

    /** Sort de son sac jusqu'à n objets de cette sorte (identiques au premier), en une pile ; vide s'il n'en a pas. */
    static ItemStack sortir(Travailleur t, Item i, int n) {
        ItemStack r = ItemStack.EMPTY;
        for (int k = 0; k < t.inventaire.getContainerSize() && r.getCount() < n; k++) {
            ItemStack s = t.inventaire.getItem(k);
            if (!s.is(i) || !r.isEmpty() && !ItemStack.isSameItemSameComponents(r, s)) continue;
            ItemStack part = s.split(Math.min(n - r.getCount(), r.isEmpty() ? s.getMaxStackSize() : r.getMaxStackSize() - r.getCount()));
            if (r.isEmpty()) r = part; else r.grow(part.getCount());
            if (r.getCount() >= r.getMaxStackSize()) break;
        }
        t.inventaire.setChanged();
        return r;
    }

    // --- La fournée ---------------------------------------------------------------------------------------------

    /**
     * Au poste, il prend le cru (jusqu'à {@code voulu}) et juste le combustible qu'il faut ; puis il va au four, le charge,
     * et note la fournée dans sa mémoire. Le four pris entre-temps : il n'y touche pas (ce qu'il porte retournera au dépôt).
     */
    static final class Enfourner extends Actions.AuBureau {
        final BlockPos four;
        final Item cru;
        final int voulu;
        Item feu;
        int cuire, chauffe, etape;

        Enfourner(BlockPos four, String cru, int voulu) {
            super("enfourner");
            this.four = four;
            this.cru = objet(cru);
            this.voulu = voulu;
        }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            if (etape == 0) {
                Bureau.Entite b = arriver(t);
                if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
                BlockEntity e = l.getBlockEntity(four);
                RecipeType<?> type = type(e);
                if (type == null || !libre(e)) return echec("four_occupe");
                if (cuit(l, type, cru) == Items.AIR) return echec("pas_de_recette");
                int place = e instanceof CampfireBlockEntity c ? c.getItems().size() : Cuisine.FOURNEE;
                int n = Math.min(Math.min(voulu, place), new ItemStack(cru).getMaxStackSize());
                int manque = n - t.compter(Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(cru).toString()));
                if (manque > 0) b.prendre(Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(cru).toString()), manque).forEach(s -> Guerrier.garder(t, b, s));
                cuire = Math.min(n, t.compter(Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(cru).toString())));
                if (cuire == 0) return echec("rien_au_depot");
                if (type != RecipeType.CAMPFIRE_COOKING) {
                    List<Item> quoi = new ArrayList<>();
                    Cuisine.Chauffe c = Cuisine.chauffe(cuire, feux(t, b, quoi));
                    if (c == null) { Cerveau.ecrire(t, "sans_combustible", Cerveau.UN_JOUR, "sans_combustible"); return echec("sans_combustible"); }
                    feu = quoi.get(c.feu());
                    cuire = c.cuire();
                    Actions.Filtre ff = Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(feu).toString());
                    int k = c.nombre() - t.compter(ff);
                    if (k > 0) b.prendre(ff, k).forEach(s -> Guerrier.garder(t, b, s));
                    chauffe = Math.min(c.nombre(), t.compter(ff));
                }
                t.swing(InteractionHand.MAIN_HAND);
                etape = 1;
                nouveauBut();
            }
            // Au four.
            if (!aPortee(t)) {
                if (coince() || age > 20 * 60) {
                    // Un four qu'il n'atteint pas (clos, en hauteur) : laissé dix minutes, sans chercher de chemin à chaque fois.
                    t.memoire.putLong("four_laisse", four.asLong());
                    t.memoire.putLong("four_laisse_t", Cerveau.temps(t) + 600);
                    return echec("inaccessible");
                }
                marcher(t, Vec3.atBottomCenterOf(four), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(four));
            BlockEntity e = l.getBlockEntity(four);
            if (e == null || !libre(e)) return echec("four_occupe");
            int mis = 0, brule = 0;
            if (e instanceof AbstractFurnaceBlockEntity f) {
                ItemStack dedans = sortir(t, cru, cuire), bois = feu == null ? ItemStack.EMPTY : sortir(t, feu, chauffe);
                if (dedans.isEmpty() || bois.isEmpty()) { t.ranger(dedans); t.ranger(bois); return echec("rien_au_depot"); } // il les avait : au sac
                mis = dedans.getCount();
                brule = bois.getCount();
                f.setItem(0, dedans);
                f.setItem(1, bois);
                f.setChanged();
            } else if (e instanceof CampfireBlockEntity c) {
                var r = c.getCookableRecipe(new ItemStack(cru));
                for (int i = 0; i < cuire && r.isPresent(); i++) {
                    ItemStack un = sortir(t, cru, 1);
                    if (un.isEmpty()) break;
                    if (!c.placeFood(t, un, r.get().value().getCookingTime())) { t.ranger(un); break; }
                    if (!un.isEmpty()) t.ranger(un); // pas pris par le feu : il le garde
                    mis++;
                }
                if (mis == 0) return echec("four_occupe");
            }
            CompoundTag f = new CompoundTag();
            f.putLong("four", four.asLong());
            f.putString("cru", BuiltInRegistries.ITEM.getKey(cru).toString());
            f.putString("cuit", BuiltInRegistries.ITEM.getKey(cuit(l, type(e), cru)).toString());
            f.putInt("n", mis);
            if (feu != null) { f.putString("feu", BuiltInRegistries.ITEM.getKey(feu).toString()); f.putInt("nf", brule); }
            f.putLong("t", l.getGameTime());
            t.memoire.put("fournee", f);
            t.memoire.putString("dernier_cru", BuiltInRegistries.ITEM.getKey(cru).toString());
            t.swing(InteractionHand.MAIN_HAND);
            Besoins.parler(t, "cuisine_four", null);
            return Actions.Etat.OK;
        }

        boolean aPortee(Travailleur t) { return Actions.aPortee(t, four); }
    }

    /**
     * Il sort sa fournée : seulement ce qu'il y a mis (le cuit, et s'il n'a pas tout cuit, le cru et le combustible qui restent),
     * autant que son sac en prend ; le reste attend dans le four. Un feu de camp : il ramasse le cuit tombé autour.
     */
    static final class Defourner extends Actions.Action {
        Defourner() { super("defourner"); }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            CompoundTag f = t.memoire.getCompound("fournee");
            if (f.isEmpty()) return Actions.Etat.OK;
            BlockPos four = BlockPos.of(f.getLong("four"));
            if (!Actions.aPortee(t, four)) {
                if (coince() || age > 20 * 60) return echec("inaccessible");
                marcher(t, Vec3.atBottomCenterOf(four), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(four));
            BlockEntity e = l.getBlockEntity(four);
            Item cru = objet(f.getString("cru")), cuit = objet(f.getString("cuit")), feu = objet(f.getString("feu"));
            int n = f.getInt("n"), sortis = 0;
            boolean reste = false;
            if (e instanceof AbstractFurnaceBlockEntity fo) {
                // Fini (plus rien à cuire) ou le feu éteint : il reprend aussi son cru et son combustible qui restent.
                boolean eteint = fo.getItem(0).isEmpty() || !allume(fo);
                int[] slots = eteint ? new int[]{2, 0, 1} : new int[]{2};
                for (int k : slots) {
                    ItemStack s = fo.getItem(k);
                    Item quoi = k == 2 ? cuit : k == 0 ? cru : feu;
                    int max = k == 1 ? f.getInt("nf") : n;
                    if (s.isEmpty() || !s.is(quoi) || max <= 0) continue;
                    ItemStack pris = s.split(Math.min(s.getCount(), max));
                    int avant = pris.getCount();
                    ItemStack non = t.inventaire.addItem(pris);
                    int pp = avant - non.getCount();
                    if (!non.isEmpty()) { s.grow(non.getCount()); reste = true; } // le sac plein : le reste attend au four
                    if (k == 1) f.putInt("nf", f.getInt("nf") - pp);
                    else { n -= pp; if (k == 2) sortis += pp; }
                }
                fo.setChanged();
                if (!eteint && !fo.getItem(0).isEmpty() && fo.getItem(0).is(cru)) reste = true; // il en reste à cuire
            } else if (e instanceof CampfireBlockEntity c) {
                for (ItemEntity ie : l.getEntitiesOfClass(ItemEntity.class, new AABB(four).inflate(3))) {
                    if (n <= 0 || !ie.isAlive() || !ie.getItem().is(cuit)) continue;
                    ItemStack s = ie.getItem().copy();
                    ItemStack pris = s.split(Math.min(n, s.getCount()));
                    int avant = pris.getCount();
                    ItemStack non = t.inventaire.addItem(pris);
                    int pp = avant - non.getCount();
                    n -= pp;
                    sortis += pp;
                    t.take(ie, pp);
                    s.grow(non.getCount());
                    if (s.isEmpty()) ie.discard(); else { ie.setItem(s); reste = true; }
                }
                if (!allume(c)) // éteint (la pluie) : il reprend ce qu'il y avait mis
                    for (int i = 0; i < c.getItems().size() && n > 0; i++)
                        if (c.getItems().get(i).is(cru) && t.inventaire.canAddItem(c.getItems().get(i))) {
                            t.inventaire.addItem(c.getItems().get(i).copy());
                            c.getItems().set(i, ItemStack.EMPTY);
                            n--;
                            c.setChanged();
                            l.sendBlockUpdated(four, c.getBlockState(), c.getBlockState(), 3);
                        }
                if (c.getItems().stream().anyMatch(s -> s.is(cru))) reste = true;
            }
            t.swing(InteractionHand.MAIN_HAND);
            f.putInt("n", n);
            if (reste && n > 0 && e != null) t.memoire.put("fournee", f);
            else t.memoire.remove("fournee");
            if (sortis > 0 && t.getRandom().nextInt(8) == 0) Besoins.parler(t, "cuisine_brule", null); // pour rire : rien n'est perdu
            return Actions.Etat.OK;
        }
    }

    // --- Les ordres ---------------------------------------------------------------------------------------------

    /** « Cuis la viande » (cuire), « va cuisiner » (cuisiner : le pain d'abord s'il en manque). */
    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) { return new Ordre(mode.equals("cuisiner")); }

    /** Sur ordre : il fait tout de suite ce qu'il ferait de lui-même, et dit ce qu'il en est. */
    static final class Ordre extends Actions.Action {
        final boolean pain;
        Actions.Action sous;
        String fini = "";

        Ordre(boolean pain) { super("cuisiner"); this.pain = pain; }

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
                String raison = sous.raison;
                boolean pains = sous instanceof Fermier.Pain;
                sous = null;
                if (pains) return r; // le pain dit lui-même ce qu'il a fait
                if (r == Actions.Etat.OK) { Voix.dire(t, fini, "calme"); return r; }
                Voix.dire(t, raison.equals("sans_combustible") ? "Je n'ai rien pour chauffer le four, chef : ni charbon ni bois au dépôt."
                        : raison.equals("four_occupe") ? "Le four est pris, chef : je n'y touche pas." : "Je n'arrive pas au four, chef.", "calme");
                return echec(raison);
            }
            Bureau.Entite b = Bureau.entite(t);
            if (b == null || !(t.level() instanceof ServerLevel l)) { Voix.dire(t, "Il me faut un poste de travail pour ça, chef.", "calme"); return echec("pas_de_bureau"); }
            CompoundTag f = t.memoire.getCompound("fournee");
            if (!f.isEmpty() && etat(l, f) >= 0) {
                if (etat(l, f) > 0) { sous = new Defourner(); fini = "La fournée est sortie, chef."; return Actions.Etat.EN_COURS; }
                Voix.dire(t, "J'ai déjà une fournée au four, chef. Patience, ça cuit.", "calme");
                return Actions.Etat.OK;
            }
            Plan p = plan(t, b, l, pain);
            Cuisine.Choix c = p.choix;
            switch (c.quoi()) {
                case PAIN -> { sous = new Fermier.Pain(c.nombre(), false); return Actions.Etat.EN_COURS; }
                case CUIRE -> {
                    sous = new Enfourner(p.four, c.cru(), c.nombre());
                    fini = "C'est au four, chef. Ça sentira bon d'ici peu.";
                    return Actions.Etat.EN_COURS;
                }
                case SANS_FOUR -> Voix.dire(t, "Je n'ai pas de four libre près du poste, chef : un fourneau, un fumoir ou un feu de camp, et je m'y mets.", "calme");
                case SANS_COMBUSTIBLE -> Voix.dire(t, "Je n'ai rien pour chauffer le four, chef : ni charbon ni bois au dépôt.", "calme");
                default -> Voix.dire(t, !pain ? "Il n'y a rien de cru à cuire au dépôt, chef."
                        : "sans_ble".equals(c.lettre()) ? "Plus de blé pour le pain, chef, et rien de cru à cuire au dépôt."
                        : "Le pain est en réserve, chef, et il n'y a rien de cru à cuire au dépôt.", "calme");
            }
            return c.quoi() == Cuisine.Quoi.RIEN ? Actions.Etat.OK : echec(c.lettre());
        }
    }
}

package fr.lacolonie.pnj;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Le poste de travail : un seul par joueur, commun à tous ses ouvriers (0.5.0). Une caisse (les salaires), un dépôt
 * (agrandi par les coffres collés au bureau), le courrier, et l'écran du chef : ses ouvriers, leurs missions, leurs
 * horaires, leur paie et leurs expéditions, et les lettres.
 * <p>
 * Avant la 0.5.0, chaque PNJ avait son bureau. Le premier bureau qu'un joueur ouvre après la mise à jour lui propose
 * d'en faire son poste ; les autres ne sont plus que des coffres (caisse et dépôt), à vider.
 */
public final class Bureau {
    private Bureau() {}

    public static class Bloc extends BaseEntityBlock {
        static final MapCodec<Bloc> CODEC = simpleCodec(Bloc::new);

        public Bloc(Properties p) { super(p); }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Entite(pos, state); }

        @Override
        protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

        /** Un seul poste par joueur : un deuxième est refusé. */
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            if (ctx.getPlayer() instanceof ServerPlayer p) {
                Courrier.Poste poste = Courrier.de(p.server).poste(p.getUUID());
                if (poste != null && posteExiste(p.server, poste)) {
                    Travailleur.signaler(p, "Tu as déjà un poste de travail, en " + poste.pos().toShortString() + " : un seul par joueur.");
                    return null;
                }
            }
            return super.getStateForPlacement(ctx);
        }

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (placer instanceof ServerPlayer p && level.getBlockEntity(pos) instanceof Entite e) {
                e.proprietaire = p.getUUID();
                e.nomProprietaire = p.getName().getString();
                e.setChanged();
                Courrier c = Courrier.de(p.server);
                Courrier.Poste ancien = c.poste(p.getUUID());
                if (ancien == null || !posteExiste(p.server, ancien)) {
                    c.definirPoste(p.getUUID(), level, pos);
                    rendreReserve(c, e);
                    Travailleur.signaler(p, "Ton poste de travail est posé : tous tes ouvriers s'y rattachent. Clic droit dessus pour les gérer.");
                }
            }
        }

        /** La réserve reverse dans le dépôt une fois par seconde (rien à faire si elle est vide). */
        @Override
        public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state,
                net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, ColoniePnj.BUREAU_BE.get(),
                    (l, p, st, e) -> { if (!e.reserve.vide() && Math.floorMod(l.getGameTime() + p.hashCode(), 20L) == 0) e.reverser(); });
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entite e && player instanceof ServerPlayer sp) {
                // Le propriétaire gère ses ouvriers ; les autres ne voient que la caisse et le dépôt (aucune protection : le vol RP est permis).
                if (sp.getUUID().equals(e.proprietaire)) PacketDistributor.sendToPlayer(sp, new Reseau.Poste(pos, e.fiche(sp)));
                else sp.openMenu(e);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState neuf, boolean piston) {
            if (!state.is(neuf.getBlock()) && level.getBlockEntity(pos) instanceof Entite e) {
                Containers.dropContents(level, pos, e.caisse);
                Containers.dropContents(level, pos, e.depot);
                e.garderReserve(); // gardée pour son propriétaire (sauvegarde du monde), rendue à son prochain poste
                if (e.proprietaire != null && level instanceof ServerLevel sl) {
                    Courrier c = Courrier.de(sl.getServer());
                    Courrier.Poste p = c.poste(e.proprietaire);
                    if (p != null && p.monde() == level.dimension() && p.pos().equals(pos)) {
                        c.oublierPoste(e.proprietaire, level, pos);
                        for (Travailleur t : ColoniePnj.pnjDe(sl.getServer(), e.proprietaire)) {
                            if (!pos.equals(t.bureau)) continue;
                            t.bureau = null;
                            t.lit = null;
                            Courrier.ecrire(t, "poste_detruit");
                        }
                    }
                }
            }
            super.onRemove(state, level, pos, neuf, piston);
        }
    }

    /** La réserve gardée de son ancien poste cassé passe à ce bureau ; ce qui n'y tient pas (garde-fous) reste gardé. */
    static void rendreReserve(Courrier c, Entite e) {
        ListTag l = e.proprietaire == null || e.getLevel() == null ? null : c.reserves.remove(e.proprietaire);
        if (l == null) return;
        ListTag reste = e.chargerReserve(l, e.getLevel().registryAccess());
        if (!reste.isEmpty()) c.reserves.put(e.proprietaire, reste);
        c.setDirty();
        e.setChanged();
    }

    /** Le poste existe encore (son monde est là et le bloc aussi, s'il est chargé). */
    static boolean posteExiste(net.minecraft.server.MinecraftServer s, Courrier.Poste p) {
        ServerLevel l = s.getLevel(p.monde());
        if (l == null) return false;
        return !l.isLoaded(p.pos()) || l.getBlockEntity(p.pos()) instanceof Entite;
    }

    public static class Entite extends BlockEntity implements MenuProvider {
        public final SimpleContainer caisse = new SimpleContainer(9) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack s) { return Reglages.piece(s) >= 0; }

            @Override
            public void setChanged() { super.setChanged(); Entite.this.setChanged(); }
        };
        public final SimpleContainer depot = new SimpleContainer(27) {
            @Override
            public void setChanged() { super.setChanged(); Entite.this.setChanged(); }
        };
        public UUID proprietaire;
        public String nomProprietaire = "";
        /** La réserve (cahier 14.4) : le surplus des ouvriers quand le dépôt est plein ; sorte = une pile d'un objet. Jamais montrée au joueur. */
        final Reserve<ItemStack> reserve = new Reserve<>(ItemStack::isSameItemSameComponents);

        public Entite(BlockPos pos, BlockState state) { super(ColoniePnj.BUREAU_BE.get(), pos, state); }

        @Override
        protected void saveAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.saveAdditional(t, reg);
            t.put("Caisse", caisse.createTag(reg));
            t.put("Depot", depot.createTag(reg));
            if (proprietaire != null) t.putUUID("Proprietaire", proprietaire);
            t.putString("NomProprietaire", nomProprietaire);
            if (!reserve.vide()) t.put("Reserve", reserveTag(reg));
        }

        /** La réserve en NBT : une ligne par sorte (la pile d'un objet, le nombre). */
        ListTag reserveTag(HolderLookup.Provider reg) {
            ListTag r = new ListTag();
            for (Reserve.Tas<ItemStack> x : reserve.tas) {
                if (x.nombre <= 0) continue;
                CompoundTag c = new CompoundTag();
                c.put("Objet", x.sorte.save(reg));
                c.putLong("Nombre", x.nombre);
                r.add(c);
            }
            return r;
        }

        /** Ajoute ces lignes à la réserve ; renvoie celles qui n'y entrent pas (garde-fous). Une sorte inconnue (mod retiré) est notée au journal du serveur. */
        ListTag chargerReserve(ListTag l, HolderLookup.Provider reg) {
            ListTag reste = new ListTag();
            for (Tag x : l) {
                CompoundTag c = (CompoundTag) x;
                long n = c.getLong("Nombre");
                ItemStack s = ItemStack.parseOptional(reg, c.getCompound("Objet"));
                if (s.isEmpty()) {
                    ColoniePnj.LOG.warn("Réserve du poste {} : objet inconnu, {} perdu(s) : {}", worldPosition.toShortString(), n, c.getCompound("Objet"));
                    continue;
                }
                long entre = reserve.ajouter(s.copyWithCount(1), n);
                if (entre < n) {
                    CompoundTag r = c.copy();
                    r.putLong("Nombre", n - entre);
                    reste.add(r);
                }
            }
            return reste;
        }

        @Override
        protected void loadAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.loadAdditional(t, reg);
            caisse.fromTag(t.getList("Caisse", Tag.TAG_COMPOUND), reg);
            depot.fromTag(t.getList("Depot", Tag.TAG_COMPOUND), reg);
            proprietaire = t.hasUUID("Proprietaire") ? t.getUUID("Proprietaire") : null;
            nomProprietaire = t.getString("NomProprietaire");
            reserve.vider();
            chargerReserve(t.getList("Reserve", Tag.TAG_COMPOUND), reg);
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(nomProprietaire.isEmpty() ? "Poste de travail" : "Poste de travail de " + nomProprietaire);
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { pauseReserve = 0; reverser(); return new Menu(id, inv, caisse, depot, this); }

        boolean estLePoste() {
            if (proprietaire == null || !(level instanceof ServerLevel sl)) return false;
            Courrier.Poste p = Courrier.de(sl.getServer()).poste(proprietaire);
            return p != null && p.monde() == level.dimension() && p.pos().equals(worldPosition);
        }

        // --- Le dépôt : le bureau, plus les coffres, tonneaux ou réservoirs collés à lui -----------------

        /** Les sortes que le dépôt trie : le minerai, puis la pierre (terre, gravier, ardoise comprises). */
        static final List<Actions.Filtre> SORTES = List.of(
                Actions.Filtre.de("#c:ores|#c:raw_materials|minecraft:coal|minecraft:diamond|minecraft:emerald|minecraft:lapis_lazuli|minecraft:redstone"),
                Actions.Filtre.de("#c:cobblestones|#c:stones|#minecraft:dirt|minecraft:gravel|minecraft:cobbled_deepslate|minecraft:deepslate"));

        List<IItemHandler> voisins() {
            List<IItemHandler> l = new ArrayList<>();
            if (level == null) return l;
            for (Direction d : Direction.values()) {
                BlockPos v = worldPosition.relative(d);
                if (level.getBlockEntity(v) instanceof Entite) continue; // pas le dépôt d'un autre bureau
                IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, v, d.getOpposite());
                if (h != null) l.add(h);
            }
            return l;
        }

        /**
         * Range un objet au dépôt puis dans les coffres voisins ; renvoie ce qui n'a pas trouvé de place. Trié (cahier 3.2) :
         * le minerai va d'abord dans un coffre voisin qui contient déjà du minerai, la pierre dans un coffre qui contient
         * déjà de la pierre.
         */
        public ItemStack ranger(ItemStack s) {
            ItemStack reste = rangerVisible(s);
            return reste.isEmpty() ? reste : versReserve(reste);
        }

        /** Le rangement d'avant la réserve : le dépôt visible seulement (le bureau et les coffres collés). */
        ItemStack rangerVisible(ItemStack s) { return rangerVisible(s, voisins()); }

        ItemStack rangerVisible(ItemStack s, List<IItemHandler> v) {
            ItemStack reste = s;
            int sorte = -1;
            for (int k = 0; k < SORTES.size() && sorte < 0; k++) if (SORTES.get(k).objet(s)) sorte = k;
            if (sorte >= 0) {
                // Le coffre de sa sorte ; s'il n'y en a pas encore, un coffre vide (il deviendra le sien).
                boolean trouve = false;
                for (IItemHandler h : v) if (contient(h, SORTES.get(sorte))) { trouve = true; reste = ItemHandlerHelper.insertItemStacked(h, reste, false); }
                for (IItemHandler h : v) if (!trouve && !reste.isEmpty() && vide(h)) { trouve = true; reste = ItemHandlerHelper.insertItemStacked(h, reste, false); }
            }
            if (reste.isEmpty()) return reste;
            reste = depot.addItem(reste);
            for (IItemHandler h : v) {
                if (reste.isEmpty()) break;
                if (sorte >= 0 && contient(h, SORTES.get(1 - sorte))) continue; // pas de minerai dans le coffre de pierre, ni l'inverse
                reste = ItemHandlerHelper.insertItemStacked(h, reste, false);
            }
            return reste;
        }

        static boolean contient(IItemHandler h, Actions.Filtre f) {
            for (int i = 0; i < h.getSlots(); i++) if (f.objet(h.getStackInSlot(i))) return true;
            return false;
        }

        static boolean vide(IItemHandler h) {
            for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) return false;
            return true;
        }

        public int compter(Actions.Filtre f) { return compter(f::objet); }

        /** Combien d'objets de ce genre au dépôt (bureau, coffres voisins, réserve) ; la boutique compte une marchandise exacte. */
        int compter(java.util.function.Predicate<ItemStack> f) {
            long n = reserve.compter(f);
            for (int i = 0; i < depot.getContainerSize(); i++) if (f.test(depot.getItem(i))) n += depot.getItem(i).getCount();
            for (IItemHandler h : voisins())
                for (int i = 0; i < h.getSlots(); i++) if (f.test(h.getStackInSlot(i))) n += h.getStackInSlot(i).getCount();
            return (int) Math.min(n, Integer.MAX_VALUE);
        }

        /** Prend jusqu'à n objets au dépôt (puis dans les coffres voisins). */
        public List<ItemStack> prendre(Actions.Filtre f, int n) { return prendre(f::objet, n); }

        List<ItemStack> prendre(java.util.function.Predicate<ItemStack> f, int n) {
            List<ItemStack> pris = new ArrayList<>();
            for (int i = 0; i < depot.getContainerSize() && n > 0; i++) {
                ItemStack s = depot.getItem(i);
                if (s.isEmpty() || !f.test(s)) continue;
                ItemStack part = s.split(Math.min(n, s.getCount()));
                n -= part.getCount();
                pris.add(part);
            }
            depot.setChanged();
            for (IItemHandler h : voisins())
                for (int i = 0; i < h.getSlots() && n > 0; i++) {
                    if (h.getStackInSlot(i).isEmpty() || !f.test(h.getStackInSlot(i))) continue;
                    ItemStack part = h.extractItem(i, n, false);
                    n -= part.getCount();
                    if (!part.isEmpty()) pris.add(part);
                }
            if (n > 0 && !reserve.vide()) {
                for (Reserve.Tas<ItemStack> x : reserve.prendre(f, n)) piles(x, pris);
                setChanged();
            }
            return pris;
        }

        // --- La réserve (cahier 14.4) : le surplus des ouvriers, sans limite pour eux, invisible pour le joueur ----------

        /** Met en réserve ce que le dépôt visible n'a pas pris ; renvoie ce qui reste (seulement si un garde-fou est atteint). */
        ItemStack versReserve(ItemStack s) {
            if (!pourReserve(s)) return s;
            long n = reserve.ajouter(s.copyWithCount(1), s.getCount());
            if (n > 0) setChanged();
            return n >= s.getCount() ? ItemStack.EMPTY : s.copyWithCount(s.getCount() - (int) n);
        }

        /** Jamais en réserve : ce qui contient d'autres objets (boîte, sac, livre écrit) ; ça reste au sac comme avant. */
        static boolean pourReserve(ItemStack s) {
            return !s.has(net.minecraft.core.component.DataComponents.CONTAINER) && !s.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
                    && !s.has(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        }

        /** Un tas de la réserve en piles du jeu (au plus la taille d'une pile chacune). */
        static void piles(Reserve.Tas<ItemStack> x, List<ItemStack> l) {
            for (long r = x.nombre; r > 0; ) {
                int k = (int) Math.min(r, x.sorte.getMaxStackSize());
                l.add(x.sorte.copyWithCount(k));
                r -= k;
            }
        }

        /** La réserve vue comme des piles (copies, une par sorte, sans rien prendre) : pour lire le dépôt en entier. */
        List<ItemStack> reserveVue() {
            List<ItemStack> l = new ArrayList<>();
            for (Reserve.Tas<ItemStack> x : reserve.tas) if (x.nombre > 0) l.add(x.sorte.copyWithCount((int) Math.min(x.nombre, Integer.MAX_VALUE)));
            return l;
        }

        /** Une case du dépôt visible a-t-elle encore de la place (vide, ou pile pas pleine) ? */
        boolean placeVisible(List<IItemHandler> v) {
            for (int i = 0; i < depot.getContainerSize(); i++) { ItemStack s = depot.getItem(i); if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) return true; }
            for (IItemHandler h : v)
                for (int i = 0; i < h.getSlots(); i++) { ItemStack s = h.getStackInSlot(i); if (s.isEmpty() || s.getCount() < Math.min(h.getSlotLimit(i), s.getMaxStackSize())) return true; }
            return false;
        }

        /** Où le reversement reprend (sorte suivante), et jusqu'à quand il se repose après un essai sans effet (temps de jeu). Non sauvegardés. */
        int curseurReserve;
        long pauseReserve;

        /**
         * Reverse la réserve dans le dépôt visible tant qu'il y a de la place : 64 sortes au plus par appel, en reprenant où
         * l'appel d'avant s'est arrêté ; 10 s de repos si rien n'est entré (levé à l'ouverture du menu). Rien ne se perd, rien ne se crée.
         */
        void reverser() {
            if (reserve.vide() || level == null || level.isClientSide || level.getGameTime() < pauseReserve) return;
            List<IItemHandler> v = voisins();
            boolean change = false;
            if (placeVisible(v)) {
                int n = reserve.tas.size();
                for (int essais = 0; essais < Math.min(64, n); essais++) {
                    Reserve.Tas<ItemStack> x = reserve.tas.get(Math.floorMod(curseurReserve++, n));
                    while (x.nombre > 0) {
                        int k = (int) Math.min(x.nombre, x.sorte.getMaxStackSize());
                        int entre = k - rangerVisible(x.sorte.copyWithCount(k), v).getCount();
                        if (entre <= 0) break;
                        reserve.retirer(x, entre);
                        change = true;
                    }
                }
            }
            if (change) { reserve.nettoyer(); setChanged(); }
            else pauseReserve = level.getGameTime() + 200;
        }

        /** Le bureau est cassé : la réserve est gardée pour son propriétaire dans la sauvegarde du monde (sans propriétaire : au sol, en piles). */
        void garderReserve() {
            if (level == null || reserve.vide()) return;
            if (proprietaire != null && level instanceof ServerLevel sl) {
                Courrier c = Courrier.de(sl.getServer());
                ListTag l = c.reserves.computeIfAbsent(proprietaire, k -> new ListTag());
                l.addAll(reserveTag(sl.registryAccess()));
                c.setDirty();
                reserve.vider();
                return;
            }
            lacherReserve();
        }

        /** Le bureau est cassé : la réserve tombe au sol, en piles. */
        void lacherReserve() {
            if (level == null || reserve.vide()) return;
            List<ItemStack> l = new ArrayList<>();
            for (Reserve.Tas<ItemStack> x : reserve.tas) piles(x, l);
            reserve.vider();
            for (ItemStack s : l) Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), s);
        }

        /** Ce qu'un ouvrier répond quand on lui demande ce qu'il y a dans la réserve : les 5 plus grosses sortes. */
        String reserveTexte() {
            if (reserve.vide()) return "La réserve est vide, chef : tout tient au dépôt.";
            List<String> l = new ArrayList<>();
            for (Reserve.Tas<ItemStack> x : reserve.plusGros(5)) l.add(x.nombre + " " + Consignes.nom(x.sorte));
            int autres = reserve.tas.size() - l.size();
            return "Dans la réserve : " + String.join(", ", l) + (autres > 0 ? ", et " + autres + " autre" + (autres > 1 ? "s" : "") + " sorte" + (autres > 1 ? "s" : "") : "") + ".";
        }

        // --- L'écran du poste -------------------------------------------------------------------------

        /** Tout ce que l'écran affiche : ses ouvriers (vivants, morts, disparus) et son courrier. */
        CompoundTag fiche(ServerPlayer p) {
            CompoundTag f = new CompoundTag();
            Courrier c = Courrier.de(p.server);
            f.putString("titre", getDisplayName().getString());
            boolean poste = estLePoste();
            f.putBoolean("poste", poste);
            Courrier.Poste ailleurs = c.poste(p.getUUID());
            if (!poste && ailleurs != null && posteExiste(p.server, ailleurs)) f.putString("ailleurs", ailleurs.pos().toShortString());
            if (!poste) return f;

            ListTag ouvriers = new ListTag();
            Set<UUID> vus = new HashSet<>();
            for (Travailleur t : ColoniePnj.pnjDe(p.server, p.getUUID())) {
                vus.add(t.getUUID());
                c.inscrire(t);
                ouvriers.add(ficheOuvrier(t));
            }
            var effectif = c.effectifs.get(p.getUUID());
            if (effectif != null) effectif.forEach((id, m) -> {
                if (vus.contains(id)) return;
                CompoundTag x = new CompoundTag();
                x.putString("uuid", id.toString());
                x.putString("nom", m.nom);
                x.putString("metier", Metier.nom(m.metier));
                x.putInt("niveau", m.niveau);
                x.putString("statut", m.mort.isEmpty() ? "Disparu" : "Mort");
                x.putString("detail", m.mort.isEmpty() ? "Introuvable : il n'est plus dans le monde." : "Mort le " + m.mort.toLowerCase() + ".");
                x.putBoolean("absent", true);
                ouvriers.add(x);
            });
            f.put("ouvriers", ouvriers);

            // Triées (importantes non lues, non lues, lues), avec le compteur dans l'objet : un ancien écran les montre
            // aussi dans cet ordre. Les archives à part, sous une clé qu'un ancien écran ignore.
            List<Courrier.Lettre> boite = c.boite(p.getUUID());
            f.put("lettres", lettres(Boite.affichees(boite, false)));
            f.put("archives", lettres(Boite.affichees(boite, true)));
            f.putLong("nonLues", c.nonLues(p.getUUID()));
            // Au-delà des 60 envoyées : y a-t-il des lues à effacer, de la routine à marquer lue ?
            f.putInt("lues", (int) boite.stream().filter(l -> l.lue && !l.archivee).count());
            f.putInt("aMarquer", (int) boite.stream().filter(l -> !l.lue && !l.archivee && !l.importante).count());
            return f;
        }

        static ListTag lettres(List<Courrier.Lettre> ls) {
            ListTag t = new ListTag();
            for (int i = 0; i < Math.min(60, ls.size()); i++) {
                CompoundTag x = ls.get(i).sauver();
                x.putString("objet", Boite.objet(ls.get(i)));
                t.add(x);
            }
            return t;
        }

        static CompoundTag ficheOuvrier(Travailleur t) {
            CompoundTag e = t.fiche(false);
            e.putString("uuid", t.getUUID().toString());
            e.putInt("fatigue", t.fatigue);
            e.putLong("salaireDemande", t.salaireDemande());
            e.putLong("salaireVerse", t.salaireVerse());
            e.putInt("debut", t.debut);
            e.putInt("fin", t.fin);
            e.putInt("joursExpedition", t.joursExpedition);
            e.putString("sortie", t.sortieTexte());
            double d = t.distanceAuBureau();
            e.putString("distance", t.bureau == null ? "" : d < 4 ? "au poste" : "à " + (int) d + " blocs");
            long paie = t.prochainePaie - System.currentTimeMillis();
            e.putString("paie", paie <= 0 ? "Paie due : il vient la chercher" : "Prochaine paie dans " + (paie >= 3_600_000 ? paie / 3_600_000 + " h " : "") + (paie / 60_000 % 60) + " min");
            ListTag ms = new ListTag();
            for (Travailleur.Mission m : t.missions) ms.add(net.minecraft.nbt.StringTag.valueOf(Metier.avancementTexte(t, m)));
            e.put("missions", ms);
            e.putInt("aLire", t.lettresALire.size() + t.lettresAuCalme.size());
            return e;
        }

        /** Les boutons de l'écran du poste (réservés au propriétaire, à moins de 8 blocs). */
        void action(ServerPlayer p, String action, CompoundTag d) {
            if (!p.getUUID().equals(proprietaire) || p.distanceToSqr(worldPosition.getCenter()) > 64) return;
            Courrier c = Courrier.de(p.server);
            switch (action) {
                case "stock" -> { p.openMenu(this); return; }
                case "devenir_poste" -> {
                    Courrier.Poste ancien = c.poste(p.getUUID());
                    if (ancien != null && posteExiste(p.server, ancien) && !estLePoste()) return;
                    c.definirPoste(p.getUUID(), level, worldPosition);
                    Travailleur.signaler(p, "Ce bureau est ton poste de travail : tous tes ouvriers s'y rattachent.");
                }
                case "enregistrer" -> {
                    Travailleur t = ouvrier(p, d.getString("uuid"));
                    if (t == null) return;
                    t.joursExpedition = Math.clamp(d.getInt("joursExpedition"), 1, 30);
                    t.debut = Math.floorMod(d.getInt("debut"), 24);
                    t.fin = Math.floorMod(d.getInt("fin"), 24);
                    long s = d.getLong("salaire");
                    t.salaire = s < 0 || s == t.salaireDemande() ? -1 : Math.min(s, 1_000_000);
                }
                case "ecrire" -> {
                    Travailleur t = ouvrier(p, d.getString("uuid"));
                    String texte = d.getString("texte").strip();
                    if (t == null || texte.isEmpty() || texte.length() > 1000) return;
                    Courrier.ecrireAuPnj(p, t, texte);
                    Travailleur.signaler(p, t.chezLui(t.blockPosition()) ? "Lettre posée au poste : " + t.prenom + " passera la prendre."
                            : "Un corbeau s'envole vers " + t.prenom + ".");
                }
                // Le courrier : seulement la boîte de celui qui agit (le propriétaire, vérifié plus haut).
                case "lue" -> c.boite(p.getUUID()).stream().filter(l -> l.id == d.getLong("id")).forEach(l -> { l.lue = true; c.setDirty(); });
                case "supprimer" -> { if (c.boite(p.getUUID()).removeIf(l -> l.id == d.getLong("id"))) c.setDirty(); }
                case "tout_lu" -> { if (Boite.toutLu(c.boite(p.getUUID()))) c.setDirty(); }
                case "archiver" -> { if (Boite.archiver(c.boite(p.getUUID()), d.getLong("id"), true)) c.setDirty(); }
                case "desarchiver" -> { if (Boite.archiver(c.boite(p.getUUID()), d.getLong("id"), false)) c.setDirty(); }
                case "tout_effacer" -> { if (Boite.effacerLues(c.boite(p.getUUID()))) c.setDirty(); }
                case "retirer" -> {
                    try { c.retirer(p.getUUID(), UUID.fromString(d.getString("uuid"))); } catch (IllegalArgumentException ignore) { return; }
                }
                default -> { return; }
            }
            if (p.connection != null && p.connection.hasChannel(Reseau.Poste.TYPE)) PacketDistributor.sendToPlayer(p, new Reseau.Poste(worldPosition, fiche(p)));
        }

        static Travailleur ouvrier(ServerPlayer p, String uuid) {
            return ColoniePnj.pnjDe(p.server, p.getUUID()).stream().filter(t -> t.getUUID().toString().equals(uuid)).findFirst().orElse(null);
        }
    }

    /** Écran de la caisse et du dépôt : 1 rangée de caisse, 3 rangées de dépôt, puis l'inventaire du joueur. */
    public static class Menu extends AbstractContainerMenu {
        public static final int Y_CAISSE = 18, Y_DEPOT = 48, Y_INVENTAIRE = 115;
        final Container caisse, depot;
        final Entite entite;

        public Menu(int id, Inventory inv) { this(id, inv, new SimpleContainer(9), new SimpleContainer(27), null); }

        Menu(int id, Inventory inv, Container caisse, Container depot, Entite entite) {
            super(ColoniePnj.BUREAU_MENU.get(), id);
            this.caisse = caisse;
            this.depot = depot;
            this.entite = entite;
            for (int i = 0; i < 9; i++)
                addSlot(new Slot(caisse, i, 8 + i * 18, Y_CAISSE) {
                    @Override
                    public boolean mayPlace(ItemStack s) { return Reglages.piece(s) >= 0; }
                });
            for (int r = 0; r < 3; r++) for (int i = 0; i < 9; i++) addSlot(new Slot(depot, i + r * 9, 8 + i * 18, Y_DEPOT + r * 18));
            for (int r = 0; r < 3; r++) for (int i = 0; i < 9; i++) addSlot(new Slot(inv, 9 + i + r * 9, 8 + i * 18, Y_INVENTAIRE + r * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(inv, i, 8 + i * 18, Y_INVENTAIRE + 58));
        }

        @Override
        public boolean stillValid(Player p) { return entite == null || Container.stillValidBlockEntity(entite, p); }

        @Override
        public ItemStack quickMoveStack(Player p, int index) {
            Slot slot = slots.get(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            ItemStack s = slot.getItem(), copie = s.copy();
            if (index < 36) {
                if (!moveItemStackTo(s, 36, 72, true)) return ItemStack.EMPTY;
            } else if (Reglages.piece(s) >= 0) {
                if (!moveItemStackTo(s, 0, 9, false) && !moveItemStackTo(s, 9, 36, false)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(s, 9, 36, false)) return ItemStack.EMPTY;
            if (s.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
            return copie;
        }
    }

    /** Le poste du PNJ (celui de son chef), ou null. */
    public static BlockPos trouver(Travailleur t) { return t.bureau; }

    /**
     * Le poste dont ce bloc fait partie du dépôt : le bureau lui-même, ou un coffre collé à lui (une moitié suffit) ; null
     * sinon, ou si ce bureau n'est pas le poste de son propriétaire (GardeDepot, cahier 6.4).
     */
    static Entite depotDe(Level l, BlockPos p) {
        BlockEntity be = l.getBlockEntity(p);
        if (be instanceof Entite e) return e.estLePoste() ? e : null;
        if (be == null || !Transporteur.coffre(l, p)) return null; // un clic sur un bloc ordinaire : rien de plus à chercher
        for (BlockPos q : Transporteur.moities(l, p))
            for (Direction d : Direction.values())
                if (l.getBlockEntity(q.relative(d)) instanceof Entite e && e.estLePoste()) return e;
        return null;
    }

    /** Le poste chargé du PNJ, ou null. */
    static Entite entite(Travailleur t) {
        return t.bureau != null && t.level().isLoaded(t.bureau) && t.level().getBlockEntity(t.bureau) instanceof Entite e ? e : null;
    }
}

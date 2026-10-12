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
                    Travailleur.signaler(p, "Ton poste de travail est posé : tous tes ouvriers s'y rattachent. Clic droit dessus pour les gérer.");
                }
            }
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

        public Entite(BlockPos pos, BlockState state) { super(ColoniePnj.BUREAU_BE.get(), pos, state); }

        @Override
        protected void saveAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.saveAdditional(t, reg);
            t.put("Caisse", caisse.createTag(reg));
            t.put("Depot", depot.createTag(reg));
            if (proprietaire != null) t.putUUID("Proprietaire", proprietaire);
            t.putString("NomProprietaire", nomProprietaire);
        }

        @Override
        protected void loadAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.loadAdditional(t, reg);
            caisse.fromTag(t.getList("Caisse", Tag.TAG_COMPOUND), reg);
            depot.fromTag(t.getList("Depot", Tag.TAG_COMPOUND), reg);
            proprietaire = t.hasUUID("Proprietaire") ? t.getUUID("Proprietaire") : null;
            nomProprietaire = t.getString("NomProprietaire");
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(nomProprietaire.isEmpty() ? "Poste de travail" : "Poste de travail de " + nomProprietaire);
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new Menu(id, inv, caisse, depot, this); }

        boolean estLePoste() {
            if (proprietaire == null || !(level instanceof ServerLevel sl)) return false;
            Courrier.Poste p = Courrier.de(sl.getServer()).poste(proprietaire);
            return p != null && p.monde() == level.dimension() && p.pos().equals(worldPosition);
        }

        // --- Le dépôt : le bureau, plus les coffres, tonneaux ou réservoirs collés à lui -----------------

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

        /** Range un objet au dépôt puis dans les coffres voisins ; renvoie ce qui n'a pas trouvé de place. */
        public ItemStack ranger(ItemStack s) {
            ItemStack reste = depot.addItem(s);
            for (IItemHandler h : voisins()) {
                if (reste.isEmpty()) break;
                reste = ItemHandlerHelper.insertItemStacked(h, reste, false);
            }
            return reste;
        }

        public int compter(Actions.Filtre f) {
            int n = 0;
            for (int i = 0; i < depot.getContainerSize(); i++) if (f.objet(depot.getItem(i))) n += depot.getItem(i).getCount();
            for (IItemHandler h : voisins())
                for (int i = 0; i < h.getSlots(); i++) if (f.objet(h.getStackInSlot(i))) n += h.getStackInSlot(i).getCount();
            return n;
        }

        /** Prend jusqu'à n objets au dépôt (puis dans les coffres voisins). */
        public List<ItemStack> prendre(Actions.Filtre f, int n) {
            List<ItemStack> pris = new ArrayList<>();
            for (int i = 0; i < depot.getContainerSize() && n > 0; i++) {
                ItemStack s = depot.getItem(i);
                if (!f.objet(s)) continue;
                ItemStack part = s.split(Math.min(n, s.getCount()));
                n -= part.getCount();
                pris.add(part);
            }
            depot.setChanged();
            for (IItemHandler h : voisins())
                for (int i = 0; i < h.getSlots() && n > 0; i++) {
                    if (!f.objet(h.getStackInSlot(i))) continue;
                    ItemStack part = h.extractItem(i, n, false);
                    n -= part.getCount();
                    if (!part.isEmpty()) pris.add(part);
                }
            return pris;
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

    /** Le poste chargé du PNJ, ou null. */
    static Entite entite(Travailleur t) {
        return t.bureau != null && t.level().isLoaded(t.bureau) && t.level().getBlockEntity(t.bureau) instanceof Entite e ? e : null;
    }
}

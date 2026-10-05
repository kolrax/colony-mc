package fr.lacolonie.pnj;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Le bureau du chef : une caisse (les salaires) et un dépôt (ce que les PNJ produisent). */
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

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (placer instanceof Player p && level.getBlockEntity(pos) instanceof Entite e) {
                e.proprietaire = p.getUUID();
                e.nomProprietaire = p.getName().getString();
                e.setChanged();
                if (level instanceof ServerLevel sl) {
                    Registre.de(sl.getServer()).mettre(sl, pos, p.getUUID());
                    p.sendSystemMessage(Component.literal("Ton bureau est posé. En haut, la caisse : mets-y les pièces des salaires. En dessous, le dépôt : tes PNJ y rangent ce qu'ils produisent."));
                }
            }
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entite e) player.openMenu(e);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState neuf, boolean piston) {
            if (!state.is(neuf.getBlock())) {
                if (level.getBlockEntity(pos) instanceof Entite e) {
                    Containers.dropContents(level, pos, e.caisse);
                    Containers.dropContents(level, pos, e.depot);
                }
                if (level instanceof ServerLevel sl) Registre.de(sl.getServer()).oublier(sl, pos);
            }
            super.onRemove(state, level, pos, neuf, piston);
        }
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
        public void onLoad() {
            super.onLoad();
            if (level instanceof ServerLevel sl && proprietaire != null) Registre.de(sl.getServer()).mettre(sl, worldPosition, proprietaire);
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(nomProprietaire.isEmpty() ? "Bureau" : "Bureau de " + nomProprietaire);
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new Menu(id, inv, caisse, depot, this); }
    }

    /** Écran du bureau : 1 rangée de caisse, 3 rangées de dépôt, puis l'inventaire du joueur. */
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

    // --- Où sont les bureaux de chacun --------------------------------------------------------------

    public static class Registre extends SavedData {
        record Entree(String dim, BlockPos pos, UUID chef) {}

        final List<Entree> bureaux = new ArrayList<>();

        static Registre de(MinecraftServer s) {
            return s.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Registre::new, Registre::charger, null), "colonie_pnj_bureaux");
        }

        static Registre charger(CompoundTag t, HolderLookup.Provider reg) {
            Registre r = new Registre();
            for (Tag x : t.getList("Bureaux", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                r.bureaux.add(new Entree(c.getString("Dim"), BlockPos.of(c.getLong("Pos")), c.getUUID("Chef")));
            }
            return r;
        }

        @Override
        public CompoundTag save(CompoundTag t, HolderLookup.Provider reg) {
            ListTag l = new ListTag();
            for (Entree e : bureaux) {
                CompoundTag c = new CompoundTag();
                c.putString("Dim", e.dim);
                c.putLong("Pos", e.pos.asLong());
                c.putUUID("Chef", e.chef);
                l.add(c);
            }
            t.put("Bureaux", l);
            return t;
        }

        void mettre(ServerLevel l, BlockPos p, UUID chef) {
            Entree e = new Entree(l.dimension().location().toString(), p.immutable(), chef);
            if (bureaux.contains(e)) return;
            oublier(l, p);
            bureaux.add(e);
            setDirty();
        }

        void oublier(ServerLevel l, BlockPos p) {
            String dim = l.dimension().location().toString();
            if (bureaux.removeIf(e -> e.dim.equals(dim) && e.pos.equals(p))) setDirty();
        }
    }

    /** Le bureau de son chef le plus proche du PNJ (256 blocs au plus), ou null. */
    public static BlockPos trouver(Travailleur t) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return null;
        String dim = sl.dimension().location().toString();
        BlockPos meilleur = null;
        double dMin = 256 * 256;
        for (Registre.Entree e : Registre.de(sl.getServer()).bureaux) {
            if (!e.chef.equals(t.chef) || !e.dim.equals(dim)) continue;
            double d = e.pos.distToCenterSqr(t.position());
            if (d < dMin) { dMin = d; meilleur = e.pos; }
        }
        return meilleur;
    }
}

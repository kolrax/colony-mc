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
import java.util.List;
import java.util.UUID;

/**
 * Le bureau : le poste de pilotage d'un PNJ (un par bureau).
 * Une caisse (les salaires), un dépôt (sa production, agrandi par les coffres collés au bureau),
 * et l'écran de gestion du propriétaire : commande, horaires, paie, durée des expéditions.
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

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            if (placer instanceof Player p && level.getBlockEntity(pos) instanceof Entite e) {
                e.proprietaire = p.getUUID();
                e.nomProprietaire = p.getName().getString();
                e.setChanged();
                if (!level.isClientSide)
                    p.sendSystemMessage(Component.literal("Ton bureau est posé. Clic droit dessus pour y rattacher un PNJ et lui donner son travail."));
            }
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof Entite e && player instanceof ServerPlayer sp) {
                // Le propriétaire gère son employé ; les autres ne voient que la caisse et le dépôt (aucune protection : le vol RP est permis).
                if (sp.getUUID().equals(e.proprietaire)) PacketDistributor.sendToPlayer(sp, new Reseau.FicheBureau(pos, e.fiche(sp)));
                else sp.openMenu(e);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState neuf, boolean piston) {
            if (!state.is(neuf.getBlock()) && level.getBlockEntity(pos) instanceof Entite e) {
                Containers.dropContents(level, pos, e.caisse);
                Containers.dropContents(level, pos, e.depot);
                Travailleur t = e.employeCharge();
                if (t != null) {
                    t.bureau = null;
                    t.lit = null;
                    t.direAuChef("Mon bureau a été détruit : je ne peux plus travailler. Rattache-moi à un autre bureau.");
                }
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
        public UUID proprietaire, employe;
        public String nomProprietaire = "", nomEmploye = "";
        /** Son employé est mort (le bureau le montre jusqu'à ce qu'on libère la place). */
        public boolean mort;

        public Entite(BlockPos pos, BlockState state) { super(ColoniePnj.BUREAU_BE.get(), pos, state); }

        @Override
        protected void saveAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.saveAdditional(t, reg);
            t.put("Caisse", caisse.createTag(reg));
            t.put("Depot", depot.createTag(reg));
            if (proprietaire != null) t.putUUID("Proprietaire", proprietaire);
            t.putString("NomProprietaire", nomProprietaire);
            if (employe != null) t.putUUID("Employe", employe);
            t.putString("NomEmploye", nomEmploye);
            t.putBoolean("Mort", mort);
        }

        @Override
        protected void loadAdditional(CompoundTag t, HolderLookup.Provider reg) {
            super.loadAdditional(t, reg);
            caisse.fromTag(t.getList("Caisse", Tag.TAG_COMPOUND), reg);
            depot.fromTag(t.getList("Depot", Tag.TAG_COMPOUND), reg);
            proprietaire = t.hasUUID("Proprietaire") ? t.getUUID("Proprietaire") : null;
            nomProprietaire = t.getString("NomProprietaire");
            employe = t.hasUUID("Employe") ? t.getUUID("Employe") : null;
            nomEmploye = t.getString("NomEmploye");
            mort = t.getBoolean("Mort");
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(nomProprietaire.isEmpty() ? "Bureau" : "Bureau de " + nomProprietaire);
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new Menu(id, inv, caisse, depot, this); }

        /** Son employé, s'il est chargé (il l'est toujours : il garde son chunk). */
        Travailleur employeCharge() {
            if (employe == null || !(level instanceof ServerLevel sl)) return null;
            return sl.getEntity(employe) instanceof Travailleur t && t.isAlive() ? t : null;
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

        // --- L'écran de gestion ---------------------------------------------------------------------

        /** Tout ce que l'écran du bureau affiche. */
        CompoundTag fiche(ServerPlayer p) {
            CompoundTag f = new CompoundTag();
            f.putString("titre", getDisplayName().getString());
            Travailleur t = employeCharge();
            if (t != null && !worldPosition.equals(t.bureau)) { employe = null; t = null; setChanged(); }
            if (t != null) {
                CompoundTag e = t.fiche(false);
                e.putString("uuid", t.getUUID().toString());
                e.putInt("fatigue", t.fatigue);
                e.putLong("salaireDemande", t.salaireDemande());
                e.putLong("salaireVerse", t.salaireVerse());
                e.putString("salaireDemandeTexte", Reglages.texte(t.salaireDemande()));
                e.putInt("debut", t.debut);
                e.putInt("fin", t.fin);
                e.putInt("joursExpedition", t.joursExpedition);
                BlockPos lit = t.monLit();
                e.putString("lit", lit == null ? "" : lit.getX() + ", " + lit.getY() + ", " + lit.getZ());
                e.putString("sortie", t.sortieTexte());
                e.putString("statut", t.statut());
                double d = t.distanceAuBureau();
                e.putString("distance", d < 4 ? "au bureau" : "à " + (int) d + " blocs");
                long paie = t.prochainePaie - System.currentTimeMillis();
                e.putString("paie", paie <= 0 ? "Paie due : il vient la chercher" : "Prochaine paie dans " + (paie >= 3_600_000 ? paie / 3_600_000 + " h " : "") + (paie / 60_000 % 60) + " min");
                e.putString("commande", t.commande);
                e.putString("commandeMode", t.commandeMode);
                e.putInt("commandeNombre", t.commandeNombre);
                e.putString("avancement", Metier.avancementTexte(t));
                ListTag cat = new ListTag();
                Metier m = Metier.de(t.metier);
                if (m != null) for (Metier.Commande ci : m.commandes) {
                    CompoundTag x = new CompoundTag();
                    x.putString("id", ci.id);
                    x.putString("nom", ci.nom);
                    x.putBoolean("quantite", ci.objet != null);
                    cat.add(x);
                }
                e.put("catalogue", cat);
                f.put("employe", e);
            } else if (employe != null) {
                // Rattaché mais introuvable : mort, ou disparu (dans une autre dimension, retiré du monde…).
                f.putString(mort ? "mort" : "absent", nomEmploye);
            }
            ListTag libres = new ListTag();
            for (Travailleur x : ColoniePnj.pnjDe(p.server, p.getUUID())) {
                if (x.bureau != null || x == t) continue;
                CompoundTag l = new CompoundTag();
                l.putString("uuid", x.getUUID().toString());
                l.putString("nom", x.prenom + " (" + Metier.nom(x.metier) + " " + "★".repeat(x.niveau) + ")");
                libres.add(l);
            }
            f.put("libres", libres);
            return f;
        }

        /** Les boutons de l'écran de gestion (réservés au propriétaire, à moins de 8 blocs). */
        void action(ServerPlayer p, String action, CompoundTag d) {
            if (!p.getUUID().equals(proprietaire) || p.distanceToSqr(worldPosition.getCenter()) > 64) return;
            Travailleur t = employeCharge();
            switch (action) {
                case "stock" -> { p.openMenu(this); return; }
                case "rattacher" -> {
                    Travailleur x = ColoniePnj.pnjDe(p.server, p.getUUID()).stream()
                            .filter(y -> y.getUUID().toString().equals(d.getString("uuid"))).findFirst().orElse(null);
                    if (x == null) return;
                    if (t != null && t != x) liberer(t);
                    liberer(x);
                    employe = x.getUUID();
                    nomEmploye = x.prenom;
                    mort = false;
                    x.bureau = worldPosition;
                    x.lit = null; // il prendra un lit près de son nouveau bureau
                    if (x.mode != Travailleur.Mode.GREVE) x.mode = Travailleur.Mode.TRAVAIL; // changer de bureau n'efface pas une grève
                    x.finirAction("ordre");
                    x.majNom();
                    x.dire("Me voilà rattaché à ce bureau. Donne-moi ma commande, chef.");
                    setChanged();
                }
                case "liberer" -> {
                    if (t == null) { employe = null; nomEmploye = ""; mort = false; setChanged(); }
                    else {
                        liberer(t);
                        t.mode = Travailleur.Mode.ATTENDRE;
                        t.finirAction("ordre");
                        t.majNom();
                        t.dire("Je ne suis plus rattaché à ce bureau.");
                    }
                }
                case "enregistrer" -> {
                    if (t == null) return;
                    String c = d.getString("commande");
                    String mode = d.getString("commandeMode").equals("quantite") ? "quantite" : "stock";
                    if (!c.equals(t.commande) || !mode.equals(t.commandeMode)) t.commandeLivre = 0;
                    Metier m = Metier.de(t.metier);
                    t.commande = m != null && m.commandes.stream().anyMatch(ci -> ci.id.equals(c)) ? c : "";
                    t.commandeMode = mode;
                    t.commandeNombre = Math.clamp(d.getInt("commandeNombre"), 1, 100_000);
                    t.joursExpedition = Math.clamp(d.getInt("joursExpedition"), 1, 30);
                    t.debut = Math.floorMod(d.getInt("debut"), 24);
                    t.fin = Math.floorMod(d.getInt("fin"), 24);
                    long s = d.getLong("salaire");
                    t.salaire = s < 0 || s == t.salaireDemande() ? -1 : Math.min(s, 1_000_000);
                    t.finirAction("ordre");
                    t.dire(t.commande.isEmpty() ? "Bien, chef. Mais je n'ai pas encore de commande." : "Bien, chef : " + Metier.nomCommande(t.metier, t.commande) + ".");
                }
                default -> { return; }
            }
            PacketDistributor.sendToPlayer(p, new Reseau.FicheBureau(worldPosition, fiche(p)));
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

    /** À sa mort, le PNJ le note sur son bureau (chargé au besoin), qui l'affiche jusqu'à ce qu'on libère la place. */
    static void marquerMort(Travailleur t) {
        if (t.bureau == null || !(t.level() instanceof ServerLevel sl)) return;
        if (sl.getChunk(t.bureau).getBlockEntity(t.bureau) instanceof Entite e && t.getUUID().equals(e.employe)) {
            e.mort = true;
            e.nomEmploye = t.prenom;
            e.setChanged();
        }
    }

    /** Le bureau du PNJ (celui auquel il est rattaché), ou null. */
    public static BlockPos trouver(Travailleur t) { return t.bureau; }

    /** Le bureau chargé du PNJ, ou null. */
    static Entite entite(Travailleur t) {
        return t.bureau != null && t.level().isLoaded(t.bureau) && t.level().getBlockEntity(t.bureau) instanceof Entite e ? e : null;
    }

    /** Détache le PNJ de son bureau (le bureau oublie aussi son employé, s'il est chargé). */
    static void liberer(Travailleur t) {
        Entite e = entite(t);
        if (e != null && t.getUUID().equals(e.employe)) {
            e.employe = null;
            e.nomEmploye = "";
            e.setChanged();
        }
        t.bureau = null;
        t.lit = null;
    }
}

package fr.lacolonie.pnj;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Tout ce qui ne tourne que chez le joueur : l'apparence des PNJ et les écrans. */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class Client {
    private Client() {}

    @SubscribeEvent
    static void rendus(EntityRenderersEvent.RegisterRenderers e) { e.registerEntityRenderer(ColoniePnj.TRAVAILLEUR.get(), Rendu::new); }

    @SubscribeEvent
    static void ecrans(RegisterMenuScreensEvent e) { e.register(ColoniePnj.BUREAU_MENU.get(), EcranBureau::new); }

    static void ouvrirFiche(Reseau.Fiche f) { Minecraft.getInstance().setScreen(new EcranFiche(f.id(), f.infos())); }

    static void ouvrirBureau(Reseau.FicheBureau f) { Minecraft.getInstance().setScreen(new EcranGestion(f.pos(), f.infos())); }

    // --- Apparence : un modèle de joueur avec le skin d'un pseudo Minecraft (ou un skin de base) ---------

    static final Map<String, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    static final Set<String> DEMANDES = ConcurrentHashMap.newKeySet();

    static PlayerSkin skin(Travailleur e) {
        String pseudo = e.skin();
        if (!pseudo.isEmpty()) {
            PlayerSkin s = SKINS.get(pseudo);
            if (s != null) return s;
            if (DEMANDES.add(pseudo))
                SkullBlockEntity.fetchGameProfile(pseudo)
                        .thenCompose(o -> o.map(p -> Minecraft.getInstance().getSkinManager().getOrLoad(p)).orElse(CompletableFuture.completedFuture(null)))
                        .thenAccept(s2 -> { if (s2 != null) SKINS.put(pseudo, s2); });
        }
        return DefaultPlayerSkin.get(e.getUUID());
    }

    static class Rendu extends HumanoidMobRenderer<Travailleur, PlayerModel<Travailleur>> {
        final PlayerModel<Travailleur> large, fin;

        Rendu(EntityRendererProvider.Context c) {
            super(c, new PlayerModel<>(c.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
            large = model;
            fin = new PlayerModel<>(c.bakeLayer(ModelLayers.PLAYER_SLIM), true);
            addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(c.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(c.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), c.getModelManager()));
        }

        @Override
        public void render(Travailleur e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int lumiere) {
            model = skin(e).model() == PlayerSkin.Model.SLIM ? fin : large;
            super.render(e, yaw, pt, ps, buf, lumiere);
        }

        @Override
        public ResourceLocation getTextureLocation(Travailleur e) { return skin(e).texture(); }

        @Override
        protected void scale(Travailleur e, PoseStack ps, float pt) { ps.scale(0.9375f, 0.9375f, 0.9375f); }
    }

    // --- Fiche d'un PNJ (entretien d'embauche ou ordres du chef) -----------------------------------------

    static class EcranFiche extends Screen {
        final int id;
        final CompoundTag i;
        final boolean entretien;
        boolean confirmer;
        int x0, y0, largeur = 280, hauteur;
        final List<FormattedCharSequence> lignes = new ArrayList<>();

        EcranFiche(int id, CompoundTag infos) {
            super(Component.literal(infos.getString("nom")));
            this.id = id;
            this.i = infos;
            this.entretien = infos.getBoolean("entretien");
        }

        void envoyer(String ordre) {
            PacketDistributor.sendToServer(new Reseau.Commande(id, ordre));
            onClose();
        }

        @Override
        protected void init() {
            lignes.clear();
            List<Component> texte = new ArrayList<>();
            texte.add(Component.literal(i.getString("nom") + " — " + i.getString("metier") + "  " + "★".repeat(i.getInt("niveau")) + "☆".repeat(5 - i.getInt("niveau"))).withStyle(s -> s.withBold(true).withColor(0xF2C14E)));
            if (!i.getString("description").isEmpty()) texte.add(Component.literal(i.getString("description")).withStyle(s -> s.withColor(0xBBBBBB)));
            texte.add(Component.literal(i.getString("attitude")).withStyle(s -> s.withItalic(true).withColor(0x9FB4C7)));
            texte.add(Component.empty());
            if (entretien) {
                texte.add(Component.literal("Il demande : " + i.getString("salaire") + " " + i.getString("periode") + "."));
                texte.add(Component.literal("Prime d'embauche : " + i.getString("prime") + ", prise dans ton inventaire."));
                texte.add(Component.literal("Il viendra chercher sa paie dans la caisse de ton bureau.").withStyle(s -> s.withColor(0xBBBBBB)));
            } else {
                texte.add(Component.literal("État : " + i.getString("mode") + (i.getString("action").isEmpty() ? "" : " (" + i.getString("action") + ")")));
                texte.add(Component.literal("Vie : " + i.getInt("vie") + "/" + i.getInt("vieMax") + "    Faim : " + i.getInt("faim") + "/20"));
                texte.add(Component.literal("Salaire : " + i.getString("salaire") + " " + i.getString("periode")
                        + (i.getInt("impayes") > 0 ? "    Paies en retard : " + i.getInt("impayes") : "")));
                if (!i.getString("besoin").isEmpty()) texte.add(Component.literal("Il te demande : " + i.getString("besoin")).withStyle(s -> s.withColor(0xFF9966)));
                texte.add(Component.literal("Accroupi + clic droit avec un objet en main : tu le lui donnes.").withStyle(s -> s.withColor(0x888888)));
            }
            for (Component c : texte) lignes.addAll(c == Component.EMPTY || c.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(c, largeur - 20));
            hauteur = 20 + lignes.size() * 11 + (entretien ? 34 : 58);
            x0 = (width - largeur) / 2;
            y0 = (height - hauteur) / 2;
            int yb = y0 + hauteur - (entretien ? 30 : 54);
            if (entretien) {
                addRenderableWidget(Button.builder(Component.literal("Embaucher"), b -> envoyer("embaucher")).bounds(x0 + 10, yb, 125, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Pas aujourd'hui"), b -> onClose()).bounds(x0 + largeur - 135, yb, 125, 20).build());
            } else {
                int w = (largeur - 40) / 3;
                addRenderableWidget(Button.builder(Component.literal("Suivre"), b -> envoyer("suivre")).bounds(x0 + 10, yb, w, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Attendre ici"), b -> envoyer("attendre")).bounds(x0 + 20 + w, yb, w, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Au travail"), b -> envoyer("travailler"))
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Il reprend son travail et cherche lui-même où trouver ce que demande sa commande. Commande, horaires, paie et expéditions se règlent dans l'écran de son bureau.")))
                        .bounds(x0 + 30 + 2 * w, yb, w, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Son sac"), b -> envoyer("inventaire")).bounds(x0 + 10, yb + 24, w, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Licencier"), b -> {
                    if (confirmer) envoyer("licencier");
                    else { confirmer = true; b.setMessage(Component.literal("Sûr ?").withStyle(s -> s.withColor(0xFF5555))); }
                }).bounds(x0 + 20 + w, yb + 24, w, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose()).bounds(x0 + 30 + 2 * w, yb + 24, w, 20).build());
            }
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            super.renderBackground(g, mx, my, pt);
            g.fill(x0, y0, x0 + largeur, y0 + hauteur, 0xE0181C1A);
            g.renderOutline(x0, y0, largeur, hauteur, 0xFF8A6A2A);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            int y = y0 + 10;
            for (FormattedCharSequence l : lignes) { g.drawString(font, l, x0 + 10, y, 0xFFFFFF, false); y += 11; }
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    // --- Le bureau : l'écran de gestion de son employé ---------------------------------------------------

    static class EcranGestion extends Screen {
        final net.minecraft.core.BlockPos pos;
        final CompoundTag f, e;
        final List<CompoundTag> catalogue = new ArrayList<>();
        int iCommande, debut, fin, jours;
        String mode;
        int x0, y0;
        static final int L = 360, H = 206;
        net.minecraft.client.gui.components.EditBox nombre, salaire;
        Button bCommande, bMode, bDebut, bFin, bJours;

        EcranGestion(net.minecraft.core.BlockPos pos, CompoundTag infos) {
            super(Component.literal(infos.getString("titre")));
            this.pos = pos;
            this.f = infos;
            this.e = infos.contains("employe") ? infos.getCompound("employe") : null;
            if (e != null) {
                CompoundTag aucune = new CompoundTag();
                aucune.putString("id", "");
                aucune.putString("nom", "Aucune commande");
                catalogue.add(aucune);
                for (var x : e.getList("catalogue", net.minecraft.nbt.Tag.TAG_COMPOUND)) catalogue.add((CompoundTag) x);
                for (int i = 0; i < catalogue.size(); i++) if (catalogue.get(i).getString("id").equals(e.getString("commande"))) iCommande = i;
                mode = e.getString("commandeMode");
                debut = e.getInt("debut");
                fin = e.getInt("fin");
                jours = Math.max(1, e.getInt("joursExpedition"));
            }
        }

        void envoyer(String action, CompoundTag d) { PacketDistributor.sendToServer(new Reseau.ActionBureau(pos, action, d)); }

        static int entier(net.minecraft.client.gui.components.EditBox b, int defaut) {
            try { return Integer.parseInt(b.getValue().trim()); } catch (NumberFormatException ex) { return defaut; }
        }

        net.minecraft.client.gui.components.EditBox champ(int x, int y, int w, String valeur) {
            var b = new net.minecraft.client.gui.components.EditBox(font, x, y, w, 16, Component.empty());
            b.setMaxLength(9);
            b.setFilter(v -> v.matches("-?[0-9]*"));
            b.setValue(valeur);
            return addRenderableWidget(b);
        }

        boolean quantitePossible() { return catalogue.get(iCommande).getBoolean("quantite"); }

        void majBoutons() {
            bCommande.setMessage(Component.literal(catalogue.get(iCommande).getString("nom")));
            bMode.setMessage(Component.literal(mode.equals("quantite") ? "Quantité, puis arrêt" : "Garder en stock"));
            bMode.active = nombre.active = quantitePossible();
            bDebut.setMessage(Component.literal(debut + " h"));
            bFin.setMessage(Component.literal(fin + " h"));
            bJours.setMessage(Component.literal(jours + (jours > 1 ? " jours" : " jour")));
        }

        @Override
        protected void init() {
            x0 = (width - L) / 2;
            y0 = (height - H) / 2;
            int yb = y0 + H - 26;
            if (e == null) {
                var libres = f.getList("libres", net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int i = 0; i < Math.min(5, libres.size()); i++) {
                    CompoundTag l = libres.getCompound(i);
                    addRenderableWidget(Button.builder(Component.literal("Rattacher " + l.getString("nom")), b -> {
                        CompoundTag d = new CompoundTag();
                        d.putString("uuid", l.getString("uuid"));
                        envoyer("rattacher", d);
                    }).bounds(x0 + 10, y0 + 52 + i * 22, L - 20, 20).build());
                }
                addRenderableWidget(Button.builder(Component.literal("Caisse et dépôt"), b -> envoyer("stock", new CompoundTag())).bounds(x0 + 10, yb, 110, 20).build());
                if (f.contains("absent"))
                    addRenderableWidget(Button.builder(Component.literal("Libérer la place"), b -> envoyer("liberer", new CompoundTag())).bounds(x0 + 125, yb, 110, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose()).bounds(x0 + L - 90, yb, 80, 20).build());
                return;
            }
            int g = x0 + 10, dcol = x0 + 190;
            // Commande
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { iCommande = Math.floorMod(iCommande - 1, catalogue.size()); majBoutons(); }).bounds(g, y0 + 72, 16, 20).build());
            bCommande = addRenderableWidget(Button.builder(Component.empty(), b -> { iCommande = (iCommande + 1) % catalogue.size(); majBoutons(); }).bounds(g + 18, y0 + 72, 126, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { iCommande = (iCommande + 1) % catalogue.size(); majBoutons(); }).bounds(g + 146, y0 + 72, 16, 20).build());
            bMode = addRenderableWidget(Button.builder(Component.empty(), b -> { mode = mode.equals("quantite") ? "stock" : "quantite"; majBoutons(); }).bounds(g, y0 + 96, 118, 20).build());
            nombre = champ(g + 122, y0 + 98, 40, String.valueOf(e.getInt("commandeNombre")));
            // Paie
            salaire = champ(g, y0 + 150, 60, String.valueOf(e.getLong("salaireVerse")));
            // Expéditions : durée maximale, s'il a un lit
            addRenderableWidget(Button.builder(Component.literal("-"), b -> { jours = Math.max(1, jours - 1); majBoutons(); }).bounds(dcol + 84, y0 + 96, 14, 20).build());
            bJours = addRenderableWidget(Button.builder(Component.empty(), b -> {})
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Il travaille où il veut, tant qu'il rentre à temps dans son espace de vie (50 blocs autour du bureau). Sans lit, il rentre chaque soir. Avec un lit à 8 blocs du bureau au plus, il peut partir aussi loin qu'il veut et rester dehors jusqu'à ce nombre de jours.")))
                    .bounds(dcol + 99, y0 + 96, 46, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> { jours = Math.min(30, jours + 1); majBoutons(); }).bounds(dcol + 146, y0 + 96, 14, 20).build());
            // Horaires
            addRenderableWidget(Button.builder(Component.literal("-"), b -> { debut = Math.floorMod(debut - 1, 24); majBoutons(); }).bounds(dcol, y0 + 148, 14, 20).build());
            bDebut = addRenderableWidget(Button.builder(Component.empty(), b -> {}).bounds(dcol + 15, y0 + 148, 34, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> { debut = (debut + 1) % 24; majBoutons(); }).bounds(dcol + 50, y0 + 148, 14, 20).build());
            addRenderableWidget(Button.builder(Component.literal("-"), b -> { fin = Math.floorMod(fin - 1, 24); majBoutons(); }).bounds(dcol + 88, y0 + 148, 14, 20).build());
            bFin = addRenderableWidget(Button.builder(Component.empty(), b -> {}).bounds(dcol + 103, y0 + 148, 34, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> { fin = (fin + 1) % 24; majBoutons(); }).bounds(dcol + 138, y0 + 148, 14, 20).build());
            // En bas
            addRenderableWidget(Button.builder(Component.literal("Enregistrer"), b -> {
                CompoundTag d = new CompoundTag();
                d.putString("commande", catalogue.get(iCommande).getString("id"));
                d.putString("commandeMode", mode);
                d.putInt("commandeNombre", entier(nombre, 64));
                d.putInt("joursExpedition", jours);
                d.putInt("debut", debut);
                d.putInt("fin", fin);
                d.putLong("salaire", entier(salaire, (int) e.getLong("salaireVerse")));
                envoyer("enregistrer", d);
            }).bounds(x0 + 10, yb, 84, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Caisse et dépôt"), b -> envoyer("stock", new CompoundTag())).bounds(x0 + 98, yb, 92, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Libérer"), b -> envoyer("liberer", new CompoundTag())).bounds(x0 + 194, yb, 74, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Fermer"), b -> onClose()).bounds(x0 + 272, yb, 78, 20).build());
            majBoutons();
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            super.renderBackground(g, mx, my, pt);
            g.fill(x0, y0, x0 + L, y0 + H, 0xE0181C1A);
            g.renderOutline(x0, y0, L, H, 0xFF8A6A2A);
        }

        void texte(GuiGraphics g, String t, int x, int y, int couleur) { g.drawString(font, t, x, y, couleur, false); }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            int or = 0xF2C14E, gris = 0xAAAAAA, blanc = 0xFFFFFF;
            texte(g, title.getString(), x0 + 10, y0 + 8, or);
            if (e == null) {
                texte(g, f.contains("absent") ? f.getString("absent") + " était rattaché ici, mais il est introuvable." : "Aucun PNJ n'est rattaché à ce bureau.", x0 + 10, y0 + 24, blanc);
                texte(g, f.getList("libres", 10).isEmpty() ? "Tu n'as aucun PNJ libre : embauche-en un, ou libère-le de son bureau." : "Choisis le PNJ qui travaillera ici :", x0 + 10, y0 + 38, gris);
                return;
            }
            int g0 = x0 + 10, dcol = x0 + 190;
            texte(g, e.getString("nom") + " — " + e.getString("metier") + " " + "★".repeat(e.getInt("niveau")) + "   " + e.getString("mode")
                    + (e.getString("action").isEmpty() ? "" : " (" + e.getString("action") + ")"), g0, y0 + 22, blanc);
            texte(g, "Fatigue " + e.getInt("fatigue") + "/100   Faim " + e.getInt("faim") + "/20   Vie " + e.getInt("vie") + "/" + e.getInt("vieMax")
                    + (e.getInt("impayes") > 0 ? "   Paies en retard : " + e.getInt("impayes") : ""), g0, y0 + 34, gris);
            if (!e.getString("besoin").isEmpty()) texte(g, "Il demande : " + e.getString("besoin"), g0, y0 + 46, 0xFF9966);
            texte(g, "COMMANDE", g0, y0 + 60, or);
            texte(g, e.getString("avancement"), g0, y0 + 120, gris);
            texte(g, "PAIE (en bronzes, à chaque paie)", g0, y0 + 138, or);
            texte(g, "Il demande " + e.getLong("salaireDemande"), g0 + 66, y0 + 154, gris);
            texte(g, "SORTIES", dcol, y0 + 60, or);
            boolean lit = !e.getString("lit").isEmpty();
            texte(g, lit ? "Lit : " + e.getString("lit") : "Pas de lit : il rentre le soir", dcol, y0 + 72, lit ? blanc : 0xFF9966);
            texte(g, e.getString("sortie"), dcol, y0 + 84, gris);
            texte(g, "Expédition max", dcol, y0 + 102, gris);
            texte(g, "Espace de vie : 50 blocs", dcol, y0 + 122, gris);
            texte(g, "HORAIRES (heures du jeu)", dcol, y0 + 138, or);
            texte(g, "à", dcol + 74, y0 + 154, gris);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    // --- Le bureau : caisse en haut, dépôt en dessous ----------------------------------------------------

    static class EcranBureau extends AbstractContainerScreen<Bureau.Menu> {
        static final ResourceLocation FOND = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

        EcranBureau(Bureau.Menu m, Inventory inv, Component titre) {
            super(m, inv, titre);
            imageHeight = 197;
            inventoryLabelY = 104;
        }

        @Override
        protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
            int x = leftPos, y = topPos;
            g.blit(FOND, x, y, 0, 0, imageWidth, 35);
            g.blit(FOND, x, y + 35, 0, 4, imageWidth, 12);
            g.blit(FOND, x, y + 47, 0, 17, imageWidth, 54);
            g.blit(FOND, x, y + 101, 0, 126, imageWidth, 96);
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mx, int my) {
            g.drawString(font, title.getString() + " · caisse des salaires", 8, 6, 0x404040, false);
            g.drawString(font, "Dépôt : ce que tes PNJ produisent", 8, 37, 0x404040, false);
            g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0x404040, false);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            renderTooltip(g, mx, my);
        }
    }
}

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
                addRenderableWidget(Button.builder(Component.literal("Travailler ici"), b -> envoyer("travailler"))
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Il travaille autour de l'endroit où il se tient. Le mineur creuse dans la direction où tu regardes.")))
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

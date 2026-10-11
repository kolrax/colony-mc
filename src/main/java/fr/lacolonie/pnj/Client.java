package fr.lacolonie.pnj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Tout ce qui ne tourne que chez le joueur : l'apparence des PNJ et du corbeau, les bulles, et les écrans. */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class Client {
    private Client() {}

    static final ModelLayerLocation COUCHE_CORBEAU = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "corbeau"), "main");
    static final ResourceLocation TEXTURE_CORBEAU = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "textures/entity/corbeau.png");
    static final int OR = 0xF2C14E, GRIS = 0xAAAAAA, BLANC = 0xFFFFFF, ROUGE = 0xFF5555, ORANGE = 0xFF9966, VERT = 0x88DD88;

    @SubscribeEvent
    static void rendus(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ColoniePnj.TRAVAILLEUR.get(), Rendu::new);
        e.registerEntityRenderer(ColoniePnj.CORBEAU.get(), RenduCorbeau::new);
    }

    @SubscribeEvent
    static void couches(EntityRenderersEvent.RegisterLayerDefinitions e) { e.registerLayerDefinition(COUCHE_CORBEAU, ModeleCorbeau::couche); }

    @SubscribeEvent
    static void ecrans(RegisterMenuScreensEvent e) { e.register(ColoniePnj.BUREAU_MENU.get(), EcranBureau::new); }

    static void ouvrirFiche(Reseau.Fiche f) { Minecraft.getInstance().setScreen(new EcranFiche(f.id(), f.infos())); }

    static void ouvrirConversation(Reseau.Conversation f) { Minecraft.getInstance().setScreen(new EcranConversation(f.id(), f.infos())); }

    static void ouvrirPoste(Reseau.Poste f) { Minecraft.getInstance().setScreen(new EcranPoste(f.pos(), f.infos())); }

    // --- La parole : une bulle au-dessus de la tête, et l'historique de la conversation -----------------

    record Bulle(String texte, long fin) {}

    static final Map<Integer, Bulle> BULLES = new ConcurrentHashMap<>();
    static final Map<Integer, List<String>> HISTORIQUE = new HashMap<>();

    static void parole(Reseau.Parole p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long fin = mc.level.getGameTime() + Math.min(400, 80 + p.texte().length() * 2L);
        BULLES.put(p.id(), new Bulle(p.texte(), fin));
        Entity e = mc.level.getEntity(p.id());
        noter(p.id(), (e == null ? "Lui" : prenom(e)) + " : " + p.texte());
    }

    static void noter(int id, String ligne) {
        List<String> h = HISTORIQUE.computeIfAbsent(id, k -> new ArrayList<>());
        h.add(ligne);
        while (h.size() > 40) h.remove(0);
    }

    /** « Philippa (Mineur) » → « Philippa ». */
    static String prenom(Entity e) { return e.getDisplayName().getString().split(" \\(| ·")[0].trim(); }

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

    /**
     * Le modèle de joueur, plus les gestes du répertoire (garde-à-vous, salut, danse…) : le jeu pose d'abord le
     * modèle (marche, regard), puis {@link Poses} y met le geste. Tous les ouvriers partagent ce modèle : on le remet
     * au repos à chaque image.
     */
    static class ModeleOuvrier extends PlayerModel<Travailleur> {
        final boolean fin;
        final Poses.Corps corps = new Poses.Corps();

        ModeleOuvrier(ModelPart p, boolean fin) { super(p, fin); this.fin = fin; }

        @Override
        public void setupAnim(Travailleur e, float marche, float ampleur, float age, float tete, float pitch) {
            for (ModelPart m : new ModelPart[]{head, hat, body, rightArm, leftArm, rightLeg, leftLeg}) m.resetPose();
            super.setupAnim(e, marche, ampleur, age, tete, pitch);
            String g = Client.geste(e);
            if (g.isEmpty()) return;
            Poses.Corps c = corps;
            c.dy = c.rx = c.ry = 0;
            c.epaule = rightArm.y;
            lire(head, c.tete); lire(body, c.corps); lire(rightArm, c.brasD); lire(leftArm, c.brasG); lire(rightLeg, c.jambeD); lire(leftLeg, c.jambeG);
            Poses.appliquer(c, g, age);
            ecrire(c.tete, head); ecrire(c.corps, body); ecrire(c.brasD, rightArm); ecrire(c.brasG, leftArm); ecrire(c.jambeD, rightLeg); ecrire(c.jambeG, leftLeg);
            hat.copyFrom(head);
            jacket.copyFrom(body);
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
            rightPants.copyFrom(rightLeg);
            leftPants.copyFrom(leftLeg);
        }

        static void lire(ModelPart m, Poses.Partie p) { p.x = m.x; p.y = m.y; p.z = m.z; p.xRot = m.xRot; p.yRot = m.yRot; p.zRot = m.zRot; }

        static void ecrire(Poses.Partie p, ModelPart m) { m.x = p.x; m.y = p.y; m.z = p.z; m.xRot = p.xRot; m.yRot = p.yRot; m.zRot = p.zRot; }
    }

    /** Son geste, sans les options (« danser+tourne » → « danser ») ; vide s'il n'en fait pas ou si ce client ne le connaît pas. */
    static String geste(Travailleur e) {
        String g = e.geste();
        int i = g.indexOf('+');
        String base = i < 0 ? g : g.substring(0, i);
        return Poses.connu(base) ? base : "";
    }

    static class Rendu extends HumanoidMobRenderer<Travailleur, PlayerModel<Travailleur>> {
        final PlayerModel<Travailleur> large, fin;
        final Poses.Corps racine = new Poses.Corps();

        Rendu(EntityRendererProvider.Context c) {
            super(c, new ModeleOuvrier(c.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
            large = model;
            fin = new ModeleOuvrier(c.bakeLayer(ModelLayers.PLAYER_SLIM), true);
            addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(c.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(c.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), c.getModelManager()));
        }

        @Override
        public void render(Travailleur e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int lumiere) {
            model = skin(e).model() == PlayerSkin.Model.SLIM ? fin : large;
            super.render(e, yaw, pt, ps, buf, lumiere);
            bulle(e, ps, buf, lumiere);
        }

        /** Il nage allongé, comme un joueur (même rotation que pour les joueurs). */
        @Override
        protected void setupRotations(Travailleur e, PoseStack ps, float bob, float yBodyRot, float pt, float scale) {
            super.setupRotations(e, ps, bob, yBodyRot, pt, scale);
            // Le corps entier : assis, à genoux, allongé, en pompes, qui tourne sur lui-même.
            String g = geste(e);
            if (!g.isEmpty()) {
                Poses.Corps c = racine;
                c.dy = c.rx = c.ry = 0;
                Poses.appliquer(c, g, e.tickCount + pt);
                if (e.geste().contains("+tourne")) c.ry += Poses.tourne(e.tickCount + pt);
                if (c.dy != 0) ps.translate(0, c.dy, 0);
                if (c.ry != 0) ps.mulPose(Axis.YP.rotationDegrees(c.ry));
                if (c.rx != 0) ps.mulPose(Axis.XP.rotationDegrees(c.rx));
            }
            float f = e.getSwimAmount(pt);
            if (f > 0) {
                float cible = e.isInWater() ? -90.0f - e.getXRot() : -90.0f;
                ps.mulPose(Axis.XP.rotationDegrees(Mth.lerp(f, 0.0f, cible)));
                if (e.isVisuallySwimming()) ps.translate(0.0f, -1.0f, 0.3f);
            }
        }

        /** Ce qu'il vient de dire, en bulle au-dessus de son nom. */
        void bulle(Travailleur e, PoseStack ps, MultiBufferSource buf, int lumiere) {
            Bulle b = BULLES.get(e.getId());
            if (b == null) return;
            if (b.fin() < e.level().getGameTime()) { BULLES.remove(e.getId()); return; }
            if (entityRenderDispatcher.distanceToSqr(e) > 24 * 24) return;
            Font font = getFont();
            List<FormattedCharSequence> lignes = font.split(Component.literal(b.texte()), 170);
            if (lignes.size() > 6) lignes = lignes.subList(lignes.size() - 6, lignes.size());
            ps.pushPose();
            ps.translate(0, e.getBbHeight() + 0.5, 0);
            ps.mulPose(entityRenderDispatcher.cameraOrientation());
            ps.scale(0.025f, -0.025f, 0.025f);
            Matrix4f m = ps.last().pose();
            int fond = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25f) * 255) << 24;
            // Comme les noms au-dessus des têtes : d'abord le fond (et un texte pâle, vu à travers les murs), puis le texte
            // seul. Fond et lettres dessinés ensemble à la même profondeur se masquaient au hasard (« res, chef ! »).
            // En pleine lumière : la bulle se lit aussi la nuit.
            for (int i = 0; i < lignes.size(); i++) {
                FormattedCharSequence l = lignes.get(i);
                float x = -font.width(l) / 2f, y = -12 - (lignes.size() - i) * 10;
                font.drawInBatch(l, x, y, 0x20FFFFFF, false, m, buf, Font.DisplayMode.SEE_THROUGH, fond, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                font.drawInBatch(l, x, y, 0xFFFFFFFF, false, m, buf, Font.DisplayMode.NORMAL, 0, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            }
            ps.popPose();
        }

        @Override
        public ResourceLocation getTextureLocation(Travailleur e) { return skin(e).texture(); }

        @Override
        protected void scale(Travailleur e, PoseStack ps, float pt) { ps.scale(0.9375f, 0.9375f, 0.9375f); }
    }

    // --- Le corbeau ----------------------------------------------------------------------------------------

    static class ModeleCorbeau extends HierarchicalModel<Corbeau> {
        final ModelPart racine, tete, queue, aileGauche, aileDroite, patteGauche, patteDroite, lettre;

        ModeleCorbeau(ModelPart r) {
            racine = r;
            tete = r.getChild("tete");
            queue = r.getChild("queue");
            aileGauche = r.getChild("aile_gauche");
            aileDroite = r.getChild("aile_droite");
            patteGauche = r.getChild("patte_gauche");
            patteDroite = r.getChild("patte_droite");
            lettre = r.getChild("tete").getChild("lettre");
        }

        /** Un corbeau d'un demi-bloc : corps, tête et bec, queue, deux ailes, deux pattes, et la lettre au bec. Texture 64 x 32. */
        static LayerDefinition couche() {
            MeshDefinition m = new MeshDefinition();
            PartDefinition r = m.getRoot();
            r.addOrReplaceChild("corps", CubeListBuilder.create().texOffs(0, 0).addBox(-2f, -2f, -4f, 4, 4, 8), PartPose.offset(0, 19, 0));
            PartDefinition tete = r.addOrReplaceChild("tete", CubeListBuilder.create().texOffs(0, 12).addBox(-1.5f, -3f, -3f, 3, 3, 3)
                    .texOffs(12, 12).addBox(-0.5f, -2f, -5f, 1, 1, 2), PartPose.offset(0, 18.5f, -3.5f));
            tete.addOrReplaceChild("lettre", CubeListBuilder.create().texOffs(28, 12).addBox(-1.5f, -1f, -0.5f, 3, 2, 1), PartPose.offset(0, -1, -5.5f));
            r.addOrReplaceChild("queue", CubeListBuilder.create().texOffs(0, 18).addBox(-1.5f, 0f, 0f, 3, 1, 5), PartPose.offsetAndRotation(0, 18, 3.5f, -0.25f, 0, 0));
            r.addOrReplaceChild("aile_gauche", CubeListBuilder.create().texOffs(24, 0).addBox(0f, 0f, -3f, 1, 3, 7), PartPose.offset(2, 17.5f, 0));
            r.addOrReplaceChild("aile_droite", CubeListBuilder.create().texOffs(24, 0).mirror().addBox(-1f, 0f, -3f, 1, 3, 7), PartPose.offset(-2, 17.5f, 0));
            r.addOrReplaceChild("patte_gauche", CubeListBuilder.create().texOffs(24, 12).addBox(-0.5f, 0f, -0.5f, 1, 3, 1), PartPose.offset(1, 21, 0.5f));
            r.addOrReplaceChild("patte_droite", CubeListBuilder.create().texOffs(24, 12).addBox(-0.5f, 0f, -0.5f, 1, 3, 1), PartPose.offset(-1, 21, 0.5f));
            return LayerDefinition.create(m, 64, 32);
        }

        @Override
        public ModelPart root() { return racine; }

        @Override
        public void setupAnim(Corbeau e, float limbSwing, float limbSwingAmount, float age, float netHeadYaw, float headPitch) {
            lettre.visible = e.porteLettre();
            if (e.pose()) {
                // Posé : ailes repliées, il hoche la tête et remue la queue.
                aileGauche.zRot = -0.05f;
                aileDroite.zRot = 0.05f;
                patteGauche.xRot = patteDroite.xRot = 0;
                tete.xRot = Mth.sin(age * 0.35f) * 0.25f;
                tete.yRot = Mth.sin(age * 0.11f) * 0.4f;
                queue.xRot = -0.25f + Mth.sin(age * 0.5f) * 0.08f;
            } else {
                // En vol : il bat des ailes, pattes repliées.
                float battement = Mth.sin(age * 1.3f);
                aileGauche.zRot = -0.35f - battement * 1.1f;
                aileDroite.zRot = 0.35f + battement * 1.1f;
                patteGauche.xRot = patteDroite.xRot = 1.3f;
                tete.xRot = 0;
                tete.yRot = 0;
                queue.xRot = -0.1f;
            }
        }
    }

    static class RenduCorbeau extends EntityRenderer<Corbeau> {
        final ModeleCorbeau modele;

        RenduCorbeau(EntityRendererProvider.Context c) {
            super(c);
            modele = new ModeleCorbeau(c.bakeLayer(COUCHE_CORBEAU));
            shadowRadius = 0.15f;
        }

        @Override
        public void render(Corbeau e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int lumiere) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(180.0f - Mth.rotLerp(pt, e.yRotO, e.getYRot())));
            ps.mulPose(Axis.XP.rotationDegrees(Mth.lerp(pt, e.xRotO, e.getXRot())));
            ps.scale(-1.0f, -1.0f, 1.0f);
            ps.translate(0.0f, -1.501f, 0.0f);
            modele.setupAnim(e, 0, 0, e.tickCount + pt, 0, 0);
            modele.renderToBuffer(ps, buf.getBuffer(modele.renderType(TEXTURE_CORBEAU)), lumiere, OverlayTexture.NO_OVERLAY);
            ps.popPose();
            super.render(e, yaw, pt, ps, buf, lumiere);
        }

        @Override
        public ResourceLocation getTextureLocation(Corbeau e) { return TEXTURE_CORBEAU; }
    }

    // --- Outils d'écran ------------------------------------------------------------------------------------

    abstract static class Cadre extends Screen {
        int x0, y0, largeur, hauteur;

        Cadre(Component titre, int largeur, int hauteur) {
            super(titre);
            this.largeur = largeur;
            this.hauteur = hauteur;
        }

        @Override
        protected void init() {
            x0 = (width - largeur) / 2;
            y0 = (height - hauteur) / 2;
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            super.renderBackground(g, mx, my, pt);
            g.fill(x0, y0, x0 + largeur, y0 + hauteur, 0xE0181C1A);
            g.renderOutline(x0, y0, largeur, hauteur, 0xFF8A6A2A);
        }

        void texte(GuiGraphics g, String t, int x, int y, int couleur) { g.drawString(font, t, x, y, couleur, false); }

        /** Un texte coupé en lignes, à partir de y ; renvoie le y suivant. */
        int paragraphe(GuiGraphics g, String t, int x, int y, int w, int couleur, int maxLignes) {
            int n = 0;
            for (String p : t.split("\n")) {
                List<FormattedCharSequence> ls = p.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(Component.literal(p), w);
                for (FormattedCharSequence l : ls) {
                    if (n++ >= maxLignes) return y;
                    g.drawString(font, l, x, y, couleur, false);
                    y += 10;
                }
            }
            return y;
        }

        Button bouton(String t, int x, int y, int w, Button.OnPress action) { return addRenderableWidget(Button.builder(Component.literal(t), action).bounds(x, y, w, 20).build()); }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    // --- L'entretien d'embauche ------------------------------------------------------------------------------

    static class EcranFiche extends Cadre {
        final int id;
        final CompoundTag i;
        final List<FormattedCharSequence> lignes = new ArrayList<>();

        EcranFiche(int id, CompoundTag infos) {
            super(Component.literal(infos.getString("nom")), 280, 120);
            this.id = id;
            this.i = infos;
        }

        @Override
        protected void init() {
            lignes.clear();
            List<Component> t = new ArrayList<>();
            t.add(Component.literal(i.getString("nom") + " — " + i.getString("metier") + "  " + "★".repeat(i.getInt("niveau")) + "☆".repeat(5 - i.getInt("niveau"))).withStyle(s -> s.withBold(true).withColor(OR)));
            if (!i.getString("description").isEmpty()) t.add(Component.literal(i.getString("description")).withStyle(s -> s.withColor(0xBBBBBB)));
            t.add(Component.literal(i.getString("attitude")).withStyle(s -> s.withItalic(true).withColor(0x9FB4C7)));
            t.add(Component.empty());
            t.add(Component.literal("Il demande : " + i.getString("salaire") + " " + i.getString("periode") + "."));
            t.add(Component.literal("Prime d'embauche : " + i.getString("prime") + ", prise dans ton inventaire."));
            t.add(Component.literal("Il viendra chercher sa paie dans la caisse de ton poste de travail.").withStyle(s -> s.withColor(0xBBBBBB)));
            for (Component c : t) lignes.addAll(c.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(c, largeur - 20));
            hauteur = 20 + lignes.size() * 11 + 34;
            super.init();
            int yb = y0 + hauteur - 30;
            bouton("Embaucher", x0 + 10, yb, 125, b -> { PacketDistributor.sendToServer(new Reseau.Commande(id, "embaucher")); onClose(); });
            bouton("Pas aujourd'hui", x0 + largeur - 135, yb, 125, b -> onClose());
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            int y = y0 + 10;
            for (FormattedCharSequence l : lignes) { g.drawString(font, l, x0 + 10, y, BLANC, false); y += 11; }
        }
    }

    // --- La conversation ------------------------------------------------------------------------------------

    static class EcranConversation extends Cadre {
        final int id;
        final CompoundTag i;
        EditBox boite;

        EcranConversation(int id, CompoundTag infos) {
            super(Component.literal(infos.getString("nom")), 330, 240);
            this.id = id;
            this.i = infos;
        }

        @Override
        protected void init() {
            super.init();
            boite = new EditBox(font, x0 + 10, y0 + hauteur - 38, largeur - 20, 18, Component.literal("Parler"));
            boite.setMaxLength(256);
            boite.setHint(Component.literal("Parle à " + i.getString("nom") + "… puis Entrée").withStyle(s -> s.withColor(0x777777)));
            addRenderableWidget(boite);
            setInitialFocus(boite);
        }

        void envoyer() {
            String t = boite.getValue().strip();
            if (t.isEmpty()) return;
            noter(id, "Toi : " + t);
            PacketDistributor.sendToServer(new Reseau.Parler(id, t));
            boite.setValue("");
        }

        @Override
        public boolean keyPressed(int touche, int scan, int mods) {
            if ((touche == GLFW.GLFW_KEY_ENTER || touche == GLFW.GLFW_KEY_KP_ENTER) && boite.isFocused()) { envoyer(); return true; }
            return super.keyPressed(touche, scan, mods);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            texte(g, i.getString("nom") + " — " + i.getString("metier") + " " + "★".repeat(i.getInt("niveau")), x0 + 10, y0 + 8, OR);
            texte(g, i.getString("statut") + " · " + i.getString("ou") + "   Faim " + i.getInt("faim") + "/20   Vie " + i.getInt("vie") + "/" + i.getInt("vieMax"), x0 + 10, y0 + 20, GRIS);
            // L'échange, du plus ancien (en haut) au plus récent (en bas).
            List<FormattedCharSequence> lignes = new ArrayList<>();
            List<Integer> couleurs = new ArrayList<>();
            for (String l : HISTORIQUE.getOrDefault(id, List.of())) {
                boolean moi = l.startsWith("Toi : ");
                for (FormattedCharSequence s : font.split(Component.literal(l), largeur - 24)) { lignes.add(s); couleurs.add(moi ? 0x9FB4C7 : BLANC); }
            }
            int bas = y0 + hauteur - 46, place = (bas - (y0 + 36)) / 10, debut = Math.max(0, lignes.size() - place);
            int y = bas - (lignes.size() - debut) * 10;
            for (int k = debut; k < lignes.size(); k++) { g.drawString(font, lignes.get(k), x0 + 12, y, couleurs.get(k), false); y += 10; }
            if (lignes.isEmpty()) texte(g, "Écris-lui ce que tu veux : « suis-moi », « ramène 10 fers », « où en es-tu ? »… (« aide » : la liste)", x0 + 12, y0 + 40, 0x777777);
            texte(g, "Entrée : envoyer   Échap : fermer", x0 + 10, y0 + hauteur - 14, 0x666666);
        }
    }

    // --- Le poste de travail ------------------------------------------------------------------------------------

    static class EcranPoste extends Cadre {
        static String ongletMemo = "ouvriers", choisiMemo = "";
        static long lettreMemo = -1;
        static int defilement;

        final BlockPos pos;
        final CompoundTag f;
        final List<CompoundTag> ouvriers = new ArrayList<>(), lettres = new ArrayList<>();
        CompoundTag choisi;
        CompoundTag lue;
        // Réglages de l'ouvrier choisi
        int debut, fin, jours;
        EditBox mission, salaire;
        Button bDebut, bFin, bJours;
        // Écriture d'une lettre
        boolean ecrire;
        int destinataire;
        MultiLineEditBox corps;

        EcranPoste(BlockPos pos, CompoundTag infos) {
            super(Component.literal(infos.getString("titre")), 420, 252);
            this.pos = pos;
            this.f = infos;
            for (Tag x : infos.getList("ouvriers", Tag.TAG_COMPOUND)) ouvriers.add((CompoundTag) x);
            for (Tag x : infos.getList("lettres", Tag.TAG_COMPOUND)) lettres.add((CompoundTag) x);
            choisi = ouvriers.stream().filter(o -> o.getString("uuid").equals(choisiMemo)).findFirst().orElse(ouvriers.isEmpty() ? null : ouvriers.get(0));
            lue = lettres.stream().filter(l -> l.getLong("id") == lettreMemo).findFirst().orElse(null);
            if (choisi != null) {
                debut = choisi.getInt("debut");
                fin = choisi.getInt("fin");
                jours = Math.max(1, choisi.getInt("joursExpedition"));
            }
        }

        void envoyer(String action, CompoundTag d) { PacketDistributor.sendToServer(new Reseau.ActionBureau(pos, action, d)); }

        void onglet(String o) { ongletMemo = o; ecrire = false; rebuildWidgets(); }

        List<CompoundTag> vivants() { return ouvriers.stream().filter(o -> !o.getBoolean("absent")).toList(); }

        @Override
        protected void init() {
            super.init();
            if (!f.getBoolean("poste")) {
                if (!f.contains("ailleurs")) bouton("Faire de ce bureau mon poste de travail", x0 + 10, y0 + 90, 260, b -> envoyer("devenir_poste", new CompoundTag()));
                bouton("Caisse et dépôt", x0 + 10, y0 + hauteur - 28, 120, b -> envoyer("stock", new CompoundTag()));
                bouton("Fermer", x0 + largeur - 90, y0 + hauteur - 28, 80, b -> onClose());
                return;
            }
            long nonLues = f.getLong("nonLues");
            bouton("Ouvriers", x0 + 150, y0 + 4, 70, b -> onglet("ouvriers")).active = !ongletMemo.equals("ouvriers");
            bouton("Lettres" + (nonLues > 0 ? " (" + nonLues + ")" : ""), x0 + 222, y0 + 4, 74, b -> onglet("lettres")).active = !ongletMemo.equals("lettres");
            bouton("Caisse et dépôt", x0 + 298, y0 + 4, 94, b -> envoyer("stock", new CompoundTag()));
            bouton("✕", x0 + largeur - 22, y0 + 4, 18, b -> onClose());
            if (ongletMemo.equals("lettres")) initLettres(); else initOuvriers();
        }

        // --- Onglet Ouvriers

        void initOuvriers() {
            for (int k = 0; k < Math.min(10, ouvriers.size()); k++) {
                CompoundTag o = ouvriers.get(k);
                String st = o.getString("statut");
                int couleur = o.getBoolean("absent") || st.equals("En grève") || st.equals("À terre") ? ROUGE : st.equals("En expédition") ? OR
                        : st.equals("Au travail") ? VERT : GRIS;
                Button b = addRenderableWidget(Button.builder(Component.literal(o.getString("nom") + " " + "★".repeat(o.getInt("niveau"))).withStyle(s -> s.withColor(couleur)), x -> {
                    choisiMemo = o.getString("uuid");
                    choisi = o;
                    debut = o.getInt("debut");
                    fin = o.getInt("fin");
                    jours = Math.max(1, o.getInt("joursExpedition"));
                    rebuildWidgets();
                }).bounds(x0 + 8, y0 + 30 + k * 22, 120, 20).tooltip(Tooltip.create(Component.literal(o.getString("metier") + " · " + st))).build());
                b.active = choisi == null || !choisi.getString("uuid").equals(o.getString("uuid"));
            }
            if (choisi == null) return;
            int x = x0 + 136;
            if (choisi.getBoolean("absent")) {
                bouton("Retirer de la liste", x, y0 + 80, 140, b -> {
                    CompoundTag d = new CompoundTag();
                    d.putString("uuid", choisi.getString("uuid"));
                    choisiMemo = "";
                    envoyer("retirer", d);
                });
                return;
            }
            mission = new EditBox(font, x, y0 + 140, 222, 18, Component.literal("Mission"));
            mission.setMaxLength(256);
            mission.setHint(Component.literal("Ex. : ramène 10 fers et 1 diamant").withStyle(s -> s.withColor(0x777777)));
            addRenderableWidget(mission);
            bouton("Envoyer", x + 226, y0 + 139, 50, b -> {
                if (mission.getValue().isBlank()) return;
                CompoundTag d = new CompoundTag();
                d.putString("uuid", choisi.getString("uuid"));
                d.putString("texte", mission.getValue());
                envoyer("ecrire", d);
            }).setTooltip(Tooltip.create(Component.literal("Ta mission part en lettre : s'il est près du poste, il passe la prendre ; s'il est loin, un corbeau la lui porte. Il te répond ce qu'il a compris.")));
            // Horaires
            bouton("-", x, y0 + 178, 14, b -> { debut = Math.floorMod(debut - 1, 24); majBoutons(); });
            bDebut = bouton("", x + 15, y0 + 178, 34, b -> {});
            bouton("+", x + 50, y0 + 178, 14, b -> { debut = (debut + 1) % 24; majBoutons(); });
            bouton("-", x + 82, y0 + 178, 14, b -> { fin = Math.floorMod(fin - 1, 24); majBoutons(); });
            bFin = bouton("", x + 97, y0 + 178, 34, b -> {});
            bouton("+", x + 132, y0 + 178, 14, b -> { fin = (fin + 1) % 24; majBoutons(); });
            // Expéditions
            bouton("-", x + 166, y0 + 178, 14, b -> { jours = Math.max(1, jours - 1); majBoutons(); });
            bJours = bouton("", x + 181, y0 + 178, 46, b -> {});
            bJours.setTooltip(Tooltip.create(Component.literal("Il va où il faut pour sa mission. Pour une sortie à la journée, il rentre le soir. Plus loin, il part de lui-même en expédition : il campe, se fatigue plus vite, et rentre au plus tard au bout de ce nombre de jours.")));
            bouton("+", x + 228, y0 + 178, 14, b -> { jours = Math.min(30, jours + 1); majBoutons(); });
            // Paie
            salaire = new EditBox(font, x, y0 + 214, 56, 16, Component.literal("Paie"));
            salaire.setMaxLength(9);
            salaire.setFilter(v -> v.matches("[0-9]*"));
            salaire.setValue(String.valueOf(choisi.getLong("salaireVerse")));
            addRenderableWidget(salaire);
            bouton("Enregistrer", x + 186, y0 + 212, 90, b -> {
                CompoundTag d = new CompoundTag();
                d.putString("uuid", choisi.getString("uuid"));
                d.putInt("debut", debut);
                d.putInt("fin", fin);
                d.putInt("joursExpedition", jours);
                try { d.putLong("salaire", Long.parseLong(salaire.getValue().trim())); } catch (NumberFormatException e) { d.putLong("salaire", choisi.getLong("salaireVerse")); }
                envoyer("enregistrer", d);
            });
            majBoutons();
        }

        void majBoutons() {
            if (bDebut == null) return;
            bDebut.setMessage(Component.literal(debut + " h"));
            bFin.setMessage(Component.literal(fin + " h"));
            bJours.setMessage(Component.literal(jours + (jours > 1 ? " jours" : " jour")));
        }

        // --- Onglet Lettres

        void initLettres() {
            if (ecrire) {
                List<CompoundTag> v = vivants();
                if (v.isEmpty()) { ecrire = false; } else {
                    destinataire = Math.floorMod(destinataire, v.size());
                    bouton("À : " + v.get(destinataire).getString("nom") + " ▸", x0 + 164, y0 + 30, 140, b -> { destinataire++; rebuildWidgets(); });
                    corps = new MultiLineEditBox(font, x0 + 164, y0 + 54, 246, 160, Component.literal("Ta lettre… (ordres, missions, ou demander des nouvelles)"), Component.literal("Lettre"));
                    corps.setCharacterLimit(1000);
                    addRenderableWidget(corps);
                    bouton("Envoyer", x0 + 164, y0 + 222, 80, b -> {
                        if (corps.getValue().isBlank()) return;
                        CompoundTag d = new CompoundTag();
                        d.putString("uuid", v.get(destinataire).getString("uuid"));
                        d.putString("texte", corps.getValue());
                        ecrire = false;
                        envoyer("ecrire", d);
                    });
                    bouton("Annuler", x0 + 248, y0 + 222, 80, b -> { ecrire = false; rebuildWidgets(); });
                }
            }
            int visibles = 9;
            defilement = Mth.clamp(defilement, 0, Math.max(0, lettres.size() - visibles));
            for (int k = 0; k < Math.min(visibles, lettres.size() - defilement); k++) {
                CompoundTag l = lettres.get(defilement + k);
                String t = (l.getBoolean("lue") ? "" : "● ") + l.getString("de") + " : " + l.getString("objet");
                if (font.width(t) > 140) t = font.plainSubstrByWidth(t, 134) + "…";
                Button b = addRenderableWidget(Button.builder(Component.literal(t).withStyle(s -> s.withColor(l.getBoolean("lue") ? GRIS : BLANC)), x -> {
                    lettreMemo = l.getLong("id");
                    lue = l;
                    ecrire = false;
                    if (!l.getBoolean("lue")) {
                        l.putBoolean("lue", true);
                        CompoundTag d = new CompoundTag();
                        d.putLong("id", l.getLong("id"));
                        envoyer("lue", d);
                    } else rebuildWidgets();
                }).bounds(x0 + 8, y0 + 30 + k * 21, 150, 20).build());
                b.active = lue == null || lue.getLong("id") != l.getLong("id") || ecrire;
            }
            bouton("▲", x0 + 8, y0 + 222, 20, b -> { defilement--; rebuildWidgets(); }).active = defilement > 0;
            bouton("▼", x0 + 30, y0 + 222, 20, b -> { defilement++; rebuildWidgets(); }).active = defilement + visibles < lettres.size();
            if (!ecrire) {
                bouton("Écrire à…", x0 + 56, y0 + 222, 102, b -> { ecrire = true; rebuildWidgets(); }).active = !vivants().isEmpty();
                if (lue != null) {
                    if (lue.hasUUID("deUuid")) bouton("Répondre", x0 + 164, y0 + 222, 80, b -> {
                        List<CompoundTag> v = vivants();
                        for (int k = 0; k < v.size(); k++) if (v.get(k).getString("uuid").equals(lue.getUUID("deUuid").toString())) destinataire = k;
                        ecrire = true;
                        rebuildWidgets();
                    }).active = vivants().stream().anyMatch(o -> o.getString("uuid").equals(lue.getUUID("deUuid").toString()));
                    bouton("Jeter", x0 + 248, y0 + 222, 70, b -> {
                        CompoundTag d = new CompoundTag();
                        d.putLong("id", lue.getLong("id"));
                        lettreMemo = -1;
                        envoyer("supprimer", d);
                    });
                }
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            if (ongletMemo.equals("lettres") && mx < x0 + 160) { defilement -= (int) Math.signum(dy); rebuildWidgets(); return true; }
            return super.mouseScrolled(mx, my, dx, dy);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            texte(g, title.getString(), x0 + 8, y0 + 10, OR);
            if (!f.getBoolean("poste")) {
                if (f.contains("ailleurs")) {
                    paragraphe(g, "Ton poste de travail est en " + f.getString("ailleurs") + ". Ce bureau n'est plus qu'un coffre : vide sa caisse et son dépôt, puis casse-le.", x0 + 10, y0 + 34, largeur - 20, BLANC, 6);
                } else {
                    paragraphe(g, "Depuis la 0.5.0, chaque joueur a un seul poste de travail, commun à tous ses ouvriers : ils s'y rattachent tous, y touchent leur paie, y déposent leur travail et leurs lettres.", x0 + 10, y0 + 34, largeur - 20, BLANC, 6);
                    paragraphe(g, "Tes autres bureaux ne seront plus que des coffres.", x0 + 10, y0 + 70, largeur - 20, GRIS, 2);
                }
                return;
            }
            if (ongletMemo.equals("lettres")) renderLettres(g); else renderOuvriers(g);
        }

        void renderOuvriers(GuiGraphics g) {
            if (ouvriers.isEmpty()) {
                paragraphe(g, "Tu n'as encore aucun ouvrier. Les chercheurs d'emploi passent près de toi : clic droit sur l'un d'eux pour l'entretien d'embauche.", x0 + 136, y0 + 34, 274, GRIS, 6);
                return;
            }
            if (choisi == null) return;
            int x = x0 + 136, w = 276;
            texte(g, choisi.getString("nom") + " — " + choisi.getString("metier") + " " + "★".repeat(choisi.getInt("niveau")), x, y0 + 30, OR);
            String st = choisi.getString("statut");
            if (choisi.getBoolean("absent")) {
                texte(g, st, x, y0 + 44, ROUGE);
                paragraphe(g, choisi.getString("detail"), x, y0 + 58, w, GRIS, 3);
                return;
            }
            int cst = st.equals("En grève") || st.equals("À terre") ? ROUGE : st.equals("En expédition") ? OR : st.equals("Au travail") ? VERT : BLANC;
            texte(g, st, x, y0 + 42, cst);
            texte(g, " · " + choisi.getString("distance") + (choisi.getString("sortie").isEmpty() ? "" : ", " + choisi.getString("sortie")), x + font.width(st), y0 + 42, GRIS);
            texte(g, "Fatigue " + choisi.getInt("fatigue") + "/100   Faim " + choisi.getInt("faim") + "/20   Vie " + choisi.getInt("vie") + "/" + choisi.getInt("vieMax")
                    + (choisi.getInt("impayes") > 0 ? "   Paies en retard : " + choisi.getInt("impayes") : ""), x, y0 + 54, GRIS);
            int y = y0 + 66;
            if (!choisi.getString("besoin").isEmpty()) y = paragraphe(g, "Il demande : " + choisi.getString("besoin"), x, y, w, ORANGE, 2);
            texte(g, "MISSIONS", x, y0 + 90, OR);
            ListTag ms = choisi.getList("missions", Tag.TAG_STRING);
            if (ms.isEmpty()) texte(g, "Aucune : écris-lui ce que tu veux, ci-dessous.", x, y0 + 102, GRIS);
            for (int k = 0; k < Math.min(3, ms.size()); k++) texte(g, (k + 1) + ". " + ms.getString(k), x, y0 + 102 + k * 11, BLANC);
            if (ms.size() > 3) texte(g, "… et " + (ms.size() - 3) + " autre(s)", x + 200, y0 + 90, GRIS);
            if (choisi.getInt("aLire") > 0) texte(g, choisi.getInt("aLire") + " lettre(s) de toi l'attendent au poste.", x, y0 + 130, 0x9FB4C7);
            texte(g, "HORAIRES", x, y0 + 166, OR);
            texte(g, "à", x + 70, y0 + 184, GRIS);
            texte(g, "EXPÉDITION MAX", x + 166, y0 + 166, OR);
            texte(g, "PAIE (en bronzes)", x, y0 + 202, OR);
            texte(g, "Il demande " + choisi.getLong("salaireDemande"), x + 60, y0 + 218, GRIS);
            texte(g, choisi.getString("paie"), x + 96, y0 + 202, GRIS);
        }

        void renderLettres(GuiGraphics g) {
            if (lettres.isEmpty() && !ecrire) texte(g, "Aucune lettre pour l'instant.", x0 + 10, y0 + 34, GRIS);
            if (ecrire) return;
            int x = x0 + 164, w = 248;
            if (lue == null) {
                paragraphe(g, "Tes ouvriers t'écrivent ici : leurs besoins, leurs missions, leurs expéditions. Ceux qui sont près du poste apportent leur lettre à pied ; les autres envoient un corbeau.", x, y0 + 34, w, GRIS, 8);
                return;
            }
            texte(g, lue.getString("objet"), x, y0 + 30, OR);
            texte(g, "De " + lue.getString("de") + " · " + lue.getString("date"), x, y0 + 42, GRIS);
            paragraphe(g, lue.getString("texte"), x, y0 + 58, w, BLANC, 16);
        }
    }

    // --- Le poste : caisse en haut, dépôt en dessous ----------------------------------------------------

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
            g.drawString(font, "Dépôt : ce que tes ouvriers produisent", 8, 37, 0x404040, false);
            g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0x404040, false);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            renderTooltip(g, mx, my);
        }
    }
}

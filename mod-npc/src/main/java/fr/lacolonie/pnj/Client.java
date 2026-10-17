package fr.lacolonie.pnj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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

/** Tout ce qui ne tourne que chez le joueur : l'apparence des PNJ, les bulles, et les écrans. */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class Client {
    private Client() {}

    static final int OR = 0xF2C14E, GRIS = 0xAAAAAA, BLANC = 0xFFFFFF, ROUGE = 0xFF5555, ORANGE = 0xFF9966, VERT = 0x88DD88;

    @SubscribeEvent
    static void rendus(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ColoniePnj.TRAVAILLEUR.get(), Rendu::new);
    }

    @SubscribeEvent
    static void ecrans(RegisterMenuScreensEvent e) { e.register(ColoniePnj.BUREAU_MENU.get(), EcranBureau::new); }

    static void ouvrirFiche(Reseau.Fiche f) { Minecraft.getInstance().setScreen(new EcranFiche(f.id(), f.infos())); }

    static void ouvrirConversation(Reseau.Conversation f) { Minecraft.getInstance().setScreen(new EcranConversation(f.id(), f.infos())); }

    static void ouvrirPoste(Reseau.Poste f) {
        // Une nouvelle ouverture repart du haut de la liste ; une mise à jour (après un bouton) reste où l'on était.
        if (!(Minecraft.getInstance().screen instanceof EcranPoste e && e.pos.equals(f.pos()))) EcranPoste.defilement = 0;
        Minecraft.getInstance().setScreen(new EcranPoste(f.pos(), f.infos()));
    }

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

    // --- Apparence : un modèle de joueur avec le skin d'un pseudo Minecraft, ou la tenue de son métier ---------

    static final Map<String, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    static final Set<String> DEMANDES = ConcurrentHashMap.newKeySet();
    /** Les tenues des métiers (chemin de texture → skin), modèle large. */
    static final Map<String, PlayerSkin> TENUES = new ConcurrentHashMap<>();

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
        return TENUES.computeIfAbsent(Tenues.chemin(e.tenue(), e.getUUID()), c -> new PlayerSkin(
                ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, c), null, null, null, PlayerSkin.Model.WIDE, false));
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

    /** Les icônes au-dessus des têtes (chemin → texture), et la distance au-delà de laquelle on ne les voit plus. */
    static final Map<String, ResourceLocation> ICONES_ETAT = icones("etat", Insignes.ETATS), ICONES_METIER = icones("metier", Tenues.METIERS);

    static Map<String, ResourceLocation> icones(String dossier, List<String> noms) {
        Map<String, ResourceLocation> m = new HashMap<>();
        for (String n : noms) m.put(n, ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, Insignes.icone(dossier, n)));
        return Map.copyOf(m);
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

        /**
         * Au-dessus de sa tête, à la place du nom du jeu (lot 8) : l'icône de son métier et son prénom, dessous en petit
         * son rôle (« Mineur · KOLRAX_MC »), au-dessus son icône d'état s'il en a une. Le prénom se lit jusqu'à
         * {@link Insignes#PORTEE_NOM} blocs, le reste jusqu'à {@link Insignes#PORTEE_DETAILS} ; rien à travers un mur
         * (une vitre ou des feuilles n'arrêtent pas la vue). Comme les noms du jeu : fond et texte pâle vus à
         * travers, puis le texte seul ; jamais de fond en mode NORMAL.
         */
        @Override
        protected void renderNameTag(Travailleur e, Component nom, PoseStack ps, MultiBufferSource buf, int lumiere, float pt) {
            double d2 = entityRenderDispatcher.distanceToSqr(e);
            if (e.isInvisible() || d2 > Insignes.PORTEE_NOM * Insignes.PORTEE_NOM || !enVue(e)) return;
            Vec3 v = e.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, e.getViewYRot(pt));
            if (v == null) return;
            Font font = getFont();
            Component nomJeu = e.getCustomName(); // le même objet tant que le serveur ne le change pas
            if (nomJeu != e.nomLu) {
                e.nomLu = nomJeu;
                e.prenomLu = nomJeu == null ? "" : Insignes.prenom(nomJeu.getString());
                e.largeurPrenom = font.width(e.prenomLu);
            }
            boolean details = d2 <= Insignes.PORTEE_DETAILS * Insignes.PORTEE_DETAILS;
            ps.pushPose();
            ps.translate(v.x, v.y + 0.5, v.z);
            ps.mulPose(entityRenderDispatcher.cameraOrientation());
            ps.scale(0.025f, -0.025f, 0.025f);
            int fond = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25f) * 255) << 24;
            Matrix4f m = ps.last().pose();
            ResourceLocation metier = details ? ICONES_METIER.getOrDefault(e.tenue(), ICONES_METIER.get(Tenues.CHOMEUR)) : null;
            float x = -(e.largeurPrenom + (metier == null ? 0 : 10)) / 2f;
            if (metier != null) { icone(m, buf, metier, x, -0.5f, 9); x += 10; }
            ecrire(font, e.prenomLu, x, 0, 0xFFFFFFFF, m, buf, fond);
            if (details) {
                // Au-dessus du prénom : son statut unique (refonte 1.0.0, « ⛏ Mine · 32/64 fer », « ⚠ Pas de pioche au dépôt ») ;
                // un chercheur d'emploi n'en a pas : son icône d'état, comme avant.
                String statut = e.statutTete();
                if (!statut.isEmpty()) {
                    if (!statut.equals(e.statutLu)) { e.statutLu = statut; e.largeurStatut = font.width(statut); }
                    int couleur = statut.startsWith("⚠") ? 0xFFFF9966 : statut.equals(Statut.ATTENTE) || statut.equals(Statut.HORS_HORAIRES) ? 0xFFBBBBBB : 0xFF9FE09F;
                    ps.pushPose();
                    ps.translate(0, -10, 0);
                    ps.scale(0.75f, 0.75f, 1);
                    ecrire(font, statut, -e.largeurStatut / 2f, 0, couleur, ps.last().pose(), buf, fond);
                    ps.popPose();
                } else {
                    ResourceLocation etat = ICONES_ETAT.get(e.etat());
                    if (etat != null) icone(m, buf, etat, -5, -12, 10);
                }
                String role = e.role();
                if (!role.isEmpty()) {
                    if (!role.equals(e.roleLu)) { e.roleLu = role; e.largeurRole = font.width(role); }
                    ps.pushPose();
                    ps.translate(0, 10, 0);
                    ps.scale(0.75f, 0.75f, 1);
                    ecrire(font, role, -e.largeurRole / 2f, 0, 0xFFAAAAAA, ps.last().pose(), buf, fond);
                    ps.popPose();
                }
            }
            ps.popPose();
        }

        /** Rien d'opaque entre les yeux du joueur et les siens : revu toutes les {@link Insignes#REVUE} ticks (50 ouvriers, 60 images par seconde). */
        static boolean enVue(Travailleur e) {
            var joueur = Minecraft.getInstance().player;
            if (joueur == null) return false;
            long t = e.level().getGameTime();
            if (Insignes.arevoir(t, e.vueLe)) { e.vueLe = t; e.vue = rienDOpaque(e.level(), joueur.getEyePosition(), e.getEyePosition()); }
            return e.vue;
        }

        /**
         * Aucun bloc qui cache la vue entre deux points. Seuls les blocs qui masquent (`canOcclude`) comptent, et
         * seulement là où leur forme coupe la ligne : la vitre, la vitre fine, les feuilles, l'herbe laissent voir.
         */
        static boolean rienDOpaque(net.minecraft.world.level.Level l, Vec3 de, Vec3 a) {
            if (de.distanceToSqr(a) > 64 * 64) return false;
            Boolean cache = net.minecraft.world.level.BlockGetter.traverseBlocks(de, a, l, (niveau, pos) -> {
                var b = niveau.getBlockState(pos);
                return b.canOcclude() && b.getOcclusionShape(niveau, pos).clip(de, a, pos) != null ? Boolean.TRUE : null;
            }, niveau -> Boolean.FALSE);
            return !cache;
        }

        static void ecrire(Font font, String s, float x, float y, int couleur, Matrix4f m, MultiBufferSource buf, int fond) {
            int lumiere = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
            font.drawInBatch(s, x, y, 0x20FFFFFF, false, m, buf, Font.DisplayMode.SEE_THROUGH, fond, lumiere);
            font.drawInBatch(s, x, y, couleur, false, m, buf, Font.DisplayMode.NORMAL, 0, lumiere);
        }

        /** Une icône 16 x 16 de {@code textures/gui}, en carré de {@code taille} face à la caméra, en pleine lumière. */
        static void icone(Matrix4f m, MultiBufferSource buf, ResourceLocation tex, float x, float y, float taille) {
            VertexConsumer vc = buf.getBuffer(RenderType.text(tex));
            int l = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
            vc.addVertex(m, x, y, 0).setColor(255, 255, 255, 255).setUv(0, 0).setLight(l);
            vc.addVertex(m, x, y + taille, 0).setColor(255, 255, 255, 255).setUv(0, 1).setLight(l);
            vc.addVertex(m, x + taille, y + taille, 0).setColor(255, 255, 255, 255).setUv(1, 1).setLight(l);
            vc.addVertex(m, x + taille, y, 0).setColor(255, 255, 255, 255).setUv(1, 0).setLight(l);
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
                float x = -font.width(l) / 2f, y = -14 - (lignes.size() - i) * 10; // au-dessus de l'icône d'état (de -12 à -2)
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

    // --- La fiche de l'ouvrier, la nouvelle mission et la confirmation (refonte 1.0.0, parties 4.1 et 4.4) ----------------

    static void ouvrirFicheOuvrier(Reseau.FicheOuvrier f) { Minecraft.getInstance().setScreen(new EcranOuvrier(f.id(), f.infos())); }

    static void envoyer(int id, String action, CompoundTag d) { PacketDistributor.sendToServer(new Reseau.OrdreOuvrier(id, action, d)); }

    static int couleurStatut(String st) {
        return st.startsWith("⚠") ? ORANGE : st.equals(Statut.ATTENTE) || st.equals(Statut.HORS_HORAIRES) ? GRIS : VERT;
    }

    /** Au clic droit : sa mission et sa progression, sa file, sa paie et sa faim, son équipement, et les six boutons. */
    static class EcranOuvrier extends Cadre {
        final int id;
        final CompoundTag i;
        final List<CompoundTag> file = new ArrayList<>();
        static final int LIGNES = 6;

        EcranOuvrier(int id, CompoundTag infos) {
            super(Component.literal(infos.getString("nom")), 330, 250);
            this.id = id;
            this.i = infos;
            for (Tag t : infos.getList("file", Tag.TAG_COMPOUND)) file.add((CompoundTag) t);
        }

        @Override
        protected void init() {
            super.init();
            for (int k = 0; k < Math.min(LIGNES, file.size()); k++) {
                CompoundTag d = new CompoundTag();
                d.putInt("index", k);
                d.putInt("n", file.size());
                addRenderableWidget(Button.builder(Component.literal("✖"), b -> envoyer(id, "annuler", d))
                        .bounds(x0 + largeur - 24, y0 + 92 + k * 14, 14, 12).tooltip(Tooltip.create(Component.literal("Annuler cette mission"))).build());
            }
            int w = 100, xa = x0 + 10, xb = x0 + 115, xc = x0 + 220, y1 = y0 + hauteur - 52, y2 = y0 + hauteur - 28;
            bouton("Nouvelle mission", xa, y1, w, b -> minecraft.setScreen(new EcranNouvelleMission(this)));
            bouton("Travaille ici", xb, y1, w, b -> envoyer(id, "ici", new CompoundTag()));
            bouton("Suis-moi", xc, y1, w, b -> envoyer(id, "suivre", new CompoundTag()));
            bouton("Attends ici", xa, y2, w, b -> envoyer(id, "attendre", new CompoundTag()));
            bouton("Rentre au poste", xb, y2, w, b -> envoyer(id, "rentrer", new CompoundTag()));
            Button renvoi = bouton("Licencier", xc, y2, w, b -> minecraft.setScreen(new EcranConfirmer(this,
                    "Renvoyer " + i.getString("nom") + " ? Il ne travaillera plus pour toi.", () -> { envoyer(id, "licencier", new CompoundTag()); onClose(); })));
            renvoi.active = i.getBoolean("chef");
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            int x = x0 + 10, w = largeur - 20;
            texte(g, i.getString("nom") + " — " + i.getString("metier") + " " + "★".repeat(i.getInt("niveau")), x, y0 + 8, OR);
            String st = i.getString("statut");
            texte(g, st, x, y0 + 22, couleurStatut(st));
            texte(g, "Salaire : " + i.getString("salaireTexte") + "   Faim " + i.getInt("faim") + "/20   Vie " + i.getInt("vie") + "/" + i.getInt("vieMax"), x, y0 + 36, GRIS);
            List<String> eq = new ArrayList<>();
            for (Tag t : i.getList("equipement", Tag.TAG_STRING)) eq.add(t.getAsString());
            paragraphe(g, "Équipement : " + (eq.isEmpty() ? "rien" : String.join(", ", eq)), x, y0 + 50, w, GRIS, 2);
            texte(g, "Missions" + (i.contains("ici") ? " (travaille en " + i.getString("ici") + ")" : "") + " :", x, y0 + 78, BLANC);
            if (file.isEmpty()) texte(g, "Aucune. « Nouvelle mission » pour lui en donner une.", x + 4, y0 + 94, GRIS);
            for (int k = 0; k < Math.min(LIGNES, file.size()); k++) {
                CompoundTag m = file.get(k);
                String t = (k + 1) + ". " + m.getString("texte");
                texte(g, font.plainSubstrByWidth(t, w - 22), x + 4, y0 + 94 + k * 14, m.getBoolean("active") ? VERT : BLANC);
            }
            if (file.size() > LIGNES) texte(g, "… et " + (file.size() - LIGNES) + " autre(s), au poste.", x + 4, y0 + 94 + LIGNES * 14, GRIS);
        }
    }

    /** « Nouvelle mission » : seulement les missions de son métier, puis la quantité, la répétition, les horaires, le lieu. */
    static class EcranNouvelleMission extends Cadre {
        static final String[] REPETITIONS = {"fois", "jour", "stock"}, NOMS_REPETITION = {"Une fois", "Tous les jours", "Garder au dépôt"};
        static final String[] HORAIRES = {"toujours", "jour", "nuit"}, NOMS_HORAIRES = {"Toujours", "Le jour", "La nuit"};
        final EcranOuvrier fiche;
        final List<CompoundTag> commandes = new ArrayList<>();
        int choix = -1, repetition, horaires;
        boolean ici;
        String quantite = "64";
        EditBox boite;

        EcranNouvelleMission(EcranOuvrier fiche) {
            super(Component.literal("Nouvelle mission"), 330, 250);
            this.fiche = fiche;
            for (Tag t : fiche.i.getList("commandes", Tag.TAG_COMPOUND)) commandes.add((CompoundTag) t);
        }

        CompoundTag choisie() { return choix < 0 || choix >= commandes.size() ? null : commandes.get(choix); }

        int nombre() {
            try { return Math.max(1, Integer.parseInt(quantite)); } catch (NumberFormatException x) { return 1; }
        }

        @Override
        protected void init() {
            super.init();
            if (boite != null) quantite = boite.getValue();
            for (int k = 0; k < Math.min(18, commandes.size()); k++) {
                int n = k;
                Button b = bouton(commandes.get(k).getString("nom"), x0 + 10 + (k % 3) * 104, y0 + 24 + (k / 3) * 22, 100, x -> { choix = n; rebuildWidgets(); });
                b.active = k != choix;
            }
            int y = y0 + hauteur - 76;
            CompoundTag c = choisie();
            if (c != null && c.getBoolean("compte")) {
                boite = new EditBox(font, x0 + 70, y + 1, 50, 18, Component.literal("Quantité"));
                boite.setFilter(v -> v.matches("[0-9]{0,5}"));
                boite.setValue(quantite);
                addRenderableWidget(boite);
                bouton(NOMS_REPETITION[repetition] + " ▸", x0 + 130, y, 120, x -> { repetition = (repetition + 1) % 3; rebuildWidgets(); });
            } else boite = null;
            bouton("Horaires : " + NOMS_HORAIRES[horaires] + " ▸", x0 + 10, y + 24, 150, x -> { horaires = (horaires + 1) % 3; rebuildWidgets(); });
            bouton(ici ? "Lieu : ici ▸" : "Lieu : à son choix ▸", x0 + 166, y + 24, 150, x -> { ici = !ici; rebuildWidgets(); });
            Button ok = bouton("Valider", x0 + 10, y + 48, 150, x -> valider());
            ok.active = c != null;
            bouton("Retour", x0 + 166, y + 48, 150, x -> minecraft.setScreen(fiche));
        }

        void valider() {
            CompoundTag c = choisie();
            if (c == null) return;
            if (boite != null) quantite = boite.getValue();
            boolean compte = c.getBoolean("compte");
            String rep = compte ? REPETITIONS[repetition] : "fois";
            String texte = Statut.confirmation(fiche.i.getString("nom"), fiche.i.getString("metierId"), c.getString("nom"), compte, rep, nombre(), HORAIRES[horaires], ici);
            CompoundTag d = new CompoundTag();
            d.putString("id", c.getString("id"));
            d.putString("repetition", rep);
            d.putInt("nombre", nombre());
            d.putString("horaires", HORAIRES[horaires]);
            d.putBoolean("ici", ici);
            minecraft.setScreen(new EcranConfirmer(this, texte, () -> envoyer(fiche.id, "mission", d)));
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            texte(g, "Nouvelle mission pour " + fiche.i.getString("nom") + " (" + fiche.i.getString("metier").toLowerCase() + ")", x0 + 10, y0 + 8, OR);
            if (commandes.isEmpty())
                paragraphe(g, "Son métier n'a pas de mission à choisir : il travaille avec le poste. « Travaille ici » lui donne un lieu.", x0 + 10, y0 + 30, largeur - 20, GRIS, 4);
            CompoundTag c = choisie();
            if (c != null && c.getBoolean("compte")) texte(g, "Quantité :", x0 + 10, y0 + hauteur - 70, BLANC);
            else if (c != null) texte(g, c.getString("nom") + " : sans quantité.", x0 + 10, y0 + hauteur - 70, GRIS);
            else if (!commandes.isEmpty()) texte(g, "Choisis une mission.", x0 + 10, y0 + hauteur - 70, GRIS);
        }
    }

    /** « Compris : Raoul mine du fer jusqu'à 64. » ✔ / ✖ (partie 4.4). Aussi pour le renvoi. */
    static class EcranConfirmer extends Cadre {
        final Screen retour;
        final String texte;
        final Runnable oui;

        EcranConfirmer(Screen retour, String texte, Runnable oui) {
            super(Component.literal("Confirmer"), 300, 90);
            this.retour = retour;
            this.texte = texte;
            this.oui = oui;
        }

        @Override
        protected void init() {
            super.init();
            bouton("✔ Oui", x0 + 10, y0 + hauteur - 28, 135, b -> oui.run());
            bouton("✖ Non", x0 + largeur - 145, y0 + hauteur - 28, 135, b -> minecraft.setScreen(retour));
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            paragraphe(g, texte, x0 + 10, y0 + 12, largeur - 20, BLANC, 4);
        }
    }

    // --- Le poste de travail ------------------------------------------------------------------------------------

    static class EcranPoste extends Cadre {
        static final String[] ONGLETS = {"ouvriers", "missions", "depot", "caisse", "journal"};
        static final String[] NOMS_ONGLETS = {"Ouvriers", "Missions", "Dépôt", "Caisse", "Journal"};
        static final String[] CATEGORIES = {"", "outils", "minerais", "nourriture", "bois", "blocs", "divers"};
        static final String[] NOMS_CATEGORIES = {"Tout", "Outils", "Minerais", "Nourriture", "Bois", "Blocs", "Divers"};
        static String ongletMemo = "ouvriers", choisiMemo = "", categorieMemo = "", rechercheMemo = "";
        /** Le défilement de la liste de l'onglet ouvert (lignes, ou rangées de la grille du dépôt). */
        static int defilement;
        static final int LIGNES_OUVRIERS = 8, LIGNE_OUVRIER = 14, LIGNES_MISSIONS = 10, LIGNES_JOURNAL = 19, CASE = 18, COLONNES = 22, RANGEES = 9;

        final BlockPos pos;
        final CompoundTag f;
        final List<CompoundTag> ouvriers = new ArrayList<>(), journal = new ArrayList<>();
        /** Le dépôt : une pile d'un objet par sorte, son nombre et sa catégorie. */
        final List<ItemStack> objets = new ArrayList<>();
        final List<Long> nombres = new ArrayList<>();
        final List<String> categories = new ArrayList<>();
        CompoundTag choisi;
        // Réglages de l'ouvrier choisi
        int debut, fin, jours;
        EditBox salaire, recherche;
        Button bDebut, bFin, bJours;

        EcranPoste(BlockPos pos, CompoundTag infos) {
            super(Component.literal(infos.getString("titre")), 420, 252);
            this.pos = pos;
            this.f = infos;
            for (Tag x : infos.getList("ouvriers", Tag.TAG_COMPOUND)) ouvriers.add((CompoundTag) x);
            for (Tag x : infos.getList("journal", Tag.TAG_COMPOUND)) journal.add((CompoundTag) x);
            var niveau = Minecraft.getInstance().level;
            if (niveau != null) for (Tag x : infos.getList("depot", Tag.TAG_COMPOUND)) {
                CompoundTag d = (CompoundTag) x;
                ItemStack s = ItemStack.parseOptional(niveau.registryAccess(), d.getCompound("objet"));
                if (s.isEmpty()) continue;
                objets.add(s);
                nombres.add(d.getLong("n"));
                categories.add(d.getString("cat"));
            }
            choisi = ouvriers.stream().filter(o -> o.getString("uuid").equals(choisiMemo)).findFirst().orElse(ouvriers.isEmpty() ? null : ouvriers.get(0));
            if (choisi != null) {
                debut = choisi.getInt("debut");
                fin = choisi.getInt("fin");
                jours = Math.max(1, choisi.getInt("joursExpedition"));
            }
        }

        void envoyer(String action, CompoundTag d) { PacketDistributor.sendToServer(new Reseau.ActionBureau(pos, action, d)); }

        void onglet(String o) { ongletMemo = o; defilement = 0; rebuildWidgets(); }

        /** La couleur d'un statut : rouge s'il est bloqué, absent, à terre ou en grève ; vert au travail ; or en expédition. */
        static int couleur(CompoundTag o) {
            String st = o.getString("statut");
            if (o.getBoolean("absent") || st.startsWith("⚠") || st.contains("loqué") || st.equals("En grève") || st.equals("À terre")) return ROUGE;
            if (st.equals("En expédition")) return OR;
            if (st.equals("Au travail") || st.startsWith("⛏") || st.contains("·")) return VERT;
            return GRIS;
        }

        /** Sa mission en cours (la première de sa file), ou un tiret. */
        static String missionEnCours(CompoundTag o) {
            ListTag ms = o.getList("missions", Tag.TAG_STRING);
            return o.getBoolean("absent") ? "" : ms.isEmpty() ? "Aucune mission" : ms.getString(0) + (ms.size() > 1 ? "  (+" + (ms.size() - 1) + ")" : "");
        }

        /** Le texte coupé pour tenir dans {@code w} pixels. */
        String couper(String t, int w) { return font.width(t) <= w ? t : font.plainSubstrByWidth(t, w - font.width("…")) + "…"; }

        /** Les objets du dépôt montrés : la catégorie choisie et la recherche. */
        List<Integer> visibles() {
            List<Integer> l = new ArrayList<>();
            String q = rechercheMemo.strip().toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < objets.size(); i++)
                if ((categorieMemo.isEmpty() || categorieMemo.equals(categories.get(i)))
                        && (q.isEmpty() || objets.get(i).getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q))) l.add(i);
            return l;
        }

        /** Les lignes de l'onglet Missions : {ouvrier, -1} pour son nom, {ouvrier, i} pour sa mission n° i. */
        List<int[]> lignesMissions() {
            List<int[]> l = new ArrayList<>();
            for (int k = 0; k < ouvriers.size(); k++) {
                CompoundTag o = ouvriers.get(k);
                if (o.getBoolean("absent")) continue;
                int n = o.getList("missions", Tag.TAG_STRING).size();
                l.add(new int[]{k, -1});
                for (int i = 0; i < n; i++) l.add(new int[]{k, i});
            }
            return l;
        }

        /** Le nombre de lignes de la liste de l'onglet ouvert, et combien s'en voient à la fois. */
        int[] tailleListe() {
            return switch (ongletMemo) {
                case "missions" -> new int[]{lignesMissions().size(), LIGNES_MISSIONS};
                case "journal" -> new int[]{journal.size(), LIGNES_JOURNAL};
                case "depot" -> new int[]{(visibles().size() + COLONNES - 1) / COLONNES, RANGEES};
                case "caisse" -> new int[]{ouvriers.size(), 12};
                default -> new int[]{ouvriers.size(), LIGNES_OUVRIERS};
            };
        }

        void defiler(int sens) {
            int[] t = tailleListe();
            int d = Mth.clamp(defilement + sens, 0, Math.max(0, t[0] - t[1]));
            if (d == defilement) return;
            defilement = d;
            if (ongletMemo.equals("missions")) rebuildWidgets(); // ses boutons suivent les lignes
        }

        @Override
        protected void init() {
            super.init();
            if (!f.getBoolean("poste")) {
                if (!f.contains("ailleurs")) bouton("Faire de ce bureau mon poste de travail", x0 + 10, y0 + 90, 260, b -> envoyer("devenir_poste", new CompoundTag()));
                bouton("Caisse et dépôt", x0 + 10, y0 + hauteur - 28, 120, b -> envoyer("stock", new CompoundTag()));
                bouton("Fermer", x0 + largeur - 90, y0 + hauteur - 28, 80, b -> onClose());
                return;
            }
            if (!List.of(ONGLETS).contains(ongletMemo)) ongletMemo = "ouvriers";
            for (int k = 0; k < ONGLETS.length; k++) {
                String o = ONGLETS[k];
                bouton(NOMS_ONGLETS[k], x0 + 8 + k * 68, y0 + 4, 66, b -> onglet(o)).active = !ongletMemo.equals(o);
            }
            bouton("✕", x0 + largeur - 22, y0 + 4, 18, b -> onClose());
            int[] t = tailleListe();
            defilement = Mth.clamp(defilement, 0, Math.max(0, t[0] - t[1]));
            switch (ongletMemo) {
                case "missions" -> initMissions();
                case "depot" -> initDepot();
                case "caisse" -> bouton("Déposer des pièces", x0 + largeur - 138, y0 + 28, 130, b -> envoyer("stock", new CompoundTag()))
                        .setTooltip(Tooltip.create(Component.literal("Ouvre la caisse : mets-y les pièces de la paie.")));
                case "journal" -> {}
                default -> initOuvriers();
            }
        }

        // --- Onglet Ouvriers : la liste (état et mission de chacun), puis les réglages de celui qu'on a choisi

        void initOuvriers() {
            if (choisi == null) return;
            int x = x0 + 8, y = y0 + 166;
            if (choisi.getBoolean("absent")) {
                bouton("Retirer de la liste", x, y + 30, 140, b -> {
                    CompoundTag d = new CompoundTag();
                    d.putString("uuid", choisi.getString("uuid"));
                    choisiMemo = "";
                    envoyer("retirer", d);
                });
                return;
            }
            // Horaires
            bouton("-", x, y + 40, 14, b -> { debut = Math.floorMod(debut - 1, 24); majBoutons(); });
            bDebut = bouton("", x + 15, y + 40, 34, b -> {});
            bouton("+", x + 50, y + 40, 14, b -> { debut = (debut + 1) % 24; majBoutons(); });
            bouton("-", x + 82, y + 40, 14, b -> { fin = Math.floorMod(fin - 1, 24); majBoutons(); });
            bFin = bouton("", x + 97, y + 40, 34, b -> {});
            bouton("+", x + 132, y + 40, 14, b -> { fin = (fin + 1) % 24; majBoutons(); });
            // Expéditions
            bouton("-", x + 160, y + 40, 14, b -> { jours = Math.max(1, jours - 1); majBoutons(); });
            bJours = bouton("", x + 175, y + 40, 46, b -> {});
            bJours.setTooltip(Tooltip.create(Component.literal("Plus loin qu'une journée de marche, il part en expédition : il campe, et rentre au plus tard au bout de ce nombre de jours.")));
            bouton("+", x + 222, y + 40, 14, b -> { jours = Math.min(30, jours + 1); majBoutons(); });
            // Paie
            salaire = new EditBox(font, x + 250, y + 42, 56, 16, Component.literal("Paie"));
            salaire.setMaxLength(9);
            salaire.setFilter(v -> v.matches("[0-9]*"));
            salaire.setValue(String.valueOf(choisi.getLong("salaireVerse")));
            addRenderableWidget(salaire);
            bouton("Enregistrer", x + 312, y + 40, 92, b -> {
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

        // --- Onglet Missions : toutes les files, à réordonner ou annuler

        void initMissions() {
            List<int[]> l = lignesMissions();
            for (int r = 0; r < Math.min(LIGNES_MISSIONS, l.size() - defilement); r++) {
                int[] ligne = l.get(defilement + r);
                if (ligne[1] < 0) continue;
                CompoundTag o = ouvriers.get(ligne[0]);
                int i = ligne[1], n = o.getList("missions", Tag.TAG_STRING).size(), y = y0 + 30 + r * 19;
                bouton("▲", x0 + largeur - 70, y, 20, b -> mission(o, "mission_monter", i, n)).active = i > 0;
                bouton("▼", x0 + largeur - 49, y, 20, b -> mission(o, "mission_descendre", i, n)).active = i < n - 1;
                bouton("✕", x0 + largeur - 28, y, 20, b -> mission(o, "mission_annuler", i, n))
                        .setTooltip(Tooltip.create(Component.literal(i == 0 ? "Annuler cette mission : il passe à la suivante." : "Annuler cette mission.")));
            }
        }

        void mission(CompoundTag o, String action, int i, int n) {
            CompoundTag d = new CompoundTag();
            d.putString("uuid", o.getString("uuid"));
            d.putInt("i", i);
            d.putInt("n", n);
            envoyer(action, d);
        }

        // --- Onglet Dépôt : la grille, des catégories et une recherche

        void initDepot() {
            for (int k = 0; k < CATEGORIES.length; k++) {
                String c = CATEGORIES[k];
                bouton(NOMS_CATEGORIES[k], x0 + 8 + k * 58, y0 + 28, 56, b -> { categorieMemo = c; defilement = 0; rebuildWidgets(); }).active = !categorieMemo.equals(c);
            }
            recherche = new EditBox(font, x0 + 8, y0 + 52, 200, 16, Component.literal("Chercher"));
            recherche.setMaxLength(40);
            recherche.setValue(rechercheMemo);
            recherche.setHint(Component.literal("Chercher un objet…").withStyle(s -> s.withColor(0x777777)));
            recherche.setResponder(v -> { rechercheMemo = v; defilement = 0; });
            addRenderableWidget(recherche);
            bouton("Ouvrir les coffres", x0 + largeur - 128, y0 + 50, 120, b -> envoyer("stock", new CompoundTag()))
                    .setTooltip(Tooltip.create(Component.literal("Pour prendre ou poser des objets au dépôt, et des pièces dans la caisse.")));
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            if (f.getBoolean("poste") && my > y0 + 26) { defiler(-(int) Math.signum(dy)); return true; }
            return super.mouseScrolled(mx, my, dx, dy);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int b) {
            // Onglet Ouvriers : un clic sur une ligne de la liste choisit cet ouvrier.
            if (f.getBoolean("poste") && ongletMemo.equals("ouvriers") && mx >= x0 + 8 && mx < x0 + largeur - 8) {
                int r = (int) Math.floor((my - (y0 + 44)) / LIGNE_OUVRIER);
                if (my >= y0 + 44 && r >= 0 && r < LIGNES_OUVRIERS && defilement + r < ouvriers.size()) {
                    CompoundTag o = ouvriers.get(defilement + r);
                    choisiMemo = o.getString("uuid");
                    choisi = o;
                    debut = o.getInt("debut");
                    fin = o.getInt("fin");
                    jours = Math.max(1, o.getInt("joursExpedition"));
                    rebuildWidgets();
                    return true;
                }
            }
            return super.mouseClicked(mx, my, b);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            texte(g, title.getString(), x0 + 2, y0 - 11, OR);
            if (!f.getBoolean("poste")) {
                if (f.contains("ailleurs")) {
                    paragraphe(g, "Ton poste de travail est en " + f.getString("ailleurs") + ". Ce bureau n'est plus qu'un coffre : vide sa caisse et son dépôt, puis casse-le.", x0 + 10, y0 + 34, largeur - 20, BLANC, 6);
                } else {
                    paragraphe(g, "Chaque chef a un seul poste de travail, commun à tous ses ouvriers : ils s'y rattachent tous, y touchent leur paie et y déposent leur ouvrage.", x0 + 10, y0 + 34, largeur - 20, BLANC, 6);
                    paragraphe(g, "Tes autres bureaux ne seront plus que des coffres.", x0 + 10, y0 + 70, largeur - 20, GRIS, 2);
                }
                return;
            }
            switch (ongletMemo) {
                case "missions" -> renderMissions(g);
                case "depot" -> renderDepot(g, mx, my);
                case "caisse" -> renderCaisse(g);
                case "journal" -> renderJournal(g, mx, my);
                default -> renderOuvriers(g, mx, my);
            }
            int[] t = tailleListe();
            if (t[0] > t[1]) texte(g, (defilement + 1) + "–" + Math.min(t[0], defilement + t[1]) + " / " + t[0] + " ⇕", x0 + largeur - 90, y0 + hauteur - 11, GRIS);
        }

        void renderOuvriers(GuiGraphics g, int mx, int my) {
            if (ouvriers.isEmpty()) {
                paragraphe(g, "Tu n'as encore aucun ouvrier. Les chercheurs d'emploi passent près de toi : clic droit sur l'un d'eux pour l'embaucher.", x0 + 10, y0 + 34, largeur - 20, GRIS, 6);
                return;
            }
            int xs = x0 + 128, xm = x0 + 262;
            texte(g, "OUVRIER", x0 + 10, y0 + 32, OR);
            texte(g, "ÉTAT", xs, y0 + 32, OR);
            texte(g, "MISSION", xm, y0 + 32, OR);
            for (int r = 0; r < Math.min(LIGNES_OUVRIERS, ouvriers.size() - defilement); r++) {
                CompoundTag o = ouvriers.get(defilement + r);
                int y = y0 + 44 + r * LIGNE_OUVRIER;
                boolean sel = choisi != null && choisi.getString("uuid").equals(o.getString("uuid"));
                boolean survol = mx >= x0 + 8 && mx < x0 + largeur - 8 && my >= y && my < y + LIGNE_OUVRIER;
                if (sel || survol) g.fill(x0 + 6, y - 2, x0 + largeur - 6, y + LIGNE_OUVRIER - 2, sel ? 0x50F2C14E : 0x28FFFFFF);
                texte(g, couper(o.getString("nom") + " " + "★".repeat(o.getInt("niveau")), xs - x0 - 16), x0 + 10, y, BLANC);
                texte(g, couper(o.getString("statut"), xm - xs - 6), xs, y, couleur(o));
                texte(g, couper(missionEnCours(o), x0 + largeur - 10 - xm), xm, y, GRIS);
            }
            if (choisi == null) return;
            int x = x0 + 8, y = y0 + 166;
            g.fill(x0 + 6, y - 6, x0 + largeur - 6, y - 5, 0xFF8A6A2A);
            texte(g, choisi.getString("nom") + " — " + choisi.getString("metier") + " " + "★".repeat(choisi.getInt("niveau")), x, y, OR);
            if (choisi.getBoolean("absent")) {
                paragraphe(g, choisi.getString("detail"), x, y + 12, largeur - 16, GRIS, 2);
                return;
            }
            String ou = choisi.getString("distance") + (choisi.getString("sortie").isEmpty() ? "" : ", " + choisi.getString("sortie"));
            texte(g, couper(choisi.getString("statut") + (ou.isEmpty() ? "" : " · " + ou), largeur - 16), x, y + 11, couleur(choisi));
            texte(g, "Vie " + choisi.getInt("vie") + "/" + choisi.getInt("vieMax") + "   Faim " + choisi.getInt("faim") + "/20"
                    + (choisi.getInt("impayes") > 0 ? "   Paies en retard : " + choisi.getInt("impayes") : "")
                    + (choisi.getString("besoin").isEmpty() ? "" : "   Il demande : " + choisi.getString("besoin")), x, y + 21, GRIS);
            texte(g, "HORAIRES", x, y + 31, OR);
            texte(g, "à", x + 70, y + 46, GRIS);
            texte(g, "EXPÉDITION MAX", x + 160, y + 31, OR);
            texte(g, "PAIE (bronzes)", x + 250, y + 31, OR);
            texte(g, couper(choisi.getString("paie") + " · il demande " + choisi.getLong("salaireDemande"), largeur - 16), x, y + 64, GRIS);
        }

        void renderMissions(GuiGraphics g) {
            List<int[]> l = lignesMissions();
            if (l.isEmpty()) { texte(g, "Aucun ouvrier.", x0 + 10, y0 + 34, GRIS); return; }
            for (int r = 0; r < Math.min(LIGNES_MISSIONS, l.size() - defilement); r++) {
                int[] ligne = l.get(defilement + r);
                CompoundTag o = ouvriers.get(ligne[0]);
                int y = y0 + 36 + r * 19;
                if (ligne[1] < 0) {
                    ListTag ms = o.getList("missions", Tag.TAG_STRING);
                    texte(g, o.getString("nom") + " — " + o.getString("metier"), x0 + 10, y, OR);
                    texte(g, couper(ms.isEmpty() ? "aucune mission" : o.getString("statut"), largeur - 200), x0 + 190, y, ms.isEmpty() ? GRIS : couleur(o));
                } else {
                    String m = o.getList("missions", Tag.TAG_STRING).getString(ligne[1]);
                    texte(g, couper((ligne[1] + 1) + ". " + m, largeur - 100), x0 + 22, y, ligne[1] == 0 ? BLANC : GRIS);
                }
            }
        }

        void renderDepot(GuiGraphics g, int mx, int my) {
            List<Integer> v = visibles();
            int gx = x0 + 9, gy = y0 + 74;
            for (int r = 0; r < RANGEES; r++)
                for (int c = 0; c < COLONNES; c++) {
                    int x = gx + c * CASE, y = gy + r * CASE;
                    g.fill(x, y, x + CASE - 1, y + CASE - 1, 0xFF2A2E2B);
                    g.renderOutline(x, y, CASE - 1, CASE - 1, 0xFF4A3E28);
                }
            ItemStack survol = null;
            long nSurvol = 0;
            for (int k = 0; k < Math.min(RANGEES * COLONNES, v.size() - defilement * COLONNES); k++) {
                int i = v.get(defilement * COLONNES + k);
                int x = gx + (k % COLONNES) * CASE, y = gy + (k / COLONNES) * CASE;
                ItemStack s = objets.get(i);
                long n = nombres.get(i);
                g.renderItem(s, x, y);
                g.renderItemDecorations(font, s, x, y, n <= 1 ? null : n < 1000 ? String.valueOf(n) : n < 100_000 ? (n / 1000) + "k" : "∞");
                if (mx >= x && mx < x + CASE - 1 && my >= y && my < y + CASE - 1) { survol = s; nSurvol = n; }
            }
            if (objets.isEmpty()) texte(g, "Le dépôt est vide.", gx + 2, gy + 4, GRIS);
            else if (v.isEmpty()) texte(g, "Rien ne correspond.", gx + 2, gy + 4, GRIS);
            if (survol != null) {
                List<Component> l = new ArrayList<>(Screen.getTooltipFromItem(minecraft, survol));
                l.add(Component.literal("× " + nSurvol + " au dépôt").withStyle(s -> s.withColor(OR)));
                g.renderComponentTooltip(font, l, mx, my);
            }
        }

        void renderCaisse(GuiGraphics g) {
            CompoundTag c = f.getCompound("caisse");
            int x = x0 + 10, y = y0 + 32;
            texte(g, "DANS LA CAISSE", x, y, OR);
            texte(g, c.getString("solde"), x + 140, y, c.getBoolean("assez") ? VERT : ROUGE);
            texte(g, "UNE PAIE DE TOUS", x, y + 13, OR);
            texte(g, c.getString("salaires"), x + 140, y + 13, BLANC);
            texte(g, "PROCHAINE PAIE", x, y + 26, OR);
            texte(g, c.getString("prochaine"), x + 140, y + 26, BLANC);
            if (c.getInt("retards") > 0) texte(g, "Paies en retard : " + c.getInt("retards") + " (sans paie, ils se mettent en grève)", x, y + 39, ROUGE);
            else if (!c.getBoolean("assez")) texte(g, "La caisse ne suffit pas pour la prochaine paie.", x, y + 39, ORANGE);
            int yl = y + 56;
            texte(g, "OUVRIER", x, yl, OR);
            texte(g, "SALAIRE", x + 140, yl, OR);
            texte(g, "PAIE", x + 230, yl, OR);
            for (int r = 0; r < Math.min(12, ouvriers.size() - defilement); r++) {
                CompoundTag o = ouvriers.get(defilement + r);
                int y2 = yl + 12 + r * 11;
                texte(g, couper(o.getString("nom"), 130), x, y2, o.getBoolean("absent") ? GRIS : BLANC);
                if (o.getBoolean("absent")) continue;
                texte(g, couper(o.getString("salaire") + " " + (o.getString("periode").isEmpty() ? "" : o.getString("periode")), 86), x + 140, y2, BLANC);
                texte(g, couper(o.getString("paie"), largeur - 250), x + 230, y2, o.getInt("impayes") > 0 ? ROUGE : GRIS);
            }
        }

        void renderJournal(GuiGraphics g, int mx, int my) {
            if (journal.isEmpty()) {
                paragraphe(g, "Rien encore. Chaque événement de la colonie s'inscrit ici, en une ligne : une mission finie, un ouvrier bloqué, une attaque repoussée.", x0 + 10, y0 + 34, largeur - 20, GRIS, 4);
                return;
            }
            CompoundTag survol = null;
            for (int r = 0; r < Math.min(LIGNES_JOURNAL, journal.size() - defilement); r++) {
                CompoundTag l = journal.get(defilement + r);
                int y = y0 + 32 + r * 11;
                String date = l.getString("date");
                texte(g, date, x0 + 10, y, GRIS);
                texte(g, couper(l.getString("ligne"), largeur - 110), x0 + 100, y, l.getBoolean("importante") ? ORANGE : BLANC);
                if (mx >= x0 + 8 && mx < x0 + largeur - 8 && my >= y - 1 && my < y + 10) survol = l;
            }
            if (survol != null && !survol.getString("texte").isBlank())
                g.renderTooltip(font, font.split(Component.literal(survol.getString("texte")), 260), mx, my);
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

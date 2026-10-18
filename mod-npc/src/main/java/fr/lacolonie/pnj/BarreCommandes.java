package fr.lacolonie.pnj;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * La barre de commandes (refonte 1.0.0, partie 4.3) : une touche (B par défaut, catégorie « La Colonie ») ouvre une
 * barre de saisie ; on tape « gar… » et elle propose les actions du {@link Repertoire} que l'ouvrier visé sait faire
 * (« Garde à vous », « Garde cette position »…). On ne peut envoyer qu'une ligne de la liste, jamais une phrase libre.
 * <p>
 * Trois parties : la recherche (pur Java, {@link #chercher}, testée par {@code BarreCommandesTest}) ; le serveur
 * ({@link #executer}, appelé par le paquet {@code Reseau.Barre} et par le joueur fantôme) ; l'écran et la touche, chez
 * le joueur seulement ({@link Ecran}, {@link Touche}).
 */
public final class BarreCommandes {
    private BarreCommandes() {}

    /** L'ouvrier que l'on regarde, jusqu'à tant de blocs ; sinon le plus proche des siens, jusqu'à {@link #PROCHE}. */
    static final double REGARD = 8, PROCHE = 16;

    // --- La recherche (pur Java) ----------------------------------------------------------------------------

    /** La recherche, à part : sans rien du jeu (les tests JUnit la chargent sans Minecraft). */
    static final class Recherche {
        private Recherche() {}

        /** Au plus tant de propositions. */
        static final int MAX = 8;

        /** Une ligne proposée : l'action {@code id}, ce qu'on lit ({@code libelle}, {@code detail}) et ce où l'on cherche ({@code textes}, aplatis). */
        record Ligne(String id, String libelle, String detail, Set<String> metiers, List<String> textes) {
            boolean pour(String metier) { return metiers.isEmpty() || metiers.contains(metier); }
        }

        /** Sans accents, sans majuscules, la ponctuation en espaces : « Garde-à-vous ! » → « garde a vous ». */
        static String plat(String s) {
            String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return n.replace("œ", "oe").replace("æ", "ae").replaceAll("[^a-z0-9]+", " ").strip();
        }

        /** La ligne d'une action : sa première formule pour libellé (« Garde à vous »), ce qu'elle fait en dessous. */
        static Ligne ligne(Repertoire.Entree e) {
            String f = (e.exemples().isEmpty() ? e.nom() : e.exemples().get(0)).replace("@", "…").strip();
            String libelle = f.isEmpty() ? e.id() : Character.toUpperCase(f.charAt(0)) + f.substring(1);
            List<String> textes = new ArrayList<>();
            textes.add(plat(libelle));
            textes.add(plat(e.nom()));
            for (String d : e.dits()) {
                String x = plat(d);
                if (!x.isEmpty() && !textes.contains(x)) textes.add(x);
            }
            return new Ligne(e.id(), libelle, e.nom(), e.metiers(), textes);
        }

        private static List<Ligne> lignes;

        /** Toutes les lignes du répertoire, préparées une fois. */
        static List<Ligne> lignes() {
            if (lignes == null) lignes = Repertoire.TOUTES.stream().map(Recherche::ligne).toList();
            return lignes;
        }

        /**
         * Le rang d'une ligne pour la saisie (déjà aplatie) : 0, le libellé commence par elle ; 1, un mot du libellé ;
         * 2, un mot d'une formule ou de ce qu'elle fait ; 3, chaque mot saisi commence un mot d'un même texte ; -1, rien.
         */
        static int rang(Ligne l, String q) {
            if (q.isEmpty()) return 3;
            List<String> t = l.textes();
            if (t.get(0).startsWith(q)) return 0;
            if ((" " + t.get(0)).contains(" " + q)) return 1;
            for (String x : t) if ((" " + x).contains(" " + q)) return 2;
            String[] mots = q.split(" ");
            for (String x : t) {
                String y = " " + x;
                boolean tous = true;
                for (String m : mots) if (!y.contains(" " + m)) { tous = false; break; }
                if (tous) return 3;
            }
            return -1;
        }

        /** Les actions à proposer pour {@code saisie} à un ouvrier de ce métier : celles de tous, et les siennes ; {@link #MAX} au plus. */
        static List<Ligne> chercher(String saisie, String metier, List<Ligne> parmi) {
            String q = plat(saisie);
            record Note(Ligne l, int r, int i) {}
            List<Note> r = new ArrayList<>();
            for (int i = 0; i < parmi.size(); i++) {
                Ligne l = parmi.get(i);
                if (!l.pour(metier)) continue;
                int k = rang(l, q);
                if (k >= 0) r.add(new Note(l, k, i));
            }
            r.sort(Comparator.comparingInt(Note::r).thenComparingInt(Note::i));
            return r.stream().limit(MAX).map(Note::l).toList();
        }

        static List<Ligne> chercher(String saisie, String metier) { return chercher(saisie, metier, lignes()); }
    }

    // --- Le serveur ------------------------------------------------------------------------------------------

    /**
     * Le joueur {@code p} choisit l'action {@code idAction} pour l'ouvrier {@code t}. Mêmes droits que de vive voix :
     * son chef, ou un joueur que le chef autorise ({@link Commandement}), hors guerre et hors ordres réservés au chef.
     * L'action est jouée par {@link Consignes#ordonner}. Renvoie ce que l'ouvrier répond (vide : rien n'est fait, ou
     * rien à dire), aussi montré au joueur (« Raoul : Garde-à-vous, chef ! ») et en bulle.
     */
    public static String executer(ServerPlayer p, Travailleur t, String idAction) {
        Repertoire.Entree e = idAction == null ? null : Repertoire.PAR_ID.get(idAction);
        if (e == null || p == null || t == null || !t.isAlive() || t.aTerre || t.chef == null || t.mode == Travailleur.Mode.CHOMEUR
                || p.level() != t.level() || p.distanceToSqr(t) > (PROCHE + 4) * (PROCHE + 4)) return "";
        String dit;
        if (!p.getUUID().equals(t.chef)) {
            Courrier c = Courrier.de(p.getServer());
            String chef = t.chefNom.isEmpty() ? "mon maître" : t.chefNom;
            if (c.enGuerre(t.chef, p.getUUID()) || !c.commande(t.chef, p.getName().getString())) dit = "Je n'obéis qu'à " + chef + ".";
            else if (Commandement.GESTES_AU_CHEF.contains(e.comportement()) || Commandement.ouvrageDuChef(e.id())) dit = "Ça, c'est à " + chef + " d'en décider.";
            else dit = null;
            if (dit != null) { repondre(p, t, dit); return dit; }
        }
        t.getLookControl().setLookAt(p);
        String phrase = e.exemples().isEmpty() ? "" : e.exemples().get(0).replace("@", "").strip();
        dit = Consignes.ordonner(t, new Ordres.Faire(e.id(), "", 0, 0, false), p, phrase, 0, 1);
        if (dit.isBlank()) dit = "Bien, chef.";
        repondre(p, t, dit);
        return dit;
    }

    private static void repondre(ServerPlayer p, Travailleur t, String dit) {
        t.dire(dit);
        Travailleur.signaler(p, t.prenom + " : " + dit);
    }

    // --- Chez le joueur ----------------------------------------------------------------------------------------

    /** La touche, et ce qu'elle ouvre. Seulement chez le joueur. */
    @EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
    static final class Touche {
        private Touche() {}

        // Les noms s'affichent tels quels dans les contrôles (pas de clé de traduction).
        static final KeyMapping BARRE = new KeyMapping("Barre de commandes", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "La Colonie");

        @SubscribeEvent
        static void touches(RegisterKeyMappingsEvent e) { e.register(BARRE); }

        @SubscribeEvent
        static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            while (BARRE.consumeClick()) {
                if (mc.player == null || mc.screen != null) continue;
                Travailleur t = cible(mc.player);
                if (t == null) mc.player.displayClientMessage(Component.literal("Aucun de tes ouvriers en vue."), true);
                else mc.setScreen(new Ecran(t));
            }
        }

        /** L'ouvrier regardé (8 blocs, sans mur entre), sinon le plus proche des siens (16 blocs). */
        static Travailleur cible(Player p) {
            Vec3 oeil = p.getEyePosition(), loin = oeil.add(p.getViewVector(1.0f).scale(REGARD));
            HitResult bloc = p.pick(REGARD, 1.0f, false);
            double max = bloc.getType() == HitResult.Type.MISS ? REGARD * REGARD : bloc.getLocation().distanceToSqr(oeil);
            EntityHitResult vu = ProjectileUtil.getEntityHitResult(p, oeil, loin, p.getBoundingBox().expandTowards(loin.subtract(oeil)).inflate(1),
                    x -> x instanceof Travailleur w && embauche(w), max);
            if (vu != null && vu.getEntity() instanceof Travailleur w) return w;
            String nom = p.getName().getString();
            Travailleur meilleur = null;
            for (Travailleur w : p.level().getEntitiesOfClass(Travailleur.class, new AABB(p.blockPosition()).inflate(PROCHE),
                    w -> embauche(w) && w.role().endsWith(" · " + nom) && w.distanceToSqr(p) <= PROCHE * PROCHE))
                if (meilleur == null || w.distanceToSqr(p) < meilleur.distanceToSqr(p)) meilleur = w;
            return meilleur;
        }

        static boolean embauche(Travailleur w) { return w.isAlive() && !metier(w).equals(Tenues.CHOMEUR); }

        /** Son métier, lu dans sa tenue (synchronisée). */
        static String metier(Travailleur w) { return w.getEntityData().get(Travailleur.TENUE); }

        /** Son prénom, lu dans son nom affiché (« Raoul (Mineur) · KOLRAX_MC »). */
        static String prenom(Entity w) {
            String n = w.getName().getString();
            for (String fin : new String[]{" (", " ·"}) { int i = n.indexOf(fin); if (i > 0) n = n.substring(0, i); }
            return n;
        }
    }

    /** La barre : une saisie en bas de l'écran, les propositions au-dessus. */
    static final class Ecran extends Screen {
        static final int LARGEUR = 320, HAUTEUR_LIGNE = 12;
        static final String INVITE = "Tape le début d'un ordre…";
        final Travailleur cible;
        final String metier, prenom;
        EditBox saisie;
        List<Recherche.Ligne> vues = List.of();
        int choix;

        Ecran(Travailleur cible) {
            super(Component.literal("Barre de commandes"));
            this.cible = cible;
            this.metier = Touche.metier(cible);
            this.prenom = Touche.prenom(cible);
        }

        int gauche() { return (width - LARGEUR) / 2; }

        int basListe() { return height - 34; }

        @Override
        protected void init() {
            String avant = saisie == null ? "" : saisie.getValue();
            saisie = new EditBox(font, gauche() + 4, height - 31, LARGEUR - 8, 14, Component.literal("Ordre"));
            saisie.setMaxLength(48);
            saisie.setBordered(true);
            saisie.setValue(avant);
            // Le jeu ne montre l'invite (setHint) que si la saisie n'a pas la main ; elle l'a toujours ici : une suggestion, en gris après le curseur.
            saisie.setResponder(s -> { saisie.setSuggestion(s.isEmpty() ? INVITE : null); majListe(); });
            saisie.setSuggestion(avant.isEmpty() ? INVITE : null);
            addRenderableWidget(saisie);
            setInitialFocus(saisie);
            majListe();
        }

        void majListe() {
            vues = Recherche.chercher(saisie.getValue(), metier);
            choix = vues.isEmpty() ? -1 : 0;
        }

        /** La ligne sous la souris, ou -1. Les lignes montent depuis la saisie : la première est juste au-dessus. */
        int ligneSous(double x, double y) {
            if (x < gauche() || x > gauche() + LARGEUR) return -1;
            int i = (int) ((basListe() - y) / HAUTEUR_LIGNE);
            return y <= basListe() && i >= 0 && i < vues.size() ? i : -1;
        }

        void envoyer(int i) {
            if (i < 0 || i >= vues.size() || !cible.isAlive()) return;
            PacketDistributor.sendToServer(new Reseau.Barre(cible.getId(), vues.get(i).id()));
            onClose();
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { envoyer(choix); return true; }
            if (!vues.isEmpty() && key == GLFW.GLFW_KEY_UP) { choix = (choix + 1) % vues.size(); return true; }
            if (!vues.isEmpty() && key == GLFW.GLFW_KEY_DOWN) { choix = (choix - 1 + vues.size()) % vues.size(); return true; }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        public boolean mouseClicked(double x, double y, int bouton) {
            int i = ligneSous(x, y);
            if (bouton == 0 && i >= 0) { envoyer(i); return true; }
            return super.mouseClicked(x, y, bouton);
        }

        @Override
        public void mouseMoved(double x, double y) {
            int i = ligneSous(x, y);
            if (i >= 0) choix = i;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            int x = gauche(), bas = basListe();
            int haut = bas - Math.max(1, vues.size()) * HAUTEUR_LIGNE - 14;
            g.fill(x, haut, x + LARGEUR, height - 2, 0xE8101010); // assez sombre : les cœurs de la barre de vie, dessous, ne se lisent plus à travers
            g.drawString(font, "Ordre à " + prenom + " (" + Metier.nom(metier).toLowerCase(Locale.ROOT) + ")", x + 4, haut + 3, Client.OR);
            if (vues.isEmpty()) g.drawString(font, "Aucun ordre ne commence ainsi.", x + 6, bas - HAUTEUR_LIGNE + 2, Client.GRIS);
            for (int i = 0; i < vues.size(); i++) {
                Recherche.Ligne l = vues.get(i);
                int y = bas - (i + 1) * HAUTEUR_LIGNE;
                if (i == choix) g.fill(x + 2, y, x + LARGEUR - 2, y + HAUTEUR_LIGNE, 0x60F2C14E);
                g.drawString(font, l.libelle(), x + 6, y + 2, i == choix ? Client.BLANC : 0xDDDDDD);
                int w = font.width(l.libelle()) + 12;
                int place = Math.max(0, LARGEUR - w - 10);
                String d = font.width(l.detail()) <= place ? l.detail() : font.plainSubstrByWidth(l.detail(), Math.max(0, place - font.width("…"))) + "…";
                g.drawString(font, d, x + w, y + 2, Client.GRIS);
            }
            super.render(g, mx, my, pt);
            g.drawString(font, "↑ ↓ choisir · Entrée : donner l'ordre · Échap : fermer", x + 4, height - 12, Client.GRIS);
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {} // le monde reste visible, comme le chat

        @Override
        public boolean isPauseScreen() { return false; }
    }
}

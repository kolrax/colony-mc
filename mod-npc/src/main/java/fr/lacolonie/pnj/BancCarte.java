package fr.lacolonie.pnj;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

/**
 * Banc photo de la carte (plan D), pour le développement seulement ({@code ./gradlew runCarte}, propriété
 * {@code colonie.carte}, jamais chez un joueur) : Xaero y est chargé. Dans un monde solo, trois ouvriers du joueur (Jean,
 * Isabeau, Gauthier), un ouvrier d'un autre chef (Voisin) et un chercheur d'emploi (Errant) ; une capture de la
 * mini-carte, puis une de la carte du monde. Seuls les trois premiers doivent y être. Puis il quitte.
 */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class BancCarte {
    private BancCarte() {}

    static final boolean ACTIF = Boolean.getBoolean("colonie.carte");
    static final String MONDE = "carte";
    static int tick = -200, attente;
    static boolean cree;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ACTIF) return;
        Minecraft mc = Minecraft.getInstance();
        // Xaero peut montrer son propre écran au démarrage (une mise à jour à annoncer) : on attend 5 s sans chargement en cours.
        if (mc.level == null && mc.getOverlay() == null && ++attente % 100 == 0)
            ColoniePnj.LOG.info("banc carte : en attente, écran {}", mc.screen == null ? "aucun" : mc.screen.getClass().getName());
        if (!cree && mc.level == null && attente > 100 && !mc.getLevelSource().levelExists(MONDE)) {
            cree = true;
            mc.createWorldOpenFlows().createFreshLevel(MONDE, new net.minecraft.world.level.LevelSettings(MONDE, net.minecraft.world.level.GameType.CREATIVE, false,
                            net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                    new net.minecraft.world.level.levelgen.WorldOptions(20261019L, false, false),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        tick++;
        if (tick == -150) BancEcrans.serveur(BancCarte::scene);
        if (tick == 0) { // face à Jean (à l'est) : son repère dans le monde aussi, s'il y en a un
            mc.setScreen(null);
            mc.player.setYRot(-90);
            mc.player.setXRot(10);
        }
        if (tick == 120) photo(mc, "carte_1_minicarte.png");
        if (tick == 130 && CarteClient.XAERO) XaeroCarteDuMonde.ouvrir();
        if (tick == 260) photo(mc, "carte_2_monde.png");
        if (tick == 290) mc.stop();
    }

    static void photo(Minecraft mc, String nom) {
        ColoniePnj.LOG.info("banc carte : {} (écran {} ; Xaero {} ; reçus {} ; repères {})", nom, mc.screen == null ? "aucun" : mc.screen.getClass().getSimpleName(),
                CarteClient.XAERO, CarteClient.dernier.stream().map(Reperes.Repere::prenom).toList(),
                CarteClient.XAERO ? XaeroOuvriers.noms() : "-");
        Screenshot.grab(mc.gameDirectory, nom, mc.getMainRenderTarget(), m -> ColoniePnj.LOG.info("photo : {}", m.getString()));
    }

    /** Les ouvriers autour du joueur : trois à lui, un au voisin, un sans chef. */
    static void scene(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        l.setDayTime(6000);
        l.setWeatherParameters(60000, 0, false, false);
        BlockPos b = p.blockPosition();
        String[][] siens = {{"Jean", "mineur", "14", "0"}, {"Isabeau", "bucheron", "-24", "12"}, {"Gauthier", "fermier", "4", "-30"}};
        for (String[] s : siens) ouvrier(l, b, s[1], Integer.parseInt(s[2]), Integer.parseInt(s[3]), p.getUUID(), s[0]);
        ouvrier(l, b, "mineur", -6, -16, UUID.randomUUID(), "Voisin");
        Travailleur errant = ColoniePnj.TRAVAILLEUR.get().create(l);
        BlockPos q = sol(l, b.offset(10, 0, 18));
        errant.moveTo(q.getX() + 0.5, q.getY(), q.getZ() + 0.5, 0, 0);
        errant.tirerAuSort("mineur", 1);
        errant.prenom = "Errant";
        l.addFreshEntity(errant);
    }

    static void ouvrier(ServerLevel l, BlockPos b, String metier, int dx, int dz, UUID chef, String prenom) {
        BlockPos q = sol(l, b.offset(dx, 0, dz));
        Travailleur t = Travailleur.auTravail(l, metier, q, Vec3.atBottomCenterOf(q), chef, ItemStack.EMPTY);
        t.prenom = prenom;
    }

    static BlockPos sol(ServerLevel l, BlockPos p) {
        l.getChunk(p.getX() >> 4, p.getZ() >> 4);
        return l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
    }
}

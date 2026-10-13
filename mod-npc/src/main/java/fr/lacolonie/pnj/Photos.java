package fr.lacolonie.pnj;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Banc photo des gestes, pour le développement seulement (lancé par {@code ./gradlew runPhotos}, jamais chez un joueur :
 * il faut la propriété {@code colonie.photos}). Dans un monde solo, il aligne un ouvrier par geste, de face puis de
 * profil, prend une capture de chaque rangée (dossier screenshots du jeu) et quitte.
 */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class Photos {
    private Photos() {}

    static final boolean ACTIF = System.getProperty("colonie.photos") != null;
    static final int PAR_RANG = 4;
    static int tick = -1, rangee = -2; // le premier lot est pris deux fois : la première, le monde n'est pas encore prêt
    static final List<Entity> POSES = new ArrayList<>();
    static List<String> gestes;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ACTIF) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) return;
        if (gestes == null) {
            gestes = new ArrayList<>(new java.util.TreeSet<>(Repertoire.GESTES));
            // run-photos/gestes.txt : seulement ces gestes (pour revoir ceux qu'on vient de corriger).
            java.nio.file.Path choix = mc.gameDirectory.toPath().resolve("gestes.txt");
            try { if (java.nio.file.Files.exists(choix)) gestes.retainAll(java.nio.file.Files.readAllLines(choix).stream().map(String::strip).toList()); }
            catch (java.io.IOException ignore) {}
            tick = -100; // le temps que le monde soit prêt
        }
        tick++;
        int rangees = (gestes.size() + PAR_RANG - 1) / PAR_RANG;
        int vue = Math.floorMod(rangee, 2); // 0 : de face, 1 : de profil
        int lot = Math.max(0, rangee / 2);
        if (lot >= rangees) { if (tick > 20) mc.stop(); return; }
        if (tick == 1) {
            var serveur = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            List<String> l = gestes.subList(lot * PAR_RANG, Math.min(gestes.size(), (lot + 1) * PAR_RANG));
            serveur.execute(() -> preparer(serveur.overworld(), serveur.getPlayerList().getPlayer(uuid), l, vue));
        }
        if (tick == 90) {
            String nom = String.format("gestes_%02d_%s.png", lot, vue == 0 ? "face" : "profil");
            if (rangee >= 0) Screenshot.grab(mc.gameDirectory, nom, mc.getMainRenderTarget(), m -> ColoniePnj.LOG.info("photo : {}", m.getString()));
            rangee++;
            tick = 0;
        }
    }

    /** Une rangée d'ouvriers, chacun figé dans son geste, son nom au-dessus de la tête ; le joueur face à eux (ou de biais). */
    static void preparer(ServerLevel l, ServerPlayer p, List<String> gestes, int vue) {
        for (Entity x : POSES) x.discard();
        POSES.clear();
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); // rien en main, pas de barre d'objets : seulement les noms
        // Pas de chercheurs d'emploi qui traînent dans le champ.
        for (Travailleur t : l.getEntitiesOfClass(Travailleur.class, new net.minecraft.world.phys.AABB(9_950, -100, 9_950, 10_050, 400, 10_050))) t.discard();
        l.setDayTime(6000);
        l.setWeatherParameters(6000, 0, false, false);
        double x0 = 10_000, z0 = 10_000;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) l.getChunk(((int) x0 >> 4) + dx, ((int) z0 >> 4) + dz); // sinon, la hauteur du sol = le fond du monde
        int y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) x0, (int) z0);
        for (int i = 0; i < gestes.size(); i++) {
            Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(l);
            t.tirerAuSort(i % 3 == 0 ? "guerrier" : i % 3 == 1 ? "mineur" : "bucheron", 3);
            t.mode = Travailleur.Mode.ATTENDRE; // embauché : il porte la tenue de son métier
            t.majNom();
            float yaw = vue == 0 ? 180 : 90;
            t.moveTo(x0 + (i - (gestes.size() - 1) / 2.0) * 1.9, y, z0, yaw, 0);
            t.setYHeadRot(yaw);
            t.setYBodyRot(yaw);
            t.setNoAi(true);
            t.setCustomName(Component.literal(gestes.get(i)));
            t.setGeste(gestes.get(i));
            if (Set.of("en_garde", "salut_epee", "moulinet", "frapper").contains(gestes.get(i)))
                t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            if (gestes.get(i).equals("bouclier")) t.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            l.addFreshEntity(t);
            POSES.add(t);
        }
        // Le joueur au nord, à 4,5 blocs, regarde le sud : les ouvriers de face, ou de profil (tournés vers l'ouest).
        p.teleportTo(l, x0, y + 0.2, z0 - 4.6, 0, 14);
    }
}

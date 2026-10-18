package fr.lacolonie.pnj;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Plan D, chez le joueur : la dernière liste de ses ouvriers reçue du serveur ({@link CarteOuvriers}), passée à la carte
 * Xaero si elle est installée. Sans Xaero, rien n'est fait et aucune de ses classes n'est chargée.
 */
@EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
public final class CarteClient {
    private CarteClient() {}

    static final boolean XAERO = ModList.get().isLoaded("xaerominimap");
    static List<Reperes.Repere> dernier = List.of();

    static void recevoir(List<Reperes.Repere> liste) {
        dernier = liste;
        if (XAERO) XaeroOuvriers.afficher(liste);
    }

    /** La carte Xaero n'est pas prête dès la connexion, et change à chaque connexion : on la vérifie chaque seconde. */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!XAERO || net.minecraft.client.Minecraft.getInstance().level == null
                || net.minecraft.client.Minecraft.getInstance().level.getGameTime() % 20 != 0) return;
        XaeroOuvriers.verifier(dernier);
    }

    @SubscribeEvent
    static void deconnexion(ClientPlayerNetworkEvent.LoggingOut e) {
        dernier = List.of();
        if (XAERO) XaeroOuvriers.oublier();
    }
}

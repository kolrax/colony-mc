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

    /** Faux dès qu'un appel à Xaero a échoué (une autre version de Xaero, installée à la main) : on n'y touche plus. */
    static boolean carteOk = true;

    static void recevoir(List<Reperes.Repere> liste) {
        dernier = liste;
        if (XAERO && carteOk) proteger(() -> XaeroOuvriers.afficher(liste));
    }

    static void proteger(Runnable r) {
        try {
            r.run();
        } catch (Throwable ex) {
            carteOk = false;
            ColoniePnj.LOG.warn("Carte Xaero : repères des ouvriers arrêtés ({})", ex.toString());
        }
    }

    /** La carte Xaero n'est pas prête dès la connexion, et change à chaque connexion : on la vérifie chaque seconde. */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!XAERO || !carteOk || net.minecraft.client.Minecraft.getInstance().level == null
                || net.minecraft.client.Minecraft.getInstance().level.getGameTime() % 20 != 0) return;
        proteger(() -> XaeroOuvriers.verifier(dernier));
    }

    @SubscribeEvent
    static void deconnexion(ClientPlayerNetworkEvent.LoggingOut e) {
        dernier = List.of();
        if (XAERO && carteOk) proteger(XaeroOuvriers::oublier);
    }
}

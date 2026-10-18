package fr.lacolonie.pnj;

import net.minecraft.client.Minecraft;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import xaero.map.mods.SupportMods;

/** Plan D : la carte du monde de Xaero. Chargée seulement si elle est installée ({@link XaeroOuvriers}, {@link BancCarte}). */
final class XaeroCarteDuMonde {
    private XaeroCarteDuMonde() {}

    /** Si elle est ouverte, elle relit ses repères (un ouvrier de plus ou de moins). */
    static void rafraichir() {
        if (SupportMods.minimap()) SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }

    /** Le banc de la carte l'ouvre, comme la touche M. */
    static void ouvrir() {
        WorldMapSession s = WorldMapSession.getCurrentSession();
        Minecraft mc = Minecraft.getInstance();
        if (s == null) return;
        try { // par réflexion : l'écran de Xaero hérite d'une classe de xaerolib, absente à la compilation
            Object ecran = GuiMap.class.getConstructors()[0].newInstance(null, null, s.getMapProcessor(), mc.getCameraEntity());
            mc.setScreen((net.minecraft.client.gui.screens.Screen) ecran);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ColoniePnj.LOG.warn("banc carte : carte du monde pas ouverte ({})", e.toString());
        }
    }
}

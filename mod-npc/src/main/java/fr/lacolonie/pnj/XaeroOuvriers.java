package fr.lacolonie.pnj;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan D : les ouvriers du joueur en repères Xaero (mini-carte et carte du monde), comme Xaero le fait lui-même pour les
 * pierres de passage (« third-party waypoints ») : pas enregistrés sur le disque, pas de radar d'entités, rien à régler
 * dans Xaero. La seule classe du mod qui touche à Xaero : appelée seulement si Xaero's Minimap est là ({@link CarteClient}).
 * Un repère par ouvrier, gardé et déplacé à chaque liste (la carte du monde lit sa place en direct).
 */
final class XaeroOuvriers {
    private XaeroOuvriers() {}

    static final ResourceLocation ORIGINE = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "ouvriers");

    /** Où est rangé chaque repère (son groupe dépend de la dimension de l'ouvrier). */
    record Place(ThirdPartyWaypoints groupe, Waypoint repere) {}

    static final Map<String, Place> AFFICHES = new HashMap<>();
    static MinimapSession session;

    /** Appelée chaque seconde : une nouvelle session Xaero (reconnexion) n'a plus nos repères, on les remet. */
    static void verifier(List<Reperes.Repere> liste) {
        MinimapSession s = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (s != null && s != session && !liste.isEmpty()) afficher(liste);
    }

    /** Les noms des repères affichés (le banc de la carte les écrit dans le journal). */
    static List<String> noms() { return AFFICHES.values().stream().map(p -> p.repere().getName()).sorted().toList(); }

    static void oublier() {
        AFFICHES.clear();
        session = null;
    }

    static void afficher(List<Reperes.Repere> liste) {
        MinimapSession s = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (s == null) return; // pas encore prête : verifier() réessaiera
        MinimapWorldRootContainer racine = s.getWorldManager().getAutoRootContainer();
        if (racine == null) return;
        if (s != session) oublier(); // les repères de l'ancienne session sont partis avec elle
        boolean change = false;
        Set<String> vus = new HashSet<>();
        for (Reperes.Repere r : liste) {
            ResourceLocation dim = ResourceLocation.tryParse(r.dim());
            if (dim == null) continue;
            String noeud = s.getDimensionHelper().getDimensionDirectoryName(ResourceKey.create(Registries.DIMENSION, dim));
            MinimapWorldContainer conteneur = racine.addSubContainer(racine.getPath().resolve(noeud));
            if (conteneur.getThirdPartyWaypointManager() == null) continue;
            ThirdPartyWaypoints groupe = conteneur.getThirdPartyWaypointManager().get(ORIGINE);
            String id = r.id().toString();
            vus.add(id);
            Place p = AFFICHES.get(id);
            if (p != null && p.groupe() == groupe && groupe.get(id) == p.repere()) {
                Waypoint w = p.repere(); // déjà là : on le déplace, sans rien réécrire
                w.setX(r.x());
                w.setY(r.y());
                w.setZ(r.z());
                if (!w.getName().equals(r.prenom())) {
                    w.setName(r.prenom());
                    w.setInitials(Reperes.initiale(r.prenom()));
                }
                continue;
            }
            if (p != null) p.groupe().remove(id); // il a changé de dimension
            Waypoint w = new Waypoint(r.x(), r.y(), r.z(), r.prenom(), Reperes.initiale(r.prenom()), WaypointColor.GOLD, WaypointPurpose.NORMAL);
            groupe.add(id, w);
            AFFICHES.put(id, new Place(groupe, w));
            change = true;
        }
        for (var it = AFFICHES.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (vus.contains(e.getKey())) continue;
            e.getValue().groupe().remove(e.getKey()); // licencié, mort ou parti : son repère s'efface
            it.remove();
            change = true;
        }
        session = s;
        if (change && ModList.get().isLoaded("xaeroworldmap")) XaeroCarteDuMonde.rafraichir();
    }
}

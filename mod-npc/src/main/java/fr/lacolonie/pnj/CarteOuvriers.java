package fr.lacolonie.pnj;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Plan D : chaque chef voit ses propres ouvriers sur sa carte (Xaero). Toutes les 2 secondes, le serveur envoie à chaque
 * joueur équipé du mod la place de SES ouvriers, et seulement si elle a changé ({@link Reperes}). Rien aux autres joueurs.
 * Le paquet est facultatif : un client sans lui se connecte quand même, il ne voit simplement pas ses ouvriers sur la carte.
 */
@EventBusSubscriber(modid = ColoniePnj.MODID)
public final class CarteOuvriers {
    private CarteOuvriers() {}

    /** La liste des ouvriers d'un chef, pour sa carte (serveur vers joueur). Vide : effacer ses repères. */
    public record Paquet(List<Reperes.Repere> liste) implements CustomPacketPayload {
        /** Sept champs : au-delà des six de {@code StreamCodec.composite}, écrits à la main (version 2 du registre). */
        static final StreamCodec<RegistryFriendlyByteBuf, Reperes.Repere> REPERE = StreamCodec.of((b, r) -> {
            UUIDUtil.STREAM_CODEC.encode(b, r.id());
            b.writeUtf(r.prenom(), 64);
            b.writeUtf(r.dim(), 128);
            b.writeVarInt(r.x());
            b.writeVarInt(r.y());
            b.writeVarInt(r.z());
            b.writeBoolean(r.aTerre());
        }, b -> new Reperes.Repere(UUIDUtil.STREAM_CODEC.decode(b), b.readUtf(64), b.readUtf(128), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readBoolean()));
        static final Type<Paquet> TYPE = Reseau.typeDe("reperes2");
        static final StreamCodec<RegistryFriendlyByteBuf, Paquet> CODEC = StreamCodec.composite(
                REPERE.apply(ByteBufCodecs.list(Reperes.MAX)), Paquet::liste, Paquet::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Son propre registre, facultatif et à sa propre version : il ne change pas le canal du mod ({@code Reseau.registrar}),
     * et un ancien client (sans ce paquet) peut encore se connecter. Branché dans {@link ColoniePnj}, à côté de {@link Reseau}.
     * Version 2 (plan E, point 5 : le repère dit s'il est à terre), sous un nouveau nom, « reperes2 » : NeoForge refuse la
     * connexion si un même paquet, même facultatif, a deux versions différentes ; un nom nouveau est simplement ignoré par un
     * client de la version 1 (et « reperes » par ce client-ci), qui se connecte encore, sans les repères.
     */
    static void enregistrer(RegisterPayloadHandlersEvent e) {
        e.registrar("2").optional().playToClient(Paquet.TYPE, Paquet.CODEC, (p, ctx) -> CarteClient.recevoir(p.liste()));
    }

    /** Ce qui a été envoyé à chaque joueur (un nouveau ServerPlayer à chaque connexion : tout repart à la reconnexion). */
    static final Map<ServerPlayer, List<Reperes.Repere>> ENVOYE = new WeakHashMap<>();

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % Reperes.PERIODE != 0) return;
        envoyer(e.getServer(), e.getServer().getPlayerList().getPlayers());
    }

    static boolean equipe(ServerPlayer p) { return p.connection != null && p.connection.hasChannel(Paquet.TYPE); }

    /** Un tour : la liste de chaque joueur équipé, envoyée si elle a changé. Les faux joueurs des tests sont ignorés. */
    static void envoyer(MinecraftServer s, Collection<ServerPlayer> joueurs) {
        List<ServerPlayer> equipes = new ArrayList<>();
        for (ServerPlayer p : joueurs) if (equipe(p)) equipes.add(p);
        if (equipes.isEmpty()) return; // personne à servir : pas même la liste des ouvriers
        List<Reperes.Ouvrier> tous = new ArrayList<>();
        for (Travailleur t : ColoniePnj.tous(s)) {
            if (t.chef == null || t.mortDefinitive) continue;
            var b = t.blockPosition();
            tous.add(new Reperes.Ouvrier(t.chef, new Reperes.Repere(t.getUUID(), court(t.prenom),
                    t.level().dimension().location().toString(), b.getX(), b.getY(), b.getZ(), t.aTerre)));
        }
        Map<java.util.UUID, List<Reperes.Repere>> parChef = Reperes.parChef(tous);
        for (ServerPlayer p : equipes) {
            List<Reperes.Repere> l = parChef.getOrDefault(p.getUUID(), List.of());
            if (!Reperes.aEnvoyer(ENVOYE.get(p), l)) continue;
            ENVOYE.put(p, l);
            PacketDistributor.sendToPlayer(p, new Paquet(l));
        }
    }

    /** Le paquet n'admet que 64 caractères par prénom (un nom donné au panneau web peut être plus long). */
    static String court(String prenom) { return prenom == null ? "" : prenom.length() > 60 ? prenom.substring(0, 60) : prenom; }
}

package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Deux messages : le serveur montre la fiche d'un PNJ, le joueur répond par un ordre. */
public final class Reseau {
    private Reseau() {}

    public record Fiche(int id, CompoundTag infos) implements CustomPacketPayload {
        static final Type<Fiche> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "fiche"));
        static final StreamCodec<RegistryFriendlyByteBuf, Fiche> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Fiche::id, ByteBufCodecs.COMPOUND_TAG, Fiche::infos, Fiche::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Commande(int id, String ordre) implements CustomPacketPayload {
        static final Type<Commande> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "commande"));
        static final StreamCodec<RegistryFriendlyByteBuf, Commande> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Commande::id, ByteBufCodecs.stringUtf8(32), Commande::ordre, Commande::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void enregistrer(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1");
        r.playToClient(Fiche.TYPE, Fiche.CODEC, (p, ctx) -> Client.ouvrirFiche(p));
        r.playToServer(Commande.TYPE, Commande.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().getEntity(p.id()) instanceof Travailleur t) t.commande(sp, p.ordre());
        });
    }
}

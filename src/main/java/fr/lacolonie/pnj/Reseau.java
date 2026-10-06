package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Les messages : la fiche d'un PNJ et ses ordres, l'écran d'un bureau et ses boutons. */
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

    /** L'écran de gestion d'un bureau (serveur vers joueur). */
    public record FicheBureau(net.minecraft.core.BlockPos pos, CompoundTag infos) implements CustomPacketPayload {
        static final Type<FicheBureau> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "fiche_bureau"));
        static final StreamCodec<RegistryFriendlyByteBuf, FicheBureau> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, FicheBureau::pos, ByteBufCodecs.COMPOUND_TAG, FicheBureau::infos, FicheBureau::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Un bouton de l'écran du bureau (joueur vers serveur). */
    public record ActionBureau(net.minecraft.core.BlockPos pos, String action, CompoundTag donnees) implements CustomPacketPayload {
        static final Type<ActionBureau> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "action_bureau"));
        static final StreamCodec<RegistryFriendlyByteBuf, ActionBureau> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, ActionBureau::pos, ByteBufCodecs.stringUtf8(32), ActionBureau::action,
                ByteBufCodecs.COMPOUND_TAG, ActionBureau::donnees, ActionBureau::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void enregistrer(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("2"); // 2 : écran du bureau (0.2.0)
        r.playToClient(Fiche.TYPE, Fiche.CODEC, (p, ctx) -> Client.ouvrirFiche(p));
        r.playToClient(FicheBureau.TYPE, FicheBureau.CODEC, (p, ctx) -> Client.ouvrirBureau(p));
        r.playToServer(ActionBureau.TYPE, ActionBureau.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().isLoaded(p.pos()) && sp.level().getBlockEntity(p.pos()) instanceof Bureau.Entite b)
                b.action(sp, p.action(), p.donnees());
        });
        r.playToServer(Commande.TYPE, Commande.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().getEntity(p.id()) instanceof Travailleur t) t.commande(sp, p.ordre());
        });
    }
}

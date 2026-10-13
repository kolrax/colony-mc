package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Les messages : l'entretien d'embauche, la conversation et la parole, l'écran du poste et ses boutons. */
public final class Reseau {
    private Reseau() {}

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeDe(String nom) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, nom));
    }

    /** L'entretien d'embauche d'un chercheur d'emploi (serveur vers joueur). */
    public record Fiche(int id, CompoundTag infos) implements CustomPacketPayload {
        static final Type<Fiche> TYPE = typeDe("fiche");
        static final StreamCodec<RegistryFriendlyByteBuf, Fiche> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Fiche::id, ByteBufCodecs.COMPOUND_TAG, Fiche::infos, Fiche::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Un bouton de l'entretien (« embaucher »), joueur vers serveur. */
    public record Commande(int id, String ordre) implements CustomPacketPayload {
        static final Type<Commande> TYPE = typeDe("commande");
        static final StreamCodec<RegistryFriendlyByteBuf, Commande> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Commande::id, ByteBufCodecs.stringUtf8(32), Commande::ordre, Commande::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Ouvre la fenêtre de conversation avec un de ses ouvriers (serveur vers joueur). */
    public record Conversation(int id, CompoundTag infos) implements CustomPacketPayload {
        static final Type<Conversation> TYPE = typeDe("conversation");
        static final StreamCodec<RegistryFriendlyByteBuf, Conversation> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Conversation::id, ByteBufCodecs.COMPOUND_TAG, Conversation::infos, Conversation::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Ce que le joueur dit à son ouvrier (joueur vers serveur). */
    public record Parler(int id, String texte) implements CustomPacketPayload {
        static final Type<Parler> TYPE = typeDe("parler");
        static final StreamCodec<RegistryFriendlyByteBuf, Parler> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Parler::id, ByteBufCodecs.stringUtf8(256), Parler::texte, Parler::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Ce que dit un PNJ : une bulle au-dessus de sa tête, et la fenêtre de conversation (serveur vers joueur). */
    public record Parole(int id, String texte) implements CustomPacketPayload {
        static final Type<Parole> TYPE = typeDe("parole");
        static final StreamCodec<RegistryFriendlyByteBuf, Parole> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Parole::id, ByteBufCodecs.stringUtf8(2048), Parole::texte, Parole::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** L'écran du poste de travail (serveur vers joueur). */
    public record Poste(net.minecraft.core.BlockPos pos, CompoundTag infos) implements CustomPacketPayload {
        static final Type<Poste> TYPE = typeDe("poste");
        static final StreamCodec<RegistryFriendlyByteBuf, Poste> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, Poste::pos, ByteBufCodecs.TRUSTED_COMPOUND_TAG, Poste::infos, Poste::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Un bouton de l'écran du poste (joueur vers serveur). */
    public record ActionBureau(net.minecraft.core.BlockPos pos, String action, CompoundTag donnees) implements CustomPacketPayload {
        static final Type<ActionBureau> TYPE = typeDe("action_bureau");
        static final StreamCodec<RegistryFriendlyByteBuf, ActionBureau> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, ActionBureau::pos, ByteBufCodecs.stringUtf8(32), ActionBureau::action,
                ByteBufCodecs.COMPOUND_TAG, ActionBureau::donnees, ActionBureau::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void enregistrer(RegisterPayloadHandlersEvent e) {
        // 2 : écran du bureau (0.2.0) ; 3 : sorties (0.3.0) ; 4 : statut au bureau (0.4.0) ; 5 : poste commun, lettres, conversation (0.5.0) ;
        // 6 : les gestes des ouvriers (0.8.0, une donnée de plus sur l'entité : un ancien client ne pourrait pas la lire)
        // 7 : la tenue des ouvriers (lot 8, après la 0.9.6 : encore une donnée sur l'entité)
        // 8 : rôle et état au-dessus des têtes (lot 8 : deux données de plus sur l'entité)
        var r = e.registrar("8");
        r.playToClient(Fiche.TYPE, Fiche.CODEC, (p, ctx) -> Client.ouvrirFiche(p));
        r.playToClient(Conversation.TYPE, Conversation.CODEC, (p, ctx) -> Client.ouvrirConversation(p));
        r.playToClient(Parole.TYPE, Parole.CODEC, (p, ctx) -> Client.parole(p));
        r.playToClient(Poste.TYPE, Poste.CODEC, (p, ctx) -> Client.ouvrirPoste(p));
        r.playToServer(ActionBureau.TYPE, ActionBureau.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().isLoaded(p.pos()) && sp.level().getBlockEntity(p.pos()) instanceof Bureau.Entite b)
                b.action(sp, p.action(), p.donnees());
        });
        r.playToServer(Commande.TYPE, Commande.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().getEntity(p.id()) instanceof Travailleur t) t.commande(sp, p.ordre());
        });
        r.playToServer(Parler.TYPE, Parler.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.level().getEntity(p.id()) instanceof Travailleur t) t.parler(sp, p.texte());
        });
    }
}

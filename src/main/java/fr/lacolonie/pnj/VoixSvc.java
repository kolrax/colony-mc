package fr.lacolonie.pnj;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le branchement à Simple Voice Chat (trouvé par lui grâce à l'annotation ; jamais chargé s'il est absent).
 * Il écoute les micros (pour {@link Voix}) et joue la voix des PNJ depuis leur position, dans une catégorie
 * « PNJ » dont chaque joueur règle le volume dans les réglages de Simple Voice Chat.
 */
@ForgeVoicechatPlugin
public class VoixSvc implements VoicechatPlugin {
    static final String CATEGORIE = "colonie_pnj";
    static volatile VoicechatServerApi api;
    static final Map<Integer, EntityAudioChannel> CANAUX = new ConcurrentHashMap<>();
    static final Map<Integer, AudioPlayer> EN_TRAIN = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() { return ColoniePnj.MODID; }

    @Override
    public void registerEvents(EventRegistration r) {
        r.registerEvent(VoicechatServerStartedEvent.class, e -> {
            api = e.getVoicechat();
            try {
                api.registerVolumeCategory(api.volumeCategoryBuilder().setId(CATEGORIE).setName("PNJ")
                        .setDescription("La voix des ouvriers de La Colonie").build());
            } catch (RuntimeException ex) {
                ColoniePnj.LOG.warn("Simple Voice Chat : catégorie de volume « PNJ » non créée", ex);
            }
            Voix.pret = true;
            ColoniePnj.LOG.info("Simple Voice Chat prêt : les PNJ peuvent parler à voix haute et écouter leur chef");
        });
        r.registerEvent(VoicechatServerStoppedEvent.class, e -> {
            Voix.pret = false;
            api = null;
            CANAUX.clear();
            EN_TRAIN.clear();
        });
        r.registerEvent(MicrophonePacketEvent.class, e -> {
            VoicechatConnection c = e.getSenderConnection();
            if (c == null || c.getPlayer() == null || c.isInGroup()) return; // en groupe, sa voix ne va qu'au groupe : ses ouvriers ne l'entendent pas
            if (c.getPlayer().getPlayer() instanceof net.minecraft.server.level.ServerPlayer p)
                Voix.paquet(p.getUUID(), e.getPacket().getOpusEncodedData());
        });
    }

    /** Joue ce son (48 kHz, mono) depuis le PNJ ; s'il parlait déjà, il s'interrompt. */
    static void jouer(Travailleur t, short[] pcm) {
        VoicechatServerApi a = api;
        if (a == null || pcm.length == 0) return;
        AudioPlayer avant = EN_TRAIN.remove(t.getId());
        if (avant != null) avant.stopPlaying();
        EntityAudioChannel canal = CANAUX.get(t.getId());
        if (canal == null || canal.isClosed()) {
            canal = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(t));
            if (canal == null) return;
            canal.setCategory(CATEGORIE);
            canal.setDistance(24f);
            CANAUX.put(t.getId(), canal);
        }
        OpusEncoder encodeur = a.createEncoder();
        AudioPlayer[] lecteur = new AudioPlayer[1];
        lecteur[0] = a.createAudioPlayer(canal, encodeur, pcm);
        lecteur[0].setOnStopped(() -> {
            encodeur.close();
            EN_TRAIN.remove(t.getId(), lecteur[0]);
        });
        EN_TRAIN.put(t.getId(), lecteur[0]);
        lecteur[0].startPlaying();
    }

    /** Il parle en ce moment (pour les tests). */
    static boolean parle(Travailleur t) {
        AudioPlayer p = EN_TRAIN.get(t.getId());
        return p != null && p.isPlaying();
    }

    /** Des trames Opus d'un micro, en son 48 kHz mono. */
    static short[] decoder(List<byte[]> trames) {
        VoicechatServerApi a = api;
        if (a == null) return new short[0];
        OpusDecoder d = a.createDecoder();
        try {
            List<short[]> morceaux = new ArrayList<>();
            for (byte[] f : trames) if (f != null && f.length > 0) morceaux.add(d.decode(f));
            return Audio.coller(morceaux);
        } finally {
            d.close();
        }
    }

    /** Encode puis décode (par trames de 20 ms), comme un micro : pour vérifier en test que l'Opus de Simple Voice Chat fonctionne. */
    static short[] allerRetour(short[] pcm) {
        VoicechatServerApi a = api;
        OpusEncoder enc = a.createEncoder();
        try {
            List<byte[]> trames = new ArrayList<>();
            for (int i = 0; i + 960 <= pcm.length; i += 960) {
                short[] f = new short[960];
                System.arraycopy(pcm, i, f, 0, 960);
                trames.add(enc.encode(f));
            }
            return decoder(trames);
        } finally {
            enc.close();
        }
    }
}

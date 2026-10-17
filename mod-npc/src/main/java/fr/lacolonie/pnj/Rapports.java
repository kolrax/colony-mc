package fr.lacolonie.pnj;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** /rapport : un joueur signale un souci ; une ligne JSON par rapport dans config/colonie_pnj/rapports.jsonl, lue par les admins. */
final class Rapports {
    private Rapports() {}

    private static final Map<UUID, Long> DERNIER = new HashMap<>();
    private static int numero = -1;

    /** L'ouvrier que le joueur regarde, sinon le plus proche, à 5 blocs au plus. */
    static Travailleur pnjVise(ServerPlayer p) {
        Vec3 oeil = p.getEyePosition(), regard = p.getLookAngle();
        var proches = p.serverLevel().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(5), t -> t.distanceToSqr(p) <= 25);
        return proches.stream().filter(t -> {
            Vec3 v = t.getEyePosition().subtract(oeil);
            return v.length() > 0.01 && v.normalize().dot(regard) > 0.95;
        }).findFirst().orElse(proches.stream().min(Comparator.comparingDouble(t -> t.distanceToSqr(p))).orElse(null));
    }

    /** Écrit le rapport ; renvoie le message à montrer au joueur. */
    static synchronized String envoyer(ServerPlayer p, String texte) {
        long maintenant = System.currentTimeMillis();
        String refus = Rapport.refus(texte, DERNIER.getOrDefault(p.getUUID(), 0L), maintenant);
        if (refus != null) return refus;
        Travailleur t = pnjVise(p);
        JsonObject pnj = null;
        if (t != null) {
            pnj = new JsonObject();
            pnj.addProperty("prenom", t.prenom);
            pnj.addProperty("metier", t.metier);
            pnj.addProperty("etat", t.statut());
            pnj.addProperty("chef", t.chefNom);
        }
        String version = ModList.get().getModContainerById(ColoniePnj.MODID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        String ligne = Rapport.ligne(Instant.now().toString(), p.getName().getString(), p.level().dimension().location().toString(),
                p.getBlockX(), p.getBlockY(), p.getBlockZ(), version, texte.strip(), pnj);
        Path f = Reglages.dossier().resolve("rapports.jsonl");
        try {
            Files.createDirectories(f.getParent());
            if (numero < 0) {
                numero = 0;
                if (Files.exists(f)) try (var l = Files.lines(f, StandardCharsets.UTF_8)) { numero = (int) l.count(); }
            }
            Files.writeString(f, ligne + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ColoniePnj.LOG.error("rapport non écrit", e);
            return "Le rapport n'a pas pu être enregistré. Préviens un admin.";
        }
        DERNIER.put(p.getUUID(), maintenant);
        numero++;
        ColoniePnj.LOG.info("[rapport] {} : {}", p.getName().getString(), texte.strip());
        return "Merci, rapport n°" + numero + " transmis à l'Intendant.";
    }
}

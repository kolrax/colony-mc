package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Le corbeau voyageur qui porte les lettres. On le voit s'envoler de chez l'expéditeur, puis arriver chez le
 * destinataire (au poste, ou près de l'ouvrier au loin), se poser, laisser la lettre et repartir. Entre les deux, son
 * vol est seulement compté par {@link Courrier} (15 blocs par seconde) : le faire voler pour de vrai chargerait et
 * générerait tout le terrain sous lui. Il n'est pas sauvegardé : la lettre, elle, l'est ({@link Courrier.Vol}).
 */
public class Corbeau extends Entity {
    static final EntityDataAccessor<Boolean> POSE = SynchedEntityData.defineId(Corbeau.class, EntityDataSerializers.BOOLEAN);
    static final EntityDataAccessor<Boolean> LETTRE = SynchedEntityData.defineId(Corbeau.class, EntityDataSerializers.BOOLEAN);

    enum Phase { DEPART, APPROCHE, POSE, REPART }

    Phase phase = Phase.REPART;
    Vec3 but = Vec3.ZERO, cap = new Vec3(1, 0, 0);
    int temps;
    /** La lettre qu'il livre en se posant (son identifiant dans le courrier) ; -1 s'il n'en livre pas. */
    long lettre = -1;

    public Corbeau(EntityType<? extends Corbeau> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(POSE, false);
        b.define(LETTRE, true);
    }

    public boolean pose() { return entityData.get(POSE); }

    public boolean porteLettre() { return entityData.get(LETTRE); }

    @Override
    protected void readAdditionalSaveData(CompoundTag t) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag t) {}

    @Override
    public boolean hurt(DamageSource src, float amount) { return false; }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        temps++;
        switch (phase) {
            case DEPART -> {
                // Il s'élève et s'éloigne dans la direction du destinataire, puis sort de la vue.
                voler(position().add(cap.scale(3)).add(0, 1.2, 0), Math.min(0.6, 0.15 + temps * 0.01));
                if (temps > 110) discard();
            }
            case APPROCHE -> {
                double d = position().distanceTo(but);
                voler(but, Math.min(0.55, d * 0.12 + 0.04));
                if (d < 0.25 || temps > 400) {
                    setPos(but);
                    phase = Phase.POSE;
                    temps = 0;
                    entityData.set(POSE, true);
                    level().playSound(null, blockPosition(), SoundEvents.PARROT_AMBIENT, SoundSource.NEUTRAL, 1.0f, 0.5f);
                }
            }
            case POSE -> {
                setDeltaMovement(Vec3.ZERO);
                if (temps == 20 && lettre >= 0 && level() instanceof ServerLevel sl) {
                    entityData.set(LETTRE, false);
                    level().playSound(null, blockPosition(), SoundEvents.BOOK_PUT, SoundSource.NEUTRAL, 1.0f, 1.2f);
                    Courrier.livrer(sl.getServer(), lettre);
                    lettre = -1;
                }
                if (temps == 30) level().playSound(null, blockPosition(), SoundEvents.PARROT_AMBIENT, SoundSource.NEUTRAL, 0.8f, 0.45f);
                if (temps > 45) {
                    phase = Phase.REPART;
                    temps = 0;
                    entityData.set(POSE, false);
                    level().playSound(null, blockPosition(), SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 1.0f, 0.8f);
                }
            }
            case REPART -> {
                voler(position().add(cap.scale(-3)).add(0, 1.5, 0), Math.min(0.6, 0.15 + temps * 0.01));
                if (temps > 90) discard();
            }
        }
    }

    /** Un pas de vol vers {@code cible}, à {@code vitesse} blocs par tick, en regardant où il va. */
    void voler(Vec3 cible, double vitesse) {
        Vec3 d = cible.subtract(position());
        if (d.lengthSqr() < 1e-4) return;
        Vec3 pas = d.normalize().scale(Math.min(vitesse, d.length()));
        setDeltaMovement(pas);
        setPos(position().add(pas));
        float yaw = (float) (Mth.atan2(pas.z, pas.x) * Mth.RAD_TO_DEG) - 90f;
        setYRot(Mth.rotLerp(0.3f, getYRot(), yaw));
        setXRot((float) -(Mth.atan2(pas.y, pas.horizontalDistance()) * Mth.RAD_TO_DEG) * 0.5f);
        if (tickCount % 12 == 0 && phase != Phase.POSE) level().playSound(null, blockPosition(), SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 0.5f, 0.8f);
    }

    static boolean temoin(Level l, BlockPos p) { return l.getNearestPlayer(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 64, false) != null; }

    /** Le corbeau s'envole de chez l'expéditeur (seulement si quelqu'un est là pour le voir). */
    static void envol(Level l, BlockPos depart, BlockPos arrivee, Courrier.Lettre lettre) {
        if (!(l instanceof ServerLevel sl) || !temoin(l, depart) || !sl.isPositionEntityTicking(depart)) return; // figé là, il resterait suspendu
        Corbeau c = ColoniePnj.CORBEAU.get().create(l);
        if (c == null) return;
        Vec3 cap = Vec3.atCenterOf(arrivee).subtract(Vec3.atCenterOf(depart)).multiply(1, 0, 1);
        c.cap = cap.lengthSqr() < 1 ? new Vec3(1, 0, 0) : cap.normalize();
        c.phase = Phase.DEPART;
        c.moveTo(depart.getX() + 0.5, depart.getY() + 1.9, depart.getZ() + 0.5, (float) (Mth.atan2(c.cap.z, c.cap.x) * Mth.RAD_TO_DEG) - 90f, 0);
        l.addFreshEntity(c);
        l.playSound(null, depart, SoundEvents.PARROT_FLY, SoundSource.NEUTRAL, 1.0f, 0.8f);
    }

    /**
     * Le corbeau arrive chez le destinataire : il approche, se pose et laisse la lettre (qu'il livre lui-même en se
     * posant). Faux s'il n'y a personne pour le voir : la lettre est alors livrée tout de suite.
     * <p>
     * Il arrive de 36 blocs en arrière et 24 plus haut, mais jamais d'un tronçon où les entités ne bougent pas (hors
     * de la distance de simulation, ou pas chargé) : il y resterait suspendu, et la lettre avec lui jusqu'au secours
     * de {@link Courrier#tick} (30 s). Il part alors du point le plus lointain d'où tout son trajet est actif.
     */
    static boolean arrivee(Level l, BlockPos depart, BlockPos dest, boolean surLePoste, long lettre) {
        if (!(l instanceof ServerLevel sl) || !l.isLoaded(dest) || !temoin(l, dest)) return false;
        Corbeau c = ColoniePnj.CORBEAU.get().create(l);
        if (c == null) return false;
        Vec3 cap = Vec3.atCenterOf(dest).subtract(Vec3.atCenterOf(depart)).multiply(1, 0, 1);
        c.cap = cap.lengthSqr() < 1 ? new Vec3(1, 0, 0) : cap.normalize();
        c.but = surLePoste ? perchoir(l, dest) : Vec3.atBottomCenterOf(dest).add(-c.cap.x * 1.2, 0, -c.cap.z * 1.2);
        if (!sl.isPositionEntityTicking(BlockPos.containing(c.but))) return false;
        c.phase = Phase.APPROCHE;
        c.lettre = lettre;
        Vec3 debut = c.but;
        for (int k = 4; k <= 36; k += 4) { // un point tous les 4 blocs : un tronçon en fait 16
            Vec3 p = c.but.add(c.cap.scale(-k)).add(0, k * 24.0 / 36, 0);
            if (!sl.isPositionEntityTicking(BlockPos.containing(p))) break;
            debut = p;
        }
        c.moveTo(debut.x, debut.y, debut.z, (float) (Mth.atan2(c.cap.z, c.cap.x) * Mth.RAD_TO_DEG) - 90f, 0);
        return l.addFreshEntity(c);
    }

    /** Où il se pose : sur le poste s'il est à ciel ouvert, sinon au plus près dehors. */
    static Vec3 perchoir(Level l, BlockPos poste) {
        if (l.canSeeSky(poste.above())) return new Vec3(poste.getX() + 0.5, poste.getY() + 1.0, poste.getZ() + 0.5);
        for (int r = 1; r <= 6; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = poste.getX() + dx, z = poste.getZ() + dz, y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (Math.abs(y - poste.getY()) <= 4 && l.canSeeSky(new BlockPos(x, y, z))) return new Vec3(x + 0.5, y, z + 0.5);
                }
        return new Vec3(poste.getX() + 0.5, poste.getY() + 1.0, poste.getZ() + 0.5);
    }
}

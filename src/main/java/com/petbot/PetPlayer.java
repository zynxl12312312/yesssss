package com.petbot;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A purely client-side fake player. The server never knows it exists.
 * It walks with real collision using smoothed A* paths, jumps up steps like a player (unless /petnojump),
 * can stand in rings around its owner (/petcircle), and teleports to its owner when it is more than
 * 20 blocks away, falls out of the world, or gets stuck.
 */
public class PetPlayer extends OtherClientPlayerEntity {
    /** Model scale. Baby villager is ~0.975 blocks tall, player is 1.8 -> ~0.54 */
    public static final float SCALE = 0.54F;

    private static final double TELEPORT_DISTANCE_SQ = 20.0 * 20.0;
    private static final double SEPARATION = 0.8;
    private static final double PET_EYE_HEIGHT = 0.88;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    private final PetData data;
    private final ClientWorld petWorld;

    private double vy = 0.0;
    private double hvx = 0.0; // horizontal velocity, eased so movement accelerates / slows down like a person
    private double hvz = 0.0;
    private boolean moving = false;
    private List<Vec3d> path = null;
    private int pathIdx = 0;
    private int repathTimer;
    private int stuckTicks = 0;
    private int insideTicks = 0;
    private int gearApplied = -1;

    public PetPlayer(ClientWorld world, PetData data) {
        // every pet needs its own UUID, otherwise the world refuses to add a second one
        super(world, new GameProfile(UUID.randomUUID(), "petbot"));
        this.data = data;
        this.petWorld = world;
        this.repathTimer = ThreadLocalRandom.current().nextInt(15); // spread path planning over several ticks
    }

    public PetData getData() {
        return data;
    }

    /** Name shown on the name tag (/petname). */
    @Override
    public Text getName() {
        return data.name;
    }

    /**
     * Picks the arm model (wide/slim) for this pet. The texture itself is replaced
     * in PlayerEntityRendererMixin.
     */
    @Override
    public SkinTextures getSkin() {
        SkinTextures base = super.getSkin();
        return SkinTextures.create(base.body(), base.cape(), base.elytra(), data.model);
    }

    private PlayerEntity getOwner() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (data.owner == null) return mc.player;
        return mc.world == null ? null : mc.world.getPlayerByUuid(data.owner);
    }

    /** Puts the armor / items from /petwardrobe on the pet (for show only). */
    private void applyGear() {
        if (this.gearApplied == data.gearVersion) return;
        this.gearApplied = data.gearVersion;
        for (int i = 0; i < Wardrobe.SLOTS.length; i++) {
            this.equipStack(Wardrobe.SLOTS[i], Wardrobe.build(petWorld, i, data.gear[i]));
        }
    }

    /**
     * Puts the pet on solid ground behind its owner at the pet's range, spread out a bit by slot number.
     * With strict = true it fails (returns false) instead of falling back to the owner's exact position.
     */
    private boolean placeNearOwner(PlayerEntity p, int slot, boolean strict) {
        double side = (slot % 2 == 0 ? 1 : -1) * ((slot + 1) / 2) * 0.55;
        double a = Math.toRadians(p.getYaw()) + side;
        double x = p.getX() + Math.sin(a) * data.range;
        double z = p.getZ() - Math.cos(a) * data.range;

        Vec3d stand = Pathing.findStand(petWorld, x, z, p.getY(), 6);
        if (stand == null) stand = Pathing.findStand(petWorld, p.getX(), p.getZ(), p.getY(), 30);
        if (stand == null) {
            if (strict) return false;
            stand = new Vec3d(p.getX(), p.getY(), p.getZ());
        }

        this.setPosition(stand.x, stand.y, stand.z);
        this.resetPosition(); // no interpolation from the old spot (that made pets appear underground for a frame)
        this.setYaw(p.getYaw());
        this.setPitch(0.0F);
        this.setHeadYaw(p.getYaw());
        this.setBodyYaw(p.getYaw());
        this.vy = 0.0;
        this.hvx = 0.0;
        this.hvz = 0.0;
        this.path = null;
        this.pathIdx = 0;
        this.stuckTicks = 0;
        this.insideTicks = 0;
        this.moving = false;
        return true;
    }

    /** Put the pet behind its owner (used when it spawns). */
    public void placeBehind(PlayerEntity p, int slot) {
        placeNearOwner(p, slot, false);
    }

    /** Where this pet stands in the rings around the owner (/petcircle): {x, z}. */
    private double[] ringSlot(PlayerEntity p) {
        List<PetData> members = new ArrayList<>();
        for (PetData d : Pets.listOf(data.owner)) {
            if (d.circle) members.add(d);
        }
        int index = Math.max(0, members.indexOf(data));
        int total = Math.max(1, members.size());

        double base = Math.max(data.range, 1.0);
        int start = 0;
        int remaining = total;
        for (int ring = 0; ; ring++) {
            double r = base + ring * 1.1;
            int cap = Math.max(4, (int) Math.floor(2.0 * Math.PI * r / 1.0));
            int inRing = Math.min(cap, remaining);
            if (index < start + inRing || remaining <= inRing) {
                int pos = Math.min(index - start, inRing - 1);
                double angle = 2.0 * Math.PI * pos / inRing + ring * 0.6;
                return new double[]{p.getX() + Math.cos(angle) * r, p.getZ() + Math.sin(angle) * r};
            }
            start += inRing;
            remaining -= inRing;
        }
    }

    /** Where this pet stands in its army formation: {x, z}. */
    private double[] formationSlot(PlayerEntity p) {
        Formation f = Formation.byName(data.formation);
        List<PetData> members = new ArrayList<>();
        for (PetData d : Pets.listOf(data.owner)) {
            if (data.formation.equals(d.formation)) members.add(d);
        }
        int index = Math.max(0, members.indexOf(data));
        double spacing = Math.max(data.range, 1.0);
        double[] lb = f.slot(index, members.size(), spacing); // {lateral, back}

        double yaw = Math.toRadians(p.getBodyYaw());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        return new double[]{
                p.getX() + rx * lb[0] - fx * lb[1],
                p.getZ() + rz * lb[0] - fz * lb[1]};
    }

    private void replan(PlayerEntity p, double tx, double tz, double goalRadius) {
        Vec3d start = new Vec3d(this.getX(), this.getY(), this.getZ());
        List<Vec3d> raw = Pathing.find(petWorld, start, new Vec3d(tx, p.getY(), tz), goalRadius, !data.noJump);
        this.path = raw == null ? null : Pathing.smooth(petWorld, start, raw);
        this.pathIdx = 0;
    }

    @Override
    public void tick() {
        PlayerEntity p = getOwner();
        if (p == null) {
            super.tick();
            return;
        }
        applyGear();

        int slot = Pets.slotOf(data);
        double range = data.range;

        // --- safety: popped into a wall / the ground, too far away, fell out of the world, or stuck ---
        if (Pathing.stuckInBlock(petWorld, this.getX(), this.getY(), this.getZ())) this.insideTicks++;
        else this.insideTicks = 0;

        boolean tooFar = this.squaredDistanceTo(p) > TELEPORT_DISTANCE_SQ;
        boolean fell = this.getY() < petWorld.getBottomY() - 8;
        if (this.insideTicks > 10) {
            placeNearOwner(p, slot, false);
        } else if (!data.stopped && (tooFar || fell || this.stuckTicks > 60)) {
            if (!placeNearOwner(p, slot, true)) {
                this.stuckTicks = 0; // nowhere safe to put it (owner is flying?), try again later
            }
        }

        double odx = p.getX() - this.getX();
        double odz = p.getZ() - this.getZ();
        double odist = Math.sqrt(odx * odx + odz * odz);
        double dyOwner = p.getY() - this.getY();
        boolean onGround = this.isOnGround();

        // --- where the pet wants to be ---
        double tx = p.getX();
        double tz = p.getZ();
        double startDist = range + 0.7;
        double stopDist = range;
        double goalRadius = Math.max(range, 1.0);
        boolean slotMode = data.circle || Formation.byName(data.formation) != null;
        if (slotMode) {
            double[] s = data.circle ? ringSlot(p) : formationSlot(p);
            tx = s[0];
            tz = s[1];
            startDist = 0.8;
            stopDist = 0.35;
            goalRadius = 0.5;
        }
        double tdx = tx - this.getX();
        double tdz = tz - this.getZ();
        double tdist = Math.sqrt(tdx * tdx + tdz * tdz);

        double dvx = 0.0;
        double dvz = 0.0;
        boolean wantJump = false;
        boolean needMove = false;

        if (!data.stopped) {
            needMove = tdist > startDist
                    || (this.moving && tdist > stopDist)
                    || (!slotMode && Math.abs(dyOwner) > 2.5 && p.isOnGround());

            if (needMove) {
                if (!this.moving) this.repathTimer = 0;
                this.moving = true;

                // (re)plan the route every half second or so
                if (onGround && --this.repathTimer <= 0) {
                    replan(p, tx, tz, goalRadius);
                    this.repathTimer = (this.path == null || this.path.isEmpty()) ? 20 : 12 + (slot % 6);
                }

                if (this.path != null) {
                    // pass waypoints without stopping on them
                    while (this.pathIdx < this.path.size()) {
                        Vec3d wp = this.path.get(this.pathIdx);
                        double reach = (this.pathIdx == this.path.size() - 1) ? 0.25 : 0.5;
                        if (Math.hypot(wp.x - this.getX(), wp.z - this.getZ()) < reach) this.pathIdx++;
                        else break;
                    }
                    if (this.pathIdx < this.path.size()) {
                        Vec3d wp = this.path.get(this.pathIdx);
                        double wx = wp.x - this.getX();
                        double wz = wp.z - this.getZ();
                        double wd = Math.sqrt(wx * wx + wz * wz);
                        // walk when close, jog when further, run when far; slow down for the last steps
                        double speed = odist > 10 ? 0.30 : (odist > 5 ? 0.25 : 0.20);
                        if (this.pathIdx == this.path.size() - 1) speed = Math.min(speed, 0.08 + wd * 0.3);
                        if (wd > 0.001) {
                            dvx = wx / wd * speed;
                            dvz = wz / wd * speed;
                        }
                        double rise = wp.y - this.getY();
                        if (!data.noJump && onGround && (rise > 0.55 || (this.horizontalCollision && rise > 0.05))) {
                            wantJump = true;
                        }
                    }
                }
            } else {
                this.moving = false;
                this.path = null;
                if (!slotMode && tdist < stopDist - 0.3 && tdist > 0.001) {
                    // range was lowered or the owner walked into the pet: back off a little
                    dvx = -tdx / tdist * 0.08;
                    dvz = -tdz / tdist * 0.08;
                }
            }

            // keep a little distance from the owner's other pets (rings already space them out)
            if (!slotMode) {
                for (PetData other : Pets.listOf(data.owner)) {
                    PetPlayer o = other.entity;
                    if (o == null || o == this || o.isRemoved()) continue;
                    double ox = this.getX() - o.getX();
                    double oz = this.getZ() - o.getZ();
                    double od = Math.sqrt(ox * ox + oz * oz);
                    if (od < SEPARATION) {
                        if (od < 0.001) {
                            double a = slot * 2.4;
                            ox = Math.cos(a);
                            oz = Math.sin(a);
                            od = 1.0;
                        }
                        double push = (SEPARATION - od) * 0.2;
                        dvx += ox / od * push;
                        dvz += oz / od * push;
                    }
                }
            }
        } else {
            this.moving = false;
            this.path = null;
        }

        // ease towards the wanted velocity (a person doesn't change speed or direction instantly)
        this.hvx += (dvx - this.hvx) * 0.3;
        this.hvz += (dvz - this.hvz) * 0.3;
        if (Math.abs(this.hvx) < 0.003) this.hvx = 0.0;
        if (Math.abs(this.hvz) < 0.003) this.hvz = 0.0;

        // --- jumping: /petjump = constant bouncing, otherwise an occasional hop; /petnojump = never ---
        boolean hop = !data.noJump && onGround && !wantJump
                && (data.jumpAlways || ThreadLocalRandom.current().nextInt(100) == 0);

        // --- vertical motion with simple gravity ---
        if (wantJump) {
            this.vy = 0.42;
        } else if (hop) {
            this.vy = 0.36;
        } else if (onGround) {
            this.vy = -0.08;
        } else {
            this.vy = (this.vy - 0.08) * 0.98;
        }
        if (this.isTouchingWater()) {
            this.vy = Math.min(0.1, Math.max(this.vy, -0.05) + 0.05); // float instead of sinking
        }

        double px = this.getX();
        double pz = this.getZ();
        this.move(MovementType.SELF, new Vec3d(this.hvx, this.vy, this.hvz));
        double moved = Math.hypot(this.getX() - px, this.getZ() - pz);

        // stuck detection: wants to get somewhere but isn't getting anywhere
        if (needMove && (tdist > stopDist + 1.5 || (!slotMode && Math.abs(dyOwner) > 2.5)) && moved < 0.03) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = Math.max(0, this.stuckTicks - 2);
        }

        super.tick();

        // --- facing: walk facing where you're going, glance at the owner, turn to face them when standing ---
        double hs = Math.hypot(this.hvx, this.hvz);
        float ownerYaw = (float) (MathHelper.atan2(odz, odx) * RAD_TO_DEG) - 90.0F;
        float body = this.getBodyYaw();
        float targetBody;
        float rate;
        if (hs > 0.06) {
            targetBody = (float) (MathHelper.atan2(this.hvz, this.hvx) * RAD_TO_DEG) - 90.0F;
            rate = 0.3F;
        } else {
            if (Formation.byName(data.formation) != null) {
                targetBody = p.getBodyYaw(); // soldiers stand facing the same way as their leader
            } else {
                targetBody = odist > 0.01 ? ownerYaw : body;
            }
            rate = 0.12F;
        }
        float newBody = MathHelper.lerpAngleDegrees(rate, body, targetBody);
        float headTarget = newBody + MathHelper.clamp(MathHelper.wrapDegrees(ownerYaw - newBody), -70.0F, 70.0F);
        float newHead = MathHelper.lerpAngleDegrees(0.35F, this.getHeadYaw(), headTarget);
        this.setYaw(newBody);
        this.setBodyYaw(newBody);
        this.setHeadYaw(newHead);

        double dy = p.getEyeY() - (this.getY() + PET_EYE_HEIGHT);
        float targetPitch = (float) -(Math.atan2(dy, Math.max(odist, 0.3)) * RAD_TO_DEG);
        targetPitch = MathHelper.clamp(targetPitch, -60.0F, 60.0F);
        this.setPitch(MathHelper.lerp(0.35F, this.getPitch(), targetPitch));
    }

    @Override
    public boolean canHit() {
        return false; // crosshair passes through it
    }

    @Override
    public boolean isPushable() {
        return false; // doesn't shove you around
    }
}

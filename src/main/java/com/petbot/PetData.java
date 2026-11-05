package com.petbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Settings of one pet. Survives respawns (dimension changes etc). */
public final class PetData {
    /** Default skin: Steve. */
    public static final Identifier DEFAULT_TEXTURE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");
    public static final String DEFAULT_NAME = "Steve";

    public volatile Identifier texture = DEFAULT_TEXTURE;
    public volatile PlayerSkinType model = PlayerSkinType.WIDE;
    /** Name of the player whose skin is used, or null for Steve. */
    public volatile String skinName = null;

    public volatile String nameRaw = DEFAULT_NAME;
    public volatile Text name = Text.literal(DEFAULT_NAME);
    public volatile boolean hideName = false;

    public volatile boolean stopped = false;
    /** /petjump : keep bouncing. */
    public volatile boolean jumpAlways = false;
    /** /petnojump : never jump at all (not even to climb a block; the pet then only uses paths without jumps). */
    public volatile boolean noJump = false;
    /** /petcircle : stand in a ring around the owner instead of just following. */
    public volatile boolean circle = false;
    /** /petformation : army formation name (see Formation), or null for none. */
    public volatile String formation = null;
    /** How far from the owner the pet stays, in blocks (0.5 - 10). */
    public volatile double range = 1.5;

    /** For-show gear: head, chest, legs, feet, main hand, off hand (see Wardrobe). null = nothing. */
    public final String[] gear = new String[6];
    public volatile int gearVersion = 0;

    /** null = belongs to the local player, otherwise the UUID of another Petbot user. */
    public UUID owner = null;

    /** The live entity (null while not in a world). */
    public PetPlayer entity;
    public ClientWorld world;

    public void setNameRaw(String raw) {
        this.nameRaw = raw;
        this.name = Text.literal(raw.replace('&', '\u00a7'));
    }

    public void setGear(int slot, String spec) {
        this.gear[slot] = (spec == null || spec.isBlank()) ? null : spec;
        this.gearVersion++;
    }

    /** Apply settings received from another player's client. */
    public void applyRemote(PetSync.Entry e) {
        this.stopped = e.stopped();
        this.jumpAlways = e.jump();
        this.noJump = e.noJump();
        this.circle = e.circle();
        this.formation = Formation.byName(e.formation()) == null ? null : e.formation();
        this.range = e.range();
        this.hideName = e.hideName();
        setNameRaw(e.name());

        if (!Arrays.equals(this.gear, e.gear())) {
            System.arraycopy(e.gear(), 0, this.gear, 0, 6);
            this.gearVersion++;
        }

        if (!Objects.equals(this.skinName, e.skin())) {
            this.skinName = e.skin();
            if (e.skin() == null) {
                this.texture = DEFAULT_TEXTURE;
                this.model = PlayerSkinType.WIDE;
            } else {
                final String wanted = e.skin();
                Skins.load(MinecraftClient.getInstance(), wanted, loaded -> {
                    if (Objects.equals(this.skinName, wanted)) {
                        this.texture = loaded.id();
                        this.model = loaded.model();
                    }
                }, err -> { /* keep Steve */ });
            }
        }
    }
}

package com.petbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All pets: yours (LIST) and other Petbot users' (REMOTE). Client thread only. */
public final class Pets {
    private Pets() {}

    public static final int MAX = 50;
    public static final List<PetData> LIST = new ArrayList<>();
    public static final Map<UUID, List<PetData>> REMOTE = new HashMap<>();

    /** /petvisible : show other players' pets. */
    public static boolean visibleOthers = false;

    public static List<PetData> listOf(UUID owner) {
        if (owner == null) return LIST;
        List<PetData> l = REMOTE.get(owner);
        return l == null ? new ArrayList<>() : l;
    }

    public static int slotOf(PetData d) {
        return Math.max(0, listOf(d.owner).indexOf(d));
    }

    /** Called every client tick: (re)spawns pet entities that are missing, removes ones that shouldn't exist. */
    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            detachAll();
            return;
        }
        for (PetData d : LIST) {
            ensure(client, d, client.player);
        }
        for (Map.Entry<UUID, List<PetData>> e : REMOTE.entrySet()) {
            PlayerEntity owner = visibleOthers ? client.world.getPlayerByUuid(e.getKey()) : null;
            for (PetData d : e.getValue()) {
                ensure(client, d, owner);
            }
        }
    }

    private static void ensure(MinecraftClient client, PetData d, PlayerEntity owner) {
        if (owner == null) {
            if (d.entity != null) despawn(client, d);
            return;
        }
        if (d.entity == null || d.entity.isRemoved() || d.world != client.world) {
            d.entity = new PetPlayer(client.world, d);
            d.entity.placeBehind(owner, slotOf(d));
            client.world.addEntity(d.entity);
            d.world = client.world;
        }
    }

    private static void despawn(MinecraftClient client, PetData d) {
        if (d.entity != null && client.world != null && d.world == client.world) {
            client.world.removeEntity(d.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        d.entity = null;
        d.world = null;
    }

    private static void detachAll() {
        for (PetData d : LIST) {
            d.entity = null;
            d.world = null;
        }
        for (List<PetData> l : REMOTE.values()) {
            for (PetData d : l) {
                d.entity = null;
                d.world = null;
            }
        }
    }

    /** Remove one of your own pets. */
    public static void remove(MinecraftClient client, PetData d) {
        despawn(client, d);
        LIST.remove(d);
        PetSync.markDirty();
    }

    public static void clear(MinecraftClient client) {
        for (PetData d : new ArrayList<>(LIST)) {
            despawn(client, d);
        }
        LIST.clear();
        PetSync.markDirty();
    }

    public static void removeOwner(MinecraftClient client, UUID owner) {
        List<PetData> l = REMOTE.remove(owner);
        if (l == null) return;
        for (PetData d : l) {
            despawn(client, d);
        }
    }

    /** Hide (despawn) all other players' pets, e.g. when /petvisible is switched off. */
    public static void despawnRemote(MinecraftClient client) {
        for (List<PetData> l : REMOTE.values()) {
            for (PetData d : l) {
                despawn(client, d);
            }
        }
    }

    public static void updateRemote(MinecraftClient client, UUID owner, List<PetSync.Entry> entries) {
        List<PetData> list = REMOTE.computeIfAbsent(owner, k -> new ArrayList<>());
        while (list.size() > entries.size()) {
            despawn(client, list.remove(list.size() - 1));
        }
        while (list.size() < entries.size()) {
            PetData d = new PetData();
            d.owner = owner;
            list.add(d);
        }
        for (int i = 0; i < entries.size(); i++) {
            list.get(i).applyRemote(entries.get(i));
        }
    }

    public static void clearAllState() {
        LIST.clear();
        REMOTE.clear();
    }
}

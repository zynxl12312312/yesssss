package com.petbot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PetbotClient implements ClientModInitializer {
    private static final Pattern PET_PREFIX = Pattern.compile("^#(\\d+)(?:\\s+(.*))?$", Pattern.DOTALL);

    @Override
    public void onInitializeClient() {
        RelayConfig.load();

        // Client commands never leave the client, so they work on any server.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // /pet : add one more pet (up to 50)
            dispatcher.register(ClientCommandManager.literal("pet").executes(ctx -> {
                if (Pets.LIST.size() >= Pets.MAX) {
                    ctx.getSource().sendError(Text.literal("You can only have " + Pets.MAX + " pets."));
                    return 0;
                }
                Pets.LIST.add(new PetData());
                PetSync.markDirty();
                int n = Pets.LIST.size();
                ctx.getSource().sendFeedback(Text.literal("Pet #" + n + " is following you. (" + n + (n == 1 ? " pet)" : " pets)")));
                return 1;
            }));

            // /petremove [number] : remove the last pet (or a specific one)
            dispatcher.register(ClientCommandManager.literal("petremove")
                    .executes(ctx -> {
                        if (Pets.LIST.isEmpty()) return noPets(ctx.getSource());
                        int n = Pets.LIST.size();
                        Pets.remove(MinecraftClient.getInstance(), Pets.LIST.get(n - 1));
                        ctx.getSource().sendFeedback(Text.literal("Pet #" + n + " left."));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("pet", IntegerArgumentType.integer(1, Pets.MAX)).executes(ctx -> {
                        int n = IntegerArgumentType.getInteger(ctx, "pet");
                        if (n > Pets.LIST.size()) return badPet(ctx.getSource(), n);
                        Pets.remove(MinecraftClient.getInstance(), Pets.LIST.get(n - 1));
                        ctx.getSource().sendFeedback(Text.literal("Pet #" + n + " left. The pets after it moved up one number."));
                        return 1;
                    })));

            // /petclear : remove every pet
            dispatcher.register(ClientCommandManager.literal("petclear").executes(ctx -> {
                Pets.clear(MinecraftClient.getInstance());
                ctx.getSource().sendFeedback(Text.literal("All pets left."));
                return 1;
            }));

            // /petstop [number] : stand still
            dispatcher.register(withPetArg("petstop", (src, n) -> setStopped(src, n, true)));

            // /petmove [number] : follow again
            dispatcher.register(withPetArg("petmove", (src, n) -> setStopped(src, n, false)));

            // /petjump [number] : start / stop constant jumping
            dispatcher.register(withPetArg("petjump", PetbotClient::toggleJump));

            // /petnojump [number] : pets never jump at all (toggle)
            dispatcher.register(withPetArg("petnojump", PetbotClient::toggleNoJump));

            // /petcircle [number] : pets stand in circles around you (toggle)
            dispatcher.register(withPetArg("petcircle", PetbotClient::toggleCircle));

            // /petformation <line|column|wedge|box|phalanx|guard|off> : army formations
            var formationCmd = ClientCommandManager.literal("petformation")
                    .executes(ctx -> formationHelp(ctx.getSource()))
                    .then(ClientCommandManager.literal("off").executes(ctx -> setFormation(ctx.getSource(), null)));
            for (Formation f : Formation.values()) {
                formationCmd = formationCmd.then(ClientCommandManager.literal(f.id)
                        .executes(ctx -> setFormation(ctx.getSource(), f)));
            }
            dispatcher.register(formationCmd);

            // /petrange <0.5-10> [number] : how far from you the pet stays
            dispatcher.register(ClientCommandManager.literal("petrange")
                    .then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(0.5, 10.0))
                            .executes(ctx -> setRange(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "blocks"), 0))
                            .then(ClientCommandManager.argument("pet", IntegerArgumentType.integer(1, Pets.MAX))
                                    .executes(ctx -> setRange(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "blocks"),
                                            IntegerArgumentType.getInteger(ctx, "pet"))))));

            // /petname, /petname <name>, /petname #<number> [name]
            // no name = no name tag. Use & for colour codes.
            dispatcher.register(ClientCommandManager.literal("petname")
                    .executes(ctx -> setName(ctx.getSource(), 0, ""))
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                        String raw = StringArgumentType.getString(ctx, "name");
                        Matcher m = PET_PREFIX.matcher(raw);
                        if (m.matches()) {
                            int n = Integer.parseInt(m.group(1));
                            return setName(ctx.getSource(), n, m.group(2) == null ? "" : m.group(2));
                        }
                        return setName(ctx.getSource(), 0, raw);
                    })));

            // /petskin <player> [number] : copy that player's skin
            dispatcher.register(ClientCommandManager.literal("petskin")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> setSkin(ctx.getSource(), StringArgumentType.getString(ctx, "player"), 0))
                            .then(ClientCommandManager.argument("pet", IntegerArgumentType.integer(1, Pets.MAX))
                                    .executes(ctx -> setSkin(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                            IntegerArgumentType.getInteger(ctx, "pet"))))));

            // /petwardrobe : armor, trims, glint and held items (for show only)
            dispatcher.register(ClientCommandManager.literal("petwardrobe").executes(ctx -> {
                if (Pets.LIST.isEmpty()) return noPets(ctx.getSource());
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.send(() -> mc.setScreen(new WardrobeScreen())); // after the chat screen has closed
                return 1;
            }));

            // /petvisible : see (or stop seeing) other players' pets
            dispatcher.register(ClientCommandManager.literal("petvisible").executes(ctx -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                Pets.visibleOthers = !Pets.visibleOthers;
                if (!Pets.visibleOthers) {
                    Pets.despawnRemote(mc);
                    ctx.getSource().sendFeedback(Text.literal("Other players' pets are hidden."));
                } else {
                    ctx.getSource().sendFeedback(Text.literal("You can now see the pets of other Petbot users near you."));
                    if (!PetSync.enabled()) {
                        ctx.getSource().sendFeedback(Text.literal(
                                "No relay is set yet, so nothing can be shared. Use /petrelay <address> (see the README)."));
                    }
                }
                return 1;
            }));

            // /petrelay [address|off] : where pet settings are shared (no server mod needed)
            dispatcher.register(ClientCommandManager.literal("petrelay")
                    .executes(ctx -> {
                        if (PetSync.enabled()) {
                            ctx.getSource().sendFeedback(Text.literal("Relay: " + RelayConfig.relay));
                        } else {
                            ctx.getSource().sendFeedback(Text.literal("No relay set. Use /petrelay <address> to share pets with other Petbot users."));
                        }
                        return 1;
                    })
                    .then(ClientCommandManager.argument("address", StringArgumentType.greedyString()).executes(ctx -> {
                        String addr = StringArgumentType.getString(ctx, "address");
                        if (!RelayConfig.set(addr)) {
                            ctx.getSource().sendError(Text.literal("That isn't a valid http:// or https:// address."));
                            return 0;
                        }
                        PetSync.markDirty();
                        if (PetSync.enabled()) {
                            ctx.getSource().sendFeedback(Text.literal("Relay set. Your UUID and your pets' settings are uploaded to it, and the UUIDs of nearby players are sent to it when /petvisible is on."));
                        } else {
                            ctx.getSource().sendFeedback(Text.literal("Relay turned off. Nothing is shared."));
                        }
                        return 1;
                    })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Pets.tick(client);
            PetSync.tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PetSync.markDirty());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Pets.clearAllState());
    }

    // ---- helpers -------------------------------------------------------------

    private interface PetAction {
        int run(FabricClientCommandSource src, int n);
    }

    /** literal(name) [number] -> action(src, 0) for all pets or action(src, number) for one. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> withPetArg(String name, PetAction action) {
        return ClientCommandManager.literal(name)
                .executes(ctx -> action.run(ctx.getSource(), 0))
                .then(ClientCommandManager.argument("pet", IntegerArgumentType.integer(1, Pets.MAX))
                        .executes(ctx -> action.run(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "pet"))));
    }

    private static int noPets(FabricClientCommandSource src) {
        src.sendError(Text.literal("You don't have a pet yet. Use /pet."));
        return 0;
    }

    private static int badPet(FabricClientCommandSource src, int n) {
        src.sendError(Text.literal("You don't have a pet #" + n + " (you have " + Pets.LIST.size() + ")."));
        return 0;
    }

    /** n == 0 -> every pet, otherwise just pet #n. Returns null (after reporting the error) if there's nothing to select. */
    private static List<PetData> select(FabricClientCommandSource src, int n) {
        if (Pets.LIST.isEmpty()) {
            noPets(src);
            return null;
        }
        if (n == 0) return new ArrayList<>(Pets.LIST);
        if (n > Pets.LIST.size()) {
            badPet(src, n);
            return null;
        }
        List<PetData> one = new ArrayList<>();
        one.add(Pets.LIST.get(n - 1));
        return one;
    }

    private static String target(int n) {
        return n == 0 ? "All pets" : "Pet #" + n;
    }

    private static int setStopped(FabricClientCommandSource src, int n, boolean stopped) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        for (PetData d : sel) d.stopped = stopped;
        PetSync.markDirty();
        src.sendFeedback(Text.literal(target(n) + (stopped ? " stay put." : " follow you again.")));
        return 1;
    }

    private static int toggleJump(FabricClientCommandSource src, int n) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        boolean on = false;
        for (PetData d : sel) {
            if (!d.jumpAlways) on = true;
        }
        for (PetData d : sel) {
            d.jumpAlways = on;
            if (on) d.noJump = false;
        }
        PetSync.markDirty();
        src.sendFeedback(Text.literal(target(n) + (on ? " keep jumping." : " stopped jumping constantly.")));
        return 1;
    }

    private static int toggleNoJump(FabricClientCommandSource src, int n) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        boolean on = false;
        for (PetData d : sel) {
            if (!d.noJump) on = true;
        }
        for (PetData d : sel) {
            d.noJump = on;
            if (on) d.jumpAlways = false;
        }
        PetSync.markDirty();
        src.sendFeedback(Text.literal(target(n) + (on ? " will never jump." : " can jump again.")));
        return 1;
    }

    private static int toggleCircle(FabricClientCommandSource src, int n) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        boolean on = false;
        for (PetData d : sel) {
            if (!d.circle) on = true;
        }
        for (PetData d : sel) {
            d.circle = on;
            if (on) d.formation = null;
        }
        PetSync.markDirty();
        src.sendFeedback(Text.literal(target(n) + (on ? " surround you in circles." : " follow you normally again.")));
        return 1;
    }

    private static int formationHelp(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("Use /petformation <name> to line your pets up like an army, or /petformation off:"));
        for (Formation f : Formation.values()) {
            src.sendFeedback(Text.literal("  " + f.id + " - " + f.description));
        }
        src.sendFeedback(Text.literal("/petrange <blocks> sets how far apart the soldiers stand."));
        return 1;
    }

    private static int setFormation(FabricClientCommandSource src, Formation f) {
        List<PetData> sel = select(src, 0);
        if (sel == null) return 0;
        for (PetData d : sel) {
            d.formation = f == null ? null : f.id;
            if (f != null) d.circle = false;
        }
        PetSync.markDirty();
        if (f == null) {
            src.sendFeedback(Text.literal("Pets are out of formation and follow you normally again."));
        } else {
            src.sendFeedback(Text.literal("Pets fall in: " + f.id + " (" + f.description + ")."));
        }
        return 1;
    }

    private static int setRange(FabricClientCommandSource src, double blocks, int n) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        for (PetData d : sel) d.range = blocks;
        PetSync.markDirty();
        src.sendFeedback(Text.literal(target(n) + " will stay about " + blocks + " blocks from you."));
        return 1;
    }

    private static int setName(FabricClientCommandSource src, int n, String raw) {
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        String text = raw.trim();
        if (text.length() > 48) text = text.substring(0, 48);
        boolean none = text.isEmpty();
        for (PetData d : sel) {
            d.hideName = none;
            if (!none) d.setNameRaw(text);
        }
        PetSync.markDirty();
        if (none) {
            src.sendFeedback(Text.literal(target(n) + " now " + (n == 0 ? "have" : "has") + " no name tag."));
        } else {
            src.sendFeedback(Text.literal(target(n) + " now " + (n == 0 ? "are" : "is") + " called ")
                    .append(Text.literal(text.replace('&', '\u00a7'))));
        }
        return 1;
    }

    private static int setSkin(FabricClientCommandSource src, String player, int n) {
        if (!player.matches("[A-Za-z0-9_]{1,16}")) {
            src.sendError(Text.literal("That's not a valid Minecraft username."));
            return 0;
        }
        List<PetData> sel = select(src, n);
        if (sel == null) return 0;
        src.sendFeedback(Text.literal("Loading " + player + "'s skin..."));

        Skins.load(src.getClient(), player, loaded -> {
            for (PetData d : sel) {
                d.texture = loaded.id();
                d.model = loaded.model();
                d.skinName = loaded.name();
            }
            PetSync.markDirty();
            src.sendFeedback(Text.literal(target(n) + " now " + (n == 0 ? "wear " : "wears ") + loaded.name() + "'s skin."));
        }, err -> src.sendError(Text.literal("Couldn't load skin: " + err)));
        return 1;
    }
}

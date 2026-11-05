# Petbot (Fabric 1.21.11)

Small client-side player pets that follow you, surround you, look at you and jump. Default pet: Steve, named
"Steve". Everything is client-side, so it works on any server and the server owner installs nothing. Only people
with the mod see pets (and only the ones they choose to see, see "Seeing other players' pets").

Pets are numbered in the order you add them. Commands that take `[number]` affect every pet when you leave it out.

| Command | What it does |
|---|---|
| `/pet` | add another pet (max 50) |
| `/petremove [number]` | remove the last pet, or pet `number` |
| `/petclear` | remove all pets |
| `/petstop [number]` | pet stays where it is |
| `/petmove [number]` | pet follows you again |
| `/petjump [number]` | start / stop constant jumping (pets also hop now and then) |
| `/petnojump [number]` | pets never jump at all (toggle; they then only use jump-free routes) |
| `/petcircle [number]` | pets stand in circles around you (toggle; more pets = more rings) |
| `/petformation <line\|column\|wedge\|box\|phalanx\|guard\|off>` | all pets stand in an army formation relative to you and turn when you turn (`/petrange` sets the spacing) |
| `/petrange <0.5-10> [number]` | how far from you the pet stays, in blocks (the first ring's radius in circles) |
| `/petskin <player> [number]` | copy that player's skin (looked up from Mojang) |
| `/petname` | remove the name tag from all pets |
| `/petname <name>` | name all pets (`&` colour codes work, e.g. `&6Bob`) |
| `/petname #<number> [name]` | name one pet; leave the name out for no name tag |
| `/petwardrobe` | GUI: armor, armor trims, enchant glint and held items (for show only) |
| `/petvisible` | show / hide the pets of other players |
| `/petrelay [address\|off]` | set / show the relay used to share pets (see below) |

## Wardrobe
`/petwardrobe` opens a screen where you pick the pet (or all pets), then for every slot (head, chest, legs, feet,
main hand, off hand) click the item button to cycle items (Shift+click goes backwards). Armor pieces also get a
trim pattern, a trim material and an enchant-glint toggle; held items get the glint toggle. It is all for show:
nothing is added to your inventory, nothing is sent to the server, and the items do nothing.

## How the pets move
Pets walk with real collision and use A* pathfinding over the blocks you have loaded. Paths are smoothed so they
cut corners in natural straight lines, speed eases in and out (walk, jog, run when far away), they face the way
they walk, glance at you, and turn to face you when they stop. They jump up one-block steps (unless `/petnojump`),
drop down up to 3 blocks, and avoid water, lava, fire, cobwebs, cactus, magma blocks and berry bushes. If a pet is
more than 20 blocks from you, falls out of the world, or is stuck for about 3 seconds, it teleports to you. A pet
you stopped with `/petstop` stays put and doesn't teleport.

## Seeing other players' pets (no server mod needed)
Minecraft servers don't pass data between players' mods, so sharing goes through a tiny relay you run once
(`relay/server.js`, plain Node.js, no dependencies; host it anywhere, e.g. Render, Railway, Replit or a VPS).
Everyone who wants to see each other's pets sets the same address with `/petrelay https://your-relay` (or put it
in `DEFAULT_RELAY` in `RelayConfig.java` before building so friends don't have to type it) and turns on
`/petvisible`. The server you play on never needs the mod.

Privacy: with a relay set, your UUID and your pets' settings (names, skin name, gear, ...) are uploaded to it, and
while `/petvisible` is on the UUIDs of players near you are sent to it to ask for their pets. Nothing is sent
anywhere unless you set a relay. Anyone could upload settings under someone else's UUID (it's cosmetic only, and
the mod sanitizes everything it receives).

Requires Fabric Loader 0.18.4+ and Fabric API for 1.21.11 (0.141.3+). Build needs Java 21 and Gradle 9.2+ (Loom 1.15).

package com.petbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* pathfinding over the blocks the client has loaded, plus path smoothing so pets walk in natural lines
 * instead of block-by-block.
 * A pet is 0.6 wide and 1.8 tall (a player-sized hitbox). It can walk, step up one block (by jumping, if jumping
 * is allowed) and drop down up to 3 blocks. It avoids water, lava, fire, cobwebs, cactus, magma and berry bushes.
 */
public final class Pathing {
    private Pathing() {}

    private static final int MAX_NODES = 600;
    private static final double MAX_RADIUS = 45.0;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class N {
        final int x, y, z;
        final double sy; // y of the surface the pet stands on
        double g, f;
        N parent;
        boolean closed;

        N(int x, int y, int z, double sy) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sy = sy;
        }
    }

    private static boolean dangerous(BlockState s) {
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.COBWEB)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS);
    }

    /** Height (0..1) of the walkable top of block (x,y,z), or NaN if you can't stand on it. */
    public static double floorHeight(ClientWorld w, int x, int y, int z) {
        if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return Double.NaN;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = w.getBlockState(p);
        if (dangerous(s)) return Double.NaN;
        VoxelShape shape = s.getCollisionShape(w, p);
        if (shape.isEmpty()) return Double.NaN;
        double h = shape.getMax(Direction.Axis.Y);
        if (h > 1.0) return Double.NaN; // fences, walls
        return h;
    }

    /** Is there room for a pet standing at (cx, sy, cz) with `height` of free space above? */
    public static boolean clear(ClientWorld w, double cx, double sy, double cz, double height) {
        Box box = new Box(cx - 0.3, sy + 0.02, cz - 0.3, cx + 0.3, sy + height, cz + 0.3);
        for (VoxelShape s : w.getBlockCollisions(null, box)) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    /** True if the middle of a pet at this position is inside solid blocks (slightly smaller box than the hitbox). */
    public static boolean stuckInBlock(ClientWorld w, double x, double y, double z) {
        Box box = new Box(x - 0.25, y + 0.1, z - 0.25, x + 0.25, y + 1.7, z + 0.25);
        for (VoxelShape s : w.getBlockCollisions(null, box)) {
            if (!s.isEmpty()) return true;
        }
        return false;
    }

    private static boolean standable(ClientWorld w, int x, double sy, int z) {
        if (!clear(w, x + 0.5, sy, z + 0.5, 1.8)) return false;
        BlockPos feet = new BlockPos(x, (int) Math.floor(sy + 0.001), z);
        return w.getFluidState(feet).isEmpty();
    }

    /** Finds a spot a pet can stand on near (x, aroundY, z), scanning downwards up to `depth` blocks. */
    public static Vec3d findStand(ClientWorld w, double x, double z, double aroundY, int depth) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(aroundY) + 1;
        for (int yy = top; yy >= top - 1 - depth; yy--) {
            double h = floorHeight(w, bx, yy, bz);
            if (Double.isNaN(h)) continue;
            double sy = yy + h;
            if (standable(w, bx, sy, bz)) {
                return new Vec3d(x, sy, z);
            }
        }
        return null;
    }

    private static double heuristic(N n, Vec3d goal) {
        return Math.abs(n.x + 0.5 - goal.x) + Math.abs(n.z + 0.5 - goal.z) + 0.5 * Math.abs(n.sy - goal.y);
    }

    private static boolean reached(N n, Vec3d goal, double radius) {
        return Math.hypot(n.x + 0.5 - goal.x, n.z + 0.5 - goal.z) <= radius && Math.abs(n.sy - goal.y) <= 1.5;
    }

    /**
     * Path from `start` to within `goalRadius` blocks of `goal`. If the goal can't be reached it returns the path
     * to the closest reachable spot. Returns null if there's nowhere to go. With allowJump = false the pet only
     * uses steps it can walk up by itself (slabs, carpets...).
     */
    public static List<Vec3d> find(ClientWorld w, Vec3d start, Vec3d goal, double goalRadius, boolean allowJump) {
        int sx = (int) Math.floor(start.x);
        int sz = (int) Math.floor(start.z);
        int sy = (int) Math.floor(start.y - 0.001);
        double h = floorHeight(w, sx, sy, sz);
        if (Double.isNaN(h)) {
            sy--;
            h = floorHeight(w, sx, sy, sz);
            if (Double.isNaN(h)) return null;
        }
        double maxRise = allowJump ? 1.05 : 0.6;

        N first = new N(sx, sy, sz, sy + h);
        first.f = heuristic(first, goal);
        Map<Long, N> all = new HashMap<>();
        all.put(BlockPos.asLong(sx, sy, sz), first);
        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble((N n) -> n.f));
        open.add(first);

        N best = first;
        double bestH = heuristic(first, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            N cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;
            expanded++;

            double hc = heuristic(cur, goal);
            if (hc < bestH) {
                bestH = hc;
                best = cur;
            }
            if (reached(cur, goal, goalRadius)) {
                best = cur;
                break;
            }

            for (int[] d : DIRS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                if (Math.hypot(nx + 0.5 - start.x, nz + 0.5 - start.z) > MAX_RADIUS) continue;

                // step up one block, stay level, or drop up to 3 blocks
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = cur.y + dy;
                    double fh = floorHeight(w, nx, ny, nz);
                    if (Double.isNaN(fh)) continue;
                    double nsy = ny + fh;
                    double rise = nsy - cur.sy;
                    if (rise > maxRise) continue;
                    if (!standable(w, nx, nsy, nz)) continue;
                    // need headroom to jump up from the current spot
                    if (rise > 0.55 && !clear(w, cur.x + 0.5, cur.sy, cur.z + 0.5, rise + 1.8)) continue;
                    // and room in the gap between the two cells
                    double my = Math.max(cur.sy, nsy);
                    if (!clear(w, (cur.x + nx) / 2.0 + 0.5, my, (cur.z + nz) / 2.0 + 0.5, 1.8)) continue;

                    double cost = 1.0;
                    if (rise > 0.55) cost += 1.5;
                    if (rise < -0.55) cost += 0.5 * -rise;
                    double g = cur.g + cost;

                    long key = BlockPos.asLong(nx, ny, nz);
                    N ex = all.get(key);
                    if (ex != null && (ex.closed || ex.g <= g)) break;
                    N nn = new N(nx, ny, nz, nsy);
                    nn.g = g;
                    nn.f = g + heuristic(nn, goal);
                    nn.parent = cur;
                    all.put(key, nn);
                    open.add(nn);
                    break; // only one walkable level per neighbouring column
                }
            }
        }

        if (best == first) return null;
        LinkedList<Vec3d> path = new LinkedList<>();
        for (N n = best; n != first && n != null; n = n.parent) {
            path.addFirst(new Vec3d(n.x + 0.5, n.sy, n.z + 0.5));
        }
        return path;
    }

    /** Can a pet walk a straight line between two points on the same level? */
    private static boolean lineOk(ClientWorld w, Vec3d a, Vec3d b) {
        if (Math.abs(a.y - b.y) > 0.1) return false;
        double len = Math.hypot(b.x - a.x, b.z - a.z);
        int steps = (int) Math.ceil(len / 0.35);
        int by = (int) Math.floor(b.y - 0.001);
        for (int k = 1; k <= steps; k++) {
            double t = (double) k / steps;
            double x = a.x + (b.x - a.x) * t;
            double z = a.z + (b.z - a.z) * t;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            double h = floorHeight(w, bx, by, bz);
            if (Double.isNaN(h) || Math.abs(by + h - b.y) > 0.01) return false;
            if (!clear(w, x, b.y, z, 1.8)) return false;
            if (!w.getFluidState(new BlockPos(bx, (int) Math.floor(b.y + 0.001), bz)).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Cuts corners: replaces the block-by-block path by fewer, longer straight lines wherever the pet can
     * walk them. Steps up/down are kept as their own waypoints.
     */
    public static List<Vec3d> smooth(ClientWorld w, Vec3d start, List<Vec3d> path) {
        List<Vec3d> out = new ArrayList<>();
        Vec3d anchor = start;
        int i = 0;
        int n = path.size();
        while (i < n) {
            int far = i;
            for (int j = Math.min(n - 1, i + 8); j > i; j--) {
                if (lineOk(w, anchor, path.get(j))) {
                    far = j;
                    break;
                }
            }
            out.add(path.get(far));
            anchor = path.get(far);
            i = far + 1;
        }
        return out;
    }
}

package com.petbot;

import java.util.Locale;

/**
 * Army formations for /petformation. Each formation turns "pet number i of n" into a spot relative to the
 * owner: {lateral, back} in blocks. lateral > 0 is to the owner's right, back > 0 is behind the owner
 * (negative = in front of the owner).
 */
public enum Formation {
    /** Soldiers side by side in ranks behind you. */
    LINE("line", "ranks of soldiers standing side by side behind you") {
        @Override
        public double[] slot(int i, int n, double s) {
            int perRank = 12;
            int rank = i / perRank;
            int inRank = Math.min(perRank, n - rank * perRank);
            int pos = i % perRank;
            return new double[]{(pos - (inRank - 1) / 2.0) * s, s + rank * s};
        }
    },
    /** Marching column: files one behind another. */
    COLUMN("column", "a marching column, one behind the other") {
        @Override
        public double[] slot(int i, int n, double s) {
            int perFile = 10;
            int file = i / perFile;
            int files = (n + perFile - 1) / perFile;
            int row = i % perFile;
            return new double[]{(file - (files - 1) / 2.0) * s, s + row * s};
        }
    },
    /** V-shape with the point nearest to you (like a flying wedge). */
    WEDGE("wedge", "a V-shape pointing at you, like a charging wedge") {
        @Override
        public double[] slot(int i, int n, double s) {
            if (i == 0) return new double[]{0.0, s};
            int k = (i + 1) / 2;
            double side = (i % 2 == 1) ? -1.0 : 1.0;
            return new double[]{side * k * s, s + k * s};
        }
    },
    /** Solid square block, like a parade. */
    BOX("box", "a tight square block, like a parade") {
        @Override
        public double[] slot(int i, int n, double s) {
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
            int row = i / cols;
            int inRow = Math.min(cols, n - row * cols);
            int col = i % cols;
            return new double[]{(col - (inRow - 1) / 2.0) * s, s + row * s};
        }
    },
    /** A shield wall in front of you. */
    PHALANX("phalanx", "a shield wall in front of you") {
        @Override
        public double[] slot(int i, int n, double s) {
            int perRank = 10;
            int rank = i / perRank;
            int inRank = Math.min(perRank, n - rank * perRank);
            int pos = i % perRank;
            return new double[]{(pos - (inRank - 1) / 2.0) * s, -(s + rank * s)};
        }
    },
    /** Guard ring is the existing circle; this is a hollow square around you. */
    GUARD("guard", "a hollow square around you, guarding on all sides") {
        @Override
        public double[] slot(int i, int n, double s) {
            // walk along the perimeter of a square of "half" blocks
            int perSide = Math.max(1, (int) Math.ceil(n / 4.0));
            double half = Math.max(s, (perSide * s) / 2.0);
            int side = i / perSide;
            int pos = i % perSide;
            double t = (pos + 0.5) / perSide * 2.0 * half - half; // -half .. +half
            switch (side % 4) {
                case 0:  return new double[]{t, half};      // behind
                case 1:  return new double[]{half, -t};     // right
                case 2:  return new double[]{-t, -half};    // front
                default: return new double[]{-half, t};     // left
            }
        }
    };

    public final String id;
    public final String description;

    Formation(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /** {lateral, back} for pet i of n, with s = spacing in blocks. */
    public abstract double[] slot(int i, int n, double s);

    public static Formation byName(String name) {
        if (name == null) return null;
        String k = name.toLowerCase(Locale.ROOT);
        for (Formation f : values()) {
            if (f.id.equals(k)) return f;
        }
        return null;
    }
}

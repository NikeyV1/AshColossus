package de.nikey.witherboss.boss;

import java.util.List;

public final class BossGeometry {
    private BossGeometry() {}

    public record Pillar(double minX, double minZ, double maxX, double maxZ) {
        public boolean inside(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public sealed interface Shape permits Circle, Ring, Sector, Lane {
        boolean contains(double x, double z);
    }

    public record Circle(double cx, double cz, double radius) implements Shape {
        public boolean contains(double x, double z) {
            double dx = x - cx, dz = z - cz;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    public record Ring(double cx, double cz, double inner, double outer) implements Shape {
        public boolean contains(double x, double z) {
            double d = Math.hypot(x - cx, z - cz);
            return d >= inner && d <= outer;
        }
    }

    public record Sector(double ax, double az, double yaw, double radius, double halfAngle) implements Shape {
        public boolean contains(double x, double z) {
            double dx = x - ax, dz = z - az, d = Math.hypot(dx, dz);
            if (d > radius) return false;
            if (d < 0.75) return true;
            double fx = Math.sin(yaw), fz = Math.cos(yaw);
            return (dx * fx + dz * fz) / d >= Math.cos(halfAngle);
        }
    }

    public record Lane(double sx, double sz, double yaw, double length, double halfWidth) implements Shape {
        public boolean contains(double x, double z) {
            double fx = Math.sin(yaw), fz = Math.cos(yaw);
            double dx = x - sx, dz = z - sz;
            double along = dx * fx + dz * fz;
            double side = Math.abs(dx * fz - dz * fx);
            return along >= -0.3 && along <= length && side <= halfWidth;
        }

        public double endX() {
            return sx + Math.sin(yaw) * length;
        }

        public double endZ() {
            return sz + Math.cos(yaw) * length;
        }
    }

    public static double clip(double sx, double sz, double yaw, double maxLength, List<Pillar> pillars) {
        double dx = Math.sin(yaw), dz = Math.cos(yaw);
        double best = maxLength;
        for (Pillar p : pillars) {
            double hit = ray(sx, sz, dx, dz, p);
            if (hit >= 0 && hit < best) best = hit;
        }
        return best;
    }

    static double ray(double sx, double sz, double dx, double dz, Pillar p) {
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        double[][] slabs = {{sx, dx, p.minX(), p.maxX()}, {sz, dz, p.minZ(), p.maxZ()}};
        for (double[] s : slabs) {
            if (Math.abs(s[1]) < 1e-9) {
                if (s[0] < s[2] || s[0] > s[3]) return -1;
            } else {
                double t1 = (s[2] - s[0]) / s[1], t2 = (s[3] - s[0]) / s[1];
                tmin = Math.max(tmin, Math.min(t1, t2));
                tmax = Math.min(tmax, Math.max(t1, t2));
            }
        }
        if (tmax < Math.max(tmin, 0)) return -1;
        return Math.max(tmin, 0);
    }

    public static boolean occluded(double fx, double fz, double tx, double tz, List<Pillar> pillars) {
        double len = Math.hypot(tx - fx, tz - fz);
        if (len < 1e-6) return false;
        double dx = (tx - fx) / len, dz = (tz - fz) / len;
        for (Pillar p : pillars) {
            if (p.inside(fx, fz)) continue;
            double hit = ray(fx, fz, dx, dz, p);
            if (hit >= 0 && hit < len) return true;
        }
        return false;
    }

    public static double[] slide(double fromX, double fromZ, double toX, double toZ, List<Pillar> pillars, double clearance) {
        double x = toX, z = toZ;
        double stepLength = Math.hypot(toX - fromX, toZ - fromZ);
        for (Pillar p : pillars) {
            if (p.inside(x, z)) return new double[] {fromX, fromZ};
            double nx = Math.clamp(x, p.minX(), p.maxX()), nz = Math.clamp(z, p.minZ(), p.maxZ());
            double dx = x - nx, dz = z - nz, d = Math.hypot(dx, dz);
            if (d >= clearance) continue;
            if (d < 1e-6) return new double[] {fromX, fromZ};
            double ux = dx / d, uz = dz / d;
            double inward = -((x - fromX) * ux + (z - fromZ) * uz);
            if (inward > 0) {
                x += ux * inward;
                z += uz * inward;
            }
            double sideways = Math.hypot(x - fromX, z - fromZ);
            if (sideways < stepLength * 0.3) {
                x += -uz * stepLength * 0.5;
                z += ux * stepLength * 0.5;
            }
            double ex = Math.clamp(x, p.minX(), p.maxX()), ez = Math.clamp(z, p.minZ(), p.maxZ());
            double rx = x - ex, rz = z - ez, rd = Math.hypot(rx, rz);
            if (rd < 1e-6) return new double[] {fromX, fromZ};
            if (rd < clearance) {
                x = ex + rx / rd * clearance;
                z = ez + rz / rd * clearance;
            }
        }
        for (Pillar p : pillars) {
            double nx = Math.clamp(x, p.minX(), p.maxX()), nz = Math.clamp(z, p.minZ(), p.maxZ());
            if (Math.hypot(x - nx, z - nz) < clearance - 1e-6) return new double[] {fromX, fromZ};
        }
        return new double[] {x, z};
    }

    public static double wrap(double angle) {
        double a = (angle + Math.PI) % (2 * Math.PI);
        if (a < 0) a += 2 * Math.PI;
        return a - Math.PI;
    }

    public static double turn(double from, double to, double maxStep) {
        double d = wrap(to - from);
        return from + Math.clamp(d, -maxStep, maxStep);
    }
}

package de.risingsea;

/** Pure eligibility rules; no game-state side effects. */
public final class BoatLiftPolicy {
    private BoatLiftPolicy() {}

    public static boolean nearOldSurface(double boatY, int seaLevel) {
        return Double.isFinite(boatY) && Math.abs(boatY - seaLevel) <= 2.0;
    }

    public static boolean alreadyAtTarget(double boatY, double targetY) {
        return Double.isFinite(boatY) && Double.isFinite(targetY)
            && Math.abs(boatY - targetY) < 1.0;
    }
}

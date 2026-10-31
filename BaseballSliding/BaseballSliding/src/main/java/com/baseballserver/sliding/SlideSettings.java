package com.baseballserver.sliding;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 의 값을 메모리에 들고 있는 설정. 메인 스레드에서만 사용합니다. */
public final class SlideSettings {

    public boolean enabled;

    public int taps;
    public long tapWindowMs;

    public double distance;
    public double friction;
    public int maxTicks;
    public double minSpeed;
    public double cooldownSeconds;
    public boolean preventJump;
    public boolean pose;

    public boolean mashEnabled;
    public long mashWindowMs;
    public double mashFullCps;
    public double mashMaxMultiplier;

    public int slownessLevel;
    public double slownessSeconds;

    public boolean particles;
    public boolean sound;

    public void load(FileConfiguration c) {
        enabled = c.getBoolean("enabled", true);

        taps = clamp(c.getInt("trigger.taps", 2), 2, 5);
        tapWindowMs = clamp(c.getLong("trigger.window-ms", 400L), 100L, 2000L);

        distance = clamp(c.getDouble("slide.distance", 8.0), 1.0, 40.0);
        friction = clamp(c.getDouble("slide.friction", 0.93), 0.50, 0.99);
        maxTicks = clamp(c.getInt("slide.max-ticks", 80), 5, 400);
        minSpeed = clamp(c.getDouble("slide.min-speed", 0.06), 0.01, 0.5);
        cooldownSeconds = clamp(c.getDouble("slide.cooldown-seconds", 3.0), 0.0, 60.0);
        preventJump = c.getBoolean("slide.prevent-jump", true);
        pose = c.getBoolean("slide.pose", true);

        mashEnabled = c.getBoolean("mash.enabled", true);
        mashWindowMs = clamp(c.getLong("mash.window-ms", 1000L), 300L, 5000L);
        mashFullCps = clamp(c.getDouble("mash.full-cps", 8.0), 1.0, 30.0);
        mashMaxMultiplier = clamp(c.getDouble("mash.max-multiplier", 2.0), 1.0, 5.0);

        slownessLevel = clamp(c.getInt("after.slowness-level", 2), 0, 10);
        slownessSeconds = clamp(c.getDouble("after.slowness-seconds", 2.0), 0.0, 60.0);

        particles = c.getBoolean("effects.particles", true);
        sound = c.getBoolean("effects.sound", true);
    }

    /** 거리와 마찰에서 시작 속도(블록/틱)를 계산합니다. 총 이동 거리 ≈ 시작속도 / (1 - 마찰) */
    public double initialSpeed() {
        return Math.min(distance * (1.0 - friction), 1.5);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

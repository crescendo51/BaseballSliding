package com.baseballserver.sliding;

import io.papermc.paper.event.player.PlayerInputEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 슬라이딩 본체.
 *  - Shift 연타를 감지해서 슬라이딩 시작
 *  - 매 틱마다 속도를 주면서 미끄러지게 함 (마찰로 점점 느려짐)
 *  - 슬라이딩 중 스페이스바 입력 속도(CPS)가 빠를수록 마찰이 줄어 더 멀리 감
 *  - 끝나면 잠시 구속 효과
 * 모든 작업은 메인 스레드에서 실행됩니다.
 */
public class SlideManager implements Listener {

    private static final class Slide {
        final Vector dir;
        double speed;
        int ticks;
        int airTicks;
        boolean posed;
        final Deque<Long> presses = new ArrayDeque<>();

        Slide(Vector dir, double speed) {
            this.dir = dir;
            this.speed = speed;
        }
    }

    private final SlidingPlugin plugin;
    private final SlideSettings settings;
    private final NamespacedKey jumpKey;

    private final Map<UUID, Deque<Long>> taps = new HashMap<>();
    private final Map<UUID, Slide> slides = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Boolean> jumpHeld = new HashMap<>();

    private BukkitTask task;

    public SlideManager(SlidingPlugin plugin, SlideSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.jumpKey = new NamespacedKey(plugin, "slide_no_jump");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, Slide> e : new ArrayList<>(slides.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                finish(p, e.getValue(), false);
            }
        }
        slides.clear();
        taps.clear();
        jumpHeld.clear();
    }

    // ---------- 이벤트 ----------

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!settings.enabled || slides.containsKey(id)) {
            return;
        }

        long now = System.currentTimeMillis();
        Deque<Long> list = taps.computeIfAbsent(id, k -> new ArrayDeque<>());
        list.addLast(now);
        while (!list.isEmpty() && now - list.peekFirst() > settings.tapWindowMs) {
            list.pollFirst();
        }

        if (list.size() >= settings.taps) {
            list.clear();
            tryStart(player, now);
        }
    }

    /** 스페이스바 입력 감지 (누르기 시작하는 순간만 카운트). */
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boolean jump = event.getInput().isJump();
        boolean was = jumpHeld.getOrDefault(id, false);
        jumpHeld.put(id, jump);

        if (jump && !was) {
            Slide s = slides.get(id);
            if (s != null && settings.mashEnabled) {
                s.presses.addLast(System.currentTimeMillis());
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        abort(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        abort(event.getPlayer());
        taps.remove(id);
        jumpHeld.remove(id);
        cooldownUntil.remove(id);
    }

    // ---------- 시작 / 종료 ----------

    private void tryStart(Player p, long now) {
        UUID id = p.getUniqueId();
        if (!p.hasPermission("baseballsliding.use")) return;
        if (cooldownUntil.getOrDefault(id, 0L) > now) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!p.isOnGround() || p.isFlying() || p.isGliding() || p.isInsideVehicle() || p.isInWater()) return;

        // 바라보는 방향(수평)으로 미끄러짐
        double yaw = Math.toRadians(p.getLocation().getYaw());
        Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));

        Slide s = new Slide(dir, settings.initialSpeed());
        slides.put(id, s);

        if (settings.preventJump) {
            applyJumpBlock(p);
        }
        if (settings.pose) {
            p.setPose(Pose.SWIMMING, true);
            s.posed = true;
        }
        if (settings.sound) {
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.8f, 1.2f);
        }
    }

    /** 외부 사유(텔레포트, 퇴장 등)로 슬라이딩을 조용히 끊습니다. */
    private void abort(Player p) {
        Slide s = slides.remove(p.getUniqueId());
        if (s != null) {
            finish(p, s, false);
        }
    }

    /** 슬라이딩 정리. 맵에서 제거하는 일은 호출한 쪽에서 합니다. */
    private void finish(Player p, Slide s, boolean applyAfterEffect) {
        clearJumpBlock(p);
        if (s.posed) {
            p.setPose(Pose.STANDING, false);
        }
        cooldownUntil.put(p.getUniqueId(),
                System.currentTimeMillis() + (long) (settings.cooldownSeconds * 1000));

        if (applyAfterEffect && settings.slownessLevel > 0 && settings.slownessSeconds > 0) {
            int duration = (int) Math.round(settings.slownessSeconds * 20);
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, duration, settings.slownessLevel - 1, false, false, true));
        }
    }

    // ---------- 매 틱 ----------

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Slide>> it = slides.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Slide> entry = it.next();
            Slide s = entry.getValue();
            Player p = Bukkit.getPlayer(entry.getKey());

            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            if (p.isDead() || p.isInWater() || p.isFlying() || p.isGliding()
                    || p.isInsideVehicle() || p.getGameMode() == GameMode.SPECTATOR) {
                finish(p, s, false);
                it.remove();
                continue;
            }

            // 공중에 오래 떠 있으면 종료 (절벽에서 떨어질 때 등)
            if (p.isOnGround()) {
                s.airTicks = 0;
            } else if (++s.airTicks > 3) {
                finish(p, s, true);
                it.remove();
                continue;
            }

            // 벽에 부딪히면 종료
            Location loc = p.getLocation();
            double reach = 0.45 + s.speed;
            Block ahead = loc.clone().add(s.dir.getX() * reach, 0, s.dir.getZ() * reach).getBlock();
            if (!ahead.isPassable()) {
                finish(p, s, true);
                it.remove();
                continue;
            }

            // 이동
            Vector v = s.dir.clone().multiply(s.speed);
            v.setY(Math.min(p.getVelocity().getY(), 0.0));
            p.setVelocity(v);

            if (settings.particles) {
                spawnParticles(p);
            }

            // 마찰 계산 (스페이스바 연타가 빠를수록 마찰이 줄어 멀리 감)
            s.ticks++;
            s.speed *= currentFriction(s, now);

            if (s.speed < settings.minSpeed || s.ticks >= settings.maxTicks) {
                finish(p, s, true);
                it.remove();
            }
        }
    }

    /**
     * 연타 속도(CPS)에 따라 이번 틱의 마찰(속도 유지율)을 계산합니다.
     * 연타 비율 r = min(CPS / full-cps, 1), 거리 배율 m = 1 + (max-multiplier - 1) * r
     * 속도 손실을 1/m 로 줄이면 총 이동 거리가 대략 m 배가 됩니다.
     */
    private double currentFriction(Slide s, long now) {
        double friction = settings.friction;
        if (!settings.mashEnabled) {
            return friction;
        }
        while (!s.presses.isEmpty() && now - s.presses.peekFirst() > settings.mashWindowMs) {
            s.presses.pollFirst();
        }
        double cps = s.presses.size() / (settings.mashWindowMs / 1000.0);
        double ratio = Math.min(1.0, cps / settings.mashFullCps);
        double multiplier = 1.0 + (settings.mashMaxMultiplier - 1.0) * ratio;
        return 1.0 - (1.0 - friction) / multiplier;
    }

    private void spawnParticles(Player p) {
        Location loc = p.getLocation();
        Block under = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (!under.getType().isAir()) {
            p.getWorld().spawnParticle(Particle.BLOCK, loc, 4, 0.25, 0.05, 0.25, 0.02, under.getBlockData());
        }
    }

    // ---------- 점프 차단 (점프력 속성을 0으로) ----------

    private void applyJumpBlock(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (attr == null) return;
        clearJumpBlock(p);
        // 저장되지 않는 임시 수정자라서 서버가 꺼져도 플레이어에게 남지 않습니다.
        attr.addTransientModifier(new AttributeModifier(
                jumpKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void clearJumpBlock(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (attr == null) return;
        for (AttributeModifier m : new ArrayList<>(attr.getModifiers())) {
            if (m.getKey().equals(jumpKey)) {
                attr.removeModifier(m);
            }
        }
    }
}

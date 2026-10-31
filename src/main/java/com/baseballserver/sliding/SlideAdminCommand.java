package com.baseballserver.sliding;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /슬라이딩설정 [항목] [값] */
public class SlideAdminCommand implements CommandExecutor, TabCompleter {

    private record Setting(String path, double min, double max, boolean integer, boolean bool, String unit) {}

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("작동", new Setting("enabled", 0, 0, false, true, ""));
        SETTINGS.put("거리", new Setting("slide.distance", 1, 40, false, false, "블록"));
        SETTINGS.put("마찰", new Setting("slide.friction", 0.5, 0.99, false, false, "(클수록 더 미끄러짐)"));
        SETTINGS.put("쿨타임", new Setting("slide.cooldown-seconds", 0, 60, false, false, "초"));
        SETTINGS.put("점프차단", new Setting("slide.prevent-jump", 0, 0, false, true, ""));
        SETTINGS.put("입력횟수", new Setting("trigger.taps", 2, 5, true, false, "회 (Shift)"));
        SETTINGS.put("입력시간", new Setting("trigger.window-ms", 100, 2000, true, false, "ms"));
        SETTINGS.put("슬로우단계", new Setting("after.slowness-level", 0, 10, true, false, "단계 (0=끔)"));
        SETTINGS.put("슬로우시간", new Setting("after.slowness-seconds", 0, 60, false, false, "초"));
        SETTINGS.put("연타", new Setting("mash.enabled", 0, 0, false, true, ""));
        SETTINGS.put("연타속도", new Setting("mash.full-cps", 1, 30, false, false, "회/초 (이 속도에서 보너스 최대)"));
        SETTINGS.put("연타배율", new Setting("mash.max-multiplier", 1, 5, false, false, "배 (최대 연타 시 거리 배율)"));
    }

    private final SlidingPlugin plugin;

    public SlideAdminCommand(SlidingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("baseballsliding.admin")) {
            send(sender, "권한이 없습니다.", NamedTextColor.RED);
            return true;
        }

        // 인자 없음: 전체 설정 출력
        if (args.length == 0) {
            send(sender, "===== 슬라이딩 설정 =====", NamedTextColor.GOLD);
            for (Map.Entry<String, Setting> e : SETTINGS.entrySet()) {
                send(sender, line(e.getKey(), e.getValue()), NamedTextColor.WHITE);
            }
            send(sender, "변경: /슬라이딩설정 <항목> <값>   |   /슬라이딩설정 리로드", NamedTextColor.GRAY);
            return true;
        }

        if (args[0].equals("리로드")) {
            plugin.reloadAll();
            send(sender, "설정을 다시 불러왔습니다.", NamedTextColor.GREEN);
            return true;
        }

        Setting setting = SETTINGS.get(args[0]);
        if (setting == null) {
            send(sender, "알 수 없는 항목입니다. /슬라이딩설정 으로 목록을 확인하세요.", NamedTextColor.RED);
            return true;
        }

        // 값 없음: 현재 값과 범위 안내
        if (args.length == 1) {
            send(sender, line(args[0], setting), NamedTextColor.WHITE);
            if (!setting.bool()) {
                send(sender, "범위: " + fmt(setting.min()) + " ~ " + fmt(setting.max()), NamedTextColor.GRAY);
            } else {
                send(sender, "값: 켜기 / 끄기", NamedTextColor.GRAY);
            }
            return true;
        }

        // 값 변경
        String input = args[1];
        if (setting.bool()) {
            Boolean value = parseBool(input);
            if (value == null) {
                send(sender, "켜기 또는 끄기로 입력하세요.", NamedTextColor.RED);
                return true;
            }
            plugin.getConfig().set(setting.path(), value);
        } else {
            double value;
            try {
                value = Double.parseDouble(input);
            } catch (NumberFormatException ex) {
                send(sender, "숫자를 입력하세요.", NamedTextColor.RED);
                return true;
            }
            if (value < setting.min() || value > setting.max()) {
                send(sender, "범위를 벗어났습니다: " + fmt(setting.min()) + " ~ " + fmt(setting.max()),
                        NamedTextColor.RED);
                return true;
            }
            if (setting.integer()) {
                plugin.getConfig().set(setting.path(), (int) Math.round(value));
            } else {
                plugin.getConfig().set(setting.path(), value);
            }
        }

        plugin.saveConfig();
        plugin.applyConfig();
        send(sender, "변경됨: " + line(args[0], setting), NamedTextColor.GREEN);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("baseballsliding.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> keys = new ArrayList<>(SETTINGS.keySet());
            keys.add("리로드");
            return keys.stream().filter(s -> s.startsWith(args[0])).toList();
        }
        if (args.length == 2) {
            Setting s = SETTINGS.get(args[0]);
            if (s != null && s.bool()) {
                return List.of("켜기", "끄기").stream().filter(x -> x.startsWith(args[1])).toList();
            }
        }
        return List.of();
    }

    // ---------- 유틸 ----------

    private String line(String key, Setting s) {
        Object value = plugin.getConfig().get(s.path());
        String shown;
        if (s.bool()) {
            shown = Boolean.TRUE.equals(value) ? "켜짐" : "꺼짐";
        } else {
            shown = String.valueOf(value) + " " + s.unit();
        }
        return key + " = " + shown;
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase()) {
            case "켜기", "on", "true" -> true;
            case "끄기", "off", "false" -> false;
            default -> null;
        };
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static void send(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }
}

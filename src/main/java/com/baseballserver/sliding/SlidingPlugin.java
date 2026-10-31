package com.baseballserver.sliding;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class SlidingPlugin extends JavaPlugin {

    private SlideSettings settings;
    private SlideManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new SlideSettings();
        settings.load(getConfig());

        manager = new SlideManager(this, settings);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.start();

        PluginCommand cmd = getCommand("슬라이딩설정");
        if (cmd != null) {
            SlideAdminCommand admin = new SlideAdminCommand(this);
            cmd.setExecutor(admin);
            cmd.setTabCompleter(admin);
        } else {
            getLogger().warning("명령어를 등록하지 못했습니다: 슬라이딩설정");
        }

        getLogger().info("BaseballSliding 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public SlideSettings settings() {
        return settings;
    }

    /** config.yml 을 다시 읽어 설정에 반영합니다. */
    public void reloadAll() {
        reloadConfig();
        settings.load(getConfig());
    }

    /** 현재 config 값을 메모리 설정에 반영합니다 (명령어로 값을 바꾼 뒤 호출). */
    public void applyConfig() {
        settings.load(getConfig());
    }
}

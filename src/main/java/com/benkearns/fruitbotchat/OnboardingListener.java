package com.benkearns.fruitbotchat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public class OnboardingListener implements Listener {
    private final FruitbotChat plugin;
    private File welcomeFile;
    private File rulesFile;
    private File checkupsFile;
    private File mutedFile;
    private FileConfiguration welcomeCfg;
    private FileConfiguration rulesCfg;
    private FileConfiguration checkupsCfg;
    private FileConfiguration mutedCfg;
    private boolean welcomeEnabled;
    private boolean welcomeBackEnabled;
    private boolean checkupEnabled;
    private long welcomeDelayTicks;
    private long welcomeBackDelayTicks;
    private long checkupDelayTicks;

    public OnboardingListener(FruitbotChat plugin) {
        this.plugin = plugin;
        loadStores();
        loadConfig();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (welcomeEnabled && !isMuted(id)) {
            if (!hasSentWelcome(id)) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player now = Bukkit.getPlayer(id);
                        if (now == null || !now.isOnline()) return;
                        now.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fWelcome to Fruit Survival! Please read /rules. Join our Discord with /member or /discord to obtain Member rank. Use /r to reply to Fruitbot if you need help."));
                        plugin.markFruitbotAsLastSenderIfUnset(id);
                        setSentWelcome(id);
                        saveStores();
                    }
                }.runTaskLater(plugin, welcomeDelayTicks);
            } else if (welcomeBackEnabled) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player now = Bukkit.getPlayer(id);
                        if (now == null || !now.isOnline()) return;
                        now.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fWelcome back, " + now.getName())) ;
                        plugin.markFruitbotAsLastSenderIfUnset(id);
                    }
                }.runTaskLater(plugin, welcomeBackDelayTicks);
            }
        }
        if (checkupEnabled && !hasSentCheckup(id)) {
            scheduleCheckup(id);
        }

        if (plugin.isReviewNoticeActive() && p.hasPermission("fruitbotchat.admin")) {
            plugin.addStaffPending(id);
            new BukkitRunnable() {
                @Override
                public void run() {
                    Player now = Bukkit.getPlayer(id);
                    if (now == null || !now.isOnline()) return;
                    if (plugin.isStaffPending(id)) {
                        now.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fFruitbot has detected and created a suspicious mining path for review. Reply to confirm you have reviewed this notice."));
                        plugin.markFruitbotAsLastSenderIfUnset(id);
                    }
                }
            }.runTaskLater(plugin, 2L * 20L);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') return;
        String[] parts = raw.substring(1).split(" ", 2);
        if (parts.length == 0) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.equals("rules")) {
            UUID id = event.getPlayer().getUniqueId();
            if (!hasReadRules(id)) {
                setReadRules(id);
                saveStores();
            }
        }
    }

    private void scheduleCheckup(UUID id) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) return;
                if (isMuted(id)) return;
                if (hasSentCheckup(id)) return;
                p.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fJust checking in. Need any help? Reply with /r and I can assist or message a mod if one is online."));
                plugin.markFruitbotAsLastSenderIfUnset(id);
                plugin.markFruitbotAsLastTarget(id);
                setSentCheckup(id);
                saveStores();
            }
        }.runTaskLater(plugin, checkupDelayTicks);
    }

    private void loadStores() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            welcomeFile = new File(plugin.getDataFolder(), "welcome-sent.yml");
            rulesFile = new File(plugin.getDataFolder(), "rules-read.yml");
            checkupsFile = new File(plugin.getDataFolder(), "checkup-sent.yml");
            mutedFile = new File(plugin.getDataFolder(), "muted.yml");
            if (!welcomeFile.exists()) welcomeFile.createNewFile();
            if (!rulesFile.exists()) rulesFile.createNewFile();
            if (!checkupsFile.exists()) checkupsFile.createNewFile();
            if (!mutedFile.exists()) mutedFile.createNewFile();
            welcomeCfg = YamlConfiguration.loadConfiguration(welcomeFile);
            rulesCfg = YamlConfiguration.loadConfiguration(rulesFile);
            checkupsCfg = YamlConfiguration.loadConfiguration(checkupsFile);
            mutedCfg = YamlConfiguration.loadConfiguration(mutedFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed initializing data files: " + e.getMessage());
        }
    }

    private void saveStores() {
        try {
            if (welcomeCfg != null && welcomeFile != null) welcomeCfg.save(welcomeFile);
            if (rulesCfg != null && rulesFile != null) rulesCfg.save(rulesFile);
            if (checkupsCfg != null && checkupsFile != null) checkupsCfg.save(checkupsFile);
            if (mutedCfg != null && mutedFile != null) mutedCfg.save(mutedFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed saving data files: " + e.getMessage());
        }
    }

    public void loadConfig() {
        FileConfiguration cfg = plugin.getConfig();
        welcomeEnabled = cfg.getBoolean("onboarding.welcome-enabled", true);
        welcomeBackEnabled = cfg.getBoolean("onboarding.welcome-back-enabled", true);
        checkupEnabled = cfg.getBoolean("onboarding.checkup-enabled", true);
        welcomeDelayTicks = cfg.getLong("onboarding.welcome-delay-seconds", 10L) * 20L;
        welcomeBackDelayTicks = cfg.getLong("onboarding.welcome-back-delay-seconds", 5L) * 20L;
        checkupDelayTicks = cfg.getLong("onboarding.checkup-delay-seconds", 900L) * 20L;
    }

    private boolean hasSentWelcome(UUID id) {
        return welcomeCfg.getBoolean(id.toString(), false);
    }

    private void setSentWelcome(UUID id) {
        welcomeCfg.set(id.toString(), true);
    }

    private boolean hasReadRules(UUID id) {
        return rulesCfg.getBoolean(id.toString(), false);
    }

    private void setReadRules(UUID id) {
        rulesCfg.set(id.toString(), true);
    }

    private boolean hasSentCheckup(UUID id) {
        return checkupsCfg.getBoolean(id.toString(), false);
    }

    private void setSentCheckup(UUID id) {
        checkupsCfg.set(id.toString(), true);
    }

    public boolean isMuted(UUID id) {
        return mutedCfg.getBoolean(id.toString(), false);
    }

    public void setMuted(UUID id, boolean muted) {
        mutedCfg.set(id.toString(), muted);
        saveStores();
    }
}

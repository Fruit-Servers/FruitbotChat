package com.benkearns.fruitbotchat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.text.SimpleDateFormat;

public final class FruitbotChat extends JavaPlugin {
    private static FruitbotChat instance;
    private FileConfiguration config;
    private FileConfiguration responses;
    private FileConfiguration unknownMessages;
    private File unknownMessagesFile;
    private final Map<UUID, UUID> lastMessagedBy = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> lastTargetWasFruitbot = new HashSet<>();
    private final Set<UUID> staffPendingReview = ConcurrentHashMap.newKeySet();
    private long cooldownTime;
    private String cooldownMessage;
    private String noPermissionMessage;
    private String playerOnlyMessage;
    private String noTargetMessage;
    private String responseFormat;
    private String playerFormat;
    private long responseDelayMs;
    private boolean xrayTrackerEnabled;
    private volatile boolean reviewNoticeActive;
    private OnboardingListener onboardingListener;
    private final Set<UUID> pendingMuteConfirm = ConcurrentHashMap.newKeySet();
    private boolean spyEnabled;
    private boolean fruitbotEnabled;
    private final Map<UUID, PendingConfigChange> pendingConfig = new ConcurrentHashMap<>();
    private final Set<UUID> pendingReload = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadResponses();
        loadUnknownMessages();
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(new FruitbotCommandListener(this), this);
        OnboardingListener onboarding = new OnboardingListener(this);
        Bukkit.getPluginManager().registerEvents(onboarding, this);
        this.onboardingListener = onboarding;
        Bukkit.getPluginManager().registerEvents(new FruitbotTabCompleteListener(), this);
        Bukkit.getPluginManager().registerEvents(new XrayTrackerListener(this), this);
        getLogger().info("FruitbotChat has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("FruitbotChat has been disabled!");
    }
    
    void processMessage(Player player, String message) {
        if (!fruitbotEnabled) {
            player.sendMessage("Fruitbot is currently disabled. Contact staff for more information.");
            return;
        }
        lastMessagedBy.put(player.getUniqueId(), player.getUniqueId());
        lastTargetWasFruitbot.add(player.getUniqueId());
        String echoToBot = color("&6[&cMe &6-> &cFruitbot&6] &f" + message);
        String playerMessage = color(playerFormat.replace("%player%", player.getName()).replace("%message%", message));

        Bukkit.getOnlinePlayers().stream()
             .filter(p -> !p.getUniqueId().equals(player.getUniqueId()))
             .forEach(p -> {
                 if (spyEnabled && p.hasPermission("essentials.socialspy")) {
                     String spy = color("&8[BotSpy] &7" + player.getName() + " -> Fruitbot: " + ChatColor.stripColor(message));
                     p.sendMessage(spy);
                 }
             });

        getLogger().info("[FruitBot] " + ChatColor.stripColor(playerMessage));
        player.sendMessage(echoToBot);
        long delayTicks = Math.max(0L, responseDelayMs) / 50L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            ResponseResult result = getResponseWithKey(message);
            if (result != null && "default".equalsIgnoreCase(result.key)) {
                logUnknownMessage(player, message);
            }
            if (result != null && "mute".equalsIgnoreCase(result.key)) {
                pendingMuteConfirm.add(player.getUniqueId());
            }
            String formattedResponse = color("&6[&cFruitbot &6-> &cMe&6] &f" + (result == null ? "" : result.text));
            player.sendMessage(formattedResponse);
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.getUniqueId().equals(player.getUniqueId()))
                .forEach(p -> {
                    if (spyEnabled && p.hasPermission("essentials.socialspy")) {
                        String spy = color("&8[BotSpy] &7Fruitbot -> " + player.getName() + ": " + ChatColor.stripColor(result == null ? "" : result.text));
                        p.sendMessage(spy);
                    }
                });
        }, delayTicks);
    }
    
    private ResponseResult getResponseWithKey(String originalMessage) {
        String msg = originalMessage == null ? "" : originalMessage.toLowerCase(Locale.ROOT);
        boolean foundMatch = false;
        if (responses.getConfigurationSection("responses") != null) {
            for (String key : responses.getConfigurationSection("responses").getKeys(false)) {
                String k = key.toLowerCase(Locale.ROOT);
                String pattern = "(?i)(?<![A-Za-z0-9])" + Pattern.quote(k) + "(?![A-Za-z0-9])";
                if (Pattern.compile(pattern).matcher(msg).find()) {
                    List<String> possibleResponses = responses.getStringList("responses." + key);
                    if (!possibleResponses.isEmpty()) {
                        String text = possibleResponses.get(new Random().nextInt(possibleResponses.size()));
                        foundMatch = true;
                        return new ResponseResult(key, text);
                    }
                }
            }
        }
        List<String> defaultResponses = responses.getStringList("default");
        if (!defaultResponses.isEmpty()) {
            return new ResponseResult("default", defaultResponses.get(new Random().nextInt(defaultResponses.size())));
        }
        return new ResponseResult("default", "I'm not sure how to respond to that. Can you try asking me something else?");
    }
    
    boolean isOnCooldown(Player player) {
        if (player.hasPermission("fruitbotchat.admin")) {
            return false;
        }
        
        if (!cooldowns.containsKey(player.getUniqueId())) {
            return false;
        }
        
        long secondsLeft = ((cooldowns.get(player.getUniqueId()) + (cooldownTime * 1000)) - System.currentTimeMillis()) / 1000;
        return secondsLeft > 0;
    }
    
    void setCooldown(Player player) {
        if (!player.hasPermission("fruitbotchat.admin")) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }
    
    private void loadConfigValues() {
        cooldownTime = getConfig().getLong("cooldown-seconds", 3);
        cooldownMessage = color(getConfig().getString("messages.cooldown", "&cPlease wait before messaging FruitBot again!"));
        noPermissionMessage = color(getConfig().getString("messages.no-permission", "&cYou don't have permission to use FruitBot!"));
        playerOnlyMessage = color(getConfig().getString("messages.player-only", "&cOnly players can use this command!"));
        noTargetMessage = color(getConfig().getString("messages.no-target", "&cNo target player found!"));
        responseFormat = getConfig().getString("formats.response", "&a[FruitBot] &f%message%");
        playerFormat = getConfig().getString("formats.player-message", "%player%: %message%");
        responseDelayMs = getConfig().getLong("response-delay-ms", 1000L);
        xrayTrackerEnabled = getConfig().getBoolean("xray-tracker-enabled", true);
        spyEnabled = getConfig().getBoolean("spy-enabled", true);
        fruitbotEnabled = getConfig().getBoolean("fruitbot-enabled", true);
    }
    
    private void loadResponses() {
        File responsesFile = new File(getDataFolder(), "responses.yml");
        if (!responsesFile.exists()) {
            saveResource("responses.yml", false);
        }
        
        responses = YamlConfiguration.loadConfiguration(responsesFile);
    }
    
    private void loadUnknownMessages() {
        unknownMessagesFile = new File(getDataFolder(), "unknown_messages.yml");
        if (!unknownMessagesFile.exists()) {
            try {
                unknownMessagesFile.createNewFile();
            } catch (java.io.IOException e) {
                getLogger().warning("Could not create unknown_messages.yml: " + e.getMessage());
            }
        }
        unknownMessages = YamlConfiguration.loadConfiguration(unknownMessagesFile);
    }
    
    private void logUnknownMessage(Player player, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String key = "messages." + System.currentTimeMillis();
        unknownMessages.set(key + ".player", player.getName());
        unknownMessages.set(key + ".uuid", player.getUniqueId().toString());
        unknownMessages.set(key + ".message", message);
        unknownMessages.set(key + ".timestamp", timestamp);
        try {
            unknownMessages.save(unknownMessagesFile);
        } catch (java.io.IOException e) {
            getLogger().warning("Could not save unknown message: " + e.getMessage());
        }
    }
    
    public static FruitbotChat getInstance() {
        return instance;
    }
    
    public FileConfiguration getResponses() {
        return responses;
    }

    public boolean wasLastTargetFruitbot(UUID playerId) {
        return lastTargetWasFruitbot.contains(playerId);
    }

    public void markFruitbotAsLastTarget(UUID playerId) {
        lastTargetWasFruitbot.add(playerId);
    }

    public void clearLastTarget(UUID playerId) {
        lastTargetWasFruitbot.remove(playerId);
    }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public boolean isXrayTrackerEnabled() {
        return xrayTrackerEnabled;
    }

    public void setXrayTrackerEnabled(boolean enabled) {
        this.xrayTrackerEnabled = enabled;
        getConfig().set("xray-tracker-enabled", enabled);
        saveConfig();
    }

    public void setWelcomeEnabled(boolean enabled) {
        getConfig().set("onboarding.welcome-enabled", enabled);
        saveConfig();
        if (onboardingListener != null) onboardingListener.loadConfig();
    }

    public void setWelcomeBackEnabled(boolean enabled) {
        getConfig().set("onboarding.welcome-back-enabled", enabled);
        saveConfig();
        if (onboardingListener != null) onboardingListener.loadConfig();
    }

    public void setCheckupEnabled(boolean enabled) {
        getConfig().set("onboarding.checkup-enabled", enabled);
        saveConfig();
        if (onboardingListener != null) onboardingListener.loadConfig();
    }

    public void setSpyEnabled(boolean enabled) {
        this.spyEnabled = enabled;
        getConfig().set("spy-enabled", enabled);
        saveConfig();
    }

    public void setFruitbotEnabled(boolean enabled) {
        this.fruitbotEnabled = enabled;
        getConfig().set("fruitbot-enabled", enabled);
        saveConfig();
    }

    public void markReviewNoticeActive() {
        reviewNoticeActive = true;
    }

    public boolean isReviewNoticeActive() {
        return reviewNoticeActive;
    }

    public void addStaffPending(UUID id) {
        staffPendingReview.add(id);
    }

    public void removeStaffPending(UUID id) {
        staffPendingReview.remove(id);
    }

    public boolean isStaffPending(UUID id) {
        return staffPendingReview.contains(id);
    }

    public void addOnlineStaffToPending() {
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (p.hasPermission("fruitbotchat.admin")) {
                addStaffPending(p.getUniqueId());
            }
        });
    }

    public void dispatchConsole(String cmd) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    public String nowStamp() {
        return new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
    }

    public void markFruitbotAsLastSenderIfUnset(UUID playerId) {
        if (!lastMessagedBy.containsKey(playerId)) {
            lastMessagedBy.put(playerId, playerId);
            lastTargetWasFruitbot.add(playerId);
        }
    }

    public static class PendingConfigChange {
        public final String path;
        public final Object value;
        public PendingConfigChange(String path, Object value) { this.path = path; this.value = value; }
    }

    public boolean isPendingMute(UUID id) {
        return pendingMuteConfirm.contains(id);
    }

    public void clearPendingMute(UUID id) {
        pendingMuteConfirm.remove(id);
    }

    public boolean isPlayerMuted(UUID id) {
        if (onboardingListener == null) return false;
        return onboardingListener.isMuted(id);
    }

    public void setPlayerMuted(UUID id, boolean muted) {
        if (onboardingListener != null) onboardingListener.setMuted(id, muted);
    }

    private static class ResponseResult {
        public final String key;
        public final String text;
        public ResponseResult(String key, String text) { this.key = key; this.text = text; }
    }

    public boolean handleAdminConfigMessage(Player player, String message) {
        if (!player.hasPermission("fruitbotchat.admin")) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        PendingConfigChange change = parseConfigIntent(lower);
        if (change == null) return false;
        pendingConfig.put(player.getUniqueId(), change);
        player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fWould you like to set " + change.path + " to " + String.valueOf(change.value) + "? Please confirm with Y/N"));
        return true;
    }

    private PendingConfigChange parseConfigIntent(String msg) {
        boolean enable = msg.contains("enable");
        boolean disable = msg.contains("disable") || msg.contains("turn off");
        Boolean boolVal = enable && !disable ? Boolean.TRUE : (disable && !enable ? Boolean.FALSE : null);

        if (containsAny(msg, "welcome back")) {
            if (boolVal != null) return new PendingConfigChange("onboarding.welcome-back-enabled", boolVal);
            Long secs = extractSeconds(msg);
            if (secs != null) return new PendingConfigChange("onboarding.welcome-back-delay-seconds", secs);
        }
        if (containsAny(msg, "welcome")) {
            if (boolVal != null) return new PendingConfigChange("onboarding.welcome-enabled", boolVal);
            Long secs = extractSeconds(msg);
            if (secs != null) return new PendingConfigChange("onboarding.welcome-delay-seconds", secs);
        }
        if (containsAny(msg, "checkup", "check up")) {
            if (boolVal != null) return new PendingConfigChange("onboarding.checkup-enabled", boolVal);
            Long secs = extractSeconds(msg);
            if (secs != null) return new PendingConfigChange("onboarding.checkup-delay-seconds", secs);
        }
        if (containsAny(msg, "xray", "x-ray")) {
            if (boolVal != null) return new PendingConfigChange("xray-tracker-enabled", boolVal);
        }
        if (containsAny(msg, "spy", "socialspy", "botspy")) {
            if (boolVal != null) return new PendingConfigChange("spy-enabled", boolVal);
        }
        if (containsAny(msg, "response delay", "reply delay", "bot delay")) {
            Long ms = extractMs(msg);
            if (ms != null) return new PendingConfigChange("response-delay-ms", ms);
        }
        return null;
    }

    private boolean containsAny(String msg, String... keys) {
        for (String k : keys) if (msg.contains(k)) return true;
        return false;
    }

    private Long extractSeconds(String msg) {
        Long n = extractNumber(msg);
        if (n == null) return null;
        if (msg.contains("ms")) return Math.max(0L, n / 1000L);
        return n;
    }

    private Long extractMs(String msg) {
        Long n = extractNumber(msg);
        if (n == null) return null;
        if (msg.contains("second")) return n * 1000L;
        return n;
    }

    private Long extractNumber(String msg) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(msg);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception ignored) {}
        return null;
    }

    public boolean confirmPendingConfig(Player player, String input) {
        PendingConfigChange change = pendingConfig.get(player.getUniqueId());
        if (change == null) return false;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("y") || s.equals("yes")) {
            applyConfigChange(change);
            pendingConfig.remove(player.getUniqueId());
            player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fSetting applied: " + change.path + " = " + String.valueOf(change.value)));
            player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fReload now? Please confirm with Y/N"));
            pendingReload.add(player.getUniqueId());
        } else {
            pendingConfig.remove(player.getUniqueId());
            player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fCancelled."));
        }
        return true;
    }

    public boolean confirmPendingReload(Player player, String input) {
        if (!pendingReload.contains(player.getUniqueId())) return false;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("y") || s.equals("yes")) {
            pendingReload.remove(player.getUniqueId());
            startReloadCountdown(player);
        } else {
            pendingReload.remove(player.getUniqueId());
            player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fReload cancelled."));
        }
        return true;
    }

    private void applyConfigChange(PendingConfigChange change) {
        getConfig().set(change.path, change.value);
        saveConfig();
        if ("onboarding.welcome-enabled".equals(change.path)
            || "onboarding.welcome-back-enabled".equals(change.path)
            || "onboarding.checkup-enabled".equals(change.path)
            || "onboarding.welcome-delay-seconds".equals(change.path)
            || "onboarding.welcome-back-delay-seconds".equals(change.path)
            || "onboarding.checkup-delay-seconds".equals(change.path)) {
            if (onboardingListener != null) onboardingListener.loadConfig();
        }
        if ("response-delay-ms".equals(change.path)) {
            this.responseDelayMs = getConfig().getLong("response-delay-ms", 1000L);
        }
        if ("xray-tracker-enabled".equals(change.path)) {
            this.xrayTrackerEnabled = getConfig().getBoolean("xray-tracker-enabled", true);
        }
        if ("spy-enabled".equals(change.path)) {
            this.spyEnabled = getConfig().getBoolean("spy-enabled", true);
        }
    }

    private void startReloadCountdown(Player player) {
        final int[] count = {3};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (count[0] > 0) {
                player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &f" + count[0]));
                count[0]--;
            } else {
                player.sendMessage(color("&6[&cFruitbot &6-> &cMe&6] &fPlugin reloaded!"));
                task.cancel();
                Bukkit.getPluginManager().disablePlugin(this);
                Bukkit.getPluginManager().enablePlugin(this);
            }
        }, 0L, 20L);
    }
}

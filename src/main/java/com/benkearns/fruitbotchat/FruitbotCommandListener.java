package com.benkearns.fruitbotchat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class FruitbotCommandListener implements Listener {
    private static final Set<String> MSG_ALIASES = new HashSet<>(Arrays.asList(
            "msg", "message", "m", "tell", "t", "w", "whisper"
    ));
    private static final Set<String> REPLY_ALIASES = new HashSet<>(Arrays.asList(
            "r", "reply"
    ));

    private final FruitbotChat plugin;

    public FruitbotCommandListener(FruitbotChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') return;

        String[] parts = raw.substring(1).split(" ", 3);
        if (parts.length == 0) return;

        String label = parts[0].toLowerCase(Locale.ROOT);
        Player player = event.getPlayer();

        if (label.equals("fruitbotchat")) {
            if (!player.hasPermission("fruitbotchat.admin")) return;
            if (parts.length >= 2) {
                String action = parts[1].toLowerCase(Locale.ROOT);
                if (action.equals("enable") || action.equals("disable")) {
                    boolean enable = action.equals("enable");
                    plugin.setFruitbotEnabled(enable);
                    player.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fFruitbot " + (enable ? "enabled" : "disabled")));
                    event.setCancelled(true);
                    return;
                }
            }
            player.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fUsage: /fruitbotchat <enable|disable>"));
            event.setCancelled(true);
            return;
        }

        if (MSG_ALIASES.contains(label)) {
            if (parts.length < 3) return;
            String target = parts[1].toLowerCase(Locale.ROOT);
            if (target.equals("fruitbot") || target.equals("fruitbotchat")) {
                if (!player.hasPermission("fruitbotchat.use")) {
                    player.sendMessage(plugin.color("&cYou don't have permission to message FruitBot."));
                    event.setCancelled(true);
                    return;
                }
                String message = parts.length >= 3 ? parts[2] : "";
                String lowerMsg = message.toLowerCase(Locale.ROOT).trim();
                if (lowerMsg.equals("unmute")) {
                    event.setCancelled(true);
                    plugin.setPlayerMuted(player.getUniqueId(), false);
                    plugin.clearPendingMute(player.getUniqueId());
                    player.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fUnmuted. You will receive Fruitbot onboarding messages again."));
                    return;
                }
                // Allow admin configuration messages to bypass cooldown
                if (!player.hasPermission("fruitbotchat.admin") && plugin.isOnCooldown(player)) {
                    player.sendMessage(plugin.color(plugin.getConfig().getString("messages.cooldown", "&cPlease wait before messaging FruitBot again!")));
                    event.setCancelled(true);
                    return;
                }
                if (message.isEmpty()) return;
                event.setCancelled(true);
                if (player.hasPermission("fruitbotchat.admin")) {
                    if (plugin.handleAdminConfigMessage(player, message)) {
                        return;
                    }
                }
                plugin.processMessage(player, message);
                plugin.setCooldown(player);
                plugin.markFruitbotAsLastTarget(player.getUniqueId());
            }
            return;
        }

        if (REPLY_ALIASES.contains(label)) {
            if (!plugin.wasLastTargetFruitbot(player.getUniqueId())) return;
            if (parts.length < 2) return;
            if (!player.hasPermission("fruitbotchat.use")) {
                player.sendMessage(plugin.color("&cYou don't have permission to message FruitBot."));
                event.setCancelled(true);
                return;
            }
            String message = raw.substring(1 + label.length()).trim();
            String lower = message.toLowerCase(Locale.ROOT);
            if (plugin.isPendingMute(player.getUniqueId()) && lower.equals("mute")) {
                event.setCancelled(true);
                plugin.setPlayerMuted(player.getUniqueId(), true);
                plugin.clearPendingMute(player.getUniqueId());
                player.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fMuted. You will no longer receive Fruitbot onboarding messages."));
                return;
            }
            if (!player.hasPermission("fruitbotchat.admin") && plugin.isOnCooldown(player)) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.cooldown", "&cPlease wait before messaging FruitBot again!")));
                event.setCancelled(true);
                return;
            }
            if (message.isEmpty()) return;
            event.setCancelled(true);
            if (plugin.confirmPendingReload(player, message)) {
                return;
            }
            if (plugin.confirmPendingConfig(player, message)) {
                return;
            }
            if (plugin.isStaffPending(player.getUniqueId())) {
                plugin.removeStaffPending(player.getUniqueId());
                player.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fYou have confirmed your review of this path."));
                return;
            }
            plugin.processMessage(player, message);
            plugin.setCooldown(player);
            plugin.markFruitbotAsLastTarget(player.getUniqueId());
        }
    }
}

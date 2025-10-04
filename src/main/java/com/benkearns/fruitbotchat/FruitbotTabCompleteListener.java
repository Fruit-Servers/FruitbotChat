package com.benkearns.fruitbotchat;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class FruitbotTabCompleteListener implements Listener {
    private static final Set<String> MSG_ALIASES = new HashSet<>(Arrays.asList(
            "msg", "message", "m", "tell", "t", "w", "whisper"
    ));

    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent event) {
        String buffer = event.getBuffer();
        if (buffer == null || buffer.length() == 0 || buffer.charAt(0) != '/') return;
        String[] parts = buffer.substring(1).split(" ", -1);
        if (parts.length == 0) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (MSG_ALIASES.contains(label)) {
            if (parts.length == 2) {
                String partial = parts[1].toLowerCase(Locale.ROOT);
                String suggestion = "fruitbot";
                if (suggestion.startsWith(partial) && !event.getCompletions().contains(suggestion)) {
                    event.getCompletions().add(suggestion);
                }
            }
        }

        if ("fruitbot".equals(label)) {
            if (parts.length == 2) {
                String partial = parts[1].toLowerCase(Locale.ROOT);
                for (String opt : Arrays.asList("enable", "disable")) {
                    if (opt.startsWith(partial) && !event.getCompletions().contains(opt)) {
                        event.getCompletions().add(opt);
                    }
                }
            }
        }
    }
}

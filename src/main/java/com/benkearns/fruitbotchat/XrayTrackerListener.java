package com.benkearns.fruitbotchat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class XrayTrackerListener implements Listener {
    private final FruitbotChat plugin;
    private static class BreakSample {
        final Material type;
        final long atMs;
        final int y;
        BreakSample(Material type, long atMs, int y) { this.type = type; this.atMs = atMs; this.y = y; }
    }

    private final Map<UUID, Deque<BreakSample>> recentBreaks = new HashMap<>();
    private final Map<UUID, Long> lastTriggered = new HashMap<>();
    private final Map<UUID, Set<Long>> countedDiamondBlocks = new HashMap<>();
    private final Map<UUID, Deque<BreakSample>> recentVeins = new HashMap<>();
    private static final Set<Material> BASE_BLOCKS = EnumSet.of(
            Material.DEEPSLATE,
            Material.STONE,
            Material.ANDESITE,
            Material.DIORITE,
            Material.GRANITE,
            Material.TUFF,
            Material.DEEPSLATE_TILES,
            Material.DEEPSLATE_BRICKS,
            Material.COBBLESTONE,
            Material.GRAVEL,
            Material.CALCITE,
            Material.DRIPSTONE_BLOCK,
            Material.BASALT,
            Material.SMOOTH_BASALT
    );
    private static final Set<Material> DIAMOND_ORES = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
    private static final Set<Material> OTHER_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE
    );
    private static final int MAX_TRACK = 60;
    private static final long TRIGGER_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final double BASE_RATIO_MIN = 0.80;
    private static final double OTHER_ORE_RATIO_MAX = 0.10;
    private static final double DIAMONDS_PER_MIN_MIN = 1.2;
    private static final int DIAMOND_Y_MAX_MEAN = 20;
    private static final double UNDERGROUND_Y = 32.0;
    private static final long MOVE_SAMPLE_COOLDOWN_MS = 1500L;
    private final Map<UUID, Long> lastMoveSample = new HashMap<>();
    private static final int VEIN_MIN = 5;
    private static final long VEIN_WINDOW_MS = 12L * 60L * 1000L;
    private static final int MAX_VEINS_TRACK = 30;

    public XrayTrackerListener(FruitbotChat plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                recentBreaks.clear();
            }
        }.runTaskTimer(plugin, 5L * 60L * 20L, 5L * 60L * 20L);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isXrayTrackerEnabled()) return;
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        Material type = event.getBlock().getType();
        int y = event.getBlock().getY();

        Deque<BreakSample> q = recentBreaks.computeIfAbsent(id, k -> new ArrayDeque<>(MAX_TRACK));
        long now = System.currentTimeMillis();
        if (DIAMOND_ORES.contains(type)) {
            if (!isDiamondCounted(id, event.getBlock())) {
                int avgY = markDiamondVeinCounted(id, event.getBlock());
                if (q.size() >= MAX_TRACK) q.removeFirst();
                q.addLast(new BreakSample(Material.DIAMOND_ORE, now, avgY));
                Deque<BreakSample> veins = recentVeins.computeIfAbsent(id, k -> new ArrayDeque<>(MAX_VEINS_TRACK));
                if (veins.size() >= MAX_VEINS_TRACK) veins.removeFirst();
                veins.addLast(new BreakSample(Material.DIAMOND_ORE, now, avgY));
                trimOldVeins(veins, now);
            }
        } else {
            if (q.size() >= MAX_TRACK) q.removeFirst();
            q.addLast(new BreakSample(type, now, y));
        }

        if (!shouldEvaluate(q)) return;
        if (System.currentTimeMillis() - lastTriggered.getOrDefault(id, 0L) < TRIGGER_COOLDOWN_MS) return;

        Deque<BreakSample> veins = recentVeins.computeIfAbsent(id, k -> new ArrayDeque<>(MAX_VEINS_TRACK));
        trimOldVeins(veins, System.currentTimeMillis());
        if (veins.size() < VEIN_MIN) return;

        long firstMs = veins.peekFirst().atMs;
        long lastMs = veins.peekLast().atMs;
        long spanMs = Math.max(1L, lastMs - firstMs);

        int size = 0;
        int baseCount = 0;
        int diamondCount = veins.size();
        int otherOreCount = 0;
        int diamondYTotal = 0;

        for (BreakSample s : veins) {
            diamondYTotal += s.y;
        }

        for (BreakSample s : q) {
            if (s.atMs < firstMs) continue;
            if (BASE_BLOCKS.contains(s.type)) baseCount++;
            else if (DIAMOND_ORES.contains(s.type)) {}
            else if (OTHER_ORES.contains(s.type)) otherOreCount++;
            else return;
            size++;
        }
        if (size == 0) return;
        double baseRatio = baseCount / (double) size;
        double otherOreRatio = otherOreCount / (double) size;
        double minutes = spanMs / 60000.0;
        double diamondsPerMinute = diamondCount / Math.max(0.1, minutes);
        int avgDiamondY = diamondCount == 0 ? 64 : (diamondYTotal / diamondCount);

        int score = 0;
        if (baseRatio >= BASE_RATIO_MIN) score += 2;
        if (diamondsPerMinute >= DIAMONDS_PER_MIN_MIN) score += 2;
        if (avgDiamondY <= DIAMOND_Y_MAX_MEAN) score += 1;
        if (otherOreRatio <= OTHER_ORE_RATIO_MAX) score += 1;

        if (score < 4) return;

        lastTriggered.put(id, System.currentTimeMillis());
        startPathWorkflow(p);
    }

    private boolean shouldEvaluate(Deque<BreakSample> q) {
        return !q.isEmpty();
    }

    private void trimOldVeins(Deque<BreakSample> veins, long now) {
        while (!veins.isEmpty()) {
            BreakSample s = veins.peekFirst();
            if (s == null) break;
            if (now - s.atMs > VEIN_WINDOW_MS) veins.removeFirst();
            else break;
        }
    }

    private void startPathWorkflow(Player p) {
        String playerName = p.getName();
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.dispatchConsole("path " + playerName + " 5m");
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        String stamp = plugin.nowStamp();
                        plugin.dispatchConsole("savepath [FRUITBOT]-" + playerName + "-" + stamp);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                plugin.dispatchConsole("unloadpath");
                                boolean any = false;
                                for (Player s : Bukkit.getOnlinePlayers()) {
                                    if (s.hasPermission("fruitbotchat.admin")) {
                                        any = true;
                                        plugin.addStaffPending(s.getUniqueId());
                                        s.sendMessage(plugin.color("&6[&cFruitbot &6-> &cMe&6] &fFruitbot has detected and saved a suspicious mining path for review."));
                                    }
                                }
                                if (!any) {
                                    plugin.markReviewNoticeActive();
                                }
                            }
                        }.runTaskLater(plugin, 1L * 20L);
                    }
                }.runTaskLater(plugin, 1L * 20L);
            }
        }.runTaskLater(plugin, 0L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!plugin.isXrayTrackerEnabled()) return;
        if (p.getLocation().getY() > UNDERGROUND_Y) return;
        long now = System.currentTimeMillis();
        long last = lastMoveSample.getOrDefault(id, 0L);
        if (now - last < MOVE_SAMPLE_COOLDOWN_MS) return;
        lastMoveSample.put(id, now);
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        Block head = w.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
        Block feet = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (!(BASE_BLOCKS.contains(feet.getType()) || BASE_BLOCKS.contains(head.getType()))) return;
        Deque<BreakSample> q = recentBreaks.computeIfAbsent(id, k -> new ArrayDeque<>(MAX_TRACK));
        if (q.size() >= MAX_TRACK) q.removeFirst();
        q.addLast(new BreakSample(feet.getType(), now, loc.getBlockY()));
    }

    private boolean isDiamondCounted(UUID id, Block start) {
        Set<Long> set = countedDiamondBlocks.computeIfAbsent(id, k -> new HashSet<>());
        long h = hash(start.getWorld(), start.getX(), start.getY(), start.getZ());
        return set.contains(h);
    }

    private int markDiamondVeinCounted(UUID id, Block start) {
        Set<Long> set = countedDiamondBlocks.computeIfAbsent(id, k -> new HashSet<>());
        ArrayDeque<Block> dq = new ArrayDeque<>();
        dq.add(start);
        World w = start.getWorld();
        long sumY = 0;
        int cnt = 0;
        while (!dq.isEmpty()) {
            Block b = dq.pollFirst();
            if (b == null) continue;
            if (!DIAMOND_ORES.contains(b.getType())) continue;
            long h = hash(w, b.getX(), b.getY(), b.getZ());
            if (!set.add(h)) continue;
            sumY += b.getY();
            cnt++;
            dq.add(w.getBlockAt(b.getX() + 1, b.getY(), b.getZ()));
            dq.add(w.getBlockAt(b.getX() - 1, b.getY(), b.getZ()));
            dq.add(w.getBlockAt(b.getX(), b.getY() + 1, b.getZ()));
            dq.add(w.getBlockAt(b.getX(), b.getY() - 1, b.getZ()));
            dq.add(w.getBlockAt(b.getX(), b.getY(), b.getZ() + 1));
            dq.add(w.getBlockAt(b.getX(), b.getY(), b.getZ() - 1));
        }
        if (cnt == 0) return start.getY();
        return (int) Math.round(sumY / (double) cnt);
    }

    private long hash(World w, int x, int y, int z) {
        long wx = x & 0x3FFFFFFFL;
        long wy = y & 0x3FFL;
        long wz = z & 0x3FFFFFFFL;
        long h = (wx << 22) ^ (wy << 12) ^ wz;
        return h ^ (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
    }
}

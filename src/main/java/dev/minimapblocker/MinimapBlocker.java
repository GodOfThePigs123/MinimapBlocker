package dev.minimapblocker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class MinimapBlocker extends JavaPlugin implements Listener {

    private static final String SECTION = "\u00a7";
    private static final String BYPASS = "minimapblocker.bypass";

    // Xaero's Minimap / World Map system-message flags (each letter prefixed by the section sign).
    private static final String XAERO_NO_MINIMAP = spaced("nominimap", false);
    private static final String XAERO_FAIR_PLAY = spaced("fairxaero", false);

    // VoxelMap legacy flags: "3 6 3 6 3 6" followed by "e" (radar) or "d" (cave mapping).
    private static final String VOXEL_NO_RADAR = voxel('e');
    private static final String VOXEL_NO_CAVE = voxel('d');

    private final List<String> codes = new ArrayList<>();
    private boolean resendOnWorldChange;
    private boolean resendOnRespawn;
    private boolean detectionEnabled;
    private boolean kick;
    private boolean logDetections;
    private String kickMessage;
    private final List<String> keywords = new ArrayList<>();

    /** Builds "§a§b§c" from "abc". */
    private static String spaced(String letters, boolean withSpaces) {
        StringBuilder sb = new StringBuilder();
        for (char c : letters.toCharArray()) {
            if (sb.length() > 0 && withSpaces) {
                sb.append(' ');
            }
            sb.append(SECTION).append(c);
        }
        return sb.toString();
    }

    private static String voxel(char last) {
        return spaced("363636", true) + " " + SECTION + last;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        codes.clear();
        keywords.clear();

        if (getConfig().getBoolean("send-disable-codes.enabled", true)) {
            if (getConfig().getBoolean("send-disable-codes.xaero-no-minimap", true)) codes.add(XAERO_NO_MINIMAP);
            if (getConfig().getBoolean("send-disable-codes.xaero-fair-play", true)) codes.add(XAERO_FAIR_PLAY);
            if (getConfig().getBoolean("send-disable-codes.voxelmap-no-radar", true)) codes.add(VOXEL_NO_RADAR);
            if (getConfig().getBoolean("send-disable-codes.voxelmap-no-cave", true)) codes.add(VOXEL_NO_CAVE);
        }
        resendOnWorldChange = getConfig().getBoolean("send-disable-codes.resend-on-world-change", true);
        resendOnRespawn = getConfig().getBoolean("send-disable-codes.resend-on-respawn", true);

        detectionEnabled = getConfig().getBoolean("channel-detection.enabled", true);
        kick = getConfig().getBoolean("channel-detection.kick", true);
        logDetections = getConfig().getBoolean("channel-detection.log-detections", true);
        kickMessage = getConfig().getString("channel-detection.kick-message",
                "&cMinimap mods are not allowed on this server.");
        for (String keyword : getConfig().getStringList("channel-detection.keywords")) {
            if (keyword != null && !keyword.isBlank()) {
                keywords.add(keyword.trim().toLowerCase(Locale.ROOT));
            }
        }
        getLogger().info(codes.size() + " disable code(s) active; channel detection "
                + (detectionEnabled ? "ON (" + keywords.size() + " keywords, kick=" + kick + ")" : "OFF"));
    }

    // ---------------------------------------------------------------------
    // Disable codes
    // ---------------------------------------------------------------------

    private void sendCodes(Player player) {
        if (!player.isOnline() || player.hasPermission(BYPASS)) {
            return;
        }
        for (String code : codes) {
            // Component.text keeps the section signs as literal characters (no legacy parsing).
            player.sendMessage(Component.text(code));
        }
    }

    private void sendCodesLater(Player player, long ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendCodes(player), ticks);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sendCodesLater(player, 20L);
        sendCodesLater(player, 100L);
        if (detectionEnabled) {
            Bukkit.getScheduler().runTaskLater(this, () -> checkAllChannels(player), 60L);
            Bukkit.getScheduler().runTaskLater(this, () -> checkAllChannels(player), 200L);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (resendOnWorldChange) {
            sendCodesLater(event.getPlayer(), 5L);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (resendOnRespawn) {
            sendCodesLater(event.getPlayer(), 5L);
        }
    }

    // ---------------------------------------------------------------------
    // Channel detection
    // ---------------------------------------------------------------------

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!detectionEnabled) {
            return;
        }
        Player player = event.getPlayer();
        String channel = event.getChannel();
        Bukkit.getScheduler().runTask(this, () -> checkChannel(player, channel));
    }

    private void checkAllChannels(Player player) {
        if (!player.isOnline()) {
            return;
        }
        for (String channel : player.getListeningPluginChannels()) {
            if (checkChannel(player, channel)) {
                return;
            }
        }
    }

    /** @return true if the channel matched and the player was handled. */
    private boolean checkChannel(Player player, String channel) {
        if (!detectionEnabled || !player.isOnline() || player.hasPermission(BYPASS)) {
            return false;
        }
        String lower = channel.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                if (logDetections) {
                    getLogger().warning(player.getName() + " registered channel '" + channel
                            + "' (matched '" + keyword + "')" + (kick ? " - kicking." : "."));
                }
                if (kick) {
                    player.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessage));
                }
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // /minimapblocker
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage("MinimapBlocker reloaded.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("resend")) {
            if (args.length == 1) {
                Bukkit.getOnlinePlayers().forEach(this::sendCodes);
                sender.sendMessage("Codes re-sent to all online players.");
            } else {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not found: " + args[1]);
                } else {
                    sendCodes(target);
                    sender.sendMessage("Codes re-sent to " + target.getName() + ".");
                }
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("channels")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[1]);
                return true;
            }
            Set<String> channels = new TreeSet<>(target.getListeningPluginChannels());
            sender.sendMessage(target.getName() + " client brand: " + target.getClientBrandName());
            sender.sendMessage("Registered channels (" + channels.size() + "): "
                    + (channels.isEmpty() ? "(none)" : String.join(", ", channels)));
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "resend", "channels");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("resend") || args[0].equalsIgnoreCase("channels"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}

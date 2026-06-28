package pl.msurvival.lobby;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MSurvivalLobby extends JavaPlugin implements Listener {

    private NamespacedKey menuItemKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuItemKey = new NamespacedKey(this, "msurvival_lobby_menu");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        getLogger().info("MSurvivalLobby wlaczony!");
    }

    private void registerCommands() {
        getCommand("lobby").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                teleportToLobby(player, true);
            }
            return true;
        });

        getCommand("setlobby").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                return true;
            }

            if (!player.hasPermission("msurvivallobby.admin")) {
                player.sendMessage(msg("no-permission"));
                return true;
            }

            Location loc = player.getLocation();
            getConfig().set("lobby.world", loc.getWorld().getName());
            getConfig().set("lobby.x", loc.getX());
            getConfig().set("lobby.y", loc.getY());
            getConfig().set("lobby.z", loc.getZ());
            getConfig().set("lobby.yaw", loc.getYaw());
            getConfig().set("lobby.pitch", loc.getPitch());
            getConfig().set("settings.lobby-world", loc.getWorld().getName());
            saveConfig();

            player.sendMessage(msg("lobby-set").replace("%world%", loc.getWorld().getName()));
            return true;
        });

        getCommand("lobbyreload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }

            reloadConfig();
            sender.sendMessage(msg("reload"));
            return true;
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (getConfig().getBoolean("settings.teleport-on-join", true)) {
            long delay = getConfig().getLong("settings.teleport-delay-ticks", 20L);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    teleportToLobby(player, false);
                }
            }, delay);
        }

        if (getConfig().getBoolean("settings.give-menu-item-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline() || hasMenuItem(player)) {
                    return;
                }

                int slot = getConfig().getInt("menu-item.slot", 4);
                ItemStack item = createMenuItem();

                if (slot >= 0 && slot <= 35) {
                    ItemStack current = player.getInventory().getItem(slot);

                    if (current == null || current.getType() == Material.AIR || isMenuItem(current)) {
                        player.getInventory().setItem(slot, item);
                        return;
                    }
                }

                player.getInventory().addItem(item);
            }, 30L);
        }
    }

    @EventHandler
    public void onMenuItemClick(PlayerInteractEvent event) {
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (!isMenuItem(event.getItem())) {
            return;
        }

        event.setCancelled(true);

        if (Bukkit.getPluginManager().getPlugin("MSurvivalKeys") != null) {
            Bukkit.dispatchCommand(event.getPlayer(), "menu");
        } else {
            teleportToLobby(event.getPlayer(), true);
        }
    }

    private void teleportToLobby(Player player, boolean message) {
        Location lobby = getLobbyLocation();

        if (lobby == null) {
            player.sendMessage(msg("lobby-not-found"));
            return;
        }

        if (getConfig().getBoolean("settings.clear-inventory-in-lobby", false)) {
            player.getInventory().clear();
        }

        player.teleport(lobby);

        if (message) {
            player.sendMessage(msg("teleported"));
        }
    }

    private Location getLobbyLocation() {
        String worldName = getConfig().getString("lobby.world", getConfig().getString("settings.lobby-world", "Lobby"));
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return null;
        }

        double x = getConfig().getDouble("lobby.x", 0.5);
        double y = getConfig().getDouble("lobby.y", 100.0);
        double z = getConfig().getDouble("lobby.z", 0.5);
        float yaw = (float) getConfig().getDouble("lobby.yaw", 0.0);
        float pitch = (float) getConfig().getDouble("lobby.pitch", 0.0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    private ItemStack createMenuItem() {
        Material material = parseMaterial(getConfig().getString("menu-item.material", "COMPASS"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("menu-item.name", "&6&lMSurvival Menu")));

            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("menu-item.lore")) {
                lore.add(color(line));
            }

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }

        return item;
    }

    private boolean hasMenuItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuItem(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(menuItemKey, PersistentDataType.STRING);
    }

    private Material parseMaterial(String value) {
        try {
            return Material.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Material.COMPASS;
        }
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String text) {
        if (text == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes('&', text);
    }
}

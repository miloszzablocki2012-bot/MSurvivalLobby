package pl.msurvival.lobby;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalLobby extends JavaPlugin implements Listener {
    private NamespacedKey menuItemKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuItemKey = new NamespacedKey(this, "msurvival_lobby_menu");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
    }

    private void registerCommands() {
        getCommand("lobby").setExecutor((sender, command, label, args) -> {
            Player target = resolveTarget(sender, args);
            if (target != null) teleport(target, getLocation("lobby"), "lobby-teleported", sender == target);
            return true;
        });

        getCommand("survival").setExecutor((sender, command, label, args) -> {
            Player target = resolveTarget(sender, args);
            if (target != null) teleport(target, getLocation("survival"), "survival-teleported", sender == target);
            return true;
        });

        getCommand("setlobby").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return true; }
            saveLocation("lobby", p.getLocation());
            p.sendMessage(msg("lobby-set").replace("%world%", p.getWorld().getName()));
            return true;
        });

        getCommand("setsurvival").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return true; }
            saveLocation("survival", p.getLocation());
            p.sendMessage(msg("survival-set").replace("%world%", p.getWorld().getName()));
            return true;
        });

        getCommand("importlobby").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length < 1) { sender.sendMessage(msg("usage-import")); return true; }
            importLobbyWorld(sender, String.join(" ", args));
            return true;
        });

        getCommand("lobbyreload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            sender.sendMessage(msg("reload"));
            return true;
        });
    }

    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 1 && sender.hasPermission("msurvivallobby.admin")) {
            Player t = Bukkit.getPlayerExact(args[0]);
            if (t == null) sender.sendMessage(color("&cNie znaleziono gracza online."));
            return t;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(color("&cKonsola musi podać gracza."));
        return null;
    }

    private void importLobbyWorld(CommandSender sender, String worldName) {
        File folder = new File(Bukkit.getWorldContainer(), worldName);
        if (!folder.exists() || !new File(folder, "level.dat").exists()) {
            sender.sendMessage(msg("import-failed"));
            sender.sendMessage(color("&7Folder świata musi być w głównym katalogu serwera i mieć &elevel.dat&7."));
            return;
        }
        sender.sendMessage(msg("importing").replace("%world%", worldName));
        if (Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import \"" + worldName + "\" normal");
        } else {
            Bukkit.createWorld(new WorldCreator(worldName));
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) { sender.sendMessage(msg("import-failed")); return; }
            getConfig().set("lobby.world", worldName);
            saveConfig();
            sender.sendMessage(msg("imported").replace("%world%", worldName));
        }, 60L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("settings.teleport-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) teleport(p, getLocation("lobby"), null, false); }, getConfig().getLong("settings.teleport-delay-ticks", 20L));
        }
        if (getConfig().getBoolean("settings.give-menu-item-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> giveMenuItem(p), 30L);
        }
    }

    @EventHandler
    public void onMenuClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(e.getItem())) return;
        e.setCancelled(true);
        String cmd = getConfig().getString("settings.menu-command", "menu");
        if (cmd != null && !cmd.isBlank()) Bukkit.dispatchCommand(e.getPlayer(), cmd);
        else teleport(e.getPlayer(), getLocation("lobby"), "lobby-teleported", true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){ if(blocked(e.getPlayer(),"block-break")) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e){ if(blocked(e.getPlayer(),"block-place")) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e){ if(blocked(e.getPlayer(),"item-drop")) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerPickupItemEvent e){ if(blocked(e.getPlayer(),"item-pickup")) e.setCancelled(true); }
    @EventHandler public void onInv(InventoryClickEvent e){ if(e.getWhoClicked() instanceof Player p && blocked(p,"inventory-click")) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.damage", false)) e.setCancelled(true);
    }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("protection.pvp", false)) {
            if (e.getDamager() instanceof Player p && inLobby(p)) e.setCancelled(true);
            if (e.getEntity() instanceof Player p && inLobby(p)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.hunger", false)) {
            e.setCancelled(true);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent e) {
        if (getConfig().getBoolean("protection.weather-clear", true) && e.toWeatherState() && e.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby.world","Lobby"))) e.setCancelled(true);
    }

    private boolean blocked(Player p, String key) {
        return getConfig().getBoolean("protection.enabled", true) && inLobby(p) && getConfig().getBoolean("protection." + key, true) && !canBypass(p);
    }

    private boolean canBypass(Player p) {
        return p.hasPermission("msurvivallobby.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    private boolean inLobby(Player p) {
        return p.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby.world", "Lobby"));
    }

    private void saveLocation(String path, Location loc) {
        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    private Location getLocation(String path) {
        World w = Bukkit.getWorld(getConfig().getString(path + ".world", path.equals("lobby") ? "Lobby" : "world"));
        if (w == null) return null;
        return new Location(w, getConfig().getDouble(path + ".x", 0.5), getConfig().getDouble(path + ".y", 100), getConfig().getDouble(path + ".z", 0.5), (float)getConfig().getDouble(path + ".yaw",0), (float)getConfig().getDouble(path + ".pitch",0));
    }

    private void teleport(Player p, Location loc, String msgKey, boolean sendMsg) {
        if (loc == null) { p.sendMessage(msg("world-not-loaded")); return; }
        p.teleport(loc);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        if (sendMsg && msgKey != null) p.sendMessage(msg(msgKey));
    }

    private void giveMenuItem(Player p) {
        if (p == null || !p.isOnline() || hasMenuItem(p)) return;
        int slot = getConfig().getInt("menu-item.slot", 4);
        ItemStack item = createMenuItem();
        if (slot >= 0 && slot <= 35) {
            ItemStack cur = p.getInventory().getItem(slot);
            if (cur == null || cur.getType() == Material.AIR || isMenuItem(cur)) {
                p.getInventory().setItem(slot, item);
                return;
            }
        }
        p.getInventory().addItem(item);
    }

    private ItemStack createMenuItem() {
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("menu-item.material", "COMPASS")));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("menu-item.name", "&6&lMSurvival Menu")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("menu-item.lore")) lore.add(color(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean hasMenuItem(Player p) {
        for (ItemStack item : p.getInventory().getContents()) if (isMenuItem(item)) return true;
        return false;
    }

    private boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(menuItemKey, PersistentDataType.STRING);
    }

    private Material parseMaterial(String v) {
        try { return Material.valueOf(v.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Material.COMPASS; }
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String t) {
        return t == null ? "" : ChatColor.translateAlternateColorCodes('&', t);
    }
}

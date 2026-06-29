package pl.msurvival.lobby;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalLobby extends JavaPlugin implements Listener {
    private NamespacedKey menuKey;
    private NamespacedKey actionKey;
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuKey = new NamespacedKey(this, "menu_item");
        actionKey = new NamespacedKey(this, "menu_action");
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        commands();
    }

    private void commands() {
        getCommand("menu").setExecutor((s,c,l,a)->{ if(s instanceof Player p) openMenu(p); return true; });
        getCommand("menuitem").setExecutor((s,c,l,a)->{ if(s instanceof Player p) { p.getInventory().addItem(menuItem()); p.sendMessage(color("&aDano kompas menu.")); } return true; });
        getCommand("lobby").setExecutor((s,c,l,a)->{ if(s instanceof Player p) toLobby(p, true); return true; });
        getCommand("survival").setExecutor((s,c,l,a)->{ if(s instanceof Player p) toSurvival(p, true); return true; });
        getCommand("setlobby").setExecutor((s,c,l,a)->{ if(!(s instanceof Player p)) return true; if(!admin(p)) return true; saveLoc("lobby", p.getLocation()); p.sendMessage(msg("lobby-set")); return true; });
        getCommand("setsurvival").setExecutor((s,c,l,a)->{ if(!(s instanceof Player p)) return true; if(!admin(p)) return true; saveLoc("survival", p.getLocation()); p.sendMessage(msg("survival-set")); return true; });
        getCommand("lobbyreload").setExecutor((s,c,l,a)->{ if(!s.hasPermission("msurvivallobby.admin")) { s.sendMessage(msg("no-permission")); return true; } reloadConfig(); loadData(); s.sendMessage(msg("reload")); return true; });
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("settings.teleport-to-lobby-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if(p.isOnline()) toLobby(p, false); }, getConfig().getLong("settings.join-delay-ticks", 1));
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (inLobby(p)) saveLobbyInventory(p);
        else saveSurvivalInventory(p);
        saveData();
    }

    private void toLobby(Player p, boolean message) {
        if (!inLobby(p)) saveSurvivalInventory(p);
        loadLobbyInventory(p);
        Location loc = loc("lobby");
        if (loc != null) p.teleport(loc);
        p.setFoodLevel(20);
        p.setSaturation(20);
        if (message) p.sendMessage(msg("lobby"));
    }

    private void toSurvival(Player p, boolean message) {
        if (inLobby(p)) saveLobbyInventory(p);
        loadSurvivalInventory(p);
        Location loc = null;
        if (getConfig().getBoolean("survival.use-bed-spawn", true) && p.getBedSpawnLocation() != null) loc = p.getBedSpawnLocation();
        if (loc == null) loc = loc("survival");
        if (loc != null) p.teleport(loc);
        if (message) p.sendMessage(msg("survival"));
    }

    private void saveSurvivalInventory(Player p) {
        try {
            String path = "survival." + p.getUniqueId();
            data.set(path + ".contents", serialize(p.getInventory().getContents()));
            data.set(path + ".armor", serialize(p.getInventory().getArmorContents()));
            data.set(path + ".offhand", serialize(new ItemStack[]{p.getInventory().getItemInOffHand()}));
            data.set(path + ".level", p.getLevel());
            data.set(path + ".exp", p.getExp());
            saveData();
        } catch (Exception ex) { getLogger().warning("Nie zapisano survival inv: " + p.getName()); }
    }

    private void loadSurvivalInventory(Player p) {
        String path = "survival." + p.getUniqueId();
        if (!data.contains(path + ".contents")) {
            p.getInventory().remove(Material.COMPASS);
            return;
        }
        try {
            p.getInventory().clear();
            p.getInventory().setContents(deserialize(data.getString(path + ".contents")));
            p.getInventory().setArmorContents(deserialize(data.getString(path + ".armor")));
            ItemStack[] off = deserialize(data.getString(path + ".offhand"));
            p.getInventory().setItemInOffHand(off.length > 0 ? off[0] : null);
            p.setLevel(data.getInt(path + ".level", 0));
            p.setExp((float)data.getDouble(path + ".exp", 0));
            p.updateInventory();
        } catch (Exception ex) { getLogger().warning("Nie wczytano survival inv: " + p.getName()); }
    }

    private void saveLobbyInventory(Player p) {
        try {
            String path = "lobbyinv." + p.getUniqueId();
            data.set(path + ".contents", serialize(p.getInventory().getContents()));
            saveData();
        } catch (Exception ex) { getLogger().warning("Nie zapisano lobby inv: " + p.getName()); }
    }

    private void loadLobbyInventory(Player p) {
        String path = "lobbyinv." + p.getUniqueId();
        try {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            if (data.contains(path + ".contents")) p.getInventory().setContents(deserialize(data.getString(path + ".contents")));
        } catch (Exception ignored) {}
        if (!hasMenu(p)) {
            int slot = getConfig().getInt("menu-item.slot", 4);
            if (slot >= 0 && slot <= 35) p.getInventory().setItem(slot, menuItem());
            else p.getInventory().addItem(menuItem());
        }
        p.updateInventory();
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("gui.title")));
        ItemStack filler = named(parseMat(getConfig().getString("gui.filler")), " ");
        for (int i=0;i<27;i++) inv.setItem(i, filler);
        inv.setItem(11, guiItem(Material.NETHER_STAR, "&e&lLobby", "lobby", List.of("&7Powrót do lobby")));
        inv.setItem(13, guiItem(Material.GRASS_BLOCK, "&a&lSurvival", "survival", List.of("&7Wejście na survival")));
        inv.setItem(15, guiItem(Material.LIGHTNING_ROD, "&6&lKlucze", "keys", List.of("&7Otwórz menu kluczy")));
        p.openInventory(inv);
    }

    @EventHandler
    public void invClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTitle().equals(color(getConfig().getString("gui.title")))) {
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String action = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action == null) return;
            p.closeInventory();
            if (action.equals("lobby")) toLobby(p, true);
            if (action.equals("survival")) toSurvival(p, true);
            if (action.equals("keys")) Bukkit.dispatchCommand(p, "keysmenu");
            return;
        }
        if (inLobby(p) && getConfig().getBoolean("protection.inventory-click", false) == false && !adminBypass(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenu(e.getItem())) return;
        e.setCancelled(true);
        openMenu(e.getPlayer());
    }

    @EventHandler public void breakBlock(BlockBreakEvent e){ if(inLobby(e.getPlayer()) && getConfig().getBoolean("protection.block-break", true) && !adminBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void placeBlock(BlockPlaceEvent e){ if(inLobby(e.getPlayer()) && getConfig().getBoolean("protection.block-place", true) && !adminBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e){ if(inLobby(e.getPlayer()) && !getConfig().getBoolean("protection.drop", false) && !adminBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void pickup(PlayerPickupItemEvent e){ if(inLobby(e.getPlayer()) && !getConfig().getBoolean("protection.pickup", false) && !adminBypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void food(FoodLevelChangeEvent e){ if(e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.hunger", false)){ e.setCancelled(true); p.setFoodLevel(20); } }
    @EventHandler public void damage(EntityDamageEvent e){ if(e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.damage", false)) e.setCancelled(true); }
    @EventHandler public void pvp(EntityDamageByEntityEvent e){ if(!getConfig().getBoolean("protection.pvp", false)){ if(e.getDamager() instanceof Player p && inLobby(p)) e.setCancelled(true); if(e.getEntity() instanceof Player p && inLobby(p)) e.setCancelled(true); } }

    private ItemStack guiItem(Material mat, String name, String action, List<String> lore) {
        ItemStack it = named(mat, name);
        ItemMeta meta = it.getItemMeta();
        List<String> l = new ArrayList<>();
        for(String s:lore) l.add(color(s));
        meta.setLore(l);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack menuItem() {
        ItemStack it = named(parseMat(getConfig().getString("menu-item.material")), getConfig().getString("menu-item.name"));
        ItemMeta meta = it.getItemMeta();
        List<String> lore = new ArrayList<>();
        for(String s:getConfig().getStringList("menu-item.lore")) lore.add(color(s));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, "true");
        it.setItemMeta(meta);
        return it;
    }

    private boolean hasMenu(Player p) { for(ItemStack it:p.getInventory().getContents()) if(isMenu(it)) return true; return false; }
    private boolean isMenu(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.STRING); }
    private boolean inLobby(Player p) { return p.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby.world")); }
    private boolean admin(Player p) { if(!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return false; } return true; }
    private boolean adminBypass(Player p) { return p.hasPermission("msurvivallobby.admin") && p.getGameMode() == GameMode.CREATIVE; }

    private void saveLoc(String key, Location l) {
        getConfig().set(key + ".world", l.getWorld().getName());
        getConfig().set(key + ".x", l.getX());
        getConfig().set(key + ".y", l.getY());
        getConfig().set(key + ".z", l.getZ());
        getConfig().set(key + ".yaw", l.getYaw());
        getConfig().set(key + ".pitch", l.getPitch());
        saveConfig();
    }

    private Location loc(String key) {
        World w = Bukkit.getWorld(getConfig().getString(key + ".world"));
        if(w == null) return null;
        return new Location(w, getConfig().getDouble(key + ".x"), getConfig().getDouble(key + ".y"), getConfig().getDouble(key + ".z"), (float)getConfig().getDouble(key + ".yaw"), (float)getConfig().getDouble(key + ".pitch"));
    }

    private String serialize(ItemStack[] items) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream data = new ObjectOutputStream(out);
        data.writeInt(items.length);
        for(ItemStack item:items) data.writeObject(item);
        data.close();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
    private ItemStack[] deserialize(String s) throws Exception {
        if(s == null || s.isBlank()) return new ItemStack[0];
        ObjectInputStream data = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(s)));
        int len = data.readInt();
        ItemStack[] items = new ItemStack[len];
        for(int i=0;i<len;i++) items[i] = (ItemStack)data.readObject();
        data.close();
        return items;
    }

    private ItemStack named(Material mat, String name) { ItemStack it = new ItemStack(mat); ItemMeta meta = it.getItemMeta(); meta.setDisplayName(color(name)); it.setItemMeta(meta); return it; }
    private Material parseMat(String s) { try { return Material.valueOf(s.toUpperCase(Locale.ROOT)); } catch(Exception e) { return Material.STONE; } }
    private String msg(String k) { return color(getConfig().getString("messages.prefix","") + getConfig().getString("messages." + k, "")); }
    private String color(String s) { return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s); }
    private void loadData(){ dataFile = new File(getDataFolder(), "inventories.yml"); if(!dataFile.exists()){ try{ getDataFolder().mkdirs(); dataFile.createNewFile(); }catch(IOException e){ e.printStackTrace(); } } data = YamlConfiguration.loadConfiguration(dataFile); }
    private void saveData(){ try{ data.save(dataFile); }catch(IOException e){ e.printStackTrace(); } }
}

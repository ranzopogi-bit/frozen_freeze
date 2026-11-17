package com.frozen.freeze;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FrozenFreeze extends JavaPlugin implements Listener {

    private NamespacedKey swordModel;
    private int seconds;
    private int leewayTicks;
    private boolean particles;

    // Server tick when each player last had the Frozen Sword in hand and then switched away from it.
    private final Map<UUID, Integer> lastSwordTick = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordModel = NamespacedKey.fromString(getConfig().getString("item-model", "minecraft:frozen_sword"));
        seconds = getConfig().getInt("freeze-seconds", 5);
        leewayTicks = getConfig().getInt("swap-leeway-ticks", 5);
        particles = getConfig().getBoolean("particles", true);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private boolean isFrozenSword(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasItemModel() && swordModel.equals(meta.getItemModel());
    }

    // Hotbar swap (number keys / scroll): remember if the slot we are leaving held the sword.
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (isFrozenSword(p.getInventory().getItem(e.getPreviousSlot()))) {
            lastSwordTick.put(p.getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    // F-key swap between hands: the item moving to the off hand was the one in the main hand.
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (isFrozenSword(e.getOffHandItem())) {
            lastSwordTick.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastSwordTick.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        boolean holding = isFrozenSword(p.getInventory().getItemInMainHand());
        boolean justSwapped = false;
        if (!holding) {
            Integer last = lastSwordTick.get(p.getUniqueId());
            justSwapped = last != null && Bukkit.getCurrentTick() - last <= leewayTicks;
        }
        if (!holding && !justSwapped) return;

        if (particles) {
            Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 1, 0, 0, 0, 0);
            target.getWorld().spawnParticle(Particle.CRIT, center, 20, 0.4, 0.5, 0.4, 0.3);
        }

        // Fire Aspect sets the target on fire right AFTER this event, and fire clears freezing.
        // So freeze now, then again a couple of ticks later after putting any fire out.
        freeze(target);
        getServer().getScheduler().runTaskLater(this, () -> freeze(target), 1L);
        getServer().getScheduler().runTaskLater(this, () -> freeze(target), 3L);
    }

    private void freeze(LivingEntity target) {
        if (!target.isValid() || target.isDead()) return;
        target.setFireTicks(0);
        // Freeze ticks drop by 2 per tick outside powder snow, so 40 per second keeps it frozen for about `seconds`.
        int ticks = target.getMaxFreezeTicks() + seconds * 40;
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), ticks));
    }
}

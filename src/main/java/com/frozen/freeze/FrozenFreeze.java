package com.frozen.freeze;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class FrozenFreeze extends JavaPlugin implements Listener {

    private NamespacedKey swordModel;
    private int seconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordModel = NamespacedKey.fromString(getConfig().getString("item-model", "minecraft:frozen_sword"));
        seconds = getConfig().getInt("freeze-seconds", 5);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasItemModel() || !swordModel.equals(meta.getItemModel())) return;

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

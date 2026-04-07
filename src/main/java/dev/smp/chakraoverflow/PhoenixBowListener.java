package dev.smp.chakraoverflow;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class PhoenixBowListener implements Listener {

    private final ChakraOverflow plugin;

    // Tracks players currently charging the bow: UUID -> charge start tick
    private final Map<UUID, Long> charging = new HashMap<>();
    // Tracks phoenix arrows in-flight
    private final Set<UUID> phoenixArrows = new HashSet<>();
    // Particle trail tasks
    private final Map<UUID, BukkitRunnable> trailTasks = new HashMap<>();
    // Charge aura tasks
    private final Map<UUID, BukkitRunnable> chargeAuraTasks = new HashMap<>();

    private static final String BOW_KEY = "phoenix_bow";
    private static final double MAX_CHARGE_SECONDS = 3.0;
    private static final double EXPLOSION_DAMAGE = 14.0;
    private static final double EXPLOSION_RADIUS = 6.0;

    public PhoenixBowListener(ChakraOverflow plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────
    //  Item Factory
    // ─────────────────────────────────────────────

    public static ItemStack createPhoenixBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        meta.displayName(Component.text("§6§l⚡ Phoenix Bow §r§7[Chakraoverflow]"));
        meta.lore(List.of(
            Component.text("§eHold right-click to charge the divine flames."),
            Component.text("§cRelease to unleash a Phoenix Arrow!"),
            Component.text("§8§oOn hit: ignites a massive phoenix eruption.")
        ));
        meta.addEnchant(Enchantment.POWER, 5, true);
        meta.addEnchant(Enchantment.INFINITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(
            new org.bukkit.NamespacedKey(ChakraOverflow.getInstance(), BOW_KEY),
            org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1
        );
        bow.setItemMeta(meta);
        return bow;
    }

    private boolean isPhoenixBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(
            new org.bukkit.NamespacedKey(ChakraOverflow.getInstance(), BOW_KEY),
            org.bukkit.persistence.PersistentDataType.BYTE
        );
    }

    // ─────────────────────────────────────────────
    //  Charging: aura of fire/soul particles builds up while held
    // ─────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isPhoenixBow(held)) return;

        var action = e.getAction();
        boolean rightClick = action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                          || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;

        if (rightClick && !charging.containsKey(player.getUniqueId())) {
            startCharge(player);
        }
    }

    private void startCharge(Player player) {
        charging.put(player.getUniqueId(), System.currentTimeMillis());

        // Give the player an arrow so infinity works
        if (!player.getInventory().contains(Material.ARROW)) {
            player.getInventory().addItem(new ItemStack(Material.ARROW, 1));
        }

        // Build-up sound loop
        BukkitRunnable auraTask = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!charging.containsKey(player.getUniqueId())) {
                    cancel();
                    return;
                }
                if (!player.isOnline()) {
                    cancel();
                    charging.remove(player.getUniqueId());
                    return;
                }

                double progress = Math.min(tick / (20.0 * MAX_CHARGE_SECONDS), 1.0);
                int particleCount = (int) (5 + progress * 20);
                double radius = 0.6 + progress * 1.4;

                // Orbital fire particles around the player
                for (int i = 0; i < particleCount; i++) {
                    double angle = (tick * 8.0 + i * (360.0 / particleCount)) * (Math.PI / 180.0);
                    double height = 0.5 + Math.sin(tick * 0.2 + i) * 0.8;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location loc = player.getLocation().clone().add(x, height, z);
                    player.getWorld().spawnParticle(Particle.FLAME, loc, 1, 0, 0, 0, 0);
                }

                // Soul fire particles at higher charge
                if (progress > 0.5) {
                    for (int i = 0; i < (int) (progress * 8); i++) {
                        double angle = Math.random() * Math.PI * 2;
                        double r2 = Math.random() * radius;
                        Location loc = player.getLocation().clone()
                                .add(Math.cos(angle) * r2, Math.random() * 2.5, Math.sin(angle) * r2);
                        player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0, 0, 0, 0);
                    }
                }

                // Lava particles at full charge
                if (progress >= 1.0) {
                    Location loc = player.getLocation().clone().add(0, 1.2, 0);
                    player.getWorld().spawnParticle(Particle.LAVA, loc, 3, 0.4, 0.3, 0.4, 0);
                    // Screen warning at full charge
                    if (tick % 10 == 0) {
                        player.sendActionBar(Component.text("§c§l🔥 DIVINE FLAMES FULLY CHARGED! 🔥"));
                    }
                } else {
                    int bars = (int) (progress * 10);
                    String bar = "§c" + "█".repeat(bars) + "§8" + "█".repeat(10 - bars);
                    player.sendActionBar(Component.text("§6Charging: " + bar));
                }

                // Charge sound — pitch rises with charge
                if (tick % 5 == 0) {
                    float pitch = (float) (0.5 + progress * 1.5);
                    player.playSound(player.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.4f, pitch);
                    if (progress > 0.7) {
                        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_BURN, 0.3f, pitch);
                    }
                }

                tick++;
            }
        };
        auraTask.runTaskTimer(plugin, 0L, 1L);
        chargeAuraTasks.put(player.getUniqueId(), auraTask);
    }

    // ─────────────────────────────────────────────
    //  Arrow fired — wrap it as a phoenix arrow
    // ─────────────────────────────────────────────

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        if (!phoenixArrows.contains(arrow.getUniqueId())) return;

        // Stop trail task
        BukkitRunnable trail = trailTasks.remove(arrow.getUniqueId());
        if (trail != null) trail.cancel();

        Entity hitEntity = e.getHitEntity();
        Location impactLoc = arrow.getLocation();

        if (hitEntity instanceof Player target) {
            // Get shooter for credit
            Entity shooter = (Entity) arrow.getShooter();

            // Detonate on player hit
            phoenixEruption(impactLoc, target, shooter instanceof Player ? (Player) shooter : null);
        } else {
            // Hit ground/block — small poof
            spawnGroundFlameImpact(impactLoc);
        }

        phoenixArrows.remove(arrow.getUniqueId());
        arrow.remove();
    }

    // We detect bow release via damage event tag and ProjectileHit above,
    // but also need to track when an arrow is actually shot from the phoenix bow.
    // We use EntityDamageByEntityEvent to do cleanup AND we register arrows on shoot.

    @EventHandler
    public void onProjectileShoot(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player player)) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isPhoenixBow(held)) return;

        // Cancel the normal arrow — we manage it ourselves
        Long startTime = charging.remove(player.getUniqueId());
        BukkitRunnable aura = chargeAuraTasks.remove(player.getUniqueId());
        if (aura != null) aura.cancel();

        double chargeSeconds = startTime != null
                ? (System.currentTimeMillis() - startTime) / 1000.0
                : MAX_CHARGE_SECONDS;
        double chargeRatio = Math.min(chargeSeconds / MAX_CHARGE_SECONDS, 1.0);

        // Scale velocity by charge
        Vector vel = arrow.getVelocity().multiply(1.0 + chargeRatio * 1.5);
        arrow.setVelocity(vel);
        arrow.setFireTicks(Integer.MAX_VALUE);
        arrow.setGlowing(true);
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);

        phoenixArrows.add(arrow.getUniqueId());

        // Fire launch effect
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.7f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);

        // Launch burst
        for (int i = 0; i < 30; i++) {
            double angle = i * (Math.PI * 2 / 30);
            double x = Math.cos(angle) * 0.8;
            double z = Math.sin(angle) * 0.8;
            player.getWorld().spawnParticle(Particle.FLAME, arrow.getLocation().clone().add(x, 0.5, z),
                    1, 0, 0, 0, 0);
        }

        // Start particle trail
        final double finalChargeRatio = chargeRatio;
        BukkitRunnable trailTask = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isDead()) {
                    cancel();
                    return;
                }

                Location loc = arrow.getLocation();
                World world = loc.getWorld();

                // Core flame trail
                world.spawnParticle(Particle.FLAME, loc, 4, 0.05, 0.05, 0.05, 0.02);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 2, 0.05, 0.05, 0.05, 0.01);

                // Spinning ring of fire around arrow at high charge
                if (finalChargeRatio > 0.5) {
                    for (int i = 0; i < 6; i++) {
                        double angle = (tick * 25.0 + i * 60.0) * (Math.PI / 180.0);
                        double r = 0.3;
                        Location ring = loc.clone().add(
                                Math.cos(angle) * r,
                                Math.sin(angle) * r,
                                0
                        );
                        world.spawnParticle(Particle.FLAME, ring, 1, 0, 0, 0, 0);
                    }
                }

                // Smoke tail
                world.spawnParticle(Particle.LARGE_SMOKE, loc, 1, 0.1, 0.1, 0.1, 0.01);

                tick++;
            }
        };
        trailTask.runTaskTimer(plugin, 0L, 1L);
        trailTasks.put(arrow.getUniqueId(), trailTask);

        player.sendActionBar(Component.text("§6§l🔥 PHOENIX ARROW FIRED! 🔥"));
    }

    // ─────────────────────────────────────────────
    //  Release detection via EntityDamageByEntityEvent cleanup
    // ─────────────────────────────────────────────

    @EventHandler
    public void onArrowHitPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Arrow arrow)) return;
        if (!phoenixArrows.contains(arrow.getUniqueId())) return;
        // Cancel default damage — eruption handles it
        e.setCancelled(true);
    }

    // ─────────────────────────────────────────────
    //  Phoenix Eruption — the main spectacle
    // ─────────────────────────────────────────────

    private void phoenixEruption(Location center, Player target, Player shooter) {
        World world = center.getWorld();

        // ─── Phase 1 (tick 0): Shockwave flash + initial burst ───
        // Blinding flash to nearby players
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distance(center) <= 20) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 15, 0, false, false));
                nearby.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.6f);
                nearby.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
                nearby.playSound(center, Sound.ENTITY_BLAZE_DEATH, 1.0f, 0.4f);
            }
        }

        // Immediate burst: 360° expanding ring at ground
        for (int i = 0; i < 80; i++) {
            double angle = i * (Math.PI * 2 / 80);
            for (double r = 0; r <= EXPLOSION_RADIUS; r += 0.3) {
                Location ring = center.clone().add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r);
                world.spawnParticle(Particle.FLAME, ring, 1, 0, 0, 0, 0);
            }
        }

        // Central lava geyser
        for (int i = 0; i < 30; i++) {
            double vx = (Math.random() - 0.5) * 0.3;
            double vy = Math.random() * 0.5 + 0.2;
            double vz = (Math.random() - 0.5) * 0.3;
            world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.5, 0), 1, vx, vy, vz, 1.0);
        }

        // ─── Phase 2 (ticks 1–30): Expanding rings + fire pillars ───
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= 30) {
                    cancel();
                    // Phase 3
                    phoenixAscent(center, target, shooter);
                    return;
                }

                double progress = tick / 30.0;
                double currentRadius = progress * EXPLOSION_RADIUS;

                // Expanding ring of flames
                for (int i = 0; i < 48; i++) {
                    double angle = i * (Math.PI * 2 / 48);
                    Location loc = center.clone().add(
                            Math.cos(angle) * currentRadius,
                            0.15,
                            Math.sin(angle) * currentRadius
                    );
                    world.spawnParticle(Particle.FLAME, loc, 2, 0, 0.1, 0, 0.05);
                    if (tick % 3 == 0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0, 0, 0, 0);
                    }
                }

                // 8 fire pillars shooting up
                for (int p = 0; p < 8; p++) {
                    double angle = p * (Math.PI / 4) + tick * 0.05;
                    double r = currentRadius * 0.6;
                    for (double h = 0; h < 4 + progress * 4; h += 0.3) {
                        Location pillar = center.clone().add(
                                Math.cos(angle) * r,
                                h,
                                Math.sin(angle) * r
                        );
                        if (Math.random() < 0.4) {
                            world.spawnParticle(Particle.FLAME, pillar, 1, 0.05, 0, 0.05, 0.02);
                        }
                    }
                }

                // Ground scorch marks (ember embers)
                if (tick % 2 == 0) {
                    for (int i = 0; i < 10; i++) {
                        double angle = Math.random() * Math.PI * 2;
                        double r = Math.random() * currentRadius;
                        world.spawnParticle(Particle.LARGE_SMOKE,
                                center.clone().add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r),
                                1, 0.1, 0.05, 0.1, 0.01);
                    }
                }

                // Damage nearby players during expansion
                if (tick == 15) {
                    applyEruptionDamage(center, target, shooter, currentRadius + 1.5);
                }

                // Sound effects each wave
                if (tick % 5 == 0) {
                    world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 1.5f, 0.8f);
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ─────────────────────────────────────────────
    //  Phase 3: Phoenix Ascent — fire tornado column
    // ─────────────────────────────────────────────

    private void phoenixAscent(Location center, Player target, Player shooter) {
        World world = center.getWorld();

        world.playSound(center, Sound.ENTITY_PHANTOM_FLAP, 1.5f, 0.4f);
        world.playSound(center, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.5f);

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= 60) {
                    cancel();
                    // Final: apply lingering fire to target
                    if (target.isOnline()) {
                        target.setFireTicks(200); // 10 seconds of fire
                        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 1, false, false));
                        target.sendMessage(Component.text("§c§l🔥 Phoenix's Divine Flames burn your soul! 🔥"));
                    }
                    return;
                }

                double angle = tick * 18.0 * (Math.PI / 180.0); // spiral
                double shrink = 1.0 - tick / 80.0;

                // Spiraling flame tornado
                for (int layer = 0; layer < 3; layer++) {
                    double layerAngle = angle + layer * (Math.PI * 2 / 3);
                    double r = (2.0 + Math.sin(tick * 0.15) * 0.5) * shrink;
                    for (double h = 0; h < 12; h += 0.6) {
                        double spiralAngle = layerAngle + h * 0.3;
                        double hr = r * (1.0 - h / 14.0);
                        Location loc = center.clone().add(
                                Math.cos(spiralAngle) * hr,
                                h + tick * 0.08,
                                Math.sin(spiralAngle) * hr
                        );
                        if (loc.getY() > center.getY() + 20) break;

                        if (Math.random() < 0.6) {
                            world.spawnParticle(Particle.FLAME, loc, 1, 0, 0, 0, 0);
                        }
                        if (Math.random() < 0.3) {
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0, 0, 0, 0);
                        }
                    }
                }

                // Phoenix wing-like bursts at peak
                if (tick == 30) {
                    phoenixWings(center.clone().add(0, 8, 0), world);
                    world.playSound(center, Sound.ENTITY_PHANTOM_DEATH, 1.5f, 0.5f);
                    world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 0.7f);
                }

                // Rising embers
                if (tick % 2 == 0) {
                    for (int i = 0; i < 5; i++) {
                        double ex = (Math.random() - 0.5) * 3;
                        double ez = (Math.random() - 0.5) * 3;
                        world.spawnParticle(Particle.ASH,
                                center.clone().add(ex, Math.random() * 6, ez),
                                1, 0, 0.1, 0, 0.01);
                    }
                }

                tick++;
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    // ─────────────────────────────────────────────
    //  Phoenix Wing silhouette at the peak
    // ─────────────────────────────────────────────

    private void phoenixWings(Location origin, World world) {
        // Left and right wing spreads
        for (int side : new int[]{-1, 1}) {
            for (int i = 1; i <= 20; i++) {
                double wx = side * i * 0.4;
                double wy = -i * 0.15;
                Location tip = origin.clone().add(wx, wy, 0);
                world.spawnParticle(Particle.FLAME, tip, 3, 0.1, 0.15, 0.05, 0.03);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, tip, 2, 0.1, 0.1, 0.05, 0.02);

                // Feather-like sub-particles
                for (int f = 0; f < 3; f++) {
                    double fx = tip.getX() + side * f * 0.3;
                    double fy = tip.getY() - f * 0.4;
                    world.spawnParticle(Particle.FLAME,
                            new Location(world, fx, fy, tip.getZ()),
                            1, 0.05, 0.1, 0.05, 0.02);
                }
            }
        }

        // Central body of phoenix
        for (double h = 0; h <= 3; h += 0.3) {
            world.spawnParticle(Particle.FLAME, origin.clone().add(0, h, 0), 3, 0.15, 0, 0.15, 0.03);
            world.spawnParticle(Particle.LAVA, origin.clone().add(0, h, 0), 1, 0.1, 0, 0.1, 0);
        }

        world.playSound(origin, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.5f, 0.6f);
    }

    // ─────────────────────────────────────────────
    //  Damage application
    // ─────────────────────────────────────────────

    private void applyEruptionDamage(Location center, Player mainTarget, Player shooter, double radius) {
        World world = center.getWorld();

        // Damage main target
        if (mainTarget.isOnline()) {
            mainTarget.damage(EXPLOSION_DAMAGE, shooter);
            mainTarget.setFireTicks(100);
            mainTarget.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, false, false));
            // Knockback upward
            Vector kb = mainTarget.getLocation().toVector().subtract(center.toVector())
                    .normalize().multiply(1.5).setY(0.8);
            mainTarget.setVelocity(kb);
        }

        // Splash damage to others in radius
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Player nearby)) continue;
            if (nearby.equals(mainTarget) || nearby.equals(shooter)) continue;

            double dist = entity.getLocation().distance(center);
            if (dist > radius) continue;

            double falloff = 1.0 - (dist / radius);
            nearby.damage(EXPLOSION_DAMAGE * falloff * 0.6, shooter);
            nearby.setFireTicks((int) (60 * falloff));

            Vector kb = nearby.getLocation().toVector().subtract(center.toVector())
                    .normalize().multiply(falloff * 1.2).setY(0.5);
            nearby.setVelocity(kb);
        }
    }

    // ─────────────────────────────────────────────
    //  Ground impact (missed shot)
    // ─────────────────────────────────────────────

    private void spawnGroundFlameImpact(Location loc) {
        World world = loc.getWorld();
        world.playSound(loc, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.8f);
        for (int i = 0; i < 40; i++) {
            double angle = Math.random() * Math.PI * 2;
            double r = Math.random() * 1.5;
            world.spawnParticle(Particle.FLAME,
                    loc.clone().add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r),
                    1, 0, 0.15, 0, 0.05);
        }
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 10, 0.5, 0.3, 0.5, 0.05);
    }
}

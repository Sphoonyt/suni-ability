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

    private void phoenixEruption(Location impactLoc, Player target, Player shooter) {
        World world = impactLoc.getWorld();

        // ── Impact flash & sounds ──
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distance(impactLoc) <= 30) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 12, 0, false, false));
                nearby.playSound(impactLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.5f);
                nearby.playSound(impactLoc, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.4f);
            }
        }

        // Small ground burst on arrow contact
        for (int i = 0; i < 24; i++) {
            double a = i * (Math.PI * 2 / 24);
            world.spawnParticle(Particle.FLAME, impactLoc.clone().add(Math.cos(a) * 1.5, 0.1, Math.sin(a) * 1.5), 2, 0, 0.05, 0, 0.04);
        }

        // ── Freeze the target: snapshot their position right now ──
        final Location frozenLoc = target.isOnline()
                ? target.getLocation().clone()
                : impactLoc.clone();

        // Apply Slowness 255 + freeze flag so the target literally cannot walk
        if (target.isOnline()) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 999, 255, false, false));
        }

        final double START_RADIUS = 15.0;
        final double HELIX_HEIGHT = 18.0;
        final int    CLOSE_TICKS  = 80;
        final int    ARMS         = 5;

        world.playSound(impactLoc, Sound.ENTITY_PHANTOM_FLAP, 1.8f, 0.3f);
        world.playSound(impactLoc, Sound.ENTITY_BLAZE_AMBIENT, 1.5f, 0.3f);

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {

                // ── Keep target locked in place every tick ──
                if (target.isOnline()) {
                    target.setVelocity(new Vector(0, 0, 0));
                    // Only reteleport if they've drifted more than 0.5 blocks (avoids jitter)
                    if (target.getLocation().distanceSquared(frozenLoc) > 0.25) {
                        target.teleport(frozenLoc);
                    }
                }

                if (tick >= CLOSE_TICKS) {
                    cancel();
                    // ── 1-second suspense pause before explosion ──
                    new BukkitRunnable() {
                        int pauseTick = 0;
                        @Override
                        public void run() {
                            // Keep frozen during the pause too
                            if (target.isOnline()) {
                                target.setVelocity(new Vector(0, 0, 0));
                                if (target.getLocation().distanceSquared(frozenLoc) > 0.25) {
                                    target.teleport(frozenLoc);
                                }
                            }
                            // Ominous buildup particles during pause
                            for (int i = 0; i < 16; i++) {
                                double a = pauseTick * 25.0 * (Math.PI / 180.0) + i * (Math.PI * 2.0 / 16);
                                double r = 1.5 - pauseTick * 0.06;
                                Location swirl = frozenLoc.clone().add(Math.cos(a) * r, 0.8 + pauseTick * 0.1, Math.sin(a) * r);
                                world.spawnParticle(Particle.SOUL_FIRE_FLAME, swirl, 1, 0, 0, 0, 0);
                            }
                            if (pauseTick % 4 == 0) {
                                world.playSound(frozenLoc, Sound.ENTITY_BLAZE_BURN, 1.0f, 0.3f + pauseTick * 0.05f);
                            }
                            pauseTick++;
                            if (pauseTick >= 20) {
                                cancel();
                                // ── Release freeze, fire explosion ──
                                if (target.isOnline()) {
                                    target.removePotionEffect(PotionEffectType.SLOWNESS);
                                }
                                phoenixFinalExplosion(frozenLoc, target, shooter);
                            }
                        }
                    }.runTaskTimer(plugin, 0L, 1L);
                    return;
                }

                double easeT     = tick / (double) CLOSE_TICKS;
                double radius    = START_RADIUS * (1.0 - easeT);
                double spinRate  = 12.0 + easeT * 30.0;
                double baseAngle = tick * spinRate * (Math.PI / 180.0);

                // Use frozen location as the fixed orbit center
                Location origin = frozenLoc.clone().add(0, 0.5, 0);

                // ── 5-arm helix ──
                for (int arm = 0; arm < ARMS; arm++) {
                    double armOffset = arm * (Math.PI * 2.0 / ARMS);
                    for (double h = 0; h <= HELIX_HEIGHT; h += 0.35) {
                        double hFrac = h / HELIX_HEIGHT;
                        double r     = radius * (1.0 - hFrac * 0.35);
                        double angle = baseAngle + armOffset + h * 0.55;
                        Location loc = origin.clone().add(Math.cos(angle) * r, h, Math.sin(angle) * r);

                        world.spawnParticle(Particle.FLAME, loc, 1, 0, 0, 0, 0);
                        if (arm % 2 == 0 && Math.random() < 0.45)
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0, 0, 0, 0);
                        if (radius < 5.0 && Math.random() < 0.3)
                            world.spawnParticle(Particle.LAVA, loc, 1, 0.05, 0.05, 0.05, 0);
                        if (Math.random() < 0.04)
                            world.spawnParticle(Particle.LARGE_SMOKE, loc, 1, 0.1, 0.1, 0.1, 0.02);
                    }
                }

                // ── Ground ring ──
                for (int i = 0; i < 32; i++) {
                    double a    = baseAngle + i * (Math.PI * 2.0 / 32);
                    Location gL = origin.clone().add(Math.cos(a) * radius, -0.4, Math.sin(a) * radius);
                    world.spawnParticle(Particle.FLAME, gL, 1, 0, 0, 0, 0);
                    if (i % 4 == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, gL, 1, 0, 0, 0, 0);
                }

                // ── Top crown ──
                for (int i = 0; i < 16; i++) {
                    double a  = baseAngle * 1.5 + i * (Math.PI * 2.0 / 16);
                    double cr = radius * 0.3;
                    world.spawnParticle(Particle.FLAME, origin.clone().add(Math.cos(a) * cr, HELIX_HEIGHT + 1.0, Math.sin(a) * cr),
                            2, 0.1, 0.15, 0.1, 0.03);
                }

                // ── Rising ash ──
                if (tick % 3 == 0) {
                    for (int i = 0; i < 6; i++) {
                        world.spawnParticle(Particle.ASH, origin.clone().add(
                                (Math.random() - 0.5) * radius * 2, Math.random() * 4,
                                (Math.random() - 0.5) * radius * 2), 1, 0, 0.05, 0, 0.01);
                    }
                }

                // ── Rising sound ──
                if (tick % 6 == 0) {
                    float pitch = 0.4f + (float) easeT * 1.4f;
                    world.playSound(origin, Sound.BLOCK_FIRE_AMBIENT, 1.8f, pitch);
                    if (easeT > 0.5) world.playSound(origin, Sound.ENTITY_BLAZE_BURN, 1.2f, pitch);
                }

                tick++;
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    // ─────────────────────────────────────────────
    //  Phase 2: Outward explosion after tornado collapses
    // ─────────────────────────────────────────────

    private void phoenixFinalExplosion(Location center, Player target, Player shooter) {
        World world = center.getWorld();

        // Damage & debuffs
        applyEruptionDamage(center, target, shooter, EXPLOSION_RADIUS);
        if (target.isOnline()) {
            target.setFireTicks(220);
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 140, 1, false, false));
            target.sendMessage(Component.text("§c§l🔥 Phoenix's Divine Flames consume you! 🔥"));
        }

        // Blind + sounds for everyone nearby
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distance(center) <= 25) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 1, false, false));
                nearby.playSound(center, Sound.ENTITY_GENERIC_EXPLODE,              2.0f, 0.4f);
                nearby.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER,       2.0f, 0.5f);
                nearby.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,  2.0f, 0.6f);
                nearby.playSound(center, Sound.ENTITY_BLAZE_DEATH,                  1.5f, 0.3f);
            }
        }

        // ── Expanding shockwave rings: one new ring spawned every 4 ticks, expanding outward ──
        new BukkitRunnable() {
            int tick = 0;
            // currentRadius grows from 0 → 20 over ~40 ticks (2 seconds)
            double expandRadius = 0;

            @Override
            public void run() {
                if (tick > 50) { cancel(); return; }

                expandRadius += 0.5; // expands 0.5 blocks/tick

                // ── Outward ring at current radius ──
                int points = (int) Math.max(24, expandRadius * 6);
                for (int i = 0; i < points; i++) {
                    double a   = i * (Math.PI * 2.0 / points);
                    Location rim = center.clone().add(Math.cos(a) * expandRadius, 0.15, Math.sin(a) * expandRadius);
                    world.spawnParticle(Particle.FLAME,          rim, 3, 0, 0.25, 0, 0.07);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, rim, 2, 0, 0.2,  0, 0.05);
                    if (i % 6 == 0) world.spawnParticle(Particle.LAVA, rim, 1, 0, 0.4, 0, 0);
                }

                // ── Fire pillars rising from the ring ──
                int pillars = (int) Math.max(6, expandRadius * 0.8);
                for (int p = 0; p < pillars; p++) {
                    double a = p * (Math.PI * 2.0 / pillars) + tick * 0.05;
                    double pillarHeight = 4.0 + expandRadius * 0.5;
                    for (double h = 0; h < pillarHeight; h += 0.5) {
                        double taper = 1.0 - (h / (pillarHeight * 1.4));
                        Location pillar = center.clone().add(
                                Math.cos(a) * expandRadius * taper,
                                h,
                                Math.sin(a) * expandRadius * taper
                        );
                        if (Math.random() < 0.55)
                            world.spawnParticle(Particle.FLAME, pillar, 1, 0.05, 0, 0.05, 0.02);
                        if (h < 3 && Math.random() < 0.2)
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, pillar, 1, 0.05, 0.05, 0.05, 0.01);
                    }
                }

                // ── Sound wave travels outward with the ring ──
                if (tick % 4 == 0) {
                    float pitch = 1.8f - (float)(expandRadius / 25.0);
                    world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.5f, Math.max(0.3f, pitch));
                    world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 1.5f, 0.6f);
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // ── Central upward geyser burst ──
        new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (tick >= 30) { cancel(); return; }
                double spread = Math.max(0.1, 0.9 - tick * 0.025);
                for (int i = 0; i < 14; i++) {
                    double a = i * (Math.PI * 2.0 / 14) + tick * 0.3;
                    Location g = center.clone().add(Math.cos(a) * spread, tick * 0.55, Math.sin(a) * spread);
                    world.spawnParticle(Particle.FLAME,           g, 2, 0.08, 0.04, 0.08, 0.04);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, g, 1, 0.05, 0.05, 0.05, 0.02);
                }
                if (tick % 3 == 0)
                    world.spawnParticle(Particle.LAVA, center.clone().add(0, tick * 0.35, 0), 4, 0.25, 0.1, 0.25, 0);
                tick++;
            }
        }.runTaskTimer(plugin, 1L, 1L);

        // Phoenix wings at geyser peak
        new BukkitRunnable() {
            @Override
            public void run() {
                phoenixWings(center.clone().add(0, 12, 0), world);
            }
        }.runTaskLater(plugin, 30L);
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

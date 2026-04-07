package dev.smp.chakraoverflow;

import org.bukkit.plugin.java.JavaPlugin;

public class ChakraOverflow extends JavaPlugin {

    private static ChakraOverflow instance;

    @Override
    public void onEnable() {
        instance = this;

        PhoenixBowListener listener = new PhoenixBowListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        getCommand("chakra").setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player player) {
                player.getInventory().addItem(PhoenixBowListener.createPhoenixBow());
                player.sendMessage(net.kyori.adventure.text.Component.text(
                    "§6§l🔥 Phoenix Bow §r§ehas been added to your inventory!"
                ));
            }
            return true;
        });

        getLogger().info("ChakraOverflow - Phoenix's Divine Flames loaded!");
    }

    @Override
    public void onDisable() {
        getLogger().info("ChakraOverflow disabled.");
    }

    public static ChakraOverflow getInstance() {
        return instance;
    }
}

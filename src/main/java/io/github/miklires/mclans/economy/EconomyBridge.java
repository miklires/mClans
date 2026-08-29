package io.github.miklires.mclans.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

public final class EconomyBridge {
    private Object provider;
    private Method withdraw;
    private Method deposit;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        try {
            Class economyType = Class.forName("net.milkbowl.vault.economy.Economy");
            var registration = Bukkit.getServicesManager().getRegistration(economyType);
            if (registration == null) return false;
            provider = registration.getProvider();
            withdraw = economyType.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = economyType.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            return true;
        } catch (ReflectiveOperationException exception) {
            provider = null;
            return false;
        }
    }

    public boolean available() { return provider != null; }
    public boolean withdraw(OfflinePlayer player, long amount) { return invoke(withdraw, player, amount); }
    public boolean deposit(OfflinePlayer player, long amount) { return invoke(deposit, player, amount); }

    private boolean invoke(Method method, OfflinePlayer player, long amount) {
        if (provider == null || method == null || amount <= 0 || amount > 1_000_000_000L) return false;
        try {
            Object response = method.invoke(provider, player, (double) amount);
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }
}

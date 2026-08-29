package io.github.miklires.mclans;
import org.bukkit.plugin.java.JavaPlugin;
public final class Text { private Text(){} public static String tr(JavaPlugin plugin,String english,String russian){return "ru_RU".equalsIgnoreCase(plugin.getConfig().getString("language","en_US"))?russian:english;} }

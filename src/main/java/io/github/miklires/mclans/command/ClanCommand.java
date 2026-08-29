package io.github.miklires.mclans.command;

import io.github.miklires.mclans.MClansPlugin;
import io.github.miklires.mclans.api.ClanRole;
import io.github.miklires.mclans.api.ClanView;
import io.github.miklires.mclans.clan.ClanService;
import io.github.miklires.mclans.gui.ClanGui;
import io.github.miklires.mclans.storage.ClanRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ClanCommand implements BasicCommand {
    private final MClansPlugin plugin;
    private final ClanService service;
    private final ClanGui gui;

    public ClanCommand(MClansPlugin plugin, ClanService service, ClanGui gui) {
        this.plugin = plugin; this.service = service; this.gui = gui;
    }

    @Override public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mclans.reload")) { deny(sender); return; }
            try { plugin.reloadRuntime(); message(sender, "&aConfiguration reloaded.", "&aКонфигурация перезагружена."); }
            catch (RuntimeException exception) { message(sender, "&cReload failed: ", "&cОшибка перезагрузки: ", exception.getMessage()); }
            return;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only"); return; }
        if (!player.hasPermission("mclans.use")) { deny(player); return; }
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) { info(player, args); return; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length != 3) { usage(player); return; }
                complete(player, service.create(player, args[1], args[2]).thenApply(ignored -> true), "Clan created.", "Клан создан.");
            }
            case "invite" -> {
                Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) { message(player, "&cPlayer not found.", "&cИгрок не найден."); return; }
                complete(player, service.invite(player, target), "Invitation sent.", "Приглашение отправлено.");
            }
            case "accept", "join" -> {
                if (args.length != 2) { usage(player); return; }
                complete(player, service.accept(player, args[1]), "You joined the clan.", "Вы вступили в клан.");
            }
            case "leave" -> complete(player, service.leave(player), "You left the clan.", "Вы покинули клан.");
            case "kick" -> {
                if (args.length != 2) { usage(player); return; }
                complete(player, service.kick(player, args[1]), "Player removed.", "Игрок исключён.");
            }
            case "promote", "demote" -> {
                if (args.length != 2) { usage(player); return; }
                ClanRole role = args[0].equalsIgnoreCase("promote") ? ClanRole.OFFICER : ClanRole.MEMBER;
                complete(player, service.setRole(player, args[1], role), "Role changed.", "Роль изменена.");
            }
            case "transfer" -> {
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                    message(player, "&cConfirm with /clan transfer <player> confirm", "&cПодтвердите: /clan transfer <игрок> confirm"); return;
                }
                complete(player, service.transfer(player, args[1]), "Ownership transferred.", "Владелец изменён.");
            }
            case "ff" -> {
                if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) { usage(player); return; }
                complete(player, service.setFriendlyFire(player, args[1].equalsIgnoreCase("on")), "Friendly fire changed.", "Огонь по своим изменён.");
            }
            case "bank" -> bank(player, args);
            case "sethome" -> complete(player, service.setHome(player), "Clan home set.", "Дом клана установлен.");
            case "home" -> complete(player, service.teleportHome(player), "Teleporting to clan home.", "Телепортация домой клана.");
            case "ally", "enemy" -> relation(player, args, args[0].equalsIgnoreCase("ally"));
            case "neutral" -> {
                if (args.length != 2) { usage(player); return; }
                complete(player, service.neutral(player, args[1]), "Relation removed.", "Отношения сброшены.");
            }
            case "top", "list" -> top(player);
            case "disband" -> {
                if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
                    message(player, "&cConfirm with /clan disband confirm", "&cПодтвердите: /clan disband confirm"); return;
                }
                complete(player, service.disband(player), "Clan disbanded.", "Клан распущен.");
            }
            default -> usage(player);
        }
    }

    private void bank(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("balance")) {
            ClanView clan = service.cached(player.getUniqueId()).orElse(null);
            if (clan == null) message(player, "&cYou are not in a clan.", "&cВы не состоите в клане.");
            else message(player, "&eClan balance: &f", "&eБаланс клана: &f", String.valueOf(clan.bank()));
            return;
        }
        if (args.length != 3) { usage(player); return; }
        long amount;
        try { amount = Long.parseLong(args[2]); } catch (NumberFormatException exception) { message(player, "&cEnter a whole positive amount.", "&cВведите целое положительное число."); return; }
        if (amount <= 0 || amount > 1_000_000_000L) { message(player, "&cAmount is outside the allowed range.", "&cСумма вне допустимого диапазона."); return; }
        if (!service.economyAvailable()) { message(player, "&cVault economy is not available.", "&cЭкономика Vault недоступна."); return; }
        if (args[1].equalsIgnoreCase("deposit")) complete(player, service.deposit(player, amount), "Money deposited.", "Деньги внесены.");
        else if (args[1].equalsIgnoreCase("withdraw")) complete(player, service.withdraw(player, amount), "Money withdrawn.", "Деньги сняты.");
        else usage(player);
    }

    private void relation(Player player, String[] args, boolean alliance) {
        if (args.length != 2) { usage(player); return; }
        service.relation(player, args[1], alliance).whenComplete((result, error) -> plugin.scheduler().player(player, () -> {
            if (error != null) { failure(player, error); return; }
            switch (result) {
                case REQUESTED -> message(player, "&aAlliance request sent.", "&aЗапрос союза отправлен.");
                case ALLIED -> message(player, "&aAlliance formed.", "&aСоюз заключён.");
                case ENEMY -> message(player, "&cClan marked as an enemy.", "&cКлан объявлен врагом.");
                case ALREADY -> message(player, "&eThis relation already exists.", "&eТакие отношения уже установлены.");
                case REJECTED -> message(player, "&cRelation change rejected.", "&cИзменение отношений отклонено.");
            }
        }));
    }

    private void top(Player player) {
        service.top(10).whenComplete((clans, error) -> plugin.scheduler().player(player, () -> {
            if (error != null) { failure(player, error); return; }
            message(player, "&6Top clans by points:", "&6Топ кланов по очкам:");
            for (int i = 0; i < clans.size(); i++) {
                ClanView clan = clans.get(i);
                player.sendMessage(ClanService.color("&e" + (i + 1) + ". &f[" + clan.tag() + "] " + clan.name() + " &7— " + clan.points()));
            }
        }));
    }

    private void info(Player player, String[] args) {
        if (args.length > 1) {
            service.repository().findClan(args[1]).whenComplete((found, error) -> plugin.scheduler().player(player, () -> {
                if (error != null || found.isEmpty()) message(player, "&cClan not found.", "&cКлан не найден.");
                else gui.open(player, found.get());
            }));
            return;
        }
        ClanView clan = service.cached(player.getUniqueId()).orElse(null);
        if (clan == null) message(player, "&eYou are not in a clan. Use /clan create <name> <tag>", "&eВы не состоите в клане. Используйте /clan create <название> <тег>");
        else gui.open(player, clan);
    }

    private void complete(Player player, CompletableFuture<Boolean> future, String english, String russian) {
        future.whenComplete((ok, error) -> plugin.scheduler().player(player, () -> {
            if (error != null) failure(player, error);
            else if (Boolean.TRUE.equals(ok)) message(player, "&a" + english, "&a" + russian);
            else message(player, "&cOperation rejected.", "&cОперация отклонена.");
        }));
    }
    private void failure(Player player, Throwable error) { message(player, "&cOperation failed: ", "&cОперация не выполнена: ", root(error).getMessage()); }
    private static Throwable root(Throwable value) { while (value.getCause() != null) value = value.getCause(); return value; }
    private void deny(CommandSender sender) { sender.sendMessage(ClanService.color(service.text("&cYou do not have permission.", "&cНедостаточно прав."))); }
    private void message(CommandSender sender, String english, String russian, String... suffix) { sender.sendMessage(ClanService.color(service.text(english, russian) + String.join("", suffix))); }
    private static void usage(CommandSender sender) { sender.sendMessage("/clan create|invite|accept|info|top|leave|kick|promote|demote|transfer|ff|bank|sethome|home|ally|enemy|neutral|disband"); }

    @Override public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) return List.of("create","invite","accept","info","top","leave","kick","promote","demote","transfer","ff","bank","sethome","home","ally","enemy","neutral","disband","reload");
        if (args.length == 2 && List.of("invite","kick","promote","demote","transfer").contains(args[0].toLowerCase(Locale.ROOT)))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("ff")) return List.of("on","off");
        if (args.length == 2 && args[0].equalsIgnoreCase("bank")) return List.of("balance","deposit","withdraw");
        return List.of();
    }
}

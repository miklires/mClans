package io.github.miklires.mclans.clan;

import io.github.miklires.mclans.MClansPlugin;
import io.github.miklires.mclans.Text;
import io.github.miklires.mclans.api.ClanMemberView;
import io.github.miklires.mclans.api.ClanRole;
import io.github.miklires.mclans.api.ClanView;
import io.github.miklires.mclans.economy.EconomyBridge;
import io.github.miklires.mclans.storage.ClanRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ClanService {
    private final MClansPlugin plugin;
    private final ClanRepository repository;
    private final EconomyBridge economy;
    private volatile ClanNamingPolicy naming;
    private volatile ClanProgression progression;
    private final Map<UUID, ClanView> playerClans = new ConcurrentHashMap<>();
    private final Map<UUID, Long> homeCooldowns = new ConcurrentHashMap<>();
    private volatile Set<ClanRepository.ClanPair> allies = Set.of();

    public ClanService(MClansPlugin plugin, ClanRepository repository, EconomyBridge economy) {
        this.plugin = plugin;
        this.repository = repository;
        this.economy = economy;
        reload();
        refreshAllies();
    }

    public void reload() {
        naming = new ClanNamingPolicy(plugin.getConfig().getString("naming.name-regex", "[A-Za-z0-9_]{3,16}"),
                plugin.getConfig().getString("naming.tag-regex", "[A-Za-z0-9]{2,5}"),
                Set.copyOf(plugin.getConfig().getStringList("naming.reserved")));
        List<ClanProgression.Level> levels = new ArrayList<>();
        for (Map<?, ?> row : plugin.getConfig().getMapList("progression.levels")) {
            Object level = row.get("level"), points = row.get("points"), members = row.get("members");
            if (level instanceof Number l && points instanceof Number p && members instanceof Number m)
                levels.add(new ClanProgression.Level(l.intValue(), p.longValue(), m.intValue()));
        }
        if (levels.isEmpty()) levels.add(new ClanProgression.Level(1, 0, 5));
        progression = new ClanProgression(levels);
    }

    public CompletableFuture<ClanView> create(Player owner, String name, String tag) {
        ClanNamingPolicy.Result result = naming.validate(name, tag);
        if (result != ClanNamingPolicy.Result.ALLOWED)
            return CompletableFuture.failedFuture(new IllegalArgumentException(result.name()));
        long cost = Math.clamp(plugin.getConfig().getLong("economy.creation-cost", 0), 0, 1_000_000_000L);
        if (cost > 0 && (!economy.available() || !economy.withdraw(owner, cost)))
            return CompletableFuture.failedFuture(new IllegalStateException("Not enough money or economy unavailable"));
        CompletableFuture<ClanView> future = repository.create(owner.getUniqueId(), owner.getName(), name, tag);
        future.whenComplete((clan, error) -> {
            if (error == null) playerClans.put(owner.getUniqueId(), clan);
            else if (cost > 0) plugin.scheduler().player(owner, () -> economy.deposit(owner, cost));
        });
        return future;
    }

    public CompletableFuture<Boolean> invite(Player actor, Player target) {
        if (actor.getUniqueId().equals(target.getUniqueId())) return CompletableFuture.completedFuture(false);
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage()) return CompletableFuture.completedFuture(false);
            return repository.member(target.getUniqueId()).thenCompose(existing -> {
                if (existing.isPresent()) return CompletableFuture.completedFuture(false);
                long seconds = Math.clamp(plugin.getConfig().getLong("invites.expire-seconds", 120), 10L, 604_800L);
                long expiry = Instant.now().plusSeconds(seconds).toEpochMilli();
                return repository.invite(ctx.clan().id(), target.getUniqueId(), target.getName(), actor.getUniqueId(), expiry)
                        .thenApply(ignored -> true);
            });
        });
    }

    public CompletableFuture<Boolean> accept(Player player, String nameOrTag) {
        return repository.findClan(nameOrTag).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(false);
            ClanView clan = found.get();
            int limit = progression.forPoints(clan.points()).memberLimit();
            return repository.acceptInvite(player.getUniqueId(), player.getName(), clan.id(), limit)
                    .thenApply(ok -> { if (ok) refresh(player.getUniqueId()); return ok; });
        });
    }

    public CompletableFuture<Boolean> leave(Player player) {
        return repository.leave(player.getUniqueId()).thenApply(ok -> {
            if (ok) playerClans.remove(player.getUniqueId());
            return ok;
        });
    }

    public CompletableFuture<Boolean> disband(Player owner) {
        ClanView clan = playerClans.get(owner.getUniqueId());
        if (clan == null) return CompletableFuture.completedFuture(false);
        return repository.disband(clan.id(), owner.getUniqueId()).thenApply(ok -> {
            if (ok) {
                playerClans.entrySet().removeIf(entry -> entry.getValue().id() == clan.id());
                refreshAllies();
            }
            return ok;
        });
    }

    public CompletableFuture<Boolean> kick(Player actor, String targetName) {
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage()) return CompletableFuture.completedFuture(false);
            return repository.memberByName(ctx.clan().id(), targetName).thenCompose(target -> {
                if (target.isEmpty() || target.get().role() == ClanRole.OWNER
                        || rank(ctx.member().role()) >= rank(target.get().role())) return CompletableFuture.completedFuture(false);
                return repository.removeMember(ctx.clan().id(), target.get().playerId()).thenApply(ok -> {
                    if (ok) playerClans.remove(target.get().playerId());
                    return ok;
                });
            });
        });
    }

    public CompletableFuture<Boolean> setRole(Player actor, String targetName, ClanRole role) {
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || ctx.member().role() != ClanRole.OWNER) return CompletableFuture.completedFuture(false);
            return repository.memberByName(ctx.clan().id(), targetName)
                    .thenCompose(target -> target.isEmpty() ? CompletableFuture.completedFuture(false)
                            : repository.setRole(ctx.clan().id(), target.get().playerId(), role));
        });
    }

    public CompletableFuture<Boolean> transfer(Player owner, String targetName) {
        return context(owner.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || ctx.member().role() != ClanRole.OWNER) return CompletableFuture.completedFuture(false);
            return repository.memberByName(ctx.clan().id(), targetName).thenCompose(target -> {
                if (target.isEmpty() || target.get().playerId().equals(owner.getUniqueId()))
                    return CompletableFuture.completedFuture(false);
                return repository.transferOwnership(ctx.clan().id(), owner.getUniqueId(), target.get().playerId())
                        .thenApply(ok -> { if (ok) refreshClan(ctx.clan().id()); return ok; });
            });
        });
    }

    public CompletableFuture<Boolean> setFriendlyFire(Player actor, boolean enabled) {
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || ctx.member().role() != ClanRole.OWNER) return CompletableFuture.completedFuture(false);
            return repository.setFriendlyFire(ctx.clan().id(), actor.getUniqueId(), enabled)
                    .thenApply(ok -> { if (ok) refreshClan(ctx.clan().id()); return ok; });
        });
    }

    public CompletableFuture<Boolean> deposit(Player player, long amount) {
        ClanView clan = playerClans.get(player.getUniqueId());
        if (clan == null || amount <= 0 || !economy.available() || !economy.withdraw(player, amount))
            return CompletableFuture.completedFuture(false);
        return repository.changeBank(clan.id(), player.getUniqueId(), amount, "deposit").thenApply(ok -> {
            if (ok) refreshClan(clan.id());
            else plugin.scheduler().player(player, () -> economy.deposit(player, amount));
            return ok;
        });
    }

    public CompletableFuture<Boolean> withdraw(Player player, long amount) {
        return context(player.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage() || amount <= 0 || !economy.available())
                return CompletableFuture.completedFuture(false);
            return repository.changeBank(ctx.clan().id(), player.getUniqueId(), -amount, "withdraw").thenApply(ok -> {
                if (!ok) return false;
                plugin.scheduler().player(player, () -> {
                    if (!economy.deposit(player, amount))
                        repository.changeBank(ctx.clan().id(), player.getUniqueId(), amount, "withdraw-refund");
                });
                refreshClan(ctx.clan().id());
                return true;
            });
        });
    }

    public CompletableFuture<Boolean> setHome(Player player) {
        return context(player.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage()) return CompletableFuture.completedFuture(false);
            Location location = player.getLocation();
            return repository.setHome(ctx.clan().id(), player.getUniqueId(), location.getWorld().getUID(),
                    location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        });
    }

    public CompletableFuture<Boolean> teleportHome(Player player) {
        ClanView clan = playerClans.get(player.getUniqueId());
        if (clan == null) return CompletableFuture.completedFuture(false);
        long cooldown = Math.clamp(plugin.getConfig().getLong("home.cooldown-seconds", 30), 0, 86_400) * 1000L;
        long now = System.currentTimeMillis();
        if (homeCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) return CompletableFuture.completedFuture(false);
        return repository.home(clan.id()).thenApply(found -> {
            if (found.isEmpty()) return false;
            ClanRepository.ClanHome home = found.get();
            plugin.scheduler().player(player, () -> {
                var world = Bukkit.getWorld(home.worldId());
                if (world != null) {
                    homeCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown);
                    player.teleportAsync(new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch()));
                }
            });
            return true;
        });
    }

    public CompletableFuture<ClanRepository.RelationResult> relation(Player actor, String target, boolean alliance) {
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage())
                return CompletableFuture.completedFuture(ClanRepository.RelationResult.REJECTED);
            return repository.findClan(target).thenCompose(found -> {
                if (found.isEmpty()) return CompletableFuture.completedFuture(ClanRepository.RelationResult.REJECTED);
                return (alliance ? repository.requestAlliance(ctx.clan().id(), found.get().id())
                        : repository.setEnemy(ctx.clan().id(), found.get().id())).thenApply(result -> {
                    refreshAllies();
                    return result;
                });
            });
        });
    }

    public CompletableFuture<Boolean> neutral(Player actor, String target) {
        return context(actor.getUniqueId()).thenCompose(ctx -> {
            if (ctx == null || !ctx.member().role().canManage()) return CompletableFuture.completedFuture(false);
            return repository.findClan(target).thenCompose(found -> found.isEmpty()
                    ? CompletableFuture.completedFuture(false)
                    : repository.removeRelation(ctx.clan().id(), found.get().id()).thenApply(ok -> { refreshAllies(); return ok; }));
        });
    }

    public void awardKill(Player killer, Player victim) {
        ClanView clan = playerClans.get(killer.getUniqueId());
        if (clan == null || sameClan(killer.getUniqueId(), victim.getUniqueId())) return;
        long value = Math.clamp(plugin.getConfig().getLong("pvp.points-per-kill", 10), 0L, 1_000_000L);
        long cooldown = Math.clamp(plugin.getConfig().getLong("pvp.same-victim-cooldown-seconds", 3600), 0L, 31_536_000L) * 1000L;
        long nextPoints = clan.points() > Long.MAX_VALUE - value ? Long.MAX_VALUE : clan.points() + value;
        int level = progression.forPoints(nextPoints).level();
        repository.awardKill(clan.id(), killer.getUniqueId(), victim.getUniqueId(), value, cooldown, level)
                .thenAccept(ok -> { if (ok) refreshClan(clan.id()); });
    }

    public void clanChat(Player sender, String message) {
        ClanView clan = playerClans.get(sender.getUniqueId());
        if (clan == null) {
            sender.sendMessage(Component.text(text("You are not in a clan.", "Вы не состоите в клане."), NamedTextColor.RED));
            return;
        }
        String clean = message.codePoints().limit(256).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        Component formatted = Component.text("[" + clan.tag() + "] ", NamedTextColor.GOLD)
                .append(Component.text(sender.getName() + ": ", NamedTextColor.WHITE))
                .append(Component.text(clean, NamedTextColor.GRAY));
        Bukkit.getOnlinePlayers().stream().filter(player -> sameClan(sender.getUniqueId(), player.getUniqueId()))
                .forEach(player -> plugin.scheduler().player(player, () -> player.sendMessage(formatted)));
    }

    public void refresh(UUID player) {
        repository.clanOf(player).thenAccept(found -> {
            if (found.isPresent()) playerClans.put(player, found.get()); else playerClans.remove(player);
        });
    }
    public void refreshClan(long id) { playerClans.forEach((player, clan) -> { if (clan.id() == id) refresh(player); }); }
    private void refreshAllies() { repository.allies().thenAccept(value -> allies = value); }
    public boolean sameClan(UUID a, UUID b) { ClanView first=playerClans.get(a),second=playerClans.get(b);return first!=null&&second!=null&&first.id()==second.id(); }
    public boolean blocksFriendlyFire(UUID a, UUID b) {
        ClanView first=playerClans.get(a),second=playerClans.get(b);
        if(first==null||second==null)return false;
        if(first.id()==second.id())return !first.friendlyFire();
        return plugin.getConfig().getBoolean("relations.protect-allies",true)
                && allies.contains(new ClanRepository.ClanPair(first.id(),second.id()));
    }
    public CompletableFuture<Context> context(UUID player) { return repository.clanOf(player).thenCombine(repository.member(player),(clan,member)->clan.isPresent()&&member.isPresent()?new Context(clan.get(),member.get()):null); }
    public String text(String english,String russian){return Text.tr(plugin,english,russian);}
    public ClanRepository repository(){return repository;}
    public Optional<ClanView> cached(UUID player){return Optional.ofNullable(playerClans.get(player));}
    public CompletableFuture<List<ClanView>> top(int limit){return repository.top(limit);}
    public boolean economyAvailable(){return economy.available();}
    private static int rank(ClanRole role){return switch(role){case OWNER->0;case OFFICER->1;case MEMBER->2;};}
    public static String color(String value){return org.bukkit.ChatColor.translateAlternateColorCodes('&',value);}
    public record Context(ClanView clan,ClanMemberView member){}
}

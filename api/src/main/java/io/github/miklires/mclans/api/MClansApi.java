package io.github.miklires.mclans.api;
import java.util.List;import java.util.Optional;import java.util.UUID;import java.util.concurrent.CompletableFuture;
public interface MClansApi {CompletableFuture<Optional<ClanView>> clanOf(UUID player);CompletableFuture<List<ClanMemberView>> members(long clanId);CompletableFuture<Boolean> areAllies(UUID first,UUID second);}

package io.github.miklires.mclans.api;
import java.time.Instant;import java.util.UUID;
public record ClanMemberView(UUID playerId,String playerName,ClanRole role,Instant joinedAt){}

package io.github.miklires.mclans.api;
import java.time.Instant;import java.util.UUID;
public record ClanView(long id,String name,String tag,int level,long points,long bank,boolean friendlyFire,int memberCount,UUID ownerId,Instant createdAt){}

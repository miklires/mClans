package io.github.miklires.mclans.api;
public enum ClanRole { OWNER, OFFICER, MEMBER; public boolean canManage(){return this==OWNER||this==OFFICER;} }

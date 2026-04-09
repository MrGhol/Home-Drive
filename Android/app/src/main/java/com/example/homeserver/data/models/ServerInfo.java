package com.example.homeserver.data.models;

import com.google.gson.annotations.SerializedName;

public class ServerInfo {
    private String status;
    private String name;
    private String version;
    @SerializedName("server_id")
    private String serverId;
    @SerializedName("lan_ip")
    private String lanIp;

    // Getters
    public String getStatus() { return status; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getServerId() { return serverId; }
    public String getLanIp() { return lanIp; }
}
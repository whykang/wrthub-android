package com.whykangkang.wrthub.model;

import java.util.UUID;

/**
 * 一台被管理的路由器,对应 iOS 的 RouterDevice。
 * 凭据随对象整体存入 EncryptedSharedPreferences(对应 Keychain)。
 */
public class RouterDevice {

    private String id;
    private String name;
    private String host;
    private int port;
    private boolean useHttps;
    private String username;
    private String password;

    public RouterDevice() {
        this.id = UUID.randomUUID().toString();
        this.port = 80;
        this.username = "root";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isUseHttps() {
        return useHttps;
    }

    public void setUseHttps(boolean useHttps) {
        this.useHttps = useHttps;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** 列表主标题:名称为空时用 host */
    public String getDisplayName() {
        return name != null && !name.trim().isEmpty() ? name.trim() : host;
    }

    /** 列表副标题:http(s)://host:port */
    public String getDisplayAddress() {
        return (useHttps ? "https://" : "http://") + host + ":" + port;
    }
}
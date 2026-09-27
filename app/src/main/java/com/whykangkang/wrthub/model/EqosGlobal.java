package com.whykangkang.wrthub.model;

/**
 * 设备限速全局设置。download/upload 是**整条 WAN 的总带宽**(Mbit/s),
 * eqos 用它来算 HTB 根队列,填错会让所有限速都不准。
 * 对应 iOS Models/EqosConfig.swift 的 EqosGlobal。
 */
public class EqosGlobal {

    public static final int DEFAULT_DOWNLOAD = 100;
    public static final int DEFAULT_UPLOAD = 20;

    public boolean enabled;
    public int download;
    public int upload;

    public EqosGlobal(boolean enabled, int download, int upload) {
        this.enabled = enabled;
        this.download = download;
        this.upload = upload;
    }

    public static EqosGlobal defaults() {
        return new EqosGlobal(false, DEFAULT_DOWNLOAD, DEFAULT_UPLOAD);
    }
}

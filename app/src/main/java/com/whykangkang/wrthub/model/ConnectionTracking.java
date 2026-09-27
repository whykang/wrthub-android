package com.whykangkang.wrthub.model;

/** conntrack 当前/上限,对应 iOS ConnectionTrackingInfo */
public class ConnectionTracking {

    public int current;
    public int max;

    public ConnectionTracking(int current, int max) {
        this.current = current;
        this.max = max;
    }

    public double percentage() {
        if (max <= 0) return 0;
        return current * 100.0 / max;
    }

    /** green / orange / red —— 与 iOS statusColor 分档一致 */
    public String statusColor() {
        double p = percentage();
        if (p < 50) return "green";
        if (p < 80) return "orange";
        return "red";
    }
}

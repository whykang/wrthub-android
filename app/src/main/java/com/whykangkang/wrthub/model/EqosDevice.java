package com.whykangkang.wrthub.model;

/**
 * 单台设备的限速条目,对应 iOS EqosDevice。
 *
 * eqos.js 里 download/upload 的 datatype 是 and(uinteger,min(1)) 且 rmempty=false:
 * 两个方向都是必填且最小 1,不能用 0 表示「不限制」。
 */
public class EqosDevice {

    /** uci 段名(匿名段如 cfg0392bd)。新建时为空,写入后由路由器回填。 */
    public String section = "";
    /** 每条规则自己的启用开关(网页端可单独停用而不删除) */
    public boolean enabled = true;
    public String ip = "";
    public int download;   // Mbit/s
    public int upload;     // Mbit/s
    public String comment = "";

    /** 列表主标题:有备注用备注,否则用 IP */
    public String displayName() {
        return comment == null || comment.trim().isEmpty() ? ip : comment.trim();
    }

    /** IP 按段做数值比较,避免 .10 排在 .9 前面 */
    public static int compareIp(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = parse(pa[i]);
            int vb = parse(pb[i]);
            if (va != vb) return Integer.compare(va, vb);
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

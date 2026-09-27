package com.whykangkang.wrthub.model;

import android.content.Context;

import com.whykangkang.wrthub.R;

/**
 * 一条 Samba 共享(uci samba4 的 sambashare 段),
 * 对应 iOS SambaServiceViewController.SambaShare。
 */
public class SambaShare {

    /** uci section 名(如 share1 / cfg0392bd) */
    public String section;
    /** 共享名称(option name),缺省用 section */
    public String displayName;
    public String path = "";
    public boolean readOnly;
    public boolean guestOk;
    public String createMask = "0666";
    public String dirMask = "0777";
    public boolean browseable = true;

    /** 列表副标题:"路径 (只读) • 允许访客",与 iOS description 一致 */
    public String describe(Context ctx) {
        StringBuilder sb = new StringBuilder(path);
        if (readOnly) {
            sb.append(' ').append(ctx.getString(R.string.samba_readonly_tag));
        }
        if (guestOk) {
            sb.append(" • ").append(ctx.getString(R.string.samba_guest_tag));
        }
        return sb.toString();
    }
}

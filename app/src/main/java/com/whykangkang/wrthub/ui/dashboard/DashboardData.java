package com.whykangkang.wrthub.ui.dashboard;

import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.model.ConnectionTracking;
import com.whykangkang.wrthub.model.NetworkDevice;
import com.whykangkang.wrthub.model.NetworkInterfaceInfo;
import com.whykangkang.wrthub.model.RouterInfo;
import com.whykangkang.wrthub.model.StorageInfo;
import com.whykangkang.wrthub.model.WiFiInfo;

import java.util.ArrayList;
import java.util.List;

/** 首页一次刷新聚合后的数据快照,对应 iOS DashboardViewController 的各 property */
public class DashboardData {

    /** null 表示这一轮连接失败(首页显示「连接失败」卡) */
    public RouterInfo routerInfo;
    public final List<NetworkInterfaceInfo> interfaces = new ArrayList<>();
    public final List<NetworkDevice> networkDevices = new ArrayList<>();
    public final List<ConnectedDevice> connectedDevices = new ArrayList<>();
    public final List<WiFiInfo> wifi = new ArrayList<>();
    public final List<StorageInfo> storage = new ArrayList<>();
    public ConnectionTracking conntrack;

    /** WAN 口字节差分出的速率(bytes/s),首轮为 null */
    public Double wanDownSpeed;
    public Double wanUpSpeed;

    /** 本轮是否出过错(用于是否记评分 engagement) */
    public boolean hadError;
}

package com.example.wallswitch;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 配对信息：地址怎么拼怎么解、info 响应体的字段、本机哪个 IPv4 该写进码里、二维码点阵怎么铺成像素。
 *
 * <p>只用 java.net / java.security / java.util，不 import android 类型，
 * 所以 check/LanSyncTest.java 能编译这份真源码跑断言。
 *
 * <p>刻意不引 org.json：它属于 android，引进来这个类就脱离 JVM 夹具（换成复刻算式就有两处定义、
 * 就会漂），而且这功能只有 10 个扁平标量字段，k=v 行文本真机调试时肉眼也能读。
 *
 * <p>二维码里只放地址（约 50 字节）不放清单：QR 越短点越粗、越远越好扫，
 * 把包清单塞进码里会变成一片扫不动的芝麻；清单由 {@code GET /<口令>/info} 单独给。
 */
public final class LanPair {

    /** 协议版本：字段含义变了就 +1，收端遇到不认识的版本直接报错而不是猜。 */
    public static final int PROTOCOL_V = 1;

    /** 剔掉 0/O/1/l/i：手输兜底那行地址时不容易看错。 */
    static final String TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    static final int TOKEN_LEN = 8;
    /** 口令短于这个长度就当没解出来（挡掉「随便一句话」这类输入）。 */
    static final int MIN_TOKEN_LEN = 4;

    static final String KEY_V = "v";
    static final String KEY_NAME = "name";
    static final String KEY_TIME = "time";
    static final String KEY_ITEMS = "items";
    static final String KEY_IMAGES = "images";
    static final String KEY_ORIGINALS = "originals";
    static final String KEY_THUMBS = "thumbs";
    static final String KEY_BYTES = "bytes";
    static final String KEY_SHA = "sha256";
    static final String KEY_APP = "app";

    private LanPair() {
    }

    public static String randomToken() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(TOKEN_LEN);
        for (int i = 0; i < TOKEN_LEN; i++) {
            sb.append(TOKEN_ALPHABET.charAt(rnd.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String buildUrl(String host, int port, String token) {
        return "http://" + host + ":" + port + "/" + token;
    }

    /** 解出来的对端地址。{@code error} 非空就当没解出来，别拿去连。 */
    public static final class Target {
        public String host;
        public String token;
        public int port;
        public String error;
    }

    /**
     * 宽容解析：带不带 {@code http://}、大小写、首尾空格、结尾斜杠都认 —— 兜底那条路
     * （长按复制地址、用任意聊天工具发过去再手输）全靠这里宽容。
     * 但带 {@code @}、带第二段路径、端口越界一律拒，宁可不连也不猜。
     */
    public static Target parse(String text) {
        Target t = new Target();
        if (text == null) {
            t.error = "没输入地址";
            return t;
        }
        String s = text.trim();
        if (s.regionMatches(true, 0, "http://", 0, 7)) {
            s = s.substring(7);
        }
        s = s.toLowerCase(Locale.US);
        int slash = s.indexOf('/');
        String authority = slash < 0 ? s : s.substring(0, slash);
        String path = slash < 0 ? "" : s.substring(slash + 1);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (authority.indexOf('@') >= 0 || path.indexOf('@') >= 0) {
            t.error = "地址里带了不该有的字符";
            return t;
        }
        if (path.indexOf('/') >= 0) {
            t.error = "地址后面多了别的路径，只该有「主机:端口/口令」";
            return t;
        }
        int colon = authority.lastIndexOf(':');
        if (colon <= 0 || authority.indexOf(':') != colon) {
            t.error = "格式应是 主机:端口/口令";
            return t;
        }
        t.host = authority.substring(0, colon);
        try {
            t.port = Integer.parseInt(authority.substring(colon + 1));
        } catch (NumberFormatException e) {
            t.error = "端口不是数字";
            return t;
        }
        t.token = path;
        if (t.host.isEmpty() || t.port < 1 || t.port > 65535 || t.token.length() < MIN_TOKEN_LEN) {
            t.error = "地址不完整（端口 1~65535，口令至少 " + MIN_TOKEN_LEN + " 位）";
            return t;
        }
        return t;
    }

    /** info 响应体：十行 k=v。 */
    public static String encodeInfo(int protocolV, String name, String time, int items, int images,
                                    int originals, int thumbs, long bytes, String sha256,
                                    String appVersion) {
        StringBuilder sb = new StringBuilder(256);
        line(sb, KEY_V, String.valueOf(protocolV));
        line(sb, KEY_NAME, name);
        line(sb, KEY_TIME, time);
        line(sb, KEY_ITEMS, String.valueOf(items));
        line(sb, KEY_IMAGES, String.valueOf(images));
        line(sb, KEY_ORIGINALS, String.valueOf(originals));
        line(sb, KEY_THUMBS, String.valueOf(thumbs));
        line(sb, KEY_BYTES, String.valueOf(bytes));
        line(sb, KEY_SHA, sha256);
        line(sb, KEY_APP, appVersion);
        return sb.toString();
    }

    /** 值里不许有换行（会多出伪字段行），也不许有等号（会让解析歧义），有的话一律截掉。 */
    private static void line(StringBuilder sb, String key, String value) {
        String v = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        int eq = v.indexOf('=');
        if (eq >= 0) {
            v = v.substring(0, eq);
        }
        sb.append(key).append('=').append(v).append('\n');
    }

    /** 对端的现场。{@code error} 非空 = 拒绝连接，别发起下载。 */
    public static final class Info {
        public int protocolV;
        public int items;
        public int images;
        public int originals;
        public int thumbs;
        public long bytes;
        public String name;
        public String time;
        public String sha256;
        public String appVersion;
        public String error;
    }

    /** 未知字段忽略（前向兼容）；数字读不出、版本不符、大小或摘要缺失都置 error。 */
    public static Info parseInfo(String text) {
        Info i = new Info();
        if (text == null || text.trim().isEmpty()) {
            i.error = "对方没给出包信息";
            return i;
        }
        for (String raw : text.split("\n")) {
            int eq = raw.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = raw.substring(0, eq).trim();
            String value = raw.substring(eq + 1).trim();
            try {
                if (KEY_V.equals(key)) {
                    i.protocolV = Integer.parseInt(value);
                } else if (KEY_NAME.equals(key)) {
                    i.name = value;
                } else if (KEY_TIME.equals(key)) {
                    i.time = value;
                } else if (KEY_ITEMS.equals(key)) {
                    i.items = Integer.parseInt(value);
                } else if (KEY_IMAGES.equals(key)) {
                    i.images = Integer.parseInt(value);
                } else if (KEY_ORIGINALS.equals(key)) {
                    i.originals = Integer.parseInt(value);
                } else if (KEY_THUMBS.equals(key)) {
                    i.thumbs = Integer.parseInt(value);
                } else if (KEY_BYTES.equals(key)) {
                    i.bytes = Long.parseLong(value);
                } else if (KEY_SHA.equals(key)) {
                    i.sha256 = value;
                } else if (KEY_APP.equals(key)) {
                    i.appVersion = value;
                }
            } catch (NumberFormatException e) {
                i.error = "对方给的信息里数字读不出（第 " + key + " 项）";
                return i;
            }
        }
        if (i.protocolV != PROTOCOL_V) {
            i.error = "对方用的是第 " + i.protocolV + " 版协议，这台机只认第 " + PROTOCOL_V
                    + " 版：两台手机装同一个安装包";
            return i;
        }
        if (i.bytes <= 0) {
            i.error = "对方没说包有多大，不敢开收";
            return i;
        }
        if (i.sha256 == null || i.sha256.length() < 32) {
            i.error = "对方没给包的校验摘要，收了也没法验";
            return i;
        }
        return i;
    }

    /** 只留「同网段设备大概率走得通」的 IPv4，顺序照输入保留（界面要按网卡顺序列给用户）。 */
    public static List<String> filterCandidates(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (isPrivateIPv4(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 是私网 IPv4 吗：192.168.* / 10.* / 172.16~31.*。
     * 丢 loopback、链路本地 169.254.*（DHCP 没拿到地址时的临时地址，对端根本走不到）、
     * 0.*、带前导零的怪写法、以及公网地址 —— 把公网 IP 写进码里等于把端口摊到公网上。
     */
    static boolean isPrivateIPv4(String s) {
        if (s == null || s.indexOf(':') >= 0) {
            return false;
        }
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octet = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 1 && parts[i].charAt(0) == '0') {
                return false;
            }
            int v;
            try {
                v = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v < 0 || v > 255) {
                return false;
            }
            octet[i] = v;
        }
        if (octet[0] == 127 || octet[0] == 0 || (octet[0] == 169 && octet[1] == 254)) {
            return false;
        }
        if (octet[0] == 192 && octet[1] == 168) {
            return true;
        }
        if (octet[0] == 10) {
            return true;
        }
        return octet[0] == 172 && octet[1] >= 16 && octet[1] <= 31;
    }

    /**
     * 该把哪个地址写进码里：热点网关 192.168.43.* 优先 —— 另一台机连本机热点时只有这一条必然通，
     * 而且它正是「路由器把客户端互相隔离了」那条唯一走得通的退路。其次家用段、10.*、172.*。
     * 一个都不剩时返回空串（界面据此提示「先连 Wi-Fi 或开热点」），不返回 null。
     */
    public static String prefer(List<String> raw) {
        List<String> ok = filterCandidates(raw);
        for (String prefix : PREFERRED_PREFIXES) {
            for (String s : ok) {
                if (s.startsWith(prefix)) {
                    return s;
                }
            }
        }
        return ok.isEmpty() ? "" : ok.get(0);
    }

    /** 写进码里的地址优先级，见 {@link #prefer}：热点网关最先。 */
    private static final String[] PREFERRED_PREFIXES = {"192.168.43.", "192.168.", "10.", "172."};

    /** 枚举本机网卡的 IPv4（java.net，不需要任何 android 权限，也不需要定位）。 */
    public static List<String> localIPv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nf.isUp() || nf.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress addr : Collections.list(nf.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 把二维码点阵铺成 int 像素（黑 0xFF000000 / 白 0xFFFFFFFF），四周留白（quiet zone）。
     * 不认 Bitmap 也不认 zxing，所以尺寸、反色、留白三条能在 JVM 里断言。
     *
     * <p>一模块一像素在屏幕上会糊成扫不动的芝麻，所以每模块至少 4px；本 App 的 URL 约 50 字节、
     * M 级纠错下是 25~33 模块，取 8px/模块 → 约 300px 见方，240dp 的框里正好。
     *
     * @param rows         行优先的点阵，长度须为 modulesWide*modulesHigh，true 表示黑
     * @param pxPerModule  一个模块画几个像素
     * @param quietModules 四周留几个模块的白边（标准是 4，扫码方自己也会补）
     */
    public static int[] qrPixels(boolean[] rows, int modulesWide, int modulesHigh,
                                 int pxPerModule, int quietModules) {
        int w = (modulesWide + quietModules * 2) * pxPerModule;
        int h = (modulesHigh + quietModules * 2) * pxPerModule;
        int[] out = new int[w * h];
        java.util.Arrays.fill(out, 0xFFFFFFFF);
        for (int my = 0; my < modulesHigh; my++) {
            for (int mx = 0; mx < modulesWide; mx++) {
                if (!rows[my * modulesWide + mx]) {
                    continue;
                }
                int x0 = (mx + quietModules) * pxPerModule;
                int y0 = (my + quietModules) * pxPerModule;
                for (int dy = 0; dy < pxPerModule; dy++) {
                    int base = (y0 + dy) * w + x0;
                    for (int dx = 0; dx < pxPerModule; dx++) {
                        out[base + dx] = 0xFF000000;
                    }
                }
            }
        }
        return out;
    }

    /** 点阵铺开后一行的像素宽（界面侧要按它给 Bitmap 定尺寸）。 */
    public static int qrSidePixels(int modules, int pxPerModule, int quietModules) {
        return (modules + quietModules * 2) * pxPerModule;
    }
}

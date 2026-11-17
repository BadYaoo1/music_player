package com.pclplayer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站音频获取器（自包含 WBI 签名 + AV/BV 互转）
 */
public class BiliAudioFetcher {

    static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/wbi/view";
    static final String PLAYER_URL = "https://api.bilibili.com/x/player/wbi/playurl";
    static final String REFERER = "https://www.bilibili.com";

    // ===== Cookie 改为可动态设置 =====
    private static String cookie = "";
    // 网络差时必须有超时，否则 client.send() 会一直挂起，导致界面"未响应"
    private static final java.time.Duration NET_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(8);
    private static final java.time.Duration NET_READ_TIMEOUT = java.time.Duration.ofSeconds(20);
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(NET_CONNECT_TIMEOUT)
            .build();

    public static void setCookie(String c) {
        cookie = c != null ? c : "";
        cachedImgKey = null;
        cachedSubKey = null;
        System.out.println("[BiliAudioFetcher] cookie 已更新");
    }

    // ==================== AV → BV 转换 ====================
    static final String BV_TABLE = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF";
    static final int[] BV_POS = {11, 10, 3, 8, 4, 6, 2, 9, 5, 7};
    static final long XOR = 177451812L;
    static final long ADD = 8728348608L;

    public static String avToBv(String av) {
        try {
            long aid = Long.parseLong(av.replaceAll("(?i)av", "").trim());
            long s = (aid ^ XOR) + ADD;
            char[] bv = "BV1       ".toCharArray();
            for (int i = 0; i < 6; i++) {
                int idx = (int) (s / power(58, i) % 58);
                bv[BV_POS[i]] = BV_TABLE.charAt(idx);
            }
            return new String(bv);
        } catch (Exception e) {
            System.out.println("⚠ avToBv 失败: " + e.getMessage());
            return null;
        }
    }

    static long power(int a, int b) {
        long r = 1;
        for (int i = 0; i < b; i++) r *= a;
        return r;
    }

    // ==================== WBI 签名 ====================
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static String cachedImgKey, cachedSubKey;
    private static long cacheTime = 0;

    static String[] getWbiKeys() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedImgKey != null && now - cacheTime < 3600_000)
            return new String[]{cachedImgKey, cachedSubKey};

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", REFERER)
                .header("Cookie", cookie)
                .timeout(NET_READ_TIMEOUT)
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        cachedImgKey = extractKey(resp.body(), "img_url");
        cachedSubKey = extractKey(resp.body(), "sub_url");
        cacheTime = now;
        return new String[]{cachedImgKey, cachedSubKey};
    }

    static String extractKey(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon) + 1;
        int end = json.indexOf("\"", start);
        String url = json.substring(start, end);
        return url.substring(url.lastIndexOf("/") + 1).replace(".png", "");
    }

    static String getMixinKey(String orig) {
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN_KEY_ENC_TAB)
            if (i < orig.length()) sb.append(orig.charAt(i));
        return sb.toString().substring(0, 32);
    }

    static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String wbiSign(Map<String, String> params) {
        try {
            String[] keys = getWbiKeys();
            String mixinKey = getMixinKey(keys[0] + keys[1]);

            Map<String, String> signed = new LinkedHashMap<>(params);
            signed.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
            Map<String, String> sorted = new TreeMap<>(signed);

            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (query.length() > 0) query.append("&");
                query.append(e.getKey()).append("=")
                        .append(e.getValue().replaceAll("[!'()*]", ""));
            }

            String wbiSign = md5(query.toString() + mixinKey);
            return query.toString() + "&w_rid=" + wbiSign;
        } catch (Exception e) {
            throw new RuntimeException("WBI 签名失败: " + e.getMessage(), e);
        }
    }

    // ==================== 业务方法 ====================
    static String getCid(String bvid) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        String signed = wbiSign(params);
        String url = VIEW_URL + "?" + signed;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", REFERER)
                .header("Cookie", cookie)
                .timeout(NET_READ_TIMEOUT)
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        Matcher m = Pattern.compile("\"cid\":(\\d+)").matcher(resp.body());
        if (m.find()) return m.group(1);
        throw new RuntimeException("cid not found");
    }

    static String getAudioUrl(String bvid, String cid) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", "64");
        params.put("fnval", "16");
        params.put("fnver", "0");
        params.put("platform", "pc");
        String signed = wbiSign(params);
        String url = PLAYER_URL + "?" + signed;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", REFERER)
                .header("Cookie", cookie)
                .timeout(NET_READ_TIMEOUT)
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        Matcher m = Pattern.compile("\"audio\"\\s*:\\[\\s*\\{([^}]*?)\\}").matcher(resp.body());
        if (m.find()) {
            String audioObj = m.group(1);
            Matcher urlM = Pattern.compile("\"baseUrl\":\"(.*?)\"").matcher(audioObj);
            // B站 会把 baseUrl 里的 & 转义成 &，不还原的话 URI.create 会抛
            // "Illegal character in query"，导致整条音频下载链路失败
            if (urlM.find()) return urlM.group(1).replace("\\/", "/").replace("\\u0026", "&");
        }
        throw new RuntimeException("audio url not found");
    }

    public static String fetchAndSave(String input, String outPath) {
        try {
            String bvid = input;
            if (input.matches("(?i)av\\d+")) {
                bvid = avToBv(input);
                if (bvid == null) return null;
                System.out.println("  AV 号 " + input + " → BV 号 " + bvid);
            }

            String cid = getCid(bvid);
            String audioUrl = getAudioUrl(bvid, cid);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(audioUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", REFERER)
                    .header("Cookie", cookie)
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream in = resp.body();
                 var out = Files.newOutputStream(Paths.get(outPath))) {
                in.transferTo(out);
            }
            return outPath;
        } catch (Exception e) {
            System.out.println("⚠ BiliAudioFetcher: " + e.getMessage());
            return null;
        }
    }

    /**
     * 命令行下载 B站视频音频，用法：
     * java -cp target\classes com.pclplayer.BiliAudioFetcher &lt;BV号/AV号/视频链接&gt; [输出路径]
     * 视频链接里会直接抠出 BV 号，所以可以整段粘贴 B站链接（?t= 之类的参数会被忽略）。
     * 不传输出路径时默认保存为「BV号.m4s」（B站 dash 音频流原始格式）。
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java com.pclplayer.BiliAudioFetcher <BV号/AV号/视频链接> [输出路径]");
            return;
        }
        String input = args[0].trim();
        Matcher bvM = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(input);
        if (bvM.find()) input = bvM.group(1);
        String outPath = args.length > 1 ? args[1] : input + ".m4s";
        String saved = fetchAndSave(input, outPath);
        if (saved == null) {
            System.out.println("下载失败");
            return;
        }
        java.io.File f = new java.io.File(saved);
        System.out.println("已保存: " + f.getAbsolutePath() + " (" + (f.length() / 1024) + " KB)");
    }
}
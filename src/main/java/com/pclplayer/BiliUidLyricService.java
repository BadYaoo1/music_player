package com.pclplayer;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站 UID/视频 → 歌词获取服务（自包含 WBI 签名）
 *
 * 优先级: Node.js (QQ/酷狗) → 网易云兜底
 */
public class BiliUidLyricService {

    // ==================== 配置 ====================
    static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/wbi/view";
    static final String ARC_SEARCH_URL = "https://api.bilibili.com/x/space/wbi/arc/search";
    static final String REFERER = "https://www.bilibili.com";
    static final String NETEASE_API = "http://localhost:3000";

    // B 站 Cookie：不再硬编码任何凭证（硬编码会随打包泄露）。
    // 运行时由 PlayerApp 登录后调用 setCookie(...) 注入；未登录时为空，
    // 此时 B 站标题获取会失败，但不影响 Node 歌词（QQ/酷狗）与网易云。
    static String COOKIE = "";

    /** 注入 B 站 Cookie（PlayerApp 登录/读取本地 cookie 后调用） */
    static void setCookie(String cookie) {
        COOKIE = (cookie == null) ? "" : cookie.trim();
    }

    static HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .build();

    // ==================== WBI 签名 ====================
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static String cachedImgKey = null, cachedSubKey = null;
    private static long cacheTime = 0;

    static String[] getWbiKeys() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedImgKey != null && now - cacheTime < 3600_000)
            return new String[]{cachedImgKey, cachedSubKey};

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
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
        } catch (Exception e) { throw new RuntimeException(e); }
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
            return query.toString() + "&w_rid=" + md5(query.toString() + mixinKey);
        } catch (Exception e) {
            throw new RuntimeException("WBI 签名失败: " + e.getMessage(), e);
        }
    }

    // ==================== B站解析 ====================

    static String getVideoTitleByBv(String bvid) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("bvid", bvid);
            String url = VIEW_URL + "?" + wbiSign(params);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", REFERER).header("Cookie", COOKIE).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"title\":\"(.*?)\"").matcher(resp.body());
            if (m.find()) return m.group(1).replace("\\u002f", "/").replace("\\/", "/");
        } catch (Exception e) {
            System.out.println("  ⚠ BV 标题获取失败: " + e.getMessage());
        }
        return null;
    }

    static String getVideoTitleByAv(String av) {
        try {
            String aid = av.replaceAll("[^0-9]", "");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("aid", aid);
            String url = VIEW_URL + "?" + wbiSign(params);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", REFERER).header("Cookie", COOKIE).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"title\":\"(.*?)\"").matcher(resp.body());
            if (m.find()) return m.group(1).replace("\\u002f", "/").replace("\\/", "/");
        } catch (Exception e) {
            System.out.println("  ⚠ AV 标题获取失败: " + e.getMessage());
        }
        return null;
    }

    static String getLatestVideoTitleByUid(String uid) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("mid", uid); params.put("pn", "1"); params.put("ps", "1");
            params.put("order", "pubdate"); params.put("tid", "0");
            params.put("keyword", ""); params.put("platform", "web");
            params.put("web_location", "1550104"); params.put("order_avoided", "true");
            String url = ARC_SEARCH_URL + "?" + wbiSign(params);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Referer", "https://space.bilibili.com/" + uid)
                    .header("Cookie", COOKIE).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"title\":\"(.*?)\"").matcher(resp.body());
            if (m.find()) return m.group(1).replace("\\u002f", "/").replace("\\/", "/");
        } catch (Exception e) {
            System.out.println("  ⚠ UID 最新视频获取失败: " + e.getMessage());
        }
        return null;
    }

    static String extractBvFromUrl(String url) {
        Matcher m = Pattern.compile("(BV[0-9A-Za-z]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String cleanTitle(String title) {
        if (title == null) return null;
        Matcher m = Pattern.compile("《([^》]+)》").matcher(title);
        if (m.find()) return m.group(1).trim();
        return title.replaceAll("\\[.*?\\]", "").replaceAll("【.*?】", "")
                .replaceAll("（.*?）", "").replaceAll("\\(.*?\\)", "")
                .replaceAll("(?i)MV|官方|视频", "").trim();
    }

    // ==================== 歌词来源 ====================

    /** 默认来源顺序：QQ → 网易云 → 酷狗 */
    public static final List<String> DEFAULT_SOURCE_ORDER =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList("qq", "netease", "kugou"));

    /** 由 Node 桥（lyric-bridge.js）负责的来源；网易云走 Java 侧直连 */
    private static final java.util.Set<String> NODE_SOURCES =
            new java.util.LinkedHashSet<>(java.util.Arrays.asList("qq", "kugou"));

    public static final java.util.Map<String, String> SOURCE_LABEL = java.util.Map.of(
            "qq", "QQ音乐",
            "netease", "网易云",
            "kugou", "酷狗");

    // ==================== Node.js 优先 ====================

    /**
     * 调 Node 桥取歌词。
     *
     * @param title     搜索关键词
     * @param platforms 按序尝试的平台（qq / kugou）；为 null 或空时由桥默认"谁快用谁"
     */
    static String fetchLyricViaNode(String title, String platforms) {
        try {
            // 用健壮定位：打包 exe 的 user.dir 不一定是安装目录，且目标机器未必装了 node。
            // resolveBridgeJs/resolveNodeCmd 会按“代码所在目录/install 目录/工作目录”多路径回退，
            // 并优先使用随包 node.exe，找不到才退回系统 PATH。
            Path bridgeJs = OtherPlatformProxy.resolveBridgeJs();
            if (bridgeJs == null || !Files.exists(bridgeJs)) {
                System.out.println("  ⚠ lyric-bridge.js 不存在，跳过");
                return null;
            }
            java.util.List<String> cmd = new ArrayList<>();
            cmd.add(OtherPlatformProxy.resolveNodeCmd());
            cmd.add(bridgeJs.toAbsolutePath().toString());
            cmd.add(title);
            if (platforms != null && !platforms.isEmpty()) cmd.add(platforms);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // 一次性读完整输出
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            proc.waitFor(12, java.util.concurrent.TimeUnit.SECONDS);
            proc.destroyForcibly();

            String output = sb.toString().trim();
            if (output.isEmpty() || output.startsWith("ERROR")) return null;

            // SOURCE:xxx\n<LRC> 协议
            if (output.startsWith("SOURCE:")) {
                int idx = output.indexOf('\n');
                if (idx > 0) return output.substring(idx + 1);
            }
            return output; // 纯 LRC 兜底
        } catch (Exception e) {
            System.out.println("  ⚠ Node.js 异常: " + e.getMessage());
        }
        return null;
    }

    // ==================== Node 桥：多候选（同来源前 N 首匹配）====================

    /** 调 Node 桥取某来源的前 limit 个候选歌词（多版本），解析为候选列表 */
    static List<LyricCandidate> fetchNodeCandidates(String title, String platforms, int limit) {
        List<LyricCandidate> out = new ArrayList<>();
        try {
            Path bridgeJs = OtherPlatformProxy.resolveBridgeJs();
            if (bridgeJs == null || !Files.exists(bridgeJs)) {
                System.out.println("  ⚠ lyric-bridge.js 不存在，跳过");
                return out;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(OtherPlatformProxy.resolveNodeCmd());
            cmd.add(bridgeJs.toAbsolutePath().toString());
            cmd.add(title);
            if (platforms != null && !platforms.isEmpty()) cmd.add(platforms);
            cmd.add("multi:" + Math.max(1, limit));   // 第 4 参：多候选模式
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            proc.destroyForcibly();
            String output = sb.toString().trim();
            if (output.isEmpty() || output.startsWith("ERROR")) return out;
            out.addAll(parseNodeCandidates(output));
        } catch (Exception e) {
            System.out.println("  ⚠ Node.js 异常: " + e.getMessage());
        }
        return out;
    }

    /** 解析 Node 桥的多候选协议：@@CANDIDATE@@ / SOURCE: / TITLE: / <LRC> / @@END@@ */
    static List<LyricCandidate> parseNodeCandidates(String output) {
        List<LyricCandidate> out = new ArrayList<>();
        final String MARKER = "@@CANDIDATE@@", END = "@@END@@";
        int idx = 0;
        while ((idx = output.indexOf(MARKER, idx)) >= 0) {
            int end = output.indexOf(END, idx);
            if (end < 0) break;
            String block = output.substring(idx + MARKER.length(), end);
            String src = null, title = null;
            StringBuilder lrc = new StringBuilder();
            boolean inHeader = true;
            for (String ln : block.split("\n", -1)) {
                if (inHeader) {
                    if (ln.startsWith("SOURCE:")) { src = ln.substring(7).trim(); continue; }
                    if (ln.startsWith("TITLE:")) { title = ln.substring(6).trim(); continue; }
                    inHeader = false;   // 首个非头行开始是 LRC
                }
                if (!inHeader) {
                    if (lrc.length() > 0) lrc.append('\n');
                    lrc.append(ln);
                }
            }
            List<LyricLine> lines = parseLrc(lrc.toString());
            if (lines != null && !lines.isEmpty())
                out.add(new LyricCandidate(src, title, lines));
            idx = end + END.length();
        }
        // 兼容旧单条格式（无 marker）：整段当作一个候选
        if (out.isEmpty() && !output.startsWith(MARKER)) {
            List<LyricLine> lines = parseLrc(output);
            if (lines != null && !lines.isEmpty())
                out.add(new LyricCandidate(null, null, lines));
        }
        return out;
    }

    // ==================== 网易云兜底 ====================

    static String searchNeteaseSongId(String keyword) {
        List<NeteaseLyrics.Song> songs = NeteaseLyrics.search(keyword, 1);
        return songs.isEmpty() ? null : songs.get(0).id;
    }

    static String fetchNeteaseLyric(String songId) {
        return NeteaseLyrics.fetchLyric(songId);
    }

    /** 网易云：搜索关键词，取前 limit 首匹配歌曲，每首歌词作为一个候选 */
    static List<LyricCandidate> searchNeteaseCandidates(String keyword, int limit) {
        List<LyricCandidate> out = new ArrayList<>();
        for (NeteaseLyrics.Song song : NeteaseLyrics.search(keyword, Math.max(1, limit))) {
            List<LyricLine> lines = parseToLines(fetchNeteaseLyric(song.id));
            if (lines != null) {
                out.add(new LyricCandidate("netease",
                        song.name + (song.artists.isEmpty() ? "" : " - " + song.artists), lines));
            }
        }
        return out;
    }

    // ==================== LRC 解析 ====================

    static class LyricLine {
        String text; long startMs, endMs;
        LyricLine(String t, long s, long e) { text = t; startMs = s; endMs = e; }
    }

    /** 单个歌词候选：某来源搜索到的某一首匹配歌曲（含歌名/歌手与解析后的歌词行） */
    static class LyricCandidate {
        String source;                 // 来源名（qq / netease / kugou）
        String title;                 // 展示标题（歌名 - 歌手）
        List<LyricLine> lines;        // 该候选的歌词行
        LyricCandidate(String s, String t, List<LyricLine> l) { source = s; title = t; lines = l; }
    }

    static List<LyricLine> parseLrc(String lrcText) {
        List<LyricLine> lines = new ArrayList<>();
        Pattern timePattern = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
        for (String raw : lrcText.split("\n")) {
            raw = raw.trim().replace("\r", "");
            if (raw.isEmpty()) continue;
            Matcher tm = timePattern.matcher(raw);
            if (!tm.find()) continue;
            int min = Integer.parseInt(tm.group(1));
            double sec = Double.parseDouble(tm.group(2));
            long startMs = (long)(min * 60 * 1000 + sec * 1000);
            String text = raw.replaceAll("\\[\\d+:\\d+(?:\\.\\d+)?\\]", "").trim();
            if (text.isEmpty()) continue;
            lines.add(new LyricLine(text, startMs, startMs + 3000));
        }
        for (int i = 0; i < lines.size() - 1; i++)
            lines.get(i).endMs = lines.get(i + 1).startMs;
        if (!lines.isEmpty())
            lines.get(lines.size() - 1).endMs = lines.get(lines.size() - 1).startMs + 5000;
        return lines;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    static String toJson(List<LyricLine> lines) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < lines.size(); i++) {
            LyricLine l = lines.get(i);
            sb.append("  {\"text\":\"").append(escape(l.text))
                    .append("\",\"start_ms\":\"").append(l.startMs)
                    .append("\",\"end_ms\":\"").append(l.endMs).append("\"}");
            if (i < lines.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    static void writeJson(String jsonPath, List<LyricLine> lines) {
        try { Files.writeString(Paths.get(jsonPath), toJson(lines), StandardCharsets.UTF_8); }
        catch (IOException e) { System.out.println("  ⚠ 写文件失败: " + e.getMessage()); }
    }

    // ==================== 主入口 ====================

    public static int fetchAndSave(String input, String jsonPath) {
        return fetchAndSave(input, jsonPath, DEFAULT_SOURCE_ORDER);
    }

    /**
     * 按指定来源顺序取词。
     *
     * @param sourceOrder 平台名序列（qq / netease / kugou），按顺序尝试，
     *                    前一个拿不到才试下一个。为 null 或空时用默认顺序。
     */
    public static int fetchAndSave(String input, String jsonPath, java.util.List<String> sourceOrder) {
        System.out.println("🔍 搜索歌词: \"" + input + "\"");

        // ① 解析输入
        String searchKeyword = null;

        if (input.startsWith("BV") || input.startsWith("bv")) {
            String title = getVideoTitleByBv(input);
            if (title != null) {
                searchKeyword = cleanTitle(title);
                System.out.println("  → B站标题: " + title + " → 关键词: " + searchKeyword);
            }
        } else if (input.matches("(?i)av\\d+")) {
            String title = getVideoTitleByAv(input);
            if (title != null) {
                searchKeyword = cleanTitle(title);
                System.out.println("  → B站标题: " + title + " → 关键词: " + searchKeyword);
            }
        } else if (input.matches("https?://.*bilibili\\.com.*")) {
            String bv = extractBvFromUrl(input);
            if (bv != null) {
                String title = getVideoTitleByBv(bv);
                if (title != null) {
                    searchKeyword = cleanTitle(title);
                    System.out.println("  → B站标题: " + title + " → 关键词: " + searchKeyword);
                }
            }
        } else if (input.matches("\\d{6,12}")) {
            String title = getLatestVideoTitleByUid(input);
            if (title != null) {
                searchKeyword = cleanTitle(title);
                System.out.println("  → UID最新视频: " + title + " → 关键词: " + searchKeyword);
            }
        }

        if (searchKeyword == null || searchKeyword.trim().isEmpty()) {
            searchKeyword = input;
            System.out.println("  → 直接作为歌名: " + searchKeyword);
        }

        // ② 按设置里的来源顺序逐个尝试
        List<String> order = (sourceOrder == null || sourceOrder.isEmpty())
                ? DEFAULT_SOURCE_ORDER : sourceOrder;

        // qq / kugou 都由 Node 桥负责，合并成一次调用（桥内部仍按传入顺序串行尝试），
        // 这样"QQ→酷狗"不会为了一个失败来源多起一次 Node 进程
        for (int i = 0; i < order.size(); ) {
            String src = order.get(i);
            if (!NODE_SOURCES.contains(src)) { i++; continue; }

            // 收集连续的、同属 Node 桥的来源，保持它们的相对顺序
            StringBuilder platforms = new StringBuilder();
            StringBuilder label = new StringBuilder();
            while (i < order.size() && NODE_SOURCES.contains(order.get(i))) {
                String s = order.get(i);
                if (platforms.length() > 0) { platforms.append(","); label.append("/"); }
                platforms.append(s);
                label.append(SOURCE_LABEL.getOrDefault(s, s));
                i++;
            }

            System.out.println("  → [" + label + "]: " + searchKeyword);
            String lrc = fetchLyricViaNode(searchKeyword, platforms.toString());
            List<LyricLine> lines = parseToLines(lrc);
            if (lines != null) {
                writeJson(jsonPath, lines);
                System.out.println("  ✅ " + label + " 歌词写入完成: " + lines.size() + " 行");
                return lines.size();
            }
            System.out.println("  ⚠ " + label + " 未获取到，尝试下一个来源...");
        }

        // ③ 网易云（Java 侧直连，只在轮到它时才走）
        if (order.contains("netease")) {
            System.out.println("  → [网易云]: " + searchKeyword);
            String songId = searchNeteaseSongId(searchKeyword);
            if (songId != null) {
                List<LyricLine> lines = parseToLines(fetchNeteaseLyric(songId));
                if (lines != null) {
                    writeJson(jsonPath, lines);
                    System.out.println("  ✅ 网易云歌词写入完成: " + lines.size() + " 行");
                    return lines.size();
                }
            }
            System.out.println("  ⚠ 网易云未获取到");
        }

        System.out.println("  ❌ 所有来源均未获取到歌词");
        return 0;
    }

    // ===== 多来源分别拉取（供"歌词设置"面板列出全部来源供选择）=====

    /** 把输入解析成搜索关键词（与 fetchAndSave 内部逻辑一致） */
    public static String resolveKeyword(String input) {
        if (input == null) return "";
        String searchKeyword = null;
        if (input.startsWith("BV") || input.startsWith("bv")) {
            String t = getVideoTitleByBv(input);
            if (t != null) searchKeyword = cleanTitle(t);
        } else if (input.matches("(?i)av\\d+")) {
            String t = getVideoTitleByAv(input);
            if (t != null) searchKeyword = cleanTitle(t);
        } else if (input.matches("https?://.*bilibili\\.com.*")) {
            String bv = extractBvFromUrl(input);
            if (bv != null) {
                String t = getVideoTitleByBv(bv);
                if (t != null) searchKeyword = cleanTitle(t);
            }
        } else if (input.matches("\\d{6,12}")) {
            String t = getLatestVideoTitleByUid(input);
            if (t != null) searchKeyword = cleanTitle(t);
        }
        if (searchKeyword == null || searchKeyword.trim().isEmpty()) searchKeyword = input;
        return searchKeyword;
    }

    /** 从单个来源取歌词（不落盘），拿不到返回 null */
    public static List<LyricLine> fetchSource(String input, String source) {
        String kw = resolveKeyword(input);
        switch (source) {
            case "qq":
                return parseToLines(fetchLyricViaNode(kw, "qq"));
            case "kugou":
                return parseToLines(fetchLyricViaNode(kw, "kugou"));
            case "netease": {
                String id = searchNeteaseSongId(kw);
                if (id == null) return null;
                return parseToLines(fetchNeteaseLyric(id));
            }
            default:
                return null;
        }
    }

    /** 取单个来源的前 limit 个候选歌词（多版本） */
    public static List<LyricCandidate> fetchSourceCandidates(String input, String source, int limit) {
        String kw = resolveKeyword(input);
        switch (source) {
            case "qq":      return fetchNodeCandidates(kw, "qq", limit);
            case "kugou":   return fetchNodeCandidates(kw, "kugou", limit);
            case "netease": return searchNeteaseCandidates(kw, limit);
            default:        return new ArrayList<>();
        }
    }

    /** 依次从各来源取前 limit 个候选，返回 来源名 -> 候选列表（取不到为 null/空）。顺序：qq, netease, kugou */
    public static Map<String, List<LyricCandidate>> fetchAllCandidates(String input, int limit) {
        String[] order = {"qq", "netease", "kugou"};
        Map<String, List<LyricCandidate>> result = new java.util.concurrent.ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (String s : order) {
            Thread t = new Thread(() -> {
                try {
                    result.put(s, fetchSourceCandidates(input, s, limit));
                } catch (Throwable ex) {
                    result.put(s, new ArrayList<>());   // 单个来源失败不影响其它来源
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(20000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // 按固定顺序回填，保证面板展示顺序稳定
        Map<String, List<LyricCandidate>> map = new LinkedHashMap<>();
        for (String s : order) map.put(s, result.get(s));
        return map;
    }

    /** 依次从各来源取词，返回 来源名 -> 行列表（取不到为 null）。顺序：qq, netease, kugou */
    public static java.util.Map<String, List<LyricLine>> fetchAllSources(String input) {
        String[] order = {"qq", "netease", "kugou"};
        java.util.Map<String, List<LyricLine>> result = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (String s : order) {
            Thread t = new Thread(() -> {
                try {
                    result.put(s, fetchSource(input, s));
                } catch (Throwable ex) {
                    result.put(s, null);   // 单个来源失败不影响其它来源
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(15000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // 按固定顺序回填，保证面板展示顺序稳定
        java.util.Map<String, List<LyricLine>> map = new java.util.LinkedHashMap<>();
        for (String s : order) map.put(s, result.get(s));
        return map;
    }

    /** 解析 LRC 为行列表；拿不到或解析不出来返回 null */
    private static List<LyricLine> parseToLines(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return null;
        List<LyricLine> lines = parseLrc(lrc);
        return lines.isEmpty() ? null : lines;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法: java BiliUidLyricService <BV/AV/UID/歌名> [输出路径]");
            return;
        }
        String out = args.length > 1 ? args[1] : "lyrics.json";
        int rows = fetchAndSave(args[0], out);
        System.out.println(rows > 0 ? "✅ " + rows + " 行" : "❌ 失败");
    }
}
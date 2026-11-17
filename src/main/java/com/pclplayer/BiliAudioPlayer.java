package com.pclplayer;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BiliAudioPlayer {

    private final AtomicLong requestCounter = new AtomicLong(0);
    private volatile long currentRequestId = 0;

    // ============ WBI 签名（不变） ============
    private static String wbiImgKey = null, wbiSubKey = null, wbiMixinKey = null;
    private static long lastWbiFetch = 0;
    private static final long WBI_CACHE_MS = 10 * 60 * 1000;
    // B站官方 WBI 混淆表，恰好 64 项（原被误写成 139 项，后半段是复制残留）
    private static final int[] WBI_MIXIN_KEY_ENC_TAB = {
            46, 47, 18,  2, 53,  8, 23, 32, 15, 50, 10, 31, 58,  3, 45, 35,
            27, 43,  5, 49, 33,  9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48,  7, 16, 24, 55, 40, 61, 26, 17,  0,  1, 60, 51, 30,  4,
            22, 25, 54, 21, 56, 59,  6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };
    private static void getWbiKeys(String cookie) throws IOException {
        long now = System.currentTimeMillis();
        if (wbiMixinKey != null && (now - lastWbiFetch) < WBI_CACHE_MS) return;
        String navJson = getWithCookie("https://api.bilibili.com/x/web-interface/nav", cookie);
        Matcher imgM = Pattern.compile("\"img_url\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(navJson);
        Matcher subM = Pattern.compile("\"sub_url\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(navJson);
        if (!imgM.find() || !subM.find()) throw new IOException("WBI keys not found");
        wbiImgKey = imgM.group(1).substring(imgM.group(1).lastIndexOf("/") + 1).replace(".png", "");
        wbiSubKey = subM.group(1).substring(subM.group(1).lastIndexOf("/") + 1).replace(".png", "");
        wbiMixinKey = genMixinKey(wbiImgKey + wbiSubKey);
        lastWbiFetch = now;
    }
    private static String genMixinKey(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(raw.charAt(WBI_MIXIN_KEY_ENC_TAB[i]));
        return sb.toString();
    }
    private static String wbiSign(String params, String cookie) {
        try {
            getWbiKeys(cookie);
            if (wbiMixinKey == null) return params;
            long wts = System.currentTimeMillis() / 1000;
            String signed = params + "&wts=" + wts;
            String toHash = signed + wbiMixinKey;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(toHash.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return signed + "&w_rid=" + hex.toString();
        } catch (Exception e) { return params; }
    }
    private static String getWithCookie(String urlStr, String cookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        // 网络差时必须超时，否则会一直挂起导致界面"未响应"
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        if (cookie != null && !cookie.isBlank()) conn.setRequestProperty("Cookie", cookie);
        conn.connect();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return sb.toString();
    }

    // ============ 成员 ============
    private MediaPlayer mediaPlayer;
    private String cookie = "";
    private String currentBvid = null;
    private File currentTempFile;
    private volatile boolean ffmpegChecked = false, ffmpegAvailable = false;
    /** seek 后的短暂保护期：拖动进度条（尤其拖到接近末尾）时 MediaPlayer 可能误触发
     *  onEndOfMedia，若不屏蔽会被上层当成"播完"而跳下一首。 */
    private volatile long suppressEndedUntil = 0;

    // 音质档位：true=高音质（优先 192K 及以上），false=标准音质（128K，省流量）
    private volatile boolean highQuality = true;

    // ============ 预取缓存（下一首预渲染） ============
    private static final int MAX_PREFETCH = 2;   // 最多缓存几首已转码的下一首，限制磁盘占用
    private final Map<String, File> prefetchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, File>(8, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, File> eldest) {
                    if (size() > MAX_PREFETCH) { cleanupFile(eldest.getValue()); return true; }
                    return false;
                }
            });
    private final Set<String> prefetchRequested = ConcurrentHashMap.newKeySet();  // 去重，避免重复预取

    /** 设置音质档位。只影响后续播放，正在播放的曲目需重新 playBvid 才生效 */
    public void setHighQuality(boolean high) { this.highQuality = high; }
    public boolean isHighQuality() { return highQuality; }

    public interface PlayCallback {
        void onPlaying(); void onPaused(); void onResumed();
        void onStopped(); void onError(String msg); void onProgress(double currentMs, double totalMs);
    }
    private PlayCallback cb;
    public void setCallback(PlayCallback cb) { this.cb = cb; }
    public void setCookie(String cookie) { if (cookie != null) this.cookie = cookie; }
    public String getCurrentBvid() { return currentBvid; }

    // ============ 播放控制 ============
    public void playBvid(String bvid) {
        long reqId = requestCounter.incrementAndGet();
        currentRequestId = reqId;
        this.currentBvid = bvid;
        stopInternal();
        prefetchRequested.remove(bvid);

        // ★ 预取命中：直接用已转码缓存，省去网络/下载/转码等待（切下一首几乎无延迟）
        File cached = prefetchCache.remove(bvid);
        if (cached != null) {
            final File f = cached;
            System.out.println("[audio] 预取命中，直接播放缓存: " + bvid);
            Platform.runLater(() -> {
                if (reqId != currentRequestId) { cleanupFile(f); return; }
                startLocalMedia(f);
            });
            return;
        }

        new Thread(() -> {
            File tmp = null;
            try {
                long cid = fetchCid(bvid);
                if (cid == 0) throw new RuntimeException("获取 cid 失败");
                String audioUrl = fetchBestAudioUrl(bvid, cid);
                if (audioUrl == null) throw new RuntimeException("没有可用的音频流");
                tmp = downloadAndTranscode(audioUrl);
                if (tmp == null) throw new RuntimeException("音频下载/转码失败");

                if (reqId != currentRequestId) { cleanupFile(tmp); return; }

                final File finalTmp = tmp;
                final long finalReqId = reqId;
                Platform.runLater(() -> {
                    if (finalReqId != currentRequestId) { cleanupFile(finalTmp); return; }
                    startLocalMedia(finalTmp);
                });
            } catch (Exception e) {
                if (reqId != currentRequestId) { if (tmp != null) cleanupFile(tmp); return; }
                System.out.println("[audio] 解析/下载失败: " + e.getMessage());
                if (cb != null) cb.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * 预渲染（预取）下一首：后台线程完成 解析cid → 取音频地址 → 下载+转码，
     * 把转码后的临时文件缓存起来；后续 playBvid 命中缓存时直接本地播放，省掉网络/转码等待。
     * 失败静默忽略；已请求/已缓存/正在播放的会跳过，性能友好（单首串行、限容 2 首）。
     */
    public void prefetchBvid(String bvid) {
        if (bvid == null) return;
        if (bvid.equals(currentBvid)) return;
        if (prefetchCache.containsKey(bvid)) return;
        if (!prefetchRequested.add(bvid)) return;   // 已在请求中则跳过
        new Thread(() -> {
            File tmp = null;
            try {
                long cid = fetchCid(bvid);
                if (cid == 0) throw new RuntimeException("cid 解析失败");
                String audioUrl = fetchBestAudioUrl(bvid, cid);
                if (audioUrl == null) throw new RuntimeException("无可用音频流");
                tmp = downloadAndTranscode(audioUrl);
                if (tmp == null) throw new RuntimeException("下载/转码失败");
                // 预取期间用户已切到这首：丢弃，避免与正在播放的重复占用
                if (bvid.equals(currentBvid)) { cleanupFile(tmp); return; }
                prefetchCache.put(bvid, tmp);
                System.out.println("[audio] 预取完成: " + bvid);
            } catch (Exception e) {
                if (tmp != null) cleanupFile(tmp);
            } finally {
                prefetchRequested.remove(bvid);
            }
        }, "prefetch-" + bvid).start();
    }

    /** 释放资源：停止播放并清理所有预取缓存的临时文件（退出时调用） */
    public void release() {
        requestCounter.incrementAndGet();
        currentRequestId = 0;
        stopInternal();
        synchronized (prefetchCache) {
            for (File f : prefetchCache.values()) cleanupFile(f);
            prefetchCache.clear();
        }
        prefetchRequested.clear();
    }

    /** ★ 修复 NPE：用 final 局部变量持有 mp */
    private void startLocalMedia(File tmp) {
        try {
            currentTempFile = tmp;
            Media media = new Media(tmp.toURI().toString());
            mediaPlayer = new MediaPlayer(media);

            // ★ 关键：用 final 变量，不被外部 stop() 置 null 影响
            final MediaPlayer mp = mediaPlayer;

            mp.setOnReady(() -> {
                if (cb != null) cb.onPlaying();
                mp.currentTimeProperty().addListener((obs, oldT, newT) -> {
                    Duration total = mp.getTotalDuration(); // ← 用 mp，不会 NPE
                    double cur = newT.toMillis();
                    double tot = (total == null || total.isUnknown()) ? -1 : total.toMillis();
                    if (cb != null) cb.onProgress(cur, tot);
                });
            });

            mp.setOnEndOfMedia(() -> {
                // seek 后的保护期内忽略：拖动（尤其拖到末尾/未缓冲区）造成的假结束不应切歌
                if (System.currentTimeMillis() < suppressEndedUntil) {
                    System.out.println("[audio] 忽略 seek 后的 onEndOfMedia（避免误切歌）");
                    return;
                }
                if (cb != null) cb.onStopped();
            });
            mp.setOnError(() -> {
                String msg = mp.getError() != null ? mp.getError().getMessage() : "unknown";
                System.out.println("[audio] MediaPlayer 错误: " + msg);
                if (cb != null) cb.onError(msg);
            });

            mp.setVolume(1.0);
            mp.play();
        } catch (Exception e) {
            System.out.println("[audio] 创建 MediaPlayer 失败: " + e.getMessage());
            if (cb != null) cb.onError(e.getMessage());
        }
    }

    public void togglePause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause(); if (cb != null) cb.onPaused();
        } else {
            mediaPlayer.play(); if (cb != null) cb.onResumed();
        }
    }
    public void pause() { if (mediaPlayer != null) mediaPlayer.pause(); }
    public void resume() { if (mediaPlayer != null) mediaPlayer.play(); }

    public void stop() {
        requestCounter.incrementAndGet();
        currentRequestId = 0;
        currentBvid = null;
        stopInternal();
        if (cb != null) cb.onStopped();
    }

    private void stopInternal() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.dispose(); } catch (Exception ignore) {}
            mediaPlayer = null;
        }
        cleanupTemp();
    }

    public void seekTo(double ms) {
        if (mediaPlayer == null) return;
        // seek 后 1.2s 内屏蔽 onEndOfMedia：MediaPlayer 对拖到末尾/未缓冲区的 seek
        // 会立即报结束，若照常回调会被上层误判为"播完"而跳下一首。
        suppressEndedUntil = System.currentTimeMillis() + 1200;
        // 目标不超过总时长减去一小段，避免正好 seek 到结束点
        Duration total = mediaPlayer.getTotalDuration();
        double target = ms;
        if (total != null && !total.isUnknown()) {
            double max = total.toMillis() - 200;
            if (max > 0 && target > max) target = max;
        }
        mediaPlayer.seek(new Duration(Math.max(0, target)));
    }
    public void setVolume(double v) { if (mediaPlayer != null) mediaPlayer.setVolume(Math.max(0, Math.min(1, v))); }
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    // ============ ffmpeg ============
    private boolean isFfmpegAvailable() {
        if (ffmpegChecked) return ffmpegAvailable;
        ffmpegChecked = true;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            ffmpegAvailable = (p.waitFor() == 0);
        } catch (Exception e) { ffmpegAvailable = false; }
        System.out.println("[audio] ffmpeg " + (ffmpegAvailable ? "可用" : "未安装，将直接播放 m4s"));
        return ffmpegAvailable;
    }

    private File downloadAndTranscode(String mediaUrl) {
        File m4s = downloadToTemp(mediaUrl);
        if (m4s == null) return null;
        if (!isFfmpegAvailable()) return m4s;
        try {
            File out = Files.createTempFile("pcl-bili-audio-", ".mp3").toFile();
            out.deleteOnExit();
            // 转码码率跟随音质档位：原先固定 128k，会把高音质(192K)也压成 128K
            String bitrate = highQuality ? "192k" : "128k";
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-i", m4s.getAbsolutePath(),
                    "-vn", "-acodec", "libmp3lame", "-ar", "44100", "-b:a", bitrate, out.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) {}
            }
            p.waitFor();
            cleanupFile(m4s);
            if (out.length() < 1024) return m4s.exists() ? m4s : null;
            System.out.println("[audio] 已转码 mp3: " + out.length() / 1024 + " KB");
            return out;
        } catch (Exception e) {
            System.out.println("[audio] ffmpeg 转码异常: " + e.getMessage());
            return m4s;
        }
    }

    // ============ B 站接口（不变） ============
    private long fetchCid(String bvid) {
        try {
            String json = getWithCookie("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, cookie);
            Matcher m = Pattern.compile("\"cid\":(\\d+)").matcher(json);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception e) { System.out.println("[audio] fetchCid 失败: " + e.getMessage()); }
        return 0;
    }
    private String fetchBestAudioUrl(String bvid, long cid) {
        try {
            String params = "bvid=" + bvid + "&cid=" + cid + "&fnval=16&fourk=1&qn=64";
            String signedParams = wbiSign(params, cookie);
            String url = "https://api.bilibili.com/x/player/wbi/playurl?" + signedParams;
            String json = getWithCookie(url, cookie);
            int dashIdx = json.indexOf("\"dash\"");
            if (dashIdx < 0) return null;
            int audioArrIdx = json.indexOf("\"audio\"", dashIdx);
            if (audioArrIdx < 0) return null;
            int start = json.indexOf('[', audioArrIdx);
            int end = json.indexOf(']', start);
            if (start < 0 || end < 0) return null;
            String audioBlock = json.substring(start + 1, end);
            List<AudioItem> items = new ArrayList<>();
            Matcher row = Pattern.compile("\"id\"\\s*:\\s*(\\d+).*?\"baseUrl\"\\s*:\\s*\"([^\"]+)\"").matcher(audioBlock);
            while (row.find()) {
                items.add(new AudioItem(Integer.parseInt(row.group(1)),
                        row.group(2).replace("\\u0026", "&").replace("\\/", "/")));
            }
            if (items.isEmpty()) return null;
            // 按音质档位决定优先级（原先无论设置如何都一律取最高，设置形同虚设）
            // 30251=Hi-Res无损 30250=杜比 30216=192K 30280=128K 30232=64K
            int[] order = highQuality
                    ? new int[]{30251, 30250, 30216, 30280, 30232}
                    : new int[]{30280, 30232};   // 标准音质刻意避开 192K 及以上，省流量
            for (int want : order) {
                for (AudioItem it : items) if (it.id == want) return it.url;
            }
            // 都没有：高音质取码率最高的，标准音质取最低的
            AudioItem pick = items.get(0);
            for (AudioItem it : items) {
                if (highQuality ? it.id > pick.id : it.id < pick.id) pick = it;
            }
            System.out.println("[audio] 未命中预设档位，回落 id=" + pick.id + "（高音质=" + highQuality + "）");
            return pick.url;
        } catch (Exception e) { return null; }
    }
    private static class AudioItem { int id; String url; AudioItem(int id, String url) { this.id = id; this.url = url; } }

    /** 分段并行下载：超过 1.5MB 且 CDN 支持 Range 时按块并发拉取再合并，显著缩短长音频等待。 */
    private static final long PARALLEL_MIN_BYTES = 1_500_000;   // 约 5 分钟音频
    private static final long CHUNK_SIZE = 2L * 1024 * 1024;    // 每块 2MB：足够小，单块能在 readTimeout 内下完
    private static final int MAX_CHUNKS = 512;                  // 块数上限（几百 MB 也够用）
    private static final int MAX_THREADS = 8;                   // 并发线程数，与块数解耦

    private void applyAudioHeaders(HttpURLConnection conn) throws java.net.ProtocolException {
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        if (!cookie.isBlank()) conn.setRequestProperty("Cookie", cookie);
        conn.setConnectTimeout(10_000); conn.setReadTimeout(30_000);
    }

    /** 用 Range 请求下载单个块到 partPath，成功返回 true。 */
    private boolean downloadChunk(String mediaUrl, long from, long to, Path partPath) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(mediaUrl).openConnection();
            applyAudioHeaders(conn);
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
            conn.connect();
            int code = conn.getResponseCode();
            // 必须严格是 206：若返回 200 说明服务器忽略了 Range、给的是整个文件，
            // 把它当作一个"块"合并会导致文件里拼接出多个完整 m4s，播放到第一个片段就报结束（看着像"播一半跳下一首"）。
            if (code != 206) return false;
            long expect = to - from + 1;
            long total = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(partPath)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > expect) return false;   // 返回超出预期，异常，判失败
                }
            }
            if (total != expect) return false;   // 块不完整/过长，判失败（整段回退单流）
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private File downloadToTemp(String mediaUrl) {
        try {
            // ① 探测总大小与 Range 支持：Range 0-0 应返回 206 且 Content-Range 形如 bytes 0-0/TOTAL
            HttpURLConnection probe = (HttpURLConnection) new URL(mediaUrl).openConnection();
            applyAudioHeaders(probe);
            probe.setRequestProperty("Range", "bytes=0-0");
            probe.connect();
            int pcode = probe.getResponseCode();
            long totalSize = -1;
            if (pcode == 206) {
                String cr = probe.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try { totalSize = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); } catch (Exception ignore) {}
                }
            }
            probe.disconnect();

            Path p = Files.createTempFile("pcl-bili-audio-", ".m4a");

            // ② 可分段：足够大 + 拿到总大小
            if (totalSize >= PARALLEL_MIN_BYTES) {
                int chunks = (int) Math.min(MAX_CHUNKS, (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
                // ★ 每块大小必须按"总大小/块数"重算，而不是固定 CHUNK_SIZE。
                // 否则当文件超过 MAX_CHUNKS*CHUNK_SIZE 时，循环只覆盖前 MAX_CHUNKS 块，
                // 后面的数据全丢 → 文件被截断 → 播放到一半就触发 onEndOfMedia 跳下一首。
                long chunkSize = (totalSize + chunks - 1) / chunks;
                Path[] parts = new Path[chunks];
                boolean[] ok = new boolean[chunks];
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(chunks, MAX_THREADS));
                CountDownLatch latch = new CountDownLatch(chunks);
                for (int i = 0; i < chunks; i++) {
                    final int idx = i;
                    long from = (long) idx * chunkSize;
                    long to = Math.min(totalSize - 1, from + chunkSize - 1);
                    parts[idx] = Files.createTempFile("pcl-chunk-" + idx + "-", ".part");
                    pool.execute(() -> {
                        try { ok[idx] = downloadChunk(mediaUrl, from, to, parts[idx]); }
                        catch (Throwable ignore) {}
                        finally { latch.countDown(); }
                    });
                }
                try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                pool.shutdown();

                boolean allOk = true;
                for (boolean b : ok) if (!b) { allOk = false; break; }
                if (allOk) {
                    // ③ 按序合并成完整文件（衔接处无重叠/空洞，合并后仍是完整 fMP4）
                    long merged = 0;
                    try (OutputStream out = Files.newOutputStream(p)) {
                        for (Path part : parts) {
                            try (InputStream in = Files.newInputStream(part)) { merged += in.transferTo(out); }
                        }
                    }
                    for (Path part : parts) cleanupFile(part.toFile());
                    // 合并长度必须等于探测到的总大小，否则说明分块有遗漏/重叠，作废回退单流
                    if (merged != totalSize) {
                        System.out.println("[audio] 分段合并长度不符(" + merged + "≠" + totalSize + ")，回退单流");
                        Files.deleteIfExists(p);
                    } else {
                        File f = p.toFile(); f.deleteOnExit();
                        System.out.println("[audio] 分段并行下载(" + chunks + "块): " + f.length() / 1024 + " KB");
                        return f;
                    }
                }
                // 有块失败：清理并回退单流下载
                for (Path part : parts) cleanupFile(part.toFile());
                Files.deleteIfExists(p);
                System.out.println("[audio] 分段下载部分失败，回退单流");
            }

            // ④ 单流下载（小文件 / CDN 不支持 Range / 分段失败）
            HttpURLConnection conn = (HttpURLConnection) new URL(mediaUrl).openConnection();
            applyAudioHeaders(conn);
            conn.connect();
            if (conn.getResponseCode() != 200 && conn.getResponseCode() != 206) return null;
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(p)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            conn.disconnect();
            File f = p.toFile(); f.deleteOnExit();
            System.out.println("[audio] 已下载: " + f.length() / 1024 + " KB");
            return f;
        } catch (Exception e) {
            System.out.println("[audio] 下载失败: " + e.getMessage());
            return null;
        }
    }

    private void cleanupTemp() {
        if (currentTempFile != null) { cleanupFile(currentTempFile); currentTempFile = null; }
    }
    private void cleanupFile(File f) {
        if (f != null) { try { Files.deleteIfExists(f.toPath()); } catch (IOException ignore) {} }
    }
}
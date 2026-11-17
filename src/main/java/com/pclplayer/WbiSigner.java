import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * B站 WBI 签名器（自包含，不依赖外部）
 */
public class WbiSigner {

    // ★ MIXIN_KEY_ENC_TAB 混淆表（B站前端提取，稳定）[1,5](@ref)
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String REFERER = "https://www.bilibili.com";
    // 不在代码里放任何登录凭证：登录态由 PlayerApp 运行时注入，随包分发不含个人信息。
    static final String COOKIE = "";

    private static HttpClient client = HttpClient.newHttpClient();

    // 缓存 img_key/sub_key（每天更新一次，这里缓存 1 小时）
    private static String cachedImgKey = null;
    private static String cachedSubKey = null;
    private static long cacheTime = 0;

    /** 从 nav 接口拿 img_key 和 sub_key [4,7](@ref) */
    static String[] getWbiKeys() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedImgKey != null && now - cacheTime < 3600_000) {
            return new String[]{cachedImgKey, cachedSubKey};
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        // 提取 img_url 和 sub_url 的文件名部分
        String body = resp.body();
        String imgKey = extractKey(body, "img_url");
        String subKey = extractKey(body, "sub_url");

        cachedImgKey = imgKey;
        cachedSubKey = subKey;
        cacheTime = now;
        return new String[]{imgKey, subKey};
    }

    static String extractKey(String json, String field) {
        // 简化提取："img_url":"https://i0.hdslb.com/bfs/wbi/xxxxx.png"
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon) + 1;
        int end = json.indexOf("\"", start);
        String url = json.substring(start, end);
        // 取文件名部分，去 .png
        String fileName = url.substring(url.lastIndexOf("/") + 1);
        return fileName.replace(".png", "");
    }

    /** 生成 mixin_key：把 imgKey+subKey 按混排表取 32 个字符 [1,5](@ref) */
    static String getMixinKey(String orig) {
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN_KEY_ENC_TAB) {
            if (i < orig.length()) sb.append(orig.charAt(i));
        }
        return sb.toString().substring(0, 32);
    }

    /** MD5 工具 */
    static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 核心方法：对参数 Map 进行 WBI 签名，返回带 w_rid 的完整 query string
     * [4,7](@ref)
     */
    public static String sign(Map<String, String> params) {
        try {
            // 1. 拿 mixinKey（从 nav 接口）
            String[] keys = getWbiKeys();  // 你原来的逻辑
            String mixinKey = getMixinKey(keys[0] + keys[1]);  // 你原来的逻辑

            // 2. 加 wts
            Map<String, String> signed = new LinkedHashMap<>(params);
            signed.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

            // 3. 按 key 排序
            Map<String, String> sorted = new TreeMap<>(signed);

            // 4. 拼接 query（过滤 value 中的特殊字符）
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (query.length() > 0) query.append("&");
                String value = entry.getValue().replaceAll("[!'()*]", "");
                query.append(entry.getKey()).append("=").append(value);
            }

            // 5. MD5 → w_rid
            String wbiSign = md5(query.toString() + mixinKey);

            // 6. 返回完整 query（含 w_rid）
            return query.toString() + "&w_rid=" + wbiSign;
        } catch (Exception e) {
            throw new RuntimeException("WBI 签名失败: " + e.getMessage(), e);
        }
    }

    /** 便捷方法：从 query string 解析为 Map 后签名 */
    public static String sign(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (queryString != null && !queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return sign(params);
    }
}
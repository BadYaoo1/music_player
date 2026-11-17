import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class BiliCollectionTest {

    // ★ 把这里换成你自己的 B 站 Cookie
    private static final String COOKIE = "SESSDATA=你的SESSDATA; bili_jct=你的bili_jct; DedeUserID=你的UID;";

    // ==================== 数据模型 ====================

    public static class CollectionItem {
        public final long id;
        public final String title;
        public final String cover;
        public final int total;
        public CollectionItem(long id, String title, String cover, int total) {
            this.id = id; this.title = title; this.cover = cover; this.total = total;
        }
        @Override public String toString() { return id + " | " + title + " (" + total + ")"; }
    }

    public static class FavVideo {
        public final String bvid;
        public final String title;
        public final String upName;
        public final int duration;
        public FavVideo(String bvid, String title, String upName, int duration) {
            this.bvid = bvid; this.title = title; this.upName = upName; this.duration = duration;
        }
        @Override public String toString() { return bvid + " | " + title + " | " + upName; }
    }

    // ==================== 入口 ====================

    public static void main(String[] args) {
        System.out.println("===== 步骤 1：获取用户 mid =====");
        long mid = fetchUserMid(COOKIE);
        System.out.println("mid = " + mid);
        if (mid == 0) { System.out.println("获取 mid 失败，请检查 Cookie"); return; }

        System.out.println("\n===== 步骤 2：获取合集列表 =====");
        List<CollectionItem> collections = fetchCollectionList(COOKIE, mid);
        System.out.println("共 " + collections.size() + " 个合集：\n");
        for (CollectionItem c : collections) System.out.println("  " + c);

        if (collections.isEmpty()) { System.out.println("没有追更的合集"); return; }

        System.out.println("\n===== 步骤 3：获取第一个合集的所有视频 =====");
        CollectionItem first = collections.get(0);
        List<FavVideo> videos = fetchCollectionArchives(first.id);
        System.out.println("合集「" + first.title + "」共 " + videos.size() + " 个视频：\n");
        for (int i = 0; i < Math.min(videos.size(), 20); i++) {
            System.out.println("  " + (i + 1) + ". " + videos.get(i));
        }
        if (videos.size() > 20) System.out.println("  ...（仅显示前 20 条）");
    }

    // ==================== 接口调用 ====================

    private static long fetchUserMid(String cookie) {
        String json = get("https://api.bilibili.com/x/web-interface/nav", cookie);
        return findLong(json, "\"mid\"");
    }

    private static List<CollectionItem> fetchCollectionList(String cookie, long mid) {
        List<CollectionItem> result = new ArrayList<>();
        int pn = 1;
        while (true) {
            String json = get("https://api.bilibili.com/x/space/fav/season/list?vmid=" + mid + "&pn=" + pn + "&ps=20&type=21", cookie);
            if (!json.contains("\"code\":0")) { System.out.println("  [合集列表] 请求失败，返回：" + json.substring(0, Math.min(200, json.length()))); break; }

            List<CollectionItem> page = parseCollectionList(json);
            result.addAll(page);

            // 判断是否还有下一页
            int total = findInt(json, "\"total\"");
            if (result.size() >= total || page.isEmpty()) break;
            pn++;
        }
        return result;
    }

    private static List<CollectionItem> parseCollectionList(String json) {
        List<CollectionItem> result = new ArrayList<>();
        int listIdx = json.indexOf("\"list\"");
        if (listIdx < 0) return result;
        int arrStart = json.indexOf('[', listIdx);
        if (arrStart < 0) return result;

        int depth = 0, objStart = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
            else if (c == '{') { if (objStart < 0) objStart = i; depth++; }
            else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 1) {
                    String obj = json.substring(objStart, i + 1);
                    long id = findLong(obj, "\"series_id\"");
                    if (id <= 0) id = findLong(obj, "\"season_id\"");
                    String title = findString(obj, "title");
                    String cover = findString(obj, "cover");
                    int total = findInt(obj, "\"total\"");
                    if (id > 0) result.add(new CollectionItem(id, title != null ? title : "合集", cover, total));
                    objStart = -1;
                }
            }
        }
        return result;
    }

    private static List<FavVideo> fetchCollectionArchives(long seriesId) {
        List<FavVideo> result = new ArrayList<>();
        int pn = 1;
        while (true) {
            String json = get("https://api.bilibili.com/x/series/archives?series_id=" + seriesId + "&pn=" + pn + "&ps=30", cookie());
            if (!json.contains("\"code\":0")) { System.out.println("  [合集内容] 请求失败，返回：" + json.substring(0, Math.min(200, json.length()))); break; }

            List<FavVideo> page = parseArchives(json);
            result.addAll(page);

            int total = findInt(json, "\"total\"");
            if (result.size() >= total || page.isEmpty()) break;
            pn++;
        }
        return result;
    }

    private static List<FavVideo> parseArchives(String json) {
        List<FavVideo> result = new ArrayList<>();
        int arrIdx = json.indexOf("\"archives\"");
        if (arrIdx < 0) arrIdx = json.indexOf("\"list\"");
        if (arrIdx < 0) return result;
        int arrStart = json.indexOf('[', arrIdx);
        if (arrStart < 0) return result;

        int depth = 0, objStart = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
            else if (c == '{') { if (objStart < 0) objStart = i; depth++; }
            else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 1) {
                    String obj = json.substring(objStart, i + 1);
                    String bvid = findString(obj, "bvid");
                    String title = findString(obj, "title");
                    if (bvid != null && !bvid.isEmpty()) {
                        int duration = findInt(obj, "\"duration\"");
                        String upName = "";
                        int ownerIdx = obj.indexOf("\"owner\"");
                        if (ownerIdx >= 0) upName = findString(obj.substring(ownerIdx), "\"name\"");
                        if (title == null) title = bvid;
                        if (upName == null) upName = "";
                        result.add(new FavVideo(bvid, title, upName, duration));
                    }
                    objStart = -1;
                }
            }
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private static String cookie() {
        return COOKIE;
    }

    private static String get(String urlStr, String cookie) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Origin", "https://www.bilibili.com");
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            conn.connect();

            int code = conn.getResponseCode();
            BufferedReader br = (code == 200)
                    ? new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))
                    : new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            System.out.println("  [HTTP] " + urlStr + " -> " + e.getMessage());
            return "{}";
        }
    }

    private static String findString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int quote = json.indexOf('"', colon);
        if (quote < 0) return null;
        int end = json.indexOf('"', quote + 1);
        if (end < 0) return null;
        String raw = json.substring(quote + 1, end);
        // 反转义
        return raw.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .replace("\\t", " ");
    }

    private static long findLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); }
        catch (Exception e) { return 0; }
    }

    private static int findInt(String json, String key) {
        return (int) findLong(json, key);
    }
}
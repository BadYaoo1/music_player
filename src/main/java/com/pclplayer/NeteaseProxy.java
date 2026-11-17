import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/**
 * 网易云代理封装（修复 songId=0 + 不回退无效外链）
 * 代理: npx @neteasecloudmusicapienhanced/api@latest  (端口 3000)
 */
public class NeteaseProxy {

    static final String PROXY = System.getProperty("netease.proxy", "http://localhost:3000");

    static final String SEARCH_API   = PROXY + "/search?type=1&limit=1&keywords=";
    static final String LYRIC_API    = PROXY + "/lyric?id=";
    static final String SONG_URL_API = PROXY + "/song/url?id=";
    static final String CHECK_API    = PROXY + "/check/music?id=";

    static HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(java.time.Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    static String httpGetOrNull(String url) {
        try { return httpGet(url); } catch (Exception e) { return null; }
    }

    // ★ 修复：优先 privilege.id，兜底排除 album/artist id
    public static String searchSongId(String keyword) {
        try {
            String url = SEARCH_API + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String body = httpGet(url);
            if (body == null) return null;
            int songsIdx = body.indexOf("\"songs\":[");
            if (songsIdx < 0) return null;
            String fromSongs = body.substring(songsIdx);

            Matcher mPriv = Pattern.compile("\"privilege\":\\{\\s*\"id\":(\\d+)", Pattern.DOTALL).matcher(fromSongs);
            if (mPriv.find()) return mPriv.group(1);

            Matcher mSong = Pattern.compile("\"name\":\"[^\"]*\"\\s*,\\s*\"id\":(\\d+),\\s*\"mark\"", Pattern.DOTALL).matcher(fromSongs);
            if (mSong.find()) return mSong.group(1);

            Matcher mAny = Pattern.compile("\"id\":(\\d+)", Pattern.DOTALL).matcher(fromSongs);
            while (mAny.find()) {
                String id = mAny.group(1);
                if (!"0".equals(id) && !"81098777".equals(id) && !"64147".equals(id)) return id;
            }
        } catch (Exception e) {
            System.out.println("  ⚠ searchSongId: " + e.getMessage());
        }
        return null;
    }

    public static String fetchLrc(long songId) {
        String body = httpGetOrNull(LYRIC_API + songId);
        if (body == null) return null;
        return extractLrc(body);
    }

    static String extractLrc(String json) {
        int dataIdx = json.indexOf("\"data\":{");
        if (dataIdx >= 0) {
            String fromData = json.substring(dataIdx);
            int contentIdx = fromData.indexOf("\"content\":");
            if (contentIdx >= 0) {
                int colon = fromData.indexOf(':', contentIdx);
                int start = fromData.indexOf('"', colon) + 1;
                if (start > 0) return parseEscaped(fromData, start);
            }
        }
        int lrcIdx = json.indexOf("\"lrc\"");
        if (lrcIdx < 0) return null;
        int lyricIdx = json.indexOf("\"lyric\"", lrcIdx);
        if (lyricIdx < 0) return null;
        int colon = json.indexOf(':', lyricIdx);
        int start = json.indexOf('"', colon) + 1;
        if (start <= 0) return null;
        return parseEscaped(json, start);
    }

    static String parseEscaped(String source, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                char nx = source.charAt(i + 1);
                switch (nx) {
                    case 'n' -> sb.append('\n');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    default -> sb.append(nx);
                }
                i++;
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    // ★ 返回 null 而不是无效外链
    public static String fetchSongUrl(long songId) {
        String body = httpGetOrNull(SONG_URL_API + songId);
        if (body != null) {
            int dataIdx = body.indexOf("\"data\":[{");
            if (dataIdx >= 0) {
                String fromData = body.substring(dataIdx);
                Matcher m = Pattern.compile("\"url\":\"([^\"]*)\"").matcher(fromData);
                if (m.find()) {
                    String url = m.group(1);
                    if (url != null && !url.isEmpty() && !url.equals("null")) return url;
                }
            }
        }
        return null;
    }

    public static boolean checkAvailable(long songId) {
        String body = httpGetOrNull(CHECK_API + songId);
        if (body == null) return false;
        return body.contains("\"success\":true");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.out.println("用法: java NeteaseProxy <歌名>"); return; }
        String keyword = String.join(" ", args);
        System.out.println("🔍 搜索: " + keyword);
        String songId = searchSongId(keyword);
        if (songId == null) { System.out.println("❌ 未搜索到"); return; }
        System.out.println("  → songId: " + songId);
        System.out.println("  → 可播放: " + checkAvailable(Long.parseLong(songId)));
        String lrc = fetchLrc(Long.parseLong(songId));
        if (lrc != null && !lrc.trim().isEmpty()) {
            System.out.println("  → 歌词前 3 行:");
            int c = 0;
            for (String line : lrc.split("\n")) {
                if (!line.trim().isEmpty()) { System.out.println("     " + line); if (++c >= 3) break; }
            }
        } else System.out.println("  ❌ 歌词为空");
        System.out.println("  → 播放地址: " + fetchSongUrl(Long.parseLong(songId)));
    }
}
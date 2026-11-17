package com.pclplayer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云歌词：直连 music.163.com 官方 Web API，无需本地 Node 代理（localhost:3000）。
 * 自包含极简 JSON 解析，无第三方依赖。
 * 改编自单文件版 NeteaseLyrics，去掉 HTTP 服务层，作为项目内工具类使用。
 */
public final class NeteaseLyrics {
    private static final String SEARCH_ENDPOINT = "https://music.163.com/api/cloudsearch/pc";
    private static final String LYRIC_ENDPOINT = "https://music.163.com/api/song/lyric";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 一首歌的元数据 */
    public static final class Song {
        public final String id, name, artists, album;
        Song(String id, String name, String artists, String album) {
            this.id = id; this.name = name; this.artists = artists; this.album = album;
        }
    }

    /** 搜索歌曲，返回前 limit 首。失败返回空列表。 */
    public static List<Song> search(String keyword, int limit) {
        List<Song> out = new ArrayList<>();
        try {
            String url = SEARCH_ENDPOINT + "?s=" + enc(keyword) + "&type=1&limit=" + Math.max(1, limit) + "&offset=0";
            Map<String, Object> root = getJson(url);
            List<Object> songs = arr(obj(root.get("result")).get("songs"));
            for (Object o : songs) {
                Map<String, Object> song = obj(o);
                long id = asLong(song.get("id"));
                if (id <= 0) continue;
                StringBuilder artistNames = new StringBuilder();
                Object artistNodes = song.get("ar") != null ? song.get("ar") : song.get("artists");
                for (Object node : arr(artistNodes)) {
                    String n = str(obj(node).get("name"));
                    if (!n.isEmpty()) { if (artistNames.length() > 0) artistNames.append(" / "); artistNames.append(n); }
                }
                Object albumNode = song.get("al") != null ? song.get("al") : song.get("album");
                out.add(new Song(String.valueOf(id), str(song.get("name")),
                        artistNames.toString(), str(obj(albumNode).get("name"))));
                if (out.size() >= limit) break;
            }
        } catch (Exception e) {
            // 交由调用方按空结果处理
        }
        return out;
    }

    /** 取某首歌的原始 LRC 歌词。无歌词或失败返回 null。 */
    public static String fetchLyric(String songId) {
        try {
            Map<String, Object> root = getJson(LYRIC_ENDPOINT + "?id=" + songId + "&lv=1&kv=1&tv=-1");
            if (Boolean.TRUE.equals(root.get("nolyric")) || Boolean.TRUE.equals(root.get("uncollected"))) return null;
            String lrc = str(obj(root.get("lrc")).get("lyric"));
            return lrc.trim().isEmpty() ? null : lrc;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- HTTP / JSON ----------

    private static Map<String, Object> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("请求网易云超时", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接网易云", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("网易云返回 HTTP " + response.statusCode());
        }
        Map<String, Object> root;
        try {
            root = obj(parseJson(response.body()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法解析网易云响应", e);
        }
        if (root.containsKey("code") && asLong(root.get("code")) != 200) {
            throw new IllegalStateException("网易云返回业务错误码 " + (long) asLong(root.get("code")));
        }
        return root;
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static Object parseJson(String text) {
        JsonReader reader = new JsonReader(text);
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != text.length()) throw new IllegalArgumentException("非法 JSON：多余字符");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }
    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }
    private static String str(Object value) { return value instanceof String ? (String) value : ""; }
    private static long asLong(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }

    private static final class JsonReader {
        private final String text;
        private int pos;
        JsonReader(String text) { this.text = text; }

        Object readValue() {
            skipWhitespace();
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }
        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; skipWhitespace();
            if (text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                pos++; // ':'
                map.put(key, readValue());
                skipWhitespace();
                char c = text.charAt(pos++);
                if (c == '}') return map;
            }
        }
        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; skipWhitespace();
            if (text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = text.charAt(pos++);
                if (c == ']') return list;
            }
        }
        private String readString() {
            if (text.charAt(pos) != '"') throw new IllegalArgumentException("期望字符串");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> { sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> throw new IllegalArgumentException("非法转义字符");
                }
            }
        }
        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) throw new IllegalArgumentException("非法字面量");
            pos += literal.length();
            return value;
        }
        private Object readNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            String number = text.substring(start, pos);
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0)
                return Long.parseLong(number);
            return Double.parseDouble(number);
        }
        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }
    }
}

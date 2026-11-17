package com.pclplayer;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放页歌词视图（普通垂直滚动，无伪 3D）
 *
 * <p>从 {@link LyricRendererFX} 移植而来，沿用它的滚动缓动、节点池复用、
 * start/pause/stop 状态机等逻辑，但去掉了伪 3D 相关部分：
 * <ul>
 *   <li>去掉 rotate（绕 Z 轴倾斜）</li>
 *   <li>去掉 scale3D（按距离缩小）</li>
 *   <li>去掉 GaussianBlur 与光晕缓存（同时也是内存增长源）</li>
 *   <li>去掉 TOP_X/BOTTOM_X 水平错位</li>
 * </ul>
 * 渲染改为：等行距纵向排布，当前行居中并高亮，其余行按距离渐隐。
 *
 * <p><b>与 LyricRendererFX 最重要的区别：时间来自外部，而非自己累加。</b>
 * 原实现在 AnimationTimer 里自己累加计时，而音频是独立播放的（且下载是异步的），
 * 导致歌词永远比音频快几秒且持续漂移。这里改由外部通过 {@link #update(double)}
 * 传入真实播放进度，从根本上保证同步；内部 AnimationTimer 只负责滚动插值的渲染。
 *
 * <p>用法（注意 onProgress 回调给的是<b>毫秒</b>）：
 * <pre>{@code
 * LyricView lyricView = new LyricView();
 * lyricView.loadLyric("BV1xx411c7mD");        // 异步拉取并解析
 * lyricView.start();
 * // 在 BiliAudioPlayer 的 onProgress(currentMs, totalMs) 里驱动
 * lyricView.updateMillis(currentMs);
 * }</pre>
 */
public class LyricView extends Pane {

    // ===================== 数据模型 =====================
    public static class LyricLine {
        public final String text;
        public double start;
        public double end;

        public LyricLine(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    // ===================== 常量 =====================
    /** 节点池大小：同屏最多可见行数（歌词再多也只渲染可视区，避免建大量节点） */
    private static final int POOL_SIZE = 40;
    /** 滚动缓动速度：越大越慢（沿用 LyricRendererFX 的手感） */
    private static final double SCROLL_SPEED = 6.0;
    /** 非当前行的最低不透明度 */
    private static final double MIN_OPACITY = 0.28;
    /** 每行距离衰减的不透明度 */
    private static final double OPACITY_FALLOFF = 0.16;
    /** 歌词左右内边距：避免文字贴边被裁，也作为单行自适应字号的安全区 */
    private static final double LYRIC_PAD = 16;
    /** 歌词字体（默认族，分中英文由 fontCN/fontEN 决定） */
    private static final String FONT_FAMILY = "Microsoft YaHei";
    private static final int DEFAULT_FONT_SIZE = 14;
    /** 中/英文歌词字体族：含汉字用 fontCN，否则用 fontEN */
    private String fontCN = FONT_FAMILY;
    private String fontEN = FONT_FAMILY;
    /** 是否加粗：开启后普通行也用粗体（当前行始终加粗），避免细体看不清 */
    private boolean bold = true;
    /** 行距相对字号的倍数：字号变化时行距同步缩放，否则大字号会行叠行 */
    private static final double ROW_HEIGHT_RATIO = 34.0 / DEFAULT_FONT_SIZE;

    private static final Color DEFAULT_COLOR_ACTIVE = Color.web("#1976D2");
    private static final Color COLOR_NORMAL = Color.web("#6B7280");

    /** 歌词持久化目录：同一首歌一个 JSON，便于保存偏移/换源/导入结果 */
    private static final Path LYRIC_DIR = Paths.get("lyrics");

    // ===================== 可设置样式 =====================
    private int fontSize = DEFAULT_FONT_SIZE;
    private double rowHeight = DEFAULT_FONT_SIZE * ROW_HEIGHT_RATIO;
    private Color colorActive = DEFAULT_COLOR_ACTIVE;

    // ===================== 状态 =====================
    private enum State { IDLE, PLAYING, PAUSED }

    private final List<LyricLine> lines = new ArrayList<>();
    private final List<Label> pool = new ArrayList<>();
    private Label statusLabel;
    private Consumer<Boolean> onLoadResult = null;
    /** 是否有歌词可显示，供外部（如播放页空态提示）绑定 */
    private final BooleanProperty hasLyric = new SimpleBooleanProperty(false);

    private volatile State state = State.IDLE;

    /** 当前滚动位置（行号，浮点，用于插值）；目标位置同样以行号为单位 */
    private double currentPos = 0;
    private double targetPos = 0;
    /** 当前高亮行索引，-1 表示无 */
    private int activeIndex = -1;

    private AnimationTimer timer;
    private String pendingInput = null;   // 加载未完成时若又换了歌，记住最后一次请求

    /** 当前歌词持久化文件路径（来自 loadLyric 的 query，用于保存偏移/换源/导入结果） */
    private Path currentSavePath = null;
    /** 累计时间偏移（秒）：烘焙进歌词时间戳的同时单独记录，便于回显“当前偏移” */
    private double appliedOffset = 0;
    private double lastTimeMs = 0;   // 最近一次播放进度（毫秒），偏移后据此立即重新定位
    private boolean suppressFollow = false;   // 切歌回顶动画期间忽略进度驱动，避免旧歌词被新歌时间拖拽错位
    private AnimationTimer resetAnim = null;  // 切歌回顶动画（从当前位置缓出到顶部）

    /** 裁剪矩形，始终等于当前实际尺寸（在 layoutChildren 里同步） */
    private final Rectangle clipRect = new Rectangle();

    // 临时量尺：复用一个 Label 量文字真实宽度；绝不能对其 setPrefWidth，
    // 否则 prefWidth(-1) 会回返设置值而非自然宽度，导致超宽行不缩放
    private static final Label MEASURE_LABEL = new Label();

    // ===================== 构造 =====================
    public LyricView() {
        setBackground(null);
        setMouseTransparent(true);

        // 裁剪到自身范围，避免溢出的歌词行画到画布外（比如盖住顶部导航栏）。
        // 尺寸在 layoutChildren 里同步，初始化阶段宽高还是 0 也不会出问题
        setClip(clipRect);

        for (int i = 0; i < POOL_SIZE; i++) {
            Label lb = new Label();
            lb.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, fontSize));
            lb.setTextFill(COLOR_NORMAL);
            // 必须单行 + 省略号：若允许换行，长歌词会撑高 Label 超过 ROW_HEIGHT 导致行与行重叠
            lb.setWrapText(false);
            lb.setTextOverrun(OverrunStyle.ELLIPSIS);
            lb.setAlignment(Pos.CENTER);
            lb.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            // 宽度在 renderFrame 中按当前画布宽度设置（与裁剪框同源），保证缩放时不被裁掉一半
            lb.setMaxWidth(Double.MAX_VALUE);
            lb.setVisible(false);
            lb.setMouseTransparent(true);
            pool.add(lb);
            getChildren().add(lb);
        }

        // 宽度/高度变化时立即重排：
        // 否则暂停或拖动窗口缩放时（AnimationTimer 没在跑），歌词不会跟着重新定位，缩小后会错位。
        widthProperty().addListener((obs, o, n) -> renderFrame());
        heightProperty().addListener((obs, o, n) -> renderFrame());
        // 位置/尺寸变化时无需父类默认布局来摆放歌词节点（renderFrame 已完全接管），
        // 否则 Pane 会把标签按各自 pref 尺寸重新摆放，覆盖 layoutX/layoutY，
        // 导致歌词被推到右半侧、右侧被截断。这里只补一次重排。
        layoutBoundsProperty().addListener((obs, o, n) -> renderFrame());

        // 状态提示（加载中 / 暂无歌词）。歌词为空时显示，避免留一个空白框
        statusLabel = new Label();
        statusLabel.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, 13));
        statusLabel.setTextFill(COLOR_NORMAL);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setVisible(false);
        statusLabel.setMouseTransparent(true);
        getChildren().add(statusLabel);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (state == State.PLAYING) renderFrame();
            }
        };
    }

    /**
     * 设置歌词加载结果回调（在 JavaFX 线程调用）。
     * true = 拿到歌词，false = 没有歌词（调用方可据此回退显示封面）。
     */
    public void setOnLoadResult(Consumer<Boolean> cb) {
        this.onLoadResult = cb;
    }

    /** 设置歌词字号（同时按固定倍率缩放行距） */
    public void setFontSize(int size) {
        int s = Math.max(8, Math.min(48, size));
        if (s == fontSize) return;
        fontSize = s;
        rowHeight = s * ROW_HEIGHT_RATIO;
        FontWeight fw = bold ? FontWeight.BOLD : FontWeight.NORMAL;
        for (Label lb : pool) lb.setFont(PlayerApp.resolveLyricFont(fontCN, fw, fontSize));
        renderFrame();
    }

    public int getFontSize() { return fontSize; }

    /** 设置中/英文歌词字体族（含汉字用 cn，英文/拉丁用 en） */
    public void setFontFamilies(String cn, String en) {
        fontCN = (cn == null || cn.isEmpty()) ? FONT_FAMILY : cn;
        fontEN = (en == null || en.isEmpty()) ? FONT_FAMILY : en;
        renderFrame();
    }

    /** 设置是否加粗：开启后普通行也用粗体（当前行始终加粗） */
    public void setBold(boolean b) {
        if (b == bold) return;
        bold = b;
        renderFrame();
    }

    /** 是否含汉字（中文规则）：含汉字 → 用中文字体，否则用英文字体 */
    private static boolean containsHan(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x3400 && cp <= 0x9FFF) return true;   // 汉字（含扩展A）
        }
        return false;
    }

    /** 设置当前行高亮色 */
    public void setHighlightColor(Color color) {
        if (color == null || color.equals(colorActive)) return;
        colorActive = color;
        renderFrame();
    }

    public Color getHighlightColor() { return colorActive; }

    // ===================== 对外接口 =====================

    /** 直接设置歌词数据（同步）。会重置滚动位置。 */
    public void setLyric(List<LyricLine> newLines) {
        pendingInput = null;   // 作废进行中的异步加载，避免稍后覆盖本次结果
        lines.clear();
        if (newLines != null) lines.addAll(newLines);
        normalizeLines();
        currentPos = targetPos = 0;
        activeIndex = -1;
        setStatus(null);
        hasLyric.set(!lines.isEmpty());
        Platform.runLater(this::renderFrame);
    }

    public boolean hasLyric() {
        return !lines.isEmpty();
    }

    public BooleanProperty hasLyricProperty() {
        return hasLyric;
    }

    /** 当前已加载的歌词行（副本，避免外部修改内部状态） */
    public List<LyricLine> getLines() {
        return new ArrayList<>(lines);
    }

    /** 异步加载歌词：input 可以是 BV 号、av 号或歌名（使用默认来源顺序） */
    public void loadLyric(String input) {
        loadLyric(input, null);
    }

    /**
     * 异步加载歌词，并指定来源优先级。
     *
     * @param sourceOrder 平台名序列（qq / netease / kugou），传递给 BiliUidLyricService 按序尝试；
     *                     为 null 时使用其默认顺序
     */
    public void loadLyric(String input, java.util.List<String> sourceOrder) {
        loadLyric(input, sourceOrder, false);
    }

    /**
     * 加载歌词。优先使用已持久化的歌词（保留用户之前做的偏移/换源/导入结果）；
     * 仅当没有缓存或 forceRefetch=true 时才重新拉取网络。
     */
    public void loadLyric(String input, java.util.List<String> sourceOrder, boolean forceRefetch) {
        if (input == null || input.trim().isEmpty()) return;
        final String key = input.trim();
        final java.util.List<String> order = sourceOrder;
        pendingInput = key;

        // 计算持久化路径（同一首歌 = 同一个文件，便于直接保存完整歌词）
        // 注意：path 只能赋值一次，才能在下方 lambda 中捕获（effectively final）
        final Path path = lyricSavePath(key);
        currentSavePath = path;
        // 累计偏移跟随这首歌：强制重新拉取会丢掉用户偏移（文件被覆盖），故归零；
        // 否则从伴随文件读回已记录的偏移，保证“当前偏移”回显正确。
        if (forceRefetch) {
            appliedOffset = 0;
            writeOffsetMeta(path, 0);
        } else {
            appliedOffset = readOffsetMeta(path);
        }

        // 命中缓存且非强制刷新 -> 直接加载本地文件（保留用户编辑结果）
        if (!forceRefetch && Files.exists(path)) {
            List<LyricLine> cached = parseLyricFile(path);
            if (cached != null) {
                final List<LyricLine> res = cached;
                Platform.runLater(() -> {
                    if (!key.equals(pendingInput)) return;
                    fillLyric(res, true);
                });
                return;
            }
        }

        // 拉取前先清空旧歌词并提示加载中，避免短暂显示上一首的歌词
        Platform.runLater(() -> {
            lines.clear();
            hasLyric.set(false);
            activeIndex = -1;
            setStatus("歌词加载中...");
            renderFrame();
        });

        new Thread(() -> {
            List<LyricLine> parsed = null;
            try {
                int rows = BiliUidLyricService.fetchAndSave(key, path.toString(), order);
                if (rows > 0) parsed = parseLyricFile(path);
            } catch (Exception e) {
                System.out.println("[lyric] 拉取歌词失败: " + e.getMessage());
            }

            final List<LyricLine> result = parsed;
            // 期间用户又切了歌则丢弃这次结果
            if (!key.equals(pendingInput)) return;

            Platform.runLater(() -> fillLyric(result, true));
        }).start();
    }

    /** 强制重新从网络拉取歌词（覆盖本地已保存的偏移/换源/导入结果） */
    public void reloadLyric() {
        if (pendingInput != null) loadLyric(pendingInput, null, true);
    }

    /** 时间偏移：整体向前（deltaMs<0）或向后（deltaMs>0）移动，并直接保存到本地完整歌词 */
    public void applyOffset(long deltaMs) {
        if (currentSavePath == null || lines.isEmpty()) return;
        double d = deltaMs / 1000.0;
        for (LyricLine l : lines) { l.start += d; l.end += d; }
        normalizeLines();
        // 时间戳烘焙进完整歌词文件；累计偏移单独记入伴随文件，便于回显“当前偏移”
        writeLyricFile(currentSavePath, lines);
        appliedOffset += d;
        writeOffsetMeta(currentSavePath, appliedOffset);
        // 仅更改时间戳并保存到本地完整歌词；不移动/滚动歌词显示（保持原样）。
    }

    /** 取累计偏移（秒），用于面板回显 */
    public double getAppliedOffset() {
        return appliedOffset;
    }

    /** 把累计偏移写入伴随文件 <歌词文件>.offset（纯文本，单位秒） */
    private void writeOffsetMeta(Path lyricPath, double offset) {
        try {
            Path meta = Paths.get(lyricPath + ".offset");
            Files.writeString(meta, String.valueOf(offset), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[lyric] 写入偏移记录失败: " + e.getMessage());
        }
    }

    /** 从伴随文件读回累计偏移；不存在或解析失败则返回 0 */
    private double readOffsetMeta(Path lyricPath) {
        try {
            Path meta = Paths.get(lyricPath + ".offset");
            if (!Files.exists(meta)) return 0;
            String v = Files.readString(meta, StandardCharsets.UTF_8).trim();
            return v.isEmpty() ? 0 : Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 设置并保存一份完整歌词（来自换源选择或导入文件），直接覆盖本地文件 */
    public void saveLines(java.util.List<LyricLine> newLines) {
        if (currentSavePath == null) return;
        lines.clear();
        if (newLines != null) lines.addAll(newLines);
        normalizeLines();
        writeLyricFile(currentSavePath, lines);
        currentPos = targetPos = 0;
        activeIndex = -1;
        hasLyric.set(!lines.isEmpty());
        Platform.runLater(this::renderFrame);
    }

    /** 导入 LRC 文本作为当前歌词并保存 */
    public void setLyricFromLrc(String lrcText) {
        if (currentSavePath == null) return;
        List<LyricLine> parsed = parseLrcText(lrcText);
        if (parsed == null || parsed.isEmpty()) return;
        saveLines(parsed);
    }

    public java.util.List<LyricLine> getCurrentLines() {
        return new java.util.ArrayList<>(lines);
    }

    /** 设置状态提示；传 null 则隐藏 */
    private void setStatus(String text) {
        if (statusLabel == null) return;
        statusLabel.setText(text == null ? "" : text);
        statusLabel.setVisible(text != null);
    }

    public void start() {
        state = State.PLAYING;
        timer.start();
    }

    public void pause() {
        state = State.PAUSED;
    }

    public void stop() {
        state = State.IDLE;
        timer.stop();
        if (resetAnim != null) { resetAnim.stop(); resetAnim = null; }
        suppressFollow = false;
        currentPos = targetPos = 0;
        activeIndex = -1;
        // 停止播放时若有未完成的状态提示（如"歌词加载中"），不要留着
        if (lines.isEmpty()) setStatus(null);
        Platform.runLater(this::renderFrame);
    }

    /**
     * 统一填充歌词数据并定位：换歌（之前已有歌词在显示）时，
     * 新歌词先停在最底部、再向上缓动滚到当前行（"下一首从最底下往上"的转场）；
     * 首次加载（之前无歌词）则直接定位，无动画。
     */
    private void fillLyric(List<LyricLine> res, boolean ok) {
        boolean wasSwitching = !lines.isEmpty();   // 填充前是否已有歌词在显示（即"换歌"）
        lines.clear();
        if (res != null) lines.addAll(res);
        normalizeLines();
        activeIndex = -1;
        hasLyric.set(!lines.isEmpty());
        setStatus(ok && lines.isEmpty() ? "暂无歌词" : null);
        currentPos = targetPos = 0;
        if (wasSwitching && !lines.isEmpty()) {
            enterFromBottom();          // 换歌：新歌词从最底部向上滚入
        } else {
            renderFrame();
        }
        if (onLoadResult != null) onLoadResult.accept(!lines.isEmpty());
    }

    /**
     * 换歌转场：新歌词从最底部（最后一行居中，即整段歌词在视口最下方）
     * 向上缓动滚到当前播放行（约 400ms 缓出）。动画期间忽略进度驱动，
     * 避免新歌时间把位置提前拖走。
     */
    private void enterFromBottom() {
        if (resetAnim != null) { resetAnim.stop(); resetAnim = null; }
        final double to = targetPos;                       // 当前应定位的行
        final double from = Math.max(0, lines.size() - 1); // 最底行居中
        if (from <= to + 0.001) { currentPos = to; renderFrame(); return; }
        currentPos = from;
        suppressFollow = true;
        final long startN = System.nanoTime();
        final double dur = 0.4e9;                          // 400ms
        resetAnim = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = (now - startN) / dur;
                if (t > 1) t = 1;
                double e = 1 - (1 - t) * (1 - t);           // ease-out
                currentPos = from + (to - from) * e;
                renderFrame();
                if (t >= 1) {
                    stop();
                    resetAnim = null;
                    suppressFollow = false;
                    currentPos = targetPos;
                    renderFrame();
                }
            }
        };
        resetAnim.start();
    }

    /**
     * 由外部播放器驱动进度（<b>毫秒</b>，与 {@code PlayCallback.onProgress} 的单位一致，
     * 直接透传即可，避免调用方漏做单位换算）。
     *
     * <p>这是保证音画同步的关键：时间不在这里累加，而是直接用音频的真实播放位置。
     * 可以高频调用，内部只在行号变化时才更新目标位置。
     */
    public void updateMillis(double currentMs) {
        if (lines.isEmpty()) return;
        if (suppressFollow) return;   // 回顶动画期间忽略进度，避免旧歌词错位
        lastTimeMs = currentMs;   // 记录最近一次进度
        int idx = findLineIndex(currentMs / 1000.0);
        if (idx == activeIndex) return;

        activeIndex = idx;
        targetPos = idx;
        // 拖动进度条等造成的大跨度跳转：直接定位，不做缓动
        if (Math.abs(targetPos - currentPos) > 5) currentPos = targetPos;

        // 兜底：若 AnimationTimer 没在跑（比如播放器的暂停/恢复回调缺失导致 start() 没被调用），
        // 这里直接渲染一次，保证歌词至少能跟着进度走，不至于完全静止。
        // 只在换行时才会走到这里，开销可忽略。
        if (state != State.PLAYING) {
            Platform.runLater(this::renderFrame);
        }
    }

    /** 释放资源 */
    public void dispose() {
        state = State.IDLE;
        if (timer != null) timer.stop();
        lines.clear();
    }

    // ===================== 歌词解析 =====================

    /** 解析 BiliUidLyricService 输出的 JSON 数组 */
    /** 生成持久化文件名（去掉文件系统非法字符） */
    private static String safeName(String key) {
        return key.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 计算本首歌的持久化路径：优先 lyrics/ 目录，失败则退到临时目录（只返回一次，保证 effectively final） */
    private Path lyricSavePath(String key) {
        try {
            Files.createDirectories(LYRIC_DIR);
            return LYRIC_DIR.resolve(safeName(key) + ".json");
        } catch (IOException e) {
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir", "."));
            try { Files.createDirectories(tmp); } catch (Exception ignored) {}
            return tmp.resolve("plain_bili_player_lyric_" + (key.hashCode() & Integer.MAX_VALUE) + ".json");
        }
    }

    /** 把歌词行写回 JSON 文件（与 BiliUidLyricService 输出同源格式，便于后续读取） */
    private void writeLyricFile(Path path, List<LyricLine> ls) {
        try {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < ls.size(); i++) {
                LyricLine l = ls.get(i);
                sb.append("  {\"text\":\"").append(escapeJson(l.text))
                        .append("\",\"start_ms\":\"").append((long) (l.start * 1000))
                        .append("\",\"end_ms\":\"").append((long) (l.end * 1000)).append("\"}");
                if (i < ls.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[lyric] 写入歌词失败: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    /** 解析 LRC 文本为歌词行（用于导入本地歌词文件） */
    private List<LyricLine> parseLrcText(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return null;
        List<LyricLine> out = new ArrayList<>();
        Pattern timePattern = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
        for (String raw : lrc.split("\n")) {
            raw = raw.replace("\r", "").trim();
            if (raw.isEmpty()) continue;
            Matcher tm = timePattern.matcher(raw);
            if (!tm.find()) continue;
            int min = Integer.parseInt(tm.group(1));
            double sec = Double.parseDouble(tm.group(2));
            double start = min * 60 + sec;
            String text = raw.replaceAll("\\[\\d+:\\d+(?:\\.\\d+)?\\]", "").trim();
            if (text.isEmpty()) continue;
            out.add(new LyricLine(text, start, start + 3.0));
        }
        for (int i = 0; i < out.size() - 1; i++) out.get(i).end = out.get(i + 1).start;
        if (!out.isEmpty()) out.get(out.size() - 1).end = out.get(out.size() - 1).start + 5.0;
        return out.isEmpty() ? null : out;
    }

    private List<LyricLine> parseLyricFile(Path jsonPath) {
        List<LyricLine> tmp = new ArrayList<>();
        try {
            String content = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\\{([^}]*)\\}").matcher(content);
            while (m.find()) {
                String obj = m.group(1);
                String t = field(obj, "text");
                String s = field(obj, "start_ms");
                String e = field(obj, "end_ms");
                if (t == null || s == null || e == null) continue;
                t = t.trim();
                if (t.isEmpty()) continue;
                double st, en;
                try {
                    st = Double.parseDouble(s) / 1000.0;
                    en = Double.parseDouble(e) / 1000.0;
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (en <= st) en = st + 3.0;
                tmp.add(new LyricLine(t, st, en));
            }
        } catch (Exception e) {
            System.out.println("[lyric] 解析歌词文件失败: " + e.getMessage());
            return null;
        }
        return tmp.isEmpty() ? null : tmp;
    }

    /** 修正行与行之间的时间重叠，避免两行同时命中 */
    private void normalizeLines() {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).end > lines.get(i + 1).start) {
                lines.get(i).end = lines.get(i + 1).start;
            }
        }
        if (!lines.isEmpty()) {
            LyricLine last = lines.get(lines.size() - 1);
            last.end = Math.max(last.end, last.start + 3.0);
        }
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    // ===================== 渲染 =====================

    /**
     * 查找当前时间对应的行，沿用 LyricRendererFX 的策略：
     * 先找命中的时间区间，找不到（落在两行之间的空档）就返回下一行。
     *
     * <p>刻意用线性扫描而非二分：二分要求 start 严格递增，
     * 而歌词来源不可控（个别源可能乱序），乱序时二分会返回错误行导致歌词乱跳。
     * 这里只在切行时才走到，歌词几百行的量级下开销可忽略，稳健性优先。
     */
    private int findLineIndex(double t) {
        for (int i = 0; i < lines.size(); i++) {
            LyricLine ln = lines.get(i);
            if (ln.start <= t && t < ln.end) return i;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (t < lines.get(i).start) return i;
        }
        return lines.size() - 1;
    }

    /** 状态提示居中显示 */
    private void layoutStatusLabel() {
        if (statusLabel == null) return;
        double w = getWidth();
        double h = getHeight();
        statusLabel.setPrefWidth(Math.max(10, w - 24));
        statusLabel.setLayoutX(12);
        statusLabel.setLayoutY(Math.max(0, h / 2.0 - 12));
    }

    /** 上次布局提交时的尺寸，用于判断是否需要补一次重排（避免缩放时歌词被裁掉） */
    private double lastLayoutW = -1, lastLayoutH = -1;

    /** 每次布局后把裁剪矩形同步为当前实际尺寸（上方多留一个行高，避免顶部歌词被切） */
    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        clipRect.setX(0);
        clipRect.setY(-rowHeight);
        clipRect.setWidth(w);
        clipRect.setHeight(h + rowHeight);

        // 先把歌词行/状态提示的宽度设成当前画布宽度（与裁剪框同源，缩放时不被截），
        // 再交给父类做布局（计算 Label 内容、尺寸），最后用 renderFrame 覆盖各行位置。
        // 文字宽度留出左右内边距，避免贴着蓝色框边缘被裁；居中显示时仍有安全边距
        double lbW = Math.max(10, w - 2 * LYRIC_PAD);
        for (Label lb : pool) {
            lb.setPrefWidth(lbW);
            lb.setMaxWidth(lbW);
        }
        statusLabel.setPrefWidth(Math.max(10, w - 24));

        // 必须调用 super：否则 Pane 不给 Label 做布局，Label 内容/尺寸不会被计算，歌词整片空白。
        super.layoutChildren();

        // 尺寸提交后对齐一次歌词：消除拖动缩放时 clip 与歌词行定位的时序错位（缩窄被截一半）。
        // 仅当尺寸确实变化时才补，避免无谓重排。
        if (Math.abs(w - lastLayoutW) > 0.5 || Math.abs(h - lastLayoutH) > 0.5) {
            lastLayoutW = w;
            lastLayoutH = h;
        }
        renderFrame();
        layoutStatusLabel();
    }

    private void renderFrame() {
        int n = lines.size();
        double h = getHeight();
        if (n == 0 || h <= 0) {
            for (Label lb : pool) lb.setVisible(false);
            layoutStatusLabel();
            return;
        }
        // 有歌词时不需要状态提示
        setStatus(null);

        setMouseTransparent(true);

        // 缓动逼近目标行（沿用 LyricRendererFX 的插值手感）
        double diff = targetPos - currentPos;
        if (Math.abs(diff) < 0.001) currentPos = targetPos;
        else currentPos += diff * (1.0 / SCROLL_SPEED);

        // 当前行固定在垂直中心
        double centerY = h / 2.0;
        int minIdx = (int) Math.floor(currentPos - (centerY + rowHeight) / rowHeight);
        int maxIdx = (int) Math.ceil(currentPos + (centerY + rowHeight) / rowHeight);
        if (minIdx < 0) minIdx = 0;
        if (maxIdx > n - 1) maxIdx = n - 1;

        final double avail = Math.max(10, getWidth() - 2 * LYRIC_PAD);
        int p = 0;
        for (int i = minIdx; i <= maxIdx; i++) {
            if (p >= pool.size()) break;
            Label lb = pool.get(p++);
            LyricLine ln = lines.get(i);

            boolean isActive = (i == activeIndex);
            double dist = Math.abs(i - currentPos);
            lb.setOpacity(isActive ? 1.0 : Math.max(MIN_OPACITY, 1.0 - dist * OPACITY_FALLOFF));
            lb.setTextFill(isActive ? colorActive : COLOR_NORMAL);

            // 自适应字号：单行放不下时缩小该行字号，避免长歌词被左右裁切或溢出蓝框
            FontWeight fw = (isActive || bold) ? FontWeight.BOLD : FontWeight.NORMAL;
            String fam = containsHan(ln.text) ? fontCN : fontEN;
            Font base = PlayerApp.resolveLyricFont(fam, fw, fontSize);
            lb.setFont(base);
            // 用专用量尺 Label 量真实文字宽度（它从未被 setPrefWidth，故 prefWidth(-1) 回返自然宽度）
            MEASURE_LABEL.setText(ln.text);
            MEASURE_LABEL.setFont(base);
            double tw = MEASURE_LABEL.prefWidth(-1);
            double size = fontSize;
            if (tw > avail) size = Math.max(9, fontSize * (avail / tw));
            if (size != fontSize) lb.setFont(PlayerApp.resolveLyricFont(fam, fw, size));

            lb.setText(ln.text);
            // 行高固定，按与当前滚动位置的偏移摆放；标签宽度已在 layoutChildren 中留出左右内边距
            double y = centerY + (i - currentPos) * rowHeight;
            lb.setLayoutX(0);
            lb.setLayoutY(y - rowHeight / 2.0);
            lb.setVisible(true);
        }
        // 隐藏本次未用到的节点
        for (int k = p; k < pool.size(); k++) pool.get(k).setVisible(false);
    }
}

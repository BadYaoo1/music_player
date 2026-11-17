package com.pclplayer;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.SnapshotParameters;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.shape.StrokeType;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.Timeline;
import javafx.util.Duration;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;

/**
 * 歌词渲染器 + 自动化歌词获取 + B站音频播放（伪 3D 歌词预览）。
 *
 * <p>启动方式一（独立运行）：
 * <pre>{@code
 *   mvn javafx:run   （配合 $env:BILI_INPUT="告白气球" 或 "BV1GJ411x7h7"）
 * }</pre>
 *
 * <p>启动方式二（作为调用接口，从已运行的 JavaFX 应用里打开预览窗口）：
 * <pre>{@code
 *   // 1) 可选：先调整参数
 *   LyricRendererFX.setConfig(new LyricRendererFX.LyricConfig()
 *           .width(1280).height(720).highlight(Color.LIMEGREEN));
 *
 *   // 2) 打开预览窗口（可传入歌词，也可留空走环境变量 / 演示数据）
 *   LyricRendererFX.showPreview();
 *
 *   // 3) 播放器驱动进度（秒），或手动喂歌词
 *   LyricRendererFX.setProgress(currentSeconds);
 *   LyricRendererFX.setLyrics(List.of(
 *       new LyricRendererFX.LyricLine("第一句", 0, 3),
 *       new LyricRendererFX.LyricLine("第二句", 3, 6)));
 *
 *   // 4) 关闭
 *   LyricRendererFX.closePreview();
 * }</pre>
 *
 * 说明：本类采用<b>单例</b>模型（全局一份配置与一份窗口），与 {@code getConfig()/setConfig()}
 * 配合即可从外部完全控制渲染效果；打开的窗口由 {@link #showPreview()} 系列方法管理。
 */
public class LyricRendererFX extends Application {

    // ===================== 可调参数（配置对象） =====================
    /**
     * 歌词渲染器的全部可调参数。默认值与原版完全一致；
     * 可用链式 {@code withXxx(...)} 或直接修改字段进行调整，所有参数均实时生效。
     */
    public static final class LyricConfig {
        public int width = 1920;
        public int height = 1080;
        public Color highlight = Color.rgb(0, 220, 255);
        public Color dimmed = Color.rgb(150, 150, 155);
        public int dimmedAlpha = 120;
        public double blurMax = 10.0;
        /** 上下高斯模糊开关：关掉后远近行不再做高斯模糊（只保留缩放/透明度层次） */
        public boolean blurEnabled = true;
        public int baseSize = 52;
        public String fontFamily = "Microsoft YaHei";        // 英文/拉丁字符字体（歌词默认）
        public String fontFamilyCN = "Microsoft YaHei";      // 中文/汉字字符字体
        public double scaleBig = 1.55;
        public double scaleSmall = 0.75;
        public int yGap = 120;
        public double transRange = 1.1;
        public double zoomPower = 3.0;
        public double scrollSpeed = 6.0;
        public int topX = 80;
        public int bottomX = 80;
        /** 水平锚点：歌词中心相对于窗口宽度的比例。0.5=居中（窗口内文字固定，靠移动窗口来左右摆放） */
        public double anchorX = 0.5;
        /** 窗口左上角屏幕 X 坐标（像素）。>=0 时 openWindow 直接用它定位；-1 表示按宽度居中 */
        public int windowX = -1;
        /** 目标屏幕左上角绝对 X（像素）。>=0 时悬浮窗铺到这块屏幕；-1 表示用主屏 */
        public int screenMinX = -1;
        /** 目标屏幕左上角绝对 Y（像素）。>=0 时配合 screenMinX 定位到这块屏幕；-1 表示用主屏 */
        public int screenMinY = -1;
        public double minScale = 0.7;
        public double maxScaleBoost = 2.0;
        public double affectRows = 8.0;
        public int glowAlpha = 2;
        public double glowW = 1.3;
        public double glowH = 1.3;
        /** 文字描边宽度（用户单位，会随 3D 缩放放大）；0=关闭。用于在复杂背景下勾勒轮廓、提升可读性 */
        public double strokeWidth = 0.7;
        /** 描边颜色（手动模式/自动模式 fallback 用，默认亮青） */
        public javafx.scene.paint.Color strokeColor = javafx.scene.paint.Color.rgb(90, 220, 255, 0.82);
        /** 是否根据背景明暗自动取反色描边：暗背景→白边，亮背景→黑边（默认开） */
        public boolean strokeAuto = true;
        public double rotateZ = 30;
        /**
         * 歌词偏到窗口右半边时是否自动反转 3D 旋转角。
         * 开启后无论歌词靠左还是靠右，都保持"当前行凸出、上下行向后倒"的观感；
         * 关闭则始终使用固定方向（左右两侧的立体观感会不一致）。
         */
        public boolean autoFlipRotate = true;
        public Color glowColor = Color.rgb(0, 200, 255);
        public String jsonPath = "lyrics.json";
        /** 透明穿透：悬浮窗不拦截鼠标， 点击会落到下方窗口（用作桌面歌词时建议开启） */
        public boolean clickThrough = false;
        /** 外部接管模式：被 PlayerApp 等集成时启用，loadLyrics() 跳过演示/文件/网络，完全由 setLyrics() 喂数据 */
        public boolean externalMode = false;
        /** 毛玻璃材质（文字本身）：半透冷白玻璃 + 高光描边，很便宜、不卡。默认开。 */
        public boolean frosted = true;
        /** 整窗背景模糊（Desktop Acrylic）：对悬浮窗背后桌面做 Windows DWM 亚克力磨砂，最出"毛玻璃"味
         *  但全屏透明窗每帧重绘会迫使 DWM 持续重合成、开销大、容易卡顿。卡就保持 false，只在显卡够强时开。 */
        public boolean frostBackdrop = false;
        /** 毛玻璃底色（磨砂 tint），通常取偏暗的半透明色以保证亮色文字可读 */
        public Color frostTint = Color.rgb(28, 32, 44);
        /** 毛玻璃底色不透明度 0~255（越大越不透明、越看不清背后桌面） */
        public int frostOpacity = 120;
        /** 渲染帧率上限（FPS）。JavaFX 渲染跑在单线程上，限帧可直接压低单核 CPU 占用；
         *  歌词滚动在 30~40fps 下依然顺滑。设 0 表示不限制（跟随显示器刷新）。 */
        public int targetFps = 40;
        /** 内部渲染分辨率系数（0~1）。Canvas 以 width*renderScale 的后备分辨率绘制再拉伸铺满，
         *  光栅像素数变为原来的 renderScale^2（0.5 → 1/4），单核光栅+场景图开销骤降；文字略软但毛玻璃更自然。
         *  注意：renderScale<1 在 IDE 运行与 jpackage 打包 exe 之间可能因 DPI/插值算法差异导致颜色观感不同（如浅蓝 vs 深蓝）。
         *  想要两边完全一致，改成 1.0（精确渲染，不吃性能，3D 歌词仅 40fps 左右）。 */
        public double renderScale = 1.0;

        public LyricConfig copy() {
            LyricConfig c = new LyricConfig();
            c.width = width; c.height = height; c.highlight = highlight; c.dimmed = dimmed;
            c.dimmedAlpha = dimmedAlpha; c.blurMax = blurMax; c.blurEnabled = blurEnabled; c.baseSize = baseSize;
            c.fontFamily = fontFamily; c.fontFamilyCN = fontFamilyCN; c.scaleBig = scaleBig; c.scaleSmall = scaleSmall;
            c.yGap = yGap; c.transRange = transRange; c.zoomPower = zoomPower;
            c.scrollSpeed = scrollSpeed; c.topX = topX; c.bottomX = bottomX; c.anchorX = anchorX; c.windowX = windowX;
            c.screenMinX = screenMinX; c.screenMinY = screenMinY;
            c.minScale = minScale; c.maxScaleBoost = maxScaleBoost; c.affectRows = affectRows;
            c.glowAlpha = glowAlpha; c.glowW = glowW; c.glowH = glowH; c.rotateZ = rotateZ;
            c.autoFlipRotate = autoFlipRotate;
            c.glowColor = glowColor; c.jsonPath = jsonPath;
            c.clickThrough = clickThrough; c.externalMode = externalMode;
            c.frosted = frosted; c.frostBackdrop = frostBackdrop; c.frostTint = frostTint; c.frostOpacity = frostOpacity;
            c.targetFps = targetFps; c.renderScale = renderScale;
            c.strokeWidth = strokeWidth; c.strokeColor = strokeColor; c.strokeAuto = strokeAuto;
            return c;
        }

        public LyricConfig width(int v) { width = v; return this; }
        public LyricConfig height(int v) { height = v; return this; }
        public LyricConfig highlight(Color v) { highlight = v; return this; }
        public LyricConfig dimmed(Color v) { dimmed = v; return this; }
        public LyricConfig dimmedAlpha(int v) { dimmedAlpha = v; return this; }
        public LyricConfig blurMax(double v) { blurMax = v; return this; }
        public LyricConfig blurEnabled(boolean v) { blurEnabled = v; return this; }
        public LyricConfig baseSize(int v) { baseSize = v; return this; }
        public LyricConfig fontFamily(String v) { fontFamily = v; return this; }
        public LyricConfig fontFamilyCN(String v) { fontFamilyCN = v; return this; }
        public LyricConfig scaleBig(double v) { scaleBig = v; return this; }
        public LyricConfig scaleSmall(double v) { scaleSmall = v; return this; }
        public LyricConfig yGap(int v) { yGap = v; return this; }
        public LyricConfig transRange(double v) { transRange = v; return this; }
        public LyricConfig zoomPower(double v) { zoomPower = v; return this; }
        public LyricConfig scrollSpeed(double v) { scrollSpeed = v; return this; }
        public LyricConfig topX(int v) { topX = v; return this; }
        public LyricConfig bottomX(int v) { bottomX = v; return this; }
        public LyricConfig minScale(double v) { minScale = v; return this; }
        public LyricConfig maxScaleBoost(double v) { maxScaleBoost = v; return this; }
        public LyricConfig affectRows(double v) { affectRows = v; return this; }
        public LyricConfig glowAlpha(int v) { glowAlpha = v; return this; }
        public LyricConfig glowW(double v) { glowW = v; return this; }
        public LyricConfig glowH(double v) { glowH = v; return this; }
        public LyricConfig rotateZ(double v) { rotateZ = v; return this; }
        public LyricConfig autoFlipRotate(boolean v) { autoFlipRotate = v; return this; }
        public LyricConfig glowColor(Color v) { glowColor = v; return this; }
        public LyricConfig jsonPath(String v) { jsonPath = v; return this; }
        public LyricConfig frosted(boolean v) { frosted = v; return this; }
        public LyricConfig frostBackdrop(boolean v) { frostBackdrop = v; return this; }
        public LyricConfig frostTint(Color v) { frostTint = v; return this; }
        public LyricConfig frostOpacity(int v) { frostOpacity = Math.max(0, Math.min(255, v)); return this; }
        public LyricConfig targetFps(int v) { targetFps = Math.max(0, v); return this; }
        public LyricConfig renderScale(double v) { renderScale = Math.max(0.1, Math.min(1.0, v)); return this; }
        public LyricConfig strokeWidth(double v) { strokeWidth = v; return this; }
        public LyricConfig strokeColor(javafx.scene.paint.Color v) { strokeColor = v; return this; }
        public LyricConfig strokeAuto(boolean v) { strokeAuto = v; return this; }
    }

    /** 当前生效的配置；可在启动前通过 getConfig()/setConfig() 调整，所有参数实时生效。 */
    private static LyricConfig config = new LyricConfig();
    public static LyricConfig getConfig() { return config; }
    public static void setConfig(LyricConfig c) { if (c != null) config = c; }
    /** 设置主程序舞台引用，悬浮窗会把滚轮事件转发给它，避免主窗口在覆盖区无法滚动 */
    public static void setMainStage(javafx.stage.Stage s) { mainStage = s; }
    public static void resetConfig() { config = new LyricConfig(); }

    static final SnapshotParameters SP = new SnapshotParameters();
    static { SP.setFill(Color.TRANSPARENT); }

    // ===================== 音频播放器 =====================
    static MediaPlayer audioPlayer;
    static final String TEMP_AUDIO = "temp_audio.m4s";

    // ===================== 光晕缓存 =====================
    static java.util.Map<String, WritableImage> glowCache = new java.util.HashMap<>();

    static WritableImage makeGlow(int w, int h, int alpha) {
        int gc = ((int) (config.glowColor.getRed() * 255) << 16)
               | ((int) (config.glowColor.getGreen() * 255) << 8)
               | (int) (config.glowColor.getBlue() * 255);
        String key = w + "x" + h + "@" + alpha + "#" + gc;
        return glowCache.computeIfAbsent(key, k -> {
            Canvas c = new Canvas(w, h);
            var g = c.getGraphicsContext2D();
            g.clearRect(0, 0, w, h);
            int cx = w / 2, cy = h / 2;
            for (int i = 0; i < 12; i++) {
                double tt = (i + 1.0) / 12;
                int lw = (int) (w * tt), lh = (int) (h * tt);
                if (lw < 4 || lh < 4) continue;
                int a = (int) (alpha * tt * tt);
                g.setFill(Color.color(config.glowColor.getRed(), config.glowColor.getGreen(),
                        config.glowColor.getBlue(), Math.min(a, 255) / 255.0));
                g.fillOval(cx - lw / 2, cy - lh / 2, lw, lh);
            }
            return c.snapshot(SP, null);
        });
    }

    // ===================== 歌词数据 =====================
    static class LyricLine {
        String text;
        double start, end;
        LyricLine(String t, double s, double e) { text = t; start = s; end = e; }
    }

    static List<LyricLine> lines = new ArrayList<>();

    static void loadLyrics() {
        // 外部接管模式（被 PlayerApp 集成时启用）：不加载演示/文件歌词，也不尝试网络，
        // 完全由 setLyrics()/clearLyrics() 喂数据，避免启动时闪一下无关歌词或读到陈旧文件。
        if (config.externalMode) {
            lines = new ArrayList<>();
            totalEnd = 3.0;
            autoTime = false;
            return;
        }
        // 读环境变量（Maven 子进程会继承）
        String input = System.getenv("BILI_INPUT");
        if (input == null || input.isEmpty()) {
            input = System.getenv("BILI_UID");
        }
        // 兜底读系统属性
        if (input == null || input.isEmpty()) {
            input = System.getProperty("BILI_INPUT");
        }

        System.out.println("[DEBUG] 最终 input = " + input);

        if (input != null && !input.trim().isEmpty()) {
            try {
                System.out.println("🎯 输入: " + input);
                int rows = BiliUidLyricService.fetchAndSave(input.trim(), config.jsonPath);
                System.out.println("[DEBUG] fetchAndSave 返回 rows = " + rows);

                // ★ 如果是 BV 号，尝试拉取 B站音频
                if (rows > 0 && (input.startsWith("BV") || input.startsWith("bv")
                        || input.startsWith("av") || input.startsWith("AV"))) {
                    tryPlayBiliAudio(input.trim());
                }
            } catch (Exception e) {
                System.err.println("⚠ 拉歌词异常:");
                e.printStackTrace();
            }
        }

        // 读 lyrics.json
        try {
            String content = new String(Files.readAllBytes(Paths.get(config.jsonPath)));
            Matcher m = Pattern.compile("\\{([^}]*)\\}").matcher(content);
            List<LyricLine> tmp = new ArrayList<>();
            while (m.find()) {
                String obj = m.group(1);
                String t = field(obj, "text");
                String s = field(obj, "start_ms");
                String e = field(obj, "end_ms");
                if (t != null && s != null && e != null) {
                    t = t.trim();
                    if (t.isEmpty()) continue;
                    double st = Double.parseDouble(s) / 1000.0;
                    double en = Double.parseDouble(e) / 1000.0;
                    if (en <= st) en = st + 3.0;
                    tmp.add(new LyricLine(t, st, en));
                }
            }
            for (int i = 0; i < tmp.size() - 1; i++)
                if (tmp.get(i).end > tmp.get(i + 1).start)
                    tmp.get(i).end = tmp.get(i + 1).start;
            if (!tmp.isEmpty()) {
                LyricLine last = tmp.get(tmp.size() - 1);
                last.end = Math.max(last.end, last.start + 3.0);
            }
            lines = tmp;
            if (!lines.isEmpty()) {
                System.out.println("✅ 加载了 " + lines.size() + " 行歌词");
                return;
            }
        } catch (IOException ex) {
            System.out.println("⚠ 未找到 lyrics.json，使用演示数据");
        }

        // 演示兜底
        lines.add(new LyricLine("窗外的麻雀", 0, 3));
        lines.add(new LyricLine("在电线杆上多嘴", 3, 6));
        lines.add(new LyricLine("你说这一句", 6, 9));
        lines.add(new LyricLine("很有夏天的感觉", 9, 12));
        lines.add(new LyricLine("手中的铅笔", 12, 15));
        lines.add(new LyricLine("在纸上来来回回", 15, 18));
        lines.add(new LyricLine("我用几行字形容", 18, 21));
        lines.add(new LyricLine("你是我的谁", 21, 24));
    }

    static String field(String obj, String f) {
        Matcher m = Pattern.compile("\"" + f + "\"\\s*:\\s*\"?([^\",}]+)\"?").matcher(obj);
        return m.find() ? m.group(1).replace("\"", "").trim() : null;
    }

    // ===================== B站音频播放 =====================
    static void tryPlayBiliAudio(String input) {
        new Thread(() -> {
            try {
                System.out.println("🔊 正在获取 B站音频: " + input);
                String bvid = input;
                if (input.startsWith("av") || input.startsWith("AV")) {
                    // av 号转 BV 号（简化：直接调 BiliAudioFetcher，它内部会处理）
                    bvid = BiliAudioFetcher.avToBv(input);
                }

                String savedPath = BiliAudioFetcher.fetchAndSave(bvid, TEMP_AUDIO);
                if (savedPath == null) {
                    System.out.println("⚠ 音频获取失败（可能需要 SESSDATA 登录）");
                    return;
                }
                System.out.println("✅ 音频已下载: " + savedPath);

                // 回到 JavaFX 应用线程播放
                final String path = savedPath;
                javafx.application.Platform.runLater(() -> {
                    try {
                        Media media = new Media(new java.io.File(path).toURI().toString());
                        audioPlayer = new MediaPlayer(media);
                        audioPlayer.setVolume(0.8);
                        audioPlayer.setOnReady(() -> {
                            audioPlayer.play();
                            System.out.println("▶ 开始播放 B站音频");
                        });
                        audioPlayer.setOnError(() -> {
                            System.out.println("⚠ 播放出错: " + audioPlayer.getError());
                        });
                    } catch (Exception e) {
                        System.out.println("⚠ MediaPlayer 初始化失败: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                System.out.println("⚠ 音频获取失败: " + e.getMessage());
                System.out.println("   （可能是需要登录 SESSDATA，或该视频限制）");
            }
        }).start();
    }

    static int getActiveIdx(double t) {
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).start <= t && t < lines.get(i).end) return i;
        for (int i = 0; i < lines.size(); i++)
        if (t < lines.get(i).start) return i;
        return lines.size() - 1;
    }

    static class ScrollCtrl {
        double current = 0, target = 0;
        void update(double t) {
            target = getActiveIdx(t);
            double diff = target - current;
            if (Math.abs(diff) < 0.001) current = target;
            else current += diff * (1.0 / config.scrollSpeed);
        }
        double getOffset() { return current; }
    }

    static double easeOut(double x, double p) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, p);
    }

    static Color lerp(Color a, Color b, double x) {
        return Color.color(
                a.getRed() + (b.getRed() - a.getRed()) * x,
                a.getGreen() + (b.getGreen() - a.getGreen()) * x,
                a.getBlue() + (b.getBlue() - a.getBlue()) * x
        );
    }

    // 含透明度的线性插值（毛玻璃材质用，颜色与不透明度一起过渡）
    static Color lerpA(Color a, Color b, double x) {
        return Color.color(
                a.getRed() + (b.getRed() - a.getRed()) * x,
                a.getGreen() + (b.getGreen() - a.getGreen()) * x,
                a.getBlue() + (b.getBlue() - a.getBlue()) * x,
                a.getOpacity() + (b.getOpacity() - a.getOpacity()) * x
        );
    }

    // 向白色混合（t=0 原色, t=1 纯白），保留原透明度——用于把高亮/暗淡色转成冷白玻璃色
    static Color mixWhite(Color c, double t) {
        return Color.color(
                c.getRed() + (1 - c.getRed()) * t,
                c.getGreen() + (1 - c.getGreen()) * t,
                c.getBlue() + (1 - c.getBlue()) * t,
                c.getOpacity()
        );
    }

    // ===================== GPU 节点池 =====================
    static class LineNode {
        Group group = new Group();
        Text text = new Text();
        ImageView glow = new ImageView();
        GaussianBlur blur = new GaussianBlur(0);
        javafx.scene.text.Font curFont = null;   // 上次设置的字体，仅变化时 setFont，避免每帧重复
        javafx.scene.paint.Color curStrokeC = null;   // 上次描边颜色，仅变化时设置（自动反色会变化）
        LineNode() {
            text.setFont(Font.font(config.fontFamily, FontWeight.BOLD, (int) (config.baseSize * config.scaleSmall)));
            text.setEffect(blur);
            text.setTextAlignment(TextAlignment.CENTER);   // 长句拆两行时，上下两行各自居中
            text.setLineSpacing(config.baseSize * 0.15);    // 两行之间的行距（随缩放放大）
            if (config.strokeWidth > 0.01) text.setStroke(config.strokeColor);   // 文字描边：在复杂背景下勾勒轮廓，开销极小
            glow.setBlendMode(BlendMode.ADD);
            group.getChildren().addAll(glow, text);
            group.setVisible(false);
        }
    }

    static final int POOL_SIZE = 40;
    static List<LineNode> pool;   // 延迟到 JavaFX 线程初始化（节点必须在 FX 线程创建）

    // 辉光预渲染缓存：只生成一次基础辉光图，每帧靠 Canvas.drawImage 缩放复用，避免每帧新建 WritableImage 引起 GC 卡顿
    static WritableImage baseGlowImg = null;
    static int baseGlowA = -1;
    static final int GLOW_BASE_W = 256, GLOW_BASE_H = 128;

    // ===================== 窗口 / 运行状态（单例） =====================
    static Stage stage;
    static Stage mainStage;   // 主程序舞台，用于把滚轮事件转发回去，保证主窗口在悬浮窗覆盖下仍可滚动
    private static final boolean IS_WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static WinDef.HWND mainHwnd = null;   // 主窗口原生句柄（滚轮转发兜底用，惰性获取）
    private static WinDef.HWND hwnd3d = null;       // 悬浮窗自身句柄（整窗穿透用）
    private static boolean exTransparentApplied = false;   // WS_EX_NOACTIVATE（不抢焦点）是否已生效
    private static int ctFrame = 0;                 // 帧计数（定期复查穿透样式）
    private static boolean wheelDiagPrinted = false;
    static Pane rootPane;
    static ScrollCtrl scrollCtrl;
    static AnimationTimer timer;
    static AnimationTimer enterAnim = null;          // 换歌入场动画（新歌词从最底部向上滚入）
    static boolean suppressScrollUpdate = false;     // 入场动画期间阻止 renderFrame 内部的进度自动定位，改由动画直接控制
    static Timeline bgSampler = null;                // 背景亮度采样器（自动反色描边用），窗口关闭时停止
    static java.awt.Robot samplerRobot = null;       // 复用的 Robot 实例（截屏估算背景明暗）
    static final java.util.Random bgRand = new java.util.Random();   // 采样随机位置/采样点

    // ===================== 自适应画质（GPU 被其他程序抢占时自动降级） =====================
    static boolean autoQuality = true;            // 是否启用自适应画质；false=始终用 config 固定值
    static int qualityLevel = 0;                  // 当前画质档：0=最高 1=中 2=最低
    static final int[] Q_FPS  = {40, 30, 24};     // 各档限帧（FPS）
    static final double[] Q_BLUR = {10.0, 4.0, 0.0}; // 各档模糊上限（高斯模糊最吃 GPU）
    /** 倾角自适应开启时的基准最大倾角（度）：按歌词位置比例连续缩放，不受手动「透视倾角」影响 */
    static final double AUTO_ROTATE_DEG = 30;
    /** 水平锚点预设：左 / 右（与设置页「左/中/右」一致），中间为 0.5 */
    static final double ANCHOR_LEFT = 0.18;
    static final double ANCHOR_RIGHT = 0.78;
    static double targetBlur = 10.0;              // blurMax 平滑过渡目标，每帧缓动逼近 → 模糊淡出/恢复不突变
    static double renderMsEMA = 0;               // 渲染耗时滑动平均(ms)，反映当前真实渲染负担
    static long lastEvalNs = 0;                   // 上次档位评估时刻（防抖）

    /** 把颜色按系数 f(<1 变暗) 缩放 RGB，得到“深一点点”的描边色（外框比里面深） */
    private static javafx.scene.paint.Color darken(javafx.scene.paint.Color c, double f) {
        return javafx.scene.paint.Color.color(
                Math.min(1, Math.max(0, c.getRed() * f)),
                Math.min(1, Math.max(0, c.getGreen() * f)),
                Math.min(1, Math.max(0, c.getBlue() * f)),
                c.getOpacity());
    }
    static double bgLumEma = 128;        // 背景亮度指数移动平均（平滑采样抖动，避免闪烁）
    static double currentTime = 0;
    static double totalEnd = 3.0;
    /** true = 内部自动累加时间（独立预览/演示）；false = 由外部 setProgress 驱动（播放器同步） */
    static boolean autoTime = true;
    static boolean[] locked = { false };
    static double[] dragOffset = { 0 };
    static double[] lineCumY;                 // 变高布局：每行中心相对位置（累积）
    static List<LyricLine> layoutLinesRef;    // 用于检测歌词已重新加载

    // 是否含汉字（中文规则）
    private static boolean isCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;   // 含汉字 → 中文
        }
        return false;
    }

    // 把字体选项名解析为 JavaFX Font；"Computer Modern Italic" 映射到嵌入字体（斜体），其余用系统族名（3D 维持粗体风格）
    static javafx.scene.text.Font resolveLyricFont(String option, double size) {
        if ("Computer Modern Italic".equals(option) && PlayerApp.EMBEDDED_CM_FAMILY != null)
            return javafx.scene.text.Font.font(PlayerApp.EMBEDDED_CM_FAMILY, javafx.scene.text.FontPosture.ITALIC, size);
        return javafx.scene.text.Font.font(option, FontWeight.BOLD, size);
    }

    // 判断是否折行：含汉字按 12 字、纯英文/拉丁按 20 字为阈值（折成上下两行）
    private static boolean shouldWrap(String text) {
        return text.length() > (isCjk(text) ? 12 : 20);
    }

    // 拆成上下两行：中文按字对半；英文尽量在空格（词边界）处断开，避免把单词劈开
    private static String splitLine(String text) {
        if (!shouldWrap(text)) return text;
        int len = text.length();
        if (!isCjk(text)) {
            int target = len / 2;
            int cut = -1;
            // 从中间向两侧找最近的空格
            for (int d = 0; d <= len / 2; d++) {
                if (target - d >= 0 && text.charAt(target - d) == ' ') { cut = target - d; break; }
                if (target + d < len && text.charAt(target + d) == ' ') { cut = target + d; break; }
            }
            if (cut > 0) {
                return text.substring(0, cut) + "\n" + text.substring(cut + 1);
            }
        }
        // 中文或英文找不到合适空格：按字/字符平衡对半
        int mid = (len + 1) / 2;
        return text.substring(0, mid) + "\n" + text.substring(mid);
    }

    // 变高布局：每行按实际（单行/两行）高度计算中心位置，保证相邻行的“块间距”恒定
    private static void ensureLayout() {
        if (lineCumY != null && lines == layoutLinesRef && lineCumY.length == lines.size()) return;
        int n = lines.size();
        lineCumY = new double[n];
        double singleH = config.baseSize * config.scaleSmall;
        double lineSp = config.baseSize * 0.15;
        double gap = config.yGap - singleH;            // 单行时的块间距（与原来一致）
        for (int i = 0; i < n; i++) {
            if (i == 0) { lineCumY[i] = 0; continue; }
            boolean w  = shouldWrap(lines.get(i).text);
            boolean wp = shouldWrap(lines.get(i - 1).text);
            double hPrev = wp ? (2 * singleH + lineSp) : singleH;
            double hCur  = w  ? (2 * singleH + lineSp) : singleH;
            lineCumY[i] = lineCumY[i - 1] + (hPrev + hCur) / 2 + gap;
        }
        layoutLinesRef = lines;
    }

    // ===================== 渲染帧 =====================
    static void renderFrame(Pane root, double t, ScrollCtrl sc) {
        if (lines.isEmpty()) return;
        ensureLayout();
        if (!suppressScrollUpdate) sc.update(t);   // 入场动画期间由动画直接控制滚动位置
        double offset = sc.getOffset();
        int centerY = config.height / 2;
        int active = getActiveIdx(t);

        int minIdx = Math.max(0, (int) (offset - (centerY + 120) / config.yGap) - 2);
        int maxIdx = Math.min(lines.size() - 1, (int) (offset + (config.height - centerY + 120) / config.yGap) + 2);

        for (LineNode n : pool) n.group.setVisible(false);
        int pi = 0;

        for (int idx = minIdx; idx <= maxIdx; idx++) {
            if (idx < 0 || idx >= lines.size()) continue;
            LyricLine line = lines.get(idx);
            // 变高布局：按每行实际高度求中心位置，块间间距恒定（不再受是否折行影响）
            double y;
            if (lineCumY != null && idx < lineCumY.length) {
                int lo = (int) Math.floor(offset);
                double frac = offset - lo;
                double cF = (lo < 0) ? lineCumY[0]
                          : (lo + 1 < lineCumY.length) ? lineCumY[lo] + frac * (lineCumY[lo + 1] - lineCumY[lo])
                          : lineCumY[lo];
                y = centerY + (lineCumY[idx] - cF);
            } else {
                y = centerY + (idx - offset) * config.yGap;
            }
            double dist = Math.abs(idx - offset);
            double diff = idx - offset;
            boolean isActive = (idx == active);

            double renderScale, alpha;
            Color color;
            if (dist <= config.transRange) {
                double eased = easeOut(1 - dist / config.transRange, config.zoomPower);
                renderScale = config.scaleSmall + (config.scaleBig - config.scaleSmall) * eased;
                color = lerp(config.dimmed, config.highlight, eased);
                alpha = (config.dimmedAlpha + (255 - config.dimmedAlpha) * eased) / 255.0;
            } else {
                renderScale = isActive ? config.scaleBig : config.scaleSmall;
                color = isActive ? config.highlight : config.dimmed;
                alpha = isActive ? 1.0 : config.dimmedAlpha / 255.0;
            }

            double norm;
            if (dist <= config.affectRows) norm = (dist / config.affectRows) * 1.2;
            else { double ex = (dist - config.affectRows) / config.affectRows; norm = 1.0 + Math.pow(ex, 1.5); }

            double scale3D = Math.min(config.minScale + norm * config.maxScaleBoost, config.minScale + config.maxScaleBoost * 2.0);
            double smoothNorm = norm * norm * (3 - 2 * norm);
            // 倾角自适应：倾角角度与位置偏移都由歌词的「水平位置」决定。
            // 靠左/靠右 = 完整 3D 弧面（倾角与偏移最大），拖到中间平滑衰减为 0（无旋转、无位置偏移）；
            // 靠右时倾角与位置偏移一起镜像，使左右两侧的立体观感保持一致。
            // 关闭时沿用手动 rotateZ，且不做位置衰减（行为与旧版完全一致）。
            double posFactor = 1.0;   // 水平位置决定的强度：中间 0，左右两侧 1
            double dirFlip = 1.0;     // 靠右时镜像方向
            if (config.autoFlipRotate) {
                double edgeRef = (config.anchorX <= 0.5) ? (0.5 - ANCHOR_LEFT) : (ANCHOR_RIGHT - 0.5);
                double edgeDist = Math.abs(config.anchorX - 0.5) / (edgeRef > 0.001 ? edgeRef : 1.0);
                if (edgeDist > 1.0) edgeDist = 1.0;
                posFactor = edgeDist * edgeDist * (3 - 2 * edgeDist);   // smoothstep：中间→两侧平滑过渡
                if (config.anchorX > 0.5) dirFlip = -1.0;
            }
            double mag = config.autoFlipRotate ? AUTO_ROTATE_DEG : config.rotateZ;
            double angle = (diff < 0 ? mag : -mag) * smoothNorm * posFactor * dirFlip;

            LineNode node = pool.get(pi % POOL_SIZE);
            pi++;
            node.group.setVisible(true);

            // 长句拆成上下两行（中文>12字按字断 / 英文>20字按空格断词）；两行同时高亮
            String disp = splitLine(line.text);
            String fam = isCjk(line.text) ? config.fontFamilyCN : config.fontFamily;
            javafx.scene.text.Font f = resolveLyricFont(fam, config.baseSize * config.scaleSmall);
            if (!disp.equals(node.text.getText())) node.text.setText(disp);
            if (!f.equals(node.curFont)) { node.text.setFont(f); node.curFont = f; }
            node.text.setFill(color);

            double textW = node.text.getLayoutBounds().getWidth();
            double textH = node.text.getLayoutBounds().getHeight();
            if (textW <= 0) textW = 1;
            if (textH <= 0) textH = config.baseSize * config.scaleSmall;

            double finalScale = (renderScale / config.scaleSmall) * scale3D;
            node.text.setScaleX(finalScale);
            node.text.setScaleY(finalScale);
            node.text.setRotate(angle);
            // 文字描边：在复杂背景下勾勒轮廓。每帧按本行 finalScale 把宽度换算成屏幕像素，
            // 使屏幕上始终约为 config.strokeWidth 像素（不受 3D 缩放放大影响，避免高亮行描边过粗）；
            // 颜色仅变化时设置（自动反色会随时改变）
            if (config.strokeWidth > 0.01) {
                node.text.setStrokeWidth(config.strokeWidth / finalScale);
                // 描边色：自动模式用背景采样色；关闭时固定为“比文字填充色深一点点”的外框
                javafx.scene.paint.Color strokeCol = config.strokeAuto ? config.strokeColor : darken(color, 0.85);
                if (node.curStrokeC == null || !node.curStrokeC.equals(strokeCol)) {
                    node.curStrokeC = strokeCol;
                    node.text.setStroke(strokeCol);
                }
            } else if (node.curStrokeC != null) {
                node.curStrokeC = null;
                node.text.setStroke(null);
                node.text.setStrokeWidth(0);
            }

            // 上下高斯模糊可关：blurMax 还会被自适应画质动态改写，所以用独立开关而不是把 blurMax 设 0
            double blurRadius = config.blurEnabled ? Math.min(norm * config.blurMax, config.blurMax) : 0.0;
            node.blur.setRadius(Math.max(0, blurRadius));
            node.text.setOpacity(alpha);

            double rad = Math.toRadians(Math.abs(angle));
            int rotW = (int) (textW * finalScale * Math.abs(Math.cos(rad)) + textH * finalScale * Math.abs(Math.sin(rad))) + 20;
            int rotH = (int) (textW * finalScale * Math.abs(Math.sin(rad)) + textH * finalScale * Math.abs(Math.cos(rad))) + 20;
            int padding = blurRadius > 0.3 ? (int) (blurRadius * 2) + 4 : 0;
            int displayW = rotW + padding * 2;
            int displayH = rotH + padding * 2;

            double shiftX = (diff < 0 ? config.topX : config.bottomX) * norm * posFactor * dirFlip;
            double rotCenterX = config.width * config.anchorX + shiftX * 2;
            double rotCenterY = y - displayH / 2 + rotH / 2;

            node.text.setLayoutX(rotCenterX - textW / 2);
            node.text.setLayoutY(rotCenterY - textH / 2);
            node.group.setLayoutX(0);
            node.group.setLayoutY(0);

            if (isActive && displayW > 2 && displayH > 2) {
                int gw = (int) (displayW * config.glowW);
                int gh = (int) (displayH * config.glowH);
                // 复用预渲染的基础辉光图，仅缩放 ImageView，避免每帧新建 WritableImage（GC 卡顿主因之一）
                if (baseGlowImg == null || config.glowAlpha != baseGlowA) {
                    baseGlowImg = makeGlow(GLOW_BASE_W, GLOW_BASE_H, config.glowAlpha);
                    baseGlowA = config.glowAlpha;
                }
                node.glow.setImage(baseGlowImg);
                node.glow.setScaleX(gw / (double) GLOW_BASE_W);
                node.glow.setScaleY(gh / (double) GLOW_BASE_H);
                node.glow.setLayoutX(rotCenterX - gw / 2);
                node.glow.setLayoutY(rotCenterY - gh / 2);
                node.glow.setOpacity(1.0);
                node.glow.setVisible(true);
            } else {
                node.glow.setVisible(false);
            }
        }
    }

    // 让整个悬浮窗在 Windows 上完全点击穿透：加 WS_EX_TRANSPARENT（命中测试穿透）+ WS_EX_NOACTIVATE（不抢焦点）。
    // 仅靠窗口扩展样式实现稳定穿透——文字与不透明像素也一律往下层窗口传递。无任何磨砂/亚克力背景（初代方案）。
    // 仅 Windows 生效；其它平台静默跳过。
    private static void enableFullClickThrough(Stage st) {
        if (!IS_WIN) return;
        hwnd3d = getHwnd(st);
        if (hwnd3d == null) { System.out.println("[3D] WARN cannot get overlay HWND, skip click-through"); return; }
        boolean ok = applyExTransparent(true);
        System.out.println("[3D] click-through(hwnd=" + hwnd3d + ") applied=" + ok);
    }

    // 悬浮窗"点击穿透 + 永不抢焦点 + 不进任务栏"扩展样式：
    // WS_EX_TRANSPARENT(0x20) 命中测试穿透 + WS_EX_NOACTIVATE(0x08000000) 不抢焦点
    // + WS_EX_TOOLWINDOW(0x80) 隐藏任务栏/Alt-Tab 图标（3D 歌词是浮动层，不应作为独立窗口出现）。
    private static final int EX_TRANSPARENT  = 0x20;
    private static final int EX_NOACTIVATE  = 0x08000000;
    private static final int EX_TOOLWINDOW  = 0x80;
    private static final int GHOST_BITS     = EX_TRANSPARENT | EX_NOACTIVATE | EX_TOOLWINDOW;

    // 读 GWL_EXSTYLE，若缺 GHOST_BITS 则补上；frameChange=true 时额外发 SetWindowPos(SWP_FRAMECHANGED) 通知系统样式已变（仅首次需要）。
    private static boolean applyExTransparent(boolean frameChange) {
        if (hwnd3d == null) return false;
        try {
            int ex = User32.INSTANCE.GetWindowLong(hwnd3d, WinUser.GWL_EXSTYLE);
            if ((ex & GHOST_BITS) != GHOST_BITS) {
                User32.INSTANCE.SetWindowLong(hwnd3d, WinUser.GWL_EXSTYLE, ex | GHOST_BITS);
                if (frameChange) User32.INSTANCE.SetWindowPos(hwnd3d, null, 0, 0, 0, 0,
                        WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER | WinUser.SWP_FRAMECHANGED);
            }
            exTransparentApplied = (User32.INSTANCE.GetWindowLong(hwnd3d, WinUser.GWL_EXSTYLE) & GHOST_BITS) == GHOST_BITS;
            return exTransparentApplied;
        } catch (Throwable t) {
            System.out.println("[3D] WARN cannot enable click-through: " + t);
            return false;
        }
    }

    // 每帧复查：若 JavaFX 把样式冲掉了就重新补上（只 SetWindowLong，避免每帧 SetWindowPos 闪烁）
    private static void ensureExTransparent() {
        if (!IS_WIN || hwnd3d == null) return;
        if (!exTransparentApplied) applyExTransparent(false);
    }

    // 取窗口原生 HWND：先反射 Stage->peer->getNativeWindow（JFX 旧版），失败再按标题枚举顶层窗口兜底。
    private static WinDef.HWND getHwnd(Stage st) {
        if (!IS_WIN || st == null) return null;
        // 1) 反射
        try {
            Object peer = null;
            for (String m : new String[]{"impl_getPeer", "getPeer"}) {
                try { java.lang.reflect.Method mm = st.getClass().getMethod(m); peer = mm.invoke(st); if (peer != null) break; }
                catch (Throwable ignore) {}
            }
            if (peer != null) {
                try {
                    long hwnd = (long) peer.getClass().getMethod("getNativeWindow").invoke(peer);
                    return new WinDef.HWND(new com.sun.jna.Pointer(hwnd));
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        // 2) 按标题枚举
        return findHwndByTitle(st.getTitle());
    }

    private static WinDef.HWND findHwndByTitle(String title) {
        if (title == null || title.isEmpty()) return null;
        final WinDef.HWND[] out = { null };
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (out[0] != null) return true;
            int len = User32.INSTANCE.GetWindowTextLength(hwnd);
            if (len > 0 && len < 256) {
                char[] buf = new char[len + 1];
                User32.INSTANCE.GetWindowText(hwnd, buf, len + 1);
                if (title.equals(new String(buf, 0, len))) out[0] = hwnd;
            }
            return true;
        }, null);
        return out[0];
    }

    private static WinDef.HWND getMainHwnd() {
        if (mainHwnd == null && mainStage != null) mainHwnd = getHwnd(mainStage);
        return mainHwnd;
    }

    // ===================== 全局低级鼠标滚轮钩子（WH_MOUSE_LL）=====================
    // 不依赖 Windows 焦点 / 穿透样式：系统把所有滚轮事件先交给本钩子。若光标落在主窗口矩形内，
    // 直接滚动主程序列表并「吞掉」该事件（防止主窗口原生再滚一次造成重复）；矩形外（如浏览器）
    // 原样放行，照常滚动其它窗口。悬浮窗开启期间有效，关闭时卸载。
    private interface WinHook extends StdCallLibrary {
        WinHook INSTANCE = Native.load("user32", WinHook.class);
        int WH_MOUSE_LL = 14;
        int WM_MOUSEWHEEL = 0x020A;
        WinUser.HHOOK SetWindowsHookExW(int idHook, MyMouseProc lpfn, WinDef.HMODULE hMod, int dwThreadId);
        WinDef.LRESULT CallNextHookEx(WinUser.HHOOK hhk, int nCode, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
        boolean UnhookWindowsHookEx(WinUser.HHOOK hhk);
        int GetMessageW(WinUser.MSG lpMsg, WinDef.HWND hWnd, int wMsgFilterMin, int wMsgFilterMax);
        boolean TranslateMessage(WinUser.MSG lpMsg);
        WinDef.LRESULT DispatchMessageW(WinUser.MSG lpMsg);
    }
    private interface MyMouseProc extends StdCallLibrary.StdCallCallback {
        WinDef.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }
    private static WinUser.HHOOK wheelHook = null;
    private static Thread wheelHookThread = null;
    private static volatile boolean wheelHookStop = false;

    private static final MyMouseProc wheelProc = (nCode, wParam, lParam) -> {
        if (nCode >= 0 && wParam.intValue() == WinHook.WM_MOUSEWHEEL) {
            MsllHook s = new MsllHook(lParam.toPointer());
            // 钩子拿到的是物理屏幕像素；换算成 JavaFX 逻辑坐标，与下方的命中判定/场景图一致（高分屏必需）
            double scale = (mainStage != null) ? mainStage.getOutputScaleX() : 1.0;
            double lx = s.pt.x / scale, ly = s.pt.y / scale;
            double rawDelta = (short) (s.mouseData >> 16);    // 正=上滚，原生约 ±120
            double deltaY = rawDelta / 3.0;                   // 折算成 JavaFX 滚轮单位（约 ±40），手感接近真实滚轮
            if (wheelHookMainContains(lx, ly)) {
                final double fdx = lx, fdy = ly, fdyDelta = deltaY;
                javafx.application.Platform.runLater(() -> scrollMainOn(fdx, fdy, fdyDelta, false, false, false, false));
                if (!wheelDiagPrinted) {
                    System.out.println("[wheel-hook] scroll main dy=" + (int) rawDelta);
                    wheelDiagPrinted = true;
                }
                return new WinDef.LRESULT(1);                  // 吞掉，主窗口不再原生滚动
            }
        }
        return WinHook.INSTANCE.CallNextHookEx(wheelHook, nCode, wParam, lParam);
    };

    // MSLLHOOKSTRUCT（低级鼠标钩子参数）：从原生指针构造并读取字段
    // 必须是 public：JNA 通过反射读取其字段来布局结构体，private 内部类对 com.sun.jna 不可访问会抛 IllegalAccessException
    public static class MsllHook extends com.sun.jna.Structure {
        private static final java.util.List<String> FIELDS =
                java.util.Arrays.asList("pt", "mouseData", "flags", "time", "dwExtraInfo");
        public WinDef.POINT pt;
        public int mouseData;
        public int flags;
        public int time;
        public com.sun.jna.Pointer dwExtraInfo;
        public MsllHook(com.sun.jna.Pointer p) { super(p); read(); }
        @Override protected java.util.List<String> getFieldOrder() { return FIELDS; }
    }

    // 光标 JavaFX 逻辑坐标 (lx,ly) 是否落在主窗口矩形内
    private static boolean wheelHookMainContains(double lx, double ly) {
        if (mainStage == null) return false;
        return lx >= mainStage.getX() && lx < mainStage.getX() + mainStage.getWidth()
                && ly >= mainStage.getY() && ly < mainStage.getY() + mainStage.getHeight();
    }

    private static void installWheelHook() {
        if (!IS_WIN || wheelHook != null) return;
        wheelHookStop = false;
        wheelHookThread = new Thread(() -> {
            wheelHook = WinHook.INSTANCE.SetWindowsHookExW(WinHook.WH_MOUSE_LL, wheelProc, null, 0);
            if (wheelHook == null) { System.out.println("[wheel-hook] install FAILED"); return; }
            System.out.println("[wheel-hook] installed");
            WinUser.MSG msg = new WinUser.MSG();
            while (!wheelHookStop) {
                int res = WinHook.INSTANCE.GetMessageW(msg, null, 0, 0);
                if (res == 0 || res == -1) break;
                WinHook.INSTANCE.TranslateMessage(msg);
                WinHook.INSTANCE.DispatchMessageW(msg);
            }
        }, "wheel-hook");
        wheelHookThread.setDaemon(true);
        wheelHookThread.start();
    }

    private static void uninstallWheelHook() {
        wheelHookStop = true;
        if (wheelHook != null) {
            WinHook.INSTANCE.UnhookWindowsHookEx(wheelHook);
            wheelHook = null;
        }
    }

    // 在主窗口场景里，于光标屏幕坐标 (e.getScreenX/Y) 处找到可滚动控件，并向其派发一个与真实滚轮
    // 完全一致的 ScrollEvent（走和真实滚轮相同的代码路径）。成功返回 true。
    // 在主窗口场景里，于屏幕坐标 (sx,sy) 处找到可滚动控件，并派发一个与真实滚轮等价的 ScrollEvent。
    // 走和真实滚轮完全相同的代码路径（控件自身的滚动处理器响应）。成功返回 true。
    private static boolean scrollMainOn(double sx, double sy, double deltaY,
                                        boolean shift, boolean ctrl, boolean alt, boolean meta) {
        if (mainStage == null || mainStage.getScene() == null) return false;
        javafx.scene.Scene scene = mainStage.getScene();
        // 遍历主窗口场景图，找光标处「包含该点」的最内层可滚动控件（ScrollPane/ListView/TableView…）
        javafx.scene.Node hit = findScrollable(scene.getRoot(), sx, sy);
        if (hit == null) return false;
        javafx.geometry.Point2D local = hit.screenToLocal(sx, sy);
        double lx = (local == null) ? 0 : local.getX();
        double ly = (local == null) ? 0 : local.getY();
        // 直接派发到命中的控件（与其自身滚轮处理走同一代码路径）
        javafx.scene.input.ScrollEvent se = new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL,
                lx, ly, sx, sy,
                shift, ctrl, alt, meta, false, false,
                0.0, deltaY, 0.0, 0.0, 0.0, 0.0,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0.0, 0,
                new javafx.scene.input.PickResult(hit, sx, sy));
        hit.fireEvent(se);
        return true;
    }

    private static boolean scrollMainAt(javafx.scene.input.ScrollEvent e) {
        return scrollMainOn(e.getScreenX(), e.getScreenY(), e.getDeltaY(),
                e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
    }

    // 收集场景中全部可滚动控件（含嵌套则取最内层：按屏幕包围盒面积最小者）
    private static javafx.scene.Node findScrollable(javafx.scene.Parent root, double sx, double sy) {
        java.util.List<javafx.scene.Node> found = new java.util.ArrayList<>();
        collectScrollables(root, found);
        javafx.scene.Node best = null;
        double bestArea = Double.MAX_VALUE;
        for (javafx.scene.Node n : found) {
            javafx.geometry.Bounds b = n.localToScreen(n.getBoundsInLocal());
            if (b != null && b.contains(sx, sy)) {
                double area = b.getWidth() * b.getHeight();
                if (area < bestArea) { bestArea = area; best = n; }
            }
        }
        return best;
    }

    private static void collectScrollables(javafx.scene.Node n, java.util.List<javafx.scene.Node> out) {
        if (n instanceof javafx.scene.control.ScrollPane
                || n instanceof javafx.scene.control.ListView
                || n instanceof javafx.scene.control.TableView
                || n instanceof javafx.scene.control.TreeView
                || n instanceof javafx.scene.control.TreeTableView) {
            out.add(n);
        }
        if (n instanceof javafx.scene.Parent) {
            for (javafx.scene.Node c : ((javafx.scene.Parent) n).getChildrenUnmodifiable()) {
                collectScrollables(c, out);
            }
        }
    }

    // ===================== 窗口构建（standalone 与 调用接口共用） =====================
    static void openWindow(Stage st) {
        loadLyrics();

        rootPane = new Pane();
        rootPane.setStyle("-fx-background-color: transparent;");   // 明确透明，避免某些平台下出现实色背景
        rootPane.setMouseTransparent(config.clickThrough);   // 整窗（歌词区+空白）鼠标穿透，不挡下方窗口

        // 歌词节点池：每个可见行一个 Text+辉光 的 Group（原始 3D 渲染，流畅且稳定）
        if (pool == null) {
            pool = new ArrayList<>();
            for (int i = 0; i < POOL_SIZE; i++) pool.add(new LineNode());
        }
        for (LineNode n : pool) rootPane.getChildren().add(n.group);

        scrollCtrl = new ScrollCtrl();
        currentTime = 0;
        totalEnd = lines.isEmpty() ? 3.0 : lines.get(lines.size() - 1).end + 2.0;
        locked[0] = false;

        Scene scene = new Scene(rootPane, config.width, config.height, Color.TRANSPARENT);
        scene.setFill(Color.TRANSPARENT);                    // 显式透明填充，确保悬浮窗可透视
        st.initStyle(StageStyle.TRANSPARENT);                // 无边框 + 整窗透明
        st.setTitle("Lyric Preview - Drag | Ctrl+L Lock");
        st.setScene(scene);
        st.setResizable(false);
        st.setAlwaysOnTop(true);  // 歌词窗口置顶：浮于其它应用(浏览器等)之上，但位于主程序下方

        // 滚轮补充处理：悬浮窗若收到滚轮（穿透样式异常未生效时），直接向主窗口对应控件派发真实
        // ScrollEvent。绝大多数情况由全局 WH_MOUSE_LL 钩子(WheelHook)统一处理，这里仅作双保险。
        scene.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (mainStage == null || mainStage.getScene() == null) return;
            if (scrollMainAt(e)) e.consume();
        });

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.L && e.isControlDown()) {
                locked[0] = !locked[0];
                rootPane.setMouseTransparent(locked[0] || config.clickThrough);
                rootPane.setCursor(locked[0] ? Cursor.DEFAULT : Cursor.MOVE);
                st.setTitle(locked[0] ? "🔒 LOCKED" : "Lyric Preview - Drag | Ctrl+L Lock");
                e.consume();
            }
            // ★ 空格键暂停/继续音频
            if (e.getCode() == KeyCode.SPACE && audioPlayer != null) {
                if (audioPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                    audioPlayer.pause();
                    System.out.println("⏸ 暂停");
                } else {
                    audioPlayer.play();
                    System.out.println("▶ 继续");
                }
                e.consume();
            }
        });

        st.setOnCloseRequest(e -> {
            if (mainStage != null) mainStage.setAlwaysOnTop(false);  // 还原主程序的置顶状态
            if (audioPlayer != null) audioPlayer.stop();
            if (timer != null) { timer.stop(); timer = null; }
            stage = null;
            try { Files.deleteIfExists(Paths.get(TEMP_AUDIO)); } catch (Exception ignored) {}
        });

        st.show();
        installWheelHook();   // 开启全局滚轮钩子：悬浮窗覆盖主窗口时，把落在主窗口内的滚轮转发给它
        // 全屏透明窗口：对齐到目标屏幕左上角铺满，整窗可点击穿透（不再用 windowX 定位子窗口）
        int sx = (config.screenMinX >= 0) ? config.screenMinX : (int) Screen.getPrimary().getBounds().getMinX();
        int sy = (config.screenMinY >= 0) ? config.screenMinY : (int) Screen.getPrimary().getBounds().getMinY();
        st.setX(sx);
        st.setY(sy);
        enableFullClickThrough(st);   // Windows 下整窗点击穿透（WS_EX_TRANSPARENT）+ 不抢焦点（初代方案，无磨砂）
        // 主程序浮到歌词上方：光标落在主程序上时它是最顶层窗口，滚轮由主程序原生处理（最可靠）
        if (mainStage != null) {
            mainStage.setAlwaysOnTop(true);
            mainStage.toFront();
        }

        final double[] time = { currentTime };
        timer = new AnimationTimer() {
            long lastNs = 0;
            long lastRenderNs = 0;
            @Override
            public void handle(long nowNs) {
                if (lastNs == 0) { lastNs = nowNs; lastRenderNs = nowNs; lastEvalNs = nowNs; return; }
                if (autoTime) {
                    time[0] += (nowNs - lastNs) / 1e9;
                    if (time[0] > totalEnd) time[0] = 0;
                    currentTime = time[0];
                }
                lastNs = nowNs;
                // 帧率上限：JavaFX 渲染本就跑在单线程上，限帧直接压低单核 CPU/GPU 占用
                if (config.targetFps > 0) {
                    long interval = (long) (1e9 / config.targetFps);
                    if (nowNs - lastRenderNs < interval) return;
                }
                lastRenderNs = nowNs;
                // —— 自适应画质：测本帧渲染耗时，GPU 被抢(变卡)时自动降级，余量足时恢复 ——
                long t0 = System.nanoTime();
                ensureExTransparent();        // 防 JavaFX 重置穿透样式
                renderFrame(rootPane, currentTime, scrollCtrl);
                double dtMs = (System.nanoTime() - t0) / 1e6;
                if (autoQuality) {
                    // 渲染耗时滑动平均（指数），反映当前真实渲染负担
                    renderMsEMA = (renderMsEMA <= 0) ? dtMs : (renderMsEMA * 0.9 + dtMs * 0.1);
                    // 模糊半径平滑过渡（朝目标档位逼近），避免降级时模糊“啪”地消失/恢复时突兀
                    if (Math.abs(config.blurMax - targetBlur) > 0.05) {
                        config.blurMax += (targetBlur - config.blurMax) * 0.12;
                    } else {
                        config.blurMax = targetBlur;
                    }
                    // 每 1.5s 评估一次档位（滞回防抖：降级/升级阈值分开，避免档位来回跳）
                    if (nowNs - lastEvalNs > 1_500_000_000L) {
                        lastEvalNs = nowNs;
                        double budget = (config.targetFps > 0) ? (1000.0 / config.targetFps) : 16.0;
                        if (renderMsEMA > budget * 0.7 && qualityLevel < Q_FPS.length - 1) {
                            qualityLevel++;                                   // 渲染占帧预算 70%+ → GPU 吃紧，降级
                            config.targetFps = Q_FPS[qualityLevel];
                            targetBlur = Q_BLUR[qualityLevel];
                            System.out.println("[autoQuality] 降级→档位" + qualityLevel
                                    + " render=" + String.format("%.1f", renderMsEMA) + "ms /预算" + String.format("%.1f", budget) + "ms");
                        } else if (renderMsEMA < budget * 0.4 && qualityLevel > 0) {
                            qualityLevel--;                                   // 余量充足 → 升级恢复画质
                            config.targetFps = Q_FPS[qualityLevel];
                            targetBlur = Q_BLUR[qualityLevel];
                            System.out.println("[autoQuality] 升级→档位" + qualityLevel
                                    + " render=" + String.format("%.1f", renderMsEMA) + "ms /预算" + String.format("%.1f", budget) + "ms");
                        }
                    }
                }
            }
        };
        timer.start();
        stage = st;
    }

    // ===================== 对外调用接口（单例） =====================

    /**
     * 打开一个独立歌词预览窗口（无需通过 {@code mvn javafx:run}，也无需 {@link #main}）。
     * 必须在<b>已经启动的 JavaFX 应用线程</b>中调用（例如播放器的按钮回调里）。
     * 若窗口已打开，则仅将其置顶，不会重复创建。
     */
    public static void showPreview() { showPreview(null, null); }

    /** 用指定配置打开预览窗口。 */
    public static void showPreview(LyricConfig cfg) { showPreview(cfg, null); }

    /** 用指定配置 + 初始歌词打开预览窗口；initialLines 为 null/空时按环境变量或演示数据加载。 */
    public static void showPreview(LyricConfig cfg, List<LyricLine> initialLines) {
        if (cfg != null) setConfig(cfg);
        Platform.runLater(() -> {
            if (stage != null) { stage.toFront(); return; }
            Stage s = new Stage();
            openWindow(s);
            startBgSampler();   // 启动背景亮度采样，驱动自动反色描边
            if (initialLines != null && !initialLines.isEmpty()) setLyrics(initialLines);
        });
    }

    /**
     * 直接设置歌词数据（同步到渲染）。可在任意线程调用，内部切回 JavaFX 线程。
     * 自动计时模式（autoTime=true）会重置进度；外部进度控制模式（autoTime=false）
     * 保留当前进度，由外部 setProgress 驱动。
     */
    public static void setLyrics(List<LyricLine> newLines) {
        if (newLines == null || newLines.isEmpty()) return;
        Platform.runLater(() -> {
            boolean wasSwitching = !lines.isEmpty();   // 换歌（之前已有歌词在显示）
            lines = new ArrayList<>(newLines);
            totalEnd = lines.get(lines.size() - 1).end + 2.0;
            if (autoTime) {
                // 自动计时模式：重置到 0，从头开始
                currentTime = 0;
            }
            if (scrollCtrl == null) {
                if (stage != null) renderFrame(rootPane, currentTime, scrollCtrl);
                return;
            }
            if (wasSwitching && lines.size() > 1) {
                enterFromBottom();                  // 换歌：新歌词从最底部向上滚入
            } else {
                scrollCtrl.current = 0;
                if (stage != null) renderFrame(rootPane, currentTime, scrollCtrl);
            }
        });
    }

    /**
     * 换歌转场：新歌词先停在最底部（最后一行居中，整段歌词在视口最下方），
     * 再用约 400ms 缓出动画向上滚到当前播放行（新歌通常回到顶部），即"下一首从最底下往上"。
     * 动画期间 suppressScrollUpdate=true，阻止 renderFrame 内部的进度自动定位覆盖动画。
     */
    private static void enterFromBottom() {
        if (enterAnim != null) { enterAnim.stop(); enterAnim = null; }
        if (scrollCtrl == null) return;
        final double to = getActiveIdx(currentTime);          // 当前应定位行（新歌通常 0）
        final double from = Math.max(0, lines.size() - 1);    // 最底行居中
        if (from <= to + 0.001) {                            // 歌词过短，无需动画
            scrollCtrl.current = to;
            if (stage != null) renderFrame(rootPane, currentTime, scrollCtrl);
            return;
        }
        scrollCtrl.current = from;
        suppressScrollUpdate = true;
        final long startN = System.nanoTime();
        final double dur = 0.4e9;                            // 400ms
        enterAnim = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = (now - startN) / dur;
                if (t > 1) t = 1;
                double e = 1 - (1 - t) * (1 - t);            // ease-out
                scrollCtrl.current = from + (to - from) * e;
                renderFrame(rootPane, currentTime, scrollCtrl);
                if (t >= 1) {
                    stop();
                    enterAnim = null;
                    suppressScrollUpdate = false;
                    scrollCtrl.current = to;
                    renderFrame(rootPane, currentTime, scrollCtrl);
                }
            }
        };
        enterAnim.start();
    }

    /** 启动背景亮度采样（自动反色描边用）；窗口关闭时由 closePreviewNow 停止 */
    private static void startBgSampler() {
        if (bgSampler != null) return;
        bgSampler = new Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(700), e -> sampleBackground()));
        bgSampler.setCycleCount(Timeline.INDEFINITE);
        bgSampler.play();
    }

    /** 停止背景亮度采样 */
    private static void stopBgSampler() {
        if (bgSampler != null) { bgSampler.stop(); bgSampler = null; }
    }

    /**
     * 估算歌词窗口背后“大概的背景”明暗，用于自动反色描边。
     * 不再只取屏幕中心一点，而是在歌词播放的范围内<b>随机摆放</b>一个采样块，
     * 块内再<b>随机抽取多点</b>求平均亮度——更能反映歌词实际所在的背景。
     * 暗背景→白色描边、亮背景→黑色描边。开销极低（每 700ms 一次、块约 1/4 屏、块内 24 点），
     * 不影响歌词渲染帧率。仅 strokeAuto 开启时更新描边色。
     */
    private static void sampleBackground() {
        if (!config.strokeAuto || stage == null) return;
        try {
            if (samplerRobot == null) samplerRobot = new java.awt.Robot();
            int sw = (int) stage.getWidth(), sh = (int) stage.getHeight();
            // 采样块大小约 1/4 屏（足够代表背景，又比截全屏省）
            int areaW = Math.max(120, sw / 4), areaH = Math.max(68, sh / 4);
            // 歌词播放范围：横向偏右（歌词从左侧滚到右侧、锚点偏右），纵向铺满视口；
            // 在此范围内随机摆放采样块，避免固定中心造成误判
            int minX = (int) (stage.getX() + sw * 0.12);
            int maxX = (int) (stage.getX() + sw * 0.92) - areaW;
            int minY = (int) (stage.getY() + sh * 0.12);
            int maxY = (int) (stage.getY() + sh * 0.88) - areaH;
            if (maxX < minX) maxX = minX;
            if (maxY < minY) maxY = minY;
            int ax = minX + bgRand.nextInt(maxX - minX + 1);
            int ay = minY + bgRand.nextInt(maxY - minY + 1);
            java.awt.image.BufferedImage img = samplerRobot.createScreenCapture(
                    new java.awt.Rectangle(ax, ay, areaW, areaH));
            // 块内随机抽取 K 个样本点（样本更多 → 估计更稳）；同时累加亮度与蓝/红通道
            long sumLum = 0, sumR = 0, sumB = 0;
            final int K = 48;
            for (int i = 0; i < K; i++) {
                int px = bgRand.nextInt(areaW), py = bgRand.nextInt(areaH);
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                sumLum += (r * 299 + g * 587 + b * 114) / 1000;
                sumR += r; sumB += b;
            }
            double lum = (double) sumLum / K;                 // 0-255
            double bMinusR = (double) (sumB - sumR) / K;       // 偏蓝程度
            // EMA 平滑：抑制随机块位置/少量样本带来的噪声，避免描边色抖动
            bgLumEma = bgLumEma * 0.75 + lum * 0.25;
            // 偏蓝背景：降低“有效亮度”，使其在蓝底上倾向深色（深青）描边，提升对比
            double effBright = bgLumEma - Math.max(0, bMinusR) * 0.6;
            // 平滑过渡：t=0(暗背景→亮青) .. t=1(亮背景→深青)；区间 [100,156] 线性插值
            double t = (effBright - 100) / 56.0;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            // 两色在 RGB 上线性插值（深青只是比亮青“暗一点”），实现深浅平滑过渡
            javafx.scene.paint.Color bc = javafx.scene.paint.Color.rgb(90, 220, 255, 0.82); // 亮青
            javafx.scene.paint.Color dc = javafx.scene.paint.Color.rgb(45, 150, 185, 0.82); // 深青（仅暗一点）
            int rr = (int) (bc.getRed() * 255 + (dc.getRed() * 255 - bc.getRed() * 255) * t + 0.5);
            int gg = (int) (bc.getGreen() * 255 + (dc.getGreen() * 255 - bc.getGreen() * 255) * t + 0.5);
            int bb = (int) (bc.getBlue() * 255 + (dc.getBlue() * 255 - bc.getBlue() * 255) * t + 0.5);
            config.strokeColor = javafx.scene.paint.Color.rgb(rr, gg, bb, 0.82);
        } catch (Exception ex) {
            // 截图失败（无权限 / 无头环境）则维持当前描边色
        }
    }

    /**
     * 时间偏移：就地把已加载歌词的时间戳整体平移（秒），<b>不重置进度/滚动位置</b>。
     * 与 LyricView.applyOffset 同义，供主程序“时间偏移”按钮在 3D 悬浮窗已开时同步生效；
     * 偏移后滚动会从当前位置平滑切到新命中的行，不会跳回开头。
     */
    public static void applyOffset(double deltaSec) {
        if (lines == null || lines.isEmpty()) return;
        Platform.runLater(() -> {
            for (LyricLine l : lines) { l.start += deltaSec; l.end += deltaSec; }
            if (stage != null) renderFrame(rootPane, currentTime, scrollCtrl);
        });
    }

    /**
     * 由外部播放器驱动进度（<b>秒</b>）。调用后窗口停止内部自动计时，改用传入的真实播放位置，
     * 从而与音频严格同步。可高频调用。
     */
    public static void setProgress(double seconds) {
        autoTime = false;
        currentTime = seconds;
        if (stage != null) Platform.runLater(() -> renderFrame(rootPane, currentTime, scrollCtrl));
    }

    /** 恢复内部自动计时（重新变为独立演示/预览循环）。 */
    public static void resumeAutoTime() { autoTime = true; }

    /** 关闭预览窗口（若已打开）。 */
    public static void closePreview() {
        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    /**
     * 立即关闭预览窗口（须在 JavaFX 应用线程调用）。会同步把 {@code stage} 置空并停止计时器，
     * 用于“关闭→再打开”的切换，避免异步关闭导致 {@link #isShowing()} 误判、重开时跳过 showPreview。
     */
    public static void closePreviewNow() {
        if (stage == null) return;
        Stage s = stage;
        if (timer != null) { timer.stop(); timer = null; }
        if (enterAnim != null) { enterAnim.stop(); enterAnim = null; }
        stopBgSampler();
        suppressScrollUpdate = false;
        stage = null;
        uninstallWheelHook();   // 关窗即卸载全局滚轮钩子，避免影响其它窗口
        s.close();   // 仍会触发 onCloseRequest（再次置空 / 停止音频）
    }

    /** 当前预览窗口是否已打开。 */
    public static boolean isShowing() { return stage != null; }

    /** 实时把预览窗口水平移动到屏幕 X（像素）。供外部按左/中/右切换定位，文字在窗口内保持不动。 */
    public static void setWindowX(int x) {
        if (stage != null) stage.setX(Screen.getPrimary().getBounds().getMinX() + x);
    }

    /** 清空歌词（窗口开着时立即重绘为空白）。用于外部还没有歌词可喂、又要避免显示演示数据。 */
    public static void clearLyrics() {
        Platform.runLater(() -> {
            lines = new ArrayList<>();
            totalEnd = 3.0;
            if (stage != null) renderFrame(rootPane, currentTime, scrollCtrl);
        });
    }

    /** 把任意字符串转成合法 JSON 字符串（转义所有 JSON 特殊字符） */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** 导出当前歌词为 JSON 数组（供 Folia WebView 等外部消费者使用） */
    public static String getLyricsAsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LyricLine l : lines) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"text\":\"").append(jsonEscape(l.text)).append("\"");
            sb.append(",\"start\":").append(l.start);
            sb.append(",\"end\":").append(l.end);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 暂停 B站音频（若正在播放）。 */
    public static void pauseAudio() { if (audioPlayer != null) audioPlayer.pause(); }

    /** 继续 B站音频（若已暂停）。 */
    public static void playAudio() { if (audioPlayer != null) audioPlayer.play(); }

    // ===================== 启动（standalone: mvn javafx:run） =====================
    @Override
    public void start(Stage stage) {
        openWindow(stage);
    }

    public static void main(String[] args) {
        // 允许从命令行参数读（备用）
        if (args.length > 0) {
            System.setProperty("BILI_INPUT", args[0]);
        }
        launch(args);
    }
}

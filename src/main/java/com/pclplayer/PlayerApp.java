package com.pclplayer;

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.CacheHint;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.effect.DropShadow;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javafx.animation.*;
import com.sun.jna.Native;
import com.sun.jna.Library;
import com.sun.jna.platform.win32.WinDef;
import javafx.geometry.Bounds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerApp extends Application {

    private BorderPane root;
    private VBox listContainer;
    private String biliCookie = null;

    private StackPane avatarContainer;
    private ImageView avatarImg;
    private Label avatarPlaceholder;
    private Label accountStatusLabel;      // 设置页「登录状态」，登录/退出后实时刷新

    // ===== 左侧播放区 =====
    private TextField leftTitle;
    private Label leftArtist;
    private Label leftTime;
    private Slider progressSlider;

    // 自定义歌名：key = bvid（B站视频）或 "song:<原标题>"（内置曲目）。
    // 在播放页点击歌名即可直接改，保存到 settings.properties，歌词搜索也用改后的名字。
    private final Map<String, String> customTitles = new HashMap<>();
    private String currentTitleKey = null;
    private String currentOriginalTitle = null;
    private String currentResolvedTitle = null;
    private FavVideo currentFavVideo = null;
    private volatile boolean seeking = false;
    private Button leftPlayBtn;

    // ★ 跳转用：记录当前音频总时长(ms)
    private double lastKnownTotal = 0;

    // ★ 视频封面（4:3 居中裁剪 + 平滑圆角 + 浅蓝边框），放在曲名与播放键之间
    private static final int COVER_H = 176;
    private static final int COVER_W = (COVER_H * 4) / 3;
    private static final double COVER_RADIUS = 14;
    private ImageView leftCover;
    private LyricView lyricView;            // 播放页歌词视图（置于右侧区域，与播放列表二选一）
    private Rectangle leftCoverClip;
    private StackPane leftCoverFrame;
    private String leftCoverBvid = null;
    private final ObjectProperty<Image> leftCoverImg = new SimpleObjectProperty<>();
    private VBox leftInfo;                       // 左侧标题/歌手容器（切歌时淡入滑入）
    private Timeline coverBreathTl = null;       // 播放时封面缓慢"呼吸"动画
    private boolean coverBreathing = false;
    private DropShadow coverShadow = null;        // 封面悬停浮起阴影

    // ===== 顶部导航"播放"按钮引用 =====
    private NavButton playNavButton;

    // ===== 选项卡滑动指示器（PCL2 风格核心）=====
    private Region tabIndicator;
    private HBox tabBox;
    private final List<NavButton> navButtons = new ArrayList<>();

    // ===== 设置页 =====
    private int currentSettingsTab = 0;
    private final List<StackPane> settingsNavCells = new ArrayList<>();
    private StackPane settingsContentArea;
    // 设置页分类切换批次号：连点侧栏时靠它作废尚未执行完的旧切换，避免塞回过期面板
    private int settingsSwitchToken = 0;
    private Timeline settingsSwitchOutTL;      // 设置页旧面板的滑出动画（连点时需停掉上一条）

    // ===== 歌单页（左导航三大块：创建的收藏夹 / 我追的合集 / 追番与追剧）=====
    private int currentFavTab = 0;
    private final List<StackPane> favNavCells = new ArrayList<>();
    private StackPane favContentArea;
    private List<BangumiItem> bangumiCache;
    private final Map<Long, List<FavVideo>> seasonEpCache = new HashMap<>();

    // ===== 歌单记忆：记录上次选择的收藏夹 =====
    private FavFolder lastSelectedFolder = null;
    Map<FavFolder, List<FavVideo>> folderVideoCache = new HashMap<>();

    // ===== B 站音频播放器 =====
    private BiliAudioPlayer audioPlayer;

    // key 区分类型："F"+收藏夹id / "S"+合集season_id，避免两套 id 撞号串数据
    private final Map<String, List<FavVideo>> favCache = new HashMap<>();
    private final Set<String> favLoading = new HashSet<>();

    /**
     * 收藏夹分页拉取的增量回调（在后台线程调用）：每解析完一页就回调一次，
     * 让 UI 能把已拉取的条目立刻显示出来，而不必等整个收藏夹拉完。
     */
    private interface FavPageSink {
        /**
         * @param page  本页新增的条目（已跨页去重），调用方可安全持有
         * @param total 收藏夹媒体总数，接口没给时为 0
         * @param done  是否已是最后一页
         */
        void onPage(List<FavVideo> page, int total, boolean done);
    }

    // 歌单页当前渲染的数据源（渐进加载时随拉取进度增长），滚动懒加载监听读它拿最新数据
    private List<FavVideo> favRenderData = null;
    // 歌单页渲染批次号：每次重新渲染（切换收藏夹/命中缓存）都会自增。
    // 异步回调凭它判断自己是否已经过期，避免把上一个收藏夹的条目追加进当前列表
    private int favRenderToken = 0;
    // 歌单页滚动懒加载监听。同一个 ScrollPane 会被多个收藏夹复用，重复绑定会让监听器累积
    private ChangeListener<Number> favScrollListener = null;
    // 歌单页 tab 切换批次号：连点时靠它作废尚未执行的旧淡出回调
    private int favSwitchToken = 0;
    // 程序化 setValue 会触发 onAction，用此标志让那次回调跳过，避免收藏夹被加载两次
    private boolean suppressFavAction = false;
    // 切换收藏夹：列表整体淡出的动画（连点时要停掉上一条）与本次目标收藏夹的 key
    private Timeline favListFadeOut = null;
    private String favPendingKey = null;
    // 歌单页列表行单独承载，与尾部状态行分开。
    // 好处是追加新行时只动 favRowsBox，不必把状态行反复 remove/add
    private VBox favRowsBox = null;
    private Label favStatusLabel = null;
    // 内容没填满视口时自动补批：否则首屏那点行撑不出滚动条，永远触发不了滚动加载
    private ChangeListener<Number> favFillListener = null;
    // 渐近加载是否还在进行（决定滚到底时该补行还是该等新数据）
    private volatile boolean favStreaming = false;

    // ===== 歌单同步数据 =====
    private List<FavVideo> syncedVideos = null;
    private String syncedFolderTitle = null;
    private ScrollPane playlistScroll = null;   // 播放页右侧列表的滚动容器（启动异步加载后需重新填充）
    private Label playlistTitleLabel = null;    // 播放页歌单标题
    private ChangeListener<Number> playlistScrollListener = null; // 懒加载监听（重复填充时需先移除旧的）
    private List<FavVideo> playlistData = null;     // 播放页列表当前数据源
    private int playlistLoadedCount = 0;            // 已渲染的行数（懒加载进度，滚动到指定歌曲时需要它）

    private static final String COOKIE_FILE =
            System.getProperty("user.home") + "/.pcl-player/bili_cookie.txt";

    // ===== 单实例：防止重复启动（开发版与打包版 exe 共用同一把锁）=====
    /**
     * 必须持有一个强引用：文件锁依附于 FileChannel/Lock 对象，
     * 一旦被 GC 回收，锁会随之释放，多开守卫就失效了。
     */
    private static FileChannel instanceChannel;
    private static FileLock instanceLock;
    // 单实例 IPC：第二个实例连本地回环端口，通知已运行的实例把窗口显示/置顶
    private static final int IPC_PORT = 19999;
    private static java.net.ServerSocket ipcServer = null;
    private static PlayerApp appInstance = null;   // 供 IPC 线程回调已有实例的窗口操作

    // ===== 播放设置（持久化到 settings.properties）=====
    private static final String SETTINGS_FILE =
            System.getProperty("user.home") + "/.pcl-player/settings.properties";
    /** 拖入字体的持久化副本存放目录（位于应用数据目录下），原文件挪走/删除也不影响 */
    private static final String FONTS_DIR =
            System.getProperty("user.home") + "/.pcl-player/fonts";

    /** 嵌入字体：Computer Modern Unicode Italic（cmunit.otf，自由字体可嵌入分发）。
     *  运行时从 jar 资源加载并探测其字体族名（如 "CMU Serif"），供中/英文歌词字体选项使用。 */
    public static String EMBEDDED_CM_FAMILY = null;
    private boolean autoPlayOnStartup = false;      // 启动时自动播放上一首
    private String loopMode = "列表循环";            // 列表循环 / 单曲循环 / 随机播放
    private int defaultVolume = 50;                 // 0~100
    private boolean highAudioQuality = true;        // true=高音质(192K)，false=标准音质(128K)
    // 播放页右侧区域显示什么："歌词"=歌词视图，"播放列表"=当前收藏夹列表
    // 默认"播放列表"：保留原本可点选歌曲的列表界面，歌词为可选视图
    private String playPageDisplay = "播放列表";
    // 当前是否处于歌词模式（供空态提示等绑定用，避免列表模式下误显"暂无歌词"）
    private final BooleanProperty lyricModeActiveProp = new SimpleBooleanProperty(false);
    // 右侧区域两个可切换层的引用（播放列表层 / 歌词层），以及它们的容器
    private BorderPane rightBorderPane;
    private StackPane rightStack;
    private VBox playlistLayer;
    private VBox rightLyricLayer;

    // 歌词设置：齿轮按钮 + 延伸在主窗口右侧外部的独立面板
    private Stage primaryStage;
    private Button lyricGearBtn;
    private Stage lyricSettingsStage;   // 独立窗口，贴主窗口右外侧

    // 免责声明：首次启动弹窗，用户点"我同意"后置 true，不再显示
    private boolean disclaimerShown = false;

    // 系统托盘：关闭主窗口时最小化到托盘而非退出（不支持时退化为直接退出）
    private boolean trayAvailable = false;
    private boolean trayExit = false;
    private java.awt.SystemTray systemTray = null;
    private java.awt.TrayIcon trayIcon = null;
    private Stage trayMenuStage = null;   // 点击托盘图标弹出的 JavaFX 菜单（绕过 AWT 原生菜单的中文方块问题）
    private boolean trayPressHandled = false; // 右键“按下”是否已处理，防止松手二次触发
    private VBox lyricSettingsPanel;
    private Label lyricTitleLabel;       // 标题“歌词设置”，按背景自适应黑白
    private Label lyricOffsetLabel;
    private java.awt.Robot screenRobot;  // 用于采样标题背后屏幕像素，决定黑白
    private long lastContrastSample = 0; // 节流：避免每帧都截图
    private Color lyricTitleColor = Color.web("#1F2937");  // 标题当前颜色（用于平滑插值）
    private Timeline lyricTitleAnim;     // 标题黑白切换的过渡动画
    private VBox lyricSourceList;
    private ScrollPane lyricSourceScroll;   // 「可选来源」列表滚动区（按主窗口高度自适应列表高度用）
    private double lyricSessionOffset = 0;   // 本次打开面板累计的偏移（秒）
    private String currentLyricQuery = null;  // 当前歌词对应的查询词（用于多来源拉取/保存）
    private int lyricSourceFetchToken = 0;    // 拉取来源的请求序号，丢弃过期结果，避免“卡在加载中”
    /** 各来源最近一次拉取到的候选列表：来源名 -> 候选歌曲；点“获取”只补某个来源后据此重绘 */
    private Map<String, List<BiliUidLyricService.LyricCandidate>> lastCandidateMap;
    private GridPane mainContentCache;        // 播放页只构建一次，避免切回时重建 LyricView/设置面板导致状态错乱
    private boolean useLyricRendererFX = false;  // 是否启用 LyricRendererFX 3D 桌面歌词悬浮窗
    // 全局快捷键管理器（Windows 下挂全局键盘钩子；非 Windows 自动禁用）
    private final HotkeyManager hotkeyManager = new HotkeyManager();
    /** 3D 桌面歌词水平位置：左 / 中 / 右（对应 LyricRendererFX.LyricConfig.anchorX） */
    private String lyricAnchor = "左";
    /** 3D 桌面歌词水平锚点（0=最左，1=最右），连续可调；左/中/右 只是三个常用预设 */
    private double d3AnchorX = 0.18;
    /** 设置页里的水平位置滑块（快捷按钮需要反向同步它的值） */
    private Slider lyricAnchorSlider;

    // ===== 3D 歌词可调参数（默认即 LyricRendererFX 原版值，可在设置里调整，实时生效）=====
    private double d3RotateZ = 30, d3MinScale = 0.7, d3MaxScaleBoost = 2.0, d3AffectRows = 8.0,
                  d3TransRange = 1.1, d3ZoomPower = 3.0, d3ScaleBig = 1.55, d3ScaleSmall = 0.75;
    private double d3StrokeWidth = 0.7;          // 3D 歌词文字描边宽度（0=关闭），屏幕像素≈该值
    private boolean d3StrokeAuto = false;        // 3D 歌词描边：默认关闭背景自动反色，固定为比文字深一点的外框
    private boolean d3BlurEnabled = true;         // 3D 歌词上下高斯模糊开关（关掉后远近行只保留缩放/透明度层次）
    private boolean d3AutoFlipRotate = true;      // 3D 歌词偏到右半边时是否自动反转旋转角（保持立体观感一致）
    private double lastProgressMs = 0;           // 最近一次播放进度（毫秒），用于驱动 3D 歌词悬浮窗

    // ===== 歌词设置 =====
    /** 歌词来源顺序，元素取自 qq / netease / kugou，按顺序尝试 */
    private List<String> lyricSourceOrder = BiliUidLyricService.DEFAULT_SOURCE_ORDER;
    private int lyricFontSize = 16;
    private Color lyricHighlight = LYRIC_COLORS.get("蓝色");
    /** 歌词字体（分中英文）：中文/汉字字符用 lyricFontCN，英文/拉丁字符用 lyricFontEN */
    private String lyricFontCN = "Microsoft YaHei";
    private String lyricFontEN = "Computer Modern Italic";
    /** 拖入的自定义字体文件路径（持久化，重启后重新加载）；空=未使用 */
    private String lyricFontCNPath = "";
    private String lyricFontENPath = "";
    /** 是否加粗歌词：开启后普通行也用粗体（当前行始终加粗） */
    private boolean lyricBold = true;
    // ===== 歌词字体候选（分中英文）=====
    // 系统字体有几百个，全塞进下拉框根本没法挑，这里只列常用的一小撮（按“系统里存在”过滤，
    // 实际可选 5~10 个）；需要别的字体仍可把 ttf/otf 直接拖进下拉框。
    private static final List<String> LYRIC_FONT_CN_CANDIDATES = List.of(
            "Microsoft YaHei", "Microsoft YaHei UI", "DengXian", "SimHei", "SimSun",
            "KaiTi", "FangSong", "Source Han Sans CN", "Noto Sans CJK SC",
            "PingFang SC", "Heiti SC", "Microsoft JhengHei");
    private static final List<String> LYRIC_FONT_EN_CANDIDATES = List.of(
            "Segoe UI", "Arial", "Calibri", "Tahoma", "Verdana", "Georgia",
            "Times New Roman", "Cambria", "Consolas", "Courier New", "Trebuchet MS");
    /** 嵌入字体在界面上的选项名（不是系统族名，resolveLyricFont 负责映射到真实族） */
    private static final String EMBEDDED_FONT_OPTION = "Computer Modern Italic";
    private String lastPlayedBvid = null;           // 上次播放的 BV 号，启动恢复用
    // 上次播放所属收藏夹（启动时据此自动加载该收藏夹，填充播放页列表并支持连播）
    private long lastFolderId = 0;
    private String lastFolderTitle = null;
    private int lastFolderCount = 0;
    private boolean lastFolderSeason = false;
    // 上次播放所属收藏夹所在的歌单页 tab：0=创建的收藏夹，1=我追的合集
    // 不记这个的话，进入歌单页会默认停在 tab 0，用 tab 0 的第一个收藏夹把恢复的收藏夹覆盖掉
    private int lastFavTab = 0;
    // 用户是否真的进过歌单页。没进过的话 currentFavTab 还是默认值 0，
    // 退出时若拿它覆盖 lastFavTab 会把上次记录的正确 tab 冲掉
    private boolean favTabVisited = false;
    private volatile boolean exitStateSaved = false;   // 退出落盘去重（多个钩子会重复触发）
    private boolean pauseOnReady = false;           // 恢复上次播放但保持暂停态：媒体就绪后立即暂停（一次性标志）

    // ★ 无限滚动用
    private int loadedVideoCount = 0;
    private static final int VIDEO_PAGE_SIZE = 30;
    // 歌词设置中每个候选歌曲预览显示的歌词行数（点击“使用”才应用完整歌词）
    private static final int CANDIDATE_PREVIEW_LINES = 3;
    // “可选来源”列表区的最大高度：超过则内部滚动，避免候选过多把整个面板撑得过长
    private static final double SOURCE_LIST_MAX_H = 230;
    // 「可选来源」列表区的最小高度：主窗口太矮时的兜底（列表内部仍可滚动，不会丢内容）
    private static final double SOURCE_LIST_MIN_H = 90;
    /**
     * 下拉框统一行高（px）：按钮区单元格与弹出层选项行必须一致。
     * JavaFX 弹出层的高度按「按钮区单元格」的行高计算，若实际选项行更高，
     * 列表底部就会顶出弹出层边框一点（行数越多越明显）。
     */
    private static final double COMBO_ROW_H = 29;
    // 歌词设置“可选来源”中每个来源搜索到的候选歌曲数量（同来源多首匹配，每首一个可选版本）
    private static final int SOURCE_CANDIDATE_COUNT = 5;

    // ★ 窗口保持原尺寸不变
    private static final double MIN_W = 775, MIN_H = 435;
    private static final double INIT_W = MIN_W, INIT_H = MIN_H;
    private static final double RADIUS = 7;
    private static final double EDGE = 10;
    private static final int SHADOW_M = 32;     // 窗口投影留白：root 相对舞台内缩 32px，顶边缩放需扣除该偏移

    // ===== 外观设置（可实时生效并落盘）=====
    private int windowOpacity = 100;       // 窗口不透明度 50~100
    private int windowCornerRadius = 7;     // 窗口圆角半径 0~20
    private int coverCornerRadius = 14;     // 播放页封面圆角半径 0~30
    private int titleBarOpacity = 60;       // 顶栏不透明度 30~100（磨砂玻璃；越低越能透出桌面）
    private int frostGrain = 14;            // 磨砂强度 0~30：顶栏磨砂颗粒 + 浅蓝背景模糊共用
    private int bgBlueOpacity = 100;        // 浅蓝背景不透明度 0~100（越低蓝越淡、越透出白色底；与顶栏互不干扰）
    private int bgBlueBlur = 14;            // 浅蓝背景高斯模糊强度 0~30（0=清晰无模糊；与顶栏磨砂颗粒互不干扰）
    private int whiteBgOpacity = 100;       // 白色底不透明度 0~100（越低越透，桌面从白色底透出；与浅蓝层叠加）
    private Rectangle rootClip;             // 窗口圆角裁剪：随“圆角半径”实时更新
    private BorderPane titleBar;            // 顶栏：随顶栏不透明度/磨砂强度实时更新（磨砂颗粒，保持原样）
    private boolean cardBlurEnabled = true; // 播放页左/右卡片毛玻璃：局部 DWM 模糊窗口背后桌面（仅 Windows）
    private VBox leftPlayer;                // 播放页左侧卡片节点（毛玻璃区域之一）
    private WinDef.HWND mainHwnd = null;    // 主窗口原生句柄（毛玻璃用，惰性获取）

    private static final String C_BG = "#FAFAFA", C_PANEL = "#FFFFFF";
    private static final String C_PRIMARY = "#1976D2", C_PRIMARY_LIGHT = "#1565C0";
    private static final String C_TEXT = "#1F2937", C_SUB = "#6B7280";
    private static final String C_BORDER = "#E5E7EB", C_CARD = "#FFFFFF", C_CARD_HOV = "#F0F7FF";
    private static final String C_BTN_BG = "#E3F0FE";
    private static final String C_RIGHT_BG = "#E8F1FA";

    private static final Color NAV_BG_TRANSPARENT = Color.web("#FFFFFF", 0.0);
    private static final Color NAV_BG_ACTIVE = Color.web("#FFFFFF", 1.0);

    // 淡蓝层横向滑动的弹性插值器（ease-out-back）：先快、轻微过冲再回弹归位，更灵动
    private static final Interpolator BLUE_SPRING = new Interpolator() {
        private final double c1 = 2.0, c3 = c1 + 1;
        @Override protected double curve(double t) {
            double p = t - 1;
            return 1 + c3 * p * p * p + c1 * p * p;
        }
    };

    // 中心区：固定白底（背景恒为白）+ 可左右平移的淡蓝层 + 透明页面
    private StackPane centerStack;
    private Region centerWhiteBg;
    private Region bgBlue;
    private Node currentCenterPage;        // 当前 center 里的页面 ScrollPane
    private int pendingNavIndex = 0;       // 即将切换到的导航索引：0播放 1歌单 2搜索 3设置
    private Timeline centerSwitchTL;       // 页面切换时间轴（快速连点先收尾再开新动画）
    private ScrollPane csOldWrapped;       // 切换中被淡出的旧页面包装，结束/打断时移除

    // ===== 歌词设置：来源顺序与高亮色 =====
    // LinkedHashMap 保证下拉框按插入顺序展示
    private static final Map<String, List<String>> LYRIC_SOURCE_ORDERS = createLyricSourceOrders();
    private static final String[] LYRIC_SOURCE_LABELS =
            LYRIC_SOURCE_ORDERS.keySet().toArray(new String[0]);
    private static final Map<String, Color> LYRIC_COLORS = createLyricColors();

    private static Map<String, List<String>> createLyricSourceOrders() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("QQ音乐 → 网易云 → 酷狗", List.of("qq", "netease", "kugou"));
        m.put("网易云 → QQ音乐 → 酷狗", List.of("netease", "qq", "kugou"));
        m.put("酷狗 → QQ音乐 → 网易云", List.of("kugou", "qq", "netease"));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Color> createLyricColors() {
        Map<String, Color> m = new LinkedHashMap<>();
        m.put("蓝色", Color.web("#1976D2"));
        m.put("绿色", Color.web("#2E7D32"));
        m.put("橙色", Color.web("#EF6C00"));
        m.put("紫色", Color.web("#6A1B9A"));
        m.put("红色", Color.web("#C62828"));
        return Collections.unmodifiableMap(m);
    }
    private static final Color NAV_BG_HOVER = Color.web("#FFFFFF", 0.15);
    private static final Color NAV_TEXT_ACTIVE = Color.web("#1976D2");
    private static final int WIN_BTN_SIZE = 32;

    // ===== 顶栏磨砂玻璃（颗粒纹理，保持原样）=====
    /**
     * 生成磨砂颗粒纹理。JavaFX 没有 CSS 的 backdrop-filter，无法真正模糊背后的内容，
     * 所以磨砂感只能靠"降低不透明度 + 细颗粒"来模拟。
     * 做成 64×64 小图后由 BackgroundImage 以 REPEAT 平铺，不必随窗口尺寸重新生成。
     * 颗粒取接近白的明暗抖动（而非中灰），只会加一层轻微磨砂质感，不会把蓝色压灰。
     * 按 alpha 缓存：拖动滑块时同一档位直接复用，不重复生成。
     */
    private static Image frostCache = null;
    private static int frostCacheAlpha = -1;

    private static Image frostNoise(int alpha) {
        if (frostCache != null && frostCacheAlpha == alpha) return frostCache;
        int size = 64;
        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        java.util.Random rnd = new java.util.Random(20240607L);   // 固定种子：每次启动纹理一致
        int a = Math.max(0, Math.min(255, alpha));
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (a == 0) { pw.setArgb(x, y, 0); continue; }
                int v = 196 + rnd.nextInt(60) - 30;                // 接近白的高频明暗抖动
                // setArgb 只接受打包好的 ARGB：高位 alpha，低 24 位 RGB
                pw.setArgb(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        frostCache = img;
        frostCacheAlpha = a;
        return img;
    }

    private static final Object[][] SONGS = {
            {"plain bmusic player", "badyaoo", "0:00", 0.00},
    };

    private int currentIndex = 0;
    private final double[] dragOffset = {0, 0};
    private final double[] rs = {0, 0}, ss = {0, 0}, sp = {0, 0};
    private String titleBarMode = "";

    // ==================== 数据结构 ====================
    public static class FavFolder {
        public final long id;
        public final String title;
        public final int count;
        /** true = 追更的视频合集（内容走 /x/space/fav/season/list），false = 收藏夹（内容走 /x/v3/fav/resource/list） */
        public final boolean season;

        public FavFolder(long id, String title, int count, boolean season) {
            this.id = id;
            this.title = title;
            this.count = count;
            this.season = season;
        }

        /**
         * 必须按 id+season 判断相等：folderVideoCache 用 FavFolder 作 key，
         * 且 renderFavVideos 用 folder.equals(lastSelectedFolder) 决定是否同步到播放页。
         * 启动恢复时重建的 FavFolder 与歌单页后来加载的是不同对象，
         * 若不重写会导致缓存不命中、同一收藏夹被重复拉取。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FavFolder)) return false;
            FavFolder f = (FavFolder) o;
            return id == f.id && season == f.season;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(id) + Boolean.hashCode(season);
        }

        @Override
        public String toString() {
            return (season ? "合集 · " : "") + title + " (" + count + ")";
        }
    }

    public static class FavVideo {
        public final String bvid, upName;
        public String title;
        public final int duration;

        public FavVideo(String bvid, String title, String upName, int duration) {
            this.bvid = bvid;
            this.title = title;
            this.upName = upName;
            this.duration = duration;
        }

        public String durationStr() {
            int m = duration / 60, s = duration % 60;
            return m + ":" + (s < 10 ? "0" + s : s);
        }
    }

    public static class BangumiItem {
        public final long seasonId;
        public final String title, cover, badge;
        public final int type; // 1=追番 2=追剧

        public BangumiItem(long seasonId, String title, String cover, String badge, int type) {
            this.seasonId = seasonId;
            this.title = title;
            this.cover = cover;
            this.badge = badge;
            this.type = type;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        appInstance = this;                 // 记下本实例，供 IPC 线程激活窗口
        startIpcServer();                   // 监听本地端口：第二个实例连上来就显示窗口
        LyricRendererFX.setMainStage(primaryStage);   // 供 3D 歌词悬浮窗转发滚轮事件给主窗口
        // 设置软件图标（窗口左上角 + 任务栏）
        try (java.io.InputStream icoIs = getClass().getResourceAsStream("/app_icon.png")) {
            if (icoIs != null) {
                stage.getIcons().add(new javafx.scene.image.Image(icoIs));
            }
        } catch (Exception ex) {
            System.err.println("[icon] 加载 app_icon.png 失败: " + ex);
        }
        loadSettings();   // 先读设置（含自定义字体路径）
        initHotkeys();    // 初始化全局快捷键（Windows 下安装钩子）
        // 加载嵌入字体 Computer Modern Italic（cmunit.otf，自由字体）；失败则回退系统字体
        try (java.io.InputStream fontIs = getClass().getResourceAsStream("/fonts/cmunit.otf")) {
            if (fontIs != null) {
                Font f = Font.loadFont(fontIs, 12);
                if (f != null) EMBEDDED_CM_FAMILY = f.getFamily();
            }
        } catch (Exception ex) {
            System.err.println("[fonts] 加载 Computer Modern Italic 失败: " + ex);
        }
        if (EMBEDDED_CM_FAMILY == null)
            System.out.println("[fonts] Computer Modern Italic 未加载（资源缺失），该选项将回退到系统字体");
        else
            System.out.println("[fonts] Computer Modern Italic 已加载，族名=" + EMBEDDED_CM_FAMILY);
        // 注意：拖入字体的重新加载已移到 Scene 创建之后（见下方 stage.setScene 之后），
        // 以确保 JavaFX 字体子系统完全就绪，避免“重启后字体未注册、3D 歌词回退到默认字体”。
        biliCookie = loadCookie();
        if (biliCookie != null) {
            BiliAudioFetcher.setCookie(biliCookie);
            // 歌词服务复用同一份登录态（原先此处是硬编码 SESSDATA，已移除）
            BiliUidLyricService.setCookie(biliCookie);
            System.out.println("[PlayerApp] 已从本地恢复 B 站登录态");
        }

        // 初始化音频播放器
        audioPlayer = new BiliAudioPlayer();
        audioPlayer.setCallback(new BiliAudioPlayer.PlayCallback() {
            @Override
            public void onPlaying() {
                Platform.runLater(() -> {
                    // BiliAudioPlayer 每次新建 MediaPlayer 时音量会被重置为 1.0，这里统一套用设置音量
                    if (audioPlayer != null) audioPlayer.setVolume(defaultVolume / 100.0);
                    // 恢复上次播放但要求暂停态（自动播放关闭）：就绪后立刻暂停，进度为 0
                    if (pauseOnReady) {
                        pauseOnReady = false;
                        audioPlayer.pause();
                        setPlayIcon(false);
                        if (lyricView != null) lyricView.pause();
                        return;
                    }
                    setPlayIcon(true);
                    if (lyricView != null) lyricView.start();
                    prefetchNext();   // 歌曲真正在放、带宽空闲时预取下一首，切歌几乎无加载延迟
                });
            }

            @Override
            public void onPaused() {
                Platform.runLater(() -> {
                    setPlayIcon(false);
                    if (lyricView != null) lyricView.pause();
                });
            }

            @Override
            public void onResumed() {
                Platform.runLater(() -> {
                    setPlayIcon(true);
                    if (lyricView != null) lyricView.start();
                });
            }

            @Override
            public void onStopped() {
                Platform.runLater(() -> {
                    // 主动 stop() 会把 BiliAudioPlayer 内部的 currentBvid 置空；
                    // 自然播放结束（onEndOfMedia）不会清空。据此区分「播完了」和「用户停止」。
                    if (audioPlayer != null && audioPlayer.getCurrentBvid() == null) {
                        setPlayIcon(false);
                        if (lyricView != null) lyricView.stop();
                        return;
                    }
                    handlePlaybackFinished();
                });
            }

            @Override
            public void onError(String msg) {
                Platform.runLater(() -> {
                    setPlayIcon(false);
                    leftTime.setText("播放失败");
                });
            }

            @Override
            public void onProgress(double cur, double total) {
                Platform.runLater(() -> {
                    lastKnownTotal = total;
                    lastProgressMs = cur;
                    // 拖动进度条期间，歌词与进度条由拖动监听实时驱动，这里不回写，
                    // 否则 onProgress 携带的“实际播放位置”会把歌词覆盖回拖动前的位置。
                    if (!seeking) {
                        if (useLyricRendererFX && LyricRendererFX.isShowing()) {
                            LyricRendererFX.setProgress(cur / 1000.0);
                        }
                        // 用音频的真实播放位置驱动歌词，保证同步（单位：毫秒）
                        if (lyricView != null) lyricView.updateMillis(cur);
                        if (total > 0) {
                            progressSlider.setMax(total);
                            progressSlider.setValue(cur);
                        } else {
                            progressSlider.setValue(0);
                        }
                        leftTime.setText(formatTime(cur) + " / " + (total > 0 ? formatTime(total) : "--:--"));
                    }
                });
            }
        });
        if (biliCookie != null) audioPlayer.setCookie(biliCookie);

        // root 必须是 BorderPane：页面切换（switchPage）依赖 getCenter()/setCenter()
        root = new BorderPane();
        // 磨砂玻璃的关键：root 背景必须透明。舞台是 TRANSPARENT，后面不画不透明物，
        // 顶栏的半透明蓝才能实时透出桌面（与歌词悬浮窗同一逻辑）。
        // 圆角仍由 rootClip 裁出，投影由外层 windowRoot 的 DropShadow 提供。
        refreshRootGlass();

        // 裁剪矩形提为字段：外观设置的“圆角半径”需要实时改它的圆角
        rootClip = new Rectangle(INIT_W, INIT_H);
        rootClip.setArcWidth(windowCornerRadius * 2);
        rootClip.setArcHeight(windowCornerRadius * 2);
        root.setClip(rootClip);
        root.widthProperty().addListener((o, ov, nv) -> rootClip.setWidth(nv.doubleValue()));
        root.heightProperty().addListener((o, ov, nv) -> rootClip.setHeight(nv.doubleValue()));

        Label pageTitle = new Label("播放");
        BorderPane titleBar = buildTitleBar(stage, pageTitle);

        mainContentCache = createMainContent();
        // 中心容器：底层固定白底（背景恒为白）+ 中间可左右平移的淡蓝层 + 上层透明页面
        centerWhiteBg = new Region();
        centerWhiteBg.setMouseTransparent(true);
        centerWhiteBg.setStyle("-fx-background-color:" + C_PANEL + ";");
        centerWhiteBg.setOpacity(whiteBgOpacity / 100.0);   // 初始即按白色底不透明度字段生效
        bgBlue = new Region();
        bgBlue.setMouseTransparent(true);
        bgBlue.setTranslateX(0);            // 起始在播放页：淡蓝停在右侧
        // 初始透明度即按浅蓝背景不透明度字段生效（设置里调低后进入播放页也直接是淡蓝，而非满蓝）
        bgBlue.setOpacity(blueTargetOpacity(0));
        applyBgBlueFrost();                 // 设渐变底 + 磨砂颗粒；强度随「磨砂颗粒强度」滑块实时变
        centerStack = new StackPane(centerWhiteBg, bgBlue);
        centerStack.setBackground(Background.EMPTY);
        ScrollPane mainScroll = wrapInScroll(mainContentCache);
        centerStack.getChildren().add(mainScroll);
        currentCenterPage = mainScroll;
        // BorderPane 布局：标题栏占顶部，内容区在下方（无需再用 padding 让位）
        root.setTop(titleBar);
        root.setCenter(centerStack);

        setupTitleBarInteraction(stage, titleBar);
        setupEdgeResize(stage, root, titleBar);
        // 播放页局部毛玻璃：窗口尺寸变化（含首次布局）时重算模糊区域
        root.widthProperty().addListener((o, ov, nv) -> updateCardBlur());
        root.heightProperty().addListener((o, ov, nv) -> updateCardBlur());
        // 窗口宽度变化（拖动边缘缩放）时，淡蓝层要跟着左侧栏重新对齐：
        // 它的落点是 -(窗口宽-侧栏宽)，原本只在切页时算一次，缩放后蓝色带会停在旧位置，
        // 与左侧栏错位、看着像背景在漂。
        root.widthProperty().addListener((o, ov, nv) -> alignBgBlueToSidebar());

        // 窗口投影：用一个比面板大一圈、四周留透明边距的透明外层容器承载 DropShadow。
        // 阴影挂在外层（不被 root 自身的圆角 clip 裁掉），按 root 的圆角轮廓生成，呈正常软件那种柔和投影。
        StackPane windowRoot = new StackPane(root);
        windowRoot.setPadding(new Insets(SHADOW_M));
        windowRoot.setStyle("-fx-background-color:transparent;");
        windowRoot.setEffect(new DropShadow(20, 0, 8, Color.rgb(0, 0, 0, 0.30)));
        Scene scene = new Scene(windowRoot, INIT_W + 2 * SHADOW_M, INIT_H + 2 * SHADOW_M);
        scene.setFill(Color.TRANSPARENT);

        try {
            scene.getStylesheets().add(getClass().getResource("/scrollbar.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("[warn] scrollbar.css 未找到");
        }
        try {
            scene.getStylesheets().add(getClass().getResource("/combobox.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("[warn] combobox.css 未找到");
        }
        try {
            scene.getStylesheets().add(getClass().getResource("/pcl_style.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("[warn] pcl_style.css 未找到");
        }

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("plain bmusic player");
        stage.setResizable(false);
        // ===== 重新加载之前拖入的自定义字体（必须在 Scene 创建之后，确保字体子系统就绪）=====
        // 用实际加载到的真实 family 名回写 lyricFontCN/EN：万一重启后 JVM 字体环境变了、
        // 注册到的 family 名与存储名不一致，这里自动纠正，否则 LyricRendererFX 的 Font.font(fam)
        // 会静默回退到默认字体 → 看起来“没记住字体”。
        String realCnFam = reloadCustomFont("CN", lyricFontCNPath, lyricFontCN);
        if (realCnFam != null && !realCnFam.equals(lyricFontCN)) { lyricFontCN = realCnFam; saveSettings(); }
        String realEnFam = reloadCustomFont("EN", lyricFontENPath, lyricFontEN);
        if (realEnFam != null && !realEnFam.equals(lyricFontEN)) { lyricFontEN = realEnFam; saveSettings(); }
        // 外观设置：窗口不透明度（用 stage 而非 root，避免与启动淡入动画互相覆盖）
        stage.setOpacity(windowOpacity / 100.0);

        // 关闭主窗口：若系统托盘可用则最小化到托盘（不退出），否则直接落盘退出。
        // 再用 JVM 停机钩子兜底（Alt+F4 / 系统关机等路径）。
        // 关键：关闭窗口只是“隐藏”，不要让 JavaFX 在“最后一个可见窗口消失”时自动退出，
        // 否则隐藏到托盘后运行时被关掉、托盘图标变死；退出改由托盘菜单 trayExit() 显式控制。
        Platform.setImplicitExit(false);
        setupSystemTray();
        stage.setOnCloseRequest(e -> {
            if (trayAvailable && !trayExit) {
                e.consume();          // 拦截关闭，改为最小化到托盘（后台运行）
                hideToTray();
            } else {
                savePlaylistStateOnExit();
                Platform.exit();      // 无托盘时真正退出（implicitExit 已关，需显式退出）
            }
        });
        stage.setOnHiding(e -> {
            // 最小化到托盘时不要落盘（状态未变，且避免提前置 exitStateSaved）；
            // 真正的退出（托盘菜单退出 / OS 关机）才保存
            if (!(trayAvailable && !trayExit)) savePlaylistStateOnExit();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::savePlaylistStateOnExit));

        root.setOpacity(0);
        stage.show();

        // ===== 首次启动免责声明 =====
        if (!disclaimerShown) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("免责声明");
            alert.setHeaderText("plain bmusic player");
            alert.setContentText(
                "本程序仅供学习与研究参考，请勿用于任何商业用途。\n\n" +
                "程序仅播放您在 B 站已收藏/关注的合法内容，\n" +
                "请遵守相关法律法规及 B 站用户协议。\n\n" +
                "如需支持正版音乐，请使用 B 站官方客户端或合作音乐平台。"
            );
            alert.getButtonTypes().setAll(ButtonType.CLOSE);
            alert.initOwner(primaryStage);
            alert.setResizable(false);
            alert.showAndWait();
            disclaimerShown = true;
            saveSettings();
        }

        // 首次布局完成后应用播放页局部毛玻璃
        Platform.runLater(() -> updateCardBlur());

        if (biliCookie != null) loadAvatar(biliCookie);

        FadeTransition ft = new FadeTransition(Duration.millis(280), root);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();

        Platform.runLater(() -> positionIndicatorToSelected());

        // 恢复播放设置，开启「自动播放」时续播上次的歌
        Platform.runLater(this::restorePlaybackSettings);
    }

    /**
     * 启动恢复：直接按 BV 号恢复，不等歌单加载。
     * 歌单是异步拉取的，刚进程序时必然还没就绪，所以这里只靠持久化的 BV 号，
     * 标题/UP 主/时长再用 view 接口单独补（列表加载完前 UI 就能显示完整信息）。
     * 进度不保存：始终从头开始。
     */
    private void restorePlaybackSettings() {
        if (audioPlayer == null) return;
        audioPlayer.setVolume(defaultVolume / 100.0);
        audioPlayer.setHighQuality(highAudioQuality);
        applyPlayPageDisplay();

        // 启动时自动加载上次播放所在的收藏夹，让播放页列表直接有内容（并支持连播）
        restoreLastFolder();

        if (lastPlayedBvid == null) return;

        final String bvid = lastPlayedBvid;
        currentBvid = bvid;
        System.out.println("[settings] 恢复上次播放: " + bvid + "（自动播放=" + autoPlayOnStartup + "）");

        // 先用 BV 号占位，元信息拉到后回填
        applyLeftTitle(bvid, bvid);
        currentFavVideo = null;
        leftArtist.setText("加载中...");
        leftTime.setText("0:00 / --:--");
        progressSlider.setMax(1);
        progressSlider.setValue(0);
        lastKnownTotal = 0;
        setPlayIcon(false);
        loadAndSetCover(bvid);

        // 自动播放关闭时：仍然加载这首歌，但媒体就绪后立刻暂停，
        // 这样点击播放键能直接续播，不用重新走一遍解析下载
        pauseOnReady = !autoPlayOnStartup;
        audioPlayer.playBvid(bvid);

        new Thread(() -> {
            FavVideo meta = fetchVideoMeta(bvid);
            Platform.runLater(() -> {
                if (!bvid.equals(currentBvid)) return; // 期间用户已切歌则不要覆盖
                if (meta != null) {
                    applyLeftTitle(bvid, meta.title);
                    currentFavVideo = null;
                    leftArtist.setText(meta.upName);
                    leftTime.setText("0:00 / " + meta.durationStr());
                } else {
                    leftArtist.setText("B 站视频");
                }
                // 标题是异步拿到的，歌词只能在这里触发，早了会拿 BV 号去搜
                loadLyricForCurrent();
            });
        }).start();
    }

    /**
     * 启动时自动加载上次播放所在的收藏夹并填充播放页列表。
     * 歌单页是懒加载的，不这样做播放页会一直显示"请先在「歌单」页选择收藏夹"，
     * 且播完一首后 getCurrentPlaylist() 拿不到列表、连播失效。
     */
    private void restoreLastFolder() {
        if (lastFolderId <= 0) return;
        if (biliCookie == null || biliCookie.isEmpty()) {
            System.out.println("[restore] 未登录，跳过自动加载收藏夹");
            return;
        }
        final FavFolder folder = new FavFolder(lastFolderId,
                lastFolderTitle != null ? lastFolderTitle : "收藏夹", lastFolderCount, lastFolderSeason);

        new Thread(() -> {
            // 校验登录态：cookie 过期时拿不到 mid，直接跳过
            long mid = fetchUserMid(biliCookie);
            if (mid == 0) {
                System.out.println("[restore] cookie 已失效，跳过自动加载收藏夹");
                return;
            }
            // 复用列表加载逻辑：普通收藏夹走 resource/list，追更合集走 season/list
            List<FavVideo> videos = folder.season
                    ? fetchSeasonContents(biliCookie, folder.id, null)
                    : fetchFavResources(biliCookie, folder.id, null);

            Platform.runLater(() -> {
                if (videos.isEmpty()) {
                    System.out.println("[restore] 收藏夹加载为空，保持原状");
                    return;
                }
                // 若期间用户已在歌单页手动选过收藏夹，则不要覆盖
                if (lastSelectedFolder != null && !lastSelectedFolder.equals(folder)) return;

                lastSelectedFolder = folder;
                folderVideoCache.put(folder, videos);
                syncedVideos = videos;
                syncedFolderTitle = folder.title;
                favCache.put((folder.season ? "S" : "F") + folder.id, videos);
                System.out.println("[restore] 已自动加载收藏夹「" + folder.title + "」" + videos.size() + " 首");

                // 只在仍停留在播放页时重建列表，避免把用户正在看的其它页面顶掉
                if (listContainer != null && playlistScroll != null
                        && root != null && isOnPlayerPage()) {
                    fillPlaylistInto(listContainer, playlistScroll);
                    refreshRightPlaylistSelection();
                    scrollPlaylistToCurrent();   // 列表刚建好，滚到自动播放的那首
                }
            });
        }).start();
    }

    /** 用 view 接口按 BV 号补出标题/UP 主/时长，仅用于启动恢复 UI */
    private FavVideo fetchVideoMeta(String bvid) {
        try {
            URL url = new URL("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (biliCookie != null && !biliCookie.isEmpty()) conn.setRequestProperty("Cookie", biliCookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String json = sb.toString();
            if (!json.contains("\"code\":0")) return null;
            // 只看 pages 之前的部分，避免匹配到分 P 的 part 等同名字段
            int pagesIdx = json.indexOf("\"pages\"");
            String head = pagesIdx > 0 ? json.substring(0, pagesIdx) : json;
            String title = findString(head, "title");
            int duration = findInt(head, "\"duration\"");
            String up = "";
            int ownerIdx = head.indexOf("\"owner\"");
            if (ownerIdx >= 0) up = findString(head.substring(ownerIdx), "\"name\"");
            if (title == null || title.isEmpty()) title = bvid;
            if (up == null) up = "";
            return new FavVideo(bvid, unescapeAndCleanTitle(title), up, duration);
        } catch (Exception e) {
            System.out.println("[restore] 获取视频信息失败 " + bvid + ": " + e.getMessage());
            return null;
        }
    }

    // ==================== 标题栏 ====================
    private BorderPane buildTitleBar(Stage stage, Label pageTitle) {
        BorderPane bar = new BorderPane();
        titleBar = bar;                          // 供外观设置实时调整磨砂参数
        bar.setStyle("-fx-padding:8 12;");
        bar.setPrefHeight(52);
        bar.setPickOnBounds(true);
        // 磨砂玻璃：半透明蓝 + 磨砂颗粒。背后是透明的 root 与 TRANSPARENT 舞台，
        // 所以桌面颜色会实时透进来（与歌词悬浮窗同一套逻辑，不需要截图）
        applyTitleBarFrost();

        boolean loggedIn = biliCookie != null;
        avatarPlaceholder = new Label(loggedIn ? "V" : "U");
        avatarPlaceholder.setPrefSize(32, 32);
        avatarPlaceholder.setAlignment(Pos.CENTER);
        avatarPlaceholder.setStyle("-fx-background-color:" + (loggedIn ? "#66BB6A" : "#42A5F5") + ";-fx-background-radius:50%;-fx-text-fill:white;-fx-font-size:16px;-fx-font-weight:bold;");

        avatarImg = new ImageView();
        avatarImg.setFitWidth(32);
        avatarImg.setFitHeight(32);
        avatarImg.setPreserveRatio(true);
        avatarImg.setSmooth(true);
        avatarImg.setVisible(false);

        avatarContainer = new StackPane(avatarPlaceholder, avatarImg);
        avatarContainer.setPrefSize(32, 32);
        avatarContainer.setMaxSize(32, 32);
        avatarContainer.setAlignment(Pos.CENTER);
        avatarContainer.setStyle("-fx-cursor:hand;");
        // 圆形裁剪挂到容器上，圆心恒等于容器中心，避免头像被切歪
        Circle avatarClip = new Circle(16, 16, 16);
        avatarContainer.setClip(avatarClip);
        avatarContainer.setOnMouseClicked(e -> openBiliLogin());

        StackPane leftBox = new StackPane(avatarContainer);
        leftBox.setMinWidth(48);
        leftBox.setAlignment(Pos.CENTER_LEFT);
        bar.setLeft(leftBox);

        ToggleGroup navGroup = new ToggleGroup();
        navGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (!(newT instanceof Toggle t)) return;
            Object ud = t.getUserData();
            if (!(ud instanceof Integer)) return;
            int idx = (Integer) ud;
            pendingNavIndex = idx;
            animateIndicatorTo(idx);
            switch (idx) {
                case 0:
                    pageTitle.setText("播放");
                    // 播放页只构建一次并缓存：反复切回不会再 new LyricView() / 重建设置面板，
                    // 否则重建的浮动设置窗口会指向旧的面板实例，导致来源列表不刷新、偏移调不动。
                    if (mainContentCache == null) mainContentCache = createMainContent();
                    if (!isOnPlayerPage()) {
                        switchPage(mainContentCache);
                    } else {
                        syncLeftPlayerFromState();
                        // 列表数据可能已过期（用户在歌单页换了收藏夹）：数据源变了就重建一次
                        if (listContainer != null && playlistScroll != null
                                && getCurrentPlaylist() != playlistData) {
                            fillPlaylistInto(listContainer, playlistScroll);
                            scrollPlaylistToCurrent();   // 重建后把当前播放的歌滚到可视区
                        }
                        refreshRightPlaylistSelection();
                    }
                    applyPlayPageDisplay();   // 确保显示模式/歌词状态与最新设置一致
                    break;
                case 1:
                    pageTitle.setText("歌单");
                    switchPage(createFavPage());
                    break;
                case 2:
                    pageTitle.setText("搜索");
                    switchPage(createPlaceholder("搜索功能开发中..."));
                    break;
                case 3:
                    pageTitle.setText("设置");
                    switchPage(createSettingsPage());
                    break;
            }
        });

        tabBox = new HBox(20);
        tabBox.setAlignment(Pos.CENTER);
        String[] tabNames = {"播放", "歌单", "搜索", "设置"};
        for (int i = 0; i < tabNames.length; i++) {
            NavButton nb = new NavButton(tabNames[i], i == 0);
            nb.getToggle().setToggleGroup(navGroup);
            nb.getToggle().setUserData(i);
            if (i == 0) playNavButton = nb;
            navButtons.add(nb);
            tabBox.getChildren().add(nb);
        }

        tabIndicator = new Region();
        tabIndicator.setPrefHeight(3);
        tabIndicator.setMaxHeight(3);
        tabIndicator.setStyle("-fx-background-color:white;-fx-background-radius:1.5px;");
        tabIndicator.setMouseTransparent(true);
        tabIndicator.setManaged(false);
        tabIndicator.setLayoutX(0);
        tabIndicator.setLayoutY(0);
        tabIndicator.setPrefWidth(0);

        StackPane navStack = new StackPane();
        navStack.setAlignment(Pos.CENTER);
        navStack.setMaxWidth(Double.MAX_VALUE);
        navStack.setPickOnBounds(false);
        navStack.getChildren().addAll(tabBox, tabIndicator);

        bar.setCenter(navStack);

        HBox winBtns = new HBox(8);
        winBtns.setAlignment(Pos.CENTER_RIGHT);
        Button minBtn = new Button("−"), closeBtn = new Button("×");
        styleWindowButton(minBtn, 20);
        styleWindowButton(closeBtn, 24);
        minBtn.setOnAction(e -> stage.setIconified(true));
        // 托盘可用时，应用内关闭按钮也走“最小化到托盘”（保持 JavaFX 工具包存活，否则托盘会变死），
        // 托盘不可用（或已选择退出）才真正关闭退出
        closeBtn.setOnAction(e -> {
            if (trayAvailable && !trayExit) {
                hideToTray();
            } else {
                savePlaylistStateOnExit();
                Platform.exit();
            }
        });
        winBtns.getChildren().addAll(minBtn, closeBtn);
        bar.setRight(winBtns);
        BorderPane.setAlignment(winBtns, Pos.CENTER_RIGHT);
        return bar;
    }

    // ==================== 选项卡滑动指示器 ====================

    private void positionIndicatorToSelected() {
        if (tabBox == null || tabIndicator == null || navButtons.isEmpty()) return;
        for (int i = 0; i < navButtons.size(); i++) {
            if (navButtons.get(i).getToggle().isSelected()) {
                layoutIndicatorAt(i, false);
                return;
            }
        }
        layoutIndicatorAt(0, false);
    }

    private void animateIndicatorTo(int index) {
        layoutIndicatorAt(index, true);
    }

    private void layoutIndicatorAt(int index, boolean animated) {
        if (tabBox == null || tabIndicator == null) return;
        if (index < 0 || index >= navButtons.size()) return;

        NavButton target = navButtons.get(index);
        double targetXInBox = target.getLayoutX();
        double targetW = target.getWidth();
        double indicatorW = targetW > 0 ? targetW * 0.6 : 36;
        double indicatorX = targetXInBox + (targetW - indicatorW) / 2.0;
        double indicatorY = tabBox.getHeight() - 4;

        if (!animated) {
            tabIndicator.setLayoutX(indicatorX);
            tabIndicator.setLayoutY(indicatorY);
            tabIndicator.setPrefWidth(indicatorW);
            return;
        }

        double fromX = tabIndicator.getLayoutX();
        double fromW = tabIndicator.getPrefWidth();
        double toX = indicatorX;
        double toW = indicatorW;

        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(tabIndicator.layoutXProperty(), fromX, Interpolator.EASE_BOTH),
                        new KeyValue(tabIndicator.prefWidthProperty(), fromW, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(260),
                        new KeyValue(tabIndicator.layoutXProperty(), toX, Interpolator.EASE_BOTH),
                        new KeyValue(tabIndicator.prefWidthProperty(), toW, Interpolator.EASE_BOTH))
        );
        tl.play();
    }

    // ==================== 播放页 ====================
    /** 把页面包进 ScrollPane：窗口缩小时内容可滚动，避免被 root 的圆角 clip 裁切 */
    private ScrollPane wrapInScroll(Node page) {
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color:transparent;-fx-background:transparent;-fx-padding:0;");
        return sp;
    }

    /** 当前 center 是否停留在播放页（GridPane），需解包 ScrollPane */
    private boolean isOnPlayerPage() {
        return currentCenterPage instanceof ScrollPane
                && ((ScrollPane) currentCenterPage).getContent() instanceof GridPane;
    }

    private static Node pageOf(Node n) {
        return (n instanceof ScrollPane) ? ((ScrollPane) n).getContent() : n;
    }

    /** 淡蓝层落点：播放页(0)=右侧(0)，歌单(1)/设置(3)=左侧(-0.56w)，搜索(2)=无蓝移出(-w) */
    // 左导航栏默认宽度：sidebar 的 minWidth=180 且内容不足 180，实际即 180；
    // 布局出真实宽度后优先用它，让淡蓝精确对齐侧栏右缘
    private static final double SIDEBAR_W = 180;
    private VBox favSidebarRef, settingsSidebarRef;

    private double sidebarWidthFor(int idx) {
        VBox sb = (idx == 1) ? favSidebarRef : (idx == 3) ? settingsSidebarRef : null;
        if (sb != null && sb.getWidth() > 1) return sb.getWidth();
        return SIDEBAR_W;
    }

    private double blueTargetX(int idx, double w) {
        if (idx == 0) return 0;                            // 播放页：淡蓝在右侧
        if (idx == 1 || idx == 3) return -(w - sidebarWidthFor(idx)); // 歌单/设置：淡蓝仅覆盖左导航栏
        return -w;                                         // 搜索页：移出视野（同时不透明度为 0）
    }

    /**
     * 把淡蓝层重新对齐到当前页左侧栏的右边缘。
     * 歌单/设置页的蓝只盖住左侧栏那一段，落点是 -(窗口宽-侧栏宽)；
     * 拖动窗口边缘改变大小时必须重算，否则蓝色带会与侧栏错位（背景看着在漂）。
     */
    private void alignBgBlueToSidebar() {
        if (bgBlue == null || centerStack == null) return;
        // 切页动画进行中不抢：动画自带落点，等它跑完
        if (centerSwitchTL != null && centerSwitchTL.getStatus() == javafx.animation.Animation.Status.RUNNING) return;
        int idx = pendingNavIndex;
        if (idx != 1 && idx != 3) return;                 // 只有歌单/设置页的蓝需要贴左侧栏
        double w = centerStack.getWidth() > 1 ? centerStack.getWidth() : root.getWidth();
        if (w <= 1) return;
        double s = sidebarWidthFor(idx);
        if (s <= 1) return;
        bgBlue.setTranslateX(-(w - s));
    }

    /** 淡蓝层可见度：播放页与歌单/设置页有蓝，搜索页透明；再乘浅蓝背景不透明度 */
    private double blueTargetOpacity(int idx) {
        double vis = (idx == 0 || idx == 1 || idx == 3) ? 1.0 : 0.0;
        return vis * Math.max(0.0, Math.min(1.0, bgBlueOpacity / 100.0));
    }

    /** 应用浅蓝背景不透明度：按当前所在页的目标可见度即时刷新（拖动滑块时实时预览） */
    private void applyBgBlueOpacity() {
        if (bgBlue == null) return;
        // 仅在无页面切换动画进行时才直接改写，避免与蓝层平移动画抢属性
        if (centerSwitchTL == null || centerSwitchTL.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            bgBlue.setOpacity(blueTargetOpacity(pendingNavIndex));
        }
    }

    /** 应用白色底不透明度：越低越透，桌面从白色底透出（与浅蓝层叠加） */
    private void applyWhiteBgOpacity() {
        if (centerWhiteBg == null) return;
        centerWhiteBg.setOpacity(Math.max(0.0, Math.min(1.0, whiteBgOpacity / 100.0)));
        refreshRootGlass();   // 同步刷新卡片白底（查找变量 -card-bg）的不透明度
    }

    /** 刷新根节点上的「卡片白底」查找变量：白 + alpha 跟随白色底不透明度。
     *  歌单列表容器 / 设置卡片 / 视频行都引用 -card-bg，滑块一动即整体联动变半透明（磨砂玻璃）。 */
    private void refreshRootGlass() {
        if (root == null) return;
        double a = Math.max(0.5, Math.min(1.0, whiteBgOpacity / 100.0));
        root.setStyle("-fx-background-color:transparent;-fx-background-radius:" + windowCornerRadius
                + ";-card-bg:rgba(255,255,255," + a + ");");
    }

    /** 应用浅蓝背景：渐变底（左侧透明、右侧淡蓝）+ 高斯模糊；模糊强度由独立的「浅蓝背景模糊」滑块控制 */
    private void applyBgBlueFrost() {
        if (bgBlue == null) return;
        LinearGradient g = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#E8F1FA", 0.0)),
                new Stop(0.34, Color.web("#E8F1FA", 0.0)),
                new Stop(0.40, Color.web("#E8F1FA", 1.0)),
                new Stop(1.0, Color.web("#E3F2FD", 1.0)));
        BackgroundFill fill = new BackgroundFill(g, CornerRadii.EMPTY, Insets.EMPTY);
        // 高斯模糊让淡蓝与左缘过渡更柔（磨砂感由模糊提供）；强度由「浅蓝背景模糊」滑块独立控制
        bgBlue.setBackground(new Background(fill));
        double r = Math.max(0.0, Math.min(16.0, bgBlueBlur / 30.0 * 16));
        bgBlue.setEffect(r <= 0.05 ? null : new GaussianBlur(r));
    }

    // ===== 播放页局部毛玻璃：整窗 ACCENT_ENABLE_BLURBEHIND 模糊桌面，再用反向裁剪只让左右卡片透出模糊 =====
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private interface User32Accent extends com.sun.jna.Library {
        User32Accent INSTANCE = com.sun.jna.Native.load("user32", User32Accent.class);
        boolean SetWindowCompositionAttribute(WinDef.HWND hwnd, WINCOMPATTRDATA pAttrData);
    }

    /** 屏幕捕获排除：WDA_EXCLUDEFROMCAPTURE=0x11 让本窗口不参与屏幕捕获，
     *  这样采样窗口背后的桌面时不会把自己拍进去（毛玻璃模糊的前提）。 */
    private interface User32Affinity extends com.sun.jna.Library {
        User32Affinity INSTANCE = com.sun.jna.Native.load("user32", User32Affinity.class);
        boolean SetWindowDisplayAffinity(WinDef.HWND hwnd, int dwAffinity);
    }

    public static class ACCENTPOLICY extends com.sun.jna.Structure {
        public int nAccentState;
        public int nFlags;
        public int nColor;
        public int nAnimationId;
        public ACCENTPOLICY() { super(); }
        @Override protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("nAccentState", "nFlags", "nColor", "nAnimationId");
        }
    }

    public static class WINCOMPATTRDATA extends com.sun.jna.Structure {
        public int nAttribute;
        public com.sun.jna.Pointer pData;
        public int ulDataSize;
        public WINCOMPATTRDATA() { super(); }
        @Override protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList("nAttribute", "pData", "ulDataSize");
        }
    }

    private WinDef.HWND getMainWindowHwnd() {
        if (!isWindows() || primaryStage == null) return null;
        if (mainHwnd != null) return mainHwnd;
        try {
            Object peer = null;
            for (String m : new String[]{"impl_getPeer", "getPeer"}) {
                try { java.lang.reflect.Method mm = primaryStage.getClass().getMethod(m); peer = mm.invoke(primaryStage); if (peer != null) break; }
                catch (Throwable ignore) {}
            }
            if (peer != null) {
                try {
                    long hwnd = (long) peer.getClass().getMethod("getNativeWindow").invoke(peer);
                    mainHwnd = new WinDef.HWND(new com.sun.jna.Pointer(hwnd));
                    return mainHwnd;
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 整窗 ACCENT_ENABLE_BLURBEHIND：把窗口透明处的桌面做成毛玻璃（局部靠反向裁剪实现） */
    private void setWindowBlurBehind(boolean enable) {
        WinDef.HWND hwnd = getMainWindowHwnd();
        if (hwnd == null) return;
        try {


            ACCENTPOLICY ap = new ACCENTPOLICY();
            ap.nAccentState = enable ? 3 : 0;   // 3=ACCENT_ENABLE_BLURBEHIND, 0=ACCENT_DISABLED
            ap.nFlags = 0;
            ap.nColor = 0;
            ap.nAnimationId = 0;
            ap.write();
            WINCOMPATTRDATA wcad = new WINCOMPATTRDATA();
            wcad.nAttribute = 19;                // WCA_ACCENT_POLICY
            wcad.pData = ap.getPointer();
            wcad.ulDataSize = ap.size();
            wcad.write();
            User32Accent.INSTANCE.SetWindowCompositionAttribute(hwnd, wcad);
        } catch (Throwable ignore) {}
    }

    /** 整窗模糊调度：播放页开「局部毛玻璃」时整窗模糊 + 白底/蓝底半透明磨砂；
     *  非播放页只要白色底或左侧淡蓝未全不透明，也开启整窗模糊，让透明处透出被模糊的桌面（毛玻璃）。 */
    private void updateCardBlur() {
        if (!isWindows()) return;
        boolean onPlayer = cardBlurEnabled && isOnPlayerPage() && leftPlayer != null && rightBorderPane != null;
        if (!onPlayer) {
            // 非播放页：白色底或左侧淡蓝未全不透明 → 开整窗模糊，透明处透出磨砂桌面；
            // 否则关模糊、恢复实心白/蓝底
            boolean transparentBg = (whiteBgOpacity < 100)
                    || ((bgBlueOpacity < 100) && blueTargetOpacity(pendingNavIndex) > 0);
            setWindowBlurBehind(transparentBg);
            if (centerWhiteBg != null) { centerWhiteBg.setClip(null); applyWhiteBgOpacity(); }
            if (bgBlue != null) { bgBlue.setClip(null); bgBlue.setOpacity(blueTargetOpacity(pendingNavIndex)); }
            return;
        }
        // 播放页：整窗模糊（窗口透明处透出模糊桌面）+ 把白底/蓝底改为半透明磨砂底色，
        // 既保留白/蓝底色、又让模糊桌面透出（磨砂玻璃质感）
        setWindowBlurBehind(true);
        if (centerWhiteBg != null) {
            centerWhiteBg.setClip(null);
            applyWhiteBgOpacity();   // 与歌单/设置页同一档位、同一不透明度，保持一致
        }
        if (bgBlue != null) {
            bgBlue.setClip(null);
            bgBlue.setOpacity(blueTargetOpacity(pendingNavIndex));
        }
    }

    private void switchPage(Node newPage) {
        if (currentCenterPage != null && pageOf(currentCenterPage) == newPage) return;
        // 离开了主播放页（GridPane）时，自动关闭歌词设置
        if (!(newPage instanceof GridPane)) {
            closeLyricSettings();
        }

        ScrollPane newWrapped = wrapInScroll(newPage);
        double w = centerStack.getWidth() > 1 ? centerStack.getWidth() : 640;

        // 初次（理论上不会走到，init 已放好播放页）：直接落入并淡入
        if (currentCenterPage == null) {
            centerStack.getChildren().add(newWrapped);
            currentCenterPage = newWrapped;
            bgBlue.setTranslateX(blueTargetX(pendingNavIndex, w));
            bgBlue.setOpacity(blueTargetOpacity(pendingNavIndex));
            fadeInPage(newWrapped);
            return;
        }

        ScrollPane oldWrapped = (ScrollPane) currentCenterPage;
        centerStack.getChildren().add(newWrapped);
        currentCenterPage = newWrapped;

        // 淡蓝层：从当前位置非线性（弹性）平移到目标落点；页面内容独立淡入淡出，不随蓝一起横移
        double fromX = bgBlue.getTranslateX(), fromO = bgBlue.getOpacity();
        double toX = blueTargetX(pendingNavIndex, w), toO = blueTargetOpacity(pendingNavIndex);

        if (centerSwitchTL != null && centerSwitchTL.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            centerSwitchTL.stop();
            if (csOldWrapped != null) centerStack.getChildren().remove(csOldWrapped);
        }
        csOldWrapped = oldWrapped;

        KeyValue[] f0 = {
                new KeyValue(oldWrapped.opacityProperty(), 1.0, Interpolator.EASE_BOTH),
                new KeyValue(newWrapped.opacityProperty(), 0.0, Interpolator.EASE_BOTH),
                new KeyValue(newWrapped.translateYProperty(), 10, Interpolator.EASE_BOTH),
                new KeyValue(bgBlue.translateXProperty(), fromX, BLUE_SPRING),
                new KeyValue(bgBlue.opacityProperty(), fromO, Interpolator.EASE_BOTH),
        };
        KeyValue[] f1 = {
                new KeyValue(oldWrapped.opacityProperty(), 0.0, Interpolator.EASE_BOTH),
                new KeyValue(newWrapped.opacityProperty(), 1.0, Interpolator.EASE_BOTH),
                new KeyValue(newWrapped.translateYProperty(), 0.0, Interpolator.EASE_BOTH),
                new KeyValue(bgBlue.translateXProperty(), toX, BLUE_SPRING),
                new KeyValue(bgBlue.opacityProperty(), toO, Interpolator.EASE_BOTH),
        };
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, f0),
                new KeyFrame(Duration.millis(340), f1));
        tl.setOnFinished(e -> {
            if (oldWrapped != null) centerStack.getChildren().remove(oldWrapped);
            newWrapped.setTranslateY(0);
            csOldWrapped = null;
            centerSwitchTL = null;
            Platform.runLater(() -> updateCardBlur());
        });
        centerSwitchTL = tl;
        tl.play();
    }

    /** 页面内容独立淡入（带轻微上浮），不随淡蓝层横移 */
    private void fadeInPage(Node wrapper) {
        wrapper.setOpacity(0);
        wrapper.setTranslateY(10);
        Timeline in = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(wrapper.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(wrapper.translateYProperty(), 10, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(220),
                        new KeyValue(wrapper.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(wrapper.translateYProperty(), 0, Interpolator.EASE_OUT)));
        in.setOnFinished(e -> wrapper.setTranslateY(0));
        in.play();
    }

    private GridPane createMainContent() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(0));
        // 透明：固定白底由 centerWhiteBg 提供，右侧淡蓝由 bgBlue 层提供（可左右滑动）
        grid.setStyle("-fx-background-color:transparent;");
        ColumnConstraints cc1 = new ColumnConstraints();
        cc1.setPercentWidth(36);
        ColumnConstraints cc2 = new ColumnConstraints();
        cc2.setPercentWidth(64);
        grid.getColumnConstraints().addAll(cc1, cc2);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);
        grid.add(createLeftPlayer(), 0, 0);
        grid.add(createRightPlaylist(), 1, 0);

        // 封面尺寸绑定到 grid 的列宽（由窗口决定，与封面自身无关，避免自引用闭环）。
        // 严格保持 4:3；同时受可用高度约束，窗口变矮时不会顶破下方控件；限制在 150~340。
        if (leftCoverFrame != null) {
            DoubleBinding coverW = Bindings.createDoubleBinding(() -> {
                double wByWidth = grid.getWidth() * 0.36 - 32;          // 左列 36% - 左右 padding
                double wByHeight = (grid.getHeight() - 230) * 4.0 / 3.0; // 预留标题/控制区高度
                double w = Math.min(wByWidth, wByHeight);
                w = Math.max(150, Math.min(w, 340));
                return w;
            }, grid.widthProperty(), grid.heightProperty());
            leftCoverFrame.prefWidthProperty().bind(coverW);
            leftCoverFrame.prefHeightProperty().bind(coverW.multiply(3.0 / 4.0));
            leftCoverFrame.minWidthProperty().bind(coverW);
            leftCoverFrame.minHeightProperty().bind(coverW.multiply(3.0 / 4.0));
        }
        return grid;
    }

    private void syncLeftPlayerFromState() {
        String bvid = currentBvid;
        if (bvid == null) {
            applyLeftTitle(null, (String) SONGS[currentIndex][0]);
            currentFavVideo = null;
            leftArtist.setText((String)SONGS[currentIndex][1]);
            leftTime.setText("0:00 / " + SONGS[currentIndex][2]);
            progressSlider.setMax(1);
            progressSlider.setValue((double)SONGS[currentIndex][3]);
            setPlayIcon(false);
            return;
        }

        List<FavVideo> curList = getCurrentPlaylist();
        FavVideo cur = null;
        if (curList != null) {
            for (FavVideo v : curList) {
                if (bvid.equals(v.bvid)) { cur = v; break; }
            }
        }

        if (cur != null) {
            applyLeftTitle(bvid, cur.title);
            currentFavVideo = cur;
            leftArtist.setText(cur.upName);
            leftTime.setText("0:00 / " + cur.durationStr());
            if (!bvid.equals(leftCoverBvid)) {
                loadAndSetCover(bvid);
            }
        } else {
            applyLeftTitle(bvid, bvid);
            currentFavVideo = null;
            leftArtist.setText("B 站视频");
            leftTime.setText("--:-- / --:--");
            if (!bvid.equals(leftCoverBvid)) {
                loadAndSetCover(bvid);
            }
        }
        loadLyricForCurrent();   // 必须在 setText 之后

        progressSlider.setMax(1);
        progressSlider.setValue(0);
        lastKnownTotal = 0; // ★ 重置
        setPlayIcon(audioPlayer != null && audioPlayer.isPlaying());
    }

    private VBox createLeftPlayer() {
        VBox left = new VBox(8);
        leftPlayer = left;
        left.setPadding(new Insets(20, 16, 8, 16));
        // 透明：白色底由底层 centerWhiteBg 提供，淡蓝在右侧由 bgBlue 层提供；
        // 不画不透明白底，这样「白色底不透明度 / 浅蓝背景不透明度」设置才能在播放页生效
        left.setStyle("-fx-background-color:transparent;");
        left.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(left, Priority.ALWAYS);

        leftTitle = new TextField("未播放");
        leftArtist = new Label("");
        leftTime = new Label("0:00 / --:--");
        progressSlider = new Slider(0, 1, 0);
        leftPlayBtn = new Button("▶");

        syncLeftPlayerFromState();

        leftTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:20px;-fx-font-weight:bold;"
                + "-fx-background-color:transparent;-fx-border-color:transparent;-fx-background-insets:0;"
                + "-fx-padding:2 6;-fx-alignment:CENTER;-fx-focus-color:transparent;"
                + "-fx-faint-focus-color:transparent;-fx-max-width:360;");
        leftArtist.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:14px;");
        VBox info = new VBox(2);
        info.setAlignment(Pos.CENTER);
        info.getChildren().addAll(leftTitle, leftArtist);
        leftInfo = info;
        // 切歌时标题/歌手淡入滑入（纯变换，不动布局）
        leftTitle.textProperty().addListener((obs, o, n) -> animateLeftMetaIn());
        // 歌名可直接点击编辑：失焦/回车保存，Esc 取消；保存后歌词按新名称搜索
        leftTitle.setEditable(currentTitleKey != null);
        leftTitle.focusedProperty().addListener((o, ov, nv) -> {
            if (nv) {
                // 进入编辑：英文歌名先还原为逻辑文字，便于正常输入
                if (isEnglishTitle(currentResolvedTitle) && !currentResolvedTitle.equals(leftTitle.getText()))
                    leftTitle.setText(currentResolvedTitle);
            } else {
                commitTitleEdit();
                applyLeftTitleFont();   // 失焦后统一恢复（数学粗斜体 / 系统默认）显示
            }
        });
        leftTitle.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) cancelTitleEdit();
        });
        leftTitle.setOnAction(e -> {
            commitTitleEdit();
            // 回车确认后让标题失焦，便于恢复数学粗斜体显示
            if (leftTitle.getScene() != null) leftTitle.getScene().getRoot().requestFocus();
        });

        leftCover = new ImageView();
        leftCover.setPreserveRatio(false);
        leftCover.setSmooth(true);
        // 缩小封面时开启高质量缓存，避免缩成小图出现锯齿/发虚（抗锯齿）
        leftCover.setCache(true);
        leftCover.setCacheHint(CacheHint.QUALITY);
        leftCover.imageProperty().bind(leftCoverImg);
        leftCover.setOpacity(0);

        // 歌词视图放在播放页右侧大区域（与播放列表二选一），不再叠在封面上。
        // 创建实例供右侧面板使用；这里不属于任何父节点。
        lyricView = new LyricView();
        lyricView.setMouseTransparent(true);
        // 歌词加载完成后，若启用了 3D 桌面歌词，则同步到 LyricRendererFX 悬浮窗
        lyricView.setOnLoadResult(ok -> syncLyricRenderer());

        leftCoverFrame = new StackPane(leftCover);
        leftCoverFrame.setAlignment(Pos.CENTER);
        // 默认尺寸，真正随窗口缩放的绑定在 createMainContent 里基于 grid 列宽设置
        // （不能绑到 left 自身宽度，否则会与封面 prefWidth 形成自引用闭环，导致缩小时不变）
        leftCoverFrame.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        leftCoverFrame.setPrefSize(COVER_W, COVER_H);
        leftCoverFrame.setMinSize(COVER_W, COVER_H);
        // 封面图填满 frame（preserveRatio=false 保证与 4:3 边框完全对齐、随窗口只缩放不拉伸错位），
        // 裁剪圆角随 frame 尺寸缩放，与边框圆角一致
        leftCover.setPreserveRatio(false);
        leftCover.fitWidthProperty().bind(leftCoverFrame.widthProperty());
        leftCover.fitHeightProperty().bind(leftCoverFrame.heightProperty());
        leftCoverClip = new Rectangle(COVER_W, COVER_H);
        leftCoverClip.widthProperty().bind(leftCoverFrame.widthProperty());
        leftCoverClip.heightProperty().bind(leftCoverFrame.heightProperty());
        leftCoverClip.setArcWidth(coverCornerRadius * 2);
        leftCoverClip.setArcHeight(coverCornerRadius * 2);
        leftCover.setClip(leftCoverClip);
        leftCoverFrame.setStyle(
                "-fx-background-color:#EAF3FE;" +
                        "-fx-border-color:#BBDEFB;" +
                        "-fx-border-width:1.5;" +
                        "-fx-border-radius:" + coverCornerRadius + "px;" +
                        "-fx-background-radius:" + COVER_RADIUS + "px;");

        // 封面灵动交互：悬停轻微放大 + 投影浮起；点击打开 B 站原视频页
        coverShadow = new DropShadow(0, 0, 0, Color.rgb(25, 118, 210, 0.32));
        leftCoverFrame.setEffect(coverShadow);
        leftCoverFrame.setCursor(Cursor.HAND);
        leftCoverFrame.setOnMouseEntered(e -> {
            if (coverBreathTl != null) coverBreathTl.pause();
            setCoverShadow(true);
        });
        leftCoverFrame.setOnMouseExited(e -> {
            setCoverShadow(false);
            if (coverBreathing) startCoverBreath();
        });
        leftCoverFrame.setOnMouseClicked(e -> {
            if (leftCoverBvid != null && !leftCoverBvid.isEmpty()) {
                try { getHostServices().showDocument("https://www.bilibili.com/video/" + leftCoverBvid); }
                catch (Exception ignored) {}
            }
        });

        leftCoverImg.addListener((obs, o, img) -> {
            if (img != null) {
                leftCover.setOpacity(0);
                Timeline fade = new Timeline(
                        new KeyFrame(Duration.ZERO, new KeyValue(leftCover.opacityProperty(), 0, Interpolator.EASE_OUT)),
                        new KeyFrame(Duration.millis(260), new KeyValue(leftCover.opacityProperty(), 1.0, Interpolator.EASE_OUT))
                );
                fade.play();
            } else {
                leftCover.setOpacity(0);
            }
        });

        HBox controls = new HBox(16);
        controls.setAlignment(Pos.CENTER);
        Button prevBtn = new Button("<");
        Button nextBtn = new Button(">");
        styleCircleBtn(prevBtn, false, 44);
        styleCircleBtn(nextBtn, false, 44);
        styleCircleBtn(leftPlayBtn, true, 48);
        leftPlayBtn.setOnAction(e -> togglePlay());
        prevBtn.setOnAction(e -> prevSong());
        nextBtn.setOnAction(e -> nextSong());
        controls.getChildren().addAll(prevBtn, leftPlayBtn, nextBtn);
        attachBtnHover(prevBtn);
        attachBtnHover(nextBtn);
        attachBtnHover(leftPlayBtn);

        // ★★★ 新布局：时间显示 + 进度条（垂直居中） ★★★
        leftTime.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        HBox timeBox = new HBox(leftTime);
        timeBox.setAlignment(Pos.CENTER);
        timeBox.setPadding(new Insets(0));

        progressSlider.setValue(0);
        progressSlider.setMax(1);
        progressSlider.setShowTickLabels(false);
        progressSlider.setShowTickMarks(false);
        progressSlider.setSnapToTicks(false);
        progressSlider.getStyleClass().add("progress-slider");
        progressSlider.setCursor(Cursor.HAND);
        progressSlider.setPrefHeight(24);
        progressSlider.setMinHeight(24);
        progressSlider.setMaxHeight(24);

        // ★ 拖动开始时锁定 seeking，阻止 onProgress 回写
        progressSlider.setOnMousePressed(e -> seeking = true);
        progressSlider.setOnMouseReleased(e -> {
            seeking = false;
            seekFromSlider();
        });
        // ★ 键盘跳转
        progressSlider.setOnKeyPressed(e -> seeking = true);
        progressSlider.setOnKeyReleased(e -> {
            seeking = false;
            seekFromSlider();
        });
        // ★ 拖动过程中实时更新时间标签，并让歌词（主窗口 + 3D 桌面）跟随拖动位置
        progressSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (seeking && lastKnownTotal > 0) {
                double v = newVal.doubleValue();
                leftTime.setText(formatTime(v) + " / " + formatTime(lastKnownTotal));
                // 拖动时把当前拖动位置推给歌词，避免歌词停在实际播放位置、松手后被播放时间覆盖
                if (lyricView != null) lyricView.updateMillis(v);
                if (useLyricRendererFX && LyricRendererFX.isShowing()) LyricRendererFX.setProgress(v / 1000.0);
            }
        });

        // ★ 进度条已播放部分淡蓝色填充
        setupSliderTrackFill(progressSlider);

        // ★ 垂直排列：时间在上，进度条在下
        VBox bottomArea = new VBox(2);
        bottomArea.setAlignment(Pos.CENTER);
        bottomArea.setPadding(new Insets(0, 4, 0, 4));
        bottomArea.getChildren().addAll(timeBox, progressSlider);

        // ★ 标题与封面之间的固定间隔
        Region titleGap = new Region();
        titleGap.setPrefHeight(4);

        left.getChildren().addAll(info, titleGap, leftCoverFrame, controls, bottomArea);

        if (leftCoverImg.get() != null) {
            leftCover.setOpacity(0);
            Timeline fade = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(leftCover.opacityProperty(), 0, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.millis(260), new KeyValue(leftCover.opacityProperty(), 1.0, Interpolator.EASE_OUT))
            );
            fade.play();
        }
        return left;
    }

    /**
     * ★ 拖动/点击 Slider 后跳转到目标位置
     */
    private void seekFromSlider() {
        if (lastKnownTotal <= 0 || audioPlayer == null) return;
        double targetMs = progressSlider.getValue();
        leftTime.setText(formatTime(targetMs) + " / " + formatTime(lastKnownTotal));
        try {
            audioPlayer.seekTo(targetMs);
        } catch (Exception e) {
            System.out.println("[seek] 跳转失败: " + e.getMessage());
        }
    }

    private void setCoverImage(Image img) {
        if (img == null) {
            leftCoverImg.set(null);
            leftCover.setViewport(null);
            return;
        }
        double srcW = img.getWidth();
        double srcH = img.getHeight();
        if (srcW > 0 && srcH > 0) {
            double wantW = srcH * 4.0 / 3.0;
            double regionW = wantW;
            if (regionW > srcW) regionW = srcW;
            int ix = (int) Math.round((srcW - regionW) / 2.0);
            int iy = 0;
            int rw = (int) Math.round(regionW);
            int rh = (int) Math.round(srcH);
            leftCover.setViewport(new Rectangle2D(ix, iy, rw, rh));
        } else {
            leftCover.setViewport(null);
        }
        leftCoverImg.set(img);
    }

    private void loadAndSetCover(String bvid) {
        if (bvid == null || bvid.isEmpty()) {
            setCoverImage(null);
            leftCoverBvid = null;
            if (lyricView != null) lyricView.setLyric(null);
            return;
        }
        leftCoverBvid = bvid;
        setCoverImage(null);

        new Thread(() -> {
            String picUrl = fetchVideoCoverPic(bvid);
            if (picUrl == null) return;
            final String fbvid = bvid;
            Image img = new Image(picUrl, 0, 0, false, true, true);
            Platform.runLater(() -> {
                if (!fbvid.equals(leftCoverBvid)) return;
                setCoverImage(img);
            });
        }).start();
    }

    /**
     * 为当前曲目加载歌词。必须在 leftTitle 更新之后调用：
     * 各处都是先 loadAndSetCover 再 setText(leftTitle)，
     * 若在 loadAndSetCover 里取标题，拿到的是上一首的（首次播放时甚至是 BV 号）。
     *
     * <p>用视频标题而非 BV 号去搜：BiliUidLyricService 是按歌名去各音乐平台匹配的，
     * B 站视频标题常带"【翻唱】""(Live)"之类噪音，成功率低于完美但显著优于 BV 号。
     */
    private void loadLyricForCurrent() {
        if (lyricView == null || currentBvid == null) return;

        String query;
        // 用逻辑歌名（非 Unicode 数学字形）去搜，否则特殊字符会导致搜不到
        String title = currentResolvedTitle;
        boolean hasTitle = title != null && !title.isBlank()
                && !title.equals(currentBvid) && !title.equals("加载中...");

        if (hasTitle) {
            // 必须清洗后再搜：BiliUidLyricService 只在 BV/av/URL/UID 分支调用 cleanTitle，
            // 传纯歌名时是原样拿去搜的。B站标题常带"【翻唱】""(Live)""官方MV"之类噪音，
            // 不清洗的话几乎搜不到。
            String cleaned = BiliUidLyricService.cleanTitle(title);
            query = (cleaned == null || cleaned.isBlank()) ? title : cleaned;
            // 书名号/方括号里的内容才是真曲名，若清洗结果仍是整句则退回原标题
            if (query.equals(title)) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("《([^》]+)》").matcher(title);
                if (m.find()) query = m.group(1).trim();
            }
        } else {
            query = currentBvid;
        }
        currentLyricQuery = query;
        // 切到下一首时，新歌词由 LyricView 从最底部向上缓动滚入（换歌转场在 LyricView 内处理），
        // 不再硬跳到顶部。
        lyricView.loadLyric(query, lyricSourceOrder);
        // 偏移已烘焙进该首歌的本地歌词文件；这里把“当前偏移”回显为文件里记录的累计偏移，
        // 不再盲目清零，否则切歌/重启后面板上会显示 +0.00s 而歌词其实已偏移。
        lyricSessionOffset = lyricView.getAppliedOffset();
        updateOffsetLabel();
        // 切歌后无条件刷新来源列表：无论设置面板开/关都更新后台数据，
        // 面板开着立即看到新来源，关着时下次打开也已是新曲目数据。
        refreshLyricSources();
    }

    // ==================== 播放页歌名编辑 ====================
    /** 歌名存储键：B站视频用 bvid，内置曲目用 "song:<原标题>" */
    private String titleStorageKey(String bvid, String originalTitle) {
        if (bvid != null && !bvid.isEmpty()) return bvid;
        return "song:" + (originalTitle == null ? "" : originalTitle);
    }
    /** 返回应显示的歌名：有自定义则优先，否则原标题 */
    private String resolveTitle(String bvid, String originalTitle) {
        if (originalTitle == null) return "";
        String v = customTitles.get(titleStorageKey(bvid, originalTitle));
        return v != null ? v : originalTitle;
    }
    /** 统一设置播放页大标题，并记住当前歌名的存储键/原值/显示值，供编辑与歌词搜索使用 */
    private void applyLeftTitle(String bvid, String originalTitle) {
        if (leftTitle.isFocused()) cancelTitleEdit();   // 切歌前若正在编辑，先还原
        currentTitleKey = titleStorageKey(bvid, originalTitle);
        currentOriginalTitle = originalTitle;
        currentResolvedTitle = resolveTitle(bvid, originalTitle);
        leftTitle.setText(currentResolvedTitle);
        leftTitle.setPrefColumnCount(Math.max(10, currentResolvedTitle.length() + 2));
        leftTitle.setEditable(currentTitleKey != null);
        applyLeftTitleFont();
    }
    /** 英文歌名用 Unicode 数学粗斜体显示，中文/混合歌名用系统默认字体；编辑态一律显示逻辑文字 */
    private void applyLeftTitleFont() {
        leftTitle.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 20));
        if (isEnglishTitle(currentResolvedTitle) && !leftTitle.isFocused()) {
            // 数学粗斜体靠字形本身，字体族不影响显示，仅固定字号字重
            String shown = toMathBoldItalic(currentResolvedTitle);
            if (!shown.equals(leftTitle.getText())) leftTitle.setText(shown);
        } else if (!currentResolvedTitle.equals(leftTitle.getText())) {
            // 非英文或正在编辑：展示逻辑文字（保证可正常编辑、歌词按原词搜索）
            leftTitle.setText(currentResolvedTitle);
        }
    }
    /** 把 ASCII 字母/数字转成 Unicode 数学粗斜体字形（digit 取粗体数字，标点/空格原样保留） */
    private static String toMathBoldItalic(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') sb.appendCodePoint(0x1D468 + (c - 'A'));
            else if (c >= 'a' && c <= 'z') sb.appendCodePoint(0x1D482 + (c - 'a'));
            else if (c >= '0' && c <= '9') sb.appendCodePoint(0x1D43F + (c - '0'));
            else sb.append(c);
        }
        return sb.toString();
    }
    /** 判断歌名是否为“纯英文”（含拉丁字母与常见标点，不含中日韩字符） */
    private static boolean isEnglishTitle(String s) {
        if (s == null || s.isBlank()) return false;
        int latin = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3040 && c <= 0x30FF)) {
                cjk++;                       // 中日韩（含汉字/假名/谚文）视为非英文
            } else if (Character.isLetter(c)) {
                latin++;
            }
        }
        return cjk == 0 && latin > 0;
    }
    /** 失焦/回车提交，Esc 取消。直接编辑 leftTitle 本身（无弹出输入框） */
    private void commitTitleEdit() {
        if (currentTitleKey == null || currentResolvedTitle == null) return;
        String newText = leftTitle.getText().trim();
        if (newText.equals(currentResolvedTitle)) return;        // 没改动，跳过
        if (newText.isEmpty()) { leftTitle.setText(currentResolvedTitle); return; }
        if (newText.equals(currentOriginalTitle)) {
            customTitles.remove(currentTitleKey);
            leftTitle.setText(currentOriginalTitle);
            currentResolvedTitle = currentOriginalTitle;
        } else {
            customTitles.put(currentTitleKey, newText);
            leftTitle.setText(newText);
            currentResolvedTitle = newText;
            if (currentFavVideo != null) currentFavVideo.title = newText;
            updatePlaylistRowTitle(currentTitleKey, newText);
        }
        applyLeftTitleFont();    // 改名后按新歌名（英文/中文）刷新字体
        saveSettings();
        loadLyricForCurrent();   // 用修改后的歌名重新搜歌词
    }
    private void cancelTitleEdit() {
        if (currentResolvedTitle != null) {
            leftTitle.setText(currentResolvedTitle);
            applyLeftTitleFont();   // 还原为数学粗斜体 / 系统默认显示
        }
    }
    /** 同步更新左侧播放列表里对应行的歌名 */
    private void updatePlaylistRowTitle(String bvid, String newTitle) {
        if (listContainer == null) return;
        for (var node : listContainer.getChildren()) {
            if (!(node instanceof Node n)) continue;
            if (bvid.equals(n.getProperties().get("bvid"))) {
                Object lbl = n.getProperties().get("titleLabel");
                if (lbl instanceof Label l) l.setText(newTitle);
            }
        }
    }

    /**
     * 按"播放页显示"设置切换右侧区域内容：歌词 / 播放列表。
     *
     * <p>歌词模式下才让 LyricView 跑动画（它内部有个 15fps 的滚动缓动循环），
     * 切到播放列表就停掉，不做无用功。
     */
    private void applyPlayPageDisplay() {
        boolean showLyric = "歌词".equals(playPageDisplay);
        if (rightBorderPane == null) return;
        lyricModeActiveProp.set(showLyric);

        if (rightBorderPane != null) {
            // 右侧面板保持透明，淡蓝由常驻 bgBlue 层提供（随「浅蓝背景不透明度」实时变化）；
            // 歌词模式同理交给 bgBlue，整片铺蓝且同样受不透明度控制，避免把后台蓝层完全盖死
            rightBorderPane.setStyle("-fx-background-color:transparent;");
            // 歌词模式下让歌词区顶到上下边缘：去掉右侧面板与歌词层的上下内边距（保留左右），
            // 否则顶部标题占位 + 两层内边距会让歌词区上下都离边一截，像被截掉
            rightBorderPane.setPadding(showLyric ? new Insets(0, 12, 0, 10) : new Insets(12, 12, 12, 10));
        }
        if (playlistLayer != null) {
            playlistLayer.setVisible(!showLyric);
            playlistLayer.setManaged(!showLyric);
            playlistLayer.setMouseTransparent(showLyric);
        }
        if (rightLyricLayer != null) {
            rightLyricLayer.setVisible(showLyric);
            rightLyricLayer.setManaged(showLyric);
            // 歌词层自身的内边距也去掉，让歌词画布真正铺到上下边缘
            rightLyricLayer.setPadding(showLyric ? Insets.EMPTY : new Insets(10));
        }
        if (lyricGearBtn != null) {
            lyricGearBtn.setVisible(showLyric);
            lyricGearBtn.setManaged(showLyric);
        }
        // 切到列表模式时关闭歌词设置独立窗口
        if (!showLyric && lyricSettingsStage != null && lyricSettingsStage.isShowing()) {
            lyricSettingsStage.hide();
        }
        if (playlistTitleLabel != null) {
            if (showLyric) {
                // 歌词模式：清空标题并取消占位，把顶部空间让给歌词区
                playlistTitleLabel.setText("");
                playlistTitleLabel.setManaged(false);
                playlistTitleLabel.setVisible(false);
            } else {
                playlistTitleLabel.setText(syncedFolderTitle != null ? syncedFolderTitle : "暂无歌单");
                playlistTitleLabel.setManaged(true);
                playlistTitleLabel.setVisible(true);
            }
        }

        Node current = rightBorderPane.getCenter();
        Node target = showLyric ? rightLyricLayer : playlistLayer;
        if (target != null && current != target) {
            rightBorderPane.setCenter(target);
        }

        if (lyricView == null) return;
        if (showLyric) {
            lyricView.start();
            if (!lyricView.hasLyric()) loadLyricForCurrent();
        } else {
            lyricView.pause();
        }
    }

    // ===================== 歌词设置面板 =====================

    /** 构建歌词设置面板（齿轮按钮点击后浮动在窗口右侧展开；“可选来源”可收起、列表内部可滚动） */
    private VBox buildLyricSettingsPanel() {
        // 浮动在歌词区右侧的卡片：固定宽度，整体高度按内容自适应
        // 使用与主窗口一致的倒角 RADIUS + 透明背景窗口，呈现圆角
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(0));
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);
        panel.setMaxSize(340, Double.MAX_VALUE);
        panel.getStyleClass().add("lyric-panel");

        // 头部（无关闭按钮：由齿轮按钮再次点击关闭）
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        lyricTitleLabel = new Label("歌词设置");
        lyricTitleLabel.getStyleClass().add("lyric-title");
        header.getChildren().add(lyricTitleLabel);

        // 时间偏移
        VBox offsetCard = makeCard("时间偏移（调整后直接保存完整歌词）");
        HBox offsetRow = new HBox(10);
        offsetRow.setAlignment(Pos.CENTER_LEFT);
        Button minusBtn = new Button("− 0.25s");
        Button plusBtn = new Button("+ 0.25s");
        stylePillBtn(minusBtn);
        stylePillBtn(plusBtn);
        lyricOffsetLabel = new Label("当前偏移 +0.00s");
        lyricOffsetLabel.getStyleClass().add("lyric-sub");
        HBox.setHgrow(lyricOffsetLabel, Priority.ALWAYS);
        minusBtn.setOnAction(e -> {
            lyricSessionOffset -= 0.25;
            try { if (lyricView != null) lyricView.applyOffset(250); } catch (Exception ex) { ex.printStackTrace(); }
            if (useLyricRendererFX && LyricRendererFX.isShowing()) LyricRendererFX.applyOffset(0.25);
            updateOffsetLabel();
        });
        plusBtn.setOnAction(e -> {
            lyricSessionOffset += 0.25;
            try { if (lyricView != null) lyricView.applyOffset(-250); } catch (Exception ex) { ex.printStackTrace(); }
            if (useLyricRendererFX && LyricRendererFX.isShowing()) LyricRendererFX.applyOffset(-0.25);
            updateOffsetLabel();
        });
        offsetRow.getChildren().addAll(minusBtn, lyricOffsetLabel, plusBtn);
        offsetCard.getChildren().add(offsetRow);

        // 可选来源：整块可收起（点标题）；列表内部可滚动。
        // 候选变多后不再把面板无限撑高，底部内容也不会被裁掉够不到。
        VBox sourceCard = new VBox(10);
        sourceCard.getStyleClass().add("lyric-card");
        Label sourceTitle = new Label("可选来源（展开查看，选用后保存完整歌词）");
        sourceTitle.getStyleClass().add("lyric-card-title");
        Label sourceArrow = new Label("▾");
        sourceArrow.getStyleClass().add("lyric-arrow");
        HBox sourceHead = new HBox(6, sourceTitle, sourceArrow);
        sourceHead.setAlignment(Pos.CENTER_LEFT);
        sourceHead.setCursor(Cursor.HAND);
        sourceCard.getChildren().add(sourceHead);

        lyricSourceList = new VBox(8);
        ScrollPane sourceScroll = new ScrollPane(lyricSourceList);
        lyricSourceScroll = sourceScroll;   // 供 fitLyricSettingsStage 按主窗口高度收缩列表区
        sourceScroll.setFitToWidth(true);
        sourceScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sourceScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sourceScroll.setPannable(true);
        sourceScroll.setPrefHeight(SOURCE_LIST_MAX_H);
        sourceScroll.setMinHeight(60);
        sourceScroll.setMaxHeight(SOURCE_LIST_MAX_H);
        attachSmoothVerticalScroll(sourceScroll);
        sourceScroll.setStyle("-fx-background-color: transparent;");
        VBox sourceBody = new VBox(0);
        sourceBody.getChildren().add(sourceScroll);
        sourceCard.getChildren().add(sourceBody);

        // 收起/展开：箭头旋转 + 高度动画（复用 animateMaxHeight）
        final boolean[] srcExpanded = {true};
        final DoubleProperty srcRot = new SimpleDoubleProperty(90);
        sourceArrow.rotateProperty().bind(srcRot);
        final Timeline srcTl = new Timeline();
        sourceHead.setOnMouseClicked(e -> {
            srcExpanded[0] = !srcExpanded[0];
            srcRot.set(srcExpanded[0] ? 90 : 0);
            srcTl.getKeyFrames().setAll(new KeyFrame(Duration.millis(200),
                    new KeyValue(srcRot, srcExpanded[0] ? 90.0 : 0.0, Interpolator.EASE_OUT)));
            srcTl.play();
            animateMaxHeight(sourceBody, srcExpanded[0]);
        });

        // 导入
        VBox importCard = makeCard("");
        Button importBtn = new Button("导入歌词文件 (.lrc / .txt)");
        importBtn.getStyleClass().add("lyric-import");
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setCursor(Cursor.HAND);
        attachTapFeedback(importBtn);
        importBtn.setOnAction(e -> importLyricFile());
        importCard.getChildren().add(importBtn);

        VBox content = new VBox(12, offsetCard, sourceCard, importCard);
        // 只有“可选来源”内部用 ScrollPane 滚动，其余内容直接全显示
        panel.getChildren().addAll(header, content);
        return panel;
    }

    private VBox makeCard(String title) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(0));
        card.getStyleClass().add("lyric-card");
        if (title != null && !title.isEmpty()) {
            Label t = new Label(title);
            t.getStyleClass().add("lyric-card-title");
            card.getChildren().add(t);
        }
        return card;
    }

    private void stylePillBtn(Button b) {
        b.getStyleClass().add("lyric-pill");
        b.setCursor(Cursor.HAND);
        attachTapFeedback(b);
    }

    /** 点击缩放/微透明的丝滑反馈（hover 放大、press 缩小） */
    private void attachTapFeedback(javafx.scene.Node n) {
        n.setOnMouseEntered(e -> scaleTo(n, 1.02, 90));
        n.setOnMouseExited(e -> scaleTo(n, 1.0, 90));
        n.setOnMousePressed(e -> scaleTo(n, 0.98, 70));
        n.setOnMouseReleased(e -> scaleTo(n, 1.02, 70));
    }

    private void scaleTo(javafx.scene.Node n, double s, int ms) {
        ScaleTransition st = new ScaleTransition(Duration.millis(ms), n);
        st.setToX(s);
        st.setToY(s);
        st.play();
    }

    private void updateOffsetLabel() {
        if (lyricOffsetLabel != null) {
            lyricOffsetLabel.setText(String.format("当前偏移 %+.2fs", lyricSessionOffset));
        }
    }

    private void toggleLyricSettings() {
        if (lyricSettingsStage != null && lyricSettingsStage.isShowing()) {
            closeLyricSettings();
        } else {
            openLyricSettings();
        }
    }

    private void openLyricSettings() {
        if (primaryStage == null) return;
        // 设置面板只构建一次（单例）：浮动窗口 lyricSettingsStage 在首次打开时
        // new Scene(lyricSettingsPanel)，之后复用同一实例，避免切回播放页重建页面后
        // 窗口仍指向旧面板、导致来源列表不刷新、偏移按钮作用不到当前 LyricView。
        if (lyricSettingsPanel == null) lyricSettingsPanel = buildLyricSettingsPanel();
        // 注意：不再在此处重置 lyricSessionOffset，否则每次打开面板都会把已保存的偏移显示清零。
        // 偏移已写入歌词文件，这里只更新标签以反映当前累计值。
        updateOffsetLabel();
        refreshLyricSources();

        if (lyricSettingsStage == null) {
            lyricSettingsStage = new Stage();
            lyricSettingsStage.initOwner(primaryStage);
            // 非模态：不挡住主窗口交互
            lyricSettingsStage.initModality(Modality.NONE);
            // 与主窗口一致的透明窗口：圆角由面板 CSS 绘制，无系统标题栏/关闭按钮
            lyricSettingsStage.initStyle(StageStyle.TRANSPARENT);
            // 附加到主窗口：锁定大小、锁定位置，跟随主窗口移动
            lyricSettingsStage.setResizable(false);
            lyricSettingsStage.setScene(new Scene(lyricSettingsPanel));
            lyricSettingsStage.getScene().setFill(Color.TRANSPARENT);
            // 套用统一蓝白样式（与主窗口同一套 CSS，保持一致）
            try {
                lyricSettingsStage.getScene().getStylesheets()
                        .add(getClass().getResource("/pcl_style.css").toExternalForm());
            } catch (Exception ignored) {}
            lyricSettingsStage.setWidth(340);
            lyricSettingsStage.sizeToScene();   // 以完整内容尺寸显示，不裁剪、不滚动
            // 不设置 alwaysOnTop：作为主窗口的所属子窗口(initOwner)，默认显示在主窗口之上，
            // 但不会强制压住其它应用程序窗口，避免“叠加在所有窗口顶层”的问题。
            // 主窗口移动/缩放时，设置窗口同步贴在其右侧（位置锁定，不独立移动）
            primaryStage.xProperty().addListener((o, ov, nv) -> { followPrimary(); updateLyricTitleContrast(); });
            primaryStage.yProperty().addListener((o, ov, nv) -> { followPrimary(); updateLyricTitleContrast(); });
            primaryStage.widthProperty().addListener((o, ov, nv) -> followPrimary());
        }
        // 沿主窗口最右边向右延伸出去：x 贴主窗口右缘 + 小间隙，y 与主窗口对齐
        // 直接显示，不做渐入/滑入动画（仿设置页的即时切换）
        double targetX = primaryStage.getX() + primaryStage.getWidth() + 6;
        double targetY = primaryStage.getY();
        lyricSettingsStage.setX(targetX);
        lyricSettingsStage.setY(targetY);
        lyricSettingsStage.setOpacity(1);
        lyricSettingsPanel.setScaleX(1);
        lyricSettingsPanel.setScaleY(1);
        lyricSettingsStage.show();
        // 标题按背后实际背景自适应黑白：先立即采样（强制绕过节流，避免停在旧色），
        // 再延迟一小段兜底重采，应对窗口刚就位/定位的瞬间，且不会被节流吞掉
        lastContrastSample = 0;
        updateLyricTitleContrast();
        new Timeline(new KeyFrame(Duration.millis(160), e -> updateLyricTitleContrast())).play();
    }

    /**
     * 根据标题背后真实屏幕像素的亮度，自动把“歌词设置”设为深色或白色，保证在任意背景下可读。
     * 采样舞台顶部中央的纯背景区域（标题上方），避开文字本身。
     */
    private void updateLyricTitleContrast() {
        if (lyricTitleLabel == null || lyricSettingsStage == null || !lyricSettingsStage.isShowing()) return;
        long now = System.currentTimeMillis();
        if (now - lastContrastSample < 120) return;   // 节流，避免频繁截图
        lastContrastSample = now;
        try {
            if (screenRobot == null) screenRobot = new java.awt.Robot();
            int sx = (int) (lyricSettingsStage.getX() + lyricSettingsStage.getWidth() / 2);
            int sy = (int) lyricSettingsStage.getY() + 1;   // 标题上方纯背景区，避开文字
            java.awt.image.BufferedImage img =
                    screenRobot.createScreenCapture(new java.awt.Rectangle(sx - 3, sy, 6, 4));
            long sum = 0;
            int n = img.getWidth() * img.getHeight();
            for (int i = 0; i < img.getWidth(); i++)
                for (int j = 0; j < img.getHeight(); j++) {
                    int rgb = img.getRGB(i, j);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    sum += (0.299 * r + 0.587 * g + 0.114 * b);
                }
            double lum = sum / (double) Math.max(n, 1);
            // 浅背景（亮）→ 深色字；深背景（暗）→ 白色字（平滑过渡）
            setTitleFillSmooth(lum > 140 ? Color.web("#1F2937") : Color.WHITE);
        } catch (Throwable t) {
            // 截图失败（无头/无权限等）则维持默认深色，不影响功能
        }
    }

    /** 平滑地把标题颜色从当前值过渡到目标色，避免黑白突兀跳变 */
    private void setTitleFillSmooth(Color target) {
        if (lyricTitleLabel == null || target == null) return;
        if (target.equals(lyricTitleColor)) return;
        final Color from = lyricTitleColor;
        final Color to = target;
        lyricTitleColor = target;
        if (lyricTitleAnim != null) lyricTitleAnim.stop();
        final DoubleProperty p = new SimpleDoubleProperty(0);
        p.addListener((o, ov, nv) -> {
            double t = nv.doubleValue();
            double r = from.getRed()   + (to.getRed()   - from.getRed())   * t;
            double g = from.getGreen() + (to.getGreen() - from.getGreen()) * t;
            double b = from.getBlue()  + (to.getBlue()  - from.getBlue())  * t;
            double a = from.getOpacity()+ (to.getOpacity()- from.getOpacity())* t;
            lyricTitleLabel.setTextFill(new Color(r, g, b, a));
        });
        lyricTitleAnim = new Timeline(
                new KeyFrame(Duration.millis(220), new KeyValue(p, 1.0, Interpolator.EASE_OUT)));
        lyricTitleAnim.play();
    }

    private void closeLyricSettings() {
        if (lyricSettingsStage == null || !lyricSettingsStage.isShowing()) {
            return;
        }
        lyricSettingsStage.hide();
    }

    // 拖动主窗口时的惯性跟随：用弹簧式动画平滑追到目标位置（类似“MC 的手”的延迟感）
    private final DoubleProperty followTargetX = new SimpleDoubleProperty();
    private final DoubleProperty followTargetY = new SimpleDoubleProperty();
    private DoubleProperty followX;
    private DoubleProperty followY;
    private Timeline followAnim;
    private boolean followInit = false;

    /** 设置窗口始终贴附在主窗口最右边（移动过程中即连续跟随，带轻微惯性延迟） */
    private void followPrimary() {
        if (lyricSettingsStage == null || !lyricSettingsStage.isShowing() || primaryStage == null) return;
        // 实时更新目标：主窗口一移动就更新，动画持续朝目标逼近，拖动过程中即跟随
        followTargetX.set(primaryStage.getX() + primaryStage.getWidth() + 6);
        followTargetY.set(primaryStage.getY());
        if (!followInit) {
            followX = new SimpleDoubleProperty(lyricSettingsStage.getX());
            followY = new SimpleDoubleProperty(lyricSettingsStage.getY());
            followX.addListener((o, ov, nv) -> lyricSettingsStage.setX(nv.doubleValue()));
            followY.addListener((o, ov, nv) -> lyricSettingsStage.setY(nv.doubleValue()));
            // 只在首次启动一次持续动画，之后只改目标值 —— 避免每次事件重启导致"动完才开始"
            followAnim = new Timeline(new KeyFrame(Duration.millis(16), e -> {
                double nx = followX.get() + (followTargetX.get() - followX.get()) * 0.35;
                double ny = followY.get() + (followTargetY.get() - followY.get()) * 0.35;
                followX.set(nx);
                followY.set(ny);
            }));
            followAnim.setCycleCount(Timeline.INDEFINITE);
            followAnim.play();
            followInit = true;
        }
    }

    /**
     * 歌词加载完后让窗口按内容高度自适应（宽度固定 340，不横向加宽），确保完整显示。
     * <p>
     * 关键：浮窗贴在主窗口右侧、顶端与主窗口对齐，所以<b>底部不能超出主窗口的下边缘</b>。
     * 「可选来源」列表展开后浮窗会变高（自然高度约 530px > 主窗口 435px），
     * 超出部分由列表区吸收 —— 列表区内部本来就能滚动，所以内容不会丢，
     * 只是列表可视高度变小，展开时也不会再伸到主窗口外面去。
     */
    private void fitLyricSettingsStage() {
        if (lyricSettingsStage == null || !lyricSettingsStage.isShowing()) return;
        lyricSettingsStage.setWidth(340);          // 宽度锁定，防止横向加宽

        // 列表区可用的最大高度 = 主窗口可见高度 - 固定部分（标题/时间偏移/导入卡/间距等）
        if (primaryStage != null && lyricSourceScroll != null && lyricSettingsPanel != null) {
            lyricSettingsPanel.applyCss();   // 让 prefHeight 带上 CSS 里的内边距
            double cap = primaryStage.getHeight() - SHADOW_M;          // 与主窗口可见下边缘对齐
            double natural = lyricSettingsPanel.prefHeight(340);      // 列表完全展开时的自然高度
            double fixed = natural - lyricSourceScroll.getPrefHeight(); // 除列表区外的固定高度
            double want = Math.max(SOURCE_LIST_MIN_H, Math.min(SOURCE_LIST_MAX_H, cap - fixed));
            lyricSourceScroll.setPrefHeight(want);
            lyricSourceScroll.setMaxHeight(want);
        }

        // 即时贴合内容高度（不做平滑过渡动画）
        lyricSettingsStage.sizeToScene();
        double h = lyricSettingsStage.getHeight();
        if (h < 200) h = 200;
        if (h > 760) h = 760;
        lyricSettingsStage.setHeight(h);
    }

    /** 拉取各来源的前 N 首匹配歌词候选并展示（后台线程，避免卡 UI） */
    private void refreshLyricSources() {
        if (lyricSourceList == null) return;
        lyricSourceList.getChildren().clear();
        final String q = currentLyricQuery;
        if (q == null || q.isBlank()) {
            Label tip = new Label("当前没有可查询的曲目");
            tip.getStyleClass().add("lyric-loading");
            lyricSourceList.getChildren().add(tip);
            return;
        }
        Label loading = new Label("正在从各来源获取歌词...");
        loading.getStyleClass().add("lyric-loading");
        lyricSourceList.getChildren().add(loading);
        // 每次拉取递增序号；只采用最新一次的结果，丢弃过期（重复开关面板）请求
        final int token = ++lyricSourceFetchToken;
        // 当前已加载的歌词（主界面展示的那份）；在线来源拉不到时作为兜底展示，保证列表不空
        final List<LyricView.LyricLine> currentLines =
                (lyricView != null) ? lyricView.getLines() : null;
        new Thread(() -> {
            Map<String, List<BiliUidLyricService.LyricCandidate>> map = null;
            try {
                map = BiliUidLyricService.fetchAllCandidates(q, SOURCE_CANDIDATE_COUNT);
            } catch (Exception ex) {
                // 拉取失败也要兜底，绝不能让列表永远停在“加载中”
                map = null;
            }
            final Map<String, List<BiliUidLyricService.LyricCandidate>> result = map;
            Platform.runLater(() -> {
                if (token != lyricSourceFetchToken) return;   // 已有更新的请求，忽略本次旧结果
                lastCandidateMap = result;
                buildSourceRows(result, currentLines);
            });
        }).start();
    }

    /**
     * 渲染来源列表（每个来源下嵌套展示其候选歌曲）。
     * @param map          在线拉取到的各来源候选（可能为 null / 空）
     * @param currentLines 当前已加载的歌词（兜底：在线拉不到时至少展示当前这一份）
     */
    private void buildSourceRows(Map<String, List<BiliUidLyricService.LyricCandidate>> map,
                                 List<LyricView.LyricLine> currentLines) {
        if (lyricSourceList == null) return;
        lyricSourceList.getChildren().clear();

        int liveCount = 0;
        if (map != null) {
            for (Map.Entry<String, List<BiliUidLyricService.LyricCandidate>> en : map.entrySet()) {
                String src = en.getKey();
                List<BiliUidLyricService.LyricCandidate> cands = en.getValue();
                if (cands == null || cands.isEmpty()) continue;
                lyricSourceList.getChildren().add(buildSourceRow(src,
                        BiliUidLyricService.SOURCE_LABEL.getOrDefault(src, src), cands, false));
                liveCount++;
            }
        }

        if (liveCount == 0) {
            // 在线来源为空：先放当前已加载的歌词（作为一个候选），保证列表不为空
            if (currentLines != null && !currentLines.isEmpty()) {
                List<BiliUidLyricService.LyricCandidate> cur = new ArrayList<>();
                cur.add(new BiliUidLyricService.LyricCandidate(null, "当前歌词（已加载）", toServiceLines(currentLines)));
                lyricSourceList.getChildren().add(buildSourceRow("__current__", "当前歌词（已加载）", cur, false));
            } else {
                Label tip = new Label("未找到任何来源歌词");
                tip.getStyleClass().add("lyric-loading");
                lyricSourceList.getChildren().add(tip);
            }
            // 再补上各平台“点击在线获取”入口，方便联网时直接切换
            for (String src : lyricSourceOrder) {
                if (!BiliUidLyricService.SOURCE_LABEL.containsKey(src)) continue;
                lyricSourceList.getChildren().add(
                        buildSourceRow(src, BiliUidLyricService.SOURCE_LABEL.get(src), null, true));
            }
        }
        fitLyricSettingsStage();
    }

    /** LyricView.LyricLine（秒）→ BiliUidLyricService.LyricLine（毫秒） */
    private List<BiliUidLyricService.LyricLine> toServiceLines(List<LyricView.LyricLine> src) {
        List<BiliUidLyricService.LyricLine> out = new ArrayList<>();
        for (LyricView.LyricLine l : src)
            out.add(new BiliUidLyricService.LyricLine(l.text, (long) (l.start * 1000), (long) (l.end * 1000)));
        return out;
    }

    /**
     * 构建单个来源行（可折叠，内部按候选歌曲分组）。
     * @param cands        该来源的候选列表；每个候选是“搜索该歌名匹配到的某一首歌”的歌词
     * @param fetchOnDemand true 表示在线未拉到，按钮变为“获取”，点击时实时联网拉取该来源的候选
     */
    private VBox buildSourceRow(String src, String label, List<BiliUidLyricService.LyricCandidate> cands,
                                boolean fetchOnDemand) {
        boolean has = cands != null && !cands.isEmpty();
        VBox row = new VBox(0);

        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("lyric-row-head");
        Label arrow = new Label("▸");
        arrow.getStyleClass().add("lyric-arrow");
        Label name = new Label(label);
        name.getStyleClass().add("lyric-row-name");
        Label cnt = new Label(has ? ("共 " + cands.size() + " 个候选")
                                  : (fetchOnDemand ? "点击在线获取" : "无歌词"));
        cnt.getStyleClass().add("lyric-row-cnt");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button useBtn = new Button(fetchOnDemand ? "获取" : "展开");
        useBtn.getStyleClass().add("lyric-use");
        useBtn.setCursor(Cursor.HAND);
        head.getChildren().addAll(arrow, name, cnt, sp, useBtn);

        // 内容区：每个候选一组（歌名 - 歌手 + 预览 + 各自“使用”）
        VBox content = new VBox(6);
        content.getStyleClass().add("lyric-card");
        content.setPadding(new Insets(8, 10, 8, 14));
        content.setMaxHeight(0);   // 初始收起，动画展开
        if (has) {
            for (BiliUidLyricService.LyricCandidate c : cands) {
                content.getChildren().add(buildCandidateRow(c));
            }
        }
        // 裁剪，让展开/收起时平滑不溢出
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(content.widthProperty());
        clip.heightProperty().bind(content.heightProperty());
        content.setClip(clip);

        final boolean[] expanded = {false};
        // 箭头用旋转过渡（0°→90°，即 ▸ 变 ▾），比直接换文字自然；
        // 旋转绑定在属性上，动画不触发布局
        final DoubleProperty arrowRot = new SimpleDoubleProperty(0);
        arrow.rotateProperty().bind(arrowRot);
        final Timeline arrowTl = new Timeline();
        Runnable toggle = () -> {
            expanded[0] = !expanded[0];
            arrowRot.set(expanded[0] ? 90 : 0);
            arrowTl.getKeyFrames().setAll(new KeyFrame(Duration.millis(240),
                    new KeyValue(arrowRot, expanded[0] ? 90.0 : 0.0, Interpolator.EASE_OUT)));
            arrowTl.play();
            animateMaxHeight(content, expanded[0]);
        };
        head.setOnMouseClicked(e -> toggle.run());

        if (fetchOnDemand) {
            final String s = src;
            useBtn.setOnAction(e -> fetchSourceCandidatesAndShow(s));
        } else {
            useBtn.setDisable(!has);
            useBtn.setOnAction(e -> toggle.run());   // 有候选时按钮用于展开/收起
        }

        row.getChildren().addAll(head, content);
        return row;
    }

    /** 构建单个候选行：歌名 - 歌手 + 前几行预览 + “使用”按钮 */
    private VBox buildCandidateRow(BiliUidLyricService.LyricCandidate c) {
        VBox box = new VBox(2);

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(c.title != null ? c.title : "（未知歌曲）");
        title.getStyleClass().add("lyric-row-name");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button useBtn = new Button("使用");
        useBtn.getStyleClass().add("lyric-use");
        useBtn.setCursor(Cursor.HAND);
        top.getChildren().addAll(title, sp, useBtn);
        box.getChildren().add(top);

        // 预览前若干行（每候选只给几行，避免候选多时面板过长）
        int lim = Math.min(c.lines.size(), CANDIDATE_PREVIEW_LINES);
        for (int i = 0; i < lim; i++) {
            Label ln = new Label(c.lines.get(i).text);
            ln.getStyleClass().add("lyric-line-preview");
            ln.setMaxWidth(Double.MAX_VALUE);
            ln.setTextOverrun(OverrunStyle.ELLIPSIS);
            box.getChildren().add(ln);
        }
        Label more = new Label("…（共 " + c.lines.size() + " 行，点击“使用”应用）");
        more.getStyleClass().add("lyric-line-preview");
        more.setStyle(more.getStyle() + "-fx-font-style:italic;");
        box.getChildren().add(more);

        useBtn.setOnAction(e -> {
            if (lyricView == null) return;
            List<LyricView.LyricLine> conv = new ArrayList<>();
            for (BiliUidLyricService.LyricLine l : c.lines)
                conv.add(new LyricView.LyricLine(l.text, l.startMs / 1000.0, l.endMs / 1000.0));
            lyricView.saveLines(conv);
            closeLyricSettings();
        });
        return box;
    }

    /**
     * 展开/收起某节点的高度（真动画）。
     * 原实现是一次性 setMaxHeight——名字叫 animate 却并不动画，视觉上是"跳"的。
     * 这里逐帧插值高度，并同步调整歌词设置窗口尺寸，展开/收起都有缓动；
     * 连点时从当前动画值接着走，不会跳回 0。
     */
    private void animateMaxHeight(VBox node, boolean expand) {
        // 动画中的当前高度挂在节点上，供下一次动画取起点
        DoubleProperty cur = (DoubleProperty) node.getProperties().get("mhCur");
        if (cur == null) {
            cur = new SimpleDoubleProperty(node.getMaxHeight());
            node.getProperties().put("mhCur", cur);
            final VBox n = node;
            // 只装一次监听：避免反复调用时监听器累积；height 变化 → 同步 maxHeight 与窗口尺寸
            cur.addListener((obs, o, nv) -> {
                n.setMaxHeight(nv.doubleValue());
                fitLyricSettingsStage();
            });
        }
        Timeline old = (Timeline) node.getProperties().get("mhTL");
        if (old != null) old.stop();

        double to;
        if (!expand) {
            to = 0;
        } else {
            // 量完整高度：maxHeight 会夹住 prefHeight，折叠态（或动画途中）量到的都是当前值甚至 0，
            // 所以先放开到 MAX_VALUE 量一次，量完立刻恢复
            double curH = cur.get();
            node.setMaxHeight(Double.MAX_VALUE);
            double w = node.getWidth();
            to = node.prefHeight(w > 0 ? w : -1);
            node.setMaxHeight(curH);
        }
        double from = cur.get();
        if (Math.abs(from - to) < 0.5) { cur.set(to); return; }

        Timeline tl = new Timeline(new KeyFrame(Duration.millis(240),
                new KeyValue(cur, to, Interpolator.EASE_OUT)));
        node.getProperties().put("mhTL", tl);
        tl.play();
    }

    /** 点击“获取”：实时联网拉取该来源的前 N 首候选并重绘列表（不自动应用，由用户选一个“使用”） */
    private void fetchSourceCandidatesAndShow(String src) {
        final String q = currentLyricQuery;
        if (q == null || q.isBlank() || lyricView == null) return;
        final int token = ++lyricSourceFetchToken;
        final List<LyricView.LyricLine> currentLines = lyricView.getLines();
        new Thread(() -> {
            List<BiliUidLyricService.LyricCandidate> cands = null;
            try {
                cands = BiliUidLyricService.fetchSourceCandidates(q, src, SOURCE_CANDIDATE_COUNT);
            } catch (Exception ignore) {
                cands = null;
            }
            final List<BiliUidLyricService.LyricCandidate> res = cands;
            Platform.runLater(() -> {
                if (token != lyricSourceFetchToken) return;
                if (lastCandidateMap == null) lastCandidateMap = new LinkedHashMap<>();
                lastCandidateMap.put(src, res);
                buildSourceRows(lastCandidateMap, currentLines);
            });
        }).start();
    }

    private void importLyricFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("导入歌词文件");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("歌词文件", "*.lrc", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File f = fc.showOpenDialog(primaryStage);
        if (f == null) return;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (lyricView != null) lyricView.setLyricFromLrc(text);
            closeLyricSettings();
        } catch (Exception ex) {
            System.out.println("[lyric] 导入失败: " + ex.getMessage());
        }
    }

    private BorderPane createRightPlaylist() {
        BorderPane right = new BorderPane();
        // 透明：右侧淡蓝改由常驻 bgBlue 层提供（随页面切换左右滑动）
        right.setStyle("-fx-background-color:transparent;");
        right.setPadding(new Insets(12, 12, 12, 10));

        playlistTitleLabel = new Label();
        playlistTitleLabel.setText(syncedFolderTitle != null ? syncedFolderTitle : "暂无歌单");
        playlistTitleLabel.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:13px;-fx-font-weight:bold;");
        playlistTitleLabel.setPadding(new Insets(0, 0, 10, 4));

        // ---- 播放列表层 ----
        VBox tracksBox = new VBox(6);
        tracksBox.setPadding(new Insets(10));
        // 透明：右侧淡蓝由常驻 bgBlue 层提供，列表直接浮在背景上（不再画外圈边框卡片），
        // 这样「浅蓝背景不透明度」设置能直接作用在播放列表区域的背景上
        tracksBox.setStyle("-fx-background-color:transparent;");
        tracksBox.setAlignment(Pos.TOP_CENTER);

        listContainer = new VBox(4);
        listContainer.setAlignment(Pos.TOP_CENTER);

        playlistScroll = new ScrollPane(listContainer);
        playlistScroll.setFitToWidth(true);
        playlistScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        playlistScroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;-fx-border-color:transparent;");
        playlistScroll.setPadding(new Insets(0));
        VBox.setVgrow(playlistScroll, Priority.ALWAYS);
        setupScrollbarGradient(playlistScroll);
        attachSmoothVerticalScroll(playlistScroll);

        fillPlaylistInto(listContainer, playlistScroll);

        tracksBox.getChildren().add(playlistScroll);

        // ---- 歌词层 ----
        // 复用 LyricView：它本身就是一块纯歌词画布（节点池 + 外部驱动时间），
        // 放在右侧大区域比挤在左侧封面框里能多显示好几行，也不必再写一套渲染。
        // 整块淡蓝色背景，营造"歌词页"氛围（按需求：用歌词时右边全部淡蓝）。
        VBox lyricLayer = new VBox(6);
        lyricLayer.setPadding(new Insets(10));
        lyricLayer.setStyle("-fx-background-color:transparent;");
        lyricLayer.setAlignment(Pos.TOP_CENTER);

        Label lyricHint = new Label("暂无歌词");
        lyricHint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
        lyricHint.setMouseTransparent(true);
        lyricHint.setVisible(false);

        if (lyricView != null) {
            // 让歌词画布撑满右侧面板：StackPane 里 maxSize 为 MAX 即填满父节点
            lyricView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            // 歌词画布本身不需要响应点击，透传即可；但父层/齿轮/设置面板仍保持可交互
            lyricView.setMouseTransparent(true);
            lyricView.setFontSize(lyricFontSize);
            lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
            lyricView.setBold(lyricBold);
            lyricView.setHighlightColor(lyricHighlight);
            // 关键：空态提示只在该显示歌词、且确实没词时才出现。
            // 否则列表模式下它会被错误地叠在播放列表上方显示"暂无歌词"。
            lyricHint.visibleProperty().bind(Bindings.createBooleanBinding(
                    () -> lyricModeActiveProp.get() && !lyricView.hasLyric(),
                    lyricView.hasLyricProperty(), lyricModeActiveProp));
        }

        StackPane lyricBox = new StackPane();
        // 铺满歌词层，使齿轮的角落对齐是相对“整个右侧面板”而非内容小框，
        // 这样“到右边/下边等距”才有意义
        lyricBox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (lyricView != null) {
            // StackPane 不会把子节点拉伸填满，而是按子节点的 pref 尺寸居中。
            // 必须把歌词画布的 pref 显式绑定到面板尺寸，窗口缩放（尤其缩小）时它才会跟着缩小/重排，
            // 否则会出现塌缩错位、歌词溢出蓝色框。面板尺寸由右侧布局决定，与歌词自身无关，无自引用闭环。
            lyricView.prefWidthProperty().bind(lyricBox.widthProperty());
            lyricView.prefHeightProperty().bind(lyricBox.heightProperty());
            lyricBox.getChildren().add(lyricView);
        }
        lyricBox.getChildren().add(lyricHint);

        // 歌词设置按钮（齿轮）：仅在歌词模式下可见；点击展开悬浮设置面板
        lyricGearBtn = new Button("⚙");
        lyricGearBtn.setStyle("-fx-background-color:rgba(255,255,255,0.9);-fx-text-fill:" + C_PRIMARY +
                ";-fx-background-radius:50%;-fx-font-size:18px;-fx-font-weight:bold;-fx-padding:7 12;-fx-cursor:hand;" +
                "-fx-effect:dropshadow(gaussian,rgba(31,41,55,0.15),6,0,0,1);");
        lyricGearBtn.setCursor(Cursor.HAND);
        lyricGearBtn.setVisible(false);
        lyricGearBtn.setManaged(false);
        attachTapFeedback(lyricGearBtn);
        lyricGearBtn.setOnAction(e -> toggleLyricSettings());
        // 移到右下角，并使到右边与下边的距离相等
        StackPane.setAlignment(lyricGearBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(lyricGearBtn, new Insets(0, 4, 20, 0));
        lyricBox.getChildren().add(lyricGearBtn);

        // 歌词设置面板改为惰性单例：首次打开面板时再构建（见 openLyricSettings），
        // 避免每次切回播放页重建页面时产生“旧面板实例”导致来源列表刷新不到、偏移调不动。

        VBox.setVgrow(lyricBox, Priority.ALWAYS);
        lyricLayer.getChildren().add(lyricBox);

        // ---- 两层叠放，按设置切换可见 ----
        playlistLayer = tracksBox;
        rightLyricLayer = lyricLayer;
        rightBorderPane = right;

        right.setTop(playlistTitleLabel);
        right.setCenter(lyricModeActiveProp.get() ? lyricLayer : tracksBox);
        applyPlayPageDisplay();
        return right;
    }

    /**
     * 用当前播放列表填充播放页右侧列表（分页懒加载）。
     * 抽成方法是因为启动时收藏夹是异步加载的，数据到达后需要重新填充一次。
     */
    private void fillPlaylistInto(VBox container, ScrollPane scroll) {
        container.getChildren().clear();
        // 注意：列表数据常是异步到达、会二次调用本方法。歌词模式下标题已清空，
        // 这里不能把它又改回收藏夹名（如"单曲"），否则会覆盖 applyPlayPageDisplay 的设置
        if (playlistTitleLabel != null && !lyricModeActiveProp.get()) {
            playlistTitleLabel.setText(syncedFolderTitle != null ? syncedFolderTitle : "暂无歌单");
        }

        playlistData = getCurrentPlaylist();
        if (playlistData == null || playlistData.isEmpty()) {
            Label hint = new Label("请先在「歌单」页选择收藏夹");
            hint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
            hint.setPadding(new Insets(20, 0, 0, 0));
            container.getChildren().add(hint);
            playlistLoadedCount = 0;
            return;
        }

        playlistLoadedCount = 0;
        ensurePlaylistRowsLoaded(Math.min(VIDEO_PAGE_SIZE, playlistData.size()));

        // 该方法会被重复调用（启动时异步加载完收藏夹会再调一次），
        // 必须先摘掉旧的滚动监听，否则监听器累积会导致滚到底部时重复添加行
        if (playlistScrollListener != null) {
            scroll.vvalueProperty().removeListener(playlistScrollListener);
            playlistScrollListener = null;
        }
        playlistScrollListener = (obs, oldVal, newVal) -> {
            if (newVal.doubleValue() >= 0.95 && playlistLoadedCount < playlistData.size()) {
                ensurePlaylistRowsLoaded(Math.min(playlistLoadedCount + VIDEO_PAGE_SIZE, playlistData.size()));
            }
        };
        scroll.vvalueProperty().addListener(playlistScrollListener);
    }

    /**
     * 把播放页列表渲染到至少 targetCount 行。懒加载与"滚动到指定歌曲"共用同一套逻辑。
     */
    private void ensurePlaylistRowsLoaded(int targetCount) {
        if (playlistData == null || listContainer == null) return;
        int end = Math.min(targetCount, playlistData.size());
        for (int i = playlistLoadedCount; i < end; i++) {
            listContainer.getChildren().add(createBiliListRow(playlistData.get(i)));
        }
        if (end > playlistLoadedCount) playlistLoadedCount = end;
    }

    /**
     * 滚动播放页列表，让当前播放的歌曲显示在可视区中央。
     * 自动播放/切歌时列表可能还停在别处，不滚的话当前歌会在视野外。
     */
    private void scrollPlaylistToCurrent() {
        if (playlistScroll == null || listContainer == null) return;
        if (playlistData == null || playlistData.isEmpty() || currentBvid == null) return;

        int idx = -1;
        for (int i = 0; i < playlistData.size(); i++) {
            if (playlistData.get(i).bvid.equals(currentBvid)) { idx = i; break; }
        }
        if (idx < 0) return;

        // 目标行可能还没被渲染（懒加载只渲染了前 30 行），必须先补齐到它
        ensurePlaylistRowsLoaded(idx + 1);

        // idx 在 for 里被赋值，不是 effectively final，lambda 里得用它的副本
        final int targetIdx = idx;
        Platform.runLater(() -> {
            if (targetIdx >= listContainer.getChildren().size() || playlistScroll == null) return;
            Node row = listContainer.getChildren().get(targetIdx);

            // 强制布局，否则刚添加的行 boundsInParent 还是旧值/0
            listContainer.applyCss();
            listContainer.layout();

            double contentH = listContainer.getBoundsInLocal().getHeight();
            double viewportH = playlistScroll.getViewportBounds().getHeight();
            if (contentH <= viewportH || viewportH <= 0) return;  // 内容没超出可视区，无需滚动

            // 让目标行居中
            double rowCenter = row.getBoundsInParent().getMinY()
                    + row.getBoundsInParent().getHeight() / 2.0;
            double target = (rowCenter - viewportH / 2.0) / (contentH - viewportH);
            target = Math.max(0, Math.min(1, target));

            // 与项目其它动效保持一致，用 300ms 缓动画滚动
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(playlistScroll.vvalueProperty(), playlistScroll.getVvalue(), Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(300),
                            new KeyValue(playlistScroll.vvalueProperty(), target, Interpolator.EASE_BOTH))
            );
            tl.play();
        });
    }

    private String unescapeAndCleanTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) return "未知标题";
        Matcher uMatcher = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(rawTitle);
        StringBuffer sb = new StringBuffer();
        while (uMatcher.find()) {
            uMatcher.appendReplacement(sb, String.valueOf((char) Integer.parseInt(uMatcher.group(1), 16)));
        }
        uMatcher.appendTail(sb);
        String title = sb.toString();
        title = title.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .replace("\\t", " ")
                .replace("\\r", " ");
        Matcher bracket = Pattern.compile("《([^》]+)》").matcher(title);
        if (bracket.find()) {
            title = bracket.group(1).trim();
        }
        return title.trim().isEmpty() ? "未知标题" : title.trim();
    }

    private HBox createBiliListRow(FavVideo video) {
        HBox row = new HBox(8);
        row.setPadding(new Insets(6, 10, 6, 6));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color:transparent;");

        Region leftBar = new Region();
        leftBar.setPrefSize(3, 28);
        leftBar.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:2;");
        leftBar.setVisible(false);
        leftBar.setMouseTransparent(true);
        HBox.setMargin(leftBar, new Insets(0, 6, 0, 0));

        Label title = new Label(resolveTitle(video.bvid, video.title));
        title.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:13px;");
        HBox.setHgrow(title, Priority.ALWAYS);
        row.getChildren().addAll(leftBar, title);

        StackPane cell = new StackPane(row);
        cell.setPadding(new Insets(0));
        StackPane.setAlignment(row, Pos.CENTER_LEFT);

        StackPane hoverFrame = new StackPane();
        hoverFrame.setMinSize(0, 0);
        hoverFrame.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        hoverFrame.setBackground(new Background(
                new BackgroundFill(Color.web(C_PRIMARY, 0.10), new CornerRadii(6), Insets.EMPTY)
        ));
        hoverFrame.setBorder(new Border(
                new BorderStroke(Color.web(C_PRIMARY, 0.40), BorderStrokeStyle.SOLID, new CornerRadii(6), new BorderWidths(1))
        ));
        hoverFrame.setOpacity(0);
        hoverFrame.setTranslateX(2);
        hoverFrame.setTranslateY(2);
        hoverFrame.setMouseTransparent(true);
        cell.getChildren().add(hoverFrame);

        HBox wrapper = new HBox(cell);
        HBox.setHgrow(cell, Priority.ALWAYS);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setStyle("-fx-background-color:transparent;-fx-cursor:hand;");

        wrapper.getProperties().put("leftBar", leftBar);
        wrapper.getProperties().put("bvid", video.bvid);
        wrapper.getProperties().put("titleLabel", title);

        updateRowSelection(wrapper, video.bvid.equals(currentBvid));

        wrapper.setOnMouseEntered(e -> {
            Timeline in = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(hoverFrame.opacityProperty(), hoverFrame.getOpacity()),
                            new KeyValue(hoverFrame.translateXProperty(), hoverFrame.getTranslateX()),
                            new KeyValue(hoverFrame.translateYProperty(), hoverFrame.getTranslateY())),
                    new KeyFrame(Duration.millis(90),
                            new KeyValue(hoverFrame.opacityProperty(), 1.0),
                            new KeyValue(hoverFrame.translateXProperty(), 0.0),
                            new KeyValue(hoverFrame.translateYProperty(), 0.0))
            );
            in.play();
        });

        wrapper.setOnMouseExited(e -> {
            Timeline out = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(hoverFrame.opacityProperty(), hoverFrame.getOpacity()),
                            new KeyValue(hoverFrame.translateXProperty(), hoverFrame.getTranslateX()),
                            new KeyValue(hoverFrame.translateYProperty(), hoverFrame.getTranslateY())),
                    new KeyFrame(Duration.millis(160),
                            new KeyValue(hoverFrame.opacityProperty(), 0.0),
                            new KeyValue(hoverFrame.translateXProperty(), 2.0),
                            new KeyValue(hoverFrame.translateYProperty(), 2.0))
            );
            out.setOnFinished(ev -> hoverFrame.setOpacity(0));
            out.play();
        });

        wrapper.setOnMouseClicked(e -> {
            System.out.println("[playlist] 选择视频: " + video.title + " (" + video.bvid + ")");
            currentBvid = video.bvid;
            setCoverImage(null);
            loadAndSetCover(video.bvid);
            applyLeftTitle(video.bvid, video.title);
            currentFavVideo = video;
            leftArtist.setText(video.upName);
            loadLyricForCurrent();   // 必须在 setText 之后
            leftTime.setText("0:00 / " + video.durationStr());
            progressSlider.setMax(1); progressSlider.setValue(0);
            lastKnownTotal = 0; // ★ 重置
            setPlayIcon(true);

            pauseOnReady = false; // 用户主动播放，取消"就绪即暂停"
            if (audioPlayer != null) {
                audioPlayer.playBvid(video.bvid);
            }
            if (playNavButton != null) playNavButton.getToggle().setSelected(true);
            rememberPlayback(video.bvid);

            refreshRightPlaylistSelection();

            wrapper.setOpacity(0.6);
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(wrapper.opacityProperty(), 0.6)),
                    new KeyFrame(Duration.millis(220), new KeyValue(wrapper.opacityProperty(), 1.0, Interpolator.EASE_OUT))
            );
            tl.play();
        });

        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private void refreshRightPlaylistSelection() {
        if (listContainer == null) return;
        for (var node : listContainer.getChildren()) {
            if (node instanceof HBox row) {
                Object bar = row.getProperties().get("leftBar");
                Object bvid = row.getProperties().get("bvid");
                boolean match = currentBvid != null && currentBvid.equals(bvid);
                if (bar instanceof Region leftBar) {
                    leftBar.setVisible(match);
                }
                // 圆角 6 与外框 hoverFrame 的 CornerRadii(6) 保持一致
                row.setStyle("-fx-background-color:" + (match ? "#E8F3FF" : "transparent")
                        + ";-fx-background-radius:6;-fx-cursor:hand;");
            }
        }
    }

    private void updateRowSelection(HBox wrapper, boolean selected) {
        Object bar = wrapper.getProperties().get("leftBar");
        if (bar instanceof Region leftBar) {
            leftBar.setVisible(selected);
        }
        // 圆角 6 与外框 hoverFrame 的 CornerRadii(6) 保持一致
        wrapper.setStyle("-fx-background-color:" + (selected ? "#E8F3FF" : "transparent")
                + ";-fx-background-radius:6;-fx-cursor:hand;");
    }

    private void togglePlay() {
        // 注意：不能直接依赖 PlayCallback 的 onPaused/onResumed 来同步歌词。
        // BiliAudioPlayer 里只有 togglePause() 会触发这两个回调，
        // 而这里调的是 pause()/resume()（它们只操作 mediaPlayer、不触发任何回调），
        // 所以那两个回调实际上永远不会被调用。歌词的启停必须在这里显式控制。
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause();
            setPlayIcon(false);
            if (lyricView != null) lyricView.pause();
        } else {
            if (audioPlayer.getCurrentBvid() != null) {
                audioPlayer.resume();
                setPlayIcon(true);
                if (lyricView != null) lyricView.start();
            }
        }
    }

    private void setPlayIcon(boolean playing) {
        String target = playing ? "⏸" : "▶";
        if (target.equals(leftPlayBtn.getText())) return;
        leftPlayBtn.setText(target);
        leftPlayBtn.setScaleX(0.97);
        leftPlayBtn.setScaleY(0.97);
        leftPlayBtn.setOpacity(0.93);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(leftPlayBtn.scaleXProperty(), 0.97, Interpolator.EASE_OUT),
                        new KeyValue(leftPlayBtn.scaleYProperty(), 0.97, Interpolator.EASE_OUT),
                        new KeyValue(leftPlayBtn.opacityProperty(), 0.93, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(leftPlayBtn.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(leftPlayBtn.scaleYProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(leftPlayBtn.opacityProperty(), 1.0, Interpolator.EASE_OUT))
        );
        tl.play();
        updateCoverBreath();   // 播放/暂停时同步封面"呼吸"动画
    }

    private String currentBvid = null;

    private void playCurrentBvid(FavVideo video) {
        if (video == null || audioPlayer == null) return;
        currentBvid = video.bvid;
        loadAndSetCover(video.bvid);
        applyLeftTitle(video.bvid, video.title);
        currentFavVideo = video;
        leftArtist.setText(video.upName);
        leftTime.setText("0:00 / " + video.durationStr());
        loadLyricForCurrent();   // 必须在 setText 之后
        progressSlider.setMax(1); progressSlider.setValue(0);
        lastKnownTotal = 0; // ★ 重置
        setPlayIcon(true);

        pauseOnReady = false; // 用户主动播放，取消"就绪即暂停"
        audioPlayer.playBvid(video.bvid);
        rememberPlayback(video.bvid);

        refreshRightPlaylistSelection();
        scrollPlaylistToCurrent();
    }

    private List<FavVideo> getCurrentPlaylist() {
        if (lastSelectedFolder != null) {
            List<FavVideo> v = folderVideoCache.get(lastSelectedFolder);
            if (v != null && !v.isEmpty()) return v;
        }
        if (currentBvid != null) {
            for (List<FavVideo> v : folderVideoCache.values()) {
                if (v != null) {
                    for (FavVideo fv : v) {
                        if (fv.bvid.equals(currentBvid)) return v;
                    }
                }
            }
        }
        return (syncedVideos != null && !syncedVideos.isEmpty()) ? syncedVideos : null;
    }

    private void prevSong() {
        List<FavVideo> videos = getCurrentPlaylist();
        if (videos != null) {
            int idx = 0;
            for (int i = 0; i < videos.size(); i++) {
                if (videos.get(i).bvid.equals(currentBvid)) { idx = i; break; }
            }
            idx = (idx - 1 + videos.size()) % videos.size();
            playCurrentBvid(videos.get(idx));
            return;
        }
        currentIndex = (currentIndex - 1 + SONGS.length) % SONGS.length;
        syncLeftPlayerFromState();
    }

    /** 预取下一首（随机模式下再额外备一首随机候选），让切歌几乎无加载延迟，性能友好 */
    private void prefetchNext() {
        if (audioPlayer == null) return;
        List<FavVideo> videos = getCurrentPlaylist();
        if (videos == null || videos.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < videos.size(); i++) {
            if (videos.get(i).bvid.equals(currentBvid)) { idx = i; break; }
        }
        if (idx < 0) return;
        int next = (idx + 1) % videos.size();
        audioPlayer.prefetchBvid(videos.get(next).bvid);
        // 随机模式：多备一首随机候选，提高命中率（仅多一首，占用可控）
        if ("随机播放".equals(loopMode) && videos.size() > 1) {
            int r; do { r = (int) (Math.random() * videos.size()); } while (r == idx);
            audioPlayer.prefetchBvid(videos.get(r).bvid);
        }
    }

    private void nextSong() {
        List<FavVideo> videos = getCurrentPlaylist();
        if (videos != null) {
            int idx = 0;
            for (int i = 0; i < videos.size(); i++) {
                if (videos.get(i).bvid.equals(currentBvid)) { idx = i; break; }
            }
            idx = (idx + 1) % videos.size();
            playCurrentBvid(videos.get(idx));
            return;
        }
        currentIndex = (currentIndex + 1) % SONGS.length;
        syncLeftPlayerFromState();
    }

    // ==================== 播放设置：循环模式 ====================

    /** 一首播完后的处理（由 onStopped 在"自然播完"时调用） */
    private void handlePlaybackFinished() {
        // 单曲循环：重播当前这首
        if ("单曲循环".equals(loopMode)) {
            if (audioPlayer != null && currentBvid != null) {
                audioPlayer.playBvid(currentBvid);
                return;
            }
        }

        List<FavVideo> videos = getCurrentPlaylist();
        if (videos == null || videos.isEmpty()) {
            setPlayIcon(false);
            return;
        }

        int cur = -1;
        for (int i = 0; i < videos.size(); i++) {
            if (videos.get(i).bvid.equals(currentBvid)) { cur = i; break; }
        }

        int next;
        if ("随机播放".equals(loopMode)) {
            if (videos.size() == 1) {
                next = 0;
            } else {
                // 随机且不连续重复同一首
                do { next = (int) (Math.random() * videos.size()); } while (next == cur);
            }
        } else {
            // 列表循环（默认）：播到末尾回到第一首
            next = cur < 0 ? 0 : (cur + 1) % videos.size();
        }
        playCurrentBvid(videos.get(next));
    }

    // ==================== 歌单页 ====================
    // ==================== 歌单页：左侧三块导航 + 右侧内容区 ====================
    private HBox createFavPage() {
        HBox root = new HBox();

        VBox sidebar = favSidebarRef = createFavSidebar();
        sidebar.setMinWidth(180);
        sidebar.setMaxWidth(220);
        HBox.setHgrow(sidebar, Priority.NEVER);

        favContentArea = new StackPane();
        HBox.setHgrow(favContentArea, Priority.ALWAYS);
        // 透明：白底由底层 centerWhiteBg 提供（受白色底不透明度设置控制）
        favContentArea.setStyle("-fx-background-color:transparent;");
        // 启动时若恢复了上次播放的收藏夹，直接落到它所在的 tab，
        // 否则停在 tab 0 会用 tab 0 的第一个收藏夹把恢复结果覆盖掉
        int initialTab = (lastSelectedFolder != null && lastFavTab == 1) ? 1 : 0;
        switchFavTab(initialTab);

        root.getChildren().addAll(sidebar, favContentArea);
        return root;
    }

    private VBox createFavSidebar() {
        favNavCells.clear();

        VBox sidebar = new VBox(4);
        // 透明：左侧淡蓝由常驻 bgBlue 层提供（切到歌单页时停在此处）
        sidebar.setStyle("-fx-background-color:transparent;");
        sidebar.setPadding(new Insets(16, 8, 16, 12));

        String[] categories = {"创建的收藏夹", "我追的合集", "追番与追剧"};
        for (int i = 0; i < categories.length; i++) {
            sidebar.getChildren().add(createFavNavItem(categories[i], i));
        }

        Region flex = new Region();
        VBox.setVgrow(flex, Priority.ALWAYS);
        sidebar.getChildren().add(flex);

        return sidebar;
    }

    private StackPane createFavNavItem(String text, int index) {
        Label label = new Label(text);
        label.setFont(Font.font(Font.getDefault().getFamily(), index == 0 ? FontWeight.BOLD : FontWeight.NORMAL, 13));
        // 内联样式固定文字色，优先级高于任何 CSS，避免被意外覆盖成白色
        label.setStyle("-fx-text-fill:" + (index == 0 ? "#0B3D91" : C_TEXT) + ";");
        HBox.setHgrow(label, Priority.ALWAYS);

        Region leftBar = new Region();
        leftBar.setPrefSize(4, 10);          // 4px 细、10px 短高，圆角取半宽(2)即胶囊形
        leftBar.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:2;");
        leftBar.setOpacity(index == 0 ? 1 : 0);
        // 收起态保留 0.35 的高度而非 0：scaleY 以中心为轴，从中心横向展开比从一条线撑开自然
        leftBar.setScaleY(index == 0 ? 1 : 0.35);
        leftBar.setMouseTransparent(true);
        // 左边距压到 2，让竖条紧贴后面淡蓝背景的左边缘
        HBox.setMargin(leftBar, new Insets(0, 10, 0, 2));

        HBox row = new HBox(leftBar, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(36);
        row.setMaxHeight(36);
        row.setPadding(new Insets(0, 10, 0, 0));

        // 选中态的淡蓝背景：单独一层，用 opacity 做淡入淡出（原先是 setStyle 硬切，无法过渡）
        Region activeBg = new Region();
        activeBg.setStyle("-fx-background-color:#E8F3FF;-fx-background-radius:8;");
        activeBg.setOpacity(index == 0 ? 1 : 0);
        activeBg.setMouseTransparent(true);

        Region hoverBg = new Region();
        hoverBg.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:8;");
        hoverBg.setOpacity(0);
        hoverBg.setMouseTransparent(true);

        StackPane cell = new StackPane(activeBg, hoverBg, row);
        cell.setCursor(Cursor.HAND);
        cell.getProperties().put("leftBar", leftBar);
        cell.getProperties().put("label", label);
        cell.getProperties().put("hoverBg", hoverBg);
        cell.getProperties().put("activeBg", activeBg);
        favNavCells.add(cell);

        cell.setOnMouseEntered(e -> {
            if (currentFavTab != index) {
                Timeline in = new Timeline(
                        new KeyFrame(Duration.ZERO, new KeyValue(hoverBg.opacityProperty(), hoverBg.getOpacity())),
                        new KeyFrame(Duration.millis(130), new KeyValue(hoverBg.opacityProperty(), 0.08, Interpolator.EASE_OUT)));
                in.play();
            }
        });
        cell.setOnMouseExited(e -> {
            if (currentFavTab != index) {
                Timeline out = new Timeline(
                        new KeyFrame(Duration.ZERO, new KeyValue(hoverBg.opacityProperty(), hoverBg.getOpacity())),
                        new KeyFrame(Duration.millis(160), new KeyValue(hoverBg.opacityProperty(), 0.0, Interpolator.EASE_OUT)));
                out.play();
            }
        });
        cell.setOnMouseClicked(e -> switchFavTab(index));

        // 不做入场滑入/逐个淡入：歌单页每次进入都会重建侧栏，
        // 动画重播会让整条选项栏（含背景块）又“动”一次，这里保持静态呈现。
        return cell;
    }

    private void applyFavNavStyle(int index, boolean active) {
        StackPane cell = favNavCells.get(index);
        Region leftBar = (Region) cell.getProperties().get("leftBar");
        Label label = (Label) cell.getProperties().get("label");
        Region hoverBg = (Region) cell.getProperties().get("hoverBg");
        Region activeBg = (Region) cell.getProperties().get("activeBg");

        // 停掉上一次未播完的切换动画：快速连点时两条 Timeline 会同时写同一批属性，导致竖条抖动
        if (cell.getProperties().get("navAnim") instanceof Timeline running) running.stop();

        // 淡蓝背景淡入淡出 + 竖条从中心展开/收起，两者同一条 Timeline 保证节奏一致
        Timeline anim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(activeBg.opacityProperty(), activeBg.getOpacity()),
                        new KeyValue(leftBar.opacityProperty(), leftBar.getOpacity()),
                        new KeyValue(leftBar.scaleYProperty(), leftBar.getScaleY())),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(activeBg.opacityProperty(), active ? 1.0 : 0.0, Interpolator.EASE_OUT),
                        new KeyValue(leftBar.opacityProperty(), active ? 1.0 : 0.0, Interpolator.EASE_OUT),
                        new KeyValue(leftBar.scaleYProperty(), active ? 1.0 : 0.35, Interpolator.EASE_OUT)));
        cell.getProperties().put("navAnim", anim);
        anim.play();

        // 选中态：深蓝粗体文字 + 淡蓝底 + 左竖条区分；未选中深灰；清晰可读，不用白色
        label.setStyle("-fx-text-fill:" + (active ? "#0B3D91" : C_TEXT) + ";");
        label.setFont(Font.font(Font.getDefault().getFamily(), active ? FontWeight.BOLD : FontWeight.NORMAL, 13));
        if (!active) hoverBg.setOpacity(0);
    }

    private void switchFavTab(int index) {
        currentFavTab = index;
        favTabVisited = true;
        for (int i = 0; i < favNavCells.size(); i++) {
            applyFavNavStyle(i, i == index);
        }
        // 面板立即构造（内部的收藏夹列表是异步拉的，早建早开始），再走淡出 → 淡入
        Node panel = switch (index) {
            case 0 -> createFavListPanel(false);
            case 1 -> createFavListPanel(true);
            case 2 -> createBangumiPanel();
            default -> createPlaceholder("未知分类");
        };

        // 记录批次号：连点 tab 时旧的淡出回调还在排队，靠它避免旧回调把已过期的 panel 塞回容器
        final int switchToken = ++favSwitchToken;

        if (favContentArea.getChildren().isEmpty()) {
            panel.setOpacity(0);
            panel.setScaleX(0.97);
            panel.setScaleY(0.97);
            favContentArea.getChildren().setAll(panel);
            playFavPanelIn(panel);
            return;
        }

        Node old = favContentArea.getChildren().get(0);
        Timeline out = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(old.opacityProperty(), old.getOpacity()),
                        new KeyValue(old.scaleXProperty(), old.getScaleX()),
                        new KeyValue(old.scaleYProperty(), old.getScaleY())),
                new KeyFrame(Duration.millis(100),
                        new KeyValue(old.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(old.scaleXProperty(), 0.97, Interpolator.EASE_OUT),
                        new KeyValue(old.scaleYProperty(), 0.97, Interpolator.EASE_OUT)));
        out.setOnFinished(e -> {
            if (switchToken != favSwitchToken) return;
            panel.setOpacity(0);
            panel.setScaleX(0.97);
            panel.setScaleY(0.97);
            favContentArea.getChildren().setAll(panel);
            playFavPanelIn(panel);
        });
        out.play();
    }

    private void playFavPanelIn(Node panel) {
        Timeline in = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(panel.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleXProperty(), 0.97, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleYProperty(), 0.97, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(panel.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleYProperty(), 1.0, Interpolator.EASE_OUT)));
        in.play();
    }

    // 收藏夹面板（collected=false 创建的收藏夹 / true 追更合集）
    private BorderPane createFavListPanel(boolean collected) {
        // 捕获本面板所属的 tab：异步回调里读 currentFavTab 会拿到用户后来切换的值，必须用快照
        final int tabIndex = currentFavTab;

        BorderPane page = new BorderPane();
        // 透明：白底统一由底层 centerWhiteBg 提供（受白色底不透明度设置控制），
        // 不要在此画不透明白底，否则右侧白色背景不会跟着透明
        page.setStyle("-fx-background-color:transparent;");

        ComboBox<FavFolder> folderCombo = new ComboBox<>();
        folderCombo.setPromptText(collected ? "选择追更合集..." : "选择收藏夹...");
        folderCombo.setPrefHeight(30);
        folderCombo.setMaxWidth(Double.MAX_VALUE);
        folderCombo.getStyleClass().add("pcl-combo");

        folderCombo.setCellFactory(cb -> new SmoothFavListCell());
        folderCombo.setButtonCell(new SmoothFavListCell());

        bindPopupFade(folderCombo);
        bindComboHoverScale(folderCombo);

        VBox videoListContainer = new VBox(4);
        videoListContainer.setAlignment(Pos.TOP_CENTER);

        ScrollPane scroll = new ScrollPane(videoListContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;-fx-border-color:transparent;");
        // 下沿不留白：列表内容一直延申到卡片边缘（上/左/右仍留 10px 内边距）
        scroll.setPadding(new Insets(10, 10, 0, 10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setupScrollbarGradient(scroll);
        attachSmoothVerticalScroll(scroll);

        VBox card = new VBox(10);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color:-card-bg;-fx-background-radius:8;-fx-border-color:" + C_BORDER + ";-fx-border-width:1;-fx-border-radius:8;");
        card.setAlignment(Pos.TOP_CENTER);
        card.getChildren().addAll(folderCombo, scroll);
        VBox.setVgrow(card, Priority.ALWAYS);
        page.setCenter(card);
        page.setPadding(new Insets(12));

        // 设置页同款卡片阴影：平时微弱灰影，悬停变蓝
        DropShadow cardShadow = new DropShadow();
        cardShadow.setRadius(8);
        cardShadow.setSpread(0);
        cardShadow.setColor(Color.rgb(0, 0, 0, 0.04));
        cardShadow.setOffsetY(2);
        card.setEffect(cardShadow);

        card.setOnMouseEntered(e -> {
            Timeline in = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(cardShadow.colorProperty(), cardShadow.getColor()),
                            new KeyValue(cardShadow.radiusProperty(), cardShadow.getRadius())),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(cardShadow.colorProperty(), Color.web(C_PRIMARY, 0.25), Interpolator.EASE_OUT),
                            new KeyValue(cardShadow.radiusProperty(), 14.0, Interpolator.EASE_OUT)));
            in.play();
        });
        card.setOnMouseExited(e -> {
            Timeline out = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(cardShadow.colorProperty(), cardShadow.getColor()),
                            new KeyValue(cardShadow.radiusProperty(), cardShadow.getRadius())),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(cardShadow.colorProperty(), Color.rgb(0, 0, 0, 0.04), Interpolator.EASE_OUT),
                            new KeyValue(cardShadow.radiusProperty(), 8.0, Interpolator.EASE_OUT)));
            out.play();
        });

        if (biliCookie == null) {
            Label hint = new Label("请先登录 B 站账号");
            hint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:14px;");
            videoListContainer.getChildren().add(hint);
            return page;
        }

        folderCombo.setDisable(true);
        new Thread(() -> {
            long mid = fetchUserMid(biliCookie);
            if (mid == 0) {
                Platform.runLater(() -> folderCombo.setPromptText("获取用户信息失败"));
                return;
            }
            // collected=false → 创建的收藏夹；true → 我追的合集（type=21）
            List<FavFolder> folders = collected
                    ? fetchSeasonFolders(biliCookie, mid)
                    : fetchFavFolders(biliCookie, mid, false);
            Platform.runLater(() -> {
                folderCombo.getItems().addAll(folders);
                if (!folders.isEmpty()) {
                    // 优先落在上次播放的收藏夹上（启动时由 restoreLastFolder 恢复）。
                    // 用 equals 而非只比 id：id 相同但 season 不同是不同类的条目（收藏夹 vs 追更合集）
                    FavFolder toSelect = null;
                    if (lastSelectedFolder != null) {
                        for (FavFolder f : folders) {
                            if (f.equals(lastSelectedFolder)) { toSelect = f; break; }
                        }
                    }
                    if (toSelect == null) toSelect = folders.get(0);
                    lastSelectedFolder = toSelect;
                    lastFavTab = tabIndex;
                    // setValue 会触发 onAction → 先置标志让那次回调跳过，只走这一次显式加载
                    suppressFavAction = true;
                    folderCombo.setValue(toSelect);
                    loadFavVideos(toSelect, videoListContainer, scroll);
                }
                folderCombo.setDisable(false);
            });
        }).start();

        folderCombo.setOnAction(e -> {
            // 程序化 setValue 触发的这次回调：只清标志、不加载（加载已由显式调用完成），避免同一次刷新被加载两次
            if (suppressFavAction) { suppressFavAction = false; return; }
            FavFolder sel = folderCombo.getValue();
            if (sel != null) {
                lastSelectedFolder = sel;
                lastFavTab = tabIndex;
                loadFavVideos(sel, videoListContainer, scroll);
            }
        });

        return page;
    }

    // ==================== 追番与追剧面板 ====================
    private BorderPane createBangumiPanel() {
        BorderPane page = new BorderPane();
        // 透明：白底统一由底层 centerWhiteBg 提供（受白色底不透明度设置控制），
        // 不要在此画不透明白底，否则右侧白色背景不会跟着透明
        page.setStyle("-fx-background-color:transparent;");

        ComboBox<String> filterCombo = new ComboBox<>();
        filterCombo.getItems().addAll("全部", "追番", "追剧");
        filterCombo.setValue("全部");
        filterCombo.setPrefHeight(30);
        filterCombo.setPrefWidth(120);
        filterCombo.getStyleClass().add("pcl-combo");
        bindPopupFade(filterCombo);
        bindComboHoverScale(filterCombo);

        FlowPane cards = new FlowPane(12, 12);
        cards.setAlignment(Pos.TOP_LEFT);
        cards.setPadding(new Insets(4));

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;-fx-border-color:transparent;");
        scroll.setPadding(new Insets(6, 10, 10, 10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setupScrollbarGradient(scroll);
        attachSmoothVerticalScroll(scroll);

        HBox top = new HBox(10, filterCombo);
        top.setPadding(new Insets(0, 0, 6, 0));

        VBox body = new VBox(0, top, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        page.setCenter(body);
        page.setPadding(new Insets(14, 12, 12, 12));

        if (biliCookie == null) {
            Label hint = new Label("请先登录 B 站账号");
            hint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:14px;");
            cards.getChildren().add(hint);
            return page;
        }

        filterCombo.setOnAction(e -> renderBangumiCards(cards, filterCombo.getValue()));

        if (bangumiCache != null) {
            renderBangumiCards(cards, filterCombo.getValue());
        } else {
            Label loading = new Label("加载中...");
            loading.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
            cards.getChildren().add(loading);
            new Thread(() -> {
                long mid = fetchUserMid(biliCookie);
                List<BangumiItem> items = mid == 0 ? new ArrayList<>() : fetchBangumiFollows(biliCookie, mid);
                Platform.runLater(() -> {
                    bangumiCache = items;
                    renderBangumiCards(cards, filterCombo.getValue());
                });
            }).start();
        }
        return page;
    }

    private void renderBangumiCards(FlowPane cards, String filter) {
        cards.getChildren().clear();
        if (bangumiCache == null || bangumiCache.isEmpty()) {
            Label empty = new Label("暂无追番/追剧内容");
            empty.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
            cards.getChildren().add(empty);
            return;
        }
        for (BangumiItem item : bangumiCache) {
            if ("追番".equals(filter) && item.type != 1) continue;
            if ("追剧".equals(filter) && item.type != 2) continue;
            cards.getChildren().add(createBangumiCard(item));
        }
    }

    private VBox createBangumiCard(BangumiItem item) {
        ImageView cover = new ImageView();
        cover.setFitWidth(110);
        cover.setFitHeight(146);
        cover.setPreserveRatio(true);
        try {
            cover.setImage(new Image(item.cover, 110, 146, true, true, true));
        } catch (Exception ignored) { }
        Rectangle clip = new Rectangle(110, 146);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        cover.setClip(clip);

        Label title = new Label(item.title);
        title.setMaxWidth(110);
        title.setMinHeight(16);
        title.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:12px;");
        title.setEllipsisString("...");

        Label badge = new Label(item.badge == null || item.badge.isEmpty() ? "番剧" : item.badge);
        badge.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:11px;");
        badge.setMaxWidth(110);

        VBox card = new VBox(6, cover, title, badge);
        card.setPrefWidth(110);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> switchToBangumiEps(item));

        card.setOnMouseEntered(e -> {
            Timeline in = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(card.translateYProperty(), card.getTranslateY())),
                    new KeyFrame(Duration.millis(120), new KeyValue(card.translateYProperty(), -3, Interpolator.EASE_OUT)));
            in.play();
        });
        card.setOnMouseExited(e -> {
            Timeline out = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(card.translateYProperty(), card.getTranslateY())),
                    new KeyFrame(Duration.millis(140), new KeyValue(card.translateYProperty(), 0, Interpolator.EASE_OUT)));
            out.play();
        });

        return card;
    }

    private void switchToBangumiEps(BangumiItem item) {
        BorderPane panel = createBangumiEpPanel(item);
        panel.setOpacity(0);
        panel.setScaleX(0.97);
        panel.setScaleY(0.97);
        favContentArea.getChildren().setAll(panel);
        Timeline in = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(panel.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleXProperty(), 0.97, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleYProperty(), 0.97, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(panel.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleYProperty(), 1.0, Interpolator.EASE_OUT)));
        in.play();
    }

    private BorderPane createBangumiEpPanel(BangumiItem item) {
        BorderPane page = new BorderPane();
        // 透明：白底统一由底层 centerWhiteBg 提供（受白色底不透明度设置控制），
        // 不要在此画不透明白底，否则右侧白色背景不会跟着透明
        page.setStyle("-fx-background-color:transparent;");

        Label back = new Label("← 返回");
        back.setCursor(Cursor.HAND);
        back.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:13px;-fx-font-weight:bold;");
        back.setOnMouseClicked(e -> switchFavTab(2));

        Label title = new Label(item.title);
        title.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:15px;-fx-font-weight:bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox top = new HBox(12, back, title);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(2, 0, 10, 2));

        VBox epContainer = new VBox(4);
        epContainer.setAlignment(Pos.TOP_CENTER);

        ScrollPane scroll = new ScrollPane(epContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;-fx-border-color:transparent;");
        scroll.setPadding(new Insets(10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setupScrollbarGradient(scroll);
        attachSmoothVerticalScroll(scroll);

        VBox body = new VBox(0, top, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        page.setCenter(body);
        page.setPadding(new Insets(12));

        if (biliCookie == null) {
            Label hint = new Label("请先登录 B 站账号");
            hint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:14px;");
            epContainer.getChildren().add(hint);
            return page;
        }

        List<FavVideo> cached = seasonEpCache.get(item.seasonId);
        if (cached != null) {
            renderBangumiEps(cached, epContainer);
        } else {
            Label loading = new Label("加载中...");
            loading.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
            epContainer.getChildren().add(loading);
            new Thread(() -> {
                List<FavVideo> eps = fetchSeasonEps(biliCookie, item.seasonId);
                Platform.runLater(() -> {
                    seasonEpCache.put(item.seasonId, eps);
                    renderBangumiEps(eps, epContainer);
                });
            }).start();
        }
        return page;
    }

    private void renderBangumiEps(List<FavVideo> eps, VBox container) {
        container.getChildren().clear();
        if (eps == null || eps.isEmpty()) {
            Label empty = new Label("暂无可用剧集");
            empty.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
            container.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < eps.size(); i++) {
            container.getChildren().add(createFavVideoRow(eps.get(i), i, eps));
        }
    }

    /**
     * 用完整列表渲染歌单页（缓存命中等已经拿到全量数据的场景）：
     * 先出第一页，其余滚到底再补。
     */
    private void renderFavVideos(FavFolder folder, List<FavVideo> videos, VBox container, ScrollPane scroll, String folderTitle) {
        beginFavRender(folder, videos, container, scroll, folderTitle);
        if (videos.isEmpty()) {
            setFavStatus("收藏夹为空");
            return;
        }
        setFavStatus(null);
        setupFavLazyRender(scroll);
    }

    /**
     * 开始一轮歌单页渲染：清空容器、作废进行中的异步加载、重置渲染水位、同步到播放页。
     */
    private void beginFavRender(FavFolder folder, List<FavVideo> videos, VBox container, ScrollPane scroll, String folderTitle) {
        favRenderToken++;           // 作废上一批尚未回调完的异步加载
        favStreaming = false;
        favRenderData = videos;
        loadedVideoCount = 0;       // 渲染水位归零：一个 row 都还没建

        // ScrollPane 与 rowsBox 都是复用的：必须先摘掉旧监听再换新容器，
        // 否则旧监听还引用着上一个收藏夹的数据源，会把别的数据追加进当前列表
        if (favScrollListener != null) {
            scroll.vvalueProperty().removeListener(favScrollListener);
            favScrollListener = null;
        }
        if (favFillListener != null && favRowsBox != null) {
            favRowsBox.heightProperty().removeListener(favFillListener);
            favFillListener = null;
        }

        container.getChildren().clear();
        // 换了收藏夹就该从头看，否则会停在上一收藏夹的滚动位置
        scroll.setVvalue(0);

        favRowsBox = new VBox(4);
        favRowsBox.setAlignment(Pos.TOP_CENTER);
        favStatusLabel = new Label();
        favStatusLabel.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:13px;");
        favStatusLabel.setMouseTransparent(true);
        favStatusLabel.setVisible(false);
        favStatusLabel.setManaged(false);   // 隐藏时不占位，避免空态时列表中间留一道空隙
        container.getChildren().addAll(favRowsBox, favStatusLabel);

        if (folder != null && folder.equals(lastSelectedFolder)) {
            syncedVideos = videos;
            syncedFolderTitle = folderTitle;
            // 播放页右侧的列表要跟着换：它只在启动恢复时重建过一次，
            // 不在这里重建的话，用户在歌单页换了收藏夹后回播放页看到的仍是旧内容
            // （甚至一直停在"请先在「歌单」页选择收藏夹"）。
            if (listContainer != null && playlistScroll != null) {
                fillPlaylistInto(listContainer, playlistScroll);
            }
        }
        if (folder != null) folderVideoCache.put(folder, videos);
    }

    /** 设置尾部状态行文案；传 null 则隐藏 */
    private void setFavStatus(String text) {
        if (favStatusLabel == null) return;
        if (text == null) {
            favStatusLabel.setVisible(false);
            favStatusLabel.setManaged(false);
            return;
        }
        favStatusLabel.setText(text);
        favStatusLabel.setVisible(true);
        favStatusLabel.setManaged(true);
    }

    /**
     * 把已渲染行数推进到 target（不超过数据源长度），幂等：已渲染的不会重复创建。
     *
     * <p>这是唯一的建行入口。收藏夹动辄上千条，绝不能把拉到的数据全渲染成节点，
     * 只维护"渲染水位"，视口里看多少建多少。
     */
    private void ensureFavRowsRendered(int target) {
        List<FavVideo> data = favRenderData;
        if (data == null || favRowsBox == null) return;
        int end = Math.min(target, data.size());
        int order = 0;
        for (int i = loadedVideoCount; i < end; i++) {
            Node row = createFavVideoRow(data.get(i), i, data);
            favRowsBox.getChildren().add(row);
            animateNodeIn(row, order++);
        }
        if (end > loadedVideoCount) loadedVideoCount = end;
    }

    /**
     * 节点入场：淡入 + 轻微上移归位。
     *
     * <p>orderInBatch 是本批次内的序号（不是全局行号），所以无论是渐进加载到的每一页、
     * 还是滚到底补的一批，动画都从头错落，不会出现"最后几行等半天才出现"。
     * 延迟封顶 120ms，配合 200ms 时长，整批最慢 320ms 就位，不拖沓。
     */
    private void animateNodeIn(Node node, int orderInBatch) {
        node.setOpacity(0);
        node.setTranslateY(6);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.opacityProperty(), 0),
                        new KeyValue(node.translateYProperty(), 6)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(node.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(node.translateYProperty(), 0.0, Interpolator.EASE_OUT)));
        tl.setDelay(Duration.millis(Math.min(orderInBatch * 18.0, 120.0)));
        tl.play();
    }

    /**
     * 装配懒渲染：先出首屏，再挂两个触发源。
     *
     * <p>滚到底只是触发源之一。内容填不满视口时根本没有滚动条，光靠滚动事件会卡在首屏，
     * 所以再监听视口高度变化，只要内容还矮于视口就继续补批。
     *
     * <p>渐近加载期间同样挂这些监听：数据还在增长不影响"水位按需推进"这套逻辑，
     * 滚到底时水位够就显示、不够就说明新数据还没到。
     */
    private void setupFavLazyRender(ScrollPane scroll) {
        if (favRenderData == null || favRenderData.isEmpty()) return;
        ensureFavRowsRendered(VIDEO_PAGE_SIZE);
        bindFavLazyRender(scroll);
    }

    /**
     * 只绑懒渲染监听，不碰首屏渲染。
     *
     * <p>必须独立出来：渐近加载开始时数据是空的，只能先绑监听，
     * 等每页回调推数据；若和数据是否到达绑在一起，streaming 路径会因为空列表直接 return，
     * 导致全程没有监听、加载完滚到底也补不出新行。
     */
    private void bindFavLazyRender(ScrollPane scroll) {
        if (favScrollListener != null || favFillListener != null) return;  // 幂等

        favScrollListener = (obs, oldVal, newVal) -> {
            List<FavVideo> data = favRenderData;
            if (data == null) return;
            if (newVal.doubleValue() >= 0.95 && loadedVideoCount < data.size()) {
                ensureFavRowsRendered(loadedVideoCount + VIDEO_PAGE_SIZE);
                refreshFavTailHint();
            }
        };
        scroll.vvalueProperty().addListener(favScrollListener);

        // 监听内容高度而非视口高度：补批后变高的是内容，
        // 挂在内容上才能"补一批→变高→再判断"自动收敛，一次补到底
        favFillListener = (obs, oldH, newH) -> {
            List<FavVideo> data = favRenderData;
            if (data == null || favRowsBox == null) return;
            if (loadedVideoCount >= data.size()) return;
            // 内容还矮于视口就继续补
            if (newH.doubleValue() > scroll.getHeight()) return;
            ensureFavRowsRendered(loadedVideoCount + VIDEO_PAGE_SIZE);
            refreshFavTailHint();
        };
        favRowsBox.heightProperty().addListener(favFillListener);
    }

    /**
     * 视口底部已贴近内容底部时主动补一批行。
     *
     * <p>不能只依赖滚动监听：用户停在底部后 vvalue 不再变化，此时即便新数据到达，
     * 监听也不会触发，界面就卡在"滚不动也没新内容"。数据每来一页都要主动查一次。
     */
    private void topUpFavRowsIfNearBottom(ScrollPane scroll) {
        List<FavVideo> data = favRenderData;
        if (data == null || favRowsBox == null) return;
        if (loadedVideoCount >= data.size()) return;
        double contentH = favRowsBox.getHeight();
        double viewH = scroll.getHeight();
        // 内容还没填满视口（撑不出滚动条），直接补
        if (contentH <= viewH) {
            ensureFavRowsRendered(loadedVideoCount + VIDEO_PAGE_SIZE);
            return;
        }
        // 剩余可滚动距离不足一屏就补；补完 contentH 变大、剩余距离变宽，自然收敛
        double remain = (1.0 - scroll.getVvalue()) * (contentH - viewH);
        if (remain < 240) ensureFavRowsRendered(loadedVideoCount + VIDEO_PAGE_SIZE);
    }

    /**
     * 刷新尾部提示。已渲染完当前全部数据、但还在拉取时，提示"继续滚动加载"，
     * 免得用户滚到底发现不动，以为卡死。
     */
    private void refreshFavTailHint() {
        List<FavVideo> data = favRenderData;
        if (data == null) return;
        if (favStreaming && loadedVideoCount >= data.size()) {
            setFavStatus("继续向下滚动加载更多...");
        } else if (!favStreaming) {
            setFavStatus(null);
        }
    }

    /**
     * 加载收藏夹内容。<b>分页拉取，每拉到一页就立即渲染出来</b>，不等整个收藏夹拉完。
     * 大收藏夹动辄几十页、接口每页还要间隔 800ms，全拉完才显示会长时间停在"加载中"。
     */
    private void loadFavVideos(FavFolder folder, VBox container, ScrollPane scroll) {
        long fid = folder.id;
        String cacheKey = (folder.season ? "S" : "F") + fid;

        // 登记本次目标：淡出是异步的，回调执行时据此判断是否已被后续点击覆盖
        favPendingKey = cacheKey;

        if (favCache.containsKey(cacheKey)) {
            List<FavVideo> cached = favCache.get(cacheKey);
            // 命中缓存也要走过渡，否则切回已看过的收藏夹是硬切、只有首次拉取才平滑
            beginFavListSwitch(container, () -> {
                if (!cacheKey.equals(favPendingKey)) return;
                renderFavVideos(folder, cached, container, scroll, folder.title);
            });
            return;
        }
        if (favLoading.contains(cacheKey)) {
            // 同一收藏夹正被别处（比如切换 tab 前留下的后台线程）拉取。
            // 不能干等 return —— 那样这里就永远空白。等它拉完直接取缓存渲染。
            new Thread(() -> {
                while (favLoading.contains(cacheKey)) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
                }
                List<FavVideo> done = favCache.get(cacheKey);
                if (done == null) return;
                Platform.runLater(() -> {
                    if (!cacheKey.equals(favPendingKey)) return;
                    beginFavListSwitch(container, () -> {
                        if (!cacheKey.equals(favPendingKey)) return;
                        renderFavVideos(folder, done, container, scroll, folder.title);
                    });
                });
            }).start();
            return;
        }

        beginFavListSwitch(container, () -> {
            if (!cacheKey.equals(favPendingKey)) return;
            startFavStreamingLoad(folder, fid, container, scroll, cacheKey);
        });
    }

    /**
     * 切换收藏夹时的列表过渡：先把当前列表整体淡出，再换成新内容。
     * 命中缓存与走网络拉取两条路径都经过这里，保证观感一致。
     */
    private void beginFavListSwitch(VBox container, Runnable renderAction) {
        if (container.getChildren().isEmpty()) {
            renderAction.run();     // 首次进入没有旧内容，没必要淡出
            return;
        }
        if (favListFadeOut != null) favListFadeOut.stop();
        favListFadeOut = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(container.opacityProperty(), container.getOpacity()),
                        new KeyValue(container.translateYProperty(), container.getTranslateY())),
                new KeyFrame(Duration.millis(120),
                        new KeyValue(container.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(container.translateYProperty(), -4, Interpolator.EASE_OUT)));
        favListFadeOut.setOnFinished(e -> {
            // 必须先恢复容器自身可见性：新行各自 opacity 0→1，嵌在透明容器里就看不见了
            container.setOpacity(1);
            container.setTranslateY(0);
            renderAction.run();
        });
        favListFadeOut.play();
    }

    private void startFavStreamingLoad(FavFolder folder, long fid, VBox container, ScrollPane scroll, String cacheKey) {
        // 边拉取边显示：数据源先建空的，之后每解析完一页就往里追加数据，
        // 但只把"用户看得到"的那部分渲染成节点，不会一股脑全建
        List<FavVideo> videos = new ArrayList<>();
        beginFavRender(folder, videos, container, scroll, folder.title);
        final int token = favRenderToken;
        // 此刻数据还是空的，只绑监听：边拉边滚也能补行，
        // 且加载完成后滚到底靠它继续出后面的行
        bindFavLazyRender(scroll);
        favStreaming = true;
        setFavStatus("加载中...");
        favLoading.add(cacheKey);

        new Thread(() -> {
            List<FavVideo> result = new ArrayList<>();   // 后台线程独占的完整结果，最终写进缓存
            try {
                FavPageSink sink = (page, total, done) -> {
                    if (!page.isEmpty()) result.addAll(page);
                    int loaded = result.size();
                    Platform.runLater(() -> {
                        if (token != favRenderToken) return;   // 期间切了别的收藏夹，丢弃这批
                        videos.addAll(page);                   // 只追加数据
                        if (!done) {
                            setFavStatus(total > 0
                                    ? "加载中... " + loaded + " / " + total
                                    : "加载中... 已加载 " + loaded + " 个");
                        }
                        // 首屏那批行还没建够就顺手补上；建够了就只更新数据，绝不预建
                        if (loadedVideoCount < VIDEO_PAGE_SIZE) {
                            ensureFavRowsRendered(VIDEO_PAGE_SIZE);
                        }
                        // 用户若已停在底部，新数据到达后 vvalue 不变、滚动监听不会再触发，
                        // 这里主动补一次，避免停在底部就再也出不来新行
                        topUpFavRowsIfNearBottom(scroll);
                        refreshFavTailHint();
                    });
                };
                // 追更的视频合集走 season/list，普通收藏夹走 resource/list
                if (folder.season) fetchSeasonContents(biliCookie, fid, sink);
                else fetchFavResources(biliCookie, fid, sink);
            } finally {
                favLoading.remove(cacheKey);
                favCache.put(cacheKey, result);
                // runLater 是 FIFO：上面各页的回调都已入队在前，这里收尾一定在它们之后执行
                Platform.runLater(() -> {
                    if (token != favRenderToken) return;
                    favStreaming = false;
                    if (videos.isEmpty()) {
                        setFavStatus("收藏夹为空");
                        return;
                    }
                    setFavStatus(null);
                    // 数据齐了：视口还没被填满说明条目很少，一次性全渲染无妨，
                    // 否则用户会看到列表只显示一部分、其余要靠滚到底才出
                    if (favRowsBox.getHeight() <= scroll.getHeight()) {
                        ensureFavRowsRendered(videos.size());
                    }
                    topUpFavRowsIfNearBottom(scroll);
                });
            }
        }).start();
    }

    private HBox createFavVideoRow(FavVideo video, int index, List<FavVideo> allVideos) {
        HBox row = new HBox(12);
        row.setPadding(new Insets(9, 12, 9, 12));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color:-card-bg;-fx-background-radius:6;-fx-cursor:hand;");

        Label playIcon = new Label("▶");
        playIcon.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:14px;");
        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label titleLbl = new Label(resolveTitle(video.bvid, video.title));
        titleLbl.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:14px;-fx-font-weight:normal;");
        titleLbl.setMaxWidth(Double.MAX_VALUE);
        titleLbl.setEllipsisString("...");
        Label subLbl = new Label(video.upName + " · " + video.durationStr());
        subLbl.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:12px;");
        info.getChildren().addAll(titleLbl, subLbl);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label bvidLbl = new Label(video.bvid);
        bvidLbl.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        row.getChildren().addAll(playIcon, info, sp, bvidLbl);

        StackPane cell = new StackPane(row);
        cell.setPadding(new Insets(0));
        StackPane.setAlignment(row, Pos.CENTER_LEFT);

        StackPane indicator = new StackPane();
        indicator.setPrefSize(2, 32);
        indicator.setMaxSize(2, 32);
        indicator.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:1px;");
        indicator.setVisible(false);
        indicator.setMouseTransparent(true);
        StackPane.setAlignment(indicator, Pos.CENTER_LEFT);
        indicator.setTranslateX(4);

        StackPane hoverFrame = new StackPane();
        hoverFrame.setMinSize(0, 0);
        hoverFrame.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        hoverFrame.setBackground(new Background(new BackgroundFill(Color.web(C_PRIMARY, 0.10), new CornerRadii(6), Insets.EMPTY)));
        hoverFrame.setBorder(new Border(new BorderStroke(Color.web(C_PRIMARY, 0.40), BorderStrokeStyle.SOLID, new CornerRadii(6), new BorderWidths(1))));
        hoverFrame.setOpacity(0);
        hoverFrame.setTranslateX(2);
        hoverFrame.setTranslateY(2);
        hoverFrame.setMouseTransparent(true);

        cell.getChildren().add(indicator);
        cell.getChildren().add(hoverFrame);

        HBox wrapper = new HBox(cell);
        HBox.setHgrow(cell, Priority.ALWAYS);
        wrapper.setAlignment(Pos.CENTER_LEFT);

        // hover 反馈只用 hoverFrame 的淡入淡出（已有 90ms/160ms 过渡）。
        // 原来这里还叠了一次 row.setStyle 背景硬切；且退出时无条件刷回白色，
        // 会把选中行的淡蓝底覆盖掉。两处硬切一并删除，手感更干净也修了选中态 bug。
        wrapper.setOnMouseEntered(e -> {
            Timeline in = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(hoverFrame.opacityProperty(), hoverFrame.getOpacity()), new KeyValue(hoverFrame.translateXProperty(), hoverFrame.getTranslateX()), new KeyValue(hoverFrame.translateYProperty(), hoverFrame.getTranslateY())), new KeyFrame(Duration.millis(90), new KeyValue(hoverFrame.opacityProperty(), 1.0), new KeyValue(hoverFrame.translateXProperty(), 0.0), new KeyValue(hoverFrame.translateYProperty(), 0.0)));
            in.play();
        });
        wrapper.setOnMouseExited(e -> {
            Timeline out = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(hoverFrame.opacityProperty(), hoverFrame.getOpacity()), new KeyValue(hoverFrame.translateXProperty(), hoverFrame.getTranslateX()), new KeyValue(hoverFrame.translateYProperty(), hoverFrame.getTranslateY())), new KeyFrame(Duration.millis(160), new KeyValue(hoverFrame.opacityProperty(), 0.0), new KeyValue(hoverFrame.translateXProperty(), 2.0), new KeyValue(hoverFrame.translateYProperty(), 2.0)));
            out.setOnFinished(ev -> hoverFrame.setOpacity(0));
            out.play();
        });

        wrapper.setOnMouseClicked(e -> {
            System.out.println("[fav] 选择视频: " + video.title + " (" + video.bvid + ")");
            currentBvid = video.bvid;
            loadAndSetCover(video.bvid);
            applyLeftTitle(video.bvid, video.title);
            currentFavVideo = video;
            leftArtist.setText(video.upName);
            loadLyricForCurrent();   // 必须在 setText 之后
            leftTime.setText("0:00 / " + video.durationStr());
            progressSlider.setMax(1); progressSlider.setValue(0);
            lastKnownTotal = 0; // ★ 重置
            setPlayIcon(true);

            pauseOnReady = false; // 用户主动播放，取消"就绪即暂停"
            if (audioPlayer != null) {
                audioPlayer.playBvid(video.bvid);
            }
            if (playNavButton != null) playNavButton.getToggle().setSelected(true);
            rememberPlayback(video.bvid);

            refreshRightPlaylistSelection();

            wrapper.setOpacity(0.6);
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(wrapper.opacityProperty(), 0.6)),
                    new KeyFrame(Duration.millis(220), new KeyValue(wrapper.opacityProperty(), 1.0, Interpolator.EASE_OUT))
            );
            tl.play();

            if (mainContentCache == null) mainContentCache = createMainContent();
            switchPage(mainContentCache);
        });

        return wrapper;
    }

    // ==================== 自定义 Cell ====================
    private static class SmoothFavListCell extends ListCell<FavFolder> {
        private final ObjectProperty<Color> bg = new SimpleObjectProperty<>(Color.web("#FFFFFF"));
        private Timeline colorAnim;

        SmoothFavListCell() {
            setTextFill(Color.web("#374151"));
            setFont(Font.font(12));
            setPadding(new Insets(7, 12, 7, 12));
            // 固定行高：未选中收藏夹时按钮区单元格是空的，高度会塌成 padding 高度，
            // 弹出层按它算高就会明显不够、内容顶出下边框
            setPrefHeight(COMBO_ROW_H);
            setMinHeight(COMBO_ROW_H);
            bg.addListener((obs, o, n) -> applyBg(n));
            applyBg(bg.get());

            hoverProperty().addListener((obs, wasHover, isHover) -> {
                if (isSelected()) return;
                animateBgTo(isHover ? Color.web("#E8F1FA") : Color.web("#FFFFFF"));
            });
        }

        @Override
        protected void updateItem(FavFolder item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.title + " (" + item.count + ")");
            if (isSelected()) animateBgTo(Color.web("#E3F0FE"));
            else if (!isHover()) animateBgTo(Color.web("#FFFFFF"));
        }

        private void applyBg(Color c) {
            if (c == null) return;
            // 用 setBackground 而非 setStyle：动画每帧都会调到这里，setStyle 会逐帧重解析 CSS 并重排
            setBackground(new Background(new BackgroundFill(c, new CornerRadii(4), Insets.EMPTY)));
        }

        private void animateBgTo(Color target) {
            if (colorAnim != null) colorAnim.stop();
            Color start = bg.get();
            colorAnim = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(bg, start, Interpolator.LINEAR)),
                    new KeyFrame(Duration.millis(140), new KeyValue(bg, target, Interpolator.EASE_BOTH))
            );
            colorAnim.play();
        }
    }

    private void bindPopupFade(ComboBox<?> combo) {
        combo.setOnShowing(e -> {
            new AnimationTimer() {
                int tries = 0;
                @Override
                public void handle(long now) {
                    if (tries++ > 15) { stop(); return; }
                    Node lv = combo.lookup(".combo-box-popup .list-view");
                    if (lv != null) {
                        stop();
                        lv.setOpacity(0);
                        lv.setTranslateY(-4);
                        Timeline in = new Timeline(
                                new KeyFrame(Duration.ZERO,
                                        new KeyValue(lv.opacityProperty(), 0, Interpolator.EASE_OUT),
                                        new KeyValue(lv.translateYProperty(), -4, Interpolator.EASE_OUT)),
                                new KeyFrame(Duration.millis(150),
                                        new KeyValue(lv.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                                        new KeyValue(lv.translateYProperty(), 0, Interpolator.EASE_OUT))
                        );
                        in.play();
                    }
                }
            }.start();
        });
        combo.setOnHidden(e -> {
            Node lv = combo.lookup(".combo-box-popup .list-view");
            if (lv != null) { lv.setOpacity(1.0); lv.setTranslateY(0); }
        });
    }

    private void bindComboHoverScale(ComboBox<?> combo) {
        combo.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), combo);
            st.setToX(1.02); st.setToY(1.02); st.play();
        });
        combo.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), combo);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });
    }

    // ==================== B 站 API ====================
    private String fetchVideoCoverPic(String bvid) {
        try {
            URL url = new URL("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (biliCookie != null && !biliCookie.isEmpty()) conn.setRequestProperty("Cookie", biliCookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String json = sb.toString();
            if (!json.contains("\"code\":0")) return null;
            Matcher m = Pattern.compile("\"pic\":\"([^\"]+)\"").matcher(json);
            if (m.find()) return m.group(1).replace("\\/", "/");
        } catch (Exception e) {
            System.out.println("[cover] 获取封面失败 " + bvid + ": " + e.getMessage());
        }
        return null;
    }

    private long fetchUserMid(String cookie) {
        try {
            URL url = new URL("https://api.bilibili.com/x/web-interface/nav");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Cookie", cookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            Matcher m = Pattern.compile("\"mid\":(\\d+)").matcher(sb.toString());
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception e) {
            System.out.println("[nav] 获取 mid 失败: " + e.getMessage());
        }
        return 0;
    }

    private List<FavFolder> fetchFavFolders(String cookie, long mid) {
        return fetchFavFolders(cookie, mid, false);
    }

    // collected=false 拉"创建的收藏夹"，true 拉"我追的合集"
    private List<FavFolder> fetchFavFolders(String cookie, long mid, boolean collected) {
        List<FavFolder> result = new ArrayList<>();
        try {
            String type = collected ? "collected" : "created";
            URL url = new URL("https://api.bilibili.com/x/v3/fav/folder/" + type + "/list?up_mid=" + mid + "&pn=1&ps=20");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://space.bilibili.com/" + mid + "/favlist");
            conn.setRequestProperty("Cookie", cookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String json = sb.toString();
            System.out.println("[fav] " + type + "/list 响应前 300 字符: "
                    + (json.length() > 300 ? json.substring(0, 300) : json));
            if (!json.contains("\"code\":0")) {
                System.out.println("[fav] " + type + "/list 接口 code 非 0，可能未登录或风控");
                return result;
            }
            if (json.contains("\"data\":null")) {
                System.out.println("[fav] " + type + "/list data 为 null —— collected 必须登录且只能查自己，cookie 可能失效");
                return result;
            }
            int listIdx = json.indexOf("\"list\":[");
            if (listIdx < 0) {
                System.out.println("[fav] " + type + "/list data 为 null 或没有 list 数组");
                return result;
            }
            int arrStart = json.indexOf('[', listIdx);
            int arrEnd = findJsonArrayEnd(json, arrStart);
            if (arrEnd < 0) return result;
            List<String> objs = new ArrayList<>();
            sliceJsonObjects(json.substring(arrStart, arrEnd), objs);
            int seasonCount = 0;
            for (String obj : objs) {
                long fid = findLong(obj, "\"id\"");
                String t = findString(obj, "title");
                long mc = findLong(obj, "\"media_count\"");
                if (fid > 0 && t != null && !t.isEmpty()) {
                    // collected/list 会把"关注的别人收藏夹"和"追更的视频合集"混在一起返回，靠 attr 区分：
                    //   attr=22 关注的别人收藏夹
                    //   attr=0  追更视频合集
                    //   attr=1  已失效
                    boolean isSeason = false;
                    if (collected) {
                        long attr = findLong(obj, "\"attr\"");
                        isSeason = (attr == 0);
                    }
                    if (isSeason) seasonCount++;
                    result.add(new FavFolder(fid, t, (int) mc, isSeason));
                }
            }
            System.out.println("[fav] " + type + "/list 解析到 " + result.size() + " 个收藏夹"
                    + (collected ? "（其中追更合集 " + seasonCount + " 个）" : ""));
        } catch (Exception e) {
            System.out.println("[fav] 获取收藏夹列表失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取"我追的合集"列表。
     * 调 x/v3/fav/folder/collected/list，过滤 type=21 的条目。
     * 老的 /x/space/fav/season/list（不带 season_id）已废弃，返回 -400。
     */
    private List<FavFolder> fetchSeasonFolders(String cookie, long mid) {
        List<FavFolder> result = new ArrayList<>();
        try {
            String urlStr = "https://api.bilibili.com/x/v3/fav/folder/collected/list"
                    + "?pn=1&ps=20&up_mid=" + mid + "&platform=web";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://space.bilibili.com/" + mid + "/favlist");
            conn.setRequestProperty("Cookie", cookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String json = sb.toString();
            if (!json.contains("\"code\":0")) return result;
            int listIdx = json.indexOf("\"list\"");
            if (listIdx < 0) return result;
            int arrStart = json.indexOf('[', listIdx);
            if (arrStart < 0) return result;
            int arrEnd = findJsonArrayEnd(json, arrStart);
            if (arrEnd < 0) return result;
            List<String> objs = new ArrayList<>();
            sliceJsonObjects(json.substring(arrStart, arrEnd), objs);
            for (String obj : objs) {
                long id = findLong(obj, "\"id\"");
                String title = findString(obj, "\"title\"");
                long mc = findLong(obj, "\"media_count\"");
                long type = findLong(obj, "\"type\"");
                // collected/list 返回混合列表：type=22 关注的别人收藏夹 / type=21 追更合集
                // 只保留合集条目，且 season=true，让 loadFavVideos 自动分流到 fetchSeasonContents
                if (id > 0 && title != null && (type == 21 || type == 0)) {
                    result.add(new FavFolder(id, title, (int) mc, true));
                }
            }
            System.out.println("[season] collected/list 解析到 " + result.size() + " 个追更合集");
        } catch (Exception e) {
            System.out.println("[season] 获取合集列表失败: " + e.getMessage());
        }
        return result;
    }

    // 从 arrStart（指向 '['）找到匹配的数组结束下标（不含），字符串内的括号不计数
    private int findJsonArrayEnd(String json, int arrStart) {
        int depth = 0;
        boolean inStr = false;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i + 1; }
        }
        return -1;
    }

    // 把 array（以 '[' 开头）中的顶层 {..} 对象逐个切片到 out
    private void sliceJsonObjects(String array, List<String> out) {
        int pos = 0;
        while (true) {
            int s = array.indexOf('{', pos);
            if (s < 0) return;
            int depth = 0;
            boolean inStr = false;
            int e = -1;
            for (int i = s; i < array.length(); i++) {
                char c = array.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                } else if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { e = i + 1; break; } }
            }
            if (e < 0) return;
            out.add(array.substring(s, e));
            pos = e;
        }
    }

    // ==================== 追番与追剧 ====================
    private List<BangumiItem> fetchBangumiFollows(String cookie, long mid) {
        List<BangumiItem> result = new ArrayList<>();
        try {
            for (int type = 1; type <= 2; type++) {
                URL url = new URL("https://api.bilibili.com/x/space/bangumi/follow/list?vmid=" + mid
                        + "&type=" + type + "&follow_status=0&pn=1&ps=50&order=pubdate");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Referer", "https://www.bilibili.com/");
                conn.setRequestProperty("Cookie", cookie);
                conn.connect();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                String json = sb.toString();
                if (!json.contains("\"code\":0")) continue;
                int listIdx = json.indexOf("\"list\":[");
                if (listIdx < 0) continue;
                int arrStart = json.indexOf('[', listIdx);
                int arrEnd = findJsonArrayEnd(json, arrStart);
                if (arrEnd < 0) continue;
                List<String> objs = new ArrayList<>();
                sliceJsonObjects(json.substring(arrStart, arrEnd), objs);
                for (String obj : objs) {
                    Long sid = findStringLong(obj, "season_id");
                    if (sid != null) {
                        String t = findString(obj, "title");
                        String cv = findString(obj, "cover");
                        String badge = findString(obj, "badge");
                        String idxShow = findString(obj, "index_show");
                        String b = (badge == null || badge.isEmpty() ? "" : badge)
                                + (idxShow != null && !idxShow.isEmpty() ? " · " + idxShow : "");
                        result.add(new BangumiItem(sid, t == null ? "未知" : t, cv == null ? "" : cv, b, type));
                    }
                }
                Thread.sleep(300);
            }
        } catch (Exception e) {
            System.out.println("[bangumi] 获取追番/追剧列表失败: " + e.getMessage());
        }
        return result;
    }

    private List<FavVideo> fetchSeasonEps(String cookie, long seasonId) {
        List<FavVideo> result = new ArrayList<>();
        try {
            URL url = new URL("https://api.bilibili.com/pgc/view/web/season?season_id=" + seasonId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Cookie", cookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String json = sb.toString();
            int epsIdx = json.indexOf("\"episodes\":[");
            if (epsIdx < 0) return result;
            int arrStart = json.indexOf('[', epsIdx);
            int arrEnd = findJsonArrayEnd(json, arrStart);
            if (arrEnd < 0) return result;
            List<String> objs = new ArrayList<>();
            sliceJsonObjects(json.substring(arrStart, arrEnd), objs);
            for (String obj : objs) {
                String bvid = findString(obj, "bvid");
                if (bvid != null && !bvid.isEmpty()) {
                    String t = findString(obj, "title");
                    String lt = findString(obj, "long_title");
                    Long dur = findStringLong(obj, "duration");
                    String label = (t == null ? "" : t)
                            + (lt != null && !lt.isEmpty() && !lt.equals(t) ? " " + lt : "");
                    result.add(new FavVideo(bvid, label.isEmpty() ? "未知剧集" : label, "番剧",
                            dur == null ? 0 : dur.intValue()));
                }
            }
        } catch (Exception e) {
            System.out.println("[bangumi] 获取剧集列表失败: " + e.getMessage());
        }
        return result;
    }

    private String findString(String json, String key) {
        String pureKey = key.replace("\"", "");
        Pattern p = Pattern.compile("\"" + pureKey + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private Long findStringLong(String json, String key) {
        String pureKey = key.replace("\"", "");
        Matcher m = Pattern.compile("\"" + pureKey + "\":(-?\\d+)").matcher(json);
        if (m.find()) return Long.parseLong(m.group(1));
        return null;
    }

    private int findInt(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (start == i) return 0;
        try { return Integer.parseInt(json.substring(start, i)); } catch (NumberFormatException e) { return 0; }
    }

    private long findLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (start == i) return 0;
        try { return Long.parseLong(json.substring(start, i)); } catch (NumberFormatException e) { return 0; }
    }

    private boolean parseOneVideo(String obj, List<FavVideo> result, Set<String> seenBvids) {
        try {
            String bvid = findString(obj, "\"bvid\"");
            if (bvid == null || bvid.length() < 10 || !bvid.startsWith("BV")) return false;
            if (!seenBvids.add(bvid)) return false;
            int type = findInt(obj, "\"type\"");
            if (type != 2 && type != 12) return false;
            // 已失效视频：attr 的 bit0 置位表示被删除/下架，加载时直接忽略，不进入列表
            long attr = findLong(obj, "\"attr\"");
            if ((attr & 1L) != 0) return false;
            String rawTitle = findString(obj, "\"title\"");
            if (rawTitle == null || rawTitle.isEmpty()) return false;
            String title = unescapeAndCleanTitle(rawTitle);
            int duration = findInt(obj, "\"duration\"");
            String up = "";
            int upperIdx = obj.indexOf("\"upper\"");
            if (upperIdx >= 0) {
                String upperObj = obj.substring(upperIdx);
                up = findString(upperObj, "\"name\"");
            }
            if (up == null) up = "";
            result.add(new FavVideo(bvid, title, up, duration));
            return true;
        } catch (Exception e) { return false; }
    }

    // 追更的视频合集内容：/x/space/fav/season/list（参数是 season_id，与收藏夹的 resource/list 不同）
    /**
     * 分页拉取追更合集内容。
     *
     * @param sink 非空时每解析完一页就回调一次（子线程调用），用于边拉取边显示；传 null 则只返回完整结果
     */
    private List<FavVideo> fetchSeasonContents(String cookie, long seasonId, FavPageSink sink) {
        List<FavVideo> result = new ArrayList<>();
        Set<String> seenBvids = new HashSet<>();
        try {
            int pn = 1;
            int total = 0;
            while (true) {
                URL url = new URL("https://api.bilibili.com/x/space/fav/season/list"
                        + "?season_id=" + seasonId + "&pn=" + pn + "&ps=36"
                        + "&web_location=333.1387"
                        + "&x-bili-locale-json=%7B%22c_locale%22%3A%7B%22language%22%3A%22zh%22%2C%22script%22%3A%22Hans%22%7D%2C%22always_translate%22%3Afalse%7D"
                        + "&x-bili-device-req-json=%7B%22platform%22%3A%22web%22%2C%22device%22%3A%22pc%22%2C%22spmid%22%3A%22333.1387%22%2C%22mobi_app%22%3A%22web_cn%22%7D");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Referer", "https://www.bilibili.com/");
                conn.setRequestProperty("Cookie", cookie);
                conn.connect();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                String json = sb.toString();
                if (!json.contains("\"code\":0")) break;
                if (total == 0) {
                    Matcher tm = Pattern.compile("\"media_count\":(\\d+)").matcher(json);
                    if (tm.find()) total = Integer.parseInt(tm.group(1));
                }
                int mediasIdx = json.indexOf("\"medias\":[");
                if (mediasIdx < 0) break;
                int arrStart = json.indexOf('[', mediasIdx);
                int arrEnd = findJsonArrayEnd(json, arrStart);
                if (arrEnd < 0) break;
                List<String> objs = new ArrayList<>();
                sliceJsonObjects(json.substring(arrStart, arrEnd), objs);
                if (objs.isEmpty()) break;
                List<FavVideo> page = new ArrayList<>();
                for (String obj : objs) {
                    // 合集条目没有 type 字段，不能复用 parseOneVideo（它要求 type 为 2 或 12，会把合集视频全过滤掉）
                    String bvid = findString(obj, "\"bvid\"");
                    if (bvid == null || bvid.length() < 10 || !bvid.startsWith("BV")) continue;
                    if (!seenBvids.add(bvid)) continue;
                    String title = unescapeAndCleanTitle(findString(obj, "\"title\""));
                    int duration = findInt(obj, "\"duration\"");
                    String up = "";
                    int upperIdx = obj.indexOf("\"upper\"");
                    if (upperIdx >= 0) {
                        up = findString(obj.substring(upperIdx), "\"name\"");
                    }
                    if (up == null) up = "";
                    page.add(new FavVideo(bvid, title, up, duration));
                }
                boolean hasMore = json.contains("\"has_more\":true") || json.contains("\"has_more\":1");
                if (!page.isEmpty()) result.addAll(page);
                // 每页解析完就往外报，UI 可以先把这一页渲染出来
                if (sink != null) sink.onPage(page, total, !hasMore);
                if (!hasMore) break;
                pn++;
                if (pn > 50) break;
            }
            // 收尾通知：让调用方无论从哪个分支退出都能收到"已结束"
            if (sink != null) sink.onPage(List.of(), total, true);
            System.out.println("[fav] season/list season_id=" + seasonId + " 解析到 " + result.size() + " 个视频");
        } catch (Exception e) {
            System.out.println("[fav] 获取合集内容失败: " + e.getMessage());
        }
        return result;
    }

    private List<FavVideo> fetchFavResources(String cookie, long mediaId) {
        return fetchFavResources(cookie, mediaId, null);
    }

    /**
     * 分页拉取收藏夹内容。
     *
     * @param sink 非空时每解析完一页就回调一次（子线程调用），用于边拉取边显示；传 null 则只返回完整结果
     */
    private List<FavVideo> fetchFavResources(String cookie, long mediaId, FavPageSink sink) {
        List<FavVideo> result = new ArrayList<>();
        Set<String> seenBvids = new HashSet<>();
        int skipped = 0;
        try {
            int pn = 1;
            int total = 0;
            while (true) {
                URL url = new URL("https://api.bilibili.com/x/v3/fav/resource/list"
                        + "?media_id=" + mediaId + "&pn=" + pn + "&ps=20&order=mtime&type=0&platform=web");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Referer", "https://www.bilibili.com/");
                conn.setRequestProperty("Cookie", cookie);
                conn.connect();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                String json = sb.toString();
                if (!json.contains("\"code\":0")) break;
                // 第一页响应里 info.media_count 即收藏夹媒体总数，用于计算加载百分比
                if (total == 0) {
                    Matcher tm = Pattern.compile("\"media_count\":(\\d+)").matcher(json);
                    if (tm.find()) total = Integer.parseInt(tm.group(1));
                }
                int mediasIdx = json.indexOf("\"medias\":[");
                if (mediasIdx < 0) break;
                int arrStart = json.indexOf('[', mediasIdx);
                int bracketCount = 0, arrEnd = -1;
                for (int i = arrStart; i < json.length(); i++) {
                    if (json.charAt(i) == '[') bracketCount++;
                    if (json.charAt(i) == ']') bracketCount--;
                    if (bracketCount == 0) { arrEnd = i; break; }
                }
                if (arrEnd < 0) break;
                String arrContent = json.substring(arrStart + 1, arrEnd);
                List<FavVideo> page = new ArrayList<>();
                int depth = 0, objStart = -1;
                for (int i = 0; i < arrContent.length(); i++) {
                    char c = arrContent.charAt(i);
                    if (c == '{') { if (depth == 0) objStart = i; depth++; }
                    else if (c == '}') {
                        depth--;
                        if (depth == 0 && objStart >= 0) {
                            if (!parseOneVideo(arrContent.substring(objStart, i + 1), page, seenBvids)) skipped++;
                            objStart = -1;
                        }
                    }
                }
                boolean hasMore = json.contains("\"has_more\":true") || json.contains("\"has_more\":1");
                if (!page.isEmpty()) result.addAll(page);
                // 每页解析完就往外报，UI 可以先把这一页渲染出来
                if (sink != null) sink.onPage(page, total, !hasMore);
                if (!hasMore) break;
                pn++;
                if (pn > 50) break;
                Thread.sleep(800);
            }
            // 收尾通知：让调用方无论从哪个分支退出都能收到"已结束"
            if (skipped > 0) System.out.println("[fav] 已忽略 " + skipped + " 个失效视频");
            if (sink != null) sink.onPage(List.of(), total, true);
        } catch (Exception e) {
            System.out.println("[fav] 拉收藏夹失败: " + e);
        }
        return result;
    }

    // ==================== 头像 ====================
    private void loadAvatar(String cookie) {
        new Thread(() -> {
            String faceUrl = fetchUserAvatar(cookie);
            Platform.runLater(() -> {
                if (faceUrl != null) {
                    avatarImg.setImage(new Image(faceUrl, 32, 32, true, true));
                    avatarImg.setVisible(true);
                    avatarPlaceholder.setVisible(false);
                } else {
                    avatarPlaceholder.setText("V");
                    avatarPlaceholder.setStyle("-fx-background-color:#66BB6A;-fx-background-radius:50%;-fx-text-fill:white;-fx-font-size:16px;-fx-font-weight:bold;");
                    avatarImg.setVisible(false);
                    avatarPlaceholder.setVisible(true);
                }
            });
        }).start();
    }

    private static String fetchUserAvatar(String cookie) {
        try {
            URL url = new URL("https://api.bilibili.com/x/web-interface/nav");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Cookie", cookie);
            conn.connect();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            Matcher m = Pattern.compile("\"face\":\"([^\"]+)\"").matcher(sb.toString());
            if (m.find()) return m.group(1).replace("\\/", "/");
        } catch (Exception e) {
            System.out.println("[nav] 获取头像失败: " + e.getMessage());
        }
        return null;
    }

    // ==================== 工具 ====================
    private static String formatTime(double ms) {
        if (ms <= 0 || ms == Double.MAX_VALUE) return "0:00";
        long s = (long) (ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private BorderPane createPlaceholder(String text) {
        BorderPane p = new BorderPane();
        p.setStyle("-fx-background-color:transparent;");
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:16px;");
        p.setCenter(lbl);
        return p;
    }

    /**
     * 让列表 ScrollPane 支持鼠标滚轮平滑滚动：拦截默认滚动，用短动画把 vvalue 缓动到目标位置，
     * 连续滚动时目标累加，避免生硬跳变（"闪一下"）。不影响原有的滚动到底懒加载监听。
     */
    private void attachSmoothVerticalScroll(ScrollPane scroll) {
        final double[] target = {scroll.getVvalue()};
        final Timeline[] anim = {null};
        // 用捕获阶段过滤器拦截，抢在 ScrollPane 自身默认滚动处理器之前执行并 consume；
        // 否则默认滚动会先「跳一格」，再叠加我们的动画，呈现生硬跳变。拦截后完全由动画缓动驱动。
        scroll.addEventFilter(ScrollEvent.SCROLL, (ScrollEvent e) -> {
            if (e.getDeltaY() == 0) return;
            // 按「一次滚轮滚动的行数」计算位移，而非固定 vvalue 比例：
            // 系统给的滚动量 deltaY 已包含其设定的滚轮行数（以像素计），换算成 vvalue 增量后，
            // 不论列表长短，一次滚轮滚过的行数都一致，不再出现短列表猛跳、长列表几乎不动。
            double viewport = scroll.getViewportBounds().getHeight();
            double contentH = scroll.getContent().getLayoutBounds().getHeight();
            double range = contentH - viewport;
            if (range <= 1) return;                  // 内容未超出视口，无需滚动
            e.consume();                              // 捕获阶段拦截默认滚动，改由动画驱动
            double dv = e.getDeltaY() / range;        // 像素位移 → vvalue 增量（一次滚轮=系统设定的行数）
            target[0] = Math.max(0.0, Math.min(1.0, target[0] - dv));
            if (anim[0] != null) anim[0].stop();
            double from = scroll.getVvalue();
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(scroll.vvalueProperty(), from, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.millis(180), new KeyValue(scroll.vvalueProperty(), target[0], Interpolator.EASE_OUT)));
            anim[0] = tl;
            tl.play();
        });
    }

    private void setupScrollbarGradient(ScrollPane scroll) {
        final int[] LIGHT_RGB = {187, 222, 251};
        final int[] DEEP_RGB = {66, 165, 245};
        scroll.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) return;
            Platform.runLater(() -> {
                Node thumbNode = scroll.lookup(".scroll-bar:vertical .thumb");
                if (!(thumbNode instanceof Region thumb)) return;
                thumb.setBackground(new Background(new BackgroundFill(
                        Color.web("#BBDEFB"), new CornerRadii(5), Insets.EMPTY)));
                thumb.hoverProperty().addListener((hObs, wasHover, isHover) -> {
                    int[] st = isHover ? LIGHT_RGB : DEEP_RGB;
                    int[] ed = isHover ? DEEP_RGB : LIGHT_RGB;
                    long dur = (long) ((isHover ? 180 : 250) * 1_000_000);
                    long start = System.nanoTime();
                    AnimationTimer timer = new AnimationTimer() {
                        @Override
                        public void handle(long now) {
                            double frac = (now - start) / (double) dur;
                            if (frac > 1.0) frac = 1.0;
                            int r = (int) (st[0] + (ed[0] - st[0]) * frac), g = (int) (st[1] + (ed[1] - st[1]) * frac), b = (int) (st[2] + (ed[2] - st[2]) * frac);
                            // 用 setBackground 而非 setStyle：setStyle 每帧都要重解析 CSS 字符串并重排父链
                            thumb.setBackground(new Background(new BackgroundFill(
                                    Color.rgb(r, g, b), new CornerRadii(5), Insets.EMPTY)));
                            if (frac >= 1.0) stop();
                        }
                    };
                    timer.start();
                });
            });
        });
    }

    private void styleCircleBtn(Button btn, boolean primary, int size) {
        btn.setMinWidth(size); btn.setMaxWidth(size); btn.setMinHeight(size); btn.setMaxHeight(size);
        if (primary)
            btn.setStyle(String.format("-fx-background-color:%s;-fx-text-fill:white;-fx-font-size:18px;-fx-background-radius:50%%;-fx-cursor:hand;", C_PRIMARY));
        else
            btn.setStyle(String.format("-fx-background-color:%s;-fx-text-fill:%s;-fx-font-size:15px;-fx-background-radius:50%%;-fx-cursor:hand;", C_BTN_BG, C_PRIMARY));
    }

    private void styleWindowButton(Button btn, int fontSize) {
        btn.setMinWidth(WIN_BTN_SIZE); btn.setMaxWidth(WIN_BTN_SIZE); btn.setMinHeight(WIN_BTN_SIZE); btn.setMaxHeight(WIN_BTN_SIZE);
        String base = String.format("-fx-background-color:transparent;-fx-text-fill:white;-fx-font-size:%dpx;-fx-font-weight:300;-fx-padding:0;-fx-alignment:center;-fx-cursor:hand;-fx-background-radius:50%%;", fontSize);
        btn.setStyle(base);
        String hover = String.format("-fx-background-color:rgba(255,255,255,0.15);-fx-text-fill:white;-fx-font-size:%dpx;-fx-font-weight:300;-fx-padding:0;-fx-alignment:center;-fx-cursor:hand;-fx-background-radius:50%%;", fontSize);
        // 原来 hover 直接 setStyle 整串替换，是硬切；改为背景色透明度渐变，观感与顶部导航一致。
        // 关闭按钮额外走红色（与 hover 态区分），同样用渐变。
        final boolean isClose = "×".equals(btn.getText());
        final ObjectProperty<Color> bg = new SimpleObjectProperty<>(Color.TRANSPARENT);
        btn.setBackground(new Background(new BackgroundFill(bg.get(), new CornerRadii(50), Insets.EMPTY)));
        Timeline fade = new Timeline();
        fade.setCycleCount(1);
        btn.setOnMouseEntered(e -> {
            fade.getKeyFrames().setAll(new KeyFrame(Duration.millis(160),
                    new KeyValue(bg, isClose ? Color.web("#E53935", 0.85) : Color.web("#FFFFFF", 0.15), Interpolator.EASE_OUT)));
            fade.play();
        });
        btn.setOnMouseExited(e -> {
            fade.getKeyFrames().setAll(new KeyFrame(Duration.millis(220),
                    new KeyValue(bg, Color.TRANSPARENT, Interpolator.EASE_OUT)));
            fade.play();
        });
        // 按下轻微缩小，补上 press 反馈
        btn.setOnMousePressed(e -> animateScale(btn, 0.9, 70));
        btn.setOnMouseReleased(e -> animateScale(btn, 1.0, 90));
    }

    private void setupTitleBarInteraction(Stage stage, BorderPane bar) {
        bar.setOnMouseMoved(e -> {
            double x = e.getX(), y = e.getY(), w = bar.getWidth();
            if (y <= EDGE && x <= EDGE) bar.setCursor(Cursor.NW_RESIZE);
            else if (y <= EDGE && x >= w - EDGE) bar.setCursor(Cursor.NE_RESIZE);
            else if (y <= EDGE) bar.setCursor(Cursor.V_RESIZE);
            else if (isInteractive(e.getPickResult().getIntersectedNode())) bar.setCursor(null);
            else bar.setCursor(Cursor.DEFAULT);
        });
        bar.setOnMousePressed(e -> {
            double x = e.getX(), y = e.getY(), w = bar.getWidth();
            if (isInteractive(e.getPickResult().getIntersectedNode())) { titleBarMode = ""; return; }
            if (y <= EDGE && x <= EDGE) {
                titleBarMode = "topleft"; bar.setCursor(Cursor.NW_RESIZE);
                rs[0] = e.getScreenX(); rs[1] = e.getScreenY(); ss[0] = stage.getWidth(); ss[1] = stage.getHeight(); sp[0] = stage.getX(); sp[1] = stage.getY();
            } else if (y <= EDGE && x >= w - EDGE) {
                titleBarMode = "topright"; bar.setCursor(Cursor.NE_RESIZE);
                rs[0] = e.getScreenX(); rs[1] = e.getScreenY(); ss[0] = stage.getWidth(); ss[1] = stage.getHeight(); sp[1] = stage.getY();
            } else if (y <= EDGE) {
                titleBarMode = "top"; bar.setCursor(Cursor.V_RESIZE);
                rs[1] = e.getScreenY(); ss[1] = stage.getHeight(); sp[1] = stage.getY();
            } else {
                titleBarMode = "move"; bar.setCursor(Cursor.DEFAULT);
                dragOffset[0] = e.getScreenX() - stage.getX(); dragOffset[1] = e.getScreenY() - stage.getY();
            }
        });
        bar.setOnMouseDragged(e -> {
            double mx = e.getScreenX(), my = e.getScreenY();
            switch (titleBarMode) {
                case "topleft":
                    double nwl = Math.max(MIN_W, ss[0] - (mx - rs[0])); double nhl = Math.max(MIN_H, ss[1] - (my - rs[1]));
                    stage.setX(sp[0] + (ss[0] - nwl)); stage.setY(sp[1] + (ss[1] - nhl)); stage.setWidth(nwl); stage.setHeight(nhl); break;
                case "topright":
                    double nwr = Math.max(MIN_W, ss[0] + (mx - rs[0])); double nhr = Math.max(MIN_H, ss[1] - (my - rs[1]));
                    // 右上角：右边缘固定，左边缘随之移动（保持底边固定）
                    stage.setX(sp[0] + ss[0] - nwr); stage.setY(sp[1] + (ss[1] - nhr)); stage.setWidth(nwr); stage.setHeight(nhr); break;
                case "top":
                    double bY = sp[1] + ss[1]; double nY = my - SHADOW_M; double nh = bY - nY;
                    if (nh < MIN_H) { nY = bY - MIN_H; nh = MIN_H; } stage.setY(nY); stage.setHeight(nh); break;
                case "move":
                    stage.setX(mx - dragOffset[0]); stage.setY(my - dragOffset[1]); break;
            }
        });
        bar.setOnMouseReleased(e -> { titleBarMode = ""; bar.setCursor(Cursor.DEFAULT); });
    }

    private void setupEdgeResize(Stage stage, Region root, Pane titleBar) {
        root.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (e.getY() <= titleBar.getHeight()) {
                if (isInteractive(e.getPickResult().getIntersectedNode())) root.setCursor(null);
                else root.setCursor(Cursor.DEFAULT);
                return;
            }
            String m = edgeAt5(e.getX(), e.getY(), root);
            switch (m) {
                case "left": case "right": root.setCursor(Cursor.H_RESIZE); break;
                case "bottom": root.setCursor(Cursor.V_RESIZE); break;
                case "bottomleft": root.setCursor(Cursor.SW_RESIZE); break;
                case "bottomright": root.setCursor(Cursor.SE_RESIZE); break;
                default:
                    if (isInteractive(e.getPickResult().getIntersectedNode())) root.setCursor(null);
                    else root.setCursor(Cursor.DEFAULT);
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getY() <= titleBar.getHeight()) return;
            String m = edgeAt5(e.getX(), e.getY(), root);
            if (m.isEmpty()) return;
            rs[0] = e.getScreenX(); rs[1] = e.getScreenY(); ss[0] = stage.getWidth(); ss[1] = stage.getHeight(); sp[0] = stage.getX(); sp[1] = stage.getY();
            e.consume();
            switch (m) {
                case "left": titleBarMode = "edge-left"; break;
                case "right": titleBarMode = "edge-right"; break;
                case "bottom": titleBarMode = "edge-bottom"; break;
                case "bottomleft": titleBarMode = "edge-bottomleft"; break;
                case "bottomright": titleBarMode = "edge-bottomright"; break;
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!titleBarMode.startsWith("edge-")) return;
            double mx = e.getScreenX(), my = e.getScreenY(), nw = ss[0], nh = ss[1], nx = sp[0], ny = sp[1];
            switch (titleBarMode) {
                case "edge-left": nw = Math.max(MIN_W, ss[0] - (mx - rs[0])); nx = sp[0] + (ss[0] - nw); break;
                case "edge-right": nw = Math.max(MIN_W, ss[0] + (mx - rs[0])); break;
                case "edge-bottom": nh = Math.max(MIN_H, ss[1] + (my - rs[1])); break;
                case "edge-bottomleft": nw = Math.max(MIN_W, ss[0] - (mx - rs[0])); nx = sp[0] + (ss[0] - nw); nh = Math.max(MIN_H, ss[1] + (my - rs[1])); break;
                case "edge-bottomright": nw = Math.max(MIN_W, ss[0] + (mx - rs[0])); nh = Math.max(MIN_H, ss[1] + (my - rs[1])); break;
            }
            stage.setX(nx); stage.setY(ny); stage.setWidth(nw); stage.setHeight(nh); root.layout(); e.consume();
        });
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> { if (titleBarMode.startsWith("edge-")) { titleBarMode = ""; e.consume(); } });
    }

    private String edgeAt5(double x, double y, Region root) {
        double w = root.getWidth(), h = root.getHeight();
        if (y >= h - EDGE && x <= EDGE) return "bottomleft";
        if (y >= h - EDGE && x >= w - EDGE) return "bottomright";
        if (x <= EDGE) return "left";
        if (x >= w - EDGE) return "right";
        if (y >= h - EDGE) return "bottom";
        return "";
    }

    // ==================== 设置页 ====================

    private HBox createSettingsPage() {
        HBox root = new HBox();
        // 透明：固定白底由 centerWhiteBg 提供，左侧淡蓝由 bgBlue 层提供
        root.setStyle("-fx-background-color:transparent;");

        VBox sidebar = settingsSidebarRef = createSettingsSidebar();
        sidebar.setMinWidth(180);
        sidebar.setMaxWidth(220);
        HBox.setHgrow(sidebar, Priority.NEVER);

        settingsContentArea = new StackPane();
        HBox.setHgrow(settingsContentArea, Priority.ALWAYS);
        // 透明：白底由底层 centerWhiteBg 提供（受白色底不透明度设置控制）
        settingsContentArea.setStyle("-fx-background-color:transparent;");
        // 右侧留 16px 间距：把滚动条从窗口右缘挪进来，避免点滚动条时触发边缘缩放（EDGE=10）
        settingsContentArea.setPadding(new Insets(0, 16, 0, 0));
        switchSettingsTab(0);

        root.getChildren().addAll(sidebar, settingsContentArea);
        return root;
    }

    private VBox createSettingsSidebar() {
        settingsNavCells.clear();

        VBox sidebar = new VBox(4);
        // 透明：左侧淡蓝由常驻 bgBlue 层提供（切到设置页时停在此处）
        sidebar.setStyle("-fx-background-color:transparent;");
        sidebar.setPadding(new Insets(16, 8, 16, 12));

        String[] categories = {"播放设置", "歌词设置", "账号管理", "外观设置", "快捷键", "关于"};
        for (int i = 0; i < categories.length; i++) {
            sidebar.getChildren().add(createSettingsNavItem(categories[i], i));
        }

        Region flex = new Region();
        VBox.setVgrow(flex, Priority.ALWAYS);
        sidebar.getChildren().add(flex);

        return sidebar;
    }

    private StackPane createSettingsNavItem(String text, int index) {
        Label label = new Label(text);
        label.setFont(Font.font(Font.getDefault().getFamily(), index == 0 ? FontWeight.BOLD : FontWeight.NORMAL, 13));
        // 内联样式固定文字色，优先级高于任何 CSS，避免被意外覆盖成白色
        label.setStyle("-fx-text-fill:" + (index == 0 ? "#0B3D91" : C_TEXT) + ";");
        HBox.setHgrow(label, Priority.ALWAYS);

        Region leftBar = new Region();
        leftBar.setPrefSize(4, 10);          // 4px 细、10px 短高，圆角取半宽(2)即胶囊形
        leftBar.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:2;");
        leftBar.setOpacity(index == 0 ? 1 : 0);
        // 收起态保留 0.35 的高度而非 0：scaleY 以中心为轴，从中心横向展开比从一条线撑开自然
        leftBar.setScaleY(index == 0 ? 1 : 0.35);
        leftBar.setMouseTransparent(true);
        // 左边距压到 2，让竖条紧贴后面淡蓝背景的左边缘
        HBox.setMargin(leftBar, new Insets(0, 10, 0, 2));

        HBox row = new HBox(leftBar, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(36);
        row.setMaxHeight(36);
        row.setPadding(new Insets(0, 10, 0, 0));

        // 选中态的淡蓝背景：单独一层，用 opacity 做淡入淡出（原先是 setStyle 硬切，无法过渡）
        Region activeBg = new Region();
        activeBg.setStyle("-fx-background-color:#E8F3FF;-fx-background-radius:8;");
        activeBg.setOpacity(index == 0 ? 1 : 0);
        activeBg.setMouseTransparent(true);

        Region hoverBg = new Region();
        hoverBg.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-background-radius:8;");
        hoverBg.setOpacity(0);
        hoverBg.setMouseTransparent(true);

        StackPane cell = new StackPane(activeBg, hoverBg, row);
        cell.setCursor(Cursor.HAND);
        cell.getProperties().put("leftBar", leftBar);
        cell.getProperties().put("label", label);
        cell.getProperties().put("hoverBg", hoverBg);
        cell.getProperties().put("activeBg", activeBg);
        settingsNavCells.add(cell);

        cell.setOnMouseEntered(e -> {
            if (currentSettingsTab != index) {
                Timeline in = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(hoverBg.opacityProperty(), hoverBg.getOpacity())),
                        new KeyFrame(Duration.millis(130),
                                new KeyValue(hoverBg.opacityProperty(), 0.08, Interpolator.EASE_OUT)));
                in.play();
            }
        });
        cell.setOnMouseExited(e -> {
            if (currentSettingsTab != index) {
                Timeline out = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(hoverBg.opacityProperty(), hoverBg.getOpacity())),
                        new KeyFrame(Duration.millis(160),
                                new KeyValue(hoverBg.opacityProperty(), 0.0, Interpolator.EASE_OUT)));
                out.play();
            }
        });
        cell.setOnMouseClicked(e -> switchSettingsTab(index));

        // 不做入场滑入/逐个淡入：设置页每次进入都会重建侧栏，
        // 动画重播会让整条选项栏（含背景块）又“动”一次，这里保持静态呈现。
        return cell;
    }

    private void applySettingsNavStyle(int index, boolean active) {
        StackPane cell = settingsNavCells.get(index);
        Region leftBar = (Region) cell.getProperties().get("leftBar");
        Label label = (Label) cell.getProperties().get("label");
        Region hoverBg = (Region) cell.getProperties().get("hoverBg");
        Region activeBg = (Region) cell.getProperties().get("activeBg");

        // 停掉上一次未播完的切换动画：快速连点时两条 Timeline 会同时写同一批属性，导致竖条抖动
        if (cell.getProperties().get("navAnim") instanceof Timeline running) running.stop();

        // 淡蓝背景淡入淡出 + 竖条从中心展开/收起，两者同一条 Timeline 保证节奏一致
        Timeline anim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(activeBg.opacityProperty(), activeBg.getOpacity()),
                        new KeyValue(leftBar.opacityProperty(), leftBar.getOpacity()),
                        new KeyValue(leftBar.scaleYProperty(), leftBar.getScaleY())),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(activeBg.opacityProperty(), active ? 1.0 : 0.0, Interpolator.EASE_OUT),
                        new KeyValue(leftBar.opacityProperty(), active ? 1.0 : 0.0, Interpolator.EASE_OUT),
                        new KeyValue(leftBar.scaleYProperty(), active ? 1.0 : 0.35, Interpolator.EASE_OUT)));
        cell.getProperties().put("navAnim", anim);
        anim.play();

        // 选中态：深蓝粗体文字 + 淡蓝底 + 左竖条区分；未选中深灰；清晰可读，不用白色
        label.setStyle("-fx-text-fill:" + (active ? "#0B3D91" : C_TEXT) + ";");
        label.setFont(Font.font(Font.getDefault().getFamily(), active ? FontWeight.BOLD : FontWeight.NORMAL, 13));
        if (!active) {
            hoverBg.setOpacity(0);
        }
    }

    private void switchSettingsTab(int index) {
        int prev = currentSettingsTab;
        currentSettingsTab = index;
        for (int i = 0; i < settingsNavCells.size(); i++) {
            applySettingsNavStyle(i, i == index);
        }
        // 批次号：连点侧栏时，旧面板淡出完成后才构建新面板，
        // 若期间又点了别的分类，早先那次必须作废，否则会把过期面板塞回来。
        final int token = ++settingsSwitchToken;

        Node old = settingsContentArea.getChildren().isEmpty() ? null : settingsContentArea.getChildren().get(0);
        // 切换方向：点后面的板块 → 新内容从右滑入；点前面的 → 从左滑入。
        // 有方向，滑入/滑出才连贯（否则只是"闪一下换页"）。
        // 首次进入没有旧面板可衔接，就只淡入、不横向滑。
        final double dir = (index >= prev) ? 1.0 : -1.0;
        final double slideDir = (old == null) ? 0.0 : dir;

        Runnable swapIn = () -> {
            if (token != settingsSwitchToken) return;
            Node panel = buildSettingsPanel(index);
            if (token != settingsSwitchToken) return;
            settingsContentArea.getChildren().setAll(panel);
            playSettingsPanelIn(panel, slideDir);
        };

        if (old == null) { swapIn.run(); return; }

        // 关键：先启动淡出动画，把新面板的构建推到下一帧。
        // 原来是在动画之前就同步把整块面板（几十个卡片/滑块/下拉）建完，
        // 点击后要先卡一下才开始动，观感就是"不丝滑"。
        // 旧内容朝"新内容来的反方向"滑出并淡出，给新内容让位。
        if (settingsSwitchOutTL != null) settingsSwitchOutTL.stop();   // 连点时停掉上一条，避免两条动画抢同一属性
        Timeline out = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(old.opacityProperty(), old.getOpacity()),
                        new KeyValue(old.scaleXProperty(), old.getScaleX()),
                        new KeyValue(old.scaleYProperty(), old.getScaleY()),
                        new KeyValue(old.translateXProperty(), old.getTranslateX())),
                new KeyFrame(Duration.millis(120),
                        new KeyValue(old.opacityProperty(), 0, Interpolator.EASE_IN),
                        new KeyValue(old.scaleXProperty(), 0.985, Interpolator.EASE_IN),
                        new KeyValue(old.scaleYProperty(), 0.985, Interpolator.EASE_IN),
                        new KeyValue(old.translateXProperty(), -26 * dir, Interpolator.EASE_IN)));
        settingsSwitchOutTL = out;
        out.setOnFinished(e -> Platform.runLater(swapIn));
        out.play();
    }

    /** 按分类构建对应的设置面板 */
    private Node buildSettingsPanel(int index) {
        return switch (index) {
            case 0 -> createPlaybackSettingsPanel();
            case 1 -> createLyricsSettingsPanel();
            case 2 -> createAccountSettingsPanel();
            case 3 -> createAppearanceSettingsPanel();
            case 4 -> createShortcutSettingsPanel();
            case 5 -> createAboutSettingsPanel();
            default -> createPlaceholder("未知分类");
        };
    }

    /**
     * 新面板入场：按切换方向滑入 + 淡入 + 轻微放大/上浮。
     * dir=+1 从右滑入（点了后面的板块），dir=-1 从左滑入，dir=0 只淡入（首次进入）。
     */
    private void playSettingsPanelIn(Node panel, double dir) {
        panel.setOpacity(0);
        panel.setScaleX(0.985);
        panel.setScaleY(0.985);
        panel.setTranslateX(30 * dir);
        panel.setTranslateY(10);
        Timeline in = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(panel.opacityProperty(), 0),
                        new KeyValue(panel.scaleXProperty(), 0.985),
                        new KeyValue(panel.scaleYProperty(), 0.985),
                        new KeyValue(panel.translateXProperty(), 30 * dir),
                        new KeyValue(panel.translateYProperty(), 10)),
                new KeyFrame(Duration.millis(300),
                        new KeyValue(panel.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.scaleYProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.translateXProperty(), 0.0, Interpolator.EASE_OUT),
                        new KeyValue(panel.translateYProperty(), 0.0, Interpolator.EASE_OUT)));
        in.setOnFinished(e -> { panel.setTranslateX(0); panel.setTranslateY(0); });
        in.play();
    }

    // ===== 播放设置 =====
    private ScrollPane createPlaybackSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));

        // 自动播放
        Node autoPlayBox = createCustomCheckBox(autoPlayOnStartup, v -> {
            autoPlayOnStartup = v;
            saveSettings();
            System.out.println("[settings] 自动播放 → " + v);
        });

        // 循环模式
        ComboBox<String> loopCombo = createComboBox("列表循环", "单曲循环", "随机播放");
        loopCombo.setValue(loopMode);
        loopCombo.setOnAction(e -> {
            loopMode = loopCombo.getValue();
            saveSettings();
            System.out.println("[settings] 循环模式 → " + loopMode);
        });

        // 默认音量：拖动即时生效（只改内存 + 音频），松手才落盘。
        // 原来在 onChange 里 saveSettings()，等于拖动过程中每帧写一次磁盘，是明显卡顿来源。
        // 写法与歌词字号滑块一致：onChange 预览、onRelease 落盘。
        final Slider[] volumeSliderRef = new Slider[1];
        Node volumeSlider = styleSettingsSlider(0, 100, defaultVolume, v -> {
            defaultVolume = (int) Math.round(v);
            if (audioPlayer != null) audioPlayer.setVolume(defaultVolume / 100.0);
        }, () -> {
            defaultVolume = (int) Math.round(volumeSliderRef[0].getValue());
            if (audioPlayer != null) audioPlayer.setVolume(defaultVolume / 100.0);
            saveSettings();
            System.out.println("[settings] 默认音量 → " + defaultVolume);
        });
        volumeSliderRef[0] = getSliderFromStyledBox(volumeSlider);

        // 音频质量
        ComboBox<String> qualityCombo = createComboBox("高音质", "标准音质");
        qualityCombo.setValue(highAudioQuality ? "高音质" : "标准音质");
        qualityCombo.setOnAction(e -> {
            String v = qualityCombo.getValue();
            boolean high = !"标准音质".equals(v);
            highAudioQuality = high;
            if (audioPlayer != null) audioPlayer.setHighQuality(high);
            saveSettings();
            System.out.println("[settings] 音频质量 → " + v);
            // 音频已下载到本地，改设置不会影响当前曲目，需重新拉取才生效
            if (currentBvid != null && audioPlayer != null) {
                audioPlayer.playBvid(currentBvid);
                setPlayIcon(true);
            }
        });

        content.getChildren().addAll(
                createSettingsCard("播放",
                        createSettingRow("自动播放", "打开程序时自动播放上一首歌曲", autoPlayBox),
                        createSettingRow("循环模式", "单曲循环 / 列表循环 / 随机播放", loopCombo),
                        createSettingRow("默认音量", "启动时的默认音量", volumeSlider)
                ),
                createSettingsCard("音频",
                        createSettingRow("音频质量", "优先高音质或标准音质", qualityCombo)
                )
        );
        content.getChildren().add(0, buildResetBar("播放设置", this::resetPlaybackSettings));
        return wrapSettingsScroll(content);
    }

    // ===== 歌词设置 =====
    private ScrollPane createLyricsSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));

        // 播放页右侧区域显示播放列表还是歌词（默认播放列表，即原本可点选的列表界面）
        ComboBox<String> displayCombo = createComboBox("播放列表", "歌词");
        displayCombo.setValue(playPageDisplay);
        displayCombo.setOnAction(e -> {
            playPageDisplay = displayCombo.getValue();
            saveSettings();
            System.out.println("[settings] 播放页显示 → " + playPageDisplay);
            applyPlayPageDisplay();
        });

        // 歌词来源优先级：按顺序尝试，前一个拿不到才试下一个
        ComboBox<String> sourceCombo = createComboBox(LYRIC_SOURCE_LABELS);
        sourceCombo.setValue(labelOfLyricSourceOrder(lyricSourceOrder));
        sourceCombo.setOnAction(e -> {
            String label = sourceCombo.getValue();
            List<String> order = LYRIC_SOURCE_ORDERS.get(label);
            if (order == null) return;
            lyricSourceOrder = order;
            saveSettings();
            System.out.println("[settings] 歌词来源 → " + label);
            // 换了来源就重新取一次，否则要等下一首歌才见效
            refreshLyricWithCurrentSettings();
        });

        // 字体（分中英文）：只列常用的一小撮（系统里真实存在的才列），避免几百项的下拉框没法用
        List<String> cnFams = pickFontFamilies(LYRIC_FONT_CN_CANDIDATES, null, lyricFontCN);
        List<String> enFams = pickFontFamilies(LYRIC_FONT_EN_CANDIDATES, EMBEDDED_FONT_OPTION, lyricFontEN);
        ComboBox<String> fontCnCombo = createComboBox(cnFams.toArray(new String[0]));
        fontCnCombo.setValue(lyricFontCN);
        fontCnCombo.setOnAction(e -> {
            String v = fontCnCombo.getValue();
            if (v == null || v.trim().isEmpty()) return;
            lyricFontCN = v.trim();
            saveSettings();
            if (lyricView != null) lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
            applyD3();
            System.out.println("[settings] 中文字体 → " + lyricFontCN);
        });
        // 支持把字体文件（ttf/otf/ttc）从文件管理器拖入，直接注入为中文字体
        fontCnCombo.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()
                    && e.getDragboard().getFiles().stream().anyMatch(PlayerApp::isFontFile)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        fontCnCombo.setOnDragDropped(e -> {
            boolean ok = false;
            if (e.getDragboard().hasFiles()) {
                for (java.io.File fx : e.getDragboard().getFiles()) {
                    if (!isFontFile(fx)) continue;
                    java.io.File saved = copyFontToData(fx);            // 复制字体到应用数据目录
                    String loadPath = (saved != null) ? saved.getAbsolutePath() : fx.getAbsolutePath();
                    Font ft = Font.loadFont(new java.io.File(loadPath).toURI().toString(), 12);
                    if (ft != null) {
                        String fam = ft.getFamily();
                        lyricFontCN = fam;
                        lyricFontCNPath = loadPath;                       // 持久化副本路径（存的是字体本身）
                        if (!fontCnCombo.getItems().contains(fam)) fontCnCombo.getItems().add(fam);
                        fontCnCombo.setValue(fam);
                        saveSettings();
                        if (lyricView != null) lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
                        applyD3();
                        System.out.println("[settings] 中文字体(拖入) → " + fam + " @ " + loadPath);
                        ok = true;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        ComboBox<String> fontEnCombo = createComboBox(enFams.toArray(new String[0]));
        fontEnCombo.setValue(lyricFontEN);
        fontEnCombo.setOnAction(e -> {
            String v = fontEnCombo.getValue();
            if (v == null || v.trim().isEmpty()) return;
            lyricFontEN = v.trim();
            saveSettings();
            if (lyricView != null) lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
            applyD3();
            System.out.println("[settings] 英文字体 → " + lyricFontEN);
        });
        // 支持把字体文件（ttf/otf/ttc）从文件管理器拖入，直接注入为英文字体
        fontEnCombo.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()
                    && e.getDragboard().getFiles().stream().anyMatch(PlayerApp::isFontFile)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        fontEnCombo.setOnDragDropped(e -> {
            boolean ok = false;
            if (e.getDragboard().hasFiles()) {
                for (java.io.File fx : e.getDragboard().getFiles()) {
                    if (!isFontFile(fx)) continue;
                    java.io.File saved = copyFontToData(fx);            // 复制字体到应用数据目录
                    String loadPath = (saved != null) ? saved.getAbsolutePath() : fx.getAbsolutePath();
                    Font ft = Font.loadFont(new java.io.File(loadPath).toURI().toString(), 12);
                    if (ft != null) {
                        String fam = ft.getFamily();
                        lyricFontEN = fam;
                        lyricFontENPath = loadPath;                       // 持久化副本路径（存的是字体本身）
                        if (!fontEnCombo.getItems().contains(fam)) fontEnCombo.getItems().add(fam);
                        fontEnCombo.setValue(fam);
                        saveSettings();
                        if (lyricView != null) lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
                        applyD3();
                        System.out.println("[settings] 英文字体(拖入) → " + fam + " @ " + loadPath);
                        ok = true;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });

        // 歌词字体大小（主窗口歌词视图）：拖动实时生效，范围 12–36px
        Node fontSizeBox = rangedSlider(12, 36, lyricFontSize, 1, v -> ((int) Math.round(v)) + " px",
                v -> {
                    lyricFontSize = (int) Math.round(v);
                    if (lyricView != null) lyricView.setFontSize(lyricFontSize);
                    applyD3();
                },
                () -> { saveSettings(); System.out.println("[settings] 歌词字号 → " + lyricFontSize + "px"); });

        // 是否加粗歌词（普通行也用粗体）
        Node boldBox = createCustomCheckBox(lyricBold, v -> {
            lyricBold = v;
            saveSettings();
            if (lyricView != null) lyricView.setBold(lyricBold);
            System.out.println("[settings] 加粗歌词 → " + v);
        });

        // 3D 桌面歌词（LyricRendererFX）：独立悬浮窗，由播放进度驱动
        Node rendererBox = createCustomCheckBox(useLyricRendererFX, v -> {
            useLyricRendererFX = v;
            saveSettings();
            syncLyricRenderer();
            System.out.println("[settings] 3D 桌面歌词 → " + v);
        });

        // 3D 歌词水平位置：连续滑块（任意位置都能停）+ 左/中/右 一键定位
        // 桌面歌词是全屏透明窗且整窗鼠标穿透，没法靠拖动窗口来挪位置，所以这里给出精确控制
        lyricAnchorSlider = new Slider(0, 1, d3AnchorX);
        // 注意：Slider 没有 setStep（那是 ScrollBar 的），步进要用 blockIncrement
        lyricAnchorSlider.setBlockIncrement(0.01);
        lyricAnchorSlider.getStyleClass().add("progress-slider");
        lyricAnchorSlider.setCursor(Cursor.HAND);
        lyricAnchorSlider.setPrefWidth(150);
        lyricAnchorSlider.setShowTickLabels(false);
        lyricAnchorSlider.setShowTickMarks(false);
        setupSliderTrackFill(lyricAnchorSlider);
        Label anchorValLabel = new Label(Math.round(d3AnchorX * 100) + "%");
        anchorValLabel.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:11px;-fx-font-weight:bold;");
        anchorValLabel.setPrefWidth(44);
        anchorValLabel.setAlignment(Pos.CENTER);
        lyricAnchorSlider.valueProperty().addListener((obs, o, n) -> {
            anchorValLabel.setText(Math.round(n.doubleValue() * 100) + "%");
            d3AnchorX = n.doubleValue();
            lyricAnchor = nearestAnchorLabel(d3AnchorX);
            applyLyricAnchor();
        });
        HBox anchorRow = new HBox(6, anchorValLabel, lyricAnchorSlider);
        anchorRow.setAlignment(Pos.CENTER_RIGHT);
        Button resetAnchor = new Button("重置");
        resetAnchor.setStyle("-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_PRIMARY
                + ";-fx-background-radius:6;-fx-padding:4 12;-fx-cursor:hand;");
        attachButtonFx(resetAnchor);
        resetAnchor.setOnAction(e -> {
            // 回到原先的左侧位置（anchorX = 0.18）
            if (lyricAnchorSlider != null) lyricAnchorSlider.setValue(0.18);
            d3AnchorX = 0.18;
            lyricAnchor = "左";
            applyLyricAnchor();
            saveSettings();
            System.out.println("[settings] 3D 歌词位置 → 重置为左侧");
        });
        anchorRow.getChildren().add(resetAnchor);

        // 倾角自适应开启时：倾角角度由歌词位置自动按比例算出（固定基准 + 位置缩放），
        // 手动「透视倾角」滑块不再生效，故在此同步禁用该滑块
        final Node[] rotateZBoxRef = new Node[1];
        final Label[] rotateZLabelRef = new Label[1];
        Node autoFlipBox = createCustomCheckBox(d3AutoFlipRotate, v -> {
            d3AutoFlipRotate = v;
            applyD3();
            saveSettings();
            if (rotateZBoxRef[0] != null) rotateZBoxRef[0].setDisable(v);
            // 禁用时滑块上的数值已不代表实际角度，改显“自动”，避免误导
            if (rotateZLabelRef[0] != null) rotateZLabelRef[0].setText(v ? "自动" : fmtDouble(d3RotateZ));
            System.out.println("[settings] 3D 倾角自适应 → " + v);
        });

        // ===== 3D 歌词效果参数（默认即原版值，拖动实时生效）=====
        Node rotateZBox = rangedSlider(0, 60, d3RotateZ, 1, v -> fmtDouble(v),
                v -> { d3RotateZ = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 倾角 → " + fmtDouble(d3RotateZ)); });
        rotateZBoxRef[0] = rotateZBox;
        rotateZLabelRef[0] = getLabelFromRangedBox(rotateZBox);
        rotateZBox.setDisable(d3AutoFlipRotate);   // 倾角自适应默认开启时禁用手动透视倾角
        if (d3AutoFlipRotate && rotateZLabelRef[0] != null) rotateZLabelRef[0].setText("自动");
        Node minScaleBox = rangedSlider(0.3, 1.0, d3MinScale, 0.05, v -> fmtDouble(v),
                v -> { d3MinScale = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 远端最小缩放 → " + fmtDouble(d3MinScale)); });
        Node boostBox = rangedSlider(0.5, 4.0, d3MaxScaleBoost, 0.05, v -> fmtDouble(v),
                v -> { d3MaxScaleBoost = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 放大增益 → " + fmtDouble(d3MaxScaleBoost)); });
        Node affectBox = rangedSlider(1, 15, d3AffectRows, 1, v -> fmtDouble(v),
                v -> { d3AffectRows = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 影响行数 → " + fmtDouble(d3AffectRows)); });
        Node transBox = rangedSlider(0.3, 3.0, d3TransRange, 0.1, v -> fmtDouble(v),
                v -> { d3TransRange = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 过渡范围 → " + fmtDouble(d3TransRange)); });
        Node zoomBox = rangedSlider(1.0, 6.0, d3ZoomPower, 0.1, v -> fmtDouble(v),
                v -> { d3ZoomPower = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 缓动指数 → " + fmtDouble(d3ZoomPower)); });
        Node scaleBigBox = rangedSlider(1.0, 2.5, d3ScaleBig, 0.05, v -> fmtDouble(v),
                v -> { d3ScaleBig = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 高亮缩放 → " + fmtDouble(d3ScaleBig)); });
        Node scaleSmallBox = rangedSlider(0.4, 1.2, d3ScaleSmall, 0.05, v -> fmtDouble(v),
                v -> { d3ScaleSmall = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 普通缩放 → " + fmtDouble(d3ScaleSmall)); });
        Node strokeBox = rangedSlider(0, 6, d3StrokeWidth, 0.5, v -> fmtDouble(v),
                v -> { d3StrokeWidth = v; applyD3(); },
                () -> { saveSettings(); System.out.println("[settings] 3D 描边 → " + fmtDouble(d3StrokeWidth)); });
        Node strokeAutoBox = createCustomCheckBox(d3StrokeAuto, v -> {
            d3StrokeAuto = v;
            saveSettings();
            applyD3();
            System.out.println("[settings] 描边自动反色 → " + v);
        });
        // 3D 歌词上下高斯模糊开关：关掉后远近行不再模糊，只保留缩放/透明度层次
        Node blurBox = createCustomCheckBox(d3BlurEnabled, v -> {
            d3BlurEnabled = v;
            saveSettings();
            applyD3();
            System.out.println("[settings] 3D 上下高斯模糊 → " + v);
        });

        content.getChildren().addAll(
                createSettingsCard("显示",
                        createSettingRow("播放页显示", "设置播放页右侧区域显示歌词或播放列表", displayCombo)
                ),
                createSettingsCard("歌词来源",
                        createSettingRow("优先级", "按列表顺序依次尝试获取歌词，前一个来源失败则自动尝试下一个", sourceCombo)
                ),
                createSettingsCard("歌词样式",
                        createSettingRow("中文字体", "歌词中文字符使用的字体（英文及拉丁字符使用英文字体）", fontCnCombo),
                        createSettingRow("英文字体", "歌词英文及拉丁字符使用的字体", fontEnCombo),
                        createSettingRow("字体大小", "歌词文字大小，单位为像素", fontSizeBox),
                        createSettingRow("加粗", "使非高亮歌词行也采用粗体（当前播放行始终加粗）", boldBox)
                ),
                createSettingsCard("3D 歌词",
                        createSettingRow("桌面歌词悬浮窗", "开启后在桌面显示独立的 3D 歌词悬浮窗，随播放进度滚动，且可穿透鼠标点击", rendererBox),
                        createSettingRow("水平位置", "拖动滑块调整歌词的水平位置", anchorRow),
                        createSettingRow("倾角自适应", "开启后倾角与位置偏移由歌词的水平位置决定：靠左/靠右为完整 3D 弧面，移到中间平滑变为无旋转、无位置偏移；手动「透视倾角」调节将被禁用", autoFlipBox),
                        createSettingRow("透视倾角", "3D 纵深效果的旋转角度（单位：度）", rotateZBox),
                        createSettingRow("远端最小缩放", "远处歌词的最小缩放比例", minScaleBox),
                        createSettingRow("放大增益", "远处歌词随距离放大的强度", boostBox),
                        createSettingRow("影响行数", "参与 3D 过渡效果的临近歌词行数", affectBox),
                        createSettingRow("过渡范围", "高亮状态向外过渡所覆盖的歌词行数范围", transBox),
                        createSettingRow("缓动指数", "控制缩放与高亮过渡的缓动曲线指数", zoomBox),
                        createSettingRow("高亮缩放", "当前播放行相对于普通行的放大倍数", scaleBigBox),
                        createSettingRow("普通缩放", "非当前播放行的基础缩放比例", scaleSmallBox),
                        createSettingRow("描边", "歌词文字描边宽度（设为 0 关闭），用于在复杂背景下勾勒文字轮廓、提升可读性", strokeBox),
                        createSettingRow("描边自动反色", "开启后根据背景明暗自动选择描边颜色；关闭则描边固定为比文字略深的颜色", strokeAutoBox),
                        createSettingRow("上下高斯模糊", "开启后当前行上下的歌词按距离做高斯模糊；关闭则只保留缩放与透明度层次", blurBox)
                )
        );
        content.getChildren().add(0, buildResetBar("歌词设置", this::resetLyricsSettings));
        return wrapSettingsScroll(content);
    }

    /** 换来源后按新顺序重新取当前这首歌的歌词 */
    private void refreshLyricWithCurrentSettings() {
        String bvid = currentBvid;
        if (bvid != null && !bvid.isEmpty() && lyricView != null) lyricView.loadLyric(bvid, lyricSourceOrder);
    }

    /**
     * 把当前歌曲的歌词 + 进度同步给 LyricRendererFX 悬浮窗。
     * useLyricRendererFX 关闭时关闭悬浮窗；开启且尚未打开时先打开（鼠标穿透、适度尺寸）。
     * 由歌词加载完成回调与设置开关触发；进度由 onProgress 单独驱动。
     */
    private void syncLyricRenderer() {
        // 统一在 JavaFX 应用线程执行，保证“关闭→置空→再打开”的先后顺序，
        // 避免异步关闭导致 isShowing() 误判、重开时跳过 showPreview 而不显示。
        javafx.application.Platform.runLater(() -> {
            if (!useLyricRendererFX) {
                LyricRendererFX.closePreviewNow();   // 同步关闭并立即置空
                return;
            }
            if (!LyricRendererFX.isShowing()) {
                LyricRendererFX.LyricConfig cfg = LyricRendererFX.getConfig();
                cfg.externalMode = true;            // 由本程序喂歌词，不加载演示/文件/网络
                cfg.clickThrough = true;            // 整窗鼠标穿透，完全不挡下方操作
                // 全屏透明窗口：文字按 anchorX 精确定位（整窗可点击穿透）
                cfg.anchorX = d3AnchorX;
                // 铺满整个主屏幕，但比屏幕小 2px：Windows DWM 对“恰好整屏的透明窗口”会渲染成实色背景
                javafx.geometry.Rectangle2D sb = javafx.stage.Screen.getPrimary().getBounds();
                cfg.width = (int) sb.getWidth() - 2;
                cfg.height = (int) sb.getHeight() - 2;
                applyD3(cfg);   // 把当前 3D 参数写入配置
                LyricRendererFX.showPreview();
            }
            if (lyricView != null) {
                java.util.List<LyricView.LyricLine> src = lyricView.getCurrentLines();
                if (src != null && !src.isEmpty()) {
                    java.util.List<LyricRendererFX.LyricLine> dst = new java.util.ArrayList<>();
                    for (LyricView.LyricLine l : src) dst.add(new LyricRendererFX.LyricLine(l.text, l.start, l.end));
                    LyricRendererFX.setLyrics(dst);
                } else {
                    LyricRendererFX.clearLyrics();
                }
                LyricRendererFX.setProgress(lastProgressMs / 1000.0);
            }
        });
    }

    /** 应用 3D 歌词水平位置（连续锚点 0~1）：全屏窗口下直接改文字水平锚点（整窗仍完全穿透），实时生效 */
    private void applyLyricAnchor() {
        if (LyricRendererFX.isShowing()) {
            LyricRendererFX.LyricConfig cfg = LyricRendererFX.getConfig();
            cfg.anchorX = d3AnchorX;
        }
    }

    /** 把连续锚点映射回最接近的 左/中/右 标签（仅用于兼容旧存档与显示） */
    private static String nearestAnchorLabel(double anchorX) {
        if (Math.abs(anchorX - 0.18) < 0.08) return "左";
        if (Math.abs(anchorX - 0.78) < 0.08) return "右";
        return "中";
    }

    /** 把当前 3D 参数写入配置（窗口未开时也直接改 getConfig() 的对象，开窗即生效） */
    private void applyD3() { applyD3(LyricRendererFX.getConfig()); }
    /** 把字体选项名解析为 JavaFX Font。选项 "Computer Modern Italic" 映射到嵌入字体（斜体），其余直接用系统族名 */
    public static javafx.scene.text.Font resolveLyricFont(String option, FontWeight fw, double size) {
        if (EMBEDDED_FONT_OPTION.equals(option) && EMBEDDED_CM_FAMILY != null)
            return javafx.scene.text.Font.font(EMBEDDED_CM_FAMILY, javafx.scene.text.FontPosture.ITALIC, size);
        return javafx.scene.text.Font.font(option, fw, size);
    }

    /**
     * 筛出字体下拉框的候选项：只保留系统里真实存在的常用字体（数量控制在 5~10 个）。
     * 嵌入字体选项固定保留；当前已选字体也必须保留（可能是用户拖入的自定义字体）。
     */
    private static List<String> pickFontFamilies(List<String> candidates, String embeddedOption, String current) {
        java.util.Set<String> installed = new java.util.HashSet<>(Font.getFamilies());
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (embeddedOption != null) out.add(embeddedOption);
        for (String c : candidates) {
            if (installed.contains(c)) out.add(c);
        }
        if (current != null && !current.trim().isEmpty() && !out.contains(current.trim())) {
            out.add(current.trim());   // 当前正在用的字体不能从列表里消失
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 从文件路径重新加载自定义字体并注册到 JVM（供重启后恢复拖入的字体）。
     * 返回实际注册到的 family 名；加载失败（文件缺失 / 格式不支持）返回 null。
     * 同时打印诊断信息，便于确认“重启后字体是否真的注册成功、family 名是否和存储的一致”。
     */
    private String reloadCustomFont(String tag, String path, String expectFamily) {
        if (path == null || path.isEmpty()) return null;
        java.io.File f = new java.io.File(path);
        if (!f.exists()) {
            System.out.println("[fonts] " + tag + " 自定义字体副本不存在: " + path);
            return null;
        }
        try {
            javafx.scene.text.Font ft = javafx.scene.text.Font.loadFont(f.toURI().toString(), 12);
            if (ft == null) {
                System.out.println("[fonts] " + tag + " 字体加载返回 null（可能格式不支持）: " + path);
                return null;
            }
            String realFam = ft.getFamily();
            boolean registered = java.util.Arrays.asList(javafx.scene.text.Font.getFamilies()).contains(realFam);
            System.out.println("[fonts] " + tag + " 自定义字体恢复: 期望=" + expectFamily
                    + " 实际=" + realFam + " 已注册=" + registered + " @ " + path);
            return realFam;
        } catch (Exception ex) {
            System.out.println("[fonts] " + tag + " 加载自定义字体失败: " + path + " " + ex);
            return null;
        }
    }

    /**
     * 把拖入的字体文件复制到应用数据目录（FONTS_DIR），返回副本路径。
     * 这样持久化的是字体文件本身，原文件被移动/删除后重启仍能用。
     * 复制失败则返回 null（调用方回退为用原文件）。
     */
    private static java.io.File copyFontToData(java.io.File src) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(FONTS_DIR);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path dst = dir.resolve(src.getName());
            java.nio.file.Files.copy(src.toPath(), dst,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[fonts] 已保存字体副本: " + dst);
            return dst.toFile();
        } catch (Exception e) {
            System.out.println("[fonts] 保存字体副本失败: " + e.getMessage());
            return null;
        }
    }

    /** 判断文件是否为字体文件（支持 ttf/otf/ttc） */
    private static boolean isFontFile(java.io.File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    private void applyD3(LyricRendererFX.LyricConfig cfg) {
        cfg.fontFamilyCN = lyricFontCN;          // 3D 歌词同样分中英文纹理
        cfg.fontFamily = lyricFontEN;
        cfg.rotateZ = d3RotateZ;
        cfg.autoFlipRotate = d3AutoFlipRotate;    // 位置靠右时自动反转倾角
        cfg.minScale = d3MinScale;
        cfg.strokeWidth = d3StrokeWidth;          // 文字描边宽度，复杂背景下更突出
        cfg.strokeAuto = d3StrokeAuto;           // 自动按背景明暗取反色描边；关闭时描边固定为比文字深一点
        cfg.blurEnabled = d3BlurEnabled;         // 上下高斯模糊开关
        cfg.maxScaleBoost = d3MaxScaleBoost;
        cfg.affectRows = d3AffectRows;
        cfg.transRange = d3TransRange;
        cfg.zoomPower = d3ZoomPower;
        cfg.scaleBig = d3ScaleBig;
        cfg.scaleSmall = d3ScaleSmall;
    }

    private String labelOfLyricSourceOrder(List<String> order) {
        for (Map.Entry<String, List<String>> e : LYRIC_SOURCE_ORDERS.entrySet()) {
            if (e.getValue().equals(order)) return e.getKey();
        }
        return LYRIC_SOURCE_LABELS[0];
    }

    private String labelOfLyricColor(Color c) {
        for (Map.Entry<String, Color> e : LYRIC_COLORS.entrySet()) {
            if (e.getValue().equals(c)) return e.getKey();
        }
        return "蓝色";
    }

    // ===== 账号管理 =====
    /** 打开 B 站登录窗口（头像与设置页共用同一入口） */
    private void openBiliLogin() {
        if (primaryStage == null) return;
        BiliLogin.openLogin(primaryStage, this::applyBiliCookie);
    }

    /** 登录成功回调：写盘 + 刷新头像与登录状态 */
    private void applyBiliCookie(String cookie) {
        biliCookie = cookie;
        BiliAudioFetcher.setCookie(cookie);
        BiliUidLyricService.setCookie(cookie);
        if (audioPlayer != null) audioPlayer.setCookie(cookie);
        saveCookie(cookie);
        loadAvatar(cookie);
        refreshAccountStatus();
        System.out.println("[login] 登录成功，cookie 已更新");
    }

    /** 退出登录：删除本地 Cookie 并把头像/状态复位 */
    private void logoutBili() {
        biliCookie = null;
        // 同步清掉歌词服务里的登录态，避免退出后它仍继续使用旧 cookie
        BiliUidLyricService.setCookie("");
        try { Files.deleteIfExists(Paths.get(COOKIE_FILE)); }
        catch (Exception e) { System.out.println("[logout] 删除 cookie 文件失败: " + e.getMessage()); }
        if (avatarImg != null) { avatarImg.setImage(null); avatarImg.setVisible(false); }
        if (avatarPlaceholder != null) {
            avatarPlaceholder.setText("U");
            avatarPlaceholder.setStyle("-fx-background-color:#42A5F5;-fx-background-radius:50%;"
                    + "-fx-text-fill:white;-fx-font-size:16px;-fx-font-weight:bold;");
            avatarPlaceholder.setVisible(true);
        }
        refreshAccountStatus();
        System.out.println("[logout] 已退出 B 站账号");
    }

    /** 刷新设置页「登录状态」标签 */
    private void refreshAccountStatus() {
        if (accountStatusLabel == null) return;
        boolean loggedIn = biliCookie != null && !biliCookie.isBlank();
        accountStatusLabel.setText(loggedIn ? "已登录" : "未登录");
        accountStatusLabel.setStyle("-fx-text-fill:" + (loggedIn ? "#2E7D32" : C_SUB)
                + ";-fx-font-size:13px;-fx-font-weight:" + (loggedIn ? "bold" : "normal") + ";");
    }

    /** 手动粘贴 Cookie：多行输入 + 后台校验，通过后写入并生效 */
    private void openManualCookieDialog() {
        if (primaryStage == null) return;
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
        area.setPromptText("SESSDATA=...; bili_jct=...; DedeUserID=...");
        area.setWrapText(true);
        area.setPrefRowCount(6);
        area.setPrefWidth(420);

        Label tip = new Label("在浏览器登录 B 站后，F12 → 网络 → 任意请求 → 请求头里的 Cookie 整段复制到下面");
        tip.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        tip.setWrapText(true);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_TEXT
                + ";-fx-background-radius:6;-fx-padding:6 16;");
        attachButtonFx(cancelBtn);
        Button okBtn = new Button("验证并保存");
        okBtn.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-text-fill:white"
                + ";-fx-background-radius:6;-fx-padding:6 16;");
        attachButtonFx(okBtn);

        HBox btns = new HBox(10, cancelBtn, okBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(10, tip, area, btns);
        box.setStyle("-fx-background-color:white;");
        box.setPadding(new Insets(16, 18, 16, 18));

        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("设置 B 站 Cookie");
        dlg.setScene(new Scene(box));
        dlg.setOnShown(e -> hotkeyManager.setTextInputFocused(true));
        dlg.setOnHidden(e -> hotkeyManager.setTextInputFocused(false));
        cancelBtn.setOnAction(e -> dlg.close());
        okBtn.setOnAction(e -> {
            String c = area.getText() == null ? "" : area.getText().trim();
            if (c.isEmpty()) {
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                        "请先粘贴 Cookie", javafx.scene.control.ButtonType.OK).showAndWait();
                return;
            }
            okBtn.setDisable(true);
            okBtn.setText("验证中...");
            // 校验要打网络请求，放后台线程，别卡住界面
            new Thread(() -> {
                String face = fetchUserAvatar(c);
                Platform.runLater(() -> {
                    okBtn.setDisable(false);
                    okBtn.setText("验证并保存");
                    if (face == null) {
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                                "Cookie 无效或已过期，请重新复制", javafx.scene.control.ButtonType.OK).showAndWait();
                        return;
                    }
                    applyBiliCookie(c);
                    dlg.close();
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                            "Cookie 已保存，登录成功", javafx.scene.control.ButtonType.OK).showAndWait();
                });
            }).start();
        });

        dlg.sizeToScene();
        dlg.showAndWait();
    }

    private ScrollPane createAccountSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));

        accountStatusLabel = new Label();
        refreshAccountStatus();

        Button loginBtn = new Button("登录 B 站");
        loginBtn.setStyle("-fx-background-color:" + C_PRIMARY + ";-fx-text-fill:white"
                + ";-fx-background-radius:6;-fx-padding:6 16;");
        attachButtonFx(loginBtn);
        loginBtn.setOnAction(e -> openBiliLogin());

        Button cookieBtn = new Button("手动设置");
        cookieBtn.setStyle("-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_PRIMARY
                + ";-fx-background-radius:6;-fx-padding:6 14;");
        attachButtonFx(cookieBtn);
        cookieBtn.setOnAction(e -> openManualCookieDialog());

        Button logoutBtn = new Button("退出登录");
        logoutBtn.setStyle("-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_SUB
                + ";-fx-background-radius:6;-fx-padding:6 14;");
        attachButtonFx(logoutBtn);
        logoutBtn.setOnAction(e -> {
            if (biliCookie == null || biliCookie.isBlank()) {
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                        "当前未登录", javafx.scene.control.ButtonType.OK).showAndWait();
                return;
            }
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "确定要退出 B 站账号吗？", javafx.scene.control.ButtonType.OK,
                    javafx.scene.control.ButtonType.CANCEL).showAndWait()
                    .filter(r -> r == javafx.scene.control.ButtonType.OK)
                    .ifPresent(r -> logoutBili());
        });

        content.getChildren().addAll(
                createSettingsCard("B 站账号",
                        createSettingRow("登录状态", "登录后方可使用收藏夹、追番等功能", accountStatusLabel),
                        createSettingRow("扫码登录", "弹出二维码窗口，扫码成功后自动保存登录凭据（Cookie）", loginBtn),
                        createSettingRow("手动 Cookie", "无法扫码或登录过期时，可手动粘贴 Cookie 登录", cookieBtn),
                        createSettingRow("退出登录", "清除本机保存的登录凭据（Cookie）", logoutBtn)
                )
        );
        content.getChildren().add(0, buildResetBar("账号管理", null));
        return wrapSettingsScroll(content);
    }

    // ===== 外观设置 =====
    /**
     * 应用顶栏磨砂玻璃。
     * 做法与歌词悬浮窗一致：不靠截图，而是让顶栏后面**真的没有不透明物**——
     * root 背景已改为透明，舞台本身是 TRANSPARENT，所以桌面能实时透进来。
     * 这里只给顶栏铺一层半透明蓝（原有配色，只调不透明度）+ 磨砂颗粒。
     */
    private void applyTitleBarFrost() {
        if (titleBar == null) return;
        double top = Math.max(0.0, Math.min(1.0, titleBarOpacity / 100.0));
        double bottom = top * 0.82;             // 保持原来的上深下浅比例
        titleBar.setBackground(new Background(
                java.util.List.of(new BackgroundFill(
                        new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                                new Stop(0.0, Color.web(C_PRIMARY_LIGHT, top)),
                                new Stop(1.0, Color.web(C_PRIMARY, bottom))),
                        CornerRadii.EMPTY, Insets.EMPTY)),
                java.util.List.of(new BackgroundImage(frostNoise(frostGrain),
                        BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
                        BackgroundPosition.CENTER, BackgroundSize.DEFAULT))));
        applyBgBlueFrost();   // 同一「磨砂强度」同时作用于浅蓝背景
    }

    /** 应用窗口不透明度（拖动时实时预览） */
    private void applyWindowOpacity(int v) {
        if (primaryStage != null) primaryStage.setOpacity(v / 100.0);
    }

    /** 应用窗口圆角：背景色保持透明（桌面要能透进来），只改圆角与裁剪矩形 */
    private void applyWindowCornerRadius(int v) {
        if (root != null) refreshRootGlass();
        if (rootClip != null) { rootClip.setArcWidth(v * 2); rootClip.setArcHeight(v * 2); }
    }

    /** 应用播放页封面圆角：图片裁剪 + 外框描边一起改 */
    private void applyCoverCornerRadius(int v) {
        if (leftCoverClip != null) { leftCoverClip.setArcWidth(v * 2); leftCoverClip.setArcHeight(v * 2); }
        if (leftCoverFrame != null) {
            leftCoverFrame.setStyle(
                    "-fx-background-color:#EAF3FE;"
                            + "-fx-border-color:#BBDEFB;"
                            + "-fx-border-width:1.5;"
                            + "-fx-border-radius:" + v + "px;"
                            + "-fx-background-radius:" + v + "px;");
        }
    }

    private ScrollPane createAppearanceSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));

        // 三个滑块都接上：onChange 拖动实时生效，onRelease 松手写回字段并落盘
        // 顶栏磨砂：拖动时实时更新顶栏磨砂颗粒 + 浅蓝背景模糊（强度 0~30）
        final Slider[] barOpacityRef = new Slider[1];
        Node opacityBar = styleSettingsSlider(30, 100, titleBarOpacity,
                v -> { titleBarOpacity = v.intValue(); applyTitleBarFrost(); },
                () -> { titleBarOpacity = (int) barOpacityRef[0].getValue(); applyTitleBarFrost();
                        saveSettings();
                        System.out.println("[settings] 顶栏不透明度 → " + titleBarOpacity); });
        barOpacityRef[0] = getSliderFromStyledBox(opacityBar);

        final Slider[] grainRef = new Slider[1];
        Node grainBar = styleSettingsSlider(0, 30, frostGrain,
                v -> { frostGrain = v.intValue(); applyTitleBarFrost(); },
                () -> { frostGrain = (int) grainRef[0].getValue(); applyTitleBarFrost();
                        saveSettings();
                        System.out.println("[settings] 磨砂颗粒强度 → " + frostGrain); });
        grainRef[0] = getSliderFromStyledBox(grainBar);

        final Slider[] opacityRef = new Slider[1];
        Node opacityBox = styleSettingsSlider(50, 100, windowOpacity,
                v -> applyWindowOpacity(v.intValue()),
                () -> {
                    windowOpacity = (int) opacityRef[0].getValue();
                    applyWindowOpacity(windowOpacity);
                    saveSettings();
                    System.out.println("[settings] 窗口不透明度 → " + windowOpacity);
                });
        opacityRef[0] = getSliderFromStyledBox(opacityBox);

        final Slider[] radiusRef = new Slider[1];
        Node radiusBox = styleSettingsSlider(0, 20, windowCornerRadius,
                v -> applyWindowCornerRadius(v.intValue()),
                () -> {
                    windowCornerRadius = (int) radiusRef[0].getValue();
                    applyWindowCornerRadius(windowCornerRadius);
                    saveSettings();
                    System.out.println("[settings] 窗口圆角 → " + windowCornerRadius);
                });
        radiusRef[0] = getSliderFromStyledBox(radiusBox);

        final Slider[] coverRef = new Slider[1];
        Node coverBox = styleSettingsSlider(0, 30, coverCornerRadius,
                v -> applyCoverCornerRadius(v.intValue()),
                () -> {
                    coverCornerRadius = (int) coverRef[0].getValue();
                    applyCoverCornerRadius(coverCornerRadius);
                    saveSettings();
                    System.out.println("[settings] 封面圆角 → " + coverCornerRadius);
                });
        coverRef[0] = getSliderFromStyledBox(coverBox);

        // 浅蓝背景：与顶栏互不干扰的独立不透明度。拖动实时预览，松手写回字段并落盘
        final Slider[] blueRef = new Slider[1];
        Node blueBox = styleSettingsSlider(0, 100, bgBlueOpacity,
                v -> { bgBlueOpacity = v.intValue(); applyBgBlueOpacity(); updateCardBlur(); },
                () -> {
                    bgBlueOpacity = (int) blueRef[0].getValue();
                    applyBgBlueOpacity(); updateCardBlur();
                    saveSettings();
                    System.out.println("[settings] 浅蓝背景不透明度 → " + bgBlueOpacity);
                });
        blueRef[0] = getSliderFromStyledBox(blueBox);

        // 白色底：与浅蓝层叠加的独立不透明度。越低越透，桌面从白色底透出（与浅蓝层叠加）
        final Slider[] whiteRef = new Slider[1];
        Node whiteBox = styleSettingsSlider(0, 100, whiteBgOpacity,
                v -> { whiteBgOpacity = v.intValue(); applyWhiteBgOpacity(); updateCardBlur(); },
                () -> {
                    whiteBgOpacity = (int) whiteRef[0].getValue();
                    applyWhiteBgOpacity(); updateCardBlur();
                    saveSettings();
                    System.out.println("[settings] 白色背景不透明度 → " + whiteBgOpacity);
                });
        whiteRef[0] = getSliderFromStyledBox(whiteBox);

        // 浅蓝背景高斯模糊：独立于顶栏磨砂颗粒的调节项。拖动实时预览，松手写回字段并落盘
        final Slider[] blueBlurRef = new Slider[1];
        Node blueBlurBox = styleSettingsSlider(0, 30, bgBlueBlur,
                v -> { bgBlueBlur = v.intValue(); applyBgBlueFrost(); },
                () -> {
                    bgBlueBlur = (int) blueBlurRef[0].getValue();
                    applyBgBlueFrost();
                    saveSettings();
                    System.out.println("[settings] 浅蓝背景模糊 → " + bgBlueBlur);
                });
        blueBlurRef[0] = getSliderFromStyledBox(blueBlurBox);

        content.getChildren().addAll(
                createSettingsCard("顶栏（磨砂玻璃）",
                        createSettingRow("顶栏不透明度", "设置顶栏不透明度，越低越通透、磨砂感越明显", opacityBar),
                        createSettingRow("磨砂强度", "顶栏磨砂颗粒强度，0 表示无效果，数值越大颗粒越明显（仅作用于顶栏）", grainBar)
                ),
                createSettingsCard("背景",
                        createSettingRow("浅蓝背景不透明度", "调整浅蓝背景的不透明度，越低则蓝色越淡、越透出白色底，与顶栏相互独立", blueBox),
                        createSettingRow("浅蓝背景模糊", "浅蓝背景的高斯模糊强度，0 表示清晰无模糊，数值越大过渡越柔和（独立于顶栏磨砂）", blueBlurBox),
                        createSettingRow("播放页毛玻璃卡片", "开启后播放页卡片背后的桌面区域应用毛玻璃模糊效果（仅 Windows 有效，关闭则恢复浅蓝背景）",
                                createCustomCheckBox(cardBlurEnabled, v -> {
                                    cardBlurEnabled = v;
                                    saveSettings();
                                    updateCardBlur();
                                })),
                        createSettingRow("白色背景不透明度", "白色背景层的不透明度，越低越通透，桌面从白色底透出（与浅蓝层叠加）", whiteBox)
                ),
                createSettingsCard("窗口",
                        createSettingRow("窗口不透明度", "设置整个窗口的不透明度", opacityBox),
                        createSettingRow("圆角半径", "窗口圆角的大小", radiusBox)
                ),
                createSettingsCard("封面",
                        createSettingRow("封面圆角", "视频封面的圆角半径", coverBox)
                )
        );
        content.getChildren().add(0, buildResetBar("外观设置", this::resetAppearanceSettings));
        return wrapSettingsScroll(content);
    }

    // ===== 关于 =====
    private ScrollPane createAboutSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));
        content.getChildren().addAll(
                createSettingsCard("关于",
                        createSettingRow("程序名称", "plain bmusic player", new Label("")),
                        createSettingRow("版本", "1.0-SNAPSHOT", new Label("")),
                        createSettingRow("技术栈", "JavaFX 21 + Maven", new Label("")),
                        createSettingRow("B站API", "WBI 签名 + 收藏夹", new Label("")),
                        createSettingRow("歌词来源", "QQ音乐 / 网易云 / 酷狗", new Label(""))
                )
        );
        content.getChildren().add(0, buildResetBar("关于", null));
        return wrapSettingsScroll(content);
    }

    // ===== 设置页「整体重置」工具条与逐选项卡重置 =====
    /** 设置面板顶部「整体重置」工具条：点击后把当前选项卡的设置恢复默认并重建面板 */
    private HBox buildResetBar(String tabLabel, Runnable resetAction) {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(0, 0, 4, 0));
        bar.setSpacing(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("恢复「" + tabLabel + "」全部设置为默认值");
        hint.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        Button reset = new Button("整体重置");
        reset.setStyle("-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_PRIMARY
                + ";-fx-background-radius:6;-fx-padding:5 16;-fx-cursor:hand;-fx-font-size:12px;");
        attachButtonFx(reset);
        if (resetAction == null) {
            reset.setDisable(true);
            reset.setTooltip(new Tooltip("该页面没有可重置的设置"));
        } else {
            reset.setOnAction(e -> {
                resetAction.run();
                saveSettings();
                switchSettingsTab(currentSettingsTab);   // 重建面板以反映默认值
                System.out.println("[settings] 「" + tabLabel + "」→ 整体重置");
            });
        }
        bar.getChildren().addAll(spacer, hint, reset);
        return bar;
    }

    /** 播放设置：恢复默认（自动播放关 / 列表循环 / 音量 50 / 高音质） */
    private void resetPlaybackSettings() {
        autoPlayOnStartup = false;
        loopMode = "列表循环";
        defaultVolume = 50;
        highAudioQuality = true;
        if (audioPlayer != null) {
            audioPlayer.setVolume(defaultVolume / 100.0);
            audioPlayer.setHighQuality(highAudioQuality);
        }
    }

    /** 歌词设置：恢复全部歌词与 3D 参数为默认并即时生效 */
    private void resetLyricsSettings() {
        playPageDisplay = "播放列表";
        lyricSourceOrder = BiliUidLyricService.DEFAULT_SOURCE_ORDER;
        lyricFontSize = 16;
        lyricFontCN = "Microsoft YaHei";
        lyricFontEN = "Computer Modern Italic";
        lyricFontCNPath = "";
        lyricFontENPath = "";
        lyricBold = true;
        lyricHighlight = LYRIC_COLORS.get("蓝色");
        useLyricRendererFX = false;
        lyricAnchor = "左";
        d3AnchorX = LyricRendererFX.ANCHOR_LEFT;
        d3AutoFlipRotate = true;
        d3RotateZ = 30; d3MinScale = 0.7; d3MaxScaleBoost = 2.0; d3AffectRows = 8.0;
        d3TransRange = 1.1; d3ZoomPower = 3.0; d3ScaleBig = 1.55; d3ScaleSmall = 0.75;
        d3StrokeWidth = 0.7; d3StrokeAuto = false; d3BlurEnabled = true;
        if (lyricView != null) {
            lyricView.setFontSize(lyricFontSize);
            lyricView.setBold(lyricBold);
            lyricView.setFontFamilies(lyricFontCN, lyricFontEN);
        }
        applyD3();
        applyLyricAnchor();
        syncLyricRenderer();                 // 关闭 3D 悬浮窗（默认关）
        refreshLyricWithCurrentSettings();    // 用默认来源顺序重新取词
        applyPlayPageDisplay();
    }

    /** 外观设置：恢复默认并实时应用（窗口/封面圆角、不透明度、磨砂、背景等） */
    private void resetAppearanceSettings() {
        windowOpacity = 100;
        windowCornerRadius = (int) RADIUS;
        coverCornerRadius = (int) COVER_RADIUS;
        titleBarOpacity = 85;
        frostGrain = 14;
        bgBlueOpacity = 100;
        bgBlueBlur = 14;
        whiteBgOpacity = 100;
        cardBlurEnabled = true;
        applyWindowOpacity(windowOpacity);
        applyWindowCornerRadius(windowCornerRadius);
        applyCoverCornerRadius(coverCornerRadius);
        applyTitleBarFrost();
        applyBgBlueOpacity();
        applyWhiteBgOpacity();
        applyBgBlueFrost();
        updateCardBlur();
        refreshRootGlass();
    }

    /** 快捷键：清空全部全局快捷键绑定 */
    private void resetShortcutSettings() {
        hotkeyManager.setBinding(HotkeyManager.PLAY_PAUSE, "");
        hotkeyManager.setBinding(HotkeyManager.NEXT, "");
        hotkeyManager.setBinding(HotkeyManager.PREV, "");
        hotkeyManager.setBinding(HotkeyManager.DESKTOP_LYRIC, "");
    }

    // ===== 快捷键设置 =====
    /** 初始化全局快捷键：注册动作处理、Windows 下安装全局钩子，退出时卸载 */
    private void initHotkeys() {
        hotkeyManager.setActionHandler(action -> {
            switch (action) {
                case HotkeyManager.PLAY_PAUSE -> togglePlay();
                case HotkeyManager.NEXT -> nextSong();
                case HotkeyManager.PREV -> prevSong();
                case HotkeyManager.DESKTOP_LYRIC -> toggleDesktopLyric();
            }
        });
        if (isWindows()) {
            hotkeyManager.install();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> hotkeyManager.uninstall()));
        }
        // 文本输入框聚焦时不触发/拦截全局快捷键，避免影响正常输入
        Platform.runLater(() -> {
            if (primaryStage != null && primaryStage.getScene() != null) {
                primaryStage.getScene().focusOwnerProperty().addListener((o, oldV, newV) -> {
                    boolean typing = newV instanceof javafx.scene.control.TextInputControl;
                    hotkeyManager.setTextInputFocused(typing);
                });
            }
        });
    }

    /** 切换 3D 桌面歌词悬浮窗开关（供快捷键调用，与设置页开关逻辑一致） */
    private void toggleDesktopLyric() {
        useLyricRendererFX = !useLyricRendererFX;
        saveSettings();
        syncLyricRenderer();
    }

    private ScrollPane createShortcutSettingsPanel() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(20, 24, 20, 24));

        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put(HotkeyManager.PLAY_PAUSE, "播放 / 暂停");
        actions.put(HotkeyManager.NEXT, "下一首");
        actions.put(HotkeyManager.PREV, "上一首");
        actions.put(HotkeyManager.DESKTOP_LYRIC, "桌面歌词 开/关");

        List<Node> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : actions.entrySet()) {
            String action = e.getKey();
            Button bindBtn = new Button();
            final String baseStyle = "-fx-background-color:" + C_BTN_BG + ";-fx-text-fill:" + C_PRIMARY
                    + ";-fx-background-radius:6;-fx-padding:6 14;-fx-min-width:150px;";
            final String recStyle = "-fx-background-color:" + C_PRIMARY + ";-fx-text-fill:white"
                    + ";-fx-background-radius:6;-fx-padding:6 14;-fx-min-width:150px;";
            bindBtn.setStyle(baseStyle);
            Runnable refresh = () -> {
                String b = hotkeyManager.getBinding(action);
                bindBtn.setText(b.isEmpty() ? "未设置" : b);
                boolean rec = hotkeyManager.isRecording() && action.equals(hotkeyManager.getRecordingAction());
                bindBtn.setStyle(rec ? recStyle : baseStyle);
            };
            refresh.run();
            bindBtn.setOnAction(ev -> {
                if (hotkeyManager.isRecording()) {
                    hotkeyManager.cancelRecording();
                    refresh.run();
                    return;
                }
                bindBtn.setText("请按下按键…(Esc 清空)");
                bindBtn.setStyle(recStyle);
                bindBtn.setDisable(true);   // 录制期间禁用，避免空格/回车再次触发该按钮取消录制
                hotkeyManager.startRecording(action, combo -> {
                    // 录到组合键则绑定；按 Esc(combo==null) 则清空该动作绑定
                    hotkeyManager.setBinding(action, combo == null ? "" : combo);
                    saveSettings();
                    Platform.runLater(() -> {
                        bindBtn.setDisable(false);
                        refresh.run();
                    });
                });
            });
            rows.add(createSettingRow(e.getValue(), "点击按钮后按下要绑定的按键组合（全局生效）", bindBtn));
        }

        content.getChildren().addAll(
                createSettingsCard("全局快捷键", rows.toArray(new Node[0])),
                createSettingsCard("说明",
                        createSettingRow("生效范围", "即使程序窗口未聚焦也能响应，仅在 Windows 下可用", new Label("")),
                        createSettingRow("取消绑定", "录制状态下按 Esc 可取消本次录制，再次点击按钮也可退出录制", new Label(""))
                )
        );
        content.getChildren().add(0, buildResetBar("快捷键", this::resetShortcutSettings));
        return wrapSettingsScroll(content);
    }

    // ===== 辅助：PCL 风格设置卡片 =====
    private VBox createSettingsCard(String title, Node... rows) {
        Label header = new Label(title);
        header.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:14px;-fx-font-weight:bold;");
        header.setPadding(new Insets(0, 0, 10, 0));

        VBox content = new VBox(0);
        content.getChildren().addAll(rows);

        VBox card = new VBox(0, header, content);
        card.setStyle(
                "-fx-background-color:-card-bg;"
                + "-fx-background-radius:10;"
                + "-fx-border-color:#D6E6F7;"
                + "-fx-border-width:1;"
                + "-fx-border-radius:10;"
                + "-fx-padding:16 20;"
        );

        DropShadow ds = new DropShadow();
        ds.setRadius(8);
        ds.setSpread(0);
        ds.setColor(Color.web(C_PRIMARY, 0.06));
        ds.setOffsetY(2);
        card.setEffect(ds);

        card.setOnMouseEntered(e -> {
            Timeline t = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(ds.colorProperty(), ds.getColor()),
                            new KeyValue(ds.radiusProperty(), ds.getRadius())),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(ds.colorProperty(), Color.web(C_PRIMARY, 0.25), Interpolator.EASE_OUT),
                            new KeyValue(ds.radiusProperty(), 14.0, Interpolator.EASE_OUT)));
            t.play();
        });
        card.setOnMouseExited(e -> {
            Timeline t = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(ds.colorProperty(), ds.getColor()),
                            new KeyValue(ds.radiusProperty(), ds.getRadius())),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(ds.colorProperty(), Color.rgb(0, 0, 0, 0.04), Interpolator.EASE_OUT),
                            new KeyValue(ds.radiusProperty(), 8.0, Interpolator.EASE_OUT)));
            t.play();
        });

        return card;
    }

    // ===== 辅助：自定义圆环 CheckBox（选中=蓝环+蓝心，未选中=灰环）=====
    private Node createCustomCheckBox(boolean selected) {
        return createCustomCheckBox(selected, null);
    }

    // onChange：值变化时回调（子线程安全，JavaFX 事件线程调用）
    private Node createCustomCheckBox(boolean selected, Consumer<Boolean> onChange) {
        Circle outerRing = new Circle(7);
        outerRing.setFill(Color.WHITE);
        outerRing.setStroke(Color.web("#D1D5DB"));
        outerRing.setStrokeWidth(1.5);

        Circle innerDot = new Circle(4);
        innerDot.setFill(Color.web(C_PRIMARY));
        innerDot.setOpacity(selected ? 1 : 0);
        innerDot.setScaleX(selected ? 1 : 0.3);
        innerDot.setScaleY(selected ? 1 : 0.3);

        StackPane check = new StackPane(outerRing, innerDot);
        check.setPrefSize(18, 18);
        check.setCursor(Cursor.HAND);

        boolean[] state = {selected};
        check.setOnMouseClicked(e -> {
            state[0] = !state[0];
            Color targetRing = state[0] ? Color.web(C_PRIMARY) : Color.web("#D1D5DB");
            Timeline t = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(innerDot.opacityProperty(), innerDot.getOpacity()),
                            new KeyValue(innerDot.scaleXProperty(), innerDot.getScaleX()),
                            new KeyValue(innerDot.scaleYProperty(), innerDot.getScaleY()),
                            new KeyValue(outerRing.strokeProperty(), outerRing.getStroke())),
                    new KeyFrame(Duration.millis(150),
                            new KeyValue(innerDot.opacityProperty(), state[0] ? 1.0 : 0.0, Interpolator.EASE_OUT),
                            new KeyValue(innerDot.scaleXProperty(), state[0] ? 1.0 : 0.3, Interpolator.EASE_OUT),
                            new KeyValue(innerDot.scaleYProperty(), state[0] ? 1.0 : 0.3, Interpolator.EASE_OUT),
                            new KeyValue(outerRing.strokeProperty(), targetRing, Interpolator.EASE_OUT)));
            t.play();
            if (onChange != null) onChange.accept(state[0]);
        });

        return check;
    }

    // ===== 辅助：设置行（左侧标签+描述，右侧控件）=====
    private HBox createSettingRow(String label, String desc, Node control) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(11, 0, 11, 0));
        row.setStyle("-fx-border-color:transparent transparent #E3F0FE transparent;-fx-border-width:0 0 1 0;");

        VBox labelBox = new VBox(2);
        HBox.setHgrow(labelBox, Priority.ALWAYS);
        Label titleLabel = new Label(label);
        titleLabel.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:13px;");
        Label descLabel = new Label(desc);
        descLabel.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        labelBox.getChildren().addAll(titleLabel, descLabel);

        HBox controlBox = new HBox(control);
        controlBox.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().addAll(labelBox, controlBox);
        return row;
    }

    // ===== 辅助：ComboBox 快捷创建 =====
    private ComboBox<String> createComboBox(String... items) {
        ComboBox<String> cb = new ComboBox<>();
        cb.getItems().addAll(items);
        cb.setValue(items[0]);
        cb.setStyle("-fx-background-radius:6;-fx-pref-width:160;");
        cb.getStyleClass().add("pcl-combo");
        bindPopupFade(cb);
        bindComboHoverScale(cb);
        return cb;
    }

    // ===== 辅助：设置页 Slider 统一样式（仿播放页进度条 + 百分比提示）=====
    /** 从 styleSettingsSlider 返回的包装 HBox 中取出真正的 Slider 引用 */
    private Slider getSliderFromStyledBox(Node box) {
        if (box instanceof HBox hb && !hb.getChildren().isEmpty()) {
            Node last = hb.getChildren().get(hb.getChildren().size() - 1);
            if (last instanceof Slider s) return s;
        }
        throw new IllegalArgumentException("styleSettingsSlider 返回的容器结构已变");
    }

    private Node styleSettingsSlider(double min, double max, double value) {
        return styleSettingsSlider(min, max, value, null, null);
    }

    private Node styleSettingsSlider(double min, double max, double value, Consumer<Double> onChange) {
        return styleSettingsSlider(min, max, value, onChange, null);
    }

    // onChange：拖动时实时回调（值为当前滑块数值）；onRelease：松手时回调（用于落盘）
    private Node styleSettingsSlider(double min, double max, double value, Consumer<Double> onChange, Runnable onRelease) {
        Slider s = new Slider(min, max, value);
        s.getStyleClass().add("progress-slider");
        s.setCursor(Cursor.HAND);
        s.setPrefWidth(140);
        s.setShowTickLabels(false);
        s.setShowTickMarks(false);
        setupSliderTrackFill(s);

        Label pctLabel = new Label();
        pctLabel.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:11px;-fx-font-weight:bold;");
        pctLabel.setPrefWidth(34);
        pctLabel.setAlignment(Pos.CENTER);
        pctLabel.setOpacity(0);
        pctLabel.setMouseTransparent(true);
        pctLabel.setText((int) value + "%");

        s.setOnMousePressed(e -> {
            Timeline in = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(pctLabel.opacityProperty(), pctLabel.getOpacity())),
                    new KeyFrame(Duration.millis(120), new KeyValue(pctLabel.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
            in.play();
        });
        s.setOnMouseReleased(e -> {
            Timeline out = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(pctLabel.opacityProperty(), pctLabel.getOpacity())),
                    new KeyFrame(Duration.millis(200), new KeyValue(pctLabel.opacityProperty(), 0.0, Interpolator.EASE_OUT)));
            out.play();
            if (onRelease != null) onRelease.run();
        });
        s.valueProperty().addListener((obs, o, n) -> {
            int pct;
            if (min == 0 && max == 100) pct = (int) (s.getValue() + 0.5);
            else pct = (int) Math.round(s.getValue());
            pctLabel.setText(pct + "%");
            if (onChange != null) onChange.accept(s.getValue());
        });

        HBox box = new HBox(6, pctLabel, s);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    // 数值格式化：整数不显小数，否则两位（避免某些区域用逗号作小数点）
    private static String fmtDouble(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-6) return String.valueOf((int) Math.round(v));
        return String.format("%.2f", v);
    }

    // 通用数值滑块：实时显示真实数值（非百分比），用于 3D 歌词参数等连续量
    private Node rangedSlider(double min, double max, double value, double step,
                              java.util.function.Function<Double, String> fmt,
                              java.util.function.Consumer<Double> onChange, Runnable onRelease) {
        Slider s = new Slider(min, max, value);
        s.getStyleClass().add("progress-slider");
        s.setCursor(Cursor.HAND);
        s.setPrefWidth(140);
        s.setShowTickLabels(false);
        s.setShowTickMarks(false);
        s.setBlockIncrement(step);
        setupSliderTrackFill(s);
        Label valLabel = new Label(fmt.apply(value));
        valLabel.setStyle("-fx-text-fill:" + C_PRIMARY + ";-fx-font-size:11px;-fx-font-weight:bold;");
        valLabel.setPrefWidth(46);
        valLabel.setAlignment(Pos.CENTER);
        s.valueProperty().addListener((obs, o, n) -> {
            valLabel.setText(fmt.apply(s.getValue()));
            if (onChange != null) onChange.accept(s.getValue());
        });
        s.setOnMouseReleased(e -> { if (onRelease != null) onRelease.run(); });
        HBox box = new HBox(6, valLabel, s);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** 从 rangedSlider 返回的包装 HBox 中取出数值 Label（用于改成“自动”等自定义文案） */
    private Label getLabelFromRangedBox(Node box) {
        if (box instanceof HBox hb && !hb.getChildren().isEmpty()) {
            Node first = hb.getChildren().get(0);
            if (first instanceof Label lb) return lb;
        }
        return null;
    }

    // ===== 辅助：包装滚动面板 =====
    private ScrollPane wrapSettingsScroll(VBox content) {
        // 背景透明：白底由底层 centerWhiteBg 提供（受白色底不透明度设置控制），
        // ScrollPane 默认浅灰内背景会透出，故显式置透明，不再用不透明 C_PANEL 覆盖
        content.setStyle("-fx-background-color:transparent;");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color:transparent;-fx-background:transparent;");
        setupScrollbarGradient(scroll);
        attachSmoothVerticalScroll(scroll);   // 设置页滚动也走平滑动画
        return scroll;
    }

    // ===== Slider 已播放部分淡蓝色填充（通用版）=====
    private void setupSliderTrackFill(Slider slider) {
        Runnable updateTrack = () -> {
            double min = slider.getMin();
            double max = slider.getMax();
            double val = slider.getValue();
            double range = max - min;
            double ratio = range > 0 ? Math.max(0, Math.min(1, (val - min) / range)) : 0;
            int pct = (int)(ratio * 100);
            // 关键：value 每秒变好几次，但百分比在同一整数位内不变。
            // 不做这道去重就会每帧重写 CSS 渐变 → CSS 重解析 + 父链重排，播放时进度条持续发顿。
            if ((int) slider.getProperties().getOrDefault("trackPct", -1) == pct) return;
            slider.getProperties().put("trackPct", pct);
            Node trackNode = slider.lookup(".track");
            if (trackNode instanceof Region track) {
                track.setStyle(
                        "-fx-background-color: linear-gradient(to right, #90CAF9 " + pct + "%, #E3F0FE " + pct + "%);"
                                + "-fx-background-radius: 3px;"
                                + "-fx-pref-height: 6px;"
                );
            }
        };
        // 悬停平滑放大圆（用 Scale 变换：纯视觉、不受 Slider 内容区高度限制，故不会被裁切）
        Runnable attachHover = () -> {
            Node thumbNode = slider.lookup(".thumb");
            if (!(thumbNode instanceof Region thumb)) return;
            slider.setOnMouseEntered(e -> animateThumbScale(thumb, 1.1, true));
            slider.setOnMouseExited(e -> animateThumbScale(thumb, 1.0, false));
        };
        slider.skinProperty().addListener((obs, o, n) -> {
            if (n != null) { Platform.runLater(updateTrack); Platform.runLater(attachHover); }
        });
        slider.valueProperty().addListener((obs, o, n) -> updateTrack.run());
        slider.maxProperty().addListener((obs, o, n) -> updateTrack.run());
        if (slider.getSkin() != null) { Platform.runLater(updateTrack); Platform.runLater(attachHover); }
    }

    // 平滑过渡圆的大小：悬停放大到 target（默认 1.18≈16.5px 等效），离开还原。同时切换悬停配色。
    private void animateThumbScale(Region thumb, double target, boolean hover) {
        Timeline tl = (Timeline) thumb.getProperties().get("hoverScaleTL");
        if (tl != null) tl.stop();
        tl = new Timeline(new KeyFrame(Duration.millis(150),
                new KeyValue(thumb.scaleXProperty(), target, Interpolator.EASE_BOTH),
                new KeyValue(thumb.scaleYProperty(), target, Interpolator.EASE_BOTH)));
        thumb.getProperties().put("hoverScaleTL", tl);
        // 悬停配色交给 CSS 的 `.progress-slider:hover > .thumb` 处理。
        // 原来这里 setStyle(hover ? 深蓝 : "")，离开时清空整串内联样式会引发额外的 CSS 过程和两级跳变。
        tl.play();
    }

    // ===== 左侧播放器灵动交互辅助（全部为纯变换/特效，不改变布局）=====
    private void animateScale(Node node, double target, int ms) {
        Timeline tl = (Timeline) node.getProperties().get("scaleTL");
        if (tl != null) tl.stop();
        tl = new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(node.scaleXProperty(), target, Interpolator.EASE_BOTH),
                new KeyValue(node.scaleYProperty(), target, Interpolator.EASE_BOTH)));
        node.getProperties().put("scaleTL", tl);
        tl.play();
    }

    // 圆形按钮悬停：平滑放大 + 浮起阴影（离开还原）
    private void attachBtnHover(Button btn) {
        DropShadow sh = new DropShadow(0, 0, 0, Color.rgb(25, 118, 210, 0.35));
        btn.setEffect(sh);
        btn.setOnMouseEntered(e -> { animateScale(btn, 1.03, 140); animateEffectRadius(btn, sh, 5); });
        btn.setOnMouseExited(e -> { animateScale(btn, 1.0, 140); animateEffectRadius(btn, sh, 0); });
    }

    /**
     * 统一的按钮交互动效：手型光标 + 悬停轻微放大 + 按下缩小。
     * 之前很多按钮只做了 hover、完全没有按压反馈，点了没感觉；这里一次性补齐。
     * 全部走 animateScale（复用同一个 scaleTL），避免和 attachBtnHover 各自 new 动画互相打断。
     */
    private void attachButtonFx(Button btn) {
        btn.setCursor(Cursor.HAND);
        btn.setOnMouseEntered(e -> animateScale(btn, 1.03, 130));
        btn.setOnMouseExited(e -> animateScale(btn, 1.0, 130));
        btn.setOnMousePressed(e -> animateScale(btn, 0.96, 70));
        btn.setOnMouseReleased(e -> animateScale(btn, btn.isHover() ? 1.03 : 1.0, 90));
    }

    private void animateEffectRadius(Button btn, DropShadow sh, double r) {
        Timeline tl = (Timeline) btn.getProperties().get("effTL");
        if (tl != null) tl.stop();
        tl = new Timeline(new KeyFrame(Duration.millis(160), new KeyValue(sh.radiusProperty(), r, Interpolator.EASE_BOTH)));
        btn.getProperties().put("effTL", tl);
        tl.play();
    }

    private void setCoverShadow(boolean on) {
        if (coverShadow == null) return;
        Timeline tl = (Timeline) leftCoverFrame.getProperties().get("coverShadowTL");
        if (tl != null) tl.stop();
        tl = new Timeline(new KeyFrame(Duration.millis(200),
                new KeyValue(coverShadow.radiusProperty(), on ? 18 : 0, Interpolator.EASE_BOTH),
                new KeyValue(coverShadow.offsetYProperty(), on ? 6 : 0, Interpolator.EASE_BOTH)));
        leftCoverFrame.getProperties().put("coverShadowTL", tl);
        tl.play();
    }

    // 播放时封面缓慢"呼吸"（1.0↔1.03 循环），悬停时暂停、离开后恢复
    private void startCoverBreath() {
        if (leftCoverFrame == null) return;
        if (coverBreathTl != null) coverBreathTl.stop();
        coverBreathTl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(leftCoverFrame.scaleXProperty(), 1.0), new KeyValue(leftCoverFrame.scaleYProperty(), 1.0)),
                new KeyFrame(Duration.millis(1800), new KeyValue(leftCoverFrame.scaleXProperty(), 1.0, Interpolator.EASE_BOTH), new KeyValue(leftCoverFrame.scaleYProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(3600), new KeyValue(leftCoverFrame.scaleXProperty(), 1.0, Interpolator.EASE_BOTH), new KeyValue(leftCoverFrame.scaleYProperty(), 1.0, Interpolator.EASE_BOTH)));
        coverBreathTl.setCycleCount(Timeline.INDEFINITE);
        coverBreathTl.play();
        coverBreathing = true;
    }

    private void stopCoverBreath() {
        if (coverBreathTl != null) { coverBreathTl.stop(); coverBreathTl = null; }
        coverBreathing = false;
    }

    private void updateCoverBreath() {
        boolean playing = audioPlayer != null && audioPlayer.isPlaying();
        if (playing && !coverBreathing) startCoverBreath();
        else if (!playing && coverBreathing) stopCoverBreath();
    }

    // 切歌时标题/歌手淡入 + 轻微上滑
    private void animateLeftMetaIn() {
        if (leftInfo == null) return;
        Timeline tl = (Timeline) leftInfo.getProperties().get("metaTL");
        if (tl != null) tl.stop();
        tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(leftInfo.opacityProperty(), 0), new KeyValue(leftInfo.translateYProperty(), 8)),
                new KeyFrame(Duration.millis(280), new KeyValue(leftInfo.opacityProperty(), 1, Interpolator.EASE_OUT), new KeyValue(leftInfo.translateYProperty(), 0, Interpolator.EASE_OUT)));
        leftInfo.getProperties().put("metaTL", tl);
        tl.play();
    }

    private boolean isInteractive(Node n) {
        while (n != null) {
            if (n instanceof Button || n instanceof ToggleButton || n instanceof NavButton || n instanceof TextInputControl) return true;
            n = n.getParent();
        }
        return false;
    }

    private class NavButton extends HBox {
        private final ToggleButton toggle;
        private final Label text;
        private Timeline anim;
        private final ObjectProperty<Color> bgColor = new SimpleObjectProperty<>(NAV_BG_TRANSPARENT);

        NavButton(String label, boolean active) {
            setAlignment(Pos.CENTER);
            setMinHeight(27); setPrefHeight(27); setMaxHeight(27);
            setPadding(new Insets(0, 15, 0, 15));
            setBackground(Background.EMPTY);
            bgColor.addListener((obs, o, n) -> applyBackground(n, 14));
            toggle = new ToggleButton(); toggle.setSelected(active); toggle.setVisible(false); toggle.setManaged(false);
            text = new Label(label);
            text.setStyle("-fx-font-size:13px;-fx-font-weight:" + (active ? "bold" : "normal") + ";");
            text.setTextFill(active ? NAV_TEXT_ACTIVE : Color.web("#E3F0FE"));
            if (active) bgColor.set(NAV_BG_ACTIVE);
            getChildren().addAll(text, toggle);
            setOnMousePressed(e -> e.consume());
            setOnMouseClicked(e -> toggle.setSelected(true));
            toggle.selectedProperty().addListener((obs, o, n) -> animateTo(n));
            setOnMouseEntered(e -> { if (!toggle.isSelected()) animateBgTo(NAV_BG_HOVER, 160); });
            setOnMouseExited(e -> { if (!toggle.isSelected()) animateBgTo(NAV_BG_TRANSPARENT, 220); });
            setCursor(Cursor.HAND);
        }

        ToggleButton getToggle() { return toggle; }

        private void animateTo(boolean active) {
            Color tBg = active ? NAV_BG_ACTIVE : NAV_BG_TRANSPARENT, tFg = active ? NAV_TEXT_ACTIVE : Color.web("#E3F0FE");
            text.setFont(Font.font(Font.getDefault().getFamily(), active ? FontWeight.BOLD : FontWeight.NORMAL, 13));
            if (anim != null) anim.stop();
            Color sBg = bgColor.get(), sFg = (Color) text.getTextFill();
            anim = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(bgColor, sBg, Interpolator.EASE_BOTH), new KeyValue(text.textFillProperty(), sFg, Interpolator.EASE_BOTH)), new KeyFrame(Duration.millis(180), new KeyValue(bgColor, tBg, Interpolator.EASE_BOTH), new KeyValue(text.textFillProperty(), tFg, Interpolator.EASE_BOTH)));
            anim.play();
        }

        private void applyBackground(Color c, double r) {
            if (c == null || c.getOpacity() < 0.02) { setBackground(Background.EMPTY); return; }
            setBackground(new Background(new BackgroundFill(c, new CornerRadii(r), Insets.EMPTY)));
        }

        private Timeline hoverAnim;

        /**
         * hover 底色渐变。选中态 animateTo 已有 180ms 过渡，而 hover 原来是直接 applyBackground 瞬变，
         * 同一个控件两种节奏、观感割裂。这里改为动画 bgColor，缓动与选中切换一致。
         */
        private void animateBgTo(Color target, int ms) {
            if (anim != null) anim.stop();          // 避免与选中切换动画同时写 bgColor
            if (hoverAnim != null) hoverAnim.stop();
            Color sBg = bgColor.get();
            hoverAnim = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(bgColor, sBg, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.millis(ms), new KeyValue(bgColor, target, Interpolator.EASE_OUT)));
            hoverAnim.play();
        }
    }

    private void saveCookie(String cookie) {
        try {
            Path p = Paths.get(COOKIE_FILE);
            Files.createDirectories(p.getParent());
            Files.writeString(p, cookie);
        } catch (Exception e) { System.out.println("⚠ 保存 cookie 失败: " + e.getMessage()); }
    }

    private String loadCookie() {
        try {
            Path p = Paths.get(COOKIE_FILE);
            if (Files.exists(p)) { String c = Files.readString(p); if (c != null && !c.isBlank()) return c; }
        } catch (Exception e) { System.out.println("⚠ 读取 cookie 失败: " + e.getMessage()); }
        return null;
    }

    // ==================== 播放设置持久化 ====================
    private void loadSettings() {
        try {
            Path p = Paths.get(SETTINGS_FILE);
            if (!Files.exists(p)) return;
            Properties prop = new Properties();
            try (var in = Files.newInputStream(p)) { prop.load(in); }
            autoPlayOnStartup = Boolean.parseBoolean(prop.getProperty("autoPlayOnStartup", "false"));
            disclaimerShown = Boolean.parseBoolean(prop.getProperty("disclaimerShown", "false"));
            loopMode = prop.getProperty("loopMode", "列表循环");
            try { defaultVolume = Integer.parseInt(prop.getProperty("defaultVolume", "50")); }
            catch (NumberFormatException ignore) { defaultVolume = 50; }
            if (defaultVolume < 0) defaultVolume = 0;
            if (defaultVolume > 100) defaultVolume = 100;
            highAudioQuality = Boolean.parseBoolean(prop.getProperty("highAudioQuality", "true"));
            // 旧版本把该项存为"封面"/"歌词"（左侧封面区二选一），现已改为右侧"歌词/播放列表"。
            // 默认沿用"播放列表"：列表是可点选歌曲的主界面，歌词为可选视图。
            // 旧值"封面"以及其它非法值一律回落到"播放列表"，保证启动即可交互。
            playPageDisplay = prop.getProperty("playPageDisplay", "播放列表");
            if (!"歌词".equals(playPageDisplay) && !"播放列表".equals(playPageDisplay)) {
                playPageDisplay = "播放列表";
            }
            lastPlayedBvid = prop.getProperty("lastPlayedBvid", null);
            if (lastPlayedBvid != null && lastPlayedBvid.isBlank()) lastPlayedBvid = null;
            try { lastFolderId = Long.parseLong(prop.getProperty("lastFolderId", "0")); }
            catch (NumberFormatException ignore) { lastFolderId = 0; }
            lastFolderTitle = prop.getProperty("lastFolderTitle", null);
            if (lastFolderTitle != null && lastFolderTitle.isBlank()) lastFolderTitle = null;
            try { lastFolderCount = Integer.parseInt(prop.getProperty("lastFolderCount", "0")); }
            catch (NumberFormatException ignore) { lastFolderCount = 0; }
            lastFolderSeason = Boolean.parseBoolean(prop.getProperty("lastFolderSeason", "false"));
            try { lastFavTab = Integer.parseInt(prop.getProperty("lastFavTab", "0")); }
            catch (NumberFormatException ignore) { lastFavTab = 0; }
            if (lastFavTab != 0 && lastFavTab != 1) lastFavTab = 0;   // 2=追番追剧不是收藏夹 tab

            // 歌词设置
            String srcLabel = prop.getProperty("lyricSourceOrder", null);
            if (srcLabel != null && LYRIC_SOURCE_ORDERS.containsKey(srcLabel)) {
                lyricSourceOrder = LYRIC_SOURCE_ORDERS.get(srcLabel);
            }
            try { lyricFontSize = Integer.parseInt(prop.getProperty("lyricFontSize", "16")); }
            catch (NumberFormatException ignore) { lyricFontSize = 16; }
            if (lyricFontSize < 12) lyricFontSize = 12;
            if (lyricFontSize > 36) lyricFontSize = 36;
            lyricFontCN = prop.getProperty("lyricFontCN", "Microsoft YaHei");
            lyricFontEN = prop.getProperty("lyricFontEN", "Computer Modern Italic");
            lyricFontCNPath = prop.getProperty("lyricFontCNPath", "");
            lyricFontENPath = prop.getProperty("lyricFontENPath", "");
            lyricBold = Boolean.parseBoolean(prop.getProperty("lyricBold", "true"));
            String colorLabel = prop.getProperty("lyricHighlight", "蓝色");
            if (LYRIC_COLORS.containsKey(colorLabel)) lyricHighlight = LYRIC_COLORS.get(colorLabel);
            useLyricRendererFX = Boolean.parseBoolean(prop.getProperty("useLyricRendererFX", "false"));
            lyricAnchor = prop.getProperty("lyricAnchor", "左");
            if (!"左".equals(lyricAnchor) && !"中".equals(lyricAnchor) && !"右".equals(lyricAnchor)) {
                lyricAnchor = "左";
            }
            // 连续锚点优先；旧存档没有该项时按 左/中/右 推导
            d3AnchorX = loadDouble(prop, "d3AnchorX",
                    "右".equals(lyricAnchor) ? 0.78 : "中".equals(lyricAnchor) ? 0.5 : 0.18);
            d3AutoFlipRotate = Boolean.parseBoolean(prop.getProperty("d3AutoFlipRotate", "true"));
            d3RotateZ = loadDouble(prop, "d3RotateZ", 30);
            d3MinScale = loadDouble(prop, "d3MinScale", 0.7);
            d3MaxScaleBoost = loadDouble(prop, "d3MaxScaleBoost", 2.0);
            d3AffectRows = loadDouble(prop, "d3AffectRows", 8.0);
            d3TransRange = loadDouble(prop, "d3TransRange", 1.1);
            d3ZoomPower = loadDouble(prop, "d3ZoomPower", 3.0);
            d3ScaleBig = loadDouble(prop, "d3ScaleBig", 1.55);
            d3ScaleSmall = loadDouble(prop, "d3ScaleSmall", 0.75);
            d3StrokeWidth = loadDouble(prop, "d3StrokeWidth", 0.7);
            d3StrokeAuto = Boolean.parseBoolean(prop.getProperty("d3StrokeAuto", "false"));
            d3BlurEnabled = Boolean.parseBoolean(prop.getProperty("d3BlurEnabled", "true"));
            // 外观设置：读回并夹到滑块允许范围内
            windowOpacity = (int) loadDouble(prop, "windowOpacity", 100);
            if (windowOpacity < 50) windowOpacity = 50;
            if (windowOpacity > 100) windowOpacity = 100;
            windowCornerRadius = (int) loadDouble(prop, "windowCornerRadius", RADIUS);
            if (windowCornerRadius < 0) windowCornerRadius = 0;
            if (windowCornerRadius > 20) windowCornerRadius = 20;
            coverCornerRadius = (int) loadDouble(prop, "coverCornerRadius", COVER_RADIUS);
            if (coverCornerRadius < 0) coverCornerRadius = 0;
            if (coverCornerRadius > 30) coverCornerRadius = 30;
            titleBarOpacity = (int) loadDouble(prop, "titleBarOpacity", 85);
            if (titleBarOpacity < 30) titleBarOpacity = 30;
            if (titleBarOpacity > 100) titleBarOpacity = 100;
            frostGrain = (int) loadDouble(prop, "frostGrain", 14);
            if (frostGrain < 0) frostGrain = 0;
            if (frostGrain > 30) frostGrain = 30;
            bgBlueOpacity = (int) loadDouble(prop, "bgBlueOpacity", 100);
            if (bgBlueOpacity < 0) bgBlueOpacity = 0;
            if (bgBlueOpacity > 100) bgBlueOpacity = 100;
            bgBlueBlur = (int) loadDouble(prop, "bgBlueBlur", 14);
            if (bgBlueBlur < 0) bgBlueBlur = 0;
            if (bgBlueBlur > 30) bgBlueBlur = 30;
            whiteBgOpacity = (int) loadDouble(prop, "whiteBgOpacity", 100);
            if (whiteBgOpacity < 0) whiteBgOpacity = 0;
            if (whiteBgOpacity > 100) whiteBgOpacity = 100;
            cardBlurEnabled = Boolean.parseBoolean(prop.getProperty("cardBlurEnabled", "true"));
            // 全局快捷键绑定
            hotkeyManager.setBinding(HotkeyManager.PLAY_PAUSE, prop.getProperty("hotkey.PLAY_PAUSE", ""));
            hotkeyManager.setBinding(HotkeyManager.NEXT, prop.getProperty("hotkey.NEXT", ""));
            hotkeyManager.setBinding(HotkeyManager.PREV, prop.getProperty("hotkey.PREV", ""));
            hotkeyManager.setBinding(HotkeyManager.DESKTOP_LYRIC, prop.getProperty("hotkey.DESKTOP_LYRIC", ""));
            // 自定义歌名：键以 "customTitle." 前缀存放，键名做 URL 编码避免冒号/中文破坏 properties 解析
            for (String name : prop.stringPropertyNames()) {
                if (name.startsWith("customTitle.")) {
                    try {
                        String key = java.net.URLDecoder.decode(name.substring("customTitle.".length()), "UTF-8");
                        customTitles.put(key, prop.getProperty(name));
                    } catch (Exception ignore) {}
                }
            }
            System.out.println("[settings] 已恢复：自动播放=" + autoPlayOnStartup
                    + "，循环模式=" + loopMode + "，音量=" + defaultVolume);
        } catch (Exception e) {
            System.out.println("⚠ 读取设置失败: " + e.getMessage());
        }
    }

    // 从设置读取 double（解析失败回退默认值），避免区域小数点导致 NumberFormatException
    private double loadDouble(Properties prop, String key, double def) {
        try { return Double.parseDouble(prop.getProperty(key, fmtDouble(def))); }
        catch (NumberFormatException ignore) { return def; }
    }

    private void saveSettings() {
        try {
            Path p = Paths.get(SETTINGS_FILE);
            Files.createDirectories(p.getParent());
            Properties prop = new Properties();
            prop.setProperty("autoPlayOnStartup", String.valueOf(autoPlayOnStartup));
            prop.setProperty("disclaimerShown", String.valueOf(disclaimerShown));
            prop.setProperty("loopMode", loopMode);
            prop.setProperty("defaultVolume", String.valueOf(defaultVolume));
            prop.setProperty("highAudioQuality", String.valueOf(highAudioQuality));
            prop.setProperty("playPageDisplay", playPageDisplay);
            if (lastPlayedBvid != null) prop.setProperty("lastPlayedBvid", lastPlayedBvid);
            prop.setProperty("lastFolderId", String.valueOf(lastFolderId));
            if (lastFolderTitle != null) prop.setProperty("lastFolderTitle", lastFolderTitle);
            prop.setProperty("lastFolderCount", String.valueOf(lastFolderCount));
            prop.setProperty("lastFolderSeason", String.valueOf(lastFolderSeason));
            prop.setProperty("lastFavTab", String.valueOf(lastFavTab));
            prop.setProperty("lyricSourceOrder", labelOfLyricSourceOrder(lyricSourceOrder));
            prop.setProperty("lyricFontSize", String.valueOf(lyricFontSize));
            prop.setProperty("lyricHighlight", labelOfLyricColor(lyricHighlight));
            prop.setProperty("lyricFontCN", lyricFontCN);
            prop.setProperty("lyricFontEN", lyricFontEN);
            prop.setProperty("lyricFontCNPath", lyricFontCNPath);
            prop.setProperty("lyricFontENPath", lyricFontENPath);
            prop.setProperty("lyricBold", String.valueOf(lyricBold));
            prop.setProperty("useLyricRendererFX", String.valueOf(useLyricRendererFX));
            prop.setProperty("lyricAnchor", lyricAnchor);
            prop.setProperty("d3AnchorX", fmtDouble(d3AnchorX));
            prop.setProperty("d3AutoFlipRotate", String.valueOf(d3AutoFlipRotate));
            prop.setProperty("d3RotateZ", fmtDouble(d3RotateZ));
            prop.setProperty("d3MinScale", fmtDouble(d3MinScale));
            prop.setProperty("d3StrokeWidth", fmtDouble(d3StrokeWidth));
            prop.setProperty("d3StrokeAuto", String.valueOf(d3StrokeAuto));
            prop.setProperty("d3BlurEnabled", String.valueOf(d3BlurEnabled));
            prop.setProperty("d3MaxScaleBoost", fmtDouble(d3MaxScaleBoost));
            prop.setProperty("d3AffectRows", fmtDouble(d3AffectRows));
            prop.setProperty("d3TransRange", fmtDouble(d3TransRange));
            prop.setProperty("d3ZoomPower", fmtDouble(d3ZoomPower));
            prop.setProperty("d3ScaleBig", fmtDouble(d3ScaleBig));
            prop.setProperty("d3ScaleSmall", fmtDouble(d3ScaleSmall));
            prop.setProperty("windowOpacity", String.valueOf(windowOpacity));
            prop.setProperty("windowCornerRadius", String.valueOf(windowCornerRadius));
            prop.setProperty("coverCornerRadius", String.valueOf(coverCornerRadius));
            prop.setProperty("titleBarOpacity", String.valueOf(titleBarOpacity));
            prop.setProperty("frostGrain", String.valueOf(frostGrain));
            prop.setProperty("bgBlueOpacity", String.valueOf(bgBlueOpacity));
            prop.setProperty("bgBlueBlur", String.valueOf(bgBlueBlur));
            prop.setProperty("whiteBgOpacity", String.valueOf(whiteBgOpacity));
            prop.setProperty("cardBlurEnabled", String.valueOf(cardBlurEnabled));
            prop.setProperty("hotkey.PLAY_PAUSE", hotkeyManager.getBinding(HotkeyManager.PLAY_PAUSE));
            prop.setProperty("hotkey.NEXT", hotkeyManager.getBinding(HotkeyManager.NEXT));
            prop.setProperty("hotkey.PREV", hotkeyManager.getBinding(HotkeyManager.PREV));
            prop.setProperty("hotkey.DESKTOP_LYRIC", hotkeyManager.getBinding(HotkeyManager.DESKTOP_LYRIC));
            // 自定义歌名：遍历 Map 写入，键名做 URL 编码
            for (var e : customTitles.entrySet()) {
                prop.setProperty("customTitle." + java.net.URLEncoder.encode(e.getKey(), "UTF-8"), e.getValue());
            }
            try (var out = Files.newOutputStream(p)) { prop.store(out, "pcl-player play settings"); }
        } catch (Exception e) {
            System.out.println("⚠ 保存设置失败: " + e.getMessage());
        }
    }

    /**
     * 记住当前播放的这首歌及其所属收藏夹（只存 BV 号与收藏夹标识，进度不保存）。
     * 下次启动时据此：1) 恢复这首歌；2) 自动加载该收藏夹以填充播放页列表、支持连播。
     */
    private void rememberPlayback(String bvid) {
        if (bvid == null) return;
        lastPlayedBvid = bvid;
        // 记录所属收藏夹：优先用当前选中的，其次在已缓存列表里反查这首歌属于哪个收藏夹
        FavFolder folder = lastSelectedFolder;
        if (folder == null) {
            for (var e : folderVideoCache.entrySet()) {
                if (e.getValue() == null) continue;
                for (FavVideo fv : e.getValue()) {
                    if (fv.bvid.equals(bvid)) { folder = e.getKey(); break; }
                }
                if (folder != null) break;
            }
        }
        if (folder != null) {
            lastFolderId = folder.id;
            lastFolderTitle = folder.title;
            lastFolderCount = folder.count;
            lastFolderSeason = folder.season;
        }
        saveSettings();
    }

    /**
     * 退出时记录"此刻歌单页选中的收藏夹"，下次启动自动加载并选中。
     * 与 rememberPlayback 的区别：那边记录的是"正在播放的歌所属收藏夹"（播放时触发），
     * 这里记录的是"当前选中的收藏夹"（退出时触发）。
     * 两者结合才能覆盖「退出前只是浏览了别的收藏夹、并没有播放其中歌曲」的情况。
     */
    private void savePlaylistStateOnExit() {
        // 关闭窗口会依次触发 setOnCloseRequest、setOnHiding，再加 JVM 停机钩子，
        // 最多调 3 次。只落盘一次，避免重复写文件和刷日志。
        if (exitStateSaved) return;
        exitStateSaved = true;
        try {
            FavFolder folder = lastSelectedFolder;
            if (folder != null) {
                lastFolderId = folder.id;
                lastFolderTitle = folder.title;
                lastFolderCount = folder.count;
                lastFolderSeason = folder.season;
                // 仅当用户确实进过歌单页、且停在收藏夹 tab（0/1）时才更新 tab；
                // 停在"追番追剧"(2) 或压根没进过歌单页时，保留上次记录的值
                if (favTabVisited && (currentFavTab == 0 || currentFavTab == 1)) {
                    lastFavTab = currentFavTab;
                }
            }
            saveSettings();
            System.out.println("[exit] 已记录收藏夹: "
                    + (folder != null ? folder.title : "(无)") + " tab=" + lastFavTab);
            if (audioPlayer != null) audioPlayer.release();   // 清理预取缓存的临时文件
        } catch (Exception e) {
            // 退出阶段不可抛异常，否则会干扰关闭流程
            System.out.println("[exit] 记录收藏夹失败: " + e.getMessage());
        }
    }

    /** 初始化系统托盘。不支持时保持“关闭即退出”的原有行为 */
    private void setupSystemTray() {
        if (!java.awt.SystemTray.isSupported()) {
            trayAvailable = false;
            return;
        }
        try {
            systemTray = java.awt.SystemTray.getSystemTray();
            trayIcon = new java.awt.TrayIcon(makeTrayImage(), "plain bmusic player");
            trayIcon.setImageAutoSize(true);
            // 右键托盘图标弹出 JavaFX 菜单；左键作兜底。以“按下”为准，
            // 快速右击时松手才带 isPopupTrigger，若不防二次触发会“打开又被关掉”，
            // 反而只有长按才看得到菜单。
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON3 || e.isPopupTrigger()) {
                        trayPressHandled = true;   // 标记已处理，松手不再触发
                        toggleTrayMenu();
                    }
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger() && !trayPressHandled) toggleTrayMenu(); // 兜底：仅在松手派发时
                    trayPressHandled = false;
                }
            });
            // 双击/单击托盘图标：直接显示主窗口，不再弹菜单
            trayIcon.addActionListener(e -> showPrimaryWindow());
            systemTray.add(trayIcon);
            trayAvailable = true;
            System.out.println("[tray] 系统托盘已启用");
        } catch (Exception ex) {
            trayAvailable = false;
            System.out.println("[tray] 初始化失败，关闭将直接退出: " + ex.getMessage());
        }
    }

    /** 取当前光标的 AWT 屏幕坐标（失败返回 null 走兜底定位） */
    private java.awt.Point cursorPoint() {
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            return pi != null ? pi.getLocation() : null;
        } catch (Exception ex) { return null; }
    }

    /** 右键切换菜单：已开则关，未开则在光标处弹出 */
    private void toggleTrayMenu() {
        java.awt.Point p = cursorPoint();
        Platform.runLater(() -> {
            if (trayMenuStage != null && trayMenuStage.isShowing()) { trayMenuStage.hide(); return; }
            showTrayMenu(p);
        });
    }

    /** 显示/置顶主窗口（双击托盘图标与菜单项共用） */
    private void showPrimaryWindow() {
        Platform.runLater(() -> {
            primaryStage.setIconified(false);
            primaryStage.show();
            primaryStage.toFront();
            if (lyricSettingsStage != null && lyricSettingsStage.isShowing()) lyricSettingsStage.toFront();
        });
    }

    /** 点击托盘图标时弹出 JavaFX 小菜单；p 为 AWT 屏幕坐标（右键位置），null 则用屏幕右下角兜底 */
    private void showTrayMenu(java.awt.Point awtPt) {
        if (trayMenuStage == null) buildTrayMenu();
        if (trayMenuStage.isShowing()) return;   // 幂等：已显示则不再定位
        // AWT 屏幕坐标 -> JavaFX 逻辑坐标（规避 DPI 缩放导致的错位）
        javafx.stage.Screen target = javafx.stage.Screen.getPrimary();
        double fxX, fxY;
        if (awtPt != null) {
            java.awt.Dimension awtSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            javafx.geometry.Rectangle2D pb = target.getVisualBounds();
            double sx = pb.getWidth() / awtSize.getWidth();
            double sy = pb.getHeight() / awtSize.getHeight();
            fxX = awtPt.x * sx;
            fxY = awtPt.y * sy;
            for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
                if (s.getVisualBounds().contains(fxX, fxY)) { target = s; break; }
            }
        } else {
            javafx.geometry.Rectangle2D pb = target.getVisualBounds();
            fxX = pb.getMaxX() - 160;
            fxY = pb.getMaxY() - 80;
        }
        final double px = fxX, py = fxY;
        // 先以透明显示拿到真实尺寸，再定位，避免未布局时宽高为 0 跑到屏幕外
        trayMenuStage.setOpacity(0);
        trayMenuStage.show();
        double w = trayMenuStage.getWidth();
        double h = trayMenuStage.getHeight();
        // 水平仍从指针向右展开；垂直以指针为中心（指针位于菜单左侧垂直正中）
        double x = px;
        double y = py - h * 0.5;
        javafx.geometry.Rectangle2D b = target.getVisualBounds();
        x = Math.max(b.getMinX(), Math.min(x, b.getMaxX() - w));
        y = Math.max(b.getMinY(), Math.min(y, b.getMaxY() - h));
        trayMenuStage.setX(x);
        trayMenuStage.setY(y);
        trayMenuStage.setOpacity(1);
        trayMenuStage.toFront();
    }

    /** 无坐标时的兜底入口（左键） */
    private void showTrayMenu() {
        showTrayMenu(null);
    }

    /** 构建托盘菜单（无装饰小窗，含“显示主窗口 / 退出”） */
    private void buildTrayMenu() {
        trayMenuStage = new Stage();
        trayMenuStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
        trayMenuStage.setAlwaysOnTop(true);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox();
        box.setStyle("-fx-background-color:white;-fx-border-color:#C9CED6;-fx-border-width:1;-fx-padding:1;");
        javafx.scene.control.Button showBtn = new javafx.scene.control.Button("显示主窗口");
        javafx.scene.control.Button exitBtn = new javafx.scene.control.Button("退出");
        for (javafx.scene.control.Button b : new javafx.scene.control.Button[]{showBtn, exitBtn}) {
            b.setPrefWidth(92);
            b.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            b.setStyle("-fx-background-color:white;-fx-text-fill:#444;"
                    + "-fx-font-size:11px;-fx-padding:4 6;-fx-cursor:hand;");
            b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:#E9EBEF;-fx-text-fill:#222;"
                    + "-fx-font-size:11px;-fx-padding:4 6;-fx-cursor:hand;"));
            b.setOnMouseExited(e -> b.setStyle("-fx-background-color:white;-fx-text-fill:#444;"
                    + "-fx-font-size:11px;-fx-padding:4 6;-fx-cursor:hand;"));
        }
        showBtn.setOnAction(e -> {
            trayMenuStage.hide();
            showPrimaryWindow();
        });
        exitBtn.setOnAction(e -> {
            trayMenuStage.hide();
            trayExit();
        });
        box.getChildren().addAll(showBtn, exitBtn);
        javafx.scene.Scene sc = new javafx.scene.Scene(box);
        sc.setFill(javafx.scene.paint.Color.TRANSPARENT);
        trayMenuStage.setScene(sc);
        trayMenuStage.sizeToScene();
        // 点击别处自动关闭菜单
        trayMenuStage.focusedProperty().addListener((o, ov, nv) -> {
            if (!nv) trayMenuStage.hide();
        });
    }

    /** 生成简单的蓝色托盘图标（不依赖外部图片资源） */
    private java.awt.Image makeTrayImage() {
        // 优先用打包进 classpath 的 app_icon.png（用户放的真实图标）
        try (java.io.InputStream is = getClass().getResourceAsStream("/app_icon.png")) {
            if (is != null) {
                java.awt.image.BufferedImage full = javax.imageio.ImageIO.read(is);
                if (full != null) {
                    // 缩放到 16x16（托盘图标要求小尺寸）
                    java.awt.image.BufferedImage scaled =
                            new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.drawImage(full, 0, 0, 16, 16, null);
                    g.dispose();
                    return scaled;
                }
            }
        } catch (Exception ignored) { }
        // 兜底：画一个蓝底白 "L" 的圆形
        int s = 16;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1976D2));
        g.fillOval(1, 1, s - 2, s - 2);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String t = "L";
        g.drawString(t, (s - fm.stringWidth(t)) / 2, (s + fm.getAscent()) / 2 - 1);
        g.dispose();
        return img;
    }

    /** 最小化到托盘：隐藏主窗口及其附属的设置窗口 */
    private void hideToTray() {
        if (lyricSettingsStage != null && lyricSettingsStage.isShowing()) lyricSettingsStage.hide();
        primaryStage.hide();
    }

    /** 托盘菜单“退出”：移除图标并真正退出 JVM */
    private void trayExit() {
        trayExit = true;
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon);
            trayIcon = null;
        }
        Platform.runLater(() -> {
            primaryStage.close();
            Platform.exit();
        });
        // AWT 托盘线程可能阻止 JVM 退出，强制结束兜底
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            System.exit(0);
        }).start();
    }

    public static void main(String[] args) {
        // 单实例守卫：已在运行时提示后退出，避免多开抢同一个端口/托盘/音频设备
        if (!acquireSingleInstance()) {
            notifyAlreadyRunning();
            return;
        }
        launch(args);
    }

    /**
     * 单实例守卫：同一时间只允许一个实例运行。
     * <p>用文件锁实现——锁随进程退出由系统自动释放，不会残留死锁。
     * 锁文件放在 {@code user.home} 而非当前工作目录，这样从 IDEA 启动的
     * 开发版和打包后的 {@code PCPlayerUI.exe} 也会互斥，不能各开一份。
     *
     * @return true=抢到锁，本进程是首个实例；false=已有实例正在运行
     */
    private static boolean acquireSingleInstance() {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".pcl-player");
            Files.createDirectories(dir);
            instanceChannel = FileChannel.open(dir.resolve("run.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = instanceChannel.tryLock();
            if (instanceLock == null) {
                // 锁被占 = 已有实例
                instanceChannel.close();
                instanceChannel = null;
                return false;
            }
            return true;
        } catch (Exception e) {
            // 拿不到锁就放行：守卫失败绝不能连正常启动都一起拦住
            System.out.println("[single] 单实例检查跳过: " + e.getMessage());
            instanceChannel = null;
            instanceLock = null;
            return true;
        }
    }

    /**
     * 启动本地回环端口监听：第二个实例连上来即调用已有实例把窗口显示/置顶。
     * 守护线程运行，绑定失败（端口被占）仅打印警告，不影响正常启动。
     */
    private static void startIpcServer() {
        try {
            ipcServer = new java.net.ServerSocket(IPC_PORT, 0, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (ipcServer != null && !ipcServer.isClosed()) {
                    try (java.net.Socket s = ipcServer.accept()) {
                        try { s.getOutputStream().write(1); } catch (Exception ignored) {}
                        if (appInstance != null) appInstance.showPrimaryWindow();  // 复用托盘“显示窗口”
                    } catch (java.io.IOException ignored) { }
                }
            }, "ipc-show-window");
            t.setDaemon(true);
            t.start();
        } catch (java.io.IOException e) {
            System.out.println("[ipc] 显示服务启动失败(端口可能已被占用): " + e.getMessage());
        }
    }

    /**
     * 已有实例在运行：连本地回环端口通知老实例把窗口显示/置顶，然后本进程退出。
     * 不再弹窗/不再静默——直接激活已经运行的程序窗口（用户再双击一次 = 把已开的程序提到前台）。
     */
    private static void notifyAlreadyRunning() {
        System.out.println("[single] 程序已在运行，激活已有窗口");
        try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getByName("127.0.0.1"), IPC_PORT)) {
            s.getOutputStream().write(1);
            s.setSoTimeout(2000);
            try { s.getInputStream().read(); } catch (Exception ignored) {}
        } catch (Throwable ignored) {
            // 连不上（老实例无此能力/端口被占）就直接退出
        } finally {
            System.exit(0);   // 本进程不留下，干净退出
        }
    }
}
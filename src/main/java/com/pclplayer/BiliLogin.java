package com.pclplayer;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BiliLogin {

    // ★ 只取 qrcode_key，URL 我们自己拼
    private static final String GEN_URL  = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header&go_url=https:%2F%2Fwww.bilibili.com%2F&web_location=333.1007";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 状态文案与配色
    private static final String C_SUB  = "#8A9099";
    private static final String C_BLUE = "#1976D2";
    private static final String C_GREEN = "#00B96B";
    private static final String C_RED  = "#E54545";
    private static final String C_TEXT_DARK = "#1F2329";

    static class CookieJar {
        private final Map<String, String> cookies = new HashMap<>();
        public synchronized void put(String k, String v) { cookies.put(k, v); }
        public synchronized String get(String k) { return cookies.get(k); }
        public synchronized void parseFromHeaders(Map<String, List<String>> headers) {
            if (headers == null) return;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                    for (String v : entry.getValue()) {
                        String[] parts = v.split(";")[0].split("=", 2);
                        if (parts.length == 2) cookies.put(parts[0].trim(), parts[1]);
                    }
                }
            }
        }
        public synchronized String toHeader() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        }
    }

    private static void fetchBuvid(CookieJar jar) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://www.bilibili.com/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", UA);
            conn.connect();
            jar.parseFromHeaders(conn.getHeaderFields());
            conn.disconnect();
            System.out.println("[login] buvid3=" + jar.get("buvid3"));
        } catch (Exception e) {
            System.out.println("[login] 获取 buvid 失败: " + e.getMessage());
        }
    }

    /**
     * 登录窗的界面状态 + 网络流程。
     * 抽成类是为了让「刷新二维码」能整套重跑一遍而不必重复 UI 代码。
     */
    private static final class LoginUi {
        final Stage win;
        final ImageView qrView;
        final Label tip;
        final ProgressIndicator qrSpinner;     // 二维码区转圈
        final ProgressIndicator statusSpinner; // 状态行转圈
        final Button refreshBtn;
        final StackPane successOverlay;
        final LoginCallback onSuccess;
        volatile AtomicBoolean stopped = new AtomicBoolean(true);

        LoginUi(Stage win, ImageView qrView, Label tip, ProgressIndicator qrSpinner,
                ProgressIndicator statusSpinner, Button refreshBtn, StackPane successOverlay,
                LoginCallback onSuccess) {
            this.win = win;
            this.qrView = qrView;
            this.tip = tip;
            this.qrSpinner = qrSpinner;
            this.statusSpinner = statusSpinner;
            this.refreshBtn = refreshBtn;
            this.successOverlay = successOverlay;
            this.onSuccess = onSuccess;
        }

        /** 更新状态行；loading 为 true 时显示转圈 */
        void status(String text, String color, boolean loading) {
            Platform.runLater(() -> {
                tip.setText(text);
                tip.setStyle("-fx-text-fill:" + color + ";-fx-font-size:12px;");
                qrSpinner.setVisible(loading);
                statusSpinner.setVisible(loading);
            });
        }

        void showRefresh(boolean show) {
            Platform.runLater(() -> refreshBtn.setVisible(show));
        }

        /** 开始一次完整的「取码 → 轮询」流程（首次打开与刷新共用） */
        void start() {
            stopped.set(true);                 // 先停掉上一轮（刷新时用）
            AtomicBoolean token = new AtomicBoolean(false);
            stopped = token;

            Platform.runLater(() -> {
                successOverlay.setVisible(false);
                refreshBtn.setVisible(false);
                qrView.setImage(null);
            });
            status("正在获取二维码...", C_SUB, true);

            CookieJar jar = new CookieJar();
            fetchBuvid(jar);

            new Thread(() -> {
                HttpURLConnection genConn = null;
                try {
                    genConn = (HttpURLConnection) new URL(GEN_URL).openConnection();
                    genConn.setRequestMethod("GET");
                    genConn.setConnectTimeout(8_000);
                    genConn.setReadTimeout(20_000);
                    genConn.setRequestProperty("User-Agent", UA);
                    genConn.setRequestProperty("Referer", "https://passport.bilibili.com/login");
                    genConn.setRequestProperty("Cookie", jar.toHeader());
                    jar.parseFromHeaders(genConn.getHeaderFields());

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(genConn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line; while ((line = br.readLine()) != null) sb.append(line);
                    }
                    String json = sb.toString();
                    System.out.println("[login] generate 响应: " + json);

                    // ★ 只取 qrcode_key
                    String qrcodeKey = extract(json, "\"qrcode_key\":\"([^\"]+)\"");
                    if (qrcodeKey == null) {
                        status("获取二维码失败", C_RED, false);
                        showRefresh(true);
                        return;
                    }

                    final String finalKey = qrcodeKey;

                    // ★★★ 自己拼二维码 URL，确保 from=main-fe-header ★★★
                    final String qrContent = "https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=" + finalKey + "&from=main-fe-header";
                    System.out.println("[login] 二维码内容: " + qrContent);

                    Platform.runLater(() -> {
                        try {
                            String encoded = URLEncoder.encode(qrContent, StandardCharsets.UTF_8);
                            String qrImageUrl = "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=" + encoded;
                            qrView.setImage(new Image(qrImageUrl, 200, 200, true, true));
                            qrSpinner.setVisible(false);
                            tip.setText("请用 B 站 App 扫码");
                            tip.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:12px;");
                        } catch (Exception ex) {
                            status("二维码生成失败", C_RED, false);
                            showRefresh(true);
                            ex.printStackTrace();
                        }
                    });

                    // ===== 轮询 =====
                    Thread pollThread = new Thread(() -> {
                        HttpURLConnection conn = null;
                        try {
                            for (int i = 0; i < 90 && !token.get(); i++) {
                                Thread.sleep(2000);
                                if (token.get()) return;

                                String pollUrl = POLL_URL + "?qrcode_key=" + URLEncoder.encode(finalKey, StandardCharsets.UTF_8) + "&source=main-fe-header";
                                conn = (HttpURLConnection) new URL(pollUrl).openConnection();
                                conn.setRequestMethod("GET");
                                conn.setConnectTimeout(8_000);
                                conn.setReadTimeout(20_000);
                                conn.setRequestProperty("User-Agent", UA);
                                conn.setRequestProperty("Referer", "https://passport.bilibili.com/login");
                                conn.setRequestProperty("Accept", "application/json");
                                conn.setRequestProperty("Cookie", jar.toHeader());

                                StringBuilder respSb = new StringBuilder();
                                try (BufferedReader br = new BufferedReader(
                                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                    String line; while ((line = br.readLine()) != null) respSb.append(line);
                                }
                                String resp = respSb.toString();
                                jar.parseFromHeaders(conn.getHeaderFields());

                                int outerCode = -1, innerCode = -1;
                                Matcher codeMatcher = Pattern.compile("\"code\":(-?[0-9]+)").matcher(resp);
                                while (codeMatcher.find()) {
                                    if (outerCode == -1) outerCode = Integer.parseInt(codeMatcher.group(1));
                                    else innerCode = Integer.parseInt(codeMatcher.group(1));
                                }

                                System.out.println("[poll #" + i + "] outer=" + outerCode + " inner=" + innerCode);

                                if (outerCode == 0 && innerCode == 0) {
                                    // 登录成功，SSO 跳转拿完整 cookie
                                    String ssoUrl = extract(resp, "\"url\":\"([^\"]+)\"");
                                    if (ssoUrl != null) {
                                        ssoUrl = ssoUrl.replace("\\/", "/");
                                        try {
                                            HttpURLConnection ssoConn = (HttpURLConnection) new URL(ssoUrl).openConnection();
                                            ssoConn.setRequestMethod("GET");
                                            ssoConn.setConnectTimeout(8_000);
                                            ssoConn.setReadTimeout(20_000);
                                            ssoConn.setRequestProperty("User-Agent", UA);
                                            ssoConn.setRequestProperty("Cookie", jar.toHeader());
                                            ssoConn.setInstanceFollowRedirects(false);
                                            ssoConn.connect();
                                            jar.parseFromHeaders(ssoConn.getHeaderFields());
                                            ssoConn.disconnect();
                                        } catch (Exception e) { /* ignore */ }
                                    }
                                    String finalCookie = jar.toHeader();
                                    System.out.println("[login] ★ 登录成功！cookie=" + finalCookie);
                                    succeed(finalCookie);
                                    return;
                                } else if (innerCode == 86038) {
                                    // 二维码过期：给刷新入口，别让用户干等
                                    status("二维码已过期", C_RED, false);
                                    showRefresh(true);
                                    return;
                                } else if (innerCode == 86090) {
                                    status("已扫码，请在手机上确认", C_BLUE, false);
                                } else if (innerCode == 86101) {
                                    status("等待扫码...", C_SUB, false);
                                }
                            }
                            if (!token.get()) status("二维码已失效", C_RED, false);
                        } catch (Exception e) {
                            System.out.println("[poll] 异常: " + e.getMessage());
                            if (!token.get()) {
                                status("网络异常，请重试", C_RED, false);
                                showRefresh(true);
                            }
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    });
                    pollThread.setDaemon(true);
                    pollThread.start();

                } catch (Exception ex) {
                    status("网络错误", C_RED, false);
                    showRefresh(true);
                    ex.printStackTrace();
                } finally {
                    if (genConn != null) genConn.disconnect();
                }
            }).start();
        }

        /** 成功：先给绿色对勾反馈，再关窗并回调 */
        private void succeed(String cookie) {
            status("登录成功", C_GREEN, false);
            Platform.runLater(() -> {
                successOverlay.setVisible(true);
                successOverlay.setOpacity(0);
                Timeline fade = new Timeline(
                        new KeyFrame(Duration.ZERO, new KeyValue(successOverlay.opacityProperty(), 0)),
                        new KeyFrame(Duration.millis(180), new KeyValue(successOverlay.opacityProperty(), 1, Interpolator.EASE_OUT)));
                fade.play();
            });
            Timeline close = new Timeline(new KeyFrame(Duration.millis(750), e -> {
                win.close();
                if (onSuccess != null) onSuccess.onCookie(cookie);
            }));
            close.play();
        }
    }

    public static void openLogin(Stage owner, LoginCallback onSuccess) {
        Stage win = new Stage(StageStyle.TRANSPARENT);
        win.initOwner(owner);
        win.initModality(Modality.APPLICATION_MODAL);
        win.setTitle("B站扫码登录");

        // ===== 根容器：白色圆角卡片 + 柔和阴影 =====
        VBox root = new VBox();
        root.setAlignment(Pos.TOP_CENTER);
        root.setPrefWidth(320);
        root.setStyle(
                "-fx-background-color: white;"
                        + "-fx-background-radius: 16;"
                        + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.16), 28, 0, 0, 8);"
        );

        // ===== 头部：淡蓝填充区分区块，不再用描边线 =====
        HBox topBar = new HBox(10);
        topBar.setPadding(new Insets(14, 12, 12, 16));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color:#EFF6FF;-fx-background-radius:16 16 0 0;");

        // B 站官方图标；加载失败才回退到字母 B
        final String LOGO_URL = "https://static.hdslb.com/images/favicon-2_oB5q4w.ico";
        Label badge = new Label("B");
        badge.setPrefSize(26, 26);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-background-color:#FB7299;-fx-background-radius:8;"
                + "-fx-text-fill:white;-fx-font-size:14px;-fx-font-weight:bold;");
        ImageView logoView = new ImageView();
        logoView.setFitWidth(26);
        logoView.setFitHeight(26);
        logoView.setPreserveRatio(true);
        StackPane logoBox = new StackPane(badge, logoView);
        logoBox.setPrefSize(26, 26);
        logoBox.setMaxSize(26, 26);
        // 后台加载：成功显示官方图标，失败保留字母，失败也不影响登录流程
        // 注意：Image 的 7 参重载只存在于 InputStream 那一族，URL 版最多 6 参（errorStream 默认 false）
        Image logo = new Image(LOGO_URL, 26, 26, true, true, true);
        logo.progressProperty().addListener((o, ov, nv) -> {
            if (nv.doubleValue() >= 1.0 && !logo.isError()) {
                logoView.setImage(logo);
                badge.setVisible(false);
            }
        });
        logo.errorProperty().addListener((o, ov, nv) -> {
            if (nv) System.out.println("[login] 官方图标加载失败，使用字母回退");
        });

        VBox titleBox = new VBox(1);
        Label title = new Label("扫码登录 B 站");
        title.setStyle("-fx-text-fill:#1F2329;-fx-font-size:15px;-fx-font-weight:bold;");
        Label sub = new Label("使用 B 站 App 扫描二维码");
        sub.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:11px;");
        titleBox.getChildren().addAll(title, sub);
        titleBox.setMouseTransparent(true);
        badge.setMouseTransparent(true);
        logoView.setMouseTransparent(true);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Button closeBtn = new Button("x");
        closeBtn.setPrefSize(26, 26);
        closeBtn.setMaxSize(26, 26);
        closeBtn.setStyle("-fx-background-color:transparent;-fx-text-fill:#8A9099;"
                + "-fx-font-size:15px;-fx-font-weight:bold;-fx-cursor:hand;-fx-background-radius:13;");
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color:#F2F3F5;-fx-text-fill:#1F2329;"
                + "-fx-font-size:15px;-fx-font-weight:bold;-fx-cursor:hand;-fx-background-radius:13;"));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle("-fx-background-color:transparent;-fx-text-fill:#8A9099;"
                + "-fx-font-size:15px;-fx-font-weight:bold;-fx-cursor:hand;-fx-background-radius:13;"));
        closeBtn.setOnAction(e -> win.close());

        topBar.getChildren().addAll(logoBox, titleBox, topSpacer, closeBtn);

        // ===== 二维码区 =====
        StackPane qrFrame = new StackPane();
        qrFrame.setPrefSize(232, 232);
        qrFrame.setPadding(new Insets(14));
        // 淡蓝填充代替描边，观感更干净
        qrFrame.setStyle("-fx-background-color:#F5FAFF;-fx-background-radius:14;");

        ImageView qrView = new ImageView();
        qrView.setFitWidth(200);
        qrView.setFitHeight(200);
        qrView.setSmooth(true);

        ProgressIndicator qrSpinner = new ProgressIndicator();
        qrSpinner.setMaxSize(26, 26);
        qrSpinner.setVisible(false);
        qrSpinner.setStyle("-fx-progress-color:" + C_BLUE + ";");

        qrFrame.getChildren().addAll(qrView, qrSpinner);

        // 成功对勾：用图形绘制，不依赖字体是否有 ✔ 字形
        StackPane successOverlay = new StackPane();
        successOverlay.setPrefSize(200, 200);
        successOverlay.setStyle("-fx-background-color:rgba(255,255,255,0.95);-fx-background-radius:10;");
        successOverlay.setVisible(false);
        Path check = new Path();
        check.setStroke(Color.web(C_GREEN));
        check.setStrokeWidth(7);
        check.setStrokeLineCap(StrokeLineCap.ROUND);
        check.setStrokeLineJoin(StrokeLineJoin.ROUND);
        check.getElements().addAll(new MoveTo(66, 102), new LineTo(88, 126), new LineTo(138, 70));
        StackPane checkWrap = new StackPane(check);
        checkWrap.setAlignment(Pos.CENTER);
        successOverlay.getChildren().add(checkWrap);
        qrFrame.getChildren().add(successOverlay);

        // ===== 状态行 =====
        HBox statusRow = new HBox(7);
        statusRow.setAlignment(Pos.CENTER);
        statusRow.setPadding(new Insets(14, 0, 0, 0));
        ProgressIndicator statusSpinner = new ProgressIndicator();
        statusSpinner.setMaxSize(13, 13);
        statusSpinner.setStyle("-fx-progress-color:" + C_BLUE + ";");
        Label tip = new Label("正在获取二维码...");
        tip.setStyle("-fx-text-fill:" + C_SUB + ";-fx-font-size:12px;");
        statusRow.getChildren().addAll(statusSpinner, tip);

        // ===== 刷新按钮（仅在失败/过期时出现）=====
        Button refreshBtn = new Button("刷新二维码");
        refreshBtn.setStyle("-fx-background-color:#F2F3F5;-fx-text-fill:" + C_TEXT_DARK + ";"
                + "-fx-font-size:12px;-fx-padding:6 16;-fx-cursor:hand;-fx-background-radius:6;");
        refreshBtn.setVisible(false);
        StackPane refreshWrap = new StackPane(refreshBtn);
        refreshWrap.setPadding(new Insets(10, 0, 18, 0));
        refreshWrap.setAlignment(Pos.CENTER);

        // ===== 正文：二维码 + 状态行 + 刷新按钮 =====
        // 二维码/转圈/状态行设为鼠标透明：它们会吃掉按下事件，导致从这些区域拖不动窗口
        qrFrame.setMouseTransparent(true);
        statusRow.setMouseTransparent(true);

        VBox body = new VBox(qrFrame, statusRow, refreshWrap);
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(20, 0, 0, 0));

        root.getChildren().addAll(topBar, body);

        // 透明无边框窗口没有标题栏，自己实现拖动。两个关键点：
        // 1) 用屏幕绝对坐标 getScreenX/Y 求差值。若用场景坐标，窗口移动会反过来改变场景原点，
        //    形成反馈回路，表现为抖动或速度翻倍。
        // 2) 拖动过程中不要用 getButton() 判断（此时常为 NONE，会导致拖到一半停住），改用 dragging 标记。
        final double[] pressScreen = new double[2];
        final double[] pressWin = new double[2];
        final boolean[] dragging = new boolean[1];
        root.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragging[0] = true;
            pressScreen[0] = e.getScreenX();
            pressScreen[1] = e.getScreenY();
            pressWin[0] = win.getX();
            pressWin[1] = win.getY();
        });
        root.setOnMouseDragged(e -> {
            if (!dragging[0]) return;
            win.setX(pressWin[0] + (e.getScreenX() - pressScreen[0]));
            win.setY(pressWin[1] + (e.getScreenY() - pressScreen[1]));
        });
        root.setOnMouseReleased(e -> dragging[0] = false);

        final LoginUi ui = new LoginUi(win, qrView, tip, qrSpinner, statusSpinner,
                refreshBtn, successOverlay, onSuccess);
        refreshBtn.setOnAction(e -> ui.start());

        Scene scene = new Scene(root, Color.TRANSPARENT);
        win.setScene(scene);

        // 所有关闭路径都停轮询：× 按钮、close()、系统关闭、主窗口关闭
        win.setOnCloseRequest(e -> ui.stopped.set(true));
        win.setOnHidden(e -> ui.stopped.set(true));
        // 主窗口关闭时连带关闭登录窗，避免模态窗残留导致程序不退出
        owner.setOnHiding(e -> {
            ui.stopped.set(true);
            if (win.isShowing()) win.close();
        });
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                ui.stopped.set(true);
                win.close();
            }
        });

        ui.start();

        // 按内容自适应大小并居中于主窗口，不再铺满整窗
        win.sizeToScene();
        double w = win.getWidth(), h = win.getHeight();
        double ox = owner.getX(), oy = owner.getY();
        double ow = owner.getWidth() > 0 ? owner.getWidth() : 775;
        double oh = owner.getHeight() > 0 ? owner.getHeight() : 435;
        win.setX(ox + (ow - w) / 2);
        win.setY(oy + (oh - h) / 2);

        win.showAndWait();
    }

    private static String extract(String t, String r) {
        Matcher m = Pattern.compile(r).matcher(t);
        return m.find() ? m.group(1) : null;
    }

    public interface LoginCallback { void onCookie(String cookie); }
}

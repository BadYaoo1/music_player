package com.pclplayer;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * PCL2 风格播放器 Controller
 * - 用 FXML 声明布局，这里只负责数据填充、动画、窗控
 */
public class PlayerController implements Initializable {

    @FXML private VBox songList;
    @FXML private ProgressBar progressBar;
    @FXML private Slider volumeSlider;

    private static final String[][] SONGS = {
            {"告白气球", "周杰伦", "3:35"},
            {"七里香",   "周杰伦", "4:59"},
            {"晴天",     "周杰伦", "4:29"},
            {"稻香",     "周杰伦", "3:43"},
            {"夜曲",     "周杰伦", "3:46"},
            {"枫",       "周杰伦", "4:20"},
            {"青花瓷",   "周杰伦", "4:02"},
    };

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 填充歌曲卡片
        for (String[] s : SONGS) {
            songList.getChildren().add(createSongCard(s[0], s[1], s[2]));
        }
        // 错落入场动画
        for (int i = 0; i < songList.getChildren().size(); i++) {
            Node card = songList.getChildren().get(i);
            card.setOpacity(0);
            card.setTranslateY(18);
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(card.opacityProperty(), 0),
                            new KeyValue(card.translateYProperty(), 18)),
                    new KeyFrame(Duration.millis(380),
                            new KeyValue(card.opacityProperty(), 1, Interpolator.EASE_OUT),
                            new KeyValue(card.translateYProperty(), 0, Interpolator.EASE_OUT))
            );
            tl.setDelay(Duration.millis(i * 55));
            tl.play();
        }
    }

    /** 构造一张歌曲卡片（封面占位 + 标题/歌手 + 时长） */
    private HBox createSongCard(String title, String artist, String duration) {
        HBox card = new HBox(14);
        card.getStyleClass().add("songCard");
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Rectangle cover = new Rectangle(44, 44);
        cover.setFill(Color.web("#EEF4FF"));
        cover.setArcWidth(8); cover.setArcHeight(8);

        VBox info = new VBox(3);
        Label t = new Label(title); t.getStyleClass().add("title");
        Label a = new Label(artist); a.getStyleClass().add("artist");
        info.getChildren().addAll(t, a);

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        Label dur = new Label(duration);
        dur.setStyle("-fx-text-fill: #6B7280; -fx-font-size: 12px;");

        card.getChildren().addAll(cover, info, spacer, dur);
        return card;
    }

    /* ===== 窗控（自定义按钮） ===== */
    @FXML private void onMinimize() {
        Stage stage = (Stage) songList.getScene().getWindow();
        stage.setIconified(true);
    }
    @FXML private void onClose() {
        Stage stage = (Stage) songList.getScene().getWindow();
        stage.close();
    }
}

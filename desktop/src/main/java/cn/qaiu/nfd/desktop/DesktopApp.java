package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.core.DownloadRequest;
import cn.qaiu.nfd.core.LinkRefresher;
import cn.qaiu.nfd.share.AuthStore;
import cn.qaiu.nfd.share.ParseHistory;
import cn.qaiu.nfd.share.ParserRuntime;
import cn.qaiu.nfd.share.ShareErrors;
import cn.qaiu.nfd.share.ShareSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class DesktopApp extends Application {

    private final AtomicInteger threads = new AtomicInteger();
    private final ExecutorService io = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "nfd-parse-" + threads.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Settings settings = Settings.load();
    private final AuthStore auths = AuthStore.load(AppPaths.dataDir().resolve("auths.json"));
    private final ParseHistory history = new ParseHistory(AppPaths.dataDir().resolve("history.json"));
    private DownloadManager manager;
    private BrowsePane browse;
    private TasksPane tasks;
    private TabPane navigation;
    private Tab parseTab;
    private Ui.StatusBar status;
    private String lastClipboard = "";

    @Override
    public void init() {
        ShareSession.useCredentials(auths::get);
        manager = new DownloadManager(new DownloadManager.Options()
                .stateDir(AppPaths.taskStateDir())
                .maxConcurrent(settings.maxConcurrent())
                .connections(settings.connections())
                .refresher(refresher())
                .resumeOnStart(true));
        io.submit(ParserRuntime::start);
    }

    @Override
    public void start(Stage stage) {
        status = new Ui.StatusBar("v" + Main.version() + "  ·  parser " + ParserRuntime.parserVersion());
        browse = new BrowsePane(io, this::enqueue, settings::downloadDir, status, history);
        tasks = new TasksPane(manager, settings::downloadDir, status);

        Label title = Ui.label("NFD 网盘直链", "title");
        HBox header = Ui.row(12, title, Ui.spacer(), Ui.label("本机解析", "runtime-label"));
        header.getStyleClass().add("header");

        parseTab = new Tab("解析", browse);
        Tab downloadTab = new Tab("下载", tasks);
        HistoryPane historyPane = new HistoryPane(history, item -> {
            navigation.getSelectionModel().select(parseTab);
            browse.reparse(item.link(), item.password());
        }, status);
        Tab historyTab = new Tab("历史", historyPane);
        historyTab.setOnSelectionChanged(event -> {
            if (historyTab.isSelected()) {
                historyPane.refresh();
            }
        });
        Tab settingsTab = new Tab("设置", new SettingsPane(settings, manager, auths, status));
        navigation = new TabPane(parseTab, downloadTab, historyTab, settingsTab);
        navigation.setSide(Side.BOTTOM);
        navigation.setTabMinWidth(70);
        navigation.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        navigation.getStyleClass().add("main-navigation");
        BorderPane.setMargin(navigation, new Insets(0, 16, 0, 16));
        BorderPane.setMargin(status, new Insets(4, 16, 12, 16));

        BorderPane root = new BorderPane(navigation, header, null, status, null);
        root.getStyleClass().add("app");
        List<String> installedFonts = Font.getFamilies();
        List.of("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
                        "Noto Sans SC", "WenQuanYi Micro Hei").stream()
                .filter(installedFonts::contains)
                .findFirst()
                .ifPresent(family -> root.setStyle("-fx-font-family: \"" + family + "\";"));
        double[] size = settings.window();
        Scene scene = new Scene(root, size[0], size[1]);
        scene.getStylesheets().add(DesktopApp.class.getResource("/app.css").toExternalForm());
        installShortcuts(scene);
        installDragAndDrop(scene);

        stage.setTitle("NFD 网盘直链");
        stage.setMinWidth(780);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.focusedProperty().addListener((observable, was, focused) -> {
            if (focused) {
                prefillFromClipboard();
            }
        });
        stage.setOnCloseRequest(event -> {
            if (!confirmExit(stage)) {
                event.consume();
                return;
            }
            settings.window(stage.getWidth(), stage.getHeight());
            settings.lastLink(browse.linkText());
        });
        stage.show();

        String last = settings.lastLink();
        List<String> args = getParameters().getUnnamed();
        String initial = args.stream().filter(arg -> arg.startsWith("http")).findFirst().orElse(last);
        browse.setLink(initial);
        if (!args.isEmpty() && initial.startsWith("http")) {
            browse.parse();
        }
        browse.focusLink();

        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(500), event -> {
            tasks.sync();
            long active = tasks.activeCount();
            downloadTab.setText(active > 0 ? "下载 (" + active + ")" : "下载");
            if (historyTab.isSelected()) {
                historyPane.refresh();
            }
        }));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    private LinkRefresher refresher() {
        return task -> {
            try {
                ShareSession.DirectLink link = ShareSession.resolveSource(task.source());
                return new LinkRefresher.ResolvedLink(link.url(), link.headers());
            } catch (RuntimeException ex) {
                throw new IllegalStateException(ShareErrors.friendly(ex), ex);
            }
        };
    }

    private void enqueue(List<DownloadRequest> requests) {
        for (DownloadRequest request : requests) {
            manager.enqueue(request);
        }
        tasks.sync();
    }

    private void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), () -> {
            navigation.getSelectionModel().select(parseTab);
            browse.focusLink();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> {
            navigation.getSelectionModel().select(parseTab);
            browse.parse();
        });
    }

    private void installDragAndDrop(Scene scene) {
        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasString() || event.getDragboard().hasUrl()) {
                event.acceptTransferModes(TransferMode.COPY, TransferMode.LINK);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            String text = event.getDragboard().hasUrl() ? event.getDragboard().getUrl() : event.getDragboard().getString();
            boolean ok = text != null && ShareSession.extractUrl(text) != null;
            if (ok) {
                navigation.getSelectionModel().select(parseTab);
                browse.setLink(text.trim());
                browse.parse();
            }
            event.setDropCompleted(ok);
            event.consume();
        });
    }

    /** Fills an empty link box from the clipboard once per copied link; never parses on its own. */
    private void prefillFromClipboard() {
        if (!browse.linkText().isBlank()) {
            return;
        }
        String text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.length() > 2000 || text.equals(lastClipboard)) {
            return;
        }
        lastClipboard = text;
        String url = ShareSession.extractUrl(text);
        if (url == null || !url.regionMatches(true, 0, "http", 0, 4)) {
            return;
        }
        try {
            ShareSession.open(url, null);
        } catch (RuntimeException ex) {
            return;
        }
        browse.setLink(text.trim());
        status.info("已从剪贴板填入分享链接，按回车解析");
    }

    private boolean confirmExit(Stage stage) {
        long active = tasks.activeCount();
        if (active == 0) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "还有 " + active + " 个下载未完成。退出后进度会保留，下次打开时自动继续。", Ui.OK, Ui.CANCEL);
        alert.initOwner(stage);
        alert.setTitle("退出");
        alert.setHeaderText("确定退出？");
        return alert.showAndWait().orElse(Ui.CANCEL) == Ui.OK;
    }

    @Override
    public void stop() {
        manager.close();
        io.shutdownNow();
        ParserRuntime.stop();
        // Vert.x and parser threads are not all daemons.
        Platform.exit();
        System.exit(0);
    }
}

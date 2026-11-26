package cn.qaiu.nfd.desktop;

import cn.qaiu.WebClientVertxInit;
import cn.qaiu.nfd.desktop.download.DownloadTask;
import cn.qaiu.nfd.desktop.download.RangeDownloadEngine;
import cn.qaiu.nfd.share.ShareSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopApp extends Application {

    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "nfd-io");
        thread.setDaemon(true);
        return thread;
    });
    private final RangeDownloadEngine engine = new RangeDownloadEngine(defaultDirectory());
    private final ObservableList<ShareSession.Entry> files = FXCollections.observableArrayList();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Deque<Crumb> crumbs = new ArrayDeque<>();
    private ShareSession session;
    private Label status;
    private Label panLabel;
    private Label dirLabel;
    private Button backButton;

    @Override
    public void start(Stage stage) {
        TextField linkField = new TextField();
        linkField.setPromptText("粘贴网盘分享链接");
        linkField.setTooltip(new Tooltip("在本机调用解析库，不会把链接提交到托管服务"));
        HBox.setHgrow(linkField, Priority.ALWAYS);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("提取码，可空");
        passwordField.setPrefWidth(140);

        Button parseButton = new Button("解析");
        parseButton.getStyleClass().add("accent");
        Button directButton = new Button("解析直链");
        Button chooseDir = new Button("下载目录");
        dirLabel = new Label(engine.downloadDirectory().toString());
        dirLabel.getStyleClass().add("muted");
        dirLabel.setMaxWidth(280);

        parseButton.setOnAction(event -> parse(linkField.getText(), passwordField.getText(), null));
        directButton.setOnAction(event -> parseDirect(linkField.getText(), passwordField.getText()));
        linkField.setOnAction(event -> parse(linkField.getText(), passwordField.getText(), null));
        chooseDir.setOnAction(event -> chooseDirectory(stage));

        HBox toolbar = new HBox(8, linkField, passwordField, parseButton, directButton, chooseDir, dirLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar");

        Label title = new Label("网盘直链下载");
        title.getStyleClass().add("title");
        Label subtitle = new Label("粘贴分享链接，解析出文件后下载。进度、暂停和继续都在本机完成。");
        subtitle.getStyleClass().add("muted");
        panLabel = new Label("");
        panLabel.getStyleClass().add("pan");
        VBox header = new VBox(4, title, subtitle, panLabel, toolbar);
        header.getStyleClass().add("header");

        TableView<ShareSession.Entry> table = fileTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        backButton = new Button("返回上一级");
        backButton.setDisable(true);
        backButton.setOnAction(event -> goBack());
        Button downloadButton = new Button("下载所选");
        downloadButton.getStyleClass().add("accent");
        downloadButton.setOnAction(event -> {
            ShareSession.Entry entry = table.getSelectionModel().getSelectedItem();
            if (entry != null) {
                openOrDownload(entry);
            }
        });
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                ShareSession.Entry entry = table.getSelectionModel().getSelectedItem();
                if (entry != null) {
                    openOrDownload(entry);
                }
            }
        });
        HBox fileActions = new HBox(8, backButton, downloadButton);
        fileActions.setAlignment(Pos.CENTER_LEFT);
        VBox filesCard = card("文件", fileActions, table);

        TableView<Row> tasks = taskTable();
        VBox.setVgrow(tasks, Priority.ALWAYS);
        VBox taskCard = card("下载", null, tasks);

        HBox body = new HBox(12, filesCard, taskCard);
        body.setPadding(new Insets(0, 16, 12, 16));
        HBox.setHgrow(filesCard, Priority.ALWAYS);
        HBox.setHgrow(taskCard, Priority.ALWAYS);

        status = new Label("准备就绪");
        status.getStyleClass().add("status");
        BorderPane.setMargin(status, new Insets(0, 16, 12, 16));

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(body);
        root.setBottom(status);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 1100, 680);
        scene.getStylesheets().add(DesktopApp.class.getResource("/app.css").toExternalForm());
        stage.setTitle("NFD Client");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> shutdown());
        stage.show();

        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(400), event -> refreshTasks()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    private void parse(String link, String password, String dirId) {
        setBusy("正在解析…");
        io.submit(() -> {
            try {
                if (session == null || dirId == null) {
                    session = ShareSession.open(link, password);
                    crumbs.clear();
                    crumbs.add(new Crumb("根目录", null));
                }
                ShareSession.Listing listing = session.list(dirId);
                Platform.runLater(() -> showListing(listing));
            } catch (RuntimeException ex) {
                Platform.runLater(() -> setError(message(ex)));
            }
        });
    }

    private void parseDirect(String link, String password) {
        setBusy("正在解析直链…");
        io.submit(() -> {
            try {
                if (session == null) {
                    session = ShareSession.open(link, password);
                }
                ShareSession.DirectLink direct = session.parseDirect();
                Platform.runLater(() -> {
                    enqueue(direct);
                    setStatus("已创建下载：" + direct.fileName());
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> setError(message(ex)));
            }
        });
    }

    private void openOrDownload(ShareSession.Entry entry) {
        if (session == null) {
            return;
        }
        if (entry.folder()) {
            setBusy("正在打开文件夹…");
            io.submit(() -> {
                try {
                    ShareSession.Listing listing = session.openFolder(entry);
                    Platform.runLater(() -> {
                        crumbs.add(new Crumb(entry.fileName(), entry.fileId()));
                        showListing(listing);
                    });
                } catch (RuntimeException ex) {
                    Platform.runLater(() -> setError(message(ex)));
                }
            });
            return;
        }
        setBusy("正在获取下载地址…");
        io.submit(() -> {
            try {
                ShareSession.DirectLink direct = session.resolve(entry);
                Platform.runLater(() -> {
                    enqueue(direct);
                    setStatus("已创建下载：" + direct.fileName());
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> setError(message(ex)));
            }
        });
    }

    private void goBack() {
        if (session == null || crumbs.size() <= 1) {
            return;
        }
        crumbs.removeLast();
        Crumb current = crumbs.peekLast();
        setBusy("正在返回…");
        io.submit(() -> {
            try {
                ShareSession.Listing listing = session.list(current.dirId());
                Platform.runLater(() -> showListing(listing));
            } catch (RuntimeException ex) {
                Platform.runLater(() -> setError(message(ex)));
            }
        });
    }

    private void showListing(ShareSession.Listing listing) {
        files.setAll(listing.entries());
        panLabel.setText(listing.panName() + "  ·  " + listing.type());
        backButton.setDisable(crumbs.size() <= 1);
        String where = crumbs.isEmpty() ? "根目录" : crumbs.peekLast().label();
        setStatus(where + "，" + listing.entries().size() + " 项");
    }

    private void enqueue(ShareSession.DirectLink direct) {
        String name = direct.fileName() == null || direct.fileName().isBlank() ? "download" : direct.fileName();
        engine.enqueue(name, direct.url(), direct.headers());
        refreshTasks();
    }

    private void refreshTasks() {
        List<DownloadTask> snapshots = engine.tasks();
        if (rows.size() != snapshots.size()) {
            List<Row> next = new ArrayList<>();
            for (DownloadTask task : snapshots) {
                next.add(rowFor(task));
            }
            rows.setAll(next);
            return;
        }
        for (int i = 0; i < snapshots.size(); i++) {
            rows.get(i).apply(snapshots.get(i));
        }
    }

    private Row rowFor(DownloadTask task) {
        Row row = new Row(task.id());
        row.apply(task);
        return row;
    }

    private void toggle(Row row) {
        DownloadTask task = engine.tasks().stream().filter(item -> item.id().equals(row.id)).findFirst().orElse(null);
        if (task == null) {
            return;
        }
        if (task.status() == DownloadTask.Status.RUNNING || task.status() == DownloadTask.Status.QUEUED) {
            engine.pause(task.id());
        } else if (task.status() == DownloadTask.Status.PAUSED || task.status() == DownloadTask.Status.FAILED) {
            engine.resume(task.id());
        }
        refreshTasks();
    }

    private void chooseDirectory(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择下载目录");
        File current = engine.downloadDirectory().toFile();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            engine.setDownloadDirectory(selected.toPath());
            dirLabel.setText(selected.getAbsolutePath());
        }
    }

    private TableView<ShareSession.Entry> fileTable() {
        TableView<ShareSession.Entry> table = new TableView<>(files);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("解析后，文件会显示在这里"));
        TableColumn<ShareSession.Entry, String> name = new TableColumn<>("名称");
        name.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().fileName()));
        TableColumn<ShareSession.Entry, String> kind = new TableColumn<>("类型");
        kind.setPrefWidth(90);
        kind.setMaxWidth(120);
        kind.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().folder()
                ? "文件夹"
                : (cell.getValue().fileType() == null || cell.getValue().fileType().isBlank()
                ? "文件" : cell.getValue().fileType())));
        TableColumn<ShareSession.Entry, String> size = new TableColumn<>("大小");
        size.setPrefWidth(100);
        size.setMaxWidth(140);
        size.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().sizeText()));
        table.getColumns().add(name);
        table.getColumns().add(kind);
        table.getColumns().add(size);
        return table;
    }

    private TableView<Row> taskTable() {
        TableView<Row> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("还没有下载"));
        TableColumn<Row, String> name = new TableColumn<>("文件");
        name.setCellValueFactory(cell -> cell.getValue().name);
        TableColumn<Row, String> detail = new TableColumn<>("进度");
        detail.setPrefWidth(220);
        detail.setCellValueFactory(cell -> cell.getValue().detail);
        TableColumn<Row, String> action = new TableColumn<>("");
        action.setPrefWidth(90);
        action.setMaxWidth(110);
        action.setCellValueFactory(cell -> cell.getValue().action);
        action.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            private final Button button = new Button();

            {
                button.setOnAction(event -> {
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        toggle(row);
                    }
                });
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || value.isBlank()) {
                    setGraphic(null);
                } else {
                    button.setText(value);
                    setGraphic(button);
                }
            }
        });
        table.getColumns().add(name);
        table.getColumns().add(detail);
        table.getColumns().add(action);
        return table;
    }

    private VBox card(String title, Region actions, Region body) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        HBox head = new HBox(8, label);
        head.setAlignment(Pos.CENTER_LEFT);
        if (actions != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            head.getChildren().add(spacer);
            head.getChildren().add(actions);
        }
        VBox card = new VBox(8, head, body);
        card.getStyleClass().add("card");
        VBox.setVgrow(body, Priority.ALWAYS);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void setBusy(String text) {
        status.setText(text);
        status.getStyleClass().remove("error");
    }

    private void setStatus(String text) {
        status.setText(text);
        status.getStyleClass().remove("error");
    }

    private void setError(String text) {
        status.setText(text);
        if (!status.getStyleClass().contains("error")) {
            status.getStyleClass().add("error");
        }
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause().getMessage() != null) {
            current = current.getCause();
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? "解析失败" : text;
    }

    private static Path defaultDirectory() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Path.of(home, "Downloads");
        if (java.nio.file.Files.isDirectory(downloads)) {
            return downloads.resolve("nfd");
        }
        return Path.of(home, "nfd-downloads");
    }

    private void shutdown() {
        engine.close();
        io.shutdownNow();
        try {
            WebClientVertxInit.get().close();
        } catch (Exception ignored) {
            // Vert.x may not have been created when the window closes immediately.
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record Crumb(String label, String dirId) {
    }

    public static final class Row {
        final String id;
        final SimpleStringProperty name = new SimpleStringProperty();
        final SimpleStringProperty detail = new SimpleStringProperty();
        final SimpleStringProperty action = new SimpleStringProperty();

        Row(String id) {
            this.id = id;
        }

        void apply(DownloadTask task) {
            name.set(task.fileName());
            String size = RangeDownloadEngine.formatSize(task.downloaded());
            String total = task.total() > 0 ? RangeDownloadEngine.formatSize(task.total()) : "未知大小";
            String state = switch (task.status()) {
                case QUEUED -> "排队";
                case RUNNING -> "下载中";
                case PAUSED -> "已暂停";
                case COMPLETED -> "完成";
                case FAILED -> "失败";
            };
            String extra = task.message() == null || task.message().isBlank() ? "" : "  " + task.message();
            detail.set(state + "  " + size + " / " + total + extra);
            action.set(switch (task.status()) {
                case RUNNING, QUEUED -> "暂停";
                case PAUSED, FAILED -> "继续";
                case COMPLETED -> "";
            });
        }
    }
}

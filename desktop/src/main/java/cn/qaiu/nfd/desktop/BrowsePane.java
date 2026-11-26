package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadRequest;
import cn.qaiu.nfd.share.ShareErrors;
import cn.qaiu.nfd.share.ShareSession;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Link input plus the share's file browser. */
final class BrowsePane extends VBox {

    private final ExecutorService io;
    private final Consumer<List<DownloadRequest>> enqueue;
    private final Supplier<Path> downloadDir;
    private final Ui.StatusBar status;

    private final TextField linkField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Button parseButton;
    private final Button directButton;
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label panLabel = Ui.label("", "pan");
    private final HBox crumbs = Ui.row(2);
    private final ObservableList<ShareSession.Entry> entries = FXCollections.observableArrayList();
    private final TableView<ShareSession.Entry> table = new TableView<>(entries);
    private final Button upButton;
    private final Button selectedButton;
    private final Button allButton;

    /** Every navigation bumps the generation, so a slow response for an older request is dropped. */
    private int generation;
    private ShareSession session;
    private String link = "";
    private String password = "";
    private final List<Level> levels = new ArrayList<>();

    BrowsePane(ExecutorService io, Consumer<List<DownloadRequest>> enqueue, Supplier<Path> downloadDir,
               Ui.StatusBar status) {
        super(10);
        this.io = io;
        this.enqueue = enqueue;
        this.downloadDir = downloadDir;
        this.status = status;

        linkField.setPromptText("粘贴分享链接或整段分享文本，回车解析");
        linkField.setTooltip(new Tooltip("解析在本机完成，链接不会提交给任何托管服务"));
        HBox.setHgrow(linkField, Priority.ALWAYS);
        linkField.setOnAction(event -> parse());
        passwordField.setPromptText("提取码（可空）");
        passwordField.setPrefWidth(130);
        passwordField.setOnAction(event -> parse());
        parseButton = Ui.accent(Ui.button("解析", "列出分享里的文件", this::parse));
        directButton = Ui.button("直接下载", "不列目录，直接解析并下载单文件分享", this::downloadDirect);
        busy.setPrefSize(18, 18);
        busy.setVisible(false);
        HBox input = Ui.row(8, linkField, passwordField, parseButton, directButton, busy);

        upButton = Ui.button("上一级", null, this::up);
        upButton.setDisable(true);
        Button refresh = Ui.button("刷新", "重新获取当前目录", this::refresh);
        HBox nav = Ui.row(8, upButton, refresh, crumbs, Ui.spacer(), panLabel);

        configureTable();
        selectedButton = Ui.accent(Ui.button("下载所选", "Ctrl/Shift 可多选", this::downloadSelected));
        allButton = Ui.button("全部下载", "下载当前目录里的全部文件（不含子文件夹）", this::downloadAll);
        Label hint = Ui.label("双击文件夹进入，双击文件下载", "muted");
        HBox actions = Ui.row(8, hint, Ui.spacer(), allButton, selectedButton);
        updateButtons();

        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(input, nav, table, actions);
        getStyleClass().add("card");
    }

    void setLink(String text) {
        linkField.setText(text);
    }

    String linkText() {
        return linkField.getText();
    }

    void focusLink() {
        linkField.requestFocus();
    }

    void parse() {
        String raw = linkField.getText() == null ? "" : linkField.getText().trim();
        String url = ShareSession.extractUrl(raw);
        if (url == null || url.isBlank()) {
            status.error("请先粘贴分享链接");
            linkField.requestFocus();
            return;
        }
        String pwd = passwordField.getText() == null ? "" : passwordField.getText().trim();
        if (pwd.isEmpty()) {
            pwd = ShareSession.extractPassword(raw);
            passwordField.setText(pwd);
        }
        String finalPwd = pwd;
        run("正在解析…", () -> {
            ShareSession opened = ShareSession.open(url, finalPwd);
            ShareSession.Listing listing = opened.list(null);
            return () -> {
                session = opened;
                link = url;
                password = finalPwd;
                levels.clear();
                levels.add(new Level("全部文件", listing));
                show();
            };
        });
    }

    private void open(ShareSession.Entry folder) {
        ShareSession current = session;
        run("正在打开 " + folder.fileName() + "…", () -> {
            ShareSession.Listing listing = current.openFolder(folder);
            return () -> {
                levels.add(new Level(folder.fileName(), listing, folder));
                show();
            };
        });
    }

    private void refresh() {
        if (session == null || levels.isEmpty()) {
            parse();
            return;
        }
        ShareSession current = session;
        Level level = levels.get(levels.size() - 1);
        run("正在刷新…", () -> {
            ShareSession.Listing listing = level.folder == null ? current.list(null) : current.openFolder(level.folder);
            return () -> {
                levels.set(levels.size() - 1, new Level(level.label, listing, level.folder));
                show();
            };
        });
    }

    boolean up() {
        if (levels.size() <= 1) {
            return false;
        }
        jumpTo(levels.size() - 2);
        return true;
    }

    private void jumpTo(int index) {
        generation++;
        busy.setVisible(false);
        while (levels.size() > index + 1) {
            levels.remove(levels.size() - 1);
        }
        show();
    }

    private void show() {
        Level level = levels.get(levels.size() - 1);
        entries.setAll(level.listing.entries());
        table.getSelectionModel().clearSelection();
        table.scrollTo(0);
        panLabel.setText(level.listing.panName() == null ? "" : level.listing.panName());
        crumbs.getChildren().clear();
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                crumbs.getChildren().add(Ui.label("›", "muted"));
            }
            int target = i;
            if (i == levels.size() - 1) {
                crumbs.getChildren().add(Ui.label(levels.get(i).label, "crumb-current"));
            } else {
                Hyperlink crumb = new Hyperlink(levels.get(i).label);
                crumb.setOnAction(event -> jumpTo(target));
                crumbs.getChildren().add(crumb);
            }
        }
        upButton.setDisable(levels.size() <= 1);
        long files = level.listing.entries().stream().filter(entry -> !entry.folder()).count();
        long folders = level.listing.entries().size() - files;
        status.info(level.label + "：" + files + " 个文件" + (folders > 0 ? "，" + folders + " 个文件夹" : ""));
        updateButtons();
    }

    private void downloadSelected() {
        download(new ArrayList<>(table.getSelectionModel().getSelectedItems()));
    }

    private void downloadAll() {
        download(new ArrayList<>(entries));
    }

    private void download(List<ShareSession.Entry> picked) {
        if (session == null || picked.isEmpty()) {
            return;
        }
        Path dir = downloadDir.get();
        List<DownloadRequest> requests = new ArrayList<>();
        int skipped = 0;
        for (ShareSession.Entry entry : picked) {
            if (entry.folder()) {
                skipped++;
                continue;
            }
            // The URL is resolved when the task starts, so queued links cannot expire while waiting.
            requests.add(new DownloadRequest(entry.fileName(), "", Map.of(), dir,
                    ShareSession.source(link, password, entry)));
        }
        if (!requests.isEmpty()) {
            enqueue.accept(requests);
        }
        status.info("已加入 " + requests.size() + " 个下载" + (skipped > 0 ? "，跳过 " + skipped + " 个文件夹（请进入后下载）" : ""));
    }

    private void downloadDirect() {
        String raw = linkField.getText() == null ? "" : linkField.getText().trim();
        String url = ShareSession.extractUrl(raw);
        if (url == null || url.isBlank()) {
            status.error("请先粘贴分享链接");
            return;
        }
        String pwd = passwordField.getText() == null || passwordField.getText().isBlank()
                ? ShareSession.extractPassword(raw) : passwordField.getText().trim();
        Path dir = downloadDir.get();
        run("正在解析下载地址…", () -> {
            ShareSession.DirectLink direct = ShareSession.open(url, pwd).parseDirect();
            return () -> {
                enqueue.accept(List.of(new DownloadRequest(direct.fileName(), direct.url(), direct.headers(), dir,
                        ShareSession.source(url, pwd, null))));
                status.info("已加入下载：" + (direct.fileName() == null || direct.fileName().isBlank()
                        ? "（文件名以服务器返回为准）" : direct.fileName()));
            };
        });
    }

    private void run(String message, Work work) {
        int ticket = ++generation;
        status.info(message);
        busy.setVisible(true);
        parseButton.setDisable(true);
        directButton.setDisable(true);
        io.submit(() -> {
            Runnable apply;
            try {
                apply = work.call();
            } catch (Throwable ex) {
                String error = ShareErrors.friendly(ex);
                apply = () -> status.error(error);
            }
            Runnable result = apply;
            Platform.runLater(() -> {
                if (ticket != generation) {
                    return;
                }
                busy.setVisible(false);
                parseButton.setDisable(false);
                directButton.setDisable(false);
                result.run();
            });
        });
    }

    private void configureTable() {
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("粘贴分享链接后点击“解析”，文件会列在这里", "muted"));
        TableColumn<ShareSession.Entry, String> name = new TableColumn<>("名称");
        name.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().fileName() + (cell.getValue().folder() ? "/" : "")));
        name.setPrefWidth(320);
        TableColumn<ShareSession.Entry, String> kind = new TableColumn<>("类型");
        kind.setCellValueFactory(cell -> new SimpleStringProperty(kind(cell.getValue())));
        kind.setPrefWidth(80);
        kind.setMaxWidth(110);
        TableColumn<ShareSession.Entry, String> size = new TableColumn<>("大小");
        size.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().folder() ? "" : cell.getValue().sizeText()));
        size.setPrefWidth(90);
        size.setMaxWidth(130);
        table.getColumns().add(name);
        table.getColumns().add(kind);
        table.getColumns().add(size);

        MenuItem download = new MenuItem("下载");
        download.setOnAction(event -> downloadSelected());
        MenuItem openFolder = new MenuItem("打开文件夹");
        openFolder.setOnAction(event -> {
            ShareSession.Entry entry = table.getSelectionModel().getSelectedItem();
            if (entry != null && entry.folder()) {
                open(entry);
            }
        });
        table.setContextMenu(new ContextMenu(download, openFolder));
        table.setRowFactory(view -> {
            TableRow<ShareSession.Entry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    activate(row.getItem());
                }
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                ShareSession.Entry entry = table.getSelectionModel().getSelectedItem();
                if (entry != null) {
                    activate(entry);
                }
            } else if (event.getCode() == KeyCode.BACK_SPACE) {
                up();
            }
        });
        table.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<ShareSession.Entry>) change -> updateButtons());
    }

    private void activate(ShareSession.Entry entry) {
        if (entry.folder()) {
            open(entry);
        } else {
            download(List.of(entry));
        }
    }

    private void updateButtons() {
        long selectedFiles = table.getSelectionModel().getSelectedItems().stream()
                .filter(entry -> entry != null && !entry.folder()).count();
        selectedButton.setText(selectedFiles > 0 ? "下载所选 (" + selectedFiles + ")" : "下载所选");
        selectedButton.setDisable(selectedFiles == 0);
        allButton.setDisable(entries.stream().noneMatch(entry -> !entry.folder()));
    }

    private static String kind(ShareSession.Entry entry) {
        if (entry.folder()) {
            return "文件夹";
        }
        String type = entry.fileType();
        if (type != null && !type.isBlank() && !"file".equalsIgnoreCase(type)) {
            return type;
        }
        int dot = entry.fileName() == null ? -1 : entry.fileName().lastIndexOf('.');
        return dot > 0 && dot < entry.fileName().length() - 1 ? entry.fileName().substring(dot + 1).toUpperCase() : "文件";
    }

    @FunctionalInterface
    private interface Work {
        Runnable call() throws Exception;
    }

    private record Level(String label, ShareSession.Listing listing, ShareSession.Entry folder) {
        Level(String label, ShareSession.Listing listing) {
            this(label, listing, null);
        }
    }
}

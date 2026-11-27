package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.share.AuthStore;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class SettingsPane extends ScrollPane {

    SettingsPane(Settings settings, DownloadManager manager, AuthStore auths, Ui.StatusBar status) {
        TextField dir = new TextField(settings.downloadDir().toString());
        dir.setPrefColumnCount(32);
        GridPane.setHgrow(dir, Priority.ALWAYS);
        Button browse = Ui.button("浏览…", null, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择下载目录");
            File current = new File(dir.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File picked = chooser.showDialog(getScene().getWindow());
            if (picked != null) {
                dir.setText(picked.getAbsolutePath());
            }
        });
        Button reset = Ui.button("恢复默认", AppPaths.defaultDownloadDir().toString(),
                () -> dir.setText(AppPaths.defaultDownloadDir().toString()));
        Spinner<Integer> concurrent = new Spinner<>(1, 8, settings.maxConcurrent());
        Spinner<Integer> connections = new Spinner<>(1, 16, settings.connections());
        concurrent.setEditable(true);
        connections.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, Ui.label("下载目录"), dir, Ui.row(8, browse, reset));
        grid.addRow(1, Ui.label("同时下载"), Ui.row(8, concurrent, Ui.label("个任务", "muted")));
        grid.addRow(2, Ui.label("每个任务"), Ui.row(8, connections, Ui.label("个连接（网盘限速时调高，被限流时调低）", "muted")));
        grid.add(Ui.label("新目录对之后加入的下载生效；连接数对新开始或继续的任务生效。", "muted"), 0, 3, 3, 1);
        grid.getChildren().forEach(node -> {
            if (node instanceof Region region) {
                region.setMinWidth(Region.USE_PREF_SIZE);
            }
        });
        dir.setMinWidth(240);

        Button save = Ui.accent(Ui.button("保存下载设置", null, () -> {
            String text = dir.getText().trim();
            Path path;
            try {
                path = text.isEmpty() ? AppPaths.defaultDownloadDir() : Path.of(text);
            } catch (InvalidPathException ex) {
                status.error("下载目录格式无效：" + ex.getReason());
                dir.requestFocus();
                return;
            }
            String problem = checkWritable(path);
            if (problem != null) {
                status.error(problem.replace('\n', ' '));
                dir.requestFocus();
                return;
            }
            try {
                concurrent.commitValue();
                connections.commitValue();
            } catch (NumberFormatException ex) {
                status.error("请填写有效的并发任务数和连接数");
                return;
            }
            settings.downloadDir(path);
            settings.downloads(concurrent.getValue(), connections.getValue());
            manager.setMaxConcurrent(settings.maxConcurrent());
            manager.setConnections(settings.connections());
            dir.setText(settings.downloadDir().toString());
            status.info("设置已保存，下载目录：" + settings.downloadDir());
        }));
        VBox downloads = new VBox(12, grid, Ui.row(8, save));
        downloads.setPadding(new Insets(12));

        TabPane tabs = new TabPane(new Tab("下载位置与连接", downloads),
                new Tab("网盘账号", accountsPane(auths,
                        () -> status.info("网盘登录信息已更新，重新解析后生效"))));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("settings-tabs");
        setContent(tabs);
        setFitToWidth(true);
        getStyleClass().add("settings-scroll");
    }

    /** Null when the folder exists (or was created) and accepts a file; otherwise why not. */
    static String checkWritable(Path dir) {
        if (!dir.isAbsolute()) {
            return "请填写完整路径，例如 " + AppPaths.defaultDownloadDir();
        }
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".nfd-write-test", ".tmp");
            Files.delete(probe);
            return null;
        } catch (IOException | SecurityException ex) {
            return "无法写入 " + dir + "：" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " " + ex.getMessage()) + "\n请换一个有写入权限的目录。";
        }
    }

    private static Node accountsPane(AuthStore auths, Runnable changed) {
        ComboBox<AuthStore.Provider> picker = new ComboBox<>();
        picker.getItems().setAll(AuthStore.PROVIDERS);
        picker.setCellFactory(list -> new ProviderCell(auths));
        picker.setButtonCell(new ProviderCell(auths));
        Label hint = Ui.label("", "muted");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.setPrefWidth(660);
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(8);
        Label state = Ui.label("", "muted");
        Map<String, TextInputControl> inputs = new LinkedHashMap<>();

        Runnable render = () -> {
            AuthStore.Provider provider = picker.getValue();
            fields.getChildren().clear();
            inputs.clear();
            if (provider == null) {
                return;
            }
            hint.setText(provider.hint());
            Map<String, String> stored = auths.get(provider.type());
            int row = 0;
            for (AuthStore.Field field : provider.fields()) {
                TextInputControl input;
                if (field.multiline()) {
                    TextArea area = new TextArea();
                    area.setPrefRowCount(4);
                    area.setWrapText(true);
                    input = area;
                } else if (field.secret()) {
                    input = new PasswordField();
                } else {
                    input = new TextField();
                }
                boolean set = stored.containsKey(field.key());
                if (field.secret()) {
                    input.setPromptText(set ? "已填写，留空则保持不变" : field.hint());
                } else {
                    input.setText(stored.getOrDefault(field.key(), ""));
                    input.setPromptText(field.hint());
                }
                GridPane.setHgrow(input, Priority.ALWAYS);
                fields.addRow(row++, Ui.label(field.label()), input);
                inputs.put(field.key(), input);
            }
            state.setText(auths.configured(provider.type()) ? "已配置" : "未配置");
        };
        picker.valueProperty().addListener((observable, before, after) -> render.run());

        Button save = Ui.accent(Ui.button("保存", "保存这个网盘的登录信息", () -> {
            AuthStore.Provider provider = picker.getValue();
            if (provider == null) {
                return;
            }
            Map<String, String> changes = new LinkedHashMap<>();
            inputs.forEach((key, input) -> changes.put(key, input.getText() == null ? "" : input.getText()));
            try {
                auths.update(provider.type(), changes);
                changed.run();
                render.run();
                state.setText((auths.configured(provider.type()) ? "已配置" : "未配置") + "，已保存");
            } catch (IOException | IllegalArgumentException ex) {
                state.setText("保存失败：" + ex.getMessage());
            }
        }));
        Button clear = Ui.button("清除", "删除这个网盘的登录信息", () -> {
            AuthStore.Provider provider = picker.getValue();
            if (provider == null) {
                return;
            }
            try {
                auths.clear(provider.type());
                changed.run();
                render.run();
                state.setText("已清除");
            } catch (IOException ex) {
                state.setText("清除失败：" + ex.getMessage());
            }
        });
        picker.getSelectionModel().selectFirst();

        Label intro = Ui.label("夸克、UC 等网盘需要登录后才能解析或下载。登录信息只保存在本机数据目录的 auths.json，"
                + "不会上传，也不会写入下载任务。请只填写自己的账号，不要分享 Cookie 或 Token。", "muted");
        intro.setWrapText(true);
        intro.setMinHeight(Region.USE_PREF_SIZE);
        intro.setPrefWidth(660);
        VBox box = new VBox(10, intro, Ui.row(8, Ui.label("网盘"), picker, state), hint, fields,
                Ui.row(8, Ui.spacer(), clear, save));
        box.setPadding(new Insets(12));
        return box;
    }

    private static final class ProviderCell extends ListCell<AuthStore.Provider> {
        private final AuthStore auths;

        ProviderCell(AuthStore auths) {
            this.auths = auths;
        }

        @Override
        protected void updateItem(AuthStore.Provider item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null
                    : item.name() + (auths.configured(item.type()) ? "（已配置）" : ""));
        }
    }
}

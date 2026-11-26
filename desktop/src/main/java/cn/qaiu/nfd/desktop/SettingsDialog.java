package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadManager;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;

final class SettingsDialog {

    private SettingsDialog() {
    }

    /** Returns true when the user saved changes. */
    static boolean show(Window owner, Settings settings, DownloadManager manager) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("设置");
        dialog.setHeaderText("下载设置");
        dialog.getDialogPane().getButtonTypes().addAll(Ui.OK, Ui.CANCEL);

        TextField dir = new TextField(settings.downloadDir().toString());
        dir.setPrefColumnCount(32);
        GridPane.setHgrow(dir, Priority.ALWAYS);
        javafx.scene.control.Button browse = Ui.button("浏览…", null, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择下载目录");
            File current = new File(dir.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File picked = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (picked != null) {
                dir.setText(picked.getAbsolutePath());
            }
        });
        Spinner<Integer> concurrent = new Spinner<>(1, 8, settings.maxConcurrent());
        Spinner<Integer> connections = new Spinner<>(1, 16, settings.connections());
        concurrent.setEditable(true);
        connections.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.addRow(0, Ui.label("下载目录"), dir, browse);
        grid.addRow(1, Ui.label("同时下载"), concurrent, Ui.label("个任务", "muted"));
        grid.addRow(2, Ui.label("每个任务"), connections, Ui.label("个连接（网盘限速时调高，被限流时调低）", "muted"));
        grid.add(Ui.label("新目录对之后加入的下载生效；连接数对新开始或继续的任务生效。", "muted"), 0, 3, 3, 1);
        dialog.getDialogPane().setContent(grid);

        if (dialog.showAndWait().orElse(Ui.CANCEL) != Ui.OK) {
            return false;
        }
        String text = dir.getText() == null ? "" : dir.getText().trim();
        if (!text.isEmpty()) {
            settings.downloadDir(Path.of(text));
        }
        settings.downloads(concurrent.getValue(), connections.getValue());
        manager.setMaxConcurrent(settings.maxConcurrent());
        manager.setConnections(settings.connections());
        return true;
    }
}

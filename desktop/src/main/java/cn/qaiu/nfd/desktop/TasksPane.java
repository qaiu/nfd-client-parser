package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.DownloadManager;
import cn.qaiu.nfd.core.Formats;
import cn.qaiu.nfd.core.TaskSnapshot;
import cn.qaiu.nfd.core.TaskStatus;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Download queue backed by the shared {@link DownloadManager}. */
final class TasksPane extends VBox {

    private final DownloadManager manager;
    private final Supplier<Path> downloadDir;
    private final Ui.StatusBar status;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Map<String, Row> byId = new HashMap<>();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label summary = Ui.label("", "muted");

    TasksPane(DownloadManager manager, Supplier<Path> downloadDir, Ui.StatusBar status) {
        super(10);
        this.manager = manager;
        this.downloadDir = downloadDir;
        this.status = status;

        HBox toolbar = Ui.row(8,
                Ui.label("下载", "card-title"), summary, Ui.spacer(),
                Ui.button("全部暂停", null, manager::pauseAll),
                Ui.button("全部继续", "继续已暂停和失败的任务", manager::resumeAll),
                Ui.button("清除已完成", "只移除记录，不删除文件", manager::clearFinished),
                Ui.button("打开目录", null, () -> openFolder(downloadDir.get())));
        configureTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(toolbar, table);
        getStyleClass().add("card");
        sync();
    }

    /** Called on the FX thread by a timeline; cheap enough at 2 Hz for hundreds of tasks. */
    void sync() {
        List<TaskSnapshot> snapshots = manager.snapshots();
        Set<String> seen = new HashSet<>();
        boolean added = false;
        int running = 0;
        long speed = 0;
        for (TaskSnapshot snapshot : snapshots) {
            seen.add(snapshot.id());
            Row row = byId.get(snapshot.id());
            if (row == null) {
                row = new Row(snapshot);
                byId.put(snapshot.id(), row);
                rows.add(row);
                added = true;
            } else if (!row.snapshot.get().equals(snapshot)) {
                row.snapshot.set(snapshot);
            }
            if (snapshot.status() == TaskStatus.RUNNING) {
                running++;
                speed += snapshot.speed();
            }
        }
        if (rows.removeIf(row -> !seen.contains(row.id()))) {
            byId.keySet().retainAll(seen);
        }
        if (added) {
            rows.sort(Comparator.comparingLong((Row row) -> row.snapshot.get().createdAt()).reversed());
        }
        long queued = snapshots.stream().filter(task -> task.status() == TaskStatus.QUEUED).count();
        summary.setText(snapshots.isEmpty() ? "" : running + " 个下载中" + (queued > 0 ? "，" + queued + " 个排队" : "")
                + (running > 0 ? "  ·  " + Formats.speed(speed) : ""));
    }

    long activeCount() {
        return manager.snapshots().stream().filter(task -> task.status().active()).count();
    }

    private void configureTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("还没有下载。在左侧选择文件后点击“下载所选”。", "muted"));

        TableColumn<Row, TaskSnapshot> name = column("文件", 260);
        name.setCellFactory(column -> new TableCell<>() {
            private final Label title = Ui.label("", "task-name");
            private final Label detail = Ui.label("", "muted");
            private final VBox box = new VBox(2, title, detail);

            @Override
            protected void updateItem(TaskSnapshot task, boolean empty) {
                super.updateItem(task, empty);
                if (empty || task == null) {
                    setGraphic(null);
                    return;
                }
                title.setText(task.fileName() == null || task.fileName().isBlank() ? "（等待文件名）" : task.fileName());
                String message = task.message() == null ? "" : task.message();
                detail.setText(message);
                detail.getStyleClass().remove("error-text");
                if (task.status() == TaskStatus.FAILED) {
                    detail.getStyleClass().add("error-text");
                }
                detail.setVisible(!message.isBlank());
                detail.setManaged(!message.isBlank());
                setGraphic(box);
            }
        });

        TableColumn<Row, TaskSnapshot> progress = column("进度", 230);
        progress.setCellFactory(column -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label text = Ui.label("", "muted");
            private final VBox box = new VBox(3, bar, text);

            {
                bar.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(TaskSnapshot task, boolean empty) {
                super.updateItem(task, empty);
                if (empty || task == null) {
                    setGraphic(null);
                    return;
                }
                double value = task.progress();
                bar.setProgress(value < 0 ? (task.status() == TaskStatus.RUNNING ? ProgressBar.INDETERMINATE_PROGRESS : 0) : value);
                bar.getStyleClass().removeAll("done", "failed", "paused");
                switch (task.status()) {
                    case COMPLETED -> bar.getStyleClass().add("done");
                    case FAILED -> bar.getStyleClass().add("failed");
                    case PAUSED -> bar.getStyleClass().add("paused");
                    default -> {
                    }
                }
                String percent = value >= 0 ? String.format("%.0f%%  ", value * 100) : "";
                text.setText(percent + task.progressText());
                setGraphic(box);
            }
        });

        TableColumn<Row, TaskSnapshot> state = column("状态", 70);
        state.setMaxWidth(90);
        state.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(TaskSnapshot task, boolean empty) {
                super.updateItem(task, empty);
                getStyleClass().removeAll(stateClasses());
                if (empty || task == null) {
                    setText(null);
                    return;
                }
                setText(task.status().label());
                getStyleClass().add("state-" + task.status().name().toLowerCase());
            }
        });

        TableColumn<Row, TaskSnapshot> actions = column("", 150);
        actions.setMaxWidth(180);
        actions.setCellFactory(column -> new TableCell<>() {
            private final Button primary = new Button();
            private final Button remove = new Button("移除");
            private final HBox box = Ui.row(6, primary, remove);

            {
                box.setAlignment(Pos.CENTER_RIGHT);
                primary.setOnAction(event -> primaryAction(getItem()));
                remove.setOnAction(event -> confirmRemove(getItem()));
            }

            @Override
            protected void updateItem(TaskSnapshot task, boolean empty) {
                super.updateItem(task, empty);
                if (empty || task == null) {
                    setGraphic(null);
                    return;
                }
                primary.setText(switch (task.status()) {
                    case RUNNING, QUEUED -> "暂停";
                    case PAUSED -> "继续";
                    case FAILED -> "重试";
                    case COMPLETED -> "打开";
                });
                setGraphic(box);
            }
        });

        table.getColumns().add(name);
        table.getColumns().add(progress);
        table.getColumns().add(state);
        table.getColumns().add(actions);
        table.setRowFactory(view -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    TaskSnapshot task = row.getItem().snapshot.get();
                    if (task.status() == TaskStatus.COMPLETED) {
                        openFile(task);
                    }
                }
            });
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null).otherwise(menu(row)));
            return row;
        });
    }

    private ContextMenu menu(TableRow<Row> row) {
        MenuItem open = new MenuItem("打开文件");
        open.setOnAction(event -> openFile(row.getItem().snapshot.get()));
        MenuItem reveal = new MenuItem("在文件夹中显示");
        reveal.setOnAction(event -> reveal(row.getItem().snapshot.get()));
        MenuItem toggle = new MenuItem("暂停 / 继续");
        toggle.setOnAction(event -> primaryAction(row.getItem().snapshot.get()));
        MenuItem remove = new MenuItem("移除…");
        remove.setOnAction(event -> confirmRemove(row.getItem().snapshot.get()));
        ContextMenu menu = new ContextMenu(open, reveal, new SeparatorMenuItem(), toggle, remove);
        menu.setOnShowing(event -> {
            TaskSnapshot task = row.getItem() == null ? null : row.getItem().snapshot.get();
            open.setDisable(task == null || task.status() != TaskStatus.COMPLETED);
            toggle.setDisable(task == null || task.status() == TaskStatus.COMPLETED);
        });
        return menu;
    }

    private void primaryAction(TaskSnapshot task) {
        if (task == null) {
            return;
        }
        switch (task.status()) {
            case RUNNING, QUEUED -> manager.pause(task.id());
            case PAUSED, FAILED -> manager.resume(task.id());
            case COMPLETED -> openFile(task);
        }
        sync();
    }

    private void confirmRemove(TaskSnapshot task) {
        if (task == null) {
            return;
        }
        ButtonType keep = new ButtonType("仅移除任务", ButtonBar.ButtonData.OK_DONE);
        ButtonType delete = new ButtonType(task.status() == TaskStatus.COMPLETED ? "同时删除文件" : "同时删除已下载部分",
                ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", keep, delete, ButtonType.CANCEL);
        alert.setTitle("移除下载");
        alert.setHeaderText("移除“" + task.fileName() + "”？");
        alert.initOwner(getScene().getWindow());
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() != ButtonType.CANCEL) {
            manager.remove(task.id(), choice.get() == delete);
            sync();
        }
    }

    private void openFile(TaskSnapshot task) {
        Path file = file(task);
        if (file == null) {
            status.error("找不到文件位置");
            return;
        }
        try {
            Opener.open(file);
        } catch (IOException ex) {
            status.error("无法打开文件：" + ex.getMessage());
        }
    }

    private void reveal(TaskSnapshot task) {
        Path file = file(task);
        try {
            if (file == null) {
                Opener.openFolder(downloadDir.get());
            } else {
                Opener.reveal(file);
            }
        } catch (IOException ex) {
            status.error("无法打开目录：" + ex.getMessage());
        }
    }

    private void openFolder(Path dir) {
        try {
            Opener.openFolder(dir);
        } catch (IOException ex) {
            status.error("无法打开目录：" + ex.getMessage());
        }
    }

    private static Path file(TaskSnapshot task) {
        return task.destination() == null || task.destination().isBlank() ? null : Path.of(task.destination());
    }

    private static List<String> stateClasses() {
        return java.util.Arrays.stream(TaskStatus.values()).map(value -> "state-" + value.name().toLowerCase()).toList();
    }

    private static TableColumn<Row, TaskSnapshot> column(String title, double width) {
        TableColumn<Row, TaskSnapshot> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> cell.getValue().snapshot);
        return column;
    }

    static final class Row {
        final SimpleObjectProperty<TaskSnapshot> snapshot;

        Row(TaskSnapshot task) {
            snapshot = new SimpleObjectProperty<>(task);
        }

        String id() {
            return snapshot.get().id();
        }
    }
}

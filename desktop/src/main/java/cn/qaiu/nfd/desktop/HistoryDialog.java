package cn.qaiu.nfd.desktop;

import cn.qaiu.nfd.core.Formats;
import cn.qaiu.nfd.share.ParseHistory;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** Lists earlier parses; picking one fills in the link and parses it again. */
final class HistoryDialog {

    private static final ButtonType CLOSE = new ButtonType("关闭", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

    private HistoryDialog() {
    }

    static void show(Window owner, ParseHistory history, Consumer<ParseHistory.Item> reparse, Ui.StatusBar status) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("解析历史");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(CLOSE);

        ObservableList<ParseHistory.Item> items = FXCollections.observableArrayList(history.items());
        TableView<ParseHistory.Item> table = new TableView<>(items);
        table.setPlaceholder(Ui.label("还没有解析记录。解析过的链接会保存在本机，选中一条即可重新解析。", "muted"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ParseHistory.Item, String> result = new TableColumn<>("结果");
        result.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().success() ? "成功" : "失败"));
        result.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                getStyleClass().removeAll("history-ok", "history-failed");
                if (!empty && value != null) {
                    getStyleClass().add("成功".equals(value) ? "history-ok" : "history-failed");
                }
            }
        });
        result.setPrefWidth(56);
        result.setMaxWidth(70);
        TableColumn<ParseHistory.Item, String> title = new TableColumn<>("名称");
        title.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().title().isBlank() ? cell.getValue().link() : cell.getValue().title()));
        title.setPrefWidth(220);
        TableColumn<ParseHistory.Item, String> summary = new TableColumn<>("内容");
        summary.setCellValueFactory(cell -> new SimpleStringProperty(summary(cell.getValue())));
        summary.setPrefWidth(260);
        TableColumn<ParseHistory.Item, String> time = new TableColumn<>("时间");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        time.setCellValueFactory(cell -> new SimpleStringProperty(format.format(new Date(cell.getValue().time()))));
        time.setPrefWidth(130);
        time.setMaxWidth(150);
        TableColumn<ParseHistory.Item, String> link = new TableColumn<>("链接");
        link.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().link()
                + (cell.getValue().password().isBlank() ? "" : "  提取码 " + cell.getValue().password())));
        link.setPrefWidth(240);
        List<TableColumn<ParseHistory.Item, String>> columns = List.of(result, title, summary, time, link);
        table.getColumns().addAll(columns);

        Runnable reopen = () -> {
            ParseHistory.Item item = table.getSelectionModel().getSelectedItem();
            if (item != null) {
                dialog.close();
                reparse.accept(item);
            }
        };
        table.setRowFactory(view -> {
            TableRow<ParseHistory.Item> row = new TableRow<>() {
                @Override
                protected void updateItem(ParseHistory.Item item, boolean empty) {
                    super.updateItem(item, empty);
                    setTooltip(empty || item == null || item.success() || item.error().isBlank() ? null : new Tooltip(item.error()));
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    reopen.run();
                }
            });
            return row;
        });

        Button again = Ui.accent(Ui.button("重新解析", "也可以双击一行", reopen));
        Button delete = Ui.button("删除", "只删除这条记录", () -> {
            ParseHistory.Item item = table.getSelectionModel().getSelectedItem();
            if (item == null) {
                return;
            }
            try {
                history.remove(item);
                items.setAll(history.items());
            } catch (IOException ex) {
                status.error("删除失败：" + ex.getMessage());
            }
        });
        Button clear = Ui.button("清空", "删除全部记录（不影响下载任务）", () -> {
            if (items.isEmpty()) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "只删除本机的记录，不影响已下载的文件和下载任务。", Ui.OK, Ui.CANCEL);
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
            confirm.setHeaderText("清空解析历史？");
            if (confirm.showAndWait().orElse(Ui.CANCEL) != Ui.OK) {
                return;
            }
            try {
                history.clear();
                items.clear();
            } catch (IOException ex) {
                status.error("清空失败：" + ex.getMessage());
            }
        });
        again.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox content = new VBox(10, table, Ui.row(8, Ui.label("失败的记录可悬停查看原因", "muted"), Ui.spacer(),
                clear, delete, again));
        content.setPrefSize(960, 460);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    static String summary(ParseHistory.Item item) {
        if (!item.success()) {
            return item.error().isBlank() ? "解析失败" : item.error();
        }
        List<String> parts = new ArrayList<>();
        if (!item.panName().isBlank()) {
            parts.add(item.panName());
        }
        if (item.singleFile()) {
            parts.add("单文件");
        }
        if (item.folderCount() > 0) {
            parts.add(item.folderCount() + " 个文件夹");
        }
        if (!item.singleFile() || item.fileCount() != 1) {
            parts.add(item.fileCount() + " 个文件");
        }
        if (item.totalSize() > 0) {
            parts.add("共 " + Formats.size(item.totalSize()) + (item.sizeComplete() ? "" : "（部分大小未知）"));
        } else if (item.fileCount() > 0 && !item.sizeComplete()) {
            parts.add("大小未知");
        }
        if (item.hasMore()) {
            parts.add("还有更多未加载");
        }
        return String.join(" · ", parts);
    }
}

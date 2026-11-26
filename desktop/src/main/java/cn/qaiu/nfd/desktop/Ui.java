package cn.qaiu.nfd.desktop;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class Ui {

    private Ui() {
    }

    static Button button(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        if (tooltip != null) {
            button.setTooltip(new Tooltip(tooltip));
        }
        button.setOnAction(event -> action.run());
        return button;
    }

    static Button accent(Button button) {
        button.getStyleClass().add("accent");
        return button;
    }

    static Label label(String text, String... styles) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styles);
        return label;
    }

    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static HBox row(double spacing, Node... children) {
        HBox row = new HBox(spacing, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static VBox card(String title, Node actions, Region body) {
        HBox head = row(8, label(title, "card-title"));
        if (actions != null) {
            head.getChildren().addAll(spacer(), actions);
        }
        VBox card = new VBox(10, head, body);
        card.getStyleClass().add("card");
        VBox.setVgrow(body, Priority.ALWAYS);
        return card;
    }

    /** Bottom status line; errors stay visible until the next message. */
    static final class StatusBar extends HBox {
        private final Label message = label("准备就绪", "status-text");

        StatusBar(String right) {
            super(12);
            setAlignment(Pos.CENTER_LEFT);
            getStyleClass().add("status");
            message.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(message, Priority.ALWAYS);
            getChildren().addAll(message, label(right, "muted"));
        }

        void info(String text) {
            message.setText(text);
            getStyleClass().remove("error");
        }

        void error(String text) {
            message.setText(text);
            if (!getStyleClass().contains("error")) {
                getStyleClass().add("error");
            }
        }
    }
}

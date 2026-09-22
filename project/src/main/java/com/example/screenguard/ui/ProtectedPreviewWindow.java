package com.example.screenguard.ui;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.sun.jna.platform.win32.WinDef.HWND;

import com.example.screenguard.capture.CaptureProtectionService;
import com.example.screenguard.capture.CaptureProtectionService.ProtectionResult;
import com.example.screenguard.windows.User32Extended;
import com.example.screenguard.windows.WindowsWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * A window that belongs to ScreenGuard itself and can therefore be protected end-to-end.
 *
 * <p>Windows requires the window to belong to the calling process, so this window is the
 * supported way to demonstrate and verify the capture-exclusion mechanism in practice: press
 * "Protect this window", then capture the screen with a tool that honours display affinity (for
 * example the Windows snipping tool) and observe that this window appears as an empty area while
 * remaining fully visible on screen.</p>
 */
public final class ProtectedPreviewWindow {

    private static final Logger LOG = Logger.getLogger(ProtectedPreviewWindow.class.getName());

    /** Unique caption used to locate the native window handle. */
    public static final String WINDOW_TITLE = "ScreenGuard Protected Preview";

    private final CaptureProtectionService protectionService;
    private final WindowsWindowManager windowManager;

    private final Stage stage = new Stage();
    private final Label clockLabel = new Label();
    private final Circle stateDot = new Circle(7);
    private final Label stateLabel = new Label("Not protected");
    private final Label detailLabel = new Label("-");
    private final Button protectButton = new Button("Protect this window");
    private final Button unprotectButton = new Button("Remove protection");
    private Timeline clock;

    /**
     * @param protectionService the protection service
     * @param windowManager     used to locate the native handle of this window
     */
    public ProtectedPreviewWindow(CaptureProtectionService protectionService,
            WindowsWindowManager windowManager) {
        this.protectionService = protectionService;
        this.windowManager = windowManager;
        buildUi();
    }

    /** Shows the preview window. */
    public void show() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        startClock();
        refreshState();
    }

    /** Hides the window and stops its clock. */
    public void hide() {
        stopClock();
        stage.hide();
    }

    /** @return {@code true} when the preview window is visible. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Switches the preview window on or off. */
    public void toggle() {
        if (isShowing()) {
            hide();
        } else {
            show();
        }
    }

    /** Closes the window for good (used while shutting the application down). */
    public void dispose() {
        stopClock();
        stage.close();
    }

    private void buildUi() {
        Label title = new Label("Protected preview window");
        title.getStyleClass().add("title-label");

        Label explanation = new Label(
                "This window is owned by ScreenGuard itself. Windows only applies display affinity "
                        + "to windows of the calling process, which is why this is the only kind of "
                        + "window ScreenGuard can protect directly. Protect it, then capture the "
                        + "screen with the Windows snipping tool: this window appears as an empty "
                        + "area in the capture while staying fully visible on screen.");
        explanation.getStyleClass().add("subtitle-label");
        explanation.setWrapText(true);
        explanation.setMaxWidth(520);

        stateDot.setFill(Color.web("#B4BECC"));
        stateLabel.getStyleClass().add("status-text");
        HBox stateRow = new HBox(8, stateDot, stateLabel);
        stateRow.setAlignment(Pos.CENTER_LEFT);

        detailLabel.getStyleClass().add("mono-label");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(520);
        clockLabel.getStyleClass().add("mono-label");

        protectButton.getStyleClass().add("button-primary");
        protectButton.setOnAction(event -> apply(true));
        unprotectButton.getStyleClass().add("button-secondary");
        unprotectButton.setOnAction(event -> apply(false));
        HBox buttons = new HBox(10, protectButton, unprotectButton);

        Label hint = new Label(
                "Results depend on the capture technology: tools that do not honour the Windows "
                        + "display-affinity setting - and cameras or hardware capture cards - are "
                        + "unaffected.");
        hint.getStyleClass().add("muted-label");
        hint.setWrapText(true);
        hint.setMaxWidth(520);

        VBox root = new VBox(12, title, explanation, new Separator(), stateRow, detailLabel,
                clockLabel, buttons, new Separator(), hint);
        root.setPadding(new Insets(18));

        Scene scene = new Scene(root, 560, 410);
        java.net.URL css = ProtectedPreviewWindow.class.getResource("/css/screenguard.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle(WINDOW_TITLE);
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        stage.setOnHidden(event -> stopClock());
    }

    // ------------------------------------------------------------------
    // Protection handling
    // ------------------------------------------------------------------

    private void apply(boolean protecting) {
        Optional<HWND> handle = findOwnHandle();
        if (handle.isEmpty()) {
            setState(false, "The native window handle of the preview window could not be located.");
            return;
        }
        ProtectionResult result = protecting
                ? protectionService.protect(handle.get())
                : protectionService.unprotect(handle.get());
        LOG.log(Level.INFO, "Preview window protection changed: {0} / affinity {1}",
                new Object[] {result.status(), result.affinityText()});
        setState(result.isProtected(),
                result.message() + " Current display affinity: " + result.affinityText() + ".");
    }

    private void refreshState() {
        Optional<HWND> handle = findOwnHandle();
        if (handle.isEmpty()) {
            setState(false, "The native window handle of the preview window could not be located.");
            return;
        }
        long affinity = protectionService.readAffinity(handle.get());
        setState(affinity == User32Extended.WDA_EXCLUDEFROMCAPTURE
                        || affinity == User32Extended.WDA_MONITOR,
                "Current display affinity: " + CaptureProtectionService.describeAffinity(affinity)
                        + ".");
    }

    /**
     * Finds the native handle of this JavaFX window by matching its caption among the windows of
     * the ScreenGuard process.
     *
     * @return the handle, or an empty {@link Optional} when it is not (yet) visible
     */
    private Optional<HWND> findOwnHandle() {
        return windowManager.findOwnWindowByTitle(WINDOW_TITLE).map(WindowsWindow::getHwnd);
    }

    private void setState(boolean protectedNow, String detail) {
        stateDot.setFill(Color.web(protectedNow ? "#1F9D55" : "#B4BECC"));
        stateLabel.setText(protectedNow ? "Protected" : "Not protected");
        detailLabel.setText(detail);
    }

    private void startClock() {
        if (clock != null) {
            return;
        }
        clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            clockLabel.setText("Live content - " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            refreshState();
        }));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
    }

    private void stopClock() {
        if (clock != null) {
            clock.stop();
            clock = null;
        }
    }
}

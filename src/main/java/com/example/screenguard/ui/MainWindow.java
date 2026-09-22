package com.example.screenguard.ui;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Creates and owns the main JavaFX window (stage, scene, icon, lifecycle).
 *
 * <p>The window content itself is produced by {@link MainController}; this class only wires the
 * stage around it. Closing the window hides it instead of terminating the application, so
 * ScreenGuard keeps running in the notification area.</p>
 */
public final class MainWindow {

    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    private static final String STYLESHEET = "/css/screenguard.css";
    private static final String[] ICON_RESOURCES = {
        "/icons/screenguard-256.png",
        "/icons/screenguard-64.png",
        "/icons/screenguard-32.png",
        "/icons/screenguard-16.png"
    };

    private final Stage stage;
    private final MainController controller;
    private boolean exitRequested;

    /**
     * Builds the main window.
     *
     * @param stage      stage supplied by the JavaFX runtime
     * @param controller controller that provides the content and receives the events
     */
    public MainWindow(Stage stage, MainController controller) {
        this.stage = stage;
        this.controller = controller;

        Parent content = controller.createContent();
        Scene scene = new Scene(content, 960, 720);
        scene.getStylesheets().add(cssLocation());

        stage.setScene(scene);
        stage.setTitle("ScreenGuard");
        applyIcons();
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(event -> {
            if (!exitRequested) {
                event.consume();
                hide();
                controller.onWindowHidden();
            }
        });
        LOG.info("Main window created.");
    }

    /** @return the stage backing this window. */
    public Stage getStage() {
        return stage;
    }

    /** @return {@code true} when the window is currently visible. */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Shows the window and brings it to the foreground. */
    public void show() {
        if (!stage.isShowing()) {
            stage.show();
            LOG.fine("Main window shown.");
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }

    /** Hides the window; the application keeps running in the tray. */
    public void hide() {
        if (stage.isShowing()) {
            stage.hide();
            LOG.fine("Main window hidden.");
        }
    }

    /** Shows the window and expands its settings section. */
    public void showSettings() {
        show();
        controller.showSettingsSection();
    }

    /** Allows the next close request to terminate the application. */
    public void allowExitAndClose() {
        exitRequested = true;
        stage.close();
    }

    private static String cssLocation() {
        java.net.URL url = MainWindow.class.getResource(STYLESHEET);
        if (url == null) {
            LOG.warning("Stylesheet " + STYLESHEET + " not found; using the default JavaFX theme.");
            return "";
        }
        return url.toExternalForm();
    }

    private void applyIcons() {
        for (String resource : ICON_RESOURCES) {
            try (InputStream in = MainWindow.class.getResourceAsStream(resource)) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Could not load window icon " + resource, e);
            }
        }
        if (stage.getIcons().isEmpty()) {
            LOG.fine("No window icon resources found.");
        }
    }
}

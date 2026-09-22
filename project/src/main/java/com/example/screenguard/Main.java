package com.example.screenguard;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import com.example.screenguard.capture.CaptureProtectionService;
import com.example.screenguard.capture.WindowsVersion;
import com.example.screenguard.config.AppSettings;
import com.example.screenguard.diagnostics.SelfTest;
import com.example.screenguard.tray.SystemTrayManager;
import com.example.screenguard.ui.MainController;
import com.example.screenguard.ui.MainWindow;
import com.example.screenguard.ui.ProtectedPreviewWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * Application entry point and lifecycle owner.
 *
 * <p>Startup order:</p>
 * <ol>
 *   <li>{@link #main(String[])} configures logging and runs the diagnostic self test when
 *       {@code --selftest} was supplied.</li>
 *   <li>{@link #start(Stage)} builds the UI, installs the notification-area icon and starts
 *       hidden in the tray when started with {@code --tray} (the flag used by the Windows
 *       startup entry).</li>
 *   <li>{@link #stop()} logs the shutdown, removes the tray icon and releases the worker.</li>
 * </ol>
 *
 * <p>Closing the main window only hides it ({@code Platform.setImplicitExit(false)}); the
 * explicit "Exit" action terminates the process.</p>
 */
public final class Main extends Application {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    /** Command line flag: show the main window immediately. */
    public static final String FLAG_SHOW = "--show";

    /** Command line flag: run without showing the main window (used by the startup entry). */
    public static final String FLAG_TRAY = "--tray";

    /** Command line flag: run the console self test instead of the GUI. */
    public static final String FLAG_SELFTEST = "--selftest";

    private AppSettings settings;
    private MainController controller;
    private MainWindow mainWindow;
    private ProtectedPreviewWindow previewWindow;
    private SystemTrayManager trayManager;
    private boolean exiting;

    /**
     * Configures logging, runs the diagnostic self test when requested, otherwise hands over to
     * the JavaFX launcher.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        if (SelfTest.isRequested(args)) {
            System.exit(SelfTest.run(args));
        }
        LOG.log(Level.INFO, "ScreenGuard {0} starting (arguments: {1})",
                new Object[] {version(), Arrays.toString(args)});
        Application.launch(args);
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0";
    }

    @Override
    public void start(Stage primaryStage) {
        WindowsVersion windows = WindowsVersion.current();
        LOG.info("Detected " + windows.summary());

        settings = AppSettings.load();
        if (settings.isVerboseLogging()) {
            enableVerboseLogging();
        }

        WindowsWindowManager windowManager = new WindowsWindowManager();
        CaptureProtectionService protection = new CaptureProtectionService(windowManager);
        trayManager = new SystemTrayManager();
        previewWindow = new ProtectedPreviewWindow(protection, windowManager);

        controller = new MainController(windowManager, protection, settings, trayManager,
                new MainController.UiActions() {
                    @Override
                    public void hideToTray() {
                        mainWindow.hide();
                        controller.onWindowHidden();
                    }

                    @Override
                    public void showWindow() {
                        mainWindow.show();
                    }

                    @Override
                    public void exitApplication() {
                        requestExit();
                    }

                    @Override
                    public void openProtectedPreview() {
                        previewWindow.toggle();
                    }
                });

        mainWindow = new MainWindow(primaryStage, controller);
        Platform.setImplicitExit(false);

        boolean trayInstalled = trayManager.install(new SystemTrayManager.TrayActions() {
            @Override
            public void onProtectWindow() {
                controller.handleTrayProtect();
            }

            @Override
            public void onUnprotectWindow() {
                controller.handleTrayUnprotect();
            }

            @Override
            public void onOpenSettings() {
                Platform.runLater(mainWindow::showSettings);
            }

            @Override
            public void onShowWindow() {
                Platform.runLater(mainWindow::show);
            }

            @Override
            public void onExit() {
                Platform.runLater(Main.this::requestExit);
            }
        });

        boolean trayOnly = hasFlag(FLAG_TRAY) && !hasFlag(FLAG_SHOW)
                && !settings.isShowWindowOnStartup();
        boolean showWindow = !trayInstalled || !trayOnly;
        if (showWindow) {
            mainWindow.show();
            LOG.info("ScreenGuard started with the main window visible.");
        } else {
            LOG.info("ScreenGuard started in the notification area (tray-only mode).");
        }

        controller.refreshWindowList();
    }

    private boolean hasFlag(String flag) {
        return getParameters().getRaw().contains(flag);
    }

    private void requestExit() {
        if (exiting) {
            return;
        }
        exiting = true;
        LOG.info("Exit requested by the user.");
        settings.save();
        if (previewWindow != null) {
            previewWindow.dispose();
        }
        if (mainWindow != null) {
            mainWindow.allowExitAndClose();
        }
        Platform.exit();
    }

    @Override
    public void stop() {
        LOG.info("ScreenGuard shutting down.");
        try {
            if (previewWindow != null) {
                previewWindow.dispose();
            }
            if (controller != null) {
                controller.shutdown();
            }
            if (trayManager != null) {
                trayManager.remove();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error during shutdown", e);
        } finally {
            LOG.info("ScreenGuard stopped.");
        }
    }

    private static void enableVerboseLogging() {
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new java.util.logging.SimpleFormatter());
        Logger root = Logger.getLogger("com.example.screenguard");
        root.setLevel(Level.ALL);
        root.addHandler(handler);
        root.setUseParentHandlers(false);
        LOG.info("Verbose logging enabled.");
    }
}

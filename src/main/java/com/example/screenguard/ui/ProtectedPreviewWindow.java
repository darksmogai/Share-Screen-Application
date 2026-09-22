package com.example.screenguard.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.example.screenguard.capture.CaptureProtectionService;
import com.example.screenguard.windows.WindowsWindowManager;

/** Launches the native Edge WebView2 browser helper used for protected browsing. */
public final class ProtectedPreviewWindow {

    private static final Logger LOG = Logger.getLogger(ProtectedPreviewWindow.class.getName());
    private Process browserProcess;

    /** Compatibility constructor retained for the existing JavaFX application wiring. */
    public ProtectedPreviewWindow(CaptureProtectionService ignoredProtection,
            WindowsWindowManager ignoredWindowManager) {
    }

    /** Opens or closes the protected Edge browser. */
    public void toggle() {
        if (browserProcess != null && browserProcess.isAlive()) {
            browserProcess.destroy();
            browserProcess = null;
            return;
        }
        Path executable = findHelper();
        if (executable == null) {
            LOG.warning("Edge WebView2 helper is not built. Run edge-helper\\build-helper.bat first.");
            return;
        }
        try {
            browserProcess = new ProcessBuilder(executable.toString()).start();
            LOG.info("Protected Edge browser started: " + executable);
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not start the protected Edge browser", exception);
        }
    }

    /** Closes the helper during application shutdown. */
    public void dispose() {
        if (browserProcess != null && browserProcess.isAlive()) {
            browserProcess.destroy();
        }
        browserProcess = null;
    }

    private static Path findHelper() {
        Path current = Path.of(System.getProperty("user.dir"));
        Path packagedApp = packagedAppDirectory();
        List<Path> candidates = List.of(
                packagedApp.resolve("edge-helper\\EdgeProtectedBrowser.exe"),
                packagedApp.resolve("app\\edge-helper\\EdgeProtectedBrowser.exe"),
                current.resolve("edge-helper\\bin\\Release\\net8.0-windows\\win-x64\\publish-new\\EdgeProtectedBrowser.exe"),
                current.resolve("edge-helper\\bin\\Release\\net8.0-windows\\win-x64\\publish\\EdgeProtectedBrowser.exe"),
                current.resolve("..\\edge-helper\\bin\\Release\\net8.0-windows\\win-x64\\publish\\EdgeProtectedBrowser.exe"),
                current.resolve("..\\..\\edge-helper\\bin\\Release\\net8.0-windows\\win-x64\\publish\\EdgeProtectedBrowser.exe"));
        return candidates.stream().map(Path::toAbsolutePath).filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Path packagedAppDirectory() {
        String appPath = System.getProperty("jpackage.app-path", "");
        if (!appPath.isBlank()) {
            Path path = Path.of(appPath).toAbsolutePath();
            return path.getParent() == null ? path : path.getParent();
        }
        return currentDirectoryFallback();
    }

    private static Path currentDirectoryFallback() {
        return Path.of(System.getProperty("user.dir"));
    }
}

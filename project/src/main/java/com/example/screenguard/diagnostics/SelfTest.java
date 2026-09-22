package com.example.screenguard.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;

import com.example.screenguard.capture.CaptureProtectionService;
import com.example.screenguard.capture.CaptureProtectionService.ProtectionResult;
import com.example.screenguard.capture.CaptureProtectionService.Status;
import com.example.screenguard.capture.WindowsVersion;
import com.example.screenguard.windows.User32Extended;
import com.example.screenguard.windows.WindowsWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * Console based self test ({@code screenguard --selftest}).
 *
 * <p>It exercises every Windows path ScreenGuard uses without opening the JavaFX UI:</p>
 * <ol>
 *   <li>window enumeration</li>
 *   <li>locating a selected window and reading its handle</li>
 *   <li>enabling capture protection on a window ScreenGuard owns</li>
 *   <li>reading the protection state back from Windows</li>
 *   <li>removing the protection</li>
 *   <li>the documented refusal for a foreign window (Notepad)</li>
 *   <li>the graceful handling of a window that was closed in the meantime</li>
 * </ol>
 */
public final class SelfTest {

    private static final Logger LOG = Logger.getLogger(SelfTest.class.getName());

    private static final String OWN_WINDOW_TITLE = "ScreenGuard self test window";
    private static final long WINDOW_FIND_TIMEOUT_MILLIS = 8000;

    /** Command line flag that selects the self test. */
    public static final String FLAG_SELFTEST = "--selftest";

    private final List<String> failures = new ArrayList<>();
    private final List<String> passes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    /**
     * @param args command line arguments
     * @return {@code true} when {@code --selftest} was supplied
     */
    public static boolean isRequested(String[] args) {
        return args != null && List.of(args).contains(FLAG_SELFTEST);
    }

    /**
     * Runs the self test.
     *
     * @param args command line arguments
     * @return process exit code (0 when all checks passed)
     */
    public static int run(String[] args) {
        return new SelfTest().execute();
    }

    private int execute() {
        System.out.println("ScreenGuard self test");
        System.out.println("=====================");
        printEnvironment();

        WindowsWindowManager windowManager = new WindowsWindowManager();
        CaptureProtectionService protection = new CaptureProtectionService(windowManager);

        checkEnumeration(windowManager);
        Optional<TestTargetWindow> ownWindow = checkProtectionOfOwnWindow(windowManager, protection);
        checkForeignWindowRefusal(protection);
        checkClosedWindow(windowManager, protection);

        printSummary();
        ownWindow.ifPresent(TestTargetWindow::close);
        return failures.isEmpty() ? 0 : 1;
    }

    private static void printEnvironment() {
        WindowsVersion version = WindowsVersion.current();
        System.out.println("Java         : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.vendor") + ")");
        System.out.println("Windows      : " + version.summary());
        System.out.println("Architecture : " + com.sun.jna.Platform.ARCH);
        System.out.println("JNA          : " + com.sun.jna.Native.VERSION);
        System.out.println("Process      : pid " + com.sun.jna.platform.win32.Kernel32.INSTANCE
                .GetCurrentProcessId());
        System.out.println("WDA_EXCLUDEFROMCAPTURE supported by this build: "
                + (version.buildIsKnown()
                        ? String.valueOf(version.build()
                                >= User32Extended.WDA_EXCLUDEFROMCAPTURE_MIN_BUILD)
                        : "unknown (build number not readable)"));
        System.out.println();
    }

    private void checkEnumeration(WindowsWindowManager windowManager) {
        try {
            List<WindowsWindow> windows = windowManager.listTargetWindows();
            if (windows == null || windows.isEmpty()) {
                record(false, "enumeration", "No visible top-level windows were found.");
                return;
            }
            boolean allValid = windows.stream().allMatch(window ->
                    window.getProcessId() > 0 && window.getHwndValue() != 0);
            record(allValid, "enumeration", "Listed " + windows.size() + " window(s), first entries:");
            windows.stream().limit(6).map(window -> "   - " + window.getDisplayName() + "  ["
                            + window.getExecutableName() + ", pid " + window.getProcessId()
                            + ", hwnd " + window.getHwndHex() + "]")
                    .forEach(System.out::println);
        } catch (RuntimeException e) {
            record(false, "enumeration", "Enumeration failed: " + e);
        }
    }

    private Optional<TestTargetWindow> checkProtectionOfOwnWindow(
            WindowsWindowManager windowManager, CaptureProtectionService protection) {
        TestTargetWindow target;
        try {
            target = TestTargetWindow.create(OWN_WINDOW_TITLE);
        } catch (IllegalStateException e) {
            record(false, "own window", "Could not create the test window: " + e.getMessage());
            return Optional.empty();
        }

        Optional<WindowsWindow> window = target.findWindow(windowManager, WINDOW_FIND_TIMEOUT_MILLIS);
        if (window.isEmpty()) {
            record(false, "own window", "The test window was not found in the enumeration.");
            target.close();
            return Optional.empty();
        }
        WindowsWindow found = window.get();
        record(true, "find window", "Found " + found.getDisplayName() + " (pid "
                + found.getProcessId() + ", hwnd " + found.getHwndHex() + ")");

        WindowsWindowManager.TargetValidation validation = windowManager.validate(found.getHwnd());
        record(validation.isValid(), "validate handle",
                validation.status() + ": " + validation.message());

        ProtectionResult enabled = protection.protect(found.getHwnd());
        record(enabled.isSuccess() && enabled.status() == Status.ENABLED, "enable protection",
                enabled.status() + " / affinity " + enabled.affinityText());

        long readBack = protection.readAffinity(found.getHwnd());
        record(readBack == User32Extended.WDA_EXCLUDEFROMCAPTURE, "read protection state",
                "GetWindowDisplayAffinity returned 0x" + Long.toHexString(readBack));

        boolean captureProtected = protection.isCaptureProtected(found.getHwnd());
        record(captureProtected, "isCaptureProtected", String.valueOf(captureProtected));

        ProtectionResult disabled = protection.unprotect(found.getHwnd());
        long cleared = protection.readAffinity(found.getHwnd());
        record(disabled.isSuccess() && cleared == User32Extended.WDA_NONE,
                "remove protection",
                disabled.status() + " / affinity 0x" + Long.toHexString(cleared));

        return Optional.of(target);
    }

    private void checkForeignWindowRefusal(CaptureProtectionService protection) {
        Process notepad = null;
        HWND notepadHwnd = null;
        try {
            notepad = new ProcessBuilder("notepad.exe").start();
            notepadHwnd = waitForProcessWindow(notepad.pid());
        } catch (Exception e) {
            note("Foreign window: Notepad could not be started (" + e.getMessage() + "). "
                    + "The refusal check was skipped; Windows documents the refusal as "
                    + "ERROR_ACCESS_DENIED (5).");
            if (notepad != null) {
                notepad.destroy();
            }
            return;
        }

        if (notepadHwnd == null) {
            note("Foreign window: no window appeared for pid " + notepad.pid()
                    + "; the refusal check was skipped.");
            notepad.destroy();
            return;
        }

        ProtectionResult result = protection.protect(notepadHwnd);
        if (result.status() == Status.NOT_OWNED) {
            record(true, "foreign window refusal",
                    "Windows returned ERROR_ACCESS_DENIED (5) as documented - ScreenGuard reports "
                            + "it instead of injecting into that process.");
        } else if (result.isSuccess()) {
            record(true, "foreign window refusal",
                    "This Windows build accepted the affinity for a foreign window (status "
                            + result.status() + "); ScreenGuard reported it verbatim.");
            protection.unprotect(notepadHwnd);
        } else {
            record(false, "foreign window refusal",
                    "Unexpected status " + result.status() + " for a foreign window.");
        }
        notepad.destroy();
    }

    private void checkClosedWindow(WindowsWindowManager windowManager,
            CaptureProtectionService protection) {
        HWND bogusHandle = new HWND(new Pointer(1L));
        ProtectionResult bogus = protection.protect(bogusHandle);
        record(!bogus.isSuccess(), "invalid handle", bogus.status() + ": " + bogus.message());

        TestTargetWindow target = TestTargetWindow.create("ScreenGuard closing window");
        try {
            Optional<WindowsWindow> window =
                    target.findWindow(windowManager, WINDOW_FIND_TIMEOUT_MILLIS);
            if (window.isEmpty()) {
                record(false, "window closed", "The test window could not be located.");
                return;
            }
            HWND handle = window.get().getHwnd();
            target.close();
            ProtectionResult result = protection.protect(handle);
            record(!result.isSuccess() && (result.status() == Status.WINDOW_CLOSED
                            || result.status() == Status.INVALID_HANDLE),
                    "window closed during operation",
                    result.status() + " - no exception was thrown");
        } finally {
            target.close();
        }
    }

    private HWND waitForProcessWindow(long processId) throws InterruptedException {
        WindowsWindowManager manager = new WindowsWindowManager();
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            List<WindowsWindow> windows = manager.listTargetWindows();
            Optional<WindowsWindow> match = windows.stream()
                    .filter(window -> window.getProcessId() == processId)
                    .findFirst();
            if (match.isPresent()) {
                return match.get().getHwnd();
            }
            Thread.sleep(300);
        }
        return null;
    }

    private void record(boolean passed, String check, String detail) {
        String prefix = passed ? "[PASS]" : "[FAIL]";
        System.out.println(prefix + " " + check + " - " + detail);
        (passed ? passes : failures).add(check + ": " + detail);
        LOG.log(Level.INFO, "{0} {1} - {2}", new Object[] {prefix, check, detail});
    }

    private void note(String detail) {
        System.out.println("[NOTE] " + detail);
        notes.add(detail);
    }

    private void printSummary() {
        System.out.println();
        System.out.println("Summary: " + passes.size() + " passed, " + failures.size()
                + " failed, " + notes.size() + " note(s).");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        for (String note : notes) {
            System.out.println("  NOTE:   " + note);
        }
        System.out.println(failures.isEmpty()
                ? "Self test completed successfully."
                : "Self test found problems; see the log for details.");
    }
}

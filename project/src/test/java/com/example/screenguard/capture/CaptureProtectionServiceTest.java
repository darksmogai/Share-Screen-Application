package com.example.screenguard.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;

import com.example.screenguard.capture.CaptureProtectionService.ProtectionResult;
import com.example.screenguard.capture.CaptureProtectionService.Status;
import com.example.screenguard.diagnostics.TestTargetWindow;
import com.example.screenguard.windows.User32Extended;
import com.example.screenguard.windows.WindowsWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * Integration tests of the capture-protection service against the real Windows APIs.
 *
 * <p>The test target is a real AWT window owned by the test JVM, because Windows applies display
 * affinity only to windows of the calling process.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class CaptureProtectionServiceTest {

    private static final String WINDOW_TITLE = "ScreenGuard capture test window";

    private static WindowsWindowManager windowManager;
    private static CaptureProtectionService protectionService;
    private static TestTargetWindow target;

    @BeforeAll
    static void setUp() {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(),
                "This test needs a desktop session to create a real window.");
        windowManager = new WindowsWindowManager();
        protectionService = new CaptureProtectionService(windowManager);
        target = TestTargetWindow.create(WINDOW_TITLE);
    }

    @AfterAll
    static void tearDown() {
        if (target != null) {
            target.close();
        }
    }

    private static WindowsWindow testWindow() {
        Optional<WindowsWindow> window = target.findWindow(windowManager, 8000);
        assumeTrue(window.isPresent(), "The test window appeared in the enumeration.");
        return window.get();
    }

    @Test
    void enablingProtectionExcludesTheWindowFromCapture() {
        WindowsWindow window = testWindow();
        ProtectionResult result = protectionService.protect(window.getHwnd());

        assertTrue(result.isSuccess(), "expected success, got: " + result.message());
        assertEquals(Status.ENABLED, result.status());
        assertEquals(User32Extended.WDA_EXCLUDEFROMCAPTURE, result.affinity());
        assertTrue(result.affinityText().contains("WDA_EXCLUDEFROMCAPTURE"));
        assertTrue(result.message().contains("Capture protection enabled"));
    }

    @Test
    void readingProtectionStateReportsTheAppliedAffinity() {
        WindowsWindow window = testWindow();
        assumeTrue(protectionService.protect(window.getHwnd()).isSuccess());

        long affinity = protectionService.readAffinity(window.getHwnd());
        assertEquals(User32Extended.WDA_EXCLUDEFROMCAPTURE, affinity);
        assertTrue(protectionService.isCaptureProtected(window.getHwnd()));
    }

    @Test
    void readingTheStateOfAnInvalidHandleReturnsMinusOne() {
        assertEquals(-1L, protectionService.readAffinity(null));
        assertEquals(-1L, protectionService.readAffinity(new HWND(new Pointer(0x1L))));
    }

    @Test
    void removingProtectionClearsTheAffinity() {
        WindowsWindow window = testWindow();
        assumeTrue(protectionService.protect(window.getHwnd()).isSuccess());
        assumeTrue(protectionService.readAffinity(window.getHwnd())
                        == User32Extended.WDA_EXCLUDEFROMCAPTURE,
                "protection was not enabled before removing it");

        ProtectionResult result = protectionService.unprotect(window.getHwnd());
        assertTrue(result.isSuccess(), "expected success, got: " + result.message());
        assertEquals(Status.DISABLED, result.status());
        assertEquals(User32Extended.WDA_NONE, protectionService.readAffinity(window.getHwnd()));
        assertFalse(protectionService.isCaptureProtected(window.getHwnd()));
    }

    @Test
    void foreignWindowsAreReportedInsteadOfInjectedInto() throws Exception {
        HWND foreignHandle = startNotepadWindow();
        assumeTrue(foreignHandle != null, "Notepad was not available on this system.");

        try {
            ProtectionResult result = protectionService.protect(foreignHandle);
            assertFalse(result.isSuccess(), "Windows must refuse foreign windows");
            if (result.win32Error() == User32Extended.ERROR_ACCESS_DENIED) {
                assertEquals(Status.NOT_OWNED, result.status());
                assertTrue(result.message().contains("SetWindowDisplayAffinity"));
            } else {
                // A future Windows release could relax the ownership rule; report what happened.
                assertNotEquals(Status.ENABLED, result.status());
            }
        } finally {
            stopNotepad();
        }
    }

    @Test
    void protectingAClosedWindowFailsGracefully() {
        WindowsWindow window = testWindow();
        HWND handle = window.getHwnd();
        target.close();

        ProtectionResult result = protectionService.protect(handle);
        assertFalse(result.isSuccess());
        assertTrue(result.status() == Status.WINDOW_CLOSED
                        || result.status() == Status.INVALID_HANDLE,
                "unexpected status: " + result.status());
    }

    @Test
    void protectionHandlesNullAndGarbageHandles() {
        assertFalse(protectionService.protect(null).isSuccess());
        assertFalse(protectionService.protect(new HWND(new Pointer(0x12345678L))).isSuccess());
    }

    @Test
    void describeAffinityExplainsEveryValue() {
        assertTrue(CaptureProtectionService.describeAffinity(User32Extended.WDA_NONE)
                .startsWith("WDA_NONE"));
        assertTrue(CaptureProtectionService.describeAffinity(User32Extended.WDA_MONITOR)
                .startsWith("WDA_MONITOR"));
        assertTrue(CaptureProtectionService.describeAffinity(User32Extended.WDA_EXCLUDEFROMCAPTURE)
                .startsWith("WDA_EXCLUDEFROMCAPTURE"));
        assertTrue(CaptureProtectionService.describeAffinity(-1).contains("unknown"));
        assertTrue(CaptureProtectionService.describeAffinity(0xCAFEL).contains("unrecognised"));
    }

    // ------------------------------------------------------------------
    // Notepad helpers
    // ------------------------------------------------------------------

    private static Process notepad;

    private static HWND startNotepadWindow() throws Exception {
        try {
            notepad = new ProcessBuilder("notepad.exe").start();
        } catch (Exception e) {
            notepad = null;
            return null;
        }
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Optional<WindowsWindow> found = windowManager.listTargetWindows().stream()
                    .filter(window -> window.getProcessId() == notepad.pid())
                    .findFirst();
            if (found.isPresent()) {
                return found.get().getHwnd();
            }
            if (!notepad.isAlive()) {
                return null;
            }
            Thread.sleep(250);
        }
        return null;
    }

    private static void stopNotepad() {
        if (notepad != null) {
            notepad.destroy();
            notepad = null;
        }
    }
}

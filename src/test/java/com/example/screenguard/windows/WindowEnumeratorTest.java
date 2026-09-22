package com.example.screenguard.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;

import com.example.screenguard.diagnostics.TestTargetWindow;

/**
 * Tests for the Windows window-enumeration and validation logic.
 *
 * <p>These are integration tests: they call the real Windows User32 APIs and create a real AWT
 * window that belongs to the test JVM so that the "find a selected window" path is exercised
 * against a handle the test owns.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class WindowEnumeratorTest {

    private static final String WINDOW_TITLE = "ScreenGuard enumeration test window";

    private static WindowEnumerator enumerator;
    private static WindowsWindowManager manager;
    private static TestTargetWindow target;

    @BeforeAll
    static void setUp() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        enumerator = new WindowEnumerator();
        manager = new WindowsWindowManager(enumerator);
        target = TestTargetWindow.create(WINDOW_TITLE);
    }

    @Test
    void enumeratingVisibleTopLevelWindowsReturnsUsableEntries() {
        List<WindowsWindow> windows = enumerator.enumerateVisibleTopLevelWindows();

        assertNotNull(windows, "enumeration must return a non-null list");
        assertFalse(windows.isEmpty(), "a desktop session always has visible windows");

        for (WindowsWindow w : windows) {
            assertTrue(w.getProcessId() > 0, "pid must be positive: " + w);
            assertTrue(User32Extended.handleValue(w.getHwnd()) != 0, "hwnd must be non-zero");
            assertTrue(w.isVisible(), "window must be visible: " + w);
        }
    }

    @Test
    void findingTheSelectedWindowReturnsItsHandle() {
        Optional<WindowsWindow> found = manager.findOwnWindowByTitle(WINDOW_TITLE);

        assertTrue(found.isPresent(), "the test window was enumerated");
        WindowsWindow w = found.get();
        assertEquals(WINDOW_TITLE, w.getTitle());
        assertNotNull(w.getHwnd());
        assertTrue(User32Extended.handleValue(w.getHwnd()) != 0);
        assertFalse(w.getExecutableName().isBlank());
        assertTrue(w.getProcessId() > 0);
    }

    @Test
    void describeWindowRejectsDeadHandles() {
        assertFalse(enumerator.describeWindow(null).isPresent());
        assertFalse(enumerator.describeWindow(new HWND(new Pointer(0L))).isPresent());
        assertFalse(enumerator.describeWindow(new HWND(new Pointer(0x12345678L))).isPresent());
    }

    @Test
    void validateExplainsEveryProblem() {
        WindowsWindowManager.TargetValidation nullValidation = manager.validate(null);
        assertEquals(WindowsWindowManager.TargetStatus.INVALID_HANDLE, nullValidation.status());
        assertFalse(nullValidation.isValid());

        WindowsWindowManager.TargetValidation zeroValidation =
                manager.validate(new HWND(new Pointer(0L)));
        assertEquals(WindowsWindowManager.TargetStatus.INVALID_HANDLE, zeroValidation.status());
        assertFalse(zeroValidation.isValid());

        WindowsWindowManager.TargetValidation garbage =
                manager.validate(new HWND(new Pointer(0x12345678L)));
        assertTrue(garbage.status() == WindowsWindowManager.TargetStatus.WINDOW_CLOSED
                || garbage.status() == WindowsWindowManager.TargetStatus.INVALID_HANDLE,
                "expected closed/invalid, got: " + garbage.status());
        assertFalse(garbage.isValid());

        Optional<WindowsWindow> found = manager.findOwnWindowByTitle(WINDOW_TITLE);
        assertTrue(found.isPresent(), "the test window was enumerated");
        WindowsWindowManager.TargetValidation valid = manager.validate(found.get().getHwnd());
        assertEquals(WindowsWindowManager.TargetStatus.VALID, valid.status());
        assertTrue(valid.isValid());
    }

    @Test
    void theOwnProcessIsDetected() {
        Optional<WindowsWindow> found = manager.findOwnWindowByTitle(WINDOW_TITLE);
        assertTrue(found.isPresent(), "the test window was enumerated");

        assertTrue(manager.isOwnWindow(found.get().getHwnd()));
        assertEquals(Kernel32.INSTANCE.GetCurrentProcessId(), manager.getOwnProcessId());
    }

    @Test
    void theModelFormatsItsFields() {
        WindowsWindow w = WindowsWindow.builder()
                .hwndValue(0x11223344L)
                .processId(4711)
                .title("Notepad")
                .className("Notepad")
                .executableName("notepad.exe")
                .executablePath("C:\\Windows\\notepad.exe")
                .displayAffinity(User32Extended.WDA_EXCLUDEFROMCAPTURE)
                .build();

        assertEquals("0x0000000011223344", w.getHwndHex());
        assertEquals("Notepad", w.getDisplayName());
        assertTrue(w.searchKey().contains("notepad"));
        assertTrue(w.searchKey().contains("notepad.exe"));
        assertTrue(w.isCaptureProtected());
        assertTrue(w.toString().contains("notepad.exe"));

        assertEquals(w, WindowsWindow.builder()
                .hwndValue(0x11223344L).processId(4711).build());
        assertNotEquals(w, WindowsWindow.builder()
                .hwndValue(0x11223345L).processId(4711).build());
    }

    @Test
    void windowListsAreSortedAndRefreshable() {
        List<WindowsWindow> first = manager.listTargetWindows();
        assertFalse(first.isEmpty());

        for (int i = 1; i < first.size(); i++) {
            String previous = first.get(i - 1).getDisplayName();
            String current = first.get(i).getDisplayName();
            assertTrue(previous.compareToIgnoreCase(current) <= 0,
                    "list must be sorted by display name");
        }

        List<WindowsWindow> second = manager.listTargetWindows();
        assertFalse(second.isEmpty());
    }
}

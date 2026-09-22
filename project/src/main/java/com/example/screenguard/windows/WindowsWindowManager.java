package com.example.screenguard.windows;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Façade used by the UI to list, look up and validate candidate protection targets.
 *
 * <p>It hides the raw {@code EnumWindows} output: shell helper windows, untitled hosts and the
 * windows of ScreenGuard itself are filtered out so that the user only sees windows that can
 * realistically act as a protection target.</p>
 */
public final class WindowsWindowManager {

    private static final Logger LOG = Logger.getLogger(WindowsWindowManager.class.getName());

    /**
     * Window classes that are never useful as a protection target: invisible UWP hosts and
     * desktop/shell plumbing that cannot be protected meaningfully.
     */
    private static final Set<String> IGNORED_CLASSES = Set.of(
            "Windows.UI.Core.CoreWindow",
            "ApplicationManager_ImmersiveShellWindow",
            "Progman",
            "WorkerW",
            "Shell_TrayWnd",
            "Shell_SecondaryTrayWnd",
            "DV2ControlHost",
            "MsgrIMEWindowClass",
            "SysShadow",
            "IME",
            "MSCTFIME UI",
            "Default IME");

    private final User32Extended user32;
    private final WindowEnumerator enumerator;
    private final int ownProcessId;

    /** Creates a manager bound to the live Windows APIs. */
    public WindowsWindowManager() {
        this(User32Extended.INSTANCE, new WindowEnumerator());
    }

    /** Visible for tests: injectable collaborators. */
    WindowsWindowManager(User32Extended user32, WindowEnumerator enumerator) {
        this.user32 = user32;
        this.enumerator = enumerator;
        this.ownProcessId = Kernel32.INSTANCE.GetCurrentProcessId();
    }

    /** @return the process id of the running ScreenGuard instance. */
    public int getOwnProcessId() {
        return ownProcessId;
    }

    /**
     * Lists the visible top-level windows of the ScreenGuard process itself.
     *
     * <p>These are the only windows Windows lets this process protect, so they are also offered
     * in the main list (clearly marked).</p>
     *
     * @return the visible windows owned by this process
     */
    public List<WindowsWindow> listOwnProcessWindows() {
        return enumerator.enumerateVisibleTopLevelWindows().stream()
                .filter(window -> window.getProcessId() == ownProcessId)
                .toList();
    }

    /**
     * Finds a ScreenGuard-owned window by its exact caption.
     *
     * @param title window caption to look for
     * @return the window, or an empty {@link Optional} when it is not visible right now
     */
    public Optional<WindowsWindow> findOwnWindowByTitle(String title) {
        return listOwnProcessWindows().stream()
                .filter(window -> title.equals(window.getTitle()))
                .findFirst();
    }

    /**
     * Lists the windows a user can choose to protect.
     *
     * @return visible, titled top-level windows of other processes, sorted by display name
     */
    public List<WindowsWindow> listTargetWindows() {
        List<WindowsWindow> targets = enumerator.enumerateVisibleTopLevelWindows().stream()
                .filter(this::isUsableTarget)
                .sorted(Comparator.comparing(WindowsWindow::getDisplayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        LOG.log(Level.INFO, "Window list produced {0} selectable target(s)", targets.size());
        return targets;
    }

    /** Drops cached process information so the next refresh re-reads it from the OS. */
    public void invalidateCache() {
        enumerator.clearProcessCache();
    }

    /**
     * Re-reads a single window.
     *
     * @param hwnd handle to look up
     * @return the fresh snapshot, or an empty {@link Optional} when the window is gone
     */
    public Optional<WindowsWindow> describe(HWND hwnd) {
        return enumerator.describeWindow(hwnd);
    }

    /** @return the process id that owns {@code hwnd}, or {@code 0} when it cannot be read. */
    public int processIdOf(HWND hwnd) {
        IntByReference pid = new IntByReference(0);
        user32.GetWindowThreadProcessId(hwnd, pid);
        return pid.getValue();
    }

    /** @return {@code true} when the window belongs to the running ScreenGuard process. */
    public boolean isOwnWindow(HWND hwnd) {
        return processIdOf(hwnd) == ownProcessId;
    }

    private boolean isUsableTarget(WindowsWindow window) {
        if (window.getTitle().isBlank()) {
            return false;
        }
        if (IGNORED_CLASSES.contains(window.getClassName())) {
            return false;
        }
        return (User32Extended.style(window.getHwnd()) & User32Extended.WS_CHILD) == 0;
    }

    /**
     * Validates that a handle is still a legitimate protection target.
     *
     * <p>Windows applies display affinity only to windows that belong to the calling process, so
     * ScreenGuard-owned windows (main window, preview window) are valid targets; foreign windows
     * are validated as well and reported verbatim by {@code SetWindowDisplayAffinity}.</p>
     *
     * @param hwnd handle to validate
     * @return the outcome of the validation, including a user-readable explanation
     */
    public TargetValidation validate(HWND hwnd) {
        if (hwnd == null || User32Extended.handleValue(hwnd) == 0L) {
            return TargetValidation.of(TargetStatus.INVALID_HANDLE, "No window is selected.");
        }
        if (!user32.IsWindow(hwnd)) {
            return TargetValidation.of(TargetStatus.WINDOW_CLOSED,
                    "The window no longer exists (it was closed).");
        }
        if ((User32Extended.style(hwnd) & User32Extended.WS_CHILD) != 0) {
            return TargetValidation.of(TargetStatus.NOT_TOP_LEVEL,
                    "The selected handle is a child window, not a top-level window.");
        }
        if (!user32.IsWindowVisible(hwnd)) {
            return TargetValidation.of(TargetStatus.NOT_VISIBLE,
                    "The window is currently hidden or minimised and cannot be protected.");
        }
        return TargetValidation.of(TargetStatus.VALID, "The window can be protected.");
    }

    /** Result of validating a candidate window handle. */
    public enum TargetStatus {
        /** The handle is a live, visible top-level window of another process. */
        VALID,
        /** The handle is null or zero. */
        INVALID_HANDLE,
        /** The window no longer exists. */
        WINDOW_CLOSED,
        /** The handle refers to a child window. */
        NOT_TOP_LEVEL,
        /** The window exists but is not visible. */
        NOT_VISIBLE
    }

    /** Outcome of {@link #validate(HWND)}. */
    public record TargetValidation(TargetStatus status, String message) {

        static TargetValidation of(TargetStatus status, String message) {
            return new TargetValidation(status, message);
        }

        /** @return {@code true} when the window may be handed to the protection service. */
        public boolean isValid() {
            return status == TargetStatus.VALID;
        }
    }
}

package com.example.screenguard.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import com.example.screenguard.windows.User32Extended.EnumWindowsProc;

/**
 * Enumerates the visible top-level windows of the current desktop and resolves the owning
 * process of each one.
 *
 * <p>Uses only {@code EnumWindows}, {@code IsWindowVisible}, {@code GetWindowTextW},
 * {@code GetWindowTextLengthW}, {@code GetClassNameW}, {@code GetWindowThreadProcessId},
 * {@code OpenProcess} and {@code QueryFullProcessImageName} - all documented user-mode APIs.</p>
 */
public final class WindowEnumerator {

    private static final Logger LOG = Logger.getLogger(WindowEnumerator.class.getName());

    /** Longest caption that is copied from a window; longer captions are truncated. */
    static final int MAX_CAPTION_CHARS = 512;

    /** Buffer size used for the full executable path. */
    private static final int MAX_PROCESS_PATH_CHARS = 1024;

    /**
     * Access rights required by {@code QueryFullProcessImageName}. The limited variant also works
     * for processes the current user may only query (elevated apps, other sessions).
     */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;

    /** {@code PROCESS_NAME_WIN32}: return the Win32 path of the image. */
    private static final int PROCESS_NAME_WIN32 = 0;

    private final User32Extended user32;
    private final Map<Integer, ProcessDetails> processCache = new ConcurrentHashMap<>();

    /** Creates an enumerator bound to the live {@code user32.dll} mapping. */
    public WindowEnumerator() {
        this(User32Extended.INSTANCE);
    }

    /** Visible for tests: allows injecting a different {@code User32} binding. */
    WindowEnumerator(User32Extended user32) {
        this.user32 = user32;
    }

    /**
     * Enumerates every window reported by {@code EnumWindows} that is currently visible.
     *
     * <p>Windows that disappear while the enumeration is running are skipped instead of aborting
     * it, so a closing window can never break a refresh.</p>
     *
     * @return an ordered snapshot of the visible top-level windows
     */
    public List<WindowsWindow> enumerateVisibleTopLevelWindows() {
        List<WindowsWindow> windows = new ArrayList<>();
        EnumWindowsProc callback = (HWND hwnd, LPARAM lParam) -> {
            try {
                if (!user32.IsWindowVisible(hwnd)) {
                    return true;
                }
                describeWindow(hwnd).ifPresent(windows::add);
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                // A window vanished mid-flight or an API call failed: never abort the refresh.
                LOG.log(Level.FINE, "Skipping window during enumeration", e);
            }
            return true;
        };

        boolean completed = user32.EnumWindows(callback, new LPARAM(0));
        if (!completed) {
            LOG.log(Level.FINE, "EnumWindows stopped early (Win32 error {0})", Native.getLastError());
        }
        LOG.log(Level.INFO, "Window enumeration completed: {0} visible top-level window(s)",
                windows.size());
        return windows;
    }

    /** Drops the cached process names. */
    public void clearProcessCache() {
        processCache.clear();
    }

    /**
     * Builds a snapshot for a single window handle.
     *
     * @param hwnd handle to inspect
     * @return the snapshot, or an empty {@link Optional} when the handle is not a live window
     */
    public Optional<WindowsWindow> describeWindow(HWND hwnd) {
        if (hwnd == null || !user32.IsWindow(hwnd)) {
            return Optional.empty();
        }

        IntByReference pidReference = new IntByReference(0);
        user32.GetWindowThreadProcessId(hwnd, pidReference);
        int processId = pidReference.getValue();
        if (processId <= 0) {
            return Optional.empty();
        }

        String title = User32Extended.windowText(hwnd, MAX_CAPTION_CHARS);
        String className = User32Extended.windowClassName(hwnd);
        ProcessDetails details = resolveProcess(processId);

        return Optional.of(WindowsWindow.builder()
                .hwnd(hwnd)
                .processId(processId)
                .title(title)
                .className(className)
                .executableName(details.executableName())
                .executablePath(details.executablePath())
                .build());
    }

    /**
     * Resolves the executable that owns a process.
     *
     * @param processId process identifier
     * @return the file name and full path, or {@code unknown} placeholders when the process
     *         cannot be queried (protected or already exiting process)
     */
    private ProcessDetails resolveProcess(int processId) {
        return processCache.computeIfAbsent(processId, WindowEnumerator::queryProcessDetails);
    }

    private static ProcessDetails queryProcessDetails(int processId) {
        HANDLE process = Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (process == null) {
            process = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_INFORMATION, false, processId);
        }
        if (process == null) {
            LOG.log(Level.FINE, "OpenProcess failed for pid {0} (Win32 error {1})",
                    new Object[] {processId, Native.getLastError()});
            return ProcessDetails.UNKNOWN;
        }
        try {
            char[] buffer = new char[MAX_PROCESS_PATH_CHARS];
            IntByReference size = new IntByReference(buffer.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(
                    process, PROCESS_NAME_WIN32, buffer, size)) {
                LOG.log(Level.FINE,
                        "QueryFullProcessImageName failed for pid {0} (Win32 error {1})",
                        new Object[] {processId, Native.getLastError()});
                return ProcessDetails.UNKNOWN;
            }
            String path = new String(buffer, 0, size.getValue());
            return new ProcessDetails(fileName(path), path);
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    /** @return the last path element, or the input when it contains no separator. */
    static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int separator = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    /** Executable information of the process that owns a window. */
    private record ProcessDetails(String executableName, String executablePath) {
        private static final ProcessDetails UNKNOWN = new ProcessDetails("", "");
    }
}

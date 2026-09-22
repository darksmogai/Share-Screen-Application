package com.example.screenguard.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.DWORDByReference;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;

/**
 * JNA mapping of the {@code User32.dll} entry points ScreenGuard needs.
 *
 * <p>Only documented, user-mode window APIs are mapped here: window enumeration, window
 * metadata and the Windows display-affinity API. No screen-capturing, hooking, injection or
 * undocumented API is used.</p>
 *
 * <p>All parameters use the native Windows types ({@code HWND}, {@code DWORD}, {@code LPARAM},
 * {@code BOOL}, {@code LONG_PTR}, ...) so no pointer casts are required at the call sites.
 * The functions that use UTF-16 strings are the explicit {@code ...W} variants and take
 * {@code char[]} buffers, which JNA maps to {@code wchar_t*}.</p>
 */
public interface User32Extended extends StdCallLibrary {

    /** Lazily created singleton binding to {@code user32.dll}. */
    User32Extended INSTANCE = Native.load("user32", User32Extended.class);

    // ------------------------------------------------------------------
    // SetWindowDisplayAffinity / GetWindowDisplayAffinity constants
    // ------------------------------------------------------------------

    /** Clears every display-affinity restriction for the window. */
    int WDA_NONE = 0x00000000;

    /**
     * Legacy value (Windows 7+): content appears only on a monitor that uses the WDDM mirror
     * driver, everywhere else the window is shown with no content.
     */
    int WDA_MONITOR = 0x00000001;

    /**
     * Windows 10 version 2004 (build 19041) and later: the window is hidden from capture APIs
     * that honour display affinity, and is shown as an empty area in the capture instead.
     *
     * <p>Value taken verbatim from {@code winuser.h}. It is <em>not</em>
     * {@code 0x00000002} - passing an unsupported value makes the API fail with
     * {@code ERROR_INVALID_PARAMETER} (87).</p>
     */
    int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    /** Minimum Windows 10 build that supports {@link #WDA_EXCLUDEFROMCAPTURE}. */
    int WDA_EXCLUDEFROMCAPTURE_MIN_BUILD = 19041;

    // ------------------------------------------------------------------
    // GetWindowLongPtr indexes and relevant style bits
    // ------------------------------------------------------------------

    int GWL_STYLE = -16;
    int GWL_EXSTYLE = -20;

    /** Window is a child window (never a protection target for ScreenGuard). */
    int WS_CHILD = 0x40000000;

    /** Extended style of a tool window (usually hidden helper windows). */
    int WS_EX_TOOLWINDOW = 0x00000080;

    /** Extended style of a "no redirection bitmap" window (hidden UWP hosts). */
    int WS_EX_NOREDIRECTIONBITMAP = 0x00200000;

    // ------------------------------------------------------------------
    // Win32 error codes used for diagnostics
    // ------------------------------------------------------------------

    int ERROR_SUCCESS = 0;
    int ERROR_ACCESS_DENIED = 5;
    int ERROR_INVALID_HANDLE = 6;
    int ERROR_NOT_ENOUGH_MEMORY = 8;
    int ERROR_NOT_SUPPORTED = 50;
    int ERROR_INVALID_PARAMETER = 87;
    int ERROR_INVALID_WINDOW_HANDLE = 1400;
    int ERROR_INVALID_INDEX = 1413;

    // ------------------------------------------------------------------
    // Window enumeration
    // ------------------------------------------------------------------

    /** Callback signature of {@code WNDENUMPROC}; return {@code true} to keep enumerating. */
    interface EnumWindowsProc extends StdCallCallback {
        /**
         * @param hwnd   handle of the top-level window currently visited
         * @param lParam application-defined value passed to {@link #EnumWindows}
         * @return {@code true} (non-zero) to continue the enumeration
         */
        boolean callback(HWND hwnd, LPARAM lParam);
    }

    /**
     * Enumerates every top-level window on the desktop.
     *
     * @param lpEnumFunc callback invoked once per window
     * @param lParam     value forwarded to {@code lpEnumFunc}
     * @return {@code true} when the enumeration completed
     */
    boolean EnumWindows(EnumWindowsProc lpEnumFunc, LPARAM lParam);

    /** @return {@code true} when {@code hWnd} still identifies an existing window. */
    boolean IsWindow(HWND hWnd);

    /** @return {@code true} when the window and all of its parents carry {@code WS_VISIBLE}. */
    boolean IsWindowVisible(HWND hWnd);

    /**
     * Copies the window caption into {@code lpString} as UTF-16.
     *
     * @return number of characters copied, excluding the terminator
     */
    int GetWindowTextW(HWND hWnd, char[] lpString, int nMaxCount);

    /** @return length in characters of the window caption. */
    int GetWindowTextLengthW(HWND hWnd);

    /**
     * Copies the window class name into {@code lpClassName} as UTF-16.
     *
     * @return number of characters copied, excluding the terminator
     */
    int GetClassNameW(HWND hWnd, char[] lpClassName, int nMaxCount);

    /**
     * Retrieves the identifier of the process that created the window.
     *
     * @param lpdwProcessId receives the process identifier
     * @return identifier of the thread that created the window
     */
    int GetWindowThreadProcessId(HWND hWnd, IntByReference lpdwProcessId);

    /**
     * Reads a window attribute (see {@link #GWL_STYLE} / {@link #GWL_EXSTYLE}).
     *
     * <p>Mapped as {@code GetWindowLongPtrW} because {@code GetWindowLongPtr} is only a
     * preprocessor macro in {@code winuser.h}, not a DLL export.</p>
     *
     * @return the attribute value, or {@code 0} when the call fails
     */
    BaseTSD.LONG_PTR GetWindowLongPtrW(HWND hWnd, int nIndex);

    // ------------------------------------------------------------------
    // Display affinity (capture protection)
    // ------------------------------------------------------------------

    /**
     * Sets the display affinity of a window, which is how a window tells Windows how its content
     * may be presented by capture APIs.
     *
     * <p>The calling process must own the window: the documented failure modes are
     * {@code ERROR_ACCESS_DENIED} (5) for a foreign window and
     * {@code ERROR_INVALID_PARAMETER} (87) when the requested value is not supported by the
     * installed Windows version.</p>
     *
     * @return {@code true} on success
     */
    boolean SetWindowDisplayAffinity(HWND hWnd, DWORD dwAffinity);

    /**
     * Reads the display affinity currently assigned to a window.
     *
     * @param pdwAffinity receives the current affinity value
     * @return {@code true} on success
     */
    boolean GetWindowDisplayAffinity(HWND hWnd, DWORDByReference pdwAffinity);

    /** @return the desktop window handle. */
    HWND GetDesktopWindow();

    /** @return the shell (Progman) window handle, or {@code null} while the shell is starting. */
    HWND GetShellWindow();

    /** @return the handle of the foreground window, or {@code null}. */
    HWND GetForegroundWindow();

    // ------------------------------------------------------------------
    // Small typed helpers built on the mapping above
    // ------------------------------------------------------------------

    /**
     * Reads the numeric value of a window handle.
     *
     * @param hwnd handle, may be {@code null}
     * @return the handle value, or {@code 0} for {@code null} / empty handles
     */
    static long handleValue(HWND hwnd) {
        if (hwnd == null) {
            return 0L;
        }
        com.sun.jna.Pointer pointer = hwnd.getPointer();
        return pointer == null ? 0L : com.sun.jna.Pointer.nativeValue(pointer);
    }

    /**
     * Reads the UTF-16 caption of a window.
     *
     * @return the caption, or an empty string when the window has no caption or no longer exists
     */
    static String windowText(HWND hwnd, int maxChars) {
        int length = INSTANCE.GetWindowTextLengthW(hwnd);
        if (length <= 0) {
            return "";
        }
        char[] buffer = new char[Math.min(length, Math.max(1, maxChars)) + 1];
        int copied = INSTANCE.GetWindowTextW(hwnd, buffer, buffer.length);
        return copied <= 0 ? "" : new String(buffer, 0, copied);
    }

    /**
     * Reads the UTF-16 class name of a window.
     *
     * @return the class name, or an empty string when it cannot be read
     */
    static String windowClassName(HWND hwnd) {
        char[] buffer = new char[256];
        int copied = INSTANCE.GetClassNameW(hwnd, buffer, buffer.length);
        return copied <= 0 ? "" : new String(buffer, 0, copied);
    }

    /** Reads {@link #GWL_EXSTYLE}; the value fits in 32 bits on every supported Windows version. */
    static int extendedStyle(HWND hwnd) {
        BaseTSD.LONG_PTR value = INSTANCE.GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
        return value == null ? 0 : (int) value.longValue();
    }

    /** Reads {@link #GWL_STYLE}; the value fits in 32 bits on every supported Windows version. */
    static int style(HWND hwnd) {
        BaseTSD.LONG_PTR value = INSTANCE.GetWindowLongPtrW(hwnd, GWL_STYLE);
        return value == null ? 0 : (int) value.longValue();
    }
}

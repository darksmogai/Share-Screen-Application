package com.example.screenguard.capture;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.DWORDByReference;
import com.sun.jna.platform.win32.WinDef.HWND;

import com.example.screenguard.windows.User32Extended;
import com.example.screenguard.windows.WindowsWindowManager;
import com.example.screenguard.windows.WindowsWindowManager.TargetValidation;

/**
 * Applies and removes the Windows display-affinity setting that excludes a window from capture.
 *
 * <p>The service wraps {@code SetWindowDisplayAffinity} / {@code GetWindowDisplayAffinity} with
 * validation, read-back verification and precise diagnostics. It performs exactly one native side
 * effect: it sets the affinity of a window that the calling process owns. Windows itself always
 * enforces the documented "the window must belong to the current process" rule.</p>
 */
public final class CaptureProtectionService {

    private static final Logger LOG = Logger.getLogger(CaptureProtectionService.class.getName());

    private final User32Extended user32;
    private final WindowsWindowManager windows;
    private final WindowsVersion windowsVersion;

    /** Creates a service bound to the live Windows APIs. */
    public CaptureProtectionService(WindowsWindowManager windows) {
        this(User32Extended.INSTANCE, windows, WindowsVersion.current());
    }

    /** Visible for tests: injectable collaborators. */
    CaptureProtectionService(User32Extended user32, WindowsWindowManager windows,
            WindowsVersion version) {
        this.user32 = user32;
        this.windows = windows;
        this.windowsVersion = version;
    }

    /** @return the Windows version detected for diagnostics. */
    public WindowsVersion getWindowsVersion() {
        return windowsVersion;
    }

    /**
     * @return {@code true} when the running Windows build is known to support
     *         {@code WDA_EXCLUDEFROMCAPTURE} (Windows 10 version 2004, build 19041 and later)
     */
    public boolean isCaptureExclusionSupported() {
        return !windowsVersion.buildIsKnown()
                || windowsVersion.build() >= User32Extended.WDA_EXCLUDEFROMCAPTURE_MIN_BUILD;
    }

    /**
     * Excludes the given window from capture.
     *
     * @param hwnd target window handle
     * @return the outcome, including the affinity that Windows reported back
     */
    public ProtectionResult protect(HWND hwnd) {
        LOG.log(Level.INFO, "Protect requested for {0}", describe(hwnd));
        return applyAffinity(hwnd, User32Extended.WDA_EXCLUDEFROMCAPTURE,
                Status.ENABLED, Status.ENABLED_AS_MONITOR);
    }

    /**
     * Removes every display-affinity restriction from the given window.
     *
     * @param hwnd target window handle
     * @return the outcome, including the affinity that Windows reported back
     */
    public ProtectionResult unprotect(HWND hwnd) {
        LOG.log(Level.INFO, "Remove-protection requested for {0}", describe(hwnd));
        return applyAffinity(hwnd, User32Extended.WDA_NONE, Status.DISABLED, Status.DISABLED);
    }

    /**
     * Reads the display affinity of a window without changing it.
     *
     * @param hwnd target window handle
     * @return the affinity value, or {@code -1} when it cannot be read
     */
    public long readAffinity(HWND hwnd) {
        if (hwnd == null || !user32.IsWindow(hwnd)) {
            return -1L;
        }
        DWORDByReference affinity = new DWORDByReference(new DWORD(0));
        if (!user32.GetWindowDisplayAffinity(hwnd, affinity)) {
            LOG.log(Level.FINE, "GetWindowDisplayAffinity failed for {0} (Win32 error {1})",
                    new Object[] {hwnd, Native.getLastError()});
            return -1L;
        }
        return affinity.getValue().longValue();
    }

    /**
     * @param hwnd target window handle
     * @return {@code true} when the window is currently excluded from capture
     */
    public boolean isCaptureProtected(HWND hwnd) {
        return readAffinity(hwnd) == User32Extended.WDA_EXCLUDEFROMCAPTURE;
    }

    private ProtectionResult applyAffinity(HWND hwnd, int affinity, Status successStatus,
            Status successWhenMonitorIsSubstituted) {
        TargetValidation validation = windows.validate(hwnd);
        if (!validation.isValid()) {
            LOG.log(Level.WARNING, "Rejected {0}: {1}",
                    new Object[] {describe(hwnd), validation.message()});
            return new ProtectionResult(mapValidation(validation.status()),
                    readAffinitySafe(hwnd), 0, validation.message());
        }

        Native.setLastError(0);
        boolean applied = user32.SetWindowDisplayAffinity(hwnd, new DWORD(affinity));
        int win32Error = applied ? 0 : Native.getLastError();

        if (!applied) {
            Status status = classifyFailure(win32Error);
            String message = failureMessage(status, win32Error);
            LOG.log(Level.WARNING, "SetWindowDisplayAffinity({0}, 0x{1}) failed: {2}",
                    new Object[] {describe(hwnd), Integer.toHexString(affinity), message});
            return new ProtectionResult(status, readAffinitySafe(hwnd), win32Error, message);
        }

        long readBack = readAffinity(hwnd);
        Status status = successStatus;
        String message;
        if (readBack == affinity) {
            message = successMessage(successStatus);
        } else if (affinity == User32Extended.WDA_EXCLUDEFROMCAPTURE
                && readBack == User32Extended.WDA_MONITOR) {
            status = successWhenMonitorIsSubstituted;
            message = "Capture protection is active, but Windows applied WDA_MONITOR instead of "
                    + "WDA_EXCLUDEFROMCAPTURE (" + windowsVersion.summary()
                    + "). WDA_EXCLUDEFROMCAPTURE requires Windows 10 version 2004 "
                    + "(build 19041) or later.";
        } else {
            status = Status.READ_BACK_MISMATCH;
            message = "Windows reported success, but the window now has affinity 0x"
                    + Long.toHexString(readBack) + " instead of 0x"
                    + Integer.toHexString(affinity)
                    + ". The window may have been recreated in the meantime.";
        }
        LOG.log(Level.INFO, "Display affinity of {0} is now 0x{1} [{2}]",
                new Object[] {describe(hwnd), Long.toHexString(readBack), status});
        return new ProtectionResult(status, readBack, 0, message);
    }

    private static Status mapValidation(WindowsWindowManager.TargetStatus status) {
        return switch (status) {
            case INVALID_HANDLE -> Status.INVALID_HANDLE;
            case WINDOW_CLOSED -> Status.WINDOW_CLOSED;
            case NOT_TOP_LEVEL -> Status.NOT_TOP_LEVEL;
            case NOT_VISIBLE -> Status.NOT_VISIBLE;
            case VALID -> Status.FAILED;
        };
    }

    private static Status classifyFailure(int win32Error) {
        return switch (win32Error) {
            case User32Extended.ERROR_ACCESS_DENIED -> Status.NOT_OWNED;
            case User32Extended.ERROR_INVALID_PARAMETER -> Status.UNSUPPORTED;
            case User32Extended.ERROR_INVALID_WINDOW_HANDLE, User32Extended.ERROR_INVALID_HANDLE ->
                Status.WINDOW_CLOSED;
            default -> Status.FAILED;
        };
    }

    private String failureMessage(Status status, int win32Error) {
        return switch (status) {
            case NOT_OWNED -> "Windows refused the change (Win32 error 5, access denied). "
                    + "SetWindowDisplayAffinity only works on top-level windows that belong to the "
                    + "calling process, so ScreenGuard cannot exclude a window owned by another "
                    + "application. Doing that would require injecting code into that process, "
                    + "which ScreenGuard deliberately never does.";
            case UNSUPPORTED -> "Windows rejected the requested display affinity (Win32 error 87, "
                    + "invalid parameter). WDA_EXCLUDEFROMCAPTURE requires Windows 10 version 2004 "
                    + "(build 19041) or later; this system reports " + windowsVersion.summary() + ".";
            case WINDOW_CLOSED -> "The window disappeared before the display affinity was applied.";
            default -> "Windows could not apply the display affinity (Win32 error " + win32Error
                    + ": " + Win32ErrorText.describe(win32Error) + ").";
        };
    }

    private static String successMessage(Status status) {
        return switch (status) {
            case ENABLED -> "Capture protection enabled (WDA_EXCLUDEFROMCAPTURE).";
            case ENABLED_AS_MONITOR -> "Capture protection enabled (WDA_MONITOR).";
            case DISABLED -> "Capture protection removed (WDA_NONE).";
            default -> "Display affinity updated.";
        };
    }

    private long readAffinitySafe(HWND hwnd) {
        try {
            return readAffinity(hwnd);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the display affinity", e);
            return -1L;
        }
    }

    private String describe(HWND hwnd) {
        if (hwnd == null) {
            return "<null handle>";
        }
        int pid = windows.processIdOf(hwnd);
        String title = user32.IsWindow(hwnd) ? User32Extended.windowText(hwnd, 80) : "<gone>";
        return "hwnd=" + hwnd + " pid=" + pid + " title=\"" + title + "\"";
    }

    /** Outcome of a protect/unprotect request. */
    public enum Status {
        /** {@code WDA_EXCLUDEFROMCAPTURE} is now active. */
        ENABLED,
        /** Protection is active, but Windows substituted {@code WDA_MONITOR}. */
        ENABLED_AS_MONITOR,
        /** {@code WDA_NONE} is now active. */
        DISABLED,
        /** The window belongs to another process, which Windows forbids. */
        NOT_OWNED,
        /** Windows does not support the requested affinity value. */
        UNSUPPORTED,
        /** The window disappeared before the call. */
        WINDOW_CLOSED,
        /** No usable handle was supplied. */
        INVALID_HANDLE,
        /** The handle does not refer to a top-level window. */
        NOT_TOP_LEVEL,
        /** The window is hidden or minimised. */
        NOT_VISIBLE,
        /** Windows accepted the call, but the affinity read back differently. */
        READ_BACK_MISMATCH,
        /** Any other Win32 failure. */
        FAILED
    }

    /** Immutable outcome of a protect/unprotect request. */
    public record ProtectionResult(Status status, long affinity, int win32Error, String message) {

        /** @return {@code true} when the requested affinity is now active. */
        public boolean isSuccess() {
            return status == Status.ENABLED || status == Status.ENABLED_AS_MONITOR
                    || status == Status.DISABLED;
        }

        /** @return {@code true} when the window is excluded from capture right now. */
        public boolean isProtected() {
            return affinity == User32Extended.WDA_EXCLUDEFROMCAPTURE
                    || affinity == User32Extended.WDA_MONITOR;
        }

        /** @return a compact description of the affinity value Windows reported. */
        public String affinityText() {
            return describeAffinity(affinity);
        }
    }

    /**
     * @param affinity raw display-affinity value
     * @return a human readable name of the affinity
     */
    public static String describeAffinity(long affinity) {
        return switch ((int) affinity) {
            case User32Extended.WDA_NONE -> "WDA_NONE (0x00000000) - not protected";
            case User32Extended.WDA_MONITOR -> "WDA_MONITOR (0x00000001) - monitor only";
            case User32Extended.WDA_EXCLUDEFROMCAPTURE ->
                "WDA_EXCLUDEFROMCAPTURE (0x00000011) - excluded from capture";
            case -1 -> "unknown (the affinity could not be read)";
            default -> "0x" + Integer.toHexString((int) affinity) + " (unrecognised)";
        };
    }
}

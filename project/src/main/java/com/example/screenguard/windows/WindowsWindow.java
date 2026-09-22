package com.example.screenguard.windows;

import java.util.Locale;
import java.util.Objects;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Immutable snapshot of one top-level Windows window.
 *
 * <p>Instances are created by {@link WindowEnumerator} while it walks the desktop. Because a
 * window can disappear at any moment, every value here is a snapshot: this class never touches
 * the native window again.</p>
 */
public final class WindowsWindow {

    /** Placeholder used when the executable name of a process could not be resolved. */
    public static final String UNKNOWN_EXECUTABLE = "unknown";

    private final HWND hwnd;
    private final long hwndValue;
    private final int processId;
    private final String title;
    private final String className;
    private final String executableName;
    private final String executablePath;
    private final long displayAffinity;

    private WindowsWindow(Builder builder) {
        this.hwnd = builder.hwnd;
        this.hwndValue = builder.hwndValue;
        this.processId = builder.processId;
        this.title = builder.title == null ? "" : builder.title;
        this.className = builder.className == null ? "" : builder.className;
        this.executableName = builder.executableName == null || builder.executableName.isBlank()
                ? UNKNOWN_EXECUTABLE
                : builder.executableName;
        this.executablePath = builder.executablePath == null ? "" : builder.executablePath;
        this.displayAffinity = builder.displayAffinity;
    }

    static Builder builder() {
        return new Builder();
    }

    /** @return the native window handle. */
    public HWND getHwnd() {
        return hwnd;
    }

    /** @return the numeric value of the window handle (useful for logging and comparisons). */
    public long getHwndValue() {
        return hwndValue;
    }

    /** @return the window handle formatted as {@code 0x0000000000000000}. */
    public String getHwndHex() {
        return String.format("0x%016X", hwndValue);
    }

    /** @return identifier of the process that owns the window. */
    public int getProcessId() {
        return processId;
    }

    /** @return the window caption as read from {@code GetWindowTextW}. */
    public String getTitle() {
        return title;
    }

    /** @return the Win32 window class name (for example {@code Chrome_WidgetWin_1}). */
    public String getClassName() {
        return className;
    }

    /** @return the executable file name (for example {@code chrome.exe}). */
    public String getExecutableName() {
        return executableName;
    }

    /** @return the full executable path, or an empty string when it could not be resolved. */
    public String getExecutablePath() {
        return executablePath;
    }

    /** @return the display affinity that was active when this snapshot was taken. */
    public long getDisplayAffinity() {
        return displayAffinity;
    }

    /** @return {@code true} when the window is currently excluded from capture. */
    public boolean isCaptureProtected() {
        return displayAffinity == User32Extended.WDA_EXCLUDEFROMCAPTURE;
    }

    /** @return the caption when it is usable, otherwise the executable name. */
    public String getDisplayName() {
        return title.isBlank() ? executableName : title;
    }

    /** @return a lower-case key used by the search box. */
    public String searchKey() {
        return (title + '\u0000' + executableName + '\u0000' + processId).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WindowsWindow that)) {
            return false;
        }
        return hwndValue == that.hwndValue && processId == that.processId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hwndValue, processId);
    }

    @Override
    public String toString() {
        return "WindowsWindow[hwnd=" + getHwndHex()
                + ", title=\"" + title + '"'
                + ", exe=" + executableName
                + ", pid=" + processId
                + ", affinity=" + displayAffinity
                + ']';
    }

    /** @return a copy of this snapshot with the process details filled in. */
    WindowsWindow withProcessDetails(String executable, String path) {
        return copy(executableOrUnknown(executable), path, displayAffinity);
    }

    /** @return a copy of this snapshot with a freshly read display affinity. */
    public WindowsWindow withDisplayAffinity(long affinity) {
        return copy(executableName, executablePath, affinity);
    }

    private static String executableOrUnknown(String executable) {
        return executable == null || executable.isBlank() ? UNKNOWN_EXECUTABLE : executable;
    }

    private WindowsWindow copy(String executable, String path, long affinity) {
        return builder()
                .hwnd(hwnd)
                .hwndValue(hwndValue)
                .processId(processId)
                .title(title)
                .className(className)
                .executableName(executable)
                .executablePath(path)
                .displayAffinity(affinity)
                .build();
    }

    /** Small mutable builder used while a snapshot is assembled. */
    static final class Builder {
        private HWND hwnd;
        private long hwndValue;
        private int processId;
        private String title = "";
        private String className = "";
        private String executableName = "";
        private String executablePath = "";
        private long displayAffinity = -1L;

        Builder hwnd(HWND value) {
            this.hwnd = value;
            this.hwndValue = User32Extended.handleValue(value);
            return this;
        }

        Builder hwndValue(long value) {
            this.hwndValue = value;
            if (this.hwnd == null) {
                this.hwnd = new HWND(new Pointer(value));
            }
            return this;
        }

        Builder processId(int value) {
            this.processId = value;
            return this;
        }

        Builder title(String value) {
            this.title = value;
            return this;
        }

        Builder className(String value) {
            this.className = value;
            return this;
        }

        Builder executableName(String value) {
            this.executableName = value;
            return this;
        }

        Builder executablePath(String value) {
            this.executablePath = value;
            return this;
        }

        Builder displayAffinity(long value) {
            this.displayAffinity = value;
            return this;
        }

        WindowsWindow build() {
            return new WindowsWindow(this);
        }
    }
}

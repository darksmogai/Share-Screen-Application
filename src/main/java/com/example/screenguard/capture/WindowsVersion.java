package com.example.screenguard.capture;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sun.jna.platform.win32.WinReg.HKEYByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows version information used to explain feature availability.
 *
 * <p>The build number is read from the read-only registry value
 * {@code HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\CurrentBuildNumber}, because
 * {@code GetVersionEx}/{@code GetVersion} report a compatibility-shimmed version for
 * unmanifested applications.</p>
 *
 * @param major major version (10 on Windows 10 and 11)
 * @param minor minor version (0)
 * @param build build number, or {@code -1} when it could not be read
 */
public record WindowsVersion(int major, int minor, int build) {

    private static final Logger LOG = Logger.getLogger(WindowsVersion.class.getName());

    private static final String KEY_PATH = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
    private static final String BUILD_VALUE = "CurrentBuildNumber";

    /** {@code KEY_READ} from winnt.h. */
    private static final int KEY_READ = 0x20019;

    /** @return {@code true} when the build number could be determined. */
    public boolean buildIsKnown() {
        return build > 0;
    }

    /** @return {@code true} when this is Windows 10 or Windows 11. */
    public boolean isWindows10OrLater() {
        return major >= 10;
    }

    /** @return a one-line description such as {@code Windows 10.0 build 19045}. */
    public String summary() {
        if (buildIsKnown()) {
            return "Windows " + major + "." + minor + " build " + build;
        }
        return "Windows " + major + "." + minor + " (build number unavailable)";
    }

    /**
     * Detects the running Windows version.
     *
     * @return the detected version; the build is {@code -1} when the registry value cannot be read
     */
    public static WindowsVersion current() {
        int major = 0;
        int minor = 0;
        int build = -1;
        try {
            HKEYByReference key = new HKEYByReference();
            int result = Advapi32.INSTANCE.RegOpenKeyEx(
                    WinReg.HKEY_LOCAL_MACHINE, KEY_PATH, 0, KEY_READ, key);
            if (result == 0) {
                HKEY handle = key.getValue();
                try {
                    String buildText = readString(handle, BUILD_VALUE);
                    if (buildText != null) {
                        build = Integer.parseInt(buildText.trim());
                    }
                    major = readInt(handle, "CurrentMajorVersionNumber", major);
                    minor = readInt(handle, "CurrentMinorVersionNumber", minor);
                } finally {
                    Advapi32.INSTANCE.RegCloseKey(handle);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the Windows version from the registry", e);
        }
        if (major == 0) {
            major = 10;
        }
        return new WindowsVersion(major, minor, build);
    }

    private static String readString(HKEY key, String valueName) {
        char[] buffer = new char[64];
        IntByReference length = new IntByReference(buffer.length * 2);
        IntByReference type = new IntByReference();
        int result = Advapi32.INSTANCE.RegQueryValueEx(key, valueName, 0, type, buffer, length);
        if (result != 0) {
            return null;
        }
        // lpcbData is reported in bytes and includes the terminating NUL.
        int chars = Math.max(0, (length.getValue() / 2) - 1);
        return new String(buffer, 0, chars);
    }

    private static int readInt(HKEY key, String valueName, int fallback) {
        IntByReference value = new IntByReference(fallback);
        IntByReference type = new IntByReference();
        int result = Advapi32.INSTANCE.RegQueryValueEx(key, valueName, 0, type, value, null);
        return result == 0 ? value.getValue() : fallback;
    }
}

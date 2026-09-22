package com.example.screenguard.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sun.jna.platform.win32.WinReg.HKEYByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * Application settings.
 *
 * <p>Preferences are stored in {@code %APPDATA%\ScreenGuard\settings.properties}. The optional
 * "start with Windows" switch uses the documented, user-visible per-user Run key
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run} - the same mechanism the Windows
 * Task Manager shows under "Startup apps". It is not disguised, not hidden and can be switched
 * off from the UI at any time.</p>
 */
public final class AppSettings {

    private static final Logger LOG = Logger.getLogger(AppSettings.class.getName());

    /** Name of the registry value used for the startup entry. */
    public static final String RUN_VALUE_NAME = "ScreenGuard";

    /** The standard per-user startup key (documented by Microsoft). */
    public static final String RUN_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private static final String SHOW_WINDOW_KEY = "ui.showWindowOnStartup";
    private static final String LOG_VERBOSE_KEY = "logging.verbose";

    /** Access masks from winnt.h. */
    private static final int KEY_QUERY_VALUE = 0x0001;
    private static final int KEY_SET_VALUE = 0x0002;

    /** Registry value types from winnt.h. */
    private static final int REG_SZ = 1;

    /** {@code ERROR_FILE_NOT_FOUND}: returned by {@code RegDeleteValue} when there is nothing to delete. */
    private static final int ERROR_FILE_NOT_FOUND = 2;

    private final Path settingsFile;
    private final Properties properties;

    private AppSettings(Path settingsFile, Properties properties) {
        this.settingsFile = settingsFile;
        this.properties = properties;
    }

    /**
     * Loads the settings of the current user, creating defaults when nothing is stored yet.
     *
     * @return the loaded settings
     */
    public static AppSettings load() {
        return forFile(defaultSettingsFile());
    }

    /**
     * Loads settings from an explicit file (used by tests).
     *
     * @param settingsFile location of the properties file
     * @return the loaded settings
     */
    public static AppSettings forFile(Path settingsFile) {
        Properties properties = new Properties();
        if (settingsFile != null && Files.isReadable(settingsFile)) {
            try (InputStream in = Files.newInputStream(settingsFile)) {
                properties.load(in);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read " + settingsFile, e);
            }
        }
        return new AppSettings(settingsFile, properties);
    }

    /** @return {@code %APPDATA%\ScreenGuard\settings.properties} */
    public static Path defaultSettingsFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Paths.get(appData)
                : Paths.get(System.getProperty("user.home", "."));
        return base.resolve("ScreenGuard").resolve("settings.properties");
    }

    /** @return the file the settings are stored in. */
    public Path getSettingsFile() {
        return settingsFile;
    }

    /** @return {@code true} when the main window should be shown at startup. */
    public boolean isShowWindowOnStartup() {
        return Boolean.parseBoolean(properties.getProperty(SHOW_WINDOW_KEY, "false"));
    }

    /**
     * Stores whether the main window should be shown at startup.
     *
     * @param show whether to show the window when ScreenGuard starts
     */
    public void setShowWindowOnStartup(boolean show) {
        properties.setProperty(SHOW_WINDOW_KEY, Boolean.toString(show));
    }

    /** @return {@code true} when verbose (FINE level) logging is enabled. */
    public boolean isVerboseLogging() {
        return Boolean.parseBoolean(properties.getProperty(LOG_VERBOSE_KEY, "false"));
    }

    /**
     * Stores the verbose logging flag.
     *
     * @param verbose whether FINE level messages should be logged
     */
    public void setVerboseLogging(boolean verbose) {
        properties.setProperty(LOG_VERBOSE_KEY, Boolean.toString(verbose));
    }

    // ------------------------------------------------------------------
    // Standard per-user startup entry (HKCU\...\Run)
    // ------------------------------------------------------------------

    /** @return {@code true} when a startup entry for ScreenGuard exists. */
    public boolean isStartWithWindowsEnabled() {
        return readRunValue() != null;
    }

    /** @return the command line currently registered for startup, or {@code null}. */
    public String getRegisteredStartupCommand() {
        return readRunValue();
    }

    /**
     * Creates or removes the startup entry.
     *
     * @param enabled {@code true} to register ScreenGuard for the next logon
     * @return {@code true} when the registry change succeeded
     */
    public boolean setStartWithWindowsEnabled(boolean enabled) {
        String command = startupCommand();
        if (enabled && command == null) {
            LOG.warning("Cannot register ScreenGuard for startup: the launch command is unknown.");
            return false;
        }
        boolean ok = enabled ? writeRunValue(command) : deleteRunValue();
        if (ok) {
            LOG.info(enabled
                    ? "Registered ScreenGuard in HKCU\\...\\Run as: " + command
                    : "Removed the ScreenGuard startup entry from HKCU\\...\\Run");
        }
        return ok;
    }

    /**
     * Builds the command line that starts ScreenGuard in the background.
     *
     * @return the command, or {@code null} when it cannot be derived
     */
    public String startupCommand() {
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) {
            return quote(packaged) + " --tray";
        }
        String launcher = System.getProperty("java.home") + "\\bin\\javaw.exe";
        String jar = currentJar();
        if (jar != null) {
            return quote(launcher) + " -jar " + quote(jar) + " --tray";
        }
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            return quote(launcher) + " -cp " + quote(classpath)
                    + " com.example.screenguard.Main --tray";
        }
        return null;
    }

    private static String currentJar() {
        try {
            java.security.CodeSource source = AppSettings.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path location = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(location) ? location.toString() : null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not determine the running jar", e);
            return null;
        }
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }

    /** Writes the settings file, creating the directory when necessary. */
    public void save() {
        if (settingsFile == null) {
            return;
        }
        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(settingsFile)) {
                properties.store(out, "ScreenGuard settings");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save " + settingsFile, e);
        }
    }

    private String readRunValue() {
        HKEYByReference key = new HKEYByReference();
        int result = Advapi32.INSTANCE.RegOpenKeyEx(
                WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, KEY_QUERY_VALUE, key);
        if (result != 0) {
            LOG.log(Level.FINE, "Could not open the Run key (Win32 error {0})", result);
            return null;
        }
        HKEY handle = key.getValue();
        try {
            char[] buffer = new char[8192];
            IntByReference length = new IntByReference(buffer.length * 2);
            IntByReference type = new IntByReference();
            result = Advapi32.INSTANCE.RegQueryValueEx(
                    handle, RUN_VALUE_NAME, 0, type, buffer, length);
            if (result != 0) {
                return null;
            }
            int chars = Math.max(0, (length.getValue() / 2) - 1);
            return new String(buffer, 0, chars);
        } finally {
            Advapi32.INSTANCE.RegCloseKey(handle);
        }
    }

    private boolean writeRunValue(String command) {
        HKEYByReference key = new HKEYByReference();
        int result = Advapi32.INSTANCE.RegOpenKeyEx(
                WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, KEY_SET_VALUE, key);
        if (result != 0) {
            LOG.log(Level.WARNING, "Could not open the Run key for writing (Win32 error {0})", result);
            return false;
        }
        HKEY handle = key.getValue();
        try {
            char[] data = command.toCharArray();
            result = Advapi32.INSTANCE.RegSetValueEx(
                    handle, RUN_VALUE_NAME, 0, REG_SZ, data, (data.length * 2) + 2);
            if (result != 0) {
                LOG.log(Level.WARNING, "RegSetValueEx failed (Win32 error {0})", result);
                return false;
            }
            return true;
        } finally {
            Advapi32.INSTANCE.RegCloseKey(handle);
        }
    }

    private boolean deleteRunValue() {
        HKEYByReference key = new HKEYByReference();
        int result = Advapi32.INSTANCE.RegOpenKeyEx(
                WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, KEY_SET_VALUE, key);
        if (result != 0) {
            LOG.log(Level.WARNING, "Could not open the Run key for writing (Win32 error {0})", result);
            return false;
        }
        HKEY handle = key.getValue();
        try {
            result = Advapi32.INSTANCE.RegDeleteValue(handle, RUN_VALUE_NAME);
            if (result != 0 && result != ERROR_FILE_NOT_FOUND) {
                LOG.log(Level.WARNING, "RegDeleteValue failed (Win32 error {0})", result);
                return false;
            }
            return true;
        } finally {
            Advapi32.INSTANCE.RegCloseKey(handle);
        }
    }
}

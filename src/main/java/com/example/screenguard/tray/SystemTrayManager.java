package com.example.screenguard.tray;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

/**
 * Owns the Windows notification-area icon and its menu.
 *
 * <p>The tray icon is the primary entry point of ScreenGuard: the application starts hidden and
 * can be operated without opening the main window.</p>
 */
public final class SystemTrayManager {

    private static final Logger LOG = Logger.getLogger(SystemTrayManager.class.getName());

    /** Menu title shown at the top of the tray menu. */
    public static final String MENU_TITLE = "ScreenGuard";

    /** Callbacks invoked from the tray menu; they run on the AWT event dispatch thread. */
    public interface TrayActions {

        /** Protect the window that is currently selected in the UI. */
        void onProtectWindow();

        /** Remove protection from the window that is currently selected in the UI. */
        void onUnprotectWindow();

        /** Show the main window on the settings/help section. */
        void onOpenSettings();

        /** Bring the main window to the front. */
        void onShowWindow();

        /** Terminate ScreenGuard. */
        void onExit();
    }

    private TrayIcon trayIcon;
    private PopupMenu popupMenu;
    private MenuItem protectItem;
    private MenuItem unprotectItem;
    private boolean installed;

    /** @return {@code true} when the platform provides a notification area. */
    public boolean isSupported() {
        try {
            return !java.awt.GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (RuntimeException | Error e) {
            LOG.log(Level.WARNING, "The system tray is not usable on this system", e);
            return false;
        }
    }

    /** @return {@code true} when the icon is currently installed. */
    public boolean isInstalled() {
        return installed;
    }

    /**
     * Creates the tray icon and its menu.
     *
     * @param actions callbacks for the menu entries
     * @return {@code true} when the icon was installed
     */
    public boolean install(TrayActions actions) {
        if (installed) {
            return true;
        }
        if (!isSupported()) {
            LOG.warning("System tray not supported - the main window will be used instead.");
            return false;
        }
        runOnEventDispatchThread(() -> installOnDispatchThread(actions));
        return installed;
    }

    /**
     * Updates the tooltip and enables/disables the protect entries.
     *
     * @param tooltip          text shown when hovering the icon
     * @param protectEnabled   whether "Protect Window" can be used
     * @param unprotectEnabled whether "Unprotect Window" can be used
     */
    public void updateState(String tooltip, boolean protectEnabled, boolean unprotectEnabled) {
        if (!installed) {
            return;
        }
        runOnEventDispatchThread(() -> {
            if (trayIcon != null) {
                trayIcon.setToolTip(tooltip);
            }
            if (protectItem != null) {
                protectItem.setEnabled(protectEnabled);
            }
            if (unprotectItem != null) {
                unprotectItem.setEnabled(unprotectEnabled);
            }
        });
    }

    /** Removes the tray icon. */
    public void remove() {
        if (!installed) {
            return;
        }
        runOnEventDispatchThread(() -> {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
            popupMenu = null;
            protectItem = null;
            unprotectItem = null;
            installed = false;
            LOG.info("System tray icon removed.");
        });
    }

    private void installOnDispatchThread(TrayActions actions) {
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem title = new MenuItem(MENU_TITLE);
            title.setEnabled(false);
            menu.add(title);
            menu.addSeparator();

            protectItem = new MenuItem("Protect Window");
            protectItem.addActionListener(e -> actions.onProtectWindow());
            menu.add(protectItem);

            unprotectItem = new MenuItem("Unprotect Window");
            unprotectItem.addActionListener(e -> actions.onUnprotectWindow());
            menu.add(unprotectItem);

            menu.addSeparator();
            MenuItem settings = new MenuItem("Open Settings");
            settings.addActionListener(e -> actions.onOpenSettings());
            menu.add(settings);

            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> actions.onExit());
            menu.add(exit);

            TrayIcon icon = new TrayIcon(loadTrayImage(),
                    "ScreenGuard - screen capture privacy", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> actions.onShowWindow());

            SystemTray.getSystemTray().add(icon);
            this.popupMenu = menu;
            this.trayIcon = icon;
            this.installed = true;
            LOG.info("System tray icon installed.");
        } catch (AWTException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not install the system tray icon", e);
        }
    }

    private static void runOnEventDispatchThread(Runnable task) {
        if (EventQueue.isDispatchThread()) {
            task.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Interrupted while updating the tray icon", e);
        } catch (InvocationTargetException e) {
            LOG.log(Level.WARNING, "Tray icon update failed", e.getCause());
        }
    }

    /**
     * Loads the tray image from the bundled resources.
     *
     * @return the icon, or a generated placeholder when the resource is missing
     */
    private static Image loadTrayImage() {
        for (String candidate : new String[] {"/icons/screenguard-32.png", "/icons/screenguard-16.png"}) {
            try (InputStream in = SystemTrayManager.class.getResourceAsStream(candidate)) {
                if (in != null) {
                    Image image = ImageIO.read(in);
                    if (image != null) {
                        return image;
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not read tray icon " + candidate, e);
            }
        }
        LOG.warning("Tray icon resources are missing; using a generated placeholder icon.");
        BufferedImage fallback = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = fallback.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x1F6FEB));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(java.awt.Color.WHITE);
        g.drawLine(8, 3, 8, 9);
        g.fillOval(7, 10, 3, 3);
        g.dispose();
        return fallback;
    }
}

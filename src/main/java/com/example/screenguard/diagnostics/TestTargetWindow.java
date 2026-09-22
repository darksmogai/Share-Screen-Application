package com.example.screenguard.diagnostics;

import java.awt.EventQueue;
import java.awt.Frame;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.example.screenguard.windows.WindowsWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * Creates a real top-level window that belongs to the running process.
 *
 * <p>It is the deterministic target for the self test and for the JUnit tests: the handle of the
 * window is looked up through the normal enumeration path, so the tests exercise exactly the code
 * path a user would use.</p>
 *
 * <p>Because {@code SetWindowDisplayAffinity} only works for windows of the calling process, this
 * window is also the only target for which protection is expected to succeed.</p>
 */
public final class TestTargetWindow implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TestTargetWindow.class.getName());

    private final String title;
    private Frame frame;

    private TestTargetWindow(String title) {
        this.title = title;
    }

    /**
     * Creates and shows a visible top-level window.
     *
     * @param title unique caption of the window
     * @return the handle to the test window
     * @throws IllegalStateException when the AWT window could not be created (headless system)
     */
    public static TestTargetWindow create(String title) {
        TestTargetWindow target = new TestTargetWindow(title);
        try {
            EventQueue.invokeAndWait(() -> {
                target.frame = new Frame(title);
                target.frame.setSize(320, 200);
                target.frame.setLocation(40, 40);
                target.frame.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the test window", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Could not create the AWT test window", e.getCause());
        }
        return target;
    }

    /** @return the caption of the test window. */
    public String title() {
        return title;
    }

    /**
     * Waits until the window shows up in the enumeration.
     *
     * @param windowManager manager used for the lookup
     * @param timeoutMillis maximum time to wait
     * @return the window snapshot
     * @throws IllegalStateException when the window did not appear in time
     */
    public Optional<WindowsWindow> findWindow(WindowsWindowManager windowManager, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Optional<WindowsWindow> found = windowManager.findOwnWindowByTitle(title);
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** @return {@code true} while the AWT window is still showing. */
    public boolean isShowing() {
        Frame snapshot = frame;
        return snapshot != null && snapshot.isShowing();
    }

    /** Closes and disposes the window. */
    @Override
    public void close() {
        Frame snapshot = frame;
        if (snapshot == null) {
            return;
        }
        try {
            EventQueue.invokeAndWait(() -> {
                snapshot.setVisible(false);
                snapshot.dispose();
            });
            frame = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.FINE, "Interrupted while closing the test window", e);
        } catch (InvocationTargetException e) {
            LOG.log(Level.FINE, "Error while closing the test window", e.getCause());
        }
    }
}

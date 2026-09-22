package com.example.screenguard;

import java.util.logging.Logger;

/**
 * Entry point that only forwards to {@link Main}.
 *
 * <p>Since Java 11 a JavaFX application whose main class extends {@code Application} cannot be
 * started from a plain JAR on the classpath ("JavaFX runtime components are missing"). This small
 * launcher does not extend {@code Application}, which makes {@code java -jar screenguard.jar},
 * {@code java -cp ...} and jpackage work with the module system switched off.</p>
 */
public final class Launcher {

    private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

    private Launcher() {
    }

    /**
     * Forwards to {@link Main#main(String[])}.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        try {
            Main.main(args);
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.SEVERE, "ScreenGuard terminated with an error", t);
            throw t;
        }
    }
}

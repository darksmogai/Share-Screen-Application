import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.DWORDByReference;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.ptr.IntByReference;

/**
 * Throw-away probe: checks whether SetWindowDisplayAffinity works on a window that belongs to
 * another process, and validates the JNA mapping used by ScreenGuard.
 *
 * <pre>java -cp "jna.jar;jna-platform.jar" tools/ProbeAffinity.java</pre>
 */
public final class ProbeAffinity {

    interface U32 extends com.sun.jna.win32.StdCallLibrary {
        U32 I = Native.load("user32", U32.class);

        interface EnumWindowsProc extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
            boolean callback(HWND hwnd, LPARAM lParam);
        }

        boolean EnumWindows(EnumWindowsProc cb, LPARAM lParam);

        boolean IsWindowVisible(HWND hwnd);

        int GetWindowTextLengthW(HWND hwnd);

        int GetWindowTextW(HWND hwnd, char[] buf, int max);

        int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);

        boolean SetWindowDisplayAffinity(HWND hwnd, DWORD affinity);

        boolean GetWindowDisplayAffinity(HWND hwnd, DWORDByReference out);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " arch=" + Platform.ARCH + " jna=" + Native.VERSION);
        System.out.println("own pid=" + Kernel32.INSTANCE.GetCurrentProcessId());

        // 1. Enumeration via JNA callback (also validates LPARAM / HWND / char[] mapping).
        List<Object[]> all = new ArrayList<>();
        U32.I.EnumWindows((hwnd, lParam) -> {
            if (U32.I.IsWindowVisible(hwnd)) {
                int len = U32.I.GetWindowTextLengthW(hwnd);
                char[] buf = new char[Math.max(1, len) + 1];
                int copied = U32.I.GetWindowTextW(hwnd, buf, buf.length);
                String title = copied <= 0 ? "" : new String(buf, 0, copied);
                IntByReference pid = new IntByReference(0);
                U32.I.GetWindowThreadProcessId(hwnd, pid);
                all.add(new Object[] {hwnd, title, pid.getValue()});
            }
            return true;
        }, new LPARAM(0));
        System.out.println("visible top-level windows=" + all.size());
        all.stream().filter(w -> !((String) w[1]).isBlank()).limit(8)
                .forEach(w -> System.out.println("   " + ((HWND) w[0]) + " pid=" + w[2]
                        + " title='" + w[1] + "'"));

        // 2. A window owned by this process: must succeed.
        java.awt.Frame own = new java.awt.Frame("ScreenGuard probe frame");
        own.setSize(320, 200);
        own.setVisible(true);
        Thread.sleep(1200);
        HWND ownHwnd = findByTitle(refresh(), "ScreenGuard probe frame");

        // 3. A window owned by another process: behaviour to determine.
        Process notepad = new ProcessBuilder("notepad.exe").start();
        Thread.sleep(1500);
        HWND foreignHwnd = null;
        for (int attempt = 0; attempt < 20 && foreignHwnd == null; attempt++) {
            foreignHwnd = findForeign(all, notepad.pid());
            if (foreignHwnd == null) {
                Thread.sleep(500);
                all.clear();
                U32.I.EnumWindows((hwnd, lParam) -> {
                    int len = U32.I.GetWindowTextLengthW(hwnd);
                    char[] buf = new char[Math.max(1, len) + 1];
                    int copied = U32.I.GetWindowTextW(hwnd, buf, buf.length);
                    IntByReference pid = new IntByReference(0);
                    U32.I.GetWindowThreadProcessId(hwnd, pid);
                    all.add(new Object[] {hwnd, copied <= 0 ? "" : new String(buf, 0, copied), pid.getValue()});
                    return true;
                }, new LPARAM(0));
                foreignHwnd = findForeign(all, notepad.pid());
            }
        }
        System.out.println("notepad hwnd=" + foreignHwnd + " (pid " + notepad.pid() + ")");

        probe("OWN window   ", ownHwnd);
        probe("FOREIGN window", foreignHwnd);

        if (foreignHwnd != null) {
            Kernel32.INSTANCE.SetLastError(0);
        }
        notepad.destroy();
        own.dispose();
        System.exit(0);
    }

    private static void probe(String label, HWND hwnd) {
        if (hwnd == null) {
            System.out.println(label + ": window not found, skipped");
            return;
        }
        Kernel32.INSTANCE.SetLastError(0);
        boolean ok = U32.I.SetWindowDisplayAffinity(hwnd, new DWORD(0x00000002));
        int err = Kernel32.INSTANCE.GetLastError();
        DWORDByReference out = new DWORDByReference(new DWORD(0));
        boolean readOk = U32.I.GetWindowDisplayAffinity(hwnd, out);
        System.out.println(label + ": SetWDA(EXCLUDEFROMCAPTURE)=" + ok + " err=" + err
                + " | GetWDA=" + readOk + " value=" + (readOk ? out.getValue().longValue() : -1));
        if (ok) {
            U32.I.SetWindowDisplayAffinity(hwnd, new DWORD(0x00000000));
        }
    }

    private static List<Object[]> refresh() {
        List<Object[]> windows = new ArrayList<>();
        U32.I.EnumWindows((hwnd, lParam) -> {
            if (U32.I.IsWindowVisible(hwnd)) {
                int len = U32.I.GetWindowTextLengthW(hwnd);
                char[] buf = new char[Math.max(1, len) + 1];
                int copied = U32.I.GetWindowTextW(hwnd, buf, buf.length);
                IntByReference pid = new IntByReference(0);
                U32.I.GetWindowThreadProcessId(hwnd, pid);
                windows.add(new Object[] {hwnd, copied <= 0 ? "" : new String(buf, 0, copied), pid.getValue()});
            }
            return true;
        }, new LPARAM(0));
        return windows;
    }

    private static HWND findByTitle(List<Object[]> windows, String title) {
        for (Object[] w : windows) {
            if (title.equalsIgnoreCase(((String) w[1]).trim())) {
                return (HWND) w[0];
            }
        }
        return null;
    }

    private static HWND findForeign(List<Object[]> windows, long pid) {
        for (Object[] w : windows) {
            if (((Integer) w[2]).longValue() == pid) {
                return (HWND) w[0];
            }
        }
        return null;
    }
}

package com.example.screenguard.capture;

import java.util.Map;

/** Small, dependency-free translation table for the Win32 error codes ScreenGuard can produce. */
final class Win32ErrorText {

    private static final Map<Integer, String> MESSAGES = Map.ofEntries(
            Map.entry(0, "the operation completed successfully"),
            Map.entry(5, "ERROR_ACCESS_DENIED - access is denied"),
            Map.entry(6, "ERROR_INVALID_HANDLE - the handle is invalid"),
            Map.entry(8, "ERROR_NOT_ENOUGH_MEMORY - not enough storage is available"),
            Map.entry(14, "ERROR_OUTOFMEMORY - not enough storage is available"),
            Map.entry(18, "ERROR_NO_MORE_FILES - there are no more files"),
            Map.entry(50, "ERROR_NOT_SUPPORTED - the request is not supported"),
            Map.entry(87, "ERROR_INVALID_PARAMETER - the parameter is incorrect"),
            Map.entry(122, "ERROR_INSUFFICIENT_BUFFER - the data area is too small"),
            Map.entry(1400, "ERROR_INVALID_WINDOW_HANDLE - invalid window handle"),
            Map.entry(1413, "ERROR_INVALID_INDEX - invalid index"),
            Map.entry(1461, "ERROR_INVALID_MONITOR_HANDLE - invalid monitor handle"));

    private Win32ErrorText() {
    }

    /**
     * @param errorCode value returned by {@code GetLastError()}
     * @return a readable description, falling back to a generic text for unknown codes
     */
    static String describe(int errorCode) {
        String known = MESSAGES.get(errorCode);
        return known != null ? known : "unknown Win32 error";
    }
}

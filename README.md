# ScreenGuard

ScreenGuard is a Windows screen-sharing privacy utility. It opens a Chromium-based Microsoft Edge WebView2 browser, automatically protects that browser window from supported screen-capture APIs, and keeps the controller in the system tray.

## Features

- Protected Edge browser for modern websites, including ChatGPT.
- Automatic capture protection when the browser opens.
- No CMD window required for the packaged application.
- Runs in the background with a system-tray controller.
- Uses the documented Windows `SetWindowDisplayAffinity` API.
- Does not capture, record, upload, or inject into other applications.

## Requirements

- Windows 10 version 2004/build 19041 or later, or Windows 11.
- Microsoft Edge WebView2 Runtime.
- Java 21 and Maven 3.9+ for development builds.
- .NET 8 SDK for rebuilding the Edge helper.

## Run the packaged application

The packaged application is created at:

```text
target/exe/ScreenGuard/ScreenGuard.exe
```

Keep the complete `target/exe/ScreenGuard` folder together and double-click `ScreenGuard.exe`. The app starts the protected Edge browser automatically; no terminal is required.

## Use

1. Start `ScreenGuard.exe`.
2. The protected Edge browser opens automatically.
3. Browse normally using the address bar.
4. The browser is protected automatically before navigation begins.
5. Share your screen using a capture tool that respects Windows display affinity.

ScreenGuard itself remains in the notification area. The main settings window can be opened from the tray menu when needed.

## Build from source

Build the native Edge WebView2 helper first:

```powershell
cd edge-helper
dotnet publish EdgeProtectedBrowser.csproj -c Release -r win-x64 --self-contained false
cd ..
```

Build the Java application:

```bat
mvn package
```

Run the packaged application by opening:

```text
target/exe/ScreenGuard/ScreenGuard.exe
```

The helper uses the Microsoft WebView2 Runtime and the Edge Chromium engine.

## How protection works

The Edge helper owns its native browser window and calls:

```text
SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)
```

When a compatible capture API sees the protected window, its content should appear blank or excluded from the captured output while remaining visible locally.

## Limitations

- Capture software must honor Windows display affinity. Some Teams, OBS, browser, hardware, remote-desktop, or driver-level capture paths may ignore it.
- Protection applies to the ScreenGuard-owned Edge browser. It cannot directly protect an already-open Chrome, Edge, Firefox, Teams, or Notepad window owned by another process.
- This project does not bypass DRM, monitoring, security software, or application-level protection.
- Display affinity is not a guarantee against cameras or capture paths that do not use the Windows display pipeline.

## Project structure

```text
src/main/java/                 JavaFX controller and Windows protection service
edge-helper/                   Native .NET 8 Edge WebView2 helper
target/exe/ScreenGuard/        Packaged Windows application
pom.xml                        Java/Maven build configuration
```

## Tests

Run the Java test suite with:

```bat
mvn test
```

The tests cover window enumeration, display-affinity enable/disable, read-back verification, invalid handles, closed windows, and foreign-window refusal.

## Privacy

ScreenGuard does not take screenshots, record the desktop, upload browsing data, modify other applications, or inject code into other processes. The embedded Edge browser is a normal WebView2 browser and websites loaded inside it retain their own network and privacy behavior.

## License

See the source files for license information.

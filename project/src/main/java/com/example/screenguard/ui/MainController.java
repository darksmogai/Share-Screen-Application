package com.example.screenguard.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;

import com.example.screenguard.capture.CaptureProtectionService;
import com.example.screenguard.capture.CaptureProtectionService.ProtectionResult;
import com.example.screenguard.capture.CaptureProtectionService.Status;
import com.example.screenguard.capture.WindowsVersion;
import com.example.screenguard.config.AppSettings;
import com.example.screenguard.tray.SystemTrayManager;
import com.example.screenguard.windows.User32Extended;
import com.example.screenguard.windows.WindowsWindow;
import com.example.screenguard.windows.WindowsWindowManager;

/**
 * Behaviour of the main window: it builds the JavaFX content, drives the window list and calls
 * the capture-protection service.
 *
 * <p>All UI mutation happens on the JavaFX application thread; the (potentially slower) window
 * enumeration runs on a single background worker so the window stays responsive.</p>
 */
public final class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    /** Application level actions the controller needs but does not own. */
    public interface UiActions {

        /** Hide the main window (ScreenGuard keeps running in the tray). */
        void hideToTray();

        /** Bring the main window to the front. */
        void showWindow();

        /** Terminate the application. */
        void exitApplication();

        /** Open the ScreenGuard-owned preview window. */
        void openProtectedPreview();
    }

    /** Visual kind of the status indicator. */
    private enum StatusKind {
        PROTECTED,
        UNPROTECTED,
        WARNING,
        ERROR,
        BUSY
    }

    private final WindowsWindowManager windowManager;
    private final CaptureProtectionService protectionService;
    private final AppSettings settings;
    private final SystemTrayManager trayManager;
    private final UiActions actions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "screenguard-enumeration");
        thread.setDaemon(true);
        return thread;
    });

    private final ObservableList<WindowsWindow> allWindows = FXCollections.observableArrayList();
    private final FilteredList<WindowsWindow> visibleWindows = new FilteredList<>(allWindows);

    private final TableView<WindowsWindow> windowTable = new TableView<>(visibleWindows);
    private final TextField searchField = new TextField();
    private final Button refreshButton = new Button("Refresh");
    private final Button protectButton = new Button("Protect Window");
    private final Button unprotectButton = new Button("Remove Protection");
    private final Button previewButton = new Button("Open protected preview window");
    private final Button hideButton = new Button("Hide to tray");
    private final Button exitButton = new Button("Exit ScreenGuard");
    private final CheckBox startWithWindowsCheck = new CheckBox("Start ScreenGuard with Windows");
    private final CheckBox showOnStartupCheck =
            new CheckBox("Show the main window when ScreenGuard starts");

    private final Circle statusDot = new Circle(6);
    private final Label statusLabel = new Label("No window selected");
    private final Label messageLabel = new Label("");
    private final Label selectionLabel = new Label("None");
    private final Label windowCountLabel = new Label("");
    private final Label affinityLabel = new Label("-");
    private final Label handleLabel = new Label("-");

    private final TitledPane settingsPane = new TitledPane();
    private final TitledPane helpPane = new TitledPane();
    private final Accordion accordion = new Accordion();

    private WindowsWindow selectedWindow;
    private boolean refreshing;
    private boolean windowHiddenNoticeShown;
    private boolean lastActionNeededAttention;

    /**
     * @param windowManager     supplies the window list and validation
     * @param protectionService applies the display affinity
     * @param settings          persisted preferences
     * @param trayManager       notification-area icon
     * @param actions           application level actions
     */
    public MainController(WindowsWindowManager windowManager,
            CaptureProtectionService protectionService,
            AppSettings settings,
            SystemTrayManager trayManager,
            UiActions actions) {
        this.windowManager = windowManager;
        this.protectionService = protectionService;
        this.settings = settings;
        this.trayManager = trayManager;
        this.actions = actions;
    }

    // ------------------------------------------------------------------
    // Content construction
    // ------------------------------------------------------------------

    /**
     * Builds the whole window content.
     *
     * @return the root node handed to the scene
     */
    public Parent createContent() {
        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildWindowSection());
        root.setBottom(buildBottomSection());
        BorderPane.setMargin(root.getCenter(), new Insets(0, 0, 0, 0));
        return root;
    }

    private Node buildHeader() {
        Label title = new Label("ScreenGuard");
        title.getStyleClass().add("title-label");
        Label subtitle = new Label(
                "Protects a window from capture APIs by using the Windows display-affinity setting "
                        + "(SetWindowDisplayAffinity).");
        subtitle.getStyleClass().add("subtitle-label");
        subtitle.setWrapText(true);

        Label hint = new Label(
                "Windows capture exclusion is honored only by capture mechanisms that respect the "
                        + "Windows display-affinity setting.");
        hint.getStyleClass().add("hint-label");
        hint.setWrapText(true);

        VBox header = new VBox(6, title, subtitle, hint);
        header.getStyleClass().add("header");
        return header;
    }

    private Node buildWindowSection() {
        Label sectionTitle = new Label("Available Windows");
        sectionTitle.getStyleClass().add("section-label");

        searchField.setPromptText("Filter by application, process or PID");
        searchField.setPrefColumnCount(22);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox sectionHeader = new HBox(10, sectionTitle, spacer, searchField);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);
        sectionHeader.setPadding(new Insets(14, 16, 8, 16));

        buildColumns();
        windowTable.setPlaceholder(new Label("No windows found. Use Refresh to enumerate again."));
        VBox.setVgrow(windowTable, Priority.ALWAYS);
        windowTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> onSelectionChanged(newValue));

        refreshButton.getStyleClass().add("button-secondary");
        refreshButton.setOnAction(event -> refreshWindowList());

        windowCountLabel.getStyleClass().add("muted-label");

        HBox refreshRow = new HBox(10, refreshButton, windowCountLabel);
        refreshRow.setAlignment(Pos.CENTER_LEFT);
        refreshRow.setPadding(new Insets(10, 16, 14, 16));

        VBox section = new VBox(sectionHeader, windowTable, refreshRow);
        VBox.setMargin(windowTable, new Insets(0, 16, 0, 16));
        return section;
    }

    private void buildColumns() {
        TableColumn<WindowsWindow, String> applicationColumn = new TableColumn<>("Application");
        applicationColumn.setCellValueFactory(cell -> {
            WindowsWindow window = cell.getValue();
            String name = window.getDisplayName();
            if (window.getProcessId() == windowManager.getOwnProcessId()) {
                name += "   (ScreenGuard - can be protected)";
            }
            return new SimpleStringProperty(name);
        });
        applicationColumn.setPrefWidth(460);

        TableColumn<WindowsWindow, String> processColumn = new TableColumn<>("Process");
        processColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().getExecutableName()));
        processColumn.setPrefWidth(220);

        TableColumn<WindowsWindow, String> pidColumn = new TableColumn<>("PID");
        pidColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                Integer.toString(cell.getValue().getProcessId())));
        pidColumn.setPrefWidth(80);
        pidColumn.setStyle("-fx-alignment: CENTER-RIGHT;");

        windowTable.getColumns().addAll(List.of(applicationColumn, processColumn, pidColumn));
        windowTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private Node buildBottomSection() {
        VBox details = new VBox(4,
                labeledRow("Selected Window:", selectionLabel, "selection-label"),
                labeledRow("Display affinity:", affinityLabel, "mono-label"),
                labeledRow("Window handle:", handleLabel, "mono-label"));

        protectButton.getStyleClass().add("button-primary");
        protectButton.setDisable(true);
        protectButton.setOnAction(event -> onProtectSelected());

        unprotectButton.getStyleClass().add("button-secondary");
        unprotectButton.setDisable(true);
        unprotectButton.setOnAction(event -> onUnprotectSelected());

        HBox actionRow = new HBox(10, protectButton, unprotectButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        statusDot.getStyleClass().addAll("status-dot", "status-dot-off");
        statusLabel.getStyleClass().add("status-text");
        HBox statusRow = new HBox(8, new Label("Status:"), statusDot, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        messageLabel.getStyleClass().add("message-label");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        VBox card = new VBox(10, details, new Separator(), actionRow, statusRow, messageLabel);
        card.getStyleClass().add("card");
        VBox.setMargin(card, new Insets(0, 16, 12, 16));

        accordion.getPanes().addAll(settingsPane, helpPane);
        settingsPane.setText("Settings");
        settingsPane.setContent(buildSettingsContent());
        helpPane.setText("Help / About");
        helpPane.setContent(buildHelpContent());
        VBox.setMargin(accordion, new Insets(0, 16, 12, 16));

        hideButton.getStyleClass().add("button-secondary");
        hideButton.setOnAction(event -> actions.hideToTray());
        exitButton.getStyleClass().add("button-danger");
        exitButton.setOnAction(event -> actions.exitApplication());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, hideButton, spacer, exitButton);
        footer.setPadding(new Insets(0, 16, 16, 16));

        VBox bottom = new VBox(card, accordion, footer);
        return bottom;
    }

    private Node buildSettingsContent() {
        startWithWindowsCheck.setSelected(safeIsStartWithWindows());
        startWithWindowsCheck.setOnAction(event -> onStartWithWindowsToggled());
        showOnStartupCheck.setSelected(settings.isShowWindowOnStartup());
        showOnStartupCheck.setOnAction(event -> {
            settings.setShowWindowOnStartup(showOnStartupCheck.isSelected());
            settings.save();
            setMessage("Saved: the main window will "
                    + (showOnStartupCheck.isSelected() ? "" : "not ")
                    + "be shown when ScreenGuard starts.", StatusKind.UNPROTECTED);
        });

        Label startupHint = new Label(
                "Uses the standard per-user startup entry "
                        + "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run, which Windows "
                        + "also lists under Task Manager > Startup apps.");
        startupHint.getStyleClass().add("muted-label");
        startupHint.setWrapText(true);

        previewButton.getStyleClass().add("button-secondary");
        previewButton.setOnAction(event -> actions.openProtectedPreview());
        Label previewHint = new Label(
                "Windows only applies display affinity to windows that the calling process owns. "
                        + "The built-in preview window belongs to ScreenGuard, so it can be "
                        + "protected end-to-end - useful to verify how capture tools behave.");
        previewHint.getStyleClass().add("muted-label");
        previewHint.setWrapText(true);

        Label storageLabel = new Label("Settings file: " + settings.getSettingsFile());
        storageLabel.getStyleClass().add("mono-label");
        storageLabel.setWrapText(true);

        VBox box = new VBox(10, startWithWindowsCheck, showOnStartupCheck, startupHint,
                new Separator(), previewButton, previewHint, new Separator(), storageLabel);
        box.setPadding(new Insets(12));
        return box;
    }

    private Node buildHelpContent() {
        TextArea help = new TextArea(buildHelpText());
        help.setEditable(false);
        help.setWrapText(true);
        help.setPrefRowCount(12);
        help.getStyleClass().add("mono-label");
        VBox box = new VBox(10, help);
        box.setPadding(new Insets(12));
        return box;
    }

    private String buildHelpText() {
        WindowsVersion version = protectionService.getWindowsVersion();
        StringBuilder text = new StringBuilder();
        text.append("Windows capture exclusion is honored only by capture mechanisms that ")
                .append("respect the Windows display-affinity setting.")
                .append(System.lineSeparator()).append(System.lineSeparator());

        text.append("How it works").append(System.lineSeparator());
        text.append("ScreenGuard calls SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) ")
                .append("and then reads the result back with GetWindowDisplayAffinity.")
                .append(System.lineSeparator()).append(System.lineSeparator());

        text.append("What that means in practice").append(System.lineSeparator());
        text.append("- A protected window stays fully visible and usable for the person at the ")
                .append("keyboard.").append(System.lineSeparator());
        text.append("- Capture tools that honour display affinity (for example the Windows ")
                .append("snipping tools, Windows.Graphics.Capture based apps and other ")
                .append("DWM-composition aware tools) show an empty area instead of the window ")
                .append("content.").append(System.lineSeparator());
        text.append("- It is NOT a DRM or security guarantee. Microsoft documents that there is ")
                .append("no guarantee the content stays protected.").append(System.lineSeparator());

        text.append("Known limitations").append(System.lineSeparator());
        text.append("- Affinity can only be set for a top-level window that belongs to the ")
                .append("calling process. ScreenGuard therefore cannot protect Chrome, Teams, ")
                .append("Meet, Webex, OBS or any other foreign window; Windows returns ")
                .append("ERROR_ACCESS_DENIED (5). Doing it would require injecting code into that ")
                .append("process, which ScreenGuard never does.")
                .append(System.lineSeparator());
        text.append("- Nothing can protect content from a camera pointed at the screen, from a ")
                .append("hardware capture card or from a tool that reads pixels outside the ")
                .append("supported capture APIs.").append(System.lineSeparator());
        text.append("- Screen sharing inside Teams / Google Meet / Webex, OBS, remote desktop ")
                .append("software and DRM pipelines are all separate technologies: some of them ")
                .append("honour affinity, some do not. Results vary - test with the tool you ")
                .append("actually use.").append(System.lineSeparator());
        text.append("- WDA_EXCLUDEFROMCAPTURE needs Windows 10 version 2004 (build 19041) or ")
                .append("later; on older builds the request degrades to WDA_MONITOR.")
                .append(System.lineSeparator());
        text.append("- Display affinity requires the Desktop Window Manager to be composing ")
                .append("the desktop; it does nothing on a system without DWM composition.")
                .append(System.lineSeparator()).append(System.lineSeparator());

        text.append("Privacy").append(System.lineSeparator());
        text.append("ScreenGuard never captures or records the screen, never reads window ")
                .append("contents, never uploads anything, never contacts a network service and ")
                .append("never injects code into other processes. It only reads window metadata ")
                .append("(title, class, process) and calls the documented display-affinity APIs.")
                .append(System.lineSeparator()).append(System.lineSeparator());

        text.append("Environment").append(System.lineSeparator());
        text.append("Windows: ").append(version.summary()).append(System.lineSeparator());
        text.append("WDA_EXCLUDEFROMCAPTURE supported by this build: ")
                .append(protectionService.isCaptureExclusionSupported() ? "yes" : "no")
                .append(System.lineSeparator());
        text.append("Java: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(')')
                .append(System.lineSeparator());
        text.append("JavaFX: ").append(System.getProperty("javafx.version", "bundled"))
                .append(System.lineSeparator());
        text.append("System tray available: ").append(trayManager.isSupported())
                .append(System.lineSeparator());
        text.append("ScreenGuard version: ").append(applicationVersion());
        return text.toString();
    }

    private static String applicationVersion() {
        String version = MainController.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0 (development build)";
    }

    private static Node labeledRow(String labelText, Label value, String valueStyleClass) {
        Label name = new Label(labelText);
        name.getStyleClass().add("muted-label");
        name.setMinWidth(120);
        value.getStyleClass().add(valueStyleClass);
        value.setWrapText(true);
        HBox row = new HBox(8, name, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------
    // Window list
    // ------------------------------------------------------------------

    /** Re-enumerates the desktop on a background thread. */
    public void refreshWindowList() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        refreshButton.setDisable(true);
        setStatus(StatusKind.BUSY, "Refreshing...");
        LOG.info("Refreshing the window list.");

        Task<List<WindowsWindow>> task = new Task<>() {
            @Override
            protected List<WindowsWindow> call() {
                windowManager.invalidateCache();
                return windowManager.listTargetWindows();
            }
        };
        task.setOnSucceeded(event -> {
            refreshing = false;
            refreshButton.setDisable(false);
            applyWindowList(task.getValue());
        });
        task.setOnFailed(event -> {
            refreshing = false;
            refreshButton.setDisable(false);
            Throwable error = task.getException();
            LOG.log(Level.WARNING, "Window enumeration failed", error);
            setStatus(StatusKind.ERROR, "Enumeration failed");
            setMessage("The window list could not be read: "
                    + (error == null ? "unknown error" : error.getMessage())
                    + ". ScreenGuard keeps running - try Refresh again.", StatusKind.ERROR);
        });
        worker.execute(task);
    }

    private void applyWindowList(List<WindowsWindow> windows) {
        Long previouslySelected = selectedWindow == null ? null : selectedWindow.getHwndValue();
        allWindows.setAll(windows);
        applyFilter();
        windowCountLabel.setText(windows.size() == 1
                ? "1 window listed"
                : windows.size() + " windows listed");
        LOG.log(Level.INFO, "Applied window list with {0} entries", windows.size());

        if (previouslySelected != null) {
            allWindows.stream()
                    .filter(window -> window.getHwndValue() == previouslySelected)
                    .findFirst()
                    .ifPresent(this::selectInTable);
        }
        if (windows.isEmpty()) {
            setMessage("No visible top-level windows were found. Open an application and press "
                    + "Refresh.", StatusKind.WARNING);
        }
        refreshSelectedWindowState();
    }

    private void applyFilter() {
        String needle = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();
        visibleWindows.setPredicate(window -> needle.isEmpty() || window.searchKey().contains(needle));
    }

    private void selectInTable(WindowsWindow window) {
        windowTable.getSelectionModel().select(window);
        windowTable.scrollTo(window);
    }

    private void onSelectionChanged(WindowsWindow window) {
        selectedWindow = window;
        if (window == null) {
            selectionLabel.setText("None");
            affinityLabel.setText("-");
            handleLabel.setText("-");
            setStatus(StatusKind.UNPROTECTED, "No window selected");
            setMessage("Select a window in the list, then press Protect Window.",
                    StatusKind.UNPROTECTED);
        } else {
            selectionLabel.setText(window.getDisplayName()
                    + "  (" + window.getExecutableName() + ", pid " + window.getProcessId() + ")");
            handleLabel.setText(window.getHwndHex());
            affinityLabel.setText(CaptureProtectionService.describeAffinity(window.getDisplayAffinity()));
            setMessage("Selected " + window.getDisplayName() + " (pid " + window.getProcessId() + ").",
                    StatusKind.UNPROTECTED);
        }
        refreshSelectedWindowState();
    }

    /** Re-reads the affinity of the selected window and updates every status surface. */
    public void refreshSelectedWindowState() {
        WindowsWindow current = selectedWindow;
        if (current == null) {
            setStatus(StatusKind.UNPROTECTED, "No window selected");
            updateButtons();
            updateTrayState();
            return;
        }
        long affinity = protectionService.readAffinity(current.getHwnd());
        if (affinity < 0) {
            setStatus(StatusKind.WARNING, "Window closed");
            setMessage("The selected window no longer exists. Press Refresh to update the list.",
                    StatusKind.WARNING);
            affinityLabel.setText("unavailable");
            updateButtons();
            updateTrayState();
            return;
        }
        WindowsWindow refreshed = current.withDisplayAffinity(affinity);
        replaceRow(refreshed);
        selectedWindow = refreshed;
        affinityLabel.setText(CaptureProtectionService.describeAffinity(affinity));

        if (affinity == User32Extended.WDA_EXCLUDEFROMCAPTURE) {
            setStatus(StatusKind.PROTECTED, "Protected");
        } else if (affinity == User32Extended.WDA_MONITOR) {
            setStatus(StatusKind.WARNING, "Protected (monitor only)");
        } else {
            setStatus(StatusKind.UNPROTECTED, "Not protected");
        }
        updateButtons();
        updateTrayState();
    }

    private void replaceRow(WindowsWindow window) {
        for (int index = 0; index < allWindows.size(); index++) {
            if (allWindows.get(index).getHwndValue() == window.getHwndValue()) {
                allWindows.set(index, window);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Protect / unprotect
    // ------------------------------------------------------------------

    /** Applies capture exclusion to the selected window. */
    public void onProtectSelected() {
        WindowsWindow target = selectedWindow;
        if (target == null) {
            setStatus(StatusKind.WARNING, "No window selected");
            setMessage("Select a window in the list first.", StatusKind.WARNING);
            return;
        }
        applyProtectionResult(target, protectionService.protect(target.getHwnd()), true);
    }

    /** Removes capture exclusion from the selected window. */
    public void onUnprotectSelected() {
        WindowsWindow target = selectedWindow;
        if (target == null) {
            setStatus(StatusKind.WARNING, "No window selected");
            setMessage("Select a window in the list first.", StatusKind.WARNING);
            return;
        }
        applyProtectionResult(target, protectionService.unprotect(target.getHwnd()), false);
    }

    /**
     * Runs {@link #onProtectSelected()} on the JavaFX thread and makes sure the user can see the
     * explanation when the request cannot succeed.
     * Called by the tray menu (which dispatches on the AWT thread).
     */
    public void handleTrayProtect() {
        Platform.runLater(() -> {
            onProtectSelected();
            if (lastActionNeededAttention) {
                actions.showWindow();
            }
        });
    }

    /**
     * Runs {@link #onUnprotectSelected()} on the JavaFX thread.
     * Called by the tray menu (which dispatches on the AWT thread).
     */
    public void handleTrayUnprotect() {
        Platform.runLater(this::onUnprotectSelected);
    }

    private void applyProtectionResult(WindowsWindow target, ProtectionResult result,
            boolean protecting) {
        String prefix = protecting ? "Protect Window: " : "Remove Protection: ";
        lastActionNeededAttention = !result.isSuccess()
                || result.status() == Status.ENABLED_AS_MONITOR;
        if (result.isSuccess()) {
            StatusKind kind = switch (result.status()) {
                case ENABLED -> StatusKind.PROTECTED;
                case ENABLED_AS_MONITOR -> StatusKind.WARNING;
                default -> StatusKind.UNPROTECTED;
            };
            if (result.affinity() >= 0) {
                replaceRow(target.withDisplayAffinity(result.affinity()));
            }
            refreshSelectedWindowState();
            setMessage(prefix + result.message() + " Current display affinity: "
                    + result.affinityText() + ".", kind);
        } else {
            StatusKind kind = result.status() == Status.WINDOW_CLOSED
                    ? StatusKind.WARNING : StatusKind.ERROR;
            setStatus(kind, result.status() == Status.WINDOW_CLOSED
                    ? "Window closed" : "Not protected");
            String extra = result.status() == Status.NOT_OWNED
                    ? " Tip: open the ScreenGuard preview window from Settings to see the "
                            + "mechanism work on a window that ScreenGuard owns."
                    : "";
            setMessage(prefix + result.message() + extra, kind);
            if (result.affinity() >= 0) {
                affinityLabel.setText(result.affinityText());
            }
        }
        updateButtons();
        updateTrayState();
    }

    private void setStatus(StatusKind kind, String text) {
        statusDot.getStyleClass().removeAll("status-dot-ok", "status-dot-off",
                "status-dot-warn", "status-dot-error");
        statusDot.getStyleClass().add(switch (kind) {
            case PROTECTED -> "status-dot-ok";
            case UNPROTECTED, BUSY -> "status-dot-off";
            case WARNING -> "status-dot-warn";
            case ERROR -> "status-dot-error";
        });
        statusLabel.setText(text);
    }

    private void setMessage(String text, StatusKind kind) {
        messageLabel.getStyleClass().removeAll("message-label", "message-label-error",
                "message-label-ok");
        messageLabel.getStyleClass().add(switch (kind) {
            case PROTECTED -> "message-label-ok";
            case ERROR -> "message-label-error";
            default -> "message-label";
        });
        messageLabel.setText(text);
    }

    private void updateButtons() {
        boolean hasSelection = selectedWindow != null;
        protectButton.setDisable(!hasSelection);
        unprotectButton.setDisable(!hasSelection);
    }

    private void updateTrayState() {
        String tooltip;
        boolean hasSelection = selectedWindow != null;
        if (!hasSelection) {
            tooltip = "ScreenGuard - no window selected";
        } else {
            tooltip = "ScreenGuard - " + selectedWindow.getDisplayName()
                    + (selectedWindow.isCaptureProtected() ? " (capture protected)" : "");
        }
        trayManager.updateState(tooltip, hasSelection, hasSelection);
    }

    private void onStartWithWindowsToggled() {
        boolean wanted = startWithWindowsCheck.isSelected();
        boolean changed = settings.setStartWithWindowsEnabled(wanted);
        if (!changed) {
            startWithWindowsCheck.setSelected(!wanted);
            setMessage("The Windows startup entry could not be changed. "
                    + "ScreenGuard stays usable, but it will not start automatically.",
                    StatusKind.ERROR);
            return;
        }
        String registered = settings.getRegisteredStartupCommand();
        setMessage(wanted
                ? "ScreenGuard will start with Windows. Registered command: " + registered
                : "The ScreenGuard startup entry was removed.", StatusKind.UNPROTECTED);
    }

    private boolean safeIsStartWithWindows() {
        try {
            return settings.isStartWithWindowsEnabled();
        } catch (RuntimeException | Error e) {
            LOG.log(Level.FINE, "Could not read the startup entry", e);
            return false;
        }
    }

    /** Expands the settings pane; used by the "Open Settings" tray entry. */
    public void showSettingsSection() {
        accordion.setExpandedPane(settingsPane);
        refreshSelectedWindowState();
    }

    /** Called when the user closes the window: ScreenGuard keeps running in the tray. */
    public void onWindowHidden() {
        if (!windowHiddenNoticeShown) {
            windowHiddenNoticeShown = true;
            LOG.info("Main window hidden; ScreenGuard keeps running in the notification area.");
        }
        updateTrayState();
    }

    /** Releases the background worker. Called when the application terminates. */
    public void shutdown() {
        worker.shutdownNow();
        LOG.info("Main controller shut down.");
    }
}

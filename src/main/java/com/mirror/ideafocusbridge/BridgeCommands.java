package com.mirror.ideafocusbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.IdeFrameEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;
import java.util.concurrent.TimeUnit;

final class BridgeCommands {
    private static final int SELECTED_TEXT_POLL_INTERVAL_MS = 30;
    private static final int SELECTED_TEXT_MAX_WAIT_MS = 200;
    private static String lastSelectedText;

    private BridgeCommands() {}

    static Map<String, Object> identity() {
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            projects.add(projectIdentity(project));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client", "fast-idea-bridge");
        FocusBridgeService service = FocusBridgeService.getInstance();
        payload.put("instanceId", service.instanceId());
        payload.put("connectionId", service.connectionId());
        payload.put("projects", projects);
        payload.put("processId", ProcessHandle.current().pid());
        payload.put("nativeWindows", nativeWindows());
        Map<String, Object> snapshot = FocusSnapshotCollector.collect();
        payload.put("fullScreen", snapshot.get("fullScreen"));
        payload.put("editorOpenedAt", snapshot.get("editorOpenedAt"));
        Object activeEditor = snapshot.get("activeEditor");
        if (activeEditor instanceof Map<?, ?> editor) {
            payload.put("activeProject", editor.get("project"));
        }
        payload.put("capabilities", List.of("state", "getInputable", "isEditorFocused", "getContext", "getObjectInfo", "getSelectedText", "getCurrentLineContent", "navigate", "select", "find", "deleteSelection", "input", "requestFocus", "fullScreen", "isFullScreen", "listProjects", "copyResourcePath", "pythonPackages", "ctrlW", "ctrlShiftW", "shiftHome", "home", "end", "inputTop", "shiftDown", "shiftUp", "moveUpIfLastLine"));
        return payload;
    }

    private static Map<String, Object> nativeWindows() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return result;
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        List<Map<String, Object>> windows = new ArrayList<>();
        String activeHandle = null;
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable() || !window.isShowing()) continue;
            try {
                Pointer pointer = Native.getComponentPointer(window);
                long handle = Pointer.nativeValue(pointer);
                if (handle == 0) continue;
                String decimal = Long.toUnsignedString(handle);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("handle", decimal);
                windows.add(item);
                if (window == active) activeHandle = decimal;
            } catch (RuntimeException ignored) { }
        }
        result.put("activeWindowHandle", activeHandle);
        result.put("windows", windows);
        return result;
    }

    static Map<String, Object> source(String instanceId, String connectionId, Object payload) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("instanceId", instanceId);
        source.put("connectionId", connectionId);
        source.put("projects", openProjectIdentities());
        if (payload instanceof Map<?, ?> map && map.get("activeEditor") instanceof Map<?, ?> editor
                && editor.get("project") instanceof Map<?, ?> project) {
            Object basePath = project.get("basePath");
            if (basePath instanceof String base && !base.isBlank()) {
                source.put("projectId", projectId(base));
                source.put("projectName", project.get("name"));
                source.put("projectBasePath", base);
            }
        }
        return source;
    }

    private static List<Map<String, Object>> openProjectIdentities() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) result.add(projectIdentity(project));
        return result;
    }

    private static Map<String, Object> projectIdentity(Project project) {
        String basePath = Objects.toString(project.getBasePath(), "");
        return Map.of("projectId", projectId(basePath), "name", project.getName(), "basePath", basePath);
    }

    static String projectId(String basePath) {
        String normalized = basePath.isBlank() ? "" : Path.of(basePath).toAbsolutePath().normalize()
                .toString().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static void handle(String raw, FocusBridgeService bridge) {
        String id = null;
        try {
            JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
            id = string(message, "id", null);
            String type = string(message, "type", "");
            JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                    ? message.getAsJsonObject("payload") : new JsonObject();
            JsonObject target = message.has("target") && message.get("target").isJsonObject()
                    ? message.getAsJsonObject("target") : new JsonObject();
            if (!validateInstanceTarget(type, target, bridge)) return;
            String commandId = id;
            switch (type) {
                case "command.ping" -> bridge.send("command.result", Map.of("success", true, "command", type), id);
                case "command.getState" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.collect()), commandId));
                case "command.getInputable" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.currentInputable()), commandId));
                case "command.isEditorFocused" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.collect().get("activeEditor") instanceof Map<?, ?> editor && Boolean.TRUE.equals(editor.get("ownsFocus"))), commandId));
                case "command.getContext" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.currentContext()), commandId));
                case "command.getObjectInfo" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.currentObjectInfo()), commandId));
                case "command.getSelectedText" -> ApplicationManager.getApplication().invokeLater(() ->
                        selectedText(commandId, bridge));
                case "command.deleteSelection" -> ApplicationManager.getApplication().invokeLater(() ->
                        deleteSelection(payload, target, commandId, bridge));
                case "command.input" -> ApplicationManager.getApplication().invokeLater(() ->
                        input(payload, target, commandId, bridge));
                case "command.getCurrentLineContent" -> ApplicationManager.getApplication().invokeLater(() ->
                        bridge.send("command.result", result(true, type, FocusSnapshotCollector.currentLineContent()), commandId));
                case "command.isFullScreen" -> ApplicationManager.getApplication().invokeLater(() ->
                        isFullScreen(target, commandId, bridge));
                case "command.listProjects" -> bridge.send("command.result", result(true, type, identity().get("projects")), id);
                case "command.navigate", "command.select" ->
                        ApplicationManager.getApplication().invokeLater(() -> navigate(type, payload, target, commandId, bridge));
                case "command.find" ->
                        ApplicationManager.getApplication().invokeLater(() -> find(payload, target, commandId, bridge));
                case "command.requestFocus" -> ApplicationManager.getApplication().invokeLater(() -> requestFocus(commandId, bridge));
                case "command.ctrlW" -> ApplicationManager.getApplication().invokeLater(() -> ctrlW(target, commandId, bridge));
                case "command.ctrlShiftW" -> ApplicationManager.getApplication().invokeLater(() -> ctrlShiftW(target, commandId, bridge));
                case "command.shiftHome" -> ApplicationManager.getApplication().invokeLater(() -> shiftHome(target, commandId, bridge));
                case "command.home" -> ApplicationManager.getApplication().invokeLater(() -> home(target, commandId, bridge));
                case "command.end" -> ApplicationManager.getApplication().invokeLater(() -> end(target, commandId, bridge));
                case "command.inputTop" -> ApplicationManager.getApplication().invokeLater(() -> inputTop(payload, target, commandId, bridge));
                case "command.shiftDown" -> ApplicationManager.getApplication().invokeLater(() -> shiftDirection(target, payload, 1, commandId, bridge));
                case "command.shiftUp" -> ApplicationManager.getApplication().invokeLater(() -> shiftDirection(target, payload, -1, commandId, bridge));
                case "command.moveUpIfLastLine" -> ApplicationManager.getApplication().invokeLater(() -> moveUpIfLastLine(target, commandId, bridge));
                case "command.fullScreen" -> ApplicationManager.getApplication().invokeLater(() -> fullScreen(target, commandId, bridge));
                case "command.copyResourcePath" -> ApplicationManager.getApplication().invokeLater(() -> copyResourcePath(commandId, bridge));
                case "command.queryPythonPackages" -> ApplicationManager.getApplication().invokeLater(() ->
                        queryPythonPackages(payload, target, commandId, bridge));
                default -> bridge.send("command.result", error(type, "UNKNOWN_COMMAND", "Unsupported command: " + type), id);
            }
        } catch (RuntimeException e) {
            bridge.send("command.result", error("unknown", "INVALID_MESSAGE", e.getMessage()), id);
        }
    }

    private static void selectedText(String commandId, FocusBridgeService bridge) {
        String selectedText = FocusSnapshotCollector.currentSelectedText();
        if (selectedText == null || !Objects.equals(selectedText, lastSelectedText)) {
            if (selectedText != null) lastSelectedText = selectedText;
            bridge.send("command.result", result(true, "command.getSelectedText", selectedText), commandId);
            return;
        }

        pollSelectedText(commandId, bridge, selectedText, 0);
    }

    private static void deleteSelection(JsonObject payload, JsonObject target, String id, FocusBridgeService bridge) {
        String expectedText = string(payload, "expectedText", null);
        if (expectedText == null) {
            bridge.send("command.result", error("command.deleteSelection", "INVALID_MESSAGE", "payload.expectedText is required"), id);
            return;
        }

        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.deleteSelection", "NO_EDITOR", "No active text editor"), id);
            return;
        }

        var selection = editor.getSelectionModel();
        if (!Objects.equals(selection.getSelectedText(), expectedText)) {
            bridge.send("command.result", result(true, "command.deleteSelection",
                    Map.of("handled", true, "matched", false, "deleted", false)), id);
            return;
        }

        int start = selection.getSelectionStart();
        int end = selection.getSelectionEnd();
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().deleteString(start, end));
        bridge.send("command.result", result(true, "command.deleteSelection",
                Map.of("handled", true, "matched", true, "deleted", true)), id);
    }

    private static void input(JsonObject payload, JsonObject target, String id, FocusBridgeService bridge) {
        String text = string(payload, "text", null);
        if (text == null) {
            bridge.send("command.result", error("command.input", "INVALID_MESSAGE", "payload.text is required"), id);
            return;
        }

        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.input", "NO_EDITOR", "No active text editor"), id);
            return;
        }

        var caret = editor.getCaretModel().getPrimaryCaret();
        int start = caret.getSelectionStart();
        int end = caret.getSelectionEnd();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            editor.getDocument().replaceString(start, end, text);
            caret.removeSelection();
            caret.moveToOffset(start + text.length());
        });
        bridge.send("command.result", result(true, "command.input", Map.of("handled", true)), id);
    }

    private static void pollSelectedText(String commandId, FocusBridgeService bridge,
                                         String previousText, int elapsedMs) {
        int delayMs = Math.min(SELECTED_TEXT_POLL_INTERVAL_MS, SELECTED_TEXT_MAX_WAIT_MS - elapsedMs);
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    String refreshedText = FocusSnapshotCollector.currentSelectedText();
                    int nextElapsedMs = elapsedMs + delayMs;
                    if (refreshedText != null && !Objects.equals(refreshedText, previousText)) {
                        lastSelectedText = refreshedText;
                        bridge.send("command.result", result(true, "command.getSelectedText", refreshedText), commandId);
                    } else if (nextElapsedMs >= SELECTED_TEXT_MAX_WAIT_MS) {
                        if (refreshedText != null) lastSelectedText = refreshedText;
                        bridge.send("command.result", result(true, "command.getSelectedText",
                                refreshedText != null ? refreshedText : previousText), commandId);
                    } else {
                        pollSelectedText(commandId, bridge, previousText, nextElapsedMs);
                    }
                }), delayMs, TimeUnit.MILLISECONDS);
    }

    private static boolean validateInstanceTarget(String type, JsonObject target, FocusBridgeService bridge) {
        String targetInstance = string(target, "instanceId", null);
        boolean mutating = type.equals("command.navigate") || type.equals("command.select")
                || type.equals("command.find") || type.equals("command.requestFocus")
                || type.equals("command.fullScreen")
                || type.equals("command.copyResourcePath")
                || type.equals("command.queryPythonPackages") || type.equals("command.deleteSelection") || type.equals("command.input") || type.equals("command.ctrlW") || type.equals("command.ctrlShiftW")
                || type.equals("command.shiftHome") || type.equals("command.home") || type.equals("command.end") || type.equals("command.inputTop") || type.equals("command.shiftDown")
                || type.equals("command.shiftUp") || type.equals("command.moveUpIfLastLine");
        if (mutating && (targetInstance == null || targetInstance.isBlank())) {
            throw new IllegalArgumentException("target.instanceId is required for this command");
        }
        return targetInstance == null || targetInstance.equals(bridge.instanceId());
    }

    private static void requestFocus(String id, FocusBridgeService bridge) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                activateEditorWindow(editor);
                bridge.send("command.result", result(true, "command.requestFocus", Map.of("project", project.getName())), id);
                return;
            }
        }
        bridge.send("command.result", error("command.requestFocus", "NO_EDITOR", "No active text editor"), id);
    }

    private static void ctrlW(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.ctrlW", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        EditorActionHandler handler = EditorActionManager.getInstance()
                .getActionHandler(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET);
        handler.execute(editor, editor.getCaretModel().getPrimaryCaret(),
                DataManager.getInstance().getDataContext(editor.getContentComponent()));
        bridge.send("command.result", result(true, "command.ctrlW", Map.of("handled", true)), id);
    }

    private static void ctrlShiftW(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.ctrlShiftW", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        EditorActionHandler handler = EditorActionManager.getInstance()
                .getActionHandler(IdeActions.ACTION_EDITOR_UNSELECT_WORD_AT_CARET);
        handler.execute(editor, editor.getCaretModel().getPrimaryCaret(),
                DataManager.getInstance().getDataContext(editor.getContentComponent()));
        bridge.send("command.result", result(true, "command.ctrlShiftW", Map.of("handled", true)), id);
    }


    private static void shiftHome(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.shiftHome", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var document = editor.getDocument();
        boolean moved = false;
        for (var caret : editor.getCaretModel().getAllCarets()) {
            int originalOffset = caret.getOffset();
            int lineStartOffset = document.getLineStartOffset(document.getLineNumber(originalOffset));
            // A repeated Shift+Home must not collapse a selection whose active end is
            // already at the line start.
            if (originalOffset == lineStartOffset) continue;
            caret.moveToOffset(lineStartOffset);
            caret.setSelection(lineStartOffset, originalOffset);
            moved = true;
        }
        bridge.send("command.result", result(true, "command.shiftHome", Map.of("handled", moved)), id);
    }

    /** Moves every caret to its logical document-line start without selecting text. */
    private static void home(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.home", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var document = editor.getDocument();
        for (var caret : editor.getCaretModel().getAllCarets()) {
            caret.removeSelection();
            caret.moveToOffset(document.getLineStartOffset(document.getLineNumber(caret.getOffset())));
        }
        bridge.send("command.result", result(true, "command.home", Map.of("handled", true)), id);
    }

    /** Moves every caret to its logical document-line end without selecting text. */
    private static void end(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.end", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var document = editor.getDocument();
        for (var caret : editor.getCaretModel().getAllCarets()) {
            int line = document.getLineNumber(caret.getOffset());
            caret.removeSelection();
            caret.moveToOffset(document.getLineEndOffset(line));
        }
        bridge.send("command.result", result(true, "command.end", Map.of("handled", true)), id);
    }

    /** Inserts text above the first non-comment line without touching the caret or selection. */
    private static void inputTop(JsonObject payload, JsonObject target, String id, FocusBridgeService bridge) {
        String code = string(payload, "code", null);
        if (code == null || code.isEmpty()) {
            bridge.send("command.result", error("command.inputTop", "INVALID_MESSAGE", "payload.code is required"), id);
            return;
        }
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.inputTop", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var document = editor.getDocument();
        String text = document.getText();
        int offset = inputTopOffset(text, isPythonFile(editor));
        String lineEnding = offset > 0 && text.charAt(offset - 1) != '\n' && text.charAt(offset - 1) != '\r' ? "\n" : "";
        String inserted = lineEnding + code + "\n";
        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.insertString(offset, inserted);
        });
        bridge.send("command.result", result(true, "command.inputTop", Map.of("handled", true)), id);
    }

    private static boolean isPythonFile(Editor editor) {
        return editor.getVirtualFile() != null && "py".equalsIgnoreCase(editor.getVirtualFile().getExtension());
    }

    private static int inputTopOffset(String text, boolean python) {
        int offset = 0;
        boolean blockComment = false;
        while (offset < text.length()) {
            int lineEnd = text.indexOf('\n', offset);
            int contentEnd = lineEnd < 0 ? text.length() : lineEnd;
            String line = text.substring(offset, contentEnd);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            String trimmed = line.trim();
            if (blockComment) {
                int close = trimmed.indexOf("*/");
                if (close < 0) {
                    offset = lineEnd < 0 ? text.length() : lineEnd + 1;
                    continue;
                }
                blockComment = false;
                trimmed = trimmed.substring(close + 2).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || (python && trimmed.startsWith("#"))) {
                    offset = lineEnd < 0 ? text.length() : lineEnd + 1;
                    continue;
                }
                return offset;
            }
            if (trimmed.startsWith("//") || (python && trimmed.startsWith("#"))) {
                offset = lineEnd < 0 ? text.length() : lineEnd + 1;
                continue;
            }
            if (trimmed.startsWith("/*")) {
                int close = trimmed.indexOf("*/", 2);
                if (close < 0) {
                    blockComment = true;
                } else if (!trimmed.substring(close + 2).trim().isEmpty()) {
                    return offset;
                }
                offset = lineEnd < 0 ? text.length() : lineEnd + 1;
                continue;
            }
            return offset;
        }
        return offset;
    }

    /** Extends every caret selection by logical document lines, ignoring soft wraps. */
    private static void shiftDirection(JsonObject target, JsonObject payload, int direction, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        String command = direction > 0 ? "command.shiftDown" : "command.shiftUp";
        if (editor == null) {
            bridge.send("command.result", error(command, "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var document = editor.getDocument();
        int count = Math.max(1, integer(payload, "count", 1));
        for (var caret : editor.getCaretModel().getAllCarets()) {
            int originalOffset = caret.getOffset();
            int line = Math.max(0, Math.min(document.getLineCount() - 1, document.getLineNumber(originalOffset) + direction * count));
            int targetOffset = Math.min(document.getLineEndOffset(line), document.getLineStartOffset(line) + caret.getLogicalPosition().column);
            int anchor = caret.hasSelection() ? (originalOffset == caret.getSelectionStart() ? caret.getSelectionEnd() : caret.getSelectionStart()) : originalOffset;
            caret.moveToOffset(targetOffset);
            caret.setSelection(anchor, targetOffset);
        }
        bridge.send("command.result", result(true, command, Map.of("handled", true)), id);
    }

    private static void moveUpIfLastLine(JsonObject target, String id, FocusBridgeService bridge) {
        Project project = resolveProject(target);
        Editor editor = project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            bridge.send("command.result", error("command.moveUpIfLastLine", "NO_EDITOR", "No active text editor"), id);
            return;
        }
        var caret = editor.getCaretModel().getPrimaryCaret();
        var document = editor.getDocument();
        int line = document.getLineNumber(caret.getOffset());
        if (line == 0 || !document.getCharsSequence().subSequence(document.getLineEndOffset(line), document.getTextLength()).toString().isBlank()) {
            bridge.send("command.result", result(true, "command.moveUpIfLastLine", Map.of("handled", false)), id);
            return;
        }
        int column = caret.getLogicalPosition().column;
        caret.removeSelection();
        caret.moveToLogicalPosition(new LogicalPosition(line - 1, column));
        bridge.send("command.result", result(true, "command.moveUpIfLastLine", Map.of("handled", true)), id);
    }

    private static void copyResourcePath(String id, FocusBridgeService bridge) {
        List<VirtualFile> files = FocusSnapshotCollector.selectedResourceFiles();
        if (files.isEmpty()) {
            bridge.send("command.result", result(true, "command.copyResourcePath", Map.of("handled", false)), id);
            return;
        }
        List<String> paths = files.stream().map(VirtualFile::getPath).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handled", true);
        data.put("paths", paths);
        if (paths.size() == 1) data.put("path", paths.get(0));
        bridge.send("command.result", result(true, "command.copyResourcePath", data), id);
    }

    private static void fullScreen(JsonObject targetIdentity, String id, FocusBridgeService bridge) {
        Project project = resolveProject(targetIdentity);
        if (project == null) {
            bridge.send("command.result", error("command.fullScreen", "NO_PROJECT", "No open project window"), id);
            return;
        }

        IdeFrameEx ideFrame = ideFrame(project);
        if (ideFrame == null) {
            bridge.send("command.result", error("command.fullScreen", "UNSUPPORTED", "Full screen action is unavailable"), id);
            return;
        }
        if (!ideFrame.isInFullScreen()) ideFrame.toggleFullScreen(true);
        bridge.send("command.result", result(true, "command.fullScreen",
                Map.of("fullScreen", ideFrame.isInFullScreen(), "project", project.getName())), id);
    }

    private static void isFullScreen(JsonObject targetIdentity, String id, FocusBridgeService bridge) {
        Project project = resolveProject(targetIdentity);
        if (project == null) {
            bridge.send("command.result", error("command.isFullScreen", "NO_PROJECT", "No open project window"), id);
            return;
        }

        IdeFrameEx ideFrame = ideFrame(project);
        if (ideFrame == null) {
            bridge.send("command.result", result(true, "command.isFullScreen",
                    Map.of("fullScreen", "unknown", "project", project.getName())), id);
            return;
        }
        bridge.send("command.result", result(true, "command.isFullScreen",
                Map.of("fullScreen", ideFrame.isInFullScreen(), "project", project.getName())), id);
    }

    private static IdeFrameEx ideFrame(Project project) {
        return WindowManagerEx.getInstanceEx().findFrameHelper(project);
    }

    private static Project resolveProject(JsonObject target) {
        String requestedProjectId = string(target, "projectId", null);
        String requestedBasePath = string(target, "projectBasePath", null);
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String basePath = Objects.toString(project.getBasePath(), "");
            if (requestedProjectId != null && !requestedProjectId.equals(projectId(basePath))) continue;
            if (requestedBasePath != null && !pathOf(requestedBasePath).normalize().equals(pathOf(basePath).normalize())) continue;
            return project;
        }
        if (requestedProjectId != null || requestedBasePath != null) return null;
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return project;
        }
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length == 0 ? null : projects[0];
    }

    private static final Pattern PYTHON_PACKAGE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final String PYTHON_PACKAGE_QUERY = """
            import importlib.util
            import importlib.metadata
            import json
            import os
            import sys
            items = {}
            for item in sys.argv[1:]:
                import_name, distribution_name = item.split('\\t', 1)
                installed = False
                version = None
                try:
                    installed = importlib.util.find_spec(import_name) is not None
                except (ImportError, ModuleNotFoundError, ValueError):
                    installed = False
                try:
                    version = importlib.metadata.version(distribution_name)
                    installed = True
                except importlib.metadata.PackageNotFoundError:
                    pass
                value = {'installed': installed}
                if version:
                    value['version'] = version
                items[import_name] = value
            if os.environ.get('CONDA_PREFIX'):
                environment_type = 'conda'
            elif sys.prefix != getattr(sys, 'base_prefix', sys.prefix):
                environment_type = 'venv'
            else:
                environment_type = 'system'
            print(json.dumps({'interpreter': sys.executable,
                              'version': platform_version(),
                              'environmentType': environment_type,
                              'packages': items}))
            """.replace("platform_version()", "'.'.join(str(part) for part in sys.version_info[:3])");

    private static void queryPythonPackages(JsonObject payload, JsonObject target, String id, FocusBridgeService bridge) {
        List<String> packages = new ArrayList<>();
        List<String> distributions = new ArrayList<>();
        if (payload.has("packages") && payload.get("packages").isJsonArray()) {
            payload.getAsJsonArray("packages").forEach(item -> {
                String importName = null;
                String distributionName = null;
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    importName = item.getAsString();
                    distributionName = importName;
                } else if (item.isJsonObject()) {
                    JsonObject object = item.getAsJsonObject();
                    importName = string(object, "importName", null);
                    distributionName = string(object, "distributionName", importName);
                }
                if (importName == null || !PYTHON_PACKAGE_NAME.matcher(importName).matches()
                        || distributionName == null || !PYTHON_PACKAGE_NAME.matcher(distributionName).matches()
                        || packages.contains(importName)) return;
                packages.add(importName);
                distributions.add(distributionName);
            });
        }
        if (packages.isEmpty()) {
            bridge.send("command.result", error("command.queryPythonPackages", "INVALID_QUERY",
                    "payload.packages must contain at least one package name"), id);
            return;
        }

        Project project = resolveProject(target);
        if (project == null) {
            bridge.send("command.result", error("command.queryPythonPackages", "NO_PROJECT", "No open project matches the target"), id);
            return;
        }
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        String interpreter = pythonInterpreter(sdk);
        if (interpreter == null) {
            bridge.send("command.result", error("command.queryPythonPackages", "PYTHON_INTERPRETER_NOT_FOUND",
                    "The targeted project has no configured Python interpreter"), id);
            return;
        }

        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) arguments.add(packages.get(index) + "\t" + distributions.get(index));
        bridge.runBackground(() -> {
            try {
                Map<String, Object> result = runPythonQuery(interpreter, arguments);
                bridge.send("command.result", result(true, "command.queryPythonPackages", result), id);
            } catch (RuntimeException e) {
                bridge.send("command.result", error("command.queryPythonPackages", "PYTHON_QUERY_FAILED", e.getMessage()), id);
            }
        });
    }

    private static String pythonInterpreter(Sdk sdk) {
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) return null;
        String type = sdk.getSdkType() == null ? "" : Objects.toString(sdk.getSdkType().getName(), "");
        String home = sdk.getHomePath();
        Path homePath = Path.of(home);
        boolean looksPython = type.toLowerCase(Locale.ROOT).contains("python")
                || homePath.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("python");
        if (!looksPython) return null;
        if (!java.nio.file.Files.isDirectory(homePath)) return homePath.toString();
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "Scripts\\python.exe" : "bin/python";
        Path candidate = homePath.resolve(executable);
        return java.nio.file.Files.isRegularFile(candidate) ? candidate.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runPythonQuery(String interpreter, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(interpreter);
        command.add("-c");
        command.add(PYTHON_PACKAGE_QUERY);
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Python package query timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) throw new IllegalStateException("Python interpreter returned no package result");
            Object value = new com.google.gson.Gson().fromJson(output, Object.class);
            if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Python interpreter returned invalid JSON");
            return (Map<String, Object>) map;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the configured Python interpreter", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Python package query was interrupted", e);
        }
    }

    private static void navigate(String type, JsonObject payload, JsonObject targetIdentity, String id, FocusBridgeService bridge) {
        try {
            Target target = resolveTarget(payload, targetIdentity);
            int base = integer(payload, "coordinateBase", 0);
            boolean hasLine = hasValue(payload, "line");
            boolean hasColumn = hasValue(payload, "column");
            OpenFileDescriptor descriptor = new OpenFileDescriptor(target.project, target.file);
            Editor editor = FileEditorManager.getInstance(target.project).openTextEditor(descriptor, true);
            if (editor == null) throw new IllegalStateException("File is not a text editor");

            activateEditorWindow(editor);

            if ("command.navigate".equals(type) && (hasLine || hasColumn)) {
                LogicalPosition current = editor.getCaretModel().getLogicalPosition();
                int requestedLine = hasLine ? integer(payload, "line", base) - base : current.line;
                int requestedColumn = hasColumn ? integer(payload, "column", base) - base : current.column;
                int line = Math.max(0, Math.min(requestedLine, Math.max(0, lineCount(target.file) - 1)));
                editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, Math.max(0, requestedColumn)));
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }

            if ("command.select".equals(type)) {
                Position start = position(payload.getAsJsonObject("start"), editor, base);
                Position end = position(payload.getAsJsonObject("end"), editor, base);
                editor.getSelectionModel().setSelection(start.offset, end.offset);
                editor.getCaretModel().moveToOffset(end.offset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
            bridge.send("command.result", result(true, type, Map.of("path", target.file.getPath(),
                    "line", editor.getCaretModel().getLogicalPosition().line,
                    "column", editor.getCaretModel().getLogicalPosition().column)), id);
        } catch (RuntimeException e) {
            bridge.send("command.result", error(type, "NAVIGATION_FAILED", Objects.toString(e.getMessage(), e.getClass().getSimpleName())), id);
        }
    }

    private static void find(JsonObject payload, JsonObject targetIdentity, String id, FocusBridgeService bridge) {
        try {
            String query = string(payload, "query", null);
            if (query == null || query.isEmpty()) throw new IllegalArgumentException("payload.query is required");

            Target target = resolveSearchTarget(payload, targetIdentity);
            var document = FileDocumentManager.getInstance().getDocument(target.file);
            if (document == null) throw new IllegalStateException("File is not a text document");

            boolean caseSensitive = bool(payload, "caseSensitive", false);
            boolean regex = bool(payload, "regex", false);
            boolean select = bool(payload, "select", false);
            int startOffset = Math.max(0, Math.min(integer(payload, "startOffset", 0), document.getTextLength()));
            Match match = findFirst(document.getCharsSequence(), query, caseSensitive, regex, startOffset);
            if (match == null) {
                bridge.send("command.result", error("command.find", "NOT_FOUND", "No match found"), id);
                return;
            }

            int line = document.getLineNumber(match.start);
            int column = match.start - document.getLineStartOffset(line);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(target.project, target.file, line, column);
            Editor editor = FileEditorManager.getInstance(target.project).openTextEditor(descriptor, true);
            if (editor == null) throw new IllegalStateException("File is not a text editor");

            activateEditorWindow(editor);
            if (select) {
                editor.getSelectionModel().setSelection(match.start, match.end);
            } else {
                editor.getSelectionModel().removeSelection();
                editor.getCaretModel().moveToOffset(match.start);
            }
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", target.file.getPath());
            data.put("startOffset", match.start);
            data.put("endOffset", match.end);
            data.put("line", line);
            data.put("column", column);
            data.put("lineNumber", line + 1);
            data.put("columnNumber", column + 1);
            data.put("caseSensitive", caseSensitive);
            data.put("regex", regex);
            data.put("selected", select);
            bridge.send("command.result", result(true, "command.find", data), id);
        } catch (PatternSyntaxException e) {
            bridge.send("command.result", error("command.find", "INVALID_QUERY", e.getDescription()), id);
        } catch (RuntimeException e) {
            bridge.send("command.result", error("command.find", "FIND_FAILED",
                    Objects.toString(e.getMessage(), e.getClass().getSimpleName())), id);
        }
    }

    private static Target resolveSearchTarget(JsonObject payload, JsonObject targetIdentity) {
        String requested = string(payload, "path", null);
        if (requested != null && !requested.isBlank()) return resolveTarget(payload, targetIdentity);

        String requestedProject = string(payload, "projectBasePath", null);
        if (requestedProject == null) requestedProject = string(targetIdentity, "projectBasePath", null);
        String requestedProjectId = string(targetIdentity, "projectId", null);
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String baseText = project.getBasePath();
            if (baseText == null || requestedProject != null
                    && !pathOf(baseText).normalize().equals(pathOf(requestedProject).normalize())) continue;
            if (requestedProjectId != null && !requestedProjectId.equals(projectId(baseText))) continue;
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null && editor.getVirtualFile() != null) {
                return new Target(project, editor.getVirtualFile());
            }
        }
        throw new IllegalArgumentException("payload.path is required when there is no active text editor");
    }

    private static Match findFirst(CharSequence text, String query, boolean caseSensitive,
                                   boolean regex, int startOffset) {
        if (regex) {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            Matcher matcher = Pattern.compile(query, flags).matcher(text);
            if (matcher.find(startOffset)) return new Match(matcher.start(), matcher.end());
            return null;
        }
        if (caseSensitive) {
            int index = text.toString().indexOf(query, startOffset);
            return index < 0 ? null : new Match(index, index + query.length());
        }
        for (int index = startOffset; index + query.length() <= text.length(); index++) {
            if (text.toString().regionMatches(true, index, query, 0, query.length())) {
                return new Match(index, index + query.length());
            }
        }
        return null;
    }

    private static void activateEditorWindow(Editor editor) {
        var component = editor.getContentComponent();
        Project project = editor.getProject();
        Window window = project == null ? null : WindowManager.getInstance().getFrame(project);
        if (window == null) window = SwingUtilities.getWindowAncestor(component);
        if (window != null) {
            if (window instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
                frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            }
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
        }
        if (project != null) {
            IdeFocusManager.getInstance(project).requestFocus(component, true);
        } else {
            component.requestFocusInWindow();
        }
    }

    private static Target resolveTarget(JsonObject payload, JsonObject targetIdentity) {
        String requested = string(payload, "path", null);
        if (requested == null || requested.isBlank()) throw new IllegalArgumentException("payload.path is required");
        String requestedProject = string(payload, "projectBasePath", null);
        if (requestedProject == null) requestedProject = string(targetIdentity, "projectBasePath", null);
        String requestedProjectId = string(targetIdentity, "projectId", null);
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String baseText = project.getBasePath();
            if (baseText == null || requestedProject != null && !pathOf(baseText).normalize().equals(pathOf(requestedProject).normalize())) continue;
            if (requestedProjectId != null && !requestedProjectId.equals(projectId(baseText))) continue;
            Path base = Path.of(baseText).toAbsolutePath().normalize();
            Path candidate = pathOf(requested);
            if (!candidate.isAbsolute()) candidate = base.resolve(candidate);
            candidate = candidate.toAbsolutePath().normalize();
            if (!candidate.startsWith(base)) continue;
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(candidate);
            if (file != null && !file.isDirectory()) return new Target(project, file);
        }
        throw new IllegalArgumentException("File was not found inside an open project");
    }

    private static Path pathOf(String value) {
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "file:", 0, 5)) {
            return Path.of(URI.create(normalized));
        }
        // Accept file URLs converted to /C:/... by a non-Windows caller.
        if (normalized.matches("^/[A-Za-z]:[\\\\/].*")) {
            normalized = normalized.substring(1);
        }
        return Path.of(normalized);
    }

    private static Position position(JsonObject object, Editor editor, int base) {
        if (object == null) throw new IllegalArgumentException("Selection start/end is required");
        int maxLine = Math.max(0, editor.getDocument().getLineCount() - 1);
        int line = Math.max(0, Math.min(integer(object, "line", base) - base, maxLine));
        int lineStart = editor.getDocument().getLineStartOffset(line);
        int lineEnd = editor.getDocument().getLineEndOffset(line);
        int offset = Math.min(lineEnd, lineStart + Math.max(0, integer(object, "column", base) - base));
        return new Position(offset);
    }

    private static int lineCount(VirtualFile file) {
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        return document == null ? 1 : document.getLineCount();
    }

    private static boolean hasValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    private static Map<String, Object> result(boolean success, String command, Object data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", success); value.put("command", command); value.put("data", data); return value;
    }
    private static Map<String, Object> error(String command, String code, String message) {
        return Map.of("success", false, "command", Objects.toString(command, "unknown"),
                "error", Map.of("code", code, "message", Objects.toString(message, "Unknown error")));
    }
    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
    private static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }
    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback;
    }
    private record Target(Project project, VirtualFile file) {}
    private record Position(int offset) {}
    private record Match(int start, int end) {}
}

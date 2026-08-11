package com.mirror.ideafocusbridge;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class FocusSnapshotCollector {
    private FocusSnapshotCollector() {}

    static Map<String, Object> collect() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("focus", describeFocus(focus));

        Editor focusedEditor = editorFrom(focus);
        Editor editor = focusedEditor != null ? focusedEditor : selectedEditor(focus);
        root.put("activeEditor", describeEditor(editor, focusedEditor == editor));
        return root;
    }

    private static Map<String, Object> describeFocus(Component component) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (component == null) {
            value.put("type", "none");
            return value;
        }
        value.put("type", component instanceof JTextComponent ? "textInput" :
                editorFrom(component) != null ? "editor" : "component");
        value.put("className", component.getClass().getName());
        value.put("name", component.getName());
        value.put("enabled", component.isEnabled());
        value.put("visible", component.isVisible());
        value.put("showing", component.isShowing());
        value.put("bounds", screenBounds(component));
        value.put("hierarchy", hierarchy(component));

        if (component.getAccessibleContext() != null) {
            value.put("accessibleName", component.getAccessibleContext().getAccessibleName());
            value.put("accessibleDescription", component.getAccessibleContext().getAccessibleDescription());
            if (component.getAccessibleContext().getAccessibleRole() != null) {
                value.put("accessibleRole", component.getAccessibleContext().getAccessibleRole().toDisplayString());
            }
        }
        Window window = SwingUtilities.getWindowAncestor(component);
        if (window != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("className", window.getClass().getName());
            w.put("title", window instanceof Frame f ? f.getTitle() : window instanceof Dialog d ? d.getTitle() : null);
            w.put("bounds", screenBounds(window));
            value.put("window", w);
        }
        if (component instanceof JTextComponent text) describeTextInput(text, value);
        return value;
    }

    private static void describeTextInput(JTextComponent text, Map<String, Object> value) {
        boolean password = text instanceof JPasswordField;
        FocusBridgeSettings.Data settings = FocusBridgeSettings.getInstance().data();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("editable", text.isEditable());
        input.put("caretOffset", text.getCaretPosition());
        input.put("selectionStart", text.getSelectionStart());
        input.put("selectionEnd", text.getSelectionEnd());
        input.put("textLength", text.getDocument().getLength());
        input.put("password", password);
        if (settings.sendText && !password) {
            input.put("text", text.getText());
            input.put("selectedText", text.getSelectedText());
        } else input.put("textRedacted", true);
        try {
            Rectangle2D rect = text.modelToView2D(text.getCaretPosition());
            Point p = new Point((int) Math.round(rect.getX()), (int) Math.round(rect.getY()));
            SwingUtilities.convertPointToScreen(p, text);
            input.put("caret", Map.of("screenX", p.x, "screenY", p.y,
                    "componentX", rect.getX(), "componentY", rect.getY(), "height", rect.getHeight()));
        } catch (Exception ignored) { }
        value.put("input", input);
    }

    private static Map<String, Object> describeEditor(Editor editor, boolean ownsFocus) {
        if (editor == null || editor.isDisposed()) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ownsFocus", ownsFocus);
        value.put("editable", !editor.isViewer() && editor.getDocument().isWritable());
        Project project = editor.getProject();
        if (project != null) {
            String basePath = Objects.toString(project.getBasePath(), "");
            value.put("project", Map.of("projectId", BridgeCommands.projectId(basePath),
                    "name", project.getName(), "basePath", basePath));
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file != null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", file.getName()); f.put("path", file.getPath()); f.put("url", file.getUrl());
            f.put("writable", file.isWritable()); f.put("fileType", file.getFileType().getName());
            if (project != null && project.getBasePath() != null) {
                String base = project.getBasePath().replace('\\', '/');
                String path = file.getPath().replace('\\', '/');
                if (path.startsWith(base + "/")) f.put("projectRelativePath", path.substring(base.length() + 1));
            }
            value.put("file", f);
        }

        var caret = editor.getCaretModel().getPrimaryCaret();
        LogicalPosition logical = caret.getLogicalPosition();
        VisualPosition visual = caret.getVisualPosition();
        Point xy = editor.visualPositionToXY(visual);
        SwingUtilities.convertPointToScreen(xy, editor.getContentComponent());
        value.put("caret", Map.of("offset", caret.getOffset(), "line", logical.line, "column", logical.column,
                "lineNumber", logical.line + 1, "columnNumber", logical.column + 1,
                "visualLine", visual.line, "visualColumn", visual.column,
                "screenX", xy.x, "screenY", xy.y));

        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("hasSelection", caret.hasSelection());
        selection.put("startOffset", caret.getSelectionStart());
        selection.put("endOffset", caret.getSelectionEnd());
        if (FocusBridgeSettings.getInstance().data().sendText) selection.put("text", caret.getSelectedText());
        value.put("selection", selection);

        List<Map<String, Object>> carets = new ArrayList<>();
        for (var c : editor.getCaretModel().getAllCarets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("primary", c == caret); item.put("offset", c.getOffset());
            item.put("line", c.getLogicalPosition().line); item.put("column", c.getLogicalPosition().column);
            item.put("selectionStart", c.getSelectionStart()); item.put("selectionEnd", c.getSelectionEnd());
            if (FocusBridgeSettings.getInstance().data().sendText) item.put("selectedText", c.getSelectedText());
            carets.add(item);
        }
        value.put("carets", carets);
        addTextContext(editor, caret.getOffset(), value);
        return value;
    }

    private static void addTextContext(Editor editor, int offset, Map<String, Object> value) {
        if (!FocusBridgeSettings.getInstance().data().sendText) return;
        var document = editor.getDocument();
        int line = document.getLineNumber(Math.min(offset, document.getTextLength()));
        int lineStart = document.getLineStartOffset(line), lineEnd = document.getLineEndOffset(line);
        int n = FocusBridgeSettings.getInstance().data().contextCharacters;
        int start = Math.max(0, offset - n), end = Math.min(document.getTextLength(), offset + n);
        value.put("textContext", Map.of("currentLine", document.getText(new TextRange(lineStart, lineEnd)),
                "beforeCaret", document.getText(new TextRange(start, offset)),
                "afterCaret", document.getText(new TextRange(offset, end))));
    }

    private static Editor editorFrom(Component component) {
        if (component == null) return null;
        try { return CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext(component)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Editor selectedEditor(Component focus) {
        if (focus != null) {
            try {
                Project focusedProject = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(focus));
                if (focusedProject != null) {
                    Editor editor = FileEditorManager.getInstance(focusedProject).getSelectedTextEditor();
                    if (editor != null) return editor;
                }
            } catch (RuntimeException ignored) { }
        }
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) return editor;
        }
        return null;
    }

    private static Map<String, Object> screenBounds(Component c) {
        try {
            Point p = c.getLocationOnScreen();
            return Map.of("screenX", p.x, "screenY", p.y, "width", c.getWidth(), "height", c.getHeight());
        } catch (IllegalComponentStateException ignored) {
            return Map.of("width", c.getWidth(), "height", c.getHeight());
        }
    }

    private static List<String> hierarchy(Component component) {
        List<String> result = new ArrayList<>();
        for (Component c = component; c != null && result.size() < 16; c = c.getParent()) {
            result.add(c.getClass().getName());
        }
        return result;
    }
}

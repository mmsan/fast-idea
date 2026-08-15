package com.mirror.ideafocusbridge;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.IdeFrameEx;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

final class FocusSnapshotCollector {
    private static String lastEditorKey;
    private static long lastEditorOpenedAt;

    private FocusSnapshotCollector() {}

    static Map<String, Object> collect() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("focus", describeFocus(focus));

        Editor focusedEditor = editorFrom(focus);
        Editor editor = focusedEditor != null ? focusedEditor : selectedEditor(focus);
        root.put("activeEditor", describeEditor(editor, focusedEditor == editor));
        root.put("editorOpenedAt", editorOpenedAt(editor));
        root.put("fullScreen", isFullScreen(editor));
        return root;
    }

    static Map<String, Object> collectStatus() {
        return status(collect());
    }

    static boolean currentInputable() {
        return Boolean.TRUE.equals(collectStatus().get("inputable"));
    }

    static String currentContext() {
        Object activeEditor = collect().get("activeEditor");
        if (!(activeEditor instanceof Map<?, ?> editor)) return null;
        Object context = editor.get("context");
        return context == null ? null : String.valueOf(context);
    }

    static Map<String, Object> currentObjectInfo() {
        Object value = collectStatus().get("objectInfo");
        return value instanceof Map<?, ?> map ? copyMap(map) : null;
    }

    private static Map<String, Object> status(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object focus = snapshot.get("focus");
        Object activeEditor = snapshot.get("activeEditor");
        result.put("inputable", isInputable(focus, activeEditor));
        result.put("focus", focus);
        result.put("activeEditor", activeEditor);
        result.put("objectInfo", objectInfo(activeEditor));
        return result;
    }

    private static boolean isInputable(Object focusValue, Object activeEditorValue) {
        if (!(focusValue instanceof Map<?, ?> focus) || !isAvailable(focus)) return false;
        String type = Objects.toString(focus.get("type"), "");
        if ("textInput".equals(type)) {
            Object inputValue = focus.get("input");
            return inputValue instanceof Map<?, ?> input && Boolean.TRUE.equals(input.get("editable"));
        }
        if (!"editor".equals(type) || !(activeEditorValue instanceof Map<?, ?> editor)) return false;
        Object editable = editor.get("editable");
        Object fileValue = editor.get("file");
        boolean writable = !(fileValue instanceof Map<?, ?> file) || !Boolean.FALSE.equals(file.get("writable"));
        return Boolean.TRUE.equals(editor.get("ownsFocus")) &&
                (Boolean.TRUE.equals(editable) || editable == null && writable);
    }

    private static boolean isAvailable(Map<?, ?> focus) {
        return !Boolean.FALSE.equals(focus.get("enabled")) &&
                !Boolean.FALSE.equals(focus.get("visible")) &&
                !Boolean.FALSE.equals(focus.get("showing"));
    }

    private static Map<String, Object> objectInfo(Object activeEditorValue) {
        if (!(activeEditorValue instanceof Map<?, ?> editor)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("project", "file", "context", "expressionContext")) {
            if (editor.containsKey(key)) result.put(key, editor.get(key));
        }
        return result;
    }

    private static Map<String, Object> copyMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    static String currentSelectedText() {
        if (!FocusBridgeSettings.getInstance().data().sendText) return null;
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (focus instanceof JPasswordField) return null;
        if (focus instanceof JTextComponent textInput) {
            String selected = textInput.getSelectedText();
            return selected == null ? "" : selected;
        }
        Editor focusedEditor = editorFrom(focus);
        Editor editor = focusedEditor != null ? focusedEditor : selectedEditor(focus);
        if (editor == null || editor.isDisposed()) return null;

        var caret = editor.getCaretModel().getPrimaryCaret();
        return caret.hasSelection() ? caret.getSelectedText() : "";
    }

    private static long editorOpenedAt(Editor editor) {
        if (editor == null || editor.isDisposed()) return 0;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        Project project = editor.getProject();
        String projectPath = project == null ? "" : Objects.toString(project.getBasePath(), "");
        String filePath = file == null ? "" : file.getUrl();
        String key = projectPath + "\n" + filePath + "\n" + System.identityHashCode(editor);
        if (!key.equals(lastEditorKey)) {
            lastEditorKey = key;
            lastEditorOpenedAt = System.currentTimeMillis();
        }
        return lastEditorOpenedAt;
    }

    private static boolean isFullScreen(Editor editor) {
        if (editor != null && editor.getProject() != null) {
            javax.swing.JFrame frame = WindowManager.getInstance().getFrame(editor.getProject());
            if (frame instanceof IdeFrameEx ideFrame) return ideFrame.isInFullScreen();
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null) {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                javax.swing.JFrame frame = WindowManager.getInstance().getFrame(project);
                if (frame == active && frame instanceof IdeFrameEx ideFrame) return ideFrame.isInFullScreen();
            }
        }
        return false;
    }

    static List<VirtualFile> selectedResourceFiles() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        Component tree = treeFrom(focus);
        if (tree == null) return List.of();
        try {
            VirtualFile[] files = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(
                    DataManager.getInstance().getDataContext(tree));
            if (files == null || files.length == 0) return List.of();
            return Arrays.stream(files).filter(Objects::nonNull).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static Component treeFrom(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JTree) return current;
        }
        return null;
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
        addEditorContext(editor, caret.getOffset(), value);
        return value;
    }

    /**
     * Keep semantic context alongside the existing editor snapshot. PSI is used
     * when the installed language plugin exposes it; unsupported languages
     * simply return the stable generic defaults instead of guessing a type.
     */
    private static void addEditorContext(Editor editor, int offset, Map<String, Object> value) {
        value.put("context", "none");
        Project project = editor.getProject();
        if (project == null) return;

        try {
            PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (file == null) return;
            PsiElement element = psiElementAt(file, offset);
            if (element == null) return;
            value.put("context", syntaxContext(file, element, offset));
            Map<String, Object> expression = expressionContext(file, element, offset, editor.getDocument().getCharsSequence());
            if (!expression.isEmpty()) value.put("expressionContext", expression);
        } catch (RuntimeException ignored) {
            // A language plugin can be unloaded independently of this plugin.
            // The normal editor snapshot must remain available in that case.
        }
    }

    private static PsiElement psiElementAt(PsiFile file, int offset) {
        int length = file.getTextLength();
        int safe = Math.max(0, Math.min(offset, length));
        PsiElement element = safe < length ? file.findElementAt(safe) : null;
        if (element == null && safe > 0) element = file.findElementAt(safe - 1);
        return element;
    }

    private static String syntaxContext(PsiFile file, PsiElement element, int offset) {
        for (PsiElement current = element; current != null && current != file; current = current.getParent()) {
            if (!containsCaret(current, offset)) continue;
            if (isStringElement(current)) return quoteKind(current.getText());
            if (isObjectLiteral(current)) return "braces";
            if (isParentheses(current)) return "parentheses";
        }
        return "none";
    }

    private static boolean containsCaret(PsiElement element, int offset) {
        TextRange range = element.getTextRange();
        return range != null && range.getStartOffset() <= offset && offset <= range.getEndOffset();
    }

    private static boolean isStringElement(PsiElement element) {
        String className = element.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String typeName = element.getNode() == null ? "" : element.getNode().getElementType().toString().toLowerCase(Locale.ROOT);
        String text = Objects.toString(element.getText(), "").trim();
        boolean quoted = text.length() >= 2 && ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("`") && text.endsWith("`")));
        return quoted && (className.contains("string") || className.contains("literal")
                || className.contains("template") || typeName.contains("string")
                || typeName.contains("literal") || typeName.contains("template"));
    }

    private static String quoteKind(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("'")) return "singleQuote";
        if (value.startsWith("\"")) return "doubleQuote";
        if (value.startsWith("`")) return "backtick";
        return "none";
    }

    private static boolean isObjectLiteral(PsiElement element) {
        String className = element.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String typeName = element.getNode() == null ? "" : element.getNode().getElementType().toString().toLowerCase(Locale.ROOT);
        return className.contains("objectliteral") || className.contains("objectliteralexpression")
                || typeName.contains("object_literal") || typeName.contains("objectliteral");
    }

    private static boolean isParentheses(PsiElement element) {
        String className = element.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (!(className.contains("parenthes") || className.contains("argument")
                || className.contains("parameter") || className.contains("expressionlist"))) return false;
        String text = Objects.toString(element.getText(), "").trim();
        return text.startsWith("(") && text.endsWith(")");
    }

    private static Map<String, Object> expressionContext(PsiFile file, PsiElement element, int offset,
                                                           CharSequence documentText) {
        PsiElement resolvedElement = null;
        Object resolved = null;
        for (PsiElement current = element; current != null && current != file; current = current.getParent()) {
            for (PsiReference reference : current.getReferences()) {
                try {
                    Object candidate = reference.resolve();
                    if (candidate != null) {
                        resolvedElement = current;
                        resolved = candidate;
                        break;
                    }
                } catch (RuntimeException ignored) { }
            }
            if (resolved != null) break;
        }

        String expression = extractReference(documentText, offset);
        if ((expression == null || expression.isBlank()) && resolvedElement != null) {
            expression = cleanExpression(resolvedElement.getText());
        }
        if (expression == null || expression.isBlank() || expression.length() > 512) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expression", expression);
        if (resolved != null) {
            result.put("kind", symbolKind(resolved));
            String type = typeText(resolved);
            if (type != null && !type.isBlank()) result.put("type", type);
            if ("method".equals(result.get("kind")) && type != null && !type.isBlank()) {
                result.put("returnType", type);
                List<Map<String, Object>> parameters = methodParameters(resolved);
                if (!parameters.isEmpty()) result.put("parameters", parameters);
            }
            List<Map<String, Object>> members = members(resolved);
            if (!members.isEmpty()) result.put("members", members);
        } else {
            result.put("kind", "reference");
        }
        return result;
    }

    private static String cleanExpression(String text) {
        if (text == null) return null;
        String value = text.trim();
        return value.contains("\n") || value.contains("\r") ? null : value;
    }

    /** Extracts a reference-shaped expression only as a fallback when no PSI reference resolves. */
    private static String extractReference(CharSequence source, int offset) {
        if (source == null || source.length() == 0) return null;
        int cursor = Math.max(0, Math.min(offset, source.length()));
        int end = cursor;
        while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
        int start = cursor;
        while (start > 0 && isIdentifierPart(source.charAt(start - 1))) start--;

        while (start > 0) {
            int index = start - 1;
            while (index >= 0 && Character.isWhitespace(source.charAt(index))) index--;
            if (index < 0 || source.charAt(index) != '.') break;
            index--;
            while (index >= 0 && Character.isWhitespace(source.charAt(index))) index--;
            if (index >= 0 && source.charAt(index) == ')') {
                index = matchingOpen(source, index, '(', ')');
                if (index < 0) break;
                start = index;
            } else {
                while (index >= 0 && isIdentifierPart(source.charAt(index))) index--;
                start = index + 1;
            }
        }
        String value = source.subSequence(start, end).toString().trim();
        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*(?:[A-Za-z_$][A-Za-z0-9_$]*|\\([^\\n\\r]*\\)))*\\s*\\.?")
                ? value.replaceAll("\\s+", "") : null;
    }

    private static int matchingOpen(CharSequence source, int close, char open, char closing) {
        int depth = 0;
        for (int index = close; index >= 0; index--) {
            char c = source.charAt(index);
            if (c == closing) depth++;
            else if (c == open && --depth == 0) return index;
        }
        return -1;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String symbolKind(Object resolved) {
        String name = resolved.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("method") || name.contains("function")) return "method";
        if (name.contains("field") || name.contains("property")) return "property";
        if (name.contains("class") || name.contains("interface") || name.contains("type")) return "type";
        if (name.contains("variable") || name.contains("parameter")) return "variable";
        return "reference";
    }

    private static String typeText(Object resolved) {
        Object type = invokeNoArg(resolved, "getType");
        if (type == null) type = invokeNoArg(resolved, "getReturnType");
        String text = canonicalType(type);
        if (text != null) return text;
        String qualified = stringValue(invokeNoArg(resolved, "getQualifiedName"));
        return qualified == null ? stringValue(invokeNoArg(resolved, "getName")) : qualified;
    }

    private static String canonicalType(Object type) {
        if (type == null) return null;
        String text = stringValue(invokeNoArg(type, "getCanonicalText"));
        if (text == null) text = stringValue(invokeNoArg(type, "getPresentableText"));
        return text == null ? cleanExpression(type.toString()) : text;
    }

    private static List<Map<String, Object>> members(Object resolved) {
        Object type = invokeNoArg(resolved, "getType");
        Object owner = invokeNoArg(type, "resolve");
        if (owner == null && resolved.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("class")) owner = resolved;
        if (owner == null) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        addMembers(result, invokeNoArg(owner, "getAllFields"), false);
        addMembers(result, invokeNoArg(owner, "getAllMethods"), true);
        return result.size() <= 15 ? result : new ArrayList<>(result.subList(0, 15));
    }

    private static void addMembers(List<Map<String, Object>> result, Object values, boolean method) {
        if (values == null) return;
        int length = values.getClass().isArray() ? Array.getLength(values) : 0;
        for (int index = 0; index < length && result.size() < 15; index++) {
            Object member = Array.get(values, index);
            String name = stringValue(invokeNoArg(member, "getName"));
            if (name == null || name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("kind", method ? "method" : "property");
            String type = method ? canonicalType(invokeNoArg(member, "getReturnType"))
                    : canonicalType(invokeNoArg(member, "getType"));
            if (type != null && !type.isBlank()) item.put("type", type);
            if (method) {
                List<Map<String, Object>> parameters = methodParameters(member);
                if (!parameters.isEmpty()) item.put("parameters", parameters);
                if (type != null && !type.isBlank()) item.put("returnType", type);
            }
            result.add(item);
        }
    }

    private static List<Map<String, Object>> methodParameters(Object method) {
        Object list = invokeNoArg(method, "getParameterList");
        Object parameters = invokeNoArg(list, "getParameters");
        if (parameters == null || !parameters.getClass().isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < Array.getLength(parameters) && index < 15; index++) {
            Object parameter = Array.get(parameters, index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", Objects.toString(stringValue(invokeNoArg(parameter, "getName")), "arg" + index));
            String type = canonicalType(invokeNoArg(parameter, "getType"));
            if (type != null) item.put("type", type);
            result.add(item);
        }
        return result;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return method.invoke(target);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
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

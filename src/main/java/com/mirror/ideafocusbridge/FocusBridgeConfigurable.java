package com.mirror.ideafocusbridge;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class FocusBridgeConfigurable implements Configurable {
    private JBTextField host;
    private JBTextField port;
    private JBTextField path;
    private JBPasswordField token;
    private JBCheckBox autoConnect;
    private JBCheckBox sendText;
    private JBTextField contextCharacters;

    @Override public @NlsContexts.ConfigurableName String getDisplayName() { return "Fast Idea Bridge"; }

    @Override public @Nullable JComponent createComponent() {
        var state = FocusBridgeSettings.getInstance().data();
        host = new JBTextField(state.host);
        port = new JBTextField(Integer.toString(state.port));
        path = new JBTextField(state.path);
        token = new JBPasswordField();
        token.setText(state.token);
        autoConnect = new JBCheckBox("Connect automatically", state.autoConnect);
        sendText = new JBCheckBox("Send input, selection and source context text", state.sendText);
        contextCharacters = new JBTextField(Integer.toString(state.contextCharacters));
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Host:"), host)
                .addLabeledComponent(new JBLabel("Port:"), port)
                .addLabeledComponent(new JBLabel("WebSocket path:"), path)
                .addLabeledComponent(new JBLabel("Bearer token:"), token)
                .addComponent(autoConnect)
                .addComponent(sendText)
                .addLabeledComponent(new JBLabel("Editor context characters:"), contextCharacters)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override public boolean isModified() {
        var s = FocusBridgeSettings.getInstance().data();
        return !host.getText().trim().equals(s.host)
                || parse(port.getText(), 9072) != s.port
                || !path.getText().trim().equals(s.path)
                || !new String(token.getPassword()).equals(s.token)
                || autoConnect.isSelected() != s.autoConnect
                || sendText.isSelected() != s.sendText
                || parse(contextCharacters.getText(), 200) != s.contextCharacters;
    }

    @Override public void apply() {
        var s = FocusBridgeSettings.getInstance().data();
        s.host = host.getText().trim();
        s.port = Math.max(1, Math.min(65535, parse(port.getText(), 9072)));
        s.path = path.getText().trim();
        s.token = new String(token.getPassword());
        s.autoConnect = autoConnect.isSelected();
        s.sendText = sendText.isSelected();
        s.contextCharacters = Math.max(0, Math.min(5000, parse(contextCharacters.getText(), 200)));
        FocusBridgeService.getInstance().settingsChanged();
    }

    @Override public void reset() {
        var s = FocusBridgeSettings.getInstance().data();
        host.setText(s.host); port.setText(Integer.toString(s.port)); path.setText(s.path);
        token.setText(s.token); autoConnect.setSelected(s.autoConnect); sendText.setSelected(s.sendText);
        contextCharacters.setText(Integer.toString(s.contextCharacters));
    }

    private static int parse(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (RuntimeException ignored) { return fallback; }
    }
}

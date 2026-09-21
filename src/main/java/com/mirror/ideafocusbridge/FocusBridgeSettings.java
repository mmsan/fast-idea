package com.mirror.ideafocusbridge;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@Service(Service.Level.APP)
@State(name = "IdeaFocusBridgeSettings", storages = @Storage("ideaFocusBridge.xml"))
public final class FocusBridgeSettings implements PersistentStateComponent<FocusBridgeSettings.Data> {
    public static final class Data {
        public String host = "127.0.0.1";
        public int port = 9072;
        public String path = "";
        public String token = "";
        public boolean autoConnect = true;
        public boolean sendText = true;
        public int contextCharacters = 200;
        public String installationId = UUID.randomUUID().toString();
    }

    private Data data = new Data();

    public static FocusBridgeSettings getInstance() {
        return ApplicationManager.getApplication().getService(FocusBridgeSettings.class);
    }

    @Override public @Nullable Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) {
        if (state.installationId == null || state.installationId.isBlank()) {
            state.installationId = UUID.randomUUID().toString();
        }
        data = state;
    }

    public Data data() { return data; }

    public String websocketUrl() {
        String normalizedPath = data.path == null || data.path.isBlank()
                ? "" : (data.path.startsWith("/") ? data.path : "/" + data.path);
        return "ws://" + data.host.trim() + ":" + data.port + normalizedPath;
    }
}

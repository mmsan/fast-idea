package com.mirror.ideafocusbridge;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginNode;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.updateSettings.impl.PluginDownloader;
import com.intellij.util.text.VersionComparatorUtil;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalPluginUpdateChecker {
    private static final String PLUGIN_ID = "com.mirror.ideafocusbridge";
    private static final String DEV_MODE_PROPERTY = "fast.idea.bridge.dev";
    private static final String AUTO_RESTART_PROPERTY = "fast.idea.bridge.autoRestart";
    private static final String DECLINED_VERSION_PROPERTY = "fast.idea.bridge.declinedUpdateVersion";
    private static final String DEFAULT_REPOSITORY =
            "http://127.0.0.1:8800/idea-plugins/updatePlugins.xml";
    private static final int MAX_METADATA_BYTES = 1_048_576;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fast-idea-bridge-updater");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile String triggeredVersion;
    private volatile long lastTriggerMillis;
    private volatile String preparedVersion;

    void start() {
        if (!Boolean.parseBoolean(System.getProperty(DEV_MODE_PROPERTY, "false"))) {
            BridgeLog.info("local plugin update polling disabled; devMode=false");
            return;
        }
        BridgeLog.info("local plugin update polling enabled; initialDelaySeconds=10; intervalSeconds=10");
        executor.scheduleWithFixedDelay(this::checkSafely, 10, 10, TimeUnit.SECONDS);
    }

    void dispose() {
        executor.shutdownNow();
    }

    private void checkSafely() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            check();
        } catch (Exception error) {
            BridgeLog.warn("local plugin update check failed", error);
        } finally {
            checking.set(false);
        }
    }

    private void check() throws Exception {
        URI repository = localRepositoryUri();
        HttpURLConnection connection = (HttpURLConnection) repository.toURL().openConnection();
        connection.setConnectTimeout(1_000);
        connection.setReadTimeout(2_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/xml,text/xml");
        byte[] metadata;
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_METADATA_BYTES) return;
            metadata = connection.getInputStream().readNBytes(MAX_METADATA_BYTES + 1);
        } finally {
            connection.disconnect();
        }
        if (metadata.length == 0 || metadata.length > MAX_METADATA_BYTES) return;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(metadata));
        var plugins = document.getElementsByTagName("plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            var element = plugins.item(index).getAttributes();
            var id = element.getNamedItem("id");
            var version = element.getNamedItem("version");
            if (id == null || version == null || !PLUGIN_ID.equals(id.getNodeValue())) continue;
            var url = element.getNamedItem("url");
            if (url == null) return;
            URI downloadUri = safeUri(repository.resolve(url.getNodeValue()).toString());
            if (downloadUri == null) return;
            triggerUpdateIfNewer(version.getNodeValue(), downloadUri, repository);
            return;
        }
    }

    private void triggerUpdateIfNewer(String availableVersion, URI downloadUri, URI repository) {
        IdeaPluginDescriptor installed = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (installed == null || availableVersion == null || availableVersion.isBlank()) return;
        if (VersionComparatorUtil.compare(availableVersion, installed.getVersion()) <= 0) {
            triggeredVersion = null;
            lastTriggerMillis = 0;
            return;
        }
        if (availableVersion.equals(PropertiesComponent.getInstance().getValue(DECLINED_VERSION_PROPERTY))) return;
        if (availableVersion.equals(preparedVersion)) return;
        long now = System.currentTimeMillis();
        if (availableVersion.equals(triggeredVersion) && now - lastTriggerMillis < 10_000) return;
        triggeredVersion = availableVersion;
        lastTriggerMillis = now;
        BridgeLog.info("local plugin update found; installed=" + installed.getVersion()
                + "; available=" + availableVersion);
        if (!installing.compareAndSet(false, true)) return;
        try {
            PluginNode node = new PluginNode(PluginId.getId(PLUGIN_ID), "Fast Idea Bridge", availableVersion);
            node.setDownloadUrl(downloadUri.toString());
            node.setRepositoryName(repository.toString());
            PluginDownloader downloader = PluginDownloader.createDownloader(
                    node, repository.toString(), PluginManagerCore.getBuildNumber());
            if (!downloader.prepareToInstall(new EmptyProgressIndicator())) {
                installing.set(false);
                return;
            }
            downloader.install();
            preparedVersion = availableVersion;
            BridgeLog.info("local plugin update prepared; version=" + availableVersion);
            installing.set(false);
            if (Boolean.parseBoolean(System.getProperty(AUTO_RESTART_PROPERTY, "false"))) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    int answer = Messages.showYesNoDialog(
                            "Fast Idea Bridge " + availableVersion + " 已下载并准备完成。是否立即重启 IDEA 并安装更新？",
                            "Fast Idea Bridge 更新",
                            "立即重启",
                            "稍后",
                            Messages.getInformationIcon());
                    if (answer == Messages.YES) {
                        PropertiesComponent.getInstance().unsetValue(DECLINED_VERSION_PROPERTY);
                        BridgeLog.info("local plugin update restart confirmed; version=" + availableVersion);
                        ApplicationManagerEx.getApplicationEx().restart(true);
                    } else {
                        PropertiesComponent.getInstance().setValue(DECLINED_VERSION_PROPERTY, availableVersion);
                        BridgeLog.info("local plugin update restart deferred; version=" + availableVersion);
                    }
                });
            }
        } catch (Exception error) {
            installing.set(false);
            BridgeLog.warn("local plugin update preparation failed; version=" + availableVersion, error);
        }
    }

    private static URI localRepositoryUri() {
        String configured = System.getProperty("idea.plugin.hosts", "");
        for (String value : configured.split(";")) {
            URI candidate = safeUri(value.trim());
            if (candidate != null) return candidate;
        }
        return URI.create(DEFAULT_REPOSITORY);
    }

    private static URI safeUri(String value) {
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return null;
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))) return null;
            return uri;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}

package org.lsposed.manager.repo;

import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.repo.model.OnlineModule;
import org.lsposed.manager.repo.model.Release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RepoLoader {
    private Map<String, OnlineModule> onlineModules = new HashMap<>();
    private Map<String, ModuleVersion> latestVersion = new ConcurrentHashMap<>();

    public static class ModuleVersion {
        public String versionName;
        public long versionCode;

        private ModuleVersion(long versionCode, String versionName) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        public boolean upgradable(long versionCode, String versionName) {
            return this.versionCode > versionCode || (this.versionCode == versionCode && !versionName.replace(' ', '_').equals(this.versionName));
        }

    }

    private final Path repoFile = Paths.get(App.getInstance().getFilesDir().getAbsolutePath(), "repo.json");
    private final Set<RepoListener> listeners = ConcurrentHashMap.newKeySet();
    private boolean repoLoaded = false;
    private static final String originRepoUrl = "https://modules.lsposed.org/";
    private static final String backupRepoUrl = "https://backup.modules.lsposed.org/";
    private static String repoUrl = originRepoUrl;
    private final Resources resources = App.getInstance().getResources();
    private final String[] channels = resources.getStringArray(R.array.update_channel_values);

    private static class Holder {
        private static final RepoLoader INSTANCE = new RepoLoader();
        static {
            App.getExecutorService().submit(() -> INSTANCE.loadLocalData(true));
        }
    }

    public static RepoLoader getInstance() {
        return Holder.INSTANCE;
    }

    private RepoLoader() {}

    private String getPreferredRepoUrl() {
        var source = App.getPreferences().getString("repo_source", "SOURCE_ORIGIN");
        return "SOURCE_BACKUP".equals(source) ? backupRepoUrl : originRepoUrl;
    }

    public boolean isRepoLoaded() {
        return repoLoaded;
    }

    synchronized public void loadRemoteData() {
        loadRemoteData(getPreferredRepoUrl(), true);
    }

    synchronized private void loadRemoteData(String url, boolean allowFallback) {
        repoUrl = url;
        repoLoaded = false;
        try (var response = App.getOkHttpClient().newCall(new Request.Builder().url(repoUrl + "modules.json").build()).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                Files.write(repoFile, response.body().string().getBytes(StandardCharsets.UTF_8));
                loadLocalData(false);
            }
        } catch (Throwable e) {
            Log.e(App.TAG, "load remote data", e);
            listeners.forEach(l -> l.onThrowable(e));
            if (allowFallback) {
                loadRemoteData(url.equals(originRepoUrl) ? backupRepoUrl : originRepoUrl, false);
            }
        }
    }

    synchronized public void loadLocalData(boolean updateRemoteRepo) {
        repoLoaded = false;
        try {
            if (Files.notExists(repoFile)) {
                loadRemoteData();
                updateRemoteRepo = false;
            }
            String bodyString = Files.readString(repoFile);
            OnlineModule[] repoModules = new Gson().fromJson(bodyString, OnlineModule[].class);
            
            onlineModules = Arrays.stream(repoModules)
                    .collect(Collectors.toMap(OnlineModule::getName, m -> m, (a, b) -> b));

            var channel = App.getPreferences().getString("update_channel", channels[0]);
            updateLatestVersion(repoModules, channel);
        } catch (Throwable t) {
            Log.e(App.TAG, Log.getStackTraceString(t));
            listeners.forEach(l -> l.onThrowable(t));
        } finally {
            repoLoaded = true;
            listeners.forEach(RepoListener::onRepoLoaded);
            if (updateRemoteRepo) loadRemoteData();
        }
    }

    synchronized private void updateLatestVersion(OnlineModule[] modules, String channel) {
        repoLoaded = false;
        Map<String, ModuleVersion> versions = new ConcurrentHashMap<>();
        
        for (var mod : modules) {
            String targetRelease = selectVersionString(mod, channel);
            if (targetRelease == null) continue;

            var splits = targetRelease.split("-", 2);
            if (splits.length < 2) continue;

            try {
                long verCode = Long.parseLong(splits[0]);
                versions.put(mod.getName(), new ModuleVersion(verCode, splits[1]));
            } catch (NumberFormatException ignored) {}
        }
        latestVersion = versions;
        repoLoaded = true;
        listeners.forEach(RepoListener::onRepoLoaded);
    }

    public void updateLatestVersion(String channel) {
        if (repoLoaded) {
            updateLatestVersion(onlineModules.values().toArray(new OnlineModule[0]), channel);
        }
    }

    @Nullable
    public ModuleVersion getModuleLatestVersion(String packageName) {
        return repoLoaded && packageName != null ? latestVersion.get(packageName) : null;
    }

    @NonNull
    public List<Release> getReleases(String packageName) {
        if (!repoLoaded || packageName == null) return Collections.emptyList();
        var mod = onlineModules.get(packageName);
        if (mod == null) return Collections.emptyList();

        List<Release> result = mod.getReleases();
        if (!mod.releasesLoaded) {
            var channel = App.getPreferences().getString("update_channel", channels[0]);
            if (channel.equals(channels[2])) {
                result = firstNonEmptyList(mod.getSnapshotReleases(), mod.getBetaReleases(), mod.getReleases());
            } else if (channel.equals(channels[1])) {
                result = firstNonEmptyList(mod.getBetaReleases(), mod.getReleases());
            }
        }
        return result != null ? result : Collections.emptyList();
    }

    @Nullable
    public String getLatestReleaseTime(String packageName, String channel) {
        if (!repoLoaded || packageName == null) return null;
        var mod = onlineModules.get(packageName);
        if (mod == null) return null;

        if (channel.equals(channels[2])) {
            return firstNonEmpty(mod.getLatestSnapshotReleaseTime(), mod.getLatestBetaReleaseTime(), mod.getLatestReleaseTime());
        } else if (channel.equals(channels[1])) {
            return firstNonEmpty(mod.getLatestBetaReleaseTime(), mod.getLatestReleaseTime());
        }
        return mod.getLatestReleaseTime();
    }

    public void loadRemoteReleases(String packageName) {
        loadRemoteReleases(packageName, repoUrl, true);
    }

    private void loadRemoteReleases(String packageName, String url, boolean allowFallback) {
        App.getOkHttpClient().newCall(new Request.Builder().url(url + "module/" + packageName + ".json").build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(App.TAG, call.request().url() + e.getMessage());
                if (allowFallback) {
                    String nextUrl = url.equals(originRepoUrl) ? backupRepoUrl : originRepoUrl;
                    repoUrl = nextUrl;
                    loadRemoteReleases(packageName, nextUrl, false);
                } else {
                    listeners.forEach(l -> l.onThrowable(e));
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (response.isSuccessful() && body != null) {
                        OnlineModule mod = new Gson().fromJson(body.string(), OnlineModule.class);
                        mod.releasesLoaded = true;
                        onlineModules.replace(packageName, mod);
                        listeners.forEach(l -> l.onModuleReleasesLoaded(mod));
                    }
                } catch (Throwable t) {
                    Log.e(App.TAG, Log.getStackTraceString(t));
                    listeners.forEach(l -> l.onThrowable(t));
                }
            }
        });
    }

    public void addListener(RepoListener listener) { listeners.add(listener); }
    public void removeListener(RepoListener listener) { listeners.remove(listener); }

    @Nullable public OnlineModule getOnlineModule(String packageName) {
        return repoLoaded && packageName != null ? onlineModules.get(packageName) : null;
    }

    @Nullable public Collection<OnlineModule> getOnlineModules() {
        return repoLoaded ? onlineModules.values() : null;
    }

    private String selectVersionString(OnlineModule mod, String channel) {
        if (channel.equals(channels[2])) {
            return firstNonEmpty(mod.getLatestSnapshotRelease(), mod.getLatestBetaRelease(), mod.getLatestRelease());
        } else if (channel.equals(channels[1])) {
            return firstNonEmpty(mod.getLatestBetaRelease(), mod.getLatestRelease());
        }
        return mod.getLatestRelease();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    @SafeVarargs
    private static <T> List<T> firstNonEmptyList(List<T>... lists) {
        for (List<T> list : lists) {
            if (list != null && !list.isEmpty()) return list;
        }
        return null;
    }

    public interface RepoListener {
        default void onRepoLoaded() {}
        default void onModuleReleasesLoaded(OnlineModule module) {}
        default void onThrowable(Throwable t) { Log.e(App.TAG, "load repo failed", t); }
    }
}

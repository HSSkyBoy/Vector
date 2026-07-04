package org.lsposed.lspd.service;

import org.lsposed.lspd.service.IRemotePreferenceCallback;
import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedScopeCallback;

interface ILSPInjectedModuleService {
    long getFrameworkProperties();

    List<String> getScope();
    oneway void requestScope(in List<String> packages, IXposedScopeCallback callback);
    void removeScope(in List<String> packages);
    List<HookedProcess> getRunningTargets();
    void hotReloadModule(long targetId, in Bundle data, IHotReloadCallback callback);

    Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback);

    void updateRemotePreferences(String group, in Bundle diff);

    void deleteRemotePreferences(String group);

    ParcelFileDescriptor openRemoteFile(String path);

    String[] getRemoteFileList();

    boolean deleteRemoteFile(String path);
}

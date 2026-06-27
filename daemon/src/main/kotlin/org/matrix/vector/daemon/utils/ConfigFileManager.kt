package org.matrix.vector.daemon.utils

import android.util.Log
import java.util.Properties
import java.util.zip.ZipFile

private const val TAG = "VectorConfigFileManager"
private const val MODULE_PROP_PATH = "META-INF/xposed/module.prop"
private const val JAVA_INIT_LIST = "META-INF/xposed/java_init.list"
private const val NATIVE_INIT_LIST = "META-INF/xposed/native_init.list"

object ConfigFileManager {
    fun readModernModuleProperties(zip: ZipFile): Properties? {
        val entry = zip.getEntry(MODULE_PROP_PATH) ?: return null
        return try {
            Properties().apply {
                zip.getInputStream(entry).use { load(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $MODULE_PROP_PATH", e)
            null
        }
    }

    fun requiresModernModuleLoading(zip: ZipFile): Boolean {
        return zip.getEntry(JAVA_INIT_LIST) != null || zip.getEntry(NATIVE_INIT_LIST) != null
    }
}

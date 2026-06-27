package org.matrix.vector.daemon.system

import android.os.Build
import android.os.IBinder
import android.os.IDeviceIdleController
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import org.matrix.vector.daemon.ipc.ManagerService

private const val DEVICE_IDLE_SERVICE = "deviceidle"
private const val TEMP_WHITELIST_DURATION = 30_000L
private const val TEMP_WHITELIST_REASON = 316

object DeviceIdleService {
  private const val TAG = "VectorDeviceIdleService"

  @Volatile private var deviceIdleController: IDeviceIdleController? = null
  @Volatile private var binder: IBinder? = null

  private val deathRecipient = IBinder.DeathRecipient {
    Log.w(TAG, "DeviceIdleService is dead")
    binder = null
    deviceIdleController = null
  }

  private fun getDeviceIdleController(): IDeviceIdleController? {
    if (binder == null || deviceIdleController == null) {
      synchronized(this) {
        if (binder == null || deviceIdleController == null) {
          binder = ServiceManager.getService(DEVICE_IDLE_SERVICE)
          if (binder == null) {
            Log.w(TAG, "DeviceIdleController is not available")
            return null
          }
          try {
            binder!!.linkToDeath(deathRecipient, 0)
          } catch (e: RemoteException) {
            Log.e(TAG, Log.getStackTraceString(e))
          }
          deviceIdleController = IDeviceIdleController.Stub.asInterface(binder)
        }
      }
    }
    return deviceIdleController
  }

  fun addPowerSaveTempWhitelistApp(packageName: String, userId: Int, reason: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
      getDeviceIdleController()?.addPowerSaveTempWhitelistApp(
          packageName, TEMP_WHITELIST_DURATION, userId, TEMP_WHITELIST_REASON, reason)
    } catch (e: Throwable) {
      Log.w(TAG, "failed to add $userId:$packageName to power save temp whitelist", e)
    }
  }
}

package org.matrix.vector.daemon.system

import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.app.IActivityManager
import android.util.Log

private const val TAG = "VectorActivityManager"

val activityManager: IActivityManager? by lazy {
    var binder: IBinder? = null
    var controller: IActivityManager? = null
    try {
        binder = ServiceManager.getService("activity")
        if (binder != null) {
            binder!!.linkToDeath({
                Log.w(TAG, "ActivityManager is dead")
            }, 0)
            controller = IActivityManager.Stub.asInterface(binder)
        }
    } catch (e: RemoteException) {
        Log.e(TAG, "Failed to get ActivityManager", e)
    }
    controller
}

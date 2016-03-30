@file:JvmName("AndroidUtils")
package com.vfpowertech.keytap.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.gcm.GoogleCloudMessaging
import com.google.android.gms.iid.InstanceID
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task

/** Run the given function in the main loop. */
fun androidRunInMain(context: Context?, body: () -> Unit) {
    val mainLooper = if (context != null)
        context.mainLooper
    else
        Looper.getMainLooper()

    Handler(mainLooper).post(body)
}

data class GCMTokenData(val username: String, val token: String)

fun gcmFetchToken(context: Context, username: String): Promise<GCMTokenData, Exception> {
    val instanceId = InstanceID.getInstance(context)
    val defaultSenderId = context.getString(R.string.gcm_defaultSenderId)
    return task {
        //we always delete any previous token; this makes it simplier to handle multiple user accounts,
        //since every time we register a new account, we invalidate the previous one if it wasn't invalidated on logout
        instanceId.deleteInstanceID()

        val token = instanceId.getToken(
            defaultSenderId,
            GoogleCloudMessaging.INSTANCE_ID_SCOPE,
            null
        )
        GCMTokenData(username, token)
    }
}

fun gcmDeleteToken(context: Context): Promise<Unit, Exception> {
    val instanceId = InstanceID.getInstance(context)
    return task {
        instanceId.deleteInstanceID()
    }
}
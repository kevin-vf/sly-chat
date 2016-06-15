package io.slychat.messenger.core.http.api.gcm

import io.slychat.messenger.core.UserCredentials
import io.slychat.messenger.core.http.HttpClientFactory
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task

class GcmAsyncClient(private val serverUrl: String, private val factory: HttpClientFactory) {
    private fun newClient(): GcmClient = GcmClient(serverUrl, factory.create())

    fun isRegistered(userCredentials: UserCredentials): Promise<IsRegisteredResponse, Exception> = task {
        newClient().isRegistered(userCredentials)
    }

    fun register(userCredentials: UserCredentials, request: RegisterRequest): Promise<RegisterResponse, Exception> = task {
        newClient().register(userCredentials, request)
    }

    fun unregister(userCredentials: UserCredentials, request: UnregisterRequest): Promise<Unit, Exception> = task {
        newClient().unregister(userCredentials, request)
    }
}

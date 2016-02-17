package com.vfpowertech.keytap.ui.services.impl

import com.vfpowertech.keytap.core.crypto.JsonFileKeyVaultStorage
import com.vfpowertech.keytap.core.http.api.registration.RegistrationInfo
import com.vfpowertech.keytap.core.http.api.registration.registrationRequestFromKeyVault
import com.vfpowertech.keytap.core.persistence.AccountInfo
import com.vfpowertech.keytap.core.persistence.AccountInfoPersistenceManager
import com.vfpowertech.keytap.ui.services.RegistrationService
import com.vfpowertech.keytap.ui.services.UIRegistrationInfo
import com.vfpowertech.keytap.ui.services.UIRegistrationResult
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

class RegistrationServiceImpl(
    serverUrl: String,
    private val accountInfoPersistenceManager: AccountInfoPersistenceManager
) : RegistrationService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val listeners = ArrayList<(String) -> Unit>()
    private val registrationClient = RegistrationClientWrapper(serverUrl)

    override fun doRegistration(info: UIRegistrationInfo): Promise<UIRegistrationResult, Exception> {

        //TODO storage needs to be moved somewhere else; need platform-specific version
        //TODO persistence should be handled by some other service so we don't need to access it manually
        val keyVaultStorage = JsonFileKeyVaultStorage(File("/tmp/keyvault.json"))

        val username = info.email
        val password = info.password

        //TODO we don't need to write the key vault to disk here, as we can fetch it during login if it doesn't exist
        //a new key vault is generated on each registration; don't upload another user's keyvault
        updateProgress("Generating key vault")
        return asyncGenerateNewKeyVault(password) bind { keyVault ->
            updateProgress("Writing key vault to disk")
            //keyVault.toStorage(keyVaultStorage)
            updateProgress("Connecting to server...")
            val registrationInfo = RegistrationInfo(username, info.name, info.phoneNumber)
            val request = registrationRequestFromKeyVault(registrationInfo, keyVault)
            registrationClient.register(request)
        } bind { result ->
            val uiResult = UIRegistrationResult(result.isSuccess, result.errorMessage, result.validationErrors)
            if (uiResult.successful) {
                updateProgress("Registration complete, writing info to disk...")
                accountInfoPersistenceManager.store(AccountInfo(info.name, info.email, info.phoneNumber)) map { uiResult }
            }
            else {
                updateProgress("An error occured during registration")
                Promise.ofSuccess(uiResult)
            }
        }
    }

    //TODO need a better reporting setup
    private fun updateProgress(status: String) {
        synchronized(this) {
            for (listener in listeners)
                listener(status)
        }
    }

    override fun addListener(listener: (String) -> Unit) {
        synchronized(this) {
            listeners.add(listener)
        }
    }
}
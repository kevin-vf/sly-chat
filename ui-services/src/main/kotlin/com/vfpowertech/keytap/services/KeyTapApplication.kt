package com.vfpowertech.keytap.services

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.vfpowertech.keytap.core.crypto.KeyVault
import com.vfpowertech.keytap.core.http.api.contacts.ContactAsyncClient
import com.vfpowertech.keytap.core.http.api.contacts.FindLocalContactsRequest
import com.vfpowertech.keytap.core.persistence.AccountInfo
import com.vfpowertech.keytap.core.persistence.ContactInfo
import com.vfpowertech.keytap.core.persistence.SessionData
import com.vfpowertech.keytap.core.relay.*
import com.vfpowertech.keytap.services.di.*
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.ui.successUi
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.BehaviorSubject

class KeyTapApplication {
    private val log = LoggerFactory.getLogger(javaClass)

    private var isNetworkAvailable = false

    private lateinit var reconnectionTimer: ExponentialBackoffTimer

    lateinit var appComponent: ApplicationComponent
        private set

    var userComponent: UserComponent? = null
        private set

    //the following observables never complete or error and are valid for the lifetime of the application
    //only changes in value are emitted from these
    private val networkAvailableSubject = BehaviorSubject.create(false)
    val networkAvailable: Observable<Boolean> = networkAvailableSubject

    private val relayAvailableSubject = BehaviorSubject.create(false)
    val relayAvailable: Observable<Boolean> = relayAvailableSubject

    private val userSessionAvailableSubject = BehaviorSubject.create(false)
    val userSessionAvailable: Observable<Boolean> = userSessionAvailableSubject

    fun init(platformModule: PlatformModule) {
        appComponent = DaggerApplicationComponent.builder()
            .platformModule(platformModule)
            .applicationModule(ApplicationModule(this))
            .build()

        initializeApplicationServices()
    }

    private fun initializeApplicationServices() {
        reconnectionTimer = ExponentialBackoffTimer(appComponent.rxScheduler)
    }

    fun updateNetworkStatus(isAvailable: Boolean) {
        //ignore dup updates
        if (isAvailable == isNetworkAvailable)
            return

        isNetworkAvailable = isAvailable
        log.info("Network is available: {}", isAvailable)

        networkAvailableSubject.onNext(isAvailable)

        //do nothing if we're not logged in
        val userComponent = this.userComponent ?: return

        connectToRelay(userComponent)
    }

    fun createUserSession(userLoginData: UserLoginData, accountInfo: AccountInfo): UserComponent {
        if (userComponent != null)
            error("UserComponent already loaded")

        log.info("Creating user session")

        val userComponent = appComponent.plus(UserModule(userLoginData, accountInfo))
        this.userComponent = userComponent

        //doing disk io here is bad, but...
        createUserPaths(userComponent.userPaths)

        initializeUserSession(userComponent)

        userSessionAvailableSubject.onNext(true)

        syncLocalContacts(userComponent)

        return userComponent
    }

    /** Attempts to find any registered users matching the user's local contacts. */
    private fun syncLocalContacts(userComponent: UserComponent) {
        val authToken = userComponent.userLoginData.authToken
        if (authToken == null) {
            log.debug("authToken is null, aborting local contacts sync")
            return
        }

        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        val phoneNumber = phoneNumberUtil.parse("+${userComponent.accountInfo.phoneNumber}", null)
        val defaultRegion = phoneNumberUtil.getRegionCodeForCountryCode(phoneNumber.countryCode)

        appComponent.platformContacts.fetchContacts() map { contacts ->
            val phoneNumberUtil = PhoneNumberUtil.getInstance()

            contacts.map { contact ->
                val phoneNumbers = contact.phoneNumbers.map { parsePhoneNumber(it, defaultRegion) }.filter { it != null }.map { phoneNumberUtil.format(it, PhoneNumberFormat.E164) }
                contact.copy(phoneNumbers = phoneNumbers)
            }
        } bind { contacts ->
            userComponent.contactsPersistenceManager.findMissing(contacts)
        } bind { missingContacts ->
            log.debug("Missing local contacts:", missingContacts)
            val client = ContactAsyncClient(appComponent.serverUrls.API_SERVER)
            client.findLocalContacts(FindLocalContactsRequest(authToken, missingContacts))
        } bind { foundContacts ->
            log.debug("Found local contacts: {}", foundContacts)
            userComponent.contactsPersistenceManager.addAll(foundContacts.contacts.map { ContactInfo(it.username, it.name, it.phoneNumber, it.publicKey) })
        } fail { e ->
            log.error("Local contacts sync failed: {}", e.message, e)
        }
    }

    private fun createUserPaths(userPaths: UserPaths) {
        userPaths.accountDir.mkdirs()
    }

    private fun initializeUserSession(userComponent: UserComponent) {
        userComponent.relayClientManager.onlineStatus.subscribe {
            onRelayStatusChange(it)
        }

        userComponent.relayClientManager.events.subscribe { handleRelayClientEvent(it) }

        if (!isNetworkAvailable) {
            log.info("Network unavailable, not connecting to relay")
            return
        }

        connectToRelay(userComponent)
    }

    private fun handleRelayClientEvent(event: RelayClientEvent) {
        when (event) {
            is ConnectionEstablished -> reconnectionTimer.reset()
            is ConnectionLost -> if (!event.wasRequested) reconnectToRelay()
            is ConnectionFailure -> {
                log.warn("Connection to relay failed: {}", event.error.message)
                reconnectToRelay()
            }

            is AuthenticationSuccessful -> {

            }

            is AuthenticationExpired -> {
                log.info("Auth token expired, refreshing")
                refreshAuthToken()
            }

            is AuthenticationFailure -> {
                //first we try and refresh; if that fails we need to prompt the user for a password
                refreshAuthToken()

                //TODO prompt user for password; this can occur if a user changes his password
                //on a diff device while they're online on another device
            }
        }
    }

    private fun refreshAuthToken() {
        val userComponent = userComponent ?: error("No user session")
        val data = userComponent.userLoginData
        data.authToken = null
        val remotePasswordHash = data.keyVault.remotePasswordHash

        val sessionDataPersistenceManager = userComponent.sessionDataPersistenceManager

        appComponent.authenticationService.refreshAuthToken(data.username, remotePasswordHash) bind { response ->
            log.info("Got new auth token")
            sessionDataPersistenceManager.store(SessionData(response.authToken)) map { response }
        } successUi { response ->
            data.authToken = response.authToken

            //TODO key regen
            reconnectToRelay()
        } fail { e ->
            log.error("Unable to refresh auth token", e)
        }
    }

    private fun reconnectToRelay() {
        if (!isNetworkAvailable)
            return

        reconnectionTimer.next().subscribe {
            val userComponent = this.userComponent
            if (userComponent != null) {
                connectToRelay(userComponent)
            }
            else
                log.warn("No longer logged in, aborting reconnect")
        }

        log.info("Attempting to reconnect to relay in {}s", reconnectionTimer.waitTimeSeconds)
    }

    private fun onRelayStatusChange(newStatus: Boolean) {
        relayAvailableSubject.onNext(newStatus)
    }

    /** Fetches auth token if none is given, then connects to the relay. */
    private fun connectToRelay(userComponent: UserComponent) {
        if (!isNetworkAvailable)
            return

        val userLoginData = userComponent.userLoginData
        if (userLoginData.authToken == null) {
            log.info("No auth token, fetching new")
            refreshAuthToken()
        }
        else
            doRelayLogin(null)
    }

    /**
     * Actually log into the relay server.
     *
     * If an authToken is given, it's used to overwrite the currently set auth token.
     */
    private fun doRelayLogin(authToken: String?) {
        val userComponent = this.userComponent
        if (userComponent == null) {
            log.warn("User session has already been terminated")
            return
        }

        if (authToken != null)
            userComponent.userLoginData.authToken = authToken

        userComponent.relayClientManager.connect()
    }

    private fun deinitializeUserSession(userComponent: UserComponent) {
        userComponent.sqlitePersistenceManager.shutdown()
        userComponent.relayClientManager.disconnect()
    }

    fun destroyUserSession() {
        val userComponent = this.userComponent ?: return

        log.info("Destroying user session")

        //notify listeners before tearing down session
        userSessionAvailableSubject.onNext(false)

        //TODO shutdown stuff; probably should return a promise
        deinitializeUserSession(userComponent)

        this.userComponent = null
    }

    fun shutdown() {
        destroyUserSession()
    }

    fun storeAccountData(keyVault: KeyVault, accountInfo: AccountInfo): Promise<Unit, Exception> {
        val userComponent = this.userComponent ?: error("No user session")

        userComponent.accountInfoPersistenceManager.store(accountInfo) fail { e ->
            log.error("Unable to store account info: {}", e.message, e)
        }

        return userComponent.keyVaultPersistenceManager.store(keyVault) fail { e ->
            log.error("Unable to store keyvault: {}", e.message, e)
        }
    }
}

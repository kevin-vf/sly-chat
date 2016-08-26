package io.slychat.messenger.services

import com.nhaarman.mockito_kotlin.*
import io.slychat.messenger.core.http.api.authentication.DeviceInfo
import io.slychat.messenger.core.persistence.InstallationData
import io.slychat.messenger.core.persistence.StartupInfoPersistenceManager
import io.slychat.messenger.core.randomAccountInfo
import io.slychat.messenger.core.randomDeviceId
import io.slychat.messenger.core.randomRegistrationId
import io.slychat.messenger.core.relay.RelayClientEvent
import io.slychat.messenger.testutils.KovenantTestModeRule
import io.slychat.messenger.testutils.thenResolve
import io.slychat.messenger.testutils.thenReject
import org.junit.Before
import org.junit.ClassRule
import org.junit.Ignore
import org.junit.Test
import rx.observers.TestSubscriber
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

class SlyApplicationTest {
    companion object {
        @ClassRule
        @JvmField
        val kovenantRule = KovenantTestModeRule()
    }

    val accountInfo = randomAccountInfo()

    val appComponent = MockApplicationComponent()

    val platformContactsUpdated: PublishSubject<Unit> = PublishSubject.create()
    val relayOnlineStatus: BehaviorSubject<Boolean> = BehaviorSubject.create(false)
    val relayEvents: PublishSubject<RelayClientEvent> = PublishSubject.create()

    val startupInfoPersistenceManager: StartupInfoPersistenceManager = mock()

    @Before
    fun before() {
        whenever(appComponent.installationDataPersistenceManager.retrieve()).thenResolve(InstallationData.generate())

        whenever(appComponent.platformContacts.contactsUpdated).thenReturn(platformContactsUpdated)

        whenever(appComponent.localAccountDirectory.getStartupInfoPersistenceManager()).thenReturn(startupInfoPersistenceManager)

        val userComponent = appComponent.userComponent

        //used in backgroundInitialization
        whenever(userComponent.sessionDataPersistenceManager.store(any())).thenResolve(Unit)
        whenever(startupInfoPersistenceManager.store(any())).thenResolve(Unit)
        whenever(userComponent.persistenceManager.initAsync()).thenResolve(Unit)
        whenever(userComponent.accountInfoManager.update(any())).thenResolve(Unit)
        whenever(userComponent.keyVaultPersistenceManager.store(any())).thenResolve(Unit)
        whenever(userComponent.relayClientManager.onlineStatus).thenReturn(relayOnlineStatus)
        whenever(userComponent.relayClientManager.events).thenReturn(relayEvents)
        whenever(userComponent.messageCipherService.updateSelfDevices(any())).thenResolve(Unit)
        whenever(userComponent.contactsService.addSelf(any())).thenResolve(Unit)
        whenever(userComponent.messengerService.broadcastNewDevice(any())).thenResolve(Unit)

        //used in finalizeInitialization
    }

    fun createApp(): SlyApplication {
        val app = SlyApplication()

        return app
    }

    fun assertSuccessfulLogin(testSubscriber: TestSubscriber<LoginEvent>) {
        var successSeen = false

        testSubscriber.onNextEvents.forEach {
            when (it) {
                is LoginEvent.LoginFailed -> throw AssertionError("Login failure: ${it.errorMessage}", it.exception)
                is LoginEvent.LoggedIn -> successSeen = true
            }
        }

        if (!successSeen)
            throw AssertionError("No LoggedIn event found, and no LoginFailed event found")
    }

    fun doLogin(app: SlyApplication, rememberMe: Boolean = false) {
        val loginEventSubscriber = TestSubscriber<LoginEvent>()

        app.loginEvents.subscribe(loginEventSubscriber)
        try {

            app.login("email", "password", rememberMe)
            assertSuccessfulLogin(loginEventSubscriber)
        }
        finally {
            loginEventSubscriber.unsubscribe()
        }
    }

    @Ignore
    @Test
    fun `it should create new installation data during initialization if no data exists`() { TODO() }

    @Ignore
    @Test
    fun `it should create new installation data during initialization if data is corrupted`() { TODO() }

    @Ignore
    @Test
    fun `it should use existing installation data during initialization if data is present`() { TODO() }

    @Ignore
    @Test
    fun `it should attempt to login automatically after basic initialization`() { TODO() }

    fun authWithOtherDevices(otherDevices: List<DeviceInfo>?): SlyApplication {
        val authResult = AuthResult(null, MockUserComponent.keyVault, accountInfo, otherDevices)

        whenever(appComponent.authenticationService.auth(any(), any(), any())).thenResolve(authResult)
        val app = createApp()

        app.init(appComponent)

        doLogin(app)

        return app
    }

    @Test
    fun `MessageCipherService should be initialized before use`() {
        val otherDevices = listOf(DeviceInfo(randomDeviceId(), randomRegistrationId()))
        authWithOtherDevices(otherDevices)

        val messageCipherService = appComponent.userComponent.messageCipherService
        val order = inOrder(messageCipherService)

        order.verify(messageCipherService).init()
        order.verify(messageCipherService).updateSelfDevices(any())
    }

    @Test
    fun `our own account must be added to the address book before MessageCipherService is called`() {
        val otherDevices = listOf(DeviceInfo(randomDeviceId(), randomRegistrationId()))
        authWithOtherDevices(otherDevices)

        val messageCipherService = appComponent.userComponent.messageCipherService
        val contactsService = appComponent.userComponent.contactsService

        val order = inOrder(contactsService, messageCipherService)

        order.verify(contactsService).addSelf(any())
        order.verify(messageCipherService).updateSelfDevices(any())
    }

    @Test
    fun `it should not add our own account after initial initialization`() {
        authWithOtherDevices(null)

        val contactsService = appComponent.userComponent.contactsService

        verify(contactsService, never()).addSelf(any())
    }

    @Test
    fun `it should update the self devices list during user initialization`() {
        val otherDevices = listOf(DeviceInfo(randomDeviceId(), randomRegistrationId()))
        authWithOtherDevices(otherDevices)
        verify(appComponent.userComponent.messageCipherService).updateSelfDevices(otherDevices)
    }

    @Test
    fun `it should send other devices a new device message during first initialization`() {
        val otherDevices = listOf(DeviceInfo(randomDeviceId(), randomRegistrationId()))
        val app = authWithOtherDevices(otherDevices)

        val deviceInfo = DeviceInfo(accountInfo.deviceId, app.installationData.registrationId)

        verify(appComponent.userComponent.messengerService).broadcastNewDevice(deviceInfo)
    }
}
package io.slychat.messenger.services

import io.slychat.messenger.core.persistence.ContactInfo

interface ContactEvent {
    class Added(val contacts: Set<ContactInfo>) : ContactEvent
    //if was pending, delete stuff (happens once a user rejects adding
    class Removed(val contacts: Set<ContactInfo>) : ContactEvent
    class Updated(val contacts: Set<ContactInfo>) : ContactEvent
    class Request(val contacts: Set<ContactInfo>) : ContactEvent
    class Sync(val isRunning: Boolean) : ContactEvent
}

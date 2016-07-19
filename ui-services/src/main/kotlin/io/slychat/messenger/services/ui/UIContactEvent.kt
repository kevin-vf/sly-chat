package io.slychat.messenger.services.ui

enum class UIContactEventType {
    ADD,
    REMOVE,
    UPDATE,
    REQUEST,
    SYNC
}

interface UIContactEvent {
    val type: UIContactEventType

    class Added(val contacts: List<UIContactDetails>) : UIContactEvent {
        override val type = UIContactEventType.ADD
    }

    class Removed(val contacts: List<UIContactDetails>) : UIContactEvent {
        override val type: UIContactEventType = UIContactEventType.REMOVE
    }

    class Updated(val contacts: List<UIContactDetails>) : UIContactEvent {
        override val type: UIContactEventType = UIContactEventType.UPDATE
    }

    class Sync(val isRunning: Boolean) : UIContactEvent {
        override val type = UIContactEventType.SYNC
    }
}

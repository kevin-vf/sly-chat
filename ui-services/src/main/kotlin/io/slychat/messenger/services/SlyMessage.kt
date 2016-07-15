package io.slychat.messenger.services

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.persistence.GroupId

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
@JsonSubTypes(
    JsonSubTypes.Type(GroupEventMessageWrapper::class, name = "g"),
    JsonSubTypes.Type(TextMessageWrapper::class, name = "t")
)
interface SlyMessage

data class GroupEventMessageWrapper(@JsonProperty("m") val m: GroupEventMessage) : SlyMessage
data class TextMessageWrapper(@JsonProperty("m") val m: TextMessage) : SlyMessage

data class TextMessage(
    @JsonProperty("timestamp")
    val timestamp: Long,
    @JsonProperty("message")
    val message: String,
    @JsonProperty("groupId")
    val groupId: GroupId?
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
@JsonSubTypes(
    JsonSubTypes.Type(GroupEventMessage.Join::class, name = "j"),
    JsonSubTypes.Type(GroupEventMessage.Part::class, name = "p"),
    JsonSubTypes.Type(GroupEventMessage.Invitation::class, name = "i")
)
sealed class GroupEventMessage {
    abstract val id: GroupId

    /** A user has joined the group. Sent from the user sending the GroupInvitation. Must be sent from a current member of the group. */
    class Join(
        override val id: GroupId,
        val joined: UserId
    ) : GroupEventMessage() {
        override fun toString(): String{
            return "Join(id=$id, joined=$joined)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Join

            if (id != other.id) return false
            if (joined != other.joined) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + joined.hashCode()
            return result
        }
    }

    /** Sender has left the group. */
    class Part(
        override val id: GroupId
    ) : GroupEventMessage() {
        override fun toString(): String {
            return "Part(id=$id)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Part

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }
    }

    /** Invitation to a new group. */
    class Invitation(
        override val id: GroupId,
        val name: String,
        val members: Set<UserId>
    ) : GroupEventMessage() {
        override fun toString(): String {
            return "Invitation(id=$id, name='$name', members=$members)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as Invitation

            if (id != other.id) return false
            if (name != other.name) return false
            if (members != other.members) return false

            return true
        }

        override fun hashCode(): Int{
            var result = id.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + members.hashCode()
            return result
        }
    }
}

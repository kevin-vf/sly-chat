package io.slychat.messenger.services.ui

import com.fasterxml.jackson.annotation.JsonProperty
import io.slychat.messenger.core.UserId

/**
 * Contact book details.
 */
data class UIContactDetails(
    @JsonProperty("id") val id: UserId,
    @JsonProperty("name") val name: String,
    @JsonProperty("phoneNumber") val phoneNumber: String?,
    @JsonProperty("email") val email: String,
    @JsonProperty("publicKey") val publicKey: String
)
package com.vfpowertech.keytap.services.ui

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Contact book details.
 */
data class UIContactDetails(
    @JsonProperty("name") val name: String,
    @JsonProperty("phoneNumber") val phoneNumber: String?,
    @JsonProperty("email") val email: String,
    @JsonProperty("publicKey") val publicKey: String
)
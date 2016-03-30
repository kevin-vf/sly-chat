package com.vfpowertech.keytap.core.http.api.accountUpdate

import com.fasterxml.jackson.annotation.JsonProperty

data class UpdatePhoneRequest(
    @param:JsonProperty("username")
    @get:JsonProperty("username")
    val username: String,

    @param:JsonProperty("hash")
    @get:JsonProperty("hash")
    val hash: String,

    @param:JsonProperty("phone-number")
    @get:JsonProperty("phone-number")
    val phoneNumber: String
)
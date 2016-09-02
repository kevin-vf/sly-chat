package io.slychat.messenger.core.http.api.contacts

import io.slychat.messenger.core.UserCredentials
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.http.HttpClient
import io.slychat.messenger.core.http.api.ApiResult
import io.slychat.messenger.core.http.api.apiGetRequest
import io.slychat.messenger.core.http.api.apiPostRequest
import io.slychat.messenger.core.typeRef

class ContactClient(private val serverBaseUrl: String, private val httpClient: HttpClient) {
    fun fetchContactInfo(userCredentials: UserCredentials, request: NewContactRequest): FetchContactResponse {
        val url = "$serverBaseUrl/v1/contact/new/info"

        return apiPostRequest(httpClient, url, userCredentials, request, typeRef())
    }

    fun fetchMultiContactInfoById(userCredentials: UserCredentials, request: FetchMultiContactInfoByIdRequest): FetchMultiContactInfoByIdResponse {
        val url = "$serverBaseUrl/v1/contact/find"

        return apiPostRequest(httpClient, url, userCredentials, request, typeRef())
    }

    fun fetchContactInfoById(userCredentials: UserCredentials, userId: UserId): FetchContactInfoByIdResponse {
        val url = "$serverBaseUrl/v1/contact/find/$userId"

        return apiGetRequest(httpClient, url, userCredentials, emptyList(), typeRef<ApiResult<FetchContactInfoByIdResponse>>())
    }

    fun findLocalContacts(userCredentials: UserCredentials, request: FindLocalContactsRequest): FindLocalContactsResponse {
        val url = "$serverBaseUrl/v1/contact/find-local"

        return apiPostRequest(httpClient, url, userCredentials, request, typeRef())
    }
}


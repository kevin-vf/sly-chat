package com.vfpowertech.keytap.services.ui

/**
 * @property online Whether or not a network connection is available.
 * @property isMobile Whether the connected network is a mobile network or not.
 */
data class UINetworkStatus(
    val online: Boolean,
    val isMobile: Boolean
)
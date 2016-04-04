package com.vfpowertech.keytap.core.crypto.axolotl

import com.vfpowertech.keytap.core.crypto.nextPreKeyId
import org.whispersystems.libaxolotl.state.PreKeyRecord
import org.whispersystems.libaxolotl.state.SignedPreKeyRecord

/** A batch of generated prekeys */
data class GeneratedPreKeys(
    val signedPreKey: SignedPreKeyRecord,
    val oneTimePreKeys: List<PreKeyRecord>
) {
    /** Returns the next usable signed prekey ID. */
    fun nextSignedId(): Int = nextPreKeyId(signedPreKey.id)

    /** Returns the next usable unsigned prekey ID. */
    fun nextUnsignedId(): Int = nextPreKeyId(oneTimePreKeys.last().id)
}

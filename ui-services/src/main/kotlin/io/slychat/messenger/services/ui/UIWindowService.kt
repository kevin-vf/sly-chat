package io.slychat.messenger.services.ui

import com.vfpowertech.jsbridge.processor.annotations.JSToJavaGenerate

/** Provides functionality for manipulating the native UI window. */
@JSToJavaGenerate("WindowService")
interface UIWindowService {
    fun minimize()

    fun closeSoftKeyboard()
}
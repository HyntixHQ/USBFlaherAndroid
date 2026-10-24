package com.hyntix.android.usbflasher.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun everyCategoryMapsToActionableMessage() {
        for (category in ErrorMessages.Category.values()) {
            val userError = ErrorMessages.get(category)
            assertTrue(userError.titleRes != 0)
            assertTrue(userError.messageRes != 0)
        }
    }
}

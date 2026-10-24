package com.hyntix.android.usbflasher.util

import android.content.Context
import com.hyntix.android.usbflasher.R

/**
 * Centralized, user-friendly error message mapping.
 * Every user-facing error should go through this to ensure consistent,
 * actionable, and localized messaging.
 */
object ErrorMessages {

    // Error categories
    enum class Category {
        INVALID_FILE,    // Not a valid Linux/Windows ISO
        NO_SPACE,         // Insufficient storage on device/drive
        PERMISSION,       // USB permission denied
        DISCONNECTED,     // USB device disconnected mid-operation
        CHECKSUM_MISMATCH, // Download checksum doesn't match
        DESTINATION_LOST,  // Download destination URI expired/unavailable
        DESTINATION_CHANGED, // File size changed unexpectedly
        NETWORK,           // Network error during download
        NO_NETWORK,        // No network available
        METERED,           // Metered network requires explicit opt-in
        ALREADY_ACTIVE,    // Another download already active
        VERIFICATION_FAILED, // USB verification failed
        VERIFICATION_MISMATCH, // USB contents don't match expected ISO
        UNKNOWN
    }

    data class UserError(
        val category: Category,
        val titleRes: Int,
        val messageRes: Int,
        val actionRes: Int? = null
    )

    private val mapping = mapOf(
        Category.INVALID_FILE to UserError(
            Category.INVALID_FILE,
            R.string.error_title_invalid_file,
            R.string.error_msg_invalid_file,
            R.string.error_action_choose_isa
        ),
        Category.NO_SPACE to UserError(
            Category.NO_SPACE,
            R.string.error_title_no_space,
            R.string.error_msg_no_space,
            null
        ),
        Category.PERMISSION to UserError(
            Category.PERMISSION,
            R.string.error_title_permission,
            R.string.error_msg_permission,
            R.string.error_action_grant_permission
        ),
        Category.DISCONNECTED to UserError(
            Category.DISCONNECTED,
            R.string.error_title_disconnected,
            R.string.error_msg_disconnected,
            R.string.error_action_reconnect
        ),
        Category.CHECKSUM_MISMATCH to UserError(
            Category.CHECKSUM_MISMATCH,
            R.string.error_title_checksum,
            R.string.error_msg_checksum,
            R.string.error_action_redownload
        ),
        Category.DESTINATION_LOST to UserError(
            Category.DESTINATION_LOST,
            R.string.error_title_destination_lost,
            R.string.error_msg_destination_lost,
            R.string.error_action_reselect_destination
        ),
        Category.DESTINATION_CHANGED to UserError(
            Category.DESTINATION_CHANGED,
            R.string.error_title_destination_changed,
            R.string.error_msg_destination_changed,
            R.string.error_action_redownload
        ),
        Category.NETWORK to UserError(
            Category.NETWORK,
            R.string.error_title_network,
            R.string.error_msg_network,
            R.string.error_action_retry
        ),
        Category.NO_NETWORK to UserError(
            Category.NO_NETWORK,
            R.string.error_title_no_network,
            R.string.error_msg_no_network,
            null
        ),
        Category.METERED to UserError(
            Category.METERED,
            R.string.error_title_metered,
            R.string.error_msg_metered,
            R.string.error_action_allow_metered
        ),
        Category.ALREADY_ACTIVE to UserError(
            Category.ALREADY_ACTIVE,
            R.string.error_title_already_active,
            R.string.error_msg_already_active,
            R.string.error_action_wait
        ),
        Category.VERIFICATION_FAILED to UserError(
            Category.VERIFICATION_FAILED,
            R.string.error_title_verify_failed,
            R.string.error_msg_verify_failed,
            R.string.error_action_retry
        ),
        Category.VERIFICATION_MISMATCH to UserError(
            Category.VERIFICATION_MISMATCH,
            R.string.error_title_verify_mismatch,
            R.string.error_msg_verify_mismatch,
            R.string.error_action_reflash
        )
    )

    fun get(category: Category): UserError {
        return mapping[category] ?: UserError(
            Category.UNKNOWN,
            R.string.error_title_unknown,
            R.string.error_msg_unknown,
            R.string.error_action_retry
        )
    }

    fun getForException(exception: Exception): Category {
        return when (exception.javaClass.simpleName) {
            "IOException" -> Category.NETWORK
            "UnknownHostException" -> Category.NETWORK
            "SecurityException" -> Category.PERMISSION
            else -> Category.UNKNOWN
        }
    }
}

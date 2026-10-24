package com.hyntix.android.usbflasher.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UsbVerificationState {
    data object Idle : UsbVerificationState()
    data class Running(val bytesRead: Long, val totalBytes: Long) : UsbVerificationState()
    data class Finished(val actualSha256: String, val expectedSha256: String?) : UsbVerificationState()
    data class Failed(val message: String) : UsbVerificationState()
    data object Cancelled : UsbVerificationState()
}

sealed class UsbBackupState {
    data object Idle : UsbBackupState()
    data class Running(val bytesRead: Long, val totalBytes: Long) : UsbBackupState()
    data class Finished(val writtenBytes: Long, val sha256: String) : UsbBackupState()
    data class Failed(val message: String) : UsbBackupState()
    data object Cancelled : UsbBackupState()
}

object UsbOperationBus {
    private val _verify = MutableStateFlow<UsbVerificationState>(UsbVerificationState.Idle)
    private val _backup = MutableStateFlow<UsbBackupState>(UsbBackupState.Idle)

    val verification: StateFlow<UsbVerificationState> = _verify.asStateFlow()
    val backup: StateFlow<UsbBackupState> = _backup.asStateFlow()

    fun setVerify(state: UsbVerificationState) { _verify.value = state }
    fun setBackup(state: UsbBackupState) { _backup.value = state }
}

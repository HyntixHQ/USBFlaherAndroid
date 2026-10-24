package com.hyntix.android.usbflasher.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbOperationBusTest {

    @Test
    fun verificationUpdatesStateInOrder() {
        UsbOperationBus.setVerify(UsbVerificationState.Idle)
        assertEquals(UsbVerificationState.Idle, UsbOperationBus.verification.value)

        UsbOperationBus.setVerify(UsbVerificationState.Running(0L, 1L))
        val running = UsbOperationBus.verification.value
        assertTrue(running is UsbVerificationState.Running)

        UsbOperationBus.setVerify(
            UsbVerificationState.Finished(
                actualSha256 = "abc",
                expectedSha256 = "abc"
            )
        )
        val finished = UsbOperationBus.verification.value as UsbVerificationState.Finished
        assertEquals("abc", finished.actualSha256)
        assertEquals("abc", finished.expectedSha256)
    }

    @Test
    fun backupUpdatesStateInOrder() {
        UsbOperationBus.setBackup(UsbBackupState.Idle)
        assertEquals(UsbBackupState.Idle, UsbOperationBus.backup.value)

        UsbOperationBus.setBackup(UsbBackupState.Running(0L, 2L))
        assertTrue(UsbOperationBus.backup.value is UsbBackupState.Running)

        UsbOperationBus.setBackup(
            UsbBackupState.Finished(writtenBytes = 2L, sha256 = "ba")
        )
        val finished = UsbOperationBus.backup.value as UsbBackupState.Finished
        assertEquals(2L, finished.writtenBytes)
        assertEquals("ba", finished.sha256)
    }

    @Test
    fun verificationCanReportFailureAndCancel() {
        UsbOperationBus.setVerify(UsbVerificationState.Cancelled)
        assertEquals(UsbVerificationState.Cancelled, UsbOperationBus.verification.value)

        UsbOperationBus.setVerify(UsbVerificationState.Failed("boom"))
        val failed = UsbOperationBus.verification.value as UsbVerificationState.Failed
        assertEquals("boom", failed.message)
    }
}

/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import com.kynetics.uf.android.update.PackageInstallerBRHandler
import org.eclipse.hara.ddiclient.core.api.Updater
import java.util.concurrent.CountDownLatch

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class PackageInstallerBroadcastReceiver internal constructor(
    private val sessionId: Int,
    private val countDownLatch: CountDownLatch,
    private val artifact: Updater.SwModuleWithPath.Artifact,
    private val currentUpdateState: CurrentUpdateState,
    private val messenger: Updater.Messenger,
    private val packageName: String,
    private val packageVersion: Long?
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (PackageInstallerBRHandler.ACTION_INSTALL_COMPLETE != intent.action) {
            return
        }

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION_ID_NOT_FOUND)
        if (sessionId != this.sessionId) {
            return
        }

        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, SESSION_ID_NOT_FOUND)

        val currentPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)

        when (result) {
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED, PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val errorMessage = listOf("Installation of ${artifact.filename} ($packageName) fails with error code $result", errorCodeToDescription.getValue(result)).toTypedArray()
                @Suppress("SpreadOperator")
                currentUpdateState.addErrorToRepor(*errorMessage)
                @Suppress("SpreadOperator")
                messenger.sendMessageToServer(*errorMessage)
                currentUpdateState.packageInstallationTerminated(packageName, packageVersion)
                countDownLatch.countDown()
                context.unregisterReceiver(this)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val message = String.format("%s (%s) installed", artifact.filename, packageName)
                currentUpdateState.addSuccessMessageToRepor(message)
                messenger.sendMessageToServer(message)
                currentUpdateState.packageInstallationTerminated(packageName, packageVersion)
                countDownLatch.countDown()
                context.unregisterReceiver(this)
            }
            else -> Log.w(TAG, String.format("Status (%s) of package installation (%s) not handle",
                    result,
                    packageName))
        }

        Log.d(TAG, String.format("Result code of %s installation: %s", packageName, result))
    }

    companion object {
        private val TAG = PackageInstallerBroadcastReceiver::class.java.simpleName

        private val SESSION_ID_NOT_FOUND = -1

        private val errorCodeToDescription = mapOf(
                PackageInstaller.STATUS_FAILURE to """
                    The operation failed in a generic way. The system will always try to
                    provide a more specific failure reason, but in some rare cases this may be delivered.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_ABORTED to """
                    The operation failed because it was actively aborted. For example, the
                    user actively declined requested permissions, or the session was abandoned.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_BLOCKED to """
                    The operation failed because it was blocked. For example, a device policy
                    may be blocking the operation, a package verifier may have blocked the
                    operation, or the app may be required for core system operation.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_CONFLICT to """
                    The operation failed because it conflicts (or is inconsistent with) with
                    another package already installed on the device. For example, an existing
                    permission, incompatible certificates, etc. The user may be able to
                    uninstall another app to fix the issue.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to """
                    The operation failed because it is fundamentally incompatible with this
                    device. For example, the app may require a hardware feature that doesn't
                    exist, it may be missing native code for the ABIs supported by the
                    device, or it requires a newer SDK version, etc.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_INVALID to """
                    The operation failed because one or more of the APKs was invalid. For
                    example, they might be malformed, corrupt, incorrectly signed, mismatched, etc.
                """.trimIndent(),
                PackageInstaller.STATUS_FAILURE_STORAGE to """
                    The operation failed because of storage issues. For example, the device
                    may be running low on space, or external media may be unavailable. The
                    user may be able to help free space or insert different external media.
                """.trimIndent()
        )
    }
}

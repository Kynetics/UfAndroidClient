/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.update.application

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.Installer
import com.kynetics.uf.android.update.InstallerSession
import com.kynetics.uf.android.update.UpdateConfirmationTimeoutProvider
import com.kynetics.uf.android.update.application.ApkAnalyzer.getPackageFromApk
import com.kynetics.uf.android.update.application.ApkAnalyzer.getVersionFromApk
import com.kynetics.uf.android.update.application.ApkAnalyzer.verifySharedUserId
import org.eclipse.hara.ddiclient.core.api.Updater
import java.io.File
import java.util.concurrent.CountDownLatch

object ApkInstaller : Installer<Unit> {

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun install(
        artifact: Updater.SwModuleWithPath.Artifact,
        currentUpdateState: CurrentUpdateState,
        messenger: Updater.Messenger,
        context: Context
    ) {

        val apk = File(artifact.path)

        when {
            !apk.exists() -> {
                val errorMessage = "Apk not found"
                Log.w(ApkUpdater.TAG, errorMessage)
                currentUpdateState.addErrorToRepor(errorMessage)
            }

            !verifySharedUserId(context, apk.absolutePath) -> {
                val errorMessage = "Application was already installed with a different sharedUserId"
                Log.w(ApkUpdater.TAG, errorMessage)
                currentUpdateState.addErrorToRepor(errorMessage)
            }

            else -> {
                val countDownLatch = CountDownLatch(1)
                val packageName = getPackageFromApk(context, apk.absolutePath)
                val packageVersion = getVersionFromApk(context, apk.absolutePath)
                val installerSession = InstallerSession.newInstance(
                    context,
                    countDownLatch,
                    packageName,
                    packageVersion,
                    artifact,
                    messenger,
                    currentUpdateState
                )

                installerSession.writeSession(apk, packageName)
                installerSession.commitSession()

                val timeout = UpdateConfirmationTimeoutProvider.FixedTimeProvider.ofSeconds(
                    ApkUpdater.TIMEOUT_LIMIT
                ).getTimeout(null)

                if (!countDownLatch.await(timeout.value, timeout.timeUnit)) {
                    currentUpdateState.addSuccessMessageToRepor("Time to update exceeds the timeout",
                        "Package manager timeout expired, package installation status unknown")
                    Log.w(ApkUpdater.TAG, "Time to update exceeds the timeout")
                }
            }
        }
    }
}

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
import android.util.Log
import com.kynetics.uf.android.update.AndroidUpdater
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.application.ApkAnalyzer.getPackageFromApk
import com.kynetics.uf.android.update.application.ApkAnalyzer.getVersionFromApk
import org.eclipse.hara.ddiclient.core.api.Updater

class ApkUpdater(context: Context) : AndroidUpdater(context) {

    companion object {
        val TAG: String = ApkUpdater::class.java.simpleName
        const val TIMEOUT_LIMIT = 1800L
    }

    override fun requiredSoftwareModulesAndPriority(swModules: Set<Updater.SwModule>): Updater.SwModsApplication {

        return Updater.SwModsApplication(1,
            swModules
                .filter { it.type == "bApp" }
                .map { Updater.SwModsApplication.SwModule(
                    it.type,
                    it.name,
                    it.version,
                    it.artifacts
                        .filter { a -> a.filename.endsWith(".apk", true) }
                        .map { a -> a.hashes }
                        .toSet())
                }.toSet())
    }

    override fun applyUpdate(
        modules: Set<Updater.SwModuleWithPath>,
        messenger: Updater.Messenger
    ): Updater.UpdateResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            val errorMessage = "Installation of apk is not supported from device with android system " +
                "com.kynetics.uf.android.api lower than ${Build.VERSION_CODES.LOLLIPOP} " +
                "(current is ${Build.VERSION.SDK_INT})"
            messenger.sendMessageToServer(errorMessage)
            Log.w(TAG, errorMessage)
            return Updater.UpdateResult(
                success = false,
                details = listOf(errorMessage)
            )
        }

        val currentUpdateState = CurrentUpdateState(context)

        modules.flatMap { it.artifacts }
            .filter { !currentUpdateState.isPackageInstallationTerminated(
                getPackageFromApk(context, it.path), getVersionFromApk(context, it.path))
            }.forEach { a ->
                Log.d(TAG, "install artifact ${a.filename} from file ${a.path}")
                try {
                    ApkInstaller.install(a, currentUpdateState, messenger, context)
                } catch (t: Throwable) { // new client replace with IOException | IllegalArgumentException e
                    val error = "${a.filename} installation fails with error ${t.message}"
                    currentUpdateState.addErrorToRepor(error)
                    currentUpdateState.packageInstallationTerminated(
                        getPackageFromApk(context, a.path),
                        getVersionFromApk(context, a.path))
                    Log.d(TAG, "Failed to install ${a.filename}")
                    Log.d(TAG, t.message, t)
                }
            }
        val details = currentUpdateState.distributionReportError + currentUpdateState.distributionReportSuccess
        return Updater.UpdateResult(success = currentUpdateState.distributionReportError.isEmpty(),
            details = details.toList())
    }
}

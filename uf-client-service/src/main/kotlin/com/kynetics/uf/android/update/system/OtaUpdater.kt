/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.update.system

import android.content.Context
import android.util.Log
import com.kynetics.uf.android.update.AndroidUpdater
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.SystemUpdateType
import org.eclipse.hara.ddiclient.core.api.Updater

class OtaUpdater(context: Context) : AndroidUpdater(context) {

    companion object {
        val TAG: String = OtaUpdater::class.java.simpleName
    }

    private val otaInstaller = SystemUpdateType.getSystemUpdateType()
        .getInstaller(context)

    override fun requiredSoftwareModulesAndPriority(swModules: Set<Updater.SwModule>): Updater.SwModsApplication {
        return Updater.SwModsApplication(0,
            swModules
                .filter { it.type == "os" }
                .map { Updater.SwModsApplication.SwModule(
                    it.type,
                    it.name,
                    it.version,
                    it.artifacts.map { a -> a.hashes }.toSet()) }.toSet())
    }

    override fun applyUpdate(
        modules: Set<Updater.SwModuleWithPath>,
        messenger: Updater.Messenger
    ): Updater.UpdateResult {
        val currentUpdateState = CurrentUpdateState(context)
        val updateDetails = mutableListOf<String>()
        val success = modules.dropWhile {
            Log.d(TAG, "apply module ${it.name} ${it.version} of type ${it.type}")
            it.artifacts.dropWhile { a ->
                Log.d(TAG, "install artifact ${a.filename} from file ${a.path}")
                val installationResult = otaInstaller.install(a, currentUpdateState, messenger, context)
                updateDetails.addAll(installationResult.details)
                checkReliable(updateDetails, messenger, installationResult)
                installationResult is CurrentUpdateState.InstallationResult.Success
            }.isEmpty()
        }.isEmpty()
        return Updater.UpdateResult(success = success, details = updateDetails)
    }

    private fun checkReliable(
        updateDetails: MutableList<String>,
        messenger: Updater.Messenger,
        installationResult: CurrentUpdateState.InstallationResult
    ) {
        if (otaInstaller.isFeedbackReliable(context)) {
            updateDetails.add("Final feedback message is reliable")
            otaInstaller.onComplete(context, messenger, installationResult)
        } else {
            updateDetails.add("Can't read ${CurrentUpdateState.LAST_LOG_FILE_NAME}, " +
                "the final feedback message could be unreliable")
        }
    }
}

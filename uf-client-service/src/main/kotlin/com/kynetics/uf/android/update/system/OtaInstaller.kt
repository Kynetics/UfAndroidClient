package com.kynetics.uf.android.update.system

import android.content.Context
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.Installer
import com.kynetics.updatefactory.ddiclient.core.api.Updater

interface OtaInstaller : Installer<CurrentUpdateState.InstallationResult> {
    fun isFeedbackReliable(context: Context): Boolean = true
    fun onUpdateError(context:Context, messenger: Updater.Messenger):Unit = Unit
}
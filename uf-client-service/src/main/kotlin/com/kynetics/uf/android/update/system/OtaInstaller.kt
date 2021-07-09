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
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.Installer
import org.eclipse.hara.ddiclient.core.api.Updater

interface OtaInstaller : Installer<CurrentUpdateState.InstallationResult> {
    fun isFeedbackReliable(context: Context): Boolean = true
    fun onComplete(context: Context, messenger: Updater.Messenger, result: CurrentUpdateState.InstallationResult): Unit = Unit
}

/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.update

import android.content.Context
import org.eclipse.hara.ddiclient.core.api.Updater

abstract class AndroidUpdater(protected val context: Context) : Updater {
    protected val currentUpdateState: CurrentUpdateState = CurrentUpdateState(context)

    final override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger): Updater.UpdateResult {
        currentUpdateState.startUpdate()
        return applyUpdate(modules, messenger)
    }

    abstract fun applyUpdate(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger): Updater.UpdateResult

    override fun updateIsCancellable(): Boolean {
        return !currentUpdateState.isUpdateStart()
    }
}

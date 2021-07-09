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

interface Installer<out T> {
    fun install(
        artifact: Updater.SwModuleWithPath.Artifact,
        currentUpdateState: CurrentUpdateState,
        messenger: Updater.Messenger,
        context: Context
    ): T
}

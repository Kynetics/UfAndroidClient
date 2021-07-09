/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.client

import okhttp3.OkHttpClient
import org.eclipse.hara.ddiclient.core.api.*

class UpdateFactoryClientWrapper(var delegate: HaraClient? = null): HaraClient {
    override fun forcePing() {
        delegate?.forcePing()
    }

    override fun init(updateFactoryClientData: HaraClientData, directoryForArtifactsProvider: DirectoryForArtifactsProvider, configDataProvider: ConfigDataProvider, deploymentPermitProvider: DeploymentPermitProvider, messageListeners: List<MessageListener>, updaters: List<Updater>, httpBuilder: OkHttpClient.Builder) {
        delegate?.init(updateFactoryClientData, directoryForArtifactsProvider, configDataProvider, deploymentPermitProvider, messageListeners, updaters.toList())
    }

    override fun startAsync() {
        delegate?.startAsync()
    }

    override fun stop() {
        delegate?.stop()
    }
}
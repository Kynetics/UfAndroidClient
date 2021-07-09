/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.converter

import com.kynetics.uf.android.api.v1.UFServiceMessageV1
import org.eclipse.hara.ddiclient.core.api.MessageListener

fun MessageListener.Message.State.Downloading.Artifact.toUFArtifact(): UFServiceMessageV1.State.Downloading.Artifact {
    return UFServiceMessageV1.State.Downloading.Artifact(name, size, md5)
}

@Suppress("ComplexMethod", "MaxLineLength")
fun MessageListener.Message.toUFMessage(): UFServiceMessageV1 {
    return when (this) {
        is MessageListener.Message.State.Downloading -> UFServiceMessageV1.State.Downloading(artifacts.map { it.toUFArtifact() })
        is MessageListener.Message.State.Updating -> UFServiceMessageV1.State.Updating
        is MessageListener.Message.State.CancellingUpdate -> UFServiceMessageV1.State.CancellingUpdate
        is MessageListener.Message.State.WaitingDownloadAuthorization -> UFServiceMessageV1.State.WaitingDownloadAuthorization
        is MessageListener.Message.State.WaitingUpdateAuthorization -> UFServiceMessageV1.State.WaitingUpdateAuthorization
        is MessageListener.Message.State.Idle -> UFServiceMessageV1.State.Idle

        is MessageListener.Message.Event.Error -> UFServiceMessageV1.Event.Error(details)
        is MessageListener.Message.Event.Polling -> UFServiceMessageV1.Event.Polling
        is MessageListener.Message.Event.AllFilesDownloaded -> UFServiceMessageV1.Event.AllFilesDownloaded
        is MessageListener.Message.Event.StartDownloadFile -> UFServiceMessageV1.Event.StartDownloadFile(fileName)
        is MessageListener.Message.Event.DownloadProgress -> UFServiceMessageV1.Event.DownloadProgress(fileName, percentage)
        is MessageListener.Message.Event.FileDownloaded -> UFServiceMessageV1.Event.FileDownloaded(fileDownloaded)
        is MessageListener.Message.Event.UpdateFinished -> UFServiceMessageV1.Event.UpdateFinished(successApply, details)
        is MessageListener.Message.Event.UpdateAvailable -> UFServiceMessageV1.Event.UpdateAvailable(id)
    }
}

/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.communication

import com.kynetics.uf.android.api.ApiCommunicationVersion
import com.kynetics.uf.android.api.UFServiceMessage
import com.kynetics.uf.android.api.v1.UFServiceMessageV1
import com.kynetics.uf.android.converter.toUFMessage
import org.eclipse.hara.ddiclient.core.api.MessageListener
import java.io.Serializable

interface MessageHandler<out T : Serializable?> {

    enum class Action {
        FORCE_PING,
        AUTH_DOWNLOAD_DENIED,
        AUTH_DOWNLOAD_GRANTED,
        AUTH_UPDATE_DENIED,
        AUTH_UPDATE_GRANTED,
        UPDATE_FINISH
    }

    val apiCommunicationVersion: ApiCommunicationVersion
    val messageToSendOnSync: T
    val currentMessage: T

    fun hasMessage(): Boolean = messageToSendOnSync != null

    fun onAction(action: Action): MessageHandler<T> {
        return this
    }

    fun onMessage(msg: MessageListener.Message): MessageHandler<T> {
        return this
    }

    fun onAndroidMessage(msg: UFServiceMessageV1): MessageHandler<T> {
        return this
    }

    fun onConfigurationError(details: List<String>): MessageHandler<T>
}

data class V0(
    override val currentMessage: UFServiceMessage? = null,
    private val suspend: UFServiceMessage.Suspend = UFServiceMessage.Suspend.NONE
) : MessageHandler<UFServiceMessage?> {

    override val apiCommunicationVersion = ApiCommunicationVersion.V0_1
    override val messageToSendOnSync: UFServiceMessage? = currentMessage

    override fun onAction(action: MessageHandler.Action): MessageHandler<UFServiceMessage?> {
        return when (action) {
            MessageHandler.Action.AUTH_DOWNLOAD_DENIED -> copy(suspend = UFServiceMessage.Suspend.DOWNLOAD)
            MessageHandler.Action.AUTH_UPDATE_DENIED -> copy(suspend = UFServiceMessage.Suspend.UPDATE)
            else -> copy(suspend = UFServiceMessage.Suspend.NONE)
        }
    }

    override fun onMessage(msg: MessageListener.Message): MessageHandler<UFServiceMessage?> {
        val newSuspendValue = when(msg){
            is MessageListener.Message.State.WaitingDownloadAuthorization -> UFServiceMessage.Suspend.DOWNLOAD
            is MessageListener.Message.State.WaitingUpdateAuthorization -> UFServiceMessage.Suspend.UPDATE
            else -> suspend
        }
        return copy(currentMessage = UFServiceMessage("", "", msg.toString(), newSuspendValue))
    }

    override fun onAndroidMessage(msg: UFServiceMessageV1): MessageHandler<UFServiceMessage?> {
        val newSuspendValue = when(msg){
            is UFServiceMessageV1.State.WaitingDownloadAuthorization -> UFServiceMessage.Suspend.DOWNLOAD
            is UFServiceMessageV1.State.WaitingUpdateAuthorization -> UFServiceMessage.Suspend.UPDATE
            else -> suspend
        }
        return copy(currentMessage = UFServiceMessage("", "", msg.toString(), newSuspendValue))
    }

    override fun onConfigurationError(details: List<String>): MessageHandler<UFServiceMessage?> {
        val state = UFServiceMessageV1.State.ConfigurationError(details).toString()
        return copy(currentMessage = UFServiceMessage("", "", state, suspend))
    }
}

data class V1(
    override val messageToSendOnSync: String? = null,
    override val currentMessage: String? = null
) : MessageHandler<String?> {

    override val apiCommunicationVersion = ApiCommunicationVersion.V1

    override fun onMessage(msg: MessageListener.Message): MessageHandler<String?> {
        return onAndroidMessage(msg.toUFMessage())
    }

    override fun onConfigurationError(details: List<String>): MessageHandler<String?> {
        val state = UFServiceMessageV1.State.ConfigurationError(details).toJson()
        return copy(messageToSendOnSync = state, currentMessage = state)
    }

    override fun onAndroidMessage(msg: UFServiceMessageV1): MessageHandler<String?> {
        return when (msg) {
            is UFServiceMessageV1.Event -> {
                copy(currentMessage = msg.toJson())
            }

            is UFServiceMessageV1.State -> {
                val currentMessage = msg.toJson()
                copy(messageToSendOnSync = currentMessage, currentMessage = currentMessage)
            }
        }
    }
}

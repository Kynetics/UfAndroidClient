/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.configuration

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.kynetics.uf.android.UpdateFactoryService
import com.kynetics.uf.android.api.Communication
import com.kynetics.uf.android.communication.MessageHandler
import com.kynetics.uf.android.communication.MessengerHandler
import com.kynetics.uf.android.update.CurrentUpdateState
import org.eclipse.hara.ddiclient.core.api.MessageListener

class AndroidMessageListener(private val service: UpdateFactoryService) : MessageListener {

    private val currentUpdateState = CurrentUpdateState(service.applicationContext)
    private val mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onMessage(message: MessageListener.Message) {
        when (message) {
            is MessageListener.Message.Event.UpdateFinished,
            is MessageListener.Message.State.CancellingUpdate -> {
                currentUpdateState.clearState()
                MessengerHandler.onAction(MessageHandler.Action.UPDATE_FINISH)
            }
            is MessageListener.Message.Event.UpdateAvailable -> currentUpdateState.setCurrentUpdateId(message.id)
            is MessageListener.Message.Event.AllFilesDownloaded -> currentUpdateState.allFileDownloaded()
        }

        MessengerHandler.onMessageReceived(message)
        MessengerHandler.sendMessage(Communication.V1.Out.ServiceNotification.ID)

        mNotificationManager.notify(UpdateFactoryService.NOTIFICATION_ID, service.getNotification(message.toString()))
        Log.i(TAG, message.toString())
    }

    companion object {
        val TAG: String = AndroidMessageListener::class.java.simpleName
    }
}

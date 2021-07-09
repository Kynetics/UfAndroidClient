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
import android.content.Intent
import com.kynetics.uf.android.UpdateFactoryService
import com.kynetics.uf.android.api.Communication
import com.kynetics.uf.android.communication.MessengerHandler
import com.kynetics.uf.android.ui.MainActivity
import com.kynetics.uf.android.update.CurrentUpdateState
import org.eclipse.hara.ddiclient.core.api.DeploymentPermitProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

interface AndroidDeploymentPermitProvider : DeploymentPermitProvider {
    fun allow(isAllowed: Boolean)

    companion object {
        @ExperimentalCoroutinesApi
        fun build(
            configurationHandler: ConfigurationHandler,
            mNotificationManager: NotificationManager,
            service: UpdateFactoryService
        ): AndroidDeploymentPermitProvider {
            return object : AndroidDeploymentPermitProvider {

                private var authResponse = CompletableDeferred<Boolean>()

                private fun allowedAsync(auth: UpdateFactoryService.Companion.AuthorizationType): Deferred<Boolean> {
                    if (configurationHandler.apiModeIsEnabled()) {
                        MessengerHandler.sendMessage(Communication.V1.Out.AuthorizationRequest.ID, auth.name)
                    } else {
                        showAuthorizationDialog(auth)
                    }

                    authResponse.complete(false)
                    authResponse = CompletableDeferred()
                    authResponse.invokeOnCompletion {
                        if (authResponse.getCompleted()) {
                            mNotificationManager.notify(UpdateFactoryService.NOTIFICATION_ID,
                                service.getNotification(auth.event.toString(), true))
                            MessengerHandler.onAction(auth.toActionOnGranted)
                        } else {
                            MessengerHandler.onAction(auth.toActionOnDenied)
                        }
                    }

                    return authResponse
                }

                override fun allow(isAllowed: Boolean) {
                    authResponse.complete(isAllowed)
                }

                override fun downloadAllowed(): Deferred<Boolean> {
                    val currentUpdateState = CurrentUpdateState(service)
                    return if (currentUpdateState.isAllFileDownloaded()) {
                        CompletableDeferred(true)
                    } else {
                        allowedAsync(UpdateFactoryService.Companion.AuthorizationType.DOWNLOAD)
                    }
                }

                override fun updateAllowed(): Deferred<Boolean> {
                    val currentUpdateState = CurrentUpdateState(service)
                    return if(currentUpdateState.isUpdateStart()){
                        CompletableDeferred(true)
                    } else {
                        allowedAsync(UpdateFactoryService.Companion.AuthorizationType.UPDATE)
                    }
                }

                private fun showAuthorizationDialog(authorization: UpdateFactoryService.Companion.AuthorizationType) {
                    val intent = Intent(service, MainActivity::class.java)
                    intent.putExtra(MainActivity.INTENT_TYPE_EXTRA_VARIABLE, authorization.extra)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(intent)
                }
            }
        }
    }
}

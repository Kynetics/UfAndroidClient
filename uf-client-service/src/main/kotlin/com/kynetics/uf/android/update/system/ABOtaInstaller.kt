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
import android.os.Build
import android.os.PowerManager
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import androidx.annotation.RequiresApi
import android.util.Log
import com.kynetics.uf.android.api.Communication
import com.kynetics.uf.android.api.v1.UFServiceMessageV1
import com.kynetics.uf.android.communication.MessengerHandler
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.util.zip.getEntryOffset
import org.eclipse.hara.ddiclient.core.api.Updater
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.streams.toList

@RequiresApi(Build.VERSION_CODES.O)
internal object ABOtaInstaller : OtaInstaller {

    val TAG: String = ABOtaInstaller::class.java.simpleName
    private const val PROPERTY_FILE = "payload_properties.txt"
    private const val PAYLOAD_FILE = "payload.bin"
    private val UPDATE_STATUS = mapOf(
        UpdateEngine.UpdateStatusConstants.IDLE to "Idle",
        UpdateEngine.UpdateStatusConstants.CHECKING_FOR_UPDATE to "Checking for update",
        UpdateEngine.UpdateStatusConstants.UPDATE_AVAILABLE to "Update available",
        UpdateEngine.UpdateStatusConstants.DOWNLOADING to "Copying file",
        UpdateEngine.UpdateStatusConstants.VERIFYING to "Verifying",
        UpdateEngine.UpdateStatusConstants.FINALIZING to "Finalizing",
        UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT to "Rebooting",
        UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT to "Reporting error event",
        UpdateEngine.UpdateStatusConstants.ATTEMPTING_ROLLBACK to "Attempting rollback",
        UpdateEngine.UpdateStatusConstants.DISABLED to "Disable")

    @Suppress("MagicNumber")
    private val errorCodeToDescription = mapOf(
        0 to "Success",
        1 to "Error",
        2 to "Omaha request error",
        3 to "Omaha response handler error",
        4 to "Filesystem copier error",
        5 to "Postinstall runner error",
        6 to "Payload mismatched type",
        7 to "Install device open error",
        8 to "Kernel device open error",
        9 to "Download transfer error",
        10 to "Payload hash mismatch error",
        11 to "Payload size mismatch error",
        12 to "Download payload verification error",
        13 to "Download new partition info error",
        14 to "Download write error",
        15 to "New rootfs verification error",
        16 to "New kernel verification error",
        17 to "Signed delta payload expected error",
        18 to "Download payload pub key verification error",
        19 to "Postinstall booted from firmware b",
        20 to "Download state initialization error",
        21 to "Download invalid metadata magic string",
        22 to "Download signature missing in manifest",
        23 to "Download manifest parse error",
        24 to "Download metadata signature error",
        25 to "Download metadata signature verification error",
        26 to "Download metadata signature mismatch",
        27 to "Download operation hash verification error",
        28 to "Download operation execution error",
        29 to "Download operation hash mismatch",
        30 to "Omaha request empty response error",
        31 to "Omaha request xmlparse error",
        32 to "Download invalid metadata size",
        33 to "Download invalid metadata signature",
        34 to "Omaha response invalid",
        35 to "Omaha update ignored per policy",
        36 to "Omaha update deferred per policy",
        37 to "Omaha error in httpresponse",
        38 to "Download operation hash missing error",
        39 to "Download metadata signature missing error",
        40 to "Omaha update deferred for backoff",
        41 to "Postinstall powerwash error",
        42 to "Update canceled by channel change",
        43 to "Postinstall firmware ronot updatable",
        44 to "Unsupported major payload version",
        45 to "Unsupported minor payload version",
        46 to "Omaha request xmlhas entity decl",
        47 to "Filesystem verifier error",
        48 to "User canceled",
        49 to "Non critical update in oobe",
        50 to "Omaha update ignored over cellular",
        51 to "Payload timestamp error",
        52 to "Updated but not active")

    private const val HOURS_TIMEOUT_FOR_UPDATE = 3L

    private val updateEngine: UpdateEngine = UpdateEngine()

    override fun install(
        artifact: Updater.SwModuleWithPath.Artifact,
        currentUpdateState: CurrentUpdateState,
        messenger: Updater.Messenger,
        context: Context
    ): CurrentUpdateState.InstallationResult {
        if (currentUpdateState.isABInstallationPending(artifact)) {
            val result = currentUpdateState.lastABIntallationResult(artifact)
            val message = "Installation result of Ota named ${artifact.filename} is " +
                if (result is CurrentUpdateState.InstallationResult.Success) "success" else "failure"
            messenger.sendMessageToServer(message + result.details)
            Log.i(TAG, message)
            return result
        }

        currentUpdateState.saveSlotName()
        val updateStatus = CompletableFuture<Int>()
        val zipFile = ZipFile(artifact.path)

        val payloadEntry = zipFile.getEntry(PAYLOAD_FILE)
        val propEntry = zipFile.getEntry(PROPERTY_FILE)
        if (payloadEntry == null || propEntry == null) {
            Log.d(TAG, "Malformed AB ota")
            return CurrentUpdateState.InstallationResult.Error(
                listOf(
                    "Malformed ota for AB update.",
                    "An AB ota update must contain a payload file named $PAYLOAD_FILE and a property file named " +
                        PROPERTY_FILE
                )
            )
        }

        val prop = zipFile.getInputStream(zipFile.getEntry(PROPERTY_FILE))
            .bufferedReader().lines().toList().toTypedArray()

        Log.d(TAG, prop.joinToString())

        updateEngine.bind(
            MyUpdateEngineCallback(
                context,
                messenger,
                updateStatus
            )
        )
        currentUpdateState.addPendingABInstallation(artifact)
        messenger.sendMessageToServer("Applying A/B ota update (${artifact.filename})...")
        val zipPath = "file://${artifact.path}"
        Log.d(TAG, zipPath)
        updateEngine.applyPayload(zipPath, zipFile.getEntryOffset(PAYLOAD_FILE), 0, prop)
        return installationResult(
            updateStatus,
            messenger,
            artifact
        )
    }

    private fun installationResult(
        updateStatus: CompletableFuture<Int>,
        messenger: Updater.Messenger,
        artifact: Updater.SwModuleWithPath.Artifact
    ): CurrentUpdateState.InstallationResult {
        return try {
            @Suppress("MagicNumber")
            val result: Int = updateStatus.get(HOURS_TIMEOUT_FOR_UPDATE, TimeUnit.HOURS)
            updateEngine.unbind()
            val messages = listOf("result: $result", errorCodeToDescription[result] ?: "")
            Log.d(TAG, "result: ${messages.joinToString(" ")}")
            when (result) {

                UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT -> {
                    Log.w(TAG, "Reboot fail, waiting for a manual reboot")
                    CountDownLatch(1).await()
                    CurrentUpdateState.InstallationResult.Error(
                        listOf(
                            "Update is successfully applied but system failed to reboot",
                            "Installation status unknown"
                        )
                    )
                }

                UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE -> {
                    messenger.sendMessageToServer("Update is successfully applied but system failed to reboot")
                    CurrentUpdateState.InstallationResult.Success()
                }

                UpdateEngine.ErrorCodeConstants.SUCCESS -> CurrentUpdateState.InstallationResult.Success()

                else -> CurrentUpdateState.InstallationResult.Error(
                    messages
                )
            }
        } catch (e: Throwable) {
            when (e) {
                is TimeoutException -> {
                    val messages = listOf(
                        "Time to update exceeds the timeout",
                        "Package manager timeout expired, package installation status unknown"
                    )
                    CurrentUpdateState.InstallationResult.Error(
                        messages
                    )
                }
                else -> {
                    Log.w(TAG, "Exception on apply AB update (${artifact.filename})", e)
                    CurrentUpdateState.InstallationResult.Error(
                        listOf("error: ${e.message}")
                    )
                }
            }
        }
    }

    private class MyUpdateEngineCallback(
        private val context: Context,
        private val messenger: Updater.Messenger,
        private val updateStatus: CompletableFuture<Int>
    ) : UpdateEngineCallback() {

        companion object {
            private const val MAX_MESSAGES_PER_PHASE = 10
        }

        private var previousState = Int.MAX_VALUE
        private val queue = ArrayBlockingQueue<Double>(MAX_MESSAGES_PER_PHASE, true)

        override fun onStatusUpdate(i: Int, v: Float) { // i==status  v==percent
            Log.d(TAG, "status:$i")
            Log.d(TAG, "percent:$v")
            val currentPhaseProgress = min(v.toDouble(),100.0)
            val newPhase = previousState != i
            if (newPhase) {
                previousState = i
                messenger.sendMessageToServer(UPDATE_STATUS.getValue(i))
                queue.clear()
                queue.addAll((1 until MAX_MESSAGES_PER_PHASE).map { it.toDouble() / MAX_MESSAGES_PER_PHASE })
            }

            val limit = queue.peek() ?: 1.0
            if (currentPhaseProgress > limit || currentPhaseProgress == 1.0 || newPhase) {
                MessengerHandler.onAndroidMessage(UFServiceMessageV1.Event.UpdateProgress(
                    phaseName = UPDATE_STATUS.getValue(i),
                    percentage = currentPhaseProgress
                ))

                MessengerHandler.sendMessage(Communication.V1.Out.ServiceNotification.ID)
                while (currentPhaseProgress >= queue.peek() ?: 1.0 && queue.isNotEmpty()) {
                    queue.poll()
                }
            }

            if (i == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                pm!!.reboot(null)
                Log.w(TAG, "Reboot fail")
                messenger.sendMessageToServer("Update is successfully applied but system failed to reboot",
                    "Waiting manual reboot")
                updateStatus.complete(UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT)
            }
        }

        override fun onPayloadApplicationComplete(errorNum: Int) {
            Log.d(
                TAG,
                "onPayloadApplicationComplete: $errorNum"
            )
            updateStatus.complete(errorNum)
        }
    }
}

/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kynetics.uf.android.api.ApiCommunicationVersion
import com.kynetics.uf.android.api.Communication
import com.kynetics.uf.android.api.Communication.Companion.SERVICE_API_VERSION_KEY
import com.kynetics.uf.android.api.Communication.V1.Companion.SERVICE_DATA_KEY
import com.kynetics.uf.android.api.UFServiceConfiguration
import com.kynetics.uf.android.apicomptibility.ApiVersion
import com.kynetics.uf.android.client.RestartableClientService
import com.kynetics.uf.android.communication.MessageHandler
import com.kynetics.uf.android.communication.MessengerHandler
import com.kynetics.uf.android.configuration.AndroidDeploymentPermitProvider
import com.kynetics.uf.android.configuration.AndroidMessageListener
import com.kynetics.uf.android.configuration.ConfigurationHandler
import com.kynetics.uf.android.content.UFSharedPreferences
import com.kynetics.uf.android.ui.MainActivity
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.SystemUpdateType
import de.psdev.slf4j.android.logger.AndroidLoggerAdapter
import de.psdev.slf4j.android.logger.LogLevel
import org.eclipse.hara.ddiclient.core.api.MessageListener

/*
 * @author Daniele Sergio
 */
class UpdateFactoryService : Service(), UpdateFactoryServiceCommand {

    override fun authorizationGranted() {
        deploymentPermitProvider?.allow(true)
    }

    override fun authorizationDenied() {
        deploymentPermitProvider?.allow(false)
    }

    private val mMessenger = Messenger(IncomingHandler())

    private var mNotificationManager: NotificationManager? = null
    private var systemUpdateType: SystemUpdateType = SystemUpdateType.SINGLE_COPY

    private var deploymentPermitProvider: AndroidDeploymentPermitProvider? = null
    private var messageListener: MessageListener? = null

    override fun configureService() {
        if(ufService == null){
            ufService = RestartableClientService.newInstance(deploymentPermitProvider!!,listOf(messageListener!!))
        }
        when {
            configurationHandler!=null -> {
                ufService?.restartService(configurationHandler!!)
            }
            else -> {
                Log.w(TAG, "Service cant be configured because configuration handler is null")
            }
        }
    }

    private lateinit var forcePingPendingIntent: PendingIntent
    lateinit var currentUpdateState: CurrentUpdateState

    override fun onCreate() {
        super.onCreate()
        AndroidLoggerAdapter.setLogTag("Update Factory Client")
        if(BuildConfig.DEBUG){
            AndroidLoggerAdapter.setLogLevel(LogLevel.TRACE)
        }
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sharedPreferencesFile = getString(R.string.shared_preferences_file)
        configurationHandler = ConfigurationHandler( this, getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE))
        systemUpdateType = SystemUpdateType.getSystemUpdateType()
        ufServiceCommand = this
        deploymentPermitProvider = AndroidDeploymentPermitProvider.build(configurationHandler!!, mNotificationManager!!, this)
        messageListener = AndroidMessageListener(this)
        currentUpdateState = CurrentUpdateState(this)

        val forcePingIntent = Intent(FORCE_PING_ACTION)

        forcePingPendingIntent = PendingIntent.getBroadcast(this, 1, forcePingIntent, 0)

        // add actions here !
        val intentFilter = IntentFilter()
        intentFilter.addAction(FORCE_PING_ACTION)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == FORCE_PING_ACTION) {
                    ufService?.forcePing()
                    MessengerHandler.onAction(MessageHandler.Action.FORCE_PING)
                    val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    sendBroadcast(closeIntent)
                }
            }
        }

        registerReceiver(receiver, intentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, String.format("service's starting with version %s (%s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        startForeground()
        isRunning = true
        var serviceConfiguration = configurationHandler?.getConfigurationFromFile()
        if (serviceConfiguration == null && intent != null) {
            serviceConfiguration = configurationHandler?.getServiceConfigurationFromIntent(intent)
        } else if (serviceConfiguration != null) {
            Log.i(TAG, "Loaded new configuration from file")
        }
        if (configurationHandler?.getCurrentConfiguration() != serviceConfiguration) {
            configurationHandler?.saveServiceConfigurationToSharedPreferences(serviceConfiguration)
        }
        configureService()
        return START_STICKY
    }

    // todo add api to configure targetAttibutes (separete  d from serviceConfiguration)
    private inner class IncomingHandler : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Communication.V1.In.ConfigureService.ID -> configureServiceFromMsg(msg)

                Communication.V1.In.RegisterClient.ID -> {
                    Log.i(TAG, "receive subscription request")
                    MessengerHandler.subscribeClient(msg.replyTo, ApiCommunicationVersion.fromVersionCode(msg.data.getInt(SERVICE_API_VERSION_KEY, 0)))
                }

                Communication.V1.In.UnregisterClient.ID -> {
                    Log.i(TAG, "receive unsubscription request")
                    MessengerHandler.unsubscribeClient(msg.replyTo)
                }

                Communication.V1.In.AuthorizationResponse.ID -> authorizationResponse(msg)

                Communication.V1.In.ForcePing.id -> {
                    Log.i(TAG, "receive request to resume suspend state")
                    ufService?.forcePing()
                }

                Communication.V1.In.Sync.ID -> sync(msg)

                else -> Log.i(TAG, "Invalid message receive (what == ${msg.what})")
            }
        }

        private fun sync(msg: Message) {
            Log.i(TAG, "received sync request")

            if (msg.replyTo == null) {
                Log.i(TAG, "command ignored because field replyTo is null")
                return
            }

            MessengerHandler.sendMessage(
                    configurationHandler?.getCurrentConfiguration(),
                    Communication.V1.Out.CurrentServiceConfiguration.ID,
                    msg.replyTo
            )
            val api = ApiCommunicationVersion.fromVersionCode(msg.data.getInt(SERVICE_API_VERSION_KEY, 0))
            if (MessengerHandler.hasMessage(api)) {
                MessengerHandler.sendMessage(
                        MessengerHandler.getlastSharedMessage(api).messageToSendOnSync,
                        Communication.V1.Out.ServiceNotification.ID,
                        msg.replyTo
                )
            }
            Log.i(TAG, "client synced")
        }

        private fun authorizationResponse(msg: Message) {
            Log.i(TAG, "receive authorization response")
            if(!msg.data.containsKey(SERVICE_DATA_KEY)){
                Log.i(TAG, "Invalid authorization response message received")
                return;
            }
            val response = msg.data.getBoolean(SERVICE_DATA_KEY)
            deploymentPermitProvider?.allow(response)
            Log.i(TAG, String.format("authorization %s", if (response) "granted" else "denied"))
        }

        private fun configureServiceFromMsg(msg: Message) {
            Log.i(TAG, "receive configuration update request")
            val configuration =  try{
                if(!msg.data.containsKey(SERVICE_DATA_KEY)){
                    Log.i(TAG, "Invalid configuration message received (no configuration found)")
                    return;
                }
                msg.data.getSerializable(SERVICE_DATA_KEY) as UFServiceConfiguration
            } catch (e:Throwable){
                Log.i(TAG, "Invalid configuration message received; Error on configuration deserialize.")
                return;
            }
            val currentConf = configurationHandler?.getCurrentConfiguration()

            if (currentConf != configuration) {
                configurationHandler?.saveServiceConfigurationToSharedPreferences(configuration)
                Log.i(TAG, "configuration updated")
            } else {
                Log.i(TAG, "new configuration equals to current configuration")
            }

            if (configurationHandler?.needReboot(currentConf) == true) {
                configureService()
                Log.i(TAG, "configuration updated - restarting service")
            } else {
                Log.i(TAG, "configuration updated - service not restarted")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        startService(this)
        return mMessenger.binder
    }

    override fun getSharedPreferences(name: String?, mode: Int): UFSharedPreferences {
        return UFSharedPreferences.get(applicationContext, name, mode)
    }

    private fun startForeground() {
        if(mNotificationManager != null) {
            ApiVersion.fromVersionCode().configureChannel(CHANNEL_ID, getString(R.string.app_name), mNotificationManager!!)
            startForeground(NOTIFICATION_ID, getNotification(""))
        }
    }

    fun getNotification(notificationContent: String, forcePingAction: Boolean = false): Notification {

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.uf_logo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent))
                .setContentTitle(getString(R.string.update_factory_notification_title))
                .setContentText(notificationContent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (forcePingAction) {
            notificationBuilder.addAction(android.R.drawable.ic_popup_sync, "Grant Authorization", forcePingPendingIntent)
        }
        return notificationBuilder.build()
    }

    companion object {
        enum class AuthorizationType(
                val toActionOnGranted: MessageHandler.Action,
                val toActionOnDenied: MessageHandler.Action
        ) {
            DOWNLOAD(
                    MessageHandler.Action.AUTH_DOWNLOAD_GRANTED,
                    MessageHandler.Action.AUTH_DOWNLOAD_DENIED
            ) {
                override val extra = MainActivity.INTENT_TYPE_EXTRA_VALUE_DOWNLOAD
                override val event = MessageListener.Message.State.WaitingDownloadAuthorization
            },

            UPDATE(MessageHandler.Action.AUTH_UPDATE_GRANTED,
                    MessageHandler.Action.AUTH_UPDATE_DENIED) {
                override val extra: Int = MainActivity.INTENT_TYPE_EXTRA_VALUE_REBOOT
                override val event = MessageListener.Message.State.WaitingUpdateAuthorization
            };

            abstract val extra: Int
            abstract val event: MessageListener.Message.State
        }

        @JvmStatic
        fun startService(context: Context) {
            if (!isRunning) {
                val myIntent = Intent(context, UpdateFactoryService::class.java)
                ApiVersion.fromVersionCode().startService(context, myIntent)
            }
        }

        var isRunning = false

        @JvmStatic
        var ufServiceCommand: UpdateFactoryServiceCommand? = null
        private const val FORCE_PING_ACTION = "ForcePing"
        private var sharedPreferencesFile: String? = null
        private var configurationHandler: ConfigurationHandler? = null
        private var ufService: RestartableClientService? = null
        private const val CHANNEL_ID = "UPDATE_FACTORY_NOTIFICATION_CHANNEL_ID"
        const val NOTIFICATION_ID = 1
        private val TAG = UpdateFactoryService::class.java.simpleName
    }
}

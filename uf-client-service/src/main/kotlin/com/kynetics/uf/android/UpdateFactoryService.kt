/*
 *
 *  Copyright © 2017-2019  Kynetics  LLC
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 */

package com.kynetics.uf.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
import com.kynetics.uf.android.api.ApiCommunicationVersion
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_AUTHORIZATION_REQUEST
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_AUTHORIZATION_RESPONSE
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_CONFIGURE_SERVICE
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_FORCE_PING
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_REGISTER_CLIENT
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_RESUME_SUSPEND_UPGRADE
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_SERVICE_CONFIGURATION_STATUS
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_SERVICE_STATUS
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_SYNC_REQUEST
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.MSG_UNREGISTER_CLIENT
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.SERVICE_API_VERSION_KEY
import com.kynetics.uf.android.api.UFServiceCommunicationConstants.SERVICE_DATA_KEY
import com.kynetics.uf.android.api.UFServiceConfiguration
import com.kynetics.uf.android.apicomptibility.ApiVersion
import com.kynetics.uf.android.communication.MessageHandler
import com.kynetics.uf.android.communication.MessangerHandler
import com.kynetics.uf.android.configuration.ConfigurationFileLoader
import com.kynetics.uf.android.content.SharedPreferencesWithObject
import com.kynetics.uf.android.ui.MainActivity
import com.kynetics.uf.android.update.ApkUpdater
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.OtaUpdater
import com.kynetics.uf.android.update.SystemUpdateType
import com.kynetics.updatefactory.ddiclient.core.UpdateFactoryClientDefaultImpl
import com.kynetics.updatefactory.ddiclient.core.api.ConfigDataProvider
import com.kynetics.updatefactory.ddiclient.core.api.DeploymentPermitProvider
import com.kynetics.updatefactory.ddiclient.core.api.DirectoryForArtifactsProvider
import com.kynetics.updatefactory.ddiclient.core.api.MessageListener
import com.kynetics.updatefactory.ddiclient.core.api.UpdateFactoryClient
import com.kynetics.updatefactory.ddiclient.core.api.UpdateFactoryClientData
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue

/*
 * @author Daniele Sergio
 */
class UpdateFactoryService : Service(), UpdateFactoryServiceCommand {

    override fun authorizationGranted() {
        authResponse.offer(true)
    }

    override fun authorizationDenied() {
        authResponse.offer(false)
    }

    private val mMessenger = Messenger(IncomingHandler())

    private var ufService: UpdateFactoryClient? = null

    private var sharedPreferencesServerUrlKey: String? = null
    private var sharedPreferencesApiModeKey: String? = null
    private var sharedPreferencesServiceEnableKey: String? = null
    private var sharedPreferencesTenantKey: String? = null
    private var sharedPreferencesControllerIdKey: String? = null
    private var sharedPreferencesRetryDelayKey: String? = null
    private var sharedPreferencesGatewayToken: String? = null
    private var sharedPreferencesTargetToken: String? = null
    private var sharedPreferencesFile: String? = null
    private var sharedPreferencesIsUpdateFactoryServerType: String? = null
    private var sharedPreferencesTargetAttributes: String? = null
    private val authResponse = ArrayBlockingQueue<Boolean>(1)
    private var mNotificationManager: NotificationManager? = null
    private var systemUpdateType: SystemUpdateType = SystemUpdateType.SINGLE_COPY

    override fun configureService() {
        buildServiceFromPreferences()
    }

    lateinit var forcePingPendingIntent: PendingIntent
    lateinit var currentUpdateState: CurrentUpdateState

    fun Map<String, String>.toMD5(): String {
        val content = entries.sortedBy { it.key }.map { "${it.key}_${it.value}" }.joinToString("-")
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray())
        return bytes.toMD5()
    }

    fun ByteArray.toMD5(): String {
        return this.joinToString("") { "%02x".format(it) }
    }
    override fun onCreate() {
        super.onCreate()
        initSharedPreferencesKeys()
        systemUpdateType = SystemUpdateType.getSystemUpdateType()
        ufServiceCommand = this

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
                    MessangerHandler.onAction(MessageHandler.Action.FORCE_PING)
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
        val configurationFile = ConfigurationFileLoader(super.getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE), UF_CONF_FILE, applicationContext)
        var serviceConfiguration = configurationFile.newFileConfiguration
        if (serviceConfiguration == null && intent != null) {
            Log.i(TAG, "Loaded new configuration from intent")
            val serializable = intent.getSerializableExtra(SERVICE_DATA_KEY)
            val string = intent.getStringExtra(SERVICE_DATA_KEY)
            try {
                serviceConfiguration = when {

                    serializable is String -> UFServiceConfiguration.fromJson(serializable)

                    serializable is UFServiceConfiguration -> serializable

                    string != null -> UFServiceConfiguration.fromJson(string)

                    else -> null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Deserialization error", e)
            }
        } else if (serviceConfiguration != null) {
            Log.i(TAG, "Loaded new configuration from file")
        }
        if (getCurrentConfiguration(getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE)) != serviceConfiguration) {
            saveServiceConfigurationToSharedPreferences(serviceConfiguration)
            buildServiceFromPreferences()
        }
        return Service.START_STICKY
    }

    private fun buildServiceFromPreferences() {
        ufService?.stop()
        val sharedPreferences = getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE)
        val serviceConfiguration = getCurrentConfiguration(sharedPreferences)
        if (sharedPreferences.getBoolean(sharedPreferencesServiceEnableKey, false)) {
            val apiMode = sharedPreferences.getBoolean(sharedPreferencesApiModeKey, false)
            val deploymentPermitProvider = object : DeploymentPermitProvider {

                private fun allowed(auth: AuthorizationType): Boolean {
                    if (apiMode) {
                        MessangerHandler.sendMessage(MSG_AUTHORIZATION_REQUEST, auth.name)
                    } else {
                        showAuthorizationDialog(auth)
                    }

                    return try {
                        val isGranted = authResponse.take()
                        if (isGranted) {
                            mNotificationManager?.notify(NOTIFICATION_ID, getNotification(auth.event.toString(), true))
                            MessangerHandler.onAction(auth.toActionOnGranted)
                        } else {
                            MessangerHandler.onAction(auth.toActionOnDenied)
                        }
                        isGranted
                    } catch (e: InterruptedException) {
                        MessangerHandler.onAction(auth.toActionOnDenied)
                        false
                    }
                }

                override fun downloadAllowed(): Boolean {
                    return allowed(Companion.AuthorizationType.DOWNLOAD)
                }
                override fun updateAllowed(): Boolean {
                    return allowed(Companion.AuthorizationType.UPDATE)
                }
            }

            val eventListener = object : MessageListener {
                override fun onMessage(message: MessageListener.Message) {
                    when (message) {
                        is MessageListener.Message.Event.UpdateFinished, is MessageListener.Message.State.CancellingUpdate -> {
                            currentUpdateState.clearState()
                            MessangerHandler.onAction(MessageHandler.Action.UPDATE_FINISH)
                        }
                    }

                    MessangerHandler.onMessageReceived(message)
                    MessangerHandler.sendMessage(MSG_SERVICE_STATUS)

                    mNotificationManager?.notify(NOTIFICATION_ID, getNotification(message.toString()))
                    Log.i(TAG, message.toString())
                }
            }

            try {
                val clientData = UpdateFactoryClientData(
                    serviceConfiguration.tenant,
                    serviceConfiguration.controllerId,
                    serviceConfiguration.url,
                    if (serviceConfiguration.isUpdateFactoryServe) { UpdateFactoryClientData.ServerType.UPDATE_FACTORY } else { UpdateFactoryClientData.ServerType.HAWKBIT },
                    serviceConfiguration.gatewayToken,
                    serviceConfiguration.targetToken
                )

                ufService = UpdateFactoryClientDefaultImpl()
                ufService!!.init(
                    clientData,
                    object : DirectoryForArtifactsProvider { override fun directoryForArtifacts(): File = currentUpdateState.rootDir() },
                    object : ConfigDataProvider {
                        override fun configData(): Map<String, String> {
                            return decorateTargetAttribute(sharedPreferences)
                        }

                        override fun isUpdated(): Boolean {
                            val md5 = decorateTargetAttribute(sharedPreferences).toMD5()
                            return md5 == sharedPreferences.getString(LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY, "")
                        }

                        override fun onConfigDataUpdate() {
                            val md5 = decorateTargetAttribute(sharedPreferences).toMD5()
                            sharedPreferences.edit().putString(LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY, md5)
                                .apply()
                        }
                    },
                    deploymentPermitProvider,
                    listOf(eventListener),
                    OtaUpdater(this@UpdateFactoryService),
                    ApkUpdater(this@UpdateFactoryService)

                )
                ufService!!.startAsync()
            } catch (e: IllegalStateException) {
                sharedPreferences.edit().putBoolean(sharedPreferencesServiceEnableKey, false).apply()
                Toast.makeText(this, "Update Factory configuration error", Toast.LENGTH_LONG)
                    .show()
            } catch (e: IllegalArgumentException) {
                sharedPreferences.edit().putBoolean(sharedPreferencesServiceEnableKey, false).apply()
                Toast.makeText(this, "Update Factory configuration error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decorateTargetAttribute(sharedPreferences: SharedPreferencesWithObject): Map<String, String> {
        var targetAttributes: MutableMap<String, String>? = sharedPreferences.getObject<HashMap<String, String>>(sharedPreferencesTargetAttributes, HashMap<String, String>().javaClass)
        if (targetAttributes == null) {
            targetAttributes = HashMap()
        }
        targetAttributes = targetAttributes.toMutableMap()
        targetAttributes[CLIENT_TYPE_TARGET_TOKEN_KEY] = "Android"
        targetAttributes[CLIENT_VERSION_TARGET_ATTRIBUTE_KEY] = BuildConfig.VERSION_NAME // TODO: 4/17/18 refactor
        targetAttributes[CLIENT_VERSION_CODE_ATTRIBUTE_KEY] = BuildConfig.VERSION_CODE.toString()
        val buildDate = Date(android.os.Build.TIME)
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.UK)
        targetAttributes[ANDROID_BUILD_DATE_TARGET_ATTRIBUTE_KEY] = dateFormat.format(buildDate)
        targetAttributes[ANDROID_BUILD_TYPE_TARGET_ATTRIBUTE_KEY] = android.os.Build.TYPE
        targetAttributes[ANDROID_FINGERPRINT_TARGET_ATTRIBUTE_KEY] = android.os.Build.FINGERPRINT
        targetAttributes[ANDROID_KEYS_TARGET_ATTRIBUTE_KEY] = android.os.Build.TAGS
        targetAttributes[ANDROID_VERSION_TARGET_ATTRIBUTE_KEY] = android.os.Build.VERSION.RELEASE
        targetAttributes[DEVICE_NAME_TARGET_ATTRIBUTE_KEY] = android.os.Build.DEVICE
        targetAttributes[SYSTEM_UPDATE_TYPE] = systemUpdateType.name
        return targetAttributes
    }

    private fun getCurrentConfiguration(sharedPreferences: SharedPreferencesWithObject): UFServiceConfiguration {
        val serviceIsEnable = ufService != null && sharedPreferences.getBoolean(sharedPreferencesServiceEnableKey, false)
        val url = sharedPreferences.getString(sharedPreferencesServerUrlKey, "")
        val controllerId = sharedPreferences.getString(sharedPreferencesControllerIdKey, "")
        val gatewayToken = sharedPreferences.getString(sharedPreferencesGatewayToken, "")
        val targetToken = sharedPreferences.getString(sharedPreferencesTargetToken, "")
        val tenant = sharedPreferences.getString(sharedPreferencesTenantKey, "")
        val delay = sharedPreferences.getLong(sharedPreferencesRetryDelayKey, 900000)
        val apiMode = sharedPreferences.getBoolean(sharedPreferencesApiModeKey, true)
        var targetAttributes: MutableMap<String, String>? = sharedPreferences.getObject<HashMap<String, String>>(sharedPreferencesTargetAttributes, HashMap<String, String>().javaClass)
        if (targetAttributes == null) {
            targetAttributes = HashMap()
        }
        val serverType = if (sharedPreferences.getBoolean(sharedPreferencesIsUpdateFactoryServerType, true)) {
            UpdateFactoryClientData.ServerType.UPDATE_FACTORY
        } else {
            UpdateFactoryClientData.ServerType.HAWKBIT
        }
        return UFServiceConfiguration.builder()
            .withTargetAttributes(targetAttributes)
            .withEnable(serviceIsEnable)
            .withApiMode(apiMode)
            .withControllerId(controllerId)
            .withGetawayToken(gatewayToken)
            .withRetryDelay(delay)
            .withTargetToken(targetToken)
            .withTenant(tenant)
            .withIsUpdateFactoryServer(serverType == UpdateFactoryClientData.ServerType.UPDATE_FACTORY)
            .withUrl(url)
            .build()
    }

    // todo api to configure targetAttibutes (separeted from serviceConfiguration)
    private inner class IncomingHandler : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CONFIGURE_SERVICE -> {
                    Log.i(TAG, "receive configuration update request")
                    val configuration = msg.data.getSerializable(SERVICE_DATA_KEY) as UFServiceConfiguration
                    val currentConf = getCurrentConfiguration(getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE))

                    if (currentConf != configuration) {
                        saveServiceConfigurationToSharedPreferences(configuration)
                        Log.i(TAG, "configuration updated")
                    } else {
                        Log.i(TAG, "new configuration equals to current configuration")
                    }

                    if (currentConf.copy(targetAttributes = emptyMap()) != configuration.copy(targetAttributes = emptyMap())) {
                        buildServiceFromPreferences()
                        Log.i(TAG, "configuration updated - restarting service")
                    }
                }

                MSG_REGISTER_CLIENT -> {
                    Log.i(TAG, "receive subscription request")
                    MessangerHandler.subscribeClient(msg.replyTo, ApiCommunicationVersion.fromVersionCode(msg.data.getInt(SERVICE_API_VERSION_KEY, 0)))
                }
                MSG_UNREGISTER_CLIENT -> {
                    Log.i(TAG, "receive unsubscription request")
                    MessangerHandler.unsubscribeClient(msg.replyTo)
                }
                MSG_AUTHORIZATION_RESPONSE -> {
                    Log.i(TAG, "receive authorization response")
                    val response = msg.data.getBoolean(SERVICE_DATA_KEY)
                    authResponse.offer(response)
                    Log.i(TAG, String.format("authorization %s", if (response) "granted" else "denied"))
                }
                MSG_RESUME_SUSPEND_UPGRADE, MSG_FORCE_PING -> {
                    Log.i(TAG, "receive request to resume suspend state")
                    ufService?.forcePing()
                }
                MSG_SYNC_REQUEST -> {
                    Log.i(TAG, "received sync request")

                    if (ufService == null || msg.replyTo == null) {
                        Log.i(TAG, "command ignored because ufService is not configured or field replyTo is null")
                        return
                    }

                    val sharedPreferences = getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE)
                    MessangerHandler.sendMessage(getCurrentConfiguration(sharedPreferences), MSG_SERVICE_CONFIGURATION_STATUS, msg.replyTo)
                    val api = ApiCommunicationVersion.fromVersionCode(msg.data.getInt(SERVICE_API_VERSION_KEY, 0))
                    if (MessangerHandler.hasMessage(api)) {
                        MessangerHandler.sendMessage(MessangerHandler.getlastSharedMessage(api).messageToSendOnSync, MSG_SERVICE_STATUS, msg.replyTo)
                    }
                    Log.i(TAG, "client synced")
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun saveServiceConfigurationToSharedPreferences(configuration: UFServiceConfiguration?) {
        if (configuration == null) {
            return
        }
        val sharedPreferences = getSharedPreferences(sharedPreferencesFile, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(sharedPreferencesControllerIdKey, configuration.controllerId)
        editor.putString(sharedPreferencesTenantKey, configuration.tenant)
        editor.putString(sharedPreferencesServerUrlKey, configuration.url)
        editor.putString(sharedPreferencesGatewayToken, configuration.gatewayToken)
        editor.putString(sharedPreferencesTargetToken, configuration.targetToken)
        editor.putLong(sharedPreferencesRetryDelayKey, configuration.retryDelay)
        editor.putBoolean(sharedPreferencesApiModeKey, configuration.isApiMode!!)
        editor.putBoolean(sharedPreferencesServiceEnableKey, configuration.isEnable!!)
        editor.putBoolean(sharedPreferencesIsUpdateFactoryServerType, configuration.isUpdateFactoryServe)
        editor.apply()
        sharedPreferences.putAndCommitObject(sharedPreferencesTargetAttributes, configuration.targetAttributes)
    }

    override fun onBind(intent: Intent): IBinder? {
        return mMessenger.binder
    }

    private fun showAuthorizationDialog(authorization: AuthorizationType) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.INTENT_TYPE_EXTRA_VARIABLE, authorization.extra)
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferencesWithObject {
        return SharedPreferencesWithObject(super.getSharedPreferences(name, mode))
    }

    private fun startForeground() {
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ApiVersion.fromVersionCode().configureChannel(CHANNEL_ID, getString(R.string.app_name), mNotificationManager)
        startForeground(NOTIFICATION_ID, getNotification(""))
    }

    private fun getNotification(notificationContent: String, forcePingAction: Boolean = false): Notification {

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

    private fun initSharedPreferencesKeys() {
        sharedPreferencesFile = getString(R.string.shared_preferences_file)
        sharedPreferencesServerUrlKey = getString(R.string.shared_preferences_server_url_key)
        sharedPreferencesApiModeKey = getString(R.string.shared_preferences_api_mode_key)
        sharedPreferencesTenantKey = getString(R.string.shared_preferences_tenant_key)
        sharedPreferencesControllerIdKey = getString(R.string.shared_preferences_controller_id_key)
        sharedPreferencesRetryDelayKey = getString(R.string.shared_preferences_retry_delay_key)
        sharedPreferencesServiceEnableKey = getString(R.string.shared_preferences_is_enable_key)
        sharedPreferencesGatewayToken = getString(R.string.shared_preferences_gateway_token_key)
        sharedPreferencesTargetToken = getString(R.string.shared_preferences_target_token_key)
        sharedPreferencesTargetAttributes = getString(R.string.shared_preferences_args_key)
        sharedPreferencesIsUpdateFactoryServerType = getString(R.string.shared_preferences_is_update_factory_server_type_key)
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
        var ufServiceCommand: UpdateFactoryServiceCommand? = null
        private const val FORCE_PING_ACTION = "ForcePing"

        private val CHANNEL_ID = "UPDATE_FACTORY_NOTIFICATION_CHANNEL_ID"
        private val NOTIFICATION_ID = 1
        private val TAG = UpdateFactoryService::class.java.simpleName
        private val CLIENT_VERSION_TARGET_ATTRIBUTE_KEY = "client_version"
        private val CLIENT_VERSION_CODE_ATTRIBUTE_KEY = "client_version_code"
        private val LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY = "LAST_TARGET_ATTRIBUTES_MD5_SET_KEY"
        //        private val SHARED_PREFERENCES_LAST_NOTIFY_MESSAGE = "LAST_NOTIFY_MESSAGE"
        private val EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory().path
        private val UF_CONF_FILE = "$EXTERNAL_STORAGE_DIR/UpdateFactoryConfiguration/ufConf.conf"
        private val ANDROID_BUILD_DATE_TARGET_ATTRIBUTE_KEY = "android_build_date"
        private val ANDROID_BUILD_TYPE_TARGET_ATTRIBUTE_KEY = "android_build_type"
        private val ANDROID_FINGERPRINT_TARGET_ATTRIBUTE_KEY = "android_fingerprint"
        private val ANDROID_KEYS_TARGET_ATTRIBUTE_KEY = "android_keys"
        private val ANDROID_VERSION_TARGET_ATTRIBUTE_KEY = "android_version"
        private val DEVICE_NAME_TARGET_ATTRIBUTE_KEY = "device_name"
        private val SYSTEM_UPDATE_TYPE = "system_update_type"
        private val CLIENT_TYPE_TARGET_TOKEN_KEY = "client"
        private val CLIENT_DATE_TARGET_TOKEN_KEY = "date"
    }
}

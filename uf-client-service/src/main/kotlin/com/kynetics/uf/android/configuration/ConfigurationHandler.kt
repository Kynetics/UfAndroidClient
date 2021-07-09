/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.configuration

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.kynetics.uf.android.BuildConfig
import com.kynetics.uf.android.R
import com.kynetics.uf.android.UpdateFactoryService
import com.kynetics.uf.android.api.Communication
import com.kynetics.uf.android.api.UFServiceConfiguration
import com.kynetics.uf.android.communication.MessengerHandler
import com.kynetics.uf.android.content.UFSharedPreferences
import com.kynetics.uf.android.update.CurrentUpdateState
import com.kynetics.uf.android.update.SystemUpdateType
import com.kynetics.uf.android.update.application.ApkUpdater
import com.kynetics.uf.android.update.system.OtaUpdater
import com.kynetics.uf.ddiclient.HaraClientFactory
import com.kynetics.uf.ddiclient.TargetTokenFoundListener
import org.eclipse.hara.ddiclient.core.api.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

data class ConfigurationHandler(
    private val context: UpdateFactoryService,
    private val sharedPreferences: UFSharedPreferences
) {

    fun getConfigurationFromFile(): UFServiceConfiguration? = configurationFile.newFileConfiguration

    fun getServiceConfigurationFromIntent(intent: Intent): UFServiceConfiguration? {
        Log.i(TAG, "Loading new configuration from intent")
        val serializable = intent.getSerializableExtra(Communication.V1.SERVICE_DATA_KEY)
        val string = intent.getStringExtra(Communication.V1.SERVICE_DATA_KEY)
        return try {
            when {

                serializable is String -> UFServiceConfiguration.fromJson(serializable)

                serializable is UFServiceConfiguration -> serializable

                string != null -> UFServiceConfiguration.fromJson(string)

                else -> null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Deserialization error", e)
            null
        }.apply {
            if(this != null){
                Log.i(TAG, "No configuration found in intent")
            } else {
                Log.i(TAG, "Loaded new configuration from intent")
            }
        }
    }

    fun saveServiceConfigurationToSharedPreferences(
        configuration: UFServiceConfiguration?
    ) {
        if (configuration == null) {
            return
        }
        sharedPreferences.edit().apply {
            if(isTargetTokenReceivedFromServerOld(configuration)){
                remove(sharedPreferencesTargetTokenReceivedFromServer)
            }
            putString(sharedPreferencesControllerIdKey, configuration.controllerId)
            putString(sharedPreferencesTenantKey, configuration.tenant)
            putString(sharedPreferencesServerUrlKey, configuration.url)
            putString(sharedPreferencesGatewayToken, configuration.gatewayToken)
            putString(sharedPreferencesTargetToken, configuration.targetToken)
            putLong(sharedPreferencesRetryDelayKey, configuration.retryDelay)
            putBoolean(sharedPreferencesApiModeKey, configuration.isApiMode())
            putBoolean(sharedPreferencesServiceEnableKey, configuration.isEnable())
            putBoolean(sharedPreferencesIsUpdateFactoryServerType, configuration.isUpdateFactoryServe)
            apply()
        }

        sharedPreferences.putAndCommitObject(sharedPreferencesTargetAttributes, configuration.targetAttributes)
    }

    fun getCurrentConfiguration(): UFServiceConfiguration {
        return with(sharedPreferences){
            UFServiceConfiguration.builder()
                    .withTargetAttributes(getTargetAttributes())
                    .withEnable(getBoolean(sharedPreferencesServiceEnableKey, false))
                    .withApiMode(getBoolean(sharedPreferencesApiModeKey, true))
                    .withControllerId(getString(sharedPreferencesControllerIdKey, ""))
                    .withGatewayToken(getString(sharedPreferencesGatewayToken, ""))
                    .withRetryDelay(getLong(sharedPreferencesRetryDelayKey, 900000))
                    .withTargetToken(getTargetToken())
                    .withTenant(getString(sharedPreferencesTenantKey, ""))
                    .withUrl(getString(sharedPreferencesServerUrlKey, ""))
                    .build()
        }
    }

    private fun getTargetToken():String{
        val targetToken = sharedPreferences.getString(sharedPreferencesTargetToken,"")
        return if(targetToken == null || targetToken == ""){
            sharedPreferences.getString(sharedPreferencesTargetTokenReceivedFromServer, "")!!
        } else {
            targetToken
        }
    }

    private fun isTargetTokenReceivedFromServerOld(newConf: UFServiceConfiguration):Boolean{
        val currentConf = getCurrentConfiguration()
        return currentConf.controllerId != newConf.controllerId
                || currentConf.tenant != newConf.tenant
                || currentConf.url != newConf.url
    }

    fun apiModeIsEnabled() = sharedPreferences.getBoolean(sharedPreferencesApiModeKey, false)

    fun buildServiceFromPreferences(
            deploymentPermitProvider: DeploymentPermitProvider,
            listeners: List<MessageListener>
    ): HaraClient? {
        val serviceConfiguration = getCurrentConfiguration()
        var newService: HaraClient? = null
        if (serviceConfiguration.isEnable()) {
            try {
                newService = serviceConfiguration.toService(deploymentPermitProvider, listeners)
            } catch (e: RuntimeException) {
                newService = null
                MessengerHandler.onConfigurationError(listOf(e.message ?: "Error"))
                MessengerHandler.sendMessage(Communication.V1.Out.ServiceNotification.ID)
                Log.e(TAG, e.message, e)
            }
        }
        return newService
    }

    fun needReboot(oldConf:UFServiceConfiguration?): Boolean {
        val newConf = getCurrentConfiguration()
        return newConf.copy(targetAttributes = emptyMap(), isApiMode = newConf.isApiMode(), isEnable = newConf.isEnable()) !=
                oldConf?.copy(targetAttributes = emptyMap(), isApiMode = oldConf.isApiMode(), isEnable = oldConf.isEnable())
    }

    private fun buildConfigDataProvider(): ConfigDataProvider {
        return object : ConfigDataProvider {
            override fun configData(): Map<String, String> {
                return decorateTargetAttribute()
            }

            override fun isUpdated(): Boolean {
                val md5 = decorateTargetAttribute().toMD5()
                return md5 == sharedPreferences.getString(LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY, "")
            }

            override fun onConfigDataUpdate() {
                val md5 = decorateTargetAttribute().toMD5()
                sharedPreferences.edit().putString(LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY, md5)
                        .apply()
            }
        }
    }

    private fun getTargetTokenListener(): TargetTokenFoundListener {
        return object : TargetTokenFoundListener {
            override fun onFound(targetToken: String){
                Log.d(TAG, "New target token received")
                sharedPreferences.edit()
                        .putString(sharedPreferencesTargetTokenReceivedFromServer, targetToken)
                        .apply()
            }
        }
    }

    private fun getTargetAttributes(): MutableMap<String, String> {
        val targetAttributes: MutableMap<String, String>? = sharedPreferences
                .getObject(
                        sharedPreferencesTargetAttributes,
                        HashMap<String, String>().javaClass
                )
        return targetAttributes ?: HashMap()
    }

    private fun decorateTargetAttribute(): Map<String, String> {
        val targetAttributes = getTargetAttributes()
        targetAttributes[CLIENT_TYPE_TARGET_TOKEN_KEY] = "Android"
        targetAttributes[CLIENT_VERSION_TARGET_ATTRIBUTE_KEY] = BuildConfig.VERSION_NAME // TODO: 4/17/18 refactor
        targetAttributes[CLIENT_VERSION_CODE_ATTRIBUTE_KEY] = BuildConfig.VERSION_CODE.toString()
        val buildDate = Date(Build.TIME)
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.UK)
        targetAttributes[ANDROID_BUILD_DATE_TARGET_ATTRIBUTE_KEY] = dateFormat.format(buildDate)
        targetAttributes[ANDROID_BUILD_TYPE_TARGET_ATTRIBUTE_KEY] = Build.TYPE
        targetAttributes[ANDROID_FINGERPRINT_TARGET_ATTRIBUTE_KEY] = Build.FINGERPRINT
        targetAttributes[ANDROID_KEYS_TARGET_ATTRIBUTE_KEY] = Build.TAGS
        targetAttributes[ANDROID_VERSION_TARGET_ATTRIBUTE_KEY] = Build.VERSION.RELEASE
        targetAttributes[DEVICE_NAME_TARGET_ATTRIBUTE_KEY] = Build.DEVICE
        targetAttributes[SYSTEM_UPDATE_TYPE] = systemUpdateType!!.name
        return targetAttributes
    }

    private fun Map<String, String>.toMD5(): String {
        val content = entries.sortedBy { it.key }.joinToString("-") { "${it.key}_${it.value}" }
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray())
        return bytes.toMD5()
    }

    private fun ByteArray.toMD5(): String {
        return this.joinToString("") { "%02x".format(it) }
    }

    private fun UFServiceConfiguration.toService(
        deploymentPermitProvider: DeploymentPermitProvider,
        listeners: List<MessageListener>
    ): HaraClient {
        return if(isUpdateFactoryServe){
            HaraClientFactory.newUFClient(
                    toClientData(),
                    object : DirectoryForArtifactsProvider {
                        override fun directoryForArtifacts(): File = currentUpdateState.rootDir()
                    },
                    buildConfigDataProvider(),
                    deploymentPermitProvider,
                    listeners,
                    listOf(OtaUpdater(context),ApkUpdater(context)),
                    getTargetTokenListener()
            )
        } else {
            HaraClientFactory.newHawkbitClient(
                    toClientData(),
                    object : DirectoryForArtifactsProvider {
                        override fun directoryForArtifacts(): File = currentUpdateState.rootDir()
                    },
                    buildConfigDataProvider(),
                    deploymentPermitProvider,
                    listeners,
                    listOf(OtaUpdater(context),ApkUpdater(context))
            )
        }
    }

    private fun UFServiceConfiguration.toClientData(): HaraClientData {
        return HaraClientData(
                tenant,
                controllerId,
                url,
                gatewayToken,
                targetToken
        )
    }

    private var systemUpdateType: SystemUpdateType? = SystemUpdateType.getSystemUpdateType()
    private val configurationFile = ConfigurationFileLoader(sharedPreferences, UF_CONF_FILE, context)

    private val sharedPreferencesServerUrlKey = context.getString(R.string.shared_preferences_server_url_key)
    private val sharedPreferencesApiModeKey = context.getString(R.string.shared_preferences_api_mode_key)
    private val sharedPreferencesTenantKey = context.getString(R.string.shared_preferences_tenant_key)
    private val sharedPreferencesControllerIdKey = context.getString(R.string.shared_preferences_controller_id_key)
    private val sharedPreferencesRetryDelayKey = context.getString(R.string.shared_preferences_retry_delay_key)
    private val sharedPreferencesServiceEnableKey = context.getString(R.string.shared_preferences_is_enable_key)
    private val sharedPreferencesGatewayToken = context.getString(R.string.shared_preferences_gateway_token_key)
    private val sharedPreferencesTargetToken = context.getString(R.string.shared_preferences_target_token_key)
    private val sharedPreferencesTargetTokenReceivedFromServer = context.getString(R.string.shared_preferences_target_token_received_from_server_key)
    private val sharedPreferencesTargetAttributes = context.getString(R.string.shared_preferences_args_key)
    private val sharedPreferencesIsUpdateFactoryServerType = context.getString(R.string.shared_preferences_is_update_factory_server_type_key)
    private val currentUpdateState: CurrentUpdateState = CurrentUpdateState(context)

    companion object {
        private const val LAST_TARGET_ATTRIBUTES_MD5_SENT_KEY = "LAST_TARGET_ATTRIBUTES_MD5_SET_KEY"
        private const val CLIENT_VERSION_TARGET_ATTRIBUTE_KEY = "client_version"
        private const val CLIENT_VERSION_CODE_ATTRIBUTE_KEY = "client_version_code"
        private const val ANDROID_BUILD_DATE_TARGET_ATTRIBUTE_KEY = "android_build_date"
        private const val ANDROID_BUILD_TYPE_TARGET_ATTRIBUTE_KEY = "android_build_type"
        private const val ANDROID_FINGERPRINT_TARGET_ATTRIBUTE_KEY = "android_fingerprint"
        private const val ANDROID_KEYS_TARGET_ATTRIBUTE_KEY = "android_keys"
        private const val ANDROID_VERSION_TARGET_ATTRIBUTE_KEY = "android_version"
        private const val DEVICE_NAME_TARGET_ATTRIBUTE_KEY = "device_name"
        private const val SYSTEM_UPDATE_TYPE = "system_update_type"
        private const val CLIENT_TYPE_TARGET_TOKEN_KEY = "client"
        private val EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory().path
        private val UF_CONF_FILE = "$EXTERNAL_STORAGE_DIR/UpdateFactoryConfiguration/ufConf.conf"
        private val TAG: String = ConfigurationHandler::class.java.simpleName
    }
}

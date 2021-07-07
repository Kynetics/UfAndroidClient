/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration

@Serializable
/**
 * This class represent the [com.kynetics.uf.android.UpdateFactoryService]'s configuration
 *
 * @property tenant the tenant
 * @property controllerId id of the controller
 * @property retryDelay time to wait between poll after a connection error
 * @property url of update server
 * @property targetToken target token
 * @property gatewayToken gateway token
 * @property isApiMode true to ask user authorization sending message to client, false to use dialog
 * @property isEnable true to enable the service, false to disable it
 * @property isUpdateFactoryServe true when the server is an UpdateServer, false when the server is an
 *  Hawkbit server
 * @property targetAttributes target's tags
 */
data class UFServiceConfiguration(
        val tenant: String,
        val controllerId: String,
        @Deprecated("As of release 1.0.0-RC repalced by exponential backoff")
        val retryDelay: Long,
        val url: String,
        val targetToken: String,
        val gatewayToken: String,
        private val isApiMode: Boolean,
        private val isEnable: Boolean,
        val isUpdateFactoryServe: Boolean,
        val targetAttributes: Map<String, String>
) : java.io.Serializable {

    private val apiMode: Boolean = false
    private val enable: Boolean  = false

    fun isEnable():Boolean =  enable || isEnable

    fun isApiMode():Boolean = apiMode || isApiMode

    /**
     * Json serialization
     */
    fun toJson(): String {
        return json.encodeToString(serializer(), this)
    }

    /**
     * [UFServiceConfiguration]'s builder class
     *
     * @property tenant the tenant
     * @property controllerId id of the controller
     * @property retryDelay time to wait between poll after a connection error, default value is 900_000
     * @property url of update server
     * @property targetToken target token
     * @property gatewayToken gateway token
     * @property isApiMode true to ask user authorization sending message to client, false to use dialog, default value is true
     * @property isEnable true to enable the service, false to disable it, default value is true
     * @property isUpdateFactoryServe true when the server is an UpdateServer, false when the server is an default value is true
     *  Hawkbit server
     * @property targetAttributes target's tags
     */
    class Builder internal constructor() {

        private var tenant: String? = ""
        private var controllerId: String? = ""
        private var retryDelay: Long = 900000
        private var url: String? = ""
        private var apiMode = true
        private var enable = true
        private var isUpdateFactoryServer = true
        private var targetToken: String? = ""
        private var gatewayToken: String? = ""
        private var targetAttributes: Map<String, String> = mutableMapOf()
        /**
         * Configure the tenant parameter
         */
        fun withTenant(tenant: String?): Builder {
            this.tenant = tenant
            return this
        }

        /**
         * Configure the controller id  parameter
         */
        fun withControllerId(controllerId: String?): Builder {
            this.controllerId = controllerId
            return this
        }

        /**
         * Configure the gateway token  parameter
         *
         * @see withGatewayToken
         */
        @Deprecated("As of release 1.0.0-RC4 replaced by",
                replaceWith = ReplaceWith("withGatewayToken"))
        fun withGetawayToken(gatewayToken: String?): Builder {
            return withGatewayToken(gatewayToken)
        }

        /**
         * Configure the gateway token  parameter
         */
        fun withGatewayToken(gatewayToken: String?): Builder {
            this.gatewayToken = gatewayToken
            return this
        }

        /**
         * Configure the target token parameter
         */
        fun withTargetToken(targetToken: String?): Builder {
            this.targetToken = targetToken
            return this
        }

        /**
         * Configure the retryDelay parameter
         */
        @Deprecated("As of release 1.0.0-RC removed")
        fun withRetryDelay(retryDelay: Long): Builder {
            this.retryDelay = retryDelay
            return this
        }

        /**
         * Configure the url parameter
         */
        fun withUrl(url: String?): Builder {
            this.url = url
            return this
        }

        /**
         * Configure the api mode parameter
         */
        fun withApiMode(apiMode: Boolean): Builder {
            this.apiMode = apiMode
            return this
        }

        /**
         * Configure the enable parameter
         * @see withEnable
         */
        @Deprecated("As of release 0.3.4 replaced by withEnable(boolean)",
                replaceWith = ReplaceWith(""))
        fun witEnable(enable: Boolean): Builder {
            return withEnable(enable)
        }

        /**
         * Configure the target attributes parameter
         *
         * @see withTargetAttributes
         */
        @Deprecated("As of release 0.3.4 replaced by withTargetAttributes(Map<String,String>",
                replaceWith = ReplaceWith(""))
        fun witArgs(args: Map<String, String>): Builder {
            return withTargetAttributes(args)
        }

        /**
         * Configure the enable parameter
         */
        fun withEnable(enable: Boolean): Builder {
            this.enable = enable
            return this
        }

        /**
         * Configure the target attributes parameter
         */
        fun withTargetAttributes(targetAttribute: Map<String, String>?): Builder {
            if (targetAttribute != null && targetAttribute.size > 0) {
                this.targetAttributes = targetAttribute
            }
            return this
        }

        /**
         * Configure the UpdateFactoryServer parameter
         */
        fun withIsUpdateFactoryServer(isUpdateFactoryServer: Boolean): Builder {
            this.isUpdateFactoryServer = isUpdateFactoryServer
            return this
        }

        /**
         * Build an instance of UFServiceConfigure with the configured parameters
         *
         * @throws IllegalStateException when the retryDelay parameter is lower then 0
         *
         */
        fun build(): UFServiceConfiguration {
            if (retryDelay < 0) {
                throw IllegalStateException("retryDelay must be grater than 0")
            }
            return UFServiceConfiguration(tenant ?: "", controllerId ?: "", retryDelay, url ?: "",
                    targetToken ?: "",
                    gatewayToken ?: "",
                    apiMode, enable, isUpdateFactoryServer,
                    targetAttributes)
        }

        /**
         * Validate the [UFServiceConfiguration] that is built using the [build] method
         */
        fun configurationIsValid(): Boolean {
            return (notEmptyString(tenant) &&
                    notEmptyString(controllerId) &&
                    notEmptyString(url) &&
                    retryDelay > 0)
        }

        private fun notEmptyString(stringToTest: String?): Boolean {
            return stringToTest != null && !stringToTest.isEmpty()
        }
    }

    companion object {
        private val json = Json
        private const val serialVersionUID = -6025361892414738765L

        /**
         * Instantiate a builder
         */
        @JvmStatic
        fun builder(): Builder {
            return Builder()
        }

        /**
         * Deserializes given json [data] into a corresponding object of type [UFServiceConfiguration].
         * @throws [JsonException] in case of malformed json
         * @throws [SerializationException] if given input can not be deserialized
         */
        @JvmStatic
        fun fromJson(data: String): UFServiceConfiguration {
            return json.decodeFromString(serializer(), data)
        }
    }

    /**
     *  get target's tags
     *
     *  @see targetAttributes
     */
    @Deprecated("As of release 0.3.4 replaced by targetAttributes",
            replaceWith = ReplaceWith(""))
    fun getArgs(): Map<String, String> {
        return this.targetAttributes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UFServiceConfiguration

        if (tenant != other.tenant) return false
        if (controllerId != other.controllerId) return false
        if (retryDelay != other.retryDelay) return false
        if (url != other.url) return false
        if (targetToken != other.targetToken) return false
        if (gatewayToken != other.gatewayToken) return false
        if (isApiMode() != other.isApiMode()) return false
        if (isEnable() != other.isEnable()) return false
        if (isUpdateFactoryServe != other.isUpdateFactoryServe) return false
        if (targetAttributes != other.targetAttributes) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tenant.hashCode()
        result = 31 * result + controllerId.hashCode()
        result = 31 * result + retryDelay.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + targetToken.hashCode()
        result = 31 * result + gatewayToken.hashCode()
        result = 31 * result + isApiMode().hashCode()
        result = 31 * result + isEnable().hashCode()
        result = 31 * result + isUpdateFactoryServe.hashCode()
        result = 31 * result + targetAttributes.hashCode()
        return result
    }
}

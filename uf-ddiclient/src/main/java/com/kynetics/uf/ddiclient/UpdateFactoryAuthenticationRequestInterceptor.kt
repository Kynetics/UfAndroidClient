package com.kynetics.uf.ddiclient

import org.eclipse.hara.ddiapiclient.api.DdiRestConstants.Companion.CONFIG_DATA_ACTION
import org.eclipse.hara.ddiapiclient.api.DdiRestConstants.Companion.TARGET_TOKEN_HEADER_NAME
import org.eclipse.hara.ddiapiclient.api.DdiRestConstants.Companion.TARGET_TOKEN_REQUEST_HEADER_NAME
import org.eclipse.hara.ddiapiclient.security.Authentication.AuthenticationType.TARGET_TOKEN_AUTHENTICATION
import org.eclipse.hara.ddiapiclient.security.Authentication.Companion.newInstance
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import org.eclipse.hara.ddiapiclient.security.Authentication

/**
 * @author Daniele Sergio
 */
class UpdateFactoryAuthenticationRequestInterceptor(
        private val authentications: MutableSet<Authentication>,
        private val targetTokenFoundListener: TargetTokenFoundListener =
                object : TargetTokenFoundListener {}
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()
        val isConfigDataRequest = originalRequest.url.toString().endsWith(CONFIG_DATA_ACTION)
        var targetTokenAuth: Authentication? = null
        for (authentication in authentications) {
            builder.addHeader(authentication.header, authentication.headerValue)
            if (authentication.type === TARGET_TOKEN_AUTHENTICATION) {
                targetTokenAuth = authentication
            }
        }
        if (isConfigDataRequest) {
            builder.header(TARGET_TOKEN_REQUEST_HEADER_NAME, true.toString())
        }
        val response = chain.proceed(builder.build())
        val targetToken = response.header(TARGET_TOKEN_HEADER_NAME)
        if (isConfigDataRequest && targetToken != null) {
            authentications.add(newInstance(TARGET_TOKEN_AUTHENTICATION, targetToken))
            targetTokenFoundListener.onFound(targetToken)
            authentications.remove(targetTokenAuth)
        }
        return response
    }
}

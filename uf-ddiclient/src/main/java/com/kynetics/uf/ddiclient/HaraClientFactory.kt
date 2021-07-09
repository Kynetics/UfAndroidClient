package com.kynetics.uf.ddiclient

import okhttp3.OkHttpClient
import org.eclipse.hara.ddiapiclient.security.Authentication
import org.eclipse.hara.ddiclient.core.HaraClientDefaultImpl
import org.eclipse.hara.ddiclient.core.api.*

object HaraClientFactory {

     private fun newInstance(
             haraClientData: HaraClientData,
             directoryForArtifactsProvider: DirectoryForArtifactsProvider,
             configDataProvider: ConfigDataProvider,
             deploymentPermitProvider: DeploymentPermitProvider,
             messageListeners: List<MessageListener>,
             updaters: List<Updater>,
             builder:OkHttpClient.Builder
     ): HaraClient  = HaraClientDefaultImpl().apply{
          init(haraClientData,
                  directoryForArtifactsProvider,
                  configDataProvider,
                  deploymentPermitProvider,
                  messageListeners,
                  updaters,
                  builder)
     }

     fun newUFClient(
             haraClientData: HaraClientData,
             directoryForArtifactsProvider: DirectoryForArtifactsProvider,
             configDataProvider: ConfigDataProvider,
             deploymentPermitProvider: DeploymentPermitProvider,
             messageListeners: List<MessageListener>,
             updaters: List<Updater>,
             targetTokenFoundListener:TargetTokenFoundListener):HaraClient =
             newInstance(haraClientData,
                     directoryForArtifactsProvider,
                     configDataProvider,
                     deploymentPermitProvider,
                     messageListeners,
                     updaters,
                     getOkHttpBuilder(targetTokenFoundListener, haraClientData))

     fun newHawkbitClient(
             haraClientData: HaraClientData,
             directoryForArtifactsProvider: DirectoryForArtifactsProvider,
             configDataProvider: ConfigDataProvider,
             deploymentPermitProvider: DeploymentPermitProvider,
             messageListeners: List<MessageListener>,
             updaters: List<Updater>):HaraClient =
             newInstance(haraClientData,
                     directoryForArtifactsProvider,
                     configDataProvider,
                     deploymentPermitProvider,
                     messageListeners,
                     updaters,
                     OkHttpClient.Builder())


     private fun getOkHttpBuilder(listener:TargetTokenFoundListener,clientData:HaraClientData): OkHttpClient.Builder{
          val authentications = HashSet<Authentication>()
          with(clientData) {
               if (gatewayToken != null) {
                    authentications.add(Authentication.newInstance(Authentication.AuthenticationType.GATEWAY_TOKEN_AUTHENTICATION, gatewayToken!!))
               }
               if (targetToken != null) {
                    authentications.add(Authentication.newInstance(Authentication.AuthenticationType.TARGET_TOKEN_AUTHENTICATION, targetToken!!))
               }
          }
          val authentication=UpdateFactoryAuthenticationRequestInterceptor(authentications,listener)
          val builder=OkHttpClient.Builder()
          builder.interceptors().add(authentication)
          return builder
     }

}

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

package com.kynetics.uf.android.update

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.os.Environment
import android.os.SystemProperties
import android.util.Log
import com.kynetics.updatefactory.ddiclient.core.api.Updater
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import kotlin.math.min

class CurrentUpdateState(context: Context) {

    private val sharedPreferences: SharedPreferences

    val distributionReportError: Set<String>
        get() = sharedPreferences.getStringSet(APK_DISTRIBUTION_REPORT_ERROR_KEY, HashSet())!!

    val distributionReportSuccess: Set<String>
        get() = sharedPreferences.getStringSet(APK_DISTRIBUTION_REPORT_SUCCESS_KEY, HashSet())!!

    fun addErrorToRepor(vararg errors:String){
        val newDistReportError = distributionReportError.toMutableSet()
        newDistReportError.addAll(errors)
        sharedPreferences.edit().putStringSet(APK_DISTRIBUTION_REPORT_ERROR_KEY, newDistReportError).apply()
    }

    fun addSuccessMessageToRepor(vararg messages:String){
        val newDistReportSuccess = distributionReportSuccess.toMutableSet()
        newDistReportSuccess.addAll(messages)
        sharedPreferences.edit().putStringSet(APK_DISTRIBUTION_REPORT_SUCCESS_KEY, newDistReportSuccess).apply()
    }

    init {
        this.sharedPreferences = context.getSharedPreferences(SHARED_PREFERENCES_FILE_NAME, MODE_PRIVATE)
    }


    fun rootDir():File = File(Environment.getDownloadCacheDirectory(), "update_factory")

    private fun currentInstallationDir():File = File(rootDir(), "current_installation")

//    private fun previousInstallationDir():File = File(rootDir(), "last_installation")

    fun lastIntallationResult(artifact: Updater.SwModuleWithPath.Artifact):InstallationResult{
        return try {
            val result = lastInstallFile().readLines()[1].trim()
            val response = when (result) {
                "1" -> InstallationResult()
                else -> InstallationResult(listOf("last_install result code: $result"))
            }

//            persistArtifactInstallationResult(artifact, response)
            response
        } catch (e:Throwable){
            Log.e(TAG, e.message, e)
            when(e){
                is FileNotFoundException -> {
                    InstallationResult(listOf("File $LAST_INSTALL_FILE_NAME not found"))
                }
                else -> InstallationResult(listOf("Installation fails with exception: ${e.message}"))
            }
        }
    }

    fun lastInstallFile():File{
        return File(RECOVERY_CACHE, LAST_INSTALL_FILE_NAME)
    }

    fun lastLogFile():File{
        return File(RECOVERY_CACHE, LAST_LOG_FILE_NAME)
    }

    fun isFeebackReliable():Boolean{
        return lastInstallFile().canWrite() && lastLogFile().canRead()
    }

    fun startUpdate(){
        sharedPreferences.edit()
                .putBoolean(UPDATE_IS_STARTED_KEY, true)
                .apply()
    }

    fun isUpdateStart():Boolean{
        return sharedPreferences.getBoolean(UPDATE_IS_STARTED_KEY, false)
    }

    fun addPendingOTAInstallation(artifact: Updater.SwModuleWithPath.Artifact){
        val file = File(CACHE_UF, artifact.filename)
        if(!file.exists()){
            file.parentFile.mkdirs()
            file.createNewFile()
        }
        val lastInstallFile = lastInstallFile()
        if(lastInstallFile.exists() && !lastInstallFile.delete()){
            Log.w(TAG, "cant delete ${lastInstallFile.name}")
        }

    }

    fun getOtaInstallationState(artifact: Updater.SwModuleWithPath.Artifact):InstallationState{
        val pendingInstallationFile = File(CACHE_UF, artifact.filename)
        return when {
            pendingInstallationFile.exists() -> InstallationState.PENDING
            File("${pendingInstallationFile.absolutePath}.$SUCCESS_EXTENSION").exists() -> InstallationState.SUCCESS
            File("${pendingInstallationFile.absolutePath}.$ERROR_EXTENSION").exists() -> InstallationState.ERROR
            else -> InstallationState.NONE
        }
    }



    fun isABInstallationPending(artifact: Updater.SwModuleWithPath.Artifact):Boolean{
        return sharedPreferences.getString(PENDING_AB_SHAREDPREFERENCES_KEY, "") == artifact.hashes.md5
    }

    fun addPendingABInstallation(artifact: Updater.SwModuleWithPath.Artifact){
        sharedPreferences.edit().putString(PENDING_AB_SHAREDPREFERENCES_KEY, artifact.hashes.md5).apply()
    }

    enum class InstallationState{
        PENDING, NONE, SUCCESS, ERROR
    }


    data class InstallationResult(val errors:List<String> = emptyList()){
        val success = errors.isEmpty()
    }

    fun isPackageInstallationTerminated(packageName: String?, versionCode: Long?): Boolean {
        val key = String.format(APK_PACKAGE_TEMPLATE_KEY, getPackageKey(packageName))
        val version = getVersion(versionCode)
        return sharedPreferences.getLong(key, version + 1) <= version
    }

    private fun getPackageKey(packageName: String?): String {
        return packageName?.replace(".".toRegex(), "_") ?: "NULL"
    }

    private fun getVersion(versionCode: Long?): Long {
        return versionCode ?: 0
    }

    fun packageInstallationTerminated(packageName: String?, versionCode: Long?) {
        val key = String.format(APK_PACKAGE_TEMPLATE_KEY, getPackageKey(packageName))
        sharedPreferences.edit()
                .putLong(key, getVersion(versionCode))
                .apply()
    }

    fun clearState() {
        currentInstallationDir().deleteRecursively()
        CACHE_UF.deleteRecursively()
        val editor = sharedPreferences.edit()

        for (key in sharedPreferences.all.keys) {
            if (key.startsWith(APK_PACKAGE_START_KEY)) {
                editor.remove(key)
            }
        }

        editor
                .remove(APK_DISTRIBUTION_REPORT_SUCCESS_KEY)
                .remove(APK_DISTRIBUTION_REPORT_ERROR_KEY)
                .remove(LAST_SLOT_NAME_SHAREDPREFERENCES_KEY)
                .remove(PENDING_AB_SHAREDPREFERENCES_KEY)
                .remove(UPDATE_IS_STARTED_KEY)
                .apply()
    }

    fun saveSlotName(){
        val partionSlotName = SystemProperties.get(LAST_LOST_NAME_PROPERTY_KEY)
        Log.d(TAG, "Using slot named: $partionSlotName")
        sharedPreferences.edit()
                .putString(LAST_SLOT_NAME_SHAREDPREFERENCES_KEY, partionSlotName)
                .apply()
    }

    //todo refactor use pending file to store last installation  slot name
    fun lastABIntallationResult(artifact: Updater.SwModuleWithPath.Artifact):InstallationResult{
        return try {
            val currentSlotName = SystemProperties.get(LAST_LOST_NAME_PROPERTY_KEY)
            val previousSlotName =  sharedPreferences.getString(LAST_SLOT_NAME_SHAREDPREFERENCES_KEY, "")
            Log.d(TAG, "(current slot named, previous slot name) ($currentSlotName,$previousSlotName)")
            val success = previousSlotName !=  currentSlotName
            val response = if(success){InstallationResult()} else { InstallationResult(listOf("System reboot on the same partition"))}
            response
        } catch (e:Throwable){
            Log.e(TAG, e.message, e)
            InstallationResult(listOf("Installation fails with exception: ${e.message}"))
        }
    }

    fun parseLastLogFile():List<String>{
        return try{
            val lastLogFile = File(RECOVERY_CACHE, LAST_LOG_FILE_NAME)
            lastLogFile.readLines().map { it.substring(0, min(it.length, 512)) }
        } catch (e:Throwable){
            Log.w(TAG, "cant part $LAST_LOG_FILE_NAME", e)
            listOf("Can't read $LAST_LOG_FILE_NAME, the notifications messageToSendOnSync could be unreliable")
        }
    }

    companion object {
        private const val UPDATE_IS_STARTED_KEY = "UPDATE_IS_STARTED"
        private const val LAST_LOST_NAME_PROPERTY_KEY = "ro.boot.slot_suffix"
        private const val LAST_SLOT_NAME_SHAREDPREFERENCES_KEY = "slot_suffix"
        private const val PENDING_AB_SHAREDPREFERENCES_KEY = "PENDING_AB_OTA_KEY"
        private const val TAG = "CurrentUpdateState"
        private val SHARED_PREFERENCES_FILE_NAME = "CURRENT_UPDATE_STATE"
        private val APK_DISTRIBUTION_REPORT_SUCCESS_KEY = "APK_DISTRIBUTION_REPORT_SUCCESS"
        private val APK_DISTRIBUTION_REPORT_ERROR_KEY = "APK_DISTRIBUTION_REPORT_ERROR"
        private const val SUCCESS_EXTENSION = "OK"
        private const val ERROR_EXTENSION = "KO"
        private const val APK_PACKAGE_START_KEY = "APK_PACKAGE"
        private const val APK_PACKAGE_TEMPLATE_KEY = "APK_PACKAGE_%s_KEY"
        private val CACHE = File("cache")
        private val CACHE_UF = File(CACHE,"update_factory")
        private val RECOVERY_CACHE = File(CACHE,"recovery")
        const val LAST_LOG_FILE_NAME = "last_log"
        private const val LAST_INSTALL_FILE_NAME = "last_install"
    }

}

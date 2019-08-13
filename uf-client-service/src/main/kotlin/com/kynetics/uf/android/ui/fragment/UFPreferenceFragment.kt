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

package com.kynetics.uf.android.ui.fragment

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.preference.EditTextPreference
import android.support.v7.preference.ListPreference
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceFragmentCompat
import android.support.v7.preference.PreferenceGroup
import android.support.v7.preference.SwitchPreferenceCompat
import android.telecom.Log
import android.widget.Toast
import com.kynetics.uf.android.R
import com.kynetics.uf.android.UpdateFactoryService
import com.kynetics.uf.android.api.ApiCommunicationVersion
import com.kynetics.uf.android.api.v1.UFServiceMessageV1
import com.kynetics.uf.android.apicomptibility.ApiVersion
import com.kynetics.uf.android.communication.MessengerHandler

/**
 * A simple [PreferenceFragmentCompat] subclass.
 */
class UFPreferenceFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var notEmptyEditTextListener = Preference.OnPreferenceChangeListener { _, newValue ->
        if (newValue.toString().trim { it <= ' ' } == "") {
            Toast.makeText(activity, "Filed can't be empty",
                    Toast.LENGTH_LONG).show()
            false
        } else {
            true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle, rootKey: String) {
        preferenceManager.sharedPreferencesName = getString(R.string.shared_preferences_file)
        setPreferencesFromResource(R.xml.pref_general, rootKey)
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        val editTextKey = arrayOf(getString(R.string.shared_preferences_controller_id_key), getString(R.string.shared_preferences_tenant_key), getString(R.string.shared_preferences_server_url_key))

        for (key in editTextKey) {
            val editTextPreference = findPreference(key) as EditTextPreference
            editTextPreference.onPreferenceChangeListener = notEmptyEditTextListener
        }
    }

    override fun onStart() {
        super.onStart()
        val myIntent = Intent(context, UpdateFactoryService::class.java)
        ApiVersion.fromVersionCode().startService(context, myIntent)
    }

    override fun onResume() {
        super.onResume()
        val sharedPrefs = preferenceManager.sharedPreferences

        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            if (preference is PreferenceGroup) {
                for (j in 0 until preference.preferenceCount) {
                    val singlePref = preference.getPreference(j)
                    updatePreference(singlePref, singlePref.key, sharedPrefs)
                }
            } else {
                updatePreference(preference, preference.key, sharedPrefs)
            }
        }
        enableDisableActivePreference(sharedPrefs)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (!isAdded) {
            return
        }
        val sharedPrefs = preferenceManager.sharedPreferences
        val preference = findPreference(key)
        updatePreference(preference, key, sharedPrefs)
        enableDisableActivePreference(sharedPrefs)

        if (key == getString(R.string.shared_preferences_is_enable_key) && !(preference as SwitchPreferenceCompat).isChecked) {
            AlertDialog.Builder(context!!)
                    .setTitle(resources.getString(R.string.stop_service_dialog_title))
                    .setCancelable(false)
                    .setMessage(
                            resources.getString(R.string.stop_service_dialog_message))
                    .setPositiveButton(
                            resources.getString(android.R.string.ok)
                    ) { _, _ -> }
                    .setNegativeButton(
                            resources.getString(android.R.string.cancel)
                    ) { _, _ -> preference.isChecked = true }.show()
        }
    }

    private fun enableDisableActivePreference(shp: SharedPreferences) {
        val activePreference = findPreference(getString(R.string.shared_preferences_is_enable_key))
        activePreference.isEnabled =
                !(shp.getString(getString(R.string.shared_preferences_server_url_key), "").isEmpty() ||
                shp.getString(getString(R.string.shared_preferences_tenant_key), "").isEmpty() ||
                shp.getString(getString(R.string.shared_preferences_controller_id_key), "").isEmpty())
    }

    private fun updatePreference(preference: Preference?, key: String, sharedPrefs: SharedPreferences) {
        if (preference == null || preference is SwitchPreferenceCompat) {
            return
        }

        if (preference is ListPreference) {
            val listPreference = preference as ListPreference?
            listPreference!!.summary = listPreference.entry
            return
        }

        if (preference is EditTextPreference) {
            val editTextPreference = preference as EditTextPreference?
            editTextPreference!!.summary = editTextPreference.text
        }

        if (key == getString(R.string.shared_preferences_current_state_key)) {
            try {
                val messageV1 = UFServiceMessageV1.fromJson(MessengerHandler.getlastSharedMessage(ApiCommunicationVersion.V1).messageToSendOnSync as String)
                preference.summary = messageV1.name.name
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Error setting current state", error)
            }

            return
        }

        if (key == getString(R.string.shared_preferences_system_update_type_key)) {
            preference.summary = sharedPrefs.getString(getString(R.string.shared_preferences_system_update_type_key), "")
        }

        if (key == getString(R.string.shared_preferences_retry_delay_key)) {
            preference.summary = sharedPrefs.getLong(key, 12000).toString()
            return
        }
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences.edit().apply()
    }

    override fun onDetach() {
        super.onDetach()
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    companion object {

        private val TAG = UFPreferenceFragment::class.java.simpleName

        fun newInstance(): UFPreferenceFragment {
            return UFPreferenceFragment()
        }
    }
}
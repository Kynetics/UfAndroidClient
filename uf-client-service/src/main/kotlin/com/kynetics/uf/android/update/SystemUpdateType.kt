/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.android.update

import android.content.Context
import android.os.Build
import android.os.SystemProperties
import androidx.annotation.RequiresApi
import com.kynetics.uf.android.R
import com.kynetics.uf.android.update.system.ABOtaInstaller
import com.kynetics.uf.android.update.system.OtaInstaller
import com.kynetics.uf.android.update.system.SingleCopyOtaInstaller

enum class SystemUpdateType(val readableName: String) {

    SINGLE_COPY("Single Copy") {
        override fun getInstaller(context: Context): OtaInstaller {
            saveToSharedPreferences(context)
            return SingleCopyOtaInstaller
        }
    }, AB("A/B") {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun getInstaller(context: Context): OtaInstaller {
            saveToSharedPreferences(context)
            return ABOtaInstaller
        }
    };

    abstract fun getInstaller(context: Context): OtaInstaller

    protected fun saveToSharedPreferences(context: Context) {
        val sh = context.getSharedPreferences(context.getString(R.string.shared_preferences_file), Context.MODE_PRIVATE)
        sh.edit().putString(context.getString(R.string.shared_preferences_system_update_type_key), readableName)
                .apply()
    }

    companion object {
        private const val AB_UPDATE_ENABLE_PROP_NAME = "ro.build.ab_update"

        @JvmStatic
        fun getSystemUpdateType(): SystemUpdateType {
            val prop = SystemProperties.get(AB_UPDATE_ENABLE_PROP_NAME)
            return if ("false".equals(prop, true) || prop.isEmpty()) {
                SINGLE_COPY
            } else {
                AB
            }
        }
    }
}

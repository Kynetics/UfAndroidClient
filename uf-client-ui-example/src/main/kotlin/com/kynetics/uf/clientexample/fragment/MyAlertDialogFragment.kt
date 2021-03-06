/*
 * Copyright © 2017-2020  Kynetics  LLC
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package com.kynetics.uf.clientexample.fragment

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import com.kynetics.uf.clientexample.activity.MainActivity

class MyAlertDialogFragment : androidx.fragment.app.DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogType = arguments!!.getString(ARG_DIALOG_TYPE)
        val titleResource = resources.getIdentifier(String.format("%s_%s", dialogType.toLowerCase(), "title"),
            "string", activity!!.packageName)
        val contentResource = resources.getIdentifier(String.format("%s_%s", dialogType.toLowerCase(), "content"),
            "string", activity!!.packageName)

        return AlertDialog.Builder(activity!!)
            // .setIcon(R.drawable.alert_dialog_icon)
            .setTitle(titleResource)
            .setMessage(contentResource)
            .setPositiveButton(android.R.string.ok
            ) { dialog, whichButton -> (activity as MainActivity).sendPermissionResponse(true) }
            .setNegativeButton(android.R.string.cancel
            ) { dialog, whichButton -> (activity as MainActivity).sendPermissionResponse(false) }
            .create()
    }

    companion object {
        private val ARG_DIALOG_TYPE = "DIALOG_TYPE"
        fun newInstance(dialogType: String): MyAlertDialogFragment {
            val frag = MyAlertDialogFragment()
            val args = Bundle()
            args.putString(ARG_DIALOG_TYPE, dialogType)
            frag.arguments = args
            frag.isCancelable = false
            return frag
        }
    }
}

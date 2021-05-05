/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.utils.extensions

import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StyleRes
import ch.protonmail.android.BuildConfig
import ch.protonmail.android.views.contactsList.ContactGroupEmailAvatarView

/*
 * Extensions for Android's View's
 *
 * Author: Davide Farella
 */

/** Execute the [listener] on [TextWatcher.onTextChanged] */
inline fun EditText.onTextChange(crossinline listener: (CharSequence) -> Unit): TextWatcher {
    val watcher = object : TextWatcher {
        override fun afterTextChanged(editable: Editable) {
            /* Do nothing */
        }

        override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) {
            /* Do nothing */
        }

        override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
            listener(text)
        }
    }
    addTextChangedListener(watcher)
    return watcher
}

fun TextView.setStyle(@StyleRes styleId: Int) {
    setTextAppearance(styleId)
}

/**
 * @return `true` if we're in Debug configuration and [View.isInEditMode]
 */
fun View.isInPreviewMode() = BuildConfig.DEBUG && isInEditMode

fun ContactGroupEmailAvatarView.setAccountLetters(displayName: String) {
    when (displayName.length) {
        0 -> setLetters("")
        1 -> setLetters(displayName)
        else -> {
            val nameCompounds = displayName.split(" ")
            if (nameCompounds.size == 1) {
                setLetters(displayName.substring(0, 1))
            } else {
                val firstCompound = if (!TextUtils.isEmpty(nameCompounds[0])) nameCompounds[0].substring(0, 1) else ""
                val secondCompound = if (!TextUtils.isEmpty(nameCompounds[1])) nameCompounds[1].substring(0, 1) else ""
                setLetters("$firstCompound$secondCompound")
            }
        }
    }
}

fun TextView.setNotificationIndicatorSize(notificationCount: Int) {
    textSize = when {
        notificationCount > 1000 -> 10f
        notificationCount > 100 -> 11f
        notificationCount > 10 -> 12f
        else -> 13f
    }
}

// SPDX-License-Identifier: MPL-2.0
package info.cemu.cemu.emulation

import androidx.annotation.Keep
import org.calamansi.cemu.CemuActivity

// Native AndroidSwkbdCallbacks reflects this original class and static signatures.
@Keep
object EmulationActivity {
    @JvmStatic
    fun showEmulationTextInput(initialText: String?, maxLength: Int) {
        CemuActivity.current.get()?.showKeyboard(initialText, maxLength)
    }

    @JvmStatic
    fun hideEmulationTextInput() {
        CemuActivity.current.get()?.hideKeyboard()
    }
}

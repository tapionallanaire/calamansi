// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import androidx.core.content.FileProvider

/** Separate manifest component/authority from the host's file provider. */
class DolphinFileProvider : FileProvider()

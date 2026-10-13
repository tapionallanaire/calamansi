// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import android.content.Context
import org.dolphinemu.dolphinemu.features.input.model.ControllerInterface
import org.dolphinemu.dolphinemu.features.input.model.MappingCommon
import org.dolphinemu.dolphinemu.features.input.model.controlleremu.ControlGroup
import org.dolphinemu.dolphinemu.features.input.model.controlleremu.ControlGroupContainer
import org.dolphinemu.dolphinemu.features.input.model.controlleremu.EmulatedController

/** Only fills empty bindings on an unconfigured pad; native settings UI owns later remapping. */
internal object DolphinControllerDefaults {
    fun install(context: Context) {
        val preferences = DolphinRuntime.preferences(context)
        if (preferences.getBoolean("physicalDefaultsInstalled", false)) return
        val device = ControllerInterface.getAllDeviceStrings().firstOrNull { name ->
            name.startsWith("Android/") &&
                ControllerInterface.getDevice(name)?.getInputs()?.any {
                    it.getName() == "Button A"
                } ==
                true
        } ?: return
        val inputs = requireNotNull(ControllerInterface.getDevice(device)).getInputs().map {
            it.getName()
        }.toSet()
        val gc = EmulatedController.getGcPad(0)
        val wii = EmulatedController.getWiimote(0)
        fun bind(
            owner: EmulatedController,
            groups: ControlGroupContainer,
            groupIndex: Int,
            controls: List<String>
        ) {
            val group = groups.getGroup(groupIndex)
            controls.take(group.getControlCount()).forEachIndexed { index, requested ->
                val name = if (requested in inputs) {
                    requested
                } else {
                    when (requested) {
                        "Button L2" -> "Axis17+"
                        "Button R2" -> "Axis18+"
                        else -> requested
                    }
                }
                if (name !in inputs) return@forEachIndexed
                val reference = group.getControl(index).getControlReference()
                if (reference.getExpression().isNotEmpty()) return@forEachIndexed
                val expression = MappingCommon.getExpressionForControl(
                    name,
                    device,
                    owner.getDefaultDevice()
                )
                check(reference.setExpression(expression) == null) {
                    "Invalid native controller expression: $expression"
                }
                owner.updateSingleControlReference(reference)
            }
        }
        val stick = listOf("Axis1-", "Axis1+", "Axis0-", "Axis0+")
        val dpad = listOf(
            "Up" to "Axis16-",
            "Down" to "Axis16+",
            "Left" to "Axis15-",
            "Right" to "Axis15+"
        )
            .map { (key, axis) -> if (key in inputs) key else axis }
        fun unconfigured(controller: EmulatedController): Boolean {
            val default = controller.getDefaultDevice()
            val buttons = controller.getGroup(0)
            return (default.isEmpty() || default.endsWith("/Device Sensors")) &&
                (0 until buttons.getControlCount()).all {
                    buttons.getControl(it).getControlReference().getExpression().isEmpty()
                }
        }
        if (unconfigured(gc)) {
            gc.setDefaultDevice(device)
            bind(
                gc,
                gc,
                0,
                listOf("Button A", "Button B", "Button X", "Button Y", "Button R1", "Start")
            )
            bind(gc, gc, 1, stick)
            bind(gc, gc, 2, listOf("Axis14-", "Axis14+", "Axis11-", "Axis11+"))
            bind(gc, gc, 3, listOf("Button L2", "Button R2", "Axis17+", "Axis18+"))
            bind(gc, gc, 4, dpad)
        }
        if (unconfigured(wii)) {
            wii.setDefaultDevice(device)
            bind(
                wii,
                wii,
                0,
                listOf("Button A", "Button B", "Button X", "Button Y", "Select", "Start", "Mode")
            )
            bind(wii, wii, 1, dpad)
            bind(wii, wii, 2, listOf("Axis14-", "Axis14+", "Axis11-", "Axis11+"))
            for (index in 0 until wii.getGroupCount()) {
                val group = wii.getGroup(index)
                if (group.getGroupType() ==
                    ControlGroup.TYPE_ATTACHMENTS
                ) {
                    group.getAttachmentSetting().setIntValue(1)
                }
            }
            val nunchuk = EmulatedController.getWiimoteAttachment(0, 1)
            bind(wii, nunchuk, 0, listOf("Button L1", "Button L2"))
            bind(wii, nunchuk, 1, stick)
        }
        MappingCommon.save()
        preferences.edit().putBoolean("physicalDefaultsInstalled", true).apply()
    }
}

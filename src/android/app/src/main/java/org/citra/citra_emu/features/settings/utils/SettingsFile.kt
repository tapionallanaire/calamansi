// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.settings.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.util.TreeMap
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.FloatSetting
import org.citra.citra_emu.features.settings.model.IntListSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.ScaledFloatSetting
import org.citra.citra_emu.features.settings.model.SettingSection
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.model.Settings.SettingsSectionMap
import org.citra.citra_emu.features.settings.model.StringSetting
import org.citra.citra_emu.features.settings.ui.SettingsActivityView
import org.citra.citra_emu.utils.BiMap
import org.citra.citra_emu.utils.DirectoryInitialization.userDirectory
import org.citra.citra_emu.utils.Log
import org.ini4j.Wini

/**
 * Contains static methods for interacting with .ini files in which settings are stored.
 */
object SettingsFile {
    const val FILE_NAME_CONFIG = "config"

    private var sectionsMap = BiMap<String?, String?>()

    /**
     * Reads a given .ini file from disk and returns it as a HashMap of Settings, themselves
     * effectively a HashMap of key/value settings. If unsuccessful, outputs an error telling why it
     * failed.
     *
     * @param ini          The ini file to load the settings from
     * @param isCustomGame
     * @param view         The current view.
     * @return An Observable that emits a HashMap of the file's contents, then completes.
     */
    fun readFile(
        ini: DocumentFile,
        isCustomGame: Boolean,
        view: SettingsActivityView?
    ): HashMap<String, SettingSection?> {
        val sections: HashMap<String, SettingSection?> = SettingsSectionMap()
        var reader: BufferedReader? = null
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            reader = BufferedReader(InputStreamReader(inputStream))
            var current: SettingSection? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("[") && line!!.endsWith("]")) {
                    current = sectionFromLine(line!!, isCustomGame)
                    sections[current.name] = current
                } else if (current != null) {
                    if (isCustomGame && (current.name == Settings.SECTION_CONTROLS ||
                            current.name == Settings.SECTION_NETWORK)
                    ) {
                        continue
                    }
                    val setting = settingFromLine(line!!)
                    if (setting != null) {
                        current.putSetting(setting)
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            Log.error("[SettingsFile] File not found: " + ini.uri + e.message)
            view?.onSettingsFileNotFound()
        } catch (e: IOException) {
            Log.error("[SettingsFile] Error reading from: " + ini.uri + e.message)
            view?.onSettingsFileNotFound()
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    Log.error("[SettingsFile] Error closing: " + ini.uri + e.message)
                }
            }
        }
        return sections
    }

    fun readFile(fileName: String, view: SettingsActivityView?): HashMap<String, SettingSection?> =
        readFile(getSettingsFile(fileName), false, view)

    fun readFile(fileName: String): HashMap<String, SettingSection?> = readFile(fileName, null)

    /**
     * Reads a given .ini file from disk and returns it as a HashMap of SettingSections, themselves
     * effectively a HashMap of key/value settings. If unsuccessful, outputs an error telling why it
     * failed.
     *
     * @param gameId the id of the game to load it's settings.
     * @param view   The current view.
     */
    fun readCustomGameSettings(
        gameId: String,
        view: SettingsActivityView?
    ): HashMap<String, SettingSection?> {
        val ini = getCustomGameSettingsFile(gameId) ?: return SettingsSectionMap()
        return readFile(ini, true, view)
    }

    /**
     * Saves a Settings HashMap to a given .ini file on disk. If unsuccessful, outputs an error
     * telling why it failed.
     *
     * @param fileName The target filename without a path or extension.
     * @param sections The HashMap containing the Settings we want to serialize.
     * @param view     The current view.
     */
    fun saveFile(
        fileName: String,
        sections: TreeMap<String, SettingSection?>,
        view: SettingsActivityView
    ) {
        val ini = getSettingsFile(fileName)
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            val writer = Wini(inputStream)
            val keySet: Set<String> = sections.keys
            for (key in keySet) {
                val section = sections[key]
                writeSection(writer, section!!)
            }
            inputStream!!.close()
            val outputStream = context.contentResolver.openOutputStream(ini.uri, "wt")
            writer.store(outputStream)
            outputStream!!.flush()
            outputStream.close()
        } catch (e: Exception) {
            Log.error("[SettingsFile] File not found: $fileName.ini: ${e.message}")
            view.showToastMessage(
                CitraApplication.appContext
                    .getString(R.string.error_saving, fileName, e.message),
                false
            )
        }
    }

    fun saveFile(fileName: String, setting: AbstractSetting) {
        val ini = getSettingsFile(fileName)
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            val writer = Wini(inputStream)
            writer.put(setting.section, setting.key, setting.valueAsString)
            inputStream!!.close()
            val outputStream = context.contentResolver.openOutputStream(ini.uri, "wt")
            writer.store(outputStream)
            outputStream!!.flush()
            outputStream.close()
        } catch (e: Exception) {
            Log.error("[SettingsFile] File not found: $fileName.ini: ${e.message}")
        }
    }

    fun saveCustomGameSettings(
        gameId: String,
        sections: TreeMap<String, SettingSection?>,
        view: SettingsActivityView
    ) {
        try {
            val ini = getCustomGameSettingsFile(gameId, true)!!
            val resolver = CitraApplication.appContext.contentResolver
            val writer = resolver.openInputStream(ini.uri)!!.use { Wini(it) }
            // Remove known values before writing the sparse overrides; preserve unknown keys.
            val knownSettings = BooleanSetting.values().toList() + IntSetting.values() +
                FloatSetting.values() + ScaledFloatSetting.values() + StringSetting.values() +
                IntListSetting.values()
            for (setting in knownSettings) {
                writer[setting.section]?.remove(setting.key)
            }
            for (section in sections.values) {
                writeSection(writer, section!!)
            }
            resolver.openOutputStream(ini.uri, "wt")!!.use { writer.store(it) }
            view.showToastMessage(CitraApplication.appContext.getString(R.string.ini_saved), false)
        } catch (e: Exception) {
            Log.error("[SettingsFile] Error saving game settings for $gameId: ${e.message}")
            view.showToastMessage(
                CitraApplication.appContext.getString(R.string.error_saving, gameId, e.message),
                true
            )
        }
    }

    fun saveCustomGameSetting(gameId: String, setting: AbstractSetting, useGlobal: Boolean) {
        try {
            val ini = getCustomGameSettingsFile(gameId, !useGlobal) ?: return
            val resolver = CitraApplication.appContext.contentResolver
            val writer = resolver.openInputStream(ini.uri)!!.use { Wini(it) }
            if (useGlobal) {
                writer[setting.section]?.remove(setting.key)
            } else {
                writer.put(setting.section, setting.key, setting.valueAsString)
            }
            resolver.openOutputStream(ini.uri, "wt")!!.use { writer.store(it) }
        } catch (e: Exception) {
            Log.error("[SettingsFile] Error saving game setting for $gameId: ${e.message}")
        }
    }

    fun deleteCustomGameSettings(gameId: String, view: SettingsActivityView): Boolean {
        try {
            val ini = getCustomGameSettingsFile(gameId) ?: return true
            if (!ini.delete()) {
                throw IOException("Could not delete ${ini.uri}")
            }
            CitraApplication.documentsTree.setRoot(Uri.parse(userDirectory))
            return true
        } catch (e: Exception) {
            view.showToastMessage(
                CitraApplication.appContext.getString(R.string.error_saving, gameId, e.message),
                true
            )
            return false
        }
    }

    fun applySettingValue(key: String, value: String) {
        settingFromLine("$key=$value")
    }

    private fun mapSectionNameFromIni(generalSectionName: String): String? =
        if (sectionsMap.getForward(generalSectionName) != null) {
            sectionsMap.getForward(generalSectionName)
        } else {
            generalSectionName
        }

    private fun mapSectionNameToIni(generalSectionName: String): String =
        if (sectionsMap.getBackward(generalSectionName) != null) {
            sectionsMap.getBackward(generalSectionName).toString()
        } else {
            generalSectionName
        }

    fun getSettingsFile(fileName: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))
        val configDirectory = root!!.findFile("config")
        return configDirectory!!.findFile("$fileName.ini")!!
    }

    private fun getCustomGameSettingsFile(
        gameId: String,
        create: Boolean = false
    ): DocumentFile? {
        require(gameId.matches(Regex("[0-9A-Fa-f]{16}")))
        val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))!!
        val directory = root.findFile("GameSettings")
            ?: if (create) root.createDirectory("GameSettings") else null
        val filename = "${gameId.uppercase()}.ini"
        val existing = directory?.findFile(filename)
        if (existing != null || !create) {
            return existing
        }
        val ini = directory?.createFile("application/octet-stream", filename)
            ?: throw IOException("Could not create GameSettings/$filename")
        // DocumentFile changes bypass the tree used by native IO; discard cached listings.
        CitraApplication.documentsTree.setRoot(Uri.parse(userDirectory))
        return ini
    }

    private fun sectionFromLine(line: String, isCustomGame: Boolean): SettingSection {
        var sectionName: String = line.substring(1, line.length - 1)
        if (isCustomGame) {
            sectionName = mapSectionNameToIni(sectionName)
        }
        return SettingSection(sectionName)
    }

    /**
     * For a line of text, determines what type of data is being represented, and returns
     * a Setting object containing this data.
     *
     * @param line    The line of text being parsed.
     * @return A typed Setting containing the key/value contained in the line.
     */
    private fun settingFromLine(line: String): AbstractSetting? {
        val splitLine = line.split("=", limit = 2)
        if (splitLine.size != 2 || line.trimStart().startsWith("#") ||
            line.trimStart().startsWith(";")
        ) {
            return null
        }
        val key = splitLine[0].trim()
        val value = splitLine[1].trim()
        val stringSetting = StringSetting.from(key)
        if (stringSetting != null) {
            if (value.isEmpty() && stringSetting.defaultValue.isNotEmpty()) {
                return null
            }
            stringSetting.string = value
            return stringSetting
        }
        if (value.isEmpty()) {
            IntListSetting.from(key)?.let { it.list = emptyList(); return it }
            return null
        }

        val booleanSetting = BooleanSetting.from(key)
        if (booleanSetting != null) {
            booleanSetting.boolean = value.lowercase() in listOf("true", "1", "yes", "on")
            return booleanSetting
        }

        val intSetting = IntSetting.from(key)
        if (intSetting != null) {
            try {
                intSetting.int = value.toInt()
            } catch (e: NumberFormatException) {
                intSetting.int = if (value.toBoolean()) 1 else 0
            }
            return intSetting
        }

        val scaledFloatSetting = ScaledFloatSetting.from(key)
        if (scaledFloatSetting != null) {
            scaledFloatSetting.float = value.toFloat() * scaledFloatSetting.scale
            return scaledFloatSetting
        }

        val floatSetting = FloatSetting.from(key)
        if (floatSetting != null) {
            floatSetting.float = value.toFloat()
            return floatSetting
        }


        val intListSetting = IntListSetting.from(key)
        if (intListSetting != null) {
            intListSetting.list = value.split(",").map { it.trim().toInt() }
            return intListSetting
        }

        return null
    }

    /**
     * Writes the contents of a Section HashMap to disk.
     *
     * @param parser  A Wini pointed at a file on disk.
     * @param section A section containing settings to be written to the file.
     */
    private fun writeSection(parser: Wini, section: SettingSection) {
        // Write the section header.
        val header = section.name

        // Write this section's values.
        val settings = section.settings
        val keySet: Set<String> = settings.keys
        for (key in keySet) {
            val setting = settings[key]
            parser.put(header, setting!!.key, setting.valueAsString)
        }
    }
}

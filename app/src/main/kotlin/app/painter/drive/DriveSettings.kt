package app.painter.drive

import android.content.Context

/**
 * Persists the linked account and the last sync time in a private preferences file. Access tokens live only in
 * Google Play services, so nothing secret is written here.
 */
class DriveSettings(context: Context) : DrivePreferences {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override var account: DriveAccount?
        get() {
            val email = prefs.getString(KEY_EMAIL, null) ?: return null
            return DriveAccount(email, prefs.getString(KEY_NAME, null))
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_EMAIL).remove(KEY_NAME)
            } else {
                edit.putString(KEY_EMAIL, value.email).putString(KEY_NAME, value.displayName)
            }
            edit.apply()
        }

    override var folders: DriveFolders?
        get() {
            val root = prefs.getString(KEY_ROOT_FOLDER, null) ?: return null
            val projects = prefs.getString(KEY_PROJECTS_FOLDER, null) ?: return null
            val drawings = prefs.getString(KEY_DRAWINGS_FOLDER, null) ?: return null
            return DriveFolders(root, projects, drawings)
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_ROOT_FOLDER).remove(KEY_PROJECTS_FOLDER).remove(KEY_DRAWINGS_FOLDER)
            } else {
                edit.putString(KEY_ROOT_FOLDER, value.rootId)
                    .putString(KEY_PROJECTS_FOLDER, value.projectsId)
                    .putString(KEY_DRAWINGS_FOLDER, value.drawingsId)
            }
            edit.apply()
        }

    override var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    override var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
        }

    /** Clears the link but keeps the auto-sync preference, which is not tied to one account. */
    override fun clear() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_NAME)
            .remove(KEY_ROOT_FOLDER)
            .remove(KEY_PROJECTS_FOLDER)
            .remove(KEY_DRAWINGS_FOLDER)
            .remove(KEY_LAST_SYNC)
            .apply()
    }

    private companion object {
        const val NAME = "drive_settings"
        const val KEY_EMAIL = "account.email"
        const val KEY_NAME = "account.name"
        const val KEY_ROOT_FOLDER = "folders.root"
        const val KEY_PROJECTS_FOLDER = "folders.projects"
        const val KEY_DRAWINGS_FOLDER = "folders.drawings"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_AUTO_SYNC = "auto_sync"
    }
}

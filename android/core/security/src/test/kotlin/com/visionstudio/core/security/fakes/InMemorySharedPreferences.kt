package com.visionstudio.core.security.fakes

import android.content.SharedPreferences

/**
 * Thread-unsafe, synchronous [SharedPreferences] with fault injection: [readFailure] is thrown
 * from every read, and [commitSucceeds] controls the result of [SharedPreferences.Editor.commit].
 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = linkedMapOf<String, Any?>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    var readFailure: (() -> Exception)? = null
    var commitSucceeds: Boolean = true

    /** Writes directly, bypassing failure injection, for arranging test state. */
    fun seed(key: String, value: String) {
        values[key] = value
    }

    fun rawString(key: String): String? = values[key] as? String

    private fun checkRead() {
        readFailure?.let { throw it() }
    }

    override fun getAll(): MutableMap<String, *> {
        checkRead()
        return values.toMutableMap()
    }

    override fun getString(key: String, defValue: String?): String? {
        checkRead()
        return values[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        checkRead()
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        checkRead()
        return values[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        checkRead()
        return values[key] as? Long ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        checkRead()
        return values[key] as? Float ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        checkRead()
        return values[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean {
        checkRead()
        return values.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = stage(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = stage(key, values?.toSet())
        override fun putInt(key: String, value: Int) = stage(key, value)
        override fun putLong(key: String, value: Long) = stage(key, value)
        override fun putFloat(key: String, value: Float) = stage(key, value)
        override fun putBoolean(key: String, value: Boolean) = stage(key, value)

        override fun remove(key: String): SharedPreferences.Editor = apply {
            removals += key
            pending.remove(key)
        }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            val changed = linkedSetOf<String>()
            if (clearRequested) {
                changed += values.keys
                values.clear()
            }
            removals.forEach { key -> if (values.remove(key) != null) changed += key }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
                changed += key
            }
            changed.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = apply {
            removals.remove(key)
            pending[key] = value
        }
    }
}

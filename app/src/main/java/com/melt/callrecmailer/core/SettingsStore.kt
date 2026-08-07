package com.melt.callrecmailer.core

interface SettingsStore {
    fun load(): Settings
    fun saveAppPassword(appPassword: String)
    fun isConfigured(): Boolean
}

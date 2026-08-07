package com.melt.callrecmailer.core

interface SentKeyRepository {
    fun isSent(key: String): Boolean
    fun markSent(key: String)
}

package com.melt.callrecmailer.core

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val appPassword: String,
    val fromAddress: String,
)

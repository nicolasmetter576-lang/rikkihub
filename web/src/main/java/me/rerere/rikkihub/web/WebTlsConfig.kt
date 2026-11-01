package me.rerere.rikkihub.web

import java.security.KeyStore

/** TLS key material is host-only; not a Settings/provider/HTTP DTO. */
data class WebTlsConfig(
    val keyStore: KeyStore,
    val keyAlias: String,
    val keyStorePassword: () -> CharArray,
    val privateKeyPassword: () -> CharArray,
)

package org.rustls.platformverifier

// Shim for the vendored CertificateVerifier.kt (fetched byte-identical from
// element-hq/element-x-android libraries/rustls-tls, itself vendored from
// rustls/rustls-platform-verifier, MIT). Element X generates this constant via
// a Gradle buildConfigField in a dedicated module; vendored into the app
// module, we supply it here instead. Never true outside the upstream test
// harness — the mock-root code paths stay dead.
internal object BuildConfig {
    const val TEST = false
}

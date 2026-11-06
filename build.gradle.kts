plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// The wrapper task performs a pre-flight download check of the distribution URL.
// That check fails behind proxies that reject full-file GETs while allowing
// ranged requests, so it is disabled here; the wrapper itself still downloads
// and verifies the distribution on first use.
tasks.wrapper {
    validateDistributionUrl = false
}


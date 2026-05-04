// Top-level build file. We declare plugins here without applying them so each
// subproject can apply them where needed. This keeps configuration fast.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

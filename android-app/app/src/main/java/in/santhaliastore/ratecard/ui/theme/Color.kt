package `in`.santhaliastore.ratecard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Hand-picked saffron / marigold-inspired palette. Anchored on
 * amber-700 (#B45309) which feels distinctly Indian without
 * descending into "Holi-card" territory.
 *
 * Mirrors the values declared in res/values/colors.xml. Compose
 * uses these tokens directly via [Theme.kt] so we don't pay the
 * indirection cost of Resources lookups in recompositions.
 */

// Brand seeds (raw, not used directly by the theme — keep for branding screens)
val BrandSaffron = Color(0xFFD97706)
val BrandMarigold = Color(0xFFF59E0B)
val BrandDeep = Color(0xFF92400E)

// Light scheme
val PrimaryLight = Color(0xFFB45309)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFFFE0B2)
val OnPrimaryContainerLight = Color(0xFF3B1F00)

val SecondaryLight = Color(0xFF7C5800)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFFFE0AE)
val OnSecondaryContainerLight = Color(0xFF271900)

val TertiaryLight = Color(0xFF3F6B3B)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFC0F1B6)
val OnTertiaryContainerLight = Color(0xFF002203)

val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

val BackgroundLight = Color(0xFFFFFBF5)
val OnBackgroundLight = Color(0xFF1F1B16)
val SurfaceLight = Color(0xFFFFFBF5)
val OnSurfaceLight = Color(0xFF1F1B16)
val SurfaceVariantLight = Color(0xFFF1E0CB)
val OnSurfaceVariantLight = Color(0xFF504539)
val OutlineLight = Color(0xFF82766A)

// Dark scheme
val PrimaryDark = Color(0xFFFFB870)
val OnPrimaryDark = Color(0xFF4A2800)
val PrimaryContainerDark = Color(0xFF6A3C00)
val OnPrimaryContainerDark = Color(0xFFFFDDB8)

val SecondaryDark = Color(0xFFF0BF6E)
val OnSecondaryDark = Color(0xFF412D00)
val SecondaryContainerDark = Color(0xFF5E4200)
val OnSecondaryContainerDark = Color(0xFFFFDEAD)

val TertiaryDark = Color(0xFFA4D49C)
val OnTertiaryDark = Color(0xFF103E13)
val TertiaryContainerDark = Color(0xFF275625)
val OnTertiaryContainerDark = Color(0xFFC0F1B6)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

val BackgroundDark = Color(0xFF1F1B16)
val OnBackgroundDark = Color(0xFFEAE1D6)
val SurfaceDark = Color(0xFF1F1B16)
val OnSurfaceDark = Color(0xFFEAE1D6)
val SurfaceVariantDark = Color(0xFF504539)
val OnSurfaceVariantDark = Color(0xFFD4C5B5)
val OutlineDark = Color(0xFF9D9085)

package com.analytic.atribution.gb.clarity

import androidx.compose.ui.Modifier
import com.microsoft.clarity.modifiers.clarityMask as microsoftClarityMask

/**
 * Marks a Composable's region as masked in Clarity session recordings.
 *
 * This is a thin pass-through over Microsoft Clarity's own `clarityMask()` modifier so the
 * host app does not need to depend on `com.microsoft.clarity:clarity-compose` directly.
 *
 * Safe to call regardless of whether Clarity has been initialized — when the SDK is not
 * active the modifier is effectively a no-op (Clarity itself ignores the directive).
 *
 * Usage:
 * ```
 * Text(
 *     text = email,
 *     modifier = Modifier.gpClarityMask(),
 * )
 * ```
 */
fun Modifier.gpClarityMask(): Modifier = this.microsoftClarityMask()

// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.dsl.gridLayout

import com.intellij.ui.dsl.checkNonNegative
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBEmptyBorder
import org.jetbrains.annotations.ApiStatus
import java.awt.Insets
import kotlin.math.roundToInt

/**
 * Defines gaps around. Values must be provided unscaled
 */
interface UnscaledGaps {
  companion object {
    @JvmField
    val EMPTY = UnscaledGaps(0)
  }

  val top: Int
  val left: Int
  val bottom: Int
  val right: Int

  val width: Int
    get() = left + right

  val height: Int
    get() = top + bottom

  @ApiStatus.Experimental
  @ApiStatus.Internal
  fun copy(top: Int = this.top, left: Int = this.left, bottom: Int = this.bottom, right: Int = this.right): UnscaledGaps
}

fun UnscaledGaps(size: Int): UnscaledGaps {
  return UnscaledGaps(size, size, size, size)
}

fun UnscaledGaps(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0): UnscaledGaps {
  return UnscaledGapsImpl(top, left, bottom, right)
}

fun Insets.toUnscaledGaps(): UnscaledGaps = toGaps().toUnscaled()

@ApiStatus.Internal
fun Int.unscale(): Int = (this / JBUIScale.scale(1f)).roundToInt()

@Suppress("UseDPIAwareInsets")
@ApiStatus.Internal
fun Insets.unscale(): Insets = Insets(top.unscale(), left.unscale(), bottom.unscale(), right.unscale())

fun UnscaledGaps.toJBEmptyBorder(): JBEmptyBorder {
  return JBEmptyBorder(top, left, bottom, right)
}

private class UnscaledGapsImpl(private val _top: Int,
                               private val _left: Int,
                               private val _bottom: Int,
                               private val _right: Int) : UnscaledGaps {

  override val top: Int
    get() = _top
  override val left: Int
    get() = _left
  override val bottom: Int
    get() = _bottom
  override val right: Int
    get() = _right

  init {
    checkNonNegative("top", _top)
    checkNonNegative("left", _left)
    checkNonNegative("bottom", _bottom)
    checkNonNegative("right", _right)
  }

  override fun copy(top: Int, left: Int, bottom: Int, right: Int): UnscaledGaps {
    return UnscaledGapsImpl(top, left, bottom, right)
  }
}

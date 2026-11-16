// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer

/**
 * Helpers for completing Pigeon `Result<Unit>` callbacks from Java, where Kotlin's inline `Result`
 * class cannot be constructed directly.
 */
object ResultCallbacks {
  @JvmStatic
  fun success(callback: (Result<Unit>) -> Unit) {
    callback(Result.success(Unit))
  }

  @JvmStatic
  fun failure(callback: (Result<Unit>) -> Unit, error: Throwable) {
    callback(Result.failure(error))
  }
}

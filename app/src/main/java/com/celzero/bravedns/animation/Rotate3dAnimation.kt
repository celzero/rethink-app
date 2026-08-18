/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.animation

import android.graphics.Camera
import android.view.animation.Animation
import android.view.animation.Transformation

// Code adopted from below link
// github.com/guardianproject/orbot/blob/ce3f265/appcore/src/main/java/org/torproject/android/core/ui/Rotate3dAnimation.kt

/**
 * An animation that rotates the view on the Y axis between two specified angles. This animation
 * also adds a translation on the Z axis (depth) to improve the effect.
 */
class Rotate3dAnimation
/**
 * Creates a new 3D rotation on the Y axis. The rotation is defined by its start angle and its end
 * angle. Both angles are in degrees. The rotation is performed around a center point on the 2D
 * space, defined by a pair of X and Y coordinates, called centerX and centerY. When the animation
 * starts, a translation on the Z axis (depth) is performed. The length of the translation can be
 * specified, as well as whether the translation should be reversed in time.
 *
 * @param mFromDegrees the start angle of the 3D rotation
 * @param mToDegrees the end angle of the 3D rotation
 * @param mCenterX the X center of the 3D rotation
 * @param mCenterY the Y center of the 3D rotation
 * @param mReverse true if the translation should be reversed, false otherwise
 */
(
    private val mFromDegrees: Float,
    private val mToDegrees: Float,
    private val mCenterX: Float,
    private val mCenterY: Float,
    private val mDepthZ: Float,
    private val mReverse: Boolean
) : Animation() {
    private lateinit var mCamera: Camera

    override fun initialize(width: Int, height: Int, parentWidth: Int, parentHeight: Int) {
        super.initialize(width, height, parentWidth, parentHeight)
        mCamera = Camera()
    }

    override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
        val degrees = mFromDegrees + (mToDegrees - mFromDegrees) * interpolatedTime
        with(mCamera) {
            save()
            if (mReverse) translate(0.0f, 0.0f, mDepthZ * interpolatedTime)
            else translate(0.0f, 0.0f, mDepthZ * (1.0f - interpolatedTime))
            rotateY(degrees)
            getMatrix(t.matrix)
            restore()
        }
        t.matrix.preTranslate(-mCenterX, -mCenterY)
        t.matrix.postTranslate(mCenterX, mCenterY)
    }
}

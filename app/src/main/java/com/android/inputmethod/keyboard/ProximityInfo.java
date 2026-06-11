/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.keyboard;

import com.android.inputmethod.latin.utils.JniUtils;

import java.util.Arrays;

public final class ProximityInfo implements AutoCloseable {
    public static final int MAX_PROXIMITY_CHARS_SIZE = 16;
    public static final int NOT_A_CODE = -1;

    private long mNativeProximityInfo;

    static {
        JniUtils.loadNativeLibrary();
    }

    public ProximityInfo(
            final int displayWidth,
            final int displayHeight,
            final int gridWidth,
            final int gridHeight,
            final int mostCommonKeyWidth,
            final int mostCommonKeyHeight,
            final int[] keyXCoordinates,
            final int[] keyYCoordinates,
            final int[] keyWidths,
            final int[] keyHeights,
            final int[] keyCharCodes,
            final float[] sweetSpotCenterXs,
            final float[] sweetSpotCenterYs,
            final float[] sweetSpotRadii) {
        final int[] proximityChars = new int[gridWidth * gridHeight * MAX_PROXIMITY_CHARS_SIZE];
        Arrays.fill(proximityChars, NOT_A_CODE);
        mNativeProximityInfo = setProximityInfoNative(
                displayWidth,
                displayHeight,
                gridWidth,
                gridHeight,
                mostCommonKeyWidth,
                mostCommonKeyHeight,
                proximityChars,
                keyCharCodes.length,
                keyXCoordinates,
                keyYCoordinates,
                keyWidths,
                keyHeights,
                keyCharCodes,
                sweetSpotCenterXs,
                sweetSpotCenterYs,
                sweetSpotRadii);
    }

    public long getNativeProximityInfo() {
        return mNativeProximityInfo;
    }

    @Override
    public void close() {
        if (mNativeProximityInfo != 0) {
            releaseProximityInfoNative(mNativeProximityInfo);
            mNativeProximityInfo = 0;
        }
    }

    private static native long setProximityInfoNative(
            int displayWidth,
            int displayHeight,
            int gridWidth,
            int gridHeight,
            int mostCommonKeyWidth,
            int mostCommonKeyHeight,
            int[] proximityCharsArray,
            int keyCount,
            int[] keyXCoordinates,
            int[] keyYCoordinates,
            int[] keyWidths,
            int[] keyHeights,
            int[] keyCharCodes,
            float[] sweetSpotCenterXs,
            float[] sweetSpotCenterYs,
            float[] sweetSpotRadii);

    private static native void releaseProximityInfoNative(long nativeProximityInfo);
}

/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.latin.utils;

public final class BinaryDictionaryUtils {
    static {
        JniUtils.loadNativeLibrary();
    }

    private BinaryDictionaryUtils() {
    }

    private static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
            String locale, String[] attributeKeyStringArray, String[] attributeValueStringArray);
    private static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    private static native int setCurrentTimeForTestNative(int currentTime);
}

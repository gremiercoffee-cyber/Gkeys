/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.latin.common;

public final class NativeSuggestOptions {
    private static final int IS_GESTURE = 0;
    private static final int USE_FULL_EDIT_DISTANCE = 1;
    private static final int BLOCK_OFFENSIVE_WORDS = 2;
    private static final int SPACE_AWARE_GESTURE_ENABLED = 3;
    private static final int WEIGHT_FOR_LOCALE_IN_THOUSANDS = 4;
    private static final int OPTIONS_SIZE = 5;

    private final int[] mOptions = new int[OPTIONS_SIZE];

    public void setIsGesture(final boolean value) {
        mOptions[IS_GESTURE] = value ? 1 : 0;
    }

    public void setUseFullEditDistance(final boolean value) {
        mOptions[USE_FULL_EDIT_DISTANCE] = value ? 1 : 0;
    }

    public void setBlockOffensiveWords(final boolean value) {
        mOptions[BLOCK_OFFENSIVE_WORDS] = value ? 1 : 0;
    }

    public void setSpaceAwareGesture(final boolean value) {
        mOptions[SPACE_AWARE_GESTURE_ENABLED] = value ? 1 : 0;
    }

    public void setWeightForLocale(final float value) {
        mOptions[WEIGHT_FOR_LOCALE_IN_THOUSANDS] = (int) (value * 1000);
    }

    public int[] getOptions() {
        return mOptions;
    }
}

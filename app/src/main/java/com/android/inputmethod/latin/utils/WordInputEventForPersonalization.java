/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.latin.utils;

public final class WordInputEventForPersonalization {
    public int[] mTargetWord;
    public int[][] mPrevWords;
    public boolean[] mIsBeginningOfSentenceArray;
    public int mTimestamp;
}

/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.latin;

import com.android.inputmethod.latin.common.NativeSuggestOptions;
import com.android.inputmethod.latin.define.DecoderSpecificConstants;
import com.android.inputmethod.latin.utils.JniUtils;

import java.util.Locale;

public final class DicTraverseSession implements AutoCloseable {
    private static final int MAX_RESULTS = 18;

    public final int[] mInputCodePoints =
            new int[DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH];
    public final int[][] mPrevWordCodePointArrays =
            new int[DecoderSpecificConstants.MAX_PREV_WORD_COUNT_FOR_N_GRAM][];
    public final boolean[] mIsBeginningOfSentenceArray =
            new boolean[DecoderSpecificConstants.MAX_PREV_WORD_COUNT_FOR_N_GRAM];
    public final int[] mOutputSuggestionCount = new int[1];
    public final int[] mOutputCodePoints =
            new int[DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH * MAX_RESULTS];
    public final int[] mSpaceIndices = new int[MAX_RESULTS];
    public final int[] mOutputScores = new int[MAX_RESULTS];
    public final int[] mOutputTypes = new int[MAX_RESULTS];
    public final int[] mOutputAutoCommitFirstWordConfidence = new int[1];
    public final float[] mInputOutputWeightOfLangModelVsSpatialModel = new float[1];
    public final NativeSuggestOptions mNativeSuggestOptions = new NativeSuggestOptions();

    private long mNativeDicTraverseSession;

    static {
        JniUtils.loadNativeLibrary();
    }

    public DicTraverseSession(final Locale locale, final long dictionary, final long dictSize) {
        mNativeDicTraverseSession = setDicTraverseSessionNative(
                locale != null ? locale.toString() : "", dictSize);
        initSession(dictionary, null);
    }

    public long getSession() {
        return mNativeDicTraverseSession;
    }

    public void initSession(final long dictionary, final int[] previousWord) {
        initDicTraverseSessionNative(mNativeDicTraverseSession, dictionary,
                previousWord, previousWord == null ? 0 : previousWord.length);
    }

    @Override
    public void close() {
        if (mNativeDicTraverseSession != 0) {
            releaseDicTraverseSessionNative(mNativeDicTraverseSession);
            mNativeDicTraverseSession = 0;
        }
    }

    private static native long setDicTraverseSessionNative(String locale, long dictSize);
    private static native void initDicTraverseSessionNative(long nativeDicTraverseSession,
            long dictionary, int[] previousWord, int previousWordLength);
    private static native void releaseDicTraverseSessionNative(long nativeDicTraverseSession);
}

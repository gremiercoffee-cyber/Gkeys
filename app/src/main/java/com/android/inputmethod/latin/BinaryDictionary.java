/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.inputmethod.latin;

import com.android.inputmethod.latin.define.DecoderSpecificConstants;
import com.android.inputmethod.latin.utils.JniUtils;
import com.android.inputmethod.latin.utils.WordInputEventForPersonalization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class BinaryDictionary implements AutoCloseable {
    public static final int FORMAT_VERSION_4 = 403;
    public static final int NOT_A_VALID_TIMESTAMP = -1;
    private static final int MAX_RESULTS = 18;
    private static final int SESSION_ID_GESTURE = 0;

    private final Locale mLocale;
    private long mNativeDict;
    private final DicTraverseSession mGestureSession;

    static {
        JniUtils.loadNativeLibrary();
    }

    public BinaryDictionary(final Locale locale) {
        mLocale = locale;
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("locale", locale == null ? "en_US" : locale.toString());
        attributes.put("dictionary", "gkeys_user");
        final String[] keys = attributes.keySet().toArray(new String[0]);
        final String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            values[i] = attributes.get(keys[i]);
        }
        mNativeDict = createOnMemoryNative(FORMAT_VERSION_4,
                locale == null ? "en_US" : locale.toString(), keys, values);
        mGestureSession = new DicTraverseSession(locale, mNativeDict, 0);
    }

    public boolean isValid() {
        return mNativeDict != 0;
    }

    public boolean addWord(final String word, final int probability) {
        if (word == null || word.isEmpty() || mNativeDict == 0) return false;
        return addUnigramEntryNative(
                mNativeDict,
                toCodePoints(word),
                probability,
                null,
                0,
                false,
                false,
                false,
                NOT_A_VALID_TIMESTAMP);
    }

    public ArrayList<Suggestion> getGestureSuggestions(
            final long proximityInfo,
            final int[] xCoordinates,
            final int[] yCoordinates,
            final int[] times,
            final int[] pointerIds,
            final String previousWord) {
        if (mNativeDict == 0 || proximityInfo == 0 || xCoordinates.length == 0) {
            return new ArrayList<>();
        }
        Arrays.fill(mGestureSession.mInputCodePoints, 0);
        Arrays.fill(mGestureSession.mOutputCodePoints, 0);
        Arrays.fill(mGestureSession.mOutputScores, 0);
        Arrays.fill(mGestureSession.mSpaceIndices, 0);
        Arrays.fill(mGestureSession.mOutputTypes, 0);
        mGestureSession.mOutputSuggestionCount[0] = 0;
        mGestureSession.mOutputAutoCommitFirstWordConfidence[0] = 0;
        mGestureSession.mInputOutputWeightOfLangModelVsSpatialModel[0] = -1.0f;
        mGestureSession.mNativeSuggestOptions.setUseFullEditDistance(true);
        mGestureSession.mNativeSuggestOptions.setIsGesture(true);
        mGestureSession.mNativeSuggestOptions.setBlockOffensiveWords(false);
        mGestureSession.mNativeSuggestOptions.setSpaceAwareGesture(false);
        mGestureSession.mNativeSuggestOptions.setWeightForLocale(1.0f);

        final int[] previous = previousWord == null || previousWord.isEmpty()
                ? null : toCodePoints(previousWord);
        mGestureSession.initSession(mNativeDict, previous);

        getSuggestionsNative(
                mNativeDict,
                proximityInfo,
                mGestureSession.getSession(),
                xCoordinates,
                yCoordinates,
                times,
                pointerIds,
                mGestureSession.mInputCodePoints,
                xCoordinates.length,
                mGestureSession.mNativeSuggestOptions.getOptions(),
                mGestureSession.mPrevWordCodePointArrays,
                mGestureSession.mIsBeginningOfSentenceArray,
                previous == null ? 0 : 1,
                mGestureSession.mOutputSuggestionCount,
                mGestureSession.mOutputCodePoints,
                mGestureSession.mOutputScores,
                mGestureSession.mSpaceIndices,
                mGestureSession.mOutputTypes,
                mGestureSession.mOutputAutoCommitFirstWordConfidence,
                mGestureSession.mInputOutputWeightOfLangModelVsSpatialModel);

        final int count = Math.min(mGestureSession.mOutputSuggestionCount[0], MAX_RESULTS);
        final ArrayList<Suggestion> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int start = i * DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH;
            int len = 0;
            while (len < DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH
                    && mGestureSession.mOutputCodePoints[start + len] != 0) {
                len++;
            }
            if (len > 0) {
                out.add(new Suggestion(
                        new String(mGestureSession.mOutputCodePoints, start, len),
                        mGestureSession.mOutputScores[i],
                        mGestureSession.mOutputTypes[i]));
            }
        }
        return out;
    }

    @Override
    public void close() {
        mGestureSession.close();
        if (mNativeDict != 0) {
            closeNative(mNativeDict);
            mNativeDict = 0;
        }
    }

    private static int[] toCodePoints(final String word) {
        return word.codePoints().toArray();
    }

    public static final class Suggestion {
        public final String word;
        public final int score;
        public final int type;

        public Suggestion(final String word, final int score, final int type) {
            this.word = word;
            this.score = score;
            this.type = type;
        }
    }

    private static native long openNative(String sourceDir, long dictOffset, long dictSize,
            boolean isUpdatable);
    private static native long createOnMemoryNative(long formatVersion, String locale,
            String[] attributeKeyStringArray, String[] attributeValueStringArray);
    private static native void closeNative(long dict);
    private static native int getFormatVersionNative(long dict);
    private static native void getHeaderInfoNative(long dict, int[] outHeaderSize,
            int[] outFormatVersion, ArrayList<int[]> outAttributeKeys,
            ArrayList<int[]> outAttributeValues);
    private static native boolean flushNative(long dict, String filePath);
    private static native boolean needsToRunGCNative(long dict, boolean mindsBlockByGC);
    private static native boolean flushWithGCNative(long dict, String filePath);
    private static native void getSuggestionsNative(long dict, long proximityInfo,
            long traverseSession, int[] xCoordinates, int[] yCoordinates, int[] times,
            int[] pointerIds, int[] inputCodePoints, int inputSize, int[] suggestOptions,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int prevWordCount, int[] outputSuggestionCount, int[] outputCodePoints,
            int[] outputScores, int[] outputIndices, int[] outputTypes,
            int[] outputAutoCommitFirstWordConfidence,
            float[] inOutWeightOfLangModelVsSpatialModel);
    private static native int getProbabilityNative(long dict, int[] word);
    private static native int getMaxProbabilityOfExactMatchesNative(long dict, int[] word);
    private static native int getNgramProbabilityNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray, int[] word);
    private static native void getWordPropertyNative(long dict, int[] word,
            boolean isBeginningOfSentence, int[] outCodePoints, boolean[] outFlags,
            int[] outProbabilityInfo, ArrayList<int[][]> outNgramPrevWordsArray,
            ArrayList<boolean[]> outNgramPrevWordIsBeginningOfSentenceArray,
            ArrayList<int[]> outNgramTargets, ArrayList<int[]> outNgramProbabilityInfo,
            ArrayList<int[]> outShortcutTargets, ArrayList<Integer> outShortcutProbabilities);
    private static native int getNextWordNative(long dict, int token, int[] outCodePoints,
            boolean[] outIsBeginningOfSentence);
    private static native boolean addUnigramEntryNative(long dict, int[] word, int probability,
            int[] shortcutTarget, int shortcutProbability, boolean isBeginningOfSentence,
            boolean isNotAWord, boolean isPossiblyOffensive, int timestamp);
    private static native boolean removeUnigramEntryNative(long dict, int[] word);
    private static native boolean addNgramEntryNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int[] word, int probability, int timestamp);
    private static native boolean removeNgramEntryNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray, int[] word);
    private static native boolean updateEntriesForWordWithNgramContextNative(long dict,
            int[][] prevWordCodePointArrays, boolean[] isBeginningOfSentenceArray,
            int[] word, boolean isValidWord, int count, int timestamp);
    private static native int updateEntriesForInputEventsNative(
            long dict, WordInputEventForPersonalization[] inputEvents, int startIndex);
    private static native String getPropertyNative(long dict, String query);
    private static native boolean isCorruptedNative(long dict);
    private static native boolean migrateNative(long dict, String dictFilePath, long newFormatVersion);
}

/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package dev.drosh.ime.latin.touchinputconsumer;

import android.view.inputmethod.EditorInfo;

import dev.drosh.ime.keyboard.Keyboard;
import dev.drosh.ime.latin.DictionaryFacilitator;
import dev.drosh.ime.latin.SuggestedWords;
import dev.drosh.ime.latin.common.InputPointers;
import dev.drosh.ime.latin.inputlogic.PrivateCommandPerformer;

import java.util.Locale;

/**
 * Stub for GestureConsumer.
 * <br>
 * The methods of this class should only be called from a single thread, e.g.,
 * the UI Thread.
 */
@SuppressWarnings("unused")
public class GestureConsumer {
    public static final GestureConsumer NULL_GESTURE_CONSUMER =
            new GestureConsumer();

    public static GestureConsumer newInstance(
            final EditorInfo editorInfo, final PrivateCommandPerformer commandPerformer,
            final Locale locale, final Keyboard keyboard) {
        return GestureConsumer.NULL_GESTURE_CONSUMER;
    }

    private GestureConsumer() {
    }

    public boolean willConsume() {
        return false;
    }

    public void onInit(final Locale locale, final Keyboard keyboard) {
    }

    public void onGestureStarted(final Locale locale, final Keyboard keyboard) {
    }

    public void onGestureCanceled() {
    }

    public void onGestureCompleted(final InputPointers inputPointers) {
    }

    public void onImeSuggestionsProcessed(final SuggestedWords suggestedWords,
            final int composingStart, final int composingLength,
            final DictionaryFacilitator dictionaryFacilitator) {
    }
}

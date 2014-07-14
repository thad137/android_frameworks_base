/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telecomm;

import android.os.Bundle;
import android.telecomm.CallAudioState;
import android.telecomm.ConnectionRequest;

import com.android.internal.telecomm.IConnectionServiceAdapter;

/**
 * Internal remote interface for connection services.
 *
 * @see android.telecomm.ConnectionService
 *
 * @hide
 */
oneway interface IConnectionService {
    void addConnectionServiceAdapter(in IConnectionServiceAdapter adapter);

    void call(in ConnectionRequest request);

    void abort(String callId);

    void createIncomingCall(in ConnectionRequest request);

    void answer(String callId);

    void reject(String callId);

    void disconnect(String callId);

    void hold(String callId);

    void unhold(String callId);

    void onAudioStateChanged(String activeCallId, in CallAudioState audioState);

    void playDtmfTone(String callId, char digit);

    void stopDtmfTone(String callId);

    void conference(String conferenceCallId, String callId);

    void splitFromConference(String callId);

    void swapWithBackgroundCall(String callId);

    void onPostDialContinue(String callId, boolean proceed);

    void onPhoneAccountClicked(String callId);
}
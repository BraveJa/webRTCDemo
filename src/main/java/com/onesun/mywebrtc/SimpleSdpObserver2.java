package com.onesun.mywebrtc;

import android.util.Log;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

public  class SimpleSdpObserver2 implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Log.i("SimpleSdpObserver2", "SdpObserver: onCreateSuccess !");
        }

        @Override
        public void onSetSuccess() {
            Log.i("SimpleSdpObserver2", "SdpObserver: onSetSuccess");
        }

        @Override
        public void onCreateFailure(String msg) {
            Log.e("SimpleSdpObserver2", "SdpObserver onCreateFailure: " + msg);
        }

        @Override
        public void onSetFailure(String msg) {

            Log.e("SimpleSdpObserver2", "SdpObserver onSetFailure: " + msg);
        }
    }
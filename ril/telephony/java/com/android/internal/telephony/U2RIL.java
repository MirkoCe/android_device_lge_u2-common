/*
 * Copyright (C) 2012 The CyanogenMod Project
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
package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;

import android.media.AudioManager;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemService;

import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import android.util.Log;
import java.util.ArrayList;

public class U2RIL extends RIL implements CommandsInterface {

    private AudioManager audioManager;
    protected HandlerThread mPathThread;
    protected CallPathHandler mPathHandler;

    private int mCallPath = -1;
    ArrayList<Integer> mCallList = new ArrayList<Integer>();

    public U2RIL(Context context, int networkMode,
            int cdmaSubscription, Integer instanceId) {
        this(context, networkMode, cdmaSubscription);
    }

    public U2RIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (state == TelephonyManager.CALL_STATE_IDLE || state > mCallState) {
                    mCallState = state;
                }
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    if (mPathHandler != null) {
                        mPathHandler.checkSpeakerphoneState();
                    }
                }
            }
        };

        ((TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE))
            .listen(mPhoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE);

        audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        if (mPathHandler == null) {
            mPathThread = new HandlerThread("CallPathThread");
            mPathThread.start();
            mPathHandler = new CallPathHandler(mPathThread.getLooper());
            mPathHandler.run();
        }
    }

    protected int mCallState = TelephonyManager.CALL_STATE_IDLE;

    private int RIL_REQUEST_HANG_UP_CALL = 0xB7;
    private int RIL_REQUEST_LGE_CPATH = 0xFD;
    private int RIL_REQUEST_LGE_SEND_COMMAND = 0x113;

    @Override
    public void getIMEI(Message result) {
        RILRequest rrLSC = RILRequest.obtain(RIL_REQUEST_LGE_SEND_COMMAND, null);
        rrLSC.mParcel.writeInt(1);
        rrLSC.mParcel.writeInt(0);
        send(rrLSC);
        
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEI, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void hangupWaitingOrBackground (Message result) {
        RILRequest rr = RILRequest.obtain(mCallState == TelephonyManager.CALL_STATE_OFFHOOK ?
                                        RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND :
                                        RIL_REQUEST_HANG_UP_CALL, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void setCallForward(int action, int cfReason, int serviceClass,
                String number, int timeSeconds, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CALL_FORWARD, response);

        rr.mParcel.writeInt(action);
        rr.mParcel.writeInt(cfReason);
        if (serviceClass == 0) serviceClass = 255;
        rr.mParcel.writeInt(serviceClass);
        rr.mParcel.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mParcel.writeString(number);
        rr.mParcel.writeInt (timeSeconds);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + action + " " + cfReason + " " + serviceClass
                    + timeSeconds);

        send(rr);
    }

    @Override
    public void queryCallForwardStatus(int cfReason, int serviceClass,
                String number, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, response);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(cfReason);
        if (serviceClass == 0) serviceClass = 255;
        rr.mParcel.writeInt(serviceClass);
        rr.mParcel.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mParcel.writeString(number);
        rr.mParcel.writeInt (0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + cfReason + " " + serviceClass);

        send(rr);
    }

    private void WriteLgeCPATH(int path) {
        RILRequest rrLSL = RILRequest.obtain(RIL_REQUEST_LGE_CPATH, null);
        rrLSL.mParcel.writeInt(1);
        rrLSL.mParcel.writeInt(path);
        send(rrLSL);
    }

    private void restartRild() {
        setRadioState(RadioState.RADIO_UNAVAILABLE);
        SystemService.stop("ril-daemon");
        RILRequest.resetSerial();
        clearRequestList(RADIO_NOT_AVAILABLE, false);
        SystemService.start("ril-daemon");
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {}
        setRadioState(RadioState.RADIO_ON);
    }
    
    static final int RIL_UNSOL_LGE_XCALLSTAT = 1053;
    static final int RIL_UNSOL_LGE_RESTART_RILD = 1055;
    static final int RIL_UNSOL_LGE_SIM_STATE_CHANGED = 1060;
    static final int RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW = 1061;
    static final int RIL_UNSOL_LGE_FACTORY_READY = 1080;

    @Override
    protected void processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition();
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED:
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW:
            case RIL_UNSOL_LGE_RESTART_RILD:
            case RIL_UNSOL_LGE_FACTORY_READY: ret =  responseVoid(p); break;
            case RIL_UNSOL_LGE_XCALLSTAT: ret =  responseInts(p); break;
            default:
                p.setDataPosition(dataPosition);
                super.processUnsolicited(p);
                return;
        }
        switch(response) {
            case RIL_UNSOL_LGE_XCALLSTAT:
                int[] intArray = (int[]) ret;
                int xcallState = intArray[1];
                int xcallID = intArray[0];
                /* 0 - established
                 * 1 - on hold
                 * 2 - dial start
                 * 3 - dialing
                 * 4 - incoming
                 * 5 - call waiting
                 * 6 - hangup
                 * 7 - answered
                 */
                switch (xcallState) {
                    case 2:
                    case 4:
                    case 7:
                        if (!mCallList.contains(xcallID)) {
                            mCallList.add(xcallID);
                        }
                        if (mCallList.size() != 0) {
                            WriteLgeCPATH(1);
                            mCallPath = 1;
                        }
                        break;
                    case 6:
                        if (mCallList.contains(xcallID)) {
                            mCallList.remove(mCallList.indexOf(xcallID));
                        }
                        if (mCallList.size() == 0) {
                            WriteLgeCPATH(0);
                            mCallPath = 0;
                        }
                        break;
                    default:
                        break;
                }

                if (RILJ_LOGD) riljLog("LGE XCALLSTAT > {" + xcallID + "," +  xcallState + "}");

                break;
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                RadioState newState = getRadioStateFromInt(p.readInt());
                switchToRadioState(newState);
                break;
            case RIL_UNSOL_LGE_RESTART_RILD:
                restartRild();
                break;
            case RIL_UNSOL_LGE_FACTORY_READY:
                RIL_REQUEST_HANG_UP_CALL = 0xB7;
                break;
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED:
            case RIL_UNSOL_LGE_SIM_STATE_CHANGED_NEW:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
            default:
                break;
        }
    }

    class CallPathHandler extends Handler implements Runnable {

        public CallPathHandler (Looper looper) {
            super(looper);
        }

        private void checkSpeakerphoneState() {
            if (mCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                int callPath = -1;
                if (mCallList.size() != 0) {
                    if (audioManager.isSpeakerphoneOn()) {
                        callPath = 3;
                    } else if (audioManager.isBluetoothScoOn()) {
                        callPath = 4;
                    } else {
                        callPath = 1;
                    }
                } else {
                    callPath = 0;
                }

                if (callPath != mCallPath) {
                    mCallPath = callPath;
                    WriteLgeCPATH(callPath);
                }

                Message msg = obtainMessage();
                msg.what = 0xc0ffee;
                sendMessageDelayed(msg, 2500);
            }
        }

        @Override
        public void handleMessage (Message msg) {
            if (msg.what == 0xc0ffee) {
                if (mCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    checkSpeakerphoneState();
                }
            }
        }

        @Override
        public void run () {
        }
    }
}

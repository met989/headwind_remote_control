/*
 * Headwind Remote: Open Source Remote Access Software for Android
 * https://headwind-remote.com
 *
 * Copyright (C) 2022 headwind-remote.com
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

package com.hmdm.control.janus;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.control.Const;
import com.hmdm.control.ServerApiHelper;
import com.hmdm.control.SharingEngine;
import com.hmdm.control.Utils;
import com.hmdm.control.janus.json.JanusJsepRequest;
import com.hmdm.control.janus.json.JanusMessageRequest;
import com.hmdm.control.janus.json.JanusResponse;
import com.hmdm.control.janus.json.Jsep;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import retrofit2.Response;

public class JanusTextRoomPlugin extends JanusPlugin {

    private String roomId;
    private String password;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private boolean joined;

    private boolean dcResult;
    private boolean dcResultReady;
    private Object dcResultLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean dcOpened = new java.util.concurrent.atomic.AtomicBoolean(false);
    private SharingEngine.CompletionHandler dcCompletionHandler;
    private SharingEngine.EventListener dcEventListener;

    private Handler handler = new Handler();
    private Runnable hangupProtectionRunnable = null;

    @Override
    public String getName() {
        return Const.JANUS_PLUGIN_TEXTROOM;
    }

    public String getRoomId() {
        return roomId;
    }

    @Override
    public void init(Context context) {
        super.init(context);
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }

    public void createPeerConnection(final SharingEngine.CompletionHandler completionHandler, final SharingEngine.EventListener eventListener) {
        this.dcCompletionHandler = completionHandler;
        this.dcEventListener = eventListener;
        this.dcOpened.set(false);
        // No ICE servers required for textroom: textroom messages are all coming through Janus
        List<PeerConnection.IceServer> iceServers = new LinkedList<>();
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(Const.LOG_TAG, "Textroom plugin: signalingState changed to " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(Const.LOG_TAG, "Textroom plugin: iceConnectionState changed to " + iceConnectionState);
                if (iceConnectionState.equals(PeerConnection.IceConnectionState.FAILED)) {
                    completionHandler.onComplete(false, Const.ERROR_ICE_FAILED);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(Const.LOG_TAG, "Textroom plugin: iceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(Const.LOG_TAG, "Textroom plugin: iceGatheringState changed to " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(Const.LOG_TAG, "Textroom plugin: iceCandidate: " + iceCandidate.toString());
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(Const.LOG_TAG, "Textroom plugin: iceCandidateRemoved");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(Const.LOG_TAG, "Textroom plugin: onAddStream: " + mediaStream.toString());
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(Const.LOG_TAG, "Textroom plugin: onRemoveStream: " + mediaStream.toString());
            }

            @Override
            public void onDataChannel(final DataChannel incomingChannel) {
                Log.d(Const.LOG_TAG, "Textroom plugin: onDataChannel, id=" + incomingChannel.id() + ", label=" + incomingChannel.label() + ", state=" + incomingChannel.state());
                attachDataChannel(incomingChannel);
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(Const.LOG_TAG, "Textroom plugin: onRenegotiationNeeded");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(Const.LOG_TAG, "Textroom plugin: onAddTrack");
            }
        });

    }

    private boolean checkJoined() {
        if (!joined) {
            Log.w(Const.LOG_TAG, "Ignoring message because we're not yet joined the textroom");
            return false;
        }
        return true;
    }


    private JanusJsepRequest createJsepRequest(String requestType) {
        JanusJsepRequest request = new JanusJsepRequest(secret, "message", getSessionId(), getHandleId());
        request.setBody(new JanusMessageRequest.Body(requestType, null));
        return request;
    }

    public void setupRtcSession(final SharingEngine.CompletionHandler completionHandler) {
        errorReason = null;
        JanusJsepRequest offerRequest = createJsepRequest("setup");
        Response<JanusResponse> response = ServerApiHelper.execute(apiInstance.sendJsep(getSessionId(), getHandleId(), offerRequest), "get JSEP offer");
        if (response == null) {
            errorReason = "Network error";
            completionHandler.onComplete(false, errorReason);
            return;
        }
        if (response.body() != null && response.body().getJanus().equalsIgnoreCase("ack")) {
            Log.i(Const.LOG_TAG, "Got response to JSEP offer, waiting for event");

            synchronized (pollingEventLock) {
                try {
                    pollingEventLock.wait(15000);
                } catch (InterruptedException e) {
                    errorReason = "Interrupted";
                    completionHandler.onComplete(false, errorReason);
                    return;
                }
            }
            if (pollingEvent == null) {
                errorReason = "Timeout waiting for JSEP offer";
                completionHandler.onComplete(false, errorReason);
                return;
            }
            Jsep jsepData = pollingEvent.getJsep();
            if (jsepData == null) {
                errorReason = "Server error";
                Log.w(Const.LOG_TAG, "Missing JSEP: " + response.body().toString());
                completionHandler.onComplete(false, errorReason);
                return;
            }
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    Log.i(Const.LOG_TAG, "RemoteDescription - create success");
                }

                @Override
                public void onSetSuccess() {
                    Log.i(Const.LOG_TAG, "RemoteDescription - success");
                    // Proceed with SDP asynchronously
                    createSessionAnswer(completionHandler);
                }

                @Override
                public void onCreateFailure(String s) {
                    Log.i(Const.LOG_TAG, "RemoteDescription - create failure: " + s);
                }

                @Override
                public void onSetFailure(String s) {
                    errorReason = "RemoteDescription - failure: " + s;
                    Log.w(Const.LOG_TAG, errorReason);
                    completionHandler.onComplete(false, errorReason);
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, jsepData.getSdp()));
        }
    }

    private void createSessionAnswer(final SharingEngine.CompletionHandler completionHandler) {
        MediaConstraints constraints = new MediaConstraints();
        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.i(Const.LOG_TAG, "createAnswer - create success");
                // Proceed with setting local session description asynchronously
                setLocalSessionDescription(sessionDescription, completionHandler);
            }

            @Override
            public void onSetSuccess() {
                Log.i(Const.LOG_TAG, "createAnswer - success");
            }

            @Override
            public void onCreateFailure(String s) {
                errorReason = "createAnswer - create failure: " + s;
                Log.w(Const.LOG_TAG, errorReason);
                completionHandler.onComplete(false, errorReason);
            }

            @Override
            public void onSetFailure(String s) {
                Log.i(Const.LOG_TAG, "createAnswer - failure: " + s);
            }
        }, constraints);
    }

    private void setLocalSessionDescription(final SessionDescription sessionDescription, final SharingEngine.CompletionHandler completionHandler) {
        peerConnection.setLocalDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.i(Const.LOG_TAG, "LocalDescription - create success");
            }

            @Override
            public void onSetSuccess() {
                Log.i(Const.LOG_TAG, "LocalDescription - success");
                // Proceed with SDP asynchronously
                sendSessionDescriptionAck(completionHandler);
            }

            @Override
            public void onCreateFailure(String s) {
                Log.i(Const.LOG_TAG, "LocalDescription - create failure: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                errorReason = "LocalDescription - failure: " + s;
                Log.w(Const.LOG_TAG, errorReason);
                completionHandler.onComplete(false, errorReason);
            }
        }, sessionDescription);
    }

    private void sendSessionDescriptionAck(final SharingEngine.CompletionHandler completionHandler) {
        JanusJsepRequest offerRequest = createJsepRequest("ack");
        offerRequest.setJsep(new Jsep("answer", peerConnection.getLocalDescription().description));
        Response<JanusResponse> response = ServerApiHelper.execute(apiInstance.sendJsep(getSessionId(), getHandleId(), offerRequest), "send JSEP ack");
        if (response == null) {
            errorReason = "Network error";
            completionHandler.onComplete(false, errorReason);
            return;
        }
        if (response.body() != null && response.body().getJanus().equalsIgnoreCase("ack")) {
            Log.i(Const.LOG_TAG, "Got response to JSEP offer, waiting for event");

            synchronized (pollingEventLock) {
                try {
                    pollingEventLock.wait(15000);
                } catch (InterruptedException e) {
                    errorReason = "Interrupted";
                    completionHandler.onComplete(false, errorReason);
                    return;
                }
            }
            if (pollingEvent == null || pollingEvent.getPlugindata() == null || pollingEvent.getPlugindata().getData() == null ||
                    !"ok".equalsIgnoreCase(pollingEvent.getPlugindata().getData().getResult())) {
                // Failure
                errorReason = "Server error";
                Log.w(Const.LOG_TAG, "Wrong server response: " + pollingEvent.toString());
                completionHandler.onComplete(false, errorReason);
                return;
            }
            // Success
        }
    }

    private void attachDataChannel(final DataChannel channel) {
        if (channel == null) {
            return;
        }
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {
                Log.d(Const.LOG_TAG, "Textroom plugin: dataChannel - onBufferedAmountChange=" + l);
            }

            @Override
            public void onStateChange() {
                Log.d(Const.LOG_TAG, "Textroom plugin: dataChannel (" + channel.label() + ") state=" + channel.state());
                if (channel.state() == DataChannel.State.OPEN && dcOpened.compareAndSet(false, true)) {
                    JanusTextRoomPlugin.this.dataChannel = channel;
                    if (dcCompletionHandler != null) {
                        dcCompletionHandler.onComplete(true, null);
                    }
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                String message = Utils.byteBufferToString(buffer.data);
                Log.d(Const.LOG_TAG, "Textroom plugin: got message from DataChannel: " + message);

                try {
                    JSONObject jsonObject = new JSONObject(message);
                    String type = jsonObject.optString("textroom");
                    if ("join".equalsIgnoreCase(type)) {
                        if (!checkJoined()) {
                            return;
                        }
                        Log.d(Const.LOG_TAG, "Remote control agent connected, starting sharing");
                        String username = jsonObject.optString("username");
                        if (dcEventListener != null) {
                            handler.post(() -> dcEventListener.onStartSharing(username));
                        }
                    } else if ("message".equalsIgnoreCase(type)) {
                        if (!checkJoined()) {
                            return;
                        }
                        String text = jsonObject.optString("text");
                        if (text.startsWith("ping,")) {
                            String[] parts = text.split(",");
                            sendMessage("pong," + parts[1], false);
                            if (dcEventListener != null) {
                                handler.post(() -> dcEventListener.onPing());
                            }
                        } else if (text.startsWith("pong,")) {
                            // Echo from our response, do nothing
                        } else if (dcEventListener != null) {
                            Log.d(Const.LOG_TAG, "Dispatching message: " + text);
                            handler.post(() -> dcEventListener.onRemoteControlEvent(text));
                        }
                    } else if ("leave".equalsIgnoreCase(type)) {
                        if (!checkJoined()) {
                            return;
                        }
                        Log.d(Const.LOG_TAG, "Remote control agent disconnected, stopping sharing");
                        if (dcEventListener != null) {
                            handler.post(() -> dcEventListener.onStopSharing());
                        }
                    } else if ("success".equalsIgnoreCase(type)) {
                        JSONArray list = jsonObject.optJSONArray("list");
                        if (list != null) {
                            return;
                        }
                        synchronized (dcResultLock) {
                            dcResult = true;
                            dcResultReady = true;
                            dcResultLock.notifyAll();
                        }
                    } else if ("error".equalsIgnoreCase(type)) {
                        synchronized (dcResultLock) {
                            dcResult = false;
                            dcResultReady = true;
                            dcResultLock.notifyAll();
                        }
                    } else {
                        Log.d(Const.LOG_TAG, "Ignoring this message");
                    }

                } catch (JSONException e) {
                    Log.w(Const.LOG_TAG, "Failed to parse JSON, ignoring!");
                }
            }
        });
        if (channel.state() == DataChannel.State.OPEN && dcOpened.compareAndSet(false, true)) {
            JanusTextRoomPlugin.this.dataChannel = channel;
            if (dcCompletionHandler != null) {
                dcCompletionHandler.onComplete(true, null);
            }
        }
    }

    @Override
    public void onWebRtcUp(final Context context) {
        Log.i(Const.LOG_TAG, "WebRTC is up! Ensuring DataChannel is opened...");
        if (!dcOpened.get() && peerConnection != null) {
            new Thread(() -> {
                if (!dcOpened.get() && peerConnection != null) {
                    DataChannel.Init init = new DataChannel.Init();
                    DataChannel localDc = peerConnection.createDataChannel("JanusDataChannel", init);
                    attachDataChannel(localDc);
                }
            }).start();
        }
    }

    private void sendToDataChannel(String message) {
        if (dataChannel == null) {
            Log.e(Const.LOG_TAG, "sendToDataChannel called while dataChannel is null!");
            return;
        }
        Log.d(Const.LOG_TAG, "Sending message: " + message);
        ByteBuffer data = Utils.stringToByteBuffer(message);
        DataChannel.Buffer buffer = new DataChannel.Buffer(data, false);
        dataChannel.send(buffer);
    }

    public int createRoom(String roomId, String password) {
        this.roomId = roomId;
        this.password = password;

        String createMessage = "{\"textroom\":\"create\",\"is_private\":false,\"permanent\":false,\"transaction\":\"" + Utils.generateTransactionId() +
                "\",\"room\":\"" + roomId + "\",\"pin\":\"" + password + "\"}";

        synchronized (dcResultLock) {
            dcResult = false;
            dcResultReady = false;
            sendToDataChannel(createMessage);
            try {
                if (!dcResultReady) {
                    dcResultLock.wait(15000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                errorReason = "Interrupted";
                return Const.INTERNAL_ERROR;
            }
            if (!dcResultReady || !dcResult) {
                errorReason = "Failed to create a text room";
                return Const.SERVER_ERROR;
            }
        }
        return Const.SUCCESS;
    }

    public int joinRoom(String username, String displayName) {
        String joinMessage = "{\"textroom\":\"join\",\"room\":\"" + roomId + "\",\"username\":\"" + username + "\",\"display\":\"" + displayName +
                "\",\"pin\":\"" + password + "\", \"transaction\":\"" + Utils.generateTransactionId() + "\"}";

        synchronized (dcResultLock) {
            dcResult = false;
            dcResultReady = false;
            sendToDataChannel(joinMessage);
            try {
                if (!dcResultReady) {
                    dcResultLock.wait(15000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                errorReason = "Interrupted";
                return Const.INTERNAL_ERROR;
            }
            if (!dcResultReady || !dcResult) {
                errorReason = "Failed to join a text room";
                return Const.SERVER_ERROR;
            }
        }
        joined = true;
        return Const.SUCCESS;
    }

    public int sendMessage(String message, boolean ack) {
        String sendCommand = "{\"textroom\":\"message\",\"room\":\"" + roomId + "\",\"text\":\"" + message + "\",\"ack\":" + ack + "," +
                "\"transaction\":\"" + Utils.generateTransactionId() + "\"}";

        if (ack) {
            synchronized (dcResultLock) {
                dcResult = false;
                dcResultReady = false;
                sendToDataChannel(sendCommand);
                try {
                    if (!dcResultReady) {
                        dcResultLock.wait(15000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    errorReason = "Interrupted";
                    return Const.INTERNAL_ERROR;
                }
                if (!dcResultReady || !dcResult) {
                    errorReason = "Failed to send a message to room";
                    return Const.SERVER_ERROR;
                }
            }
        } else {
            sendToDataChannel(sendCommand);
        }
        return Const.SUCCESS;
    }

    @Override
    public int destroy() {
 /*       if (dataChannel != null) {
            String destroyMessage = "{\"textroom\":\"destroy\",\"room\":\"" + roomId + "\",\"permanent\":false,\"transaction\":\"" + Utils.generateTransactionId() + "\"}";
            sendToDataChannel(destroyMessage);
        }*/
        // DataChannel may be broken, so we destroy using HTTP
        destroyViaHttp();
        return Const.SUCCESS;
    }

    private int destroyViaHttp() {
        JanusMessageRequest destroyRequest = new JanusMessageRequest(secret, "message", sessionId, getHandleId());
        destroyRequest.setBody(new JanusMessageRequest.Body("destroy", null, roomId));
        destroyRequest.generateTransactionId();

        Response<JanusResponse> response = ServerApiHelper.execute(apiInstance.sendMessage(sessionId, getHandleId(), destroyRequest), "destroy textroom");
        if (response == null) {
            errorReason = "Network error";
            return Const.NETWORK_ERROR;
        }
        if (response.body() == null || !response.body().getJanus().equalsIgnoreCase("success")) {
            errorReason = "Server error";
            Log.w(Const.LOG_TAG, "Wrong server response: " + response.body().toString());
            return Const.SERVER_ERROR;
        }
        return Const.SUCCESS;
    }
}

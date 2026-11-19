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

package net.majorkernelpanic.streaming.rtp;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An InputStream that uses data from a MediaCodec.
 * The purpose of this class is to interface existing RTP packetizers of
 * libstreaming with the new MediaCodec API. This class is not thread safe !  
 */
@SuppressLint("NewApi")
public class MediaCodecInputStream extends InputStream {

	public final String TAG = "MediaCodecInputStream"; 

	private MediaCodec mMediaCodec = null;
	private BufferInfo mBufferInfo = new BufferInfo();
	private ByteBuffer[] mBuffers = null;
	private ByteBuffer mBuffer = null;
	private int mIndex = -1;
	private boolean mClosed = false;

	private H264Packetizer mH264Packetizer;

	public MediaFormat mMediaFormat;

	public MediaCodecInputStream(MediaCodec mediaCodec) {
		mMediaCodec = mediaCodec;
		mBuffers = mMediaCodec.getOutputBuffers();
	}

	@Override
	public void close() {
		mClosed = true;
	}

	@Override
	public int read() throws IOException {
		return 0;
	}

	@Override
	@SuppressLint("WrongConstant")
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int min = 0;

		try {
			if (mBuffer==null) {
				while (!Thread.interrupted() && !mClosed) {
					mIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 500000);
					if (mIndex>=0 ){
						//Log.d(TAG,"Index: "+mIndex+" Time: "+mBufferInfo.presentationTimeUs+" size: "+mBufferInfo.size);
						mBuffer = mBuffers[mIndex];
						mBuffer.position(0);
						break;
					} else if (mIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						mBuffers = mMediaCodec.getOutputBuffers();
					} else if (mIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						mMediaFormat = mMediaCodec.getOutputFormat();

						ByteBuffer spsBuffer = mMediaFormat.getByteBuffer("csd-0");
						ByteBuffer ppsBuffer = mMediaFormat.getByteBuffer("csd-1");
						byte[] sps = null;
						byte[] pps = null;
						if (spsBuffer != null && ppsBuffer != null) {
							spsBuffer.position(4);
							ppsBuffer.position(4);
							sps = new byte[spsBuffer.remaining()];
							pps = new byte[ppsBuffer.remaining()];
							spsBuffer.get(sps);
							ppsBuffer.get(pps);
						} else if (spsBuffer != null) {
							// Combined csd-0 containing 00 00 00 01 SPS 00 00 00 01 PPS
							spsBuffer.position(0);
							byte[] all = new byte[spsBuffer.remaining()];
							spsBuffer.get(all);
							int ppsStart = -1;
							for (int i = 4; i < all.length - 4; i++) {
								if (all[i] == 0 && all[i+1] == 0 && all[i+2] == 0 && all[i+3] == 1) {
									ppsStart = i;
									break;
								}
							}
							if (ppsStart > 4) {
								sps = new byte[ppsStart - 4];
								System.arraycopy(all, 4, sps, 0, sps.length);
								pps = new byte[all.length - (ppsStart + 4)];
								System.arraycopy(all, ppsStart + 4, pps, 0, pps.length);
							}
						}

						if (mH264Packetizer != null && sps != null && pps != null) {
							mH264Packetizer.setStreamParameters(pps, sps);
						}

						Log.i(TAG,mMediaFormat.toString());
					} else if (mIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
						Log.v(TAG,"No buffer available...");
						//return 0;
					} else {
						Log.e(TAG,"Message: "+mIndex);
						//return 0;
					}
				}			
			}
			
			if (mClosed) throw new IOException("This InputStream was closed");
			
			min = length < mBufferInfo.size - mBuffer.position() ? length : mBufferInfo.size - mBuffer.position(); 
			mBuffer.get(buffer, offset, min);
			if (mBuffer.position()>=mBufferInfo.size) {
				mMediaCodec.releaseOutputBuffer(mIndex, false);
				mBuffer = null;
			}
			
		} catch (RuntimeException e) {
			e.printStackTrace();
		}

		return min;
	}

	public void setH264Packetizer(H264Packetizer packetizer) {
		mH264Packetizer = packetizer;
	}

	public int available() {
		if (mBuffer != null) 
			return mBufferInfo.size - mBuffer.position();
		else 
			return 0;
	}

	public BufferInfo getLastBufferInfo() {
		return mBufferInfo;
	}

}

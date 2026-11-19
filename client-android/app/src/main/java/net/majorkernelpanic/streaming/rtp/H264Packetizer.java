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

import java.io.IOException;
import android.annotation.SuppressLint;
import android.util.Log;

/**
 * 
 *   RFC 3984.
 *   
 *   H.264 streaming over RTP.
 *   
 *   Must be fed with an InputStream containing H.264 NAL units preceded by their length (4 bytes).
 *   The stream must start with mpeg4 or 3gpp header, it will be skipped.
 *   
 */
public class H264Packetizer extends AbstractPacketizer implements Runnable {

	public final static String TAG = "H264Packetizer";

	private Thread t = null;
	private int naluLength = 0;
	private long delay = 0, oldtime = 0;
	private Statistics stats = new Statistics();
	private byte[] sps = null, pps = null, stapa = null;
	byte[] header = new byte[5];	
	private int count = 0;
	private int streamType = 1;


	public H264Packetizer() {
		super();
		socket.setClockFrequency(90000);
	}

	public void start() {
		if (t == null) {
			t = new Thread(this);
			t.start();
		}
	}

	public void stop() {
		if (t != null) {
			try {
				is.close();
			} catch (IOException e) {}
			t.interrupt();
			try {
				t.join();
			} catch (InterruptedException e) {}
			t = null;
		}
		sps = null;
		pps = null;
		stapa = null;
	}

	public void setStreamParameters(byte[] pps, byte[] sps) {
		this.pps = pps;
		this.sps = sps;

		// A STAP-A NAL (NAL type 24) containing the sps and pps of the stream
		if (pps != null && sps != null) {
			// STAP-A NAL header + NALU 1 (SPS) size + NALU 2 (PPS) size = 5 bytes
			stapa = new byte[sps.length + pps.length + 5];

			// STAP-A NAL header is 24
			stapa[0] = 24;

			// Write NALU 1 size into the array (NALU 1 is the SPS).
			stapa[1] = (byte) (sps.length >> 8);
			stapa[2] = (byte) (sps.length & 0xFF);

			// Write NALU 2 size into the array (NALU 2 is the PPS).
			stapa[sps.length + 3] = (byte) (pps.length >> 8);
			stapa[sps.length + 4] = (byte) (pps.length & 0xFF);

			// Write NALU 1 into the array, then write NALU 2 into the array.
			System.arraycopy(sps, 0, stapa, 3, sps.length);
			System.arraycopy(pps, 0, stapa, 5 + sps.length, pps.length);
		}
	}	

	public void run() {
		long duration = 0;
		Log.d(TAG,"H264 packetizer started !");
		stats.reset();
		count = 0;

		if (is instanceof MediaCodecInputStream) {
			streamType = 1;
			socket.setCacheSize(0);
		} else {
			streamType = 0;	
			socket.setCacheSize(400);
		}

		try {
			while (!Thread.interrupted()) {

				oldtime = System.nanoTime();
				// We read a NAL units from the input stream and we send them
				send();
				// We measure how long it took to receive NAL units from the phone
				duration = System.nanoTime() - oldtime;

				stats.push(duration);
				// Computes the average duration of a NAL unit
				delay = stats.average();
				//Log.d(TAG,"duration: "+duration/1000000+" delay: "+delay/1000000);

			}
		} catch (IOException e) {
		} catch (InterruptedException e) {}

		Log.d(TAG,"H264 packetizer stopped !");

	}

	/**
	 * Reads a NAL unit in the FIFO and sends it.
	 * If it is too big, we split it in FU-A units (RFC 3984).
	 */
	@SuppressLint("NewApi")
	private void send() throws IOException, InterruptedException {
		int sum = 1, len = 0, type;

		if (streamType == 0) {
			// NAL units are preceeded by their length, we parse the length
			fill(header,0,5);
			ts += delay;
			naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
			if (naluLength>100000 || naluLength<0) resync();
		} else if (streamType == 1) {
			// NAL units are preceeded with 0x00000001
			fill(header,0,5);
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
			//ts += delay;
			naluLength = is.available()+1;
			if (!(header[0]==0 && header[1]==0 && header[2]==0)) {
				// Turns out, the NAL units are not preceeded with 0x00000001
				Log.e(TAG, "NAL units are not preceeded by 0x00000001");
				streamType = 2; 
				return;
			}
		} else {
			// Nothing preceededs the NAL units
			fill(header,0,1);
			header[4] = header[0];
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
			//ts += delay;
			naluLength = is.available()+1;
		}

		// Read the entire MediaCodec output buffer and split into individual Annex-B NAL units
		// (handles combined SPS+PPS and SPS+PPS+IDR buffers emitted by Android c2/MediaTek encoders)
		byte[] frame = new byte[naluLength];
		frame[0] = header[4];
		if (naluLength > 1) {
			fill(frame, 1, naluLength - 1);
		}

		java.util.List<byte[]> nals = splitAnnexBNals(frame);
		for (int idx = 0; idx < nals.size(); idx++) {
			byte[] nal = nals.get(idx);
			if (nal == null || nal.length == 0) continue;
			int nalType = nal[0] & 0x1F;

			if (nalType == 7) {
				sps = nal;
				continue;
			}
			if (nalType == 8) {
				pps = nal;
				continue;
			}

			// Before every IDR keyframe (type 5), send clean RFC 6184 Single NAL SPS (M=0) and PPS (M=0)
			if (nalType == 5 && sps != null && pps != null) {
				sendSingleNal(sps, false);
				sendSingleNal(pps, false);
			}

			boolean isLastNal = (idx == nals.size() - 1);
			sendNalRtp(nal, isLastNal);
		}
	}

	private java.util.List<byte[]> splitAnnexBNals(byte[] data) {
		java.util.List<byte[]> result = new java.util.ArrayList<>();
		int start = 0;
		int len = data.length;
		int i = 0;
		while (i < len) {
			int scLen = 0;
			if (i + 3 < len && data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
				scLen = 4;
			} else if (i + 2 < len && data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) {
				scLen = 3;
			}
			if (scLen > 0) {
				if (i > start) {
					byte[] nal = new byte[i - start];
					System.arraycopy(data, start, nal, 0, nal.length);
					result.add(nal);
				}
				i += scLen;
				start = i;
			} else {
				i++;
			}
		}
		if (len > start) {
			byte[] nal = new byte[len - start];
			System.arraycopy(data, start, nal, 0, nal.length);
			result.add(nal);
		}
		return result;
	}

	private void sendSingleNal(byte[] nal, boolean mark) throws IOException, InterruptedException {
		buffer = socket.requestBuffer();
		System.arraycopy(nal, 0, buffer, rtphl, nal.length);
		socket.updateTimestamp(ts);
		if (mark) {
			socket.markNextPacket();
		}
		super.send(nal.length + rtphl);
	}

	private void sendNalRtp(byte[] nal, boolean markLast) throws IOException, InterruptedException {
		int nalLen = nal.length;
		if (nalLen <= MAXPACKETSIZE - rtphl - 2) {
			sendSingleNal(nal, markLast);
		} else {
			byte fuIndicator = (byte) (((nal[0] & 0x60) & 0xFF) + 28);
			byte fuHeader = (byte) ((nal[0] & 0x1F) | 0x80); // Start bit set
			int offset = 1;
			while (offset < nalLen) {
				int chunk = Math.min(nalLen - offset, MAXPACKETSIZE - rtphl - 2);
				boolean isLast = (offset + chunk >= nalLen);
				buffer = socket.requestBuffer();
				buffer[rtphl] = fuIndicator;
				buffer[rtphl + 1] = isLast ? (byte) (fuHeader | 0x40) : fuHeader;
				System.arraycopy(nal, offset, buffer, rtphl + 2, chunk);
				socket.updateTimestamp(ts);
				if (isLast && markLast) {
					socket.markNextPacket();
				}
				super.send(chunk + rtphl + 2);
				offset += chunk;
				fuHeader = (byte) (fuHeader & 0x7F); // Clear start bit
			}
		}
	}

	private int fill(byte[] buffer, int offset,int length) throws IOException {
		int sum = 0, len;
		while (sum<length) {
			len = is.read(buffer, offset+sum, length-sum);
			if (len<0) {
				throw new IOException("End of stream");
			}
			else sum+=len;
		}
		return sum;
	}

	private void resync() throws IOException {
		int type;

		Log.e(TAG,"Packetizer out of sync ! Let's try to fix that...(NAL length: "+naluLength+")");

		while (true) {

			header[0] = header[1];
			header[1] = header[2];
			header[2] = header[3];
			header[3] = header[4];
			header[4] = (byte) is.read();

			type = header[4]&0x1F;

			if (type == 5 || type == 1) {
				naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
				if (naluLength>0 && naluLength<100000) {
					oldtime = System.nanoTime();
					Log.e(TAG,"A NAL unit may have been found in the bit stream !");
					break;
				}
				if (naluLength==0) {
					Log.e(TAG,"NAL unit with NULL size found...");
				} else if (header[3]==0xFF && header[2]==0xFF && header[1]==0xFF && header[0]==0xFF) {
					Log.e(TAG,"NAL unit with 0xFFFFFFFF size found...");
				}
			}

		}

	}

}

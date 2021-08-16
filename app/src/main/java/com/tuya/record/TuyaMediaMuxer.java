package com.tuya.record;


import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;


import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TuyaMediaMuxer implements TuyaVideoEncoder.Callback, TuyaAudioEncoder.Callback {
    private static final String TAG = "TuyaMediaMuxer";

    private TuyaVideoEncoder tuyaVideoEncoder;
    private TuyaAudioEncoder tuyaAudioEncoder;
    private MediaMuxer       mediaMuxer;


    private        int     audioTrackIndex = -1;
    private        int     videoTrackIndex = -1;
    private        boolean isVideoAdd      = false;
    private        boolean isAudioAdd      = false;
    private static int     AUDIO_ONLY      = 0x1;
    private static int     VIDEO_ONLY      = 0x2;
    private        int     recordMode;

    private boolean isMediaMuxerStart = false;

    private boolean isStartRecord;
    private boolean isKeyFrameArrived;

    public TuyaMediaMuxer() {

    }

    public synchronized int startRecord(
            boolean audio,
            int samplesRate,
            int channelCount,
            int audioBitrate,
            boolean video,
            int width,
            int height,
            int fps,
            int videoBitrate,
            String recrodFile) {
        Log.d(TAG, "startRecord enter.");

        JSONObject desc = new JSONObject();
        try {
            JSONObject aud = new JSONObject();
            JSONObject vid = new JSONObject();
            aud.put("enable", audio);
            aud.put("samplesRate", samplesRate);
            aud.put("channelCount", audioBitrate);
            aud.put("audioBitrate", audioBitrate);
            vid.put("enable", video);
            vid.put("width", width);
            vid.put("height", height);
            vid.put("fps", fps);
            vid.put("videoBitrate", videoBitrate);
            desc.put("audio", aud);
            desc.put("video", vid);
            desc.put("file", recrodFile);
            Log.d(TAG, "start recrod desc :" + desc.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        this.recordMode = 0;
        if (audio) recordMode += AUDIO_ONLY;
        if (video) recordMode += VIDEO_ONLY;
        try {
            mediaMuxer = new MediaMuxer(recrodFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
        if (audio) {
            TuyaAudioEncoder.Settings audioSettings = new TuyaAudioEncoder.Settings(
                    samplesRate, channelCount, audioBitrate, AudioFormat.ENCODING_PCM_16BIT);
            tuyaAudioEncoder = new TuyaAudioEncoder(audioSettings, this);
        }

        if (video) {
            TuyaVideoEncoder.Settings videoSettins = new TuyaVideoEncoder.Settings(
                    width, height, 3000, videoBitrate, fps);
            tuyaVideoEncoder = new TuyaVideoEncoder(videoSettins, TuyaVideoEncoder.MimeType.H264, true, this);
        }
        isStartRecord = true;
        isKeyFrameArrived = false;
        Log.e(TAG, "startRecord leave.");

        return 0;
    }

    public synchronized int stopRecord() {
        isKeyFrameArrived = false;
        if (!isStartRecord) {
            return 0;
        }
        isStartRecord = false;

        if (tuyaVideoEncoder != null) {
            tuyaVideoEncoder.encodeEndOfStream();
            tuyaVideoEncoder.release();
            tuyaVideoEncoder = null;
        }

        if (tuyaAudioEncoder != null) {
            tuyaAudioEncoder.encodeEndOfStream();
            tuyaAudioEncoder.release();

            tuyaAudioEncoder = null;
        }

        isAudioAdd = false;
        isVideoAdd = false;
        if (isMediaMuxerStart) {
            try {
                mediaMuxer.stop();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }

            try {
                mediaMuxer.release();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
        isMediaMuxerStart = false;

        return 0;
    }


    public void writeVideoFrame(TuyaVideoEncoder.VideoFrame frame) {
        if ((recordMode & VIDEO_ONLY) != 0) {
            if (tuyaVideoEncoder != null) {
                if (tuyaVideoEncoder.isEncodeReady() != true) {
                    tuyaVideoEncoder.initEncode();
                }
                if (isStartRecord) {
                    tuyaVideoEncoder.encode(frame, false);
                }

            }
        }

    }

    public void writeAudioSample(TuyaAudioEncoder.AudioSamples audioSamples) {
        if ((recordMode & AUDIO_ONLY) != 0) {
            if (tuyaAudioEncoder != null) {
                if (tuyaAudioEncoder.isEncodeReady() != true) {
                    tuyaAudioEncoder.initEncode();
                }
                if (isStartRecord) {
                    tuyaAudioEncoder.encode(audioSamples);
                }
            }
        }
    }


    @Override
    public synchronized void onAudioSample(ByteBuffer outBuf, MediaCodec.BufferInfo bufferInfo) {
        if ((isMediaMuxerStart) && (isStartRecord)) {
            if((!isKeyFrameArrived) && (recordMode == (VIDEO_ONLY+AUDIO_ONLY))) {
                Log.e(TAG, "Wait video key frame to write.");
                return;
            }
            //Log.e(TAG, "Write audio ts " + bufferInfo.presentationTimeUs);
            mediaMuxer.writeSampleData(audioTrackIndex, outBuf, bufferInfo);
        }

    }

    @Override
    public synchronized void onAddAudioTrack(MediaFormat format) {
        Log.d(TAG, "onAddAudioTrack " + format.toString());
        if (mediaMuxer != null) {
            audioTrackIndex = mediaMuxer.addTrack(format);
            isAudioAdd = true;

        }

        if (!isMediaMuxerStart && (((isVideoAdd) && (isAudioAdd)) ||
                ((recordMode == AUDIO_ONLY) && !isVideoAdd))
        ) {
            isMediaMuxerStart = true;
            mediaMuxer.start();
        }
    }

    @Override
    public synchronized void onVideoFrame(ByteBuffer frame, MediaCodec.BufferInfo bufferInfo) {
        if ((isMediaMuxerStart) && (isStartRecord)) {
            if (!isKeyFrameArrived) {
                tuyaVideoEncoder.requestKeyFrame(10L);
                isKeyFrameArrived = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
            }
            //Log.e(TAG, "Write video ts " + bufferInfo.presentationTimeUs + " key " + bufferInfo.flags);
            mediaMuxer.writeSampleData(videoTrackIndex, frame, bufferInfo);
        }

    }

    @Override
    public synchronized void onAddVideoTrack(MediaFormat format) {
        Log.d(TAG, "onAddVideoTrack " + format.toString());

        if (mediaMuxer != null) {
            videoTrackIndex = mediaMuxer.addTrack(format);
            isVideoAdd = true;

        }
        if (!isMediaMuxerStart && (((isVideoAdd) && (isAudioAdd)) ||
                ((recordMode == VIDEO_ONLY) && !isAudioAdd))
        ) {
            isMediaMuxerStart = true;
            mediaMuxer.start();
        }

    }

}

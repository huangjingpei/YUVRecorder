package com.tuya.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;


import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class TuyaVideoEncoder {
    private static final String TAG = "TuyaVideoEncoder";

    private MediaCodec                codec;
    private TuyaVideoEncoder.Settings settings;
    private TuyaVideoEncoder.Callback callback;
    private ArrayList<Integer> supportColorFormatList;

    private volatile boolean isEncodeReady;
    private MimeType                  mimeType;
    private ByteBuffer[] outputBuffers;
    private Thread outputThread;

    private volatile boolean running;
    private boolean endOfStream;
    private long videoTimestampBase;

    private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
    private static final int DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = 10000;


    /**
     * Contains audio sample information.
     */
    public static class VideoFrame {
        private final int width;
        private final int height;
        private final int framerate;
        private final int pixelFmt;
//        private final ByteBuffer yPlanner;
//        private final ByteBuffer uPlanner;
//        private final ByteBuffer vPlanner;
        private final long timestampNs;
        private final byte[] data;


//        public VideoFrame(ByteBuffer y, ByteBuffer u, ByteBuffer v,
//                int pixelFmt,
//                int width, int height, int framerate, long timestampNs) {
//            this.yPlanner = y;
//            this.uPlanner = u;
//            this.vPlanner = v;
//            this.pixelFmt = pixelFmt;
//            this.width = width;
//            this.height = height;
//            this.framerate = framerate;
//            this.timestampNs = timestampNs;
//        }

        public VideoFrame(byte[] data,
                          int pixelFmt,
                          int width, int height, int framerate, long timestampNs) {
            this.data = data;
            this.pixelFmt = pixelFmt;
            this.width = width;
            this.height = height;
            this.framerate = framerate;
            this.timestampNs = timestampNs;
        }

//        public ByteBuffer getyPlanner() {
//            return yPlanner;
//        }
//
//        public ByteBuffer getuPlanner() {
//            return uPlanner;
//        }
//
//        public ByteBuffer getvPlanner() {
//            return vPlanner;
//        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getFramerate() {
            return framerate;
        }

        public long getTimestampNs() {
            return timestampNs;
        }

        public int getPixelFmt() {
            return pixelFmt;
        }

        public byte[] getData() {
            return data;
        }
    }

    public TuyaVideoEncoder(Settings setting, MimeType mime, boolean useSurface, Callback callback) {
        this.settings = setting;
        this.callback = callback;
        this.mimeType = mime;
        this.isEncodeReady = false;
        this.endOfStream = false;
        this.videoTimestampBase = 0L;


    }
    public VideoCodecStatus initEncode() {
        if (codec != null) {
            return VideoCodecStatus.OK;
        }
        videoTimestampBase = 0L;
        Log.i(TAG, "initVideoParam width: "+settings.width+" height: "+settings.height+ " fps: "+ settings.fps + " IframeInterval: " +
                settings.keyFrameIntervalSec + " bitrate: " + settings.bitrate );
        supportColorFormatList = new ArrayList<>();

        MediaCodecInfo vCodecInfo = selectCodec(mimeType.mimeType());
        if (vCodecInfo == null) {
            Log.e(TAG, "initVideoParam Unable to find an appropriate codec for " + mimeType.mimeType());
            return VideoCodecStatus.ERR_PARAMETER;
        }

        selectColorFormat(vCodecInfo, mimeType.mimeType());


        Log.i(TAG, "initVideoParam found video codec: " + vCodecInfo.getName());
        //根据MIME格式,选择颜色格式
        int colorFormat = 0;
        Integer fmt = selectColorFormat(ENCODER_COLOR_FORMATS, vCodecInfo.getCapabilitiesForType(mimeType.mimeType()));
        if (fmt == null) {
            Log.e(TAG, "initVideoParam Unable to find an appropriate colorFormat " + mimeType.mimeType());
            return VideoCodecStatus.ERR_PARAMETER;
        }

        colorFormat = fmt.intValue();
        //colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

        MediaFormat format = MediaFormat.createVideoFormat(mimeType.mimeType(), settings.width, settings.height);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameIntervalSec);

        Log.i(TAG, "initVideoParam video format: " + format.toString());

        try {
            codec = MediaCodec.createByCodecName(vCodecInfo.getName());
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Cannot create media encoder " + vCodecInfo.getName());
            return VideoCodecStatus.FALLBACK_SOFTWARE;
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        outputBuffers = codec.getOutputBuffers();

        running = true;
        outputThread = createOutputThread();
        outputThread.start();
        isEncodeReady = true;
        return VideoCodecStatus.OK;
    }

    public boolean isEncodeReady() {
        return isEncodeReady;
    }

    public VideoCodecStatus encode(VideoFrame videoFrame, boolean eos) {
        if (codec == null) {
            return VideoCodecStatus.UNINITIALIZED;
        }
        final int frameWidth = videoFrame.getWidth();
        final int frameHeight = videoFrame.getHeight();
        int bufferSize = frameWidth * frameHeight * 3 / 2;
        if (frameWidth != settings.width || frameHeight != settings.height) {
            VideoCodecStatus status = resetCodec(frameWidth, frameHeight);
            if (status != VideoCodecStatus.OK) {
                return status;
            }
        }

        VideoCodecStatus ret = encodeByteBuffer(videoFrame, bufferSize);
        return ret;
    }


    public VideoCodecStatus encodeEndOfStream() {
        // Frame timestamp rounded to the nearest microsecond
        if (codec == null) {
            return VideoCodecStatus.OK;
        }
        try {
            //codec.signalEndOfInputStream();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }

        endOfStream = true;


        long presentationTimestampUs = System.currentTimeMillis()*1000;

        // No timeout.  Don't block for an input buffer, drop frames if the encoder falls behind.
        int index;
        try {
            index = codec.dequeueInputBuffer(0 /* timeout */);
        } catch (IllegalStateException e) {
            Log.e(TAG, "dequeueInputBuffer failed", e);
            return VideoCodecStatus.ERROR;
        }

        if (index == -1) {
            // Encoder is falling behind.  No input buffers available.  Drop the frame.
            Log.d(TAG, "Dropped frame, no input buffers available");
            return VideoCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
        }

        ByteBuffer buffer;
        try {
            buffer = codec.getInputBuffers()[index];
        } catch (IllegalStateException e) {
            Log.e(TAG, "getInputBuffers failed", e);
            return VideoCodecStatus.ERROR;
        }


        try {
            codec.queueInputBuffer(
                    index, 0 /* offset */, 0, presentationTimestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } catch (IllegalStateException e) {
            Log.e(TAG, "queueInputBuffer failed", e);
            // IllegalStateException thrown when the codec is in the wrong state.
            return VideoCodecStatus.ERROR;
        }
        return VideoCodecStatus.OK;
    }


    private VideoCodecStatus encodeByteBuffer(
            VideoFrame videoFrame, int bufferSize) {
        // Frame timestamp rounded to the nearest microsecond.

        long presentationTimestampUs = System.currentTimeMillis()*1000;
        if (videoTimestampBase == 0L) {
            videoTimestampBase = presentationTimestampUs;
        }

        long relativeTS = presentationTimestampUs - videoTimestampBase;


        // No timeout.  Don't block for an input buffer, drop frames if the encoder falls behind.
        int index;
        try {
            index = codec.dequeueInputBuffer(0 /* timeout */);
        } catch (IllegalStateException e) {
            Log.e(TAG, "dequeueInputBuffer failed", e);
            return VideoCodecStatus.ERROR;
        }

        if (index == -1) {
            // Encoder is falling behind.  No input buffers available.  Drop the frame.
            Log.d(TAG, "Dropped frame, no input buffers available");
            return VideoCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
        }

        ByteBuffer buffer;
        try {
            buffer = codec.getInputBuffers()[index];
        } catch (IllegalStateException e) {
            Log.e(TAG, "getInputBuffers failed", e);
            return VideoCodecStatus.ERROR;
        }

        fillInputBuffer(buffer, videoFrame);

        try {
            codec.queueInputBuffer(
                    index, 0 /* offset */, bufferSize, relativeTS, 0 /* flags */);
        } catch (IllegalStateException e) {
            Log.e(TAG, "queueInputBuffer failed", e);
            // IllegalStateException thrown when the codec is in the wrong state.
            return VideoCodecStatus.ERROR;
        }
        return VideoCodecStatus.OK;
    }


    private Thread createOutputThread() {
        return new Thread() {
            @Override
            public void run() {
                while (running) {
                    deliverEncodedImage();
                }
                Log.e(TAG, "createOutputThread enter");
                releaseCodecOnOutputThread();
                Log.e(TAG, "createOutputThread leave");
            }
        };
    }



    // Visible for testing.
    protected void deliverEncodedImage() {
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index = codec.dequeueOutputBuffer(info, DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US);
            if (index < 0) {
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat newformat = codec.getOutputFormat(); // API >= 16
                    callback.onAddVideoTrack(newformat);
                }

                return;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.i(TAG, "Recv Video Encoder BUFFER_FLAG_END_OF_STREAM" );
                running = false;

                return;
            }
            if (outputBuffers == null) {
                return;
            }
            ByteBuffer codecOutputBuffer = outputBuffers[index];
            codecOutputBuffer.position(info.offset);
            codecOutputBuffer.limit(info.offset + info.size);

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                Log.d(TAG, "Config frame generated. Offset: " + info.offset + ". Size: " + info.size);
                info.size = 0;
            } else {
                final boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                if (isKeyFrame) {
                    Log.d(TAG, "Sync frame generated");
                }

                final ByteBuffer frameBuffer;
                frameBuffer = codecOutputBuffer.slice();

                try {
                    codec.releaseOutputBuffer(index, false);
                } catch (Exception e) {
                    Log.e(TAG, "releaseOutputBuffer failed", e);
                }

                info.presentationTimeUs = System.currentTimeMillis()*1000;
                //Log.e(TAG, "video ts =====> " + info.presentationTimeUs);
                // TODO(mellem):  Set codec-specific info.
                if (!endOfStream) {
                    callback.onVideoFrame(frameBuffer, info);
                }
                // Note that the callback may have retained the image.

            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "deliverOutput failed", e);
            e.printStackTrace();

        }
    }

    private void releaseCodecOnOutputThread() {
        Log.e(TAG, "Releasing MediaCodec on output thread - video");
        Log.e(TAG, "Releasing MediaCodec on output thread");
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception e) {
                Log.e(TAG, "Media encoder stop failed", e);
            }
            try {
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Media encoder release failed", e);
                // Propagate exceptions caught during release back to the main thread.
            }
        }
        Log.e(TAG, "Release on output thread done - video");

        Log.d(TAG, "Release on output thread done");
    }

    private VideoCodecStatus resetCodec(int newWidth, int newHeight) {
        VideoCodecStatus status = release();
        if (status != VideoCodecStatus.OK) {
            return status;
        }
        settings.width = newWidth;
        settings.height = newHeight;
        return initEncode();
    }


    public VideoCodecStatus release() {
        VideoCodecStatus returnValue = VideoCodecStatus.OK;
        if (outputThread == null) {
            returnValue = VideoCodecStatus.OK;
        } else {
            // The outputThread actually stops and releases the codec once running is false.
            running = false;

            outputThread.interrupt();
            if (!joinUninterruptibly(outputThread, MEDIA_CODEC_RELEASE_TIMEOUT_MS)) {
                Log.e(TAG, "Media encoder release timeout");
                returnValue = VideoCodecStatus.TIMEOUT;
            } else {
                returnValue = VideoCodecStatus.OK;
            }
        }

        codec = null;
        outputBuffers = null;
        outputThread = null;

        return returnValue;

    }


    private boolean joinUninterruptibly(final Thread thread, long timeoutMs) {
        final long startTimeMs = SystemClock.elapsedRealtime();
        long timeRemainingMs = timeoutMs;
        boolean wasInterrupted = false;
        while (timeRemainingMs > 0) {
            try {
                thread.join(timeRemainingMs);
                break;
            } catch (InterruptedException e) {
                // Someone is asking us to return early at our convenience. We can't cancel this operation,
                // but we should preserve the information and pass it along.
                wasInterrupted = true;
                final long elapsedTimeMs = SystemClock.elapsedRealtime() - startTimeMs;
                timeRemainingMs = timeoutMs - elapsedTimeMs;
            }
        }
        // Pass interruption information along.
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    // Visible for testing.
    protected void fillInputBuffer(ByteBuffer buffer, VideoFrame frame) {
        if ((buffer != null) && (frame != null)) {
            //Log.e(TAG, "width " + frame.getWidth() + " height " + frame.getHeight());
            buffer.put(frame.getData());
        }
    }

    public void requestKeyFrame(long presentationTimestampNs) {
        // Ideally MediaCodec would honor BUFFER_FLAG_SYNC_FRAME so we could
        // indicate this in queueInputBuffer() below and guarantee _this_ frame
        // be encoded as a key frame, but sadly that flag is ignored.  Instead,
        // we request a key frame "soon".
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                codec.setParameters(b);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "requestKeyFrame failed", e);
            return;
        }
    }

    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }



    private void selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo
                .getCapabilitiesForType(mimeType);
        supportColorFormatList.clear();
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            Log.i(TAG, "selectColorFormat add supportColorFormatList color format: " + colorFormat);
            supportColorFormatList.add(colorFormat);
        }
    }



    private Integer selectColorFormat(
            int[] supportedColorFormats, MediaCodecInfo.CodecCapabilities capabilities) {
        for (int supportedColorFormat : supportedColorFormats) {
            for (int codecColorFormat : capabilities.colorFormats) {
                if (codecColorFormat == supportedColorFormat) {
                    return codecColorFormat;
                }
            }
        }
        return null;
    }

    private boolean codecSupportsType(MediaCodecInfo info, MimeType type) {
        for (String mimeType : info.getSupportedTypes()) {
            if (type.mimeType().equals(mimeType)) {
                return true;
            }
        }
        return false;
    }

    enum MimeType {
        H264("video/avc"),
        H265("video/hevc");

        private final String mimeType;

        private MimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        String mimeType() {
            return mimeType;
        }
    }

    public enum VideoCodecStatus {
        REQUEST_SLI(2),
        NO_OUTPUT(1),
        OK(0),
        ERROR(-1),
        LEVEL_EXCEEDED(-2),
        MEMORY(-3),
        ERR_PARAMETER(-4),
        ERR_SIZE(-5),
        TIMEOUT(-6),
        UNINITIALIZED(-7),
        ERR_REQUEST_SLI(-12),
        FALLBACK_SOFTWARE(-13),
        TARGET_BITRATE_OVERSHOOT(-14);

        private final int number;

        private VideoCodecStatus(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    static class Settings {
        public int width;
        public int height;
        public int keyFrameIntervalSec;
        public int bitrate;
        public int fps;

        public Settings(int width, int height, int keyFrameIntervalSec, int bitrate, int fps) {
            this.width = width;
            this.height = height;
            this.keyFrameIntervalSec = keyFrameIntervalSec;
            this.bitrate = bitrate;
            this.fps = fps;
        }
    }

    public interface Callback {
        public void onVideoFrame(ByteBuffer frame, MediaCodec.BufferInfo bufferInfo);

        public void onAddVideoTrack(MediaFormat format);
    }


    static final int COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m = 0x7FA30C04;
    // Color formats supported by hardware encoder - in order of preference.
    static final int[] ENCODER_COLOR_FORMATS = {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
            TuyaVideoEncoder.COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m};

}

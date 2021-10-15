package com.tuya.record;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;



import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class TuyaAudioEncoder {
    private static final String TAG = "TuyaAudioEncoder";

    private MediaCodec codec;
    private Settings settings;
    private     Callback           callback;
    private ArrayList<Integer> supportColorFormatList;

    private volatile boolean isEncodeReady;
    private ByteBuffer[] outputBuffers;
    private byte[] intputBuffer;

    private Thread outputThread;
    private volatile boolean running;
    private TuyaRingBuffer tuyaRingBuffer;
    private boolean endOfStream;
    private long audioTimestampBase;


    private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
    private static final int DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = 50000;


    /**
     * Contains audio sample information.
     */
    public static class AudioSamples {
        private final int audioFormat;
        private final int channelCount;
        private final int sampleRate;

        private final byte[] data;

        public AudioSamples(int audioFormat, int channelCount, int sampleRate, byte[] data) {
            this.audioFormat = audioFormat;
            this.channelCount = channelCount;
            this.sampleRate = sampleRate;
            this.data = data;
        }

        public int getAudioFormat() {
            return audioFormat;
        }

        public int getChannelCount() {
            return channelCount;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public byte[] getData() {
            return data;
        }
    }

    public TuyaAudioEncoder(Settings setting, Callback callback) {
        this.settings = setting;
        this.callback = callback;
        tuyaRingBuffer = new TuyaRingBuffer(1024*50);
        intputBuffer = new byte[2048];
        this.endOfStream = false;
        this.audioTimestampBase = 0L;
    }


    public AudioCodecStatus initEncode() {
        audioTimestampBase = 0L;
        MediaCodecInfo audioCodecInfo = selectCodec(MimeType.AAC.mimeType());
        if (audioCodecInfo == null) {
            Log.e(TAG, "initAudioParam Unable to find an appropriate codec for " + MimeType.AAC.mimeType());
            return AudioCodecStatus.UNINITIALIZED;
        }

        Log.i(TAG, "initAudioParam selected codec: " + audioCodecInfo.getName());
        MediaFormat format = MediaFormat.createAudioFormat(MimeType.AAC.mimeType(), settings.sampleRate, settings.channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_CHANNEL_MASK,
                (settings.channelCount == 2) ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO);
        //int bitRate = sampleRate * pcmFormat * channels ;//select bitrate
        format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, settings.channelCount);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, settings.sampleRate);
        Log.i(TAG, "initAudioParam audio format: " + format.toString());

        try {
            codec = MediaCodec.createEncoderByType(MimeType.AAC.mimeType());
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
        try {
            codec.start();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }

        outputBuffers = codec.getOutputBuffers();

        running = true;
        outputThread = createOutputThread();
        outputThread.start();
        isEncodeReady = true;
        return AudioCodecStatus.OK;

    }


    public boolean isEncodeReady() {
        return isEncodeReady;
    }

    public AudioCodecStatus encode(AudioSamples audioSamples) {

        if (codec == null) {
            return AudioCodecStatus.UNINITIALIZED;
        }
        final byte[] audioSampleBuffer = audioSamples.getData();
        final int audioSampleRate = audioSamples.getSampleRate();
        final int audioSampleChannelCount = audioSamples.getChannelCount();
        final int audioFormat = audioSamples.getAudioFormat();

        if (audioSampleRate != settings.sampleRate ||
                audioSampleChannelCount != settings.channelCount ||
                audioFormat != settings.audioFmt) {
            AudioCodecStatus status = resetCodec(audioSampleRate, audioSampleChannelCount, audioFormat);
            if (status != AudioCodecStatus.OK) {
                return status;
            }
        }

        final AudioCodecStatus returnValue;
        returnValue = encodeByteBuffer(audioSampleBuffer,  audioSampleChannelCount*audioSampleRate*audioFormat/8);
        return returnValue;
    }


    private AudioCodecStatus encodeByteBuffer(byte[] audioSampleBuffer, int bufferSize) {
        // Frame timestamp rounded to the nearest microsecond.

        tuyaRingBuffer.overrunPush(audioSampleBuffer);

        if (tuyaRingBuffer.sizeUsed() >= 2048) {
            tuyaRingBuffer.pop(intputBuffer);
        } else if (tuyaRingBuffer.sizeFree() <= 2048) {
            Log.e(TAG, "no memory for recording audio sample buffer." + tuyaRingBuffer.sizeFree());
            return AudioCodecStatus.MEMORY;
        } else {
            return AudioCodecStatus.MEMORY;
        }

        long presentationTimestampUs = System.currentTimeMillis()*1000;
        if (audioTimestampBase == 0L) {
            audioTimestampBase = presentationTimestampUs;
        }
        long relativeTS = presentationTimestampUs - audioTimestampBase;


        // No timeout.  Don't block for an input buffer, drop frames if the encoder falls behind.
        int index;
        try {
            index = codec.dequeueInputBuffer(0 /* timeout */);
        } catch (IllegalStateException e) {
            Log.e(TAG, "dequeueInputBuffer failed", e);
            return AudioCodecStatus.ERROR;
        }

        if (index == -1) {
            // Encoder is falling behind.  No input buffers available.  Drop the frame.
            Log.d(TAG, "Dropped frame, no input buffers available");
            return AudioCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
        }

        ByteBuffer buffer;
        try {
            buffer = codec.getInputBuffers()[index];
        } catch (IllegalStateException e) {
            Log.e(TAG, "getInputBuffers failed", e);
            return AudioCodecStatus.ERROR;
        }

        fillInputBuffer(buffer, intputBuffer);

        try {
            codec.queueInputBuffer(
                    index, 0 /* offset */, intputBuffer.length, relativeTS, 0 /* flags */);
        } catch (IllegalStateException e) {
            Log.e(TAG, "queueInputBuffer failed", e);
            // IllegalStateException thrown when the codec is in the wrong state.
            return AudioCodecStatus.ERROR;
        }
        return AudioCodecStatus.OK;
    }



    public AudioCodecStatus encodeEndOfStream() {
        if (codec == null) {
            return AudioCodecStatus.OK;
        }
        // Frame timestamp rounded to the nearest microsecond
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
            return AudioCodecStatus.ERROR;
        }

        if (index == -1) {
            // Encoder is falling behind.  No input buffers available.  Drop the frame.
            Log.d(TAG, "Dropped frame, no input buffers available");
            return AudioCodecStatus.NO_OUTPUT; // See webrtc bug 2887.
        }

        ByteBuffer buffer;
        try {
            buffer = codec.getInputBuffers()[index];
        } catch (IllegalStateException e) {
            Log.e(TAG, "getInputBuffers failed", e);
            return AudioCodecStatus.ERROR;
        }


        try {
            codec.queueInputBuffer(
                    index, 0 /* offset */, intputBuffer.length, presentationTimestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } catch (IllegalStateException e) {
            Log.e(TAG, "queueInputBuffer failed", e);
            // IllegalStateException thrown when the codec is in the wrong state.
            return AudioCodecStatus.ERROR;
        }
        return AudioCodecStatus.OK;
    }

    private void fillInputBuffer(ByteBuffer buffer, byte[] audioSampleBuffer) {
        buffer.put(audioSampleBuffer);
    }

    private AudioCodecStatus resetCodec(int audioSampleRate, int audioSampleChannelCount, int audioFormat) {
        AudioCodecStatus status = release();
        if (status != AudioCodecStatus.OK) {
            return status;
        }
        settings.sampleRate = audioSampleRate;
        settings.channelCount = audioSampleChannelCount;
        settings.audioFmt = audioFormat;
        return initEncode();
    }

    public AudioCodecStatus release() {
        AudioCodecStatus returnValue = AudioCodecStatus.OK;
        if (outputThread == null) {
            returnValue = AudioCodecStatus.OK;
        } else {
            // The outputThread actually stops and releases the codec once running is false.
            running = false;

            outputThread.interrupt();
            if (!joinUninterruptibly(outputThread, MEDIA_CODEC_RELEASE_TIMEOUT_MS)) {
                Log.e(TAG, "Media encoder release timeout");
                returnValue = AudioCodecStatus.TIMEOUT;
            } else {
                returnValue = AudioCodecStatus.OK;
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
                    callback.onAddAudioTrack(newformat);
                }

                return;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.i(TAG, "Recv Audio Encoder BUFFER_FLAG_END_OF_STREAM" );
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

                final ByteBuffer frameBuffer;
                frameBuffer = codecOutputBuffer.slice();


                try {
                    codec.releaseOutputBuffer(index, false);
                } catch (Exception e) {
                    Log.e(TAG, "releaseOutputBuffer failed", e);
                }

                //Log.e(TAG, "audio ts =====> " + info.presentationTimeUs);
                if (!endOfStream) {
                    callback.onAudioSample(frameBuffer, info);
                }

            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "deliverOutput failed", e);
        }
    }

    private void releaseCodecOnOutputThread() {
        Log.e(TAG, "Releasing MediaCodec on output thread - audio ");

        Log.d(TAG, "Releasing MediaCodec on output thread");
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
        Log.d(TAG, "Release on output thread done");
        Log.e(TAG, "Release on output thread done - audio");
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
    enum MimeType {
        AAC("audio/mp4a-latm");

        private final String mimeType;

        private MimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        String mimeType() {
            return mimeType;
        }
    }

    public enum AudioCodecStatus {
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

        private AudioCodecStatus(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    static class Settings {
        public int sampleRate;
        public int channelCount;
        public int bitrate;
        public int audioFmt;

        public Settings(int sampleRate, int channelCount, int bitrate, int audioFmt) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.bitrate = bitrate;
            this.audioFmt = audioFmt;
        }
    }



    public interface Callback {
        public void onAudioSample(ByteBuffer outBuf, MediaCodec.BufferInfo bufferInfo);
        public void onAddAudioTrack(MediaFormat format);

    }
}

package com.example.yuvrecorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioSource {
    private static final String TAG = "AudioSource";
    private static AudioSource mAudioSource;

    private AudioRecord audioRecord;
    private int channels;
    private int samplerate;
    private int pcmFormat;
    private byte[] audioBuf;

    private Thread workThread;
    private volatile  boolean loop = false;
    private AudioCallBack mCallback;

    public interface AudioCallBack {
        public void sendData(byte[] data, int audioFmt, int channelCount, int sampleRatent);
    }


    public static AudioSource getInstance(){
        if (mAudioSource == null) {
            synchronized (AudioSource.class) {
                if (mAudioSource == null) {
                    mAudioSource = new AudioSource();
                }
            }
        }
        return mAudioSource;
    }

    private AudioSource() {
        //private
    }

    public void prepare() {
        Log.e(TAG, "==prepare== start");
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        int[] sampleRates = {44100, 22050, 16000, 11025, 8000, 4000};
        for (int sample : sampleRates) {
            int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
            int min_buffer_size = 2 * AudioRecord.getMinBufferSize(sample, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sample, channelConfig, AudioFormat.ENCODING_PCM_16BIT, min_buffer_size);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord = null;
                Log.e(TAG, "==prepare== initialized the mic failed");
                continue;
            }

            samplerate = sample;
            channels = channelConfig == AudioFormat.CHANNEL_CONFIGURATION_STEREO ? 2 : 1;
            pcmFormat = 16;
            //ByteBuffer分配内存的最大值为4096
            int buffSize =  Math.min(2048, min_buffer_size);
            audioBuf = new byte[buffSize];
            Log.i(TAG, "==prepare== SampleRate: " + samplerate + "  channels: " + channels + "  min_buffer_size: " + min_buffer_size);
            break;
        }
    }

    public void startRecord() {
        Log.i(TAG, "==startRecord== start record");
        if (loop) {
            Log.i(TAG, "==startRecord== already start, return");
            return;
        }

        workThread = new Thread() {
            public void run() {
                Log.i(TAG, "==startRecord== workThread start");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                if (audioRecord != null) {
                    audioRecord.startRecording();
                }

                while (loop && !Thread.interrupted()) {
                    int size = audioRecord.read(audioBuf,0,audioBuf.length);
                    if (size > 0) {
                        if (mCallback != null) {
                            int audioFmt = audioRecord.getAudioFormat();
                            int channelCount = audioRecord.getChannelCount();
                            int sampleRate = audioRecord.getSampleRate();

                            //Log.i(TAG, "==startRecord== workThread audioBuf.length,size: "+size + " vs "+audioBuf.length);
                            mCallback.sendData(audioBuf, audioFmt, channelCount, sampleRate);
                        }
                    }
                }

                Log.i(TAG, "==startRecord== workThread stop");
            }
        };

        loop = true;
        workThread.start();
    }

    public void stopRecord() {
        Log.i(TAG, "==stopRecord== stop record");
        if (audioRecord != null) {
            audioRecord.stop();
        }

        loop = false;

        if (workThread != null){
            workThread.interrupt();
        }
    }

    public void release() {
        Log.i(TAG, "==release== ");
        if(audioRecord != null)
            audioRecord.release();

        audioRecord = null;
        mCallback = null;
        mAudioSource = null;
    }

    public int getaChannelCount() {
        return channels;
    }

    public int getaSampleRate() {
        return samplerate;
    }

    public int getPcmForamt() {
        return pcmFormat;
    }

    public void setCallback(AudioCallBack callback) {
        this.mCallback = callback;
    }

}

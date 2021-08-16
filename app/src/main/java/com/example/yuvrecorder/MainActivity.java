package com.example.yuvrecorder;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import com.tuya.record.TuyaAudioEncoder;
import com.tuya.record.TuyaMediaMuxer;
import com.tuya.record.TuyaVideoEncoder;

import java.io.File;


public class MainActivity extends AppCompatActivity implements  View.OnClickListener, VideoSource.CameraOperateCallback, SurfacePreview.PermissionNotify{
    private final static String         TAG = "MainActivity";
    private              boolean        bStarted;
    private              Button         btnStart;
    private              Button         btnStop;
    private Button btnShare;
    private              SurfaceView    mSurfaceView;
    private              SurfaceHolder  mSurfaceHolder;
    private              TuyaMediaMuxer yuvrecorder;

    private VideoSource mVideoSource;
    private AudioSource mAudioSource;

    private SurfacePreview mSurfacePreview;
    private boolean hasPermission;

    private int width;
    private int height;
    private int IFrameInterval;
    private int bps;
    private int fps;
    private int colorFormat;
    private String mp4File; //= getApplicationContext().getFilesDir().getAbsolutePath()+"/a.mp4";//"/sdcard/test2.mp4";//"/sdcard/test2.mp4"

    private long audioPts = 0;
    private long startPts = 0;

    // 要申请的权限
    private String[] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO};
    private static final int TARGET_PERMISSION_REQUEST = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bStarted = false;
        btnStart = (Button) findViewById(R.id.btnStartRecord);
        btnStart.setOnClickListener(this);

        btnStop = (Button) findViewById(R.id.btnStopRecord);
        btnStop.setOnClickListener(this);

        btnShare = (Button) findViewById(R.id.btnShareMedia);
        btnShare.setOnClickListener(this);

        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceView.setKeepScreenOn(true);
        mSurfaceView.setZOrderMediaOverlay(true);

        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfacePreview = new SurfacePreview(this,this);
        mSurfaceHolder.addCallback(mSurfacePreview);


        mVideoSource = VideoSource.getInstance();
        mAudioSource = AudioSource.getInstance();
        yuvrecorder = new TuyaMediaMuxer();
        mp4File = getApplicationContext().getFilesDir().getAbsolutePath()+"/a.mp4";

        mAudioSource.setCallback(new AudioSource.AudioCallBack() {
            public void sendData(byte[] data, int audioFmt, int channelCount, int sampleRatent) {
                if (yuvrecorder != null && bStarted) {
                    long time = System.currentTimeMillis() - startPts;
                    TuyaAudioEncoder.AudioSamples  audioSamples = new TuyaAudioEncoder.AudioSamples(audioFmt, channelCount, sampleRatent, data);
                    yuvrecorder.writeAudioSample(audioSamples);
                    //audioPts += data.length;
                }
            }
        });

        mVideoSource.setCallback(new VideoSource.Callback() {
            @Override
            public void sendVideoData(byte[] data, int fmt, int width, int height, int frameRate, long timeMS) {
                if (yuvrecorder != null && bStarted) {
                    long time = System.currentTimeMillis() - startPts;
                    TuyaVideoEncoder.VideoFrame videoFrame = new TuyaVideoEncoder.VideoFrame(data, fmt, width, height,frameRate, timeMS);
                    yuvrecorder.writeVideoFrame(videoFrame);
                }
            }
        });

        // 版本判断。当手机系统大于 23 时，才有必要去判断权限是否获取
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 检查该权限是否已经获取
            for (int i = 0; i < permissions.length; i++) {
                int result = ContextCompat.checkSelfPermission(this, permissions[i]);
                // 权限是否已经 授权 GRANTED---授权  DINIED---拒绝
                if (result != PackageManager.PERMISSION_GRANTED) {
                    hasPermission = false;
                    break;
                } else
                    hasPermission = true;
            }
            if(!hasPermission){
                // 如果没有授予权限，就去提示用户请求
                ActivityCompat.requestPermissions(this,
                        permissions, TARGET_PERMISSION_REQUEST);
            }
        }

        Log.e(TAG, "onCreate End.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bStarted) {
            //stop recording
            //stop audio capture
            //stop video capture
            yuvrecorder.stopRecord();
            mAudioSource.stopRecord();
            mAudioSource.release();

            bStarted = false;

        }
        VideoSource.getInstance().doStopCamera();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    private void toggle() {
        if (bStarted) {
            //stop recording
            //stop audio capture
            //stop video capture
            yuvrecorder.stopRecord();
            mAudioSource.stopRecord();
            //mAudioSource.release();

            bStarted = false;
        } else {
            startPts = System.currentTimeMillis();
            //start audio capture
            mAudioSource.prepare();
            mAudioSource.startRecord();
            //start video capture

            //start recording
            //720p/1080p/16000/44100/48000/8000/22050
            yuvrecorder.startRecord(
                    true,
                    44100,
                    2,
                    128*1024,
                    true,
                    1280,
                    720,
                    30,
                    2048*1024,
                    mp4File
            );


            bStarted = true;
        }
        btnStart.setText(bStarted ? "stopRecord" : "startRecord");
    }

    @Override
    public void onClick(View v) {

        if (v.getId() == R.id.btnStartRecord) {
            toggle();
            btnStop.setEnabled(true);
            btnStart.setEnabled(false);
        } else if (v.getId() == R.id.btnStopRecord) {
            toggle();
            btnStop.setEnabled(false);
            btnStart.setEnabled(true);
        } else if (v.getId() == R.id.btnShareMedia) {
            shareMedia(this, mp4File, false);
        }
    }

    private int shareMedia(Context context, String mediaPath, boolean isPhoto) {
        final Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri contentUri = FileProvider.getUriForFile(context,context.getPackageName() + ".provider" , new File(mediaPath));
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            if (isPhoto) {
                intent.setType("image/jpeg");
            } else {
                intent.setType("video/mp4");
            }
            startActivity(intent);
        } else {
            Uri uri = Uri.fromFile(new File(mediaPath));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            if (isPhoto) {
                intent.setType("image/jpeg");
            } else {
                intent.setType("video/mp4");
            }
            startActivity(intent);
        }

        return 0;
    }

    @Override
    public void cameraHasOpened() {
        Log.i(TAG, "cameraHasOpened");
        VideoSource.getInstance().doStartPreview(this, mSurfaceHolder);
    }

    @Override
    public void cameraHasPreview(int w, int h, int f) {
        Log.i(TAG, "cameraHasPreview width: "+ w + " height "+ h + " fps "+ f);
        width = w;
        height = h;
        fps = f;
    }

    @Override
    public boolean hasPermission() {
        return hasPermission;
    }
}
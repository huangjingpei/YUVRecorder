package com.example.yuvrecorder;

import android.util.Log;
import android.view.SurfaceHolder;

public class SurfacePreview implements SurfaceHolder.Callback{
    private final static String TAG = "SurfacePreview";
    private VideoSource.CameraOperateCallback mCallback;
    private PermissionNotify listener;

    public interface PermissionNotify{
        boolean hasPermission();
    }

    public SurfacePreview(VideoSource.CameraOperateCallback cb,PermissionNotify listener){
        mCallback = cb;
        this.listener = listener;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
        Log.i(TAG, "SurfacePreview=====surfaceDestroyed()====");
        VideoSource.getInstance().doStopCamera();
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        Log.i(TAG, "SurfacePreview=====surfaceCreated()====");
        if(listener != null){
            if(listener.hasPermission())
                // 打开摄像头
                VideoSource.getInstance().doOpenCamera(mCallback);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3) {
        Log.i(TAG, "SurfacePreview=====surfaceChanged()====");
        if(listener != null){
            if(listener.hasPermission())
                // 打开摄像头
                VideoSource.getInstance().doOpenCamera(mCallback);
        }
    }
}

package com.example.yuvrecorder;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static android.hardware.Camera.Parameters.PREVIEW_FPS_MAX_INDEX;
import static android.hardware.Camera.Parameters.PREVIEW_FPS_MIN_INDEX;

public class VideoSource {
    private static final String TAG = "VideoSource";
    private int preWidth;
    private int preHeight;
    private int frameRate;
    private static VideoSource mCameraWrapper;

    // 定义系统所用的照相机
    private Camera mCamera;
    //预览尺寸
    private Camera.Size previewSize;
    private Camera.Parameters mCameraParamters;
    private boolean mIsPreviewing = false;
    private CameraPreviewCallback mCameraPreviewCallback;

    private Callback mCallback;
    private CameraOperateCallback cameraCb;
    private Context mContext;

    private VideoSource() {
    }

    public interface CameraOperateCallback {
        public void cameraHasOpened();
        public void cameraHasPreview(int width,int height,int fps);
    }

    public interface Callback {
        public void sendVideoData(byte[] data, int fmt, int width, int height, int frameRate, long timeNs);
    }

    public static VideoSource getInstance() {
        if (mCameraWrapper == null) {
            synchronized (VideoSource.class) {
                if (mCameraWrapper == null) {
                    mCameraWrapper = new VideoSource();
                }
            }
        }
        return mCameraWrapper;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;

    }

    public void doOpenCamera(CameraOperateCallback callback) {
        Log.i(TAG, "doOpenCamera camera open....");
        cameraCb = callback;
        if(mCamera != null)
            return;
        if (mCamera == null) {
            Log.i(TAG, "doOpenCamera No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("doOpenCamera, Unable to open camera");
        }
        Log.i(TAG, "doOpenCamera Camera open over....");
        cameraCb.cameraHasOpened();
    }

    public void doStartPreview(Activity activity, SurfaceHolder surfaceHolder) {
        Log.i(TAG, "doStartPreview start....");
        if (mIsPreviewing) {
            return;
        }
        mContext = activity;
        setCameraDisplayOrientation(activity, Camera.CameraInfo.CAMERA_FACING_BACK);
        setCameraParamter(surfaceHolder);
        try {
            // 通过SurfaceView显示取景画面
            mCamera.setPreviewDisplay(surfaceHolder);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();
        mIsPreviewing = true;
        mCamera.autoFocus(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                Log.i(TAG, "doStartPreview onAutoFocus----->success: "+success);
            }
        });
        Log.i(TAG, "doStartPreview Camera Preview Started...");
        cameraCb.cameraHasPreview(preWidth,preHeight,frameRate);
    }

    public void doStopCamera() {
        Log.i(TAG, "doStopCamera  mCamera: "+mCamera+"   mCameraWrapper: "+mCameraWrapper);
        // 如果camera不为null，释放摄像头
        if (mCamera != null) {
            mCamera.setPreviewCallbackWithBuffer(null);
            mCameraPreviewCallback = null;
            if (mIsPreviewing)
                mCamera.stopPreview();
            mIsPreviewing = false;
            mCamera.release();
            mCamera = null;
        }

        if(mCameraWrapper != null){
            mCallback = null;
            cameraCb = null;
            mContext = null;
            mCameraWrapper = null;
        }
    }

    private void setCameraParamter(SurfaceHolder surfaceHolder) {
        if (!mIsPreviewing && mCamera != null) {
            mCameraParamters = mCamera.getParameters();
            List<Integer> previewFormats = mCameraParamters.getSupportedPreviewFormats();
            for(int i=0;i<previewFormats.size();i++){
                Log.i(TAG,"setCameraParamter support preview format : "+previewFormats.get(i));
            }
            mCameraParamters.setPreviewFormat(ImageFormat.NV21);//ImageFormat.NV21
            // Set preview size.
            List<Camera.Size> supportedPreviewSizes = mCameraParamters.getSupportedPreviewSizes();
            for (Camera.Size size: supportedPreviewSizes) {
                Log.i(TAG, "setCameraParamter support preview size : W:" + size.width + " H:" + size.height);
            }

            Collections.sort(supportedPreviewSizes, new Comparator<Camera.Size>() {
                @Override
                public int compare(Camera.Size o1, Camera.Size o2) {
                    Integer left = o1.width;
                    Integer right = o2.width;
                    return left.compareTo(right);
                }
            });

            DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            Log.i(TAG, "setCameraParamter Screen width=" + dm.widthPixels + ", height=" + dm.heightPixels);
            dm.heightPixels = 1280;//force
            dm.widthPixels = 720;
            for (Camera.Size size : supportedPreviewSizes) {
                if (size.width >= dm.heightPixels && size.height >= dm.widthPixels) {
                    if ((1.0f * size.width / size.height) == (1.0f * dm.heightPixels / dm.widthPixels)) {
                        previewSize = size;
                        Log.i(TAG, "setCameraParamter select preview size width=" + size.width + ",height=" + size.height);
                        break;
                    }
                }
            }
            preWidth = previewSize.width;
            preHeight = previewSize.height;
            mCameraParamters.setPreviewSize(previewSize.width, previewSize.height);

            //set fps range.
            int defminFps = 0;
            int defmaxFps = 0;
            List<int[]> supportedPreviewFpsRange = mCameraParamters.getSupportedPreviewFpsRange();
            for (int[] fps : supportedPreviewFpsRange) {
                Log.i(TAG, "===setCameraParamter===find fps:" + Arrays.toString(fps));
                if (defminFps <= fps[PREVIEW_FPS_MIN_INDEX] && defmaxFps <= fps[PREVIEW_FPS_MAX_INDEX]) {
                    defminFps = fps[PREVIEW_FPS_MIN_INDEX];
                    defmaxFps = fps[PREVIEW_FPS_MAX_INDEX];
                }
            }
            //设置相机预览帧率
            Log.i(TAG, "===setCameraParamter==defminFps:" + defminFps+"    defmaxFps: "+defmaxFps);
            mCameraParamters.setPreviewFpsRange(defminFps,defmaxFps);
            frameRate = defmaxFps / 1000;
            surfaceHolder.setFixedSize(previewSize.width, previewSize.height);
            mCameraPreviewCallback = new CameraPreviewCallback();
            mCamera.addCallbackBuffer(new byte[previewSize.width * previewSize.height*3/2]);
            mCamera.setPreviewCallbackWithBuffer(mCameraPreviewCallback);
            List<String> focusModes = mCameraParamters.getSupportedFocusModes();
            for (String focusMode : focusModes){//检查支持的对焦
                Log.i(TAG, "===setCameraParamter===focusMode:" + focusMode);
                if (focusMode.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)){
                    mCameraParamters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                }else if (focusMode.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)){
                    mCameraParamters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }else if(focusMode.contains(Camera.Parameters.FOCUS_MODE_AUTO)){
                    mCameraParamters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            Log.i(TAG, "==setCameraParamter==preWidth:" + preWidth+"   preHeight: "+preHeight+"  frameRate: "+frameRate);
            mCamera.setParameters(mCameraParamters);
        }
    }

    private void setCameraDisplayOrientation(Activity activity,int cameraId) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result = 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        Log.i(TAG, "=====setCameraDisplayOrientation=====result:" + result+"  rotation: "+rotation+"  degrees: "+degrees+"  orientation: "+info.orientation);
        mCamera.setDisplayOrientation(result);
    }

    class CameraPreviewCallback implements Camera.PreviewCallback {
        private CameraPreviewCallback() {
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            Camera.Size size = camera.getParameters().getPreviewSize();
            int fmt = camera.getParameters().getPreviewFormat();
            int frameRate = camera.getParameters().getPreviewFrameRate();

            //通过回调,拿到的data数据是原始数据
            //丢给VideoRunnable线程,使用MediaCodec进行h264编码操作
            if(data != null){
                camera.getParameters();

                if(mCallback != null) {
                    int length = size.width*size.height*3/2;
                    byte[] nv12 = new byte[length];
                    NV21ToNV12(data, nv12);
                    mCallback.sendVideoData(nv12, fmt, size.width, size.height, frameRate, System.currentTimeMillis());
                }
                camera.addCallbackBuffer(data);
            }
            else {
                camera.addCallbackBuffer(new byte[size.width * size.height *3/2]);
            }
        }
    }

    private void NV21ToNV12(byte[] nv21, byte[] nv12) {
        System.arraycopy(nv21, 0, nv12, 0, nv21.length * 2 / 3);

        int uvStart = nv21.length * 2 / 3;
        for (int i = uvStart; i < nv21.length; i += 2) {
            nv12[i + 1] = nv21[i];
            nv12[i] = nv21[i + 1];
        }
    }

}

package com.llx278.record;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;

import com.llx278.record.encode.MediaMuxerWrapper;
import com.llx278.record.glutils.GLDrawer2D;
import com.llx278.record.glutils.RenderHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 *
 * Render of GLSurfaceView
 * Created by llx on 02/12/2017.
 */

public class CameraPreviewGLSurfaceView extends GLSurfaceView implements MediaMuxerWrapper.VideoCodecInitListener {

    private static final String TAG = "main";

    private static final int SCALE_STRETCH_FIT = 0;
    private static final int SCALE_KEEP_ASPECT_VIEWPORT = 1;
    private static final int SCALE_KEEP_ASPECT = 2;
    private static final int SCALE_CROP_CENTER = 3;

    private SurfaceRender surfaceRender;

    private Handler bgHandler;

    private boolean hasSurfaceTexture;

    private SurfaceTexture texture;

    private Surface cameraPreviewSurface;

    private int videoWidth;
    private int videoHeight;

    private CameraDevice cameraDevice;
    private TextView fpsView;

    private int scaleMode = SCALE_KEEP_ASPECT_VIEWPORT;

    private HandlerThread backgroundThread;

    private RenderHandler renderHandler;

    private MediaMuxerWrapper mediaMuxerWrapper;

    public CameraPreviewGLSurfaceView(Context context) {
        super(context);
    }

    public CameraPreviewGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setUpdateView(TextView tv) {
        this.fpsView = tv;
    }


    private void init() {
        Log.d(TAG, String.format("context ClassName = %s", getContext().getClass().getName()));
        setEGLContextClientVersion(2);
        surfaceRender = new SurfaceRender();
        setRenderer(surfaceRender);
    }

    public void setMediaMuxerWrapper (MediaMuxerWrapper mediaMuxerWrapper) {
        this.mediaMuxerWrapper = mediaMuxerWrapper;
        this.mediaMuxerWrapper.setOnVideoCodecInitListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (renderHandler == null) {
            renderHandler = RenderHandler.createHandler("RenderHandler");
        }

        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("BackgroundThread");
            backgroundThread.start();
        }
        if (bgHandler == null) {
            bgHandler = new BGHandler(backgroundThread.getLooper());
        }

        if (hasSurfaceTexture) {
            startPreview(getWidth(), getHeight());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bgHandler != null) {
            stopPreview();
        }
        backgroundThread.quitSafely();
        backgroundThread = null;
        bgHandler = null;
    }

    private void startPreview(int width, int height) {
        if (bgHandler == null) {
            Log.e(TAG, "you should initiate bgHandler before call startPreview(int,int)!");
            return;
        }
        Message message = Message.obtain();
        message.what = BGHandler.START_PREVIEW;
        message.arg1 = width;
        message.arg2 = height;
        bgHandler.sendMessage(message);
    }

    private void stopPreview() {
        if (bgHandler == null) {
            Log.e(TAG, "you should initiate bgHandler before call stopPreview(int,int)!");
            return;
        }
        bgHandler.sendEmptyMessage(BGHandler.STOP_PREVIEW);
    }

    @Override
    public void onInputSurfaceCreated(final Surface inputSurface) {
        if (inputSurface == null) {
            throw new IllegalArgumentException("you can not pass a null input cameraPreviewSurface!!");
        }
        Log.d(TAG,"onInputSurfaceCreated");
        queueEvent(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "render handler setEglContext");
                renderHandler.setEglContext(EGL14.eglGetCurrentContext(),
                        surfaceRender.textureId,
                        inputSurface,
                        true);
            }
        });
    }


    class SurfaceRender implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private GLDrawer2D drawer;
        private final float[] stMatrix = new float[16];
        private final float[] mvpMatrix = new float[16];
        private volatile boolean requestUpdateTex = false;
        private int textureId;
        private long elapsedTime = -1;
        private boolean flip = false;

        SurfaceRender() {
            Matrix.setIdentityM(mvpMatrix, 0);
        }
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.d(TAG, "onSurfaceCreated");
            final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (!extensions.contains("OES_EGL_image_external")) {
                throw new RuntimeException("This system does not support OES_EGL_image_external");
            }

            // Create texture ID
            textureId = GLDrawer2D.initTex();
            // Create SurfaceTexture with texture ID
            texture = new SurfaceTexture(textureId);
            texture.setOnFrameAvailableListener(this);
            // clear screen with yellow color so you can see rendering rectangle
            GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
            hasSurfaceTexture = true;
            // create object for preview display
            drawer = new GLDrawer2D();
            drawer.setMatrix(mvpMatrix, 0);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.d(TAG, String.format("onSurfaceCreated:(width:%d,height:%d)", width, height));
            // if at least width or height is 0,initialization of this view is still progress
            if (width == 0 || height == 0) {
                return;
            }
            updateViewport(width, height);
            startPreview(width, height);
        }

        private void updateViewport(int viewWidth, int viewHeight) {

            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            // ??
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            Log.d(TAG, String.format("updateViewPort : videoWidth = %d,videoHeight = %d",
                    videoWidth, videoHeight));
            if (videoWidth == 0 || videoHeight == 0) {
                return;
            }
            Matrix.setIdentityM(mvpMatrix, 0);
            switch (scaleMode) {
                case SCALE_STRETCH_FIT:
                    break;
                case SCALE_KEEP_ASPECT_VIEWPORT:
                    Log.d(TAG, "SCALE_KEEP_ASPECT_VIEWPORT");
                    int newWidth, newHeight;
                    int x, y;
                    final float videoAspect = (float) videoWidth / (float) videoHeight;
                    final float viewAspect = (float) viewWidth / (float) viewHeight;
                    if (viewAspect > videoAspect) {
                        // 如果video的height与view的height相同，view的width比video的width要宽
                        // 那么也就是说只需要固定view的height，计算出实际的video的width就可以了
                        y = 0;
                        newHeight = viewHeight;
                        newWidth = (int) (viewWidth * videoAspect);
                        x = (viewWidth - newWidth) / 2;
                    } else {
                        x = 0;
                        // 如果video的height与view的height相同，view的with比video的with要窄
                        // 那么也就只需要让video的width与view的width相同，在计算出实际video的height
                        // 就可以了
                        newWidth = viewWidth;
                        newHeight = (int) (videoHeight * videoAspect);
                        y = (viewHeight - newHeight) / 2;
                    }
                    Log.d(TAG, String.format("viewAspect=%f,videoAspect=%f",
                            viewAspect, videoAspect));
                    Log.d(TAG, String.format("xy(%d,%d),size(%d,%d)", x, y, newWidth, newHeight));
                    GLES20.glViewport(x, y, newWidth, newHeight);
                    break;
                case SCALE_KEEP_ASPECT:
                    break;
                case SCALE_CROP_CENTER:
                    break;
            }
            if (drawer != null) {
                drawer.setMatrix(mvpMatrix, 0);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (requestUpdateTex) {
                requestUpdateTex = false;
                // update Texture(came from camera)
                texture.updateTexImage();
                texture.getTransformMatrix(stMatrix);
            }
            drawer.draw(textureId, stMatrix);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestUpdateTex = true;
            if (mediaMuxerWrapper.isCodecRunning()) {
                renderHandler.draw(textureId, stMatrix, mvpMatrix);
                mediaMuxerWrapper.frameAvailableSoon();
            }
        }
    }

    @SuppressLint("HandlerLeak")
    class BGHandler extends Handler {
        static final int START_PREVIEW = 1;
        static final int STOP_PREVIEW = 2;
        static final int UPDATE_VIEW_PORT = 3;
        CameraDeviceHelper cameraDeviceHelper;

        BGHandler(Looper looper) {
            super(looper);
            cameraDeviceHelper = new CameraDeviceHelper();
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case START_PREVIEW:
                    handlePreview(msg.arg1, msg.arg2);
                    break;
                case STOP_PREVIEW:
                    handleStop();
                    break;
                case UPDATE_VIEW_PORT:
                    int width = msg.arg1;
                    int height = msg.arg2;
                    handleUpdateViewPort(width, height);
                    mediaMuxerWrapper.initCodec(videoWidth,videoHeight);
                    break;
            }
        }

        private void handleStop() {
            cameraDeviceHelper.stopPreview();
        }

        private void handleUpdateViewPort(final int width, final int height) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    surfaceRender.updateViewport(width, height);
                }
            });
        }

        private void handlePreview(int width, int height) {
            cameraDeviceHelper.startPreview(width, height);
        }
    }

    class CameraDeviceHelper extends CameraDevice.StateCallback {

        private int width;
        private int height;
        // default camera id is 0
        private String cameraId = "0";
        private Size previewSize;
        private CameraCharacteristics characteristics;
        private PreviewStateCallback previewStateCallback;

        private void openCameraIfNeeded() {
            if (cameraDevice == null) {

                try {
                    Context context = CameraPreviewGLSurfaceView.this.getContext();
                    CameraManager cameraManager = (CameraManager) context.
                            getSystemService(Context.CAMERA_SERVICE);
                    // 默认cameraId = 0;
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        Log.e(TAG, "camera permission not granted!open camera failed");
                        return;
                    }

                    if (cameraManager == null) {
                        Log.e(TAG, "get CameraManager failed !!");
                        return;
                    }

                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    previewSize = chooseOptimalPreviewSize();
                    if (previewSize == null) {
                        Log.d(TAG, "choose preview size failed !!");
                        return;
                    }
                    Log.d(TAG, String.format("previewSize : width = %d,height = %d",
                            previewSize.getWidth(), previewSize.getHeight()));
                    videoWidth = previewSize.getWidth();
                    videoHeight = previewSize.getHeight();
                    texture.setDefaultBufferSize(videoWidth, videoHeight);
                    Message msg = Message.obtain();
                    msg.what = BGHandler.UPDATE_VIEW_PORT;
                    msg.arg1 = CameraPreviewGLSurfaceView.this.getWidth();
                    msg.arg2 = CameraPreviewGLSurfaceView.this.getHeight();
                    bgHandler.sendMessage(msg);
                    cameraManager.openCamera(cameraId, this, bgHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        private Size chooseOptimalPreviewSize() throws CameraAccessException {

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                Log.e(TAG,
                        String.format("Cannot get StreamConfigurationMap " +
                                "from a indicate camera id! cameraId = %s", cameraId));
                return null;
            }
            Size videoSize = Collections.max(
                    Arrays.asList(map.getOutputSizes(MediaRecorder.class)),
                    new CompareSizesByArea());
            Log.d(TAG, String.format("MaxVideoSize : width = %d,height = %d",
                    videoSize.getWidth(), videoSize.getHeight()));
            Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);

            Activity activity = (Activity) CameraPreviewGLSurfaceView.this.getContext();
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            Point displaySize = new Point();
            activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
            Integer sensorRotation = characteristics.
                    get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorRotation == null) {
                Log.e(TAG, "cannot get sensorRotation !!");
                return null;
            }
            boolean swappedDimensions = false;
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if (sensorRotation == 90 || sensorRotation == 270) {
                        swappedDimensions = true;
                    }
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if (sensorRotation == 0 || sensorRotation == 180) {
                        swappedDimensions = true;
                    }
                    break;
            }
            int rotatedPreviewWidth = width;
            int rotatedPreviewHeight = height;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;
            if (swappedDimensions) {
                //noinspection SuspiciousNameCombination
                rotatedPreviewWidth = height;
                //noinspection SuspiciousNameCombination
                rotatedPreviewHeight = width;
                //noinspection SuspiciousNameCombination
                maxPreviewWidth = displaySize.y;
                //noinspection SuspiciousNameCombination
                maxPreviewHeight = displaySize.x;
            }
            Size optimalSize;
            List<Size> bigEnough = new ArrayList<>();
            List<Size> notBigEnough = new ArrayList<>();
            for (Size option : outputSizes) {
                if (option.getWidth() <= maxPreviewWidth && option.getHeight() <= maxPreviewHeight
                        && option.getHeight() ==
                        option.getWidth() * videoSize.getHeight() /
                                videoSize.getWidth()) {
                    if (option.getWidth() >= rotatedPreviewWidth
                            && option.getHeight() >= rotatedPreviewHeight) {
                        bigEnough.add(option);
                    } else {
                        notBigEnough.add(option);
                    }
                }
            }
            if (bigEnough.size() > 0) {
                optimalSize = Collections.min(bigEnough, new CompareSizesByArea());
            } else if (notBigEnough.size() > 0) {
                optimalSize = Collections.max(notBigEnough, new CompareSizesByArea());
            } else {
                optimalSize = outputSizes[0];
            }
            return optimalSize;
        }


        void startPreview(int width, int height) {
            this.width = width;
            this.height = height;
            openCameraIfNeeded();
        }

        void stopPreview() {
            if (previewStateCallback.session != null) {
                Log.d(TAG, "ready to close previewStateCallback session");
                previewStateCallback.session.close();
            }
            if (cameraDevice != null) {
                Log.d(TAG, "ready to close CameraDevice");
                cameraDevice.close();
                cameraDevice = null;
            }
        }

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "camera open success!!");
            cameraDevice = camera;
            if (!CameraPreviewGLSurfaceView.this.hasSurfaceTexture) {
                Log.d(TAG, "surfaceTexture initialization failed!");
                return;
            }
            cameraPreviewSurface = new Surface(texture);
            previewStateCallback = new PreviewStateCallback(characteristics);
            try {

                cameraDevice.createCaptureSession(Collections.singletonList(cameraPreviewSurface),
                        previewStateCallback, CameraPreviewGLSurfaceView.this.bgHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                Log.e(TAG, "Create CaptureSession failed !");
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "camera device disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "camera device error !! ");
        }
    }

    class PreviewStateCallback extends CameraCaptureSession.StateCallback {

        private CameraCharacteristics cameraCharacteristics;
        private CameraCaptureSession session;

        PreviewStateCallback(CameraCharacteristics cameraCharacteristics) {
            this.cameraCharacteristics = cameraCharacteristics;
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            this.session = session;
            // start preview
            try {
                CaptureRequest.Builder builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(cameraPreviewSurface);
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                session.setRepeatingRequest(builder.build(), null, bgHandler);
                Log.d(TAG, "start preview success !!! ");
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "PreviewStateCallback onConfigureFailed !!");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            super.onClosed(session);
            Log.d(TAG, "PreviewStateCallback onClosed!!");
        }
    }

    class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}

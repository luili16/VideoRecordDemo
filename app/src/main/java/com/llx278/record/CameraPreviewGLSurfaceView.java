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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
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

import com.llx278.record.glutils.GLDrawer2D;
import com.llx278.record.glutils.RenderHandler;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 *
 *
 * Render of GLSurfaceView
 * Created by llx on 02/12/2017.
 */

public class CameraPreviewGLSurfaceView extends GLSurfaceView {

    private static final String TAG = "main";

    private static final int SCALE_STRETCH_FIT = 0;
    private static final int SCALE_KEEP_ASPECT_VIEWPORT = 1;
    private static final int SCALE_KEEP_ASPECT = 2;
    private static final int SCALE_CROP_CENTER = 3;

    private static final Object syncObj = new Object();

    private SurfaceRender surfaceRender;

    private Handler bgHandler;

    private boolean hasSurfaceTexture;

    private SurfaceTexture texture;

    private Surface surface;

    private int videoWidth;
    private int videoHeight;

    private CameraDevice cameraDevice;
    private TextView fpsView;

    private int scaleMode = SCALE_KEEP_ASPECT_VIEWPORT;

    private HandlerThread backgroundThread;

    private PrepareCallback callback;

    private RenderHandler renderHandler;

    private boolean canDraw = false;


    public CameraPreviewGLSurfaceView(Context context) {
        super(context);
    }

    public CameraPreviewGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setPreviewCallback(PrepareCallback callback) {
        this.callback = callback;
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

    public void startRecord() {
        Log.d(TAG, "startRecord!");
        bgHandler.sendEmptyMessage(BGHandler.START_RECORD);
    }

    public void stopRecord() {
        Log.d(TAG,"stopRecord");
        bgHandler.sendEmptyMessage(BGHandler.STOP_RECORD);
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

    class SurfaceRender implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private GLDrawer2D drawer;
        private final float[] stMatrix = new float[16];
        private final float[] mvpMatrix = new float[16];
        private volatile boolean requestUpdateTex = false;
        private int textureId;
        private long elapsedTime = -1;
        private boolean flip = false;
        private int codecReady;

        SurfaceRender() {
            Matrix.setIdentityM(mvpMatrix, 0);
        }

        public void notifyCodecReady(int codecReady) {
            this.codecReady = codecReady;
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
            flip = !flip;
            if (flip) {
                synchronized (this) {
                    if (codecReady == 1) {
                        //Log.d(TAG,"renderHandler draw !!");
                        renderHandler.draw(textureId, stMatrix, mvpMatrix);
                    }
                    bgHandler.sendEmptyMessage(BGHandler.FRAME_AVAILABLE_SOON);
                }
            }
            if (elapsedTime == -1) {
                elapsedTime = SystemClock.elapsedRealtime();
            } else {
                long current = SystemClock.elapsedRealtime();
                final float frameRate = (float) (current - elapsedTime) * 1000;
                elapsedTime = current;
                fpsView.post(new Runnable() {
                    @Override
                    public void run() {
                        fpsView.setText(String.valueOf(Float.valueOf(frameRate)));
                    }
                });
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestUpdateTex = true;
        }
    }

    @SuppressLint("HandlerLeak")
    class BGHandler extends Handler {
        static final int START_PREVIEW = 1;
        static final int STOP_PREVIEW = 2;
        static final int UPDATE_VIEW_PORT = 3;
        static final int INIT_VIDEO_CODEC = 4;
        static final int SURFACE_CREATED = 5;
        static final int START_RECORD = 6;
        static final int STOP_RECORD = 7;
        static final int FRAME_AVAILABLE_SOON = 8;
        static final int CODEC_READY = 9;
        CameraDeviceHelper cameraDeviceHelper;
        VideoCodecThread videoCodecThread;

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
                    break;
                case INIT_VIDEO_CODEC:
                    startVideoThread();
                    break;
                case SURFACE_CREATED:
                    Surface surface = (Surface) msg.obj;
                    surfaceCreated(surface);
                    break;
                case START_RECORD:
                    videoCodecThread.innerStartRecord();
                    break;
                case STOP_RECORD:
                    videoCodecThread.innerStopRecord();
                    break;
                case FRAME_AVAILABLE_SOON:
                    if (videoCodecThread != null) {
                        videoCodecThread.frameAvailableSoon();
                    }
                    break;
                case CODEC_READY:
                    int ready = msg.arg1;
                    surfaceRender.notifyCodecReady(ready);
                    break;
            }
        }

        private void surfaceCreated(final Surface surface) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "render handler setEglContext");
                    renderHandler.setEglContext(EGL14.eglGetCurrentContext(),
                            surfaceRender.textureId,
                            surface,
                            true);
                }
            });
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

        private void startVideoThread() {
            videoCodecThread = new VideoCodecThread();
            videoCodecThread.start();
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
                    bgHandler.sendEmptyMessage(BGHandler.INIT_VIDEO_CODEC);
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
            surface = new Surface(texture);
            previewStateCallback = new PreviewStateCallback(characteristics);
            try {

                cameraDevice.createCaptureSession(Collections.singletonList(surface),
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
                builder.addTarget(surface);
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                Range<Integer>[] ranges = cameraCharacteristics.
                        get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null && ranges.length >= 1) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, ranges[0]);
                }
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

    class VideoCodecThread extends Thread {
        static final String MIME_TYPE = "video/avc";
        static final String DIR_NAME = "MY";
        static final int FRAME_RATE = 25;
        final Object requestDrainSync = new Object();
        private MediaCodec mediaCodec;
        private MediaMuxer mediaMuxer;
        private int trackId;
        private boolean hasStarted;
        private MediaCodec.BufferInfo bufferInfo;
        private int requestDrain = 0;
        private boolean stop;
        private boolean muxerIsStarted;

        void frameAvailableSoon() {

            if (stop) {
                return;
            }
            synchronized (requestDrainSync) {
                requestDrain++;
                requestDrainSync.notify();
            }
        }


        void innerStartRecord() {
            Log.d(TAG,"innerStartRecord!");
            hasStarted = true;
            synchronized (syncObj) {
                syncObj.notify();
            }
        }

        void innerStopRecord() {
            Log.d(TAG,"innerStopRecord!");
            hasStarted = false;
        }

        private void init() {
            Log.d(TAG, "videoCodec init");
            String outPutPath = createCaptureFile();
            try {
                if (outPutPath == null) {
                    throw new RuntimeException("create capture file failed!");
                }
                mediaMuxer = new MediaMuxer(outPutPath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            bufferInfo = new MediaCodec.BufferInfo();

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE,
                    videoWidth, videoHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int bitrate = (int) (0.25f * videoWidth * videoHeight * FRAME_RATE);
            Log.d(TAG, String.format("bitRate = %5.2f[Mbps]", bitrate / 1024f / 1024f));
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
            Log.d(TAG, "format : " + format);
            try {
                mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
                mediaCodec.configure(format,
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                final Surface surface = mediaCodec.createInputSurface();
                Log.d(TAG, "mediaCodec surface created!init renderhandler");
                Message msg = Message.obtain();
                msg.obj = surface;
                msg.what = BGHandler.SURFACE_CREATED;
                bgHandler.sendMessage(msg);
                mediaCodec.start();
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("create mediacodec failed!");
            }
        }

        private String createCaptureFile() {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    DIR_NAME);
            if (!dir.exists()) {
                if (!dir.mkdir()) {
                    Log.e(TAG,"create capture file failed!!");
                    return null;
                }
            }

            if (!dir.canWrite()) {
                Log.e(TAG,String.format("path = %s cannot write!",dir.getAbsolutePath()));
                return null;
            }
            SimpleDateFormat dateFormat =
                    new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.CHINA);
            Date date = new Date(System.currentTimeMillis());
            String fileName  = "record_" + dateFormat.format(date) + ".mp4";
            return new File(dir,fileName).getAbsolutePath();
        }

        @Override
        public void run() {

            init();

            if (!hasStarted) {
                synchronized (syncObj) {
                    while (!hasStarted) {
                        try {
                            syncObj.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            Log.e(TAG, "interrupted syncObj.wait()! return");
                            return;
                        }
                    }
                }
            }

            Log.d(TAG, "mediaCodec prepare start");
            Message msg = Message.obtain();
            msg.what = BGHandler.CODEC_READY;
            msg.arg1 = 1;
            bgHandler.sendMessage(msg);
            while (true) {
                boolean localRequestDrain;
                synchronized (requestDrainSync) {
                    localRequestDrain = requestDrain>0;
                    if (localRequestDrain) {
                        requestDrain--;
                    }
                }

                if (!hasStarted) {
                    // we have stop capture video,so we drain media codec output buffer
                    // and signal end of input stream
                    Log.d(TAG,"prepare quit codec !!!");
                    drain();
                    mediaCodec.signalEndOfInputStream();
                    drain();
                    Log.d(TAG,"we finish record !!");
                    Message msg1 = Message.obtain();
                    msg1.what = BGHandler.CODEC_READY;
                    msg1.arg1 = 0;
                    bgHandler.sendMessage(msg1);

                    mediaCodec.stop();
                    mediaCodec.release();

                    mediaMuxer.stop();
                    mediaMuxer.release();
                    muxerIsStarted = false;
                    stop = true;
                    break;
                }

                if (localRequestDrain) {
                    // we drain output buffer
                    drain();
                } else {
                    synchronized (requestDrainSync) {
                        try {
                            requestDrainSync.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            }
        }

        private void drain() {
            boolean isCapturing = true;
            while (isCapturing) {
                int index = mediaCodec.dequeueOutputBuffer(bufferInfo,1000 * 5);
                if (index >= 0) {
                    // we get an available index
                    ByteBuffer buffer = mediaCodec.getOutputBuffer(index);
                    if (buffer == null) {
                        Log.e(TAG,"we get an unavailable buffer");
                        throw new RuntimeException("we get an unavailable buffer");
                    }
                    if (bufferInfo.size != 0) {
                        if (muxerIsStarted) {
                            mediaMuxer.writeSampleData(trackId,buffer,bufferInfo);
                        }
                        mediaCodec.releaseOutputBuffer(index,false);
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG,"we found end fo stream tag!");
                        isCapturing = false;
                    }

                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    Log.e(TAG,"INFO_OUTPUT_FORMAT_CHANGED !!");
                    MediaFormat videoFormat = mediaCodec.getOutputFormat();
                    trackId = mediaMuxer.addTrack(videoFormat);
                    mediaMuxer.start();
                    muxerIsStarted = true;
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    isCapturing = false;
                }
            }
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

    public interface PrepareCallback {
        void onSurfaceViewPrepare(int previewWidth, int previewHeight);
    }
}

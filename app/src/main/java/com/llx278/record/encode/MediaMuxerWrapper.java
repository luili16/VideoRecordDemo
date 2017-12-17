package com.llx278.record.encode;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Created by llx on 17/12/2017.
 */

public class MediaMuxerWrapper {

    private static final String TAG = MediaMuxerWrapper.class.getSimpleName();
    private static final Object syncObj = new Object();

    private static final String MIME_TYPE = "video/avc";
    private static final String DIR_NAME = "MY";
    private MediaMuxer mediaMuxer;
    private VideoCodecThread videoCodecThread;
    private VideoCodecInitListener listener;

    public MediaMuxerWrapper() {
        String outPutPath = createCaptureFile();
        try {
            if (outPutPath == null) {
                throw new RuntimeException("create capture file failed!");
            }
            mediaMuxer = new MediaMuxer(outPutPath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mediaMuxer.setOrientationHint(90);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void initCodec(int videoWidth,int videoHeight) {
        Log.d(TAG,"start videoCodecThread");
        videoCodecThread = new VideoCodecThread(videoWidth, videoHeight);
        videoCodecThread.start();
    }

    public void startRecord() {
        videoCodecThread.innerStartRecord();
    }

    public void stopRecord() {
        videoCodecThread.innerStopRecord();
    }

    public void setOnVideoCodecInitListener(VideoCodecInitListener listener) {
        this.listener = listener;
    }

    public boolean isCodecRunning() {
        return videoCodecThread != null && videoCodecThread.codecRunning;
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

        File file =  new File(dir,"hellowrold.mp4");
        if (file.exists()) {
            file.delete();
        }
        return file.getAbsolutePath();
    }

    public void frameAvailableSoon() {
        //msgHandler.sendEmptyMessage(MessageHandler.FRAME_AVAILABLE_SOON);
        videoCodecThread.frameAvailableSoon();
    }

    class VideoCodecThread extends Thread {

        static final int FRAME_RATE = 25;
        final Object requestDrainSync = new Object();
        private MediaCodec mediaCodec;

        private int trackId;
        private boolean hasStarted;
        private MediaCodec.BufferInfo bufferInfo;
        private int requestDrain = 0;
        private boolean muxerIsStarted;
        private int videoWidth;
        private int videoHeight;
        private boolean codecRunning;
        private long prevOutputPTSUs = 0;

        VideoCodecThread(int videoWidth,int videoHeight) {
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
        }

        void frameAvailableSoon() {
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
                Surface surface = mediaCodec.createInputSurface();
                if (listener != null) {
                    listener.onInputSurfaceCreated(surface);
                }
                mediaCodec.start();
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("create mediacodec failed!");
            }
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
            codecRunning = true;
            while (true) {
                boolean localRequestDrain;
                synchronized (requestDrainSync) {
                    localRequestDrain = requestDrain > 0;
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
                    codecRunning = false;

                    mediaCodec.stop();
                    mediaCodec.release();

                    mediaMuxer.stop();
                    mediaMuxer.release();
                    muxerIsStarted = false;
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
                        throw new RuntimeException("we get an empty buffer");
                    }
                    if (bufferInfo.size != 0) {
                        if (muxerIsStarted) {
                            bufferInfo.presentationTimeUs = getPTSUs();
                            mediaMuxer.writeSampleData(trackId,buffer,bufferInfo);
                            prevOutputPTSUs = bufferInfo.presentationTimeUs;
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

        private long getPTSUs() {
            long result = System.nanoTime() / 1000L;
            // presentationTimeUs should be monotonic
            // otherwise muxer fail to write
            if (result < prevOutputPTSUs)
                result = (prevOutputPTSUs - result) + result;
            return result;
        }
    }

    public interface VideoCodecInitListener {
        void onInputSurfaceCreated(Surface inputSurface);
    }
}

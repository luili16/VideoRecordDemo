package com.llx278.record;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.llx278.record.encode.MediaMuxerWrapper;

/**
 *
 * Created by llx on 02/12/2017.
 */

public class CameraPreviewFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "main";
    private CameraPreviewGLSurfaceView cameraPreviewGLSurfaceView;
    private MediaMuxerWrapper mediaMuxerWrapper;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.
                inflate(R.layout.fragment_camera2_preview,container,false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        cameraPreviewGLSurfaceView = view.findViewById(R.id.gl_surface_view);
        TextView tv = view.findViewById(R.id.fps);
        cameraPreviewGLSurfaceView.setUpdateView(tv);
        Button start = view.findViewById(R.id.start);
        Button stop = view.findViewById(R.id.stop);
        start.setOnClickListener(this);
        stop.setOnClickListener(this);

        mediaMuxerWrapper = new MediaMuxerWrapper();
        cameraPreviewGLSurfaceView.setMediaMuxerWrapper(mediaMuxerWrapper);

    }

    private void startRecord() {
        mediaMuxerWrapper.startRecord();
    }

    private void stopRecord() {
        mediaMuxerWrapper.stopRecord();
    }

    @Override
    public void onResume() {
        super.onResume();
        cameraPreviewGLSurfaceView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        cameraPreviewGLSurfaceView.onPause();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.start:
                startRecord();
                break;
            case R.id.stop:
                stopRecord();
                break;
        }
    }
}

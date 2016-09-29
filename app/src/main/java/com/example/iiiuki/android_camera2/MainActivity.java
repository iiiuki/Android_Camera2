package com.example.iiiuki.android_camera2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG=MainActivity.class.getSimpleName();
    //Request camera permission
    private static final int REQUEST_CAMERA_PERMISSION=200;

    private Button mBtnCapture;
    private TextureView mTexturePreview;

    //Kiếm tra trạng thai orientations đầu ra
    private static final SparseIntArray ORIENTATIONS=new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0,90);
        ORIENTATIONS.append(Surface.ROTATION_90,0);
        ORIENTATIONS.append(Surface.ROTATION_180,270);
        ORIENTATIONS.append(Surface.ROTATION_270,180);
    }

    private String mCameraId;
    protected CameraDevice mCameraDevice;
    protected CameraCaptureSession mCameraCaptureSession;
    protected CaptureRequest mCaptureRequest;
    protected CaptureRequest.Builder mCaptureRequestBuilder;
    private Size mSizeImageDimension;
    private ImageReader mImageReader;

    //Lưu ảnh ra file
    private File mFileOutput;
    //
    private boolean mIsFlashSupported;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroudnHandlerThread;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTexturePreview=(TextureView)findViewById(R.id.texture);
        assert mTexturePreview != null; //A set of assert methods. Messages are only displayed when an assert fails.
        mTexturePreview.setSurfaceTextureListener(textureListener);

        mBtnCapture=(Button)findViewById(R.id.btn_takepicture);
        assert mBtnCapture!=null;
        mBtnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takeCapture();
            }
        });

    }

    // CALLBACK
    private final TextureView.SurfaceTextureListener textureListener=new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            // Open camera khi ready
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {
            // Transform you image captured size according to the surface width and height, và thay đổi kích thước ảnh
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private final CameraDevice.StateCallback stateCallback=new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.e(TAG, "onOpened");
            mCameraDevice = cameraDevice;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraDevice.close();
        }

        @Override
        public void onError(CameraDevice cameraDevice, int i) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };

    //Thực hiển việc capture ảnh thông qua CAMERACAPTURESESSION
    private final CameraCaptureSession.CaptureCallback captureCallbackListener=new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
        }
    };

    protected void startBackgroundThread(){
        mBackgroudnHandlerThread=new HandlerThread("Camera Background");
        mBackgroudnHandlerThread.start();
        mBackgroundHandler=new Handler(mBackgroudnHandlerThread.getLooper());
    }

    protected void stopBackgroundThread(){
        //mBackgroudnHandlerThread.quit(); không an toàn dữ liệu vì có thể một số tin nhắn không được gửi khi chấm dứt looper
        mBackgroudnHandlerThread.quitSafely();
        try {
            mBackgroudnHandlerThread.join(); //Waits for this thread to die.
            mBackgroudnHandlerThread=null;
            mBackgroundHandler=null;

        }catch (InterruptedException e){
            e.printStackTrace();
        }
    }

    private void takeCapture() {
        if (mCameraDevice==null){
            Log.e(TAG, "cameraDevice is null");
            return;
        }

        CameraManager cameraManager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try{
            CameraCharacteristics cameraCharacteristics=cameraManager.getCameraCharacteristics(mCameraDevice.getId());
            Size[] jpegSizes=null;
            if (cameraCharacteristics!=null){
                jpegSizes=cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }

            // CAPTURE IMAGE với tuỳ chỉnh kích thước
            int width = 640;
            int height = 480;
            if (jpegSizes != null && jpegSizes.length >0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            ImageReader imageReader=ImageReader.newInstance(width,height,ImageFormat.JPEG,1);

            List<Surface> outputSurfaces=new ArrayList<>(2);
            outputSurfaces.add(imageReader.getSurface());
            outputSurfaces.add(new Surface(mTexturePreview.getSurfaceTexture()));

            final CaptureRequest.Builder captureBuilder=mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // kiểm tra orientation tuỳ thuộc vào mỗi device khác nhau
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

            final File file = new File(Environment.getExternalStorageDirectory()+"/pic.jpg");

            ImageReader.OnImageAvailableListener reanderListener=new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    Image image=null;
                    try {
                        image=imageReader.acquireLatestImage();
                        ByteBuffer buffer=image.getPlanes()[0].getBuffer();
                        byte[] bytes=new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    }catch (IOException e){
                        e.printStackTrace();
                    }catch (Exception e){
                        e.printStackTrace();
                    }finally {
                        image.close();
                    }
                }

                // Lưu ảnh
                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);;
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };

            imageReader.setOnImageAvailableListener(reanderListener,mBackgroundHandler);

            final CameraCaptureSession.CaptureCallback captureListener=new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };

            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    try {
                        cameraCaptureSession.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

                }
            },mBackgroundHandler);

        }catch (Exception e){

        }
    }

    // Khởi tạo camera để preview trong textureview
    private void createCameraPreview() {
        try {
            SurfaceTexture surfaceTexture = mTexturePreview.getSurfaceTexture();
            assert surfaceTexture != null;

            surfaceTexture.setDefaultBufferSize(mSizeImageDimension.getWidth(), mSizeImageDimension.getHeight());
            Surface surface = new Surface(surfaceTexture);

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    if (null == mCameraDevice) {
                        return;
                    }

                    mCameraCaptureSession=cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            },null);


        }catch (CameraAccessException e){

        }
    }

    private void updatePreview() {
        if (mCameraDevice==null){
            Log.e(TAG,"update preview error");
        }
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);

        try {
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            mCameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mSizeImageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            // Kiểm tra permission với android sdk >= 23
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(mCameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (mTexturePreview.isAvailable()) {
            openCamera();
        } else {
            mTexturePreview.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        //closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}

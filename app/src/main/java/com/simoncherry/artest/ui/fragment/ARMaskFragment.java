package com.simoncherry.artest.ui.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.simoncherry.artest.OnGetImageListener;
import com.simoncherry.artest.R;
import com.simoncherry.artest.ui.custom.AutoFitTextureView;
import com.simoncherry.artest.ui.custom.TrasparentTitleView;
import com.simoncherry.artest.rajawali3d.AExampleFragment;
import com.simoncherry.artest.util.BitmapUtils;
import com.simoncherry.artest.util.FileUtils;
import com.simoncherry.artest.util.OBJUtils;
import com.simoncherry.dlib.VisionDetRet;

import org.rajawali3d.Object3D;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.loader.LoaderOBJ;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.renderer.ISurfaceRenderer;
import org.rajawali3d.view.SurfaceView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import hugo.weaving.DebugLog;


public class ARMaskFragment extends AExampleFragment {

    private ImageView ivDraw;

    private float lastX = 0;
    private float lastY = 0;
    private float lastZ = 0;
    private boolean isDrawLandMark = true;
    private boolean isBuildMask = false;

    private Quaternion mQuaternion = new Quaternion();

    private Handler mUIHandler;
    private Paint mFaceLandmarkPaint;


    private static final int MINIMUM_PREVIEW_SIZE = 320;
    private static final String TAG = "ARMaskFragment";

    private TrasparentTitleView mScoreView;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };


    private String cameraId;
    private AutoFitTextureView textureView;
    private CameraCaptureSession captureSession;
    private CameraDevice cameraDevice;
    private Size previewSize;

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;

                    if (mOnGetPreviewListener != null) {
                        mOnGetPreviewListener.deInitialize();
                    }
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                    final Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }

                    if (mOnGetPreviewListener != null) {
                        mOnGetPreviewListener.deInitialize();
                    }
                }
            };

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    private ImageReader previewReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    @SuppressLint("LongLogTag")
    @DebugLog
    private static Size chooseOptimalSize(
            final Size[] choices, final int width, final int height, final Size aspectRatio) {
        final List<Size> bigEnough = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.getHeight() >= MINIMUM_PREVIEW_SIZE && option.getWidth() >= MINIMUM_PREVIEW_SIZE) {
                Log.i(TAG, "Adding size: " + option.getWidth() + "x" + option.getHeight());
                bigEnough.add(option);
            } else {
                Log.i(TAG, "Not adding size: " + option.getWidth() + "x" + option.getHeight());
            }
        }

        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new ARMaskFragment.CompareSizesByArea());
            Log.i(TAG, "Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static ARMaskFragment newInstance() {
        return new ARMaskFragment();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return mLayout;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_ar_mask;
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mScoreView = (TrasparentTitleView) view.findViewById(R.id.results);
        ivDraw = (ImageView) view.findViewById(R.id.iv_draw);

        CheckBox checkShowCrop = (CheckBox) view.findViewById(R.id.check_show_crop);
        CheckBox checkShowModel = (CheckBox) view.findViewById(R.id.check_show_model);
        CheckBox checkLandMark = (CheckBox) view.findViewById(R.id.check_land_mark);
        CheckBox checkDrawMode = (CheckBox) view.findViewById(R.id.check_draw_mode);
        Button btnBuildModel = (Button) view.findViewById(R.id.btn_build_model);
        Button btnSwapFace = (Button) view.findViewById(R.id.btn_swap_face);

        checkShowCrop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    mOnGetPreviewListener.setWindowVisible(true);
                } else {
                    mOnGetPreviewListener.setWindowVisible(false);
                }
            }
        });

        checkShowModel.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ARMaskFragment.AccelerometerRenderer renderer = ((ARMaskFragment.AccelerometerRenderer) mRenderer);
                renderer.mMonkey.setVisible(isChecked);
            }
        });

        checkLandMark.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                isDrawLandMark = isChecked;
            }
        });

        checkDrawMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ((ARMaskFragment.AccelerometerRenderer) mRenderer).toggleWireframe();
            }
        });

        btnBuildModel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mOnGetPreviewListener.setIsNeedMask(true);
            }
        });

        btnSwapFace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        String[] pathArray = new String[2];
                        pathArray[0] = "/storage/emulated/0/dlib/20130821040137899.jpg";
                        pathArray[1] = "/storage/emulated/0/BuildMask/capture_face.jpg";
                        String texture = "/storage/emulated/0/BuildMask/capture_face.jpg";
                        OBJUtils.swapFace(getContext(), pathArray, texture);
                        isBuildMask = true;
                    }
                });
            }
        });
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mUIHandler = new Handler(Looper.getMainLooper());

        mFaceLandmarkPaint = new Paint();
        mFaceLandmarkPaint.setColor(Color.YELLOW);
        mFaceLandmarkPaint.setStrokeWidth(2);
        mFaceLandmarkPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @DebugLog
    @SuppressLint("LongLogTag")
    private void setUpCameraOutputs(final int width, final int height) {
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            SparseArray<Integer> cameraFaceTypeMap = new SparseArray<>();
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, 1);
                    }
                }

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, 1);
                    }
                }
            }

            Integer num_facing_back_camera = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT);
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (num_facing_back_camera != null && num_facing_back_camera > 0) {
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        continue;
                    }
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                final Size largest =
                        Collections.max(
                                Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                                new ARMaskFragment.CompareSizesByArea());

                previewSize =
                        chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);


                final int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                } else {
                    textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                }

                ARMaskFragment.this.cameraId = cameraId;
                return;
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, "Exception!",  e);
        } catch (final NullPointerException e) {

        }
    }

    @SuppressLint("LongLogTag")
    @DebugLog
    private void openCamera(final int width, final int height) {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(this.getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "checkSelfPermission CAMERA");
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
            Log.d(TAG, "open Camera");
        } catch (final CameraAccessException e) {
            Log.e(TAG, "Exception!", e);
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    @DebugLog
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
            if (null != mOnGetPreviewListener) {
                mOnGetPreviewListener.deInitialize();
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    @DebugLog
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        inferenceThread = new HandlerThread("InferenceThread");
        inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());
    }

    @SuppressLint("LongLogTag")
    @DebugLog
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        inferenceThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;

            inferenceThread.join();
            inferenceThread = null;
            inferenceThread = null;
        } catch (final InterruptedException e) {
            Log.e(TAG, "error" ,e );
        }
    }

    private final OnGetImageListener mOnGetPreviewListener = new OnGetImageListener();

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {}
            };

    @SuppressLint("LongLogTag")
    @DebugLog
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            final Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            Log.i(TAG, "Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());


            previewReader =
                    ImageReader.newInstance(
                            previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

            previewReader.setOnImageAvailableListener(mOnGetPreviewListener, backgroundHandler);
            previewRequestBuilder.addTarget(previewReader.getSurface());


            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                            if (null == cameraDevice) {
                                return;
                            }

                            captureSession = cameraCaptureSession;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler);
                            } catch (final CameraAccessException e) {
                                Log.e(TAG, "Exception!", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            Log.e(TAG, "Exception!", e);
        }

        Log.i(TAG, "Getting assets.");
        mOnGetPreviewListener.initialize(getActivity().getApplicationContext(), getActivity().getAssets(), mScoreView, inferenceHandler);
        mOnGetPreviewListener.setLandMarkListener(new OnGetImageListener.LandMarkListener() {
            @Override
            public void onLandmarkChange(final List<VisionDetRet> results) {
                if (!isDrawLandMark) {
                    mUIHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            ivDraw.setImageResource(0);
                        }
                    });
                    return;
                }
                inferenceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (results != null && results.size() > 0) {
                            VisionDetRet ret = results.get(0);
                            float resizeRatio = 1.0f;
                            Rect bounds = new Rect();
                            bounds.left = (int) (ret.getLeft() * resizeRatio);
                            bounds.top = (int) (ret.getTop() * resizeRatio);
                            bounds.right = (int) (ret.getRight() * resizeRatio);
                            bounds.bottom = (int) (ret.getBottom() * resizeRatio);

                            final Bitmap mBitmap = Bitmap.createBitmap(previewSize.getHeight(), previewSize.getWidth(), Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(mBitmap);
                            canvas.drawRect(bounds, mFaceLandmarkPaint);

                            ArrayList<Point> landmarks = ret.getFaceLandmarks();
                            for (Point point : landmarks) {
                                int pointX = (int) (point.x * resizeRatio);
                                int pointY = (int) (point.y * resizeRatio);
                                canvas.drawCircle(pointX, pointY, 2, mFaceLandmarkPaint);
                            }

                            mUIHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    ivDraw.setImageBitmap(mBitmap);
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void onRotateChange(float x, float y, float z) {
                if (mRenderer != null) {
                    boolean isJumpX = false;
                    boolean isJumpY = false;
                    boolean isJumpZ = false;
                    float rotateX = x;
                    float rotateY = y;
                    float rotateZ = z;

                    if (Math.abs(lastX-x) > 90) {
                        Log.e("rotateException", "X");
                        isJumpX = true;
                        rotateX = lastX;
                    }
                    if (Math.abs(lastY-y) > 90) {
                        Log.e("rotateException", "Y");
                        isJumpY = true;
                        rotateY = lastY;
                    }
                    if (Math.abs(lastZ-z) > 90) {
                        Log.e("rotateException", "Z");
                        isJumpZ = true;
                        rotateZ = lastZ;
                    }

                    ((ARMaskFragment.AccelerometerRenderer) mRenderer).setAccelerometerValues(rotateZ, rotateY, -rotateX);

                    if (!isJumpX) {
                        lastX = x;
                    }
                    if (!isJumpY) {
                        lastY = y;
                    }
                    if (!isJumpZ) {
                        lastZ = z;
                    }
                }
            }

            @Override
            public void onTransChange(float x, float y, float z) {
                ARMaskFragment.AccelerometerRenderer renderer = ((ARMaskFragment.AccelerometerRenderer) mRenderer);
                renderer.getCurrentCamera().setPosition(-x/200, y/200, z/100);
            }

            @Override
            public void onMatrixChange(ArrayList<Double> elementList) {
                if (elementList != null && elementList.size() >= 16) {
                    Double[] mArray = elementList.toArray(new Double[16]);
                    double[] mHeadViewMatrix = new double[16];
                    for (int i=0; i<16; i++) {
                        mHeadViewMatrix[i] = mArray[i];
                    }
                    Log.e("mHeadViewMatrix: ", Arrays.toString(mHeadViewMatrix));
                    Matrix4 mHeadViewMatrix4 = new Matrix4(mHeadViewMatrix);
                    mQuaternion.fromMatrix(mHeadViewMatrix4);
                    ((ARMaskFragment.AccelerometerRenderer) mRenderer).mMonkey.rotate(mQuaternion);
                }
            }
        });

        mOnGetPreviewListener.setBuildMaskListener(new OnGetImageListener.BuildMaskListener() {
            @Override
            public void onGetSuitableFace(final Bitmap bitmap, final ArrayList<Point> landmarks) {
                Log.e("rotateList", "onGetSuitableFace");
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        OBJUtils.buildFaceModel(getContext(), bitmap, landmarks);
                        isBuildMask = true;
                    }
                });
            }
        });
    }

    @DebugLog
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    public ISurfaceRenderer createRenderer() {
        return new ARMaskFragment.AccelerometerRenderer(getActivity(), this);
    }

    @Override
    protected void onBeforeApplyRenderer() {
        ((SurfaceView) mRenderSurface).setTransparent(true);
        super.onBeforeApplyRenderer();
    }

    private final class AccelerometerRenderer extends AExampleRenderer {
        private DirectionalLight mLight;
        private Object3D mContainer;
        private Object3D mMonkey;
        private Vector3 mAccValues;

        AccelerometerRenderer(Context context, @Nullable AExampleFragment fragment) {
            super(context, fragment);
            mAccValues = new Vector3();
        }

        @Override
        protected void initScene() {
            try {
                mLight = new DirectionalLight(0.1f, -1.0f, -1.0f);
                mLight.setColor(1.0f, 1.0f, 1.0f);
                mLight.setPower(1);
                getCurrentScene().addLight(mLight);

                mContainer = new Object3D();
                showMaskModel();
                getCurrentScene().addChild(mContainer);

            } catch (Exception e) {
                e.printStackTrace();
            }

            getCurrentScene().setBackgroundColor(0);
        }

        @Override
        protected void onRender(long ellapsedRealtime, double deltaTime) {
            super.onRender(ellapsedRealtime, deltaTime);
            mContainer.setRotation(mAccValues.x, mAccValues.y, mAccValues.z);

            if (isBuildMask) {
                showMaskModel();
                isBuildMask = false;
            }
        }

        void setAccelerometerValues(float x, float y, float z) {
            mAccValues.setAll(x, y, z);
        }

        void toggleWireframe() {
            mMonkey.setDrawingMode(mMonkey.getDrawingMode() == GLES20.GL_TRIANGLES ? GLES20.GL_LINES
                    : GLES20.GL_TRIANGLES);
        }

        void showMaskModel() {
            try {
                if (mMonkey != null) {
                    mMonkey.setY(0);
                    mContainer.removeChild(mMonkey);
                }

                String mImagePath = "/storage/emulated/0/BuildMask/capture_face.jpg";
                String objDir ="BuildMask" + File.separator;
                String objName = FileUtils.getMD5(mImagePath) + "_obj";
                LoaderOBJ parser = new LoaderOBJ(this, objDir + objName);
                parser.parse();
                mMonkey = parser.getParsedObject();
                ATexture texture = mMonkey.getMaterial().getTextureList().get(0);
                mMonkey.getMaterial().removeTexture(texture);
                mMonkey.setScale(0.06f);
                mMonkey.setY(-0.54f);
                mMonkey.setZ(0.25f);

                File sdcard = Environment.getExternalStorageDirectory();
                String textureDir = sdcard.getAbsolutePath() + File.separator + "BuildMask" + File.separator;
                String textureName = FileUtils.getMD5(mImagePath) + ".jpg";
                Bitmap bitmap = BitmapUtils.decodeSampledBitmapFromFilePath(textureDir + textureName, 1024, 1024);
                mMonkey.getMaterial().addTexture(new Texture("canvas", bitmap));
                mMonkey.getMaterial().enableLighting(false);

                mContainer.addChild(mMonkey);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

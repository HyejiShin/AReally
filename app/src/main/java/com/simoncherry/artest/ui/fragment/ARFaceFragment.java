package com.simoncherry.artest.ui.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import android.os.Message;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.simoncherry.artest.MediaLoaderCallback;
import com.simoncherry.artest.OnGetImageListener;
import com.simoncherry.artest.R;
import com.simoncherry.artest.contract.ARFaceContract;
import com.simoncherry.artest.model.ImageBean;
import com.simoncherry.artest.model.Ornament;
import com.simoncherry.artest.nekocode.MyCameraRenderer;
import com.simoncherry.artest.presenter.ARFacePresenter;
import com.simoncherry.artest.rajawali3d.AExampleFragment;
import com.simoncherry.artest.ui.adapter.FaceAdapter;
import com.simoncherry.artest.ui.adapter.FilterAdapter;
import com.simoncherry.artest.ui.adapter.OrnamentAdapter;
import com.simoncherry.artest.ui.custom.AutoFitTextureView;
import com.simoncherry.artest.ui.custom.CustomBottomSheet;
import com.simoncherry.artest.ui.custom.TrasparentTitleView;
import com.simoncherry.artest.util.BitmapUtils;
import com.simoncherry.artest.util.CameraUtils;
import com.simoncherry.artest.util.FileUtils;
import com.simoncherry.artest.util.OBJUtils;
import com.simoncherry.dlib.VisionDetRet;

import org.rajawali3d.Object3D;
import org.rajawali3d.animation.Animation;
import org.rajawali3d.animation.Animation3D;
import org.rajawali3d.animation.AnimationGroup;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.loader.LoaderOBJ;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.renderer.ISurfaceRenderer;
import org.rajawali3d.view.SurfaceView;
import org.reactivestreams.Subscription;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import hugo.weaving.DebugLog;
import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmList;
import io.realm.RealmResults;

public class ARFaceFragment extends AExampleFragment implements ARFaceContract.View{
    private static final String TAG = "ARMaskFragment";

    private TrasparentTitleView mScoreView;
    private AutoFitTextureView textureView;
    private ImageView ivDraw;
    private TextView mTvCameraHint;
    private TextView mTvSearchHint;
    private LinearLayout mLayoutBottomBtn;
    private RecyclerView mRvFace;
    private RecyclerView mRvOrnament;
    private RecyclerView mRvFilter;
    private CustomBottomSheet mFaceSheet;
    private CustomBottomSheet mOrnamentSheet;
    private CustomBottomSheet mFilterSheet;
    private FaceAdapter mFaceAdapter;
    private OrnamentAdapter mOrnamentAdapter;
    private FilterAdapter mFilterAdapter;
    private ProgressDialog mDialog;

    private Context mContext;
    private ARFacePresenter mPresenter;
    private Paint mFaceLandmarkPaint;
    private MyCameraRenderer mCameraRenderer;
    private OnGetImageListener mOnGetPreviewListener = null;

    private List<ImageBean> mImages = new ArrayList<>();
    private List<Ornament> mOrnaments = new ArrayList<>();
    private List<Integer> mFilters = new ArrayList<>();
    private MediaLoaderCallback mediaLoaderCallback = null;
    private Subscription mSubscription = null;
    private Realm realm;
    private RealmResults<ImageBean> realmResults;

    private float lastX = 0;
    private float lastY = 0;
    private float lastZ = 0;
    private boolean isDrawLandMark = true;
    private boolean isBuildMask = false;
    private String mSwapPath = "/storage/emulated/0/dlib/20130821040137899.jpg";
    private int mOrnamentId = -1;

    private Handler mUIHandler;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    private View layout;



    public static ARFaceFragment newInstance() {
        return new ARFaceFragment();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        mContext = getContext();
        mPresenter = new ARFacePresenter(mContext, this);
        return mLayout;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_ar_face;
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        initView(view);
        initFaceSheet();
        initOrnamentSheet();
        initOrnamentData();
        initFilterSheet();
        initRealm();
        initCamera();
    }


    private static final String CAPTURE_PATH = "/AReally";
    private void initView(View view) {
        textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mScoreView = (TrasparentTitleView) view.findViewById(R.id.results);
        ivDraw = (ImageView) view.findViewById(R.id.iv_draw);
        mTvCameraHint = (TextView) view.findViewById(R.id.tv_hint);
        mLayoutBottomBtn = (LinearLayout) view.findViewById(R.id.layout_bottom_btn);

        CheckBox checkShowWindow = (CheckBox) view.findViewById(R.id.check_show_window);
        CheckBox checkShowModel = (CheckBox) view.findViewById(R.id.check_show_model);
        CheckBox checkLandMark = (CheckBox) view.findViewById(R.id.check_land_mark);
        CheckBox checkDrawMode = (CheckBox) view.findViewById(R.id.check_draw_mode);
        CheckBox checkShowOrnament = (CheckBox) view.findViewById(R.id.check_show_ornament);
        Button btnBuildModel = (Button) view.findViewById(R.id.btn_build_model);
        Button btnResetFace = (Button) view.findViewById(R.id.btn_reset_face);
        Button btnFaceSheet = (Button) view.findViewById(R.id.btn_face_sheet);
        Button btnOrnament = (Button) view.findViewById(R.id.btn_ornament_sheet);
        Button btnFilterSheet = (Button) view.findViewById(R.id.btn_filter_sheet);
        Button btn = (Button) view.findViewById(R.id.btn_capture);
        Typeface typeFace = Typeface.createFromAsset(getContext().getAssets(), "fonts/manbal.ttf");
        checkShowModel.setTypeface(typeFace);
        checkShowOrnament.setTypeface(typeFace);
        btnFaceSheet.setTypeface(typeFace);
        btnOrnament.setTypeface(typeFace);
        btnFilterSheet.setTypeface(typeFace);


        final View v = view.findViewById(R.id.rajwali_surface);

        btn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View view) {
                screenshot( v);
            }

            private void screenshot(View view){
                view.setDrawingCacheEnabled(true);
                Bitmap screenshot = view.getDrawingCache();
                String filename = System.currentTimeMillis() + ".jpg";
                try{
                    File f = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + CAPTURE_PATH, filename);

                    OutputStream outStream = new FileOutputStream(f);
                    screenshot.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
                    Toast.makeText(getActivity(), "영상을 캡쳐했습니다", Toast.LENGTH_SHORT).show();
                    outStream.close();
                }catch (IOException e){
                    e.printStackTrace();
                }
                view.setDrawingCacheEnabled(false);
            }

        });


        CompoundButton.OnCheckedChangeListener onCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                int id = buttonView.getId();
                switch (id) {
                    case R.id.check_show_window:
                        if (isChecked) {
                            mOnGetPreviewListener.setWindowVisible(true);
                        } else {
                            mOnGetPreviewListener.setWindowVisible(false);
                        }
                        break;
                    case R.id.check_show_model:
                        ARFaceFragment.AccelerometerRenderer renderer1 = ((ARFaceFragment.AccelerometerRenderer) mRenderer);
                        if (renderer1.mMonkey != null) {
                            renderer1.mMonkey.setVisible(isChecked);
                        }
                        break;
                    case R.id.check_land_mark:
                        isDrawLandMark = isChecked;
                        break;
                    case R.id.check_draw_mode:
                        ((ARFaceFragment.AccelerometerRenderer) mRenderer).toggleWireframe();
                        break;
                    case R.id.check_show_ornament:
                        ARFaceFragment.AccelerometerRenderer renderer2 = ((ARFaceFragment.AccelerometerRenderer) mRenderer);
                        if (renderer2.mOrnament != null) {
                            renderer2.mOrnament.setVisible(isChecked);
                        }
                        break;
                }
            }
        };


        checkShowWindow.setOnCheckedChangeListener(onCheckedChangeListener);
        checkShowModel.setOnCheckedChangeListener(onCheckedChangeListener);
        checkLandMark.setOnCheckedChangeListener(onCheckedChangeListener);
        checkDrawMode.setOnCheckedChangeListener(onCheckedChangeListener);
        checkShowOrnament.setOnCheckedChangeListener(onCheckedChangeListener);

        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                switch (id) {
                    case R.id.btn_build_model:
                        mTvCameraHint.setText("");
                        mTvCameraHint.setVisibility(View.VISIBLE);
                        mOnGetPreviewListener.setIsNeedMask(true);
                        break;
                    case R.id.btn_reset_face:
                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                mPresenter.resetFaceTexture();
                                isBuildMask = true;
                            }
                        });
                        break;
                    case R.id.btn_face_sheet:
                        mFaceSheet.show();
                        break;
                    case R.id.btn_ornament_sheet:
                        mOrnamentSheet.show();
                        break;
                    case R.id.btn_filter_sheet:
                        mLayoutBottomBtn.setVisibility(View.GONE);
                        mFilterSheet.show();
                        break;
                }
            }
        };

        btnBuildModel.setOnClickListener(onClickListener);
        btnResetFace.setOnClickListener(onClickListener);
        btnFaceSheet.setOnClickListener(onClickListener);
        btnOrnament.setOnClickListener(onClickListener);
        btnFilterSheet.setOnClickListener(onClickListener);


    }

    private void initFaceSheet() {
        mFaceAdapter = new FaceAdapter(mContext, mImages);
        mFaceAdapter.setOnItemClickListener(new FaceAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(String path) {
                Toast.makeText(mContext, path, Toast.LENGTH_SHORT).show();
                mSwapPath = path;
                mFaceSheet.dismiss();

                Thread mThread = new Thread() {
                    @Override
                    public void run() {
                        mPresenter.swapFace(mSwapPath);
                        isBuildMask = true;
                        dismissDialog();
                    }
                };
                mThread.start();
            }
        });

        View sheetView = LayoutInflater.from(mContext)
                .inflate(R.layout.layout_bottom_sheet, null);
        mTvSearchHint = (TextView) sheetView.findViewById(R.id.tv_hint);
        mRvFace = (RecyclerView) sheetView.findViewById(R.id.rv_gallery);
        mRvFace.setAdapter(mFaceAdapter);
        mRvFace.setLayoutManager(new GridLayoutManager(mContext, 3));
        mFaceSheet = new CustomBottomSheet(mContext);
        mFaceSheet.setContentView(sheetView);
        mFaceSheet.getWindow().findViewById(R.id.design_bottom_sheet)
                .setBackgroundResource(android.R.color.transparent);
    }

    private void initOrnamentSheet() {
        mOrnamentAdapter = new OrnamentAdapter(mContext, mOrnaments);
        mOrnamentAdapter.setOnItemClickListener(new OrnamentAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
                mOrnamentSheet.dismiss();
                mOrnamentId = position;
                isBuildMask = true;
            }
        });

        View sheetView = LayoutInflater.from(mContext)
                .inflate(R.layout.layout_bottom_sheet, null);
        mRvOrnament = (RecyclerView) sheetView.findViewById(R.id.rv_gallery);
        mRvOrnament.setAdapter(mOrnamentAdapter);
        mRvOrnament.setLayoutManager(new GridLayoutManager(mContext, 4));
        mOrnamentSheet = new CustomBottomSheet(mContext);
        mOrnamentSheet.setContentView(sheetView);
        mOrnamentSheet.getWindow().findViewById(R.id.design_bottom_sheet)
                .setBackgroundResource(android.R.color.transparent);
    }



    private void initOrnamentData() {
        mOrnaments.addAll(mPresenter.getPresetOrnament());
        mOrnamentAdapter.notifyDataSetChanged();
    }

    private void initFilterSheet() {
        for (int i=0; i<21; i++) {
            mFilters.add(i);
        }

        mFilterAdapter = new FilterAdapter(mContext, mFilters);
        mFilterAdapter.setOnItemClickListener(new FilterAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
                String resName = "filter" + position;
                int resId = getResources().getIdentifier(resName, "string", mContext.getPackageName());
                mCameraRenderer.setSelectedFilter(resId);
            }
        });

        View sheetView = LayoutInflater.from(mContext)
                .inflate(R.layout.layout_filter_sheet, null);
        mRvFilter = (RecyclerView) sheetView.findViewById(R.id.rv_filter);
        mRvFilter.setAdapter(mFilterAdapter);
        mRvFilter.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
        mFilterSheet = new CustomBottomSheet(mContext);
        mFilterSheet.setContentView(sheetView);
        mFilterSheet.getWindow().findViewById(R.id.design_bottom_sheet)
                .setBackgroundResource(android.R.color.transparent);
        mFilterSheet.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mFilterSheet.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                mLayoutBottomBtn.setVisibility(View.VISIBLE);
            }
        });
    }

    private void initRealm() {
        realm = Realm.getDefaultInstance();
        realmResults = realm.where(ImageBean.class).equalTo("hasFace", true).findAllAsync();
        realmResults.addChangeListener(new RealmChangeListener<RealmResults<ImageBean>>() {
            @Override
            public void onChange(RealmResults<ImageBean> results) {
                if (results.size() > 0) {
                    Log.e(TAG, "results size: " + results.size());
                    mTvSearchHint.setVisibility(View.GONE);
                    mImages.clear();
                    mImages.addAll(results.subList(0, results.size()));
                    if (mFaceAdapter != null) {
                        mFaceAdapter.notifyDataSetChanged();
                        Log.e(TAG, "getItemCount: " + mFaceAdapter.getItemCount());
                    }
                } else {
                    mTvSearchHint.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void initCamera() {
        CameraManager cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        int orientation = getResources().getConfiguration().orientation;
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        CameraUtils.init(textureView, cameraManager, orientation, rotation);
    }

    private void showDialog(final String title, final String content) {
        mDialog = ProgressDialog.show(mContext, title, content, true);
    }

    private void dismissDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }


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
            CameraUtils.openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            if (mOnGetPreviewListener == null) {
                initGetPreviewListener();
            }
            if (mCameraRenderer == null) {
                mCameraRenderer = new MyCameraRenderer(mContext);
            }
            textureView.setSurfaceTextureListener(mCameraRenderer);
        }

        if (mediaLoaderCallback == null) {
            loadLocalImage();
        }
    }

    @Override
    public void onPause() {
        CameraUtils.closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onDestroy() {

        super.onDestroy();
        if (mSubscription != null) {
            mSubscription.cancel();
        }
        mRvFace.setAdapter(null);
        mRvOrnament.setAdapter(null);
        realmResults.removeAllChangeListeners();
        realm.close();
        CameraUtils.releaseReferences();
    }

    @DebugLog
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        inferenceThread = new HandlerThread("InferenceThread");
        inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());

        CameraUtils.setBackgroundHandler(backgroundHandler);
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

    private void initGetPreviewListener() {
        mOnGetPreviewListener = new OnGetImageListener();
        Thread mThread = new Thread() {
            @Override
            public void run() {
                mOnGetPreviewListener.initialize(
                        getActivity().getApplicationContext(), getActivity().getAssets(), mScoreView, inferenceHandler);
            }
        };
        mThread.start();


        mOnGetPreviewListener.setLandMarkListener(new OnGetImageListener.LandMarkListener() {
            @Override
            public void onLandmarkChange(final List<VisionDetRet> results) {
                mUIHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDrawLandMark) {
                            ivDraw.setImageResource(0);
                        }
                    }
                });

                if (isDrawLandMark) {
                    inferenceHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (results != null && results.size() > 0) {
                                drawLandMark(results.get(0));
                            }
                        }
                    });
                }
            }

            @Override
            public void onRotateChange(float x, float y, float z) {
                rotateModel(x, y, z);
            }

            @Override
            public void onTransChange(float x, float y, float z) {
                ARFaceFragment.AccelerometerRenderer renderer = ((ARFaceFragment.AccelerometerRenderer) mRenderer);
                renderer.getCurrentCamera().setPosition(-x/200, y/200, z/100);
            }

            @Override
            public void onMatrixChange(ArrayList<Double> elementList) {
            }
        });

        mOnGetPreviewListener.setBuildMaskListener(new OnGetImageListener.BuildMaskListener() {
            @Override
            public void onGetSuitableFace(final Bitmap bitmap, final ArrayList<Point> landmarks) {
                Log.e("rotateList", "onGetSuitableFace");
                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        OBJUtils.buildFaceModel(mContext, bitmap, landmarks);
                        isBuildMask = true;
                    }
                });
            }
        });

        CameraUtils.setOnGetPreviewListener(mOnGetPreviewListener);
    }



    private void handleMouthOpen(List<VisionDetRet> results) {
        if (results != null && results.size() > 0) {
            ArrayList<Point> landmarks = results.get(0).getFaceLandmarks();
            int mouthTop = landmarks.get(62).y;
            int mouthBottom = landmarks.get(66).y;
            int mouthAmplitude = mouthBottom - mouthTop;
            String openMouth = "openMouth: " + mouthAmplitude;
            mTvCameraHint.setText(openMouth);
        }
    }

    private void drawLandMark(VisionDetRet ret) {
        float resizeRatio = 1.0f;
        Rect bounds = new Rect();
        bounds.left = (int) (ret.getLeft() * resizeRatio);
        bounds.top = (int) (ret.getTop() * resizeRatio);
        bounds.right = (int) (ret.getRight() * resizeRatio);
        bounds.bottom = (int) (ret.getBottom() * resizeRatio);

        Size previewSize = CameraUtils.getPreviewSize();
        if (previewSize != null) {
            final Bitmap mBitmap = Bitmap.createBitmap(previewSize.getHeight(), previewSize.getWidth(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mBitmap);

            final ArrayList<Point> landmarks = ret.getFaceLandmarks();
            for (Point point : landmarks) {
                int pointX = (int) (point.x * resizeRatio);
                int pointY = (int) (point.y * resizeRatio);
            }

            mUIHandler.post(new Runnable() {
                @Override
                public void run() {
                    ivDraw.setImageBitmap(mBitmap);
                }
            });
        }
    }

    private void rotateModel(float x, float y, float z) {
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

            ((ARFaceFragment.AccelerometerRenderer) mRenderer).setAccelerometerValues(rotateZ, rotateY, -rotateX);

            if (!isJumpX) lastX = x;
            if (!isJumpY) lastY = y;
            if (!isJumpZ) lastZ = z;
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        mSubscription = subscription;
        mSubscription.request(Long.MAX_VALUE);
    }

    @Override
    public ISurfaceRenderer createRenderer() {
        return new ARFaceFragment.AccelerometerRenderer(getActivity(), this);
    }

    @Override
    protected void onBeforeApplyRenderer() {
        ((SurfaceView) mRenderSurface).setTransparent(true);
        super.onBeforeApplyRenderer();
    }

    private final class AccelerometerRenderer extends AExampleRenderer{
        private DirectionalLight mLight;
        private Object3D mContainer;
        private Object3D mMonkey;
        private Object3D mOrnament;
        private Vector3 mAccValues;

        AccelerometerRenderer(Context context, AExampleFragment fragment) {
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
            if (isBuildMask) {
                showMaskModel();
                isBuildMask = false;
                Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mTvCameraHint.setVisibility(View.GONE);
                        }
                    });
                }
            }

            mContainer.setRotation(mAccValues.x, mAccValues.y, mAccValues.z);
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
                boolean isFaceVisible = false;
                boolean isOrnamentVisible = true;
                if (mMonkey != null) {
                    isFaceVisible = mMonkey.isVisible();
                    mMonkey.setScale(1.0f);
                    mMonkey.setPosition(0, 0, 0);
                    mContainer.removeChild(mMonkey);
                }
                if (mOrnament != null) {
                    isOrnamentVisible = mOrnament.isVisible();
                    mOrnament.setScale(1.0f);
                    mOrnament.setPosition(0, 0, 0);
                    mContainer.removeChild(mOrnament);
                }

                String modelDir = OBJUtils.getModelDir();
                String imagePath = modelDir + OBJUtils.IMG_FACE;
                String objPath = OBJUtils.DIR_NAME + File.separator + FileUtils.getMD5(imagePath) + "_obj";
                LoaderOBJ parser = new LoaderOBJ(this, objPath);
                parser.parse();
                mMonkey = parser.getParsedObject();
                ATexture texture = mMonkey.getMaterial().getTextureList().get(0);
                mMonkey.getMaterial().removeTexture(texture);
                mMonkey.setScale(0.06f);
                mMonkey.setY(-0.54f);
                mMonkey.setZ(0.15f);
                mMonkey.setVisible(isFaceVisible);

                String texturePath = FileUtils.getMD5(imagePath) + ".jpg";
                Bitmap bitmap = BitmapUtils.decodeSampledBitmapFromFilePath(modelDir + texturePath, 1024, 1024);
                mMonkey.getMaterial().addTexture(new Texture("canvas", bitmap));
                mMonkey.getMaterial().enableLighting(false);

                mContainer.addChild(mMonkey);

                if (mOrnamentId >= 0 && mOrnaments.size() > mOrnamentId) {
                    Ornament ornament = mOrnaments.get(mOrnamentId);
                    LoaderOBJ objParser1 = new LoaderOBJ(mContext.getResources(), mTextureManager, ornament.getModelResId());
                    objParser1.parse();
                    mOrnament = objParser1.getParsedObject();
                    mOrnament.setScale(ornament.getScale());
                    mOrnament.setPosition(ornament.getOffsetX(), ornament.getOffsetY(), ornament.getOffsetZ());
                    mOrnament.setRotation(ornament.getRotateX(), ornament.getRotateY(), ornament.getRotateZ());
                    int color = ornament.getColor();
                    if (color != ARFacePresenter.NO_COLOR) {
                        mOrnament.getMaterial().setColor(color);
                    }
                    mOrnament.setVisible(isOrnamentVisible);
                    mContainer.addChild(mOrnament);

                    getCurrentScene().clearAnimations();
                    List<Animation3D> animation3Ds = ornament.getAnimation3Ds();
                    if (animation3Ds != null && animation3Ds.size() > 0) {
                        final AnimationGroup animGroup = new AnimationGroup();
                        animGroup.setRepeatMode(Animation.RepeatMode.REVERSE_INFINITE);

                        for (Animation3D animation3D : animation3Ds) {
                            animation3D.setTransformable3D(mOrnament);
                            animGroup.addAnimation(animation3D);
                        }

                        getCurrentScene().registerAnimation(animGroup);
                        animGroup.play();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void loadLocalImage() {
        mediaLoaderCallback = new MediaLoaderCallback(mContext);
        mediaLoaderCallback.setOnLoadFinishedListener(new MediaLoaderCallback.OnLoadFinishedListener() {
            @Override
            public void onLoadFinished(RealmList<ImageBean> data) {
                mPresenter.startFaceScanTask(data);
            }
        });
        getActivity().getSupportLoaderManager().initLoader(0, null, mediaLoaderCallback);
    }


}

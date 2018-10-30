package com.simoncherry.artest.ui.fragment;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;

import com.simoncherry.artest.R;
import com.simoncherry.artest.rajawali3d.AExampleFragment;
import com.simoncherry.artest.util.BitmapUtils;
import com.simoncherry.artest.util.FileUtils;

import org.rajawali3d.Object3D;
import org.rajawali3d.cameras.ArcballCamera;
import org.rajawali3d.debug.CoordinateTrident;
import org.rajawali3d.debug.DebugLight;
import org.rajawali3d.debug.DebugVisualizer;
import org.rajawali3d.debug.GridFloor;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.loader.LoaderOBJ;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;

import java.io.File;


public class ShowMaskFragment extends AExampleFragment {

    public final static String IMG_KEY = "img_key";
    private String mImagePath = null;

    public static ShowMaskFragment newInstance(String imgPath) {
        ShowMaskFragment fragment = new ShowMaskFragment();
        Bundle args = new Bundle();
        args.putString(IMG_KEY, imgPath);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        if (bundle != null) {
            mImagePath = bundle.getString(IMG_KEY, null);
        }
    }

    @Override
    public AExampleRenderer createRenderer() {
        return new ArcBallCameraRenderer(getActivity(), this);
    }

    private final class ArcBallCameraRenderer extends AExampleRenderer {
        public ArcBallCameraRenderer(Context context, @Nullable AExampleFragment fragment) {
            super(context, fragment);
        }

        @Override
        protected void initScene() {
            try {
                DirectionalLight light = new DirectionalLight();
                light.setLookAt(0, 2, 0);
                light.enableLookAt();
                light.setPosition(0, 2, 5);
                light.setPower(1.0f);
                getCurrentScene().addLight(light);

                DebugVisualizer debugViz = new DebugVisualizer(this);
                debugViz.addChild(new GridFloor());
                debugViz.addChild(new DebugLight(light, 0x999900, 1));
                debugViz.addChild(new CoordinateTrident());
                getCurrentScene().addChild(debugViz);

                String objDir ="BuildMask" + File.separator;
                String objName = FileUtils.getMD5(mImagePath) + "_obj";
                LoaderOBJ parser = new LoaderOBJ(this, objDir + objName);
                parser.parse();
                Object3D monkey = parser.getParsedObject();
                ATexture texture = monkey.getMaterial().getTextureList().get(0);
                monkey.getMaterial().removeTexture(texture);
                monkey.setScale(0.65f);

                File sdcard = Environment.getExternalStorageDirectory();
                String textureDir = sdcard.getAbsolutePath() + File.separator + "BuildMask" + File.separator;
                String textureName = FileUtils.getMD5(mImagePath) + ".jpg";
                Bitmap bitmap = BitmapUtils.decodeSampledBitmapFromFilePath(textureDir + textureName, 1024, 1024);
                monkey.getMaterial().addTexture(new Texture("canvas", bitmap));
                monkey.getMaterial().enableLighting(false);

                getCurrentScene().addChild(monkey);

                ArcballCamera arcBall = new ArcballCamera(mContext, ((Activity)mContext).findViewById(R.id.layout_container));
                arcBall.setPosition(4, 4, 4);
                getCurrentScene().replaceAndSwitchCamera(getCurrentCamera(), arcBall);
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }
}

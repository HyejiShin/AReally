package com.simoncherry.artest.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.simoncherry.artest.R;
import com.simoncherry.artest.ui.custom.AutoFitTextureView;
import com.simoncherry.artest.ui.custom.TrasparentTitleView;
import com.simoncherry.artest.ui.fragment.ARFaceFragment;
import com.simoncherry.artest.ui.activity.CameraActivity;

import java.util.Timer;
import java.util.TimerTask;



public class CameraActivity extends AppCompatActivity {
    private FrameLayout splash;
    private Button btn_capture1;

    private static int OVERLAY_PERMISSION_REQ_CODE = 1;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);


        splash = (FrameLayout) findViewById(R.id.splash);
        btn_capture1 = (Button) findViewById(R.id.btn_capture1);



        splash.setOnClickListener(new View.OnClickListener() {
                                      @Override
                                      public void onClick(View view) {
                                          splash.setVisibility(view.GONE);
                                          btn_capture1.setVisibility(view.VISIBLE);

                                      }
                                  }
        );


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this.getApplicationContext())) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            }
        }

        if (null == savedInstanceState) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, ARFaceFragment.newInstance())
                    .commit();
        }

    }

    public void capture(View v){
        Toast.makeText(this, "갤러리에 저장되었습니다.", Toast.LENGTH_SHORT).show();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this.getApplicationContext())) {
                    Toast.makeText(CameraActivity.this, "CameraActivity\", \"SYSTEM_ALERT_WINDOW, permission not granted...", Toast.LENGTH_SHORT).show();
                } else {
                    Intent intent = getIntent();
                    finish();
                    startActivity(intent);
                }
            }
        }
    }


}


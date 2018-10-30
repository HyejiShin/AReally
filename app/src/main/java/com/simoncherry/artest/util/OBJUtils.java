package com.simoncherry.artest.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.os.Environment;
import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.simoncherry.artest.R;
import com.simoncherry.dlib.Constants;
import com.simoncherry.dlib.FaceDet;
import com.simoncherry.dlib.VisionDetRet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;


public class OBJUtils {
    private final static String TAG = OBJUtils.class.getSimpleName();
    public final static String DIR_NAME = "BuildMask";
    public final static String IMG_FACE = "capture_face.jpg";
    public final static String IMG_TEXTURE = "face_texture.jpg";
    public final static String TXT_LANDMARK = "_original";
    public final static String TXT_VERTEX = "_vertices.txt";
    public final static String TXT_UV = "_coordinates.txt";

    private static FaceDet mFaceDet = null;

    public static FaceDet getFaceDet() {
        if (mFaceDet == null) {
            final String targetPath = Constants.getFaceShapeModelPath();
            if (!new File(targetPath).exists()) {
                throw new RuntimeException("cannot find shape_predictor_68_face_landmarks.dat");
            }
            mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());
        }
        return mFaceDet;
    }

    public static String getModelDir() {
        File sdcard = Environment.getExternalStorageDirectory();
        return sdcard.getAbsolutePath() + File.separator + DIR_NAME + File.separator;
    }

    public static void buildFaceModel(Context context, Bitmap bitmap, ArrayList<Point> landmarks) {
        String modelDir = getModelDir();
        Matrix mtx = new Matrix();
        mtx.postRotate(-90.0f);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), mtx, true);
        FileUtils.saveBitmapToFile(context, bitmap, modelDir, IMG_FACE);
        FileUtils.saveBitmapToFile(context, bitmap, modelDir, IMG_TEXTURE);

        String facePath = modelDir + IMG_FACE;
        String landmarkName = FileUtils.getMD5(facePath) + TXT_LANDMARK;
        writeLandmarkToDisk(landmarks, modelDir, landmarkName);
        createVerticesAndCoordinates(context, facePath);
        createObjFile(context, facePath);
    }

    public static void createVerticesAndCoordinates(Context context, String imgPath) {
        Bitmap face = BitmapUtils.decodeSampledBitmapFromFilePath(imgPath, 1024, 1024);
        int faceWidth = face.getWidth();
        int faceHeight = face.getHeight();
        float scale;
        if (faceHeight >= faceWidth) {
            scale = 1024.0f / faceHeight;
        } else {
            scale = 1024.0f / faceWidth;
        }
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        face = Bitmap.createBitmap(face, 0, 0, faceWidth, faceHeight, matrix, false);

        faceWidth = face.getWidth();
        faceHeight = face.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(face, (1024-faceWidth)/2, (1024-faceHeight)/2, null);
        canvas.save(Canvas.ALL_SAVE_FLAG);
        canvas.restore();
        face.recycle();
        face = null;

        String modelDir = getModelDir();
        String imageName = FileUtils.getMD5(imgPath);
        FileUtils.saveBitmapToFile(context, bitmap, modelDir, imageName + ".jpg");
        String texturePath = modelDir + IMG_TEXTURE;
        String textureName= FileUtils.getMD5(texturePath);
        FileUtils.saveBitmapToFile(context, bitmap, modelDir, textureName + ".jpg");
        bitmap.recycle();
        bitmap = null;

        final String imagePath = modelDir + imageName + ".jpg";
        final List<VisionDetRet> faceList = getFaceDet().detect(imagePath);
        if (faceList != null && faceList.size() > 0) {
            ArrayList<Point> landmarks = faceList.get(0).getFaceLandmarks();
            writeLandmarkToDisk(landmarks, modelDir, imageName);
            saveVertices(context, landmarks, modelDir, imageName);
            saveUVMapCoordinate(landmarks, modelDir, imageName);
        }

        Log.e(TAG, "createVerticesAndCoordinates done!");
    }

    public static void saveLandmarkTxt(ArrayList<Point> landmarks, String imgPath) {
        String modelDir = getModelDir();
        String landmarkName = FileUtils.getMD5(imgPath) + TXT_LANDMARK;
        File file = new File(modelDir + landmarkName + ".txt");
        if (!file.exists()) {
            writeLandmarkToDisk(landmarks, modelDir, landmarkName);
        }
    }

    public static void writeLandmarkToDisk(ArrayList<Point> landmarks, String path, String name) {
        String jsonString = JSON.toJSONString(landmarks);
        Log.e(TAG, "landmarks: " + jsonString);

        String fileName = path + name + ".txt";
        try {
            int i = 0;
            FileWriter writer = new FileWriter(fileName);
            for (Point point : landmarks) {
                int pointX = point.x;
                int pointY = point.y ;
                String landmark = String.valueOf(pointX) + " " + String.valueOf(pointY) + "\n";
                Log.e(TAG, "write landmark[" + String.valueOf(i) + "]: " + landmark);
                i++;
                writer.write(landmark);
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }
    }

    private static void saveVertices(Context context, ArrayList<Point> landmarks, String path, String name) {
        ArrayList<Point> vertices = new ArrayList<>();
        for (int i=0; i<40; i++) {
            vertices.add(new Point(0, 0));
        }
        for (int i=0; i<vertices.size(); i++) {
            vertices.set(i, getVertexByIndex(landmarks, i));
        }

        Point chin_point = landmarks.get(8);
        Point first_point = vertices.get(0);
        float z_scale = (first_point.x - chin_point.x) / 30f / -2.15f;

        ArrayList<Float> zAxisPoints = new ArrayList<>();
        InputStream is = context.getResources().openRawResource(R.raw.z_axis);
        try {
            InputStreamReader reader = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(reader);
            for(String str; (str = br.readLine()) != null; ) {
                zAxisPoints.add(Float.parseFloat(str));
            }
            br.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.e(TAG, "get z-axis: " + zAxisPoints.toString());

        DecimalFormat decimalFormat = new DecimalFormat(".000000");
        String fileName = path + name + TXT_VERTEX;
        try {
            int i = 0;
            FileWriter writer = new FileWriter(fileName);
            for (Point point : vertices) {
                float pointZ = zAxisPoints.get(i) * z_scale;
                float pointX = (point.x - chin_point.x) / 30f;
                float pointY = (point.y - chin_point.y) / -30f;
                String landmark = "v " + decimalFormat.format(pointX) + " " + decimalFormat.format(pointY) + " " + decimalFormat.format(pointZ) + "\n";
                Log.e(TAG, "write vertex[" + String.valueOf(i) + "]: " + landmark);
                i++;
                writer.write(landmark);
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }
    }

    private static void saveUVMapCoordinate(ArrayList<Point> landmarks, String path, String name) {
        ArrayList<Point> coordinates = new ArrayList<>();
        for (int i=0; i<40; i++) {
            coordinates.add(new Point(0, 0));
        }

        for (int i=0; i<coordinates.size(); i++) {
            coordinates.set(i, getCoordinateByIndex(landmarks, i));
        }

        DecimalFormat decimalFormat = new DecimalFormat(".0000");
        String fileName = path + name + TXT_UV;
        try {
            int i = 0;
            FileWriter writer = new FileWriter(fileName);
            for (Point point : coordinates) {
                float x = point.x / 1024.0f;
                float y = 1 - point.y / 1024.0f;
                String string = "vt " + decimalFormat.format(x) + " " + decimalFormat.format(y) + "\n";
                Log.e(TAG, "write coordinates[" + String.valueOf(i) + "]: " + string);
                i++;
                writer.write(string);
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }
    }

    public static void createObjFile(Context context, String imgPath) {
        String modelDir = getModelDir();
        String baseMtlPath = modelDir + "base_mask.mtl";
        File mtlFile = new File(baseMtlPath);
        if (!mtlFile.exists()) {
            FileUtils.copyFileFromRawToOthers(context, R.raw.base_mask, baseMtlPath);
        }

        String baseTexturePath = modelDir + "base_texture.jpg";
        File textureFile = new File(baseTexturePath);
        if (!textureFile.exists()) {
            FileUtils.copyFileFromRawToOthers(context, R.raw.base_texture, baseTexturePath);
        }

        StringBuilder stringBuilder = new StringBuilder();
        InputStream is = context.getResources().openRawResource(R.raw.base_mask_obj);
        try {
            InputStreamReader reader = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(reader);
            for(String str; (str = br.readLine()) != null; ) {
                stringBuilder.append(str).append("\n");
            }
            br.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        String obj_str = stringBuilder.toString();
        Log.e(TAG, "read base_mask_obj: " + obj_str);

        String[] ss = obj_str.split("\n");
        List<String> vertices = readVerticesFromTxt(imgPath);
        List<String> coordinates = readCoordinatesFromTxt(imgPath);
        for (int i=4; i<44; i++) {
            ss[i] = vertices.get(i-4);
        }

        for (int i=44; i<84; i++) {
            ss[i] = coordinates.get(i-44);
        }


        String objName = FileUtils.getMD5(imgPath);
        String objPath = modelDir + objName + "_obj";

        try {
            int i = 0;
            FileWriter writer = new FileWriter(objPath);
            for (String s : ss) {
                Log.e(TAG, "write obj[" + String.valueOf(i) + "]: " + s);
                i++;
                writer.write(s + "\n");
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }

        Log.e(TAG, "doCreateObjFile done!");
    }

    private static List<String> readVerticesFromTxt(String imgPath) {
        String modelDir = getModelDir();
        String vertexName = FileUtils.getMD5(imgPath) + TXT_VERTEX;
        String vertexPath = modelDir + vertexName;

        final List<String> vertices = new ArrayList<>();
        try {
            FileReader fileReader = new FileReader(vertexPath);
            BufferedReader br = new BufferedReader(fileReader);
            for(String str; (str = br.readLine()) != null; ) {
                vertices.add(str);
            }
            br.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return vertices;
    }

    private static List<String> readCoordinatesFromTxt(String imgPath) {
        String modelDir = getModelDir();
        String uvName = FileUtils.getMD5(imgPath) + TXT_UV;
        String uvPath = modelDir + uvName;

        final List<String> coordinate = new ArrayList<>();
        try {
            FileReader fileReader = new FileReader(uvPath);
            BufferedReader br = new BufferedReader(fileReader);
            for(String str; (str = br.readLine()) != null; ) {
                coordinate.add(str);
            }
            br.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return coordinate;
    }

    private static void detectAndSaveLandmark(String imgPath) {
        List<VisionDetRet> faceList = getFaceDet().detect(imgPath);
        if (faceList != null && faceList.size() > 0) {
            String modelDir = getModelDir();
            String landmarkPath = FileUtils.getMD5(imgPath) + TXT_LANDMARK;
            writeLandmarkToDisk(faceList.get(0).getFaceLandmarks(), modelDir, landmarkPath);
        }
    }

    public static void createTexture(Context context, String srcPath, String dstPath) {
        Bitmap face = BitmapUtils.decodeSampledBitmapFromFilePath(srcPath, 1024, 1024);
        int faceWidth = face.getWidth();
        int faceHeight = face.getHeight();
        float scale;
        if (faceHeight >= faceWidth) {
            scale = 1024.0f / faceHeight;
        } else {
            scale = 1024.0f / faceWidth;
        }
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        face = Bitmap.createBitmap(face, 0, 0, faceWidth, faceHeight, matrix, false);

        faceWidth = face.getWidth();
        faceHeight = face.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(face, (1024-faceWidth)/2, (1024-faceHeight)/2, null);
        canvas.save(Canvas.ALL_SAVE_FLAG);
        canvas.restore();
        face.recycle();
        face = null;

        String modelDir = getModelDir();
        String textureName = FileUtils.getMD5(dstPath)  + ".jpg";
        FileUtils.saveBitmapToFile(context, bitmap, modelDir, textureName);
        bitmap.recycle();
        bitmap = null;
    }

    public static void swapFace(Context context, String[] pathArray,  String resultPath) {
        String modelDir = getModelDir();

        for (int i=0; i<2; i++) {
            String landmarkName = FileUtils.getMD5(pathArray[i]) + TXT_LANDMARK;
            File file = new File(modelDir + landmarkName);
            //if (!file.exists())
            {
                detectAndSaveLandmark(pathArray[i]);
            }
        }

        String swapResult = JNIUtils.doFaceSwap(pathArray);

        if (swapResult != null) {
            final File file = new File(swapResult);
            if (file.exists()) {
                createTexture(context, swapResult, resultPath);
            }
        }
    }

    private static Point getVertexByIndex(ArrayList<Point> landmarks, int index) {
        switch (index) {
            case 0:
                return landmarks.get(36);
            case 1:
                return landmarks.get(39);
            case 2:
                return getMiddlePoint(landmarks, 40, 41);
            case 3:
                return getMiddlePoint(landmarks, 37, 38);
            case 4:
                return landmarks.get(20);
            case 5:
                return landmarks.get(27);
            case 6:
                return landmarks.get(23);
            case 7:
                return landmarks.get(42);
            case 8:
                return landmarks.get(35);
            case 9:
                return getMiddlePoint(landmarks, 43, 44);
            case 10:
                return landmarks.get(45);
            case 11:
                return getMiddlePoint(landmarks, 46, 47);
            case 12:
                return getMiddlePoint(landmarks, 31, 35);
            case 13:
                return landmarks.get(31);
            case 14:
                return landmarks.get(33);
            case 15:
                return landmarks.get(62);
            case 16:
                return landmarks.get(48);
            case 17:
                return landmarks.get(54);
            case 18:
                return landmarks.get(57);
            case 19:
            case 31:
                return landmarks.get(8);
            case 20:
                return getMiddlePoint(landmarks, 20, 23);
            case 21:
                return landmarks.get(25);
            case 22:
                return landmarks.get(16);
            case 23:
            case 36:
                return landmarks.get(15);
            case 24:
            case 34:
                return landmarks.get(12);
            case 25:
            case 32:
                return landmarks.get(10);
            case 26:
                return landmarks.get(18);
            case 27:
                return landmarks.get(0);
            case 28:
            case 37:
                return landmarks.get(1);
            case 29:
            case 35:
                return landmarks.get(4);
            case 30:
            case 33:
                return landmarks.get(6);
            case 38:
                return getMiddlePoint(landmarks, 36, 39);
            case 39:
                return getMiddlePoint(landmarks, 42, 45);
            default:
                return landmarks.get(0);
        }
    }

    private static Point getCoordinateByIndex(ArrayList<Point> landmarks, int index) {
        switch (index) {
            case 0:
                return landmarks.get(18);
            case 1:
                return landmarks.get(0);
            case 2:
                return landmarks.get(36);
            case 3:
                return getDoNotKnowHowToSay(landmarks, 17, 0, 1);
            case 4:
                return getIntersectPoint(landmarks, 5, 3);
            case 5:
                return getIntersectPoint(landmarks, 6, 5);
            case 6:
                return landmarks.get(48);
            case 7:
                return getDoNotKnowHowToSay(landmarks, 8, 6, 7);
            case 8:
                return landmarks.get(57);
            case 9:
                return landmarks.get(62);
            case 10:
                return landmarks.get(33);
            case 11:
                return landmarks.get(31);
            case 12:
                return getMiddlePoint(landmarks, 31, 35);
            case 13:
                return landmarks.get(27);
            case 14:
                return getMiddlePoint(landmarks, 40, 41);
            case 15:
                return landmarks.get(39);
            case 16:
                return landmarks.get(20);
            case 17:
                return getMiddlePoint(landmarks, 37, 38);
            case 18:
                return getMiddlePoint(landmarks, 20, 23);
            case 19:
                return landmarks.get(23);
            case 20:
                return landmarks.get(42);
            case 21:
                return landmarks.get(35);
            case 22:
                return getMiddlePoint(landmarks, 46, 47);
            case 23:
                return landmarks.get(45);
            case 24:
                return getMiddlePoint(landmarks, 43, 44);
            case 25:
                return landmarks.get(25);
            case 26:
                return landmarks.get(16);
            case 27:
                return getDoNotKnowHowToSay(landmarks, 26, 15, 16);
            case 28:
                return landmarks.get(54);
            case 29:
                return getIntersectPoint(landmarks, 10, 12);
            case 30:
                return getIntersectPoint(landmarks, 11, 13);
            case 31:
                return landmarks.get(15);
            case 32:
                return landmarks.get(12);
            case 33:
                return landmarks.get(10);
            case 34:
                return landmarks.get(8);
            case 35:
                return landmarks.get(6);
            case 36:
                return landmarks.get(1);
            case 37:
                return landmarks.get(4);
            case 38:
                return getMiddlePoint(landmarks, 36, 39);
            case 39:
                return getMiddlePoint(landmarks, 42, 45);
            default:
                return landmarks.get(0);
        }
    }

    private static Point getMiddlePoint(ArrayList<Point> landmarks, int p1, int p2) {
        Point point1 = landmarks.get(p1);
        Point point2 = landmarks.get(p2);
        return new Point((point1.x+point2.x)/2, (point1.y+point2.y)/2);
    }

    private static Point getIntersectPoint(ArrayList<Point> landmarks, int p1, int p2) {
        Point point1 = landmarks.get(p1);
        Point point2 = landmarks.get(p2);
        return new Point(point1.x, point2.y);
    }

    private static Point getDoNotKnowHowToSay(ArrayList<Point> landmarks, int p1, int p2, int p3) {
        Point point1 = landmarks.get(p1);
        Point point2 = landmarks.get(p2);
        Point point3 = landmarks.get(p3);
        return new Point(point1.x, (point2.y + point3.y)/2);
    }
}

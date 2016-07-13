package com.arlong.stepcounter;

import android.app.Activity;
import android.graphics.PointF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


public class SensorReadoutActivity extends Activity implements  OnClickListener,RefreshableView{

    // Sensor 配置
    private static final String TAG = "SensorScan";
    private SensorManager sensorManager;

    private Sensor accSensor;
    private Sensor gyroSensor;

    // 计步参数
    private int accNo = 0;
    public int stepNo = 0;
    public float stepLength;
    public float heading;
    private float[] accold1 = new float[2];
    private float[] accold2 = new float[2];
    private float[] accnew = new float[2];
    private float[] peakmax = new float[2];
    private float[] peakmin = new float[2];
    private float deltaTime = 0.15f;
    private float deltaA = 1.4f;
    private boolean peakmaxReady;
    private float locationx, locationy;
    //采样频率
    public static final int sampleRate = 20;

    private TextView tvStep;
    private TextView tvStepLength;
    private TextView tvHeading;
    private TextView tvLocation;
    private MultiTouchView multiTouchView;

    //平均滤波参数
    private int N_windows = 5;
    private float[] value_buf = new float[N_windows];
    private int i_filter=0;

    // 读取传感器数据时用到的变量
    long timeStart=0;// the timestamp of the first sample，因为event.timestamp格式为long，这里是为了保证相减前不丢数据，否则后面间隔可能为负
    float timestamp;// 距离first sample 的时间间隔

    public float beta = 0.3f;								// 2 * proportional gain (Kp)
    public float q0 = 1.0f, q1 = 0.0f, q2 = 0.0f, q3 = 0.0f;	// quaternion of sensor frame relative to auxiliary frame

    protected SiteMapDrawable map;
    /**
     * @uml.property name="user"
     * @uml.associationEnd
     */
    private Thread ticker;
    private int xTick = 0;
    private Madgwick mMadgwick = new Madgwick();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "on Create");
        initUI();
        initSensors();
        MultiTouchDrawable.setGridSpacing(100,100);

        map = new SiteMapDrawable(this,this);
        map.setScale(30, 30);
        map.setPos(0, 0, 30, 30, (float) Math.PI, true);
        map.setSize(map.width / 50, map.height / 50);
        multiTouchView.setRearrangable(false);
        multiTouchView.addDrawable(map);

    }
    //Sensor 函数
    //按键监听
    public void onClick(View v) {
        switch (v.getId())
        {
            case R.id.scanStart:
                try {
                    doStartScan();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;
            case R.id.scanStop:
                try {
                    doStopScan();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;
            case R.id.btnClear:
                try {
                    doClear();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;
        }
    }


    //****************************************/
    // 初始化函数
    //******************************************/
    private void initUI(){
        ((Button)findViewById(R.id.scanStart)).setOnClickListener(this);
        ((Button)findViewById(R.id.scanStop)).setOnClickListener(this);
        ((Button)findViewById(R.id.btnClear)).setOnClickListener(this);
        tvStep = (TextView)findViewById(R.id.tvStep);
        tvStepLength = (TextView)findViewById(R.id.tvStepLength);
        tvHeading = (TextView)findViewById(R.id.tvHeading);
        tvLocation = (TextView)findViewById(R.id.tvLocation);
        multiTouchView = ((MultiTouchView) findViewById(R.id.mapView));
    }
    private void initSensors(){
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    //****************************************/
    // 计步函数
    //******************************************/
    private boolean stepDetecter(float Time,float acc){
        float[] normAcc = new float[2];
        boolean isStep;
        isStep = false;
        normAcc[1] = acc;
        normAcc[0] = Time;

        if(xTick == 0)
            accold1 = normAcc;
        else if(accNo == 1)
            accold2 = normAcc;
        else{
            accnew = normAcc;
            if(accold1[1]<accold2[1] && accold2[1] > accnew[1]){
                if (accold2[0]-peakmin[0]> deltaTime)
                    peakmax = accold2;
                    peakmaxReady = true;
            }
            else if(accold1[1]>accold2[1] && accold2[1] < accnew[1]){
                if (accold2[0] - peakmax[0] >deltaTime  && peakmaxReady){
                    peakmin = accold2;
                    if(peakmax[1] - peakmin[1] >deltaA){
                        stepLength =(float)( 0.5 * Math.pow(peakmax[1] - peakmin[1],0.25) );
                        String str = String.format("%.2f",stepLength);
                        tvStepLength.setText(str);
                        isStep = true;
                        peakmaxReady = false;
                    }
                }

            }
            accold1 = accold2;
            accold2 = accnew;
        }
        return isStep;
    }
    //****************************************/
    // 平滑滤波
    //******************************************/

    private float filter(float data)
    {
        int count;
        float  sum=0;
        value_buf[i_filter] = data;
        i_filter++;
        if ( i_filter == N_windows )   i_filter = 0;
        for ( count=0;count<N_windows;count++){
            sum = sum + value_buf[count];
        }
        return (sum/N_windows);
    }
    //****************************************/
    // 传感器数据读取开始与停止函数
    //******************************************/
    private void doStartScan() throws Exception{
        Log.d(TAG, "Create File");

        timeStart = 0;//开始时时间归零
        peakmax[0] = 0;peakmin[0] = 0;
        peakmax[1] = 0;peakmin[1] =0;
        Log.d(TAG, "Start Listener");
        ticker = new Ticker(this);
        ticker.start();
        sensorManager.registerListener((SensorEventListener) ticker, accSensor, 1000 / sampleRate);
        sensorManager.registerListener((SensorEventListener) ticker, gyroSensor, 1000 / sampleRate);
        doNotify("Scan Starting");

    }
    private void doStopScan() throws Exception {
        Log.d(TAG,"Stop Scan");
        Log.d(TAG, "Stop Listener");
        sensorManager.unregisterListener((SensorEventListener) ticker);
        ticker.interrupt();
        ticker.join();
        ticker = null;
        doNotify("Scan Stop!");
    }
    private void doClear() throws Exception{
        xTick = 0;
        timeStart=0;
        stepNo = 0;
        q0 = 1.0f; q1 = 0.0f; q2 = 0.0f; q3 = 0.0f;
        tvStep.setText(String.format("%s", stepNo));
    }

    //****************************************/
    // Lagrange 二次插值
    //******************************************/
    public float Lagrange2_interpolation(float[] x, float[] y, float xk){
        float x0 = x[0];
        float y0 = y[0];
        float x1 = x[1];
        float y1 = y[1];
        float x2 = x[2];
        float y2 = y[2];
        float yk;
        yk = y0 * ( (xk-x1)*(xk-x2)/(x0-x1)/(x0-x2) ) + y1 * ( (xk-x0)*(xk-x2)/(x1-x0)/(x1-x2) ) + y2 * ( (xk-x0)*(xk-x1)/(x2-x0)/(x2-x1) );
        return yk;
    }

    //****************************************/
    // 屏幕显示通知函数的简化
    //******************************************/
    public void doNotify(String message) {
        doNotify(message, false);
    }
    public void doNotify(String message, boolean longMessage) {
        (Toast.makeText(this, message, longMessage ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT)).show();
        Log.d(TAG, "Notify: " + message);
    }

    /**
     * Periodically called by the ticker
     *
     * @param accEvent,gyroEvent
     *          current sensor data.
     */
    protected void doPdrDraw(SensorEvent accEvent, SensorEvent gyroEvent) {

        float NS2S=1.0f/1000000000.0f;//纳秒转为秒
        if(timeStart==0){
            timeStart=accEvent.timestamp;
            timestamp=0;
        }
        else
            timestamp=(accEvent.timestamp-timeStart)*NS2S;
        float yvalue = (float)Math.sqrt(accEvent.values[0]*accEvent.values[0]+accEvent.values[1]*accEvent.values[1]+accEvent.values[2]*accEvent.values[2]);
        float lpyvalue = 0;
        if(xTick+1>N_windows)
            lpyvalue = filter(yvalue);
        else
            value_buf[xTick] = yvalue;
        float[] euler = mMadgwick.rotMat2euler(mMadgwick.quatern2rotMat(mMadgwick.MadgwickAHRSupdateGyro(gyroEvent.values[0], gyroEvent.values[1], gyroEvent.values[2])));
        heading = euler[2];
        tvHeading.setText(String.format("%.2f", heading * 180 / Math.PI));
        //Log.d(TAG,String.format("%.2f,%f",timestamp,lpyvalue));
        if (stepDetecter(timestamp,lpyvalue)){
            stepNo= stepNo+1;
            if(stepNo==1) {
                locationx = (float)(0f + stepLength * Math.sin(heading));
                locationy = (float)(0f + stepLength * Math.cos(heading));
            }
            else {
                locationx = (float)(locationx + stepLength * Math.sin(heading));
                locationy = (float)(locationy + stepLength * Math.cos(heading));
            }
            tvStep.setText(String.format("%s",stepNo));
            tvLocation.setText(String.format("( %.2f, %.2f)", locationx, locationy));
            map.addStep(new PointF(-locationx,locationy));
            map.setPos(-locationx * map.getScaleX(), locationy * map.getScaleY(), 30, 30, (float) Math.PI, true);
            multiTouchView.invalidate();
        }
        xTick++;
    }
    @Override
    public void invalidate() {
        if (multiTouchView != null) {
            multiTouchView.invalidate();
        }
    }

}
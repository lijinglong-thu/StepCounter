package com.arlong.stepcounter;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

public class SensorReadoutActivity extends Activity {

    // Sensor 配置
    private static final String TAG = "SensorScan";
    private SensorManager sensorManager;

    private Sensor accSensor;
    private Sensor gyroSensor;
    private Sensor magSensor;

    private float[] accValues = new float[4];
    private float[] gyroValues = new float[4];
    private float[] magValues = new float[4];

    // 计步参数
    private int accNo = 0;
    public int stepNo = 0;
    private float[] accold1 = new float[2];
    private float[] accold2 = new float[2];
    private float[] accnew = new float[2];
    private float[] peakmax = new float[2];
    private float[] peakmin = new float[2];
    private float deltaTime = 0.15f;
    private float deltaA = 1.4f;
    private boolean peakmaxReady;

    //采样频率
    public static final int sampleRate = 20;

    // UI 配置
    private Button btnScanStart;
    private Button btnScanStop;
    private Button btnClear;
    private TextView tvStep;
    private LinearLayout chartLyt;

    //平均滤波参数
    private int N_windows = 5;
    private float[] value_buf = new float[N_windows];
    private int i_filter=0;

    // 读取传感器数据时用到的变量
    long timeStart=0;// the timestamp of the first sample，因为event.timestamp格式为long，这里是为了保证相减前不丢数据，否则后面间隔可能为负
    float timestamp;// 距离first sample 的时间间隔
    /**
     * The displaying component
     */
    private GraphicalView chartView;

    /**
     * Dataset of the graphing component//sensor��ͼ���ݼ�
     */
    private XYMultipleSeriesDataset sensorData;

    /**
     * Renderer for actually drawing the graph//��ͼ��Ⱦ
     */
    private XYMultipleSeriesRenderer renderer;
    /**
     * Data channels. Corresponds to <code>SensorEvent.values</code>. Individual
     * channels may be set to null to indicate that they must not be painted.
     */
    private XYSeries channel[];
    /**
     * The ticker thread takes care of updating the UI
     */
    private Thread ticker;
    /**
     * For moving the viewport of the graph
     */
    private int xTick = 0;

    /**
     * For moving the viewport of the grpah
     */
    private int lastMinX = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "on Create");
        initUI();
        initSensors();
        btnScanStart.setOnClickListener(btnListener);
        btnScanStop.setOnClickListener(btnListener);
        btnClear.setOnClickListener(btnListener);
        chartLyt = (LinearLayout) findViewById(R.id.sensorChart);
        sensorData = new XYMultipleSeriesDataset();
        renderer = new XYMultipleSeriesRenderer();
        renderer.setGridColor(Color.DKGRAY);
        renderer.setShowGrid(true);
        renderer.setXAxisMin(0.0);
        renderer.setXTitle(getString(R.string.samplerate, 1000 / sampleRate));
        renderer.setXAxisMax(10000 / (1000 / sampleRate)); // 10 seconds wide
        renderer.setXLabels(10); // 1 second per DIV
        renderer.setChartTitle(" ");
        renderer.setYLabelsAlign(Paint.Align.RIGHT);
        chartView = ChartFactory.getLineChartView(this, sensorData, renderer);
        chartLyt.addView(chartView);
        float textSize = new TextView(this).getTextSize();
        float upscale = textSize / renderer.getLegendTextSize();
        renderer.setLabelsTextSize(textSize);
        renderer.setLegendTextSize(textSize);
        renderer.setChartTitleTextSize(textSize);
        renderer.setAxisTitleTextSize(textSize);
        renderer.setFitLegend(true);
        int[] margins = renderer.getMargins();
        margins[0] *= upscale;
        margins[1] *= upscale;
        margins[2] = (int) (2 * renderer.getLegendTextSize());
        renderer.setMargins(margins);
        //setContentView(R.layout.activity_main);
    }

    //Sensor 函数
    //按键监听
    private Button.OnClickListener btnListener = new Button.OnClickListener()
    {
        public void onClick(View v){
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
    };

    //****************************************/
    // 初始化函数
    //******************************************/
    private void initUI(){
        btnScanStart = (Button)findViewById(R.id.scanStart);
        btnScanStop = (Button)findViewById(R.id.scanStop);
        btnClear = (Button)findViewById(R.id.btnClear);
        tvStep = (TextView)findViewById(R.id.tvStep);
    }
    private void initSensors(){
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        //gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }


    //Sensor Listener
 /*   private SensorEventListener mySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float NS2MS=1.0f/1000000.0f; //event中timestamp为纳秒，纳秒转为毫秒
            float NS2S=1.0f/1000000000.0f;//纳秒转为秒
            if(timeStart==0){
                timeStart=event.timestamp;
                timestamp=0;
            }
            else
                timestamp=(event.timestamp-timeStart)*NS2S;
            switch(event.sensor.getType()){
                case Sensor.TYPE_ACCELEROMETER:
                    accNo = accNo+1;
                    //onAccSensorChanged(event.values);
                    if (stepDetecter(timestamp,event.values)){
                        stepNo= stepNo+1;
                        tvStep.setText(Integer.toString(stepNo));
                    }
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    //onGyroSensorChanged(event.values);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    //onMagSensorChanged(event.values);
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }; */

    //****************************************/
    // 传感器变化时读取、存储系列函数
    //******************************************/
    private void onAccSensorChanged(float[] acc){

        //显示存储
        StringBuilder stringBuilder=new StringBuilder();
        accValues[0] = timestamp;
        accValues[1] = acc[0];
        accValues[2] = acc[1];
        accValues[3] = acc[2];
    }
    private void onMagSensorChanged(float[] mag){
        StringBuilder stringBuilder=new StringBuilder();
        magValues[0] = timestamp;
        magValues[1] = mag[0];
        magValues[2] = mag[1];
        magValues[3] = mag[2];
    }
    private void onGyroSensorChanged(float[] gyro){
        StringBuilder stringBuilder=new StringBuilder();
        gyroValues[0] = timestamp;
        gyroValues[1] = gyro[0];
        gyroValues[2] = gyro[1];
        gyroValues[3] = gyro[2];
    }
    //****************************************/
    // 计步函数
    //******************************************/
    private boolean stepDetecter(float Time,float acc){
        float[] normAcc = new float[2];
        float stepLength;
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
        sensorData.clear();
        chartView.repaint();
        xTick = 0;
        stepNo = 0;
        tvStep.setText(Integer.toString(stepNo));
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
     * @param currentEvent
     *          current sensor data.
     */
    protected void onTick(SensorEvent currentEvent) {

        float NS2S=1.0f/1000000000.0f;//纳秒转为秒
        if(timeStart==0){
            timeStart=currentEvent.timestamp;
            timestamp=0;
        }
        else
            timestamp=(currentEvent.timestamp-timeStart)*NS2S;
        float xvalue = timestamp;
        float yvalue = (float)Math.sqrt(currentEvent.values[0]*currentEvent.values[0]+currentEvent.values[1]*currentEvent.values[1]+currentEvent.values[2]*currentEvent.values[2]);
        float lpyvalue = 0;
        if(xTick+1>N_windows)
            lpyvalue = filter(yvalue);
        else
            value_buf[xTick] = yvalue;
        float[] valueSet = new float[2];
        valueSet[0] = yvalue; valueSet[1] = lpyvalue;
        if (stepDetecter(timestamp,lpyvalue)){
            stepNo= stepNo+1;
            tvStep.setText(Integer.toString(stepNo));
        }
        if (xTick == 0) {
            // Dirty, but we only learn a few things after getting the first event.
            configure(valueSet);
            //setContentView(chartView);
        }

        if (xTick > renderer.getXAxisMax()) {
            renderer.setXAxisMax(xTick);
            renderer.setXAxisMin(++lastMinX);
        }
        if (xTick < renderer.getXAxisMin()) {
            renderer.setXAxisMin(xTick);
            renderer.setXAxisMax(xTick+200);
        }

        fitYAxis(valueSet);
        for (int i = 0; i < channel.length; i++) {
            if (channel[i] != null) {
                channel[i].add(xTick, valueSet[i]);
            }
        }
        xTick++;
        chartView.repaint();
    }

    /**
     * Make sure the Y axis is large enough to display the graph
     *
     * @param value
     *          current event
     */
    private void fitYAxis(float[] value) {
        double min = renderer.getYAxisMin(), max = renderer.getYAxisMax();
        for (int i = 0; i < channel.length; i++) {
            if (value[i] < min) {
                min = value[i];
            }
            if (value[i] > max) {
                max = value[i];
            }
        }
        float sum = 0;
        for (int i = 0; i < value.length; i++) {
            sum += value[i];
        }
        double half = 0;
        if (xTick == 0 && sum == value[0] * value.length) {
            // If the plot flatlines(ƽ��) on the first event, we can't grade the Y axis(min==max).
            // This is especially bad if the sensor does not change without a
            // stimulus. the graph will then flatline on the x-axis where it is
            // impossible to be seen.
            half = value[0] * 0.5 + 1;//if values[0]==0; half=1;
        }
        renderer.setYAxisMax(max + half);
        renderer.setYAxisMin(min - half);
    }

    /**
     * Final configuration step. Must be called between receiving the first
     * <code>SensorEvent</code> and updating the graph for the first time.
     *
     * @param value
     *          the event
     */
    private void configure(float[] value) {
        String[] channelNames = new String[value.length];
        channel = new XYSeries[value.length];
        for (int i = 0; i < channelNames.length; i++) {
            channelNames[i] = getString(R.string.channel_default) + i;
        }

        int[] colors = {
                Color.BLUE,
                Color.RED,
                Color.GREEN,
                Color.YELLOW,
                Color.MAGENTA,
                Color.CYAN };
        for (int i = 0; i < channel.length; i++) {
            channel[i] = new XYSeries(channelNames[i]);
            sensorData.addSeries(channel[i]);
            XYSeriesRenderer r = new XYSeriesRenderer();
            r.setColor(colors[i % colors.length]);
            renderer.addSeriesRenderer(r);
        }
    }
}



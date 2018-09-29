package triopu.qtint;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import triopu.qtint.ecginterpretation.*;

public class MainQT extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, NameDialog.NameDialogListener{
    private final static String TAG = MainQT.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private String deviceAddress;
    private String deviceName;

    private long startTime = 0;

    FileWriter fw;
    BufferedWriter bw;
    String printFormat;

    GraphView graphView;
    private LineGraphSeries<DataPoint> ecgGraph;
    private LineGraphSeries<DataPoint> lpfGraph;
    private double graph2LastXValue = 5d;

    private boolean autoScrollX = false;
    private boolean lock = true;
    private int xView = 1000;
    private double minX,maxX,minY,maxY;

    private boolean connected = false;

    private String fileName = "defaultName";

    private BluetoothLeService bluetoothLeService;

    private ArrayList<ArrayList<BluetoothGattCharacteristic>> gattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

    private BluetoothGattCharacteristic notifyCharacteristic;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            bluetoothLeService = ((BluetoothLeService.LocalBinder)service).getService();
            if(!bluetoothLeService.initialize()){
                Log.e(TAG,"Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            bluetoothLeService.connect(deviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bluetoothLeService = null;
        }
    };

    //Part of Signal Processing
    private SampleData sampleData;
    private int[] sampleECGData = SampleData.generateData(4);

    private LowPassFilter lp;
    private HighPassFilter hp;
    private Derivative dr;
    private Squaring sq;
    private MovingWindowIntegration mw;

    private int midWave;

    private class SignalProcessing extends AsyncTask<int[],int[],int[]>{


        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        //@SuppressLint("DefaultLocale")
        @Override
        protected int[] doInBackground(int[]... integers) {
            int[] data = integers[0];
            int lpf,hpf,drv,sqr,mwi;
            lp = new LowPassFilter(300);
            hp = new HighPassFilter();
            dr = new Derivative();
            sq = new Squaring();
            mw = new MovingWindowIntegration();

            File sdCard = Environment.getExternalStorageDirectory();
            File dir = new File (sdCard.getAbsolutePath());
            //dir.mkdirs();
            File file = new File(dir, "/DataEKG/ECGProcessT2.txt");
            Log.d("File is", String.valueOf(file));

            try {
                fw = new FileWriter(file, true);
            } catch (IOException e) {
                e.printStackTrace();
            }

            List<Integer> lpVal = new ArrayList<Integer>();
            List<Integer> hpVal = new ArrayList<Integer>();
            List<Integer> drVal = new ArrayList<Integer>();
            List<Integer> sqVal = new ArrayList<Integer>();
            List<Integer> mwVal = new ArrayList<Integer>();
            List<Integer> mECG = new ArrayList<Integer>();
            List<Integer> dataECG = new ArrayList<Integer>();
            for (int aData : data) {
                dataECG.add(aData); mECG.add(aData);
                lpf = (int) lp.filter(aData);       lpVal.add(lpf);
                hpf = (int) hp.filter(lpf);         hpVal.add(hpf);
                drv = dr.derive(hpf);               drVal.add(drv);
                sqr = sq.square(drv);               sqVal.add(sqr);
                mwi = mw.calculate(sqr);            mwVal.add(mwi);
            }

            cancelDelay(lpVal,6);
            cancelDelay(hpVal,16);
            cancelDelay(drVal, 2);
            cancelDelay(mwVal,30);

            List<Integer> mwAbs = new ArrayList<Integer>();
            for(int i = 0; i<mwVal.size(); i++) mwAbs.add(Math.abs(mwVal.get(i)));
            int maxMW = Collections.max(mwAbs);
            List<Double> mwT = new ArrayList<Double>();
            for(int i = 0; i<mwAbs.size(); i++) mwT.add((double)mwVal.get(i)/(double)maxMW);

            double max_h = Collections.max(mwT);
            double avr = calculateAverage(mwT);
            double thr = max_h*avr;

            List<Integer> posReg = new ArrayList<Integer>();
            for (int i = 0; i<mwT.size();i++)if(mwT.get(i)>thr)posReg.add(1);else posReg.add(0);
            List<Integer> leftBound = new ArrayList<Integer>();
            List<Integer> rightBound = new ArrayList<Integer>();

            for (int i = 0; i<posReg.size()-1;i++){
                if(posReg.get(i+1)-posReg.get(i) == 1)leftBound.add(i);
                else if(posReg.get(i+1)-posReg.get(i) == -1)rightBound.add(i);
                if(rightBound.size() > 0 && leftBound.size() > 0) {
                    if (rightBound.get(0) < leftBound.get(0)) rightBound.remove(0);
                }
            }

            //Find R-Peak
            List<Integer> peakIndex = new ArrayList<Integer>();
            for(int i=0; i<rightBound.size();i++){
                int peak = findPeak(data, leftBound.get(i),rightBound.get(i));
                peakIndex.add(peak);
            }

            List<Integer> qrsOnset = new ArrayList<Integer>();
            for(int i = 0; i<peakIndex.size();i++){
                int qrsOn = findBase(lpVal,leftBound.get(i), peakIndex.get(i));
                qrsOnset.add(qrsOn);
            }

            Log.d("Left",String.valueOf(leftBound));
            Log.d("Right ",String.valueOf(rightBound));
            Log.d("Peak",String.valueOf(peakIndex));
            Log.d("QRSOnset",String.valueOf(qrsOnset));

            //Removing P and QRS
            for(int i = 0; i < peakIndex.size()-1;i++){
                midWave = (peakIndex.get(i+1)+peakIndex.get(i))/2;
                int leftRem = peakIndex.get(i+1)-midWave;
                leftRem = peakIndex.get(i) - leftRem;
                if(leftRem < 0) leftRem = 0;
                if(midWave > data.length)midWave = data.length-1;
                Log.d("MID-Wave",String.valueOf(midWave)+" leftRem: "+String.valueOf(leftRem)+" Right: "+String.valueOf(rightBound.get(i)));
                while (true){
                    mECG.set(leftRem,data[midWave]);
                    leftRem += 1;
                    if(leftRem>rightBound.get(i)) break;
                }
            }

            //Used Data is last peak - 1
            List<Integer> ecgData = new ArrayList<Integer>();
            List<Integer> ecgDataR = new ArrayList<Integer>();
            Log.d("midWave",String.valueOf(midWave));
            for(int i = 0; i < midWave; i++){
                Log.d("midWave",String.valueOf(midWave)+" i: "+String.valueOf(i));
                ecgData.add(dataECG.get(i));
                ecgDataR.add(mECG.get(i));
            }

            List<Integer> lpValT = new ArrayList<Integer>();
            List<Integer> drValT = new ArrayList<Integer>();
            List<Integer> sqValT = new ArrayList<Integer>();
            List<Integer> mwValT = new ArrayList<Integer>();

            for (int aData : ecgDataR) {
                lpf = (int) lp.filter(aData);       lpValT.add(lpf);
                drv = dr.derive(lpf);               drValT.add(drv);
                sqr = sq.square(drv);               sqValT.add(sqr);
                mwi = mw.calculate(sqr);            mwValT.add(mwi);
            }

            cancelDelay(lpValT,6);
            cancelDelay(drValT, 2);
            cancelDelay(mwValT,30);

            List<Integer> mwAbsT = new ArrayList<Integer>();
            for(int i = 0; i<mwValT.size(); i++) mwAbsT.add(Math.abs(mwValT.get(i)));
            int maxMWT = Collections.max(mwAbsT);
            List<Double> mwTT = new ArrayList<Double>();
            for(int i = 0; i<mwAbsT.size(); i++) mwTT.add((double)mwValT.get(i)/(double)maxMWT);

            double max_hT = Collections.max(mwTT);
            double avrT = calculateAverage(mwTT);
            double thrT = max_hT*avrT;

            Log.d("Threshold is", String.valueOf(thrT));

            List<Integer> posRegT = new ArrayList<Integer>();
            for (int i = 0; i<mwTT.size();i++){
                if(mwTT.get(i)>thrT)posRegT.add(1);
                else posRegT.add(0);
            }

            List<Integer> leftBoundT = new ArrayList<Integer>();
            List<Integer> rightBoundT = new ArrayList<Integer>();

            for (int i = 0; i<posRegT.size()-1;i++){
                if(posRegT.get(i+1)-posRegT.get(i) == 1)leftBoundT.add(i);
                else if(posRegT.get(i+1)-posRegT.get(i) == -1)rightBoundT.add(i);
                if(rightBoundT.size()>0&&leftBoundT.size()>0) {
                    if (rightBoundT.get(0) < leftBoundT.get(0)){
                        rightBoundT.remove(0);
                    }
                }
            }

            int k = 0;
            Log.d("T-End",String.valueOf(rightBoundT));

            Log.d("Number of Peak",String.valueOf(peakIndex.size()));
            Log.d("Number of T-end", String.valueOf(rightBoundT.size()));


            while(true){
                if(rightBoundT.size() < (peakIndex.size()-1)) break;
                if(rightBoundT.size() == (peakIndex.size()-1)) break;
                if((k+1)>rightBoundT.size())break;
                if(rightBoundT.get(k+1)-rightBoundT.get(k) < 50) rightBoundT.remove(k);
                k += 1;
            }

            for (int i = 0; i<ecgData.size();i++) {
                printFormat = String.format("%d\t%d\t%d\t%d\t%d\t%d\t%d", ecgData.get(i),ecgDataR.get(i),lpValT.get(i),drValT.get(i),sqValT.get(i),mwValT.get(i),posRegT.get(i));
                try {
                    fw.append(printFormat).append("\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                fw.flush();
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return data;
            //return null;
        }

        private void cancelDelay(List<Integer> arrayList, int numberDelay){
            for(int i = 0; i<arrayList.size()-numberDelay;i++){
                arrayList.set(i, arrayList.get(i + numberDelay));
            }
        }

        private double calculateAverage(List<Double> marks) {
            double sum = 0;
            if(!marks.isEmpty()) {
                for (Double mark : marks) sum += mark;
                return sum / marks.size();
            }
            return sum;
        }

        private int findPeak(int[] theData, int minBound, int maxBound){
            int peak = 0;
            int k = 0;
            for(int i = minBound;i < maxBound; i++){
                if(theData[i]>peak){
                    peak = theData[i];
                    k = i+1;
                }
            }
            return k;
        }

        private int findBase(List<Integer> theData, int minBound, int maxBound){
            int base = 1000;
            int k = 0;
            for(int i = minBound; i< maxBound; i++){
                if(theData.get(i)<base){
                    base = theData.get(i);
                    k = i+1;
                }
            }
            return k;
        }

        @Override
        protected void onProgressUpdate(int[]... values) {
            //super.onProgressUpdate(values);
        }

        @Override
        protected void onPostExecute(int[] integers) {
            //super.onPostExecute(integers);
        }
    }

    // Handles various events fired by the Service.
    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {  // ACTION_GATT_CONNECTED: connected to a GATT server.
                connected = true;
                Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_LONG).show();
                startTime = 0;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) { // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
                connected = false;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) { // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
                // Connect to services
                connectGattServices(bluetoothLeService.getSupportedServices());     // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read or notification operations.
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                String incomeData = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                String[] items = incomeData.split("\\*");
                for (String item : items) {
                    if (item.length() == 3) {
                        //graphIt(item, 1);
                    }
                }
            }
        }
    };

    private void graphIt(String item, int maxDataPoint, int typeGraph) {
        if(typeGraph == 1) {
            if (graph2LastXValue >= xView) {
                graph2LastXValue = 0;
                ecgGraph.resetData(new DataPoint[]{new DataPoint(graph2LastXValue, Double.parseDouble(item))});
            } else {
                graph2LastXValue += 1d;
            }
            ecgGraph.appendData(new DataPoint(graph2LastXValue, Double.parseDouble(item)), autoScrollX, maxDataPoint);
        }
    }

    // Connect the GATT Servive
    private void connectGattServices(List<BluetoothGattService> gattServices) {
        if(gattServices == null) return;
        String uuid = null;
        gattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for(BluetoothGattService gattService: gattServices){
            List<BluetoothGattCharacteristic> thegattCharacteristics = gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas = new ArrayList<BluetoothGattCharacteristic>();

            for(BluetoothGattCharacteristic gattCharacteristic : thegattCharacteristics){
                charas.add(gattCharacteristic);
                uuid = gattCharacteristic.getUuid().toString();

                //My BLE RX_TX is 0000ffe1-0000-1000-8000-00805f9b34fb
                if(uuid.equals("0000ffe1-0000-1000-8000-00805f9b34fb")){
                    notifyCharacteristic = gattCharacteristic;
                    bluetoothLeService.setCharacteristicNotification(notifyCharacteristic,true);
                }
            }
            gattCharacteristics.add(charas);
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter(){
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private void getTime(){
        long millis = System.currentTimeMillis() - startTime;
        int seconds = (int) (millis / 1000);
        int minutes = seconds / 60;
        seconds     = seconds % 60;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main_qt);

        DrawerLayout drawerLayout = findViewById(R.id.main_qt_layout);

        graphInit();

        final Intent intent = getIntent();
        deviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        deviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent,serviceConnection, BIND_AUTO_CREATE);

        NavigationView navigationView = findViewById(R.id.navi_main);
        navigationView.setNavigationItemSelectedListener(this);
    }

    private void graphInit() {
        graphView = findViewById(R.id.graph);
        ecgGraph = new LineGraphSeries<>();
        graphView.addSeries(ecgGraph);

        ecgGraph.setThickness(1);
        ecgGraph.setColor(Color.YELLOW);

        //Signal Processing Graph
        lpfGraph = new LineGraphSeries<>();
        graphView.addSeries(lpfGraph);

        lpfGraph.setThickness(1);
        lpfGraph.setColor(Color.GREEN);

        //graphView.getViewport().setScalable(true);
        graphView.getViewport().setScrollable(true);

        graphView.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        graphView.getGridLabelRenderer().setVerticalLabelsVisible(false);

        graphView.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.BOTH);
        graphView.getViewport().setDrawBorder(false);

        graphView.getGridLabelRenderer().setGridColor(Color.WHITE);

        /*
        minX = 0;maxX = 1000;minY = 100;maxY = 500;

        makeBorder(graphView,minX,maxX,minY,maxY);

        graphView.getViewport().setYAxisBoundsManual(true);
        graphView.getViewport().setMinY(minY);
        graphView.getViewport().setMaxY(maxY);

        graphView.getViewport().setXAxisBoundsManual(true);
        graphView.getViewport().setMinX(minX);
        graphView.getViewport().setMaxX(maxX);

        double[] centerX = {200,400,600,800};
        double tickWidthY = 20;
        double tickLengthY = 2;

        makeSecondTickY(centerX, minY, maxY, tickWidthY, tickLengthY);

        double[] centerY = {200,300,400};
        double tickWidthX = 20;
        double tickLengthX = 2;

        makeSecondTickX(centerY, minX, maxX, tickWidthX, tickLengthX);
        */
        //new SignalProcessing().execute(sampleECGData);
    }

    private void makeSecondTickY(double[] center, double minY, double maxY, double tickWidth, double tickLength) {
        for(int i = 0; i<center.length;i++){
            double y = minY;
            while(y < maxY) {
                y = y + tickWidth;
                int thickness = 1;
                LineGraphSeries<DataPoint> tick = new LineGraphSeries<>(new DataPoint[]{
                        new DataPoint(center[i] - tickLength, y),
                        new DataPoint(center[i] + tickLength, y)
                });
                graphView.addSeries(tick);
                tick.setThickness(thickness);
                tick.setColor(Color.WHITE);
            }
        }
    }

    private void makeSecondTickX(double[] center, double minX, double maxX, double tickWidth, double tickLength) {
        for(int i = 0; i<center.length;i++){
            double x = minX;
            while(x < maxX) {
                x = x + tickWidth;
                int thickness = 1;
                LineGraphSeries<DataPoint> tick = new LineGraphSeries<>(new DataPoint[]{
                        new DataPoint(x, center[i] - tickLength),
                        new DataPoint(x, center[i] + tickLength)
                });
                graphView.addSeries(tick);
                tick.setThickness(thickness);
                tick.setColor(Color.WHITE);
            }
        }
    }

    private void makeBorder(GraphView graphView, double startX, double endX, double startY, double endY) {
        int thickness = 4;
        LineGraphSeries<DataPoint> series1 = new LineGraphSeries<>(new DataPoint[]{
                new DataPoint(startX,startY),
                new DataPoint(endX,startY)
        });
        graphView.addSeries(series1);
        series1.setThickness(thickness);
        series1.setColor(Color.WHITE);

        LineGraphSeries<DataPoint> series2 = new LineGraphSeries<>(new DataPoint[]{
                new DataPoint(endX,startY),
                new DataPoint(endX, endY)
        });
        graphView.addSeries(series2);
        series2.setThickness(thickness);
        series2.setColor(Color.WHITE);

        LineGraphSeries<DataPoint> series3 = new LineGraphSeries<>(new DataPoint[]{
                new DataPoint(startX, endY),
                new DataPoint(endX, endY)
        });
        graphView.addSeries(series3);
        series3.setThickness(thickness);
        series3.setColor(Color.WHITE);


        LineGraphSeries<DataPoint> series4 = new LineGraphSeries<>(new DataPoint[]{
                new DataPoint(startX, startY),
                new DataPoint(startX, endY)
        });
        graphView.addSeries(series4);
        series4.setThickness(3);
        series4.setColor(Color.WHITE);
    }

    @Override
    protected void onResume(){
        super.onResume();
        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());
        if(bluetoothLeService != null){
            final boolean result = bluetoothLeService.connect(deviceAddress);
            Log.d(TAG,"Connect request results = "+ result);
        }
    }

    @Override
    protected void onPause(){
        super.onPause();
        unregisterReceiver(gattUpdateReceiver);
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        unbindService(serviceConnection);
        bluetoothLeService = null;
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {

        int id = item.getItemId();

        if (id == R.id.bt_connect) {
            bluetoothLeService.connect(deviceAddress);
            Toast.makeText(getApplicationContext(),"Connecting...",Toast.LENGTH_SHORT).show();
        } else if (id == R.id.bt_disconnect) {
            bluetoothLeService.disconnect();
            Toast.makeText(getApplicationContext(),"Disconnected",Toast.LENGTH_SHORT).show();
        } else if (id == R.id.record) {
            Toast.makeText(getApplicationContext(),"Recording...",Toast.LENGTH_SHORT).show();
        } else if (id == R.id.stoprecord) {
            Toast.makeText(getApplicationContext(),"Stopped",Toast.LENGTH_SHORT).show();
        } else if (id == R.id.name_edit) {
            openDialog();
        } else if (id == R.id.browser){
            new SignalProcessing().execute(sampleECGData);
            Toast.makeText(getApplicationContext(),"Browse Folder",Toast.LENGTH_SHORT).show();
        }

        DrawerLayout drawer = findViewById(R.id.main_qt_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    public void openDialog(){
        NameDialog nameDialog = new NameDialog();
        nameDialog.show(getSupportFragmentManager(),"Name Dialog");
    }

    @Override
    public void applyText(String namefile) {
        fileName = namefile;
        Toast.makeText(getApplicationContext(),"Your file name is "+fileName,Toast.LENGTH_SHORT).show();
    }

}

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
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
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
    private double theTime;

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
    private boolean record = false;

    private String fileName = "TimeTest";

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

    //Make a data processing container
    private ArrayList<Integer> processedECGData = new ArrayList<Integer>();
    private ArrayList<Double> processedECGTime = new ArrayList<Double>();

    int second;

    //It's a goAsync part
    BroadcastReceiver.PendingResult result;

    //Boolean to Process the Data.
    boolean process = false;
    boolean unprocess = true;

    //Initialize TextView
    private TextView HR;
    private TextView RR;
    private TextView QT;

    double qtAvr, rrAvr, hr;
    int qtDiv, rrDiv;

    @SuppressLint("StaticFieldLeak")
    private class SignalProcessing extends AsyncTask<Object,int[],ArrayList<Integer>>{

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @SafeVarargs
        @Override
        protected final ArrayList<Integer> doInBackground(Object... integers) {

            //Parse the input of AsyncTask, ECG data in 0 and Time in 1
            ArrayList<Integer> data = (ArrayList<Integer>) integers[0];
            ArrayList<Double> time = (ArrayList<Double>) integers[1];

            //If data size is less than 10,cancel AsynTask by return the data
            if(data.size() < 10) return data;                                                       //

            //Give bound to the processed data
            int dataCount = data.size();
            if (data.size() > time.size()){
                dataCount = time.size();
                Log.d("PD","Data is Longer"+
                        String.valueOf(data.size())+"|"+String.valueOf(time.size()));
            }

            //Pan-Tompkins Section
            int lpf,hpf,drv,sqr,mwi;
            lp = new LowPassFilter(300);
            hp = new HighPassFilter();
            dr = new Derivative();
            sq = new Squaring();
            mw = new MovingWindowIntegration();

            List<Integer> lpVal = new ArrayList<Integer>();
            List<Integer> hpVal = new ArrayList<Integer>();
            List<Integer> drVal = new ArrayList<Integer>();
            List<Integer> sqVal = new ArrayList<Integer>();
            List<Integer> mwVal = new ArrayList<Integer>();
            List<Integer> mECG = new ArrayList<Integer>();
            List<Integer> dataECG = new ArrayList<Integer>();
            List<Double> dataTime = new ArrayList<Double>();

            List<Integer> annECG = new ArrayList<Integer>();
            for (int i = 0; i < dataCount ; i++) {
                annECG.add(0);
                dataECG.add(data.get(i));
                mECG.add(data.get(i));
                dataTime.add(time.get(i));
                lpf = (int) lp.filter(data.get(i)); lpVal.add(lpf);
                hpf = (int) hp.filter(lpf);         hpVal.add(hpf);
                drv = dr.derive(hpf);               drVal.add(drv);
                sqr = sq.square(drv);               sqVal.add(sqr);
                mwi = mw.calculate(sqr);            mwVal.add(mwi);
            }

            // Removing the delay of Pan-Tompkins
            cancelDelay(lpVal,6);
            cancelDelay(hpVal,16);
            cancelDelay(drVal, 2);
            cancelDelay(mwVal,30);

            //Calculate the Threshold, Thr = Moving Window Integration (mwi) / absolute mean of mwi
            List<Integer> mwAbs = new ArrayList<Integer>();
            for(int i = 0; i<mwVal.size(); i++) mwAbs.add(Math.abs(mwVal.get(i)));
            int maxMW = Collections.max(mwAbs);
            List<Double> mwT = new ArrayList<Double>();
            for(int i = 0; i<mwAbs.size(); i++) mwT.add((double)mwVal.get(i)/(double)maxMW);

            double max_h = Collections.max(mwT);
            double avr = calculateAverage(mwT);
            double thr = max_h*avr;

            //Convert the data into 1 and 0 to find the left and right bound
            List<Integer> posReg = new ArrayList<Integer>();
            for (int i = 0; i<mwT.size();i++)if(mwT.get(i)>thr)posReg.add(1);else posReg.add(0);
            List<Integer> leftBound = new ArrayList<Integer>();
            List<Integer> rightBound = new ArrayList<Integer>();

            //Find the right and left bound
            for (int i = 0; i<posReg.size()-1;i++) {
                if (posReg.get(i + 1) - posReg.get(i) == 1) leftBound.add(i);
                else if (posReg.get(i + 1) - posReg.get(i) == -1) rightBound.add(i);

                //If the first data is found to be right bound, we have to remove it
                if (rightBound.size() > 0 && leftBound.size() > 0) {
                    if (rightBound.get(0) < leftBound.get(0)) rightBound.remove(0);
                }

                //Remove the first right bound if two right bound's distance is less than 100 point data
                if (rightBound.size() > 1) {
                    if (rightBound.get(rightBound.size() - 1) - rightBound.get(rightBound.size() - 2) < 100) {
                        rightBound.remove(rightBound.size() - 2);
                    }
                }

                //Remove the first left bound if two left bound's distance is less than 100 point data
                if (leftBound.size() > 1) {
                    if (leftBound.get(leftBound.size() - 1) - leftBound.get(leftBound.size() - 2) < 100) {
                        leftBound.remove(leftBound.size() - 2);
                    }
                }
            }


            //Find R-Peak
            List<Integer> peakIndex = new ArrayList<Integer>();
            for(int i=0; i<rightBound.size();i++){
                int peak = findPeak(data, leftBound.get(i),rightBound.get(i));
                //If index of peak is so small, 0 for example, its mean the right and left bound is too close
                if(peak == 0) {
                    Log.d("PD QRSOnset","Zero Peak");
                    leftBound.remove(i);
                    rightBound.remove(i);
                }else{
                    peakIndex.add(peak);
                }
            }

            //Find the QRS-onset
            List<Integer> qrsOnset = new ArrayList<Integer>();
            for(int i = 0; i<peakIndex.size();i++){
                int qrsOn = findBase(lpVal,leftBound.get(i), peakIndex.get(i));
                qrsOnset.add(qrsOn);
            }

            Log.d("PD Left",String.valueOf(leftBound));
            Log.d("PD Right ",String.valueOf(rightBound));
            Log.d("PD Peak",String.valueOf(peakIndex));
            Log.d("PD QRSOnset",String.valueOf(qrsOnset));

            //Removing P and QRS
            for(int i = 0; i < peakIndex.size()-1;i++){
                midWave = (peakIndex.get(i+1)+peakIndex.get(i))/2;
                int leftRem = peakIndex.get(i+1)-midWave;
                leftRem = peakIndex.get(i) - leftRem;
                if(leftRem < 0) leftRem = 0;
                if(midWave > data.size())midWave = data.size()-1;
                Log.d("MID-Wave",String.valueOf(midWave)+" leftRem: "+String.valueOf(leftRem)+" Right: "+String.valueOf(rightBound.get(i)));
                while (true){
                    mECG.set(leftRem,data.get(midWave));
                    leftRem += 1;
                    if(leftRem>rightBound.get(i)) break;
                }
            }

            //Used Data is last peak - 1
            List<Integer> ecgData = new ArrayList<Integer>();
            List<Integer> ecgDataR = new ArrayList<Integer>();
            List<Double> timeData = new ArrayList<Double>();
            Log.d("midWave",String.valueOf(midWave));
            for(int i = 0; i < midWave; i++){
                Log.d("midWave",String.valueOf(midWave)+" i: "+String.valueOf(i));
                ecgData.add(dataECG.get(i));
                ecgDataR.add(mECG.get(i));
                timeData.add(dataTime.get(i));
            }

            //Save unused Data, the leftover of previous process
            int unused = midWave;
            processedECGData = new ArrayList<Integer>();
            processedECGTime = new ArrayList<Double>();
            boolean unn = true;
            while (unn){
                if(unused > dataECG.size()-2) unn = false;
                processedECGData.add(dataECG.get(unused));
                processedECGTime.add(dataTime.get(unused));
                unused += 1;
            }

            Log.d("TheData Left", String.valueOf(processedECGData.size()));

            //T-end detection section

            List<Integer> lpValT = new ArrayList<Integer>();
            List<Integer> drValT = new ArrayList<Integer>();
            List<Integer> sqValT = new ArrayList<Integer>();
            List<Integer> mwValT = new ArrayList<Integer>();

            //ECG data is passed through LPF, Derivative, Squaring, and mwi
            for (int aData : ecgDataR) {
                lpf = (int) lp.filter(aData);       lpValT.add(lpf);
                drv = dr.derive(lpf);               drValT.add(drv);
                sqr = sq.square(drv);               sqValT.add(sqr);
                mwi = mw.calculate(sqr);            mwValT.add(mwi);
            }

            cancelDelay(lpValT,6);
            cancelDelay(drValT, 2);
            cancelDelay(mwValT,30);

            //Find the Threshold for T-end detection
            List<Integer> mwAbsT = new ArrayList<Integer>();
            for(int i = 0; i<mwValT.size(); i++) mwAbsT.add(Math.abs(mwValT.get(i)));
            int maxMWT = Collections.max(mwAbsT);
            List<Double> mwTT = new ArrayList<Double>();
            for(int i = 0; i<mwAbsT.size(); i++) mwTT.add((double)mwValT.get(i)/(double)maxMWT);

            double max_hT = Collections.max(mwTT);
            double avrT = calculateAverage(mwTT);
            double thrT = max_hT*avrT;

            Log.d("Threshold is", String.valueOf(thrT));

            int alpha = 1;
            List<Integer> leftBoundT = new ArrayList<Integer>();
            List<Integer> rightBoundT = new ArrayList<Integer>();
            List<Integer> posRegT = new ArrayList<Integer>();
            List<Integer> endT = new ArrayList<Integer>();

            //T-end is detected between 2 consecutive R-peak
            //If T-end is not detected, multiply the Threshold by alpha, until alpha = 0.1
            //Give maximum iteration by 10
            for(int i = 0; i < peakIndex.size()-1;i++){
                boolean k = true;
                int iter = 0;
                int indT = 0;
                while (k){
                    posRegT = new ArrayList<Integer>();

                    //Start od searching is 1st R-peak, End of searching is 2nd peak
                    //If the 2nd peak is last peak + 1, End of searching is midWave, look at used data
                    int searchEnd;
                    if(i == (peakIndex.size()-2)){
                        searchEnd = midWave;
                        Log.d("SEndE",String.valueOf(searchEnd)+" i: "+String.valueOf(i) + " START: "+String.valueOf(peakIndex.get(i))+" numbP: "+String.valueOf(peakIndex.size()));
                    }
                    else{
                        searchEnd = peakIndex.get(i+1);
                        Log.d("SEnd",String.valueOf(searchEnd)+" i: "+String.valueOf(i)+ " START: "+String.valueOf(peakIndex.get(i))+" numbP: "+String.valueOf(peakIndex.size()));
                    }

                    //Converting to 0 and 1 to find the right bound
                    for (int m = peakIndex.get(i); m < searchEnd-1; m++) {
                        if (mwTT.get(m) > thrT) posRegT.add(1);
                        else posRegT.add(0);
                    }

                    //Find the right bound
                    leftBoundT = new ArrayList<Integer>();
                    rightBoundT = new ArrayList<Integer>();
                    int n;
                    for (n = 0; n < posRegT.size() - 1; n++) {
                        if (posRegT.get(n + 1) - posRegT.get(n) == 1) leftBoundT.add(n);
                        else if (posRegT.get(n + 1) - posRegT.get(n) == -1) rightBoundT.add(n);
                        if (rightBoundT.size() > 0 && leftBoundT.size() > 0) {
                            //If the first data is found to be right bound, we have to remove it
                            if (rightBoundT.get(0) < leftBoundT.get(0)) {
                                rightBoundT.remove(0);
                                Log.d("Removing",String.valueOf(rightBoundT.get(0)));
                            }
                        }
                    }

                    //Iteration is add by 1
                    iter += 1;

                    Log.d("Thd", String.valueOf(thrT));

                    //If one right bound is found, return the index of T as the rightBoundT.get(0)
                    //Stop the iteration by switch k to false
                    if(rightBoundT.size() == 1){
                        indT = rightBoundT.get(0);
                        k = false;
                        Log.d("PD TE","T-End 1");
                    }

                    //If the right bound is not found, reduce the Threshold
                    if(rightBoundT.size()<1) thrT = thrT * (alpha - 0.1);

                    //If more than one right bound are found, find the nearest bound to R-Peak + 60 data point
                    if(rightBoundT.size()>1){
                        int peakRef = 100;
                        int minVal = Math.abs(rightBoundT.get(0)- peakRef); // Keeps a running count of the smallest value so far
                        int minIdx = 0; // Will store the index of minVal
                        for(int idx=1; idx < rightBoundT.size(); idx++) {
                            if(Math.abs(rightBoundT.get(idx)- peakRef) < minVal) {
                                minVal = Math.abs(rightBoundT.get(idx)- peakRef);;
                                minIdx = idx;
                            }
                        }
                        indT = rightBoundT.get(minIdx);
                        Log.d("PD TE T-End > 1",String.valueOf(rightBoundT)+" | "+String.valueOf(peakRef)+" | "+String.valueOf(indT));
                        k = false;
                    }

                    //If iteration is more than 10 times
                    //If the second bound is Start of Search, T-end is midWave
                    //Else it's the avearage of two consecutive R-Peak
                    if(iter > 10){
                        k = false;
                        if(i == peakIndex.size()) indT = midWave;
                        else indT = ((peakIndex.get(i+1)+peakIndex.get(i))/2)-peakIndex.get(i);
                        Log.d("PD TE","T-End Iter");
                    }
                }
                //Collect the T-end
                indT = indT + peakIndex.get(i);
                endT.add(indT);
            }

            Log.d("Number of Peak",String.valueOf(peakIndex.size()));
            Log.d("Number of T-end", String.valueOf(endT.size()));
            Log.d("peak1",String.valueOf(peakIndex));
            Log.d("peak2",String.valueOf(endT));
            Log.d("PD T-End",String.valueOf(endT));

            //Calculate RR and HR
            double rrSum = 0.000;
            rrDiv = 0;
            for(int i = 1;i<peakIndex.size()-1;i++){
                annECG.set(peakIndex.get(i),2);
                annECG.set(qrsOnset.get(i),1);
                double rrInt = dataTime.get(peakIndex.get(i)) - dataTime.get(peakIndex.get(i-1));
                if(rrInt > 0.400 && rrInt < 2.000){
                    rrSum += rrInt;
                    rrDiv += 1;
                }
            }

            for(int i = 0;i<peakIndex.size();i++){
                annECG.set(peakIndex.get(i),2);
                annECG.set(qrsOnset.get(i),1);
            }

            if(rrDiv > 0){
                rrAvr = rrSum/rrDiv;
                hr = 60.000/rrAvr;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        RR.setText(" RR: "+String.format("%.3f",rrAvr));
                        HR.setText(" HR: "+String.format("%.0f",hr));
                    }
                });
            }else{
                Log.d("PD","No RR");
            }

            //Calculate QT
            double qtSum = 0.000;
            qtDiv = 0;
            for(int i = 0;i<endT.size();i++){
                annECG.set(endT.get(i),3);
                double qtInt = dataTime.get(endT.get(i)) - dataTime.get(qrsOnset.get(i));
                if(qtInt > 0.100 && qtInt < 2.000){
                    qtSum += qtInt;
                    qtDiv += 1;
                }
            }

            if(qtDiv > 0){
                qtAvr = qtSum/qtDiv;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        QT.setText(" QT: "+String.format("%.3f",qtAvr));
                    }
                });
            }else{
                Log.d("PD","No QT");
            }

            //Write the result to text file if recording
            if(record) {
                for (int i = 0; i < ecgData.size() - 1; i++) {
                    try {
                        printFormat = String.format("%.5f\t%d\t%d\t%d", timeData.get(i), ecgData.get(i), ecgDataR.get(i), annECG.get(i));
                        //printFormat = String.format("%d\t%d\t%d\t%d\t%d\t%d", ecgData.get(i),ecgDataR.get(i),lpValT.get(i),drValT.get(i),sqValT.get(i),mwValT.get(i));
                        Log.d("Print", printFormat);
                        fw.append(printFormat).append("\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            result.finish();
            return data;
        }

        //Method to cancel delay
        private void cancelDelay(List<Integer> arrayList, int numberDelay){
            for(int i = 0; i<arrayList.size()-numberDelay;i++){
                arrayList.set(i, arrayList.get(i + numberDelay));
            }
        }

        //Method to calculate the average or mean of absolute Moving Window Integration output
        private double calculateAverage(List<Double> marks) {
            double sum = 0;
            if(!marks.isEmpty()) {
                for (Double mark : marks) sum += mark;
                return sum / marks.size();
            }
            return sum;
        }

        //Method to find R-peak
        private int findPeak(ArrayList<Integer> theData, int minBound, int maxBound){
            int peak = 0;
            int k = 0;
            for(int i = minBound;i < maxBound; i++){
                if(theData.get(i)>peak){
                    peak = theData.get(i);
                    k = i+1;
                }
            }
            return k;
        }

        //Method to find QRS-onset
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
        protected void onPostExecute(ArrayList integers) {
            //super.onPostExecute(integers);
        }
    }

    // Handles various events fired by the Service.
    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                //ACTION_GATT_CONNECTED: connected to a GATT server.
                connected = true;
                Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_LONG).show();
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                //ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
                connected = false;
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                //ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
                connectGattServices(bluetoothLeService.getSupportedServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read or notification operations.
                String incomeData = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                String[] items = incomeData.split("\\*");
                for (String item : items) {
                    if (item.length() == 3) {
                        graphIt(item);
                        if(process) {
                            //Calculate the time, process data every 5 second
                            second = getTime(startTime);
                            if (second < 6) {
                                //Get the time of ECG data
                                double nowTime  = System.currentTimeMillis()/1000.00000;
                                double time     = nowTime - theTime ;
                                Log.d("Time", String.valueOf(time));

                                //Collect data
                                processedECGData.add(Integer.valueOf(item));
                                processedECGTime.add(time);
                            } else {
                                startTime = System.currentTimeMillis();
                                if(processedECGData.size() > 0) {
                                    result = goAsync();
                                    new SignalProcessing().execute(processedECGData, processedECGTime);
                                    Log.d("TheData Input", String.valueOf(processedECGData.size()));
                                }
                            }
                        }
                    }
                }
            }
        }
    };

    private void graphIt(String item) {
        if (graph2LastXValue >= xView) {
            graph2LastXValue = 0;
            ecgGraph.resetData(new DataPoint[]{new DataPoint(graph2LastXValue, Double.parseDouble(item))});
        } else {
            graph2LastXValue += 1d;
        }
        ecgGraph.appendData(new DataPoint(graph2LastXValue, Double.parseDouble(item)), autoScrollX, 1000);
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

    private int getTime(long startTime){
        long millis = System.currentTimeMillis() - startTime;
        int seconds = (int) (millis / 1000);
        int minutes = seconds / 60;
        seconds     = seconds % 60;
        return seconds;
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

        HR = (TextView)findViewById(R.id.heart_rate);
        HR.setMovementMethod(new ScrollingMovementMethod());
        RR = (TextView)findViewById(R.id.rr_interval);
        RR.setMovementMethod(new ScrollingMovementMethod());
        QT = (TextView)findViewById(R.id.qt_interval);
        QT.setMovementMethod(new ScrollingMovementMethod());
        HR.setText(" HR: ");
        RR.setText(" RR: ");
        QT.setText(" QT: ");
        HR.setVisibility(View.INVISIBLE);
        RR.setVisibility(View.INVISIBLE);
        QT.setVisibility(View.INVISIBLE);
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
            if(unprocess){
                Toast.makeText(getApplicationContext(),"Must be processing!",Toast.LENGTH_SHORT).show();
            }else {
                //theTime = System.currentTimeMillis() / 1000.00000;
                record = true;
                processedECGData = new ArrayList<Integer>();

                //Try make a new file in the Internal
                File sdCard = Environment.getExternalStorageDirectory();
                File dir = new File(sdCard.getAbsolutePath());
                File file = new File(dir, "/" + fileName + ".txt");

                Log.d("File is", String.valueOf(file));

                try {
                    fw = new FileWriter(file, true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(getApplicationContext(), "Recording...", Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.stoprecord) {
            record = false;
            startTime = 0;
            processedECGData = new ArrayList<Integer>();
            try {
                fw.flush();
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Toast.makeText(getApplicationContext(),"Stopped",Toast.LENGTH_SHORT).show();
        } else if (id == R.id.name_edit) {
            openDialog();
        } else if (id == R.id.process){
            if(!process){
                process = true;
                unprocess = false;
                HR.setVisibility(View.VISIBLE);
                RR.setVisibility(View.VISIBLE);
                QT.setVisibility(View.VISIBLE);
                theTime = System.currentTimeMillis() / 1000.00000;
                Toast.makeText(getApplicationContext(),"Processing data...",Toast.LENGTH_SHORT).show();
            }

            else if (!unprocess){
                process = false;
                unprocess = true;
                HR.setVisibility(View.INVISIBLE);
                RR.setVisibility(View.INVISIBLE);
                QT.setVisibility(View.INVISIBLE);
                Toast.makeText(getApplicationContext(),"Stop processing data...",Toast.LENGTH_SHORT).show();
            }
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

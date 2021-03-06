/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klikbruch.sender.bluetooth;

import java.io.File;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

import com.klikbruch.sender.R;
import com.klikbruch.sender.logic.FFT;
import com.klikbruch.sender.logic.Util;

/**
 * This is the main Activity that displays the current chat session.
 */
public class BluetoothBrush extends Activity {

	// Debugging
	private static final String TAG = "BluetoothChat";
	private static final boolean D = true;

	// Message types sent from the BluetoothChatService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;

	// Key names received from the BluetoothChatService Handler
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";

	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;

	// Layout Views
	// private TextView mTitle;
	private Button mSendButton;

	// Name of the connected device
	private String mConnectedDeviceName = null;
	// Local Bluetooth adapter
	private BluetoothAdapter mBluetoothAdapter = null;
	// Member object for the chat services
	private BluetoothChatService mChatService = null;
	private SensorManager mSensorManager;
	private AccellEventListener eventListener;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (D)
			Log.e(TAG, "+++ ON CREATE +++");

		// // Set up the window layout
		// requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
		setContentView(R.layout.activity_main);
		// getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE,
		// R.layout.custom_title);

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		eventListener = new AccellEventListener();

		findViewById(R.id.btn_start).setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				mSensorManager.registerListener(eventListener, mSensorManager
						.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
						SensorManager.SENSOR_DELAY_FASTEST);
			}
		});

		findViewById(R.id.btn_stop).setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				mSensorManager.unregisterListener(eventListener);
			}
		});

		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available",
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		if (D)
			Log.e(TAG, "++ ON START ++");

		// If BT is not on, request that it be enabled.
		// setupChat() will then be called during onActivityResult
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
			// Otherwise, setup the chat session
		} else {
			if (mChatService == null)
				setupChat();
		}
	}

	@Override
	public synchronized void onResume() {
		super.onResume();
		if (D)
			Log.e(TAG, "+ ON RESUME +");

		// Performing this check in onResume() covers the case in which BT was
		// not enabled during onStart(), so we were paused to enable it...
		// onResume() will be called when ACTION_REQUEST_ENABLE activity
		// returns.
		if (mChatService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't
			// started already
			if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
				// Start the Bluetooth chat services
				mChatService.start();
			}
		}
	}

	private void setupChat() {
		// Initialize the BluetoothChatService to perform bluetooth connections
		mChatService = new BluetoothChatService(this, mHandler);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// Stop the Bluetooth chat services
		if (mChatService != null)
			mChatService.stop();
	}

	private void ensureDiscoverable() {
		if (D)
			Log.d(TAG, "ensure discoverable");
		if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent discoverableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(
					BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	/**
	 * Sends a message.
	 * 
	 * @param message
	 *            A string of text to send.
	 */
	private void sendMessage(String message) {
		System.out.println("BluetoothBrush.sendMessage() " + message);

		if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
			return;
		}
		if (message.length() > 0) {
			byte[] send = message.getBytes();
			mChatService.write(send);
		}
	}

	// The Handler that gets information back from the BluetoothChatService
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				if (D)
					Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
				switch (msg.arg1) {
				case BluetoothChatService.STATE_CONNECTED:
					Toast.makeText(
							getApplicationContext(),
							R.string.title_connected_to + " "
									+ mConnectedDeviceName, Toast.LENGTH_SHORT)
							.show();
					break;
				case BluetoothChatService.STATE_CONNECTING:
					break;
				case BluetoothChatService.STATE_LISTEN:
				case BluetoothChatService.STATE_NONE:
					Toast.makeText(getApplicationContext(),
							R.string.title_not_connected, Toast.LENGTH_SHORT)
							.show();
					break;
				}
				break;
			case MESSAGE_WRITE:
//				// we have sent a message!
//				byte[] writeBuf = (byte[]) msg.obj;
//				// construct a string from the buffer
//				String writeMessage = new String(writeBuf);
				break;
			case MESSAGE_READ:
				// a message has arrived!
				byte[] readBuf = (byte[]) msg.obj;
				// construct a string from the valid bytes in the buffer
				String readMessage = new String(readBuf, 0, msg.arg1);
				break;
			case MESSAGE_DEVICE_NAME:
				// save the connected device's name
				mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
				Toast.makeText(getApplicationContext(),
						"Connected to " + mConnectedDeviceName,
						Toast.LENGTH_SHORT).show();
				break;
			case MESSAGE_TOAST:
				Toast.makeText(getApplicationContext(),
						msg.getData().getString(TOAST), Toast.LENGTH_SHORT)
						.show();
				break;
			}
		}
	};

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (D)
			Log.d(TAG, "onActivityResult " + resultCode);
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				// Get the device MAC address
				String address = data.getExtras().getString(
						DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				// Get the BLuetoothDevice object
				BluetoothDevice device = mBluetoothAdapter
						.getRemoteDevice(address);
				// Attempt to connect to the device
				mChatService.connect(device);
			}
			break;
		case REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up a chat session
				setupChat();
			} else {
				// User did not enable Bluetooth or an error occured
				Log.d(TAG, "BT not enabled");
				Toast.makeText(this, R.string.bt_not_enabled_leaving,
						Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.option_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.scan:
			// Launch the DeviceListActivity to see devices and do scan
			Intent serverIntent = new Intent(this, DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
			return true;
		case R.id.discoverable:
			// Ensure this device is discoverable by others
			ensureDiscoverable();
			return true;
		}
		return false;
	}

	public class AccellEventListener implements SensorEventListener {

		private static final int sampleSize = 128;
		double[] realNumbersX, imagNumbersX;
		double[] realNumbersY, imagNumbersY;
		double[] realNumbersZ, imagNumbersZ;
		
		long[] timestamps;
		int count = 0;
		
		//computations
		double[] magnitudeTemp;

		File output = new File(Environment.getExternalStorageDirectory(),
				"output.csv");

		private long lastUpdate;

		@Override
		public void onSensorChanged(SensorEvent event) {

			if (count == 0) {
				realNumbersX = new double[sampleSize];
				imagNumbersX = new double[sampleSize];
				realNumbersY = new double[sampleSize];
				imagNumbersY = new double[sampleSize];
				realNumbersZ = new double[sampleSize];
				imagNumbersZ = new double[sampleSize];
				timestamps = new long[sampleSize];
				
				//computation
			}

			realNumbersX[count] = event.values[0];
			imagNumbersX[count] = 0d;
			realNumbersY[count] = event.values[1];
			imagNumbersY[count] = 0d;
			realNumbersZ[count] = event.values[2];
			imagNumbersZ[count] = 0d;
			timestamps[count] = event.timestamp;
			count++;

			if (count == sampleSize) {
				
				double avgX = Util.getAverage(realNumbersX);
				double avgY = Util.getAverage(realNumbersY);
				double avgZ = Util.getAverage(realNumbersZ);
				
				//default
				int output_State = 0;
				
				if(Math.abs(avgX)>6 && Math.abs(avgY)<4 && Math.abs(avgZ) < 4){
					//front
					output_State = 1;
				}
				else if(Math.abs(avgX)<4 && Math.abs(avgY)<4 && Math.abs(avgZ) > 6){
					//top
					output_State = 2;
				}
				
				new FFT(sampleSize).fft(realNumbersX, imagNumbersX);
				new FFT(sampleSize).fft(realNumbersY, imagNumbersY);
				new FFT(sampleSize).fft(realNumbersZ, imagNumbersZ);
				
				double output_FrequencyX = Util.getFrequency(realNumbersX, imagNumbersX, timestamps, sampleSize);
				double output_FrequencyY = Util.getFrequency(realNumbersY, imagNumbersY, timestamps, sampleSize);
				double output_FrequencyZ = Util.getFrequency(realNumbersZ, imagNumbersZ, timestamps, sampleSize);
				
				double magnitudeX = Math.abs(realNumbersX[Util.computeMaxIndex(realNumbersX, imagNumbersX)]);
				double magnitudeY = Math.abs(realNumbersY[Util.computeMaxIndex(realNumbersY, imagNumbersY)]);
				double magnitudeZ = Math.abs(realNumbersZ[Util.computeMaxIndex(realNumbersZ, imagNumbersZ)]);
				
				// Util.addDoublesToFiles(realNumbersX, imagNumbersX,
				// realNumbersY,
				// imagNumbersY, realNumbersZ, imagNumbersZ, output,
				// timestamps);
				
				double circleXY = magnitudeX/magnitudeY;
				double circleYZ = magnitudeY/magnitudeZ;
				double circleZX = magnitudeZ/magnitudeX;
				
				String output="";
				output+=output_State+","+output_FrequencyX+","+output_FrequencyY+","+output_FrequencyZ;
				output+=","+magnitudeX+","+magnitudeY+","+magnitudeZ;
				output+=","+circleXY+","+circleYZ+","+circleZX;
				
				sendMessage(output);
				count = 0;
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			// TODO Auto-generated method stub
		}
	}

}
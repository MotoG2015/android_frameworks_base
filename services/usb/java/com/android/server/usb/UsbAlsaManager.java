/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.alsa.AlsaCardsParser;
import android.alsa.AlsaDevicesParser;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.media.AudioManager;
import android.midi.IMidiManager;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * UsbAlsaManager manages USB audio and MIDI devices.
 */
public class UsbAlsaManager {
    private static final String TAG = UsbAlsaManager.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String ALSA_DIRECTORY = "/dev/snd/";

    private final Context mContext;
    private IMidiManager mMidiManager;

    private final AlsaCardsParser mCardsParser = new AlsaCardsParser();
    private final AlsaDevicesParser mDevicesParser = new AlsaDevicesParser();

    // this is needed to map USB devices to ALSA Audio Devices, especially to remove an
    // ALSA device when we are notified that its associated USB device has been removed.

    private final HashMap<UsbDevice,UsbAudioDevice>
        mAudioDevices = new HashMap<UsbDevice,UsbAudioDevice>();

    private final HashMap<String,AlsaDevice>
        mAlsaDevices = new HashMap<String,AlsaDevice>();

    private UsbAudioDevice mSelectedAudioDevice = null;

    private final class AlsaDevice {
        public static final int TYPE_UNKNOWN = 0;
        public static final int TYPE_PLAYBACK = 1;
        public static final int TYPE_CAPTURE = 2;
        public static final int TYPE_MIDI = 3;

        public int mCard;
        public int mDevice;
        public int mType;

        public AlsaDevice(int type, int card, int device) {
            mType = type;
            mCard = card;
            mDevice = device;
        }

        public boolean equals(Object obj) {
            if (! (obj instanceof AlsaDevice)) {
                return false;
            }
            AlsaDevice other = (AlsaDevice)obj;
            return (mType == other.mType && mCard == other.mCard && mDevice == other.mDevice);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("AlsaDevice: [card: " + mCard);
            sb.append(", device: " + mDevice);
            sb.append(", type: " + mType);
            sb.append("]");
            return sb.toString();
        }
    }

    private final FileObserver mAlsaObserver = new FileObserver(ALSA_DIRECTORY,
            FileObserver.CREATE | FileObserver.DELETE) {
        public void onEvent(int event, String path) {
            switch (event) {
                case FileObserver.CREATE:
                    alsaFileAdded(path);
                    break;
                case FileObserver.DELETE:
                    alsaFileRemoved(path);
                    break;
            }
        }
    };

    /* package */ UsbAlsaManager(Context context) {
        mContext = context;

        // initial scan
        mCardsParser.scan();
    }

    public void systemReady() {
        final IBinder b = ServiceManager.getService(Context.MIDI_SERVICE);
        mMidiManager = IMidiManager.Stub.asInterface(b);
        mAlsaObserver.startWatching();

        // add existing alsa devices
        File[] files = new File(ALSA_DIRECTORY).listFiles();
        for (int i = 0; i < files.length; i++) {
            alsaFileAdded(files[i].getName());
        }
    }

    // Broadcasts the arrival/departure of a USB audio interface
    // audioDevice - the UsbAudioDevice that was added or removed
    // enabled - if true, we're connecting a device (it's arrived), else disconnecting
    private void sendDeviceNotification(UsbAudioDevice audioDevice, boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "sendDeviceNotification(enabled:" + enabled +
                    " c:" + audioDevice.mCard +
                    " d:" + audioDevice.mDevice + ")");
        }

        // send a sticky broadcast containing current USB state
        Intent intent = new Intent(AudioManager.ACTION_USB_AUDIO_DEVICE_PLUG);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        intent.putExtra("state", enabled ? 1 : 0);
        intent.putExtra("card", audioDevice.mCard);
        intent.putExtra("device", audioDevice.mDevice);
        intent.putExtra("hasPlayback", audioDevice.mHasPlayback);
        intent.putExtra("hasCapture", audioDevice.mHasCapture);
        intent.putExtra("hasMIDI", audioDevice.mHasMIDI);
        intent.putExtra("class", audioDevice.mDeviceClass);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private AlsaDevice waitForAlsaDevice(int card, int device, int type) {
        AlsaDevice testDevice = new AlsaDevice(type, card, device);

        // This value was empirically determined.
        final int kWaitTime = 2500; // ms

        synchronized(mAlsaDevices) {
            long timeout = SystemClock.elapsedRealtime() + kWaitTime;
            do {
                if (mAlsaDevices.values().contains(testDevice)) {
                    return testDevice;
                }
                long waitTime = timeout - SystemClock.elapsedRealtime();
                if (waitTime > 0) {
                    try {
                        mAlsaDevices.wait(waitTime);
                    } catch (InterruptedException e) {
                        Slog.d(TAG, "usb: InterruptedException while waiting for ALSA file.");
                    }
                }
            } while (timeout > SystemClock.elapsedRealtime());
        }

        Slog.e(TAG, "waitForAlsaDevice failed for " + testDevice);
        return null;
    }

    private void alsaFileAdded(String name) {
        int type = AlsaDevice.TYPE_UNKNOWN;
        int card = -1, device = -1;

        if (name.startsWith("pcmC")) {
            if (name.endsWith("p")) {
                type = AlsaDevice.TYPE_PLAYBACK;
            } else if (name.endsWith("c")) {
                type = AlsaDevice.TYPE_CAPTURE;
            }
        } else if (name.startsWith("midiC")) {
            type = AlsaDevice.TYPE_MIDI;
        }

        if (type != AlsaDevice.TYPE_UNKNOWN) {
            try {
                int c_index = name.indexOf('C');
                int d_index = name.indexOf('D');
                int end = name.length();
                if (type == AlsaDevice.TYPE_PLAYBACK || type == AlsaDevice.TYPE_CAPTURE) {
                    // skip trailing 'p' or 'c'
                    end--;
                }
                card = Integer.parseInt(name.substring(c_index + 1, d_index));
                device = Integer.parseInt(name.substring(d_index + 1, end));
            } catch (Exception e) {
                Slog.e(TAG, "Could not parse ALSA file name " + name, e);
                return;
            }
            synchronized(mAlsaDevices) {
                if (mAlsaDevices.get(name) == null) {
                    AlsaDevice alsaDevice = new AlsaDevice(type, card, device);
                    Slog.d(TAG, "Adding ALSA device " + alsaDevice);
                    mAlsaDevices.put(name, alsaDevice);
                    mAlsaDevices.notifyAll();
                }
            }
        }
    }

    private void alsaFileRemoved(String path) {
        synchronized(mAlsaDevices) {
            AlsaDevice device = mAlsaDevices.remove(path);
            if (device != null) {
                Slog.d(TAG, "ALSA device removed: " + device);
            }
        }
    }

    /*
     * Select the default device of the specified card.
     */
    /* package */ boolean selectCard(int card) {
        if (DEBUG) {
            Slog.d(TAG, "selectCard() card:" + card);
        }
        if (!mCardsParser.isCardUsb(card)) {
            // Don't. AudioPolicyManager has logic for falling back to internal devices.
            return false;
        }

        if (mSelectedAudioDevice != null) {
            if (mSelectedAudioDevice.mCard == card) {
                // Nothing to do here.
                return false;
            }
            // "disconnect" the AudioPolicyManager from the previously selected device.
            sendDeviceNotification(mSelectedAudioDevice, false);
            mSelectedAudioDevice = null;
        }

        mDevicesParser.scan();
        int device = mDevicesParser.getDefaultDeviceNum(card);

        boolean hasPlayback = mDevicesParser.hasPlaybackDevices(card);
        boolean hasCapture = mDevicesParser.hasCaptureDevices(card);
        boolean hasMidi = mDevicesParser.hasMIDIDevices(card);
        int deviceClass =
            (mCardsParser.isCardUsb(card)
                ? UsbAudioDevice.kAudioDeviceClass_External
                : UsbAudioDevice.kAudioDeviceClass_Internal) |
            UsbAudioDevice.kAudioDeviceMeta_Alsa;

        // Playback device file needed/present?
        if (hasPlayback && (waitForAlsaDevice(card, device, AlsaDevice.TYPE_PLAYBACK) == null)) {
            return false;
        }

        // Capture device file needed/present?
        if (hasCapture && (waitForAlsaDevice(card, device, AlsaDevice.TYPE_CAPTURE) == null)) {
            return false;
        }
        //TODO - seems to me that we need to decouple the above tests for audio
        // from the one below for MIDI.

        // MIDI device file needed/present?
        AlsaDevice midiDevice = null;
        if (hasMidi) {
            midiDevice = waitForAlsaDevice(card, device, AlsaDevice.TYPE_MIDI);
        }

        if (DEBUG) {
            Slog.d(TAG, "usb: hasPlayback:" + hasPlayback + " hasCapture:" + hasCapture);
        }

        mSelectedAudioDevice =
                new UsbAudioDevice(card, device, hasPlayback, hasCapture, hasMidi, deviceClass);
        mSelectedAudioDevice.mDeviceName = mCardsParser.getCardRecordFor(card).mCardName;
        mSelectedAudioDevice.mDeviceDescription =
                mCardsParser.getCardRecordFor(card).mCardDescription;

        sendDeviceNotification(mSelectedAudioDevice, true);

        return true;
    }

    /* package */ boolean selectDefaultDevice() {
        if (DEBUG) {
            Slog.d(TAG, "UsbAudioManager.selectDefaultDevice()");
        }
        mCardsParser.scan();
        return selectCard(mCardsParser.getDefaultCard());
    }

    /* package */ void deviceAdded(UsbDevice usbDevice) {
       if (DEBUG) {
          Slog.d(TAG, "deviceAdded(): " + usbDevice);
        }

        // Is there an audio interface in there?
        boolean isAudioDevice = false;
        AlsaDevice midiDevice = null;

        // FIXME - handle multiple configurations?
        int interfaceCount = usbDevice.getInterfaceCount();
        for (int ntrfaceIndex = 0; !isAudioDevice && ntrfaceIndex < interfaceCount;
                ntrfaceIndex++) {
            UsbInterface ntrface = usbDevice.getInterface(ntrfaceIndex);
            if (ntrface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                isAudioDevice = true;
            }
        }
        if (!isAudioDevice) {
            return;
        }

        ArrayList<AlsaCardsParser.AlsaCardRecord> prevScanRecs = mCardsParser.getScanRecords();
        mCardsParser.scan();

        int addedCard = -1;
        ArrayList<AlsaCardsParser.AlsaCardRecord>
            newScanRecs = mCardsParser.getNewCardRecords(prevScanRecs);
        if (newScanRecs.size() > 0) {
            // This is where we select the just connected device
            // NOTE - to switch to prefering the first-connected device, just always
            // take the else clause below.
            addedCard = newScanRecs.get(0).mCardNum;
        } else {
            addedCard = mCardsParser.getDefaultUsbCard();
        }

        // If the default isn't a USB device, let the existing "select internal mechanism"
        // handle the selection.
        if (mCardsParser.isCardUsb(addedCard)) {
            selectCard(addedCard);
            mAudioDevices.put(usbDevice, mSelectedAudioDevice);
        }

        if (midiDevice != null && mMidiManager != null) {
            try {
                mMidiManager.alsaDeviceAdded(midiDevice.mCard, midiDevice.mDevice, usbDevice);
            } catch (RemoteException e) {
                Slog.e(TAG, "MIDI Manager dead", e);
            }
        }
    }

    /* package */ void deviceRemoved(UsbDevice device) {
       if (DEBUG) {
          Slog.d(TAG, "deviceRemoved(): " + device);
        }

        UsbAudioDevice audioDevice = mAudioDevices.remove(device);
        if (audioDevice != null) {
            if (audioDevice.mHasPlayback || audioDevice.mHasPlayback) {
                sendDeviceNotification(audioDevice, false);
            }
            if (audioDevice.mHasMIDI) {
                try {
                    mMidiManager.alsaDeviceRemoved(device);
                } catch (RemoteException e) {
                    Slog.e(TAG, "MIDI Manager dead", e);
                }
            }
        }

        mSelectedAudioDevice = null;

        // if there any external devices left, select one of them
        selectDefaultDevice();
    }

    //
    // Devices List
    //
    public ArrayList<UsbAudioDevice> getConnectedDevices() {
        ArrayList<UsbAudioDevice> devices = new ArrayList<UsbAudioDevice>(mAudioDevices.size());
        for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
            devices.add(entry.getValue());
        }
        return devices;
    }

    //
    // Logging
    //
    public void dump(FileDescriptor fd, PrintWriter pw) {
        pw.println("  USB AudioDevices:");
        for (UsbDevice device : mAudioDevices.keySet()) {
            pw.println("    " + device.getDeviceName() + ": " + mAudioDevices.get(device));
        }
    }

    public void logDevicesList(String title) {
      if (DEBUG) {
          for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, "UsbDevice-------------------");
              Slog.i(TAG, "" + (entry != null ? entry.getKey() : "[none]"));
              Slog.i(TAG, "UsbAudioDevice--------------");
              Slog.i(TAG, "" + entry.getValue());
          }
      }
  }

  // This logs a more terse (and more readable) version of the devices list
  public void logDevices(String title) {
      if (DEBUG) {
          Slog.i(TAG, title);
          for (HashMap.Entry<UsbDevice,UsbAudioDevice> entry : mAudioDevices.entrySet()) {
              Slog.i(TAG, entry.getValue().toShortString());
          }
      }
  }
}
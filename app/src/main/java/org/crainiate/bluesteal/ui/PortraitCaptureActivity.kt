package org.crainiate.bluesteal.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * ZXing's default [CaptureActivity] is declared `sensorLandscape`, so the scanner
 * viewfinder opens sideways. This subclass exists only so we can register it in
 * the manifest with `android:screenOrientation="portrait"`, matching the iOS
 * (VisionKit) scanner. Wired up via `ScanOptions.setCaptureActivity(...)`.
 */
class PortraitCaptureActivity : CaptureActivity()

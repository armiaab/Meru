# Meru

A small Android app that copies photos from a camera to your phone. Plug the camera in with a USB cable, and Meru copies the new JPEG photos in the background and shows its progress in a notification.

## How it works

- Android can open Meru when a USB camera is attached (USB imaging class, PTP).
- A foreground service reads the camera's storage through Android's MTP API and goes through its folders.
- JPEG photos are copied to `Pictures/sync` in the app's own folder on the phone. A photo that is already there, by file name, is skipped.

It only handles JPEG files for now, not RAW.

## Build

Open the project in Android Studio and run it on a phone that supports USB host mode.

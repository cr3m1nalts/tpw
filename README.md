# TPW: Totally Parallax Wallpaper

A live wallpaper that turns one photo into a 3D-feeling scene. Tilt the phone
and the background turns in perspective around the object you picked.

## Features

- Automatic person selection, with a brush and eraser for any other object
- AI background fill behind the object, on the device
- Relief or volume shape for the object
- Clock styles: classic, frosted glass, liquid glass
- Horizontal or vertical clock; move and resize it freely, with center snapping
- Turkish and English

## Requirements

- Android 12 or newer (liquid glass needs Android 13; on 12 it falls back to frosted)
- To build: Android Studio with its bundled JDK

## Build

Open the project in Android Studio and run it. The inpainting and depth models are
downloaded and verified on the first build (about 260 MB).

Release builds are signed only when `~/tpw-release.keystore` and
`~/tpw-signing.txt` exist on the build machine. Neither belongs in this repository.

## Privacy

Photos are processed on your phone and no internet connection is needed for that.
The app itself never uploads them. It includes Google libraries that hold the
internet permission.

## Tested on

Galaxy A56, Android 17, One UI 9. Other devices are untested.

## Credits

- [LaMa-Dilated](https://huggingface.co/qualcomm/LaMa-Dilated) inpainting model, Apache-2.0
- [Depth Anything V2 Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Small) depth model, Apache-2.0, via [Qualcomm AI Hub](https://huggingface.co/qualcomm/Depth-Anything-V2) (MIT)
- ML Kit Selfie Segmentation and LiteRT by Google

# Bundled models

## efficientdet_lite0.tflite

Object detector used to confirm a vehicle is in frame before any plate text is
read, and to attach a body type (car / truck / bus / motorcycle) to a sighting.

- Source: MediaPipe model card, EfficientDet-Lite0 (int8)
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite
- Licence: Apache 2.0
- SHA-256: 0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb
- Size: 4,602,795 bytes
- Input: 320×320 RGB; 90 COCO classes with labels embedded in the model metadata
- Runs on-device through the TensorFlow Lite Task Library; nothing leaves the phone

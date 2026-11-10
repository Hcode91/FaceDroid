# FaceDroid

Android app that turns a face photo into a textured 3D head mesh.

1. **Photo** – pick a front-on face photo. MediaPipe finds the face and fits a 468-point mesh to it.
2. **Pins** – drag the yellow pins (eyes, brows, nose, nostrils, mouth, chin, jaw) onto the right spots if the fit is off. The rest of the mesh follows. Pinch to zoom, drag empty space to pan. Moved pins turn red.
3. **3D** – spin the result around; the Depth slider flattens or exaggerates it.
4. **UV map** – the baked texture.
5. **Export** – saves `face_<time>.obj`, `.mtl` and `.png` (2048×2048) to `Download/FaceDroid`.

The mesh uses the MediaPipe canonical face layout, so every export shares the same UVs and vertex order. Eyes and mouth are open holes.

## Build

GitHub Actions builds the debug APK on every push (Actions → latest run → `FaceDroid-apk`). The face model downloads automatically during the build. A fixed debug key is committed so new builds install over old ones.

`app/src/main/assets/face_topology.txt` is derived from MediaPipe's `canonical_face_model.obj` (Apache 2.0).

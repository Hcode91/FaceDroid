# FaceDroid

Android app that turns a face photo into a textured 3D head mesh, plus an OBJ viewer.

1. **Photo** – pick a front-on face photo. MediaPipe finds the face and fits a 468-point mesh to it.
2. **Pins** – drag the yellow pins (eyes, brows, nose, nostrils, mouth, chin, jaw) onto the right spots if the fit is off. The rest of the mesh follows. Pinch to zoom, drag empty space to pan. Moved pins turn red.
3. **Head: full / face only** – full head grows a skull from the face edge round to the back (closed mesh, ~1,000 vertices). The skull is fitted to the face's size, proportions and tilt.
4. **3D** – spin the result around; the Depth slider flattens or exaggerates it.
5. **UV map** – the baked texture. Full head: 4096×2048, face on the left half, rest of the head on the right. Skull areas visible in the photo (hairline, temples) come from the photo; hidden areas are filled with a colour sampled above the forehead. Face only: 2048×2048.
6. **Export** – saves `face_<time>.obj`, `.mtl` and `.png` to `Download/FaceDroid`.
7. **OBJ viewer** – opens any `.obj`. Pick the `.obj` and its texture image together (and the `.mtl` if it has one); "My exports" lists FaceDroid's own saves. Drag to rotate, pinch to zoom.

The face part uses the MediaPipe canonical face layout, so every export shares the same vertex order and UVs. No ears or neck yet.

## Build

GitHub Actions builds the debug APK on every push (Actions → latest run → `FaceDroid-apk`). The face model downloads automatically during the build. A fixed debug key is committed so new builds install over old ones.

`app/src/main/assets/face_topology.txt` is derived from MediaPipe's `canonical_face_model.obj` (Apache 2.0).

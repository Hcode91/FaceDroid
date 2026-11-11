# FaceDroid

Android app that turns photos into textured 3D models: a head from a face photo (plus an optional side photo), a full body from full-length photos, and an OBJ viewer.

1. **Photo** – pick a front-on face photo. MediaPipe finds the face and fits a 468-point mesh to it.
2. **Pins** – drag the yellow pins (eyes, brows, nose, nostrils, mouth, chin, jaw) onto the right spots if the fit is off. The rest of the mesh follows. Pinch to zoom, drag empty space to pan. Moved pins turn red.
3. **Head: full / face only** – full head grows a skull from the face edge round to the back (closed mesh, ~1,000 vertices). The skull is fitted to the face's size, proportions and tilt.
4. **3D** – spin the result around; the Depth slider flattens or exaggerates it.
5. **UV map** – the baked texture. Full head: 4096×2048, face on the left half, rest of the head on the right. Skull areas visible in the photo (hairline, temples) come from the photo; hidden areas are filled with a colour sampled above the forehead. Face only: 2048×2048.
6. **Export** – saves `face_<time>.obj`, `.mtl` and `.png` to `Download/FaceDroid`.
7. **Side photo** (optional, full head only) – a side-on photo of the same person. Drag 5 pins (nose tip, chin, top of head, back of head, ear); "Flip side" if the face points the other way. It reshapes the top and back of the skull to the real profile and textures the sides of the head (colour-matched to the front photo, mirrored for the other side).
8. **OBJ viewer** – opens any `.obj`. Pick the `.obj` and its texture image together (and the `.mtl` if it has one); "My exports" lists FaceDroid's own saves. Drag to rotate, pinch to zoom.

The face part uses the MediaPipe canonical face layout, so every export shares the same vertex order and UVs. No ears or neck yet.

## Body

The **Body** button opens the full-body screen.

- **Photos:** front (required), side and back (optional). Full length, standing straight, arms slightly away from the body (A-pose), fitted clothes, ideally taken by someone else from about waist height.
- **Pins:** MediaPipe Pose finds the joints (blue = person's left, pink = person's right). Drag any that are off. Reset pins goes back to the detected positions.
- **Fitting:** the MakeHuman base body is warped (thin-plate spline) so its joints land on the pins, then refined with points along the person's outline from MediaPipe's person mask (turn off with "Outline fit" if the background confuses it). The front photo sets height/width, the side photo sets depth. The Height box sets the real-world scale.
- **Head:** "Head: FaceDroid" swaps the template head for the head last built on the face screen; the neck is pulled up inside it. "Head: template" keeps the MakeHuman head.
- **Texture:** 2048×2048 on the MakeHuman UV layout; each triangle takes the photo that sees it most directly (with a hidden-surface test), colours matched to the front photo, gaps filled smoothly.
- **Export:** `body_<time>.obj` with a `body` part and (if used) a `head` part, each with its own texture.

## Measure

The **Measure** tab on the Body screen estimates body measurements from the same front photo, optional side photo, pins and outline. Enter the person's height first: it sets the scale. With the height box empty, lengths are shown as a percentage of height.

- **How:** lengths come from the joint pins, widths and depths from the person outline, circumferences from an ellipse through the front width and side depth at the same height. Arms use a circle from the front outline; legs do too when there's no side photo.
- **Honest labels:** each row is *Measured*, *Estimate* (uses a shape assumption) or *Unavailable* with the reason. Torso circumferences need the side photo. A few (head height, neck length, back length, hand width, sitting height) can't be got from these photos and say so.
- **± values:** roughly one standard deviation, built from pin accuracy (1% of height), outline edge accuracy, scale accuracy (1.5%) and the shape assumption (4–10%). Rows are tagged good / fair / rough. Values outside the usual human range, arms touching the body, and front/side photos that disagree are flagged.
- **Clothing:** the clothed outline is what gets measured. Loose clothes make everything bigger.
- **Overlay:** solid lines are distances seen directly; dashed lines mark the level a circumference was estimated at. Tap a line or a row to highlight it and see its method and limits. Fix pins on the Front/Side tabs and come back to re-measure.
- **Export:** in the Measure tab, Export saves `measure_<time>.json`, `.csv` and `.pdf` to `Download/FaceDroid`. The JSON includes each measurement's definition (landmarks, method, photos needed, limits).
- **Bra size (UK):** a rough estimate from the bust and under-chest circumferences (needs a side photo and a height), shown with its likely cup and band range. One cup is only 2.5 cm, so expect it to be a cup or two out. **Bra size from tape** gives an exact size from two typed-in tape measurements: band = under-bust rounded to an even inch, one cup per inch of bust over band.
- **Adding measurements:** add a line to `MeasurementRegistry.kt`.
- **Checked against:** a synthetic 180 cm mannequin with known sizes (`MeasureTest`). It has not been validated against tape measurements of real people, so don't treat it as tailoring- or medical-grade.

Everything runs on the phone; nothing is uploaded. Only measure adults who have agreed to it.

## Model editor

**Edit model** (face screen, body screen) or **Edit** (OBJ viewer) opens the editor. Only vertex positions change, so the texture and UV map stay as they were.

- **Sliders:** Width, Depth and Height for any model (70–130%). Bodies made on the Body screen also get Head size, Shoulders, Chest, Waist, Hips, Arm thickness/length and Leg thickness/length. Movement is blended across the joins between body areas so nothing pulls apart.
- **Brush:** Push out, Pull in or Smooth, with Brush size and Strength, and an optional left/right Mirror. One finger sculpts; two fingers turn and zoom. Undo steps back up to 20 brush strokes.
- **Edits carry back:** leaving the editor hands the edited shape to the screen it came from, so its 3D view, UV map and Export use it, an edited head is what the Body screen puts on the body, and reopening the editor continues where you left off. Rebuilding from the photos (pins, photos, height, depth) drops the edits.
- **Reset all** puts everything back. **Save as OBJ** writes `edit_<time>.obj`, `.mtl` and textures to `Download/FaceDroid`; the original export is not changed.
- OBJs opened from the viewer get the whole-model sliders and the brush (the app can't tell which part is a waist or an arm), assume Y is up, and are saved with unshared vertices, so the file is larger.

## Build

GitHub Actions builds the debug APK on every push (Actions → latest run → `FaceDroid-apk`). The face model downloads automatically during the build. A fixed debug key is committed so new builds install over old ones.

`app/src/main/assets/face_topology.txt` is derived from MediaPipe's `canonical_face_model.obj` (Apache 2.0). `app/src/main/assets/body_template.txt` is the MakeHuman base mesh hm08 and default weights (CC0, makehumancommunity.org). MediaPipe face and pose models are downloaded at build time.

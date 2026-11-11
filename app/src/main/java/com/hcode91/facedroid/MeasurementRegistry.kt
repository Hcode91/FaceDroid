package com.hcode91.facedroid

import java.util.Locale

/**
 * Every measurement the app knows about. To add one: write a compute function on [MeasureEngine]
 * (or reuse its helpers) and add a line here. Nothing else needs to change.
 */
object MeasurementRegistry {
    private const val HEAD = "Head and neck"
    private const val UPPER = "Upper body"
    private const val ABDOMEN = "Waist and hips"
    private const val ARMS = "Arms"
    private const val LEGS = "Legs"
    private const val WHOLE = "Whole body"

    private const val F = "front"
    private const val FS = "front + side"
    private const val S = "side"
    private const val CLOTHES = "Measures the clothed outline; loose clothing makes it too big."
    private const val ELL = "Front width and side depth at the same height, joined as an ellipse (Ramanujan perimeter)."
    private const val PINS2D = "Straight line in the photo; too short if the limb points towards or away from the camera."

    private fun d(
        id: String, name: String, group: String, kind: MKind, landmarks: String, method: String, views: String,
        limits: String, scale: Boolean = true, compute: (MeasureEngine) -> Outcome,
    ) = MeasurementDef(id, name, group, kind, landmarks, method, views, scale, limits, compute)

    private fun missing(id: String, name: String, group: String, kind: MKind, why: String) =
        d(id, name, group, kind, "-", "Not implemented", "-", why) { Outcome(null, note = why) }

    val all: List<MeasurementDef> = listOf(
        // ---- head and neck
        d("head_width", "Head width", HEAD, MKind.WIDTH, "outline above nose", "Widest outline row between the top of the head and just below the nose.", F, "Includes hair and ears.") { it.headWidth() },
        d("head_circ", "Head circumference", HEAD, MKind.CIRCUMFERENCE, "outline above nose (front, side)", ELL, FS, "Includes hair; a real head isn't an exact ellipse.") { it.headCirc() },
        missing("head_height", "Head height", HEAD, MKind.LENGTH, "Needs a chin point, which the pose model doesn't give"),
        d("neck_width", "Neck width", HEAD, MKind.WIDTH, "outline between nose and shoulders", "Narrowest outline row between the nose and the shoulder line.", F, "Long hair or collars over the neck make it too wide.") { it.neckWidth() },
        d("neck_circ", "Neck circumference", HEAD, MKind.CIRCUMFERENCE, "narrowest neck row (front, side)", "$ELL Without a side photo: a circle from the front width.", "front (+ side)", "Hair and collars; neck isn't exactly round.") { it.neckCirc() },
        missing("neck_length", "Neck length", HEAD, MKind.LENGTH, "Needs chin and neck-base points, which the pose model doesn't give"),

        // ---- upper body
        d("shoulder_width_joints", "Shoulder width (joint to joint)", UPPER, MKind.WIDTH, "left shoulder, right shoulder", "Distance between the shoulder joint pins.", F, "Joint centres, so narrower than a tailor's shoulder width.") { it.shoulderJoints() },
        d("shoulder_breadth", "Shoulder breadth (outer edges)", UPPER, MKind.WIDTH, "outline just below shoulder line", "Outline width just below the shoulder line.", F, CLOTHES) { it.shoulderBreadth() },
        d("shoulder_circ", "Shoulder circumference", UPPER, MKind.CIRCUMFERENCE, "outline just below shoulder line (front, side)", ELL, FS, "Around both shoulders; the shape here is far from an ellipse, so this is rough.") { it.shoulderCirc() },
        d("chest_width", "Chest width", UPPER, MKind.WIDTH, "outline 25% down shoulder→hip", "Outline width at armpit level.", F, "Arms must be clear of the body. $CLOTHES") { it.chestWidth() },
        d("chest_depth", "Chest depth", UPPER, MKind.DEPTH, "side outline 25% down shoulder→hip", "Side outline depth at armpit level.", FS, "Includes the arm if it sits in front of or behind the chest.") { it.chestDepth() },
        d("chest_circ", "Chest circumference", UPPER, MKind.CIRCUMFERENCE, "outline 25% down shoulder→hip (front, side)", ELL, FS, CLOTHES) { it.chestCirc() },
        d("bust_circ", "Chest at fullest point (bust)", UPPER, MKind.CIRCUMFERENCE, "deepest side-outline row 20–45% down shoulder→hip", "$ELL Taken at the row where the side outline is deepest.", FS, CLOTHES) { it.bustCirc() },
        d("underbust_circ", "Under-chest (underbust)", UPPER, MKind.CIRCUMFERENCE, "outline 45% down shoulder→hip (front, side)", ELL, FS, "Fixed level; may not match where a tape would sit. $CLOTHES") { it.underbustCirc() },
        d("shoulder_to_waist", "Shoulder to waist", UPPER, MKind.LENGTH, "shoulder line, narrowest waist row", "Vertical distance from the shoulder line to the narrowest waist row.", F, "The narrowest row may not be the natural waist under loose clothing.") { it.shoulderToWaist() },
        d("neck_to_waist", "Neck to waist (front)", UPPER, MKind.LENGTH, "narrowest neck row, narrowest waist row", "Vertical distance between the two rows.", F, "Starts at the narrowest neck level, not the neck base.") { it.neckToWaist() },
        missing("back_length", "Back length", UPPER, MKind.LENGTH, "Needs the bone at the base of the neck (C7), which can't be found reliably in a photo"),
        d("shoulder_to_hip", "Torso length (shoulder to hip)", UPPER, MKind.LENGTH, "shoulders, hips", "Shoulder-line midpoint to hip-joint midpoint.", F, PINS2D) { it.shoulderToHip() },

        // ---- waist and hips
        d("waist_width", "Waist width", ABDOMEN, MKind.WIDTH, "narrowest outline row 50–85% down shoulder→hip", "Narrowest outline row in the waist band.", F, CLOTHES) { it.waistWidth() },
        d("waist_depth", "Waist depth", ABDOMEN, MKind.DEPTH, "side outline at the waist row", "Side outline depth at the waist row.", FS, CLOTHES) { it.waistDepth() },
        d("waist_circ", "Natural waist circumference", ABDOMEN, MKind.CIRCUMFERENCE, "narrowest waist row (front, side)", ELL, FS, CLOTHES) { it.waistCirc() },
        d("upper_abd_circ", "Upper abdomen circumference", ABDOMEN, MKind.CIRCUMFERENCE, "outline 58% down shoulder→hip", ELL, FS, CLOTHES) { it.upperAbdCirc() },
        d("navel_circ", "Navel-level circumference", ABDOMEN, MKind.CIRCUMFERENCE, "outline 80% down shoulder→hip", ELL, FS, "The navel isn't detected; its usual level is used. $CLOTHES") { it.navelCirc() },
        d("lower_abd_circ", "Lower abdomen circumference", ABDOMEN, MKind.CIRCUMFERENCE, "outline 92% down shoulder→hip", ELL, FS, CLOTHES) { it.lowerAbdCirc() },
        d("hip_width", "Hip width", ABDOMEN, MKind.WIDTH, "widest outline row around the hip joints", "Widest outline row from 85% to 115% of shoulder→hip.", F, "Hands or arms resting on the hips widen it. $CLOTHES") { it.hipWidth() },
        d("hip_depth", "Hip depth", ABDOMEN, MKind.DEPTH, "side outline at the hip row", "Side outline depth at the hip row.", FS, CLOTHES) { it.hipDepth() },
        d("hip_circ", "Hip circumference", ABDOMEN, MKind.CIRCUMFERENCE, "widest hip row (front, side)", ELL, FS, CLOTHES) { it.hipCirc() },
        d("pelvis_width", "Pelvis width (hip joints)", ABDOMEN, MKind.WIDTH, "left hip, right hip", "Distance between the hip joint pins.", F, "Hip joints are inside the body and only estimated by the pose model.") { it.pelvisWidth() },
        d("waist_hip_ratio", "Waist-to-hip ratio", ABDOMEN, MKind.RATIO, "waist, hip", "Waist circumference ÷ hip circumference (front widths if there's no side photo).", "front (+ side)", "Without a side photo it isn't the usual tape-measure ratio.", false) { it.waistHipRatio() },

        // ---- arms
        d("upper_arm_len", "Upper arm length", ARMS, MKind.LENGTH, "shoulder, elbow", "Shoulder pin to elbow pin, average of both arms.", F, PINS2D) { it.upperArmLen() },
        d("forearm_len", "Forearm length", ARMS, MKind.LENGTH, "elbow, wrist", "Elbow pin to wrist pin, average of both arms.", F, PINS2D) { it.forearmLen() },
        d("arm_len", "Arm length (shoulder to wrist)", ARMS, MKind.LENGTH, "shoulder, elbow, wrist", "Upper arm + forearm.", F, PINS2D) { it.armLen() },
        d("upper_arm_circ", "Upper arm circumference", ARMS, MKind.CIRCUMFERENCE, "outline across mid upper arm", "Outline thickness across the arm, treated as a circle.", F, "Arm must be clear of the body. Sleeves count.") { it.upperArmCirc() },
        d("elbow_circ", "Elbow circumference", ARMS, MKind.CIRCUMFERENCE, "outline across elbow pin", "Outline thickness across the elbow, treated as a circle.", F, "Arm must be clear of the body. Sleeves count.") { it.elbowCirc() },
        d("forearm_circ", "Forearm circumference", ARMS, MKind.CIRCUMFERENCE, "outline across upper forearm", "Outline thickness 30% down the forearm, treated as a circle.", F, "Arm must be clear of the body. Sleeves count.") { it.forearmCirc() },
        d("wrist_circ", "Wrist circumference", ARMS, MKind.CIRCUMFERENCE, "outline across wrist", "Outline thickness just above the wrist pin, treated as a circle.", F, "A wrist is flatter than a circle; watches and cuffs count.") { it.wristCirc() },
        d("hand_len", "Hand length", ARMS, MKind.LENGTH, "wrist, end of outline", "Wrist pin to where the outline ends along the forearm line.", F, "Needs straight, open fingers pointing along the arm.") { it.handLen() },
        missing("hand_width", "Hand width", ARMS, MKind.WIDTH, "Hands are too small and too varied in pose to measure across from a full-length photo"),
        d("arm_span", "Arm span (added up)", ARMS, MKind.LENGTH, "shoulders, elbows, wrists, hands", "Shoulder width + both arms + both hands.", F, "A sum of parts, not a direct measurement.") { it.armSpan() },

        // ---- legs
        d("leg_len", "Leg length (hip joint to floor)", LEGS, MKind.LENGTH, "hips, floor", "Vertical distance from the hip-joint line to the floor.", F, "Hip joints are estimated by the pose model.") { it.legLen() },
        d("inseam", "Inseam", LEGS, MKind.LENGTH, "top of leg gap, floor", "Vertical distance from where the outline splits between the legs down to the floor.", F, "Needs feet apart. Low or loose clothing shortens it; shoes add their sole.") { it.inseam() },
        d("outseam", "Outseam (waist to floor)", LEGS, MKind.LENGTH, "narrowest waist row, floor", "Vertical distance from the waist row to the floor.", F, "Straight down, not along the curve of the hip.") { it.outseam() },
        d("thigh_len", "Thigh length", LEGS, MKind.LENGTH, "hip, knee", "Hip pin to knee pin, average of both legs.", F, PINS2D) { it.thighLen() },
        d("lower_leg_len", "Lower leg length", LEGS, MKind.LENGTH, "knee, ankle", "Knee pin to ankle pin, average of both legs.", F, PINS2D) { it.lowerLegLen() },
        d("upper_thigh_circ", "Upper thigh circumference", LEGS, MKind.CIRCUMFERENCE, "leg outline just below the leg gap", "Front width of each leg with side depth as an ellipse; a circle without a side photo.", "front (+ side)", CLOTHES) { it.upperThighCirc() },
        d("mid_thigh_circ", "Mid-thigh circumference", LEGS, MKind.CIRCUMFERENCE, "leg outline halfway hip→knee", "Front width of each leg with side depth as an ellipse; a circle without a side photo.", "front (+ side)", CLOTHES) { it.midThighCirc() },
        d("knee_circ", "Knee circumference", LEGS, MKind.CIRCUMFERENCE, "leg outline at knee pins", "Front width of each leg with side depth as an ellipse; a circle without a side photo.", "front (+ side)", CLOTHES) { it.kneeCirc() },
        d("calf_circ", "Calf circumference", LEGS, MKind.CIRCUMFERENCE, "widest leg outline 15–45% down knee→ankle", "Front width of each leg with side depth as an ellipse; a circle without a side photo.", "front (+ side)", CLOTHES) { it.calfCirc() },
        d("ankle_circ", "Ankle circumference", LEGS, MKind.CIRCUMFERENCE, "narrowest leg outline just above the ankle", "Front width of each leg with side depth as an ellipse; a circle without a side photo.", "front (+ side)", "Socks, boots and trouser hems count.") { it.ankleCirc() },
        d("foot_len", "Foot length", LEGS, MKind.LENGTH, "side outline below ankle", "Longest outline row below the ankle in the side photo.", S, "Includes shoes; too long if the feet aren't side by side.") { it.footLen() },
        d("foot_width", "Foot width", LEGS, MKind.WIDTH, "front outline near toes", "Outline width near the toes in the front photo.", F, "Feet must point at the camera; includes shoes.") { it.footWidth() },

        // ---- whole body
        d("height", "Height", WHOLE, MKind.LENGTH, "top of head, floor", "Entered by the user; sets the scale.", F, "Not measured – it's the calibration.") { it.height() },
        missing("sitting_height", "Sitting height", WHOLE, MKind.LENGTH, "Needs a seated photo, which this app doesn't take"),
        d("torso_leg_ratio", "Torso-to-leg ratio", WHOLE, MKind.RATIO, "shoulders, hips, floor", "Shoulder-to-hip length ÷ leg length.", F, "Depends on the estimated hip joints.", false) { it.torsoLegRatio() },
        d("arm_height_ratio", "Arm-to-height ratio", WHOLE, MKind.RATIO, "shoulder, elbow, wrist", "Arm length (no hand) ÷ height.", F, PINS2D, false) { it.armHeightRatio() },
        d("leg_height_ratio", "Leg-to-height ratio", WHOLE, MKind.RATIO, "hips, floor", "Leg length ÷ height.", F, "Depends on the estimated hip joints.", false) { it.legHeightRatio() },
        d("shoulder_hip_ratio", "Shoulder-to-hip ratio", WHOLE, MKind.RATIO, "shoulder outline, hip outline", "Shoulder breadth ÷ hip width.", F, CLOTHES, false) { it.shoulderHipRatio() },
    )
}

/** Text formatting and file exports for results. No Android classes, so it can be unit-tested. */
object MeasureFormat {
    fun value(r: MeasurementResult, inches: Boolean): String {
        val v = r.value ?: return "—"
        return when {
            r.def.kind == MKind.RATIO -> String.format(Locale.US, "%.2f ± %.2f", v, r.plusMinus)
            r.status == MStatus.RELATIVE -> String.format(Locale.US, "%.1f ± %.1f %% of height", v, r.plusMinus)
            inches -> String.format(Locale.US, "%.1f ± %.1f in", v / 2.54f, r.plusMinus / 2.54f)
            else -> String.format(Locale.US, "%.1f ± %.1f cm", v, r.plusMinus)
        }
    }

    fun unit(r: MeasurementResult) = when {
        r.value == null -> ""
        r.def.kind == MKind.RATIO -> "ratio"
        r.status == MStatus.RELATIVE -> "percent_of_height"
        else -> "cm"
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    private fun num(x: Float?) = if (x == null) "null" else String.format(Locale.US, "%.2f", x)

    fun json(results: List<MeasurementResult>, warnings: List<String>, heightCm: Float?, generated: String) = buildString {
        append("{\n  \"app\": \"FaceDroid\",\n  \"generated\": \"").append(esc(generated)).append("\",\n")
        append("  \"heightCm\": ").append(num(heightCm)).append(",\n")
        append("  \"plusMinus\": \"about one standard deviation; clothing is not accounted for\",\n")
        append("  \"warnings\": [").append(warnings.joinToString(", ") { "\"" + esc(it) + "\"" }).append("],\n")
        append("  \"measurements\": [\n")
        for ((i, r) in results.withIndex()) {
            append("    {\"id\": \"").append(r.def.id).append("\", \"name\": \"").append(esc(r.def.name)).append("\", ")
            append("\"group\": \"").append(esc(r.def.group)).append("\", \"kind\": \"").append(r.def.kind.name.lowercase()).append("\", ")
            append("\"status\": \"").append(r.status.name.lowercase()).append("\", \"value\": ").append(num(r.value)).append(", ")
            append("\"unit\": \"").append(unit(r)).append("\", \"plusMinus\": ").append(if (r.value == null) "null" else num(r.plusMinus)).append(", ")
            append("\"reliability\": ").append(r.reliability?.let { "\"" + it.name.lowercase() + "\"" } ?: "null").append(", ")
            append("\"note\": \"").append(esc(r.note)).append("\", ")
            append("\"definition\": {\"landmarks\": \"").append(esc(r.def.landmarks)).append("\", \"method\": \"").append(esc(r.def.method))
            append("\", \"views\": \"").append(esc(r.def.views)).append("\", \"needsScale\": ").append(r.def.needsScale)
            append(", \"limitations\": \"").append(esc(r.def.limitations)).append("\"}}")
            append(if (i < results.size - 1) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    private fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun csv(results: List<MeasurementResult>) = buildString {
        append("id,name,group,kind,status,value,unit,plus_minus,reliability,note,method,limitations\n")
        for (r in results) {
            append(r.def.id).append(',').append(cell(r.def.name)).append(',').append(cell(r.def.group)).append(',')
            append(r.def.kind.name.lowercase()).append(',').append(r.status.name.lowercase()).append(',')
            append(if (r.value == null) "" else num(r.value)).append(',').append(unit(r)).append(',')
            append(if (r.value == null) "" else num(r.plusMinus)).append(',').append(r.reliability?.name?.lowercase() ?: "").append(',')
            append(cell(r.note)).append(',').append(cell(r.def.method)).append(',').append(cell(r.def.limitations)).append('\n')
        }
    }
}

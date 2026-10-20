package com.velvet.piano

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

data class MidiEvent(val timestampMs: Long, val type: Int, val note: Int, val velocity: Int)
data class ChordDef(val name: String, val notes: IntArray)

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("velvet")
        }
    }

    external fun initEngine(assetManager: AssetManager): Boolean
    external fun nativeNoteOn(note: Int, velocity: Float)
    external fun nativeNoteOff(note: Int)
    external fun nativeTriggerMetronome(downbeat: Boolean)
    external fun nativeSetSustain(active: Boolean)
    external fun nativeSetInstrument(index: Int)
    external fun nativeSetControls(vol: Float, rev: Float, room: Float, width: Float)

    private lateinit var prefs: SharedPreferences
    lateinit var pianoView: VelvetKeyboardView
    lateinit var overviewStripRow: LinearLayout
    lateinit var overviewStripView: OverviewStripView
    private lateinit var presetTextView: TextView
    private lateinit var minusCircleBtn: TextView
    private lateinit var sustainPill: LinearLayout
    private lateinit var sustainText: TextView
    private lateinit var sustainDot: View
    private lateinit var recText: TextView
    private lateinit var scrollPillText: TextView
    private lateinit var chordsPillText: TextView
    private lateinit var metroPillText: TextView
    private lateinit var chordScrollContainer: HorizontalScrollView
    private lateinit var chordPadBar: LinearLayout

    // 11 Premium Instruments (5 Kept + 6 New Replacements)
    private val instruments = arrayOf(
        "Felt Piano",
        "Rhodes Electric",
        "Neon Synth Piano",
        "Nylon Guitar",
        "Celeste",
        "Wurlitzer EP200",
        "Clavinet Funk",
        "Vibraphone",
        "Marimba",
        "Orchestral Harp",
        "Church Pipe Organ"
    )

    // 20 Rich Chords (Scrollable & Reorderable)
    private val defaultChords = listOf(
        ChordDef("Cmaj9", intArrayOf(48, 55, 59, 62, 64)),
        ChordDef("Am9", intArrayOf(45, 52, 55, 59, 60)),
        ChordDef("Fmaj7", intArrayOf(41, 48, 52, 57, 60)),
        ChordDef("G13", intArrayOf(43, 50, 53, 59, 64)),
        ChordDef("Dm9", intArrayOf(50, 57, 60, 64, 65)),
        ChordDef("Em7", intArrayOf(40, 47, 50, 55, 59)),
        ChordDef("Bbmaj7", intArrayOf(46, 53, 57, 58, 62)),
        ChordDef("E7alt", intArrayOf(40, 47, 52, 56, 60)),
        ChordDef("Abmaj7", intArrayOf(44, 51, 55, 56, 60)),
        ChordDef("Fm9", intArrayOf(41, 48, 51, 55, 56)),
        ChordDef("Dbmaj9", intArrayOf(49, 56, 60, 63, 65)),
        ChordDef("G7sus4", intArrayOf(43, 50, 53, 55, 60)),
        ChordDef("Cm11", intArrayOf(48, 55, 58, 62, 65)),
        ChordDef("Bm7b5", intArrayOf(47, 53, 57, 59, 62)),
        ChordDef("A7#5", intArrayOf(45, 52, 55, 57, 61)),
        ChordDef("Eb6/9", intArrayOf(51, 55, 58, 60, 65)),
        ChordDef("Gm9", intArrayOf(43, 50, 53, 57, 58)),
        ChordDef("Dmaj9", intArrayOf(50, 57, 61, 64, 66)),
        ChordDef("F#m11", intArrayOf(42, 49, 52, 56, 59)),
        ChordDef("B7#9", intArrayOf(47, 51, 54, 57, 62))
    )
    private val userChords = mutableListOf<ChordDef>()

    var currentInstrument = 0
    var sustainOn = true
    var stripVisible = true
    var showNoteLabels = true
    var hapticsEnabled = true
    var blackKeysOnlyUpperZone = false
    var visibleWhiteKeysCount = 10f
    var volumeLevel = 0.90f
    var reverbLevel = 0.30f
    var roomSize = 0.65f
    var stereoWidth = 1.25f

    // Metronome State
    private var metronomeRunning = false
    private var metronomeBpm = 100
    private var metronomeBeat = 0
    private val metroHandler = Handler(Looper.getMainLooper())
    private val metroRunnable = object : Runnable {
        override fun run() {
            if (!metronomeRunning) return
            nativeTriggerMetronome(metronomeBeat % 4 == 0)
            metronomeBeat++
            val intervalMs = (60000L / metronomeBpm.coerceIn(40, 240))
            metroHandler.postDelayed(this, intervalMs)
        }
    }

    // MIDI Recording & Playback State
    private var isRecording = false
    private var isPlayingMidi = false
    private var recordStartTime = 0L
    private val recordedMidiEvents = mutableListOf<MidiEvent>()
    private val playbackHandler = Handler(Looper.getMainLooper())

    fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    fun dpF(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        prefs = getSharedPreferences("VelvetState", Context.MODE_PRIVATE)
        loadPersistedState()

        initEngine(assets)
        nativeSetInstrument(currentInstrument)
        nativeSetSustain(sustainOn)
        nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)

        buildInterface()
    }

    private fun loadPersistedState() {
        sustainOn = prefs.getBoolean("sustain_on", true)
        currentInstrument = prefs.getInt("instrument_idx", 0).coerceIn(0, instruments.size - 1)
        stripVisible = prefs.getBoolean("strip_visible", true)
        showNoteLabels = prefs.getBoolean("show_labels", true)
        hapticsEnabled = prefs.getBoolean("haptics", true)
        blackKeysOnlyUpperZone = prefs.getBoolean("black_keys_upper", false)
        visibleWhiteKeysCount = prefs.getFloat("visible_keys", 10f).coerceIn(6f, 22f)
        metronomeBpm = prefs.getInt("metro_bpm", 100)
        volumeLevel = prefs.getFloat("volume", 0.90f)
        reverbLevel = prefs.getFloat("reverb", 0.30f)
        roomSize = prefs.getFloat("room_size", 0.65f)
        stereoWidth = prefs.getFloat("stereo_width", 1.25f)

        userChords.clear()
        val savedOrder = prefs.getString("chord_order", null)
        if (!savedOrder.isNullOrEmpty()) {
            val names = savedOrder.split(",")
            for (n in names) {
                val found = defaultChords.find { it.name == n }
                if (found != null) userChords.add(found)
            }
        }
        for (c in defaultChords) {
            if (userChords.none { it.name == c.name }) userChords.add(c)
        }
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean("sustain_on", sustainOn)
            .putInt("instrument_idx", currentInstrument)
            .putBoolean("strip_visible", stripVisible)
            .putBoolean("show_labels", showNoteLabels)
            .putBoolean("haptics", hapticsEnabled)
            .putBoolean("black_keys_upper", blackKeysOnlyUpperZone)
            .putFloat("visible_keys", visibleWhiteKeysCount)
            .putInt("metro_bpm", metronomeBpm)
            .putFloat("volume", volumeLevel)
            .putFloat("reverb", reverbLevel)
            .putFloat("room_size", roomSize)
            .putFloat("stereo_width", stereoWidth)
            .putString("chord_order", userChords.joinToString(",") { it.name })
            .apply()
    }

    private fun makePillDrawable(bgColorHex: String, borderColorHex: String? = null, borderWidthDp: Float = 0f, radiusDp: Float = 100f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(Color.parseColor(bgColorHex))
            if (borderColorHex != null && borderWidthDp > 0f) {
                setStroke(dp(borderWidthDp), Color.parseColor(borderColorHex))
            }
        }
    }

    private fun buildInterface() {
        val sysSans = Typeface.create("sans-serif", Typeface.NORMAL)
        val sysSansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1b1310"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // =====================================================================
        // 1. TOOLBAR (56dp tall, bg #2b1e17, padding 0 12dp, gap 8dp)
        // =====================================================================
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2b1e17"))
            setPadding(dp(12f), 0, dp(12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56f)
            )
        }

        // Left Slot (Replaces "Nocturne" badge with Saved MIDI Recordings Folder + Metronome)
        val filesPill = createStandardPill("Files", sysSans).apply {
            setOnClickListener { showRecordingsFolderDialog() }
        }
        toolbar.addView(filesPill)

        val metroPill = createStandardPill("Metro", sysSans).apply {
            metroPillText = getChildAt(0) as TextView
            setOnClickListener { toggleMetronome() }
            setOnLongClickListener {
                showMetronomeBpmDialog()
                true
            }
        }
        toolbar.addView(metroPill)

        // 2. Preset Pill (#e8a547 fill, #2b1608 text, 14sp medium, music-note icon 16dp before name, pad 7 12)
        val presetPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillDrawable("#e8a547")
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8f) }
            setOnClickListener { showInstrumentDialog() }
        }

        val musicNoteIcon = MusicNoteIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply {
                rightMargin = dp(6f)
            }
        }
        presetPill.addView(musicNoteIcon)

        presetTextView = TextView(this).apply {
            text = instruments[currentInstrument]
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = sysSansMedium
            setTextColor(Color.parseColor("#2b1608"))
        }
        presetPill.addView(presetTextView)
        toolbar.addView(presetPill)

        // 3. The "—" Button (30x30 circle, bg #3d2c21, minus icon in amber 17sp)
        minusCircleBtn = TextView(this).apply {
            text = "—"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = sysSansMedium
            setTextColor(Color.parseColor("#e8a547"))
            background = makePillDrawable("#3d2c21", null, 0f, 15f)
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
                rightMargin = dp(8f)
            }
            setOnClickListener {
                // Toggles retractable overview strip quickly
                toggleOverviewStrip()
            }
        }
        toolbar.addView(minusCircleBtn)

        // 4. Flexible Spacer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        toolbar.addView(spacer)

        // 5. Right Utility Pills: Chords, Sustain, Rec, Scroll, Sound (gap 6dp)
        val chordsPill = createStandardPill("Chords", sysSans, gapDp = 6f).apply {
            chordsPillText = getChildAt(0) as TextView
            setOnClickListener {
                if (chordScrollContainer.visibility == View.VISIBLE) {
                    chordScrollContainer.visibility = View.GONE
                    chordsPillText.setTextColor(Color.parseColor("#f0d9b5"))
                } else {
                    chordScrollContainer.visibility = View.VISIBLE
                    chordsPillText.setTextColor(Color.parseColor("#e8a547"))
                }
            }
        }
        toolbar.addView(chordsPill)

        // Sustain Pill (Active: bg #4a3322, 1.5 border #e8a547, text #e8a547, 7dp amber dot AFTER label)
        sustainPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6f) }
            setOnClickListener {
                sustainOn = !sustainOn
                nativeSetSustain(sustainOn)
                updateSustainVisual()
                saveState()
            }
        }
        sustainText = TextView(this).apply {
            text = "Sustain"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = sysSans
        }
        sustainDot = View(this).apply {
            background = makePillDrawable("#e8a547")
            layoutParams = LinearLayout.LayoutParams(dp(7f), dp(7f)).apply {
                leftMargin = dp(6f)
            }
        }
        sustainPill.addView(sustainText)
        sustainPill.addView(sustainDot)
        updateSustainVisual()
        toolbar.addView(sustainPill)

        // Rec Pill (8dp red dot #e5604d BEFORE the label, no Play button next to it)
        val recPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillDrawable("#3d2c21")
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6f) }
            setOnClickListener { toggleMidiRecording() }
        }
        val recDot = View(this).apply {
            background = makePillDrawable("#e5604d")
            layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply {
                rightMargin = dp(6f)
            }
        }
        recText = TextView(this).apply {
            text = "Rec"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = sysSans
            setTextColor(Color.parseColor("#f0d9b5"))
        }
        recPill.addView(recDot)
        recPill.addView(recText)
        toolbar.addView(recPill)

        // Scroll Pill (Toggles retractable overview strip; long-press locks/unlocks position)
        val scrollPill = createStandardPill("Scroll", sysSans, gapDp = 6f).apply {
            scrollPillText = getChildAt(0) as TextView
            setOnClickListener { toggleOverviewStrip() }
        }
        toolbar.addView(scrollPill)

        // Sound Pill (24dp decorative knob icon before label, pill padding 3 12 3 4)
        val soundPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillDrawable("#3d2c21")
            setPadding(dp(4f), dp(3f), dp(12f), dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showSoundSettingsDialog() }
        }
        val knobIcon = KnobIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply {
                rightMargin = dp(6f)
            }
        }
        val soundLabel = TextView(this).apply {
            text = "Sound"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = sysSans
            setTextColor(Color.parseColor("#f0d9b5"))
        }
        soundPill.addView(knobIcon)
        soundPill.addView(soundLabel)
        toolbar.addView(soundPill)

        root.addView(toolbar)

        // =====================================================================
        // 2. OVERVIEW STRIP ROW (54dp tall, bg #241914, 3dp bottom border #8a3f1f)
        // =====================================================================
        overviewStripRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#241914"))
            visibility = if (stripVisible) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54f)
            )
        }

        val stripTrackWrapper = FrameLayout(this).apply {
            setPadding(dp(12f), 0, dp(12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(51f)
            )
        }

        overviewStripView = OverviewStripView(this, this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40f),
                Gravity.CENTER_VERTICAL
            )
        }
        stripTrackWrapper.addView(overviewStripView)
        overviewStripRow.addView(stripTrackWrapper)

        val stripBottomBorder = View(this).apply {
            setBackgroundColor(Color.parseColor("#8a3f1f"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3f)
            )
        }
        overviewStripRow.addView(stripBottomBorder)
        root.addView(overviewStripRow)

        // =====================================================================
        // OPTIONAL SCROLLABLE & REORDERABLE CHORDS BAR
        // =====================================================================
        chordScrollContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#241914"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44f)
            )
        }
        chordPadBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        }
        chordScrollContainer.addView(chordPadBar)
        populateChordButtons()
        root.addView(chordScrollContainer)

        // =====================================================================
        // 3. KEYBOARD (Fills the rest, padding 8 10 10, bg #1b1310)
        // =====================================================================
        pianoView = VelvetKeyboardView(this, this).apply {
            showLabels = showNoteLabels
            keyScrollOffset = prefs.getFloat("scroll_offset", -1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(pianoView)

        updateScrollPillVisual()
        setContentView(root)
    }

    private fun createStandardPill(label: String, tf: Typeface, gapDp: Float = 8f): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillDrawable("#3d2c21")
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(gapDp) }

            val tv = TextView(this@MainActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = tf
                setTextColor(Color.parseColor("#f0d9b5"))
            }
            addView(tv)
        }
    }

    private fun updateSustainVisual() {
        if (sustainOn) {
            sustainPill.background = makePillDrawable("#4a3322", "#e8a547", 1.5f)
            sustainText.setTextColor(Color.parseColor("#e8a547"))
            sustainDot.visibility = View.VISIBLE
        } else {
            sustainPill.background = makePillDrawable("#3d2c21")
            sustainText.setTextColor(Color.parseColor("#f0d9b5"))
            sustainDot.visibility = View.GONE
        }
    }

    private fun toggleOverviewStrip() {
        stripVisible = !stripVisible
        overviewStripRow.visibility = if (stripVisible) View.VISIBLE else View.GONE
        updateScrollPillVisual()
        saveState()
    }

    private fun updateScrollPillVisual() {
        scrollPillText.setTextColor(
            if (stripVisible) Color.parseColor("#e8a547") else Color.parseColor("#f0d9b5")
        )
    }

    private fun populateChordButtons() {
        chordPadBar.removeAllViews()
        val sysSans = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Reorder Chords button first
        val reorderBtn = TextView(this).apply {
            text = "⇅ Order"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = sysSans
            setTextColor(Color.parseColor("#e8a547"))
            background = makePillDrawable("#2b1e17", "#8a3f1f", 1f, 8f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8f) }
            setOnClickListener { showReorderChordsDialog() }
        }
        chordPadBar.addView(reorderBtn)

        for (chord in userChords) {
            val btn = TextView(this).apply {
                text = chord.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = sysSans
                setTextColor(Color.parseColor("#f0d9b5"))
                background = makePillDrawable("#3d2c21", null, 0f, 8f)
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6f) }

                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.background = makePillDrawable("#e8a547", null, 0f, 8f)
                            (v as TextView).setTextColor(Color.parseColor("#2b1608"))
                            for (n in chord.notes) {
                                pianoView.setExternalKeyPressed(n, true)
                                triggerNoteOn(n, 0.78f)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.background = makePillDrawable("#3d2c21", null, 0f, 8f)
                            (v as TextView).setTextColor(Color.parseColor("#f0d9b5"))
                            for (n in chord.notes) {
                                pianoView.setExternalKeyPressed(n, false)
                                triggerNoteOff(n)
                            }
                        }
                    }
                    true
                }
            }
            chordPadBar.addView(btn)
        }
    }

    private fun showReorderChordsDialog() {
        val names = userChords.map { "${it.name}  (Tap to move to front)" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rearrange Chords (Tap a chord to bring to #1)")
            .setItems(names) { _, which ->
                val chosen = userChords.removeAt(which)
                userChords.add(0, chosen)
                populateChordButtons()
                saveState()
                Toast.makeText(this, "Moved ${chosen.name} to front", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset Default") { _, _ ->
                userChords.clear()
                userChords.addAll(defaultChords)
                populateChordButtons()
                saveState()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun toggleMetronome() {
        metronomeRunning = !metronomeRunning
        if (metronomeRunning) {
            metronomeBeat = 0
            metroPillText.text = "${metronomeBpm} bpm"
            metroPillText.setTextColor(Color.parseColor("#e8a547"))
            metroHandler.post(metroRunnable)
        } else {
            metroHandler.removeCallbacks(metroRunnable)
            metroPillText.text = "Metro"
            metroPillText.setTextColor(Color.parseColor("#f0d9b5"))
        }
    }

    private fun showMetronomeBpmDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(12f))
        }
        val label = TextView(this).apply {
            text = "Tempo: $metronomeBpm BPM"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val sb = SeekBar(this).apply {
            max = 180
            progress = (metronomeBpm - 40).coerceIn(0, 180)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    metronomeBpm = 40 + p
                    label.text = "Tempo: $metronomeBpm BPM"
                    if (metronomeRunning) metroPillText.text = "${metronomeBpm} bpm"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
            })
        }
        layout.addView(label)
        layout.addView(sb)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Metronome Settings")
            .setView(layout)
            .setPositiveButton("Start / Stop") { _, _ -> toggleMetronome() }
            .setNegativeButton("Close", null)
            .show()
    }

    fun triggerNoteOn(midiNote: Int, velocity: Float) {
        val clamped = midiNote.coerceIn(21, 108)
        nativeNoteOn(clamped, velocity)
        if (hapticsEnabled) {
            pianoView.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
        if (isRecording) {
            val timestamp = SystemClock.elapsedRealtime() - recordStartTime
            val velInt = (velocity * 127f).toInt().coerceIn(1, 127)
            recordedMidiEvents.add(MidiEvent(timestamp, 1, clamped, velInt))
        }
    }

    fun triggerNoteOff(midiNote: Int) {
        val clamped = midiNote.coerceIn(21, 108)
        nativeNoteOff(clamped)
        if (isRecording) {
            val timestamp = SystemClock.elapsedRealtime() - recordStartTime
            recordedMidiEvents.add(MidiEvent(timestamp, 0, clamped, 0))
        }
    }

    private fun toggleMidiRecording() {
        if (!isRecording) {
            recordedMidiEvents.clear()
            recordStartTime = SystemClock.elapsedRealtime()
            isRecording = true
            recText.text = "Stop"
            recText.setTextColor(Color.parseColor("#e5604d"))
            Toast.makeText(this, "Recording MIDI...", Toast.LENGTH_SHORT).show()
        } else {
            isRecording = false
            recText.text = "Rec"
            recText.setTextColor(Color.parseColor("#f0d9b5"))
            if (recordedMidiEvents.isNotEmpty()) {
                saveMidiFileToInternalFolder()
            } else {
                Toast.makeText(this, "No notes played", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getRecordingsDir(): File {
        val dir = File(filesDir, "velvet_midi")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveMidiFileToInternalFolder() {
        try {
            val stamp = SimpleDateFormat("MMMdd_HHmmss", Locale.US).format(Date())
            val file = File(getRecordingsDir(), "Velvet_$stamp.mid")
            FileOutputStream(file).use { out ->
                val trackData = mutableListOf<Byte>()
                var lastTime = 0L
                for (ev in recordedMidiEvents) {
                    val deltaMs = max(0L, ev.timestampMs - lastTime)
                    lastTime = ev.timestampMs
                    val deltaTicks = (deltaMs * 480L / 500L).toInt()
                    writeVarLen(trackData, deltaTicks)
                    if (ev.type == 1) {
                        trackData.add(0x90.toByte())
                        trackData.add(ev.note.toByte())
                        trackData.add(ev.velocity.toByte())
                    } else {
                        trackData.add(0x80.toByte())
                        trackData.add(ev.note.toByte())
                        trackData.add(0x00.toByte())
                    }
                }
                writeVarLen(trackData, 0)
                trackData.add(0xFF.toByte())
                trackData.add(0x2F.toByte())
                trackData.add(0x00.toByte())

                val header = byteArrayOf(
                    0x4D, 0x54, 0x68, 0x64,
                    0, 0, 0, 6,
                    0, 0,
                    0, 1,
                    0x01, 0xE0.toByte(),
                    0x4D, 0x54, 0x72, 0x6B
                )
                out.write(header)
                val len = trackData.size
                out.write(byteArrayOf(
                    ((len shr 24) and 0xFF).toByte(),
                    ((len shr 16) and 0xFF).toByte(),
                    ((len shr 8) and 0xFF).toByte(),
                    (len and 0xFF).toByte()
                ))
                out.write(trackData.toByteArray())
            }
            Toast.makeText(this, "Saved to Files: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving MIDI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeVarLen(out: MutableList<Byte>, value: Int) {
        var buffer = value and 0x7F
        var v = value shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or ((v and 0x7F) or 0x80)
            v = v shr 7
        }
        while (true) {
            out.add((buffer and 0xFF).toByte())
            if ((buffer and 0x80) != 0) buffer = buffer shr 8 else break
        }
    }

    // =========================================================================
    // IN-APP MIDI RECORDINGS FOLDER (Play with Visual Keys, Share, Delete)
    // =========================================================================
    private fun showRecordingsFolderDialog() {
        stopActiveMidiPlayback()
        val files = getRecordingsDir().listFiles { f -> f.extension == "mid" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Saved MIDI Recordings")
                .setMessage("No recordings yet. Tap '● Rec' in the top bar to sketch an idea.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Saved MIDI Recordings (${files.size})")
            .setItems(names) { _, which ->
                showSingleRecordingOptions(files[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSingleRecordingOptions(file: File) {
        val options = arrayOf(
            "▶ Play on Piano Keys",
            "📤 Share MIDI File",
            "🗑 Delete Recording"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(file.name)
            .setItems(options) { _, actionIdx ->
                when (actionIdx) {
                    0 -> playMidiFileVisuallyOnKeys(file)
                    1 -> shareMidiFile(file)
                    2 -> {
                        file.delete()
                        Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                        showRecordingsFolderDialog()
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showRecordingsFolderDialog() }
            .show()
    }

    private fun shareMidiFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.velvet.piano.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/midi"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopActiveMidiPlayback() {
        if (isPlayingMidi) {
            playbackHandler.removeCallbacksAndMessages(null)
            pianoView.clearExternalKeys()
            for (n in 21..108) nativeNoteOff(n)
            isPlayingMidi = false
        }
    }

    private fun playMidiFileVisuallyOnKeys(file: File) {
        stopActiveMidiPlayback()
        val events = parseStandardMidiFile(file)
        if (events.isEmpty()) {
            Toast.makeText(this, "Empty MIDI file", Toast.LENGTH_SHORT).show()
            return
        }
        isPlayingMidi = true
        Toast.makeText(this, "Playing ${file.name}...", Toast.LENGTH_SHORT).show()

        for (ev in events) {
            playbackHandler.postDelayed({
                if (ev.type == 1 && ev.velocity > 0) {
                    pianoView.setExternalKeyPressed(ev.note, true)
                    nativeNoteOn(ev.note, ev.velocity / 127f)
                } else {
                    pianoView.setExternalKeyPressed(ev.note, false)
                    nativeNoteOff(ev.note)
                }
            }, ev.timestampMs)
        }
        val endMs = (events.lastOrNull()?.timestampMs ?: 0L) + 400L
        playbackHandler.postDelayed({
            stopActiveMidiPlayback()
        }, endMs)
    }

    private fun parseStandardMidiFile(file: File): List<MidiEvent> {
        val result = mutableListOf<MidiEvent>()
        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            var idx = 0
            // Locate "MTrk" chunk
            while (idx < bytes.size - 8) {
                if (bytes[idx] == 0x4D.toByte() && bytes[idx + 1] == 0x54.toByte() &&
                    bytes[idx + 2] == 0x72.toByte() && bytes[idx + 3] == 0x6B.toByte()) {
                    idx += 8
                    break
                }
                idx++
            }
            var currentMs = 0L
            while (idx < bytes.size) {
                var deltaTicks = 0
                while (idx < bytes.size) {
                    val b = bytes[idx++].toInt() and 0xFF
                    deltaTicks = (deltaTicks shl 7) or (b and 0x7F)
                    if ((b and 0x80) == 0) break
                }
                currentMs += (deltaTicks * 500L) / 480L
                if (idx >= bytes.size) break
                val status = bytes[idx++].toInt() and 0xFF
                if (status == 0xFF) {
                    if (idx >= bytes.size) break
                    idx++ // meta type
                    val len = bytes[idx++].toInt() and 0xFF
                    idx += len
                } else if ((status and 0xF0) == 0x90) {
                    if (idx + 1 >= bytes.size) break
                    val note = bytes[idx++].toInt() and 0x7F
                    val vel = bytes[idx++].toInt() and 0x7F
                    result.add(MidiEvent(currentMs, if (vel > 0) 1 else 0, note, vel))
                } else if ((status and 0xF0) == 0x80) {
                    if (idx + 1 >= bytes.size) break
                    val note = bytes[idx++].toInt() and 0x7F
                    idx++
                    result.add(MidiEvent(currentMs, 0, note, 0))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun showInstrumentDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Studio Instrument")
            .setSingleChoiceItems(instruments, currentInstrument) { dialog, which ->
                currentInstrument = which
                presetTextView.text = instruments[which]
                nativeSetInstrument(which)
                saveState()
                dialog.dismiss()
            }
            .show()
    }

    private fun showSoundSettingsDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        scroll.addView(layout)

        fun addSlider(label: String, initial: Float, minVal: Float, maxVal: Float, onChange: (Float) -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            val sb = SeekBar(this).apply {
                max = 100
                progress = ((initial - minVal) / (maxVal - minVal) * 100f).toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = minVal + (p / 100f) * (maxVal - minVal)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
                })
            }
            layout.addView(tv)
            layout.addView(sb)
        }

        // Key Width / Visible Keys Slider
        val keysLabel = TextView(this).apply {
            text = "Visible White Keys Across Screen: ${visibleWhiteKeysCount.toInt()}"
            setTextColor(Color.parseColor("#e8a547"))
            textSize = 13f
        }
        val keysSlider = SeekBar(this).apply {
            max = 16
            progress = (visibleWhiteKeysCount.toInt() - 6).coerceIn(0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    visibleWhiteKeysCount = (6 + p).toFloat()
                    keysLabel.text = "Visible White Keys Across Screen: ${visibleWhiteKeysCount.toInt()}"
                    pianoView.updateKeyWidthFromCount(visibleWhiteKeysCount)
                    overviewStripView.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
            })
        }
        layout.addView(keysLabel)
        layout.addView(keysSlider)

        addSlider("Master Volume", volumeLevel, 0.1f, 1.4f) {
            volumeLevel = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
        addSlider("Acoustic Hall Reverb", reverbLevel, 0.0f, 0.85f) {
            reverbLevel = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
        addSlider("Room Size & Resonance Decay", roomSize, 0.1f, 0.98f) {
            roomSize = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
        addSlider("Stereo Imaging Width", stereoWidth, 0.5f, 1.8f) {
            stereoWidth = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }

        // Black-Keys-Only Upper Zone Toggle
        val blackUpperCheck = CheckBox(this).apply {
            text = "Upper Zone Plays Black Keys Only (Easy Sharps/Flats)"
            setTextColor(Color.parseColor("#e8a547"))
            isChecked = blackKeysOnlyUpperZone
            setOnCheckedChangeListener { _, checked ->
                blackKeysOnlyUpperZone = checked
                saveState()
            }
        }
        layout.addView(blackUpperCheck)

        val labelsCheck = CheckBox(this).apply {
            text = "Show Note & Octave Labels"
            setTextColor(Color.WHITE)
            isChecked = showNoteLabels
            setOnCheckedChangeListener { _, checked ->
                showNoteLabels = checked
                pianoView.showLabels = checked
                pianoView.invalidate()
                saveState()
            }
        }
        layout.addView(labelsCheck)

        val hapticsCheck = CheckBox(this).apply {
            text = "Tactile Key Haptics"
            setTextColor(Color.WHITE)
            isChecked = hapticsEnabled
            setOnCheckedChangeListener { _, checked ->
                hapticsEnabled = checked
                saveState()
            }
        }
        layout.addView(hapticsCheck)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sound & Keyboard Setup")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        stopActiveMidiPlayback()
        prefs.edit()
            .putFloat("scroll_offset", pianoView.keyScrollOffset)
            .apply()
        saveState()
    }
}

// =============================================================================
// CUSTOM CANVAS ICONS (Music Note 16dp & Decorative 35°-Rotated Knob 24dp)
// =============================================================================
class MusicNoteIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2b1608")
        style = Paint.Style.FILL
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // Note heads
        canvas.drawOval(RectF(w * 0.12f, h * 0.62f, w * 0.44f, h * 0.88f), paint)
        canvas.drawOval(RectF(w * 0.54f, h * 0.54f, w * 0.86f, h * 0.80f), paint)
        // Stems & top beam
        canvas.drawLine(w * 0.40f, h * 0.72f, w * 0.40f, h * 0.18f, paint)
        canvas.drawLine(w * 0.82f, h * 0.64f, w * 0.82f, h * 0.12f, paint)
        canvas.drawLine(w * 0.40f, h * 0.18f, w * 0.82f, h * 0.12f, paint)
    }
}

class KnobIconView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2b1e17")
        style = Paint.Style.FILL
    }
    private val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6b4a33")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e8a547")
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (width / 2f) - d
        canvas.drawCircle(cx, cy, r, circleFill)
        canvas.drawCircle(cx, cy, r, circleStroke)

        canvas.save()
        canvas.rotate(35f, cx, cy)
        val nw = 2f * d
        val nh = 8f * d
        canvas.drawRoundRect(
            RectF(cx - nw / 2f, cy - r + 2f * d, cx + nw / 2f, cy - r + 2f * d + nh),
            nw / 2f, nw / 2f, needlePaint
        )
        canvas.restore()
    }
}

// =============================================================================
// 2. OVERVIEW STRIP VIEW (Track 40dp tall, radius 9, #14100d, ticks every 12dp)
// =============================================================================
class OverviewStripView(context: Context, private val host: MainActivity) : View(context) {
    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14100d")
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3a2a20")
        style = Paint.Style.FILL
    }
    private val windowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(51, 232, 165, 71) // rgba(232,165,71, 0.20)
        style = Paint.Style.FILL
    }
    private val windowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e8a547")
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            host.visibleWhiteKeysCount = (host.visibleWhiteKeysCount / detector.scaleFactor).coerceIn(6f, 22f)
            host.pianoView.updateKeyWidthFromCount(host.visibleWhiteKeysCount)
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r9 = 9f * d

        // Track background (#14100d, radius 9)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r9, r9, trackPaint)

        // Dense vertical ticks: 4dp wide #3a2a20 every 12dp
        val tickW = 4f * d
        val step = 12f * d
        var x = 6f * d
        while (x + tickW <= w - 4f * d) {
            canvas.drawRect(x, 5f * d, x + tickW, h - 5f * d, tickPaint)
            x += step
        }

        // Highlighted window bound to real visible key range (3dp border #e8a547, 20% amber fill, radius 8, no center line)
        val totalPianoW = host.pianoView.getTotalPianoWidth()
        val visiblePianoW = host.pianoView.getVisibleKeyboardWidth()
        if (totalPianoW > 0f) {
            val startFrac = (host.pianoView.keyScrollOffset / totalPianoW).coerceIn(0f, 1f)
            val spanFrac = (visiblePianoW / totalPianoW).coerceIn(0.05f, 1f)
            val halfStroke = 1.5f * d
            val left = (startFrac * w).coerceAtLeast(halfStroke)
            val right = ((startFrac + spanFrac) * w).coerceAtMost(w - halfStroke)
            val winRect = RectF(left, halfStroke, right, h - halfStroke)
            val r8 = 8f * d
            canvas.drawRoundRect(winRect, r8, r8, windowFillPaint)
            canvas.drawRoundRect(winRect, r8, r8, windowBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val frac = (event.x / width.toFloat()).coerceIn(0f, 1f)
                    host.pianoView.scrollToFraction(frac)
                    invalidate()
                }
            }
        }
        return true
    }
}

// =============================================================================
// 3. KEYBOARD VIEW (Exact Padding 8 10 10, Full-Key Press Colors & Upper Zone)
// =============================================================================
class VelvetKeyboardView(context: Context, private val host: MainActivity) : View(context) {

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val whiteNotes = mutableListOf<Int>()
    private val allBlackNotes = mutableListOf<Int>()
    private val allKeyRects = mutableMapOf<Int, RectF>()
    private val pointerToNote = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()
    private val externalActiveNotes = mutableSetOf<Int>()

    var whiteKeyW = -1f
    var keyScrollOffset = -1f
    var showLabels = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1b1310") }
    private val whiteKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f1e6d0") }
    private val whiteKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e8a547") }
    private val blackKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#20160f") }
    private val blackKeyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3b2a1e") }
    private val blackKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6b4a1f") }
    private val blackKeyPressedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e8a547") }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init {
        for (midi in 21..108) {
            if (isBlackKey(midi)) allBlackNotes.add(midi) else whiteNotes.add(midi)
        }
    }

    private fun isBlackKey(midi: Int): Boolean {
        val m = midi % 12
        return m == 1 || m == 3 || m == 6 || m == 8 || m == 10
    }

    fun getVisibleKeyboardWidth(): Float = (width - 20f * d).coerceAtLeast(1f)
    fun getTotalPianoWidth(): Float = whiteNotes.size * whiteKeyW

    fun updateKeyWidthFromCount(count: Float) {
        val availW = getVisibleKeyboardWidth()
        if (availW > 0f) {
            val centerRatio = if (getTotalPianoWidth() > 0f) (keyScrollOffset + availW / 2f) / getTotalPianoWidth() else 0.45f
            whiteKeyW = availW / count.coerceIn(6f, 22f)
            keyScrollOffset = centerRatio * getTotalPianoWidth() - availW / 2f
            clampScroll()
            invalidate()
        }
    }

    fun scrollToFraction(fraction: Float) {
        val totalW = getTotalPianoWidth()
        val availW = getVisibleKeyboardWidth()
        keyScrollOffset = fraction * totalW - (availW / 2f)
        clampScroll()
        invalidate()
    }

    fun setExternalKeyPressed(midiNote: Int, pressed: Boolean) {
        if (pressed) externalActiveNotes.add(midiNote) else externalActiveNotes.remove(midiNote)
        invalidate()
    }

    fun clearExternalKeys() {
        externalActiveNotes.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availW = getVisibleKeyboardWidth()
        whiteKeyW = availW / host.visibleWhiteKeysCount.coerceIn(6f, 22f)
        if (keyScrollOffset < 0f) {
            val c3Index = whiteNotes.indexOf(48).coerceAtLeast(0)
            keyScrollOffset = c3Index * whiteKeyW
        }
        clampScroll()
        host.overviewStripView.invalidate()
    }

    private fun clampScroll() {
        val totalWidth = getTotalPianoWidth()
        val maxScroll = max(0f, totalWidth - getVisibleKeyboardWidth())
        keyScrollOffset = keyScrollOffset.coerceIn(0f, maxScroll)
    }

    private fun drawBottomRoundedRect(canvas: Canvas, rect: RectF, bottomRadius: Float, paint: Paint) {
        val radii = floatArrayOf(0f, 0f, 0f, 0f, bottomRadius, bottomRadius, bottomRadius, bottomRadius)
        val path = Path()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Keyboard padding: top 8dp, left 10dp, right 10dp, bottom 10dp
        val padLeft = 10f * d
        val padRight = width.toFloat() - 10f * d
        val keyTop = 8f * d
        val keyBottom = height.toFloat() - 10f * d
        val keyboardHeight = keyBottom - keyTop

        val blackKeyHeight = keyboardHeight * 0.62f
        val blackKeyWidth = whiteKeyW * 0.60f
        val halfGap = 1f * d // 2dp gap between white keys
        val whiteCornerRadius = 8f * d
        val blackCornerRadius = 5f * d
        val blackBottomBorderH = 5f * d

        allKeyRects.clear()

        canvas.save()
        canvas.clipRect(padLeft, keyTop, padRight, keyBottom)

        // 1. Draw White Keys
        for (i in whiteNotes.indices) {
            val midi = whiteNotes[i]
            val left = padLeft + i * whiteKeyW - keyScrollOffset + halfGap
            val right = padLeft + (i + 1) * whiteKeyW - keyScrollOffset - halfGap
            if (right < padLeft || left > padRight) continue

            val pressed = activeNotes.contains(midi) || externalActiveNotes.contains(midi)
            val rect = RectF(left, keyTop, right, keyBottom)
            allKeyRects[midi] = rect

            // Entire white key changes to #e8a547 when pressed, #f1e6d0 when idle
            drawBottomRoundedRect(canvas, rect, whiteCornerRadius, if (pressed) whiteKeyPressedPaint else whiteKeyPaint)

            // Note label centered 10dp above bottom, 13sp medium, #8a6b4a (or #3a2210 when pressed)
            if (showLabels && (midi % 12 == 0 || whiteKeyW > 70f * d)) {
                val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                val label = if (midi % 12 == 0) "C${(midi / 12) - 1}" else noteNames[midi % 12]
                labelPaint.color = if (pressed) Color.parseColor("#3a2210") else Color.parseColor("#8a6b4a")
                canvas.drawText(label, rect.centerX(), rect.bottom - 10f * d, labelPaint)
            }
        }

        // 2. Draw Black Keys (60% width, 62% height, centered on white key boundary)
        for (i in whiteNotes.indices) {
            val whiteMidi = whiteNotes[i]
            val blackMidi = whiteMidi + 1
            if (blackMidi <= 108 && isBlackKey(blackMidi)) {
                val boundaryX = padLeft + (i + 1) * whiteKeyW - keyScrollOffset
                val left = boundaryX - blackKeyWidth / 2f
                val right = boundaryX + blackKeyWidth / 2f
                if (right < padLeft || left > padRight) continue

                val pressed = activeNotes.contains(blackMidi) || externalActiveNotes.contains(blackMidi)
                val fullRect = RectF(left, keyTop, right, keyTop + blackKeyHeight)
                allKeyRects[blackMidi] = fullRect

                // Draw 5dp bottom border layer first (#3b2a1e idle, #e8a547 pressed)
                drawBottomRoundedRect(
                    canvas,
                    fullRect,
                    blackCornerRadius,
                    if (pressed) blackKeyPressedBorderPaint else blackKeyBorderPaint
                )

                // Draw main black key body above the 5dp bottom border (#20160f idle, #6b4a1f pressed)
                val bodyRect = RectF(left, keyTop, right, keyTop + blackKeyHeight - blackBottomBorderH)
                drawBottomRoundedRect(
                    canvas,
                    bodyRect,
                    blackCornerRadius * 0.7f,
                    if (pressed) blackKeyPressedPaint else blackKeyPaint
                )
            }
        }

        canvas.restore()
    }

    private fun findMidiAtPoint(x: Float, y: Float): Pair<Int, Float>? {
        val keyTop = 8f * d
        val keyBottom = height.toFloat() - 10f * d
        val blackKeyEndY = keyTop + (keyBottom - keyTop) * 0.62f

        // Special Mode: Upper Zone Plays Black Keys Only
        if (host.blackKeysOnlyUpperZone && y <= blackKeyEndY) {
            var nearestBlackMidi = -1
            var minDistance = Float.MAX_VALUE
            var targetRect: RectF? = null
            for (entry in allKeyRects.entries) {
                if (isBlackKey(entry.key)) {
                    val dist = abs(x - entry.value.centerX())
                    if (dist < minDistance) {
                        minDistance = dist
                        nearestBlackMidi = entry.key
                        targetRect = entry.value
                    }
                }
            }
            if (nearestBlackMidi != -1 && targetRect != null && minDistance < whiteKeyW * 1.2f) {
                val relY = ((y - targetRect.top) / targetRect.height()).coerceIn(0.25f, 1.0f)
                return Pair(nearestBlackMidi, 0.45f + relY * 0.55f)
            }
            return null
        }

        // Standard hit detection: Black keys first, then White keys
        for (entry in allKeyRects.entries) {
            if (isBlackKey(entry.key) && entry.value.contains(x, y)) {
                val relY = ((y - entry.value.top) / entry.value.height()).coerceIn(0.25f, 1.0f)
                return Pair(entry.key, 0.42f + (relY * 0.58f))
            }
        }
        for (entry in allKeyRects.entries) {
            if (!isBlackKey(entry.key) && entry.value.contains(x, y)) {
                val relY = ((y - entry.value.top) / entry.value.height()).coerceIn(0.25f, 1.0f)
                return Pair(entry.key, 0.38f + (relY * 0.62f))
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val hit = findMidiAtPoint(event.getX(actionIndex), event.getY(actionIndex))
                if (hit != null) {
                    pointerToNote[pointerId] = hit.first
                    pressKey(hit.first, hit.second)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val hit = findMidiAtPoint(event.getX(i), event.getY(i))
                    val prevNote = pointerToNote[pid]
                    val newNote = hit?.first
                    if (prevNote != newNote) {
                        if (prevNote != null) {
                            pointerToNote.remove(pid)
                            if (!pointerToNote.containsValue(prevNote)) releaseKey(prevNote)
                        }
                        if (newNote != null && hit != null) {
                            pointerToNote[pid] = newNote
                            pressKey(newNote, hit.second)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val prevNote = pointerToNote.remove(pointerId)
                if (prevNote != null && !pointerToNote.containsValue(prevNote)) {
                    releaseKey(prevNote)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerToNote.clear()
                val copy = activeNotes.toList()
                activeNotes.clear()
                for (n in copy) host.triggerNoteOff(n)
                invalidate()
            }
        }
        return true
    }

    private fun pressKey(midiNote: Int, velocity: Float) {
        activeNotes.add(midiNote)
        host.triggerNoteOn(midiNote, velocity)
        invalidate()
    }

    private fun releaseKey(midiNote: Int) {
        activeNotes.remove(midiNote)
        host.triggerNoteOff(midiNote)
        invalidate()
    }
}

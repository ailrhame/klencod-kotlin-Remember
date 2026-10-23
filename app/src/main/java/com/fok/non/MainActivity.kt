package com.fok.non

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.UUID

private val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

data class WordEntry(
    val id: String,
    var word: String,
    var meaning: String,
    val createdAt: Long
)

object Store {
    private const val PREFS = "remember_prefs_v1"
    private const val KEY_ENTRIES = "entries"
    private const val DELIM = "\u001F"
    private const val REC_SEP = "\u001E"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<WordEntry> {
        val raw = prefs(ctx).getString(KEY_ENTRIES, "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        val out = mutableListOf<WordEntry>()
        for (rec in raw.split(REC_SEP)) {
            if (rec.isEmpty()) continue
            val p = rec.split(DELIM)
            if (p.size < 4) continue
            try { out.add(WordEntry(p[0], p[1], p[2], p[3].toLong())) } catch (_: Exception) {}
        }
        return out
    }

    fun saveAll(ctx: Context, list: List<WordEntry>) {
        val sb = StringBuilder()
        for ((i, e) in list.withIndex()) {
            if (i > 0) sb.append(REC_SEP)
            sb.append(e.id).append(DELIM).append(e.word).append(DELIM)
                .append(e.meaning).append(DELIM).append(e.createdAt)
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, sb.toString()).apply()
    }

    fun add(ctx: Context, word: String, meaning: String): WordEntry {
        val list = all(ctx)
        val e = WordEntry(UUID.randomUUID().toString(), word, meaning, System.currentTimeMillis())
        list.add(e)
        saveAll(ctx, list)
        return e
    }

    fun update(ctx: Context, id: String, word: String, meaning: String) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx].word = word
            list[idx].meaning = meaning
            saveAll(ctx, list)
        }
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, all(ctx).filterNot { it.id == id })
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ENTRIES).apply()
    }

    fun speakOnUnlock(ctx: Context): Boolean = prefs(ctx).getBoolean("speak_on_unlock", true)
    fun setSpeakOnUnlock(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("speak_on_unlock", v).apply()
    }

    fun pitch(ctx: Context): Float = prefs(ctx).getFloat("pitch", 1.0f)
    fun setPitch(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("pitch", v).apply() }

    fun speed(ctx: Context): Float = prefs(ctx).getFloat("speed", 1.0f)
    fun setSpeed(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("speed", v).apply() }

    fun volume(ctx: Context): Float = prefs(ctx).getFloat("volume", 1.0f)
    fun setVolume(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("volume", v).apply() }

    fun repeat(ctx: Context): Int = prefs(ctx).getInt("repeat", 3)
    fun setRepeat(ctx: Context, v: Int) { prefs(ctx).edit().putInt("repeat", v).apply() }

    fun lang(ctx: Context): String = prefs(ctx).getString("lang", "") ?: ""
    fun setLang(ctx: Context, v: String) { prefs(ctx).edit().putString("lang", v).apply() }
}

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var searchBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E0E14.toInt()
        window.navigationBarColor = 0xFF0E0E14.toInt()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        buildMainUI()
        ensureServiceState()
    }

    private fun ensureServiceState() {
        if (Store.speakOnUnlock(this)) startSpeakService() else stopSpeakService()
    }

    private fun startSpeakService() {
        val i = Intent(this, SpeakService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun stopSpeakService() {
        try { stopService(Intent(this, SpeakService::class.java)) } catch (_: Exception) {}
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeWidthDp: Float = 0f): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != 0) d.setStroke(dp(strokeWidthDp), strokeColor)
        return d
    }

    private fun buildMainUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0E0E14.toInt()) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(16f), dp(12f), dp(16f), dp(16f))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Remember"
            setTextColor(0xFFD4AF37.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val settingsBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            background = null
            setColorFilter(0xFFD4AF37.toInt())
            setOnClickListener { showSettings() }
        }
        topBar.addView(title)
        topBar.addView(settingsBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        content.addView(topBar)

        searchBar = EditText(this).apply {
            hint = "بحث..."
            setHintTextColor(0xFF8B8B99.toInt())
            setTextColor(0xFFF0EAD6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = bg(0xFF1A1A24.toInt(), 12f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { refreshList() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        content.addView(searchBar, LinearLayout.LayoutParams(MATCH, dp(48f)).also { it.topMargin = dp(12f) })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).also { it.topMargin = dp(12f) }
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        }
        emptyView = TextView(this).apply {
            text = "لا كلمات بعد\n\nاضغط + لإضافة أول كلمة"
            setTextColor(0xFF8B8B99.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(80f) }
        }
        listContainer.addView(emptyView)
        scroll.addView(listContainer)
        content.addView(scroll)

        root.addView(content)

        val fab = TextView(this).apply {
            text = "+"
            setTextColor(0xFF0E0E14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = bg(0xFFD4AF37.toInt(), 32f)
            layoutParams = FrameLayout.LayoutParams(dp(64f), dp(64f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(20f), dp(20f))
            }
            setOnClickListener { showAddDialog() }
            elevation = dp(8f).toFloat()
        }
        root.addView(fab)

        setContentView(root)
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        listContainer.addView(emptyView)
        val q = searchBar.text.toString().trim().lowercase()
        val all = Store.all(this)
        val filtered = if (q.isEmpty()) all else all.filter {
            it.word.lowercase().contains(q) || it.meaning.lowercase().contains(q)
        }.toMutableList()
        filtered.sortByDescending { it.createdAt }
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        for (e in filtered) listContainer.addView(makeCard(e))
    }

    private fun makeCard(e: WordEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xFF1A1A24.toInt(), 14f, 0xFF2A2A36.toInt(), 1f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(10f) }
            isClickable = true
            isFocusable = true
        }
        val word = TextView(this).apply {
            text = e.word
            setTextColor(0xFFF0EAD6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        val meaning = TextView(this).apply {
            text = e.meaning
            setTextColor(0xFF8B8B99.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6f), 0, 0)
        }
        card.addView(word)
        card.addView(meaning)
        card.setOnClickListener { speakAdhoc(e.word, e.meaning) }
        card.setOnLongClickListener { showEntryMenu(e); true }
        return card
    }

    private fun showAddDialog() {
        val ctx = this
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        val wordIn = EditText(ctx).apply {
            hint = "النطق"
            setHintTextColor(0xFF8B8B99.toInt())
            setTextColor(0xFFF0EAD6.toInt())
            background = bg(0xFF1A1A24.toInt(), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val meanIn = EditText(ctx).apply {
            hint = "المعنى"
            setHintTextColor(0xFF8B8B99.toInt())
            setTextColor(0xFFF0EAD6.toInt())
            background = bg(0xFF1A1A24.toInt(), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        ll.addView(wordIn, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(meanIn, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10f) })

        AlertDialog.Builder(ctx)
            .setTitle("إضافة كلمة")
            .setView(ll)
            .setPositiveButton("حفظ") { _, _ ->
                val w = wordIn.text.toString().trim()
                val m = meanIn.text.toString().trim()
                if (w.isEmpty() || m.isEmpty()) {
                    Toast.makeText(ctx, "الحقول فارغة", Toast.LENGTH_SHORT).show()
                } else {
                    Store.add(ctx, w, m)
                    refreshList()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEditDialog(e: WordEntry) {
        val ctx = this
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        val wordIn = EditText(ctx).apply {
            hint = "النطق"
            setText(e.word)
            setHintTextColor(0xFF8B8B99.toInt())
            setTextColor(0xFFF0EAD6.toInt())
            background = bg(0xFF1A1A24.toInt(), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val meanIn = EditText(ctx).apply {
            hint = "المعنى"
            setText(e.meaning)
            setHintTextColor(0xFF8B8B99.toInt())
            setTextColor(0xFFF0EAD6.toInt())
            background = bg(0xFF1A1A24.toInt(), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        ll.addView(wordIn, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(meanIn, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10f) })

        AlertDialog.Builder(ctx)
            .setTitle("تعديل")
            .setView(ll)
            .setPositiveButton("حفظ") { _, _ ->
                val w = wordIn.text.toString().trim()
                val m = meanIn.text.toString().trim()
                if (w.isNotEmpty() && m.isNotEmpty()) {
                    Store.update(ctx, e.id, w, m)
                    refreshList()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEntryMenu(e: WordEntry) {
        val options = arrayOf("نطق", "تعديل", "نسخ", "حذف")
        AlertDialog.Builder(this)
            .setTitle(e.word)
            .setItems(options) { _, w ->
                when (w) {
                    0 -> speakAdhoc(e.word, e.meaning)
                    1 -> showEditDialog(e)
                    2 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("remember", "${e.word} = ${e.meaning}"))
                        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("تأكيد")
                            .setMessage("حذف \"${e.word}\"؟")
                            .setPositiveButton("حذف") { _, _ ->
                                Store.delete(this, e.id)
                                refreshList()
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun speakAdhoc(word: String, meaning: String) {
        val i = Intent(this, SpeakService::class.java)
        i.putExtra("word", word)
        i.putExtra("meaning", meaning)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (ex: Exception) {
            try { startService(i) } catch (_: Exception) {
                Toast.makeText(this, "خطأ: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSettings() {
        val ctx = this
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        }

        val sw = Switch(ctx).apply {
            text = "النطق عند فتح القفل"
            setTextColor(0xFFF0EAD6.toInt())
            isChecked = Store.speakOnUnlock(ctx)
            setOnCheckedChangeListener { _, b ->
                Store.setSpeakOnUnlock(ctx, b)
                if (b) startSpeakService() else stopSpeakService()
            }
            setPadding(0, dp(8f), 0, dp(8f))
        }
        ll.addView(sw)

        ll.addView(seekRow(ctx, "نبرة الصوت", 0.5f, 2.0f, Store.pitch(ctx), 15) { Store.setPitch(ctx, it) })
        ll.addView(seekRow(ctx, "سرعة الصوت", 0.5f, 2.0f, Store.speed(ctx), 15) { Store.setSpeed(ctx, it) })
        ll.addView(seekRow(ctx, "قوة الصوت", 0f, 1f, Store.volume(ctx), 10) { Store.setVolume(ctx, it) })
        ll.addView(seekRow(ctx, "عدد التكرار", 1f, 10f, Store.repeat(ctx).toFloat(), 9) { Store.setRepeat(ctx, it.toInt()) })

        val langRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val langLbl = TextView(ctx).apply {
            text = "اللغة: " + langName(Store.lang(ctx))
            setTextColor(0xFFF0EAD6.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val langBtn = Button(ctx).apply {
            text = "تغيير"
            setOnClickListener {
                val langs = listOf(
                    "" to "تلقائي", "ar" to "العربية", "en" to "English",
                    "fr" to "Français", "es" to "Español", "de" to "Deutsch",
                    "tr" to "Türkçe", "ur" to "اردو", "fa" to "فارسی"
                )
                val codes = langs.map { it.first }.toTypedArray()
                val names = langs.map { it.second }.toTypedArray()
                val curIdx = codes.indexOf(Store.lang(ctx)).coerceAtLeast(0)
                AlertDialog.Builder(ctx)
                    .setTitle("اختر اللغة")
                    .setSingleChoiceItems(names, curIdx) { d, w ->
                        Store.setLang(ctx, codes[w])
                        langLbl.text = "اللغة: " + names[w]
                        d.dismiss()
                    }
                    .show()
            }
        }
        langRow.addView(langLbl)
        langRow.addView(langBtn)
        ll.addView(langRow)

        val testBtn = Button(ctx).apply {
            text = "تجربة الإعدادات"
            setOnClickListener { speakAdhoc("اختبار", "Test OK") }
        }
        ll.addView(testBtn, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(10f) })

        val batteryBtn = Button(ctx).apply {
            text = "إلغاء تحسين البطارية (مهم للاستقرار)"
            setOnClickListener {
                try {
                    val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "غير متاح على هذا الجهاز", Toast.LENGTH_SHORT).show()
                }
            }
        }
        ll.addView(batteryBtn, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8f) })

        val clearBtn = Button(ctx).apply {
            text = "حذف كل الكلمات"
            setTextColor(0xFFE05555.toInt())
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("تأكيد")
                    .setMessage("سيتم حذف جميع الكلمات")
                    .setPositiveButton("حذف") { _, _ ->
                        Store.clearAll(ctx)
                        refreshList()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
        ll.addView(clearBtn, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8f) })

        val scroll = ScrollView(ctx).apply { addView(ll) }
        AlertDialog.Builder(ctx)
            .setTitle("الإعدادات")
            .setView(scroll)
            .setPositiveButton("تم", null)
            .show()
    }

    private fun seekRow(
        ctx: Context, label: String, minV: Float, maxV: Float,
        current: Float, steps: Int, onChange: (Float) -> Unit
    ): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(4f))
        }
        val lbl = TextView(ctx).apply {
            setTextColor(0xFFF0EAD6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "$label: " + formatNum(current, steps)
        }
        val sb = SeekBar(ctx).apply {
            max = steps
            progress = (((current - minV) / (maxV - minV)) * steps).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = minV + (p.toFloat() / steps) * (maxV - minV)
                    onChange(v)
                    lbl.text = "$label: " + formatNum(v, steps)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(lbl)
        col.addView(sb)
        return col
    }

    private fun formatNum(v: Float, steps: Int): String =
        if (steps <= 9) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.1f", v)

    private fun langName(code: String): String = when (code) {
        "" -> "تلقائي"
        "ar" -> "العربية"
        "en" -> "English"
        "fr" -> "Français"
        "es" -> "Español"
        "de" -> "Deutsch"
        "tr" -> "Türkçe"
        "ur" -> "اردو"
        "fa" -> "فارسی"
        else -> code
    }
}

class SpeakService : Service(), TextToSpeech.OnInitListener {
    companion object {
        private const val CHANNEL_ID = "remember_speak"
        private const val NOTIF_ID = 6767
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile
    private var sessionId = 0

    private var pendingEntry: WordEntry? = null
    private var pendingStopAfter = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            if (!Store.speakOnUnlock(this@SpeakService)) return
            val list = Store.all(this@SpeakService)
            if (list.isEmpty()) return
            speak(list.random(), stopAfter = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (_: Exception) {
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }

        try {
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (_: Exception) {}

        tts = TextToSpeech(this, this).apply {
            try {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val w = intent?.getStringExtra("word")
        val m = intent?.getStringExtra("meaning")
        if (w != null && m != null) {
            val entry = WordEntry("adhoc", w, m, 0L)
            val stopAfter = !Store.speakOnUnlock(this)
            speak(entry, stopAfter = stopAfter)
        }
        return if (Store.speakOnUnlock(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS && tts != null
        if (ttsReady) {
            val e = pendingEntry
            val s = pendingStopAfter
            pendingEntry = null
            pendingStopAfter = false
            if (e != null) speak(e, s)
        }
    }

    private fun speak(e: WordEntry, stopAfter: Boolean) {
        if (!ttsReady) {
            pendingEntry = e
            pendingStopAfter = stopAfter
            return
        }
        val t = tts ?: return

        val mySession = ++sessionId
        val repeat = Store.repeat(this).coerceAtLeast(1)
        val total = repeat * 2
        var done = 0

        try { t.setPitch(Store.pitch(this)); t.setSpeechRate(Store.speed(this)) } catch (_: Exception) {}

        val lang = Store.lang(this)
        try {
            if (lang.isNotEmpty()) {
                val r = t.setLanguage(Locale.forLanguageTag(lang))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.getDefault())
                }
            } else {
                t.setLanguage(Locale.getDefault())
            }
        } catch (_: Exception) {
            try { t.setLanguage(Locale.getDefault()) } catch (_: Exception) {}
        }

        val vol = Store.volume(this).coerceIn(0f, 1f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)
        }

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (mySession != sessionId) return
                done++
                if (done >= total && stopAfter) stopSelf()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (mySession != sessionId) return
                done++
                if (done >= total && stopAfter) stopSelf()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (mySession != sessionId) return
                done++
                if (done >= total && stopAfter) stopSelf()
            }
        }
        t.setOnUtteranceProgressListener(listener)

        var id = 0
        for (i in 0 until repeat) {
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(e.word, mode, params, "u${id++}")
        }
        for (i in 0 until repeat) {
            t.speak(e.meaning, TextToSpeech.QUEUE_ADD, params, "u${id++}")
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Remember")
            .setContentText("جاهز للنطق عند فتح القفل")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(contentIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) {
            try { builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) } catch (_: Exception) {}
        }
        return builder.build()
    }

    private fun createChannel() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Remember Speech", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        } catch (_: Exception) {}
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.speakOnUnlock(context)) return
        val svc = Intent(context, SpeakService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
        } catch (_: Exception) {
            try { context.startService(svc) } catch (_: Exception) {}
        }
    }
}
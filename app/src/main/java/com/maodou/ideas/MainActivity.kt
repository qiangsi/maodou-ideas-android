package com.maodou.ideas

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    // Backend endpoint. sharecode is required by the hosting gateway on every request.
    private val base = "https://wbapp-gesugud1oymreklydekjnm.app.workbuddy.host"
    private val share = "sharecode=OJH77TM527"
    // Access code for reading / editing ideas. Must match the server's MAODOU_CODE.
    private val accessCode = "maodou2026"
    private val audioCacheMax = 24 * 1024 * 1024

    private lateinit var tabText: TextView
    private lateinit var tabVoice: TextView
    private lateinit var panelText: View
    private lateinit var panelVoice: View
    private lateinit var textInput: EditText
    private lateinit var charCount: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var submitText: Button
    private lateinit var wave: TextView
    private lateinit var timer: TextView
    private lateinit var voiceTip: TextView
    private lateinit var recBtn: Button
    private lateinit var voiceNote: EditText
    private lateinit var asrText: EditText
    private lateinit var copyAsr: Button
    private lateinit var submitVoice: Button
    private lateinit var ideaList: LinearLayout
    private lateinit var ideaCount: TextView
    private lateinit var myUidView: TextView
    private lateinit var queryUid: EditText
    private lateinit var queryResult: TextView
    private var myUid = ""

    private var selectedTag = "产品建议"
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var recordFile = ""
    private var pendingAudioBase64: String? = null
    private var pendingDuration = ""
    private var ticker: CountDownTimer? = null
    private var seconds = 0
    private var mediaPlayer: MediaPlayer? = null
    private var speech: SpeechRecognizer? = null
    private var asrFinal = StringBuilder()
    private var asrSupported = false
    private val main = Handler(Looper.getMainLooper())

    private val REQUEST_RECORD = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabText = findViewById(R.id.tabText)
        tabVoice = findViewById(R.id.tabVoice)
        panelText = findViewById(R.id.panelText)
        panelVoice = findViewById(R.id.panelVoice)
        textInput = findViewById(R.id.textInput)
        charCount = findViewById(R.id.charCount)
        chipRow = findViewById(R.id.chipRow)
        submitText = findViewById(R.id.submitText)
        wave = findViewById(R.id.wave)
        timer = findViewById(R.id.timer)
        voiceTip = findViewById(R.id.voiceTip)
        recBtn = findViewById(R.id.recBtn)
        voiceNote = findViewById(R.id.voiceNote)
        asrText = findViewById(R.id.asrText)
        copyAsr = findViewById(R.id.copyAsr)
        submitVoice = findViewById(R.id.submitVoice)
        ideaList = findViewById(R.id.ideaList)
        ideaCount = findViewById(R.id.ideaCount)
        myUidView = findViewById(R.id.myUid)
        queryUid = findViewById(R.id.queryUid)
        queryResult = findViewById(R.id.queryResult)

        val prefs = getSharedPreferences("maodou", MODE_PRIVATE)
        myUid = prefs.getString("uid", "").orEmpty()
        if (myUid.matches(Regex("^\\d{9,12}$"))) myUidView.text = myUid else ensureUid(prefs)

        findViewById<Button>(R.id.copyUid).setOnClickListener {
            if (myUid.isEmpty()) { toast("编号还没生成"); return@setOnClickListener }
            copyText(myUid)
        }
        findViewById<Button>(R.id.doQuery).setOnClickListener { queryMyRecords() }

        asrSupported = SpeechRecognizer.isRecognitionAvailable(this)
        if (!asrSupported) {
            asrText.hint = "此设备不支持自动转写，可手动输入文字"
        }
        copyAsr.setOnClickListener {
            val t = asrText.text.toString().trim()
            if (t.isEmpty()) { toast("还没有文字"); return@setOnClickListener }
            copyText(t)
        }

        tabText.setOnClickListener { switchTab(true) }
        tabVoice.setOnClickListener { switchTab(false) }

        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                charCount.text = "${s?.length ?: 0} / 500"
            }
        })

        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            chip.setOnClickListener {
                selectedTag = chip.tag as String
                for (j in 0 until chipRow.childCount) {
                    val c = chipRow.getChildAt(j) as TextView
                    if (c === chip) {
                        c.setBackgroundResource(R.drawable.bg_chip_on)
                        c.setTextColor(resources.getColor(R.color.deep_green))
                    } else {
                        c.setBackgroundResource(R.drawable.bg_chip_off)
                        c.setTextColor(resources.getColor(R.color.muted))
                    }
                }
            }
        }

        submitText.setOnClickListener { submitIdea() }
        recBtn.setOnClickListener { toggleRecord() }
        submitVoice.setOnClickListener { submitVoiceIdea() }

        loadIdeas()
    }

    private fun switchTab(text: Boolean) {
        panelText.visibility = if (text) View.VISIBLE else View.GONE
        panelVoice.visibility = if (text) View.GONE else View.VISIBLE
        tabText.setBackgroundResource(if (text) R.drawable.bg_tab_on else 0)
        tabVoice.setBackgroundResource(if (text) 0 else R.drawable.bg_tab_on)
        tabText.setTextColor(resources.getColor(if (text) R.color.deep_green else R.color.faint))
        tabVoice.setTextColor(resources.getColor(if (text) R.color.faint else R.color.deep_green))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("毛豆灵感", text))
        toast("已复制")
    }

    private fun ensureUid(prefs: android.content.SharedPreferences) {
        Thread {
            val res = httpPost("/api/user/new", "{}")
            main.post {
                if (res != null) {
                    try {
                        val j = JSONObject(res)
                        val id = j.optString("uid", "")
                        if (id.matches(Regex("^\\d{9,12}$"))) {
                            myUid = id
                            prefs.edit().putString("uid", id).apply()
                            myUidView.text = id
                            return@post
                        }
                    } catch (e: Exception) {}
                }
                myUidView.text = "获取失败"
            }
        }.start()
    }

    private fun queryMyRecords() {
        val id = queryUid.text.toString().trim()
        if (!id.matches(Regex("^\\d{9,12}$"))) { toast("请输入 9-12 位数字编号"); return }
        queryResult.visibility = View.VISIBLE
        queryResult.text = "查询中…"
        Thread {
            val res = httpGet("/api/user/$id/records")
            main.post {
                if (res == null) { queryResult.text = "网络出错"; return@post }
                try {
                    val j = JSONObject(res)
                    val items = j.optJSONArray("items") ?: JSONArray()
                    if (items.length() == 0) { queryResult.text = "这个编号还没有记录"; return@post }
                    val sb = StringBuilder("编号 $id · 共 ${items.length()} 条记录\n")
                    for (i in 0 until minOf(items.length(), 5)) {
                        val x = items.getJSONObject(i)
                        val t = if (x.optString("type") == "voice")
                            x.optString("transcript").ifEmpty { x.optString("content").ifEmpty { "[语音]" } }
                        else x.optString("content")
                        sb.append("· ").append(t.take(60)).append("\n")
                    }
                    if (items.length() > 5) sb.append("仅显示最近 5 条")
                    queryResult.text = sb.toString().trimEnd()
                } catch (e: Exception) { queryResult.text = "查询失败" }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startAsr() {
        if (!asrSupported) return
        try {
            speech?.destroy()
            speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        toast("语音识别需要麦克风权限")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        if (asrFinal.isNotEmpty()) asrFinal.append("")
                        asrFinal.append(text)
                        asrText.setText(asrFinal.toString())
                        asrText.setSelection(asrText.text.length)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    main.post {
                        asrText.setText(asrFinal.toString() + text)
                        asrText.setSelection(asrText.text.length)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            asrFinal = StringBuilder()
            asrText.setText("")
            speech?.startListening(intent)
        } catch (e: Exception) {
            asrSupported = false
            asrText.hint = "自动转写不可用，可手动输入文字"
        }
    }

    private fun stopAsr() {
        try { speech?.stopListening() } catch (e: Exception) {}
    }

    private fun withPath(path: String): String {
        val q = "$share&code=${java.net.URLEncoder.encode(accessCode, "UTF-8")}"
        return if (path.contains("?")) "$path&$q" else "$path?$q"
    }

    private fun httpPost(path: String, json: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(base + withPath(path)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGet(path: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(base + withPath(path)).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun submitIdea() {
        val content = textInput.text.toString().trim()
        if (content.isEmpty()) { toast("先写点什么吧"); return }
        submitText.isEnabled = false
        Thread {
            val body = JSONObject().apply {
                put("type", "text")
                put("uid", myUid)
                put("content", content)
                put("tag", selectedTag)
                put("ts", System.currentTimeMillis())
            }.toString()
            val res = httpPost("/api/ideas", body)
            main.post {
                submitText.isEnabled = true
                if (res != null && JSONObject(res).optBoolean("ok")) {
                    textInput.setText("")
                    toast("收下了，灵感已记下")
                    loadIdeas()
                } else {
                    toast("提交失败，检查网络后再试")
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecord() {
        if (recording) {
            stopRecord()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
            return
        }
        try {
            recordFile = "${cacheDir}/voice_${System.currentTimeMillis()}.m4a"
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(recordFile)
                prepare()
                start()
            }
            recording = true
            seconds = 0
            startAsr()
            recBtn.text = "■"
            voiceTip.text = "正在录音，再点一次结束"
            ticker = object : CountDownTimer(60000, 200) {
                override fun onTick(millisUntilFinished: Long) {
                    seconds = ((60000 - millisUntilFinished) / 1000).toInt()
                    timer.text = "0:${String.format(Locale.US, "%02d", seconds)}"
                    val bars = "▂▄▆█▆▄▂▄▆▇▅▃▂▄▆█▃▅▇▄▂▄▆█▆▄"
                    wave.text = bars.toList().shuffled().take(20).joinToString("")
                }
                override fun onFinish() { stopRecord() }
            }.start()
        } catch (e: Exception) {
            toast("录音启动失败：${e.message}")
        }
    }

    private fun stopRecord() {
        if (!recording) return
        recording = false
        ticker?.cancel()
        stopAsr()
        try {
            recorder?.stop()
        } catch (e: Exception) {
        } finally {
            recorder?.release()
            recorder = null
        }
        val file = java.io.File(recordFile)
        if (file.exists() && file.length() > 0 && file.length() <= audioCacheMax) {
            val bytes = file.readBytes()
            pendingAudioBase64 = "data:audio/mp4;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            pendingDuration = "0:${String.format(Locale.US, "%02d", maxOf(1, seconds))}"
            submitVoice.isEnabled = true
            voiceTip.text = "正在转成文字…"
            serverTranscribe(pendingAudioBase64!!)
        } else {
            voiceTip.text = "没录到声音，再点一次试试"
        }
        recBtn.text = "●"
    }

    private fun serverTranscribe(audio: String) {
        Thread {
            val res = httpPost("/api/transcribe", JSONObject().put("audio", audio).toString())
            main.post {
                if (res != null) {
                    try {
                        val j = JSONObject(res)
                        val tr = j.optString("transcript", "")
                        if (j.optBoolean("ok") && tr.isNotEmpty()) {
                            asrText.setText(tr)
                            voiceTip.text = "已自动转成文字，可修改后提交"
                            return@post
                        }
                    } catch (e: Exception) {}
                }
                voiceTip.text = if (asrSupported) "已用本机识别，可修改" else "请手动输入文字"
            }
        }.start()
    }

    private fun submitVoiceIdea() {
        val audio = pendingAudioBase64
        if (audio == null) { toast("先录一段语音"); return }
        submitVoice.isEnabled = false
        Thread {
            val body = JSONObject().apply {
                put("type", "voice")
                put("uid", myUid)
                put("content", voiceNote.text.toString().trim())
                put("transcript", asrText.text.toString().trim())
                put("tag", selectedTag)
                put("duration", pendingDuration)
                put("audio", audio)
                put("ts", System.currentTimeMillis())
            }.toString()
            val res = httpPost("/api/ideas", body)
            main.post {
                submitVoice.isEnabled = true
                if (res != null && JSONObject(res).optBoolean("ok")) {
                    pendingAudioBase64 = null
                    voiceNote.setText("")
                    asrText.setText("")
                    asrFinal = StringBuilder()
                    timer.text = "0:00"
                    voiceTip.text = "点一下开始说话"
                    toast("语音和文字都收下了")
                    loadIdeas()
                } else {
                    toast("提交失败，检查网络后再试")
                }
            }
        }.start()
    }

    private fun loadIdeas() {
        Thread {
            val res = httpGet("/api/ideas")
            main.post {
                if (res == null) {
                    ideaList.removeAllViews()
                    val t = TextView(this)
                    t.text = "暂时读不到灵感，检查网络后重开 App"
                    t.setTextColor(resources.getColor(R.color.faint))
                    t.textSize = 13f
                    t.setPadding(0, 60, 0, 0)
                    ideaList.addView(t)
                    return@post
                }
                val arr = JSONObject(res).optJSONArray("items") ?: JSONArray()
                ideaCount.text = "${arr.length()} 条"
                ideaList.removeAllViews()
                if (arr.length() == 0) {
                    val t = TextView(this)
                    t.text = "还没有灵感，先写一句或者说一段吧\n提交后就会出现在这里"
                    t.setTextColor(resources.getColor(R.color.faint))
                    t.textSize = 13f
                    t.gravity = android.view.Gravity.CENTER
                    t.setPadding(0, 60, 0, 0)
                    ideaList.addView(t)
                    return@post
                }
                for (i in 0 until arr.length()) {
                    addIdeaCard(arr.getJSONObject(i))
                }
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun addIdeaCard(item: JSONObject) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_idea, ideaList, false)
        val card = v.findViewById<LinearLayout>(R.id.card)
        val tag = v.findViewById<TextView>(R.id.tag)
        val time = v.findViewById<TextView>(R.id.time)
        val body = v.findViewById<TextView>(R.id.body)
        val play = v.findViewById<Button>(R.id.play)
        val transcriptView = v.findViewById<TextView>(R.id.transcript)
        val transcriptActions = v.findViewById<LinearLayout>(R.id.transcriptActions)
        val copyTextBtn = v.findViewById<Button>(R.id.copyText)
        val editTextBtn = v.findViewById<Button>(R.id.editText)
        val noteView = v.findViewById<TextView>(R.id.note)
        val del = v.findViewById<Button>(R.id.delete)

        val type = item.optString("type", "text")
        val isVoice = type == "voice"
        card.setBackgroundResource(if (isVoice) R.drawable.bg_idea_voice else R.drawable.bg_idea)
        tag.text = "${item.optString("tag", "随手记")}${if (isVoice) " · 语音 ${item.optString("duration", "")}" else ""}"
        if (isVoice) tag.setBackgroundResource(R.drawable.bg_chip_on)
        body.text = item.optString("content").ifEmpty { "（这段语音没有加文字备注）" }

        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        time.text = fmt.format(Date(item.optLong("ts", System.currentTimeMillis())))

        val audioUrl = item.optString("audioUrl", "")
        if (isVoice && audioUrl.isNotEmpty()) {
            play.visibility = View.VISIBLE
            play.setOnClickListener { playAudio(audioUrl) }
        }

        val id = item.optString("id", "")
        val transcript = item.optString("transcript", "")
        if (isVoice && transcript.isNotEmpty()) {
            transcriptView.text = transcript
            transcriptView.visibility = View.VISIBLE
            transcriptActions.visibility = View.VISIBLE
            copyTextBtn.setOnClickListener { copyText(transcript) }
            editTextBtn.setOnClickListener {
                val input = EditText(this)
                input.setText(transcriptView.text.toString())
                input.setSelection(input.text.length)
                android.app.AlertDialog.Builder(this)
                    .setTitle("编辑转写文字")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        val value = input.text.toString().trim()
                        Thread {
                            val res = httpPost("/api/edit", JSONObject().apply {
                                put("id", id); put("transcript", value)
                            }.toString())
                            main.post {
                                if (res != null && JSONObject(res).optBoolean("ok")) {
                                    toast("已更新"); loadIdeas()
                                } else toast("更新失败")
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        val note = item.optString("content", "")
        if (isVoice && note.isNotEmpty()) {
            noteView.text = "备注：$note"
            noteView.visibility = View.VISIBLE
        }
        del.setOnClickListener {
            Thread {
                val res = httpPost("/api/delete", JSONObject().put("id", id).toString())
                main.post {
                    if (res != null && JSONObject(res).optBoolean("ok")) {
                        toast("已删除")
                        loadIdeas()
                    } else {
                        toast("删除失败")
                    }
                }
            }.start()
        }
        ideaList.addView(v)
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(base + withPath(url))
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> toast("播放失败"); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            toast("播放失败：${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toggleRecord()
        } else {
            toast("需要麦克风权限才能录音")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker?.cancel()
        recorder?.release()
        mediaPlayer?.release()
        speech?.destroy()
    }
}

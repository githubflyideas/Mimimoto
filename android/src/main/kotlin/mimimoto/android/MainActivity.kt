package mimimoto.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mimimoto.audioqc.Audio
import mimimoto.audioqc.Policy
import mimimoto.audioqc.Profile
import mimimoto.audioqc.Report
import mimimoto.audioqc.encodeWav
import mimimoto.audioqc.judge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The product's palette, from the design canvas.
private val Cream = Color(0xFFFAF7F2)
private val Ink = Color(0xFF2A2520)
private val Muted = Color(0xFF6B6157)
private val Hairline = Color(0xFFE7DFD3)
private val Lamp = Color(0xFFE8A33D)
private val Clay = Color(0xFFC4644B)
private val Moss = Color(0xFF5B7B5A)

/**
 * One reading: what was recorded, what the gate said, and where the WAV landed.
 *
 * The saved file matters as much as the verdict during the pilot — a take the
 * gate rejected is evidence about a handset, and it can only be argued about if
 * it can be pulled off the device and looked at.
 */
private data class Reading(
    val at: String,
    val source: String,
    val claimsUnprocessed: Boolean,
    val report: Report,
    val file: File?,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MimimotoTheme { RecordScreen() } }
    }
}

@Composable
private fun MimimotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Lamp,
            onPrimary = Ink,
            background = Cream,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
private fun RecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recorder = remember {
        PcmRecorder(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var readings by remember { mutableStateOf(listOf<Reading>()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        Text("录音质量实测", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            "按住说一句话。判断用的是服务端同一套算法——最关键的一条是看频谱在哪里停止，" +
                "因为设备声称的采样率经常是假的。",
            fontSize = 14.sp, color = Muted, lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(36.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RecordButton(
                recording = recording,
                level = level,
                enabled = granted,
                onPress = {
                    if (!granted) {
                        ask.launch(Manifest.permission.RECORD_AUDIO)
                        return@RecordButton
                    }
                    error = null
                    recording = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { recorder.record { level = it } }
                        }
                        recording = false
                        level = 0f
                        outcome
                            .onSuccess { take ->
                                val audio = Audio(take.samples, take.sampleRate, 1, 16)
                                val report = judge(audio, Policy.forProfile(Profile.DAILY))
                                val file = withContext(Dispatchers.IO) { save(context, take) }
                                readings = listOf(
                                    Reading(now(), take.source, take.claimsUnprocessed, report, file)
                                ) + readings
                            }
                            .onFailure { error = it.message ?: "录音失败" }
                    }
                },
                onRelease = { recorder.stop() },
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (!granted) "需要麦克风权限" else if (recording) "松开结束" else "按住说话",
            fontSize = 14.sp, color = Muted,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )

        error?.let {
            Spacer(Modifier.height(20.dp))
            Card(RoundedCornerShape(16.dp), Color.White) {
                Text(it, fontSize = 14.sp, color = Clay, modifier = Modifier.padding(16.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        DeviceCard(recorder.claimsUnprocessed())

        for (reading in readings) {
            Spacer(Modifier.height(14.dp))
            ReadingCard(reading)
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "录下的 WAV 留在这台设备上（Android/data/ideas.githubfly.mimimoto.debug/files），" +
                "可以拷出来对比。这一版不联网。",
            fontSize = 13.sp, color = Muted, lineHeight = 21.sp,
        )
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val size = if (recording) 188.dp + (28.dp * level.coerceIn(0f, 1f)) else 188.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (recording) Clay else Lamp)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (recording) "正在听…" else "按住",
            fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink,
        )
    }
}

@Composable
private fun DeviceCard(claimsUnprocessed: Boolean) {
    Card(RoundedCornerShape(18.dp), Color.White) {
        Column(Modifier.padding(18.dp)) {
            Label("这台设备")
            Spacer(Modifier.height(10.dp))
            Row2("型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            Row2("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Row2("声称支持未处理采集", if (claimsUnprocessed) "是" else "否")
        }
    }
}

@Composable
private fun ReadingCard(reading: Reading) {
    val r = reading.report
    val worst = r.failures.firstOrNull() ?: r.warnings.firstOrNull()
    val tone = when {
        !r.passed -> Clay
        r.warnings.isNotEmpty() -> Lamp
        else -> Moss
    }

    Card(RoundedCornerShape(18.dp), Color.White) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !r.passed -> "不能用"
                        r.warnings.isNotEmpty() -> "勉强可用"
                        else -> "可以用"
                    },
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tone,
                    modifier = Modifier.weight(1f),
                )
                Text(reading.at, fontSize = 13.sp, color = Muted)
            }

            Spacer(Modifier.height(12.dp))
            Row2("频谱截止", "${r.cutoffHz.toInt()} Hz")
            Row2("信噪比", "%.1f dB".format(r.snrDb))
            Row2("有效语音", "%.1f s".format(r.speechSeconds))
            Row2("削波", "%.2f %%".format(r.clippingRatio * 100))
            Row2("采集源", reading.source)

            worst?.let {
                Spacer(Modifier.height(12.dp))
                Text(advice(it.code.wire), fontSize = 14.sp, color = Ink, lineHeight = 22.sp)
            }

            reading.file?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.name, fontSize = 12.sp, color = Muted)
            }
        }
    }
}

/**
 * Codes become sentences here and nowhere else, so the wording can change — or
 * be translated — without touching the gate.
 */
private fun advice(code: String): String = when (code) {
    "band_limited" -> "这条像是从通话里录的——高音被切掉了。直接在这里录，别用通话或语音消息转发过来。"
    "noisy" -> "背景有点吵。找个安静的地方，关掉风扇和电视。"
    "clipping" -> "声音录爆了。手机离嘴一拳远就好，不用贴着。"
    "too_short" -> "再说长一点，一句完整的话就够。"
    "no_speech" -> "没听到说话，检查一下麦克风有没有被挡住。"
    "mostly_silence" -> "大部分是空白。按住之后直接说，别等。"
    "low_sample_rate" -> "这台设备的采样率太低。"
    "dc_offset" -> "采集链路有点异常。"
    else -> code
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted)
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Card(shape: RoundedCornerShape, fill: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
    ) { content() }
}

private fun now(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun save(context: Context, take: PcmRecorder.Take): File? = try {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "take-$stamp-${take.source}.wav")
    file.writeBytes(encodeWav(take.samples, take.sampleRate))
    file
} catch (_: Exception) {
    null
}

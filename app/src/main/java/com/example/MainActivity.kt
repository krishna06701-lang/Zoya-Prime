package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.context.ContextMonitor
import com.example.service.ZoyaForegroundService
import com.example.tool.ToolExecutor
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private var zoyaService: ZoyaForegroundService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ZoyaForegroundService.ServiceBinder
            zoyaService = binder.getService()
            isBound = true
            Log.i(TAG, "Successfully bound to ZoyaForegroundService.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            zoyaService = null
            isBound = false
            Log.i(TAG, "ZoyaForegroundService disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bind Foreground Service immediately if we already have voice recording permission
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            bindZoyaService()
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF050505))
                            .padding(innerPadding)
                    ) {
                        ZoyaCoreLayoutApp(
                            zoyaService = zoyaService,
                            onActivateService = {
                                bindZoyaService()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun bindZoyaService() {
        val intent = Intent(this, ZoyaForegroundService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun ZoyaCoreLayoutApp(
    zoyaService: ZoyaForegroundService?,
    onActivateService: () -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(hasAllRequiredPermissions(context))
    }

    if (!permissionsGranted) {
        OnboardingScreen(
            onPermissionsCompleted = {
                permissionsGranted = true
                onActivateService()
            }
        )
    } else {
        ZoyaAssistantDashboard(
            zoyaService = zoyaService
        )
    }
}

private fun hasAllRequiredPermissions(context: Context): Boolean {
    val reqs = listOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )
    return reqs.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
fun OnboardingScreen(onPermissionsCompleted: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results: Map<String, Boolean> ->
        val ok = results[android.Manifest.permission.RECORD_AUDIO] == true
        if (ok) {
            onPermissionsCompleted()
        } else {
            Log.e("Onboarding", "Mic permission is mandatory!")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("onboarding_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Decorative Futuristic Shield Logo
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0x33FFFFFF), CircleShape)
                .shadow(16.dp, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "Shield Logo",
                tint = Color.Cyan,
                modifier = Modifier.size(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "ZOYA PRIME",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Premium Voice-First Android AI Architect",
            fontSize = 15.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x22000000)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "System Requirements",
                    color = Color.Cyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                PermissionInfoRow(icon = Icons.Filled.Mic, text = "Microphone (Mandatory Wakes)")
                PermissionInfoRow(icon = Icons.Filled.Contacts, text = "Contacts (Search phone logs)")
                PermissionInfoRow(icon = Icons.Filled.Phone, text = "Phone Dial (Call family hands-free)")
                PermissionInfoRow(icon = Icons.Filled.Camera, text = "Camera (Smart vision photography)")
                PermissionInfoRow(icon = Icons.Filled.LocationOn, text = "Location (Ambient weather / routing)")
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.CALL_PHONE,
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("activate_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Cyan,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(
                text = "ACTIVATE ASSISTANT",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Zoya runs offline background services using secure local state memory.",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun PermissionInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.LightGray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun ZoyaAssistantDashboard(
    zoyaService: ZoyaForegroundService?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Collect states dynamically from service flows
    val assistantState by if (zoyaService != null) {
        ZoyaForegroundService.currentState.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(ZoyaForegroundService.AssistantState.IDLE) }
    }

    val liveTranscript by if (zoyaService != null) {
        ZoyaForegroundService.transcriptionFlow.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf("Initializing assistant interface...") }
    }

    val lastSpeech by if (zoyaService != null) {
        ZoyaForegroundService.lastAssistantSpeech.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf("") }
    }

    val micLevel by if (zoyaService != null) {
        ZoyaForegroundService.liveMicLevel.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(0f) }
    }

    // Dynamic transcript logs for terminal console history
    val logs = remember { mutableStateListOf<String>() }
    var showLogsDrawer by remember { mutableStateOf(true) }

    LaunchedEffect(liveTranscript) {
        if (liveTranscript.isNotEmpty() && liveTranscript != "Searching..." && liveTranscript != "listening...") {
            if (logs.isEmpty() || logs.firstOrNull() != liveTranscript) {
                logs.add(0, liveTranscript)
                if (logs.size > 15) logs.removeLast()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Sleek Minimal Header (App Identity)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SYSTEM ALPHA",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF818CF8), // indigo-400
                    letterSpacing = 2.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Zoya ",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "Prime",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        letterSpacing = (-0.5).sp
                    )
                }
            }

            // Glass Morphism Status Indicator Circle
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .clip(CircleShape)
                    .clickable {
                        try {
                            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (assistantState == ZoyaForegroundService.AssistantState.OFFLINE) Color.Red else Color(0xFF14B8A6), // teal-400
                            CircleShape
                        )
                )
            }
        }

        // 2. Main AI Visualization Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Decorative background radial indigo glow mapping the design spec
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF4F46E5).copy(alpha = 0.08f), Color.Transparent),
                            radius = 480f
                        )
                    )
            )

            // Dynamic ambient particle elements on corners
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 28.dp, end = 16.dp)
                        .size(6.dp)
                        .background(Color(0xFFC7D2FE).copy(alpha = 0.4f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 32.dp, start = 8.dp)
                        .size(5.dp)
                        .background(Color(0xFFE9D5FF).copy(alpha = 0.3f), CircleShape)
                )
            }

            // Central AI Orb View
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .clickable {
                            zoyaService?.toggleActivation()
                        }
                        .testTag("ai_orb_button"),
                    contentAlignment = Alignment.Center
                ) {
                    ZoyaAnimatedOrb(
                        state = assistantState,
                        micLevel = micLevel
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Beautiful voice state subtitle
                Text(
                    text = when (assistantState) {
                        ZoyaForegroundService.AssistantState.IDLE -> "Awaiting system voice query..."
                        ZoyaForegroundService.AssistantState.LISTENING -> "LISTENING..."
                        ZoyaForegroundService.AssistantState.THINKING -> "THINKING..."
                        ZoyaForegroundService.AssistantState.SPEAKING -> "SPEAKING..."
                        ZoyaForegroundService.AssistantState.OFFLINE -> "SYSTEM OFFLINE"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC7D2FE).copy(alpha = 0.6f),
                    letterSpacing = 1.8.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Direct Transcription Quote text from specs
                Text(
                    text = if (liveTranscript.length > 85) liveTranscript.take(85) + "..." else "\"$liveTranscript\"",
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    lineHeight = 25.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // 3. Optional Integrated System Terminal History Logs
        AnimatedVisibility(
            visible = showLogsDrawer && logs.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                color = Color.White.copy(alpha = 0.03f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "TERMINAL INSTANT REPORTED SYSTEM LOGS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF818CF8).copy(alpha = 0.8f),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = ">",
                                    color = Color(0xFF818CF8),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(12.dp)
                                )
                                Text(
                                    text = log,
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Clean Context Badge Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Memory Active Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF22C55E), CircleShape) // Green dot
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MEMORY ACTIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Gemini Pro Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF60A5FA), CircleShape) // Light blue dot
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "GEMINI ULTRA SECURE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Minimalist Bottom Trigger & Action Utilities Panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logs Expand Button (Clean Minimalism representation)
            IconButton(
                onClick = { showLogsDrawer = !showLogsDrawer },
                modifier = Modifier.size(44.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (showLogsDrawer) Icons.Filled.KeyboardArrowDown else Icons.Filled.List,
                        contentDescription = "Logs toggle",
                        tint = if (showLogsDrawer) Color(0xFF818CF8) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "LOGS",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 0.3.sp
                    )
                }
            }

            // Big gorgeous minimal responsive White touch-action Orb FAB
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(Color.White, CircleShape)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .clickable {
                        zoyaService?.toggleActivation()
                    }
                    .testTag("submit_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.Black, RoundedCornerShape(4.dp))
                )
            }

            // Right Utilities Button - Quick Flashlight trigger
            IconButton(
                onClick = {
                    ToolExecutor.toggleFlashlight(context, true)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.FlashlightOn,
                        contentDescription = "Flashlight shortcut",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "LIGHT",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }
    }
}

private fun getStatusTextColor(state: ZoyaForegroundService.AssistantState): Color {
    return when (state) {
        ZoyaForegroundService.AssistantState.IDLE -> Color.LightGray
        ZoyaForegroundService.AssistantState.LISTENING -> Color.Green
        ZoyaForegroundService.AssistantState.THINKING -> Color.Cyan
        ZoyaForegroundService.AssistantState.SPEAKING -> Color(0xFFF39C12)
        ZoyaForegroundService.AssistantState.OFFLINE -> Color.Red
    }
}

@Composable
fun ZoyaAnimatedOrb(
    state: ZoyaForegroundService.AssistantState,
    micLevel: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "Orb Transition")

    // Idle soft breathing glow scale animation
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "soft_glow"
    )

    // Thinking rotation degree animation
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_rotation"
    )

    // Offline pulsing color alpha
    val warningAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alert_pulse"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .scale(if (state == ZoyaForegroundService.AssistantState.IDLE) breathingScale else 1f)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width * 0.32f

        when (state) {
            ZoyaForegroundService.AssistantState.IDLE -> {
                // Soft breathing purple-blue radial layout glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF4F46E5).copy(alpha = 0.45f), Color.Transparent),
                        center = center,
                        radius = radius * 1.6f
                    ),
                    center = center,
                    radius = radius * 1.6f
                )
                // Center core gradient orb
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF312E81), Color(0xFF4F46E5), Color(0xFFA855F7)),
                        start = Offset(center.x - radius, center.y + radius),
                        end = Offset(center.x + radius, center.y - radius)
                    ),
                    center = center,
                    radius = radius
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.12f),
                    center = center,
                    radius = radius * 0.95f
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    center = center,
                    radius = radius * 1.15f,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            ZoyaForegroundService.AssistantState.LISTENING -> {
                // Energetic pulsating waveform representation
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Green.copy(alpha = 0.6f), Color.Transparent),
                        center = center,
                        radius = radius * 1.7f
                    ),
                    center = center,
                    radius = radius * 1.7f
                )

                val points = 60
                val path = Path()
                for (i in 0..points) {
                    val angle = (i * (360f / points)) * (Math.PI / 180f)
                    // Oscillate radius with active mic level!
                    val oscillation = 1f + (micLevel * 0.35f * sin((i * 1.8f) + System.currentTimeMillis() * 0.15f).toFloat())
                    val currentRad = radius * oscillation
                    val x = center.x + currentRad * cos(angle).toFloat()
                    val y = center.y + currentRad * sin(angle).toFloat()
                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path = path, color = Color.Green, style = Stroke(width = 3.dp.toPx()))
                drawCircle(color = Color.White.copy(alpha = 0.5f), center = center, radius = radius * 0.85f)
            }

            ZoyaForegroundService.AssistantState.THINKING -> {
                // Beautiful rotating dual concentric neon rings
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Cyan.copy(alpha = 0.5f), Color.Transparent),
                        center = center,
                        radius = radius * 1.6f
                    ),
                    center = center,
                    radius = radius * 1.6f
                )

                // Outer rotating ring segments
                drawArc(
                    color = Color.Cyan,
                    startAngle = rotationAngle,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = size * 0.64f,
                    style = Stroke(width = 4.dp.toPx())
                )
                drawArc(
                    color = Color(0xFFF39C12),
                    startAngle = rotationAngle + 180f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = size * 0.64f,
                    style = Stroke(width = 4.dp.toPx())
                )

                // Inner countering rotating ring
                drawArc(
                    color = Color.White,
                    startAngle = -rotationAngle,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.8f, center.y - radius * 0.8f),
                    size = size * 0.512f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            ZoyaForegroundService.AssistantState.SPEAKING -> {
                // High-fidelity active speech spikes
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFEC38BC).copy(alpha = 0.65f), Color.Transparent),
                        center = center,
                        radius = radius * 1.7f
                    ),
                    center = center,
                    radius = radius * 1.7f
                )

                val spokeLines = 45
                for (i in 0 until spokeLines) {
                    val angle = (i * (360f / spokeLines)) * (Math.PI / 180f)
                    val scaleOffset = 18f + (160f * micLevel * cos((i * 2.5f) + System.currentTimeMillis() * 0.1f).toFloat().coerceIn(0f, 1f))
                    val innerX = center.x + (radius * 0.8f) * cos(angle).toFloat()
                    val innerY = center.y + (radius * 0.8f) * sin(angle).toFloat()
                    val outerX = center.x + (radius * 0.8f + scaleOffset) * cos(angle).toFloat()
                    val outerY = center.y + (radius * 0.8f + scaleOffset) * sin(angle).toFloat()

                    drawLine(
                        color = Color.White,
                        start = Offset(innerX, innerY),
                        end = Offset(outerX, outerY),
                        strokeWidth = 3.dp.toPx()
                    )
                }
                drawCircle(color = Color(0xFFEC38BC), center = center, radius = radius * 0.75f)
            }

            ZoyaForegroundService.AssistantState.OFFLINE -> {
                // Emergency pulsing warning red glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Red.copy(alpha = warningAlpha * 0.75f), Color.Transparent),
                        center = center,
                        radius = radius * 1.8f
                    ),
                    center = center,
                    radius = radius * 1.8f
                )
                drawCircle(
                    color = Color.Red,
                    center = center,
                    radius = radius
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    center = center,
                    radius = radius * 0.7f
                )
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

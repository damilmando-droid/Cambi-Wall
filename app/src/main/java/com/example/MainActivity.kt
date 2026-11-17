package com.example

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

// Class to hold info about available wallpaper templates
data class WallpaperTemplate(
    val id: String,
    val name: String,
    val resId: Int?,
    val isCustom: Boolean = false,
    val customUri: Uri? = null
)

// Class to hold saved phrases and typography 3D presets
data class SavedDesign(
    val id: String,
    val text: String,
    val colorVal: Int,
    val fontSize: Float,
    val xOffset: Float,
    val yOffset: Float,
    val fontFamily: String,
    val effect3D: String,
    val depth: Int,
    val alignment: String
)

fun serializeSavedDesigns(designs: List<SavedDesign>): String {
    return designs.joinToString(";;") { d ->
        "${d.id}|${d.text.replace("|", "\\|").replace(";", "\\;")}|${d.colorVal}|${d.fontSize}|${d.xOffset}|${d.yOffset}|${d.fontFamily}|${d.effect3D}|${d.depth}|${d.alignment}"
    }
}

fun deserializeSavedDesigns(str: String): List<SavedDesign> {
    if (str.isEmpty()) return emptyList()
    return try {
        str.split(";;").mapNotNull { item ->
            if (item.isEmpty()) return@mapNotNull null
            val parts = item.split("|")
            if (parts.size >= 10) {
                SavedDesign(
                    id = parts[0],
                    text = parts[1].replace("\\|", "|").replace("\\;", ";"),
                    colorVal = parts[2].toIntOrNull() ?: 0xFFFF6D00.toInt(),
                    fontSize = parts[3].toFloatOrNull() ?: 36f,
                    xOffset = parts[4].toFloatOrNull() ?: 0.5f,
                    yOffset = parts[5].toFloatOrNull() ?: 0.4f,
                    fontFamily = parts[6],
                    effect3D = parts[7],
                    depth = parts[8].toIntOrNull() ?: 6,
                    alignment = parts[9]
                )
            } else null
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveDesignsToPreferences(context: Context, list: List<SavedDesign>) {
    val sharedPref = context.getSharedPreferences("dam_walls_presets", Context.MODE_PRIVATE)
    sharedPref.edit().putString("saved_designs", serializeSavedDesigns(list)).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) { innerPadding ->
                    DamWallsApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun DamWallsApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Get exact native phone resolution & info
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    val deviceModel = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"

    // Preloaded wallpapers (using generated suffixes)
    val templates = remember {
        mutableStateListOf(
            WallpaperTemplate(
                id = "custom_gradient",
                name = "Diseñar Degradado",
                resId = null
            ),
            WallpaperTemplate(
                id = "grad_orange",
                name = "Gradiente Dam",
                resId = null // We render procedurally if null
            ),
            WallpaperTemplate(
                id = "abstract_1",
                name = "Abstracto 1",
                resId = R.drawable.img_wall_abstract_1_1783573413835
            ),
            WallpaperTemplate(
                id = "abstract_2",
                name = "Abstracto 2",
                resId = R.drawable.img_wall_abstract_2_1783573425908
            ),
            WallpaperTemplate(
                id = "abstract_3",
                name = "Abstracto 3",
                resId = R.drawable.img_wall_abstract_3_1783573436630
            )
        )
    }

    // Active State
    var selectedTemplate by remember { mutableStateOf(templates[0]) }
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var blurredBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingImage by remember { mutableStateOf(false) }

    // --- PHOTO ENHANCEMENT & RESTORATION STATE (Perfect for old or low-res photos) ---
    var unenhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var autoEnhanceEnabled by remember { mutableStateOf(false) }
    var sharpenAmount by remember { mutableStateOf(0.0f) } // 0.0 to 1.0 (kernels)
    var denoiseAmount by remember { mutableStateOf(0.0f) } // 0.0 to 1.0 (smoothing)
    var manualBrightness by remember { mutableStateOf(0.0f) } // -100f to 100f
    var manualContrast by remember { mutableStateOf(1.0f) } // 0.5f to 2.0f
    var manualSaturation by remember { mutableStateOf(1.0f) } // 0.0f to 2.0f
    var isEnhancingImage by remember { mutableStateOf(false) }

    // --- CUSTOM GRADIENT CREATOR STATE ---
    var gradientColor1 by remember { mutableStateOf(Color(0xFFFF3D00)) } // Default Orange-Red
    var gradientColor2 by remember { mutableStateOf(Color(0xFF7C4DFF)) } // Default Purple
    var gradientColor3 by remember { mutableStateOf(Color(0xFF00E5FF)) } // Default Cyan
    var useGradientColor3 by remember { mutableStateOf(true) }
    var gradientOrientation by remember { mutableStateOf("vertical") } // vertical, horizontal, diagonal, radial
    var activeGradientColorSlot by remember { mutableStateOf(1) } // 1, 2, or 3

    // --- SAVED PHRASES / DESIGNS HISTORY ---
    val savedDesigns = remember { mutableStateListOf<SavedDesign>() }

    // Load saved designs from SharedPreferences on launch
    LaunchedEffect(Unit) {
        val sharedPref = context.getSharedPreferences("dam_walls_presets", Context.MODE_PRIVATE)
        val rawStr = sharedPref.getString("saved_designs", "") ?: ""
        val list = deserializeSavedDesigns(rawStr)
        savedDesigns.clear()
        savedDesigns.addAll(list)
    }

    // Text Editor State
    var overlayText by remember { mutableStateOf("CAMBIWALL") }
    var textColor by remember { mutableStateOf(Color(0xFFFF6D00)) } // Default orange
    var textFontSize by remember { mutableStateOf(36f) } // sp
    var textYOffsetPct by remember { mutableStateOf(0.4f) } // 0.0f (top) to 1.0f (bottom)
    var textXOffsetPct by remember { mutableStateOf(0.5f) } // Centered
    var selectedFontFamily by remember { mutableStateOf("sans") } // sans, serif, mono, elegant, impact
    var selected3DEffect by remember { mutableStateOf("extrude_black") } // none, extrude_black, extrude_orange, extrude_white, isometric
    var effectDepth by remember { mutableStateOf(6) } // depth level 1-10
    var textAlignment by remember { mutableStateOf("center") } // center, left, right

    // Fit Formatting State
    // "crop" (fills screen proportionally), "blur_fit" (centered with blur background), "solid_fit" (centered with black background), "stretch", "center"
    var fitMode by remember { mutableStateOf("crop") }
    var showApplyDialog by remember { mutableStateOf(false) }
    var isApplyingWallpaper by remember { mutableStateOf(false) }
    var simulateClock by remember { mutableStateOf(false) }
    var showFullScreenPreview by remember { mutableStateOf(false) }

    // Tab view selection
    var activeTab by remember { mutableStateOf("wallpaper") } // wallpaper, text, style, effects

    // Picture selector launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isLoadingImage = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = decodeUriToBitmap(context, uri)
                    if (bitmap != null) {
                        // Generate a unique ID for custom
                        val customTemplate = WallpaperTemplate(
                            id = "custom_${System.currentTimeMillis()}",
                            name = "Personalizada",
                            resId = null,
                            isCustom = true,
                            customUri = uri
                        )
                        withContext(Dispatchers.Main) {
                            templates.add(customTemplate)
                            selectedTemplate = customTemplate
                            unenhancedBitmap = bitmap
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isLoadingImage = false
                            Toast.makeText(context, "Error al cargar la imagen", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingImage = false
                        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Load selected template bitmap whenever selectedTemplate or gradient parameters change
    LaunchedEffect(
        selectedTemplate,
        gradientColor1,
        gradientColor2,
        gradientColor3,
        useGradientColor3,
        gradientOrientation
    ) {
        if (!selectedTemplate.isCustom) {
            isLoadingImage = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = when {
                        selectedTemplate.id == "custom_gradient" -> {
                            createCustomGradientBitmap(
                                w = 1080,
                                h = 2400,
                                color1 = gradientColor1,
                                color2 = gradientColor2,
                                color3 = if (useGradientColor3) gradientColor3 else null,
                                orientation = gradientOrientation
                            )
                        }
                        selectedTemplate.resId != null -> {
                            BitmapFactory.decodeResource(context.resources, selectedTemplate.resId!!)
                        }
                        else -> {
                            // Render a procedural default gradient bitmap (Black/Orange)
                            createProceduralGradientBitmap(1080, 2400)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        unenhancedBitmap = bitmap
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingImage = false
                    }
                }
            }
        }
    }

    // --- PHOTO RESTORATION AND ENHANCEMENT ENGINE PIPELINE ---
    // Automatically runs when the base photo or any filter parameters change,
    // processing images asynchronously in a background thread to prevent UI lag.
    LaunchedEffect(
        unenhancedBitmap,
        autoEnhanceEnabled,
        sharpenAmount,
        denoiseAmount,
        manualBrightness,
        manualContrast,
        manualSaturation
    ) {
        val base = unenhancedBitmap ?: return@LaunchedEffect
        isEnhancingImage = true
        isLoadingImage = true
        withContext(Dispatchers.Default) {
            try {
                // 1. Color dynamic range enhancement (Auto-Restorer or manual adjustments)
                var processed = enhanceColorBitmap(
                    src = base,
                    autoEnhance = autoEnhanceEnabled,
                    brightness = manualBrightness,
                    contrast = manualContrast,
                    saturation = manualSaturation
                )

                // 2. Film grain / Digital sensor noise smoothing
                if (denoiseAmount > 0f) {
                    processed = denoiseBitmap(processed, denoiseAmount)
                }

                // 3. Detail sharpening (reconstructs fuzzy/blurry borders of old/low-res photos)
                if (sharpenAmount > 0f) {
                    processed = sharpenBitmap(processed, sharpenAmount)
                }

                // 4. Precompute blurred backdrop for "Ajuste Perfecto (Fondo Borroso)"
                val blurred = blurBitmap(processed, 25)

                withContext(Dispatchers.Main) {
                    loadedBitmap = processed
                    blurredBitmap = blurred
                    isEnhancingImage = false
                    isLoadingImage = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isEnhancingImage = false
                    isLoadingImage = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF6D00)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CW",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Cambi Wall",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Ajuste Perfecto Inteligente",
                        color = Color(0xFFFF6D00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Info chip for Resolution
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = deviceModel,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End
                )
                Text(
                    text = "${screenWidth} x ${screenHeight} px",
                    color = Color(0xFFFF6D00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
        }

        Divider(color = Color(0xFF222222), thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))

        // --- DYNAMIC LIVE PREVIEW (Locked to Device Aspect Ratio) ---
        Box(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF121212))
                .border(2.dp, Color(0xFFFF6D00), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoadingImage) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFFFF6D00))
                    Text(
                        text = if (isEnhancingImage) "Mejorando resolución..." else "Cargando...",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // We fit the preview into the box matching device aspect ratio (screenWidth / screenHeight)
                val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(screenRatio)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    val containerWidth = maxWidth
                    val containerHeight = maxHeight

                    // 1. Draw Background based on Fit Mode
                    when (fitMode) {
                        "crop" -> {
                            loadedBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Preview Background Crop",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        "stretch" -> {
                            loadedBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Preview Background",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                        "solid_fit" -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                loadedBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Preview Background Centered",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                        "center" -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                loadedBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Preview Background Unscaled",
                                        modifier = Modifier.wrapContentSize(),
                                        contentScale = ContentScale.None
                                    )
                                }
                            }
                        }
                        "blur_fit" -> {
                            // Professional blur-filler backdrop
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Blurred copy filling the background
                                blurredBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Blur Filler Backdrop",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } ?: Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.DarkGray)
                                )

                                // Soft dark overlay over the blur to make text pop
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                )

                                // Sharp copy fit in the center
                                loadedBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Preview Background Centered Fit",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }

                    // Mock Lock Screen Clock Simulation
                    if (simulateClock) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "10:09",
                                color = Color.White,
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Miércoles, 8 de jul.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // 2. Interactive Overlay Text
                    val density = LocalDensity.current
                    var boxHeightPx by remember { mutableStateOf(0f) }
                    var boxWidthPx by remember { mutableStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (boxWidthPx > 0 && boxHeightPx > 0) {
                                        val newX = textXOffsetPct + (dragAmount.x / boxWidthPx)
                                        val newY = textYOffsetPct + (dragAmount.y / boxHeightPx)
                                        textXOffsetPct = newX.coerceIn(0.05f, 0.95f)
                                        textYOffsetPct = newY.coerceIn(0.05f, 0.95f)
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                // To measure the container coordinates
                            }
                            .background(Color.Transparent)
                    ) {
                        // Invisible spacer to measure container dimensions
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    coroutineScope.launch {
                                        // Just placeholder to trigger measurements if needed
                                    }
                                }
                        )

                        // Calculate actual positions based on measured container size
                        val textX = with(density) { (containerWidth.toPx() * textXOffsetPct).toDp() }
                        val textY = with(density) { (containerHeight.toPx() * textYOffsetPct).toDp() }

                        // We render the custom styled 3D text at computed positions
                        if (overlayText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .absoluteOffset(
                                        x = textX - 100.dp, // Offset roughly to center the text box
                                        y = textY - 24.dp
                                    )
                                    .width(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Live3DText(
                                    text = overlayText,
                                    fontSize = textFontSize.sp,
                                    color = textColor,
                                    fontFamily = getFontFamilyFromString(selectedFontFamily),
                                    effect3D = selected3DEffect,
                                    depth = effectDepth,
                                    alignment = textAlignment
                                )
                            }
                        }

                        // Save the pixel dimensions
                        LaunchedEffect(containerWidth, containerHeight) {
                            with(density) {
                                boxWidthPx = containerWidth.toPx()
                                boxHeightPx = containerHeight.toPx()
                            }
                        }
                    }

                    // Floating controls inside live preview container
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        // Top Left: Clock Toggle Quick Button
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { simulateClock = !simulateClock },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = "Simular Reloj",
                                tint = if (simulateClock) Color(0xFFFF6D00) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Top Right: Full Screen Preview Eye Button
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { showFullScreenPreview = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Vista Completa",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- EDITOR NAVIGATION TABS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EditorTabButton(
                title = "Fondo",
                icon = Icons.Default.Image,
                isActive = activeTab == "wallpaper",
                onClick = { activeTab = "wallpaper" },
                modifier = Modifier.weight(1f)
            )
            EditorTabButton(
                title = "Ajuste",
                icon = Icons.Default.AspectRatio,
                isActive = activeTab == "fitting",
                onClick = { activeTab = "fitting" },
                modifier = Modifier.weight(1f)
            )
            EditorTabButton(
                title = "Mejorar",
                icon = Icons.Default.AutoAwesome,
                isActive = activeTab == "enhance",
                onClick = { activeTab = "enhance" },
                modifier = Modifier.weight(1f)
            )
            EditorTabButton(
                title = "Texto",
                icon = Icons.Default.TextFields,
                isActive = activeTab == "text",
                onClick = { activeTab = "text" },
                modifier = Modifier.weight(1f)
            )
            EditorTabButton(
                title = "Efecto 3D",
                icon = Icons.Default.Layers,
                isActive = activeTab == "effects",
                onClick = { activeTab = "effects" },
                modifier = Modifier.weight(1f)
            )
        }

        // --- TAB CONTENT CANVAS ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .padding(12.dp)
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (activeTab) {
                    "wallpaper" -> {
                        Text(
                            text = "Diseños de Fondos Premium",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(templates) { item ->
                                Box(
                                    modifier = Modifier
                                        .size(70.dp, 120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF121212))
                                        .border(
                                            2.dp,
                                            if (selectedTemplate.id == item.id) Color(0xFFFF6D00) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedTemplate = item },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.resId != null) {
                                        Image(
                                            painter = painterResource(id = item.resId),
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else if (item.isCustom && item.customUri != null) {
                                        // Custom image picked from gallery
                                        loadedBitmap?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = "Picked Image",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    } else if (item.id == "custom_gradient") {
                                        val gradientColors = if (useGradientColor3) {
                                            listOf(gradientColor1, gradientColor3, gradientColor2)
                                        } else {
                                            listOf(gradientColor1, gradientColor2)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    when (gradientOrientation) {
                                                        "horizontal" -> Brush.horizontalGradient(colors = gradientColors)
                                                        "diagonal" -> Brush.linearGradient(
                                                            colors = gradientColors,
                                                            start = androidx.compose.ui.geometry.Offset.Zero,
                                                            end = androidx.compose.ui.geometry.Offset.Infinite
                                                        )
                                                        "radial" -> Brush.radialGradient(colors = gradientColors)
                                                        else -> Brush.verticalGradient(colors = gradientColors)
                                                    }
                                                )
                                        )
                                    } else {
                                        // Procedural orange gradient preview
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(Color(0xFFFF6D00), Color.Black)
                                                    )
                                                )
                                        )
                                    }

                                    // Selection Overlay Label
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = item.name,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Gallery Trigger Card
                            item {
                                Box(
                                    modifier = Modifier
                                        .size(70.dp, 120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2E2E2E))
                                        .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .clickable { galleryLauncher.launch("image/*") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AddPhotoAlternate,
                                            contentDescription = "Agregar de Galería",
                                            tint = Color(0xFFFF6D00),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Galería",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // --- BRAND NEW: NATIVE CUSTOM GRADIENT CREATOR STUDIO ---
                        if (selectedTemplate.id == "custom_gradient") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Ajustes de Degradado Personalizado",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )

                            // A. Presets Row
                            Text("Estilos Inspiracionales:", color = Color.Gray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val gradPresets = listOf(
                                    "Crepúsculo" to Triple(Color(0xFFFF416C), Color(0xFFFF4B2B), Color(0xFFFFCC33)),
                                    "Atardecer" to Triple(Color(0xFFF3904F), Color(0xFF3B4371), Color(0xFF1E1E24)),
                                    "Bosque" to Triple(Color(0xFF11998E), Color(0xFF38EF7D), Color(0xFF0F2027)),
                                    "Cyberpunk" to Triple(Color(0xFFE040FB), Color(0xFF00E5FF), Color(0xFF12005E)),
                                    "Aurora" to Triple(Color(0xFF0575E6), Color(0xFF00F260), Color(0xFF00223E))
                                )
                                gradPresets.forEach { (label, colors) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                gradientColor1 = colors.first
                                                gradientColor2 = colors.second
                                                gradientColor3 = colors.third
                                                useGradientColor3 = true
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // B. Active Color Slot & 3-Color Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Ajustar Color:", color = Color.Gray, fontSize = 11.sp)
                                    // Color 1
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(gradientColor1)
                                            .border(
                                                2.dp,
                                                if (activeGradientColorSlot == 1) Color.White else Color.Transparent,
                                                CircleShape
                                            )
                                            .clickable { activeGradientColorSlot = 1 }
                                    )
                                    // Color 2
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(gradientColor2)
                                            .border(
                                                2.dp,
                                                if (activeGradientColorSlot == 2) Color.White else Color.Transparent,
                                                CircleShape
                                            )
                                            .clickable { activeGradientColorSlot = 2 }
                                    )
                                    // Color 3 (optional)
                                    if (useGradientColor3) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(gradientColor3)
                                                .border(
                                                    2.dp,
                                                    if (activeGradientColorSlot == 3) Color.White else Color.Transparent,
                                                    CircleShape
                                                )
                                                .clickable { activeGradientColorSlot = 3 }
                                        )
                                    }
                                }

                                // Switch to enable 3rd color
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("3 Colores", color = Color.LightGray, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Switch(
                                        checked = useGradientColor3,
                                        onCheckedChange = { 
                                            useGradientColor3 = it
                                            if (!it && activeGradientColorSlot == 3) activeGradientColorSlot = 1
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFFFF6D00)
                                        )
                                    )
                                }
                            }

                            // C. Sliders to fine-tune active slot color
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF121212))
                                    .padding(10.dp)
                            ) {
                                val activeColorName = when (activeGradientColorSlot) {
                                    1 -> "Color 1"
                                    2 -> "Color 2"
                                    else -> "Color 3 (Centro)"
                                }
                                val currentColor = when (activeGradientColorSlot) {
                                    1 -> gradientColor1
                                    2 -> gradientColor2
                                    else -> gradientColor3
                                }

                                Text(
                                    text = "Selección Rápida de Color ($activeColorName):",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                val quickColors = listOf(
                                    listOf(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF757575), Color(0xFFBDBDBD), Color(0xFFFF1744), Color(0xFFFF6D00), Color(0xFFFFEA00), Color(0xFF00E676)),
                                    listOf(Color(0xFF00E5FF), Color(0xFF00B0FF), Color(0xFF2979FF), Color(0xFF651FFF), Color(0xFFD500F9), Color(0xFFF50057), Color(0xFFB388FF), Color(0xFFFFC400))
                                )

                                quickColors.forEach { rowColors ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        rowColors.forEach { colorSwatch ->
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(colorSwatch)
                                                    .border(
                                                        width = if (currentColor == colorSwatch) 2.dp else 1.dp,
                                                        color = if (currentColor == colorSwatch) Color.White else Color.White.copy(alpha = 0.2f),
                                                        shape = CircleShape
                                                    )
                                                    .clickable {
                                                        when (activeGradientColorSlot) {
                                                            1 -> gradientColor1 = colorSwatch
                                                            2 -> gradientColor2 = colorSwatch
                                                            else -> gradientColor3 = colorSwatch
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Ajustar $activeColorName (Canales RGB)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // R Slider
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("R:", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                                    Slider(
                                        value = currentColor.red,
                                        onValueChange = { r ->
                                            val updated = Color(red = r, green = currentColor.green, blue = currentColor.blue, alpha = 1f)
                                            when (activeGradientColorSlot) {
                                                1 -> gradientColor1 = updated
                                                2 -> gradientColor2 = updated
                                                else -> gradientColor3 = updated
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(thumbColor = Color.Red, activeTrackColor = Color.Red)
                                    )
                                    Text("${(currentColor.red * 255).roundToInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(24.dp))
                                }

                                // G Slider
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("G:", color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                                    Slider(
                                        value = currentColor.green,
                                        onValueChange = { g ->
                                            val updated = Color(red = currentColor.red, green = g, blue = currentColor.blue, alpha = 1f)
                                            when (activeGradientColorSlot) {
                                                1 -> gradientColor1 = updated
                                                2 -> gradientColor2 = updated
                                                else -> gradientColor3 = updated
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                                    )
                                    Text("${(currentColor.green * 255).roundToInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(24.dp))
                                }

                                // B Slider
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("B:", color = Color.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                                    Slider(
                                        value = currentColor.blue,
                                        onValueChange = { b ->
                                            val updated = Color(red = currentColor.red, green = currentColor.green, blue = b, alpha = 1f)
                                            when (activeGradientColorSlot) {
                                                1 -> gradientColor1 = updated
                                                2 -> gradientColor2 = updated
                                                else -> gradientColor3 = updated
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                                    )
                                    Text("${(currentColor.blue * 255).roundToInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(24.dp))
                                }
                            }

                            // D. Orientation Selector
                            Text("Orientación de Degradado:", color = Color.Gray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val orientations = listOf(
                                    "vertical" to "Vertical ↕",
                                    "horizontal" to "Horizontal ↔",
                                    "diagonal" to "Diagonal ⤡",
                                    "radial" to "Radial ☉"
                                )
                                orientations.forEach { (key, label) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (gradientOrientation == key) Color(0xFFFF6D00) else Color(0xFF2D2D2D))
                                            .clickable { gradientOrientation = key }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (gradientOrientation == key) Color.Black else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        } else {
                            // Suggest custom gradient
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF121212))
                                    .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "¿Quieres crear tu propio fondo degradado?",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "Genera degradados minimalistas en alta resolución con 2 o 3 colores combinados a tu gusto.",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val t = templates.find { it.id == "custom_gradient" }
                                            if (t != null) selectedTemplate = t
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("Empezar a Diseñar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    "fitting" -> {
                        Text(
                            text = "Ajuste Inteligente (Fondo de Pantalla)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Facilita el ajuste automático para el modelo de tu dispositivo sin recortar la foto y manteniendo la máxima calidad posible.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FitOptionCard(
                                title = "Llenar Pantalla (Recorte Perfecto)",
                                description = "Escala la imagen para cubrir el 100% de la pantalla sin deformarla (recorta los bordes excedentes).",
                                isActive = fitMode == "crop",
                                onClick = { fitMode = "crop" }
                            )

                            FitOptionCard(
                                title = "Ajuste Perfecto (Fondo Borroso)",
                                description = "Centra la imagen sobre un fondo difuminado de la misma. Sin recortes ni franjas vacías.",
                                isActive = fitMode == "blur_fit",
                                onClick = { fitMode = "blur_fit" }
                            )

                            FitOptionCard(
                                title = "Ajuste Centrado (Fondo Negro)",
                                description = "Coloca la imagen original centrada sobre un canvas negro nativo.",
                                isActive = fitMode == "solid_fit",
                                onClick = { fitMode = "solid_fit" }
                            )

                            FitOptionCard(
                                title = "Estirar / Llenar Pantalla",
                                description = "Escala y deforma la imagen para cubrir el 100% de la pantalla.",
                                isActive = fitMode == "stretch",
                                onClick = { fitMode = "stretch" }
                            )

                            FitOptionCard(
                                title = "Tamaño Original",
                                description = "Mantiene el tamaño real de la imagen en el centro.",
                                isActive = fitMode == "center",
                                onClick = { fitMode = "center" }
                            )
                        }
                    }

                    "enhance" -> {
                        Text(
                            text = "Restauración y Súper Resolución",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Optimiza y revive fotos viejas, borrosas o de baja resolución con filtros de procesamiento en tiempo real.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // A. Presets Section
                        Text("Preajustes de un Solo Toque:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val presets = listOf(
                                "Vieja/Faded" to {
                                    autoEnhanceEnabled = true
                                    sharpenAmount = 0.45f
                                    denoiseAmount = 0.25f
                                    manualBrightness = 5f
                                    manualContrast = 1.15f
                                    manualSaturation = 1.35f
                                },
                                "Baja Res" to {
                                    autoEnhanceEnabled = false
                                    sharpenAmount = 0.85f
                                    denoiseAmount = 0.10f
                                    manualBrightness = 0f
                                    manualContrast = 1.05f
                                    manualSaturation = 1.0f
                                },
                                "Suave/Retrato" to {
                                    autoEnhanceEnabled = true
                                    sharpenAmount = 0.20f
                                    denoiseAmount = 0.55f
                                    manualBrightness = 10f
                                    manualContrast = 1.0f
                                    manualSaturation = 1.15f
                                },
                                "Limpiar" to {
                                    autoEnhanceEnabled = false
                                    sharpenAmount = 0.0f
                                    denoiseAmount = 0.75f
                                    manualBrightness = 0f
                                    manualContrast = 1.0f
                                    manualSaturation = 1.0f
                                }
                            )

                            presets.forEach { (label, applyPreset) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2E2E2E))
                                        .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { applyPreset() }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // B. Core Enhancements (Auto + Sliders)
                        // Auto-Enhance Switch Card
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF121212))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Mejora Inteligente", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Ajusta contraste, balance de blancos y descoloramiento.", color = Color.Gray, fontSize = 10.sp)
                            }
                            Switch(
                                checked = autoEnhanceEnabled,
                                onCheckedChange = { autoEnhanceEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFF6D00),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = Color(0xFF2E2E2E)
                                )
                            )
                        }

                        // Sharpening Slider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF121212))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Súper Enfoque / Nitidez", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Define bordes y recupera detalles borrosos.", color = Color.Gray, fontSize = 10.sp)
                                }
                                Text("${(sharpenAmount * 100).roundToInt()}%", color = Color(0xFFFF6D00), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = sharpenAmount,
                                onValueChange = { sharpenAmount = it },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF6D00),
                                    activeTrackColor = Color(0xFFFF6D00)
                                )
                            )
                        }

                        // Denoise Slider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF121212))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Filtro Anti-Ruido (Suavizado)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Suaviza el grano analógico y ruido digital.", color = Color.Gray, fontSize = 10.sp)
                                }
                                Text("${(denoiseAmount * 100).roundToInt()}%", color = Color(0xFFFF6D00), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = denoiseAmount,
                                onValueChange = { denoiseAmount = it },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF6D00),
                                    activeTrackColor = Color(0xFFFF6D00)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // C. Manual Fine Tuning Section
                        Text("Ajustes Manuales de Imagen:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF121212))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Brightness Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Brillo Extra", color = Color.LightGray, fontSize = 11.sp)
                                    Text("${manualBrightness.roundToInt()}", color = Color(0xFFFF6D00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = manualBrightness,
                                    onValueChange = { manualBrightness = it },
                                    valueRange = -60f..60f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF6D00),
                                        activeTrackColor = Color(0xFFFF6D00)
                                    )
                                )
                            }

                            // Contrast Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Contraste", color = Color.LightGray, fontSize = 11.sp)
                                    Text("${(manualContrast * 100).roundToInt()}%", color = Color(0xFFFF6D00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = manualContrast,
                                    onValueChange = { manualContrast = it },
                                    valueRange = 0.5f..1.8f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF6D00),
                                        activeTrackColor = Color(0xFFFF6D00)
                                    )
                                )
                            }

                            // Saturation Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Saturación (Color)", color = Color.LightGray, fontSize = 11.sp)
                                    Text("${(manualSaturation * 100).roundToInt()}%", color = Color(0xFFFF6D00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = manualSaturation,
                                    onValueChange = { manualSaturation = it },
                                    valueRange = 0.0f..2.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF6D00),
                                        activeTrackColor = Color(0xFFFF6D00)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Reset Button
                        Button(
                            onClick = {
                                autoEnhanceEnabled = false
                                sharpenAmount = 0.0f
                                denoiseAmount = 0.0f
                                manualBrightness = 0.0f
                                manualContrast = 1.0f
                                manualSaturation = 1.0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E2E2E)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Restablecer Valores de Restauración", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    "text" -> {
                        Text(
                            text = "Añadir Texto al Fondo",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        TextField(
                            value = overlayText,
                            onValueChange = { overlayText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay_text_field"),
                            placeholder = { Text("Escribe un mensaje...") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF121212),
                                unfocusedContainerColor = Color(0xFF121212),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color(0xFFFF6D00)
                            ),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Font size control
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tamaño:", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                            Slider(
                                value = textFontSize,
                                onValueChange = { textFontSize = it },
                                valueRange = 16f..72f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF6D00),
                                    activeTrackColor = Color(0xFFFF6D00)
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${textFontSize.roundToInt()}sp", color = Color(0xFFFF6D00), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Vertical Offset control
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Posición Y:", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                            Slider(
                                value = textYOffsetPct,
                                onValueChange = { textYOffsetPct = it },
                                valueRange = 0.05f..0.95f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFF6D00),
                                    activeTrackColor = Color(0xFFFF6D00)
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${(textYOffsetPct * 100).roundToInt()}%", color = Color(0xFFFF6D00), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Alignment buttons: Left, Center, Right
                        Text("Alineación del Texto:", color = Color.LightGray, fontSize = 12.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val alignments = listOf(
                                "left" to "Izquierda",
                                "center" to "Centrado",
                                "right" to "Derecha"
                            )
                            alignments.forEach { (alignKey, alignLabel) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (textAlignment == alignKey) Color(0xFFFF6D00) else Color(0xFF2E2E2E))
                                        .clickable { textAlignment = alignKey }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = alignLabel,
                                        color = if (textAlignment == alignKey) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Smart Presets for Layout
                        Text("Ajustes Rápidos de Diseño:", color = Color.LightGray, fontSize = 12.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val quickPresets = listOf(
                                Triple("Reloj (Arriba)", 48f, 0.15f),
                                Triple("Frase (Centro)", 32f, 0.45f),
                                Triple("Mínimo (Abajo)", 20f, 0.80f)
                            )
                            quickPresets.forEach { (label, size, yPos) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2E2E2E))
                                        .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            textFontSize = size
                                            textYOffsetPct = yPos
                                            textAlignment = "center"
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        
                        // 1. Guardar Button Card
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Historial de Frases / Diseños",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = {
                                    if (overlayText.trim().isEmpty()) {
                                        Toast.makeText(context, "Escribe un texto antes de guardar", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    val isDuplicate = savedDesigns.any { it.text == overlayText && it.colorVal == textColor.toArgb() && it.fontFamily == selectedFontFamily }
                                    if (!isDuplicate) {
                                        val newDesign = SavedDesign(
                                            id = "design_${System.currentTimeMillis()}",
                                            text = overlayText,
                                            colorVal = textColor.toArgb(),
                                            fontSize = textFontSize,
                                            xOffset = textXOffsetPct,
                                            yOffset = textYOffsetPct,
                                            fontFamily = selectedFontFamily,
                                            effect3D = selected3DEffect,
                                            depth = effectDepth,
                                            alignment = textAlignment
                                        )
                                        savedDesigns.add(0, newDesign)
                                        saveDesignsToPreferences(context, savedDesigns)
                                        Toast.makeText(context, "¡Diseño guardado en tu historial!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Este diseño ya existe en tu historial", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = "Guardar",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Guardar Diseño", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (savedDesigns.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF121212))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No tienes diseños guardados. Diseña un texto arriba y toca 'Guardar Diseño' para guardarlo.",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(savedDesigns, key = { it.id }) { design ->
                                    Box(
                                        modifier = Modifier
                                            .size(110.dp, 80.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF121212))
                                            .border(1.dp, Color(0xFFFF6D00).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                overlayText = design.text
                                                textColor = Color(design.colorVal)
                                                textFontSize = design.fontSize
                                                textXOffsetPct = design.xOffset
                                                textYOffsetPct = design.yOffset
                                                selectedFontFamily = design.fontFamily
                                                selected3DEffect = design.effect3D
                                                effectDepth = design.depth
                                                textAlignment = design.alignment
                                                Toast.makeText(context, "¡Diseño aplicado!", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(6.dp)
                                    ) {
                                        // Miniature text representation
                                        Text(
                                            text = design.text,
                                            color = Color(design.colorVal),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = getFontFamilyFromString(design.fontFamily),
                                            textAlign = when (design.alignment) {
                                                "left" -> TextAlign.Left
                                                "right" -> TextAlign.Right
                                                else -> TextAlign.Center
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.align(Alignment.Center)
                                        )

                                        // Delete button (Top Right)
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .align(Alignment.TopEnd)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                .clickable {
                                                    savedDesigns.remove(design)
                                                    saveDesignsToPreferences(context, savedDesigns)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Eliminar",
                                                tint = Color.Red,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }

                                        // Tiny Font Label (Bottom Left)
                                        Text(
                                            text = design.fontFamily.uppercase(),
                                            color = Color.Gray,
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.align(Alignment.BottomStart)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "effects" -> {
                        Text(
                            text = "Tipos de Letra y Estilo 3D",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        // 1. Font Family Picker
                        Text("Familia de Letras:", color = Color.LightGray, fontSize = 12.sp)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val fonts = listOf(
                                "sans" to "Moderna Sans",
                                "serif" to "Elegante Serif",
                                "mono" to "Retro Monospace",
                                "cursive" to "Cursiva",
                                "impact" to "Impacto Extra"
                            )
                            items(fonts) { (fontKey, fontLabel) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedFontFamily == fontKey) Color(0xFFFF6D00) else Color(0xFF2E2E2E))
                                        .clickable { selectedFontFamily = fontKey }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = fontLabel,
                                        color = if (selectedFontFamily == fontKey) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 2. 3D Effect type
                        Text("Efecto de Relieve 3D:", color = Color.LightGray, fontSize = 12.sp)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val effects3D = listOf(
                                "none" to "Ninguno (Plano)",
                                "extrude_black" to "Relieve Negro 3D",
                                "extrude_orange" to "Relieve Naranja 3D",
                                "extrude_white" to "Relieve Blanco 3D",
                                "isometric" to "Sombra Isometrica"
                            )
                            items(effects3D) { (effectKey, effectLabel) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected3DEffect == effectKey) Color(0xFFFF6D00) else Color(0xFF2E2E2E))
                                        .clickable { selected3DEffect = effectKey }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = effectLabel,
                                        color = if (selected3DEffect == effectKey) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 3. Extrusion Depth
                        if (selected3DEffect != "none") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Profundidad 3D:", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                                Slider(
                                    value = effectDepth.toFloat(),
                                    onValueChange = { effectDepth = it.roundToInt() },
                                    valueRange = 1f..10f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFFF6D00),
                                        activeTrackColor = Color(0xFFFF6D00)
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("$effectDepth px", color = Color(0xFFFF6D00), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 4. Color Selection
                        Text("Color del Texto Principal:", color = Color.LightGray, fontSize = 12.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val colors = listOf(
                                Color(0xFFFF6D00) to "Naranja",
                                Color.White to "Blanco",
                                Color.Black to "Negro",
                                Color(0xFFFFD600) to "Amarillo",
                                Color(0xFF00E676) to "Verde"
                            )
                            colors.forEach { (col, label) ->
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .border(
                                            2.dp,
                                            if (textColor == col) Color.White else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable { textColor = col }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- EXPORT & APPLY ACTIONS PANEL ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Button 1: Save to local device Gallery
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        val finalWallpaper = generateHighResWallpaperBitmap(
                            context = context,
                            sourceBitmap = loadedBitmap ?: createProceduralGradientBitmap(1080, 2400),
                            blurredBitmap = blurredBitmap ?: blurBitmap(createProceduralGradientBitmap(1080, 2400), 25),
                            nativeW = screenWidth,
                            nativeH = screenHeight,
                            fitMode = fitMode,
                            textStr = overlayText,
                            textColor = textColor,
                            fontFamilyStr = selectedFontFamily,
                            effect3D = selected3DEffect,
                            depth = effectDepth,
                            fontSizeSp = textFontSize,
                            xPct = textXOffsetPct,
                            yPct = textYOffsetPct,
                            textAlignment = textAlignment
                        )
                        val saved = saveBitmapToGallery(context, finalWallpaper, "CambiWall_${System.currentTimeMillis()}")
                        if (saved) {
                            Toast.makeText(context, "¡Fondo guardado en la galería!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Error al guardar el fondo", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("save_gallery_button"),
                border = BorderStroke(1.dp, Color(0xFFFF6D00)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF6D00)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = "Guardar en Galería")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Guardar", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // Button 2: Direct set Wallpaper (Fondo Protector)
            Button(
                onClick = { showApplyDialog = true },
                modifier = Modifier
                    .weight(1.3f)
                    .height(52.dp)
                    .testTag("set_wallpaper_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6D00),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Wallpaper, contentDescription = "Aplicar Wallpaper")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Establecer Fondo", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }

    // --- APPLY WALLPAPER ACTION DIALOG ---
    if (showApplyDialog) {
        AlertDialog(
            onDismissRequest = { showApplyDialog = false },
            title = {
                Text(
                    "Establecer Fondo Protector",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    "¿Dónde deseas aplicar tu nuevo fondo de pantalla personalizado de alta definición?",
                    color = Color.LightGray
                )
            },
            containerColor = Color(0xFF1E1E1E),
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showApplyDialog = false
                            isApplyingWallpaper = true
                            coroutineScope.launch {
                                val applied = applyWallpaper(
                                    context = context,
                                    sourceBitmap = loadedBitmap ?: createProceduralGradientBitmap(1080, 2400),
                                    blurredBitmap = blurredBitmap ?: blurBitmap(createProceduralGradientBitmap(1080, 2400), 25),
                                    nativeW = screenWidth,
                                    nativeH = screenHeight,
                                    fitMode = fitMode,
                                    textStr = overlayText,
                                    textColor = textColor,
                                    fontFamilyStr = selectedFontFamily,
                                    effect3D = selected3DEffect,
                                    depth = effectDepth,
                                    fontSizeSp = textFontSize,
                                    xPct = textXOffsetPct,
                                    yPct = textYOffsetPct,
                                    textAlignment = textAlignment,
                                    which = WallpaperManager.FLAG_SYSTEM
                                )
                                isApplyingWallpaper = false
                                if (applied) {
                                    Toast.makeText(context, "Fondo aplicado en Pantalla de Inicio", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al aplicar el fondo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                    ) {
                        Text("Pantalla de Inicio", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            showApplyDialog = false
                            isApplyingWallpaper = true
                            coroutineScope.launch {
                                val applied = applyWallpaper(
                                    context = context,
                                    sourceBitmap = loadedBitmap ?: createProceduralGradientBitmap(1080, 2400),
                                    blurredBitmap = blurredBitmap ?: blurBitmap(createProceduralGradientBitmap(1080, 2400), 25),
                                    nativeW = screenWidth,
                                    nativeH = screenHeight,
                                    fitMode = fitMode,
                                    textStr = overlayText,
                                    textColor = textColor,
                                    fontFamilyStr = selectedFontFamily,
                                    effect3D = selected3DEffect,
                                    depth = effectDepth,
                                    fontSizeSp = textFontSize,
                                    xPct = textXOffsetPct,
                                    yPct = textYOffsetPct,
                                    textAlignment = textAlignment,
                                    which = WallpaperManager.FLAG_LOCK
                                )
                                isApplyingWallpaper = false
                                if (applied) {
                                    Toast.makeText(context, "Fondo aplicado en Pantalla de Bloqueo", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al aplicar el fondo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                    ) {
                        Text("Pantalla de Bloqueo", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            showApplyDialog = false
                            isApplyingWallpaper = true
                            coroutineScope.launch {
                                val appliedHome = applyWallpaper(
                                    context = context,
                                    sourceBitmap = loadedBitmap ?: createProceduralGradientBitmap(1080, 2400),
                                    blurredBitmap = blurredBitmap ?: blurBitmap(createProceduralGradientBitmap(1080, 2400), 25),
                                    nativeW = screenWidth,
                                    nativeH = screenHeight,
                                    fitMode = fitMode,
                                    textStr = overlayText,
                                    textColor = textColor,
                                    fontFamilyStr = selectedFontFamily,
                                    effect3D = selected3DEffect,
                                    depth = effectDepth,
                                    fontSizeSp = textFontSize,
                                    xPct = textXOffsetPct,
                                    yPct = textYOffsetPct,
                                    textAlignment = textAlignment,
                                    which = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                                )
                                isApplyingWallpaper = false
                                if (appliedHome) {
                                    Toast.makeText(context, "Fondo aplicado en Ambas Pantallas", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al aplicar el fondo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                    ) {
                        Text("Ambas Pantallas", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    TextButton(
                        onClick = { showApplyDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar", color = Color.White)
                    }
                }
            },
            dismissButton = null
        )
    }

    // --- APPLYING OVERLAY INDICATOR ---
    if (isApplyingWallpaper) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFFFF6D00))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Ajustando formato y aplicando...",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }

    // --- FULL SCREEN REAL-TIME PREVIEW DIALOG ---
    if (showFullScreenPreview) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFullScreenPreview = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullScreenPreview = false }
            ) {
                // 1. Background image based on fitMode
                when (fitMode) {
                    "crop" -> {
                        loadedBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Full Background Crop",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                    "stretch" -> {
                        loadedBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Full Background Stretch",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    }
                    "solid_fit" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            loadedBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Full Background Centered",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                    "center" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            loadedBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Full Background Unscaled",
                                    modifier = Modifier.wrapContentSize(),
                                    contentScale = ContentScale.None
                                )
                            }
                        }
                    }
                    "blur_fit" -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            blurredBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Full Blur Backdrop",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f))
                            )
                            loadedBitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Full Background Centered Fit",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }

                // 2. Simulated clock
                if (simulateClock) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "10:09",
                            color = Color.White,
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Miércoles, 8 de jul.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // 3. Render 3D Text Overlay
                if (overlayText.isNotEmpty()) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val fullW = maxWidth
                        val fullH = maxHeight
                        val textX = fullW * textXOffsetPct
                        val textY = fullH * textYOffsetPct

                        Box(
                            modifier = Modifier
                                .absoluteOffset(
                                    x = textX - 150.dp,
                                    y = textY - 35.dp
                                )
                                .width(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Live3DText(
                                text = overlayText,
                                fontSize = (textFontSize * 1.3f).sp,
                                color = textColor,
                                fontFamily = getFontFamilyFromString(selectedFontFamily),
                                effect3D = selected3DEffect,
                                depth = (effectDepth * 1.3f).toInt(),
                                alignment = textAlignment
                            )
                        }
                    }
                }

                // 4. Tap anywhere to close instruction chip
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Toca la pantalla para salir de vista previa",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// Custom styled 3D Composable text for screen preview rendering
@Composable
fun Live3DText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    fontFamily: FontFamily,
    effect3D: String,
    depth: Int,
    alignment: String
) {
    val weight = FontWeight.Black
    val textAlignmentValue = when (alignment) {
        "left" -> TextAlign.Left
        "right" -> TextAlign.Right
        else -> TextAlign.Center
    }

    Box(contentAlignment = Alignment.Center) {
        // Render 3D layers using small offsets
        if (effect3D != "none") {
            val depthColor = when (effect3D) {
                "extrude_orange" -> Color(0xFFD84315)
                "extrude_black" -> Color(0xFF111111)
                "extrude_white" -> Color(0xFFE0E0E0)
                "isometric" -> Color(0xFF333333)
                else -> Color.DarkGray
            }

            // Stagger multiple offset text composables diagonally to produce high-contrast 3D extrusion
            for (i in depth downTo 1) {
                val factor = i * 0.8f
                Text(
                    text = text,
                    color = depthColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    fontWeight = weight,
                    textAlign = textAlignmentValue,
                    modifier = Modifier.offset(x = factor.dp, y = factor.dp),
                    letterSpacing = 2.sp
                )
            }
        }

        // Top Layer Text
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = weight,
            textAlign = textAlignmentValue,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun EditorTabButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFFFF6D00) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (isActive) Color.Black else Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = if (isActive) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FitOptionCard(
    title: String,
    description: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) Color(0xFFFF6D00).copy(alpha = 0.15f) else Color(0xFF121212))
            .border(
                1.dp,
                if (isActive) Color(0xFFFF6D00) else Color(0xFF2E2E2E),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isActive,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFFFF6D00),
                unselectedColor = Color.LightGray
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                color = if (isActive) Color(0xFFFF6D00) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = Color.LightGray,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

// Helper: Converts customized font key to standard FontFamily
fun getFontFamilyFromString(familyStr: String): FontFamily {
    return when (familyStr) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "sans" -> FontFamily.SansSerif
        "impact" -> FontFamily.Default // Default Bold fallback
        else -> FontFamily.Default
    }
}

// Decode URI from Photo Picker safely into a Bitmap
fun decodeUriToBitmap(context: Context, uri: Uri): Bitmap? {
    var inputStream: InputStream? = null
    return try {
        inputStream = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(inputStream)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        inputStream?.close()
    }
}

// Procedural high-contrast black/orange background if no wallpaper is selected
fun createProceduralGradientBitmap(w: Int, h: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
    }

    // Dynamic gradient
    val shader = android.graphics.LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        0xFFFF6D00.toInt(),
        0xFF000000.toInt(),
        android.graphics.Shader.TileMode.CLAMP
    )
    paint.shader = shader
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null

    // Draw some subtle modern geometric overlay lines
    paint.color = 0x1AFFFFFF
    paint.strokeWidth = 3f
    for (i in 0..10) {
        val y = h * (i / 10f)
        canvas.drawLine(0f, y, w.toFloat(), y, paint)
    }

    return bitmap
}

// Procedural high-definition multi-color custom gradient background
fun createCustomGradientBitmap(
    w: Int,
    h: Int,
    color1: Color,
    color2: Color,
    color3: Color?,
    orientation: String
): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
    }

    val c1 = color1.toArgb()
    val c2 = color2.toArgb()
    val colors = if (color3 != null) {
        intArrayOf(c1, color3.toArgb(), c2)
    } else {
        intArrayOf(c1, c2)
    }

    val positions = if (colors.size == 3) {
        floatArrayOf(0.0f, 0.5f, 1.0f)
    } else {
        null
    }

    val shader = when (orientation) {
        "horizontal" -> android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            colors, positions,
            android.graphics.Shader.TileMode.CLAMP
        )
        "diagonal" -> android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            colors, positions,
            android.graphics.Shader.TileMode.CLAMP
        )
        "radial" -> android.graphics.RadialGradient(
            w / 2f, h / 2f, Math.max(w, h) / 1.1f,
            colors, positions,
            android.graphics.Shader.TileMode.CLAMP
        )
        else -> android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            colors, positions,
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    paint.shader = shader
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null

    // Draw some extremely subtle modern geometric overlay lines
    paint.color = 0x0CFFFFFF
    paint.strokeWidth = 2f
    for (i in 0..12) {
        val y = h * (i / 12f)
        canvas.drawLine(0f, y, w.toFloat(), y, paint)
    }

    return bitmap
}

// --- CORE UTILITY: PROGRAMMATIC HIGH-RESOLUTION WALLPAPER RENDERER ---
// Creates a bitmap exactly sized to native device dimensions (widthPixels x heightPixels)
// without cropping or losing quality, drawing blurred backdrop if necessary, and overlays the customized 3D text.
suspend fun generateHighResWallpaperBitmap(
    context: Context,
    sourceBitmap: Bitmap,
    blurredBitmap: Bitmap,
    nativeW: Int,
    nativeH: Int,
    fitMode: String,
    textStr: String,
    textColor: Color,
    fontFamilyStr: String,
    effect3D: String,
    depth: Int,
    fontSizeSp: Float,
    xPct: Float,
    yPct: Float,
    textAlignment: String
): Bitmap = withContext(Dispatchers.IO) {
    val finalBitmap = Bitmap.createBitmap(nativeW, nativeH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(finalBitmap)
    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    val srcW = sourceBitmap.width
    val srcH = sourceBitmap.height

    // 1. Render Background layer based on fit mode
    when (fitMode) {
        "crop" -> {
            // Fills the screen, keeping aspect ratio (scales up to cover everything, and crops the overflow)
            val scale = Math.max(nativeW.toFloat() / srcW, nativeH.toFloat() / srcH)
            val fitW = srcW * scale
            val fitH = srcH * scale
            val left = (nativeW - fitW) / 2f
            val top = (nativeH - fitH) / 2f
            val destRect = android.graphics.RectF(left, top, left + fitW, top + fitH)
            canvas.drawBitmap(sourceBitmap, null, destRect, paint)
        }
        "stretch" -> {
            val destRect = android.graphics.Rect(0, 0, nativeW, nativeH)
            canvas.drawBitmap(sourceBitmap, null, destRect, paint)
        }
        "solid_fit" -> {
            canvas.drawColor(AndroidColor.BLACK)
            // Centered fit calculation
            val scale = Math.min(nativeW.toFloat() / srcW, nativeH.toFloat() / srcH)
            val fitW = srcW * scale
            val fitH = srcH * scale
            val left = (nativeW - fitW) / 2f
            val top = (nativeH - fitH) / 2f
            val destRect = android.graphics.RectF(left, top, left + fitW, top + fitH)
            canvas.drawBitmap(sourceBitmap, null, destRect, paint)
        }
        "center" -> {
            canvas.drawColor(AndroidColor.BLACK)
            val left = (nativeW - srcW) / 2f
            val top = (nativeH - srcH) / 2f
            canvas.drawBitmap(sourceBitmap, left, top, paint)
        }
        "blur_fit" -> {
            // Draw blurred base stretched to full native dimensions
            val fullDestRect = android.graphics.Rect(0, 0, nativeW, nativeH)
            canvas.drawBitmap(blurredBitmap, null, fullDestRect, paint)

            // Darken backdrop slightly to increase high contrast for overlay text
            val darkOverlayPaint = Paint().apply {
                color = AndroidColor.argb((255 * 0.35f).toInt(), 0, 0, 0)
            }
            canvas.drawRect(0f, 0f, nativeW.toFloat(), nativeH.toFloat(), darkOverlayPaint)

            // Draw sharp centered image
            val scale = Math.min(nativeW.toFloat() / srcW, nativeH.toFloat() / srcH)
            val fitW = srcW * scale
            val fitH = srcH * scale
            val left = (nativeW - fitW) / 2f
            val top = (nativeH - fitH) / 2f
            val destRect = android.graphics.RectF(left, top, left + fitW, top + fitH)
            canvas.drawBitmap(sourceBitmap, null, destRect, paint)
        }
    }

    // 2. Draw 3D Extruded Overlay Text
    if (textStr.isNotEmpty()) {
        // Map FontFamily
        val selectedTypeface = when (fontFamilyStr) {
            "serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            "cursive" -> Typeface.create("sans-serif-condensed", Typeface.ITALIC)
            "impact" -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Scale font size according to native device high resolution
        val baseDensity = context.resources.displayMetrics.density
        val targetSizePx = fontSizeSp * baseDensity * (nativeW.toFloat() / 1080f).coerceAtLeast(1f)

        val alignType = when (textAlignment) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }

        val textPaint = Paint().apply {
            isAntiAlias = true
            isSubpixelText = true
            textSize = targetSizePx
            typeface = selectedTypeface
            textAlign = alignType
        }

        val textX = nativeW * xPct
        val textY = nativeH * yPct

        // Split text by newlines and draw line-by-line
        val lines = textStr.split("\n")
        val lineSpacing = targetSizePx * 1.15f
        val totalHeight = (lines.size - 1) * lineSpacing
        val startY = textY - (totalHeight / 2f)

        // Render 3D Extrusion on Canvas
        if (effect3D != "none") {
            val depthColorInt = when (effect3D) {
                "extrude_orange" -> 0xFFD84315.toInt() // Burned orange
                "extrude_black" -> 0xFF111111.toInt()  // Matte black
                "extrude_white" -> 0xFFE0E0E0.toInt()  // Matte white
                "isometric" -> 0xFF333333.toInt()      // Dark gray
                else -> 0xFF333333.toInt()
            }

            val depthPaint = Paint(textPaint).apply {
                color = depthColorInt
            }

            // Draw multiple layers diagonally offset
            // Scale offset depth according to resolution scale
            val offsetMultiplier = (nativeW.toFloat() / 1080f).coerceAtLeast(1f) * 1.5f
            for (i in depth downTo 1) {
                val dx = i * offsetMultiplier
                lines.forEachIndexed { index, line ->
                    val lineY = startY + (index * lineSpacing)
                    canvas.drawText(line, textX + dx, lineY + dx, depthPaint)
                }
            }
        }

        // Draw final primary front text layer
        textPaint.color = AndroidColor.argb(
            (textColor.alpha * 255).toInt(),
            (textColor.red * 255).toInt(),
            (textColor.green * 255).toInt(),
            (textColor.blue * 255).toInt()
        )
        lines.forEachIndexed { index, line ->
            val lineY = startY + (index * lineSpacing)
            canvas.drawText(line, textX, lineY, textPaint)
        }
    }

    finalBitmap
}

// Directly Apply Wallpaper using Android native WallpaperManager API
suspend fun applyWallpaper(
    context: Context,
    sourceBitmap: Bitmap,
    blurredBitmap: Bitmap,
    nativeW: Int,
    nativeH: Int,
    fitMode: String,
    textStr: String,
    textColor: Color,
    fontFamilyStr: String,
    effect3D: String,
    depth: Int,
    fontSizeSp: Float,
    xPct: Float,
    yPct: Float,
    textAlignment: String,
    which: Int
): Boolean = withContext(Dispatchers.IO) {
    try {
        val wallpaperBitmap = generateHighResWallpaperBitmap(
            context = context,
            sourceBitmap = sourceBitmap,
            blurredBitmap = blurredBitmap,
            nativeW = nativeW,
            nativeH = nativeH,
            fitMode = fitMode,
            textStr = textStr,
            textColor = textColor,
            fontFamilyStr = fontFamilyStr,
            effect3D = effect3D,
            depth = depth,
            fontSizeSp = fontSizeSp,
            xPct = xPct,
            yPct = yPct,
            textAlignment = textAlignment
        )
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            wallpaperManager.setBitmap(wallpaperBitmap, null, true, which)
        } else {
            @Suppress("DEPRECATION")
            wallpaperManager.setBitmap(wallpaperBitmap)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// Save High-Resolution Wallpaper output directly to Android Gallery via Scoped Storage (MediaStore)
suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val imageDetails = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CambiWall")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    var stream: OutputStream? = null
    var uri: Uri? = null

    try {
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageDetails)
        if (uri != null) {
            stream = resolver.openOutputStream(uri)
            if (stream != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    imageDetails.clear()
                    imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, imageDetails, null, null)
                }
                return@withContext true
            }
        }
        false
    } catch (e: Exception) {
        e.printStackTrace()
        // Delete if error
        if (uri != null) {
            resolver.delete(uri, null, null)
        }
        false
    } finally {
        stream?.close()
    }
}

// Simple Robust box blur algorithm in pure Kotlin
fun blurBitmap(sentBitmap: Bitmap, radius: Int): Bitmap {
    val width = sentBitmap.width
    val height = sentBitmap.height
    val pixels = IntArray(width * height)
    sentBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val outPixels = IntArray(width * height)
    val r = radius.coerceIn(1, 100)

    boxBlurHorizontal(pixels, outPixels, width, height, r)
    boxBlurVertical(outPixels, pixels, width, height, r)
    boxBlurHorizontal(pixels, outPixels, width, height, r)
    boxBlurVertical(outPixels, pixels, width, height, r)

    val blurredBitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
    blurredBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return blurredBitmap
}

private fun boxBlurHorizontal(inPixels: IntArray, outPixels: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (y in 0 until height) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var aSum = 0
        val rowOffset = y * width

        for (x in -radius..radius) {
            val clampedX = x.coerceIn(0, width - 1)
            val p = inPixels[rowOffset + clampedX]
            aSum += (p ushr 24) and 0xFF
            rSum += (p ushr 16) and 0xFF
            gSum += (p ushr 8) and 0xFF
            bSum += p and 0xFF
        }

        for (x in 0 until width) {
            val avgA = aSum / div
            val avgR = rSum / div
            val avgG = gSum / div
            val avgB = bSum / div
            outPixels[rowOffset + x] = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB

            val leftX = (x - radius).coerceIn(0, width - 1)
            val rightX = (x + radius + 1).coerceIn(0, width - 1)

            val pLeft = inPixels[rowOffset + leftX]
            val pRight = inPixels[rowOffset + rightX]

            aSum += ((pRight ushr 24) and 0xFF) - ((pLeft ushr 24) and 0xFF)
            rSum += ((pRight ushr 16) and 0xFF) - ((pLeft ushr 16) and 0xFF)
            gSum += ((pRight ushr 8) and 0xFF) - ((pLeft ushr 8) and 0xFF)
            bSum += (pRight and 0xFF) - (pLeft and 0xFF)
        }
    }
}

private fun boxBlurVertical(inPixels: IntArray, outPixels: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (x in 0 until width) {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var aSum = 0

        for (y in -radius..radius) {
            val clampedY = y.coerceIn(0, height - 1)
            val p = inPixels[clampedY * width + x]
            aSum += (p ushr 24) and 0xFF
            rSum += (p ushr 16) and 0xFF
            gSum += (p ushr 8) and 0xFF
            bSum += p and 0xFF
        }

        for (y in 0 until height) {
            val avgA = aSum / div
            val avgR = rSum / div
            val avgG = gSum / div
            val avgB = bSum / div
            outPixels[y * width + x] = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB

            val topY = (y - radius).coerceIn(0, height - 1)
            val bottomY = (y + radius + 1).coerceIn(0, height - 1)

            val pTop = inPixels[topY * width + x]
            val pBottom = inPixels[bottomY * width + x]

            aSum += ((pBottom ushr 24) and 0xFF) - ((pTop ushr 24) and 0xFF)
            rSum += ((pBottom ushr 16) and 0xFF) - ((pTop ushr 16) and 0xFF)
            gSum += ((pBottom ushr 8) and 0xFF) - ((pTop ushr 8) and 0xFF)
            bSum += (pBottom and 0xFF) - (pTop and 0xFF)
        }
    }
}

// --- PHOTO RESTORATION AND ENHANCEMENT ALGORITHMS ---

/**
 * Reconstructs blurry edges and details in old or low-resolution images
 * using a high-fidelity 3x3 sharpening convolution matrix filter.
 */
fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
    if (amount <= 0.05f) return src
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    val outPixels = IntArray(w * h)

    // Staggered weights based on the user-selected amount
    val centerWeight = 1f + 4f * amount
    val neighborWeight = -amount

    // Direct buffer copy of borders to keep edges clean
    System.arraycopy(pixels, 0, outPixels, 0, w)
    for (y in 1 until h - 1) {
        outPixels[y * w] = pixels[y * w]
        outPixels[y * w + w - 1] = pixels[y * w + w - 1]
    }
    System.arraycopy(pixels, (h - 1) * w, outPixels, (h - 1) * w, w)

    // Run Convolution Kernel loop
    for (y in 1 until h - 1) {
        val row = y * w
        val prevRow = (y - 1) * w
        val nextRow = (y + 1) * w
        for (x in 1 until w - 1) {
            val idx = row + x

            val c = pixels[idx]
            val t = pixels[prevRow + x]
            val b = pixels[nextRow + x]
            val l = pixels[row + x - 1]
            val r = pixels[row + x + 1]

            val a = (c ushr 24) and 0xFF

            // Red Channel
            val cr = (c ushr 16) and 0xFF
            val tr = (t ushr 16) and 0xFF
            val br = (b ushr 16) and 0xFF
            val lr = (l ushr 16) and 0xFF
            val rr = (r ushr 16) and 0xFF
            var nr = (cr * centerWeight + (tr + br + lr + rr) * neighborWeight).toInt()
            nr = nr.coerceIn(0, 255)

            // Green Channel
            val cg = (c ushr 8) and 0xFF
            val tg = (t ushr 8) and 0xFF
            val bg = (b ushr 8) and 0xFF
            val lg = (l ushr 8) and 0xFF
            val rg = (r ushr 8) and 0xFF
            var ng = (cg * centerWeight + (tg + bg + lg + rg) * neighborWeight).toInt()
            ng = ng.coerceIn(0, 255)

            // Blue Channel
            val cb = c and 0xFF
            val tb = t and 0xFF
            val bb = b and 0xFF
            val lb = l and 0xFF
            val rb = r and 0xFF
            var nb = (cb * centerWeight + (tb + bb + lb + rb) * neighborWeight).toInt()
            nb = nb.coerceIn(0, 255)

            outPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    val dest = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    dest.setPixels(outPixels, 0, w, 0, 0, w, h)
    return dest
}

/**
 * Automatically or manually enhances the color spectrum, contrast, and brightness
 * to revive dull, faded, or yellowed colors typical of old physical photographs.
 */
fun enhanceColorBitmap(
    src: Bitmap,
    autoEnhance: Boolean,
    brightness: Float,
    contrast: Float,
    saturation: Float
): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    // Blend manual input with premium smart auto-restoration vectors
    val finalBrightness = if (autoEnhance) brightness + 12f else brightness
    val finalContrast = if (autoEnhance) contrast * 1.22f else contrast
    val finalSaturation = if (autoEnhance) saturation * 1.30f else saturation

    if (!autoEnhance && brightness == 0f && contrast == 1.0f && saturation == 1.0f) {
        return src
    }

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        var r = (p ushr 16) and 0xFF
        var g = (p ushr 8) and 0xFF
        var b = p and 0xFF

        // 1. Shift Luminance (Brightness)
        if (finalBrightness != 0f) {
            r = (r + finalBrightness).toInt().coerceIn(0, 255)
            g = (g + finalBrightness).toInt().coerceIn(0, 255)
            b = (b + finalBrightness).toInt().coerceIn(0, 255)
        }

        // 2. Center Contrast Expansion (relative to 128 mid-gray anchor)
        if (finalContrast != 1.0f) {
            r = (((r - 128) * finalContrast) + 128).toInt().coerceIn(0, 255)
            g = (((g - 128) * finalContrast) + 128).toInt().coerceIn(0, 255)
            b = (((b - 128) * finalContrast) + 128).toInt().coerceIn(0, 255)
        }

        // 3. Vibrancy Expansion (using high-accuracy NTSC color weights for grayscale extrapolation)
        if (finalSaturation != 1.0f) {
            val lum = (0.299f * r + 0.587f * g + 0.114f * b)
            r = (lum + (r - lum) * finalSaturation).toInt().coerceIn(0, 255)
            g = (lum + (g - lum) * finalSaturation).toInt().coerceIn(0, 255)
            b = (lum + (b - lum) * finalSaturation).toInt().coerceIn(0, 255)
        }

        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    val dest = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    dest.setPixels(pixels, 0, w, 0, 0, w, h)
    return dest
}

/**
 * Smooths high-frequency film grain and camera sensor noise from old physical photos
 * using a high-performance edge-preserving bilateral-filtering approximation.
 */
fun denoiseBitmap(src: Bitmap, amount: Float): Bitmap {
    if (amount <= 0.05f) return src
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    val outPixels = IntArray(w * h)

    System.arraycopy(pixels, 0, outPixels, 0, w)
    for (y in 1 until h - 1) {
        outPixels[y * w] = pixels[y * w]
        outPixels[y * w + w - 1] = pixels[y * w + w - 1]
    }
    System.arraycopy(pixels, (h - 1) * w, outPixels, (h - 1) * w, w)

    for (y in 1 until h - 1) {
        val row = y * w
        val prevRow = (y - 1) * w
        val nextRow = (y + 1) * w
        for (x in 1 until w - 1) {
            val idx = row + x

            val c = pixels[idx]
            val cr = (c ushr 16) and 0xFF
            val cg = (c ushr 8) and 0xFF
            val cb = c and 0xFF

            var rSum = cr.toFloat()
            var gSum = cg.toFloat()
            var bSum = cb.toFloat()
            var wSum = 1f

            val neighbors = intArrayOf(
                pixels[prevRow + x],
                pixels[nextRow + x],
                pixels[row + x - 1],
                pixels[row + x + 1]
            )

            for (p in neighbors) {
                val nr = (p ushr 16) and 0xFF
                val ng = (p ushr 8) and 0xFF
                val nb = p and 0xFF

                // Fast Manhattan color difference
                val dist = Math.abs(cr - nr) + Math.abs(cg - ng) + Math.abs(cb - nb)

                // Edge-preserving factor: pixels with low color divergence are weighted higher,
                // while sharp outlines (high divergence) are skipped to maintain perfect sharpness!
                val weight = if (dist < 80) 1.0f else 0.1f
                rSum += nr * weight
                gSum += ng * weight
                bSum += nb * weight
                wSum += weight
            }

            val finalR = (rSum / wSum).toInt().coerceIn(0, 255)
            val finalG = (gSum / wSum).toInt().coerceIn(0, 255)
            val finalB = (bSum / wSum).toInt().coerceIn(0, 255)
            val a = (c ushr 24) and 0xFF

            // Linearly interpolate based on user strength preference
            val rSmooth = (cr + (finalR - cr) * amount).toInt().coerceIn(0, 255)
            val gSmooth = (cg + (finalG - cg) * amount).toInt().coerceIn(0, 255)
            val bSmooth = (cb + (finalB - cb) * amount).toInt().coerceIn(0, 255)

            outPixels[idx] = (a shl 24) or (rSmooth shl 16) or (gSmooth shl 8) or bSmooth
        }
    }

    val dest = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
    dest.setPixels(outPixels, 0, w, 0, 0, w, h)
    return dest
}

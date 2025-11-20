package com.filetransfer.app

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FileTransferTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileTransferScreen()
                }
            }
        }
    }

    // -------------------------------
    // Auto-detect SD card UUIDs
    // -------------------------------
    private fun getSdCardPaths(): List<String> {
        val storageDir = File("/storage")
        return storageDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("[A-F0-9]{4}-[A-F0-9]{4}")) }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    private fun resolveRealPathFromTreeUri(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")

        if (split.size < 2) return null

        val uuid = split[0]
        val relativePath = split[1]

        val sdPaths = getSdCardPaths()
        val sdRoot = sdPaths.find { it.contains(uuid, ignoreCase = true) }

        return if (sdRoot != null) {
            "$sdRoot/$relativePath"
        } else null
    }

        // -------------------------------
    // UI Theme & Main Screen
    // -------------------------------
    @Composable
    fun FileTransferTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF6366F1),
                secondary = Color(0xFF8B5CF6),
                tertiary = Color(0xFFEC4899),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = Color(0xFFF1F5F9),
                onSurface = Color(0xFFF1F5F9)
            ),
            content = content
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FileTransferScreen() {
        var sourceUri by remember { mutableStateOf<Uri?>(null) }
        var destUri by remember { mutableStateOf<Uri?>(null) }
        var sourceName by remember { mutableStateOf("") }
        var destName by remember { mutableStateOf("") }
        var isProcessing by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var showSuccess by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var operationType by remember { mutableStateOf("copy") }
        val scope = rememberCoroutineScope()

        val sourcePickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                sourceUri = it
                sourceName = getFileName(it)
            }
        }

        val destPickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                destUri = it
                destName = getDirectoryName(it)
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // no-op; we request to show the prompt
        }

        LaunchedEffect(Unit) {
            requestPermissions(permissionLauncher)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E293B),
                            Color(0xFF334155)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                AnimatedHeader()

                Spacer(modifier = Modifier.height(48.dp))

                OperationTypeSelector(operationType) { operationType = it }

                Spacer(modifier = Modifier.height(32.dp))

                SelectionCard(
                    title = "Select Source File",
                    fileName = sourceName,
                    icon = Icons.Default.Description,
                    gradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                    onClick = { sourcePickerLauncher.launch(arrayOf("*/*")) }
                )

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedArrow(isProcessing)

                Spacer(modifier = Modifier.height(20.dp))

                SelectionCard(
                    title = "Select Destination Folder",
                    fileName = destName,
                    icon = Icons.Default.Folder,
                    gradient = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
                    onClick = { destPickerLauncher.launch(null) }
                )

                Spacer(modifier = Modifier.height(40.dp))

                ActionButton(
                    text = if (operationType == "copy") "Copy File" else "Move File",
                    enabled = sourceUri != null && destUri != null && !isProcessing,
                    isProcessing = isProcessing,
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            progress = 0f
                            errorMessage = null
                            showSuccess = false

                            val result = transferFile(
                                sourceUri!!,
                                destUri!!,
                                operationType == "move"
                            ) { prog ->
                                progress = prog
                            }

                            isProcessing = false
                            if (result) {
                                showSuccess = true
                                if (operationType == "move") {
                                    sourceUri = null
                                    sourceName = ""
                                }
                            } else {
                                errorMessage = "Transfer failed. Please check permissions."
                            }
                        }
                    }
                )

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressWithPercentage(progress)
                }

                AnimatedVisibility(
                    visible = showSuccess,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    SuccessIndicator()
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    ErrorMessage(it)
                }
            }
        }
    }

    // -------------------------------
    // Reusable UI components
    // -------------------------------
    @Composable
    fun AnimatedHeader() {
        val infiniteTransition = rememberInfiniteTransition(label = "header")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF6366F1), Color(0xFFEC4899))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "File Transfer Pro",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Preserve timestamps with ease",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFCBD5E1)
            )
        }
    }

    @Composable
    fun OperationTypeSelector(selected: String, onSelect: (String) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("copy" to "Copy", "move" to "Move").forEach { (type, label) ->
                Button(
                    onClick = { onSelect(type) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected == type)
                            Color(0xFF6366F1) else Color.Transparent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

        @Composable
    fun SelectionCard(
        title: String,
        fileName: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        gradient: List<Color>,
        onClick: () -> Unit
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(gradient.map { it.copy(alpha = 0.1f) }))
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(gradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = fileName.ifEmpty { "Tap to select" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (fileName.isEmpty()) Color(0xFF94A3B8) else Color(0xFF6366F1)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF64748B)
                    )
                }
            }
        }
    }

    @Composable
    fun AnimatedArrow(isProcessing: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "arrow")
        val offsetY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isProcessing) 10f else 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "offset"
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .offset(y = offsetY.dp)
                .clip(CircleShape)
                .background(Color(0xFF334155)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    @Composable
    fun ActionButton(
        text: String,
        enabled: Boolean,
        isProcessing: Boolean,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6366F1),
                disabledContainerColor = Color(0xFF334155)
            )
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Processing...", fontWeight = FontWeight.Bold)
            } else {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun CircularProgressWithPercentage(progress: Float) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(80.dp),
                    color = Color(0xFF6366F1),
                    strokeWidth = 6.dp,
                    trackColor = Color(0xFF334155)
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun SuccessIndicator() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Transfer completed successfully!",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    fun ErrorMessage(message: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = message,
                    color = Color(0xFFEF4444),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // -------------------------------
    // Permissions (Android 11+ SAF)
    // -------------------------------
    private fun requestPermissions(
        launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

            if (permissions.isNotEmpty()) launcher.launch(permissions.toTypedArray())
        }
    }

        // -------------------------------
    // SAF Timestamp Update (Fallback)
    // -------------------------------
    private fun updateLastModifiedSaf(uri: Uri, lastModified: Long) {
        try {
            val values = ContentValues().apply {
                put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified)
            }
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) { }
    }

    // -------------------------------
    // REAL PATH Resolver for SD Card + Internal Storage
    // -------------------------------
    private fun getRealPath(uri: Uri): String? {
        // SAF tree URI → SD card or Internal storage
        if (uri.toString().contains("tree")) {
            return resolveRealPathFromTreeUri(uri)
        }

        // Normal content file → copy to temp, no real path (fallback only)
        if (uri.scheme == "content") return null

        return uri.path
    }

    // -------------------------------
    // MAIN FILE TRANSFER FUNCTION
    // -------------------------------
    private suspend fun transferFile(
        sourceUri: Uri,
        destUri: Uri,
        isMove: Boolean,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            // Get Names
            val srcName = getFileName(sourceUri)

            // Get REAL paths (if possible)
            val realSourcePath = getRealPath(sourceUri)
            val realDestFolder = getRealPath(destUri)

            // Get timestamp of original file
            val tempSourceFile = getFileFromUri(sourceUri)
            val originalLastModified = tempSourceFile?.lastModified() ?: 0L

            // ---------------------------------------------------------
            // 1) BEST CASE → Real paths exist → DIRECT FILE COPY
            // ---------------------------------------------------------
            if (realSourcePath != null && realDestFolder != null) {

                val srcFile = File(realSourcePath)
                val dstFile = File(realDestFolder, srcName)

                FileInputStream(srcFile).use { input ->
                    FileOutputStream(dstFile).use { output ->

                        val buffer = ByteArray(10240)
                        var total = 0L
                        val size = srcFile.length().coerceAtLeast(1)

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            withContext(Dispatchers.Main) {
                                onProgress(total.toFloat() / size.toFloat())
                            }
                        }
                        output.flush()
                    }
                }

                // → TIMESTAMP PRESERVED PERFECTLY
                dstFile.setLastModified(originalLastModified)

                if (isMove) srcFile.delete()

                return@withContext true
            }

            // ---------------------------------------------------------
            // 2) SAF CASE → Fallback using DocumentsContract
            // ---------------------------------------------------------
            val destDocUri = DocumentsContract.buildDocumentUriUsingTree(
                destUri,
                DocumentsContract.getTreeDocumentId(destUri)
            )

            val mime = contentResolver.getType(sourceUri) ?: "application/octet-stream"

            val newFileUri = DocumentsContract.createDocument(
                contentResolver,
                destDocUri,
                mime,
                srcName
            ) ?: return@withContext false

            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(newFileUri)?.use { output ->

                    val buffer = ByteArray(8192)
                    var total = 0L
                    val size = tempSourceFile?.length()?.coerceAtLeast(1L) ?: 1L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        total += read
                        withContext(Dispatchers.Main) {
                            onProgress(total.toFloat() / size.toFloat())
                        }
                    }
                    output.flush()
                }
            }

            // Fallback timestamp update
            updateLastModifiedSaf(newFileUri, originalLastModified)

            if (isMove) {
                try { DocumentsContract.deleteDocument(contentResolver, sourceUri) }
                catch (_: Exception) {}
            }

            true

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // -------------------------------
    // Convert URI → File (fallback)
    // -------------------------------
    private fun getFileFromUri(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                File(uri.path!!)
            } else if (uri.scheme == "content") {
                val name = getFileName(uri)
                val tmp = File(cacheDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                    }
                }
                tmp
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // -------------------------------
    // Get file name
    // -------------------------------
    private fun getFileName(uri: Uri): String {
        var name = ""

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = cursor.getString(idx)
            }
        }

        return name.ifEmpty { "unknown_file" }
    }

    // -------------------------------
    // Get folder name for UI
    // -------------------------------
    private fun getDirectoryName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfter(":") ?: "Selected Folder"
    }
}

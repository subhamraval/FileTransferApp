package com.filetransfer.app

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
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
            if (permissions.values.all { it }) {
                // Permissions granted
            }
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

                // Header with animation
                AnimatedHeader()

                Spacer(modifier = Modifier.height(48.dp))

                // Operation Type Toggle
                OperationTypeSelector(operationType) { operationType = it }

                Spacer(modifier = Modifier.height(32.dp))

                // Source Selection Card
                SelectionCard(
                    title = "Select Source File",
                    fileName = sourceName,
                    icon = Icons.Default.Description,
                    gradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                    onClick = { sourcePickerLauncher.launch(arrayOf("*/*")) }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Arrow Animation
                AnimatedArrow(isProcessing)

                Spacer(modifier = Modifier.height(20.dp))

                // Destination Selection Card
                SelectionCard(
                    title = "Select Destination Folder",
                    fileName = destName,
                    icon = Icons.Default.Folder,
                    gradient = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
                    onClick = { destPickerLauncher.launch(null) }
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Action Button
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

                // Progress Indicator
                if (isProcessing) {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressWithPercentage(progress)
                }

                // Success Animation
                AnimatedVisibility(
                    visible = showSuccess,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    SuccessIndicator()
                }

                // Error Message
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    ErrorMessage(it)
                }
            }
        }
    }

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
    fun AnimatedArrow(isAnimating: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "arrow")
        val offsetY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isAnimating) 10f else 0f,
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
                    strokeWidth = 6.dp
                )
                Text(
                    text = "${'$'}{(progress * 100).toInt()}%",
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

    private fun requestPermissions(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (permissions.isNotEmpty()) {
                launcher.launch(permissions.toTypedArray())
            }
        }
    }

    private suspend fun transferFile(
        sourceUri: Uri,
        destUri: Uri,
        isMove: Boolean,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sourceFile = getFileFromUri(sourceUri) ?: return@withContext false
            val fileName = sourceFile.name
            val lastModified = sourceFile.lastModified()

            val destDocUri = DocumentsContract.buildDocumentUriUsingTree(
                destUri,
                DocumentsContract.getTreeDocumentId(destUri)
            )

            val mimeType = contentResolver.getType(sourceUri) ?: "*/*"
            val newFileUri = DocumentsContract.createDocument(
                contentResolver,
                destDocUri,
                mimeType,
                fileName
            ) ?: return@withContext false

            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(newFileUri)?.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val fileSize = sourceFile.length().coerceAtLeast(1L)

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        withContext(Dispatchers.Main) {
                            onProgress(totalBytes.toFloat() / fileSize)
                        }
                    }
                    output.flush()
                }
            }

            // Preserve timestamp if possible (may not work for document URIs on all devices)
            try {
                val newFile = getFileFromUri(newFileUri)
                newFile?.setLastModified(lastModified)
            } catch (ignored: Exception) {}

            if (isMove) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, sourceUri)
                } catch (ignored: Exception) {}
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            // Try to resolve display name and copy to cache if it's a content URI
            if (uri.scheme == "content") {
                val name = getFileName(uri)
                val tempFile = File(cacheDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } else {
                val path = uri.path ?: return null
                File(path)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name.ifEmpty { "Unknown" }
    }

    private fun getDirectoryName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
    }
}

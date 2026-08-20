package com.example.btcall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter

object LogCsvExporter {
    fun exportAndShareLogs(context: Context, logs: List<SignalPacket>) {
        if (logs.isEmpty()) {
            Toast.makeText(context, "协议日志为空，无法导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Save to app internal files directory under "recordings" to match file provider
            val outputDir = File(context.filesDir, "recordings")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val csvFile = File(outputDir, "BTCall_Protocol_Logs_${System.currentTimeMillis()}.csv")
            val writer = FileWriter(csvFile)
            
            // Add UTF-8 BOM so Excel opens Chinese logs correctly
            writer.write('\ufeff'.code)
            
            // Header
            writer.write("Timestamp,Type,Direction,Content\n")

            // Write logs
            for (log in logs) {
                val escapedContent = log.content.replace("\"", "\"\"")
                writer.write("\"${log.timestamp}\",\"${log.type}\",\"${log.direction}\",\"$escapedContent\"\n")
            }
            writer.flush()
            writer.close()

            BluetoothCallEngine.instance?.addSystemLogDirect(
                "CSV",
                "EXPORT_OK",
                "协议日志成功导出至: ${csvFile.name} (大小: ${csvFile.length()} 字节)"
            )

            // Share using FileProvider
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, csvFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/comma-separated-values"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "蓝牙通话协议控制层日志 (CSV)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "分享协议日志 CSV")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            
            BluetoothCallEngine.instance?.addSystemLogDirect("CSV", "SHARE_LAUNCH", "系统分享面板成功拉起")
        } catch (e: Exception) {
            val errMsg = "导出或分享日志失败: ${e.localizedMessage}"
            BluetoothCallEngine.instance?.addSystemLogDirect("CSV", "EXPORT_ERR", errMsg)
            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
        }
    }
}

package com.example.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.data.*
import com.example.viewmodel.POSViewModel
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupAndExcelUtils {

    fun requestAllFilesAccessPermission(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:" + context.packageName)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // 1. AUTO DAILY BACKUP TO UNMobilePosOnline FOLDER
    fun performAutoDailyBackup(context: Context): String {
        return try {
            val db = AppDatabase.getDatabase(context)
            try {
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL);")
            } catch (_: Exception) {}

            val dbFile = context.getDatabasePath("unpatch_pos_db")
            if (!dbFile.exists()) {
                return "Database file not found"
            }

            val dateStr = SimpleDateFormat("ddMMyy", Locale.US).format(Date())
            val targetFileName = "$dateStr.db" // e.g., 310726.db or 010826.db

            // Target folder: Internal Storage UNMobilePosOnline only
            val targetDirs = listOfNotNull(
                File(Environment.getExternalStorageDirectory(), "UNMobilePosOnline"),
                context.getExternalFilesDir(null)?.let { File(it, "UNMobilePosOnline") }
            )

            var successCount = 0

            for (dir in targetDirs) {
                try {
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }

                    // Auto replace old backup files (*.db) in UNMobilePosOnline with the new date filename
                    val existingFiles = dir.listFiles { _, name -> name.endsWith(".db", ignoreCase = true) }
                    existingFiles?.forEach { oldFile ->
                        if (oldFile.name != targetFileName) {
                            oldFile.delete() // Replace old day backup (e.g., 310726.db -> 010826.db)
                        }
                    }

                    val backupFile = File(dir, targetFileName)
                    dbFile.inputStream().use { input ->
                        FileOutputStream(backupFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    try {
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(backupFile.absolutePath), null, null)
                    } catch (_: Exception) {}

                    successCount++
                } catch (_: Exception) {
                    // Ignore directory permission failures on specific paths
                }
            }

            if (successCount > 0) {
                "Auto backup success: $targetFileName"
            } else {
                "Auto backup failed to write to folder"
            }
        } catch (e: Exception) {
            "Auto backup error: ${e.message}"
        }
    }

    // CSV Helper function to escape text fields for CSV
    private fun escapeCsvField(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    // 2. EXCEL EXPORT FUNCTIONS
    fun exportProductsToCSV(context: Context, products: List<Product>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF") // UTF-8 BOM for Excel
        sb.append("Barcode,Product Name,Group,Purchase Price,Selling Price,Unit,Stock Quantity,Alert Quantity,Note\n")

        for (p in products) {
            sb.append("${escapeCsvField(p.barcode)},")
            sb.append("${escapeCsvField(p.name)},")
            sb.append("${escapeCsvField(p.groupName)},")
            sb.append("${p.purchasePrice},")
            sb.append("${p.sellingPrice},")
            sb.append("${escapeCsvField(p.unit)},")
            sb.append("${p.quantity},")
            sb.append("${p.alertQuantity},")
            sb.append("${escapeCsvField(p.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Products_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    fun exportCustomersToCSV(context: Context, customers: List<Customer>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("Customer Name,Phone,Address,Note\n")

        for (c in customers) {
            sb.append("${escapeCsvField(c.name)},")
            sb.append("${escapeCsvField(c.phone)},")
            sb.append("${escapeCsvField(c.address)},")
            sb.append("${escapeCsvField(c.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Customers_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    fun exportSuppliersToCSV(context: Context, suppliers: List<Supplier>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("Supplier Name,Phone,Address,Note\n")

        for (s in suppliers) {
            sb.append("${escapeCsvField(s.name)},")
            sb.append("${escapeCsvField(s.phone)},")
            sb.append("${escapeCsvField(s.address)},")
            sb.append("${escapeCsvField(s.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Suppliers_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    fun exportSalesToCSV(context: Context, vouchers: List<Voucher>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("Voucher No,Date,Customer,Cashier,Payment Method,Total Items,Total Amount,Paid Amount,Change Amount,Note\n")

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        for (v in vouchers.filter { !it.isPurchase && it.isCompleted }) {
            val dateStr = sdf.format(Date(v.timestamp))
            sb.append("${escapeCsvField(v.receiptNo)},")
            sb.append("${escapeCsvField(dateStr)},")
            sb.append("${escapeCsvField(v.customerName)},")
            sb.append("${escapeCsvField(v.cashierName)},")
            sb.append("${escapeCsvField(v.paymentMethod)},")
            sb.append("${v.totalItems},")
            sb.append("${v.totalAmount},")
            sb.append("${v.paidAmount},")
            sb.append("${v.changeAmount},")
            sb.append("${escapeCsvField(v.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Sales_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    fun exportPurchasesToCSV(context: Context, vouchers: List<Voucher>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("Voucher No,Date,Supplier,Cashier,Payment Method,Total Items,Total Amount,Paid Amount,Change Amount,Note\n")

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        for (v in vouchers.filter { it.isPurchase && it.isCompleted }) {
            val dateStr = sdf.format(Date(v.timestamp))
            sb.append("${escapeCsvField(v.receiptNo)},")
            sb.append("${escapeCsvField(dateStr)},")
            sb.append("${escapeCsvField(v.customerName)},")
            sb.append("${escapeCsvField(v.cashierName)},")
            sb.append("${escapeCsvField(v.paymentMethod)},")
            sb.append("${v.totalItems},")
            sb.append("${v.totalAmount},")
            sb.append("${v.paidAmount},")
            sb.append("${v.changeAmount},")
            sb.append("${escapeCsvField(v.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Purchases_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    fun exportExpensesToCSV(context: Context, expenses: List<Expense>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("Category,Description,Amount,Payment Method,Date,Note\n")

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        for (e in expenses) {
            val dateStr = if (e.dateString.isNotEmpty()) e.dateString else sdf.format(Date(e.timestamp))
            sb.append("${escapeCsvField(e.categoryName)},")
            sb.append("${escapeCsvField(e.description)},")
            sb.append("${e.amount},")
            sb.append("${escapeCsvField(e.paymentMethod)},")
            sb.append("${escapeCsvField(dateStr)},")
            sb.append("${escapeCsvField(e.note)}\n")
        }
        return saveCsvToFolder(context, "UN_POS_Expenses_${SimpleDateFormat("ddMMyy", Locale.US).format(Date())}.csv", sb.toString())
    }

    private fun saveCsvToFolder(context: Context, fileName: String, content: String): String {
        val targetDirs = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "UNMobilePosOnline/Excel"),
            context.getExternalFilesDir(null)?.let { File(it, "UNMobilePosOnline/Excel") }
        )

        var savedPath = ""
        for (dir in targetDirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeText(content, Charsets.UTF_8)
                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                } catch (_: Exception) {}
                if (savedPath.isEmpty()) savedPath = file.name
            } catch (_: Exception) {}
        }
        return if (savedPath.isNotEmpty()) "Excel Export Successful: $fileName (Saved in UNMobilePosOnline folder)" else "Export Failed"
    }

    // CSV Line Parser (handles quoted values with commas and escaped quotes inside)
    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    private fun parseFlexibleDateTimestamp(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return System.currentTimeMillis()
        trimmed.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }
        val patterns = listOf(
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy hh:mm a",
            "dd/MM/yyyy",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "dd-MM-yyyy HH:mm",
            "dd-MM-yyyy",
            "MM/dd/yyyy HH:mm",
            "MM/dd/yyyy"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    // 3. EXCEL IMPORT FUNCTIONS
    fun importProductsFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var isFirstLine = true
                reader.forEachLine { rawLine ->
                    val line = rawLine.replace("\uFEFF", "").trim()
                    if (line.isNotEmpty()) {
                        if (isFirstLine) {
                            isFirstLine = false
                            if (line.contains("Name", ignoreCase = true) || line.contains("Barcode", ignoreCase = true)) {
                                return@forEachLine
                            }
                        }
                        val tokens = parseCsvLine(line)
                        if (tokens.size >= 2) {
                            val barcode = tokens.getOrNull(0) ?: ""
                            val name = tokens.getOrNull(1) ?: ""
                            val groupName = tokens.getOrNull(2) ?: "General"
                            val purchasePrice = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                            val sellingPrice = tokens.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                            val unit = tokens.getOrNull(5) ?: ""
                            val quantity = tokens.getOrNull(6)?.toIntOrNull() ?: 0
                            val alertQuantity = tokens.getOrNull(7)?.toIntOrNull() ?: 5
                            val note = tokens.getOrNull(8) ?: ""

                            if (name.isNotEmpty()) {
                                val product = Product(
                                    barcode = barcode,
                                    name = name,
                                    groupName = groupName,
                                    purchasePrice = purchasePrice,
                                    sellingPrice = sellingPrice,
                                    unit = unit,
                                    quantity = quantity,
                                    alertQuantity = alertQuantity,
                                    note = note,
                                    trackStock = true
                                )
                                viewModel.insertDirectProduct(product)
                                count++
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun importCustomersFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var isFirstLine = true
                reader.forEachLine { rawLine ->
                    val line = rawLine.replace("\uFEFF", "").trim()
                    if (line.isNotEmpty()) {
                        if (isFirstLine) {
                            isFirstLine = false
                            if (line.contains("Customer", ignoreCase = true) || line.contains("Name", ignoreCase = true)) {
                                return@forEachLine
                            }
                        }
                        val tokens = parseCsvLine(line)
                        if (tokens.isNotEmpty()) {
                            val name = tokens.getOrNull(0) ?: ""
                            val phone = tokens.getOrNull(1) ?: ""
                            val address = tokens.getOrNull(2) ?: ""
                            val note = tokens.getOrNull(3) ?: ""
                            if (name.isNotEmpty()) {
                                viewModel.addCustomer(name = name, phone = phone, address = address, note = note, onSuccess = {})
                                count++
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun importSuppliersFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var isFirstLine = true
                reader.forEachLine { rawLine ->
                    val line = rawLine.replace("\uFEFF", "").trim()
                    if (line.isNotEmpty()) {
                        if (isFirstLine) {
                            isFirstLine = false
                            if (line.contains("Supplier", ignoreCase = true) || line.contains("Name", ignoreCase = true)) {
                                return@forEachLine
                            }
                        }
                        val tokens = parseCsvLine(line)
                        if (tokens.isNotEmpty()) {
                            val name = tokens.getOrNull(0) ?: ""
                            val phone = tokens.getOrNull(1) ?: ""
                            val address = tokens.getOrNull(2) ?: ""
                            val note = tokens.getOrNull(3) ?: ""
                            if (name.isNotEmpty()) {
                                viewModel.addSupplier(name = name, phone = phone, address = address, note = note, onSuccess = {})
                                count++
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun importSalesFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        return importVouchersFromCSV(context, uri, viewModel, isPurchase = false)
    }

    fun importPurchasesFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        return importVouchersFromCSV(context, uri, viewModel, isPurchase = true)
    }

    private fun importVouchersFromCSV(context: Context, uri: Uri, viewModel: POSViewModel, isPurchase: Boolean): Int {
        val importedVouchers = mutableListOf<Voucher>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var isFirstLine = true
                reader.forEachLine { rawLine ->
                    val line = rawLine.replace("\uFEFF", "").trim()
                    if (line.isNotEmpty()) {
                        if (isFirstLine) {
                            isFirstLine = false
                            if (line.contains("Voucher", ignoreCase = true) ||
                                (line.contains("Date", ignoreCase = true) && line.contains("Total Amount", ignoreCase = true))
                            ) {
                                return@forEachLine
                            }
                        }
                        val tokens = parseCsvLine(line)
                        if (tokens.size >= 2) {
                            val rawReceiptNo = tokens.getOrNull(0)?.trim() ?: ""
                            val rawDate = tokens.getOrNull(1)?.trim() ?: ""
                            val timestamp = parseFlexibleDateTimestamp(rawDate)
                            val receiptNo = if (rawReceiptNo.isNotEmpty()) {
                                rawReceiptNo
                            } else {
                                val prefix = if (isPurchase) "P" else "A"
                                val dayCode = SimpleDateFormat("yyMMdd", Locale.US).format(Date(timestamp))
                                "$prefix$dayCode-${(10000000..99999999).random()}"
                            }
                            val customerOrSupplier = tokens.getOrNull(2)?.trim()?.ifEmpty { "Not Register" } ?: "Not Register"
                            val cashierName = tokens.getOrNull(3)?.trim() ?: ""
                            val paymentMethod = tokens.getOrNull(4)?.trim()?.ifEmpty { "CASH" } ?: "CASH"
                            val totalItems = tokens.getOrNull(5)?.replace(",", "")?.toIntOrNull() ?: 0
                            val totalAmount = tokens.getOrNull(6)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val paidAmount = tokens.getOrNull(7)?.replace(",", "")?.toDoubleOrNull() ?: totalAmount
                            val changeAmount = tokens.getOrNull(8)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val note = tokens.getOrNull(9)?.trim() ?: ""

                            if (rawReceiptNo.isNotEmpty() || totalAmount > 0.0) {
                                val balanceAmount = (totalAmount - paidAmount).coerceAtLeast(0.0)
                                importedVouchers.add(
                                    Voucher(
                                        receiptNo = receiptNo,
                                        timestamp = timestamp,
                                        cashierName = cashierName,
                                        totalAmount = totalAmount,
                                        totalItems = totalItems,
                                        customerName = customerOrSupplier,
                                        paymentMethod = paymentMethod,
                                        isCompleted = true,
                                        isPurchase = isPurchase,
                                        paidAmount = paidAmount,
                                        changeAmount = changeAmount,
                                        balanceAmount = balanceAmount,
                                        note = note,
                                        discount = 0.0,
                                        fee = 0.0,
                                        isSynced = false
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (importedVouchers.isNotEmpty()) {
                viewModel.importVouchersDirect(importedVouchers)
            }
        } catch (_: Exception) {}
        return importedVouchers.size
    }

    fun importExpensesFromCSV(context: Context, uri: Uri, viewModel: POSViewModel): Int {
        val importedExpenses = mutableListOf<Expense>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var isFirstLine = true
                reader.forEachLine { rawLine ->
                    val line = rawLine.replace("\uFEFF", "").trim()
                    if (line.isNotEmpty()) {
                        if (isFirstLine) {
                            isFirstLine = false
                            if (line.contains("Category", ignoreCase = true) && line.contains("Amount", ignoreCase = true)) {
                                return@forEachLine
                            }
                        }
                        val tokens = parseCsvLine(line)
                        if (tokens.size >= 3) {
                            val categoryName = tokens.getOrNull(0)?.trim()?.ifEmpty { "အထွေထွေ" } ?: "အထွေထွေ"
                            val description = tokens.getOrNull(1)?.trim() ?: ""
                            val amount = tokens.getOrNull(2)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val paymentMethod = tokens.getOrNull(3)?.trim()?.ifEmpty { "CASH" } ?: "CASH"
                            val rawDate = tokens.getOrNull(4)?.trim() ?: ""
                            val note = tokens.getOrNull(5)?.trim() ?: ""

                            if (amount > 0.0 || description.isNotEmpty()) {
                                val timestamp = parseFlexibleDateTimestamp(rawDate)
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
                                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(timestamp)).uppercase()
                                importedExpenses.add(
                                    Expense(
                                        categoryName = categoryName,
                                        description = description,
                                        amount = amount,
                                        paymentMethod = paymentMethod,
                                        note = note,
                                        timestamp = timestamp,
                                        dateString = dateStr,
                                        timeString = timeStr
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (importedExpenses.isNotEmpty()) {
                viewModel.importExpensesDirect(importedExpenses)
            }
        } catch (_: Exception) {}
        return importedExpenses.size
    }
}

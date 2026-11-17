package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL

object SyncManager {
    private const val TAG = "POSSyncManager"

    private fun HttpURLConnection.applyAuthHeaders(authToken: String, deviceId: String) {
        if (authToken.isNotBlank()) {
            setRequestProperty("Authorization", "Bearer $authToken")
        }
        if (deviceId.isNotBlank()) {
            setRequestProperty("X-Device-Id", deviceId)
        }
    }

    private fun safeDouble(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun Writer.writeJsonStr(value: String?) {
        write(JSONObject.quote(value ?: ""))
    }

    data class SyncResult(
        val success: Boolean,
        val syncedVouchersCount: Int = 0,
        val syncedProductsCount: Int = 0,
        val syncedCustomersCount: Int = 0,
        val message: String = ""
    )

    data class PullResult(
        val success: Boolean,
        val vouchersRestored: Int = 0,
        val productsRestored: Int = 0,
        val customersRestored: Int = 0,
        val message: String = ""
    )

    data class ServerCheckResult(
        val isReachable: Boolean,
        val responseTimeMs: Long = 0,
        val message: String = ""
    )

    /**
     * Real-time network connectivity check on device.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val actNw = cm.getNetworkCapabilities(nw) ?: return false
                actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val nwInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                nwInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the VPS Server / Cloudflare Worker is actually online and responding.
     */
    suspend fun checkServerReachability(workerUrl: String): ServerCheckResult {
        return withContext(Dispatchers.IO) {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            if (cleanUrl.isBlank() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
                return@withContext ServerCheckResult(false, 0, "Server URL မမှန်ကန်ပါ (URL must start with http:// or https://)")
            }

            val start = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                val checkUrl = URL("$cleanUrl/api/check-status")
                conn = checkUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                val elapsed = System.currentTimeMillis() - start

                // Any standard HTTP response code (including 200, 404, etc.) means server is reachable
                if (code in 200..499) {
                    ServerCheckResult(true, elapsed, "Server ချိတ်ဆက်မှု အောင်မြင်ပါသည် (${elapsed}ms)")
                } else {
                    ServerCheckResult(false, elapsed, "Server မှ အကြောင်းပြန်မှု မှားယွင်းနေပါသည် (Status: $code)")
                }
            } catch (e: java.net.ConnectException) {
                ServerCheckResult(false, 0, "VPS Server သို့ ချိတ်ဆက်၍မရပါ (Connection Refused)။ သင့် VPS တွင် server.js ကို run ထားခြင်း မရှိသေးပါ (သို့မဟုတ် Port 8081 ပိတ်နေပါသည်)။")
            } catch (e: java.net.SocketTimeoutException) {
                ServerCheckResult(false, 0, "Server ချိတ်ဆက်မှု အချိန်ကုန်သွားပါသည် (Timeout)။ VPS Server IP သို့မဟုတ် Firewall စစ်ဆေးပါ။")
            } catch (e: java.net.UnknownHostException) {
                ServerCheckResult(false, 0, "Server Domain/IP ကို ရှာမတွေ့ပါ (Unknown Host)။")
            } catch (e: Exception) {
                ServerCheckResult(false, 0, "Server ချိတ်ဆက်၍မရပါ: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Upload single sale immediately when online.
     * Guarantees Tenant isolation via userPhone.
     */
    suspend fun syncSingleVoucher(
        workerUrl: String,
        userPhone: String,
        voucher: Voucher,
        items: List<VoucherItem>,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean {
        if (userPhone.isBlank()) return false

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val targetUrl = workerUrl.trim().removeSuffix("/") + "/api/sync/voucher"
                val url = URL(targetUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.applyAuthHeaders(authToken, deviceId)
                conn.doOutput = true

                val voucherJson = JSONObject().apply {
                    put("receiptNo", voucher.receiptNo)
                    put("timestamp", voucher.timestamp)
                    put("cashierName", voucher.cashierName)
                    put("totalAmount", voucher.totalAmount)
                    put("totalItems", voucher.totalItems)
                    put("customerName", voucher.customerName)
                    put("paymentMethod", voucher.paymentMethod)
                    put("isCompleted", voucher.isCompleted)
                    put("isPurchase", voucher.isPurchase)
                    put("paidAmount", voucher.paidAmount)
                    put("changeAmount", voucher.changeAmount)
                    put("balanceAmount", voucher.balanceAmount)
                    put("note", voucher.note)
                    put("discount", voucher.discount)
                    put("fee", voucher.fee)
                }

                val itemsArray = JSONArray()
                for (item in items) {
                    val itemJson = JSONObject().apply {
                        put("productId", item.productId)
                        put("productName", item.productName)
                        put("quantity", item.quantity)
                        put("purchasePrice", item.purchasePrice)
                        put("sellingPrice", item.sellingPrice)
                    }
                    itemsArray.put(itemJson)
                }

                val rootJson = JSONObject().apply {
                    put("userPhone", userPhone)
                    put("voucher", voucherJson)
                    put("items", itemsArray)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                    os.write(rootJson.toString())
                    os.flush()
                }

                val code = conn.responseCode
                code in 200..299
            } catch (e: Exception) {
                Log.e(TAG, "Single voucher sync failed: ${e.message}")
                false
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    suspend fun deleteSingleProductOnCloud(
        workerUrl: String,
        userPhone: String,
        productId: Long,
        productName: String,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        var conn: HttpURLConnection? = null
        try {
            val targetUrl = "$cleanUrl/api/sync/delete-product"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("productId", productId)
                put("productName", productName)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteProductsOnCloud(
        workerUrl: String,
        userPhone: String,
        productIds: List<Long>,
        productNames: List<String>,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank() || (productIds.isEmpty() && productNames.isEmpty())) return@withContext false
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        var conn: HttpURLConnection? = null
        try {
            val targetUrl = "$cleanUrl/api/sync/delete-products"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val idsArr = JSONArray()
            productIds.forEach { idsArr.put(it) }
            val namesArr = JSONArray()
            productNames.forEach { namesArr.put(it) }

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("productIds", idsArr)
                put("productNames", namesArr)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteVoucherOnCloud(
        workerUrl: String,
        userPhone: String,
        receiptNo: String,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank() || receiptNo.isBlank()) return@withContext false
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        var conn: HttpURLConnection? = null
        try {
            val targetUrl = "$cleanUrl/api/sync/delete-voucher"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("receiptNo", receiptNo)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun adjustStockOnCloud(
        workerUrl: String,
        userPhone: String,
        productId: Long,
        productName: String,
        newQuantity: Int,
        newAlertQuantity: Int,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        var conn: HttpURLConnection? = null
        try {
            val targetUrl = "$cleanUrl/api/sync/adjust-stock"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("productId", productId)
                put("productName", productName)
                put("newQuantity", newQuantity)
                put("newAlertQuantity", newAlertQuantity)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteCustomerOnCloud(
        workerUrl: String,
        userPhone: String,
        customerName: String,
        customerId: Long = 0L,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            val targetUrl = "$cleanUrl/api/sync/delete-customer"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("customerName", customerName)
                if (customerId > 0L) put("customerId", customerId)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteSupplierOnCloud(
        workerUrl: String,
        userPhone: String,
        supplierName: String,
        supplierId: Long = 0L,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            val targetUrl = "$cleanUrl/api/sync/delete-supplier"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("supplierName", supplierName)
                if (supplierId > 0L) put("supplierId", supplierId)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteGroupOnCloud(
        workerUrl: String,
        userPhone: String,
        groupName: String,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            val targetUrl = "$cleanUrl/api/sync/delete-group"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("groupName", groupName)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deleteUnitOnCloud(
        workerUrl: String,
        userPhone: String,
        unitName: String,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            val targetUrl = "$cleanUrl/api/sync/delete-unit"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("unitName", unitName)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun deletePaymentOnCloud(
        workerUrl: String,
        userPhone: String,
        method: String,
        authToken: String = "",
        deviceId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val cleanUrl = workerUrl.trim().removeSuffix("/")
            val targetUrl = "$cleanUrl/api/sync/delete-payment"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("userPhone", userPhone)
                put("method", method)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Atomically mirrors 100% of local products, stock, vouchers, customers to the Cloud.
     * Guarantees that what is in local is EXACTLY mirrored on the Cloud.
     */
    suspend fun mirrorLocalToCloud(
        workerUrl: String,
        userPhone: String,
        repository: Repository,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        authToken: String = "",
        deviceId: String = ""
    ): SyncResult = withContext(Dispatchers.IO) {
        if (workerUrl.isBlank() || userPhone.isBlank()) {
            return@withContext SyncResult(false, 0, 0, 0, "ကျေးဇူးပြု၍ Login ဝင်ထားပါ (No user phone)")
        }
        val cleanUrl = workerUrl.trim().removeSuffix("/")
        onProgress(0.1f, "Cloud ချိတ်ဆက်မှု စစ်ဆေးနေပါသည်...")
        val check = checkServerReachability(workerUrl)
        if (!check.isReachable) {
            return@withContext SyncResult(false, 0, 0, 0, check.message)
        }

        var conn: HttpURLConnection? = null
        try {
            onProgress(0.3f, "ဒေတာများကို စုစည်းပြင်ဆင်နေပါသည်...")
            val allProducts = repository.getAllProductsDirect()
            val allVouchers = repository.getAllCompletedVouchersDirect()
            val allVoucherItems = repository.getAllVoucherItemsDirect()
            val allCustomers = repository.getAllCustomersDirect()
            val allGroups = repository.getAllGroupsDirect().map { it.name }
            val allUnits = repository.getAllUnitsDirect().map { it.name }
            val allPayments = repository.getAllPaymentsDirect()
            val allSuppliers = repository.getAllSuppliersDirect()
            val allExpenseCategories = repository.getAllExpenseCategoriesDirect()
            val allExpenses = repository.getAllExpensesDirect()
            val allStockAdjustments = repository.getAllStockAdjustmentsDirect()

            val targetUrl = "$cleanUrl/api/sync/mirror"
            conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 25000
            conn.readTimeout = 90000
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.applyAuthHeaders(authToken, deviceId)
            conn.doOutput = true
            conn.setChunkedStreamingMode(0)

            onProgress(0.55f, "Cloud ပေါ်သို့ စာရင်းများ တိုက်ရိုက် Streaming ပေးပို့နေပါသည်...")
            BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8"), 32768).use { w ->
                w.write("{\"userPhone\":")
                w.writeJsonStr(userPhone)

                w.write(",\"products\":[")
                allProducts.forEachIndexed { idx, p ->
                    if (idx > 0) w.write(",")
                    w.write("{\"id\":${p.id},\"name\":")
                    w.writeJsonStr(p.name)
                    w.write(",\"groupName\":")
                    w.writeJsonStr(p.groupName)
                    w.write(",\"purchasePrice\":${safeDouble(p.purchasePrice)},\"sellingPrice\":${safeDouble(p.sellingPrice)},\"unit\":")
                    w.writeJsonStr(p.unit)
                    w.write(",\"barcode\":")
                    w.writeJsonStr(p.barcode)
                    w.write(",\"quantity\":${p.quantity},\"alertQuantity\":${p.alertQuantity},\"trackStock\":${p.trackStock},\"note\":")
                    w.writeJsonStr(p.note)
                    w.write("}")
                }
                w.write("]")

                w.write(",\"vouchers\":[")
                allVouchers.forEachIndexed { idx, v ->
                    if (idx > 0) w.write(",")
                    w.write("{\"receiptNo\":")
                    w.writeJsonStr(v.receiptNo)
                    w.write(",\"timestamp\":${v.timestamp},\"cashierName\":")
                    w.writeJsonStr(v.cashierName)
                    w.write(",\"totalAmount\":${safeDouble(v.totalAmount)},\"totalItems\":${v.totalItems},\"customerName\":")
                    w.writeJsonStr(v.customerName)
                    w.write(",\"paymentMethod\":")
                    w.writeJsonStr(v.paymentMethod)
                    w.write(",\"isCompleted\":${v.isCompleted},\"isPurchase\":${v.isPurchase},\"paidAmount\":${safeDouble(v.paidAmount)},\"changeAmount\":${safeDouble(v.changeAmount)},\"balanceAmount\":${safeDouble(v.balanceAmount)},\"note\":")
                    w.writeJsonStr(v.note)
                    w.write(",\"discount\":${safeDouble(v.discount)},\"fee\":${safeDouble(v.fee)}}")
                }
                w.write("]")

                w.write(",\"voucherItems\":[")
                allVoucherItems.forEachIndexed { idx, it ->
                    if (idx > 0) w.write(",")
                    w.write("{\"voucherId\":")
                    w.writeJsonStr(it.voucherId)
                    w.write(",\"productId\":${it.productId},\"productName\":")
                    w.writeJsonStr(it.productName)
                    w.write(",\"quantity\":${it.quantity},\"purchasePrice\":${safeDouble(it.purchasePrice)},\"sellingPrice\":${safeDouble(it.sellingPrice)}}")
                }
                w.write("]")

                w.write(",\"customers\":[")
                val seenCustNames = mutableSetOf<String>()
                var custFirst = true
                for (c in allCustomers) {
                    val clean = c.name.trim()
                    val norm = clean.lowercase()
                    if (clean.isNotBlank() && seenCustNames.add(norm)) {
                        if (!custFirst) w.write(",")
                        custFirst = false
                        w.write("{\"name\":")
                        w.writeJsonStr(clean)
                        w.write(",\"phone\":")
                        w.writeJsonStr(c.phone)
                        w.write(",\"address\":")
                        w.writeJsonStr(c.address)
                        w.write(",\"note\":")
                        w.writeJsonStr(c.note)
                        w.write("}")
                    }
                }
                w.write("]")

                w.write(",\"groups\":[")
                allGroups.forEachIndexed { idx, g ->
                    if (idx > 0) w.write(",")
                    w.writeJsonStr(g)
                }
                w.write("]")

                w.write(",\"units\":[")
                allUnits.forEachIndexed { idx, u ->
                    if (idx > 0) w.write(",")
                    w.writeJsonStr(u)
                }
                w.write("]")

                w.write(",\"payments\":[")
                allPayments.forEachIndexed { idx, p ->
                    if (idx > 0) w.write(",")
                    w.write("{\"method\":")
                    w.writeJsonStr(p.method)
                    w.write(",\"amount\":${safeDouble(p.amount)},\"date\":${p.date}}")
                }
                w.write("]")

                w.write(",\"suppliers\":[")
                val seenSupNames = mutableSetOf<String>()
                var supFirst = true
                for (s in allSuppliers) {
                    val clean = s.name.trim()
                    val norm = clean.lowercase()
                    if (clean.isNotBlank() && seenSupNames.add(norm)) {
                        if (!supFirst) w.write(",")
                        supFirst = false
                        w.write("{\"name\":")
                        w.writeJsonStr(clean)
                        w.write(",\"phone\":")
                        w.writeJsonStr(s.phone)
                        w.write(",\"address\":")
                        w.writeJsonStr(s.address)
                        w.write(",\"note\":")
                        w.writeJsonStr(s.note)
                        w.write("}")
                    }
                }
                w.write("]")

                w.write(",\"expenseCategories\":[")
                allExpenseCategories.forEachIndexed { idx, ec ->
                    if (idx > 0) w.write(",")
                    w.write("{\"name\":")
                    w.writeJsonStr(ec.name)
                    w.write(",\"iconName\":")
                    w.writeJsonStr(ec.iconName)
                    w.write("}")
                }
                w.write("]")

                w.write(",\"expenses\":[")
                allExpenses.forEachIndexed { idx, e ->
                    if (idx > 0) w.write(",")
                    w.write("{\"categoryName\":")
                    w.writeJsonStr(e.categoryName)
                    w.write(",\"description\":")
                    w.writeJsonStr(e.description)
                    w.write(",\"amount\":${safeDouble(e.amount)},\"paymentMethod\":")
                    w.writeJsonStr(e.paymentMethod)
                    w.write(",\"note\":")
                    w.writeJsonStr(e.note)
                    w.write(",\"timestamp\":${e.timestamp},\"dateString\":")
                    w.writeJsonStr(e.dateString)
                    w.write(",\"timeString\":")
                    w.writeJsonStr(e.timeString)
                    w.write("}")
                }
                w.write("]")

                w.write(",\"stockAdjustments\":[")
                allStockAdjustments.forEachIndexed { idx, sa ->
                    if (idx > 0) w.write(",")
                    w.write("{\"productId\":${sa.productId},\"productName\":")
                    w.writeJsonStr(sa.productName)
                    w.write(",\"adjustmentType\":")
                    w.writeJsonStr(sa.adjustmentType)
                    w.write(",\"reasonTitle\":")
                    w.writeJsonStr(sa.reasonTitle)
                    w.write(",\"description\":")
                    w.writeJsonStr(sa.description)
                    w.write(",\"quantity\":${sa.quantity},\"beforeQuantity\":${sa.beforeQuantity},\"afterQuantity\":${sa.afterQuantity},\"timestamp\":${sa.timestamp},\"adjustedBy\":")
                    w.writeJsonStr(sa.adjustedBy)
                    w.write("}")
                }
                w.write("]}")
                w.flush()
            }

            onProgress(0.85f, "Cloud Server အတည်ပြုချက် စောင့်ဆိုင်းနေပါသည်...")
            val code = conn.responseCode
            if (code in 200..299) {
                onProgress(0.95f, "Cloud စာရင်းများ အတည်ပြုပြီးပါပြီ")
                repository.markAllProductsSynced()
                repository.markAllVouchersSynced()
                repository.markAllCustomersSynced()
                onProgress(1.0f, "အောင်မြင်စွာ သိမ်းဆည်းပြီးပါပြီ။")
                SyncResult(
                    success = true,
                    syncedVouchersCount = allVouchers.size,
                    syncedProductsCount = allProducts.size,
                    syncedCustomersCount = allCustomers.size,
                    message = "Local ဒေတာအတိုင်း Cloud ပေါ်သို့ အတိအကျ ၁၀၀% Mirror တင်ပြီးပါပြီ။"
                )
            } else {
                val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                SyncResult(false, 0, 0, 0, "Server error: $code ${errBody.take(120)}")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            SyncResult(false, 0, 0, 0, "Mirror sync failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Batch syncs all pending offline vouchers, new products, and customers to the cloud.
     */
    suspend fun syncAllPendingData(
        workerUrl: String,
        userPhone: String,
        repository: Repository,
        pendingDeletedProductIds: List<Long> = emptyList(),
        pendingDeletedProductNames: List<String> = emptyList(),
        pendingDeletedVouchers: List<String> = emptyList(),
        manualStockAdjustedProductIds: Set<Long> = emptySet(),
        pendingDeletedCustomers: List<String> = emptyList(),
        pendingDeletedSuppliers: List<String> = emptyList(),
        pendingDeletedGroups: List<String> = emptyList(),
        pendingDeletedUnits: List<String> = emptyList(),
        pendingDeletedPayments: List<String> = emptyList(),
        authToken: String = "",
        deviceId: String = ""
    ): SyncResult {
        if (userPhone.isBlank()) {
            return SyncResult(false, 0, 0, 0, "ကျေးဇူးပြု၍ အကောင့် Login ဝင်ထားပါ (No active user account)")
        }

        return withContext(Dispatchers.IO) {
            // First check if server is reachable
            val check = checkServerReachability(workerUrl)
            if (!check.isReachable) {
                return@withContext SyncResult(
                    success = false,
                    message = "${check.message}\n(ဒေတာများကို ဖုန်းထဲတွင် ဆက်လက်သိမ်းဆည်းထားပါသည်)"
                )
            }

            try {
                // 0. Sync pending deleted products and vouchers first
                if (pendingDeletedProductIds.isNotEmpty() || pendingDeletedProductNames.isNotEmpty()) {
                    deleteProductsOnCloud(workerUrl, userPhone, pendingDeletedProductIds, pendingDeletedProductNames, authToken, deviceId)
                }
                if (pendingDeletedVouchers.isNotEmpty()) {
                    for (vId in pendingDeletedVouchers) {
                        deleteVoucherOnCloud(workerUrl, userPhone, vId, authToken, deviceId)
                    }
                }

                var syncedVouchersCount = 0
                var syncedProductsCount = 0
                var syncedCustomersCount = 0

                val cleanUrl = workerUrl.trim().removeSuffix("/")

                // 1. Sync pending vouchers (chunked so large histories never time out or exhaust memory)
                val unsyncedVouchers = repository.getUnsyncedCompletedVouchers()
                if (unsyncedVouchers.isNotEmpty()) {
                    val targetUrl = "$cleanUrl/api/sync/vouchers"
                    for (batch in unsyncedVouchers.chunked(150)) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = URL(targetUrl).openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.connectTimeout = 20000
                            conn.readTimeout = 35000
                            conn.setRequestProperty("Content-Type", "application/json; utf-8")
                            conn.setRequestProperty("Accept", "application/json")
                            conn.applyAuthHeaders(authToken, deviceId)
                            conn.doOutput = true

                            val vouchersArray = JSONArray()
                            val syncedIds = mutableListOf<String>()

                            for (v in batch) {
                                val items = repository.getVoucherItemsDirect(v.receiptNo)
                                val vObj = JSONObject().apply {
                                    put("receiptNo", v.receiptNo)
                                    put("timestamp", v.timestamp)
                                    put("cashierName", v.cashierName)
                                    put("totalAmount", v.totalAmount)
                                    put("totalItems", v.totalItems)
                                    put("customerName", v.customerName)
                                    put("paymentMethod", v.paymentMethod)
                                    put("isCompleted", v.isCompleted)
                                    put("isPurchase", v.isPurchase)
                                    put("paidAmount", v.paidAmount)
                                    put("changeAmount", v.changeAmount)
                                    put("balanceAmount", v.balanceAmount)
                                    put("discount", v.discount)
                                    put("fee", v.fee)
                                }

                                val itemsArr = JSONArray()
                                for (it in items) {
                                    itemsArr.put(JSONObject().apply {
                                        put("productId", it.productId)
                                        put("productName", it.productName)
                                        put("quantity", it.quantity)
                                        put("purchasePrice", it.purchasePrice)
                                        put("sellingPrice", it.sellingPrice)
                                    })
                                }

                                vouchersArray.put(JSONObject().apply {
                                    put("voucher", vObj)
                                    put("items", itemsArr)
                                })
                                syncedIds.add(v.receiptNo)
                            }

                            val rootJson = JSONObject().apply {
                                put("userPhone", userPhone)
                                put("vouchers", vouchersArray)
                            }

                            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                                os.write(rootJson.toString())
                                os.flush()
                            }

                            val code = conn.responseCode
                            if (code in 200..299) {
                                repository.markVouchersSynced(syncedIds)
                                syncedVouchersCount += syncedIds.size
                            }
                        } finally {
                            try { conn?.disconnect() } catch (_: Exception) {}
                        }
                    }
                }

                // 2. Sync pending products (chunked for large catalogs)
                val unsyncedProducts = repository.getUnsyncedProducts()
                if (unsyncedProducts.isNotEmpty()) {
                    val targetUrl = "$cleanUrl/api/sync/products"
                    for (batch in unsyncedProducts.chunked(250)) {
                        var conn: HttpURLConnection? = null
                        try {
                            conn = URL(targetUrl).openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.connectTimeout = 20000
                            conn.readTimeout = 35000
                            conn.setRequestProperty("Content-Type", "application/json; utf-8")
                            conn.applyAuthHeaders(authToken, deviceId)
                            conn.doOutput = true

                            val prodsArr = JSONArray()
                            val pIds = mutableListOf<Long>()
                            for (p in batch) {
                                val isManual = (p.id in manualStockAdjustedProductIds)
                                prodsArr.put(JSONObject().apply {
                                    put("id", p.id)
                                    put("name", p.name)
                                    put("groupName", p.groupName)
                                    put("purchasePrice", p.purchasePrice)
                                    put("sellingPrice", p.sellingPrice)
                                    put("unit", p.unit)
                                    put("barcode", p.barcode)
                                    put("quantity", p.quantity)
                                    put("alertQuantity", p.alertQuantity)
                                    put("trackStock", p.trackStock)
                                    put("note", p.note)
                                    if (isManual) {
                                        put("isManualStockAdjustment", true)
                                    }
                                })
                                pIds.add(p.id)
                            }

                            val payload = JSONObject().apply {
                                put("userPhone", userPhone)
                                put("products", prodsArr)
                            }

                            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                            val code = conn.responseCode
                            if (code in 200..299) {
                                repository.markProductsSynced(pIds)
                                syncedProductsCount += pIds.size
                            }
                        } finally {
                            try { conn?.disconnect() } catch (_: Exception) {}
                        }
                    }
                }

                // 3. Sync pending customers
                val unsyncedCustomers = repository.getUnsyncedCustomers()
                if (unsyncedCustomers.isNotEmpty() || pendingDeletedCustomers.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        val targetUrl = "$cleanUrl/api/sync/customers"
                        conn = URL(targetUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        conn.setRequestProperty("Content-Type", "application/json; utf-8")
                        conn.applyAuthHeaders(authToken, deviceId)
                        conn.doOutput = true

                        val custArr = JSONArray()
                        val cIds = mutableListOf<Long>()
                        val seenPendingCust = mutableSetOf<String>()
                        for (c in unsyncedCustomers) {
                            val clean = c.name.trim()
                            val norm = clean.lowercase()
                            if (clean.isNotBlank() && norm !in seenPendingCust) {
                                seenPendingCust.add(norm)
                                custArr.put(JSONObject().apply {
                                    put("id", c.id)
                                    put("name", clean)
                                    put("phone", c.phone)
                                    put("address", c.address)
                                    put("note", c.note)
                                })
                            }
                            cIds.add(c.id)
                        }

                        val payload = JSONObject().apply {
                            put("userPhone", userPhone)
                            put("customers", custArr)
                            if (pendingDeletedCustomers.isNotEmpty()) {
                                put("deletedCustomers", JSONArray(pendingDeletedCustomers))
                            }
                        }

                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            repository.markCustomersSynced(cIds)
                            syncedCustomersCount = cIds.size
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Customers sync error: ${e.message}")
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }

                // 4. Sync product groups (categories)
                val allGroups = repository.getAllGroupsDirect()
                if (allGroups.isNotEmpty() || pendingDeletedGroups.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        val targetUrl = "$cleanUrl/api/sync/groups"
                        conn = URL(targetUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("Content-Type", "application/json; utf-8")
                        conn.applyAuthHeaders(authToken, deviceId)
                        conn.doOutput = true

                        val grpArr = JSONArray()
                        for (g in allGroups) grpArr.put(g.name)

                        val payload = JSONObject().apply {
                            put("userPhone", userPhone)
                            put("groups", grpArr)
                            if (pendingDeletedGroups.isNotEmpty()) {
                                put("deletedGroups", JSONArray(pendingDeletedGroups))
                            }
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                        conn.responseCode
                    } catch (e: Exception) {
                        Log.e(TAG, "Groups sync error: ${e.message}")
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }

                // 5. Sync product units
                val allUnits = repository.getAllUnitsDirect()
                if (allUnits.isNotEmpty() || pendingDeletedUnits.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        val targetUrl = "$cleanUrl/api/sync/units"
                        conn = URL(targetUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("Content-Type", "application/json; utf-8")
                        conn.applyAuthHeaders(authToken, deviceId)
                        conn.doOutput = true

                        val untArr = JSONArray()
                        for (u in allUnits) untArr.put(u.name)

                        val payload = JSONObject().apply {
                            put("userPhone", userPhone)
                            put("units", untArr)
                            if (pendingDeletedUnits.isNotEmpty()) {
                                put("deletedUnits", JSONArray(pendingDeletedUnits))
                            }
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                        conn.responseCode
                    } catch (e: Exception) {
                        Log.e(TAG, "Units sync error: ${e.message}")
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }

                // 6. Sync payment methods
                val allPayments = repository.getAllPaymentsDirect()
                if (allPayments.isNotEmpty() || pendingDeletedPayments.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        val targetUrl = "$cleanUrl/api/sync/payments"
                        conn = URL(targetUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("Content-Type", "application/json; utf-8")
                        conn.applyAuthHeaders(authToken, deviceId)
                        conn.doOutput = true

                        val payArr = JSONArray()
                        for (pay in allPayments) {
                            payArr.put(JSONObject().apply {
                                put("method", pay.method)
                                put("amount", pay.amount)
                                put("date", pay.date)
                            })
                        }

                        val payload = JSONObject().apply {
                            put("userPhone", userPhone)
                            put("payments", payArr)
                            if (pendingDeletedPayments.isNotEmpty()) {
                                put("deletedPayments", JSONArray(pendingDeletedPayments))
                            }
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                        conn.responseCode
                    } catch (e: Exception) {
                        Log.e(TAG, "Payments sync error: ${e.message}")
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }

                // 7. Sync suppliers
                val allSuppliers = repository.getAllSuppliersDirect()
                if (allSuppliers.isNotEmpty() || pendingDeletedSuppliers.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        val targetUrl = "$cleanUrl/api/sync/suppliers"
                        conn = URL(targetUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("Content-Type", "application/json; utf-8")
                        conn.applyAuthHeaders(authToken, deviceId)
                        conn.doOutput = true

                        val supArr = JSONArray()
                        val seenPendingSup = mutableSetOf<String>()
                        for (sup in allSuppliers) {
                            val clean = sup.name.trim()
                            val norm = clean.lowercase()
                            if (clean.isNotBlank() && norm !in seenPendingSup) {
                                seenPendingSup.add(norm)
                                supArr.put(JSONObject().apply {
                                    put("id", sup.id)
                                    put("name", clean)
                                    put("phone", sup.phone)
                                    put("address", sup.address)
                                    put("note", sup.note)
                                })
                            }
                        }

                        val payload = JSONObject().apply {
                            put("userPhone", userPhone)
                            put("suppliers", supArr)
                            if (pendingDeletedSuppliers.isNotEmpty()) {
                                put("deletedSuppliers", JSONArray(pendingDeletedSuppliers))
                            }
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
                        conn.responseCode
                    } catch (e: Exception) {
                        Log.e(TAG, "Suppliers sync error: ${e.message}")
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }

                val totalSynced = syncedVouchersCount + syncedProductsCount + syncedCustomersCount
                if (totalSynced > 0) {
                    // Pull updated cloud data immediately so local stock matches the server's absolute reconciled stock
                    try {
                        pullCloudData(
                            workerUrl = workerUrl,
                            userPhone = userPhone,
                            repository = repository,
                            authToken = authToken,
                            deviceId = deviceId
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-pull after sync error: ${e.message}")
                    }
                    val msg = "Cloud သို့ အောင်မြင်စွာ တင်ပြီးပါပြီ (အရောင်း: $syncedVouchersCount, ပစ္စည်း: $syncedProductsCount, ဖောက်သည်: $syncedCustomersCount)"
                    SyncResult(true, syncedVouchersCount, syncedProductsCount, syncedCustomersCount, msg)
                } else {
                    SyncResult(true, 0, 0, 0, "ဒေတာအားလုံး (ပစ္စည်း၊ အမျိုးအစား၊ ယူနစ်၊ ဖောက်သည်၊ ကုန်သွင်းသူ၊ ငွေပေးချေမှု) Cloud ပေါ်တွင် ရောက်ရှိပြီးဖြစ်ပါသည်")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Batch sync failed: ${t.message}")
                SyncResult(false, 0, 0, 0, "Sync ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Pulls / restores cloud data for this specific user account.
     * Guarantees Customer A and Customer B data separation!
     */
    suspend fun pullCloudData(
        workerUrl: String,
        userPhone: String,
        repository: Repository,
        pendingDeletedCustomers: Set<String> = emptySet(),
        pendingDeletedSuppliers: Set<String> = emptySet(),
        pendingDeletedGroups: Set<String> = emptySet(),
        pendingDeletedUnits: Set<String> = emptySet(),
        pendingDeletedPayments: Set<String> = emptySet(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
        authToken: String = "",
        deviceId: String = "",
        pendingRestoreMirror: Boolean = false
    ): PullResult {
        if (userPhone.isBlank()) {
            return PullResult(false, message = "ကျေးဇူးပြု၍ Login အရင်ဝင်ပါ")
        }
        if (pendingRestoreMirror) {
            return PullResult(false, message = "DB Restore ကို Cloud သို့ Mirror တင်နေဆဲဖြစ်ပါသည်")
        }

        return withContext(Dispatchers.IO) {
            onProgress(0.08f, "Cloud ချိတ်ဆက်မှု စစ်ဆေးနေပါသည်...")
            val check = checkServerReachability(workerUrl)
            if (!check.isReachable) {
                return@withContext PullResult(false, message = check.message)
            }

            var conn: HttpURLConnection? = null
            try {
                onProgress(0.20f, "Cloud မှ ဒေတာများ ရယူနေပါသည်...")
                val targetUrl = workerUrl.trim().removeSuffix("/") + "/api/sync/pull"
                conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 45000
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.applyAuthHeaders(authToken, deviceId)
                conn.doOutput = true

                val req = JSONObject().apply { put("userPhone", userPhone) }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(req.toString()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext PullResult(false, message = "Cloud မှ ဒေတာရယူ၍မရပါ (Status: $code)")
                }

                val responseStr = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val resObj = JSONObject(responseStr)
                onProgress(0.40f, "ဒေတာများကို စစ်ဆေးထည့်သွင်းနေပါသည်...")

                var vCount = 0
                var pCount = 0
                var cCount = 0

                // 1. Restore voucher items (supporting both camelCase and snake_case)
                val itemsArr = resObj.optJSONArray("voucherItems") ?: resObj.optJSONArray("voucher_items") ?: JSONArray()
                val itemsMap = mutableMapOf<String, MutableList<VoucherItem>>()
                for (i in 0 until itemsArr.length()) {
                    val it = itemsArr.getJSONObject(i)
                    val vId = if (it.has("voucherId")) it.optString("voucherId") else it.optString("voucher_id")
                    if (vId.isNotBlank()) {
                        val pId = if (it.has("productId")) it.optLong("productId") else it.optLong("product_id", 0L)
                        val pName = if (it.has("productName")) it.optString("productName") else it.optString("product_name", "")
                        val qty = it.optInt("quantity", 1)
                        val pPrice = if (it.has("purchasePrice")) it.optDouble("purchasePrice") else it.optDouble("purchase_price", 0.0)
                        val sPrice = if (it.has("sellingPrice")) it.optDouble("sellingPrice") else it.optDouble("selling_price", 0.0)
                        val vItem = VoucherItem(
                            voucherId = vId,
                            productId = pId,
                            productName = pName,
                            quantity = qty,
                            purchasePrice = pPrice,
                            sellingPrice = sPrice
                        )
                        itemsMap.getOrPut(vId) { mutableListOf() }.add(vItem)
                    }
                }

                // Pre-parse vouchers before entering DB transaction
                val vouchersArr = resObj.optJSONArray("vouchers") ?: JSONArray()
                val parsedVouchers = ArrayList<Voucher>(vouchersArr.length())
                val cloudVoucherReceiptNos = HashSet<String>(vouchersArr.length() * 2)
                for (i in 0 until vouchersArr.length()) {
                    val v = vouchersArr.getJSONObject(i)
                    val receiptNo = if (v.has("receiptNo")) v.optString("receiptNo") else v.optString("receipt_no")
                    if (receiptNo.isNotBlank()) {
                        cloudVoucherReceiptNos.add(receiptNo)
                        val ts = v.optLong("timestamp", System.currentTimeMillis())
                        val cashier = if (v.has("cashierName")) v.optString("cashierName") else v.optString("cashier_name", "Cashier")
                        val totAmt = if (v.has("totalAmount")) v.optDouble("totalAmount") else v.optDouble("total_amount", 0.0)
                        val totItms = if (v.has("totalItems")) v.optInt("totalItems") else v.optInt("total_items", 0)
                        val cust = if (v.has("customerName")) v.optString("customerName") else v.optString("customer_name", "Not Register")
                        val payMethod = if (v.has("paymentMethod")) v.optString("paymentMethod") else v.optString("payment_method", "CASH")

                        val isComp = when {
                            v.has("isCompleted") -> v.optBoolean("isCompleted", true)
                            v.has("is_completed") -> v.optInt("is_completed", 1) == 1
                            else -> true
                        }
                        val isPurch = when {
                            v.has("isPurchase") -> v.optBoolean("isPurchase", false)
                            v.has("is_purchase") -> v.optInt("is_purchase", 0) == 1
                            else -> false
                        }

                        val paidAmt = if (v.has("paidAmount")) v.optDouble("paidAmount") else v.optDouble("paid_amount", 0.0)
                        val changeAmt = if (v.has("changeAmount")) v.optDouble("changeAmount") else v.optDouble("change_amount", 0.0)
                        val balAmt = if (v.has("balanceAmount")) v.optDouble("balanceAmount") else v.optDouble("balance_amount", 0.0)
                        val disc = if (v.has("discount")) v.optDouble("discount") else 0.0
                        val feeAmt = if (v.has("fee")) v.optDouble("fee") else 0.0
                        val vNote = v.optString("note", "")

                        parsedVouchers.add(
                            Voucher(
                                receiptNo = receiptNo,
                                timestamp = ts,
                                cashierName = cashier,
                                totalAmount = totAmt,
                                totalItems = totItms,
                                customerName = cust,
                                paymentMethod = payMethod,
                                isCompleted = isComp,
                                isPurchase = isPurch,
                                paidAmount = paidAmt,
                                changeAmount = changeAmt,
                                balanceAmount = balAmt,
                                discount = disc,
                                fee = feeAmt,
                                note = vNote,
                                isSynced = true
                            )
                        )
                    }
                }

                // Wrap all Room writes inside a single SQLite transaction to eliminate per-row Flow storms & ANRs
                val prodsArr = resObj.optJSONArray("products") ?: JSONArray()
                val custsArr = resObj.optJSONArray("customers") ?: JSONArray()
                val groupsArr = resObj.optJSONArray("groups") ?: JSONArray()
                val unitsArr = resObj.optJSONArray("units") ?: JSONArray()
                val paymentsArr = resObj.optJSONArray("payments") ?: JSONArray()
                val suppliersArr = resObj.optJSONArray("suppliers") ?: JSONArray()
                val expCatsArr = resObj.optJSONArray("expenseCategories") ?: resObj.optJSONArray("expense_categories") ?: JSONArray()
                val expensesArr = resObj.optJSONArray("expenses") ?: JSONArray()
                val adjustmentsArr = resObj.optJSONArray("stockAdjustments") ?: JSONArray()

                repository.runInTransaction {
                    // 2. Restore vouchers
                    for (voucher in parsedVouchers) {
                        repository.insertVoucher(voucher)
                        repository.deleteVoucherItems(voucher.receiptNo)
                        itemsMap[voucher.receiptNo]?.forEach { repository.insertVoucherItem(it) }
                        vCount++
                    }

                    // Multi-device voucher deletion sync: delete local synced vouchers via explicit tombstones or only when cloud vouchers array is non-empty
                    val deletedVouchersFromCloud = HashSet<String>()
                    val delVouchersArr = resObj.optJSONArray("deletedVouchers")
                    if (delVouchersArr != null) {
                        for (i in 0 until delVouchersArr.length()) {
                            val key = delVouchersArr.optString(i).trim()
                            if (key.isNotBlank()) {
                                deletedVouchersFromCloud.add(key)
                                deletedVouchersFromCloud.add(key.lowercase())
                            }
                        }
                    }
                    val allLocalVouchers = repository.getAllVouchersDirect()
                    for (lv in allLocalVouchers) {
                        val isExplicitlyDeleted = lv.receiptNo in deletedVouchersFromCloud || lv.receiptNo.lowercase() in deletedVouchersFromCloud
                        val missingFromNonEmptyCloud = vouchersArr.length() > 0 && lv.receiptNo !in cloudVoucherReceiptNos
                        if (lv.isSynced && (isExplicitlyDeleted || missingFromNonEmptyCloud)) {
                            repository.deleteVoucher(lv.receiptNo)
                            repository.deleteVoucherItems(lv.receiptNo)
                        }
                    }
                    onProgress(0.65f, "ဘောက်ချာမှတ်တမ်းများ ထည့်သွင်းပြီးပါပြီ...")

                    // 3. Restore products (real-time stock/metadata sync & multi-device deletion sync)
                    val pendingVouchers = repository.getUnsyncedCompletedVouchers()
                    val pendingSoldById = mutableMapOf<Long, Int>()
                    val pendingSoldByName = mutableMapOf<String, Int>()
                    val pendingPurchasedById = mutableMapOf<Long, Int>()
                    val pendingPurchasedByName = mutableMapOf<String, Int>()

                    for (pv in pendingVouchers) {
                        val items = repository.getVoucherItemsDirect(pv.receiptNo)
                        for (it in items) {
                            val q = it.quantity
                            if (pv.isPurchase) {
                                if (it.productId > 0) pendingPurchasedById[it.productId] = (pendingPurchasedById[it.productId] ?: 0) + q
                                if (it.productName.isNotBlank()) pendingPurchasedByName[it.productName.trim().lowercase()] = (pendingPurchasedByName[it.productName.trim().lowercase()] ?: 0) + q
                            } else {
                                if (it.productId > 0) pendingSoldById[it.productId] = (pendingSoldById[it.productId] ?: 0) + q
                                if (it.productName.isNotBlank()) pendingSoldByName[it.productName.trim().lowercase()] = (pendingSoldByName[it.productName.trim().lowercase()] ?: 0) + q
                            }
                        }
                    }

                    val cloudProductIds = mutableSetOf<Long>()
                    val cloudProductNames = mutableSetOf<String>()
                    val currentLocalProds = repository.getAllProductsDirect()
                    val localProdsByName = currentLocalProds.associateBy { it.name.trim().lowercase() }.toMutableMap()
                    val localProdsById = currentLocalProds.filter { it.id > 0 }.associateBy { it.id }.toMutableMap()

                    for (i in 0 until prodsArr.length()) {
                        val p = prodsArr.getJSONObject(i)
                        val prodId = if (p.has("id")) p.optLong("id") else p.optLong("product_id", 0L)
                        val pName = p.optString("name").trim()
                        if (pName.isBlank()) continue
                        val normName = pName.lowercase()
                        if (prodId > 0) cloudProductIds.add(prodId)
                        cloudProductNames.add(normName)

                        val gName = if (p.has("groupName")) p.optString("groupName") else p.optString("group_name", "Default")
                        val pPrice = if (p.has("purchasePrice")) p.optDouble("purchasePrice") else p.optDouble("purchase_price", 0.0)
                        val sPrice = if (p.has("sellingPrice")) p.optDouble("sellingPrice") else p.optDouble("selling_price", 0.0)
                        val aQty = if (p.has("alertQuantity")) p.optInt("alertQuantity") else p.optInt("alert_quantity", 0)
                        val track = if (p.has("trackStock")) p.optBoolean("trackStock") else true
                        val barcode = p.optString("barcode", "")
                        val unit = p.optString("unit", "ခု")
                        val note = p.optString("note", "")
                        val cloudQty = p.optInt("quantity", 0)

                        // Relative offline reconciliation: remain stock = cloud database stock - pending offline sold stock
                        val pSold = (if (prodId > 0) pendingSoldById[prodId] else null) ?: pendingSoldByName[normName] ?: 0
                        val pPurchased = (if (prodId > 0) pendingPurchasedById[prodId] else null) ?: pendingPurchasedByName[normName] ?: 0
                        val finalQty = if (pSold > 0 || pPurchased > 0) {
                            (cloudQty + pPurchased - pSold).coerceAtLeast(0)
                        } else {
                            cloudQty
                        }
                        val hasPendingDeltas = (pSold > 0 || pPurchased > 0)

                        // Avoid ID-shift collisions across DB restores: prefer matching same name if ID points to a different item
                        val byId = if (prodId > 0) localProdsById[prodId] else null
                        val byName = localProdsByName[normName]
                        val existing = if (byId != null && (byId.name.trim().lowercase() == normName || byName == null)) {
                            byId
                        } else {
                            byName ?: byId
                        }

                        if (existing != null) {
                            val updated = existing.copy(
                                name = pName,
                                groupName = gName,
                                purchasePrice = pPrice,
                                sellingPrice = sPrice,
                                unit = unit,
                                note = note,
                                trackStock = track,
                                barcode = barcode,
                                quantity = finalQty,
                                alertQuantity = aQty,
                                isSynced = !hasPendingDeltas
                            )
                            repository.updateProduct(updated)
                            localProdsByName[normName] = updated
                            if (updated.id > 0) localProdsById[updated.id] = updated
                        } else {
                            val safeNewId = if (prodId > 0 && localProdsById[prodId] == null) prodId else 0L
                            val prod = Product(
                                id = safeNewId,
                                name = pName,
                                groupName = gName,
                                purchasePrice = pPrice,
                                sellingPrice = sPrice,
                                unit = unit,
                                note = note,
                                trackStock = track,
                                barcode = barcode,
                                quantity = finalQty,
                                alertQuantity = aQty,
                                isSynced = !hasPendingDeltas
                            )
                            val insertedId = repository.insertProduct(prod)
                            val saved = prod.copy(id = insertedId)
                            localProdsByName[normName] = saved
                            if (insertedId > 0) localProdsById[insertedId] = saved
                        }
                        pCount++
                    }

                    // Multi-device deletion sync: honor explicit deletedProducts tombstones and prune missing synced items ONLY when cloud products array is non-empty
                    val deletedProductsFromCloud = HashSet<String>()
                    val delProdsArr = resObj.optJSONArray("deletedProducts")
                    if (delProdsArr != null) {
                        for (i in 0 until delProdsArr.length()) {
                            val key = delProdsArr.optString(i).trim().lowercase()
                            if (key.isNotBlank()) deletedProductsFromCloud.add(key)
                        }
                    }
                    val refreshedProds = repository.getAllProductsDirect()
                    val seenLocalNames = HashSet<String>()
                    for (lp in refreshedProds) {
                        val lpNorm = lp.name.trim().lowercase()
                        val isExplicitlyDeleted = lpNorm in deletedProductsFromCloud || (lp.id > 0 && lp.id.toString() in deletedProductsFromCloud)
                        val inCloud = lpNorm in cloudProductNames || (lp.id > 0 && lp.id in cloudProductIds && lpNorm in cloudProductNames)
                        val missingFromNonEmptyCloud = prodsArr.length() > 0 && !inCloud
                        val isDupName = !seenLocalNames.add(lpNorm)
                        if (lp.isSynced && (isExplicitlyDeleted || missingFromNonEmptyCloud || isDupName)) {
                            repository.deleteProduct(lp)
                        }
                    }
                    onProgress(0.85f, "ကုန်ပစ္စည်းနှင့် Stock လက်ကျန်များ ထည့်သွင်းပြီးပါပြီ...")

                // 4. Restore customers (deduplicated by name and cleans up existing duplicates in local Room, with multi-device deletion sync)
                val currentLocalCustomers = repository.getAllCustomersDirect()
                val localCustomersByName = mutableMapOf<String, Customer>()
                for (c in currentLocalCustomers) {
                    val norm = c.name.trim().lowercase()
                    if (norm.isBlank() || norm in localCustomersByName) {
                        repository.deleteCustomer(c)
                    } else {
                        localCustomersByName[norm] = c
                    }
                }

                val deletedCustomersFromCloud = mutableSetOf<String>()
                val delCustsArr = resObj.optJSONArray("deletedCustomers")
                if (delCustsArr != null) {
                    for (i in 0 until delCustsArr.length()) {
                        deletedCustomersFromCloud.add(delCustsArr.optString(i).trim().lowercase())
                    }
                }
                for (norm in pendingDeletedCustomers) {
                    deletedCustomersFromCloud.add(norm.trim().lowercase())
                }
                    for ((norm, c) in localCustomersByName) {
                        if (norm in deletedCustomersFromCloud || c.name.trim().lowercase() in deletedCustomersFromCloud || c.id.toString() in deletedCustomersFromCloud) {
                            repository.deleteCustomer(c)
                        }
                    }
                for (i in 0 until custsArr.length()) {
                    val c = custsArr.getJSONObject(i)
                    val cName = c.optString("name").trim()
                    if (cName.isBlank()) continue
                    val norm = cName.lowercase()
                    val cIdStr = c.optString("id", "")
                    if (norm in deletedCustomersFromCloud || cName in deletedCustomersFromCloud || (cIdStr.isNotBlank() && cIdStr in deletedCustomersFromCloud)) {
                        continue
                    }
                    val existing = localCustomersByName[norm]
                    if (existing != null) {
                        val updated = existing.copy(
                            name = cName,
                            phone = if (c.has("phone")) c.optString("phone") else existing.phone,
                            address = if (c.has("address")) c.optString("address") else existing.address,
                            note = if (c.has("note")) c.optString("note") else existing.note,
                            isSynced = true
                        )
                        repository.updateCustomer(updated)
                        localCustomersByName[norm] = updated
                    } else {
                        val customer = Customer(
                            name = cName,
                            phone = c.optString("phone", ""),
                            address = c.optString("address", ""),
                            note = c.optString("note", ""),
                            isSynced = true
                        )
                        repository.insertCustomer(customer)
                        localCustomersByName[norm] = customer
                        cCount++
                    }
                }

                // 5. Restore product groups (with multi-device deletion sync)
                val deletedGroupsFromCloud = mutableSetOf<String>()
                val delGroupsArr = resObj.optJSONArray("deletedGroups")
                if (delGroupsArr != null) {
                    for (i in 0 until delGroupsArr.length()) {
                        deletedGroupsFromCloud.add(delGroupsArr.optString(i).trim().lowercase())
                    }
                }
                for (norm in pendingDeletedGroups) {
                    deletedGroupsFromCloud.add(norm.trim().lowercase())
                }
                val currentLocalGroups = repository.getAllGroupsDirect()
                for (g in currentLocalGroups) {
                    val norm = g.name.trim().lowercase()
                    if (norm in deletedGroupsFromCloud || norm in pendingDeletedGroups || g.name in pendingDeletedGroups) {
                        repository.deleteGroup(g)
                    }
                }
                val groupsArr = resObj.optJSONArray("groups") ?: JSONArray()
                for (i in 0 until groupsArr.length()) {
                    val gName = groupsArr.optString(i).trim()
                    val norm = gName.lowercase()
                    if (gName.isNotBlank() && norm !in deletedGroupsFromCloud && norm !in pendingDeletedGroups && gName !in pendingDeletedGroups) {
                        repository.insertGroup(ProductGroup(gName))
                    }
                }

                // 6. Restore product units (with multi-device deletion sync)
                val deletedUnitsFromCloud = mutableSetOf<String>()
                val delUnitsArr = resObj.optJSONArray("deletedUnits")
                if (delUnitsArr != null) {
                    for (i in 0 until delUnitsArr.length()) {
                        deletedUnitsFromCloud.add(delUnitsArr.optString(i).trim().lowercase())
                    }
                }
                for (norm in pendingDeletedUnits) {
                    deletedUnitsFromCloud.add(norm.trim().lowercase())
                }
                val currentLocalUnits = repository.getAllUnitsDirect()
                for (u in currentLocalUnits) {
                    val norm = u.name.trim().lowercase()
                    if (norm in deletedUnitsFromCloud || norm in pendingDeletedUnits || u.name in pendingDeletedUnits) {
                        repository.deleteUnit(u)
                    }
                }
                val unitsArr = resObj.optJSONArray("units") ?: JSONArray()
                for (i in 0 until unitsArr.length()) {
                    val uName = unitsArr.optString(i).trim()
                    val norm = uName.lowercase()
                    if (uName.isNotBlank() && norm !in deletedUnitsFromCloud && norm !in pendingDeletedUnits && uName !in pendingDeletedUnits) {
                        repository.insertUnit(ProductUnit(uName))
                    }
                }

                // 7. Clean up duplicated payments in local Room, then restore uniquely (with multi-device deletion sync)
                val currentLocalPayments = repository.getAllPaymentsDirect()
                val deletedPaymentsFromCloud = mutableSetOf<String>()
                val delPaymentsArr = resObj.optJSONArray("deletedPayments")
                if (delPaymentsArr != null) {
                    for (i in 0 until delPaymentsArr.length()) {
                        deletedPaymentsFromCloud.add(delPaymentsArr.optString(i).trim().lowercase())
                    }
                }
                for (norm in pendingDeletedPayments) {
                    deletedPaymentsFromCloud.add(norm.trim().lowercase())
                }
                val localSeenMethods = mutableSetOf<String>()
                for (p in currentLocalPayments) {
                    val norm = p.method.trim().lowercase()
                    if (norm in localSeenMethods || norm in deletedPaymentsFromCloud || norm in pendingDeletedPayments || p.method in pendingDeletedPayments) {
                        repository.deletePayment(p)
                    } else {
                        localSeenMethods.add(norm)
                    }
                }

                val paymentsArr = resObj.optJSONArray("payments") ?: JSONArray()
                for (i in 0 until paymentsArr.length()) {
                    val pay = paymentsArr.getJSONObject(i)
                    val m = if (pay.has("method")) pay.optString("method") else pay.optString("payment_method", "")
                    val clean = m.trim()
                    val norm = clean.lowercase()
                    if (clean.isNotBlank() && norm !in deletedPaymentsFromCloud && norm !in pendingDeletedPayments && clean !in pendingDeletedPayments) {
                        if (norm !in localSeenMethods) {
                            localSeenMethods.add(norm)
                            repository.insertPayment(Payment(
                                method = clean,
                                amount = pay.optDouble("amount", 0.0),
                                date = pay.optLong("date", System.currentTimeMillis())
                            ))
                        }
                    }
                }

                // 8. Restore suppliers (deduplicated by name and cleans up existing duplicates in local Room, with multi-device deletion sync)
                val currentLocalSuppliers = repository.getAllSuppliersDirect()
                val localSuppliersByName = mutableMapOf<String, Supplier>()
                for (s in currentLocalSuppliers) {
                    val norm = s.name.trim().lowercase()
                    if (norm.isBlank() || norm in localSuppliersByName) {
                        repository.deleteSupplier(s)
                    } else {
                        localSuppliersByName[norm] = s
                    }
                }

                val deletedSuppliersFromCloud = mutableSetOf<String>()
                val delSupsArr = resObj.optJSONArray("deletedSuppliers")
                if (delSupsArr != null) {
                    for (i in 0 until delSupsArr.length()) {
                        deletedSuppliersFromCloud.add(delSupsArr.optString(i).trim().lowercase())
                    }
                }
                for (norm in pendingDeletedSuppliers) {
                    deletedSuppliersFromCloud.add(norm.trim().lowercase())
                }
                for ((norm, s) in localSuppliersByName) {
                    if (norm in deletedSuppliersFromCloud || s.name.trim().lowercase() in deletedSuppliersFromCloud || s.id.toString() in deletedSuppliersFromCloud) {
                        repository.deleteSupplier(s)
                    }
                }

                val suppliersArr = resObj.optJSONArray("suppliers") ?: JSONArray()
                for (i in 0 until suppliersArr.length()) {
                    val s = suppliersArr.getJSONObject(i)
                    val sName = s.optString("name").trim()
                    if (sName.isBlank()) continue
                    val norm = sName.lowercase()
                    val sIdStr = s.optString("id", "")
                    if (norm in deletedSuppliersFromCloud || sName in deletedSuppliersFromCloud || (sIdStr.isNotBlank() && sIdStr in deletedSuppliersFromCloud)) {
                        continue
                    }
                    val existing = localSuppliersByName[norm]
                    if (existing != null) {
                        val updated = existing.copy(
                            name = sName,
                            phone = if (s.has("phone")) s.optString("phone") else existing.phone,
                            address = if (s.has("address")) s.optString("address") else existing.address,
                            note = if (s.has("note")) s.optString("note") else existing.note
                        )
                        repository.updateSupplier(updated)
                        localSuppliersByName[norm] = updated
                    } else {
                        val newSupplier = Supplier(
                            name = sName,
                            phone = s.optString("phone", ""),
                            address = s.optString("address", ""),
                            note = s.optString("note", "")
                        )
                        repository.insertSupplier(newSupplier)
                        localSuppliersByName[norm] = newSupplier
                    }
                }

                // 9. Restore expense categories (deduplicated by name)
                val currentCats = repository.getAllExpenseCategoriesDirect()
                val seenCatNames = currentCats.map { it.name.trim().lowercase() }.toMutableSet()
                for (i in 0 until expCatsArr.length()) {
                    val ec = expCatsArr.getJSONObject(i)
                    val catName = if (ec.has("name")) ec.optString("name") else ec.optString("category_name", "")
                    val clean = catName.trim()
                    if (clean.isNotBlank() && clean.lowercase() !in seenCatNames) {
                        seenCatNames.add(clean.lowercase())
                        repository.insertExpenseCategory(ExpenseCategory(
                            name = clean,
                            iconName = if (ec.has("iconName")) ec.optString("iconName") else ec.optString("icon_name", "ShoppingCart")
                        ))
                    }
                }

                // 10. Restore expenses (O(1) deduplicated lookup)
                val currentExpenses = repository.getAllExpensesDirect()
                val existingExpenseKeys = HashSet<String>(currentExpenses.size * 2)
                for (ex in currentExpenses) {
                    existingExpenseKeys.add("${ex.timestamp}::${ex.amount}::${ex.description}")
                }
                var restoredExpensesCount = 0
                for (i in 0 until expensesArr.length()) {
                    val exp = expensesArr.getJSONObject(i)
                    val catName = if (exp.has("categoryName")) exp.optString("categoryName") else exp.optString("category_name", "")
                    val desc = exp.optString("description", "")
                    val amt = exp.optDouble("amount", 0.0)
                    val pMethod = if (exp.has("paymentMethod")) exp.optString("paymentMethod") else exp.optString("payment_method", "CASH")
                    val expNote = exp.optString("note", "")
                    val ts = exp.optLong("timestamp", System.currentTimeMillis())
                    val dStr = if (exp.has("dateString")) exp.optString("dateString") else exp.optString("date_string", "")
                    val tStr = if (exp.has("timeString")) exp.optString("timeString") else exp.optString("time_string", "")

                    val key = "$ts::$amt::$desc"
                    if (existingExpenseKeys.add(key) && (catName.isNotBlank() || desc.isNotBlank())) {
                        repository.insertExpense(Expense(
                            categoryName = catName,
                            description = desc,
                            amount = amt,
                            paymentMethod = pMethod,
                            note = expNote,
                            timestamp = ts,
                            dateString = dStr,
                            timeString = tStr
                        ))
                        restoredExpensesCount++
                    }
                }

                // 11. Restore stock adjustments (O(1) deduplicated lookup)
                val currentAdjustments = repository.getAllStockAdjustmentsDirect()
                val existingAdjKeys = HashSet<String>(currentAdjustments.size * 2)
                for (adj in currentAdjustments) {
                    existingAdjKeys.add("${adj.timestamp}::${adj.productName.trim().lowercase()}::${adj.adjustmentType}::${adj.quantity}")
                }
                val adjProdsByName = repository.getAllProductsDirect().associateBy { it.name.trim().lowercase() }
                for (i in 0 until adjustmentsArr.length()) {
                    val sa = adjustmentsArr.getJSONObject(i)
                    val pName = sa.optString("productName", "")
                    val rawPid = sa.optLong("productId", 0L)
                    val matchedPid = adjProdsByName[pName.trim().lowercase()]?.id ?: rawPid
                    val adjType = sa.optString("adjustmentType", "STOCK_OUT")
                    val rTitle = sa.optString("reasonTitle", "")
                    val desc = sa.optString("description", "")
                    val qty = sa.optInt("quantity", 0)
                    val beforeQty = sa.optInt("beforeQuantity", 0)
                    val afterQty = sa.optInt("afterQuantity", 0)
                    val ts = sa.optLong("timestamp", System.currentTimeMillis())
                    val adjBy = sa.optString("adjustedBy", "")

                    val adjKey = "$ts::${pName.trim().lowercase()}::$adjType::$qty"
                    if (pName.isNotBlank() && qty > 0 && existingAdjKeys.add(adjKey)) {
                        repository.insertStockAdjustment(
                            StockAdjustment(
                                productId = matchedPid,
                                productName = pName,
                                adjustmentType = adjType,
                                reasonTitle = rTitle,
                                description = desc,
                                quantity = qty,
                                beforeQuantity = beforeQty,
                                afterQuantity = afterQty,
                                timestamp = ts,
                                adjustedBy = adjBy,
                                isSynced = true
                            )
                        )
                    }
                }
                } // end runInTransaction

                onProgress(1.0f, "ဒေတာများ အောင်မြင်စွာ ပြန်လည်ရယူပြီးပါပြီ။")

                PullResult(
                    success = true,
                    vouchersRestored = vCount,
                    productsRestored = pCount,
                    customersRestored = cCount,
                    message = "Cloud မှ အောင်မြင်စွာ ပြန်လည်ရယူပြီးပါပြီ (အရောင်း/အဝယ်: $vCount, ပစ္စည်း: $pCount, ဖောက်သည်: $cCount, အမျိုးအစား: ${groupsArr.length()}, ယူနစ်: ${unitsArr.length()}, ကုန်သွင်းသူ: ${suppliersArr.length()}, ကုန်ကျစရိတ်: ${expensesArr.length()})"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Pull cloud data failed: ${t.message}")
                PullResult(false, message = "Cloud မှ ပြန်လည်ရယူရာတွင် အမှားဖြစ်ပေါ်ပါသည်: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }
}

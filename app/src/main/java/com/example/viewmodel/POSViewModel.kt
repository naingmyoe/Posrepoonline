package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

data class CartItem(
    val product: Product,
    val quantity: Int
)

class POSViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = Repository(database)
    private val sharedPrefs = application.getSharedPreferences("pos_prefs", android.content.Context.MODE_PRIVATE)
    private var isSavingCompletion = false
    private val syncMutex = Mutex()
    @Volatile private var isLoggingOutState = false
    @Volatile private var sessionToken = 0L

    // Flow states
    val allAccounts = repository.allAccounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allProducts = repository.allProducts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allGroups = repository.allGroups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allUnits = repository.allUnits.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allVouchers = repository.allVouchers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allVoucherItems = repository.allVoucherItems.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allCustomers = repository.allCustomers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allSuppliers = repository.allSuppliers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allPayments = repository.allPayments.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allExpenseCategories = repository.allExpenseCategories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allExpenses = repository.allExpenses.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allStockAdjustments = repository.allStockAdjustments.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Selected expense category state for AddExpenseScreen
    val selectedExpenseCategoryName = MutableStateFlow("No Category")
    val selectedExpenseCategoryIcon = MutableStateFlow("ShoppingCart")

    // Active logged in user
    private val _currentUser = MutableStateFlow<UserAccount?>(null)
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    // Global account expiration StateFlow
    val isAccountExpired = MutableStateFlow<Boolean>(false)

    fun checkAndSetAccountExpired(phone: String? = null): Boolean {
        val targetPhone = phone ?: _currentUser.value?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        if (targetPhone.isBlank()) return false
        val now = System.currentTimeMillis()
        val expiresAt = sharedPrefs.getLong("account_expires_at_$targetPhone", 0L)
        val isExpiredByTime = (expiresAt > 0L && now > expiresAt)
        val isExpiredByStatus = (_currentUser.value?.status == "expired")
        val expired = isExpiredByTime || isExpiredByStatus
        if (expired) {
            isAccountExpired.value = true
            viewModelScope.launch(Dispatchers.IO) {
                val localAcc = repository.getAccountByPhone(targetPhone)
                if (localAcc != null && localAcc.status != "expired") {
                    repository.insertAccount(localAcc.copy(status = "expired"))
                }
            }
            if (_currentUser.value != null && _currentUser.value?.status != "expired") {
                _currentUser.value = _currentUser.value?.copy(status = "expired")
            }
        } else {
            isAccountExpired.value = false
        }
        return expired
    }

    suspend fun checkAndSetAccountExpiredDirect(phone: String? = null): Boolean {
        val targetPhone = phone ?: _currentUser.value?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        if (targetPhone.isBlank()) return false
        val now = System.currentTimeMillis()
        val expiresAt = sharedPrefs.getLong("account_expires_at_$targetPhone", 0L)
        val localAcc = repository.getAccountByPhone(targetPhone)
        val isExpiredByTime = (expiresAt > 0L && now > expiresAt)
        val isExpiredByStatus = (localAcc?.status == "expired" || _currentUser.value?.status == "expired")
        val expired = isExpiredByTime || isExpiredByStatus
        if (expired) {
            isAccountExpired.value = true
            if (localAcc != null && localAcc.status != "expired") {
                repository.insertAccount(localAcc.copy(status = "expired"))
            }
            if (_currentUser.value != null && _currentUser.value?.status != "expired") {
                _currentUser.value = _currentUser.value?.copy(status = "expired")
            }
        } else {
            isAccountExpired.value = false
        }
        return expired
    }

    suspend fun getLocalAccountByPhone(phone: String): UserAccount? {
        return repository.getAccountByPhone(phone)
    }

    // Registration UI Temp States
    val selectedRole = MutableStateFlow("ADMIN")
    val registerUsername = MutableStateFlow("")
    val registerBusinessName = MutableStateFlow("")
    val registerBusinessType = MutableStateFlow("")
    val registerAddress = MutableStateFlow("")
    val registerPhone = MutableStateFlow("")
    val registerPassword = MutableStateFlow("")
    val registerRetypePassword = MutableStateFlow("")

    // Circular loading states during registration
    private val _registrationProgress = MutableStateFlow(0f)
    val registrationProgress: StateFlow<Float> = _registrationProgress.asStateFlow()

    private val _registrationStatus = MutableStateFlow("")
    val registrationStatus: StateFlow<String> = _registrationStatus.asStateFlow()

    // Product registration inputs
    val prodName = MutableStateFlow("")
    val prodGroup = MutableStateFlow("")
    val prodPurchasePrice = MutableStateFlow("")
    val prodSellingPrice = MutableStateFlow("")
    val prodUnit = MutableStateFlow("")
    val prodNote = MutableStateFlow("")
    val prodTrackStock = MutableStateFlow(true)
    val prodBarcode = MutableStateFlow("")
    val prodQty = MutableStateFlow("")
    val prodAlertQty = MutableStateFlow("")
    val prodImageUri = MutableStateFlow("")

    // Editing States for Customers & Suppliers
    val editingCustomer = MutableStateFlow<Customer?>(null)
    val editingSupplier = MutableStateFlow<Supplier?>(null)
    val editingProduct = MutableStateFlow<Product?>(null)
    val isProductGridView = MutableStateFlow(sharedPrefs.getBoolean("is_product_grid_view", false))
    val isDarkTheme = MutableStateFlow(sharedPrefs.getBoolean("is_dark_theme", false))
    val selectedPrinterName = MutableStateFlow(sharedPrefs.getString("selected_printer_name", "") ?: "")
    val selectedPrinterMac = MutableStateFlow(sharedPrefs.getString("selected_printer_mac", "") ?: "")
    val selectedPaperWidth = MutableStateFlow(sharedPrefs.getString("selected_paper_width", "80mm") ?: "80mm")
    val selectedPrintMode = MutableStateFlow(sharedPrefs.getString("selected_print_mode", "mode2") ?: "mode2")
    val selectedTemplate = MutableStateFlow(sharedPrefs.getString("selected_template", "template2") ?: "template2")
    val selectedCurrency = MutableStateFlow(sharedPrefs.getString("selected_currency", "Ks") ?: "Ks")
    val appLanguage = MutableStateFlow(sharedPrefs.getString("app_language", "my") ?: "my")

    // Dynamic Header & Footer states
    val headerLines = MutableStateFlow(
        run {
            val raw = sharedPrefs.getString("header_lines", "") ?: ""
            if (raw.contains("|||")) raw.split("|||").filter { it.isNotEmpty() }
            else raw.split(",").filter { it.isNotEmpty() }
        }
    )
    val footerLines = MutableStateFlow(
        run {
            val raw = sharedPrefs.getString("footer_lines", "") ?: ""
            if (raw.contains("|||")) raw.split("|||").filter { it.isNotEmpty() }
            else raw.split(",").filter { it.isNotEmpty() }
        }
    )
    val headerFontSize = MutableStateFlow(sharedPrefs.getFloat("header_font_size", 40f))
    val headerFontFamily = MutableStateFlow(sharedPrefs.getString("header_font_family", "Default") ?: "Default")
    val headerIsBold = MutableStateFlow(sharedPrefs.getBoolean("header_is_bold", true))
    val headerAlignment = MutableStateFlow(sharedPrefs.getString("header_alignment", "Center") ?: "Center")
    val headerLogoUri = MutableStateFlow(sharedPrefs.getString("header_logo_uri", "") ?: "")
    val paperSpacing = MutableStateFlow(sharedPrefs.getFloat("paper_spacing", 10f))
    val bottomFeed = MutableStateFlow(sharedPrefs.getFloat("bottom_feed", 15f))
    val printBrightness = MutableStateFlow(sharedPrefs.getFloat("print_brightness", 100f))
    val receiptBodyFontSize = MutableStateFlow(sharedPrefs.getFloat("receipt_body_font_size", 16f))
    val printReceiptDiscount = MutableStateFlow(sharedPrefs.getBoolean("print_receipt_discount", true))
    val printReceiptFee = MutableStateFlow(sharedPrefs.getBoolean("print_receipt_fee", true))
    val lineSpacing = MutableStateFlow(sharedPrefs.getFloat("line_spacing", 12f))

    // Customizable Receipt/Voucher Labels
    val labelDate = MutableStateFlow(sharedPrefs.getString("label_date", "ရက်စွဲ") ?: "ရက်စွဲ")
    val labelCustomerName = MutableStateFlow(sharedPrefs.getString("label_customer_name", "ဈေးဝယ်သူ") ?: "ဈေးဝယ်သူ")
    val labelNo = MutableStateFlow(sharedPrefs.getString("label_no", "စဉ်") ?: "စဉ်")
    val labelItemName = MutableStateFlow(sharedPrefs.getString("label_item_name", "အမည်") ?: "အမည်")
    val labelQuantity = MutableStateFlow(sharedPrefs.getString("label_quantity", "ဦးရေ") ?: "ဦးရေ")
    val labelUnitPrice = MutableStateFlow(sharedPrefs.getString("label_unit_price", "ဈေးနှုန်း") ?: "ဈေးနှုန်း")
    val labelLineTotal = MutableStateFlow(sharedPrefs.getString("label_line_total", "သင့်ငွေ") ?: "သင့်ငွေ")
    val labelSubTotal = MutableStateFlow(sharedPrefs.getString("label_sub_total", "ကျသင့်ငွေ") ?: "ကျသင့်ငွေ")
    val labelTotal = MutableStateFlow(sharedPrefs.getString("label_total", "စုစုပေါင်း") ?: "စုစုပေါင်း")
    val labelPaid = MutableStateFlow(sharedPrefs.getString("label_paid", "ပေးငွေ") ?: "ပေးငွေ")
    val labelDue = MutableStateFlow(sharedPrefs.getString("label_due", "အမ်းငွေ") ?: "အမ်းငွေ")
    val labelVoucherNo = MutableStateFlow(sharedPrefs.getString("label_voucher_no", "ပြေစာအမှတ်") ?: "ပြေစာအမှတ်")


    // Active Voucher/Draft States
    val activeVoucherId = MutableStateFlow<String?>(null)
    val activeCustomerName = MutableStateFlow("Not Register")
    val activePaymentMethod = MutableStateFlow("CASH")
    val activeDiscount = MutableStateFlow(0.0)
    val activeFee = MutableStateFlow(0.0)
    val activeNote = MutableStateFlow("")

    val editingVoucherInitialItems = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val editingVoucher = MutableStateFlow<Voucher?>(null)

    fun getAvailableStock(product: Product): Int {
        if (!product.trackStock || isPurchaseMode.value) return Int.MAX_VALUE
        val originalQty = editingVoucherInitialItems.value[product.id] ?: 0
        val currentDbProduct = allProducts.value.find { it.id == product.id }
        val currentStock = currentDbProduct?.quantity ?: product.quantity
        return (currentStock + originalQty).coerceAtLeast(0)
    }

    val isPurchaseMode = MutableStateFlow(false)
    val isPurchaseHistoryMode = MutableStateFlow(false)
    
    // Stock report mode: "DEFAULT", "PURCHASE_VALUE", "SELLING_VALUE"
    val stockReportMode = MutableStateFlow("DEFAULT")

    val calendarYear = MutableStateFlow(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
    val calendarMonth = MutableStateFlow(java.util.Calendar.getInstance().get(java.util.Calendar.MONTH))

    // Persisted Filter states for Sales History (ReceiptsScreen)
    val salesHistoryFilterMode = MutableStateFlow("month")
    val salesHistorySelectedCustomer = MutableStateFlow<Customer?>(null)
    val salesHistorySelectedPaymentMethod = MutableStateFlow<String?>(null)
    val salesHistoryCalendarTime = MutableStateFlow<Long>(java.util.Calendar.getInstance().timeInMillis)

    fun resetSalesHistoryFilters() {
        salesHistoryFilterMode.value = "month"
        salesHistorySelectedCustomer.value = null
        salesHistorySelectedPaymentMethod.value = null
        val now = java.util.Calendar.getInstance()
        calendarYear.value = now.get(java.util.Calendar.YEAR)
        calendarMonth.value = now.get(java.util.Calendar.MONTH)
        salesHistoryCalendarTime.value = now.timeInMillis
    }

    // Shopping Cart State
    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart.asStateFlow()

    // Offline Sync State
    private val _isOfflineSaved = MutableStateFlow(true)
    val isOfflineSaved: StateFlow<Boolean> = _isOfflineSaved.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    val cloudDownloadProgress = MutableStateFlow(0f)
    val cloudDownloadProgressMsg = MutableStateFlow("Cloud မှ အကောင့်ဒေတာများ ရယူနေပါသည်...")

    val unsyncedVouchersCount: StateFlow<Int> = repository.unsyncedVoucherCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )
    val unsyncedProductsCount: StateFlow<Int> = repository.unsyncedProductCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )
    val unsyncedCustomersCount: StateFlow<Int> = repository.unsyncedCustomerCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val unsyncedGroupsCount = MutableStateFlow(sharedPrefs.getStringSet("pending_groups_set", emptySet())?.size ?: 0)
    val unsyncedUnitsCount = MutableStateFlow(sharedPrefs.getStringSet("pending_units_set", emptySet())?.size ?: 0)
    val unsyncedPaymentsCount = MutableStateFlow(sharedPrefs.getStringSet("pending_payments_set", emptySet())?.size ?: 0)
    val unsyncedSuppliersCount = MutableStateFlow(sharedPrefs.getStringSet("pending_suppliers_set", emptySet())?.size ?: 0)

    fun recordPendingGroupChange(name: String) {
        val set = (sharedPrefs.getStringSet("pending_groups_set", emptySet()) ?: emptySet()).toMutableSet()
        set.add(name)
        sharedPrefs.edit().putStringSet("pending_groups_set", set).apply()
        unsyncedGroupsCount.value = set.size
    }

    fun recordPendingUnitChange(name: String) {
        val set = (sharedPrefs.getStringSet("pending_units_set", emptySet()) ?: emptySet()).toMutableSet()
        set.add(name)
        sharedPrefs.edit().putStringSet("pending_units_set", set).apply()
        unsyncedUnitsCount.value = set.size
    }

    fun recordPendingPaymentChange(name: String) {
        val set = (sharedPrefs.getStringSet("pending_payments_set", emptySet()) ?: emptySet()).toMutableSet()
        set.add(name)
        sharedPrefs.edit().putStringSet("pending_payments_set", set).apply()
        unsyncedPaymentsCount.value = set.size
    }

    fun recordPendingSupplierChange(name: String) {
        val set = (sharedPrefs.getStringSet("pending_suppliers_set", emptySet()) ?: emptySet()).toMutableSet()
        set.add(name)
        sharedPrefs.edit().putStringSet("pending_suppliers_set", set).apply()
        unsyncedSuppliersCount.value = set.size
    }

    fun clearAllPendingMasterDataChanges() {
        sharedPrefs.edit()
            .remove("pending_groups_set")
            .remove("pending_units_set")
            .remove("pending_payments_set")
            .remove("pending_suppliers_set")
            .apply()
        unsyncedGroupsCount.value = 0
        unsyncedUnitsCount.value = 0
        unsyncedPaymentsCount.value = 0
        unsyncedSuppliersCount.value = 0
    }

    val totalUnsyncedCount: StateFlow<Int> = combine(
        repository.totalUnsyncedCount,
        unsyncedGroupsCount,
        unsyncedUnitsCount,
        unsyncedPaymentsCount,
        unsyncedSuppliersCount
    ) { baseTotal, groups, units, payments, suppliers ->
        baseTotal + groups + units + payments + suppliers
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )
    val cloudflareWorkerUrl = MutableStateFlow(
        run {
            val saved = sharedPrefs.getString("cf_worker_url", null)
            if (saved.isNullOrBlank() || saved.contains("74.81.63.87")) {
                sharedPrefs.edit().putString("cf_worker_url", "http://171.22.172.107:8081").apply()
                "http://171.22.172.107:8081"
            } else {
                saved
            }
        }
    )
    val isOnlineState = MutableStateFlow(com.example.util.SyncManager.isNetworkAvailable(application))
    val isServerReachable = MutableStateFlow(false)
    val syncStatusMessage = MutableStateFlow("")
    val lastSyncTime = MutableStateFlow(sharedPrefs.getLong("last_sync_time", 0L))

    var isFromSalesFlow = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val savedPhone = sharedPrefs.getString("logged_in_phone", null)
            if (!savedPhone.isNullOrBlank()) {
                val acc = repository.getAccountByPhone(savedPhone)
                if (acc != null) {
                    _currentUser.value = acc
                }
                checkAndSetAccountExpired(savedPhone)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            com.example.util.BackupAndExcelUtils.performAutoDailyBackup(application)
        }

        viewModelScope.launch {
            cloudflareWorkerUrl.collect { value ->
                sharedPrefs.edit().putString("cf_worker_url", value).apply()
            }
        }

        // Instant Deduplication: Clean up any duplicate customers & suppliers created by previous downloads
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentCustomers = repository.getAllCustomersDirect()
                val seenCust = mutableSetOf<String>()
                for (c in currentCustomers) {
                    val norm = c.name.trim().lowercase()
                    if (norm.isBlank() || norm in seenCust) {
                        repository.deleteCustomer(c)
                    } else {
                        seenCust.add(norm)
                    }
                }

                val currentSuppliers = repository.getAllSuppliersDirect()
                val seenSup = mutableSetOf<String>()
                for (s in currentSuppliers) {
                    val norm = s.name.trim().lowercase()
                    if (norm.isBlank() || norm in seenSup) {
                        repository.deleteSupplier(s)
                    } else {
                        seenSup.add(norm)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Instant Network Callback: as soon as internet connects, immediately trigger 100% cloud mirror sync!
        try {
            val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && cm != null) {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        viewModelScope.launch {
                            isOnlineState.value = true
                            delay(1000) // Brief pause for connection to settle
                            if (isLoggingOutState) return@launch
                            val savedLoggedPhone = sharedPrefs.getString("logged_in_phone", null)
                            if (savedLoggedPhone.isNullOrBlank()) return@launch
                            val currentUrl = cloudflareWorkerUrl.value
                            val serverCheck = com.example.util.SyncManager.checkServerReachability(currentUrl)
                            isServerReachable.value = serverCheck.isReachable
                            val phone = _currentUser.value?.phoneNo ?: sharedPrefs.getString("logged_in_phone", null)
                            val activeAcc = _currentUser.value ?: (if (!phone.isNullOrBlank()) repository.getAccountByPhone(phone) else null)
                            val isAllowedToSync = activeAcc != null && activeAcc.status == "on" && (activeAcc.role != "CASHIER" || activeAcc.adminPhone.isNotBlank())
                            if (!isLoggingOutState && !phone.isNullOrBlank() && isAllowedToSync && serverCheck.isReachable && !_isUploading.value) {
                                if (sharedPrefs.getBoolean("pending_db_restore_mirror", false)) {
                                    mirrorLocalToCloudDirect()
                                } else {
                                    triggerRealSync()
                                }
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        isOnlineState.value = false
                        isServerReachable.value = false
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Periodic network and server reachability check, auto-sync, account expiration guard and 5-device real-time sync loop
        viewModelScope.launch {
            var cycleCount = 0
            while (true) {
                try {
                    val available = com.example.util.SyncManager.isNetworkAvailable(application)
                    isOnlineState.value = available
                    val loggedPhonePref = sharedPrefs.getString("logged_in_phone", null)
                    val phone = if (isLoggingOutState || loggedPhonePref.isNullOrBlank()) null else (_currentUser.value?.phoneNo ?: loggedPhonePref)

                    if (!phone.isNullOrBlank()) {
                        checkAndSetAccountExpired(phone)
                    }

                    if (available && !isLoggingOutState && !phone.isNullOrBlank()) {
                        val currentUrl = cloudflareWorkerUrl.value
                        val serverCheck = com.example.util.SyncManager.checkServerReachability(currentUrl)
                        isServerReachable.value = serverCheck.isReachable
                        if (!isLoggingOutState && !sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                            // Check user activation / expiration status with server periodically (every 2 cycles = 12s)
                            if (serverCheck.isReachable && cycleCount % 2 == 0) {
                                checkUserActivationStatus(application, phone)
                                checkAndSetAccountExpired(phone)
                            }
                            if (!isLoggingOutState && !_isUploading.value && serverCheck.isReachable) {
                                val activeAcc = _currentUser.value ?: repository.getAccountByPhone(phone)
                                val isAllowedToSync = activeAcc != null && activeAcc.status == "on" && (activeAcc.role != "CASHIER" || activeAcc.adminPhone.isNotBlank())
                                if (isAllowedToSync && !sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                                    val pendingRestoreMirror = sharedPrefs.getBoolean("pending_db_restore_mirror", false)
                                    if (pendingRestoreMirror) {
                                        mirrorLocalToCloudDirect()
                                    } else {
                                        // 1. Upload any local offline backlog
                                        val unsyncedV = repository.getUnsyncedCompletedVouchers()
                                        val unsyncedP = repository.getUnsyncedProducts()
                                        val unsyncedC = repository.getUnsyncedCustomers()
                                        if (unsyncedV.isNotEmpty() || unsyncedP.isNotEmpty() || unsyncedC.isNotEmpty()) {
                                            triggerRealSync()
                                        }

                                        // 2. Multi-device real-time sync: silently pull latest sales, stock & product edits from other phones every 6s
                                        cycleCount++
                                        if (cycleCount >= 1 && !_isUploading.value && !isLoggingOutState && !sharedPrefs.getBoolean("pending_db_restore_mirror", false)) {
                                            cycleCount = 0
                                            val capturedToken = sessionToken
                                            syncMutex.withLock {
                                                val stillLoggedIn = !isLoggingOutState &&
                                                    capturedToken == sessionToken &&
                                                    !sharedPrefs.getString("logged_in_phone", null).isNullOrBlank() &&
                                                    !sharedPrefs.getBoolean("pending_db_restore_mirror", false)
                                                if (stillLoggedIn) {
                                                    val cloudTargetPhone = getCloudPhone(phone)
                                                    com.example.util.SyncManager.pullCloudData(
                                                        workerUrl = currentUrl,
                                                        userPhone = cloudTargetPhone,
                                                        repository = repository,
                                                        pendingDeletedCustomers = getPendingDeletedCustomers().toSet(),
                                                        pendingDeletedSuppliers = getPendingDeletedSuppliers().toSet(),
                                                        pendingDeletedGroups = getPendingDeletedGroups().toSet(),
                                                        pendingDeletedUnits = getPendingDeletedUnits().toSet(),
                                                        pendingDeletedPayments = getPendingDeletedPayments().toSet()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (!available) {
                        isServerReachable.value = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isServerReachable.value = false
                }
                delay(6000)
            }
        }

        viewModelScope.launch {
            isProductGridView.collect { value ->
                sharedPrefs.edit().putBoolean("is_product_grid_view", value).apply()
            }
        }

        viewModelScope.launch {
            isDarkTheme.collect { value ->
                sharedPrefs.edit().putBoolean("is_dark_theme", value).apply()
            }
        }

        viewModelScope.launch {
            selectedPrinterName.collect { value ->
                sharedPrefs.edit().putString("selected_printer_name", value).apply()
            }
        }

        viewModelScope.launch {
            selectedPrinterMac.collect { value ->
                sharedPrefs.edit().putString("selected_printer_mac", value).apply()
            }
        }

        viewModelScope.launch {
            selectedPaperWidth.collect { value ->
                sharedPrefs.edit().putString("selected_paper_width", value).apply()
            }
        }

        viewModelScope.launch {
            selectedPrintMode.collect { value ->
                sharedPrefs.edit().putString("selected_print_mode", value).apply()
            }
        }

        viewModelScope.launch {
            selectedTemplate.collect { value ->
                sharedPrefs.edit().putString("selected_template", value).apply()
            }
        }

        viewModelScope.launch {
            selectedCurrency.collect { value ->
                sharedPrefs.edit().putString("selected_currency", value).apply()
            }
        }

        com.example.util.AppLocalization.currentLang = appLanguage.value
        viewModelScope.launch {
            appLanguage.collect { value ->
                sharedPrefs.edit().putString("app_language", value).apply()
                com.example.util.AppLocalization.currentLang = value
                labelPaid.value = com.example.util.AppLocalization.translate("ပေးငွေ", value)
                labelDue.value = com.example.util.AppLocalization.translate("အမ်းငွေ", value)
            }
        }

        viewModelScope.launch {
            headerLines.collect { value ->
                sharedPrefs.edit().putString("header_lines", value.joinToString("|||")).apply()
            }
        }

        viewModelScope.launch {
            footerLines.collect { value ->
                sharedPrefs.edit().putString("footer_lines", value.joinToString("|||")).apply()
            }
        }

        viewModelScope.launch {
            headerFontSize.collect { value ->
                sharedPrefs.edit().putFloat("header_font_size", value).apply()
            }
        }

        viewModelScope.launch {
            headerFontFamily.collect { value ->
                sharedPrefs.edit().putString("header_font_family", value).apply()
            }
        }

        viewModelScope.launch {
            headerIsBold.collect { value ->
                sharedPrefs.edit().putBoolean("header_is_bold", value).apply()
            }
        }

        viewModelScope.launch {
            headerAlignment.collect { value ->
                sharedPrefs.edit().putString("header_alignment", value).apply()
            }
        }

        viewModelScope.launch {
            headerLogoUri.collect { value ->
                sharedPrefs.edit().putString("header_logo_uri", value).apply()
            }
        }

        viewModelScope.launch {
            paperSpacing.collect { value ->
                sharedPrefs.edit().putFloat("paper_spacing", value).apply()
            }
        }

        viewModelScope.launch {
            bottomFeed.collect { value ->
                sharedPrefs.edit().putFloat("bottom_feed", value).apply()
            }
        }

        viewModelScope.launch {
            printBrightness.collect { value ->
                sharedPrefs.edit().putFloat("print_brightness", value).apply()
            }
        }

        viewModelScope.launch {
            receiptBodyFontSize.collect { value ->
                sharedPrefs.edit().putFloat("receipt_body_font_size", value).apply()
            }
        }

        viewModelScope.launch {
            printReceiptDiscount.collect { value ->
                sharedPrefs.edit().putBoolean("print_receipt_discount", value).apply()
            }
        }

        viewModelScope.launch {
            printReceiptFee.collect { value ->
                sharedPrefs.edit().putBoolean("print_receipt_fee", value).apply()
            }
        }

        viewModelScope.launch {
            lineSpacing.collect { value ->
                sharedPrefs.edit().putFloat("line_spacing", value).apply()
            }
        }

        viewModelScope.launch {
            labelDate.collect { value -> sharedPrefs.edit().putString("label_date", value).apply() }
        }
        viewModelScope.launch {
            labelCustomerName.collect { value -> sharedPrefs.edit().putString("label_customer_name", value).apply() }
        }
        viewModelScope.launch {
            labelNo.collect { value -> sharedPrefs.edit().putString("label_no", value).apply() }
        }
        viewModelScope.launch {
            labelItemName.collect { value -> sharedPrefs.edit().putString("label_item_name", value).apply() }
        }
        viewModelScope.launch {
            labelQuantity.collect { value -> sharedPrefs.edit().putString("label_quantity", value).apply() }
        }
        viewModelScope.launch {
            labelUnitPrice.collect { value -> sharedPrefs.edit().putString("label_unit_price", value).apply() }
        }
        viewModelScope.launch {
            labelLineTotal.collect { value -> sharedPrefs.edit().putString("label_line_total", value).apply() }
        }
        viewModelScope.launch {
            labelSubTotal.collect { value -> sharedPrefs.edit().putString("label_sub_total", value).apply() }
        }
        viewModelScope.launch {
            labelTotal.collect { value -> sharedPrefs.edit().putString("label_total", value).apply() }
        }
        viewModelScope.launch {
            labelPaid.collect { value -> sharedPrefs.edit().putString("label_paid", value).apply() }
        }
        viewModelScope.launch {
            labelDue.collect { value -> sharedPrefs.edit().putString("label_due", value).apply() }
        }
        viewModelScope.launch {
            labelVoucherNo.collect { value -> sharedPrefs.edit().putString("label_voucher_no", value).apply() }
        }

        // Auto-save active voucher as draft on any changes
        viewModelScope.launch {
            combine(activeVoucherId, _cart, activeCustomerName, activePaymentMethod) { voucherId, cartList, customer, payment ->
                if (voucherId != null && !isSavingCompletion) {
                    val existingVoucher = repository.getVoucherDirect(voucherId)
                    if (existingVoucher != null && existingVoucher.isCompleted) {
                        return@combine
                    }
                    if (cartList.isEmpty()) {
                        repository.deleteVoucher(voucherId)
                        repository.deleteVoucherItems(voucherId)
                    } else {
                        val totalAmount = cartList.sumOf { (if (isPurchaseMode.value) it.product.purchasePrice else it.product.sellingPrice) * it.quantity }
                        val totalItems = cartList.sumOf { it.quantity }
                        
                        val timestamp = existingVoucher?.timestamp ?: System.currentTimeMillis()
                        
                        val voucher = Voucher(
                            receiptNo = voucherId,
                            timestamp = timestamp,
                            cashierName = _currentUser.value?.username ?: "Guest Cashier",
                            totalAmount = totalAmount,
                            totalItems = totalItems,
                            customerName = customer,
                            paymentMethod = payment,
                            isCompleted = false,
                            isPurchase = isPurchaseMode.value
                        )
                        
                        repository.insertVoucher(voucher)
                        repository.deleteVoucherItems(voucherId)
                        for (item in cartList) {
                            val vItem = VoucherItem(
                                voucherId = voucherId,
                                productId = item.product.id,
                                productName = item.product.name,
                                quantity = item.quantity,
                                purchasePrice = item.product.purchasePrice,
                                sellingPrice = item.product.sellingPrice
                            )
                            repository.insertVoucherItem(vItem)
                        }
                    }
                }
            }.collect {}
        }

        // Sync logged-in user with database accounts ONLY when a valid session exists
        viewModelScope.launch {
            allAccounts.collect { accList ->
                if (accList.isNotEmpty() && !isLoggingOutState) {
                    val savedPhone = sharedPrefs.getString("logged_in_phone", null)
                    if (!savedPhone.isNullOrBlank()) {
                        val currentPhone = _currentUser.value?.phoneNo
                        if (currentPhone.isNullOrEmpty() || accList.none { it.phoneNo == currentPhone }) {
                            val matched = accList.firstOrNull { it.phoneNo == savedPhone }
                            if (matched != null) {
                                _currentUser.value = matched
                                cacheUserRoleAndAdmin(matched)
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            // Pre-populate default payment methods (Cash and Credit) only if a user session is active
            val savedPhone = sharedPrefs.getString("logged_in_phone", null)
            if (!savedPhone.isNullOrBlank()) {
                ensureDefaultPaymentsIfEmpty()
            }
        }
    }

    suspend fun ensureDefaultPaymentsIfEmpty() {
        withContext(Dispatchers.IO) {
            repository.allPayments.first().let { payments ->
                if (payments.isEmpty()) {
                    repository.insertPayment(Payment(method = "Cash", amount = 0.0, date = System.currentTimeMillis()))
                    repository.insertPayment(Payment(method = "Credit", amount = 0.0, date = System.currentTimeMillis()))
                }
            }
        }
    }

    fun setAppLanguage(lang: String) {
        appLanguage.value = lang
        com.example.util.AppLocalization.currentLang = lang
        labelPaid.value = com.example.util.AppLocalization.translate("ပေးငွေ", lang)
        labelDue.value = com.example.util.AppLocalization.translate("အမ်းငွေ", lang)
    }

    // Device ID and Internet helpers
    fun getDeviceId(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("pos_prefs", android.content.Context.MODE_PRIVATE)
        var devId = prefs.getString("device_id", null)
        if (devId.isNullOrEmpty()) {
            val androidId = try {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (e: Exception) { null }
            devId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") androidId else java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", devId).apply()
        }
        return devId
    }

    fun isInternetAvailable(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm != null) {
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            if (capabilities != null) {
                return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                       capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                       capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        }
        return false
    }

    private fun cacheUserRoleAndAdmin(acc: UserAccount) {
        sharedPrefs.edit()
            .putString("user_role_${acc.phoneNo}", acc.role)
            .putString("cashier_admin_phone_${acc.phoneNo}", acc.adminPhone)
            .apply()
    }

    fun getCloudPhone(phoneNo: String? = null): String {
        val current = _currentUser.value
        val activePhone = phoneNo ?: current?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        if (activePhone.isBlank()) return ""
        if (current != null && current.phoneNo == activePhone && current.role == "CASHIER" && current.adminPhone.isNotBlank()) {
            return current.adminPhone
        }
        val cachedRole = sharedPrefs.getString("user_role_$activePhone", "") ?: ""
        val cachedAdminPhone = sharedPrefs.getString("cashier_admin_phone_$activePhone", "") ?: ""
        if ((current?.role == "CASHIER" || cachedRole == "CASHIER") && cachedAdminPhone.isNotBlank()) {
            return cachedAdminPhone
        }
        return activePhone
    }

    fun checkAccessAuthorization(
        serverStatus: String,
        localAccount: UserAccount?,
        currentDeviceId: String
    ): Boolean {
        if (serverStatus == "expired" || serverStatus == "device_limit_reached" || serverStatus == "off" || serverStatus == "pending" || serverStatus == "device_mismatch" || serverStatus == "not_found") {
            return false
        }
        if (localAccount == null || localAccount.status != "on") {
            return false
        }
        if (localAccount.role == "CASHIER" && localAccount.adminPhone.isBlank()) {
            return false
        }
        val targetPhone = localAccount.phoneNo
        val expiresAt = sharedPrefs.getLong("account_expires_at_$targetPhone", 0L)
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            return false
        }
        return (serverStatus == "on")
    }

    fun checkAccessAuthorization(
        serverIsActive: Boolean,
        localAccount: UserAccount?,
        currentDeviceId: String
    ): Boolean {
        val serverStatus = if (serverIsActive) "on" else "off"
        return checkAccessAuthorization(serverStatus, localAccount, currentDeviceId)
    }

    suspend fun checkUserActivationStatus(context: android.content.Context, phoneNo: String? = null): Pair<Boolean, String> {
        val targetPhone = phoneNo ?: sharedPrefs.getString("logged_in_phone", null) ?: currentUser.value?.phoneNo ?: registerPhone.value
        val deviceId = getDeviceId(context)
        val capturedToken = sessionToken

        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/check-status"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val jsonInputString = org.json.JSONObject().apply {
                    if (!targetPhone.isNullOrEmpty()) put("phoneNo", targetPhone)
                    put("deviceId", deviceId)
                }.toString()

                conn.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                if (conn.responseCode in 200..299) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(responseStr)
                    val status = json.optString("status", "off")
                    val serverMsg = json.optString("message", "")
                    val expiresAt = json.optLong("expiresAt", 0L)
                    val daysLeft = json.optInt("daysLeft", 7)
                    val isTrial = json.optBoolean("isTrial", true)
                    val localAcc = if (!targetPhone.isNullOrEmpty()) repository.getAccountByPhone(targetPhone) else null

                    if (!targetPhone.isNullOrEmpty()) {
                        sharedPrefs.edit()
                            .putLong("account_expires_at_$targetPhone", expiresAt)
                            .putInt("account_days_left_$targetPhone", daysLeft)
                            .putBoolean("account_is_trial_$targetPhone", isTrial)
                            .apply()
                    }

                    if (status == "expired") {
                        if (localAcc != null) {
                            val updatedAcc = localAcc.copy(status = "expired")
                            repository.insertAccount(updatedAcc)
                            _currentUser.value = updatedAcc
                        }
                        return@withContext Pair(false, "expired")
                    }

                    if (status == "device_limit_reached") {
                        if (localAcc != null) {
                            val updatedAcc = localAcc.copy(status = "device_limit_reached")
                            repository.insertAccount(updatedAcc)
                            _currentUser.value = updatedAcc
                        }
                        return@withContext Pair(false, "device_limit_reached")
                    }

                    if (status == "not_found") {
                        return@withContext Pair(false, if (serverMsg.isNotEmpty()) serverMsg else "အကောင့်ကို Admin မှ မဖွင့်ပေးရသေးပါ (Account Pending)")
                    }

                    if (status == "on") {
                        val serverRole = json.optString("role", localAcc?.role ?: "ADMIN")
                        val serverAdminPhone = json.optString("adminPhone", localAcc?.adminPhone ?: "")
                        if (serverRole == "CASHIER" && serverAdminPhone.isBlank()) {
                            if (localAcc != null) {
                                val updatedAcc = localAcc.copy(status = "pending")
                                repository.insertAccount(updatedAcc)
                                _currentUser.value = updatedAcc
                                cacheUserRoleAndAdmin(updatedAcc)
                            }
                            return@withContext Pair(false, "Admin မှ အတည်ပြုပေးရန် စောင့်ဆိုင်းနေပါသည် (Pending Approval)")
                        }
                        isAccountExpired.value = false
                        val stillActiveSession = !isLoggingOutState && capturedToken == sessionToken
                        if (!targetPhone.isNullOrEmpty() && stillActiveSession) {
                            val acc = repository.getAccountByPhone(targetPhone) ?: _currentUser.value
                            val updatedAcc = acc?.copy(
                                role = serverRole.ifBlank { acc.role },
                                adminPhone = serverAdminPhone.ifBlank { acc.adminPhone },
                                joinCode = json.optString("joinCode", acc.joinCode).ifBlank { acc.joinCode },
                                businessName = json.optString("businessName", acc.businessName).ifBlank { acc.businessName },
                                businessType = json.optString("businessType", acc.businessType).ifBlank { acc.businessType },
                                address = json.optString("address", acc.address).ifBlank { acc.address },
                                status = "on",
                                deviceId = deviceId
                            ) ?: UserAccount(
                                phoneNo = targetPhone,
                                username = json.optString("username", "User"),
                                businessName = json.optString("businessName", ""),
                                businessType = json.optString("businessType", ""),
                                address = json.optString("address", ""),
                                role = serverRole.ifBlank { "ADMIN" },
                                passwordHash = "",
                                deviceId = deviceId,
                                status = "on",
                                joinCode = json.optString("joinCode", ""),
                                adminPhone = serverAdminPhone
                            )
                            repository.insertAccount(updatedAcc)
                            _currentUser.value = updatedAcc
                            cacheUserRoleAndAdmin(updatedAcc)
                            sharedPrefs.edit().putString("logged_in_phone", targetPhone).putString("last_registered_phone", targetPhone).apply()
                        }
                        Pair(true, "အကောင့် ဖွင့်လှစ်ပြီးပါပြီ (Account Active)")
                    } else {
                        if (localAcc != null) {
                            val updatedAcc = localAcc.copy(status = status)
                            repository.insertAccount(updatedAcc)
                            _currentUser.value = updatedAcc
                            cacheUserRoleAndAdmin(updatedAcc)
                        }
                        val msg = if (serverMsg.isNotEmpty()) serverMsg else "အကောင့်ကို Admin မှ မဖွင့်ပေးရသေးပါ (Account Pending)"
                        Pair(false, msg)
                    }
                } else {
                    Pair(false, "အကောင့် အခြေအနေ စစ်ဆေး၍မရပါ (Status Check Failed)")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val localAcc = if (!targetPhone.isNullOrEmpty()) repository.getAccountByPhone(targetPhone) else null
                if (localAcc != null) {
                    if (localAcc.status == "expired") {
                        Pair(false, "expired")
                    } else if (localAcc.status == "device_limit_reached") {
                        Pair(false, "device_limit_reached")
                    } else if (localAcc.role == "CASHIER" && (localAcc.status != "on" || localAcc.adminPhone.isBlank())) {
                        Pair(false, "Admin မှ အတည်ပြုပေးရန် စောင့်ဆိုင်းနေပါသည် (Pending Approval)")
                    } else if (localAcc.status == "on") {
                        Pair(true, "အကောင့် ဖွင့်လှစ်ပြီးပါပြီ (Offline Active)")
                    } else {
                        Pair(false, "အကောင့်ကို Admin မှ မဖွင့်ပေးရသေးပါ (Account Pending)")
                    }
                } else {
                    Pair(false, "အင်တာနက် ချိတ်ဆက်မှု အဆင်မပြေပါ")
                }
            }
        }
    }

    // Login Method
    suspend fun login(context: android.content.Context, phone: String, pass: String): Triple<Boolean, String, String> {
        isLoggingOutState = false
        sessionToken++
        val deviceId = getDeviceId(context)

        return withContext(Dispatchers.IO) {
            val localAccCheck = repository.getAccountByPhone(phone)

            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/login"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val jsonInputString = org.json.JSONObject().apply {
                    put("phoneNo", phone)
                    put("password", pass)
                    put("deviceId", deviceId)
                }.toString()

                conn.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseStr = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = if (responseStr.isNotEmpty()) org.json.JSONObject(responseStr) else org.json.JSONObject()

                val success = json.optBoolean("success", false)
                val message = json.optString("message", "")
                val status = json.optString("status", "")
                val userObj = json.optJSONObject("user")

                // 1. Explicit check for unregistered phone number
                if (responseCode == 404 || status == "not_found" || message.contains("not registered", ignoreCase = true) || message.contains("not found", ignoreCase = true) || message.contains("register ပြုလုပ်ထားခြင်းမရှိပါ") || message.contains("မရှိပါ")) {
                    return@withContext Triple(false, "ဤဖုန်းနံပါတ်ဖြင့် အကောင့်ဖွင့်ထားခြင်း မရှိပါ (Account not registered)", "not_found")
                }

                // 2. Explicit check for incorrect password
                if (responseCode == 401 || status == "invalid_password" || message.contains("password", ignoreCase = true) || message.contains("credentials", ignoreCase = true) || message.contains("မှားယွင်း")) {
                    if (!message.contains("Device", ignoreCase = true) && !message.contains("မတူညီ") && status != "device_mismatch") {
                        return@withContext Triple(false, "Password မှားယွင်းနေပါသည် (Incorrect password)", "invalid_password")
                    }
                }

                // 3. Status checks: Expiration and Device Limit
                if (status == "expired") {
                    val expiresAt = json.optLong("expiresAt", 0L)
                    sharedPrefs.edit()
                        .putString("logged_in_phone", phone)
                        .putLong("account_expires_at_$phone", expiresAt)
                        .putInt("account_days_left_$phone", 0)
                        .apply()
                    val localAcc = repository.getAccountByPhone(phone)
                    if (localAcc != null) {
                        repository.insertAccount(localAcc.copy(status = "expired"))
                    }
                    return@withContext Triple(false, if (message.isNotEmpty()) message else "အကောင့် သက်တမ်းကုန်ဆုံးသွားပါပြီ (Subscription Expired)", "expired")
                }

                if (status == "device_limit_reached") {
                    return@withContext Triple(false, if (message.isNotEmpty()) message else "ဤအကောင့်ကို အများဆုံး စက် ၅ လုံးအထိသာ သုံးခွင့်ပြုထားပါသည် (Device limit of 5 reached)", "device_limit_reached")
                }

                if (success && status == "on") {
                    val expiresAt = json.optLong("expiresAt", 0L)
                    val daysLeft = json.optInt("daysLeft", 7)
                    val isTrial = json.optBoolean("isTrial", true)
                    sharedPrefs.edit()
                        .putString("logged_in_phone", phone)
                        .putString("last_registered_phone", phone)
                        .putLong("account_expires_at_$phone", expiresAt)
                        .putInt("account_days_left_$phone", daysLeft)
                        .putBoolean("account_is_trial_$phone", isTrial)
                        .apply()

                    val remoteUsername = userObj?.optString("username")?.takeIf { it.isNotEmpty() && it != "null" }
                    val remoteBusinessName = userObj?.optString("businessName")?.takeIf { it.isNotEmpty() && it != "null" }
                    val remoteBusinessType = userObj?.optString("businessType")?.takeIf { it.isNotEmpty() && it != "null" }
                    val remoteAddress = userObj?.optString("address")?.takeIf { it.isNotEmpty() && it != "null" }
                    val remoteRole = userObj?.optString("role")?.takeIf { it.isNotEmpty() && it != "null" } ?: localAccCheck?.role ?: "ADMIN"
                    val remoteJoinCode = userObj?.optString("joinCode")?.takeIf { it.isNotEmpty() && it != "null" } ?: localAccCheck?.joinCode ?: ""
                    val remoteAdminPhone = userObj?.optString("adminPhone")?.takeIf { it.isNotEmpty() && it != "null" } ?: localAccCheck?.adminPhone ?: ""

                    if (remoteRole == "CASHIER" && remoteAdminPhone.isBlank()) {
                        val pendingAccount = UserAccount(
                            phoneNo = phone,
                            username = remoteUsername ?: localAccCheck?.username ?: "Cashier",
                            businessName = remoteBusinessName ?: localAccCheck?.businessName ?: "",
                            businessType = remoteBusinessType ?: localAccCheck?.businessType ?: "",
                            address = remoteAddress ?: localAccCheck?.address ?: "",
                            role = "CASHIER",
                            passwordHash = pass,
                            deviceId = deviceId,
                            status = "pending",
                            joinCode = remoteJoinCode,
                            adminPhone = ""
                        )
                        repository.insertAccount(pendingAccount)
                        _currentUser.value = pendingAccount
                        cacheUserRoleAndAdmin(pendingAccount)
                        sharedPrefs.edit().putString("logged_in_phone", phone).putString("last_registered_phone", phone).apply()
                        return@withContext Triple(false, "Admin မှ အတည်ပြုပေးရန် စောင့်ဆိုင်းနေပါသည် (Pending Approval)", "pending")
                    }

                    val remoteAccount = UserAccount(
                        phoneNo = phone,
                        username = remoteUsername ?: localAccCheck?.username ?: "User",
                        businessName = remoteBusinessName ?: localAccCheck?.businessName ?: "",
                        businessType = remoteBusinessType ?: localAccCheck?.businessType ?: "",
                        address = remoteAddress ?: localAccCheck?.address ?: "",
                        role = remoteRole,
                        passwordHash = pass,
                        deviceId = deviceId,
                        status = "on",
                        joinCode = remoteJoinCode,
                        adminPhone = remoteAdminPhone
                    )
                    repository.insertAccount(remoteAccount)
                    _currentUser.value = remoteAccount
                    cacheUserRoleAndAdmin(remoteAccount)
                    Triple(true, "Login Successful", "on")
                } else if (status == "off" || status == "pending" || message.contains("စောင့်ဆိုင်း") || message.contains("မဖွင့်ပေးရသေးပါ")) {
                    val remoteRole = userObj?.optString("role")?.takeIf { it.isNotEmpty() && it != "null" } ?: localAccCheck?.role ?: "ADMIN"
                    val remoteJoinCode = userObj?.optString("joinCode")?.takeIf { it.isNotEmpty() && it != "null" } ?: localAccCheck?.joinCode ?: ""
                    if (localAccCheck != null) {
                        val pendingAcc = localAccCheck.copy(status = if (status.isNotBlank()) status else "pending", role = remoteRole, joinCode = remoteJoinCode)
                        repository.insertAccount(pendingAcc)
                        _currentUser.value = pendingAcc
                        cacheUserRoleAndAdmin(pendingAcc)
                    }
                    sharedPrefs.edit().putString("logged_in_phone", phone).putString("last_registered_phone", phone).apply()
                    Triple(false, if (message.isNotEmpty()) message else "အကောင့်ကို Admin မှ မဖွင့်ပေးရသေးပါ (Account Pending)", "pending")
                } else {
                    val finalMsg = if (message.isNotEmpty()) message else "ဖုန်းနံပါတ် သို့မဟုတ် စကားဝှက် မှားယွင်းနေပါသည်"
                    Triple(false, finalMsg, "failed")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Offline fallback
                val localAcc = repository.getAccountByPhone(phone)
                if (localAcc != null) {
                    if (localAcc.passwordHash != pass) {
                        return@withContext Triple(false, "Password မှားယွင်းနေပါသည် (Incorrect password)", "invalid_password")
                    }
                    if (localAcc.status == "expired") {
                        return@withContext Triple(false, "အကောင့် သက်တမ်းကုန်ဆုံးသွားပါပြီ (Subscription Expired)", "expired")
                    }
                    if (localAcc.status == "device_limit_reached") {
                        return@withContext Triple(false, "ဤအကောင့်ကို အများဆုံး စက် ၅ လုံးအထိသာ သုံးခွင့်ပြုထားပါသည် (Device limit of 5 reached)", "device_limit_reached")
                    }
                    if (localAcc.status != "on" || (localAcc.role == "CASHIER" && localAcc.adminPhone.isBlank())) {
                        _currentUser.value = localAcc
                        cacheUserRoleAndAdmin(localAcc)
                        sharedPrefs.edit().putString("logged_in_phone", phone).putString("last_registered_phone", phone).apply()
                        return@withContext Triple(false, "Admin မှ အတည်ပြုပေးရန် စောင့်ဆိုင်းနေပါသည် (Pending Approval)", "pending")
                    }
                    val isDeviceMatch = localAcc.deviceId.isNotBlank() && localAcc.deviceId == deviceId
                    if (!isDeviceMatch) {
                        sharedPrefs.edit().putString("logged_in_phone", phone).putString("last_registered_phone", phone).apply()
                        return@withContext Triple(false, "ဒီဖုန်းနံပါတ်သည် အခြား Device တွင် Register ပြုလုပ်ထားပါသည် (Device ID မတူညီပါ)", "device_mismatch")
                    }
                    _currentUser.value = localAcc
                    cacheUserRoleAndAdmin(localAcc)
                    sharedPrefs.edit().putString("logged_in_phone", phone).putString("last_registered_phone", phone).apply()
                    Triple(true, "Offline Login Successful", "on")
                } else {
                    Triple(false, "ဤဖုန်းနံပါတ်ဖြင့် အကောင့်ဖွင့်ထားခြင်း မရှိပါ (Account not registered)", "not_found")
                }
            }
        }
    }

    fun getAccountTrialInfo(phoneNo: String? = null): Triple<Long, Int, Boolean> {
        val phone = phoneNo ?: sharedPrefs.getString("logged_in_phone", null) ?: currentUser.value?.phoneNo ?: ""
        val expiresAt = sharedPrefs.getLong("account_expires_at_$phone", 0L)
        val daysLeft = sharedPrefs.getInt("account_days_left_$phone", -1)
        val isTrial = sharedPrefs.getBoolean("account_is_trial_$phone", false)
        return Triple(expiresAt, daysLeft, isTrial)
    }

    fun insertAccount(account: UserAccount) {
        viewModelScope.launch {
            repository.insertAccount(account)
        }
    }

    // Trigger Registration Simulation & Send Data to Backend
    fun runRegistration(context: android.content.Context, onComplete: (String) -> Unit) {
        viewModelScope.launch {
            isLoggingOutState = false
            sessionToken++
            _registrationProgress.value = 0f
            _registrationStatus.value = "Registering..."

            val deviceId = getDeviceId(context)

            val stages = listOf(0.00f, 0.05f, 0.22f, 0.50f, 0.74f, 0.95f, 1.00f)
            for (stage in stages) {
                _registrationProgress.value = stage
                delay(300)
            }

            _registrationStatus.value = "Successfully Finished"

            val isCashier = selectedRole.value == "CASHIER"
            val generatedJoinCode = if (isCashier) (100000..999999).random().toString() else ""

            // Ensure any previous account's business data, cashiers, and pending flags are wiped cleanly before initializing the new account
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    repository.clearAllBusinessData()
                    repository.clearAllAccounts()
                }
                clearAllPendingDataAndFlags()
                clearSessionPrefsSynchronously()
                resetActiveVoucher()
                resetSalesHistoryFilters()
                clearProductForm()
            }

            // Save user in local database with status (pending for cashier, off for admin initially)
            val newUser = UserAccount(
                phoneNo = registerPhone.value.trim(),
                username = registerUsername.value.trim(),
                businessName = registerBusinessName.value.trim(),
                businessType = registerBusinessType.value.trim(),
                address = registerAddress.value.trim(),
                role = selectedRole.value,
                passwordHash = registerPassword.value,
                deviceId = deviceId,
                status = if (isCashier) "pending" else "off",
                joinCode = generatedJoinCode,
                adminPhone = ""
            )
            repository.insertAccount(newUser)
            _currentUser.value = newUser
            cacheUserRoleAndAdmin(newUser)
            sharedPrefs.edit()
                .putString("logged_in_phone", newUser.phoneNo)
                .putString("last_registered_phone", newUser.phoneNo)
                .putString("data_owner_phone", if (isCashier) "" else newUser.phoneNo)
                .putString("cashier_join_code_${newUser.phoneNo}", generatedJoinCode)
                .apply()

            if (!isCashier) {
                ensureDefaultPaymentsIfEmpty()
            }

            var registrationFinalStatus = if (isCashier) "pending" else "off"

            // Asynchronously send data to Server Database
            withContext(Dispatchers.IO) {
                try {
                    val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/register"
                    val url = java.net.URL(apiUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000

                    val jsonInputString = org.json.JSONObject().apply {
                        put("phoneNo", newUser.phoneNo)
                        put("username", newUser.username)
                        put("businessName", newUser.businessName)
                        put("businessType", newUser.businessType)
                        put("address", newUser.address)
                        put("role", newUser.role)
                        put("password", newUser.passwordHash)
                        put("deviceId", deviceId)
                        put("joinCode", generatedJoinCode)
                    }.toString()

                    conn.outputStream.use { os ->
                        val input = jsonInputString.toByteArray(charset("utf-8"))
                        os.write(input, 0, input.size)
                    }

                    if (conn.responseCode in 200..299) {
                        val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(respStr)
                        val st = if (isCashier) "pending" else json.optString("status", "on")
                        registrationFinalStatus = st
                        val expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L)
                        val daysLeft = json.optInt("daysLeft", 7)

                        sharedPrefs.edit()
                            .putLong("account_expires_at_${newUser.phoneNo}", expiresAt)
                            .putInt("account_days_left_${newUser.phoneNo}", daysLeft)
                            .putBoolean("account_is_trial_${newUser.phoneNo}", !isCashier)
                            .apply()

                        val updatedUser = newUser.copy(status = st)
                        repository.insertAccount(updatedUser)
                        _currentUser.value = updatedUser
                        cacheUserRoleAndAdmin(updatedUser)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            resetRegistrationFields()
            delay(300)
            onComplete(registrationFinalStatus)
        }
    }

    suspend fun joinCashierByCode(code: String): Pair<Boolean, String> {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            return Pair(false, "Cashier Code ရိုက်ထည့်ပါ")
        }
        val admin = currentUser.value
        val adminPhone = admin?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        val adminBName = admin?.businessName ?: "UN Mobile POS"
        val adminBType = admin?.businessType ?: "Retail"
        val adminAddr = admin?.address ?: ""

        // 1. Try finding locally first
        val localCashier = repository.getAccountByJoinCode(cleanCode)
        if (localCashier != null) {
            val updated = localCashier.copy(
                status = "on",
                adminPhone = adminPhone,
                businessName = if (adminBName.isNotBlank()) adminBName else localCashier.businessName,
                businessType = if (adminBType.isNotBlank()) adminBType else localCashier.businessType,
                address = if (adminAddr.isNotBlank()) adminAddr else localCashier.address
            )
            repository.insertAccount(updated)

            // Also notify server
            withContext(Dispatchers.IO) {
                try {
                    val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/join-cashier"
                    val url = java.net.URL(apiUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    val payload = org.json.JSONObject().apply {
                        put("adminPhone", adminPhone)
                        put("joinCode", cleanCode)
                    }.toString()
                    conn.outputStream.use { it.write(payload.toByteArray(charset("utf-8"))) }
                    conn.responseCode
                } catch (_: Exception) {}
            }
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            return Pair(true, "Cashier '${localCashier.username}' (${localCashier.phoneNo}) ကို အောင်မြင်စွာ Join ပေးပြီးပါပြီ")
        }

        // 2. If not found locally, query cloud server
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/join-cashier"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val payload = org.json.JSONObject().apply {
                    put("adminPhone", adminPhone)
                    put("joinCode", cleanCode)
                }.toString()
                conn.outputStream.use { it.write(payload.toByteArray(charset("utf-8"))) }

                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(respStr)
                    val cashierObj = json.optJSONObject("cashier")
                    if (cashierObj != null) {
                        val joinedUser = UserAccount(
                            phoneNo = cashierObj.optString("phoneNo"),
                            username = cashierObj.optString("username"),
                            businessName = cashierObj.optString("businessName", adminBName),
                            businessType = cashierObj.optString("businessType", adminBType),
                            address = cashierObj.optString("address", adminAddr),
                            role = "CASHIER",
                            passwordHash = cashierObj.optString("password", ""),
                            status = "on",
                            joinCode = cleanCode,
                            adminPhone = adminPhone
                        )
                        repository.insertAccount(joinedUser)
                        if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                            triggerRealSync()
                        }
                        Pair(true, "Cashier '${joinedUser.username}' (${joinedUser.phoneNo}) ကို အောင်မြင်စွာ Join ပေးပြီးပါပြီ")
                    } else {
                        Pair(true, "Cashier အကောင့် ချိတ်ဆက်ပြီးပါပြီ")
                    }
                } else {
                    Pair(false, "ဤ Code ဖြင့် Cashier အကောင့် မတွေ့ရှိပါ")
                }
            } catch (e: Exception) {
                Pair(false, "ချိတ်ဆက်၍ မရပါ (ဤစက်တွင် မတွေ့ရှိပါ): ${e.localizedMessage ?: "အင်တာနက် မရှိပါ"}")
            }
        }
    }

    suspend fun refreshLinkedCashiers() {
        val admin = currentUser.value
        val adminPhone = admin?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        if (adminPhone.isBlank() || admin?.role == "CASHIER") return

        withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/cashiers?phoneNo=" + java.net.URLEncoder.encode(adminPhone, "UTF-8")
                val conn = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(respStr)
                    val arr = json.optJSONArray("cashiers") ?: return@withContext
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val cPhone = obj.optString("phoneNo")
                        if (cPhone.isNotBlank()) {
                            val existing = repository.getAccountByPhone(cPhone)
                            val acc = UserAccount(
                                phoneNo = cPhone,
                                username = obj.optString("username", existing?.username ?: "Cashier"),
                                businessName = obj.optString("businessName", existing?.businessName ?: ""),
                                businessType = obj.optString("businessType", existing?.businessType ?: ""),
                                address = obj.optString("address", existing?.address ?: ""),
                                role = "CASHIER",
                                passwordHash = existing?.passwordHash ?: "",
                                deviceId = existing?.deviceId ?: "",
                                status = obj.optString("status", "on"),
                                joinCode = obj.optString("joinCode", existing?.joinCode ?: ""),
                                adminPhone = obj.optString("adminPhone", adminPhone)
                            )
                            repository.insertAccount(acc)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun removeCashierAccount(account: UserAccount): Pair<Boolean, String> {
        if (account.role != "CASHIER") {
            return Pair(false, "Admin အကောင့်ကို ဖျက်၍မရပါ")
        }
        val adminPhone = currentUser.value?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        repository.deleteAccount(account)

        withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/remove-cashier"
                val conn = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val payload = org.json.JSONObject().apply {
                    put("adminPhone", adminPhone)
                    put("cashierPhone", account.phoneNo)
                }.toString()
                conn.outputStream.use { it.write(payload.toByteArray(charset("utf-8"))) }
                conn.responseCode
            } catch (_: Exception) {}
        }
        return Pair(true, "Cashier '${account.username}' (${account.phoneNo}) အကောင့်ကို ဖျက်ပြီးပါပြီ")
    }

    suspend fun checkCashierStatus(phone: String): String {
        val local = repository.getAccountByPhone(phone)
        if (local != null && local.status == "on" && local.adminPhone.isNotBlank()) {
            _currentUser.value = local
            cacheUserRoleAndAdmin(local)
            return "on"
        }

        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/check-status"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 5000
                val payload = org.json.JSONObject().apply {
                    put("phoneNo", phone)
                }.toString()
                conn.outputStream.use { it.write(payload.toByteArray(charset("utf-8"))) }
                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(respStr)
                    val st = json.optString("status", "pending")
                    val adminPh = json.optString("adminPhone", local?.adminPhone ?: "")
                    if (st == "on" && adminPh.isNotBlank() && local != null) {
                        val expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L)
                        val daysLeft = json.optInt("daysLeft", 7)
                        sharedPrefs.edit()
                            .putLong("account_expires_at_$phone", expiresAt)
                            .putInt("account_days_left_$phone", daysLeft)
                            .putString("logged_in_phone", phone)
                            .putString("last_registered_phone", phone)
                            .apply()
                        val updated = local.copy(
                            status = "on",
                            adminPhone = adminPh,
                            businessName = json.optString("businessName", local.businessName).ifBlank { local.businessName },
                            businessType = json.optString("businessType", local.businessType).ifBlank { local.businessType },
                            address = json.optString("address", local.address).ifBlank { local.address }
                        )
                        repository.insertAccount(updated)
                        _currentUser.value = updated
                        cacheUserRoleAndAdmin(updated)
                        "on"
                    } else {
                        if (st == "not_found" && local != null) {
                            // Re-register pending cashier on server if missing
                            try {
                                val regUrl = java.net.URL(cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/register")
                                val rConn = regUrl.openConnection() as java.net.HttpURLConnection
                                rConn.requestMethod = "POST"
                                rConn.setRequestProperty("Content-Type", "application/json; utf-8")
                                rConn.doOutput = true
                                rConn.connectTimeout = 5000
                                val rPayload = org.json.JSONObject().apply {
                                    put("phoneNo", local.phoneNo)
                                    put("username", local.username)
                                    put("businessName", local.businessName)
                                    put("businessType", local.businessType)
                                    put("address", local.address)
                                    put("role", "CASHIER")
                                    put("password", local.passwordHash)
                                    put("deviceId", local.deviceId)
                                    put("joinCode", local.joinCode)
                                }.toString()
                                rConn.outputStream.use { it.write(rPayload.toByteArray(charset("utf-8"))) }
                                rConn.responseCode
                            } catch (_: Exception) {}
                        }
                        "pending"
                    }
                } else {
                    if (conn.responseCode == 404 && local != null) {
                        try {
                            val regUrl = java.net.URL(cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/register")
                            val rConn = regUrl.openConnection() as java.net.HttpURLConnection
                            rConn.requestMethod = "POST"
                            rConn.setRequestProperty("Content-Type", "application/json; utf-8")
                            rConn.doOutput = true
                            rConn.connectTimeout = 5000
                            val rPayload = org.json.JSONObject().apply {
                                put("phoneNo", local.phoneNo)
                                put("username", local.username)
                                put("businessName", local.businessName)
                                put("businessType", local.businessType)
                                put("address", local.address)
                                put("role", "CASHIER")
                                put("password", local.passwordHash)
                                put("deviceId", local.deviceId)
                                put("joinCode", local.joinCode)
                            }.toString()
                            rConn.outputStream.use { it.write(rPayload.toByteArray(charset("utf-8"))) }
                            rConn.responseCode
                        } catch (_: Exception) {}
                    }
                    "pending"
                }
            } catch (_: Exception) {
                if (local?.status == "on" && local.adminPhone.isNotBlank()) "on" else "pending"
            }
        }
    }

    // Change Password Method
    suspend fun changePassword(phone: String, oldPass: String, newPass: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/change-password"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val jsonInputString = org.json.JSONObject().apply {
                    put("phoneNo", phone)
                    put("oldPassword", oldPass)
                    put("newPassword", newPass)
                }.toString()

                conn.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseStr = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = if (responseStr.isNotEmpty()) org.json.JSONObject(responseStr) else org.json.JSONObject()

                val success = json.optBoolean("success", false)
                val message = json.optString("message", if (success) "Password changed successfully" else "Failed to change password")

                if (success) {
                    val localAcc = repository.getAccountByPhone(phone)
                    if (localAcc != null) {
                        val updated = localAcc.copy(passwordHash = newPass)
                        repository.insertAccount(updated)
                        if (_currentUser.value?.phoneNo == phone) {
                            _currentUser.value = updated
                        }
                    }
                }
                Pair(success, message)
            } catch (e: Exception) {
                e.printStackTrace()
                val localAcc = repository.getAccountByPhone(phone)
                if (localAcc != null && localAcc.passwordHash == oldPass) {
                    val updated = localAcc.copy(passwordHash = newPass)
                    repository.insertAccount(updated)
                    if (_currentUser.value?.phoneNo == phone) {
                        _currentUser.value = updated
                    }
                    Pair(true, "Password changed locally")
                } else if (localAcc != null && localAcc.passwordHash != oldPass) {
                    Pair(false, "ယခင်စကားဝှက် မှားယွင်းနေပါသည်")
                } else {
                    Pair(false, "အင်တာနက် ချိတ်ဆက်မှု အဆင်မပြေပါ")
                }
            }
        }
    }

    suspend fun updatePasswordDirectly(phone: String, newPass: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            val localAcc = repository.getAccountByPhone(phone)
            val oldPass = localAcc?.passwordHash ?: ""
            if (oldPass.isNotEmpty()) {
                val res = changePassword(phone, oldPass, newPass)
                if (res.first) return@withContext res
            }
            try {
                if (localAcc != null) {
                    val updated = localAcc.copy(passwordHash = newPass)
                    repository.insertAccount(updated)
                    if (_currentUser.value?.phoneNo == phone) {
                        _currentUser.value = updated
                    }
                    Pair(true, "စကားဝှက် ပြောင်းလဲပြီးပါပြီ")
                } else {
                    Pair(false, "အကောင့် ရှာမတွေ့ပါ")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Pair(false, e.message ?: "စကားဝှက် ပြောင်းလဲ၍ မရပါ")
            }
        }
    }

    // Delete User Account Method
    suspend fun deleteUserAccount(phone: String, pass: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = cloudflareWorkerUrl.value.trim().removeSuffix("/") + "/api/delete-user"
                val url = java.net.URL(apiUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val jsonInputString = org.json.JSONObject().apply {
                    put("phoneNo", phone)
                    put("password", pass)
                }.toString()

                conn.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseStr = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = if (responseStr.isNotEmpty()) org.json.JSONObject(responseStr) else org.json.JSONObject()

                val success = json.optBoolean("success", false)
                val message = json.optString("message", if (success) "Account deleted successfully" else "Failed to delete account")

                if (success) {
                    val localAcc = repository.getAccountByPhone(phone)
                    if (localAcc != null) {
                        repository.deleteAccount(localAcc)
                        if (_currentUser.value?.phoneNo == phone) {
                            _currentUser.value = null
                            sharedPrefs.edit().remove("logged_in_phone").apply()
                        }
                    }
                }
                Pair(success, message)
            } catch (e: Exception) {
                e.printStackTrace()
                val localAcc = repository.getAccountByPhone(phone)
                if (localAcc != null && localAcc.passwordHash == pass) {
                    repository.deleteAccount(localAcc)
                    if (_currentUser.value?.phoneNo == phone) {
                        _currentUser.value = null
                        sharedPrefs.edit().remove("logged_in_phone").apply()
                    }
                    Pair(true, "Account deleted locally")
                } else if (localAcc != null && localAcc.passwordHash != pass) {
                    Pair(false, "စကားဝှက် မှားယွင်းနေပါသည်")
                } else {
                    Pair(false, "အင်တာနက် ချိတ်ဆက်မှု အဆင်မပြေပါ")
                }
            }
        }
    }

    // Add/Update Product Method
    fun addProduct(onSuccess: (Product?) -> Unit) {
        viewModelScope.launch {
            val name = prodName.value
            val group = prodGroup.value
            val pPrice = prodPurchasePrice.value.toDoubleOrNull() ?: 0.0
            val sPrice = prodSellingPrice.value.toDoubleOrNull() ?: 0.0
            val unit = prodUnit.value
            val note = prodNote.value
            val track = prodTrackStock.value
            val barcode = prodBarcode.value
            val qty = prodQty.value.toIntOrNull() ?: 0
            val alert = prodAlertQty.value.toIntOrNull() ?: 0
            val imgUri = prodImageUri.value

            if (name.isNotEmpty() && sPrice > 0.0) {
                val currentEditing = editingProduct.value
                var savedProduct: Product? = null
                if (currentEditing != null) {
                    val latestQty = repository.getProductDirect(currentEditing.id)?.quantity ?: currentEditing.quantity
                    val updatedProduct = currentEditing.copy(
                        name = name,
                        groupName = group,
                        purchasePrice = pPrice,
                        sellingPrice = sPrice,
                        unit = unit,
                        note = note,
                        trackStock = track,
                        barcode = barcode,
                        quantity = latestQty,
                        alertQuantity = alert,
                        imageUri = imgUri,
                        isSynced = false
                    )
                    repository.updateProduct(updatedProduct)
                    editingProduct.value = null
                    savedProduct = updatedProduct

                    // Immediately update cart if product is currently in cart
                    val currentCart = _cart.value.toMutableList()
                    val idx = currentCart.indexOfFirst { it.product.id == updatedProduct.id }
                    if (idx >= 0) {
                        currentCart[idx] = currentCart[idx].copy(product = updatedProduct)
                        _cart.value = currentCart
                    }
                } else {
                    val product = Product(
                        name = name,
                        groupName = group,
                        purchasePrice = pPrice,
                        sellingPrice = sPrice,
                        unit = unit,
                        note = note,
                        trackStock = track,
                        barcode = barcode,
                        quantity = qty,
                        alertQuantity = alert,
                        imageUri = imgUri,
                        isSynced = false
                    )
                    val newId = repository.insertProduct(product)
                    savedProduct = product.copy(id = newId)
                }

                // Auto-sync in background if online (without concurrent race against adjustStockOnCloud)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    val editedToAdjust = if (currentEditing != null) savedProduct else null
                    viewModelScope.launch {
                        if (editedToAdjust != null) {
                            syncMutex.withLock {
                                com.example.util.SyncManager.adjustStockOnCloud(
                                    workerUrl = cloudflareWorkerUrl.value,
                                    userPhone = phone,
                                    productId = editedToAdjust.id,
                                    productName = editedToAdjust.name,
                                    newQuantity = editedToAdjust.quantity,
                                    newAlertQuantity = editedToAdjust.alertQuantity
                                )
                            }
                        }
                        triggerRealSync()
                    }
                }

                // Clear state
                prodName.value = ""
                prodGroup.value = ""
                prodPurchasePrice.value = ""
                prodSellingPrice.value = ""
                prodUnit.value = ""
                prodNote.value = ""
                prodTrackStock.value = true
                prodBarcode.value = ""
                prodQty.value = ""
                prodAlertQty.value = ""
                prodImageUri.value = ""

                onSuccess(savedProduct)
            }
        }
    }

    fun insertDirectProduct(product: Product, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            repository.insertProduct(product.copy(isSynced = false))
            if (product.groupName.isNotEmpty()) {
                repository.insertGroup(ProductGroup(product.groupName))
            }
            if (product.unit.isNotEmpty()) {
                repository.insertUnit(ProductUnit(product.unit))
            }
            if (isOnlineState.value && isServerReachable.value) {
                triggerRealSync()
            }
            onSuccess()
        }
    }

    fun importProductsBatchDirect(products: List<Product>, onComplete: () -> Unit = {}) {
        if (products.isEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            val insertedIds = mutableListOf<Long>()
            repository.runInTransaction {
                val existingGroups = repository.getAllGroupsDirect().map { it.name.trim().lowercase() }.toMutableSet()
                val existingUnits = repository.getAllUnitsDirect().map { it.name.trim().lowercase() }.toMutableSet()
                products.forEach { product ->
                    val newId = repository.insertProduct(product.copy(isSynced = false))
                    insertedIds.add(newId)
                    if (product.groupName.isNotBlank() && existingGroups.add(product.groupName.trim().lowercase())) {
                        repository.insertGroup(ProductGroup(product.groupName.trim()))
                        recordPendingGroupChange(product.groupName.trim())
                    }
                    if (product.unit.isNotBlank() && existingUnits.add(product.unit.trim().lowercase())) {
                        repository.insertUnit(ProductUnit(product.unit.trim()))
                        recordPendingUnitChange(product.unit.trim())
                    }
                }
            }
            if (insertedIds.isNotEmpty()) {
                val currentSet = sharedPrefs.getStringSet("manual_stock_adjusted_products", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                insertedIds.forEach { currentSet.add(it.toString()) }
                sharedPrefs.edit().putStringSet("manual_stock_adjusted_products", currentSet).apply()
            }
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            onComplete()
        }
    }

    fun importVouchersDirect(vouchers: List<Voucher>, onComplete: () -> Unit = {}) {
        if (vouchers.isEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            repository.runInTransaction {
                vouchers.forEach { voucher ->
                    repository.insertVoucher(voucher.copy(isSynced = false))
                }
            }
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            onComplete()
        }
    }

    fun importExpensesDirect(expenses: List<Expense>, onComplete: () -> Unit = {}) {
        if (expenses.isEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            repository.runInTransaction {
                val existingCategories = repository.getAllExpenseCategoriesDirect().map { it.name.lowercase() }.toMutableSet()
                expenses.forEach { expense ->
                    if (expense.categoryName.isNotBlank() && existingCategories.add(expense.categoryName.lowercase())) {
                        repository.insertExpenseCategory(ExpenseCategory(name = expense.categoryName, iconName = "ShoppingCart"))
                    }
                    repository.insertExpense(expense)
                }
            }
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            onComplete()
        }
    }

    fun recordPendingDeletedProduct(id: Long, name: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_products", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add("$id::$name")
        sharedPrefs.edit().putStringSet("pending_deleted_products", currentSet).apply()
    }

    fun removePendingDeletedProduct(id: Long, name: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_products", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove("$id::$name")
        sharedPrefs.edit().putStringSet("pending_deleted_products", currentSet).apply()
    }

    fun getPendingDeletedProducts(): Pair<List<Long>, List<String>> {
        val set = sharedPrefs.getStringSet("pending_deleted_products", emptySet()) ?: emptySet()
        val ids = mutableListOf<Long>()
        val names = mutableListOf<String>()
        for (entry in set) {
            val parts = entry.split("::")
            parts.getOrNull(0)?.toLongOrNull()?.let { ids.add(it) }
            parts.getOrNull(1)?.let { if (it.isNotEmpty()) names.add(it) }
        }
        return Pair(ids, names)
    }

    fun clearPendingDeletedProducts() {
        sharedPrefs.edit().remove("pending_deleted_products").apply()
    }

    fun recordPendingDeletedVoucher(receiptNo: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_vouchers", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(receiptNo)
        sharedPrefs.edit().putStringSet("pending_deleted_vouchers", currentSet).apply()
    }

    fun removePendingDeletedVoucher(receiptNo: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_vouchers", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(receiptNo)
        sharedPrefs.edit().putStringSet("pending_deleted_vouchers", currentSet).apply()
    }

    fun getPendingDeletedVouchers(): List<String> {
        val set = sharedPrefs.getStringSet("pending_deleted_vouchers", emptySet()) ?: emptySet()
        return set.toList()
    }

    fun clearPendingDeletedVouchers() {
        sharedPrefs.edit().remove("pending_deleted_vouchers").apply()
    }

    fun recordManualStockAdjustment(productId: Long) {
        val currentSet = sharedPrefs.getStringSet("manual_stock_adjusted_products", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(productId.toString())
        sharedPrefs.edit().putStringSet("manual_stock_adjusted_products", currentSet).apply()
    }

    fun removeManualStockAdjustment(productId: Long) {
        val currentSet = sharedPrefs.getStringSet("manual_stock_adjusted_products", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(productId.toString())
        sharedPrefs.edit().putStringSet("manual_stock_adjusted_products", currentSet).apply()
    }

    fun getPendingManualStockAdjustments(): Set<Long> {
        val set = sharedPrefs.getStringSet("manual_stock_adjusted_products", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun clearManualStockAdjustments() {
        sharedPrefs.edit().remove("manual_stock_adjusted_products").apply()
    }

    fun recordPendingDeletedCustomer(name: String, id: Long = 0L) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_customers", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name.trim())
        if (id > 0L) currentSet.add(id.toString())
        sharedPrefs.edit().putStringSet("pending_deleted_customers", currentSet).apply()
    }
    fun getPendingDeletedCustomers(): List<String> {
        return sharedPrefs.getStringSet("pending_deleted_customers", emptySet())?.toList() ?: emptyList()
    }
    fun clearPendingDeletedCustomers() {
        sharedPrefs.edit().remove("pending_deleted_customers").apply()
    }

    fun recordPendingDeletedSupplier(name: String, id: Long = 0L) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_suppliers", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name.trim())
        if (id > 0L) currentSet.add(id.toString())
        sharedPrefs.edit().putStringSet("pending_deleted_suppliers", currentSet).apply()
    }
    fun getPendingDeletedSuppliers(): List<String> {
        return sharedPrefs.getStringSet("pending_deleted_suppliers", emptySet())?.toList() ?: emptyList()
    }
    fun clearPendingDeletedSuppliers() {
        sharedPrefs.edit().remove("pending_deleted_suppliers").apply()
    }

    fun recordPendingDeletedGroup(name: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_groups", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name.trim())
        sharedPrefs.edit().putStringSet("pending_deleted_groups", currentSet).apply()
    }
    fun getPendingDeletedGroups(): List<String> {
        return sharedPrefs.getStringSet("pending_deleted_groups", emptySet())?.toList() ?: emptyList()
    }
    fun clearPendingDeletedGroups() {
        sharedPrefs.edit().remove("pending_deleted_groups").apply()
    }

    fun recordPendingDeletedUnit(name: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_units", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name.trim())
        sharedPrefs.edit().putStringSet("pending_deleted_units", currentSet).apply()
    }
    fun getPendingDeletedUnits(): List<String> {
        return sharedPrefs.getStringSet("pending_deleted_units", emptySet())?.toList() ?: emptyList()
    }
    fun clearPendingDeletedUnits() {
        sharedPrefs.edit().remove("pending_deleted_units").apply()
    }

    fun recordPendingDeletedPayment(method: String) {
        val currentSet = sharedPrefs.getStringSet("pending_deleted_payments", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(method.trim())
        sharedPrefs.edit().putStringSet("pending_deleted_payments", currentSet).apply()
    }
    fun getPendingDeletedPayments(): List<String> {
        return sharedPrefs.getStringSet("pending_deleted_payments", emptySet())?.toList() ?: emptyList()
    }
    fun clearPendingDeletedPayments() {
        sharedPrefs.edit().remove("pending_deleted_payments").apply()
    }

    fun deleteProduct(product: Product, onSuccess: () -> Unit) {
        viewModelScope.launch {
            repository.deleteProduct(product)
            val currentCart = _cart.value.toMutableList()
            currentCart.removeAll { it.product.id == product.id }
            _cart.value = currentCart

            recordPendingDeletedProduct(product.id, product.name)

            val phone = getCloudPhone()
            if (phone.isNotEmpty() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    val ok = com.example.util.SyncManager.deleteSingleProductOnCloud(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        productId = product.id,
                        productName = product.name
                    )
                    if (ok) {
                        removePendingDeletedProduct(product.id, product.name)
                    }
                }
            }
            onSuccess()
        }
    }

    fun clearProductForm() {
        prodName.value = ""
        prodGroup.value = ""
        prodPurchasePrice.value = ""
        prodSellingPrice.value = ""
        prodUnit.value = ""
        prodNote.value = ""
        prodTrackStock.value = true
        prodBarcode.value = ""
        prodQty.value = ""
        prodAlertQty.value = ""
        prodImageUri.value = ""
    }

    fun loadProductToForm(product: Product) {
        editingProduct.value = product
        prodName.value = product.name
        prodGroup.value = product.groupName
        prodPurchasePrice.value = if (product.purchasePrice > 0.0) product.purchasePrice.toString() else ""
        prodSellingPrice.value = if (product.sellingPrice > 0.0) product.sellingPrice.toString() else ""
        prodUnit.value = product.unit
        prodNote.value = product.note
        prodTrackStock.value = product.trackStock
        prodBarcode.value = product.barcode
        prodQty.value = product.quantity.toString()
        prodAlertQty.value = product.alertQuantity.toString()
        prodImageUri.value = product.imageUri
    }

    // Product Group Management
    fun addProductGroup(name: String) {
        viewModelScope.launch {
            if (name.isNotEmpty()) {
                repository.insertGroup(ProductGroup(name))
                recordPendingGroupChange(name)
                if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    triggerRealSync()
                }
            }
        }
    }

    fun updateProductGroup(oldGroup: ProductGroup, newName: String) {
        viewModelScope.launch {
            if (newName.isNotEmpty() && oldGroup.name != newName) {
                repository.deleteGroup(oldGroup)
                repository.insertGroup(ProductGroup(newName))
                recordPendingGroupChange(newName)
                val prods = repository.getAllProductsDirect()
                for (p in prods) {
                    if (p.groupName == oldGroup.name) {
                        repository.updateProduct(p.copy(groupName = newName, isSynced = false))
                    }
                }
                recordPendingDeletedGroup(oldGroup.name)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.example.util.SyncManager.deleteGroupOnCloud(cloudflareWorkerUrl.value, phone, oldGroup.name)
                    }
                    triggerRealSync()
                }
            }
        }
    }

    fun deleteProductGroup(group: ProductGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
            recordPendingGroupChange(group.name)
            recordPendingDeletedGroup(group.name)
            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.example.util.SyncManager.deleteGroupOnCloud(cloudflareWorkerUrl.value, phone, group.name)
                }
                triggerRealSync()
            }
        }
    }

    // Product Unit Management
    fun addProductUnit(name: String) {
        viewModelScope.launch {
            if (name.isNotEmpty()) {
                repository.insertUnit(ProductUnit(name))
                recordPendingUnitChange(name)
                if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    triggerRealSync()
                }
            }
        }
    }

    fun updateProductUnit(oldUnit: ProductUnit, newName: String) {
        viewModelScope.launch {
            if (newName.isNotEmpty() && oldUnit.name != newName) {
                repository.deleteUnit(oldUnit)
                repository.insertUnit(ProductUnit(newName))
                recordPendingUnitChange(newName)
                val prods = repository.getAllProductsDirect()
                for (p in prods) {
                    if (p.unit == oldUnit.name) {
                        repository.updateProduct(p.copy(unit = newName, isSynced = false))
                    }
                }
                recordPendingDeletedUnit(oldUnit.name)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.example.util.SyncManager.deleteUnitOnCloud(cloudflareWorkerUrl.value, phone, oldUnit.name)
                    }
                    triggerRealSync()
                }
            }
        }
    }

    fun deleteProductUnit(unit: ProductUnit) {
        viewModelScope.launch {
            repository.deleteUnit(unit)
            recordPendingUnitChange(unit.name)
            recordPendingDeletedUnit(unit.name)
            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.example.util.SyncManager.deleteUnitOnCloud(cloudflareWorkerUrl.value, phone, unit.name)
                }
                triggerRealSync()
            }
        }
    }

    // Customer Management
    fun addCustomer(name: String, phone: String, address: String, note: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val cleanName = name.trim()
            if (cleanName.isNotEmpty()) {
                val existing = repository.getAllCustomersDirect().find { it.name.trim().equals(cleanName, ignoreCase = true) }
                if (existing != null) {
                    repository.updateCustomer(existing.copy(phone = phone, address = address, note = note, isSynced = false))
                } else {
                    repository.insertCustomer(Customer(name = cleanName, phone = phone, address = address, note = note, isSynced = false))
                }
                if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    triggerRealSync()
                }
                onSuccess()
            }
        }
    }

    fun updateCustomer(customer: Customer, oldName: String? = null, onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (oldName != null && oldName.trim().isNotBlank() && !oldName.trim().equals(customer.name.trim(), ignoreCase = true)) {
                recordPendingDeletedCustomer(oldName.trim(), customer.id)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.example.util.SyncManager.deleteCustomerOnCloud(cloudflareWorkerUrl.value, phone, oldName.trim(), customer.id)
                    }
                }
            }
            repository.updateCustomer(customer.copy(isSynced = false))
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            onSuccess()
        }
    }

    fun deleteCustomer(customer: Customer, onSuccess: () -> Unit) {
        viewModelScope.launch {
            repository.deleteCustomer(customer)
            recordPendingDeletedCustomer(customer.name, customer.id)
            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.example.util.SyncManager.deleteCustomerOnCloud(cloudflareWorkerUrl.value, phone, customer.name, customer.id)
                }
                triggerRealSync()
            }
            onSuccess()
        }
    }

    // Supplier Management
    fun addSupplier(name: String, phone: String, address: String, note: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val cleanName = name.trim()
            if (cleanName.isNotEmpty()) {
                val existing = repository.getAllSuppliersDirect().find { it.name.trim().equals(cleanName, ignoreCase = true) }
                if (existing != null) {
                    repository.updateSupplier(existing.copy(phone = phone, address = address, note = note))
                } else {
                    repository.insertSupplier(Supplier(name = cleanName, phone = phone, address = address, note = note))
                }
                recordPendingSupplierChange(cleanName)
                if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    triggerRealSync()
                }
                onSuccess()
            }
        }
    }

    fun updateSupplier(supplier: Supplier, oldName: String? = null, onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (oldName != null && oldName.trim().isNotBlank() && !oldName.trim().equals(supplier.name.trim(), ignoreCase = true)) {
                recordPendingDeletedSupplier(oldName.trim(), supplier.id)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.example.util.SyncManager.deleteSupplierOnCloud(cloudflareWorkerUrl.value, phone, oldName.trim(), supplier.id)
                    }
                }
            }
            repository.updateSupplier(supplier)
            recordPendingSupplierChange(supplier.name)
            if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                triggerRealSync()
            }
            onSuccess()
        }
    }

    fun deleteSupplier(supplier: Supplier, onSuccess: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSupplier(supplier)
            recordPendingSupplierChange(supplier.name)
            recordPendingDeletedSupplier(supplier.name, supplier.id)
            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.example.util.SyncManager.deleteSupplierOnCloud(cloudflareWorkerUrl.value, phone, supplier.name, supplier.id)
                }
                triggerRealSync()
            }
            onSuccess()
        }
    }

    // Payment Methods Management
    fun addPayment(method: String) {
        viewModelScope.launch {
            if (method.isNotEmpty()) {
                repository.insertPayment(Payment(method = method, amount = 0.0, date = System.currentTimeMillis()))
                recordPendingPaymentChange(method)
                if (com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    triggerRealSync()
                }
            }
        }
    }

    fun updatePayment(payment: Payment, newMethod: String) {
        viewModelScope.launch {
            if (newMethod.isNotEmpty()) {
                val oldMethod = payment.method
                repository.updatePayment(payment.copy(method = newMethod))
                recordPendingPaymentChange(newMethod)
                recordPendingDeletedPayment(oldMethod)
                val phone = getCloudPhone()
                if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.example.util.SyncManager.deletePaymentOnCloud(cloudflareWorkerUrl.value, phone, oldMethod)
                    }
                    triggerRealSync()
                }
            }
        }
    }

    fun deletePayment(payment: Payment) {
        viewModelScope.launch {
            repository.deletePayment(payment)
            recordPendingPaymentChange(payment.method)
            recordPendingDeletedPayment(payment.method)
            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.example.util.SyncManager.deletePaymentOnCloud(cloudflareWorkerUrl.value, phone, payment.method)
                }
                triggerRealSync()
            }
        }
    }

    // Cart Actions
    fun addToCart(product: Product) {
        val current = _cart.value.toMutableList()
        val index = current.indexOfFirst { it.product.id == product.id }
        if (index != -1) {
            val oldItem = current[index]
            current[index] = oldItem.copy(quantity = oldItem.quantity + 1)
        } else {
            current.add(CartItem(product, 1))
        }
        _cart.value = current
    }

    fun updateCartQuantity(productId: Long, qty: Int) {
        val current = _cart.value.toMutableList()
        val index = current.indexOfFirst { it.product.id == productId }
        if (index != -1) {
            if (qty <= 0) {
                current.removeAt(index)
            } else {
                current[index] = current[index].copy(quantity = qty)
            }
            _cart.value = current
        }
    }

    fun clearCart() {
        _cart.value = emptyList()
    }

    fun resetActiveVoucher() {
        activeVoucherId.value = null
        activeCustomerName.value = "Not Register"
        activePaymentMethod.value = "CASH"
        activeDiscount.value = 0.0
        activeFee.value = 0.0
        activeNote.value = ""
        _cart.value = emptyList()
        editingVoucherInitialItems.value = emptyMap()
        editingVoucher.value = null
    }

    fun startNewVoucher() {
        isSavingCompletion = true
        val formatter = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
        val rand = (10000000..99999999).random()
        val activePhone = _currentUser.value?.phoneNo ?: sharedPrefs.getString("logged_in_phone", "") ?: ""
        val cachedRole = sharedPrefs.getString("user_role_$activePhone", "") ?: ""
        val isCashier = (_currentUser.value?.role ?: cachedRole) == "CASHIER"
        val prefix = if (isCashier) "C" else "A"
        val receiptNo = prefix + formatter.format(Date()).substring(0, 6) + "-" + rand
        
        activeVoucherId.value = receiptNo
        activeCustomerName.value = "Not Register"
        activePaymentMethod.value = "CASH"
        activeDiscount.value = 0.0
        activeFee.value = 0.0
        activeNote.value = ""
        _cart.value = emptyList()
        editingVoucherInitialItems.value = emptyMap()
        editingVoucher.value = null
        isSavingCompletion = false
    }

    fun loadDraftVoucher(voucher: Voucher) {
        isSavingCompletion = true
        editingVoucher.value = voucher
        viewModelScope.launch {
            isPurchaseMode.value = voucher.isPurchase
            activeVoucherId.value = voucher.receiptNo
            activeCustomerName.value = voucher.customerName
            activePaymentMethod.value = voucher.paymentMethod
            activeDiscount.value = voucher.discount
            activeFee.value = voucher.fee
            activeNote.value = voucher.note
            
            // Get voucher items from direct
            val dbItems = repository.getVoucherItemsDirect(voucher.receiptNo)
            val productsList = repository.allProducts.first()

            val initialMap = mutableMapOf<Long, Int>()
            if (voucher.isCompleted && !voucher.isPurchase) {
                for (item in dbItems) {
                    val pId = if (item.productId != 0L) {
                        item.productId
                    } else {
                        productsList.find { it.name.equals(item.productName, ignoreCase = true) }?.id ?: 0L
                    }
                    if (pId != 0L) {
                        initialMap[pId] = (initialMap[pId] ?: 0) + item.quantity
                    }
                }
            }
            editingVoucherInitialItems.value = initialMap
            
            // Map to CartItems
            val cartItems = dbItems.mapNotNull { item ->
                val baseProd = productsList.find { it.id == item.productId || it.name.equals(item.productName, ignoreCase = true) }
                val prod = if (baseProd != null) {
                    baseProd.copy(
                        name = item.productName,
                        purchasePrice = item.purchasePrice,
                        sellingPrice = item.sellingPrice
                    )
                } else {
                    Product(
                        id = item.productId,
                        name = item.productName,
                        groupName = "",
                        purchasePrice = item.purchasePrice,
                        sellingPrice = item.sellingPrice,
                        unit = "",
                        note = "",
                        trackStock = false,
                        barcode = "",
                        quantity = 0,
                        alertQuantity = 0
                    )
                }
                CartItem(product = prod, quantity = item.quantity)
            }
            _cart.value = cartItems
            isSavingCompletion = false
        }
    }

    fun saveOrUpdateVoucher(
        isCompleted: Boolean,
        customTimestamp: Long? = null,
        customTotalAmount: Double? = null,
        paidAmount: Double = 0.0,
        changeAmount: Double = 0.0,
        balanceAmount: Double = 0.0,
        note: String = activeNote.value,
        discount: Double = activeDiscount.value,
        fee: Double = activeFee.value,
        onComplete: () -> Unit
    ) {
        val voucherId = activeVoucherId.value ?: return
        val items = _cart.value
        
        isSavingCompletion = true
        
        viewModelScope.launch {
            val totalAmount = customTotalAmount ?: items.sumOf { (if (isPurchaseMode.value) it.product.purchasePrice else it.product.sellingPrice) * it.quantity }
            val totalItems = items.sumOf { it.quantity }
            
            val voucher = Voucher(
                receiptNo = voucherId,
                timestamp = customTimestamp ?: System.currentTimeMillis(),
                cashierName = _currentUser.value?.username ?: "Guest Cashier",
                totalAmount = totalAmount,
                totalItems = totalItems,
                customerName = activeCustomerName.value,
                paymentMethod = activePaymentMethod.value,
                isCompleted = isCompleted,
                isPurchase = isPurchaseMode.value,
                paidAmount = paidAmount,
                changeAmount = changeAmount,
                balanceAmount = balanceAmount,
                note = note,
                discount = discount,
                fee = fee
            )
            
            // Revert stock of previous completed items if updating an existing voucher
            val existingVoucher = repository.getVoucherDirect(voucherId)
            if (existingVoucher != null && existingVoucher.isCompleted) {
                val existingItems = repository.getVoucherItemsDirect(voucherId)
                val allProds = repository.allProducts.first()
                for (oldItem in existingItems) {
                    val product = if (oldItem.productId != 0L) {
                        repository.getProductDirect(oldItem.productId)
                    } else {
                        allProds.find { it.name.equals(oldItem.productName, ignoreCase = true) }
                    }
                    if (product != null && product.trackStock) {
                        val restoredQty = if (existingVoucher.isPurchase) {
                            product.quantity - oldItem.quantity
                        } else {
                            product.quantity + oldItem.quantity
                        }
                        repository.updateStockLocally(product.id, restoredQty)
                    }
                }
            }

            repository.insertVoucher(voucher)
            repository.deleteVoucherItems(voucherId)
            
            val stockUpdates = mutableMapOf<Long, Int>()
            for (item in items) {
                val vItem = VoucherItem(
                    voucherId = voucherId,
                    productId = item.product.id,
                    productName = item.product.name,
                    quantity = item.quantity,
                    purchasePrice = item.product.purchasePrice,
                    sellingPrice = item.product.sellingPrice
                )
                repository.insertVoucherItem(vItem)
                
                if (isCompleted && item.product.trackStock) {
                    stockUpdates[item.product.id] = (stockUpdates[item.product.id] ?: 0) + item.quantity
                }
            }
            
            if (isCompleted) {
                for ((pId, totalQty) in stockUpdates) {
                    val dbProduct = repository.getProductDirect(pId)
                    if (dbProduct != null && dbProduct.trackStock) {
                        val remaining = if (isPurchaseMode.value) {
                            dbProduct.quantity + totalQty
                        } else {
                            (dbProduct.quantity - totalQty).coerceAtLeast(0)
                        }
                        repository.updateStockLocally(pId, remaining)
                    }
                }
            }
            
            activeVoucherId.value = null
            activeCustomerName.value = "Not Register"
            activePaymentMethod.value = "CASH"
            _cart.value = emptyList()
            editingVoucherInitialItems.value = emptyMap()
            editingVoucher.value = null
            isSavingCompletion = false
            onComplete()

            // Real-time Cloud Sync for online checkout
            if (isCompleted) {
                val phone = getCloudPhone()
                val isOnlineNow = com.example.util.SyncManager.isNetworkAvailable(getApplication())
                if (isOnlineNow && phone.isNotEmpty()) {
                    viewModelScope.launch {
                        val currentVoucherItems = repository.getVoucherItemsDirect(voucherId)
                        val synced = com.example.util.SyncManager.syncSingleVoucher(
                            workerUrl = cloudflareWorkerUrl.value,
                            userPhone = phone,
                            voucher = voucher,
                            items = currentVoucherItems
                        )
                        if (synced) {
                            repository.markVoucherSynced(voucherId)
                            _isOfflineSaved.value = true
                        } else {
                            _isOfflineSaved.value = false
                        }
                        // Also sync updated products to cloud so stock matches local immediately!
                        triggerRealSync()
                    }
                } else {
                    _isOfflineSaved.value = false
                }
            }
        }
    }

    fun deleteVoucher(voucherId: String) {
        viewModelScope.launch {
            val voucher = repository.getVoucherDirect(voucherId)
            if (voucher != null && voucher.isCompleted) {
                val items = repository.getVoucherItemsDirect(voucherId)
                for (item in items) {
                    val product = repository.getProductDirect(item.productId)
                    if (product != null && product.trackStock) {
                        val newQty = if (voucher.isPurchase) {
                            product.quantity - item.quantity
                        } else {
                            product.quantity + item.quantity
                        }
                        repository.updateStockLocally(product.id, newQty)
                    }
                }
            }
            repository.deleteVoucher(voucherId)
            repository.deleteVoucherItems(voucherId)

            recordPendingDeletedVoucher(voucherId)

            val phone = getCloudPhone()
            if (phone.isNotEmpty() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch(Dispatchers.IO) {
                    val ok = com.example.util.SyncManager.deleteVoucherOnCloud(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        receiptNo = voucherId
                    )
                    if (ok) {
                        removePendingDeletedVoucher(voucherId)
                    }
                }
                triggerRealSync()
            }
        }
    }

    fun updateVoucherDirectly(voucher: Voucher) {
        viewModelScope.launch {
            repository.insertVoucher(voucher.copy(isSynced = false))
            if (isOnlineState.value && isServerReachable.value) {
                triggerRealSync()
            }
        }
    }

    fun updateProductStock(productId: Long, newQuantity: Int) {
        viewModelScope.launch {
            repository.updateStock(productId, newQuantity)
            recordManualStockAdjustment(productId)
            val phone = getCloudPhone()
            val prod = repository.getProductDirect(productId)
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                val ok = syncMutex.withLock {
                    com.example.util.SyncManager.adjustStockOnCloud(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        productId = productId,
                        productName = prod?.name ?: "",
                        newQuantity = newQuantity,
                        newAlertQuantity = prod?.alertQuantity ?: 0
                    )
                }
                if (ok) {
                    repository.markProductsSynced(listOf(productId))
                    removeManualStockAdjustment(productId)
                } else {
                    triggerRealSync()
                }
            }
        }
    }

    fun updateProductStockAndAlert(productId: Long, newQuantity: Int, newAlertQuantity: Int) {
        viewModelScope.launch {
            repository.updateStockAndAlert(productId, newQuantity, newAlertQuantity)
            recordManualStockAdjustment(productId)
            val phone = getCloudPhone()
            val prod = repository.getProductDirect(productId)
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                val ok = syncMutex.withLock {
                    com.example.util.SyncManager.adjustStockOnCloud(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        productId = productId,
                        productName = prod?.name ?: "",
                        newQuantity = newQuantity,
                        newAlertQuantity = newAlertQuantity
                    )
                }
                if (ok) {
                    repository.markProductsSynced(listOf(productId))
                    removeManualStockAdjustment(productId)
                } else {
                    triggerRealSync()
                }
            }
        }
    }

    fun adjustProductStockWithHistory(
        productId: Long,
        adjustmentType: String,
        reasonTitle: String,
        description: String,
        quantity: Int,
        onResult: (Boolean, String) -> Unit
    ) {
        if (quantity <= 0) {
            onResult(false, "အရေအတွက် ၁ ခုနှင့်အထက် ထည့်သွင်းပါ")
            return
        }
        viewModelScope.launch {
            val prod = repository.getProductDirect(productId)
            if (prod == null) {
                onResult(false, "ကုန်ပစ္စည်း ရှာမတွေ့ပါ")
                return@launch
            }
            val beforeQty = prod.quantity
            if (adjustmentType == "STOCK_OUT" && quantity > beforeQty) {
                onResult(false, "လက်ကျန်အရေအတွက် ($beforeQty) ထက် ကျော်လွန်၍ Stock Out မလုပ်နိုင်ပါ")
                return@launch
            }
            val afterQty = if (adjustmentType == "STOCK_IN") beforeQty + quantity else (beforeQty - quantity).coerceAtLeast(0)
            repository.updateStock(productId, afterQty)
            recordManualStockAdjustment(productId)

            val adjustment = StockAdjustment(
                productId = prod.id,
                productName = prod.name,
                adjustmentType = adjustmentType,
                reasonTitle = reasonTitle,
                description = description.trim(),
                quantity = quantity,
                beforeQuantity = beforeQty,
                afterQuantity = afterQty,
                timestamp = System.currentTimeMillis(),
                adjustedBy = _currentUser.value?.username ?: "Admin",
                isSynced = false
            )
            repository.insertStockAdjustment(adjustment)

            val phone = getCloudPhone()
            if (phone.isNotBlank() && com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
                viewModelScope.launch {
                    val ok = syncMutex.withLock {
                        com.example.util.SyncManager.adjustStockOnCloud(
                            workerUrl = cloudflareWorkerUrl.value,
                            userPhone = phone,
                            productId = productId,
                            productName = prod.name,
                            newQuantity = afterQty,
                            newAlertQuantity = prod.alertQuantity
                        )
                    }
                    if (ok) {
                        repository.markProductsSynced(listOf(productId))
                        removeManualStockAdjustment(productId)
                    }
                    triggerRealSync()
                }
            }

            val msg = if (adjustmentType == "STOCK_IN") {
                "Stock In ပေါင်းထည့်ပြီးပါပြီ ($beforeQty → $afterQty)"
            } else {
                "Stock Out နှုတ်ယူပြီးပါပြီ ($beforeQty → $afterQty)"
            }
            onResult(true, msg)
        }
    }

    fun updateCartItemPrice(productId: Long, newPrice: Double) {
        val current = _cart.value.toMutableList()
        val index = current.indexOfFirst { it.product.id == productId }
        if (index != -1) {
            val oldItem = current[index]
            val updatedProduct = if (isPurchaseMode.value) {
                oldItem.product.copy(purchasePrice = newPrice)
            } else {
                oldItem.product.copy(sellingPrice = newPrice)
            }
            current[index] = oldItem.copy(product = updatedProduct)
            _cart.value = current
        }
    }

    fun updateCartItemQuantityByIndex(index: Int, qty: Int) {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            if (qty <= 0) {
                current.removeAt(index)
            } else {
                current[index] = current[index].copy(quantity = qty)
            }
            _cart.value = current
        }
    }

    fun updateCartItemPriceByIndex(index: Int, newPrice: Double) {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            val oldItem = current[index]
            val updatedProduct = if (isPurchaseMode.value) {
                oldItem.product.copy(purchasePrice = newPrice)
            } else {
                oldItem.product.copy(sellingPrice = newPrice)
            }
            current[index] = oldItem.copy(product = updatedProduct)
            _cart.value = current
        }
    }

    fun updateCartItemNameByIndex(index: Int, newName: String) {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            val oldItem = current[index]
            current[index] = oldItem.copy(product = oldItem.product.copy(name = newName))
            _cart.value = current
        }
    }

    fun updateCartItemPurchasePriceByIndex(index: Int, newPurchasePrice: Double) {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            val oldItem = current[index]
            current[index] = oldItem.copy(product = oldItem.product.copy(purchasePrice = newPurchasePrice))
            _cart.value = current
        }
    }

    fun updateCartItemSellingPriceByIndex(index: Int, newSellingPrice: Double) {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            val oldItem = current[index]
            current[index] = oldItem.copy(product = oldItem.product.copy(sellingPrice = newSellingPrice))
            _cart.value = current
        }
    }

    fun updateProductPricesInDb(productId: Long, newPurchasePrice: Double, newSellingPrice: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getProductDirect(productId)
            if (existing != null) {
                val updated = existing.copy(
                    purchasePrice = newPurchasePrice,
                    sellingPrice = newSellingPrice,
                    isSynced = false
                )
                repository.updateProduct(updated)
                if (isOnlineState.value && isServerReachable.value) {
                    triggerRealSync()
                }
            }
        }
    }

    fun duplicateCartItem(index: Int): Int {
        val current = _cart.value.toMutableList()
        if (index >= 0 && index < current.size) {
            val itemToDuplicate = current[index]
            current.add(itemToDuplicate.copy())
            _cart.value = current
            return current.size - 1
        }
        return index
    }

    // Real Cloud Synchronization (Online/Offline First)
    fun triggerUpload() {
        triggerRealSync()
    }

    fun triggerMirrorSync(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val phone = getCloudPhone()
        if (phone.isBlank() || isLoggingOutState) {
            val msg = "ကျေးဇူးပြု၍ အကောင့် Login အရင်ဝင်ပါ (No active login)"
            syncStatusMessage.value = msg
            onResult(false, msg)
            return
        }

        if (!com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
            val msg = "လက်ရှိ အင်တာနက်မရှိပါ (Offline Mode)။ အင်တာနက်ရရှိပါက Cloud သို့ အလိုအလျောက် Mirror တင်ပေးပါမည်။"
            syncStatusMessage.value = msg
            onResult(false, msg)
            return
        }

        val capturedToken = sessionToken
        viewModelScope.launch {
            syncMutex.withLock {
                if (isLoggingOutState || capturedToken != sessionToken || sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                    onResult(false, "Session changed")
                    return@withLock
                }
                _isUploading.value = true
                syncStatusMessage.value = "Local ဒေတာအတိုင်း Cloud ပေါ်သို့ ၁၀၀% Mirror တင်နေပါသည်..."
                val result = com.example.util.SyncManager.mirrorLocalToCloud(
                    workerUrl = cloudflareWorkerUrl.value,
                    userPhone = phone,
                    repository = repository
                )
                clearPendingDeletedProducts()
                _isUploading.value = false
                syncStatusMessage.value = result.message
                if (result.success) {
                    sharedPrefs.edit().putBoolean("pending_db_restore_mirror", false).apply()
                    clearAllPendingDataAndFlags()
                    _isOfflineSaved.value = true
                    val now = System.currentTimeMillis()
                    lastSyncTime.value = now
                    sharedPrefs.edit().putLong("last_sync_time", now).apply()
                }
                onResult(result.success, result.message)
            }
        }
    }

    suspend fun mirrorLocalToCloudDirect(): Boolean {
        val phone = getCloudPhone()
        if (phone.isBlank() || isLoggingOutState) return false
        if (!com.example.util.SyncManager.isNetworkAvailable(getApplication())) return false
        val capturedToken = sessionToken
        return syncMutex.withLock {
            if (isLoggingOutState || capturedToken != sessionToken || sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                return@withLock false
            }
            _isUploading.value = true
            syncStatusMessage.value = "Restored Database ကို Cloud ပေါ်သို့ Mirror တင်နေပါသည်..."
            val result = com.example.util.SyncManager.mirrorLocalToCloud(
                workerUrl = cloudflareWorkerUrl.value,
                userPhone = phone,
                repository = repository
            )
            if (result.success) {
                sharedPrefs.edit().putBoolean("pending_db_restore_mirror", false).commit()
                clearAllPendingDataAndFlags()
                _isOfflineSaved.value = true
                val now = System.currentTimeMillis()
                lastSyncTime.value = now
                sharedPrefs.edit().putLong("last_sync_time", now).apply()
            }
            _isUploading.value = false
            syncStatusMessage.value = result.message
            result.success
        }
    }

    fun triggerRealSync(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val phone = getCloudPhone()
        if (phone.isBlank() || isLoggingOutState) {
            val msg = "ကျေးဇူးပြု၍ အကောင့် Login အရင်ဝင်ပါ (No active login)"
            syncStatusMessage.value = msg
            onResult(false, msg)
            return
        }

        if (!com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
            val msg = "လက်ရှိ အင်တာနက်မရှိပါ (Offline Mode)။ အရောင်းဒေတာများကို ဖုန်းထဲတွင် သိမ်းဆည်းထားပြီး အင်တာနက်ရရှိပါက အလိုအလျောက် Cloud သို့ တင်ပေးပါမည်။"
            syncStatusMessage.value = msg
            _isOfflineSaved.value = false
            onResult(false, msg)
            return
        }

        val capturedToken = sessionToken
        viewModelScope.launch {
            syncMutex.withLock {
                if (isLoggingOutState || capturedToken != sessionToken || sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                    onResult(false, "Session changed")
                    return@withLock
                }
                _isUploading.value = true
                syncStatusMessage.value = "Cloud သို့ ဒေတာတင်နေပါသည်..."
                val (delIds, delNames) = getPendingDeletedProducts()
                val delVouchers = getPendingDeletedVouchers()
                val manualStockProds = getPendingManualStockAdjustments()
                val delCustomers = getPendingDeletedCustomers()
                val delSuppliers = getPendingDeletedSuppliers()
                val delGroups = getPendingDeletedGroups()
                val delUnits = getPendingDeletedUnits()
                val delPayments = getPendingDeletedPayments()
                val result = com.example.util.SyncManager.syncAllPendingData(
                    workerUrl = cloudflareWorkerUrl.value,
                    userPhone = phone,
                    repository = repository,
                    pendingDeletedProductIds = delIds,
                    pendingDeletedProductNames = delNames,
                    pendingDeletedVouchers = delVouchers,
                    manualStockAdjustedProductIds = manualStockProds,
                    pendingDeletedCustomers = delCustomers,
                    pendingDeletedSuppliers = delSuppliers,
                    pendingDeletedGroups = delGroups,
                    pendingDeletedUnits = delUnits,
                    pendingDeletedPayments = delPayments
                )
                if (result.success) {
                    if (delIds.isNotEmpty() || delNames.isNotEmpty()) {
                        clearPendingDeletedProducts()
                    }
                    if (delVouchers.isNotEmpty()) {
                        clearPendingDeletedVouchers()
                    }
                    if (manualStockProds.isNotEmpty()) {
                        clearManualStockAdjustments()
                    }
                    if (delCustomers.isNotEmpty()) clearPendingDeletedCustomers()
                    if (delSuppliers.isNotEmpty()) clearPendingDeletedSuppliers()
                    if (delGroups.isNotEmpty()) clearPendingDeletedGroups()
                    if (delUnits.isNotEmpty()) clearPendingDeletedUnits()
                    if (delPayments.isNotEmpty()) clearPendingDeletedPayments()
                    clearAllPendingMasterDataChanges()
                }
                _isUploading.value = false
                syncStatusMessage.value = result.message
                if (result.success) {
                    _isOfflineSaved.value = true
                    val now = System.currentTimeMillis()
                    lastSyncTime.value = now
                    sharedPrefs.edit().putLong("last_sync_time", now).apply()
                }
                onResult(result.success, result.message)
            }
        }
    }

    fun testServerConnection(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = com.example.util.SyncManager.checkServerReachability(cloudflareWorkerUrl.value)
            isServerReachable.value = result.isReachable
            onResult(result.isReachable, result.message)
        }
    }

    fun triggerPullCloudData(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val phone = getCloudPhone()
        if (phone.isBlank() || isLoggingOutState) {
            val msg = "ကျေးဇူးပြု၍ အကောင့် Login အရင်ဝင်ပါ"
            onResult(false, msg)
            return
        }

        if (!com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
            val msg = "အင်တာနက် မရှိပါ (No internet connection)"
            onResult(false, msg)
            return
        }

        val capturedToken = sessionToken
        viewModelScope.launch {
            syncMutex.withLock {
                if (isLoggingOutState || capturedToken != sessionToken || sharedPrefs.getString("logged_in_phone", null).isNullOrBlank()) {
                    onResult(false, "Session changed")
                    return@withLock
                }
                _isUploading.value = true
                cloudDownloadProgress.value = 0.05f
                cloudDownloadProgressMsg.value = "Cloud မှ အကောင့်ဒေတာများ ရယူနေပါသည်..."
                syncStatusMessage.value = "Cloud မှ ဒေတာရယူနေပါသည်..."
                val result = com.example.util.SyncManager.pullCloudData(
                    workerUrl = cloudflareWorkerUrl.value,
                    userPhone = phone,
                    repository = repository,
                    pendingDeletedCustomers = getPendingDeletedCustomers().toSet(),
                    pendingDeletedSuppliers = getPendingDeletedSuppliers().toSet(),
                    pendingDeletedGroups = getPendingDeletedGroups().toSet(),
                    pendingDeletedUnits = getPendingDeletedUnits().toSet(),
                    pendingDeletedPayments = getPendingDeletedPayments().toSet(),
                    onProgress = { progress, msg ->
                        cloudDownloadProgress.value = progress
                        cloudDownloadProgressMsg.value = msg
                        syncStatusMessage.value = msg
                    }
                )
                _isUploading.value = false
                syncStatusMessage.value = result.message
                if (result.success) {
                    cloudDownloadProgress.value = 1f
                    _isOfflineSaved.value = true
                    val now = System.currentTimeMillis()
                    lastSyncTime.value = now
                    sharedPrefs.edit().putLong("last_sync_time", now).apply()
                    ensureDefaultPaymentsIfEmpty()
                }
                onResult(result.success, result.message)
            }
        }
    }

    suspend fun pullCloudDataDirect(phoneNo: String? = null): Boolean {
        if (sharedPrefs.getBoolean("pending_db_restore_mirror", false)) return false
        val phone = getCloudPhone(phoneNo)
        if (phone.isBlank() || isLoggingOutState) return false
        val capturedToken = sessionToken
        return syncMutex.withLock {
            if (isLoggingOutState || capturedToken != sessionToken || sharedPrefs.getString("logged_in_phone", null).isNullOrBlank() || sharedPrefs.getBoolean("pending_db_restore_mirror", false)) {
                return@withLock false
            }
            val result = com.example.util.SyncManager.pullCloudData(
                workerUrl = cloudflareWorkerUrl.value,
                userPhone = phone,
                repository = repository,
                pendingDeletedCustomers = getPendingDeletedCustomers().toSet(),
                pendingDeletedSuppliers = getPendingDeletedSuppliers().toSet(),
                pendingDeletedGroups = getPendingDeletedGroups().toSet(),
                pendingDeletedUnits = getPendingDeletedUnits().toSet(),
                pendingDeletedPayments = getPendingDeletedPayments().toSet(),
                onProgress = { progress, msg ->
                    cloudDownloadProgress.value = progress
                    cloudDownloadProgressMsg.value = msg
                }
            )
            if (result.success) {
                val now = System.currentTimeMillis()
                lastSyncTime.value = now
                sharedPrefs.edit().putLong("last_sync_time", now).apply()
            }
            result.success
        }
    }

    private fun resetRegistrationFields() {
        registerUsername.value = ""
        registerBusinessName.value = ""
        registerBusinessType.value = ""
        registerAddress.value = ""
        registerPhone.value = ""
        registerPassword.value = ""
        registerRetypePassword.value = ""
    }

    private fun clearAllPendingDataAndFlags() {
        clearPendingDeletedProducts()
        clearPendingDeletedVouchers()
        clearManualStockAdjustments()
        clearPendingDeletedCustomers()
        clearPendingDeletedSuppliers()
        clearPendingDeletedGroups()
        clearPendingDeletedUnits()
        clearPendingDeletedPayments()
        clearAllPendingMasterDataChanges()
    }

    private fun clearSessionPrefsSynchronously() {
        val editor = sharedPrefs.edit()
            .remove("logged_in_phone")
            .remove("last_registered_phone")
            .remove("data_owner_phone")
            .remove("last_sync_time")
            .remove("last_checked_date")
        for (key in sharedPrefs.all.keys) {
            if (key.startsWith("user_role_") ||
                key.startsWith("cashier_admin_phone_") ||
                key.startsWith("cashier_join_code_") ||
                key.startsWith("account_expires_at_") ||
                key.startsWith("account_days_left_") ||
                key.startsWith("account_is_trial_")
            ) {
                editor.remove(key)
            }
        }
        editor.commit()
    }

    suspend fun clearAllBusinessData() {
        withContext(Dispatchers.IO) {
            repository.clearAllBusinessData()
        }
        resetActiveVoucher()
        resetSalesHistoryFilters()
    }

    suspend fun clearAllAccountsAndBusinessData() {
        withContext(Dispatchers.IO) {
            repository.clearAllBusinessData()
            repository.clearAllAccounts()
        }
        clearAllPendingDataAndFlags()
        clearSessionPrefsSynchronously()
        resetActiveVoucher()
        resetSalesHistoryFilters()
        clearProductForm()
        editingProduct.value = null
        editingCustomer.value = null
        editingSupplier.value = null
        resetRegistrationFields()
        isAccountExpired.value = false
        lastSyncTime.value = 0L
        syncStatusMessage.value = ""
        _isOfflineSaved.value = true
    }

    suspend fun isLocalDataEmpty(): Boolean {
        return withContext(Dispatchers.IO) {
            val prods = repository.getAllProductsDirect()
            val vouchers = repository.getAllCompletedVouchersDirect()
            val custs = repository.getAllCustomersDirect()
            val sups = repository.getAllSuppliersDirect()
            val groups = repository.getAllGroupsDirect()
            val units = repository.getAllUnitsDirect()
            prods.isEmpty() && vouchers.isEmpty() && custs.isEmpty() && sups.isEmpty() && groups.isEmpty() && units.isEmpty()
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        isLoggingOutState = true
        sessionToken++
        _currentUser.value = null
        clearSessionPrefsSynchronously()
        resetActiveVoucher()
        resetSalesHistoryFilters()
        clearProductForm()
        resetRegistrationFields()
        isAccountExpired.value = false
        lastSyncTime.value = 0L
        syncStatusMessage.value = ""
        viewModelScope.launch {
            try {
                syncMutex.withLock {
                    clearAllAccountsAndBusinessData()
                }
            } finally {
                isLoggingOutState = false
            }
            onComplete()
        }
    }

    fun logoutWithSync(
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        isLoggingOutState = true
        sessionToken++
        val phone = getCloudPhone()
        if (phone.isBlank() || !com.example.util.SyncManager.isNetworkAvailable(getApplication())) {
            _currentUser.value = null
            clearSessionPrefsSynchronously()
            viewModelScope.launch {
                try {
                    onProgress(0.5f, "ဒေတာများ ရှင်းလင်းနေပါသည်...")
                    syncMutex.withLock {
                        clearAllAccountsAndBusinessData()
                    }
                    onProgress(1.0f, "Logout ပြုလုပ်ပြီးပါပြီ")
                } finally {
                    isLoggingOutState = false
                }
                onComplete(true, "Logout ပြုလုပ်ပြီးပါပြီ")
            }
            return
        }

        viewModelScope.launch {
            var syncOk = false
            var resultMsg = "Logout ပြုလုပ်ပြီးပါပြီ"
            try {
                _isUploading.value = true
                onProgress(0.10f, "Cloud ချိတ်ဆက်မှု စစ်ဆေးနေပါသည်...")
                onProgress(0.25f, "အော့ဖ်လိုင်း စာရင်းများ စစ်ဆေးစုစည်းနေပါသည်...")

                syncMutex.withLock {
                    val (delIds, delNames) = getPendingDeletedProducts()
                    val delVouchers = getPendingDeletedVouchers()
                    val manualStockProds = getPendingManualStockAdjustments()
                    val delCustomers = getPendingDeletedCustomers()
                    val delSuppliers = getPendingDeletedSuppliers()
                    val delGroups = getPendingDeletedGroups()
                    val delUnits = getPendingDeletedUnits()
                    val delPayments = getPendingDeletedPayments()

                    onProgress(0.40f, "အော့ဖ်လိုင်း စာရင်းများကို Cloud သို့ ပို့ဆောင်နေပါသည်...")
                    val syncResult = com.example.util.SyncManager.syncAllPendingData(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        repository = repository,
                        pendingDeletedProductIds = delIds,
                        pendingDeletedProductNames = delNames,
                        pendingDeletedVouchers = delVouchers,
                        manualStockAdjustedProductIds = manualStockProds,
                        pendingDeletedCustomers = delCustomers,
                        pendingDeletedSuppliers = delSuppliers,
                        pendingDeletedGroups = delGroups,
                        pendingDeletedUnits = delUnits,
                        pendingDeletedPayments = delPayments
                    )

                    onProgress(0.70f, "Local အတိုင်း Cloud သို့ ၁၀၀% Mirror သိမ်းဆည်းနေပါသည်...")
                    val mirrorResult = com.example.util.SyncManager.mirrorLocalToCloud(
                        workerUrl = cloudflareWorkerUrl.value,
                        userPhone = phone,
                        repository = repository,
                        onProgress = { p, msg ->
                            val combined = 0.70f + (p * 0.25f)
                            onProgress(combined, msg)
                        }
                    )

                    onProgress(0.95f, "အကောင့် ဒေတာများ လုံခြုံစွာ ရှင်းလင်းနေပါသည်...")
                    _currentUser.value = null
                    clearAllAccountsAndBusinessData()
                    syncOk = syncResult.success || mirrorResult.success
                    resultMsg = if (syncOk) {
                        "Cloud ပေါ်သို့ ဒေတာများ သိမ်းဆည်းပြီး Logout ပြုလုပ်ပြီးပါပြီ"
                    } else {
                        syncResult.message
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _currentUser.value = null
                syncMutex.withLock {
                    clearAllAccountsAndBusinessData()
                }
            } finally {
                _isUploading.value = false
                isLoggingOutState = false
            }
            onProgress(1.0f, "Logout ပြုလုပ်ပြီးပါပြီ")
            onComplete(syncOk, resultMsg)
        }
    }

    fun addExpenseCategory(name: String, iconName: String) {
        viewModelScope.launch {
            repository.insertExpenseCategory(ExpenseCategory(name = name, iconName = iconName))
        }
    }

    fun deleteExpenseCategory(category: ExpenseCategory) {
        viewModelScope.launch {
            repository.deleteExpenseCategory(category)
        }
    }

    fun saveExpense(expense: Expense, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            if (expense.id == 0L) {
                repository.insertExpense(expense)
            } else {
                repository.updateExpense(expense)
            }
            onComplete()
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }

    suspend fun getExpenseById(id: Long): Expense? {
        return repository.getExpenseById(id)
    }

    // Helper functions for sales reports
    fun getVoucherItemsFlow(voucherId: String): Flow<List<VoucherItem>> {
        return repository.getVoucherItems(voucherId)
    }
}

package com.mockschan.cashless

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.core.view.isVisible
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import android.graphics.Color
import java.util.Timer
import java.util.TimerTask

class MainActivity : Activity() {

    // NFC & User
    private var nfcAdapter: NfcAdapter? = null
    private var currentUID: String? = null
    private var currentBalance: Double = 0.0

    private var lastUIDForTheke: String? = null
    private var thekeResetAction: (() -> Unit)? = null
    private var thekeRefreshTotals: (() -> Unit)? = null

    // Trigger zum Neuaufbau der Produktliste (fix: Produkte erscheinen schon nach dem ersten Scan)
    private var thekeBuildProducts: (() -> Unit)? = null

    // Scan-Session (2 Minuten, vom Backend validiert; Client löscht lokal zur UX)
    private var scanSessionId: String? = null
    private var scanSessionTimer: Timer? = null
    private val SESSION_TTL_MS = 120_000L

    // Beleg der letzten Zahlung (bleibt sichtbar)
    private var lastReceipt: JSONObject? = null

    // UI
    private lateinit var mainContent: FrameLayout

    // HTTP
    private val client = OkHttpClient()
    private val apiBaseUrl = "https://cashless.local"

    // Settings
    private lateinit var prefs: SharedPreferences
    private var wineEnabled = false

    // PIN
    private val PINKASSE = "2209"
    private val PINADMIN = "1511"

    // Daten
    private data class Product(
        val id: Int,
        val name: String,
        val price: Double,
        val inDrinks: Boolean,
        val inFood: Boolean,
        val inWine: Boolean
    )
    private var productsCache: List<Product> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mainContent = findViewById(R.id.mainContent)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        wineEnabled = prefs.getBoolean("wineEnabled", false)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "⚠️ NFC nicht verfügbar – manueller UID-Modus aktiv", Toast.LENGTH_LONG).show()
        }

        showHome()
    }

    override fun onBackPressed() {
        val isTheke     = mainContent.findViewById<View?>(R.id.thekeRoot) != null
        val isSelector  = mainContent.findViewById<View?>(R.id.thekeSelectorRoot) != null

        when {
            isTheke -> {
                thekeResetAction = null
                thekeRefreshTotals = null
                thekeBuildProducts = null
                showThekeSelector()
            }
            isSelector -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            { tag ->
                val bytes = tag?.id ?: return@enableReaderMode
                val uid = bytes.joinToString("") { "%02X".format(it) }
                runOnUiThread {
                    val inTheke = mainContent.findViewById<View?>(R.id.thekeRoot) != null

                    // Wenn ein anderes Band gescannt wird, Theke zurücksetzen
                    if (inTheke && thekeResetAction != null && lastUIDForTheke != null && lastUIDForTheke != uid) {
                        thekeResetAction?.invoke()
                    }

                    currentUID = uid
                    lastUIDForTheke = uid
                    Toast.makeText(this, "Tag erkannt: $uid", Toast.LENGTH_SHORT).show()

                    // Balance immer aktualisieren
                    checkBalance(uid) { thekeRefreshTotals?.invoke() }

                    // KASSE: Falls wir gerade im Kassenscreen sind → Buttons aktivieren & UID anzeigen
                    val btnLoad = mainContent.findViewById<Button?>(R.id.btnLoadTokens)
                    val btnRef  = mainContent.findViewById<Button?>(R.id.btnRefund)
                    val uidInfo = mainContent.findViewById<TextView?>(R.id.uidInfo)
                    if (btnLoad != null && btnRef != null) {
                        btnLoad.isEnabled = true
                        btnRef.isEnabled = true
                        mainContent.findViewById<TextView?>(R.id.kasseScanHint)?.visibility = View.GONE
                        uidInfo?.text = "Gescannt: $uid"
                        updateStatusCards()
                    }

                    // THEKE: sofort eine POS-Session öffnen; nach dem Laden Liste bauen
                    if (inTheke) {
                        openSessionAfterScan(uid) {
                            thekeBuildProducts?.invoke()  // UI jetzt aufbauen
                        }
                    }
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // ------------------------------------------------------------
    // HOME
    // ------------------------------------------------------------
    private fun showHome() {
        mainContent.removeAllViews()
        val v = layoutInflater.inflate(R.layout.view_home, mainContent, false)
        mainContent.addView(v)

        val btnKasse = v.findViewById<Button>(R.id.btnKasse)
        val btnTheke = v.findViewById<Button>(R.id.btnTheke)
        val btnAdmin = v.findViewById<Button>(R.id.btnAdmin)
        val btnManualUid = v.findViewById<Button>(R.id.btnManualUid)

        updateStatusCards()

        btnKasse.setOnClickListener { promptPin(PINKASSE) { showKasse() } }
        btnTheke.setOnClickListener { showThekeSelector() }
        btnAdmin.setOnClickListener { promptPin(PINADMIN) { showAdmin() } }

        btnManualUid.setOnClickListener {
            promptManualUID { uid ->
                currentUID = uid
                checkBalance(uid)
            }
        }
    }

    // ------------------------------------------------------------
    // KASSE (scan-first)
    // ------------------------------------------------------------
    private fun showKasse() {
        mainContent.removeAllViews()
        val v = layoutInflater.inflate(R.layout.view_kasse, mainContent, false)
        mainContent.addView(v)

        v.findViewById<Button>(R.id.btnBackKasse).setOnClickListener { showHome() }

        // Initialzustand: Buttons deaktivieren, solange kein Scan
        val hasUid = currentUID != null
        setKasseUiEnabled(v, hasUid)
        if (!hasUid) showKasseScanHint(v) else v.findViewById<TextView?>(R.id.kasseScanHint)?.visibility = View.GONE
        v.findViewById<TextView>(R.id.uidInfo).text = "Gescannt: ${currentUID ?: "-"}"
        updateStatusCards()

        // Aufladen
        v.findViewById<Button>(R.id.btnLoadTokens).setOnClickListener {
            if (currentUID == null) {
                Toast.makeText(this, "Bitte erst Band scannen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showLoadDialogWithAfter {
                // Nach Erfolg Kasse leeren und auf nächsten Scan warten
                clearKasseState(v)
                showKasseScanHint(v)
            }
        }

        // Auszahlen
        v.findViewById<Button>(R.id.btnRefund).setOnClickListener {
            if (currentUID == null) {
                Toast.makeText(this, "Bitte erst Band scannen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Guthaben auszahlen")
                .setMessage("Wirklich gesamtes Guthaben auszahlen?")
                .setPositiveButton("Ja") { _, _ ->
                    sendTransaction("refund", 0.0) {
                        clearKasseState(v)
                        showKasseScanHint(v)
                    }
                }
                .setNegativeButton("Nein", null)
                .show()
        }
    }

    /** Kasse-UI aktivieren/deaktivieren (Buttons + Anzeige) */
    private fun setKasseUiEnabled(root: View, enabled: Boolean) {
        root.findViewById<Button>(R.id.btnLoadTokens)?.isEnabled = enabled
        root.findViewById<Button>(R.id.btnRefund)?.isEnabled = enabled
        val uidInfo = root.findViewById<TextView>(R.id.uidInfo)
        if (!enabled) {
            uidInfo?.text = "Gescannt: -"
        } else {
            uidInfo?.text = "Gescannt: ${currentUID ?: "-"}"
        }
    }

    /** Nach Abschluss einer Kassen-Transaktion alles leeren & Buttons sperren */
    private fun clearKasseState(root: View) {
        currentUID = null
        currentBalance = 0.0
        setKasseUiEnabled(root, false)
        updateStatusCards()
    }

    /** Hinweistext, wenn in der Kasse noch kein Band gescannt wurde (optional) */
    private fun showKasseScanHint(root: View) {
        root.findViewById<TextView?>(R.id.kasseScanHint)?.let { hint ->
            hint.text = "Bitte Armband scannen, um aufzuladen oder auszuzahlen."
            hint.visibility = View.VISIBLE
        }
    }

    // Alter Auflade-Dialog: neue Variante mit Callback nach Erfolg
    private fun showLoadDialogWithAfter(onAfterSuccess: () -> Unit) {
        var amount = 0 // nur ganze Chips

        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val display = TextView(this).apply {
            text = amount.toString()
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val minus = Button(context).apply {
                text = "–"
                setOnClickListener {
                    if (amount > 0) {
                        amount -= 1
                        display.text = amount.toString()
                    }
                }
            }
            val plus = Button(context).apply {
                text = "+"
                setOnClickListener {
                    amount += 1
                    display.text = amount.toString()
                }
            }
            addView(minus)
            addView(display, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(plus)
        }

        val quickGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val values = (5..50 step 5).toList()
            for (chunk in values.chunked(3)) {
                val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { v ->
                    val b = Button(this@MainActivity).apply {
                        text = "+$v"
                        setOnClickListener {
                            amount += v
                            display.text = amount.toString()
                        }
                    }
                    r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                addView(r)
            }
        }

        val manual = Button(this).apply {
            text = "Betrag eingeben"
            setOnClickListener {
                val inp = EditText(this@MainActivity).apply {
                    hint = "z. B. 12"
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Chips manuell")
                    .setView(inp)
                    .setPositiveButton("OK") { _, _ ->
                        val v = inp.text.toString().toIntOrNull()
                        if (v != null && v >= 0) {
                            amount = v
                            display.text = amount.toString()
                        } else {
                            Toast.makeText(this@MainActivity, "Ungültige Eingabe", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }

        dialog.addView(row)
        dialog.addView(quickGrid)
        dialog.addView(manual)

        AlertDialog.Builder(this)
            .setTitle("Chips aufladen")
            .setView(dialog)
            .setPositiveButton("Aufladen") { _, _ ->
                if (currentUID == null) {
                    Toast.makeText(this, "Bitte erst scannen", Toast.LENGTH_SHORT).show()
                } else if (amount <= 0) {
                    Toast.makeText(this, "Betrag wählen", Toast.LENGTH_SHORT).show()
                } else {
                    sendTransaction("load", amount.toDouble()) { onAfterSuccess() }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------
    // THEKE
    // ------------------------------------------------------------
    private fun showThekeSelector() {
        mainContent.removeAllViews()
        val v = layoutInflater.inflate(R.layout.view_theke_selector, mainContent, false)
        mainContent.addView(v)

        // nicht im Theken-Screen → alte Callbacks löschen
        thekeResetAction = null
        thekeRefreshTotals = null
        thekeBuildProducts = null
        // offene Scan-Session lokal beenden (UI-Logik), Server bleibt maßgeblich
        clearLocalScanSession()

        v.findViewById<Button>(R.id.btnBackThekeSelector).setOnClickListener { showHome() }

        val btnDrinks = v.findViewById<Button>(R.id.btnDrinks)
        val btnFood   = v.findViewById<Button>(R.id.btnFood)
        val btnWine   = v.findViewById<Button>(R.id.btnWine)

        btnWine.isVisible = wineEnabled

        if (currentUID == null) {
            Toast.makeText(this, "Bitte erst Armband scannen.", Toast.LENGTH_SHORT).show()
        }

        btnDrinks.setOnClickListener { showTheke("drinks") }
        btnFood.setOnClickListener { showTheke("food") }
        btnWine.setOnClickListener { showTheke("wine") }
    }

    private fun showTheke(category: String) {
        mainContent.removeAllViews()
        val v = layoutInflater.inflate(R.layout.view_theke, mainContent, false)
        mainContent.addView(v)

        val btnBack = v.findViewById<Button>(R.id.btnBackTheke)
        btnBack.setOnClickListener {
            thekeResetAction = null
            thekeRefreshTotals = null
            thekeBuildProducts = null
            showThekeSelector()
        }

        val tokenDisplay= v.findViewById<TextView>(R.id.tokenDisplay)
        val tokenWarning= v.findViewById<TextView>(R.id.tokenWarning)
        val orderNote   = v.findViewById<TextView>(R.id.orderNote)
        val productList = v.findViewById<LinearLayout>(R.id.productList)
        val btnPay      = v.findViewById<Button>(R.id.btnPay)
        val btnCancel   = v.findViewById<Button>(R.id.btnCancel)

        // Wenn (noch) keine Session existiert → Hinweis anzeigen
        if (scanSessionId == null) {
            showScanPrompt(productList)
        }

        // Auswahl pro Theken-Vorgang
        val selection = mutableMapOf<Int, Int>()
        val handler = Handler(Looper.getMainLooper())

        fun calcTotal(): Double =
            selection.entries.sumOf { (id, qty) ->
                val price = productsCache.find { it.id == id }?.price ?: 0.0
                price * qty
            }

        fun refreshTotals() {
            val total = calcTotal()
            val newBal = currentBalance - total
            tokenDisplay.text = "Chips: %.2f → %.2f".format(currentBalance, newBal)
            tokenWarning.isVisible = newBal < 0
            tokenWarning.text = if (newBal < 0) "Nicht genug Chips!" else ""
        }

        fun buildProductRows() {
            productList.removeAllViews()
            val filtered = when (category) {
                "drinks" -> productsCache.filter { it.inDrinks }
                "food"   -> productsCache.filter { it.inFood }
                "wine"   -> productsCache.filter { it.inWine }
                else     -> emptyList()
            }

            filtered.forEachIndexed { index, p ->
                selection.putIfAbsent(p.id, 0)
                val row = LayoutInflater.from(this).inflate(R.layout.item_product_row, productList, false)
                val name = row.findViewById<TextView>(R.id.txtProductName)
                val price = row.findViewById<TextView>(R.id.txtProductPrice)
                val qty = row.findViewById<TextView>(R.id.txtQty)
                val btnMinus = row.findViewById<Button>(R.id.btnMinus)
                val btnPlus = row.findViewById<Button>(R.id.btnPlus)

                name.text = p.name
                price.text = "Preis: %.2f Chips".format(p.price)
                qty.text = (selection[p.id] ?: 0).toString()

                fun applyRowColor() {
                    val q = selection[p.id] ?: 0
                    val base = if (index % 2 == 0) "#F7F7F7" else "#FFFFFF" // Zebra
                    val highlight = "#E8F5E9" // zartes Grün bei Auswahl
                    row.setBackgroundColor(Color.parseColor(if (q > 0) highlight else base))
                }
                applyRowColor()

                btnPlus.setOnClickListener {
                    selection[p.id] = (selection[p.id] ?: 0) + 1
                    qty.text = selection[p.id].toString()
                    applyRowColor()
                    refreshTotals()
                }
                btnMinus.setOnClickListener {
                    val cur = selection[p.id] ?: 0
                    if (cur > 0) {
                        selection[p.id] = cur - 1
                        qty.text = selection[p.id].toString()
                        applyRowColor()
                        refreshTotals()
                    }
                }

                productList.addView(row)
            }
            refreshTotals()
        }

        // Callback setzen: späterer Neuaufbau nach erstem Scan
        thekeBuildProducts = { buildProductRows() }

        // Reset-Action: bei neuem Scan Auswahl leeren + Anzeige neu zeichnen
        thekeResetAction = {
            selection.clear()
            buildProductRows()
            orderNote.isVisible = false
            orderNote.setBackgroundColor(Color.TRANSPARENT)
            orderNote.setTextColor(Color.BLACK)
            tokenWarning.isVisible = false
            refreshTotals()
        }
        lastUIDForTheke = currentUID

        // Wird aufgerufen, wenn checkBalance nach Scan fertig ist
        thekeRefreshTotals = { refreshTotals() }

        // Pay mit Session (verbraucht die Session serverseitig)
        btnPay.setOnClickListener {
            val total = calcTotal()
            when {
                total <= 0 -> Toast.makeText(this, "Keine Auswahl", Toast.LENGTH_SHORT).show()
                total > currentBalance -> Toast.makeText(this, "Nicht genug Chips", Toast.LENGTH_SHORT).show()
                scanSessionId == null -> Toast.makeText(this, "Bitte zuerst Band scannen", Toast.LENGTH_SHORT).show()
                else -> {
                    val items = JSONArray().apply {
                        selection.filter { it.value > 0 }.forEach { (id, qty) ->
                            val prod = productsCache.find { it.id == id }
                            if (prod != null) {
                                put(JSONObject().apply {
                                    put("id", prod.id)
                                    put("name", prod.name)
                                    put("qty", qty)
                                    put("price", prod.price)
                                })
                            }
                        }
                    }
                    postChargeWithSession(total, items) { ok, receipt ->
                        if (ok) {
                            lastReceipt = receipt // Beleg bleibt sichtbar (eigenes Panel möglich)

                            // Grüne Bestätigung inline
                            val note = selection
                                .filter { it.value > 0 }
                                .entries
                                .joinToString("\n") { (id, qty) ->
                                    val prodName = productsCache.find { it.id == id }?.name ?: "Unbekannt"
                                    "$prodName × $qty"
                                }
                            orderNote.apply {
                                isVisible = true
                                text = "✓ Bezahlt\n$note"
                                setBackgroundColor(Color.parseColor("#43A047"))
                                setTextColor(Color.WHITE)
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                orderNote.isVisible = false
                                orderNote.setBackgroundColor(Color.TRANSPARENT)
                                orderNote.setTextColor(Color.BLACK)
                            }, 2 * 60 * 1000L)

                            // Session lokal leeren & Produkte ausblenden
                            clearLocalScanSession()
                            selection.clear()
                            showScanPrompt(productList)

                            // Balance neu laden (nur Anzeige)
                            currentUID?.let { uid -> checkBalance(uid) { thekeRefreshTotals?.invoke() } }
                        }
                    }
                }
            }
        }

        // Cancel: Session invalidieren, Auswahl leeren, Produkte ausblenden bis neuer Scan
        btnCancel.setOnClickListener {
            posCancel {
                selection.clear()
                showScanPrompt(productList)
                orderNote.isVisible = false
                orderNote.setBackgroundColor(Color.TRANSPARENT)
                orderNote.setTextColor(Color.BLACK)
                refreshTotals()
            }
        }

        // Produkte laden (nur wenn Session existiert, sonst Hinweis)
        loadThekeProducts {
            if (scanSessionId == null) {
                showScanPrompt(productList)
            } else {
                buildProductRows()
            }
        }

        refreshTotals()
    }

    // ------------------------------------------------------------
    // ADMIN
    // ------------------------------------------------------------
    private fun showAdmin() {
        mainContent.removeAllViews()
        val v = layoutInflater.inflate(R.layout.view_admin, mainContent, false)
        mainContent.addView(v)

        v.findViewById<Button>(R.id.btnBackAdmin).setOnClickListener { showHome() }
        val adminContent = v.findViewById<FrameLayout>(R.id.adminContent)

        val switchWine = v.findViewById<Switch>(R.id.switchWine)
        switchWine.isChecked = wineEnabled
        switchWine.setOnCheckedChangeListener { _, isChecked ->
            wineEnabled = isChecked
            prefs.edit().putBoolean("wineEnabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Wein aktiviert" else "Wein deaktiviert", Toast.LENGTH_SHORT).show()
        }

        val tabProducts = v.findViewById<Button>(R.id.tabProducts)
        val tabUsers    = v.findViewById<Button>(R.id.tabUsers)
        val tabCounter  = v.findViewById<Button>(R.id.tabCounter) // NEU

        tabProducts.setOnClickListener { renderAdminProducts(adminContent) }
        tabUsers.setOnClickListener    { renderAdminUsers(adminContent) }
        tabCounter.setOnClickListener  { renderAdminCounter(adminContent) } // NEU

        renderAdminProducts(adminContent) // default
    }

    // --- NEU: Counter/History-Tab rendern ---
    private fun renderAdminCounter(container: FrameLayout) {
        container.removeAllViews()
        val view = layoutInflater.inflate(R.layout.view_admin_counter, container, false)
        container.addView(view)

        val txtSummary = view.findViewById<TextView>(R.id.txtSummary)
        val btnRefresh = view.findViewById<Button>(R.id.btnSummaryRefresh)
        val btnReset   = view.findViewById<Button>(R.id.btnReset)
        val listHistory= view.findViewById<LinearLayout>(R.id.listHistory)

        fun refreshAll() {
            loadSummary { since, topup, ret, net ->
                runOnUiThread {
                    txtSummary.text = "Seit: $since\nAufgeladen: ${fmt(topup)}\nZurückgegeben: ${fmt(ret)}\nNetto: ${fmt(net)}"
                }
            }
            loadHistory { arr ->
                runOnUiThread {
                    listHistory.removeAllViews()
                    if (arr.length() == 0) {
                        listHistory.addView(TextView(this).apply { text = "Noch keine Snapshots." })
                    } else {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            listHistory.addView(TextView(this).apply {
                                text = "• ${o.getString("name")} | ${o.optString("range_start")} → ${o.optString("range_end")} | " +
                                        "Auf: ${fmt(o.optDouble("topup_total",0.0))} | Zur: ${fmt(o.optDouble("return_total",0.0))} | Netto: ${fmt(o.optDouble("net_total",0.0))}"
                                setPadding(dp(0), dp(6), dp(0), dp(6))
                            })
                        }
                    }
                }
            }
        }

        btnRefresh.setOnClickListener { refreshAll() }
        btnReset.setOnClickListener {
            val input = EditText(this).apply { hint = "Event-Name (Pflicht)" }
            AlertDialog.Builder(this)
                .setTitle("Snapshot anlegen & Zähler zurücksetzen")
                .setView(input)
                .setPositiveButton("Speichern") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Bitte Namen eingeben", Toast.LENGTH_SHORT).show()
                    } else {
                        postReset(name) { ok ->
                            runOnUiThread {
                                if (ok) {
                                    Toast.makeText(this, "Snapshot gespeichert", Toast.LENGTH_SHORT).show()
                                    refreshAll()
                                } else {
                                    Toast.makeText(this, "Reset fehlgeschlagen", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        // Initial laden
        refreshAll()
    }

    // --- /admin/summary ---
    private fun loadSummary(onDone: (since: String, topup: Double, ret: Double, net: Double) -> Unit) {
        val req = Request.Builder().url("$apiBaseUrl/admin/summary").get().build()
        client.newCall(req).enqueue(object: Callback{
            override fun onFailure(call: Call, e: IOException) { onDone("-", 0.0, 0.0, 0.0) }
            override fun onResponse(call: Call, resp: Response) {
                resp.use {
                    val j = JSONObject(it.body?.string() ?: "{}")
                    onDone(
                        j.optString("since", "-"),
                        j.optDouble("topup_total", 0.0),
                        j.optDouble("return_total", 0.0),
                        j.optDouble("net_total", 0.0)
                    )
                }
            }
        })
    }

    // --- /admin/history ---
    private fun loadHistory(onDone: (JSONArray) -> Unit) {
        val req = Request.Builder().url("$apiBaseUrl/admin/history?limit=100").get().build()
        client.newCall(req).enqueue(object: Callback{
            override fun onFailure(call: Call, e: IOException) { onDone(JSONArray()) }
            override fun onResponse(call: Call, resp: Response) {
                resp.use {
                    val arr = JSONArray(it.body?.string() ?: "[]")
                    onDone(arr)
                }
            }
        })
    }

    // --- /admin/reset ---
    private fun postReset(name: String, onDone: (Boolean) -> Unit) {
        val body = JSONObject(mapOf("name" to name)).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$apiBaseUrl/admin/reset").post(body).build()
        client.newCall(req).enqueue(object: Callback{
            override fun onFailure(call: Call, e: IOException) { onDone(false) }
            override fun onResponse(call: Call, resp: Response) { onDone(resp.isSuccessful) }
        })
    }

    // --- kleine Format-Hilfe ---
    private fun fmt(v: Double) = String.format("%.2f", v)

    private fun renderAdminProducts(container: FrameLayout) {
        container.removeAllViews()
        val view = layoutInflater.inflate(R.layout.view_admin_products, container, false)
        container.addView(view)

        val btnNew = view.findViewById<Button>(R.id.btnNewProduct)
        val listLayout = view.findViewById<LinearLayout>(R.id.adminProductList)

        btnNew.setOnClickListener { promptNewProduct { renderAdminProducts(container) } }

        listLayout.removeAllViews()
        val loading = TextView(this).apply { text = "Lade Produkte..." }
        listLayout.addView(loading)

        client.newCall(Request.Builder().url("$apiBaseUrl/products").get().build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fehler beim Laden", Toast.LENGTH_SHORT).show()
                }
                override fun onResponse(call: Call, response: Response) {
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    runOnUiThread {
                        listLayout.removeAllViews()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val id = o.getInt("id")
                            val n  = o.getString("name")
                            val p  = o.getDouble("price")
                            val dr = o.getBoolean("in_drinks")
                            val fd = o.getBoolean("in_food")
                            val wn = o.getBoolean("in_wine")

                            val row = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(dp(6), dp(6), dp(6), dp(6))
                            }
                            val info = TextView(this@MainActivity).apply {
                                text = "$n – %.2f Chips\nDr:$dr  Es:$fd  Wn:$wn".format(p)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val edit = Button(this@MainActivity).apply {
                                text = "✏️"
                                setOnClickListener {
                                    promptEditProduct(id, n, p, dr, fd, wn) {
                                        renderAdminProducts(container)
                                    }
                                }
                            }
                            val del = Button(this@MainActivity).apply {
                                text = "🗑"
                                setOnClickListener {
                                    client.newCall(
                                        Request.Builder().url("$apiBaseUrl/products/$id").delete().build()
                                    ).enqueue(object : Callback {
                                        override fun onFailure(c: Call, e: IOException) = runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Fehler", Toast.LENGTH_SHORT).show()
                                        }
                                        override fun onResponse(c: Call, r: Response) = runOnUiThread {
                                            renderAdminProducts(container)
                                        }
                                    })
                                }
                            }
                            row.addView(info)
                            row.addView(edit)
                            row.addView(del)
                            listLayout.addView(row)
                        }
                    }
                }
            })
    }

    private fun renderAdminUsers(container: FrameLayout) {
        container.removeAllViews()
        val view = layoutInflater.inflate(R.layout.view_admin_users, container, false)
        container.addView(view)

        val list = view.findViewById<LinearLayout>(R.id.adminUsersList)
        val btnDeleteAll = view.findViewById<Button>(R.id.btnDeleteAllUsers)

        // "Alle Nutzer löschen" jetzt hier
        btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Achtung!")
                .setMessage("Alle Benutzer löschen?")
                .setPositiveButton("Ja") { _, _ ->
                    client.newCall(Request.Builder().url("$apiBaseUrl/reset").delete().build())
                        .enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                                Toast.makeText(this@MainActivity, "Fehler", Toast.LENGTH_SHORT).show()
                            }
                            override fun onResponse(call: Call, response: Response) = runOnUiThread {
                                Toast.makeText(this@MainActivity, "Nutzer gelöscht", Toast.LENGTH_SHORT).show()
                                renderAdminUsers(container)
                            }
                        })
                }
                .setNegativeButton("Nein", null)
                .show()
        }

        list.removeAllViews()
        client.newCall(Request.Builder().url("$apiBaseUrl/users?nonzero=1").get().build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fehler beim Laden", Toast.LENGTH_SHORT).show()
                }
                override fun onResponse(call: Call, response: Response) {
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    runOnUiThread {
                        list.removeAllViews()
                        if (arr.length() == 0) {
                            list.addView(TextView(this@MainActivity).apply { text = "Keine Nutzer" })
                            return@runOnUiThread
                        }
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val row = layoutInflater.inflate(R.layout.item_user_row, list, false)
                            val lbl = row.findViewById<TextView>(R.id.txtUserInfo)
                            lbl.text = "${o.getString("uid")} → ${"%.2f".format(o.getDouble("balance"))} Chips"
                            list.addView(row)
                        }
                    }
                }
            })
    }

    // ------------------------------------------------------------
    // DIALOGE / HILFSFUNKTIONEN
    // ------------------------------------------------------------
    private fun promptPin(correctPin: String, onSuccess: () -> Unit) {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("PIN eingeben")
            .setView(pinInput)
            .setPositiveButton("OK") { _, _ ->
                if (pinInput.text.toString() == correctPin) onSuccess()
                else Toast.makeText(this, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptManualUID(onSet: (String) -> Unit) {
        val inp = EditText(this).apply {
            hint = "UID (Hex, z.B. 04A1...)"
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("UID manuell eingeben")
            .setView(inp)
            .setPositiveButton("OK") { _, _ ->
                val uid = inp.text.toString().trim().uppercase()
                if (uid.isNotBlank()) onSet(uid) else Toast.makeText(this, "Ungültig", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------
    // NETZWERK / POS-SESSION
    // ------------------------------------------------------------

    /** Kleiner Hinweis-Block statt Produktliste, bis ein Band gescannt wurde. */
    private fun showScanPrompt(container: LinearLayout) {
        container.removeAllViews()
        val hint = TextView(this).apply {
            text = "Bitte Armband scannen – dann werden die Produkte angezeigt."
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container.addView(hint)
    }

    /**
     * Öffnet nach NFC-Scan eine POS-Session (2 Minuten) und speichert scanSessionId.
     * Produkte werden erst dann angezeigt.
     */
    private fun openSessionAfterScan(uid: String, onReady: (() -> Unit)? = null) {
        val payload = JSONObject().apply {
            put("uid", uid)
            put("pos_id", "POS-ANDROID-1")
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$apiBaseUrl/pos/scan")
            .post(payload)
            .build()

        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                Toast.makeText(this@MainActivity, "Scan fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
            override fun onResponse(call: Call, resp: Response) {
                val text = resp.body?.string() ?: "{}"
                val json = JSONObject(text)
                if (!resp.isSuccessful) {
                    runOnUiThread { Toast.makeText(this@MainActivity, json.optString("error","Scan fehlgeschlagen"), Toast.LENGTH_SHORT).show() }
                    return
                }
                scanSessionId = json.optString("scanSessionId", null)
                // lokaler TTL-Timer (zusätzlich zur Server-Validierung) – nur fürs UI
                resetLocalSessionTimer()
                // Direkt Produkte für Theke laden, danach onReady triggern
                runOnUiThread {
                    loadThekeProducts { onReady?.invoke() }
                }
            }
        })
    }

    private fun resetLocalSessionTimer() {
        scanSessionTimer?.cancel()
        scanSessionTimer = Timer().apply {
            schedule(object: TimerTask() {
                override fun run() {
                    runOnUiThread {
                        clearLocalScanSession()
                        // Produkte verschwinden automatisch, weil loadThekeProducts nur mit Session lädt
                    }
                }
            }, SESSION_TTL_MS)
        }
    }

    private fun clearLocalScanSession() {
        scanSessionTimer?.cancel()
        scanSessionTimer = null
        scanSessionId = null
    }

    /** Produkte für die Theke nur laden, wenn eine gültige Session existiert. */
    private fun loadThekeProducts(onDone: () -> Unit) {
        val sid = scanSessionId
        if (sid == null) {
            onDone()
            return
        }
        val req = Request.Builder()
            .url("$apiBaseUrl/pos/products")
            .get()
            .addHeader("X-Scan-Session", sid)
            .build()

        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                Toast.makeText(this@MainActivity, "Produkte laden fehlgeschlagen", Toast.LENGTH_SHORT).show()
                onDone()
            }
            override fun onResponse(call: Call, resp: Response) {
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Bitte Band scannen", Toast.LENGTH_SHORT).show()
                        clearLocalScanSession()
                        onDone()
                    }
                    return
                }
                val arr = JSONObject(text).optJSONArray("products") ?: JSONArray()
                productsCache = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Product(
                        o.getInt("id"),
                        o.getString("name"),
                        o.getDouble("price"),
                        o.getBoolean("in_drinks"),
                        o.getBoolean("in_food"),
                        o.getBoolean("in_wine")
                    )
                }
                runOnUiThread { onDone() }
            }
        })
    }

    /** Theken-Abbruch: Session invalidieren, UI räumen. */
    private fun posCancel(onCleared: () -> Unit) {
        val sid = scanSessionId
        if (sid == null) {
            onCleared()
            return
        }
        val req = Request.Builder()
            .url("$apiBaseUrl/pos/cancel")
            .post(ByteArray(0).toRequestBody(null))
            .addHeader("X-Scan-Session", sid)
            .build()
        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                // zur Sicherheit trotzdem lokal leeren
                clearLocalScanSession()
                onCleared()
            }
            override fun onResponse(call: Call, resp: Response) = runOnUiThread {
                clearLocalScanSession()
                onCleared()
            }
        })
    }

    /** Bezahlen in der Theke: /charge mit X-Scan-Session; Server verbraucht Session. */
    private fun postChargeWithSession(total: Double, items: JSONArray, onDone: (Boolean, JSONObject?) -> Unit) {
        val sid = scanSessionId
        thekeBuildProducts = null  // während Zahlung UI-Neuaufbau blocken (optional)
        val uid = currentUID
        if (sid == null || uid == null) {
            Toast.makeText(this, "Bitte Band scannen", Toast.LENGTH_SHORT).show()
            onDone(false, null)
            return
        }
        val body = JSONObject().apply {
            put("amount", total)
            put("items", items)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$apiBaseUrl/charge")
            .post(body)
            .addHeader("X-Scan-Session", sid)
            .build()

        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                Toast.makeText(this@MainActivity, "Bezahlung fehlgeschlagen", Toast.LENGTH_SHORT).show()
                onDone(false, null)
            }
            override fun onResponse(call: Call, resp: Response) {
                val text = resp.body?.string() ?: "{}"
                val json = JSONObject(text)
                runOnUiThread {
                    if (resp.isSuccessful) {
                        // Server hat Session verbraucht -> lokal ebenfalls löschen
                        clearLocalScanSession()
                        // Balance aktualisieren
                        checkBalance(uid) { thekeRefreshTotals?.invoke() }
                        onDone(true, json.optJSONObject("order"))
                    } else {
                        Toast.makeText(this@MainActivity, json.optString("error","Bezahlung fehlgeschlagen"), Toast.LENGTH_SHORT).show()
                        onDone(false, null)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------
    // Bisherige Netzwerkroutinen (Admin/Kasse)
    // ------------------------------------------------------------

    /** Admin/Katalog: vollständige Produktliste (ohne Scan-Gate) */
    private fun loadProducts(onDone: () -> Unit) {
        client.newCall(Request.Builder().url("$apiBaseUrl/products").get().build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fehler beim Laden der Produkte", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                override fun onResponse(call: Call, response: Response) {
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    productsCache = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Product(
                            o.getInt("id"),
                            o.getString("name"),
                            o.getDouble("price"),
                            o.getBoolean("in_drinks"),
                            o.getBoolean("in_food"),
                            o.getBoolean("in_wine")
                        )
                    }
                    runOnUiThread { onDone() }
                }
            })
    }

    private fun checkBalance(uid: String, onUpdated: (() -> Unit)? = null) {
        client.newCall(Request.Builder().url("$apiBaseUrl/balance/$uid").build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    currentBalance = 0.0
                    updateStatusCards(error = true)
                    onUpdated?.invoke()
                    Toast.makeText(this@MainActivity, "Verbindung fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
                override fun onResponse(call: Call, response: Response) {
                    val bal = try {
                        JSONObject(response.body?.string() ?: "{}").optDouble("balance", 0.0)
                    } catch (_: Exception) { 0.0 }
                    runOnUiThread {
                        currentBalance = bal
                        updateStatusCards()
                        onUpdated?.invoke()
                    }
                }
            })
    }

    /** Kasse: load/refund/charge ohne Scan-Session (bleibt wie gehabt, aber Kasse ist scan-first via UI) */
    private fun sendTransaction(type: String, amount: Double, onDone: (() -> Unit)? = null) {
        val uid = currentUID ?: run {
            Toast.makeText(this, "Bitte erst scannen", Toast.LENGTH_SHORT).show()
            return
        }
        val json = JSONObject().apply {
            put("uid", uid)
            if (type != "refund") put("amount", amount)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$apiBaseUrl/$type").post(body).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                    Toast.makeText(this@MainActivity, "Transaktionsfehler", Toast.LENGTH_SHORT).show()
                }
                override fun onResponse(call: Call, response: Response) = runOnUiThread {
                    checkBalance(uid) { onDone?.invoke() }
                }
            })
    }

    // ------------------------------------------------------------
    // ADMIN: PRODUKTE CRUD
    // ------------------------------------------------------------
    private fun promptNewProduct(onSaved: () -> Unit) {
        val nameInp = EditText(this).apply { hint = "Name" }
        val priceInp = EditText(this).apply {
            hint = "Preis"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val drinksCb = CheckBox(this).apply { text = "Getränke" }
        val foodCb = CheckBox(this).apply { text = "Essen" }
        val wineCb = CheckBox(this).apply { text = "Wein" }

        val dlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(nameInp); addView(priceInp); addView(drinksCb); addView(foodCb); addView(wineCb)
        }

        AlertDialog.Builder(this)
            .setTitle("Neues Produkt")
            .setView(dlg)
            .setPositiveButton("Speichern") { _, _ ->
                val json = JSONObject().apply {
                    put("name", nameInp.text.toString())
                    put("price", priceInp.text.toString().toDoubleOrNull() ?: 0.0)
                    put("in_drinks", drinksCb.isChecked)
                    put("in_food", foodCb.isChecked)
                    put("in_wine", wineCb.isChecked)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url("$apiBaseUrl/products").post(body).build())
                    .enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) = runOnUiThread {
                            Toast.makeText(this@MainActivity, "Fehler", Toast.LENGTH_SHORT).show()
                        }
                        override fun onResponse(call: Call, response: Response) = runOnUiThread { onSaved() }
                    })
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun promptEditProduct(
        id: Int,
        name: String,
        price: Double,
        dr: Boolean,
        fd: Boolean,
        wn: Boolean,
        onSaved: () -> Unit
    ) {
        val nameInp = EditText(this).apply { setText(name) }
        val priceInp = EditText(this).apply {
            setText(price.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val drinksCb = CheckBox(this).apply { text = "Getränke"; isChecked = dr }
        val foodCb = CheckBox(this).apply { text = "Essen"; isChecked = fd }
        val wineCb = CheckBox(this).apply { text = "Wein"; isChecked = wn }

        val dlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(nameInp); addView(priceInp); addView(drinksCb); addView(foodCb); addView(wineCb)
        }

        AlertDialog.Builder(this)
            .setTitle("Produkt bearbeiten")
            .setView(dlg)
            .setPositiveButton("Speichern") { _, _ ->
                val json = JSONObject().apply {
                    put("name", nameInp.text.toString())
                    put("price", priceInp.text.toString().toDoubleOrNull() ?: price)
                    put("in_drinks", drinksCb.isChecked)
                    put("in_food", foodCb.isChecked)
                    put("in_wine", wineCb.isChecked)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                client.newCall(Request.Builder().url("$apiBaseUrl/products/$id").put(body).build())
                    .enqueue(object : Callback {
                        override fun onFailure(c: Call, e: IOException) = runOnUiThread {
                            Toast.makeText(this@MainActivity, "Fehler", Toast.LENGTH_SHORT).show()
                        }
                        override fun onResponse(c: Call, r: Response) = runOnUiThread { onSaved() }
                    })
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------
    // STATUS-UI
    // ------------------------------------------------------------
    private fun updateStatusCards(error: Boolean = false) {
        // Home-Statuskarte
        (mainContent.findViewById<TextView?>(R.id.statusCard))?.let { tv ->
            tv.text = if (error) "Fehler beim Abrufen"
            else "Letzte UID: ${currentUID ?: "-"}\nChips: %.2f".format(currentBalance)
        }
        // Kasse-Statuskarte
        (mainContent.findViewById<TextView?>(R.id.kasseStatusCard))?.let { tv ->
            tv.text = if (error) "Fehler beim Abrufen"
            else "Letzte UID: ${currentUID ?: "-"}\nChips: %.2f".format(currentBalance)
        }
    }

    // ------------------------------------------------------------
    // CHAT (optional)
    // ------------------------------------------------------------
    private fun openChat() {
        try {
            startActivity(Intent(this, Class.forName("com.mockschan.cashless.ChatActivity")))
        } catch (_: Exception) {
            Toast.makeText(this, "Chat noch nicht verfügbar", Toast.LENGTH_SHORT).show()
        }
    }
}

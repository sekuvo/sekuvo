package com.afgover.vault.ime

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.afgover.vault.R
import com.afgover.vault.VaultApp
import com.afgover.vault.core.VaultSession
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.UsageEvent
import com.afgover.vault.data.UsageKind
import com.afgover.vault.data.EntrySort
import com.afgover.vault.data.EntryType
import com.afgover.vault.data.sortedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.afgover.vault.core.AppLocale
import android.content.Context

/**
 * Vault Klavyesi: herhangi bir uygulamada kayıtlı kullanıcı adı, şifre, kart
 * bilgisi vb. alanları doğrudan odaklanılan metin kutusuna yazar.
 *
 * Şifre değerleri ekranda gösterilmez; yalnızca alan etiketleri listelenir.
 *
 * Kasa kilitliyken listede **yalnızca "hızlı erişim" işaretli kayıtlar** çıkar;
 * bunlar Keystore'daki ayrı anahtarla çözülür, ana parola gerekmez. Geri kalan
 * her şey için 🔓 ile kilit açılır (parola/parmak izi) — kilit açık kaldığı
 * sürece (ekran kapanana kadar) tekrar sorulmaz.
 *
 * Kayıt sayısı arttığında listeyi taramak yerine son kullanılanlar en üstte
 * durur; 🔍 ile de arama yapılır. Klavye kendi metin kutusuna yazamadığı için
 * arama harfleri klavyenin kendi tuş ızgarasından gelir.
 */
class VaultImeService : InputMethodService() {

    // Seçili uygulama dili cihaz dilinden bağımsızdır; her Context açılışında
    // yapılandırmaya bindirilir (AppLocale).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * `attachBaseContext` yalnız BİR KEZ çalışır; servis ayakta kalırken
     * kullanıcı dili değiştirirse klavye eski dilde kalırdı (denetim).
     * Metinler her seferinde sarılmış context'ten okunur ve dil değişmişse
     * girdi görünümü yeniden kurulur.
     */
    private fun yerel(): Context = AppLocale.wrap(baseContext)

    private var uygulananDil: String? = null

    /**
     * Görünüm kurulurken geçerli olan gece/gündüz kipi. Renkler artık temayı
     * izliyor (values-night); kip servis ayaktayken değişirse kurulu görünüm
     * eski paletle kalırdı — dil değişimindeki bayatlama sorununun aynısı.
     */
    private var uygulananGece: Int? = null

    private fun geceKipi(): Int =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var searchLabel: TextView
    private lateinit var keyGrid: LinearLayout
    private var searching: Boolean = false
    private var query: String = ""
    private lateinit var scroll: ScrollView




    private var entries: List<DecryptedEntry> = emptyList()
    private var locked: Boolean = true
    private var selected: DecryptedEntry? = null

    /**
     * "Gündelik" süzgeci: yalnız [EntryType.EVERYDAY] kayıtları göster.
     *
     * Klavyede en sık aranan şey ad, telefon, e-posta, adres — yani formların
     * istediği gündelik bilgiler; parolalar zaten otomatik doldurmadan geliyor.
     * Süzgeç klavye kapanınca sıfırlanmaz, çünkü bir formu doldururken klavye
     * birkaç kez açılıp kapanıyor ve seçimi her seferinde yeniden yapmak
     * özelliğin kendisini götürürdü.
     */
    private var everydayOnly: Boolean = false
    private var everydayButton: Button? = null


    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    override fun onCreateInputView(): View {
        uygulananDil = AppLocale.currentTag(this)
        uygulananGece = geceKipi()
        // Klavye penceresi kasa içeriğini gösterebiliyor; ekran görüntüsü ve
        // ekran kaydına kapat (uygulama ekranlarındaki FLAG_SECURE ile aynı).
        window?.window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.ime_background))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Üst çubuk
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "🔐 Sekuvo"
            setTextColor(color(R.color.ime_accent))
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Gündelik süzgeci Sekuvo adının hemen yanında: listeyi daraltan tek
        // düğme, aramadan önce gelir çünkü çoğu durumda aramanın yerine geçer.
        everydayButton = flatButton(yerel().getString(R.string.type_everyday)) {
            everydayOnly = !everydayOnly
            selected = null
            applyEverydayLook()
            render()
        }
        header.addView(everydayButton)
        applyEverydayLook()
        header.addView(flatButton("🔍") { toggleSearch() })
        header.addView(flatButton(yerel().getString(R.string.ime_switch_keyboard)) { switchBackToKeyboard() })
        // Basılı tutunca sistem klavyesindeki gibi silmeye devam eder.
        header.addView(repeatingButton("⌫") {
            currentInputConnection?.deleteSurroundingText(1, 0)
            haptic()
        })
        header.addView(flatButton("⌄") { requestHideSelf(0) })
        root.addView(header)

        // Arama satırı (yalnızca arama açıkken görünür)
        searchLabel = TextView(this).apply {
            setTextColor(color(R.color.ime_text))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(color(R.color.ime_surface))
            setPadding(dp(12), dp(6), dp(6), dp(6))
            addView(searchLabel)
            addView(repeatingButton("⌫") { backspaceQuery(); haptic() })
            addView(flatButton("✕") { toggleSearch() })
        }
        root.addView(searchRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(4)) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
            addView(content)
        }
        root.addView(scroll)

        keyGrid = buildKeyGrid()
        root.addView(keyGrid)

        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Dil değiştiyse görünümdeki metinler, tema değiştiyse renkler
        // bayattır: baştan kur.
        val simdikiDil = AppLocale.currentTag(this)
        val simdikiGece = geceKipi()
        if ((uygulananDil != null && uygulananDil != simdikiDil) ||
            (uygulananGece != null && uygulananGece != simdikiGece)
        ) {
            setInputView(onCreateInputView())
        }
        uygulananDil = simdikiDil
        uygulananGece = simdikiGece
        selected = null
        searching = false
        query = ""
        applySearchVisibility()
        refresh()
    }

    /**
     * Her gösterimde kilit durumu YENİDEN okunur ve liste tazelenir. Tek yönlü
     * (yalnız kilitli→açık) tazeleme, kasa açıkken ekran kapanıp klavye tekrar
     * açıldığında eski çözülmüş kayıtları ekranda bırakıyordu (denetim).
     */
    override fun onWindowShown() {
        super.onWindowShown()
        refresh()
    }

    /** Pencere gizlenince çözülmüş kayıtları bellekte tutma. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        selected = null
        entries = emptyList()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Kilitliyken yalnızca hızlı erişim kopyaları okunur; ana blob'a
     * dokunulmaz, yani korumalı kayıtlar kilitliyken çözülemez.
     */
    private fun refresh() {
        val key = VaultSession.key()
        locked = key == null
        scope.launch {
            val repo = (application as VaultApp).repository
            entries = withContext(Dispatchers.IO) {
                (if (key == null) repo.getQuickDecrypted() else repo.getVisibleDecrypted(key))
                    // Klavye HER ZAMAN öncelik numarasına göre dizer, uygulama
                    // listesinin sırasına bakmaksızın (kullanıcı kararı):
                    // burada aranan şey "hangi kayda en sık uzanıyorum",
                    // ve cevabı kullanıcının verdiği numara veriyor. Süzgeç
                    // açıkken de kapalıyken de aynı kural işler.
                    .sortedBy(EntrySort.MANUAL)
            }
            render()
        }
    }

    private fun render() {
        // Kayıt seçiliyken arama satırı ve tuşlar gizlenir
        applySearchVisibility()
        content.removeAllViews()
        val current = selected
        if (current != null) {
            renderFields(current)
            return
        }

        if (locked) {
            content.addView(hint(yerel().getString(R.string.ime_locked_quick_only)))
            content.addView(actionButton(yerel().getString(R.string.ime_unlock_all)) { startUnlock() })
        }

        if (entries.isEmpty()) {
            content.addView(
                hint(
                    if (locked) yerel().getString(R.string.ime_no_quick_entries)
                    else yerel().getString(R.string.ime_no_entries)
                )
            )
            return
        }

        val matches = filtered()
        if (matches.isEmpty()) {
            // Arama boşken eşleşme yoksa sebep süzgeçtir; kullanıcıya boş bir
            // arama sonucu göstermek yanlış yere baktırırdı.
            content.addView(
                hint(
                    if (query.isEmpty()) yerel().getString(R.string.ime_no_everyday)
                    else yerel().getString(R.string.ime_no_match, query)
                )
            )
            return
        }
        matches.forEach { content.addView(entryButton(it)) }
    }

    private fun renderFields(entry: DecryptedEntry) {
        content.addView(actionButton(yerel().getString(R.string.ime_back_to_list, entry.title)) {
            selected = null
            render()
        })
        entry.data.fields().forEach { alan ->
            val gosterilen = alan.customLabel ?: getString(alan.key!!.labelRes)
            content.addView(actionButton(yerel().getString(R.string.ime_write_field, gosterilen)) {
                // commitText false dönerse (bağlantı yok) yazma olmamıştır;
                // günlüğe 'yazıldı' düşme (denetim).
                val yazildi = currentInputConnection?.commitText(alan.value, 1) == true
                // Günlüğe KARARLI ad yazılır, ekrandaki çeviri değil.
                if (yazildi) logUsage(entry.id, alan.stableName)
            })
        }
    }

    /**
     * Klavyeden yazma günlüğe düşer. Kasa kilitliyken (hızlı erişim kayıtları)
     * olay Keystore tamponuna yazılır ve ilk kilit açılışında günlüğe taşınır.
     */
    private fun logUsage(entryId: Long, label: String) {
        val target = currentInputEditorInfo?.packageName
        val app = VaultApp.from(this)
        scope.launch(Dispatchers.IO) {
            app.usageLog.record(
                UsageEvent(
                    entryId,
                    UsageKind.KLAVYE_YAZILDI,
                    System.currentTimeMillis(),
                    label,
                    target
                )
            )
        }
    }

    private fun entryButton(entry: DecryptedEntry): Button =
        actionButton(entry.title) {
            selected = entry
            render()
        }

    /**
     * Önce tür süzgeci (Gündelik), sonra arama. Sıra önemli: süzgeç açıkken
     * arama da yalnız gündelik kayıtlarda gezsin, kapatınca hepsinde.
     */
    private fun filtered(): List<DecryptedEntry> {
        val base = if (everydayOnly) entries.filter { it.type == EntryType.EVERYDAY } else entries
        if (query.isEmpty()) return base
        val needle = normalize(query)
        return base.filter { entry ->
            normalize(entry.title).contains(needle) ||
                normalize(entry.data.username).contains(needle) ||
                normalize(entry.data.url).contains(needle)
        }
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace("ı", "i").replace("İ", "i").replace("ş", "s").replace("ğ", "g")
        .replace("ü", "u").replace("ö", "o").replace("ç", "c")

    // --- Arama tuşları ---------------------------------------------------

    private fun toggleSearch() {
        searching = !searching
        query = ""
        applySearchVisibility()
        render()
    }

    private fun applySearchVisibility() {
        val visible = searching && selected == null
        searchRow.visibility = if (visible) View.VISIBLE else View.GONE
        keyGrid.visibility = if (visible) View.VISIBLE else View.GONE
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (visible) dp(120) else dp(220)
        )
        updateSearchLabel()
    }

    private fun updateSearchLabel() {
        searchLabel.text = query.ifEmpty { yerel().getString(R.string.ime_search_hint) }
        searchLabel.alpha = if (query.isEmpty()) 0.6f else 1f
    }

    private fun appendToQuery(ch: String) {
        query += ch
        updateSearchLabel()
        render()
    }

    private fun backspaceQuery() {
        if (query.isEmpty()) return
        query = query.dropLast(1)
        updateSearchLabel()
        render()
    }

    /**
     * Aramaya özel küçük tuş ızgarası. Türkçe harfler aramada zaten ASCII
     * karşılığına indirgendiği için düz QWERTY yeterli.
     */
    private fun buildKeyGrid(): LinearLayout {
        val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            rows.forEach { row ->
                addView(LinearLayout(this@VaultImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { ch ->
                        addView(keyButton(ch.toString()), LinearLayout.LayoutParams(
                            0, dp(38), 1f
                        ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    }
                })
            }
        }
    }

    private fun keyButton(ch: String): Button =
        Button(this).apply {
            text = ch
            isAllCaps = false
            textSize = 14f
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(color(R.color.ime_text))
            setBackgroundColor(color(R.color.ime_surface))
            setOnClickListener { appendToQuery(ch); haptic() }
        }
    // --- Ortak görünümler ------------------------------------------------

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.ime_text))
        setPadding(dp(8), dp(16), dp(8), dp(8))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.ime_accent))
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(8), dp(8), dp(8), dp(2))
    }

    /**
     * Süzgecin açık olduğu düğmenin KENDİSİNDE görünür: ayrı bir gösterge
     * satırı klavyenin zaten dar olan yüksekliğinden çalardı.
     */
    private fun applyEverydayLook() {
        everydayButton?.apply {
            setTextColor(color(if (everydayOnly) R.color.ime_accent else R.color.ime_text))
            setTypeface(null, if (everydayOnly) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(color(R.color.ime_text))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = dp(48)
            minimumWidth = dp(48)
            setOnClickListener { onClick() }
        }

    /**
     * Kısa dokunsal geri bildirim. Sistem klavyeleri gibi: silme her
     * karakterde hissedilir. Cihazın dokunsal geri bildirimi kapalıysa
     * sessizce hiçbir şey olmaz (kullanıcı ayarına saygı).
     */
    private fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        }
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    12L,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    /**
     * Basılı tutuldukça eylemi tekrarlayan düğme (silme tuşu). Sistem
     * klavyelerindeki davranış: 400 ms bekle, sonra 55 ms'de bir tekrarla.
     */
    private fun repeatingButton(label: String, onAction: () -> Unit): Button =
        flatButton(label) { onAction() }.apply {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var repeater: Runnable? = null
            var repeated = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        repeated = false
                        val r = object : Runnable {
                            override fun run() {
                                repeated = true
                                onAction()
                                handler.postDelayed(this, 55)
                            }
                        }
                        repeater = r
                        handler.postDelayed(r, 400)
                        // false: tekrar başlamazsa parmak kalkınca sistemin
                        // kendi tıklaması tek silmeyi yapar.
                        false
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        repeater?.let(handler::removeCallbacks)
                        repeater = null
                        // Tekrar çalıştıysa parmak kalkışını YUTARIZ: aksi
                        // hâlde sistem tıklaması bir fazla silerdi.
                        repeated
                    }
                    else -> false
                }
            }
        }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(color(R.color.ime_text))
            setBackgroundColor(color(R.color.ime_surface))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
            setOnClickListener { onClick() }
        }

    /**
     * Kilit açma ekranı ayrı bir aktivitede açılır; onaylandıktan sonra
     * kullanıcı yazdığı uygulamaya döner ve klavye tazelenir ([onWindowShown]).
     */
    private fun startUnlock() {
        startActivity(Intent(this, ImeUnlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun switchBackToKeyboard() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }
}

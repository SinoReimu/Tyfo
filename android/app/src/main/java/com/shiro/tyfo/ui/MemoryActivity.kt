package com.shiro.tyfo.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shiro.tyfo.R
import com.shiro.tyfo.db.GlobalMemoryEntity
import com.shiro.tyfo.db.GlobalMemoryRepository
import com.shiro.tyfo.ui.fx.MonetDialogs
import com.shiro.tyfo.ui.fx.MonetDialogs.showTinted
import com.shiro.tyfo.ui.fx.MonetPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 管理记忆（侧边栏进入），莫奈扁平风格同主界面。 */
class MemoryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: GlobalMemoryRepository
    private lateinit var adapter: MemoryAdapter
    private lateinit var searchInput: EditText
    private lateinit var palette: MonetPalette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_memory)
        palette = MonetPalette(this)

        repository = GlobalMemoryRepository(this)
        searchInput = findViewById(R.id.edit_search)
        applyPalette(palette)
        val recycler = findViewById<RecyclerView>(R.id.recycler_memories)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.memory_root)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, 0)
            recycler.setPadding(recycler.paddingLeft, 0, recycler.paddingRight, maxOf(bars.bottom, ime.bottom) + 16.dp())
            insets
        }
        adapter = MemoryAdapter(
            palette,
            onClick = { showEditDialog(it) },
            onLongClick = { showMemoryActions(it) }
        )

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_add_memory).setOnClickListener { showEditDialog(null) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadMemories()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadMemories()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyPalette(palette: MonetPalette) {
        findViewById<View>(R.id.memory_root).setBackgroundColor(palette.background)
        findViewById<TextView>(R.id.memory_title).setTextColor(palette.text)
        // 返回、新增：半透明圆形按钮，同主界面左上角
        for (id in listOf(R.id.btn_back, R.id.btn_add_memory)) {
            val button = findViewById<ImageView>(id)
            button.imageTintList = ColorStateList.valueOf(palette.text)
            button.background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.surfaceLite)
                    setStroke(maxOf(1, 1.dp() / 2), palette.hairlineStrong)
                },
                null,
            )
        }
        searchInput.setTextColor(palette.text)
        searchInput.setHintTextColor(palette.textSecondary)
        searchInput.background = GradientDrawable().apply {
            cornerRadius = 24.dp().toFloat()
            setColor(palette.ghost)
        }
        searchInput.textCursorDrawable = GradientDrawable().apply {
            setColor(palette.accent)
            setSize(maxOf(2, 2.dp()), 0)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun loadMemories() {
        val query = searchInput.text.toString()
        scope.launch {
            adapter.submitList(repository.search(query))
        }
    }

    private fun showEditDialog(memory: GlobalMemoryEntity?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val keyInput = dialogInput(getString(R.string.memory_dialog_key_hint)).apply {
            setText(memory?.key.orEmpty())
            isEnabled = memory == null
        }
        val valueInput = dialogInput(getString(R.string.memory_dialog_value_hint)).apply {
            setText(memory?.value.orEmpty())
            minLines = 2
        }
        val categoryInput = dialogInput(getString(R.string.memory_dialog_category_hint)).apply {
            setText(memory?.category.orEmpty())
        }
        container.addView(keyInput)
        container.addView(valueInput)
        container.addView(categoryInput)

        MonetDialogs.builder(this, palette)
            .setTitle(if (memory == null) getString(R.string.memory_dialog_add_title) else getString(R.string.memory_dialog_edit_title))
            .setView(container)
            .setPositiveButton(getString(R.string.memory_dialog_save)) { _, _ ->
                val key = keyInput.text.toString().trim()
                val value = valueInput.text.toString().trim()
                val category = categoryInput.text.toString().trim()
                if (key.isEmpty() || value.isEmpty()) return@setPositiveButton
                scope.launch {
                    if (memory == null) {
                        repository.upsert(key, value, category, GlobalMemoryEntity.SOURCE_USER)
                    } else {
                        repository.update(
                            memory.copy(
                                value = value,
                                category = category,
                                source = GlobalMemoryEntity.SOURCE_USER,
                                enabled = true
                            )
                        )
                    }
                    loadMemories()
                }
            }
            .setNegativeButton(getString(R.string.memory_dialog_cancel), null)
            .showTinted(palette)
    }

    private fun showMemoryActions(memory: GlobalMemoryEntity) {
        val labels = arrayOf(
            if (memory.enabled) getString(R.string.memory_action_disable) else getString(R.string.memory_action_enable),
            getString(R.string.memory_action_delete)
        )
        MonetDialogs.builder(this, palette)
            .setTitle(memory.key)
            .setItems(labels) { _, which ->
                scope.launch {
                    when (which) {
                        0 -> {
                            if (memory.enabled) {
                                repository.disable(memory.id)
                            } else {
                                repository.update(memory.copy(enabled = true))
                            }
                        }
                        1 -> repository.delete(memory.id)
                    }
                    loadMemories()
                }
            }
            .showTinted(palette)
    }

    private fun dialogInput(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            visibility = View.VISIBLE
        }
}

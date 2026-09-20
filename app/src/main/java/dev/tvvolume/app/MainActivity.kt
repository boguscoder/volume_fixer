package dev.tvvolume.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var rows: LinearLayout
    private lateinit var packageInput: EditText
    private lateinit var volumeInput: EditText
    private lateinit var tvIpInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val svc = Intent(this, TvVolumeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        TvVolumeClient.resetToDefault(this)

        if (!TvVolumeClient.hasUsageAccess(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !pm.isIgnoringBatteryOptimizations(packageName)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        TvVolumeClient.ensureSeeded(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "TV Volume"
            textSize = 24f
        })
        val tvRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvIpInput = EditText(this).apply {
            hint = "Samsung TV IP"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvIpInput.setText(TvVolumeClient.tvIp(this) ?: "")
        tvRow.addView(tvIpInput)
        tvRow.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveTvIp() }
        })
        root.addView(tvRow)
        val form = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        packageInput = EditText(this).apply {
            hint = "package name"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        volumeInput = EditText(this).apply {
            hint = "0-100"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        form.addView(packageInput)
        form.addView(volumeInput)
        form.addView(Button(this).apply {
            text = "Add"
            setOnClickListener { addMapping() }
        })
        root.addView(form)
        root.addView(Button(this).apply {
            text = "Use foreground app"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { fillForeground() }
        })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        return ScrollView(this).apply { addView(root) }
    }

    private fun saveTvIp() {
        val ip = tvIpInput.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter TV IP", Toast.LENGTH_SHORT).show()
            return
        }
        TvVolumeClient.setTvIp(this, ip)
        TvVolumeClient.warmup(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun addMapping() {
        val pkg = packageInput.text.toString().trim()
        val vol = volumeInput.text.toString().toIntOrNull()
        if (pkg.isEmpty() || vol == null || vol !in 0..100) {
            Toast.makeText(this, "Enter package and 0-100", Toast.LENGTH_SHORT).show()
            return
        }
        TvVolumeClient.setVolumeMapping(this, pkg, vol)
        packageInput.text.clear()
        volumeInput.text.clear()
        refreshRows()
    }

    private fun fillForeground() {        if (!TvVolumeClient.hasUsageAccess(this)) {
            Toast.makeText(this, "Grant Usage Access first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        val pkg = TvVolumeClient.foregroundPackage(this)
        if (pkg == null) {
            Toast.makeText(this, "No app detected", Toast.LENGTH_SHORT).show()
        } else {
            packageInput.setText(pkg)
        }
    }

    private fun refreshRows() {
        rows.removeAllViews()
        for ((pkg, vol) in TvVolumeClient.volumeMappings(this).toSortedMap()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = pkg
                textSize = 14f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(this).apply {
                text = vol.toString()
                textSize = 16f
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    (48 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
            }
            row.addView(SeekBar(this).apply {
                max = 100
                progress = vol
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        value.text = progress.toString()
                        if (fromUser) {
                            TvVolumeClient.setVolumeMapping(this@MainActivity, pkg, progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
            })
            row.addView(value)
            row.addView(Button(this).apply {
                text = "Del"
                setOnClickListener {
                    TvVolumeClient.removeVolumeMapping(this@MainActivity, pkg)
                    refreshRows()
                }
            })
            rows.addView(row)
        }
    }
}

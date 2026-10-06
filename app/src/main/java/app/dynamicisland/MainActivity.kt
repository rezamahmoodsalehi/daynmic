package app.dynamicisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dynamicisland.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var updating = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) {}
            }
            sendToService(IslandService.ACTION_PLAY_URIS) {
                putParcelableArrayListExtra(IslandService.EXTRA_URIS, ArrayList(uris))
            }
        }
    }
    private val askPost = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnAccess.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        b.btnPost.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        b.swEnable.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Prefs.setEnabled(this, true)
                    ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java))
                }
            } else {
                Prefs.setEnabled(this, false)
                stopService(Intent(this, IslandService::class.java))
            }
        }

        b.toggleStyle.check(if (Prefs.styleAuto(this)) R.id.btnStyleAuto else R.id.btnStyleDark)
        b.toggleStyle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) Prefs.setStyleAuto(this, id == R.id.btnStyleAuto)
        }

        b.toggleCam.check(
            when (Prefs.camOverride(this)) { 1 -> R.id.btnCamLeft; 2 -> R.id.btnCamCenter; 3 -> R.id.btnCamRight; 4 -> R.id.btnCamNone; else -> R.id.btnCamAuto }
        )
        b.toggleCam.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Prefs.setCamOverride(
                this,
                when (id) { R.id.btnCamLeft -> 1; R.id.btnCamCenter -> 2; R.id.btnCamRight -> 3; R.id.btnCamNone -> 4; else -> 0 }
            )
            refresh()
            if (Settings.canDrawOverlays(this) && Prefs.enabled(this)) sendToService(IslandService.ACTION_REFRESH)
        }
        b.btnTestLock.setOnClickListener {
            sendToService(IslandService.ACTION_TEST_MUSIC)
            Toast.makeText(this, R.string.lock_hint, Toast.LENGTH_LONG).show()
        }
        b.btnTestMessage.setOnClickListener { sendToService(IslandService.ACTION_TEST_MESSAGE) }
        b.btnTestMusic.setOnClickListener { sendToService(IslandService.ACTION_TEST_MUSIC) }
        b.btnTestOngoing.setOnClickListener { sendToService(IslandService.ACTION_TEST_ONGOING) }
        b.btnPickAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        b.btnStopAll.setOnClickListener { sendToService(IslandService.ACTION_CLEAR) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sendToService(action: String, extras: Intent.() -> Unit = {}) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setEnabled(this, true)
        val i = Intent(this, IslandService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(this, i)
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val post = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        status(b.tvOverlay, R.string.perm_overlay, overlay); b.btnOverlay.isEnabled = !overlay
        status(b.tvAccess, R.string.perm_access, access); b.btnAccess.isEnabled = !access
        status(b.tvPost, R.string.perm_post, post); b.btnPost.isEnabled = !post

        updating = true
        b.swEnable.isChecked = Prefs.enabled(this) && overlay
        updating = false

        val ci = CutoutDetector.detect(this)
        val cam = when (ci.pos) {
            CamPos.LEFT -> R.string.cam_left
            CamPos.CENTER -> R.string.cam_center
            CamPos.RIGHT -> R.string.cam_right
            CamPos.NONE -> R.string.cam_none
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        b.tvDevice.text = getString(
            R.string.device_info, getString(cam),
            getString(if (night) R.string.theme_dark else R.string.theme_light)
        )
    }

    private fun status(tv: TextView, label: Int, ok: Boolean) {
        tv.text = if (ok) "${getString(label)}\n${getString(R.string.granted)}" else getString(label)
    }
}

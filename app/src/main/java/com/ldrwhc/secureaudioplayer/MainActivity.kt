package com.ldrwhc.secureaudioplayer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.google.android.material.navigation.NavigationView
import com.ldrwhc.secureaudioplayer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CHANNEL_ID = "bus_player_channel"
        private const val NOTIFICATION_ID = 12086
        private const val BACK_EXIT_INTERVAL_MS = 1800L
    }

    private enum class PlaybackMode {
        SEQUENTIAL,
        LIST_LOOP,
        SINGLE_LOOP,
        SHUFFLE,
    }

    private enum class UiTheme(val id: String) {
        SYSTEM("system"),
        MINIMAL("minimal"),
        OCEAN("ocean"),
        FOREST("forest"),
        SUNSET("sunset"),
        GRAPHITE("graphite"),
    }

    private data class PreparedSegment(
        val item: MediaItem,
        val durationMs: Long,
    )

    private data class InitDialogHolder(
        val dialog: AlertDialog,
        val progressBar: ProgressBar,
        val textView: TextView,
    )

    private data class UiPalette(
        val bgColor: Int,
        val surfaceColor: Int,
        val textColor: Int,
        val statusColor: Int,
        val progressTextColor: Int,
        val listBgColor: Int,
        val listItemBgColor: Int,
        val listItemActiveBgColor: Int,
        val spinnerBgColor: Int,
        val neutralButton: Int,
        val primaryButton: Int,
        val primaryIcon: Int,
        val navBgColor: Int,
        val progressTint: Int,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private lateinit var engine: BusAudioEngine
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationProgressPlayer: NotificationProgressPlayer
    private var notificationManager: PlayerNotificationManager? = null

    private val prefs by lazy { getSharedPreferences("secure_audio_player", MODE_PRIVATE) }
    private var options = SynthesisOptions()
    private var playbackMode = PlaybackMode.SEQUENTIAL
    private var uiTheme = UiTheme.MINIMAL

    private var templates: List<TemplateConfig> = emptyList()
    private var lineFiles: List<File> = emptyList()
    private var tracks: List<TrackItem> = emptyList()
    private var currentTrackIndex = -1
    private var lastBackPressedAt = 0L
    private val durationCacheMs = hashMapOf<String, Long>()
    private var progressJob: Job? = null
    private var userSeekingProgress = false
    private var currentPalette: UiPalette? = null
    private var lastSynthesisUiUpdateMs = 0L
    private var initRunning = false
    private var hapticEnabled = true
    private val synthesisLogs = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = BusAudioEngine(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        loadOptions()
        initPlayer()
        initNotification()
        initUi()
        applyUiTheme(uiTheme)
        setupBackPressHandler()
        initializeResourcesAsync()
    }

    override fun onResume() {
        super.onResume()
        syncPlayerVolumeFromSystem()
        if (uiTheme == UiTheme.SYSTEM) applyUiTheme(uiTheme)
        startProgressTicker()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (uiTheme == UiTheme.SYSTEM) {
            applyUiTheme(uiTheme)
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressTicker()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressTicker()
        notificationManager?.setPlayer(null)
        notificationManager = null
        mediaSession.release()
        player.release()
        engine.clearSession()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        notificationProgressPlayer = NotificationProgressPlayer(player)
        notificationProgressPlayer.onSkipToNext = {
            runOnUiThread { moveTrack(1) }
        }
        notificationProgressPlayer.onSkipToPrevious = {
            runOnUiThread { moveTrack(-1) }
        }
        notificationProgressPlayer.canSkipNextProvider = {
            hasLogicalNextTrack()
        }
        notificationProgressPlayer.canSkipPreviousProvider = {
            hasLogicalPreviousTrack()
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                renderPlaybackProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                renderPlaybackProgress()
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }
        })
        syncPlayerVolumeFromSystem()
        updatePlayPauseIcon(false)
    }

    private fun initNotification() {
        ensureNotificationChannel()
        requestNotificationPermissionIfNeeded()

        mediaSession = MediaSession.Builder(this, notificationProgressPlayer).build()
        val descriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence {
                val line = currentLineName()
                return if (line.isNotBlank()) {
                    getString(R.string.notify_line_title, line)
                } else {
                    getString(R.string.app_name)
                }
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                return PendingIntent.getActivity(
                    this@MainActivity,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            override fun getCurrentContentText(player: Player): CharSequence {
                return tracks.getOrNull(currentTrackIndex)?.title ?: ""
            }

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback
            ) = null
        }

        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(descriptionAdapter)
            .build()
            .apply {
                setUseRewindAction(false)
                setUseFastForwardAction(false)
                setUseNextAction(true)
                setUsePreviousAction(true)
                setUseNextActionInCompactView(true)
                setUsePreviousActionInCompactView(true)
                setUseStopAction(false)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setPlayer(notificationProgressPlayer)
                setMediaSessionToken(mediaSession.sessionCompatToken)
            }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notify_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
        }
    }

    private fun initUi() {
        binding.trackList.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        binding.menuButton.setOnClickListener {
            pulse(binding.menuButton)
            binding.drawerRoot.openDrawer(GravityCompat.START)
        }
        binding.optionsButton.setOnClickListener {
            pulse(binding.optionsButton)
            showOptionsDialog()
        }
        binding.loadButton.setOnClickListener {
            pulse(binding.loadButton)
            loadSelectedConfigAndLine()
        }
        binding.trackList.setOnItemClickListener { _, view, position, _ ->
            pulse(view)
            playTrack(position)
        }
        binding.playModeButton.setOnClickListener {
            pulse(binding.playModeButton)
            playbackMode = when (playbackMode) {
                PlaybackMode.SEQUENTIAL -> PlaybackMode.LIST_LOOP
                PlaybackMode.LIST_LOOP -> PlaybackMode.SINGLE_LOOP
                PlaybackMode.SINGLE_LOOP -> PlaybackMode.SHUFFLE
                PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENTIAL
            }
            savePlaybackMode()
            updatePlaybackModeIcon()
            Toast.makeText(this, modeText(playbackMode), Toast.LENGTH_SHORT).show()
            setStatus(getString(R.string.status_mode_changed, modeText(playbackMode)))
        }
        binding.prevButton.setOnClickListener {
            pulse(binding.prevButton)
            moveTrack(-1)
        }
        binding.nextButton.setOnClickListener {
            pulse(binding.nextButton)
            moveTrack(1)
        }
        binding.playPauseButton.setOnClickListener {
            pulse(binding.playPauseButton)
            if (player.isPlaying) {
                player.pause()
            } else if (player.mediaItemCount > 0) {
                player.play()
            } else if (tracks.isNotEmpty()) {
                playTrack(if (currentTrackIndex >= 0) currentTrackIndex else 0)
            }
        }
        binding.volumeButton.setOnClickListener {
            pulse(binding.volumeButton)
            showVolumeDialog()
        }
        binding.configDropdown.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            pulse(view)
        }
        binding.lineDropdown.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            pulse(view)
        }
        updatePlaybackModeIcon()
        setupDrawerNavigation()
        setupPlaybackProgressBar()
    }

    private fun setupPlaybackProgressBar() {
        binding.playProgressBar.max = 1000
        binding.playProgressBar.progress = 0
        binding.playProgressText.text = getString(R.string.progress_time_text, "00:00", "00:00")
        binding.playProgressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val total = notificationProgressPlayer.getTotalDurationMs().coerceAtLeast(0L)
                if (total <= 0L) return
                val target = total * progress / binding.playProgressBar.max
                binding.playProgressText.text = getString(
                    R.string.progress_time_text,
                    formatDuration(target),
                    formatDuration(total)
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeekingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val total = notificationProgressPlayer.getTotalDurationMs().coerceAtLeast(0L)
                if (total > 0L) {
                    val progress = binding.playProgressBar.progress
                    val target = total * progress / binding.playProgressBar.max
                    notificationProgressPlayer.seekToAggregated(target)
                }
                userSeekingProgress = false
            }
        })
    }

    private fun setupDrawerNavigation() {
        updateHapticMenuState()
        binding.navView.setNavigationItemSelectedListener(NavigationView.OnNavigationItemSelectedListener { item ->
            pulse(binding.navView)
            when (item.itemId) {
                R.id.nav_theme -> showThemeDialog()
                R.id.nav_tutorial -> startTutorialSequence(markAsShown = false)
                R.id.nav_haptic -> {
                    hapticEnabled = !hapticEnabled
                    saveHapticEnabled()
                    updateHapticMenuState()
                    setStatus(
                        if (hapticEnabled) getString(R.string.status_haptic_on)
                        else getString(R.string.status_haptic_off)
                    )
                }
                R.id.nav_logs -> showLogsDialog()
                R.id.nav_about -> showAboutDialog()
                else -> return@OnNavigationItemSelectedListener false
            }
            binding.drawerRoot.closeDrawer(GravityCompat.START)
            true
        })
    }

    private fun updateHapticMenuState() {
        val item = binding.navView.menu.findItem(R.id.nav_haptic) ?: return
        item.isChecked = hapticEnabled
        item.title = if (hapticEnabled) getString(R.string.drawer_haptic_on) else getString(R.string.drawer_haptic_off)
    }

    private fun initializeResourcesAsync() {
        if (initRunning) return
        initRunning = true

        val quickReady = engine.hasRuntimeReady()
        if (quickReady) {
            reloadInputs()
            setStatus(getString(R.string.status_resource_ready))
            initRunning = false
            showOnboardingIfNeeded()
            return
        }

        val holder = showInitDialog()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                updateInitDialog(holder, 12, getString(R.string.init_step_unpack), true)
                engine.bootstrapPayloadFromAssets()

                updateInitDialog(holder, 70, getString(R.string.init_step_load_ui), false)
                withContext(Dispatchers.Main) { reloadInputs() }

                updateInitDialog(holder, 100, getString(R.string.init_done), false)
                delay(220L)
                withContext(Dispatchers.Main) {
                    runCatching { holder.dialog.dismiss() }
                    setStatus(getString(R.string.status_resource_ready))
                    showOnboardingIfNeeded()
                }
            } finally {
                initRunning = false
            }
        }
    }

    private suspend fun updateInitDialog(
        holder: InitDialogHolder,
        progress: Int,
        message: String,
        indeterminate: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            holder.progressBar.isIndeterminate = indeterminate
            if (!indeterminate) holder.progressBar.progress = progress.coerceIn(0, 100)
            holder.textView.text = message
        }
    }

    private fun showInitDialog(): InitDialogHolder {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 12)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val message = TextView(this).apply {
            text = getString(R.string.init_starting)
            textSize = 14f
            setTextColor(Color.parseColor("#1F1F1F"))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18 }
        }
        root.addView(message)
        root.addView(progress)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.init_title))
            .setView(root)
            .setCancelable(false)
            .create()
        dialog.show()
        return InitDialogHolder(dialog, progress, message)
    }

    private fun reloadInputs() {
        templates = engine.loadTemplateConfigs()
        lineFiles = engine.loadLineFiles()

        val cfgNames = if (templates.isEmpty()) listOf(getString(R.string.no_config)) else templates.map { it.name }
        val cfgAdapter = createThemedSpinnerAdapter(cfgNames)
        binding.configDropdown.setAdapter(cfgAdapter)

        val lineNames = if (lineFiles.isEmpty()) listOf(getString(R.string.no_line)) else lineFiles.map { it.nameWithoutExtension }
        val lineAdapter = createThemedSpinnerAdapter(lineNames)
        binding.lineDropdown.setAdapter(lineAdapter)

        val savedCfg = prefs.getString("last_cfg_name", null)
        val savedLinePath = prefs.getString("last_line_path", null)
        savedCfg?.let { name ->
            val idx = templates.indexOfFirst { it.name == name }
            if (idx >= 0 && idx < cfgNames.size) binding.configDropdown.setText(cfgNames[idx], false)
        }
        savedLinePath?.let { path ->
            val idx = lineFiles.indexOfFirst { it.absolutePath == path }
            if (idx >= 0 && idx < lineNames.size) binding.lineDropdown.setText(lineNames[idx], false)
        }
        if (binding.configDropdown.text.isNullOrBlank() && cfgNames.isNotEmpty()) {
            binding.configDropdown.setText(cfgNames[0], false)
        }
        if (binding.lineDropdown.text.isNullOrBlank() && lineNames.isNotEmpty()) {
            binding.lineDropdown.setText(lineNames[0], false)
        }
    }

    private fun createThemedSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                val palette = currentPalette
                if (palette != null) {
                    styleDropdownItem(view, tv, palette.spinnerBgColor, palette.textColor, false)
                }
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                val palette = currentPalette
                if (palette != null) {
                    styleDropdownItem(view, tv, palette.listItemBgColor, palette.textColor, true)
                }
                return view
            }
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_list_item_1)
        }
    }

    private fun styleDropdownItem(
        view: View,
        textView: TextView,
        bgColor: Int,
        textColor: Int,
        asPopupItem: Boolean,
    ) {
        textView.setTextColor(textColor)
        textView.includeFontPadding = false
        textView.setLineSpacing(0f, 1.12f)
        val vertical = if (asPopupItem) dp(14) else dp(10)
        textView.setPadding(dp(16), vertical, dp(16), vertical)
        textView.minHeight = if (asPopupItem) dp(52) else dp(44)
        view.setBackgroundColor(bgColor)
    }

    private fun showOptionsDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 10)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cbExternal = CheckBox(this).apply {
            text = getString(R.string.opt_external)
            isChecked = options.externalBroadcast
        }
        val cbNext = CheckBox(this).apply {
            text = getString(R.string.opt_next_station)
            isChecked = options.includeNextStation
        }
        val cbHigh = CheckBox(this).apply {
            text = getString(R.string.opt_high_quality)
            isChecked = options.highQuality
        }
        val rgMissing = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbSilence = RadioButton(this).apply { text = getString(R.string.opt_missing_silence) }
        val rbChinese = RadioButton(this).apply { text = getString(R.string.opt_missing_chinese) }
        rgMissing.addView(rbSilence)
        rgMissing.addView(rbChinese)
        if (options.missingEngUseChinese) rbChinese.isChecked = true else rbSilence.isChecked = true

        root.addView(cbExternal)
        root.addView(cbNext)
        root.addView(cbHigh)
        root.addView(rgMissing)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.opt_title))
            .setView(root)
            .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                options.externalBroadcast = cbExternal.isChecked
                options.includeNextStation = cbNext.isChecked
                options.highQuality = cbHigh.isChecked
                options.missingEngUseChinese = rbChinese.isChecked
                saveOptions()
                setStatus(getString(R.string.status_options_saved))
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun loadSelectedConfigAndLine() {
        if (templates.isEmpty() || lineFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_empty_source), Toast.LENGTH_SHORT).show()
            return
        }

        val cfgName = binding.configDropdown.text?.toString()?.trim().orEmpty()
        val lineName = binding.lineDropdown.text?.toString()?.trim().orEmpty()
        val cfgIdx = templates.indexOfFirst { it.name == cfgName }.let { if (it >= 0) it else 0 }.coerceIn(0, templates.lastIndex)
        val lineIdx = lineFiles.indexOfFirst { it.nameWithoutExtension == lineName }.let { if (it >= 0) it else 0 }.coerceIn(0, lineFiles.lastIndex)
        val cfg = templates[cfgIdx]
        val line = lineFiles[lineIdx]
        prefs.edit().putString("last_cfg_name", cfg.name).putString("last_line_path", line.absolutePath).apply()

        binding.loadProgress.isIndeterminate = true
        binding.loadProgress.progress = 0
        setStatus(getString(R.string.status_loading_hint))
        appendLog("INFO: start load config=${cfg.name}, line=${line.nameWithoutExtension}")

        lifecycleScope.launch {
            try {
                val stations = withContext(Dispatchers.IO) { engine.readStationsByLine(line) }
                if (stations.isEmpty()) {
                    binding.loadProgress.isIndeterminate = false
                    setStatus(getString(R.string.status_line_empty))
                    appendLog("WARN: no station found from line file - ${line.nameWithoutExtension}")
                    return@launch
                }

                binding.loadProgress.isIndeterminate = false
                binding.loadProgress.progress = 35
                setStatus(getString(R.string.status_read_stations, stations.size))

                val result = withContext(Dispatchers.IO) {
                    engine.buildSynthesisTracks(cfg, line, stations, options) { done, total, title ->
                        val progress = 35 + if (total > 0) (done * 60 / total) else 0
                        val now = System.currentTimeMillis()
                        val shouldRefresh = now - lastSynthesisUiUpdateMs >= 150L || done == total
                        if (shouldRefresh) {
                            lastSynthesisUiUpdateMs = now
                            runOnUiThread {
                                binding.loadProgress.progress = progress
                                setStatus(getString(R.string.status_synth_item, title))
                            }
                        }
                    }
                }

                tracks = result.tracks
                val missing = result.missingResourceKeys
                binding.loadProgress.progress = 100
                result.logs.forEach { entry ->
                    val msg = entry.message
                    val keep = msg.startsWith("match:") ||
                        msg.startsWith("fallback:") ||
                        msg.startsWith("missing:") ||
                        msg.startsWith("template resources missing") ||
                        msg.startsWith("synthesis done") ||
                        msg.startsWith("missing required sequence:") ||
                        msg.contains(": unresolved") ||
                        msg.contains("synthesis result empty")
                    if (keep) {
                        appendLog("${entry.level}: $msg")
                    }
                }
                if (tracks.isEmpty()) {
                    setStatus(getString(R.string.status_synth_empty))
                    appendLog("WARN: synthesis finished but result list is empty")
                    return@launch
                }

                val titles = tracks.map { it.title }
                val adapter = object : ArrayAdapter<String>(
                    this@MainActivity,
                    android.R.layout.simple_list_item_activated_1,
                    titles
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        val tv = view.findViewById<TextView>(android.R.id.text1)
                        val palette = currentPalette
                        if (palette != null) {
                            val active = binding.trackList.isItemChecked(position)
                            view.setBackgroundColor(if (active) palette.listItemActiveBgColor else palette.listItemBgColor)
                            tv.setTextColor(palette.textColor)
                        }
                        return view
                    }
                }
                binding.trackList.adapter = adapter
                currentTrackIndex = 0
                binding.trackList.setItemChecked(0, true)
                adapter.notifyDataSetChanged()
                if (missing.isEmpty()) {
                    setStatus(getString(R.string.status_ready_tracks, tracks.size))
                } else {
                    setStatus(getString(R.string.status_ready_with_missing, tracks.size, missing.size))
                }
                appendLog("INFO: synthesis done, tracks=${tracks.size}, incomplete=${result.incompleteTrackCount}, stationMissing=${result.missingStationCount}")
            } catch (t: Throwable) {
                binding.loadProgress.isIndeterminate = false
                binding.loadProgress.progress = 0
                setStatus(getString(R.string.status_load_failed, t.message ?: getString(R.string.unknown_error)))
                appendLog("ERROR: synthesis failed - ${t.message ?: getString(R.string.unknown_error)}")
            }
        }
    }

    private fun playTrack(index: Int) {
        if (index !in tracks.indices) return
        val track = tracks[index]
        currentTrackIndex = index
        binding.trackList.setItemChecked(index, true)
        (binding.trackList.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        setStatus(getString(R.string.status_prepare_play, track.title))

        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                track.segments.mapNotNull { seg ->
                    val file = engine.resolveSegmentFilePath(seg)?.let { File(it) } ?: return@mapNotNull null
                    if (!file.exists()) return@mapNotNull null
                    val item = MediaItem.Builder()
                        .setUri(Uri.fromFile(file))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(getString(R.string.notify_line_title, currentLineName()))
                                .build()
                        )
                        .build()
                    PreparedSegment(item, durationForFile(file))
                }
            }
            if (prepared.isEmpty()) {
                setStatus(getString(R.string.status_play_failed_no_segment))
                appendLog("WARN: playback failed, no playable segment - ${track.title}")
                return@launch
            }
            notificationProgressPlayer.setTrackDurations(prepared.map { it.durationMs })
            player.setMediaItems(prepared.map { it.item }, true)
            player.prepare()
            player.play()
            setStatus(getString(R.string.status_playing_track, track.title))
            appendLog("INFO: start playback - ${track.title} (segments=${prepared.size})")
            renderPlaybackProgress()
        }
    }

    private fun moveTrack(step: Int) {
        if (tracks.isEmpty()) return
        val cur = if (currentTrackIndex in tracks.indices) currentTrackIndex else 0
        val next = when (playbackMode) {
            PlaybackMode.SINGLE_LOOP -> cur
            PlaybackMode.SHUFFLE -> {
                if (tracks.size <= 1) cur
                else {
                    var n = cur
                    repeat(5) { n = Random.nextInt(tracks.size) }
                    if (n == cur) (cur + 1) % tracks.size else n
                }
            }
            PlaybackMode.LIST_LOOP -> ((cur + step) % tracks.size + tracks.size) % tracks.size
            PlaybackMode.SEQUENTIAL -> (cur + step).coerceIn(0, tracks.lastIndex)
        }
        if (next == cur && playbackMode == PlaybackMode.SEQUENTIAL && (cur + step !in tracks.indices)) {
            Toast.makeText(this, getString(R.string.toast_reached_edge), Toast.LENGTH_SHORT).show()
            return
        }
        playTrack(next)
    }

    private fun hasLogicalNextTrack(): Boolean {
        if (tracks.isEmpty()) return false
        val cur = if (currentTrackIndex in tracks.indices) currentTrackIndex else 0
        return when (playbackMode) {
            PlaybackMode.SEQUENTIAL -> cur < tracks.lastIndex
            PlaybackMode.LIST_LOOP -> tracks.size > 1
            PlaybackMode.SINGLE_LOOP -> true
            PlaybackMode.SHUFFLE -> tracks.size > 1
        }
    }

    private fun hasLogicalPreviousTrack(): Boolean {
        if (tracks.isEmpty()) return false
        val cur = if (currentTrackIndex in tracks.indices) currentTrackIndex else 0
        return when (playbackMode) {
            PlaybackMode.SEQUENTIAL -> cur > 0
            PlaybackMode.LIST_LOOP -> tracks.size > 1
            PlaybackMode.SINGLE_LOOP -> true
            PlaybackMode.SHUFFLE -> tracks.size > 1
        }
    }

    private fun handleTrackEnded() {
        if (tracks.isEmpty()) return
        when (playbackMode) {
            PlaybackMode.SINGLE_LOOP -> playTrack(currentTrackIndex.coerceAtLeast(0))
            PlaybackMode.LIST_LOOP -> playTrack(((currentTrackIndex + 1) % tracks.size + tracks.size) % tracks.size)
            PlaybackMode.SHUFFLE -> moveTrack(1)
            PlaybackMode.SEQUENTIAL -> {
                if (currentTrackIndex in 0 until tracks.lastIndex) {
                    playTrack(currentTrackIndex + 1)
                } else {
                    updatePlayPauseIcon(false)
                }
            }
        }
    }

    private fun showVolumeDialog() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVol)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 10)
        }
        val value = TextView(this).apply {
            text = "${(curVol * 100 / maxVol)}%"
            textSize = 16f
        }
        val seek = SeekBar(this).apply {
            max = maxVol
            progress = curVol
        }
        var lastHapticAt = -1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                value.text = "${(progress * 100 / maxVol)}%"
                player.volume = progress.toFloat() / maxVol.toFloat()
                if (hapticEnabled && progress != lastHapticAt) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastHapticAt = progress
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        root.addView(value)
        root.addView(seek)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.volume_title))
            .setView(root)
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_minimal),
            getString(R.string.theme_ocean),
            getString(R.string.theme_forest),
            getString(R.string.theme_sunset),
            getString(R.string.theme_graphite)
        )
        var selected = uiTheme.ordinal
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.drawer_theme))
            .setSingleChoiceItems(options, selected) { _, which -> selected = which }
            .setPositiveButton(getString(R.string.common_apply)) { _, _ ->
                uiTheme = UiTheme.entries[selected]
                saveUiTheme()
                applyUiTheme(uiTheme)
                setStatus(getString(R.string.status_theme_applied, options[selected]))
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAboutDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 14)
        }
        val title1 = TextView(this).apply {
            text = getString(R.string.about_section_intro)
            textSize = 16f
        }
        val body1 = TextView(this).apply {
            text = getString(R.string.about_section_intro_body)
            textSize = 14f
            setPadding(0, 6, 0, 10)
        }
        val title2 = TextView(this).apply {
            text = getString(R.string.about_section_usage)
            textSize = 16f
        }
        val body2 = TextView(this).apply {
            text = getString(R.string.about_section_usage_body)
            textSize = 14f
            setPadding(0, 6, 0, 10)
        }
        val title3 = TextView(this).apply {
            text = getString(R.string.about_section_version)
            textSize = 16f
        }
        val body3 = TextView(this).apply {
            text = getString(R.string.about_section_version_body, appVersionName())
            textSize = 14f
            setPadding(0, 6, 0, 10)
        }
        val title4 = TextView(this).apply {
            text = getString(R.string.about_section_repo)
            textSize = 16f
        }
        val body4 = TextView(this).apply {
            text = getString(R.string.about_section_repo_body)
            textSize = 14f
            setTextColor(currentPalette?.primaryButton ?: Color.parseColor("#2C6FFF"))
            setPadding(0, 6, 0, 0)
            setOnClickListener {
                runCatching {
                    val it = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_link)))
                    startActivity(it)
                }
            }
        }
        root.addView(title1)
        root.addView(body1)
        root.addView(createAboutDivider())
        root.addView(title2)
        root.addView(body2)
        root.addView(createAboutDivider())
        root.addView(title3)
        root.addView(body3)
        root.addView(createAboutDivider())
        root.addView(title4)
        root.addView(body4)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.drawer_about))
            .setView(root)
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    private fun showOnboardingIfNeeded() {
        if (prefs.getBoolean("onboarding_shown", false)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.guide_title))
            .setMessage(getString(R.string.guide_welcome))
            .setPositiveButton(getString(R.string.guide_start_now)) { _, _ ->
                prefs.edit().putBoolean("onboarding_shown", true).apply()
                binding.mainContainer.post { startTutorialSequence(markAsShown = true) }
            }
            .setNegativeButton(getString(R.string.guide_skip)) { _, _ ->
                prefs.edit().putBoolean("onboarding_shown", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun startTutorialSequence(markAsShown: Boolean) {
        val sequence = TapTargetSequence(this)
            .targets(
                buildTapTarget(binding.configInputLayout, R.string.guide_step_title_1, R.string.guide_step_1),
                buildTapTarget(binding.loadButton, R.string.guide_step_title_2, R.string.guide_step_2),
                buildTapTarget(binding.trackList, R.string.guide_step_title_3, R.string.guide_step_3),
                buildTapTarget(binding.playPauseButton, R.string.guide_step_title_4, R.string.guide_desc_playback),
                buildTapTarget(binding.menuButton, R.string.guide_step_title_5, R.string.guide_desc_menu),
            )
            .listener(object : TapTargetSequence.Listener {
                override fun onSequenceFinish() {
                    if (markAsShown) {
                        prefs.edit().putBoolean("onboarding_shown", true).apply()
                    }
                }

                override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {}

                override fun onSequenceCanceled(lastTarget: TapTarget?) {
                    if (markAsShown) {
                        prefs.edit().putBoolean("onboarding_shown", true).apply()
                    }
                }
            })
        sequence.start()
    }

    private fun buildTapTarget(view: View, titleRes: Int, descRes: Int): TapTarget {
        return TapTarget.forView(view, getString(titleRes), getString(descRes))
            .outerCircleColorInt(Color.parseColor("#B3000000"))
            .targetCircleColorInt(Color.parseColor("#FFFFFF"))
            .titleTextColorInt(Color.WHITE)
            .descriptionTextColorInt(Color.WHITE)
            .transparentTarget(true)
            .drawShadow(false)
            .tintTarget(false)
            .cancelable(true)
    }

    private fun applyUiTheme(theme: UiTheme) {
        val palette = when (theme) {
            UiTheme.SYSTEM -> if (isSystemDarkMode()) {
                UiPalette(
                    bgColor = Color.parseColor("#121417"),
                    surfaceColor = Color.parseColor("#1A1E23"),
                    textColor = Color.parseColor("#E9EDF3"),
                    statusColor = Color.parseColor("#BFC7D1"),
                    progressTextColor = Color.parseColor("#C7D2DE"),
                    listBgColor = Color.parseColor("#171B20"),
                    listItemBgColor = Color.parseColor("#1B2027"),
                    listItemActiveBgColor = Color.parseColor("#263241"),
                    spinnerBgColor = Color.parseColor("#1E232B"),
                    neutralButton = Color.parseColor("#242C36"),
                    primaryButton = Color.parseColor("#3D82F5"),
                    primaryIcon = Color.WHITE,
                    navBgColor = Color.parseColor("#171B21"),
                    progressTint = Color.parseColor("#5D9BFF"),
                )
            } else {
                UiPalette(
                    bgColor = Color.parseColor("#F2F4F8"),
                    surfaceColor = Color.parseColor("#EEF2F7"),
                    textColor = Color.parseColor("#202124"),
                    statusColor = Color.parseColor("#4A4E57"),
                    progressTextColor = Color.parseColor("#525861"),
                    listBgColor = Color.parseColor("#EFF3F8"),
                    listItemBgColor = Color.parseColor("#F6F8FC"),
                    listItemActiveBgColor = Color.parseColor("#E7EFFD"),
                    spinnerBgColor = Color.parseColor("#E8EDF4"),
                    neutralButton = Color.parseColor("#EEF1F5"),
                    primaryButton = Color.parseColor("#2C6FFF"),
                    primaryIcon = Color.WHITE,
                    navBgColor = Color.parseColor("#EEF2F7"),
                    progressTint = Color.parseColor("#2C6FFF"),
                )
            }
            UiTheme.MINIMAL -> UiPalette(
                bgColor = Color.parseColor("#F2F4F8"),
                surfaceColor = Color.parseColor("#EEF2F7"),
                textColor = Color.parseColor("#202124"),
                statusColor = Color.parseColor("#4A4E57"),
                progressTextColor = Color.parseColor("#525861"),
                listBgColor = Color.parseColor("#EFF3F8"),
                listItemBgColor = Color.parseColor("#F6F8FC"),
                listItemActiveBgColor = Color.parseColor("#E7EFFD"),
                spinnerBgColor = Color.parseColor("#E8EDF4"),
                neutralButton = Color.parseColor("#EEF1F5"),
                primaryButton = Color.parseColor("#2C6FFF"),
                primaryIcon = Color.WHITE,
                navBgColor = Color.parseColor("#EEF2F7"),
                progressTint = Color.parseColor("#2C6FFF"),
            )
            UiTheme.OCEAN -> UiPalette(
                bgColor = Color.parseColor("#EAF2FF"),
                surfaceColor = Color.parseColor("#F8FBFF"),
                textColor = Color.parseColor("#153C6B"),
                statusColor = Color.parseColor("#2A5B95"),
                progressTextColor = Color.parseColor("#295581"),
                listBgColor = Color.parseColor("#F8FBFF"),
                listItemBgColor = Color.parseColor("#EAF3FF"),
                listItemActiveBgColor = Color.parseColor("#CCE1FF"),
                spinnerBgColor = Color.parseColor("#DCEBFF"),
                neutralButton = Color.parseColor("#DCEBFF"),
                primaryButton = Color.parseColor("#1F78FF"),
                primaryIcon = Color.WHITE,
                navBgColor = Color.parseColor("#F4F9FF"),
                progressTint = Color.parseColor("#1F78FF"),
            )
            UiTheme.FOREST -> UiPalette(
                bgColor = Color.parseColor("#EEF6EE"),
                surfaceColor = Color.parseColor("#F8FCF8"),
                textColor = Color.parseColor("#274B2D"),
                statusColor = Color.parseColor("#35683E"),
                progressTextColor = Color.parseColor("#3E6946"),
                listBgColor = Color.parseColor("#F7FCF8"),
                listItemBgColor = Color.parseColor("#ECF7EE"),
                listItemActiveBgColor = Color.parseColor("#CFEAD4"),
                spinnerBgColor = Color.parseColor("#DFEEE0"),
                neutralButton = Color.parseColor("#DFEEE0"),
                primaryButton = Color.parseColor("#2E8B57"),
                primaryIcon = Color.WHITE,
                navBgColor = Color.parseColor("#F4FBF4"),
                progressTint = Color.parseColor("#2E8B57"),
            )
            UiTheme.SUNSET -> UiPalette(
                bgColor = Color.parseColor("#FFF4EC"),
                surfaceColor = Color.parseColor("#FFF9F3"),
                textColor = Color.parseColor("#6A3A1D"),
                statusColor = Color.parseColor("#8E532E"),
                progressTextColor = Color.parseColor("#8D4F2C"),
                listBgColor = Color.parseColor("#FFF9F3"),
                listItemBgColor = Color.parseColor("#FFEEDF"),
                listItemActiveBgColor = Color.parseColor("#FFD4B3"),
                spinnerBgColor = Color.parseColor("#FFE3CD"),
                neutralButton = Color.parseColor("#FFE3CD"),
                primaryButton = Color.parseColor("#E86A2E"),
                primaryIcon = Color.WHITE,
                navBgColor = Color.parseColor("#FFF5EA"),
                progressTint = Color.parseColor("#E86A2E"),
            )
            UiTheme.GRAPHITE -> UiPalette(
                bgColor = Color.parseColor("#121417"),
                surfaceColor = Color.parseColor("#1A1E23"),
                textColor = Color.parseColor("#E9EDF3"),
                statusColor = Color.parseColor("#BFC7D1"),
                progressTextColor = Color.parseColor("#C7D2DE"),
                listBgColor = Color.parseColor("#171B20"),
                listItemBgColor = Color.parseColor("#1B2027"),
                listItemActiveBgColor = Color.parseColor("#263241"),
                spinnerBgColor = Color.parseColor("#1E232B"),
                neutralButton = Color.parseColor("#242C36"),
                primaryButton = Color.parseColor("#3D82F5"),
                primaryIcon = Color.WHITE,
                navBgColor = Color.parseColor("#171B21"),
                progressTint = Color.parseColor("#5D9BFF"),
            )
        }

        currentPalette = palette
        binding.mainContainer.setBackgroundColor(palette.bgColor)
        binding.topBar.setBackgroundColor(palette.bgColor)
        binding.playerProgressContainer.setBackgroundColor(palette.bgColor)
        binding.titleText.setTextColor(palette.textColor)
        binding.statusText.setTextColor(palette.statusColor)
        binding.playProgressText.setTextColor(palette.progressTextColor)
        binding.playProgressBar.progressTintList = ColorStateList.valueOf(palette.progressTint)
        binding.playProgressBar.thumbTintList = ColorStateList.valueOf(palette.progressTint)

        binding.trackList.setBackgroundColor(palette.listBgColor)
        binding.navView.setBackgroundColor(palette.navBgColor)
        binding.navView.itemTextColor = ColorStateList.valueOf(palette.textColor)
        binding.navView.itemIconTintList = ColorStateList.valueOf(palette.textColor)

        if (binding.navView.headerCount > 0) {
            val header = binding.navView.getHeaderView(0)
            header.setBackgroundColor(palette.surfaceColor)
            header.findViewById<TextView>(R.id.navHeaderTitle)?.setTextColor(palette.textColor)
            header.findViewById<TextView>(R.id.navHeaderSubtitle)?.setTextColor(palette.statusColor)
        }

        binding.configInputLayout.boxBackgroundColor = palette.spinnerBgColor
        binding.configInputLayout.hintTextColor = ColorStateList.valueOf(palette.statusColor)
        binding.configInputLayout.defaultHintTextColor = ColorStateList.valueOf(palette.statusColor)
        binding.configInputLayout.setBoxStrokeColor(palette.progressTint)
        binding.lineInputLayout.boxBackgroundColor = palette.spinnerBgColor
        binding.lineInputLayout.hintTextColor = ColorStateList.valueOf(palette.statusColor)
        binding.lineInputLayout.defaultHintTextColor = ColorStateList.valueOf(palette.statusColor)
        binding.lineInputLayout.setBoxStrokeColor(palette.progressTint)
        binding.configDropdown.setTextColor(palette.textColor)
        binding.lineDropdown.setTextColor(palette.textColor)
        applyDropdownPopupTheme(palette)

        val optionsBgColor = blendColor(palette.neutralButton, palette.primaryButton, 0.18f)
        val optionsTextColor = readableTextColor(optionsBgColor)
        val loadTextColor = readableTextColor(palette.primaryButton)
        tintButton(binding.optionsButton, R.drawable.bg_action_button, optionsBgColor, optionsTextColor)
        tintButton(binding.loadButton, R.drawable.bg_action_button, palette.primaryButton, loadTextColor)

        tintImageButton(binding.menuButton, R.drawable.bg_player_button, palette.neutralButton, palette.textColor)
        tintImageButton(binding.playModeButton, R.drawable.bg_player_button, palette.neutralButton, palette.textColor)
        tintImageButton(binding.prevButton, R.drawable.bg_player_button, palette.neutralButton, palette.textColor)
        tintImageButton(binding.nextButton, R.drawable.bg_player_button, palette.neutralButton, palette.textColor)
        tintImageButton(binding.volumeButton, R.drawable.bg_player_button, palette.neutralButton, palette.textColor)
        tintImageButton(binding.playPauseButton, R.drawable.bg_player_button_primary, palette.primaryButton, palette.primaryIcon)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = palette.bgColor
            window.navigationBarColor = palette.bgColor
        }
        val useDarkSystemBarIcons = !isColorDark(palette.bgColor)
        WindowCompat.getInsetsController(window, window.decorView)?.isAppearanceLightStatusBars = useDarkSystemBarIcons
        WindowCompat.getInsetsController(window, window.decorView)?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        (binding.trackList.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        (binding.configDropdown.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        (binding.lineDropdown.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
    }

    private fun applyDropdownPopupTheme(palette: UiPalette) {
        val configDrawable = AppCompatResources.getDrawable(this, R.drawable.bg_dropdown_popup)?.mutate()
        if (configDrawable != null) {
            DrawableCompat.setTint(configDrawable, palette.listBgColor)
            binding.configDropdown.setDropDownBackgroundDrawable(configDrawable)
        }

        val lineDrawable = AppCompatResources.getDrawable(this, R.drawable.bg_dropdown_popup)?.mutate()
        if (lineDrawable != null) {
            DrawableCompat.setTint(lineDrawable, palette.listBgColor)
            binding.lineDropdown.setDropDownBackgroundDrawable(lineDrawable)
        }
    }

    private fun createAboutDivider(): View {
        val palette = currentPalette
        val dividerColor = if (palette == null) {
            Color.parseColor("#22000000")
        } else {
            Color.argb(
                70,
                Color.red(palette.statusColor),
                Color.green(palette.statusColor),
                Color.blue(palette.statusColor),
            )
        }
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
            setBackgroundColor(dividerColor)
        }
    }

    private fun isSystemDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val inv = 1f - r
        return Color.argb(
            (Color.alpha(from) * inv + Color.alpha(to) * r).roundToInt(),
            (Color.red(from) * inv + Color.red(to) * r).roundToInt(),
            (Color.green(from) * inv + Color.green(to) * r).roundToInt(),
            (Color.blue(from) * inv + Color.blue(to) * r).roundToInt(),
        )
    }

    private fun readableTextColor(bg: Int): Int {
        return if (isColorDark(bg)) Color.WHITE else Color.parseColor("#15171A")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun tintButton(view: View, @DrawableRes bgRes: Int, bgColor: Int, textColor: Int) {
        val drawable = AppCompatResources.getDrawable(this, bgRes)?.mutate() ?: return
        DrawableCompat.setTint(drawable, bgColor)
        view.background = drawable
        if (view is android.widget.Button) view.setTextColor(textColor)
    }

    private fun tintImageButton(view: View, @DrawableRes bgRes: Int, bgColor: Int, iconColor: Int) {
        val drawable = AppCompatResources.getDrawable(this, bgRes)?.mutate() ?: return
        DrawableCompat.setTint(drawable, bgColor)
        view.background = drawable
        if (view is android.widget.ImageButton) {
            view.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    private fun syncPlayerVolumeFromSystem() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVol)
        player.volume = curVol.toFloat() / maxVol.toFloat()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updatePlaybackModeIcon() {
        @DrawableRes
        val icon = when (playbackMode) {
            PlaybackMode.SEQUENTIAL -> R.drawable.ic_mode_sequence
            PlaybackMode.LIST_LOOP -> R.drawable.ic_mode_list_loop
            PlaybackMode.SINGLE_LOOP -> R.drawable.ic_mode_single
            PlaybackMode.SHUFFLE -> R.drawable.ic_mode_shuffle
        }
        binding.playModeButton.setImageResource(icon)
        binding.playModeButton.contentDescription = modeText(playbackMode)
    }

    private fun modeText(mode: PlaybackMode): String {
        return when (mode) {
            PlaybackMode.SEQUENTIAL -> getString(R.string.mode_sequence)
            PlaybackMode.LIST_LOOP -> getString(R.string.mode_list_loop)
            PlaybackMode.SINGLE_LOOP -> getString(R.string.mode_single)
            PlaybackMode.SHUFFLE -> getString(R.string.mode_shuffle)
        }
    }

    private fun currentLineName(): String {
        val selected = binding.lineDropdown.text?.toString()?.trim().orEmpty()
        return selected.ifBlank { getString(R.string.unknown_line) }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = lifecycleScope.launch {
            while (isActive) {
                renderPlaybackProgress()
                delay(if (player.isPlaying) 220L else 1000L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun renderPlaybackProgress() {
        if (userSeekingProgress) return
        val total = notificationProgressPlayer.getTotalDurationMs().coerceAtLeast(0L)
        val current = notificationProgressPlayer.getAggregatedPositionMs().coerceAtLeast(0L)
        if (total <= 0L) {
            binding.playProgressBar.progress = 0
            binding.playProgressText.text = getString(R.string.progress_time_text, "00:00", "00:00")
            return
        }
        val progress = (current * binding.playProgressBar.max / total).toInt().coerceIn(0, binding.playProgressBar.max)
        binding.playProgressBar.progress = progress
        binding.playProgressText.text = getString(
            R.string.progress_time_text,
            formatDuration(current),
            formatDuration(total)
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    private fun appVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun pulse(view: View?) {
        if (!hapticEnabled) return
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun appendLog(message: String) {
        val stamp = runCatching {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        }.getOrDefault("--:--:--")
        synthesisLogs += "[$stamp] $message"
        if (synthesisLogs.size > 800) {
            synthesisLogs.removeAt(0)
        }
    }

    private fun showLogsDialog() {
        val display = if (synthesisLogs.isEmpty()) {
            mutableListOf("暂无日志")
        } else {
            synthesisLogs.asReversed().toMutableList()
        }
        val palette = currentPalette
        val dialogBg = palette?.surfaceColor ?: Color.parseColor("#F4F6FA")
        val listBg = palette?.listBgColor ?: dialogBg
        val rowBg = palette?.listItemBgColor ?: listBg
        val rowTextColor = palette?.textColor ?: readableTextColor(rowBg)
        val dividerColor = Color.argb(48, Color.red(rowTextColor), Color.green(rowTextColor), Color.blue(rowTextColor))

        val rowAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, display) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                val bg = if (position % 2 == 0) rowBg else blendColor(rowBg, listBg, 0.32f)
                view.setBackgroundColor(bg)
                tv.setTextColor(rowTextColor)
                tv.textSize = 13f
                val hPadding = (12 * resources.displayMetrics.density).roundToInt()
                val vPadding = (8 * resources.displayMetrics.density).roundToInt()
                tv.setPadding(hPadding, vPadding, hPadding, vPadding)
                return view
            }
        }

        val listView = android.widget.ListView(this).apply {
            setBackgroundColor(listBg)
            divider = ColorDrawable(dividerColor)
            dividerHeight = 1
            cacheColorHint = listBg
            this.adapter = rowAdapter
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("日志")
            .setView(listView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                synthesisLogs.clear()
                setStatus("状态：日志已清空")
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(dialogBg))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette?.primaryButton ?: Color.parseColor("#2C6FFF"))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(palette?.primaryButton ?: Color.parseColor("#2C6FFF"))
    }

    private fun durationForFile(file: File): Long {
        durationCacheMs[file.absolutePath]?.let { return it }
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        durationCacheMs[file.absolutePath] = duration
        return duration
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerRoot.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerRoot.closeDrawer(GravityCompat.START)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt <= BACK_EXIT_INTERVAL_MS) {
                    performExitCleanup()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(this@MainActivity, getString(R.string.toast_press_back_again), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun performExitCleanup() {
        setStatus(getString(R.string.status_exiting_cleanup))
        runCatching {
            notificationManager?.setPlayer(null)
            player.stop()
            engine.clearSession()
            notificationProgressPlayer.setTrackDurations(emptyList())
            binding.playProgressBar.progress = 0
            binding.playProgressText.text = getString(R.string.progress_time_text, "00:00", "00:00")
        }
        finishAndRemoveTask()
    }

    private fun loadOptions() {
        options.externalBroadcast = prefs.getBoolean("opt_external", true)
        options.includeNextStation = prefs.getBoolean("opt_next", true)
        options.highQuality = prefs.getBoolean("opt_high", true)
        options.missingEngUseChinese = prefs.getBoolean("opt_missing_zh", true)
        hapticEnabled = prefs.getBoolean("opt_haptic", true)
        playbackMode = runCatching {
            PlaybackMode.valueOf(
                prefs.getString("play_mode", PlaybackMode.SEQUENTIAL.name) ?: PlaybackMode.SEQUENTIAL.name
            )
        }.getOrElse { PlaybackMode.SEQUENTIAL }
        uiTheme = runCatching {
            UiTheme.valueOf(prefs.getString("ui_theme", UiTheme.SYSTEM.name) ?: UiTheme.SYSTEM.name)
        }.getOrElse { UiTheme.SYSTEM }
    }

    private fun saveOptions() {
        prefs.edit()
            .putBoolean("opt_external", options.externalBroadcast)
            .putBoolean("opt_next", options.includeNextStation)
            .putBoolean("opt_high", options.highQuality)
            .putBoolean("opt_missing_zh", options.missingEngUseChinese)
            .apply()
    }

    private fun savePlaybackMode() {
        prefs.edit().putString("play_mode", playbackMode.name).apply()
    }

    private fun saveUiTheme() {
        prefs.edit().putString("ui_theme", uiTheme.name).apply()
    }

    private fun saveHapticEnabled() {
        prefs.edit().putBoolean("opt_haptic", hapticEnabled).apply()
    }
}

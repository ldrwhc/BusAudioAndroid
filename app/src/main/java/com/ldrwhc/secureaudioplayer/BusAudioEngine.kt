package com.ldrwhc.secureaudioplayer

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.abs

class BusAudioEngine(private val context: Context) {
    companion object {
        private val PAK_MAGIC = byteArrayOf(0x42, 0x55, 0x53, 0x41, 0x55, 0x44, 0x31, 0x00) // BUSAUD1\0
        private const val DEFAULT_KEY = "BusAnnouncement@2026"
        private val AUDIO_EXT = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "wma")
    }

    private val runtimeRoot: File = context.filesDir
    private val configDir = File(runtimeRoot, "config")
    private val linesDir = File(runtimeRoot, "lines")
    private val packsDir = File(runtimeRoot, "packs")
    private val tempRoot = File(context.cacheDir, "_runtime_tmp/SecureAudioPlayer")

    private var currentManifest: PackManifest = PackManifest(DEFAULT_KEY, emptyList(), emptyMap(), emptyMap())
    private var currentSessionDir: File? = null
    private var currentPackKey: String = DEFAULT_KEY
    private val packages = mutableListOf<PackageInfo>()
    private val sources = mutableListOf<SourceEntry>()
    private val extractedSourcePathByIndex = ConcurrentHashMap<Int, String>()
    private var silence100msPath: String = ""

    private val bestResourceByLowerPath = hashMapOf<String, Int>()
    private val bestResourceByLowerName = hashMapOf<String, Int>()
    private val stationRootIndexZh = hashMapOf<String, MutableList<Int>>()
    private val stationRootIndexEn = hashMapOf<String, MutableList<Int>>()
    @Volatile
    private var prewarmed = false

    fun bootstrapPayloadFromAssets() {
        configDir.mkdirs()
        linesDir.mkdirs()
        packsDir.mkdirs()
        tempRoot.mkdirs()

        if (hasRuntimeReady()) {
            return
        }

        copyAssetIfExists("payload/seed_config.zip", File(runtimeRoot, "seed_config.zip"))
        copyAssetIfExists("payload/seed_lines.zip", File(runtimeRoot, "seed_lines.zip"))
        copyAssetDir("payload/packs", packsDir)

        val cfgJson = configDir.listFiles()?.any {
            it.isFile &&
                it.extension.equals("json", true) &&
                !it.name.equals("pack_manifest.json", true)
        } == true
        if (!cfgJson) {
            val seedCfg = File(runtimeRoot, "seed_config.zip")
            if (seedCfg.exists()) unzipToDir(seedCfg, configDir)
        }
        val lineExists = linesDir.listFiles()?.any {
            it.isFile && (it.extension.equals("xlsx", true) || it.extension.equals("txt", true))
        } == true
        if (!lineExists) {
            val seedLines = File(runtimeRoot, "seed_lines.zip")
            if (seedLines.exists()) unzipToDir(seedLines, linesDir)
        }
    }

    fun hasRuntimeReady(): Boolean {
        val cfgJson = configDir.exists() && (configDir.listFiles()?.any {
            it.isFile && it.extension.equals("json", true) && !it.name.equals("pack_manifest.json", true)
        } == true)
        val lineExists = linesDir.exists() && (linesDir.listFiles()?.any {
            it.isFile && (it.extension.equals("xlsx", true) || it.extension.equals("txt", true))
        } == true)
        val pakExists = packsDir.exists() && (packsDir.listFiles()?.any {
            it.isFile && it.extension.equals("pak", true)
        } == true)
        return cfgJson && lineExists && pakExists
    }

    fun clearSession() {
        sources.clear()
        packages.clear()
        extractedSourcePathByIndex.clear()
        bestResourceByLowerPath.clear()
        bestResourceByLowerName.clear()
        stationRootIndexZh.clear()
        stationRootIndexEn.clear()
        currentSessionDir?.deleteRecursively()
        currentSessionDir = null
        silence100msPath = ""
        prewarmed = false
    }

    fun prewarmSynthesis() {
        if (prewarmed) return
        ensureSourcesLoaded()
        ensureSilence100msFile()
        prewarmed = true
    }

    fun loadTemplateConfigs(): List<TemplateConfig> {
        val out = mutableListOf<TemplateConfig>()
        if (!configDir.exists()) return out
        configDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) && !it.name.equals("pack_manifest.json", true) }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?.forEach { file ->
                parseTemplateConfig(file)?.let { out += it }
            }
        return out
    }

    fun loadLineFiles(): List<File> {
        val files = linesDir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("xlsx", true) || it.extension.equals("txt", true)) }
            ?: emptyList()

        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        return files.sortedWith { a, b ->
            val an = a.nameWithoutExtension
            val bn = b.nameWithoutExtension
            val ai = parseFirstInt(an)
            val bi = parseFirstInt(bn)
            when {
                ai != null && bi == null -> -1
                ai == null && bi != null -> 1
                ai != null && bi != null -> {
                    val aa = abs(ai)
                    val bb = abs(bi)
                    if (aa != bb) aa.compareTo(bb)
                    else ai.compareTo(bi)
                }
                else -> collator.compare(an, bn)
            }
        }
    }

    fun readStationsByLine(lineFile: File): List<String> {
        if (!lineFile.exists()) return emptyList()
        return when {
            lineFile.extension.equals("txt", true) -> readTxtStations(lineFile)
            lineFile.extension.equals("xlsx", true) -> readXlsxStations(lineFile)
            else -> emptyList()
        }
    }

    fun resolveSegmentFilePath(segment: SegmentRef): String? {
        return when (segment) {
            is SegmentRef.FilePath -> segment.path
            is SegmentRef.Source -> ensureSourceExtractedFile(segment.sourceIndex)?.absolutePath
        }
    }

    fun buildSynthesisTracks(
        template: TemplateConfig,
        lineFile: File,
        stations: List<String>,
        options: SynthesisOptions,
        stationPicker: ((StationPickRequest) -> Int?)? = null,
        progressCb: ((done: Int, total: Int, title: String) -> Unit)? = null,
    ): SynthesisBuildResult {
        if (stations.isEmpty()) {
            return SynthesisBuildResult(
                tracks = emptyList(),
                missingResourceKeys = emptyList(),
                logs = listOf(SynthesisLogEntry("WARN", "line station list is empty, no synthesis generated")),
                incompleteTrackCount = 0,
                matchedStationCount = 0,
                missingStationCount = 0,
            )
        }
        ensureSourcesLoaded()

        val logs = mutableListOf<SynthesisLogEntry>()
        fun info(msg: String) {
            logs += SynthesisLogEntry("INFO", msg)
        }
        fun warn(msg: String) {
            logs += SynthesisLogEntry("WARN", msg)
        }

        val required = listOf("start_station", "enter_station", "terminal_station")
        required.forEach {
            if (!template.sequences.containsKey(it)) {
                warn("missing required sequence: $it")
                return SynthesisBuildResult(
                    tracks = emptyList(),
                    missingResourceKeys = emptyList(),
                    logs = logs,
                    incompleteTrackCount = 0,
                    matchedStationCount = 0,
                    missingStationCount = 0,
                )
            }
        }

        val missingResourceKeys = mutableListOf<String>()
        val runtimeTemplate = materializeTemplate(template, missingResourceKeys, logs)

        val stationSelectCache = hashMapOf<String, Int>()
        val stationSegCache = hashMapOf<String, SegmentRef?>()
        val matchedStationKeys = linkedSetOf<String>()
        val missingStationKeys = linkedSetOf<String>()
        val stationLogDedup = hashSetOf<String>()

        fun stationSeg(name: String, isChinese: Boolean, fallback: SegmentRef?): SegmentRef? {
            val key = "${if (isChinese) "zh" else "en"}|${name.trim()}|${if (options.highQuality) 1 else 0}|${if (options.missingEngUseChinese) 1 else 0}"
            if (stationSegCache.containsKey(key)) return stationSegCache[key]
            val cleanName = name.trim()
            val idx = selectStationSourceIndex(cleanName, isChinese, options, stationSelectCache, stationPicker)
            val out = if (idx >= 0) {
                matchedStationKeys += key.substringBeforeLast("|")
                if (stationLogDedup.add(key)) {
                    info("match: ${if (isChinese) "ZH" else "EN"} $cleanName -> ${sources[idx].baseFileName}")
                }
                SegmentRef.Source(idx)
            } else if (!isChinese) {
                missingStationKeys += key.substringBeforeLast("|")
                if (options.missingEngUseChinese && fallback != null) {
                    if (stationLogDedup.add(key)) {
                        warn("fallback: EN->ZH $cleanName")
                    }
                    fallback
                } else {
                    if (stationLogDedup.add(key)) {
                        warn("fallback: EN->silence $cleanName")
                    }
                    SegmentRef.FilePath(ensureSilence100msFile())
                }
            } else {
                missingStationKeys += key.substringBeforeLast("|")
                if (stationLogDedup.add(key)) {
                    warn("missing: ZH $cleanName")
                }
                null
            }
            stationSegCache[key] = out
            return out
        }

        val routeId = routeIdFromLineFile(lineFile).ifBlank { lineFile.nameWithoutExtension.trim() }
        val lineChn = stationSeg(routeId, true, null)
        val enableEnglish = template.hasEnglish && options.externalBroadcast
        val lineEng = if (enableEnglish) stationSeg(routeId, false, lineChn) else null
        val terminalChn = stationSeg(stations.last(), true, null)
        val terminalEng = if (enableEnglish) stationSeg(stations.last(), false, terminalChn) else null
        val nextFlag = options.includeNextStation && runtimeTemplate.sequences.containsKey("next_station")

        val tracks = mutableListOf<TrackItem>()
        var incompleteTrackCount = 0
        var done = 0
        val total = if (nextFlag) (stations.size * 2 - 1) else stations.size

        fun buildCtx(currentIdx: Int, nextIdx: Int): SynthesisContext {
            val currentChn = if (currentIdx in stations.indices) stationSeg(stations[currentIdx], true, null) else null
            val currentEng = if (enableEnglish && currentIdx in stations.indices) stationSeg(stations[currentIdx], false, currentChn) else null
            val nextChn = if (nextIdx in stations.indices) stationSeg(stations[nextIdx], true, null) else null
            val nextEng = if (enableEnglish && nextIdx in stations.indices) stationSeg(stations[nextIdx], false, nextChn) else null
            return SynthesisContext(lineChn, lineEng, currentChn, currentEng, nextChn, nextEng, terminalChn, terminalEng)
        }

        fun addTrack(title: String, seqType: String, ctx: SynthesisContext) {
            val issues = mutableListOf<String>()
            val segments = buildSequenceSegments(seqType, runtimeTemplate, ctx, issues)
            var incomplete = false
            if (issues.isNotEmpty()) {
                incomplete = true
                issues.forEach { warn("$title: $it") }
            }
            if (segments.isEmpty()) {
                incomplete = true
                warn("$title: synthesis result empty")
            } else {
                tracks += TrackItem(title = title, segments = segments)
            }
            if (incomplete) incompleteTrackCount += 1
            done += 1
            progressCb?.invoke(done, total, title)
        }

        addTrack("${"%02d".format(1)} ${stations.first()}", "start_station", buildCtx(0, if (stations.size > 1) 1 else -1))
        if (nextFlag && stations.size > 1) {
            addTrack("${"%02d".format(1)}-下 ${stations[1]}", "next_station", buildCtx(0, 1))
        }
        for (i in 1 until (stations.size - 1)) {
            addTrack("${"%02d".format(i + 1)} ${stations[i]}", "enter_station", buildCtx(i, i + 1))
            if (nextFlag) addTrack("${"%02d".format(i + 1)}-下 ${stations[i + 1]}", "next_station", buildCtx(i, i + 1))
        }
        if (stations.size > 1) {
            val idx = stations.lastIndex
            addTrack("${"%02d".format(stations.size)} ${stations.last()}", "terminal_station", buildCtx(idx, -1))
        }
        if (missingResourceKeys.isNotEmpty()) {
            warn("template resources missing: ${missingResourceKeys.size}")
        }
        info("synthesis done: tracks=${tracks.size}, incomplete=$incompleteTrackCount")
        return SynthesisBuildResult(
            tracks = tracks,
            missingResourceKeys = missingResourceKeys,
            logs = logs,
            incompleteTrackCount = incompleteTrackCount,
            matchedStationCount = matchedStationKeys.size,
            missingStationCount = missingStationKeys.size,
        )
    }

    private fun parseTemplateConfig(file: File): TemplateConfig? {
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val seqObj = obj.optJSONObject("sequences") ?: return null
            val sequences = linkedMapOf<String, List<String>>()
            seqObj.keys().forEach { key ->
                val arr = seqObj.optJSONArray(key) ?: JSONArray()
                val parts = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) parts += v
                }
                sequences[key] = parts
            }
            if (sequences.isEmpty()) return null

            val resourcesObj = obj.optJSONObject("resources") ?: JSONObject()
            val resources = linkedMapOf<String, String>()
            resourcesObj.keys().forEach { key ->
                val v = resourcesObj.optString(key, "")
                if (v.isNotBlank()) resources[key] = v
            }
            TemplateConfig(
                filePath = file.absolutePath,
                name = obj.optString("name", file.nameWithoutExtension),
                hasEnglish = obj.optBoolean("eng", false),
                resources = resources,
                sequences = sequences,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureSourcesLoaded() {
        if (sources.isNotEmpty()) return
        prepareNewSessionDir()
        currentManifest = loadPackManifest()
        currentPackKey = if (currentManifest.key.isBlank()) DEFAULT_KEY else currentManifest.key
        loadPackagesForSession(currentManifest)
    }

    private fun prepareNewSessionDir() {
        clearSession()
        tempRoot.mkdirs()
        currentSessionDir = File(tempRoot, "${android.os.Process.myPid()}_${System.currentTimeMillis()}_${UUID.randomUUID()}")
        currentSessionDir?.mkdirs()
        File(currentSessionDir, "pak_work_zip").mkdirs()
        File(currentSessionDir, "source_cache").mkdirs()
    }

    private fun loadPackManifest(): PackManifest {
        val file = File(configDir, "pack_manifest.json")
        if (!file.exists()) {
            val paks = packsDir.listFiles()?.filter { it.extension.equals("pak", true) }?.map { it.name } ?: emptyList()
            return PackManifest(DEFAULT_KEY, paks, emptyMap(), emptyMap())
        }
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val key = obj.optString("key", DEFAULT_KEY)
            val packages = mutableListOf<String>()
            obj.optJSONArray("packages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i, "")
                    if (n.isNotBlank()) packages += n
                }
            }
            if (packages.isEmpty()) {
                packsDir.listFiles()?.filter { it.extension.equals("pak", true) }?.sortedBy { it.name }?.forEach {
                    packages += it.name
                }
            }

            val aliasesByPkg = hashMapOf<String, Map<String, String>>()
            obj.optJSONObject("aliases")?.let { aliasRoot ->
                aliasRoot.keys().forEach { pkg ->
                    val mapObj = aliasRoot.optJSONObject(pkg) ?: return@forEach
                    val map = hashMapOf<String, String>()
                    mapObj.keys().forEach { alias ->
                        val origin = mapObj.optString(alias, "")
                        if (origin.isNotBlank()) map[alias.lowercase(Locale.getDefault())] = origin
                    }
                    if (map.isNotEmpty()) aliasesByPkg[pkg.lowercase(Locale.getDefault())] = map
                }
            }
            val kindsByPkg = hashMapOf<String, String>()
            obj.optJSONObject("kinds")?.let { kindsRoot ->
                kindsRoot.keys().forEach { pkg ->
                    val kind = kindsRoot.optString(pkg, "")
                    if (kind.isNotBlank()) kindsByPkg[pkg.lowercase(Locale.getDefault())] = kind.lowercase(Locale.getDefault())
                }
            }
            PackManifest(key, packages, aliasesByPkg, kindsByPkg)
        } catch (_: Throwable) {
            val paks = packsDir.listFiles()?.filter { it.extension.equals("pak", true) }?.map { it.name } ?: emptyList()
            PackManifest(DEFAULT_KEY, paks, emptyMap(), emptyMap())
        }
    }

    private fun loadPackagesForSession(manifest: PackManifest) {
        val session = currentSessionDir ?: return
        val zipWorkDir = File(session, "pak_work_zip")
        val sourceCacheDir = File(session, "source_cache")
        zipWorkDir.mkdirs()
        sourceCacheDir.mkdirs()

        manifest.packages.forEachIndexed { packageIndex, pkgName ->
            val pakFile = File(if (File(pkgName).isAbsolute) pkgName else File(packsDir, pkgName).absolutePath)
            if (!pakFile.exists()) return@forEachIndexed

            val pkgFileLower = pakFile.name.lowercase(Locale.getDefault())
            val kind = (manifest.kindsByPackageLower[pkgFileLower] ?: "").lowercase(Locale.getDefault())
            val pakBase = pakFile.nameWithoutExtension.lowercase(Locale.getDefault())
            var isPrompt = kind == "prompt" ||
                pakBase.contains("prompt") ||
                pakBase.contains("tip") ||
                pakBase.contains("提示") ||
                pakBase.contains("发车通知")

            val zipPath = File(zipWorkDir, "${"%03d".format(packageIndex)}_${pakFile.nameWithoutExtension}.work.zip")
            if (!decryptPakToZipFile(pakFile, currentPackKey, zipPath)) return@forEachIndexed

            val aliasMap = manifest.aliasesByPackageLower[pkgFileLower] ?: emptyMap()
            var numericPromptCount = 0
            for (k in 1..6) {
                val wav = "$k.wav"
                val mp3 = "$k.mp3"
                if (aliasMap.values.any { v ->
                        val lv = v.lowercase(Locale.getDefault())
                        lv == wav || lv == mp3
                    }) {
                    numericPromptCount += 1
                }
            }
            if (numericPromptCount >= 3) isPrompt = true

            packages += PackageInfo(
                pakPath = pakFile.absolutePath,
                packageFileNameLower = pkgFileLower,
                packageKindLower = kind,
                isEnglishPack = kind == "concat_eng" || pakBase.contains("eng"),
                isTemplatePack = kind == "template" || pakBase.contains("template"),
                isPromptPack = isPrompt,
                zipPath = zipPath.absolutePath,
            )

            ZipFile(zipPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val aliasFile = File(entry.name).name
                    val aliasKey = aliasFile.lowercase(Locale.getDefault())
                    var logicalFile = aliasMap[aliasKey] ?: aliasFile
                    logicalFile = logicalFile.replace('\\', '/')
                    var suffix = File(logicalFile).extension.lowercase(Locale.getDefault())
                    if (suffix !in AUDIO_EXT) suffix = File(aliasFile).extension.lowercase(Locale.getDefault())
                    if (suffix !in AUDIO_EXT) continue

                    val baseName = File(logicalFile).name.ifBlank { aliasFile }
                    val stem = File(baseName).nameWithoutExtension.trim()
                    val logicalLower = logicalFile.lowercase(Locale.getDefault())

                    val src = SourceEntry(
                        packageIndex = packages.lastIndex,
                        zipEntryPath = entry.name,
                        zipEntryPathLower = entry.name.lowercase(Locale.getDefault()),
                        logicalPathLower = logicalLower,
                        baseFileName = baseName,
                        stem = stem,
                        suffix = suffix,
                        isEnglish = packages.last().isEnglishPack,
                        isTemplate = packages.last().isTemplatePack || logicalLower.startsWith("template/"),
                        isPrompt = packages.last().isPromptPack ||
                            logicalLower.contains("提示") ||
                            logicalLower.contains("转弯") ||
                            logicalLower.contains("让座") ||
                            logicalLower.contains("切换线路") ||
                            logicalLower.contains("进入公交线路选择模式") ||
                            logicalLower.contains("prompt") ||
                            logicalLower.contains("tip"),
                    )

                    val idx = sources.size
                    sources += src
                    updateBestResourceMap(src, idx)
                    updateStationRootIndex(src, idx)
                }
            }
        }
    }

    private fun updateBestResourceMap(source: SourceEntry, index: Int) {
        val pathKeys = listOf(source.zipEntryPathLower, source.logicalPathLower)
        pathKeys.forEach { key ->
            val old = bestResourceByLowerPath[key]
            if (old == null || resourceSourceScore(source) > resourceSourceScore(sources[old])) {
                bestResourceByLowerPath[key] = index
            }
        }
        val lowerName = source.baseFileName.lowercase(Locale.getDefault())
        val old2 = bestResourceByLowerName[lowerName]
        if (old2 == null || resourceSourceScore(source) > resourceSourceScore(sources[old2])) {
            bestResourceByLowerName[lowerName] = index
        }
    }

    private fun updateStationRootIndex(source: SourceEntry, index: Int) {
        val root = stationRootFromStem(source.stem)
        if (root.isBlank()) return
        val map = if (source.isEnglish) stationRootIndexEn else stationRootIndexZh
        map.getOrPut(root) { mutableListOf() }.add(index)
    }

    private fun stationRootFromStem(stem: String): String {
        val s = stem.trim()
        if (s.isEmpty()) return s

        val dotIdx = s.indexOf('\u00B7')
        if (dotIdx > 0) return s.substring(0, dotIdx).trim()

        Regex("^(.+?)\\(.+\\)$").matchEntire(s)?.let { return it.groupValues[1].trim() }
        Regex("^(.+?)（.+）$").matchEntire(s)?.let { return it.groupValues[1].trim() }
        Regex("^(.+?)#\\d*$").matchEntire(s)?.let { return it.groupValues[1].trim() }
        Regex("^(.+?)(\\d+)$").matchEntire(s)?.let { return it.groupValues[1].trim() }
        return s
    }

    private fun resourceSourceScore(source: SourceEntry): Int {
        var score = extensionPriority(source.suffix) * 100
        if (source.isTemplate) score += 1000
        if (source.isPrompt) score -= 200
        return score
    }

    private fun extensionPriority(suffix: String): Int {
        return when (suffix.lowercase(Locale.getDefault())) {
            "mp3" -> 6
            "wav" -> 5
            "m4a" -> 4
            "ogg" -> 3
            "flac" -> 2
            "aac" -> 1
            else -> 0
        }
    }

    private fun stationSourceScore(station: String, source: SourceEntry): Int {
        var score = extensionPriority(source.suffix) * 100
        if (source.stem == station) score += 10_000
        val escaped = Regex.escape(station)
        when {
            Regex("^$escaped#\\d*$").matches(source.stem) -> score += 9_000
            Regex("^$escaped\\d+$").matches(source.stem) -> score += 8_000
            Regex("^$escaped(?:\\u00B7.+|\\(.+\\)|\\uFF08.+\\uFF09)$").matches(source.stem) -> score += 7_000
        }
        score -= source.baseFileName.length
        return score
    }

    private fun collectStationSourceCandidates(
        stationName: String,
        isChinese: Boolean,
        options: SynthesisOptions,
    ): List<Int> {
        val clean = stationName.trim()
        if (clean.isEmpty()) return emptyList()

        val ranked = mutableListOf<Pair<Int, Int>>()
        for (i in sources.indices) {
            val s = sources[i]
            if (isChinese && s.isEnglish) continue
            if (!isChinese && !s.isEnglish) continue
            if (!isStationFileMatch(clean, s.stem)) continue
            ranked += i to stationSourceScore(clean, s)
        }
        if (ranked.isEmpty()) return emptyList()

        val filtered = if (options.highQuality) {
            val exact = ranked.filter { sources[it.first].stem == clean }
            if (exact.isNotEmpty()) exact else ranked
        } else {
            ranked
        }

        return filtered
            .sortedWith(
                compareByDescending<Pair<Int, Int>> { it.second }
                    .thenBy { sources[it.first].baseFileName.length }
                    .thenBy { sources[it.first].baseFileName.lowercase(Locale.getDefault()) }
            )
            .map { it.first }
    }

    private fun selectStationSourceIndex(
        stationName: String,
        isChinese: Boolean,
        options: SynthesisOptions,
        cache: MutableMap<String, Int>,
        stationPicker: ((StationPickRequest) -> Int?)? = null,
    ): Int {
        val clean = stationName.trim()
        if (clean.isEmpty()) return -1
        val cacheKey =
            "${if (isChinese) "zh" else "en"}|$clean|${if (options.highQuality) 1 else 0}|${if (options.silentSynthesis) 1 else 0}"
        cache[cacheKey]?.let { return it }

        val candidates = collectStationSourceCandidates(clean, isChinese, options)
        if (candidates.isEmpty()) {
            cache[cacheKey] = -1
            return -1
        }

        var best = candidates.first()
        if (!options.silentSynthesis && candidates.size > 1 && stationPicker != null) {
            val req = StationPickRequest(
                stationName = clean,
                isChinese = isChinese,
                candidates = candidates.map { idx ->
                    StationCandidate(
                        sourceIndex = idx,
                        displayName = sources[idx].baseFileName,
                    )
                },
                defaultSourceIndex = best,
            )
            val picked = runCatching { stationPicker(req) }.getOrNull()
            if (picked != null && picked in candidates) {
                best = picked
            }
        }

        cache[cacheKey] = best
        return best
    }

    private fun isStationFileMatch(stationName: String, baseName: String): Boolean {
        val cleanStation = stationName.trim()
        val cleanBase = baseName.trim()
        if (cleanStation.isEmpty() || cleanBase.isEmpty()) return false
        val pattern = Regex("^${Regex.escape(cleanStation)}(?:#\\d*|\\d+|\\u00B7.+|\\(.+\\)|\\uFF08.+\\uFF09)?$")
        return pattern.matches(cleanBase)
    }

    private fun materializeTemplate(
        cfg: TemplateConfig,
        missingResourceKeys: MutableList<String>,
        logs: MutableList<SynthesisLogEntry>,
    ): RuntimeTemplate {
        val map = linkedMapOf<String, SegmentRef>()
        cfg.resources.forEach { (key, fileName) ->
            val idx = resolveResourceSourceIndex(fileName)
            if (idx >= 0) {
                map[key] = SegmentRef.Source(idx)
            } else {
                missingResourceKeys += key
                logs += SynthesisLogEntry("WARN", "template resource missing: $key -> $fileName")
            }
        }
        return RuntimeTemplate(
            name = cfg.name,
            hasEnglish = cfg.hasEnglish,
            sequences = cfg.sequences,
            resources = map,
        )
    }

    private fun resolveResourceSourceIndex(fileName: String): Int {
        val normalized = fileName.replace('\\', '/').trim().lowercase(Locale.getDefault())
        val lowerName = File(normalized).name.lowercase(Locale.getDefault())
        if (lowerName.isBlank()) return -1

        val pathCandidates = linkedSetOf<String>()
        pathCandidates += normalized
        if (normalized.startsWith("template/")) pathCandidates += normalized.removePrefix("template/")

        var best = -1
        var bestScore = Int.MIN_VALUE
        pathCandidates.forEach { candidate ->
            val idx = bestResourceByLowerPath[candidate] ?: return@forEach
            val score = resourceSourceScore(sources[idx]) + 1000
            if (score > bestScore) {
                best = idx
                bestScore = score
            }
        }
        if (best < 0) best = bestResourceByLowerName[lowerName] ?: -1
        return best
    }

    private fun buildSequenceSegments(
        seqType: String,
        tpl: RuntimeTemplate,
        ctx: SynthesisContext,
        issues: MutableList<String>,
    ): List<SegmentRef> {
        val seq = tpl.sequences[seqType] ?: run {
            issues += "sequence not found: $seqType"
            return emptyList()
        }
        val out = mutableListOf<SegmentRef>()
        seq.forEach { token ->
            if (token.startsWith("$")) {
                val seg = when (token) {
                    "\$LINE_NAME", "\$LINE" -> ctx.lineAudioChn
                    "\$LINE_NAME_EN", "\$LINE_EN" -> ctx.lineAudioEng
                    "\$CURRENT_STATION" -> ctx.currentStationChn
                    "\$CURRENT_STATION_EN" -> ctx.currentStationEng
                    "\$NEXT_STATION" -> ctx.nextStationChn
                    "\$NEXT_STATION_EN" -> ctx.nextStationEng
                    "\$TERMINAL", "\$TERMINAL_STATION" -> ctx.terminalStationChn
                    "\$TERMINAL_EN" -> ctx.terminalStationEng
                    else -> null
                }
                if (seg != null) {
                    out += seg
                } else {
                    issues += "unresolved variable: $token"
                }
            } else {
                val seg = tpl.resources[token]
                if (seg != null) {
                    out += seg
                } else if (token.isNotBlank()) {
                    issues += "unresolved resource token: $token"
                }
            }
        }
        return out
    }

    private fun ensureSourceExtractedFile(sourceIndex: Int): File? {
        extractedSourcePathByIndex[sourceIndex]?.let {
            val f = File(it)
            if (f.exists()) return f
        }
        if (sourceIndex !in sources.indices) return null
        val source = sources[sourceIndex]
        val pkg = packages.getOrNull(source.packageIndex) ?: return null
        val zipFile = File(pkg.zipPath)
        if (!zipFile.exists()) return null
        val outDir = File(currentSessionDir, "source_cache")
        outDir.mkdirs()

        val hashHex = sha1Hex("${pkg.packageFileNameLower}|${source.zipEntryPath}")
        val outFile = File(outDir, "$hashHex.${source.suffix}")
        if (!outFile.exists()) {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(source.zipEntryPath) ?: return null
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
        }
        extractedSourcePathByIndex[sourceIndex] = outFile.absolutePath
        return outFile
    }

    private fun ensureSilence100msFile(): String {
        if (silence100msPath.isNotBlank() && File(silence100msPath).exists()) return silence100msPath
        val outDir = File(currentSessionDir, "source_cache")
        outDir.mkdirs()
        val out = File(outDir, "silence_100ms.wav")
        if (!out.exists()) out.writeBytes(makeSilentWav(100, 44100, 1, 16))
        silence100msPath = out.absolutePath
        return silence100msPath
    }

    private fun makeSilentWav(durationMs: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val frameBytes = channels * bytesPerSample
        val samples = ((sampleRate * durationMs) / 1000.0).toInt().coerceAtLeast(1)
        val dataSize = samples * frameBytes
        val chunkSize = 36 + dataSize
        val out = ByteArray(44 + dataSize)

        fun putIntLE(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte()
            out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShortLE(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        "RIFF".toByteArray().copyInto(out, 0)
        putIntLE(4, chunkSize)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        putIntLE(16, 16)
        putShortLE(20, 1)
        putShortLE(22, channels)
        putIntLE(24, sampleRate)
        putIntLE(28, sampleRate * frameBytes)
        putShortLE(32, frameBytes)
        putShortLE(34, bitsPerSample)
        "data".toByteArray().copyInto(out, 36)
        putIntLE(40, dataSize)
        return out
    }

    private fun routeIdFromLineFile(lineFile: File): String {
        return lineFile.nameWithoutExtension.replace(Regex("[^0-9a-zA-Z]"), "")
    }

    private fun parseFirstInt(text: String): Long? {
        val m = Regex("-?\\d+").find(text) ?: return null
        return m.value.toLongOrNull()
    }

    private fun readTxtStations(file: File): List<String> {
        return try {
            file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readXlsxStations(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val sharedStrings = mutableListOf<String>()
        val stations = mutableListOf<String>()
        ZipFile(file).use { zip ->
            zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    parseSharedStrings(input, sharedStrings)
                }
            }
            zip.getEntry("xl/worksheets/sheet1.xml")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    parseSheet1Stations(input, sharedStrings, stations)
                }
            }
        }
        return stations
    }

    private fun parseSharedStrings(input: java.io.InputStream, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(InputStreamReader(input, StandardCharsets.UTF_8))
        var event = parser.eventType
        var inSi = false
        val sb = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inSi = true
                        sb.setLength(0)
                    } else if (inSi && parser.name == "t") {
                        sb.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        out += sb.toString()
                        inSi = false
                        sb.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseSheet1Stations(
        input: java.io.InputStream,
        sharedStrings: List<String>,
        out: MutableList<String>,
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(InputStreamReader(input, StandardCharsets.UTF_8))
        var event = parser.eventType
        var isFirstCol = false
        var isShared = false
        var isInline = false
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r") ?: ""
                        isFirstCol = ref.startsWith("A")
                        val t = parser.getAttributeValue(null, "t") ?: ""
                        isShared = t == "s"
                        isInline = t == "inlineStr"
                    }
                    "v" -> {
                        if (isFirstCol) {
                            val value = parser.nextText().trim()
                            var station = value
                            if (isShared) {
                                val idx = value.toIntOrNull()
                                if (idx != null && idx in sharedStrings.indices) station = sharedStrings[idx]
                            }
                            station = station.trim()
                            if (station.isNotBlank()) out += station
                        }
                    }
                    "t" -> {
                        if (isFirstCol && isInline) {
                            val station = parser.nextText().trim()
                            if (station.isNotBlank()) out += station
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun copyAssetIfExists(assetPath: String, target: File) {
        if (target.exists() && target.length() > 0L) return
        try {
            context.assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        destDir.mkdirs()
        val children = context.assets.list(assetDir) ?: return
        for (child in children) {
            val childAssetPath = "$assetDir/$child"
            val nested = context.assets.list(childAssetPath)
            if (nested != null && nested.isNotEmpty()) {
                copyAssetDir(childAssetPath, File(destDir, child))
            } else {
                val outFile = File(destDir, child)
                if (outFile.exists() && outFile.length() > 0L) continue
                try {
                    context.assets.open(childAssetPath).use { input ->
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun unzipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                        zis.copyTo(bos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun decryptPakToZipFile(pakFile: File, keyText: String, zipOut: File): Boolean {
        if (!pakFile.exists()) return false
        FileInputStream(pakFile).use { input ->
            val magic = ByteArray(8)
            if (input.read(magic) != 8) return false
            if (!magic.contentEquals(PAK_MAGIC)) return false
            val nonce = ByteArray(16)
            if (input.read(nonce) != 16) return false

            zipOut.parentFile?.mkdirs()
            FileOutputStream(zipOut).use { output ->
                val keyBytes = keyText.toByteArray(StandardCharsets.UTF_8)
                val cipherBuf = ByteArray(1024 * 1024)
                var firstBytes = ByteArray(0)
                var counter = 0u
                var block = ByteArray(0)
                var blockPos = 32

                fun nextBlock(): ByteArray {
                    val md = MessageDigest.getInstance("SHA-256")
                    val ctr = byteArrayOf(
                        (counter.toInt() and 0xFF).toByte(),
                        ((counter.toInt() shr 8) and 0xFF).toByte(),
                        ((counter.toInt() shr 16) and 0xFF).toByte(),
                        ((counter.toInt() shr 24) and 0xFF).toByte(),
                    )
                    counter += 1u
                    md.update(keyBytes)
                    md.update(nonce)
                    md.update(ctr)
                    return md.digest()
                }

                while (true) {
                    val n = input.read(cipherBuf)
                    if (n <= 0) break
                    val plain = ByteArray(n)
                    for (i in 0 until n) {
                        if (blockPos >= block.size) {
                            block = nextBlock()
                            blockPos = 0
                        }
                        plain[i] = (cipherBuf[i].toInt() xor block[blockPos].toInt()).toByte()
                        blockPos += 1
                    }
                    if (firstBytes.size < 8) {
                        val need = 8 - firstBytes.size
                        val prefix = plain.copyOfRange(0, minOf(need, plain.size))
                        firstBytes += prefix
                    }
                    output.write(plain)
                }
                val looksZip = firstBytes.size >= 4 &&
                    firstBytes[0] == 'P'.code.toByte() &&
                    firstBytes[1] == 'K'.code.toByte()
                if (!looksZip) {
                    zipOut.delete()
                    return false
                }
            }
        }
        return true
    }

    private fun sha1Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package com.ldrwhc.secureaudioplayer

import java.io.File

data class TemplateConfig(
    val filePath: String,
    val name: String,
    val hasEnglish: Boolean,
    val resources: Map<String, String>,
    val sequences: Map<String, List<String>>,
)

data class PackManifest(
    val key: String,
    val packages: List<String>,
    val aliasesByPackageLower: Map<String, Map<String, String>>,
    val kindsByPackageLower: Map<String, String>,
)

data class PackageInfo(
    val pakPath: String,
    val packageFileNameLower: String,
    val packageKindLower: String,
    val isEnglishPack: Boolean,
    val isTemplatePack: Boolean,
    val isPromptPack: Boolean,
    val zipPath: String,
)

data class SourceEntry(
    val packageIndex: Int,
    val zipEntryPath: String,
    val zipEntryPathLower: String,
    val logicalPathLower: String,
    val baseFileName: String,
    val stem: String,
    val suffix: String,
    val isEnglish: Boolean,
    val isTemplate: Boolean,
    val isPrompt: Boolean,
)

sealed interface SegmentRef {
    data class Source(val sourceIndex: Int) : SegmentRef
    data class FilePath(val path: String) : SegmentRef
}

data class TrackItem(
    val title: String,
    val segments: List<SegmentRef>,
)

data class RuntimeTemplate(
    val name: String,
    val hasEnglish: Boolean,
    val sequences: Map<String, List<String>>,
    val resources: Map<String, SegmentRef>,
)

data class SynthesisContext(
    val lineAudioChn: SegmentRef?,
    val lineAudioEng: SegmentRef?,
    val currentStationChn: SegmentRef?,
    val currentStationEng: SegmentRef?,
    val nextStationChn: SegmentRef?,
    val nextStationEng: SegmentRef?,
    val terminalStationChn: SegmentRef?,
    val terminalStationEng: SegmentRef?,
)

data class SynthesisOptions(
    var externalBroadcast: Boolean = true,
    var includeNextStation: Boolean = true,
    var highQuality: Boolean = true,
    var missingEngUseChinese: Boolean = true,
)

data class BuildResult(
    val templates: List<TemplateConfig>,
    val lineFiles: List<File>,
    val tracks: List<TrackItem>,
    val missingResourceKeys: List<String>,
)

data class SynthesisLogEntry(
    val level: String,
    val message: String,
)

data class SynthesisBuildResult(
    val tracks: List<TrackItem>,
    val missingResourceKeys: List<String>,
    val logs: List<SynthesisLogEntry>,
    val incompleteTrackCount: Int,
    val matchedStationCount: Int,
    val missingStationCount: Int,
)

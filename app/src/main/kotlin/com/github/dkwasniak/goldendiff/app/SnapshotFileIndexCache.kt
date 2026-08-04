package com.github.dkwasniak.goldendiff.app

import com.github.dkwasniak.goldendiff.match.MatchMode
import com.github.dkwasniak.goldendiff.project.SnapshotCase
import com.github.dkwasniak.goldendiff.project.SnapshotFileIndex
import com.github.dkwasniak.goldendiff.project.SnapshotFileStatus
import com.github.dkwasniak.goldendiff.project.SnapshotIndexFingerprint
import com.github.dkwasniak.goldendiff.project.SnapshotSourceFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk cache of the last known [SnapshotFileIndex] tree, keyed per project, so the app can show the
 * previous run's file/case list immediately on launch instead of an empty pane while the real scan
 * (which still runs, and still shows the "Indexing" banner) catches up.
 *
 * Deliberately a hand-written [org.json] mapping rather than a reflection-based serializer: every
 * field here is already a primitive, string, list, or enum, so there is nothing generic to gain, and
 * this keeps the cache format stable across refactors of [SnapshotFileIndex] itself.
 */
internal object SnapshotFileIndexCache {

    fun toJson(index: SnapshotFileIndex): JSONObject = JSONObject().apply {
        put("root", index.root.absolutePath)
        put("files", JSONArray(index.files.map(::fileToJson)))
        put("failedFilesByDirectory", JSONObject(index.failedFilesByDirectory.mapValues { it.value as Any }))
        put("fingerprint", fingerprintToJson(index.fingerprint))
        put("goldenInventory", JSONArray(index.goldenInventory))
    }

    fun fromJson(json: JSONObject): SnapshotFileIndex? = runCatching {
        SnapshotFileIndex(
            root = File(json.getString("root")),
            files = json.getJSONArray("files").mapObjects(::fileFromJson),
            failedFilesByDirectory = json.getJSONObject("failedFilesByDirectory").let { obj ->
                obj.keys().asSequence().associateWith { obj.getInt(it) }
            },
            fingerprint = fingerprintFromJson(json.getJSONObject("fingerprint")),
            goldenInventory = json.getJSONArray("goldenInventory").mapStrings().toSet(),
        )
    }.getOrNull()

    private fun fileToJson(file: SnapshotSourceFile): JSONObject = JSONObject().apply {
        put("relativePath", file.relativePath)
        put("goldenPaths", JSONArray(file.goldenPaths))
        put("status", file.status.name)
        put("failedCount", file.failedCount)
        put("cases", JSONArray(file.cases.map(::caseToJson)))
        put("unmatchedGoldenPaths", JSONArray(file.unmatchedGoldenPaths))
        put("statuses", JSONArray(file.statuses.map { it.name }))
    }

    private fun fileFromJson(json: JSONObject): SnapshotSourceFile = SnapshotSourceFile(
        relativePath = json.getString("relativePath"),
        goldenPaths = json.getJSONArray("goldenPaths").mapStrings(),
        status = SnapshotFileStatus.valueOf(json.getString("status")),
        failedCount = json.getInt("failedCount"),
        cases = json.getJSONArray("cases").mapObjects(::caseFromJson),
        unmatchedGoldenPaths = json.getJSONArray("unmatchedGoldenPaths").mapStrings(),
        statuses = json.getJSONArray("statuses").mapStrings().map(SnapshotFileStatus::valueOf).toSet(),
    )

    private fun caseToJson(case: SnapshotCase): JSONObject = JSONObject().apply {
        put("id", case.id)
        put("methodName", case.methodName)
        put("generatorPath", case.generatorPath)
        put("className", case.className ?: JSONObject.NULL)
        put("goldenPaths", JSONArray(case.goldenPaths))
        put("status", case.status.name)
        put("failedCount", case.failedCount)
        put("statuses", JSONArray(case.statuses.map { it.name }))
    }

    private fun caseFromJson(json: JSONObject): SnapshotCase = SnapshotCase(
        id = json.getString("id"),
        methodName = json.getString("methodName"),
        generatorPath = json.getString("generatorPath"),
        className = json.opt("className")?.takeUnless { it == JSONObject.NULL } as? String,
        goldenPaths = json.getJSONArray("goldenPaths").mapStrings(),
        status = SnapshotFileStatus.valueOf(json.getString("status")),
        failedCount = json.getInt("failedCount"),
        statuses = json.getJSONArray("statuses").mapStrings().map(SnapshotFileStatus::valueOf).toSet(),
    )

    private fun fingerprintToJson(fingerprint: SnapshotIndexFingerprint): JSONObject = JSONObject().apply {
        put("projectPath", fingerprint.projectPath)
        put("goldenPaths", JSONArray(fingerprint.goldenPaths))
        put("matchMode", fingerprint.matchMode.name)
        put("annotatedFunctionRegex", fingerprint.annotatedFunctionRegex)
        put("goldenFilePatterns", JSONArray(fingerprint.goldenFilePatterns))
        put("excludedSuffixes", JSONArray(fingerprint.excludedSuffixes))
    }

    private fun fingerprintFromJson(json: JSONObject): SnapshotIndexFingerprint = SnapshotIndexFingerprint(
        projectPath = json.getString("projectPath"),
        goldenPaths = json.getJSONArray("goldenPaths").mapStrings(),
        matchMode = MatchMode.fromName(json.getString("matchMode")),
        annotatedFunctionRegex = json.getString("annotatedFunctionRegex"),
        goldenFilePatterns = json.getJSONArray("goldenFilePatterns").mapStrings(),
        excludedSuffixes = json.getJSONArray("excludedSuffixes").mapStrings(),
    )

    private fun JSONArray.mapStrings(): List<String> = (0 until length()).map { getString(it) }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}

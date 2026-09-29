package dev.friction.spike

import androidx.compose.runtime.mutableStateOf

/** UI snapshots must never share the evaluator's mutable state or nested counters. */
class FocusPresentation {
    val state = mutableStateOf<FocusState?>(null)
    fun publish(live: FocusState): FocusState {
        val snapshot = FocusCodec.decode(FocusCodec.encode(live))
        state.value = snapshot
        return snapshot
    }
}

internal data class AppListRows(val apps: List<Pair<String, String>>, val dividerBefore: String?)

internal fun appListRows(apps: List<Pair<String, String>>, rules: Map<String, AppRule>, query: String): AppListRows {
    fun restricted(pkg: String) = rules[pkg]?.policy?.let { it != Policy.Allowed } ?: false
    val search = query.trim()
    val sorted = apps.distinctBy { it.second }
        .filter { it.first.contains(search, true) || it.second.contains(search, true) }
        .sortedWith(compareBy<Pair<String, String>> { !restricted(it.second) }
            .thenBy { it.first.lowercase() }.thenBy { it.second })
    val boundary = sorted.indexOfFirst { !restricted(it.second) }
    val divider = if (search.isEmpty() && boundary > 0) sorted[boundary].second else null
    return AppListRows(sorted, divider)
}

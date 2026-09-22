package com.lastwave.app.ui.player

import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable

/**
 * Display text for each syllable: the syllable text plus the karaoke
 * separator space the canvas renderer draws after it.
 *
 * This is the single source of truth for the separator rule — it mirrors
 * the renderer mapping's inline rule exactly (word spacing is
 * display-only: no timing change, skipped for spaceless CJK lines, for
 * providers that already kept spacing, and before Apple Music `part`
 * continuations like "with"+"drawals"). Both the renderer mapping and the
 * [splitKaraokeToFit] word grouping below must agree on where words end,
 * otherwise a "word" here could straddle a wrap point the canvas cannot
 * break at (or vice versa).
 */
fun renderedSyllableContents(units: List<LyricSyllable>, needsSpacing: Boolean): List<String> {
    return units.mapIndexed { index, syl ->
        val next = units.getOrNull(index + 1)
        val separator = if (needsSpacing &&
            index < units.lastIndex &&
            !syl.text.endsWith(' ') &&
            !syl.text.endsWith('\u00A0') &&
            next?.appendToPrevious != true &&
            (next == null || (!next.text.startsWith(' ') && !next.text.startsWith('\u00A0')))
        ) " " else ""
        syl.text + separator
    }
}

/**
 * Balanced word-wrap packing (Knuth-Plass-lite): partitions [widths] into
 * the fewest rows that each fit [maxWidth], minimizing the sum of squared
 * leftover space so rows come out even ("Where two and two alone" /
 * "will never meet") instead of ragged ("Where two and two alone will" /
 * "meet"). Mirrors the cost function of the karaoke canvas's own balancer.
 *
 * Units wider than [maxWidth] can never share a row — the greedy fallback
 * isolates each on its own row rather than failing outright.
 */
fun packBalanced(widths: List<Float>, maxWidth: Float): List<IntRange> {
    if (widths.isEmpty()) return emptyList()
    val n = widths.size
    val costs = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val breaks = IntArray(n + 1)
    costs[0] = 0.0
    for (i in 1..n) {
        var rowWidth = 0f
        for (j in i downTo 1) {
            rowWidth += widths[j - 1]
            if (rowWidth > maxWidth) break
            val badness = (maxWidth - rowWidth).toDouble().let { it * it }
            if (costs[j - 1] + badness < costs[i]) {
                costs[i] = costs[j - 1] + badness
                breaks[i] = j - 1
            }
        }
    }
    if (costs[n].isInfinite()) {
        val rows = mutableListOf<IntRange>()
        var start = 0
        var rowWidth = 0f
        widths.forEachIndexed { index, unitWidth ->
            if (rowWidth > 0f && rowWidth + unitWidth > maxWidth) {
                rows.add(start..index - 1)
                start = index
                rowWidth = 0f
            }
            rowWidth += unitWidth
        }
        rows.add(start..n - 1)
        return rows
    }
    val rows = mutableListOf<IntRange>()
    var index = n
    while (index > 0) {
        val start = breaks[index]
        rows.add(0, start..index - 1)
        index = start
    }
    return rows
}

/**
 * Splits an overlong word-sync line into balanced, independently-timed
 * sub-lines that each fit [maxWidthPx] when drawn with the karaoke style.
 *
 * Why this exists: the karaoke canvas pre-computes its row wrapping once per
 * line (unstated `remember` with no width key), so a line measured against a
 * transient width — first composition during the panel crossfade, an async
 * layout-cache fill, a provider upgrade reusing an item slot — can freeze as
 * one giant row that runs past the screen edge (Apple/Paxsenix word-sync
 * lines are the usual victims: long enough to overflow, short enough to
 * look like they should fit). Short sub-lines render as identical single
 * rows under ANY measured width, so the symptom is impossible by
 * construction, for every provider.
 *
 * Timing stays exact: chunks partition the syllable sequence in order, each
 * chunk's range comes from its own first/last syllable timestamps, and
 * background syllables ride with the chunk containing their start, so
 * karaoke fill, line focus and auto-scroll behave as before — just on
 * shorter rows. Returns `listOf(this)` untouched when the line already fits
 * or cannot be split (no syllables, single unbreakable word).
 */
fun LyricLine.splitKaraokeToFit(
    maxWidthPx: Float,
    measureWidth: (String) -> Float,
): List<LyricLine> {
    if (!hasSyllables || maxWidthPx <= 0f) return listOf(this)
    val leads = syllables.filter { !it.isBackground }
    val units = leads.ifEmpty { syllables }
    val backs = if (leads.isNotEmpty()) syllables.filter { it.isBackground } else emptyList()
    val needsSpacing = text.contains(' ') || text.contains('\u00A0')
    val rendered = renderedSyllableContents(units, needsSpacing)

    // Word grouping mirrors the canvas renderer's own grouping (a syllable
    // whose drawn text ends in whitespace ends the word), so every chunk
    // boundary below is a point the canvas can also break at.
    val words = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    rendered.forEachIndexed { index, content ->
        current.add(index)
        if (content.trimEnd().length < content.length) {
            words.add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) words.add(current)

    val wordTexts = words.map { idxs -> idxs.joinToString("") { rendered[it] } }
    val wordWidths = wordTexts.map(measureWidth)
    if (wordWidths.sum() <= maxWidthPx) return listOf(this)

    // Over-wide words (long compounds) cannot share any row — explode them
    // into syllable units first so the packer always has a feasible layout.
    val packUnits = mutableListOf<List<Int>>()
    val packWidths = mutableListOf<Float>()
    words.forEachIndexed { wordIndex, idxs ->
        if (wordWidths[wordIndex] <= maxWidthPx) {
            packUnits.add(idxs)
            packWidths.add(wordWidths[wordIndex])
        } else {
            idxs.forEach { unitIndex ->
                packUnits.add(listOf(unitIndex))
                packWidths.add(measureWidth(rendered[unitIndex]))
            }
        }
    }
    val rows = packBalanced(packWidths, maxWidthPx)
    if (rows.size <= 1) return listOf(this)

    val chunkUnitIdxs = rows.map { row -> row.flatMap { packUnits[it] } }
    val chunkRanges = chunkUnitIdxs.map { idxs ->
        val chunkLeads = idxs.map { units[it] }
        val start = chunkLeads.minOf { it.timeMs }
        val end = chunkLeads.maxOf { it.timeMs + it.durationMs }
        start to end
    }
    val backsByChunk = List(chunkUnitIdxs.size) { mutableListOf<LyricSyllable>() }
    backs.forEach { back ->
        val target = chunkRanges.indexOfFirst { back.timeMs >= it.first && back.timeMs < it.second }
            .takeIf { it >= 0 }
            ?: if (back.timeMs < chunkRanges.first().first) 0 else chunkRanges.lastIndex
        backsByChunk[target].add(back)
    }

    val originalEnd = timeMs + durationMs
    return chunkUnitIdxs.mapIndexed { chunkIndex, idxs ->
        val chunkLeads = idxs.map { units[it] }
        val ownedBacks = backsByChunk[chunkIndex]
        val containedStart = minOf(chunkLeads.minOf { it.timeMs }, ownedBacks.minOfOrNull { it.timeMs } ?: Long.MAX_VALUE)
        val containedEnd = maxOf(
            chunkLeads.maxOf { it.timeMs + it.durationMs },
            ownedBacks.maxOfOrNull { it.timeMs + it.durationMs } ?: Long.MIN_VALUE,
        )
        // Preserve the original line's full coverage: first chunk opens no
        // later than the line did, chunks join contiguously, last chunk
        // closes no earlier than the line did — focus never gaps or clips.
        val nextStart = chunkUnitIdxs.getOrNull(chunkIndex + 1)
            ?.let { nextIdxs -> nextIdxs.map { units[it] }.minOf { it.timeMs } }
        val start = if (chunkIndex == 0) minOf(containedStart, timeMs) else containedStart
        val end = when {
            nextStart != null -> maxOf(containedEnd, nextStart)
            else -> maxOf(containedEnd, originalEnd)
        }
        LyricLine(
            timeMs = start,
            durationMs = (end - start).coerceAtLeast(0L),
            text = idxs.joinToString("") { rendered[it] }.trimEnd(),
            syllables = (chunkLeads + ownedBacks).sortedBy { it.timeMs },
            transliteration = if (chunkIndex == 0) transliteration else null,
        )
    }
}

package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Tab separated `mariadb --batch` output: a header row, then the rows. */
class SqlTable(val columns: List<String>, val rows: List<List<String>>, val truncated: Boolean) {
    companion object {
        const val MAX_ROWS = 500

        /** Null when the output has no columns, e.g. after an INSERT. */
        fun parse(output: String): SqlTable? {
            val lines = output.lines().filter { it.isNotEmpty() }
            val header = lines.firstOrNull()?.split('\t')?.map(::unescape) ?: return null
            val body = lines.drop(1)
            return SqlTable(header, body.take(MAX_ROWS).map { row -> row.split('\t').map(::unescape) }, body.size > MAX_ROWS)
        }

        private fun unescape(value: String) = if ('\\' !in value) value else buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    append(when (val next = value[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'; else -> next })
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

@Composable
fun ResultTable(table: SqlTable, modifier: Modifier = Modifier) {
    val widths = remember(table) {
        table.columns.indices.map { column ->
            val longest = (table.rows.map { it.getOrNull(column).orEmpty().lines().first().length } + table.columns[column].length).max()
            (longest * 8 + 28).coerceIn(72, 360).dp
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        Column(Modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.columns.forEachIndexed { i, name ->
                    Text(
                        name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths[i]).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            table.rows.forEachIndexed { index, row ->
                Row(Modifier.background(if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)) {
                    table.columns.indices.forEach { i ->
                        val value = row.getOrNull(i).orEmpty()
                        val isNull = value == "NULL"
                        Text(
                            value.lines().first() + if ('\n' in value) " …" else "",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = if (isNull) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths[i]).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        Text(
            "${table.rows.size}${if (table.truncated) "+" else ""} row${if (table.rows.size == 1) "" else "s"}" + if (table.truncated) " (first ${SqlTable.MAX_ROWS} shown)" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
